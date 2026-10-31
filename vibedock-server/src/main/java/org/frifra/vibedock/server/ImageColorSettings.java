package org.frifra.vibedock.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Ordered device-only color policy. Missing rules mean full RGB565; browser rendering bypasses it. */
@Component
class ImageColorSettings {
    record Rule(int maxWidth,int maxHeight,int colors) {
        Rule {
            if(maxWidth<16||maxWidth>4096||maxHeight<16||maxHeight>4096||!Set.of(0,32,64,256).contains(colors))
                throw new IllegalArgumentException("Use dimensions 16–4096 and 32, 64, 256 or full colors");
        }
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
    record Mapping(List<Rule> rules,Boolean adaptive) {
        Mapping(List<Rule> rules){this(rules,true);}
        Mapping {
            if(rules==null||rules.size()>16||rules.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Use at most 16 color rules");rules=List.copyOf(rules);
            adaptive=adaptive==null?true:adaptive;
        }
        int colors(int width,int height){return rules.stream().filter(r->width<=r.maxWidth()&&height<=r.maxHeight()).mapToInt(Rule::colors).findFirst().orElse(0);}
    }
    static final Mapping DEFAULT=new Mapping(List.of(new Rule(420,420,32),new Rule(640,640,256)));
    private final Path file;private final ObjectMapper json;private volatile Mapping mapping;
    ImageColorSettings(@Value("${vibedock.data-dir}") String directory,ObjectMapper json) throws IOException {
        this.json=json;file=Path.of(directory).resolve("image-colors.json");
        mapping=Files.exists(file)?json.readValue(file.toFile(),Mapping.class):DEFAULT;
    }
    Mapping get(){return mapping;}
    synchronized void save(Mapping next) throws IOException {
        Files.createDirectories(file.getParent());var temp=Files.createTempFile(file.getParent(),"image-colors-",".tmp");
        try{Files.write(temp,json.writeValueAsBytes(next));try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}mapping=next;}
        finally{Files.deleteIfExists(temp);}
    }
}
