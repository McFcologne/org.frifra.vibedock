package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class IconContourTest {
    private static BufferedImage face(){
        var image=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
        for(int y=2;y<14;y++)for(int x=2;x<14;x++)image.setRGB(x,y,0xffffcc00);
        image.setRGB(6,6,0xff000000);image.setRGB(9,6,0xff000000);image.setRGB(7,10,0xff000000);
        return image;
    }
    @Test void lcdFaceHasADarkContourWithoutChangingItsSizeOrTransparency(){
        var source=face();var image=StatusImage.styled(source,Scheme.HANDHELD);
        assertEquals(source.getWidth(),image.getWidth());assertEquals(source.getHeight(),image.getHeight());
        assertEquals(0xff000000|Scheme.HANDHELD.text,image.getRGB(2,7));
        assertEquals(source.getRGB(7,7),image.getRGB(7,7));
        assertEquals(source.getRGB(6,6),image.getRGB(6,6));
        for(int y=0;y<16;y++)for(int x=0;x<16;x++)assertEquals(source.getRGB(x,y)>>>24,image.getRGB(x,y)>>>24);
    }
    @Test void blueprintKeepsDarkEyesAndMouthAgainstALightFaceAndOutline(){
        var source=face();var image=StatusImage.styled(source,Scheme.BLUEPRINT);
        assertEquals(0xff000000|Scheme.BLUEPRINT.text,image.getRGB(2,7));
        int face=image.getRGB(7,7)&255,eye=image.getRGB(6,6)&255,mouth=image.getRGB(7,10)&255;
        assertTrue(face-eye>100,"dark facial features must not be washed out by a brightness floor");
        assertEquals(eye,mouth);
        for(int y=0;y<16;y++)for(int x=0;x<16;x++)assertEquals(source.getRGB(x,y)>>>24,image.getRGB(x,y)>>>24);
    }
}
