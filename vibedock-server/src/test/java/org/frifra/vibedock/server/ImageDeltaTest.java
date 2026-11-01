package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImageDeltaTest {
    static byte[] apply(byte[] base,byte[] patch,int width) {
        byte[] result=base.clone();var in=ByteBuffer.wrap(patch);int end=0;
        while(true){
            int y=Short.toUnsignedInt(in.getShort());if(y==65535)break;
            int n=Short.toUnsignedInt(in.getShort()),x=Short.toUnsignedInt(in.getShort()),w=Short.toUnsignedInt(in.getShort());
            assertTrue(y>=end);end=y+n;
            int[] hashes=new int[n];for(int i=0;i<n;i++)hashes[i]=in.getInt();
            for(int row=0;row<n;row++){
                in.get(result,2*((y+row)*width+x),2*w);
                assertEquals(hashes[row],ImageDelta.rowHash(result,2*(y+row)*width,2*width));
            }
        }
        assertFalse(in.hasRemaining());return result;
    }
    @Test void sparseAndGroupedChangesReconstructExactly(){
        var random=new Random(7);
        for(var display:DisplayType.INVENTORY)for(int attempt=0;attempt<10;attempt++){
            int w=display.width(),h=display.height();byte[] before=new byte[2*w*h];random.nextBytes(before);byte[] after=before.clone();
            for(int i=0;i<40;i++)after[random.nextInt(after.length)]^=1;
            var patch=ImageDelta.encode(before,after,w,h).orElseThrow();
            assertArrayEquals(after,apply(before,patch.bytes(),w));
            assertEquals(ImageDelta.hex(ImageDelta.fnv(patch.bytes(),0,patch.bytes().length)),patch.payload());
            assertTrue(patch.bytes().length<after.length);
        }
    }
    @Test void sharedFirmwareVector(){
        byte[] before=new byte[512],after=before.clone();after[0]=(byte)0xab;after[1]=(byte)0xcd;after[32]=(byte)0xab;after[33]=(byte)0xcd;
        var patch=ImageDelta.encode(before,after,16,16).orElseThrow();
        assertEquals("0000000200000001"+"0776765d0776765d"+"abcdabcdffff",HexFormat.of().formatHex(patch.bytes()));
        assertEquals("3e29e1f9",patch.check());assertEquals("48b8b2a2",patch.payload());
        assertArrayEquals(after,apply(before,patch.bytes(),16));
    }
    @Test void fullAndIncompatibleImagesDoNotCreateDeltas(){
        byte[] base=new byte[512],changed=new byte[512];Arrays.fill(changed,(byte)1);
        assertTrue(ImageDelta.encode(base,changed,16,16).isEmpty());
        assertTrue(ImageDelta.encode(base,new byte[510],16,16).isEmpty());
        assertTrue(ImageDelta.encode(base,base,8,32).isEmpty());
    }
    @Test void historySupportsRetryButNotOtherDevicesSizesOrExpiredBases(){
        var cache=new ImageDelta();byte[] image=new byte[512];
        assertTrue(cache.exchange("a",(String)null,"a0",16,16,image,0).isEmpty());
        image[0]=1;assertTrue(cache.exchange("a","a0","a1",16,16,image,1).isPresent());
        image[0]=2;assertTrue(cache.exchange("a","a0","a2",16,16,image,2).isPresent()); // retry after lost answer
        assertTrue(cache.exchange("b","a0","b0",16,16,image,3).isEmpty());
        image[0]=3;cache.exchange("a","a0","a3",16,16,image,3);
        assertTrue(cache.exchange("a","a0","a4",16,16,image,4).isEmpty()); // only three retained
        assertTrue(cache.exchange("a","a4","a5",32,16,new byte[1024],5).isEmpty());
        assertTrue(cache.exchange("b","b0","b1",16,16,image,ImageDelta.MAX_AGE_MS+4).isEmpty());
        assertTrue(new ImageDelta().exchange("a","a4","a5",16,16,image,1).isEmpty());
    }
    @Test void bothGlobalLimitsEvictOldBases(){
        var cache=new ImageDelta();byte[] small=new byte[512];
        cache.exchange("old",(String)null,"first",16,16,small,0);
        for(int i=0;i<ImageDelta.MAX_DEVICES;i++)cache.exchange("d"+i,(String)null,"tag",16,16,small,1);
        assertTrue(cache.exchange("old","first","new",16,16,small,2).isEmpty());
        var bytes=new ImageDelta();byte[] large=new byte[2*480*480];
        bytes.exchange("old",(String)null,"first",480,480,large,0);
        for(int i=0;i<30;i++)bytes.exchange("large"+i,(String)null,"tag",480,480,large,1);
        assertTrue(bytes.exchange("old","first","new",480,480,large,2).isEmpty());
    }
    /** The display after a transfer cut off at row cut: the new rows above, the old below; hashes of a cut section unknown. */
    @Test void aResumedTransferStartsFromWhatTheDisplayShows(){
        var random=new Random(11);
        for(var display:DisplayType.INVENTORY)for(int attempt=0;attempt<10;attempt++){
            int w=display.width(),h=display.height(),cut=1+random.nextInt(h-1);
            byte[] old=new byte[2*w*h];random.nextBytes(old);
            byte[] aborted=old.clone();for(int i=0;i<200;i++)aborted[random.nextInt(aborted.length)]^=1;
            byte[] shown=old.clone();System.arraycopy(aborted,0,shown,0,2*w*cut);
            byte[] next=attempt%2==0?aborted:aborted.clone();if(attempt%2==1)for(int i=0;i<40;i++)next[random.nextInt(next.length)]^=2;
            var patch=ImageDelta.encode(aborted,cut,old,next,w,h).orElseThrow();
            var result=apply(shown,patch.bytes(),w);
            assertArrayEquals(next,result);
            // Every row below the cut that differed between the two images is sent again: its hash may be unknown.
            var sent=sentRows(patch.bytes());
            for(int y=cut;y<h;y++)if(!Arrays.equals(old,2*y*w,2*(y+1)*w,aborted,2*y*w,2*(y+1)*w))assertTrue(sent.contains(y),"row "+y);
            // Without the old image every row below the cut is sent whole.
            var blind=ImageDelta.encode(aborted,cut,null,next,w,h);
            blind.ifPresent(p->assertArrayEquals(next,apply(shown,p.bytes(),w)));
        }
    }
    @Test void theCacheResumesFromBothImagesOrSendsTheWholeImage(){
        var cache=new ImageDelta();byte[] old=new byte[512],aborted=old.clone();aborted[0]=1;aborted[300]=1;
        cache.exchange("a",(String)null,"o",16,16,old,0);cache.exchange("a","o","t",16,16,aborted,1);
        assertTrue(cache.exchange("a",new ImageDelta.Basis("t",4,"o"),"t",16,16,aborted,2).isPresent());
        assertTrue(cache.exchange("a",new ImageDelta.Basis("t",4,null),"t",16,16,aborted,3).isPresent());
        assertTrue(cache.exchange("a",new ImageDelta.Basis("gone",4,"o"),"t",16,16,aborted,4).isEmpty());
        // Cut off before the first row was drawn: the old image with the rows of the patch unknown.
        var none=cache.exchange("a",new ImageDelta.Basis("t",0,"o"),"t",16,16,aborted,5).orElseThrow();
        assertArrayEquals(aborted,apply(old,none.bytes(),16));
        assertNull(DeviceApiController.resumeBasis("\"t\";rows=4;base=\"o\"",16));
        assertEquals(new ImageDelta.Basis("\"ab\"",4,"\"cd\""),DeviceApiController.resumeBasis("\"ab\";rows=4;base=\"cd\"",16));
        assertEquals(new ImageDelta.Basis("\"ab\"",4,null),DeviceApiController.resumeBasis("\"ab\";rows=4;base=",16));
        for(var bad:new String[]{null,"","\"ab\";rows=x;base=","\"ab\";rows=16;base=","\"ab\";rows=4","ab;rows=4;base="})
            assertNull(DeviceApiController.resumeBasis(bad,16),bad);
    }
    private static Set<Integer> sentRows(byte[] patch){
        var in=ByteBuffer.wrap(patch);var rows=new HashSet<Integer>();
        while(true){
            int y=Short.toUnsignedInt(in.getShort());if(y==65535)return rows;
            int n=Short.toUnsignedInt(in.getShort());in.getShort();int w=Short.toUnsignedInt(in.getShort());
            for(int i=0;i<n;i++)rows.add(y+i);
            in.position(in.position()+4*n+2*n*w);
        }
    }

    @Test void transferLogStatesTheSavingAgainstTheRawAndThePaletteImage() {
        var next=new PullSchedule.Next(59,false);
        // A palette delta: 168 bytes are 99.9 % below raw RGB565 but only 98.8 % below the full palette image.
        assertEquals("Image transfer d1: delta palette-4, full=115200 bytes, payload=168 bytes, saved=115032 bytes (99.9%), "
                +"palette full=14410 bytes, saved against it=14242 bytes (98.8%), next=59 s (current)",
                DeviceApiController.transferLine("d1","delta palette-4",115200,14410,168,next));
        // Without a palette, and for a full palette image, the line keeps its form: there is no second baseline.
        assertEquals("Image transfer d2: delta, full=115200 bytes, payload=638 bytes, saved=114562 bytes (99.4%), next=59 s (current)",
                DeviceApiController.transferLine("d2","delta",115200,0,638,next));
        assertEquals("Image transfer d3: full palette-4, full=115200 bytes, payload=14410 bytes, saved=100790 bytes (87.5%), next=59 s (current)",
                DeviceApiController.transferLine("d3","full palette-4",115200,14410,14410,next));
    }
}
