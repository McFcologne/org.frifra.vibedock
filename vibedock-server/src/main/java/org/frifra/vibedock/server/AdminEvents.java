package org.frifra.vibedock.server;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes changes to open administration pages (GET /admin/events, server-sent events): a display's design changed,
 * e.g. by a swipe on its touch panel. The pages then refresh their device cards at once instead of with their next
 * timer (device-state.js).
 */
@Controller
class AdminEvents {
    private final List<SseEmitter> pages=new CopyOnWriteArrayList<>();
    @GetMapping(value="/admin/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) @ResponseBody SseEmitter subscribe(){
        var page=new SseEmitter(0L);pages.add(page);
        page.onCompletion(()->pages.remove(page));page.onTimeout(()->pages.remove(page));page.onError(e->pages.remove(page));
        return page;
    }
    void devicesChanged(){
        for(var page:pages){
            try{page.send(SseEmitter.event().name("devices").data("changed"));}
            catch(Exception e){pages.remove(page);}
        }
    }
}
