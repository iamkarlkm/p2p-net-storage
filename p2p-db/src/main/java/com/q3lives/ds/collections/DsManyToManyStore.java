package com.q3lives.ds.collections;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.bucket.DsFixedBucketStore.UpdatePolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

public final class DsManyToManyStore implements AutoCloseable {

    private final ReentrantLock lock = new ReentrantLock();

    private final DsFixedBucketStore bucketStore;
    private final String ringType;

    private final DsHashMap leftToRingId;
    private final DsHashMap rightToRingId;

    private final int initialRingCap;

    public DsManyToManyStore(Path rootDir, String name, int initialRingCap) {
        if (initialRingCap <= 0) {
            throw new IllegalArgumentException("initialRingCap must be > 0");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is blank");
        }
        try {
            Path home = rootDir.toAbsolutePath().normalize();
            Files.createDirectories(home);
            this.bucketStore = new DsFixedBucketStore(home.resolve("rings").toString());
            this.ringType = name + "_ring";
            this.leftToRingId = new DsHashMap(home.resolve(name + ".left.map").toFile());
            this.rightToRingId = new DsHashMap(home.resolve(name + ".right.map").toFile());
            this.leftToRingId.setSyncModeStrong100ms();
            this.rightToRingId.setSyncModeStrong100ms();
            this.initialRingCap = initialRingCap;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean link(long leftId, long rightId) throws IOException {
        lock.lock();
        try {
            boolean changedLeft = ensureLinked(leftToRingId, leftId, rightId);
            boolean changedRight = ensureLinked(rightToRingId, rightId, leftId);
            if (changedLeft) {
                leftToRingId.sync();
            }
            if (changedRight) {
                rightToRingId.sync();
            }
            return changedLeft || changedRight;
        } finally {
            lock.unlock();
        }
    }

    public boolean unlink(long leftId, long rightId) throws IOException {
        lock.lock();
        try {
            boolean changedLeft = removeLink(leftToRingId, leftId, rightId);
            boolean changedRight = removeLink(rightToRingId, rightId, leftId);
            if (changedLeft) {
                leftToRingId.sync();
            }
            if (changedRight) {
                rightToRingId.sync();
            }
            return changedLeft || changedRight;
        } finally {
            lock.unlock();
        }
    }

    public long[] listRights(long leftId) throws IOException {
        lock.lock();
        try {
            return listRelated(leftToRingId, leftId);
        } finally {
            lock.unlock();
        }
    }

    public long[] listLefts(long rightId) throws IOException {
        lock.lock();
        try {
            return listRelated(rightToRingId, rightId);
        } finally {
            lock.unlock();
        }
    }

    private boolean ensureLinked(DsHashMap ownerToRingId, long ownerId, long relatedId) throws IOException {
        Long ringId = ownerToRingId.get(Long.valueOf(ownerId));
        if (ringId == null) {
            long newRingId = createRingWith(relatedId);
            ownerToRingId.put(Long.valueOf(ownerId), Long.valueOf(newRingId));
            return true;
        }

        DsMemoryRing ring = loadRing(ringId.longValue());
        if (!ring.offerUnique(relatedId)) {
            return false;
        }
        long savedId = saveRing(ringId.longValue(), ring);
        if (savedId != ringId.longValue()) {
            ownerToRingId.put(Long.valueOf(ownerId), Long.valueOf(savedId));
        }
        return true;
    }

    private boolean removeLink(DsHashMap ownerToRingId, long ownerId, long relatedId) throws IOException {
        Long ringId = ownerToRingId.get(Long.valueOf(ownerId));
        if (ringId == null) {
            return false;
        }
        long id = ringId.longValue();
        DsMemoryRing ring = loadRing(id);
        if (!ring.removeValue(relatedId)) {
            return false;
        }
        if (ring.getCount() == 0) {
            bucketStore.removeMeta(ringType, id);
            ownerToRingId.remove(Long.valueOf(ownerId));
            return true;
        }
        long savedId = saveRing(id, ring);
        if (savedId != id) {
            ownerToRingId.put(Long.valueOf(ownerId), Long.valueOf(savedId));
        }
        return true;
    }

    private long[] listRelated(DsHashMap ownerToRingId, long ownerId) throws IOException {
        Long ringId = ownerToRingId.get(Long.valueOf(ownerId));
        if (ringId == null) {
            return new long[0];
        }
        DsMemoryRing ring = loadRing(ringId.longValue());
        return ring.snapshot();
    }

    private long createRingWith(long relatedId) throws IOException {
        DsMemoryRing ring = new DsMemoryRing(initialRingCap);
        ring.offer(relatedId);
        return bucketStore.put(DsFixedBucketStore.META_SPACE, ringType, ring.toBytes());
    }

    private DsMemoryRing loadRing(long ringId) throws IOException {
        // 先读取固定头部（cap 在头部），再按 cap 读取完整 ring 数据，避免依赖 bucket 的 power 解码
        byte[] header = bucketStore.get(DsFixedBucketStore.META_SPACE, ringType, ringId, 20);
        if (header.length < 20) {
            throw new IOException("invalid ring header");
        }
        if (header[0] != '.' || header[1] != 'M' || header[2] != '-' || header[3] != 'R') {
            throw new IOException("invalid ring magic");
        }
        int cap = java.nio.ByteBuffer.wrap(header).getInt(4);
        if (cap <= 0) {
            throw new IOException("invalid ring cap: " + cap);
        }
        int len = 20 + cap * 8;
        byte[] data = bucketStore.get(DsFixedBucketStore.META_SPACE, ringType, ringId, len);
        return new DsMemoryRing(data);
    }

    private long saveRing(long oldRingId, DsMemoryRing ring) throws IOException {
        // ring 可能扩容导致 bytes 变大，使用 update 以便必要时迁移到新 bucket 并回收旧 id
        return bucketStore.update(DsFixedBucketStore.META_SPACE, ringType, oldRingId, ring.toBytes(), UpdatePolicy.KEEP_BUCKET);
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            leftToRingId.close();
            rightToRingId.close();
            bucketStore.close();
        } finally {
            lock.unlock();
        }
    }
}
