package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** Browser login tokens: only SHA-256 hashes are persisted; provider exports never include them. */
@Component
final class AdminLoginTokens {
    static final int DAYS=30,MAX_AGE=(int)Duration.ofDays(DAYS).toSeconds();
    static final long RENEW_AFTER=Duration.ofHours(1).toSeconds();
    record Entry(String hash,String identity,long renewed,long expires) {}
    record State(String environmentHash,List<Entry> logins) {}
    private String environmentHash;
    private final Path file;private final ObjectMapper json;private final Clock clock;
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private final SecureRandom random=new SecureRandom();
    @org.springframework.beans.factory.annotation.Autowired
    AdminLoginTokens(@Value("${vibedock.data-dir}") String directory,ObjectMapper json) throws IOException {
        this(Path.of(directory),json,Clock.systemUTC());
    }
    AdminLoginTokens(Path directory,ObjectMapper json,Clock clock) throws IOException {
        file=directory.resolve("admin-logins.json");this.json=json;this.clock=clock;
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
            if(Files.isSymbolicLink(file)||Files.size(file)>1_048_576)throw new IOException("Invalid browser login store");
            var state=json.readValue(Files.readAllBytes(file),State.class);environmentHash=state.environmentHash();
            for(var entry:state.logins())entries.put(entry.hash(),entry);
            if(entries.values().removeIf(e->e.expires()<=now()))persist();
        }
    }
    /** Keep the configured password's salted BCrypt identity stable across server restarts. */
    synchronized String environmentHash(String password) throws IOException {
        var bcrypt=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        if(environmentHash!=null&&bcrypt.matches(password,environmentHash))return environmentHash;
        String before=environmentHash;environmentHash=bcrypt.encode(password);
        try{persist();}catch(IOException e){environmentHash=before;throw e;}return environmentHash;
    }
    static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private long now(){return clock.instant().getEpochSecond();}
    synchronized String issue(String identity) throws IOException {
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=HexFormat.of().formatHex(bytes);
        var before=new LinkedHashMap<>(entries);long now=now();
        entries.values().removeIf(e->e.expires()<=now);
        entries.put(hash(token),new Entry(hash(token),identity,now,now+MAX_AGE));
        saveOrRestore(before);return token;
    }
    synchronized boolean valid(String token,String identity) throws IOException {
        if(token==null||!token.matches("[a-f0-9]{64}"))return false;
        var entry=entries.get(hash(token));if(entry==null)return false;
        if(entry.expires()>now()&&entry.identity().equals(identity))return true;
        revoke(token);return false;
    }
    /** At most one write per browser per hour, even while the page polls frequently. */
    synchronized boolean renew(String token,String identity) throws IOException {
        if(!valid(token,identity))return false;
        var entry=entries.get(hash(token));long now=now();
        if(now-entry.renewed()<RENEW_AFTER)return false;
        var before=new LinkedHashMap<>(entries);
        entries.put(entry.hash(),new Entry(entry.hash(),identity,now,now+MAX_AGE));saveOrRestore(before);return true;
    }
    synchronized void revoke(String token) throws IOException {
        if(token==null)return;var before=new LinkedHashMap<>(entries);
        if(entries.remove(hash(token))!=null)saveOrRestore(before);
    }
    synchronized void revokeAll() throws IOException {
        if(entries.isEmpty())return;var before=new LinkedHashMap<>(entries);entries.clear();saveOrRestore(before);
    }
    private void saveOrRestore(Map<String,Entry> before) throws IOException {
        try{persist();}catch(IOException e){entries.clear();entries.putAll(before);throw e;}
    }
    private void persist() throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary=Files.createTempFile(file.getParent(),"admin-logins-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{
            byte[] bytes=json.writeValueAsBytes(new State(environmentHash,List.copyOf(entries.values())));
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
}
