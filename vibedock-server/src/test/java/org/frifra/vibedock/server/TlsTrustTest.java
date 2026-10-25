package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TlsTrustTest {
    @Test void plainHttpLanCandidatePreservesRootOnlyCertificateLogFallback() throws Exception {
        try(var plain=new java.net.ServerSocket(0,10,java.net.InetAddress.getByName("127.0.0.1"))){
            var requests=new java.util.concurrent.atomic.AtomicInteger();
            Thread responder=Thread.ofVirtual().start(()->{
                try(var socket=plain.accept()){
                    socket.setSoTimeout(5000);
                    byte[] header=socket.getInputStream().readNBytes(5);
                    if(header.length==5)socket.getInputStream().readNBytes(((header[3]&255)<<8)|(header[4]&255));
                    requests.incrementAndGet();
                    socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                }catch(java.io.IOException ignored){}
            });
            String external="https://external.example.invalid:"+plain.getLocalPort();
            var configured=mock(ServerAddresses.class);
            when(configured.external()).thenReturn(external);when(configured.configuredInternal()).thenReturn("http://127.0.0.1:8081");
            @SuppressWarnings("unchecked") ObjectProvider<ServerAddresses> provider=mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(configured);
            var certificateLog=mock(CertificateLog.class);byte[] root={1,2,3};
            when(certificateLog.root("external.example.invalid")).thenReturn(root);
            var trust=new TlsTrust();ReflectionTestUtils.setField(trust,"addresses",provider);
            ReflectionTestUtils.setField(trust,"certificateLog",certificateLog);
            var result=trust.forPairing(external);
            assertTrue(result.fromLog());assertArrayEquals(root,result.root());assertNull(result.fingerprint());
            verify(certificateLog).root("external.example.invalid");
            responder.join(5000);assertEquals(1,requests.get());assertFalse(responder.isAlive());
        }
    }
    @Test void lanProbeValidatesExternalIdentityAndDeliversPinInUsbPairing(@TempDir Path dir) throws Exception {
        Path store=dir.resolve("tls.p12");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                "-genkeypair","-alias","proxy","-keystore",store.toString(),"-storepass","password",
                "-keyalg","EC","-groupname","secp256r1","-dname","CN=external.example.invalid",
                "-ext","SAN=dns:external.example.invalid","-validity","2").redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes());assertEquals(0,process.waitFor(),output);
        var keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(store)){keys.load(in,"password".toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"password".toCharArray());
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        var serverContext=SSLContext.getInstance("TLS");serverContext.init(km.getKeyManagers(),null,null);
        var trustedContext=SSLContext.getInstance("TLS");trustedContext.init(null,tm.getTrustManagers(),null);
        var sni=new AtomicReference<String>();
        var server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext){
            @Override public void configure(HttpsParameters parameters){
                var ssl=serverContext.getDefaultSSLParameters();
                ssl.setSNIMatchers(List.of(new SNIMatcher(0){
                    @Override public boolean matches(SNIServerName name){sni.set(((SNIHostName)name).getAsciiName());return true;}
                }));parameters.setSSLParameters(ssl);
            }
        });
        server.start();
        try{
            int port=server.getAddress().getPort();String external="https://external.example.invalid:"+port;
            byte[] leaf=keys.getCertificate("proxy").getEncoded();
            String pin=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(leaf));
            var registry=new ClientRegistry(dir.resolve("data").toString(),JsonCompatibility.mapper(),Clock.systemUTC());
            var configured=new ServerAddresses(registry,10002,"http://127.0.0.1:8081",external);
            @SuppressWarnings("unchecked") ObjectProvider<ServerAddresses> provider=mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(configured);
            var trust=new TlsTrust(){
                @Override SSLContext tlsContext(){return trustedContext;}
                @Override byte[] trustRoot(Certificate[] chain){return leaf;}
            };
            ReflectionTestUtils.setField(trust,"addresses",provider);
            // Public name has no DNS entry. Automatic fallback uses the LAN host + external TLS port, not HTTP 8081.
            var result=trust.forPairing(external);
            assertEquals(pin,result.fingerprint());assertArrayEquals(leaf,result.root());assertFalse(result.fromLog());
            assertEquals("external.example.invalid",sni.get());
            assertEquals("lan-fallback",result.route());assertEquals("127.0.0.1:"+port,result.target());
            assertTrue(result.certificate().subject().contains("external.example.invalid"));
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(leaf)),result.certificate().sha256());
            assertTrue(result.certificate().protocol().startsWith("TLSv1."));
            // Split DNS: no usable system route or LAN candidate, but public DNS returns the TLS listener's IP.
            var externalOnly=mock(ServerAddresses.class);when(externalOnly.external()).thenReturn(external);
            when(externalOnly.configuredInternal()).thenReturn("");
            @SuppressWarnings("unchecked") ObjectProvider<ServerAddresses> publicProvider=mock(ObjectProvider.class);
            when(publicProvider.getIfAvailable()).thenReturn(externalOnly);
            var resolver=mock(PublicDns.class);when(resolver.resolve(anyString())).thenReturn(List.of("127.0.0.1"));
            var publicRoute=new TlsTrust(){
                @Override SSLContext tlsContext(){return trustedContext;}
                @Override byte[] trustRoot(Certificate[] chain){return leaf;}
            };
            ReflectionTestUtils.setField(publicRoute,"addresses",publicProvider);ReflectionTestUtils.setField(publicRoute,"publicDns",resolver);
            var publicResult=publicRoute.forPairing(external);
            assertEquals(pin,publicResult.fingerprint());assertEquals("external.example.invalid",sni.get());
            assertEquals("public-dns",publicResult.route());assertEquals("127.0.0.1:"+port,publicResult.target());
            assertEquals(result.certificate().sha256(),publicResult.certificate().sha256());
            verify(resolver).resolve("external.example.invalid");
            when(externalOnly.external()).thenReturn("https://wrong.example.invalid:"+port);
            assertTrue(assertThrows(IllegalArgumentException.class,()->publicRoute.forPairing("https://wrong.example.invalid:"+port)).getMessage().contains("not trusted"));
            clearInvocations(resolver);
            assertThrows(IllegalArgumentException.class,()->publicRoute.inspect("https://internal.example.invalid:"+port));
            verifyNoInteractions(resolver);
            // USB setup forwards the live certificate's pin through the existing pairing format.
            var pairing=new ClientPairing(registry,java.net.http.HttpClient.newHttpClient(),JsonCompatibility.mapper(),trust,new OtaSigning(dir.resolve("data").toString()));
            var setup=pairing.setUp("vibedock-123456789abc","Test",List.of("http://127.0.0.1:8081",external));
            assertEquals(pin,setup.pairing().get("pin"));assertTrue(setup.warnings().isEmpty());
            assertEquals(List.of("http://127.0.0.1:8081",external),setup.pairing().get("servers"));
            var withoutPin=pairing.setUp("vibedock-123456789abc","Test",null,List.of(external),false);
            assertFalse(withoutPin.pairing().containsKey("pin"));
            assertEquals(Base64.getEncoder().encodeToString(leaf),withoutPin.pairing().get("anchor"));
            // Explicit probe endpoint can use a different port and bypasses public DNS.
            ReflectionTestUtils.setField(trust,"probeUrl","https://127.0.0.1:"+port);
            assertEquals(pin,trust.recentInspection(external).fingerprint());
            // The LAN certificate is NOT trusted merely because the transport connects.
            var untrusted=new TlsTrust();ReflectionTestUtils.setField(untrusted,"addresses",provider);
            ReflectionTestUtils.setField(untrusted,"probeUrl","https://127.0.0.1:"+port);
            var rejectedFallback=mock(CertificateLog.class);
            ReflectionTestUtils.setField(untrusted,"certificateLog",rejectedFallback);
            assertTrue(assertThrows(IllegalArgumentException.class,()->untrusted.forPairing(external)).getMessage().contains("not trusted"));
            verifyNoInteractions(rejectedFallback);
            // A trusted certificate for another name must also fail; LAN routing never disables hostname validation.
            when(provider.getIfAvailable()).thenReturn(new ServerAddresses(registry,10002,"", "https://wrong.example.invalid"));
            ReflectionTestUtils.setField(trust,"certificateLog",rejectedFallback);
            assertTrue(assertThrows(IllegalArgumentException.class,()->trust.forPairing("https://wrong.example.invalid")).getMessage().contains("not trusted"));
            verifyNoInteractions(rejectedFallback);
            ReflectionTestUtils.setField(trust,"probeUrl","http://127.0.0.1:"+port);
            assertTrue(assertThrows(IllegalArgumentException.class,()->trust.inspect("https://wrong.example.invalid")).getMessage().contains("must use https"));
        }finally{server.stop(0);}
    }
    @Test void imageAnswersNeverWaitAndAFailedCheckIsNotRepeatedForEveryPull(){
        var calls=new java.util.concurrent.atomic.AtomicInteger();var reachable=new java.util.concurrent.atomic.AtomicBoolean(false);
        var trust=new TlsTrust(){
            @Override public Inspection inspect(String url){
                calls.incrementAndGet();
                if(!reachable.get())throw new IllegalArgumentException(url+" is not reachable from the server");
                return new Inspection(new byte[]{1},"ab".repeat(20));
            }
        };
        var queued=new java.util.ArrayList<Runnable>();trust.background=queued::add;
        String url="https://external.example.invalid:10002";
        // The first answer goes out at once without a pin; the check runs afterwards.
        assertTrue(trust.pinForAnswer(url).isEmpty());assertEquals(1,queued.size());assertEquals(0,calls.get());
        assertTrue(trust.pinForAnswer(url).isEmpty());assertEquals(1,queued.size(),"one check at a time");
        queued.removeFirst().run();assertEquals(1,calls.get());
        // Unreachable: further answers do not try again within ten minutes.
        for(int i=0;i<5;i++)assertTrue(trust.pinForAnswer(url).isEmpty());
        assertTrue(queued.isEmpty());assertEquals(1,calls.get());
        // Once reachable (e.g. Check pin again in the administration), answers carry the pin.
        reachable.set(true);trust.recentInspection(url);
        assertEquals(Optional.of("ab".repeat(20)),trust.pinForAnswer(url));assertTrue(queued.isEmpty());
    }
}
