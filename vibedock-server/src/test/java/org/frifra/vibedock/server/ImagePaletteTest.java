package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ImagePaletteTest {
    @Test void orderedRulesPersistAndMatchBothDimensions(@TempDir Path dir) throws Exception {
        var settings=new ImageColorSettings(dir.toString(),new JsonMapper());
        assertEquals(32,settings.get().colors(420,420));assertEquals(256,settings.get().colors(421,420));
        assertEquals(256,settings.get().colors(640,640));assertEquals(0,settings.get().colors(641,240));
        var custom=new ImageColorSettings.Mapping(List.of(new ImageColorSettings.Rule(240,240,32),new ImageColorSettings.Rule(300,400,0),new ImageColorSettings.Rule(640,640,256)));
        settings.save(custom);assertEquals(custom,new ImageColorSettings(dir.toString(),new JsonMapper()).get());
        assertEquals(32,custom.colors(240,240));assertEquals(0,custom.colors(250,320));assertEquals(256,custom.colors(320,250));
        settings.save(new ImageColorSettings.Mapping(List.of()));assertEquals(0,settings.get().colors(240,240));
        assertThrows(IllegalArgumentException.class,()->new ImageColorSettings.Rule(240,240,16));
        assertThrows(IllegalArgumentException.class,()->new ImageColorSettings.Mapping(Collections.nCopies(17,new ImageColorSettings.Rule(240,240,64))));
    }
    @Test void adaptivePaletteEscalatesOnlyAboveTheConfiguredPixelLimit() {
        int w=32,h=16;byte[] source=new byte[w*h*2];
        for(int i=0;i<w*h;i++){source[2*i]=(byte)(i>>8);source[2*i+1]=(byte)i;}
        var fixed=ImagePalette.select(source,w,h,32,List.of(),List.of(),false,0);
        assertEquals(32,fixed.colors());assertTrue(fixed.changedPercent()>40);
        var adaptive=ImagePalette.select(source,w,h,32,List.of(),List.of(),true,40);
        assertEquals(64,adaptive.colors()); // Never escalate twice, even if the target remains exceeded.
        assertEquals(32,ImagePalette.select(source,w,h,32,List.of(),List.of(),true,100).colors());
        assertEquals(64,ImagePalette.select(source,w,h,32,List.of(),List.of(),true,0).colors());
        assertEquals(256,ImagePalette.select(source,w,h,64,List.of(),List.of(),true,0).colors());
        assertEquals(0,ImagePalette.select(source,w,h,256,List.of(),List.of(),true,0).colors());
        byte[] exact=new byte[w*h*2];assertEquals(32,ImagePalette.select(exact,w,h,32,List.of(),List.of(),true,0).colors());
        assertEquals(1,ImagePalette.changedPixels(new byte[]{0,0,0,0},new byte[]{0,1,0,0}));
        assertTrue(new ImageColorSettings.Mapping(List.of(),null).adaptive());
        assertEquals(40,ImageQuality.corridor(20));assertEquals(50,ImageQuality.corridor(51));assertEquals(45,ImageQuality.corridor(44.2));

    }
    @Test void identicalImagesReuseMetricsButWeatherPixelsAndSettingsInvalidateTheCache() {
        var cache=new ImagePalette.Cache();byte[] source=new byte[32*16*2];
        var first=cache.select(source,32,16,32,List.of(new Rectangle(1,1,4,4)),List.of(),true,40);
        assertSame(first,cache.select(source.clone(),32,16,32,List.of(new Rectangle(1,1,4,4)),List.of(),true,40));
        assertEquals(1,cache.computations());
        source[2*(2*32+2)]=(byte)255; // changed weather pixel, without changing dimensions or theme
        cache.select(source,32,16,32,List.of(new Rectangle(1,1,4,4)),List.of(),true,40);assertEquals(2,cache.computations());
        cache.select(source,32,16,32,List.of(new Rectangle(1,1,4,4)),List.of(),true,30);assertEquals(3,cache.computations());
        cache.select(source,32,16,32,List.of(new Rectangle(2,1,4,4)),List.of(),true,30);assertEquals(4,cache.computations());
        for(int i=0;i<100;i++){source[0]=(byte)i;cache.select(source,32,16,32,List.of(),List.of(),true,40);}
        assertTrue(cache.size()<=ImagePalette.Cache.MAX_ENTRIES);assertTrue(cache.bytes()<=ImagePalette.Cache.MAX_BYTES);
    }
    @Test void packedRowsRoundTripAndStayWithinOneSharedBudget() {
        for(int count:List.of(32,64,256)){
            int w=17,h=16;byte[] source=new byte[w*h*2];for(int p=0;p<w*h;p++){int color=(p*947)&65535;source[2*p]=(byte)(color>>8);source[2*p+1]=(byte)color;}
            var encoded=ImagePalette.encode(source,w,h,count,List.of());int bits=encoded.bits(),stride=(w*bits+7)/8,offset=2+encoded.colorsUsed()*2;
            assertEquals(offset+stride*h,encoded.body().length);Set<Integer> colors=new HashSet<>();
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int index=0;for(int b=0;b<bits;b++){int pos=x*bits+b;index=(index<<1)|((encoded.body()[offset+y*stride+pos/8]>>(7-pos%8))&1);}
                int at=2*(y*w+x);assertEquals(encoded.body()[2+index*2],encoded.pixels()[at]);assertEquals(encoded.body()[2+index*2+1],encoded.pixels()[at+1]);
                colors.add((encoded.pixels()[at]&255)<<8|(encoded.pixels()[at+1]&255));
            }
            assertTrue(colors.size()<=count);assertArrayEquals(encoded.body(),ImagePalette.encode(source,w,h,count,List.of()).body());
        }
    }
    @Test void smallYellowWeatherAccentSurvivesEvenAt32Colors() {
        int w=240,h=240;byte[] source=new byte[w*h*2];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int color=(((x*31/w)<<11)|((y*63/h)<<5)|((x+y)*31/(w+h)));int at=2*(y*w+x);source[at]=(byte)(color>>8);source[at+1]=(byte)color;}
        for(int y=190;y<196;y++)for(int x=12;x<18;x++){int at=2*(y*w+x);source[at]=(byte)0xff;source[at+1]=(byte)0xe0;}
        var encoded=ImagePalette.encode(source,w,h,32,List.of(new Rectangle(10,188,24,24)));
        int at=2*(192*w+14);assertEquals((byte)0xff,encoded.pixels()[at]);assertEquals((byte)0xe0,encoded.pixels()[at+1]);
    }
    @Test void framesAlreadyWithinBudgetArePixelIdentical() {
        byte[] source=new byte[32*16*2];for(int i=0;i<source.length;i+=2){source[i]=(byte)(i%3==0?255:0);source[i+1]=(byte)(i%3==0?224:0);}
        for(int colors:List.of(32,64,256))assertArrayEquals(source,ImagePalette.encode(source,32,16,colors,List.of()).pixels());
    }
    @Test void hifiVuMeterKeepsItsYellowAndRedEndSegments() throws Exception {
        var theme=Scheme.HI_FI;var icon=ThemeIcons.image(new Design.Icon("theme-default",null,null),theme,44).orElseThrow();
        var weather=new Twemoji(Twemoji.locate(null).toString()).image("⛅",24).orElseThrow();
        var rendered=new StatusImage("Europe/Berlin").render(ImageQuality.reference(0),"192.168.1.50",new StatusImage.Look(icon,theme,weather,"13°"));
        int red=StatusImage.rgb565(0xd47c5c);assertTrue(rendered.primary().contains(red),"Rare red VU segment is a protected index paint");
        var selected=ImagePalette.select(rendered.rgb565(),240,240,32,rendered.accents(),rendered.bars(),true,50,16,rendered.primary());
        int found=0;
        for(var area:rendered.bars())for(int y=area.y;y<area.y+area.height;y++)for(int x=area.x;x<area.x+area.width;x++){
            int at=2*(y*240+x),rgb=(rendered.rgb565()[at]&255)<<8|(rendered.rgb565()[at+1]&255);
            if(rendered.primary().contains(rgb)){assertEquals(rendered.rgb565()[at],selected.image().pixels()[at]);assertEquals(rendered.rgb565()[at+1],selected.image().pixels()[at+1]);}
            if(rgb==red)found++;
        }
        assertTrue(found>20);
    }
    @Test void solarizedIconKeepsBothTurquoiseIndexPaintsInsteadOfMergingThemWithBackground() throws Exception {
        var theme=Scheme.SOLARIZED;var icon=ThemeIcons.image(new Design.Icon("theme-default",null,null),theme,44).orElseThrow();
        int bright=StatusImage.rgb565(0x279ca8),dark=StatusImage.rgb565(0x167687);
        assertTrue(StatusImage.iconIndexColors(icon).contains(bright));assertTrue(StatusImage.iconIndexColors(icon).contains(dark));
        var rendered=new StatusImage("Europe/Berlin").render(ImageQuality.reference(0),"192.168.1.50",new StatusImage.Look(icon,theme,null,null));
        assertTrue(rendered.primary().contains(bright));assertTrue(rendered.primary().contains(dark));
        var selected=ImagePalette.select(rendered.rgb565(),240,240,32,rendered.accents(),rendered.bars(),true,50,16,rendered.primary());
        int found=0;for(int at=0;at<rendered.rgb565().length;at+=2){int rgb=(rendered.rgb565()[at]&255)<<8|(rendered.rgb565()[at+1]&255);
            if(rgb==bright||rgb==dark){assertEquals(rendered.rgb565()[at],selected.image().pixels()[at]);assertEquals(rendered.rgb565()[at+1],selected.image().pixels()[at+1]);found++;}}
        assertTrue(found>30);
    }
    @Test void starshipPurpleAndEveryPresentThemeBaseColorRemainExact() throws Exception {
        var theme=Scheme.STARSHIP;var rendered=new StatusImage("Europe/Berlin").render(ImageQuality.reference(0),"192.168.1.50",new StatusImage.Look(null,theme,null,null),DisplayType.of("240x280"));
        int purple=((theme.muted>>19)&31)<<11|((theme.muted>>10)&63)<<5|((theme.muted>>3)&31);int found=0;
        for(int budget:List.of(16,32,64,256)){
            var reduced=ImagePalette.encode(rendered.rgb565(),240,280,budget,rendered.accents(),rendered.bars(),rendered.primary());
            for(int at=0;at<rendered.rgb565().length;at+=2){int rgb=(rendered.rgb565()[at]&255)<<8|(rendered.rgb565()[at+1]&255);
                if(rendered.primary().contains(rgb)){assertEquals(rendered.rgb565()[at],reduced.pixels()[at]);assertEquals(rendered.rgb565()[at+1],reduced.pixels()[at+1]);}
                if(rgb==purple)found++;
            }
        }
        assertTrue(found>100,"LCARS purple stripe is present in the fixture");
    }
    @Test void unusedColorsAreRemovedWithoutLossAndAutomaticLowPaletteCanBeSelected() {
        byte[] pixels=new byte[240*240*2];for(int p=0;p<pixels.length;p+=2){pixels[p]=(byte)(p%4==0?255:0);pixels[p+1]=(byte)(p%4==0?224:0);}
        var encoded=ImagePalette.encode(pixels,240,240,32,List.of());assertEquals(2,encoded.colorsUsed());assertEquals(1,encoded.bits());
        assertEquals(2+4+7200,encoded.body().length);assertArrayEquals(pixels,encoded.pixels());
        var low=ImagePalette.select(pixels,240,240,32,List.of(),List.of(),true,50,16);assertEquals(16,low.colors());assertArrayEquals(pixels,low.image().pixels());
    }
    @Test void activeAndInactiveBarPaintsStayExactEvenWhenTheirColorsAreClose() {
        int w=240,h=240;byte[] source=new byte[w*h*2];
        for(int i=0;i<w*h;i++){source[2*i]=(byte)(i>>8);source[2*i+1]=(byte)i;}
        for(int y=30;y<40;y++)for(int x=60;x<180;x++){int at=2*(y*w+x),rgb=x<120?0x8410:0x94b2;source[at]=(byte)(rgb>>8);source[at+1]=(byte)rgb;}
        var reduced=ImagePalette.encode(source,w,h,32,List.of(),List.of(new Rectangle(60,30,120,10)));
        for(int x:List.of(80,160)){int at=2*(35*w+x);assertEquals(source[at],reduced.pixels()[at]);assertEquals(source[at+1],reduced.pixels()[at+1]);}
    }
    @Test void deltaUsesHalfThePaletteOnlyWhenEverySpanColorFitsExactly() {
        byte[] before=new byte[32*16*2],after=before.clone();
        for(int y=0;y<2;y++)for(int x=0;x<16;x++){int at=2*(y*32+x);after[at]=(byte)0xab;after[at+1]=(byte)0xcd;}
        var raw=ImageDelta.encode(before,16,before,after,32,16).orElseThrow();var indexed=ImagePalette.delta(raw,32);
        assertEquals(1,indexed.bits());assertEquals(raw.check(),indexed.patch().check());
        assertEquals(2+2+8+8+4+2,indexed.patch().bytes().length);
        byte[] bytes=indexed.patch().bytes();assertEquals(1,bytes[1]);assertEquals((byte)0xab,bytes[2]);assertEquals((byte)0xcd,bytes[3]);
        assertArrayEquals(Arrays.copyOfRange(raw.bytes(),0,16),Arrays.copyOfRange(bytes,4,20));
        assertEquals(ImageDelta.hex(ImageDelta.fnv(bytes,0,bytes.length)),indexed.patch().payload());
        for(int y=0;y<2;y++)for(int x=0;x<32;x++){int at=2*(y*32+x);after[at]=0;after[at+1]=(byte)x;}
        raw=ImageDelta.encode(before,16,before,after,32,16).orElseThrow();
        assertEquals(5,ImagePalette.delta(raw,32).bits()); // 31 span colors cannot fit a 16-color palette.
        assertEquals(5,ImagePalette.delta(raw,64).bits());
        assertEquals(5,ImagePalette.delta(raw,256).bits());
    }
}
