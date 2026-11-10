package org.frifra.vibedock.server;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The battery level of displays as the server learned it (docs/design/battery-learning.md). Off for every device until an
 * administrator switches it on; then shown only once at least one discharge is confirmed (DischargeDetector), from the
 * curve of that device alone (BatteryCurve), marked "learned". Everything is derived from the device's own stored
 * metrics; the only thing kept is which devices are switched on (battery-level.json).
 */
@Component
class BatteryLevels {
    private static final Logger log=LoggerFactory.getLogger(BatteryLevels.class);
    /** No sample for this long: the contact is lost. */
    static final long CONTACT_LOST_MILLIS=3L*DeviceMetrics.INTERVAL_SECONDS*1000L;
    private static final long CACHE_MILLIS=5*60_000L;

    /** state: off, learning, learned, external-power, offline (last value with its age) or contact-lost (0 %, the cell is empty). */
    record Level(String state,Integer percent,String origin,long ageSeconds,Integer millivolts) {
        static Level of(String state){return new Level(state,null,null,0,null);}
    }
    private record Learned(long at,List<DischargeDetector.Discharge> discharges) {}

    private final DeviceMetrics metrics;private final DeviceDiagnostics diagnostics;private final Clock clock;
    private final Path file;private final ObjectMapper json;
    private final Set<String> enabled=ConcurrentHashMap.newKeySet();
    private final Map<String,Learned> cache=new ConcurrentHashMap<>();
    BatteryLevels(@Value("${vibedock.data-dir}") String dataDir,ObjectMapper json,DeviceMetrics metrics,DeviceDiagnostics diagnostics,Clock clock){
        this.file=Path.of(dataDir).resolve("battery-level.json");this.json=json;this.metrics=metrics;this.diagnostics=diagnostics;this.clock=clock;
        try{if(Files.isRegularFile(file))enabled.addAll(json.readValue(file.toFile(),new TypeReference<List<String>>(){}));}
        catch(RuntimeException e){log.warn("Unreadable {}: {}",file,e.toString());}
    }
    /** The line under the switch on the device card; empty while the level is off. */
    static String describe(Level level){
        return switch(level.state()){
            case "learned"->level.percent()+" % (learned from this display, "+level.millivolts()+" mV)";
            case "learning"->"Learning: the level appears after the first confirmed discharge down to the limit range.";
            case "external-power"->"On external power: no voltage while charging or on USB is used.";
            case "offline"->level.percent()==null?"No measurement in the last 6 hours.":"Last level "+level.percent()+" %, no contact for "+minutes(level.ageSeconds())+".";
            case "contact-lost"->"0 %: no contact for "+minutes(level.ageSeconds())+" after the voltage fell into the limit range.";
            default->"";
        };
    }
    private static String minutes(long seconds){return seconds>=7200?seconds/3600+" h":seconds/60+" min";}
    boolean enabled(String identifier){return enabled.contains(identifier);}
    synchronized void enable(String identifier,boolean on) throws IOException {
        if(!(on?enabled.add(identifier):enabled.remove(identifier)))return;
        Files.createDirectories(file.getParent());
        json.writeValue(file.toFile(),new TreeSet<>(enabled));
    }
    /** The confirmed discharges of a device in its kept metrics, oldest first (kept for a few minutes). */
    List<DischargeDetector.Discharge> discharges(String identifier){
        long now=clock.millis();var known=cache.get(identifier);
        if(known!=null&&now-known.at()<CACHE_MILLIS)return known.discharges();
        var kinds=new HashMap<String,String>();
        try{for(var s:diagnostics.starts(identifier))kinds.put(s.bootId(),s.kind());}catch(IOException e){log.debug("No starts of {}: {}",identifier,e.toString());}
        var found=DischargeDetector.find(metrics.samples(identifier,now-metrics.keepDays()*86_400_000L,now+1),kinds::get);
        cache.put(identifier,new Learned(now,found));
        return found;
    }
    Level level(String identifier){
        if(!enabled(identifier))return Level.of("off");
        long now=clock.millis();
        var curve=BatteryCurve.of(discharges(identifier));
        if(curve==null)return Level.of("learning");
        var samples=metrics.samples(identifier,now-6*3_600_000L,now+1);
        if(samples.isEmpty())return Level.of("offline");
        var points=BatterySeries.of(samples);
        var newest=samples.get(samples.size()-1);
        long age=(now-newest.at())/1000;
        BatterySeries.Point last=null;
        for(int i=points.size()-1;i>=0;i--)if(points.get(i).smoothed()!=null){last=points.get(i);break;}
        if(last==null)return Level.of(newest.millivolts()==null?"external-power":"learning");
        if(now-newest.at()>CONTACT_LOST_MILLIS){
            if(last.raw()!=null&&last.raw()<DischargeDetector.LIMIT_MV)return new Level("contact-lost",0,BatteryCurve.LEARNED,age,last.raw());
            return new Level("offline",curve.percent(last.smoothed()),BatteryCurve.LEARNED,age,last.raw());
        }
        if(newest.millivolts()==null)return Level.of("external-power");
        return new Level("learned",curve.percent(last.smoothed()),BatteryCurve.LEARNED,age,newest.millivolts());
    }
}
