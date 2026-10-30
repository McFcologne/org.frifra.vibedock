package org.frifra.vibedock.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** One instance's immutable live catalog; imports validate completely before an atomic on-disk replacement. */
@Component
final class ThemeLibrary {
    static final int MAX_INSTALLED=64,MAX_TOTAL=64*1024*1024;
    record Item(ThemePackage.Contents contents,Scheme scheme,boolean builtin) {}
    record Snapshot(Map<String,Item> items,ThemeCatalog catalog) {
        Optional<Item> item(String id){return Optional.ofNullable(id==null?null:items.get(id));}
        Optional<ThemePackage.Contents> contents(String id){return item(id).map(Item::contents);}
        Scheme effective(Scheme selected){return catalog.find(selected.id()).orElse(Scheme.MIDNIGHT);}
        List<ThemeIcons.Entry> icons(){return ThemeIcons.catalog(items.values().stream().map(Item::contents).toList());}
    }
    record Inspection(String id,String name,String version,String author,String license,boolean exists,boolean builtin,
                      String currentVersion,boolean canUpdate,String copyId) {}
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
    static final class Conflict extends IllegalArgumentException {Conflict(String message){super(message);}}
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ThemeLibrary.class);
    private final Path directory;
    private volatile Snapshot state=builtins();
    ThemeLibrary(@Value("${vibedock.data-dir}") String directory) throws IOException {
        this.directory=Path.of(directory).resolve("themes");
        if(!Files.exists(this.directory))return;
        var custom=new TreeMap<String,Item>();
        try(var files=Files.list(this.directory)){
            for(var path:files.filter(p->p.getFileName().toString().endsWith(".vibetheme")).sorted().toList()){
                String filename=path.getFileName().toString();
                try{
                    if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>ThemePackage.MAX_ARCHIVE)throw new IllegalArgumentException();
                    byte[] bytes;try(var input=Files.newInputStream(path)){bytes=input.readNBytes(ThemePackage.MAX_ARCHIVE+1);}
                    var item=validate(bytes);String id=item.scheme().id();
                    if(!filename.equals(id+".vibetheme")||state.items().containsKey(id))throw new IllegalArgumentException();
                    var next=new TreeMap<>(custom);next.put(id,item);budget(next);custom=next;
                }catch(IOException|IllegalArgumentException e){LOG.warn("Theme package {} was not loaded: invalid, incompatible or over the instance limit",filename.replaceAll("[^a-zA-Z0-9._-]","_"));}
            }
        }
        state=combine(custom);
    }
    static Snapshot builtins(){return combine(Map.of());}
    private static Snapshot combine(Map<String,Item> custom){
        var all=new LinkedHashMap<String,Item>();
        for(var scheme:ThemeCatalog.builtins().all())all.put(scheme.id(),new Item(BuiltinThemes.get(scheme.id()).contents(),scheme,true));
        new TreeMap<>(custom).forEach(all::put);
        return new Snapshot(Collections.unmodifiableMap(all),new ThemeCatalog(all.values().stream().map(Item::scheme).toList()));
    }
    Snapshot snapshot(){return state;}
    Inspection inspect(byte[] bytes){
        var item=validate(bytes);var m=item.contents().manifest();var existing=state.item(m.id());
        String copy=m.id().substring(0,Math.min(60,m.id().length()))+".copy-"+UUID.randomUUID().toString().substring(0,8);
        return new Inspection(m.id(),m.name(),m.version(),m.author(),m.license(),existing.isPresent(),existing.map(Item::builtin).orElse(false),
                existing.map(i->i.contents().manifest().version()).orElse(null),existing.filter(i->!i.builtin()&&newer(m.version(),i.contents().manifest().version())).isPresent(),copy);
    }
    synchronized Item install(byte[] bytes,String mode,String copyId,String copyName) throws IOException {
        var source=validate(bytes);var m=source.contents().manifest();String id=m.id();
        if("copy".equals(mode)){
            if(copyId==null||!copyId.matches("[a-z][a-z0-9._-]{0,79}"))throw new IllegalArgumentException("Choose a new lowercase theme ID");
            id=copyId;String name=copyName==null||copyName.isBlank()?shortName(m.name()+" (copy)"):copyName.strip();
            source=validate(ThemeArchive.write(source.contents(),false,id,name));
        }else if(!"add".equals(mode)&&!"update".equals(mode))throw new IllegalArgumentException("Choose install, update or copy");
        var existing=state.item(id);
        // Canonical bytes also make a retry after a lost response harmless, without a second install or revision.
        byte[] stored=ThemeArchive.write(source.contents(),false);var item=validate(stored);
        if(existing.isPresent()&&!existing.get().builtin()&&Arrays.equals(stored,ThemeArchive.write(existing.get().contents(),false)))return existing.get();
        if("update".equals(mode)){
            if(existing.isEmpty()||existing.get().builtin())throw new Conflict("Built-in themes stay intact; install an independent copy");
            if(!newer(m.version(),existing.get().contents().manifest().version()))throw new Conflict("Updates need a newer theme version; install a copy instead");
        }else if(existing.isPresent())throw new Conflict("This theme ID already exists; choose update or an independent copy");
        if(existing.isEmpty()&&Files.exists(directory.resolve(id+".vibetheme")))
            throw new Conflict("An unloaded package file already uses this ID; choose an independent copy");
        // Store the compact package, excluding generated authoring references; those are recreated on export.
        var custom=custom(state);custom.put(id,item);budget(custom);var next=combine(custom);
        Files.createDirectories(directory);var temporary=Files.createTempFile(directory,"theme-",".tmp");
        var target=directory.resolve(id+".vibetheme");
        try{
            Files.write(temporary,stored);
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            state=next;
        }finally{Files.deleteIfExists(temporary);}
        LOG.info("Theme {} v{} installed ({} compressed bytes)",id,item.contents().manifest().version(),stored.length);return item;
    }
    synchronized void remove(String id) throws IOException {
        var item=state.item(id).orElseThrow(()->new Conflict("Theme not found"));
        if(item.builtin())throw new Conflict("Built-in themes cannot be removed");
        var custom=custom(state);custom.remove(id);var next=combine(custom);
        Files.delete(directory.resolve(id+".vibetheme"));state=next;LOG.info("Theme {} removed",id);
    }
    byte[] export(String id,boolean guide) throws IOException {
        var contents=state.contents(id).orElseThrow(()->new Conflict("Theme not found"));return ThemeArchive.write(contents,guide);
    }
    private static TreeMap<String,Item> custom(Snapshot snapshot){
        var custom=new TreeMap<String,Item>();snapshot.items().forEach((id,item)->{if(!item.builtin())custom.put(id,item);});return custom;
    }
    private static void budget(Map<String,Item> custom){
        if(custom.size()>MAX_INSTALLED||custom.values().stream().mapToLong(i->i.contents().expandedBytes()).sum()>MAX_TOTAL)
            throw new IllegalArgumentException("Installed themes exceed 64 packages or 64 MiB expanded");
    }
    private static Item validate(byte[] bytes){
        try{
            var contents=ThemePackage.read(bytes);var definition=ThemeDefinition.read(contents);
            for(String path:contents.paths())if(path.endsWith(".png")||path.endsWith(".svg")){
                try{ThemeIconAsset.draw(path,contents.file(path),Twemoji.SIZE);}
                catch(Exception e){String reason=e instanceof IllegalArgumentException?e.getMessage():"Invalid or unreadable image";
                    throw new IllegalArgumentException("Unsupported theme graphic: "+path+" ("+reason+")",e);}
            }
            var m=contents.manifest();return new Item(contents,new Scheme(m.id(),m.name(),definition),false);
        }catch(IOException|tools.jackson.core.JacksonException e){throw new IllegalArgumentException("Could not read the theme archive or definition",e);}
    }
    static boolean newer(String next,String current){
        var a=next.split("\\.");var b=current.split("\\.");
        for(int i=0;i<3;i++){int difference=new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i]));if(difference!=0)return difference>0;}return false;
    }
    private static String shortName(String name){return name.substring(0,name.offsetByCodePoints(0,Math.min(80,name.codePointCount(0,name.length()))));}
}
