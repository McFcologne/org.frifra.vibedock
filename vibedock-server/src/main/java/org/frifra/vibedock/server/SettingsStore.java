package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

@Component
public class SettingsStore {
    public record Credential(String token,String accountId) {
        public Credential { token=token==null?"":token.trim(); accountId=accountId==null?"":accountId.trim(); }
    }
    /**
     * A usage data source: one provider account. type is the provider; id is a random UUID, the key used in layouts
     * and the API; label is shown on the display and can change. manual is an optional token entered by hand; otherwise
     * the account's own browser sign-in supplies the credential. folder is its sign-in folder below /data/auth.
     */
    /** suspended: kept with its sign-in, but not polled and shown as paused, e.g. while an account is not in use. */
    public record Account(String id,String type,String label,Credential manual,String folder,boolean suspended) {
        public static final List<String> TYPES=List.of("codex","claude","cursor","grok","gemini");
        /** Types with a browser sign-in; the others use a key entered by hand. */
        public static final List<String> SIGN_IN_TYPES=List.of("codex","claude","grok","gemini");
        public Account {
            manual=manual==null?new Credential("",""):manual;label=label==null?"":label.strip();
            if(id==null||!id.matches("[a-z0-9][a-z0-9_-]{0,39}"))throw new IllegalArgumentException("Invalid data source id");
            // Sources of earlier versions: "codex" and "claude" signed in into auth/codex and auth/claude.
            if(folder==null)folder=id.equals(type)?type:"accounts/"+id;
            if(!folder.matches("[a-z]+|accounts/[a-z0-9][a-z0-9_-]{0,39}"))throw new IllegalArgumentException("Invalid data source folder");
            if(!TYPES.contains(type))throw new IllegalArgumentException("Unknown data source type: "+type);
            if(label.isEmpty()||label.length()>24||label.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("A data source needs a name of up to 24 characters");
        }
        public Account(String id,String type,String label,Credential manual,String folder){this(id,type,label,manual,folder,false);}
        public Account(String id,String type,String label,Credential manual){this(id,type,label,manual,null);}
        Account withManual(Credential next){return new Account(id,type,label,next,folder,suspended);}
        Account withId(String next){return new Account(next,type,label,manual,folder,suspended);}
        Account withSuspended(boolean next){return new Account(id,type,label,manual,folder,next);}
        /** A new source with a random id and its own sign-in folder. */
        static Account create(String type,String label){String id=UUID.randomUUID().toString();return new Account(id,type,label,null,"accounts/"+id);}
        static boolean randomId(String id){return id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
        /** The file of the browser sign-in the server reads, in the source's sign-in folder; null for key-only types. */
        String credentialFile(){return switch(type){case "codex","grok"->"auth.json";case "claude"->".credentials.json";case "gemini"->".gemini/oauth_creds.json";default->null;};}
        static String typeLabel(String type){return switch(type){case "codex"->"Codex";case "claude"->"Claude";case "grok"->"Grok";case "gemini"->"Gemini";default->"Cursor";};}
    }
    public record Device(String name,String url,String token,boolean enabled) {}
    /**
     * How often the sources are asked for their usage (PollPace): dynamic learns each source's interval between the
     * shortest and the longest one; fixed asks at the shortest and only backs off up to the longest after HTTP 429.
     */
    public record UsagePolling(boolean dynamic,int minMinutes,int maxMinutes) {
        public static final UsagePolling DEFAULT=new UsagePolling(true,1,10);
        public UsagePolling {
            if(minMinutes<PollPace.MIN_MINUTES||maxMinutes>PollPace.MAX_MINUTES||minMinutes>maxMinutes)
                throw new IllegalArgumentException("Update intervals must be "+PollPace.MIN_MINUTES+"–"+PollPace.MAX_MINUTES+" minutes, the shortest not above the longest");
        }
    }
    public record Settings(List<String> allowedIps,List<Account> accounts,List<Device> devices) {
        public Settings { allowedIps=List.copyOf(allowedIps); accounts=List.copyOf(accounts); devices=List.copyOf(devices); }
        /** The two default sources a new server starts with, ids "codex" and "claude". */
        public Settings(List<String> allowedIps,Credential codex,Credential claude,List<Device> devices){
            this(allowedIps,List.of(new Account("codex","codex","Codex",codex),new Account("claude","claude","Claude",claude)),devices);
        }
        public Optional<Account> account(String id){return accounts.stream().filter(a->a.id().equals(id)).findFirst();}
        /** Manual credential of the first source of a type; empty when there is none. */
        public Credential codex(){return first("codex").map(Account::manual).orElse(new Credential("",""));}
        public Credential claude(){return first("claude").map(Account::manual).orElse(new Credential("",""));}
        public Optional<Account> first(String type){return accounts.stream().filter(a->a.type().equals(type)).findFirst();}
        Settings withAccounts(List<Account> next){return new Settings(allowedIps,next,devices);}
    }
    private final Path file,directory;
    private final ObjectMapper json;
    private final LoginCredentials login;
    final SecretStore secrets;
    private volatile Settings current;
    @org.springframework.beans.factory.annotation.Autowired
    public SettingsStore(@Value("${vibedock.data-dir}") String directory,ObjectMapper json,
                         @Value("${vibedock.auth-runtime-dir:}") String runtime) throws IOException {
        this(directory,json,new SecretStore(Path.of(directory),"",runtime));
    }
    public SettingsStore(String directory,ObjectMapper json) throws IOException {this(directory,json,"");}
    SettingsStore(String directory,ObjectMapper json,SecretStore secrets) throws IOException {
        this.json=json;this.secrets=secrets;
        Path dir=Path.of(directory);Files.createDirectories(dir);this.directory=dir;
        login=new LoginCredentials(dir,json,secrets);
        file=dir.resolve("settings.json");
        current=Files.exists(file)?read(json.readTree(secrets.read(file)))
                :new Settings(List.of(),new Credential("",""),new Credential("",""),List.of());
        validate(current);
    }
    /** Earlier files had exactly one Codex and one Claude credential; they become the two default sources. */
    private Settings read(tools.jackson.databind.JsonNode root) throws IOException {
        if(root.has("accounts")){
            if(root instanceof tools.jackson.databind.node.ObjectNode object)object.remove(IMPORT_TRANSACTION);
            return json.treeToValue(root,Settings.class);
        }
        List<String> ips=new ArrayList<>();root.path("allowedIps").forEach(n->ips.add(n.asText()));
        var devices=Arrays.asList(json.treeToValue(root.path("devices").isMissingNode()?json.createArrayNode():root.path("devices"),Device[].class));
        return new Settings(ips,credential(root.path("codex")),credential(root.path("claude")),devices);
    }
    private static Credential credential(tools.jackson.databind.JsonNode node){
        return new Credential(node.path("token").asText(""),node.path("accountId").asText(""));
    }
    public Settings get() { return current; }
    private Path pollingFile(){return directory.resolve("usage-polling.json");}
    private volatile UsagePolling polling;
    /** The update interval of the sources; the default until it is saved once. */
    public UsagePolling polling() {
        var known=polling;if(known!=null)return known;
        try{known=Files.exists(pollingFile())?json.readValue(pollingFile().toFile(),UsagePolling.class):UsagePolling.DEFAULT;}
        catch(RuntimeException e){known=UsagePolling.DEFAULT;}
        return polling=known;
    }
    public synchronized void savePolling(UsagePolling next) throws IOException {
        writePrivate(pollingFile(),json.writeValueAsBytes(next));polling=next;
    }
    /**
     * Gives sources of earlier versions (ids "codex", "claude" or derived from their name) a random id; their sign-in
     * folder stays. Returns old id → new id, also for "codex" and "claude" when a design may still name them.
     */
    public synchronized Map<String,String> assignRandomIds() throws IOException {
        Map<String,String> renamed=new LinkedHashMap<>();
        var accounts=current.accounts().stream().map(a->{
            if(Account.randomId(a.id()))return a;
            String id=UUID.randomUUID().toString();renamed.put(a.id(),id);return a.withId(id);
        }).toList();
        if(!renamed.isEmpty()){
            // Recorded before the new ids are saved, so designs can still be renamed if the start is interrupted.
            var pending=new LinkedHashMap<>(pendingRenames());pending.putAll(renamed);writePrivate(pendingFile(),json.writeValueAsBytes(pending));
            save(current.withAccounts(accounts));
        }
        for(String type:List.of("codex","claude"))
            if(!renamed.containsKey(type))current.accounts().stream().filter(a->a.folder().equals(type)).findFirst().ifPresent(a->renamed.put(type,a.id()));
        return renamed;
    }
    /** The settings with every source's credential: the manual token, otherwise the source's browser sign-in. */
    public Settings effective() {
        var s=current;
        return s.withAccounts(s.accounts().stream().map(a->a.withManual(login.read(a,a.manual()))).toList());
    }
    /** Where a source's browser sign-in keeps its credentials; the two default sources keep their earlier folders. */
    Path authDirectory(Account account){return authDirectory(directory,account);}
    static Path authDirectory(Path dataDir,Account account){
        return dataDir.resolve("auth/"+account.folder());
    }
    private Path pendingFile(){return directory.resolve("source-ids.pending.json");}
    /** Id renames saved by assignRandomIds whose designs were not updated yet (an interrupted start). */
    synchronized Map<String,String> pendingRenames() throws IOException {
        if(!Files.exists(pendingFile()))return Map.of();
        return json.readValue(pendingFile().toFile(),new tools.jackson.core.type.TypeReference<LinkedHashMap<String,String>>(){});
    }
    synchronized void clearPendingRenames() throws IOException {Files.deleteIfExists(pendingFile());}
    private Path adminPasswordFile(){return directory.resolve("admin-password");}
    /** The BCrypt hash of the administration password set under Settings; empty while ADMIN_PASSWORD applies. */
    synchronized Optional<String> adminPasswordHash() throws IOException {
        if(!Files.exists(adminPasswordFile()))return Optional.empty();
        String hash=Files.readString(adminPasswordFile()).strip();
        return hash.startsWith("$2")?Optional.of(hash):Optional.empty();
    }
    /** Saves the hash of a password set under Settings; null deletes it, so ADMIN_PASSWORD applies again. */
    synchronized void saveAdminPasswordHash(String hash) throws IOException {
        if(hash==null)Files.deleteIfExists(adminPasswordFile());
        else writePrivate(adminPasswordFile(),hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    /** The folder that holds every source's sign-in folder. */
    Path authRoot(){return directory.resolve("auth");}
    private void writePrivate(Path target,byte[] bytes) throws IOException {
        Path temporary=Files.createTempFile(target.getParent(),"pending-",".tmp");
        try{
            Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));Files.write(temporary,bytes);
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
    /** Deletes a source's browser sign-in. The two default sources keep their folder; only the credentials go. */
    void forgetSignIn(Account account) throws IOException {
        Path dir=authDirectory(account);
        if(account.folder().equals(account.type())){
            if(account.credentialFile()!=null)Files.deleteIfExists(dir.resolve(account.credentialFile()));
            return;
        }
        if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS))return;
        try(var paths=Files.walk(dir)){
            for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
        }
    }
    public synchronized void save(Settings settings) throws IOException {save(settings,null);}
    /**
     * importTransaction: the import that writes these settings (SourceTransfer.apply). It is saved in the same atomic
     * write, so after a stop a restart can tell whether that import was saved; the next save drops it.
     */
    static final String IMPORT_TRANSACTION="importTransaction";
    synchronized void save(Settings settings,String importTransaction) throws IOException {
        validate(settings);
        var node=json.valueToTree(settings);
        if(importTransaction!=null)((tools.jackson.databind.node.ObjectNode)node).put(IMPORT_TRANSACTION,importTransaction);
        secrets.write(file,json.writeValueAsBytes(node));
        current=settings;
    }

    static void validate(Settings s) {
        if (s.allowedIps().size()>100 || s.devices().size()>3) throw new IllegalArgumentException("At most 100 IP rules and three devices");
        if (s.accounts().size()>12) throw new IllegalArgumentException("At most 12 data sources");
        if (s.accounts().stream().map(Account::id).distinct().count()!=s.accounts().size()) throw new IllegalArgumentException("Duplicate data source");
        s.allowedIps().forEach(IpNetworks::parse);
        for (Credential c:s.accounts().stream().map(Account::manual).toList()) {
            if (c.token().length()>16384 || c.token().contains("\n") || c.token().contains("\r") || c.accountId().length()>200
                    || c.accountId().contains("\n") || c.accountId().contains("\r"))
                throw new IllegalArgumentException("Invalid credential format");
        }
        for (Device d:s.devices()) {
            URI u;
            try { u=URI.create(d.url()); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid device address"); }
            if (!"http".equals(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null || u.getQuery()!=null
                    || u.getFragment()!=null || !(u.getPath().isEmpty() || u.getPath().equals("/")))
                throw new IllegalArgumentException("Device address must be http://HOST[:PORT]");
            if (d.name()==null || d.name().isBlank() || d.name().length()>40 || d.token()==null
                    || d.token().contains("\n") || d.token().contains("\r") || d.token().length()>1024
                    || (d.enabled() && d.token().isBlank())) throw new IllegalArgumentException("Device name or pairing token missing or invalid");
        }
    }
}
