package org.frifra.vibedock.server;

import org.junit.jupiter.api.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;

/** Black-and-white panels (e-paper): the image is greyscale with raised contrast; colour displays are unchanged. */
class GreyDisplayTest {
    private final StatusImage renderer=new StatusImage("Europe/Berlin");
    private static final DisplayType EPAPER=DisplayType.reported("200x200").orElseThrow();

    @Test void onlyTheEpaperTypeIsGrey(){
        Assertions.assertTrue(EPAPER.grey());
        for(var t:DisplayType.WEB)if(t!=EPAPER)Assertions.assertFalse(t.grey(),t.id());
    }
    @Test void contrastCurve(){
        Assertions.assertEquals(0x000000,StatusImage.greyContrast(0x303030));
        Assertions.assertEquals(0xffffff,StatusImage.greyContrast(0xf0f0f0));
        Assertions.assertEquals(0xffffff,StatusImage.greyContrast(0xd8d8d8));
        int mid=StatusImage.greyContrast(0x808080)&255;
        Assertions.assertTrue(mid>100&&mid<160,"mid grey stays mid: "+mid);
        Assertions.assertEquals(0x000000,StatusImage.greyContrast(0x0000ff)&0xff0000,"saturated blue is dark");
    }
    @Test void everyPixelIsGreyInEveryScheme() throws Exception {
        var snapshot=GreyDisplayTest.snapshot();
        for(var scheme:ThemeCatalog.builtins().all())for(double scale:new double[]{1,2}){
            var look=new StatusImage.Look(null,scheme,null,"13°",Layout.DEFAULT);
            var image=ImageIO.read(new ByteArrayInputStream(renderer.render(snapshot,"192.168.1.50",look,EPAPER,scale).png()));
            for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
                int rgb=image.getRGB(x,y);int r=rgb>>16&255,g=rgb>>8&255,b=rgb&255;
                // RGB565 turns a grey into a nearly grey colour (within one step of 5 or 6 bits)
                Assertions.assertTrue(Math.abs(r-g)<=8&&Math.abs(g-b)<=8,scheme+" scale "+scale+": colour at "+x+","+y+" "+Integer.toHexString(rgb));
            }
        }
    }
    @Test void colourDisplaysKeepTheirColours() throws Exception {
        var look=new StatusImage.Look(null,Scheme.OCEAN,null,"13°",Layout.DEFAULT);
        var image=ImageIO.read(new ByteArrayInputStream(renderer.render(snapshot(),"192.168.1.50",look,DisplayType.M1,1).png()));
        boolean coloured=false;
        for(int y=0;y<240&&!coloured;y++)for(int x=0;x<240;x++){int rgb=image.getRGB(x,y);if(Math.abs((rgb>>16&255)-(rgb&255))>40){coloured=true;break;}}
        Assertions.assertTrue(coloured);
    }
    @Test void paperBackgroundBecomesPureWhite() throws Exception {
        var look=new StatusImage.Look(null,Scheme.E_INK,null,"13°",Layout.DEFAULT);
        var image=ImageIO.read(new ByteArrayInputStream(renderer.render(snapshot(),"192.168.1.50",look,EPAPER,1).png()));
        Assertions.assertTrue((image.getRGB(2,2)&0xffffff)>=0xf8f8f8,Integer.toHexString(image.getRGB(2,2)));
    }
    static UsageService.Snapshot snapshot(){return ThemesImageTest.snapshot();}
}
