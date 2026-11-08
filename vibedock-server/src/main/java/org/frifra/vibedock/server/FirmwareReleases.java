package org.frifra.vibedock.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Firmware downloaded from the releases of the sources in {@code firmware-sources.yaml} (see {@link FirmwareSources}),
 * kept in the data directory: {@code firmware/releases/<source>/<version>/<board>/} with {@code firmware.bin},
 * {@code flash.bin}, {@code manifest.json} and {@code version}, the last one written last. Checked once at start and
 * every day after that. Only releases whose index names the protocol version this server speaks are taken, and every
 * file must match the SHA-256 and size of the index; the app must also carry the board and version it is for. The
 * newest versions of a source ({@link #KEEP}) are fetched and stay on disk, older ones are removed.
 */
@Service
public class FirmwareReleases {
    private static final Logger log=LoggerFactory.getLogger(FirmwareReleases.class);
    /** The protocol version of the client firmware this server speaks (CLIENT-PROTOCOL.md). */
    public static final int PROTOCOL=2;
    static final int KEEP=2,LOOK=KEEP;
    /** The files of one board of one release; trusted: updates over Wi-Fi are allowed. */
    record Cached(String source,String version,Path dir,boolean trusted) {}
    /** What the administration shows per source: the newest compatible version on disk, the last check, its error. */
    public record Status(FirmwareSources.Source source,String latest,long checkedAt,String error) {}
    private final Path root,sourcesFile;private final ReleaseFeed feed;private final boolean enabled;
    private final Map<String,Status> status=new LinkedHashMap<>();private String configError;

    FirmwareReleases(@Value("${vibedock.data-dir:}") String dataDir,@Value("${vibedock.firmware-sync:true}") boolean enabled,ReleaseFeed feed){
        Path data=dataDir==null||dataDir.isBlank()?null:Path.of(dataDir);
        this.root=data==null?null:data.resolve("firmware").resolve("releases");
        this.sourcesFile=data==null?null:data.resolve("firmware-sources.yaml");
        this.enabled=enabled&&data!=null;this.feed=feed;
    }

    /** The configured sources, in priority order; empty (with {@link #configError}) when the file has a mistake. */
    synchronized List<FirmwareSources.Source> sources(){
        String stamp;
        try{stamp=sourcesFile!=null&&Files.isRegularFile(sourcesFile)?Files.getLastModifiedTime(sourcesFile)+"|"+Files.size(sourcesFile):"none";}
        catch(IOException e){stamp="unreadable";}
        if(sourcesCache!=null&&stamp.equals(sourcesStamp))return sourcesCache;
        best.clear();sourcesStamp=stamp;
        try{configError=null;sourcesCache=FirmwareSources.load(sourcesFile);}
        catch(IllegalArgumentException e){configError=e.getMessage();sourcesCache=List.of();}
        return sourcesCache;
    }
    private List<FirmwareSources.Source> sourcesCache;private String sourcesStamp;
    /** Per board id, until something is fetched or the sources change: the answer of {@link #best}. */
    private final Map<String,Optional<Cached>> best=new HashMap<>();
    synchronized String configError(){sources();return configError;}

    /** The firmware of a board from the first source (in order) that has complete files for it, newest version. */
    synchronized Optional<Cached> best(Board board){
        if(root==null)return Optional.empty();
        var list=sources();
        var known=best.get(board.id());
        if(known!=null&&known.map(c->Files.isRegularFile(c.dir().resolve("version"))).orElse(true))return known;
        var found=find(board,list);best.put(board.id(),found);return found;
    }
    private Optional<Cached> find(Board board,List<FirmwareSources.Source> list){
        for(var source:list){
            if(!source.enabled()||!source.offers(board.id()))continue;
            for(String version:versions(source.id())){
                Path dir=root.resolve(source.id()).resolve(version).resolve(board.id());
                if(complete(dir,board))return Optional.of(new Cached(source.id(),version,dir,source.trusted()));
            }
        }
        return Optional.empty();
    }
    private static boolean complete(Path dir,Board board){
        return Files.isRegularFile(dir.resolve("version"))&&Files.isRegularFile(dir.resolve("firmware.bin"))
                &&(board.esp8266()||Files.isRegularFile(dir.resolve("flash.bin")));
    }
    /** The version directories of a source, newest first. */
    private List<String> versions(String sourceId){
        Path dir=root.resolve(sourceId);
        if(!Files.isDirectory(dir))return List.of();
        try(Stream<Path> s=Files.list(dir)){
            var names=new ArrayList<>(s.filter(Files::isDirectory).map(p->p.getFileName().toString()).filter(n->n.matches("[0-9A-Za-z.+-]{1,40}")).toList());
            names.sort((a,b)->FirmwareVersion.newer(a,b)?-1:FirmwareVersion.newer(b,a)?1:0);
            return names;
        }catch(IOException e){return List.of();}
    }
    synchronized List<Status> status(){
        var out=new ArrayList<Status>();
        for(var s:sources()){
            var known=status.get(s.id());
            out.add(new Status(s,root==null?null:versions(s.id()).stream().findFirst().orElse(null),known==null?0:known.checkedAt(),known==null?null:known.error()));
        }
        return out;
    }

    /** At start (after 20 seconds) and then every 24 hours after the last check ended. */
    @Scheduled(initialDelay=20_000,fixedDelayString="PT24H")
    void scheduled(){if(enabled)sync();}

    /** Asks every enabled source and fetches what is new; a source that fails does not stop the others. */
    public synchronized void sync(){
        if(root==null)return;
        try{runSync();}finally{best.clear();}
    }
    private void runSync(){
        for(var source:sources()){
            if(!source.enabled())continue;
            String error=null;
            try{sync(source);}catch(IOException|RuntimeException e){error=e.getMessage()==null?e.toString():e.getMessage();log.warn("Firmware source {}: {}",source.id(),error);}
            status.put(source.id(),new Status(source,null,Instant.now().getEpochSecond(),error));
        }
    }
    private void sync(FirmwareSources.Source source) throws IOException {
        var releases=feed.releases(source);
        int looked=0;String problem=null;
        for(var release:releases){
            if(looked++>=LOOK)break;
            try{fetch(source,release);}
            catch(IOException|RuntimeException e){problem=release.tag()+": "+(e.getMessage()==null?e.toString():e.getMessage());log.warn("Firmware source {}, release {}: {}",source.id(),release.tag(),e.getMessage());}
        }
        prune(source);
        if(problem!=null)throw new IOException(problem);
    }
    private void fetch(FirmwareSources.Source source,ReleaseFeed.Release release) throws IOException {
        Path versionDir=root.resolve(source.id()).resolve(release.version());
        // The index of a release that was checked once stays next to its files: later checks do not ask for it again.
        Path kept=versionDir.resolve("index.json");ReleaseIndex index=null;
        if(Files.isRegularFile(kept)){try{index=ReleaseIndex.parse(Files.readAllBytes(kept));}catch(IllegalArgumentException e){index=null;}}
        if(index!=null&&index.protocol()!=PROTOCOL)index=null;
        if(index==null){
            String indexUrl=release.assets().get("index.json");
            if(indexUrl==null)throw new IOException("no index.json");
            byte[] bytes=feed.fetch(indexUrl,ReleaseIndex.MAX_BYTES);
            index=ReleaseIndex.parse(bytes);
            if(index.protocol()!=PROTOCOL)throw new IOException("protocol "+index.protocol()+", this server speaks "+PROTOCOL);
            if(!index.client().equals(release.version()))throw new IOException("index.json is for version "+index.client());
            Files.createDirectories(versionDir);Files.write(kept,bytes);
        }
        var wanted=new ArrayList<Board>();
        for(var b:Board.INVENTORY)if(source.offers(b.id())&&index.board(b.id()).isPresent()&&!complete(versionDir.resolve(b.id()),b))wanted.add(b);
        for(var board:wanted){
            var entry=index.board(board.id());
            if(entry.isEmpty())continue;
            Path tmp=Files.createDirectories(root.resolve(source.id())).resolve(".tmp-"+release.version()+"-"+board.id());
            deleteTree(tmp);Files.createDirectories(tmp);
            try{
                byte[] app=file(release,entry.get().app()),flash=file(release,entry.get().flash()),manifest=file(release,entry.get().manifest());
                var identity=Firmware.identity(app).orElseThrow(()->new IOException(board.id()+": the app carries no VibeDock identity"));
                if(!identity[0].equals(release.version())||!identity[1].equals(board.id()))
                    throw new IOException(board.id()+": the app is "+identity[1]+" "+identity[0]+", not "+board.id()+" "+release.version());
                Files.write(tmp.resolve("firmware.bin"),app);Files.write(tmp.resolve("flash.bin"),flash);Files.write(tmp.resolve("manifest.json"),manifest);
                Files.writeString(tmp.resolve("version"),release.version()+"\n",StandardCharsets.UTF_8);
                Path target=Files.createDirectories(versionDir).resolve(board.id());
                deleteTree(target);
                Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE);
                log.info("Firmware {} for {} from source {} fetched",release.version(),board.id(),source.id());
            }finally{deleteTree(tmp);}
        }
    }
    private byte[] file(ReleaseFeed.Release release,ReleaseIndex.File file) throws IOException {
        String url=release.assets().get(file.file());
        if(url==null)throw new IOException("the release has no file "+file.file());
        byte[] bytes=feed.fetch(url,(int)file.size());
        if(bytes.length!=file.size())throw new IOException(file.file()+": size differs from the index");
        try{
            if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(file.sha256()))
                throw new IOException(file.file()+": SHA-256 differs from the index");
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        return bytes;
    }
    private void prune(FirmwareSources.Source source) throws IOException {
        var all=versions(source.id());
        for(int i=KEEP;i<all.size();i++)deleteTree(root.resolve(source.id()).resolve(all.get(i)));
    }
    private static void deleteTree(Path path) throws IOException {
        if(!Files.exists(path))return;
        try(Stream<Path> s=Files.walk(path)){for(var p:s.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
    }
}
