package org.frifra.vibedock.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Per-asset provenance. Never removes an unknown author's notices or infers ownership from a filename. */
final class ThemeCredits {
    private ThemeCredits() {}
    private record Credit(String author,String license) {}
    private static Credit known(byte[] bytes){
        String hash=ThemePackage.sha256(bytes);
        for(String id:BuiltinThemes.ids()){
            var contents=BuiltinThemes.get(id).contents();
            for(var icon:contents.manifest().icons())if(ThemePackage.sha256(contents.file(icon.file())).equals(hash))
                return icon.id().equals("legacy")?new Credit("Twemoji contributors","CC-BY-4.0"):new Credit("VibeDock contributors","Apache-2.0");
        }return null;
    }
    static void requireCreators(ThemePackage.Contents source){
        for(var icon:source.manifest().icons())if(icon.author()==null||icon.author().isBlank())
            throw new IllegalArgumentException("Enter the creator for icon '"+icon.label()+"' in the theme editor");
    }
    static ThemePackage.Contents prepare(ThemePackage.Contents source,boolean require) throws IOException {
        var m=source.manifest();var icons=new ArrayList<ThemePackage.Icon>();
        for(var icon:m.icons()){
            var credit=known(source.file(icon.file()));
            icons.add(new ThemePackage.Icon(icon.id(),icon.label(),icon.file(),
                    credit!=null?credit.author():icon.author(),
                    credit!=null?credit.license():icon.license()));
        }
        var files=new TreeMap<String,byte[]>();for(String path:source.paths())files.put(path,source.file(path));
        boolean managed=false;String matchedNotice=null;
        if(files.containsKey("license.txt")){
            String digest=ThemePackage.sha256(files.get("license.txt"));
            for(String id:BuiltinThemes.ids()){
                var builtin=BuiltinThemes.get(id).contents();
                if(builtin.paths().contains("license.txt")&&ThemePackage.sha256(builtin.file("license.txt")).equals(digest)){managed=true;matchedNotice=new String(builtin.file("license.txt"),StandardCharsets.UTF_8);break;}
            }
        }
        String original=new String(BuiltinThemes.get("midnight").contents().file("license.txt"),StandardCharsets.UTF_8);
        int split=original.indexOf("Unmodified default.svg: Twemoji");
        String apache=original.substring(0,split).replaceFirst("[^\\n]*", "VibeDock theme definition and retained artwork: Apache-2.0.");
        // Recognize our exact generated notice, not a prefix that could hide someone else's license text.
        if(files.containsKey("license.txt"))managed|=Arrays.equals(files.get("license.txt"),apache.getBytes(StandardCharsets.UTF_8));
        String author=m.author(),license=m.license();
        boolean twemoji=icons.stream().anyMatch(i->i.author()!=null&&i.author().contains("Twemoji"));
        if(managed){
            String retained=matchedNotice;
            if(twemoji&&retained==null){
                for(var icon:icons){var hash=ThemePackage.sha256(source.file(icon.file()));
                    for(String id:BuiltinThemes.ids()){
                        var builtin=BuiltinThemes.get(id).contents();
                        if(builtin.manifest().icons().stream().anyMatch(i->i.id().equals("legacy")&&ThemePackage.sha256(builtin.file(i.file())).equals(hash)))retained=new String(builtin.file("license.txt"),StandardCharsets.UTF_8);
                    }
                }
            }
            if(twemoji&&retained==null)retained=original.replaceAll("assets/svg/[^\\s]+","assets/svg/");
            files.put("license.txt",(twemoji?retained:apache).getBytes(StandardCharsets.UTF_8));
            if(author.equals("VibeDock contributors; Twemoji contributors (icon)"))author="VibeDock contributors";
            if(license.equals("Apache-2.0 AND CC-BY-4.0")||license.equals("Apache-2.0"))license=twemoji?"Apache-2.0 AND CC-BY-4.0":"Apache-2.0";
        }
        var manifest=new ThemePackage.Manifest(m.id(),m.version(),m.name(),author,license,m.renderer(),m.definition(),m.defaultIcon(),icons,m.preview(),m.files());
        if(!files.containsKey("credits.txt")||Arrays.equals(files.get("credits.txt"),credits(m)))files.put("credits.txt",credits(manifest));
        var result=ThemePackage.read(ThemeArchive.rebuild(manifest,files));if(require)requireCreators(result);return result;
    }
    static boolean generatedCredits(ThemePackage.Contents source){
        return source.paths().contains("credits.txt")&&Arrays.equals(source.file("credits.txt"),credits(source.manifest()));
    }
    private static byte[] credits(ThemePackage.Manifest manifest){
        StringBuilder text=new StringBuilder("Theme: ").append(manifest.author()).append("\nTheme license: ").append(manifest.license()).append("\n\n");
        for(var icon:manifest.icons())text.append(icon.label()).append(" ("+icon.file()+")\nCreator: ").append(icon.author()==null?"Not specified":icon.author())
                .append("\nIcon license: ").append(icon.license()==null?"Not specified":icon.license()).append("\n\n");
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
}
