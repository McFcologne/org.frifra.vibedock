package org.frifra.vibedock.server;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Predefined colour schemes of the status image; quota bars keep their colour scale from green to red. The device footer follows the background. */
public enum Scheme {
    MIDNIGHT("Midnight",0x000000,0x80d8ff,0xffffff,0x8a97a8,0x2a3546,0xffb84d),
    OCEAN("Ocean",0x000000,0x5ac8fa,0xe6f6ff,0x6f9bb3,0x163447,0xffb84d),
    FOREST("Forest",0x000000,0x9be38a,0xf0fff0,0x7f9f86,0x1f3a28,0xffb84d),
    SUNSET("Sunset",0x000000,0xffb36b,0xfff4e8,0xb08f7a,0x45291f,0xffb84d),
    LAVENDER("Lavender",0x000000,0xc9a7ff,0xf6f0ff,0x9a8bb3,0x2f2547,0xffb84d),
    MONO("Mono",0x000000,0xffffff,0xffffff,0xb0b0b0,0x3a3a3a,0xffb84d),
    PAPER("Paper (light)",0xf4f4f0,0x1f5fbf,0x1a1a1a,0x6b6b6b,0xd8dbe0,0xc25e00),
    SKY("Sky (light)",0xe6f1fc,0x0a5fb4,0x0f1e33,0x56708c,0xc6d9ee,0xc25e00),
    SAND("Sand (light)",0xf6eedf,0xa8471a,0x2b1d12,0x84705e,0xe3d3bb,0xb34700),
    TERMINAL_GREEN("Classic green",0x040b05,0x6fdc8c,0x4fd873,0x2a8344,0x0d2a14,0xb5e8a0,Style.TERMINAL),
    TERMINAL_AMBER("Classic amber",0x0d0800,0xffc23d,0xffb000,0x9a6a00,0x2e1f00,0xffd98a,Style.TERMINAL),
    CLASSIC_MAC("Classic Mac",0xffffff,0x000000,0x000000,0x000000,0xffffff,0x000000,Style.MAC),
    CLASSIC_WINDOWS("Classic Windows",0x008080,0xffffff,0x000000,0x404040,0xc0c0c0,0xffffff,Style.WINDOWS),
    WINDOWS_11("Windows 11",0x1f5fbf,0x1a1a1a,0x1a1a1a,0x5f5f5f,0xd4d4d4,0xc42b1c,Style.FLUENT),
    MACOS("macOS",0x4a3a7a,0x262626,0x262626,0x6e6e6e,0xd2d2d2,0xd9480f,Style.MACOS),
    STARSHIP("Starship console",0x000000,0xff9c00,0xffcc99,0xcc99cc,0x2e2e3e,0xff5c5c,Style.CONSOLE),
    HANDHELD("Handheld LCD",0x9bbc0f,0x0f380f,0x0f380f,0x306230,0x8bac0f,0x0f380f,Style.LCD),
    BLUEPRINT("Blueprint",0x1b4b8a,0xffffff,0xeaf2ff,0xa9c4ea,0x2d5f9e,0xffd166,Style.BLUEPRINT),
    NEON("Neon",0x12051f,0x26f0ff,0xfbe9ff,0xc08ae6,0x351650,0xff4fd8,Style.NEON),
    NEWSPRINT("Newsprint",0xf1ede3,0x111111,0x1a1a1a,0x5e5a52,0xd2ccc0,0xa31515,Style.PRINT);

    /**
     * MODERN: rounded colour bars; TERMINAL: monospaced phosphor text with ASCII bars and scan lines; MAC: 1-bit
     * windows; WINDOWS: grey bevelled windows with a navy title bar on a teal desktop; FLUENT: light rounded windows
     * with caption buttons on a blue wallpaper (Windows 11); MACOS: rounded windows with the three window buttons on a
     * dusk wallpaper; CONSOLE: a starship console of coloured bars and rounded elbows on black; LCD: four shades of
     * green like an old handheld, block bars, no anti-aliasing; BLUEPRINT: white lines and hatched bars on a gridded
     * blue; NEON: glowing bars over a perspective grid; PRINT: a newspaper page, serif type, black bars with a small
     * coloured dot.
     */
    enum Style { MODERN, TERMINAL, MAC, WINDOWS, FLUENT, MACOS, CONSOLE, LCD, BLUEPRINT, NEON, PRINT }
    /** The four shades of the LCD style, darkest first: everything the style draws is reduced to them. */
    static final int[] LCD_SHADES={0x0f380f,0x306230,0x8bac0f,0x9bbc0f};
    /** Styles that draw a window per provider. */
    boolean windowed(){return style==Style.MAC||style==Style.WINDOWS||style==Style.FLUENT||style==Style.MACOS;}
    public final String label;
    final int background,title,text,muted,track,warning;
    final Style style;
    Scheme(String label,int background,int title,int text,int muted,int track,int warning){
        this(label,background,title,text,muted,track,warning,Style.MODERN);
    }
    Scheme(String label,int background,int title,int text,int muted,int track,int warning,Style style){
        this.label=label;this.background=background;this.title=title;this.text=text;this.muted=muted;this.track=track;this.warning=warning;this.style=style;
    }
    String font(){return style==Style.TERMINAL||style==Style.LCD?"DejaVu Sans Mono":style==Style.PRINT?"DejaVu Serif":"DejaVu Sans";}
    /** Light schemes get dark footer text on the device. */
    boolean light(){int r=background>>16&255,g=background>>8&255,b=background&255;return (299*r+587*g+114*b)/1000>140;}
    @JsonValue public String id(){return name().toLowerCase(Locale.ROOT);}
    /** The scheme {@code step} places further in this list, wrapping around: touch displays swipe through them. */
    public Scheme shifted(int step){var all=values();return all[Math.floorMod(ordinal()+step,all.length)];}
    static boolean known(String id){
        for(var s:values())if(s.id().equals(id))return true;
        return false;
    }
    /** Stored and submitted ids; also accepts the enum name of older files. Unknown ids are rejected. */
    @JsonCreator static Scheme parse(String id){
        String key=id==null?"":id.strip().toLowerCase(Locale.ROOT);
        for(var s:values())if(s.id().equals(key))return s;
        throw new IllegalArgumentException("Unknown colour scheme: "+id);
    }
    /** Unknown or empty ids fall back to Midnight. */
    static Scheme of(String id){
        for(var s:values())if(s.id().equals(id))return s;
        return MIDNIGHT;
    }
}
