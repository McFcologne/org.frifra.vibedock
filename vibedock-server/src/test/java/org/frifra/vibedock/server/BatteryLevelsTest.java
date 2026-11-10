package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.frifra.vibedock.server.BatteryLevels.Level;
import static org.junit.jupiter.api.Assertions.*;

/** The learned battery level: off until switched on, only from the device's own confirmed discharge, 0 % after a confirmed contact loss. */
class BatteryLevelsTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    static final String ID="vibedock-aabbcc000001";
    /** A header with the given voltage in mV (the unit is 10 mV, hex): 1;up;;;;;;;;;mv. */
    static String header(int uptime,int mv){return "1;"+uptime+";;;;;;;;;"+Integer.toHexString(mv/10)+";;;";}
    private static final class MutableClock extends Clock {
        long now;MutableClock(long start){now=start;}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}
        public long millis(){return now;}
    }
    /** Reports one pull of the display every five minutes with the given voltages; returns the time after the last one. */
    private static void pulls(MutableClock clock,DeviceMetrics metrics,String boot,int... mv){
        int up=10;
        for(int v:mv){metrics.report(ID,header(up,v),boot);clock.now+=300_000L;up+=300;}
    }
    private static final int[] FALL={4170,4100,4060,3940,3870,3810,3750,3690,3630,3600,3570,3540,3520,3500,3460,3430,3380,3290};

    @Test void aDeviceIsOffUntilItIsSwitchedOnAndLearningUntilADischargeIsConfirmed(@TempDir Path dir) throws Exception {
        var clock=new MutableClock(Instant.parse("2026-11-10T16:00:00Z").toEpochMilli());
        var metrics=new DeviceMetrics(dir.toString(),JSON,clock);
        var levels=new BatteryLevels(dir.toString(),JSON,metrics,new DeviceDiagnostics(dir.toString(),JSON,clock),clock);
        pulls(clock,metrics,"aaaa1111",3970,4170,4100,4060);
        assertEquals("off",levels.level(ID).state());
        levels.enable(ID,true);
        assertEquals("learning",levels.level(ID).state(),"no confirmed discharge yet: nothing is shown");
        assertTrue(new BatteryLevels(dir.toString(),JSON,metrics,new DeviceDiagnostics(dir.toString(),JSON,clock),clock).enabled(ID),"the switch is kept");
    }
    @Test void aConfirmedDischargeGivesAPercentageAndAContactLossAtALowVoltageIsZero(@TempDir Path dir) throws Exception {
        var clock=new MutableClock(Instant.parse("2026-11-10T16:00:00Z").toEpochMilli());
        var metrics=new DeviceMetrics(dir.toString(),JSON,clock);
        var diagnostics=new DeviceDiagnostics(dir.toString(),JSON,clock);
        var levels=new BatteryLevels(dir.toString(),JSON,metrics,diagnostics,clock);levels.enable(ID,true);
        pulls(clock,metrics,"aaaa1111",3970);pulls(clock,metrics,"aaaa1111",FALL);
        // The display fails and starts again after charging: the decline is confirmed by the failure.
        clock.now+=40*60_000L;
        pulls(clock,metrics,"bbbb2222",3730,3830,3910,4040);
        // (the start kind comes from the display's start report; the unit test has no start file, so only the limit range confirms)
        var learned=levels.level(ID);
        assertEquals("learned",learned.state(),learned.toString());
        assertEquals(BatteryCurve.LEARNED,learned.origin());
        assertTrue(learned.percent()>=50&&learned.percent()<=100,"4040 mV on the curve of this device: "+learned);
        // The display is silent while its last value lies in the limit range: the cell is empty.
        var empty=new BatteryLevels(dir.toString(),JSON,metrics,diagnostics,clock);empty.enable(ID,true);
        pulls(clock,metrics,"cccc3333",4000,3900,3800,3700,3600,3500,3400,3380);
        clock.now+=20*60_000L;
        var lost=empty.level(ID);
        assertEquals("contact-lost",lost.state(),lost.toString());assertEquals(0,lost.percent());
    }
    @Test void silenceAtAHigherVoltageIsOnlyOfflineWithTheLastValue(@TempDir Path dir) throws Exception {
        var clock=new MutableClock(Instant.parse("2026-11-10T16:00:00Z").toEpochMilli());
        var metrics=new DeviceMetrics(dir.toString(),JSON,clock);
        var levels=new BatteryLevels(dir.toString(),JSON,metrics,new DeviceDiagnostics(dir.toString(),JSON,clock),clock);levels.enable(ID,true);
        pulls(clock,metrics,"aaaa1111",3970);pulls(clock,metrics,"aaaa1111",FALL);
        clock.now+=40*60_000L;
        pulls(clock,metrics,"bbbb2222",3730,3830,3910,4040,4040,4040,4040);
        clock.now+=30*60_000L;
        var level=levels.level(ID);
        assertEquals("offline",level.state(),level.toString());assertNotEquals(0,level.percent());assertTrue(level.ageSeconds()>=900);
    }
    @Test void theTextsNameWhatIsKnown() {
        assertEquals("",BatteryLevels.describe(Level.of("off")));
        assertTrue(BatteryLevels.describe(Level.of("offline")).contains("No measurement"),"no percentage without a recent value");
        assertTrue(BatteryLevels.describe(new Level("contact-lost",0,"learned",1200,3290)).startsWith("0 %"));
        assertTrue(BatteryLevels.describe(new Level("learned",63,"learned",30,3800)).contains("learned from this display"));
    }
    @Test void theCurveOfOneDeviceFollowsItsOwnDischarge() throws Exception {
        var data=BatterySeriesTest.Fixture.load("epaper-154");
        var curve=BatteryCurve.of(DischargeDetector.find(data.samples,data.starts::get));
        assertNotNull(curve);assertEquals(DischargeDetector.FAILURE,curve.basis());
        assertEquals(100,curve.percent(4170));assertEquals(0,curve.percent(3290));
        int middle=curve.percent(3700);
        assertTrue(middle>20&&middle<80,"the middle of the fall: "+middle);
        for(int mv=3300;mv<4170;mv+=10)assertTrue(curve.percent(mv+10)>=curve.percent(mv),"monotone at "+mv);
    }
}
