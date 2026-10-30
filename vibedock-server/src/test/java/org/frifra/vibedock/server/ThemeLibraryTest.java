package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ThemeLibraryTest {
    static final JsonMapper JSON=new JsonMapper();
    static Map<String,byte[]> unzip(byte[] bytes) throws IOException {
        var files=new LinkedHashMap<String,byte[]>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){for(var e=zip.getNextEntry();e!=null;e=zip.getNextEntry())files.put(e.getName(),zip.readAllBytes());}
        return files;
    }
    static byte[] zip(Map<String,byte[]> files) throws IOException {
        var hashes=new TreeMap<String,String>();files.forEach((name,bytes)->{if(!name.equals("theme.json"))hashes.put(name,ThemePackage.sha256(bytes));});
        var manifest=JSON.readValue(files.get("theme.json"),LinkedHashMap.class);manifest.put("files",hashes);files.put("theme.json",JSON.writeValueAsBytes(manifest));
        var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){
            for(var file:files.entrySet()){zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue());zip.closeEntry();}
        }return out.toByteArray();
    }
    static byte[] theme(String id,String version,String background) throws Exception {
        var files=unzip(ThemeArchive.write(BuiltinThemes.get("midnight").contents(),false,id,"Custom space"));
        var manifest=JSON.readValue(files.get("theme.json"),LinkedHashMap.class);manifest.put("version",version);files.put("theme.json",JSON.writeValueAsBytes(manifest));
        var definition=JSON.readValue(files.get("style.json"),LinkedHashMap.class);((Map)definition.get("palette")).put("background",background);files.put("style.json",JSON.writeValueAsBytes(definition));
        return zip(files);
    }
    @Test void exportsAreSmallCompressedDeterministicAndCarryOptionalInertReferences() throws Exception {
        var source=BuiltinThemes.get("debugger").contents();byte[] simple=ThemeArchive.write(source,false),guided=ThemeArchive.write(source,true);
        assertArrayEquals(simple,ThemeArchive.write(source,false));assertArrayEquals(guided,ThemeArchive.write(source,true));
        assertTrue(simple.length<source.expandedBytes()/2);assertTrue(guided.length<16000,"small complete package including licenses and references");
        assertFalse(unzip(simple).containsKey("authoring/agent.txt"));
        Files.write(Path.of("target/theme-example.vibetheme"),guided);Files.write(Path.of("target/theme-example-minimal.vibetheme"),simple);
        var contents=ThemePackage.read(guided);assertTrue(contents.paths().containsAll(Set.of("authoring/agent.txt","authoring/drawing.schema.json","authoring/package.schema.json")));
        assertTrue(new String(contents.file("authoring/agent.txt"),java.nio.charset.StandardCharsets.UTF_8).contains("untrusted task data"));
        assertEquals(ThemeDefinition.read(source),ThemeDefinition.read(contents));
        try(var zip=new ZipInputStream(new ByteArrayInputStream(guided))){for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())assertEquals(ZipEntry.DEFLATED,entry.getMethod());}
        for(String name:List.of("drawing.schema.json","package.schema.json")){
            assertEquals(JSON.readTree(Files.readAllBytes(Path.of("themes/"+name))),JSON.readTree(contents.file("authoring/"+name)),"bundled agent schema stays current");
        }
    }
    @Test void addCopyUpdateAndRestartPreserveStableIdsAndPublishFreshDefinitions(@TempDir Path dir) throws Exception {
        var library=new ThemeLibrary(dir.toString());byte[] first=theme("user.space","1.0.0","#123456");
        assertFalse(library.inspect(first).exists());var item=library.install(first,"add",null,null);
        var unchanged=library.snapshot();assertSame(item,library.install(first,"add",null,null));assertSame(unchanged,library.snapshot());
        assertEquals(0x123456,item.scheme().background);assertEquals(36,library.snapshot().catalog().all().size());
        assertTrue(library.inspect(theme("user.space","1.0.1","#654321")).canUpdate());
        library.install(theme("user.space","1.0.1","#654321"),"update",null,null);
        assertEquals(0x654321,library.snapshot().catalog().find("user.space").orElseThrow().background);
        assertThrows(ThemeLibrary.Conflict.class,()->library.install(first,"update",null,null));
        assertThrows(ThemeLibrary.Conflict.class,()->library.install(first,"add",null,null));
        library.install(first,"copy","user.second","Independent copy");
        var reloaded=new ThemeLibrary(dir.toString());assertEquals(library.snapshot().catalog().all(),reloaded.snapshot().catalog().all());
        assertEquals("Independent copy",reloaded.snapshot().catalog().find("user.second").orElseThrow().label);
        assertFalse(ThemePackage.read(library.export("user.space",false)).paths().contains("authoring/agent.txt"));
        library.remove("user.second");assertEquals(36,library.snapshot().catalog().all().size());
        assertEquals(35,new ThemeLibrary(Files.createTempDirectory(dir,"separate").toString()).snapshot().catalog().all().size(),"instance catalogs never leak through globals");
    }
    @Test void invalidPackagesNeverReplaceTheWorkingFileAndBuiltinsStayProtected(@TempDir Path dir) throws Exception {
        var library=new ThemeLibrary(dir.toString());var first=theme("user.space","1.0.0","#123456");library.install(first,"add",null,null);
        var before=library.snapshot();var file=dir.resolve("themes/user.space.vibetheme");byte[] stored=Files.readAllBytes(file);
        var unsafe=unzip(theme("user.space","2.0.0","#abcdef"));unsafe.put("assets/theme.svg","<svg xmlns=\"http://www.w3.org/2000/svg\"><script>do bad things</script></svg>".getBytes());
        assertThrows(IllegalArgumentException.class,()->library.install(zip(unsafe),"update",null,null));
        assertSame(before,library.snapshot());assertArrayEquals(stored,Files.readAllBytes(file));
        assertThrows(ThemeLibrary.Conflict.class,()->library.install(ThemeArchive.write(BuiltinThemes.get("midnight").contents(),false),"update",null,null));
        assertThrows(ThemeLibrary.Conflict.class,()->library.remove("midnight"));
        assertThrows(IllegalArgumentException.class,()->library.install(first,"copy","../bad","Bad"));
        assertThrows(IllegalArgumentException.class,()->library.inspect(new byte[]{1,2,3}));
        try(var files=Files.list(dir.resolve("themes"))){assertEquals(1,files.count());}
    }
    @Test void badStartupPackagesAreSkippedWithoutBeingOverwritten(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("themes"));var file=dir.resolve("themes/user.broken.vibetheme");Files.write(file,new byte[]{1,2,3});
        var library=new ThemeLibrary(dir.toString());assertEquals(35,library.snapshot().catalog().all().size());
        assertThrows(ThemeLibrary.Conflict.class,()->library.install(theme("user.broken","1.0.0","#123456"),"add",null,null));
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(file));
    }
    @Test void storedReferencesResolveLiveAndMissingPackagesRetainSelections(@TempDir Path dir) throws Exception {
        var library=new ThemeLibrary(dir.toString());library.install(theme("user.space","1.0.0","#123456"),"add",null,null);
        var settings=new ThemeSettings(dir.toString(),JsonCompatibility.mapper(),library);
        var registry=new ClientRegistry(dir.toString(),JsonCompatibility.mapper(),Clock.systemUTC(),library);
        registry.configureDesign(Design.DEFAULT);byte[] legacy=Files.readAllBytes(dir.resolve("clients.json"));
        registry.configureDesign(new Design(library.snapshot().catalog().find("user.space").orElseThrow(),"",null));
        assertArrayEquals(legacy,Files.readAllBytes(dir.resolve("clients.json.before-theme-icons.bak")));
        assertEquals(4,JSON.readTree(dir.resolve("clients.json").toFile()).get("version").intValue());
        var reloaded=new ClientRegistry(dir.toString(),JsonCompatibility.mapper(),Clock.systemUTC(),library);
        var selected=reloaded.defaultDesign().scheme();assertEquals("user.space",selected.id());assertEquals(0x123456,library.snapshot().effective(selected).background);
        library.install(theme("user.space","1.0.1","#654321"),"update",null,null);assertEquals(0x654321,library.snapshot().effective(selected).background);
        settings.setEnabled(library.snapshot().catalog().find("user.space").orElseThrow(),false);
        assertFalse(new ThemeSettings(dir.toString(),JsonCompatibility.mapper(),new ThemeLibrary(dir.toString())).enabled(selected));
        Files.delete(dir.resolve("themes/user.space.vibetheme"));var missing=new ThemeLibrary(dir.toString());
        assertEquals(Scheme.MIDNIGHT,missing.snapshot().effective(selected));assertEquals("user.space",reloaded.defaultDesign().scheme().id());
        assertTrue(ThemeLibrary.newer("10.0.0","2.99.99"));assertFalse(ThemeLibrary.newer("1.0.0","1.0.0"));
    }
}
