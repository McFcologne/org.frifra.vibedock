package org.frifra.vibedock.server;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties={"vibedock.admin-user=admin","vibedock.admin-password=remember-original-password","vibedock.admin-port=10001","vibedock.polling=false","spring.config.import=optional:file:missing-remember-env[.properties]"})
@AutoConfigureMockMvc
class AdminRememberMeTest {
    static Path data;
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) throws Exception {
        data=Files.createTempDirectory("vibedock-remember-test-");r.add("vibedock.data-dir",data::toString);r.add("vibedock.log-dir",()->data.resolve("logs").toString());
    }
    @Autowired MockMvc mvc;@Autowired AdminAccount account;
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder atAdmin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request){
        return request.with(r->{r.setLocalPort(10001);return r;});
    }
    Cookie login(boolean remember,boolean secure) throws Exception {
        var request=post("/login").secure(secure).param("username","admin").param("password","remember-original-password").with(csrf());
        if(remember)request.param("remember-me","on");
        var result=mvc.perform(atAdmin(request)).andExpect(redirectedUrl("/admin")).andReturn();
        return result.getResponse().getCookie(AdminRememberMe.COOKIE);
    }
    @Test void persistentCookieRestoresAuthenticationAndLogoutRevokesItWithoutASession() throws Exception {
        Cookie cookie=login(true,false),other=login(true,false);
        assertNotNull(cookie);assertEquals(AdminLoginTokens.MAX_AGE,cookie.getMaxAge());assertTrue(cookie.isHttpOnly());assertFalse(cookie.getSecure());assertEquals("Strict",cookie.getAttribute("SameSite"));
        mvc.perform(atAdmin(get("/admin").cookie(cookie))).andExpect(status().isOk());
        // No session attached: logout still revokes the persistent credential, including a copied cookie.
        mvc.perform(atAdmin(get("/logout").accept("text/html").cookie(cookie))).andExpect(redirectedUrl("/login?logout"));
        mvc.perform(atAdmin(get("/admin").accept("text/html").cookie(cookie))).andExpect(redirectedUrl("/login"));
        mvc.perform(atAdmin(get("/admin").cookie(other))).andExpect(status().isOk());
    }
    @Test void uncheckedLoginHasNoPersistentCookieAndHttpsCookiesAreSecure() throws Exception {
        Cookie disabled=login(false,false);assertTrue(disabled==null||disabled.getMaxAge()==0);
        assertTrue(login(true,true).getSecure());
        var proxied=mvc.perform(atAdmin(post("/login").header("X-Forwarded-Proto","https").with(csrf()).param("username","admin").param("password","remember-original-password").param("remember-me","on"))).andExpect(redirectedUrl("/admin")).andReturn();
        assertTrue(proxied.getResponse().getCookie(AdminRememberMe.COOKIE).getSecure());
        mvc.perform(atAdmin(get("/login"))).andExpect(content().string(org.hamcrest.Matchers.containsString("Stay signed in")));
        mvc.perform(atAdmin(get("/admin").accept("text/html"))).andExpect(redirectedUrl("/login"));
    }
    @Test void tamperedCookiesCannotAuthenticateAndCsrfStillApplies() throws Exception {
        Cookie cookie=login(true,false);
        mvc.perform(atAdmin(get("/admin").accept("text/html").cookie(new Cookie(AdminRememberMe.COOKIE,"invalid-token")))).andExpect(redirectedUrl("/login"));
        mvc.perform(atAdmin(post("/admin/refresh").cookie(cookie))).andExpect(status().isForbidden());
    }
    @Test void passwordChangeAndResetInvalidatePersistentLoginsAndTheirSessions() throws Exception {
        Cookie first=login(true,false),second=login(true,false);
        var restored=mvc.perform(atAdmin(get("/admin").cookie(first))).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)restored.getRequest().getSession(false);assertNotNull(session);
        try{
            account.change("remember-original-password","remember-new-password","remember-new-password");
            mvc.perform(atAdmin(get("/admin").accept("text/html").cookie(first))).andExpect(redirectedUrl("/login"));
            mvc.perform(atAdmin(get("/admin").accept("text/html").cookie(second))).andExpect(redirectedUrl("/login"));
            mvc.perform(atAdmin(get("/admin").session(session))).andExpect(redirectedUrl("/login?expired"));
            var newLogin=mvc.perform(atAdmin(post("/login").with(csrf()).param("username","admin").param("password","remember-new-password").param("remember-me","on"))).andExpect(redirectedUrl("/admin")).andReturn().getResponse().getCookie(AdminRememberMe.COOKIE);
            account.reset("remember-new-password");
            mvc.perform(atAdmin(get("/admin").accept("text/html").cookie(newLogin))).andExpect(redirectedUrl("/login"));
        }finally{if(account.overridden())account.reset("remember-new-password");}
    }
}
