package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Root certificate of an https address the server cannot reach itself (e.g. its own public host name behind a router
 * without NAT loopback). Every publicly trusted certificate is in the Certificate Transparency logs: the server takes
 * the newest valid certificate for the host name from crt.sh, loads its issuer from the address in the certificate
 * (Authority Information Access), checks the signature and validates the issuer against the Java trust store. The
 * device still checks host name and chain itself when it connects; this only chooses the root it trusts.
 * Results are kept for a day and refreshed once a day; when crt.sh fails, a result up to 30 days old is used.
 */
@Component
public class CertificateLog {
    private static final Logger log=LoggerFactory.getLogger(CertificateLog.class);
    static final long DAY=86_400,MAX_AGE=30*DAY;
    private record Cached(byte[] root,long fetchedAt) {}
    private final Map<String,Cached> cache=new ConcurrentHashMap<>();
    private final HttpClient http;private final ObjectMapper json;private final Clock clock;
    private final ObjectProvider<ServerAddresses> addresses;
    private final String base;
    /** The trust anchor a chain below it leads to (TlsTrust.root); replaceable for tests with their own root. */
    Function<Certificate[],byte[]> anchorOf=TlsTrust::root;

    CertificateLog(HttpClient http,ObjectMapper json,Clock clock,ObjectProvider<ServerAddresses> addresses,
                   @Value("${vibedock.certificate-log:https://crt.sh}") String base){
        this.http=http;this.json=json;this.clock=clock;this.addresses=addresses;this.base=base.replaceAll("/+$","");
    }

    /** DER root for the host name; IllegalArgumentException with the reason when there is none. */
    public byte[] root(String host){return root(host,false);}
    private synchronized byte[] root(String host,boolean refresh){
        String key=host.toLowerCase(Locale.ROOT);long now=clock.instant().getEpochSecond();var cached=cache.get(key);
        if(!refresh&&cached!=null&&now-cached.fetchedAt()<DAY)return cached.root();
        try{
            byte[] root=fetch(key);cache.put(key,new Cached(root,now));return root;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalArgumentException("certificate log lookup interrupted");}
        catch(Exception e){
            if(cached!=null&&now-cached.fetchedAt()<MAX_AGE){
                log.warn("Certificate log lookup for {} failed ({}); using the result from {}",key,e.getMessage(),Instant.ofEpochSecond(cached.fetchedAt()));
                return cached.root();
            }
            throw new IllegalArgumentException("no certificate found in the public certificate log (crt.sh): "+e.getMessage());
        }
    }

    /** Once a day, and right after start, for the configured external address when the server cannot reach it. */
    @Scheduled(cron="0 23 4 * * *")
    @EventListener(ApplicationReadyEvent.class)
    public void refreshExternal(){
        var provider=addresses.getIfAvailable();if(provider==null)return;
        String url=provider.external();
        if(url==null||!url.startsWith("https://"))return;
        Thread.ofVirtual().name("certificate-log").start(()->{
            String host=URI.create(url).getHost();
            try{root(host,true);log.info("Certificate log: root for {} refreshed",host);}
            catch(IllegalArgumentException e){log.warn("Certificate log: {} for {}",e.getMessage(),host);}
        });
    }

    private byte[] fetch(String host) throws Exception {
        var list=json.readTree(get(base+"/?q="+URLEncoder.encode(host,StandardCharsets.UTF_8)+"&output=json&exclude=expired"));
        Instant now=clock.instant();
        record Entry(long id,Instant notBefore) {}
        List<Entry> candidates=new ArrayList<>();
        for(var item:list){
            Instant from=utc(item.path("not_before").asText()),until=utc(item.path("not_after").asText());
            if(from==null||until==null||from.isAfter(now)||!until.isAfter(now))continue;
            boolean named=Arrays.stream(item.path("name_value").asText("").split("\\s+")).anyMatch(n->matches(n,host));
            if(named)candidates.add(new Entry(item.path("id").asLong(),from));
        }
        if(candidates.isEmpty())throw new IllegalStateException("no valid certificate for "+host);
        candidates.sort(Comparator.comparing(Entry::notBefore).reversed());
        Exception last=null;
        for(var entry:candidates.subList(0,Math.min(3,candidates.size()))){
            try{return rootOf(certificate(get(base+"/?d="+entry.id())),host);}
            catch(InterruptedException e){throw e;}
            catch(Exception e){last=e;}
        }
        throw new IllegalStateException(last==null?"no usable certificate":last.getMessage());
    }

    /** Root of a leaf certificate: its issuer from the address in the certificate, signature checked, then validated. */
    byte[] rootOf(X509Certificate leaf,String host) throws Exception {
        if(!names(leaf).stream().anyMatch(n->matches(n,host)))throw new IllegalStateException("certificate does not name "+host);
        leaf.checkValidity(Date.from(clock.instant()));
        String issuerUrl=caIssuers(leaf);
        if(issuerUrl==null)throw new IllegalStateException("certificate names no issuer address");
        var issuers=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(get(issuerUrl)));
        for(var issuer:issuers){
            try{leaf.verify(issuer.getPublicKey());}catch(Exception e){continue;}
            byte[] root=anchorOf.apply(new Certificate[]{issuer});
            if(root!=null)return root;
        }
        throw new IllegalStateException("the issuer of the certificate could not be verified");
    }

    static boolean matches(String name,String host){
        String n=name.toLowerCase(Locale.ROOT).trim(),h=host.toLowerCase(Locale.ROOT);
        if(n.equals(h))return true;
        return n.startsWith("*.")&&h.endsWith(n.substring(1))&&h.indexOf('.')==h.length()-n.length()+1;
    }
    private static List<String> names(X509Certificate certificate) throws CertificateParsingException {
        List<String> names=new ArrayList<>();
        var alternatives=certificate.getSubjectAlternativeNames();
        if(alternatives!=null)for(var a:alternatives)if(Integer.valueOf(2).equals(a.get(0)))names.add((String)a.get(1));
        return names;
    }
    /** The caIssuers address of the Authority Information Access extension (1.3.6.1.5.5.7.1.1), or null. */
    static String caIssuers(X509Certificate certificate){
        byte[] value=certificate.getExtensionValue("1.3.6.1.5.5.7.1.1");if(value==null)return null;
        byte[] oid={0x06,0x08,0x2b,0x06,0x01,0x05,0x05,0x07,0x30,0x02};  // id-ad-caIssuers
        outer:
        for(int i=0;i+oid.length<value.length;i++){
            for(int j=0;j<oid.length;j++)if(value[i+j]!=oid[j])continue outer;
            int p=i+oid.length;if(p+1>=value.length||(value[p]&0xff)!=0x86)continue;  // [6] uniformResourceIdentifier
            int length=value[p+1]&0xff,start=p+2;
            if(length==0x81&&p+2<value.length){length=value[p+2]&0xff;start=p+3;}
            if(start+length>value.length)return null;
            String uri=new String(value,start,length,StandardCharsets.US_ASCII);
            if(uri.startsWith("http://")||uri.startsWith("https://"))return uri;
        }
        return null;
    }
    private static Instant utc(String text){
        try{return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);}catch(Exception e){return null;}
    }
    private static X509Certificate certificate(byte[] bytes) throws CertificateException {
        return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(bytes));
    }
    /** crt.sh often takes half a minute to answer a search; the result is cached and refreshed in the background. */
    private byte[] get(String url) throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()!=200)throw new IllegalStateException(URI.create(url).getHost()+" answered HTTP "+response.statusCode());
        if(response.body().length>4_000_000)throw new IllegalStateException("answer too large");
        return response.body();
    }
}
