package javax.net.p2p.auth.utils;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.InvalidPathException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import javax.net.p2p.utils.RSAUtils;
import javax.crypto.Cipher;
import org.apache.commons.codec.binary.Base64;

public final class AuthCrypto {

    private static final SecureRandom RNG = new SecureRandom();

    private AuthCrypto() {
    }

    public static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        RNG.nextBytes(b);
        return b;
    }

    public static byte[] signSha256Rsa(byte[] data, String privateKeyBase64OrPath) throws Exception {
        PrivateKey privateKey = loadPrivateKey(privateKeyBase64OrPath);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(data);
        return signature.sign();
    }

    public static boolean verifySha256Rsa(byte[] data, String publicKeyBase64OrPath, byte[] sig) throws Exception {
        if (sig == null) {
            return false;
        }
        PublicKey publicKey = loadPublicKey(publicKeyBase64OrPath);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        signature.update(data);
        return signature.verify(sig);
    }

    public static byte[] rsaEncryptWithPublic(byte[] data, String publicKeyBase64OrPath) throws Exception {
        String key = readKeyMaybeFile(publicKeyBase64OrPath);
        return RSAUtils.encryptByPublicKey(data, key);
    }

    public static byte[] rsaDecryptWithPrivate(byte[] data, String privateKeyBase64OrPath) throws Exception {
        String key = readKeyMaybeFile(privateKeyBase64OrPath);
        return RSAUtils.decryptByPrivateKey(data, key);
    }

    public static byte[] rsaEncryptLargeWithPrivate(byte[] data, String privateKeyBase64OrPath) throws Exception {
        PrivateKey key = loadPrivateKey(privateKeyBase64OrPath);
        RSAPrivateKey rsa = (RSAPrivateKey) key;
        int keySizeBytes = (rsa.getModulus().bitLength() + 7) / 8;
        int maxPlain = keySizeBytes - 11;
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return rsaProcessLarge(cipher, data, maxPlain);
    }

    public static byte[] rsaDecryptLargeWithPublic(byte[] encrypted, String publicKeyBase64OrPath) throws Exception {
        PublicKey key = loadPublicKey(publicKeyBase64OrPath);
        RSAPublicKey rsa = (RSAPublicKey) key;
        int keySizeBytes = (rsa.getModulus().bitLength() + 7) / 8;
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, key);
        return rsaProcessLarge(cipher, encrypted, keySizeBytes);
    }

    private static byte[] rsaProcessLarge(Cipher cipher, byte[] data, int blockSize) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        while (offset < data.length) {
            int n = Math.min(blockSize, data.length - offset);
            byte[] block = cipher.doFinal(data, offset, n);
            out.write(block);
            offset += n;
        }
        return out.toByteArray();
    }

    public static byte[] deriveXorKey(byte[] seed, String userId, byte[] nonce, int outLen) throws Exception {
        if (outLen <= 0) {
            throw new IllegalArgumentException("outLen must be > 0");
        }
        byte[] user = userId == null ? new byte[0] : userId.getBytes(StandardCharsets.UTF_8);
        byte[] salt = nonce == null ? new byte[0] : nonce;
        byte[] material = new byte[seed.length + user.length + salt.length];
        System.arraycopy(seed, 0, material, 0, seed.length);
        System.arraycopy(user, 0, material, seed.length, user.length);
        System.arraycopy(salt, 0, material, seed.length + user.length, salt.length);

        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] out = new byte[outLen];
        int pos = 0;
        int counter = 0;
        while (pos < outLen) {
            md.reset();
            md.update(material);
            md.update((byte) (counter));
            md.update((byte) (counter >>> 8));
            md.update((byte) (counter >>> 16));
            md.update((byte) (counter >>> 24));
            byte[] block = md.digest();
            int n = Math.min(block.length, outLen - pos);
            System.arraycopy(block, 0, out, pos, n);
            pos += n;
            counter++;
        }
        Arrays.fill(material, (byte) 0);
        return out;
    }

    public static void xorInPlace(byte[] data, byte[] key) {
        if (data == null || key == null || key.length == 0) {
            return;
        }
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (data[i] ^ key[i % key.length]);
        }
    }

    private static PublicKey loadPublicKey(String base64OrPath) throws Exception {
        byte[] keyBytes = Base64.decodeBase64(readKeyMaybeFile(base64OrPath));
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(keySpec);
    }

    private static PrivateKey loadPrivateKey(String base64OrPath) throws Exception {
        byte[] keyBytes = Base64.decodeBase64(readKeyMaybeFile(base64OrPath));
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(keySpec);
    }

    private static String readKeyMaybeFile(String base64OrPath) throws Exception {
        if (base64OrPath == null) {
            throw new IllegalArgumentException("key is null");
        }
        String s = base64OrPath.trim();
        if (s.startsWith("file:")) {
            s = s.substring("file:".length());
        }
        s = s.trim();
        if (s.startsWith("inline:")) {
            return s.substring("inline:".length()).trim();
        }
        if (s.isBlank()) {
            return s;
        }
        if (looksLikeInlineKey(s)) {
            return s;
        }

        Path p;
        try {
            p = Paths.get(s);
        } catch (InvalidPathException e) {
            return s;
        }
        if (p.isAbsolute()) {
            throw new IllegalArgumentException("absolute key path is not allowed");
        }
        String base = System.getProperty("p2p.key.dir", ".");
        Path baseDir = Paths.get(base).toAbsolutePath().normalize();
        Path resolved = baseDir.resolve(p).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("invalid key path");
        }
        java.io.File f = resolved.toFile();
        if (f.exists() && f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = in.readAllBytes();
                return new String(buf, StandardCharsets.UTF_8).trim();
            }
        }
        return s;
    }

    private static boolean looksLikeInlineKey(String s) {
        if (s.length() > 200) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ') {
                return true;
            }
        }
        if (s.indexOf('+') >= 0 || s.indexOf('=') >= 0) {
            return true;
        }
        if (s.startsWith("-----BEGIN")) {
            return true;
        }
        return false;
    }
}
