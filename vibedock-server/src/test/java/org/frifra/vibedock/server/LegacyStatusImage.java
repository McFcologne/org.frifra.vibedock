// Frozen pre-layout renderer: pixel-compatibility oracle, never used by the application.
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

class LegacyStatusImage {
    public record Image(byte[] png,byte[] rgb565) {}
    /** Optional parts of a device's image: emoji bottom right, colour scheme, weather icon and temperature bottom left. */
    public record Look(BufferedImage emoji,Scheme scheme,BufferedImage weatherIcon,String weatherText) {
        public Look { scheme=scheme==null?Scheme.MIDNIGHT:scheme; }
        static final Look PLAIN=new Look(null,Scheme.MIDNIGHT,null,null);
    }
    private final ZoneId zone;
    public LegacyStatusImage(@Value("${vibedock.time-zone}") String zone){this.zone=ZoneId.of(zone);}
    public Image render(UsageService.Snapshot snapshot,String ip) throws IOException { return render(snapshot,ip,Look.PLAIN); }
    public Image render(UsageService.Snapshot snapshot,String ip,BufferedImage emoji) throws IOException {
        return render(snapshot,ip,new Look(emoji,Scheme.MIDNIGHT,null,null));
    }
    public Image render(UsageService.Snapshot snapshot,String ip,Look look) throws IOException {
        var scheme=look.scheme();
        var image=new BufferedImage(240,240,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try{
            g.setColor(new Color(scheme.background));g.fillRect(0,0,240,240);
            // Classic Mac and Windows: no anti-aliasing, crisp pixels like the originals.
            boolean smooth=scheme.style!=Scheme.Style.MAC&&scheme.style!=Scheme.Style.WINDOWS;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,smooth?RenderingHints.VALUE_TEXT_ANTIALIAS_ON:RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,smooth?RenderingHints.VALUE_ANTIALIAS_ON:RenderingHints.VALUE_ANTIALIAS_OFF);
            int top=22;
            for(String name:List.of("codex","claude")){
                var p=snapshot.providers().get(name);
                String title=name.equals("codex")?"Codex":"Claude";
                // Rate limited with last known values: keep them and show the time they were read.
                boolean held=p.status().equals("rate_limited")&&p.observedAt()!=null&&(p.fiveHour()!=null||p.week()!=null);
                String status=held?"Rate limited \u00b7 "+DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochSecond(p.observedAt()))
                        :p.status().equals("ok")?null:DeviceSender.statusLabel(p.status());
                switch(scheme.style){
                    case TERMINAL -> {
                        text(g,scheme,"> "+title.toUpperCase(Locale.ROOT),10,top,15,scheme.title,true);
                        if(status!=null)right(g,scheme,status.toUpperCase(Locale.ROOT),230,top,10,scheme.warning);
                    }
                    case MAC -> macWindow(g,scheme,title,status,top);
                    case WINDOWS -> windowsWindow(g,scheme,title,status,top);
                    default -> {
                        text(g,scheme,title,10,top,16,scheme.title,true);
                        if(status!=null)right(g,scheme,status,230,top,10,scheme.warning);
                    }
                }
                window(g,scheme,"5h",p.fiveHour(),top+22);window(g,scheme,"Week",p.week(),top+52);top+=92;
            }
            // Time of the newest provider reading, not of this rendering: the image (and its ETag) only changes with new data.
            var observed=snapshot.providers().values().stream().map(UsageService.Provider::observedAt).filter(Objects::nonNull).max(Long::compare);
            String updated="Updated "+observed.map(t->DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochSecond(t))).orElse("--:--");
            var weatherIcon=styled(look.weatherIcon(),scheme);var emoji=styled(look.emoji(),scheme);
            if(look.weatherText()!=null){
                // Weather bottom left: icon, temperature, then the update time on the same line.
                int x=10;
                if(weatherIcon!=null){
                    // Light colour schemes: a thin grey outline keeps white icons (clouds, snow) visible.
                    if(scheme.light()&&scheme.style==Scheme.Style.MODERN)outline(g,weatherIcon,x,188,0x9aa0a6);
                    g.drawImage(weatherIcon,x,188,null);x+=weatherIcon.getWidth()+5;
                }
                text(g,scheme,look.weatherText(),x,207,16,desktopText(scheme),true);
                x+=g.getFontMetrics().stringWidth(look.weatherText())+10;
                text(g,scheme,updated,x,207,10,desktopMuted(scheme),false);
            } else text(g,scheme,updated,10,212,10,desktopMuted(scheme),false);
            // Emoji bottom right with the same 6 px margin to the right and bottom edge.
            if(emoji!=null)g.drawImage(emoji,234-emoji.getWidth(),234-emoji.getHeight(),null);
            // Footer corner as the device draws it (rows 220-239, columns 0-149); the emoji may reach into the rest.
            g.setColor(new Color(scheme.background));g.fillRect(0,220,150,20);text(g,Scheme.MIDNIGHT,"IP: "+ip,10,235,12,scheme.light()?0x000000:0xffffff,false);
        }finally{g.dispose();}
        byte[] pixels=new byte[240*240*2];int offset=0;
        for(int y=0;y<240;y++)for(int x=0;x<240;x++){
            int rgb=image.getRGB(x,y);
            // Terminal schemes: every other row darker, like the scan lines of an old monitor.
            if(scheme.style==Scheme.Style.TERMINAL&&(y&1)==1)rgb=(((rgb>>16&255)*7/10)<<16)|(((rgb>>8&255)*7/10)<<8)|((rgb&255)*7/10);
            int r=(rgb>>19)&31,b=(rgb>>3)&31,green=(rgb>>10)&63;
            int value=(r<<11)|(green<<5)|b;pixels[offset++]=(byte)(value>>8);pixels[offset++]=(byte)value;
            image.setRGB(x,y,((r*255/31)<<16)|((green*255/63)<<8)|(b*255/31));
        }
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return new Image(bytes.toByteArray(),pixels);
    }
    /** One quota window: label, bar with the remaining share, remaining percent and reset time. */
    private void window(Graphics2D g,Scheme scheme,String name,ProviderClient.Window w,int y){
        text(g,scheme,name,10,y,12,scheme.text,false);
        int x=60,width=120,height=10,top=y-9;
        if(scheme.style!=Scheme.Style.MODERN){
            if(w!=null){
                double left=Math.max(0,Math.min(100,100-w.usedPercent()));
                if(scheme.style==Scheme.Style.TERMINAL){
                    int cells=14,filled=(int)Math.round(cells*left/100);
                    text(g,scheme,"["+"#".repeat(filled)+".".repeat(cells-filled)+"]",x-4,y,12,scheme.text,false);
                }else if(scheme.style==Scheme.Style.WINDOWS){
                    // Sunken field with navy blocks, like a Windows 95 progress bar.
                    bevel(g,x,top-1,width,height+2,false);
                    int blocks=(int)Math.round(14*left/100);
                    g.setColor(new Color(0x000080));
                    for(int b=0;b<blocks;b++)g.fillRect(x+3+b*8,top+2,6,height-3);
                }else{
                    g.setColor(Color.BLACK);g.drawRect(x,top,width,height);
                    int fill=(int)Math.round((width-3)*left/100);
                    if(fill>0)g.fillRect(x+2,top+2,fill,height-3);
                }
                right(g,scheme,String.format(Locale.ROOT,"%.0f %%",left),230,y,12,scheme.text);
                text(g,scheme,"Reset "+DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(zone).format(Instant.ofEpochSecond(w.resetsAt())),x,y+13,10,scheme.muted,false);
            } else right(g,scheme,"--",230,y,12,scheme.muted);
            return;
        }
        g.setColor(new Color(scheme.track));g.fillRoundRect(x,top,width,height,height,height);
        if(w!=null){
            double left=Math.max(0,Math.min(100,100-w.usedPercent()));
            int fill=(int)Math.round(width*left/100);
            if(fill>0){g.setColor(new Color(barColor(left)));g.fillRoundRect(x,top,Math.max(fill,height),height,height,height);}
            right(g,scheme,String.format(Locale.ROOT,"%.0f %%",left),230,y,12,scheme.text);
            text(g,scheme,"Reset "+DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(zone).format(Instant.ofEpochSecond(w.resetsAt())),x,y+13,10,scheme.muted,false);
        } else right(g,scheme,"--",230,y,12,scheme.muted);
    }
    /** A classic Mac window per provider: frame with drop shadow and a pinstriped title bar with the name in the middle. */
    private static void macWindow(Graphics2D g,Scheme scheme,String title,String status,int top){
        int x=4,y=top-19,width=231,height=88,bar=16;
        g.setColor(Color.BLACK);g.fillRect(x+2,y+2,width,height);
        g.setColor(Color.WHITE);g.fillRect(x,y,width,height);
        g.setColor(Color.BLACK);g.drawRect(x,y,width,height);g.drawLine(x,y+bar,x+width,y+bar);
        for(int line=y+3;line<y+bar-1;line+=2)g.drawLine(x+3,line,x+width-3,line);
        g.setFont(new Font(scheme.font(),Font.BOLD,12));
        // Centred like a Mac window title; with a status on the right the title moves to the left.
        int w=g.getFontMetrics().stringWidth(title),tx=status==null?x+(width-w)/2:x+14;
        g.setColor(Color.WHITE);g.fillRect(tx-6,y+1,w+12,bar-1);
        g.setColor(Color.BLACK);g.drawString(title,tx,y+bar-4);
        if(status!=null){
            g.setFont(new Font(scheme.font(),Font.PLAIN,9));
            int sw=g.getFontMetrics().stringWidth(status),sx=x+width-sw-6;
            g.setColor(Color.WHITE);g.fillRect(sx-4,y+1,sw+8,bar-1);
            g.setColor(Color.BLACK);g.drawString(status,sx,y+bar-5);
        }
    }
    /** A Windows 95 style window: grey bevelled frame, navy title bar with white bold title and a close button. */
    private static void windowsWindow(Graphics2D g,Scheme scheme,String title,String status,int top){
        int x=4,y=top-19,width=232,height=89,bar=16;
        g.setColor(new Color(0xc0c0c0));g.fillRect(x,y,width,height);
        bevel(g,x,y,width,height,true);
        g.setColor(new Color(0x000080));g.fillRect(x+3,y+3,width-6,bar-1);
        g.setFont(new Font(scheme.font(),Font.BOLD,11));g.setColor(Color.WHITE);g.drawString(title,x+7,y+bar-2);
        int bx=x+width-3-15,by=y+5;
        g.setColor(new Color(0xc0c0c0));g.fillRect(bx,by,13,11);bevel(g,bx,by,13,11,true);
        g.setColor(Color.BLACK);g.drawLine(bx+4,by+3,bx+8,by+7);g.drawLine(bx+8,by+3,bx+4,by+7);
        if(status!=null){
            g.setFont(new Font(scheme.font(),Font.PLAIN,9));
            g.setColor(Color.WHITE);g.drawString(status,bx-6-g.getFontMetrics().stringWidth(status),y+bar-3);
        }
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
    private static int desktopText(Scheme scheme){return scheme.style==Scheme.Style.WINDOWS?0xffffff:scheme.text;}
    private static int desktopMuted(Scheme scheme){return scheme.style==Scheme.Style.WINDOWS?0xd8f0f0:scheme.muted;}
    /** Icons in the look of the scheme: tinted phosphor for terminals, 1-bit ordered dithering for the Mac. */
    static BufferedImage styled(BufferedImage icon,Scheme scheme){
        if(icon==null||scheme.style==Scheme.Style.MODERN||scheme.style==Scheme.Style.WINDOWS)return icon;
        int[] bayer={0,8,2,10,12,4,14,6,3,11,1,9,15,7,13,5};
        var out=new BufferedImage(icon.getWidth(),icon.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<icon.getHeight();y++)for(int x=0;x<icon.getWidth();x++){
            int argb=icon.getRGB(x,y),alpha=argb>>>24;
            if(alpha<96)continue;
            int luma=(299*(argb>>16&255)+587*(argb>>8&255)+114*(argb&255))/1000;
            if(scheme.style==Scheme.Style.TERMINAL){
                int level=Math.max(70,luma),c=scheme.text;
                out.setRGB(x,y,0xff000000|((c>>16&255)*level/255)<<16|((c>>8&255)*level/255)<<8|(c&255)*level/255);
            }else{
                boolean dark=luma*16/256<=bayer[(y&3)*4+(x&3)];
                out.setRGB(x,y,dark?0xff000000:0xffffffff);
            }
        }
        return out;
    }
    /** Draws the icon's shape one pixel larger in every direction in a solid colour, as an outline behind the icon. */
    static void outline(Graphics2D g,BufferedImage icon,int x,int y,int rgb){
        var mask=new BufferedImage(icon.getWidth(),icon.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int j=0;j<icon.getHeight();j++)for(int i=0;i<icon.getWidth();i++){
            int alpha=icon.getRGB(i,j)>>>24;
            if(alpha>96)mask.setRGB(i,j,0xff000000|rgb);
        }
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)if(dx!=0||dy!=0)g.drawImage(mask,x+dx,y+dy,null);
    }
    /** Green while plenty is left, orange below 25 %, red below 10 %. */
    // The bar colours are not part of the frozen layout: both renderers use the current scale.
    static int barColor(double left){return StatusImage.barColor(left);}
    private static void right(Graphics2D g,Scheme scheme,String value,int x,int y,int size,int color){
        g.setFont(new Font(scheme.font(),Font.PLAIN,size));
        text(g,scheme,value,x-g.getFontMetrics().stringWidth(value),y,size,color,false);
    }
    private static void text(Graphics2D g,Scheme scheme,String value,int x,int y,int size,int color,boolean bold){
        g.setFont(new Font(scheme.font(),bold?Font.BOLD:Font.PLAIN,size));g.setColor(new Color(color));g.drawString(value,x,y);
    }
}
