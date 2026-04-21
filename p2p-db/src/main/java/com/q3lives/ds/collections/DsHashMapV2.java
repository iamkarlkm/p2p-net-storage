package com.q3lives.ds.collections;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.core.DsObject;
import java.io.File;
import java.io.IOException;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * V2 版 long -> long 哈希映射。
 *
 * <p>核心变化：</p>
 * <ul>
 * <li>统一使用 256-bit 扩展哈希，按 16 -> 32 -> 64 -> 128 -> 256 前缀逐级升级。</li>
 * <li>slot 统一只存 8 字节引用：要么指向 entry，要么指向 child node。</li>
 * <li>key/value/next 统一存放在固定长度 entry 记录中，并实现 free-ring 回收。</li>
 * </ul>
 */
public class DsHashMapV2 extends DsObject implements Map<Long, Long> {
    public interface HashBytesProvider {
        void fillHashBytes(long key, byte[] out);
    }

    public enum SyncMode {
        SYSTEM_AUTO,
        WRITE_REQUESTS,
        SECONDS,
        STRONG_100MS,
        MANUAL
    }

    public interface LongLongConsumer {
        void accept(long key, long value);
    }

    private static final int[] SIGNED_ASC_SLOTS = buildSignedAscSlots();

    public static record DebugStats(
        long totalSize,
        long rootLocalSize,
        long rootNextNodeId,
        long totalNodeCount,
        long nextEntryId,
        long freeEntryId,
        long freeEntryCount,
        long usedEntryCount
    ) {
    }

    public static record FastPutStats(
        long lastHitCount,
        long quickHitCount,
        long missCount,
        long rejectedCount,
        long invalidatedCount,
        int quickCacheSize,
        int quickCacheCapacity
    ) {
    }

    private static record FastPutCache(int tierIndex, long nodeId, int level) {
    }

    private static final HashBytesProvider DEFAULT_HASH_BYTES_PROVIDER = DsHashMapV2::fillDefaultHashBytes;

    private static final byte[] MAGIC = new byte[] {'.', 'M', 'P', '2'};
    private static final int HEADER_SIZE = DsFixedBucketStore.HEADER_SIZE;
    private static final int HDR_MAGIC = 0;
    private static final int HDR_VALUE_SIZE = 4;
    private static final int HDR_NEXT_NODE_ID = 8;
    private static final int HDR_LOCAL_SIZE = 16;
    private static final int HDR_HASH_PROVIDER_ID = 24;

    private static final int STATE_EMPTY = 0;
    private static final int STATE_VALUE = 1;
    private static final int STATE_CHILD = 2;
    private static final int STATE_NEXT_LEVEL = 3;

    private static final int BITMAP_BYTES = 64;
    private static final int SLOT_PAYLOAD_BYTES = 8;
    private static final int NODE_SIZE = BITMAP_BYTES + 256 * SLOT_PAYLOAD_BYTES;
    private static final int HASH_BYTES = 32;
    private static final int[] HASH_LEVEL_ENDS = {2, 4, 8, 16, 32};

    private static final int DEFAULT_QUICK_CACHE_SIZE = Math.max(8, Integer.getInteger("ds.hashmapv2.quickCacheSize", 256));

    private long nextNodeId;
    private long localSize;
    private final long[] zeroNode;
    private final int tierIndex;
    private final int hashOffset;
    private final int hashEnd;
    private final boolean root;
    private final DsHashMapV2 rootMap;
    private final EntryStore entryStore;
    private final HashBytesProvider hashBytesProvider;
    private final long hashProviderId;
    private final DsHashMapV2 nextHashMap;
    private volatile SyncMode syncMode = SyncMode.MANUAL;
    private volatile int syncEveryWriteRequests = 0;
    private final java.util.concurrent.atomic.AtomicLong writeRequestCounter = new java.util.concurrent.atomic.AtomicLong(0);

    private volatile FastPutCache lastPutCache;
    private byte[] lastPutHashBytes;
    private long fastPutLastHitCount = 0;
    private long fastPutQuickHitCount = 0;
    private long fastPutMissCount = 0;
    private long fastPutRejectedCount = 0;
    private long fastPutInvalidatedCount = 0;
    private int quickCacheCapacity = 0;
    private int quickCacheSize = 0;
    private int quickCacheHead = 0;
    private long[] quickCacheNodeIds;
    private int[] quickCacheLevels;
    private int[] quickCacheTierIndexes;
    private byte[] quickCacheHashes;

    public DsHashMapV2(File file) {
        this(file, DEFAULT_HASH_BYTES_PROVIDER, 1L);
    }

    public DsHashMapV2(File file, HashBytesProvider hashBytesProvider) {
        this(file, hashBytesProvider, hashBytesProvider == DEFAULT_HASH_BYTES_PROVIDER ? 1L : 0L);
    }

    public DsHashMapV2(File file, HashBytesProvider hashBytesProvider, long hashProviderId) {
        this(null, file, file, 0, new EntryStore(entryFile(file)), hashBytesProvider, hashProviderId, true);
    }

    private DsHashMapV2(DsHashMapV2 rootMap, File baseFile, File nodeFile, int tierIndex, EntryStore entryStore, HashBytesProvider hashBytesProvider, long hashProviderId, boolean root) {
        super(nodeFile, HEADER_SIZE, NODE_SIZE);
        this.zeroNode = new long[this.dataUnitSize / 8];
        this.tierIndex = tierIndex;
        this.hashOffset = tierIndex == 0 ? 0 : HASH_LEVEL_ENDS[tierIndex - 1];
        this.hashEnd = HASH_LEVEL_ENDS[tierIndex];
        this.root = root;
        this.rootMap = root ? this : Objects.requireNonNull(rootMap, "rootMap");
        this.entryStore = entryStore;
        this.hashBytesProvider = Objects.requireNonNull(hashBytesProvider, "hashBytesProvider");
        this.hashProviderId = hashProviderId;
        initHeader();
        if (tierIndex + 1 < HASH_LEVEL_ENDS.length) {
            this.nextHashMap = new DsHashMapV2(this.rootMap, baseFile, tierFile(baseFile, HASH_LEVEL_ENDS[tierIndex + 1]), tierIndex + 1, entryStore, hashBytesProvider, hashProviderId, false);
        } else {
            this.nextHashMap = null;
        }
        if (root) {
            setQuickCacheSizeInternal(DEFAULT_QUICK_CACHE_SIZE, false);
        }
    }

    public Long put(long key, long value) throws IOException {
        byte[] fullHash = hashBytes(key);
        Long oldValue;
        if (root) {
            FastPutCache cache = findFastPutCache(fullHash);
            if (cache == null) {
                oldValue = putByHashFrom(0L, hashOffset, fullHash, key, value, true, true);
            } else {
                DsHashMapV2 tier = tierAtIndex(cache.tierIndex());
                oldValue = tier.putByHashFrom(cache.nodeId(), cache.level(), fullHash, key, value, true, true);
            }
        } else {
            oldValue = putByHashFrom(0L, hashOffset, fullHash, key, value, true, false);
        }
        afterWriteRequest();
        return oldValue;
    }

    public Long get(long key) throws IOException {
        return getByHash(hashBytes(key), key);
    }

    public Long remove(long key) throws IOException {
        Long oldValue = removeByHash(hashBytes(key), key);
        afterWriteRequest();
        return oldValue;
    }

    @Override
    public Long put(Long key, Long value) {
        if (key == null || value == null) {
            throw new NullPointerException("key/value");
        }
        try {
            return put(key.longValue(), value.longValue());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public Long get(Object key) {
        if (!(key instanceof Long longKey)) {
            return null;
        }
        try {
            return get(longKey.longValue());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public Long remove(Object key) {
        if (!(key instanceof Long longKey)) {
            return null;
        }
        try {
            return remove(longKey.longValue());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public int size() {
        long total = sizeLong();
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public long sizeLong() {
        long total = localSize;
        if (nextHashMap != null) {
            total += nextHashMap.sizeLong();
        }
        return total;
    }

    public DebugStats getDebugStats() {
        if (!root) {
            throw new IllegalStateException("debug stats are only available on root map");
        }
        try {
            long freeEntryCount = entryStore.countFreeEntries();
            long nextEntryId = entryStore.getNextEntryId();
            return new DebugStats(
                sizeLong(),
                localSize,
                nextNodeId,
                countTotalNodes(),
                nextEntryId,
                entryStore.getFreeEntryId(),
                freeEntryCount,
                (nextEntryId - 1L) - freeEntryCount
            );
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public Map<String, Object> getDebugStatsMap() {
        DebugStats stats = getDebugStats();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalSize", stats.totalSize());
        out.put("rootLocalSize", stats.rootLocalSize());
        out.put("rootNextNodeId", stats.rootNextNodeId());
        out.put("totalNodeCount", stats.totalNodeCount());
        out.put("nextEntryId", stats.nextEntryId());
        out.put("freeEntryId", stats.freeEntryId());
        out.put("freeEntryCount", stats.freeEntryCount());
        out.put("usedEntryCount", stats.usedEntryCount());
        return out;
    }

    public FastPutStats getFastPutStats() {
        if (!root) {
            throw new IllegalStateException("fastPut stats are only available on root map");
        }
        return new FastPutStats(
            fastPutLastHitCount,
            fastPutQuickHitCount,
            fastPutMissCount,
            fastPutRejectedCount,
            fastPutInvalidatedCount,
            quickCacheSize,
            quickCacheCapacity
        );
    }

    public Map<String, Object> getFastPutStatsMap() {
        FastPutStats stats = getFastPutStats();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lastHitCount", stats.lastHitCount());
        out.put("quickHitCount", stats.quickHitCount());
        out.put("missCount", stats.missCount());
        out.put("rejectedCount", stats.rejectedCount());
        out.put("invalidatedCount", stats.invalidatedCount());
        out.put("quickCacheSize", stats.quickCacheSize());
        out.put("quickCacheCapacity", stats.quickCacheCapacity());
        return out;
    }

    public void setQuickCacheSize(int quickCacheSize) {
        if (!root) {
            throw new IllegalStateException("quick cache size can only be configured on root map");
        }
        setQuickCacheSizeInternal(quickCacheSize, true);
    }

    public Map<String, Object> debugDumpMap(long key) {
        try {
            byte[] fullHash = hashBytes(key);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("key", key);
            out.put("hashHex", toHex(fullHash));
            out.put("stats", getDebugStatsMap());
            out.put("levels", debugLevels(fullHash, key));
            out.put("value", getByHash(fullHash, key));
            return out;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public String debugDump(long key) {
        Map<String, Object> dump = debugDumpMap(key);
        StringBuilder out = new StringBuilder(512);
        out.append("DsHashMapV2 key=").append(key).append('\n');
        out.append("hashHex=").append(dump.get("hashHex")).append('\n');
        out.append("value=").append(dump.get("value")).append('\n');
        Object levels = dump.get("levels");
        if (levels instanceof Object[] array) {
            for (Object current : array) {
                if (!(current instanceof Map<?, ?> row)) {
                    continue;
                }
                out.append("level=")
                    .append(row.get("level"))
                    .append(", tierBits=")
                    .append(row.get("tierBits"))
                    .append(", slot=")
                    .append(row.get("slot"))
                    .append(", state=")
                    .append(row.get("state"))
                    .append(", ref=")
                    .append(row.get("ref"))
                    .append('\n');
            }
        }
        return out.toString();
    }

    public String debugDumpJson(long key) {
        return toJson(debugDumpMap(key));
    }

    @Override
    public boolean isEmpty() {
        return sizeLong() == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        if (!(value instanceof Long longValue)) {
            return false;
        }
        for (Long current : values()) {
            if (Objects.equals(current, longValue)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void putAll(Map<? extends Long, ? extends Long> map) {
        for (Entry<? extends Long, ? extends Long> entry : map.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void clear() {
        clearLocal();
        if (nextHashMap != null) {
            nextHashMap.clear();
        }
        if (root) {
            entryStore.clearStore();
            invalidateFastPutCaches();
        }
        afterWriteRequest();
    }

    public int forEachEntryRange(long start, int count, LongLongConsumer consumer) {
        if (consumer == null) {
            throw new NullPointerException("consumer");
        }
        if (start < 0) {
            throw new IllegalArgumentException("start must be >= 0");
        }
        if (count <= 0) {
            return 0;
        }
        try {
            long[] skip = new long[] {start};
            int[] emitted = new int[] {0};
            TraversalContext ctx = new TraversalContext();
            collectEntryRangeOrdered(this, 0L, hashOffset, skip, count, (k, v) -> {
                consumer.accept(k, v);
                return false;
            }, emitted, ctx);
            return emitted[0];
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public List<Entry<Long, Long>> rangeEntries(long start, int count) {
        ArrayList<Entry<Long, Long>> out = new ArrayList<>(Math.max(0, count));
        forEachEntryRange(start, count, (k, v) -> out.add(new AbstractMap.SimpleImmutableEntry<>(k, v)));
        return out;
    }

    @Override
    public Set<Long> keySet() {
        return new AbstractSet<Long>() {
            @Override
            public Iterator<Long> iterator() {
                Iterator<Entry<Long, Long>> iterator = entrySet().iterator();
                return new Iterator<Long>() {
                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public Long next() {
                        return iterator.next().getKey();
                    }

                    @Override
                    public void remove() {
                        iterator.remove();
                    }
                };
            }

            @Override
            public int size() {
                return DsHashMapV2.this.size();
            }

            @Override
            public boolean contains(Object key) {
                return containsKey(key);
            }

            @Override
            public boolean remove(Object key) {
                return DsHashMapV2.this.remove(key) != null;
            }

            @Override
            public void clear() {
                DsHashMapV2.this.clear();
            }
        };
    }

    @Override
    public Collection<Long> values() {
        return new AbstractCollection<Long>() {
            @Override
            public Iterator<Long> iterator() {
                Iterator<Entry<Long, Long>> iterator = entrySet().iterator();
                return new Iterator<Long>() {
                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public Long next() {
                        return iterator.next().getValue();
                    }

                    @Override
                    public void remove() {
                        iterator.remove();
                    }
                };
            }

            @Override
            public int size() {
                return DsHashMapV2.this.size();
            }

            @Override
            public void clear() {
                DsHashMapV2.this.clear();
            }
        };
    }

    @Override
    public Set<Entry<Long, Long>> entrySet() {
        return new AbstractSet<Entry<Long, Long>>() {
            @Override
            public Iterator<Entry<Long, Long>> iterator() {
                return new OrderedEntryIterator();
            }

            @Override
            public int size() {
                return DsHashMapV2.this.size();
            }

            @Override
            public void clear() {
                DsHashMapV2.this.clear();
            }
        };
    }

    public void close() {
        disableSyncMode();
        sync();
        if (nextHashMap != null) {
            nextHashMap.close();
        }
        if (root) {
            entryStore.sync();
        }
    }

    public void setSyncModeSystemAuto() {
        ensureRoot();
        applySyncModeRecursive(SyncMode.SYSTEM_AUTO, 0, 0);
    }

    public void setSyncModeWriteRequests(int writeRequests) {
        if (writeRequests <= 0) {
            throw new IllegalArgumentException("writeRequests must be > 0");
        }
        ensureRoot();
        applySyncModeRecursive(SyncMode.WRITE_REQUESTS, writeRequests, 0);
    }

    public void setSyncModeSeconds(long seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("seconds must be > 0");
        }
        ensureRoot();
        long intervalMs = seconds * 1000L;
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("seconds too large");
        }
        applySyncModeRecursive(SyncMode.SECONDS, 0, intervalMs);
    }

    public void setSyncModeStrong100ms() {
        ensureRoot();
        applySyncModeRecursive(SyncMode.STRONG_100MS, 0, 100L);
    }

    public void disableSyncMode() {
        if (nextHashMap != null) {
            nextHashMap.disableSyncMode();
        }
        disableBackgroundFlush();
        syncMode = SyncMode.MANUAL;
        syncEveryWriteRequests = 0;
        writeRequestCounter.set(0);
        if (root) {
            entryStore.disableBackgroundFlush();
        }
    }

    public Map<String, Object> getSyncModeMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", syncMode.name());
        out.put("syncEveryWriteRequests", syncEveryWriteRequests);
        out.put("rootBackgroundFlushEnabled", isBackgroundFlushEnabled());
        out.put("enabledObjectCount", countBackgroundFlushEnabledObjects());
        if (root) {
            out.put("entryBackgroundFlushEnabled", entryStore.isBackgroundFlushEnabled());
        }
        return out;
    }

    private void ensureRoot() {
        if (!root) {
            throw new IllegalStateException("sync mode can only be configured on root map");
        }
    }

    private int countBackgroundFlushEnabledObjects() {
        int count = isBackgroundFlushEnabled() ? 1 : 0;
        if (nextHashMap != null) {
            count += nextHashMap.countBackgroundFlushEnabledObjects();
        }
        if (root && entryStore.isBackgroundFlushEnabled()) {
            count++;
        }
        return count;
    }

    private void applySyncModeRecursive(SyncMode mode, int writeRequests, long intervalMs) {
        if (nextHashMap != null) {
            nextHashMap.applySyncModeRecursive(mode, writeRequests, intervalMs);
        }
        disableBackgroundFlush();
        if (root) {
            entryStore.disableBackgroundFlush();
        }
        syncMode = mode;
        syncEveryWriteRequests = writeRequests;
        writeRequestCounter.set(0);
        if (mode == SyncMode.SYSTEM_AUTO) {
            enableAdaptiveBackgroundFlush(200L, 5000L, 64);
            if (root) {
                entryStore.enableAdaptiveBackgroundFlush(200L, 5000L, 64);
            }
            return;
        }
        if (mode == SyncMode.SECONDS) {
            enableBackgroundFlush(intervalMs, Integer.MAX_VALUE);
            if (root) {
                entryStore.enableBackgroundFlush(intervalMs, Integer.MAX_VALUE);
            }
            return;
        }
        if (mode == SyncMode.STRONG_100MS) {
            enableBackgroundFlush(100L, Integer.MAX_VALUE);
            if (root) {
                entryStore.enableBackgroundFlush(100L, Integer.MAX_VALUE);
            }
            return;
        }
    }

    private void afterWriteRequest() {
        if (!root) {
            return;
        }
        if (syncMode != SyncMode.WRITE_REQUESTS) {
            return;
        }
        int every = syncEveryWriteRequests;
        if (every <= 0) {
            return;
        }
        long c = writeRequestCounter.incrementAndGet();
        if (c % every != 0) {
            return;
        }
        syncAll();
    }

    private void syncAll() {
        sync();
        if (nextHashMap != null) {
            nextHashMap.syncAll();
        }
        if (root) {
            entryStore.sync();
        }
    }

    private void initHeader() {
        try {
            headerBuffer = loadBuffer(0L);
            byte[] magic = new byte[4];
            headerBuffer.get(HDR_MAGIC, magic, 0, 4);
            if (Arrays.equals(magic, MAGIC)) {
                nextNodeId = headerBuffer.getLong(HDR_NEXT_NODE_ID);
                localSize = headerBuffer.getLong(HDR_LOCAL_SIZE);
                long storedHashProviderId = headerBuffer.getLong(HDR_HASH_PROVIDER_ID);
                if (storedHashProviderId != 0L && storedHashProviderId != hashProviderId) {
                    throw new IllegalStateException("hash provider id mismatch: stored=" + storedHashProviderId + ", current=" + hashProviderId);
                }
                return;
            }
            headerBuffer.put(HDR_MAGIC, MAGIC, 0, 4);
            headerBuffer.putInt(HDR_VALUE_SIZE, SLOT_PAYLOAD_BYTES);
            nextNodeId = 1;
            localSize = 0;
            headerBuffer.putLong(HDR_NEXT_NODE_ID, nextNodeId);
            headerBuffer.putLong(HDR_LOCAL_SIZE, localSize);
            headerBuffer.putLong(HDR_HASH_PROVIDER_ID, hashProviderId);
            dirty(0L);
            storeLongOffset(nodeBase(0), zeroNode);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void setQuickCacheSizeInternal(int quickCacheSize, boolean resetStats) {
        int capacity = Math.max(8, quickCacheSize);
        this.quickCacheCapacity = capacity;
        this.quickCacheSize = capacity;
        this.quickCacheHead = 0;
        this.quickCacheNodeIds = new long[capacity];
        this.quickCacheLevels = new int[capacity];
        this.quickCacheTierIndexes = new int[capacity];
        this.quickCacheHashes = new byte[capacity * HASH_BYTES];
        Arrays.fill(this.quickCacheLevels, -1);
        if (resetStats) {
            resetFastPutStats();
        }
    }

    private void resetFastPutStats() {
        fastPutLastHitCount = 0;
        fastPutQuickHitCount = 0;
        fastPutMissCount = 0;
        fastPutRejectedCount = 0;
        fastPutInvalidatedCount = 0;
        lastPutCache = null;
        lastPutHashBytes = null;
        if (quickCacheLevels != null) {
            Arrays.fill(quickCacheLevels, -1);
        }
    }

    private void invalidateFastPutCaches() {
        lastPutCache = null;
        lastPutHashBytes = null;
        if (quickCacheLevels != null) {
            Arrays.fill(quickCacheLevels, -1);
        }
    }

    private static boolean prefixMatches(byte[] cachedHash, int level, byte[] fullHash) {
        if (cachedHash == null) {
            return false;
        }
        if (level <= 0) {
            return true;
        }
        for (int i = 0; i < level; i++) {
            if (cachedHash[i] != fullHash[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean prefixMatchesQuick(int index, int level, byte[] fullHash) {
        int base = index * HASH_BYTES;
        for (int i = 0; i < level; i++) {
            if (quickCacheHashes[base + i] != fullHash[i]) {
                return false;
            }
        }
        return true;
    }

    private void copyHashToQuick(int index, byte[] fullHash) {
        System.arraycopy(fullHash, 0, quickCacheHashes, index * HASH_BYTES, HASH_BYTES);
    }

    private FastPutCache findFastPutCache(byte[] fullHash) {
        if (!root) {
            return null;
        }
        FastPutCache last = lastPutCache;
        if (last != null && prefixMatches(lastPutHashBytes, last.level(), fullHash)) {
            try {
                if (isFastPutNodeReachable(last, fullHash)) {
                    fastPutLastHitCount++;
                    return last;
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            fastPutRejectedCount++;
            fastPutInvalidatedCount++;
            lastPutCache = null;
            lastPutHashBytes = null;
        }

        int bestIndex = -1;
        int bestLevel = -1;
        int bestTier = 0;
        long bestNodeId = 0L;
        if (quickCacheLevels != null) {
            for (int i = 0; i < quickCacheCapacity; i++) {
                int level = quickCacheLevels[i];
                if (level < 0) {
                    continue;
                }
                if (level <= bestLevel) {
                    continue;
                }
                if (!prefixMatchesQuick(i, level, fullHash)) {
                    continue;
                }
                FastPutCache candidate = new FastPutCache(quickCacheTierIndexes[i], quickCacheNodeIds[i], level);
                try {
                    if (!isFastPutNodeReachable(candidate, fullHash)) {
                        quickCacheLevels[i] = -1;
                        fastPutRejectedCount++;
                        fastPutInvalidatedCount++;
                        continue;
                    }
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
                bestIndex = i;
                bestLevel = level;
                bestTier = candidate.tierIndex();
                bestNodeId = candidate.nodeId();
            }
        }
        if (bestIndex >= 0) {
            fastPutQuickHitCount++;
            return new FastPutCache(bestTier, bestNodeId, bestLevel);
        }
        fastPutMissCount++;
        return null;
    }

    private void recordFastPutLocation(int tierIndex, long nodeId, int level, byte[] fullHash) {
        if (!root) {
            return;
        }
        FastPutCache previous = lastPutCache;
        byte[] lastHash = lastPutHashBytes;
        if (previous != null && quickCacheLevels != null && lastHash != null) {
            int insertIndex = quickCacheHead;
            quickCacheHead = (quickCacheHead + 1) % quickCacheCapacity;
            quickCacheTierIndexes[insertIndex] = previous.tierIndex();
            quickCacheNodeIds[insertIndex] = previous.nodeId();
            quickCacheLevels[insertIndex] = previous.level();
            copyHashToQuick(insertIndex, lastHash);
        }

        if (lastHash == null) {
            lastHash = new byte[HASH_BYTES];
            lastPutHashBytes = lastHash;
        }
        System.arraycopy(fullHash, 0, lastHash, 0, HASH_BYTES);
        lastPutCache = new FastPutCache(tierIndex, nodeId, level);
    }

    private DsHashMapV2 tierAtIndex(int tierIndex) {
        DsHashMapV2 current = this;
        int t = 0;
        while (t < tierIndex) {
            if (current.nextHashMap == null) {
                return current;
            }
            current = current.nextHashMap;
            t++;
        }
        return current;
    }

    private boolean isFastPutNodeReachable(FastPutCache cache, byte[] fullHash) throws IOException {
        DsHashMapV2 tier = this;
        long nodeId = 0L;
        int currentTier = 0;
        while (currentTier < cache.tierIndex()) {
            int leafLevel = tier.hashEnd - 1;
            for (int level = tier.hashOffset; level < tier.hashEnd; level++) {
                int slot = fullHash[level] & 0xFF;
                int state = tier.readState(nodeId, slot);
                boolean leaf = level == leafLevel;
                if (!leaf) {
                    if (state != STATE_CHILD) {
                        return false;
                    }
                    nodeId = tier.readSlotRef(nodeId, slot);
                    continue;
                }
                if (state != STATE_NEXT_LEVEL || tier.nextHashMap == null) {
                    return false;
                }
                long nextRoot = tier.readSlotRef(nodeId, slot);
                if (nextRoot == 0L) {
                    return false;
                }
                tier = tier.nextHashMap;
                nodeId = nextRoot;
                break;
            }
            currentTier++;
        }

        if (cache.level() < tier.hashOffset || cache.level() >= tier.hashEnd) {
            return false;
        }
        for (int level = tier.hashOffset; level < cache.level(); level++) {
            int slot = fullHash[level] & 0xFF;
            int state = tier.readState(nodeId, slot);
            if (state != STATE_CHILD) {
                return false;
            }
            nodeId = tier.readSlotRef(nodeId, slot);
        }
        return nodeId == cache.nodeId();
    }

    private Long putByHash(byte[] fullHash, long key, long value) throws IOException {
        return putByHashFrom(0L, hashOffset, fullHash, key, value, true, false);
    }

    private Long putByHashFrom(long startNodeId, int startLevel, byte[] fullHash, long key, long value, boolean countLocalSize, boolean updateFastPutCache) throws IOException {
        long nodeId = startNodeId;
        for (int level = startLevel; level < hashEnd; level++) {
            int slot = fullHash[level] & 0xFF;
            int state = readState(nodeId, slot);
            boolean leaf = level == hashEnd - 1;
            if (!leaf) {
                if (state == STATE_CHILD) {
                    nodeId = readSlotRef(nodeId, slot);
                    continue;
                }
                if (state == STATE_EMPTY) {
                    return storeEntryInSlot(nodeId, slot, level, fullHash, key, value, countLocalSize, updateFastPutCache);
                }
                if (state == STATE_VALUE) {
                    return splitEntryWithinTier(nodeId, slot, level, fullHash, key, value, countLocalSize, updateFastPutCache);
                }
                throw new IOException("invalid state before leaf: " + state);
            }
            return putAtLeaf(nodeId, slot, state, level, fullHash, key, value, countLocalSize, updateFastPutCache);
        }
        return null;
    }

    private Long putAtLeaf(long nodeId, int slot, int state, int level, byte[] fullHash, long key, long value, boolean countLocalSize, boolean updateFastPutCache) throws IOException {
        if (state == STATE_EMPTY) {
            return storeEntryInSlot(nodeId, slot, level, fullHash, key, value, countLocalSize, updateFastPutCache);
        }
        if (state == STATE_VALUE) {
            long entryId = readSlotRef(nodeId, slot);
            if (nextHashMap == null) {
                return putAtTerminalChain(nodeId, slot, entryId, level, fullHash, key, value, countLocalSize, updateFastPutCache);
            }
            long storedKey = entryStore.readKey(entryId);
            long storedValue = entryStore.readValue(entryId);
            if (storedKey == key) {
                if (storedValue != value) {
                    entryStore.writeValue(entryId, value);
                }
                if (updateFastPutCache) {
                    rootMap.recordFastPutLocation(this.tierIndex, nodeId, level, fullHash);
                }
                return storedValue;
            }
            // 叶子前缀冲突后，直接升级到下一层级。
            long nextLevelRootNodeId = nextHashMap.allocateNodeId();
            writeState(nodeId, slot, STATE_NEXT_LEVEL);
            writeSlotRef(nodeId, slot, nextLevelRootNodeId);
            entryStore.free(entryId);
            decrementLocalSize();
            nextHashMap.putByHashFrom(nextLevelRootNodeId, nextHashMap.hashOffset, hashBytes(storedKey), storedKey, storedValue, true, false);
            return nextHashMap.putByHashFrom(nextLevelRootNodeId, nextHashMap.hashOffset, fullHash, key, value, true, updateFastPutCache);
        }
        if (state == STATE_NEXT_LEVEL) {
            long nextLevelRootNodeId = readSlotRef(nodeId, slot);
            if (nextLevelRootNodeId == 0L) {
                return nextHashMap.putByHash(fullHash, key, value);
            }
            return nextHashMap.putByHashFrom(nextLevelRootNodeId, nextHashMap.hashOffset, fullHash, key, value, true, updateFastPutCache);
        }
        throw new IOException("invalid leaf state: " + state);
    }

    private Long splitEntryWithinTier(long nodeId, int slot, int level, byte[] fullHash, long key, long value, boolean countLocalSize, boolean updateFastPutCache) throws IOException {
        long entryId = readSlotRef(nodeId, slot);
        long storedKey = entryStore.readKey(entryId);
        long storedValue = entryStore.readValue(entryId);
        if (storedKey == key) {
            if (storedValue != value) {
                entryStore.writeValue(entryId, value);
            }
            if (updateFastPutCache) {
                rootMap.recordFastPutLocation(this.tierIndex, nodeId, level, fullHash);
            }
            return storedValue;
        }
        long childNodeId = allocateNodeId();
        writeState(nodeId, slot, STATE_CHILD);
        writeSlotRef(nodeId, slot, childNodeId);
        putByHashFrom(childNodeId, level + 1, hashBytes(storedKey), storedKey, storedValue, false, false);
        return putByHashFrom(childNodeId, level + 1, fullHash, key, value, countLocalSize, updateFastPutCache);
    }

    private Long storeEntryInSlot(long nodeId, int slot, int level, byte[] fullHash, long key, long value, boolean countLocalSize, boolean updateFastPutCache) throws IOException {
        long entryId = entryStore.allocate(key, value, 0L);
        writeState(nodeId, slot, STATE_VALUE);
        writeSlotRef(nodeId, slot, entryId);
        if (countLocalSize) {
            incrementLocalSize();
        }
        if (updateFastPutCache) {
            rootMap.recordFastPutLocation(this.tierIndex, nodeId, level, fullHash);
        }
        return null;
    }

    private Long putAtTerminalChain(long nodeId, int slot, long headEntryId, int level, byte[] fullHash, long key, long value, boolean countLocalSize, boolean updateFastPutCache) throws IOException {
        long currentEntryId = headEntryId;
        long previousEntryId = 0L;
        while (currentEntryId != 0L) {
            long storedKey = entryStore.readKey(currentEntryId);
            long storedValue = entryStore.readValue(currentEntryId);
            if (storedKey == key) {
                if (storedValue != value) {
                    entryStore.writeValue(currentEntryId, value);
                }
                if (updateFastPutCache) {
                    rootMap.recordFastPutLocation(this.tierIndex, nodeId, level, fullHash);
                }
                return storedValue;
            }
            previousEntryId = currentEntryId;
            currentEntryId = entryStore.readNext(currentEntryId);
        }
        long newEntryId = entryStore.allocate(key, value, 0L);
        if (previousEntryId == 0L) {
            writeSlotRef(nodeId, slot, newEntryId);
        } else {
            entryStore.writeNext(previousEntryId, newEntryId);
        }
        if (countLocalSize) {
            incrementLocalSize();
        }
        if (updateFastPutCache) {
            rootMap.recordFastPutLocation(this.tierIndex, nodeId, level, fullHash);
        }
        return null;
    }

    private Long getByHash(byte[] fullHash, long key) throws IOException {
        return getByHashFrom(0L, hashOffset, fullHash, key);
    }

    private Long getByHashFrom(long startNodeId, int startLevel, byte[] fullHash, long key) throws IOException {
        long nodeId = startNodeId;
        for (int level = startLevel; level < hashEnd; level++) {
            int slot = fullHash[level] & 0xFF;
            int state = readState(nodeId, slot);
            boolean leaf = level == hashEnd - 1;
            if (!leaf) {
                if (state == STATE_CHILD) {
                    nodeId = readSlotRef(nodeId, slot);
                    continue;
                }
                if (state == STATE_VALUE) {
                    return matchSingleEntry(readSlotRef(nodeId, slot), key);
                }
                return null;
            }
            if (state == STATE_EMPTY) {
                return null;
            }
            if (state == STATE_VALUE) {
                long entryId = readSlotRef(nodeId, slot);
                return nextHashMap == null ? findInTerminalChain(entryId, key) : matchSingleEntry(entryId, key);
            }
            if (state == STATE_NEXT_LEVEL) {
                if (nextHashMap == null) {
                    return null;
                }
                long nextLevelRootNodeId = readSlotRef(nodeId, slot);
                if (nextLevelRootNodeId == 0L) {
                    return nextHashMap.getByHash(fullHash, key);
                }
                return nextHashMap.getByHashFrom(nextLevelRootNodeId, nextHashMap.hashOffset, fullHash, key);
            }
            throw new IOException("invalid leaf state: " + state);
        }
        return null;
    }

    private Long removeByHash(byte[] fullHash, long key) throws IOException {
        return removeByHashFrom(0L, hashOffset, fullHash, key);
    }

    private Long removeByHashFrom(long startNodeId, int startLevel, byte[] fullHash, long key) throws IOException {
        long nodeId = startNodeId;
        for (int level = startLevel; level < hashEnd; level++) {
            int slot = fullHash[level] & 0xFF;
            int state = readState(nodeId, slot);
            boolean leaf = level == hashEnd - 1;
            if (!leaf) {
                if (state == STATE_CHILD) {
                    nodeId = readSlotRef(nodeId, slot);
                    continue;
                }
                if (state == STATE_VALUE) {
                    return removeSingleEntry(nodeId, slot, readSlotRef(nodeId, slot), key);
                }
                return null;
            }
            if (state == STATE_EMPTY) {
                return null;
            }
            if (state == STATE_VALUE) {
                long entryId = readSlotRef(nodeId, slot);
                return nextHashMap == null ? removeFromTerminalChain(nodeId, slot, entryId, key) : removeSingleEntry(nodeId, slot, entryId, key);
            }
            if (state == STATE_NEXT_LEVEL) {
                if (nextHashMap == null) {
                    return null;
                }
                long nextLevelRootNodeId = readSlotRef(nodeId, slot);
                if (nextLevelRootNodeId == 0L) {
                    return nextHashMap.removeByHash(fullHash, key);
                }
                return nextHashMap.removeByHashFrom(nextLevelRootNodeId, nextHashMap.hashOffset, fullHash, key);
            }
            throw new IOException("invalid leaf state: " + state);
        }
        return null;
    }

    private Long removeSingleEntry(long nodeId, int slot, long entryId, long key) throws IOException {
        long storedKey = entryStore.readKey(entryId);
        if (storedKey != key) {
            return null;
        }
        long oldValue = entryStore.readValue(entryId);
        writeState(nodeId, slot, STATE_EMPTY);
        writeSlotRef(nodeId, slot, 0L);
        entryStore.free(entryId);
        decrementLocalSize();
        return oldValue;
    }

    private Long removeFromTerminalChain(long nodeId, int slot, long headEntryId, long key) throws IOException {
        long previousEntryId = 0L;
        long currentEntryId = headEntryId;
        while (currentEntryId != 0L) {
            long storedKey = entryStore.readKey(currentEntryId);
            long nextEntryId = entryStore.readNext(currentEntryId);
            if (storedKey == key) {
                long oldValue = entryStore.readValue(currentEntryId);
                if (previousEntryId == 0L) {
                    if (nextEntryId == 0L) {
                        writeState(nodeId, slot, STATE_EMPTY);
                        writeSlotRef(nodeId, slot, 0L);
                    } else {
                        writeSlotRef(nodeId, slot, nextEntryId);
                    }
                } else {
                    entryStore.writeNext(previousEntryId, nextEntryId);
                }
                entryStore.free(currentEntryId);
                decrementLocalSize();
                return oldValue;
            }
            previousEntryId = currentEntryId;
            currentEntryId = nextEntryId;
        }
        return null;
    }

    private Long matchSingleEntry(long entryId, long key) throws IOException {
        long storedKey = entryStore.readKey(entryId);
        if (storedKey != key) {
            return null;
        }
        return entryStore.readValue(entryId);
    }

    private Long findInTerminalChain(long entryId, long key) throws IOException {
        long currentEntryId = entryId;
        while (currentEntryId != 0L) {
            long storedKey = entryStore.readKey(currentEntryId);
            if (storedKey == key) {
                return entryStore.readValue(currentEntryId);
            }
            currentEntryId = entryStore.readNext(currentEntryId);
        }
        return null;
    }

    private long[] toKeyArray() {
        Longs out = new Longs(size());
        try {
            collectKeys(out, 0L, hashOffset);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        if (nextHashMap != null) {
            long[] more = nextHashMap.toKeyArray();
            for (long value : more) {
                out.add(value);
            }
        }
        return out.toArray();
    }

    private void collectKeys(Longs out, long nodeId, int startLevel) throws IOException {
        for (int slot = 0; slot < 256; slot++) {
            int state = readState(nodeId, slot);
            if (state == STATE_EMPTY || state == STATE_NEXT_LEVEL) {
                continue;
            }
            long slotRef = readSlotRef(nodeId, slot);
            if (state == STATE_VALUE) {
                if (nextHashMap == null && startLevel == hashEnd - 1) {
                    collectTerminalChainKeys(out, slotRef);
                } else {
                    out.add(entryStore.readKey(slotRef));
                }
                continue;
            }
            if (state == STATE_CHILD && startLevel < hashEnd - 1) {
                collectKeys(out, slotRef, startLevel + 1);
            }
        }
    }

    private void collectTerminalChainKeys(Longs out, long headEntryId) throws IOException {
        long currentEntryId = headEntryId;
        while (currentEntryId != 0L) {
            out.add(entryStore.readKey(currentEntryId));
            currentEntryId = entryStore.readNext(currentEntryId);
        }
    }

    private void incrementLocalSize() {
        headerOpLockWrite.lock();
        try {
            localSize++;
            headerBuffer.putLong(HDR_LOCAL_SIZE, localSize);
            dirty(0L);
        } finally {
            headerOpLockWrite.unlock();
        }
    }

    private void decrementLocalSize() {
        headerOpLockWrite.lock();
        try {
            localSize--;
            headerBuffer.putLong(HDR_LOCAL_SIZE, localSize);
            dirty(0L);
        } finally {
            headerOpLockWrite.unlock();
        }
    }

    private void clearLocal() {
        try {
            nextNodeId = 1;
            localSize = 0;
            headerBuffer.putLong(HDR_NEXT_NODE_ID, nextNodeId);
            headerBuffer.putLong(HDR_LOCAL_SIZE, localSize);
            dirty(0L);
            storeLongOffset(nodeBase(0), zeroNode);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private long countTotalNodes() {
        long total = nextNodeId;
        if (nextHashMap != null) {
            total += nextHashMap.countTotalNodes();
        }
        return total;
    }

    private Object[] debugLevels(byte[] fullHash, long key) throws IOException {
        DebugRowList rows = new DebugRowList(8);
        collectDebugLevels(rows, fullHash, key);
        return rows.toArray();
    }

    private void collectDebugLevels(DebugRowList rows, byte[] fullHash, long key) throws IOException {
        collectDebugLevelsFrom(rows, 0L, hashOffset, fullHash, key);
    }

    private void collectDebugLevelsFrom(DebugRowList rows, long startNodeId, int startLevel, byte[] fullHash, long key) throws IOException {
        long nodeId = startNodeId;
        for (int level = startLevel; level < hashEnd; level++) {
            int slot = fullHash[level] & 0xFF;
            int state = readState(nodeId, slot);
            long ref = state == STATE_EMPTY ? 0L : readSlotRef(nodeId, slot);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("level", level);
            row.put("tierBits", (level + 1) * 8);
            row.put("slot", slot);
            row.put("state", stateName(state));
            row.put("ref", ref);
            if (state == STATE_VALUE && ref != 0L) {
                row.put("entryKey", entryStore.readKey(ref));
                row.put("entryValue", entryStore.readValue(ref));
                row.put("entryNext", entryStore.readNext(ref));
            }
            rows.add(row);
            if (state == STATE_CHILD) {
                nodeId = ref;
                continue;
            }
            if (state == STATE_NEXT_LEVEL && nextHashMap != null) {
                if (ref == 0L) {
                    nextHashMap.collectDebugLevels(rows, fullHash, key);
                } else {
                    nextHashMap.collectDebugLevelsFrom(rows, ref, nextHashMap.hashOffset, fullHash, key);
                }
            }
            break;
        }
    }

    private long allocateNodeId() throws IOException {
        headerOpLockWrite.lock();
        try {
            long nodeId = nextNodeId;
            nextNodeId++;
            headerBuffer.putLong(HDR_NEXT_NODE_ID, nextNodeId);
            dirty(0L);
            storeLongOffset(nodeBase(nodeId), zeroNode);
            return nodeId;
        } finally {
            headerOpLockWrite.unlock();
        }
    }

    private byte[] hashBytes(long key) {
        byte[] out = new byte[HASH_BYTES];
        hashBytesProvider.fillHashBytes(key, out);
        return out;
    }

    private long nodeBase(long nodeId) {
        return HEADER_SIZE + nodeId * (long) dataUnitSize;
    }

    private long bitmapPos(long nodeId, int slot) {
        return nodeBase(nodeId) + (slot >>> 2);
    }

    private long valuePos(long nodeId, int slot) {
        return nodeBase(nodeId) + BITMAP_BYTES + (long) slot * SLOT_PAYLOAD_BYTES;
    }

    private long readSlotRef(long nodeId, int slot) throws IOException {
        return loadLongOffset(valuePos(nodeId, slot));
    }

    private void writeSlotRef(long nodeId, int slot, long value) throws IOException {
        storeLongOffset(valuePos(nodeId, slot), value);
    }

    private int readState(long nodeId, int slot) throws IOException {
        int stateByte = loadU8ByOffset(bitmapPos(nodeId, slot));
        return getStateValue(stateByte, slot & 3);
    }

    private void writeState(long nodeId, int slot, int state) throws IOException {
        long position = bitmapPos(nodeId, slot);
        int stateByte = loadU8ByOffset(position);
        storeByteOffset(position, (byte) setStateValue(stateByte, slot & 3, state));
    }

    private static int getStateValue(int data, int index) {
        int shift = switch (index) {
            case 0 -> 6;
            case 1 -> 4;
            case 2 -> 2;
            default -> 0;
        };
        return (data >>> shift) & 0x3;
    }

    private static int setStateValue(int data, int index, int state) {
        int shift = switch (index) {
            case 0 -> 6;
            case 1 -> 4;
            case 2 -> 2;
            default -> 0;
        };
        int mask = ~(0x3 << shift);
        return (data & mask) | ((state & 0x3) << shift);
    }

    private static File entryFile(File baseFile) {
        return new File(baseFile.getAbsolutePath() + ".entries");
    }

    private static File tierFile(File baseFile, int hashBits) {
        return new File(baseFile.getAbsolutePath() + ".m" + hashBits);
    }

    private static void fillDefaultHashBytes(long key, byte[] out) {
        if (out.length != HASH_BYTES) {
            throw new IllegalArgumentException("hash buffer length must be 32");
        }
        long seed = key ^ 0x9E3779B97F4A7C15L;
        for (int offset = 0; offset < HASH_BYTES; offset += 8) {
            seed += 0x9E3779B97F4A7C15L;
            long mixed = mix64(seed);
            for (int i = 0; i < 8; i++) {
                out[offset + i] = (byte) (mixed >>> (i * 8));
            }
        }
    }

    private static long mix64(long value) {
        long mixed = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            out[i * 2] = hex[value >>> 4];
            out[i * 2 + 1] = hex[value & 0x0F];
        }
        return new String(out);
    }

    private static String stateName(int state) {
        return switch (state) {
            case STATE_EMPTY -> "EMPTY";
            case STATE_VALUE -> "VALUE";
            case STATE_CHILD -> "CHILD";
            case STATE_NEXT_LEVEL -> "NEXT_LEVEL";
            default -> "UNKNOWN(" + state + ")";
        };
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String stringValue) {
            return "\"" + escapeJson(stringValue) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> mapValue) {
            StringBuilder out = new StringBuilder();
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    continue;
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(toJson(entry.getKey()));
                out.append(':');
                out.append(toJson(entry.getValue()));
            }
            out.append('}');
            return out.toString();
        }
        if (value instanceof Iterable<?> iterableValue) {
            StringBuilder out = new StringBuilder();
            out.append('[');
            boolean first = true;
            for (Object current : iterableValue) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(toJson(current));
            }
            out.append(']');
            return out.toString();
        }
        if (value instanceof Object[] arrayValue) {
            StringBuilder out = new StringBuilder();
            out.append('[');
            for (int i = 0; i < arrayValue.length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(toJson(arrayValue[i]));
            }
            out.append(']');
            return out.toString();
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    private static String escapeJson(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (current < 32) {
                        out.append(String.format("\\u%04x", (int) current));
                    } else {
                        out.append(current);
                    }
                }
            }
        }
        return out.toString();
    }

    private static int[] buildSignedAscSlots() {
        int[] out = new int[256];
        int index = 0;
        for (int slot = 128; slot < 256; slot++) {
            out[index++] = slot;
        }
        for (int slot = 0; slot < 128; slot++) {
            out[index++] = slot;
        }
        return out;
    }

    private interface EntryVisitor {
        boolean visit(long key, long value) throws IOException;
    }

    private static final class TraversalContext {
        final NodeCountCache countCache = new NodeCountCache();
    }

    private static final class NodeCountCache {
        private static final long EMPTY_META = Long.MIN_VALUE;
        private static final long MISSING_VALUE = -1L;
        private long[] nodeIds = new long[128];
        private long[] metas = new long[128];
        private long[] counts = new long[128];
        private int size = 0;
        private int resizeThreshold = 64;

        NodeCountCache() {
            Arrays.fill(metas, EMPTY_META);
        }

        long get(int tierIndex, int level, long nodeId) {
            int mask = metas.length - 1;
            long meta = (((long) tierIndex) << 32) | (level & 0xFFFFFFFFL);
            int index = mix(meta, nodeId) & mask;
            while (true) {
                long storedMeta = metas[index];
                if (storedMeta == EMPTY_META) {
                    return MISSING_VALUE;
                }
                if (storedMeta == meta && nodeIds[index] == nodeId) {
                    return counts[index];
                }
                index = (index + 1) & mask;
            }
        }

        void put(int tierIndex, int level, long nodeId, long count) {
            if (size >= resizeThreshold) {
                resize();
            }
            long meta = (((long) tierIndex) << 32) | (level & 0xFFFFFFFFL);
            insert(meta, nodeId, count, metas, nodeIds, counts);
        }

        private void resize() {
            long[] oldNodeIds = nodeIds;
            long[] oldMetas = metas;
            long[] oldCounts = counts;
            nodeIds = new long[oldNodeIds.length << 1];
            metas = new long[oldMetas.length << 1];
            counts = new long[oldCounts.length << 1];
            Arrays.fill(metas, EMPTY_META);
            resizeThreshold = metas.length >>> 1;
            size = 0;
            for (int i = 0; i < oldMetas.length; i++) {
                long storedMeta = oldMetas[i];
                if (storedMeta == EMPTY_META) {
                    continue;
                }
                insert(storedMeta, oldNodeIds[i], oldCounts[i], metas, nodeIds, counts);
            }
        }

        private void insert(long meta, long nodeId, long count, long[] metaTable, long[] nodeTable, long[] countTable) {
            int mask = metaTable.length - 1;
            int index = mix(meta, nodeId) & mask;
            while (true) {
                long storedMeta = metaTable[index];
                if (storedMeta == EMPTY_META) {
                    metaTable[index] = meta;
                    nodeTable[index] = nodeId;
                    countTable[index] = count;
                    size++;
                    return;
                }
                if (storedMeta == meta && nodeTable[index] == nodeId) {
                    countTable[index] = count;
                    return;
                }
                index = (index + 1) & mask;
            }
        }

        private int mix(long meta, long nodeId) {
            long mixed = meta ^ (meta >>> 33) ^ (nodeId * 0x9E3779B97F4A7C15L);
            mixed ^= mixed >>> 29;
            return (int) mixed;
        }
    }

    private final class OrderedEntryIterator implements Iterator<Entry<Long, Long>> {
        private static final int MAX_LEVELS = HASH_BYTES;
        private final DsHashMapV2[] tierStack = new DsHashMapV2[MAX_LEVELS];
        private final long[] nodeStack = new long[MAX_LEVELS];
        private final int[] levelStack = new int[MAX_LEVELS];
        private final int[] slotIndexStack = new int[MAX_LEVELS];
        private int stackSize = 0;
        private long currentChainEntryId = 0L;
        private long nextChainEntryId = 0L;
        private Long lastReturnedKey;
        private boolean canRemove = false;
        private boolean legacyNextPending = false;
        private boolean legacyNextDone = false;

        OrderedEntryIterator() {
            pushFrame(DsHashMapV2.this, 0L, DsHashMapV2.this.hashOffset);
        }

        @Override
        public boolean hasNext() {
            if (nextChainEntryId != 0L || currentChainEntryId != 0L) {
                return true;
            }
            try {
                return prepareNextValue();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public Entry<Long, Long> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            try {
                long entryId = currentChainEntryId != 0L ? currentChainEntryId : nextChainEntryId;
                if (currentChainEntryId == 0L) {
                    nextChainEntryId = 0L;
                }
                long key = entryStore.readKey(entryId);
                long value = entryStore.readValue(entryId);
                if (currentChainEntryId != 0L) {
                    currentChainEntryId = entryStore.readNext(currentChainEntryId);
                }
                lastReturnedKey = key;
                canRemove = true;
                return new OrderedEntry(DsHashMapV2.this, key, value);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public void remove() {
            if (!canRemove || lastReturnedKey == null) {
                throw new IllegalStateException();
            }
            DsHashMapV2.this.remove(lastReturnedKey);
            canRemove = false;
        }

        private void pushFrame(DsHashMapV2 tier, long nodeId, int level) {
            tierStack[stackSize] = tier;
            nodeStack[stackSize] = nodeId;
            levelStack[stackSize] = level;
            slotIndexStack[stackSize] = 0;
            stackSize++;
        }

        private boolean prepareNextValue() throws IOException {
            while (true) {
                if (stackSize <= 0) {
                    if (legacyNextPending && !legacyNextDone && DsHashMapV2.this.nextHashMap != null) {
                        legacyNextDone = true;
                        pushFrame(DsHashMapV2.this.nextHashMap, 0L, DsHashMapV2.this.nextHashMap.hashOffset);
                        continue;
                    }
                    return false;
                }
                int top = stackSize - 1;
                DsHashMapV2 tier = tierStack[top];
                long nodeId = nodeStack[top];
                int level = levelStack[top];
                boolean leaf = level == tier.hashEnd - 1;
                int slotPos = slotIndexStack[top];
                if (slotPos >= 256) {
                    stackSize--;
                    continue;
                }
                int slot = SIGNED_ASC_SLOTS[slotPos];
                slotIndexStack[top] = slotPos + 1;
                int state = tier.readState(nodeId, slot);
                if (state == STATE_EMPTY) {
                    continue;
                }
                if (!leaf) {
                    if (state == STATE_CHILD) {
                        long child = tier.readSlotRef(nodeId, slot);
                        pushFrame(tier, child, level + 1);
                        continue;
                    }
                    if (state == STATE_VALUE) {
                        nextChainEntryId = tier.readSlotRef(nodeId, slot);
                        return true;
                    }
                    throw new IOException("invalid state before leaf: " + state);
                }
                if (state == STATE_VALUE) {
                    long ref = tier.readSlotRef(nodeId, slot);
                    if (tier.nextHashMap == null) {
                        currentChainEntryId = ref;
                        return currentChainEntryId != 0L;
                    }
                    nextChainEntryId = ref;
                    return true;
                }
                if (state == STATE_NEXT_LEVEL) {
                    if (tier.nextHashMap == null) {
                        continue;
                    }
                    long nextRoot = tier.readSlotRef(nodeId, slot);
                    if (nextRoot == 0L) {
                        legacyNextPending = true;
                        continue;
                    }
                    pushFrame(tier.nextHashMap, nextRoot, tier.nextHashMap.hashOffset);
                    continue;
                }
                throw new IOException("invalid leaf state: " + state);
            }
        }
    }

    private static final class OrderedEntry implements Entry<Long, Long> {
        private final DsHashMapV2 map;
        private final long key;
        private long value;

        OrderedEntry(DsHashMapV2 map, long key, long value) {
            this.map = map;
            this.key = key;
            this.value = value;
        }

        @Override
        public Long getKey() {
            return key;
        }

        @Override
        public Long getValue() {
            return value;
        }

        @Override
        public Long setValue(Long value) {
            if (value == null) {
                throw new NullPointerException("value");
            }
            Long old = map.put(Long.valueOf(key), value);
            this.value = value.longValue();
            return old;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Entry<?, ?> entry)) {
                return false;
            }
            return Objects.equals(getKey(), entry.getKey()) && Objects.equals(getValue(), entry.getValue());
        }
    }

    private static void collectEntryRangeOrdered(
        DsHashMapV2 tier,
        long nodeId,
        int level,
        long[] skip,
        int limit,
        EntryVisitor visitor,
        int[] emitted,
        TraversalContext ctx
    ) throws IOException {
        boolean leaf = level == tier.hashEnd - 1;
        for (int i = 0; i < 256; i++) {
            int slot = SIGNED_ASC_SLOTS[i];
            int state = tier.readState(nodeId, slot);
            if (state == STATE_EMPTY) {
                continue;
            }
            if (!leaf) {
                if (state == STATE_VALUE) {
                    long entryId = tier.readSlotRef(nodeId, slot);
                    if (skip[0] > 0) {
                        skip[0]--;
                        continue;
                    }
                    emitted[0]++;
                    long key = tier.entryStore.readKey(entryId);
                    long value = tier.entryStore.readValue(entryId);
                    if (visitor.visit(key, value) || emitted[0] >= limit) {
                        return;
                    }
                    continue;
                }
                if (state == STATE_CHILD) {
                    long child = tier.readSlotRef(nodeId, slot);
                    long subtree = countSubtree(tier, child, level + 1, ctx);
                    if (skip[0] >= subtree) {
                        skip[0] -= subtree;
                        continue;
                    }
                    collectEntryRangeOrdered(tier, child, level + 1, skip, limit, visitor, emitted, ctx);
                    if (emitted[0] >= limit) {
                        return;
                    }
                    continue;
                }
                throw new IOException("invalid state before leaf: " + state);
            }
            if (state == STATE_VALUE) {
                long entryId = tier.readSlotRef(nodeId, slot);
                if (tier.nextHashMap == null) {
                    long current = entryId;
                    while (current != 0L) {
                        if (skip[0] > 0) {
                            skip[0]--;
                        } else {
                            emitted[0]++;
                            long key = tier.entryStore.readKey(current);
                            long value = tier.entryStore.readValue(current);
                            if (visitor.visit(key, value) || emitted[0] >= limit) {
                                return;
                            }
                        }
                        current = tier.entryStore.readNext(current);
                    }
                    continue;
                }
                if (skip[0] > 0) {
                    skip[0]--;
                    continue;
                }
                emitted[0]++;
                long key = tier.entryStore.readKey(entryId);
                long value = tier.entryStore.readValue(entryId);
                if (visitor.visit(key, value) || emitted[0] >= limit) {
                    return;
                }
                continue;
            }
            if (state == STATE_NEXT_LEVEL && tier.nextHashMap != null) {
                long nextRoot = tier.readSlotRef(nodeId, slot);
                if (nextRoot == 0L) {
                    continue;
                }
                long subtree = countSubtree(tier.nextHashMap, nextRoot, tier.nextHashMap.hashOffset, ctx);
                if (skip[0] >= subtree) {
                    skip[0] -= subtree;
                    continue;
                }
                collectEntryRangeOrdered(tier.nextHashMap, nextRoot, tier.nextHashMap.hashOffset, skip, limit, visitor, emitted, ctx);
                if (emitted[0] >= limit) {
                    return;
                }
                continue;
            }
            if (state == STATE_NEXT_LEVEL) {
                continue;
            }
            throw new IOException("invalid leaf state: " + state);
        }
    }

    private static long countSubtree(DsHashMapV2 tier, long nodeId, int level, TraversalContext ctx) throws IOException {
        long cached = ctx.countCache.get(tier.tierIndex, level, nodeId);
        if (cached >= 0) {
            return cached;
        }
        boolean leaf = level == tier.hashEnd - 1;
        long total = 0;
        for (int slot = 0; slot < 256; slot++) {
            int state = tier.readState(nodeId, slot);
            if (state == STATE_EMPTY) {
                continue;
            }
            if (!leaf) {
                if (state == STATE_VALUE) {
                    total++;
                    continue;
                }
                if (state == STATE_CHILD) {
                    total += countSubtree(tier, tier.readSlotRef(nodeId, slot), level + 1, ctx);
                    continue;
                }
                throw new IOException("invalid state before leaf: " + state);
            }
            if (state == STATE_VALUE) {
                if (tier.nextHashMap == null) {
                    total += countTerminalChain(tier, tier.readSlotRef(nodeId, slot));
                } else {
                    total++;
                }
                continue;
            }
            if (state == STATE_NEXT_LEVEL && tier.nextHashMap != null) {
                long nextRoot = tier.readSlotRef(nodeId, slot);
                if (nextRoot != 0L) {
                    total += countSubtree(tier.nextHashMap, nextRoot, tier.nextHashMap.hashOffset, ctx);
                }
                continue;
            }
            if (state == STATE_NEXT_LEVEL) {
                continue;
            }
            throw new IOException("invalid leaf state: " + state);
        }
        ctx.countCache.put(tier.tierIndex, level, nodeId, total);
        return total;
    }

    private static long countTerminalChain(DsHashMapV2 tier, long headEntryId) throws IOException {
        long total = 0;
        long current = headEntryId;
        while (current != 0L) {
            total++;
            current = tier.entryStore.readNext(current);
        }
        return total;
    }

    private static final class Longs {
        private long[] values;
        private int size;

        Longs(int capacity) {
            this.values = new long[Math.max(16, capacity)];
        }

        void add(long value) {
            if (size >= values.length) {
                values = Arrays.copyOf(values, values.length << 1);
            }
            values[size++] = value;
        }

        long[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }

    private static final class DebugRowList {
        private Object[] values;
        private int size;

        DebugRowList(int capacity) {
            this.values = new Object[Math.max(8, capacity)];
        }

        void add(Object value) {
            if (size >= values.length) {
                values = Arrays.copyOf(values, values.length << 1);
            }
            values[size++] = value;
        }

        Object[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }

    /**
     * entry 使用 key/value/next 三个 long 字段，既支持终态冲突链，也支持 free-ring。
     */
    private static final class EntryStore extends DsObject {
        private static final byte[] MAGIC = new byte[] {'.', 'E', 'N', '2'};
        private static final int HEADER_SIZE = 32;
        private static final int HDR_MAGIC = 0;
        private static final int HDR_NEXT_ENTRY_ID = 8;
        private static final int HDR_FREE_ENTRY_ID = 16;

        private static final int ENTRY_KEY = 0;
        private static final int ENTRY_VALUE = 8;
        private static final int ENTRY_NEXT = 16;
        private static final int ENTRY_SIZE = 24;

        private long nextEntryId;
        private long freeEntryId;

        EntryStore(File file) {
            super(file, HEADER_SIZE, ENTRY_SIZE);
            initHeader();
        }

        long allocate(long key, long value, long nextEntryId) throws IOException {
            headerOpLockWrite.lock();
            try {
                long entryId;
                if (freeEntryId != 0L) {
                    entryId = freeEntryId;
                    freeEntryId = readLong(entryId, ENTRY_NEXT);
                } else {
                    entryId = this.nextEntryId;
                    this.nextEntryId++;
                }
                headerBuffer.putLong(HDR_NEXT_ENTRY_ID, this.nextEntryId);
                headerBuffer.putLong(HDR_FREE_ENTRY_ID, freeEntryId);
                dirty(0L);
                writeLong(entryId, ENTRY_KEY, key);
                writeLong(entryId, ENTRY_VALUE, value);
                writeLong(entryId, ENTRY_NEXT, nextEntryId);
                return entryId;
            } finally {
                headerOpLockWrite.unlock();
            }
        }

        void free(long entryId) throws IOException {
            headerOpLockWrite.lock();
            try {
                writeLong(entryId, ENTRY_KEY, 0L);
                writeLong(entryId, ENTRY_VALUE, 0L);
                writeLong(entryId, ENTRY_NEXT, freeEntryId);
                freeEntryId = entryId;
                headerBuffer.putLong(HDR_FREE_ENTRY_ID, freeEntryId);
                dirty(0L);
            } finally {
                headerOpLockWrite.unlock();
            }
        }

        long readKey(long entryId) throws IOException {
            return readLong(entryId, ENTRY_KEY);
        }

        long readValue(long entryId) throws IOException {
            return readLong(entryId, ENTRY_VALUE);
        }

        long readNext(long entryId) throws IOException {
            return readLong(entryId, ENTRY_NEXT);
        }

        void writeValue(long entryId, long value) throws IOException {
            writeLong(entryId, ENTRY_VALUE, value);
        }

        void writeNext(long entryId, long nextEntryId) throws IOException {
            writeLong(entryId, ENTRY_NEXT, nextEntryId);
        }

        void clearStore() {
            headerOpLockWrite.lock();
            try {
                nextEntryId = 1L;
                freeEntryId = 0L;
                headerBuffer.putLong(HDR_NEXT_ENTRY_ID, nextEntryId);
                headerBuffer.putLong(HDR_FREE_ENTRY_ID, freeEntryId);
                dirty(0L);
            } finally {
                headerOpLockWrite.unlock();
            }
        }

        long getNextEntryId() {
            return nextEntryId;
        }

        long getFreeEntryId() {
            return freeEntryId;
        }

        long countFreeEntries() throws IOException {
            long count = 0;
            long currentEntryId = freeEntryId;
            while (currentEntryId != 0L) {
                count++;
                currentEntryId = readLong(currentEntryId, ENTRY_NEXT);
            }
            return count;
        }

        private void initHeader() {
            try {
                headerBuffer = loadBuffer(0L);
                byte[] magic = new byte[4];
                headerBuffer.get(HDR_MAGIC, magic, 0, 4);
                if (Arrays.equals(magic, MAGIC)) {
                    nextEntryId = headerBuffer.getLong(HDR_NEXT_ENTRY_ID);
                    freeEntryId = headerBuffer.getLong(HDR_FREE_ENTRY_ID);
                    return;
                }
                headerBuffer.put(HDR_MAGIC, MAGIC, 0, 4);
                nextEntryId = 1L;
                freeEntryId = 0L;
                headerBuffer.putLong(HDR_NEXT_ENTRY_ID, nextEntryId);
                headerBuffer.putLong(HDR_FREE_ENTRY_ID, freeEntryId);
                dirty(0L);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }
}
