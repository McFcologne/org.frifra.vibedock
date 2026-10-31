package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Same-runtime pixel comparison: frozen 0.3.x drawing, palette and artwork, independent of catalog definitions. */
class ThemeRenderingCompatibilityTest {
    record Case(String id,UsageService.Snapshot data,Layout layout,boolean icons,Integer battery) {}
    private static List<Case> cases(){
        var split=new Layout(List.of(new Layout.Usage(List.of("codex","claude")),new Layout.Context(true),new Layout.Usage(List.of("claude"),true)));
        var compact=new Layout(List.of(new Layout.Usage(List.of("codex"),true),new Layout.Usage(List.of("claude"),true),
                new Layout.Usage(List.of("codex"),true),new Layout.Context(false)),true);
        return List.of(new Case("normal",LayoutTest.snapshot(0),Layout.DEFAULT,true,null),
                new Case("limited",LayoutTest.snapshot(1),Layout.DEFAULT,true,20),
                new Case("partial",LayoutTest.snapshot(2),Layout.DEFAULT,false,null),
                new Case("empty",new UsageService.Snapshot(1,1800000000,Map.of()),Layout.DEFAULT,false,null),
                new Case("split-context-middle",LayoutTest.snapshot(1),split,true,60),
                new Case("compact-top",LayoutTest.snapshot(0),compact,true,100));
    }
    @Test void everyBuiltinKeepsAllPixelsAcrossPanelsLayoutsStatesAndWebScaling() throws Exception {
        var before=new BaselineStatusImage("Europe/Berlin");var after=new StatusImage("Europe/Berlin");
        var emoji=new Twemoji(Twemoji.locate(null).toString());
        for(var original:BaselineScheme.values()){
            var scheme=Scheme.parse(original.id());
            for(var type:DisplayType.INVENTORY)for(var example:cases()){
                compare(before,after,original,scheme,type,1,example,emoji);
            }
            // A fractional high-DPI scale and a large fitted browser, with the same SVG rasterisation on both sides.
            compare(before,after,original,scheme,DisplayType.M1,1.5,cases().get(0),emoji);
            compare(before,after,original,scheme,DisplayType.FIT,2,cases().get(4),emoji);
        }
    }
    private static void compare(BaselineStatusImage before,StatusImage after,BaselineScheme original,Scheme scheme,
                                DisplayType type,double scale,Case example,Twemoji emoji) throws Exception {
        var mood=example.icons()?emoji.image("😎",(int)Math.round(Twemoji.SIZE*scale)).orElseThrow():null;
        var weather=example.icons()?emoji.image("⛅",(int)Math.round(24*scale)).orElseThrow():null;
        var oldLook=new BaselineStatusImage.Look(mood,original,weather,example.icons()?"13°":null,example.layout(),
                example.battery()==null?null:new BaselineStatusImage.Battery(example.battery()));
        var look=new StatusImage.Look(mood,scheme,weather,example.icons()?"13°":null,example.layout(),
                example.battery()==null?null:new StatusImage.Battery(example.battery()));
        var expected=before.render(example.data(),"192.168.123.193",oldLook,type,scale);
        var actual=after.render(example.data(),"192.168.123.193",look,type,scale);
        String context=original.id()+" / "+type.id()+" / "+scale+" / "+example.id();
        assertArrayEquals(expected.rgb565(),actual.rgb565(),context+" wire pixels");
        var a=ImageIO.read(new ByteArrayInputStream(expected.png()));var b=ImageIO.read(new ByteArrayInputStream(actual.png()));
        assertEquals(a.getWidth(),b.getWidth(),context);assertEquals(a.getHeight(),b.getHeight(),context);
        assertArrayEquals(a.getRGB(0,0,a.getWidth(),a.getHeight(),null,0,a.getWidth()),
                b.getRGB(0,0,b.getWidth(),b.getHeight(),null,0,b.getWidth()),context+" PNG pixels");
    }
}
