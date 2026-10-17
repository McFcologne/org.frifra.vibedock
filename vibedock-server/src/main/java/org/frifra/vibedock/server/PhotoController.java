package org.frifra.vibedock.server;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

/** The photo library of the design editor: upload (one photo per request, as its bytes), list, thumbnail, delete. */
@Controller
class PhotoController {
    private static final Logger log=LoggerFactory.getLogger(PhotoController.class);
    private final PhotoLibrary photos;private final ClientRegistry registry;private final DevicePoke poke;
    PhotoController(PhotoLibrary photos,ClientRegistry registry,DevicePoke poke){this.photos=photos;this.registry=registry;this.poke=poke;}

    @GetMapping(value="/admin/photos",produces="application/json") @ResponseBody List<PhotoLibrary.Photo> list() throws IOException {return photos.list();}
    @PostMapping(value="/admin/photos",produces="application/json") @ResponseBody ResponseEntity<Object> upload(HttpServletRequest request) throws IOException {
        byte[] bytes;try(var in=request.getInputStream()){bytes=in.readNBytes(PhotoLibrary.MAX_UPLOAD+1);}
        try{
            var photo=photos.add(bytes);
            log.info("Photo {} added ({}×{} kept of {} bytes uploaded)",photo.id(),photo.width(),photo.height(),bytes.length);
            return ResponseEntity.ok(photo);
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
    }
    @GetMapping("/admin/photos/{id}.jpg") ResponseEntity<byte[]> photo(@PathVariable String id) throws IOException {
        return photos.jpeg(id).map(b->ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate().immutable()).body(b)).orElse(ResponseEntity.notFound().build());
    }
    /** Removes a photo; slideshows skip it from then on, displays showing it get their next image now. */
    @PostMapping(value="/admin/photos/{id}/delete",produces="application/json") @ResponseBody Map<String,Object> delete(@PathVariable String id) throws IOException {
        boolean deleted=photos.delete(id);
        if(deleted){log.info("Photo {} removed",id);poke.pullNow(registry.all());}
        return Map.of("deleted",deleted);
    }
}
