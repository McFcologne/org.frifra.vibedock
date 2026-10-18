package org.frifra.vibedock.server;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Read-only pin status; protected by the same administrator authentication as the pairing endpoints. */
@RestController
class TlsPinController {
    private final TlsTrust trust;private final ServerAddresses addresses;
    TlsPinController(TlsTrust trust,ServerAddresses addresses){this.trust=trust;this.addresses=addresses;}
    record Status(boolean known,String fingerprint,String message) {}
    @GetMapping("/admin/tls-pin") ResponseEntity<Status> status(){
        String external=addresses.external();Status status;
        if(!external.startsWith("https://"))status=new Status(false,null,"No external HTTPS address configured.");
        else{
            try{
                var inspection=trust.recentInspection(external);
                status=new Status(inspection.fingerprint()!=null,inspection.fingerprint(),
                        inspection.fingerprint()!=null?"TLS pin available for "+external:"TLS pin unavailable; the display must verify HTTPS itself.");
            }catch(IllegalArgumentException e){status=new Status(false,null,"TLS pin unavailable: "+e.getMessage());}
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status);
    }
}
