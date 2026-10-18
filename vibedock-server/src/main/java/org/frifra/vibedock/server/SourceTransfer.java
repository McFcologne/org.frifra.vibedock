package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;

/**
 * Export and import of the data sources including their credentials (manual tokens and the browser sign-in file of
 * each source), encrypted with a password: PBKDF2-HMAC-SHA256 derives an AES-256-GCM key. Only the credential file
 * the server itself reads is carried, no other content of the sign-in folders.
 */
@Service
class SourceTransfer {
    static final String FORMAT="vibedock-sources";
    // Exports from the project's earlier name still import; the format is part of the encryption (AAD).
    private static final String EARLIER_FORMAT="vibetv-sources";
    static final int VERSION=1,ITERATIONS=600_000,MIN_PASSWORD=10,MAX_FILE=65536,MAX_EXPORT=1_048_576;
    /** The file on disk; all binary fields are base64. */
    record Envelope(String format,int version,String kdf,int iterations,String salt,String cipher,String nonce,String data) {}
    /** The decrypted content: the sources and per source id the content of its credential file (base64). */
    record Bundle(List<SettingsStore.Account> accounts,Map<String,String> files) {
        Bundle { accounts=List.copyOf(accounts); files=Map.copyOf(files); }
        boolean signedIn(SettingsStore.Account a){return files.containsKey(a.id())||!a.manual().token().isBlank();}
    }
    private final SettingsStore store;private final ObjectMapper json;private final SecureRandom random=new SecureRandom();
    SourceTransfer(SettingsStore store,ObjectMapper json){this.store=store;this.json=json;}

    byte[] export(String password) throws IOException {return export(password,FORMAT);}
    /** With the format of an earlier export (tests of the import of earlier exports). */
    byte[] export(String password,String format) throws IOException {
        if(password==null||password.length()<MIN_PASSWORD)throw new IllegalArgumentException("The password needs at least "+MIN_PASSWORD+" characters");
        var accounts=store.get().accounts();Map<String,String> files=new LinkedHashMap<>();
        for(var account:accounts){
            String name=account.credentialFile();if(name==null)continue;
            Path file=store.authDirectory(account).resolve(name);
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))continue;
            byte[] bytes=store.secrets.read(file);
            if(bytes.length<=MAX_FILE)files.put(account.id(),Base64.getEncoder().encodeToString(bytes));
        }
        byte[] salt=new byte[16],nonce=new byte[12];random.nextBytes(salt);random.nextBytes(nonce);
        byte[] plain=json.writeValueAsBytes(new Bundle(accounts,files));
        try{
            byte[] data=cipher(Cipher.ENCRYPT_MODE,format,password,salt,ITERATIONS,nonce).doFinal(plain);
            var b64=Base64.getEncoder();
            return json.writerWithDefaultPrettyPrinter().writeValueAsBytes(new Envelope(format,VERSION,"PBKDF2-HMAC-SHA256",ITERATIONS,
                    b64.encodeToString(salt),"AES-256-GCM",b64.encodeToString(nonce),b64.encodeToString(data)));
        }catch(GeneralSecurityException e){throw new IllegalStateException("Encryption unavailable",e);}
        finally{Arrays.fill(plain,(byte)0);}
    }

    /** Decrypts and checks an export; nothing is changed yet. */
    Bundle read(String content,String password) {
        if(content==null||content.isBlank())throw new IllegalArgumentException("Choose an export file");
        if(content.length()>MAX_EXPORT)throw new IllegalArgumentException("The file is too large for a VibeDock export");
        Envelope envelope;
        try{envelope=json.readValue(content,Envelope.class);}catch(IOException e){throw new IllegalArgumentException("This is not a VibeDock data source export");}
        if(!(FORMAT.equals(envelope.format())||EARLIER_FORMAT.equals(envelope.format()))||envelope.version()!=VERSION||!"AES-256-GCM".equals(envelope.cipher())||!"PBKDF2-HMAC-SHA256".equals(envelope.kdf()))
            throw new IllegalArgumentException("This is not a VibeDock data source export of a supported version");
        if(envelope.iterations()<100_000||envelope.iterations()>5_000_000)throw new IllegalArgumentException("The export uses unsupported key settings");
        byte[] plain=null;
        try{
            var b64=Base64.getDecoder();byte[] salt=b64.decode(envelope.salt()),nonce=b64.decode(envelope.nonce());
            if(salt.length!=16||nonce.length!=12)throw new IllegalArgumentException("The export is damaged");
            plain=cipher(Cipher.DECRYPT_MODE,envelope.format(),password==null?"":password,salt,envelope.iterations(),nonce).doFinal(b64.decode(envelope.data()));
            var bundle=json.readValue(plain,Bundle.class);
            if(bundle.accounts().isEmpty())throw new IllegalArgumentException("The export contains no data source");
            SettingsStore.validate(store.get().withAccounts(bundle.accounts()));
            for(var entry:bundle.files().entrySet()){
                var account=bundle.accounts().stream().filter(a->a.id().equals(entry.getKey())).findFirst();
                if(account.isEmpty()||account.get().credentialFile()==null||b64.decode(entry.getValue()).length>MAX_FILE)
                    throw new IllegalArgumentException("The export is damaged");
            }
            return bundle;
        }catch(AEADBadTagException e){throw new IllegalArgumentException("Wrong password or damaged file");}
        catch(GeneralSecurityException|IOException|NullPointerException e){throw new IllegalArgumentException("The export is damaged");}
        finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }

    /**
     * Applies the chosen sources of an export. replace: they become the only sources, with their ids, so designs of
     * the exporting server keep working; all other sources and their sign-ins are removed. Otherwise they are added;
     * an id that already exists gets a new one, and every added source gets its own sign-in folder.
     */
    synchronized List<SettingsStore.Account> apply(Bundle bundle,Set<String> ids,boolean replace) throws IOException {
        var chosen=bundle.accounts().stream().filter(a->ids.contains(a.id())).toList();
        if(chosen.isEmpty())throw new IllegalArgumentException("Choose at least one data source");
        var old=store.get();Map<SettingsStore.Account,String> content=new LinkedHashMap<>();List<SettingsStore.Account> next=new ArrayList<>();
        if(!replace)next.addAll(old.accounts());
        for(var account:chosen){
            var target=account;
            if(!replace){
                String id=old.account(account.id()).isPresent()?UUID.randomUUID().toString():account.id();
                target=new SettingsStore.Account(id,account.type(),account.label(),account.manual(),"accounts/"+id,account.suspended());
            }
            next.add(target);
            if(bundle.files().containsKey(account.id()))content.put(target,bundle.files().get(account.id()));
        }
        var settings=old.withAccounts(next);SettingsStore.validate(settings);
        // Replacing moves the current sign-ins aside first; they are deleted only once everything is saved and are
        // put back if anything fails, so a failed import never leaves the old sources without their credentials.
        Path aside=null;Map<Path,Path> moved=new LinkedHashMap<>();List<Path> written=new ArrayList<>(),created=new ArrayList<>();
        String transaction=UUID.randomUUID().toString();
        try{
            if(replace){
                var present=old.accounts().stream().map(this::signIn).filter(p->Files.exists(p,LinkOption.NOFOLLOW_LINKS)).distinct().toList();
                if(!present.isEmpty()){
                    aside=store.authRoot().resolve(ASIDE+UUID.randomUUID());
                    Files.createDirectories(aside);Files.setPosixFilePermissions(aside,PosixFilePermissions.fromString("rwx------"));
                    // What a restart needs if the process ends half-way: where each parked folder belongs, and the
                    // import's id, which settings.json carries once the import is saved (recoverReplacements).
                    var manifest=json.createObjectNode();manifest.put("transaction",transaction);
                    var places=manifest.putArray("moved");present.forEach(p->places.add(store.authRoot().relativize(p).toString()));
                    Files.write(aside.resolve(MANIFEST),json.writeValueAsBytes(manifest));
                    int n=0;
                    for(Path signIn:present){Path parked=aside.resolve(String.valueOf(n++));Files.move(signIn,parked,StandardCopyOption.ATOMIC_MOVE);moved.put(signIn,parked);}
                }
            }
            for(var entry:content.entrySet()){
                // A sign-in that did not exist before is removed completely on failure, folder included.
                Path signIn=signIn(entry.getKey());if(!Files.exists(signIn,LinkOption.NOFOLLOW_LINKS))created.add(signIn);
                written.add(write(entry.getKey(),Base64.getDecoder().decode(entry.getValue())));
            }
            store.save(settings,transaction);
        }catch(IOException|RuntimeException e){
            for(Path file:written)Files.deleteIfExists(file);
            for(Path signIn:created)deleteTree(signIn);
            for(var entry:moved.entrySet()){
                if(Files.isDirectory(entry.getKey(),LinkOption.NOFOLLOW_LINKS))deleteTree(entry.getKey());
                Files.move(entry.getValue(),entry.getKey(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }
            if(aside!=null)deleteTree(aside);
            throw e;
        }
        if(aside!=null)deleteTree(aside);
        return next;
    }
    static final String ASIDE=".replaced-",MANIFEST="manifest.json";
    /**
     * Finishes a replacing import that a stopped process left half-way, before the credential store reads auth/: the
     * parked sign-ins are encrypted for their own place and cannot be read where they are parked. When settings.json
     * carries the import's id, the import was saved and the parked sign-ins are removed; otherwise they go back to
     * their places, replacing what the import had written there. The source ids cannot tell: an import of the
     * server's own export has the same ones before and after.
     */
    static void recoverReplacements(Path data) throws IOException {
        Path auth=data.resolve("auth");
        if(!Files.isDirectory(auth,LinkOption.NOFOLLOW_LINKS))return;
        List<Path> asides;
        try(var list=Files.list(auth)){asides=list.filter(p->p.getFileName().toString().startsWith(ASIDE)&&Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).toList();}
        var json=new ObjectMapper();
        for(Path aside:asides){
            Path manifest=aside.resolve(MANIFEST);
            if(Files.isRegularFile(manifest,LinkOption.NOFOLLOW_LINKS)){
                var plan=json.readTree(manifest.toFile());String transaction=plan.path("transaction").asText("");
                Path settings=data.resolve("settings.json");
                boolean saved=!transaction.isEmpty()&&Files.isRegularFile(settings,LinkOption.NOFOLLOW_LINKS)
                        &&transaction.equals(json.readTree(settings.toFile()).path(SettingsStore.IMPORT_TRANSACTION).asText());
                if(!saved){
                    int n=0;
                    for(var place:plan.path("moved")){
                        Path parked=aside.resolve(String.valueOf(n++)),target=auth.resolve(place.asText()).normalize();
                        if(!target.startsWith(auth)||target.equals(auth))throw new IOException("Invalid place in "+data.relativize(manifest));
                        if(!Files.exists(parked,LinkOption.NOFOLLOW_LINKS))continue;
                        deleteTree(target);Files.createDirectories(target.getParent());
                        Files.move(parked,target,StandardCopyOption.ATOMIC_MOVE);
                    }
                }
            }
            // Without a manifest nothing had been moved yet.
            deleteTree(aside);
        }
    }
    /** What a replacement removes: the whole sign-in folder, or only the credential file of an earlier default source. */
    private Path signIn(SettingsStore.Account account){
        Path dir=store.authDirectory(account);
        return account.folder().equals(account.type())&&account.credentialFile()!=null?dir.resolve(account.credentialFile()):dir;
    }
    private static void deleteTree(Path root) throws IOException {
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        try(var paths=Files.walk(root)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    private Path write(SettingsStore.Account account,byte[] bytes) throws IOException {
        Path dir=store.authDirectory(account);
        for(Path d:List.of(dir.getParent().getParent(),dir.getParent(),dir)){
            if(Files.isDirectory(d,LinkOption.NOFOLLOW_LINKS))continue;
            Files.createDirectories(d);Files.setPosixFilePermissions(d,PosixFilePermissions.fromString("rwx------"));
        }
        Path target=dir.resolve(account.credentialFile());
        if(!Files.isDirectory(target.getParent(),LinkOption.NOFOLLOW_LINKS)){
            Files.createDirectories(target.getParent());Files.setPosixFilePermissions(target.getParent(),PosixFilePermissions.fromString("rwx------"));
        }
        if(account.type().equals("gemini"))SignInRenewal.prepareGemini(dir);
        try{store.secrets.write(target,bytes);return target;}finally{Arrays.fill(bytes,(byte)0);}
    }

    /** AES-256-GCM with a PBKDF2 key from the password; the format and version are the associated data (also SupportFile). */
    static Cipher cipher(int mode,String format,String password,byte[] salt,int iterations,byte[] nonce) throws GeneralSecurityException {
        var spec=new PBEKeySpec(password.toCharArray(),salt,iterations,256);
        try{
            byte[] key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD((format+"/"+VERSION).getBytes(StandardCharsets.UTF_8));
            Arrays.fill(key,(byte)0);
            return cipher;
        }finally{spec.clearPassword();}
    }
}
