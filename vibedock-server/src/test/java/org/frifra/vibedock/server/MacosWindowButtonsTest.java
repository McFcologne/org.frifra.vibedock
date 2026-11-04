package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The three window buttons are a few pixels of colour; every palette size must keep them. */
class MacosWindowButtonsTest {
    @Test void redYellowAndGreenSurviveEveryPaletteSize() throws Exception {
        var look=new StatusImage.Look(null,Scheme.MACOS,null,null);
        var image=new StatusImage("Europe/Berlin").render(ThemesImageTest.snapshot(),"192.168.1.50",look,DisplayType.M1,1);
        int[] lights={0xff5f57,0xfebc2e,0x28c840};
        for(int rgb:lights)assertTrue(count(image.rgb565(),to565(rgb))>0,"rendered "+Integer.toHexString(rgb));
        for(int colors:new int[]{16,32,64,128}){
            var reduced=ImagePalette.encode(image.rgb565(),240,240,colors,image.accents(),image.bars(),image.primary());
            for(int rgb:lights)assertTrue(count(reduced.pixels(),to565(rgb))>0,Integer.toHexString(rgb)+" kept with "+colors+" colors");
        }
    }
    private static int to565(int rgb){return ((rgb>>19)&31)<<11|((rgb>>10)&63)<<5|((rgb>>3)&31);}
    private static int count(byte[] pixels,int value){int n=0;for(int i=0;i<pixels.length;i+=2)if(((pixels[i]&255)<<8|(pixels[i+1]&255))==value)n++;return n;}
}
