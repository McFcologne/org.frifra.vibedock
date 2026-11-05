package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Several displays do not pull in the same second: fixed steps, the order drawn anew every round. */
class PullSpreadTest {
    @Test void aRoundGivesEveryDisplayAnotherStepInRisingSteps(){
        var spread=new PullSpread(new Random(7));
        var offsets=new TreeSet<Integer>();
        for(String d:List.of("a","b","c","d","e","f"))offsets.add(spread.offset(d,60,6));
        assertEquals(new TreeSet<>(List.of(0,2,4,6,8,10)),offsets,"six displays: steps of two seconds up to ten");
    }
    @Test void theOrderIsDrawnAnewEveryRound(){
        var spread=new PullSpread(new Random(3));var orders=new HashSet<List<String>>();
        for(int round=0;round<12;round++){
            var order=new ArrayList<String>();var seen=new HashMap<String,Integer>();
            for(String d:List.of("a","b","c","d")){seen.put(d,spread.offset(d,60,4));}
            seen.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(e->order.add(e.getKey()));
            orders.add(order);
        }
        assertTrue(orders.size()>3,"different orders: "+orders.size());
    }
    @Test void aDisplayThatAsksAgainKeepsItsStepWhileOthersWait(){
        for(long seed=0;seed<50;seed++){
            var spread=new PullSpread(new Random(seed));
            int a=spread.offset("a",60,3),b=spread.offset("b",60,3);
            assertEquals(a,spread.offset("a",60,3),"a keeps its step");
            assertEquals(b,spread.offset("b",60,3),"b keeps its step");
            int c=spread.offset("c",60,3);
            assertEquals(3,new HashSet<>(List.of(a,b,c)).size(),"c gets the free step, seed "+seed);
        }
    }
    @Test void aRoundEndsWhenEveryDisplayHadItsSlotAndOneAsksAgain(){
        var spread=new PullSpread(new Random(2));
        for(int round=0;round<30;round++){
            var steps=new TreeSet<Integer>();
            for(String d:List.of("a","b","c"))steps.add(spread.offset(d,60,3));
            assertEquals(new TreeSet<>(List.of(0,5,10)),steps,"round "+round);
        }
    }
    @Test void aChangedNumberOfDisplaysStartsANewRound(){
        var spread=new PullSpread(new Random(4));
        spread.offset("a",60,3);spread.offset("b",60,3);
        var steps=new TreeSet<Integer>();
        for(String d:List.of("a","b","c","d"))steps.add(spread.offset(d,60,4));
        assertEquals(4,steps.size(),"four displays, four different steps");
    }
    @Test void aSingleDisplayAndShortIntervalsGetNothing(){
        var spread=new PullSpread(new Random(1));
        assertEquals(0,spread.offset("a",60,1));assertEquals(0,spread.offset("a",60,0));
        assertEquals(0,spread.offset("a",19,5));assertEquals(0,spread.offset("b",2,5));
    }
    @Test void moreDisplaysThanSlotsStillStayWithinTenSeconds(){
        var spread=new PullSpread(new Random(5));
        for(int i=0;i<40;i++){int o=spread.offset("d"+(i%25),60,25);assertTrue(o>=0&&o<=10,"offset "+o);}
        assertEquals(1,Math.max(1,PullSpread.MAX_OFFSET/24));
    }
    @Test void theAnnouncedTimeNeverPassesTheLimitAndTheStepsStayApart(){
        for(int limit:new int[]{120,600})for(int seconds:new int[]{20,115,120,300,600}){
            var spread=new PullSpread(new Random(limit+seconds));var announced=new TreeSet<Integer>();
            for(String d:List.of("a","b","c")){
                int next=spread.announce(d,Math.min(seconds,limit),limit,3);
                assertTrue(next<=limit,"limit "+limit+", interval "+seconds+": "+next);announced.add(next);
            }
            assertEquals(3,announced.size(),"three displays, three different times at limit "+limit+", interval "+seconds);
        }
        assertEquals(60,new PullSpread(new Random(1)).announce("a",60,120,1),"a single display keeps its interval");
        assertEquals(5,new PullSpread(new Random(1)).announce("a",5,120,3),"short intervals stay as they are");
    }
}
