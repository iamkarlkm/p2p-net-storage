package com.q3lives.ds.bucket;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.q3lives.ds.core.DsObject;
import com.q3lives.ds.util.DsPathUtil;

/**
 * 固定长度 bucket 存储（按 2^power 分级）。
 *
 * <p>核心目标：</p>
 * <ul>
 *   <li>把任意长度的 byte[] 存储映射为某一档固定单元大小（bucketSize = 2^power）的写入/读取。</li>
 *   <li>每个 bucket 文件仅处理“定长单元”的随机读写；上层通过 {@link #encodeId(int, long)} 把 power 与 baseId 组合成全局 id。</li>
 *   <li>通过每个 bucket 对应的 .free 文件维护可复用的 baseId 环形队列（free-ring），实现空间回收与复用。</li>
 * </ul>
 *
 * <p>目录结构：</p>
 * <pre>
 *   rootDir/
 *     {spacePath}/{type}/data_{size}.dat      // bucket 数据文件
 *     {spacePath}/{type}/data_{size}.dat.free // bucket 回收队列（同目录）
 * </pre>
 *
 * <p>关于 space/type：</p>
 * <ul>
 *   <li>bucket 层不理解“业务路径”，只使用 space/type 作为物理目录分组。</li>
 *   <li>space 支持 Java 包名风格（a.b.c），内部用 {@link DsPathUtil#dottedToLinuxPath(String, String)} 转换为 a/b/c。</li>
 *   <li>type 必须是单段名称（不能包含 '/'、'\\'、'.'、'..'）。</li>
 * </ul>
 *
 * <p>ID 编码：</p>
 * <ul>
 *   <li>高 8 bit：power（bucketSize=2^power）</li>
 *   <li>低 56 bit：baseId（定长单元序号）</li>
 * </ul>
 */
public class DsFixedBucketStore {

    /**
     * bucket 文件头大小（byte）。
     * <p>头部用于存放 MAGIC、bucketSize、文件级元数据等；具体布局在内部 Bucket 实现中解析。</p>
     */
    public static final int HEADER_SIZE = 64;

    /**
     * id 的 offset/baseId 的 bit 宽度。
     * <p>低 56bit 足以寻址到 2^56 个定长单元（理论极限）。</p>
     */
    public static final int ID_OFFSET_BITS = 56;

    /**
     * 最大 power（即最大 bucketSize=2^MAX_POWER）。
     * <p>与上层允许写入的最大 value 长度相关。</p>
     */
    public static final int MAX_POWER = 27;

    /**
     * bucket 数据文件魔数 ".BK2"。
     */
    private static final byte[] MAGIC_BKT = new byte[] {'.', 'B', 'K', '2'};

    /**
     * free-ring 队列文件魔数 ".FRQ"。
     */
    private static final byte[] MAGIC_FREE = new byte[] {'.', 'F', 'R', 'Q'};

    /**
     * free 文件头大小（byte）。头部之后为 long[] 的环形队列数据区。
     */
    private static final int FREE_HEADER_SIZE = 64;
    private static final int FREE_OFFSET_MAGIC = 0;
    private static final int FREE_OFFSET_CAP = 8;
    private static final int FREE_OFFSET_HEAD = 16;
    private static final int FREE_OFFSET_TAIL = 24;
    private static final int FREE_OFFSET_COUNT = 32;
    private static final long DEFAULT_FREE_CAP = 1024L;

    /**
     * 共享空间：用于跨业务域共享的公共数据（由上层约定使用）。
     */
    public static final String SHARED_SPACE = "shared";

    /**
     * 独立空间：用于默认/内部索引等数据（由上层约定使用）。
     */
    public static final String INDEPENDENT_SPACE = "independent";

    /**
     * 更新策略：当新数据落入更小 power 档位时，是否收缩到更小 bucket。
     * <ul>
     *   <li>KEEP_BUCKET：保持原 bucket（原 id 不变，可能浪费一些空间，但避免迁移）</li>
     *   <li>SHRINK_TO_FIT：必要时迁移到更小 bucket（产生新 id，并回收旧 id）</li>
     * </ul>
     */
    public enum UpdatePolicy {
        KEEP_BUCKET,
        SHRINK_TO_FIT
    }

    private final String rootDir;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ReentrantLock bucketLock = new ReentrantLock();

    /**
     * 创建一个 bucket 存储实例。
     *
     * @param rootDir 存储根目录
     */
    public DsFixedBucketStore(String rootDir) {
        this.rootDir = rootDir;
        File root = new File(rootDir);
        if (!root.exists()) {
            root.mkdirs();
        }
    }

    /**
     * 分配一个新 id（按 data 长度选择 power 档位）。
     *
     * <p>返回值是“编码后的全局 id”，包含：</p>
     * <ul>
     *   <li>power：bucketSize=2^power</li>
     *   <li>baseId：在该 bucket 文件中的定长单元序号</li>
     * </ul>
     *
     * <p>注意：该方法只负责分配 id，不写入数据。</p>
     */
    public long getNewId(String space, String type, int length) throws IOException {
        int unitSize = toBucketSize(length);
        int power = log2(unitSize);
        return getNewIdByPower(space, type, power);
    }

    /**
     * 在指定 power 的 bucket 中分配一个新 id。
     *
     * <p>bucket 内部会优先从 free-ring 复用回收的 baseId；free-ring 为空时才递增分配新 baseId。</p>
     */
    public long getNewIdByPower(String space, String type, int power) throws IOException {
        Bucket bucket = getBucket(space, type, power);
        long baseId = bucket.allocateBaseId();
        return encodeId(power, baseId);
    }

    /**
     * 在指定 power 的 bucket 中批量分配 id。
     *
     * <p>优先批量消耗 free-ring 的 baseId，再用 nextId 递增补足剩余部分。</p>
     */
    public long[] getNewIdsByPower(String space, String type, int power, int size) throws IOException {
        if (size <= 0) {
            return new long[0];
        }
        Bucket bucket = getBucket(space, type, power);
        long[] baseIds = bucket.allocateBaseIds(size);
        long[] ids = new long[size];
        for (int i = 0; i < size; i++) {
            ids[i] = encodeId(power, baseIds[i]);
        }
        return ids;
    }

    /**
     * 按 length 推导 power 后，在指定 baseId 上覆盖写入（id 由调用方以 baseId 传入）。
     *
     * <p>该接口主要用于旧格式/兼容调用：调用方单独维护 baseId 与 length/power。</p>
     */
    public void overwriteByLength(String space, String type, long baseId, int length, byte[] data) throws IOException {
        int unitSize = toBucketSize(length);
        int power = log2(unitSize);
        overwriteByPower(space, type, power, baseId, data);
    }

    /**
     * 在指定 power/baseId 的 bucket 单元上覆盖写入（不分配新 id）。
     */
    public void overwriteByPower(String space, String type, int power, long baseId, byte[] data) throws IOException {
        if (data == null) {
            data = new byte[0];
        }
        Bucket bucket = getBucket(space, type, power);
        bucket.write(baseId, data);
    }

    /**
     * 按 length 推导 power 后读取（读取 length 字节）。
     */
    public byte[] getByLength(String space, String type, long baseId, int length) throws IOException {
        if (length <= 0) {
            return new byte[0];
        }
        int unitSize = toBucketSize(length);
        int power = log2(unitSize);
        return getByPower(space, type, power, baseId, length);
    }

    /**
     * 在指定 power/baseId 的 bucket 单元上读取（读取 length 字节）。
     */
    public byte[] getByPower(String space, String type, int power, long baseId, int length) throws IOException {
        if (length <= 0) {
            return new byte[0];
        }
        Bucket bucket = getBucket(space, type, power);
        return bucket.read(baseId, length);
    }

    /**
     * 按 length 推导 power 后回收 baseId（加入 free-ring）。
     */
    public void removeByLength(String space, String type, long baseId, int length) throws IOException {
        int unitSize = toBucketSize(length);
        int power = log2(unitSize);
        removeByPower(space, type, power, baseId);
    }

    /**
     * 在指定 power 的 bucket 中回收 baseId（加入 free-ring，不清零数据）。
     */
    public void removeByPower(String space, String type, int power, long baseId) throws IOException {
        Bucket bucket = getBucket(space, type, power);
        bucket.free(baseId);
    }

    /**
     * 更新一条记录：必要时迁移到新 power 桶并回收旧 id。
     *
     * <p>行为：</p>
     * <ul>
     *   <li>若 newPower==oldPower：原地覆盖写（id 不变）。</li>
     *   <li>若 newPower&lt;oldPower 且 policy=KEEP_BUCKET：仍在旧桶写（id 不变）。</li>
     *   <li>否则：分配新 id 写入 + 回收旧 id，返回新 id。</li>
     * </ul>
     */
    public long update(String space, String type, long oldId, byte[] data, UpdatePolicy policy) throws IOException {
        if (data == null) {
            data = new byte[0];
        }
        if (policy == null) {
            policy = UpdatePolicy.KEEP_BUCKET;
        }
        int oldPower = decodePower(oldId);
        int newPower = log2(toBucketSize(data.length));
        // 尽可能在原 bucket 中更新：
        // 1) 新旧 power 相同：原地覆盖写
        // 2) 新数据更小且策略 KEEP_BUCKET：仍在旧 bucket 写（id 不变）
        if (newPower == oldPower || (newPower < oldPower && policy == UpdatePolicy.KEEP_BUCKET)) {
            overwrite(space, type, oldId, data);
            return oldId;
        }
        // 需要迁移到其他 bucket：先分配新 id 写入，再回收旧 id（free-ring 复用）
        long newId = getNewIdByPower(space, type, newPower);
        overwrite(space, type, newId, data);
        remove(space, type, oldId);
        return newId;
    }

    /**
     * 局部更新：在原 id 对应的 bucket 单元中，从 offset 处覆盖写入 data。
     *
     * <p>该接口不会改变 id，也不会触发迁移；因此调用方必须保证 offset+data.length 不超过 bucketSize。</p>
     */
    public long update(String space, String type, long oldId, int offset, byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return oldId;
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        int power = decodePower(oldId);
        long baseId = decodeOffset(oldId);
        Bucket bucket = getBucket(space, type, power);
        bucket.writePartial(baseId, offset, data);
        return oldId;
    }

    /**
     * 局部读取：从指定 id 的 offset 处读取 length 字节。
     */
    public byte[] get(String space, String type, long id, int offset, int length) throws IOException {
        if (length <= 0) {
            return new byte[0];
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        int power = decodePower(id);
        long baseId = decodeOffset(id);
        Bucket bucket = getBucket(space, type, power);
        return bucket.readPartial(baseId, offset, length);
    }

    /**
     * 旧格式兼容：调用方以 oldBaseId+oldLength 表达旧记录位置，内部转为 oldId 再走 {@link #update(String, String, long, byte[], UpdatePolicy)}。
     */
    public long updateByLength(String space, String type, long oldBaseId, int oldLength, byte[] data, UpdatePolicy policy)
            throws IOException {
        int oldPower = log2(toBucketSize(oldLength));
        long oldId = encodeId(oldPower, oldBaseId);
        return update(space, type, oldId, data, policy);
    }

    /**
     * 旧格式兼容：调用方以 oldPower+oldBaseId 表达旧记录位置，内部转为 oldId 再走 {@link #update(String, String, long, byte[], UpdatePolicy)}。
     */
    public long updateByPower(String space, String type, int oldPower, long oldBaseId, byte[] data, UpdatePolicy policy)
            throws IOException {
        long oldId = encodeId(oldPower, oldBaseId);
        return update(space, type, oldId, data, policy);
    }

    /**
     * 写入一条新记录并返回 id（始终分配新 id）。
     */
    public long put(String space, String type, byte[] data) throws IOException {
        if (data == null) {
            data = new byte[0];
        }
        int rawLen = data.length;
        // put 语义：始终分配新 id 并写入；旧 id 的回收由上层选择 remove/update 来完成。
        long id = getNewId(space, type, rawLen);
        overwrite(space, type, id, data);
        return id;
    }

    /**
     * 读取一条记录的前 length 字节（按 id 解码出 power/baseId）。
     */
    public byte[] get(String space, String type, long id, int length) throws IOException {
        if (length <= 0) {
            return new byte[0];
        }
        int power = decodePower(id);
        long baseId = decodeOffset(id);
        Bucket bucket = getBucket(space, type, power);
        return bucket.read(baseId, length);
    }

    /**
     * 回收一条记录的 id（加入 free-ring）。
     */
    public void remove(String space, String type, long id) throws IOException {
        int power = decodePower(id);
        long baseId = decodeOffset(id);
        Bucket bucket = getBucket(space, type, power);
        bucket.free(baseId);
    }

    /**
     * 覆盖写入一条记录（id 不变）。
     */
    public void overwrite(String space, String type, long id, byte[] data) throws IOException {
        if (data == null) {
            data = new byte[0];
        }
        int power = decodePower(id);
        long baseId = decodeOffset(id);
        Bucket bucket = getBucket(space, type, power);
        bucket.write(baseId, data);
    }

    /**
     * 便捷重载：默认 space=independent。
     */
    public byte[] get(String type, long id, int length) throws IOException {
        return get(INDEPENDENT_SPACE, type, id, length);
    }

    /**
     * 便捷重载：默认 space=independent。
     */
    public void remove(String type, long id) throws IOException {
        remove(INDEPENDENT_SPACE, type, id);
    }

    /**
     * 关闭所有已打开的 bucket 文件与 free 文件句柄。
     */
    public void close() throws IOException {
        for (Bucket b : buckets.values()) {
            b.close();
        }
    }

    private Bucket getBucket(String space, String type, int power) throws IOException {
        String spacePath = DsPathUtil.dottedToLinuxPath(space, "space");
        DsPathUtil.validateSegment(type, "type");
        String key = space + "_" + type + "_" + power;
        Bucket b = buckets.get(key);
        if (b != null) {
            return b;
        }
        bucketLock.lock();
        try {
            b = buckets.get(key);
            if (b != null) {
                return b;
            }
            File file = new File(rootDir, spacePath + "/" + type + "/" + bucketFileName(power));
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            Bucket created = new Bucket(file, 1 << power);
            buckets.put(key, created);
            return created;
        } finally {
            bucketLock.unlock();
        }
    }

    private static String bucketFileName(int power) {
        long unit = 1L << power;
        return "data_" + readableSize(unit) + ".dat";
    }

    private static String readableSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return (bytes / (1024L * 1024 * 1024)) + "G";
        }
        if (bytes >= 1024L * 1024) {
            return (bytes / (1024L * 1024)) + "M";
        }
        if (bytes >= 1024L) {
            return (bytes / 1024L) + "K";
        }
        return bytes + "B";
    }

    private static int toBucketSize(int rawLen) {
        int n = rawLen <= 0 ? 4 : rawLen;
        int v = 1;
        while (v < n && v > 0) {
            v <<= 1;
        }
        if (v <= 0) {
            return 1 << MAX_POWER;
        }
        int max = 1 << MAX_POWER;
        return Math.min(v, max);
    }

    private static int log2(int v) {
        int p = 0;
        while ((1 << p) < v) {
            p++;
        }
        return p;
    }

    /**
     * 编码全局 id：高 8bit 为 power，低 56bit 为 baseId。
     *
     * <p>上层通过 id 即可反解出“应该落在哪个 bucket 档位”以及“在 bucket 文件中的单元位置”。</p>
     */
    private static long encodeId(int power, long offset) {
        return ((long) power << ID_OFFSET_BITS) | (offset & ((1L << ID_OFFSET_BITS) - 1));
    }

    /**
     * 从全局 id 中解出 power（bucketSize=2^power）。
     */
    private static int decodePower(long id) {
        return (int) ((id >>> ID_OFFSET_BITS) & 0xFF);
    }

    /**
     * 从全局 id 中解出 baseId（定长单元序号）。
     */
    private static long decodeOffset(long id) {
        return id & ((1L << ID_OFFSET_BITS) - 1);
    }

    private static final class Bucket extends DsObject {
        private final int unitSize;
        private final File freeFile;
        private final ReentrantLock headerLock = new ReentrantLock();
        private final ReentrantLock freeLock = new ReentrantLock();
        private RandomAccessFile freeRaf;
        private long freeCap;
        private long freeHead;
        private long freeTail;
        private long freeCount;

        Bucket(File dataFile, int unitSize) throws IOException {
            super(dataFile, 1);
            this.unitSize = unitSize;
            this.freeFile = new File(dataFile.getPath() + ".free");
            initHeader();
            initFree();
        }

        private void initHeader() throws IOException {
            headerLock.lock();
            try {
                headerBuffer = loadBuffer(0L);
                byte[] m = new byte[4];
                headerBuffer.get(0, m, 0, 4);
                if (Arrays.equals(m, MAGIC_BKT)) {
                    int u = headerBuffer.getInt(4);
                    if (u != unitSize) {
                        throw new IOException("bucket unitSize mismatch");
                    }
                    return;
                }
                headerBuffer.put(0, MAGIC_BKT, 0, 4);
                headerBuffer.putInt(4, unitSize);
                headerBuffer.putLong(8, 0L);
                dirty(0L);
            } finally {
                headerLock.unlock();
            }
        }

        /**
         * 分配一个 baseId（bucket 内部序号）。
         *
         * <p>优先从 free-ring 获取回收的 baseId；free-ring 为空时从 header 的 nextId 递增分配。</p>
         */
        long allocateBaseId() throws IOException {
            long fromFree = pollFree();
            if (fromFree >= 0) {
                return fromFree;
            }
            headerLock.lock();
            try {
                long nextId = headerBuffer.getLong(8);
                headerBuffer.putLong(8, nextId + 1);
                dirty(0L);
                return nextId;
            } finally {
                headerLock.unlock();
            }
        }

        /**
         * 批量分配 baseId。
         *
         * <p>实现上会先锁住 freeLock 尽可能取出回收 id，再锁 headerLock 从 nextId 补齐。</p>
         */
        long[] allocateBaseIds(int size) throws IOException {
            if (size <= 0) {
                return new long[0];
            }
            long[] ids = new long[size];
            int filled = 0;
            freeLock.lock();
            try {
                while (filled < size && freeCount > 0) {
                    long v = readFreeSlot(freeHead);
                    freeHead = (freeHead + 1) % freeCap;
                    freeCount--;
                    ids[filled++] = v;
                }
                writeFreeHeader();
            } finally {
                freeLock.unlock();
            }
            if (filled < size) {
                headerLock.lock();
                try {
                    long nextId = headerBuffer.getLong(8);
                    for (int i = filled; i < size; i++) {
                        ids[i] = nextId++;
                    }
                    headerBuffer.putLong(8, nextId);
                    dirty(0L);
                } finally {
                    headerLock.unlock();
                }
            }
            return ids;
        }

        void close() throws IOException {
            sync();
            freeLock.lock();
            try {
                if (freeRaf != null) {
                    freeRaf.close();
                    freeRaf = null;
                }
            } finally {
                freeLock.unlock();
            }
        }

        private long toOffset(long baseId) {
            return HEADER_SIZE + baseId * (long) unitSize;
        }

        /**
         * 回收一个 baseId：仅入队到 free-ring，不清零数据内容。
         */
        void free(long baseId) throws IOException {
            offerFree(baseId);
        }

        void write(long baseId, byte[] data) throws IOException {
            long offset = toOffset(baseId);
            Long bufferIndex = offset / BLOCK_SIZE;
            int bufferOffset = (int) (offset % BLOCK_SIZE);
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            int len = Math.min(unitSize, data.length);
            if (bufferOffset + unitSize <= BLOCK_SIZE) {
                buffer.put(bufferOffset, data, 0, len);
                if (len < unitSize) {
                    for (int i = bufferOffset + len; i < bufferOffset + unitSize; i++) {
                        buffer.put(i, (byte) 0);
                    }
                }
                dirty(bufferIndex);
                return;
            }

            int remain = len;
            int src = 0;
            long bi = bufferIndex;
            int off = bufferOffset;
            while (remain > 0) {
                buffer = loadBuffer(bi);
                int can = Math.min(BLOCK_SIZE - off, remain);
                buffer.put(off, data, src, can);
                dirty(bi);
                remain -= can;
                src += can;
                bi++;
                off = 0;
            }
        }

        void writePartial(long baseId, int offsetInUnit, byte[] data) throws IOException {
            if (offsetInUnit < 0) {
                throw new IllegalArgumentException("offset must be >= 0");
            }
            if (data == null || data.length == 0) {
                return;
            }
            if ((long) offsetInUnit + (long) data.length > (long) unitSize) {
                throw new IllegalArgumentException("data overflow bucket unit");
            }

            long abs = toOffset(baseId) + (long) offsetInUnit;
            Long bufferIndex = abs / BLOCK_SIZE;
            int bufferOffset = (int) (abs % BLOCK_SIZE);
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            if (bufferOffset + data.length <= BLOCK_SIZE) {
                buffer.put(bufferOffset, data, 0, data.length);
                dirty(bufferIndex);
                return;
            }

            int remain = data.length;
            int src = 0;
            long bi = bufferIndex;
            int off = bufferOffset;
            while (remain > 0) {
                buffer = loadBuffer(bi);
                int can = Math.min(BLOCK_SIZE - off, remain);
                buffer.put(off, data, src, can);
                dirty(bi);
                remain -= can;
                src += can;
                bi++;
                off = 0;
            }
        }

        byte[] readPartial(long baseId, int offsetInUnit, int length) throws IOException {
            if (offsetInUnit < 0) {
                throw new IllegalArgumentException("offset must be >= 0");
            }
            if (length <= 0) {
                return new byte[0];
            }
            if ((long) offsetInUnit + (long) length > (long) unitSize) {
                throw new IllegalArgumentException("data overflow bucket unit");
            }

            byte[] out = new byte[length];
            long abs = toOffset(baseId) + (long) offsetInUnit;
            Long bufferIndex = abs / BLOCK_SIZE;
            int bufferOffset = (int) (abs % BLOCK_SIZE);
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            if (bufferOffset + length <= BLOCK_SIZE) {
                buffer.get(bufferOffset, out, 0, length);
                return out;
            }

            int remain = length;
            int dst = 0;
            long bi = bufferIndex;
            int off = bufferOffset;
            while (remain > 0) {
                buffer = loadBuffer(bi);
                int can = Math.min(BLOCK_SIZE - off, remain);
                buffer.get(off, out, dst, can);
                remain -= can;
                dst += can;
                bi++;
                off = 0;
            }
            return out;
        }

        byte[] read(long baseId, int length) throws IOException {
            int len = Math.min(length, unitSize);
            byte[] out = new byte[len];
            long offset = toOffset(baseId);
            Long bufferIndex = offset / BLOCK_SIZE;
            int bufferOffset = (int) (offset % BLOCK_SIZE);
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            if (bufferOffset + len <= BLOCK_SIZE) {
                buffer.get(bufferOffset, out, 0, len);
                return out;
            }
            int remain = len;
            int dst = 0;
            long bi = bufferIndex;
            int off = bufferOffset;
            while (remain > 0) {
                buffer = loadBuffer(bi);
                int can = Math.min(BLOCK_SIZE - off, remain);
                buffer.get(off, out, dst, can);
                remain -= can;
                dst += can;
                bi++;
                off = 0;
            }
            return out;
        }

        private void initFree() throws IOException {
            freeLock.lock();
            try {
                if (freeRaf != null) {
                    return;
                }
                freeRaf = new RandomAccessFile(freeFile, "rw");
                if (freeRaf.length() < FREE_HEADER_SIZE) {
                    initFreeFile(DEFAULT_FREE_CAP);
                    return;
                }
                freeRaf.seek(FREE_OFFSET_MAGIC);
                byte[] m = new byte[4];
                freeRaf.readFully(m);
                if (!Arrays.equals(m, MAGIC_FREE)) {
                    initFreeFile(DEFAULT_FREE_CAP);
                    return;
                }
                freeRaf.seek(FREE_OFFSET_CAP);
                freeCap = freeRaf.readLong();
                freeHead = freeRaf.readLong();
                freeTail = freeRaf.readLong();
                freeCount = freeRaf.readLong();
                if (freeCap <= 0) {
                    initFreeFile(DEFAULT_FREE_CAP);
                }
            } finally {
                freeLock.unlock();
            }
        }

        private void initFreeFile(long cap) throws IOException {
            freeCap = Math.max(8L, cap);
            freeHead = 0L;
            freeTail = 0L;
            freeCount = 0L;
            freeRaf.setLength(FREE_HEADER_SIZE + freeCap * 8L);
            freeRaf.seek(FREE_OFFSET_MAGIC);
            freeRaf.write(MAGIC_FREE);
            writeFreeHeader();
        }

        private void writeFreeHeader() throws IOException {
            freeRaf.seek(FREE_OFFSET_CAP);
            freeRaf.writeLong(freeCap);
            freeRaf.writeLong(freeHead);
            freeRaf.writeLong(freeTail);
            freeRaf.writeLong(freeCount);
        }

        private long freeSlotPos(long slot) {
            return FREE_HEADER_SIZE + slot * 8L;
        }

        private long readFreeSlot(long slot) throws IOException {
            freeRaf.seek(freeSlotPos(slot));
            return freeRaf.readLong();
        }

        private void writeFreeSlot(long slot, long value) throws IOException {
            freeRaf.seek(freeSlotPos(slot));
            freeRaf.writeLong(value);
        }

        /**
         * 从 free-ring 弹出一个可复用 baseId。
         *
         * <p>返回 -1 表示当前无可复用项，需要从 nextId 分配新 baseId。</p>
         */
        private long pollFree() throws IOException {
            freeLock.lock();
            try {
                initFree();
                if (freeCount <= 0) {
                    return -1L;
                }
                long v = readFreeSlot(freeHead);
                freeHead = (freeHead + 1) % freeCap;
                freeCount--;
                writeFreeHeader();
                return v;
            } finally {
                freeLock.unlock();
            }
        }

        /**
         * 把 baseId 放回 free-ring，供后续复用。
         *
         * <p>当 free-ring 满时会触发 {@link #expandFree()} 扩容（容量翻倍）。</p>
         */
        private void offerFree(long baseId) throws IOException {
            freeLock.lock();
            try {
                initFree();
                if (freeCount >= freeCap) {
                    expandFree();
                }
                writeFreeSlot(freeTail, baseId);
                freeTail = (freeTail + 1) % freeCap;
                freeCount++;
                writeFreeHeader();
            } finally {
                freeLock.unlock();
            }
        }

        /**
         * 扩容 free-ring（容量翻倍）。
         *
         * <p>实现采用“写新文件 + 搬迁当前队列内容 + 原子替换”的方式：</p>
         * <ul>
         *   <li>把 ring 中从 freeHead 开始的 freeCount 个元素线性写入新文件的数据区。</li>
         *   <li>替换后将 freeHead 置 0，freeTail 置 freeCount，保持队列语义不变。</li>
         * </ul>
         */
        private void expandFree() throws IOException {
            long newCap = freeCap * 2L;
            File tmp = new File(freeFile.getPath() + ".tmp");
            RandomAccessFile out = new RandomAccessFile(tmp, "rw");
            try {
                out.setLength(FREE_HEADER_SIZE + newCap * 8L);
                out.seek(FREE_OFFSET_MAGIC);
                out.write(MAGIC_FREE);
                out.seek(FREE_OFFSET_CAP);
                out.writeLong(newCap);
                out.writeLong(0L);
                out.writeLong(freeCount);
                out.writeLong(freeCount);
                for (long i = 0; i < freeCount; i++) {
                    long slot = (freeHead + i) % freeCap;
                    long v = readFreeSlot(slot);
                    out.seek(FREE_HEADER_SIZE + i * 8L);
                    out.writeLong(v);
                }
            } finally {
                out.close();
            }
            freeRaf.close();
            freeRaf = null;
            Files.move(tmp.toPath(), freeFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            freeRaf = new RandomAccessFile(freeFile, "rw");
            freeCap = newCap;
            freeHead = 0L;
            freeTail = freeCount;
        }
    }
}
