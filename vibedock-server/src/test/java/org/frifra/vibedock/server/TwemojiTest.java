package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TwemojiTest {
    private static void png(Path dir,String name) throws Exception {
        var image=new BufferedImage(72,72,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<72;y++)for(int x=0;x<72;x++)image.setRGB(x,y,0xffff0000);
        ImageIO.write(image,"png",dir.resolve(name+".png").toFile());
    }
    @Test void mapsEmojiToTwemojiFileNames() {
        assertEquals(List.of("1f680"),Twemoji.fileNames("🚀"));
        assertEquals("2764",Twemoji.fileNames("❤️").get(0));
        assertEquals("1f3f3-fe0f-200d-1f308",Twemoji.fileNames("🏳️‍🌈").get(0));
    }
    @Test void loadsScaledImagesAndRejectsUnknownEmoji(@TempDir Path dir) throws Exception {
        png(dir,"1f680");png(dir,"2764");
        var twemoji=new Twemoji(dir.toString());
        var rocket=twemoji.image("🚀").orElseThrow();
        assertEquals(Twemoji.SIZE,rocket.getWidth());assertEquals(Twemoji.SIZE,rocket.getHeight());
        assertTrue(twemoji.image("❤️").isPresent());
        assertTrue(twemoji.image("-").isEmpty());assertTrue(twemoji.image("").isEmpty());
        twemoji.validate("");twemoji.validate("-");twemoji.validate("🚀");
        assertThrows(IllegalArgumentException.class,()->twemoji.validate("🐢"));
        assertThrows(IllegalArgumentException.class,()->twemoji.validate("../etc/passwd"));
        assertThrows(IllegalArgumentException.class,()->new Twemoji(dir.resolve("missing").toString()).validate("🚀"));
    }
    @Test void listsTheInstalledSetForThePicker(@TempDir Path dir) throws Exception {
        png(dir,"1f680");png(dir,"2764");png(dir,"1f44d-1f3fd");png(dir,"1f3f3-fe0f-200d-1f308");
        java.nio.file.Files.writeString(dir.resolve("notes.txt"),"x");
        var twemoji=new Twemoji(dir.toString());
        var files=twemoji.catalog().stream().map(Twemoji.Entry::file).toList();
        assertEquals(List.of("2764","1f3f3-fe0f-200d-1f308","1f680"),files);
        assertEquals("rocket",twemoji.catalog().get(2).name());
        assertEquals("flag de",Twemoji.describe("1f1e9-1f1ea"));
        assertEquals("🚀",twemoji.catalog().get(2).emoji());
        assertEquals("2764",twemoji.entry("❤️").orElseThrow().file());
        assertTrue(twemoji.png("1f680").isPresent());
        assertTrue(twemoji.png("../1f680").isEmpty());assertTrue(twemoji.png("notes").isEmpty());
        assertTrue(new Twemoji(dir.resolve("missing").toString()).catalog().isEmpty());
    }
    @Test void devicesInheritTheDefaultEmojiUnlessOverridden(@TempDir Path dir) throws Exception {
        var registry=new ClientRegistry(dir.toString(),new ObjectMapper(),Clock.systemUTC());
        registry.paired("vibedock-a1b2c3d4","Desk","a".repeat(64),"192.168.1.20",80);
        assertEquals("🤖",registry.designFor("vibedock-a1b2c3d4").mood());
        registry.configureDesign(new Design(Scheme.MIDNIGHT,"🚀",null));
        assertEquals("🚀",registry.designFor("vibedock-a1b2c3d4").mood());
        registry.setDesign("vibedock-a1b2c3d4",new Design(Scheme.MIDNIGHT,"❤️",null));
        assertEquals("❤️",registry.designFor("vibedock-a1b2c3d4").mood());
        registry.paired("vibedock-a1b2c3d4","Desk","b".repeat(64),"192.168.1.20",80);
        var reloaded=new ClientRegistry(dir.toString(),new ObjectMapper(),Clock.systemUTC());
        assertEquals("❤️",reloaded.designFor("vibedock-a1b2c3d4").mood());
        registry.configure("","",45);
        assertEquals("🚀",registry.defaultDesign().mood());
    }
    @Test void drawsTheEmojiBottomRight(@TempDir Path dir) throws Exception {
        png(dir,"1f680");
        var icon=new Twemoji(dir.toString()).image("🚀").orElseThrow();
        var snapshot=new UsageService.Snapshot(1,1800000000,java.util.Map.of(
                "codex",new UsageService.Provider("unconfigured",null,null,null),"claude",new UsageService.Provider("unconfigured",null,null,null)));
        var with=ImageIO.read(new java.io.ByteArrayInputStream(new StatusImage("Europe/Berlin").render(snapshot,"x",icon).png()));
        var without=ImageIO.read(new java.io.ByteArrayInputStream(new StatusImage("Europe/Berlin").render(snapshot,"x").png()));
        int rgb=with.getRGB(214,200);
        assertTrue(((rgb>>16)&255)>0xe0&&(rgb&0xffff)==0,"red emoji pixel expected");
        assertEquals(0,without.getRGB(214,200)&0xffffff);
    }
}
