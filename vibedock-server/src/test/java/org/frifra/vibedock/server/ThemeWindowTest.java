package org.frifra.vibedock.server;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;
class ThemeWindowTest {
    @Test void oldWindowRecipesKeepTheirPreviouslyFixedTitleColors() throws Exception {
        var json=new JsonMapper();var renderer=new StatusImage("Europe/Berlin");
        for(String id:List.of("classic_windows","classic_mac")){
            var root=json.readValue(BuiltinThemes.get(id).contents().file("style.json"),LinkedHashMap.class);
            root.remove("window");root.put("version",1);((Map)root.get("palette")).put("title","#ff0000");
            var legacy=new Scheme("legacy.test","Legacy",ThemeDefinition.read(json.writeValueAsBytes(root)));
            var before=renderer.render(LayoutTest.snapshot(0),"",new StatusImage.Look(null,Scheme.parse(id),null,null));
            var after=renderer.render(LayoutTest.snapshot(0),"",new StatusImage.Look(null,legacy,null,null));
            assertArrayEquals(before.rgb565(),after.rgb565(),id+" original v1 appearance");
        }
    }
    @Test void allWindowProfilesReadPaintsFromTheirPortableRecipe() throws Exception {
        var json=new JsonMapper();var renderer=new StatusImage("Europe/Berlin");
        for(String id:List.of("classic_windows","classic_mac","windows_11","macos")){
            var root=json.readValue(BuiltinThemes.get(id).contents().file("style.json"),LinkedHashMap.class);
            root.put("window",Map.of("surface","#ff00ff","titleBar","#00ffff","titleText","#ffffff"));
            var recipe=ThemeDefinition.read(json.writeValueAsBytes(root));var scheme=new Scheme("window.test","Window",recipe);
            var png=renderer.render(LayoutTest.snapshot(0),"",new StatusImage.Look(null,scheme,null,null)).png();
            var image=ImageIO.read(new ByteArrayInputStream(png));
            assertEquals(0xffff00ff,image.getRGB(15,30),id+" surface");
            assertEquals(0xff00ffff,image.getRGB(180,11),id+" title bar");
            root.put("version",1);assertThrows(IllegalArgumentException.class,()->ThemeDefinition.read(json.writeValueAsBytes(root)));
            root.put("version",2);root.put("window",Map.of("surface","url(https://example.com/x)"));
            assertThrows(IllegalArgumentException.class,()->ThemeDefinition.read(json.writeValueAsBytes(root)));
        }
    }
    private static boolean near(int c,int rgb){return Math.abs((c>>16&255)-(rgb>>16&255))<=8&&Math.abs((c>>8&255)-(rgb>>8&255))<=4&&Math.abs((c&255)-(rgb&255))<=8;}
    @Test void macTrafficLightsStayVisibleOnACustomTitleBar() throws Exception {
        var json=new JsonMapper();var root=json.readValue(BuiltinThemes.get("macos").contents().file("style.json"),LinkedHashMap.class);
        root.put("window",Map.of("surface","#ffffff","titleBar","#3060a0","titleText","#ffffff"));
        var scheme=new Scheme("window.lights","Lights",ThemeDefinition.read(json.writeValueAsBytes(root)));
        var image=ImageIO.read(new ByteArrayInputStream(new StatusImage("Europe/Berlin").render(LayoutTest.snapshot(0),"",new StatusImage.Look(null,scheme,null,null)).png()));
        // The PNG is the RGB565 view of the screen, so compare with a tolerance of one 565 step.
        int red=0,yellow=0,green=0;
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int c=image.getRGB(x,y);
            if(near(c,0xff5f57))red++;else if(near(c,0xfebc2e))yellow++;else if(near(c,0x28c840))green++;}
        assertTrue(red>10&&yellow>10&&green>10,"the three lights are drawn above a custom title bar");
    }
}
