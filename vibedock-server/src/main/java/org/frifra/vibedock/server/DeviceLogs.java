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
    static final List<String> SOURCES=List.of("usb","net");
    private static final Pattern IDENTIFIER=Pattern.compile("[A-Za-z0-9_.:-]{8,80}");
    private static final Pattern NAME=Pattern.compile("\\d{13}-(usb|net)(-\\d{1,2})?\\.json");
    private static final Pattern BEARER=Pattern.compile("(?i)(bearer\\s+)\\S+");
    private static final Pattern HEX64=Pattern.compile("(?i)\\b[0-9a-f]{64}\\b");
    private static final Pattern WEB=Pattern.compile("/web/[^\\s\"'/?#]+");

    /** A saved log without its lines; name identifies it within the device. */
    record Entry(String name,long savedAt,String source,String firmware,String board,int lines) {}
    record Log(Entry entry,List<String> lines) {}
    private record Stored(long savedAt,String source,String firmware,String board,List<String> lines) {}

    private final Path root;private final ObjectMapper json;private final Clock clock;
    DeviceLogs(@Value("${vibedock.data-dir}") String dataDir,ObjectMapper json,Clock clock){
        this.root=Path.of(dataDir).resolve("device-logs");this.json=json;this.clock=clock;
    }

    /** Saves a log read from a display and drops its oldest logs beyond {@value #KEEP}. */
    synchronized Entry save(String identifier,String source,String firmware,String board,List<String> lines) throws IOException {
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
        var stored=new Stored(now,source,short40(firmware),short40(board),clean);
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
        Path dir=directory(identifier);
        for(String name:names(dir))Files.deleteIfExists(dir.resolve(name));
        try{Files.deleteIfExists(dir);}catch(DirectoryNotEmptyException ignored){}
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
    private static Entry entry(String name,Stored s){return new Entry(name,s.savedAt(),s.source(),s.firmware(),s.board(),s.lines()==null?0:s.lines().size());}
    private static String short40(String value){
        if(value==null)return null;String text=value.replaceAll("[^0-9A-Za-z.+_-]","");
        return text.isEmpty()?null:text.substring(0,Math.min(40,text.length()));
    }
}
