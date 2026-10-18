package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The logs read from displays, kept per device below data/device-logs/<identifier>/: the last {@value #KEEP} of each
 * device with the time they were read, how (USB or network), and the firmware and board of the display. They feed
 * the device's log page and support bundles. Keys and web display secrets that might show up are removed on saving.
 */
@Component
class DeviceLogs {
    static final int KEEP=10,MAX_LINES=500,MAX_LINE=400,MAX_BYTES=64*1024;
    /** The starts the display reported (DeviceDiagnostics), beside its logs. */
    static final String STARTS="starts.json";
    static final List<String> SOURCES=List.of("usb","net");
    private static final Pattern IDENTIFIER=Pattern.compile("[A-Za-z0-9_.:-]{8,80}");
    private static final Pattern NAME=Pattern.compile("\\d{13}-(usb|net)(-\\d{1,2})?\\.json");
    private static final Pattern BEARER=Pattern.compile("(?i)(bearer\\s+)\\S+");
    private static final Pattern HEX64=Pattern.compile("(?i)\\b[0-9a-f]{64}\\b");
    private static final Pattern WEB=Pattern.compile("/web/[^\\s\"'/?#]+");

    /**
     * A saved log without its lines; name identifies it within the device. savedAt is when the server received it
     * (server time), not when its events happened: a log sent now can hold events of earlier starts. bootId: the
     * start of the display the log was sent in (or its newest start line names), uptimeSeconds its run time then;
     * both null for firmware that does not report them.
     */
    record Entry(String name,long savedAt,String source,String firmware,String board,int lines,String bootId,Long uptimeSeconds) {}
    record Log(Entry entry,List<String> lines) {}
    private record Stored(long savedAt,String source,String firmware,String board,List<String> lines,String bootId,Long uptimeSeconds) {}
    /** Boot ids as the firmware writes them: 8 hex digits. */
    static final Pattern BOOT_ID=Pattern.compile("[0-9a-f]{8}");
    private static final Pattern BOOT_IN_LINE=Pattern.compile("\\bboot=([0-9a-f]{8})\\b");

    /** How long a display may answer a request to send its log over the network. */
    static final java.time.Duration REQUEST_VALID=java.time.Duration.ofMinutes(10);
    private record Request(String id,long until) {}
    private final Map<String,Request> requests=new HashMap<>();
    /**
     * The last request per display, kept after it was answered or expired, for the troubleshooting page: when it was
     * made, until when the display may answer, and whether the display could be asked to pull at once.
     */
    record RequestStatus(long requestedAt,long until,DevicePoke.Reach reach) {}
    private final Map<String,RequestStatus> lastRequests=new HashMap<>();
    private final java.security.SecureRandom random=new java.security.SecureRandom();
    private final Path root;private final ObjectMapper json;private final Clock clock;
    DeviceLogs(@Value("${vibedock.data-dir}") String dataDir,ObjectMapper json,Clock clock){
        this.root=Path.of(dataDir).resolve("device-logs");this.json=json;this.clock=clock;
    }

    /** Saves a log read from a display and drops its oldest logs beyond {@value #KEEP}. */
    synchronized Entry save(String identifier,String source,String firmware,String board,List<String> lines) throws IOException {
        return save(identifier,source,firmware,board,lines,null,null);
    }
    /** The same with the start the display sent it in; without one the newest start line of the log names it. */
    synchronized Entry save(String identifier,String source,String firmware,String board,List<String> lines,String bootId,Long uptimeSeconds) throws IOException {
        Path dir=directory(identifier);
        if(!SOURCES.contains(source))throw new IllegalArgumentException("Unknown log source");
        if(lines==null)throw new IllegalArgumentException("No log lines");
        List<String> clean=new ArrayList<>();int bytes=0;
        for(String line:lines.subList(Math.max(0,lines.size()-MAX_LINES),lines.size())){
            String text=redact(line==null?"":line.replaceAll("[\\p{Cntrl}&&[^\\t]]"," "));
            if(text.length()>MAX_LINE)text=text.substring(0,MAX_LINE)+" …";
            bytes+=text.length()+1;if(bytes>MAX_BYTES)break;
            clean.add(text);
        }
        long now=clock.millis();
        Files.createDirectories(dir);
        String base=String.format(Locale.ROOT,"%013d-%s",now,source),name=base+".json";
        for(int i=1;Files.exists(dir.resolve(name));i++)name=base+"-"+i+".json";
        String boot=bootId!=null&&BOOT_ID.matcher(bootId).matches()?bootId:newestBoot(clean);
        Long uptime=uptimeSeconds!=null&&uptimeSeconds>=0&&uptimeSeconds<10L*365*86400?uptimeSeconds:null;
        var stored=new Stored(now,source,short40(firmware),short40(board),clean,boot,uptime);
        Path temporary=Files.createTempFile(dir,"log-",".tmp");
        try{
            try{Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            json.writeValue(temporary.toFile(),stored);
            Files.move(temporary,dir.resolve(name),StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(temporary);}
        var names=names(dir);
        for(String old:names.subList(Math.min(KEEP,names.size()),names.size()))Files.deleteIfExists(dir.resolve(old));
        return entry(name,stored);
    }
    /** Every display with saved logs (also displays that are not paired). */
    synchronized List<String> identifiers() throws IOException {
        if(!Files.isDirectory(root))return List.of();
        try(var dirs=Files.list(root)){
            return dirs.filter(Files::isDirectory).map(d->d.getFileName().toString()).filter(n->IDENTIFIER.matcher(n).matches()).sorted().toList();
        }
    }
    /** The device's logs, newest first. */
    synchronized List<Entry> list(String identifier) throws IOException {
        Path dir=directory(identifier);List<Entry> entries=new ArrayList<>();
        for(String name:names(dir))entries.add(read(dir,name).entry());
        return entries;
    }
    synchronized int count(String identifier){
        try{return names(directory(identifier)).size();}catch(IOException|IllegalArgumentException e){return 0;}
    }
    synchronized Optional<Log> read(String identifier,String name) throws IOException {
        Path dir=directory(identifier);
        if(!NAME.matcher(name).matches()||!Files.isRegularFile(dir.resolve(name)))return Optional.empty();
        return Optional.of(read(dir,name));
    }
    synchronized boolean delete(String identifier,String name) throws IOException {
        if(!NAME.matcher(name).matches())return false;
        return Files.deleteIfExists(directory(identifier).resolve(name));
    }
    /** Removes every log of a device, e.g. when the device is removed. */
    synchronized void deleteAll(String identifier) throws IOException {
        Path dir=directory(identifier);requests.remove(identifier);lastRequests.remove(identifier);
        Files.deleteIfExists(dir.resolve(STARTS));
        for(String name:names(dir))Files.deleteIfExists(dir.resolve(name));
        try{Files.deleteIfExists(dir);}catch(DirectoryNotEmptyException ignored){}
    }

    /**
     * Asks a display to send its log: the id goes to the display with its next image (X-VibeDock-Send-Log) and is
     * valid for one upload within {@link #REQUEST_VALID}. A new request replaces an open one.
     */
    synchronized String request(String identifier){
        directory(identifier);
        byte[] bytes=new byte[16];random.nextBytes(bytes);
        String id=HexFormat.of().formatHex(bytes);
        long now=clock.millis();
        requests.put(identifier,new Request(id,now+REQUEST_VALID.toMillis()));
        lastRequests.put(identifier,new RequestStatus(now,now+REQUEST_VALID.toMillis(),null));
        return id;
    }
    /** Notes whether the display could be asked to pull at once for its last request. */
    synchronized void reached(String identifier,long requestedAt,DevicePoke.Reach reach){
        var last=lastRequests.get(identifier);
        if(last!=null&&last.requestedAt()==requestedAt)lastRequests.put(identifier,new RequestStatus(last.requestedAt(),last.until(),reach));
    }
    synchronized Optional<RequestStatus> lastRequest(String identifier){return Optional.ofNullable(lastRequests.get(identifier));}
    /**
     * Where the last request of a display stands: RECEIVED once a log arrived over the network after it, PENDING
     * while the display may still answer (also when it could not be reached at once: then it sends it with its next
     * pull), TIMED_OUT afterwards. Logs saved before stay either way.
     */
    enum RequestState { NONE, PENDING, RECEIVED, TIMED_OUT }
    synchronized RequestState requestState(String identifier) throws IOException {
        var last=lastRequests.get(identifier);
        if(last==null)return RequestState.NONE;
        for(var entry:list(identifier))if("net".equals(entry.source())&&entry.savedAt()>=last.requestedAt())return RequestState.RECEIVED;
        return clock.millis()<=last.until()&&requests.containsKey(identifier)?RequestState.PENDING:RequestState.TIMED_OUT;
    }
    /** The open request of a display, if any. */
    synchronized Optional<String> pendingRequest(String identifier){
        var open=requests.get(identifier);
        if(open==null)return Optional.empty();
        if(open.until()<clock.millis()){requests.remove(identifier);return Optional.empty();}
        return Optional.of(open.id());
    }
    /** Uses up the open request when the id matches; false for a wrong, used or expired id. */
    synchronized boolean takeRequest(String identifier,String id){
        var open=requests.get(identifier);
        if(open==null||id==null||open.until()<clock.millis()
                ||!java.security.MessageDigest.isEqual(open.id().getBytes(java.nio.charset.StandardCharsets.US_ASCII),id.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))return false;
        requests.remove(identifier);return true;
    }

    /** Key-like values and web display links never stay in a saved log. */
    static String redact(String line){
        String text=BEARER.matcher(line).replaceAll("$1[redacted]");
        text=HEX64.matcher(text).replaceAll("[redacted]");
        return WEB.matcher(text).replaceAll("/web/[redacted]");
    }
    private Path directory(String identifier){
        if(identifier==null||!IDENTIFIER.matcher(identifier).matches()||identifier.chars().allMatch(c->c=='.'))
            throw new IllegalArgumentException("Invalid device identifier");
        return root.resolve(identifier);
    }
    private static List<String> names(Path dir) throws IOException {
        if(!Files.isDirectory(dir))return List.of();
        try(var files=Files.list(dir)){
            return files.map(p->p.getFileName().toString()).filter(n->NAME.matcher(n).matches()).sorted(Comparator.reverseOrder()).toList();
        }
    }
    private Log read(Path dir,String name) throws IOException {
        var stored=json.readValue(dir.resolve(name).toFile(),Stored.class);
        return new Log(entry(name,stored),stored.lines()==null?List.of():stored.lines());
    }
    private static Entry entry(String name,Stored s){return new Entry(name,s.savedAt(),s.source(),s.firmware(),s.board(),s.lines()==null?0:s.lines().size(),s.bootId(),s.uptimeSeconds());}
    /** The boot id of the newest start line ("Start v… boot=…") of a log, if the firmware writes them. */
    static String newestBoot(List<String> lines){
        for(int i=lines.size()-1;i>=0;i--){
            String line=lines.get(i);
            if(!line.contains("Start v"))continue;
            var m=BOOT_IN_LINE.matcher(line);if(m.find())return m.group(1);
        }
        return null;
    }
    private static String short40(String value){
        if(value==null)return null;String text=value.replaceAll("[^0-9A-Za-z.+_-]","");
        return text.isEmpty()?null:text.substring(0,Math.min(40,text.length()));
    }
}
