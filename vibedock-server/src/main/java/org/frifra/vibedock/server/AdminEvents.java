package org.frifra.vibedock.server;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
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
 * A page that was closed or reloaded is removed on its next event, at the latest with the keep-alive every 30 s;
 * its broken connection is expected and not an error.
 */
@Controller
class AdminEvents {
    private static final Logger log=LoggerFactory.getLogger(AdminEvents.class);
    static final String ACCEPTED=AdminEvents.class.getName()+".accepted";
    /** Server-side marker: only this already-authorized GET's async completion can use the completion chain. */
    static boolean acceptedCompletion(HttpServletRequest request){
        return request.getDispatcherType()==DispatcherType.ASYNC&&"GET".equals(request.getMethod())
                &&"/admin/events".equals(request.getRequestURI())&&Boolean.TRUE.equals(request.getAttribute(ACCEPTED));
    }
    private final List<SseEmitter> pages=new CopyOnWriteArrayList<>();
    @GetMapping(value="/admin/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) @ResponseBody SseEmitter subscribe(HttpServletRequest request){
        request.setAttribute(ACCEPTED,true);return subscribe();
    }
    SseEmitter subscribe(){
        var page=new SseEmitter(0L);pages.add(page);
        page.onCompletion(()->pages.remove(page));page.onTimeout(()->{pages.remove(page);page.complete();});page.onError(e->pages.remove(page));
        return page;
    }
    void devicesChanged(){send("devices","changed");}
    void logSaved(String identifier){send("logs",identifier);}
    /** A comment line every 30 s: keeps proxies from closing the stream and finds pages that are gone. */
    @Scheduled(fixedDelay=30_000,initialDelay=30_000) void keepAlive(){
        for(var page:pages){
            try{page.send(SseEmitter.event().comment("keep-alive"));}
            catch(Exception e){drop(page,e);}
        }
    }
    int open(){return pages.size();}
    private void send(String name,String data){
        for(var page:pages){
            try{page.send(SseEmitter.event().name(name).data(data));}
            catch(Exception e){drop(page,e);}
        }
    }
    private void drop(SseEmitter page,Exception e){
        pages.remove(page);
        if(StorageErrors.disconnected(e))log.debug("Administration page closed its event stream: {}",e.toString());
        else log.warn("Event stream of an administration page failed: {}",e.toString());
        // On an IOException the container dispatches the send failure back to MVC and owns completion.
        // A second complete() races that dispatch, which may happen after the user's session has expired.
        if(!(e instanceof IOException))try{page.complete();}catch(Exception ignored){}
    }
}
