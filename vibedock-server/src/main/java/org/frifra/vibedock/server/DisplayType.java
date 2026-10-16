package org.frifra.vibedock.server;

import java.util.*;

/**
 * The display of a device: its resolution decides how many bands fit and whether a usage band can be split. The
 * VibeTV M1 (240 x 240) is the default; the other entries are common SPI colour panels for ESP8266/ESP32 builds.
 * The renderer adapts any design to the type: it keeps the first usage bands that fit and shows a split band's first
 * source alone on narrow screens.
 */
public record DisplayType(String id,String label,int width,int height) {
    public static final DisplayType M1=new DisplayType("vibedock-m1","VibeTV M1 · 240×240 ST7789",240,240);
    public static final List<DisplayType> INVENTORY=List.of(M1,
            new DisplayType("240x280","240×280 · 1.69\" ST7789",240,280),
            new DisplayType("240x320","240×320 portrait · 2.0–2.8\" ST7789 / ILI9341",240,320),
            new DisplayType("320x240","320×240 landscape · 2.4–2.8\" ILI9341",320,240),
            new DisplayType("170x320","170×320 · 1.9\" ST7789",170,320),
            new DisplayType("135x240","135×240 · 1.14\" ST7789",135,240),
            new DisplayType("128x160","128×160 · 1.8\" ST7735",128,160));
    /** The type of an id; unknown or missing ids are the VibeTV M1. */
    public static DisplayType of(String id){return INVENTORY.stream().filter(t->t.id().equals(id)).findFirst().orElse(M1);}
    /** The type a device reports as "<width>x<height>", if it is in the inventory. */
    public static Optional<DisplayType> reported(String size){
        if(size==null)return Optional.empty();
        return INVENTORY.stream().filter(t->(t.width()+"x"+t.height()).equals(size.trim())).findFirst();
    }
    /** Height for usage bands above the weather/mood band (see {@link Layout#fit}). */
    public int bandRoom(){return height-Layout.CONTEXT_HEIGHT;}
    /** Full-size usage bands that fit: two from 232 px height, three from 320 px, otherwise one. */
    public int usageBands(){return Math.max(1,Math.min(Layout.MAX_USAGE_BANDS,bandRoom()/Layout.USAGE_MIN_HEIGHT));}
    /** A usage band can show two sources side by side from 200 px width. */
    public boolean halfWidth(){return width>=200;}
    public String imageFormat(){return "rgb565-be-"+width+"x"+height;}
}
