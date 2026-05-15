package javax.net.p2p.auth;

import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.api.P2PServiceCategory;
import javax.net.p2p.auth.config.AuthConfig;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.model.P2PWrapper;

public final class AuthEnforcer {

    private static volatile AuthConfig CONFIG;
    private static volatile String CONFIG_SOURCE;

    private AuthEnforcer() {
    }

    public static P2PWrapper check(Channel channel, P2PWrapper request) {
        AuthConfig cfg = loadConfig();
        if (cfg == null || !cfg.isEnabled()) {
            return null;
        }
        P2PCommand cmd = request.getCommand();
        if (cmd == P2PCommand.HAND
                || cmd == P2PCommand.HEART_PING
                || cmd == P2PCommand.HEART_PONG
                || cmd == P2PCommand.STD_CANCEL
                || cmd == P2PCommand.STD_STOP
                || cmd == P2PCommand.UDP_FRAME_ACK
                || cmd == P2PCommand.UDP_FRAME_RESET
                || cmd == P2PCommand.UDP_RELIABILITY_ACK
                || cmd == P2PCommand.UDP_STREAM_ACK2) {
            return null;
        }

        byte[] key = channel.attr(ChannelUtils.XOR_KEY).get();
        Boolean handshakeDone = channel.attr(ChannelUtils.AUTH_HANDSHAKE_DONE).get();
        boolean hasKey = key != null && key.length > 0;
        boolean hasHandshake = handshakeDone != null && handshakeDone;
        if (!hasKey && !hasHandshake) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.AUTH_HANDSHAKE_REQUIRED);
        }

        Boolean logged = channel.attr(ChannelUtils.AUTH_LOGGED_IN).get();
        boolean isLoggedIn = logged != null && logged;
        if (!isLoggedIn) {
            if (cmd == P2PCommand.LOGIN) {
                return null;
            }
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.AUTH_LOGIN_REQUIRED);
        }

        String userId = channel.attr(ChannelUtils.AUTH_USER_ID).get();
        if (userId == null || userId.isBlank()) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.AUTH_MISSING_USER_ID);
        }

        AuthConfig.Server server = cfg.getServer();
        if (server == null) {
            return null;
        }
        if (isAllowed(server, userId, cmd)) {
            return null;
        }
        return P2PErrors.stdError(request.getSeq(), P2PErrorCode.AUTH_PERMISSION_DENIED);
    }

    private static boolean isAllowed(AuthConfig.Server server, String userId, P2PCommand cmd) {
        Map<String, AuthConfig.RolePolicy> roles = server.getRoles();
        if (roles != null && !roles.isEmpty()) {
            List<String> effectiveRoles = resolveRoles(server, userId);
            if (!effectiveRoles.isEmpty() && isAllowedByRolePolicies(roles, effectiveRoles, cmd)) {
                return true;
            }
            Map<String, List<String>> allowCommands = server.getAllowCommands();
            List<String> userRules = allowCommands == null ? null : allowCommands.get(userId);
            if (userRules != null && !userRules.isEmpty()) {
                return isAllowedByCommands(userRules, cmd);
            }
            return false;
        }

        Map<String, List<String>> allow = server.getAllowCommands();
        if (allow == null || allow.isEmpty()) {
            return true;
        }
        List<String> rules = allow.get(userId);
        if (rules == null || rules.isEmpty()) {
            return false;
        }
        return isAllowedByCommands(rules, cmd);
    }

    private static boolean isAllowedByRolePolicies(
            Map<String, AuthConfig.RolePolicy> rolePolicies,
            List<String> roleNames,
            P2PCommand cmd) {
        String cmdName = cmd.name();
        P2PServiceCategory category = cmd.getCategory();
        String categoryName = category == null ? null : category.name();

        for (String roleName : roleNames) {
            if (roleName == null || roleName.isBlank()) {
                continue;
            }
            AuthConfig.RolePolicy policy = rolePolicies.get(roleName);
            if (policy == null) {
                continue;
            }
            if (isAllowedByStrings(policy.getAllowCommands(), cmdName)) {
                return true;
            }
            if (categoryName != null && isAllowedByStrings(policy.getAllowCategories(), categoryName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllowedByCommands(List<String> allowCommands, P2PCommand cmd) {
        return isAllowedByStrings(allowCommands, cmd.name());
    }

    private static boolean isAllowedByStrings(List<String> rules, String value) {
        if (rules == null || rules.isEmpty() || value == null) {
            return false;
        }
        for (String r : rules) {
            if (r == null) {
                continue;
            }
            if ("*".equals(r) || value.equals(r)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> resolveRoles(AuthConfig.Server server, String userId) {
        Set<String> out = new LinkedHashSet<>();

        List<String> defaults = server.getDefaultRoles();
        if (defaults != null) {
            for (String r : defaults) {
                if (r != null && !r.isBlank()) {
                    out.add(r);
                }
            }
        }

        Map<String, List<String>> userRoles = server.getUserRoles();
        List<String> direct = userRoles == null ? null : userRoles.get(userId);
        if (direct != null) {
            for (String r : direct) {
                if (r != null && !r.isBlank()) {
                    out.add(r);
                }
            }
        }

        List<AuthConfig.RoleBinding> bindings = server.getRoleBindings();
        if (bindings != null) {
            for (AuthConfig.RoleBinding b : bindings) {
                if (b == null) {
                    continue;
                }
                String pattern = b.getMatch();
                if (pattern == null || pattern.isBlank()) {
                    continue;
                }
                if (!globMatch(pattern, userId)) {
                    continue;
                }
                List<String> rs = b.getRoles();
                if (rs == null) {
                    continue;
                }
                for (String r : rs) {
                    if (r != null && !r.isBlank()) {
                        out.add(r);
                    }
                }
            }
        }

        return out.isEmpty() ? List.of() : new ArrayList<>(out);
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

    private static AuthConfig loadConfig() {
        String currentSource = normalizeSource(System.getProperty("p2p.auth.yaml"));
        AuthConfig local = CONFIG;
        String cachedSource = CONFIG_SOURCE;
        if (local != null && cachedSource != null && cachedSource.equals(currentSource)) {
            return local;
        }
        synchronized (AuthEnforcer.class) {
            currentSource = normalizeSource(System.getProperty("p2p.auth.yaml"));
            local = CONFIG;
            cachedSource = CONFIG_SOURCE;
            if (local != null && cachedSource != null && cachedSource.equals(currentSource)) {
                return local;
            }
            local = AuthConfig.load();
            CONFIG = local;
            CONFIG_SOURCE = currentSource;
            return local;
        }
    }

    private static String normalizeSource(String src) {
        if (src == null) {
            return "";
        }
        String s = src.trim();
        return s.isEmpty() ? "" : s;
    }
}

