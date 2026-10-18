package org.frifra.vibedock.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The uploaded photos for photo designs (data/photos). Only a reduced copy is kept: at most {@value #MAX_SIDE} pixels
 * on its longer side, as JPEG, turned upright by its EXIF orientation; the original is not stored. For a display the
 * copy is cut to the display's shape (centred) and scaled when the image is fetched.
 */
@Service
class PhotoLibrary {
    static final Pattern ID=Pattern.compile("[0-9a-f]{16}");
    static final int MAX_SIDE=600,MAX_UPLOAD=30*1024*1024,MAX_PHOTOS=500;
    record Photo(String id,int width,int height,long addedAt) {}
    private final Path dir;private final SecureRandom random=new SecureRandom();
    /** Display images of a photo per display size, small enough to keep: a slideshow repeats them. */
    private final Map<String,StatusImage.Image> rendered=Collections.synchronizedMap(new LinkedHashMap<>(64,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,StatusImage.Image> e){return size()>64;}
    });
    PhotoLibrary(@Value("${vibedock.data-dir}") String dataDir){this.dir=Path.of(dataDir).resolve("photos");}

    synchronized List<Photo> list() throws IOException {
        if(!Files.isDirectory(dir))return List.of();
        var photos=new ArrayList<Photo>();
        try(var files=Files.list(dir)){
            for(Path p:files.filter(f->f.getFileName().toString().matches("[0-9a-f]{16}\\.jpg")).toList()){
                var size=size(p);photos.add(new Photo(p.getFileName().toString().substring(0,16),size.width,size.height,Files.getLastModifiedTime(p).toMillis()));
            }
        }
        photos.sort(Comparator.comparingLong(Photo::addedAt).thenComparing(Photo::id));
        return photos;
    }
    /** Adds a photo (JPEG, PNG, GIF or BMP): reduced, upright, as JPEG. */
    synchronized Photo add(byte[] upload) throws IOException {
        if(upload==null||upload.length==0)throw new IllegalArgumentException("Choose a photo");
        if(upload.length>MAX_UPLOAD)throw new IllegalArgumentException("The photo is larger than 30 MB");
        if(list().size()>=MAX_PHOTOS)throw new IllegalArgumentException("At most "+MAX_PHOTOS+" photos");
        BufferedImage image;
        try{image=ImageIO.read(new ByteArrayInputStream(upload));}catch(IOException|RuntimeException e){image=null;}
        if(image==null)throw new IllegalArgumentException("Not a photo this server can read (JPEG, PNG, GIF or BMP; not HEIC)");
        if((long)image.getWidth()*image.getHeight()>60_000_000L)throw new IllegalArgumentException("The photo has too many pixels");
        image=upright(image,exifOrientation(upload));
        double scale=Math.min(1,(double)MAX_SIDE/Math.max(image.getWidth(),image.getHeight()));
        int w=Math.max(1,(int)Math.round(image.getWidth()*scale)),h=Math.max(1,(int)Math.round(image.getHeight()*scale));
        var small=scaled(image,w,h);
        Files.createDirectories(dir);
        String id;do{id=HexFormat.of().formatHex(bytes(8));}while(Files.exists(file(id)));
        Path temporary=Files.createTempFile(dir,"photo-",".tmp");
        try{
            try(var out=Files.newOutputStream(temporary)){jpeg(small,out);}
            Files.move(temporary,file(id),StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(temporary);}
        return new Photo(id,w,h,Files.getLastModifiedTime(file(id)).toMillis());
    }
    synchronized boolean delete(String id) throws IOException {
        if(id==null||!ID.matcher(id).matches())return false;
        rendered.keySet().removeIf(k->k.startsWith(id));
        return Files.deleteIfExists(file(id));
    }
    /** The stored copy (for the editor's thumbnails). */
    Optional<byte[]> jpeg(String id) throws IOException {
        if(id==null||!ID.matcher(id).matches()||!Files.isRegularFile(file(id)))return Optional.empty();
        return Optional.of(Files.readAllBytes(file(id)));
    }
    boolean exists(String id){return id!=null&&ID.matcher(id).matches()&&Files.isRegularFile(file(id));}
    /** The photo cut to the display's shape (centred, filling it) as PNG and RGB565 like the status image. */
    Optional<StatusImage.Image> render(String id,DisplayType type) throws IOException {return render(id,type,1);}
    /** The same at scale times the type's pixels (web displays; PNG only, no RGB565). */
    Optional<StatusImage.Image> render(String id,DisplayType type,double scale) throws IOException {
        if(!exists(id))return Optional.empty();
        int w=(int)Math.round(type.width()*scale),h=(int)Math.round(type.height()*scale);
        String key=id+"/"+w+"x"+h+(scale==1?"":"/web");
        var cached=rendered.get(key);if(cached!=null)return Optional.of(cached);
        var photo=ImageIO.read(file(id).toFile());if(photo==null)return Optional.empty();
        var canvas=scaled(cover(photo,w,h),w,h);
        // Devices: RGB565, and the PNG shows what they show. Web displays get the photo in full colour.
        byte[] pixels=new byte[scale==1?w*h*2:0];int offset=0;
        if(scale==1)for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int rgb=canvas.getRGB(x,y),r=(rgb>>19)&31,green=(rgb>>10)&63,b=(rgb>>3)&31,value=(r<<11)|(green<<5)|b;
            pixels[offset++]=(byte)(value>>8);pixels[offset++]=(byte)value;
            canvas.setRGB(x,y,((r*255/31)<<16)|((green*255/63)<<8)|(b*255/31));
        }
        var png=new ByteArrayOutputStream();ImageIO.write(canvas,"png",png);
        var image=new StatusImage.Image(png.toByteArray(),pixels);rendered.put(key,image);
        return Optional.of(image);
    }

    private Path file(String id){return dir.resolve(id+".jpg");}
    private byte[] bytes(int n){byte[] b=new byte[n];random.nextBytes(b);return b;}
    private static Dimension size(Path p) throws IOException {
        try(var in=ImageIO.createImageInputStream(p.toFile())){
            var readers=ImageIO.getImageReaders(in);
            if(readers.hasNext()){var r=readers.next();try{r.setInput(in);return new Dimension(r.getWidth(0),r.getHeight(0));}finally{r.dispose();}}
        }
        return new Dimension(0,0);
    }
    /**
     * The centred part of the photo with the display's shape, before scaling: scaling the whole photo to cover the
     * display first would make a huge image of a very wide or tall photo (600 × 1 on 240 × 280: 168000 × 280).
     */
    static BufferedImage cover(BufferedImage photo,int w,int h){
        double cover=Math.max((double)w/photo.getWidth(),(double)h/photo.getHeight());
        int cw=Math.max(1,Math.min(photo.getWidth(),(int)Math.round(w/cover))),ch=Math.max(1,Math.min(photo.getHeight(),(int)Math.round(h/cover)));
        return photo.getSubimage((photo.getWidth()-cw)/2,(photo.getHeight()-ch)/2,cw,ch);
    }
    /** Scales in halving steps first: one bicubic step from a large photo to a small one looks coarse. */
    private static BufferedImage scaled(BufferedImage image,int w,int h){
        BufferedImage current=image;
        if(current.getType()!=BufferedImage.TYPE_INT_RGB){
            var rgb=new BufferedImage(current.getWidth(),current.getHeight(),BufferedImage.TYPE_INT_RGB);
            var g=rgb.createGraphics();g.setColor(Color.BLACK);g.fillRect(0,0,rgb.getWidth(),rgb.getHeight());g.drawImage(current,0,0,null);g.dispose();current=rgb;
        }
        while(current.getWidth()/2>=w&&current.getHeight()/2>=h)current=step(current,current.getWidth()/2,current.getHeight()/2);
        return current.getWidth()==w&&current.getHeight()==h?current:step(current,w,h);
    }
    private static BufferedImage step(BufferedImage image,int w,int h){
        var next=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);var g=next.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image,0,0,w,h,null);g.dispose();return next;
    }
    private static void jpeg(BufferedImage image,OutputStream out) throws IOException {
        var writer=ImageIO.getImageWritersByFormatName("jpg").next();
        try(ImageOutputStream stream=ImageIO.createImageOutputStream(out)){
            writer.setOutput(stream);var param=writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);param.setCompressionQuality(.88f);
            writer.write(null,new IIOImage(image,null,null),param);
        }finally{writer.dispose();}
    }
    /** Turns a photo by its EXIF orientation (1–8), as phones store portrait photos turned. */
    static BufferedImage upright(BufferedImage image,int orientation){
        if(orientation<2||orientation>8)return image;
        int w=image.getWidth(),h=image.getHeight();boolean swap=orientation>=5;
        var t=new AffineTransform();
        switch(orientation){
            case 2->{t.translate(w,0);t.scale(-1,1);}
            case 3->{t.translate(w,h);t.rotate(Math.PI);}
            case 4->{t.translate(0,h);t.scale(1,-1);}
            case 5->{t.rotate(Math.PI/2);t.scale(1,-1);}
            case 6->{t.translate(h,0);t.rotate(Math.PI/2);}
            case 7->{t.scale(-1,1);t.translate(-h,0);t.translate(0,w);t.rotate(3*Math.PI/2);}
            case 8->{t.translate(0,w);t.rotate(3*Math.PI/2);}
        }
        var out=new BufferedImage(swap?h:w,swap?w:h,BufferedImage.TYPE_INT_RGB);var g=out.createGraphics();
        g.drawImage(image,t,null);g.dispose();return out;
    }
    /** The EXIF orientation of a JPEG (tag 0x0112 in APP1), 1 if there is none. */
    static int exifOrientation(byte[] jpeg){
        try{
            if(jpeg.length<4||(jpeg[0]&255)!=0xFF||(jpeg[1]&255)!=0xD8)return 1;
            int i=2;
            while(i+4<jpeg.length&&(jpeg[i]&255)==0xFF){
                int marker=jpeg[i+1]&255,length=((jpeg[i+2]&255)<<8)|(jpeg[i+3]&255);
                if(marker==0xE1&&i+10<jpeg.length&&new String(jpeg,i+4,4,java.nio.charset.StandardCharsets.ISO_8859_1).equals("Exif")){
                    int tiff=i+10;boolean little=jpeg[tiff]=='I';
                    int ifd=tiff+read(jpeg,tiff+4,4,little),entries=read(jpeg,ifd,2,little);
                    for(int e=0;e<entries;e++){
                        int entry=ifd+2+e*12;
                        if(read(jpeg,entry,2,little)==0x0112)return read(jpeg,entry+8,2,little);
                    }
                    return 1;
                }
                if(marker==0xDA)return 1;
                i+=2+length;
            }
        }catch(ArrayIndexOutOfBoundsException ignored){}
        return 1;
    }
    private static int read(byte[] b,int at,int n,boolean little){
        int v=0;for(int k=0;k<n;k++)v=little?v|((b[at+k]&255)<<(8*k)):(v<<8)|(b[at+k]&255);return v;
    }
}
