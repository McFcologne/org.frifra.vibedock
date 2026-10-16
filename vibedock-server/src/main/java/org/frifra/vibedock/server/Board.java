package org.frifra.vibedock.server;

import java.util.*;

/**
 * The hardware a device runs on: the firmware build for it (the PlatformIO environment of the same id in
 * vibedock-client/firmware/platformio.ini), its chip as esptool reports it, and the display type it comes with. Every
 * device has one; it decides which firmware is installed over USB or over Wi-Fi. Devices report it with every pull
 * (X-VibeDock-Board); firmware before that reports nothing and is the VibeTV M1.
 */
public record Board(String id,String label,String chip,String display,String note) {
    public static final Board M1=new Board("vibedock-m1","VibeTV M1","ESP8266","vibedock-m1","ESP-12E, 1.54\" ST7789 240×240");
    public static final List<Board> INVENTORY=List.of(M1,
            new Board("esp32-cyd","Cheap Yellow Display (ESP32-2432S028R)","ESP32","320x240","2.8\" ILI9341 320×240, landscape"),
            new Board("lilygo-t-display","LilyGO T-Display","ESP32","135x240","1.14\" ST7789 135×240"),
            new Board("lilygo-t-display-s3","LilyGO T-Display-S3","ESP32-S3","170x320","1.9\" ST7789 170×320, 8-bit parallel"),
            new Board("waveshare-s3-lcd-169","Waveshare ESP32-S3-LCD-1.69","ESP32-S3","240x280","1.69\" ST7789V2 240×280"),
            new Board("esp32-c3-st7789","ESP32-C3 with ST7789 240×240","ESP32-C3","vibedock-m1","e.g. C3 SuperMini, wired SCL 4 · SDA 6 · DC 2 · RES 3 · BLK 10"));
    /** The board of an id; unknown or missing ids are the VibeTV M1. */
    public static Board of(String id){return find(id).orElse(M1);}
    public static Optional<Board> find(String id){return INVENTORY.stream().filter(b->b.id().equals(id)).findFirst();}
    public DisplayType displayType(){return DisplayType.of(display);}
    /** esptool's --chip argument. */
    public String esptoolChip(){return chip.toLowerCase(Locale.ROOT).replace("-","");}
    public boolean esp8266(){return chip.equals("ESP8266");}
}
