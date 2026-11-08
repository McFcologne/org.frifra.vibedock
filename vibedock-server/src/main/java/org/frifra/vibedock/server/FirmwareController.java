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

    private FirmwareReleases releases;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void releases(FirmwareReleases releases){this.releases=releases;}

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
        try{model.addAttribute("boardChips",new tools.jackson.databind.json.JsonMapper().writeValueAsString(chips));}
        catch(tools.jackson.core.JacksonException e){model.addAttribute("boardChips","[]");}
        model.addAttribute("firmware",firmware.info(selected).orElse(null));
        model.addAttribute("uploadIncomplete",firmware.uploadIncomplete(selected));
        model.addAttribute("uploadedAt",firmware.info(selected).filter(Firmware.Info::uploaded)
                .map(i->java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(java.time.Instant.ofEpochSecond(i.uploadedAt()).atZone(zone))).orElse(null));
        model.addAttribute("anyFirmware",!firmware.all().isEmpty());
        if(releases!=null){
            model.addAttribute("sources",releases.status());
            model.addAttribute("sourcesError",releases.configError());
            model.addAttribute("checking",releases.running());
            var candidates=releases.candidates(selected);
            model.addAttribute("candidates",candidates);
            var pin=releases.pinned(selected);
            model.addAttribute("pin",pin.orElse(null));
            model.addAttribute("pinMissing",pin.isPresent()&&candidates.stream().noneMatch(c->c.source().equals(pin.get().source())&&c.version().equals(pin.get().version())));
            var labels=new HashMap<String,String>();
            for(var st:releases.status())labels.put(st.source().id(),st.source().label());
            model.addAttribute("sourceLabels",labels);
            model.addAttribute("checkedAt",releases.status().stream().mapToLong(FirmwareReleases.Status::checkedAt).max().stream().boxed().findFirst()
                    .filter(t->t>0).map(t->java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(java.time.Instant.ofEpochSecond(t).atZone(zone))).orElse(null));
        }
        model.addAttribute("webFlasher",firmware.esptool().isPresent());
        // The devices of this board; web displays have no firmware.
        model.addAttribute("clients",registry.views().stream().filter(c->c.web()==null&&c.board().equals(selected)).toList());
        model.addAttribute("announced",addresses.announced(request));
        model.addAttribute("internalLoopback",addresses.internalIsLoopback(request));
        return "firmware";
    }
    /** Whether a check of the sources runs right now: the page waits for it and reloads when it is over. */
    @GetMapping(value="/admin/firmware/sources/state",produces=MediaType.APPLICATION_JSON_VALUE) @ResponseBody
    Map<String,Object> sourcesState(){return Map.of("running",releases!=null&&releases.running());}
    /** Asks the firmware sources now, in the background; the page follows the check and shows the result by itself. */
    @PostMapping("/admin/firmware/sources/check") String check(@RequestParam(defaultValue="") String board){
        if(releases!=null&&releases.checkInBackground())log.info("Firmware sources are checked on request");
        return "redirect:/admin/firmware"+(Board.find(board).isPresent()?"?board="+board:"");
    }
    /** The list of sources with one change applied; a mistake in the file stops every change. */
    private void changeSources(String board,java.util.function.Function<List<FirmwareSources.Source>,List<FirmwareSources.Source>> change,
                               org.springframework.web.servlet.mvc.support.RedirectAttributes flash){
        try{
            if(releases==null)throw new IllegalArgumentException("Firmware downloads are not available here");
            if(releases.configError()!=null)throw new IllegalArgumentException("Fix firmware-sources.yaml first: "+releases.configError());
            releases.saveSources(change.apply(releases.sources()));
        }catch(IllegalArgumentException e){flash.addFlashAttribute("sourceError",e.getMessage());}
        catch(IOException e){log.warn("firmware-sources.yaml could not be written: {}",e.getMessage());flash.addFlashAttribute("sourceError","The list could not be saved");}
    }
    private static String firmwareUrl(String board){return "redirect:/admin/firmware"+(Board.find(board).isPresent()?"?board="+board:"");}
    /** Adds a source. It is for USB flashing only until the administrator trusts it, and says so on the page. */
    @PostMapping("/admin/firmware/sources/add") String addSource(@RequestParam String repo,@RequestParam(defaultValue="") String tagPrefix,
            @RequestParam(defaultValue="") String label,@RequestParam(defaultValue="") String board,org.springframework.web.servlet.mvc.support.RedirectAttributes flash){
        changeSources(board,list->{
            String r=repo.trim().replaceFirst("^https://github\\.com/","").replaceAll("/+$","");
            var taken=list.stream().map(FirmwareSources.Source::id).toList();
            var source=new FirmwareSources.Source(FirmwareSources.idFor(r.contains("/")?r:"x/"+r,taken),label.isBlank()?r:label.trim(),r,tagPrefix.trim(),false,true,Set.of());
            if(list.stream().anyMatch(x->x.repo().equalsIgnoreCase(source.repo())&&x.tagPrefix().equals(source.tagPrefix())))
                throw new IllegalArgumentException(source.repo()+" with this tag prefix is in the list already");
            if(list.size()>=FirmwareSources.MAX_SOURCES)throw new IllegalArgumentException("At most "+FirmwareSources.MAX_SOURCES+" sources");
            var out=new ArrayList<>(list);out.add(source);
            log.info("Firmware source {} added (USB only)",source.id());
            return out;
        },flash);
        return firmwareUrl(board);
    }
    /** Removes a source (not the official one: that one is switched off instead). The downloaded files are pruned with the next check. */
    @PostMapping("/admin/firmware/sources/{id}/remove") String removeSource(@PathVariable String id,@RequestParam(defaultValue="") String board,
            org.springframework.web.servlet.mvc.support.RedirectAttributes flash){
        changeSources(board,list->{
            if(id.equals(FirmwareSources.OFFICIAL.id()))throw new IllegalArgumentException("The official source cannot be removed, only switched off");
            var out=list.stream().filter(x->!x.id().equals(id)).toList();
            if(out.size()==list.size())throw new IllegalArgumentException("No such source");
            log.info("Firmware source {} removed",id);
            return out;
        },flash);
        return firmwareUrl(board);
    }
    /** Switches a source on or off. */
    @PostMapping("/admin/firmware/sources/{id}/enabled") String enableSource(@PathVariable String id,@RequestParam boolean on,@RequestParam(defaultValue="") String board,
            org.springframework.web.servlet.mvc.support.RedirectAttributes flash){
        changeSources(board,list->replace(list,id,x->new FirmwareSources.Source(x.id(),x.label(),x.repo(),x.tagPrefix(),x.trusted(),on,x.boards())),flash);
        return firmwareUrl(board);
    }
    /** Trusts a source (updates over Wi-Fi) only with the confirmation of the form, or takes the trust back. */
    @PostMapping("/admin/firmware/sources/{id}/trusted") String trustSource(@PathVariable String id,@RequestParam boolean on,@RequestParam(defaultValue="") String confirm,
            @RequestParam(defaultValue="") String board,org.springframework.web.servlet.mvc.support.RedirectAttributes flash){
        changeSources(board,list->{
            if(on&&!"yes".equals(confirm))throw new IllegalArgumentException("Confirm that you trust this source");
            var out=replace(list,id,x->new FirmwareSources.Source(x.id(),x.label(),x.repo(),x.tagPrefix(),on,x.enabled(),x.boards()));
            log.info("Firmware source {} {}",id,on?"trusted: displays may update from it over Wi-Fi":"set to USB only");
            return out;
        },flash);
        return firmwareUrl(board);
    }
    private static List<FirmwareSources.Source> replace(List<FirmwareSources.Source> list,String id,java.util.function.UnaryOperator<FirmwareSources.Source> change){
        if(list.stream().noneMatch(x->x.id().equals(id)))throw new IllegalArgumentException("No such source");
        return list.stream().map(x->x.id().equals(id)?change.apply(x):x).toList();
    }
    /** Holds a downloaded version for a board: "source|version" as the select sends it. */
    @PostMapping("/admin/firmware/pin") String pin(@RequestParam String board,@RequestParam String version) throws IOException {
        var target=Board.find(board).orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST));
        int bar=version.indexOf('|');
        if(releases==null||bar<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try{releases.pin(target,version.substring(0,bar),version.substring(bar+1));}
        catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
        log.info("Firmware {} of source {} held for {}",version.substring(bar+1),version.substring(0,bar),target.id());
        return "redirect:/admin/firmware?board="+target.id();
    }
    /** The newest version applies again. */
    @PostMapping("/admin/firmware/unpin") String unpin(@RequestParam String board) throws IOException {
        var target=Board.find(board).orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST));
        if(releases!=null)releases.unpin(target);
        return "redirect:/admin/firmware?board="+target.id();
    }
    /** The form to ask for firmware of a new board. */
    @GetMapping("/admin/firmware/request") String requestPage(Model model){
        model.addAttribute("chips",new TreeSet<>(Manifest.CHIPS));
        model.addAttribute("webFlasher",firmware.esptool().isPresent());
        return "firmware-request";
    }
    /** The answers of the form; the photo (optional) as base64, reduced here again whatever the browser made of it. */
    record RequestForm(Map<String,String> answers,String photo) {}
    /** The package for the answers: request.json (see FirmwareRequest) and photo.jpg. */
    @PostMapping(value="/admin/firmware/request",consumes=MediaType.APPLICATION_JSON_VALUE) ResponseEntity<?> request(
            @RequestBody RequestForm body,@ModelAttribute("appVersion") String version) throws IOException {
        try{
            byte[] photo=null;
            if(body.photo()!=null&&!body.photo().isBlank()){
                if(body.photo().length()>12_000_000)throw new IllegalArgumentException("The photo is too large");
                byte[] raw;try{raw=Base64.getDecoder().decode(body.photo().trim());}catch(IllegalArgumentException e){throw new IllegalArgumentException("The photo is not readable");}
                photo=PhotoLibrary.reduced(raw,1600);
            }
            var report=FirmwareRequest.report(body.answers(),version,java.time.Instant.now(),photo!=null);
            var id=((Map<?,?>)report.get("board")).get("id");
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).header(HttpHeaders.CACHE_CONTROL,"no-store")
                    .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("firmware-request-"+id+".zip").build().toString())
                    .body(FirmwareRequest.pack(report,photo));
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("error",e.getMessage()));}
    }
    @GetMapping("/admin/firmware/{file:.+\\.bin}") ResponseEntity<byte[]> download(@PathVariable String file) throws IOException {
        var info=firmware.all().stream().filter(i->i.fileName().equals(file)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CACHE_CONTROL,"no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(info.fileName()).build().toString())
                .body(firmware.bytes(info.board()));
    }
    /**
     * An own build of a board, sent as the file's bytes: firmware.bin (the app; on the ESP8266 all there is) or, for
     * ESP32 chips, also flash.bin (the USB image) from .pio/build/<board>/. It replaces the released firmware of the
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
    /** Back to the released firmware of a board. */
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
