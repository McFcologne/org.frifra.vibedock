package org.frifra.vibedock.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The Doom firmware of the Pico-LCD-1.3, put together here when it is downloaded: the program (resource
 * {@code doom/doom-pico2w-lcd-13-code.uf2}) plus the shareware {@code DOOM1.WAD}, which this server fetches from
 * the first of its sources ({@code vibedock.doom.wad-urls}, or {@code doom/sources.txt} in the data directory, read on every call) that has it, checks against its SHA-1 and converts with {@code whd_gen} (see doom/README.md). No WAD data
 * is part of the repository or of a release. The result is kept in the data directory ({@code doom/}).
 */
@Service
class DoomFirmware {
    private static final Logger log=LoggerFactory.getLogger(DoomFirmware.class);
    /** Where the converted WAD sits in the flash of the RP2350 (TINY_WAD_ADDR of the Doom port). */
    static final int WAD_ADDRESS=0x10041000;
    static final int MAX_DOWNLOAD=8*1024*1024,MAX_WAD=6*1024*1024,MAX_WHX=2*1024*1024+512*1024;
    static final String CODE="/doom/doom-pico2w-lcd-13-code.uf2";
    /** Where a running build is: fetch, convert, pack; empty when none runs. The dialog shows it. */
    private volatile String stage="";
    String stage(){return stage;}
    private final List<URI> configured;private final String wadSha1;private final Path whdGen,dir;
    /** The host the current or last build fetched from; the dialog shows it. */
    private volatile String source="";
    String source(){return source;}
    private final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();

    /** What the dialog tells before the download: where the WAD comes from and what is packed. */
    record Plan(boolean available,List<URI> sources,String wadSha1,boolean cached,boolean fromFile) {}

    DoomFirmware(@Value("${vibedock.doom.wad-urls:https://www.gamers.org/pub/idgames/idstuff/doom/doom19s.zip,https://youfailit.net/pub/idgames/idstuff/doom/doom19s.zip}") String wadUrls,
                 @Value("${vibedock.doom.wad-sha1:5b2e249b9c5133ec987b3ea77596381dc0d6bc1d}") String wadSha1,
                 @Value("${vibedock.doom.whd-gen:/app/doom/whd_gen}") String whdGen,
                 @Value("${vibedock.data-dir:}") String dataDir){
        this.configured=parse(List.of(wadUrls.split("[,\\s]+")));this.wadSha1=wadSha1.toLowerCase(Locale.ROOT);this.whdGen=Path.of(whdGen);
        this.dir=dataDir==null||dataDir.isBlank()?null:Path.of(dataDir).resolve("doom");
    }

    Plan plan(){
        var file=fileSources();
        return new Plan(Files.isExecutable(whdGen)&&dir!=null&&code()!=null,file.isEmpty()?configured:file,wadSha1,dir!=null&&Files.isRegularFile(cacheFile()),!file.isEmpty());
    }

    private static List<URI> parse(List<String> urls){
        var out=new ArrayList<URI>();
        for(var u:urls){
            if(u.isBlank()||u.startsWith("#"))continue;
            try{var uri=URI.create(u.trim());if(uri.getHost()!=null&&uri.getUserInfo()==null&&("https".equals(uri.getScheme())||"http".equals(uri.getScheme())))out.add(uri);}catch(IllegalArgumentException ignored){}
        }
        return List.copyOf(out);
    }
    /**
     * Addresses an administrator put into {@code <data>/doom/sources.txt} (one per line, # for comments): read on every call,
     * so a mirror can be replaced without a restart. They take the place of the configured ones.
     */
    private List<URI> fileSources(){
        if(dir==null)return List.of();
        try{return Files.isRegularFile(dir.resolve("sources.txt"))?parse(Files.readAllLines(dir.resolve("sources.txt"))):List.of();}catch(IOException e){return List.of();}
    }

    private static byte[] code(){
        try(var in=DoomFirmware.class.getResourceAsStream(CODE)){return in==null?null:in.readAllBytes();}catch(IOException e){return null;}
    }
    private Path cacheFile(){
        var code=code();if(code==null||dir==null)throw new IllegalStateException("no Doom program");
        return dir.resolve("doom1-"+wadSha1.substring(0,8)+".whx");
    }

    /** The finished firmware: the converted WAD comes from the cache (built now when it is not there yet: a download and a conversion of a few seconds). */
    byte[] firmware() throws IOException {
        var whx=Files.readAllBytes(whx());
        stage="pack";
        try{return merge(code(),whx);}finally{stage="";}
    }

    private synchronized Path whx() throws IOException {
        if(!plan().available())throw new IOException("This installation cannot build the Doom firmware (the converter or the program is missing).");
        var target=cacheFile();
        if(Files.isRegularFile(target))return target;
        Files.createDirectories(dir);
        var work=Files.createTempDirectory(dir,"build-");
        try{
            stage="fetch";
            var wad=fetchWad();
            var wadFile=work.resolve("DOOM1.WAD");Files.write(wadFile,wad);
            var whxFile=work.resolve("doom1.whx");
            log.info("Doom: converting the WAD");
            stage="convert";
            convert(wadFile,whxFile);
            var whx=Files.readAllBytes(whxFile);
            if(whx.length<1024||whx.length>MAX_WHX||!"IWHX".equals(new String(whx,0,4,StandardCharsets.ISO_8859_1)))throw new IOException("The converter produced no usable data.");
            var tmp=Files.createTempFile(dir,"doom-",".part");
            Files.write(tmp,whx);
            Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            log.info("Doom: game data ready ({} KB)",Files.size(target)/1024);
            return target;
        }finally{
            stage="";
            try(var s=Files.walk(work)){s.sorted(Comparator.reverseOrder()).forEach(p->p.toFile().delete());}catch(IOException ignored){}
        }
    }

    /** The WAD from the first source that has it: an unreachable one, a wrong answer or other content moves on to the next. */
    private byte[] fetchWad() throws IOException {
        var sources=plan().sources();
        if(sources.isEmpty())throw new IOException("No source for the WAD is set (vibedock.doom.wad-urls, or doom/sources.txt in the data directory).");
        var problems=new ArrayList<String>();
        for(var uri:sources){
            source=uri.getHost();
            log.info("Doom: fetching the shareware WAD from {}",uri.getHost());
            try{return wad(download(uri));}
            catch(IOException e){log.warn("Doom: {}: {}",uri.getHost(),e.getMessage());problems.add(uri.getHost()+": "+e.getMessage());}
        }
        throw new IOException("No source could be used. "+String.join(" ",problems)+" Try again later or put another address into doom/sources.txt in the data directory.");
    }

    private byte[] download(URI uri) throws IOException {
        try{
            var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(120)).header("User-Agent","vibedock-server").build(),HttpResponse.BodyHandlers.ofInputStream());
            if(response.statusCode()!=200)throw new IOException("answered "+response.statusCode()+".");
            try(var in=response.body()){
                var data=in.readNBytes(MAX_DOWNLOAD+1);
                if(data.length>MAX_DOWNLOAD)throw new IOException("The download is larger than expected.");
                return data;
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("interrupted");}
        catch(IOException e){
            var m=e.getMessage();
            if(m!=null&&(m.startsWith("The ")||m.startsWith("answered ")))throw e;
            throw new IOException("not reachable ("+e.getClass().getSimpleName()+": this server needs internet access for the download).");
        }
    }

    /** DOOM1.WAD out of what was downloaded: the file itself, a ZIP holding it, or the 1995 installer (two parts of a ZIP). Checked by its SHA-1. */
    byte[] wad(byte[] body) throws IOException {
        byte[] wad=looksLikeWad(body)?body:fromZip(body,0);
        if(wad==null)throw new IOException("DOOM1.WAD was not found in the download.");
        if(!hex(sha(wad,"SHA-1")).equals(wadSha1))throw new IOException("The WAD does not match the expected checksum (SHA-1 "+wadSha1+"); it is not the shareware version this firmware is made for.");
        return wad;
    }
    private static boolean looksLikeWad(byte[] d){return d.length>12&&d[0]=='I'&&d[1]=='W'&&d[2]=='A'&&d[3]=='D';}
    private static byte[] fromZip(byte[] data,int depth) throws IOException {
        int start=indexOf(data,new byte[]{'P','K',3,4});
        if(start<0||depth>1)return null;
        var parts=new TreeMap<String,byte[]>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(data,start,data.length-start))){
            for(ZipEntry e;(e=zip.getNextEntry())!=null;){
                var name=e.getName().toLowerCase(Locale.ROOT);name=name.substring(name.lastIndexOf('/')+1);
                if(name.equals("doom1.wad")){var wad=zip.readNBytes(MAX_WAD+1);if(wad.length>MAX_WAD)throw new IOException("The WAD is larger than expected.");return wad;}
                if(name.matches("[a-z0-9_]+\\.[0-9]")&&depth==0)parts.put(name,zip.readNBytes(MAX_DOWNLOAD+1));
            }
        }catch(java.util.zip.ZipException e){return null;}
        if(parts.isEmpty())return null;
        var joined=new ByteArrayOutputStream();for(var p:parts.values())joined.write(p);
        return fromZip(joined.toByteArray(),depth+1);
    }
    private static int indexOf(byte[] d,byte[] k){
        outer:for(int i=0;i+k.length<=d.length;i++){for(int j=0;j<k.length;j++)if(d[i+j]!=k[j])continue outer;return i;}
        return -1;
    }

    private void convert(Path wad,Path whx) throws IOException {
        var pb=new ProcessBuilder(whdGen.toString(),wad.toString(),whx.toString()).directory(wad.getParent().toFile()).redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        var p=pb.start();
        try{
            if(!p.waitFor(120,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("The conversion took too long.");}
        }catch(InterruptedException e){p.destroyForcibly();Thread.currentThread().interrupt();throw new IOException("interrupted");}
        if(p.exitValue()!=0||!Files.isRegularFile(whx))throw new IOException("The conversion failed (exit code "+p.exitValue()+").");
    }

    /**
     * Appends the converted WAD to the program as UF2 data blocks. The leading block of the RP2350 image (family
     * "absolute") stays as it is; the blocks of the data family are numbered again over the program and the WAD.
     */
    static byte[] merge(byte[] code,byte[] whx) throws IOException {
        if(code==null||code.length%512!=0||code.length<1024)throw new IOException("The Doom program is not a UF2 file.");
        int blocks=code.length/512,codeData=0;long family=0;
        for(int i=0;i<blocks;i++){
            var b=ByteBuffer.wrap(code,i*512,512).order(ByteOrder.LITTLE_ENDIAN);
            if(b.getInt()!=0x0A324655||b.getInt()!=0x9E5D5157||b.getInt(i*512+508)!=0x0AB16F30)throw new IOException("The Doom program is not a UF2 file.");
            long fam=b.getInt(i*512+28)&0xffffffffL;
            if(i==0)continue;
            if(family==0)family=fam;
            if(fam!=family)throw new IOException("The Doom program mixes UF2 families.");
            codeData++;
        }
        int wadBlocks=(whx.length+255)/256,first=codeData,total=first+wadBlocks;
        var out=ByteBuffer.allocate((blocks+wadBlocks)*512).order(ByteOrder.LITTLE_ENDIAN);
        out.put(code,0,512);
        for(int i=1;i<blocks;i++){
            int at=out.position();
            out.put(code,i*512,512);
            out.putInt(at+20,i-1).putInt(at+24,total);
        }
        for(int k=0;k<wadBlocks;k++){
            int at=out.position();
            out.putInt(0x0A324655).putInt(0x9E5D5157).putInt(0x2000).putInt(WAD_ADDRESS+k*256).putInt(256).putInt(first+k).putInt(total).putInt((int)family);
            out.put(whx,k*256,Math.min(256,whx.length-k*256));
            out.position(at+508);out.putInt(0x0AB16F30);
        }
        return out.array();
    }

    private static byte[] sha(byte[] d,String alg){try{return MessageDigest.getInstance(alg).digest(d);}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String hex(byte[] d){var s=new StringBuilder();for(byte b:d)s.append(String.format("%02x",b));return s.toString();}
}
