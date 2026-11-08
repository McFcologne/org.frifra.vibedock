package org.frifra.vibedock.server;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.io.IOException;

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

    private static final Path SCRIPT=Path.of("../vibedock-client/firmware/manifest.py"),INI=Path.of("../vibedock-client/firmware/platformio.ini");
    private static String python(Path dir,String... args) throws IOException,InterruptedException {
        var command=new ArrayList<String>(List.of("python3",SCRIPT.toAbsolutePath().toString()));command.addAll(List.of(args));
        var process=new ProcessBuilder(command).redirectErrorStream(true).directory(dir.toFile()).start();
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        Assertions.assertEquals(0,process.waitFor(),output);
        return output;
    }
    /** The manifest manifest.py writes for an environment, with firmware.bin and flash.bin made up. */
    private static byte[] generate(Path dir,Path ini,String env) throws Exception {
        Files.write(dir.resolve("firmware.bin"),("app of "+env).getBytes());Files.write(dir.resolve("flash.bin"),("flash of "+env).getBytes());
        python(dir,"--env",env,"--build-dir",dir.toString(),"--ini",ini.toAbsolutePath().toString(),"--version","0.6.0-test");
        return Files.readAllBytes(dir.resolve("manifest.json"));
    }
    /** The manifests of the build script are accepted by the server's parser and say what Board.java says. */
    @Test void generatedManifestsMatchTheInventory(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(INI)&&Files.isRegularFile(SCRIPT),"firmware sources not next to the server (e.g. Docker build)");
        for(var board:Board.INVENTORY){
            var dir=Files.createDirectory(tmp.resolve(board.id()));
            var manifest=Manifest.parse(generate(dir,INI,board.id()));
            Assertions.assertEquals(board.id(),manifest.id(),board.id());Assertions.assertEquals(board.label(),manifest.label(),board.id());
            Assertions.assertEquals(board.chip(),manifest.chip(),board.id());Assertions.assertEquals(board.tested()?"tested":"prepared",manifest.hardware(),board.id());
            var display=board.displayType();
            Assertions.assertEquals(new Manifest.Screen(display.width(),display.height(),display.corner(),display.grey()),manifest.display(),board.id());
            Assertions.assertEquals(board.rotations(),manifest.capabilities().rotations(),board.id());
            Assertions.assertEquals(board.note().contains("touch"),manifest.capabilities().touch(),board.id());
            Assertions.assertEquals(board.batteryPowerSaving(),manifest.capabilities().powerSaving(),board.id());
            Assertions.assertNotNull(manifest.build(),board.id());
            manifest.verifyBinaries(Files.readAllBytes(dir.resolve("firmware.bin")),Files.readAllBytes(dir.resolve("flash.bin")));
        }
    }
    /** manifest.py --to-ini turns a manifest back into an environment that builds the same manifest. */
    @Test void aManifestMakesTheSameEnvironmentAgain(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(INI)&&Files.isRegularFile(SCRIPT),"firmware sources not next to the server (e.g. Docker build)");
        String original=Files.readString(INI);
        for(var board:Board.INVENTORY){
            Path first=Files.createDirectory(tmp.resolve("a-"+board.id())),second=Files.createDirectory(tmp.resolve("b-"+board.id()));
            byte[] manifest=generate(first,INI,board.id());
            String section=python(first,"--to-ini",first.resolve("manifest.json").toString());
            // The ini without the environment, then the one the manifest describes in its place.
            String without=original.replaceAll("(?ms)^\\[env:"+Pattern.quote(board.id())+"]\\n.*?(?=^\\[|\\z)","");
            Path rebuilt=tmp.resolve("rebuilt-"+board.id()+".ini");Files.writeString(rebuilt,without+"\n"+section);
            Assertions.assertEquals(new String(manifest),new String(generate(second,rebuilt,board.id())),board.id());
        }
    }
}
