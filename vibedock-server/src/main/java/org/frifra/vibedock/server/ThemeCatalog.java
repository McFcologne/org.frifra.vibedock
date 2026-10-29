package org.frifra.vibedock.server;

import java.util.*;

/** Ordered theme definitions keyed by their persisted IDs; no enum ordinals or mutable registration. */
final class ThemeCatalog {
    private static final ThemeCatalog BUILTINS=new ThemeCatalog(List.of(
            Scheme.MIDNIGHT,Scheme.OCEAN,Scheme.FOREST,Scheme.SUNSET,Scheme.LAVENDER,
            Scheme.MONO,Scheme.PAPER,Scheme.SKY,Scheme.SAND,Scheme.TERMINAL_GREEN,
            Scheme.TERMINAL_AMBER,Scheme.CLASSIC_MAC,Scheme.CLASSIC_WINDOWS,Scheme.WINDOWS_11,Scheme.MACOS,
            Scheme.STARSHIP,Scheme.HANDHELD,Scheme.BLUEPRINT,Scheme.NEON,Scheme.NEWSPRINT,
            Scheme.MISSION_CONTROL,Scheme.DEPARTURE_BOARD,Scheme.NORDIC,Scheme.HI_FI,Scheme.SOLARIZED,
            Scheme.PIXEL_QUEST,Scheme.BAUHAUS,Scheme.E_INK,Scheme.INDUSTRIAL,Scheme.PORCELAIN,
            Scheme.GITHUB_DARK,Scheme.VS_CODE,Scheme.TERMINAL,Scheme.GIT_DIFF,Scheme.DEBUGGER));
    private final List<Scheme> themes;
    private final Map<String,Scheme> byId;
    ThemeCatalog(Collection<Scheme> definitions){
        if(definitions==null||definitions.isEmpty())throw new IllegalArgumentException("A theme catalog cannot be empty");
        var indexed=new LinkedHashMap<String,Scheme>();
        for(var theme:definitions){
            Objects.requireNonNull(theme,"Theme definition");
            if(indexed.putIfAbsent(theme.id(),theme)!=null)throw new IllegalArgumentException("Duplicate theme id: "+theme.id());
        }
        themes=List.copyOf(indexed.values());byId=Map.copyOf(indexed);
    }
    static ThemeCatalog builtins(){return BUILTINS;}
    List<Scheme> all(){return themes;}
    Optional<Scheme> find(String id){return Optional.ofNullable(id==null?null:byId.get(id));}
    Scheme shifted(Scheme current,int step){
        return shifted(current,step,Set.of(),false);
    }
    /** Availability swipes take one enabled step in either direction, preserving the device protocol. */
    Scheme nextEnabled(Scheme current,int step,Set<String> disabled){
        return shifted(current,step,disabled,true);
    }
    private Scheme shifted(Scheme current,int step,Set<String> disabled,boolean enabledOnly){
        var canonical=find(current.id()).orElseThrow(()->new IllegalArgumentException("Theme is not in this catalog: "+current.id()));
        int index=themes.indexOf(canonical);if(step==0)return current;
        if(!enabledOnly)return themes.get((int)Math.floorMod((long)index+step,themes.size()));
        int direction=Integer.signum(step);
        for(int n=1;n<=themes.size();n++){
            var candidate=themes.get(Math.floorMod(index+direction*n,themes.size()));
            if(!disabled.contains(candidate.id()))return candidate;
        }
        return current;
    }
}
