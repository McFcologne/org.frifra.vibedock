package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;

class FirmwareRequestTest {
    private static Map<String,String> form(){
        var m=new LinkedHashMap<String,String>();
        m.put("board.label","Acme Round 1.28");m.put("maker.productUrl","https://example.com/acme");m.put("maker.shopUrl","https://www.amazon.de/dp/B0FQJNW2Q5");
        m.put("board.chip","ESP32-S3");m.put("board.flashMb","16");m.put("board.usbReport","Chip: ESP32-S3\nFlash: 16MB");m.put("blank","  ");
        return m;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> sub(Map<String,Object> m,String key){return (Map<String,Object>)m.get(key);}

    @Test void theReportCarriesTheAnswers(){
        var r=FirmwareRequest.report(form(),"0.6.0",Instant.parse("2026-11-07T10:00:00Z"),true);
        assertEquals("firmware-request",r.get("kind"));assertEquals("0.6.0",r.get("serverVersion"));assertEquals("photo.jpg",r.get("photo"));
        assertEquals("acme-round-1-28",sub(r,"board").get("id"));assertEquals("ESP32-S3",sub(r,"board").get("chip"));assertEquals(16,sub(r,"board").get("flashMb"));
        assertEquals("https://www.amazon.de/dp/B0FQJNW2Q5",sub(r,"maker").get("shopUrl"));
        assertEquals("<server version>-dirty",sub(r,"delivery").get("version"));
    }
    @Test void onlyNameAndProductPageAreRequired(){
        var r=FirmwareRequest.report(Map.of("board.label","X","maker.productUrl","https://example.com/x"),null,Instant.EPOCH);
        assertEquals("unknown",r.get("serverVersion"));assertFalse(sub(r,"board").containsKey("chip"));assertFalse(sub(r,"maker").containsKey("shopUrl"));assertFalse(r.containsKey("photo"));
        assertThrows(IllegalArgumentException.class,()->FirmwareRequest.report(Map.of("board.label","X"),"0.6.0",Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,()->FirmwareRequest.report(Map.of("maker.productUrl","https://example.com/x"),"0.6.0",Instant.EPOCH));
    }
    @Test void everyAnswerIsChecked(){
        for(var bad:List.of(Map.entry("board.chip","z80"),Map.entry("maker.productUrl","javascript:alert(1)"),Map.entry("maker.shopUrl","ftp://x.example/a"),
                Map.entry("board.label","a\u0001b"),Map.entry("notes","x".repeat(1001)),Map.entry("board.flashMb","0"),Map.entry("board.id","Not Valid"))){
            var m=form();m.put(bad.getKey(),bad.getValue());
            assertThrows(IllegalArgumentException.class,()->FirmwareRequest.report(m,"0.6.0",Instant.EPOCH),bad.getKey());
        }
    }
    @Test void thePackageHoldsTheReportAndThePhoto() throws Exception {
        var image=new BufferedImage(3000,1500,BufferedImage.TYPE_INT_RGB);var png=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",png);
        byte[] photo=PhotoLibrary.reduced(png.toByteArray(),1600);
        var read=javax.imageio.ImageIO.read(new ByteArrayInputStream(photo));assertEquals(1600,read.getWidth());assertEquals(800,read.getHeight());
        assertThrows(IllegalArgumentException.class,()->PhotoLibrary.reduced(new byte[]{1,2,3},1600));
        var zip=FirmwareRequest.pack(FirmwareRequest.report(form(),"0.6.0",Instant.EPOCH,true),photo);
        var names=new ArrayList<String>();
        try(var in=new ZipInputStream(new ByteArrayInputStream(zip))){for(var e=in.getNextEntry();e!=null;e=in.getNextEntry())names.add(e.getName());}
        assertEquals(List.of("request.json","photo.jpg"),names);
    }
}
