package org.frifra.vibedock.server;

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
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Authenticated encryption of settings and CLI caches; plaintext CLI workspaces live on a RAM filesystem only. */
final class SecretStore {
    private static final String MAGIC="VIBEDOCK-SEALED-1\n";
    private static final byte[] PREFIX=MAGIC.getBytes(StandardCharsets.US_ASCII);
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
        boolean encrypted=false;for(Path file:files)if(sealed(bytes(file))){encrypted=true;break;}
        key=loadKey(encrypted);
        // Check every existing ciphertext before changing anything: a wrong key must never replace old data.
        for(Path file:files){byte[] b=bytes(file);if(sealed(b))Arrays.fill(open(file,b),(byte)0);}
        for(Path file:files)migrate(file);
    }
    private List<Path> files() throws IOException {
        var files=new ArrayList<Path>();Path settings=data.resolve("settings.json");
        if(Files.exists(settings,LinkOption.NOFOLLOW_LINKS))files.add(settings);
        Path auth=data.resolve("auth");
        if(Files.exists(auth,LinkOption.NOFOLLOW_LINKS)){
            if(Files.isSymbolicLink(auth))throw new IOException("Symbolic links are not supported in the credential store");
            try(var paths=Files.walk(auth)){
                for(Path p:paths.toList()){
                    if(Files.isSymbolicLink(p))throw new IOException("Symbolic links are not supported in the credential store");
                    if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))files.add(p);
                }
            }
        }
        return files;
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
        byte[] stored=bytes(file);
        if(sealed(stored))return open(file,stored);
        // Also cover an old CLI file installed while the server was running.
        write(file,stored);return stored;
    }
    private void migrate(Path file) throws IOException {
        byte[] plain=bytes(file);
        try{if(!sealed(plain))write(file,plain);}finally{Arrays.fill(plain,(byte)0);}
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
        try{
            byte[] nonce=new byte[12];random.nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(file));
            byte[] encrypted=cipher.doFinal(plain),encoded=Base64.getEncoder().encode(ByteBuffer.allocate(12+encrypted.length).put(nonce).put(encrypted).array());
            byte[] stored=ByteBuffer.allocate(PREFIX.length+encoded.length).put(PREFIX).put(encoded).array();
            // Verify the complete new ciphertext before atomically replacing a legacy or current file.
            byte[] verified=open(file,stored);try{if(!Arrays.equals(plain,verified))throw new IOException("Credential encryption verification failed");}finally{Arrays.fill(verified,(byte)0);}
            atomic(file,stored);
        }catch(GeneralSecurityException e){throw new IOException("Token encryption unavailable",e);}
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
                try(var paths=Files.walk(original)){
                    for(Path p:paths.toList()){
                        if(Files.isSymbolicLink(p))throw new IOException("Symbolic links are not supported in sign-in folders");
                        Path dest=path.resolve(original.relativize(p));
                        if(Files.isDirectory(p))Files.createDirectories(dest);
                        else {byte[] clear=read(p);try{Files.write(dest,clear,StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(dest,PosixFilePermissions.fromString("rw-------"));}finally{Arrays.fill(clear,(byte)0);}}
                    }
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
            try(var files=Files.walk(path)){files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).forEach(p->initial.add(path.relativize(p)));}
        }
        synchronized void commit() throws IOException {
            try(var paths=Files.walk(path)){
                for(Path p:paths.filter(p->!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).toList()){
                    if(Files.isSymbolicLink(p))throw new IOException("CLI produced a symbolic link in its sign-in folder");
                    byte[] clear=bytes(p);try{write(original.resolve(path.relativize(p)),clear);}finally{Arrays.fill(clear,(byte)0);}
                }
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
