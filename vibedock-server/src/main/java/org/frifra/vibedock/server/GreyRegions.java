package org.frifra.vibedock.server;

import java.awt.image.BufferedImage;
import java.util.*;

/**
 * Black-and-white panels: large dark areas that remain in a greyscale picture (a dark window body, a title bar, a footer)
 * are shown light, their light content (text, icons) dark. The areas are flat regions of one grey that cover at least
 * {@value #MIN_SHARE} percent of the picture; content is what lies inside them. Gradients and small dark marks stay as they
 * are, so the picture keeps its features.
 */
final class GreyRegions {
    static final int MIN_SHARE=3,DARK_BELOW=100,LIGHTEST=225;
    private GreyRegions(){}

    static void lighten(BufferedImage image){
        int w=image.getWidth(),h=image.getHeight(),n=w*h;
        int[] g=new int[n];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)g[y*w+x]=image.getRGB(x,y)&255;
        int[] label=new int[n];Arrays.fill(label,-1);
        var dark=new ArrayList<Boolean>();
        int[] queue=new int[n];long minArea=(long)n*MIN_SHARE/100;
        for(int start=0;start<n;start++){
            if(label[start]!=-1)continue;
            int head=0,tail=0;queue[tail++]=start;label[start]=-2;  // -2: visited, not yet large
            while(head<tail){
                int p=queue[head++],x=p%w,y=p/w;
                if(x>0)tail=visit(g,label,queue,tail,p,p-1);
                if(x<w-1)tail=visit(g,label,queue,tail,p,p+1);
                if(y>0)tail=visit(g,label,queue,tail,p,p-w);
                if(y<h-1)tail=visit(g,label,queue,tail,p,p+w);
            }
            if(tail>=minArea){int id=dark.size();dark.add(g[start]<DARK_BELOW);for(int i=0;i<tail;i++)label[queue[i]]=id;}
            else for(int i=0;i<tail;i++)label[queue[i]]=-3;  // detail
        }
        if(!dark.contains(true))return;
        boolean[] flip=new boolean[n];
        for(int p=0;p<n;p++)if(label[p]>=0&&dark.get(label[p]))flip[p]=true;
        // Detail blobs (text, icons, borders) belong to the large region that surrounds them most.
        boolean[] seen=new boolean[n];
        for(int start=0;start<n;start++){
            if(label[start]!=-3||seen[start])continue;
            int head=0,tail=0;queue[tail++]=start;seen[start]=true;
            var votes=new HashMap<Integer,Integer>();
            while(head<tail){
                int p=queue[head++],x=p%w,y=p/w;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                    int xx=x+dx,yy=y+dy;if((dx==0&&dy==0)||xx<0||yy<0||xx>=w||yy>=h)continue;
                    int q=yy*w+xx;
                    if(label[q]==-3){if(!seen[q]){seen[q]=true;queue[tail++]=q;}}
                    else if(label[q]>=0&&(dx==0||dy==0))votes.merge(label[q],1,Integer::sum);
                }
            }
            if(tail>n/4)continue;  // a gradient, not content
            int best=-1,most=0;
            for(var e:votes.entrySet())if(e.getValue()>most){most=e.getValue();best=e.getKey();}
            if(best>=0&&dark.get(best))for(int i=0;i<tail;i++)flip[queue[i]]=true;
        }
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(flip[y*w+x]){
            int v=Math.min(LIGHTEST,255-g[y*w+x]);image.setRGB(x,y,0xff000000|v<<16|v<<8|v);
        }
    }
    private static int visit(int[] g,int[] label,int[] queue,int tail,int from,int to){
        if(label[to]==-1&&g[to]==g[from]){label[to]=-2;queue[tail++]=to;}
        return tail;
    }
}
