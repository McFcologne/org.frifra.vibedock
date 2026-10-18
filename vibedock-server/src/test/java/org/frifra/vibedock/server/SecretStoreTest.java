package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SecretStoreTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void existingSettingsAndEveryProviderCacheAreMigratedAndSurviveRestart(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"),"{\"allowedIps\":[],\"codex\":{\"token\":\"manual-old-secret\"},\"claude\":{},\"devices\":[]}");
        Map<String,String> credentials=Map.of("codex/auth.json","{\"tokens\":{\"access_token\":\"oauth-codex-secret\",\"refresh_token\":\"refresh-secret\"}}",
                "claude/.credentials.json","{\"claudeAiOauth\":{\"accessToken\":\"oauth-claude-secret\",\"refreshToken\":\"refresh-secret\"}}",
                "accounts/grok/auth.json","{\"key\":\"grok-secret\"}","accounts/gemini/.gemini/oauth_creds.json","{\"refresh_token\":\"google-secret\"}");
        for(var e:credentials.entrySet()){Path file=dir.resolve("auth/"+e.getKey());Files.createDirectories(file.getParent());Files.writeString(file,e.getValue());}
        var store=new SettingsStore(dir.toString(),json);
        assertEquals("manual-old-secret",store.get().codex().token());assertEquals("oauth-claude-secret",store.effective().claude().token());
        assertTrue(SecretStore.sealed(Files.readAllBytes(dir.resolve("settings.json"))));
        for(var e:credentials.entrySet()){
            Path file=dir.resolve("auth/"+e.getKey());assertTrue(SecretStore.sealed(Files.readAllBytes(file)));
            assertFalse(Files.readString(file).contains("secret"));assertEquals(e.getValue(),new String(store.secrets.read(file),java.nio.charset.StandardCharsets.UTF_8));
        }
        byte[] encrypted=Files.readAllBytes(dir.resolve("settings.json"));String id=Files.readString(dir.resolve(".installation-id"));
        assertEquals(store.effective(),new SettingsStore(dir.toString(),json).effective());
        assertArrayEquals(encrypted,Files.readAllBytes(dir.resolve("settings.json")));assertEquals(id,Files.readString(dir.resolve(".installation-id")));
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(dir.resolve(".installation-id")));
    }
    @Test void missingWrongOrTamperedInstallationDataNeverOverwritesCredentials(@TempDir Path dir) throws Exception {
        var store=new SettingsStore(dir.toString(),json);store.save(store.get());
        Path file=dir.resolve("settings.json"),id=dir.resolve(".installation-id");byte[] encrypted=Files.readAllBytes(file);String original=Files.readString(id);
        Files.delete(id);assertThrows(java.io.IOException.class,()->new SettingsStore(dir.toString(),json));assertFalse(Files.exists(id));assertArrayEquals(encrypted,Files.readAllBytes(file));
        Files.writeString(id,UUID.randomUUID().toString());assertThrows(java.io.IOException.class,()->new SettingsStore(dir.toString(),json));assertArrayEquals(encrypted,Files.readAllBytes(file));
        Files.writeString(id,original);byte[] tampered=encrypted.clone();tampered[tampered.length-5]^=1;Files.write(file,tampered);
        assertThrows(java.io.IOException.class,()->new SettingsStore(dir.toString(),json));assertArrayEquals(tampered,Files.readAllBytes(file));
    }
    @Test void ciphertextCannotBeMovedToAnotherCredentialPath(@TempDir Path dir) throws Exception {
        var vault=new SecretStore(dir,"","",true);Path first=dir.resolve("auth/a/auth.json"),other=dir.resolve("auth/b/auth.json");
        vault.write(first,"private-token".getBytes());Files.createDirectories(other.getParent());Files.copy(first,other);
        assertThrows(java.io.IOException.class,()->vault.read(other));
    }
    @Test void cliWorkspacePersistsRotationAndLogoutWithoutPlaintextOnDisk(@TempDir Path dir,@TempDir Path ram) throws Exception {
        var vault=new SecretStore(dir,"",ram.toString(),false);Path source=dir.resolve("auth/claude"),file=source.resolve(".credentials.json");
        vault.write(file,"old-token".getBytes());Path workspace;
        try(var login=vault.workspace(source)){
            workspace=login.path;assertEquals("old-token",Files.readString(workspace.resolve(".credentials.json")));
            assertThrows(java.io.IOException.class,()->vault.workspace(source));Files.writeString(workspace.resolve(".credentials.json"),"rotated-token");
            var otherStore=new SecretStore(dir,"",ram.toString(),false);
            assertThrows(java.io.IOException.class,()->otherStore.workspace(source));
            assertFalse(Files.readString(file).contains("rotated-token"));
        }
        assertFalse(Files.exists(workspace));assertEquals("rotated-token",new String(vault.read(file)));
        try(var logout=vault.workspace(source)){Files.delete(logout.path.resolve(".credentials.json"));}
        assertFalse(Files.exists(file));
    }
    @Test void failedCommitKeepsTheRamFilesAndReleasesTheSourceLock(@TempDir Path dir,@TempDir Path ram) throws Exception {
        var vault=new SecretStore(dir,"",ram.toString(),false);Path source=dir.resolve("auth/claude");
        vault.write(source.resolve(".credentials.json"),"old-token".getBytes());
        var workspace=vault.workspace(source);
        Files.createSymbolicLink(workspace.path.resolve("unexpected-link"),dir.resolve("outside"));
        assertThrows(java.io.IOException.class,workspace::close);assertTrue(Files.exists(workspace.path));
        assertDoesNotThrow(workspace::close);
        try(var retry=vault.workspace(source)){assertEquals("old-token",Files.readString(retry.path.resolve(".credentials.json")));}
    }
    @Test void passwordExportImportsIntoADifferentInstallationWithoutTheOriginalGuid(@TempDir Path dir) throws Exception {
        var source=new SettingsStore(dir.resolve("source").toString(),json);var s=source.get();
        source.save(new SettingsStore.Settings(s.allowedIps(),new SettingsStore.Credential("manual-secret","account"),s.claude(),List.of()));
        source.secrets.write(source.authDirectory(s.accounts().get(1)).resolve(".credentials.json"),"{\"claudeAiOauth\":{\"accessToken\":\"oauth-secret\",\"refreshToken\":\"refresh-secret\"}}".getBytes());
        String export=new String(new SourceTransfer(source,json).export("own export password"));
        var target=new SettingsStore(dir.resolve("target").toString(),json);var transfer=new SourceTransfer(target,json);
        var bundle=transfer.read(export,"own export password");assertThrows(IllegalArgumentException.class,()->transfer.read(export,"wrong password"));
        transfer.apply(bundle,Set.of("codex","claude"),true);
        assertNotEquals(Files.readString(dir.resolve("source/.installation-id")),Files.readString(dir.resolve("target/.installation-id")));
        assertEquals("manual-secret",target.effective().codex().token());assertEquals("oauth-secret",target.effective().claude().token());
        assertFalse(Files.readString(target.authDirectory(target.get().accounts().get(1)).resolve(".credentials.json")).contains("oauth-secret"));
    }
    @Test void renewalRunsInsideTheWorkspaceAndEncryptsTheNewRefreshToken(@TempDir Path dir,@TempDir Path ram) throws Exception {
        var vault=new SecretStore(dir,"",ram.toString(),false);var store=new SettingsStore(dir.toString(),json,vault);
        Path folder=store.authDirectory(store.get().accounts().get(1)),file=folder.resolve(".credentials.json");
        vault.write(file,("{\"claudeAiOauth\":{\"accessToken\":\"old\",\"expiresAt\":"+(Instant.now().minusSeconds(60).toEpochMilli())+"}}").getBytes());
        Path cli=dir.resolve("fake-claude");Files.writeString(cli,"#!/bin/sh\nprintf '%s' '{\"claudeAiOauth\":{\"accessToken\":\"new-secret\",\"refreshToken\":\"new-refresh\",\"expiresAt\":4102444800000}}' > \"$CLAUDE_CONFIG_DIR/.credentials.json\"\n");
        Files.setPosixFilePermissions(cli,PosixFilePermissions.fromString("rwx------"));
        var renewal=new SignInRenewal("grok","gemini",cli.toString(),json);ReflectionTestUtils.setField(renewal,"settings",store);
        renewal.renewIfDue(store.get().accounts().get(1),folder);
        assertEquals("new-secret",store.effective().claude().token());assertFalse(Files.readString(file).contains("new-secret"));
        assertTrue(new String(vault.read(file)).contains("new-refresh"));try(var files=Files.list(ram)){assertEquals(0,files.count());}
    }
}
