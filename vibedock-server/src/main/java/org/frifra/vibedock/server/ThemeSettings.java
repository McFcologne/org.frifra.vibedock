package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Instance-wide theme availability. Existing designs keep their theme; selections and swipes use enabled ones. */
@Component
class ThemeSettings {
    record State(Set<String> disabled,boolean dark) {
        State(Set<String> disabled){this(disabled,false);}
        State {
            // Old enum spellings were accepted on read; keep the same canonical IDs and JSON shape.
            disabled=disabled==null?Set.of():disabled.stream().map(id->Scheme.parse(id).id()).collect(java.util.stream.Collectors.toUnmodifiableSet());
            if(disabled.size()==ThemeCatalog.builtins().all().size())throw new IllegalArgumentException("Keep at least one theme enabled");
        }
    }
    private final Path file;private final ObjectMapper json;
    private volatile State state=new State(Set.of());
    ThemeSettings(@Value("${vibedock.data-dir}") String directory,ObjectMapper json) throws IOException {
        this.json=json;file=Path.of(directory).resolve("themes.json");
        if(Files.exists(file))state=json.readValue(file.toFile(),State.class);
    }
    boolean dark(){return state.dark();}
    synchronized void setDark(boolean dark) throws IOException { save(new State(state.disabled(),dark)); }
    boolean enabled(Scheme scheme){return !state.disabled().contains(scheme.id());}
    synchronized void setEnabled(Scheme scheme,boolean enabled) throws IOException {
        var disabled=new HashSet<>(state.disabled());if(enabled)disabled.remove(scheme.id());else disabled.add(scheme.id());
        save(new State(disabled,state.dark()));
    }
    private void save(State next) throws IOException {
        if(next.equals(state))return;
        Files.createDirectories(file.getParent());var temporary=Files.createTempFile(file.getParent(),"themes-",".tmp");
        try{json.writeValue(temporary.toFile(),next);Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);state=next;}
        finally{Files.deleteIfExists(temporary);}
    }
    Scheme shifted(Scheme current,int step){
        return ThemeCatalog.builtins().nextEnabled(current,step,state.disabled());
    }
}
