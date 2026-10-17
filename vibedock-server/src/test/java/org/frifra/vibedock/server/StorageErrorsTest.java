package org.frifra.vibedock.server;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.nio.file.Path;

/** A browser closing a page is not a storage error; a failed write still is, with its cause. */
class StorageErrorsTest {
    private static MockHttpServletRequest request(){
        var r=new MockHttpServletRequest("POST","/admin/designs/default");r.setAttribute(DispatcherServlet.OUTPUT_FLASH_MAP_ATTRIBUTE,new FlashMap());return r;
    }
    private static FlashMap flash(MockHttpServletRequest r){return (FlashMap)r.getAttribute(DispatcherServlet.OUTPUT_FLASH_MAP_ATTRIBUTE);}

    @Test void closedPagesGetNoRedirectAndNoMessage(@TempDir Path dir){
        var errors=new StorageErrors(dir.toString());
        // Like Tomcat's ClientAbortException and Spring's AsyncRequestNotUsableException: the browser is gone.
        for(var e:new IOException[]{new IOException("Broken pipe"),new IOException("write failed",new IOException("Connection reset by peer")),
                new org.springframework.web.context.request.async.AsyncRequestNotUsableException("ServletOutputStream failed to write")}){
            var r=request();var view=errors.saveFailed(e,r,new MockHttpServletResponse());
            Assertions.assertNull(view.getViewName(),e.toString());Assertions.assertTrue(flash(r).isEmpty(),e.toString());
        }
        // After the response has begun: no redirect either.
        var committed=new MockHttpServletResponse();committed.setCommitted(true);var r=request();
        Assertions.assertNull(errors.saveFailed(new IOException("disk full"),r,committed).getViewName());Assertions.assertTrue(flash(r).isEmpty());
    }
    @Test void failedWritesAreStillShownWithTheirCause(@TempDir Path dir){
        var r=request();var view=new StorageErrors(dir.toString()).saveFailed(new java.nio.file.AccessDeniedException("/data/clients.json"),r,new MockHttpServletResponse());
        Assertions.assertEquals("redirect:/admin",view.getViewName());
        Assertions.assertEquals("Saving failed: /data/clients.json",flash(r).get("error"));
        // A data directory that is not writable names the fix.
        var missing=request();new StorageErrors(dir.resolve("missing").toString()).saveFailed(new IOException("x"),missing,new MockHttpServletResponse());
        Assertions.assertTrue(((String)flash(missing).get("error")).contains("not writable for the container user"));
    }
    @Test void closedEventStreamsAreRemoved() throws Exception {
        var events=new AdminEvents();
        var page=events.subscribe();Assertions.assertEquals(1,events.open());
        // A completed emitter fails on its next send, like one whose page was closed.
        page.complete();events.keepAlive();
        Assertions.assertEquals(0,events.open());
        events.subscribe();events.devicesChanged();Assertions.assertEquals(1,events.open());
    }
}
