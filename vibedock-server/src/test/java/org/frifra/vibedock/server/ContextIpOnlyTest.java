package org.frifra.vibedock.server;

import org.junit.jupiter.api.*;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.*;

/** The context band without weather and mood: only the device IP row stays and the usage bands get the room. */
class ContextIpOnlyTest {
    private static final DisplayType EPAPER=DisplayType.reported("200x200").orElseThrow();
    private static Layout twoFull(boolean ipOnly){
        return new Layout(List.of(new Layout.Usage(List.of("codex")),new Layout.Usage(List.of("claude")),new Layout.Context(false,ipOnly)));
    }
    @Test void aSmallDisplayShowsTwoFullBandsWhenTheContextBandIsOnlyTheIp(){
        Assertions.assertEquals(Layout.IP_HEIGHT,twoFull(true).context().height());
        Assertions.assertEquals(1,twoFull(false).fit(EPAPER).bands().stream().filter(Layout.Usage.class::isInstance).count(),"with weather and mood only one band fits 200 px");
        var fitted=twoFull(true).fit(EPAPER);
        Assertions.assertEquals(2,fitted.bands().stream().filter(Layout.Usage.class::isInstance).count());
        Assertions.assertTrue(fitted.bands().stream().filter(Layout.Usage.class::isInstance).noneMatch(b->((Layout.Usage)b).compact()));
    }
    @Test void ipOnlyIsKeptInTheJsonAndTheDefaultIsNotWritten() throws Exception {
        var mapper=JsonCompatibility.mapper();
        String json=mapper.writeValueAsString(twoFull(true));
        Assertions.assertTrue(json.contains("\"ipOnly\":true"),json);
        Assertions.assertEquals(twoFull(true),mapper.readValue(json,Layout.class));
        Assertions.assertFalse(mapper.writeValueAsString(twoFull(false)).contains("ipOnly"));
        Assertions.assertEquals(twoFull(false),mapper.readValue(mapper.writeValueAsString(twoFull(false)),Layout.class));
    }
    @Test void theImageHasTwoFullBandsAndTheIpRowAndNoWeather() throws Exception {
        var renderer=new StatusImage("Europe/Berlin");
        var snapshot=ThemesImageTest.snapshot();
        var scheme=Scheme.WINDOWS_XP;
        var weather=new Twemoji(Twemoji.locate(null).toString()).image("⛅",24).orElseThrow();
        byte[] with=renderer.render(snapshot,"192.168.1.50",new StatusImage.Look(null,scheme,weather,"13°",twoFull(false)),EPAPER).png();
        byte[] without=renderer.render(snapshot,"192.168.1.50",new StatusImage.Look(null,scheme,weather,"13°",twoFull(true)),EPAPER).png();
        var a=ImageIO.read(new ByteArrayInputStream(with)).getSubimage(0,0,200,200);
        var b=ImageIO.read(new ByteArrayInputStream(without));
        // The second full band reaches into the rows that held the weather before: they differ
        int different=0;for(int y=100;y<176;y++)for(int x=0;x<200;x++)if(a.getRGB(x,y)!=b.getRGB(x,y))different++;
        Assertions.assertTrue(different>500,"second full band drawn: "+different);
        // The IP row: dark pixels in the last 22 rows
        int ink=0;for(int y=178;y<200;y++)for(int x=0;x<150;x++)if((b.getRGB(x,y)&255)<100)ink++;
        Assertions.assertTrue(ink>20,"IP text in the last row: "+ink);
    }
    @Test void theUpdateTimeStandsRightOfTheIpWhenThereIsRoom() throws Exception {
        var renderer=new StatusImage("Europe/Berlin");
        var look=new StatusImage.Look(null,Scheme.E_INK,null,null,twoFull(true));
        var wide=ImageIO.read(new ByteArrayInputStream(renderer.render(ThemesImageTest.snapshot(),"10.0.0.5",look,DisplayType.M1).png()));
        int ink=0;for(int y=240-20;y<240;y++)for(int x=150;x<240;x++)if((wide.getRGB(x,y)&255)<160)ink++;
        Assertions.assertTrue(ink>15,"time at the right of a short IP: "+ink);
        // On the 200 px e-paper the long IP leaves room for the time only
        var epaper=ImageIO.read(new ByteArrayInputStream(renderer.render(ThemesImageTest.snapshot(),"192.168.123.185",look,EPAPER).png()));
        int right=0;for(int y=178;y<200;y++)for(int x=150;x<200;x++)if((epaper.getRGB(x,y)&255)<160)right++;
        Assertions.assertTrue(right>5,"time at the right of the long IP: "+right);
    }
}
