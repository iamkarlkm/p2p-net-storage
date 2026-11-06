package com.q3lives.ds.registry;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Predicate;

import com.q3lives.ds.bucket.DsTypeFixedBucketStore;
import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.core.DsFreeRing;
import com.q3lives.ds.core.DsTypeString;
import com.q3lives.ds.enums.Category;
import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.util.MultiTypeDataId;

public class DsRegistry implements AutoCloseable {

    public static final int TYPE_REGISTRY_KEY = 200;
    public static final int TYPE_REGISTRY_VALUE_STRING = 201;
    public static final int TYPE_REGISTRY_NODE_META = 202;
    public static final int TYPE_REGISTRY_VALUE_BINARY = 203;
    public static final int TYPE_REGISTRY_VALUE_COMPLEX = 204;
    public static final int TYPE_REGISTRY_DIR_META  = 205;

    private static final long SEQ_HASH_KEY = 1L;
    private static final long ROOT_DIR_TYPED_ID_KEY = 2L;
    private static final long ROOT_KEY_ID_MARKER = 0L;
    public static final long ROOT_DIR_NODE_ID = 1L;

    private static long makeLeafTypedId(long leafSeq) {
        return MultiTypeDataId.encode(TYPE_REGISTRY_NODE_META, DsRegistryNodeSerializer.POWER_NODE_BLOCK, leafSeq);
    }

    private static final int POWER_NODE_BLOCK_DIR = DsRegistryNodeSerializer.POWER_NODE_BLOCK + 1;

    private static long makeDirTypedId(long keyIdOrMarker) {
        if (keyIdOrMarker == ROOT_KEY_ID_MARKER) {
            return MultiTypeDataId.encode(TYPE_REGISTRY_DIR_META, POWER_NODE_BLOCK_DIR, 0L);
        }
        long idx = MultiTypeDataId.decodeIndexId(keyIdOrMarker);
        return MultiTypeDataId.encode(TYPE_REGISTRY_DIR_META, POWER_NODE_BLOCK_DIR, idx + 1L);
    }

    private static boolean isTypedNodeId(long id) {
        int t = (int)((id >>> 48) & 0xFFFFL);
        return t == TYPE_REGISTRY_NODE_META || t == TYPE_REGISTRY_DIR_META;
    }

    private static long rootDirTypedId() {
        return makeDirTypedId(ROOT_KEY_ID_MARKER);
    }

    private static boolean isRootNodeId(long id) {
        return id == ROOT_DIR_NODE_ID || id == rootDirTypedId();
    }

    private static final int INLINE_BYTES_MAX_LEN = 8;
    private static final int VALUE_BIN_HEADER_LEN = 4;

    private final String rootDir;
    private final String rootCanonical;
    private final int serverId;
    private final int partitionId;

    private final DsTypeString keyStrings;
    private final DsTypeString valueStrings;
    private final DsTypeFixedBucketStore nodeStore;
    private final DsTypeFixedBucketStore valueBinaries;
    private final DsTypeFixedBucketStore valueComplex;

    private final DsHashMap keyIdToDirId;
    private final DsHashMap keyIdToLeafOrDirId;
    private final DsHashMap leafIdToValueOrId;
    private final DsHashMap dirIdToRingId;
    private final DsHashMap nextSequence;

    private final StampedLock sl = new StampedLock();
    private volatile boolean closed = false;

    public DsRegistry(String rootDir) throws IOException {
        this(rootDir, 0, 0);
    }

    public DsRegistry(String rootDir, int serverId, int partitionId) throws IOException {
        if (serverId < 0 || serverId > DsIdLayout.SERVER_ID_MASK) {
            throw new IllegalArgumentException("serverId out of range: " + serverId);
        }
        if (partitionId < 0 || partitionId > DsIdLayout.PARTITION_MASK) {
            throw new IllegalArgumentException("partitionId out of range: " + partitionId);
        }
        this.serverId = serverId;
        this.partitionId = partitionId;
        File f = new File(rootDir);
        if (!f.exists()) f.mkdirs();
        this.rootDir = f.getAbsolutePath();
        this.rootCanonical = f.getCanonicalPath();
        this.keyStrings = new DsTypeString(this.rootCanonical, TYPE_REGISTRY_KEY);
        this.valueStrings = new DsTypeString(this.rootCanonical, TYPE_REGISTRY_VALUE_STRING);
        this.nodeStore = new DsTypeFixedBucketStore(this.rootCanonical, TYPE_REGISTRY_NODE_META);
        this.valueBinaries = new DsTypeFixedBucketStore(this.rootCanonical, TYPE_REGISTRY_VALUE_BINARY);
        this.valueComplex = new DsTypeFixedBucketStore(this.rootCanonical, TYPE_REGISTRY_VALUE_COMPLEX);
        File regDir = new File(this.rootCanonical, "registry");
        if (!regDir.exists()) regDir.mkdirs();
        this.keyIdToDirId = new DsHashMap(new File(regDir, "keyId_to_dirId.hash"));
        this.keyIdToLeafOrDirId = new DsHashMap(new File(regDir, "keyId_to_leafOrDirId.hash"));
        this.leafIdToValueOrId = new DsHashMap(new File(regDir, "leafId_to_valueOrId.hash"));
        this.dirIdToRingId = new DsHashMap(new File(regDir, "dirId_to_ringId.hash"));
        this.nextSequence = new DsHashMap(new File(regDir, "next_sequence.hash"));
        ensureRootInitialized();
    }

    public String rootDir() { return rootCanonical; }
    public int serverId() { return serverId; }
    public int partitionId() { return partitionId; }

    public long toGlobalNodeId(long internalTypedNodeId) {
        long local = MultiTypeDataId.decodeIndexId(internalTypedNodeId);
        if (local < 0 || local > DsIdLayout.LOCAL_ADDRESS_MASK) {
            throw new IllegalArgumentException("local address out of range: " + local);
        }
        return DsIdLayout.compose(serverId, partitionId, local);
    }

    public long fromGlobalNodeId(long globalRowId, boolean isDirectory) {
        int sId = DsIdLayout.serverId(globalRowId);
        int pId = DsIdLayout.partitionId(globalRowId);
        if (sId != serverId) {
            throw new IllegalArgumentException("serverId mismatch: expected " + serverId + ", got " + sId);
        }
        if (pId != partitionId) {
            throw new IllegalArgumentException("partitionId mismatch: expected " + partitionId + ", got " + pId);
        }
        long local = DsIdLayout.localAddress(globalRowId);
        return isDirectory ? makeDirTypedIdFromLocal(local) : makeLeafTypedId(local);
    }

    private static long makeDirTypedIdFromLocal(long local) {
        if (local == 0L) return rootDirTypedId();
        return MultiTypeDataId.encode(TYPE_REGISTRY_DIR_META, POWER_NODE_BLOCK_DIR, local);
    }
    public DsTypeString keyStrings() { return keyStrings; }
    public DsTypeString valueStrings() { return valueStrings; }
    public DsTypeFixedBucketStore valueBinaries() { return valueBinaries; }

    private static DsRegistryNodeSerializer.NodeMeta readNodeMeta(DsTypeFixedBucketStore store, long nodeIdOrTyped) throws IOException {
        int t = (int)((nodeIdOrTyped >>> 48) & 0xFFFFL);
        if (t != TYPE_REGISTRY_NODE_META && t != TYPE_REGISTRY_DIR_META) {
            throw new IllegalArgumentException("DsRegistry legacy plain-id read fallback removed (P6a G1-G5 passed); got id=" + nodeIdOrTyped);
        }
        return DsRegistryNodeSerializer.readNode(store, nodeIdOrTyped);
    }

    private static void writeNodeMeta(DsTypeFixedBucketStore store, long nodeIdOrTyped, DsRegistryNodeSerializer.NodeMeta m) throws IOException {
        int t = (int)((nodeIdOrTyped >>> 48) & 0xFFFFL);
        if (t != TYPE_REGISTRY_NODE_META && t != TYPE_REGISTRY_DIR_META) {
            throw new IllegalArgumentException("DsRegistry legacy plain-id write fallback removed (P6a G1-G5 passed); got id=" + nodeIdOrTyped);
        }
        DsRegistryNodeSerializer.writeNode(store, nodeIdOrTyped, m);
    }

    private void ensureRootInitialized() throws IOException {
        long rootTyped = rootDirTypedId();
        Long existingRing = dirIdToRingId.get(rootTyped);
        if (existingRing != null && existingRing != 0L) return;
        long stamp = sl.writeLock();
        try {
            long rootTyped2 = rootDirTypedId();
            Long ringSeq = dirIdToRingId.get(rootTyped2);
            if (ringSeq == null || ringSeq == 0L) {
                long ringFileSeq = allocateNextSequenceLocked();
                File ringFile = ringFileFor(ringFileSeq);
                DsFreeRing ring = new DsFreeRing(ringFile, 16);
                ring.close();
                dirIdToRingId.put(rootTyped2, ringFileSeq);
                long ts = System.currentTimeMillis();
                DsRegistryNodeSerializer.NodeMeta rootMeta = new DsRegistryNodeSerializer.NodeMeta(
                        true,
                        DataType.REG_REGISTRY_DIR.getCode(),
                        DataType.REG_REGISTRY_DIR.getCode(),
                        ROOT_KEY_ID_MARKER,
                        0,
                        0,
                        0,
                        ringFileSeq,
                        ts
                );
                DsRegistryNodeSerializer.writeNode(nodeStore, rootTyped2, rootMeta);
                if (nextSequence.get(SEQ_HASH_KEY) == null) {
                    nextSequence.put(SEQ_HASH_KEY, 10L);
                }
            }
        } finally {
            sl.unlockWrite(stamp);
        }
    }

    private File ringFileFor(long ringSeq) {
        File ringDir = new File(rootCanonical, "registry" + File.separator + "rings");
        if (!ringDir.exists()) ringDir.mkdirs();
        return new File(ringDir, "ring_" + Long.toHexString(ringSeq).toUpperCase() + ".free");
    }

    public static List<String> parseKey(String keyPath) {
        if (keyPath == null) throw new IllegalArgumentException("keyPath is null");
        if (!keyPath.startsWith("/")) throw new IllegalArgumentException("keyPath 必须以 '/' 开头，实际：" + keyPath);
        String k = keyPath;
        int utfLen = k.getBytes(StandardCharsets.UTF_8).length;
        if (utfLen > DataType.maxKeyLengthBytes()) {
            throw new IllegalArgumentException("keyPath 超过 4096 bytes 上限：" + utfLen);
        }
        String[] parts = k.split("/", -1);
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (p == null || p.isEmpty()) {
                if (out.isEmpty()) continue;
                throw new IllegalArgumentException("keyPath 中间禁止空段(//)：" + keyPath);
            }
            if ("..".equals(p)) throw new IllegalArgumentException("keyPath 禁止 '..' 段：" + keyPath);
            out.add(p);
        }
        return out;
    }

    static String parentPath(String keyPath) {
        List<String> parts = parseKey(keyPath);
        if (parts.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size() - 1; i++) sb.append('/').append(parts.get(i));
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private long ensureKeyId(String keyPath) throws IOException {
        Long id = keyStrings.getIndexId(keyPath, false);
        if (id != null) return id;
        long stamp = sl.writeLock();
        try {
            return ensureKeyIdLocked(keyPath);
        } finally {
            sl.unlockWrite(stamp);
        }
    }

    private long ensureKeyIdLocked(String keyPath) throws IOException {
        Long id = keyStrings.getIndexId(keyPath, false);
        if (id != null) return id;
        return keyStrings.add(keyPath);
    }

    private long allocateNextSequenceLocked() throws IOException {
        Long seq = nextSequence.get(SEQ_HASH_KEY);
        long next = (seq == null) ? 10L : (seq + 1L);
        nextSequence.put(SEQ_HASH_KEY, next);
        return next;
    }

    public long mkdirs(String parentPath) throws IOException {
        if ("/".equals(parentPath)) return rootDirTypedId();
        List<String> parts = parseKey(parentPath);
        long stamp = sl.tryOptimisticRead();
        long currentDirTypedId = rootDirTypedId();
        StringBuilder pathSoFar = new StringBuilder();
        boolean allHit = true;
        for (String seg : parts) {
            pathSoFar.append('/').append(seg);
            String segKey = pathSoFar.toString();
            Long id0 = keyStrings.getIndexId(segKey, false);
            if (id0 == null) { allHit = false; break; }
            long keyId = id0;
            long typedId = makeDirTypedId(keyId);
            Long ringSeq = dirIdToRingId.get(typedId);
            if (ringSeq != null && ringSeq != 0L) {
                currentDirTypedId = typedId;
                continue;
            }
            allHit = false;
            break;
        }
        if (allHit && sl.validate(stamp)) return currentDirTypedId;
        stamp = sl.writeLock();
        try {
            return mkdirsLocked(parentPath);
        } finally {
            sl.unlockWrite(stamp);
        }
    }

    private long mkdirsLocked(String parentPath) throws IOException {
        if ("/".equals(parentPath)) return rootDirTypedId();
        List<String> parts = parseKey(parentPath);
        long currentDirTypedId = rootDirTypedId();
        StringBuilder pathSoFar = new StringBuilder();
        for (String seg : parts) {
            pathSoFar.append('/').append(seg);
            String segKey = pathSoFar.toString();
            long keyId = ensureKeyIdLocked(segKey);
            long typedId = makeDirTypedId(keyId);
            Long ringSeq = dirIdToRingId.get(typedId);
            if (ringSeq != null && ringSeq != 0L) {
                currentDirTypedId = typedId;
                continue;
            }
            long ringFileSeq = allocateNextSequenceLocked();
            DsFreeRing ring = new DsFreeRing(ringFileFor(ringFileSeq), 16);
            ring.close();
            long ts = System.currentTimeMillis();
            DsRegistryNodeSerializer.NodeMeta meta = new DsRegistryNodeSerializer.NodeMeta(
                    true,
                    DataType.REG_REGISTRY_DIR.getCode(),
                    DataType.REG_REGISTRY_DIR.getCode(),
                    keyId,
                    currentDirTypedId,
                    0,
                    0,
                    ringFileSeq,
                    ts
            );
            DsRegistryNodeSerializer.writeNode(nodeStore, typedId, meta);
            dirIdToRingId.put(typedId, ringFileSeq);
            DsFreeRing parentRing = openRing(currentDirTypedId);
            parentRing.offerUnique(typedId);
            parentRing.close();
            currentDirTypedId = typedId;
        }
        return currentDirTypedId;
    }

    private DsFreeRing openRing(long dirNodeId) throws IOException {
        Long ringSeq = dirIdToRingId.get(dirNodeId);
        if (ringSeq == null || ringSeq <= 0) {
            throw new IOException("DIR nodeId=" + dirNodeId + " 未找到对应 ring（可能非目录或已损坏）");
        }
        return new DsFreeRing(ringFileFor(ringSeq), 16);
    }

    public long putLong(String keyPath, DataType type, long value) throws IOException {
        checkOpen();
        if (type == null) throw new IllegalArgumentException("type is null");
        long stamp = sl.writeLock();
        try {
            long keyId = ensureKeyIdLocked(keyPath);
            String parent = parentPath(keyPath);
            long parentDirTypedId = mkdirsLocked(parent);
            Long existingLeaf = keyIdToLeafOrDirId.get(keyId);
            long leafTypedId;
            if (existingLeaf != null && existingLeaf != 0L) {
                leafTypedId = existingLeaf;
                long leafSeq = MultiTypeDataId.decodeIndexId(leafTypedId);
                leafIdToValueOrId.put(leafSeq, value);
                touchNodeMeta(leafTypedId, DataType.REG_REGISTRY_DIR.getCode(), type.getCode(), keyId, parentDirTypedId, 0, value, 0);
            } else {
                long leafSeq = allocateNextSequenceLocked();
                leafTypedId = makeLeafTypedId(leafSeq);
                leafIdToValueOrId.put(leafSeq, value);
                long ts = System.currentTimeMillis();
                DsRegistryNodeSerializer.NodeMeta meta = new DsRegistryNodeSerializer.NodeMeta(
                        false,
                        DataType.REG_SZ.getCode(),
                        type.getCode(),
                        keyId,
                        parentDirTypedId,
                        0,
                        value,
                        0,
                        ts
                );
                DsRegistryNodeSerializer.writeNode(nodeStore, leafTypedId, meta);
                keyIdToLeafOrDirId.put(keyId, leafTypedId);
                DsFreeRing r = openRing(parentDirTypedId);
                r.offerUnique(leafTypedId);
                r.close();
            }
            return leafTypedId;
        } finally {
            sl.unlockWrite(stamp);
        }
    }

    public long putString(String keyPath, String value, DataType stringType) throws IOException {
        checkOpen();
        if (value == null) value = "";
        Category c = stringType.getCategory();
        if (c != Category.STRING && c != Category.CONFIG && c != Category.SCRIPT && c != Category.DOCS && c != Category.CODE) {
            stringType = DataType.REG_SZ;
        }
        long stamp = sl.writeLock();
        try {
            long keyId = ensureKeyIdLocked(keyPath);
            long valueTypedId = valueStrings.add(value);
            return putInlineOrTypedIdLocked(keyId, keyPath, stringType, valueTypedId, true);
        } finally {
            sl.unlockWrite(stamp);
        }
    }

    public long putBytes(String keyPath, byte[] value, DataType binaryType) throws IOException {
        checkOpen();
        if (value == null) value = new byte[0];
        if (value.length > 0 && value.length < INLINE_BYTES_MAX_LEN) {
            long inline = packSmallBytes(value);
            long stamp = sl.writeLock();
            try {
                long keyId = ensureKeyIdLocked(keyPath);
                putInlineRawLocked(keyId, keyPath, binaryType, inline);
                return keyId;
            } finally {
                sl.unlockWrite(stamp);
            }
        }
        long stamp = sl.writeLock();
        try {
            long keyId = ensureKeyIdLocked(keyPath);
            ByteBuffer buf = ByteBuffer.allocate(VALUE_BIN_HEADER_LEN + value.length).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(value.length);
            buf.put(value);
            byte[] payload = buf.array();
            long typedId = valueBinaries.put("reg_vals", "bin", payload);
            DataType type = (binaryType.getCategory() == Category.BINARY || binaryType.getCategory() == Category.ZIP
                    || binaryType.getCategory() == Category.SETUP || binaryType.getCategory() == Category.EXEC)
                    ? binaryType : DataType.REG_BINARY;
            return putInlineOrTypedIdLocked(keyId, keyPath, type, typedId, true);
        } finally {
            sl.unlockWrite(stamp);
        }
    }

    public long putBytesFromFile(String keyPath, File file, DataType binaryType) throws IOException {
        checkOpen();
        if (file == null || !file.isFile()) throw new IllegalArgumentException("file 不存在或不是文件");
        byte[] b = Files.readAllBytes(file.toPath());
        return putBytes(keyPath, b, binaryType);
    }

    public long putList(String keyPath, List<String> values, DataType listType) throws IOException {
        checkOpen();
        if (listType == null) throw new IllegalArgumentException("listType is null");
        if (listType != DataType.REG_MULTI_SZ && listType != DataType.REG_LIST) {
            throw new IllegalArgumentException("putList 仅支持 REG_MULTI_SZ 或 REG_LIST，实际：" + listType);
        }
        byte[] payload = DsRegistryMultiSzCodec.encode(values);
        if (payload.length > 0 && payload.length < INLINE_BYTES_MAX_LEN) {
            long inline = packSmallBytes(payload);
            long stamp = sl.writeLock();
            try {
                long keyId = ensureKeyIdLocked(keyPath);
                putInlineRawLocked(keyId, keyPath, listType, inline);
                return keyId;
            } finally {
                sl.unlockWrite(stamp);
            }
        }
        long stamp = sl.writeLock();
        try {
            long keyId = ensureKeyIdLocked(keyPath);
            ByteBuffer buf = ByteBuffer.allocate(VALUE_BIN_HEADER_LEN + payload.length).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(payload.length);
            buf.put(payload);
            byte[] frame = buf.array();
            long typedId = valueComplex.put("reg_vals", "bin", frame);
            return putInlineOrTypedIdLocked(keyId, keyPath, listType, typedId, true);
        } finally {
            sl.unlockWrite(stamp);
        }
    }

    private long putInlineRawLocked(long keyId, String keyPath, DataType type, long inlineValue) throws IOException {
        return putInlineOrTypedIdLocked(keyId, keyPath, type, inlineValue, false);
    }

    private long putInlineOrTypedIdLocked(long keyId, String keyPath, DataType type, long payloadOrId, boolean externalRef) throws IOException {
        String parent = parentPath(keyPath);
        long parentDirTypedId = mkdirsLocked(parent);
        Long existingLeaf = keyIdToLeafOrDirId.get(keyId);
        long leafTypedId;
        if (existingLeaf != null && existingLeaf != 0L) {
            leafTypedId = existingLeaf;
            long leafSeq = MultiTypeDataId.decodeIndexId(leafTypedId);
            leafIdToValueOrId.put(leafSeq, payloadOrId);
            touchNodeMeta(leafTypedId, DataType.REG_SZ.getCode(), type.getCode(), keyId, parentDirTypedId, 0, payloadOrId, 0);
        } else {
            long leafSeq = allocateNextSequenceLocked();
            leafTypedId = makeLeafTypedId(leafSeq);
            leafIdToValueOrId.put(leafSeq, payloadOrId);
            long ts = System.currentTimeMillis();
            DsRegistryNodeSerializer.NodeMeta meta = new DsRegistryNodeSerializer.NodeMeta(
                    false,
                    DataType.REG_SZ.getCode(),
                    type.getCode(),
                    keyId,
                    parentDirTypedId,
                    0,
                    payloadOrId,
                    0,
                    ts
            );
            DsRegistryNodeSerializer.writeNode(nodeStore, leafTypedId, meta);
            keyIdToLeafOrDirId.put(keyId, leafTypedId);
            DsFreeRing r = openRing(parentDirTypedId);
            r.offerUnique(leafTypedId);
            r.close();
        }
        return leafTypedId;
    }

    private void touchNodeMeta(long nodeId, int keyCode, int valCode, long keyId, long parentId, long sibling, long valOrId, long ringId) throws IOException {
        DsRegistryNodeSerializer.NodeMeta old = readNodeMeta(nodeStore, nodeId);
        long ts = old == null ? System.currentTimeMillis() : old.timestamp;
        boolean isDir = old != null && old.isDirectory;
        DsRegistryNodeSerializer.NodeMeta m = new DsRegistryNodeSerializer.NodeMeta(
                isDir,
                isDir ? DataType.REG_REGISTRY_DIR.getCode() : keyCode,
                isDir ? DataType.REG_REGISTRY_DIR.getCode() : valCode,
                keyId == 0L ? (old == null ? 0L : old.keyPathId) : keyId,
                parentId == 0L ? (old == null ? 0L : old.parentDirNodeId) : parentId,
                sibling == 0L ? (old == null ? 0L : old.siblingOrder) : sibling,
                valOrId,
                ringId == 0L ? (old == null ? 0L : old.childRingFileId) : ringId,
                ts);
        writeNodeMeta(nodeStore, nodeId, m);
    }

    public static long packSmallBytes(byte[] b) {
        if (b == null) return 0L;
        int n = Math.min(b.length, 8);
        if (n <= 0) return 0L;
        if (n < 8) {
            long v = (((long) n) & 0xFFL) << DataType.INLINE_PACKED_LENGTH_HINT_SHIFT;
            for (int i = 0; i < n; i++) v |= (((long) b[i]) & 0xFFL) << (i * 8);
            return v;
        }
        long v = 0L;
        for (int i = 0; i < 8; i++) v |= (((long) b[i]) & 0xFFL) << (i * 8);
        return v;
    }

    public static byte[] unpackSmallBytes(long v) {
        if (v == 0L) return new byte[0];
        int n = (int) ((v >>> DataType.INLINE_PACKED_LENGTH_HINT_SHIFT) & 0xFFL);
        if (n >= 1 && n <= 7) {
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) out[i] = (byte) ((v >>> (i * 8)) & 0xFFL);
            return out;
        }
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) out[i] = (byte) ((v >>> (i * 8)) & 0xFFL);
        return out;
    }

    public RegistryEntry get(String keyPath) throws IOException {
        checkOpen();
        long stamp = sl.tryOptimisticRead();
        RegistryEntry e = getInternal(keyPath);
        if (sl.validate(stamp)) return e;
        stamp = sl.readLock();
        try {
            return getInternal(keyPath);
        } finally {
            sl.unlockRead(stamp);
        }
    }

    private RegistryEntry getInternal(String keyPath) throws IOException {
        if ("/".equals(keyPath)) {
            long rootTyped = rootDirTypedId();
            DsRegistryNodeSerializer.NodeMeta root = DsRegistryNodeSerializer.readNode(nodeStore, rootTyped);
            if (root == null) return RegistryEntry.EMPTY;
            return new RegistryEntry.Builder()
                    .key("/").directory(true)
                    .dataType(DataType.REG_REGISTRY_DIR)
                    .nodeId(rootTyped).parentDirNodeId(0L).keyPathId(0L)
                    .childRingFileId(root.childRingFileId)
                    .build();
        }
        Long keyId = keyStrings.getIndexId(keyPath, false);
        if (keyId == null) return RegistryEntry.EMPTY;
        long dirTypedId = makeDirTypedId(keyId);
        DsRegistryNodeSerializer.NodeMeta dm = DsRegistryNodeSerializer.readNode(nodeStore, dirTypedId);
        if (dm != null) {
            return new RegistryEntry.Builder()
                    .key(keyPath).directory(true)
                    .dataType(dm.keyDataType())
                    .nodeId(dirTypedId).parentDirNodeId(dm.parentDirNodeId)
                    .keyPathId(keyId)
                    .childRingFileId(dm.childRingFileId)
                    .build();
        }
        Long leafTypedId = keyIdToLeafOrDirId.get(keyId);
        if (leafTypedId == null || leafTypedId == 0L) return RegistryEntry.EMPTY;
        long leafIdx = MultiTypeDataId.decodeIndexId(leafTypedId);
        DsRegistryNodeSerializer.NodeMeta m = readNodeMeta(nodeStore, leafTypedId);
        Long storedVal = leafIdToValueOrId.get(leafIdx);
        if (storedVal == null) storedVal = 0L;
        RegistryEntry.Builder b = new RegistryEntry.Builder()
                .key(keyPath).directory(false)
                .dataType(m == null ? DataType.REG_BINARY : m.valueDataType())
                .nodeId(leafTypedId).parentDirNodeId(m == null ? 0L : m.parentDirNodeId).keyPathId(keyId);
        DataType t = m == null ? DataType.REG_BINARY : m.valueDataType();
        Category c = t.getCategory();
        boolean isBinaryCategory = (c == Category.BINARY || c == Category.ZIP || c == Category.SETUP || c == Category.EXEC);
        if (isBinaryCategory && storedVal != 0) {
            boolean isExternalRef = (MultiTypeDataId.decodeTypeId(storedVal) == TYPE_REGISTRY_VALUE_BINARY);
            if (!isExternalRef) {
                byte[] small = unpackSmallBytes(storedVal);
                if (small.length > 0) {
                    b.bytesValue(small);
                    return b.build();
                }
            }
            byte[] raw = getValueBytes(valueBinaries, storedVal);
            if (raw != null) b.bytesValue(raw);
            return b.build();
        }
        if (c == Category.COMPLEX) {
            byte[] payload = null;
            if (storedVal != 0) {
                boolean isExternalRef = (MultiTypeDataId.decodeTypeId(storedVal) == TYPE_REGISTRY_VALUE_COMPLEX);
                if (!isExternalRef) {
                    byte[] small = unpackSmallBytes(storedVal);
                    if (small.length > 0) {
                        payload = small;
                    }
                }
                if (payload == null) {
                    payload = getValueBytes(valueComplex, storedVal);
                }
            }
            if (payload != null && payload.length > 0) b.bytesValue(payload);
            if (t == DataType.REG_LIST && payload != null && payload.length >= 2) {
                try {
                    b.listValue(DsRegistryMultiSzCodec.decode(payload));
                } catch (IllegalArgumentException ignore) {}
            }
            return b.build();
        }
        if (c == Category.NUMERIC || t == DataType.REG_CHAR_DATETIME) {
            b.inline(storedVal);
            return b.build();
        }
        if (c == Category.STRING || c == Category.CONFIG || c == Category.SCRIPT
                || c == Category.DOCS || c == Category.CODE) {
            if (t == DataType.REG_MULTI_SZ) {
                byte[] payload = null;
                if (storedVal != 0) {
                    boolean isExternalRef = (MultiTypeDataId.decodeTypeId(storedVal) == TYPE_REGISTRY_VALUE_COMPLEX);
                    if (!isExternalRef) {
                        byte[] small = unpackSmallBytes(storedVal);
                        if (small.length > 0) payload = small;
                    }
                    if (payload == null) {
                        payload = getValueBytes(valueComplex, storedVal);
                    }
                }
                if (payload != null && payload.length > 0) b.bytesValue(payload);
                if (payload != null && payload.length >= 2) {
                    try {
                        b.listValue(DsRegistryMultiSzCodec.decode(payload));
                    } catch (IllegalArgumentException ignore) {}
                }
                return b.build();
            }
            if (storedVal != 0) {
                String s = valueStrings.get(storedVal);
                if (s != null) b.stringValue(s);
            }
            return b.build();
        }
        if (storedVal != 0) b.inline(storedVal);
        return b.build();
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("DsRegistry 已关闭");
    }

    private static byte[] getValueBytes(DsTypeFixedBucketStore store, long typedId) throws IOException {
        int p = com.q3lives.ds.util.MultiTypeDataId.decodePower(typedId);
        int maxLen = 1 << p;
        int readLen = Math.min(maxLen, 8 * 1024 * 1024);
        if (readLen < VALUE_BIN_HEADER_LEN) return null;
        byte[] buf = store.get("reg_vals", "bin", typedId, readLen);
        if (buf == null || buf.length < VALUE_BIN_HEADER_LEN) return null;
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        int len = bb.getInt(0);
        if (len < 0 || len > readLen - VALUE_BIN_HEADER_LEN) return null;
        byte[] out = new byte[len];
        System.arraycopy(buf, VALUE_BIN_HEADER_LEN, out, 0, len);
        return out;
    }

    public boolean exists(String keyPath) throws IOException {
        return get(keyPath).isPresent();
    }

    public int migrateLegacyIds() throws IOException {
        checkOpen();
        long stamp = sl.writeLock();
        int migrated = 0;
        java.util.List<long[]> legacy = new java.util.ArrayList<>();
        try {
            Long legacyRootRing = dirIdToRingId.get(ROOT_DIR_NODE_ID);
            if (legacyRootRing != null && legacyRootRing != 0L) {
                long rootTyped = rootDirTypedId();
                Long existing = dirIdToRingId.get(rootTyped);
                if (existing == null || existing == 0L) {
                    dirIdToRingId.put(rootTyped, legacyRootRing.longValue());
                }
                dirIdToRingId.remove(ROOT_DIR_NODE_ID);
                migrated++;
            }
            for (java.util.Iterator<java.util.Map.Entry<Long, Long>> it = keyIdToDirId.entrySet().iterator(); it.hasNext(); ) {
                java.util.Map.Entry<Long, Long> e = it.next();
                long kId = e.getKey();
                long dirNode = e.getValue();
                legacy.add(new long[]{kId, dirNode});
            }
            for (long[] kv : legacy) {
                long kId = kv[0];
                long dirNode = kv[1];
                Long ring = dirIdToRingId.get(dirNode);
                long newTyped = makeDirTypedId(kId);
                if (ring != null && ring != 0L) {
                    Long cur = dirIdToRingId.get(newTyped);
                    if (cur == null || cur == 0L) {
                        dirIdToRingId.put(newTyped, ring.longValue());
                    }
                }
                keyIdToDirId.remove(kId);
                migrated++;
            }
        } finally {
            sl.unlockWrite(stamp);
        }
        return migrated;
    }

    public boolean remove(String keyPath, boolean recursive) throws IOException {
        checkOpen();
        if ("/".equals(keyPath)) {
            throw new IllegalArgumentException("禁止删除注册表根目录 '/'");
        }
        long stamp = sl.writeLock();
        try {
            Long keyId = keyStrings.getIndexId(keyPath, false);
            if (keyId == null) return false;
            long dirTypedId = makeDirTypedId(keyId);
            DsRegistryNodeSerializer.NodeMeta dm = DsRegistryNodeSerializer.readNode(nodeStore, dirTypedId);
            if (dm != null) {
                if (!recursive) {
                    Long ringSeq = dirIdToRingId.get(dirTypedId);
                    long[] children = new long[0];
                    if (ringSeq != null) {
                        DsFreeRing r = openRing(dirTypedId);
                        children = r.snapshot();
                        r.close();
                    }
                    if (children.length > 0) {
                        throw new IllegalStateException("目录非空，请 recursive=true 才能删除：" + keyPath);
                    }
                }
                deleteDirRecursive(keyPath, dirTypedId, keyId);
                return true;
            }
            Long leafTypedId = keyIdToLeafOrDirId.get(keyId);
            if (leafTypedId == null || leafTypedId == 0L) return false;
            long leafIdx = MultiTypeDataId.decodeIndexId(leafTypedId);
            DsRegistryNodeSerializer.NodeMeta m = readNodeMeta(nodeStore, leafTypedId);
            long parent = (m == null) ? 0L : m.parentDirNodeId;
            leafIdToValueOrId.remove(leafIdx);
            keyIdToLeafOrDirId.remove(keyId);
            if (m != null) {
                DataType t = m.valueDataType();
                Category c = t.getCategory();
                long pay = m.valueOrValueId;
                if (pay != 0) {
                    if (c == Category.STRING || c == Category.CONFIG || c == Category.SCRIPT || c == Category.DOCS || c == Category.CODE) {
                        try { valueStrings.remove(pay); } catch (Exception ignore) {}
                    } else if (c == Category.BINARY || c == Category.ZIP || c == Category.SETUP || c == Category.EXEC) {
                        try { valueBinaries.remove("reg_vals", "bin", pay); } catch (Exception ignore) {}
                    } else if (c == Category.COMPLEX) {
                        try { valueComplex.remove("reg_vals", "bin", pay); } catch (Exception ignore) {}
                    }
                }
            }
            if (parent != 0L) {
                try {
                    DsFreeRing r = openRing(parent);
                    r.remove(leafTypedId);
                    r.close();
                } catch (Exception ignore) {}
            }
            try { keyStrings.remove(keyId); } catch (Exception ignore) {}
            return true;
        } finally {
            sl.unlockWrite(stamp);
        }
    }

    private void deleteDirRecursive(String path, long dirNodeId, long pathKeyId) throws IOException {
        Long ringSeq = dirIdToRingId.get(dirNodeId);
        long[] children = new long[0];
        if (ringSeq != null) {
            DsFreeRing r = openRing(dirNodeId);
            children = r.snapshot();
            r.close();
        }
        String prefix = "/".equals(path) ? "" : path;
        for (long childId : children) {
            DsRegistryNodeSerializer.NodeMeta cm = readNodeMeta(nodeStore, childId);
            if (cm == null) continue;
            String childKey;
            if (cm.keyPathId != 0L) {
                String k = keyStrings.get(cm.keyPathId);
                if (k != null && !k.isEmpty()) { childKey = k; }
                else { childKey = prefix + "/__node_" + Long.toHexString(childId); }
            } else {
                childKey = prefix + "/__node_" + Long.toHexString(childId);
            }
            if (cm.isDirectory) {
                long cDirTyped = makeDirTypedId(cm.keyPathId);
                Long cRing = dirIdToRingId.get(cDirTyped);
                if (cRing == null || cRing == 0L) {
                    Long l = dirIdToRingId.get(childId);
                    if (l != null && l != 0L) dirIdToRingId.putIfAbsent(cDirTyped, l);
                }
                deleteDirRecursive(childKey, cDirTyped, cm.keyPathId);
            } else {
                Long cKeyId = cm.keyPathId == 0L ? null : cm.keyPathId;
                if (cKeyId == null) continue;
                Long cLeafStored = keyIdToLeafOrDirId.get(cKeyId);
                boolean cIsDir = false;
                if (cLeafStored == null || cLeafStored == 0L) {
                    long cDirTyped2 = makeDirTypedId(cKeyId);
                    DsRegistryNodeSerializer.NodeMeta cdm2 = DsRegistryNodeSerializer.readNode(nodeStore, cDirTyped2);
                    if (cdm2 != null) cIsDir = true;
                    if (!cIsDir) continue;
                }
                if (cIsDir) {
                    long cDirTyped3 = makeDirTypedId(cKeyId);
                    Long r3 = dirIdToRingId.get(cDirTyped3);
                    if (r3 == null || r3 == 0L) {
                        Long l3 = dirIdToRingId.get(childId);
                        if (l3 != null && l3 != 0L) dirIdToRingId.putIfAbsent(cDirTyped3, l3);
                    }
                    deleteDirRecursive(childKey, cDirTyped3, cm.keyPathId);
                    continue;
                }
                long cIdx = MultiTypeDataId.decodeIndexId(cLeafStored);
                long cParent = cm.parentDirNodeId;
                leafIdToValueOrId.remove(cIdx);
                keyIdToLeafOrDirId.remove(cKeyId);
                DataType t = cm.valueDataType();
                Category c = t.getCategory();
                long pay = cm.valueOrValueId;
                if (pay != 0) {
                    if (c == Category.STRING || c == Category.CONFIG || c == Category.SCRIPT || c == Category.DOCS || c == Category.CODE) {
                        try { valueStrings.remove(pay); } catch (Exception ignore) {}
                    } else if (c == Category.BINARY || c == Category.ZIP || c == Category.SETUP || c == Category.EXEC) {
                        try { valueBinaries.remove("reg_vals", "bin", pay); } catch (Exception ignore) {}
                    } else if (c == Category.COMPLEX) {
                        try { valueComplex.remove("reg_vals", "bin", pay); } catch (Exception ignore) {}
                    }
                }
                if (cParent != 0L) {
                    try {
                        DsFreeRing pr = openRing(cParent);
                        pr.remove(cLeafStored);
                        pr.close();
                    } catch (Exception ignore) {}
                }
                try { keyStrings.remove(cKeyId); } catch (Exception ignore) {}
            }
        }
        if (ringSeq != null) {
            File f = ringFileFor(ringSeq);
            if (f.exists()) f.delete();
            dirIdToRingId.remove(dirNodeId);
        }
        if (pathKeyId != 0L) keyIdToDirId.remove(pathKeyId);
        long parentId = 0L;
        DsRegistryNodeSerializer.NodeMeta meta = readNodeMeta(nodeStore, dirNodeId);
        if (meta != null) { parentId = meta.parentDirNodeId; }
        if (parentId != 0L) {
            try {
                DsFreeRing r = openRing(parentId);
                r.remove(dirNodeId);
                r.close();
            } catch (Exception ignore) {
                if (pathKeyId != 0L) {
                    long expectedTyped = makeDirTypedId(pathKeyId);
                    if (expectedTyped != dirNodeId) {
                        try {
                            DsFreeRing r = openRing(parentId);
                            r.remove(expectedTyped);
                            r.close();
                        } catch (Exception ignore2) {}
                    }
                }
            }
        }
        if (pathKeyId != 0L) { try { keyStrings.remove(pathKeyId); } catch (Exception ignore) {} }
    }

    public List<String> listChildren(String dirPath) throws IOException {
        checkOpen();
        long stamp = sl.tryOptimisticRead();
        List<String> out1 = null;
        {
            RegistryEntry dir = getInternal(dirPath);
            if (!(dir.isPresent() && dir.isDirectory())) {
                if (sl.validate(stamp)) return Collections.emptyList();
            } else {
                long dirNodeId = isRootNodeId(dir.nodeId()) ? dir.nodeId() : resolveDirId(dirPath);
                if (dirNodeId <= 0) {
                    if (sl.validate(stamp)) return Collections.emptyList();
                } else {
                    long[] children = snapshotChildren(dirNodeId);
                    List<String> out = new ArrayList<>(children.length);
                    for (long cid : children) {
                        DsRegistryNodeSerializer.NodeMeta cm = readNodeMeta(nodeStore, cid);
                        if (cm == null) continue;
                        if (isRootNodeId(dirNodeId) && cm.keyPathId == 0L && cm.isDirectory) continue;
                        if (cm.keyPathId == 0L) continue;
                        String k = keyStrings.get(cm.keyPathId);
                        if (k == null) continue;
                        int idx = k.lastIndexOf('/');
                        String seg = idx < 0 ? k : k.substring(idx + 1);
                        if (seg == null || seg.isEmpty()) continue;
                        out.add(seg);
                    }
                    if (sl.validate(stamp)) return out;
                    out1 = out;
                }
            }
        }
        stamp = sl.readLock();
        try {
            RegistryEntry dir = getInternal(dirPath);
            if (!dir.isPresent() || !dir.isDirectory()) return Collections.emptyList();
            long dirNodeId = isRootNodeId(dir.nodeId()) ? dir.nodeId() : resolveDirId(dirPath);
            if (dirNodeId <= 0) return Collections.emptyList();
            long[] children = snapshotChildren(dirNodeId);
            List<String> out = new ArrayList<>(children.length);
            for (long cid : children) {
                DsRegistryNodeSerializer.NodeMeta cm = readNodeMeta(nodeStore, cid);
                if (cm == null) continue;
                if (isRootNodeId(dirNodeId) && cm.keyPathId == 0L && cm.isDirectory) continue;
                if (cm.keyPathId == 0L) continue;
                String k = keyStrings.get(cm.keyPathId);
                if (k == null) continue;
                int idx = k.lastIndexOf('/');
                String seg = idx < 0 ? k : k.substring(idx + 1);
                if (seg == null || seg.isEmpty()) continue;
                out.add(seg);
            }
            return out;
        } finally {
            sl.unlockRead(stamp);
        }
    }

    public List<RegistryEntry> listChildrenEntries(String dirPath) throws IOException {
        checkOpen();
        long stamp = sl.readLock();
        try {
            RegistryEntry dir = getInternal(dirPath);
            if (!dir.isPresent() || !dir.isDirectory()) return Collections.emptyList();
            long dirNodeId = isRootNodeId(dir.nodeId()) ? dir.nodeId() : resolveDirId(dirPath);
            if (dirNodeId <= 0) return Collections.emptyList();
            long[] children = snapshotChildren(dirNodeId);
            List<RegistryEntry> out = new ArrayList<>(children.length);
            String dirPrefix = "/".equals(dirPath) ? "" : dirPath;
            for (long cid : children) {
                DsRegistryNodeSerializer.NodeMeta cm = readNodeMeta(nodeStore, cid);
                if (cm == null) continue;
                String key;
                if (cm.keyPathId == 0L) {
                    if (isRootNodeId(dirNodeId) && cm.isDirectory) {
                        key = "/";
                    } else {
                        continue;
                    }
                } else {
                    key = keyStrings.get(cm.keyPathId);
                }
                if (key == null || key.isEmpty()) continue;
                String segKey;
                int li = key.lastIndexOf('/');
                if (li >= 0 && li < key.length() - 1) segKey = dirPrefix + "/" + key.substring(li + 1);
                else segKey = key;
                RegistryEntry e;
                if (cm.isDirectory) {
                    e = new RegistryEntry.Builder()
                            .key(segKey).directory(true).dataType(DataType.REG_REGISTRY_DIR)
                            .nodeId(cid).parentDirNodeId(cm.parentDirNodeId).keyPathId(cm.keyPathId)
                            .childRingFileId(cm.childRingFileId).build();
                } else {
                    Long storedVal = null;
                    if (isTypedNodeId(cid)) {
                        long cIdx = MultiTypeDataId.decodeIndexId(cid);
                        storedVal = leafIdToValueOrId.get(cIdx);
                    }
                    if (storedVal == null) {
                        storedVal = leafIdToValueOrId.get(cid);
                    }
                    RegistryEntry.Builder b = new RegistryEntry.Builder()
                            .key(segKey).directory(false).dataType(cm.valueDataType())
                            .nodeId(cid).parentDirNodeId(cm.parentDirNodeId).keyPathId(cm.keyPathId);
                    if (storedVal != null) {
                        Category cat = cm.valueDataType().getCategory();
                        if (cat == Category.NUMERIC || cm.valueDataType() == DataType.REG_CHAR_DATETIME) b.inline(storedVal);
                        else if (cat == Category.STRING || cat == Category.CONFIG || cat == Category.SCRIPT || cat == Category.DOCS || cat == Category.CODE) {
                            String s = valueStrings.get(storedVal);
                            if (s != null) b.stringValue(s);
                        } else if (cat == Category.COMPLEX) {
                            byte[] raw = getValueBytes(valueComplex, storedVal);
                            if (raw != null) b.bytesValue(raw);
                        } else {
                            byte[] small = unpackSmallBytes(storedVal);
                            if (small.length > 0 && small.length <= INLINE_BYTES_MAX_LEN && (cat == Category.BINARY || cat == Category.ZIP || cat == Category.SETUP || cat == Category.EXEC)) {
                                b.bytesValue(small);
                            } else if (storedVal != 0) {
                                byte[] raw = getValueBytes(valueBinaries, storedVal);
                                if (raw != null) b.bytesValue(raw);
                            }
                        }
                    }
                    e = b.build();
                }
                out.add(e);
            }
            return out;
        } finally {
            sl.unlockRead(stamp);
        }
    }

    private long resolveDirId(String path) throws IOException {
        if ("/".equals(path)) return rootDirTypedId();
        Long keyId = keyStrings.getIndexId(path, false);
        if (keyId == null) return 0L;
        long typedId = makeDirTypedId(keyId);
        Long ring = dirIdToRingId.get(typedId);
        if (ring != null && ring != 0L) return typedId;
        return 0L;
    }

    private long[] snapshotChildren(long dirNodeId) throws IOException {
        DsFreeRing r = openRing(dirNodeId);
        try { return r.snapshot(); } finally { r.close(); }
    }

    public void walk(RegistryWalker.RegistryEntryVisitor visitor, int maxDepth, Predicate<String> pathFilter) throws Exception {
        walk(visitor, maxDepth, pathFilter, false);
    }

    public void walk(RegistryWalker.RegistryEntryVisitor visitor, int maxDepth, Predicate<String> pathFilter, boolean withGlobals) throws Exception {
        checkOpen();
        long stamp = sl.readLock();
        try {
            walkInternal("/", 0, maxDepth, pathFilter, visitor, withGlobals);
        } finally {
            sl.unlockRead(stamp);
        }
    }

    private RegistryEntry wrapWithGlobal(RegistryEntry e) {
        if (e == null || !e.isPresent()) return e;
        long nid = e.nodeId();
        if (!isTypedNodeId(nid)) return e;
        long g;
        try {
            g = toGlobalNodeId(nid);
        } catch (IllegalArgumentException iae) {
            return e;
        }
        if (g == 0L) return e;
        RegistryEntry.Builder b = new RegistryEntry.Builder()
                .key(e.key()).directory(e.isDirectory()).dataType(e.dataType())
                .nodeId(e.nodeId()).parentDirNodeId(e.parentDirNodeId()).keyPathId(e.keyPathId())
                .childRingFileId(e.childRingFileId()).globalNodeId(g);
        OptionalLong iv = e.inlineValueRaw();
        if (iv.isPresent()) b.inline(iv.getAsLong());
        e.stringValue().ifPresent(b::stringValue);
        e.bytesValue().ifPresent(b::bytesValue);
        e.listValueRaw().ifPresent(b::listValue);
        return b.build();
    }

    private boolean walkInternal(String path, int depth, int maxDepth, Predicate<String> filter, RegistryWalker.RegistryEntryVisitor visitor, boolean withGlobals) throws Exception {
        if (maxDepth >= 0 && depth > maxDepth) return true;
        if (filter != null && !filter.test(path)) return true;
        RegistryEntry entry = getInternal(path);
        if (!entry.isPresent()) return true;
        if (withGlobals) entry = wrapWithGlobal(entry);
        boolean shouldContinue = visitor.onEntry(entry, depth);
        if (!shouldContinue) return false;
        if (!entry.isDirectory()) return true;
        long dirNodeId = isRootNodeId(entry.nodeId()) ? entry.nodeId() : resolveDirId(path);
        if (dirNodeId <= 0) return true;
        long[] childrenIds = snapshotChildren(dirNodeId);
        String prefix = "/".equals(path) ? "" : path;
        for (long cid : childrenIds) {
            DsRegistryNodeSerializer.NodeMeta cm = readNodeMeta(nodeStore, cid);
            if (cm == null) continue;
            String childKey;
            if (cm.keyPathId == 0L) {
                if (isRootNodeId(dirNodeId) && cm.isDirectory) childKey = "/";
                else continue;
            } else {
                String k = keyStrings.get(cm.keyPathId);
                if (k == null) continue;
                childKey = k;
            }
            if (childKey == null || childKey.isEmpty()) continue;
            if (!childKey.startsWith(prefix == "" ? "/" : prefix + "/")) {
                int li = childKey.lastIndexOf('/');
                String name = (li < 0 || li >= childKey.length() - 1) ? childKey : childKey.substring(li + 1);
                childKey = (prefix.isEmpty()) ? "/" + name : prefix + "/" + name;
            }
            boolean goOn = walkInternal(childKey, depth + 1, maxDepth, filter, visitor, withGlobals);
            if (!goOn) return false;
        }
        return true;
    }

    public void sync() {
        checkOpen();
        long stamp = sl.writeLock();
        try {
            nodeStore.sync();
            valueBinaries.sync();
            valueComplex.sync();
        } finally {
            sl.unlockWrite(stamp);
        }
    }

    @Override
    public void close() throws IOException {
        long stamp = sl.writeLock();
        try {
            closed = true;
            try { keyStrings.close(); } catch (Exception ignore) {}
            try { valueStrings.close(); } catch (Exception ignore) {}
            try { nodeStore.close(); } catch (Exception ignore) {}
            try { valueBinaries.close(); } catch (Exception ignore) {}
            try { valueComplex.close(); } catch (Exception ignore) {}
            try { keyIdToDirId.close(); } catch (Exception ignore) {}
            try { keyIdToLeafOrDirId.close(); } catch (Exception ignore) {}
            try { leafIdToValueOrId.close(); } catch (Exception ignore) {}
            try { dirIdToRingId.close(); } catch (Exception ignore) {}
            try { nextSequence.close(); } catch (Exception ignore) {}
        } finally {
            sl.unlockWrite(stamp);
        }
    }

    public List<RegistryEntry> findByPrefix(String dirPrefix) throws IOException {
        return findByPrefix(dirPrefix, Integer.MAX_VALUE, 1000, null);
    }

    public List<RegistryEntry> findByPrefix(String dirPrefix, int maxDepth, int limit, Predicate<String> pathFilter) throws IOException {
        return findByPrefix(dirPrefix, maxDepth, limit, pathFilter, false);
    }

    public List<RegistryEntry> findByPrefix(String dirPrefix, int maxDepth, int limit, Predicate<String> pathFilter, boolean withGlobals) throws IOException {
        checkOpen();
        if (limit <= 0) limit = 1000;
        if (maxDepth < 0) maxDepth = Integer.MAX_VALUE;
        List<RegistryEntry> out = new ArrayList<>(Math.min(limit, 128));
        long stamp = sl.tryOptimisticRead();
        boolean ok = false;
        try {
            findByPrefixInternal(dirPrefix, maxDepth, limit, pathFilter, out, withGlobals);
            ok = sl.validate(stamp);
        } catch (Exception ignore) { ok = false; }
        if (ok) return out;
        stamp = sl.readLock();
        try {
            List<RegistryEntry> out2 = new ArrayList<>(Math.min(limit, 128));
            try {
                findByPrefixInternal(dirPrefix, maxDepth, limit, pathFilter, out2, withGlobals);
            } catch (Exception e) {
                if (e instanceof IOException) throw (IOException) e;
                throw new IOException("findByPrefix walk 失败：" + e.getMessage(), e);
            }
            return out2;
        } finally {
            sl.unlockRead(stamp);
        }
    }

    public List<String> findByPrefixKeys(String dirPrefix, int maxDepth, int limit, Predicate<String> pathFilter) throws IOException {
        return new ArrayList<>(findByPrefixKeys(dirPrefix, maxDepth, limit, pathFilter, false).keySet());
    }

    public Map<String, Long> findByPrefixKeys(String dirPrefix, int maxDepth, int limit, Predicate<String> pathFilter, boolean withGlobals) throws IOException {
        checkOpen();
        if (limit <= 0) limit = 1000;
        if (maxDepth < 0) maxDepth = Integer.MAX_VALUE;
        java.util.LinkedHashMap<String, Long> out = new java.util.LinkedHashMap<>(Math.min(limit, 128));
        long stamp = sl.tryOptimisticRead();
        boolean ok = false;
        try {
            findByPrefixKeysInternal(dirPrefix, maxDepth, limit, pathFilter, out, withGlobals);
            ok = sl.validate(stamp);
        } catch (Exception ignore) { ok = false; }
        if (ok) return out;
        stamp = sl.readLock();
        try {
            java.util.LinkedHashMap<String, Long> out2 = new java.util.LinkedHashMap<>(Math.min(limit, 128));
            try {
                findByPrefixKeysInternal(dirPrefix, maxDepth, limit, pathFilter, out2, withGlobals);
            } catch (Exception e) {
                if (e instanceof IOException) throw (IOException) e;
                throw new IOException("findByPrefixKeys walk 失败：" + e.getMessage(), e);
            }
            return out2;
        } finally {
            sl.unlockRead(stamp);
        }
    }

    public List<String> listKeys(String dirPrefix, int maxDepth) throws IOException {
        return new ArrayList<>(listKeys(dirPrefix, maxDepth, false).keySet());
    }

    public Map<String, Long> listKeys(String dirPrefix, int maxDepth, boolean withGlobals) throws IOException {
        checkOpen();
        java.util.LinkedHashMap<String, Long> out = new java.util.LinkedHashMap<>(128);
        long stamp;
        boolean ok;
        try {
            stamp = sl.tryOptimisticRead();
            listKeysInternal(dirPrefix, maxDepth, Integer.MAX_VALUE, null, out, withGlobals);
            ok = sl.validate(stamp);
        } catch (Exception ignore) { ok = false; }
        if (ok) return out;
        stamp = sl.readLock();
        try {
            java.util.LinkedHashMap<String, Long> out2 = new java.util.LinkedHashMap<>(Math.min(maxDepth, 128));
            try {
                listKeysInternal(dirPrefix, maxDepth, Integer.MAX_VALUE, null, out2, withGlobals);
            } catch (Exception e) {
                if (e instanceof IOException) throw (IOException) e;
                throw new IOException("listKeys walk 失败：" + e.getMessage(), e);
            }
            return out2;
        } finally {
            sl.unlockRead(stamp);
        }
    }

    public Map<String, Long> listChildrenGlobalIds(String dirPath) throws IOException {
        checkOpen();
        long stamp = sl.tryOptimisticRead();
        java.util.LinkedHashMap<String, Long> out1 = null;
        {
            RegistryEntry dir = getInternal(dirPath);
            if (!(dir.isPresent() && dir.isDirectory())) {
                if (sl.validate(stamp)) return Collections.emptyMap();
            } else {
                long dirNodeId = isRootNodeId(dir.nodeId()) ? dir.nodeId() : resolveDirId(dirPath);
                if (dirNodeId <= 0) {
                    if (sl.validate(stamp)) return Collections.emptyMap();
                } else {
                    long[] children = snapshotChildren(dirNodeId);
                    java.util.LinkedHashMap<String, Long> out = new java.util.LinkedHashMap<>(children.length);
                    for (long cid : children) {
                        DsRegistryNodeSerializer.NodeMeta cm = readNodeMeta(nodeStore, cid);
                        if (cm == null) continue;
                        if (isRootNodeId(dirNodeId) && cm.keyPathId == 0L && cm.isDirectory) continue;
                        if (cm.keyPathId == 0L) continue;
                        String k = keyStrings.get(cm.keyPathId);
                        if (k == null) continue;
                        int idx = k.lastIndexOf('/');
                        String seg = idx < 0 ? k : k.substring(idx + 1);
                        if (seg == null || seg.isEmpty()) continue;
                        long g;
                        try { g = toGlobalNodeId(cid); } catch (IllegalArgumentException iae) { g = 0L; }
                        if (g != 0L) out.put(seg, g);
                    }
                    if (sl.validate(stamp)) return out;
                    out1 = out;
                }
            }
        }
        stamp = sl.readLock();
        try {
            if (out1 != null) return out1;
            RegistryEntry dir = getInternal(dirPath);
            if (!dir.isPresent() || !dir.isDirectory()) return Collections.emptyMap();
            long dirNodeId = isRootNodeId(dir.nodeId()) ? dir.nodeId() : resolveDirId(dirPath);
            if (dirNodeId <= 0) return Collections.emptyMap();
            long[] children = snapshotChildren(dirNodeId);
            java.util.LinkedHashMap<String, Long> out = new java.util.LinkedHashMap<>(children.length);
            for (long cid : children) {
                DsRegistryNodeSerializer.NodeMeta cm = readNodeMeta(nodeStore, cid);
                if (cm == null) continue;
                if (isRootNodeId(dirNodeId) && cm.keyPathId == 0L && cm.isDirectory) continue;
                if (cm.keyPathId == 0L) continue;
                String k = keyStrings.get(cm.keyPathId);
                if (k == null) continue;
                int idx = k.lastIndexOf('/');
                String seg = idx < 0 ? k : k.substring(idx + 1);
                if (seg == null || seg.isEmpty()) continue;
                long g;
                try { g = toGlobalNodeId(cid); } catch (IllegalArgumentException iae) { g = 0L; }
                if (g != 0L) out.put(seg, g);
            }
            return out;
        } finally {
            sl.unlockRead(stamp);
        }
    }

    private void listKeysInternal(String dirPrefix, int maxDepth, int limit, Predicate<String> pathFilter,
                                  Map<String, Long> result, boolean withGlobals) throws Exception {
        String norm = (dirPrefix == null || dirPrefix.isEmpty()) ? "/" : dirPrefix;
        RegistryEntry dir = getInternal(norm);
        int baseDepth;
        String basePath;
        if (dir.isPresent() && dir.isDirectory()) {
            baseDepth = "/".equals(norm) ? 0 : parseKey(norm).size();
            basePath = norm;
        } else {
            String parent = parentPath(norm);
            basePath = parent;
            baseDepth = "/".equals(parent) ? 0 : parseKey(parent).size();
        }
        final int stopAt = limit;
        final int startDepth = baseDepth;
        final int maxD = maxDepth;
        final String rootPref = basePath;
        final String matchPrefix = norm;
        final Predicate<String> userFilter = pathFilter;
        Predicate<String> compoundFilter = p -> {
            if (p == null) return false;
            if (!p.startsWith(rootPref)) return false;
            if (userFilter != null && !userFilter.test(p)) return false;
            return true;
        };
        RegistryWalker.RegistryEntryVisitor visitor = (entry, depthIgnored) -> {
            if (result.size() >= stopAt) return false;
            String key = entry.key();
            try {
                int rel = "/".equals(rootPref) ? parseKey(key).size() : parseKey(key).size() - startDepth;
                if (rel < 0) return true;
                if (rel > maxD) return true;
            } catch (Exception ignore) { return true; }
            if (matchPrefix != null && !"/".equals(matchPrefix) && !key.startsWith(matchPrefix)) return true;
            if (withGlobals) {
                RegistryEntry wrapped = wrapWithGlobal(entry);
                result.put(key, wrapped.globalNodeId());
            } else {
                result.put(key, 0L);
            }
            return true;
        };
        int baseRelDepth = "/".equals(basePath) ? 0 : parseKey(basePath).size();
        walkInternal(basePath, baseRelDepth, -1, compoundFilter, visitor, withGlobals);
    }

    private void findByPrefixInternal(String dirPrefix, int maxDepth, int limit, Predicate<String> pathFilter,
                                      List<RegistryEntry> result, boolean withGlobals) throws Exception {
        String norm = (dirPrefix == null || dirPrefix.isEmpty()) ? "/" : dirPrefix;
        RegistryEntry dir = getInternal(norm);
        int baseDepth;
        String basePath;
        if (dir.isPresent() && dir.isDirectory()) {
            baseDepth = "/".equals(norm) ? 0 : parseKey(norm).size();
            basePath = norm;
        } else {
            String parent = parentPath(norm);
            basePath = parent;
            baseDepth = "/".equals(parent) ? 0 : parseKey(parent).size();
        }
        final int stopAt = limit;
        final int startDepth = baseDepth;
        final int maxD = maxDepth;
        final String rootPref = basePath;
        final String matchPrefix = norm;
        final Predicate<String> userFilter = pathFilter;
        Predicate<String> compoundFilter = p -> {
            if (p == null) return false;
            if (!p.startsWith(rootPref)) return false;
            if (userFilter != null && !userFilter.test(p)) return false;
            return true;
        };
        RegistryWalker.RegistryEntryVisitor visitor = (entry, depthIgnored) -> {
            try {
                int rel = "/".equals(rootPref) ? parseKey(entry.key()).size() : parseKey(entry.key()).size() - startDepth;
                if (rel < 0) return true;
                if (rel > maxD) return true;
                if (rel == 0 && matchPrefix != null && !"/".equals(matchPrefix)) {
                    if (matchPrefix.equals(entry.key()) && entry.isPresent() && entry.isDirectory()) return true;
                }
            } catch (Exception ignore) { return true; }
            if (result.size() >= stopAt) return false;
            if (matchPrefix != null && !"/".equals(matchPrefix) && !entry.key().startsWith(matchPrefix)) return true;
            result.add(withGlobals ? wrapWithGlobal(entry) : entry);
            return true;
        };
        int baseRelDepth = "/".equals(basePath) ? 0 : parseKey(basePath).size();
        walkInternal(basePath, baseRelDepth, -1, compoundFilter, visitor, withGlobals);
    }

    private void findByPrefixKeysInternal(String dirPrefix, int maxDepth, int limit, Predicate<String> pathFilter,
                                          Map<String, Long> result, boolean withGlobals) throws Exception {
        String norm = (dirPrefix == null || dirPrefix.isEmpty()) ? "/" : dirPrefix;
        RegistryEntry dir = getInternal(norm);
        int baseDepth;
        String basePath;
        if (dir.isPresent() && dir.isDirectory()) {
            baseDepth = "/".equals(norm) ? 0 : parseKey(norm).size();
            basePath = norm;
        } else {
            String parent = parentPath(norm);
            basePath = parent;
            baseDepth = "/".equals(parent) ? 0 : parseKey(parent).size();
        }
        final int stopAt = limit;
        final int startDepth = baseDepth;
        final int maxD = maxDepth;
        final String rootPref = basePath;
        final String matchPrefix = norm;
        final Predicate<String> userFilter = pathFilter;
        Predicate<String> compoundFilter = p -> {
            if (p == null) return false;
            if (!p.startsWith(rootPref)) return false;
            if (userFilter != null && !userFilter.test(p)) return false;
            return true;
        };
        RegistryWalker.RegistryEntryVisitor visitor = (entry, depthIgnored) -> {
            if (result.size() >= stopAt) return false;
            String key = entry.key();
            try {
                int rel = "/".equals(rootPref) ? parseKey(key).size() : parseKey(key).size() - startDepth;
                if (rel < 0) return true;
                if (rel > maxD) return true;
                if (rel == 0 && matchPrefix != null && !"/".equals(matchPrefix)) {
                    if (matchPrefix.equals(key) && entry.isPresent() && entry.isDirectory()) return true;
                }
            } catch (Exception ignore) { return true; }
            if (matchPrefix != null && !"/".equals(matchPrefix) && !key.startsWith(matchPrefix)) return true;
            if (withGlobals) {
                RegistryEntry wrapped = wrapWithGlobal(entry);
                result.put(key, wrapped.globalNodeId());
            } else {
                result.put(key, 0L);
            }
            return true;
        };
        int baseRelDepth = "/".equals(basePath) ? 0 : parseKey(basePath).size();
        walkInternal(basePath, baseRelDepth, -1, compoundFilter, visitor, withGlobals);
    }
}
