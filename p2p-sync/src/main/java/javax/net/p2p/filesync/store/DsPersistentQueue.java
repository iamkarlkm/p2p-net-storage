package javax.net.p2p.filesync.store;

import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.core.DsString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class DsPersistentQueue<T> implements PersistentQueue<T>, AutoCloseable {

    private static final long META_NEXT_ID = 1L;

    private final PersistentCodec<T> codec;
    private final DsHashSet entryIds;
    private final DsHashMap entryIdToPayloadId;
    private final DsHashMap meta;
    private final DsString payloadStrings;

    public DsPersistentQueue(Path dsHome, String name, PersistentCodec<T> codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
        validateName(name);
        try {
            Path home = Objects.requireNonNull(dsHome, "dsHome").toAbsolutePath().normalize();
            Files.createDirectories(home);

            this.entryIds = new DsHashSet(home.resolve(name + ".entries.set").toFile());
            this.entryIdToPayloadId = new DsHashMap(home.resolve(name + ".entry_payload.map").toFile());
            this.entryIdToPayloadId.setSyncModeStrong100ms();
            this.meta = new DsHashMap(home.resolve(name + ".meta.map").toFile());
            this.meta.setSyncModeStrong100ms();

            Path stringsHome = home.resolve(name + ".strings");
            Files.createDirectories(stringsHome);
            this.payloadStrings = new DsString(stringsHome.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized boolean isEmpty() {
        return entryIds.isEmpty();
    }

    @Override
    public synchronized int size() {
        return entryIds.size();
    }

    @Override
    public synchronized long enqueue(T value) {
        String encoded = codec.encode(value);
        if (encoded == null) {
            encoded = "";
        }

        long entryId = nextEntryId();
        long payloadId;
        try {
            payloadId = payloadStrings.add(encoded);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        entryIdToPayloadId.put(Long.valueOf(entryId), Long.valueOf(payloadId));
        entryIds.add(Long.valueOf(entryId));
        return entryId;
    }

    @Override
    public synchronized Entry<T> peek() {
        Iterator<Long> it = entryIds.iterator();
        if (!it.hasNext()) {
            return null;
        }
        long entryId = it.next().longValue();
        return get(entryId);
    }

    @Override
    public synchronized Entry<T> get(long entryId) {
        Long payloadId = entryIdToPayloadId.get(Long.valueOf(entryId));
        if (payloadId == null) {
            return null;
        }
        String encoded;
        try {
            encoded = payloadStrings.get(payloadId.longValue());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        T value = codec.decode(encoded);
        return new Entry<>(entryId, value);
    }

    @Override
    public synchronized boolean remove(long entryId) {
        Long payloadId = entryIdToPayloadId.remove(Long.valueOf(entryId));
        if (payloadId == null) {
            return false;
        }
        entryIds.remove(Long.valueOf(entryId));
        try {
            payloadStrings.remove(payloadId.longValue());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    public synchronized void sync() {
        entryIds.sync();
        entryIdToPayloadId.sync();
        meta.sync();
    }

    @Override
    public synchronized void close() {
        try {
            sync();
        } finally {
            tryClose(entryIds);
            tryClose(entryIdToPayloadId);
            tryClose(meta);
            try {
                payloadStrings.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public Iterator<Entry<T>> iterator() {
        Iterator<Long> it = entryIds.iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public Entry<T> next() {
                Long id = it.next();
                if (id == null) {
                    throw new NoSuchElementException();
                }
                Entry<T> e;
                synchronized (DsPersistentQueue.this) {
                    e = get(id.longValue());
                }
                if (e == null) {
                    throw new NoSuchElementException();
                }
                return e;
            }
        };
    }

    private long nextEntryId() {
        Long next = meta.get(Long.valueOf(META_NEXT_ID));
        long id = next == null ? 1L : next.longValue();
        if (id <= 0L) {
            id = 1L;
        }
        meta.put(Long.valueOf(META_NEXT_ID), Long.valueOf(id + 1L));
        return id;
    }

    private static void tryClose(Object obj) {
        if (obj == null) {
            return;
        }
        try {
            if (obj instanceof AutoCloseable) {
                ((AutoCloseable) obj).close();
            }
        } catch (Exception ignored) {
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is blank");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.';
            if (!ok) {
                throw new IllegalArgumentException("invalid name: " + name);
            }
        }
    }
}
