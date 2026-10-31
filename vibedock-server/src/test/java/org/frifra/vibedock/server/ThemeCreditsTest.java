package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ThemeCreditsTest {
    @TempDir Path directory;
    private static final String SVG="<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\"><rect width=\"40\" height=\"40\" fill=\"#123456\"/></svg>";
    @Test void twemojiCreditsFollowRetainedAssetsAndCustomCreatorSurvivesUpdatesAndExport() throws Exception {
        var library=new ThemeLibrary(directory.toString());var editor=new ThemeEditor(library);
        var initial=editor.open("porcelain",false).draft();
        var custom=new ThemeEditor.Icon("custom","Brand icon","upload.svg",Base64.getEncoder().encodeToString(SVG.getBytes(StandardCharsets.UTF_8)),"Alex Artist","Proprietary");
        var inherited=initial.icons().stream().filter(i->i.id().equals("legacy")).findFirst().orElseThrow();
        var first=ThemeEditorTest.edit(initial,Map.of("id","credits.custom","icons",List.of(custom,inherited),"defaultIcon","custom"));
        var installed=editor.save(first).contents();
        assertTrue(new String(installed.file("license.txt"),StandardCharsets.UTF_8).contains("Twemoji"));
        assertEquals("Alex Artist",installed.manifest().icons().getFirst().author());
        var update=editor.open("credits.custom",false).draft();
        var kept=update.icons().stream().filter(i->i.id().equals("custom")).toList();
        var revised=editor.save(ThemeEditorTest.edit(update,Map.of("icons",kept))).contents();
        assertFalse(new String(revised.file("license.txt"),StandardCharsets.UTF_8).contains("Twemoji"));
        assertFalse(new String(revised.file("credits.txt"),StandardCharsets.UTF_8).contains("Twemoji"));
        assertTrue(new String(revised.file("credits.txt"),StandardCharsets.UTF_8).contains("Alex Artist"));
        var exported=ThemeCredits.prepare(ThemePackage.read(library.export("credits.custom",false)),true);
        assertEquals("Alex Artist",exported.manifest().icons().getFirst().author());assertEquals("Proprietary",exported.manifest().icons().getFirst().license());
        assertFalse(exported.manifest().author().contains("Twemoji"));
    }
    @Test void unknownCustomCreatorMustBeEnteredAndUnknownLicenseNoticesStayIntact() throws Exception {
        var library=new ThemeLibrary(directory.toString());var editor=new ThemeEditor(library);var draft=editor.open("midnight",false).draft();
        var icon=new ThemeEditor.Icon("custom","Custom","upload.svg",Base64.getEncoder().encodeToString(SVG.getBytes(StandardCharsets.UTF_8)),null,null);
        var missing=new ThemeEditor.Draft(draft.sourceId(),draft.sourceDigest(),draft.id(),draft.name(),draft.version(),draft.author(),draft.license(),"custom",draft.drawing(),List.of(icon));
        assertTrue(assertThrows(IllegalArgumentException.class,()->editor.save(missing)).getMessage().contains("creator"));
        var bytes=ThemeLibraryTest.unzip(ThemeArchive.write(BuiltinThemes.get("midnight").contents(),false));
        byte[] notice="A custom notice that mentions Twemoji and must not be rewritten".getBytes(StandardCharsets.UTF_8);bytes.put("license.txt",notice);
        var preserved=ThemeCredits.prepare(ThemePackage.read(ThemeLibraryTest.zip(bytes)),false);assertArrayEquals(notice,preserved.file("license.txt"));
    }
}
