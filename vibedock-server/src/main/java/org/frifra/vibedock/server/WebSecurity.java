package org.frifra.vibedock.server;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import java.util.Set;
import java.io.IOException;

@Configuration
class WebSecurity {
    @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> adminConnector(
            @Value("${vibedock.admin-port}") int port,@Value("${vibedock.admin-address}") String address) {
        return factory -> {
            Connector connector=new Connector("org.apache.coyote.http11.Http11NioProtocol");
            connector.setPort(port); connector.setProperty("address",address);
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
    @Bean org.springframework.security.core.session.SessionRegistry sessionRegistry(){
        return new org.springframework.security.core.session.SessionRegistryImpl();
    }
    @Bean org.springframework.security.web.session.HttpSessionEventPublisher sessionEvents(){
        return new org.springframework.security.web.session.HttpSessionEventPublisher();
    }
    /** Finish an existing event response without trying to log in or redirect on its async redispatch. */
    @Bean @org.springframework.core.annotation.Order(0)
    SecurityFilterChain eventCompletion(HttpSecurity http) throws Exception {
        return http.securityMatcher(AdminEvents::acceptedCompletion)
                .requestCache(cache->cache.disable())
                .authorizeHttpRequests(a->a.anyRequest().permitAll()).build();
    }
    @Bean SecurityFilterChain security(HttpSecurity http,org.springframework.security.core.session.SessionRegistry sessions,
            @Value("${vibedock.admin-auth:password}") String auth,@Value("${vibedock.admin-user}") String admin,AdminRememberMe remember) throws Exception {
        // ADMIN_AUTH=none (quick tests): every visitor of the administration port counts as the administrator. The port
        // guard still keeps the administration off the public port; publish the administration port on this host only.
        if("none".equals(auth)){
            org.slf4j.LoggerFactory.getLogger(WebSecurity.class).warn("ADMIN_AUTH=none: the administration needs no sign-in");
            http.anonymous(a -> a.principal(admin).authorities("ROLE_ADMIN"));
        }
        // Behind a TLS reverse proxy (e.g. Synology Web Station) absolute http:// redirects would break; browsers get
        // a relative redirect to the login page instead. Other clients keep HTTP Basic.
        var login=new LoginUrlAuthenticationEntryPoint("/login");login.setFavorRelativeUris(true);
        var browsers=new MediaTypeRequestMatcher(MediaType.TEXT_HTML,MediaType.APPLICATION_XHTML_XML);
        browsers.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        return http
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(login,browsers).accessDeniedHandler((request,response,denied)->{
                    // This marker permits one safe client retry: CSRF rejected the request before any controller ran.
                    if(denied instanceof org.springframework.security.web.csrf.CsrfException)response.setHeader("X-VibeDock-CSRF","invalid");
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                }))
                .authorizeHttpRequests(a -> a.requestMatchers("/api/v1/usage","/api/v1/clients/*/image","/api/v1/clients/*/firmware","/api/v1/clients/*/log","/web/*","/web/*/image.png","/web/*/icon.png","/web/*/state","/web/*/swipe").permitAll()
                // Login page assets; the port guard still limits them to the administration port.
                .requestMatchers("/login","/error","/app.css","/theme.js","/logo.svg","/favicon.ico","/apple-touch-icon.png").permitAll()
                .anyRequest().hasRole("ADMIN"))
                .sessionManagement(s -> s.maximumSessions(-1).sessionRegistry(sessions).expiredUrl("/login?expired"))
                .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/admin",true).permitAll())
                .rememberMe(r -> r.key(AdminRememberMe.KEY).rememberMeServices(remember))
                .addFilterAfter(remember.renewalFilter(),org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter.class)
                // Sign out by GET as well and without the CSRF token: opening /logout or a form from before a server
                // restart (expired token) used to end on a 403 page. A forged sign-out link can only sign you out.
                .logout(l -> l.logoutRequestMatcher(new OrRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET,"/logout"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST,"/logout"))).logoutSuccessUrl("/login?logout").permitAll())
                // The display's log upload is authenticated by its key, like its image pull, and has no browser session.
                .csrf(c -> c.ignoringRequestMatchers(PathPatternRequestMatcher.withDefaults().matcher("/logout"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST,"/api/v1/clients/*/log"),
                        // Web display pages: the secret in the path is the access, there is no session.
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST,"/web/*/swipe")))
                .httpBasic(Customizer.withDefaults()).build();
    }
    @Bean FilterRegistrationBean<Filter> portAndIpGuard(SettingsStore settings,
            @Value("${vibedock.admin-port}") int adminPort) {
        Filter guard=new Filter() {
            @Override public void doFilter(ServletRequest req,ServletResponse res,FilterChain chain)
                    throws IOException,ServletException {
                HttpServletRequest request=(HttpServletRequest)req;
                HttpServletResponse response=(HttpServletResponse)res;
                String path=request.getRequestURI();
                // Never log bearer URLs or query strings. Missing attributes log as "-", never as the raw request.
                String decoded;
                try{decoded=java.net.URLDecoder.decode(path,java.nio.charset.StandardCharsets.UTF_8);}
                catch(IllegalArgumentException e){decoded="/web/invalid";} // Malformed escapes are not safe to log.
                String loggedPath=decoded.startsWith("/web/")||decoded.startsWith("/web;")?"/web/[redacted]":path;
                request.setAttribute("vibedock.accessRequest",request.getMethod()+" "+loggedPath+" "+request.getProtocol());
                boolean api=path.equals("/api/v1/usage");
                // Device pull (protocol v2): authenticated by the device key in the controller.
                boolean upload=path.matches("/api/v1/clients/[A-Za-z0-9_.:-]{8,80}/log");
                boolean image=upload||path.matches("/api/v1/clients/[A-Za-z0-9_.:-]{8,80}/(image|firmware)")
                        // Web displays: authenticated by the secret in the link in the controller.
                        ||path.matches("/web/[a-f0-9]{64}(/image\\.png|/icon\\.png|/state)?");
                // The swipe of a web display page: like the page, but POST.
                boolean swipe=path.matches("/web/[a-f0-9]{64}/swipe");
                response.setHeader("Cache-Control","no-store");
                if(image||swipe){
                    if(request.getLocalPort()==adminPort){response.setStatus(404);return;}
                    if(!(upload||swipe?"POST":"GET").equals(request.getMethod())){response.setStatus(405);return;}
                } else if (api) {
                    if (request.getLocalPort()==adminPort) { response.setStatus(404); return; }
                    if (!"GET".equals(request.getMethod())) { response.setStatus(405); return; }
                    // Forwarded/X-Forwarded-For are deliberately ignored. Only the socket peer counts.
                    if (!IpNetworks.allows(settings.get().allowedIps(),request.getRemoteAddr())) {
                        response.setStatus(403); return;
                    }
                } else if (request.getLocalPort()!=adminPort) { response.setStatus(404); return; }
                chain.doFilter(req,res);
            }
        };
        FilterRegistrationBean<Filter> registration=new FilterRegistrationBean<>(guard);
        registration.setOrder(-200); return registration;
    }
}
