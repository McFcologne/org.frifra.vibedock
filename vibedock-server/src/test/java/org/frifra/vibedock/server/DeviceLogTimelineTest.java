package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** A display log with the server's lines about it, and the key figures of a log against the one before. */
class DeviceLogTimelineTest {
    /** A UTC time as the time line shows it: in server time. */
    private static String local(String utc){
        return java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(Instant.parse(utc));
    }
    private static final long SAVED=Instant.parse("2026-10-25T01:38:27Z").toEpochMilli();
    private static final List<String> DEVICE=List.of(
            "+3s Wi-Fi FRIFRA: 192.168.123.190, -86 dBm, heap 238604",
            "10-25 01:36:04 http://192.168.123.138:10002: HTTP 200",
            "10-25 01:36:47 http://192.168.123.138:10002: HTTP 304",
            "10-25 01:36:47 Log sent to the server");
    private static final List<String> SERVER=List.of(
            "2026-10-25T01:20:00.000Z  INFO 1 --- [exec-1] o.f.vibedock.server.DeviceApiController  : Image transfer vibedock-fc012cfe04e4: full, long before",
            "2026-10-25T01:36:04.156Z  INFO 1 --- [exec-1] o.f.vibedock.server.DeviceApiController  : Image transfer vibedock-fc012cfe04e4: delta, full=134400 bytes, payload=198 bytes, saved=134202 bytes (99.9%), next=59 s (current)",
            "2026-10-25T02:36:47.246+01:00  INFO 1 --- [exec-5] o.f.vibedock.server.DeviceApiController  : Saved the log 1792892207236-net.json sent by vibedock-fc012cfe04e4 (24 lines)",
            "2026-10-25T01:38:26.433Z  INFO 1 --- [exec-9] o.f.vibedock.server.DeviceApiController  : Image transfer vibedock-fc012cfe04e4: delta, payload=42 bytes",
            "2026-10-25T01:39:03.526Z  INFO 1 --- [exec-1] o.f.vibedock.server.DeviceApiController  : Image transfer vibedock-fc012cfe04e4: after the log was read");

    @Test void serverLinesGoBetweenTheDisplayLinesByTimeWithinTheLog() {
        var lines=DeviceLogTimeline.merge(DEVICE,SAVED,SERVER);
        assertEquals(List.of("display","display","server","display","display","server","server"),lines.stream().map(DeviceLogTimeline.Line::source).toList());
        assertEquals(new DeviceLogTimeline.Line("","display","+3s Wi-Fi FRIFRA: 192.168.123.190, -86 dBm, heap 238604"),lines.get(0),"no clock yet: as it is");
        assertEquals(new DeviceLogTimeline.Line(local("2026-10-25T01:36:04Z"),"display","http://192.168.123.138:10002: HTTP 200"),lines.get(1));
        assertEquals(local("2026-10-25T01:36:04Z"),lines.get(2).time(),"same second: after the display line");
        assertTrue(lines.get(2).text().startsWith("Image transfer vibedock-fc012cfe04e4: delta"),"only the message");
        assertEquals(local("2026-10-25T01:36:47Z"),lines.get(5).time(),"other offsets converted");
        assertEquals(local("2026-10-25T01:38:26Z"),lines.get(6).time(),"up to the time the log was saved; earlier and later lines stay out");
    }
    @Test void aLogFromTheEndOfTheYearKeepsItsYear() {
        long january=Instant.parse("2027-01-01T00:00:10Z").toEpochMilli();
        assertEquals(Instant.parse("2026-12-31T23:59:50Z"),DeviceLogTimeline.deviceTime("12-31 23:59:50 Start",january));
        assertNull(DeviceLogTimeline.deviceTime("+5s Start",january));
    }
    @Test void keyFiguresAndTheirChangeAgainstTheLogBefore() {
        var before=DeviceLogTimeline.figures(List.of(
                "10-25 00:54:46 https://home.frifra.org:10002: HTTP 200, TLS 1.3 pinned after 1504 ms; heap 23864+19336 IRAM, TLS 28374, stack 2780/4608, 17864+280 IRAM free after",
                "10-25 00:55:09 Image shown, read in 23201 ms",
                "+0s Start v0.2.4-22 vibedock-m1: boot=1c207f61 reset=6 heap=22568/22568 rssi=-41 drops=0"));
        var now=DeviceLogTimeline.figures(List.of(
                "+18s Wi-Fi iPhoneDM6HVV6X5K: 172.20.10.11, -45 dBm, heap 24168+19336 IRAM",
                "10-25 01:42:21 Image aborted: no data for 15 s at row 61/240 after 17101 ms, heap 14648+320 IRAM",
                "10-25 01:43:53 https://home.frifra.org:10002: HTTP 200, heap 25120+19336 IRAM, TLS 28374, stack 2780/4608, 19232+280 IRAM free after",
                "10-25 01:44:01 Image shown, read in 8544 ms",
                "10-25 01:44:11 https://home.frifra.org:10002: connection lost (-5) after TLS; TLS -29184"));
        assertEquals(new DeviceLogTimeline.Figures(14648,19232,2780,4608,8544,1,1,-45,-45),now);
        assertEquals(List.of("14,648 (-7,920)","19,232 (+1,368)","2,780 of 4,608","8.5 s (-14.7 s)","1 shown, 1 aborted","-45 dBm"),DeviceLogTimeline.cells(now,before));
        assertEquals(List.of("–","–","–","–","0 shown","–"),DeviceLogTimeline.cells(DeviceLogTimeline.figures(List.of("10-25 01:00:00 Clock set (NTP)")),null));
    }
}
