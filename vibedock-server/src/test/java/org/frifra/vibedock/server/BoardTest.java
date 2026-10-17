package org.frifra.vibedock.server;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** The server's board inventory and the firmware's PlatformIO environments name the same boards and the same test state. */
class BoardTest {
    @Test void inventoryMatchesThePlatformIoEnvironments() throws Exception {
        Path ini=Path.of("../vibedock-client/firmware/platformio.ini");
        Assumptions.assumeTrue(Files.isRegularFile(ini),"firmware sources not next to the server (e.g. Docker build)");
        var hardware=new LinkedHashMap<String,String>();String env=null;
        Pattern section=Pattern.compile("^\\[env:(.+)]$"),value=Pattern.compile("^custom_hardware\\s*=\\s*(\\S+)");
        for(String line:Files.readAllLines(ini)){
            var s=section.matcher(line);if(s.matches()){env=s.group(1);hardware.put(env,null);continue;}
            if(line.startsWith("["))env=null;
            var v=value.matcher(line);if(env!=null&&v.find())hardware.put(env,v.group(1));
        }
        var boards=new LinkedHashMap<String,String>();
        for(var b:Board.INVENTORY)boards.put(b.id(),b.tested()?"tested":"prepared");
        Assertions.assertEquals(boards,hardware);
        Assertions.assertEquals(Set.of("vibedock-m1","waveshare-s3-touch-lcd-147","waveshare-c6-touch-lcd-169"),
                new HashSet<>(Board.INVENTORY.stream().filter(Board::tested).map(Board::id).toList()));
    }
}
