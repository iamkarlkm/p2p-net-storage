package com.q3lives.ds.database.index;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.collections.DsHashMap;

/**
 * 二级索引等值存储（V0.2 non-unique）。
 *
 * <p>每个索引值(indexedValue)对应一个 long[] rowId 列表，支持多行共用同一索引值。
 * 内部用两层结构实现：</p>
 * <ul>
 *   <li>{@code indexMap}: DsHashMap&lt;Long,Long&gt;，key=indexedValue，value=rowId 列表在 DsFixedBucketStore 中的 bucketId。</li>
 *   <li>{@code rowStore}: DsFixedBucketStore，序列化存储 long[] rowId 列表（4 字节 count + 8*count 字节 rowId）。</li>
 * </ul>
 *
 * <p>设计约束（Karpathy Simplicity First）：</p>
 * <ul>
 *   <li>不侵入 ORM、注解、DsDatabaseLocal 以及任何子类。</li>
 *   <li>仅使用现有 DsHashMap + DsFixedBucketStore 原语，不新增通用集合。</li>
 *   <li>写入幂等：同一 (indexedValue, rowId) 重复 put 只保留一份。</li>
 *   <li>非线程安全，与 DsHashMap 一致。</li>
 * </ul>
 */
public class DsEqIndexStore implements AutoCloseable {
    public enum IndexedValueKind {
        LONG,
        STRING
    }

    public static final String INDEX_DIR_NAME = "indexes";
    public static final String INDEX_FILE_PREFIX = "eqidx_";
    public static final String INDEX_FILE_SUFFIX = ".dat";
    public static final long NOT_FOUND = 0L;

    private static final String ROW_STORE_TYPE = "rows";

    protected final File rootDir;
    protected final String space;
    protected final String indexName;
    protected final String safeName;
    protected final IndexedValueKind valueKind;
    protected final DsHashMap indexMap;
    protected final DsFixedBucketStore rowStore;

    /**
     * 打开或创建一个指定 space/indexName 的等值索引。
     *
     * @param rootDir   数据库根目录
     * @param space     数据空间名
     * @param indexName 索引名（允许任意字符，会转义为安全文件名）
     * @throws IOException 当底层存储初始化失败时
     */
    public DsEqIndexStore(File rootDir, String space, String indexName) throws IOException {
        this(rootDir, space, indexName, IndexedValueKind.LONG);
    }

    /**
     * 打开或创建一个指定 space/indexName 的等值索引（可选择 long / String 索引值）。
     */
    public DsEqIndexStore(File rootDir, String space, String indexName, IndexedValueKind valueKind) throws IOException {
        if (rootDir == null) {
            throw new IllegalArgumentException("rootDir cannot be null");
        }
        if (space == null || space.isBlank()) {
            throw new IllegalArgumentException("space cannot be null or blank");
        }
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("indexName cannot be null or blank");
        }
        this.rootDir = rootDir;
        this.space = space;
        this.indexName = indexName;
        this.safeName = safeFileName(indexName);
        this.valueKind = valueKind == null ? IndexedValueKind.LONG : valueKind;
        File indexDir = resolveIndexDir(rootDir, space);
        if (!indexDir.exists() && !indexDir.mkdirs()) {
            throw new IOException("Failed to create index directory: " + indexDir);
        }
        File indexFile = new File(indexDir, INDEX_FILE_PREFIX + safeName + INDEX_FILE_SUFFIX);
        this.indexMap = new DsHashMap(indexFile);
        this.rowStore = new DsFixedBucketStore(indexDir.getAbsolutePath());
    }

    private long hashString(String s) {
        if (s == null) return 0L;
        if (s.isEmpty()) return 0L;
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h == 0L ? 1L : h;
    }

    private long toMapKey(long indexedValue) {
        return indexedValue == 0L && valueKind == IndexedValueKind.STRING ? 1L : indexedValue;
    }

    public void putIndex(String indexedValue, long rowId) throws IOException {
        if (valueKind != IndexedValueKind.STRING) {
            throw new IllegalStateException("putIndex(String) requires IndexedValueKind.STRING");
        }
        putIndex(hashString(indexedValue), rowId);
    }

    public boolean removeIndex(String indexedValue) throws IOException {
        if (valueKind != IndexedValueKind.STRING) {
            throw new IllegalStateException("removeIndex(String) requires IndexedValueKind.STRING");
        }
        return removeIndex(hashString(indexedValue));
    }

    public boolean removeIndex(String indexedValue, long rowId) throws IOException {
        if (valueKind != IndexedValueKind.STRING) {
            throw new IllegalStateException("removeIndex(String,long) requires IndexedValueKind.STRING");
        }
        return removeIndex(hashString(indexedValue), rowId);
    }

    public boolean containsIndex(String indexedValue) throws IOException {
        if (valueKind != IndexedValueKind.STRING) {
            throw new IllegalStateException("containsIndex(String) requires IndexedValueKind.STRING");
        }
        return containsIndex(hashString(indexedValue));
    }

    public boolean containsIndex(String indexedValue, long rowId) throws IOException {
        if (valueKind != IndexedValueKind.STRING) {
            throw new IllegalStateException("containsIndex(String,long) requires IndexedValueKind.STRING");
        }
        return containsIndex(hashString(indexedValue), rowId);
    }

    public long findFirstByIndex(String indexedValue) throws IOException {
        if (valueKind != IndexedValueKind.STRING) {
            throw new IllegalStateException("findFirstByIndex(String) requires IndexedValueKind.STRING");
        }
        return findFirstByIndex(hashString(indexedValue));
    }

    public long[] findByIndex(String indexedValue) throws IOException {
        if (valueKind != IndexedValueKind.STRING) {
            throw new IllegalStateException("findByIndex(String) requires IndexedValueKind.STRING");
        }
        return findByIndex(hashString(indexedValue));
    }

    /**
     * 将 (indexedValue, rowId) 加入索引。若该 rowId 已存在则幂等。
     *
     * @param indexedValue 被索引的值（已统一转换为 long）
     * @param rowId        行 ID，不能为 0
     * @throws IOException 当写入失败时
     */
    public void putIndex(long indexedValue, long rowId) throws IOException {
        if (rowId == NOT_FOUND) {
            throw new IllegalArgumentException("rowId cannot be 0");
        }
        Long bucketId = this.indexMap.get(indexedValue);
        long[] current = (bucketId == null) ? new long[0] : readRowIds(bucketId);
        for (long r : current) {
            if (r == rowId) {
                return;
            }
        }
        long[] next = Arrays.copyOf(current, current.length + 1);
        next[next.length - 1] = rowId;
        long newBucketId = writeRowIds(bucketId, next);
        this.indexMap.put(indexedValue, newBucketId);
    }

    /**
     * 删除 indexedValue 下的全部 rowId 索引（V0.1 兼容语义）。
     *
     * @param indexedValue 被索引的值
     * @return 是否实际删除了条目
     * @throws IOException 当删除失败时
     */
    public boolean removeIndex(long indexedValue) throws IOException {
        Long bucketId = this.indexMap.remove(indexedValue);
        if (bucketId == null) {
            return false;
        }
        this.rowStore.remove(safeName, ROW_STORE_TYPE, bucketId);
        return true;
    }

    /**
     * 批量应用索引变更。map 中每个条目表示该 indexedValue 最终应指向的 rowId 集合。
     * <p>用于批量 putEntity 时减少 read-modify-write 次数：每个索引值只读一次、只写一次。</p>
     *
     * @param target indexedValue -> 最终 rowId 数组（允许为空，表示删除该索引值）
     * @throws IOException 当读写失败时
     */
    public void applyIndexBatch(Map<Long, long[]> target) throws IOException {
        if (target == null || target.isEmpty()) return;
        for (Map.Entry<Long, long[]> e : target.entrySet()) {
            long indexedValue = e.getKey();
            long[] next = e.getValue();
            if (next == null || next.length == 0) {
                removeIndex(indexedValue);
                continue;
            }
            Long bucketId = this.indexMap.get(indexedValue);
            long[] current = (bucketId == null) ? new long[0] : readRowIds(bucketId);
            if (Arrays.equals(current, next)) continue;
            long newBucketId = writeRowIds(bucketId, next);
            this.indexMap.put(indexedValue, newBucketId);
        }
    }

    /**
     * 删除 indexedValue 下的指定 rowId。若删除后列表为空，则同时删除该索引值条目。
     *
     * @param indexedValue 被索引的值
     * @param rowId        要删除的行 ID
     * @return 是否实际删除了 rowId
     * @throws IOException 当删除失败时
     */
    public boolean removeIndex(long indexedValue, long rowId) throws IOException {
        Long bucketId = this.indexMap.get(indexedValue);
        if (bucketId == null) {
            return false;
        }
        long[] current = readRowIds(bucketId);
        int idx = -1;
        for (int i = 0; i < current.length; i++) {
            if (current[i] == rowId) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return false;
        }
        if (current.length == 1) {
            this.rowStore.remove(safeName, ROW_STORE_TYPE, bucketId);
            this.indexMap.remove(indexedValue);
        } else {
            long[] next = new long[current.length - 1];
            System.arraycopy(current, 0, next, 0, idx);
            System.arraycopy(current, idx + 1, next, idx, current.length - idx - 1);
            long newBucketId = writeRowIds(bucketId, next);
            this.indexMap.put(indexedValue, newBucketId);
        }
        return true;
    }

    /**
     * 查找指定索引值的第一个 rowId。
     *
     * @param indexedValue 被索引的值
     * @return 第一个 rowId，不存在时返回 {@link #NOT_FOUND}
     * @throws IOException 当读取失败时
     */
    public long findFirstByIndex(long indexedValue) throws IOException {
        long[] rows = findByIndex(indexedValue);
        return rows.length == 0 ? NOT_FOUND : rows[0];
    }

    /**
     * 查找指定索引值对应的所有 rowId。
     *
     * @param indexedValue 被索引的值
     * @return rowId 数组；不存在时返回长度为 0 的数组
     * @throws IOException 当读取失败时
     */
    public long[] findByIndex(long indexedValue) throws IOException {
        Long bucketId = this.indexMap.get(indexedValue);
        if (bucketId == null) {
            return new long[0];
        }
        return readRowIds(bucketId);
    }

    public long[] findByBetween(long loInclusive, long hiInclusive) throws IOException {
        return findByRange(loInclusive, true, hiInclusive, true);
    }

    public long[] findByGt(long loExclusive) throws IOException {
        return findByRange(loExclusive, false, null, false);
    }

    public long[] findByGte(long loInclusive) throws IOException {
        return findByRange(loInclusive, true, null, false);
    }

    public long[] findByLt(long hiExclusive) throws IOException {
        return findByRange(null, false, hiExclusive, false);
    }

    public long[] findByLte(long hiInclusive) throws IOException {
        return findByRange(null, false, hiInclusive, true);
    }

    public long findFirstByBetween(long loInclusive, long hiInclusive) throws IOException {
        long[] rows = findByBetween(loInclusive, hiInclusive);
        return rows.length == 0 ? NOT_FOUND : rows[0];
    }

    public long findFirstByGt(long loExclusive) throws IOException {
        long[] rows = findByGt(loExclusive);
        return rows.length == 0 ? NOT_FOUND : rows[0];
    }

    public long findFirstByGte(long loInclusive) throws IOException {
        long[] rows = findByGte(loInclusive);
        return rows.length == 0 ? NOT_FOUND : rows[0];
    }

    public long findFirstByLt(long hiExclusive) throws IOException {
        long[] rows = findByLt(hiExclusive);
        return rows.length == 0 ? NOT_FOUND : rows[0];
    }

    public long findFirstByLte(long hiInclusive) throws IOException {
        long[] rows = findByLte(hiInclusive);
        return rows.length == 0 ? NOT_FOUND : rows[0];
    }

    public long[] findByRange(Long lo, boolean loInclusive, Long hi, boolean hiInclusive) throws IOException {
        if (valueKind != IndexedValueKind.LONG) {
            throw new IllegalStateException("findByRange/LONG-range queries require IndexedValueKind.LONG; STRING FNV-hash is unordered and not supported for range scan");
        }
        if (lo != null && hi != null) {
            long a = loInclusive ? lo : lo + 1;
            long b = hiInclusive ? hi : hi - 1;
            if (a > b) {
                return new long[0];
            }
        }
        List<long[]> perKeyResults = new ArrayList<>();
        int total = 0;
        List<Entry<Long, Long>> inRange = new ArrayList<>();
        Iterator<Entry<Long, Long>> it = this.indexMap.iterator();
        while (it.hasNext()) {
            Entry<Long, Long> e = it.next();
            long k = e.getKey();
            boolean loOk = (lo == null) || (loInclusive ? (k >= lo) : (k > lo));
            if (!loOk) continue;
            boolean hiOk = (hi == null) || (hiInclusive ? (k <= hi) : (k < hi));
            if (!hiOk) continue;
            inRange.add(e);
        }
        Collections.sort(inRange, Comparator.comparingLong(Entry::getKey));
        for (Entry<Long, Long> e : inRange) {
            long[] rows = readRowIds(e.getValue());
            if (rows.length > 0) {
                perKeyResults.add(rows);
                total += rows.length;
            }
        }
        if (total == 0) {
            return new long[0];
        }
        long[] out = new long[total];
        int pos = 0;
        for (long[] seg : perKeyResults) {
            System.arraycopy(seg, 0, out, pos, seg.length);
            pos += seg.length;
        }
        return out;
    }

    public long findFirstByRange(Long lo, boolean loInclusive, Long hi, boolean hiInclusive) throws IOException {
        long[] rows = findByRange(lo, loInclusive, hi, hiInclusive);
        return rows.length == 0 ? NOT_FOUND : rows[0];
    }

    /**
     * 是否包含该索引值（至少有一个 rowId）。
     *
     * @param indexedValue 被索引的值
     * @return true 如果存在
     */
    public boolean containsIndex(long indexedValue) throws IOException {
        return this.indexMap.containsKey(indexedValue);
    }

    /**
     * 是否包含指定的 (indexedValue, rowId) 索引。
     *
     * @param indexedValue 被索引的值
     * @param rowId        行 ID
     * @return true 如果存在
     * @throws IOException 当读取失败时
     */
    public boolean containsIndex(long indexedValue, long rowId) throws IOException {
        long[] rows = findByIndex(indexedValue);
        for (long r : rows) {
            if (r == rowId) {
                return true;
            }
        }
        return false;
    }

    /**
     * 当前索引中不同索引值的个数。
     *
     * @return 索引值数量
     */
    public long size() {
        return this.indexMap.size();
    }

    /**
     * 将索引数据同步落盘。
     *
     * @throws IOException 当同步失败时
     */
    public void sync() throws IOException {
        this.indexMap.syncAll();
        // DsFixedBucketStore 每次写入已 force，无需额外 sync 接口。
    }

    @Override
    public void close() throws IOException {
        try {
            this.indexMap.syncAll();
        } finally {
            try {
                this.rowStore.close();
            } finally {
                this.indexMap.close();
            }
        }
    }

    /**
     * 测试/人工重试按钮：强制删除该索引的全部磁盘文件，下次重新打开即为空索引。
     * 生产代码不应调用。
     *
     * @param rootDir   数据库根目录
     * @param space     数据空间名
     * @param indexName 索引名
     */
    public static void forceResetIndexForTest(File rootDir, String space, String indexName) {
        File indexDir = resolveIndexDir(rootDir, space);
        String safe = safeFileName(indexName);
        File[] files = indexDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String n = f.getName();
            // 删除主索引文件以及同一索引名对应的 rowStore 目录（空间名为 safeName）。
            if (n.equals(INDEX_FILE_PREFIX + safe + INDEX_FILE_SUFFIX) || n.equals(safe)) {
                deleteRecursive(f);
            }
        }
    }

    static File resolveIndexDir(File rootDir, String space) {
        return new File(rootDir, INDEX_DIR_NAME + "/" + space);
    }

    static String safeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unnamed";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '$') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() == 0 ? "unnamed" : sb.toString();
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }

    private long[] readRowIds(long bucketId) throws IOException {
        byte[] head = this.rowStore.get(safeName, ROW_STORE_TYPE, bucketId, 0, 4);
        if (head == null || head.length < 4) {
            return new long[0];
        }
        int count = decodeInt(head, 0);
        if (count <= 0) {
            return new long[0];
        }
        int totalLen = 4 + count * 8;
        byte[] data = this.rowStore.get(safeName, ROW_STORE_TYPE, bucketId, 0, totalLen);
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            out[i] = decodeLong(data, 4 + i * 8);
        }
        return out;
    }

    private long writeRowIds(Long oldBucketId, long[] rowIds) throws IOException {
        byte[] data = encodeRowIds(rowIds);
        if (oldBucketId == null) {
            return this.rowStore.put(safeName, ROW_STORE_TYPE, data);
        }
        return this.rowStore.update(safeName, ROW_STORE_TYPE, oldBucketId, data,
                DsFixedBucketStore.UpdatePolicy.SHRINK_TO_FIT);
    }

    private static byte[] encodeRowIds(long[] rowIds) {
        int len = 4 + rowIds.length * 8;
        byte[] data = new byte[len];
        encodeInt(data, 0, rowIds.length);
        for (int i = 0; i < rowIds.length; i++) {
            encodeLong(data, 4 + i * 8, rowIds[i]);
        }
        return data;
    }

    private static void encodeInt(byte[] b, int off, int v) {
        b[off + 0] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static int decodeInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static void encodeLong(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) {
            b[off + 7 - i] = (byte) (v >>> (i * 8));
        }
    }

    private static long decodeLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFF);
        }
        return v;
    }
}
