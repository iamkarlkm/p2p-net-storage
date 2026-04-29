package p2pws.sdk.demo;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
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
        Map<Integer, String> storageLocations = parseStorageLocations(m, "storage_locations", path);
        Map<Integer, String> imStorageLocations = parseStorageLocations(m, "im_storage_locations", path);
        return new ServerConfig(listenPort, wsPath, kp.toString(), magic, version, flagsPlain, flagsEncrypted, maxFramePayload, storageLocations, imStorageLocations);
    }

    private static Map<Integer, String> parseStorageLocations(Map<String, Object> root, String key, Path cfgPath) {
        Object raw = root.get(key);
        if (raw == null) {
            return Map.of();
        }
        Path base = cfgPath.toAbsolutePath().normalize().getParent();
        HashMap<Integer, String> out = new HashMap<>();
        if (raw instanceof Map m) {
            for (Object k0 : m.keySet()) {
                Integer sid = tryParseInt(String.valueOf(k0));
                if (sid == null || sid == 0) continue;
                String v = String.valueOf(m.get(k0)).trim();
                if (v.isEmpty()) continue;
                Path p = Path.of(v);
                if (!p.isAbsolute()) {
                    p = base.resolve(p).normalize();
                }
                out.put(sid, p.toString());
            }
            return out;
        }
        if (raw instanceof List list) {
            for (Object e : list) {
                if (!(e instanceof Map m)) continue;
                Object sidRaw = m.getOrDefault("store_id", m.get("storeId"));
                Integer sid = tryParseInt(String.valueOf(sidRaw));
                if (sid == null || sid == 0) continue;
                Object pathRaw = m.getOrDefault("path", m.getOrDefault("dir", ""));
                String v = String.valueOf(pathRaw).trim();
                if (v.isEmpty()) continue;
                Path p = Path.of(v);
                if (!p.isAbsolute()) {
                    p = base.resolve(p).normalize();
                }
                out.put(sid, p.toString());
            }
            return out;
        }
        return Map.of();
    }

    private static Integer tryParseInt(String s0) {
        if (s0 == null) return null;
        String s = s0.trim();
        if (s.isEmpty()) return null;
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Integer.parseInt(s.substring(2), 16);
            }
            return Integer.parseInt(s, 10);
        } catch (Exception e) {
            return null;
        }
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
