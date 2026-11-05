package org.frifra.vibedock.server;

import java.util.*;

/**
 * Spreads the pulls of several displays over a few seconds, so they do not hit the server at the same moment. Every
 * display gets a fixed step on top of the interval the server announces: the slots are 0, s, 2s, ... up to
 * {@value #MAX_OFFSET} seconds (s = {@value #MAX_OFFSET} / (displays - 1), at least 1 s), the order of the displays is
 * drawn anew every round. A round ends when a display asks again; it takes a free slot at random. Nothing is added
 * for a single display or for intervals shorter than {@value #MIN_INTERVAL} s.
 */
final class PullSpread {
    static final int MAX_OFFSET=10,MIN_INTERVAL=20;
    private final Random random;private final Map<String,Integer> slots=new HashMap<>();
    PullSpread(Random random){this.random=random;}
    /** The seconds to add for a display that is announced an interval of {@code seconds}; devices: how many displays pull. */
    synchronized int offset(String device,int seconds,int devices){
        if(seconds<MIN_INTERVAL||devices<2)return 0;
        if(slots.containsKey(device))slots.clear();  // it asks again: the round is over
        var free=new ArrayList<Integer>();
        for(int slot=0;slot<devices;slot++)if(!slots.containsValue(slot))free.add(slot);
        if(free.isEmpty()){slots.clear();for(int slot=0;slot<devices;slot++)free.add(slot);}
        int slot=free.get(random.nextInt(free.size()));
        slots.put(device,slot);
        return Math.min(MAX_OFFSET,slot*Math.max(1,MAX_OFFSET/(devices-1)));
    }
    synchronized void forget(String device){slots.remove(device);}
}
