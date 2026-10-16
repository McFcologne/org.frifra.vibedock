package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataSourceTest {
    final ObjectMapper json=new ObjectMapper();

    @Test void earlierSettingsBecomeTheTwoDefaultSources(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"),"""
            {"allowedIps":["10.0.0.0/8"],"codex":{"token":"manual-codex","accountId":"ws"},"claude":{"token":"","accountId":""},"devices":[]}
            """);
        var store=new SettingsStore(dir.toString(),json);
        assertEquals(List.of("codex","claude"),store.get().accounts().stream().map(SettingsStore.Account::id).toList());
        assertEquals("manual-codex",store.get().codex().token());assertEquals("ws",store.get().codex().accountId());
        store.save(store.get());
        assertTrue(json.readTree(dir.resolve("settings.json").toFile()).has("accounts"));
        assertEquals(store.get(),new SettingsStore(dir.toString(),json).get());
    }
    @Test void everySourceSignsInIntoItsOwnFolderAndIsPolledWithItsType(@TempDir Path dir) throws Exception {
        var store=new SettingsStore(dir.toString(),json);
        var business=new SettingsStore.Account("claude-business","claude","Claude business",null);
        var accounts=new java.util.ArrayList<>(store.get().accounts());accounts.add(business);store.save(store.get().withAccounts(accounts));
        assertEquals(dir.resolve("auth/claude"),SettingsStore.authDirectory(dir,store.get().account("claude").orElseThrow()));
        Path own=SettingsStore.authDirectory(dir,business);assertEquals(dir.resolve("auth/accounts/claude-business"),own);
        Files.createDirectories(own);Files.writeString(own.resolve(".credentials.json"),"{\"claudeAiOauth\":{\"accessToken\":\"business-token\"}}");
        assertEquals("business-token",store.effective().account("claude-business").orElseThrow().manual().token());
        assertTrue(store.effective().claude().token().isEmpty());
        var client=mock(ProviderClient.class);
        when(client.fetch(eq("claude"),any())).thenReturn(new ProviderClient.Reading(new ProviderClient.Window(40,1900000000),null));
        var usage=new UsageService(store,client,mock(DeviceSender.class),Clock.fixed(Instant.ofEpochSecond(1800000000),ZoneOffset.UTC));
        usage.refresh();
        verify(client).fetch("claude",new SettingsStore.Credential("business-token",""));
        var snapshot=usage.snapshot();
        assertEquals(List.of("codex","claude","claude-business"),List.copyOf(snapshot.providers().keySet()));
        assertEquals("Claude business",snapshot.label("claude-business"));assertEquals("partial",snapshot.providers().get("claude-business").status());
        assertEquals("unconfigured",snapshot.providers().get("claude").status());
    }
    @Test void sourcesAreValidatedAndGetRandomIds(@TempDir Path dir) throws Exception {
        var store=new SettingsStore(dir.toString(),json);
        var created=SettingsStore.Account.create("claude","Claude business");
        assertTrue(SettingsStore.Account.randomId(created.id()));assertEquals("accounts/"+created.id(),created.folder());
        assertThrows(IllegalArgumentException.class,()->new SettingsStore.Account("gemini","antigravity","Gemini",null));
        assertThrows(IllegalArgumentException.class,()->new SettingsStore.Account("x","claude","",null));
        var twice=new java.util.ArrayList<>(store.get().accounts());twice.add(store.get().accounts().get(0));
        assertThrows(IllegalArgumentException.class,()->store.save(store.get().withAccounts(twice)));
    }
    @Test void cursorReportsTheMembersShareOfTheMonth() throws Exception {
        var root=json.readTree("""
            {"subscriptionCycleStart":1790812800000,"teamMemberSpend":[
             {"email":"other@example.com","spendCents":100,"totalPercentUsed":90},
             {"email":"Me@Example.com","includedSpendCents":1500,"spendCents":500,"hardLimitOverrideDollars":40}]}
            """);
        var r=ProviderClient.parseCursor(root,"me@example.com");
        assertNull(r.fiveHour());assertEquals(50,r.week().usedPercent(),0.001);
        assertEquals(ZonedDateTime.ofInstant(Instant.ofEpochMilli(1790812800000L),ZoneOffset.UTC).plusMonths(1).toEpochSecond(),r.week().resetsAt());
        assertEquals(90,ProviderClient.parseCursor(root,"other@example.com").week().usedPercent());
        var unknown=assertThrows(ProviderClient.Failure.class,()->ProviderClient.parseCursor(root,"nobody@example.com"));
        assertEquals("unavailable",unknown.status);
        var noLimit=json.readTree("{\"subscriptionCycleStart\":1790812800000,\"teamMemberSpend\":[{\"email\":\"a@b.c\",\"spendCents\":1234}]}");
        assertTrue(assertThrows(ProviderClient.Failure.class,()->ProviderClient.parseCursor(noLimit,"a@b.c")).detail.contains("$12.34"));
    }
    @Test void cursorUsesItsKeyAndShowsOneMonthlyWindow(@TempDir Path dir) throws Exception {
        var store=new SettingsStore(dir.toString(),json);
        var cursor=new SettingsStore.Account("cursor","cursor","Cursor",new SettingsStore.Credential("key_test","me@example.com"));
        var accounts=new java.util.ArrayList<>(store.get().accounts());accounts.add(cursor);store.save(store.get().withAccounts(accounts));
        var client=mock(ProviderClient.class);
        when(client.fetch(eq("cursor"),any())).thenReturn(new ProviderClient.Reading(null,new ProviderClient.Window(25,1900000000)));
        var usage=new UsageService(store,client,mock(DeviceSender.class),Clock.fixed(Instant.ofEpochSecond(1800000000),ZoneOffset.UTC));
        usage.refresh();
        verify(client).fetch("cursor",new SettingsStore.Credential("key_test","me@example.com"));
        var p=usage.snapshot().providers().get("cursor");
        assertEquals("ok",p.status());assertEquals("Month",p.period());
        var login=new BrowserLogin(dir.toString(),"codex","claude",json,store,usage);
        assertThrows(IllegalArgumentException.class,()->login.start("cursor"));
        var image=new StatusImage("Europe/Berlin").render(usage.snapshot(),"x",new StatusImage.Look(null,Scheme.MIDNIGHT,null,null,
                new Layout(List.of(new Layout.Usage(List.of("cursor")),new Layout.Usage(List.of("claude")),new Layout.Context(false)))));
        assertEquals(115200,image.rgb565().length);
    }
    @Test void earlierIdsBecomeRandomAndDesignsFollow(@TempDir Path dir) throws Exception {
        var store=new SettingsStore(dir.toString(),json);
        var accounts=new java.util.ArrayList<>(store.get().accounts());accounts.add(new SettingsStore.Account("privat","codex","Codex privat",null));
        store.save(store.get().withAccounts(accounts));
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        registry.configureDesign(new Design(Scheme.MIDNIGHT,"",null,new Layout(List.of(new Layout.Usage(List.of("codex","privat")),
                new Layout.Usage(List.of("claude")),new Layout.Context(false)))));
        new SourceIdMigration(store,registry).migrate();
        var ids=store.get().accounts().stream().map(SettingsStore.Account::id).toList();
        assertTrue(ids.stream().allMatch(SettingsStore.Account::randomId));
        assertEquals(List.of("codex","claude","accounts/privat"),store.get().accounts().stream().map(SettingsStore.Account::folder).toList());
        var bands=registry.defaultDesign().layout().bands();
        assertEquals(new Layout.Usage(List.of(ids.get(0),ids.get(2))),bands.get(0));assertEquals(new Layout.Usage(List.of(ids.get(1))),bands.get(1));
        new SourceIdMigration(store,registry).migrate();
        assertEquals(ids,store.get().accounts().stream().map(SettingsStore.Account::id).toList());
        assertEquals(dir.resolve("auth/codex"),SettingsStore.authDirectory(dir,store.get().accounts().get(0)));
    }
    @Test void anInterruptedIdMigrationIsFinishedOnTheNextStart(@TempDir Path dir) throws Exception {
        var store=new SettingsStore(dir.toString(),json);
        var accounts=new java.util.ArrayList<>(store.get().accounts());accounts.add(new SettingsStore.Account("privat","codex","Codex privat",null));
        store.save(store.get().withAccounts(accounts));
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        registry.configureDesign(new Design(Scheme.MIDNIGHT,"",null,new Layout(List.of(new Layout.Usage(List.of("privat")),
                new Layout.Usage(List.of("claude")),new Layout.Context(false)))));
        var failing=new ClientRegistry(dir.toString(),json,Clock.systemUTC()){
            @Override public synchronized void renameSources(java.util.Map<String,String> renamed) throws java.io.IOException {throw new java.io.IOException("disk full");}
        };
        assertThrows(java.io.IOException.class,()->new SourceIdMigration(store,failing).migrate());
        assertTrue(store.get().accounts().stream().allMatch(a->SettingsStore.Account.randomId(a.id())));
        // Next start: new objects read the files again.
        var restarted=new SettingsStore(dir.toString(),json);var reloaded=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        new SourceIdMigration(restarted,reloaded).migrate();
        String privat=restarted.get().accounts().get(2).id();
        assertEquals(new Layout.Usage(List.of(privat)),reloaded.defaultDesign().layout().bands().get(0));
        assertTrue(restarted.pendingRenames().isEmpty());
    }
    @Test void grokReadsTheCreditPoolAndItsSignIn(@TempDir Path dir) throws Exception {
        var r=ProviderClient.parseGrok(json.readTree("""
            {"config":{"creditUsagePercent":37.5,"productUsage":[]},"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","start":"2026-10-12T00:00:00Z","end":"2026-10-19T00:00:00Z"}}
            """));
        assertNull(r.fiveHour());assertEquals(37.5,r.week().usedPercent());assertEquals(7L*86400,r.week().seconds());
        assertEquals(Instant.parse("2026-10-19T00:00:00Z").getEpochSecond(),r.week().resetsAt());
        assertEquals("Week",UsageService.period("grok",new UsageService.Provider("ok",1L,null,r.week())));
        var fresh=ProviderClient.parseGrok(json.readTree("{\"config\":{},\"billingPeriodStart\":1790812800000,\"billingPeriodEnd\":1793404800000}"));
        assertEquals(0,fresh.week().usedPercent());assertEquals("Month",UsageService.period("grok",new UsageService.Provider("ok",1L,null,fresh.week())));
        assertThrows(ProviderClient.Failure.class,()->ProviderClient.parseGrok(json.readTree("{}")));
        Files.writeString(dir.resolve("auth.json"),"""
            {"api":{"key":"xai-apikey"},"oidc":{"key":"grok-token","issuer":"https://auth.x.ai","expires_at":4102444800}}
            """);
        assertEquals("grok-token",LoginCredentials.grok(dir.resolve("auth.json"),json).token());
        Files.writeString(dir.resolve("auth.json"),"{\"oidc\":{\"key\":\"old\",\"issuer\":\"https://auth.x.ai\",\"expires_at\":1000}}");
        assertTrue(LoginCredentials.grok(dir.resolve("auth.json"),json).token().isEmpty());
        assertTrue(BrowserLogin.allowedUrl("grok","https://accounts.x.ai/oauth2/device?user_code=852D-ZZ5E"));
        assertFalse(BrowserLogin.allowedUrl("grok","https://evil.example/oauth2/device?user_code=852D-ZZ5E"));
    }
    @Test void geminiReadsTheDailyQuotaAndItsSignIn(@TempDir Path dir) throws Exception {
        long now=Instant.parse("2026-10-15T10:00:00Z").getEpochSecond();
        var r=ProviderClient.parseGemini(json.readTree("""
            {"buckets":[{"modelId":"gemini-2.5-flash","tokenType":"REQUESTS","remainingFraction":0.9,"resetTime":"2026-10-16T07:00:00Z"},
                        {"modelId":"gemini-2.5-pro","tokenType":"REQUESTS","remainingFraction":0.25,"resetTime":"2026-10-16T07:00:00Z"},
                        {"modelId":"other"}]}
            """),now);
        assertNull(r.fiveHour());assertEquals(75,r.week().usedPercent(),1e-9);assertEquals(86400,r.week().seconds());
        assertEquals(Instant.parse("2026-10-16T07:00:00Z").getEpochSecond(),r.week().resetsAt());
        assertEquals("Day",UsageService.period("gemini",new UsageService.Provider("ok",1L,null,r.week())));
        assertThrows(ProviderClient.Failure.class,()->ProviderClient.parseGemini(json.readTree("{\"buckets\":[]}"),now));
        assertThrows(java.io.IOException.class,()->ProviderClient.parseGemini(json.readTree("{\"buckets\":[{\"remainingFraction\":2}]}"),now));

        var account=new SettingsStore.Account(java.util.UUID.randomUUID().toString(),"gemini","Gemini",null);
        assertEquals(".gemini/oauth_creds.json",account.credentialFile());
        Path auth=SettingsStore.authDirectory(dir,account);Files.createDirectories(auth.resolve(".gemini"));
        Files.writeString(auth.resolve(".gemini/oauth_creds.json"),"{\"access_token\":\"ya29.synthetic\",\"refresh_token\":\"1//r\",\"expiry_date\":4102444800000}");
        assertEquals("ya29.synthetic",new LoginCredentials(dir,json).read(account,new SettingsStore.Credential("","")).token());
        Files.writeString(auth.resolve(".gemini/oauth_creds.json"),"{\"access_token\":\"old\",\"expiry_date\":1000}");
        assertTrue(new LoginCredentials(dir,json).read(account,new SettingsStore.Credential("","")).token().isEmpty());
        SignInRenewal.prepareGemini(auth);
        assertEquals("oauth-personal",json.readTree(auth.resolve(".gemini/settings.json").toFile()).path("security").path("auth").path("selectedType").asText());
        assertTrue(BrowserLogin.allowedUrl("gemini","https://accounts.google.com/o/oauth2/v2/auth?redirect_uri=https%3A%2F%2Fcodeassist.google.com%2Fauthcode&response_type=code"));
        assertFalse(BrowserLogin.allowedUrl("gemini","https://accounts.google.com/o/oauth2/v2/auth?redirect_uri=https%3A%2F%2Fevil.example%2Fcb"));
        assertFalse(BrowserLogin.allowedUrl("gemini","https://evil.example/o/oauth2/v2/auth?redirect_uri=https%3A%2F%2Fcodeassist.google.com%2Fauthcode"));
    }
    @Test void overviewShowsTheRemainingShares(){
        var both=new UsageService.Provider("ok",1L,new ProviderClient.Window(20,1),new ProviderClient.Window(26,1));
        assertEquals("5h 80 % · Week 74 % left",Controllers.figures(both));
        assertEquals("Month 100 % left",Controllers.figures(new UsageService.Provider("ok",1L,null,new ProviderClient.Window(0,1),"Month")));
        assertEquals("",Controllers.figures(new UsageService.Provider("waiting",null,null,null)));
    }
    @Test void reorderingSourcesKeepsTheirValuesWithoutAskingTheProviders(@TempDir Path dir) throws Exception {
        var store=new SettingsStore(dir.toString(),json);
        store.save(store.get().withAccounts(List.of(new SettingsStore.Account("work","codex","Work",new SettingsStore.Credential("work-token","")),
                new SettingsStore.Account("home","claude","Home",new SettingsStore.Credential("home-token","")))));
        var client=mock(ProviderClient.class);
        when(client.fetch(any(),any())).thenReturn(new ProviderClient.Reading(new ProviderClient.Window(40,1900000000),new ProviderClient.Window(10,1900000000)));
        var usage=new UsageService(store,client,mock(DeviceSender.class),Clock.fixed(Instant.ofEpochSecond(1800000000),ZoneOffset.UTC));
        usage.refresh();
        var before=usage.snapshot().providers();
        store.save(store.get().withAccounts(List.copyOf(store.get().accounts()).reversed()));
        usage.suspensionChanged();
        var after=usage.snapshot().providers();
        assertEquals(List.of("home","work"),List.copyOf(after.keySet()));
        for(String id:List.of("work","home")){
            assertEquals("ok",after.get(id).status());assertEquals(before.get(id).observedAt(),after.get(id).observedAt());
            assertEquals(before.get(id).fiveHour(),after.get(id).fiveHour());
        }
        verify(client,times(2)).fetch(any(),any());  // the first collection only
    }
    @Test void pausingASourceKeepsTheOthersValues(@TempDir Path dir) throws Exception {
        var store=new SettingsStore(dir.toString(),json);
        var accounts=List.of(new SettingsStore.Account("work","codex","Work",new SettingsStore.Credential("work-token","")),
                new SettingsStore.Account("home","claude","Home",new SettingsStore.Credential("home-token","")));
        store.save(store.get().withAccounts(accounts));
        var client=mock(ProviderClient.class);
        when(client.fetch(any(),any())).thenReturn(new ProviderClient.Reading(new ProviderClient.Window(40,1900000000),new ProviderClient.Window(10,1900000000)));
        var usage=new UsageService(store,client,mock(DeviceSender.class),Clock.fixed(Instant.ofEpochSecond(1800000000),ZoneOffset.UTC));
        usage.refresh();
        assertEquals("ok",usage.snapshot().providers().get("home").status());
        store.save(store.get().withAccounts(store.get().accounts().stream().map(a->a.id().equals("work")?a.withSuspended(true):a).toList()));
        usage.suspensionChanged();
        assertEquals("suspended",usage.snapshot().providers().get("work").status());
        assertEquals("ok",usage.snapshot().providers().get("home").status());
        usage.refresh();
        verify(client,times(1)).fetch(eq("codex"),any());
        store.save(store.get().withAccounts(store.get().accounts().stream().map(a->a.withSuspended(false)).toList()));
        usage.suspensionChanged();
        assertEquals("ok",usage.snapshot().providers().get("home").status());
        assertNotEquals("suspended",usage.snapshot().providers().get("work").status());
    }
}
