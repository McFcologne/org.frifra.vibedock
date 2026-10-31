package org.frifra.vibedock.server;

import tools.jackson.databind.JsonNode;
import java.util.*;
import static org.frifra.vibedock.server.ThemeDefinition.*;

/** Shared editable window paints. Profiles retain their original geometry and exact legacy default pixels. */
record ThemeWindow(int surface,int titleBar,int titleText,int border,int highlight,int edgeLight,int edgeDark,
                   int controls,int shadow,int shadowAlpha,int radius) {
    static final Set<String> COLORS=Set.of("surface","titleBar","titleText","border","highlight","edgeLight","edgeDark","controls","shadow");
    static boolean supported(Scheme.Style style){return Set.of(Scheme.Style.MAC,Scheme.Style.WINDOWS,Scheme.Style.FLUENT,Scheme.Style.MACOS).contains(style);}
    static Map<String,Object> defaults(Scheme.Style style){
        var m=new LinkedHashMap<String,Object>();
        String surface=switch(style){case WINDOWS->"#c0c0c0";case FLUENT->"#f3f3f3";case MACOS->"#ececec";default->"#ffffff";};
        m.put("surface",surface);m.put("titleBar",style==Scheme.Style.WINDOWS?"#000080":surface);m.put("titleText","@title");
        m.put("border",style==Scheme.Style.FLUENT?"#d0d0d0":style==Scheme.Style.MACOS?"#bdbdbd":"#000000");
        m.put("highlight","#ffffff");m.put("edgeLight","#dfdfdf");m.put("edgeDark","#808080");
        m.put("controls",style==Scheme.Style.FLUENT?"#5c5c5c":style==Scheme.Style.MACOS?"#4a4a4a":"#000000");m.put("shadow","#000000");
        m.put("shadowAlpha",style==Scheme.Style.FLUENT?60:style==Scheme.Style.MACOS?70:style==Scheme.Style.MAC?255:0);
        m.put("radius",style==Scheme.Style.FLUENT?6:style==Scheme.Style.MACOS?7:0);return m;
    }
    static ThemeWindow read(JsonNode node,Scheme.Style style,Palette palette){
        if(!supported(style)){if(node!=null)throw invalid("Window settings need a window design style");return null;}
        var values=defaults(style);
        if(node==null&&style==Scheme.Style.WINDOWS)values.put("titleText","#ffffff");
        if(node==null&&style==Scheme.Style.MAC)values.put("titleText","#000000");
        if(node!=null){
            var allowed=new HashSet<>(COLORS);allowed.addAll(Set.of("shadowAlpha","radius"));fields(node,allowed);
            for(var e:node.properties())values.put(e.getKey(),COLORS.contains(e.getKey())?text(node,e.getKey()):integer(e.getValue(),e.getKey().equals("radius")?24:255));
        }
        int radius=(int)values.get("radius");if((style==Scheme.Style.WINDOWS||style==Scheme.Style.MAC)&&radius!=0)throw invalid("Pixel window styles have square corners");
        return new ThemeWindow(c(values,"surface",palette),c(values,"titleBar",palette),c(values,"titleText",palette),c(values,"border",palette),c(values,"highlight",palette),c(values,"edgeLight",palette),c(values,"edgeDark",palette),c(values,"controls",palette),c(values,"shadow",palette),(int)values.get("shadowAlpha"),radius);
    }
    private static int c(Map<String,Object> values,String key,Palette palette){return colour((String)values.get(key),palette);}
    private static int integer(JsonNode value,int max){double n=number(value,0,max);if(n!=Math.rint(n))throw invalid("Window sizes must be whole numbers");return (int)n;}
}
