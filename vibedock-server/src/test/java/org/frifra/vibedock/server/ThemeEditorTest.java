package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ThemeEditorTest {
    @TempDir Path directory;
    private static final JsonMapper JSON=new JsonMapper();
    static ThemeEditor.Draft edit(ThemeEditor.Draft draft,Map<String,Object> changes){
        var node=JSON.valueToTree(draft);changes.forEach((key,value)->((tools.jackson.databind.node.ObjectNode)node).set(key,JSON.valueToTree(value)));
        if(changes.containsKey("icons"))for(var icon:node.get("icons"))if(!icon.hasNonNull("author"))((tools.jackson.databind.node.ObjectNode)icon).put("author","Test artist");
        return JSON.treeToValue(node,ThemeEditor.Draft.class);
    }
    @Test void everyBuiltInBecomesALosslessIndependentDraftWithoutInstallation() throws Exception {
        var library=new ThemeLibrary(directory.toString());var editor=new ThemeEditor(library);
        for(var original:library.snapshot().items().values()){
            var state=editor.open(original.scheme().id(),false);var d=state.draft();assertNotEquals(d.id(),d.sourceId());assertTrue(state.builtin());
            var copy=ThemePackage.read(editor.archive(d,false));var source=original.contents();
            assertEquals(ThemeDefinition.read(source),ThemeDefinition.read(copy));
            assertEquals(source.manifest().icons().stream().map(i->List.of(i.id(),i.label(),i.file())).toList(),
                    copy.manifest().icons().stream().map(i->List.of(i.id(),i.label(),i.file())).toList());
            assertTrue(copy.manifest().icons().stream().allMatch(i->i.author()!=null));assertEquals(source.manifest().defaultIcon(),copy.manifest().defaultIcon());
            for(String file:source.paths())if(!file.endsWith(".json"))assertArrayEquals(source.file(file),copy.file(file),file);
        }
        assertEquals(35,library.snapshot().items().size());assertFalse(Files.exists(directory.resolve("themes")));
    }
    @Test void savingAndExportingReuseThePackageLifecycleAndStaleDraftsCannotOverwrite() throws Exception {
        var library=new ThemeLibrary(directory.toString());var editor=new ThemeEditor(library);
        var initial=edit(editor.open("blueprint",false).draft(),Map.of("id","editor.copy","name","My blueprint"));
        var before=library.snapshot();byte[] exported=editor.archive(initial,true);
        assertSame(before,library.snapshot());assertTrue(ThemePackage.read(exported).paths().contains("authoring/agent.txt"));
        editor.save(initial);assertTrue(library.snapshot().item("editor.copy").isPresent());
        var first=editor.open("editor.copy",false).draft();var stale=first;
        var drawing=JSON.valueToTree(first.drawing());((tools.jackson.databind.node.ObjectNode)drawing.get("palette")).put("background","#123456");
        editor.save(edit(first,Map.of("drawing",drawing)));
        assertEquals(0x123456,library.snapshot().item("editor.copy").orElseThrow().scheme().drawing.palette().background());
        assertThrows(ThemeLibrary.Conflict.class,()->editor.save(edit(stale,Map.of("version","9.0.0"))));
        assertEquals("1.0.1",library.snapshot().item("editor.copy").orElseThrow().contents().manifest().version());
        var restored=new ThemeLibrary(directory.toString());assertEquals(0x123456,restored.snapshot().item("editor.copy").orElseThrow().scheme().drawing.palette().background());
        assertTrue(restored.snapshot().item("blueprint").orElseThrow().builtin());
    }
    @Test void invalidDrawingIconsAndIdentityLeaveBothDiskAndCatalogUntouched() throws Exception {
        var library=new ThemeLibrary(directory.toString());var editor=new ThemeEditor(library);var draft=editor.open("midnight",false).draft();
        var before=library.snapshot();
        assertThrows(IllegalArgumentException.class,()->editor.save(edit(draft,Map.of("id","../escape"))));
        assertThrows(IllegalArgumentException.class,()->editor.save(edit(draft,Map.of("id","midnight"))));
        var drawing=JSON.valueToTree(draft.drawing());((tools.jackson.databind.node.ObjectNode)drawing).put("font","remote font");
        assertThrows(IllegalArgumentException.class,()->editor.archive(edit(draft,Map.of("drawing",drawing)),false));
        var unsafe=new ThemeEditor.Icon("new","Unsafe","upload.svg",Base64.getEncoder().encodeToString("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes()));
        assertThrows(IllegalArgumentException.class,()->editor.save(edit(draft,Map.of("icons",List.of(unsafe),"defaultIcon","new"))));
        var valid=new ThemeEditor.Icon("dot","Dot","upload.svg",Base64.getEncoder().encodeToString("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\"><circle cx=\"24\" cy=\"24\" r=\"20\" fill=\"#ff0000\"/></svg>".getBytes()));
        var archive=ThemePackage.read(editor.archive(edit(draft,Map.of("icons",List.of(valid),"defaultIcon","dot")),false));
        assertTrue(archive.manifest().icons().getFirst().file().matches("assets/editor-[0-9a-f]{64}\\.svg"));
        assertSame(before,library.snapshot());assertFalse(Files.exists(directory.resolve("themes")));
    }

    @Test void replacingUploadedArtworkDoesNotAccumulateOldImages() throws Exception {
        var library=new ThemeLibrary(directory.toString());var editor=new ThemeEditor(library);
        var draft=edit(editor.open("midnight",false).draft(),Map.of("id","editor.art"));
        var icon=new ThemeEditor.Icon("dot","Dot","upload.svg",Base64.getEncoder().encodeToString("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\"><circle cx=\"24\" cy=\"24\" r=\"20\" fill=\"#ff0000\"/></svg>".getBytes()));
        editor.save(edit(draft,Map.of("icons",List.of(icon),"defaultIcon","dot")));
        var next=editor.open("editor.art",false).draft();
        var replacement=new ThemeEditor.Icon("dot","Dot","upload.svg",Base64.getEncoder().encodeToString("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\"><circle cx=\"24\" cy=\"24\" r=\"20\" fill=\"#00ff00\"/></svg>".getBytes()));
        var saved=editor.save(edit(next,Map.of("icons",List.of(replacement)))).contents();
        assertEquals(1,saved.paths().stream().filter(path->path.startsWith("assets/editor-")).count());
        assertFalse(new String(saved.file("license.txt")).contains("Twemoji"));
        assertTrue(new String(saved.file("credits.txt")).contains("Test artist"));
        assertFalse(saved.paths().contains("assets/theme.svg"),"replaced source artwork is omitted");
        assertFalse(saved.paths().contains("assets/default.svg"),"an undeclared legacy alternative is omitted");
        var exported=ThemePackage.read(library.export("editor.art",false));
        assertTrue(exported.paths().contains(exported.manifest().icons().getFirst().file()));
        assertEquals(1,exported.paths().stream().filter(path->path.endsWith(".svg")||path.endsWith(".png")).count());
    }
}
