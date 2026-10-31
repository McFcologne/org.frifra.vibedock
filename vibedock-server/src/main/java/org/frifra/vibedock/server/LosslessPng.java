package org.frifra.vibedock.server;
import java.awt.image.*;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
/** Exact indexed PNG when useful; never quantizes or enlarges the original file. */
final class LosslessPng {
    static byte[] encode(BufferedImage image) throws IOException {
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);byte[] original=out.toByteArray();
        var colors=new LinkedHashMap<Integer,Integer>();int w=image.getWidth(),h=image.getHeight();
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=image.getRGB(x,y);if(!colors.containsKey(c)){if(colors.size()==256)return original;colors.put(c,colors.size());}}
        int bits=colors.size()<=2?1:colors.size()<=4?2:colors.size()<=16?4:8,n=colors.size();byte[] r=new byte[n],g=new byte[n],b=new byte[n],a=new byte[n];
        colors.forEach((c,i)->{r[i]=(byte)(c>>16);g[i]=(byte)(c>>8);b[i]=(byte)(int)c;a[i]=(byte)(c>>>24);});
        var indexed=new BufferedImage(w,h,bits<=4?BufferedImage.TYPE_BYTE_BINARY:BufferedImage.TYPE_BYTE_INDEXED,new IndexColorModel(bits,n,r,g,b,a));
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)indexed.getRaster().setSample(x,y,0,colors.get(image.getRGB(x,y)));
        out.reset();ImageIO.write(indexed,"png",out);byte[] packed=out.toByteArray();return packed.length<original.length?packed:original;
    }
}
