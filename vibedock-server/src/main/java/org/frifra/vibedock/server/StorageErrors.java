package org.frifra.vibedock.server;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;
import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

/**
 * Saving to the data directory can fail, typically when the container user (UID 10001) may not write to the
 * mounted folder. Instead of an error page the administration shows what went wrong.
 * <p>
 * Not every IOException is a storage error: a browser that closes or reloads a page (also the event stream of
 * AdminEvents) breaks the connection while the server still writes to it. Such disconnects are logged in one line at
 * debug level and get no answer; once a response has begun, neither a redirect nor a flash message is possible.
 */
@ControllerAdvice
class StorageErrors {
    private static final Logger log=LoggerFactory.getLogger(StorageErrors.class);
    private final Path directory;
    StorageErrors(@Value("${vibedock.data-dir}") String directory){this.directory=Path.of(directory);}

    @ExceptionHandler(IOException.class)
    ModelAndView saveFailed(IOException e,HttpServletRequest request,HttpServletResponse response){
        String operation=request.getMethod()+" "+request.getRequestURI();
        if(disconnected(e)){
            log.debug("Connection closed by the browser during {}: {}",operation,e.toString());
            return new ModelAndView();
        }
        if(response.isCommitted()){
            // The answer has begun: report the cause, but no redirect (it would fail with "Cannot call sendRedirect()").
            log.warn("{} failed after its response had begun: {}",operation,e.toString());
            return new ModelAndView();
        }
        log.warn("Saving to {} failed during {}: {}",directory,operation,e.toString());
        RequestContextUtils.getOutputFlashMap(request).put("error","Saving failed: "+problem(e));
        return new ModelAndView("redirect:/admin");
    }
    /** Whether the exception (or one of its causes) is a connection the browser closed, not a failed write to disk. */
    static boolean disconnected(Throwable e){
        for(Throwable t=e;t!=null;t=t.getCause()==t?null:t.getCause()){
            String type=t.getClass().getName();
            if(type.endsWith("ClientAbortException")||type.endsWith("AsyncRequestNotUsableException")||type.endsWith("EofException"))return true;
            String message=t.getMessage()==null?"":t.getMessage().toLowerCase(Locale.ROOT);
            if(message.contains("broken pipe")||message.contains("connection reset by peer")||message.contains("connection was aborted")
                    ||message.contains("an established connection was aborted"))return true;
        }
        return false;
    }
    /** The cause for the administration: a data directory that is not writable, otherwise the error itself. */
    private String problem(IOException e){
        String problem=problem();
        return problem!=null?problem:e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
    }
    /** Null when the data directory is writable, otherwise a description with the fix. */
    String problem(){
        try{
            Path probe=Files.createTempFile(directory,".write-test-",".tmp");Files.delete(probe);return null;
        }catch(Exception e){
            return "the data directory "+directory+" is not writable for the container user (UID 10001). On the host: "
                    +"sudo chown -R 10001:10001 <data folder>";
        }
    }
    /** For the administration page: a write problem that would make saving fail. */
    String writeProblem(){
        String problem=problem();
        return problem==null?null:"Saving is not possible: "+problem;
    }
}
