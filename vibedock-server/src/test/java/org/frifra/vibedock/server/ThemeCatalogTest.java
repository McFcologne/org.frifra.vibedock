package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ThemeCatalogTest {
    @Test void preservesEveryPublishedIdLabelStyleAndSwipePosition() throws Exception {
        var catalog=ThemeCatalog.builtins();var json=JsonCompatibility.mapper();
        assertEquals(Arrays.stream(BaselineScheme.values()).map(BaselineScheme::id).toList(),catalog.all().stream().map(Scheme::id).toList());
        for(var old:BaselineScheme.values()){
            var current=catalog.find(old.id()).orElseThrow();
            assertEquals(old.label,current.label);assertEquals(old.font(),current.font());
            assertEquals(old.light(),current.light());assertEquals(old.windowed(),current.windowed());
            assertEquals(old.developer(),current.developer());assertEquals(old.illustrated(),current.illustrated());
            for(int step:List.of(-36,-1,0,1,36))assertEquals(old.shifted(step).id(),current.shifted(step).id());
            assertEquals(json.writeValueAsString(old),json.writeValueAsString(current));
            assertSame(current,json.readValue(json.writeValueAsString(old),Scheme.class));
            assertSame(current,json.readValue("\""+old.name()+"\"",Scheme.class));
            for(String mood:List.of("","🚀")){
                var stored="{\"scheme\":\""+old.name()+"\",\"mood\":\""+mood+"\"}";
                var design=json.readValue(stored,Design.class);
                assertSame(current,design.scheme());assertEquals(mood,design.mood());assertEquals(Layout.DEFAULT,design.layout());
                assertEquals(design,json.readValue(json.writeValueAsString(design),Design.class));
            }
        }
        assertThrows(UnsupportedOperationException.class,()->catalog.all().clear());
        assertFalse(Scheme.known(null));assertFalse(Scheme.known("MIDNIGHT"));assertTrue(Scheme.known("midnight"));
        assertSame(Scheme.MIDNIGHT,Scheme.of("absent"));assertSame(Scheme.MIDNIGHT,Scheme.of(null));
        assertThrows(IllegalArgumentException.class,()->Scheme.parse("absent"));
    }
    @Test void catalogOrderAndAvailabilityWorkForDefinitionsOutsideTheOldEnum(){
        var custom=new Scheme("example.custom","Custom",0,1,2,3,4,5,Scheme.Style.MODERN);
        var duplicateObject=new Scheme("example.custom","Custom",0,1,2,3,4,5,Scheme.Style.MODERN);
        var catalog=new ThemeCatalog(List.of(Scheme.SAND,custom,Scheme.OCEAN));
        assertEquals(custom,duplicateObject);assertEquals(custom.hashCode(),duplicateObject.hashCode());
        assertNotEquals(custom,new Scheme("example.custom","Changed",0,1,2,3,4,5,Scheme.Style.MODERN));
        assertSame(custom,catalog.find(custom.id()).orElseThrow());
        assertSame(Scheme.OCEAN,catalog.shifted(duplicateObject,1));assertSame(Scheme.SAND,catalog.shifted(custom,-1));
        assertSame(Scheme.OCEAN,catalog.nextEnabled(Scheme.SAND,1,Set.of(custom.id())));
        assertSame(Scheme.SAND,catalog.nextEnabled(Scheme.OCEAN,-1,Set.of(custom.id())));
        assertSame(custom,catalog.nextEnabled(Scheme.SAND,-1,Set.of("sand","ocean")));
        assertThrows(IllegalArgumentException.class,()->new ThemeCatalog(List.of(custom,duplicateObject)));
        assertThrows(IllegalArgumentException.class,()->new ThemeCatalog(List.of()));
        assertThrows(IllegalArgumentException.class,()->catalog.shifted(Scheme.FOREST,1));
        assertThrows(IllegalArgumentException.class,()->new Scheme("../custom","Custom",0,1,2,3,4,5));
    }
    @Test void loadingLegacyAvailabilityDoesNotRewriteItAndSavingStillUsesIds(@TempDir Path dir) throws Exception {
        var file=dir.resolve("themes.json");String original="{\"disabled\":[\"OCEAN\",\" classic_mac \"],\"dark\":true}";
        Files.writeString(file,original);var settings=new ThemeSettings(dir.toString(),JsonCompatibility.mapper());
        assertEquals(original,Files.readString(file));assertTrue(settings.dark());
        assertFalse(settings.enabled(Scheme.OCEAN));assertFalse(settings.enabled(Scheme.CLASSIC_MAC));
        settings.setEnabled(Scheme.FOREST,false);
        var saved=JsonCompatibility.mapper().readTree(Files.readString(file));
        var disabled=new HashSet<String>();saved.get("disabled").forEach(id->disabled.add(id.textValue()));
        assertEquals(Set.of("ocean","classic_mac","forest"),disabled);assertTrue(saved.get("dark").booleanValue());
        var loaded=new ThemeSettings(dir.toString(),JsonCompatibility.mapper());assertFalse(loaded.enabled(Scheme.FOREST));
        assertEquals(Scheme.SUNSET,loaded.shifted(Scheme.MIDNIGHT,1));
        assertEquals(Scheme.DEBUGGER,loaded.shifted(Scheme.MIDNIGHT,-1));
    }
}
