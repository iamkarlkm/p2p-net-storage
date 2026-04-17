package p2pws.sdk;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryKeyFileProvider implements KeyFileProvider {

    private final Map<Key, byte[]> map = new ConcurrentHashMap<>();

    public void put(byte[] keyId32, byte[] keyfileBytes) {
        if (keyId32 == null || keyId32.length != 32) {
            throw new IllegalArgumentException("keyId must be 32 bytes");
        }
        if (keyfileBytes == null) {
            throw new IllegalArgumentException("keyfileBytes required");
        }
        map.put(new Key(keyId32), keyfileBytes);
    }

    @Override
    public byte[] read(byte[] keyId32, long offset, int len) {
        byte[] key = map.get(new Key(keyId32));
        if (key == null) {
            throw new IllegalStateException("keyfile not found");
        }
        if (offset < 0 || offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("offset out of range");
        }
        int off = (int) offset;
        if (len < 0) {
            throw new IllegalArgumentException("len out of range");
        }
        if (off + len > key.length) {
            throw new IllegalArgumentException("offset+len exceeds keyLen");
        }
        return Arrays.copyOfRange(key, off, off + len);
    }

    @Override
    public long length(byte[] keyId32) {
        byte[] key = map.get(new Key(keyId32));
        if (key == null) {
            return 0;
        }
        return key.length;
    }

    private record Key(byte[] id) {
        private Key(byte[] id) {
            if (id == null || id.length != 32) {
                throw new IllegalArgumentException("keyId must be 32 bytes");
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

