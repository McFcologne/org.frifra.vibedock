package org.frifra.vibedock.server;

import java.util.*;

/**
 * The display of a device: its resolution decides how many bands fit and whether a usage band can be split. The
 * VibeTV M1 (240 x 240) is the default; the other entries are common SPI colour panels for ESP8266/ESP32 builds.
 * The renderer adapts any design to the type: it keeps the first usage bands that fit and shows a split band's first
 * source alone on narrow screens.
 */
public record DisplayType(String id,String label,int width,int height,int corner) {
    /** corner: radius of the panel's rounded corners in pixels (0 = square); the image keeps its text clear of them. */
    public DisplayType(String id,String label,int width,int height){this(id,label,width,height,0);}
    public static final DisplayType M1=new DisplayType("vibedock-m1","VibeTV M1 · 240×240 ST7789",240,240);
    public static final List<DisplayType> INVENTORY=List.of(M1,
            new DisplayType("240x280","240×280 · 1.69\" ST7789",240,280,36),
            new DisplayType("240x320","240×320 portrait · 2.0–2.8\" ST7789 / ILI9341",240,320),
            new DisplayType("320x240","320×240 landscape · 2.4–2.8\" ILI9341",320,240),
            new DisplayType("170x320","170×320 · 1.9\" ST7789",170,320),
            new DisplayType("172x320","172×320 · 1.47\" JD9853",172,320,18),
            new DisplayType("135x240","135×240 · 1.14\" ST7789",135,240),
            new DisplayType("128x160","128×160 · 1.8\" ST7735",128,160));
    /**
     * Web displays only: the image takes the shape of the browser window and its full resolution (see
     * {@link #fitting}); the size here is what the administration's previews and programs without a size get.
     */
    public static final DisplayType FIT=new DisplayType("fit","Fit the browser · any size, sharp",360,640);
    /** What a web display can show: the device types and the browser's own size. */
    public static final List<DisplayType> WEB=java.util.stream.Stream.concat(INVENTORY.stream(),java.util.stream.Stream.of(FIT)).toList();
    /** The type of an id; unknown or missing ids are the VibeTV M1. */
    public static DisplayType of(String id){return WEB.stream().filter(t->t.id().equals(id)).findFirst().orElse(M1);}
    /** Short side of a fitted image in its own pixels: the layout's bands keep a readable size on large screens. */
    static final int FIT_SHORT_SIDE=360;
    /**
     * The image for a browser area of width x height CSS pixels at a device pixel ratio: the type and the scale to
     * draw it at. FIT takes the area's shape (short side {@value #FIT_SHORT_SIDE}, larger screens are zoomed), the
     * other types keep theirs and are drawn as large as the area holds. The scale is rounded up to quarter steps (few
     * different sizes to draw) and limited to 16 megapixels.
     */
    public record Sized(DisplayType type,double scale) {}
    public Sized fitting(int cssWidth,int cssHeight,double ratio){
        int cw=Math.max(100,Math.min(4096,cssWidth)),ch=Math.max(100,Math.min(4096,cssHeight));
        double r=Double.isFinite(ratio)?Math.max(1,Math.min(4,ratio)):1;
        DisplayType type=this;double zoom;
        if(this==FIT||id.equals(FIT.id)){
            zoom=Math.max(1,Math.min(cw,ch)/(double)FIT_SHORT_SIDE);
            int w=(int)Math.round(cw/zoom),h=(int)Math.round(ch/zoom);
            type=new DisplayType(FIT.id,FIT.label,Math.max(120,Math.min(1600,w)),Math.max(120,Math.min(1600,h)));
        }else zoom=Math.min(cw/(double)width,ch/(double)height);
        double scale=Math.max(1,Math.ceil(zoom*r*4)/4);
        while(scale>1&&type.width*scale*type.height*scale>16_000_000)scale-=.25;
        return new Sized(type,Math.min(StatusImage.MAX_SCALE,scale));
    }
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
