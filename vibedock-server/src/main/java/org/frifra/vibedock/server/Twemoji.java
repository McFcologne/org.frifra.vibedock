package org.frifra.vibedock.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Colour emoji for the status image. Java2D cannot draw colour emoji fonts, so the Twemoji set (CC-BY 4.0, bundled in
 * the Docker image) is used instead: the SVG files, drawn sharp at any size (high-resolution web displays); a
 * directory of the PNG files (72 x 72) works as well. "-" or an empty value means no emoji.
 */
@Component
public class Twemoji {
    static final int SIZE=44;
    static final String NONE="-";
    private final Path directory;
    private final Map<String,Optional<BufferedImage>> cache=new ConcurrentHashMap<>();
    /** One emoji of the installed set: the text to store, its PNG file name and a searchable Unicode name. */
    public record Entry(String emoji,String file,String name) {}
    /** Largest size drawn (web displays at high resolution). */
    static final int MAX_SIZE=512;
    private static final java.util.regex.Pattern FILE=java.util.regex.Pattern.compile("[0-9a-f]{1,6}(-[0-9a-f]{1,6}){0,10}\\.(svg|png)");
    private volatile List<Entry> catalog;

    public Twemoji(@Value("${vibedock.emoji-dir:/app/twemoji/svg}") String directory){this.directory=Path.of(directory);}
    /** The file of an emoji name: the SVG, else the PNG. */
    private Optional<Path> file(String name){
        for(String extension:List.of(".svg",".png")){var file=directory.resolve(name+extension);if(Files.isRegularFile(file))return Optional.of(file);}
        return Optional.empty();
    }

    /** Twemoji file names for an emoji: code points in hex joined by "-"; U+FE0F is dropped unless the sequence has a ZWJ. */
    static List<String> fileNames(String emoji){
        int[] points=emoji.strip().codePoints().toArray();
        String full=hex(Arrays.stream(points));
        String plain=hex(Arrays.stream(points).filter(c->c!=0xfe0f));
        boolean zwj=Arrays.stream(points).anyMatch(c->c==0x200d);
        return (zwj?List.of(full,plain):List.of(plain,full)).stream().distinct().toList();
    }
    private static String hex(java.util.stream.IntStream points){
        return points.mapToObj(Integer::toHexString).collect(Collectors.joining("-"));
    }

    /** All emoji of the installed set in code point order, without skin-tone variants. */
    public List<Entry> catalog(){
        var result=catalog;
        if(result==null){result=buildCatalog();if(!result.isEmpty())catalog=result;}
        return result;
    }
    /** The catalog entry for an emoji, if the installed set has it. */
    public Optional<Entry> entry(String emoji){
        if(emoji==null||emoji.isBlank()||emoji.length()>32)return Optional.empty();
        return fileNames(emoji).stream().filter(name->file(name).isPresent()).findFirst()
                .map(name->new Entry(emoji.strip(),name,describe(name)));
    }
    /** The PNG of one emoji for the admin picker (56 pixels from SVG); only names made of hex code points are served. */
    public Optional<byte[]> png(String file){
        if(file==null||!file.matches("[0-9a-f]{1,6}(-[0-9a-f]{1,6}){0,10}"))return Optional.empty();
        var path=file(file);if(path.isEmpty())return Optional.empty();
        try{
            if(path.get().toString().endsWith(".png"))return Optional.of(Files.readAllBytes(path.get()));
            var image=draw(path.get(),PICKER_SIZE);if(image.isEmpty())return Optional.empty();
            var out=new java.io.ByteArrayOutputStream();ImageIO.write(image.get(),"png",out);return Optional.of(out.toByteArray());
        }catch(Exception e){return Optional.empty();}
    }
    static final int PICKER_SIZE=56;
    private List<Entry> buildCatalog(){
        if(!Files.isDirectory(directory))return List.of();
        try(var files=Files.list(directory)){
            return files.map(f->f.getFileName().toString()).filter(n->FILE.matcher(n).matches())
                    .map(n->n.substring(0,n.length()-4)).distinct().map(Twemoji::codePoints)
                    .filter(points->Arrays.stream(points).noneMatch(c->c>=0x1f3fb&&c<=0x1f3ff))
                    .sorted(Arrays::compare)
                    .map(points->{String file=hex(Arrays.stream(points));return new Entry(new String(points,0,points.length),file,describe(file));})
                    .toList();
        }catch(Exception e){return List.of();}
    }
    private static int[] codePoints(String file){return Arrays.stream(file.split("-")).mapToInt(h->Integer.parseInt(h,16)).toArray();}
    /** Lower-case Unicode names of the visible code points, e.g. "rocket" or "regional indicator symbol letter d ...". */
    static String describe(String file){
        int[] points=codePoints(file);
        // Flags are pairs of regional indicators: name them by their country code, e.g. "flag de".
        if(points.length==2&&Arrays.stream(points).allMatch(c->c>=0x1f1e6&&c<=0x1f1ff))
            return "flag "+(char)('a'+points[0]-0x1f1e6)+(char)('a'+points[1]-0x1f1e6);
        return Arrays.stream(points).filter(c->c!=0xfe0f&&c!=0x200d)
                .mapToObj(Character::getName).filter(Objects::nonNull).map(n->n.toLowerCase(Locale.ROOT)).collect(Collectors.joining(" "));
    }

    /** The emoji scaled to SIZE pixels, or empty for none or unknown. */
    public Optional<BufferedImage> image(String emoji){return image(emoji,SIZE);}
    public Optional<BufferedImage> image(String emoji,int size){
        if(emoji==null||emoji.isBlank()||emoji.equals(NONE)||emoji.length()>32||size<8||size>MAX_SIZE)return Optional.empty();
        if(cache.size()>128)cache.clear();
        return cache.computeIfAbsent(size+":"+emoji.strip(),key->load(emoji.strip(),size));
    }
    /** Accepts empty, "-" and every emoji of the installed set. */
    public void validate(String emoji){
        if(emoji==null||emoji.isBlank()||emoji.strip().equals(NONE))return;
        if(!Files.isDirectory(directory))throw new IllegalArgumentException("The emoji set is not installed ("+directory+")");
        if(emoji.strip().length()>32||image(emoji).isEmpty())throw new IllegalArgumentException("Unknown emoji: "+emoji.strip());
    }
    private Optional<BufferedImage> load(String emoji,int size){
        for(String name:fileNames(emoji)){
            var file=file(name);
            if(file.isPresent())return draw(file.get(),size);
        }
        return Optional.empty();
    }
    /** One file at size x size pixels: SVG drawn at that size, PNG scaled (not above its own size). */
    private static Optional<BufferedImage> draw(Path file,int size){
        try{
            var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
            try{
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
                if(file.toString().endsWith(".svg")){
                    var document=new com.github.weisj.jsvg.parser.SVGLoader().load(file.toUri().toURL());
                    if(document==null)return Optional.empty();
                    document.render((java.awt.Component)null,g,new com.github.weisj.jsvg.view.ViewBox(0,0,size,size));
                }else{
                    BufferedImage source=ImageIO.read(file.toFile());
                    if(source==null)return Optional.empty();
                    g.drawImage(source.getScaledInstance(size,size,Image.SCALE_AREA_AVERAGING),0,0,null);
                }
            }finally{g.dispose();}
            return Optional.of(image);
        }catch(Exception e){return Optional.empty();}
    }
}
