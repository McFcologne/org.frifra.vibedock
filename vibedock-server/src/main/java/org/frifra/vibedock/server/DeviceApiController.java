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
    private final Firmware firmware;private final OtaSigning signing;private final DeviceLogs logs;
    DeviceApiController(ClientRegistry registry,DesignService designs,ServerAddresses addresses,TlsTrust trust,Firmware firmware,OtaSigning signing,DeviceLogs logs){
        this.logs=logs;this.registry=registry;this.designs=designs;this.addresses=addresses;this.trust=trust;this.firmware=firmware;this.signing=signing;
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
        var display=registry.displayType(identifier);
        byte[] pixels=designs.render(DesignService.forDevice(registry.designFor(identifier),serverFooter),peer,display).rgb565();
        String tag="\""+HexFormat.of().formatHex(Arrays.copyOf(sha256(pixels),12))+"\"";
        var headers=new HttpHeaders();headers.setETag(tag);headers.set("X-VibeDock-Image-Format",display.imageFormat());
        headers.set("X-VibeDock-Poll-Seconds",String.valueOf(registry.pollSeconds()));
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
        if(!"1".equals(request.getHeader("X-VibeDock-Preview"))&&registry.takeForcedRefresh(identifier)){headers.remove(HttpHeaders.ETAG);headers.set("X-VibeDock-Redraw","1");}
        else if(tag.equals(etag))return new ResponseEntity<>(headers,HttpStatus.NOT_MODIFIED);
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return new ResponseEntity<>(pixels,headers,HttpStatus.OK);
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
        var entry=logs.save(identifier,"net",request.getHeader("X-VibeDock-Firmware"),request.getHeader("X-VibeDock-Board"),lines);
        log.info("Saved the log {} sent by {} ({} lines)",entry.name(),identifier,entry.lines());
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
}
