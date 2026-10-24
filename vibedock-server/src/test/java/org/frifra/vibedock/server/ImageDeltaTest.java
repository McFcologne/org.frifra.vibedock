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
        assertTrue(cache.exchange("a",null,"a0",16,16,image,0).isEmpty());
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
        cache.exchange("old",null,"first",16,16,small,0);
        for(int i=0;i<ImageDelta.MAX_DEVICES;i++)cache.exchange("d"+i,null,"tag",16,16,small,1);
        assertTrue(cache.exchange("old","first","new",16,16,small,2).isEmpty());
        var bytes=new ImageDelta();byte[] large=new byte[2*480*480];
        bytes.exchange("old",null,"first",480,480,large,0);
        for(int i=0;i<30;i++)bytes.exchange("large"+i,null,"tag",480,480,large,1);
        assertTrue(bytes.exchange("old","first","new",480,480,large,2).isEmpty());
    }
}
