package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** The container login helper reads the same settings as the server, also those saved before "suspended" existed. */
class CredentialCliTest {
    @Test void readsSettingsSavedBeforeSuspendedExisted(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"),"""
            {"allowedIps":[],"devices":[],
             "accounts":[{"id":"work","type":"claude","label":"Work","manual":{"token":"","accountId":""}}]}
            """);
        var account=CredentialCli.store(dir.toString(),"").get().accounts().getFirst();
        assertEquals("claude",account.type());assertFalse(account.suspended());
    }
}
