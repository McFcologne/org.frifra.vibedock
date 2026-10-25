package org.frifra.vibedock.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** The server's own log files in the log directory: vibedock.log with its rotated .gz files, and the access logs. */
final class ServerLogFiles {
    private ServerLogFiles(){}
    private static final Pattern STAMP=Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)(Z|[+-]\\d{2}:?\\d{2})?");
    /** The time of a log line: with its offset (Spring Boot writes one), otherwise in the server's time zone. */
    static Instant time(java.util.regex.Matcher m){
        var local=LocalDateTime.parse(m.group(1)+"T"+m.group(2));
        if(m.group(3)==null)return local.atZone(ZoneId.systemDefault()).toInstant();
        return local.atOffset(ZoneOffset.of(m.group(3).equals("Z")?"Z":m.group(3).length()==5?m.group(3).substring(0,3)+":"+m.group(3).substring(3):m.group(3))).toInstant();
    }
    /**
     * The lines of the files whose name starts with prefix, since the given time, oldest first, the newest maxChars
     * characters at most. Lines without a time (stack traces) go with the line before them; of the lines with a time
     * only those that pass filter are taken. Each line taken passes map.
     */
    static List<String> read(Path dir,String prefix,Instant since,int maxChars,Predicate<String> filter,UnaryOperator<String> map) throws IOException {
        if(!Files.isDirectory(dir))return List.of();
        List<Path> files;
        try(var list=Files.list(dir)){
            files=list.filter(p->p.getFileName().toString().startsWith(prefix)&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
                    .filter(p->{try{return Files.getLastModifiedTime(p).toInstant().isAfter(since);}catch(IOException e){return false;}})
                    .sorted(Comparator.comparing(p->{try{return Files.getLastModifiedTime(p);}catch(IOException e){return java.nio.file.attribute.FileTime.fromMillis(0);}}))
                    .toList();
        }
        var lines=new ArrayDeque<String>();long chars=0;boolean keep=false;
        for(Path file:files){
            InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS);
            if(file.getFileName().toString().endsWith(".gz"))in=new GZIPInputStream(in);
            try(var reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
                for(String line;(line=reader.readLine())!=null;){
                    var m=STAMP.matcher(line);
                    if(m.find()){
                        Instant at;
                        try{at=time(m);}catch(java.time.DateTimeException e){at=null;}
                        keep=at!=null&&!at.isBefore(since)&&filter.test(line);
                    }
                    if(!keep)continue;
                    String text=map.apply(line.length()>2000?line.substring(0,2000)+" …":line);
                    lines.addLast(text);chars+=text.length()+1;
                    while(chars>maxChars&&!lines.isEmpty())chars-=lines.removeFirst().length()+1;
                }
            }
        }
        return List.copyOf(lines);
    }
}
