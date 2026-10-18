package org.frifra.vibedock.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The administration user. Its password is ADMIN_PASSWORD until one is set under Settings; that one is kept as a BCrypt
 * hash in the data directory and replaces ADMIN_PASSWORD until it is reset.
 */
@Component
class AdminAccount implements UserDetailsService {
    private static final BCryptPasswordEncoder BCRYPT=new BCryptPasswordEncoder();
    private final String name,configured,configuredIdentity;
    private final AdminLoginTokens loginTokens;
    String loginIdentity(){String h=own;return h==null?configuredIdentity:AdminLoginTokens.hash(name+"\0own\0"+h);}
    private final SettingsStore store;
    private volatile String own;
    private org.springframework.security.core.session.SessionRegistry sessions;
    @org.springframework.beans.factory.annotation.Autowired
    void sessionRegistry(org.springframework.security.core.session.SessionRegistry sessions){this.sessions=sessions;}
    private void revokeSessions(){
        if(sessions==null)return; // Standalone account tests have no servlet sessions.
        for(var principal:sessions.getAllPrincipals())
            if(principal instanceof UserDetails user&&name.equals(user.getUsername()))
                sessions.getAllSessions(principal,false).forEach(org.springframework.security.core.session.SessionInformation::expireNow);
    }

    AdminAccount(String name,String password,String auth,SettingsStore store) throws IOException {this(name,password,auth,store,null);}
    @org.springframework.beans.factory.annotation.Autowired
    AdminAccount(@Value("${vibedock.admin-user}") String name,@Value("${vibedock.admin-password}") String password,
                 @Value("${vibedock.admin-auth:password}") String auth,SettingsStore store,AdminLoginTokens tokens) throws IOException {
        this.name=name;this.store=store;this.loginTokens=tokens;
        own=store.adminPasswordHash().orElse(null);
        if(password.isBlank()&&own==null&&!"none".equals(auth))throw new IllegalArgumentException("ADMIN_PASSWORD must be set");
        configured=password.isBlank()?null:tokens==null?BCRYPT.encode(password):tokens.environmentHash(password);
        configuredIdentity=AdminLoginTokens.hash(name+"\0environment\0"+configured);
    }
    String name(){return name;}
    /** A password set under Settings replaces ADMIN_PASSWORD. */
    boolean overridden(){return own!=null;}
    private String hash(){String h=own;return h!=null?h:configured;}
    @Override public synchronized UserDetails loadUserByUsername(String username){
        if(!name.equals(username))throw new UsernameNotFoundException("Unknown user");
        return new Details(name,"{bcrypt}"+hash(),loginIdentity());
    }
    /** The credential version survives Spring erasing the password after authentication. */
    static final class Details extends User {
        final String identity;
        Details(String name,String password,String identity){super(name,password,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));this.identity=identity;}
    }
    synchronized void change(String current,String next,String repeat) throws IOException {
        check(current);
        if(next==null||!next.equals(repeat))throw new IllegalArgumentException("The new passwords do not match.");
        int bytes=next.getBytes(StandardCharsets.UTF_8).length;
        if(next.length()<8||bytes>72)throw new IllegalArgumentException("The new password needs at least 8 characters (at most 72 bytes).");
        String hash=BCRYPT.encode(next);if(loginTokens!=null)loginTokens.revokeAll();store.saveAdminPasswordHash(hash);own=hash;revokeSessions();
    }
    /** Back to ADMIN_PASSWORD; only possible while it is set. */
    synchronized void reset(String current) throws IOException {
        check(current);
        if(configured==null)throw new IllegalArgumentException("ADMIN_PASSWORD is not set, so the password cannot be reset to it.");
        if(loginTokens!=null)loginTokens.revokeAll();store.saveAdminPasswordHash(null);own=null;revokeSessions();
    }
    private void check(String current){
        if(current==null||!BCRYPT.matches(current,hash()))throw new IllegalArgumentException("The current password is wrong.");
    }
}
