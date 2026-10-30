package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** New defaults, explicitly opted into: inspect every icon through the real theme's icon filters and placement. */
class ThemeIconImageTest {
    @Test void newDefaultsRenderInEveryThemeOnSquareAndNarrowPanels() throws Exception {
        var renderer=new StatusImage("Europe/Berlin");var emoji=new Twemoji(Twemoji.locate(null).toString());
        for(var type:java.util.List.of(DisplayType.M1,DisplayType.of("135x240"))){
            var all=ThemeCatalog.builtins().all();
            for(int from=0;from<all.size();from+=20){
                int until=Math.min(from+20,all.size()),k=2,cell=(type.width()+16)*k,label=24*k,gap=16*k,rows=(until-from+4)/5;
                var sheet=new BufferedImage(gap+5*cell,gap+rows*(type.height()*k+label+gap),BufferedImage.TYPE_INT_RGB);var g=sheet.createGraphics();
                try{
                    g.setColor(new Color(0xf4f5f7));g.fillRect(0,0,sheet.getWidth(),sheet.getHeight());
                    g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13*k));
                    for(int i=from;i<until;i++){
                        var scheme=all.get(i);var icon=ThemeIcons.image(new Design.Icon("theme-default",null,null),scheme,Twemoji.SIZE*k).orElseThrow(()->new AssertionError(scheme.id()));
                        assertTrue(java.util.stream.IntStream.range(0,icon.getHeight()).anyMatch(y->java.util.stream.IntStream.range(0,icon.getWidth()).anyMatch(x->(icon.getRGB(x,y)>>>24)>0)),scheme.id());
                        var look=new StatusImage.Look(icon,scheme,emoji.image("⛅",24*k).orElseThrow(),"13°",Layout.DEFAULT);
                        var picture=ImageIO.read(new ByteArrayInputStream(renderer.render(LayoutTest.snapshot(0),"192.168.1.50",look,type,k).png()));
                        int x=gap+(i-from)%5*cell,y=gap+(i-from)/5*(type.height()*k+label+gap);
                        g.setColor(new Color(0x242c37));g.drawString(scheme.label,x,y+17*k);g.drawImage(picture,x,y+label,null);
                    }
                }finally{g.dispose();}
                Files.createDirectories(Path.of("target"));ImageIO.write(sheet,"png",Path.of("target/theme-icons-"+type.id()+"-"+(from/20+1)+".png").toFile());
            }
        }
    }
}
