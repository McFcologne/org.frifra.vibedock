package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractTest {
    final ObjectMapper json=JsonCompatibility.mapper();
    @Test void ipsAndCidrsUseSocketAddressesOnly() {
        assertTrue(IpNetworks.allows(List.of("192.168.2.0/24"),"192.168.2.170"));
        assertFalse(IpNetworks.allows(List.of("192.168.2.0/24"),"192.168.3.170"));
        assertTrue(IpNetworks.allows(List.of("2001:db8::/32"),"2001:db8::12"));
        assertFalse(IpNetworks.allows(List.of("2001:db8::/32"),"2001:db9::12"));
        assertFalse(IpNetworks.allows(List.of(),"127.0.0.1"));
        assertThrows(IllegalArgumentException.class,()->IpNetworks.parse("example.org/24"));
        assertThrows(IllegalArgumentException.class,()->IpNetworks.parse("192.168.2.0/33"));
    }
    @Test void codexWindowsAreMatchedByDurationNotPosition() throws Exception {
        var r=ProviderClient.parse("codex",json.readTree("""
          {"rate_limit":{"primary_window":{"used_percent":77,"reset_at":1900000000,"limit_window_seconds":604800},
          "secondary_window":{"used_percent":12,"reset_at":1800000000,"limit_window_seconds":18000}}}
          """));
        assertEquals(12,r.fiveHour().usedPercent()); assertEquals(77,r.week().usedPercent());
        var none=assertThrows(ProviderClient.Failure.class,()->ProviderClient.parse("codex",json.readTree("{\"rate_limit\":{}}")));
        assertEquals("unavailable",none.status);
        assertEquals("no rate_limit in the answer, plan free",assertThrows(ProviderClient.Failure.class,
                ()->ProviderClient.parse("codex",json.readTree("{\"plan_type\":\"free\"}"))).detail);
        // Other plans: windows by duration, up to a day short, longer ones weekly.
        var plus=ProviderClient.parse("codex",json.readTree("""
          {"rate_limit":{"primary_window":{"used_percent":5,"reset_at":1800000000,"limit_window_seconds":10800},
          "secondary_window":{"used_percent":30,"reset_at":1900000000,"limit_window_seconds":2592000}}}
          """));
        assertEquals(5,plus.fiveHour().usedPercent());assertEquals(30,plus.week().usedPercent());
        assertEquals(2592000,plus.week().seconds());
        assertEquals("Month",UsageService.period("codex",new UsageService.Provider("ok",1L,null,plus.week())));
        assertNull(UsageService.period("codex",new UsageService.Provider("ok",1L,plus.fiveHour(),plus.week())));
        assertNull(UsageService.period("claude",new UsageService.Provider("ok",1L,null,new ProviderClient.Window(3,1,604800))));
    }
    @Test void claudeMissingAndMalformedWindowsDoNotBecomeZero() throws Exception {
        var r=ProviderClient.parse("claude",json.readTree("""
            {"five_hour":null,"seven_day":{"utilization":42.5,"resets_at":"2026-10-20T12:00:00Z"}}
            """));
        assertNull(r.fiveHour());assertEquals(42.5,r.week().usedPercent());
        assertEquals(Instant.parse("2026-10-20T12:00:00Z").getEpochSecond(),r.week().resetsAt());
        assertThrows(Exception.class,()->ProviderClient.parse("claude",json.readTree("""
            {"five_hour":{"utilization":101,"resets_at":"2026-10-20T12:00:00Z"}}
            """)));
        // No usage in the window yet: no reset time, but the value counts.
        var fresh=ProviderClient.parse("claude",json.readTree("{\"five_hour\":{\"utilization\":0,\"resets_at\":null},\"seven_day\":{\"utilization\":3,\"resets_at\":\"2026-10-20T12:00:00Z\"}}"));
        assertEquals(0,fresh.fiveHour().usedPercent());assertEquals(0,fresh.fiveHour().resetsAt());
    }
    @Test void configurationPersistsAndInvalidChangesPreserveOldState(@TempDir Path dir) throws Exception {
        var store=new SettingsStore(dir.toString(),json);
        var saved=new SettingsStore.Settings(List.of("10.0.0.0/8"),new SettingsStore.Credential("secret-value","workspace"),
                new SettingsStore.Credential("",""),List.of());
        store.save(saved);
        assertEquals(saved,new SettingsStore(dir.toString(),json).get());
        assertThrows(IllegalArgumentException.class,()->store.save(new SettingsStore.Settings(List.of("oops"),saved.codex(),saved.claude(),List.of())));
        assertEquals(saved,store.get());
    }
    @Test void deviceFrameHasBothProvidersResetsAndFitsWireBudget() throws Exception {
        var sender=new DeviceSender(HttpClient.newHttpClient(),json,"Europe/Berlin");
        var window=new ProviderClient.Window(73.2,1900000000);
        var snapshot=new UsageService.Snapshot(1,1800000000,Map.of(
                "codex",new UsageService.Provider("ok",1800000000L,window,window),
                "claude",new UsageService.Provider("authentication_required",null,null,null)));
        byte[] frame=sender.frame(snapshot);assertTrue(frame.length<=2048);
        Files.createDirectories(Path.of("target"));Files.write(Path.of("target/frame-example.json"),frame);
        var node=json.readTree(frame);assertEquals(2,node.path("v").intValue());
        String spec=node.path("themeSpec").toString();
        assertTrue(spec.contains("Codex"));assertTrue(spec.contains("Claude"));
        assertTrue(spec.contains("73.2%"));assertTrue(spec.contains("Login required"));assertTrue(spec.contains("5h: --"));
        assertFalse(spec.contains("1900000000"));
    }
    @Test void actualDevicePostUsesThePairingHeaderAndNeverRetriesFailure() throws Exception {
        HttpServer fake=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger requests=new AtomicInteger();
        fake.createContext("/frame",exchange->{
            requests.incrementAndGet();
            assertEquals("POST",exchange.getRequestMethod());
            assertEquals("test-pairing-token",exchange.getRequestHeaders().getFirst("X-VibeDock-Token"));
            assertTrue(exchange.getRequestBody().readAllBytes().length<=2048);
            exchange.sendResponseHeaders(401,-1);exchange.close();
        });fake.start();
        try {
            var sender=new DeviceSender(HttpClient.newHttpClient(),json,"Europe/Berlin");
            var empty=new UsageService.Provider("unconfigured",null,null,null);
            sender.send(List.of(new SettingsStore.Device("Simulator","http://127.0.0.1:"+fake.getAddress().getPort(),"test-pairing-token",true)),
                    new UsageService.Snapshot(1,1800000000,Map.of("codex",empty,"claude",empty)));
            assertEquals(1,requests.get());assertEquals("HTTP 401",sender.status().get("Simulator"));
        } finally { fake.stop(0); }
    }
    @Test void staleOrChangedAccountNeverReusesCurrentQuota(@TempDir Path dir) throws Exception {
        var settings=new SettingsStore(dir.toString(),json);
        settings.save(new SettingsStore.Settings(List.of(),new SettingsStore.Credential("first",""),new SettingsStore.Credential("",""),List.of()));
        var client=mock(ProviderClient.class);var clock=mock(java.time.Clock.class);
        Instant start=Instant.parse("2026-10-13T20:00:00Z");when(clock.instant()).thenReturn(start);
        when(client.fetch(eq("codex"),any())).thenReturn(new ProviderClient.Reading(new ProviderClient.Window(20,1900000000),null));
        var service=new UsageService(settings,client,mock(DeviceSender.class),clock);
        service.refresh();assertNotNull(service.snapshot().providers().get("codex").fiveHour());
        when(clock.instant()).thenReturn(start.plusSeconds(151));
        assertEquals("stale",service.snapshot().providers().get("codex").status());
        assertNull(service.snapshot().providers().get("codex").fiveHour());
        settings.save(new SettingsStore.Settings(List.of(),new SettingsStore.Credential("second",""),settings.get().claude(),List.of()));
        assertEquals("waiting",service.snapshot().providers().get("codex").status());
        assertNull(service.snapshot().providers().get("codex").fiveHour());
    }
}
