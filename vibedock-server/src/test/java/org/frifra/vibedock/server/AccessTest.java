package org.frifra.vibedock.server;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"vibedock.admin-password=test-password-123","vibedock.polling=false","vibedock.log-dir=target/test-logs"})
@AutoConfigureMockMvc
class AccessTest {
    static java.nio.file.Path licenseInventory;
    @DynamicPropertySource static void directory(DynamicPropertyRegistry r) throws Exception {
        var dir=Files.createTempDirectory("vibedock-test-");r.add("vibedock.data-dir",dir::toString);
        licenseInventory=dir.resolve("components.json");r.add("vibedock.license-inventory",licenseInventory::toString);
        var firmware=Files.createDirectories(dir.resolve("firmware"));
        Files.write(firmware.resolve("vibedock-firmware.bin"),new byte[]{(byte)0xe9,3,2,0x40});Files.writeString(firmware.resolve("version"),"0.2.0\n");
        Files.writeString(firmware.resolve("esptool.js"),"export class ESPLoader{}");
        var cyd=Files.createDirectories(firmware.resolve("esp32-cyd"));
        Files.write(cyd.resolve("flash.bin"),new byte[]{(byte)0xff,1});Files.write(cyd.resolve("firmware.bin"),new byte[]{(byte)0xe9,7,7,7,7});
        r.add("vibedock.firmware-dir",firmware::toString);
        var manual=Files.writeString(dir.resolve("manual.pdf"),"%PDF-1.7 test");r.add("vibedock.manual-file",manual::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired SettingsStore store;
    @Autowired UsageService usage;
    @Autowired ClientRegistry clients;
    @Autowired OtaSigning otaSigning;
    @Autowired DeviceLogs deviceLogs;
    @MockitoBean ProviderClient providers;
    @MockitoBean BrowserLogin login;
    @BeforeEach void resetSettings() throws Exception {
        store.save(new SettingsStore.Settings(List.of("127.0.0.1/32"),new SettingsStore.Credential("",""),new SettingsStore.Credential("",""),List.of()));
        usage.configurationChanged();
    }
    @Test void footerAndLicensePageUseGeneratedBuildInformation() throws Exception {
        mvc.perform(get("/admin").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Licenses &amp; notices")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("https://github.com/McFcologne/org.frifra.vibedock#readme")));
        mvc.perform(get("/admin/licenses").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("spring-boot")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Apache License 2.0")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("junit-jupiter"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("License report unavailable"))));
        mvc.perform(get("/admin/licenses").header("Accept","application/json").with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/licenses").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(8080);return r;}))
                .andExpect(status().isNotFound());
    }
    @Test void footerLinksTheManualOnlyForSignedInAdministrators() throws Exception {
        mvc.perform(get("/admin").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/manual.pdf")));
        mvc.perform(get("/admin/manual.pdf").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("inline;")))
                .andExpect(content().string("%PDF-1.7 test"));
        mvc.perform(get("/admin/manual.pdf").header("Accept","application/json").with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/manual.pdf").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10002);return r;}))
                .andExpect(status().isNotFound());
    }
    @Test void generatedContainerNoticesAreEscapedAndAvailableAsPlainText() throws Exception {
        Files.writeString(licenseInventory,"""
                [{"name":"Example <script>","version":"1.2.3","license":"MIT","text":"Bundled license text"}]
                """);
        try{
            mvc.perform(get("/admin/licenses").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                    .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Example &lt;script&gt;")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("1.2.3")));
            mvc.perform(get("/admin/licenses/components/0.txt").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                    .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/plain"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Bundled license text")));
            mvc.perform(get("/admin/licenses/components/1.txt").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/admin/licenses/components/0.txt").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        }finally{Files.deleteIfExists(licenseInventory);}
    }
    @Test void apiIsAnonymousReadOnlyAndNeverExposesTokens() throws Exception {
        mvc.perform(get("/api/v1/usage").with(r->{r.setLocalPort(8080);return r;}))
                .andExpect(status().isOk()).andExpect(jsonPath("$.providers.codex.status").value("unconfigured"))
                .andExpect(jsonPath("$.providers.codex.token").doesNotExist());
        mvc.perform(post("/api/v1/usage").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isMethodNotAllowed());
    }
    @Test void apiAnswersMatchThePublishedSchemaAlsoWithAPausedSource() throws Exception {
        store.save(store.get().withAccounts(List.of(
                new SettingsStore.Account("codex","codex","Codex",new SettingsStore.Credential("codex-token","")),
                new SettingsStore.Account("claude","claude","Claude",new SettingsStore.Credential("claude-token","")).withSuspended(true))));
        when(providers.fetch(any(),any())).thenReturn(new ProviderClient.Reading(new ProviderClient.Window(40,1900000000),new ProviderClient.Window(10,1900000000)));
        usage.configurationChanged();usage.refresh();
        String body=mvc.perform(get("/api/v1/usage").with(r->{r.setLocalPort(8080);return r;}))
                .andExpect(status().isOk()).andExpect(jsonPath("$.providers.claude.status").value("suspended"))
                .andExpect(jsonPath("$.providers.codex.status").value("ok")).andReturn().getResponse().getContentAsString();
        var schema=com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(java.nio.file.Path.of("usage.schema.json")));
        Assertions.assertEquals(java.util.Set.of(),schema.validate(new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)));
    }
    @Test void spoofedForwardedHeaderDoesNotBypassIpAllowlist() throws Exception {
        mvc.perform(get("/api/v1/usage").header("X-Forwarded-For","127.0.0.1")
                .with(r->{r.setLocalPort(8080);r.setRemoteAddr("203.0.113.50");return r;})).andExpect(status().isForbidden());
        store.save(new SettingsStore.Settings(List.of(),store.get().codex(),store.get().claude(),List.of()));
        mvc.perform(get("/api/v1/usage").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isForbidden());
    }
    @Test void loginPageAndAssetsAreOnlyOnTheAdminPort() throws Exception {
        mvc.perform(get("/login").with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in")));
        mvc.perform(get("/app.css").with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk());
        mvc.perform(get("/favicon.ico").with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk());
        mvc.perform(get("/login").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        mvc.perform(get("/app.css").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
    }
    @Test void adminNeedsAuthenticationAndItsDedicatedPort() throws Exception {
        mvc.perform(get("/admin").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(8080);return r;}))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin").header("Accept","application/json").with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin").with(httpBasic("admin","test-password-123"))
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk());
        mvc.perform(get("/api/v1/usage").with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isNotFound());
    }
    @Test void signOutWorksByLinkAndWithAnExpiredForm() throws Exception {
        // Opening /logout and a form whose CSRF token expired (server restart) sign out instead of showing a 403.
        mvc.perform(get("/logout").header("Accept","text/html").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(redirectedUrl("/login?logout"));
        mvc.perform(post("/logout").header("Accept","text/html").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(redirectedUrl("/login?logout"));
    }
    @Test void loginShowsWhyAPasswordChangeSignedYouOut() throws Exception {
        mvc.perform(get("/login").param("expired","").flashAttr("passwordMessage","Password changed. Sign in with the new password.")
                .with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Password changed. Sign in with the new password.")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Your session has expired"))));
        mvc.perform(get("/login").param("expired","").with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Your session has expired")));
    }
    @Test void ownPasswordReplacesTheConfiguredOneUntilReset() throws Exception {
        var admin=user("admin").roles("ADMIN");
        try{
            mvc.perform(post("/admin/password").param("current","wrong").param("password","new-password-1").param("repeat","new-password-1")
                    .with(admin).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                    .andExpect(redirectedUrl("/admin/server#sign-in")).andExpect(flash().attribute("passwordError","The current password is wrong."));
            mvc.perform(post("/admin/password").param("current","test-password-123").param("password","new-password-1").param("repeat","new-password-1")
                    .with(admin).with(csrf()).with(r->{r.setLocalPort(10001);return r;})).andExpect(flash().attributeExists("passwordMessage"));
            Assertions.assertTrue(store.adminPasswordHash().isPresent());
            mvc.perform(post("/login").param("username","admin").param("password","test-password-123").with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                    .andExpect(redirectedUrl("/login?error"));
            mvc.perform(post("/login").param("username","admin").param("password","new-password-1").with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                    .andExpect(redirectedUrl("/admin"));
            mvc.perform(post("/admin/password").param("current","new-password-1").param("reset","1")
                    .with(admin).with(csrf()).with(r->{r.setLocalPort(10001);return r;})).andExpect(flash().attributeExists("passwordMessage"));
            mvc.perform(post("/login").param("username","admin").param("password","test-password-123").with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                    .andExpect(redirectedUrl("/admin"));
        }finally{store.saveAdminPasswordHash(null);}
    }
    @Test void errorsShowTheOwnPageWithoutDetails() throws Exception {
        mvc.perform(get("/error").requestAttr("jakarta.servlet.error.status_code",403).header("Accept","text/html")
                .with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Back to the administration")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Whitelabel"))));
    }
    @Test void everyAdministrationPageRendersAndSavesOnlyItsOwnSettings() throws Exception {
        for(String page:List.of("/admin","/admin/devices","/admin/design","/admin/sources","/admin/server"))
            mvc.perform(get(page).with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                    .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("aria-current=\"page\"")));
        store.save(new SettingsStore.Settings(List.of("10.0.0.0/8"),new SettingsStore.Credential("codex-secret","acct"),new SettingsStore.Credential("",""),List.of()));
        mvc.perform(post("/admin/settings").param("page","sources").param("codexToken","").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(redirectedUrl("/admin/sources?saved"));
        Assertions.assertEquals(List.of("10.0.0.0/8"),store.get().allowedIps());Assertions.assertEquals("codex-secret",store.get().codex().token());
        Assertions.assertEquals("acct",store.get().codex().accountId());
        mvc.perform(post("/admin/settings").param("page","server").param("allowedIps","192.168.0.0/16").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(redirectedUrl("/admin/server?saved"));
        Assertions.assertEquals(List.of("192.168.0.0/16"),store.get().allowedIps());Assertions.assertEquals("codex-secret",store.get().codex().token());
    }
    @Test void removalErrorsDoNotOpenPairingButPairingErrorsDo() throws Exception {
        var removed=mvc.perform(post("/admin/clients/vibedock-missing/remove").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(redirectedUrl("/admin/devices"))
                .andExpect(flash().attributeExists("deviceRemovalError")).andExpect(flash().attributeCount(1)).andReturn();
        mvc.perform(get("/admin/devices").flashAttrs(removed.getFlashMap()).with(user("admin").roles("ADMIN"))
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk())
                .andExpect(result -> assertCreatePanel(result,"invite",false))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("The device was already removed.")));
        var paired=mvc.perform(post("/admin/clients/invite").param("ip","not-an-ip").param("code","123456")
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(redirectedUrl("/admin/devices")).andExpect(flash().attributeExists("pairingError"))
                .andExpect(flash().attributeCount(1)).andReturn();
        mvc.perform(get("/admin/devices").flashAttrs(paired.getFlashMap()).with(user("admin").roles("ADMIN"))
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk())
                .andExpect(result -> assertCreatePanel(result,"invite",true));
    }
    @Test void sourcesAreExportedAndImportedAfterReview() throws Exception {
        mvc.perform(post("/admin/sources/export").param("password","long enough pw").param("confirm","long enough pw").with(csrf())
                .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        var mismatch=mvc.perform(post("/admin/sources/export").param("password","long enough pw").param("confirm","other password")
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isSeeOther()).andExpect(flash().attribute("exportError","The passwords do not match")).andReturn();
        mvc.perform(get("/admin/sources").flashAttrs(mismatch.getFlashMap()).with(user("admin").roles("ADMIN"))
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(result -> assertCreatePanel(result,"export-sources",true));
        String file=mvc.perform(post("/admin/sources/export").param("password","long enough pw").param("confirm","long enough pw")
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment")))
                .andReturn().getResponse().getContentAsString();
        var session=new org.springframework.mock.web.MockHttpSession();
        mvc.perform(post("/admin/sources/import").param("content",file).param("password","wrong password").session(session)
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(redirectedUrl("/admin/sources")).andExpect(flash().attribute("importError","Wrong password or damaged file"));
        mvc.perform(post("/admin/sources/import").param("content",file).param("password","long enough pw").session(session)
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(redirectedUrl("/admin/sources#import-review"));
        mvc.perform(get("/admin/sources").session(session).with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Replace all current sources")));
        var ids=store.get().accounts().stream().map(SettingsStore.Account::id).toList();
        mvc.perform(post("/admin/sources/import/apply").param("mode","add").param("source",ids.get(0)).session(session)
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(redirectedUrl("/admin/sources")).andExpect(flash().attribute("importMessage","Imported 1 data source."));
        Assertions.assertEquals(ids.size()+1,store.get().accounts().size());
        mvc.perform(post("/admin/sources/import/apply").param("mode","add").param("source",ids.get(0)).session(session)
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(flash().attribute("importError","The import expired. Upload the file again."));
    }
    @Test void onlySourceCreationErrorsOpenTheAddSourcePanel() throws Exception {
        var added=mvc.perform(post("/admin/sources/add").param("type","missing").param("label","Test")
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(redirectedUrl("/admin/sources")).andExpect(flash().attributeExists("sourceCreateError"))
                .andExpect(flash().attributeCount(1)).andReturn();
        mvc.perform(get("/admin/sources").flashAttrs(added.getFlashMap()).with(user("admin").roles("ADMIN"))
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk())
                .andExpect(result -> assertCreatePanel(result,"add-source",true));
        String id=store.get().accounts().getFirst().id();
        var saved=mvc.perform(post("/admin/settings").param("page","sources").param("label_"+id,"")
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(redirectedUrl("/admin/sources")).andExpect(flash().attributeExists("error"))
                .andExpect(flash().attributeCount(1)).andReturn();
        mvc.perform(get("/admin/sources").flashAttrs(saved.getFlashMap()).with(user("admin").roles("ADMIN"))
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk())
                .andExpect(result -> assertCreatePanel(result,"add-source",false));
    }
    private static void assertCreatePanel(org.springframework.test.web.servlet.MvcResult result,String id,boolean open) throws Exception {
        String html=result.getResponse().getContentAsString();
        int start=html.indexOf("id=\""+id+"\"");
        Assertions.assertTrue(start>=0,"Creation panel exists");
        String panel=html.substring(start,html.indexOf("</section>",start));
        Assertions.assertTrue(panel.contains("data-create-error=\""+open+"\""));
        Assertions.assertEquals(open,panel.contains("role=\"alert\""),"Only a creation error belongs inside the panel");
    }
    @Test void dataSourcesCanBeAddedRenamedAndRemoved() throws Exception {
        mvc.perform(post("/admin/sources/add").param("type","claude").param("label","Claude business").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(redirectedUrl("/admin/sources?saved"));
        String id=store.get().accounts().stream().filter(x->x.label().equals("Claude business")).findFirst().orElseThrow().id();
        Assertions.assertTrue(SettingsStore.Account.randomId(id));
        mvc.perform(get("/admin/sources").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-provider=\""+id+"\"")));
        mvc.perform(post("/admin/settings").param("page","sources").param("label_"+id,"Work").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(redirectedUrl("/admin/sources?saved"));
        Assertions.assertEquals("Work",store.get().account(id).orElseThrow().label());
        Assertions.assertEquals("Work",usage.snapshot().label(id));
        mvc.perform(post("/admin/sources/"+id+"/rename").param("name","Business").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(jsonPath("$.name").value("Business"));
        Assertions.assertEquals("Business",usage.snapshot().label(id));
        mvc.perform(post("/admin/sources/"+id+"/rename").param("name"," ").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isBadRequest());
        // The remove-token checkbox the page renders must clear the stored token when submitted.
        mvc.perform(post("/admin/settings").param("page","sources").param("token_"+id,"synthetic-manual-token").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(redirectedUrl("/admin/sources?saved"));
        Assertions.assertEquals("synthetic-manual-token",store.get().account(id).orElseThrow().manual().token());
        String page=mvc.perform(get("/admin/sources").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andReturn().getResponse().getContentAsString();
        var clear=java.util.regex.Pattern.compile("type=\"checkbox\" name=\"([^\"]*"+java.util.regex.Pattern.quote(id)+"[^\"]*)\"").matcher(page);
        Assertions.assertTrue(clear.find(),"remove-token checkbox rendered");
        mvc.perform(post("/admin/settings").param("page","sources").param(clear.group(1),"on").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(redirectedUrl("/admin/sources?saved"));
        Assertions.assertEquals("",store.get().account(id).orElseThrow().manual().token());
        // Pausing keeps the source with its settings, stops polling it and survives saving the page.
        mvc.perform(post("/admin/sources/"+id+"/suspend").param("suspend","true").with(user("admin").roles("ADMIN"))
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isForbidden());
        mvc.perform(post("/admin/sources/"+id+"/suspend").param("suspend","true").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(redirectedUrl("/admin/sources#source-"+id));
        Assertions.assertTrue(store.get().account(id).orElseThrow().suspended());
        Assertions.assertEquals("suspended",usage.snapshot().providers().get(id).status());
        mvc.perform(post("/admin/settings").param("page","sources").param("label_"+id,"Work").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(redirectedUrl("/admin/sources?saved"));
        Assertions.assertTrue(store.get().account(id).orElseThrow().suspended());
        mvc.perform(get("/admin/sources").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Resume data source")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("source-tile tile-claude")));
        mvc.perform(get("/admin").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">Paused<")));
        mvc.perform(get("/admin/sources/state.json").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(jsonPath("$['"+id+"'].status").value("suspended")).andExpect(jsonPath("$['"+id+"'].badge").value("Paused"));
        mvc.perform(get("/admin/sources/state.json").header("Accept","application/json").with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/sources/"+id+"/suspend").param("suspend","false").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;}));
        Assertions.assertFalse(store.get().account(id).orElseThrow().suspended());
        mvc.perform(post("/admin/sources/"+id+"/remove").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(redirectedUrl("/admin/sources?saved"));
        Assertions.assertTrue(store.get().account(id).isEmpty());
        mvc.perform(post("/admin/sources/add").param("type","antigravity").param("label","Gemini").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(redirectedUrl("/admin/sources"));
    }
    @Test void configurationWritesNeedCsrfAndPersistRules() throws Exception {
        mvc.perform(post("/admin/settings").with(user("admin").roles("ADMIN"))
                .with(r->{r.setLocalPort(10001);return r;}).param("allowedIps","10.0.0.0/8")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/settings").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;}).param("allowedIps","10.0.0.0/8"))
                .andExpect(status().is3xxRedirection());
        Assertions.assertEquals(List.of("10.0.0.0/8"),store.get().allowedIps());
    }
    @Test void oneFailedProviderDoesNotHideTheOther() throws Exception {
        store.save(new SettingsStore.Settings(List.of("127.0.0.1"),new SettingsStore.Credential("codex-secret",""),
                new SettingsStore.Credential("claude-secret",""),List.of()));
        when(providers.fetch(eq("codex"),any())).thenReturn(new ProviderClient.Reading(new ProviderClient.Window(12,1900000000),null));
        when(providers.fetch(eq("claude"),any())).thenThrow(new ProviderClient.Failure("authentication_required"));
        usage.refresh();
        mvc.perform(get("/api/v1/usage").with(r->{r.setLocalPort(8080);return r;}))
                .andExpect(status().isOk()).andExpect(jsonPath("$.providers.codex.fiveHour.usedPercent").value(12.0))
                .andExpect(jsonPath("$.providers.claude.status").value("authentication_required"))
                .andExpect(jsonPath("$.providers.claude.fiveHour").isEmpty());
    }
    @Test void rateLimitCooldownAlsoAppliesToManualRefresh() throws Exception {
        store.save(new SettingsStore.Settings(List.of(),new SettingsStore.Credential("test-token",""),new SettingsStore.Credential("",""),List.of()));
        when(providers.fetch(eq("codex"),any())).thenThrow(new ProviderClient.Failure("rate_limited",java.time.Instant.now().getEpochSecond()+300));
        usage.refresh();usage.refresh();
        verify(providers,times(1)).fetch(eq("codex"),any());
        Assertions.assertEquals("rate_limited",usage.snapshot().providers().get("codex").status());
    }
    @Test void loginButtonsRequireAdminPortAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/admin/login/codex").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        mvc.perform(get("/admin/login/codex").header("Accept","application/json")
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/login/codex/start").with(user("admin").roles("ADMIN"))
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isForbidden());
        when(login.start("codex")).thenReturn(new BrowserLogin.View("test-id","starting",null,null,false,"Start"));
        mvc.perform(post("/admin/login/codex/start").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("test-id"));
        verify(login,times(1)).start("codex");
        mvc.perform(get("/admin/sources").with(user("admin").roles("ADMIN"))
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in with Codex")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in with Claude")));
    }
    @Test void pairedDevicesPullWithTheirKeyAndRemovalIsProtected() throws Exception {
        String key="ab".repeat(32);
        clients.paired("vibedock-access-test","Test",key,"192.168.1.10",80);
        String path="/api/v1/clients/vibedock-access-test/image";
        var pull=mvc.perform(get(path).header("Authorization","Bearer "+key)
                .with(r->{r.setLocalPort(8080);r.setRemoteAddr("192.168.1.20");return r;})).andExpect(status().isOk())
                .andExpect(header().string("X-VibeDock-Image-Format","rgb565-be-240x240"))
                .andExpect(header().string("X-VibeDock-Poll-Seconds","30")).andReturn();
        Assertions.assertEquals(115200,pull.getResponse().getContentAsByteArray().length);
        String etag=pull.getResponse().getHeader("ETag");Assertions.assertNotNull(etag);
        mvc.perform(get(path).header("Authorization","Bearer "+key).header("If-None-Match",etag)
                .with(r->{r.setLocalPort(8080);r.setRemoteAddr("192.168.1.20");return r;})).andExpect(status().isNotModified());
        mvc.perform(post("/admin/clients/vibedock-access-test/refresh").with(user("admin").roles("ADMIN"))
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isForbidden());
        mvc.perform(post("/admin/clients/vibedock-access-test/refresh").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        mvc.perform(post("/admin/clients/vibedock-access-test/refresh").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(redirectedUrl("/admin/devices#device-vibedock-access-test"));
        mvc.perform(get(path).header("Authorization","Bearer "+key).header("X-VibeDock-Preview","1")
                .with(r->{r.setLocalPort(8080);r.setRemoteAddr("192.168.1.20");return r;})).andExpect(status().isOk()).andExpect(header().doesNotExist("X-VibeDock-Redraw"));
        mvc.perform(get(path).header("Authorization","Bearer "+key).header("If-None-Match",etag)
                .with(r->{r.setLocalPort(8080);r.setRemoteAddr("192.168.1.20");return r;})).andExpect(status().isOk())
                .andExpect(header().string("X-VibeDock-Redraw","1")).andExpect(header().doesNotExist("ETag"));
        mvc.perform(get(path).header("Authorization","Bearer "+key).header("If-None-Match",etag)
                .with(r->{r.setLocalPort(8080);r.setRemoteAddr("192.168.1.20");return r;})).andExpect(status().isNotModified());
        Assertions.assertEquals("192.168.1.20",clients.get("vibedock-access-test").ip());
        mvc.perform(get(path).header("Authorization","Bearer "+key).header("X-VibeDock-Device-IP","192.168.1.170")
                .with(r->{r.setLocalPort(8080);r.setRemoteAddr("192.168.65.1");return r;})).andExpect(status().isOk());
        Assertions.assertEquals("192.168.1.170",clients.get("vibedock-access-test").ip());
        mvc.perform(get(path).header("Authorization","Bearer "+key).header("X-VibeDock-Device-IP","not-an-ip")
                .with(r->{r.setLocalPort(8080);r.setRemoteAddr("192.168.1.21");return r;})).andExpect(status().isOk());
        Assertions.assertEquals("192.168.1.21",clients.get("vibedock-access-test").ip());
        mvc.perform(get(path).header("Authorization","Bearer "+"cd".repeat(32)).with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/clients/vibedock-unknown-1/image").header("Authorization","Bearer "+key).with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization","Bearer "+key).with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isMethodNotAllowed());
        mvc.perform(get(path).header("Authorization","Bearer "+key).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/clients/register").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        mvc.perform(get("/admin/clients/vibedock-access-test/preview.png").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        mvc.perform(get("/admin/preview.png").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        mvc.perform(get("/admin/preview.png").header("Accept","application/json").with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/preview.png").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(post("/admin/clients/vibedock-access-test/remove").with(user("admin").roles("ADMIN"))
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isForbidden());
        mvc.perform(post("/admin/clients/vibedock-access-test/remove").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().is3xxRedirection());
        mvc.perform(get(path).header("Authorization","Bearer "+key).with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isUnauthorized());
    }
    @Test void usbLogsAreSavedShownDownloadedAndRemovedWithTheDevice() throws Exception {
        String id="vibedock-log-access",body="{\"firmware\":\"0.1.1\",\"board\":\"vibedock-m1\",\"lines\":[\"Start\",\"Wi-Fi Home: 192.168.1.20\"]}";
        var admin=user("admin").roles("ADMIN");
        clients.paired(id,"Log desk","d".repeat(64),"192.168.1.20",80);
        mvc.perform(post("/admin/clients/"+id+"/logs").contentType("application/json").content(body).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/admin/clients/"+id+"/logs").contentType("application/json").content(body).with(admin)
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isForbidden());
        mvc.perform(post("/admin/clients/"+id+"/logs").contentType("application/json").content(body).with(admin).with(csrf())
                .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        var saved=mvc.perform(post("/admin/clients/"+id+"/logs").contentType("application/json").content(body).with(admin).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1)).andReturn();
        String name=new com.fasterxml.jackson.databind.ObjectMapper().readTree(saved.getResponse().getContentAsString()).path("name").asText().replace(".json","");
        mvc.perform(post("/admin/clients/../logs").contentType("application/json").content(body).with(admin).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().is4xxClientError());
        mvc.perform(get("/admin/troubleshooting").with(admin).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Read log over USB")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1 of "+DeviceLogs.KEEP)));
        mvc.perform(get("/admin/clients/"+id+"/logs").with(admin).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Wi-Fi Home: 192.168.1.20")));
        mvc.perform(get("/admin/clients/"+id+"/logs/"+name+".txt").with(admin).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Firmware: 0.1.1")));
        mvc.perform(get("/admin/clients/"+id+"/logs/"+name+".txt").with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/clients/"+id+"/remove").with(admin).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/clients/"+id+"/logs/"+name+".txt").with(admin).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isNotFound());
    }
    @Test void displaysSendTheirLogOnceWhenAskedOverTheNetwork() throws Exception {
        String key="ef".repeat(32),id="vibedock-log-network",image="/api/v1/clients/"+id+"/image",upload="/api/v1/clients/"+id+"/log";
        var admin=user("admin").roles("ADMIN");
        clients.paired(id,"Shelf",key,"192.168.1.31",80);
        try{
            // Firmware 0.1.0 cannot send its log: no request, a hint instead.
            mvc.perform(get(image).header("Authorization","Bearer "+key).header("X-VibeDock-Firmware","0.1.0")
                    .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isOk()).andExpect(header().doesNotExist("X-VibeDock-Send-Log"));
            mvc.perform(post("/admin/clients/"+id+"/logs/request").with(admin).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                    .andExpect(flash().attribute("logMessage",org.hamcrest.Matchers.containsString("only over USB")));
            Assertions.assertTrue(deviceLogs.pendingRequest(id).isEmpty());
            // Newer firmware: the request reaches the display with its next image, also on a 304.
            var pull=mvc.perform(get(image).header("Authorization","Bearer "+key).header("X-VibeDock-Firmware","0.2.0")
                    .with(r->{r.setLocalPort(8080);return r;})).andReturn();
            mvc.perform(post("/admin/clients/"+id+"/logs/request").with(admin).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                    .andExpect(flash().attribute("logMessage",org.hamcrest.Matchers.startsWith("Requested")));
            String request=mvc.perform(get(image).header("Authorization","Bearer "+key).header("X-VibeDock-Firmware","0.2.0")
                    .header("If-None-Match",pull.getResponse().getHeader("ETag")).with(r->{r.setLocalPort(8080);return r;}))
                    .andExpect(status().isNotModified()).andReturn().getResponse().getHeader("X-VibeDock-Send-Log");
            Assertions.assertTrue(request!=null&&request.matches("[0-9a-f]{32}"),String.valueOf(request));
            String body="+5s Start\n+9s Wi-Fi Home: 192.168.1.31\nBearer "+key+"\n";
            // Wrong key, wrong request, admin port, GET and too large are refused; nothing is saved.
            mvc.perform(post(upload).header("Authorization","Bearer "+"0".repeat(64)).header("X-VibeDock-Log-Request",request).content(body)
                    .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isUnauthorized());
            mvc.perform(post(upload).header("Authorization","Bearer "+key).header("X-VibeDock-Log-Request","1".repeat(32)).content(body)
                    .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isConflict());
            mvc.perform(post(upload).header("Authorization","Bearer "+key).header("X-VibeDock-Log-Request",request).content(body)
                    .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isNotFound());
            mvc.perform(get(upload).header("Authorization","Bearer "+key).with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isMethodNotAllowed());
            mvc.perform(post(upload).header("Authorization","Bearer "+key).header("X-VibeDock-Log-Request",request).content("x".repeat(DeviceLogs.MAX_BYTES+1))
                    .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isPayloadTooLarge());
            Assertions.assertEquals(0,deviceLogs.count(id));
            // The right one is saved once, without the key; the request is used up.
            mvc.perform(post(upload).header("Authorization","Bearer "+key).header("X-VibeDock-Log-Request",request)
                    .header("X-VibeDock-Firmware","0.2.0").header("X-VibeDock-Board","vibedock-m1").content(body)
                    .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNoContent());
            mvc.perform(post(upload).header("Authorization","Bearer "+key).header("X-VibeDock-Log-Request",request).content(body)
                    .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isConflict());
            var saved=deviceLogs.list(id);
            Assertions.assertEquals(1,saved.size());Assertions.assertEquals("net",saved.getFirst().source());Assertions.assertEquals("0.2.0",saved.getFirst().firmware());
            var lines=deviceLogs.read(id,saved.getFirst().name()).orElseThrow().lines();
            Assertions.assertEquals(3,lines.size());Assertions.assertFalse(String.join("\n",lines).contains(key));
            mvc.perform(get(image).header("Authorization","Bearer "+key).header("X-VibeDock-Firmware","0.2.0").with(r->{r.setLocalPort(8080);return r;}))
                    .andExpect(header().doesNotExist("X-VibeDock-Send-Log"));
            mvc.perform(get("/admin/clients/"+id+"/logs").with(admin).with(r->{r.setLocalPort(10001);return r;}))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Read log over the network")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Network")));
        }finally{clients.remove(id);deviceLogs.deleteAll(id);}
    }
    @Test void touchDisplaysSwipeThroughTheColourSchemes() throws Exception {
        String key="cd".repeat(32),id="vibedock-touch",image="/api/v1/clients/"+id+"/image";
        clients.paired(id,"Desk",key,"192.168.1.32",80);
        try{
            var start=clients.defaultDesign().scheme();
            var first=mvc.perform(get(image).header("Authorization","Bearer "+key).with(r->{r.setLocalPort(8080);return r;}))
                    .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
            Assertions.assertFalse(clients.views().stream().filter(v->v.identifier().equals(id)).findFirst().orElseThrow().overridden());
            // Next: a new image right away, and the device keeps its own design from then on.
            mvc.perform(get(image).header("Authorization","Bearer "+key).header("X-VibeDock-Gesture","scheme-next").header("If-None-Match",first)
                    .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isOk());
            Assertions.assertEquals(start.shifted(1),clients.designFor(id).scheme());
            Assertions.assertTrue(clients.views().stream().filter(v->v.identifier().equals(id)).findFirst().orElseThrow().overridden());
            Assertions.assertEquals(start,clients.defaultDesign().scheme());
            // Previous goes back; the first scheme wraps around to the last; unknown gestures change nothing.
            mvc.perform(get(image).header("Authorization","Bearer "+key).header("X-VibeDock-Gesture","scheme-previous")
                    .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isOk());
            Assertions.assertEquals(start,clients.designFor(id).scheme());
            Assertions.assertEquals(Scheme.values()[Scheme.values().length-1],Scheme.values()[0].shifted(-1));
            mvc.perform(get(image).header("Authorization","Bearer "+key).header("X-VibeDock-Gesture","reboot")
                    .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isOk());
            Assertions.assertEquals(start,clients.designFor(id).scheme());
            // Without the key nothing changes.
            mvc.perform(get(image).header("Authorization","Bearer "+"0".repeat(64)).header("X-VibeDock-Gesture","scheme-next")
                    .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isUnauthorized());
            Assertions.assertEquals(start,clients.designFor(id).scheme());
        }finally{clients.remove(id);}
    }
    @Test void flipImageTurnsEveryBoardButTheVibeTvM1() throws Exception {
        String key="ef".repeat(32),id="vibedock-flip",image="/api/v1/clients/"+id+"/image",flip="/admin/clients/"+id+"/flip";
        clients.paired(id,"Shelf",key,"192.168.1.33",80,"waveshare-s3-touch-lcd-147");
        try{
            mvc.perform(get(image).header("Authorization","Bearer "+key).with(r->{r.setLocalPort(8080);return r;}))
                    .andExpect(status().isOk()).andExpect(header().string("X-VibeDock-Orientation","upright"));
            mvc.perform(post(flip).param("flipped","true").header("Accept","application/json").with(user("admin").roles("ADMIN")).with(csrf())
                    .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk()).andExpect(jsonPath("$.flipped").value(true));
            // Also the 304 carries it: the device turns without a new image.
            var tag=mvc.perform(get(image).header("Authorization","Bearer "+key).with(r->{r.setLocalPort(8080);return r;}))
                    .andExpect(header().string("X-VibeDock-Orientation","flipped")).andReturn().getResponse().getHeader("ETag");
            mvc.perform(get(image).header("Authorization","Bearer "+key).header("If-None-Match",tag).with(r->{r.setLocalPort(8080);return r;}))
                    .andExpect(status().isNotModified()).andExpect(header().string("X-VibeDock-Orientation","flipped"));
            // The VibeTV M1 stands as its case decides: no switch, no header.
            clients.setBoard(id,"vibedock-m1");
            Assertions.assertFalse(clients.flipped(id));
            mvc.perform(get(image).header("Authorization","Bearer "+key).with(r->{r.setLocalPort(8080);return r;}))
                    .andExpect(header().doesNotExist("X-VibeDock-Orientation"));
            clients.setFlipped(id,true);Assertions.assertFalse(clients.get(id).flipped());
        }finally{clients.remove(id);}
    }
    @Test void firmwareIsOfferedForDownloadAndDevicesReportTheirVersion() throws Exception {
        String key="ab".repeat(32);
        for(var client:clients.views())clients.remove(client.identifier());
        clients.paired("vibedock-firmware-test","Desk",key,"192.168.1.10",80);
        mvc.perform(get("/admin/firmware").header("Accept","application/json").with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/firmware").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        mvc.perform(get("/admin/firmware").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("vibedock-m1-0.2.0.bin")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-esptool=\"/admin/firmware/esptool.js\"")));
        mvc.perform(get("/admin/firmware/vibedock-m1-0.2.0.bin").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{(byte)0xe9,3,2,0x40}))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"vibedock-m1-0.2.0.bin\""));
        mvc.perform(get("/admin/firmware/vibedock-m1-0.1.0.bin").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isNotFound());
        mvc.perform(get("/admin/firmware/vibedock-m1-0.2.0.bin").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        mvc.perform(get("/admin/firmware/esptool.js").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/javascript"));
        // An older firmware sends no version: it is offered the update, as is a device with another version.
        String path="/api/v1/clients/vibedock-firmware-test/image";
        mvc.perform(get(path).header("Authorization","Bearer "+key).with(r->{r.setLocalPort(8080);r.setRemoteAddr("192.168.1.20");return r;})).andExpect(status().isOk());
        Assertions.assertNull(clients.get("vibedock-firmware-test").firmware());
        mvc.perform(get("/admin/devices").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Update 0.2.0")));
        mvc.perform(get(path).header("Authorization","Bearer "+key).header("X-VibeDock-Firmware","0.2.0").with(r->{r.setLocalPort(8080);r.setRemoteAddr("192.168.1.20");return r;})).andExpect(status().isOk());
        Assertions.assertEquals("0.2.0",clients.get("vibedock-firmware-test").firmware());
        mvc.perform(get("/admin/devices").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Update 0.2.0"))));
        // A device with a newer build than the server's gets no downgrade offered.
        mvc.perform(get(path).header("Authorization","Bearer "+key).header("X-VibeDock-Firmware","0.2.0-4-gabcdef0").with(r->{r.setLocalPort(8080);r.setRemoteAddr("192.168.1.20");return r;})).andExpect(status().isOk());
        mvc.perform(get("/admin/devices").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Update 0.2.0"))));
        mvc.perform(get("/admin/firmware").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">Newer</span>")));
        mvc.perform(get(path).header("Authorization","Bearer "+key).header("X-VibeDock-Firmware","<script>").with(r->{r.setLocalPort(8080);r.setRemoteAddr("192.168.1.20");return r;})).andExpect(status().isOk());
        Assertions.assertNull(clients.get("vibedock-firmware-test").firmware());
        // A device names its board: it gets the board's display type and that board's firmware on the firmware page.
        mvc.perform(get(path).header("Authorization","Bearer "+key).header("X-VibeDock-Board","esp32-cyd").with(r->{r.setLocalPort(8080);r.setRemoteAddr("192.168.1.20");return r;})).andExpect(status().isOk());
        Assertions.assertEquals("esp32-cyd",clients.get("vibedock-firmware-test").board().id());Assertions.assertEquals("320x240",clients.get("vibedock-firmware-test").display().id());
        mvc.perform(get(path).header("Authorization","Bearer "+key).header("X-VibeDock-Board","../etc").with(r->{r.setLocalPort(8080);r.setRemoteAddr("192.168.1.20");return r;})).andExpect(status().isOk());
        Assertions.assertEquals("esp32-cyd",clients.get("vibedock-firmware-test").board().id());
        mvc.perform(get("/admin/firmware").param("board","esp32-cyd").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("vibedock-esp32-cyd-0.2.0.bin")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-chip=\"ESP32\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("--chip esp32 ")));
        mvc.perform(get("/admin/firmware/vibedock-esp32-cyd-0.2.0.bin").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{(byte)0xff,1}));
        clients.remove("vibedock-firmware-test");
    }
    @Test void aDeviceFlashedInTheBrowserIsPairedOverUsb() throws Exception {
        for(var client:clients.views())clients.remove(client.identifier());
        String body="{\"identifier\":\"vibedock-a1b2c3d4e5f6\",\"name\":\"Desk\"}";
        mvc.perform(post("/admin/firmware/setup").contentType("application/json").content(body).with(user("admin").roles("ADMIN"))
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isForbidden());
        // Opened on localhost without an internal address: no address a device could reach.
        clients.configure("","",30);
        mvc.perform(post("/admin/firmware/setup").contentType("application/json").content(body).with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("internal address")));
        clients.configure("http://192.168.1.5:10002","",30);
        mvc.perform(post("/admin/firmware/setup").contentType("application/json").content("{\"identifier\":\"../etc\"}").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isBadRequest());
        var result=mvc.perform(post("/admin/firmware/setup").contentType("application/json").content(body).with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk())
                .andExpect(jsonPath("$.pairing.servers[0]").value("http://192.168.1.5:10002"))
                .andExpect(jsonPath("$.pairing.name").value("Desk")).andExpect(jsonPath("$.pairing.pollSeconds").value(30)).andReturn();
        String key=new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).path("pairing").path("key").asText();
        Assertions.assertTrue(key.matches("[0-9a-f]{64}"));
        mvc.perform(get("/admin/firmware/setup/vibedock-a1b2c3d4e5f6").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(jsonPath("$.status").value("Waiting for first pull")).andExpect(jsonPath("$.ip").value(""));
        mvc.perform(get("/api/v1/clients/vibedock-a1b2c3d4e5f6/image").header("Authorization","Bearer "+key)
                .header("X-VibeDock-Device-IP","192.168.1.44").header("X-VibeDock-Firmware","0.1.0")
                .with(r->{r.setLocalPort(8080);r.setRemoteAddr("172.17.0.1");return r;})).andExpect(status().isOk());
        Assertions.assertEquals("192.168.1.44",clients.address("vibedock-a1b2c3d4e5f6").ip(),"Use the device report, not the Docker gateway");
        mvc.perform(get("/admin/firmware/setup/vibedock-a1b2c3d4e5f6").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(jsonPath("$.status").value("Connected")).andExpect(jsonPath("$.ip").value("192.168.1.44"));
        mvc.perform(get("/api/v1/clients/vibedock-a1b2c3d4e5f6/image").header("Authorization","Bearer wrong")
                .header("X-VibeDock-Device-IP","192.168.1.44").header("X-VibeDock-Firmware","0.1.1").header("X-VibeDock-Subnet-Mask","255.255.0.0")
                .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/clients/vibedock-a1b2c3d4e5f6/image").header("Authorization","Bearer "+key)
                .header("X-VibeDock-Device-IP","192.168.1.44").header("X-VibeDock-Firmware","0.1.1").header("X-VibeDock-Subnet-Mask","255.255.254.0")
                .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isOk());
        mvc.perform(get("/api/v1/clients/vibedock-a1b2c3d4e5f6/image").header("Authorization","Bearer "+key)
                .header("X-VibeDock-Device-IP","192.168.0.99").header("X-VibeDock-Firmware","0.1.1").header("X-VibeDock-Subnet-Mask","255.255.0.0")
                .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isOk());
        Assertions.assertEquals("192.168.0.99",clients.address("vibedock-a1b2c3d4e5f6").ip());
        mvc.perform(get("/api/v1/clients/vibedock-a1b2c3d4e5f6/image").header("Authorization","Bearer "+key)
                .header("X-VibeDock-Device-IP","192.168.2.99").header("X-VibeDock-Firmware","0.1.1").header("X-VibeDock-Subnet-Mask","255.255.0.0")
                .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isOk());
        Assertions.assertEquals("192.168.1.44",clients.address("vibedock-a1b2c3d4e5f6").ip());
        mvc.perform(get("/admin/firmware/setup/vibedock-000000000000").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isNotFound());
        clients.remove("vibedock-a1b2c3d4e5f6");clients.configure("","",30);
    }
    @Test void anOwnBuildIsUploadedOnTheAdministrationPortOnly() throws Exception {
        byte[] app=new byte[4096];app[0]=(byte)0xE9;
        byte[] id="VIBEDOCK-FIRMWARE 0.9.9-test vibedock-m1".getBytes();System.arraycopy(id,0,app,100,id.length);
        mvc.perform(post("/admin/firmware/upload").param("board","vibedock-m1").contentType("application/octet-stream").content(app)
                .with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isForbidden());
        mvc.perform(post("/admin/firmware/upload").param("board","vibedock-m1").contentType("application/octet-stream").content(app)
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        mvc.perform(post("/admin/firmware/upload").param("board","esp32-cyd").contentType("application/octet-stream").content(app)
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("vibedock-m1")));
        mvc.perform(post("/admin/firmware/upload").param("board","vibedock-m1").contentType("application/octet-stream").content(app)
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("0.9.9-test")).andExpect(jsonPath("$.complete").value(true));
        mvc.perform(get("/admin/firmware").param("board","vibedock-m1").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("vibedock-m1-0.9.9-test.bin")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Use the built-in firmware")));
        mvc.perform(post("/admin/firmware/upload/remove").param("board","vibedock-m1").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(redirectedUrl("/admin/firmware?board=vibedock-m1"));
        mvc.perform(get("/admin/firmware").param("board","vibedock-m1").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("0.9.9-test"))));
    }
    @Test void devicesUpdateOverWifiOnlyWithSignedFirmware() throws Exception {
        for(var client:clients.views())clients.remove(client.identifier());
        String key="cd".repeat(32),id="vibedock-0a1b2c3d4e5f",path="/api/v1/clients/"+id+"/image";
        clients.paired(id,"Shelf",key,"192.168.1.30",80);
        java.util.function.Function<String,org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> pull=version->get(path).header("Authorization","Bearer "+key)
                .header("X-VibeDock-Firmware",version).header("X-VibeDock-Ota","1").with(r->{r.setLocalPort(8080);r.setRemoteAddr("192.168.1.30");return r;});
        // Firmware without update support cannot be updated over Wi-Fi.
        mvc.perform(get(path).header("Authorization","Bearer "+key).header("X-VibeDock-Firmware","0.1.0").with(r->{r.setLocalPort(8080);return r;}));
        mvc.perform(post("/admin/clients/"+id+"/update").with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(flash().attributeExists("deviceRemovalError"));
        mvc.perform(get(path).header("Authorization","Bearer "+key).header("X-VibeDock-Firmware","0.1.0").header("X-VibeDock-Ota","no-key").with(r->{r.setLocalPort(8080);return r;}));
        mvc.perform(get("/admin/devices").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Pair again to update to 0.2.0 over Wi-Fi")));
        mvc.perform(pull.apply("0.1.0")).andExpect(status().isOk()).andExpect(header().doesNotExist("X-VibeDock-Update"));
        mvc.perform(get("/admin/devices").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Update to 0.2.0")));
        mvc.perform(post("/admin/clients/"+id+"/update").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isForbidden());
        mvc.perform(post("/admin/clients/"+id+"/update").with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(redirectedUrl("/admin/devices#device-"+id)).andExpect(flash().attributeExists("clientMessage"));
        Assertions.assertTrue(clients.get(id).updating());
        mvc.perform(post("/admin/clients/"+id+"/rename").param("name","Kitchen").with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(jsonPath("$.name").value("Kitchen"));
        Assertions.assertEquals("Kitchen",clients.get(id).name());
        mvc.perform(post("/admin/clients/"+id+"/rename").param("name","x".repeat(41)).with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isBadRequest());
        mvc.perform(pull.apply("0.1.0")).andExpect(header().string("X-VibeDock-Update","0.2.0"));
        mvc.perform(get("/admin/clients/state.json").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(jsonPath("$['"+id+"'].updating").value(true)).andExpect(jsonPath("$['"+id+"'].firmware").value("0.1.0"));
        // The download: only with the device key, only on the device port, signed with the key the device got when paired.
        String firmwarePath="/api/v1/clients/"+id+"/firmware";
        mvc.perform(get(firmwarePath).with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isUnauthorized());
        mvc.perform(get(firmwarePath).header("Authorization","Bearer "+key).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isNotFound());
        var download=mvc.perform(get(firmwarePath).header("Authorization","Bearer "+key).with(r->{r.setLocalPort(8080);return r;}))
                .andExpect(status().isOk()).andReturn().getResponse();
        byte[] signed=download.getContentAsByteArray(),firmware={(byte)0xe9,3,2,0x40};
        Assertions.assertEquals(4+256+4,signed.length);
        Assertions.assertArrayEquals(firmware,java.util.Arrays.copyOf(signed,4));
        Assertions.assertArrayEquals(new byte[]{0,1,0,0},java.util.Arrays.copyOfRange(signed,260,264));  // 256, little endian
        Assertions.assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(signed)),download.getHeader("x-MD5"));
        Assertions.assertEquals(String.valueOf(signed.length),download.getHeader("X-VibeDock-Length"));
        String pem=otaSigning.publicKeyPem().replaceAll("-----[A-Z ]+-----|\\s","");
        var publicKey=java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(pem)));
        var verifier=java.security.Signature.getInstance("SHA256withRSA");verifier.initVerify(publicKey);verifier.update(firmware);
        Assertions.assertTrue(verifier.verify(java.util.Arrays.copyOfRange(signed,4,260)));
        // A failed attempt is reported and ends the request; the installed version ends it as well.
        mvc.perform(pull.apply("0.1.0").header("X-VibeDock-Update-Error","0.2.0: Signature verification failed"))
                .andExpect(header().doesNotExist("X-VibeDock-Update"));
        Assertions.assertEquals("0.2.0: Signature verification failed",clients.get(id).updateError());Assertions.assertFalse(clients.get(id).updating());
        mvc.perform(post("/admin/clients/all/update").with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(redirectedUrl("/admin/devices"));
        Assertions.assertNull(clients.get(id).updateError());
        mvc.perform(pull.apply("0.2.0")).andExpect(header().doesNotExist("X-VibeDock-Update"));
        Assertions.assertFalse(clients.get(id).updating());
        clients.remove(id);
    }
    @Test void invitingIsAdminOnly() throws Exception {
        mvc.perform(post("/admin/clients/invite").param("ip","192.168.1.50").param("code","123456")
                .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        mvc.perform(post("/admin/clients/invite").param("ip","192.168.1.50").param("code","123456").with(user("admin").roles("ADMIN"))
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isForbidden());
    }
    @Test void sharedDesignEditorAndPreviewAreAuthenticatedAdminOnlyAndPreviewDoesNotSave() throws Exception {
        String body="""
            {"mood":"-","scheme":"classic_mac","weatherLocation":"","layout":{"bands":[{"type":"usage","sources":["claude"]},{"type":"usage","sources":["codex","claude"]},{"type":"context","moodLeft":true}]},"inherit":false}
            """;
        var before=clients.defaultDesign();
        mvc.perform(get("/admin/design").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("layout-editor.js")));
        mvc.perform(get("/admin/designs").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(jsonPath("$.schemes.length()").value(Scheme.values().length))
                .andExpect(jsonPath("$.defaults.layout.bands[2].type").value("context"));
        mvc.perform(post("/admin/design-preview.png").contentType("application/json").content(body).with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        Assertions.assertEquals(before,clients.defaultDesign());
        mvc.perform(post("/admin/designs/default").contentType("application/json").content(body).with(user("admin").roles("ADMIN"))
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isForbidden());
        mvc.perform(get("/admin/designs").header("Accept","application/json").with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/design-preview.png").contentType("application/json").content(body).with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        mvc.perform(post("/admin/designs/default").contentType("application/json").content(body.replace("\"codex\",\"claude\"","\"unknown\""))
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isBadRequest());
        Assertions.assertEquals(before,clients.defaultDesign());
        mvc.perform(post("/admin/designs/default").contentType("application/json").content(body).with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk());
        Assertions.assertEquals(new Layout.Usage(List.of("codex","claude")),clients.defaultDesign().layout().bands().get(1));
        Assertions.assertEquals(Scheme.CLASSIC_MAC,clients.defaultDesign().scheme());
        clients.configureDesign(before);
    }
    @Test void deviceDesignCanBeSavedPreviewedAndResetToDefault() throws Exception {
        String id="vibedock-design-access";clients.paired(id,"Design test","a".repeat(64),"192.168.1.44",80);
        String body="""
            {"mood":"-","scheme":"paper","weatherLocation":"","layout":{"bands":[{"type":"usage","sources":["claude"]},{"type":"usage","sources":["codex","claude"]},{"type":"context","moodLeft":true}]},"inherit":false}
            """;
        mvc.perform(post("/admin/designs/"+id).contentType("application/json").content(body).with(user("admin").roles("ADMIN")).with(csrf())
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk());
        Assertions.assertTrue(clients.get(id).overridden());Assertions.assertEquals(Scheme.PAPER,clients.designFor(id).scheme());
        byte[] preview=mvc.perform(post("/admin/design-preview.png").param("target",id).contentType("application/json").content(body)
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        byte[] saved=mvc.perform(get("/admin/clients/"+id+"/preview.png").with(user("admin").roles("ADMIN"))
                .with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Assertions.assertArrayEquals(saved,preview);
        mvc.perform(post("/admin/designs/"+id).contentType("application/json").content("{\"inherit\":true}")
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isOk());
        Assertions.assertFalse(clients.get(id).overridden());Assertions.assertEquals(clients.defaultDesign(),clients.designFor(id));
    }
    @Test void webDisplaysServeTheirPageAndImageOnlyWithTheirSecretOnThePublicPort() throws Exception {
        mvc.perform(post("/admin/clients/web").param("name","Kitchen tablet").param("display","240x320").param("reloadSeconds","30")
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().is3xxRedirection());
        var view=clients.views().stream().filter(v->v.web()!=null&&v.name().equals("Kitchen tablet")).findFirst().orElseThrow();
        String secret=view.web().secret();Assertions.assertEquals("240x320",view.display().id());
        mvc.perform(get("/web/"+secret).with(r->{r.setLocalPort(8080);return r;}))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("/web/"+secret+"/image.png")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-reload=\"30\"")));
        var png=mvc.perform(get("/web/"+secret+"/image.png").with(r->{r.setLocalPort(8080);r.setRemoteAddr("192.168.1.77");return r;}))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andReturn().getResponse().getContentAsByteArray();
        var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));Assertions.assertEquals(240,image.getWidth());Assertions.assertEquals(320,image.getHeight());
        Assertions.assertEquals("192.168.1.77",clients.get(view.identifier()).ip());Assertions.assertEquals("Connected",clients.get(view.identifier()).status());
        // The page asks for its window's size and pixel ratio: the type's image as large as it fits, drawn sharp.
        var sharp=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(mvc.perform(get("/web/"+secret+"/image.png").param("w","390").param("h","844").param("r","3")
                .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()));
        Assertions.assertEquals(240*5,sharp.getWidth());Assertions.assertEquals(320*5,sharp.getHeight());  // 390/240 x 3 = 4.875, rounded up to 5
        // The home screen icon, behind the secret as well.
        mvc.perform(get("/web/"+secret+"/icon.png").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/web/"+"0".repeat(64)+"/icon.png").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        mvc.perform(get("/web/"+secret).with(r->{r.setLocalPort(8080);return r;})).andExpect(content().string(org.hamcrest.Matchers.containsString("rel=\"apple-touch-icon\" href=\"/web/"+secret+"/icon.png\"")));
        // Changes: the state tag follows the image; a swipe changes the theme (POST only, no session needed).
        var state=new com.fasterxml.jackson.databind.ObjectMapper().readTree(mvc.perform(get("/web/"+secret+"/state").with(r->{r.setLocalPort(8080);return r;}))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("tag").asText();
        var scheme=clients.designFor(view.identifier()).scheme();
        mvc.perform(post("/web/"+secret+"/swipe").param("direction","next").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNoContent());
        Assertions.assertEquals(scheme.shifted(1),clients.designFor(view.identifier()).scheme());
        mvc.perform(get("/web/"+secret+"/state").with(r->{r.setLocalPort(8080);return r;})).andExpect(jsonPath("$.tag").value(org.hamcrest.Matchers.not(state)));
        mvc.perform(get("/web/"+secret+"/swipe").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/web/"+"0".repeat(64)+"/swipe").param("direction","next").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        mvc.perform(post("/web/"+secret+"/swipe").param("direction","next").with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isNotFound());
        clients.setDesign(view.identifier(),null);
        // Fitting the browser: the window's shape at its full resolution; devices cannot fit.
        clients.setDisplay(view.identifier(),"fit");
        var fitted=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(mvc.perform(get("/web/"+secret+"/image.png").param("w","393").param("h","852").param("r","3")
                .with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()));
        Assertions.assertEquals(360*3.5,fitted.getWidth());Assertions.assertEquals(780*3.5,fitted.getHeight());  // see WebSizeTest
        Assertions.assertThrows(IllegalArgumentException.class,()->{clients.paired("vibedock-fit-test","Desk","a".repeat(64),"192.168.1.30",80);clients.setDisplay("vibedock-fit-test","fit");});
        clients.remove("vibedock-fit-test");clients.setDisplay(view.identifier(),"240x320");
        // Only on the public port, only GET, only with the right secret; the admin list shows the links.
        mvc.perform(get("/web/"+secret).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isNotFound());
        mvc.perform(post("/web/"+secret).with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/web/"+"0".repeat(64)+"/image.png").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        mvc.perform(get("/admin/devices").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("/web/"+secret+"/image.png")));
        // New links: the old secret stops working.
        mvc.perform(post("/admin/clients/"+view.identifier()+"/web/renew").with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/web/"+secret).with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isNotFound());
        String renewed=clients.get(view.identifier()).web().secret();Assertions.assertNotEquals(secret,renewed);
        mvc.perform(get("/web/"+renewed+"/image.png").with(r->{r.setLocalPort(8080);return r;})).andExpect(status().isOk());
        // Web displays have no firmware and are never asked to pull.
        mvc.perform(get("/admin/firmware").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Kitchen tablet"))));
        Assertions.assertEquals(ClientPairing.UNKNOWN_IP,clients.address(view.identifier()).ip());
        clients.remove(view.identifier());
    }
    @Test void devicesAndSourcesKeepTheOrderTheyAreDraggedInto() throws Exception {
        for(var client:clients.views())clients.remove(client.identifier());
        String key="a".repeat(64);
        clients.paired("vibedock-order-one","One",key,"192.168.1.11",80);clients.paired("vibedock-order-two","Two",key,"192.168.1.12",80);
        mvc.perform(post("/admin/clients/order").contentType("application/json").content("{\"ids\":[\"vibedock-order-two\",\"unknown\"]}")
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ids[0]").value("vibedock-order-two")).andExpect(jsonPath("$.ids[1]").value("vibedock-order-one"));
        Assertions.assertEquals(List.of("vibedock-order-two","vibedock-order-one"),clients.views().stream().map(ClientRegistry.View::identifier).toList());
        mvc.perform(post("/admin/clients/order").contentType("application/json").content("{\"ids\":[]}")
                .with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isForbidden());
        mvc.perform(post("/admin/sources/order").contentType("application/json").content("{\"ids\":[\"claude\",\"codex\"]}")
                .with(user("admin").roles("ADMIN")).with(csrf()).with(r->{r.setLocalPort(10001);return r;}))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ids[0]").value("claude"));
        Assertions.assertEquals(List.of("claude","codex"),store.get().accounts().stream().map(SettingsStore.Account::id).toList());
        clients.remove("vibedock-order-one");clients.remove("vibedock-order-two");
    }
}
