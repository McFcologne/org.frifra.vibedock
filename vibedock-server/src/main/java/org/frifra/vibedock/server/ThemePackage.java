package org.frifra.vibedock.server;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

/**
 * The bounded, self-contained envelope of a .vibetheme ZIP. This checks the manifest and file integrity only:
 * the named renderer must validate the definition and graphics before a package may be installed or drawn.
 * Reading never extracts files, changes a design, fetches resources or executes package content.
 */
final class ThemePackage {
    static final String FORMAT="org.frifra.vibedock.theme";
    static final int VERSION=1,MAX_ARCHIVE=8*1024*1024,MAX_EXPANDED=8*1024*1024,
            MAX_FILE=2*1024*1024,MAX_MANIFEST=64*1024,MAX_ENTRIES=64;
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Pattern ID=Pattern.compile("[a-z][a-z0-9._-]{0,79}");
    private static final Pattern SEMVER=Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");
    record Renderer(String id,int version) {}
    record Icon(String id,String label,String file) {}
    record Manifest(String id,String version,String name,String author,String license,Renderer renderer,
                    String definition,String defaultIcon,List<Icon> icons,String preview,Map<String,String> files) {
        Manifest {icons=List.copyOf(icons);files=Map.copyOf(files);}
    }
    /** File arrays are private: callers cannot change bytes after their digest was checked. */
    static final class Contents {
        private final Manifest manifest;private final Map<String,byte[]> files;
        private Contents(Manifest manifest,Map<String,byte[]> files){this.manifest=manifest;this.files=Map.copyOf(files);}
        Manifest manifest(){return manifest;}
        Set<String> paths(){return files.keySet();}
        byte[] file(String path){var bytes=files.get(path);if(bytes==null)throw invalid("File not found: "+path);return bytes.clone();}
    }
    private ThemePackage() {}

    static Contents read(byte[] archive) throws IOException {
        if(archive==null||archive.length==0||archive.length>MAX_ARCHIVE)throw invalid("Choose a theme archive of at most 8 MiB");
        Map<String,byte[]> entries=new LinkedHashMap<>();Set<String> names=new HashSet<>();int total=0,count=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(archive))){
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()){
                if(++count>MAX_ENTRIES)throw invalid("At most 64 ZIP entries");
                String name=entry.getName();boolean directory=entry.isDirectory();
                String path=directory?name.substring(0,name.length()-1):name;
                path(path);
                if(!names.add(path))throw invalid("Duplicate ZIP path: "+path);
                if(directory){if(zip.read()!=-1)throw invalid("Directory contains data");continue;}
                int limit=path.equals("theme.json")?MAX_MANIFEST:MAX_FILE;
                byte[] bytes=zip.readNBytes(Math.min(limit,MAX_EXPANDED-total)+1);
                if(bytes.length>limit||(total+=bytes.length)>MAX_EXPANDED)throw invalid("Theme exceeds its expanded size limit");
                entries.put(path,bytes);
            }
        }
        byte[] description=entries.remove("theme.json");
        if(description==null)throw invalid("The archive needs theme.json at its root");
        Manifest manifest=manifest(description);
        if(!entries.keySet().equals(manifest.files().keySet()))throw invalid("ZIP files must match the manifest exactly");
        for(var entry:entries.entrySet())
            if(!sha256(entry.getValue()).equals(manifest.files().get(entry.getKey())))throw invalid("Checksum mismatch: "+entry.getKey());
        return new Contents(manifest,entries);
    }

    static Manifest manifest(byte[] bytes) throws IOException {
        if(bytes==null||bytes.length==0||bytes.length>MAX_MANIFEST)throw invalid("Manifest exceeds 64 KiB or is empty");
        JsonNode root=JSON.readTree(bytes);
        fields(root,Set.of("format","schemaVersion","id","version","name","author","license","renderer","definition","defaultIcon","icons","preview","files"));
        if(!FORMAT.equals(text(root,"format",80))||integer(root,"schemaVersion")!=VERSION)throw invalid("Unsupported theme package format or schema version");
        String id=identifier(text(root,"id",80)),version=text(root,"version",40);
        if(!SEMVER.matcher(version).matches())throw invalid("Theme version must be major.minor.patch");
        String name=text(root,"name",80),author=text(root,"author",160),license=text(root,"license",160);
        JsonNode renderer=root.get("renderer");fields(renderer,Set.of("id","version"));
        var engine=new Renderer(identifier(text(renderer,"id",80)),integer(renderer,"version"));
        if(engine.version()<1)throw invalid("Renderer version must be positive");
        String definition=path(text(root,"definition",160));
        if(!definition.endsWith(".json"))throw invalid("Definition must be a JSON file");
        String defaultIcon=identifier(text(root,"defaultIcon",80));
        JsonNode list=root.get("icons");
        if(list==null||!list.isArray()||list.isEmpty()||list.size()>32)throw invalid("A theme needs 1 to 32 icons");
        var icons=new ArrayList<Icon>();Set<String> iconIds=new HashSet<>();
        for(var item:list){
            fields(item,Set.of("id","label","file"));
            String iconId=identifier(text(item,"id",80)),file=image(text(item,"file",160));
            if(!iconIds.add(iconId))throw invalid("Duplicate icon id: "+iconId);
            icons.add(new Icon(iconId,text(item,"label",80),file));
        }
        if(!iconIds.contains(defaultIcon))throw invalid("Default icon must name an icon in this theme");
        String preview=root.has("preview")?image(text(root,"preview",160)):null;
        JsonNode fileMap=root.get("files");
        if(fileMap==null||!fileMap.isObject()||fileMap.isEmpty()||fileMap.size()>MAX_ENTRIES-1)throw invalid("A theme needs 1 to 63 declared files");
        Map<String,String> files=new LinkedHashMap<>();
        for(var file:fileMap.properties()){
            String path=path(file.getKey());
            if(path.equals("theme.json")||!path.matches(".*\\.(json|png|svg|txt)"))throw invalid("Unsupported package file: "+path);
            if(!file.getValue().isTextual()||!file.getValue().textValue().matches("[0-9a-f]{64}"))throw invalid("Expected a SHA-256 checksum for "+path);
            files.put(path,file.getValue().textValue());
        }
        if(!files.containsKey(definition)||preview!=null&&!files.containsKey(preview)||icons.stream().anyMatch(i->!files.containsKey(i.file())))
            throw invalid("Every definition, preview and icon must name a declared file");
        return new Manifest(id,version,name,author,license,engine,definition,defaultIcon,icons,preview,files);
    }
    private static String image(String value){path(value);if(!value.endsWith(".png")&&!value.endsWith(".svg"))throw invalid("Image must be PNG or SVG");return value;}
    private static String identifier(String value){if(!ID.matcher(value).matches())throw invalid("Invalid identifier: "+value);return value;}
    private static String path(String value){
        if(!value.matches("[a-z0-9][a-z0-9._/-]{0,159}"))throw invalid("Invalid package path");
        for(String part:value.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw invalid("Invalid package path");
        return value;
    }
    private static String text(JsonNode node,String field,int max){
        var value=node.get(field);
        if(value==null||!value.isTextual())throw invalid("Expected text: "+field);
        String text=value.textValue();
        if(text.isBlank()||text.codePointCount(0,text.length())>max||text.codePoints().anyMatch(Character::isISOControl))throw invalid("Invalid text: "+field);
        return text;
    }
    private static int integer(JsonNode node,String field){
        var value=node.get(field);if(value==null||!value.isNumber())throw invalid("Expected integer: "+field);
        try{return value.decimalValue().intValueExact();}
        catch(ArithmeticException e){throw invalid("Expected integer: "+field);}
    }
    private static void fields(JsonNode node,Set<String> allowed){
        if(node==null||!node.isObject())throw invalid("Expected a JSON object");
        for(var field:node.properties())if(!allowed.contains(field.getKey()))throw invalid("Unknown property: "+field.getKey());
    }
    static String sha256(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException(reason);}
}
