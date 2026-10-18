package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminLoginTokensTest {
    final ObjectMapper json=new ObjectMapper();
    static class Time extends Clock {
        Instant instant=Instant.parse("2026-10-18T12:00:00Z");
        void advance(Duration duration){instant=instant.plus(duration);}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return instant;}
    }
    @Test void onlyHashesPersistAndRevocationSurvivesRestart(@TempDir Path dir) throws Exception {
        var clock=new Time();var store=new AdminLoginTokens(dir,json,clock);
        String first=store.issue("identity"),second=store.issue("identity");
        String contents=Files.readString(dir.resolve("admin-logins.json"));
        assertFalse(contents.contains(first));assertFalse(contents.contains(second));assertTrue(contents.contains(AdminLoginTokens.hash(first)));
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(dir.resolve("admin-logins.json")));
        var restarted=new AdminLoginTokens(dir,json,clock);assertTrue(restarted.valid(first,"identity"));
        restarted.revoke(first);assertFalse(new AdminLoginTokens(dir,json,clock).valid(first,"identity"));
        assertTrue(restarted.valid(second,"identity"));restarted.revokeAll();assertFalse(new AdminLoginTokens(dir,json,clock).valid(second,"identity"));
    }
    @Test void activityRenewsThirtyDaysWithBoundedWritesAndIdleTokensExpire(@TempDir Path dir) throws Exception {
        var clock=new Time();var store=new AdminLoginTokens(dir,json,clock);String token=store.issue("identity");
        byte[] original=Files.readAllBytes(dir.resolve("admin-logins.json"));
        assertFalse(store.renew(token,"identity"));assertArrayEquals(original,Files.readAllBytes(dir.resolve("admin-logins.json")));
        clock.advance(Duration.ofDays(29));assertTrue(store.renew(token,"identity"));
        clock.advance(Duration.ofDays(29));assertTrue(new AdminLoginTokens(dir,json,clock).valid(token,"identity"));
        clock.advance(Duration.ofDays(1));assertFalse(store.valid(token,"identity"));
    }
    @Test void passwordIdentityIsStableAcrossRestartsButChangesAndResetsRevokeTokens(@TempDir Path dir) throws Exception {
        var settings=new SettingsStore(dir.toString(),json);var tokens=new AdminLoginTokens(dir,json,Clock.systemUTC());
        var account=new AdminAccount("admin","original-password","password",settings,tokens);
        String identity=account.loginIdentity(),token=tokens.issue(identity);
        var reopened=new AdminLoginTokens(dir,json,Clock.systemUTC());
        var restarted=new AdminAccount("admin","original-password","password",settings,reopened);
        assertEquals(identity,restarted.loginIdentity());assertTrue(reopened.valid(token,restarted.loginIdentity()));
        restarted.change("original-password","new-password","new-password");assertFalse(reopened.valid(token,identity));
        String ownToken=reopened.issue(restarted.loginIdentity());restarted.reset("new-password");assertFalse(reopened.valid(ownToken,restarted.loginIdentity()));
        String envToken=reopened.issue(restarted.loginIdentity());
        var changed=new AdminAccount("admin","changed-environment-password","password",settings,reopened);
        assertFalse(reopened.valid(envToken,changed.loginIdentity()));
    }
    @Test void cookieSurvivesServerRestartAndActiveSessionsRenewIt(@TempDir Path dir) throws Exception {
        var clock=new Time();var settings=new SettingsStore(dir.toString(),json);var tokens=new AdminLoginTokens(dir,json,clock);
        var account=new AdminAccount("admin","original-password","password",settings,tokens);
        var remember=new AdminRememberMe(account,tokens);
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.setParameter("remember-me","on");
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        var principal=account.loadUserByUsername("admin");
        var authentication=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities());
        remember.loginSuccess(request,response,authentication);var cookie=response.getCookie(AdminRememberMe.COOKIE);assertNotNull(cookie);
        var reopened=new AdminLoginTokens(dir,json,clock);var restartedAccount=new AdminAccount("admin","original-password","password",settings,reopened);
        var restarted=new AdminRememberMe(restartedAccount,reopened);
        var subsequent=new org.springframework.mock.web.MockHttpServletRequest();subsequent.setCookies(cookie);
        assertNotNull(restarted.autoLogin(subsequent,new org.springframework.mock.web.MockHttpServletResponse()));
        clock.advance(Duration.ofDays(29));var refreshed=new org.springframework.mock.web.MockHttpServletResponse();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try{restarted.renewalFilter().doFilter(subsequent,refreshed,(req,res)->{});}
        finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
        assertEquals(AdminLoginTokens.MAX_AGE,refreshed.getCookie(AdminRememberMe.COOKIE).getMaxAge());
        clock.advance(Duration.ofDays(29));assertNotNull(restarted.autoLogin(subsequent,new org.springframework.mock.web.MockHttpServletResponse()));
        clock.advance(Duration.ofDays(1));assertNull(restarted.autoLogin(subsequent,new org.springframework.mock.web.MockHttpServletResponse()));
    }

    @Test void passwordChangesAlsoRejectAnAuthenticationRacingSessionRegistration(@TempDir Path dir) throws Exception {
        var settings=new SettingsStore(dir.toString(),json);var tokens=new AdminLoginTokens(dir,json,Clock.systemUTC());
        var account=new AdminAccount("admin","original-password","password",settings,tokens);var remember=new AdminRememberMe(account,tokens);
        var principal=account.loadUserByUsername("admin");
        var authentication=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities());
        account.change("original-password","changed-password","changed-password");
        var request=new org.springframework.mock.web.MockHttpServletRequest();var session=request.getSession();
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try{
            remember.renewalFilter().doFilter(request,response,(req,res)->{});
            assertNull(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
            assertTrue(((org.springframework.mock.web.MockHttpSession)session).isInvalid());
        }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }

}
