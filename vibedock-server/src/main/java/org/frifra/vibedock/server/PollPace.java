package org.frifra.vibedock.server;

/**
 * Poll pacing of one data source. The first poll runs at once; afterwards the next poll waits at least one interval
 * and then for the next full minute. Never earlier than a Retry-After of the provider.
 * <ul>
 * <li><b>Fixed:</b> the interval is the shortest one; after HTTP 429 it doubles up to the longest one, and each
 *     successful poll halves it again.</li>
 * <li><b>Dynamic:</b> the source learns the interval its provider accepts. Each HTTP 429 lengthens it by one minute
 *     (up to the longest), and only the next poll waits twice as long. After an hour without 429 it tries one minute
 *     less (not below the shortest). A 429 at such a shorter interval goes back up and waits longer before the next
 *     try: two, then four hours. A shorter interval that lasts its whole wait resets the wait to one hour.</li>
 * </ul>
 */
final class PollPace {
    static final int MIN_MINUTES=1, MAX_MINUTES=60;
    static final int PROBE_MINUTES=60, MAX_PROBE_MINUTES=240;
    private SettingsStore.UsagePolling polling;
    private int minutes;
    private long nextAt;
    private boolean limited;
    /** Dynamic: when the interval last changed, how long it then stays, and whether it was lowered to try. */
    private long changedAt;
    private int probeMinutes=PROBE_MINUTES;
    private boolean probing;

    PollPace(){this(SettingsStore.UsagePolling.DEFAULT);}
    PollPace(SettingsStore.UsagePolling polling){this.polling=polling;minutes=polling.minMinutes();}

    int minutes(){return minutes;}
    /** When the source is polled next (epoch seconds); 0 or past while it is due. */
    long nextAt(){return nextAt;}
    boolean limited(){return limited;}
    boolean dynamic(){return polling.dynamic();}
    /** Minutes until the next poll, rounded up; at least the interval. */
    int waitMinutes(long now){return (int)Math.max(minutes,(nextAt-now+59)/60);}
    /** New bounds or mode from the settings: the interval stays within them; a mode change starts at the shortest. */
    void configure(SettingsStore.UsagePolling next){
        if(next.equals(polling))return;
        if(next.dynamic()!=polling.dynamic()){minutes=next.minMinutes();probing=false;probeMinutes=PROBE_MINUTES;}
        polling=next;minutes=Math.max(next.minMinutes(),Math.min(next.maxMinutes(),minutes));
    }
    /** A manual refresh skips the regular schedule but never a rate-limit back-off. */
    boolean due(long now,boolean manual){return now>=nextAt||manual&&!limited;}
    /** Changed sources: poll again at once, unless a rate-limit back-off is running. The learned interval stays. */
    void restart(){if(!limited)nextAt=0;}
    void success(long now){
        limited=false;
        if(!polling.dynamic())minutes=Math.max(polling.minMinutes(),minutes/2);
        else if(minutes>polling.minMinutes()&&now-changedAt>=probeMinutes*60L){
            if(probing)probeMinutes=PROBE_MINUTES;
            minutes--;probing=true;changedAt=now;
        }
        nextAt=slot(now,minutes);
    }
    void rateLimited(long now,long retryAt){
        limited=true;
        if(!polling.dynamic()){minutes=Math.min(polling.maxMinutes(),minutes*2);nextAt=Math.max(slot(now,minutes),retryAt);return;}
        if(probing)probeMinutes=Math.min(MAX_PROBE_MINUTES,probeMinutes*2);
        minutes=Math.min(polling.maxMinutes(),minutes+1);probing=false;changedAt=now;
        nextAt=Math.max(slot(now,2*minutes),retryAt);
    }
    void failed(long now){limited=false;nextAt=slot(now,minutes);}
    /** Readings older than one interval plus a grace period count as stale. */
    long staleAfterSeconds(){return minutes*60L+90;}
    private static long slot(long now,int minutes){return (now+minutes*60L+59)/60*60;}
}
