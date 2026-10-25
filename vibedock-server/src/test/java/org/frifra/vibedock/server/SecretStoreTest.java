package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SecretStoreTest {
    final ObjectMapper json=JsonCompatibility.mapper();
    @Test void wholeFileEncryptionMigratesToSecretFieldsAndPreservesEditableMetadata(@TempDir Path dir) throws Exception {
        var vault=new SecretStore(dir,"","",true);Path file=dir.resolve("auth/claude/.credentials.json");Files.createDirectories(file.getParent());
        String plain="{\"claudeAiOauth\":{\"accessToken\":\"access-secret\",\"refreshToken\":\"refresh-secret\",\"expiresAt\":1900000000000,\"scopes\":[\"user:profile\"]},\"label\":\"Work\"}";
        legacyFile(vault,dir,file,plain);
        Path settings=dir.resolve("settings.json");String oldSettings="{\"allowedIps\":[\"192.168.1.0/24\"],\"codex\":{\"token\":\"manual-secret\"}}";
        legacyFile(vault,dir,settings,oldSettings);
        Path note=dir.resolve("auth/claude/note.txt");Files.writeString(note,"readable auxiliary file");
        var migrated=new SecretStore(dir,"","",true);
        var stored=(tools.jackson.databind.node.ObjectNode)json.readTree(file.toFile());
        assertEquals("Work",stored.path("label").asText());assertEquals(1900000000000L,stored.path("claudeAiOauth").path("expiresAt").asLong());
        assertTrue(stored.path("claudeAiOauth").path("scopes").isArray());
        assertTrue(stored.path("claudeAiOauth").path("accessToken").asText().startsWith(SecretStore.FIELD_PREFIX));
        assertTrue(stored.path("claudeAiOauth").path("refreshToken").asText().startsWith(SecretStore.FIELD_PREFIX));
        assertEquals(json.readTree(plain),json.readTree(migrated.read(file)));
        assertEquals(json.readTree(oldSettings),json.readTree(migrated.read(settings)));
        assertEquals("192.168.1.0/24",json.readTree(settings.toFile()).path("allowedIps").get(0).asText());
        assertTrue(json.readTree(settings.toFile()).path("codex").path("token").asText().startsWith(SecretStore.FIELD_PREFIX));
        stored.put("label","Personal");Files.writeString(file,json.writerWithDefaultPrettyPrinter().writeValueAsString(stored));
        assertEquals("Personal",json.readTree(migrated.read(file)).path("label").asText());
        assertEquals("access-secret",json.readTree(migrated.read(file)).path("claudeAiOauth").path("accessToken").asText());
        assertEquals("readable auxiliary file",Files.readString(note));
        byte[] before=Files.readAllBytes(file);new SecretStore(dir,"","",true);assertArrayEquals(before,Files.readAllBytes(file));
    }
    private void legacyFile(SecretStore vault,Path dir,Path file,String plain) throws Exception {
        byte[] key=(byte[])ReflectionTestUtils.getField(vault,"key"),nonce=new byte[12];new java.security.SecureRandom().nextBytes(nonce);
        var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,nonce));
        cipher.updateAAD(("VIBEDOCK-SEALED-1\n"+dir.relativize(file).toString().replace('\\','/')).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] encrypted=cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.writeString(file,"VIBEDOCK-SEALED-1\n"+Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(12+encrypted.length).put(nonce).put(encrypted).array()));
    }
    @Test void onlyCredentialValuesAreEncryptedAndAdminPasswordRemainsHashed(@TempDir Path dir) throws Exception {
        var store=new SettingsStore(dir.toString(),json);
        var account=new SettingsStore.Account("work","cursor","Work",new SettingsStore.Credential("api-secret","person@example.com"));
        store.save(store.get().withAccounts(List.of(account)));
        var stored=json.readTree(dir.resolve("settings.json").toFile()).path("accounts").get(0);
        assertEquals("work",stored.path("id").asText());assertEquals("cursor",stored.path("type").asText());assertEquals("Work",stored.path("label").asText());
        assertEquals("person@example.com",stored.path("manual").path("accountId").asText());
        assertTrue(stored.path("manual").path("token").asText().startsWith(SecretStore.FIELD_PREFIX));
        String hash=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test portal password");store.saveAdminPasswordHash(hash);
        new SettingsStore(dir.toString(),json);assertEquals(hash,Files.readString(dir.resolve("admin-password")));assertEquals(hash,store.adminPasswordHash().orElseThrow());
    }
    @Test void codexPluginCacheStaysOutOfTheRamWorkspace(@TempDir Path dir,@TempDir Path ram) throws Exception {
        // Codex keeps downloaded plugins (here 68 MB with a Git pack) in .tmp; Docker's /dev/shm has 64 MB by default.
        Path folder=dir.resolve("auth/accounts/private");Files.createDirectories(folder.resolve(".tmp/plugins/.git"));
        Files.writeString(folder.resolve("auth.json"),"{\"tokens\":{\"access_token\":\"old-secret\"}}");
        Files.write(folder.resolve(".tmp/plugins/.git/pack"),new byte[256*1024]);
        Files.writeString(folder.resolve(".tmp/plugins/manifest.json"),"{\"token\":\"not-a-credential\"}");
        var vault=new SecretStore(dir,"",ram.toString(),false);
        assertEquals("not-a-credential",json.readTree(folder.resolve(".tmp/plugins/manifest.json").toFile()).path("token").asText(),"scratch is left as it is");
        try(var workspace=vault.workspace(folder)){
            assertFalse(Files.exists(workspace.path.resolve(".tmp")));
            assertTrue(Files.readString(workspace.path.resolve("auth.json")).contains("old-secret"));
            Files.createDirectories(workspace.path.resolve(".tmp/new"));Files.writeString(workspace.path.resolve(".tmp/new/cache"),"x");
        }
        assertFalse(Files.exists(folder.resolve(".tmp/new")),"scratch the CLI wrote in RAM is not stored");
        assertTrue(Files.exists(folder.resolve(".tmp/plugins/.git/pack")),"and the stored scratch is not deleted");
    }
    @Test void codexLauncherScratchLinksDoNotBlockMigrationOrCliWorkspaces(@TempDir Path dir,@TempDir Path ram,@TempDir Path outside) throws Exception {
        Path executable=outside.resolve("codex");Files.writeString(executable,"external executable must not be touched");
        for(String account:List.of("codex","accounts/private")){
            Path folder=dir.resolve("auth/"+account);Files.createDirectories(folder.resolve("tmp/arg0/codex-run"));
            Files.writeString(folder.resolve("auth.json"),"{\"tokens\":{\"access_token\":\"old-secret\"}}");
            Files.createSymbolicLink(folder.resolve("tmp/arg0/codex-run/apply_patch"),executable);
        }
        var vault=new SecretStore(dir,"",ram.toString(),false);
        for(String account:List.of("codex","accounts/private")){
            Path folder=dir.resolve("auth/"+account);
            assertTrue(json.readTree(folder.resolve("auth.json").toFile()).path("tokens").path("access_token").asText().startsWith(SecretStore.FIELD_PREFIX));
            assertTrue(Files.isSymbolicLink(folder.resolve("tmp/arg0/codex-run/apply_patch")));
            try(var workspace=vault.workspace(folder)){
                assertFalse(Files.exists(workspace.path.resolve("tmp/arg0")));
                assertTrue(Files.readString(workspace.path.resolve("auth.json")).contains("old-secret"));
                Files.createDirectories(workspace.path.resolve("tmp/arg0/new-run"));
                Files.createSymbolicLink(workspace.path.resolve("tmp/arg0/new-run/apply_patch"),executable);
            }
            assertFalse(Files.exists(folder.resolve("tmp/arg0/new-run")));
        }
        assertEquals("external executable must not be touched",Files.readString(executable));
        assertDoesNotThrow(()->new SecretStore(dir,"",ram.toString(),false));
    }
    @Test void credentialLinksRemainRejectedWithoutTouchingTheirTargets(@TempDir Path dir,@TempDir Path outside) throws Exception {
        Path target=outside.resolve("credential");Files.writeString(target,"external secret");
        Files.createDirectories(dir.resolve("auth/codex"));Files.createSymbolicLink(dir.resolve("auth/codex/auth.json"),target);
        assertThrows(java.io.IOException.class,()->new SettingsStore(dir.toString(),json));
        assertEquals("external secret",Files.readString(target));assertFalse(Files.exists(dir.resolve(".installation-id")));
    }
    @Test void existingSettingsAndEveryProviderCacheAreMigratedAndSurviveRestart(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"),"{\"allowedIps\":[],\"codex\":{\"token\":\"manual-old-secret\"},\"claude\":{},\"devices\":[]}");
        Map<String,String> credentials=Map.of("codex/auth.json","{\"tokens\":{\"access_token\":\"oauth-codex-secret\",\"refresh_token\":\"refresh-secret\"},\"OPENAI_API_KEY\":\"api-key-secret\"}",
                "claude/.credentials.json","{\"claudeAiOauth\":{\"accessToken\":\"oauth-claude-secret\",\"refreshToken\":\"refresh-secret\"}}",
                "accounts/grok/auth.json","{\"key\":\"grok-secret\"}","accounts/gemini/.gemini/oauth_creds.json","{\"refresh_token\":\"google-secret\"}");
        for(var e:credentials.entrySet()){Path file=dir.resolve("auth/"+e.getKey());Files.createDirectories(file.getParent());Files.writeString(file,e.getValue());}
        var store=new SettingsStore(dir.toString(),json);
        assertEquals("manual-old-secret",store.get().codex().token());assertEquals("oauth-claude-secret",store.effective().claude().token());
        var saved=json.readTree(dir.resolve("settings.json").toFile());
        assertTrue(new String(store.secrets.read(dir.resolve("settings.json"))).contains("manual-old-secret"));
        assertTrue(saved.path("codex").path("token").asText().startsWith(SecretStore.FIELD_PREFIX));
        assertTrue(saved.path("allowedIps").isArray());
        for(var e:credentials.entrySet()){
            Path file=dir.resolve("auth/"+e.getKey());assertTrue(json.readTree(file.toFile()).isObject());
            assertFalse(Files.readString(file).contains("secret"));assertEquals(json.readTree(e.getValue()),json.readTree(store.secrets.read(file)));
        }
        byte[] encrypted=Files.readAllBytes(dir.resolve("settings.json"));String id=Files.readString(dir.resolve(".installation-id"));
        assertEquals(store.effective(),new SettingsStore(dir.toString(),json).effective());
        assertArrayEquals(encrypted,Files.readAllBytes(dir.resolve("settings.json")));assertEquals(id,Files.readString(dir.resolve(".installation-id")));
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(dir.resolve(".installation-id")));
    }
    @Test void missingWrongOrTamperedInstallationDataNeverOverwritesCredentials(@TempDir Path dir) throws Exception {
        var store=new SettingsStore(dir.toString(),json);
        store.save(new SettingsStore.Settings(List.of(),new SettingsStore.Credential("secret", ""),store.get().claude(),List.of()));
        Path file=dir.resolve("settings.json"),id=dir.resolve(".installation-id");byte[] encrypted=Files.readAllBytes(file);String original=Files.readString(id);
        Files.delete(id);assertThrows(java.io.IOException.class,()->new SettingsStore(dir.toString(),json));assertFalse(Files.exists(id));assertArrayEquals(encrypted,Files.readAllBytes(file));
        Files.writeString(id,UUID.randomUUID().toString());assertThrows(java.io.IOException.class,()->new SettingsStore(dir.toString(),json));assertArrayEquals(encrypted,Files.readAllBytes(file));
        Files.writeString(id,original);var damaged=(tools.jackson.databind.node.ObjectNode)json.readTree(encrypted);
        var field=(tools.jackson.databind.node.ObjectNode)damaged.path("accounts").get(0).path("manual");
        String cipher=field.path("token").asText();int n=SecretStore.FIELD_PREFIX.length()+3;
        field.put("token",cipher.substring(0,n)+(cipher.charAt(n)=='A'?'B':'A')+cipher.substring(n+1));
        byte[] tampered=json.writeValueAsBytes(damaged);Files.write(file,tampered);
        assertThrows(java.io.IOException.class,()->new SettingsStore(dir.toString(),json));assertArrayEquals(tampered,Files.readAllBytes(file));
    }
    @Test void ciphertextCannotBeMovedToAnotherCredentialPath(@TempDir Path dir) throws Exception {
        var vault=new SecretStore(dir,"","",true);Path first=dir.resolve("auth/a/auth.json"),other=dir.resolve("auth/b/auth.json");
        vault.write(first,"{\"token\":\"private-token\"}".getBytes());Files.createDirectories(other.getParent());Files.copy(first,other);
        assertThrows(java.io.IOException.class,()->vault.read(other));
    }
    @Test void cliWorkspacePersistsRotationAndLogoutWithoutPlaintextOnDisk(@TempDir Path dir,@TempDir Path ram) throws Exception {
        var vault=new SecretStore(dir,"",ram.toString(),false);Path source=dir.resolve("auth/claude"),file=source.resolve(".credentials.json");
        vault.write(file,"{\"accessToken\":\"old-token\"}".getBytes());Path workspace;
        try(var login=vault.workspace(source)){
            workspace=login.path;assertEquals("old-token",json.readTree(workspace.resolve(".credentials.json").toFile()).path("accessToken").asText());
            assertThrows(java.io.IOException.class,()->vault.workspace(source));Files.writeString(workspace.resolve(".credentials.json"),"{\"accessToken\":\"rotated-token\"}");
            var otherStore=new SecretStore(dir,"",ram.toString(),false);
            assertThrows(java.io.IOException.class,()->otherStore.workspace(source));
            assertFalse(Files.readString(file).contains("rotated-token"));
        }
        assertFalse(Files.exists(workspace));assertEquals("rotated-token",json.readTree(vault.read(file)).path("accessToken").asText());
        try(var logout=vault.workspace(source)){Files.delete(logout.path.resolve(".credentials.json"));}
        assertFalse(Files.exists(file));
    }
    @Test void failedCommitKeepsTheRamFilesAndReleasesTheSourceLock(@TempDir Path dir,@TempDir Path ram) throws Exception {
        var vault=new SecretStore(dir,"",ram.toString(),false);Path source=dir.resolve("auth/claude");
        vault.write(source.resolve(".credentials.json"),"{\"accessToken\":\"old-token\"}".getBytes());
        var workspace=vault.workspace(source);
        Files.createSymbolicLink(workspace.path.resolve("unexpected-link"),dir.resolve("outside"));
        assertThrows(java.io.IOException.class,workspace::close);assertTrue(Files.exists(workspace.path));
        assertDoesNotThrow(workspace::close);
        try(var retry=vault.workspace(source)){assertEquals("old-token",json.readTree(retry.path.resolve(".credentials.json").toFile()).path("accessToken").asText());}
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
