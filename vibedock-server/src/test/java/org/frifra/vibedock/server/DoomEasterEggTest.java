package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The "can it play doom?" link and its dialog appear for the Pico-LCD-1.3 only. */
@SpringBootTest(properties={"vibedock.admin-auth=none","vibedock.admin-password=","vibedock.polling=false"})
@AutoConfigureMockMvc
class DoomEasterEggTest {
    static Path data;
    @DynamicPropertySource static void directory(DynamicPropertyRegistry r) throws Exception {
        data=Files.createTempDirectory("vibedock-doom-");r.add("vibedock.data-dir",data::toString);
        r.add("vibedock.log-dir",Files.createDirectories(data.resolve("logs"))::toString);
    }
    @Autowired MockMvc mvc;@Autowired ClientRegistry clients;
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder admin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b){return b.with(r->{r.setLocalPort(10001);return r;});}
    @Test void onlyThePicoCardOffersDoom() throws Exception {
        mvc.perform(admin(get("/admin/devices"))).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("can it play doom?"))))
                .andExpect(content().string(not(containsString("doom-dialog"))));
        clients.paired("vibedock-0a1b2c000051","Pico","ab".repeat(32),"192.168.1.71",80,"rpi-pico2w-lcd-13");
        mvc.perform(admin(get("/admin/devices"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("data-doom>can it play doom?</button>")))
                .andExpect(content().string(containsString("id=\"doom-dialog\"")))
                .andExpect(content().string(containsString("releases/download/doom-pico/vibedock-doom-pico2w-lcd-13.uf2")))
                .andExpect(content().string(containsString("doom-popup.js")));
    }
}
