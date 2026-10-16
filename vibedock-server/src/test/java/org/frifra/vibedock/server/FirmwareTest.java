package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FirmwareTest {
    @Test void describesTheBundledFirmwarePerBoard(@TempDir Path dir) throws Exception {
        var firmware=new Firmware(dir.toString());
        assertTrue(firmware.info().isEmpty());assertTrue(firmware.all().isEmpty());
        // Images of earlier versions: one vibedock-firmware.bin, the VibeTV M1's.
        Files.write(dir.resolve("vibedock-firmware.bin"),"abc".getBytes());Files.writeString(dir.resolve("version"),"0.2.0\n");
        var info=firmware.info().orElseThrow();
        assertEquals(Board.M1,info.board());assertEquals("0.2.0",info.version());assertEquals(3,info.size());assertEquals("vibedock-m1-0.2.0.bin",info.fileName());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",info.sha256());
        assertTrue(firmware.info(Board.of("esp32-cyd")).isEmpty());assertTrue(firmware.esptool().isEmpty());
        // One directory per board: flash.bin for USB, firmware.bin (the app) for updates over Wi-Fi.
        var cyd=Files.createDirectories(dir.resolve("esp32-cyd"));
        Files.write(cyd.resolve("flash.bin"),"flash".getBytes());Files.write(cyd.resolve("firmware.bin"),"app".getBytes());
        var board=Board.of("esp32-cyd");
        assertEquals(5,firmware.info(board).orElseThrow().size());assertArrayEquals("flash".getBytes(),firmware.bytes(board));
        assertEquals(java.util.List.of("vibedock-m1","esp32-cyd"),firmware.all().stream().map(i->i.board().id()).toList());
        Files.writeString(dir.resolve("version"),"../../etc\n");
        assertTrue(firmware.info().isEmpty());assertTrue(firmware.all().isEmpty());
    }

    private static byte[] image(int size,String identity,boolean usb){
        byte[] b=new byte[size];b[0]=(byte)0xE9;
        if(usb){b[0]=(byte)0xFF;b[0x1000]=(byte)0xE9;b[0x8000]=(byte)0xAA;b[0x8001]=0x50;}
        byte[] id=identity.getBytes();System.arraycopy(id,0,b,usb?0x10100:200,id.length);
        return b;
    }
    @Test void anUploadedBuildReplacesTheBuiltInFirmwareUntilRemoved(@TempDir Path dir) throws Exception {
        Path builtIn=Files.createDirectories(dir.resolve("app"));Path data=Files.createDirectories(dir.resolve("data"));
        var m1=Files.createDirectories(builtIn.resolve("vibedock-m1"));
        Files.write(m1.resolve("flash.bin"),"flash".getBytes());Files.write(m1.resolve("firmware.bin"),"flash".getBytes());
        Files.writeString(builtIn.resolve("version"),"0.2.4\n");
        var firmware=new Firmware(builtIn.toString(),data.toString());
        assertFalse(firmware.info(Board.M1).orElseThrow().uploaded());
        // Checked: an ESP image with the identity of this board.
        assertThrows(IllegalArgumentException.class,()->firmware.upload(Board.M1,new byte[4096]));
        assertThrows(IllegalArgumentException.class,()->firmware.upload(Board.M1,image(4096,"no identity",false)));
        var other=assertThrows(IllegalArgumentException.class,()->firmware.upload(Board.M1,image(4096,"VIBEDOCK-FIRMWARE 0.2.5 esp32-cyd",false)));
        assertTrue(other.getMessage().contains("esp32-cyd"));
        // ESP8266: the app is all there is.
        byte[] app=image(4096,"VIBEDOCK-FIRMWARE 0.2.5-1-gabc vibedock-m1",false);
        var result=firmware.upload(Board.M1,app);
        assertEquals("0.2.5-1-gabc",result.version());assertTrue(result.complete());assertEquals(Firmware.Kind.APP,result.kind());
        var info=firmware.info(Board.M1).orElseThrow();
        assertTrue(info.uploaded());assertEquals("0.2.5-1-gabc",info.version());assertArrayEquals(app,firmware.bytes(Board.M1));
        assertEquals("0.2.5-1-gabc",firmware.versions().get("vibedock-m1"));
        // Firmware 0.2.3/0.2.4 carries its start log line instead.
        assertTrue(firmware.upload(Board.M1,image(4096,"Start v0.2.4-9-gcd950d0 vibedock-m1, ",false)).complete());
        assertEquals("0.2.4-9-gcd950d0",firmware.info(Board.M1).orElseThrow().version());
        firmware.removeUpload(Board.M1);
        assertFalse(firmware.info(Board.M1).orElseThrow().uploaded());assertEquals("0.2.4",firmware.info(Board.M1).orElseThrow().version());
        // ESP32 chips: app and USB image of the same build; until both are there the built-in firmware stays.
        var cyd=Board.of("esp32-cyd");
        var usb=firmware.upload(cyd,image(0x20000,"VIBEDOCK-FIRMWARE 0.2.5 esp32-cyd",true));
        assertEquals(Firmware.Kind.USB,usb.kind());assertFalse(usb.complete());
        assertTrue(firmware.uploadIncomplete(cyd));assertTrue(firmware.info(cyd).isEmpty());
        assertTrue(firmware.upload(cyd,image(4096,"VIBEDOCK-FIRMWARE 0.2.5 esp32-cyd",false)).complete());
        assertEquals(0x20000,firmware.info(cyd).orElseThrow().size());assertFalse(firmware.uploadIncomplete(cyd));
        // Another version replaces the other part, which has to come again.
        assertFalse(firmware.upload(cyd,image(4096,"VIBEDOCK-FIRMWARE 0.2.6 esp32-cyd",false)).complete());
        assertTrue(firmware.info(cyd).isEmpty());
    }
}
