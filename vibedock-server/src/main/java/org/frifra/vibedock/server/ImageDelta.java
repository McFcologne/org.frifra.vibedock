package org.frifra.vibedock.server;

import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** Optional RGB565 delta v1. A bounded, expiring cache; losing it only costs one full image. */
@Component
class ImageDelta {
    static final int MAX_BYTES=12*1024*1024, MAX_DEVICES=128, HISTORY=3;
    static final long MAX_AGE_MS=10*60*1000;
    record Frame(String tag,int width,int height,byte[] pixels,long at) {}
    record Patch(byte[] bytes,String check,String payload) {}
    /**
     * What the display shows: rows 0 to rows-1 of the image top, the others of bottom (null: unknown). A complete image
     * is (tag, height, null); after an aborted transfer it is the image that was cut off and the one under it.
     */
    record Basis(String top,int rows,String bottom) {
        static Basis complete(String tag,int height){return tag==null?null:new Basis(tag,height,null);}
    }
    private final LinkedHashMap<String,Deque<Frame>> cache=new LinkedHashMap<>(16,.75f,true);
    private int retained;

    synchronized Optional<Patch> exchange(String device,String base,String tag,int width,int height,byte[] pixels,long now) {
        return exchange(device,Basis.complete(base,height),tag,width,height,pixels,now);
    }
    synchronized Optional<Patch> exchange(String device,Basis basis,String tag,int width,int height,byte[] pixels,long now) {
        if(width<16||height<16||width>480||height>480||pixels.length!=2*width*height)return Optional.empty();
        // Evict inactive devices even when the device count stays small.
        var entries=cache.entrySet().iterator();
        while(entries.hasNext()){
            var frames=entries.next().getValue();
            while(!frames.isEmpty()&&now-frames.peekFirst().at()>MAX_AGE_MS)retained-=frames.removeFirst().pixels().length;
            if(frames.isEmpty())entries.remove();
        }
        var frames=cache.computeIfAbsent(device,k->new ArrayDeque<>());
        Optional<Patch> patch=Optional.empty();
        if(basis!=null&&basis.rows()>=0&&basis.rows()<=height){
            var top=frame(frames,basis.top(),width,height);
            var bottom=basis.rows()<height?frame(frames,basis.bottom(),width,height):top;
            if(top.isPresent())patch=encode(top.get(),basis.rows(),bottom.orElse(null),pixels,width,height);
        }
        frames.removeIf(f->{if(f.tag().equals(tag)){retained-=f.pixels().length;return true;}return false;});
        frames.addLast(new Frame(tag,width,height,pixels.clone(),now));retained+=pixels.length;
        while(frames.size()>HISTORY)retained-=frames.removeFirst().pixels().length;
        while(retained>MAX_BYTES||cache.size()>MAX_DEVICES){
            var oldest=cache.entrySet().iterator();
            for(var frame:oldest.next().getValue())retained-=frame.pixels().length;
            oldest.remove();
        }
        return patch;
    }
    private static Optional<byte[]> frame(Deque<Frame> frames,String tag,int width,int height){
        return tag==null?Optional.empty():frames.stream().filter(f->f.tag().equals(tag)&&f.width()==width&&f.height()==height).map(Frame::pixels).findFirst();
    }
    static Optional<Patch> encode(byte[] before,byte[] after,int width,int height) {
        return encode(before,height,before,after,width,height);
    }
    /**
     * The patch from what the display shows (rows below split from top, the others from bottom) to after. Rows from
     * split on are sent whole when bottom is unknown, and always where bottom and top differ: a transfer cut off in
     * such a row leaves its row hash unknown on the display.
     */
    static Optional<Patch> encode(byte[] top,int split,byte[] bottom,byte[] after,int width,int height) {
        if(width<16||height<16||width>480||height>480||top.length!=2*width*height||after.length!=top.length
                ||split<0||split>height||(bottom!=null&&bottom.length!=top.length))return Optional.empty();
        var out=new ByteArrayOutputStream();
        int[] left=new int[height],right=new int[height];Arrays.fill(left,width);
        for(int y=0;y<height;y++){
            byte[] before=y<split?top:bottom;
            if(before==null){left[y]=0;right[y]=width;continue;}
            for(int x=0;x<width;x++){
                int p=2*(y*width+x);
                if(before[p]!=after[p]||before[p+1]!=after[p+1]){left[y]=Math.min(left[y],x);right[y]=x+1;}
            }
            if(y>=split&&left[y]==width&&!Arrays.equals(top,2*y*width,2*(y+1)*width,bottom,2*y*width,2*(y+1)*width)){left[y]=0;right[y]=1;}
        }
        for(int y=0;y<height;){
            if(left[y]==width){y++;continue;}
            int end=y+1;
            while(end<height&&left[end]==left[y]&&right[end]==right[y])end++;
            int n=end-y,w=right[y]-left[y];
            // Stop before allocating an answer larger than the uncompressed image.
            if(out.size()+8+4*n+2*n*w+2>=after.length)return Optional.empty();
            u16(out,y);u16(out,n);u16(out,left[y]);u16(out,w);
            for(int row=y;row<end;row++)u32(out,rowHash(after,2*row*width,2*width));
            for(int row=y;row<end;row++)out.write(after,2*(row*width+left[y]),2*w);
            y=end;
        }
        u16(out,65535);byte[] bytes=out.toByteArray();
        int state=0x811c9dc5;
        for(int row=0;row<height;row++){
            int hash=rowHash(after,2*row*width,2*width);
            for(int shift=24;shift>=0;shift-=8)state=step(state,hash>>>shift);
        }
        return Optional.of(new Patch(bytes,hex(state),hex(fnv(bytes,0,bytes.length))));
    }
    static int step(int hash,int value){return (hash^(value&255))*0x01000193;}
    static int fnv(byte[] data,int offset,int length){int h=0x811c9dc5;for(int i=offset;i<offset+length;i++)h=step(h,data[i]);return h;}
    static int rowHash(byte[] data,int offset,int length){int h=fnv(data,offset,length);return h==0?1:h;}
    static String hex(int value){return String.format(Locale.ROOT,"%08x",value);}
    private static void u16(ByteArrayOutputStream out,int n){out.write(n>>>8);out.write(n);}
    private static void u32(ByteArrayOutputStream out,int n){u16(out,n>>>16);u16(out,n);}
}
