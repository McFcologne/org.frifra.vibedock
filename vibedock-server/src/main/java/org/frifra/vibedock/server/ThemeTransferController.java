package org.frifra.vibedock.server;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.io.*;
import java.util.*;

/** Authenticated theme archive lifecycle. CSRF and the administration-listener boundary apply as for other writes. */
@Controller
class ThemeTransferController {
    @ExceptionHandler(IllegalArgumentException.class) @ResponseBody ResponseEntity<Map<String,String>> invalid(IllegalArgumentException error){
        return ResponseEntity.badRequest().body(Map.of("error",error.getMessage()==null?"Invalid theme package":error.getMessage()));
    }
    @ExceptionHandler(ThemeLibrary.Conflict.class) @ResponseBody ResponseEntity<Map<String,String>> conflict(ThemeLibrary.Conflict error){
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",error.getMessage()));
    }
    private final DesignService designs;private final ThemeLibrary library;private final ClientRegistry registry;private final ThemeSettings settings;private final DevicePoke poke;
    ThemeTransferController(ThemeLibrary library,ClientRegistry registry,ThemeSettings settings,DevicePoke poke,DesignService designs){
        this.designs=designs;
        this.library=library;this.registry=registry;this.settings=settings;this.poke=poke;
    }
    private byte[] upload(HttpServletRequest request) throws IOException {
        try(var in=request.getInputStream()){
            byte[] bytes=in.readNBytes(ThemePackage.MAX_ARCHIVE+1);
            if(bytes.length>ThemePackage.MAX_ARCHIVE)throw new IllegalArgumentException("Choose a theme archive of at most 8 MiB");return bytes;
        }
    }
    @PostMapping("/admin/themes/inspect") @ResponseBody ThemeLibrary.Inspection inspect(HttpServletRequest request) throws IOException {
        return library.inspect(upload(request));
    }
    @PostMapping("/admin/themes/import") @ResponseBody Map<String,String> install(HttpServletRequest request,@RequestParam(defaultValue="add") String mode,
            @RequestParam(required=false) String copyId,@RequestParam(required=false) String copyName) throws IOException {
        var installed=library.install(upload(request),mode,copyId,copyName);poke.pullNow(registry.all());
        return Map.of("id",installed.scheme().id(),"name",installed.scheme().label,"version",installed.contents().manifest().version());
    }
    @GetMapping("/admin/themes/{id}/export") ResponseEntity<byte[]> export(@PathVariable String id,@RequestParam(defaultValue="true") boolean agent) throws IOException {
        var contents=ThemeCredits.prepare(ThemePackage.read(library.export(id,agent)),true);byte[] bytes=ThemeArchive.preview(contents,designs.themeExportPreview(contents,DisplayType.M1),agent);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(id+".vibetheme").build().toString()).body(bytes);
    }
    @PostMapping("/admin/themes/{id}/delete") @ResponseBody Map<String,Boolean> remove(@PathVariable String id) throws IOException {
        synchronized(registry){
            var designs=new ArrayList<Design>();designs.add(registry.defaultDesign());registry.views().forEach(v->designs.add(v.design()));
            if(designs.stream().anyMatch(d->d.scheme().id().equals(id)||d.icon()!=null&&"theme-catalog".equals(d.icon().mode())&&id.equals(d.icon().theme())))
                throw new ThemeLibrary.Conflict("This theme or one of its icons is used by a design; change that selection first");
            if(library.snapshot().catalog().all().stream().noneMatch(s->!s.id().equals(id)&&settings.enabled(s)))
                throw new ThemeLibrary.Conflict("Keep at least one theme enabled");
            library.remove(id);
        }
        return Map.of("deleted",true);
    }
}
