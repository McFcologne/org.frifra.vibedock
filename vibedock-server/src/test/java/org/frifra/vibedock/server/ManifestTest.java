package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ManifestTest {
    private static final byte[] APP="app".getBytes(),FLASH="flash".getBytes();
    private static String body(String... changes){
        var json=new StringBuilder("{\"schema\":1,\"id\":\"my-board\",\"label\":\"My Board\",\"chip\":\"ESP32-S3\",\"version\":\"0.6.0\",\"hardware\":\"prepared\","
                +"\"firmware\":{\"sha256\":\""+Manifest.sha256(APP)+"\",\"flashSha256\":\""+Manifest.sha256(FLASH)+"\"},"
                +"\"display\":{\"width\":240,\"height\":320,\"corner\":0,\"grey\":false},"
                +"\"capabilities\":{\"touch\":false,\"battery\":true,\"rotations\":[0,180],\"powerSaving\":false},"
                +"\"case\":{\"kind\":\"generic\",\"margin\":[0.30,0.15,0.40,0.15],\"radius\":0.10,\"rim\":0.06,\"colour\":\"#eeeeea\"},"
                +"\"build\":{\"platformioBoard\":\"esp32-s3-devkitc-1\",\"extends\":\"esp32-s3r8\",\"flags\":[\"-D ST7789_DRIVER\",\"-D TFT_WIDTH=240\",\"-D TFT_RGB_ORDER=TFT_BGR\"],\"libraries\":[\"bodmer/TFT_eSPI@2.5.43\"]}}");
        String s=json.toString();
        for(int i=0;i<changes.length;i+=2){assertTrue(s.contains(changes[i]),changes[i]);s=s.replace(changes[i],changes[i+1]);}
        return s;
    }
    /** The manifest text with the checksum its content has. */
    private static byte[] signed(String json){return ("{\"checksum\":\""+Manifest.checksumOf(json)+"\","+json.substring(1)).getBytes(StandardCharsets.UTF_8);}
    private static void refused(String json,String part){
        var e=assertThrows(IllegalArgumentException.class,()->Manifest.parse(signed(json)),json);
        assertTrue(e.getMessage().contains(part),e.getMessage());
    }

    @Test void aValidManifestIsRead(){
        var m=Manifest.parse(signed(body()));
        assertEquals("my-board",m.id());assertEquals("My Board",m.label());assertEquals("ESP32-S3",m.chip());assertEquals("0.6.0",m.version());
        assertEquals(240,m.display().width());assertEquals(320,m.display().height());assertFalse(m.display().grey());
        assertTrue(m.capabilities().battery());assertEquals(List.of(0,180),m.capabilities().rotations());
        assertEquals(4,m.boardCase().margin().size());assertEquals("#eeeeea",m.boardCase().colour());
        m.verifyBinaries(APP,FLASH);m.verifyIdentity("my-board","0.6.0");
        assertEquals("esp32-s3-devkitc-1",m.build().platformioBoard());assertEquals("esp32-s3r8",m.build().extendsEnv());
        assertEquals(List.of("-D ST7789_DRIVER","-D TFT_WIDTH=240","-D TFT_RGB_ORDER=TFT_BGR"),m.build().flags());assertEquals(List.of("bodmer/TFT_eSPI@2.5.43"),m.build().libraries());
    }
    @Test void aMinimalManifestGetsTheDefaults(){
        String json="{\"schema\":1,\"id\":\"tiny\",\"label\":\"Tiny\",\"chip\":\"ESP8266\",\"version\":\"1.0\",\"firmware\":{\"sha256\":\""+Manifest.sha256(APP)
                +"\"},\"display\":{\"width\":128,\"height\":160}}";
        var m=Manifest.parse(signed(json));
        assertEquals("prepared",m.hardware());assertEquals(List.of(0),m.capabilities().rotations());assertFalse(m.capabilities().touch());
        assertNull(m.boardCase());assertNull(m.build());assertNull(m.firmware().flashSha256());m.verifyBinaries(APP,null);
    }
    @Test void theChecksumCatchesChangesAndOnlyAChecksumedTextIsAccepted(){
        // Key order, white space and the checksum's place do not matter; the content does.
        var sorted=signed(body());
        assertDoesNotThrow(()->Manifest.parse(sorted));
        String spaced=new String(sorted,StandardCharsets.UTF_8).replace(",\"id\"",",\n  \"id\"");
        assertDoesNotThrow(()->Manifest.parse(spaced.getBytes(StandardCharsets.UTF_8)));
        String changed=new String(sorted,StandardCharsets.UTF_8).replace("\"My Board\"","\"Other Board\"");
        assertTrue(assertThrows(IllegalArgumentException.class,()->Manifest.parse(changed.getBytes(StandardCharsets.UTF_8))).getMessage().contains("checksum"));
        assertThrows(IllegalArgumentException.class,()->Manifest.parse(body().getBytes(StandardCharsets.UTF_8)));
        // 0.30 and 0.3 are the same number in the checksum.
        assertEquals(Manifest.checksumOf(body()),Manifest.checksumOf(body("0.30","0.3")));
    }
    @Test void everyValueIsChecked(){
        refused(body("\"schema\":1","\"schema\":2"),"schema");
        refused(body("\"id\":\"my-board\"","\"id\":\"My_Board\""),"id");
        refused(body("\"id\":\"my-board\"","\"id\":\"../x\""),"id");
        refused(body("\"version\":\"0.6.0\"","\"version\":\"a b\""),"version");
        refused(body("\"chip\":\"ESP32-S3\"","\"chip\":\"ESP99\""),"chip");
        refused(body("\"label\":\"My Board\"","\"label\":\""+"x".repeat(81)+"\""),"label");
        refused(body("\"label\":\"My Board\"","\"label\":\"a\\nb\""),"label");
        refused(body("\"hardware\":\"prepared\"","\"hardware\":\"great\""),"hardware");
        refused(body("\"width\":240","\"width\":63"),"width");
        refused(body("\"height\":320","\"height\":481"),"height");
        refused(body("\"corner\":0","\"corner\":101"),"corner");
        refused(body("\"grey\":false","\"grey\":\"no\""),"grey");
        refused(body("\"rotations\":[0,180]","\"rotations\":[45]"),"rotations");
        refused(body("\"kind\":\"generic\"","\"kind\":\"svg\""),"kind");
        refused(body("[0.30,0.15,0.40,0.15]","[0.3,0.15]"),"margin");
        refused(body("[0.30,0.15,0.40,0.15]","[0.3,0.15,0.4,9]"),"margin");
        refused(body("\"rim\":0.06","\"rim\":0.9"),"rim");
        refused(body("\"colour\":\"#eeeeea\"","\"colour\":\"red\""),"colour");
        refused(body("\"sha256\":\""+Manifest.sha256(APP)+"\"","\"sha256\":\"abc\""),"sha256");
        refused(body("\"display\":{\"width\":240,\"height\":320,\"corner\":0,\"grey\":false},",""),"display");
        // Only -D NAME=value flags: no other compiler options, no commands, no paths or URLs.
        for(String flag:new String[]{"-O3","-D x=1","-D NAME=a b","-D NAME=$(rm)","-DNAME=1","-include /etc/passwd","-D NAME=\\\"x\\\"","--version","-D NAME=http://x"})
            refused(body("\"-D ST7789_DRIVER\"","\""+flag+"\""),"flags");
        refused(body("\"bodmer/TFT_eSPI@2.5.43\"","\"https://evil.example/x.zip\""),"libraries");
        refused(body("\"platformioBoard\":\"esp32-s3-devkitc-1\"","\"platformioBoard\":\"../x\""),"platformioBoard");
        refused(body("\"flags\":[\"-D ST7789_DRIVER\"","\"flags\":["+"\"-D A\",".repeat(80)+"\"-D ST7789_DRIVER\""),"flags");
        assertThrows(IllegalArgumentException.class,()->Manifest.parse("[]".getBytes()));
        assertThrows(IllegalArgumentException.class,()->Manifest.parse("not json".getBytes()));
        assertThrows(IllegalArgumentException.class,()->Manifest.parse(new byte[0]));
        assertThrows(IllegalArgumentException.class,()->Manifest.parse(new byte[Manifest.MAX_BYTES+1]));
    }
    @Test void aManifestBelongsToItsBinariesAndItsFirmwareIdentity(){
        var m=Manifest.parse(signed(body()));
        assertThrows(IllegalArgumentException.class,()->m.verifyBinaries("other".getBytes(),FLASH));
        assertThrows(IllegalArgumentException.class,()->m.verifyBinaries(APP,"other".getBytes()));
        assertThrows(IllegalArgumentException.class,()->m.verifyBinaries(APP,null));
        assertThrows(IllegalArgumentException.class,()->m.verifyIdentity("other-board","0.6.0"));
        assertThrows(IllegalArgumentException.class,()->m.verifyIdentity("my-board","0.5.1"));
    }
    @Test void unknownFieldsAreIgnoredButCountInTheChecksum(){
        String extra=body("\"schema\":1","\"schema\":1,\"note\":\"hello\"");
        var m=Manifest.parse(signed(extra));
        assertEquals("my-board",m.id());
        assertNotEquals(Manifest.checksumOf(body()),Manifest.checksumOf(extra));
    }
}
