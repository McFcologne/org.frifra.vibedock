package org.frifra.vibedock.server;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** ADMIN_AUTH=none and DEMO_USAGE=true: no sign-in on the administration port, made-up usage without a sign-in. */
@SpringBootTest(properties={"vibedock.admin-auth=none","vibedock.demo-usage=true","vibedock.admin-password=","vibedock.polling=false","vibedock.log-dir=target/test-logs"})
@AutoConfigureMockMvc
class TestModesTest {
    @DynamicPropertySource static void directory(DynamicPropertyRegistry r) throws Exception {
        var dir=Files.createTempDirectory("vibedock-test-modes-");r.add("vibedock.data-dir",dir::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired SettingsStore store;
    @Autowired UsageService usage;
    @Autowired ClientRegistry clients;

    @Test void administrationOpensWithoutSignInOnItsPortOnly() throws Exception {
        mvc.perform(get("/admin").with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Test mode: no sign-in, made-up usage")));
        // The public port still offers no administration.
        mvc.perform(get("/admin").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        mvc.perform(get("/login").with(r->{r.setLocalPort(10001);return r;})).andExpect(redirectedUrl("/admin"));
        mvc.perform(get("/admin/events").with(r->{r.setLocalPort(10001);return r;})).andExpect(request().asyncStarted());
    }
    @Test void displaysWithoutReportedWifiShowMadeUpValues() throws Exception {
        String id="vibedock-demo-wifi";
        clients.paired(id,"Demo Wi-Fi card","cd".repeat(32),"192.168.1.30",80);
        try{
            var net=DemoUsage.network(id);
            Assertions.assertTrue(net.rssi()<=-45&&net.rssi()>=-85&&List.of("lan","external").contains(net.route()),net.toString());
            for(var page:List.of("/admin","/admin/devices"))
                mvc.perform(get(page).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk())
                        .andExpect(content().string(org.hamcrest.Matchers.containsString("Demo Wi-Fi</span>")))
                        .andExpect(content().string(org.hamcrest.Matchers.containsString("−"+(-net.rssi())+" dBm")));
        }finally{clients.remove(id);}
    }
    @Test void sourcesWithoutSignInReportMadeUpUsage() throws Exception {
        var s=store.get();
        store.save(s.withAccounts(List.of(new SettingsStore.Account("demo-claude","claude","Claude Demo",null))));
        usage.refresh();
        var p=usage.snapshot().providers().get("demo-claude");
        Assertions.assertEquals("ok",p.status());
        Assertions.assertTrue(p.fiveHour().usedPercent()>=0&&p.fiveHour().usedPercent()<=100);
        Assertions.assertTrue(p.week().resetsAt()>p.observedAt());
    }
}
