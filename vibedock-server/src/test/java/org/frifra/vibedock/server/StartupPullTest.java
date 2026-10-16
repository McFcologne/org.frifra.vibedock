package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.mockito.Mockito.*;

class StartupPullTest {
    @Test void everyDeviceIsAskedToPullOnceTheFirstReadingsAreThere(){
        var usage=mock(UsageService.class);var registry=mock(ClientRegistry.class);var poke=mock(DevicePoke.class);
        var first=new CompletableFuture<Void>();when(usage.firstCollection()).thenReturn(first);
        var devices=List.of(new ClientRegistry.Address("192.168.1.20",80),new ClientRegistry.Address("192.168.1.21",80));
        when(registry.all()).thenReturn(devices);
        new StartupPull(usage,registry,poke).started();
        verifyNoInteractions(poke);
        first.complete(null);
        verify(poke,timeout(1000)).pullNow(devices);
    }
}
