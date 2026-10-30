package org.frifra.vibedock.server;

import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Read the bundled directories through the same archive/definition validators used for portable themes. */
final class BuiltinThemes {
    record Loaded(ThemePackage.Contents contents,ThemeDefinition definition) {}
    private static final Map<String,Loaded> THEMES=load();
    private BuiltinThemes() {}
    static Loaded get(String id){var theme=THEMES.get(id);if(theme==null)throw new IllegalArgumentException("Missing built-in theme: "+id);return theme;}
    static List<String> ids(){return List.copyOf(THEMES.keySet());}
    private static Map<String,Loaded> load(){
        try{
            String[] ids=new JsonMapper().readValue(resource("/themes/builtin/catalog.json",8192),String[].class);
            if(ids.length==0||ids.length>64)throw new IOException("Invalid built-in catalog size");
            var themes=new LinkedHashMap<String,Loaded>();
            for(String id:ids){
                if(id==null||!id.matches("[a-z][a-z0-9._-]{0,79}")||themes.containsKey(id))throw new IOException("Invalid or duplicate built-in theme ID");
                String root="/themes/builtin/"+id+"/";
                byte[] manifest=resource(root+"theme.json",ThemePackage.MAX_MANIFEST);
                var parsed=ThemePackage.manifest(manifest);
                if(!parsed.id().equals(id))throw new IOException("Built-in theme ID mismatch");
                var bytes=new ByteArrayOutputStream();
                try(var zip=new ZipOutputStream(bytes)){
                    entry(zip,"theme.json",manifest);
                    int total=manifest.length;
                    for(var path:new TreeSet<>(parsed.files().keySet())){
                        byte[] file=resource(root+path,ThemePackage.MAX_FILE);total+=file.length;
                        if(total>ThemePackage.MAX_EXPANDED)throw new IOException("Built-in theme exceeds expanded limit");
                        entry(zip,path,file);
                    }
                }
                var contents=ThemePackage.read(bytes.toByteArray());
                themes.put(id,new Loaded(contents,ThemeDefinition.read(contents)));
            }
            return Collections.unmodifiableMap(themes);
        }catch(IOException|IllegalArgumentException e){throw new ExceptionInInitializerError(e);}
    }
    private static byte[] resource(String path,int limit) throws IOException {
        try(var stream=BuiltinThemes.class.getResourceAsStream(path)){
            if(stream==null)throw new IOException("Missing built-in resource: "+path);
            byte[] bytes=stream.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("Oversized built-in resource: "+path);return bytes;
        }
    }
    private static void entry(ZipOutputStream out,String path,byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(path));out.write(bytes);out.closeEntry();
    }
}
