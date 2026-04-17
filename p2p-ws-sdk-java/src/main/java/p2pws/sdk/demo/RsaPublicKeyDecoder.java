package p2pws.sdk.demo;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

public final class RsaPublicKeyDecoder {

    private RsaPublicKeyDecoder() {
    }

    public static PublicKey fromSpkiDer(byte[] der) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

