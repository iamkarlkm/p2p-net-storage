package javax.net.p2p.server;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.p2p.api.P2PServiceCategory;
import javax.net.p2p.config.P2PConfig;

public final class P2PServiceManager {
    private static final Set<P2PServiceCategory> DISABLED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private P2PServiceManager() {
    }

    public static boolean isEnabled(P2PServiceCategory category) {
        if (category == null) {
            return true;
        }
        return !DISABLED.contains(category);
    }

    public static void disable(P2PServiceCategory category) {
        if (category == null) {
            return;
        }
        if (category == P2PServiceCategory.CORE) {
            return;
        }
        if (DISABLED.add(category)) {
            javax.net.p2p.channel.AbstractTcpMessageProcessor.unloadCategory(category);
            javax.net.p2p.channel.AbstractUdpMessageProcessor.unloadCategory(category);
            javax.net.p2p.channel.AbstractQuicMessageProcessor.unloadCategory(category);
        }
    }

    public static void enable(P2PServiceCategory category) {
        if (category == null) {
            return;
        }
        if (DISABLED.remove(category)) {
            javax.net.p2p.channel.AbstractTcpMessageProcessor.loadCategory(category);
            javax.net.p2p.channel.AbstractUdpMessageProcessor.loadCategory(category);
            javax.net.p2p.channel.AbstractQuicMessageProcessor.loadCategory(category);
        }
    }

    public static Set<P2PServiceCategory> disabledSnapshot() {
        if (DISABLED.isEmpty()) {
            return EnumSet.noneOf(P2PServiceCategory.class);
        }
        return EnumSet.copyOf(DISABLED);
    }

    public static void initFromConfigOnce() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        String v = P2PConfig.getOptionalProperty("p2p.services.disabled");
        if (v == null || v.isBlank()) {
            return;
        }
        for (String part : v.split(",")) {
            if (part == null) {
                continue;
            }
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                P2PServiceCategory c = P2PServiceCategory.valueOf(s);
                if (c != P2PServiceCategory.CORE) {
                    DISABLED.add(c);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
