package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The ochre dot is close to the yellow of the icon; it must not merge into it. */
class BauhausHighlightTest {
    @Test void ochreDotSurvivesEveryPaletteSize() throws Exception {
        var look=new StatusImage.Look(null,Scheme.BAUHAUS,null,null);
        var image=new StatusImage("Europe/Berlin").render(ThemesImageTest.snapshot(),"192.168.1.50",look,DisplayType.M1,1);
        int dot=to565(ThemeArtwork.BAUHAUS_HIGHLIGHT);
        assertTrue(count(image.rgb565(),dot)>0);
        for(int colors:new int[]{16,32,64,128}){
            var reduced=ImagePalette.encode(image.rgb565(),240,240,colors,image.accents(),image.bars(),image.primary());
            assertTrue(count(reduced.pixels(),dot)>0,"ochre kept with "+colors+" colors");
        }
    }
    private static int to565(int rgb){return ((rgb>>19)&31)<<11|((rgb>>10)&63)<<5|((rgb>>3)&31);}
    private static int count(byte[] pixels,int value){int n=0;for(int i=0;i<pixels.length;i+=2)if(((pixels[i]&255)<<8|(pixels[i+1]&255))==value)n++;return n;}
}
