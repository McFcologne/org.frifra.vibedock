package org.frifra.vibedock.server;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * What displays report about themselves with every pull (header X-VibeDock-Diag, firmware newer than 0.1.1; older
 * firmware sends nothing and keeps working): the current start (boot id, run time, free memory and its minimum,
 * Wi-Fi signal and drops) and, once per start, how the start came about: its kind (power on, update, crash, ...), the
 * platform's reset code and the last state of the previous start as far as it survived the reset.
 * <p>
 * The current values are kept in memory only. Each new start is kept per display in
 * data/device-logs/&lt;identifier&gt;/starts.json (the last {@value #KEEP}); the boot id makes one start count once,
 * however often it is reported. Times are server times of the first report; the device's run times are seconds.
 */
@Component
class DeviceDiagnostics {
    private static final Logger log=LoggerFactory.getLogger(DeviceDiagnostics.class);
    static final int KEEP=20;
    /** Start kinds the firmware reports; anything else is stored as "other". */
    static final Set<String> KINDS=Set.of("power-on","update","setup","factory-reset","low-memory","external","crash","watchdog","brownout","software","deep-sleep","other");
    /** Kinds that are not a planned restart and not a power cut: what a support report counts as unexpected. */
    static final Set<String> UNEXPECTED=Set.of("crash","watchdog","brownout","software");
    /** Phases of the previous start, as the firmware names them. */
    static final Set<String> PHASES=Set.of("start","wifi","connect","tls","image","draw","config","log","ota","idle","portal");
    private static final Pattern IDENTIFIER=Pattern.compile("[A-Za-z0-9_.:-]{8,80}");

    /** The current start of a display as of its last pull; receivedAt is server time. */
    record Live(String bootId,Long uptimeSeconds,Long heapFree,Long heapMin,Integer rssi,Integer wifiDrops,long receivedAt,String ssid) {
        Live(String bootId,Long uptimeSeconds,Long heapFree,Long heapMin,Integer rssi,Integer wifiDrops,long receivedAt){
            this(bootId,uptimeSeconds,heapFree,heapMin,rssi,wifiDrops,receivedAt,null);
        }
    }
    /**
     * The network of a display for its card: Wi-Fi name (firmware 0.3.1 and newer, else null), signal, and how its last
     * pull reached the server: route "lan" (the internal address), "external" (the external address) or "other",
     * host the address it used.
     */
    record Network(String ssid,Integer rssi,String route,String host) {}
    private final Map<String,String[]> routes=new ConcurrentHashMap<>();
    /**
     * One start: seenAt is the server time of its first report. previous*: the last state of the start before, null
     * where it did not survive (power cut) or the firmware does not know it. crash*: what the crash of the start before
     * left (firmware 0.2.0 and newer): its code addresses in hex, pc first ("4200a1b2,4200c3d4"), the cause and the
     * faulting address where the chip gives them, the text of an abort or assert; null without one.
     */
    record Start(String bootId,long seenAt,String firmware,String board,String kind,Integer resetCode,
                 String previousBoot,String previousPhase,Long previousUptime,Long previousHeapFree,Long previousHeapMin,Integer previousRssi,Integer previousWifiDrops,
                 String crashCode,Long crashCause,String crashAddress,String crashText) {
        public boolean unexpected(){return UNEXPECTED.contains(kind);}
        /** The code addresses as 0x… for addr2line, separated by spaces; null without a crash. */
        public String crashAddresses(){return crashCode==null?null:String.join(" ",Arrays.stream(crashCode.split(",")).map(a->"0x"+a).toList());}
    }
    private final Map<String,Live> live=new ConcurrentHashMap<>();
    private final Path root;private final ObjectMapper json;private final Clock clock;
    DeviceDiagnostics(@Value("${vibedock.data-dir}") String dataDir,ObjectMapper json,Clock clock){
        this.root=Path.of(dataDir).resolve("device-logs");this.json=json;this.clock=clock;
    }

    /** The values of a diagnosis header, each checked; unknown keys and invalid values are left out. */
    static Map<String,String> parse(String header){
        var values=new LinkedHashMap<String,String>();
        if(header==null||header.length()>600)return values;
        for(String part:header.split(";")){
            int eq=part.indexOf('=');if(eq<=0)continue;
            String key=part.substring(0,eq).trim(),value=part.substring(eq+1).trim();
            boolean valid=switch(key){
                case "boot","prev" -> DeviceLogs.BOOT_ID.matcher(value).matches();
                case "up","heap","min","prevup","prevheap","prevmin" -> value.matches("\\d{1,10}");
                case "rssi","prevrssi" -> value.matches("-?\\d{1,3}");
                case "drops","prevdrops","reset" -> value.matches("\\d{1,5}");
                case "start" -> KINDS.contains(value);
                case "phase" -> PHASES.contains(value);
                case "panic" -> value.matches("[0-9a-f]{1,8}(,[0-9a-f]{1,8}){0,5}");
                case "cause" -> value.matches("\\d{1,10}");
                case "fault" -> value.matches("[0-9a-f]{1,8}");
                case "abort" -> value.matches("[A-Za-z0-9 .:_/()-]{1,60}");
                case "ssid" -> ssid(value)!=null;
                default -> false;
            };
            if(valid)values.put(key,value);
        }
        return values;
    }
    /** Takes the header of a pull; a start not seen before is stored. Without a boot id nothing is kept. */
    void report(String identifier,String header,String firmware,String board){
        var v=parse(header);String boot=v.get("boot");
        if(boot==null||!IDENTIFIER.matcher(identifier).matches())return;
        live.put(identifier,new Live(boot,longOf(v,"up"),longOf(v,"heap"),longOf(v,"min"),intOf(v,"rssi"),intOf(v,"drops"),clock.millis(),ssid(v.get("ssid"))));
        synchronized(this){
            try{
                var starts=new ArrayList<>(starts(identifier));
                if(starts.stream().anyMatch(s->s.bootId().equals(boot)))return;
                var start=new Start(boot,clock.millis(),firmware,board,v.getOrDefault("start","other"),intOf(v,"reset"),
                        v.get("prev"),v.get("phase"),longOf(v,"prevup"),longOf(v,"prevheap"),longOf(v,"prevmin"),intOf(v,"prevrssi"),intOf(v,"prevdrops"),
                        v.get("panic"),longOf(v,"cause"),v.get("fault"),v.get("abort"));
                starts.add(0,start);
                save(identifier,starts.subList(0,Math.min(KEEP,starts.size())));
                if(start.unexpected())log.warn("Display {} restarted unexpectedly: {} (reset {}), before in phase {} after {} s{}",identifier,start.kind(),
                        start.resetCode(),start.previousPhase()==null?"unknown":start.previousPhase(),start.previousUptime()==null?"?":start.previousUptime(),
                        start.crashCode()==null?"":", crashed at "+start.crashAddresses()+(start.crashText()==null?"":" ("+start.crashText()+")"));
                else log.info("Display {} started: {}",identifier,start.kind());
            }catch(IOException|RuntimeException e){log.warn("Could not keep the start of {}: {}",identifier,e.toString());}
        }
    }
    Optional<Live> live(String identifier){return Optional.ofNullable(live.get(identifier));}
    /**
     * The Wi-Fi name as the firmware sends it: the SSID's UTF-8 bytes, percent-encoded outside A–Z, a–z, 0–9 and
     * "-._~" (an SSID may contain ";" and "="). Null when it is not such a text of 1 to 32 bytes.
     */
    static String ssid(String encoded){
        if(encoded==null||!encoded.matches("([A-Za-z0-9._~-]|%[0-9A-Fa-f]{2}){1,32}"))return null;
        var bytes=new java.io.ByteArrayOutputStream();
        for(int i=0;i<encoded.length();i++){
            char c=encoded.charAt(i);
            if(c=='%'){bytes.write(Integer.parseInt(encoded.substring(i+1,i+3),16));i+=2;}else bytes.write(c);
        }
        String text=bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        return text.chars().anyMatch(ch->ch<32||ch==127)?null:text;
    }
    /** How the display's last pull reached the server (see Network). */
    void route(String identifier,String route,String host){if(IDENTIFIER.matcher(identifier).matches())routes.put(identifier,new String[]{route,host});}
    /** The display's network as of its last pull; null before its first pull since the server started. */
    Network network(String identifier){
        var l=live.get(identifier);var r=routes.get(identifier);
        if(l==null&&r==null)return null;
        return new Network(l==null?null:l.ssid(),l==null?null:l.rssi(),r==null?null:r[0],r==null?null:r[1]);
    }
    /** The display's starts, newest first; empty for displays that never reported one. */
    synchronized List<Start> starts(String identifier) throws IOException {
        if(!IDENTIFIER.matcher(identifier).matches())return List.of();
        Path file=root.resolve(identifier).resolve(DeviceLogs.STARTS);
        if(!Files.isRegularFile(file))return List.of();
        try{return json.readValue(file.toFile(),new TypeReference<List<Start>>(){});}
        catch(tools.jackson.core.JacksonException e){log.warn("Unreadable {}: {}",file,e.toString());return List.of();}
    }
    void forget(String identifier){live.remove(identifier);routes.remove(identifier);}
    private void save(String identifier,List<Start> starts) throws IOException {
        Path dir=Files.createDirectories(root.resolve(identifier));
        Path temporary=Files.createTempFile(dir,"starts-",".tmp");
        try{
            try{Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            json.writeValue(temporary.toFile(),starts);
            Files.move(temporary,dir.resolve(DeviceLogs.STARTS),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
    private static Long longOf(Map<String,String> v,String key){return v.containsKey(key)?Long.valueOf(v.get(key)):null;}
    private static Integer intOf(Map<String,String> v,String key){return v.containsKey(key)?Integer.valueOf(v.get(key)):null;}
}
