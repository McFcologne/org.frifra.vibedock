package org.frifra.vibedock.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Bounded, optional sound metadata: only fresh quota windows of the sources fitted to this display. */
final class QuotaHeader {
    private QuotaHeader() {}
    static boolean requested(String capability,String preview){return "1".equals(capability)&&!"1".equals(preview);}
    static String create(UsageService.Snapshot snapshot,Layout layout) {
        var output=new StringBuilder("v1");var sources=new LinkedHashSet<String>();
        for(var band:layout.bands())if(band instanceof Layout.Usage u)sources.addAll(u.sources());
        for(String id:sources){
            var provider=snapshot.providers().get(id);
            if(provider==null||!("ok".equals(provider.status())||"partial".equals(provider.status())))continue;
            String hash=hash(id);
            append(output,hash+"f",provider.fiveHour(),snapshot.generatedAt());
            append(output,hash+"w",provider.week(),snapshot.generatedAt());
        }
        return output.toString();
    }
    private static void append(StringBuilder output,String id,ProviderClient.Window window,long now){
        if(window==null||!Double.isFinite(window.usedPercent())||window.usedPercent()<0||window.usedPercent()>100
                ||(window.resetsAt()>0&&window.resetsAt()<=now))return;
        long remaining=Math.round((100-window.usedPercent())*100);
        output.append(';').append(id).append(':').append(remaining);
    }
    private static String hash(String id){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)),0,6);}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
