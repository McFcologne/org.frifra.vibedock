package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The support file (Troubleshooting): everything needed to analyse a problem, server and displays alike, in one file
 * that is safe to attach to a public ticket. It holds the user's description of the problem, a summary of the installation, the data sources without
 * credentials, the displays, the server and access log of the chosen days and the saved display logs; all of it
 * passes {@link SupportRedactor}. The file is encrypted (AES-256-GCM, PBKDF2 key like the data source export) with a
 * random key in GUID form that is shown once and not stored; the key goes to the maintainer separately.
 */
@Service
class SupportFile {
    static final String FORMAT="vibedock-support";
    /** Text of the server and access log at most, newest kept; the file stays small enough for a ticket. */
    static final int MAX_LOG_CHARS=3_000_000,MAX_FILE=12_000_000;
    record Summary(String version,String java,String platform,boolean docker,String timeZone,long uptimeSeconds,long createdAt,
                   int days,int pollSeconds,int ipRules,int sources,int displays,String testMode,boolean hostsHidden) {}
    record Source(String type,String label,boolean suspended,String status,Long observedAt) {}
    record Display(String identifier,String name,String board,String display,String firmware,String status,Long lastPullAt,String ip,
                   boolean web,boolean ota,boolean otaNeedsPairing,boolean updating,String updateError,boolean flipped,boolean serverFooter) {}
    record DeviceLog(String identifier,long savedAt,String source,String firmware,String board,List<String> lines) {}
    /** The problem in the user's words (all optional); displays: the identifiers of the displays it concerns. */
    record Report(String title,String happened,String expected,String steps,String since,List<String> displays) {
        static final int MAX_TITLE=200,MAX_TEXT=4000;
        Report {
            title=clip(title,MAX_TITLE);happened=clip(happened,MAX_TEXT);expected=clip(expected,MAX_TEXT);steps=clip(steps,MAX_TEXT);since=clip(since,MAX_TITLE);
            displays=displays==null?List.of():displays.stream().filter(Objects::nonNull).distinct().limit(100).toList();
        }
        static final Report NONE=new Report(null,null,null,null,null,null);
        private static String clip(String text,int max){
            if(text==null)return null;
            // Line breaks and tabs stay, other control characters go.
            text=text.replace("\r\n","\n").replaceAll("[\\p{Cntrl}&&[^\n\t]]","").strip();
            return text.isEmpty()?null:text.length()>max?text.substring(0,max)+" …":text;
        }
    }
    record Contents(Report report,Summary summary,List<Source> sources,List<Display> displays,List<String> serverLog,List<String> accessLog,List<DeviceLog> deviceLogs) {}
    record Created(String name,String key,byte[] bytes) {}

    private final ApplicationInfo info;private final SettingsStore settings;private final UsageService usage;private final ClientRegistry registry;
    private final DeviceLogs logs;private final ObjectMapper json;private final Clock clock;private final Path logDir;
    private final SecureRandom random=new SecureRandom();
    SupportFile(ApplicationInfo info,SettingsStore settings,UsageService usage,ClientRegistry registry,DeviceLogs logs,ObjectMapper json,Clock clock,
                @Value("${vibedock.log-dir}") String logDir){
        this.info=info;this.settings=settings;this.usage=usage;this.registry=registry;this.logs=logs;this.json=json;this.clock=clock;this.logDir=Path.of(logDir);
    }

    Created create(int days,boolean hideHosts) throws IOException {return create(days,hideHosts,Report.NONE);}
    /**
     * A new support file of the last {@code days} days (1–14) with the user's description; hideHosts also removes host
     * names and public addresses. The description passes the redactor too; unknown display identifiers are dropped.
     */
    Created create(int days,boolean hideHosts,Report report) throws IOException {
        if(days<1||days>14)throw new IllegalArgumentException("Choose 1 to 14 days");
        var redactor=new SupportRedactor(secrets(),hideHosts);
        var known=registry.views().stream().map(ClientRegistry.View::identifier).collect(java.util.stream.Collectors.toSet());
        var r=report==null?Report.NONE:report;
        r=new Report(redactor.text(r.title()),redactor.text(r.happened()),redactor.text(r.expected()),redactor.text(r.steps()),redactor.text(r.since()),
                r.displays().stream().filter(known::contains).toList());
        var contents=contents(days,hideHosts,redactor,r);
        String key=UUID.randomUUID().toString();
        byte[] salt=new byte[16],nonce=new byte[12];random.nextBytes(salt);random.nextBytes(nonce);
        byte[] plain=json.writeValueAsBytes(contents);
        try{
            byte[] data=SourceTransfer.cipher(Cipher.ENCRYPT_MODE,FORMAT,key,salt,SourceTransfer.ITERATIONS,nonce).doFinal(plain);
            var b64=Base64.getEncoder();
            byte[] file=json.writerWithDefaultPrettyPrinter().writeValueAsBytes(new SourceTransfer.Envelope(FORMAT,SourceTransfer.VERSION,"PBKDF2-HMAC-SHA256",
                    SourceTransfer.ITERATIONS,b64.encodeToString(salt),"AES-256-GCM",b64.encodeToString(nonce),b64.encodeToString(data)));
            String name="vibedock-support-"+DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").format(LocalDateTime.now(clock))+".vdsupport";
            return new Created(name,key,file);
        }catch(GeneralSecurityException e){throw new IllegalStateException("Encryption unavailable",e);}
        finally{Arrays.fill(plain,(byte)0);}
    }

    /** Decrypts a support file in memory for the analysis; nothing of it is stored. */
    Contents open(String content,String key){
        if(content==null||content.isBlank())throw new IllegalArgumentException("Choose a support file");
        if(content.length()>MAX_FILE*2)throw new IllegalArgumentException("The file is too large for a VibeDock support file");
        SourceTransfer.Envelope envelope;
        try{envelope=json.readValue(content,SourceTransfer.Envelope.class);}catch(IOException e){throw new IllegalArgumentException("This is not a VibeDock support file");}
        if(!FORMAT.equals(envelope.format())||envelope.version()!=SourceTransfer.VERSION||!"AES-256-GCM".equals(envelope.cipher())||!"PBKDF2-HMAC-SHA256".equals(envelope.kdf()))
            throw new IllegalArgumentException("This is not a VibeDock support file of a supported version");
        if(envelope.iterations()<100_000||envelope.iterations()>5_000_000)throw new IllegalArgumentException("The file uses unsupported key settings");
        byte[] plain=null;
        try{
            var b64=Base64.getDecoder();byte[] salt=b64.decode(envelope.salt()),nonce=b64.decode(envelope.nonce());
            if(salt.length!=16||nonce.length!=12)throw new IllegalArgumentException("The support file is damaged");
            plain=SourceTransfer.cipher(Cipher.DECRYPT_MODE,FORMAT,key==null?"":key.trim(),salt,envelope.iterations(),nonce).doFinal(b64.decode(envelope.data()));
            return json.readValue(plain,Contents.class);
        }catch(AEADBadTagException e){throw new IllegalArgumentException("Wrong key, or the file was changed");}
        catch(GeneralSecurityException|IOException|IllegalArgumentException e){
            if(e instanceof IllegalArgumentException i&&i.getMessage()!=null&&i.getMessage().startsWith("The support"))throw i;
            throw new IllegalArgumentException("The support file is damaged");
        }finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }

    private Contents contents(int days,boolean hideHosts,SupportRedactor redactor,Report report) throws IOException {
        long now=clock.millis();Instant since=clock.instant().minus(Duration.ofDays(days));
        var s=settings.get();var snapshot=usage.snapshot();
        var sources=new ArrayList<Source>();
        for(var a:s.accounts()){
            var p=snapshot.providers().get(a.id());
            sources.add(new Source(a.type(),redactor.text(a.label()),a.suspended(),p==null?null:p.status(),p==null?null:p.observedAt()));
        }
        var displays=new ArrayList<Display>();
        for(var v:registry.views())
            displays.add(new Display(v.identifier(),redactor.text(v.name()),v.board().id(),v.display().id(),v.firmware(),v.status(),v.lastPullAt(),
                    redactor.text(v.ip()),v.web()!=null,v.ota(),v.otaNeedsPairing(),v.updating(),redactor.text(v.updateError()),v.flipped(),v.serverFooter()));
        var deviceLogs=new ArrayList<DeviceLog>();
        for(String id:logs.identifiers())for(var entry:logs.list(id)){
            if(entry.savedAt()<since.toEpochMilli())continue;
            var log=logs.read(id,entry.name());
            log.ifPresent(l->deviceLogs.add(new DeviceLog(id,entry.savedAt(),entry.source(),entry.firmware(),entry.board(),l.lines().stream().map(redactor::text).toList())));
        }
        var runtime=ManagementFactory.getRuntimeMXBean();
        var summary=new Summary(info.getVersion(),System.getProperty("java.version"),System.getProperty("os.name")+" "+System.getProperty("os.arch"),
                Files.exists(Path.of("/.dockerenv")),ZoneId.systemDefault().getId(),runtime.getUptime()/1000,now,days,registry.pollSeconds(),
                s.allowedIps().size(),s.accounts().size(),displays.size(),info.getTestMode(),hideHosts);
        return new Contents(report,summary,sources,displays,logLines("vibedock.log",since,redactor),logLines("access.",since,redactor),deviceLogs);
    }

    private static final Pattern STAMP=Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2}:\\d{2})");
    /**
     * The lines of the server log (vibedock.log and its rotated .gz files) or of the access logs (access.*.log) since
     * the given time, oldest first, the newest {@value #MAX_LOG_CHARS} characters at most. Lines without a time (stack
     * traces) go with the line before them.
     */
    private List<String> logLines(String prefix,Instant since,SupportRedactor redactor) throws IOException {
        if(!Files.isDirectory(logDir))return List.of();
        List<Path> files;
        try(var list=Files.list(logDir)){
            files=list.filter(p->p.getFileName().toString().startsWith(prefix)&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
                    .filter(p->{try{return Files.getLastModifiedTime(p).toInstant().isAfter(since);}catch(IOException e){return false;}})
                    .sorted(Comparator.comparing(p->{try{return Files.getLastModifiedTime(p);}catch(IOException e){return java.nio.file.attribute.FileTime.fromMillis(0);}}))
                    .toList();
        }
        var lines=new ArrayDeque<String>();long chars=0;boolean keep=false;
        String sinceText=LocalDateTime.ofInstant(since,ZoneId.systemDefault()).toString().replace('T',' ');
        for(Path file:files){
            InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS);
            if(file.getFileName().toString().endsWith(".gz"))in=new GZIPInputStream(in);
            try(var reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
                for(String line;(line=reader.readLine())!=null;){
                    var m=STAMP.matcher(line);
                    if(m.find())keep=(m.group(1)+" "+m.group(2)).compareTo(sinceText)>=0;
                    if(!keep)continue;
                    String text=redactor.text(line.length()>2000?line.substring(0,2000)+" …":line);
                    lines.addLast(text);chars+=text.length()+1;
                    while(chars>MAX_LOG_CHARS&&!lines.isEmpty())chars-=lines.removeFirst().length()+1;
                }
            }
        }
        return List.copyOf(lines);
    }
    /** Values that must never leave the server, removed literally: hand-entered keys and web display secrets. */
    private List<String> secrets(){
        var list=new ArrayList<String>();
        for(var a:settings.get().accounts()){list.add(a.manual().token());list.add(a.manual().accountId());}
        for(var v:registry.views())if(v.web()!=null)list.add(v.web().secret());
        return list;
    }
}
