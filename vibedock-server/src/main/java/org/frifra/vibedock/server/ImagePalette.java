package org.frifra.vibedock.server;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** One shared palette, packed per row; the decoded RGB565 frame remains the delta basis. */
final class ImagePalette {
    /** Pixel-identity cache: metrics and encoded bodies are recomputed only for a new frame/policy (or eviction). */
    @org.springframework.stereotype.Component
    static final class Cache {
        static final int MAX_ENTRIES=64,MAX_BYTES=8*1024*1024;
        private record Key(String pixels,int width,int height,int colors,boolean adaptive,int threshold,int preferred,List<Rectangle> accents,List<Rectangle> bars,List<Integer> primary) {}
        private final LinkedHashMap<Key,Selection> entries=new LinkedHashMap<>(16,.75f,true);
        private int retained;private long computations;
        synchronized Selection select(byte[] source,int width,int height,int colors,List<Rectangle> accents,List<Rectangle> bars,boolean adaptive,int threshold){
            return select(source,width,height,colors,accents,bars,adaptive,threshold,colors);
        }
        synchronized Selection select(byte[] source,int width,int height,int colors,List<Rectangle> accents,List<Rectangle> bars,boolean adaptive,int threshold,int preferred){
            return select(source,width,height,colors,accents,bars,adaptive,threshold,preferred,List.of());
        }
        synchronized Selection select(byte[] source,int width,int height,int colors,List<Rectangle> accents,List<Rectangle> bars,boolean adaptive,int threshold,int preferred,List<Integer> primary){

            String fingerprint;
            try{fingerprint=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source));}
            catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            var key=new Key(fingerprint,width,height,colors,adaptive,threshold,preferred,accents.stream().map(Rectangle::new).toList(),bars.stream().map(Rectangle::new).toList(),List.copyOf(primary));
            var hit=entries.get(key);if(hit!=null)return hit;
            var result=ImagePalette.select(source,width,height,colors,accents,bars,adaptive,threshold,preferred,primary);computations++;
            int bytes=weight(result);
            if(bytes<=MAX_BYTES){
                entries.put(key,result);retained+=bytes;
                while(entries.size()>MAX_ENTRIES||retained>MAX_BYTES){var it=entries.entrySet().iterator();retained-=weight(it.next().getValue());it.remove();}
            }
            return result;
        }
        private static int weight(Selection result){return result.image()==null?0:result.image().pixels().length+result.image().body().length;}
        synchronized long computations(){return computations;}
        synchronized int size(){return entries.size();}
        synchronized int bytes(){return retained;}
    }
    record Encoded(byte[] pixels,byte[] body,int bits,int colorsUsed,boolean protectedColorsPreserved) {}
    record Selection(Encoded image,int colors,double changedPercent) {}
    static Selection select(byte[] source,int width,int height,int colors,List<Rectangle> accents,List<Rectangle> bars,boolean adaptive,int threshold){
        return select(source,width,height,colors,accents,bars,adaptive,threshold,colors);
    }
    static Selection select(byte[] source,int width,int height,int colors,List<Rectangle> accents,List<Rectangle> bars,boolean adaptive,int threshold,int preferred){
        return select(source,width,height,colors,accents,bars,adaptive,threshold,preferred,List.of());
    }
    static Selection select(byte[] source,int width,int height,int colors,List<Rectangle> accents,List<Rectangle> bars,boolean adaptive,int threshold,int preferred,List<Integer> primary){
        if(colors==0)return lossless(source,width,height,accents,bars);
        if(adaptive&&preferred==ImageQuality.higher(colors))return candidate(source,width,height,preferred,accents,bars,primary);
        if(adaptive&&preferred==ImageQuality.lower(colors)){
            var low=candidate(source,width,height,preferred,accents,bars,primary);if(low.image().protectedColorsPreserved()&&low.changedPercent()<=threshold)return low;
        }
        var normal=candidate(source,width,height,colors,accents,bars,primary);
        return !adaptive||(normal.image().protectedColorsPreserved()&&normal.changedPercent()<=threshold)?normal:candidate(source,width,height,ImageQuality.higher(colors),accents,bars,primary);
    }
    private static Selection candidate(byte[] source,int width,int height,int colors,List<Rectangle> accents,List<Rectangle> bars,List<Integer> primary){
        if(colors==0)return lossless(source,width,height,accents,bars);
        var encoded=encode(source,width,height,colors,accents,bars,primary);
        return new Selection(encoded,colors,changedPixels(source,encoded.pixels())*100.0/(width*height));
    }
    private static Selection lossless(byte[] source,int width,int height,List<Rectangle> accents,List<Rectangle> bars){
        var colors=new HashSet<Integer>();for(int i=0;i<source.length;i+=2){colors.add(code(source,i));if(colors.size()>256)return new Selection(null,0,0);}
        return new Selection(encode(source,width,height,256,accents,bars),0,0);
    }
    static int changedPixels(byte[] original,byte[] reduced){
        int changed=0;for(int i=0;i<original.length;i+=2)if(original[i]!=reduced[i]||original[i+1]!=reduced[i+1])changed++;
        return changed;
    }
    record Delta(ImageDelta.Patch patch,int bits) {}
    /** Recode only the already-quantized delta pixels: never approximate a color a second time. */
    static Delta delta(ImageDelta.Patch raw,int fullColors){
        byte[] bytes=raw.bytes();var entries=new TreeSet<Integer>();
        for(int at=0;at<bytes.length;){
            int y=u16(bytes,at);if(y==65535)break;
            int n=u16(bytes,at+2),w=u16(bytes,at+6);at+=8+4*n;
            for(int end=at+2*n*w;at<end;at+=2)entries.add(code(bytes,at));
        }
        if(fullColors!=0&&entries.size()>fullColors)throw new IllegalArgumentException("Delta does not share the image palette");
        var palette=new ArrayList<>(entries);if(palette.isEmpty())palette.add(0);
        int bits=Math.max(1,32-Integer.numberOfLeadingZeros(palette.size()-1));
        var lookup=new HashMap<Integer,Integer>();for(int i=0;i<palette.size();i++)lookup.put(palette.get(i),i);
        var out=new ByteArrayOutputStream();out.write(palette.size()>>8);out.write(palette.size());
        for(int rgb:palette){out.write(rgb>>8);out.write(rgb);}
        for(int at=0;at<bytes.length;){
            int y=u16(bytes,at);if(y==65535){out.write(255);out.write(255);break;}
            int n=u16(bytes,at+2),w=u16(bytes,at+6),head=8+4*n;out.write(bytes,at,head);at+=head;
            for(int row=0;row<n;row++){
                byte[] packed=new byte[(w*bits+7)/8];
                for(int x=0;x<w;x++){int index=lookup.get(code(bytes,at));at+=2;
                    for(int bit=0;bit<bits;bit++)if((index&(1<<(bits-1-bit)))!=0){int pos=x*bits+bit;packed[pos/8]|=(byte)(1<<(7-pos%8));}}
                out.writeBytes(packed);
            }
        }
        byte[] body=out.toByteArray();return new Delta(new ImageDelta.Patch(body,raw.check(),ImageDelta.hex(ImageDelta.fnv(body,0,body.length))),bits);
    }
    private static int u16(byte[] bytes,int at){return (bytes[at]&255)<<8|(bytes[at+1]&255);}
    private record Point(int rgb,int count) {}
    private static int r(int p){return (p>>11)*255/31;}
    private static int g(int p){return ((p>>5)&63)*255/63;}
    private static int b(int p){return (p&31)*255/31;}
    private static int code(byte[] p,int offset){return (p[offset]&255)<<8|(p[offset+1]&255);}
    private static long distance(int a,int c){long dr=r(a)-r(c),dg=g(a)-g(c),db=b(a)-b(c);return dr*dr+2*dg*dg+db*db;}
    private static int bucket(int p){float[] hsv=java.awt.Color.RGBtoHSB(r(p),g(p),b(p),null);return hsv[1]>.22?Math.min(11,(int)(hsv[0]*12)):hsv[2]>.8?12:hsv[2]>.25?13:14;}
    private static double score(int p,int count){int max=Math.max(r(p),Math.max(g(p),b(p))),min=Math.min(r(p),Math.min(g(p),b(p)));return count*(.3+(max==0?0:(max-min)/(double)max))*(.3+max/255.0);}
    static Encoded encode(byte[] source,int width,int height,int colors,List<Rectangle> accents){
        return encode(source,width,height,colors,accents,List.of());
    }
    static Encoded encode(byte[] source,int width,int height,int colors,List<Rectangle> accents,List<Rectangle> bars){
        return encode(source,width,height,colors,accents,bars,List.of());
    }
    static Encoded encode(byte[] source,int width,int height,int colors,List<Rectangle> accents,List<Rectangle> bars,List<Integer> primary){
        if(source.length!=width*height*2||!Set.of(16,32,64,128,256).contains(colors))throw new IllegalArgumentException("Invalid palette frame");
        var histogram=new TreeMap<Integer,Integer>();for(int i=0;i<source.length;i+=2)histogram.merge(code(source,i),1,Integer::sum);
        var palette=new LinkedHashSet<Integer>();boolean protectedPreserved=true;
        if(histogram.size()<=colors)palette.addAll(histogram.keySet());
        else{
            primary.stream().filter(histogram::containsKey).forEach(palette::add);
            // Lock the dominant fill/track paints before assigning similar gradient shades to shared slots.
            for(var rect:new LinkedHashSet<>(bars)){
                var counts=region(source,width,height,rect);
                counts.entrySet().stream().sorted(Map.Entry.<Integer,Integer>comparingByValue().reversed().thenComparingInt(Map.Entry::getKey)).limit(3)
                        .forEach(e->palette.add(e.getKey()));
            }

            // Give the small weather and mood artworks their own representative hue slots. They share the global budget.
            for(var rect:primary.isEmpty()?new LinkedHashSet<>(accents):Set.<Rectangle>of()){
                var counts=region(source,width,height,rect);
                var groups=new HashMap<Integer,Point>();counts.forEach((rgb,count)->{var old=groups.get(bucket(rgb));if(old==null||score(rgb,count)>score(old.rgb(),old.count()))groups.put(bucket(rgb),new Point(rgb,count));});
                int limit=rect.height<=32?4:colors==16?6:8;
                groups.values().stream().sorted(Comparator.<Point>comparingDouble(p->score(p.rgb(),p.count())).reversed().thenComparingInt(Point::rgb)).limit(limit)
                        .forEach(p->palette.add(p.rgb()));
            }
            protectedPreserved=palette.size()<=colors;
            if(!protectedPreserved){var keep=palette.stream().limit(colors).toList();palette.clear();palette.addAll(keep);}
            var boxes=new ArrayList<List<Point>>();boxes.add(histogram.entrySet().stream().map(e->new Point(e.getKey(),e.getValue())).toList());
            while(boxes.size()<colors-palette.size()){
                int selected=-1;long best=-1;
                for(int i=0;i<boxes.size();i++)if(boxes.get(i).size()>1){long rank=(long)range(boxes.get(i))*boxes.get(i).stream().mapToInt(Point::count).sum();if(rank>best){best=rank;selected=i;}}
                if(selected<0)break;var box=new ArrayList<>(boxes.remove(selected));int channel=channel(box);
                box.sort(Comparator.<Point>comparingInt(p->component(p.rgb(),channel)).thenComparingInt(Point::rgb));
                long total=box.stream().mapToLong(Point::count).sum(),sum=0;int at=0;
                while(at<box.size()-1&&sum<total/2){sum+=box.get(at++).count();}at=Math.max(1,at);
                boxes.add(new ArrayList<>(box.subList(0,at)));boxes.add(new ArrayList<>(box.subList(at,box.size())));
            }
            for(var box:boxes){long total=0,red=0,green=0,blue=0;for(var p:box){total+=p.count();red+=(long)r(p.rgb())*p.count();green+=(long)g(p.rgb())*p.count();blue+=(long)b(p.rgb())*p.count();}
                int mean=((int)(red/total)*31/255<<11)|((int)(green/total)*63/255<<5)|((int)(blue/total)*31/255);
                if(palette.size()<colors)palette.add(box.stream().min(Comparator.<Point>comparingLong(p->distance(p.rgb(),mean)).thenComparingInt(Point::rgb)).orElseThrow().rgb());}
        }
        int[] entries=palette.stream().mapToInt(Integer::intValue).toArray(),indices=new int[65536];
        for(int rgb:histogram.keySet()){int nearest=0;long best=Long.MAX_VALUE;for(int i=0;i<entries.length;i++){long d=distance(rgb,entries[i]);if(d<best){best=d;nearest=i;}}indices[rgb]=nearest;}
        int bits=Math.max(1,32-Integer.numberOfLeadingZeros(entries.length-1));var out=new ByteArrayOutputStream();
        out.write(entries.length>>8);out.write(entries.length);
        for(int p:entries){out.write(p>>8);out.write(p);}
        byte[] decoded=new byte[source.length];int rowBytes=(width*bits+7)/8;
        for(int y=0;y<height;y++){byte[] row=new byte[rowBytes];for(int x=0;x<width;x++){
            int at=2*(y*width+x),index=indices[code(source,at)],rgb=entries[index];decoded[at]=(byte)(rgb>>8);decoded[at+1]=(byte)rgb;
            for(int bit=0;bit<bits;bit++)if((index&(1<<(bits-1-bit)))!=0){int pos=x*bits+bit;row[pos/8]|=(byte)(1<<(7-pos%8));}
        }out.writeBytes(row);}
        return new Encoded(decoded,out.toByteArray(),bits,entries.length,protectedPreserved);
    }
    private static int component(int p,int c){return c==0?r(p):c==1?g(p):b(p);}
    private static Map<Integer,Integer> region(byte[] source,int width,int height,Rectangle rect){
        var counts=new TreeMap<Integer,Integer>();
        for(int y=Math.max(0,rect.y);y<Math.min(height,rect.y+rect.height);y++)for(int x=Math.max(0,rect.x);x<Math.min(width,rect.x+rect.width);x++)
            counts.merge(code(source,2*(y*width+x)),1,Integer::sum);
        return counts;
    }
    private static int channel(List<Point> points){int channel=0,best=-1;for(int c=0;c<3;c++){int min=255,max=0;for(var p:points){min=Math.min(min,component(p.rgb(),c));max=Math.max(max,component(p.rgb(),c));}if(max-min>best){best=max-min;channel=c;}}return channel;}
    private static int range(List<Point> points){int c=channel(points),min=255,max=0;for(var p:points){min=Math.min(min,component(p.rgb(),c));max=Math.max(max,component(p.rgb(),c));}return max-min;}
}
