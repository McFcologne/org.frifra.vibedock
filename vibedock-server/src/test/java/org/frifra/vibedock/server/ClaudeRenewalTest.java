package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** Claude sign-ins: the CLI runs in the source's folder shortly before the token expires, not otherwise. */
class ClaudeRenewalTest {
    private final ObjectMapper json=new ObjectMapper();
    private static String credentials(long expiresAtMillis){
        return "{\"claudeAiOauth\":{\"accessToken\":\"a\",\"refreshToken\":\"r\",\"expiresAt\":"+expiresAtMillis+"}}";
    }
    /** A stand-in for the Claude CLI: notes its arguments and folder, and writes a token valid for eight more hours. */
    private Path fakeCli(Path dir) throws Exception {
        Path script=dir.resolve("claude");
        long later=(Instant.now().getEpochSecond()+8*3600)*1000;
        Files.writeString(script,"#!/bin/sh\necho \"$@ $CLAUDE_CONFIG_DIR ${ANTHROPIC_API_KEY:-none}\" >> \"$CLAUDE_CONFIG_DIR/calls\"\n"
                +"printf '%s' '"+credentials(later)+"' > \"$CLAUDE_CONFIG_DIR/.credentials.json\"\n");
        Files.setPosixFilePermissions(script,PosixFilePermissions.fromString("rwx------"));
        return script;
    }
    @Test void anExpiringClaudeSignInIsRenewedByTheCliInItsOwnFolder(@TempDir Path dir) throws Exception {
        Path folder=Files.createDirectories(dir.resolve("accounts/claude-a"));
        var renewal=new SignInRenewal("grok","gemini",fakeCli(dir).toString(),json);
        var account=new SettingsStore.Account("claude-a","claude","Claude",null,"accounts/claude-a",false);
        // Valid for hours: nothing runs.
        Files.writeString(folder.resolve(".credentials.json"),credentials((Instant.now().getEpochSecond()+5*3600)*1000));
        renewal.renewIfDue(account,folder);
        assertFalse(Files.exists(folder.resolve("calls")));
        // Ten minutes left: the CLI runs once in the source's folder, without a foreign API key, and the file is newer.
        Files.writeString(folder.resolve(".credentials.json"),credentials((Instant.now().getEpochSecond()+600)*1000));
        renewal.renewIfDue(account,folder);
        assertEquals("auth status --json "+folder+" none\n",Files.readString(folder.resolve("calls")));
        long expires=SignInRenewal.claudeExpiry(json.readTree(folder.resolve(".credentials.json").toFile()));
        assertTrue(expires>Instant.now().getEpochSecond()+7*3600);
        // Expired already and the CLI changed nothing: tried again only after the retry gap, not with every poll.
        Files.writeString(folder.resolve(".credentials.json"),credentials((Instant.now().getEpochSecond()-60)*1000));
        renewal.renewIfDue(account,folder);
        assertEquals(1,Files.readAllLines(folder.resolve("calls")).size());
    }
    @Test void theExpiryIsReadFromMilliseconds() throws Exception {
        assertEquals(1792101834L,SignInRenewal.claudeExpiry(json.readTree(credentials(1792101834441L))));
        assertEquals(0L,SignInRenewal.claudeExpiry(json.readTree("{}")));
    }
}
