package p2pws.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

public final class KeyId {

    private KeyId() {
    }

    public static byte[] sha256(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

