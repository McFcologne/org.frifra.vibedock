package org.frifra.vibedock.server;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import static org.junit.jupiter.api.Assertions.*;

class CsrfRejectionLogTest {
    @Test void namesTheFailureAndOnlyFactsNeverValues(){
        var request=new MockHttpServletRequest("POST","/login");
        request.setCookies(new Cookie("JSESSIONID","secret-session-id"),new Cookie("vibedock-login","secret-token"));
        request.addHeader("Sec-Fetch-Site","same-origin");request.addHeader("Origin","http://192.168.123.100:10001");
        request.setParameter("_csrf","secret-csrf");request.setQueryString("next=secret");
        var line=WebSecurity.csrfRejection(request,new InvalidCsrfTokenException(new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN","_csrf","expected"),"actual"));
        assertTrue(line.contains("InvalidCsrfTokenException")&&line.contains("POST /login")&&line.contains("[JSESSIONID, vibedock-login]"));
        assertTrue(line.contains("token sent true")&&line.contains("Sec-Fetch-Site same-origin")&&line.contains("Origin set")&&line.contains("Referer none"));
        assertFalse(line.contains("secret")||line.contains("192.168"));
    }
    @Test void withoutCookiesAndToken(){
        var line=WebSecurity.csrfRejection(new MockHttpServletRequest("POST","/admin/x"),new MissingCsrfTokenException("none"));
        assertTrue(line.contains("MissingCsrfTokenException")&&line.contains("cookies []")&&line.contains("session known false")&&line.contains("token sent false")&&line.contains("Sec-Fetch-Site null"));
    }
}
