package org.frifra.vibedock.server;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Persistent browser sign-in; session expiry remains separate from the 30-day browser token. */
@Component
final class AdminRememberMe extends AbstractRememberMeServices {
    static final String COOKIE="vibedock-login",KEY="vibedock-browser-login";
    private final AdminLoginTokens tokens;private final AdminAccount account;
    AdminRememberMe(AdminAccount account,AdminLoginTokens tokens){
        super(KEY,account);this.account=account;this.tokens=tokens;
        setCookieName(COOKIE);setTokenValiditySeconds(AdminLoginTokens.MAX_AGE);
    }
    @Override protected void setCookie(String[] values,int maxAge,HttpServletRequest request,HttpServletResponse response){
        var cookie=new Cookie(COOKIE,encodeCookie(values));
        cookie.setPath(request.getContextPath().isEmpty()?"/":request.getContextPath());
        cookie.setMaxAge(maxAge);cookie.setHttpOnly(true);cookie.setAttribute("SameSite","Strict");
        // A TLS proxy can only strengthen this flag. Forwarded headers still never grant access or select device IPs.
        cookie.setSecure(request.isSecure()||"https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")));
        response.addCookie(cookie);
    }
    @Override protected UserDetails processAutoLoginCookie(String[] values,HttpServletRequest request,HttpServletResponse response){
        try{
            synchronized(account){
                if(values.length!=1||!tokens.valid(values[0],account.loginIdentity()))throw new InvalidCookieException("Browser sign-in expired or revoked");
                return account.loadUserByUsername(account.name());
            }
        }catch(IOException e){throw new InvalidCookieException("Browser sign-in unavailable");}
    }
    @Override public void loginSuccess(HttpServletRequest request,HttpServletResponse response,Authentication authentication){
        if(!rememberMeRequested(request,getParameter())){logout(request,response,authentication);return;}
        super.loginSuccess(request,response,authentication);
    }
    @Override protected void onLoginSuccess(HttpServletRequest request,HttpServletResponse response,Authentication authentication){
        try{
            // Do not turn an authentication racing a password change into a new persistent login.
            synchronized(account){
                if(!(authentication.getPrincipal() instanceof AdminAccount.Details signedIn)||!account.loginIdentity().equals(signedIn.identity))return;
                tokens.revoke(raw(request));
                setCookie(new String[]{tokens.issue(account.loginIdentity())},AdminLoginTokens.MAX_AGE,request,response);
            }
        }catch(IOException e){cancelCookie(request,response);logger.warn("Could not persist browser sign-in");}
    }
    @Override public void logout(HttpServletRequest request,HttpServletResponse response,Authentication authentication){
        try{tokens.revoke(raw(request));}
        catch(IOException e){throw new IllegalStateException("Could not revoke browser sign-in; check server storage",e);}
        finally{super.logout(request,response,authentication);}
    }
    /** LogoutFilter runs before auto-login, so revocation must also work without an authenticated session. */
    private String raw(HttpServletRequest request){
        String value=extractRememberMeCookie(request);if(value==null)return null;
        try{String[] parts=decodeCookie(value);return parts.length==1?parts[0]:null;}
        catch(InvalidCookieException e){return null;}
    }
    OncePerRequestFilter renewalFilter(){
        return new OncePerRequestFilter(){
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
                Authentication auth=SecurityContextHolder.getContext().getAuthentication();
                if(auth!=null&&auth.isAuthenticated()&&account.name().equals(auth.getName())){
                    // Also cover a password change racing session registration after automatic authentication.
                    if(auth.getPrincipal() instanceof AdminAccount.Details details&&!details.identity.equals(account.loginIdentity())){
                        var session=request.getSession(false);if(session!=null)session.invalidate();
                        SecurityContextHolder.clearContext();cancelCookie(request,response);
                    }else{
                        String token=raw(request);
                        try{if(tokens.renew(token,account.loginIdentity()))setCookie(new String[]{token},AdminLoginTokens.MAX_AGE,request,response);}
                        catch(IOException e){logger.warn("Could not renew browser sign-in");}
                    }
                }
                chain.doFilter(request,response);
            }
        };
    }
}
