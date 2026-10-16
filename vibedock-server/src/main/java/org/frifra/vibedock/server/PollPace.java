package org.frifra.vibedock.server;

/**
 * Poll pacing of one provider. The first poll runs at once; afterwards the next poll waits at least one interval and
 * then for the next full minute. The interval is one minute normally; after HTTP 429 it doubles up to 30 minutes
 * (never earlier than Retry-After), and each successful poll halves it again.
 */
final class PollPace {
    static final int MIN_MINUTES=1, MAX_MINUTES=30;
    private int minutes=MIN_MINUTES;
    private long nextAt;
    private boolean limited;

    int minutes(){return minutes;}
    boolean limited(){return limited;}
    /** A manual refresh skips the regular schedule but never a rate-limit back-off. */
    boolean due(long now,boolean manual){return now>=nextAt||manual&&!limited;}
    void success(long now){limited=false;minutes=Math.max(MIN_MINUTES,minutes/2);nextAt=slot(now,minutes);}
    void rateLimited(long now,long retryAt){limited=true;minutes=Math.min(MAX_MINUTES,minutes*2);nextAt=Math.max(slot(now,minutes),retryAt);}
    void failed(long now){limited=false;nextAt=slot(now,minutes);}
    /** Readings older than one interval plus a grace period count as stale. */
    long staleAfterSeconds(){return minutes*60L+90;}
    private static long slot(long now,int minutes){return (now+minutes*60L+59)/60*60;}
}
