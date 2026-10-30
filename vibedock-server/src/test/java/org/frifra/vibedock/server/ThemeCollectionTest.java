package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** The 0.4 collection uses the portable format, including its original artwork and all panel layouts. */
class ThemeCollectionTest {
    static final List<String> IDS=List.of("windows_xp","windows_7","dos","nuclear","halloween",
            "christmas","winter","cyberpunk","orbital","oscilloscope");
    @Test void collectionExportsAndInstallsWithoutBuiltinIdentityOrTwemojiArtwork(@TempDir Path dir) throws Exception {
        var library=new ThemeLibrary(dir.toString());
        assertEquals(IDS,ThemeCatalog.builtins().all().subList(35,45).stream().map(Scheme::id).toList());
        var renderer=new StatusImage("Europe/Berlin");
        for(String id:IDS){
            var source=BuiltinThemes.get(id).contents();
            var credited=ThemeCredits.prepare(source,true);
            assertEquals("Apache-2.0",credited.manifest().license());
            assertEquals(1,credited.manifest().icons().size());
            assertEquals("VibeDock contributors",credited.manifest().icons().getFirst().author());
            byte[] archive=ThemeArchive.write(credited,true,"collection.copy."+id,"Copy of "+source.manifest().name());
            assertTrue(archive.length<16*1024,"compact original vector package: "+id);
            var imported=library.install(archive,"add",null,null);
            assertEquals(ThemeDefinition.read(source),imported.scheme().drawing);
            assertFalse(imported.builtin());
            assertFalse(imported.contents().paths().contains("assets/default.svg"));
            for(var panel:DisplayType.INVENTORY){
                var layout=Layout.DEFAULT.fit(panel);
                var a=ThemeIcons.image(source,"theme",Twemoji.SIZE).orElseThrow();
                var b=ThemeIcons.image(imported.contents(),"theme",Twemoji.SIZE).orElseThrow();
                for(int state=0;state<3;state++){
                    var expected=renderer.render(LayoutTest.snapshot(state),"192.168.1.50",
                            new StatusImage.Look(a,Scheme.parse(id),null,null,layout),panel,1);
                    var actual=renderer.render(LayoutTest.snapshot(state),"192.168.1.50",
                            new StatusImage.Look(b,imported.scheme(),null,null,layout),panel,1);
                    assertArrayEquals(expected.rgb565(),actual.rgb565(),id+" / "+panel.id()+" / "+state);
                }
            }
        }
    }
    @Test void drawsCollectionWithItsOwnIconsOnSquareAndNarrowPanels() throws Exception {
        var renderer=new StatusImage("Europe/Berlin");var emoji=new Twemoji(Twemoji.locate(null).toString());
        for(var panel:List.of(DisplayType.M1,DisplayType.of("135x240"))){
            int scale=2,gap=24,label=40,cell=panel.width()*scale+gap;
            var sheet=new BufferedImage(gap+5*cell,gap+2*(panel.height()*scale+label+gap),BufferedImage.TYPE_INT_RGB);
            var g=sheet.createGraphics();
            try{
                g.setColor(new Color(0xf3f5f8));g.fillRect(0,0,sheet.getWidth(),sheet.getHeight());
                g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,24));
                for(int n=0;n<IDS.size();n++){
                    var theme=Scheme.parse(IDS.get(n));var icon=ThemeIcons.image(new Design.Icon("theme-default",null,null),theme,Twemoji.SIZE*scale).orElseThrow();
                    var look=new StatusImage.Look(icon,theme,emoji.image("⛅",24*scale).orElseThrow(),"13°",Layout.DEFAULT.fit(panel));
                    var png=renderer.render(ThemesImageTest.snapshot(),"192.168.1.50",look,panel,scale).png();
                    var image=ImageIO.read(new ByteArrayInputStream(png));
                    int x=gap+n%5*cell,y=gap+n/5*(panel.height()*scale+label+gap);
                    g.setColor(new Color(0x263442));g.drawString(theme.label,x,y+28);g.drawImage(image,x,y+label,null);
                }
            }finally{g.dispose();}
            Files.createDirectories(Path.of("target"));
            ImageIO.write(sheet,"png",Path.of("target/theme-collection-"+panel.id()+".png").toFile());
        }
    }
}
