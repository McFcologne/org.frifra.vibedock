package org.frifra.vibedock.server;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The design preview can simulate a display on its battery; nothing is saved. */
@SpringBootTest(properties={"vibedock.admin-password=test-password-123","vibedock.polling=false","vibedock.log-dir=target/test-logs"})
@AutoConfigureMockMvc
class PreviewBatteryTest {
    @Autowired MockMvc mvc;
    @Autowired ClientRegistry clients;
    @Autowired SettingsStore store;
    @Autowired UsageService usage;
    @org.springframework.test.context.bean.override.mockito.MockitoBean ProviderClient providers;
    @org.springframework.test.context.bean.override.mockito.MockitoBean BrowserLogin login;
    @BeforeEach void settings() throws Exception {
        store.save(new SettingsStore.Settings(java.util.List.of("127.0.0.1/32"),new SettingsStore.Credential("",""),new SettingsStore.Credential("",""),java.util.List.of()));
        usage.forgetPacing();usage.configurationChanged();
    }
    private static final String BODY="""
        {"mood":"-","scheme":"classic_mac","weatherLocation":"","layout":{"bands":[{"type":"usage","sources":["claude"]},{"type":"usage","sources":["codex","claude"]},{"type":"context","moodLeft":true}]},"inherit":false}
        """;
    private byte[] preview(String battery) throws Exception {
        var request=post("/admin/design-preview.png").contentType("application/json").content(BODY).with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;});
        if(battery!=null)request.param("battery",battery);
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }
    @Test void theSimulatedBatteryChangesThePreviewByLevelAndNeverTheSavedDesign() throws Exception {
        var before=clients.defaultDesign();
        byte[] plain=preview(null);
        Assertions.assertArrayEquals(plain,preview(""),"empty keeps the preview as it was");
        byte[] sixty=preview("60"),twenty=preview("20"),unknown=preview("unknown");
        Assertions.assertFalse(java.util.Arrays.equals(plain,sixty));
        Assertions.assertFalse(java.util.Arrays.equals(sixty,twenty));
        Assertions.assertFalse(java.util.Arrays.equals(sixty,unknown));
        Assertions.assertEquals(before,clients.defaultDesign());
    }
    @Test void aBatteryLevelOutOfRangeIsRefused() throws Exception {
        var request=post("/admin/design-preview.png").param("battery","101").contentType("application/json").content(BODY).with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;});
        mvc.perform(request).andExpect(status().isBadRequest());
        Assertions.assertNull(DesignController.simulatedBattery(null));Assertions.assertNull(DesignController.simulatedBattery(" "));
        Assertions.assertEquals(new StatusImage.Battery(null),DesignController.simulatedBattery("unknown"));
        Assertions.assertEquals(new StatusImage.Battery(0),DesignController.simulatedBattery("0"));
        Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,()->DesignController.simulatedBattery("abc"));
    }
}
