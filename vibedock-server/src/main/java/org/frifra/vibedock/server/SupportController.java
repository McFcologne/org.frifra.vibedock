package org.frifra.vibedock.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

/** Support files on the troubleshooting page: create one (the key is answered once) and open one for the analysis. */
@Controller
class SupportController {
    private static final Logger log=LoggerFactory.getLogger(SupportController.class);
    private final SupportFile support;
    SupportController(SupportFile support){this.support=support;}

    @PostMapping(value="/admin/support/file",produces="application/json") @ResponseBody
    ResponseEntity<Map<String,Object>> create(@RequestParam(defaultValue="7") int days,@RequestParam(defaultValue="false") boolean hideHosts,
            @RequestParam(required=false) String title,@RequestParam(required=false) String happened,@RequestParam(required=false) String expected,
            @RequestParam(required=false) String steps,@RequestParam(required=false) String since,@RequestParam(required=false) List<String> displays) throws IOException {
        try{
            var created=support.create(days,hideHosts,new SupportFile.Report(title,happened,expected,steps,since,displays));
            log.info("Support file {} created ({} days{}, {} bytes)",created.name(),days,hideHosts?", host names hidden":"",created.bytes().length);
            return ResponseEntity.ok(Map.of("name",created.name(),"key",created.key(),"data",Base64.getEncoder().encodeToString(created.bytes())));
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
    }
    record OpenRequest(String content,String key) {}
    @PostMapping(value="/admin/support/open",produces="application/json") @ResponseBody
    ResponseEntity<Object> open(@RequestBody OpenRequest request){
        try{return ResponseEntity.ok(support.open(request.content(),request.key()));}
        catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
    }
}
