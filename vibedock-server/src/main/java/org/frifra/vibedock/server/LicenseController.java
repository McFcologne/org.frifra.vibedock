package org.frifra.vibedock.server;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Notices generated from resolved dependencies and from the installed Docker image. No runtime network calls. */
@Controller
@RequestMapping("/admin/licenses")
class LicenseController {
    record Notice(String name,String version,String license,String text) {}
    private final ObjectMapper json;
    private final Path inventory;
    LicenseController(ObjectMapper json,@Value("${vibedock.license-inventory:/app/legal/components.json}") String inventory){
        this.json=json;this.inventory=Path.of(inventory);
    }
    @GetMapping String page(Model model) throws IOException {
        var report=new ClassPathResource("MAVEN-THIRD-PARTY.txt");
        model.addAttribute("javaNotices",report.exists()?report.getContentAsString(StandardCharsets.UTF_8):null);
        model.addAttribute("containerNotices",notices());
        model.addAttribute("containerInventoryAvailable",Files.isRegularFile(inventory));
        return "licenses";
    }
    @GetMapping(value="/components/{index}.txt",produces="text/plain;charset=UTF-8") @ResponseBody
    String component(@PathVariable int index) throws IOException {
        var entries=notices();
        if(index<0||index>=entries.size())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var entry=entries.get(index);
        return entry.name()+" "+entry.version()+"\n"+entry.license()+"\n\n"+entry.text();
    }
    private List<Notice> notices() throws IOException {
        return Files.isRegularFile(inventory)?json.readValue(inventory.toFile(),new TypeReference<List<Notice>>(){}):List.of();
    }
}
