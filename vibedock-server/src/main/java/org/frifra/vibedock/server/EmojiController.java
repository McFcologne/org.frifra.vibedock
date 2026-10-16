package org.frifra.vibedock.server;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.*;

/** Emoji picker data and the Twemoji images it shows. */
@Controller
class EmojiController {
    /** Quick picks of the picker; any other installed Twemoji can be searched. */
    static final List<String> QUICK_PICKS=List.of("🚀","🤖","🧠","💡","🔥","⚡","✨","⭐","🎯","🏆","💻","🖥️","⌨️","🛠️","🧪","📈",
            "☕","🍕","🍺","🎧","🎮","🎸","🐱","🐶","🦊","🐼","🐧","🦄","🐙","🌈","🌙","☀️","🍀","🌵","❤️","💜","💚","💙","👍","😎",
            "🙂","😄","🤓","🥳","😴","🤯","👻","💀");
    private final Twemoji twemoji;
    EmojiController(Twemoji twemoji){this.twemoji=twemoji;}
    @GetMapping(value="/admin/emoji.json",produces="application/json") @ResponseBody Map<String,Object> catalog(){
        var quick=QUICK_PICKS.stream().map(twemoji::entry).flatMap(Optional::stream).toList();
        return Map.of("quick",quick,"all",twemoji.catalog());
    }
    @GetMapping(value="/admin/twemoji/{file}.png",produces="image/png") ResponseEntity<byte[]> image(@PathVariable String file){
        return twemoji.png(file).map(png->ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate()).body(png))
                .orElseGet(()->ResponseEntity.notFound().build());
    }
}
