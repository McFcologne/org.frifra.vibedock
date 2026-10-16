package org.frifra.vibedock.server;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.RequestContextUtils;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/**
 * Encrypted export and import of the data sources. The import has two steps: the uploaded file is decrypted and kept
 * in the admin's session for ten minutes, the page lists its sources, and only the confirmation changes anything.
 */
@Controller
class SourceTransferController {
    private static final Logger log=LoggerFactory.getLogger(SourceTransferController.class);
    private static final String PENDING="vibedock.sourceImport";
    private static final long PENDING_MILLIS=10*60*1000;
    record Pending(SourceTransfer.Bundle bundle,long created) {}
    /** A source of the uploaded export as the review lists it; no credential leaves the server. */
    record Entry(String id,String label,String typeLabel,boolean signedIn,boolean exists) {}
    private final SourceTransfer transfer;private final SettingsStore store;private final UsageService usage;
    SourceTransferController(SourceTransfer transfer,SettingsStore store,UsageService usage){this.transfer=transfer;this.store=store;this.usage=usage;}

    /** The sources of the pending import for the page, or null. */
    static List<Entry> pendingEntries(HttpSession session,SettingsStore store){
        var pending=pending(session);if(pending==null)return null;
        return pending.bundle().accounts().stream().map(a->new Entry(a.id(),a.label(),SettingsStore.Account.typeLabel(a.type()),
                pending.bundle().signedIn(a),store.get().account(a.id()).isPresent())).toList();
    }
    private static Pending pending(HttpSession session){
        if(session==null||!(session.getAttribute(PENDING) instanceof Pending p))return null;
        if(System.currentTimeMillis()-p.created()>PENDING_MILLIS){session.removeAttribute(PENDING);return null;}
        return p;
    }

    @PostMapping("/admin/sources/export")
    ResponseEntity<byte[]> export(@RequestParam(defaultValue="") String password,@RequestParam(defaultValue="") String confirm,
                                  HttpServletRequest request,HttpServletResponse response) throws IOException {
        try{
            if(!password.equals(confirm))throw new IllegalArgumentException("The passwords do not match");
            byte[] file=transfer.export(password);
            log.info("Exported {} data source(s)",store.get().accounts().size());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL,"no-store")
                    .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("vibedock-sources-"+LocalDate.now()+".json").build().toString())
                    .body(file);
        }catch(IllegalArgumentException e){
            // Back to the page with the message; flash attributes need saving by hand when no view name is returned.
            RequestContextUtils.getOutputFlashMap(request).put("exportError",e.getMessage());
            RequestContextUtils.saveOutputFlashMap("/admin/sources",request,response);
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(java.net.URI.create(request.getContextPath()+"/admin/sources")).build();
        }
    }
    @PostMapping("/admin/sources/import") String upload(@RequestParam(defaultValue="") String content,@RequestParam(defaultValue="") String password,
                                                       HttpSession session,RedirectAttributes flash) {
        try{session.setAttribute(PENDING,new Pending(transfer.read(content,password),System.currentTimeMillis()));return "redirect:/admin/sources#import-review";}
        catch(IllegalArgumentException e){flash.addFlashAttribute("importError",e.getMessage());return "redirect:/admin/sources";}
    }
    @PostMapping("/admin/sources/import/apply") String apply(@RequestParam(defaultValue="add") String mode,@RequestParam(name="source",required=false) List<String> ids,
                                                            HttpSession session,RedirectAttributes flash) throws IOException {
        var pending=pending(session);
        if(pending==null){flash.addFlashAttribute("importError","The import expired. Upload the file again.");return "redirect:/admin/sources";}
        boolean replace=mode.equals("replace");
        try{
            var chosen=new HashSet<>(ids==null?List.<String>of():ids);chosen.retainAll(pending.bundle().accounts().stream().map(SettingsStore.Account::id).toList());
            transfer.apply(pending.bundle(),chosen,replace);
            session.removeAttribute(PENDING);usage.configurationChanged();
            log.info("Imported {} data source(s), {}",chosen.size(),replace?"replacing the existing ones":"added to the existing ones");
            flash.addFlashAttribute("importMessage","Imported "+chosen.size()+" data source"+(chosen.size()==1?"":"s")+(replace?"; the previous sources were replaced.":"."));
        }catch(IllegalArgumentException e){flash.addFlashAttribute("importReviewError",e.getMessage());return "redirect:/admin/sources#import-review";}
        return "redirect:/admin/sources";
    }
    @PostMapping("/admin/sources/import/cancel") String cancel(HttpSession session){session.removeAttribute(PENDING);return "redirect:/admin/sources";}
}
