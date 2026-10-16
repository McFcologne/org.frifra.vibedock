package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PollPaceTest {
    static final long MINUTE=1_800_000_000L/60*60;

    @Test void pollsOnFullMinutes() {
        var pace=new PollPace();
        assertTrue(pace.due(MINUTE+3,false));
        pace.success(MINUTE+3);
        assertFalse(pace.due(MINUTE+119,false));
        assertTrue(pace.due(MINUTE+120,false));
        pace.success(MINUTE+120);
        assertFalse(pace.due(MINUTE+179,false));
        assertTrue(pace.due(MINUTE+180,false));
        assertTrue(pace.due(MINUTE+30,true));
        assertEquals(150,pace.staleAfterSeconds());
    }
    @Test void backsOffOnRateLimitAndRecovers() {
        var pace=new PollPace();long now=MINUTE;
        for (int expected:new int[]{2,4,8,16,30,30}) { pace.rateLimited(now,0);assertEquals(expected,pace.minutes()); }
        assertTrue(pace.limited());
        assertFalse(pace.due(now+60,true));
        assertTrue(pace.due(now+30*60,false));
        for (int expected:new int[]{15,7,3,1,1}) { pace.success(now);assertEquals(expected,pace.minutes()); }
        assertFalse(pace.limited());
    }
    @Test void neverPollsBeforeRetryAfter() {
        var pace=new PollPace();
        pace.rateLimited(MINUTE,MINUTE+600);
        assertFalse(pace.due(MINUTE+120,false));
        assertTrue(pace.due(MINUTE+600,false));
    }
    @Test void otherFailuresKeepTheInterval() {
        var pace=new PollPace();
        pace.rateLimited(MINUTE,0);pace.failed(MINUTE);
        assertEquals(2,pace.minutes());assertFalse(pace.limited());
        assertTrue(pace.due(MINUTE+120,false));
    }
}
