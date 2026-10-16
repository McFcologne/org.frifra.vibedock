package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginCredentialsTest {
    final ObjectMapper json=new ObjectMapper();
    void cache(Path dir,String relative,String body) throws Exception {
        Path file=dir.resolve(relative);Files.createDirectories(file.getParent());Files.writeString(file,body);
    }
    @Test void cliLoginSurvivesServiceRestartAndManualTokenOverridesIt(@TempDir Path dir) throws Exception {
        cache(dir,"auth/codex/auth.json","""
            {"tokens":{"access_token":"codex-test","refresh_token":"refresh-test","account_id":"workspace-test"}}
            """);
        cache(dir,"auth/claude/.credentials.json","""
            {"claudeAiOauth":{"accessToken":"claude-test","refreshToken":"refresh-test","expiresAt":1900000000000}}
            """);
        var store=new SettingsStore(dir.toString(),json);
        assertEquals("workspace-test",store.effective().codex().accountId());
        assertEquals("claude-test",store.effective().claude().token());
        assertEquals(store.effective(),new SettingsStore(dir.toString(),json).effective());
        assertTrue(store.get().codex().token().isEmpty()); // never copies CLI tokens into settings
        store.save(new SettingsStore.Settings(List.of(),new SettingsStore.Credential("manual","manual-account"),store.get().claude(),List.of()));
        assertEquals("manual",store.effective().codex().token());
    }
    @Test void rotationLogoutAndBrokenFilesNeverReturnOldQuota(@TempDir Path dir) throws Exception {
        cache(dir,"auth/codex/auth.json","{\"tokens\":{\"access_token\":\"first\",\"account_id\":\"one\"}}");
        var store=new SettingsStore(dir.toString(),json);
        var client=mock(ProviderClient.class);
        when(client.fetch(eq("codex"),any())).thenReturn(new ProviderClient.Reading(new ProviderClient.Window(12,1900000000),null));
        var service=new UsageService(store,client,mock(DeviceSender.class),Clock.fixed(Instant.ofEpochSecond(1800000000),ZoneOffset.UTC));
        service.refresh();assertNotNull(service.snapshot().providers().get("codex").fiveHour());
        cache(dir,"auth/codex/auth.json","{\"tokens\":{\"access_token\":\"second\",\"account_id\":\"two\"}}");
        assertEquals("waiting",service.snapshot().providers().get("codex").status());
        assertNull(service.snapshot().providers().get("codex").fiveHour());
        service.refresh();verify(client).fetch("codex",new SettingsStore.Credential("second","two"));
        assertFalse(json.writeValueAsString(service.snapshot()).contains("\"second\""));
        Files.delete(dir.resolve("auth/codex/auth.json"));
        assertEquals("unconfigured",service.snapshot().providers().get("codex").status());
        cache(dir,"auth/codex/auth.json","{incomplete");
        assertTrue(store.effective().codex().token().isEmpty());
    }
    @Test void apiKeysOversizedTokensAndSymlinksAreNotSubscriptionLogins(@TempDir Path dir) throws Exception {
        var store=new SettingsStore(dir.toString(),json);
        cache(dir,"auth/codex/auth.json","{\"OPENAI_API_KEY\":\"test-api-key\"}");
        assertTrue(store.effective().codex().token().isEmpty());
        cache(dir,"auth/claude/.credentials.json","{\"claudeAiOauth\":{\"accessToken\":\""+"a".repeat(16385)+"\"}}");
        assertTrue(store.effective().claude().token().isEmpty());
        Path file=dir.resolve("auth/codex/auth.json");Files.delete(file);
        Path other=dir.resolve("other.json");Files.writeString(other,"{\"tokens\":{\"access_token\":\"outside\"}}");
        Files.createSymbolicLink(file,other);
        assertTrue(store.effective().codex().token().isEmpty());
    }
}
