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
    @Test void reportedAddressesNeverBecomeCallbackTargets(@TempDir Path dir) throws Exception {
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        registry.paired("vibedock-callback","Trusted",key,"192.0.2.123",8080);
        registry.pulled("vibedock-callback","127.0.0.1");
        assertEquals("127.0.0.1",registry.get("vibedock-callback").ip());
        assertEquals(new ClientRegistry.Address("192.0.2.123",8080),registry.address("vibedock-callback"));
        var reloaded=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        assertEquals(registry.address("vibedock-callback"),reloaded.address("vibedock-callback"));
        var web=registry.addWeb("Browser",null,30);
        registry.pulled(web.identifier(),"127.0.0.1");
        assertEquals(ClientPairing.UNKNOWN_IP,registry.address(web.identifier()).ip());
        assertTrue(registry.all().contains(registry.address("vibedock-callback")));
        assertTrue(registry.followingDefault().contains(registry.address(web.identifier())));
    }
    @Test void callbackAddressesMayMoveOnlyInsideTheNetworkFixedAtPairing(@TempDir Path dir) throws Exception {
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        String id="vibedock-subnet-test";
        registry.paired(id,"Subnet",key,"192.168.17.5",8080,null,IpNetworks.subnet("192.168.17.5","255.255.240.0"));
        for(String ip:List.of("192.168.16.1","192.168.31.254")){
            registry.pulled(id,ip);assertEquals(new ClientRegistry.Address(ip,8080),registry.address(id));
        }
        for(String ip:List.of("192.168.32.1","192.168.15.254","127.0.0.1","::1")){
            registry.pulled(id,ip);assertEquals("192.168.17.5",registry.address(id).ip());
            assertEquals(ip,registry.get(id).ip()); // Still shown as reporting data.
        }
        registry.pulled(id,"192.168.20.4");registry.rename(id,"Renamed");
        registry.setBoard(id,"esp32-cyd");
        var reloaded=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        assertEquals("192.168.20.4",reloaded.address(id).ip());
        assertTrue(reloaded.followingDefault().contains(reloaded.address(id)));
        assertTrue(reloaded.all().contains(reloaded.address(id)));
        // Re-pairing with older firmware replaces the broader learned network with the /24 fallback.
        reloaded.paired(id,"Old firmware",key,"192.168.17.5",8080);
        reloaded.pulled(id,"192.168.20.4");assertEquals("192.168.17.5",reloaded.address(id).ip());
        var root=json.readTree(dir.resolve("clients.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("devices").get(0)).remove("callbackNetwork");
        json.writeValue(dir.resolve("clients.json").toFile(),root);
        var legacy=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        assertEquals("192.168.17.5",legacy.address(id).ip());
        legacy.pulled(id,"192.168.17.254");assertEquals("192.168.17.254",legacy.address(id).ip());
        legacy.pulled(id,"192.168.18.1");assertEquals("192.168.17.5",legacy.address(id).ip());
        legacy.pulled(id,"192.168.18.254");assertEquals("192.168.17.5",legacy.address(id).ip());
    }
    @Test void pairingLearnsTheMaskOnceAndRejectsInvalidMasks(@TempDir Path dir) throws Exception {
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        var mask=new java.util.concurrent.atomic.AtomicReference<>("255.255.254.0");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/pair",exchange->{
            exchange.getRequestBody().readAllBytes();
            byte[] reply=json.writeValueAsBytes(Map.of("identifier","vibedock-mask-test","name","Subnet","subnetMask",mask.get()));
            exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();
        });server.start();
        try{
            var pairing=new ClientPairing(registry,HttpClient.newHttpClient(),json,new TlsTrust(),new OtaSigning(dir.toString()));
            var result=pairing.invite("127.0.0.1",server.getAddress().getPort(),"123456","Subnet",List.of("http://127.0.0.1:9"));
            assertTrue(result.warnings().isEmpty());
            registry.pulled(result.view().identifier(),"127.0.1.254");assertEquals("127.0.1.254",registry.address(result.view().identifier()).ip());
            registry.pulled(result.view().identifier(),"127.0.2.1");assertEquals("127.0.0.1",registry.address(result.view().identifier()).ip());
            mask.set("0.0.0.0");
            result=pairing.invite("127.0.0.1",server.getAddress().getPort(),"123456","Subnet",List.of("http://127.0.0.1:9"));
            assertTrue(result.warnings().getFirst().contains("Invalid device subnet mask"));
            registry.pulled(result.view().identifier(),"127.0.0.2");assertEquals("127.0.0.1",registry.address(result.view().identifier()).ip());
        }finally{server.stop(0);}
    }
    @Test void wideMasksNeitherWidenPairingNorTheOneTimeUpgrade(@TempDir Path dir) throws Exception {
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());String id="vibedock-wide-mask";
        registry.paired(id,"Wide",key,"10.1.2.3",80);
        registry.pulled(id,"10.1.2.3",true,"0.1.1","128.0.0.0");  // invalid: does not use up the one-time upgrade
        registry.pulled(id,"10.9.9.9",true,"0.1.1");assertEquals("10.1.2.3",registry.address(id).ip());
        registry.pulled(id,"10.1.2.3",true,"0.1.1","255.255.0.0");
        registry.pulled(id,"10.1.200.7",true,"0.1.1");assertEquals("10.1.200.7",registry.address(id).ip());
        registry.pulled(id,"10.2.0.1",true,"0.1.1","255.0.0.0");assertEquals("10.1.2.3",registry.address(id).ip());
    }
    @Test void spoofedSpecialAddressesFallBackToTheConnectionSource(){
        for(String reported:List.of("127.0.0.1","127.8.8.8","::1","169.254.169.254","fe80::1","0.0.0.0","::","224.0.0.1","ff02::1"))
            assertEquals("192.168.1.50",DeviceApiController.deviceAddress(reported,"192.168.1.50"),reported);
        assertEquals("192.168.1.60",DeviceApiController.deviceAddress("192.168.1.60","172.17.0.1"));
        assertEquals("fd00::5",DeviceApiController.deviceAddress("fd00::5","172.17.0.1"));
    }
    @Test void usbFirstContactPinsTheOriginAcrossDhcpChangesAndRestarts(@TempDir Path dir) throws Exception {
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());String id="vibedock-usb-origin";
        registry.paired(id,"USB",key,ClientPairing.UNKNOWN_IP,80);
        assertEquals(ClientPairing.UNKNOWN_IP,registry.address(id).ip());
        for(String invalid:List.of("0.0.0.0","::","224.0.0.1","not-an-ip")){
            registry.pulled(id,invalid);assertEquals(ClientPairing.UNKNOWN_IP,registry.address(id).ip());
        }
        registry.pulled(id,"192.168.1.50");assertEquals("192.168.1.50",registry.address(id).ip());
        registry.pulled(id,"192.168.1.99");assertEquals("192.168.1.99",registry.address(id).ip());
        var reloaded=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        assertEquals("192.168.1.99",reloaded.address(id).ip());
        reloaded.pulled(id,"192.168.2.1");assertEquals("192.168.1.50",reloaded.address(id).ip());
        reloaded.pulled(id,"192.168.2.99");assertEquals("192.168.1.50",reloaded.address(id).ip());
        var web=reloaded.addWeb("Web",null,30);reloaded.pulled(web.identifier(),"192.168.1.50");
        assertEquals(ClientPairing.UNKNOWN_IP,reloaded.address(web.identifier()).ip());
    }
    @Test void legacyAndUsbClientsLearnOnlyTheirFirstValidMask(@TempDir Path dir) throws Exception {
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        for(String id:List.of("vibedock-mask-legacy","vibedock-mask-usb")){
            registry.paired(id,"Mask upgrade",key,id.endsWith("usb")?ClientPairing.UNKNOWN_IP:"192.168.17.5",80);
            registry.pulled(id,"192.168.17.5",true,"0.1.0"); // No mask: old firmware remains usable.
            registry.pulled(id,"192.168.17.9",true,"0.1.0");assertEquals("192.168.17.9",registry.address(id).ip());
            for(String version:new String[]{null,"0.1.0","v0.1.0","0.1.0-dirty","0.0.9","unknown","999999999999999999999.1.0"})
                registry.pulled(id,"192.168.17.9",true,version,"255.255.240.0");
            registry.pulled(id,"192.168.20.1",true,"0.1.0");assertEquals("192.168.17.5",registry.address(id).ip());
            for(String invalid:List.of("0.0.0.0","255.0.255.0"))registry.pulled(id,"192.168.17.9",true,"0.1.1",invalid);
            registry.pulled(id,"192.168.32.1",true,"0.1.1","255.255.240.0"); // Outside the proposed network: do not learn.
            assertEquals("192.168.17.5",registry.address(id).ip());
            registry.pulled(id,"192.168.17.9",true,"0.1.1");
            registry.pulled(id,"192.168.17.9",true,"0.1.1","255.255.240.0"); // Same peer/time: must still persist the upgrade.
            var reloaded=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
            reloaded.pulled(id,"192.168.31.254",true,"0.1.1");assertEquals("192.168.31.254",reloaded.address(id).ip());
            reloaded.pulled(id,"192.168.32.1",true,"0.2.0","255.255.0.0");assertEquals("192.168.17.5",reloaded.address(id).ip());
            reloaded.pulled(id,"192.168.20.1",true,"0.2.0","255.255.255.0");assertEquals("192.168.20.1",reloaded.address(id).ip());
            registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        }
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
            pairing.invite("127.0.0.1",port,"123456","Office","",List.of("https://vibedock.example.com"),false);
            assertEquals("AQID",received.get().path("anchor").asText());assertTrue(received.get().path("pin").isMissingNode());
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
