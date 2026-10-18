package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicDnsTest {
    @Test void secondResolverStillWorksWhenFirstFailsAndDuplicateAddressesAreRemoved(){
        var tried=new ArrayList<String>();
        var fallback=new PublicDns(){
            @Override List<String> lookup(String host,String resolver) throws Exception {
                tried.add(resolver);if(resolver.equals("8.8.8.8"))throw new java.io.IOException("test unavailable");
                return List.of("203.0.113.5","203.0.113.5");
            }
        };
        assertEquals(List.of("203.0.113.5"),fallback.resolve("external.example.org"));
        assertEquals(List.of("8.8.8.8","1.1.1.1"),tried);
    }
    @Test void jdkDnsProviderReadsIpv4AndIpv6FromTheSelectedResolver() throws Exception {
        try(var socket=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))){
            socket.setSoTimeout(5000);
            var worker=java.util.concurrent.CompletableFuture.runAsync(()->{
                try{
                    for(int count=0;count<2;count++){
                        byte[] data=new byte[1024];var packet=new DatagramPacket(data,data.length);socket.receive(packet);
                        int end=12;while(data[end]!=0)end+=1+(data[end]&255);end++;
                        int type=((data[end]&255)<<8)|(data[end+1]&255),questionEnd=end+4;
                        byte[] ip=type==1?new byte[]{(byte)203,0,113,5}:InetAddress.getByName("2001:db8::5").getAddress();
                        var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes);
                        out.write(data,0,2);out.writeShort(0x8180);out.writeShort(1);out.writeShort(1);out.writeShort(0);out.writeShort(0);
                        out.write(data,12,questionEnd-12);out.writeShort(0xc00c);out.writeShort(type);out.writeShort(1);
                        out.writeInt(60);out.writeShort(ip.length);out.write(ip);
                        byte[] reply=bytes.toByteArray();socket.send(new DatagramPacket(reply,reply.length,packet.getSocketAddress()));
                    }
                }catch(Exception e){throw new RuntimeException(e);}
            });
            var answer=new PublicDns().lookup("external.example.org","127.0.0.1:"+socket.getLocalPort());
            assertEquals(List.of("203.0.113.5","2001:db8:0:0:0:0:0:5"),answer);
            worker.get(6,java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
