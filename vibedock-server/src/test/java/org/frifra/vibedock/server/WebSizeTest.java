package org.frifra.vibedock.server;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;

/** Web displays in the size of the browser: the fitted type, the scale, sharp drawing and SVG emoji. */
class WebSizeTest {
    @Test void fittingKeepsTheTypeOrTakesTheWindowsShape(){
        var phone=DisplayType.FIT.fitting(393,852,3);
        Assertions.assertEquals(360,phone.type().width());Assertions.assertEquals(780,phone.type().height());
        Assertions.assertEquals(3.5,phone.scale());  // 393/360 x 3 = 3.275, rounded up to quarter steps
        var tv=DisplayType.FIT.fitting(1920,1080,1);
        Assertions.assertEquals(640,tv.type().width());Assertions.assertEquals(360,tv.type().height());Assertions.assertEquals(3,tv.scale());
        var m1=DisplayType.M1.fitting(390,844,3);
        Assertions.assertSame(DisplayType.M1,m1.type());Assertions.assertEquals(5,m1.scale());
        // Never below the type's pixels, never above 16 megapixels, odd input clamped.
        Assertions.assertEquals(1,DisplayType.M1.fitting(100,100,1).scale());
        var huge=DisplayType.FIT.fitting(4096,4096,4);
        Assertions.assertTrue(huge.type().width()*huge.scale()*huge.type().height()*huge.scale()<=16_000_000);
        Assertions.assertEquals(1,DisplayType.M1.fitting(-5,99999,Double.NaN).scale(),1);
        Assertions.assertEquals(DisplayType.FIT,DisplayType.of("fit"));
        Assertions.assertFalse(DisplayType.INVENTORY.contains(DisplayType.FIT));
    }
    @Test void scaledImagesAreSharpAndFullColour() throws Exception {
        var renderer=new StatusImage("Europe/Berlin");
        var snapshot=new UsageService.Snapshot(1,0,new LinkedHashMap<>(),Map.of());
        var emoji=new java.awt.image.BufferedImage(132,132,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(var scheme:ThemeCatalog.builtins().all()){
            var look=new StatusImage.Look(emoji,scheme,null,"12°",Layout.DEFAULT);
            var image=renderer.render(snapshot,"VibeDock server v1",look,DisplayType.M1,3);
            Assertions.assertEquals(0,image.rgb565().length);
            var png=ImageIO.read(new ByteArrayInputStream(image.png()));
            Assertions.assertEquals(720,png.getWidth(),scheme.id());Assertions.assertEquals(720,png.getHeight(),scheme.id());
        }
        Assertions.assertEquals(240*240*2,renderer.render(snapshot,"1.2.3.4",new StatusImage.Look(null,Scheme.MIDNIGHT,null,null),DisplayType.M1).rgb565().length);
        Assertions.assertThrows(IllegalArgumentException.class,()->renderer.render(snapshot,"x",new StatusImage.Look(null,Scheme.MIDNIGHT,null,null),DisplayType.M1,.5));
    }
    @Test void tallScreensTakeFiveBandsSpreadOrAtTheTop() throws Exception {
        var five=new ArrayList<Layout.Band>();for(int i=0;i<5;i++)five.add(new Layout.Usage(List.of("codex")));five.add(new Layout.Context(false));
        Assertions.assertEquals(6,new Layout(five).fit(DisplayType.FIT.fitting(393,852,3).type()).bands().size());
        var six=new ArrayList<Layout.Band>(five);six.add(0,new Layout.Usage(List.of("claude")));
        Assertions.assertThrows(IllegalArgumentException.class,()->new Layout(six));
        // The JSON keeps alignTop only when set; fitting and moving the weather band keep it.
        var json=JsonCompatibility.mapper();
        Assertions.assertFalse(json.writeValueAsString(Layout.DEFAULT).contains("alignTop"));
        var top=new Layout(Layout.DEFAULT.bands(),true);
        Assertions.assertEquals(top,json.readValue(json.writeValueAsString(top),Layout.class));
        Assertions.assertTrue(top.fit(DisplayType.M1).alignTop());Assertions.assertTrue(top.withContextAtBottom().alignTop());
        // Spread: the second band far down; at the top: right below the first. The weather band stays at the bottom.
        var renderer=new StatusImage("Europe/Berlin");var snapshot=new UsageService.Snapshot(1,0,new LinkedHashMap<>(),Map.of());
        var tall=DisplayType.FIT.fitting(360,780,1).type();
        int spread=inked(renderer.render(snapshot,"x",new StatusImage.Look(null,Scheme.MIDNIGHT,null,"5°",Layout.DEFAULT),tall),100,180);
        int packed=inked(renderer.render(snapshot,"x",new StatusImage.Look(null,Scheme.MIDNIGHT,null,"5°",top),tall),100,180);
        Assertions.assertEquals(0,spread);Assertions.assertTrue(packed>0);
        Assertions.assertTrue(inked(renderer.render(snapshot,"x",new StatusImage.Look(null,Scheme.MIDNIGHT,null,"5°",top),tall),730,780)>0,"weather at the bottom");
    }
    private static int inked(StatusImage.Image image,int from,int to) throws Exception {
        var png=ImageIO.read(new ByteArrayInputStream(image.png()));int n=0;
        for(int y=from;y<to;y++)for(int x=0;x<png.getWidth();x++)if((png.getRGB(x,y)&0xffffff)!=(Scheme.MIDNIGHT.background&0xffffff))n++;
        return n;
    }
    @Test void svgEmojiAreDrawnAtAnySize(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("1f916.svg"),"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 36 36\"><circle cx=\"18\" cy=\"18\" r=\"12\" fill=\"#ff0000\"/></svg>");
        var twemoji=new Twemoji(dir.toString());
        var large=twemoji.image("🤖",300).orElseThrow();
        Assertions.assertEquals(300,large.getWidth());
        Assertions.assertEquals(0xffff0000,large.getRGB(150,150));
        Assertions.assertEquals(0,large.getRGB(5,5)>>>24,"transparent outside the circle");
        Assertions.assertEquals(List.of("1f916"),twemoji.catalog().stream().map(Twemoji.Entry::file).toList());
        var picker=ImageIO.read(new ByteArrayInputStream(twemoji.png("1f916").orElseThrow()));
        Assertions.assertEquals(Twemoji.PICKER_SIZE,picker.getWidth());
        twemoji.validate("🤖");
        Assertions.assertTrue(twemoji.image("🤖",Twemoji.MAX_SIZE+1).isEmpty());
    }
}
