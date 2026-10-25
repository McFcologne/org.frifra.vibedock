package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Log freshness, log requests and the starts displays report: old firmware without them keeps working. */
class DiagnosticsTest {
    private final ObjectMapper json=JsonCompatibility.mapper();
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
    @Test void theCrashOfTheRunBeforeIsKeptAndNamedForDecoding(@TempDir Path dir) throws Exception {
        var diagnostics=new DeviceDiagnostics(dir.toString(),json,new TestClock());String id="vibedock-0a1b2c000014";
        diagnostics.report(id,"boot=0000c0de;up=4;start=crash;reset=4;prev=0000c0dd;phase=idle;prevup=43;panic=4200a1b2,4200c3d4,40800100;cause=5;fault=0;abort=assert failed: x.c 12 (ok)",
                "0.2.0","waveshare-c6-touch-lcd-169");
        var s=diagnostics.starts(id).get(0);
        assertEquals("4200a1b2,4200c3d4,40800100",s.crashCode());assertEquals(5L,s.crashCause());assertEquals("0",s.crashAddress());
        assertEquals("assert failed: x.c 12 (ok)",s.crashText());assertEquals("0x4200a1b2 0x4200c3d4 0x40800100",s.crashAddresses());
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        registry.paired(id,"Shelf","ab".repeat(32),"192.168.1.22",80,"waveshare-c6-touch-lcd-169");
        var d=SupportDiagnosis.display(registry.get(id),null,null,List.of(s),null,System.currentTimeMillis(),ZoneId.of("UTC"),NONE);
        assertTrue(d.observed().contains("Crash of the run before at 0x4200a1b2 0x4200c3d4 0x40800100, cause 5, address 0x0: assert failed: x.c 12 (ok)"));
        assertTrue(d.missing().stream().anyMatch(t->t.startsWith("To decode the crash: addr2line -pfiaC -e <the ELF of waveshare-c6-touch-lcd-169 0.2.0> 0x4200a1b2")));
        assertFalse(d.missing().stream().anyMatch(t->t.startsWith("The backtrace")),"the addresses came over the network");
        // Invalid crash values are dropped, a start from older firmware has none.
        var v=DeviceDiagnostics.parse("boot=0000c0df;panic=4200a1b2,zz;cause=-1;fault=123456789;abort=a;b<script>");
        assertFalse(v.containsKey("panic"));assertFalse(v.containsKey("cause"));assertFalse(v.containsKey("fault"));
    }
    private static final SupportRedactor NONE=new SupportRedactor(List.of(),false);
    private static DeviceDiagnostics.Start start(String boot,String kind,String phase,Long up,Long min){
        return new DeviceDiagnostics.Start(boot,1_760_000_000_000L,"0.2.0","x",kind,4,phase==null?null:"00000001",phase,up,null,min,null,null,null,null,null,null);
    }
    @Test void theOverviewTellsObservedFromSuspectedAndNamesWhatIsMissing(@TempDir Path dir) throws Exception {
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        registry.paired("vibedock-0a1b2c000020","Shelf","ab".repeat(32),"192.168.1.20",80,"waveshare-c6-touch-lcd-169");
        registry.pulled("vibedock-0a1b2c000020","192.168.1.20",true,"0.1.1-22-g13998d9",null);
        registry.paired("vibedock-0a1b2c000021","Desk","ab".repeat(32),"192.168.1.21",80);
        var c6=registry.get("vibedock-0a1b2c000020");var m1=registry.get("vibedock-0a1b2c000021");
        long now=System.currentTimeMillis();ZoneId zone=ZoneId.of("Europe/Berlin");
        // The report of the issue: the display runs -22, its newest log is from -20 and 33 minutes old; a crash while drawing.
        var latest=new DeviceLogs.Entry("x.json",now-33*60_000,"net","0.1.1-20-g1111111","waveshare-c6-touch-lcd-169",2,"00000001",1980L);
        var d=SupportDiagnosis.display(c6,latest,List.of("+1s Start v0.1.1-20 waveshare-c6-touch-lcd-169, Exception","+2s Wi-Fi ok"),
                List.of(start("0000abcd","crash","image",5400L,90000L)),null,now,zone,NONE);
        assertTrue(d.firmwareDiffers());assertEquals(33L,d.logAgeMinutes());assertEquals("0.1.1-20-g1111111",d.logFirmware());
        assertTrue(d.observed().stream().anyMatch(t->t.contains("reports firmware 0.1.1-22-g13998d9 now; the newest log is from 0.1.1-20-g1111111")));
        assertTrue(d.observed().stream().anyMatch(t->t.contains("+02:00")||t.contains("+01:00")),"server times carry their offset");
        assertTrue(d.suspected().contains("Firmware crash while receiving and drawing the image"));
        assertTrue(d.missing().stream().anyMatch(t->t.startsWith("The backtrace of a panic")));
        assertTrue(d.missing().contains("No log of the firmware it runs now: Request current logs"));
        assertEquals(1,d.startLinesWithoutBoot(),"an old start line is counted, not taken as a restart");assertEquals(1,d.unexpectedStarts());
        // ESP8266 crash record with low memory: decode hint; an update before: planned.
        var crashLog=List.of("+0s Start v0.2.0 vibedock-m1: boot=0000beef reset=254 start=crash prev=00000001 phase=tls up=812 heap=9000/3100 rssi=-70 drops=0; "
                +"Software/System restart: abort (e.g. out of memory) (reason 254, cause 0 at 0x40212345, address 0x00000000), stack 1200 bytes free");
        var m=SupportDiagnosis.display(m1,new DeviceLogs.Entry("y.json",now,"usb","0.2.0","vibedock-m1",1,"0000beef",null),crashLog,
                List.of(start("0000beef","crash","tls",812L,3100L),start("0000bee0","update","ota",100L,null)),null,now,zone,NONE);
        assertTrue(m.crashRecord().contains("at 0x40212345"));assertEquals(0,m.startLinesWithoutBoot());
        assertTrue(m.suspected().stream().anyMatch(t->t.contains("out of memory is likely")));
        assertTrue(m.missing().stream().anyMatch(t->t.contains("the ELF of vibedock-m1 0.2.0")));
        assertFalse(m.missing().stream().anyMatch(t->t.startsWith("The backtrace")),"the ESP8266 has its crash record");
        assertEquals(1,m.unexpectedStarts());
        var planned=SupportDiagnosis.display(m1,null,List.of(),List.of(start("0000bee1","update","ota",100L,null)),null,now,zone,NONE);
        assertTrue(planned.suspected().contains("The last restart was planned (update)"));assertEquals(0,planned.unexpectedStarts());
        // Not connected, no crash reported: Wi-Fi or server; old firmware: no diagnosis.
        var silent=SupportDiagnosis.display(m1,null,null,List.of(),null,now,zone,NONE);
        assertTrue(silent.suspected().contains("No crash reported: Wi-Fi or server not reachable is more likely"));
        assertTrue(silent.missing().get(0).startsWith("No diagnosis from the display"));
        // The server: warnings and failed saves counted from its log.
        var server=SupportDiagnosis.server(List.of("2026-10-17 08:00:00 WARN x - Saving to /data failed during POST /x: disk full","2026-10-17 08:00:01 INFO y"),true);
        assertEquals(1,server.warnings());assertEquals(1,server.savingFailed());assertEquals(0,server.errors());
    }
    @Test void invalidDiagnosisValuesAreDropped(){
        var v=DeviceDiagnostics.parse("boot=XYZ;up=-1;heap=12a;rssi=-61;start=exploded;phase=image;reset=99999999;evil=1;prev=0000abcd");
        assertEquals(Map.of("rssi","-61","phase","image","prev","0000abcd"),v);
        assertTrue(DeviceDiagnostics.parse("x".repeat(500)).isEmpty());assertTrue(DeviceDiagnostics.parse(null).isEmpty());
    }
    @Test void theCardKnowsTheWifiAndTheAddressOfTheLastPull(@TempDir Path dir){
        var diagnostics=new DeviceDiagnostics(dir.toString(),json,new TestClock());String id="vibedock-0a1b2c000099";
        assertNull(diagnostics.network(id));
        diagnostics.report(id,"boot=0000abcd;up=5;rssi=-47;ssid=Caf%C3%A9%3B%3D%20WLAN","0.3.1","vibedock-m1");
        diagnostics.route(id,"external","home.example.org:10002");
        assertEquals(new DeviceDiagnostics.Network("Café;= WLAN",-47,"external","home.example.org:10002"),diagnostics.network(id));
        // Older firmware: signal only; invalid names are left out.
        for(String bad:new String[]{"","%G1","%0A","x".repeat(33),"a;b"})assertNull(DeviceDiagnostics.ssid(bad),bad);
        diagnostics.report(id,"boot=0000abcd;up=6;rssi=-80","0.3.0","vibedock-m1");
        assertEquals(new DeviceDiagnostics.Network(null,-80,"external","home.example.org:10002"),diagnostics.network(id));
        diagnostics.forget(id);assertNull(diagnostics.network(id));
    }
    @Test void theRouteFollowsTheHostTheDisplayAskedFor(){
        String internal="http://192.168.123.100:10002",external="https://home.example.org:10002";
        assertEquals("lan",DeviceApiController.route(DeviceApiController.usedHost(null,"192.168.123.100:10002"),internal,external));
        // Behind the reverse proxy: the forwarded host counts, not the proxy's upstream address.
        assertEquals("external",DeviceApiController.route(DeviceApiController.usedHost("home.example.org:10002, proxy","192.168.123.100:10002"),internal,external));
        assertEquals("external",DeviceApiController.route(DeviceApiController.usedHost(null,"HOME.example.org"),internal,"https://home.example.org"));
        assertEquals("other",DeviceApiController.route("192.168.123.100:9999",internal,external));
        assertEquals("other",DeviceApiController.route(null,internal,external));
        assertNull(DeviceApiController.usedHost(null,"bad host/"));
    }
}
