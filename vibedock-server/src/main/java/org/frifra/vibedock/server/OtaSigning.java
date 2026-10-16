package org.frifra.vibedock.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Signs firmware for updates over Wi-Fi. The key pair (RSA 2048) is created once in the data directory; devices get
 * the public key with their pairing and install only firmware signed with it (SHA-256, PKCS#1 v1.5, the format of
 * the ESP8266 updater: firmware, signature, signature length as 32-bit little endian).
 */
@Service
class OtaSigning {
    private final Path privateFile,publicFile;private KeyPair keys;
    OtaSigning(@Value("${vibedock.data-dir}") String dataDir){
        Path dir=Path.of(dataDir);privateFile=dir.resolve("ota-signing.key");publicFile=dir.resolve("ota-signing.pub");
    }
    /** The public key as PEM, for the device. */
    synchronized String publicKeyPem(){
        String b64=Base64.getMimeEncoder(64,"\n".getBytes()).encodeToString(keys().getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n"+b64+"\n-----END PUBLIC KEY-----\n";
    }
    /** Bytes of a signature: the key's modulus length. */
    synchronized int signatureLength(){return (((java.security.interfaces.RSAPublicKey)keys().getPublic()).getModulus().bitLength()+7)/8;}
    /** The firmware with its signature appended, as the device's updater expects it. */
    synchronized byte[] signed(byte[] firmware){
        try{
            var signer=Signature.getInstance("SHA256withRSA");signer.initSign(keys().getPrivate());signer.update(firmware);
            byte[] signature=signer.sign(),out=new byte[firmware.length+signature.length+4];
            System.arraycopy(firmware,0,out,0,firmware.length);System.arraycopy(signature,0,out,firmware.length,signature.length);
            int n=signature.length,at=firmware.length+signature.length;
            out[at]=(byte)n;out[at+1]=(byte)(n>>8);out[at+2]=(byte)(n>>16);out[at+3]=(byte)(n>>24);
            return out;
        }catch(GeneralSecurityException e){throw new IllegalStateException("Signing unavailable",e);}
    }
    private KeyPair keys(){
        if(keys!=null)return keys;
        try{
            var factory=KeyFactory.getInstance("RSA");
            if(Files.isRegularFile(privateFile)&&Files.isRegularFile(publicFile)){
                keys=new KeyPair(factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicFile))),
                        factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privateFile))));
                return keys;
            }
            var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
            Files.createDirectories(privateFile.getParent());
            write(privateFile,pair.getPrivate().getEncoded());write(publicFile,pair.getPublic().getEncoded());
            return keys=pair;
        }catch(IOException|GeneralSecurityException e){throw new IllegalStateException("Firmware signing key unavailable",e);}
    }
    private static void write(Path file,byte[] bytes) throws IOException {
        Path temporary=Files.createTempFile(file.getParent(),"key-",".tmp");
        try{Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));Files.write(temporary,bytes);
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally{Files.deleteIfExists(temporary);}
    }
}
