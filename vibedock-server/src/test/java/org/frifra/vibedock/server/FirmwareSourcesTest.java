package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FirmwareSourcesTest {
    private static String error(String yaml){return assertThrows(IllegalArgumentException.class,()->FirmwareSources.parse(yaml)).getMessage();}

    @Test void withoutAFileOnlyTheOfficialSourceApplies(@TempDir Path dir){
        var sources=FirmwareSources.load(dir.resolve("firmware-sources.yaml"));
        assertEquals(1,sources.size());assertEquals("McFcologne/org.frifra.vibedock",sources.get(0).repo());
        assertEquals("client-v",sources.get(0).tagPrefix());assertTrue(sources.get(0).trusted());
    }
    @Test void readsSourcesInOrderWithTheirDefaults(){
        var sources=FirmwareSources.parse("""
                sources:
                  - id: vibedock
                    repo: McFcologne/org.frifra.vibedock
                    tagPrefix: client-v
                    trusted: true
                  - id: alt-fw
                    label: Alternative firmware
                    repo: someone/alt-firmware
                    tagPrefix: v
                    boards: [esp32-cyd, vibedock-m1]
                """);
        assertEquals(2,sources.size());
        var alt=sources.get(1);
        assertEquals("alt-fw",alt.id());assertEquals("Alternative firmware",alt.label());assertFalse(alt.trusted());assertTrue(alt.enabled());
        assertTrue(alt.offers("esp32-cyd"));assertFalse(alt.offers("lilygo-t-display"));
        assertEquals("vibedock",sources.get(0).label());assertTrue(sources.get(0).offers("anything"));
    }
    @Test void refusesWhatIsNotAsExpected(){
        String ok="  - id: a\n    repo: o/r\n    tagPrefix: v\n";
        assertTrue(error("sources: []").contains("1 to 8"));
        assertTrue(error("sources:\n"+ok+ok).contains("used twice"));
        assertTrue(error("sources:\n"+ok+"    url: https://example.com\n").contains("unknown key url"));
        assertTrue(error("sources:\n  - id: a\n    tagPrefix: v\n").contains("repo is missing"));
        assertTrue(error("sources:\n  - id: A_B\n    repo: o/r\n    tagPrefix: v\n").contains("id must be"));
        assertTrue(error("sources:\n  - id: a\n    repo: https://github.com/o/r\n    tagPrefix: v\n").contains("repo must be owner/name"));
        assertTrue(error("sources:\n  - id: a\n    repo: ../x\n    tagPrefix: v\n").contains("repo must be"));
        assertTrue(error("sources:\n  - id: a\n    repo: o/..\n    tagPrefix: v\n").contains("repo must be"));
        assertTrue(error("sources:\n"+ok+"    trusted: yes please\n").contains("true or false"));
        assertTrue(error("sources:\n"+ok+"    boards: [../x]\n").contains("not valid"));
        assertTrue(error("other: 1").contains("expected one key"));
        assertTrue(error("sources: [").contains("not valid YAML"));
        assertTrue(error("sources:\n"+ok+"a: &x [1]\nb: *x\n").length()>0);
        assertTrue(error("!!java.net.URL [\"http://x\"]").length()>0);
    }
    @Test void aFileWithAMistakeNamesTheFile(@TempDir Path dir) throws Exception {
        Path file=Files.writeString(dir.resolve("firmware-sources.yaml"),"sources: []");
        assertTrue(assertThrows(IllegalArgumentException.class,()->FirmwareSources.load(file)).getMessage().startsWith("firmware-sources.yaml: "));
    }

    @Test void writtenListReadsBackTheSame(@TempDir Path dir) throws Exception {
        var list=java.util.List.of(FirmwareSources.OFFICIAL,
                new FirmwareSources.Source("alt-fw","Alt: \"firmware\" #1","someone/alt-firmware","fw-",false,false,java.util.Set.of("esp32-cyd","m1")));
        var file=dir.resolve("firmware-sources.yaml");
        FirmwareSources.write(file,list);
        assertEquals(list,FirmwareSources.load(file));
        assertFalse(Files.exists(dir.resolve("firmware-sources.yaml.tmp")));
    }
    @Test void aListWithAMistakeIsNotWritten(@TempDir Path dir) throws Exception {
        var file=dir.resolve("firmware-sources.yaml");
        var bad=java.util.List.of(new FirmwareSources.Source("Bad Id","x","not a repo","client-v",false,true,java.util.Set.of()));
        assertThrows(IllegalArgumentException.class,()->FirmwareSources.write(file,bad));
        assertFalse(Files.exists(file));
    }
    @Test void aNewSourceGetsAnIdFromItsRepositoryThatIsFree(){
        assertEquals("codexbar-display",FirmwareSources.idFor("DreamyTalesPAN/CodexBar-Display",java.util.List.of("vibedock")));
        assertEquals("codexbar-display-2",FirmwareSources.idFor("a/CodexBar-Display",java.util.List.of("codexbar-display")));
        assertTrue(FirmwareSources.idFor("a/"+"x".repeat(80),java.util.List.of()).length()<=40);
        assertEquals("source",FirmwareSources.idFor("a/...",java.util.List.of()));
    }
}
