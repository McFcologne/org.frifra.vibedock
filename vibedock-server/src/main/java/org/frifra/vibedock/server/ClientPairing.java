package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

/**
 * First contact is made by the server: the administrator enters the device IP and the pairing code shown on its
 * screen. The server posts its addresses, a fresh device key and the poll interval to the device; from then on the
 * device pulls its image. Only IP literals are accepted as targets, no host names.
 */
@Service
public class ClientPairing {
    private final ClientRegistry registry;private final HttpClient http;private final ObjectMapper json;private final TlsTrust trust;private final OtaSigning signing;
    private final SecureRandom random=new SecureRandom();
    public ClientPairing(ClientRegistry registry,HttpClient http,ObjectMapper json,TlsTrust trust,OtaSigning signing){
        this.registry=registry;this.http=http;this.json=json;this.trust=trust;this.signing=signing;
    }
    /** The paired device and the https addresses that could not be announced, with the reason. */
    public record Invitation(ClientRegistry.View view,List<String> warnings) {}

    /**
     * @param servers addresses announced to the device in order of preference (internal first, then external).
     * For https addresses the root certificate of their chain goes along as the device's only trust anchor. Every
     * configured address is announced; when the server cannot get or use the certificate of an https address (not
     * reachable from the server, not trusted, a second root), it goes without one and the warning says so: the
     * device uses an https address only with its certificate. The device supports one https root.
     */
    public Invitation invite(String ip,int port,String code,String name,List<String> servers) throws Exception {return invite(ip,port,code,name,"",servers);}
    /** @param board the board chosen in the administration; current firmware names its own, which wins. */
    public Invitation invite(String ip,int port,String code,String name,String board,List<String> servers) throws Exception {
        String target=ip==null?"":ip.trim();IpNetworks.literal(target);
        if(port<1||port>65535)throw new IllegalArgumentException("Invalid device port");
        if(code==null||!code.trim().matches("[0-9]{6}"))throw new IllegalArgumentException("The pairing code has 6 digits");
        var pairing=pairing(name,servers);
        Map<String,Object> body=new LinkedHashMap<>();body.put("code",code.trim());body.putAll(pairing.body());
        String host=target.contains(":")?"["+target+"]":target;
        var request=HttpRequest.newBuilder(URI.create("http://"+host+":"+port+"/pair")).timeout(Duration.ofSeconds(8))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        HttpResponse<byte[]> response;
        try{response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
        catch(Exception e){throw new IllegalArgumentException("Device not reachable at "+target+":"+port);}
        if(response.statusCode()==403)throw new IllegalArgumentException("The device rejected the pairing code");
        if(response.statusCode()==429)throw new IllegalArgumentException("Too many wrong codes; restart the device to get a new one");
        if(response.statusCode()!=200||response.body().length>1024)throw new IllegalArgumentException("Unexpected answer from the device (HTTP "+response.statusCode()+")");
        var reply=json.readTree(response.body());
        String id=reply.path("identifier").asText("");
        String deviceName=name!=null&&!name.isBlank()?name.trim():reply.path("name").asText("VibeDock");
        String reported=reply.path("board").asText("");
        registry.paired(id,deviceName,pairing.key(),target,port,Board.find(reported).or(()->Board.find(board)).map(Board::id).orElse(null));
        return new Invitation(registry.get(id),pairing.warnings());
    }

    /** Device address until its first pull, for a device set up over USB: not reachable for an immediate pull. */
    static final String UNKNOWN_IP="0.0.0.0";
    /** The pairing for a device set up over USB by the browser flasher, and warnings about its https addresses. */
    public record Setup(Map<String,Object> pairing,List<String> warnings) {}
    /**
     * Pairs a device that is set up over USB right after flashing: the browser hands this pairing to the device
     * together with the Wi-Fi it entered. The device is registered now and gets its address with its first pull.
     */
    public Setup setUp(String identifier,String name,List<String> servers) throws Exception {return setUp(identifier,name,null,servers);}
    /** @param board the board the display reports over USB, or the one chosen on the firmware page. */
    public Setup setUp(String identifier,String name,String board,List<String> servers) throws Exception {
        if(identifier==null||!identifier.matches("vibedock-[0-9a-f]{12}"))throw new IllegalArgumentException("Unexpected device identifier");
        String deviceName=name==null||name.isBlank()?"VibeDock "+identifier.substring(identifier.length()-6):name.trim();
        var pairing=pairing(deviceName,servers);
        registry.paired(identifier,deviceName,pairing.key(),UNKNOWN_IP,80,Board.find(board).map(Board::id).orElse(null));
        return new Setup(pairing.body(),pairing.warnings());
    }

    /** What the device takes over when paired (the firmware's applyPairing), its key and warnings about https addresses. */
    private record Pairing(Map<String,Object> body,String key,List<String> warnings) {}
    private Pairing pairing(String name,List<String> servers) {
        if(servers.isEmpty())throw new IllegalArgumentException("No server address to announce");
        servers.forEach(ClientRegistry::validateServerUrl);
        List<String> announced=new ArrayList<>(servers),warnings=new ArrayList<>();byte[] anchor=null;String pin=null;
        String without="; announced without its certificate, so the display cannot use this address";
        for(String url:servers){
            if(!url.startsWith("https://"))continue;
            try{
                var inspection=trust.forPairing(url);byte[] root=inspection.root();
                if(anchor!=null&&!Arrays.equals(anchor,root)){warnings.add(url+": a second https root is not supported"+without);continue;}
                if(root.length>2048){warnings.add(url+": root certificate too large for the device"+without);continue;}
                anchor=root;pin=inspection.fingerprint();
                if(inspection.fromLog())warnings.add(url+" is not reachable from the server; its root certificate comes from the public certificate log (crt.sh)");
            }catch(IllegalArgumentException e){warnings.add(e.getMessage()+without);}
        }
        byte[] raw=new byte[32];random.nextBytes(raw);String key=HexFormat.of().formatHex(raw);
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("servers",announced);
        if(anchor!=null){body.put("anchor",Base64.getEncoder().encodeToString(anchor));if(pin!=null)body.put("pin",pin);}body.put("key",key);body.put("pollSeconds",registry.pollSeconds());
        if(name!=null&&!name.isBlank())body.put("name",name.trim());
        // The device installs updates over Wi-Fi only when they are signed with this server's key.
        body.put("otaKey",signing.publicKeyPem());
        return new Pairing(body,key,warnings);
    }
}
