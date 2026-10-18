package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Owns only login processes; no model requests, device writes or host credential reads. */
@Service
public class BrowserLogin {
    public record View(String id,String status,String authUrl,String userCode,boolean needsCode,String message) {}
    // Claude Code 2.1.x prints claude.com/cai/oauth/authorize; older clients used claude.ai/oauth/authorize.
    private static final Pattern CLAUDE_URL=Pattern.compile("https://claude\\.(?:ai|com)/(?:cai/)?oauth/authorize\\?[^\\s<>\"']+");
    private static final Pattern ANSI=Pattern.compile("\\x1b\\[[0-9;?]*[ -/]*[@-~]");
    private final Map<String,Session> sessions=new HashMap<>();
    private final ScheduledExecutorService timers=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"login-timeouts");t.setDaemon(true);return t;
    });
    private final Path directory;
    private final String codexCli,claudeCli;
    @Value("${vibedock.grok-cli:grok}") private String grokCli="grok";
    // Grok Build prints the device page of accounts.x.ai with the code, e.g. .../oauth2/device?user_code=ABCD-1234.
    @Value("${vibedock.gemini-cli:gemini}") private String geminiCli="gemini";
    // Gemini CLI prints Google's consent page; its redirect shows the code on codeassist.google.com to paste back.
    private static final Pattern GEMINI_URL=Pattern.compile("https://accounts\\.google\\.com/o/oauth2/v2/auth\\?[^\\s<>\"']+");
    private static final Pattern GROK_URL=Pattern.compile("https://accounts\\.x\\.ai/oauth2/device\\?user_code=([A-Z0-9]{4}-[A-Z0-9]{4})");
    private final ObjectMapper json;
    private final SettingsStore settings;
    private final UsageService usage;
    private boolean closed;
    private static final class Session {
        final String id=UUID.randomUUID().toString();
        final String provider,type;  // provider: the data source id; type: codex or claude
        Process process;
        SecretStore.Workspace workspace;
        ScheduledFuture<?> timeout;
        View view;
        Session(String provider,String type) { this.provider=provider;this.type=type;view=new View(id,"starting",null,null,false,"Starting sign-in …"); }
    }
    public BrowserLogin(@Value("${vibedock.data-dir}") String directory,
                        @Value("${vibedock.codex-cli:codex}") String codexCli,
                        @Value("${vibedock.claude-cli:claude}") String claudeCli,
                        ObjectMapper json,SettingsStore settings,UsageService usage) {
        this.directory=Path.of(directory).toAbsolutePath();this.codexCli=codexCli;this.claudeCli=claudeCli;
        this.json=json;this.settings=settings;this.usage=usage;
    }
    public synchronized View status(String provider) {
        check(provider);
        var s=sessions.get(provider);
        return s==null ? new View(null,"idle",null,null,false,"Ready to sign in.") : s.view;
    }
    public synchronized View start(String provider) {
        var account=check(provider);
        if (closed) throw new IllegalStateException("Service is shutting down");
        var previous=sessions.get(provider);
        if (previous!=null && active(previous)) return previous.view; // double click does not start another OAuth flow
        var s=new Session(provider,account.type());sessions.put(provider,s);
        try {
            var auth=SettingsStore.authDirectory(directory,account);
            for (Path dir:List.of(directory.resolve("home"),auth.getParent(),auth)) {
                Files.createDirectories(dir);
                Files.setPosixFilePermissions(dir,PosixFilePermissions.fromString("rwx------"));
            }
            s.workspace=settings.secrets.workspace(auth);
            auth=s.workspace.path;
            List<String> command=switch(account.type()){
                case "codex" -> List.of(codexCli,"-c","cli_auth_credentials_store=\"file\"","app-server");
                case "grok" -> List.of(grokCli,"login","--device-auth");
                // The Gemini CLI asks for the code only in a terminal; script gives it one. It signs in on start.
                case "gemini" -> List.of("script","-qfec",geminiCli,"/dev/null");
                default -> List.of(claudeCli,"auth","login","--claudeai","--sso");
            };
            ProcessBuilder builder=new ProcessBuilder(command).directory(auth.toFile());
            var env=builder.environment();
            // Only the service-owned login caches may supply authentication.
            for (String key:List.of("OPENAI_API_KEY","CODEX_API_KEY","CODEX_ACCESS_TOKEN","CODEX_AUTH_JSON",
                    "ANTHROPIC_API_KEY","ANTHROPIC_AUTH_TOKEN","CLAUDE_CODE_OAUTH_TOKEN","ANTHROPIC_PROFILE")) env.remove(key);
            env.put("HOME",auth.toString());
            // Each source signs in into its own folder, so several accounts of one provider can coexist.
            env.put("CODEX_HOME",auth.toString());
            env.put("CLAUDE_CONFIG_DIR",auth.toString());
            env.put("GROK_HOME",auth.toString());env.remove("XAI_API_KEY");
            if (account.type().equals("gemini")) {
                SignInRenewal.prepareGemini(auth);
                SignInRenewal.GEMINI_FOREIGN_CREDENTIALS.forEach(env::remove);env.putAll(SignInRenewal.geminiEnvironment(auth));
                builder.directory(auth.toFile());
            }
            env.put("NO_COLOR","1");env.put("TERM","dumb");
            if (!s.type.equals("codex")) builder.redirectErrorStream(true);
            else builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            s.process=builder.start();
            s.timeout=timers.schedule(()->finish(s,"timeout","Sign-in expired. Please start again."),10,TimeUnit.MINUTES);
            if (s.type.equals("codex")) send(s,Map.of("method","initialize","id",0,"params",Map.of(
                    "clientInfo",Map.of("name","vibedock_server","title","VibeDock Server","version","0.1.0"))));
            Thread.startVirtualThread(()->read(s));
        } catch (Exception ignored) { finish(s,"error","Sign-in client unavailable. Please start the server with the current Docker image."); }
        return s.view;
    }
    public synchronized View cancel(String provider,String id) {
        var s=matching(provider,id);finish(s,"cancelled","Sign-in cancelled.");return s.view;
    }
    public synchronized View submitCode(String provider,String id,String code) {
        var s=matching(provider,id);
        if (!Set.of("claude","gemini").contains(s.type) || !active(s) || !s.view.needsCode())
            throw new IllegalArgumentException("No sign-in is waiting for a code");
        if (code==null || !code.matches(s.type.equals("gemini")?"[A-Za-z0-9/_.-]{10,512}":"[A-Za-z0-9_#.-]{1,2048}"))
            throw new IllegalArgumentException("Please enter only the complete one-time browser code");
        try {
            // The Gemini CLI reads from a terminal, where Enter is a carriage return.
            s.process.getOutputStream().write((code+(s.type.equals("gemini")?"\r":"\n")).getBytes(StandardCharsets.UTF_8));s.process.getOutputStream().flush();
            s.view=new View(s.id,"completing",s.view.authUrl(),null,false,"Completing sign-in …");
            if (s.type.equals("gemini")) for (int i=1;i<=60;i++) timers.schedule(()->geminiSignedIn(s),i,TimeUnit.SECONDS);
        } catch (IOException ignored) { finish(s,"error","The sign-in client exited. Please start again."); }
        return s.view;
    }
    private void read(Session s) {
        try (var reader=new InputStreamReader(s.process.getInputStream(),StandardCharsets.UTF_8)) {
            StringBuilder buffer=new StringBuilder();int total=0;char[] chunk=new char[512];int count;
            while ((count=reader.read(chunk))!=-1) {
                total+=count;if (total>131072) throw new IOException("Output limit");
                buffer.append(chunk,0,count);
                if (s.type.equals("codex")) {
                    int newline;
                    while ((newline=buffer.indexOf("\n"))>=0) {
                        String line=buffer.substring(0,newline);buffer.delete(0,newline+1);
                        if (!line.isBlank()) codexMessage(s,json.readTree(line));
                    }
                    if (buffer.length()>65536) throw new IOException("Line limit");
                } else if (s.type.equals("grok")) {
                    grokOutput(s,buffer.toString());
                    if (buffer.length()>16384) buffer.delete(0,buffer.length()-8192);
                } else if (s.type.equals("gemini")) {
                    geminiOutput(s,buffer.toString());
                    // After the code the CLI keeps running its terminal interface; only the saved sign-in counts.
                    if (buffer.length()>16384) buffer.delete(0,buffer.length()-8192);
                } else {
                    claudeOutput(s,buffer.toString(),false);
                    if (buffer.length()>16384) buffer.delete(0,buffer.length()-8192);
                }
            }
            if (s.type.equals("gemini")) {
                s.process.waitFor();
                if (!geminiSignedIn(s)) finish(s,"error","Gemini sign-in failed. Please start again; the code is valid only once.");
            } else if (s.type.equals("grok")) {
                int exit=s.process.waitFor();
                if (exit==0) completed(s);
                else finish(s,"error","Grok sign-in failed. Grok Build needs a SuperGrok or X Premium+ subscription.");
            } else if (s.type.equals("claude")) {
                claudeOutput(s,buffer.toString(),true);
                int exit=s.process.waitFor();
                if (exit==0) completed(s);
                else finish(s,"error","Claude sign-in failed. Please start again and check your organization's approval.");
            } else finish(s,"error","The Codex sign-in client exited before completion.");
        } catch (Exception ignored) { finish(s,"error","Sign-in could not be completed. Please start again."); }
    }
    private synchronized void codexMessage(Session s,JsonNode node) throws Exception {
        if (!active(s)) return;
        if (node.has("error")) {
            finish(s,"error","Codex sign-in failed. Device-code sign-in must be allowed in the workspace.");return;
        }
        if (node.path("id").isIntegralNumber() && node.path("id").intValue()==0) {
            send(s,Map.of("method","initialized","params",Map.of()));
            send(s,Map.of("method","account/login/start","id",1,"params",Map.of("type","chatgptDeviceCode")));
        } else if (node.path("id").isIntegralNumber() && node.path("id").intValue()==1) {
            var result=node.path("result");String url=result.path("verificationUrl").asText();String code=result.path("userCode").asText();
            if (!allowedUrl("codex",url) || !code.matches("[A-Za-z0-9-]{4,32}")) throw new IOException("Invalid login response");
            s.view=new View(s.id,"waiting",url,code,false,"Open the link and enter the code shown here on the provider page.");
        } else if (node.path("method").asText().equals("account/login/completed")) {
            if (node.path("params").path("success").asBoolean(false)) completed(s);
            else finish(s,"error","Codex sign-in failed. Please start again.");
        }
    }
    private synchronized void claudeOutput(Session s,String text,boolean eof) {
        if (!active(s) || s.view.status().equals("completing")) return;
        String clean=ANSI.matcher(text).replaceAll("");var match=CLAUDE_URL.matcher(clean);
        while (match.find()) {
            if (!eof && match.end()==clean.length()) continue; // never publish a partially received URL
            String url=match.group();
            if (allowedUrl("claude",url)) s.view=new View(s.id,"waiting",url,null,true,
                    "Complete the sign-in in the browser. If a code appears there, paste it below.");
        }
    }
    private synchronized void grokOutput(Session s,String text) {
        if (!active(s) || !s.view.status().equals("starting")) return;
        var match=GROK_URL.matcher(ANSI.matcher(text).replaceAll(""));
        if (match.find() && allowedUrl("grok",match.group()))
            s.view=new View(s.id,"waiting",match.group(),match.group(1),false,"Open the link and confirm the code shown here on the xAI page.");
    }
    private synchronized void geminiOutput(Session s,String text) {
        if (!active(s)) return;
        if (s.view.status().equals("completing")) {
            if (geminiSignedIn(s)) return;
            if (!text.contains("Failed to authenticate")) return;
        }
        String clean=ANSI.matcher(text).replaceAll("");var match=GEMINI_URL.matcher(clean);String url=null;
        while (match.find()) if (match.end()<clean.length()) url=match.group(); // never publish a partially received URL
        if (url==null || !allowedUrl("gemini",url) || url.equals(s.view.authUrl())) return;
        boolean retry=s.view.status().equals("completing")||s.view.authUrl()!=null;
        s.view=new View(s.id,"waiting",url,null,true,retry
                ? "Google did not accept the code. Open the link again and paste the new code below."
                : "Open the link, sign in with Google and paste the code shown on the Code Assist page below.");
    }
    /** True once the CLI saved a usable sign-in; then the sign-in completes and the CLI is stopped. */
    private synchronized boolean geminiSignedIn(Session s) {
        if (!active(s) || !s.view.status().equals("completing")) return false;
        var account=settings.get().account(s.provider).orElse(null);
        if (account==null || s.workspace==null || LoginCredentials.gemini(s.workspace.path.resolve(account.credentialFile()),json).token().isBlank()) return false;
        completed(s);return true;
    }
    static boolean allowedUrl(String provider,String value) {
        try {
            var uri=URI.create(value);
            return value.length()<=8192 && "https".equals(uri.getScheme()) && uri.getUserInfo()==null
                    && uri.getPort()==-1 && uri.getFragment()==null && (provider.equals("codex")
                    ? "auth.openai.com".equals(uri.getHost()) && "/codex/device".equals(uri.getPath())
                    : provider.equals("grok") ? "accounts.x.ai".equals(uri.getHost()) && "/oauth2/device".equals(uri.getPath())
                    : provider.equals("gemini") ? "accounts.google.com".equals(uri.getHost()) && "/o/oauth2/v2/auth".equals(uri.getPath())
                      && value.contains("redirect_uri=https%3A%2F%2Fcodeassist.google.com%2Fauthcode")
                    : ("claude.ai".equals(uri.getHost()) && "/oauth/authorize".equals(uri.getPath()))
                      || ("claude.com".equals(uri.getHost()) && "/cai/oauth/authorize".equals(uri.getPath())));
        } catch (RuntimeException ignored) { return false; }
    }
    private void send(Session s,Object message) throws IOException {
        s.process.getOutputStream().write((json.writeValueAsString(message)+"\n").getBytes(StandardCharsets.UTF_8));
        s.process.getOutputStream().flush();
    }
    private synchronized void completed(Session s) {
        if (!active(s)) return;
        var account=settings.get().account(s.provider).orElse(null);
        if (account==null) { finish(s,"error","The data source was removed during the sign-in.");return; }
        var credential=s.workspace==null?new SettingsStore.Credential("",""):new LoginCredentials(directory,json).readFile(account.type(),s.workspace.path.resolve(account.credentialFile()));
        if (credential.token().isBlank()) {
            finish(s,"error","The client stored no readable credentials. Please sign in inside the Linux Docker container.");return;
        }
        try {
            stop(s);
            s.workspace.close();s.workspace=null;
            // The browser sign-in replaces a manual token of this source.
            var old=settings.get();
            settings.save(old.withAccounts(old.accounts().stream().map(a->a.id().equals(s.provider)?a.withManual(null):a).toList()));
            usage.configurationChanged();
            finish(s,"success","Signed in. Credentials saved; usage data arrives with the next refresh.");
        } catch (IOException ignored) { finish(s,"error","Credentials saved, but the configuration could not be updated."); }
    }
    private synchronized void finish(Session s,String status,String message) {
        if (!active(s)) return;
        s.view=new View(s.id,status,null,null,false,message);
        if (s.timeout!=null) s.timeout.cancel(false);
        try {
            stop(s);
            if(s.workspace!=null){s.workspace.close();s.workspace=null;}
        }catch(IOException e){
            s.view=new View(s.id,"error",null,null,false,"The sign-in could not be saved securely. Check server storage and retry.");
            org.slf4j.LoggerFactory.getLogger(BrowserLogin.class).warn("Could not persist encrypted sign-in: {}",e.getMessage());
        }
    }
    private void stop(Session s) throws IOException {
        if(s.process==null)return;
        s.process.descendants().forEach(ProcessHandle::destroyForcibly);s.process.destroyForcibly();
        try{if(!s.process.waitFor(5,TimeUnit.SECONDS))throw new IOException("Sign-in process did not stop");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted while stopping sign-in",e);}
    }

    private boolean active(Session s) { return sessions.get(s.provider)==s && Set.of("starting","waiting","completing").contains(s.view.status()); }
    private Session matching(String provider,String id) {
        check(provider);var s=sessions.get(provider);
        if (s==null || !s.id.equals(id)) throw new IllegalArgumentException("Sign-in is no longer current");return s;
    }
    private SettingsStore.Account check(String provider) {
        var account=settings.get().account(provider).orElseThrow(()->new IllegalArgumentException("Unknown data source"));
        if (!SettingsStore.Account.SIGN_IN_TYPES.contains(account.type())) throw new IllegalArgumentException("This data source uses a key, not a sign-in");
        return account;
    }
    @PreDestroy public synchronized void close() {
        closed=true;for (var s:sessions.values()) finish(s,"cancelled","Server is shutting down.");timers.shutdownNow();
    }
}
