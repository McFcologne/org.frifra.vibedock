package org.frifra.vibedock.server;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.*;

/** Stateless, validated drafts. Preview/export never install a package or change a device's design. */
@Component
final class ThemeEditor {
    record Icon(String id,String label,String file,String data,String author,String license) {
        Icon(String id,String label,String file,String data){this(id,label,file,data,null,null);}
    }
    record Draft(String sourceId,String sourceDigest,String id,String name,String version,String author,String license,
                 String defaultIcon,JsonNode drawing,List<Icon> icons) {}
    record Template(String id,String name) {}
    record State(Draft draft,boolean builtin,List<String> presets,List<DisplayType> displays,List<Template> templates,Map<String,Map<String,Object>> windows) {}
    private static final JsonMapper JSON=new JsonMapper();
    private final ThemeLibrary library;
    ThemeEditor(ThemeLibrary library){this.library=library;}
    State open(String id,boolean copy) throws IOException {
        var item=library.snapshot().item(id).orElseThrow(()->new ThemeLibrary.Conflict("Theme not found"));
        var source=ThemeCredits.prepare(item.contents(),false);var m=source.manifest();boolean duplicate=item.builtin()||copy;
        String nextId=duplicate?id.substring(0,Math.min(60,id.length()))+".copy-"+UUID.randomUUID().toString().substring(0,8):id;
        String nextName=duplicate?m.name().substring(0,Math.min(70,m.name().length()))+" (copy)":m.name();
        String nextVersion=duplicate?"1.0.0":patch(m.version());
        var draft=new Draft(id,digest(item.contents()),nextId,nextName,nextVersion,m.author(),m.license(),m.defaultIcon(),
                JSON.readTree(source.file(m.definition())),m.icons().stream().map(i->new Icon(i.id(),i.label(),i.file(),null,i.author(),i.license())).toList());
        return new State(draft,item.builtin(),Arrays.stream(Scheme.Style.values()).map(s->s.name().toLowerCase(Locale.ROOT)).toList(),DisplayType.INVENTORY,ThemeCatalog.builtins().all().stream().map(t->new Template(t.id(),t.label)).toList(),Map.of("windows",ThemeWindow.defaults(Scheme.Style.WINDOWS),"mac",ThemeWindow.defaults(Scheme.Style.MAC),"fluent",ThemeWindow.defaults(Scheme.Style.FLUENT),"macos",ThemeWindow.defaults(Scheme.Style.MACOS)));
    }
    Map<String,Object> iconSource(String id,String icon) throws IOException {
        var item=library.snapshot().item(id).orElseThrow(()->new ThemeLibrary.Conflict("Theme not found"));
        var contents=item.contents();var selected=contents.manifest().icons().stream().filter(i->i.id().equals(icon)).findFirst().orElseThrow(()->new IllegalArgumentException("Icon not found"));
        byte[] bytes=contents.file(selected.file());
        try{var info=selected.file().endsWith(".svg")?ThemeIconAsset.svgInfo(bytes):ThemeIconAsset.pngInfo(bytes);return Map.of("data",Base64.getEncoder().encodeToString(bytes),"canRecolor",info.supported(),"color",info.color());}
        catch(Exception e){throw new IllegalArgumentException("Could not read icon artwork");}
    }
    private static String optional(String value){return value==null||value.isBlank()?null:value.strip();}
    private static String patch(String version){
        var parts=version.split("\\.");String next=parts[0]+"."+parts[1]+"."+new java.math.BigInteger(parts[2]).add(java.math.BigInteger.ONE);
        return next.length()<=40?next:version;
    }
    private static String digest(ThemePackage.Contents source) throws IOException {return ThemePackage.sha256(ThemeArchive.write(source,false));}
    byte[] archive(Draft draft,boolean guide) throws IOException {
        if(draft==null||draft.sourceId()==null||draft.drawing()==null||draft.icons()==null||draft.icons().isEmpty()||draft.icons().size()>32)
            throw new IllegalArgumentException("Complete the theme and choose 1 to 32 icons");
        var item=library.snapshot().item(draft.sourceId()).orElseThrow(()->new ThemeLibrary.Conflict("The source theme was removed; reopen the editor"));
        var source=item.contents();var m=ThemeCredits.prepare(source,false).manifest();
        if(!Objects.equals(draft.sourceDigest(),digest(source)))throw new ThemeLibrary.Conflict("The source theme changed; keep this draft open and reopen the theme in another tab to compare");
        var icons=new ArrayList<ThemePackage.Icon>();var artwork=new HashMap<String,byte[]>();
        for(var icon:draft.icons()){
            if(icon==null)throw new IllegalArgumentException("Choose a theme icon");
            String file=icon.file();
            if(icon.data()!=null){
                if(file==null||!(file.endsWith(".png")||file.endsWith(".svg"))||icon.data().length()>4*((ThemeIconAsset.MAX_BYTES+2)/3))
                    throw new IllegalArgumentException("Choose a PNG or safe SVG of at most 128 KiB");
                byte[] bytes;try{bytes=Base64.getDecoder().decode(icon.data());}catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid icon data");}
                if(bytes.length>ThemeIconAsset.MAX_BYTES)throw new IllegalArgumentException("Choose an icon of at most 128 KiB");
                file="assets/editor-"+ThemePackage.sha256(bytes)+(file.endsWith(".svg")?".svg":".png");artwork.put(file,bytes);
            }else if(m.icons().stream().noneMatch(i->i.file().equals(icon.file())))throw new IllegalArgumentException("Choose an existing icon file or upload an image");
            String creator=optional(icon.author()),license=optional(icon.license());
            var previous=m.icons().stream().filter(i->i.file().equals(icon.file())).findFirst();
            if(previous.isPresent()&&previous.get().author()!=null&&(previous.get().author().contains("Twemoji")||previous.get().author().equals("VibeDock contributors"))){
                String inherited=previous.get().author();creator=creator==null||creator.contains(inherited)?inherited:inherited+"; "+creator;
                license=previous.get().license();
            }
            icons.add(new ThemePackage.Icon(icon.id(),icon.label(),file,creator,license));
        }
        var manifest=new ThemePackage.Manifest(draft.id(),draft.version(),draft.name(),draft.author(),draft.license(),new ThemePackage.Renderer(ThemeDefinition.RENDERER,draft.drawing().path("version").intValue()),m.definition(),draft.defaultIcon(),icons,null,m.files());
        byte[] result=ThemeArchive.edited(source,manifest,JSON.writeValueAsBytes(draft.drawing()),artwork,guide);
        // Reuse the full import validator, including every graphic and all format/size/integrity limits.
        result=ThemeArchive.write(ThemeCredits.prepare(ThemePackage.read(result),false),guide);library.inspect(result);return result;
    }
    ThemeLibrary.Item save(Draft draft) throws IOException {
        // The revision check and installation share the import lock: another editor cannot silently overwrite a save.
        synchronized(library){
            byte[] bytes=archive(draft,false);ThemeCredits.requireCreators(ThemePackage.read(bytes));
            return library.install(bytes,draft.id().equals(draft.sourceId())?"update":"add",null,null);
        }
    }
}
