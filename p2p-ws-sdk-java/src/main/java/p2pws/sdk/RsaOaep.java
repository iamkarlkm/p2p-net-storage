package p2pws.sdk;

import java.security.PrivateKey;
import java.security.PublicKey;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.security.spec.MGF1ParameterSpec;

public final class RsaOaep {

    private RsaOaep() {
    }

    public static byte[] decryptSha256(PrivateKey privateKey, byte[] cipherText) {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
            OAEPParameterSpec spec = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            c.init(Cipher.DECRYPT_MODE, privateKey, spec);
            return c.doFinal(cipherText);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] encryptSha256(PublicKey publicKey, byte[] plainText) {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
            OAEPParameterSpec spec = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            c.init(Cipher.ENCRYPT_MODE, publicKey, spec);
            return c.doFinal(plainText);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
