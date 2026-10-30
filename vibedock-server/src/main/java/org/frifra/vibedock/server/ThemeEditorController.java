package org.frifra.vibedock.server;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.util.*;

@Controller
final class ThemeEditorController {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ThemeEditor editor;private final DesignService designs;private final DevicePoke poke;private final ClientRegistry registry;
    ThemeEditorController(ThemeEditor editor,DesignService designs,DevicePoke poke,ClientRegistry registry){this.editor=editor;this.designs=designs;this.poke=poke;this.registry=registry;}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseBody ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){
        return ResponseEntity.status(e instanceof ThemeLibrary.Conflict?HttpStatus.CONFLICT:HttpStatus.BAD_REQUEST)
                .body(Map.of("error",e.getMessage()==null?"Invalid theme draft":e.getMessage()));
    }
    @GetMapping("/admin/themes/{id}/edit") String page(){return "theme-editor";}
    @GetMapping("/admin/themes/{id}/editor") @ResponseBody ResponseEntity<ThemeEditor.State> open(@PathVariable String id,@RequestParam(defaultValue="false") boolean copy) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(editor.open(id,copy));
    }
    private ThemeEditor.Draft draft(HttpServletRequest request) throws IOException {
        // Base64 can expand an 8 MiB package; reject before JSON binding, not after arbitrary allocation.
        byte[] bytes;try(var in=request.getInputStream()){bytes=in.readNBytes(12*1024*1024+1);}
        if(bytes.length>12*1024*1024)throw new IllegalArgumentException("Theme draft exceeds 12 MiB");
        try{return JSON.readValue(bytes,ThemeEditor.Draft.class);}catch(tools.jackson.core.JacksonException e){throw new IllegalArgumentException("Invalid theme draft JSON");}
    }
    @PostMapping("/admin/theme-editor/preview.png") @ResponseBody ResponseEntity<byte[]> preview(HttpServletRequest request,@RequestParam(defaultValue="vibedock-m1") String display) throws IOException {
        var type=DisplayType.INVENTORY.stream().filter(d->d.id().equals(display)).findFirst().orElseThrow(()->new IllegalArgumentException("Choose a supported preview display"));
        var contents=ThemePackage.read(editor.archive(draft(request),false));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).body(designs.themePreview(contents,type));
    }
    @PostMapping("/admin/theme-editor/export") @ResponseBody ResponseEntity<byte[]> export(HttpServletRequest request,@RequestParam(defaultValue="true") boolean agent) throws IOException {
        var input=draft(request);var contents=ThemePackage.read(editor.archive(input,agent));
        byte[] bytes=ThemeArchive.preview(contents,designs.themePreview(contents,DisplayType.M1),agent);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(input.id()+".vibetheme").build().toString()).body(bytes);
    }
    @GetMapping("/admin/themes/{id}/editor-icons/{icon}") @ResponseBody ResponseEntity<Map<String,Object>> originalIcon(@PathVariable String id,@PathVariable String icon) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(editor.pngSource(id,icon));
    }
    @PostMapping("/admin/theme-editor/icon") @ResponseBody Map<String,Object> icon(HttpServletRequest request,@RequestParam String format,@RequestParam(required=false) String color) throws IOException {
        byte[] bytes;try(var in=request.getInputStream()){bytes=in.readNBytes(ThemeIconAsset.MAX_BYTES+1);}
        if(bytes.length==0||bytes.length>ThemeIconAsset.MAX_BYTES)throw new IllegalArgumentException("Choose an icon of at most 128 KiB");
        try{
            int removed=0;
            if("svg".equals(format)){var converted=ThemeIconAsset.staticSvg(bytes);bytes=converted.svg();removed=converted.removed();}
            else if("png".equals(format)){ThemeIconAsset.draw("icon.png",bytes,Twemoji.SIZE);if(color!=null)bytes=ThemeIconAsset.recolorPng(bytes,color);}
            else throw new IllegalArgumentException("Choose PNG or SVG artwork");
            var info="png".equals(format)?ThemeIconAsset.pngInfo(bytes):new ThemeIconAsset.PngInfo(false,"#000000");
            return Map.of("file","icon."+format,"data",Base64.getEncoder().encodeToString(bytes),"removed",removed,"converted",false,
                    "vector","svg".equals(format),"canRecolor",info.supported(),"color",info.color());
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Could not read a static SVG or PNG image");}
    }
    @PostMapping("/admin/theme-editor/save") @ResponseBody Map<String,String> save(HttpServletRequest request) throws IOException {
        var item=editor.save(draft(request));poke.pullNow(registry.all());
        return Map.of("id",item.scheme().id(),"name",item.scheme().label,"version",item.contents().manifest().version());
    }
}
