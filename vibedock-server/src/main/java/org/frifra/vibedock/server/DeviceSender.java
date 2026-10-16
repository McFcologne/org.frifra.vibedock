package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class DeviceSender {
    private final HttpClient http;
    private final ObjectMapper json;
    private final ZoneId zone;
    private volatile Map<String,String> status=Map.of();
    public DeviceSender(HttpClient http,ObjectMapper json,@Value("${vibedock.time-zone}") String zone) {
        this.http=http; this.json=json; this.zone=ZoneId.of(zone);
    }
    public Map<String,String> status() { return status; }
    public void send(List<SettingsStore.Device> devices,UsageService.Snapshot snapshot) {
        Map<String,String> result=new LinkedHashMap<>();
        for (var device:devices) {
            if (!device.enabled()) { result.put(device.name(),"Deaktiviert"); continue; }
            try {
                byte[] body=frame(snapshot);
                var request=HttpRequest.newBuilder(URI.create(device.url().replaceAll("/$","")+"/frame"))
                        .timeout(Duration.ofSeconds(20)).header("X-VibeDock-Token",device.token())
                        .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                var response=http.send(request,HttpResponse.BodyHandlers.discarding());
                result.put(device.name(),response.statusCode()==200?"Sent":"HTTP " + response.statusCode());
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch (Exception e) { result.put(device.name(),"Unreachable"); }
        }
        status=Map.copyOf(result);
    }
    byte[] frame(UsageService.Snapshot snapshot) throws Exception {
        List<Map<String,Object>> primitives=new ArrayList<>();
        primitives.add(Map.of("type","rect","x",0,"y",0,"width",240,"height",240,"color","#000000"));
        int y=8;
        for (String name:snapshot.providers().keySet().stream().limit(2).toList()) {
            var p=snapshot.providers().getOrDefault(name,new UsageService.Provider("unconfigured",null,null,null));
            String state=p.status().equals("ok")?"":p.status().equals("partial")?" (partial)":" ("+statusLabel(p.status())+")";
            primitives.add(text(8,y,snapshot.label(name)+state,"#80D8FF")); y+=27;
            if (p.period()==null) { primitives.add(text(8,y,line("5h",p.fiveHour()),"#FFFFFF")); y+=27; }
            primitives.add(text(8,y,line(p.period()==null?"Week":p.period(),p.week()),"#FFFFFF")); y+=p.period()==null?36:63;
        }
        primitives.add(text(8,220,"Updated " + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)
                .format(Instant.ofEpochSecond(snapshot.generatedAt())),"#AAAAAA"));
        int revision=Math.floorMod(json.writeValueAsString(primitives).hashCode(),Integer.MAX_VALUE-1)+1;
        Map<String,Object> frame=new LinkedHashMap<>();
        frame.put("v",2);frame.put("provider","codex");frame.put("label","Codex + Claude");
        frame.put("session",0);frame.put("weekly",0);frame.put("usageUnavailable",true);
        frame.put("sessionUnavailable",true);frame.put("weeklyUnavailable",true);frame.put("usageMode","used");
        frame.put("themeSpec",Map.of("themeSpecVersion",1,"themeId","server-overview","themeRev",revision,"primitives",primitives));
        byte[] bytes=json.writeValueAsBytes(frame);
        if (bytes.length>2048) throw new IllegalArgumentException("Frame exceeds 2048 bytes");
        return bytes;
    }
    private Map<String,Object> text(int x,int y,String value,String color) {
        return Map.of("type","text","x",x,"y",y,"font",2,"fontSize",16,"maxWidth",224,"fit","shrink","color",color,"text",value);
    }
    private String line(String label,ProviderClient.Window w) {
        if (w==null) return label+": --";
        return label+": "+String.format(Locale.ROOT,"%.1f%%",w.usedPercent())+"  R "
                +(w.resetsAt()<=0?"--":DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(zone).format(Instant.ofEpochSecond(w.resetsAt())));
    }
    static String statusLabel(String status) {
        return switch (status) {
            case "authentication_required" -> "Login required";
            case "rate_limited" -> "Rate limited";
            case "stale" -> "stale";
            case "unconfigured","waiting" -> "not ready";
            case "suspended" -> "paused";
            default -> "unavailable";
        };
    }
}
