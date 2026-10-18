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
    /**
     * Root certificate (DER) a trusted chain leads to, and the SHA-1 fingerprint of the server certificate itself.
     * fromLog: the root comes from the public certificate log because the server cannot reach the address; then
     * there is no fingerprint.
     */
    public record Inspection(byte[] root,String fingerprint,boolean fromLog) {
        public Inspection(byte[] root,String fingerprint){this(root,fingerprint,false);}
    }
    @org.springframework.beans.factory.annotation.Autowired(required=false) private CertificateLog certificateLog;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private org.springframework.beans.factory.ObjectProvider<ServerAddresses> addresses;
    @org.springframework.beans.factory.annotation.Value("${vibedock.external-tls-probe-url:}") private String probeUrl="";
    private final Map<String,Inspection> recent=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,Long> checkedAt=new java.util.concurrent.ConcurrentHashMap<>();

    /** DER-encoded root certificate that the address's chain leads to; IllegalArgumentException if it is not trusted. */
    public byte[] rootFor(String url){return inspect(url).root();}
    /** Like {@link #inspect} but reuses a result younger than a minute, for devices doing their full check. */
    public Inspection recentInspection(String url){
        Long at=checkedAt.get(url);
        if(at!=null&&System.currentTimeMillis()-at<60_000&&recent.containsKey(url))return recent.get(url);
        var result=inspect(url);recent.put(url,result);checkedAt.put(url,System.currentTimeMillis());return result;
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
        Certificate[] chain;
        // An explicit LAN proxy address avoids the public route entirely (routers without NAT loopback).
        if(probe!=null&&!probeUrl.isBlank())chain=chain(uri,probe);
        else{
            try{chain=chain(uri,uri);}
            catch(IllegalArgumentException e){
                // Never turn a rejected certificate into a successful fallback.
                if(probe==null||!e.getMessage().endsWith("is not reachable from the server"))throw e;
                chain=chain(uri,probe);
            }
        }
        try{
            String fingerprint=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(chain[0].getEncoded()));
            return new Inspection(trustRoot(chain),fingerprint);
        }catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("Certificate of "+url+" could not be read");}
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
    private Certificate[] chain(URI identity,URI target){
        String url=identity.toString();
        try{
            try(var transport=new java.net.Socket()){
                transport.connect(new InetSocketAddress(target.getHost(),target.getPort()>0?target.getPort():443),5000);
                try(var socket=(SSLSocket)tlsContext().getSocketFactory().createSocket(transport,identity.getHost(),
                        identity.getPort()>0?identity.getPort():443,true)){
                    var parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    parameters.setServerNames(List.of(new SNIHostName(identity.getHost())));socket.setSSLParameters(parameters);
                    socket.setSoTimeout(5000);socket.startHandshake();
                    return socket.getSession().getPeerCertificates();
                }
            }
        }catch(SSLException e){throw new IllegalArgumentException("Certificate of "+url+" is not trusted");}
        catch(Exception e){throw new IllegalArgumentException(url+" is not reachable from the server");}
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
