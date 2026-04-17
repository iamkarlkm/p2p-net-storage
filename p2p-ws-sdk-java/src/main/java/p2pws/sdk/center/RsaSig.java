package p2pws.sdk.center;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

public final class RsaSig {

    private RsaSig() {
    }

    public static boolean verifySha256WithRsa(byte[] pubkeySpkiDer, byte[] data, byte[] signature) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(pubkeySpkiDer));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(pub);
            s.update(data);
            return s.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }
}

