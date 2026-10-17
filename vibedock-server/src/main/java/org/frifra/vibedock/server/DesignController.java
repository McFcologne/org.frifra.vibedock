package org.frifra.vibedock.server;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

/** The display design editor: one editor for the default design and every device override. */
@Controller
class DesignController {
    /** serverFooter: the device's firmware can show the weather/mood band anywhere; older firmware keeps it at the bottom. */
    record Target(String identifier,String name,String ip,boolean overridden,Design design,boolean serverFooter,String display,String previewCase) {}
    record Catalog(Design defaults,List<Target> devices,List<SourceOption> providers,List<SchemeOption> schemes,List<DisplayOption> displays) {}
    /** A display type: bandRoom (px for usage bands) and halfWidth tell the editor what the type shows of a design. */
    record DisplayOption(String id,String label,int width,int height,int bandRoom,boolean halfWidth) {}
    /** A data source the usage bands can show: id as stored in layouts, label as shown on the display. */
    record SourceOption(String id,String label) {}
    record SchemeOption(String id,String label) {}
    private final ClientRegistry registry;private final DesignService designs;private final UsageService usage;private final DevicePoke poke;
    private final ApplicationInfo info;
    DesignController(ClientRegistry registry,DesignService designs,UsageService usage,DevicePoke poke,ApplicationInfo info){
        this.info=info;
        this.registry=registry;this.designs=designs;this.usage=usage;this.poke=poke;
    }

    @GetMapping("/admin/design") String editor(){return "design";}
    @GetMapping("/admin/designs") @ResponseBody Catalog catalog(){
        return new Catalog(registry.defaultDesign(),registry.views().stream()
                .map(v->new Target(v.identifier(),v.name(),v.ip(),v.overridden(),v.design(),v.serverFooter(),v.display().id(),v.board().previewCase())).toList(),
                usage.snapshot().providers().keySet().stream().map(id->new SourceOption(id,usage.snapshot().label(id))).toList(),
                Arrays.stream(Scheme.values()).map(s->new SchemeOption(s.id(),s.label)).toList(),
                DisplayType.INVENTORY.stream().map(t->new DisplayOption(t.id(),t.label(),t.width(),t.height(),t.bandRoom(),t.halfWidth())).toList());
    }
    @PostMapping("/admin/designs/default") @ResponseBody Design saveDefault(@RequestBody DesignService.Input input) throws IOException {
        if(input.inherit())throw new IllegalArgumentException("The default design cannot inherit");
        registry.configureDesign(designs.resolve(input,registry.defaultDesign()));poke.pullNow(registry.followingDefault());
        return registry.defaultDesign();
    }
    @PostMapping("/admin/designs/{identifier}") @ResponseBody Design saveDevice(@PathVariable String identifier,@RequestBody DesignService.Input input) throws IOException {
        var previous=registry.designFor(identifier);var next=input.inherit()?null:designs.resolve(input,previous);
        if(input.display()!=null)registry.setDisplay(identifier,input.display());
        registry.setDesign(identifier,next);
        poke.pullNow(List.of(registry.address(identifier)));return registry.designFor(identifier);
    }
    /** Same renderer, snapshot and effective settings as the device pull; nothing is saved. */
    @PostMapping(value="/admin/design-preview.png",produces="image/png") @ResponseBody byte[] preview(
            @RequestParam(defaultValue="default") String target,@RequestBody DesignService.Input input) throws IOException {
        boolean defaults=target.equals("default");var previous=defaults?registry.defaultDesign():registry.designFor(target);
        var design=input.inherit()?registry.defaultDesign():designs.resolve(input,previous);
        if(defaults)return designs.render(design,"Preview").png();
        var device=registry.get(target);var display=input.display()!=null?DisplayType.of(input.display()):device.display();
        String footer=device.web()!=null?WebDisplayController.footer(info):device.ip();
        return designs.render(DesignService.forDevice(design,device.serverFooter()),footer,display).png();
    }
}
