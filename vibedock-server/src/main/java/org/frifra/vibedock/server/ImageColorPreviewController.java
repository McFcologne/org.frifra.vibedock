package org.frifra.vibedock.server;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.ui.Model;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;

/** Admin-only live comparison using the same renderer, quality policy and palette encoder as device pulls. */
@Controller
class ImageColorPreviewController {
    private final ThemeSettings themes;private final ClientRegistry clients;private final DesignService designs;
    private final StatusImage renderer;private final Twemoji emoji;
    private final ImageColorSettings settings;private final ImageQuality quality;private final ImagePalette.Cache cache;
    ImageColorPreviewController(ThemeSettings themes,ClientRegistry clients,DesignService designs,ImageColorSettings settings,ImageQuality quality,ImagePalette.Cache cache,StatusImage renderer,Twemoji emoji){
        this.renderer=renderer;this.emoji=emoji;this.themes=themes;this.clients=clients;this.designs=designs;this.settings=settings;this.quality=quality;this.cache=cache;
    }
    @GetMapping("/admin/color-preview") String page(Model model){model.addAttribute("displayTypes",DisplayType.INVENTORY);return "color-preview";}
    record Card(String id,String name,int width,int height,int base,int budget,int used,int bits,int pngBytes,int originalPngBytes,int payloadBytes,int originalPayload,double changed,String image) {}
    record Result(List<Card> cards,boolean calibrating) {}
    @GetMapping("/admin/color-preview/quality") @ResponseBody Map<String,Boolean> calibration(){return Map.of("calibrating",quality.calibrating());}
    @GetMapping("/admin/color-preview/data") @ResponseBody
    Result data(@RequestParam(defaultValue="vibedock-m1") String display,@RequestParam(defaultValue="auto") String mode,@RequestParam(defaultValue="true") boolean fallback,@RequestParam(defaultValue="live") String source,@RequestParam(defaultValue="0") int state)throws Exception {
        boolean web=display.equals("web");var panel=web?DisplayType.M1:DisplayType.INVENTORY.stream().filter(p->p.id().equals(display)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST));
        if(!Set.of("live","reference").contains(source)||state<0||state>2)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if(!Set.of("auto","original","lossless","32","64","256").contains(mode))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var results=new ArrayList<Card>();var snapshot=themes.snapshot();int scale=web?2:1,w=panel.width()*scale,h=panel.height()*scale;
        for(var theme:snapshot.catalog().all()){
            var root=clients.defaultDesign();
            var design=new Design(theme,root.mood(),root.weather(),root.layout(),null,new Design.Icon("theme-default",null,null));
            var rendered=source.equals("live")?designs.render(design,"192.168.1.50",panel,scale):renderer.render(ImageQuality.reference(state),"192.168.1.50",
                    new StatusImage.Look(ThemeIcons.image(snapshot.item(theme.id()).orElseThrow().contents(),snapshot.item(theme.id()).orElseThrow().contents().manifest().defaultIcon(),44*scale).orElse(null),theme,emoji.image("⛅",24*scale).orElse(null),"13°",Layout.DEFAULT),panel,scale);byte[] png=rendered.png();
            var original=ImageIO.read(new ByteArrayInputStream(png));var raw=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);raw.setRGB(0,0,w,h,original.getRGB(0,0,w,h,null,0,w),0,w);var rawPng=new ByteArrayOutputStream();ImageIO.write(raw,"png",rawPng);
            int base=settings.get().colors(panel.width(),panel.height()),budget=0,used=0,bits=0,payload=web?png.length:rendered.rgb565().length;double changed=0;
            if(!web&&!mode.equals("original")){
                int requested=mode.equals("auto")?base:mode.equals("lossless")?0:Integer.parseInt(mode);
                var policy=mode.equals("auto")&&fallback&&requested!=0?quality.policy(theme,requested):new ImageQuality.Policy(requested,50);
                var selected=cache.select(rendered.rgb565(),w,h,requested,rendered.accents(),rendered.bars(),mode.equals("auto")&&fallback,policy.target(),policy.preferred(),rendered.primary());
                budget=selected.colors();changed=selected.changedPercent();
                if(selected.image()!=null){
                    var indexed=selected.image();used=indexed.colorsUsed();bits=indexed.bits();payload=Math.min(payload,indexed.body().length);
                    var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);var p=indexed.pixels();
                    for(int y=0;y<h;y++)for(int x=0;x<w;x++){int at=2*(y*w+x),rgb=(p[at]&255)<<8|(p[at+1]&255);image.setRGB(x,y,((rgb>>11)*255/31<<16)|(((rgb>>5)&63)*255/63<<8)|((rgb&31)*255/31));}
                    png=LosslessPng.encode(image);
                }
            }
            if(mode.equals("original"))png=rawPng.toByteArray();
            if(web&&original.getColorModel() instanceof java.awt.image.IndexColorModel indexed){used=indexed.getMapSize();bits=indexed.getPixelSize();}
            if(web)payload=png.length;
            results.add(new Card(theme.id(),theme.label,w,h,base,budget,used,bits,png.length,rawPng.size(),payload,web?rawPng.size():rendered.rgb565().length,changed,"data:image/png;base64,"+Base64.getEncoder().encodeToString(png)));
        }
        return new Result(results,quality.calibrating());
    }
}
