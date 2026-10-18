package org.frifra.vibedock.server;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Device firmware: download, instructions, flashing from the browser, an administrator's own build per board and
 * setting the device up over USB right after (administration port only).
 */
@Controller
class FirmwareController {
    private static final Logger log=LoggerFactory.getLogger(FirmwareController.class);
    private final Firmware firmware;private final ClientRegistry registry;private final ClientPairing pairing;private final ServerAddresses addresses;
    private final java.time.ZoneId zone;
    FirmwareController(Firmware firmware,ClientRegistry registry,ClientPairing pairing,ServerAddresses addresses,
                       @org.springframework.beans.factory.annotation.Value("${vibedock.time-zone:Europe/Berlin}") String zone){
        this.firmware=firmware;this.registry=registry;this.pairing=pairing;this.addresses=addresses;this.zone=java.time.ZoneId.of(zone);
    }

    /** A board for the page's selection: whether this installation has firmware for it. */
    record BoardOption(Board board,boolean available) {}
    @GetMapping("/admin/firmware") String page(@RequestParam(defaultValue="") String board,Model model,HttpServletRequest request){
        // The board chosen, else the first one with firmware (the VibeTV M1 in a complete image).
        var selected=Board.find(board).orElseGet(()->firmware.all().stream().findFirst().map(Firmware.Info::board).orElse(Board.M1));
        model.addAttribute("board",selected);
        model.addAttribute("boards",Board.INVENTORY.stream().map(b->new BoardOption(b,firmware.info(b).isPresent())).toList());
        // For the browser flasher: which boards of this installation have which chip, to switch to the right one itself.
        var chips=new java.util.ArrayList<java.util.Map<String,Object>>();
        for(var b:Board.INVENTORY)if(firmware.info(b).isPresent())chips.add(java.util.Map.of("id",b.id(),"label",b.label(),"chip",b.chip()));
        try{model.addAttribute("boardChips",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(chips));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){model.addAttribute("boardChips","[]");}
        model.addAttribute("firmware",firmware.info(selected).orElse(null));
        model.addAttribute("uploadIncomplete",firmware.uploadIncomplete(selected));
        model.addAttribute("uploadedAt",firmware.info(selected).filter(Firmware.Info::uploaded)
                .map(i->java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(java.time.Instant.ofEpochSecond(i.uploadedAt()).atZone(zone))).orElse(null));
        model.addAttribute("anyFirmware",!firmware.all().isEmpty());
        model.addAttribute("webFlasher",firmware.esptool().isPresent());
        // The devices of this board; web displays have no firmware.
        model.addAttribute("clients",registry.views().stream().filter(c->c.web()==null&&c.board().equals(selected)).toList());
        model.addAttribute("announced",addresses.announced(request));
        model.addAttribute("internalLoopback",addresses.internalIsLoopback(request));
        return "firmware";
    }
    @GetMapping("/admin/firmware/{file:.+\\.bin}") ResponseEntity<byte[]> download(@PathVariable String file) throws IOException {
        var info=firmware.all().stream().filter(i->i.fileName().equals(file)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CACHE_CONTROL,"no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(info.fileName()).build().toString())
                .body(firmware.bytes(info.board()));
    }
    /**
     * An own build of a board, sent as the file's bytes: firmware.bin (the app; on the ESP8266 all there is) or, for
     * ESP32 chips, also flash.bin (the USB image) from .pio/build/<board>/. It replaces the built-in firmware of the
     * board for USB and for updates over Wi-Fi (signed with this server's key) until it is removed.
     */
    @PostMapping(value="/admin/firmware/upload",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String,Object>> upload(@RequestParam String board,HttpServletRequest request) throws IOException {
        var target=Board.find(board).orElse(null);
        if(target==null)return ResponseEntity.badRequest().body(Map.of("error","Unknown board"));
        if(request.getContentLengthLong()>Firmware.MAX_SIZE)return ResponseEntity.badRequest().body(Map.of("error","The file is larger than 4 MB"));
        byte[] bytes;
        try(var in=request.getInputStream()){bytes=in.readNBytes(Firmware.MAX_SIZE+1);}
        try{
            var result=firmware.upload(target,bytes);
            log.info("Own firmware {} uploaded for {} ({} file, {} bytes){}",result.version(),target.id(),
                    result.kind()==Firmware.Kind.USB?"USB image":"app",bytes.length,result.complete()?"":", waiting for the other file");
            return ResponseEntity.ok(Map.of("version",result.version(),"kind",result.kind().name().toLowerCase(Locale.ROOT),"complete",result.complete()));
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
    }
    /** Back to the built-in firmware of a board. */
    @PostMapping("/admin/firmware/upload/remove") String removeUpload(@RequestParam String board) throws IOException {
        var target=Board.find(board).orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST));
        firmware.removeUpload(target);log.info("Own firmware of {} removed",target.id());
        return "redirect:/admin/firmware?board="+target.id();
    }
    @GetMapping(value="/admin/firmware/esptool.js",produces="text/javascript") ResponseEntity<byte[]> esptool() throws IOException {
        var path=firmware.esptool().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"max-age=3600").body(Files.readAllBytes(path));
    }

    /** board: what the display reports over USB, or the board chosen on the page for firmware that reports none. */
    record SetupRequest(String identifier,String name,String board,boolean omitTlsPin) {}
    /**
     * Pairs the device the browser has just flashed; the page sends the result over USB together with the Wi-Fi.
     * Addresses on this machine itself (localhost, as Web Serial needs it without https) are of no use to a device.
     */
    @PostMapping(value="/admin/firmware/setup",produces=MediaType.APPLICATION_JSON_VALUE) ResponseEntity<Map<String,Object>> setUp(
            @RequestBody SetupRequest body,HttpServletRequest request) {
        List<String> servers=new ArrayList<>(addresses.announced(request));
        servers.removeIf(url->{String host=java.net.URI.create(url).getHost();
            return host==null||host.equals("localhost")||host.startsWith("127.")||host.equals("[::1]");});
        if(servers.isEmpty())return ResponseEntity.badRequest().body(Map.of("error",
                "The administration is opened on localhost, so the server's LAN address is unknown. Set the internal address under Settings."));
        try{
            var setup=pairing.setUp(body.identifier(),body.name(),body.board(),servers,!body.omitTlsPin());
            log.info("Set up {} over USB; announced {}{}",body.identifier(),setup.pairing().get("servers"),
                    setup.warnings().isEmpty()?"":"; warning: "+String.join("; ",setup.warnings()));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("pairing",setup.pairing(),"warnings",setup.warnings()));
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
        catch(Exception e){log.warn("USB setup of {} failed: {}",body.identifier(),e.toString());return ResponseEntity.internalServerError().body(Map.of("error","Setup failed"));}
    }
    /** Whether the device set up over USB has pulled its first image yet. */
    @GetMapping(value="/admin/firmware/setup/{identifier}",produces=MediaType.APPLICATION_JSON_VALUE) @ResponseBody Map<String,Object> setupState(@PathVariable String identifier){
        ClientRegistry.View view;
        try{view=registry.get(identifier);}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
        return Map.of("status",view.status(),"ip",view.lastPullAt()==null?"":view.ip(),"name",view.name());
    }
}
