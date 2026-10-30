package org.frifra.vibedock.server;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.*;

/**
 * Paired image clients (protocol v2). The server invites a device once; afterwards the device pulls its image.
 * Only a SHA-256 hash of each device key is stored, so the registry file alone cannot impersonate a device.
 */
@Service
public class ClientRegistry {
    private static final Logger log=LoggerFactory.getLogger(ClientRegistry.class);
    static final int MIN_POLL=10,MAX_POLL=3600,DEFAULT_POLL=30,VERSION=3,THEME_ICONS_VERSION=4;
    /** Addresses announced to devices (empty: derive the internal one, no external one) and the poll interval. */
    record Server(String internalUrl,String externalUrl,int pollSeconds) {
        Server { internalUrl=internalUrl==null?"":internalUrl.trim();externalUrl=externalUrl==null?"":externalUrl.trim(); }
    }
    /**
     * A paired device; design is its complete override, null follows the default design. serverFooter: its firmware
     * shows the IP the server draws (header X-VibeDock-Footer: server on the last pull); older firmware draws it itself.
     * firmware: the version it reported on its last pull (X-VibeDock-Firmware), null if it sent none.
     * display: its {@link DisplayType} id, null for the VibeTV M1; chosen in the editor or reported by the device.
     * web: set for a web display, which fetches the image by link instead of being a paired device.
     * board: its {@link Board} id, null for the VibeTV M1; chosen when pairing or reported by the device.
     * callbackNetwork: pairing target plus the mask reported at pairing; missing means IPv4 /24 for old devices.
     * This field may be missing in older files. The first valid authenticated mask fixes it once; USB origins are fixed by their first authenticated pull.
     * flipped: the display shows its picture turned by 180 degrees (mounted upside down); set in the administration,
     * the device learns it with every pull (X-VibeDock-Orientation). Never for the VibeTV M1, whose case decides.
     * rotation: the turn of the picture in clockwise degrees (0, 90, 180, 270; 90 and 270 on square panels only);
     * missing in older files, where flipped decides. flipped stays set for 180, so an older server reads it as before.
     * powerSaving: experimental battery power saving, for boards that measure their battery (Board.batteryPowerSaving);
     * the device learns it with every pull (X-VibeDock-Power-Saving). Missing in older files: off.
     */
    record Device(String identifier,String name,String keyHash,String ip,int port,long pairedAt,Long lastPullAt,String lastPullIp,Design design,
                  boolean serverFooter,String firmware,String display,Web web,String board,String callbackNetwork,boolean flipped,Integer rotation,boolean powerSaving) {
        Device(String identifier,String name,String keyHash,String ip,int port,long pairedAt,Long lastPullAt,String lastPullIp,Design design,
               boolean serverFooter,String firmware,String display,Web web,String board,String callbackNetwork,boolean flipped,Integer rotation){
            this(identifier,name,keyHash,ip,port,pairedAt,lastPullAt,lastPullIp,design,serverFooter,firmware,display,web,board,callbackNetwork,flipped,rotation,false);
        }
        Device(String identifier,String name,String keyHash,String ip,int port,long pairedAt,Long lastPullAt,String lastPullIp,Design design,
               boolean serverFooter,String firmware,String display,Web web,String board,String callbackNetwork,boolean flipped){
            this(identifier,name,keyHash,ip,port,pairedAt,lastPullAt,lastPullIp,design,serverFooter,firmware,display,web,board,callbackNetwork,flipped,null);
        }
        Device(String identifier,String name,String keyHash,String ip,int port,long pairedAt,Long lastPullAt,String lastPullIp,Design design,
               boolean serverFooter,String firmware,String display,Web web,String board,String callbackNetwork){
            this(identifier,name,keyHash,ip,port,pairedAt,lastPullAt,lastPullIp,design,serverFooter,firmware,display,web,board,callbackNetwork,false);
        }
        Device(String identifier,String name,String keyHash,String ip,int port,long pairedAt,Long lastPullAt,String lastPullIp,Design design,
               boolean serverFooter,String firmware){this(identifier,name,keyHash,ip,port,pairedAt,lastPullAt,lastPullIp,design,serverFooter,firmware,null,null,null,null);}
        Device(String identifier,String name,String keyHash,String ip,int port,long pairedAt,Long lastPullAt,String lastPullIp,Design design,
               boolean serverFooter,String firmware,String display,Web web,String board){
            this(identifier,name,keyHash,ip,port,pairedAt,lastPullAt,lastPullIp,design,serverFooter,firmware,display,web,board,null);
        }
        Device withCallbackNetwork(String network){return new Device(identifier,name,keyHash,ip,port,pairedAt,lastPullAt,lastPullIp,design,serverFooter,firmware,display,web,board,network,flipped,rotation,powerSaving);}
        Device withCallbackOrigin(String origin){return new Device(identifier,name,keyHash,origin,port,pairedAt,lastPullAt,lastPullIp,design,serverFooter,firmware,display,web,board,null,flipped,rotation,powerSaving);}
        Device pulled(long at,String peer,boolean footer,String version){return new Device(identifier,name,keyHash,ip,port,pairedAt,at,peer,design,footer,version,display,web,board,callbackNetwork,flipped,rotation,powerSaving);}
        Device withDesign(Design next){return new Device(identifier,name,keyHash,ip,port,pairedAt,lastPullAt,lastPullIp,next,serverFooter,firmware,display,web,board,callbackNetwork,flipped,rotation,powerSaving);}
        Device withName(String next){return new Device(identifier,next,keyHash,ip,port,pairedAt,lastPullAt,lastPullIp,design,serverFooter,firmware,display,web,board,callbackNetwork,flipped,rotation,powerSaving);}
        Device withDisplay(String next){return new Device(identifier,name,keyHash,ip,port,pairedAt,lastPullAt,lastPullIp,design,serverFooter,firmware,next,web,board,callbackNetwork,flipped,rotation,powerSaving);}
        Device withWeb(Web next){return new Device(identifier,name,hash(next.secret()),ip,port,pairedAt,lastPullAt,lastPullIp,design,serverFooter,firmware,display,next,board,callbackNetwork,flipped,rotation,powerSaving);}
        Device withRotation(int degrees){return new Device(identifier,name,keyHash,ip,port,pairedAt,lastPullAt,lastPullIp,design,serverFooter,firmware,display,web,board,callbackNetwork,degrees==180,degrees,powerSaving);}
        /** The turn in clockwise degrees: the saved rotation, else 180 for a device saved as flipped. */
        Device withPowerSaving(boolean next){return new Device(identifier,name,keyHash,ip,port,pairedAt,lastPullAt,lastPullIp,design,serverFooter,firmware,display,web,board,callbackNetwork,flipped,rotation,next);}
        int degrees(){return rotation!=null?rotation:flipped?180:0;}
        Device withBoard(String next,String nextDisplay){return new Device(identifier,name,keyHash,ip,port,pairedAt,lastPullAt,lastPullIp,design,serverFooter,firmware,nextDisplay,web,next,callbackNetwork,flipped,rotation,powerSaving);}
        DisplayType displayType(){return DisplayType.of(display);}
        Board boardType(){return Board.of(board);}
    }
    /**
     * A web display: a browser page or another program fetches the image with the secret in its links. The secret is
     * kept (not only its hash) so the administration can show the links again; clients.json is readable by the owner
     * only. external: the links use the external server address.
     */
    public record Web(String secret,boolean external,int reloadSeconds) {
        public Web { validateKey(secret);if(reloadSeconds<MIN_POLL||reloadSeconds>MAX_POLL)throw new IllegalArgumentException("Reload every "+MIN_POLL+"–"+MAX_POLL+" seconds"); }
        Web withSecret(String next){return new Web(next,external,reloadSeconds);}
        Web withExternal(boolean next){return new Web(secret,next,reloadSeconds);}
    }
    /** clients.json: one section each for the server settings, the default design and the devices. */
    record State(int version,Server server,Design defaultDesign,List<Device> devices) {
        State { server=server==null?new Server("","",DEFAULT_POLL):server;defaultDesign=defaultDesign==null?Design.DEFAULT:defaultDesign;
            devices=devices==null?List.of():List.copyOf(devices); }
        State withDevices(List<Device> next){return new State(VERSION,server,defaultDesign,next);}
    }
    /** design is the device's design (own or default); overridden tells whether it is its own. */
    /**
     * ota: the device installs signed updates over Wi-Fi (it sent X-VibeDock-Ota with its last pull); otaNeedsPairing:
     * its firmware can, but it lacks this server's signing key because it was paired before (pair again); updating: an
     * update is requested and not yet installed; updateError: why its last update over Wi-Fi failed.
     */
    public record View(String identifier,String name,String ip,String status,Long lastPullAt,Design design,boolean overridden,boolean serverFooter,String firmware,
                       boolean ota,boolean otaNeedsPairing,boolean updating,String updateError,DisplayType display,Web web,Board board,boolean flipped,int rotation,boolean powerSaving) {}
    private final Path file;
    private final ObjectMapper json;
    private final Clock clock;
    private State state;

    public ClientRegistry(@Value("${vibedock.data-dir}") String directory,ObjectMapper json,Clock clock) throws IOException {
        this.json=json;this.clock=clock;Path dir=Path.of(directory);Files.createDirectories(dir);file=dir.resolve("clients.json");
        state=new State(VERSION,null,null,List.of());
        if(Files.exists(file)){
            JsonNode node=json.readTree(file.toFile());
            if(node.isArray()) log.warn("clients.json uses the push protocol v1; those registrations are ignored, invite the devices again");
            else if(node.path("version").asInt(0)>=VERSION) state=json.treeToValue(node,State.class);
            else {
                Files.copy(file,dir.resolve("clients.json.v2.bak"),StandardCopyOption.REPLACE_EXISTING);
                var migrated=RegistryMigration.fromVersion2(node,json);
                save(migrated);
                log.info("clients.json migrated to version {} ({} devices, {} with their own design); backup in clients.json.v2.bak",
                        VERSION,migrated.devices().size(),migrated.devices().stream().filter(d->d.design()!=null).count());
            }
        }
        if(state.devices().size()>100)throw new IOException("Too many clients");
        validateSettings(state.server().internalUrl(),state.server().externalUrl(),state.server().pollSeconds());
        Set<String> ids=new HashSet<>();
        for(var d:state.devices()){validateClient(d.identifier(),d.name(),d.ip(),d.port());
            if(!ids.add(d.identifier()))throw new IOException("Duplicate client identifier");}
    }

    // ---- server settings ----
    public synchronized String internalUrl(){return state.server().internalUrl();}
    public synchronized String externalUrl(){return state.server().externalUrl();}
    public synchronized int pollSeconds(){return state.server().pollSeconds();}
    public synchronized void configure(String internalUrl,String externalUrl,int pollSeconds) throws IOException {
        validateSettings(internalUrl,externalUrl,pollSeconds);
        save(new State(VERSION,new Server(internalUrl,externalUrl,pollSeconds),state.defaultDesign(),state.devices()));
    }

    // ---- designs ----
    public synchronized Design defaultDesign(){return state.defaultDesign();}
    public synchronized void configureDesign(Design design) throws IOException {
        save(new State(VERSION,state.server(),Objects.requireNonNull(design),state.devices()));
    }
    /** What a device shows: its own design, otherwise the default. */
    public synchronized Design designFor(String id){return design(find(id));}
    /** A complete override; null makes the device follow the default design again. */
    public synchronized void setDesign(String id,Design design) throws IOException {
        var updated=find(id).withDesign(design);List<Device> next=new ArrayList<>(state.devices());
        next.replaceAll(d->d.identifier().equals(id)?updated:d);save(state.withDevices(next));
    }
    private Design design(Device d){return d.design()==null?state.defaultDesign():d.design();}
    /** Replaces data source ids in all designs, e.g. after sources got random ids. */
    public synchronized void renameSources(Map<String,String> renamed) throws IOException {
        if(renamed.isEmpty())return;
        var next=new State(VERSION,state.server(),renamed(state.defaultDesign(),renamed),
                state.devices().stream().map(d->d.design()==null?d:d.withDesign(renamed(d.design(),renamed))).toList());
        if(!next.equals(state))save(next);
    }
    private static Design renamed(Design design,Map<String,String> renamed){
        var bands=design.layout().bands().stream().map(b->b instanceof Layout.Usage u
                ?(Layout.Band)new Layout.Usage(u.sources().stream().map(s->renamed.getOrDefault(s,s)).toList(),u.compact()):b).toList();
        return design.withLayout(design.layout().withBands(bands));
    }

    // ---- clients ----
    /** Stores a device after a successful pairing; pairing the same identifier again replaces its key. */
    public synchronized void paired(String id,String name,String key,String ip,int port) throws IOException {paired(id,name,key,ip,port,null);}
    /** The same with the device's board (null: keep the one known, the VibeTV M1 for a new device). */
    public synchronized void paired(String id,String name,String key,String ip,int port,String board) throws IOException {
        paired(id,name,key,ip,port,board,null);
    }
    /** Network learned once during pairing; null uses IPv4 /24 anchored to the original pairing address. */
    public synchronized void paired(String id,String name,String key,String ip,int port,String board,String callbackNetwork) throws IOException {
        if(callbackNetwork!=null&&(ClientPairing.UNKNOWN_IP.equals(ip)||!IpNetworks.parse(callbackNetwork).matches(ip)
                ||IpNetworks.parse(callbackNetwork).bits()==0))throw new IllegalArgumentException("Callback network must contain the pairing address");
        validateClient(id,name,ip,port);validateKey(key);
        List<Device> next=new ArrayList<>(state.devices());
        var previous=next.stream().filter(d->d.identifier().equals(id)).findFirst();
        next.removeIf(d->d.identifier().equals(id));
        if(next.size()>=100)throw new IllegalArgumentException("At most 100 clients");
        next.add(new Device(id,name.trim(),hash(key),ip,port,clock.instant().getEpochSecond(),null,null,previous.map(Device::design).orElse(null),
                previous.map(Device::serverFooter).orElse(false),previous.map(Device::firmware).orElse(null),previous.map(Device::display).orElse(null),null,
                previous.map(Device::board).orElse(null),callbackNetwork,previous.map(Device::flipped).orElse(false),previous.map(Device::rotation).orElse(null),
                previous.map(Device::powerSaving).orElse(false)));
        save(state.withDevices(next));
        if(board!=null)setBoard(id,board);
    }
    /**
     * Sets the board of a device (null or the M1 id: the VibeTV M1). A device still on the default display type gets
     * the display its board comes with; a display type chosen in the editor stays.
     */
    public synchronized void setBoard(String id,String board) throws IOException {
        var d=find(id);var type=Board.of(board);String next=type.equals(Board.M1)?null:type.id();
        if(Objects.equals(next,d.board()))return;
        String display=d.display()==null||d.display().equals(d.boardType().display())?(type.display().equals(DisplayType.M1.id())?null:type.display()):d.display();
        List<Device> list=new ArrayList<>(state.devices());list.replaceAll(x->x.identifier().equals(id)?x.withBoard(next,display):x);save(state.withDevices(list));
        log.info("Device {} ({}) is a {}",d.name(),id,type.label());
    }
    /** Adds a web display with a new secret; it draws the server address where devices show their IP. */
    public synchronized View addWeb(String name,String display,int reloadSeconds) throws IOException {
        String id="web-"+HexFormat.of().formatHex(randomBytes(6));
        validateClient(id,name==null?"":name.trim(),ClientPairing.UNKNOWN_IP,80);
        if(state.devices().size()>=100)throw new IllegalArgumentException("At most 100 clients");
        var web=new Web(newSecret(),false,reloadSeconds);
        String type=display==null||display.equals(DisplayType.M1.id())?null:DisplayType.of(display).id();
        List<Device> next=new ArrayList<>(state.devices());
        next.add(new Device(id,name.trim(),hash(web.secret()),ClientPairing.UNKNOWN_IP,80,clock.instant().getEpochSecond(),null,null,null,true,null,type,web,null));
        save(state.withDevices(next));log.info("Web display {} ({}) added",name.trim(),id);
        return view(find(id));
    }
    /** New links for a web display; the old ones stop working at once. */
    public synchronized void renewWeb(String id) throws IOException {updateWeb(id,find(id).web().withSecret(newSecret()));log.info("Web display {} got new links",id);}
    /** Whether the links and the drawn address use the external server address. */
    public synchronized void setWebExternal(String id,boolean external) throws IOException {updateWeb(id,find(id).web().withExternal(external));}
    private void updateWeb(String id,Web web) throws IOException {
        if(find(id).web()==null)throw new IllegalArgumentException("Not a web display");
        List<Device> list=new ArrayList<>(state.devices());list.replaceAll(x->x.identifier().equals(id)?x.withWeb(web):x);save(state.withDevices(list));
    }
    /** The web display a link's secret belongs to; compared as hashes in constant time per entry. */
    public synchronized Optional<View> webBySecret(String secret){
        if(secret==null||!secret.matches("[a-f0-9]{64}"))return Optional.empty();
        byte[] given=hash(secret).getBytes(StandardCharsets.US_ASCII);Device found=null;
        for(var d:state.devices())if(d.web()!=null&&MessageDigest.isEqual(given,d.keyHash().getBytes(StandardCharsets.US_ASCII)))found=d;
        return Optional.ofNullable(found).map(this::view);
    }
    private static String newSecret(){return HexFormat.of().formatHex(randomBytes(32));}
    private static byte[] randomBytes(int n){byte[] b=new byte[n];new java.security.SecureRandom().nextBytes(b);return b;}
    /** New order of the devices (device page, overview, editor); unknown ids are ignored, devices not named stay at the end. */
    public synchronized void reorder(List<String> ids) throws IOException {save(state.withDevices(ordered(state.devices(),ids,Device::identifier)));}
    static <T> List<T> ordered(List<T> items,List<String> ids,java.util.function.Function<T,String> id){
        List<T> next=new ArrayList<>();
        for(String wanted:ids==null?List.<String>of():ids)items.stream().filter(i->id.apply(i).equals(wanted)&&!next.contains(i)).findFirst().ifPresent(next::add);
        items.stream().filter(i->!next.contains(i)).forEach(next::add);
        return next;
    }
    public synchronized void rename(String id,String name) throws IOException {
        var d=find(id);String next=name==null?"":name.trim();
        validateClient(d.identifier(),next,d.ip(),d.port());
        var renamed=d.withName(next);
        List<Device> list=new ArrayList<>(state.devices());list.replaceAll(x->x.identifier().equals(id)?renamed:x);save(state.withDevices(list));
    }
    /** Sets the display type of a device (null or the M1 id: the VibeTV M1). */
    public synchronized void setDisplay(String id,String type) throws IOException {
        var d=find(id);String next=type==null||type.equals(DisplayType.M1.id())?null:DisplayType.of(type).id();
        if(DisplayType.FIT.id().equals(next)&&d.web()==null)throw new IllegalArgumentException("Only web displays fit the browser");
        if(Objects.equals(next,d.display()))return;
        List<Device> list=new ArrayList<>(state.devices());list.replaceAll(x->x.identifier().equals(id)?x.withDisplay(next):x);save(state.withDevices(list));
    }
    public synchronized DisplayType displayType(String id){return find(id).displayType();}
    /** Turns the picture of a device by 180 degrees or back; boards whose case decides the orientation keep it upright. */
    public synchronized void setFlipped(String id,boolean flipped) throws IOException {
        var d=find(id);setRotation(id,flipped&&d.web()==null&&d.boardType().flippable()?180:0);
    }
    /** Turns the picture of a device clockwise; 90 and 270 degrees only on square panels, nothing on the VibeTV M1. */
    public synchronized void setRotation(String id,int degrees) throws IOException {
        var d=find(id);
        if(!d.boardType().rotations().contains(degrees))throw new IllegalArgumentException("This display cannot be turned by "+degrees+" degrees");
        int next=d.web()!=null?0:degrees;
        if(next==d.degrees()&&(d.rotation()!=null||next==0))return;
        List<Device> list=new ArrayList<>(state.devices());list.replaceAll(x->x.identifier().equals(id)?x.withRotation(next):x);save(state.withDevices(list));
    }
    /** Experimental battery power saving on or off; only for boards that measure their battery. */
    public synchronized void setPowerSaving(String id,boolean enabled) throws IOException {
        var d=find(id);
        if(enabled&&(d.web()!=null||!d.boardType().batteryPowerSaving()))throw new IllegalArgumentException("This display has no battery power saving");
        if(d.powerSaving()==enabled)return;
        List<Device> list=new ArrayList<>(state.devices());list.replaceAll(x->x.identifier().equals(id)?x.withPowerSaving(enabled):x);save(state.withDevices(list));
    }
    public synchronized boolean flipped(String id){return rotation(id)==180;}
    /** The turn the device shows, 0 when its board cannot show the saved one (e.g. after a change of board). */
    public synchronized int rotation(String id){return effectiveRotation(find(id));}
    private static int effectiveRotation(Device d){int r=d.degrees();return d.web()==null&&d.boardType().rotations().contains(r)?r:0;}
    public synchronized void remove(String id) throws IOException {
        var removed=find(id);List<Device> next=new ArrayList<>(state.devices());next.removeIf(d->d.identifier().equals(id));
        save(state.withDevices(next));forcedRefresh.remove(id);otaCapable.remove(id);otaWithoutKey.remove(id);updateRequested.remove(id);updateErrors.remove(id);
        log.info("Device {} ({}) removed",removed.name(),id);
    }
    /** Devices whose next pull gets the full image even if it did not change, so the screen is redrawn. In memory only. */
    private final Set<String> forcedRefresh=new HashSet<>();
    public synchronized void forceRefresh(String id){forcedRefresh.add(find(id).identifier());}
    /** True once after {@link #forceRefresh}. */
    public synchronized boolean takeForcedRefresh(String id){return forcedRefresh.remove(id);}
    /** Updates over Wi-Fi, in memory only: devices report their capability with every pull. */
    private final Set<String> otaCapable=new HashSet<>(),otaWithoutKey=new HashSet<>();
    /** Requested updates and when; a request the device has not finished within ten minutes counts as failed. */
    private final Map<String,Long> updateRequested=new HashMap<>();
    private static final long UPDATE_TIMEOUT=600;
    private void expireUpdate(String id){
        Long at=updateRequested.get(id);
        if(at!=null&&clock.instant().getEpochSecond()-at>UPDATE_TIMEOUT){updateRequested.remove(id);updateErrors.put(id,"no answer within ten minutes");}
    }private final Map<String,String> updateErrors=new HashMap<>();
    /** What a device reported with its pull about updates over Wi-Fi: whether it can, and why its last one failed. */
    public synchronized void otaReport(String id,String state,String error){
        if("1".equals(state))otaCapable.add(id);else otaCapable.remove(id);
        if("no-key".equals(state))otaWithoutKey.add(id);else otaWithoutKey.remove(id);
        if(error!=null){updateErrors.put(id,error);updateRequested.remove(id);log.warn("Device {} reports a failed update: {}",id,error);}
    }
    /** Requests an update to {@code version}; false if the device cannot update over Wi-Fi or runs it or a newer one. */
    public synchronized boolean requestUpdate(String id,String version){
        var d=find(id);
        if(!otaCapable.contains(id)||!FirmwareVersion.offered(version,d.firmware()))return false;
        updateRequested.put(id,clock.instant().getEpochSecond());updateErrors.remove(id);return true;
    }
    /** True while an update is requested and the device does not yet run {@code version}; cleared once it does. */
    public synchronized boolean updatePending(String id,String version){
        if(!FirmwareVersion.offered(version,find(id).firmware())){updateRequested.remove(id);return false;}
        expireUpdate(id);return updateRequested.containsKey(id);
    }
    /** Constant-time key check; false for unknown identifiers so callers cannot tell them apart. */
    public synchronized boolean authenticate(String id,String key){
        String given=key==null||!key.matches("[a-f0-9]{64}")?"0".repeat(64):hash(key);
        String stored=state.devices().stream().filter(d->d.identifier().equals(id)).map(Device::keyHash).findFirst().orElse("f".repeat(64));
        boolean match=MessageDigest.isEqual(given.getBytes(StandardCharsets.US_ASCII),stored.getBytes(StandardCharsets.US_ASCII));
        return match && state.devices().stream().anyMatch(d->d.identifier().equals(id));
    }
    /** Records a pull; written to disk at most once a minute per client to spare the data directory. */
    public synchronized void pulled(String id,String peer) throws IOException {var c=find(id);pulled(id,peer,c.serverFooter(),c.firmware());}
    public synchronized void pulled(String id,String peer,boolean serverFooter) throws IOException {pulled(id,peer,serverFooter,find(id).firmware());}
    public synchronized void pulled(String id,String peer,boolean serverFooter,String firmware) throws IOException {
        pulled(id,peer,serverFooter,firmware,null);
    }
    public synchronized void pulled(String id,String peer,boolean serverFooter,String firmware,String subnetMask) throws IOException {
        var c=find(id);long now=clock.instant().getEpochSecond();
        // USB establishes trust on its first authenticated contact. Never re-anchor on later reports.
        boolean learnOrigin=c.web()==null&&ClientPairing.UNKNOWN_IP.equals(c.ip())&&usableCallbackIp(peer);
        if(learnOrigin)c=c.withCallbackOrigin(peer);
        boolean learnNetwork=false;
        if(c.web()==null&&c.callbackNetwork()==null&&!ClientPairing.UNKNOWN_IP.equals(c.ip())
                &&FirmwareVersion.knownNewer(firmware,"0.1.0")&&subnetMask!=null&&!subnetMask.isBlank()){
            try{
                String network=IpNetworks.subnet(c.ip(),subnetMask.trim());
                if(IpNetworks.parse(network).matches(peer)){c=c.withCallbackNetwork(network);learnNetwork=true;}
            }catch(IllegalArgumentException ignored){ /* Invalid masks do not consume the one-time upgrade. */ }
        }
        boolean persist=learnOrigin||learnNetwork||c.lastPullAt()==null || now-c.lastPullAt()>=60 || !Objects.equals(peer,c.lastPullIp()) || serverFooter!=c.serverFooter()
                || !Objects.equals(firmware,c.firmware());
        // Another firmware than before (over Wi-Fi or USB, e.g. after an update that timed out): an old update error is over.
        if(firmware!=null&&c.firmware()!=null&&!firmware.equals(c.firmware()))updateErrors.remove(id);
        if(c.lastPullAt()==null)log.info("Device {} ({}) pulled its first image from {}",c.name(),id,peer);
        else if(!Objects.equals(peer,c.lastPullIp()))log.info("Device {} ({}) now pulls from {} (was {})",c.name(),id,peer,c.lastPullIp());
        var updated=c.pulled(now,peer,serverFooter,firmware);
        List<Device> next=new ArrayList<>(state.devices());next.replaceAll(x->x.identifier().equals(id)?updated:x);
        var nextState=state.withDevices(next);
        if(persist)save(nextState);else state=nextState;
    }
    private static boolean usableCallbackIp(String ip){
        try{
            var address=java.net.InetAddress.getByAddress(IpNetworks.literal(ip));
            return !address.isAnyLocalAddress()&&!address.isMulticastAddress();
        }catch(IllegalArgumentException|java.net.UnknownHostException e){return false;}
    }
    /** Endpoint for an immediate pull: reports may change the IP only inside the network fixed at pairing. */
    public record Address(String ip,int port) {}
    /** Devices that show the default design. */
    public synchronized List<Address> followingDefault(){
        return state.devices().stream().filter(d->d.design()==null).map(ClientRegistry::address).toList();
    }
    public synchronized Address address(String id){return address(find(id));}
    /** Every paired device. */
    public synchronized List<Address> all(){return state.devices().stream().map(ClientRegistry::address).toList();}
    /** Web displays cannot be asked to pull; they count as not reachable. */
    private static Address address(Device d){
        if(d.web()!=null||ClientPairing.UNKNOWN_IP.equals(d.ip()))return new Address(ClientPairing.UNKNOWN_IP,d.port());
        String target=d.ip();
        String network=d.callbackNetwork();
        // Legacy firmware supplied no mask. Keep this fallback anchored to the original pairing IP.
        if(network==null&&IpNetworks.literal(d.ip()).length==4)network=d.ip()+"/24";
        if(network!=null&&d.lastPullIp()!=null&&IpNetworks.parse(network).matches(d.lastPullIp()))target=d.lastPullIp();
        return new Address(target,d.port());
    }
    /** Pulls announced to displays that follow X-VibeDock-Next-Pull (epoch seconds), in memory only. */
    private final Map<String,Long> expectedPull=new java.util.concurrent.ConcurrentHashMap<>();
    /** The display was told to pull again in seconds: it counts as missing only well after that. */
    public void expectPull(String id,int seconds){expectedPull.put(id,clock.instant().getEpochSecond()+seconds);}
    /** The pull last announced to a display (epoch seconds), if it follows X-VibeDock-Next-Pull. */
    public Optional<Long> expectedPull(String id){return Optional.ofNullable(expectedPull.get(id));}
    public synchronized List<View> views(){return state.devices().stream().map(this::view).toList();}
    public synchronized View get(String id){return view(find(id));}

    private Device find(String id){return state.devices().stream().filter(d->d.identifier().equals(id)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Client not found"));}
    private View view(Device d){
        expireUpdate(d.identifier());
        long now=clock.instant().getEpochSecond();
        int interval=d.web()!=null?d.web().reloadSeconds():state.server().pollSeconds();
        // Web displays fetch only while their page is open: missing fetches are no fault there.
        String status=d.lastPullAt()==null?(d.web()!=null?"Not connected yet":"Waiting for first pull")
                :now-Math.max(d.lastPullAt()+3L*interval,expectedPull.getOrDefault(d.identifier(),0L)+2L*interval)>0
                        ?(d.web()!=null?"Not connected":"Updates missing"):"Connected";
        return new View(d.identifier(),d.name(),d.lastPullIp()==null?d.ip():d.lastPullIp(),status,d.lastPullAt(),design(d),d.design()!=null,d.serverFooter(),d.firmware(),
                otaCapable.contains(d.identifier()),otaWithoutKey.contains(d.identifier()),updateRequested.containsKey(d.identifier()),updateErrors.get(d.identifier()),d.displayType(),d.web(),d.boardType(),effectiveRotation(d)==180,effectiveRotation(d),
                d.web()==null&&d.boardType().batteryPowerSaving()&&d.powerSaving());
    }
    private void save(State next) throws IOException {
        // Do not migrate old files on read or on an ordinary emoji edit. Mark the new shape only when opted into.
        if(hasThemeIcons(next))next=new State(THEME_ICONS_VERSION,next.server(),next.defaultDesign(),next.devices());
        Path temp=Files.createTempFile(file.getParent(),"clients-",".tmp");
        try{Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));json.writeValue(temp.toFile(),next);
            backupBeforeThemeIcons(next);
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);state=next;
        }finally{Files.deleteIfExists(temp);}
    }
    /** The optional icon field is additive; keep a private pre-feature snapshot before first opting into it. */
    private void backupBeforeThemeIcons(State next) throws IOException {
        var backup=file.resolveSibling("clients.json.before-theme-icons.bak");
        if(!hasThemeIcons(next)||hasThemeIcons(state)||!Files.exists(file)||Files.exists(backup))return;
        var temporary=Files.createTempFile(file.getParent(),"clients-icons-backup-",".tmp");
        try{
            Files.copy(file,temporary,StandardCopyOption.REPLACE_EXISTING);
            Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));
            Files.move(temporary,backup,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(temporary);}
    }
    private static boolean hasThemeIcons(State state){
        return state.defaultDesign().icon()!=null||state.devices().stream().anyMatch(d->d.design()!=null&&d.design().icon()!=null);
    }
    static String hash(String key){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.US_ASCII)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static void validateKey(String key){if(key==null||!key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid client key");}
    static void validateClient(String id,String name,String ip,int port){
        if(id==null || !id.matches("[A-Za-z0-9_.:-]{8,80}") || name==null || name.isBlank() || name.length()>40
                || name.chars().anyMatch(Character::isISOControl) || port<1 || port>65535)
            throw new IllegalArgumentException("Invalid client");
        IpNetworks.literal(ip);
    }
    static void validateSettings(String internalUrl,String externalUrl,int pollSeconds){
        if(pollSeconds<MIN_POLL||pollSeconds>MAX_POLL)throw new IllegalArgumentException("Poll interval must be "+MIN_POLL+"–"+MAX_POLL+" seconds");
        for(String url:List.of(internalUrl==null?"":internalUrl,externalUrl==null?"":externalUrl)) if(!url.isBlank()) validateServerUrl(url);
    }
    /** An address announced to devices: http(s)://HOST[:PORT] without path, query or credentials. */
    static void validateServerUrl(String url){
        URI u;
        try{u=URI.create(url.trim());}catch(RuntimeException e){throw new IllegalArgumentException("Invalid server address: "+url);}
        if(!("http".equals(u.getScheme())||"https".equals(u.getScheme())) || u.getHost()==null || u.getUserInfo()!=null
                || u.getQuery()!=null || u.getFragment()!=null || !(u.getPath()==null||u.getPath().isEmpty()||u.getPath().equals("/"))
                || url.length()>120)
            throw new IllegalArgumentException("Server address must be http(s)://HOST[:PORT]: "+url);
    }
}
