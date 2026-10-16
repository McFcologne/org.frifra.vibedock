package org.frifra.vibedock.server;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/admin/login")
class LoginController {
    private final BrowserLogin login;
    LoginController(BrowserLogin login) { this.login=login; }
    @GetMapping("/{provider}") BrowserLogin.View status(@PathVariable String provider) { return login.status(provider); }
    @PostMapping("/{provider}/start") BrowserLogin.View start(@PathVariable String provider) { return login.start(provider); }
    @PostMapping("/{provider}/cancel") BrowserLogin.View cancel(@PathVariable String provider,@RequestBody Map<String,String> body) {
        return login.cancel(provider,body.get("id"));
    }
    @PostMapping("/{provider}/code") BrowserLogin.View code(@PathVariable String provider,@RequestBody Map<String,String> body) {
        return login.submitCode(provider,body.get("id"),body.get("code"));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,String> invalid(IllegalArgumentException e) { return Map.of("message",e.getMessage()); }
}
