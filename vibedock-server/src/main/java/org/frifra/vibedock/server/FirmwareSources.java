package org.frifra.vibedock.server;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Where the server gets client firmware from: GitHub repositories whose releases carry the files of
 * {@code vibedock-client/scripts/release-index.py} (an {@code index.json} and the images). The list is
 * {@code firmware-sources.yaml} in the data directory; without it only the official source is used. The order is the
 * priority: for each board the first source with compatible firmware wins.
 * <pre>
 * sources:
 *   - id: vibedock                       # a-z, 0-9, "-"; names the cache directory
 *     label: VibeDock (official)
 *     repo: McFcologne/org.frifra.vibedock
 *     tagPrefix: client-v                # releases whose tag starts so; the rest is the version
 *     trusted: true                      # false: only for USB flashing, never for updates over Wi-Fi
 *     enabled: true
 *     boards: [esp32-cyd]                # optional: only these boards
 * </pre>
 * Only {@code id}, {@code repo} and {@code tagPrefix} are required. An alternative source is code the server hands
 * to displays, so it is {@code trusted: false} until the administrator, who edits the file, says otherwise.
 */
public final class FirmwareSources {
    public static final int MAX_SOURCES=8;
    private static final Pattern ID=Pattern.compile("[a-z0-9-]{1,40}"),REPO=Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/(?!\\.{1,2}$)[A-Za-z0-9_.-]{1,100}"),
            PREFIX=Pattern.compile("[A-Za-z0-9._-]{0,40}"),BOARD=Pattern.compile("[a-z0-9-]{1,40}");
    private static final Set<String> KEYS=Set.of("id","label","repo","tagPrefix","trusted","enabled","boards");
    /** boards: empty means every board the source offers. */
    public record Source(String id,String label,String repo,String tagPrefix,boolean trusted,boolean enabled,Set<String> boards) {
        public boolean offers(String board){return boards.isEmpty()||boards.contains(board);}
    }
    public static final Source OFFICIAL=new Source("vibedock","VibeDock (official)","McFcologne/org.frifra.vibedock","client-v",true,true,Set.of());
    private FirmwareSources(){}

    /** The file's sources, or the official one when there is no file. A file with a mistake is an error that names it. */
    public static List<Source> load(Path file){
        if(file==null||!Files.isRegularFile(file))return List.of(OFFICIAL);
        try{
            if(Files.size(file)>64*1024)throw new IllegalArgumentException("larger than 64 KB");
            return parse(Files.readString(file,StandardCharsets.UTF_8));
        }catch(IOException e){throw new IllegalArgumentException(file.getFileName()+": cannot be read");}
        catch(IllegalArgumentException e){throw new IllegalArgumentException(file.getFileName()+": "+e.getMessage());}
    }

    public static List<Source> parse(String yaml){
        Object root;
        try{
            var options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);options.setCodePointLimit(64*1024);
            root=new Yaml(new SafeConstructor(options)).load(yaml);
        }catch(RuntimeException e){throw new IllegalArgumentException("not valid YAML");}
        if(!(root instanceof Map<?,?> top)||!top.keySet().equals(Set.of("sources"))||!(top.get("sources") instanceof List<?> list))
            throw new IllegalArgumentException("expected one key, sources, with a list");
        if(list.isEmpty()||list.size()>MAX_SOURCES)throw new IllegalArgumentException("1 to "+MAX_SOURCES+" sources");
        var out=new ArrayList<Source>();var ids=new HashSet<String>();
        for(int i=0;i<list.size();i++){
            String at="source "+(i+1)+": ";
            if(!(list.get(i) instanceof Map<?,?> m))throw new IllegalArgumentException(at+"not a mapping");
            for(var k:m.keySet())if(!(k instanceof String s)||!KEYS.contains(s))throw new IllegalArgumentException(at+"unknown key "+k);
            String id=text(m,"id",at,ID,"a-z, 0-9 and - (1-40)",true);
            if(!ids.add(id))throw new IllegalArgumentException(at+"id "+id+" is used twice");
            String repo=text(m,"repo",at,REPO,"owner/name",true),prefix=text(m,"tagPrefix",at,PREFIX,"letters, digits, . _ -",true);
            String label=m.get("label")==null?id:text(m,"label",at,Pattern.compile(".{1,80}"),"1-80 characters",false);
            if(label.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException(at+"label has control characters");
            var boards=new LinkedHashSet<String>();
            if(m.get("boards")!=null){
                if(!(m.get("boards") instanceof List<?> b)||b.size()>64)throw new IllegalArgumentException(at+"boards is a list of up to 64 ids");
                for(var x:b){
                    if(!(x instanceof String s)||!BOARD.matcher(s).matches())throw new IllegalArgumentException(at+"board id "+x+" is not valid");
                    boards.add(s);
                }
            }
            out.add(new Source(id,label,repo,prefix,flag(m,"trusted",at,false),flag(m,"enabled",at,true),Set.copyOf(boards)));
        }
        return List.copyOf(out);
    }
    private static String text(Map<?,?> m,String key,String at,Pattern form,String shape,boolean required){
        Object v=m.get(key);
        if(v==null){if(required)throw new IllegalArgumentException(at+key+" is missing");return null;}
        if(!(v instanceof String s)||!form.matcher(s).matches())throw new IllegalArgumentException(at+key+" must be "+shape);
        return s;
    }
    private static boolean flag(Map<?,?> m,String key,String at,boolean fallback){
        Object v=m.get(key);
        if(v==null)return fallback;
        if(!(v instanceof Boolean b))throw new IllegalArgumentException(at+key+" must be true or false");
        return b;
    }
}
