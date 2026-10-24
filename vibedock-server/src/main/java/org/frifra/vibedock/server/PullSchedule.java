package org.frifra.vibedock.server;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When a display should pull next (X-VibeDock-Next-Pull): shortly after its picture can change, instead of a fixed
 * interval. That is the earliest next poll of the sources it shows plus the time a collection takes and two seconds,
 * the moment a reading turns stale, the next photo of a running slideshow or the start or end of a scheduled one.
 * "pending" means a source of the display is being asked right now: the display comes back when the collection should
 * be done, and less often while it is not (2, 5, 10, 30 s), so a slow provider cannot make it pull every second.
 * An upper limit keeps the display in touch: displays that took an immediate pull (POST /pull) within a day see design
 * changes at once and may wait up to ten minutes, the others two.
 */
@Component
class PullSchedule {
    static final int MIN_SECONDS=2, REACHABLE_SECONDS=600, UNREACHABLE_SECONDS=120;
    static final int[] PENDING_STEPS={2,5,10,30};
    static final long REACHED_WITHIN=24*3600;
    record Next(int seconds,boolean pending) {
        String header(){return seconds+"; state="+(pending?"pending":"current");}
    }
    private final UsageService usage;private final DesignService designs;private final ClientRegistry registry;private final DevicePoke poke;
    private final java.time.Clock clock;
    private final Map<String,Integer> pendingInRow=new ConcurrentHashMap<>();
    PullSchedule(UsageService usage,DesignService designs,ClientRegistry registry,DevicePoke poke,java.time.Clock clock){
        this.usage=usage;this.designs=designs;this.registry=registry;this.poke=poke;this.clock=clock;
    }
    /** preview: the status page's copy; it neither counts as waiting nor changes the display's schedule. */
    Next next(String device,DisplayType display,boolean preview){
        long now=clock.instant().getEpochSecond();
        int limit=limit(device);
        var design=registry.designFor(device);
        // A running slideshow changes with every photo: one pull per photo, as before.
        if(!designs.activePhotos(design,device).isEmpty())
            return current(device,Math.min(limit,designs.pollSeconds(design,registry.pollSeconds(),device)),preview);
        var sources=new LinkedHashSet<String>();
        for(var band:design.layout().fit(display).bands())if(band instanceof Layout.Usage u)sources.addAll(u.sources());
        var schedule=usage.schedule(sources);
        if(schedule.pending()){
            int steps=preview?1:pendingInRow.merge(device,1,Integer::sum);
            long wait=Math.max(schedule.at()-now,PENDING_STEPS[Math.min(steps,PENDING_STEPS.length)-1]);
            return new Next((int)Math.min(limit,Math.max(MIN_SECONDS,wait)),true);
        }
        long at=schedule.at();
        Long boundary=designs.nextPhotoBoundary(design);if(boundary!=null)at=Math.min(at,boundary+1);
        long wait=at==Long.MAX_VALUE?limit:at-now;
        return current(device,(int)Math.max(MIN_SECONDS,Math.min(limit,wait)),preview);
    }
    private Next current(String device,int seconds,boolean preview){
        if(!preview)pendingInRow.remove(device);
        return new Next(seconds,false);
    }
    private int limit(String device){
        try{return poke.reachedWithin(registry.address(device),REACHED_WITHIN)?REACHABLE_SECONDS:UNREACHABLE_SECONDS;}
        catch(IllegalArgumentException e){return UNREACHABLE_SECONDS;}
    }
}
