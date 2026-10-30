package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ThemeIconsTest {
    private static final Design.Icon DEFAULT=new Design.Icon("theme-default",null,null);
    @Test void allCatalogIconsAreSelfContainedAndRasterizable() throws Exception {
        assertEquals(80,ThemeIcons.catalog().size());assertEquals(45,ThemeIcons.catalog().stream().filter(ThemeIcons.Entry::themeDefault).count());
        for(var icon:ThemeIcons.catalog()){
            assertTrue(ThemeIcons.png(icon.theme(),icon.id()).isPresent(),icon.theme()+"/"+icon.id());
            assertTrue(icon.image().startsWith("/admin/themes/"));
        }
        assertTrue(ThemeIcons.png("../outside","theme").isEmpty());assertTrue(ThemeIcons.png("midnight","absent").isEmpty());
    }
    @Test void legacyDesignsKeepTheirEmoticonAndOriginalJsonShape() throws Exception {
        var json=JsonCompatibility.mapper();
        for(String mood:List.of("","🚀")){
            var old=json.readValue("{\"scheme\":\"MIDNIGHT\",\"mood\":\""+mood+"\"}",Design.class);
            assertNull(old.icon());assertEquals(mood,old.mood());assertFalse(json.readTree(json.writeValueAsString(old)).has("icon"));
            for(var icon:List.of(DEFAULT,new Design.Icon("theme-catalog","blueprint","theme"))){
                var design=new Design(old.scheme(),old.mood(),old.weather(),old.layout(),old.photos(),icon);
                assertEquals(design,json.readValue(json.writeValueAsString(design),Design.class));
                assertEquals(icon,design.withScheme(Scheme.OCEAN).icon());assertEquals(mood,design.withScheme(Scheme.OCEAN).mood());
                assertEquals(icon,DesignService.forDevice(design.withLayout(new Layout(List.of(new Layout.Context(false),new Layout.Usage(List.of("codex"))))),false).icon());
            }
        }
        assertThrows(IllegalArgumentException.class,()->new Design.Icon("emoji",null,null));
        assertThrows(IllegalArgumentException.class,()->new Design.Icon("theme-default","midnight",null));
        assertThrows(IllegalArgumentException.class,()->new Design.Icon("theme-catalog","../outside","theme"));
    }
    @Test void defaultsFollowTheThemeButCatalogReferencesStayFixed(){
        var midnight=ThemeIcons.image(DEFAULT,Scheme.MIDNIGHT,44).orElseThrow();
        var ocean=ThemeIcons.image(DEFAULT,Scheme.OCEAN,44).orElseThrow();assertNotSame(midnight,ocean);
        var fixed=new Design.Icon("theme-catalog","midnight","theme");
        assertSame(midnight,ThemeIcons.image(fixed,Scheme.MIDNIGHT,44).orElseThrow());
        assertSame(midnight,ThemeIcons.image(fixed,Scheme.OCEAN,44).orElseThrow());
        assertTrue(ThemeIcons.image(null,Scheme.MIDNIGHT,44).isEmpty());
        assertFalse(ThemeIcons.available(new Design.Icon("theme-catalog","missing","theme"),Scheme.MIDNIGHT));
    }
    @Test void missingReferencesFallBackWithoutLosingTheStoredChoice(@TempDir Path dir) throws Exception {
        var usage=mock(UsageService.class);when(usage.snapshot()).thenReturn(LayoutTest.snapshot(0));
        var weather=mock(WeatherService.class);when(weather.current(any())).thenReturn(Optional.empty());
        var service=new DesignService(usage,new StatusImage("Europe/Berlin"),new Twemoji(Twemoji.locate(null).toString()),weather,
                new PhotoLibrary(dir.toString()),Clock.systemUTC());
        var old=new Design(Scheme.MIDNIGHT,"🚀",null);var missing=new Design.Icon("theme-catalog","not.installed","missing");
        var kept=new Design(old.scheme(),old.mood(),null,old.layout(),null,missing);
        assertArrayEquals(service.render(old,"test").png(),service.render(kept,"test").png());
        var input=new DesignService.Input(old.mood(),old.scheme().id(),"",old.layout(),false,null,null,missing);
        assertEquals(missing,service.resolve(input,kept).icon());
        assertThrows(IllegalArgumentException.class,()->service.resolve(input,old));
        assertEquals(missing,kept.icon());
    }
    @Test void optInCreatesOnePrivateRollbackSnapshotAndSurvivesRestart(@TempDir Path dir) throws Exception {
        var registry=new ClientRegistry(dir.toString(),JsonCompatibility.mapper(),Clock.systemUTC());
        registry.configureDesign(new Design(Scheme.MIDNIGHT,"",null));
        byte[] before=Files.readAllBytes(dir.resolve("clients.json"));
        var loaded=new ClientRegistry(dir.toString(),JsonCompatibility.mapper(),Clock.systemUTC());
        assertArrayEquals(before,Files.readAllBytes(dir.resolve("clients.json")));assertFalse(Files.exists(dir.resolve("clients.json.before-theme-icons.bak")));
        var design=new Design(Scheme.OCEAN,"",null,Layout.DEFAULT,null,DEFAULT);loaded.configureDesign(design);
        assertEquals(4,JsonCompatibility.mapper().readTree(dir.resolve("clients.json").toFile()).get("version").intValue());
        var backup=dir.resolve("clients.json.before-theme-icons.bak");assertArrayEquals(before,Files.readAllBytes(backup));
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(backup));
        var again=new ClientRegistry(dir.toString(),JsonCompatibility.mapper(),Clock.systemUTC());assertEquals(design,again.defaultDesign());
        again.renameSources(Map.of("codex","renamed"));assertEquals(DEFAULT,again.defaultDesign().icon());
        again.configureDesign(design.withScheme(Scheme.BLUEPRINT));assertArrayEquals(before,Files.readAllBytes(backup));
        again.configureDesign(new Design(Scheme.OCEAN,"",null));
        assertEquals(3,JsonCompatibility.mapper().readTree(dir.resolve("clients.json").toFile()).get("version").intValue());
        assertArrayEquals(before,Files.readAllBytes(backup));
        var fresh=dir.resolve("fresh");var created=new ClientRegistry(fresh.toString(),JsonCompatibility.mapper(),Clock.systemUTC());
        created.configureDesign(design);created.configureDesign(design.withScheme(Scheme.FOREST));
        assertFalse(Files.exists(fresh.resolve("clients.json.before-theme-icons.bak")),"no misleading pre-feature backup for a fresh themed configuration");
    }
    @Test void deviceOverridesKeepTheirFixedIconAndCanReturnToLegacyInheritance(@TempDir Path dir) throws Exception {
        var registry=new ClientRegistry(dir.toString(),JsonCompatibility.mapper(),Clock.systemUTC());
        var device=registry.addWeb("Icon test",DisplayType.M1.id(),30);
        var fixed=new Design.Icon("theme-catalog","debugger","theme");
        registry.setDesign(device.identifier(),new Design(Scheme.OCEAN,"",null,Layout.DEFAULT,null,fixed));
        var loaded=new ClientRegistry(dir.toString(),JsonCompatibility.mapper(),Clock.systemUTC());
        assertEquals(fixed,loaded.designFor(device.identifier()).icon());
        loaded.setDesign(device.identifier(),null);assertNull(loaded.designFor(device.identifier()).icon());
        assertEquals(3,JsonCompatibility.mapper().readTree(dir.resolve("clients.json").toFile()).get("version").intValue());
    }
    @Test void svgRejectsScriptsEntitiesReferencesAndUnboundedFiles(){
        for(String element:List.of("<script>alert(1)</script>","<image href=\"https://example.com/pixel\"/>","<use href=\"#x\"/>",
                "<style>path{fill:red}</style>","<text>text</text>","<path fill=\"url(https://example.com/)\"/>","<path onclick=\"alert(1)\"/>")){
            String svg="<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\">"+element+"</svg>";
            assertThrows(Exception.class,()->ThemeIconAsset.draw("icon.svg",svg.getBytes(StandardCharsets.UTF_8),44),element);
        }
        var entity="<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/hosts'>]><svg xmlns=\"http://www.w3.org/2000/svg\">&x;</svg>";
        assertThrows(Exception.class,()->ThemeIconAsset.draw("icon.svg",entity.getBytes(StandardCharsets.UTF_8),44));
        assertThrows(Exception.class,()->ThemeIconAsset.draw("icon.svg",new byte[ThemeIconAsset.MAX_BYTES+1],44));
        assertThrows(Exception.class,()->ThemeIconAsset.draw("icon.png",PhotoTest.png(1025,1,0),44));
        assertThrows(Exception.class,()->ThemeIconAsset.draw("icon.png","not an image".getBytes(StandardCharsets.UTF_8),44));
    }

    @Test void literalPresentationStylesCurrentColorAndEmptyDefsRender() throws Exception {
        String svg="<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\" color=\"#ffffff\"><defs/><path d=\"M4 4 L44 4 L44 44 L4 44 Z\" fill=\"none\" stroke=\"currentColor\" style=\"stroke-width: 2px;\"/></svg>";
        var image=ThemeIconAsset.draw("icon.svg",svg.getBytes(StandardCharsets.UTF_8),48);
        assertEquals(0xffffff,image.getRGB(4,24)&0xffffff);assertTrue((image.getRGB(4,24)>>>24)>0);
        assertThrows(Exception.class,()->ThemeIconAsset.draw("icon.svg",svg.replace("stroke-width: 2px;","fill: url(https://example.com/x);").getBytes(StandardCharsets.UTF_8),48));
    }
    @Test void editorSvgCleanupKeepsStaticShapesAndDropsActiveAndExternalContent() throws Exception {
        String svg="<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\"><defs/><script>alert(1)</script><image href=\"https://example.com/x\"/><rect x=\"4\" y=\"4\" width=\"40\" height=\"40\" onclick=\"alert(2)\" style=\"fill:#ff0000;stroke-width:2px;filter:url(https://example.com/y)\"/></svg>";
        var result=ThemeIconAsset.staticSvg(svg.getBytes(StandardCharsets.UTF_8));assertTrue(result.removed()>=5);
        assertEquals(0xff0000,ThemeIconAsset.draw("icon.svg",result.svg(),48).getRGB(24,24)&0xffffff);
        assertTrue(result.svg().length<ThemeIconAsset.MAX_BYTES);
        String deep="<svg xmlns=\"http://www.w3.org/2000/svg\">"+"<g>".repeat(70)+"<rect width=\"48\" height=\"48\"/>"+"</g>".repeat(70)+"</svg>";
        assertThrows(Exception.class,()->ThemeIconAsset.staticSvg(deep.getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class,()->ThemeIconAsset.staticSvg("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class,()->ThemeIconAsset.staticSvg("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/hosts'>]><svg xmlns=\"http://www.w3.org/2000/svg\">&x;</svg>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void pngColorOverridePreservesAlphaAndRejectsMulticolorOrOpaqueImages() throws Exception {
        var image=new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1,1,0xffff0000);image.setRGB(2,1,0x80ff0000);
        var source=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",source);
        assertTrue(ThemeIconAsset.pngInfo(source.toByteArray()).supported());
        byte[] recolored=ThemeIconAsset.recolorPng(source.toByteArray(),"#00ff00");
        var result=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(recolored));
        assertEquals(0xff00ff00,result.getRGB(1,1));assertEquals(0x8000ff00,result.getRGB(2,1));assertEquals(0,result.getRGB(0,0)>>>24);
        image.setRGB(2,1,0xff0000ff);source.reset();javax.imageio.ImageIO.write(image,"png",source);
        assertFalse(ThemeIconAsset.pngInfo(source.toByteArray()).supported());
        byte[] multi=source.toByteArray();assertThrows(IllegalArgumentException.class,()->ThemeIconAsset.recolorPng(multi,"#00ff00"));
        assertFalse(ThemeIconAsset.pngInfo(PhotoTest.png(2,2,0xffff0000)).supported());
    }
}
