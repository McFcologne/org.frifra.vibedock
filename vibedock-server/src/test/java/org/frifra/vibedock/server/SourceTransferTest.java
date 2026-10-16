package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SourceTransferTest {
    private final ObjectMapper json=new ObjectMapper();
    private static final String PASSWORD="correct horse battery";

    private SettingsStore server(Path dir,String label) throws Exception {
        var store=new SettingsStore(dir.toString(),json);
        var codex=SettingsStore.Account.create("codex",label);
        var cursor=new SettingsStore.Account(java.util.UUID.randomUUID().toString(),"cursor","Team",new SettingsStore.Credential("synthetic-admin-key","dev@example.com"));
        store.save(store.get().withAccounts(List.of(codex,cursor)));
        Path auth=store.authDirectory(codex);Files.createDirectories(auth);
        Files.writeString(auth.resolve("auth.json"),"{\"tokens\":{\"access_token\":\"synthetic-"+label+"\"}}");
        Files.writeString(auth.resolve("history.jsonl"),"not exported");
        return store;
    }
    @Test void exportIsEncryptedAndRestoresSourcesWithTheirCredentials() throws Exception {
        var source=server(Files.createTempDirectory("vibedock-export-"),"Work");
        byte[] file=new SourceTransfer(source,json).export(PASSWORD);
        String text=new String(file,StandardCharsets.UTF_8);
        assertFalse(text.contains("synthetic"));assertFalse(text.contains("Work"));assertFalse(text.contains("dev@example.com"));

        var target=server(Files.createTempDirectory("vibedock-import-"),"Private");
        var transfer=new SourceTransfer(target,json);
        var exception=assertThrows(IllegalArgumentException.class,()->transfer.read(text,"wrong password!"));
        assertEquals("Wrong password or damaged file",exception.getMessage());
        assertThrows(IllegalArgumentException.class,()->transfer.read("{\"format\":\"other\"}",PASSWORD));

        var bundle=transfer.read(text,PASSWORD);
        assertEquals(List.of("Work","Team"),bundle.accounts().stream().map(SettingsStore.Account::label).toList());
        // Adding only the Codex source: the target keeps its own two sources.
        var work=bundle.accounts().get(0);
        transfer.apply(bundle,Set.of(work.id()),false);
        assertEquals(List.of("Private","Team","Work"),target.get().accounts().stream().map(SettingsStore.Account::label).toList());
        var added=target.get().accounts().get(2);
        assertEquals(work.id(),added.id());assertEquals("accounts/"+work.id(),added.folder());
        assertEquals("synthetic-Work",target.effective().account(added.id()).orElseThrow().manual().token());
        assertFalse(Files.exists(target.authDirectory(added).resolve("history.jsonl")));
        // The same source again becomes a copy with its own id.
        transfer.apply(bundle,Set.of(work.id()),false);
        assertEquals(4,target.get().accounts().size());assertNotEquals(work.id(),target.get().accounts().get(3).id());

        // Replacing: exactly the exported sources, same ids, old sign-ins gone.
        var previous=target.get().accounts().get(0);
        transfer.apply(bundle,Set.of(work.id(),bundle.accounts().get(1).id()),true);
        assertEquals(source.get().accounts(),target.get().accounts());
        assertFalse(Files.exists(target.authDirectory(previous)));
        assertEquals("synthetic-admin-key",target.effective().accounts().get(1).manual().token());
        assertThrows(IllegalArgumentException.class,()->transfer.apply(bundle,Set.of(),true));
    }
    @Test void exportsOfTheEarlierProjectNameStillImport() throws Exception {
        var source=server(Files.createTempDirectory("vibedock-export-"),"Work");
        String text=new String(new SourceTransfer(source,json).export(PASSWORD,"vibetv-sources"),StandardCharsets.UTF_8);
        var target=server(Files.createTempDirectory("vibedock-import-"),"Private");
        assertEquals(List.of("Work","Team"),new SourceTransfer(target,json).read(text,PASSWORD).accounts().stream().map(SettingsStore.Account::label).toList());
    }
    @Test void exportNeedsALongPassword() throws Exception {
        var store=server(Files.createTempDirectory("vibedock-export-"),"Work");
        assertThrows(IllegalArgumentException.class,()->new SourceTransfer(store,json).export("short"));
    }
    @Test void aFailedReplacementKeepsTheCurrentSignIns() throws Exception {
        var source=server(Files.createTempDirectory("vibedock-export-"),"Work");
        String file=new String(new SourceTransfer(source,json).export(PASSWORD),StandardCharsets.UTF_8);
        Path dir=Files.createTempDirectory("vibedock-import-");server(dir,"Private");
        var failing=new java.util.concurrent.atomic.AtomicBoolean(false);
        var target=new SettingsStore(dir.toString(),json){
            @Override public synchronized void save(Settings settings) throws java.io.IOException {
                if(failing.get())throw new java.io.IOException("disk full");super.save(settings);
            }
        };
        var before=target.get();var privat=before.accounts().get(0);
        String credential=Files.readString(target.authDirectory(privat).resolve("auth.json"));
        var transfer=new SourceTransfer(target,json);var bundle=transfer.read(file,PASSWORD);
        failing.set(true);
        assertThrows(java.io.IOException.class,()->transfer.apply(bundle,Set.of(bundle.accounts().get(0).id()),true));
        assertEquals(before,target.get());
        assertEquals(credential,Files.readString(target.authDirectory(privat).resolve("auth.json")));
        assertTrue(Files.exists(target.authDirectory(privat).resolve("history.jsonl")));
        assertFalse(Files.exists(target.authDirectory(bundle.accounts().get(0))));
        try(var entries=Files.list(dir.resolve("auth"))){assertTrue(entries.noneMatch(p->p.getFileName().toString().startsWith(".replaced-")));}
        failing.set(false);
        transfer.apply(bundle,Set.of(bundle.accounts().get(0).id()),true);
        assertFalse(Files.exists(target.authDirectory(privat)));
        try(var entries=Files.list(dir.resolve("auth"))){assertTrue(entries.noneMatch(p->p.getFileName().toString().startsWith(".replaced-")));}
    }
}
