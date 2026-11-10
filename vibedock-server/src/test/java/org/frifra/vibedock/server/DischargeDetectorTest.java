package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only a confirmed decline is a discharge: a rise proves nothing, and a failure at a high voltage is no discharge. */
class DischargeDetectorTest {
    private static final Map<String,String> POWER_ON=Map.of("bbbb2222","power-on");
    private static List<DeviceMetrics.Sample> followedByStart(List<DeviceMetrics.Sample> run,int minuteAfter,int mv){
        var all=new ArrayList<>(run);
        all.addAll(BatterySeriesTest.run("bbbb2222",minuteAfter,mv,mv,mv));
        return all;
    }
    @Test void aDeclineThatEndsInAFailureAtALowVoltageIsConfirmed() {
        var run=BatterySeriesTest.run("aaaa1111",0,3970,4170,4100,4060,3940,3870,3810,3750,3690,3630,3600,3570,3540,3520,3500,3460,3430,3380,3290);
        var found=DischargeDetector.find(followedByStart(run,120,3730),POWER_ON::get);
        assertEquals(1,found.size());
        var d=found.get(0);
        assertEquals(DischargeDetector.FAILURE,d.endedBy());
        assertEquals("aaaa1111",d.boot());
        assertEquals(4170,d.startMv(),.01,"the start is the highest point before the fall");
        assertTrue(d.endMv()<3450,d.toString());
    }
    @Test void aFailureAtAHighVoltageIsAPowerCutNotADischarge() {
        var run=BatterySeriesTest.run("aaaa1111",0,4170,4100,4060,3990,3940,3900,3880);
        assertTrue(DischargeDetector.find(followedByStart(run,60,3900),POWER_ON::get).isEmpty());
    }
    @Test void aDeclineThatReachesTheLimitRangeCountsEvenWithoutAFailure() {
        var run=new ArrayList<>(BatterySeriesTest.run("aaaa1111",0,4100,4000,3900,3800,3700,3600,3500,3450,3440));
        run.addAll(BatterySeriesTest.run("aaaa1111",45,3560,3640,3720,3800)); // the voltage rises: a charger, not called so
        var found=DischargeDetector.find(run,k->null);
        assertEquals(1,found.size());
        assertEquals(DischargeDetector.LIMIT,found.get(0).endedBy());
        assertEquals(3440,found.get(0).endMv(),60);
    }
    @Test void aDeclineThatEndsBeforeTheLimitRangeIsDropped() {
        var run=BatterySeriesTest.run("aaaa1111",0,4170,4100,4040,3980,3920,3900,3960,4040,4120,4170);
        assertTrue(DischargeDetector.find(run,k->null).isEmpty());
    }
    @Test void aFailureNeedsTheNextSampleToBeAStartAfterLostPower() {
        var run=BatterySeriesTest.run("aaaa1111",0,3970,4170,4100,4060,3940,3870,3810,3750,3690,3630,3600,3570,3540,3500,3460,3430,3380);
        assertTrue(DischargeDetector.find(followedByStart(run,120,3730),k->"external".equals(k)?k:"update").size()==1,"a decline into the limit range counts anyway");
        var found=DischargeDetector.find(followedByStart(run,120,3730),k->"update");
        assertEquals(DischargeDetector.LIMIT,found.get(0).endedBy(),"but is no failure after an update");
    }
    @Test void aRiseAloneTeachesNothing() {
        assertTrue(DischargeDetector.find(BatterySeriesTest.run("aaaa1111",0,3730,3830,3910,4040,4190,4100,4070),k->null).isEmpty());
    }
    @Test void theRealBoardHasOneConfirmedDischargeWithAFailure() throws Exception {
        var data=BatterySeriesTest.Fixture.load("epaper-154");
        var found=DischargeDetector.find(data.samples,data.starts::get);
        assertEquals(1,found.size(),found.toString());
        var d=found.get(0);
        assertEquals("9d17a290",d.boot());assertEquals(DischargeDetector.FAILURE,d.endedBy());
        assertEquals(BatterySeriesTest.Fixture.at("2026-11-10T17:04:10Z"),d.startAt(),"the decline starts with the first counted reading, 4170 mV");
        assertEquals(BatterySeriesTest.Fixture.at("2026-11-10T18:34:07Z"),d.endAt());
        assertTrue(d.startMv()>4100&&d.endMv()<3450,d.toString());
    }
}
