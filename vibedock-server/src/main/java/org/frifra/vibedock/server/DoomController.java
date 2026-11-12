package org.frifra.vibedock.server;

import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
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
        return Map.of("available",p.available(),"sources",p.sources().stream().map(u->Map.of("host",u.getHost(),"url",u.toString())).toList(),
                "sha1",p.wadSha1(),"cached",p.cached(),"fromFile",p.fromFile());
    }

    /** Which step of a running build is on: the dialog polls it during the download. */
    @GetMapping(value="/admin/doom/state",produces=MediaType.APPLICATION_JSON_VALUE) @ResponseBody
    Map<String,Object> state(){return Map.of("stage",doom.stage(),"source",doom.source());}

    /** Builds the firmware (download and conversion on the first call) and sends it as a UF2 file. */
    @GetMapping("/admin/doom/firmware.uf2")
    ResponseEntity<?> firmware(jakarta.servlet.http.HttpServletRequest request){
        try{
            // The address the administrator uses for this server (without the scheme and a default port), for the way back on the screen.
            int port=request.getServerPort();
            var address=request.getServerName()+(port==80||port==443?"":":"+port);
            var data=doom.firmware(address);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(data.length)
                    .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"vibedock-doom-pico2w-lcd-13.uf2\"")
                    .header(HttpHeaders.CACHE_CONTROL,"no-store")
                    .body(data);
        }catch(IOException e){
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_PLAIN).body(e.getMessage());
        }
    }
}
