package org.frifra.vibedock.server;

import java.util.*;

/**
 * The hardware a device runs on: the firmware build for it (the PlatformIO environment of the same id in
 * vibedock-client/firmware/platformio.ini), its chip as esptool reports it, and the display type it comes with. Every
 * device has one; it decides which firmware is installed over USB or over Wi-Fi. Devices report it with every pull
 * (X-VibeDock-Board); firmware before that reports nothing and is the VibeTV M1. tested: the firmware ran on the real
 * board; the others are prepared (they build, but were never tried on hardware). Same as custom_hardware in
 * platformio.ini.
 */
public record Board(String id,String label,String chip,String display,String note,boolean tested) {
    public static final Board M1=new Board("vibedock-m1","VibeTV M1","ESP8266","vibedock-m1","ESP-12E, 1.54\" ST7789 240×240",true);
    public static final List<Board> INVENTORY=List.of(M1,
            new Board("esp32-cyd","Cheap Yellow Display (ESP32-2432S028R)","ESP32","320x240","2.8\" ILI9341 320×240, landscape",false),
            new Board("lilygo-t-display","LilyGO T-Display","ESP32","135x240","1.14\" ST7789 135×240",false),
            new Board("lilygo-t-display-s3","LilyGO T-Display-S3","ESP32-S3","170x320","1.9\" ST7789 170×320, 8-bit parallel",false),
            new Board("waveshare-s3-lcd-169","Waveshare ESP32-S3-LCD-1.69","ESP32-S3","240x280","1.69\" ST7789V2 240×280",false),
            new Board("waveshare-s3-touch-lcd-147","Waveshare ESP32-S3-Touch-LCD-1.47","ESP32-S3","172x320","1.47\" JD9853 172×320, touch",true),
            new Board("waveshare-c6-touch-lcd-169","Waveshare ESP32-C6-Touch-LCD-1.69","ESP32-C6","240x280","1.69\" ST7789V2 240×280, touch",true),
            new Board("esp32-c3-st7789","ESP32-C3 with ST7789 240×240","ESP32-C3","vibedock-m1","e.g. C3 SuperMini, wired SCL 4 · SDA 6 · DC 2 · RES 3 · BLK 10",false));
    /** The board of an id; unknown or missing ids are the VibeTV M1. */
    public static Board of(String id){return find(id).orElse(M1);}
    public static Optional<Board> find(String id){return INVENTORY.stream().filter(b->b.id().equals(id)).findFirst();}
    public DisplayType displayType(){return DisplayType.of(display);}
    /** esptool's --chip argument. */
    public String esptoolChip(){return chip.toLowerCase(Locale.ROOT).replace("-","");}
    public boolean esp8266(){return chip.equals("ESP8266");}
    /** The picture can be turned by 180 degrees in the administration; the VibeTV M1's case decides how it stands. */
    public boolean flippable(){return !id.equals(M1.id());}
    /**
     * About how long the display takes to draw a whole new image (the preview on the device card builds up as slowly):
     * shortened for the administration, the ESP8266 slower than the ESP32 boards.
     */
    /** For the administration: tested on hardware or only prepared. */
    public String hardware(){return tested?"tested on hardware":"prepared, not tested on hardware";}
    public double drawSeconds(){return esp8266()?1.2:.5;}
    /** The case the administration draws around the preview (CSS class), null for the default light one. */
    public String previewCase(){
        return switch(id){case "waveshare-s3-touch-lcd-147"->"case-147";case "waveshare-c6-touch-lcd-169"->"case-c6";default->null;};
    }
}
