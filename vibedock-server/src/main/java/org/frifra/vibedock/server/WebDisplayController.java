package org.frifra.vibedock.server;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.HexFormat;

/**
 * Web displays on the public port: a page that shows the image full screen and reloads it, and the image itself as
 * PNG for other programs. The secret in the path is the only access; unknown secrets get 404. The page asks for the
 * image in the size of its window and the screen's pixel ratio, so it is drawn sharp (see {@link DisplayType#fitting});
 * without a size the image has the display type's pixels.
 */
@Controller
class WebDisplayController {
    private final ClientRegistry registry;private final DesignService designs;private final ApplicationInfo info;private final AdminEvents events;
    WebDisplayController(ClientRegistry registry,DesignService designs,ApplicationInfo info,AdminEvents events){
        this.registry=registry;this.designs=designs;this.info=info;this.events=events;
    }
    @GetMapping("/web/{secret}") String page(@PathVariable String secret,Model model){
        var view=registry.webBySecret(secret).orElseThrow(WebDisplayController::notFound);
        model.addAttribute("name",view.name());model.addAttribute("image","/web/"+secret+"/image.png");model.addAttribute("icon","/web/"+secret+"/icon.png");
        model.addAttribute("background",background(view));
        model.addAttribute("state","/web/"+secret+"/state");model.addAttribute("swipe","/web/"+secret+"/swipe");
        model.addAttribute("reload",view.web().reloadSeconds());model.addAttribute("ratio",(double)view.display().height()/view.display().width());
        return "web-display";
    }
    @GetMapping(value="/web/{secret}/image.png",produces="image/png") ResponseEntity<byte[]> image(@PathVariable String secret,
            @RequestParam(required=false) Integer w,@RequestParam(required=false) Integer h,@RequestParam(required=false) Double r,
            HttpServletRequest request) throws IOException {
        var view=registry.webBySecret(secret).orElseThrow(WebDisplayController::notFound);
        registry.pulled(view.identifier(),request.getRemoteAddr(),true,null);
        var sized=w!=null&&h!=null?view.display().fitting(w,h,r==null?1:r):new DisplayType.Sized(view.display(),1);
        byte[] png=designs.render(view.design(),footer(info),sized.type(),view.identifier(),sized.scale()).png();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).body(png);
    }
    /**
     * What the image shows now, as a short tag: the page asks every few seconds and loads a new image only when it
     * changed, so changes (a new design, new usage data, the next photo) appear within seconds.
     */
    @GetMapping(value="/web/{secret}/state",produces="application/json") ResponseEntity<java.util.Map<String,String>> state(@PathVariable String secret) throws IOException {
        var view=registry.webBySecret(secret).orElseThrow(WebDisplayController::notFound);
        byte[] png=designs.render(view.design(),footer(info),view.display(),view.identifier()).png();
        String tag;try{tag=HexFormat.of().formatHex(java.util.Arrays.copyOf(java.security.MessageDigest.getInstance("SHA-256").digest(png),12));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("tag",tag));
    }
    /**
     * A swipe on the page: the next or previous theme, in a slideshow the next or previous photo (like touch
     * displays); toggle (a double tap or double click): the slideshow on or off.
     */
    @PostMapping("/web/{secret}/swipe") ResponseEntity<Void> swipe(@PathVariable String secret,@RequestParam String direction){
        var view=registry.webBySecret(secret).orElseThrow(WebDisplayController::notFound);
        if("toggle".equals(direction)){DeviceApiController.togglePhotos(registry,designs,events,view.identifier());return ResponseEntity.noContent().build();}
        int step=switch(direction){case "next"->1;case "previous"->-1;default->0;};
        if(step==0)return ResponseEntity.badRequest().build();
        DeviceApiController.swipe(registry,designs,events,view.identifier(),step);
        return ResponseEntity.noContent().build();
    }
    /** The home screen icon of the page (iOS web clips, Android shortcuts): the display's icon, as on the devices' own pages. */
    @GetMapping(value="/web/{secret}/icon.png",produces="image/png") ResponseEntity<byte[]> icon(@PathVariable String secret) throws IOException {
        registry.webBySecret(secret).orElseThrow(WebDisplayController::notFound);
        byte[] png;try(var in=getClass().getResourceAsStream("/web-display-icon.png")){if(in==null)throw notFound();png=in.readAllBytes();}
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePrivate()).body(png);
    }
    /** What a web display shows where devices show their IP. */
    static String footer(ApplicationInfo info){String v=info.getVersion();return "VibeDock server "+(v.matches("[0-9].*")?"v"+v:v);}
    /** The server address of a web display's links: internal, or external when chosen and set. */
    static String base(ClientRegistry.View view,ServerAddresses addresses,HttpServletRequest request){
        return view.web().external()&&!addresses.external().isBlank()?addresses.external().replaceFirst("/$",""):addresses.internal(request).replaceFirst("/$","");
    }
    private String background(ClientRegistry.View view){return "#%06x".formatted(designs.effectiveScheme(view.design().scheme()).background);}
    private static org.springframework.web.server.ResponseStatusException notFound(){
        return new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
