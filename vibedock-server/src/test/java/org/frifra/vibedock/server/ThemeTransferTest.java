package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties={"vibedock.admin-password=local-theme-test-only","vibedock.polling=false","vibedock.log-dir=target/theme-test-logs"})
@AutoConfigureMockMvc
class ThemeTransferTest {
    @DynamicPropertySource static void directory(DynamicPropertyRegistry properties) throws Exception {
        var directory=Files.createTempDirectory("vibedock-transfer-");properties.add("vibedock.data-dir",directory::toString);
    }
    @Autowired ThemeEditor editor;@Autowired MockMvc mvc;@Autowired ThemeLibrary library;@Autowired ClientRegistry registry;@Autowired DesignService designs;
    @MockitoBean DevicePoke poke;@MockitoBean ProviderClient providers;@MockitoBean BrowserLogin login;
    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request){return request.with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;});}
    @Test void authenticatedImportIsLiveUpdatesExistingImagesAndGuardsUsedThemes() throws Exception {
        String id="http.custom";byte[] archive=ThemeLibraryTest.theme(id,"1.0.0","#123456");var old=registry.defaultDesign();
        try{
            mvc.perform(admin(post("/admin/themes/inspect")).with(csrf()).content(archive)).andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(false));
            mvc.perform(admin(post("/admin/themes/import")).with(csrf()).content(archive)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
            mvc.perform(admin(get("/admin/designs"))).andExpect(jsonPath("$.schemes.length()").value(ThemeCatalog.builtins().all().size()+1));
            var selected=library.snapshot().catalog().find(id).orElseThrow();registry.configureDesign(new Design(selected,"",null));
            byte[] before=designs.render(registry.defaultDesign(),"test").png();
            mvc.perform(admin(post("/admin/themes/"+id+"/delete")).with(csrf())).andExpect(status().isConflict());
            mvc.perform(admin(post("/admin/themes/import")).param("mode","update").with(csrf()).content(ThemeLibraryTest.theme(id,"1.0.1","#654321"))).andExpect(status().isOk());
            assertFalse(Arrays.equals(before,designs.render(registry.defaultDesign(),"test").png()),"already saved design uses the update without being rewritten");
            mvc.perform(admin(post("/admin/themes/import")).param("mode","update").with(csrf()).content(archive)).andExpect(status().isConflict());
            registry.configureDesign(new Design(Scheme.MIDNIGHT,"",null,Layout.DEFAULT,null,new Design.Icon("theme-catalog",id,"theme")));
            mvc.perform(admin(post("/admin/themes/"+id+"/delete")).with(csrf())).andExpect(status().isConflict());
            registry.configureDesign(old);
            mvc.perform(admin(post("/admin/themes/"+id+"/delete")).with(csrf())).andExpect(status().isOk());
        }finally{registry.configureDesign(old);if(library.snapshot().catalog().find(id).isPresent())library.remove(id);}
    }
    @Test void transfersStayOnTheAdminListenerAndWritesNeedCsrf() throws Exception {
        byte[] archive=ThemeLibraryTest.theme("http.boundary","1.0.0","#123456");
        mvc.perform(post("/admin/themes/inspect").with(csrf()).content(archive).header("Accept","application/json").with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isUnauthorized());
        mvc.perform(admin(post("/admin/themes/import")).content(archive)).andExpect(status().isForbidden());
        mvc.perform(admin(post("/admin/themes/inspect")).with(csrf()).content("not a theme")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/admin/themes/import").with(user("admin").roles("ADMIN")).with(csrf()).content(archive).with(r->{r.setLocalPort(10002);return r;})).andExpect(status().isNotFound());
        byte[] exported=mvc.perform(admin(get("/admin/themes/midnight/export")).param("agent","true")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition","attachment; filename=\"midnight.vibetheme\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(ThemePackage.read(exported).paths().contains("authoring/agent.txt"));
        assertTrue(ThemePackage.read(exported).paths().contains("preview.html"));
        // A real server-rendered export for the offline file:// browser check after Maven verify.
        var offline=java.nio.file.Path.of("target/theme-preview-offline").toAbsolutePath();Files.createDirectories(offline);
        for(var entry:ThemeLibraryTest.unzip(exported).entrySet()){
            var target=offline.resolve(entry.getKey()).normalize();assertTrue(target.startsWith(offline));
            Files.createDirectories(target.getParent());Files.write(target,entry.getValue());
        }
        mvc.perform(get("/admin/themes/midnight/export").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10002);return r;})).andExpect(status().isNotFound());
    }

    @Test void editorDraftsPreviewExactBuiltInsAndSaveOnlyOnExplicitRequest() throws Exception {
        var json=new tools.jackson.databind.json.JsonMapper();
        for(var scheme:ThemeCatalog.builtins().all()){
            var draft=editor.open(scheme.id(),false).draft();
            var preview=mvc.perform(admin(post("/admin/theme-editor/preview.png")).with(csrf()).content(json.writeValueAsBytes(draft)))
                    .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andReturn().getResponse().getContentAsByteArray();
            assertArrayEquals(designs.themePreview(scheme),preview,scheme.id()+" unchanged draft uses identical rendering");
        }
        var draft=ThemeEditorTest.edit(editor.open("midnight",false).draft(),Map.of("id","http.editor","name","Editor theme"));
        byte[] body=json.writeValueAsBytes(draft);
        mvc.perform(admin(get("/admin/themes/midnight/edit"))).andExpect(status().isOk()).andExpect(view().name("theme-editor"));
        mvc.perform(admin(get("/admin/themes/midnight/editor"))).andExpect(status().isOk()).andExpect(jsonPath("$.builtin").value(true));
        byte[] png=mvc.perform(admin(post("/admin/theme-editor/preview.png")).with(csrf()).param("display","135x240").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(135,javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png)).getWidth());
        byte[] exported=mvc.perform(admin(post("/admin/theme-editor/export")).with(csrf()).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals("http.editor",ThemePackage.read(exported).manifest().id());assertTrue(library.snapshot().item("http.editor").isEmpty());
        try{
            mvc.perform(admin(post("/admin/theme-editor/save")).with(csrf()).content(body)).andExpect(status().isOk());
            assertTrue(library.snapshot().item("http.editor").isPresent());
            mvc.perform(admin(post("/admin/theme-editor/save")).with(csrf()).content(body)).andExpect(status().isOk());
        }finally{if(library.snapshot().item("http.editor").isPresent())library.remove("http.editor");}
    }
    @Test void editorRejectsInvalidDraftsAndUsesTheSameAdminSecurityBoundary() throws Exception {
        var json=new tools.jackson.databind.json.JsonMapper();byte[] body=json.writeValueAsBytes(editor.open("midnight",false).draft());
        mvc.perform(admin(post("/admin/theme-editor/save")).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/theme-editor/save").with(user("admin").roles("ADMIN")).with(csrf()).content(body).with(r->{r.setLocalPort(10002);return r;})).andExpect(status().isNotFound());
        mvc.perform(get("/admin/themes/midnight/editor").header("Accept","application/json").with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isUnauthorized());
        mvc.perform(admin(post("/admin/theme-editor/preview.png")).with(csrf()).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(admin(post("/admin/theme-editor/save")).with(csrf()).content("{bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(admin(post("/admin/theme-editor/preview.png")).with(csrf()).param("display","huge-unknown").content(body)).andExpect(status().isBadRequest());
    }

    @Test void editorUploadKeepsSafeVectorsAndRequiresCsrf() throws Exception {
        byte[] svg="<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\"><script>alert(1)</script><rect width=\"48\" height=\"48\" fill=\"#ff0000\"/></svg>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] body=mvc.perform(admin(post("/admin/theme-editor/icon")).param("format","svg").with(csrf()).content(svg))
                .andExpect(status().isOk()).andExpect(jsonPath("$.file").value("icon.svg"))
                .andExpect(jsonPath("$.removed").value(1)).andReturn().getResponse().getContentAsByteArray();
        var json=new tools.jackson.databind.json.JsonMapper();byte[] svgBytes=Base64.getDecoder().decode(json.readTree(body).get("data").textValue());
        assertFalse(new String(svgBytes,java.nio.charset.StandardCharsets.UTF_8).contains("<script"));
        assertEquals(0xff0000,ThemeIconAsset.draw("icon.svg",svgBytes,48).getRGB(24,24)&0xffffff);
        mvc.perform(admin(post("/admin/theme-editor/icon")).param("format","svg").content(svg)).andExpect(status().isForbidden());
        mvc.perform(admin(post("/admin/theme-editor/icon")).param("format","svg").with(csrf()).content(new byte[ThemeIconAsset.MAX_BYTES+1])).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/theme-editor/icon").param("format","svg").with(user("admin").roles("ADMIN")).with(csrf()).content(svg).with(r->{r.setLocalPort(10002);return r;})).andExpect(status().isNotFound());
    }

    @Test void contributorPreferenceIsOptionalAndStaysBehindAdminAndCsrf() throws Exception {
        try{
            mvc.perform(admin(get("/admin/theme-editor/contributor"))).andExpect(status().isOk()).andExpect(jsonPath("$.name").value(""));
            mvc.perform(admin(post("/admin/theme-editor/contributor")).with(csrf()).contentType("application/json").content("{\"name\":\"Alex Artist\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Alex Artist"));
            mvc.perform(admin(get("/admin/theme-editor/contributor"))).andExpect(jsonPath("$.name").value("Alex Artist"));
            mvc.perform(admin(post("/admin/theme-editor/contributor")).contentType("application/json").content("{\"name\":\"Other\"}")).andExpect(status().isForbidden());
            mvc.perform(get("/admin/theme-editor/contributor").header("Accept","application/json").with(r->{r.setLocalPort(10001);return r;})).andExpect(status().isUnauthorized());
            mvc.perform(get("/admin/theme-editor/contributor").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10002);return r;})).andExpect(status().isNotFound());
        }finally{mvc.perform(admin(post("/admin/theme-editor/contributor")).with(csrf()).contentType("application/json").content("{\"name\":\"\"}")).andExpect(status().isOk());}
    }
}
