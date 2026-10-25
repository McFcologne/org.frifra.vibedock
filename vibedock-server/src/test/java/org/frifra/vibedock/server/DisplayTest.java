package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DisplayTest {
    private final ObjectMapper json=JsonCompatibility.mapper();
    private static final WeatherService.Place COLOGNE=new WeatherService.Place("Cologne, DE",50.94,6.96);

    @Test void parsesOpenMeteoAnswers() throws Exception {
        var place=WeatherService.parsePlace(json.readTree("{\"results\":[{\"name\":\"Cologne\",\"latitude\":50.93,\"longitude\":6.95,\"country_code\":\"DE\"}]}")).orElseThrow();
        assertEquals("Cologne, DE",place.name());assertEquals(50.93,place.latitude());
        assertTrue(WeatherService.parsePlace(json.readTree("{\"generationtime_ms\":0.5}")).isEmpty());
        var current=WeatherService.parseCurrent(json.readTree("{\"current\":{\"temperature_2m\":11.6,\"weather_code\":61,\"is_day\":0}}"),100).orElseThrow();
        assertEquals(61,current.code());assertFalse(current.day());assertEquals("12°",WeatherService.temperature(current.temperature()));
        assertEquals("-3°",WeatherService.temperature(-2.6));
        assertTrue(WeatherService.parseCurrent(json.readTree("{\"current\":{}}"),100).isEmpty());
    }
    @Test void mapsWeatherCodesToEmoji() {
        assertEquals("☀️",WeatherService.emoji(0,true));assertEquals("🌙",WeatherService.emoji(0,false));
        assertEquals("🌧️",WeatherService.emoji(63,true));assertEquals("🌨️",WeatherService.emoji(73,true));
        assertEquals("⛈️",WeatherService.emoji(95,true));assertEquals("🌡️",WeatherService.emoji(42,true));
    }
    @Test void acceptsCoordinatesWithoutLookup() {
        var weather=new WeatherService(null,json,Clock.systemUTC(),"http://invalid","http://invalid");
        var place=weather.locate("50.94, 6.96");
        assertEquals(50.94,place.latitude());assertEquals(6.96,place.longitude());
        assertNull(weather.locate(""));assertSame(WeatherService.Place.NONE,weather.locate("-"));
        assertThrows(IllegalArgumentException.class,()->weather.locate("95, 6.96"));
    }
    @Test void devicesInheritSchemeAndWeatherUnlessOverridden(@TempDir Path dir) throws Exception {
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        registry.paired("vibedock-a1b2c3d4","Desk","a".repeat(64),"192.168.1.20",80);
        assertEquals(Scheme.MIDNIGHT,registry.designFor("vibedock-a1b2c3d4").scheme());
        assertNull(registry.designFor("vibedock-a1b2c3d4").weather());
        registry.configureDesign(new Design(Scheme.OCEAN,"🚀",COLOGNE));
        var shown=registry.designFor("vibedock-a1b2c3d4");
        assertEquals(Scheme.OCEAN,shown.scheme());assertEquals(COLOGNE,shown.weather());assertEquals("🚀",shown.mood());
        registry.setDesign("vibedock-a1b2c3d4",new Design(Scheme.SUNSET,"🚀",null));
        shown=registry.designFor("vibedock-a1b2c3d4");
        assertEquals(Scheme.SUNSET,shown.scheme());assertNull(shown.weather());
        var reloaded=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        assertEquals(Scheme.SUNSET,reloaded.designFor("vibedock-a1b2c3d4").scheme());assertEquals(COLOGNE,reloaded.defaultDesign().weather());
        registry.configureDesign(new Design(Scheme.FOREST,"",null));
        assertNull(registry.defaultDesign().weather());assertEquals("",registry.defaultDesign().mood());
    }
    @Test void schemeChangesTheTitleColour() throws Exception {
        var snapshot=new UsageService.Snapshot(1,1800000000,Map.of(
                "codex",new UsageService.Provider("unconfigured",null,null,null),"claude",new UsageService.Provider("unconfigured",null,null,null)));
        var renderer=new StatusImage("Europe/Berlin");
        var midnight=ImageIO.read(new ByteArrayInputStream(renderer.render(snapshot,"x").png()));
        var sunset=ImageIO.read(new ByteArrayInputStream(renderer.render(snapshot,"x",new StatusImage.Look(null,Scheme.SUNSET,null,"12°")).png()));
        assertNotEquals(colour(midnight),colour(sunset));
    }
    private static int colour(java.awt.image.BufferedImage image){
        int best=0;
        for(int y=8;y<26;y++)for(int x=10;x<70;x++){int rgb=image.getRGB(x,y)&0xffffff;if(Integer.bitCount(rgb)>Integer.bitCount(best))best=rgb;}
        return best;
    }
    @Test void barColourMovesFromGreenOverYellowToRed(){
        org.junit.jupiter.api.Assertions.assertEquals(0x34c759,StatusImage.barColor(100));
        org.junit.jupiter.api.Assertions.assertEquals(0x34c759,StatusImage.barColor(60));
        org.junit.jupiter.api.Assertions.assertEquals(0xffcc00,StatusImage.barColor(32));
        org.junit.jupiter.api.Assertions.assertEquals(0xff9f0a,StatusImage.barColor(20));
        org.junit.jupiter.api.Assertions.assertEquals(0xff453a,StatusImage.barColor(8));
        org.junit.jupiter.api.Assertions.assertEquals(0xff453a,StatusImage.barColor(0));
        int between=StatusImage.barColor(26);
        org.junit.jupiter.api.Assertions.assertTrue(((between>>8)&255)<0xcc&&((between>>8)&255)>0x9f,"orange-yellow between the stops");
        org.junit.jupiter.api.Assertions.assertEquals(0x34c759,StatusImage.barColor(Double.NaN));
    }
}
