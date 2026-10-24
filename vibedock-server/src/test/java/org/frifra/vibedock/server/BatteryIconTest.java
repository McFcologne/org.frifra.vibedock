package org.frifra.vibedock.server;

import org.junit.jupiter.api.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;

/** The battery icon of displays on their battery: only at the top right, nothing else of the image changes. */
class BatteryIconTest {
    private final StatusImage renderer=new StatusImage("Europe/Berlin");
    private final UsageService.Snapshot snapshot=snapshot("ok");

    @Test void theIconChangesOnlyTheTopRightCornerInEveryScheme() throws Exception {
        for(var scheme:Scheme.values())for(var layout:List.of(Layout.DEFAULT,halfAndCompact())){
            var plain=image(scheme,layout,null,snapshot);
            for(Integer level:new Integer[]{null,10,60,100}){
                var battery=image(scheme,layout,new StatusImage.Battery(level),snapshot);
                int changed=0;
                for(int y=0;y<240;y++)for(int x=0;x<240;x++)if(plain.getRGB(x,y)!=battery.getRGB(x,y)){
                    changed++;
                    Assertions.assertTrue(x>=150&&y<24,scheme+" "+layout.bands().size()+" bands, level "+level+": pixel changed at "+x+","+y);
                }
                Assertions.assertTrue(changed>=20,scheme+": the icon is drawn");
            }
        }
    }
    @Test void aStatusMovesLeftOfTheIconInsteadOfUnderIt() throws Exception {
        var limited=snapshot("rate_limited");
        for(var scheme:Scheme.values()){
            var plain=image(scheme,Layout.DEFAULT,null,limited);var battery=image(scheme,Layout.DEFAULT,new StatusImage.Battery(40),limited);
            for(int y=0;y<240;y++)for(int x=0;x<240;x++)if(plain.getRGB(x,y)!=battery.getRGB(x,y))
                Assertions.assertTrue(y<24,scheme+": only the heading row changes, at "+x+","+y);
        }
    }
    @Test void theLevelShowsInTheIcon() throws Exception {
        Assertions.assertNotEquals(fingerprint(image(Scheme.MIDNIGHT,Layout.DEFAULT,new StatusImage.Battery(20),snapshot)),
                fingerprint(image(Scheme.MIDNIGHT,Layout.DEFAULT,new StatusImage.Battery(80),snapshot)));
        Assertions.assertThrows(IllegalArgumentException.class,()->new StatusImage.Battery(101));
    }
    @Test void theDeviceReportsBatteryOperationWithOrWithoutItsLevel(){
        Assertions.assertEquals(Optional.of(new StatusImage.Battery(null)),DevicePower.parse("battery"));
        Assertions.assertEquals(Optional.of(new StatusImage.Battery(60)),DevicePower.parse("battery;level=60"));
        Assertions.assertEquals(Optional.of(new StatusImage.Battery(null)),DevicePower.parse("battery;level=900"));
        Assertions.assertEquals(Optional.empty(),DevicePower.parse(null));
        Assertions.assertEquals(Optional.empty(),DevicePower.parse("usb"));
        var power=new DevicePower();power.report("a","battery;level=40");
        Assertions.assertEquals(new StatusImage.Battery(40),power.battery("a"));
        power.report("a",null);Assertions.assertNull(power.battery("a"));
    }
    @Test void malformedPowerHeadersAreIgnored(){
        for(String header:new String[]{""," ",";",";;"," ; ; ","unknown"})
            Assertions.assertEquals(Optional.empty(),DevicePower.parse(header),header);
    }
    private BufferedImage image(Scheme scheme,Layout layout,StatusImage.Battery battery,UsageService.Snapshot snap) throws Exception {
        var look=new StatusImage.Look(null,scheme,null,"13°",layout,battery);
        return ImageIO.read(new ByteArrayInputStream(renderer.render(snap,"192.168.1.50",look,DisplayType.M1).png()));
    }
    private static int fingerprint(BufferedImage image){int h=0;for(int y=0;y<24;y++)for(int x=150;x<240;x++)h=31*h+image.getRGB(x,y);return h;}
    private static Layout halfAndCompact(){
        return new Layout(List.of(new Layout.Usage(List.of("codex","claude"),false),new Layout.Usage(List.of("claude"),true),new Layout.Context(false)));
    }
    private static UsageService.Snapshot snapshot(String status){
        long t=1_790_000_000L;
        var p=new UsageService.Provider(status,t,new ProviderClient.Window(5,t+9000,5*3600),new ProviderClient.Window(14,t+86400,7*86400));
        return new UsageService.Snapshot(1,t,new LinkedHashMap<>(Map.of("codex",p,"claude",p)),Map.of("codex","Codex","claude","Claude"));
    }
}
