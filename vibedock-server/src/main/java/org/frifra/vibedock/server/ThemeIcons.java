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
        return BuiltinThemes.ids().stream().flatMap(theme->{var m=BuiltinThemes.get(theme).contents().manifest();
            return m.icons().stream().map(icon->new Entry(theme,m.name(),icon.id(),icon.label(),icon.id().equals(m.defaultIcon()),url(theme,icon.id())));
        }).toList();
    }
    private static String url(String theme,String icon){return "/admin/themes/"+theme+"/icons/"+icon+".png";}
    static boolean available(Design.Icon icon,Scheme scheme){return image(icon,scheme,Twemoji.SIZE).isPresent();}
    static Optional<BufferedImage> image(Design.Icon selection,Scheme scheme,int size){
        if(selection==null)return Optional.empty();
        String theme=selection.mode().equals("theme-default")?scheme.id():selection.theme();
        if(!BuiltinThemes.ids().contains(theme))return Optional.empty();
        var contents=BuiltinThemes.get(theme).contents();
        String id=selection.mode().equals("theme-default")?contents.manifest().defaultIcon():selection.id();
        return image(theme,id,size);
    }
    static Optional<BufferedImage> image(String theme,String id,int size){
        if(size<8||size>Twemoji.MAX_SIZE||theme==null||!BuiltinThemes.ids().contains(theme))return Optional.empty();
        var contents=BuiltinThemes.get(theme).contents();var entry=contents.manifest().icons().stream().filter(i->i.id().equals(id)).findFirst();
        if(entry.isEmpty())return Optional.empty();String path=entry.get().file();
        String key=size+":"+path.substring(path.lastIndexOf('.'))+":"+contents.manifest().files().get(path);
        if(CACHE.size()>256)CACHE.clear();
        return CACHE.computeIfAbsent(key,ignored->{
            try{return Optional.of(ThemeIconAsset.draw(path,contents.file(path),size));}
            catch(Exception e){return Optional.empty();}
        });
    }
    static Optional<byte[]> png(String theme,String id){
        return image(theme,id,Twemoji.PICKER_SIZE).map(image->{
            try{var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();}
            catch(IOException e){throw new UncheckedIOException(e);}
        });
    }
}
