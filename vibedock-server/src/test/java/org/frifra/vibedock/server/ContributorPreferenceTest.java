package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ContributorPreferenceTest {
    @TempDir Path directory;
    @Test void optInSurvivesRestartAndOtherThemeSettingsWithoutWritingOnRead() throws Exception {
        var json=JsonCompatibility.mapper();var settings=new ThemeSettings(directory.toString(),json);
        assertEquals("",settings.contributor());assertFalse(Files.exists(directory.resolve("themes.json")));
        settings.setContributor("  Alex Artist  ");settings.setDark(true);settings.setEnabled(Scheme.FOREST,false);
        var restored=new ThemeSettings(directory.toString(),json);assertEquals("Alex Artist",restored.contributor());assertTrue(restored.dark());assertFalse(restored.enabled(Scheme.FOREST));
        byte[] before=Files.readAllBytes(directory.resolve("themes.json"));new ThemeSettings(directory.toString(),json);assertArrayEquals(before,Files.readAllBytes(directory.resolve("themes.json")));
        assertThrows(IllegalArgumentException.class,()->restored.setContributor("x".repeat(161)));assertEquals("Alex Artist",restored.contributor());
        restored.setContributor("");assertEquals("",new ThemeSettings(directory.toString(),json).contributor());assertFalse(json.readTree(directory.resolve("themes.json").toFile()).has("contributor"));
    }
}
