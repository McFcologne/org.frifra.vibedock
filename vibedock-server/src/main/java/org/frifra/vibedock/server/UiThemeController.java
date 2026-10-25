package org.frifra.vibedock.server;

import java.io.IOException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/** The administration appearance is shared with paired devices through their image responses. */
@Controller
class UiThemeController {
    private final ThemeSettings settings;
    private final ClientRegistry registry;private final DevicePoke poke;
    UiThemeController(ThemeSettings settings,ClientRegistry registry,DevicePoke poke){this.settings=settings;this.registry=registry;this.poke=poke;}
    @PostMapping("/admin/ui-theme") @ResponseBody
    void save(@RequestParam boolean dark) throws IOException {settings.setDark(dark);poke.appearance(registry.all(),dark);}

    @ControllerAdvice
    static class AppearanceModel {
        private final ThemeSettings settings;
        AppearanceModel(ThemeSettings settings){this.settings=settings;}
        @ModelAttribute("uiDark") boolean dark(){return settings.dark();}
    }
}
