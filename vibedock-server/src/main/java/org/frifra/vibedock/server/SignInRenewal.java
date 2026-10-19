package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Keeps CLI sign-ins fresh. VibeDock never refreshes tokens itself: shortly before a token expires it runs the official
 * CLI with a command that needs a valid sign-in and spends no usage, and the CLI renews the token in the source's own
 * folder. Grok Build: "grok models". Gemini CLI: "gemini --list-sessions", which signs in (renewing an expiring
 * token through the Google auth library) and lists the saved chats of an empty folder. Claude Code renews its token
 * (about eight hours) only before a model request, not for "claude auth status": one minimal request in print mode
 * (smallest model, no tools, skills, MCP servers or session, own short system prompt, an empty working folder) with
 * the source's folder as CLAUDE_CONFIG_DIR. Whether a renewal worked is read from the expiry in the file afterwards and
 * logged.
 */
@Component
class SignInRenewal {
    private static final Logger log=LoggerFactory.getLogger(SignInRenewal.class);
    static final long GROK_RENEW_BEFORE_SECONDS=900;
    /** Google tokens live an hour; the auth library only renews within five minutes of the expiry. */
    static final long GEMINI_RENEW_BEFORE_SECONDS=240;
    /** Claude Code tokens live about eight hours; renewed half an hour before, and also once they have expired. */
    static final long CLAUDE_RENEW_BEFORE_SECONDS=1800;
    /** A Claude renewal that changed nothing is tried again after this long, not with every poll. */
    static final long CLAUDE_RETRY_SECONDS=600;
    /** Once the token has expired and a renewal failed, the refresh token is most likely gone too: tried rarely. */
    static final long CLAUDE_EXPIRED_RETRY_SECONDS=6*3600;
    /** The minimal request that makes the Claude CLI renew its token; its answer is discarded. */
    static List<String> claudeCommand(String cli){
        return List.of(cli,"-p","Reply with OK.","--model","haiku","--max-turns","1","--tools","","--strict-mcp-config",
                "--disable-slash-commands","--no-session-persistence","--system-prompt","Reply with OK.");
    }
    private static final long MIN_GAP_SECONDS=120;
    private final String grokCli,geminiCli,claudeCli;private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private SettingsStore settings;
    private final Map<Path,Long> lastRun=new ConcurrentHashMap<>();
    /** Claude folders whose last renewal failed with the token already expired: only these wait the long gap. */
    private final java.util.Set<Path> expiredFailures=ConcurrentHashMap.newKeySet();
    /** The time of the Claude renewal decisions; replaced in tests. */
    Clock clock=Clock.systemUTC();
    SignInRenewal(@Value("${vibedock.grok-cli:grok}") String grokCli,@Value("${vibedock.gemini-cli:gemini}") String geminiCli,
                  @Value("${vibedock.claude-cli:claude}") String claudeCli,ObjectMapper json){
        this.grokCli=grokCli;this.geminiCli=geminiCli;this.claudeCli=claudeCli;this.json=json;
    }

    void renewIfDue(SettingsStore.Account account,Path folder){
        switch(account.type()){
            case "grok"->grok(folder);
            case "gemini"->gemini(folder);
            case "claude"->claude(folder);
            default->{}
        }
    }
    private void grok(Path folder){
        long expires=expiry(folder.resolve("auth.json"),SignInRenewal::grokExpiry);
        if(expires<=0||expires-Instant.now().getEpochSecond()>GROK_RENEW_BEFORE_SECONDS)return;
        run("Grok",folder,List.of(grokCli,"models"),Map.of("GROK_HOME",folder.toString(),"HOME",folder.toString(),"GROK_AUTH_EXPIRED","1"),
                List.of("XAI_API_KEY"));
    }
    private void gemini(Path folder){
        long expires=expiry(folder.resolve(".gemini/oauth_creds.json"),node->epochSeconds(node.path("expiry_date")));
        if(expires<=0||expires-Instant.now().getEpochSecond()>GEMINI_RENEW_BEFORE_SECONDS)return;
        Long previous=lastRun.get(folder);long now=Instant.now().getEpochSecond();
        if(previous!=null&&now-previous<MIN_GAP_SECONDS)return;
        lastRun.put(folder,now);
        try{if(settings==null)prepareGemini(folder);}catch(Exception e){log.warn("Gemini settings in {} could not be written: {}",folder.getFileName(),e.toString());return;}
        run("Gemini",folder,List.of(geminiCli,"--list-sessions"),geminiEnvironment(folder),GEMINI_FOREIGN_CREDENTIALS);
    }
    private void claude(Path folder){
        Path file=folder.resolve(".credentials.json");
        long expires=expiry(file,SignInRenewal::claudeExpiry),now=clock.instant().getEpochSecond();
        if(expires<=0||expires-now>CLAUDE_RENEW_BEFORE_SECONDS)return;
        Long previous=lastRun.get(folder);
        if(previous!=null&&now-previous<(expiredFailures.contains(folder)?CLAUDE_EXPIRED_RETRY_SECONDS:CLAUDE_RETRY_SECONDS))return;
        lastRun.put(folder,now);
        run("Claude",folder,claudeCommand(claudeCli),Map.of("CLAUDE_CONFIG_DIR",folder.toString(),"NO_COLOR","1","TERM","dumb"),
                CLAUDE_FOREIGN_CREDENTIALS);
        long after=expiry(file,SignInRenewal::claudeExpiry);
        if(after>expires){lastRun.remove(folder);expiredFailures.remove(folder);log.info("Claude sign-in in {} renewed until {}",folder.getFileName(),Instant.ofEpochSecond(after));}
        else if(expires<now){expiredFailures.add(folder);log.warn("Claude sign-in in {} expired at {} and could not be renewed; sign in again (next try in {} h)",
                folder.getFileName(),Instant.ofEpochSecond(expires),CLAUDE_EXPIRED_RETRY_SECONDS/3600);}
        else{expiredFailures.remove(folder);log.warn("Claude sign-in in {} not renewed (expires {}); tried again in {} min",folder.getFileName(),Instant.ofEpochSecond(expires),CLAUDE_RETRY_SECONDS/60);}
    }
    /** The expiry of the access token in Claude Code's .credentials.json (milliseconds there), 0 when missing. */
    static long claudeExpiry(JsonNode node){return epochSeconds(node.path("claudeAiOauth").path("expiresAt"));}
    /** Environment variables that would let the Claude CLI use other credentials than the source's own sign-in. */
    static final List<String> CLAUDE_FOREIGN_CREDENTIALS=List.of("ANTHROPIC_API_KEY","ANTHROPIC_AUTH_TOKEN","CLAUDE_CODE_OAUTH_TOKEN",
            "CLAUDE_CODE_USE_BEDROCK","CLAUDE_CODE_USE_VERTEX","CLAUDE_CODE_USE_FOUNDRY","ANTHROPIC_PROFILE");
    /** Environment variables that would let the Gemini CLI use other credentials than the source's own sign-in. */
    static final List<String> GEMINI_FOREIGN_CREDENTIALS=List.of("GEMINI_API_KEY","GOOGLE_API_KEY","GOOGLE_GENAI_USE_VERTEXAI","GOOGLE_GENAI_USE_GCA",
            "GOOGLE_CLOUD_ACCESS_TOKEN","GOOGLE_APPLICATION_CREDENTIALS","GOOGLE_CLOUD_PROJECT","GOOGLE_CLOUD_PROJECT_ID");
    static Map<String,String> geminiEnvironment(Path folder){
        return Map.of("GEMINI_CLI_HOME",folder.toString(),"HOME",folder.toString(),"NO_BROWSER","true",
                "GEMINI_FORCE_FILE_STORAGE","true","NO_COLOR","1","TERM","dumb");
    }
    /** The Gemini CLI signs in with Google only when its settings select it; written once per source folder. */
    static void prepareGemini(Path folder) throws java.io.IOException {
        Path dir=folder.resolve(".gemini");
        if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)){Files.createDirectories(dir);Files.setPosixFilePermissions(dir,PosixFilePermissions.fromString("rwx------"));}
        Path settings=dir.resolve("settings.json");
        if(!Files.exists(settings,LinkOption.NOFOLLOW_LINKS))
            Files.writeString(settings,"{\"security\":{\"auth\":{\"selectedType\":\"oauth-personal\"}}}\n",StandardCharsets.UTF_8);
    }
    private void run(String name,Path folder,List<String> command,Map<String,String> environment,List<String> removed){
        SecretStore.Workspace workspace=null;Process process=null;Path empty=null;
        try{
            Path actual=folder;
            if(settings!=null){workspace=settings.secrets.workspace(folder);actual=workspace.path;}
            // Claude Code reads the working folder as its project: an empty one, not the sign-in folder.
            if(name.equals("Claude"))empty=Files.createTempDirectory("vibedock-claude-renewal-");
            var builder=new ProcessBuilder(command).directory((empty!=null?empty:actual).toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
            removed.forEach(builder.environment()::remove);
            for(var entry:environment.entrySet())builder.environment().put(entry.getKey(),entry.getValue().replace(folder.toString(),actual.toString()));
            builder.environment().put("HOME",actual.toString());
            if(name.equals("Gemini"))prepareGemini(actual);
            process=builder.start();
            if(!process.waitFor(60,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();process.waitFor();}
            else if(process.exitValue()!=0)log.warn("{} sign-in in {} could not be renewed (exit {}); sign in again",name,folder.getFileName(),process.exitValue());
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(Exception e){log.warn("{} CLI not available to renew the sign-in: {}",name,e.getClass().getSimpleName());}
        finally{
            if(process!=null&&process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();try{process.waitFor();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            if(workspace!=null)try{workspace.close();}catch(java.io.IOException e){log.error("Could not persist encrypted {} sign-in; check server storage",name);}
            if(empty!=null)deleteTree(empty);
        }
    }

    private static void deleteTree(Path dir){
        try(var paths=Files.walk(dir)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(java.io.IOException ignored){}});}
        catch(java.io.IOException ignored){}
    }
    private long expiry(Path file,java.util.function.ToLongFunction<JsonNode> reader){
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))return 0;
        try{return reader.applyAsLong(settings==null?json.readTree(file.toFile()):json.readTree(settings.secrets.read(file)));}catch(Exception e){return 0;}
    }
    /** The earliest expiry of the sign-in entries in Grok's auth.json, 0 when none is given. */
    private static long grokExpiry(JsonNode node){
        long min=0;
        if(node.isObject()&&node.path("key").isTextual()){long e=epochSeconds(node.path("expires_at"));if(e>0)min=e;}
        for(var child:node){long e=grokExpiry(child);if(e>0&&(min==0||e<min))min=e;}
        return min;
    }
    /** Seconds since the epoch from a number (seconds or milliseconds) or an ISO date; 0 when missing. */
    static long epochSeconds(JsonNode value){
        if(value==null||value.isMissingNode()||value.isNull())return 0;
        if(value.isNumber()){long v=value.longValue();return v>100_000_000_000L?v/1000:v;}
        if(value.isTextual()){
            String text=value.textValue().strip();
            try{return OffsetDateTime.parse(text).toEpochSecond();}catch(Exception ignored){}
            try{return Instant.parse(text).getEpochSecond();}catch(Exception ignored){}
            try{long v=Long.parseLong(text);return v>100_000_000_000L?v/1000:v;}catch(Exception ignored){}
        }
        if(value.isObject()&&value.has("seconds"))return value.path("seconds").asLong();
        return 0;
    }
}
