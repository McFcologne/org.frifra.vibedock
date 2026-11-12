package org.frifra.vibedock.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

/** The Doom firmware built on download: UF2 merge, finding the WAD in what was fetched, the whole chain with a stand-in converter. */
class DoomFirmwareTest {
    private static final byte[] WAD=wad();
    private static byte[] wad(){var d=new byte[5000];d[0]='I';d[1]='W';d[2]='A';d[3]='D';for(int i=4;i<d.length;i++)d[i]=(byte)(i*7);return d;}
    private static String sha1(byte[] d)throws Exception{var s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-1").digest(d))s.append(String.format("%02x",b));return s.toString();}
    private static byte[] zip(String name,byte[] data)throws IOException{
        var out=new ByteArrayOutputStream();try(var z=new ZipOutputStream(out)){z.putNextEntry(new ZipEntry(name));z.write(data);z.closeEntry();}return out.toByteArray();
    }
    private DoomFirmware service(String url,String whdGen,Path data)throws Exception{return new DoomFirmware(url,sha1(WAD),whdGen,data.toString());}

    private static byte[] block(int flags,int addr,int no,int total,long family){
        var b=ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x0A324655).putInt(0x9E5D5157).putInt(flags).putInt(addr).putInt(256).putInt(no).putInt(total).putInt((int)family);
        b.putInt(508,0x0AB16F30);return b.array();
    }

    @Test void mergeKeepsTheLeadingBlockAndNumbersTheDataFamily()throws Exception{
        var code=new ByteArrayOutputStream();
        code.write(block(0xa000,0x10ffff00,0,2,0xe48bff57L));
        for(int i=0;i<3;i++)code.write(block(0x2000,0x10000000+i*256,i,3,0xe48bff59L));
        var whx=new byte[600];whx[0]='I';whx[1]='W';whx[2]='H';whx[3]='X';
        var merged=DoomFirmware.merge(code.toByteArray(),whx);
        assertEquals((4+3)*512,merged.length);
        assertArrayEquals(java.util.Arrays.copyOfRange(code.toByteArray(),0,512),java.util.Arrays.copyOfRange(merged,0,512));
        var b=ByteBuffer.wrap(merged).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=1;i<7;i++){
            assertEquals(i-1,b.getInt(i*512+20));assertEquals(6,b.getInt(i*512+24));
            assertEquals(0xe48bff59,b.getInt(i*512+28));assertEquals(0x0AB16F30,b.getInt(i*512+508));
        }
        assertEquals(DoomFirmware.WAD_ADDRESS,b.getInt(4*512+12));assertEquals(DoomFirmware.WAD_ADDRESS+512,b.getInt(6*512+12));
        assertEquals('I',merged[4*512+32]);assertEquals(0,merged[6*512+32+100]);
    }
    @Test void theProgramInTheServerIsAUf2()throws Exception{
        try(var in=DoomFirmware.class.getResourceAsStream(DoomFirmware.CODE)){
            var code=in.readAllBytes();
            var merged=DoomFirmware.merge(code,new byte[256*10]);
            assertEquals(code.length+10*512,merged.length);
        }
    }
    @Test void aNonUf2IsRefused(){assertThrows(IOException.class,()->DoomFirmware.merge(new byte[1024],new byte[10]));}

    @Test void findsTheWadInTheFileInAZipAndInTheInstaller(@TempDir Path data)throws Exception{
        var s=service("https://example.invalid/x.zip","/nonexistent",data);
        assertArrayEquals(WAD,s.wad(WAD));
        assertArrayEquals(WAD,s.wad(zip("DOOM1.WAD",WAD)));
        // The 1995 installer: a ZIP with two parts (after a stub) that together are a ZIP holding the WAD.
        var inner=zip("doom1.wad",WAD);
        var first=new byte[100+inner.length/2];var second=java.util.Arrays.copyOfRange(inner,inner.length/2,inner.length);
        System.arraycopy(inner,0,first,100,inner.length/2);first[0]='M';first[1]='Z';
        var out=new ByteArrayOutputStream();
        try(var z=new ZipOutputStream(out)){z.putNextEntry(new ZipEntry("DOOMS_19.1"));z.write(first);z.closeEntry();z.putNextEntry(new ZipEntry("DOOMS_19.2"));z.write(second);z.closeEntry();}
        assertArrayEquals(WAD,s.wad(out.toByteArray()));
    }
    @Test void aWadWithAnotherChecksumIsRefused(@TempDir Path data)throws Exception{
        var other=WAD.clone();other[100]^=1;
        var e=assertThrows(IOException.class,()->service("https://example.invalid/x","/nonexistent",data).wad(other));
        assertTrue(e.getMessage().contains("checksum"));
    }

    @Test void buildsTheFirmwareFromADownloadAndCachesIt(@TempDir Path data)throws Exception{
        // A stand-in converter: writes a WHX header and the size of the WAD it was given.
        var tool=data.resolve("whd_gen");
        Files.writeString(tool,"#!/bin/sh\nprintf 'IWHX' > \"$2\"\nhead -c 3000 \"$1\" >> \"$2\"\n");
        tool.toFile().setExecutable(true);
        int[] calls={0};
        var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/doom.zip",x->{calls[0]++;var body=zip("DOOM1.WAD",WAD);x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);x.close();});
        http.start();
        try{
            var s=service("http://127.0.0.1:"+http.getAddress().getPort()+"/doom.zip",tool.toString(),data);
            assertTrue(s.plan().available());assertFalse(s.plan().cached());
            var file=s.firmware();
            var uf2=Files.readAllBytes(file);
            assertEquals(0,uf2.length%512);
            var b=ByteBuffer.wrap(uf2).order(ByteOrder.LITTLE_ENDIAN);
            int last=uf2.length/512-1;
            assertEquals(DoomFirmware.WAD_ADDRESS+((3004+255)/256-1)*256,b.getInt(last*512+12));
            assertEquals('I',uf2[(uf2.length/512-(3004+255)/256)*512+32]);
            assertTrue(s.plan().cached());
            assertEquals(file,s.firmware());assertEquals(1,calls[0]);
        }finally{http.stop(0);}
    }
    @Test void anUnreachableSourceIsExplained(@TempDir Path data)throws Exception{
        var tool=data.resolve("whd_gen");Files.writeString(tool,"#!/bin/sh\nexit 1\n");tool.toFile().setExecutable(true);
        var e=assertThrows(IOException.class,()->service("http://127.0.0.1:9/doom.zip",tool.toString(),data).firmware());
        assertTrue(e.getMessage().contains("internet access"),e.getMessage());
    }
    @Test void withoutTheConverterTheFirmwareIsNotAvailable(@TempDir Path data)throws Exception{
        var s=service("https://example.invalid/x","/nonexistent",data);
        assertFalse(s.plan().available());assertThrows(IOException.class,s::firmware);
    }
}
