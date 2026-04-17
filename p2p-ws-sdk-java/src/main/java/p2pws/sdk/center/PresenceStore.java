package p2pws.sdk.center;

import io.netty.channel.ChannelHandlerContext;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import p2pws.P2PControl;

public final class PresenceStore {

    private final Map<Key, Presence> map = new ConcurrentHashMap<>();
    private final Map<Long, Presence> byNodeId = new ConcurrentHashMap<>();

    public void put(byte[] nodeKey32, long nodeId64, List<P2PControl.Endpoint> endpoints, P2PControl.NodeCaps caps, long expiresAtMs, ChannelHandlerContext ctx) {
        Presence p = new Presence(nodeKey32, nodeId64, endpoints, caps, expiresAtMs, ctx);
        map.put(new Key(nodeKey32), p);
        if (nodeId64 != 0) {
            byNodeId.put(nodeId64, p);
        }
    }

    public Presence get(byte[] nodeKey32) {
        return map.get(new Key(nodeKey32));
    }

    public Presence getByNodeId(long nodeId64) {
        return byNodeId.get(nodeId64);
    }

    public int purgeExpired(long nowMs) {
        int removed = 0;
        for (Map.Entry<Key, Presence> e : map.entrySet()) {
            Presence p = e.getValue();
            if (p != null && p.expiresAtMs() <= nowMs) {
                if (map.remove(e.getKey(), p)) {
                    removed++;
                }
                if (p != null && p.nodeId64() != 0) {
                    byNodeId.remove(p.nodeId64(), p);
                }
            }
        }
        return removed;
    }

    public record Presence(byte[] nodeKey32, long nodeId64, List<P2PControl.Endpoint> endpoints, P2PControl.NodeCaps caps, long expiresAtMs, ChannelHandlerContext ctx) {
        public Presence(byte[] nodeKey32, long nodeId64, List<P2PControl.Endpoint> endpoints, P2PControl.NodeCaps caps, long expiresAtMs, ChannelHandlerContext ctx) {
            if (nodeKey32 == null || nodeKey32.length != 32) {
                throw new IllegalArgumentException("nodeKey32 must be 32 bytes");
            }
            this.nodeKey32 = Arrays.copyOf(nodeKey32, nodeKey32.length);
            this.nodeId64 = nodeId64;
            this.endpoints = endpoints == null ? java.util.List.of() : java.util.List.copyOf(endpoints);
            this.caps = caps;
            this.expiresAtMs = expiresAtMs;
            this.ctx = ctx;
        }
    }

    private record Key(byte[] id) {
        private Key(byte[] id) {
            if (id == null || id.length != 32) {
                throw new IllegalArgumentException("nodeKey must be 32 bytes");
            }
            this.id = Arrays.copyOf(id, id.length);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Arrays.equals(id, k.id);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(id);
        }
    }
}
