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
        Assertions.assertEquals(Set.of("vibedock-m1","waveshare-s3-touch-lcd-147","waveshare-s3-touch-lcd-154","waveshare-c6-touch-lcd-169"),
                new HashSet<>(Board.INVENTORY.stream().filter(Board::tested).map(Board::id).toList()));
    }

    @Test void aBoardThatIsNotInTheInventoryIsAnOwnBuildAndGetsNoFirmware(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        var own=Board.of("my-own-board");
        Assertions.assertTrue(own.custom());Assertions.assertEquals("my-own-board",own.id());Assertions.assertNotEquals(Board.M1,own);
        Assertions.assertFalse(own.flippable());Assertions.assertEquals(List.of(0),own.rotations());Assertions.assertFalse(own.esp8266());
        Assertions.assertFalse(Board.M1.custom());Assertions.assertFalse(Board.of("esp32-cyd").custom());
        Assertions.assertEquals(Board.M1,Board.of(null));Assertions.assertEquals(Board.M1,Board.of("../etc/passwd"));Assertions.assertEquals(Board.M1,Board.of(""));
        Assertions.assertTrue(Board.reportable("my-own-board"));Assertions.assertFalse(Board.reportable("a/b"));Assertions.assertFalse(Board.reportable(null));
        // The VibeTV M1's firmware is never offered to it, and the registry keeps what the device reported.
        var firmware=new Firmware(dir.resolve("fw").toString());
        var m1=Files.createDirectories(dir.resolve("fw/vibedock-m1"));
        Files.write(m1.resolve("flash.bin"),"flash".getBytes());Files.write(m1.resolve("firmware.bin"),"app".getBytes());Files.writeString(dir.resolve("fw/version"),"0.5.2\n");
        Assertions.assertTrue(firmware.info(Board.M1).isPresent());Assertions.assertTrue(firmware.info(own).isEmpty());
        var registry=new ClientRegistry(dir.toString(),JsonCompatibility.mapper(),java.time.Clock.systemUTC());
        registry.paired("vibedock-aabbcc","Own","ab".repeat(32),"192.168.1.5",80);
        registry.setBoard("vibedock-aabbcc","my-own-board");
        Assertions.assertEquals("my-own-board",registry.get("vibedock-aabbcc").board().id());
        Assertions.assertTrue(registry.get("vibedock-aabbcc").board().custom());
        Assertions.assertEquals("my-own-board",new ClientRegistry(dir.toString(),JsonCompatibility.mapper(),java.time.Clock.systemUTC()).get("vibedock-aabbcc").board().id());
    }
    @Test void aSaneOwnDisplaySizeIsKeptOthersAreIgnored() {
        var own=DisplayType.reported("320x200").orElseThrow();
        Assertions.assertEquals(320,own.width());Assertions.assertEquals(200,own.height());Assertions.assertFalse(own.grey());
        Assertions.assertEquals("320x200",DisplayType.of("320x200").id());
        Assertions.assertSame(DisplayType.INVENTORY.get(0),DisplayType.reported("vibedock-m1").orElse(DisplayType.INVENTORY.get(0)));
        Assertions.assertEquals(DisplayType.M1,DisplayType.of("9999x9999"));
        for(String bad:new String[]{"10x10","5000x200","axb","320x","-320x200","320x200x2",""})Assertions.assertTrue(DisplayType.reported(bad).isEmpty(),bad);
        Assertions.assertTrue(DisplayType.reported("200x200").orElseThrow().grey());
        // Another board with 200x200 is a colour display; only the e-paper board has the greyscale one.
        Assertions.assertFalse(DisplayType.reported("200x200",Board.of("my-colour-board")).orElseThrow().grey());
        Assertions.assertTrue(DisplayType.reported("200x200",Board.find("waveshare-s3-epaper-154").orElseThrow()).orElseThrow().grey());
        Assertions.assertSame(DisplayType.reported("240x280").orElseThrow(),DisplayType.reported("240x280",Board.of("my-colour-board")).orElseThrow());
    }
}
