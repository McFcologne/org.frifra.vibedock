package org.frifra.vibedock.server;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What displays with a battery report with every pull (X-VibeDock-Power): "battery" or "battery;level=60" while they
 * run on their battery, nothing on USB. Their image then shows a small battery icon at the top right; the device
 * card's preview shows it as well. In memory only: the next pull reports it again.
 */
@Component
class DevicePower {
    private final Map<String,StatusImage.Battery> onBattery=new ConcurrentHashMap<>();
    /** The header of a pull; a missing or unknown value means on USB. */
    void report(String identifier,String header){
        var battery=parse(header);
        if(battery.isPresent())onBattery.put(identifier,battery.get());else onBattery.remove(identifier);
    }
    /** The display's battery while it runs on it, else null. */
    StatusImage.Battery battery(String identifier){return identifier==null?null:onBattery.get(identifier);}
    /** "battery", optionally ";level=" 0–100 (rounded to steps of 20 by the device); anything else is not a battery. */
    static Optional<StatusImage.Battery> parse(String header){
        if(header==null)return Optional.empty();
        var parts=header.trim().split(";");
        if(!parts[0].trim().equals("battery"))return Optional.empty();
        Integer level=null;
        for(int i=1;i<parts.length;i++){
            var kv=parts[i].trim().split("=",2);
            if(kv.length==2&&kv[0].equals("level")&&kv[1].matches("[0-9]{1,3}")){int v=Integer.parseInt(kv[1]);if(v<=100)level=v;}
        }
        return Optional.of(new StatusImage.Battery(level));
    }
}
