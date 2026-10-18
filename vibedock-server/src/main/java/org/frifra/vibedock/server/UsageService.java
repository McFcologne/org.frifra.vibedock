package org.frifra.vibedock.server;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class UsageService {
    /**
     * The reading of one source. period is null for the usual 5-hour and weekly windows; sources with a single
     * longer window (Cursor: the billing month) name it there and use only week.
     */
    public record Provider(String status,Long observedAt,ProviderClient.Window fiveHour,ProviderClient.Window week,String period) {
        public Provider(String status,Long observedAt,ProviderClient.Window fiveHour,ProviderClient.Window week){this(status,observedAt,fiveHour,week,null);}
        Provider withPeriod(String next){return new Provider(status,observedAt,fiveHour,week,next);}
    }
    /**
     * The name of a source's single window, or null for the usual 5-hour and weekly windows: Cursor bills by month,
     * and some Codex plans only have a window of about a month.
     */
    static String period(String type,Provider p){
        if(type.equals("cursor"))return "Month";
        if(type.equals("gemini"))return "Day";
        if(type.equals("grok"))return p!=null&&p.week()!=null&&p.week().seconds()>=20L*86400?"Month":"Week";
        if(p!=null&&p.fiveHour()==null&&p.week()!=null&&p.week().seconds()>=20L*86400)return "Month";
        return null;
    }
    /** providers and labels are keyed by data source id, in the order of the sources; labels are the display names. */
    public record Snapshot(int schemaVersion,long generatedAt,Map<String,Provider> providers,Map<String,String> labels) {
        public Snapshot { labels=labels==null?Map.of():labels; }
        public Snapshot(int schemaVersion,long generatedAt,Map<String,Provider> providers){this(schemaVersion,generatedAt,providers,Map.of());}
        /** Display name of a source; ids without a label are capitalised. */
        public String label(String id){
            String label=labels.get(id);if(label!=null)return label;
            return id.equals("codex")?"Codex":id.equals("claude")?"Claude":id.isEmpty()?id:id.substring(0,1).toUpperCase(Locale.ROOT)+id.substring(1);
        }
    }
    private final SettingsStore settings;
    private final ProviderClient client;
    private final DeviceSender devices;
    private final Clock clock;
    private volatile Map<String,Provider> readings=Map.of();
    private volatile SettingsStore.Settings collectedFor;
    private final Map<String,PollPace> pace=new ConcurrentHashMap<>();
    private static final Logger log=LoggerFactory.getLogger(UsageService.class);
    private final Map<String,String> logged=new ConcurrentHashMap<>();
    /** Logs a provider only when its status changes, so a steady state does not fill the log. */
    private void report(SettingsStore.Account account,String status,String detail){
        report(account.label()+" ("+account.typeLabel(account.type())+")",account.id(),status,detail);
    }
    private void report(String name,String key,String status,String detail){
        String line=status+(detail==null||detail.isEmpty()?"":" ("+detail+")");
        if(line.equals(logged.put(key,line)))return;
        if(status.equals("ok")||status.equals("partial"))log.info("{} usage: {}",name,line);
        else log.warn("{} usage: {}",name,line);
    }
    @Value("${vibedock.polling:true}") private boolean polling=true;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private SignInRenewal renewal;
    /** DEMO_USAGE: sources need no sign-in, DemoUsage makes up their readings. */
    @org.springframework.beans.factory.annotation.Value("${vibedock.demo-usage:false}") private boolean demo;
    public UsageService(SettingsStore settings,ProviderClient client,DeviceSender devices,Clock clock) {
        this.settings=settings; this.client=client; this.devices=devices; this.clock=clock;
    }
    public Snapshot snapshot() {
        Map<String,Provider> view=new LinkedHashMap<>();Map<String,String> labels=new LinkedHashMap<>(); long now=clock.instant().getEpochSecond();
        var s=settings.effective();
        for (var account:s.accounts()) {
            String name=account.id();var credential=account.manual();labels.put(name,account.label());
            Provider p=account.suspended() ? new Provider("suspended",null,null,null)
                    : credential.token().isBlank()&&!demo ? new Provider("unconfigured",null,null,null)
                    : s.equals(collectedFor) ? readings.getOrDefault(name,new Provider("waiting",null,null,null))
                    : new Provider("waiting",null,null,null);
            if (p.observedAt()!=null && now-p.observedAt()>staleAfter(name) && (p.status().equals("ok") || p.status().equals("partial")))
                p=new Provider("stale",p.observedAt(),null,null);
            view.put(name,p.withPeriod(period(account.type(),p)));
        }
        return new Snapshot(1,now,Collections.unmodifiableMap(view),Collections.unmodifiableMap(labels));
    }
    private long staleAfter(String name) {
        var p=pace.get(name);return p==null?PollPace.MIN_MINUTES*60L+90:p.staleAfterSeconds();
    }
    /** First values right after start; the full-minute schedule takes over from there. */
    @EventListener(ApplicationReadyEvent.class)
    public void started() {
        if (!polling) { firstCollection.complete(null); return; }
        Thread.ofVirtual().name("usage-first-poll").start(()->{
            try { collect(false); } finally { firstCollection.complete(null); }
        });
    }
    private final java.util.concurrent.CompletableFuture<Void> firstCollection=new java.util.concurrent.CompletableFuture<>();
    /** Completes once the readings collected right after start are available (or collection is off). */
    java.util.concurrent.CompletableFuture<Void> firstCollection() { return firstCollection; }
    private void pollNow() { if (polling) Thread.ofVirtual().name("usage-first-poll").start(()->collect(false)); }
    /**
     * While rate limited, the last values stay visible with a hint on the display. A window whose reset time has
     * passed is dropped, because its value no longer applies.
     */
    private Provider rateLimited(Provider last,long now) {
        if (last==null) return new Provider("rate_limited",null,null,null);
        return new Provider("rate_limited",last.observedAt(),current(last.fiveHour(),now),current(last.week(),now));
    }
    private static ProviderClient.Window current(ProviderClient.Window w,long now) { return w!=null&&(w.resetsAt()<=0||w.resetsAt()>now)?w:null; }
    /** Scheduled poll on every full minute; each provider is only asked when its pace says so. */
    @Scheduled(cron="0 * * * * *")
    public void scheduled() { collect(false); }
    /** Manual refresh from the administration; still respects a rate-limit back-off. */
    public void refresh() { collect(true); }
    private synchronized void collect(boolean manual) {
        // Grok, Gemini and Claude sign-ins are renewed by their CLI shortly before they expire.
        if (renewal!=null&&!demo) for (var a:settings.get().accounts())
            if (a.manual().token().isBlank()&&!a.suspended()) renewal.renewIfDue(a,settings.authDirectory(a));
        var configuration=settings.effective(); Map<String,Provider> next=new LinkedHashMap<>();
        for (var account:configuration.accounts()) {
            String name=account.id();var credential=account.manual();
            if (account.suspended()) { pace.remove(name); next.put(name,new Provider("suspended",null,null,null)); continue; }
            if (credential.token().isBlank()&&!demo) { pace.remove(name); next.put(name,new Provider("unconfigured",null,null,null)); continue; }
            var last=readings.get(name);Long previous=last==null?null:last.observedAt();
            var p=pace.computeIfAbsent(name,k->new PollPace());
            long now=clock.instant().getEpochSecond();
            if (!p.due(now,manual)) {
                next.put(name,p.limited()?rateLimited(last,now):last!=null?last:new Provider("waiting",null,null,null));
                continue;
            }
            try {
                var r=client.fetch(account.type(),credential);
                boolean single=period(account.type(),new Provider("ok",null,r.fiveHour(),r.week()))!=null;
                String status=r.fiveHour()==null && r.week()==null ? "unavailable"
                        : !single && (r.fiveHour()==null || r.week()==null) ? "partial":"ok";
                p.success(now);report(account,status,"");
                next.put(name,new Provider(status,clock.instant().getEpochSecond(),r.fiveHour(),r.week()));
            } catch (ProviderClient.Failure e) {
                report(account,e.status,e.detail+(e.status.equals("rate_limited")?", next try in "+Math.min(PollPace.MAX_MINUTES,p.minutes()*2)+" min":""));
                if (e.status.equals("rate_limited")) { p.rateLimited(now,e.retryAt); next.put(name,rateLimited(last,now)); }
                else { p.failed(now); next.put(name,new Provider(e.status,previous,null,null)); }
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            catch (Exception e) { p.failed(now); report(account,"upstream_error",e.toString()); next.put(name,new Provider("upstream_error",previous,null,null)); }
        }
        pace.keySet().retainAll(configuration.accounts().stream().map(SettingsStore.Account::id).toList());
        if (!configuration.equals(settings.effective())) return;
        readings=Map.copyOf(next);
        collectedFor=configuration;
        devices.send(configuration.devices(),snapshot());
    }
    /**
     * Sources were paused, resumed, renamed or reordered: every source keeps its values, their time and its polling
     * pace (configurationChanged would show all of them as waiting until the next collection); a resumed one is
     * polled right away.
     */
    public synchronized void suspensionChanged() {
        var configuration=settings.effective();Map<String,Provider> next=new LinkedHashMap<>(readings);
        for (var account:configuration.accounts()) {
            var last=readings.get(account.id());
            if (account.suspended()) { pace.remove(account.id()); next.put(account.id(),new Provider("suspended",null,null,null)); }
            else if (last!=null && last.status().equals("suspended")) next.remove(account.id());
        }
        readings=Map.copyOf(next);collectedFor=configuration;
        devices.send(configuration.devices(),snapshot());
        pollNow();
    }
    public synchronized void configurationChanged() { readings=Map.of(); collectedFor=null; pace.clear(); pollNow(); }
}
