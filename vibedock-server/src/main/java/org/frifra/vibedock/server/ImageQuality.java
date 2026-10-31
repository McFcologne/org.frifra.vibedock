package org.frifra.vibedock.server;

import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** Automatic collection-wide quality reference; never blocks a device pull on the calibration. */
@Component
class ImageQuality {
    record Policy(int preferred,int target) {}
    record Stats(Map<Integer,Double> worst) {}
    record Calibration(Map<String,Stats> themes,Map<Integer,Integer> targets) {}
    private final ThemeSettings themes;private final StatusImage renderer;private final Twemoji emoji;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"image-quality");t.setDaemon(true);return t;});
    private ThemeLibrary.Snapshot source;private volatile Calibration ready;private Future<?> task;private volatile boolean failed;
    ImageQuality(ThemeSettings themes,StatusImage renderer,Twemoji emoji){this.themes=themes;this.renderer=renderer;this.emoji=emoji;}
    synchronized Policy policy(Scheme theme,int base){
        var snapshot=themes.snapshot();
        if(source!=snapshot){source=snapshot;ready=null;failed=false;if(task!=null)task.cancel(true);task=worker.submit(()->{
            try{var result=calibrate(snapshot,DisplayType.INVENTORY);synchronized(this){if(source==snapshot)ready=result;}}
            catch(Exception e){synchronized(this){if(source==snapshot)failed=true;}if(!(e instanceof InterruptedException))org.slf4j.LoggerFactory.getLogger(ImageQuality.class).warn("Image quality calibration unavailable: {}",e.toString());}
        });}
        var reference=ready;
        // During the initial calculation, prefer one conservative upgrade rather than blocking the Wi-Fi transfer.
        if(reference==null)return new Policy(higher(base),50);
        var stats=reference.themes().get(theme.id());int target=reference.targets().getOrDefault(base,50);
        if(stats==null)return new Policy(higher(base),target);
        if(stats.worst().getOrDefault(base,100.0)>target)return new Policy(higher(base),target);
        int lower=lower(base);
        return new Policy(stats.worst().getOrDefault(lower,100.0)<=target?lower:base,target);
    }
    Calibration calibrate(ThemeLibrary.Snapshot snapshot,List<DisplayType> panels) throws Exception {
        var result=new LinkedHashMap<String,Stats>();var global=new HashMap<Integer,Double>();
        for(var item:snapshot.items().values()){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            var scheme=item.scheme();var worst=new HashMap<Integer,Double>();
            var icon=ThemeIcons.image(item.contents(),item.contents().manifest().defaultIcon(),Twemoji.SIZE).orElse(null);
            for(var panel:panels){if(panel.width()>420||panel.height()>420)continue;
                for(int state=0;state<3;state++){
                    var weather=emoji.image(state==2?"🌧":"⛅",24).orElse(null);
                    var image=renderer.render(reference(state),"192.168.1.50",new StatusImage.Look(icon,scheme,weather,state==2?"-2°":"13°",Layout.DEFAULT),panel);
                    for(int n:List.of(16,32,64,128,256)){
                        var reduced=ImagePalette.encode(image.rgb565(),panel.width(),panel.height(),n,image.accents(),image.bars(),image.primary());
                        double change=reduced.protectedColorsPreserved()?ImagePalette.changedPixels(image.rgb565(),reduced.pixels())*100.0/(panel.width()*panel.height()):100.0;
                        worst.merge(n,change,Math::max);global.merge(n,change,Math::max);
                    }
                }
            }
            result.put(scheme.id(),new Stats(Map.copyOf(worst)));
        }
        var targets=new HashMap<Integer,Integer>();for(int n:List.of(32,64,256))targets.put(n,corridor(global.getOrDefault(higher(n),0.0)));
        return new Calibration(Map.copyOf(result),Map.copyOf(targets));
    }
    static int corridor(double worstNext){return Math.max(40,Math.min(50,(int)Math.ceil(worstNext)));}
    static int higher(int colors){return colors==32?64:colors==64?256:0;}
    static int lower(int colors){return colors==32?16:colors==64?32:128;}
    static UsageService.Snapshot reference(int state){
        long now=1800000000L;
        var window=new ProviderClient.Window(state==0?1:83,1900000000L);
        return new UsageService.Snapshot(1,now,Map.of("codex",new UsageService.Provider(state==2?"authentication_required":"ok",state==2?null:now,state==2?null:window,window),
                "claude",new UsageService.Provider(state==1?"rate_limited":"ok",now,window,state==2?null:window)));
    }
    synchronized boolean calibrating(){return source!=null&&ready==null&&!failed;}
    @PreDestroy void close(){worker.shutdownNow();}
}
