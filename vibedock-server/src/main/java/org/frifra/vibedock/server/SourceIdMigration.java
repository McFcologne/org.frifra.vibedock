package org.frifra.vibedock.server;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.IOException;

/**
 * Data sources of earlier versions had readable ids ("codex", "claude", "privat"). On start they get random ids and
 * every design that shows them is updated; sign-ins stay where they are.
 */
@Component
class SourceIdMigration {
    private static final Logger log=LoggerFactory.getLogger(SourceIdMigration.class);
    private final SettingsStore settings;private final ClientRegistry registry;
    SourceIdMigration(SettingsStore settings,ClientRegistry registry){this.settings=settings;this.registry=registry;}
    @PostConstruct void migrate() throws IOException {
        var before=settings.get().accounts().stream().map(SettingsStore.Account::id).toList();
        // Renames of an interrupted earlier start come first; the designs are updated before the record is removed.
        var renamed=new java.util.LinkedHashMap<>(settings.pendingRenames());renamed.putAll(settings.assignRandomIds());
        registry.renameSources(renamed);
        settings.clearPendingRenames();
        if(!before.equals(settings.get().accounts().stream().map(SettingsStore.Account::id).toList()))
            log.info("Data sources got random ids; designs updated (was: {})",String.join(", ",before));
    }
}
