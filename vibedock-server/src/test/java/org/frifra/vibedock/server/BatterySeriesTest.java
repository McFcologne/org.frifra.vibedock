package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The smoothed battery voltage: derived when reading, per run, never "charging". */
class BatterySeriesTest {
    static final long MIN=60_000L;
    static DeviceMetrics.Sample sample(long at,String boot,Integer mv){
        return new DeviceMetrics.Sample(at,boot,"",null,null,null,null,null,null,null,null,null,mv,null,null,null);
    }
    /** Samples every five minutes from minute 0 with the given voltages. */
    static List<DeviceMetrics.Sample> run(String boot,long startMinute,Integer... mv){
        var list=new ArrayList<DeviceMetrics.Sample>();
        for(int i=0;i<mv.length;i++)list.add(sample((startMinute+5L*i)*MIN,boot,mv[i]));
        return list;
    }
    @Test void theFirstReadingOfARunDoesNotCountAndAnOutlierIsRemoved() {
        var points=BatterySeries.of(run("aaaa1111",0,3970,4100,4100,3800,4100,4100));
        assertNull(points.get(0).smoothed(),"the first reading only starts the run");
        assertEquals(4100,points.get(1).smoothed(),.01);
        assertEquals(4100,points.get(4).smoothed(),.01,"a dip of one reading (radio load) is cut by the median: "+points.get(4));
        assertTrue(points.get(3).smoothed()>4000,"and barely moves the mean: "+points.get(3).smoothed());
    }
    @Test void aFallBeyondTheNoiseIsADeclineCandidateAndARiseIsOnlyFlat() {
        var falling=BatterySeries.of(run("aaaa1111",0,4170,4100,4060,3940,3870,3810,3750));
        assertEquals(BatterySeries.CANDIDATE,falling.get(falling.size()-1).phase());
        assertTrue(falling.get(falling.size()-1).slope()<-100);
        var rising=BatterySeries.of(run("bbbb2222",0,3730,3830,3910,4040,4190,4100));
        for(var p:rising)assertNotEquals(BatterySeries.CANDIDATE,p.phase(),"a rise is never called anything but flat");
        var steady=BatterySeries.of(run("cccc3333",0,4050,4050,4040,4040,4040,4040,4040));
        for(var p:steady)assertEquals(BatterySeries.FLAT,p.phase());
    }
    @Test void aNewRunAndAGapStartTheSmoothingAnew() {
        var samples=new ArrayList<>(run("aaaa1111",0,4000,4000,4000,4000));
        samples.addAll(run("bbbb2222",20,3500,3500,3500));
        var points=BatterySeries.of(samples);
        assertNull(points.get(4).smoothed(),"first reading of the next run");
        assertEquals(3500,points.get(5).smoothed(),.01,"not mixed with the run before");
        var gap=new ArrayList<>(run("aaaa1111",0,4000,4000,4000));
        gap.addAll(run("aaaa1111",60,3000,3000));
        var gapped=BatterySeries.of(gap);
        assertNull(gapped.get(3).smoothed(),"after a gap of an hour the run starts anew");
        assertEquals(3000,gapped.get(4).smoothed(),.01);
    }
    @Test void withoutAVoltageThereIsNothingToSmooth() {
        var points=BatterySeries.of(run("aaaa1111",0,null,null));
        for(var p:points){assertNull(p.smoothed());assertNull(p.slope());}
    }
    @Test void theRealDischargeOfAnEPaperBoardShowsAsOneDecline() throws Exception {
        var data=Fixture.load("epaper-154");
        var points=BatterySeries.of(data.samples);
        var candidates=points.stream().filter(p->BatterySeries.CANDIDATE.equals(p.phase())&&"9d17a290".equals(p.boot())).count();
        assertTrue(candidates>=10,"the 90 minute fall is a decline: "+candidates);
        var steady=points.stream().filter(p->"37113bb9".equals(p.boot())&&p.at()>Fixture.at("2026-11-10T19:45:00Z")&&p.at()<Fixture.at("2026-11-10T20:26:00Z")).toList();
        assertFalse(steady.isEmpty());
        for(var p:steady)assertEquals(BatterySeries.FLAT,p.phase(),"the stable voltage on the charger stays flat");
    }

    /** The recorded samples of a real board (src/test/resources/battery), without a device identifier. */
    static final class Fixture {
        final List<DeviceMetrics.Sample> samples=new ArrayList<>();final Map<String,String> starts=new HashMap<>();
        static long at(String instant){return java.time.Instant.parse(instant).toEpochMilli();}
        @SuppressWarnings("unchecked")
        static Fixture load(String name) throws Exception {
            try(InputStream in=BatterySeriesTest.class.getResourceAsStream("/battery/"+name+".json")){
                var root=new ObjectMapper().readValue(in,Map.class);
                var fixture=new Fixture();
                fixture.starts.putAll((Map<String,String>)root.get("starts"));
                for(var row:(List<List<Object>>)root.get("samples"))
                    fixture.samples.add(sample(((Number)row.get(0)).longValue(),(String)row.get(1),row.get(2)==null?null:((Number)row.get(2)).intValue()));
                return fixture;
            }
        }
    }
}
