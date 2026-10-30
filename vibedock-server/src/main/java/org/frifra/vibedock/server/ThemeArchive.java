package org.frifra.vibedock.server;

import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;

/** Small deterministic DEFLATE archives. Authoring references are inert optional data, never an instruction source. */
final class ThemeArchive {
    private static final JsonMapper JSON=new JsonMapper();
    private static final Set<String> GUIDES=Set.of("authoring/agent.txt","authoring/drawing.schema.json","authoring/package.schema.json");
    private ThemeArchive() {}
    static byte[] write(ThemePackage.Contents source,boolean guide) throws IOException {return write(source,guide,null,null);}
    static byte[] write(ThemePackage.Contents source,boolean guide,String copyId,String copyName) throws IOException {
        var files=new TreeMap<String,byte[]>();
        for(var path:source.paths())files.put(path,source.file(path));
        return encode(source.manifest(),files,guide,copyId,copyName);
    }
    static byte[] edited(ThemePackage.Contents source,ThemePackage.Manifest manifest,byte[] drawing,Map<String,byte[]> artwork,boolean guide) throws IOException {
        var files=new TreeMap<String,byte[]>();
        for(var path:source.paths())files.put(path,source.file(path));
        files.put(manifest.definition(),drawing);files.putAll(artwork);
        var referenced=new HashSet<String>();manifest.icons().forEach(i->referenced.add(i.file()));referenced.add(manifest.definition());
        files.keySet().removeIf(path->path.matches("assets/editor-[0-9a-f]{64}\\.(png|svg)")&&!referenced.contains(path));
        return encode(manifest,files,guide,null,null);
    }
    static byte[] preview(ThemePackage.Contents source,byte[] png,boolean guide) throws IOException {
        var m=source.manifest();String path="preview/display-"+ThemePackage.sha256(png)+".png";
        var manifest=new ThemePackage.Manifest(m.id(),m.version(),m.name(),m.author(),m.license(),m.renderer(),m.definition(),m.defaultIcon(),m.icons(),path,m.files());
        var files=new TreeMap<String,byte[]>();for(String name:source.paths())files.put(name,source.file(name));
        files.put(path,png);files.put(ThemePreviewPage.FILE,new byte[0]);return encode(manifest,files,guide,null,null);
    }
    private static byte[] encode(ThemePackage.Manifest manifest,TreeMap<String,byte[]> files,boolean guide,String copyId,String copyName) throws IOException {
        var runtime=new HashSet<String>();runtime.add(manifest.definition());if(manifest.preview()!=null)runtime.add(manifest.preview());
        manifest.icons().forEach(icon->runtime.add(icon.file()));
        files.keySet().removeIf(path->!runtime.contains(path)&&(GUIDES.contains(path)||path.endsWith(".png")||path.endsWith(".svg")));
        if(files.containsKey(ThemePreviewPage.FILE))files.put(ThemePreviewPage.FILE,ThemePreviewPage.page(manifest));
        // Only JSON whitespace is removed; images, artwork, attribution and license bytes stay intact.
        files.put(manifest.definition(),JSON.writeValueAsBytes(JSON.readTree(files.get(manifest.definition()))));
        if(guide){
            if(runtime.stream().anyMatch(GUIDES::contains))throw new IllegalArgumentException("Rename runtime files that use reserved authoring paths before including the guide");
            files.put("authoring/agent.txt",resource("agent.txt"));
            for(String name:List.of("drawing.schema.json","package.schema.json"))files.put("authoring/"+name,JSON.writeValueAsBytes(JSON.readTree(resource(name))));
        }
        var root=new LinkedHashMap<String,Object>();root.put("format",ThemePackage.FORMAT);root.put("schemaVersion",ThemePackage.VERSION);
        root.put("id",copyId==null?manifest.id():copyId);root.put("version",manifest.version());root.put("name",copyName==null?manifest.name():copyName);
        root.put("author",manifest.author());root.put("license",manifest.license());root.put("renderer",manifest.renderer());
        root.put("definition",manifest.definition());root.put("defaultIcon",manifest.defaultIcon());root.put("icons",manifest.icons());
        if(manifest.preview()!=null)root.put("preview",manifest.preview());
        var hashes=new TreeMap<String,String>();files.forEach((path,bytes)->hashes.put(path,ThemePackage.sha256(bytes)));root.put("files",hashes);
        byte[] description=JSON.writeValueAsBytes(root);ThemePackage.manifest(description);
        var output=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(output)){
            zip.setLevel(Deflater.BEST_COMPRESSION);entry(zip,"theme.json",description);
            for(var file:files.entrySet())entry(zip,file.getKey(),file.getValue());
        }
        byte[] archive=output.toByteArray();ThemePackage.read(archive);return archive;
    }
    private static byte[] resource(String name) throws IOException {
        try(var stream=ThemeArchive.class.getResourceAsStream("/themes/authoring/"+name)){
            if(stream==null)throw new IOException("Missing bundled authoring reference");return stream.readNBytes(ThemePackage.MAX_FILE+1);
        }
    }
    private static void entry(ZipOutputStream out,String path,byte[] bytes) throws IOException {
        var entry=new ZipEntry(path);entry.setTimeLocal(LocalDateTime.of(1980,1,1,0,0));out.putNextEntry(entry);out.write(bytes);out.closeEntry();
    }
}
