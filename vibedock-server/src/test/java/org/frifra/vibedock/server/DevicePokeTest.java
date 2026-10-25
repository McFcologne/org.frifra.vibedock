package org.frifra.vibedock.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DevicePokeTest {
    @Test void appearanceUsesExistingCallbackWithoutMarkingAnImagePull() throws Exception {
        var received=new LinkedBlockingQueue<String>();
        var device=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        device.createContext("/pull",exchange->{received.add(exchange.getRequestURI().toString());exchange.sendResponseHeaders(202,-1);exchange.close();});
        device.start();
        try{
            var poke=new DevicePoke(HttpClient.newHttpClient());
            var address=new ClientRegistry.Address("127.0.0.1",device.getAddress().getPort());
            poke.appearance(List.of(address),true);assertNull(received.poll(100,TimeUnit.MILLISECONDS));
            poke.uiCapable(address);poke.appearance(List.of(address),true);
            assertEquals("/pull?ui=dark",received.poll(5,TimeUnit.SECONDS));
            assertFalse(poke.reachedWithin(address,60));
            poke.appearance(List.of(address),false);
            assertEquals("/pull?ui=light",received.poll(5,TimeUnit.SECONDS));
        }finally{device.stop(0);}
    }

    @Test void asksTheDeviceToPullWithAnEmptyPost() throws Exception {
        var received=new LinkedBlockingQueue<String>();
        var device=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        device.createContext("/pull",exchange->{
            received.add(exchange.getRequestMethod()+" "+exchange.getRequestBody().readAllBytes().length);
            exchange.sendResponseHeaders(202,-1);exchange.close();
        });
        device.start();
        try{
            new DevicePoke(HttpClient.newHttpClient()).pullNow(List.of(new ClientRegistry.Address("127.0.0.1",device.getAddress().getPort())));
            assertEquals("POST 0",received.poll(5,TimeUnit.SECONDS));
            // An unreachable device is no error: it pulls at its next poll.
            new DevicePoke(HttpClient.newHttpClient()).pullNow(List.of(new ClientRegistry.Address("127.0.0.1",1)));
        }finally{device.stop(0);}
    }
}
