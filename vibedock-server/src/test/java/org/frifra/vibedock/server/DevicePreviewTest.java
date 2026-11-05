package org.frifra.vibedock.server;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The page of a display shows its picture straight from the server, with the token the server announced. */
@SpringBootTest(properties={"vibedock.admin-password=test-password-123","vibedock.polling=false","vibedock.log-dir=target/test-logs"})
@AutoConfigureMockMvc
class DevicePreviewTest {
    @Autowired MockMvc mvc;
    @Autowired ClientRegistry clients;
    @Autowired SettingsStore store;
    @Autowired UsageService usage;
    @MockitoBean ProviderClient providers;
    @MockitoBean BrowserLogin login;
    @BeforeEach void settings() throws Exception {
        store.save(new SettingsStore.Settings(List.of("127.0.0.1/32"),new SettingsStore.Credential("",""),new SettingsStore.Credential("",""),List.of()));
        usage.forgetPacing();usage.configurationChanged();
    }
    @Test void thePullAnnouncesTheTokenAndOnlyItOpensThePicture() throws Exception {
        String id="vibedock-preview-test",other="vibedock-preview-other",key="ab".repeat(32);
        clients.paired(id,"Shelf",key,"192.168.1.30",80);clients.paired(other,"Desk","cd".repeat(32),"192.168.1.31",80);
        try{
            String token=mvc.perform(get("/api/v1/clients/"+id+"/image").header("Authorization","Bearer "+key).with(r->{r.setLocalPort(8080);return r;}))
                    .andExpect(status().isOk()).andExpect(header().string("X-VibeDock-Preview-Token",org.hamcrest.Matchers.matchesPattern("[a-f0-9]{32}")))
                    .andReturn().getResponse().getHeader("X-VibeDock-Preview-Token");
            Assertions.assertNotEquals(token,clients.previewToken(other),"every display has its own token");
            Assertions.assertFalse(token.contains(key.substring(0,8)),"the key is not in it");
            mvc.perform(get("/api/v1/clients/"+id+"/preview.png").param("t",token).with(r->{r.setLocalPort(8080);return r;}))
                    .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
            // Wrong, missing, malformed or another display's token, and an unknown display: all the same 404.
            for(String bad:new String[]{"0".repeat(32),"zz",clients.previewToken(other)})
                mvc.perform(get("/api/v1/clients/"+id+"/preview.png").param("t",bad).with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/clients/"+id+"/preview.png").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/clients/vibedock-nobody-here/preview.png").param("t",token).with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
            // Not on the administration port.
            mvc.perform(get("/api/v1/clients/"+id+"/preview.png").param("t",token).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isNotFound());
        }finally{clients.remove(id);clients.remove(other);}
    }
}
