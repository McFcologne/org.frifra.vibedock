package org.frifra.vibedock.server;

import java.time.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A saved display log as one time line with what the server logged about the display in the same time (image
 * transfers, starts, saved logs, updates), and the key figures of a log for comparing it with the logs before.
 * Display lines carry the display's UTC time once its clock is set ("10-25 01:36:04 …"), before that the seconds
 * since its start ("+3s …"); server lines start with an ISO time with offset.
 */
final class DeviceLogTimeline {
    private DeviceLogTimeline(){}
    /** One line of the time line: time "MM-dd HH:mm:ss" (server time, like the rest of the administration; empty for display lines without a clock), source "display" or "server". */
    record Line(String time,String source,String text) {}
    /** Server lines this long before the first and after the last display line still belong to the log. */
    static final Duration MARGIN=Duration.ofSeconds(5);
    private static final Pattern DEVICE_TIME=Pattern.compile("^(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2}) ");
    private static final Pattern SERVER_TIME=Pattern.compile("^(\\S+)\\s");
    private static final java.time.format.DateTimeFormatter FORMAT=java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** The time of a display line, or null; the year is the one of savedAt (the year before for a date after it). */
    static Instant deviceTime(String line,long savedAt){
        Matcher m=DEVICE_TIME.matcher(line);
        if(!m.find())return null;
        int year=LocalDate.ofInstant(Instant.ofEpochMilli(savedAt),ZoneOffset.UTC).getYear();
        try{
            var at=LocalDateTime.of(year,Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),Integer.parseInt(m.group(4)),Integer.parseInt(m.group(5))).toInstant(ZoneOffset.UTC);
            return at.toEpochMilli()>savedAt+86_400_000L?at.atZone(ZoneOffset.UTC).minusYears(1).toInstant():at;
        }catch(DateTimeException e){return null;}
    }
    /** The time of a server log line, or null. */
    static Instant serverTime(String line){
        Matcher m=SERVER_TIME.matcher(line);
        if(!m.find())return null;
        try{return OffsetDateTime.parse(m.group(1)).toInstant();}catch(DateTimeException e){return null;}
    }
    /** The message of a server log line, without time, level, process, thread and logger. */
    static String message(String line){
        int at=line.indexOf(" : ");
        return (at<0?line:line.substring(at+3)).strip();
    }
    /** The time span of a log: from its first line with a time (or savedAt) to savedAt, widened by {@link #MARGIN}. */
    static Instant[] span(List<String> lines,long savedAt){
        Instant first=null;
        for(String line:lines){first=deviceTime(line,savedAt);if(first!=null)break;}
        Instant end=Instant.ofEpochMilli(savedAt);
        return new Instant[]{(first==null||first.isAfter(end)?end:first).minus(MARGIN),end.plus(MARGIN)};
    }
    /**
     * The display's lines with the server's lines of its span merged in by time. A server line goes before the first
     * display line of a later second; display lines without a time keep their place.
     */
    static List<Line> merge(List<String> deviceLines,long savedAt,List<String> serverLines){
        var span=span(deviceLines,savedAt);
        var server=new ArrayList<Map.Entry<Instant,String>>();
        for(String line:serverLines){
            Instant at=serverTime(line);
            if(at!=null&&!at.isBefore(span[0])&&!at.isAfter(span[1]))server.add(Map.entry(at,message(line)));
        }
        server.sort(Map.Entry.comparingByKey());
        var result=new ArrayList<Line>();int next=0;
        for(String line:deviceLines){
            Instant at=deviceTime(line,savedAt);
            if(at!=null){
                long second=at.getEpochSecond();
                while(next<server.size()&&server.get(next).getKey().getEpochSecond()<second){
                    var s=server.get(next++);result.add(new Line(FORMAT.format(s.getKey()),"server",s.getValue()));
                }
                result.add(new Line(FORMAT.format(at),"display",line.substring(15)));
            }else result.add(new Line("","display",line));
        }
        while(next<server.size()){var s=server.get(next++);result.add(new Line(FORMAT.format(s.getKey()),"server",s.getValue()));}
        return result;
    }

    /**
     * What a log says about memory, Wi-Fi and pictures, to compare it with the logs before: the lowest free heap the
     * display reported, the lowest free heap after a TLS handshake, the highest stack use of the TLS handshake, the
     * slowest and the number of complete pictures, the aborted pictures, and the weakest and strongest Wi-Fi signal.
     * Every value is null when the log names none.
     */
    record Figures(Integer lowestHeap,Integer lowestAfterTls,Integer highestStack,Integer stackSize,Integer slowestPicture,int pictures,
                   int aborted,Integer weakestSignal,Integer strongestSignal) {}
    private static final Pattern HEAP=Pattern.compile("\\bheap[ =](\\d+)");
    private static final Pattern AFTER_TLS=Pattern.compile("(\\d+)\\+\\d+ IRAM free after");
    private static final Pattern STACK=Pattern.compile("\\bstack (\\d+)/(\\d+)");
    private static final Pattern PICTURE=Pattern.compile("Image shown, read in (\\d+) ms");
    private static final Pattern SIGNAL=Pattern.compile("(?:rssi=|, )(-\\d{2,3})(?: dBm|\\b)");
    static Figures figures(List<String> lines){
        Integer heap=null,afterTls=null,stack=null,stackSize=null,slowest=null,weakest=null,strongest=null;int pictures=0,aborted=0;
        for(String line:lines){
            for(Matcher m=HEAP.matcher(line);m.find();)heap=min(heap,Integer.parseInt(m.group(1)));
            for(Matcher m=AFTER_TLS.matcher(line);m.find();)afterTls=min(afterTls,Integer.parseInt(m.group(1)));
            for(Matcher m=STACK.matcher(line);m.find();){stack=max(stack,Integer.parseInt(m.group(1)));stackSize=Integer.parseInt(m.group(2));}
            Matcher p=PICTURE.matcher(line);if(p.find()){pictures++;slowest=max(slowest,Integer.parseInt(p.group(1)));}
            if(line.contains("Image aborted"))aborted++;
            for(Matcher m=SIGNAL.matcher(line);m.find();){
                int dbm=Integer.parseInt(m.group(1));
                if(dbm<0&&dbm>-120){weakest=min(weakest,dbm);strongest=max(strongest,dbm);}
            }
        }
        return new Figures(heap,afterTls,stack,stackSize,slowest,pictures,aborted,weakest,strongest);
    }
    /**
     * The figures of a log as table cells, each with the change against the log before it in brackets when both name
     * it and it differs: lowest free heap, lowest free heap after TLS, TLS stack, slowest complete picture, pictures
     * shown and aborted, Wi-Fi signal.
     */
    static List<String> cells(Figures now,Figures before){
        return List.of(
                bytes(now.lowestHeap(),before==null?null:before.lowestHeap()),
                bytes(now.lowestAfterTls(),before==null?null:before.lowestAfterTls()),
                now.highestStack()==null?"–":bytes(now.highestStack(),before==null?null:before.highestStack())+" of "+String.format(Locale.ROOT,"%,d",now.stackSize()),
                now.slowestPicture()==null?"–":String.format(Locale.ROOT,"%.1f s",now.slowestPicture()/1000.0)
                        +(before==null||before.slowestPicture()==null||before.slowestPicture().equals(now.slowestPicture())?""
                        :String.format(Locale.ROOT," (%+.1f s)",(now.slowestPicture()-before.slowestPicture())/1000.0)),
                now.pictures()+" shown"+(now.aborted()>0?", "+now.aborted()+" aborted":""),
                now.weakestSignal()==null?"–":now.weakestSignal().equals(now.strongestSignal())?now.weakestSignal()+" dBm":now.weakestSignal()+" to "+now.strongestSignal()+" dBm");
    }
    private static String bytes(Integer now,Integer before){
        if(now==null)return "–";
        String text=String.format(Locale.ROOT,"%,d",now);
        return before==null||before.equals(now)?text:text+String.format(Locale.ROOT," (%+,d)",now-before);
    }
    private static Integer min(Integer a,int b){return a==null?b:Math.min(a,b);}
    private static Integer max(Integer a,int b){return a==null?b:Math.max(a,b);}
}
