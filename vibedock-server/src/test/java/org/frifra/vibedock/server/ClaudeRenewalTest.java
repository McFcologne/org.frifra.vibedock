package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** Claude sign-ins: the CLI runs in the source's folder shortly before the token expires, not otherwise. */
class ClaudeRenewalTest {
    private final ObjectMapper json=JsonCompatibility.mapper();
    private static String credentials(long expiresAtMillis){
        return "{\"claudeAiOauth\":{\"accessToken\":\"a\",\"refreshToken\":\"r\",\"expiresAt\":"+expiresAtMillis+"}}";
    }
    /** A stand-in for the Claude CLI: notes its arguments, folders and API key, and writes a token valid for eight more
     *  hours unless the folder holds "broken" (a refresh token that no longer works). */
    private Path fakeCli(Path dir) throws Exception {
        Path script=dir.resolve("claude");
        long later=(Instant.now().getEpochSecond()+8*3600)*1000;
        // One line per call; the arguments one per line (an empty one stays an empty line); folder, key and the number of
        // entries in the working folder separately, so no shell quirk mixes them.
        Files.writeString(script,"#!/bin/sh\necho call >> \"$CLAUDE_CONFIG_DIR/calls\"\nprintf '%s\\n' \"$@\" > \"$CLAUDE_CONFIG_DIR/args\"\n"
                +"printf '%s\\n' \"$CLAUDE_CONFIG_DIR\" \"${ANTHROPIC_API_KEY:-none}\" \"$(ls -A | wc -l | tr -d ' ')\" > \"$CLAUDE_CONFIG_DIR/env\"\n"
                +"[ -f \"$CLAUDE_CONFIG_DIR/broken\" ] || printf '%s' '"+credentials(later)+"' > \"$CLAUDE_CONFIG_DIR/.credentials.json\"\n");
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
        // Ten minutes left: one minimal model request (auth status renews nothing) with the source's folder as
        // configuration, run in an empty working folder without a foreign API key; the file is newer afterwards.
        Files.writeString(folder.resolve(".credentials.json"),credentials((Instant.now().getEpochSecond()+600)*1000));
        renewal.renewIfDue(account,folder);
        var command=SignInRenewal.claudeCommand("claude");
        assertTrue(command.containsAll(java.util.List.of("-p","--tools","","--strict-mcp-config","--disable-slash-commands","--no-session-persistence")));
        assertEquals(command.subList(1,command.size()),Files.readAllLines(folder.resolve("args")));
        assertEquals(java.util.List.of(folder.toString(),"none","0"),Files.readAllLines(folder.resolve("env")));
        long expires=SignInRenewal.claudeExpiry(json.readTree(folder.resolve(".credentials.json").toFile()));
        assertTrue(expires>Instant.now().getEpochSecond()+7*3600);
        // Expired already: still renewed by the request (the CLI refreshes an expired token before it).
        Files.writeString(folder.resolve(".credentials.json"),credentials((Instant.now().getEpochSecond()-60)*1000));
        renewal.renewIfDue(account,folder);
        assertEquals(2,Files.readAllLines(folder.resolve("calls")).size());
        assertTrue(SignInRenewal.claudeExpiry(json.readTree(folder.resolve(".credentials.json").toFile()))>Instant.now().getEpochSecond());
        // Expired and not renewable: tried once, then only after hours, not with every poll.
        Files.writeString(folder.resolve("broken"),"");
        Files.writeString(folder.resolve(".credentials.json"),credentials((Instant.now().getEpochSecond()-60)*1000));
        renewal.renewIfDue(account,folder);renewal.renewIfDue(account,folder);
        assertEquals(3,Files.readAllLines(folder.resolve("calls")).size());
    }
    /** A clock the test moves forward. */
    static class Time extends java.time.Clock {
        Instant instant=Instant.parse("2026-10-19T12:00:00Z");
        void advance(long seconds){instant=instant.plusSeconds(seconds);}
        public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
        public java.time.Clock withZone(java.time.ZoneId zone){return this;}
        public Instant instant(){return instant;}
    }
    @Test void aFailureBeforeTheExpiryDoesNotDelayTheFirstTryAfterIt(@TempDir Path dir) throws Exception {
        Path folder=Files.createDirectories(dir.resolve("accounts/claude-b"));
        var renewal=new SignInRenewal("grok","gemini",fakeCli(dir).toString(),json);var time=new Time();renewal.clock=time;
        var account=new SettingsStore.Account("claude-b","claude","Claude",null,"accounts/claude-b",false);
        Files.writeString(folder.resolve("broken"),"");
        Files.writeString(folder.resolve(".credentials.json"),credentials((time.instant().getEpochSecond()+600)*1000));
        // Ten minutes before the expiry: fails, tried again after ten minutes.
        renewal.renewIfDue(account,folder);
        assertEquals(1,Files.readAllLines(folder.resolve("calls")).size());
        // Expired now: the first try after the expiry still comes after ten minutes, not six hours.
        time.advance(SignInRenewal.CLAUDE_RETRY_SECONDS+60);renewal.renewIfDue(account,folder);
        assertEquals(2,Files.readAllLines(folder.resolve("calls")).size());
        // That one failed with the token expired: the next only after six hours.
        time.advance(SignInRenewal.CLAUDE_RETRY_SECONDS+60);renewal.renewIfDue(account,folder);
        assertEquals(2,Files.readAllLines(folder.resolve("calls")).size());
        time.advance(SignInRenewal.CLAUDE_EXPIRED_RETRY_SECONDS);renewal.renewIfDue(account,folder);
        assertEquals(3,Files.readAllLines(folder.resolve("calls")).size());
    }
    @Test void theExpiryIsReadFromMilliseconds() throws Exception {
        assertEquals(1792101834L,SignInRenewal.claudeExpiry(json.readTree(credentials(1792101834441L))));
        assertEquals(0L,SignInRenewal.claudeExpiry(json.readTree("{}")));
    }
}
