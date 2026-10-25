package org.frifra.vibedock.server;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;

/** Image pull of paired devices on the public port (protocol v2). */
@Controller
class DeviceApiController {
    static final String IMAGE_FORMAT="rgb565-be-240x240";
    private static final Logger log=LoggerFactory.getLogger(DeviceApiController.class);
    private final ClientRegistry registry;private final DesignService designs;private final ServerAddresses addresses;private final TlsTrust trust;
    private final Firmware firmware;private final OtaSigning signing;private final DeviceLogs logs;private final AdminEvents events;private final DeviceDiagnostics diagnostics;
    private final DevicePower power;
    private final ImageDelta delta;
    private final PullSchedule schedule;
    DeviceApiController(ClientRegistry registry,DesignService designs,ServerAddresses addresses,TlsTrust trust,Firmware firmware,OtaSigning signing,DeviceLogs logs,AdminEvents events,
                        DeviceDiagnostics diagnostics,DevicePower power,ImageDelta delta,PullSchedule schedule){
        this.delta=delta;this.schedule=schedule;
        this.power=power;this.logs=logs;this.events=events;this.diagnostics=diagnostics;this.registry=registry;this.designs=designs;this.addresses=addresses;this.trust=trust;this.firmware=firmware;this.signing=signing;
    }
    /** The device authenticates with its key; unknown devices and wrong keys get the same 401. */
    @GetMapping("/api/v1/clients/{identifier}/image")
    ResponseEntity<byte[]> image(@PathVariable String identifier,@RequestHeader(value="Authorization",required=false) String authorization,
                                 @RequestHeader(value="If-None-Match",required=false) String etag,HttpServletRequest request) throws IOException {
        String key=authorization!=null&&authorization.startsWith("Bearer ")?authorization.substring(7).trim():null;
        if(!registry.authenticate(identifier,key)){
            log.info("Pull rejected (401) for {} from {}: unknown device or wrong key",identifier,request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String peer=deviceAddress(request.getHeader("X-VibeDock-Device-IP"),request.getRemoteAddr());
        boolean serverFooter="server".equals(request.getHeader("X-VibeDock-Footer"));
        String version=request.getHeader("X-VibeDock-Firmware");
        registry.pulled(identifier,peer,serverFooter,version!=null&&version.matches("[0-9A-Za-z.+-]{1,40}")?version:null,
                request.getHeader("X-VibeDock-Subnet-Mask"));
        // The display's own state (newer firmware): its start and run time, memory and Wi-Fi; nothing for older firmware.
        diagnostics.report(identifier,request.getHeader("X-VibeDock-Diag"),registry.get(identifier).firmware(),request.getHeader("X-VibeDock-Board"));
        String updateError=request.getHeader("X-VibeDock-Update-Error");
        registry.otaReport(identifier,request.getHeader("X-VibeDock-Ota"),
                updateError!=null&&updateError.matches("[ -~]{1,120}")?updateError:null);
        // The device knows its board (firmware build); it decides which firmware an update installs.
        String board=request.getHeader("X-VibeDock-Board");
        if(board!=null&&Board.find(board).isPresent()&&!registry.get(identifier).board().id().equals(board)){
            try{registry.setBoard(identifier,board);}catch(IOException e){log.warn("Could not store the board of {}: {}",identifier,e.toString());}
        }
        // A device that reports its screen size gets the image in it; a type chosen in the administration stays.
        DisplayType.reported(request.getHeader("X-VibeDock-Display")).ifPresent(type->{
            try{if(registry.get(identifier).display()==DisplayType.M1&&type!=DisplayType.M1)registry.setDisplay(identifier,type.id());}
            catch(IOException e){log.warn("Could not store the display type of {}: {}",identifier,e.toString());}
        });
        // On its battery the display gets a small battery icon at the top right; on USB it reports nothing.
        power.report(identifier,request.getHeader("X-VibeDock-Power"));
        String gesture=request.getHeader("X-VibeDock-Gesture");
        if("photos-toggle".equals(gesture))togglePhotos(registry,designs,events,identifier);
        else if("turn-clockwise".equals(gesture)||"turn-counterclockwise".equals(gesture))turn(registry,events,identifier,"turn-clockwise".equals(gesture)?1:-1);
        else swipe(registry,designs,events,identifier,"scheme-next".equals(gesture)?1:"scheme-previous".equals(gesture)?-1:0);
        var display=registry.displayType(identifier);
        byte[] pixels=designs.render(DesignService.forDevice(registry.designFor(identifier),serverFooter),peer,display,identifier).rgb565();
        int fullBytes=pixels.length;
        String tag="\""+HexFormat.of().formatHex(Arrays.copyOf(sha256(pixels),12))+"\"";
        var headers=new HttpHeaders();headers.setETag(tag);headers.set("X-VibeDock-Image-Format",display.imageFormat());
        headers.set("X-VibeDock-Poll-Seconds",String.valueOf(designs.pollSeconds(registry.designFor(identifier),registry.pollSeconds(),identifier)));
        // When the picture can change next: firmware that follows it (X-VibeDock-Schedule: 1) pulls then instead of
        // every poll interval, also after a 304, and the server expects it then.
        boolean previewPull="1".equals(request.getHeader("X-VibeDock-Preview"));
        var next=schedule.next(identifier,display,previewPull);
        headers.set("X-VibeDock-Next-Pull",next.header());
        if(!previewPull&&"1".equals(request.getHeader("X-VibeDock-Schedule")))
            registry.expectPull(identifier,next.seconds());
        // Only clients that explicitly support sound receive quota metadata. Preview pulls neither need nor consume it.
        if(QuotaHeader.requested(request.getHeader("X-VibeDock-Audio"),request.getHeader("X-VibeDock-Preview")))
            headers.set("X-VibeDock-Quota",designs.quotaHeader(registry.designFor(identifier),display));
        // Mounted turned: the device turns its panel (also its own screens and the swipes). "upright" and "flipped" as
        // before; firmware that does not know "rotated-90"/"rotated-270" keeps its orientation.
        if(registry.get(identifier).board().flippable())headers.set("X-VibeDock-Orientation",orientation(registry.rotation(identifier)));
        // Experimental battery power saving: "battery" lets the display save power while it runs on its battery.
        if(registry.get(identifier).board().batteryPowerSaving())headers.set("X-VibeDock-Power-Saving",registry.get(identifier).powerSaving()?"battery":"off");
        // A requested update over Wi-Fi: the device fetches /firmware after this pull and installs it if signed.
        firmware.info(registry.get(identifier).board()).filter(i->registry.updatePending(identifier,i.version())).ifPresent(i->headers.set("X-VibeDock-Update",i.version()));
        // A log requested in the administration: the display uploads it after this pull (POST .../log).
        logs.pendingRequest(identifier).ifPresent(id->headers.set("X-VibeDock-Send-Log",id));
        // After a full certificate check the device learns the current fingerprint for its simple check. The device
        // accepts it only over such a verified connection, so it cannot be injected over plain http.
        if("full".equals(request.getHeader("X-VibeDock-Tls-Check"))&&addresses.external().startsWith("https://")){
            try{headers.set("X-VibeDock-Tls-Pin",trust.recentInspection(addresses.external()).fingerprint());}catch(IllegalArgumentException ignored){}
        }
        // A forced refresh sends the image without ETag: Spring would otherwise answer the matching If-None-Match with
        // 304 itself. The device then pulls the next image once without condition and learns the ETag again.
        // The device skips rows it already shows; X-VibeDock-Redraw makes it draw every row again.
        // The status page's preview copy (X-VibeDock-Preview) leaves a pending forced refresh for the screen.
        boolean preview="1".equals(request.getHeader("X-VibeDock-Preview"));
        boolean forced=!preview&&registry.takeForcedRefresh(identifier);
        if(forced){headers.remove(HttpHeaders.ETAG);headers.set("X-VibeDock-Redraw","1");}
        else if(tag.equals(etag)){
            logTransfer(identifier,preview?"preview unchanged (304)":"unchanged (304)",fullBytes,0,next);
            return new ResponseEntity<>(headers,HttpStatus.NOT_MODIFIED);
        }
        // Capability and exact panel size are required: deltas cannot be scaled without a framebuffer.
        if(!preview&&designs.currentPhoto(registry.designFor(identifier),identifier).isEmpty()&&"1".equals(request.getHeader("X-VibeDock-Delta"))
                &&(display.width()+"x"+display.height()).equals(request.getHeader("X-VibeDock-Display"))){
            var patch=delta.exchange(identifier,forced?null:etag,tag,display.width(),display.height(),pixels,System.currentTimeMillis());
            if(!forced&&patch.isPresent()){
                var p=patch.get();pixels=p.bytes();
                headers.set("X-VibeDock-Image-Format","rgb565-delta-be-"+display.width()+"x"+display.height());
                headers.set("X-VibeDock-Base",etag);headers.set("X-VibeDock-Check",p.check());
                headers.set("X-VibeDock-Payload",p.payload());headers.set("X-VibeDock-Length",String.valueOf(pixels.length));
            }
        }
        logTransfer(identifier,preview?"preview full":headers.containsHeader("X-VibeDock-Base")?"delta":"full",fullBytes,pixels.length,next);
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return new ResponseEntity<>(pixels,headers,HttpStatus.OK);
    }
    /** Response payload selected for transmission; excludes transport overhead and is not a receipt acknowledgement. */
    private static void logTransfer(String device,String mode,int full,int payload,PullSchedule.Next next){
        String announced=next.seconds()+" s ("+(next.pending()?"pending":"current")+")";
        // Unchanged images (304) come with every poll: only at debug level, so the log keeps its other events.
        if(payload==0){log.debug("Image transfer {}: {}, next={}",device,mode,announced);return;}
        log.info("Image transfer {}: {}, full={} bytes, payload={} bytes, saved={} bytes ({}%), next={}",device,mode,full,payload,full-payload,
                String.format(Locale.ROOT,"%.1f",full==0?0:100.0*(full-payload)/full),announced);
    }
    /**
     * A swipe on a touch display or a web display page: the next (1) or previous (-1) colour scheme. The display keeps
     * it as its own design, also when it followed the default until now. In a running slideshow a swipe shows the next
     * or previous photo instead.
     */
    static void swipe(ClientRegistry registry,DesignService designs,AdminEvents events,String identifier,int step){
        if(step==0)return;
        if(designs.skip(registry.designFor(identifier),identifier,step)){log.info("Display {} skipped to another photo by a swipe",identifier);return;}
        var current=registry.designFor(identifier);var scheme=designs.nextScheme(current.scheme(),step);
        if(scheme==current.scheme())return;
        try{registry.setDesign(identifier,current.withScheme(scheme));log.info("Display {} switched to the scheme {} by a swipe",identifier,scheme.id());events.devicesChanged();}
        catch(IOException e){log.warn("Could not store the scheme of {}: {}",identifier,e.toString());}
    }
    /**
     * A circle drawn with one finger on a touch display turns its picture to the next orientation in that direction:
     * by a quarter on square panels, otherwise between upright and flipped. The answer to this pull carries it.
     */
    static void turn(ClientRegistry registry,AdminEvents events,String identifier,int step){
        var rotations=registry.get(identifier).board().rotations();
        if(rotations.size()<2){log.info("Display {}: circle gesture, but its board cannot be turned",identifier);return;}
        int index=rotations.indexOf(registry.rotation(identifier)),next=rotations.get(Math.floorMod(index+step,rotations.size()));
        try{registry.setRotation(identifier,next);log.info("Display {} turned to {} degrees by a circle gesture",identifier,next);events.devicesChanged();}
        catch(IOException e){log.warn("Could not store the orientation of {}: {}",identifier,e.toString());}
    }
    /** A double tap on a touch display or a web display page: the slideshow on or off, whatever its schedule says. */
    static void togglePhotos(ClientRegistry registry,DesignService designs,AdminEvents events,String identifier){
        if(!designs.togglePhotos(registry.designFor(identifier),identifier)){log.info("Display {}: double tap without photos in its design",identifier);return;}
        log.info("Display {} turned its photos {} by a double tap",identifier,Boolean.TRUE.equals(designs.photosForced(identifier))?"on":"off");
        events.devicesChanged();
    }
    /**
     * The display's log, sent after a pull that named an open request (X-VibeDock-Send-Log): plain text, one line per
     * event, at most {@value DeviceLogs#MAX_BYTES} bytes. Accepted once per request, with the device key.
     */
    @PostMapping("/api/v1/clients/{identifier}/log")
    ResponseEntity<Void> log(@PathVariable String identifier,@RequestHeader(value="Authorization",required=false) String authorization,
                             @RequestHeader(value="X-VibeDock-Log-Request",required=false) String requestId,HttpServletRequest request) throws IOException {
        String key=authorization!=null&&authorization.startsWith("Bearer ")?authorization.substring(7).trim():null;
        if(!registry.authenticate(identifier,key))return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        byte[] body=request.getInputStream().readNBytes(DeviceLogs.MAX_BYTES+1);
        if(body.length>DeviceLogs.MAX_BYTES)return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        if(!logs.takeRequest(identifier,requestId)){
            log.info("Log of {} rejected: no open request",identifier);
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        var lines=new String(body,java.nio.charset.StandardCharsets.UTF_8).lines().filter(l->!l.isBlank()).toList();
        var diag=DeviceDiagnostics.parse(request.getHeader("X-VibeDock-Diag"));
        var entry=logs.save(identifier,"net",request.getHeader("X-VibeDock-Firmware"),request.getHeader("X-VibeDock-Board"),lines,
                diag.get("boot"),diag.containsKey("up")?Long.valueOf(diag.get("up")):null);
        log.info("Saved the log {} sent by {} ({} lines)",entry.name(),identifier,entry.lines());events.logSaved(identifier);
        return ResponseEntity.noContent().build();
    }
    /** The firmware offered by this server, signed for the device's updater; fetched by the device after a pull named it. */
    @GetMapping("/api/v1/clients/{identifier}/firmware")
    ResponseEntity<byte[]> firmware(@PathVariable String identifier,@RequestHeader(value="Authorization",required=false) String authorization,
                                    HttpServletRequest request) throws IOException {
        String key=authorization!=null&&authorization.startsWith("Bearer ")?authorization.substring(7).trim():null;
        if(!registry.authenticate(identifier,key))return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        var signed=firmware.signed(registry.get(identifier).board(),signing);
        if(signed.isEmpty())return ResponseEntity.notFound().build();
        log.info("Device {} downloads firmware {} ({}) for an update over Wi-Fi",identifier,signed.get().version(),registry.get(identifier).board().id());
        // X-VibeDock-Length: reverse proxies may drop Content-Length (chunked answer); the device needs the size up front.
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header("x-MD5",signed.get().md5())
                .header("X-VibeDock-Length",String.valueOf(signed.get().bytes().length))
                .header("X-VibeDock-Firmware-Version",signed.get().version()).body(signed.get().bytes());
    }
    /**
     * The device's own address as it reports it; behind NAT (e.g. Docker Desktop) the remote address is only the
     * gateway. The report is display data; callback selection separately enforces the network fixed at pairing.
     * A display never has a loopback, link-local, unspecified or multicast address: such reports (e.g. 127.0.0.1 or
     * the cloud metadata address 169.254.169.254) are ignored and the connection's source address counts instead.
     */
    static String deviceAddress(String reported,String remote){
        if(reported!=null&&!reported.isBlank()&&reported.length()<=45){
            try{
                var address=java.net.InetAddress.getByAddress(IpNetworks.literal(reported.trim()));
                if(!address.isLoopbackAddress()&&!address.isLinkLocalAddress()&&!address.isAnyLocalAddress()&&!address.isMulticastAddress())
                    return reported.trim();
            }catch(IllegalArgumentException|java.net.UnknownHostException ignored){}
        }
        return remote;
    }
    private static byte[] sha256(byte[] data){
        try{return MessageDigest.getInstance("SHA-256").digest(data);}catch(Exception e){throw new IllegalStateException(e);}
    }
    /** X-VibeDock-Orientation for a clockwise turn in degrees. */
    static String orientation(int degrees){return switch(degrees){case 90->"rotated-90";case 180->"flipped";case 270->"rotated-270";default->"upright";};}
}
