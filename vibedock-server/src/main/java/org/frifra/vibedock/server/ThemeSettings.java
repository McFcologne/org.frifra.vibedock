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
    record State(Set<Scheme> disabled,boolean dark) {
        State(Set<Scheme> disabled){this(disabled,false);}
        State { disabled=disabled==null?Set.of():Set.copyOf(disabled);if(disabled.size()==Scheme.values().length)throw new IllegalArgumentException("Keep at least one theme enabled"); }
    }
    private final Path file;private final ObjectMapper json;
    private volatile State state=new State(Set.of());
    ThemeSettings(@Value("${vibedock.data-dir}") String directory,ObjectMapper json) throws IOException {
        this.json=json;file=Path.of(directory).resolve("themes.json");
        if(Files.exists(file))state=json.readValue(file.toFile(),State.class);
    }
    boolean enabled(Scheme scheme){return !state.disabled().contains(scheme);}
    synchronized void setEnabled(Scheme scheme,boolean enabled) throws IOException {
        var disabled=new HashSet<>(state.disabled());if(enabled)disabled.remove(scheme);else disabled.add(scheme);
        var next=new State(disabled);if(next.equals(state))return;
        Files.createDirectories(file.getParent());var temporary=Files.createTempFile(file.getParent(),"themes-",".tmp");
        try{json.writeValue(temporary.toFile(),next);Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);state=next;}
        finally{Files.deleteIfExists(temporary);}
    }
    Scheme shifted(Scheme current,int step){
        var snapshot=state;var all=Scheme.values();int direction=Integer.signum(step);if(direction==0)return current;
        for(int n=1;n<=all.length;n++){var candidate=all[Math.floorMod(current.ordinal()+direction*n,all.length)];if(!snapshot.disabled().contains(candidate))return candidate;}
        return current;
    }
}
