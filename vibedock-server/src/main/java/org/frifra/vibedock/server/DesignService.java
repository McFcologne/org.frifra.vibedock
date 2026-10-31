package org.frifra.vibedock.server;

import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.Objects;

/** Turns designs into status images and validates unsaved designs from the editor. */
@Service
class DesignService {
    /** An unsaved design from the editor; mood "" or "-" is none, an empty weather location turns weather off. */
    /** display: the device's display type id (editor of a device), null keeps the current one. */
    record Input(String mood,String scheme,String weatherLocation,Layout layout,boolean inherit,String display,Design.Photos photos,Design.Icon icon) {
        Input(String mood,String scheme,String weatherLocation,Layout layout,boolean inherit,String display,Design.Photos photos){this(mood,scheme,weatherLocation,layout,inherit,display,photos,null);}
        Input(String mood,String scheme,String weatherLocation,Layout layout,boolean inherit,String display){this(mood,scheme,weatherLocation,layout,inherit,display,null);}
    }
    private final UsageService usage;private final StatusImage renderer;private final Twemoji twemoji;private final WeatherService weather;
    private final PhotoLibrary photos;private final java.time.Clock clock;private final ThemeSettings themes;private final DevicePower power;
    /** Swipes in a slideshow: photos skipped per display, in memory only. */
    private final java.util.Map<String,Integer> skipped=new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * A double tap on a touch display (or the web page): photos on (true) or off (false) for that display, whatever
     * the schedule or the switch in the editor say; until the next double tap or a restart of the server.
     */
    private final java.util.Map<String,Boolean> forced=new java.util.concurrent.ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    DesignService(UsageService usage,StatusImage renderer,Twemoji twemoji,WeatherService weather,PhotoLibrary photos,java.time.Clock clock,ThemeSettings themes,DevicePower power){
        this.usage=usage;this.renderer=renderer;this.twemoji=twemoji;this.weather=weather;this.photos=photos;this.clock=clock;this.themes=themes;this.power=power;
    }
    DesignService(UsageService usage,StatusImage renderer,Twemoji twemoji,WeatherService weather,PhotoLibrary photos,java.time.Clock clock,ThemeSettings themes){
        this(usage,renderer,twemoji,weather,photos,clock,themes,new DevicePower());
    }
    DesignService(UsageService usage,StatusImage renderer,Twemoji twemoji,WeatherService weather,PhotoLibrary photos,java.time.Clock clock){this(usage,renderer,twemoji,weather,photos,clock,null);}
    ThemeLibrary.Snapshot themeSnapshot(){return themes==null?ThemeLibrary.builtins():themes.snapshot();}
    Scheme effectiveScheme(Scheme scheme){return themeSnapshot().effective(scheme);}
    Scheme nextScheme(Scheme current,int step){return themes==null?current.shifted(step):themes.shifted(current,step);}
    /** Stable sample readings, rendered by the same code as devices; no provider or weather network calls. */
    byte[] themePreview(Scheme scheme) throws IOException {
        var snapshot=themeSnapshot();scheme=snapshot.effective(scheme);
        long time=java.time.Instant.parse("2026-10-14T05:12:00Z").getEpochSecond();
        var values=new java.util.LinkedHashMap<String,UsageService.Provider>();
        values.put("codex",new UsageService.Provider("ok",time,new ProviderClient.Window(0,time+5*3600),new ProviderClient.Window(22,time+6*86400)));
        values.put("claude",new UsageService.Provider("ok",time,new ProviderClient.Window(5,time+3*3600),new ProviderClient.Window(14,time+5*86400)));
        return renderer.render(new UsageService.Snapshot(1,time,values),"192.168.1.50",new StatusImage.Look(
                ThemeIcons.image(new Design.Icon("theme-default",null,null),scheme,Twemoji.SIZE,snapshot::contents).orElse(null),scheme,twemoji.image("⛅",24).orElse(null),"13°",Layout.DEFAULT)).png();
    }
    /**
     * What a display shows now: the current photo of its slideshow while that is active, otherwise the status.
     * display: the key of the display (its identifier) for its swipes.
     */
    StatusImage.Image render(Design design,String ip,DisplayType type,String display) throws IOException {return render(design,ip,type,display,1);}
    /** The same at scale times the type's pixels (web displays on high-resolution screens; PNG only). */
    StatusImage.Image render(Design design,String ip,DisplayType type,String display,double scale) throws IOException {
        var photo=currentPhoto(design,display);
        if(photo.isPresent()){var image=photos.render(photo.get(),type,scale);if(image.isPresent())return image.get();}
        return render(design,ip,type,scale,power.battery(display));
    }
    /** The photos of the design's slideshow that still exist, if it is active now. */
    java.util.List<String> activePhotos(Design design){return activePhotos(design,null);}
    /** The same for one display: its double tap decides instead of the schedule. */
    java.util.List<String> activePhotos(Design design,String display){
        var p=design.photos();
        Boolean on=display==null?null:forced.get(display);
        if(p==null||p.images().isEmpty()||(on==null?!p.active(java.time.LocalTime.now(clock)):!on))return java.util.List.of();
        return p.images().stream().filter(photos::exists).toList();
    }
    /**
     * A double tap: the slideshow stops when it runs and starts when it does not, independent of its schedule; false
     * (nothing changes) for a design without photos.
     */
    boolean togglePhotos(Design design,String display){
        if(display==null||design.photos()==null||design.photos().images().isEmpty())return false;
        boolean running=!activePhotos(design,display).isEmpty();
        forced.put(display,!running);skipped.remove(display);return true;
    }
    /** Whether a display's double tap overrides its slideshow now: true on, false off, null not. */
    Boolean photosForced(String display){return display==null?null:forced.get(display);}
    /**
     * The photo shown now: every photo for its seconds, counted from the clock so that all displays with the same
     * slideshow change together; swipes move a display on. Shuffled: a new order per round, the same for all.
     */
    java.util.Optional<String> currentPhoto(Design design,String display){
        var images=activePhotos(design,display);if(images.isEmpty())return java.util.Optional.empty();
        long step=clock.instant().getEpochSecond()/design.photos().seconds()+skipped.getOrDefault(display==null?"":display,0);
        int n=images.size();
        if(!design.photos().shuffle())return java.util.Optional.of(images.get((int)Math.floorMod(step,n)));
        var order=new java.util.ArrayList<>(images);
        java.util.Collections.shuffle(order,new java.util.Random(images.hashCode()*31L+Math.floorDiv(step,n)));
        return java.util.Optional.of(order.get((int)Math.floorMod(step,n)));
    }
    /** A swipe in a running slideshow: the next (1) or previous (-1) photo; false when no slideshow runs. */
    boolean skip(Design design,String display,int step){
        if(display==null||activePhotos(design,display).isEmpty())return false;
        skipped.merge(display,step,Integer::sum);return true;
    }
    /** Sound metadata stays available during a slideshow, for the sources configured on this display. */
    String quotaHeader(Design design,DisplayType display){return QuotaHeader.create(usage.snapshot(),design.layout().fit(display));}
    /** The poll interval while a slideshow runs: one pull per photo, but not slower than the server's interval. */
    int pollSeconds(Design design,int server){return pollSeconds(design,server,null);}
    int pollSeconds(Design design,int server,String display){
        return activePhotos(design,display).isEmpty()?server:Math.min(server,Math.max(ClientRegistry.MIN_POLL,design.photos().seconds()));
    }
    /** The next time of day the design's slideshow starts or stops by its schedule (epoch seconds), else null. */
    Long nextPhotoBoundary(Design design){
        var p=design.photos();
        if(p==null||p.paused()||p.images().isEmpty()||p.from()==null)return null;
        var now=java.time.ZonedDateTime.now(clock);Long next=null;
        for(String time:java.util.List.of(p.from(),p.to())){
            var at=now.with(java.time.LocalTime.parse(time));if(!at.isAfter(now))at=at.plusDays(1);
            long s=at.toEpochSecond();next=next==null?s:Math.min(next,s);
        }
        return next;
    }
    /** The device image for a design with the current usage data; weather appears once a reading is cached. */
    StatusImage.Image render(Design design,String ip) throws IOException {return render(design,ip,DisplayType.M1);}
    /** The image in the resolution of a display type, the design fitted to it. */
    StatusImage.Image render(Design design,String ip,DisplayType type) throws IOException {return render(design,ip,type,1);}
    StatusImage.Image render(Design design,String ip,DisplayType type,double scale) throws IOException {return render(design,ip,type,scale,null);}
    /** battery: the display runs on its battery (a small icon at the top right), null otherwise. */
    StatusImage.Image render(Design design,String ip,DisplayType type,double scale,StatusImage.Battery battery) throws IOException {
        var current=weather.current(design.weather());
        var snapshot=themeSnapshot();var scheme=snapshot.effective(design.scheme());
        int iconSize=(int)Math.round(Twemoji.SIZE*scale);
        var icon=ThemeIcons.image(design.icon(),scheme,iconSize,snapshot::contents).or(()->twemoji.image(design.mood(),iconSize));
        var look=new StatusImage.Look(icon.orElse(null),scheme,
                current.flatMap(c->twemoji.image(WeatherService.emoji(c.code(),c.day()),(int)Math.round(24*scale))).orElse(null),
                current.map(c->WeatherService.temperature(c.temperature())).orElse(null),design.layout(),battery);
        return renderer.render(usage.snapshot(),ip,look,type,scale);
    }
    /**
     * What a device actually shows: firmware that draws the IP corner itself (rows 220-239) needs the weather/mood
     * band, which carries the IP, at the bottom.
     */
    static Design forDevice(Design design,boolean serverFooter){
        if(serverFooter||design.layout().contextAtBottom())return design;
        return design.withLayout(design.layout().withContextAtBottom());
    }
    /** Validates an editor input; an unchanged weather location reuses the resolved place instead of geocoding again. */
    Design resolve(Input input,Design previous){
        if(input==null||input.layout()==null||input.mood()==null||input.scheme()==null||input.weatherLocation()==null)
            throw new IllegalArgumentException("Complete the design before saving");
        String schemeId=Scheme.stored(input.scheme()).id();
        var found=themeSnapshot().catalog().find(schemeId);
        var scheme=found.orElseGet(()->{
            if(previous.scheme().id().equals(schemeId))return previous.scheme();
            throw new IllegalArgumentException("This theme is unavailable");
        });
        if(themes!=null&&!themes.enabled(scheme)&&!scheme.id().equals(previous.scheme().id()))throw new IllegalArgumentException("This theme is disabled for this instance");
        input.layout().validateSources(usage.snapshot().providers().keySet());
        if(!Objects.equals(input.mood(),previous.mood()))twemoji.validate(input.mood());
        if(input.icon()!=null&&!Objects.equals(input.icon(),previous.icon())&&!ThemeIcons.image(input.icon(),scheme,Twemoji.SIZE,themeSnapshot()::contents).isPresent())
            throw new IllegalArgumentException("This theme icon is unavailable");
        String place=input.weatherLocation().strip();
        var resolved=previous.weather()!=null&&place.equals(previous.weather().name())?previous.weather():weather.locate(place);
        // Photos removed from the library meanwhile drop out of the selection.
        var chosen=input.photos();
        var selected=chosen==null?null:new Design.Photos(chosen.images().stream().filter(photos::exists).toList(),chosen.seconds(),chosen.shuffle(),chosen.from(),chosen.to(),chosen.paused());
        return new Design(scheme,input.mood(),resolved,input.layout(),selected,input.icon());
    }
}
