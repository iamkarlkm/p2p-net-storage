package p2pws.sdk;

import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FileKeyFileProvider implements KeyFileProvider {

    private final Map<Key, Entry> map = new ConcurrentHashMap<>();

    public void put(Path path) {
        try {
            byte[] keyId;
            try (InputStream in = java.nio.file.Files.newInputStream(path)) {
                keyId = KeyId.sha256(in);
            }
            FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
            long len = ch.size();
            map.put(new Key(keyId), new Entry(ch, len));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] read(byte[] keyId32, long offset, int len) {
        Entry e = map.get(new Key(keyId32));
        if (e == null) {
            throw new IllegalStateException("keyfile not found");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset out of range");
        }
        if (len < 0) {
            throw new IllegalArgumentException("len out of range");
        }
        if (offset + len > e.len) {
            throw new IllegalArgumentException("offset+len exceeds keyLen");
        }
        byte[] out = new byte[len];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(out);
        try {
            while (buf.hasRemaining()) {
                int n = e.ch.read(buf, offset + buf.position());
                if (n < 0) {
                    break;
                }
            }
            return out;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public long length(byte[] keyId32) {
        Entry e = map.get(new Key(keyId32));
        return e == null ? 0 : e.len;
    }

    public static byte[] sha256(Path path) {
        try (InputStream in = java.nio.file.Files.newInputStream(path)) {
            return KeyId.sha256(in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record Entry(FileChannel ch, long len) {
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

