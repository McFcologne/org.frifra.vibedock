package org.frifra.vibedock.server;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties={"vibedock.admin-user=admin","vibedock.admin-password=synthetic-events-only","vibedock.admin-port=10001","vibedock.polling=false","spring.config.import=optional:file:missing-events-env[.properties]"})
@AutoConfigureMockMvc
class AdminEventsSecurityTest {
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) throws Exception {
        Path dir=Files.createTempDirectory("vibedock-events-test-");r.add("vibedock.data-dir",dir::toString);r.add("vibedock.log-dir",()->dir.resolve("logs").toString());
    }
    @Autowired MockMvc mvc;@Autowired AdminEvents events;
    @Autowired org.springframework.security.core.session.SessionRegistry sessions;
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder atAdmin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request){
        return request.with(r->{r.setLocalPort(10001);return r;});
    }
    private MockHttpSession signIn() throws Exception {
        return (MockHttpSession)mvc.perform(atAdmin(post("/login").with(csrf()).param("username","admin").param("password","synthetic-events-only")))
                .andExpect(redirectedUrl("/admin")).andReturn().getRequest().getSession(false);
    }
    @SuppressWarnings("unchecked") private SseEmitter emitter(){return ((List<SseEmitter>)ReflectionTestUtils.getField(events,"pages")).getLast();}
    private MvcResult open(MockHttpSession session) throws Exception {
        var result=mvc.perform(atAdmin(get("/admin/events").session(session).accept("text/event-stream")))
                .andExpect(request().asyncStarted()).andReturn();
        events.keepAlive();assertTrue(result.getResponse().isCommitted());return result;
    }
    @Test void anAcceptedStreamCanFinishAfterItsSessionExpires() throws Exception {
        var session=signIn();var result=open(session);var page=emitter();session.invalidate();page.complete();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(header().doesNotExist("Location"));
        assertNull(result.getRequest().getSession(false));assertEquals(0,events.open());
    }
    @Test void aDisconnectedStreamDoesNotBecomeAStorageRedirectOrSecurityError() throws Exception {
        var session=signIn();var result=open(session);var page=emitter();session.invalidate();
        page.completeWithError(new org.springframework.web.context.request.async.AsyncRequestNotUsableException("ServletOutputStream failed to write: Broken pipe"));
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(header().doesNotExist("Location"));
        assertNull(result.getRequest().getSession(false));assertEquals(0,events.open());
    }
    @Test void finishingAStreamDoesNotRedirectWhenTheRegisteredSessionWasRevoked() throws Exception {
        var session=signIn();var result=open(session);var page=emitter();
        sessions.getAllPrincipals().forEach(p->sessions.getAllSessions(p,false).forEach(org.springframework.security.core.session.SessionInformation::expireNow));
        page.complete();mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(header().doesNotExist("Location"));
        mvc.perform(atAdmin(get("/admin/events").session(session))).andExpect(redirectedUrl("/login?expired"));
    }
    @Test void newAndUnmarkedAsyncRequestsStillRequireAuthentication() throws Exception {
        mvc.perform(atAdmin(get("/admin/events").accept("text/event-stream"))).andExpect(redirectedUrl("/login"));
        mvc.perform(atAdmin(get("/admin/events").accept("application/json").with(r->{r.setDispatcherType(DispatcherType.ASYNC);return r;})))
                .andExpect(status().isUnauthorized());
        mvc.perform(atAdmin(get("/admin/events").accept("application/json").requestAttr(AdminEvents.ACCEPTED,true)))
                .andExpect(status().isUnauthorized());
        mvc.perform(atAdmin(get("/admin/server").accept("application/json").requestAttr(AdminEvents.ACCEPTED,true)
                .with(r->{r.setDispatcherType(DispatcherType.ASYNC);return r;}))).andExpect(status().isUnauthorized());
        assertEquals(0,events.open());
    }
}
