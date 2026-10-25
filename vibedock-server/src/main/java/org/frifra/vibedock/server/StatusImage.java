package org.frifra.vibedock.server;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

@Component
public class StatusImage {
    public record Image(byte[] png,byte[] rgb565) {}
    /** Optional parts of a device's image: emoji bottom right, colour scheme, weather icon and temperature bottom left. */
    /** battery: the display runs on its battery (null: on USB, or it does not tell); a small icon at the top right. */
    public record Look(BufferedImage emoji,Scheme scheme,BufferedImage weatherIcon,String weatherText,Layout layout,Battery battery) {
        public Look { scheme=scheme==null?Scheme.MIDNIGHT:scheme;layout=layout==null?Layout.DEFAULT:layout; }
        public Look(BufferedImage emoji,Scheme scheme,BufferedImage weatherIcon,String weatherText,Layout layout){this(emoji,scheme,weatherIcon,weatherText,layout,null);}
        public Look(BufferedImage emoji,Scheme scheme,BufferedImage weatherIcon,String weatherText){this(emoji,scheme,weatherIcon,weatherText,Layout.DEFAULT);}
        static final Look PLAIN=new Look(null,Scheme.MIDNIGHT,null,null);
    }
    /**
     * A display on its battery (X-VibeDock-Power): level in percent when the board measures it (in steps, so the image
     * changes rarely), otherwise null and the icon shows three dots (level unknown).
     */
    public record Battery(Integer level) {
        public Battery { if(level!=null&&(level<0||level>100))throw new IllegalArgumentException("Battery level 0–100"); }
    }
    private final ZoneId zone;
    public StatusImage(@Value("${vibedock.time-zone}") String zone){this.zone=ZoneId.of(zone);}
    public Image render(UsageService.Snapshot snapshot,String ip) throws IOException { return render(snapshot,ip,Look.PLAIN); }
    public Image render(UsageService.Snapshot snapshot,String ip,BufferedImage emoji) throws IOException {
        return render(snapshot,ip,new Look(emoji,Scheme.MIDNIGHT,null,null));
    }
    /**
     * Draws the bands of the layout from top to bottom, each at its fixed height. Nothing is scaled; the default layout
     * gives exactly the image of earlier versions.
     */
    public Image render(UsageService.Snapshot snapshot,String ip,Look look) throws IOException { return render(snapshot,ip,look,DisplayType.M1); }
    /** The image in the resolution of a display type; the layout is fitted to it (see {@link Layout#fit}). */
    public Image render(UsageService.Snapshot snapshot,String ip,Look look,DisplayType type) throws IOException { return render(snapshot,ip,look,type,1); }
    /**
     * The image drawn at scale times the display type's pixels (web displays on high-resolution screens): the layout
     * stays the type's, text and lines are drawn sharp at the larger size. The emoji of the look are expected at
     * scale times their size. Scaled images have a PNG only, no RGB565 pixels.
     */
    public Image render(UsageService.Snapshot snapshot,String ip,Look look,DisplayType type,double scale) throws IOException {
        if(!(scale>=1&&scale<=MAX_SCALE))throw new IllegalArgumentException("Scale 1–"+MAX_SCALE);
        var fitted=new Look(look.emoji(),look.scheme(),look.weatherIcon(),look.weatherText(),look.layout().fit(type),look.battery());
        return new Frame(type.width(),type.height(),type.corner(),scale).draw(snapshot,ip,fitted);
    }
    static final double MAX_SCALE=8;
    /** One rendering at a given size; the drawing code below works for any width and height. */
    private final class Frame {
    final int w,h,corner;final double scale;
    /** The battery icon until the first usage band has drawn it at its top right. */
    Battery battery;
    Frame(int w,int h,int corner,double scale){this.w=w;this.h=h;this.corner=corner;this.scale=scale;}
    /** Width and height of an icon in the frame's pixels (icons come at scale times their size). */
    int iw(BufferedImage icon){return (int)Math.round(icon.getWidth()/scale);}
    int ih(BufferedImage icon){return (int)Math.round(icon.getHeight()/scale);}
    void put(Graphics2D g,BufferedImage icon,int x,int y){
        if(scale==1)g.drawImage(icon,x,y,null);else g.drawImage(icon,x,y,iw(icon),ih(icon),null);
    }
    /** The scheme's icon look; the Mac's dithering keeps its pixel size on scaled images. */
    BufferedImage look(BufferedImage icon,Scheme scheme){
        if(scheme.style==Scheme.Style.LCD||scheme.style==Scheme.Style.BLUEPRINT)return styled(icon,scheme,Math.max(1,(int)Math.round(scale)));
        if(icon==null||scale==1||scheme.style!=Scheme.Style.MAC)return styled(icon,scheme);
        var dithered=styled(resize(icon,iw(icon),ih(icon)),scheme);
        var out=new BufferedImage(icon.getWidth(),icon.getHeight(),BufferedImage.TYPE_INT_ARGB);var g=out.createGraphics();
        try{g.drawImage(dithered,0,0,out.getWidth(),out.getHeight(),null);}finally{g.dispose();}
        return out;
    }
    /** How far the rounded corners reach in from the side at a distance from the top or bottom edge. */
    int inset(int fromEdge){
        if(corner<=0||fromEdge>=corner)return 0;
        int d=corner-fromEdge;return (int)Math.ceil(corner-Math.sqrt((double)corner*corner-(double)d*d));
    }
    Image draw(UsageService.Snapshot snapshot,String ip,Look look) throws IOException {
        var scheme=look.scheme();battery=look.battery();
        var image=new BufferedImage((int)Math.round(w*scale),(int)Math.round(h*scale),BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try{
            if(scale!=1){
                g.scale(scale,scale);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
            }
            g.setColor(new Color(scheme.background));g.fillRect(0,0,w,h);
            backdrop(g,scheme);
            // Classic Mac, Windows and the LCD: no anti-aliasing, crisp pixels like the originals.
            boolean smooth=scheme.style!=Scheme.Style.MAC&&scheme.style!=Scheme.Style.WINDOWS&&scheme.style!=Scheme.Style.LCD&&scheme.style!=Scheme.Style.QUEST;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,smooth?RenderingHints.VALUE_TEXT_ANTIALIAS_ON:RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,smooth?RenderingHints.VALUE_ANTIALIAS_ON:RenderingHints.VALUE_ANTIALIAS_OFF);
            // Usage bands shrink in proportion (to their minimum, see Layout.fit) when they need more than the screen
            // has; taller screens: the spare height goes evenly between the bands; the last band stays at the bottom edge.
            // Aligned to the top: the bands stay together, only a weather/mood band at the end moves to the bottom edge.
            var bands=look.layout().bands();
            int nominal=bands.stream().filter(Layout.Usage.class::isInstance).mapToInt(Layout.Band::height).sum();
            int room=h-Layout.CONTEXT_HEIGHT,used=Math.min(nominal,room)+Layout.CONTEXT_HEIGHT;
            int gaps=bands.size()-1,spare=Math.max(0,h-used);
            // Rounded panels: spare height first moves the top band out of the corners a little.
            int y=Math.min(spare,corner/3);spare-=y;
            for(int i=0;i<bands.size();i++){
                var band=bands.get(i);
                int height=band instanceof Layout.Usage&&nominal>room?band.height()*room/nominal:band.height();
                switch(band){
                    case Layout.Usage usage -> usage(g,scheme,snapshot,usage,y,height,i==bands.size()-1&&spare==0);
                    case Layout.Context context -> context(g,snapshot,ip,look,context,y);
                }
                boolean top=look.layout().alignTop();
                if(top)y+=height+(i==bands.size()-2&&bands.get(bands.size()-1) instanceof Layout.Context?spare:0);
                else y+=height+(gaps==0?0:spare/gaps+(i<spare%gaps?1:0));
            }
        }finally{g.dispose();}
        return encode(image,scheme);
    }
    /** One usage band: a full-width provider, or two half-width ones side by side. */
    /** bottom: the band ends at the screen edge; windows get the same margin below as above. */
    private void usage(Graphics2D g,Scheme scheme,UsageService.Snapshot snapshot,Layout.Usage band,int y,int height,boolean bottom){
        var sources=band.sources();
        // The battery icon goes into the first usage band, at its top right (the right one of two half-width panels).
        var icon=battery;battery=null;
        if(band.compact()){row(g,scheme,snapshot.label(sources.get(0)),snapshot.providers().get(sources.get(0)),y,height,bottom,icon);return;}
        if(sources.size()==1){provider(g,scheme,snapshot.label(sources.get(0)),snapshot.providers().get(sources.get(0)),y+22,height,bottom,icon);return;}
        for(int column=0;column<sources.size();column++){
            var panel=(Graphics2D)g.create();
            try{
                panel.translate(column*(w/2),y);panel.clipRect(0,0,w/2,height);
                compact(panel,scheme,snapshot.label(sources.get(column)),snapshot.providers().get(sources.get(column)),height,bottom,
                        column==sources.size()-1?icon:null);
            }finally{panel.dispose();}
        }
    }
    private void provider(Graphics2D g,Scheme scheme,String title,UsageService.Provider p,int top,int height,boolean bottom,Battery icon){
        title=ThemeArtwork.title(scheme,title);
        ThemeArtwork.band(g,scheme,0,top-22,w,height);
        if(p==null)p=new UsageService.Provider("unconfigured",null,null,null);
        // Rate limited with last known values: keep them and show the time they were read.
        boolean held=p.status().equals("rate_limited")&&p.observedAt()!=null&&(p.fiveHour()!=null||p.week()!=null);
        String status=held?"Rate limited \u00b7 "+DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochSecond(p.observedAt()))
                :p.status().equals("ok")?null:DeviceSender.statusLabel(p.status());
        // Narrow screens: the status is shortened to the room the title leaves.
        g.setFont(new Font(scheme.font(),Font.BOLD,scheme.style==Scheme.Style.TERMINAL?15:16));
        int reserve=icon==null?0:BATTERY_ROOM+ThemeArtwork.cornerRoom(scheme);
        int titleWidth=g.getFontMetrics().stringWidth((scheme.style==Scheme.Style.TERMINAL?"> ":"")+title);
        if(scheme.developer()&&status!=null)titleWidth=Math.min(titleWidth,w/2-12);
        int room=w-20-titleWidth-8-reserve;
        if(status!=null&&!scheme.windowed())
            status=fit(g,scheme,scheme.style==Scheme.Style.TERMINAL?status.toUpperCase(Locale.ROOT):status,10,room);
        switch(scheme.style){
            case TERMINAL -> {
                text(g,scheme,"> "+title.toUpperCase(Locale.ROOT),10,top,15,scheme.title,true);
                if(status!=null)right(g,scheme,status,w-10-reserve,top,10,scheme.warning);
                if(icon!=null)batteryIcon(g,icon,w-10,top-6,scheme.text,scheme.warning);
            }
            case MAC -> macWindow(g,scheme,title,status,top,4,w-9,height-(bottom?8:4),icon);
            case WINDOWS -> windowsWindow(g,scheme,title,status,top,4,w-8,height-(bottom?6:3),icon);
            case FLUENT -> fluentWindow(g,scheme,title,status,top,4,w-8,height-(bottom?6:3),icon);
            case MACOS -> macosWindow(g,scheme,title,status,top,4,w-8,height-(bottom?6:3),icon);
            case CONSOLE -> console(g,scheme,title,status,top,2,w-4,height-(bottom?6:3),icon);
            default -> {
                if(!ThemeArtwork.heading(g,scheme,title,10,top,(status!=null?Math.min(w-20,w/2-12):w-20)-reserve,16))text(g,scheme,title,10,top,16,scheme.title,true);
                if(status!=null)right(g,scheme,status,w-10-reserve,top,10,scheme.warning);
                if(icon!=null)batteryIcon(g,icon,w-10-ThemeArtwork.cornerRoom(scheme),top-6,scheme.text,scheme.warning);
                // Newsprint: a thin rule below the heading, like a column.
                if(scheme.style==Scheme.Style.PRINT){g.setColor(new Color(scheme.text));g.fillRect(10,top+4,w-20,1);}
            }
        }
        // Window styles on narrow screens: the rows sit a little higher, so the last reset time stays clear of the frame.
        boolean higher=windowed(scheme)&&w<200;int first=higher?top+20:top+22,second=higher?top+49:top+52;
        if(p.period()!=null)window(g,scheme,p.period(),p.week(),first);
        else{window(g,scheme,"5h",p.fiveHour(),first);window(g,scheme,"Week",p.week(),second);}
    }
    /**
     * The weather/mood band, 56 px: weather, update time and mood, and the device IP in its bottom-left corner
     * (rows 36-55, columns 0-149). The left slot ends above the IP, the right slot uses the full height.
     */
    private void context(Graphics2D g,UsageService.Snapshot snapshot,String ip,Look look,Layout.Context band,int y){
        var scheme=look.scheme();
        if(scheme.style==Scheme.Style.CODE){g.setColor(new Color(ThemeArtwork.footerBackground(scheme)));g.fillRect(0,y,w,56);}
        // Time of the newest provider reading, not of this rendering: the image (and its ETag) only changes with new data.
        var observed=snapshot.providers().values().stream().map(UsageService.Provider::observedAt).filter(Objects::nonNull).max(Long::compare);
        String time=observed.map(t->DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochSecond(t))).orElse("--:--");
        String updated="Updated "+time;
        // Narrow screens (below 200 px): only the time, in the middle between weather and mood.
        boolean narrow=w<200;
        BufferedImage rightMood=null;int weatherLeft=w;  // left edge of a temperature in the IP row
        if(!band.moodLeft()){
            var weatherIcon=look(look.weatherIcon(),scheme);var emoji=look(look.emoji(),scheme);
            if(look.weatherText()!=null){
                // Weather left: icon, temperature, then the update time on the same line.
                int x=10;
                if(weatherIcon!=null){icon(g,scheme,weatherIcon,x,y+4);x+=iw(weatherIcon)+5;}
                text(g,scheme,look.weatherText(),x,y+23,16,desktopText(scheme),true);
                x+=g.getFontMetrics().stringWidth(look.weatherText())+10;
                g.setFont(new Font(scheme.font(),Font.PLAIN,10));
                if(narrow)centredTime(g,scheme,time,x,w-6-(emoji!=null?iw(emoji)+4:0),y+23);
                else if(x+g.getFontMetrics().stringWidth(updated)<=w-(emoji!=null?iw(emoji)+10:6))text(g,scheme,updated,x,y+23,10,desktopMuted(scheme),false);
            } else if(narrow)centredTime(g,scheme,time,10,w-6-(emoji!=null?iw(emoji)+4:0),y+28);
            else text(g,scheme,updated,10,y+28,10,desktopMuted(scheme),false);
            rightMood=emoji;  // drawn last, in front of the IP corner
        }else{
            // Mood left in the short slot, followed by the update time; weather right in the tall slot.
            var mood=look(resize(look.emoji(),(int)Math.round(30*scale)),scheme);int x=10;
            if(mood!=null){put(g,mood,x,y+5);x+=36;}
            text(g,scheme,updated,x,y+24,10,desktopMuted(scheme),false);
            var weatherIcon=look(look.weatherIcon(),scheme);
            if(weatherIcon!=null)icon(g,scheme,weatherIcon,w-6-iw(weatherIcon),y+4);
            if(look.weatherText()!=null){
                right(g,scheme,look.weatherText(),w-6,y+50,16,desktopText(scheme));
                weatherLeft=w-6-g.getFontMetrics().stringWidth(look.weatherText());
            }
        }
        // IP corner, drawn straight over the background like the other texts of the band; the mood may reach into the
        // rest of the bottom row. A text with a space (web displays: "VibeDock server v0.2.0") is drawn as it is,
        // shortened to the corner. On narrow screens the corner ends before the mood and a temperature in the same row:
        // the text gets smaller, then loses "IP: ", then its leading segments.
        // Rounded panels: the text starts where the corner leaves room near the bottom edge.
        int left=Math.max(10,inset(4)+3),size=12,room=Math.min(150,w)-4-left;
        int moodMargin=Math.max(6,inset(12));
        if(rightMood!=null)room=Math.min(room,w-moodMargin-iw(rightMood)-4-left);
        room=Math.min(room,weatherLeft-4-left);
        String full=ip.contains(" ")?ip:"IP: "+ip,label=fit(g,scheme,full,size,room);
        if(!full.equals(label)){size=10;label=fit(g,scheme,full,size,room);}
        // An address that does not fit loses its prefix, then its leading segments: the last ones tell displays apart.
        if(!full.equals(label)&&!ip.contains(" "))label=fitTail(g,scheme,ip,size,room);
        if(label!=null)text(g,scheme,label,left,y+51,size,desktopText(scheme),false);
        // Mood right, in front of the IP corner. Developer themes raise it another 6 px inside the same slot.
        if(rightMood!=null)put(g,rightMood,w-moodMargin-iw(rightMood),y+50-ih(rightMood)-(scheme.developer()?6:0));
    }
    /** The update time centred on the screen, kept between from and to; left out when it does not fit. */
    private void centredTime(Graphics2D g,Scheme scheme,String time,int from,int to,int baseline){
        g.setFont(new Font(scheme.font(),Font.PLAIN,10));int width=g.getFontMetrics().stringWidth(time);
        int x=Math.max(from,Math.min((w-width)/2,to-width));
        if(x+width<=to)text(g,scheme,time,x,baseline,10,desktopMuted(scheme),false);
    }
    /** Light colour schemes: a thin grey outline keeps white icons (clouds, snow) visible. */
    private void icon(Graphics2D g,Scheme scheme,BufferedImage icon,int x,int y){
        if(scheme.light()&&(scheme.style==Scheme.Style.MODERN||scheme.illustrated()&&scheme.style!=Scheme.Style.INK)){
            var mask=mask(icon,0x9aa0a6);
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)if(dx!=0||dy!=0)put(g,mask,x+dx,y+dy);
        }
        put(g,icon,x,y);
    }
    private Image encode(BufferedImage image,Scheme scheme) throws IOException {
        int pw=image.getWidth(),ph=image.getHeight();
        byte[] pixels=new byte[scale==1?pw*ph*2:0];int offset=0;
        for(int y=0;y<ph;y++)for(int x=0;x<pw;x++){
            int rgb=image.getRGB(x,y);
            // LCD: every colour (emoji, the IP corner) becomes one of the four shades.
            if(scheme.style==Scheme.Style.LCD){rgb=lcdShade(rgb);image.setRGB(x,y,rgb);}
            // Terminal schemes: every other row darker, like the scan lines of an old monitor (rows of the display).
            if(scheme.style==Scheme.Style.TERMINAL&&(((int)(y/scale))&1)==1)rgb=(((rgb>>16&255)*7/10)<<16)|(((rgb>>8&255)*7/10)<<8)|((rgb&255)*7/10);
            // Scaled (web displays): full colour; devices: RGB565, and the PNG shows what they show.
            if(scale!=1){if(scheme.style==Scheme.Style.TERMINAL)image.setRGB(x,y,rgb);continue;}
            int r=(rgb>>19)&31,b=(rgb>>3)&31,green=(rgb>>10)&63;
            int value=(r<<11)|(green<<5)|b;
            pixels[offset++]=(byte)(value>>8);pixels[offset++]=(byte)value;
            image.setRGB(x,y,((r*255/31)<<16)|((green*255/63)<<8)|(b*255/31));
        }
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return new Image(bytes.toByteArray(),pixels);
    }
    /** The LCD shade of a colour: its own when it is one, otherwise by brightness. */
    static int lcdShade(int rgb){
        rgb&=0xffffff;for(int shade:Scheme.LCD_SHADES)if(shade==rgb)return rgb;
        int luma=(299*(rgb>>16&255)+587*(rgb>>8&255)+114*(rgb&255))/1000;
        return Scheme.LCD_SHADES[luma<70?0:luma<130?1:luma<195?2:3];
    }
    private static BufferedImage resize(BufferedImage icon,int size){return resize(icon,size,size);}
    private static BufferedImage resize(BufferedImage icon,int width,int height){
        if(icon==null)return null;var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
        try{g.drawImage(icon.getScaledInstance(width,height,java.awt.Image.SCALE_AREA_AVERAGING),0,0,null);}finally{g.dispose();}return image;
    }
    /** Half-width provider: the theme's own window or heading at half width; compact text, nothing scaled. */
    private void compact(Graphics2D g,Scheme scheme,String title,UsageService.Provider p,int height,boolean bottom,Battery icon){
        title=ThemeArtwork.title(scheme,title);
        ThemeArtwork.band(g,scheme,0,0,w/2,height);
        if(p==null)p=new UsageService.Provider("unconfigured",null,null,null);
        int top=16;
        switch(scheme.style){
            case MAC -> macWindow(g,scheme,title,null,22,2,w/2-6,height-(bottom?8:4),icon);
            case WINDOWS -> windowsWindow(g,scheme,title,null,22,2,w/2-4,height-(bottom?6:3),icon);
            case FLUENT -> fluentWindow(g,scheme,title,null,22,2,w/2-4,height-(bottom?6:3),icon);
            case MACOS -> macosWindow(g,scheme,title,null,22,2,w/2-4,height-(bottom?6:3),icon);
            case CONSOLE -> console(g,scheme,title,null,22,2,w/2-4,height-(bottom?6:3),icon);
            default -> {
                int reserve=icon==null?0:BATTERY_ROOM+ThemeArtwork.cornerRoom(scheme);
                if(!ThemeArtwork.heading(g,scheme,title,6,top,w/2-12-reserve,10))text(g,scheme,(scheme.style==Scheme.Style.TERMINAL?"> ":"")+title,6,top,10,scheme.title,true);
                if(icon!=null)batteryIcon(g,icon,w/2-6-ThemeArtwork.cornerRoom(scheme),top-4,scheme.text,scheme.warning);
            }
        }
        if(!p.status().equals("ok")){
            String status=p.status().equals("rate_limited")?"Limited":DeviceSender.statusLabel(p.status());
            int y=scheme.windowed()||scheme.style==Scheme.Style.CONSOLE?top+14:top+10;
            text(g,scheme,status.length()>18?status.substring(0,18):status,6,y,8,scheme.style==Scheme.Style.WINDOWS?scheme.muted:scheme.warning,false);
        }
        // Half width: the label column fits about four characters, so "Month" becomes "30d".
        if(p.period()!=null)compactWindow(g,scheme,p.period().equals("Month")?"30d":p.period(),p.week(),45);
        else{compactWindow(g,scheme,"5h",p.fiveHour(),45);compactWindow(g,scheme,"Week",p.week(),74);}
    }
    private void compactWindow(Graphics2D g,Scheme scheme,String label,ProviderClient.Window q,int y){
        if(miniWindow(g,scheme,label,q,0,y,w/2))text(g,scheme,ThemeArtwork.reset(scheme,reset(q)),6,y+10,8,scheme.muted,false);
    }
    /** Label, small bar and remaining percent in a column from x0 that is span wide; false without readings. */
    private boolean miniWindow(Graphics2D g,Scheme scheme,String label,ProviderClient.Window q,int x0,int y,int span){
        text(g,scheme,label,x0+6,y,9,scheme.text,false);int x=x0+32,width=span-71,right=x0+span-6;
        if(q==null){right(g,scheme,"--",right,y,9,scheme.muted);return false;}
        double left=Math.max(0,Math.min(100,100-q.usedPercent()));int fill=(int)Math.round(width*left/100);
        if(scheme.style==Scheme.Style.TERMINAL){int n=(int)Math.round(5*left/100);text(g,scheme,"["+"#".repeat(n)+".".repeat(5-n)+"]",x-2,y,9,scheme.text,false);}
        else if(scheme.style==Scheme.Style.WINDOWS){bevel(g,x,y-7,width,9,false);g.setColor(new Color(0x000080));for(int b=0;b<(int)Math.round((width-6)/7*left/100);b++)g.fillRect(x+3+b*7,y-5,5,4);}
        else if(scheme.style==Scheme.Style.MAC){g.setColor(Color.BLACK);g.drawRect(x,y-6,width,6);if(fill>0)g.fillRect(x+1,y-5,Math.min(width-2,fill),5);}
        else bar(g,scheme,x,y-6,width,6,left);
        right(g,scheme,String.format(Locale.ROOT,"%.0f%%",left),right,y,9,scheme.text);
        return true;
    }
    /**
     * Compact band, 46 px: the theme's heading or window title with the status, below it one row with the small bars of
     * both windows side by side (a single window spans the width). No reset times.
     */
    private void row(Graphics2D g,Scheme scheme,String title,UsageService.Provider p,int y,int height,boolean bottom,Battery icon){
        title=ThemeArtwork.title(scheme,title);
        ThemeArtwork.band(g,scheme,0,y,w,height);
        if(p==null)p=new UsageService.Provider("unconfigured",null,null,null);
        boolean held=p.status().equals("rate_limited")&&p.observedAt()!=null&&(p.fiveHour()!=null||p.week()!=null);
        String status=held?"Limited \u00b7 "+DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochSecond(p.observedAt()))
                :p.status().equals("ok")?null:DeviceSender.statusLabel(p.status());
        int top=y+16,bars=y+height-11;
        switch(scheme.style){
            case MAC -> macWindow(g,scheme,title,status,y+22,4,w-9,height-(bottom?8:4),icon);
            case WINDOWS -> windowsWindow(g,scheme,title,status,y+22,4,w-8,height-(bottom?6:3),icon);
            case FLUENT -> fluentWindow(g,scheme,title,status,y+22,4,w-8,height-(bottom?6:3),icon);
            case MACOS -> macosWindow(g,scheme,title,status,y+22,4,w-8,height-(bottom?6:3),icon);
            case CONSOLE -> console(g,scheme,title,status,y+22,2,w-4,height-(bottom?6:3),icon);
            default -> {
                boolean terminal=scheme.style==Scheme.Style.TERMINAL;
                g.setFont(new Font(scheme.font(),Font.BOLD,13));
                int reserve=icon==null?0:BATTERY_ROOM+ThemeArtwork.cornerRoom(scheme);
                int titleWidth=g.getFontMetrics().stringWidth((terminal?"> ":"")+title);
                if(scheme.developer()&&status!=null)titleWidth=Math.min(titleWidth,w/2-12);
                int room=w-20-titleWidth-8-reserve;
                if(!ThemeArtwork.heading(g,scheme,title,10,top,(status!=null?Math.min(w-20,w/2-12):w-20)-reserve,13))text(g,scheme,terminal?"> "+title.toUpperCase(Locale.ROOT):title,10,top,13,scheme.title,true);
                if(status!=null){status=fit(g,scheme,terminal?status.toUpperCase(Locale.ROOT):status,10,room);if(status!=null)right(g,scheme,status,w-10-reserve,top,10,scheme.warning);}
                if(icon!=null)batteryIcon(g,icon,w-10-ThemeArtwork.cornerRoom(scheme),top-5,scheme.text,scheme.warning);
            }
        }
        if(p.period()!=null)miniWindow(g,scheme,p.period().equals("Month")?"30d":p.period(),p.week(),4,bars,w-8);
        else{miniWindow(g,scheme,"5h",p.fiveHour(),4,bars,w/2-4);miniWindow(g,scheme,"Wk",p.week(),w/2,bars,w/2-4);}
    }
    private static boolean windowed(Scheme scheme){return scheme.windowed();}
    /** Reset time of a window; a window that has not started yet (no usage in it) has none. */
    private String reset(ProviderClient.Window w){
        return w.resetsAt()<=0?"Not started":"Reset "+DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(zone).format(Instant.ofEpochSecond(w.resetsAt()));
    }
    /** One quota window: label, bar with the remaining share, remaining percent and reset time. */
    private void window(Graphics2D g,Scheme scheme,String name,ProviderClient.Window q,int y){
        text(g,scheme,name,10,y,12,scheme.text,false);
        // Narrow screens: a shorter label column and bar; the percentage stays right-aligned.
        int x=w<200?46:60,width=w<200?w-x-42:w-120,height=10,top=y-9,right=w-10;
        g.setFont(new Font(scheme.font(),Font.PLAIN,12));
        width=Math.min(width,right-g.getFontMetrics().stringWidth("100 %")-4-x);
        if(scheme.style==Scheme.Style.TERMINAL||scheme.style==Scheme.Style.WINDOWS||scheme.style==Scheme.Style.MAC){
            if(q!=null){
                double left=Math.max(0,Math.min(100,100-q.usedPercent()));
                if(scheme.style==Scheme.Style.TERMINAL){
                    int cells=Math.max(4,width*14/120),filled=(int)Math.round(cells*left/100);
                    text(g,scheme,"["+"#".repeat(filled)+".".repeat(cells-filled)+"]",x-4,y,12,scheme.text,false);
                }else if(scheme.style==Scheme.Style.WINDOWS){
                    // Sunken field with navy blocks, like a Windows 95 progress bar.
                    bevel(g,x,top-1,width,height+2,false);
                    int blocks=(int)Math.round((width-4)/8*left/100);
                    g.setColor(new Color(0x000080));
                    for(int b=0;b<blocks;b++)g.fillRect(x+3+b*8,top+2,6,height-3);
                }else{
                    g.setColor(Color.BLACK);g.drawRect(x,top,width,height);
                    int fill=(int)Math.round((width-3)*left/100);
                    if(fill>0)g.fillRect(x+2,top+2,fill,height-3);
                }
                right(g,scheme,String.format(Locale.ROOT,"%.0f %%",left),right,y,12,scheme.text);
                text(g,scheme,ThemeArtwork.reset(scheme,reset(q)),x,y+13,10,scheme.muted,false);
            } else right(g,scheme,"--",right,y,12,scheme.muted);
            return;
        }
        if(q!=null){
            double left=Math.max(0,Math.min(100,100-q.usedPercent()));
            bar(g,scheme,x,top,width,height,left);
            right(g,scheme,String.format(Locale.ROOT,"%.0f %%",left),right,y,12,scheme.text);
            text(g,scheme,ThemeArtwork.reset(scheme,reset(q)),x,y+13,10,scheme.muted,false);
        } else{bar(g,scheme,x,top,width,height,-1);right(g,scheme,"--",right,y,12,scheme.muted);}
    }
    /**
     * A quota bar of the modern styles with the remaining share left (0–100; below 0: no reading, the track only):
     * rounded in the colour scale, as blocks on the LCD, hatched on the blueprint, glowing for neon, black with a small
     * coloured dot in the newspaper.
     */
    private void bar(Graphics2D g,Scheme scheme,int x,int top,int width,int height,double left){
        if(scheme.illustrated()){ThemeArtwork.bar(g,scheme,x,top,width,height,left);return;}
        int fill=left<0?0:(int)Math.round(width*left/100);
        switch(scheme.style){
            case LCD -> {
                g.setColor(new Color(scheme.muted));g.drawRect(x,top,width-1,height-1);
                int step=height>6?6:4,blocks=left<0?0:(int)Math.round((width-3)/step*left/100);
                g.setColor(new Color(scheme.text));
                for(int b=0;b<blocks;b++)g.fillRect(x+2+b*step,top+2,step-2,height-4);
            }
            case BLUEPRINT -> {
                g.setColor(new Color(scheme.text));g.drawRect(x,top,width-1,height-1);
                if(fill>2){
                    var hatch=(Graphics2D)g.create();
                    try{
                        hatch.clipRect(x+1,top+1,fill-2,height-2);hatch.setColor(new Color(scheme.title));
                        for(int d=-height;d<fill;d+=4)hatch.drawLine(x+d,top+height,x+d+height,top);
                    }finally{hatch.dispose();}
                    g.drawLine(x+fill-1,top,x+fill-1,top+height-1);
                }
            }
            case PRINT -> {
                g.setColor(new Color(scheme.track));g.fillRect(x,top+height/4,width,Math.max(2,height/2));
                if(fill>0){
                    g.setColor(new Color(scheme.text));g.fillRect(x,top+height/4,fill,Math.max(2,height/2));
                    g.setColor(new Color(barColor(left)));g.fillOval(x+fill-height/2,top,height,height);
                }
            }
            default -> {
                g.setColor(new Color(scheme.track));g.fillRoundRect(x,top,width,height,height,height);
                if(fill>0){
                    int colour=barColor(left),bar=Math.max(fill,height);
                    if(scheme.style==Scheme.Style.NEON){
                        // A soft glow around the bar in its own colour.
                        for(int r=3;r>=1;r--){g.setColor(new Color(colour>>16&255,colour>>8&255,colour&255,28+12*(3-r)));g.fillRoundRect(x-r,top-r,bar+2*r,height+2*r,height+2*r,height+2*r);}
                    }
                    g.setColor(new Color(colour));g.fillRoundRect(x,top,bar,height,height,height);
                }
            }
        }
    }
    /** What some styles draw behind everything: the blueprint's grid, the neon horizon. */
    private void backdrop(Graphics2D g,Scheme scheme){
        if(scheme.style==Scheme.Style.BLUEPRINT){
            for(int i=0;i*12<Math.max(w,h);i++){
                g.setColor(new Color(i%4==0?0x3a6cab:0x285a98));
                g.drawLine(i*12,0,i*12,h);g.drawLine(0,i*12,w,i*12);
            }
        }else if(scheme.style==Scheme.Style.NEON){
            // A grid running to the horizon in the lower part, faint so that it stays behind the text.
            int horizon=h*7/10,cx=w/2;var line=new Color(0xff,0x4f,0xd8,40);g.setColor(line);
            for(int i=1,y=horizon;y<h;i++,y=horizon+i*i*2)g.drawLine(0,y,w,y);
            for(int i=-8;i<=8;i++)g.drawLine(cx,horizon,cx+i*w/4,h);
            g.setColor(new Color(0x26,0xf0,0xff,60));g.drawLine(0,horizon,w,horizon);
        }
    }
    }
    /** The text, shortened with an ellipsis to fit the width at this size; null when not even the ellipsis fits. */
    private static String fit(Graphics2D g,Scheme scheme,String value,int size,int width){
        g.setFont(new Font(scheme.font(),Font.PLAIN,size));var metrics=g.getFontMetrics();
        if(metrics.stringWidth(value)<=width)return value;
        for(int n=value.length()-1;n>0;n--){String shorter=value.substring(0,n).strip()+"…";if(metrics.stringWidth(shorter)<=width)return shorter;}
        return null;
    }
    /** An address shortened from the left by whole segments ("…123.193"); null when not even the last one fits. */
    static String fitTail(Graphics2D g,Scheme scheme,String address,int size,int width){
        g.setFont(new Font(scheme.font(),Font.PLAIN,size));var metrics=g.getFontMetrics();
        if(metrics.stringWidth(address)<=width)return address;
        for(int i=0;i<address.length();i++){
            char c=address.charAt(i);
            if(c!='.'&&c!=':')continue;
            String tail="…"+address.substring(i+1);
            if(metrics.stringWidth(tail)<=width)return tail;
        }
        return null;
    }
    /** Windows 11 and macOS: windows on a coloured desktop, with white text outside them. */
    private static boolean wallpapered(Scheme scheme){return scheme.style==Scheme.Style.FLUENT||scheme.style==Scheme.Style.MACOS;}
    /** The status in the room a window title leaves; shortened, or left out when not even the ellipsis fits. */
    private static String fitStatus(Graphics2D g,Scheme scheme,String status,int room){return status==null?null:fit(g,scheme,status,9,room);}
    /** Windows 11: light rounded window with a soft shadow, the title left and the three caption buttons right. */
    private static void fluentWindow(Graphics2D g,Scheme scheme,String title,String status,int top,int x,int width,int height,Battery icon){
        int y=top-19,bar=19;
        g.setColor(new Color(0,0,0,60));g.fillRoundRect(x+1,y+2,width,height,12,12);
        g.setColor(new Color(0xf3f3f3));g.fillRoundRect(x,y,width,height,12,12);
        g.setColor(new Color(0xd0d0d0));g.drawRoundRect(x,y,width-1,height-1,12,12);
        g.setFont(new Font(scheme.font(),Font.BOLD,11));g.setColor(new Color(scheme.title));g.drawString(title,x+9,y+bar-5);
        int titleEnd=x+9+g.getFontMetrics().stringWidth(title);
        // Minimise, maximise, close. A narrow window with the battery icon leaves out minimise and maximise for it.
        int cy=y+bar/2-1,cx=x+width-12;g.setColor(new Color(0x5c5c5c));
        g.drawLine(cx-3,cy-3,cx+3,cy+3);g.drawLine(cx+3,cy-3,cx-3,cy+3);
        boolean tight=icon!=null&&titleEnd+6>cx-40-BATTERY_WIDTH;
        if(!tight){g.drawRect(cx-19,cy-3,6,6);g.drawLine(cx-34,cy,cx-28,cy);}
        int reserve=icon==null?0:BATTERY_ROOM;
        if(icon!=null)batteryIcon(g,icon,tight?cx-10:cx-40,cy,0x5c5c5c,scheme.warning);
        status=fitStatus(g,scheme,status,cx-38-titleEnd-8-reserve);
        if(status!=null){g.setFont(new Font(scheme.font(),Font.PLAIN,9));g.setColor(new Color(scheme.warning));
            g.drawString(status,cx-40-reserve-g.getFontMetrics().stringWidth(status),y+bar-6);}
    }
    /** macOS: rounded window with the red, yellow and green buttons, the title in the middle (left of the status). */
    private static void macosWindow(Graphics2D g,Scheme scheme,String title,String status,int top,int x,int width,int height,Battery icon){
        int y=top-19,bar=19;
        g.setColor(new Color(0,0,0,70));g.fillRoundRect(x+1,y+2,width,height,14,14);
        g.setColor(new Color(0xececec));g.fillRoundRect(x,y,width,height,14,14);
        g.setColor(new Color(0xbdbdbd));g.drawRoundRect(x,y,width-1,height-1,14,14);
        int[] lights={0xff5f57,0xfebc2e,0x28c840};
        for(int i=0;i<3;i++){g.setColor(new Color(lights[i]));g.fillOval(x+8+i*13,y+bar/2-4,9,9);}
        g.setFont(new Font(scheme.font(),Font.BOLD,11));var metrics=g.getFontMetrics();int tw=metrics.stringWidth(title);
        // Centred; left beside the lights with a status, or when the battery icon leaves no room for the centred title.
        // A narrow window moves title and icon a little apart, nearer to the lights and the edge.
        int centre=Math.max(x+50,x+(width-tw)/2);
        boolean tight=icon!=null&&status==null&&x+50+tw+4>x+width-8-BATTERY_WIDTH;
        int tx=tight?x+46:status==null&&(icon==null||centre+tw+6<=x+width-8-BATTERY_WIDTH)?centre:x+50;
        g.setColor(new Color(scheme.title));g.drawString(title,tx,y+bar-5);
        int reserve=icon==null?0:BATTERY_ROOM;
        if(icon!=null)batteryIcon(g,icon,x+width-(tight?5:8),y+bar/2,0x4a4a4a,scheme.warning);
        status=fitStatus(g,scheme,status,x+width-8-reserve-(tx+tw+8));
        if(status!=null){g.setFont(new Font(scheme.font(),Font.PLAIN,9));g.setColor(new Color(scheme.warning));
            g.drawString(status,x+width-8-reserve-g.getFontMetrics().stringWidth(status),y+bar-6);}
    }
    /**
     * Starship console: a coloured bar with a rounded elbow down the left side, the title in capitals on the bar, the
     * status at its end. The rows below sit right of the elbow.
     */
    private static void console(Graphics2D g,Scheme scheme,String title,String status,int top,int x,int width,int height,Battery icon){
        int y=top-17,bar=13,arm=6,r=6,outer=10,inner=arm+r;
        // The elbow: its outer corner rounded, the inner one with a fillet, then the bar to a round end.
        var elbow=new java.awt.geom.Area(new Rectangle(x,y,inner,bar+r));
        var corner=new java.awt.geom.Area(new Rectangle(x,y,outer,outer));corner.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(x,y,2*outer,2*outer)));
        elbow.subtract(corner);
        var fillet=new java.awt.geom.Area(new Rectangle(x+arm,y+bar,r,r));fillet.intersect(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(x+arm,y+bar,2*r,2*r)));
        elbow.subtract(fillet);
        elbow.add(new java.awt.geom.Area(new Rectangle(x+inner,y,width-inner-bar/2,bar)));
        elbow.add(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(x+width-bar,y,bar,bar)));
        g.setColor(new Color(scheme.title));g.fill(elbow);
        // The arm below, a second colour after a small gap.
        g.setColor(new Color(scheme.muted));g.fillRect(x,y+bar+r+2,arm,Math.max(0,height-bar-r-4));
        g.setFont(new Font(scheme.font(),Font.BOLD,10));g.setColor(Color.BLACK);
        String caps=title.toUpperCase(Locale.ROOT);g.drawString(caps,x+inner+4,y+bar-3);
        int capsEnd=x+inner+4+g.getFontMetrics().stringWidth(caps);
        int reserve=icon==null?0:BATTERY_ROOM;
        if(icon!=null)batteryIcon(g,icon,x+width-bar-4,y+bar/2,0x000000,0x000000);
        status=fitStatus(g,scheme,status==null?null:status.toUpperCase(Locale.ROOT),x+width-bar-capsEnd-12-reserve);
        if(status!=null){g.setFont(new Font(scheme.font(),Font.BOLD,9));int sw=g.getFontMetrics().stringWidth(status),sx=x+width-bar-4-reserve-sw;
            g.setColor(new Color(scheme.background));g.fillRect(sx-4,y,sw+8,bar);
            g.setColor(new Color(scheme.warning));g.drawString(status,sx,y+bar-3);}
    }
    /** A classic Mac window per provider: frame with drop shadow and a pinstriped title bar with the name in the middle. */
    private static void macWindow(Graphics2D g,Scheme scheme,String title,String status,int top,int x,int width,int height,Battery icon){
        int y=top-19,bar=16;
        g.setColor(Color.BLACK);g.fillRect(x+2,y+2,width,height);
        g.setColor(Color.WHITE);g.fillRect(x,y,width,height);
        g.setColor(Color.BLACK);g.drawRect(x,y,width,height);g.drawLine(x,y+bar,x+width,y+bar);
        for(int line=y+3;line<y+bar-1;line+=2)g.drawLine(x+3,line,x+width-3,line);
        g.setFont(new Font(scheme.font(),Font.BOLD,12));
        // Centred like a Mac window title; with a status on the right the title moves to the left.
        int w=g.getFontMetrics().stringWidth(title),tx=status==null?x+(width-w)/2:x+14;
        g.setColor(Color.WHITE);g.fillRect(tx-6,y+1,w+12,bar-1);
        g.setColor(Color.BLACK);g.drawString(title,tx,y+bar-4);
        // Narrow screens: the status is shortened to the room right of the title.
        int reserve=icon==null?0:BATTERY_ROOM;
        if(icon!=null){g.setColor(Color.WHITE);g.fillRect(x+width-6-BATTERY_WIDTH-4,y+1,BATTERY_WIDTH+8,bar-1);batteryIcon(g,icon,x+width-6,y+bar/2,0x000000,0x000000);}
        if(status!=null)status=fit(g,scheme,status,9,x+width-6-4-(tx+w+6)-4-reserve);
        if(status!=null){
            g.setFont(new Font(scheme.font(),Font.PLAIN,9));
            int sw=g.getFontMetrics().stringWidth(status),sx=x+width-sw-6-reserve;
            g.setColor(Color.WHITE);g.fillRect(sx-4,y+1,sw+8,bar-1);
            g.setColor(Color.BLACK);g.drawString(status,sx,y+bar-5);
        }
    }
    /** A Windows 95 style window: grey bevelled frame, navy title bar with white bold title and a close button. */
    private static void windowsWindow(Graphics2D g,Scheme scheme,String title,String status,int top,int x,int width,int height,Battery icon){
        int y=top-19,bar=16;
        g.setColor(new Color(0xc0c0c0));g.fillRect(x,y,width,height);
        bevel(g,x,y,width,height,true);
        g.setColor(new Color(0x000080));g.fillRect(x+3,y+3,width-6,bar-1);
        g.setFont(new Font(scheme.font(),Font.BOLD,11));g.setColor(Color.WHITE);g.drawString(title,x+7,y+bar-2);
        int titleEnd=x+7+g.getFontMetrics().stringWidth(title);
        int bx=x+width-3-15,by=y+5;
        // Narrow screens: the status is shortened to the room between title and close box.
        int reserve=icon==null?0:BATTERY_ROOM;
        if(icon!=null)batteryIcon(g,icon,bx-5,y+3+(bar-1)/2,0xffffff,0xffffff);
        if(status!=null)status=fit(g,scheme,status,9,bx-6-titleEnd-6-reserve);
        g.setColor(new Color(0xc0c0c0));g.fillRect(bx,by,13,11);bevel(g,bx,by,13,11,true);
        g.setColor(Color.BLACK);g.drawLine(bx+4,by+3,bx+8,by+7);g.drawLine(bx+8,by+3,bx+4,by+7);
        if(status!=null){
            g.setFont(new Font(scheme.font(),Font.PLAIN,9));
            g.setColor(Color.WHITE);g.drawString(status,bx-6-reserve-g.getFontMetrics().stringWidth(status),y+bar-3);
        }
    }
    /** The battery icon: 16 × 8 px, the room it takes beside a status (icon and gap). */
    static final int BATTERY_WIDTH=16,BATTERY_ROOM=BATTERY_WIDTH+5;
    /**
     * A battery ending at right, centred on cy: outline and nub in the colour, filled to its level (the warning colour
     * below 20 %); without a level three dots, so a board without measurement does not look like an empty battery.
     */
    private static void batteryIcon(Graphics2D g,Battery battery,int right,int cy,int colour,int warning){
        int x=right-BATTERY_WIDTH,y=cy-4;
        g.setColor(new Color(colour));g.drawRect(x,y,13,7);g.fillRect(x+14,y+2,2,4);
        if(battery.level()==null){for(int dx=3;dx<=9;dx+=3)g.fillRect(x+dx,y+3,2,2);return;}
        if(battery.level()==0)return;
        g.setColor(new Color(battery.level()<20?warning:colour));
        g.fillRect(x+2,y+2,Math.max(1,(int)Math.round(10*battery.level()/100.0)),4);
    }
    /** Two-pixel 3D border: raised (light top left, dark bottom right) or sunken (the other way round). */
    private static void bevel(Graphics2D g,int x,int y,int w,int h,boolean raised){
        Color outerLight=raised?new Color(0xdfdfdf):new Color(0x808080),outerDark=raised?Color.BLACK:Color.WHITE;
        Color innerLight=raised?Color.WHITE:Color.BLACK,innerDark=raised?new Color(0x808080):new Color(0xdfdfdf);
        g.setColor(outerLight);g.drawLine(x,y,x+w-1,y);g.drawLine(x,y,x,y+h-1);
        g.setColor(outerDark);g.drawLine(x,y+h-1,x+w-1,y+h-1);g.drawLine(x+w-1,y,x+w-1,y+h-1);
        g.setColor(innerLight);g.drawLine(x+1,y+1,x+w-2,y+1);g.drawLine(x+1,y+1,x+1,y+h-2);
        g.setColor(innerDark);g.drawLine(x+1,y+h-2,x+w-2,y+h-2);g.drawLine(x+w-2,y+1,x+w-2,y+h-2);
    }
    /** Text outside the windows: on the Windows desktop white, otherwise the scheme's text colours. */
    private static int desktopText(Scheme scheme){return scheme.style==Scheme.Style.WINDOWS||wallpapered(scheme)?0xffffff:scheme.text;}
    private static int desktopMuted(Scheme scheme){return scheme.style==Scheme.Style.WINDOWS?0xd8f0f0:wallpapered(scheme)?0xe2e6f0:scheme.muted;}
    /**
     * Icons in the look of the scheme: tinted phosphor for terminals, white lines for the blueprint, grey for the
     * newspaper, 1-bit ordered dithering for the Mac (the LCD reduces the whole image to its shades, see encode).
     */
    static BufferedImage styled(BufferedImage icon,Scheme scheme){
        return styled(icon,scheme,1);
    }
    private static BufferedImage styled(BufferedImage icon,Scheme scheme,int contourWidth){
        if(scheme.illustrated())return ThemeArtwork.icon(icon,scheme);
        var s=scheme.style;
        if(icon!=null&&s==Scheme.Style.LCD)return contoured(icon,scheme.text,contourWidth);
        if(icon==null||(s!=Scheme.Style.TERMINAL&&s!=Scheme.Style.MAC&&s!=Scheme.Style.BLUEPRINT&&s!=Scheme.Style.PRINT))return icon;
        int[] bayer={0,8,2,10,12,4,14,6,3,11,1,9,15,7,13,5};
        var out=new BufferedImage(icon.getWidth(),icon.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<icon.getHeight();y++)for(int x=0;x<icon.getWidth();x++){
            int argb=icon.getRGB(x,y),alpha=argb>>>24;
            if(alpha<96)continue;
            int luma=(299*(argb>>16&255)+587*(argb>>8&255)+114*(argb&255))/1000;
            if(s==Scheme.Style.TERMINAL||s==Scheme.Style.BLUEPRINT){
                int level=Math.max(s==Scheme.Style.BLUEPRINT?25:70,luma),c=scheme.text;
                out.setRGB(x,y,0xff000000|((c>>16&255)*level/255)<<16|((c>>8&255)*level/255)<<8|(c&255)*level/255);
            }else if(s==Scheme.Style.PRINT){
                int level=Math.min(210,luma);out.setRGB(x,y,(alpha<<24)|level<<16|level<<8|level);
            }else{
                boolean dark=luma*16/256<=bayer[(y&3)*4+(x&3)];
                out.setRGB(x,y,dark?0xff000000:0xffffffff);
            }
        }
        return s==Scheme.Style.BLUEPRINT?contoured(out,scheme.text,contourWidth):out;
    }
    /** A contour inside the alpha silhouette: keeps the icon's size, position and transparent surroundings unchanged. */
    private static BufferedImage contoured(BufferedImage icon,int rgb,int width){
        int w=icon.getWidth(),h=icon.getHeight();var out=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int argb=icon.getRGB(x,y);
            if((argb>>>24)>=96){
                boolean edge=x<width||x+width>=w||y<width||y+width>=h
                        ||(icon.getRGB(x-width,y)>>>24)<96||(icon.getRGB(x+width,y)>>>24)<96
                        ||(icon.getRGB(x,y-width)>>>24)<96||(icon.getRGB(x,y+width)>>>24)<96;
                if(edge)argb=(argb&0xff000000)|rgb;
            }
            out.setRGB(x,y,argb);
        }
        return out;
    }
    /** Draws the icon's shape one pixel larger in every direction in a solid colour, as an outline behind the icon. */
    static void outline(Graphics2D g,BufferedImage icon,int x,int y,int rgb){
        var mask=mask(icon,rgb);
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)if(dx!=0||dy!=0)g.drawImage(mask,x+dx,y+dy,null);
    }
    /** The icon's shape in a solid colour. */
    static BufferedImage mask(BufferedImage icon,int rgb){
        var mask=new BufferedImage(icon.getWidth(),icon.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int j=0;j<icon.getHeight();j++)for(int i=0;i<icon.getWidth();i++){
            int alpha=icon.getRGB(i,j)>>>24;
            if(alpha>96)mask.setRGB(i,j,0xff000000|rgb);
        }
        return mask;
    }
    /** Remaining share and colour: green from 60 % up, then smoothly over yellow-green, yellow and orange to red at 8 %. */
    private static final double[] BAR_STOPS={60,45,32,20,8};
    private static final int[] BAR_COLOURS={0x34c759,0xa3d13b,0xffcc00,0xff9f0a,0xff453a};
    static int barColor(double left){
        if(!(left<BAR_STOPS[0]))return BAR_COLOURS[0];
        for(int i=1;i<BAR_STOPS.length;i++){
            if(left>=BAR_STOPS[i]){
                double t=(BAR_STOPS[i-1]-left)/(BAR_STOPS[i-1]-BAR_STOPS[i]);return mix(BAR_COLOURS[i-1],BAR_COLOURS[i],t);
            }
        }
        return BAR_COLOURS[BAR_COLOURS.length-1];
    }
    private static int mix(int a,int b,double t){
        int r=(int)Math.round(((a>>16)&255)*(1-t)+((b>>16)&255)*t),g=(int)Math.round(((a>>8)&255)*(1-t)+((b>>8)&255)*t),bl=(int)Math.round((a&255)*(1-t)+(b&255)*t);
        return (r<<16)|(g<<8)|bl;
    }
    private static void right(Graphics2D g,Scheme scheme,String value,int x,int y,int size,int color){
        g.setFont(new Font(scheme.font(),Font.PLAIN,size));
        text(g,scheme,value,x-g.getFontMetrics().stringWidth(value),y,size,color,false);
    }
    private static void text(Graphics2D g,Scheme scheme,String value,int x,int y,int size,int color,boolean bold){
        g.setFont(new Font(scheme.font(),bold?Font.BOLD:Font.PLAIN,size));g.setColor(new Color(color));g.drawString(value,x,y);
    }
}
