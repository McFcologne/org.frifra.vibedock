package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Encrypts secret JSON fields, keeping configuration and metadata readable; CLI plaintext stays in RAM. */
final class SecretStore {
    private static final String MAGIC="VIBEDOCK-SEALED-1\n";
    private static final byte[] PREFIX=MAGIC.getBytes(StandardCharsets.US_ASCII);
    static final String FIELD_PREFIX="vibedock:sealed:v1:";
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> SECRET_NAMES=Set.of("token","accesstoken","refreshtoken","idtoken","apikey","openaikey","openaiapikey","anthropicapikey","authtoken","clientsecret","password");
    private static final long MAX_FILE=32L*1024*1024;
    private final Path data,keyFile,runtime;
    private final boolean checkRam;
    private final byte[] key;
    private final SecureRandom random=new SecureRandom();
    private final Set<Path> active=ConcurrentHashMap.newKeySet();

    SecretStore(Path data,String keyFile,String runtime) throws IOException {
        this(data,keyFile,runtime,true);
    }
    /** Tests can supply an isolated temporary workspace without mounting a RAM disk. */
    SecretStore(Path data,String keyFile,String runtime,boolean checkRam) throws IOException {
        this.data=data.toAbsolutePath().normalize();
        this.keyFile=(keyFile.isBlank()?this.data.resolve(".installation-id"):Path.of(keyFile)).toAbsolutePath().normalize();
        this.runtime=Path.of(runtime.isBlank()?"/dev/shm":runtime).toAbsolutePath().normalize();this.checkRam=checkRam;
        if(this.runtime.startsWith(this.data))throw new IOException("AUTH_RUNTIME_DIR must be outside DATA_DIR");
        var files=files();
        boolean encrypted=false;for(Path file:files){byte[] b=bytes(file);if(sealed(b)||new String(b,StandardCharsets.UTF_8).contains(FIELD_PREFIX)){encrypted=true;break;}}
        key=loadKey(encrypted);
        // Check every existing ciphertext before changing anything: a wrong key must never replace old data.
        for(Path file:files){byte[] clear=clear(file,bytes(file));Arrays.fill(clear,(byte)0);}
        for(Path file:files)migrate(file);
    }
    private List<Path> files() throws IOException {
        var files=new ArrayList<Path>();Path settings=data.resolve("settings.json");
        if(Files.exists(settings,LinkOption.NOFOLLOW_LINKS))files.add(settings);
        Path auth=data.resolve("auth");
        if(Files.exists(auth,LinkOption.NOFOLLOW_LINKS)){
            for(Path p:entries(auth,true))if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))files.add(p);
        }
        return files;
    }
    /** Codex creates executable links under each account's tmp/arg0. They are disposable launcher files, not credentials. */
    private boolean launcherScratch(Path root,Path path,boolean authRoot){
        Path relative=root.relativize(path);int prefix=0;
        if(authRoot)prefix=relative.getNameCount()>0&&relative.getName(0).toString().equals("accounts")?2:1;
        return relative.getNameCount()>=prefix+2&&relative.getName(prefix).toString().equals("tmp")
                &&relative.getName(prefix+1).toString().equals("arg0");
    }
    /** Never follow links; skip launcher scratch trees before visiting their contents. */
    private List<Path> entries(Path root,boolean authRoot) throws IOException {
        var result=new ArrayList<Path>();
        Files.walkFileTree(root,new SimpleFileVisitor<>(){
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs){
                if(launcherScratch(root,dir,authRoot))return FileVisitResult.SKIP_SUBTREE;
                result.add(dir);return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException {
                if(launcherScratch(root,file,authRoot))return FileVisitResult.CONTINUE;
                if(attrs.isSymbolicLink())throw new IOException("Symbolic links are not supported in credential files: "+data.relativize(file.toAbsolutePath().normalize()));
                if(attrs.isRegularFile())result.add(file);return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }
    private byte[] loadKey(boolean required) throws IOException {
        if(!Files.exists(keyFile,LinkOption.NOFOLLOW_LINKS)){
            if(required)throw new IOException("Private installation ID is missing; restore .installation-id. Existing credentials were not changed.");
            Files.createDirectories(keyFile.getParent());
            // A lock also prevents two first starts from generating different keys.
            try(var lockChannel=FileChannel.open(keyFile.resolveSibling(".installation-id.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lock=lockChannel.lock()){
                if(!Files.exists(keyFile,LinkOption.NOFOLLOW_LINKS)){
                    atomic(keyFile,UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
                }
            }
        }
        if(Files.isSymbolicLink(keyFile)||!Files.isRegularFile(keyFile,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid private installation ID file");
        Files.setPosixFilePermissions(keyFile,PosixFilePermissions.fromString("rw-------"));
        try{
            String id=Files.readString(keyFile).strip();UUID.fromString(id);
            // HKDF-SHA256: domain separation, not an additional secret. The private installation ID is the root.
            var mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("VibeDock credential storage v1".getBytes(StandardCharsets.US_ASCII),"HmacSHA256"));
            byte[] prk=mac.doFinal(id.getBytes(StandardCharsets.US_ASCII));
            try{mac.init(new SecretKeySpec(prk,"HmacSHA256"));return mac.doFinal("AES-256-GCM\u0001".getBytes(StandardCharsets.US_ASCII));}
            finally{Arrays.fill(prk,(byte)0);}
        }catch(IllegalArgumentException|GeneralSecurityException e){throw new IOException("Invalid private installation ID or unavailable key derivation");}
    }
    static boolean sealed(byte[] bytes){return bytes.length>=PREFIX.length&&Arrays.equals(PREFIX,Arrays.copyOf(bytes,PREFIX.length));}
    private byte[] bytes(Path file) throws IOException {
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_FILE)
            throw new IOException("Invalid or oversized credential file");
        try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] b=in.readNBytes((int)MAX_FILE+1);if(b.length>MAX_FILE)throw new IOException("Credential file too large");return b;}
    }
    synchronized byte[] read(Path file) throws IOException {
        byte[] stored=bytes(file),plain=clear(file,stored);
        if(sealed(stored)||needsMigration(file,stored))write(file,plain);
        return plain;
    }
    private void migrate(Path file) throws IOException {
        byte[] stored=bytes(file);
        if(!sealed(stored)&&!needsMigration(file,stored))return;
        byte[] plain=clear(file,stored);
        try{write(file,plain);}finally{Arrays.fill(plain,(byte)0);}
    }
    private JsonNode tree(byte[] bytes){
        try{return JSON.readTree(bytes);}catch(IOException e){return null;}
    }
    private boolean secret(Path file,String name){
        String normalized=name.replace("_","").replace("-","").toLowerCase(Locale.ROOT);
        return SECRET_NAMES.contains(normalized)||(name.equals("key")&&file.getFileName().toString().equals("auth.json"));
    }
    private boolean needsMigration(Path file,byte[] bytes){return plainSecrets(file,tree(bytes));}
    private boolean plainSecrets(Path file,JsonNode node){
        if(node==null)return false;
        if(node.isObject()){
            var fields=node.fields();while(fields.hasNext()){
                var field=fields.next();var value=field.getValue();
                if(secret(file,field.getKey())&&value.isTextual()&&!value.textValue().isBlank()&&!value.textValue().startsWith(FIELD_PREFIX))return true;
                if(plainSecrets(file,value))return true;
            }
        }else if(node.isArray())for(var value:node)if(plainSecrets(file,value))return true;
        return false;
    }
    private byte[] clear(Path file,byte[] stored) throws IOException {
        byte[] plain=sealed(stored)?open(file,stored):stored;
        if(!new String(plain,StandardCharsets.UTF_8).contains(FIELD_PREFIX))return plain.clone();
        JsonNode node=tree(plain);
        if(node==null)throw new IOException("Invalid JSON containing encrypted credential fields");
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(fields(file,node,"",false,false));
    }
    private JsonNode fields(Path file,JsonNode node,String pointer,boolean encrypt,boolean protectedField) throws IOException {
        if(node.isObject()){
            var result=(ObjectNode)node.deepCopy();var names=node.fieldNames();
            while(names.hasNext()){
                String name=names.next(),part=name.replace("~","~0").replace("/","~1");
                result.set(name,fields(file,node.get(name),pointer+"/"+part,encrypt,secret(file,name)));
            }
            return result;
        }
        if(node.isArray()){
            var result=(ArrayNode)node.deepCopy();for(int i=0;i<node.size();i++)result.set(i,fields(file,node.get(i),pointer+"/"+i,encrypt,false));return result;
        }
        if(!node.isTextual())return node;
        String value=node.textValue();
        if(value.startsWith(FIELD_PREFIX)){
            String decoded=openField(file,pointer,value);return encrypt?node:TextNode.valueOf(decoded);
        }
        return encrypt&&protectedField&&!value.isBlank()?TextNode.valueOf(sealField(file,pointer,value)):node;
    }
    private byte[] fieldAad(Path file,String pointer) throws IOException {
        return (new String(aad(file),StandardCharsets.UTF_8)+"\nfield:"+pointer).getBytes(StandardCharsets.UTF_8);
    }
    private String openField(Path file,String pointer,String value) throws IOException {
        try{
            byte[] encoded=Base64.getDecoder().decode(value.substring(FIELD_PREFIX.length()));if(encoded.length<28)throw new IllegalArgumentException();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,encoded,0,12));
            cipher.updateAAD(fieldAad(file,pointer));byte[] plain=cipher.doFinal(encoded,12,encoded.length-12);
            try{return new String(plain,StandardCharsets.UTF_8);}finally{Arrays.fill(plain,(byte)0);}
        }catch(GeneralSecurityException|IllegalArgumentException e){throw new IOException("Cannot decrypt credential field: wrong installation ID, moved field or damaged value. Existing data was not changed.");}
    }
    private String sealField(Path file,String pointer,String value) throws IOException {
        try{
            byte[] nonce=new byte[12];random.nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(fieldAad(file,pointer));
            byte[] plain=value.getBytes(StandardCharsets.UTF_8),encrypted;
            try{encrypted=cipher.doFinal(plain);}finally{Arrays.fill(plain,(byte)0);}
            String stored=FIELD_PREFIX+Base64.getEncoder().encodeToString(ByteBuffer.allocate(12+encrypted.length).put(nonce).put(encrypted).array());
            if(!value.equals(openField(file,pointer,stored)))throw new IOException("Credential field encryption verification failed");return stored;
        }catch(GeneralSecurityException e){throw new IOException("Token encryption unavailable",e);}
    }
    private byte[] aad(Path file) throws IOException {
        Path path=file.toAbsolutePath().normalize();
        if(!path.startsWith(data))throw new IOException("Credential path outside DATA_DIR");
        return (MAGIC+data.relativize(path).toString().replace('\\','/')).getBytes(StandardCharsets.UTF_8);
    }
    private byte[] open(Path file,byte[] stored) throws IOException {
        try{
            byte[] encoded=Base64.getDecoder().decode(Arrays.copyOfRange(stored,PREFIX.length,stored.length));
            if(encoded.length<28)throw new IllegalArgumentException();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,encoded,0,12));cipher.updateAAD(aad(file));
            return cipher.doFinal(encoded,12,encoded.length-12);
        }catch(GeneralSecurityException|IllegalArgumentException e){throw new IOException("Cannot decrypt stored credentials: wrong key or damaged file. Existing data was not changed.");}
    }
    synchronized void write(Path file,byte[] plain) throws IOException {
        // Non-secret fields and non-JSON auxiliary files stay readable and are never wrapped as a whole file.
        JsonNode node=tree(plain);byte[] stored=plain;
        if(node!=null&&plainSecrets(file,node))stored=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(fields(file,node,"",true,false));
        else if(node!=null&&new String(plain,StandardCharsets.UTF_8).contains(FIELD_PREFIX))fields(file,node,"",false,false);
        atomic(file,stored);
    }
    private static void atomic(Path target,byte[] bytes) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary=Files.createTempFile(target.getParent(),"sealed-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
    Workspace workspace(Path source) throws IOException {
        Path original=source.toAbsolutePath().normalize();
        if(!original.startsWith(data.resolve("auth")))throw new IOException("Invalid sign-in folder");
        if(!active.add(original))throw new IOException("A sign-in operation is already running for this source");
        Path path=null;FileChannel lockChannel=null;FileLock lock=null;
        try{
            Path locks=data.resolve(".auth-locks");Files.createDirectories(locks);
            String name=UUID.nameUUIDFromBytes(aad(original)).toString()+".lock";
            lockChannel=FileChannel.open(locks.resolve(name),Set.of(StandardOpenOption.CREATE,StandardOpenOption.WRITE),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try{lock=lockChannel.tryLock();}catch(java.nio.channels.OverlappingFileLockException e){throw new IOException("A sign-in operation is already running for this source");}
            if(lock==null)throw new IOException("A sign-in operation is already running for this source");
            if(!Files.isDirectory(runtime))throw new IOException("No RAM credential workspace available; set AUTH_RUNTIME_DIR to a RAM filesystem");
            if(checkRam&&!Set.of("tmpfs","ramfs").contains(Files.getFileStore(runtime).type()))throw new IOException("AUTH_RUNTIME_DIR must be a RAM filesystem (tmpfs or ramfs)");
            path=Files.createTempDirectory(runtime,"vibedock-auth-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            if(Files.exists(original,LinkOption.NOFOLLOW_LINKS)){
                for(Path p:entries(original,false)){
                    Path dest=path.resolve(original.relativize(p));
                    if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))Files.createDirectories(dest);
                    else {byte[] clear=read(p);try{Files.write(dest,clear,StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(dest,PosixFilePermissions.fromString("rw-------"));}finally{Arrays.fill(clear,(byte)0);}}
                }
            }
            return new Workspace(original,path,lockChannel,lock);
        }catch(IOException|RuntimeException e){
            active.remove(original);if(path!=null)delete(path);if(lock!=null)lock.release();if(lockChannel!=null)lockChannel.close();throw e;
        }
    }
    final class Workspace implements AutoCloseable {
        final Path original,path;
        private boolean closed;
        private final FileChannel lockChannel;private final FileLock lock;
        private final Set<Path> initial=new HashSet<>();
        Workspace(Path original,Path path,FileChannel lockChannel,FileLock lock) throws IOException {
            this.original=original;this.path=path;this.lockChannel=lockChannel;this.lock=lock;
            for(Path p:entries(path,false))if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))initial.add(path.relativize(p));
        }
        synchronized void commit() throws IOException {
            for(Path p:entries(path,false))if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)){
                byte[] clear=bytes(p);try{write(original.resolve(path.relativize(p)),clear);}finally{Arrays.fill(clear,(byte)0);}
            }
            for(Path relative:initial)if(!Files.exists(path.resolve(relative),LinkOption.NOFOLLOW_LINKS))Files.deleteIfExists(original.resolve(relative));
        }
        @Override public synchronized void close() throws IOException {
            if(closed)return;
            try{commit();delete(path);}finally{closed=true;active.remove(original);try{if(lock.isValid())lock.release();}finally{lockChannel.close();}}
        }
    }
    private static void delete(Path root) throws IOException {
        try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
}
