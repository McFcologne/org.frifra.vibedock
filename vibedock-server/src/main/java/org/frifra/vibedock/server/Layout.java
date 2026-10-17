package org.frifra.vibedock.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.*;

/**
 * The bands of the status image from top to bottom: one to five usage bands and the context band (weather, update time,
 * mood emoji and the device IP). A usage band holds one full-width source, two half-width sources (92 px, may shrink to
 * 88 px) or one compact source in a single row (46 px, may shrink to 44 px); the context band is 56 px. A display shows
 * the first usage bands that fit its height, e.g. on the VibeTV M1 one full and two compact bands or four compact ones.
 * Taller screens have spare height: spread evenly between the bands (the default), or with alignTop the bands stay
 * together at the top and a weather/mood band at the end keeps to the bottom edge.
 */
public record Layout(List<Band> bands,@JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean alignTop) {
    public static final int USAGE_HEIGHT=92,USAGE_MIN_HEIGHT=88,COMPACT_HEIGHT=46,COMPACT_MIN_HEIGHT=44,CONTEXT_HEIGHT=56,MAX_USAGE_BANDS=5;
    public Layout(List<Band> bands){this(bands,false);}

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME,property="type")
    @JsonSubTypes({@JsonSubTypes.Type(value=Usage.class,name="usage"),@JsonSubTypes.Type(value=Context.class,name="context")})
    public sealed interface Band permits Usage,Context { int height(); }
    /** One source fills the width; two sources share it; compact: one source in a single row with small bars. */
    public record Usage(List<String> sources,@JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean compact) implements Band {
        public Usage {
            if(sources==null||sources.isEmpty()||sources.size()>2||sources.stream().anyMatch(s->s==null||!s.matches("[a-z0-9][a-z0-9_-]{0,39}")))
                throw new IllegalArgumentException("Each usage band needs one or two data sources");
            if(compact&&sources.size()>1)throw new IllegalArgumentException("A compact band holds one data source");
            sources=List.copyOf(sources);
        }
        public Usage(List<String> sources){this(sources,false);}
        public int height(){return compact?COMPACT_HEIGHT:USAGE_HEIGHT;}
        public int minHeight(){return compact?COMPACT_MIN_HEIGHT:USAGE_MIN_HEIGHT;}
    }
    /** Weather and mood; moodLeft swaps their slots. The left slot is shorter because the IP sits below it. */
    public record Context(boolean moodLeft) implements Band { public int height(){return CONTEXT_HEIGHT;} }

    public static final Layout DEFAULT=new Layout(List.of(new Usage(List.of("codex")),new Usage(List.of("claude")),new Context(false)));
    public Layout {
        if(bands==null||bands.size()<2||bands.size()>MAX_USAGE_BANDS+1||bands.stream().anyMatch(Objects::isNull)||bands.stream().filter(Context.class::isInstance).count()!=1)
            throw new IllegalArgumentException("A layout has one to "+MAX_USAGE_BANDS+" usage bands and one weather/mood band");
        bands=List.copyOf(bands);
    }
    /**
     * The layout as a display of this type shows it: only the first usage bands that fit its height (at their minimum
     * height, at least one), and on narrow screens the first source of a split band alone. The order of the bands stays.
     */
    public Layout fit(DisplayType type){
        var next=new ArrayList<Band>();int used=0;boolean full=false;
        for(var band:bands){
            if(band instanceof Usage u){
                if(full||(used>0&&used+u.minHeight()>type.bandRoom())){full=true;continue;}
                used+=u.minHeight();
                next.add(type.halfWidth()||u.sources().size()==1?u:new Usage(List.of(u.sources().get(0))));
            }else next.add(band);
        }
        return new Layout(next,alignTop);
    }
    public Layout withBands(List<Band> next){return new Layout(next,alignTop);}
    public Context context(){return bands.stream().filter(Context.class::isInstance).map(Context.class::cast).findFirst().orElseThrow();}
    public boolean contextAtBottom(){return bands.get(bands.size()-1) instanceof Context;}
    /** The same bands with the context band moved to the bottom, for firmware that draws the IP corner itself. */
    public Layout withContextAtBottom(){
        var next=new ArrayList<Band>(bands.stream().filter(Usage.class::isInstance).toList());next.add(context());
        return new Layout(next,alignTop);
    }
    public void validateSources(Set<String> available){
        for(var band:bands)if(band instanceof Usage usage&&!available.containsAll(usage.sources()))throw new IllegalArgumentException("Unknown data source");
    }
}
