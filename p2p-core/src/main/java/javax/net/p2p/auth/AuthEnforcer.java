package javax.net.p2p.auth;

import io.netty.channel.Channel;
import java.util.List;
import java.util.Map;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.auth.config.AuthConfig;
import javax.net.p2p.channel.ChannelUtils;
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
        if (cmd == P2PCommand.HAND || cmd == P2PCommand.HEART_PING || cmd == P2PCommand.HEART_PONG) {
            return null;
        }

        byte[] key = channel.attr(ChannelUtils.XOR_KEY).get();
        if (key == null || key.length == 0) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "handshake required");
        }

        Boolean logged = channel.attr(ChannelUtils.AUTH_LOGGED_IN).get();
        boolean isLoggedIn = logged != null && logged;
        if (!isLoggedIn) {
            if (cmd == P2PCommand.LOGIN) {
                return null;
            }
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "login required");
        }

        String userId = channel.attr(ChannelUtils.AUTH_USER_ID).get();
        if (userId == null || userId.isBlank()) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "missing userId");
        }

        AuthConfig.Server server = cfg.getServer();
        if (server == null) {
            return null;
        }
        Map<String, List<String>> allow = server.getAllowCommands();
        if (allow == null || allow.isEmpty()) {
            return null;
        }
        List<String> rules = allow.get(userId);
        if (rules == null || rules.isEmpty()) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "permission denied");
        }
        String name = cmd.name();
        for (String r : rules) {
            if (r == null) {
                continue;
            }
            if ("*".equals(r) || name.equals(r)) {
                return null;
            }
        }
        return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "permission denied");
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

