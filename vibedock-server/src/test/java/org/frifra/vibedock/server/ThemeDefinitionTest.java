package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ThemeDefinitionTest {
    private final JsonMapper json=new JsonMapper();
    private static final Path EXAMPLE=Path.of("themes/examples/minimal");
    private byte[] example() throws IOException {return Files.readAllBytes(EXAMPLE.resolve("style.json"));}
    private Map<String,Object> definition() throws IOException {return json.readValue(example(),LinkedHashMap.class);}
    private byte[] zip(Map<String,byte[]> files) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)){
            for(var file:files.entrySet()){zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue());zip.closeEntry();}
        }
        return bytes.toByteArray();
    }
    @Test void allBuiltinPackagesMatchTheSchemaAndCanRenderUnderDifferentIds() throws Exception {
        var schema=com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(Path.of("themes/drawing.schema.json")));
        var envelope=com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(Path.of("themes/package.schema.json")));
        assertEquals(ThemeCatalog.builtins().all().stream().map(Scheme::id).toList(),BuiltinThemes.ids());
        var renderer=new StatusImage("Europe/Berlin");
        for(var original:ThemeCatalog.builtins().all()){
            var builtin=BuiltinThemes.get(original.id());var files=new LinkedHashMap<String,byte[]>();
            try(var manifest=getClass().getResourceAsStream("/themes/builtin/"+original.id()+"/theme.json")){
                files.put("theme.json",Objects.requireNonNull(manifest).readAllBytes());
            }
            assertEquals(List.of(),envelope.validate(new String(files.get("theme.json"),StandardCharsets.UTF_8),com.networknt.schema.InputFormat.JSON),original.id());
            for(var path:builtin.contents().paths())files.put(path,builtin.contents().file(path));
            var loaded=ThemePackage.read(zip(files));var drawing=ThemeDefinition.read(loaded);
            assertEquals(original.drawing,drawing);
            assertEquals(List.of(),schema.validate(new String(loaded.file(loaded.manifest().definition()),StandardCharsets.UTF_8),com.networknt.schema.InputFormat.JSON),original.id());
            var renamed=new Scheme("custom."+original.id(),"Independent copy",drawing);
            var before=renderer.render(LayoutTest.snapshot(0),"192.168.1.50",new StatusImage.Look(null,original,null,null));
            var after=renderer.render(LayoutTest.snapshot(0),"192.168.1.50",new StatusImage.Look(null,renamed,null,null));
            assertArrayEquals(before.png(),after.png(),original.id());assertArrayEquals(before.rgb565(),after.rgb565(),original.id());
        }
    }
    @Test void customPackageDrawsItsLayersWithoutBeingRegistered() throws Exception {
        var files=new LinkedHashMap<String,byte[]>();
        try(var paths=Files.walk(EXAMPLE)){
            for(var path:paths.filter(Files::isRegularFile).sorted().toList())files.put(EXAMPLE.relativize(path).toString().replace('\\','/'),Files.readAllBytes(path));
        }
        var contents=ThemePackage.read(zip(files));var drawing=ThemeDefinition.read(contents);
        var scheme=new Scheme(contents.manifest().id(),contents.manifest().name(),drawing);
        assertFalse(Scheme.known(scheme.id()));
        var renderer=new StatusImage("Europe/Berlin");
        var image=renderer.render(LayoutTest.snapshot(0),"192.168.1.50",new StatusImage.Look(null,scheme,null,"13°"));
        var plain=renderer.render(LayoutTest.snapshot(0),"192.168.1.50",new StatusImage.Look(null,Scheme.MIDNIGHT,null,"13°"));
        assertFalse(Arrays.equals(plain.png(),image.png()));assertEquals(115200,image.rgb565().length);
        Files.createDirectories(Path.of("target"));Files.write(Path.of("target/theme-package-example.png"),image.png());
    }
    @Test void rejectsUnknownFieldsRecipesFontsColoursVersionsAndResourceRequests() throws Exception {
        for(var invalid:Map.<String,Object>of("version",2,"format","unknown","preset","unknown","font","https://example.com/font.ttf",
                "script","alert(1)","footerBackground","@missing","barColor","#gg0000").entrySet()){
            var root=definition();root.put(invalid.getKey(),invalid.getValue());
            assertThrows(IllegalArgumentException.class,()->ThemeDefinition.read(json.writeValueAsBytes(root)),invalid.getKey());
        }
        var root=definition();root.put("palette",Map.of("background","#000000"));
        assertThrows(IllegalArgumentException.class,()->ThemeDefinition.read(json.writeValueAsBytes(root)));
        assertThrows(IllegalArgumentException.class,()->ThemeDefinition.read(new byte[ThemeDefinition.MAX_BYTES+1]));
        assertThrows(IllegalArgumentException.class,()->ThemeDefinition.read((byte[])null));
        assertThrows(Exception.class,()->ThemeDefinition.read((new String(example(),StandardCharsets.UTF_8)+" {}").getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class,()->ThemeDefinition.read(new String(example(),StandardCharsets.UTF_8).replace("\"version\": 1","\"version\": 1, \"version\": 1").getBytes(StandardCharsets.UTF_8)));
    }
    private Map<String,Object> rectangle(){return new LinkedHashMap<>(Map.of("op","rect","x",0,"y",0,"width",10,"height",10,"color","@title"));}
    @Test void boundsDrawingOperationsAndRejectsExecutableCoordinates() throws Exception {
        var root=definition();
        var many=Collections.nCopies(33,rectangle());root.put("layers",Map.of("background",many));
        assertThrows(IllegalArgumentException.class,()->ThemeDefinition.read(json.writeValueAsBytes(root)));
        for(var field:Map.<String,Object>of("op","image","x","Math.random()","width",Map.of("relative",1,"pixels",5000),
                "opacity",1.1,"radius",-1,"fill","yes","url","https://example.com/picture.svg").entrySet()){
            var shape=rectangle();shape.put(field.getKey(),field.getValue());root.put("layers",Map.of("band",List.of(shape)));
            assertThrows(IllegalArgumentException.class,()->ThemeDefinition.read(json.writeValueAsBytes(root)),field.getKey());
        }
        var grid=rectangle();grid.put("op","grid");grid.put("step",0);root.put("layers",Map.of("background",List.of(grid)));
        assertThrows(IllegalArgumentException.class,()->ThemeDefinition.read(json.writeValueAsBytes(root)));
    }
    @Test void layersStayClippedAndRestoreTheGraphicsState() throws Exception {
        var root=definition();
        root.put("layers",Map.of("band",List.of(Map.of("op","rect","x",-100,"y",-100,"width",400,"height",400,"color","#ff0000"))));
        var drawing=ThemeDefinition.read(json.writeValueAsBytes(root));var image=new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();var transform=g.getTransform();var composite=g.getComposite();
        try{
            drawing.layers().drawBand(g,10,20,30,40);
            assertEquals(0xff000000,image.getRGB(9,20));assertEquals(0xffff0000,image.getRGB(10,20));
            assertEquals(0xffff0000,image.getRGB(39,59));assertEquals(0xff000000,image.getRGB(40,59));
            assertEquals(transform,g.getTransform());assertEquals(composite,g.getComposite());assertNull(g.getClip());
        }finally{g.dispose();}
    }
    @Test void relativeShapesAndAllOperationsScaleInsideTheirOwnLayer() throws Exception {
        var shapes=List.of(
                Map.of("op","gradient","x",0,"y",0,"width",Map.of("relative",1,"pixels",0),"height",20,"color","#ff0000","to","#0000ff","vertical",false),
                Map.of("op","line","x",0,"y",25,"width",90,"height",0,"color","#ffffff"),
                Map.of("op","ellipse","x",10,"y",30,"width",10,"height",10,"color","#00ff00"),
                Map.of("op","grid","x",0,"y",50,"width",90,"height",20,"color","#ffffff","step",10));
        var root=definition();root.put("layers",Map.of("background",shapes));
        var drawing=ThemeDefinition.read(json.writeValueAsBytes(root));var image=new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try{drawing.layers().drawBackground(g,100,100);}finally{g.dispose();}
        assertEquals(0xffff0000,image.getRGB(0,0));assertNotEquals(image.getRGB(0,0),image.getRGB(90,0));
        assertEquals(0xffffffff,image.getRGB(50,25));assertEquals(0xff00ff00,image.getRGB(15,35));assertEquals(0xffffffff,image.getRGB(10,55));
    }
    @Test void explicitColourOverridesReachThePixelsAndKeepLowQuotaWarnings() throws Exception {
        var root=definition();root.remove("layers");root.put("barColor","#ff00ff");root.put("footerBackground","#123456");
        var scheme=new Scheme("custom.colours","Custom colours",ThemeDefinition.read(json.writeValueAsBytes(root)));
        var renderer=new StatusImage("Europe/Berlin");
        var normal=renderer.render(LayoutTest.snapshot(0),"",new StatusImage.Look(null,scheme,null,null),DisplayType.M1,2);
        var low=renderer.render(LayoutTest.snapshot(1),"",new StatusImage.Look(null,scheme,null,null),DisplayType.M1,2);
        var a=javax.imageio.ImageIO.read(new ByteArrayInputStream(normal.png()));var b=javax.imageio.ImageIO.read(new ByteArrayInputStream(low.png()));
        assertEquals(0xffff00ff,a.getRGB(65*2,40*2));assertNotEquals(0xffff00ff,b.getRGB(65*2,40*2));
        assertEquals(0xff123456,a.getRGB(220*2,230*2));
        for(String preset:List.of("terminal","mac","windows","lcd","blueprint","print")){
            root.put("preset",preset);assertThrows(IllegalArgumentException.class,()->ThemeDefinition.read(json.writeValueAsBytes(root)));
        }
    }
    @Test void anUnknownRendererCannotBeUsedEvenWithValidArchiveChecksums() throws Exception {
        var files=new LinkedHashMap<String,byte[]>();
        try(var paths=Files.walk(EXAMPLE)){
            for(var path:paths.filter(Files::isRegularFile).toList())files.put(EXAMPLE.relativize(path).toString().replace('\\','/'),Files.readAllBytes(path));
        }
        var manifest=json.readValue(files.get("theme.json"),LinkedHashMap.class);
        for(var renderer:List.of(Map.of("id","unknown","version",1),Map.of("id","vibedock-2d","version",2))){
            manifest.put("renderer",renderer);files.put("theme.json",json.writeValueAsBytes(manifest));
            var contents=ThemePackage.read(zip(files));
            assertThrows(IllegalArgumentException.class,()->ThemeDefinition.read(contents));
        }
    }
}
