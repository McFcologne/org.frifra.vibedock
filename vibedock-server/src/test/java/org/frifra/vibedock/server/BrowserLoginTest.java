package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserLoginTest {
    final ObjectMapper json=new ObjectMapper();
    BrowserLogin login(Path dir,SettingsStore store) throws Exception {
        Path executable=dir.resolve("client");
        String java=Path.of(System.getProperty("java.home"),"bin/java").toString();
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Files.writeString(executable,"#!/bin/sh\nexec '"+java.replace("'","'\\''")+"' -cp '"+cp.replace("'","'\\''")+"' org.frifra.vibedock.server.FakeLoginClient \"$@\"\n");
        Files.setPosixFilePermissions(executable,PosixFilePermissions.fromString("rwx------"));
        var usage=new UsageService(store,mock(ProviderClient.class),mock(DeviceSender.class),Clock.systemUTC());
        return new BrowserLogin(dir.toString(),executable.toString(),executable.toString(),json,store,usage);
    }
    BrowserLogin.View await(BrowserLogin login,String provider,Predicate<BrowserLogin.View> condition) throws Exception {
        for (int i=0;i<200;i++) {
            var view=login.status(provider);if (condition.test(view)) return view;
            if (view.status().equals("error")) fail(view.message());Thread.sleep(25);
        }
        throw new AssertionError("Login simulator timed out");
    }
    @Test void codexButtonStartsStructuredFlowAndPersistsWithoutDeviceWrites(@TempDir Path dir) throws Exception {
        var store=new SettingsStore(dir.toString(),json);
        store.save(new SettingsStore.Settings(List.of(),new SettingsStore.Credential("old-manual","old-account"),store.get().claude(),List.of()));
        var login=login(dir,store);
        try {
            var first=login.start("codex");assertEquals(first.id(),login.start("codex").id());
            var waiting=await(login,"codex",v->v.status().equals("waiting"));
            assertEquals("TEST-1234",waiting.userCode());assertEquals("https://auth.openai.com/codex/device",waiting.authUrl());
            Files.createFile(dir.resolve("auth/codex/approve"));
            var success=await(login,"codex",v->v.status().equals("success"));
            assertNull(success.authUrl());assertNull(success.userCode());
            assertEquals("synthetic-codex-token",new SettingsStore(dir.toString(),json).effective().codex().token());
            assertTrue(store.get().codex().token().isEmpty());
            assertFalse(json.writeValueAsString(success).contains("synthetic-codex-token"));
        } finally { login.close(); }
    }
    @Test void claudeButtonAcceptsBrowserReturnCodeAndPersists(@TempDir Path dir) throws Exception {
        var store=new SettingsStore(dir.toString(),json);var login=login(dir,store);
        try {
            login.start("claude");var view=await(login,"claude",v->v.status().equals("waiting"));
            assertEquals("https://claude.ai/oauth/authorize?client_id=fixture&state=synthetic-state",view.authUrl());
            assertTrue(view.needsCode());
            assertThrows(IllegalArgumentException.class,()->login.submitCode("claude","obsolete-id","test-code#test-state"));
            assertThrows(IllegalArgumentException.class,()->login.submitCode("claude",view.id(),"bad\ncode"));
            login.submitCode("claude",view.id(),"test-code#test-state");
            await(login,"claude",v->v.status().equals("success"));
            assertEquals("synthetic-claude-token",new SettingsStore(dir.toString(),json).effective().claude().token());
        } finally { login.close(); }
    }
    @Test void cancellationMissingClientAndUnsafeUrlsAreHandled(@TempDir Path dir) throws Exception {
        var store=new SettingsStore(dir.toString(),json);var login=login(dir,store);
        try {
            var start=login.start("claude");await(login,"claude",v->v.status().equals("waiting"));
            assertEquals("cancelled",login.cancel("claude",start.id()).status());
            assertNull(login.status("claude").authUrl());
            assertThrows(IllegalArgumentException.class,()->login.start("other"));
        } finally { login.close(); }
        var absent=new BrowserLogin(dir.toString(),dir.resolve("missing").toString(),dir.resolve("missing").toString(),json,store,mock(UsageService.class));
        try { assertEquals("error",absent.start("codex").status()); } finally { absent.close(); }
        assertFalse(BrowserLogin.allowedUrl("claude","https://claude.ai.evil.test/oauth/authorize?a=1"));
        assertTrue(BrowserLogin.allowedUrl("claude","https://claude.com/cai/oauth/authorize?code=true&client_id=fixture"));
        assertFalse(BrowserLogin.allowedUrl("claude","https://claude.com/oauth/authorize?a=1"));
        assertFalse(BrowserLogin.allowedUrl("claude","https://claude.com.evil.test/cai/oauth/authorize?a=1"));
        assertFalse(BrowserLogin.allowedUrl("codex","http://auth.openai.com/codex/device"));
        assertFalse(BrowserLogin.allowedUrl("codex","https://auth.openai.com@evil.test/codex/device"));
    }
}
