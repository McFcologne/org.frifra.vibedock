package org.frifra.vibedock.server;

import org.springframework.stereotype.Component;
import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.*;
import java.util.*;

/**
 * Trust anchor for an https server address. The device has no certificate store of its own, so at pairing time the
 * server connects to the address, validates its chain and host name against the Java trust store, and hands the
 * root certificate of that chain to the device together with its key. The device then trusts only this root.
 */
@Component
public class TlsTrust {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(TlsTrust.class);
    /**
     * Root certificate (DER) a trusted chain leads to, and the SHA-1 fingerprint of the server certificate itself.
     * fromLog: the root comes from the public certificate log because the server cannot reach the address; then
     * there is no fingerprint.
     */
    public record CertificateInfo(String subject,String issuer,String notBefore,String notAfter,String sha256,String protocol) {}
    public record Inspection(byte[] root,String fingerprint,boolean fromLog,String route,String target,CertificateInfo certificate) {
        public Inspection(byte[] root,String fingerprint){this(root,fingerprint,false);}
        public Inspection(byte[] root,String fingerprint,boolean fromLog){this(root,fingerprint,fromLog,null,null,null);}
    }
    private record Connection(Certificate[] certificates,String route,String target,String protocol) {}
    @org.springframework.beans.factory.annotation.Autowired(required=false) private CertificateLog certificateLog;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private org.springframework.beans.factory.ObjectProvider<ServerAddresses> addresses;
    @org.springframework.beans.factory.annotation.Value("${vibedock.external-tls-probe-url:}") private String probeUrl="";
    @org.springframework.beans.factory.annotation.Autowired(required=false) private PublicDns publicDns;
    private final Map<String,Inspection> recent=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,Long> checkedAt=new java.util.concurrent.ConcurrentHashMap<>();

    /** DER-encoded root certificate that the address's chain leads to; IllegalArgumentException if it is not trusted. */
    public byte[] rootFor(String url){return inspect(url).root();}
    /** Like {@link #inspect} but reuses a result younger than a minute, for devices doing their full check. */
    public Inspection recentInspection(String url){
        if(isRecent(url))return recent.get(url);
        var result=inspect(url);recent.put(url,result);checkedAt.put(url,System.currentTimeMillis());failedAt.remove(url);return result;
    }
    boolean isRecent(String url){
        Long at=checkedAt.get(url);return at!=null&&System.currentTimeMillis()-at<60_000&&recent.containsKey(url);
    }
    static final long ANSWER_PIN_MS=10*60_000,FAILED_RETRY_MS=10*60_000;
    private final Map<String,Long> failedAt=new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> refreshing=java.util.concurrent.ConcurrentHashMap.newKeySet();
    java.util.concurrent.Executor background=command->Thread.ofVirtual().name("tls-pin-refresh").start(command);
    /**
     * The pin for an image answer after a device's full check, without waiting: the last successful inspection of
     * the last ten minutes unless a refresh failed after it, else none (the display then pins the certificate it
     * verified itself; current firmware prefers that anyway). A missing or older
     * one is refreshed in the background; after a failure the next try waits ten minutes, so an address the server
     * cannot reach no longer delays every answer by the connect timeout.
     */
    public Optional<String> pinForAnswer(String url){
        long now=System.currentTimeMillis();
        Long at=checkedAt.get(url),failed=failedAt.get(url);
        boolean fresh=at!=null&&now-at<60_000,waiting=failed!=null&&now-failed<FAILED_RETRY_MS;
        if(!fresh&&!waiting&&refreshing.add(url))background.execute(()->{
            try{recentInspection(url);failedAt.remove(url);}
            catch(RuntimeException e){failedAt.put(url,System.currentTimeMillis());}
            finally{refreshing.remove(url);}
        });
        var known=recent.get(url);at=checkedAt.get(url);failed=failedAt.get(url);
        // Not after a failed refresh: the certificate may have been renewed since that pin was taken.
        if(known==null||at==null||(failed!=null&&failed>=at)||System.currentTimeMillis()-at>=ANSWER_PIN_MS)return Optional.empty();
        return Optional.ofNullable(known.fingerprint());
    }
    /**
     * The root for pairing a device: from the address itself, or from the public certificate log when the server
     * cannot reach it (e.g. its own public host name without NAT loopback).
     */
    public Inspection forPairing(String url){
        try{return inspect(url);}
        catch(IllegalArgumentException e){
            if(certificateLog==null||!e.getMessage().endsWith("is not reachable from the server"))throw e;
            try{return new Inspection(certificateLog.root(URI.create(url).getHost()),null,true);}
            catch(IllegalArgumentException logged){throw new IllegalArgumentException(e.getMessage()+"; "+logged.getMessage());}
        }
    }
    /** Connects to an https address and validates chain and host name; IllegalArgumentException if it is not trusted. */
    public Inspection inspect(String url) {
        URI uri=URI.create(url);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null)throw new IllegalArgumentException("Not an https address: "+url);
        int port=uri.getPort()>0?uri.getPort():443;
        URI probe=probeFor(url,port);
        Connection connection;
        // An explicit LAN proxy address avoids the public route entirely (routers without NAT loopback).
        if(probe!=null&&!probeUrl.isBlank())connection=chain(uri,probe,"configured-probe");
        else{
            try{connection=chain(uri,uri,"system-dns");}
            catch(IllegalArgumentException e){
                // Never turn a rejected certificate into a successful fallback.
                if(!unreachable(e))throw e;
                connection=alternativeChain(uri,probe,e);
            }
        }
        try{
            Certificate[] chain=connection.certificates();var leaf=(X509Certificate)chain[0];
            String fingerprint=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(leaf.getEncoded()));
            String sha256=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));
            var details=new CertificateInfo(leaf.getSubjectX500Principal().getName(),leaf.getIssuerX500Principal().getName(),
                    leaf.getNotBefore().toInstant().toString(),leaf.getNotAfter().toInstant().toString(),sha256,connection.protocol());
            return new Inspection(trustRoot(chain),fingerprint,false,connection.route(),connection.target(),details);
        }catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("Certificate of "+url+" could not be read");}
    }
    private static boolean unreachable(IllegalArgumentException e){return e.getMessage().endsWith("is not reachable from the server");}
    private Connection alternativeChain(URI identity,URI probe,IllegalArgumentException failure){
        if(probe!=null){
            try{return chain(identity,probe,"lan-fallback");}
            catch(IllegalArgumentException e){if(!unreachable(e))throw e;failure=e;}
        }
        var configured=addresses==null?null:addresses.getIfAvailable();
        // Only an administrator-configured external hostname is sent to public resolvers, never a device IP.
        if(publicDns!=null&&configured!=null&&identity.toString().equals(configured.external())&&!ipLiteral(identity.getHost())){
            int port=identity.getPort()>0?identity.getPort():443;
            for(String ip:publicDns.resolve(identity.getHost())){
                String host=ip.contains(":")?"["+ip+"]":ip;
                URI target=URI.create("https://"+host+":"+port);
                try{
                    var result=chain(identity,target,"public-dns");
                    log.info("TLS certificate for {} verified through public DNS address {}",identity,ip);return result;
                }catch(IllegalArgumentException e){if(!unreachable(e))throw e;failure=e;}
            }
        }
        throw failure;
    }
    private static boolean ipLiteral(String host){
        try{IpNetworks.literal(host.startsWith("[")?host.substring(1,host.length()-1):host);return true;}
        catch(IllegalArgumentException e){return false;}
    }
    /** Only the configured external address may use this transport override; it is never announced to devices. */
    private URI probeFor(String url,int port){
        var configured=addresses==null?null:addresses.getIfAvailable();
        if(configured==null||!url.equals(configured.external()))return null;
        if(!probeUrl.isBlank()){
            ClientRegistry.validateServerUrl(probeUrl);
            URI probe=URI.create(probeUrl);
            if(!"https".equals(probe.getScheme()))throw new IllegalArgumentException("External TLS probe URL must use https");
            return probe;
        }
        String internal=configured.configuredInternal();
        if(internal.isBlank())return null;
        String host=URI.create(internal).getHost();
        if(host==null)return null;
        return URI.create("https://"+(host.contains(":")&&!host.startsWith("[")?"["+host+"]":host)+":"+port);
    }
    // The socket connects to the LAN address; SNI and certificate identity always refer to the external host.
    private Connection chain(URI identity,URI target,String route){
        String url=identity.toString();
        try{
            try(var transport=new java.net.Socket()){
                transport.connect(new InetSocketAddress(target.getHost(),target.getPort()>0?target.getPort():443),5000);
                try(var socket=(SSLSocket)tlsContext().getSocketFactory().createSocket(transport,identity.getHost(),
                        identity.getPort()>0?identity.getPort():443,true)){
                    var parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    parameters.setServerNames(List.of(new SNIHostName(identity.getHost())));socket.setSSLParameters(parameters);
                    socket.setSoTimeout(5000);socket.startHandshake();
                    String ip=transport.getInetAddress().getHostAddress();
                    String endpoint=(ip.contains(":")?"["+ip+"]":ip)+":"+transport.getPort();
                    return new Connection(socket.getSession().getPeerCertificates(),route,endpoint,socket.getSession().getProtocol());
                }
            }
        }catch(SSLHandshakeException|SSLPeerUnverifiedException e){
            log.warn("TLS certificate check for {} via {}:{} failed: {}: {}",url,target.getHost(),target.getPort()>0?target.getPort():443,e.getClass().getSimpleName(),e.getMessage());
            throw new IllegalArgumentException("Certificate of "+url+" is not trusted");
        }catch(Exception e){
            log.warn("TLS connection to {} via {}:{} failed: {}: {}",url,target.getHost(),target.getPort()>0?target.getPort():443,e.getClass().getSimpleName(),e.getMessage());
            // An HTTP listener can answer TLS with HTTP 400 (SSLException): a protocol mismatch, not a rejected
            // certificate. Keep the root-only certificate-log fallback; it never supplies an unverified pin.
            throw new IllegalArgumentException(url+" is not reachable from the server");
        }
    }
    byte[] trustRoot(Certificate[] chain){return root(chain);}
    SSLContext tlsContext() throws java.security.GeneralSecurityException {
        var context=SSLContext.getInstance("TLS");context.init(null,null,null);return context;
    }
    /** The trust anchor of a chain the default trust store accepts. */
    static byte[] root(Certificate[] chain){
        try{
            var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init((KeyStore)null);
            Set<TrustAnchor> anchors=new HashSet<>();
            for(var manager:factory.getTrustManagers())if(manager instanceof X509TrustManager x509)
                for(var issuer:x509.getAcceptedIssuers())anchors.add(new TrustAnchor(issuer,null));
            // A chain that already ends in a trusted root: drop it, the validator expects the path below the anchor.
            List<Certificate> path=new ArrayList<>(Arrays.asList(chain));
            var last=(X509Certificate)path.get(path.size()-1);
            for(var anchor:anchors)if(anchor.getTrustedCert().equals(last)){
                if(path.size()==1)return last.getEncoded();
                path.remove(path.size()-1);break;
            }
            var parameters=new PKIXParameters(anchors);parameters.setRevocationEnabled(false);
            var result=(PKIXCertPathValidatorResult)CertPathValidator.getInstance("PKIX")
                    .validate(CertificateFactory.getInstance("X.509").generateCertPath(path),parameters);
            return result.getTrustAnchor().getTrustedCert().getEncoded();
        }catch(Exception e){throw new IllegalArgumentException("The certificate chain does not lead to a trusted root");}
    }
}
