package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Log freshness, log requests and the starts displays report: old firmware without them keeps working. */
class DiagnosticsTest {
    private final ObjectMapper json=new ObjectMapper();
    private static final class TestClock extends Clock {
        long now=Instant.parse("2026-10-17T08:00:00Z").toEpochMilli();
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return Instant.ofEpochMilli(now);}
    }

    @Test void logsKnowTheStartTheyCameFromAndOldLogsStayReadable(@TempDir Path dir) throws Exception {
        var clock=new TestClock();var logs=new DeviceLogs(dir.toString(),json,clock);String id="vibedock-0a1b2c000010";
        // Over the network: the start from the header.
        var sent=logs.save(id,"net","0.2.0-22","waveshare-c6-touch-lcd-169",List.of("+3s Start"),"1a2b3c4d",754L);
        assertEquals("1a2b3c4d",sent.bootId());assertEquals(754L,sent.uptimeSeconds());
        // Over USB: the newest start line names it; the received time is the server's, not the events'.
        clock.now+=60_000;
        var usb=logs.save(id,"usb","0.2.0-22","waveshare-c6-touch-lcd-169",List.of(
                "10-17 07:00:01 Start v0.2.0-20 waveshare-c6-touch-lcd-169, Power on · boot=00000001 reset=1 start=power-on",
                "10-17 07:30:00 Start v0.2.0-22 waveshare-c6-touch-lcd-169, Software restart · boot=0000beef reset=3 start=update prev=00000001 phase=ota up=1799"));
        assertEquals("0000beef",usb.bootId());assertNull(usb.uptimeSeconds());assertEquals(clock.now,usb.savedAt());
        // Invalid values are not kept.
        assertNull(logs.save(id,"net",null,null,List.of("x"),"not-hex",-5L).bootId());
        // A log saved by 0.1.x (no boot id, no run time) is read as before.
        Path old=dir.resolve("device-logs").resolve(id).resolve("1760000000000-usb.json");
        Files.writeString(old,"{\"savedAt\":1760000000000,\"source\":\"usb\",\"firmware\":\"0.1.0\",\"board\":\"vibedock-m1\",\"lines\":[\"Start v0.1.0\"]}");
        var read=logs.read(id,old.getFileName().toString()).orElseThrow().entry();
        assertNull(read.bootId());assertEquals("0.1.0",read.firmware());assertEquals(1,read.lines());
    }
    @Test void partlyAnsweredRequestsShowPerDisplayAndKeepTheSavedLogs(@TempDir Path dir) throws Exception {
        var clock=new TestClock();var logs=new DeviceLogs(dir.toString(),json,clock);
        String answers="vibedock-0a1b2c000011",silent="vibedock-0a1b2c000012";
        logs.save(silent,"usb","0.2.0",null,List.of("old"));
        assertEquals(DeviceLogs.RequestState.NONE,logs.requestState(answers));
        String first=logs.request(answers);logs.request(silent);
        logs.reached(silent,clock.now,DevicePoke.Reach.UNREACHABLE);
        assertEquals(DevicePoke.Reach.UNREACHABLE,logs.lastRequest(silent).orElseThrow().reach());
        clock.now+=5_000;
        assertTrue(logs.takeRequest(answers,first));logs.save(answers,"net","0.2.0",null,List.of("new"));
        assertEquals(DeviceLogs.RequestState.RECEIVED,logs.requestState(answers));
        assertEquals(DeviceLogs.RequestState.PENDING,logs.requestState(silent),"a USB log from before is not the answer");
        clock.now+=DeviceLogs.REQUEST_VALID.toMillis()+1;
        assertEquals(DeviceLogs.RequestState.TIMED_OUT,logs.requestState(silent));
        assertEquals(1,logs.count(silent),"the saved log stays");
        // A reach reported for an older request does not change the newer one.
        logs.request(silent);logs.reached(silent,1,DevicePoke.Reach.ASKED);assertNull(logs.lastRequest(silent).orElseThrow().reach());
    }
    @Test void eachStartCountsOnceAndOldFirmwareReportsNothing(@TempDir Path dir) throws Exception {
        var clock=new TestClock();var diagnostics=new DeviceDiagnostics(dir.toString(),json,clock);String id="vibedock-0a1b2c000013";
        // 0.1.x: no header, nothing kept, nothing breaks.
        diagnostics.report(id,null,"0.1.0","vibedock-m1");
        assertTrue(diagnostics.live(id).isEmpty());assertTrue(diagnostics.starts(id).isEmpty());
        String crash="boot=0000abcd;up=12;heap=21000;min=18000;rssi=-61;drops=0;start=crash;reset=4;prev=0000aaaa;phase=image;prevup=5400;prevheap=9000;prevmin=4100;prevrssi=-70;prevdrops=2";
        for(int pull=0;pull<5;pull++){diagnostics.report(id,crash.replace("up=12","up="+(12+30*pull)),"0.2.0","waveshare-c6-touch-lcd-169");clock.now+=30_000;}
        var starts=diagnostics.starts(id);
        assertEquals(1,starts.size(),"five pulls of one start are one start");
        var s=starts.get(0);
        assertEquals("crash",s.kind());assertTrue(s.unexpected());assertEquals(4,s.resetCode());
        assertEquals("image",s.previousPhase());assertEquals(5400L,s.previousUptime());assertEquals(4100L,s.previousHeapMin());assertEquals(2,s.previousWifiDrops());
        assertEquals(132L,diagnostics.live(id).orElseThrow().uptimeSeconds());
        // An update is a start, but not an unexpected one; a power cut leaves the previous state unknown.
        diagnostics.report(id,"boot=0000abce;up=3;start=update;reset=3;prev=0000abcd;phase=ota","0.2.1","waveshare-c6-touch-lcd-169");
        diagnostics.report(id,"boot=0000abcf;up=2;start=power-on;reset=1","0.2.1","waveshare-c6-touch-lcd-169");
        starts=diagnostics.starts(id);
        assertEquals(List.of("power-on","update","crash"),starts.stream().map(DeviceDiagnostics.Start::kind).toList());
        assertEquals(1,starts.stream().filter(DeviceDiagnostics.Start::unexpected).count());
        assertNull(starts.get(0).previousPhase());
        // Bounded, newest kept.
        for(int i=0;i<30;i++)diagnostics.report(id,String.format("boot=%08x;start=software",0x1000+i),"0.2.1",null);
        assertEquals(DeviceDiagnostics.KEEP,diagnostics.starts(id).size());assertEquals("0000101d",diagnostics.starts(id).get(0).bootId());
    }
    @Test void invalidDiagnosisValuesAreDropped(){
        var v=DeviceDiagnostics.parse("boot=XYZ;up=-1;heap=12a;rssi=-61;start=exploded;phase=image;reset=99999999;evil=1;prev=0000abcd");
        assertEquals(Map.of("rssi","-61","phase","image","prev","0000abcd"),v);
        assertTrue(DeviceDiagnostics.parse("x".repeat(500)).isEmpty());assertTrue(DeviceDiagnostics.parse(null).isEmpty());
    }
}
