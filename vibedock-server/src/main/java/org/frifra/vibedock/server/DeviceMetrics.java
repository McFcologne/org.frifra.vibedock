package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The measured values displays send with every pull (header X-VibeDock-Metrics, firmware newer than 0.4.2; older
 * firmware sends nothing). The header is
 * <pre>1;up;free;lowest;block;afterTls;tlsStack;pictureMs;rssi;drops;mv;bright;activeS;dimS</pre>
 * The version comes first and the order never changes; new fields are only appended and unknown trailing fields are
 * ignored. An empty field was not measured. Memory is in units of 100 bytes, the picture time in units of 100 ms and
 * the voltage in units of 10 mV, all in hex; the signal is a signed hex number (dBm); run time, drops, brightness (%),
 * active and dimmed seconds are plain numbers. "lowest" is the lowest free heap since the display's last request.
 * <p>
 * One sample per display is kept every {@value #INTERVAL_SECONDS} s (and with every new start) in
 * data/device-logs/&lt;identifier&gt;/metrics-YYYY-MM-DD.jsonl (one file per day, UTC), one JSON line each, for the days
 * of the retention setting (Settings, default {@value #DEFAULT_KEEP_DAYS}); the administration shows at most 30 days at a
 * time and pages back through the rest. A query reads only the day files it needs. A kept sample
 * carries the header of its pull as received and the lowest free heap of all pulls since the sample before, so a
 * dip between two samples is not lost. Everything else (curves, key figures, export) is derived from these lines.
 */
@Component
class DeviceMetrics {
    private static final Logger log=LoggerFactory.getLogger(DeviceMetrics.class);
    static final int INTERVAL_SECONDS=300,DEFAULT_KEEP_DAYS=90;
    static final String HEADER="X-VibeDock-Metrics";
    private static final Pattern IDENTIFIER=Pattern.compile("[A-Za-z0-9_.:-]{8,80}");
    private static final Pattern HEX=Pattern.compile("[0-9a-f]{1,6}"),SIGNED=Pattern.compile("-?[0-9a-f]{1,2}"),NUMBER=Pattern.compile("\\d{1,10}");

    /**
     * One sample with every value in its natural unit, null where the display did not measure it. at is the server
     * time of the pull (epoch milliseconds); boot the start of the display it came from (null if it sent none).
     */
    record Sample(long at,String boot,String raw,Long up,Integer freeBytes,Integer lowestBytes,Integer blockBytes,Integer afterTlsBytes,Integer tlsStackBytes,
                  Integer pictureMs,Integer rssi,Integer drops,Integer millivolts,Integer brightness,Long activeSeconds,Long dimmedSeconds) {
        Sample withLowest(Integer bytes){
            return new Sample(at,boot,raw,up,freeBytes,bytes,blockBytes,afterTlsBytes,tlsStackBytes,pictureMs,rssi,drops,millivolts,brightness,activeSeconds,dimmedSeconds);
        }
    }
    /** What a line of the file holds: the sample is rebuilt from raw; lowest is the lowest of the whole interval. */
    record Stored(long at,String boot,String raw,Integer lowest) {}

    /** The sample a header describes, or null when it is not version 1 or has no valid run time. Invalid fields are left out. */
    static Sample parse(String header,long at,String boot){
        if(header==null||header.length()>200)return null;
        String[] f=header.split(";",-1);
        if(f.length<2||!f[0].equals("1")||!NUMBER.matcher(f[1]).matches())return null;
        return new Sample(at,boot!=null&&DeviceLogs.BOOT_ID.matcher(boot).matches()?boot:null,header,Long.parseLong(f[1]),
                scaled(f,2,HEX,100,0,16_000_000),scaled(f,3,HEX,100,0,16_000_000),scaled(f,4,HEX,100,0,16_000_000),scaled(f,5,HEX,100,0,16_000_000),
                scaled(f,6,HEX,100,0,16_000_000),scaled(f,7,HEX,100,0,3_600_000),signal(f,8),plain(f,9,0,100_000),
                scaled(f,10,HEX,10,2000,5500),plain(f,11,0,100),number(f,12),number(f,13));
    }
    private static String field(String[] f,int index){return index<f.length?f[index]:"";}
    private static Integer scaled(String[] f,int index,Pattern pattern,int unit,int min,int max){
        String v=field(f,index);if(!pattern.matcher(v).matches())return null;
        long value=Long.parseLong(v,16)*unit;
        return value<min||value>max?null:(Integer)(int)value;
    }
    private static Integer signal(String[] f,int index){
        String v=field(f,index);if(!SIGNED.matcher(v).matches())return null;
        int value=Integer.parseInt(v,16);
        return value<-127||value>0?null:value;
    }
    private static Integer plain(String[] f,int index,int min,int max){
        String v=field(f,index);if(!NUMBER.matcher(v).matches())return null;
        long value=Long.parseLong(v);
        return value<min||value>max?null:(Integer)(int)value;
    }
    private static Long number(String[] f,int index){String v=field(f,index);return NUMBER.matcher(v).matches()?Long.valueOf(v):null;}

    private final Path root;private final ObjectMapper json;private final Clock clock;private final java.util.function.IntSupplier keepDays;
    /** Per display: the sample kept last (time, start) and the lowest free heap since then. */
    private record Window(long keptAt,String keptBoot,Integer lowest) {}
    private final Map<String,Window> windows=new ConcurrentHashMap<>();
    private final Map<String,Integer> appended=new ConcurrentHashMap<>();
    private final Set<String> migrated=ConcurrentHashMap.newKeySet();
    @org.springframework.beans.factory.annotation.Autowired
    DeviceMetrics(@Value("${vibedock.data-dir}") String dataDir,ObjectMapper json,Clock clock,SettingsStore settings){
        this(dataDir,json,clock,()->settings.retention().metricsDays());
    }
    DeviceMetrics(String dataDir,ObjectMapper json,Clock clock,java.util.function.IntSupplier keepDays){
        this.root=Path.of(dataDir).resolve("device-logs");this.json=json;this.clock=clock;this.keepDays=keepDays;
    }
    DeviceMetrics(String dataDir,ObjectMapper json,Clock clock){this(dataDir,json,clock,()->DEFAULT_KEEP_DAYS);}
    /** How many days of samples are kept. */
    int keepDays(){return keepDays.getAsInt();}

    /**
     * Takes the header of a pull. It is kept when the last kept sample is {@value #INTERVAL_SECONDS} s old, the display
     * started anew, or nothing is kept yet; otherwise only its lowest free heap counts for the next one.
     */
    void report(String identifier,String header,String boot){
        long now=clock.millis();var sample=parse(header,now,boot);
        if(sample==null||!IDENTIFIER.matcher(identifier).matches())return;
        synchronized(this){
            try{
                var window=windows.computeIfAbsent(identifier,this::lastKept);
                Integer lowest=min(window.lowest(),sample.lowestBytes());
                boolean due=window.keptAt()==0||now-window.keptAt()>=INTERVAL_SECONDS*1000L-5000||(boot!=null&&!boot.equals(window.keptBoot()));
                if(!due){windows.put(identifier,new Window(window.keptAt(),window.keptBoot(),lowest));return;}
                append(identifier,new Stored(now,sample.boot(),header,lowest));
                windows.put(identifier,new Window(now,sample.boot(),null));
            }catch(IOException|RuntimeException e){log.warn("Could not keep the metrics of {}: {}",identifier,e.toString());}
        }
    }
    private static Integer min(Integer a,Integer b){return a==null?b:b==null?a:Integer.valueOf(Math.min(a,b));}
    private Window lastKept(String identifier){
        try{
            var all=stored(identifier,clock.millis()-2*86_400_000L,Long.MAX_VALUE);
            if(all.isEmpty())return new Window(0,null,null);
            var last=all.get(all.size()-1);
            return new Window(last.at(),last.boot(),null);
        }catch(IOException|RuntimeException e){return new Window(0,null,null);}
    }
    private static final Pattern DAY_FILE=Pattern.compile("metrics-(\\d{4}-\\d{2}-\\d{2})\\.jsonl");
    private Path dayFile(String identifier,long at){return root.resolve(identifier).resolve("metrics-"+LocalDate.ofInstant(Instant.ofEpochMilli(at),ZoneOffset.UTC)+".jsonl");}
    private void append(String identifier,Stored line) throws IOException {
        migrate(identifier);
        Path file=dayFile(identifier,line.at());Files.createDirectories(file.getParent());
        boolean created=!Files.exists(file);
        Files.writeString(file,json.writeValueAsString(line)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        if(created)try{Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
        int count=appended.merge(identifier,1,Integer::sum);
        if(count==1||count%500==0)prune(identifier);
    }
    /** Deletes the day files older than the kept days: no rewriting. */
    private void prune(String identifier) throws IOException {
        LocalDate oldest=LocalDate.ofInstant(Instant.ofEpochMilli(clock.millis()),ZoneOffset.UTC).minusDays(keepDays());
        for(var day:dayFiles(identifier))if(day.date().isBefore(oldest))Files.deleteIfExists(day.path());
    }
    private record Day(LocalDate date,Path path) {}
    private List<Day> dayFiles(String identifier) throws IOException {
        Path dir=root.resolve(identifier);var out=new ArrayList<Day>();
        if(!Files.isDirectory(dir))return out;
        try(var names=Files.list(dir)){
            for(var path:names.toList()){
                var m=DAY_FILE.matcher(path.getFileName().toString());
                if(m.matches())try{out.add(new Day(LocalDate.parse(m.group(1)),path));}catch(DateTimeParseException ignored){}
            }
        }
        out.sort(Comparator.comparing(Day::date));
        return out;
    }
    /** Files of the earlier format, one metrics.jsonl with every sample: split into day files, then removed; repeatable if cut off in between. */
    private void migrate(String identifier) throws IOException {
        if(!migrated.add(identifier))return;
        Path legacy=root.resolve(identifier).resolve(DeviceLogs.METRICS);
        if(!Files.isRegularFile(legacy))return;
        var byDay=new TreeMap<LocalDate,Map<Long,String>>();
        for(String line:Files.readAllLines(legacy,StandardCharsets.UTF_8)){
            if(line.isBlank())continue;
            try{var s=json.readValue(line,Stored.class);byDay.computeIfAbsent(LocalDate.ofInstant(Instant.ofEpochMilli(s.at()),ZoneOffset.UTC),d->new LinkedHashMap<>()).putIfAbsent(s.at(),json.writeValueAsString(s));}
            catch(RuntimeException e){log.debug("Skipped an unreadable metrics line of {}",identifier);}
        }
        for(var e:byDay.entrySet()){
            Path file=root.resolve(identifier).resolve("metrics-"+e.getKey()+".jsonl");
            boolean created=!Files.exists(file);
            // Repeatable: a run that was cut off before the single file was removed left some samples in the day files; those are not written again.
            var present=new HashSet<Long>();
            if(!created)for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)){
                if(line.isBlank())continue;
                try{present.add(json.readValue(line,Stored.class).at());}catch(RuntimeException ignored){ /* unreadable lines are skipped when reading */ }
            }
            var text=new StringBuilder();
            e.getValue().forEach((at,line)->{if(!present.contains(at))text.append(line).append('\n');});
            if(text.length()==0)continue;
            Files.writeString(file,text.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            if(created)try{Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
        }
        Files.delete(legacy);
        log.info("Metrics of {} split into {} day file(s)",identifier,byDay.size());
    }
    /** The kept lines of the days that overlap from (inclusive) to to (exclusive), oldest first. */
    private synchronized List<Stored> stored(String identifier,long from,long to) throws IOException {
        if(!IDENTIFIER.matcher(identifier).matches())return List.of();
        migrate(identifier);
        var first=LocalDate.ofInstant(Instant.ofEpochMilli(Math.max(0,from)),ZoneOffset.UTC);
        var last=to==Long.MAX_VALUE?LocalDate.MAX:LocalDate.ofInstant(Instant.ofEpochMilli(Math.max(0,to-1)),ZoneOffset.UTC);
        var result=new ArrayList<Stored>();
        for(var day:dayFiles(identifier)){
            if(day.date().isBefore(first)||day.date().isAfter(last))continue;
            for(String line:Files.readAllLines(day.path(),StandardCharsets.UTF_8)){
                if(line.isBlank())continue;
                try{result.add(json.readValue(line,Stored.class));}catch(RuntimeException e){log.debug("Skipped an unreadable metrics line of {}",identifier);}
            }
        }
        result.sort(Comparator.comparingLong(Stored::at));
        return result;
    }
    /** The kept samples of a display from from (inclusive) to to (exclusive) in epoch milliseconds, oldest first. */
    List<Sample> samples(String identifier,long from,long to){
        try{
            var result=new ArrayList<Sample>();
            for(var s:stored(identifier,from,to)){
                if(s.at()<from||s.at()>=to)continue;
                var sample=parse(s.raw(),s.at(),s.boot());
                if(sample!=null)result.add(s.lowest()==null?sample:sample.withLowest(s.lowest()));
            }
            return result;
        }catch(IOException|RuntimeException e){log.warn("Could not read the metrics of {}: {}",identifier,e.toString());return List.of();}
    }
    /** The samples of the last hours, oldest first. */
    List<Sample> recent(String identifier,int hours){long now=clock.millis();return samples(identifier,now-hours*3_600_000L,now+1);}
    /** Forgets a removed display. The file goes with its log directory (DeviceLogs.deleteAll). */
    void forget(String identifier){windows.remove(identifier);appended.remove(identifier);}
}
