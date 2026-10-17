package org.frifra.vibedock.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * The administration pages: Overview (read only), Devices, Data sources and Server. The design editor is
 * {@link DesignController}. Each page shows and saves only its own settings.
 */
@Controller
class Controllers {
    private final SettingsStore store;
    private final UsageService usage;
    private final DeviceSender devices;
    private final ClientRegistry clients;
    private final ServerAddresses addresses;
    private final StorageErrors storage;
    private final String dataDir,logDir;
    private final Firmware firmware;
    private final ApplicationInfo info;
    private final AdminAccount admin;
    Controllers(SettingsStore store,UsageService usage,DeviceSender devices,ClientRegistry clients,ServerAddresses addresses,StorageErrors storage,
                Firmware firmware,ApplicationInfo info,AdminAccount admin,@Value("${vibedock.data-dir}") String dataDir,@Value("${vibedock.log-dir}") String logDir) {
        this.store=store; this.usage=usage; this.devices=devices;this.clients=clients;this.addresses=addresses;
        this.storage=storage;this.firmware=firmware;this.info=info;this.admin=admin;this.dataDir=dataDir;this.logDir=logDir;
    }
    @GetMapping("/login") String login() { return "login"; }
    @GetMapping("/api/v1/usage") @ResponseBody
    UsageService.Snapshot api() { return usage.snapshot(); }
    /** The user manual, opened in the browser's PDF viewer. */
    @GetMapping("/admin/manual.pdf") ResponseEntity<Resource> manual(){
        var file=info.manual().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=\"vibedock-manual-"+info.getVersion()+".pdf\"")
                .body(new FileSystemResource(file));
    }
    @ModelAttribute("storageProblem") String storageProblem(){return storage.writeProblem();}

    @GetMapping("/admin") String overview(Model model) {
        var snapshot=usage.snapshot();
        model.addAttribute("snapshot",snapshot);
        Map<String,String> figures=new LinkedHashMap<>();
        snapshot.providers().forEach((id,p)->figures.put(id,figures(p)));
        model.addAttribute("figures",figures);
        var effective=store.effective();
        Map<String,String> sourceDetails=new LinkedHashMap<>();
        for(var account:store.get().accounts()){
            boolean configured=effective.account(account.id()).map(a->!a.manual().token().isBlank()).orElse(false);
            sourceDetails.put(account.id(),SettingsStore.Account.typeLabel(account.type())+" · "+(configured?"credentials stored":"not connected"));
        }
        model.addAttribute("sourceDetails",sourceDetails);
        Map<String,String> sourceTypes=new LinkedHashMap<>();
        for(var account:store.get().accounts())sourceTypes.put(account.id(),account.type());
        model.addAttribute("sourceTypes",sourceTypes);
        model.addAttribute("defaultScheme",clients.defaultDesign().scheme().label);
        model.addAttribute("deviceStatus",devices.status());
        model.addAttribute("clients",clients.views());
        model.addAttribute("firmwareVersions",firmware.versions());
        return "admin";
    }
    /** Remaining share per window as on the display, e.g. "5h 80 % · Week 74 % left"; empty without values. */
    /** The badge text of a source's state, as the overview and the data sources page show it. */
    static String badge(UsageService.Provider p){
        return switch(p.status()){case "ok"->"Current";case "suspended"->"Paused";default->p.status().replace('_',' ');};
    }
    /** Current state of every source, for the pages to update their badges while a collection is pending. */
    @GetMapping(value="/admin/sources/state.json",produces="application/json") @ResponseBody Map<String,Map<String,String>> sourceState(){
        Map<String,Map<String,String>> out=new LinkedHashMap<>();
        usage.snapshot().providers().forEach((id,p)->out.put(id,Map.of("status",p.status(),"badge",badge(p),"figures",figures(p))));
        return out;
    }
    static String figures(UsageService.Provider p){
        List<String> parts=new ArrayList<>();
        if(p.period()==null&&p.fiveHour()!=null)parts.add("5h "+left(p.fiveHour()));
        if(p.week()!=null)parts.add((p.period()==null?"Week":p.period())+" "+left(p.week()));
        return parts.isEmpty()?"":String.join(" · ",parts)+" left";
    }
    private static String left(ProviderClient.Window w){return String.format(Locale.ROOT,"%.0f %%",Math.max(0,Math.min(100,100-w.usedPercent())));}
    @GetMapping("/admin/devices") String devices(Model model,HttpServletRequest request) {
        model.addAttribute("clients",clients.views());
        var versions=firmware.versions();  // per board: an uploaded build has its own version
        model.addAttribute("firmwareVersions",versions);model.addAttribute("boards",Board.INVENTORY);
        model.addAttribute("updatable",clients.views().stream().filter(c->c.ota()&&!c.updating()&&versions.containsKey(c.board().id())
                &&FirmwareVersion.newer(versions.get(c.board().id()),c.firmware())).count());
        model.addAttribute("announced",addresses.announced(request));
        model.addAttribute("internalLoopback",addresses.internalIsLoopback(request));
        // Web displays: the server address of their links per display, and the choices for a new one.
        var bases=new java.util.HashMap<String,String>();
        for(var c:clients.views())if(c.web()!=null)bases.put(c.identifier(),WebDisplayController.base(c,addresses,request));
        model.addAttribute("webBases",bases);model.addAttribute("externalSet",!addresses.external().isBlank());
        model.addAttribute("displayTypes",DisplayType.INVENTORY);
        return "devices";
    }
    /** A data source as the page shows it; the token itself never leaves the server. */
    record SourceView(String id,String type,String typeLabel,String label,boolean configured,String accountId,boolean suspended) {}
    @GetMapping("/admin/sources") String sources(Model model,jakarta.servlet.http.HttpSession session) {
        model.addAttribute("importEntries",SourceTransferController.pendingEntries(session,store));
        var effective=store.effective();
        model.addAttribute("sources",store.get().accounts().stream().map(a->new SourceView(a.id(),a.type(),SettingsStore.Account.typeLabel(a.type()),
                a.label(),effective.account(a.id()).map(e->!e.manual().token().isBlank()).orElse(false),a.manual().accountId(),a.suspended())).toList());
        model.addAttribute("types",SettingsStore.Account.TYPES);
        model.addAttribute("sourceStates",usage.snapshot().providers());
        return "sources";
    }
    @PostMapping("/admin/sources/add") String addSource(@RequestParam String type,@RequestParam String label,RedirectAttributes flash) throws Exception {
        try{
            var old=store.get();
            var accounts=new ArrayList<>(old.accounts());accounts.add(SettingsStore.Account.create(type,label));
            store.save(old.withAccounts(accounts));usage.configurationChanged();
            return "redirect:/admin/sources?saved";
        }catch(IllegalArgumentException e){flash.addFlashAttribute("sourceCreateError",e.getMessage());return "redirect:/admin/sources";}
    }
    /** Renames a source (pen next to its name); answers the stored name. Its values stay. */
    record Order(List<String> ids) {}
    /** Order of the data sources after dragging one by its grip; the overview and the editor follow it. */
    @PostMapping(value="/admin/sources/order",produces="application/json") @ResponseBody Map<String,Object> orderSources(@RequestBody Order order) throws Exception {
        var old=store.get();
        store.save(old.withAccounts(ClientRegistry.ordered(old.accounts(),order.ids(),SettingsStore.Account::id)));
        usage.suspensionChanged();  // keeps the readings and their time; only the order changed
        return Map.of("ids",store.get().accounts().stream().map(SettingsStore.Account::id).toList());
    }
    @PostMapping(value="/admin/sources/{id}/rename",produces="application/json") @ResponseBody ResponseEntity<Map<String,String>> renameSource(
            @PathVariable String id,@RequestParam String name) throws Exception {
        var old=store.get();
        if(old.account(id).isEmpty())return ResponseEntity.notFound().build();
        try{
            store.save(old.withAccounts(old.accounts().stream().map(a->a.id().equals(id)?new SettingsStore.Account(a.id(),a.type(),name,a.manual(),a.folder(),a.suspended()):a).toList()));
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error","A name of 1 to 24 characters"));}
        usage.suspensionChanged();  // keeps the readings; the display picks up the new name
        return ResponseEntity.ok(Map.of("name",store.get().account(id).orElseThrow().label()));
    }
    /** Pauses or resumes a source: a paused one keeps its sign-in but is not polled; the display shows it as paused. */
    @PostMapping("/admin/sources/{id}/suspend") String suspendSource(@PathVariable String id,@RequestParam boolean suspend,RedirectAttributes flash) throws Exception {
        var old=store.get();
        if(old.account(id).isEmpty()){flash.addFlashAttribute("error","The data source was already removed.");return "redirect:/admin/sources";}
        store.save(old.withAccounts(old.accounts().stream().map(a->a.id().equals(id)?a.withSuspended(suspend):a).toList()));
        usage.suspensionChanged();
        return "redirect:/admin/sources#source-"+id;
    }
    /** Removes the source and its stored sign-in; designs that show it display it as not configured. */
    @PostMapping("/admin/sources/{id}/remove") String removeSource(@PathVariable String id,RedirectAttributes flash) throws Exception {
        var old=store.get();var account=old.account(id);
        if(account.isEmpty()){flash.addFlashAttribute("error","The data source was already removed.");return "redirect:/admin/sources";}
        store.save(old.withAccounts(old.accounts().stream().filter(a->!a.id().equals(id)).toList()));
        store.forgetSignIn(account.get());usage.configurationChanged();
        return "redirect:/admin/sources?saved";
    }
    @GetMapping("/admin/server") String server(Model model,HttpServletRequest request) {
        var settings=store.get();
        model.addAttribute("internalUrl",clients.internalUrl());
        model.addAttribute("externalUrl",clients.externalUrl());
        model.addAttribute("pollSeconds",clients.pollSeconds());
        model.addAttribute("derivedInternal",addresses.derivedInternal(request));
        model.addAttribute("allowedIps",String.join("\n",settings.allowedIps()));
        model.addAttribute("peerIp",request.getRemoteAddr());
        model.addAttribute("devices",settings.devices());
        model.addAttribute("dataDir",dataDir);model.addAttribute("logDir",logDir);
        model.addAttribute("adminUser",admin.name());model.addAttribute("ownPassword",admin.overridden());
        return "server";
    }
    /**
     * Saves the provider credentials (Data sources) or the API access and legacy devices (Server). Fields that the
     * submitting page does not contain keep their stored values.
     */
    @PostMapping("/admin/settings") String save(@RequestParam Map<String,String> form,RedirectAttributes flash) throws Exception {
        String page="server".equals(form.get("page"))?"server":"sources";
        try {
            var old=store.get();
            List<String> ips=!form.containsKey("allowedIps")?old.allowedIps():form.get("allowedIps").lines().map(String::trim)
                    .filter(s -> !s.isBlank()).distinct().toList();
            var accounts=old.accounts().stream().map(a->new SettingsStore.Account(a.id(),a.type(),form.getOrDefault("label_"+a.id(),a.label()),
                    new SettingsStore.Credential(secret(form,"token_"+a.id(),a.manual().token()),form.getOrDefault("accountId_"+a.id(),a.manual().accountId())),a.folder(),a.suspended())).toList();
            List<SettingsStore.Device> targets=form.containsKey("device0Url")?legacyDevices(form,old):old.devices();
            store.save(new SettingsStore.Settings(ips,accounts,targets));
            usage.configurationChanged();
            return "redirect:/admin/"+page+"?saved";
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error",e.getMessage());return "redirect:/admin/"+page;
        }
    }
    /** Sets the administration password (replaces ADMIN_PASSWORD) or, with reset, returns to ADMIN_PASSWORD. */
    @PostMapping("/admin/password") String password(@RequestParam(defaultValue="") String current,@RequestParam(defaultValue="") String password,
            @RequestParam(defaultValue="") String repeat,@RequestParam(required=false) String reset,RedirectAttributes flash) throws Exception {
        try{
            if(reset!=null)admin.reset(current);else admin.change(current,password,repeat);
            flash.addFlashAttribute("passwordMessage",reset!=null?"The password from ADMIN_PASSWORD applies again. Sign in with it.":"Password changed. Sign in with the new password.");
            return "redirect:/login?expired";
        }catch(IllegalArgumentException e){flash.addFlashAttribute("passwordError",e.getMessage());}
        return "redirect:/admin/server#sign-in";
    }
    private static List<SettingsStore.Device> legacyDevices(Map<String,String> form,SettingsStore.Settings old){
        List<SettingsStore.Device> targets=new ArrayList<>();
        for (int i=0;i<3;i++) {
            String prefix="device"+i;
            String url=form.getOrDefault(prefix+"Url","").trim();
            if (url.isBlank()) continue;
            String previous=i<old.devices().size() && old.devices().get(i).url().equals(url)
                    ? old.devices().get(i).token():"";
            targets.add(new SettingsStore.Device(form.getOrDefault(prefix+"Name","VibeDock "+(i+1)).trim(),url,
                    secret(form,prefix+"Token",previous),form.containsKey(prefix+"Enabled")));
        }
        return targets;
    }
    private static String secret(Map<String,String> form,String key,String previous) {
        if (form.containsKey(key+"Clear")) return "";
        String submitted=form.getOrDefault(key,"").trim(); return submitted.isEmpty()?previous:submitted;
    }
    @PostMapping("/admin/refresh") String refresh() { usage.refresh();return "redirect:/admin"; }
}
