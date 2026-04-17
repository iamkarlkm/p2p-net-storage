package p2pws.sdk.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

public final class DemoKeyFile {

    private DemoKeyFile() {
    }

    public static byte[] readAllBytes(Path p) throws IOException {
        return Files.readAllBytes(p);
    }

    public static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

