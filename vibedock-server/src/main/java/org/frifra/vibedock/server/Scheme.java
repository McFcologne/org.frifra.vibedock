package org.frifra.vibedock.server;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Objects;

/** Display themes: shared quota data and layout, with each style's colours and drawing. The footer follows the background. */
public final class Scheme {
    public static final Scheme MIDNIGHT=builtin("midnight");
    public static final Scheme OCEAN=builtin("ocean");
    public static final Scheme FOREST=builtin("forest");
    public static final Scheme SUNSET=builtin("sunset");
    public static final Scheme LAVENDER=builtin("lavender");
    public static final Scheme MONO=builtin("mono");
    public static final Scheme PAPER=builtin("paper");
    public static final Scheme SKY=builtin("sky");
    public static final Scheme SAND=builtin("sand");
    public static final Scheme TERMINAL_GREEN=builtin("terminal_green");
    public static final Scheme TERMINAL_AMBER=builtin("terminal_amber");
    public static final Scheme CLASSIC_MAC=builtin("classic_mac");
    public static final Scheme CLASSIC_WINDOWS=builtin("classic_windows");
    public static final Scheme WINDOWS_11=builtin("windows_11");
    public static final Scheme MACOS=builtin("macos");
    public static final Scheme STARSHIP=builtin("starship");
    public static final Scheme HANDHELD=builtin("handheld");
    public static final Scheme BLUEPRINT=builtin("blueprint");
    public static final Scheme NEON=builtin("neon");
    public static final Scheme NEWSPRINT=builtin("newsprint");
    public static final Scheme MISSION_CONTROL=builtin("mission_control");
    public static final Scheme DEPARTURE_BOARD=builtin("departure_board");
    public static final Scheme NORDIC=builtin("nordic");
    public static final Scheme HI_FI=builtin("hi_fi");
    public static final Scheme SOLARIZED=builtin("solarized");
    public static final Scheme PIXEL_QUEST=builtin("pixel_quest");
    public static final Scheme BAUHAUS=builtin("bauhaus");
    public static final Scheme E_INK=builtin("e_ink");
    public static final Scheme INDUSTRIAL=builtin("industrial");
    public static final Scheme PORCELAIN=builtin("porcelain");
    public static final Scheme GITHUB_DARK=builtin("github_dark");
    public static final Scheme VS_CODE=builtin("vs_code");
    public static final Scheme TERMINAL=builtin("terminal");
    public static final Scheme GIT_DIFF=builtin("git_diff");
    public static final Scheme DEBUGGER=builtin("debugger");

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
    final ThemeDefinition drawing;
    Scheme(String id,String label,int background,int title,int text,int muted,int track,int warning){
        this(id,label,background,title,text,muted,track,warning,Style.MODERN);
    }
    Scheme(String id,String label,int background,int title,int text,int muted,int track,int warning,Style style){
        this(id,label,ThemeDefinition.basic(style,new ThemeDefinition.Palette(background,title,text,muted,track,warning)));
    }
    Scheme(String id,String label,ThemeDefinition drawing){
        if(id==null||!id.matches("[a-z][a-z0-9._-]{0,79}"))throw new IllegalArgumentException("Invalid theme id");
        this.id=id;this.label=Objects.requireNonNull(label);this.drawing=Objects.requireNonNull(drawing);
        var palette=drawing.palette();background=palette.background();title=palette.title();text=palette.text();
        muted=palette.muted();track=palette.track();warning=palette.warning();style=drawing.preset();
    }
    private static Scheme builtin(String id){
        var loaded=BuiltinThemes.get(id);return new Scheme(id,loaded.contents().manifest().name(),loaded.definition());
    }
    String font(){return drawing.font();}
    /** Light schemes get dark footer text on the device. */
    boolean light(){int r=background>>16&255,g=background>>8&255,b=background&255;return (299*r+587*g+114*b)/1000>140;}
    @JsonValue public String id(){return id;}
    /** Catalog order is explicit and independent of the renderer's style or declaration order. */
    public Scheme shifted(int step){return ThemeCatalog.builtins().shifted(this,step);}
    static boolean known(String id){return ThemeCatalog.builtins().find(id).isPresent();}
    /** Stored and submitted ids; also accepts the enum names of older files. Unknown ids are rejected. */
    static Scheme parse(String id){
        String key=id==null?"":id.strip().toLowerCase(Locale.ROOT);
        return ThemeCatalog.builtins().find(key).orElseThrow(()->new IllegalArgumentException("Unknown colour scheme: "+id));
    }
    /** Missing installed packages retain their ID on disk; rendering resolves it against the instance catalog. */
    @JsonCreator static Scheme stored(String id){
        String key=id==null?"":id.strip().toLowerCase(Locale.ROOT);
        return ThemeCatalog.builtins().find(key).orElseGet(()->new Scheme(key,key,MIDNIGHT.drawing));
    }
    /** Unknown or empty ids fall back to Midnight, as before. */
    static Scheme of(String id){return ThemeCatalog.builtins().find(id).orElse(MIDNIGHT);}
    @Override public String toString(){return id;}
    @Override public boolean equals(Object other){
        return other instanceof Scheme s&&id.equals(s.id)&&label.equals(s.label)&&background==s.background&&title==s.title
                &&text==s.text&&muted==s.muted&&track==s.track&&warning==s.warning&&style==s.style&&drawing.equals(s.drawing);
    }
    @Override public int hashCode(){return Objects.hash(id,label,background,title,text,muted,track,warning,style,drawing);}
}
