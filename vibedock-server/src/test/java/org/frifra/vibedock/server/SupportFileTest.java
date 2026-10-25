package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** The support file: created encrypted with a one-time key, secrets removed, opened again only with that key. */
@SpringBootTest(properties={"vibedock.admin-auth=none","vibedock.admin-password=","vibedock.polling=false"})
@AutoConfigureMockMvc
class SupportFileTest {
    static Path logs;
    @DynamicPropertySource static void directory(DynamicPropertyRegistry r) throws Exception {
        var dir=Files.createTempDirectory("vibedock-support-");r.add("vibedock.data-dir",dir::toString);
        logs=Files.createDirectories(dir.resolve("logs"));r.add("vibedock.log-dir",logs::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired ClientRegistry clients;
    @Autowired DeviceLogs deviceLogs;
    final ObjectMapper json=JsonCompatibility.mapper();

    @Autowired DeviceDiagnostics diagnostics;
    @Test void troubleshootingShowsHowOldTheNewestLogIsAndAsksForCurrentOnes() throws Exception {
        String id="vibedock-fresh-test";
        clients.paired(id,"Shelf","cd".repeat(32),"192.168.1.41",80,"waveshare-c6-touch-lcd-169");
        try{
            clients.pulled(id,"192.168.1.41",true,"0.1.1-22-g1234567",null);
            deviceLogs.save(id,"net","0.1.1-20-g7654321","waveshare-c6-touch-lcd-169",List.of("Start v0.1.1-20"),"0badc0de",1980L);
            diagnostics.report(id,"boot=0000c0de;up=60;heap=150000;min=120000;rssi=-58;drops=1;start=update;reset=3;prev=0badc0de;phase=ota;prevup=1990","0.1.1-22-g1234567","waveshare-c6-touch-lcd-169");
            mvc.perform(get("/admin/troubleshooting").with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("firmware 0.1.1-20-g7654321 · start 0badc0de, up 33 min")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Older firmware than now")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("0.1.1-22-g1234567")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Last start: update")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Received ")));
            // Asking for current logs: per display, without touching the saved ones.
            mvc.perform(post("/admin/support/request-logs").with(csrf()).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.identifier=='"+id+"')].state").value("PENDING"));
            Assertions.assertEquals(1,deviceLogs.count(id));
            mvc.perform(get("/admin/support/log-requests").with(r->{r.setLocalPort(10001);return r;}))
                    .andExpect(jsonPath("$[?(@.identifier=='"+id+"')].reach").value("DISABLED"));
        }finally{clients.remove(id);deviceLogs.deleteAll(id);diagnostics.forget(id);}
    }
    @Test void createdFilesHideSecretsAndOpenOnlyWithTheirKey() throws Exception {
        String now=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),old=LocalDateTime.now().minusDays(9).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Files.write(logs.resolve("access.test.log"),List.of(
                old+" 192.168.1.5 :10002 \"GET /old\" 200 1 1ms",
                now+" 192.168.1.5 :10002 \"GET /api/v1/clients/x/image Authorization: Bearer abcdefabcdef\" 200 1 1ms",
                now+" 203.0.113.9 :10001 \"POST /admin/login password=hunter22hunter\" 302 1 1ms",
                now+" 192.168.1.6 :10002 \"GET /web/s3cretSecretValue/image.png\" 200 1 1ms mail me@example.org via home.example.com"));
        String key="ab".repeat(32),id="vibedock-support-test";
        clients.paired(id,"Desk",key,"192.168.1.40",80,"waveshare-s3-touch-lcd-147");
        try{
            deviceLogs.save(id,"usb","0.2.0","waveshare-s3-touch-lcd-147",List.of("Start v0.2.0","Server https://home.example.com: HTTP 200"));
            var created=json.readTree(mvc.perform(post("/admin/support/file").param("days","7").param("hideHosts","true")
                    .param("title","Desk stays dark").param("happened","Black since the update.\r\nMail me@example.org\u0007").param("since","yesterday")
                    .param("displays",id,"vibedock-unknown").with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            String file=new String(Base64.getDecoder().decode(created.path("data").asText()));
            Assertions.assertTrue(created.path("name").asText().endsWith(".vdsupport"));
            Assertions.assertTrue(created.path("key").asText().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
            // Base64 ciphertext can contain words by chance; verify the envelope instead.
            var envelope=json.readTree(file);
            Set<String> fields=new HashSet<>();
            envelope.propertyNames().iterator().forEachRemaining(fields::add);
            Assertions.assertEquals(Set.of("format","version","kdf","iterations","salt","cipher","nonce","data"),fields);
            Assertions.assertEquals("vibedock-support",envelope.path("format").asText());
            Assertions.assertEquals(1,envelope.path("version").asInt());
            Assertions.assertEquals("AES-256-GCM",envelope.path("cipher").asText());
            Assertions.assertEquals("PBKDF2-HMAC-SHA256",envelope.path("kdf").asText());
            Assertions.assertEquals(600_000,envelope.path("iterations").asInt());
            Assertions.assertEquals(16,Base64.getDecoder().decode(envelope.path("salt").asText()).length);
            Assertions.assertEquals(12,Base64.getDecoder().decode(envelope.path("nonce").asText()).length);
            byte[] ciphertext=Base64.getDecoder().decode(envelope.path("data").asText());
            Assertions.assertTrue(ciphertext.length>16,"encrypted payload includes the GCM authentication tag");
            // A changed ciphertext must fail authentication even with the correct key.
            ciphertext[0]^=1;
            var tampered=envelope.deepCopy();
            ((tools.jackson.databind.node.ObjectNode)tampered).put("data",Base64.getEncoder().encodeToString(ciphertext));
            mvc.perform(post("/admin/support/open").contentType("application/json").content(json.writeValueAsString(Map.of("content",json.writeValueAsString(tampered),"key",created.path("key").asText())))
                    .with(csrf()).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Wrong key, or the file was changed"));
            // The wrong key opens nothing.
            mvc.perform(post("/admin/support/open").contentType("application/json").content(json.writeValueAsString(Map.of("content",file,"key","00000000-0000-0000-0000-000000000000")))
                    .with(csrf()).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Wrong key, or the file was changed"));
            String opened=mvc.perform(post("/admin/support/open").contentType("application/json").content(json.writeValueAsString(Map.of("content",file,"key",created.path("key").asText())))
                    .with(csrf()).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk())
                    .andExpect(jsonPath("$.summary.days").value(7)).andExpect(jsonPath("$.summary.hostsHidden").value(true))
                    .andExpect(jsonPath("$.displays[?(@.identifier=='"+id+"')].board").value("waveshare-s3-touch-lcd-147"))
                    .andExpect(jsonPath("$.deviceLogs[0].lines[0]").value("Start v0.2.0"))
                    .andExpect(jsonPath("$.diagnosis.legend").exists()).andExpect(jsonPath("$.diagnosis.server.storageWritable").value(true))
                    .andExpect(jsonPath("$.diagnosis.displays[?(@.identifier=='"+id+"')].logSource").value("usb"))
                    // The description: redacted, control characters removed, only known displays, empty fields absent.
                    .andExpect(jsonPath("$.report.title").value("Desk stays dark")).andExpect(jsonPath("$.report.happened").value("Black since the update.\nMail [e-mail]"))
                    .andExpect(jsonPath("$.report.since").value("yesterday")).andExpect(jsonPath("$.report.expected").doesNotExist())
                    .andExpect(jsonPath("$.report.displays.length()").value(1)).andExpect(jsonPath("$.report.displays[0]").value(id))
                    .andReturn().getResponse().getContentAsString();
            for(String secret:List.of("abcdefabcdef","hunter22hunter","s3cretSecretValue","me@example.org","home.example.com","203.0.113.9","/old"))
                Assertions.assertFalse(opened.contains(secret),secret+" must not be in the support file");
            Assertions.assertTrue(opened.contains("192.168.1.5"),"private addresses stay");
            Assertions.assertTrue(opened.contains("Bearer [redacted]")&&opened.contains("/web/[redacted]")&&opened.contains("[e-mail]")&&opened.contains("[host]"));
            mvc.perform(post("/admin/support/file").param("days","30").with(csrf()).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isBadRequest());
            mvc.perform(post("/admin/support/file").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        }finally{clients.remove(id);deviceLogs.deleteAll(id);}
    }
}
