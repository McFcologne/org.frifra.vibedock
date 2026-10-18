package org.frifra.vibedock.server;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Read-only pin status; protected by the same administrator authentication as the pairing endpoints. */
@RestController
class TlsPinController {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(TlsPinController.class);
    private final TlsTrust trust;private final ServerAddresses addresses;
    TlsPinController(TlsTrust trust,ServerAddresses addresses){this.trust=trust;this.addresses=addresses;}
    record Status(boolean known,String fingerprint,String message,String route,String target,boolean cached,TlsTrust.CertificateInfo certificate) {
        Status(boolean known,String fingerprint,String message){this(known,fingerprint,message,null,null,false,null);}
    }
    @GetMapping("/admin/tls-pin") ResponseEntity<Status> status(){
        long started=System.nanoTime();
        String external=addresses.external();Status status;
        if(!external.startsWith("https://"))status=new Status(false,null,"No external HTTPS address configured.");
        else{
            try{
                boolean cached=trust.isRecent(external);
                var inspection=trust.recentInspection(external);
                status=new Status(inspection.fingerprint()!=null,inspection.fingerprint(),
                        inspection.fingerprint()!=null?"TLS pin available for "+external:"TLS pin unavailable; the display must verify HTTPS itself.",
                        inspection.route(),inspection.target(),cached,inspection.certificate());
            }catch(IllegalArgumentException e){status=new Status(false,null,"TLS pin unavailable: "+e.getMessage());}
        }
        log.info("TLS pin refresh for {}: {} ({} ms)",external.isBlank()?"(not configured)":external,
                !external.startsWith("https://")?"no HTTPS address":status.known()?"available":"unavailable",
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status);
    }
}
