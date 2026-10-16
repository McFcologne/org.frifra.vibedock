package org.frifra.vibedock.server;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.support.RequestContextUtils;
import java.io.IOException;
import java.nio.file.*;

/**
 * Saving to the data directory can fail, typically when the container user (UID 10001) may not write to the
 * mounted folder. Instead of an error page the administration shows what went wrong.
 */
@ControllerAdvice
class StorageErrors {
    private static final Logger log=LoggerFactory.getLogger(StorageErrors.class);
    private final Path directory;
    StorageErrors(@Value("${vibedock.data-dir}") String directory){this.directory=Path.of(directory);}

    @ExceptionHandler(IOException.class)
    String saveFailed(IOException e,HttpServletRequest request){
        log.warn("Saving to {} failed: {}",directory,e.toString());
        RequestContextUtils.getOutputFlashMap(request).put("error","Saving failed: "+problem());
        return "redirect:/admin";
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
