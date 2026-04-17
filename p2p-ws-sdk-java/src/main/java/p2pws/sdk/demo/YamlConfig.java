package p2pws.sdk.demo;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public final class YamlConfig {

    private YamlConfig() {
    }

    public static ServerConfig loadServer(Path path) {
        Map<String, Object> m = loadMap(path);
        int listenPort = intVal(m, "listen_port", 18090);
        String wsPath = strVal(m, "ws_path", "/p2p");
        String keyfilePath = strVal(m, "keyfile_path", null);
        int magic = intVal(m, "magic", 0x1234);
        int version = intVal(m, "version", 1);
        int flagsPlain = intVal(m, "flags_plain", 4);
        int flagsEncrypted = intVal(m, "flags_encrypted", 5);
        int maxFramePayload = intVal(m, "max_frame_payload", 4 * 1024 * 1024);
        if (keyfilePath == null || keyfilePath.isBlank()) {
            throw new IllegalArgumentException("keyfile_path required");
        }
        java.nio.file.Path kp = java.nio.file.Path.of(keyfilePath);
        if (!kp.isAbsolute()) {
            kp = path.toAbsolutePath().normalize().getParent().resolve(kp).normalize();
        }
        return new ServerConfig(listenPort, wsPath, kp.toString(), magic, version, flagsPlain, flagsEncrypted, maxFramePayload);
    }

    private static Map<String, Object> loadMap(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            Yaml yaml = new Yaml();
            Object o = yaml.load(in);
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException("yaml root must be map");
            }
            return (Map<String, Object>) o;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String strVal(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        if (v == null) {
            return def;
        }
        return String.valueOf(v);
    }

    private static int intVal(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(v).trim();
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Integer.parseInt(s.substring(2), 16);
            }
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
}
