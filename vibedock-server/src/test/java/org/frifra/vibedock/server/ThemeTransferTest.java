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
    @Autowired MockMvc mvc;@Autowired ThemeLibrary library;@Autowired ClientRegistry registry;@Autowired DesignService designs;
    @MockitoBean DevicePoke poke;@MockitoBean ProviderClient providers;@MockitoBean BrowserLogin login;
    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request){return request.with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10001);return r;});}
    @Test void authenticatedImportIsLiveUpdatesExistingImagesAndGuardsUsedThemes() throws Exception {
        String id="http.custom";byte[] archive=ThemeLibraryTest.theme(id,"1.0.0","#123456");var old=registry.defaultDesign();
        try{
            mvc.perform(admin(post("/admin/themes/inspect")).with(csrf()).content(archive)).andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(false));
            mvc.perform(admin(post("/admin/themes/import")).with(csrf()).content(archive)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
            mvc.perform(admin(get("/admin/designs"))).andExpect(jsonPath("$.schemes.length()").value(36));
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
        mvc.perform(get("/admin/themes/midnight/export").with(user("admin").roles("ADMIN")).with(r->{r.setLocalPort(10002);return r;})).andExpect(status().isNotFound());
    }
}
