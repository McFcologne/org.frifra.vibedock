package org.frifra.vibedock.server;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes changes to open administration pages (GET /admin/events, server-sent events): "devices" when a display's
 * design changed, e.g. by a swipe on its touch panel (device-state.js refreshes the cards at once instead of with its
 * next timer); "logs" with the display's identifier when a log of it was saved (log pages and Troubleshooting).
 */
@Controller
class AdminEvents {
    private final List<SseEmitter> pages=new CopyOnWriteArrayList<>();
    @GetMapping(value="/admin/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) @ResponseBody SseEmitter subscribe(){
        var page=new SseEmitter(0L);pages.add(page);
        page.onCompletion(()->pages.remove(page));page.onTimeout(()->pages.remove(page));page.onError(e->pages.remove(page));
        return page;
    }
    void devicesChanged(){send("devices","changed");}
    void logSaved(String identifier){send("logs",identifier);}
    private void send(String name,String data){
        for(var page:pages){
            try{page.send(SseEmitter.event().name(name).data(data));}
            catch(Exception e){pages.remove(page);}
        }
    }
}
