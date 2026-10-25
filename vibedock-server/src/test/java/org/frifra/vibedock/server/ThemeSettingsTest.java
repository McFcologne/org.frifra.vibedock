package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ThemeSettingsTest {
    @Test void serverAppearanceSurvivesRestartAndAvailabilityChanges(@TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("themes.json"),"{\"disabled\":[]}");
        var settings=new ThemeSettings(dir.toString(),JsonCompatibility.mapper());
        assertFalse(settings.dark());settings.setDark(true);settings.setEnabled(Scheme.OCEAN,false);
        var loaded=new ThemeSettings(dir.toString(),JsonCompatibility.mapper());
        assertTrue(loaded.dark());assertFalse(loaded.enabled(Scheme.OCEAN));
        loaded.setDark(false);assertFalse(new ThemeSettings(dir.toString(),JsonCompatibility.mapper()).dark());
    }

    @Test void availabilityPersistsAndSwipesSkipInEitherDirection(@TempDir Path dir) throws Exception {
        var settings=new ThemeSettings(dir.toString(),JsonCompatibility.mapper());
        for(var theme:Scheme.values())assertTrue(settings.enabled(theme));
        settings.setEnabled(Scheme.OCEAN,false);
        var loaded=new ThemeSettings(dir.toString(),JsonCompatibility.mapper());
        assertFalse(loaded.enabled(Scheme.OCEAN));
        assertEquals(Scheme.FOREST,loaded.shifted(Scheme.MIDNIGHT,1));
        assertEquals(Scheme.MIDNIGHT,loaded.shifted(Scheme.FOREST,-1));
        assertEquals(Scheme.FOREST,loaded.shifted(Scheme.OCEAN,1));
        assertEquals(Scheme.MIDNIGHT,loaded.shifted(Scheme.OCEAN,-1));
        loaded.setEnabled(Scheme.OCEAN,true);assertEquals(Scheme.OCEAN,loaded.shifted(Scheme.MIDNIGHT,1));
    }
    @Test void theLastThemeCannotBeDisabledAndWrapsToItself(@TempDir Path dir) throws Exception {
        var settings=new ThemeSettings(dir.toString(),JsonCompatibility.mapper());
        for(var theme:Scheme.values())if(theme!=Scheme.PORCELAIN)settings.setEnabled(theme,false);
        assertThrows(IllegalArgumentException.class,()->settings.setEnabled(Scheme.PORCELAIN,false));
        assertEquals(Scheme.PORCELAIN,settings.shifted(Scheme.PORCELAIN,1));
        assertEquals(Scheme.PORCELAIN,settings.shifted(Scheme.MIDNIGHT,-1));
        var restored=new ThemeSettings(dir.toString(),JsonCompatibility.mapper());
        assertEquals(1,Arrays.stream(Scheme.values()).filter(restored::enabled).count());
    }
}
