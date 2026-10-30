package org.frifra.vibedock.server;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.*;

/** Versioned, non-executable drawing recipe. Archive integrity and drawing validation are separate steps. */
record ThemeDefinition(Scheme.Style preset,Palette palette,String font,Integer footerBackground,Integer barColor,ThemeLayers layers) {
    static final String RENDERER="vibedock-2d";
    static final int VERSION=1,MAX_BYTES=64*1024;
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    record Palette(int background,int title,int text,int muted,int track,int warning) {
        int resolve(String name){return switch(name){case "background"->background;case "title"->title;case "text"->text;
            case "muted"->muted;case "track"->track;case "warning"->warning;default->throw invalid("Unknown palette colour: "+name);};}
    }
    static ThemeDefinition read(ThemePackage.Contents contents) throws IOException {
        var engine=contents.manifest().renderer();
        if(!RENDERER.equals(engine.id())||engine.version()!=VERSION)throw invalid("Unsupported theme renderer: "+engine.id()+" v"+engine.version());
        return read(contents.file(contents.manifest().definition()));
    }
    static ThemeDefinition read(byte[] bytes) throws IOException {
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw invalid("Drawing definition must be 1–65536 bytes");
        var root=JSON.readTree(bytes);
        fields(root,Set.of("format","version","preset","palette","font","footerBackground","barColor","layers"));
        if(!RENDERER.equals(text(root,"format"))||!root.has("version")||!root.get("version").isNumber()
                ||root.get("version").decimalValue().compareTo(java.math.BigDecimal.ONE)!=0)throw invalid("Unsupported drawing format or version");
        Scheme.Style preset;
        try{preset=Scheme.Style.valueOf(text(root,"preset").toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException e){throw invalid("Unknown drawing preset");}
        if(!text(root,"preset").equals(preset.name().toLowerCase(Locale.ROOT)))throw invalid("Drawing preset must use its lowercase name");
        var p=root.get("palette");fields(p,Set.of("background","title","text","muted","track","warning"));
        var palette=new Palette(hex(text(p,"background")),hex(text(p,"title")),hex(text(p,"text")),hex(text(p,"muted")),hex(text(p,"track")),hex(text(p,"warning")));
        String font=text(root,"font");
        if(!Set.of("DejaVu Sans","DejaVu Sans Mono","DejaVu Serif").contains(font))throw invalid("Unsupported theme font");
        Integer footer=root.has("footerBackground")?colour(text(root,"footerBackground"),palette):null;
        Integer bar=root.has("barColor")?colour(text(root,"barColor"),palette):null;
        if(bar!=null&&Set.of(Scheme.Style.TERMINAL,Scheme.Style.MAC,Scheme.Style.WINDOWS,Scheme.Style.LCD,Scheme.Style.BLUEPRINT,Scheme.Style.PRINT).contains(preset))
            throw invalid("This preset uses fixed, palette-based or text bars instead of barColor");
        return new ThemeDefinition(preset,palette,font,footer,bar,ThemeLayers.read(root.get("layers"),palette));
    }
    static ThemeDefinition basic(Scheme.Style preset,Palette palette){
        String font=switch(preset){case TERMINAL,LCD,MISSION,DEPARTURE,HIFI,SOLARIZED,QUEST,INDUSTRIAL,GITHUB,CODE,SHELL,DIFF,DEBUG->"DejaVu Sans Mono";
            case PRINT,PORCELAIN->"DejaVu Serif";default->"DejaVu Sans";};
        return new ThemeDefinition(preset,palette,font,null,null,ThemeLayers.EMPTY);
    }
    static void fields(JsonNode node,Set<String> allowed){
        if(node==null||!node.isObject())throw invalid("Expected a theme object");
        for(var entry:node.properties())if(!allowed.contains(entry.getKey()))throw invalid("Unknown theme property: "+entry.getKey());
    }
    static String text(JsonNode node,String field){
        var value=node.get(field);if(value==null||!value.isTextual())throw invalid("Expected theme text: "+field);return value.textValue();
    }
    static double number(JsonNode node,double low,double high){
        if(node==null||!node.isNumber())throw invalid("Expected a theme number");
        double value=node.doubleValue();if(!Double.isFinite(value)||value<low||value>high)throw invalid("Theme number outside allowed range");return value;
    }
    static int colour(String value,Palette palette){return value.startsWith("@")?palette.resolve(value.substring(1)):hex(value);}
    private static int hex(String value){if(!value.matches("#[0-9a-fA-F]{6}"))throw invalid("Expected an RGB colour (#rrggbb)");return Integer.parseInt(value.substring(1),16);}
    static IllegalArgumentException invalid(String reason){return new IllegalArgumentException(reason);}
}
