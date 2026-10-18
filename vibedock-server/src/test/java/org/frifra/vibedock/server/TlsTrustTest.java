package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
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
            var registry=new ClientRegistry(dir.resolve("data").toString(),new ObjectMapper(),Clock.systemUTC());
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
            // USB setup forwards the live certificate's pin through the existing pairing format.
            var pairing=new ClientPairing(registry,java.net.http.HttpClient.newHttpClient(),new ObjectMapper(),trust,new OtaSigning(dir.resolve("data").toString()));
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
            assertTrue(assertThrows(IllegalArgumentException.class,()->untrusted.inspect(external)).getMessage().contains("not trusted"));
            // A trusted certificate for another name must also fail; LAN routing never disables hostname validation.
            when(provider.getIfAvailable()).thenReturn(new ServerAddresses(registry,10002,"", "https://wrong.example.invalid"));
            assertTrue(assertThrows(IllegalArgumentException.class,()->trust.inspect("https://wrong.example.invalid")).getMessage().contains("not trusted"));
            ReflectionTestUtils.setField(trust,"probeUrl","http://127.0.0.1:"+port);
            assertTrue(assertThrows(IllegalArgumentException.class,()->trust.inspect("https://wrong.example.invalid")).getMessage().contains("must use https"));
        }finally{server.stop(0);}
    }
}
