package org.frifra.vibedock.server;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

/**
 * Web displays on the public port: a page that shows the image full screen and reloads it, and the image itself as
 * PNG for other programs. The secret in the path is the only access; unknown secrets get 404. The page asks for the
 * image in the size of its window and the screen's pixel ratio, so it is drawn sharp (see {@link DisplayType#fitting});
 * without a size the image has the display type's pixels.
 */
@Controller
class WebDisplayController {
    private final ClientRegistry registry;private final DesignService designs;private final ApplicationInfo info;
    WebDisplayController(ClientRegistry registry,DesignService designs,ApplicationInfo info){
        this.registry=registry;this.designs=designs;this.info=info;
    }
    @GetMapping("/web/{secret}") String page(@PathVariable String secret,Model model){
        var view=registry.webBySecret(secret).orElseThrow(WebDisplayController::notFound);
        model.addAttribute("name",view.name());model.addAttribute("image","/web/"+secret+"/image.png");model.addAttribute("icon","/web/"+secret+"/icon.png");
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
    /** The home screen icon of the page (iOS web clips, Android shortcuts): the VibeDock icon. */
    @GetMapping(value="/web/{secret}/icon.png",produces="image/png") ResponseEntity<byte[]> icon(@PathVariable String secret) throws IOException {
        registry.webBySecret(secret).orElseThrow(WebDisplayController::notFound);
        byte[] png;try(var in=getClass().getResourceAsStream("/static/apple-touch-icon.png")){if(in==null)throw notFound();png=in.readAllBytes();}
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePrivate()).body(png);
    }
    /** What a web display shows where devices show their IP. */
    static String footer(ApplicationInfo info){String v=info.getVersion();return "VibeDock server "+(v.matches("[0-9].*")?"v"+v:v);}
    /** The server address of a web display's links: internal, or external when chosen and set. */
    static String base(ClientRegistry.View view,ServerAddresses addresses,HttpServletRequest request){
        return view.web().external()&&!addresses.external().isBlank()?addresses.external().replaceFirst("/$",""):addresses.internal(request).replaceFirst("/$","");
    }
    private static org.springframework.web.server.ResponseStatusException notFound(){
        return new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
