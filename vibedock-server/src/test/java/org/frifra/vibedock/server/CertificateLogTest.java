package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.security.cert.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CertificateLogTest {
    /** keytool from the running JDK, as the tests have no certificate library. */
    private static void keytool(Path dir,String... args) throws Exception {
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                "-keystore",dir.resolve("ks.p12").toString(),"-storepass","password","-storetype","PKCS12"));
        command.addAll(List.of(args));
        var process=new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes());
        assertEquals(0,process.waitFor(),output);
    }
    private static X509Certificate read(Path file) throws Exception {
        return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Files.readAllBytes(file)));
    }

    @Test void rootComesFromTheLogAndTheIssuerNamedInTheCertificate(@TempDir Path dir) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);int port=server.getAddress().getPort();
        // Root, intermediate (the issuer) and a leaf for home.example.org naming the issuer's address.
        String key="-keyalg EC -groupname secp256r1 -validity 30";
        keytool(dir,("-genkeypair -alias root -dname CN=Root -ext bc:c "+key).split(" "));
        keytool(dir,("-genkeypair -alias ca -dname CN=Issuer "+key).split(" "));
        keytool(dir,"-certreq","-alias","ca","-file","ca.csr");
        keytool(dir,"-gencert","-alias","root","-infile","ca.csr","-outfile","ca.der","-ext","bc:c");
        keytool(dir,("-genkeypair -alias leaf -dname CN=home.example.org "+key).split(" "));
        keytool(dir,"-certreq","-alias","leaf","-file","leaf.csr");
        keytool(dir,"-gencert","-alias","ca","-infile","leaf.csr","-outfile","leaf.pem","-rfc","-ext","san=dns:home.example.org",
                "-ext","AIA=caIssuers:uri:http://127.0.0.1:"+port+"/issuer.der");
        keytool(dir,"-exportcert","-alias","root","-file","root.der");
        byte[] root=Files.readAllBytes(dir.resolve("root.der"));
        X509Certificate leaf=read(dir.resolve("leaf.pem"));
        assertEquals("http://127.0.0.1:"+port+"/issuer.der",CertificateLog.caIssuers(leaf));

        var requests=new AtomicInteger();var available=new java.util.concurrent.atomic.AtomicBoolean(true);
        String from=LocalDateTime.ofInstant(leaf.getNotBefore().toInstant(),ZoneOffset.UTC).toString(),
               until=LocalDateTime.ofInstant(leaf.getNotAfter().toInstant(),ZoneOffset.UTC).toString();
        server.createContext("/",exchange->{
            requests.incrementAndGet();
            String query=exchange.getRequestURI().getQuery()==null?"":exchange.getRequestURI().getQuery();
            byte[] body;int status=200;
            if(!available.get()){body=new byte[0];status=503;}
            else if(exchange.getRequestURI().getPath().equals("/issuer.der"))body=Files.readAllBytes(dir.resolve("ca.der"));
            else if(query.startsWith("q=home.example.org"))body=("[{\"id\":7,\"name_value\":\"home.example.org\",\"not_before\":\""+from+"\",\"not_after\":\""+until+"\"},"
                    +"{\"id\":8,\"name_value\":\"other.example.org\",\"not_before\":\""+from+"\",\"not_after\":\""+until+"\"}]").getBytes();
            else if(query.equals("d=7"))body=Files.readAllBytes(dir.resolve("leaf.pem"));
            else{body=new byte[0];status=404;}
            exchange.sendResponseHeaders(status,body.length==0?-1:body.length);
            if(body.length>0)exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try{
            var now=new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
            Clock clock=new Clock(){
                @Override public ZoneId getZone(){return ZoneOffset.UTC;}
                @Override public Clock withZone(ZoneId zone){return this;}
                @Override public Instant instant(){return now.get();}
            };
            @SuppressWarnings("unchecked") ObjectProvider<ServerAddresses> none=mock(ObjectProvider.class);
            var certificateLog=new CertificateLog(HttpClient.newHttpClient(),JsonCompatibility.mapper(),clock,none,"http://127.0.0.1:"+port+"/");
            // The test root is not in the Java trust store: accept it, as TlsTrust.root does for trusted ones.
            certificateLog.anchorOf=chain->{
                try{chain[0].verify(read(dir.resolve("root.der")).getPublicKey());return root;}catch(Exception e){throw new IllegalArgumentException(e);}
            };
            assertArrayEquals(root,certificateLog.root("home.example.org"));
            int afterFirst=requests.get();assertEquals(3,afterFirst);  // search, certificate, issuer
            // Kept for a day: no new requests.
            assertArrayEquals(root,certificateLog.root("HOME.example.org"));assertEquals(afterFirst,requests.get());
            // After a day it is fetched again; if the log is down, the older result still serves for 30 days.
            now.set(now.get().plusSeconds(CertificateLog.DAY+60));available.set(false);
            assertArrayEquals(root,certificateLog.root("home.example.org"));assertTrue(requests.get()>afterFirst);
            now.set(now.get().plusSeconds(CertificateLog.MAX_AGE));
            var gone=assertThrows(IllegalArgumentException.class,()->certificateLog.root("home.example.org"));
            assertTrue(gone.getMessage().contains("crt.sh"));
            available.set(true);
            assertThrows(IllegalArgumentException.class,()->certificateLog.root("unknown.example.org"));
        }finally{server.stop(0);}
    }
    @Test void wildcardsCoverOneLabelOnly(){
        assertTrue(CertificateLog.matches("*.example.org","home.example.org"));
        assertTrue(CertificateLog.matches("home.example.org","HOME.example.org"));
        assertFalse(CertificateLog.matches("*.example.org","a.home.example.org"));
        assertFalse(CertificateLog.matches("*.example.org","example.org"));
        assertFalse(CertificateLog.matches("other.example.org","home.example.org"));
    }
}
