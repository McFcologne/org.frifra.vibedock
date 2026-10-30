package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ThemePreviewPageTest {
    @Test void exportedPageReferencesTheActualSnapshotAndIconsAndRoundTrips() throws Exception {
        var source=BuiltinThemes.get("midnight").contents();byte[] png=PhotoTest.png(240,240,0xff123456);
        var archive=ThemeArchive.preview(source,png,false);var contents=ThemePackage.read(archive);
        assertArrayEquals(png,contents.file(contents.manifest().preview()));
        String html=new String(contents.file("preview.html"),StandardCharsets.UTF_8);
        assertTrue(html.contains(contents.manifest().preview()));
        for(var icon:contents.manifest().icons())assertTrue(html.contains(icon.file()));
        assertFalse(html.contains("<script"));
        assertArrayEquals(archive,ThemeArchive.preview(source,png,false),"offline previews remain deterministic");
        ThemePackage.read(ThemeArchive.write(contents,true));
    }
    @Test void previewMarkupCannotExecuteScriptsFetchExternalResourcesOrInjectMetadata() throws Exception {
        var source=BuiltinThemes.get("midnight").contents();var archive=ThemeArchive.preview(source,PhotoTest.png(240,240,0xff123456),false);
        var contents=ThemePackage.read(archive);String original=new String(contents.file("preview.html"),StandardCharsets.UTF_8);
        for(String unsafe:List.of(original.replace("</main>","<script>alert(1)</script></main>"),
                original.replace("<img src=", "<img onload=\"alert(1)\" src="),original.replace("assets/theme.svg","https://example.com/icon.svg"),
                original.replace("<style>","<style>@import url(https://example.com/style);"))){
            var files=ThemeLibraryTest.unzip(archive);files.put("preview.html",unsafe.getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class,()->ThemePackage.read(ThemeLibraryTest.zip(files)));
        }
        var m=contents.manifest();var named=new ThemePackage.Manifest(m.id(),m.version(),"<script>alert(1)</script>",m.author(),m.license(),m.renderer(),m.definition(),m.defaultIcon(),m.icons(),m.preview(),m.files());
        byte[] escaped=ThemePreviewPage.page(named);assertTrue(new String(escaped,StandardCharsets.UTF_8).contains("&lt;script&gt;"));
        ThemePreviewPage.validate(escaped,named);
    }
}
