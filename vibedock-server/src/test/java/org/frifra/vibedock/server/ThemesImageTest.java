package org.frifra.vibedock.server;

import org.junit.jupiter.api.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;

/**
 * Draws every colour scheme with example data, with the server's renderer at twice the size (sharp on high-resolution
 * screens), into target/themes.png. With the Twemoji graphics versioned in the repository; VIBEDOCK_TWEMOJI names
 * another SVG folder. The picture of the README and the manual (docs/manual/themes.png; outside images/, which
 * scripts/docs.sh makes again) is made with
 * {@code VIBEDOCK_THEMES_IMAGE=../docs/manual/themes.png mvn test -Dtest=ThemesImageTest} (from vibedock-server).
 */
class ThemesImageTest {
    record Example(Scheme scheme,String mood,String weather,String temperature) {}
    static final List<Example> EXAMPLES=List.of(
            new Example(Scheme.MIDNIGHT,"🤖","⛅","13°"),new Example(Scheme.OCEAN,"🐙","🌧","9°"),new Example(Scheme.FOREST,"🌵","☀","21°"),
            new Example(Scheme.SUNSET,"🔥","🌥","17°"),new Example(Scheme.LAVENDER,"🦄","🌨","-2°"),new Example(Scheme.MONO,"🎧","☁","11°"),
            new Example(Scheme.PAPER,"☕","⛅","14°"),new Example(Scheme.SKY,"🐧","☁","6°"),new Example(Scheme.SAND,"🍀","🌥","19°"),
            new Example(Scheme.TERMINAL_GREEN,"👾","⛅","13°"),new Example(Scheme.TERMINAL_AMBER,"🍯","⛅","13°"),new Example(Scheme.CLASSIC_MAC,"🍎","☀","21°"),
            new Example(Scheme.CLASSIC_WINDOWS,"💾","⛅","16°"),new Example(Scheme.WINDOWS_11,"💻","⛅","15°"),new Example(Scheme.MACOS,"🍏","☀","22°"),
            new Example(Scheme.STARSHIP,"🖖","🌥","12°"),new Example(Scheme.HANDHELD,"🍄","☀","20°"),new Example(Scheme.BLUEPRINT,"📐","⛅","14°"),
            new Example(Scheme.NEON,"🌆","🌙","18°"),new Example(Scheme.NEWSPRINT,"📰","🌧","10°"),
            new Example(Scheme.MISSION_CONTROL,"😎","⛅","13°"),new Example(Scheme.DEPARTURE_BOARD,"😎","⛅","13°"),
            new Example(Scheme.NORDIC,"😎","⛅","13°"),new Example(Scheme.HI_FI,"😎","⛅","13°"),
            new Example(Scheme.SOLARIZED,"😎","⛅","13°"),new Example(Scheme.PIXEL_QUEST,"😎","⛅","13°"),
            new Example(Scheme.BAUHAUS,"😎","⛅","13°"),new Example(Scheme.E_INK,"😎","⛅","13°"),
            new Example(Scheme.INDUSTRIAL,"😎","⛅","13°"),new Example(Scheme.PORCELAIN,"😎","⛅","13°"),
            new Example(Scheme.GITHUB_DARK,"😎","⛅","13°"),new Example(Scheme.VS_CODE,"😎","⛅","13°"),
            new Example(Scheme.TERMINAL,"😎","⛅","13°"),new Example(Scheme.GIT_DIFF,"😎","⛅","13°"),
            new Example(Scheme.DEBUGGER,"😎","⛅","13°"));

    @Test void drawsEveryScheme() throws Exception {
        var emoji=Twemoji.locate(System.getenv("VIBEDOCK_TWEMOJI"));
        Assertions.assertTrue(Files.isDirectory(emoji),"No Twemoji graphics in "+emoji.toAbsolutePath().normalize()
                +": third-party/twemoji/svg of the repository, or the folder VIBEDOCK_TWEMOJI names");
        Assertions.assertEquals(ThemeCatalog.builtins().all().size(),EXAMPLES.size(),"every scheme has an example");
        var twemoji=new Twemoji(emoji.toString());var renderer=new StatusImage("Europe/Berlin");
        long updated=LocalDateTime.of(2026,10,14,7,12).atZone(ZoneId.of("Europe/Berlin")).toEpochSecond();
        var snapshot=new UsageService.Snapshot(1,updated,new LinkedHashMap<>(Map.of(
                "codex",new UsageService.Provider("ok",updated,new ProviderClient.Window(0,updated+5*3600,5*3600),new ProviderClient.Window(22,updated+6*86400+1560,7*86400)),
                "claude",new UsageService.Provider("ok",updated,new ProviderClient.Window(5,updated+9000,5*3600),new ProviderClient.Window(14,updated+5*86400-2040,7*86400)))),
                Map.of("codex","Codex","claude","Claude"));
        int k=2,columns=5,cell=256*k,label=22*k,gap=16*k,rows=(EXAMPLES.size()+columns-1)/columns;
        var sheet=new BufferedImage(gap+columns*cell,gap/2+rows*(label+240*k+gap)+gap/2,BufferedImage.TYPE_INT_RGB);
        var g=sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0xf6f7f9));g.fillRect(0,0,sheet.getWidth(),sheet.getHeight());
        g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,14*k));
        for(int i=0;i<EXAMPLES.size();i++){
            var e=EXAMPLES.get(i);int x=gap+(i%columns)*cell,y=gap/2+(i/columns)*(label+240*k+gap);
            var look=new StatusImage.Look(required(twemoji,e.mood(),Twemoji.SIZE*k),e.scheme(),required(twemoji,e.weather(),24*k),e.temperature(),Layout.DEFAULT);
            var image=ImageIO.read(new ByteArrayInputStream(renderer.render(snapshot,"192.168.1.50",look,DisplayType.M1,k).png()));
            g.setColor(new Color(0x3c4048));g.drawString(e.scheme().label,x,y+15*k);
            g.drawImage(image,x,y+label,null);
        }
        g.dispose();
        String target=System.getenv("VIBEDOCK_THEMES_IMAGE");
        Path out=Path.of(target==null||target.isBlank()?"target/themes.png":target);
        Files.createDirectories(out.toAbsolutePath().getParent());ImageIO.write(sheet,"png",out.toFile());
    }
    /** An emoji of the examples; a missing or unreadable SVG fails with its file name and folder. */
    private static BufferedImage required(Twemoji twemoji,String emoji,int size){
        return twemoji.image(emoji,size).orElseThrow(()->new AssertionError("Twemoji SVG for "+emoji+" ("+String.join(" or ",Twemoji.fileNames(emoji))
                +".svg) is missing or unreadable in "+twemoji.directory().toAbsolutePath().normalize()));
    }
}
