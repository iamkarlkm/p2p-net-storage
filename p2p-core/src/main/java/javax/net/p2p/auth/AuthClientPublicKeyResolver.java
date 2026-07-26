package javax.net.p2p.auth;

import java.util.List;
import java.util.Map;
import javax.net.p2p.auth.config.AuthConfig;

public final class AuthClientPublicKeyResolver {

    private AuthClientPublicKeyResolver() {
    }

    public static String resolve(AuthConfig.Server server, String userId) {
        if (server == null || userId == null || userId.isBlank()) {
            return null;
        }
        if (containsUnsafePathChars(userId)) {
            return null;
        }

        Map<String, String> legacy = server.getClientPublicKeys();
        if (legacy != null) {
            String v = legacy.get(userId);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }

        List<AuthConfig.ClientKeyBinding> bindings = server.getClientPublicKeyBindings();
        if (bindings != null) {
            for (AuthConfig.ClientKeyBinding b : bindings) {
                if (b == null) {
                    continue;
                }
                String match = b.getMatch();
                if (match == null || match.isBlank()) {
                    continue;
                }
                if (!globMatch(match, userId)) {
                    continue;
                }
                String pk = b.getPublicKey();
                if (pk != null && !pk.isBlank()) {
                    return pk;
                }
            }
        }

        String template = server.getClientPublicKeyTemplate();
        if (template != null && !template.isBlank()) {
            String resolved = applyTemplate(template, userId);
            return resolved == null || resolved.isBlank() ? null : resolved;
        }

        String dir = server.getClientPublicKeyDir();
        if (dir != null && !dir.isBlank()) {
            String suffix = server.getClientPublicKeySuffix();
            String ext = suffix == null || suffix.isBlank() ? ".pub" : suffix.trim();
            if (!ext.startsWith(".")) {
                ext = "." + ext;
            }
            String base = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
            return base + "/" + userId + ext;
        }

        return null;
    }

    private static boolean containsUnsafePathChars(String s) {
        if (s.indexOf('/') >= 0 || s.indexOf('\\') >= 0) {
            return true;
        }
        if (s.contains("..")) {
            return true;
        }
        return false;
    }

    private static String applyTemplate(String template, String userId) {
        String p2 = userId.length() >= 2 ? userId.substring(0, 2) : userId;
        String out = template;
        out = out.replace("{userId}", userId);
        out = out.replace("{userIdPrefix2}", p2);
        return out;
    }

    private static boolean globMatch(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        if ("*".equals(pattern)) {
            return true;
        }
        int p = 0;
        int v = 0;
        int star = -1;
        int match = 0;
        while (v < value.length()) {
            if (p < pattern.length()
                    && (pattern.charAt(p) == '?' || pattern.charAt(p) == value.charAt(v))) {
                p++;
                v++;
                continue;
            }
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p;
                match = v;
                p++;
                continue;
            }
            if (star != -1) {
                p = star + 1;
                match++;
                v = match;
                continue;
            }
            return false;
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}

