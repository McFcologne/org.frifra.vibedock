package org.frifra.vibedock.server;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Invalid input to the JSON and image endpoints: 400 with the reason, never a stack trace. */
@RestControllerAdvice(assignableTypes={DesignController.class,DeviceAdminController.class,DeviceApiController.class,EmojiController.class})
class RequestErrors {
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    String invalid(IllegalArgumentException error){return error.getMessage()==null?"Invalid request":error.getMessage();}
}
