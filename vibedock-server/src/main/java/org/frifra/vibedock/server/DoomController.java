package org.frifra.vibedock.server;

import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/** The Doom firmware of the Pico-LCD-1.3 for the download dialog of the devices page (administration port only). */
@Controller
class DoomController {
    private final DoomFirmware doom;
    DoomController(DoomFirmware doom){this.doom=doom;}

    /** Where the WAD is fetched from and what is packed: the dialog shows it before the download. */
    @GetMapping(value="/admin/doom/plan",produces=MediaType.APPLICATION_JSON_VALUE) @ResponseBody
    Map<String,Object> plan(){
        var p=doom.plan();
        return Map.of("available",p.available(),"url",p.wadUrl(),"host",p.host(),"sha1",p.wadSha1(),"cached",p.cached());
    }

    /** Builds the firmware (download and conversion on the first call) and sends it as a UF2 file. */
    @GetMapping("/admin/doom/firmware.uf2")
    ResponseEntity<?> firmware(){
        try{
            var file=doom.firmware();
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(Files.size(file))
                    .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"vibedock-doom-pico2w-lcd-13.uf2\"")
                    .header(HttpHeaders.CACHE_CONTROL,"no-store")
                    .body(Files.readAllBytes(file));
        }catch(IOException e){
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_PLAIN).body(e.getMessage());
        }
    }
}
