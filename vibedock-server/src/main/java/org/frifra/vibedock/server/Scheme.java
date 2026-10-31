package org.frifra.vibedock.server;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Objects;

/** Display themes: shared quota data and layout, with each style's colours and drawing. The footer follows the background. */
public final class Scheme {
    public static final Scheme MIDNIGHT=new Scheme("midnight","Midnight",0x000000,0x80d8ff,0xffffff,0x8a97a8,0x2a3546,0xffb84d);
    public static final Scheme OCEAN=new Scheme("ocean","Ocean",0x000000,0x5ac8fa,0xe6f6ff,0x6f9bb3,0x163447,0xffb84d);
    public static final Scheme FOREST=new Scheme("forest","Forest",0x000000,0x9be38a,0xf0fff0,0x7f9f86,0x1f3a28,0xffb84d);
    public static final Scheme SUNSET=new Scheme("sunset","Sunset",0x000000,0xffb36b,0xfff4e8,0xb08f7a,0x45291f,0xffb84d);
    public static final Scheme LAVENDER=new Scheme("lavender","Lavender",0x000000,0xc9a7ff,0xf6f0ff,0x9a8bb3,0x2f2547,0xffb84d);
    public static final Scheme MONO=new Scheme("mono","Mono",0x000000,0xffffff,0xffffff,0xb0b0b0,0x3a3a3a,0xffb84d);
    public static final Scheme PAPER=new Scheme("paper","Paper (light)",0xf4f4f0,0x1f5fbf,0x1a1a1a,0x6b6b6b,0xd8dbe0,0xc25e00);
    public static final Scheme SKY=new Scheme("sky","Sky (light)",0xe6f1fc,0x0a5fb4,0x0f1e33,0x56708c,0xc6d9ee,0xc25e00);
    public static final Scheme SAND=new Scheme("sand","Sand (light)",0xf6eedf,0xa8471a,0x2b1d12,0x84705e,0xe3d3bb,0xb34700);
    public static final Scheme TERMINAL_GREEN=new Scheme("terminal_green","Classic green",0x040b05,0x6fdc8c,0x4fd873,0x2a8344,0x0d2a14,0xb5e8a0,Style.TERMINAL);
    public static final Scheme TERMINAL_AMBER=new Scheme("terminal_amber","Classic amber",0x0d0800,0xffc23d,0xffb000,0x9a6a00,0x2e1f00,0xffd98a,Style.TERMINAL);
    public static final Scheme CLASSIC_MAC=new Scheme("classic_mac","Classic Mac",0xffffff,0x000000,0x000000,0x000000,0xffffff,0x000000,Style.MAC);
    public static final Scheme CLASSIC_WINDOWS=new Scheme("classic_windows","Classic Windows",0x008080,0xffffff,0x000000,0x404040,0xc0c0c0,0xffffff,Style.WINDOWS);
    public static final Scheme WINDOWS_11=new Scheme("windows_11","Windows 11",0x1f5fbf,0x1a1a1a,0x1a1a1a,0x5f5f5f,0xd4d4d4,0xc42b1c,Style.FLUENT);
    public static final Scheme MACOS=new Scheme("macos","macOS",0x4a3a7a,0x262626,0x262626,0x6e6e6e,0xd2d2d2,0xd9480f,Style.MACOS);
    public static final Scheme STARSHIP=new Scheme("starship","Starship console",0x000000,0xff9c00,0xffcc99,0xcc99cc,0x2e2e3e,0xff5c5c,Style.CONSOLE);
    public static final Scheme HANDHELD=new Scheme("handheld","Handheld LCD",0x9bbc0f,0x0f380f,0x0f380f,0x306230,0x8bac0f,0x0f380f,Style.LCD);
    public static final Scheme BLUEPRINT=new Scheme("blueprint","Blueprint",0x1b4b8a,0xffffff,0xeaf2ff,0xa9c4ea,0x2d5f9e,0xffd166,Style.BLUEPRINT);
    public static final Scheme NEON=new Scheme("neon","Neon",0x12051f,0x26f0ff,0xfbe9ff,0xc08ae6,0x351650,0xff4fd8,Style.NEON);
    public static final Scheme NEWSPRINT=new Scheme("newsprint","Newsprint",0xf1ede3,0x111111,0x1a1a1a,0x5e5a52,0xd2ccc0,0xa31515,Style.PRINT);
    public static final Scheme MISSION_CONTROL=new Scheme("mission_control","Mission Control",0x101b24,0x78d9ea,0xd8e5ea,0x829ba9,0x243744,0xffb84d,Style.MISSION);
    public static final Scheme DEPARTURE_BOARD=new Scheme("departure_board","Departure Board",0x11130f,0xf4cd56,0xe1d8ad,0xa19d7d,0x2b2c20,0xf4a353,Style.DEPARTURE);
    public static final Scheme NORDIC=new Scheme("nordic","Nordic",0xf4f2ec,0x315b62,0x263d43,0x727e76,0xdde2d9,0xa85126,Style.NORDIC);
    public static final Scheme HI_FI=new Scheme("hi_fi","Hi-Fi",0x242522,0xefc784,0xe8e2cb,0xa39e89,0x363b2d,0xf1a56c,Style.HIFI);
    public static final Scheme SOLARIZED=new Scheme("solarized","Solarized",0x002b36,0xb58900,0x93a1a1,0x839496,0x073642,0xcb4b16,Style.SOLARIZED);
    public static final Scheme PIXEL_QUEST=new Scheme("pixel_quest","Pixel Quest",0x16182c,0xf0ce80,0xf1eacb,0xa2a2c2,0x30354e,0xf7a276,Style.QUEST);
    public static final Scheme BAUHAUS=new Scheme("bauhaus","Bauhaus",0xf1e8d5,0xb23927,0x252620,0x7d796c,0xd8cfbb,0xb23927,Style.BAUHAUS);
    public static final Scheme E_INK=new Scheme("e_ink","E-Ink",0xf5f4ee,0x232620,0x232620,0x66695e,0xdfdfd5,0x232620,Style.INK);
    public static final Scheme INDUSTRIAL=new Scheme("industrial","Industrial",0x252a2c,0xf1aa50,0xeee9dc,0x959d9b,0x3e4646,0xf1aa50,Style.INDUSTRIAL);
    public static final Scheme PORCELAIN=new Scheme("porcelain","Porcelain",0xf8f8f2,0x2453a1,0x253e66,0x6b7f99,0xdce5ee,0x9b452f,Style.PORCELAIN);
    public static final Scheme GITHUB_DARK=new Scheme("github_dark","GitHub Dark",0x0d1117,0x79c0ff,0xe6edf3,0x8b949e,0x212c25,0xffb84d,Style.GITHUB);
    public static final Scheme VS_CODE=new Scheme("vs_code","VS Code",0x1f1f1f,0xdcdcaa,0xd4d4d4,0x9b9b9b,0x343434,0xffb84d,Style.CODE);
    public static final Scheme TERMINAL=new Scheme("terminal","Terminal",0x101410,0xb4e88c,0xd8e9d0,0x88a47e,0x233021,0xffb84d,Style.SHELL);
    public static final Scheme GIT_DIFF=new Scheme("git_diff","Git Diff",0x161b22,0x79c0ff,0xe6edf3,0xa1acb8,0x643338,0xffb84d,Style.DIFF);
    public static final Scheme DEBUGGER=new Scheme("debugger","Debugger",0x191829,0xc4a5ff,0xe1def5,0xa39abb,0x34304b,0xffb84d,Style.DEBUG);

    /**
     * MODERN: rounded colour bars; TERMINAL: monospaced phosphor text with ASCII bars and scan lines; MAC: 1-bit
     * windows; WINDOWS: grey bevelled windows with a navy title bar on a teal desktop; FLUENT: light rounded windows
     * with caption buttons on a blue wallpaper (Windows 11); MACOS: rounded windows with the three window buttons on a
     * dusk wallpaper; CONSOLE: a starship console of coloured bars and rounded elbows on black; LCD: four shades of
     * green like an old handheld, block bars, no anti-aliasing; BLUEPRINT: white lines and hatched bars on a gridded
     * blue; NEON: glowing bars over a perspective grid; PRINT: a newspaper page, serif type, black bars with a small
     * coloured dot. The additional instrument, pixel and print styles are drawn by ThemeArtwork, within the same bands.
     */
    enum Style { MODERN, TERMINAL, MAC, WINDOWS, FLUENT, MACOS, CONSOLE, LCD, BLUEPRINT, NEON, PRINT,
        MISSION, DEPARTURE, NORDIC, HIFI, SOLARIZED, QUEST, BAUHAUS, INK, INDUSTRIAL, PORCELAIN, GITHUB, CODE, SHELL, DIFF, DEBUG }
    boolean developer(){return switch(style){case GITHUB,CODE,SHELL,DIFF,DEBUG->true;default->false;};}
    boolean illustrated(){return switch(style){
        case MISSION,DEPARTURE,NORDIC,HIFI,SOLARIZED,QUEST,BAUHAUS,INK,INDUSTRIAL,PORCELAIN,GITHUB,CODE,SHELL,DIFF,DEBUG->true;
        default->false;
    };}
    /** The four shades of the LCD style, darkest first: everything the style draws is reduced to them. */
    static final int[] LCD_SHADES={0x0f380f,0x306230,0x8bac0f,0x9bbc0f};
    /** Styles that draw a window per provider. */
    boolean windowed(){return style==Style.MAC||style==Style.WINDOWS||style==Style.FLUENT||style==Style.MACOS;}
    private final String id;
    public final String label;
    final int background,title,text,muted,track,warning;
    final Style style;
    Scheme(String id,String label,int background,int title,int text,int muted,int track,int warning){
        this(id,label,background,title,text,muted,track,warning,Style.MODERN);
    }
    Scheme(String id,String label,int background,int title,int text,int muted,int track,int warning,Style style){
        if(id==null||!id.matches("[a-z][a-z0-9._-]{0,79}"))throw new IllegalArgumentException("Invalid theme id");
        this.id=id;Objects.requireNonNull(label);Objects.requireNonNull(style);
        this.label=label;this.background=background;this.title=title;this.text=text;this.muted=muted;this.track=track;this.warning=warning;this.style=style;
    }
    String font(){return switch(style){case TERMINAL,LCD,MISSION,DEPARTURE,HIFI,SOLARIZED,QUEST,INDUSTRIAL,GITHUB,CODE,SHELL,DIFF,DEBUG->"DejaVu Sans Mono";case PRINT,PORCELAIN->"DejaVu Serif";default->"DejaVu Sans";};}
    /** Light schemes get dark footer text on the device. */
    boolean light(){int r=background>>16&255,g=background>>8&255,b=background&255;return (299*r+587*g+114*b)/1000>140;}
    @JsonValue public String id(){return id;}
    /** Catalog order is explicit and independent of the renderer's style or declaration order. */
    public Scheme shifted(int step){return ThemeCatalog.builtins().shifted(this,step);}
    static boolean known(String id){return ThemeCatalog.builtins().find(id).isPresent();}
    /** Stored and submitted ids; also accepts the enum names of older files. Unknown ids are rejected. */
    @JsonCreator static Scheme parse(String id){
        String key=id==null?"":id.strip().toLowerCase(Locale.ROOT);
        return ThemeCatalog.builtins().find(key).orElseThrow(()->new IllegalArgumentException("Unknown colour scheme: "+id));
    }
    /** Unknown or empty ids fall back to Midnight, as before. */
    static Scheme of(String id){return ThemeCatalog.builtins().find(id).orElse(MIDNIGHT);}
    @Override public String toString(){return id;}
    @Override public boolean equals(Object other){
        return other instanceof Scheme s&&id.equals(s.id)&&label.equals(s.label)&&background==s.background&&title==s.title
                &&text==s.text&&muted==s.muted&&track==s.track&&warning==s.warning&&style==s.style;
    }
    @Override public int hashCode(){return Objects.hash(id,label,background,title,text,muted,track,warning,style);}
}
