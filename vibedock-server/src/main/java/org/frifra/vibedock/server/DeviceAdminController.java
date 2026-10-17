package org.frifra.vibedock.server;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.io.IOException;
import java.util.List;

/** Pairing, removal and previews of devices, and the settings announced to them. */
@Controller
class DeviceAdminController {
    private static final Logger log=LoggerFactory.getLogger(DeviceAdminController.class);
    private final ClientRegistry registry;private final ClientPairing pairing;private final DesignService designs;private final ServerAddresses addresses;private final DevicePoke poke;
    private final Firmware firmware;private final ApplicationInfo info;private final DeviceLogs logs;private final AdminEvents events;
    DeviceAdminController(ClientRegistry registry,ClientPairing pairing,DesignService designs,ServerAddresses addresses,DevicePoke poke,Firmware firmware,ApplicationInfo info,DeviceLogs logs,AdminEvents events){
        this.info=info;this.logs=logs;this.events=events;
        this.registry=registry;this.pairing=pairing;this.designs=designs;this.addresses=addresses;this.poke=poke;this.firmware=firmware;
    }
    @PostMapping("/admin/clients/invite") String invite(@RequestParam String ip,@RequestParam(defaultValue="80") int port,
            @RequestParam String code,@RequestParam(defaultValue="") String name,@RequestParam(defaultValue="") String board,HttpServletRequest request,RedirectAttributes flash) {
        try{
            var invitation=pairing.invite(ip,port,code,name,board,addresses.announced(request));var view=invitation.view();
            log.info("Paired {} ({}) at {}:{}; announced {}{}",view.name(),view.identifier(),ip,port,addresses.announced(request),
                    invitation.warnings().isEmpty()?"":"; warning: "+String.join("; ",invitation.warnings()));
            flash.addFlashAttribute("clientMessage","Paired "+view.name()+" ("+view.identifier()+").");
            if(!invitation.warnings().isEmpty())flash.addFlashAttribute("clientWarning",String.join("; ",invitation.warnings())+".");
        }catch(InterruptedException e){Thread.currentThread().interrupt();flash.addFlashAttribute("pairingError","Pairing interrupted");}
        catch(IllegalArgumentException e){log.warn("Pairing with {}:{} failed: {}",ip,port,e.getMessage());flash.addFlashAttribute("pairingError",e.getMessage());}
        catch(Exception e){log.warn("Pairing with {}:{} failed: {}",ip,port,e.toString());flash.addFlashAttribute("pairingError","Pairing failed");}
        return "redirect:/admin/devices";
    }
    @PostMapping("/admin/clients/settings") String settings(@RequestParam(defaultValue="") String internalUrl,
            @RequestParam(defaultValue="") String externalUrl,@RequestParam(defaultValue="30") int pollSeconds,RedirectAttributes flash) throws IOException {
        try{registry.configure(internalUrl,externalUrl,pollSeconds);flash.addFlashAttribute("settingsMessage","Saved. Paired devices pick up the poll interval with their next pull; new addresses apply when a device is invited again.");}
        catch(IllegalArgumentException e){flash.addFlashAttribute("settingsError",e.getMessage());}
        return "redirect:/admin/server";
    }
    @PostMapping("/admin/clients/{identifier}/remove") String remove(@PathVariable String identifier,RedirectAttributes flash) throws IOException {
        try{registry.remove(identifier);logs.deleteAll(identifier);}
        catch(IllegalArgumentException e){flash.addFlashAttribute("deviceRemovalError","The device was already removed.");}
        return "redirect:/admin/devices";
    }

    // ---- troubleshooting: logs read from a display, over USB (any board) or the network ----
    /** A paired display on the troubleshooting page: its saved logs and whether it can send one over the network. */
    record LogTarget(ClientRegistry.View device,int logs,boolean network) {}
    @GetMapping("/admin/troubleshooting") String troubleshooting(org.springframework.ui.Model model) throws IOException {
        var targets=new java.util.ArrayList<LogTarget>();
        for(var c:registry.views())if(c.web()==null)targets.add(new LogTarget(c,logs.count(c.identifier()),sendsLog(c.firmware())));
        model.addAttribute("targets",targets);model.addAttribute("keep",DeviceLogs.KEEP);model.addAttribute("displays",registry.views());
        return "troubleshooting";
    }
    record LogUpload(String firmware,String board,List<String> lines) {}
    @PostMapping(value="/admin/clients/{identifier}/logs",produces="application/json") @ResponseBody
    org.springframework.http.ResponseEntity<java.util.Map<String,Object>> saveLog(@PathVariable String identifier,@RequestBody LogUpload upload) throws IOException {
        try{
            var entry=logs.save(identifier,"usb",upload.firmware(),upload.board(),upload.lines());
            log.info("Saved the USB log of {} ({} lines)",identifier,entry.lines());events.logSaved(identifier);
            return org.springframework.http.ResponseEntity.ok(java.util.Map.of("name",entry.name(),"count",logs.count(identifier),"keep",DeviceLogs.KEEP));
        }catch(IllegalArgumentException e){return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("error",e.getMessage()));}
    }
    /** Firmware that sends its log over the network when asked; older firmware only over USB. */
    static boolean sendsLog(String firmware){return FirmwareVersion.knownNewer(firmware,"0.1.1");}
    /** Asks the display to send its log: with its next pull, right away when the server may ask it to pull now. */
    @PostMapping("/admin/clients/{identifier}/logs/request") String requestLog(@PathVariable String identifier,RedirectAttributes flash) {
        String page="redirect:/admin/clients/"+java.net.URLEncoder.encode(identifier,java.nio.charset.StandardCharsets.UTF_8)+"/logs";
        try{
            var view=registry.get(identifier);
            if(view.web()!=null||!sendsLog(view.firmware())){flash.addFlashAttribute("logMessage","This display can send its log only over USB. Update its firmware to read it over the network.");return page;}
            logs.request(identifier);poke.pullNow(List.of(registry.address(identifier)));
            flash.addFlashAttribute("logMessage","Requested. The log appears here by itself: within seconds, or with the display's next pull.");
        }catch(IllegalArgumentException e){flash.addFlashAttribute("logMessage","The device was already removed.");}
        return page;
    }
    @GetMapping("/admin/clients/{identifier}/logs") String logs(@PathVariable String identifier,org.springframework.ui.Model model) throws IOException {
        java.util.List<java.util.Map<String,Object>> entries=new java.util.ArrayList<>();
        try{
            for(var entry:logs.list(identifier))
                entries.add(java.util.Map.of("entry",entry,"text",String.join("\n",logs.read(identifier,entry.name()).map(DeviceLogs.Log::lines).orElse(List.of()))));
        }catch(IllegalArgumentException e){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);}
        String name=identifier;
        boolean paired=false,network=false;
        try{var view=registry.get(identifier);name=view.name();paired=view.web()==null;network=paired&&sendsLog(view.firmware());}
        catch(IllegalArgumentException notPaired){ /* logs of a display that is not paired */ }
        model.addAttribute("identifier",identifier);model.addAttribute("deviceName",name);
        model.addAttribute("paired",paired);model.addAttribute("networkLog",network);
        model.addAttribute("logRequested",logs.pendingRequest(identifier).isPresent());
        model.addAttribute("entries",entries);model.addAttribute("keep",DeviceLogs.KEEP);
        return "device-logs";
    }
    @GetMapping(value="/admin/clients/{identifier}/logs/{name}.txt",produces="text/plain;charset=UTF-8") @ResponseBody
    org.springframework.http.ResponseEntity<String> downloadLog(@PathVariable String identifier,@PathVariable String name) throws IOException {
        java.util.Optional<DeviceLogs.Log> found;
        try{found=logs.read(identifier,name+".json");}catch(IllegalArgumentException e){found=java.util.Optional.empty();}
        if(found.isEmpty())return org.springframework.http.ResponseEntity.notFound().build();
        var entry=found.get().entry();
        String text="VibeDock device log of "+identifier+"\nRead: "+java.time.Instant.ofEpochMilli(entry.savedAt())+" over "+("usb".equals(entry.source())?"USB":"the network")
                +"\nFirmware: "+entry.firmware()+" · board: "+entry.board()+"\n\n"+String.join("\n",found.get().lines())+"\n";
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition","attachment; filename=\"vibedock-log-"+identifier.replace(':','-')+"-"+name+".txt\"").body(text);
    }
    @PostMapping("/admin/clients/{identifier}/logs/{name}/delete") String deleteLog(@PathVariable String identifier,@PathVariable String name) throws IOException {
        try{logs.delete(identifier,name+".json");}catch(IllegalArgumentException ignored){ /* nothing to delete */ }
        return "redirect:/admin/clients/"+java.net.URLEncoder.encode(identifier,java.nio.charset.StandardCharsets.UTF_8)+"/logs";
    }
    /** Redraws the device screen now: the next pull ignores the ETag, and the device is asked to pull immediately. */
    @PostMapping("/admin/clients/{identifier}/refresh") String refresh(@PathVariable String identifier,RedirectAttributes flash) {
        try{
            registry.forceRefresh(identifier);var view=registry.get(identifier);poke.pullNow(List.of(registry.address(identifier)));
            flash.addFlashAttribute("clientMessage","Asked "+view.name()+" to redraw its screen. If it is not reachable directly, it redraws at its next poll.");
            return "redirect:/admin/devices#device-"+view.identifier();
        }catch(IllegalArgumentException e){flash.addFlashAttribute("deviceRemovalError","The device was already removed.");}
        return "redirect:/admin/devices";
    }
    /**
     * Update over Wi-Fi: the device learns it with its next pull (asked to pull right away), downloads the signed
     * firmware and installs it. {identifier} "all" updates every device that can and runs another version.
     */
    @PostMapping("/admin/clients/{identifier}/update") String update(@PathVariable String identifier,@RequestParam(defaultValue="") String back,RedirectAttributes flash) {
        var versions=firmware.versions();  // per board: an uploaded build has its own version
        if(versions.isEmpty()){flash.addFlashAttribute("deviceRemovalError","No firmware in this installation.");return back(back,identifier,"redirect:/admin/devices");}
        try{
            List<ClientRegistry.View> targets="all".equals(identifier)?registry.views():List.of(registry.get(identifier));
            List<ClientRegistry.Address> started=new java.util.ArrayList<>();List<String> names=new java.util.ArrayList<>();
            for(var view:targets){
                String version=versions.get(view.board().id());
                if(version!=null&&registry.requestUpdate(view.identifier(),version)){started.add(registry.address(view.identifier()));names.add(view.name()+" ("+version+")");}
            }
            poke.pullNow(started);
            log.info("Update over Wi-Fi requested for {}",names);
            flash.addFlashAttribute(started.isEmpty()?"deviceRemovalError":"clientMessage",started.isEmpty()
                    ?"No device to update over Wi-Fi: a device needs firmware with updates over Wi-Fi and a pairing that gave it this server's signing key."
                    :"Updating "+String.join(", ",names)+". Each display shows the progress and restarts; this takes about a minute.");
            return back(back,identifier,"redirect:/admin/devices"+("all".equals(identifier)?"":"#device-"+identifier));
        }catch(IllegalArgumentException e){flash.addFlashAttribute("deviceRemovalError","The device was already removed.");}
        return "redirect:/admin/devices";
    }
    /** The page an update was started from: the overview, the firmware page of the device's board, or the device list. */
    private String back(String back,String identifier,String devices){
        if("overview".equals(back))return "redirect:/admin";
        if("firmware".equals(back)){
            try{return "redirect:/admin/firmware?board="+registry.get(identifier).board().id();}catch(IllegalArgumentException e){return "redirect:/admin/firmware";}
        }
        return devices;
    }
    /** Adds a web display: a page and an image link with a new secret, shown on its card. */
    @PostMapping("/admin/clients/web") String addWeb(@RequestParam(defaultValue="") String name,@RequestParam(defaultValue="") String display,
            @RequestParam(defaultValue="30") int reloadSeconds,RedirectAttributes flash) throws IOException {
        try{var view=registry.addWeb(name,display,reloadSeconds);
            flash.addFlashAttribute("clientMessage","Added web display "+view.name()+". Open its page or use the image link below.");
            return "redirect:/admin/devices#device-"+view.identifier();}
        catch(IllegalArgumentException e){flash.addFlashAttribute("webError",e.getMessage().equals("Invalid client")?"A name of 1 to 40 characters":e.getMessage());}
        return "redirect:/admin/devices";
    }
    /** New links for a web display; the old links stop working. */
    @PostMapping("/admin/clients/{identifier}/web/renew") String renewWeb(@PathVariable String identifier,RedirectAttributes flash) throws IOException {
        try{registry.renewWeb(identifier);flash.addFlashAttribute("clientMessage","New links for "+registry.get(identifier).name()+"; the old links no longer work.");
            return "redirect:/admin/devices#device-"+identifier;}
        catch(IllegalArgumentException e){flash.addFlashAttribute("deviceRemovalError","The web display was already removed.");}
        return "redirect:/admin/devices";
    }
    /** Internal or external server address for the links of a web display. */
    @PostMapping("/admin/clients/{identifier}/web/external") String webExternal(@PathVariable String identifier,
            @RequestParam(defaultValue="false") boolean external,RedirectAttributes flash) throws IOException {
        try{registry.setWebExternal(identifier,external);return "redirect:/admin/devices#device-"+identifier;}
        catch(IllegalArgumentException e){flash.addFlashAttribute("deviceRemovalError","The web display was already removed.");}
        return "redirect:/admin/devices";
    }
    /** The same for the switch on the card: answers the new links, so the page swaps them in place. */
    @PostMapping(value="/admin/clients/{identifier}/web/external",produces="application/json") @ResponseBody org.springframework.http.ResponseEntity<java.util.Map<String,String>> webExternalLinks(
            @PathVariable String identifier,@RequestParam(defaultValue="false") boolean external,HttpServletRequest request) throws IOException {
        try{registry.setWebExternal(identifier,external);var view=registry.get(identifier);
            String page=WebDisplayController.base(view,addresses,request)+"/web/"+view.web().secret();
            return org.springframework.http.ResponseEntity.ok(java.util.Map.of("page",page,"image",page+"/image.png"));}
        catch(IllegalArgumentException e){return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("error","The web display was already removed."));}
    }
    /** The switch on the card: turns the device's picture by 180 degrees; the device is asked to pull right away. */
    @PostMapping(value="/admin/clients/{identifier}/flip",produces="application/json") @ResponseBody org.springframework.http.ResponseEntity<java.util.Map<String,Object>> flip(
            @PathVariable String identifier,@RequestParam(defaultValue="false") boolean flipped) throws IOException {
        try{registry.setFlipped(identifier,flipped);
            try{poke.pullNow(List.of(registry.address(identifier)));}catch(IllegalArgumentException ignored){ /* learns it at its next poll */ }
            return org.springframework.http.ResponseEntity.ok(java.util.Map.of("flipped",registry.flipped(identifier)));}
        catch(IllegalArgumentException e){return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("error","The device was already removed."));}
    }
    record Order(List<String> ids) {}
    /** Order of the device cards after dragging one by its grip. */
    @PostMapping(value="/admin/clients/order",produces="application/json") @ResponseBody java.util.Map<String,Object> order(@RequestBody Order order) throws IOException {
        registry.reorder(order.ids());return java.util.Map.of("ids",registry.views().stream().map(ClientRegistry.View::identifier).toList());
    }
    /** Renames a device (pen next to its name); answers the stored name. */
    @PostMapping(value="/admin/clients/{identifier}/rename",produces="application/json") @ResponseBody org.springframework.http.ResponseEntity<java.util.Map<String,String>> rename(
            @PathVariable String identifier,@RequestParam String name) throws IOException {
        try{registry.rename(identifier,name);return org.springframework.http.ResponseEntity.ok(java.util.Map.of("name",registry.get(identifier).name()));}
        catch(IllegalArgumentException e){return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("error","A name of 1 to 40 characters"));}
    }
    /** Firmware, update and connection state of every device, for the device list to follow a running update. */
    @GetMapping(value="/admin/clients/state.json",produces="application/json") @ResponseBody java.util.Map<String,java.util.Map<String,Object>> state(){
        java.util.Map<String,java.util.Map<String,Object>> out=new java.util.LinkedHashMap<>();
        for(var v:registry.views())out.put(v.identifier(),java.util.Map.of("firmware",v.firmware()==null?"":v.firmware(),"updating",v.updating(),
                "updateError",v.updateError()==null?"":v.updateError(),"status",v.status()));
        return out;
    }
    /**
     * Kept briefly by the browser (private, 10 s, then shown while it fetches the next): reloading the page shows the
     * previews at once instead of empty while they load (the default no-store made them flicker).
     */
    @GetMapping(value="/admin/clients/{identifier}/preview.png",produces="image/png") @ResponseBody org.springframework.http.ResponseEntity<byte[]> preview(@PathVariable String identifier) throws IOException {
        var client=registry.get(identifier);
        String footer=client.web()!=null?WebDisplayController.footer(info):client.ip();
        byte[] png=designs.render(DesignService.forDevice(client.design(),client.serverFooter()),footer,client.display(),identifier).png();
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.IMAGE_PNG)
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofSeconds(10)).cachePrivate().staleWhileRevalidate(java.time.Duration.ofMinutes(5)))
                .body(png);
    }
    /** The default design as a device would show it; the IP is a placeholder. */
    @GetMapping(value="/admin/preview.png",produces="image/png") @ResponseBody byte[] statusPreview() throws IOException {
        return designs.render(registry.defaultDesign(),"Preview",DisplayType.M1,"default").png();
    }
}
