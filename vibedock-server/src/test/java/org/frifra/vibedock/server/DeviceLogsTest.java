package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeviceLogsTest {
    private final ObjectMapper json=new ObjectMapper();
    private static Clock ticking(){
        var now=new long[]{Instant.parse("2026-10-17T08:00:00Z").toEpochMilli()};
        return new Clock(){
            @Override public ZoneId getZone(){return ZoneOffset.UTC;}
            @Override public Clock withZone(ZoneId zone){return this;}
            @Override public Instant instant(){return Instant.ofEpochMilli(now[0]+=1000);}
        };
    }
    @Test void keepsTheLastTenLogsOfEachDeviceNewestFirst(@TempDir Path dir) throws Exception {
        var logs=new DeviceLogs(dir.toString(),json,ticking());
        for(int i=1;i<=12;i++)logs.save("vibedock-0a1b2c000001","usb","0.1.1","vibedock-m1",List.of("line "+i));
        logs.save("vibedock-0a1b2c000002","usb","0.1.0",null,List.of("other"));
        var entries=logs.list("vibedock-0a1b2c000001");
        assertEquals(DeviceLogs.KEEP,entries.size());
        assertEquals(List.of("line 12"),logs.read("vibedock-0a1b2c000001",entries.getFirst().name()).orElseThrow().lines());
        assertEquals(List.of("line 3"),logs.read("vibedock-0a1b2c000001",entries.getLast().name()).orElseThrow().lines());
        assertEquals("0.1.1",entries.getFirst().firmware());assertEquals("usb",entries.getFirst().source());
        assertEquals(1,logs.count("vibedock-0a1b2c000002"));
    }
    @Test void aLogKeepsTheServerLinesAboutItsDisplayFromTheTimeItWasSaved(@TempDir Path dir) throws Exception {
        var logs=new DeviceLogs(dir.toString(),json,ticking());
        Path logDir=java.nio.file.Files.createDirectories(dir.resolve("logs"));
        org.springframework.test.util.ReflectionTestUtils.setField(logs,"logDir",logDir.toString());
        String id="vibedock-0a1b2c000007";
        // The ticking clock starts at 2026-10-17T08:00:00Z and moves a second per reading.
        java.nio.file.Files.writeString(logDir.resolve("vibedock.log"),
                "2026-10-17T07:59:00.000Z  INFO 1 --- [x] o.f.v.s.DeviceApiController  : Image transfer "+id+": before the log\n"+
                "2026-10-17T08:00:00.000Z  INFO 1 --- [x] o.f.v.s.DeviceApiController  : Image transfer "+id+": delta, payload=198 bytes\n"+
                "2026-10-17T08:00:00.100Z  INFO 1 --- [x] o.f.v.s.DeviceApiController  : Image transfer vibedock-0a1b2c000008: another display\n");
        var entry=logs.save(id,"net","0.2.4","vibedock-m1",List.of("10-17 08:00:00 http://192.168.1.10:10002: HTTP 200"));
        java.nio.file.Files.delete(logDir.resolve("vibedock.log"));  // rotated away: the log keeps its lines
        var server=logs.read(id,entry.name()).orElseThrow().serverLines();
        assertEquals(1,server.size());assertTrue(server.getFirst().endsWith(": delta, payload=198 bytes"));
        // Without a server log the log has none (null: not taken), and stays readable.
        var plain=new DeviceLogs(dir.toString(),json,ticking());
        assertNull(plain.read(id,plain.save(id,"usb","0.2.4","vibedock-m1",List.of("x")).name()).orElseThrow().serverLines());
    }
    @Test void savedLogsHoldNoKeysNoWebLinksAndAreBounded(@TempDir Path dir) throws Exception {
        var logs=new DeviceLogs(dir.toString(),json,ticking());
        String key="ab".repeat(32);
        List<String> lines=new ArrayList<>(List.of("Authorization: Bearer "+key,"key "+key.toUpperCase(),"https://h.example/web/"+key+"/image.png","bell\u0007and\nnewline"));
        lines.add("x".repeat(1000));
        for(int i=0;i<600;i++)lines.add("filler "+i);
        var entry=logs.save("vibedock-0a1b2c000003","usb","0.1.1<script>","vibedock-m1",lines);
        var saved=logs.read("vibedock-0a1b2c000003",entry.name()).orElseThrow().lines();
        String all=String.join("\n",saved);
        assertFalse(all.toLowerCase().contains(key));assertFalse(all.contains("\u0007"));
        assertTrue(saved.size()<=DeviceLogs.MAX_LINES);
        assertTrue(saved.stream().allMatch(l->l.length()<=DeviceLogs.MAX_LINE+2));
        assertTrue(all.length()<=DeviceLogs.MAX_BYTES);
        assertEquals("0.1.1script",entry.firmware());
        // Only the newest lines are kept when a log is too long.
        assertEquals("filler 599",saved.getLast());
        assertTrue(Files.readString(dir.resolve("device-logs/vibedock-0a1b2c000003/"+entry.name())).contains("filler 599"));
    }
    @Test void identifiersAndNamesCannotLeaveTheLogFolder(@TempDir Path dir) throws Exception {
        var logs=new DeviceLogs(dir.toString(),json,ticking());
        for(String id:new String[]{null,"../../etc","short","........","a/b/c/d/e/f",""})
            assertThrows(IllegalArgumentException.class,()->logs.save(id,"usb",null,null,List.of("x")),String.valueOf(id));
        assertThrows(IllegalArgumentException.class,()->logs.save("vibedock-0a1b2c000004","ftp",null,null,List.of("x")));
        logs.save("vibedock-0a1b2c000004","usb",null,null,List.of("x"));
        assertTrue(logs.read("vibedock-0a1b2c000004","../../settings.json").isEmpty());
        assertFalse(logs.delete("vibedock-0a1b2c000004","../settings.json"));
        assertEquals(0,logs.count("../../etc"));
    }
    @Test void logRequestsAreUsedOnceAndExpire(@TempDir Path dir) throws Exception {
        var now=new long[]{0};
        var clock=new Clock(){
            @Override public ZoneId getZone(){return ZoneOffset.UTC;}
            @Override public Clock withZone(ZoneId zone){return this;}
            @Override public Instant instant(){return Instant.ofEpochMilli(now[0]);}
        };
        var logs=new DeviceLogs(dir.toString(),json,clock);String id="vibedock-0a1b2c000006";
        assertTrue(logs.pendingRequest(id).isEmpty());assertFalse(logs.takeRequest(id,null));
        String first=logs.request(id),second=logs.request(id);
        assertNotEquals(first,second);assertTrue(second.matches("[0-9a-f]{32}"));
        assertFalse(logs.takeRequest(id,first),"a new request replaces the open one");
        assertEquals(Optional.of(second),logs.pendingRequest(id));
        assertTrue(logs.takeRequest(id,second));assertFalse(logs.takeRequest(id,second));
        String late=logs.request(id);now[0]+=DeviceLogs.REQUEST_VALID.toMillis()+1;
        assertTrue(logs.pendingRequest(id).isEmpty());assertFalse(logs.takeRequest(id,late));
        assertThrows(IllegalArgumentException.class,()->logs.request("../x"));
    }
    @Test void deletingOneOrAllLogs(@TempDir Path dir) throws Exception {
        var logs=new DeviceLogs(dir.toString(),json,ticking());
        var first=logs.save("vibedock-0a1b2c000005","usb",null,null,List.of("a"));
        logs.save("vibedock-0a1b2c000005","usb",null,null,List.of("b"));
        assertTrue(logs.delete("vibedock-0a1b2c000005",first.name()));
        assertEquals(1,logs.count("vibedock-0a1b2c000005"));
        logs.deleteAll("vibedock-0a1b2c000005");
        assertEquals(0,logs.count("vibedock-0a1b2c000005"));
        assertFalse(Files.exists(dir.resolve("device-logs/vibedock-0a1b2c000005")));
    }
}
