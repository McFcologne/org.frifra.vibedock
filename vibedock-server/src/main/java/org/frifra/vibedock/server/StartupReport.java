package org.frifra.vibedock.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.nio.file.*;

/** One line per relevant setting at start, so a log shows how the server was configured. */
@Component
class StartupReport {
    private static final Logger log=LoggerFactory.getLogger(StartupReport.class);
    private final int apiPort,adminPort;private final String dataDir,logDir;private final Twemoji twemoji;private final StorageErrors storage;
    private final ClientRegistry clients;
    StartupReport(@Value("${server.port}") int apiPort,@Value("${vibedock.admin-port}") int adminPort,@Value("${vibedock.data-dir}") String dataDir,
                  Twemoji twemoji,@Value("${vibedock.log-dir}") String logDir,
                  StorageErrors storage,ClientRegistry clients){
        this.apiPort=apiPort;this.adminPort=adminPort;this.dataDir=dataDir;this.twemoji=twemoji;this.logDir=logDir;this.storage=storage;
        this.clients=clients;
    }
    @EventListener(ApplicationReadyEvent.class)
    void started(){
        log.info("VibeDock server started: device API on port {}, administration on port {}",apiPort,adminPort);
        var emoji=twemoji.directory().toAbsolutePath().normalize();
        log.info("Data directory {}, logs in {}, emoji set {}",dataDir,logDir,Files.isDirectory(emoji)?emoji:"missing ("+emoji+"; set EMOJI_DIR to the Twemoji svg folder)");
        log.info("Announced addresses: internal {}, external {}; {} paired device(s)",
                clients.internalUrl().isBlank()?"derived from the administration host":clients.internalUrl(),
                clients.externalUrl().isBlank()?"none":clients.externalUrl(),clients.views().size());
        String problem=storage.writeProblem();
        if(problem!=null)log.error(problem);
    }
}
