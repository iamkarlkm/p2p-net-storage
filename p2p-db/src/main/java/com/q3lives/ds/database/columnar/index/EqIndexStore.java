package com.q3lives.ds.database.columnar.index;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.util.DsDataUtil;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Objects;

public final class EqIndexStore {
    private static final int NODE_BYTES = 16;
    private final File dbRoot;
    private final DsFixedBucketStore bucketStore;
    private final EqIndexMetaStore metaStore;

    public EqIndexStore(File dbRoot) {
        this.dbRoot = Objects.requireNonNull(dbRoot, "dbRoot cannot be null");
        this.bucketStore = new DsFixedBucketStore(this.dbRoot.getAbsolutePath());
        this.metaStore = new EqIndexMetaStore(this.dbRoot);
    }

    public boolean exists(String entityClassName, String logicalName) {
        return metaStore.exists(entityClassName, logicalName);
    }

    public EqIndexMetaStore.IndexDef get(String entityClassName, String logicalName) {
        return metaStore.get(entityClassName, logicalName);
    }

    public void createOrReplace(String entityClassName, String logicalName, long colId) {
        EqIndexMetaStore.IndexDef meta = new EqIndexMetaStore.IndexDef();
        meta.entityClassName = entityClassName;
        meta.logicalName = logicalName;
        meta.colId = colId;
        metaStore.put(meta);
    }

    public boolean drop(String entityClassName, String logicalName) throws Exception {
        EqIndexMetaStore.IndexDef idx = metaStore.get(entityClassName, logicalName);
        if (idx == null || idx.colId <= 0L) {
            return false;
        }
        long colId = idx.colId;
        withLock(entityClassName, colId, () -> {
            DsHashMap map = new DsHashMap(metaStore.mapFile(entityClassName, colId));
            Long[] hashes = map.keySet().toArray(new Long[0]);
            for (Long hashObj : hashes) {
                if (hashObj == null) {
                    continue;
                }
                long cur = map.getOrDefault(hashObj, 0L);
                while (cur != 0L) {
                    byte[] node = bucketStore.get(entityClassName, nodeType(colId), cur, 0, NODE_BYTES);
                    long next = decodeNext(node);
                    bucketStore.remove(entityClassName, nodeType(colId), cur);
                    cur = next;
                }
                map.remove(hashObj);
            }
        });
        metaStore.delete(entityClassName, logicalName);
        return true;
    }

    public void add(String entityClassName, long colId, byte[] valueBytes, long rowId) throws Exception {
        if (rowId <= 0L) {
            return;
        }
        long hash = DsDataUtil.hash64(normalizeBytes(valueBytes));
        withLock(entityClassName, colId, () -> {
            DsHashMap map = new DsHashMap(metaStore.mapFile(entityClassName, colId));
            long head = map.getOrDefault(hash, 0L);
            long nodeId = bucketStore.getNewId(entityClassName, nodeType(colId), NODE_BYTES);
            bucketStore.overwrite(entityClassName, nodeType(colId), nodeId, encodeNode(rowId, head));
            map.put(hash, nodeId);
        });
    }

    public void remove(String entityClassName, long colId, byte[] valueBytes, long rowId) throws Exception {
        if (rowId <= 0L) {
            return;
        }
        long hash = DsDataUtil.hash64(normalizeBytes(valueBytes));
        withLock(entityClassName, colId, () -> {
            DsHashMap map = new DsHashMap(metaStore.mapFile(entityClassName, colId));
            long head = map.getOrDefault(hash, 0L);
            if (head == 0L) {
                return;
            }
            ArrayList<Long> keep = new ArrayList<>();
            long cur = head;
            while (cur != 0L) {
                byte[] node = bucketStore.get(entityClassName, nodeType(colId), cur, 0, NODE_BYTES);
                long v = decodeRowId(node);
                long next = decodeNext(node);
                bucketStore.remove(entityClassName, nodeType(colId), cur);
                if (v != rowId) {
                    keep.add(v);
                }
                cur = next;
            }
            long newHead = 0L;
            for (int i = keep.size() - 1; i >= 0; i--) {
                long v = keep.get(i);
                long nodeId = bucketStore.getNewId(entityClassName, nodeType(colId), NODE_BYTES);
                bucketStore.overwrite(entityClassName, nodeType(colId), nodeId, encodeNode(v, newHead));
                newHead = nodeId;
            }
            if (newHead == 0L) {
                map.remove(hash);
            } else {
                map.put(hash, newHead);
            }
        });
    }

    public long[] findRowIds(String entityClassName, long colId, byte[] valueBytes, int limit) throws Exception {
        long hash = DsDataUtil.hash64(normalizeBytes(valueBytes));
        return withLock(entityClassName, colId, () -> {
            DsHashMap map = new DsHashMap(metaStore.mapFile(entityClassName, colId));
            long head = map.getOrDefault(hash, 0L);
            if (head == 0L) {
                return new long[0];
            }
            int cap = limit <= 0 ? Integer.MAX_VALUE : limit;
            ArrayList<Long> out = new ArrayList<>();
            long cur = head;
            while (cur != 0L && out.size() < cap) {
                byte[] node = bucketStore.get(entityClassName, nodeType(colId), cur, 0, NODE_BYTES);
                out.add(decodeRowId(node));
                cur = decodeNext(node);
            }
            long[] arr = new long[out.size()];
            for (int i = 0; i < out.size(); i++) {
                arr[i] = out.get(i);
            }
            return arr;
        });
    }

    private void withLock(String entityClassName, long colId, ThrowingRunnable body) throws Exception {
        File lockFile = metaStore.lockFile(entityClassName, colId);
        try (FileChannel ch = new FileOutputStream(lockFile, true).getChannel();
            FileLock ignored = ch.lock()) {
            body.run();
        }
    }

    private <T> T withLock(String entityClassName, long colId, ThrowingSupplier<T> body) throws Exception {
        File lockFile = metaStore.lockFile(entityClassName, colId);
        try (FileChannel ch = new FileOutputStream(lockFile, true).getChannel();
            FileLock ignored = ch.lock()) {
            return body.get();
        }
    }

    private static String nodeType(long colId) {
        return "idxn_" + colId;
    }

    private static byte[] encodeNode(long rowId, long next) {
        ByteBuffer buf = ByteBuffer.allocate(NODE_BYTES);
        buf.putLong(rowId);
        buf.putLong(next);
        return buf.array();
    }

    private static long decodeRowId(byte[] node) {
        return ByteBuffer.wrap(node).getLong(0);
    }

    private static long decodeNext(byte[] node) {
        return ByteBuffer.wrap(node).getLong(8);
    }

    private static byte[] normalizeBytes(byte[] v) {
        return v == null ? new byte[0] : v;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
