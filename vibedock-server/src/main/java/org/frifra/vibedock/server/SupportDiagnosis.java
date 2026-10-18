package org.frifra.vibedock.server;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The diagnosis overview at the top of a support file: per display what is known for certain (observed), what that
 * suggests (suspected, an assumption, never a finding) and which data is missing for a firm answer; for the server
 * its warnings and errors. Starts count once per boot id; start lines in logs without one (older firmware) are only
 * counted, never taken as separate restarts. Server times carry their offset, device run times are seconds.
 */
final class SupportDiagnosis {
    static final String LEGEND="Server times: ISO 8601 with the server's offset. Device run times (up, prevup): seconds since the display's start. "
            +"Device log lines: the display's UTC time once its clock is set (NTP), before that +<seconds> since its start. "
            +"Observed: what the server or the display reported. Suspected: what that suggests, not proven.";
    record Server(int warnings,int errors,int savingFailed,boolean storageWritable) {}
    record Display(String identifier,String name,String board,String status,String lastPullAt,
                   String firmwareNow,String logFirmware,String logReceivedAt,Long logAgeMinutes,String logSource,boolean firmwareDiffers,
                   String bootNow,Long uptimeSeconds,Long heapFree,Long heapMin,Integer rssi,Integer wifiDrops,
                   DeviceDiagnostics.Start lastStart,int startsKnown,int unexpectedStarts,int startLinesWithoutBoot,String crashRecord,
                   List<String> observed,List<String> suspected,List<String> missing) {}
    record Overview(String legend,Server server,List<Display> displays) {}
    /** The ESP8266 crash record in a start line (crash.cpp). */
    static final Pattern CRASH=Pattern.compile("(exception|stack overflow|abort \\(e\\.g\\. out of memory\\)|software watchdog|hardware watchdog|crash) \\(reason (\\d+), cause (\\d+) at (0x[0-9a-f]{8}), address (0x[0-9a-f]{8})\\)(, stack \\d+ bytes free)?");
    private static final DateTimeFormatter ISO=DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private SupportDiagnosis(){}

    static Server server(List<String> serverLog,boolean storageWritable){
        int warnings=0,errors=0,saving=0;
        for(String line:serverLog){
            if(line.contains(" WARN "))warnings++;
            if(line.contains(" ERROR "))errors++;
            if(line.contains("Saving to ")&&line.contains(" failed"))saving++;
        }
        return new Server(warnings,errors,saving,storageWritable);
    }
    /** One display; latest and lines: its newest saved log (null without one). */
    static Display display(ClientRegistry.View v,DeviceLogs.Entry latest,List<String> lines,List<DeviceDiagnostics.Start> starts,
                           DeviceDiagnostics.Live live,long now,ZoneId zone,SupportRedactor redactor){
        var observed=new ArrayList<String>();var suspected=new ArrayList<String>();var missing=new ArrayList<String>();
        String pull=v.lastPullAt()==null?null:time(v.lastPullAt()*1000,zone);
        observed.add("Status "+v.status()+(pull==null?", no pull yet":", last pull "+pull));
        boolean differs=latest!=null&&latest.firmware()!=null&&v.firmware()!=null&&!latest.firmware().equals(v.firmware());
        Long age=latest==null?null:Math.max(0,(now-latest.savedAt())/60000);
        if(latest!=null)observed.add("Newest log received "+time(latest.savedAt(),zone)+" ("+age+" min before this file) over "+("usb".equals(latest.source())?"USB":"the network")
                +", firmware "+(latest.firmware()==null?"unknown":latest.firmware()));
        if(differs)observed.add("The display reports firmware "+v.firmware()+" now; the newest log is from "+latest.firmware());
        var start=starts.isEmpty()?null:starts.get(0);
        int unexpected=(int)starts.stream().filter(DeviceDiagnostics.Start::unexpected).count();
        if(start!=null){
            observed.add("Last start "+time(start.seenAt(),zone)+": "+start.kind()+(start.resetCode()==null?"":" (reset code "+start.resetCode()+")")
                    +(start.previousPhase()!=null?"; the run before ended in phase "+start.previousPhase()+" after "+start.previousUptime()+" s"
                    +(start.previousHeapMin()!=null?", lowest free memory "+start.previousHeapMin()+" B":""):"; the run before is unknown"));
            observed.add(unexpected+" unexpected of the last "+starts.size()+" start(s) the display reported");
        }
        if(live!=null&&live.wifiDrops()!=null&&live.wifiDrops()>0)observed.add("Wi-Fi lost "+live.wifiDrops()+"× since the current start");
        String crash=null;int withoutBoot=0;
        if(lines!=null)for(int i=lines.size()-1;i>=0;i--){
            String line=lines.get(i);if(!line.contains("Start v"))continue;
            if(!line.contains("boot="))withoutBoot++;
            var m=CRASH.matcher(line);if(crash==null&&m.find())crash=m.group();
        }
        if(crash!=null)observed.add("Crash record in the log: "+crash);
        if(withoutBoot>0)observed.add(withoutBoot+" start line(s) in the newest log without a boot id (older firmware): not counted as restarts");
        // What that suggests.
        boolean esp8266=v.board().esp8266();
        if(start!=null){
            switch(start.kind()){
                case "crash","watchdog","software","brownout" -> {
                    suspected.add("Firmware "+("software".equals(start.kind())?"restart it did not plan":start.kind())+(start.previousPhase()==null?"; phase unknown":" while "+phase(start.previousPhase())));
                    if(start.previousHeapMin()!=null&&start.previousHeapMin()<(esp8266?6000:20000))suspected.add("Free memory was low before ("+start.previousHeapMin()+" B): out of memory is likely");
                    if("brownout".equals(start.kind()))suspected.add("Power supply too weak (brownout)");
                }
                case "update","setup","factory-reset" -> suspected.add("The last restart was planned ("+start.kind()+")");
                case "external" -> suspected.add("Reset by the button or over USB (also esptool or a serial monitor)");
                case "power-on" -> suspected.add("Power on: plugged in or a power cut; the run before is unknown");
                default -> {}
            }
        }
        boolean connected="Connected".equals(v.status());
        if(!connected&&(start==null||!start.unexpected()))suspected.add("No crash reported: Wi-Fi or server not reachable is more likely");
        if(live!=null&&live.wifiDrops()!=null&&live.wifiDrops()>0)suspected.add("Wi-Fi interruptions");
        // What is missing for a firm answer.
        if(live==null)missing.add("No diagnosis from the display (firmware before 0.2.0, or no pull since the server started)");
        if(latest==null)missing.add("No saved log: read one (Troubleshooting)");
        else if(differs)missing.add("No log of the firmware it runs now: Request current logs");
        if(start!=null&&start.unexpected()){
            if(start.previousPhase()==null)missing.add("The state before the restart was not kept");
            if(!esp8266)missing.add("The backtrace of a panic: printed on the serial port only (FLASHING.md, Decoding a crash)");
            if(esp8266&&crash==null)missing.add("The crash record: in the log of that start (read the log)");
            missing.add("To decode addresses: the ELF of "+v.board().id()+" "+(start.firmware()==null?"of that firmware":start.firmware()));
        }
        if(suspected.isEmpty())suspected.add("Nothing points to a problem");
        return new Display(v.identifier(),redactor.text(v.name()),v.board().id(),v.status(),pull,v.firmware(),latest==null?null:latest.firmware(),
                latest==null?null:time(latest.savedAt(),zone),age,latest==null?null:latest.source(),differs,
                live==null?null:live.bootId(),live==null?null:live.uptimeSeconds(),live==null?null:live.heapFree(),live==null?null:live.heapMin(),
                live==null?null:live.rssi(),live==null?null:live.wifiDrops(),start,starts.size(),unexpected,withoutBoot,crash,observed,suspected,missing);
    }
    private static String phase(String phase){
        return switch(phase){
            case "wifi" -> "connecting to Wi-Fi"; case "connect" -> "connecting to the server"; case "tls" -> "the TLS connection to the server";
            case "image" -> "receiving and drawing the image"; case "draw" -> "drawing its own screen"; case "config" -> "saving its settings";
            case "log" -> "sending its log"; case "ota" -> "an update"; case "idle" -> "waiting between pulls"; case "portal" -> "the Wi-Fi setup";
            default -> "starting";
        };
    }
    static String time(long millis,ZoneId zone){return ISO.format(Instant.ofEpochMilli(millis).atZone(zone).withNano(0));}
}
