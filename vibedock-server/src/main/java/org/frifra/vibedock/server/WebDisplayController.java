package org.frifra.vibedock.server;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

/**
 * Web displays on the public port: a page that shows the image full screen and reloads it, and the image itself as
 * PNG for other programs. The secret in the path is the only access; unknown secrets get 404.
 */
@Controller
class WebDisplayController {
    private final ClientRegistry registry;private final DesignService designs;private final ApplicationInfo info;
    WebDisplayController(ClientRegistry registry,DesignService designs,ApplicationInfo info){
        this.registry=registry;this.designs=designs;this.info=info;
    }
    @GetMapping("/web/{secret}") String page(@PathVariable String secret,Model model){
        var view=registry.webBySecret(secret).orElseThrow(WebDisplayController::notFound);
        model.addAttribute("name",view.name());model.addAttribute("image","/web/"+secret+"/image.png");
        model.addAttribute("reload",view.web().reloadSeconds());model.addAttribute("ratio",(double)view.display().height()/view.display().width());
        return "web-display";
    }
    @GetMapping(value="/web/{secret}/image.png",produces="image/png") ResponseEntity<byte[]> image(@PathVariable String secret,HttpServletRequest request) throws IOException {
        var view=registry.webBySecret(secret).orElseThrow(WebDisplayController::notFound);
        registry.pulled(view.identifier(),request.getRemoteAddr(),true,null);
        byte[] png=designs.render(view.design(),footer(info),view.display(),view.identifier()).png();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).body(png);
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
