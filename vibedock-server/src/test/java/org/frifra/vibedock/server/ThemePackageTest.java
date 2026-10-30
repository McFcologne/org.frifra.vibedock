package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ThemePackageTest {
    private final JsonMapper json=new JsonMapper();
    private static final byte[] STYLE="{\"palette\":{\"background\":\"#000000\"}}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ICON="<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);
    private Map<String,Object> manifest(){
        var root=new LinkedHashMap<String,Object>();
        root.put("format",ThemePackage.FORMAT);root.put("schemaVersion",1);root.put("id","example.midnight");
        root.put("version","1.0.0");root.put("name","Example");root.put("author","Example author");root.put("license","Apache-2.0");
        root.put("renderer",Map.of("id","vibedock-2d","version",1));root.put("definition","style.json");
        root.put("defaultIcon","robot");root.put("icons",List.of(Map.of("id","robot","label","Robot","file","assets/robot.svg")));
        root.put("files",Map.of("style.json",ThemePackage.sha256(STYLE),"assets/robot.svg",ThemePackage.sha256(ICON)));
        return root;
    }
    private byte[] archive(Map<String,Object> manifest,Map<String,byte[]> files) throws IOException {
        var entries=new LinkedHashMap<String,byte[]>();entries.put("theme.json",json.writeValueAsBytes(manifest));entries.putAll(files);return zip(entries);
    }
    private byte[] zip(Map<String,byte[]> entries) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var out=new ZipOutputStream(bytes)){
            for(var entry:entries.entrySet()){out.putNextEntry(new ZipEntry(entry.getKey()));out.write(entry.getValue());out.closeEntry();}
        }
        return bytes.toByteArray();
    }
    private Map<String,byte[]> assets(){return Map.of("style.json",STYLE,"assets/robot.svg",ICON);}

    @Test void readsSelfContainedPackageAndProtectsVerifiedBytes() throws Exception {
        var contents=ThemePackage.read(archive(manifest(),assets()));
        assertEquals("example.midnight",contents.manifest().id());assertEquals("robot",contents.manifest().defaultIcon());
        var exposed=contents.file("style.json");exposed[0]=0;assertArrayEquals(STYLE,contents.file("style.json"));
        assertThrows(UnsupportedOperationException.class,()->contents.paths().clear());
        assertThrows(UnsupportedOperationException.class,()->contents.manifest().files().clear());
        assertThrows(UnsupportedOperationException.class,()->contents.manifest().icons().clear());
    }
    @Test void acceptsArchivesWithExplicitDirectoriesButNotDuplicateNames() throws Exception {
        var files=new LinkedHashMap<>(assets());files.put("assets/",new byte[0]);
        assertEquals(2,ThemePackage.read(archive(manifest(),files)).paths().size());
        // ZipOutputStream refuses duplicates itself: use equal-length names and replace the second one in its bytes.
        byte[] duplicate=zip(Map.of("one.json",STYLE,"two.json",STYLE));
        String raw=new String(duplicate,StandardCharsets.ISO_8859_1).replace("two.json","one.json");
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(raw.getBytes(StandardCharsets.ISO_8859_1)));
    }
    @Test void rejectsTraversalAbsolutePathsAndCrossPlatformAmbiguity() {
        for(String name:List.of("../outside.json","/outside.json","assets/../outside.svg","assets//icon.svg","assets\\icon.svg",
                "c:/icon.svg","assets/./icon.svg","assets/Icon.svg","assets/icon.svg/")){
            assertThrows(IllegalArgumentException.class,()->ThemePackage.read(archive(manifest(),Map.of(name,ICON))),name);
        }
    }
    @Test void requiresExactFileSetAndMatchingDigests() {
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(archive(manifest(),Map.of("style.json",STYLE))));
        var extra=new LinkedHashMap<>(assets());extra.put("unlisted.txt",ICON);
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(archive(manifest(),extra)));
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(archive(manifest(),Map.of("style.json",ICON,"assets/robot.svg",ICON))));
    }
    @Test void rejectsUnsupportedMetadataAndMissingReferences() {
        for(var field:Map.<String,Object>of("format","not-a-theme","schemaVersion",2,"id","../example","version","01.0.0",
                "defaultIcon","absent","definition","absent.json","preview","absent.png","icons",List.of(),"name","bad\nname").entrySet()){
            var root=manifest();root.put(field.getKey(),field.getValue());
            assertThrows(IllegalArgumentException.class,()->ThemePackage.read(archive(root,assets())),field.getKey());
        }
        var unknown=manifest();unknown.put("script","do something");
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(archive(unknown,assets())));
        var renderer=manifest();renderer.put("renderer",Map.of("id","vibedock-2d","version",0));
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(archive(renderer,assets())));
        var duplicate=manifest();duplicate.put("icons",List.of(Map.of("id","robot","label","A","file","assets/robot.svg"),
                Map.of("id","robot","label","B","file","assets/robot.svg")));
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(archive(duplicate,assets())));
    }
    @Test void rejectsDuplicateJsonKeysTrailingDocumentsAndUnsupportedFiles() throws Exception {
        String root=json.writeValueAsString(manifest());
        assertThrows(Exception.class,()->ThemePackage.manifest((root+" {}").getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class,()->ThemePackage.manifest(root.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1").getBytes(StandardCharsets.UTF_8)));
        var script=manifest();script.put("files",Map.of("run.js",ThemePackage.sha256(ICON)));
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(archive(script,Map.of("run.js",ICON))));
    }
    @Test void acceptsMathematicalIntegersButRejectsFractionsAndOverflow() throws Exception {
        var root=manifest();root.put("schemaVersion",new java.math.BigDecimal("1.0"));
        root.put("renderer",Map.of("id","vibedock-2d","version",new java.math.BigDecimal("1.0")));
        assertEquals(1,ThemePackage.manifest(json.writeValueAsBytes(root)).renderer().version());
        for(Number invalid:List.of(1.5,2147483648L)){
            root.put("renderer",Map.of("id","vibedock-2d","version",invalid));
            assertThrows(IllegalArgumentException.class,()->ThemePackage.manifest(json.writeValueAsBytes(root)));
        }
    }
    @Test void boundsExpandedBytesEvenWhenTheZipIsTiny() throws Exception {
        byte[] oversized=new byte[ThemePackage.MAX_FILE+1];
        byte[] compressed=zip(Map.of("huge.json",oversized));assertTrue(compressed.length<10_000);
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(compressed));
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(new byte[ThemePackage.MAX_ARCHIVE+1]));
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(zip(Map.of("theme.json",new byte[ThemePackage.MAX_MANIFEST+1]))));
        var tooMany=new LinkedHashMap<String,byte[]>();for(int i=0;i<65;i++)tooMany.put("file"+i+".json",new byte[0]);
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(zip(tooMany)));
        var expanded=new LinkedHashMap<String,byte[]>();for(int i=0;i<5;i++)expanded.put("file"+i+".json",new byte[ThemePackage.MAX_FILE]);
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(zip(expanded)));
    }
    @Test void rejectsEmptyInvalidOrIncompleteArchives() {
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(null));
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(new byte[0]));
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(ICON));
        assertThrows(IllegalArgumentException.class,()->ThemePackage.read(zip(assets())));
    }
    @Test void exampleMatchesSchemaAndCanBePackedWithoutGeneratedFiles() throws Exception {
        Path example=Path.of("themes/examples/minimal");var files=new LinkedHashMap<String,byte[]>();
        try(var paths=Files.walk(example)){
            for(var path:paths.filter(Files::isRegularFile).sorted().toList())
                files.put(example.relativize(path).toString().replace('\\','/'),Files.readAllBytes(path));
        }
        var contents=ThemePackage.read(zip(files));assertEquals("example.minimal",contents.manifest().id());
        var schema=com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(Path.of("themes/package.schema.json")));
        assertEquals(List.of(),schema.validate(Files.readString(example.resolve("theme.json")),com.networknt.schema.InputFormat.JSON));
        for(String field:List.of("format","schemaVersion","id","version","name","author","license","renderer","definition","defaultIcon","icons","files")){
            var root=manifest();root.remove(field);String body=json.writeValueAsString(root);
            assertFalse(schema.validate(body,com.networknt.schema.InputFormat.JSON).isEmpty(),field);
            assertThrows(IllegalArgumentException.class,()->ThemePackage.manifest(body.getBytes(StandardCharsets.UTF_8)),field);
        }
    }
}
