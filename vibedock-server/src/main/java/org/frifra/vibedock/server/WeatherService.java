package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Current weather from Open-Meteo (no API key; data CC-BY 4.0). Places are resolved once when they are saved.
 * Readings are cached per place, refreshed in the background at most every 15 minutes and dropped after 2 hours.
 */
@Service
public class WeatherService {
    /** A resolved place; the name "-" means "no weather". */
    public record Place(String name,double latitude,double longitude) {
        static final Place NONE=new Place("-",0,0);
        boolean none(){return "-".equals(name);}
    }
    public record Current(double temperature,int code,boolean day,long fetchedAt) {}
    static final long REFRESH_SECONDS=900,KEEP_SECONDS=7200;
    private static final Logger log=LoggerFactory.getLogger(WeatherService.class);
    private final Set<String> failing=ConcurrentHashMap.newKeySet();
    private final HttpClient http;private final ObjectMapper json;private final Clock clock;
    private final String geocodingUrl,forecastUrl;
    private final Map<String,Current> cache=new ConcurrentHashMap<>();
    private final Set<String> loading=ConcurrentHashMap.newKeySet();
    @Value("${vibedock.polling:true}") private boolean polling=true;

    public WeatherService(HttpClient http,ObjectMapper json,Clock clock,
            @Value("${vibedock.weather.geocoding-url:https://geocoding-api.open-meteo.com/v1/search}") String geocodingUrl,
            @Value("${vibedock.weather.forecast-url:https://api.open-meteo.com/v1/forecast}") String forecastUrl){
        this.http=http;this.json=json;this.clock=clock;this.geocodingUrl=geocodingUrl;this.forecastUrl=forecastUrl;
    }

    /** Resolves a place name or "latitude, longitude"; empty returns null, "-" returns NONE. */
    public Place locate(String query){
        String q=query==null?"":query.strip();
        if(q.isEmpty())return null;
        if(q.equals("-"))return Place.NONE;
        if(q.length()>80||q.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid weather location");
        var coordinates=java.util.regex.Pattern.compile("(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)").matcher(q);
        if(coordinates.matches())return place(q,Double.parseDouble(coordinates.group(1)),Double.parseDouble(coordinates.group(2)));
        JsonNode root;
        try{root=get(geocodingUrl+"?count=1&language=en&format=json&name="+URLEncoder.encode(q,StandardCharsets.UTF_8));}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalArgumentException("Weather service not reachable");}
        catch(Exception e){throw new IllegalArgumentException("Weather service not reachable");}
        return parsePlace(root).orElseThrow(()->new IllegalArgumentException("Weather location not found: "+q));
    }
    static Optional<Place> parsePlace(JsonNode root){
        JsonNode hit=root.path("results").path(0);
        if(!hit.path("latitude").isNumber()||!hit.path("longitude").isNumber()||!hit.path("name").isTextual())return Optional.empty();
        String name=hit.path("name").asText();
        if(hit.path("country_code").isTextual())name+=", "+hit.path("country_code").asText();
        return Optional.of(place(name,hit.path("latitude").asDouble(),hit.path("longitude").asDouble()));
    }
    static Place place(String name,double latitude,double longitude){
        if(latitude<-90||latitude>90||longitude<-180||longitude>180)throw new IllegalArgumentException("Invalid coordinates");
        return new Place(name.length()>80?name.substring(0,80):name,latitude,longitude);
    }
    static void validate(Place p){
        if(p!=null&&!p.none())place(p.name()==null||p.name().isBlank()?"?":p.name(),p.latitude(),p.longitude());
    }

    /** The cached reading for a place; starts a background refresh when it is missing or older than 15 minutes. */
    public Optional<Current> current(Place place){
        if(place==null||place.none())return Optional.empty();
        String key=String.format(Locale.ROOT,"%.2f,%.2f",place.latitude(),place.longitude());
        long now=clock.instant().getEpochSecond();
        Current c=cache.get(key);
        if(polling&&(c==null||now-c.fetchedAt()>=REFRESH_SECONDS)&&loading.add(key))
            Thread.ofVirtual().name("weather").start(()->{try{
                var reading=fetch(place);
                reading.ifPresent(r->cache.put(key,r));
                if(reading.isEmpty()&&failing.add(key))log.warn("Weather for {} could not be fetched",place.name());
                if(reading.isPresent()&&failing.remove(key))log.info("Weather for {} available again",place.name());
            }finally{loading.remove(key);}});
        return c!=null&&now-c.fetchedAt()<KEEP_SECONDS?Optional.of(c):Optional.empty();
    }
    private Optional<Current> fetch(Place p){
        try{
            return parseCurrent(get(String.format(Locale.ROOT,"%s?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day&timezone=auto",
                    forecastUrl,p.latitude(),p.longitude())),clock.instant().getEpochSecond());
        }catch(InterruptedException e){Thread.currentThread().interrupt();return Optional.empty();}
        catch(Exception e){return Optional.empty();}
    }
    static Optional<Current> parseCurrent(JsonNode root,long now){
        JsonNode c=root.path("current");
        if(!c.path("temperature_2m").isNumber()||!c.path("weather_code").canConvertToInt())return Optional.empty();
        return Optional.of(new Current(c.path("temperature_2m").asDouble(),c.path("weather_code").asInt(),c.path("is_day").asInt(1)==1,now));
    }
    private JsonNode get(String url) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("Accept","application/json").header("User-Agent","VibeDock-Server/0.1").GET().build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()!=200||response.body().length>65536)throw new IllegalStateException("HTTP "+response.statusCode());
        return json.readTree(response.body());
    }

    /** Twemoji for a WMO weather code. */
    static String emoji(int code,boolean day){
        if(code==0)return day?"☀️":"🌙";
        if(code==1)return day?"🌤️":"🌙";
        if(code==2)return "⛅";
        if(code==3)return "☁️";
        if(code==45||code==48)return "🌫️";
        if(code>=51&&code<=57)return "🌦️";
        if(code>=61&&code<=67||code>=80&&code<=82)return "🌧️";
        if(code>=71&&code<=77||code==85||code==86)return "🌨️";
        if(code>=95&&code<=99)return "⛈️";
        return "🌡️";
    }
    static String temperature(double celsius){return Math.round(celsius)+"°";}
}
