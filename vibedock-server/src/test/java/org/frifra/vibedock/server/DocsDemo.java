package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import java.net.http.HttpClient;
import java.time.*;
import java.util.Optional;

/**
 * The server as the manual's screenshots show it (vibedock-server/scripts/docs.sh): the real application with a fixed
 * clock, made-up usage readings per demo token and fixed weather, so every image can be made again identically. It
 * talks to no provider and no weather service. Not part of the server image (test sources only).
 */
public class DocsDemo {
    /** 2026-10-14 09:41 in Berlin. */
    static final Instant NOW=Instant.parse("2026-10-14T07:41:00Z");

    public static void main(String[] args) {
        new SpringApplicationBuilder(Application.class,Beans.class).run(args);
    }

    /** Registered explicitly (no @Configuration), so the server's own tests never pick it up by component scan. */
    static class Beans {
        @Bean @Primary Clock docsClock(){return Clock.fixed(NOW,ZoneOffset.UTC);}

        @Bean @Primary ProviderClient docsProviders(HttpClient http,ObjectMapper json){
            return new ProviderClient(http,json){
                @Override public Reading fetch(String provider,SettingsStore.Credential credential){
                    long now=NOW.getEpochSecond(),hour=3600,day=86400;
                    return switch(credential.token()){
                        case "demo-codex-work" -> new Reading(new Window(22,now+3*hour+12*60),new Window(41,now+4*day+5*hour));
                        case "demo-claude-work" -> new Reading(new Window(64,now+hour+5*60),new Window(37,now+2*day+9*hour));
                        case "demo-claude-private" -> new Reading(new Window(0,0),new Window(12,now+5*day+hour));
                        case "demo-codex-private" -> new Reading(null,new Window(8,now+28*day,30*day));
                        case "demo-grok" -> new Reading(null,new Window(55,now+3*day,7*day));
                        case "demo-gemini" -> new Reading(null,new Window(18,now+14*hour,day));
                        default -> new Reading(null,null);
                    };
                }
            };
        }

        @Bean @Primary WeatherService docsWeather(HttpClient http,ObjectMapper json,Clock clock){
            return new WeatherService(http,json,clock,"http://127.0.0.1:9","http://127.0.0.1:9"){
                @Override public Place locate(String query){
                    if(query==null||query.isBlank())return null;
                    return query.strip().equals("-")?Place.NONE:new Place(query.strip(),50.94,6.96);
                }
                @Override public Optional<Current> current(Place place){
                    if(place==null||place.equals(Place.NONE))return Optional.empty();
                    // Sunny, except in London.
                    return Optional.of(place.name().startsWith("London")?new Current(16.2,3,true,NOW.getEpochSecond()):new Current(19.4,1,true,NOW.getEpochSecond()));
                }
            };
        }
    }
}
