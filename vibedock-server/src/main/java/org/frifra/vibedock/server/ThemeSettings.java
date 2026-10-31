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
    record State(Set<String> disabled,boolean dark,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String contributor) {
        State(Set<String> disabled){this(disabled,false,null);}
        State(Set<String> disabled,boolean dark){this(disabled,dark,null);}
        State {
            if(contributor!=null){contributor=contributor.strip();if(contributor.isEmpty())contributor=null;
                else if(contributor.codePointCount(0,contributor.length())>160||contributor.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Enter a contributor name of at most 160 characters");}
            // Old enum spellings were accepted on read; keep the same canonical IDs and JSON shape.
            disabled=disabled==null?Set.of():disabled.stream().map(id->Scheme.stored(id).id()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
    private final Path file;private final ObjectMapper json;private final ThemeLibrary library;
    private volatile State state=new State(Set.of());
    @org.springframework.beans.factory.annotation.Autowired
    ThemeSettings(@Value("${vibedock.data-dir}") String directory,ObjectMapper json,ThemeLibrary library) throws IOException {
        this.library=library;this.json=json;file=Path.of(directory).resolve("themes.json");
        if(Files.exists(file))state=json.readValue(file.toFile(),State.class);
    }
    ThemeSettings(String directory,ObjectMapper json) throws IOException {this(directory,json,null);}
    ThemeLibrary.Snapshot snapshot(){return library==null?ThemeLibrary.builtins():library.snapshot();}
    private Set<String> effectiveDisabled(){
        var disabled=state.disabled();
        if(snapshot().catalog().all().stream().noneMatch(s->!disabled.contains(s.id()))){
            var fallback=new HashSet<>(disabled);fallback.remove(Scheme.MIDNIGHT.id());return fallback;
        }
        return disabled;
    }
    boolean dark(){return state.dark();}
    String contributor(){return state.contributor()==null?"":state.contributor();}
    synchronized void setContributor(String name) throws IOException {save(new State(state.disabled(),state.dark(),name));}
    synchronized void setDark(boolean dark) throws IOException { save(new State(state.disabled(),dark,state.contributor())); }
    boolean enabled(Scheme scheme){return !effectiveDisabled().contains(scheme.id());}
    synchronized void setEnabled(Scheme scheme,boolean enabled) throws IOException {
        var disabled=new HashSet<>(state.disabled());if(enabled)disabled.remove(scheme.id());else disabled.add(scheme.id());
        if(snapshot().catalog().all().stream().noneMatch(s->!disabled.contains(s.id())))throw new IllegalArgumentException("Keep at least one theme enabled");
        save(new State(disabled,state.dark(),state.contributor()));
    }
    private void save(State next) throws IOException {
        if(next.equals(state))return;
        Files.createDirectories(file.getParent());var temporary=Files.createTempFile(file.getParent(),"themes-",".tmp");
        try{json.writeValue(temporary.toFile(),next);Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);state=next;}
        finally{Files.deleteIfExists(temporary);}
    }
    Scheme shifted(Scheme current,int step){
        if(step==0)return current;
        var catalog=snapshot().catalog();
        var start=catalog.find(current.id()).orElse(step>0?catalog.all().getLast():catalog.all().getFirst());
        return catalog.nextEnabled(start,step,effectiveDisabled());
    }
}
