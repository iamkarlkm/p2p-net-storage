package p2pws.sdk.center;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RegisteredUsers {

    private final Map<Long, Entry> byNodeId = new ConcurrentHashMap<>();
    private final Map<Key, Entry> byNodeKey = new ConcurrentHashMap<>();

    public void put(long nodeId64, byte[] nodeKey32, byte[] pubkeySpkiDer, boolean enabled, List<String> allowedCryptoModes) {
        Entry e = new Entry(nodeId64, nodeKey32, pubkeySpkiDer, enabled, allowedCryptoModes);
        byNodeId.put(nodeId64, e);
        byNodeKey.put(new Key(nodeKey32), e);
    }

    public Entry getByNodeId(long nodeId64) {
        return byNodeId.get(nodeId64);
    }

    public Entry getByNodeKey(byte[] nodeKey32) {
        return byNodeKey.get(new Key(nodeKey32));
    }

    public record Entry(long nodeId64, byte[] nodeKey32, byte[] pubkeySpkiDer, boolean enabled, List<String> allowedCryptoModes) {
        public Entry(long nodeId64, byte[] nodeKey32, byte[] pubkeySpkiDer, boolean enabled, List<String> allowedCryptoModes) {
            if (nodeKey32 == null || nodeKey32.length != 32) {
                throw new IllegalArgumentException("nodeKey32 must be 32 bytes");
            }
            if (pubkeySpkiDer == null || pubkeySpkiDer.length == 0) {
                throw new IllegalArgumentException("pubkeySpkiDer required");
            }
            this.nodeId64 = nodeId64;
            this.nodeKey32 = Arrays.copyOf(nodeKey32, nodeKey32.length);
            this.pubkeySpkiDer = Arrays.copyOf(pubkeySpkiDer, pubkeySpkiDer.length);
            this.enabled = enabled;
            this.allowedCryptoModes = allowedCryptoModes == null ? java.util.List.of() : java.util.List.copyOf(allowedCryptoModes);
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
