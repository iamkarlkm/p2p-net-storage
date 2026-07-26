package javax.net.p2p.auth.model;

public final class HandshakeCryptoMode {
    public static final int CLIENT_RANDOM = 0;
    public static final int SERVER_RANDOM = 1;
    public static final int KEYFILE = 2;
    public static final int PLAIN = 3;

    private HandshakeCryptoMode() {
    }

    public static int parse(String s) {
        if (s == null || s.isBlank()) {
            return CLIENT_RANDOM;
        }
        String v = s.trim();
        if ("CLIENT_RANDOM".equalsIgnoreCase(v) || "CLIENT_RANDOM_XOR_RSA_OAEP".equalsIgnoreCase(v)) {
            return CLIENT_RANDOM;
        }
        if ("SERVER_RANDOM".equalsIgnoreCase(v) || "SERVER_RANDOM_XOR_RSA_OAEP".equalsIgnoreCase(v)) {
            return SERVER_RANDOM;
        }
        if ("KEYFILE".equalsIgnoreCase(v) || "KEYFILE_XOR_RSA_OAEP".equalsIgnoreCase(v)) {
            return KEYFILE;
        }
        if ("PLAIN".equalsIgnoreCase(v)) {
            return PLAIN;
        }
        throw new IllegalArgumentException("unknown cryptoMode: " + s);
    }
}
