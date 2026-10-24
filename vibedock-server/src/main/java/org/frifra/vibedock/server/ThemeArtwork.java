package org.frifra.vibedock.server;

import java.awt.*;
import java.awt.image.BufferedImage;

/** Drawing details of the new themes. All coordinates belong to a band or a bar, so every layout keeps working. */
final class ThemeArtwork {
    private ThemeArtwork() {}
    static String title(Scheme scheme,String title){return switch(scheme.style){case MISSION,DEPARTURE,HIFI,QUEST,INDUSTRIAL->title.toUpperCase(java.util.Locale.ROOT);default->title;};}
    static void band(Graphics2D g,Scheme s,int x,int y,int width,int height){
        if(!s.illustrated())return;
        var ink=(Graphics2D)g.create();
        try{
            ink.clipRect(x,y,width,height);ink.setColor(new Color(s.track));
            switch(s.style){
                case MISSION -> {
                    for(int line=y+6;line<y+height;line+=12){ink.setColor(new Color(255,255,255,5));ink.drawLine(x,line,x+width,line);}
                    ink.setColor(new Color(s.title));
                    for(int left:new int[]{x+5,x+width-21}){ink.drawLine(left,y+5,left+16,y+5);ink.drawLine(left==x+5?left:left+16,y+5,left==x+5?left:left+16,y+17);}
                }
                case DEPARTURE,INK,NORDIC -> {ink.setColor(new Color(s.track));ink.drawLine(x+10,y+height-2,x+width-10,y+height-2);}
                case HIFI -> {
                    for(int line=y;line<y+height;line+=3){ink.setColor(new Color(255,255,255,5));ink.drawLine(x,line,x+width,line);}
                }
                case QUEST -> {ink.setColor(new Color(0x20213a));ink.fillRect(x+3,y+3,width-6,height-6);ink.setColor(new Color(0x60668d));ink.drawRect(x+3,y+3,width-7,height-7);}
                case BAUHAUS -> {ink.setColor(new Color(s.title));ink.fillRect(x,y+3,4,height-6);ink.setColor(new Color(0xc6a12f));ink.fillOval(x+width-17,y+9,7,7);}
                case INDUSTRIAL -> {
                    ink.setColor(new Color(0x626965));ink.drawRect(x+3,y+3,width-7,height-7);
                    for(int left:new int[]{x+5,x+width-6}){ink.setColor(new Color(0x92978b));ink.fillOval(left-1,y+5,2,2);}
                }
                case PORCELAIN -> {ink.setColor(new Color(0xa7bbd2));ink.drawRoundRect(x+3,y+3,width-7,height-7,10,10);}
                default -> { }
            }
        }finally{ink.dispose();}
    }
    static void bar(Graphics2D g,Scheme s,int x,int y,int width,int height,double left){
        int fill=left<0?0:(int)Math.round(width*left/100);
        g.setColor(new Color(s.track));g.fillRect(x,y,width,height);
        int colour=switch(s.style){case MISSION->0x6ecab8;case DEPARTURE->s.title;case NORDIC->0x54877d;case HIFI->0xa7ca6b;case SOLARIZED->0x859900;case QUEST->0x73be91;case BAUHAUS->0x2f6088;case INK->s.text;case INDUSTRIAL->0xeca24a;case PORCELAIN->0x416ba4;default->s.title;};
        // Numeric quota values remain unchanged. Low remaining quota gets the existing warning colour where coloured.
        if(left>=0&&left<20&&s.style!=Scheme.Style.INK)colour=StatusImage.barColor(left);
        g.setColor(new Color(colour));
        switch(s.style){
            case MISSION,HIFI,QUEST -> {
                int step=height>6?5:4;
                for(int b=0;b<width;b+=step)if(b<fill){
                    if(s.style==Scheme.Style.HIFI&&left>=20)g.setColor(new Color(b>width*.9?0xd47c5c:b>width*.75?0xd1ba68:colour));
                    g.fillRect(x+b,y,Math.min(step-1,Math.min(fill-b,width-b)),height);
                }
                if(s.style==Scheme.Style.QUEST){g.setColor(new Color(0x8185ab));g.drawRect(x-1,y-1,width+1,height+1);}
            }
            case DEPARTURE -> {for(int row=1;row<height;row+=3)for(int col=1;col<fill;col+=3)g.fillRect(x+col,y+row,Math.min(2,fill-col),Math.min(2,height-row));}
            case INK -> {
                var hatch=(Graphics2D)g.create();try{hatch.clipRect(x,y,fill,height);for(int d=-height;d<fill;d+=4)hatch.drawLine(x+d,y+height,x+d+height,y);}finally{hatch.dispose();}
            }
            case NORDIC -> {
                g.setColor(new Color(s.track));g.fillRoundRect(x,y,width,height,height,height);
                if(fill>0){g.setColor(new Color(colour));g.fillRoundRect(x,y,fill,height,height,height);}
            }
            default -> {if(fill>0)g.fillRect(x,y,fill,height);}
        }
        if(s.style==Scheme.Style.INDUSTRIAL){g.setColor(new Color(s.muted));for(int n=0;n<=10;n++){int at=x+n*(width-1)/10;g.drawLine(at,y-2,at,y-(n%5==0?4:3));}}
    }
    static BufferedImage icon(BufferedImage source,Scheme scheme){
        if(source==null||scheme.style!=Scheme.Style.INK&&scheme.style!=Scheme.Style.QUEST)return source;
        var image=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_ARGB);
        int block=scheme.style==Scheme.Style.QUEST?Math.max(2,source.getWidth()/12):1;
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
            int rgb=source.getRGB(x/block*block,y/block*block);
            if(scheme.style==Scheme.Style.INK){int luma=(299*(rgb>>16&255)+587*(rgb>>8&255)+114*(rgb&255))/1000;rgb=(rgb&0xff000000)|(luma>185?0xf5f4ee:0x232620);}
            image.setRGB(x,y,rgb);
        }
        if(scheme.style==Scheme.Style.INK){
            var outlined=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_ARGB);var g=outlined.createGraphics();
            try{StatusImage.outline(g,image,0,0,scheme.text);g.drawImage(image,0,0,null);}finally{g.dispose();}
            return outlined;
        }
        return image;
    }
}
