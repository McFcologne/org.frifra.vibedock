package org.frifra.vibedock.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * The server addresses announced to devices when they are invited: an internal one (LAN) and an optional external
 * one (e.g. a public host name). Each can be set in the administration UI; INTERNAL_URL / EXTERNAL_URL provide
 * defaults. Without any setting the internal address is derived from the host used to open the administration UI.
 */
@Component
public class ServerAddresses {
    private final ClientRegistry registry;private final int publicPort;private final String internalDefault;private final String externalDefault;
    public ServerAddresses(ClientRegistry registry,@Value("${vibedock.public-port:${server.port}}") int publicPort,
                           @Value("${vibedock.internal-url:}") String internalDefault,@Value("${vibedock.external-url:}") String externalDefault){
        this.registry=registry;this.publicPort=publicPort;this.internalDefault=internalDefault.trim();this.externalDefault=externalDefault.trim();
    }
    public String derivedInternal(HttpServletRequest request){
        String host=request.getServerName();if(host.contains(":"))host="["+host+"]";
        return "http://"+host+":"+publicPort;
    }
    /** Explicitly configured LAN address; no request-derived Host header is used for TLS probes. */
    public String configuredInternal(){return !registry.internalUrl().isBlank()?registry.internalUrl():internalDefault;}
    public String internal(HttpServletRequest request){
        String configured=configuredInternal();
        return configured.isBlank()?derivedInternal(request):configured;
    }
    public String external(){return !registry.externalUrl().isBlank()?registry.externalUrl():externalDefault;}
    /** Internal first, external second; the device tries them in this order. */
    public List<String> announced(HttpServletRequest request){
        List<String> list=new ArrayList<>();list.add(internal(request));
        String external=external();if(!external.isBlank()&&!list.contains(external))list.add(external);
        return list;
    }
    /** True when the announced internal address points to the server itself and no device could reach it. */
    public boolean internalIsLoopback(HttpServletRequest request){
        String host=java.net.URI.create(internal(request)).getHost();
        return host==null||host.equals("localhost")||host.startsWith("127.")||host.equals("[::1]")||host.equals("::1");
    }
}
