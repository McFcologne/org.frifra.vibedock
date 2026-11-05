package org.frifra.vibedock.server;

import java.util.*;

/**
 * Spreads the pulls of several displays over a few seconds, so they do not hit the server at the same moment. Every
 * display gets a fixed step on top of the interval the server announces: the slots are 0, s, 2s, ... up to
 * {@value #MAX_OFFSET} seconds (s = {@value #MAX_OFFSET} / (displays - 1), at least 1 s), the order of the displays is
 * drawn anew every round. A display takes a free slot at random and keeps it for the whole round, however often it
 * asks. A round ends when every display of it has a slot and one asks again, or when the number of displays changes.
 * Nothing is added for a single display or for intervals shorter than {@value #MIN_INTERVAL} s.
 */
final class PullSpread {
    static final int MAX_OFFSET=10,MIN_INTERVAL=20;
    private final Random random;private final Map<String,Integer> slots=new HashMap<>();private int roundSize;
    PullSpread(Random random){this.random=random;}
    /** The seconds to add for a display that is announced an interval of {@code seconds}; devices: how many displays pull. */
    synchronized int offset(String device,int seconds,int devices){
        if(seconds<MIN_INTERVAL||devices<2)return 0;
        if(devices!=roundSize){slots.clear();roundSize=devices;}  // other displays: a new round
        Integer kept=slots.get(device);
        if(kept!=null){
            if(slots.size()<devices)return step(kept,devices);  // others still wait for their slot: keep it
            slots.clear();  // everyone had a slot and it asks again: the round is over
        }
        var free=new ArrayList<Integer>();
        for(int slot=0;slot<devices;slot++)if(!slots.containsValue(slot))free.add(slot);
        if(free.isEmpty()){slots.clear();for(int slot=0;slot<devices;slot++)free.add(slot);}
        int slot=free.get(random.nextInt(free.size()));
        slots.put(device,slot);
        return step(slot,devices);
    }
    private static int step(int slot,int devices){return Math.min(MAX_OFFSET,slot*Math.max(1,MAX_OFFSET/(devices-1)));}
    /** A display that no longer pulls: the set of displays changed, the round starts anew. */
    synchronized void forget(String device){if(slots.remove(device)!=null)slots.clear();}
}
