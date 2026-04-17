package p2pws.sdk.center;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public final class CenterYaml {

    private CenterYaml() {
    }

    public static CenterConfig loadCenter(Path cfgPath) {
        Map<String, Object> m = loadMap(cfgPath);
        int listenPort = intVal(m, "listen_port", 18091);
        String wsPath = strVal(m, "ws_path", "/p2p");
        String keyfilePath = resolvePath(cfgPath, strVal(m, "keyfile_path", null));
        String regPath = resolvePath(cfgPath, strVal(m, "registered_users_path", null));
        int ttlSeconds = intVal(m, "ttl_seconds", 600);
        int magic = intVal(m, "magic", 0x1234);
        int version = intVal(m, "version", 1);
        int flagsPlain = intVal(m, "flags_plain", 4);
        int flagsEncrypted = intVal(m, "flags_encrypted", 5);
        int maxFramePayload = intVal(m, "max_frame_payload", 4 * 1024 * 1024);
        if (keyfilePath == null || keyfilePath.isBlank()) {
            throw new IllegalArgumentException("keyfile_path required");
        }
        if (regPath == null || regPath.isBlank()) {
            throw new IllegalArgumentException("registered_users_path required");
        }
        return new CenterConfig(listenPort, wsPath, keyfilePath, regPath, ttlSeconds, magic, version, flagsPlain, flagsEncrypted, maxFramePayload);
    }

    public static RegisteredUsers loadRegisteredUsers(Path regPath) {
        Map<String, Object> m = loadMap(regPath);
        Object users = m.get("users");
        if (!(users instanceof List)) {
            throw new IllegalArgumentException("users must be list");
        }
        RegisteredUsers out = new RegisteredUsers();
        for (Object o : (List<?>) users) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> u = (Map<String, Object>) o;
            long nodeId64 = longVal(u, "node_id64", 0);
            String nodeKeyHex = strVal(u, "node_key_hex", null);
            String pubB64 = strVal(u, "pubkey_spki_der_base64", null);
            boolean enabled = boolVal(u, "enabled", true);
            List<String> allowed = strList(u, "allowed_crypto_modes");
            if (nodeId64 == 0 || nodeKeyHex == null || nodeKeyHex.isBlank() || pubB64 == null || pubB64.isBlank()) {
                continue;
            }
            byte[] nodeKey = hex(nodeKeyHex.trim());
            byte[] pub = Base64.getDecoder().decode(pubB64.trim());
            out.put(nodeId64, nodeKey, pub, enabled, allowed);
        }
        return out;
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

    private static String resolvePath(Path cfgPath, String p) {
        if (p == null) {
            return null;
        }
        Path pp = Path.of(p);
        if (!pp.isAbsolute()) {
            pp = cfgPath.toAbsolutePath().normalize().getParent().resolve(pp).normalize();
        }
        return pp.toString();
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

    private static long longVal(Map<String, Object> m, String k, long def) {
        Object v = m.get(k);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(v).trim();
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean boolVal(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.equals("true") || s.equals("1") || s.equals("yes")) {
            return true;
        }
        if (s.equals("false") || s.equals("0") || s.equals("no")) {
            return false;
        }
        return def;
    }

    private static List<String> strList(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) {
            return java.util.List.of();
        }
        if (!(v instanceof List)) {
            return java.util.List.of(String.valueOf(v));
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (Object o : (List<?>) v) {
            if (o == null) {
                continue;
            }
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static byte[] hex(String s) {
        int n = s.length();
        if ((n & 1) != 0) {
            throw new IllegalArgumentException("hex length must be even");
        }
        byte[] out = new byte[n / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("invalid hex");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
