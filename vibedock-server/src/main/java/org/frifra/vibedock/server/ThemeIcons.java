package org.frifra.vibedock.server;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/** Package-owned icon IDs and safe rasters. Availability of a theme for swipes does not invalidate its fixed icons. */
final class ThemeIcons {
    record Entry(String theme,String themeLabel,String id,String label,boolean themeDefault,String image) {}
    private static final Map<String,Optional<BufferedImage>> CACHE=new ConcurrentHashMap<>();
    private ThemeIcons() {}
    static List<Entry> catalog(){
        return catalog(BuiltinThemes.ids().stream().map(id->BuiltinThemes.get(id).contents()).toList());
    }
    static List<Entry> catalog(Collection<ThemePackage.Contents> packages){
        return packages.stream().flatMap(contents->{var m=contents.manifest();
            return m.icons().stream().map(icon->new Entry(m.id(),m.name(),icon.id(),icon.label(),icon.id().equals(m.defaultIcon()),url(m.id(),icon.id())));
        }).toList();
    }
    private static String url(String theme,String icon){return "/admin/themes/"+theme+"/icons/"+icon+".png";}
    static boolean available(Design.Icon icon,Scheme scheme){return image(icon,scheme,Twemoji.SIZE).isPresent();}
    static Optional<BufferedImage> image(Design.Icon selection,Scheme scheme,int size){
        return image(selection,scheme,size,id->BuiltinThemes.ids().contains(id)?Optional.of(BuiltinThemes.get(id).contents()):Optional.empty());
    }
    static Optional<BufferedImage> image(Design.Icon selection,Scheme scheme,int size,java.util.function.Function<String,Optional<ThemePackage.Contents>> packages){
        if(selection==null)return Optional.empty();
        String theme=selection.mode().equals("theme-default")?scheme.id():selection.theme();
        var found=packages.apply(theme);if(found.isEmpty())return Optional.empty();var contents=found.get();
        String id=selection.mode().equals("theme-default")?contents.manifest().defaultIcon():selection.id();
        return image(contents,id,size);
    }
    static Optional<BufferedImage> image(String theme,String id,int size){
        if(size<8||size>Twemoji.MAX_SIZE||theme==null||!BuiltinThemes.ids().contains(theme))return Optional.empty();
        return image(BuiltinThemes.get(theme).contents(),id,size);
    }
    static Optional<BufferedImage> image(ThemePackage.Contents contents,String id,int size){
        if(size<8||size>Twemoji.MAX_SIZE)return Optional.empty();
        var entry=contents.manifest().icons().stream().filter(i->i.id().equals(id)).findFirst();
        if(entry.isEmpty())return Optional.empty();String path=entry.get().file();
        String key=size+":"+path.substring(path.lastIndexOf('.'))+":"+contents.manifest().files().get(path);
        if(CACHE.size()>256)CACHE.clear();
        return CACHE.computeIfAbsent(key,ignored->{
            try{return Optional.of(ThemeIconAsset.draw(path,contents.file(path),size));}
            catch(Exception e){return Optional.empty();}
        });
    }
    static Optional<byte[]> png(String theme,String id){
        return png(image(theme,id,Twemoji.PICKER_SIZE));
    }
    static Optional<byte[]> png(Optional<BufferedImage> image){
        return image.map(raster->{
            try{var bytes=new ByteArrayOutputStream();ImageIO.write(raster,"png",bytes);return bytes.toByteArray();}
            catch(IOException e){throw new UncheckedIOException(e);}
        });
    }
}
