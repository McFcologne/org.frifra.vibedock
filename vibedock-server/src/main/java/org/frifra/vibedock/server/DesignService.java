package org.frifra.vibedock.server;

import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.Objects;

/** Turns designs into status images and validates unsaved designs from the editor. */
@Service
class DesignService {
    /** An unsaved design from the editor; mood "" or "-" is none, an empty weather location turns weather off. */
    /** display: the device's display type id (editor of a device), null keeps the current one. */
    record Input(String mood,String scheme,String weatherLocation,Layout layout,boolean inherit,String display) {}
    private final UsageService usage;private final StatusImage renderer;private final Twemoji twemoji;private final WeatherService weather;
    DesignService(UsageService usage,StatusImage renderer,Twemoji twemoji,WeatherService weather){
        this.usage=usage;this.renderer=renderer;this.twemoji=twemoji;this.weather=weather;
    }
    /** The device image for a design with the current usage data; weather appears once a reading is cached. */
    StatusImage.Image render(Design design,String ip) throws IOException {return render(design,ip,DisplayType.M1);}
    /** The image in the resolution of a display type, the design fitted to it. */
    StatusImage.Image render(Design design,String ip,DisplayType type) throws IOException {
        var current=weather.current(design.weather());
        var look=new StatusImage.Look(twemoji.image(design.mood()).orElse(null),design.scheme(),
                current.flatMap(c->twemoji.image(WeatherService.emoji(c.code(),c.day()),24)).orElse(null),
                current.map(c->WeatherService.temperature(c.temperature())).orElse(null),design.layout());
        return renderer.render(usage.snapshot(),ip,look,type);
    }
    /**
     * What a device actually shows: firmware that draws the IP corner itself (rows 220-239) needs the weather/mood
     * band, which carries the IP, at the bottom.
     */
    static Design forDevice(Design design,boolean serverFooter){
        if(serverFooter||design.layout().contextAtBottom())return design;
        return new Design(design.scheme(),design.mood(),design.weather(),design.layout().withContextAtBottom());
    }
    /** Validates an editor input; an unchanged weather location reuses the resolved place instead of geocoding again. */
    Design resolve(Input input,Design previous){
        if(input==null||input.layout()==null||input.mood()==null||input.scheme()==null||input.weatherLocation()==null)
            throw new IllegalArgumentException("Complete the design before saving");
        input.layout().validateSources(usage.snapshot().providers().keySet());
        if(!Objects.equals(input.mood(),previous.mood()))twemoji.validate(input.mood());
        String place=input.weatherLocation().strip();
        var resolved=previous.weather()!=null&&place.equals(previous.weather().name())?previous.weather():weather.locate(place);
        return new Design(Scheme.parse(input.scheme()),input.mood(),resolved,input.layout());
    }
}
