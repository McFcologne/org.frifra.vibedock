package org.frifra.vibedock.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * The client firmware built with the Docker image from the same sources (see Dockerfile), one build per board:
 * {@code <board>/flash.bin} (installed over USB at 0x0) and {@code <board>/firmware.bin} (the app, for updates over Wi-Fi),
 * the version for all of them, and esptool-js for flashing from the browser. Images of earlier versions held one
 * {@code vibedock-firmware.bin} for the VibeTV M1. Without the files (e.g. a local Maven run) there is none.
 * <p>
 * An administrator can upload an own build per board (data directory {@code firmware/<board>/}); it takes the place
 * of the built-in one for USB and for updates over Wi-Fi until it is removed. Its version and board are read from the
 * identity the firmware carries ({@code VIBEDOCK-FIRMWARE <version> <board>}).
 */
@Service
public class Firmware {
    /** uploaded: an administrator's own build, at uploadedAt (epoch seconds). */
    public record Info(Board board,String version,long size,String sha256,boolean uploaded,long uploadedAt) {
        public String fileName(){return (board.id().startsWith("vibedock-")?"":"vibedock-")+board.id()+"-"+version+".bin";}
    }
    static final int MAX_SIZE=4*1024*1024;
    private final Path dir,uploads;
    private final Map<String,Info> cached=new HashMap<>();private final Map<String,String> cachedFor=new HashMap<>();
    Firmware(String dir){this(dir,null);}
    @org.springframework.beans.factory.annotation.Autowired
    Firmware(@Value("${vibedock.firmware-dir:/app/firmware}") String dir,@Value("${vibedock.data-dir:}") String dataDir){
        this.dir=Path.of(dir);this.uploads=dataDir==null||dataDir.isBlank()?null:Path.of(dataDir).resolve("firmware");
    }

    private Path uploaded(Board board){return uploads==null?null:uploads.resolve(board.id());}
    /** The uploaded build of a board, when it is complete: the app, and on ESP32 chips also the USB image. */
    private boolean hasUpload(Board board){
        Path u=uploaded(board);
        return u!=null&&Files.isRegularFile(u.resolve("firmware.bin"))&&Files.isRegularFile(u.resolve("version"))
                &&(board.esp8266()||Files.isRegularFile(u.resolve("flash.bin")));
    }
    private Path flash(Board board){
        if(hasUpload(board))return uploaded(board).resolve(board.esp8266()?"firmware.bin":"flash.bin");
        Path p=dir.resolve(board.id()).resolve("flash.bin");
        return Files.isRegularFile(p)||!board.equals(Board.M1)?p:dir.resolve("vibedock-firmware.bin");
    }
    private Path app(Board board){
        if(hasUpload(board))return uploaded(board).resolve("firmware.bin");
        Path p=dir.resolve(board.id()).resolve("firmware.bin");
        return Files.isRegularFile(p)||!board.equals(Board.M1)?p:dir.resolve("vibedock-firmware.bin");
    }
    private static Optional<String> versionIn(Path file){
        try{
            if(!Files.isRegularFile(file))return Optional.empty();
            String v=Files.readString(file,StandardCharsets.UTF_8).strip();
            return v.matches("[0-9A-Za-z.+-]{1,40}")?Optional.of(v):Optional.empty();
        }catch(IOException e){return Optional.empty();}
    }
    private Optional<String> version(Board board){
        return hasUpload(board)?versionIn(uploaded(board).resolve("version")):versionIn(dir.resolve("version"));
    }
    /** The firmware for a board, if this installation has one: the uploaded build, else the built-in one. */
    synchronized Optional<Info> info(Board board){
        Path bin=flash(board);
        try{
            var v=version(board);if(v.isEmpty()||!Files.isRegularFile(bin)||!Files.isRegularFile(app(board)))return Optional.empty();
            var modified=Files.getLastModifiedTime(bin);String key=bin+"|"+modified+"|"+v.get();var info=cached.get(board.id());
            if(info==null||!key.equals(cachedFor.get(board.id()))){
                byte[] bytes=Files.readAllBytes(bin);
                info=new Info(board,v.get(),bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                        hasUpload(board),hasUpload(board)?modified.toInstant().getEpochSecond():0);
                cached.put(board.id(),info);cachedFor.put(board.id(),key);
            }
            return Optional.of(info);
        }catch(IOException|NoSuchAlgorithmException e){return Optional.empty();}
    }
    /** The firmware of the VibeTV M1. */
    Optional<Info> info(){return info(Board.M1).or(()->all().stream().findFirst());}
    /** Every board this installation has firmware for, in the order of the inventory. */
    List<Info> all(){return Board.INVENTORY.stream().map(this::info).flatMap(Optional::stream).toList();}
    /** The version per board id, for the pages that offer updates. */
    Map<String,String> versions(){
        Map<String,String> out=new LinkedHashMap<>();for(var i:all())out.put(i.board().id(),i.version());return out;
    }
    /** The image installed over USB at 0x0. */
    byte[] bytes(Board board) throws IOException {return Files.readAllBytes(flash(board));}

    /** What an uploaded file is: the app (updates over Wi-Fi; on the ESP8266 also USB) or the ESP32 USB image. */
    enum Kind {APP,USB}
    record Uploaded(Kind kind,String version,boolean complete) {}
    /**
     * Stores an uploaded build file for a board. The file must be an ESP firmware image (0xE9) carrying the identity
     * of this board; an ESP32 USB image (bootloader, partition table at 0x8000, app) is told from the app by its
     * partition table. A file of another version replaces the other part, which then has to be uploaded again.
     */
    synchronized Uploaded upload(Board board,byte[] bytes) throws IOException {
        if(uploads==null)throw new IllegalStateException("no data directory");
        if(bytes.length<1024||bytes.length>MAX_SIZE)throw new IllegalArgumentException("not a firmware file (size)");
        // The ESP32 USB image: partition table at 0x8000, bootloader at 0x0 (or 0x1000 on the classic ESP32).
        boolean usb=!board.esp8266()&&bytes.length>0x8002&&(bytes[0x8000]&0xff)==0xAA&&(bytes[0x8001]&0xff)==0x50;
        boolean image=(bytes[0]&0xff)==0xE9||usb&&(bytes[0x1000]&0xff)==0xE9;
        if(!image)throw new IllegalArgumentException("not an ESP firmware image");
        var identity=identity(bytes).orElseThrow(()->new IllegalArgumentException(
                "no VibeDock identity in the file: build it from these sources"));
        if(!identity[1].equals(board.id()))throw new IllegalArgumentException("the file is firmware for "+identity[1]+", not for "+board.id());
        Kind kind=usb?Kind.USB:Kind.APP;
        Path u=Files.createDirectories(uploaded(board));
        var previous=versionIn(u.resolve("version"));
        if(previous.isPresent()&&!previous.get().equals(identity[0])){
            Files.deleteIfExists(u.resolve("firmware.bin"));Files.deleteIfExists(u.resolve("flash.bin"));
        }
        write(u.resolve(kind==Kind.USB?"flash.bin":"firmware.bin"),bytes);
        write(u.resolve("version"),(identity[0]+"\n").getBytes(StandardCharsets.UTF_8));
        signed.remove(board.id());
        return new Uploaded(kind,identity[0],hasUpload(board));
    }
    /** Removes the uploaded build of a board; the built-in one applies again. */
    synchronized void removeUpload(Board board) throws IOException {
        Path u=uploaded(board);if(u==null||!Files.isDirectory(u))return;
        for(String f:List.of("firmware.bin","flash.bin","version"))Files.deleteIfExists(u.resolve(f));
        Files.deleteIfExists(u);signed.remove(board.id());
    }
    /** Whether an upload of the board is waiting for its other part (ESP32: app or USB image). */
    synchronized boolean uploadIncomplete(Board board){
        Path u=uploaded(board);return u!=null&&Files.isRegularFile(u.resolve("version"))&&!hasUpload(board);
    }
    private static void write(Path file,byte[] bytes) throws IOException {
        Path tmp=file.resolveSibling(file.getFileName()+".tmp");
        Files.write(tmp,bytes);Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    /**
     * Version and board from the identity string the firmware carries: "VIBEDOCK-FIRMWARE <version> <board>", or in
     * as a fallback the start line of its log, "Start v<version> <board>, ".
     */
    static Optional<String[]> identity(byte[] bytes){
        String text=new String(bytes,StandardCharsets.ISO_8859_1);
        var m=java.util.regex.Pattern.compile("VIBEDOCK-FIRMWARE ([0-9A-Za-z.+-]{1,40}) ([a-z0-9-]{1,40})").matcher(text);
        if(m.find())return Optional.of(new String[]{m.group(1),m.group(2)});
        m=java.util.regex.Pattern.compile("Start v([0-9A-Za-z.+-]{1,40}) ([a-z0-9-]{1,40}), ").matcher(text);
        return m.find()?Optional.of(new String[]{m.group(1),m.group(2)}):Optional.empty();
    }
    /** The firmware signed for updates over Wi-Fi, and the MD5 of those bytes (the updater's x-MD5 check). */
    record Signed(String version,String sha256,byte[] bytes,String md5) {}
    private final Map<String,Signed> signed=new HashMap<>();
    synchronized Optional<Signed> signed(Board board,OtaSigning signing) throws IOException {
        var i=info(board);if(i.isEmpty())return Optional.empty();
        var s=signed.get(board.id());
        if(s==null||!s.version().equals(i.get().version())||!s.sha256().equals(i.get().sha256())){
            byte[] bytes=signing.signed(Files.readAllBytes(app(board)));
            try{s=new Signed(i.get().version(),i.get().sha256(),bytes,HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)));}
            catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            signed.put(board.id(),s);
        }
        return Optional.of(s);
    }
    /** esptool-js (Espressif, Apache-2.0) as one ES module, for flashing over Web Serial. */
    Optional<Path> esptool(){Path p=dir.resolve("esptool.js");return Files.isRegularFile(p)?Optional.of(p):Optional.empty();}
}
