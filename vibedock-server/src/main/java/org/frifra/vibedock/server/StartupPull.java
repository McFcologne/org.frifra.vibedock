package org.frifra.vibedock.server;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

/**
 * After a restart every paired device is asked to pull right away instead of at its next poll, so the screens leave
 * "Server unreachable" quickly. It waits for the first usage collection, so the image already carries current data.
 */
@Component
class StartupPull {
    private final UsageService usage;private final ClientRegistry registry;private final DevicePoke poke;
    StartupPull(UsageService usage,ClientRegistry registry,DevicePoke poke){this.usage=usage;this.registry=registry;this.poke=poke;}

    @EventListener(ApplicationReadyEvent.class)
    void started(){
        usage.firstCollection().completeOnTimeout(null,60,TimeUnit.SECONDS).thenRun(()->poke.pullNow(registry.all()));
    }
}
