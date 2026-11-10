package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Split band: the outer edges of the two half-width windows are those of the full-width window, and a joint is visible. */
class SplitBandGeometryTest {
    private static BufferedImage render(String scheme,Layout layout) throws Exception {
        var look=new StatusImage.Look(null,Scheme.parse(scheme),null,null,layout,null);
        var image=new StatusImage("Europe/Berlin").render(LayoutTest.snapshot(0),"192.168.1.2",look,DisplayType.M1,1);
        return ImageIO.read(new ByteArrayInputStream(image.png()));
    }
    /** First and last column of the row that differ from the background at its left edge. */
    private static int[] extent(BufferedImage image,int y){
        int background=image.getRGB(0,y),first=-1,last=-1;
        for(int x=0;x<image.getWidth();x++)if(image.getRGB(x,y)!=background){if(first<0)first=x;last=x;}
        return new int[]{first,last};
    }
    @Test void outerEdgesAreFlushWithTheFullWidthWindow() throws Exception {
        for(String scheme:List.of("classic_windows","windows_11","classic_mac","macos")){
            var full=render(scheme,new Layout(List.of(new Layout.Usage(List.of("codex")),new Layout.Context(true))));
            var split=render(scheme,new Layout(List.of(new Layout.Usage(List.of("codex","claude")),new Layout.Context(true))));
            int y=60;
            assertArrayEquals(extent(full,y),extent(split,y),scheme+": outer edges of the split band");
        }
    }
    @Test void roundedWindowsKeepAVisibleJoint() throws Exception {
        for(String scheme:List.of("windows_11","macos")){
            var split=render(scheme,new Layout(List.of(new Layout.Usage(List.of("codex","claude")),new Layout.Context(true))));
            int y=60,middle=split.getWidth()/2,background=split.getRGB(0,y);
            boolean joint=false;
            for(int x=middle-2;x<=middle+1;x++)joint|=split.getRGB(x,y)==background;
            assertTrue(joint,scheme+": the background shows between the two windows");
        }
    }
}
