package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.net.*;
import java.net.http.HttpClient;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import com.sun.net.httpserver.HttpServer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientTest {
    final ObjectMapper json=new ObjectMapper();
    final String key="ab".repeat(32);
    UsageService.Snapshot snapshot(){var p=new UsageService.Provider("unconfigured",null,null,null);
        return new UsageService.Snapshot(1,1800000000,Map.of("codex",p,"claude",p));}
    @Test void pairedClientsPersistOnlyAKeyHashAndCanBeRemoved(@TempDir Path dir) throws Exception {
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        registry.paired("vibedock-112233","Test",key,"192.168.1.10",80);
        var reloaded=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        assertTrue(reloaded.authenticate("vibedock-112233",key));
        assertFalse(reloaded.authenticate("vibedock-112233","cd".repeat(32)));
        assertFalse(reloaded.authenticate("vibedock-unknown1",key));
        assertFalse(reloaded.authenticate("vibedock-112233",null));
        assertFalse(Files.readString(dir.resolve("clients.json")).contains(key));
        assertFalse(json.writeValueAsString(reloaded.views()).contains(key));
        assertEquals("Waiting for first pull",reloaded.get("vibedock-112233").status());
        reloaded.pulled("vibedock-112233","192.168.1.20");
        assertEquals("Connected",reloaded.get("vibedock-112233").status());
        assertEquals("192.168.1.20",reloaded.get("vibedock-112233").ip());
        reloaded.remove("vibedock-112233");
        assertFalse(reloaded.authenticate("vibedock-112233",key));
    }
    @Test void legacyPushRegistryIsIgnoredAndSettingsAreValidated(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("clients.json"),"[]");
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        assertTrue(registry.views().isEmpty());
        registry.configure("http://192.168.1.10:10002","https://vibedock.example.com",45);
        assertEquals(45,new ClientRegistry(dir.toString(),json,Clock.systemUTC()).pollSeconds());
        assertThrows(IllegalArgumentException.class,()->registry.configure("","",5));
        assertThrows(IllegalArgumentException.class,()->registry.configure("ftp://x","",30));
        assertThrows(IllegalArgumentException.class,()->registry.configure("","https://user@example.com",30));
        assertThrows(IllegalArgumentException.class,()->registry.configure("","https://example.com/path",30));
    }
    @Test void previewAndWireImageHaveExactlyTheSamePixelsAndIpOnlyChangesFooter() throws Exception {
        var renderer=new StatusImage("Europe/Berlin");var image=renderer.render(snapshot(),"192.168.1.10");
        assertEquals(115200,image.rgb565().length);
        var decoded=ImageIO.read(new ByteArrayInputStream(image.png()));assertEquals(240,decoded.getWidth());assertEquals(240,decoded.getHeight());
        int offset=0;
        for(int y=0;y<240;y++)for(int x=0;x<240;x++){
            int value=((image.rgb565()[offset++]&255)<<8)|(image.rgb565()[offset++]&255);
            int expected=(((value>>11)&31)*255/31<<16)|(((value>>5)&63)*255/63<<8)|((value&31)*255/31);
            assertEquals(expected,decoded.getRGB(x,y)&0xffffff);
        }
        var changed=renderer.render(snapshot(),"192.168.1.99");
        assertArrayEquals(Arrays.copyOf(image.rgb565(),220*480),Arrays.copyOf(changed.rgb565(),220*480));
        assertFalse(Arrays.equals(image.rgb565(),changed.rgb565()));
        Files.createDirectories(Path.of("target"));Files.write(Path.of("target/status-preview.png"),image.png());
    }
    @Test void pairingSendsAddressesAndKeyAndRejectsWrongCodes(@TempDir Path dir) throws Exception {
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        var received=new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/pair",exchange->{
            var body=json.readTree(exchange.getRequestBody().readAllBytes());received.set(body);
            byte[] reply=body.path("code").asText().equals("123456")
                    ? "{\"identifier\":\"vibedock-simulator\",\"name\":\"Simulator\"}".getBytes():new byte[0];
            exchange.sendResponseHeaders(reply.length>0?200:403,reply.length>0?reply.length:-1);
            if(reply.length>0)exchange.getResponseBody().write(reply);exchange.close();
        });server.start();
        try{
            var trust=new TlsTrust(){@Override public Inspection inspect(String url){
                if(url.contains("untrusted"))throw new IllegalArgumentException("Certificate of "+url+" is not trusted");
                return new Inspection(new byte[]{1,2,3},"ab".repeat(20));}
                @Override public Inspection forPairing(String url){
                    return url.contains("behind-router")?new Inspection(new byte[]{1,2,3},null,true):super.forPairing(url);}};
            var pairing=new ClientPairing(registry,HttpClient.newHttpClient(),json,trust,new OtaSigning(dir.toString()));
            int port=server.getAddress().getPort();
            var error=assertThrows(IllegalArgumentException.class,()->pairing.invite("127.0.0.1",port,"654321","",List.of("http://192.168.1.10:10002")));
            assertTrue(error.getMessage().contains("pairing code"));
            assertTrue(registry.views().isEmpty());
            var view=pairing.invite("127.0.0.1",port,"123456","Office",List.of("http://192.168.1.10:10002","https://vibedock.example.com")).view();
            assertEquals("vibedock-simulator",view.identifier());assertEquals("Office",view.name());
            var body=received.get();
            assertEquals("http://192.168.1.10:10002",body.path("servers").get(0).asText());
            assertEquals("https://vibedock.example.com",body.path("servers").get(1).asText());
            assertEquals("AQID",body.path("anchor").asText());assertEquals("ab".repeat(20),body.path("pin").asText());
            assertEquals(30,body.path("pollSeconds").asInt());
            assertTrue(body.path("otaKey").asText().startsWith("-----BEGIN PUBLIC KEY-----\n"));
            assertTrue(registry.authenticate("vibedock-simulator",body.path("key").asText()));
            var untrusted=pairing.invite("127.0.0.1",port,"123456","Office",List.of("http://192.168.1.10:10002","https://untrusted.example.com"));
            // Announced anyway, without a certificate, and the administration warns about it.
            assertEquals(2,received.get().path("servers").size());assertEquals("https://untrusted.example.com",received.get().path("servers").get(1).asText());
            assertTrue(received.get().path("anchor").isMissingNode());
            assertTrue(untrusted.warnings().get(0).contains("not trusted"));assertTrue(untrusted.warnings().get(0).contains("without its certificate"));
            // Not reachable from the server: the root from the certificate log goes along, without a fingerprint.
            var logged=pairing.invite("127.0.0.1",port,"123456","",List.of("http://192.168.1.10:10002","https://behind-router.example.com"));
            assertEquals("AQID",received.get().path("anchor").asText());assertTrue(received.get().path("pin").isMissingNode());
            assertTrue(logged.warnings().get(0).contains("certificate log"));
            pairing.invite("127.0.0.1",port,"123456","",List.of("https://untrusted.example.com"));
            assertEquals(List.of("https://untrusted.example.com"),List.of(received.get().path("servers").get(0).asText()));

            assertThrows(IllegalArgumentException.class,()->pairing.invite("not-an-ip",port,"123456","",List.of("http://x:1")));
            assertThrows(IllegalArgumentException.class,()->pairing.invite("127.0.0.1",port,"12345","",List.of("http://x:1")));
        }finally{server.stop(0);}
    }
}
