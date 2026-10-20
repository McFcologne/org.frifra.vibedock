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
    @Test void fixedBacksOffOnRateLimitAndRecovers() {
        var pace=new PollPace(new SettingsStore.UsagePolling(false,1,30));long now=MINUTE;
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
    @Test void dynamicLearnsALongerIntervalAndWaitsTwiceOnce() {
        var pace=new PollPace(new SettingsStore.UsagePolling(true,1,3));long now=MINUTE;
        pace.rateLimited(now,0);
        assertEquals(2,pace.minutes());assertEquals(4,pace.waitMinutes(now));
        assertFalse(pace.due(now+180,false));assertTrue(pace.due(now+240,false));
        now+=240;pace.success(now);
        assertEquals(2,pace.minutes());assertFalse(pace.limited());assertTrue(pace.due(now+120,false));
        pace.rateLimited(now,0);assertEquals(3,pace.minutes());
        pace.rateLimited(now,0);assertEquals(3,pace.minutes());  // never above the longest
    }
    @Test void dynamicTriesShorterAfterAnHourAndWaitsLongerWhenThatFails() {
        var pace=new PollPace(new SettingsStore.UsagePolling(true,1,10));long now=MINUTE;
        pace.rateLimited(now,0);pace.rateLimited(now,0);pace.rateLimited(now,0);
        assertEquals(4,pace.minutes());
        now+=59*60;pace.success(now);assertEquals(4,pace.minutes());  // not yet an hour
        now+=60;pace.success(now);assertEquals(3,pace.minutes());     // an hour without 429: one minute less
        pace.rateLimited(now,0);assertEquals(4,pace.minutes());       // too short: back up
        now+=119*60;pace.success(now);assertEquals(4,pace.minutes()); // now it waits two hours
        now+=60;pace.success(now);assertEquals(3,pace.minutes());
        pace.rateLimited(now,0);
        now+=239*60;pace.success(now);assertEquals(4,pace.minutes()); // then four hours
        now+=60;pace.success(now);assertEquals(3,pace.minutes());
        now+=240*60;pace.success(now);assertEquals(2,pace.minutes()); // 3 held four hours: back to one hour
        now+=60*60;pace.success(now);assertEquals(1,pace.minutes());
        now+=60*60;pace.success(now);assertEquals(1,pace.minutes());  // never below the shortest
    }
    @Test void newLimitsKeepTheIntervalInside() {
        var pace=new PollPace(new SettingsStore.UsagePolling(true,1,10));
        pace.rateLimited(MINUTE,0);pace.rateLimited(MINUTE,0);pace.rateLimited(MINUTE,0);
        pace.configure(new SettingsStore.UsagePolling(true,1,3));assertEquals(3,pace.minutes());
        pace.configure(new SettingsStore.UsagePolling(true,5,10));assertEquals(5,pace.minutes());
        pace.configure(new SettingsStore.UsagePolling(false,2,10));assertEquals(2,pace.minutes());  // another mode starts at the shortest
    }
    @Test void restartPollsAtOnceButNotDuringABackOff() {
        var pace=new PollPace();
        pace.success(MINUTE);pace.restart();assertTrue(pace.due(MINUTE+1,false));
        pace.rateLimited(MINUTE,0);pace.restart();assertFalse(pace.due(MINUTE+1,false));
    }
    @Test void limitsAreValidated() {
        assertThrows(IllegalArgumentException.class,()->new SettingsStore.UsagePolling(true,0,10));
        assertThrows(IllegalArgumentException.class,()->new SettingsStore.UsagePolling(true,5,4));
        assertThrows(IllegalArgumentException.class,()->new SettingsStore.UsagePolling(true,1,61));
    }
}
