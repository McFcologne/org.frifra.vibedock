package org.frifra.vibedock.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;

/**
 * Asks devices to pull their image now instead of at their next poll, e.g. after a design was saved. The request
 * carries no data; the device then pulls as usual with its key. Unreachable devices (for example only reachable
 * through the external address) simply pull at their next poll.
 */
@Service
class DevicePoke {
    private static final Logger log=LoggerFactory.getLogger(DevicePoke.class);
    private final HttpClient http;
    @Value("${vibedock.polling:true}") private boolean enabled=true;
    DevicePoke(HttpClient http){this.http=http;}

    void pullNow(List<ClientRegistry.Address> devices){
        if(!enabled||devices.isEmpty())return;
        for(var device:devices){
            if(ClientPairing.UNKNOWN_IP.equals(device.ip()))continue;  // USB/web display: no administrator-approved callback endpoint
            String host=device.ip().contains(":")?"["+device.ip()+"]":device.ip();
            var request=HttpRequest.newBuilder(URI.create("http://"+host+":"+device.port()+"/pull")).timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            http.sendAsync(request,HttpResponse.BodyHandlers.discarding()).whenComplete((response,error)->{
                if(error!=null)log.debug("Device at {} not reachable for an immediate pull: {}",device.ip(),error.toString());
                else if(response.statusCode()!=202)log.debug("Device at {} answered {} to an immediate pull",device.ip(),response.statusCode());
            });
        }
        log.info("Asked {} device(s) to pull the new image now",devices.size());
    }
}
