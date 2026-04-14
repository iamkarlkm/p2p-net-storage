package com.q3lives.ds.legacy.db;

import com.q3lives.ds.core.DsObject;
import com.q3lives.ds.core.UnreadableBlockException;
import com.q3lives.ds.util.DsDataUtil;

import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.io.RandomAccessFile;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Arrays;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于固定长度数组和多级哈希的字符串存储系统。
 * <p>
 * 设计思想：
 * 1. 数据存储：使用固定长度数组（文件），长度按 2 的幂次增长（4B, 8B, 16B... 128MB）。
 * 2. 空间划分：分为共享空间（Shared）和独立空间（Independent）。每个空间包含 Index, Key, Value 三种格式。
 * 3. 存储逻辑：
 *    - Value: 根据长度存入对应 Bucket 文件，返回 ValueID。
 *    - Index: 组合 [ValueLen(4)+ValueID(8)+MD5(16)+KeyContent]，存入 Index Bucket 文件，返回 IndexID。
 *    - Master: MD5 -> IndexID，存入总控 MD5 文件（DbMasterIndex）。
 * 4. 查询逻辑：
 *    - MD5 -> IndexID (Master) -> Index Data (Index Bucket) -> Check MD5/Key -> ValueID -> Value Data (Value Bucket).
 * </p>
 */
public class DbSha256KV {

    private static ScheduledExecutorService scheduler;

    static {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DbSha256KV-Compaction-Thread");
            t.setDaemon(true);
            return t;
        });
        
        long now = System.currentTimeMillis();
        Calendar midnight = Calendar.getInstance();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        midnight.add(Calendar.DAY_OF_MONTH, 1);
        long initialDelay = midnight.getTimeInMillis() - now;
        
        scheduler.scheduleAtFixedRate(() -> {
            compactAll();
        }, initialDelay, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
    }

    private static final java.util.List<DbSha256KV> instances = new java.util.ArrayList<>();

    /**
     * 触发对所有 DbSha256KV 实例的 bucket 进行 free 文件紧缩（compaction）。
     *
     * <p>该方法由定时任务每天调用一次，也可以由外部显式调用。</p>
     */
    public static void compactAll() {
        synchronized(instances) {
            for (DbSha256KV db : instances) {
                db.compactBuckets();
            }
        }
    }
    
    private void compactBuckets() {
        bucketLock.lock();
        try {
            for (DbBucket bucket : buckets.values()) {
                bucket.compactFreeFile();
            }
        } finally {
            bucketLock.unlock();
        }
    }


    public static int MAX_FILE_SIZE = 128 * 1024 * 1024; // 128MB
    public static final String SHARED_SPACE = "shared";
    public static final String INDEPENDENT_SPACE = "independent";
    
    /** 文件头部大小 (字节) */
    public static final int HEADER_SIZE = 64;
    /** Index Data 中固定结构的总长度 (KeyLen:4 + ValueLen:4 + ValueID:8 + MD5:16 = 32) */
    public static final int INDEX_FIXED_PART_SIZE = 48;
    /** Value 长度在 Index Data 中的偏移量 */
    public static final int INDEX_OFFSET_VALUE_LEN = 4;
    /** Value ID 在 Index Data 中的偏移量 */
    public static final int INDEX_OFFSET_VALUE_ID = 8;
    /** MD5 在 Index Data 中的偏移量 */
    public static final int INDEX_OFFSET_SHA256 = 16;
    /** Key 内容在 Index Data 中的偏移量 */
    public static final int INDEX_OFFSET_KEY_CONTENT = 48;
    /** ID 编码中，Offset 占用的位数 */
    public static final int ID_OFFSET_BITS = 56;
    /** 最大支持的 Power (2^27 = 128MB) */
    public static final int MAX_POWER = 27;
    
    private final String rootDir;
    private final DbMasterIndex masterIndex;
    private final DbMasterIndex valueIndex;
    
    // 缓存 Bucket 文件处理器: <Space>_<Type>_<Power> -> DbBucket
    private final ConcurrentHashMap<String, DbBucket> buckets = new ConcurrentHashMap<>();
    private final ReentrantLock bucketLock = new ReentrantLock();

    /**
     * 创建一个基于 sha256(key) 的 KV 存储实例。
     *
     * <p>会初始化两个总控索引：</p>
     * <ul>
     *   <li>masterIndex：sha256(key) -> indexId</li>
     *   <li>valueIndex：sha256(value) -> indexId（用于按 value 反查）</li>
     * </ul>
     */
    public DbSha256KV(String rootDir) throws IOException {
        this.rootDir = rootDir;
        File root = new File(rootDir);
        if (!root.exists()) root.mkdirs();
        
        // 初始化总控 MD5 索引 (Key MD5 -> IndexID)
        File masterFile = new File(root, INDEPENDENT_SPACE + File.separator + "master_sha256.idx");
        if (!masterFile.getParentFile().exists()) masterFile.getParentFile().mkdirs();
        this.masterIndex = new DbMasterIndex(masterFile);
        
        // 初始化值索引 (Value MD5 -> IndexID)
        File valueIndexFile = new File(root, INDEPENDENT_SPACE + File.separator + "value_sha256.idx");
        this.valueIndex = new DbMasterIndex(valueIndexFile);
    }

    /**
     * 更新字符串。如果存在则覆盖，如果不存在则新增。
     * @param key 键
     * @param value 新值
     * @return 64位 IndexID
     */
    public long update(byte[] key, byte[] value) throws IOException, InterruptedException {
        // 由于我们的存储系统（特别是定长 Bucket）不支持就地更新长度不同的数据，
        // 且底层结构是追加或写新 Slot 的形式，更新的最简单方式就是：
        // 直接存入新数据，并更新 Master 索引指向新的 IndexID。
        // 旧数据会在底层留存，后续可通过垃圾回收（GC）机制或紧缩（Compaction）清理。
        byte[] sha256 = DsDataUtil.sha256(key);
        return put(key, sha256, value);
    }

    /**
     * 存储字符串。
     * @param key 键
     * @param value 值
     * @return 64位 IndexID
     */
    

    /**
     * 存储数据（核心逻辑）。
     * <p>
     * 严格遵循"无变长"原则：
     * 1. Value 直接存入对应长度的 Bucket（如果大小不是 2^N，则向上取整到 2^N）。
     * 2. Index Data 也是一个固定长度的数据块，存入对应长度的 Index Bucket。
     * </p>
     */
    public long put(byte[] keyContent, byte[] sha256, byte[] value) throws IOException, InterruptedException {
        // 1. 存储 Value
        long valueId;
        if (value.length > MAX_FILE_SIZE) {
            // 超过最大长度，分段存储
            int numChunks = (value.length + MAX_FILE_SIZE - 1) / MAX_FILE_SIZE;
            long[] chunkIds = new long[numChunks];
            for (int i = 0; i < numChunks; i++) {
                int offset = i * MAX_FILE_SIZE;
                int len = Math.min(MAX_FILE_SIZE, value.length - offset);
                byte[] chunk = new byte[len];
                System.arraycopy(value, offset, chunk, 0, len);
                chunkIds[i] = storeData(chunk, "value");
            }
            // 存储分段ID列表
            byte[] listData = new byte[numChunks * 8];
            for (int i = 0; i < numChunks; i++) {
                DsDataUtil.storeLong(listData, i * 8, chunkIds[i]);
            }
            valueId = storeData(listData, "value");
        } else {
            valueId = storeData(value, "value");
        }
        
        // 2. 构造 Index Data
        // 格式: [KeyLen(4)] [ValueLen(4)] [ValueID(8)] [MD5(16)] [KeyContent(N)]
        // 总长度 = 32 + KeyContent.length
        int indexDataRawLen = INDEX_FIXED_PART_SIZE + keyContent.length;
        
        // 我们需要将这个变长的 indexDataRawLen 放入一个固定长度的 Bucket 中。
        // storeData 会自动向上取整找到合适的 2^N Bucket。
        byte[] indexData = new byte[indexDataRawLen];
        
        DsDataUtil.storeInt(indexData, 0, keyContent.length);
        DsDataUtil.storeInt(indexData, INDEX_OFFSET_VALUE_LEN, value.length);
        DsDataUtil.storeLong(indexData, INDEX_OFFSET_VALUE_ID, valueId);
        System.arraycopy(sha256, 0, indexData, INDEX_OFFSET_SHA256, 32);
        System.arraycopy(keyContent, 0, indexData, INDEX_OFFSET_KEY_CONTENT, keyContent.length);
        
        // 3. 存储 Index Data
        long indexId = storeData(indexData, "index");
        
        // 4. 更新总控 MD5 索引
        masterIndex.put(sha256, indexId);
        
        // 5. 更新值索引 (Value MD5 -> IndexID)
        byte[] valueSha256 = DsDataUtil.sha256(value);
        valueIndex.put(valueSha256, indexId);
        
        return indexId;
    }

    /**
     * 获取字符串。
     */
    public byte[] get(byte[] key) throws IOException, InterruptedException {
        byte[] sha256 = DsDataUtil.sha256(key);
        byte[] valBytes = get(key, sha256);
        return valBytes;
    }

    /**
     * 根据值内容反推 Key。
     * @param value 值字符串
     * @return 对应的 Key，若不存在则返回 null
     */
    public byte[] getKeyByValue(byte[] value) throws IOException, InterruptedException {
        byte[] valSha256 = DsDataUtil.sha256(value);
        return getKeyByValueMd5(valSha256);
    }

    /**
     * 根据值 MD5 反推 Key。
     */
    public byte[] getKeyByValueMd5(byte[] valSha256) throws IOException, InterruptedException {
        // 1. 查询值索引 -> IndexID
        Long indexId = valueIndex.get(valSha256);
        if (indexId == null) return null;
        
        // 2. 获取 Index Data
        byte[] indexBucketData = getData(indexId, "index");
        if (indexBucketData == null) return null;
        
        // 3. 解析 Key
        if (indexBucketData.length < INDEX_FIXED_PART_SIZE) return null;
        
        int keyLen = DsDataUtil.loadInt(indexBucketData, 0);
        if (keyLen < 0 || indexBucketData.length < INDEX_FIXED_PART_SIZE + keyLen) return null;
        
        byte[] keyContent = new byte[keyLen];
        System.arraycopy(indexBucketData, INDEX_OFFSET_KEY_CONTENT, keyContent, 0, keyLen);
        
        return keyContent;
    }

    /**
     * 获取超大数据的分段ID列表。
     * @param key 键
     * @param page 页码（从 1 开始）
     * @param pageSize 每页大小（默认 5000）
     * @return 分段ID列表，若不是超大数据或不存在则返回 null
     */
    public List<Long> getChunkList(byte[] key, int page, int pageSize) throws IOException, InterruptedException {
        byte[] sha256 = DsDataUtil.sha256(key);
        return getChunkList(key, sha256, page, pageSize);
    }

    /**
     * 获取超大数据的分段 ID 列表（默认 page=1，pageSize=5000）。
     */
    public List<Long> getChunkList(byte[] key) throws IOException, InterruptedException {
        return getChunkList(key, 1, 5000);
    }

    /**
     * 获取超大数据的分段ID列表（核心逻辑）。
     */
    public List<Long> getChunkList(byte[] keyContent, byte[] sha256, int page, int pageSize) throws IOException, InterruptedException {
        // 1. 查询总控 MD5 索引 -> IndexID
        Long indexId = masterIndex.get(sha256);
        if (indexId == null) return null;
        
        // 2. 获取 Index Data
        byte[] indexBucketData = getData(indexId, "index");
        if (indexBucketData == null || indexBucketData.length < INDEX_FIXED_PART_SIZE) return null;
        
        int storedKeyLen = DsDataUtil.loadInt(indexBucketData, 0);
        int storedValueLen = DsDataUtil.loadInt(indexBucketData, INDEX_OFFSET_VALUE_LEN);
        long valueId = DsDataUtil.loadLong(indexBucketData, INDEX_OFFSET_VALUE_ID);
        
        // 校验数据完整性
        if (indexBucketData.length < INDEX_FIXED_PART_SIZE + storedKeyLen) return null;
        
        // 校验 MD5
        byte[] storedSha256 = new byte[32];
        System.arraycopy(indexBucketData, INDEX_OFFSET_SHA256, storedSha256, 0, 32);
        if (!Arrays.equals(sha256, storedSha256)) return null;
        
        // 校验 Key
        byte[] storedKey = new byte[storedKeyLen];
        System.arraycopy(indexBucketData, INDEX_OFFSET_KEY_CONTENT, storedKey, 0, storedKeyLen);
        if (!Arrays.equals(keyContent, storedKey)) return null;
        
        // 判断是否为超大数据
        if (storedValueLen <= MAX_FILE_SIZE) {
            return null; // 不是超大数据，没有分段列表
        }
        
        // 计算总分段数
        int totalChunks = (storedValueLen + MAX_FILE_SIZE - 1) / MAX_FILE_SIZE;
        
        // 4. 获取 Value（存储的是分段ID列表）
        byte[] valueBucketData = getData(valueId, "value");
        if (valueBucketData == null) return null;
        
        int startIdx = (page - 1) * pageSize;
        if (startIdx >= totalChunks) return new ArrayList<>();
        
        int endIdx = Math.min(startIdx + pageSize, totalChunks);
        List<Long> chunkIds = new ArrayList<>(endIdx - startIdx);
        
        for (int i = startIdx; i < endIdx; i++) {
            long chunkId = DsDataUtil.loadLong(valueBucketData, i * 8);
            chunkIds.add(chunkId);
        }
        
        return chunkIds;
    }

    /**
     * 获取数据（核心逻辑）。
     */
    public byte[] get(byte[] keyContent, byte[] sha256) throws IOException, InterruptedException {
        // 1. 查询总控 MD5 索引 -> IndexID
        Long indexId = masterIndex.get(sha256);
        if (indexId == null) return null;
        
        // 2. 获取 Index Data
        // 注意：getData 返回的是整个 Bucket 的固定长度内容 (例如 64字节, 128字节...)
        byte[] indexBucketData = getData(indexId, "index");
        if (indexBucketData == null) return null;
        
        // 3. 解析 Index Data
        // 即使 Bucket 比实际数据大，只要包含头部信息即可解析。
        if (indexBucketData.length < INDEX_FIXED_PART_SIZE) return null; 
        
        int storedKeyLen = DsDataUtil.loadInt(indexBucketData, 0);
        int storedValueLen = DsDataUtil.loadInt(indexBucketData, INDEX_OFFSET_VALUE_LEN);
        long valueId = DsDataUtil.loadLong(indexBucketData, INDEX_OFFSET_VALUE_ID);
        
        // 校验数据完整性
        if (indexBucketData.length < INDEX_FIXED_PART_SIZE + storedKeyLen) return null;
        
        // 校验 MD5
        byte[] storedSha256 = new byte[32];
        System.arraycopy(indexBucketData, INDEX_OFFSET_SHA256, storedSha256, 0, 32);
        if (!Arrays.equals(sha256, storedSha256)) return null;
        
        // 校验 Key
        byte[] storedKey = new byte[storedKeyLen];
        System.arraycopy(indexBucketData, INDEX_OFFSET_KEY_CONTENT, storedKey, 0, storedKeyLen);
        if (!Arrays.equals(keyContent, storedKey)) return null;
        
        // 检查是否为超大分段数据
        if (storedValueLen > MAX_FILE_SIZE) {
            throw new IllegalStateException("Data exceeds max file size (128MB), please use getChunkList to retrieve segment IDs.");
        }
        
        // 4. 获取 Value
        // 同样，getData 返回的是整个 Value Bucket 的内容
        byte[] valueBucketData = getData(valueId, "value");
        
        // 根据存储时记录的 storedValueLen 截取有效数据
        if (valueBucketData.length < storedValueLen) return null; // 理论上不应发生
        
        if (valueBucketData.length == storedValueLen) return valueBucketData;
        
        byte[] realValue = new byte[storedValueLen];
        System.arraycopy(valueBucketData, 0, realValue, 0, storedValueLen);
        return realValue;
    }

    /**
     * 根据分段ID查询对应分段内容
     * @param chunkId 分段ID
     * @return 分段的字节数组数据
     */
    public byte[] getChunkById(long chunkId) throws IOException {
        return getData(chunkId, "value");
    }

    /**
     * 【核心架构原则：全定长数组扫描】
     * 绕过正常 MD5 索引查询流程，直接根据 Key 内容长度定位到对应的 Index Bucket 文件，
     * 以固定长度（2^N）为步长顺序扫描文件，寻找匹配的 Key，并返回对应的 Value。
     * 
     * @param key 要查询的键
     * @return 对应的值，未找到返回 null
     */
    public byte[] scanValueByKey(byte[] key) throws IOException {
        if (key == null || key.length == 0) return null;
        byte[] keyContent = key;
        
        // 1. 确定 Index Data 的预期原始长度
        // 格式: [KeyLen(4)] [ValueLen(4)] [ValueID(8)] [MD5(16)] [KeyContent(N)]
        int indexDataRawLen = INDEX_FIXED_PART_SIZE + keyContent.length;
        
        // 2. 计算所在的 Bucket 大小 (Power of 2)
        int targetLen = indexDataRawLen;
        if (targetLen < 4) targetLen = 4;
        int p = 2;
        while ((1 << p) < targetLen) {
            p++;
        }
        int bucketSize = 1 << p;
        
        // 3. 获取对应的 Index Bucket
        DbBucket indexBucket = getBucket(INDEPENDENT_SPACE, "index", p);
        if (indexBucket == null) return null;
        
        // 4. 获取 Bucket 文件的总有效偏移量 (通过读取 Header 的 nextOffset)
        long maxOffset = indexBucket.getNextOffset();
        
        // 5. 顺序扫描文件 (跳过 Header 64字节，以 bucketSize 为步长)
        for (long offset = HEADER_SIZE; offset < maxOffset; offset += bucketSize) {
            byte[] bucketData = indexBucket.read(offset);
            
            // 校验是否是空槽位 (这里简单假设 KeyLen == 0 表示空)
            int storedKeyLen = DsDataUtil.loadInt(bucketData, 0);
            if (storedKeyLen == 0 || storedKeyLen != keyContent.length) {
                continue;
            }
            
            // 提取 Key 内容并比对
            byte[] storedKey = new byte[storedKeyLen];
            System.arraycopy(bucketData, INDEX_OFFSET_KEY_CONTENT, storedKey, 0, storedKeyLen);
            
            if (Arrays.equals(keyContent, storedKey)) {
                // 找到匹配的 Index Data
                // 解析 ValueID 和 ValueLen
                int valueLen = DsDataUtil.loadInt(bucketData, INDEX_OFFSET_VALUE_LEN);
                long valueId = DsDataUtil.loadLong(bucketData, INDEX_OFFSET_VALUE_ID);
                
                // 检查是否为超大分段数据
                if (valueLen > MAX_FILE_SIZE) {
                    throw new IllegalStateException("Data exceeds max file size (128MB), please use getChunkList to retrieve segment IDs.");
                }
                
                // 根据 ValueID 获取 Value Data
                byte[] valueBucketData = getData(valueId, "value");
                if (valueBucketData == null || valueBucketData.length < valueLen) {
                    return null;
                }
                
                // 截取有效长度的 Value
                byte[] realValue = new byte[valueLen];
                System.arraycopy(valueBucketData, 0, realValue, 0, valueLen);
                return realValue;
            }
        }
        
        return null;
    }

    /**
     * 【核心架构原则：全定长数组扫描】
     * 绕过正常 MD5 索引查询流程，直接根据 Value 内容长度定位到对应的 Value Bucket 文件，
     * 以固定长度（2^N）为步长顺序扫描文件，寻找匹配的 Value 数据，反推其 ValueID。
     * （目前仅返回反推的 ValueID，若要获取 Key 则需再去扫描 Index Bucket）
     * 
     * @param value 要查询的值
     * @return 对应的 ValueID，未找到返回 -1
     */
    public long scanValueIdByValue(byte[] value) throws IOException {
        if (value == null || value.length == 0) return -1L;
        byte[] valueContent = value;
        int len = valueContent.length;
        
        // 1. 计算所在的 Value Bucket 大小 (Power of 2)
        int targetLen = len;
        if (targetLen < 4) targetLen = 4;
        int p = 2;
        while ((1 << p) < targetLen) {
            p++;
        }
        int bucketSize = 1 << p;
        
        // 2. 获取对应的 Value Bucket
        DbBucket valueBucket = getBucket(INDEPENDENT_SPACE, "value", p);
        if (valueBucket == null) return -1L;
        
        // 3. 顺序扫描
        long maxOffset = valueBucket.getNextOffset();
        for (long offset = HEADER_SIZE; offset < maxOffset; offset += bucketSize) {
            byte[] bucketData = valueBucket.read(offset);
            
            // 提取有效数据进行比对
            // 因为 Value Bucket 直接存储 Value，没有单独的长度字段，
            // 且写入时如果数据不足 Bucket 大小会补 0。
            // 我们可以比对前 len 字节，并确认剩余字节是否全为 0 (或者忽略剩余字节)。
            // 为了安全，要求前 len 字节匹配。
            boolean match = true;
            for (int i = 0; i < len; i++) {
                if (bucketData[i] != valueContent[i]) {
                    match = false;
                    break;
                }
            }
            
            // 注意：如果有两个值，一个是 "abc"，另一个是 "abcd"，存在 8 字节 bucket 里：
            // "abc\0\0\0\0\0" 和 "abcd\0\0\0\0"
            // 仅比对前 3 字节，"abcd" 也会被误认为匹配 "abc"。
            // 所以，扫描时我们必须确保后面的填充字节全是 0（假设我们在 put 时补 0）。
            if (match) {
                boolean paddingZero = true;
                for (int i = len; i < bucketSize; i++) {
                    if (bucketData[i] != 0) {
                        paddingZero = false;
                        break;
                    }
                }
                if (paddingZero) {
                    // 找到了匹配的 Value
                    // 构造 ID: High 8 bits = p (Power), Low 56 bits = Offset
                    return ((long) p << ID_OFFSET_BITS) | offset;
                }
            }
        }
        
        return -1L;
    }

    /**
     * 【核心架构原则：全定长数组扫描】
     * 绕过正常流程，直接根据 Value 顺序扫描获取对应的 Key。
     * @param value 要查询的值
     * @return 对应的 Key
     */
    public byte[] scanKeyByValue(byte[] value) throws IOException {
        // 1. 扫描 Value Bucket 获取 ValueID
        long targetValueId = scanValueIdByValue(value);
        if (targetValueId == -1L) return null;
        
        // 2. 由于不知道 Index Data 的具体长度（因为不知道 Key 的长度），
        // 无法直接定位到唯一的 Index Bucket。
        // 但我们可以遍历所有已实例化的 Index Bucket（或者从 p=2 到最大允许值），
        // 扫描寻找 ValueID 匹配的 Index 记录。
        
        // 遍历可能的 Power 值 (假设最大 128MB，2^27)
        for (int p = 2; p <= MAX_POWER; p++) {
            String bucketKey = INDEPENDENT_SPACE + "_index_" + p;
            DbBucket indexBucket = buckets.get(bucketKey);
            if (indexBucket == null) {
                // 尝试加载文件如果存在
                int unitSize = 1 << p;
                String readableSize = formatUnitSize(unitSize);
                File file = new File(rootDir + File.separator + INDEPENDENT_SPACE + File.separator + "index" + File.separator + "data_" + readableSize + ".dat");
                if (!file.exists()) continue; // 文件不存在，跳过
                indexBucket = getBucket(INDEPENDENT_SPACE, "index", p);
            }
            
            int bucketSize = 1 << p;
            long maxOffset = indexBucket.getNextOffset();
            for (long offset = HEADER_SIZE; offset < maxOffset; offset += bucketSize) {
                byte[] bucketData = indexBucket.read(offset);
                
                // 解析 ValueID
                if (bucketData.length < INDEX_FIXED_PART_SIZE) continue;
                long storedValueId = DsDataUtil.loadLong(bucketData, INDEX_OFFSET_VALUE_ID);
                
                if (storedValueId == targetValueId) {
                    // 匹配成功，提取 Key
                    int keyLen = DsDataUtil.loadInt(bucketData, 0);
                    if (keyLen <= 0 || keyLen > bucketSize - INDEX_FIXED_PART_SIZE) continue;
                    
                    byte[] keyContent = new byte[keyLen];
                    System.arraycopy(bucketData, INDEX_OFFSET_KEY_CONTENT, keyContent, 0, keyLen);
                    return keyContent;
                }
            }
        }
        
        return null;
    }

    // --- 内部存储辅助方法 ---

    private long storeData(byte[] data, String type) throws IOException {
        int len = data.length;
        if (len == 0) return 0L;
        
        // 1. 确定 Bucket 大小 (Power of 2)
        // 规则: 找 >= len 的最小 2^N。
        // 这保证了数据总是存入一个确定的、固定长度的 Bucket 文件中。
        int targetLen = len;
        if (targetLen < 4) targetLen = 4;
        
        int p = 2; // 2^2 = 4
        while ((1 << p) < targetLen) {
            p++;
        }
        
        // 2. 获取对应长度的 Bucket
        DbBucket bucket = getBucket(INDEPENDENT_SPACE, type, p);
        
        // 3. 存入数据 (Bucket 会负责写入其固定长度的 Slot)
        // 注意：如果 data.length < bucketSize，我们需要补零吗？
        // DbBucket.add 目前是 writeBytes，它会写入 data.length 长度。
        // 为了严格遵守"固定长度"原则，我们应该写入整个 bucketSize 长度。
        // 如果 data 不足，后面补 0 (或保留原样，如果我们只读 data.length)。
        // 为了安全性，建议补 0。
        
        int bucketSize = 1 << p;
        byte[] fixedData = data;
        if (data.length < bucketSize) {
            fixedData = new byte[bucketSize];
            System.arraycopy(data, 0, fixedData, 0, data.length);
        }
        
        long offset = bucket.add(fixedData);
        
        // 构造 ID: High 8 bits = p (Power), Low 56 bits = Offset
        return ((long) p << ID_OFFSET_BITS) | offset;
    }

    
    
    /**
     * 通过 indexId 直接物理回收 index/value 对应的 bucket 单元（把 offset 加入 free 列表）。
     *
     * <p>注意：这是“物理回收”行为，会导致后续读取该 indexId/valueId 可能读到无效数据或被复用。</p>
     */
    public void deleteByIndexId(long indexId) throws IOException {
        // Free index block
        int indexP = (int) (indexId >>> ID_OFFSET_BITS);
        long indexOffset = indexId & 0x00FFFFFFFFFFFFFFL;
        DbBucket indexBucket = getBucket(INDEPENDENT_SPACE, "index", indexP);
        indexBucket.markFree(indexOffset);
        
        // Read index to get value ID and free it
        byte[] indexBucketData = null;
        try {
            indexBucketData = indexBucket.read(indexOffset);
        } catch (Exception e) {
            // ignore if unreadable
        }
        
        if (indexBucketData != null && indexBucketData.length >= INDEX_FIXED_PART_SIZE) {
            long valueId = DsDataUtil.loadLong(indexBucketData, INDEX_OFFSET_VALUE_ID);
            int valueP = (int) (valueId >>> ID_OFFSET_BITS);
            long valueOffset = valueId & 0x00FFFFFFFFFFFFFFL;
            DbBucket valueBucket = getBucket(INDEPENDENT_SPACE, "value", valueP);
            valueBucket.markFree(valueOffset);
        }
    }

    /**
     * 通过 indexId 直接读取 value 的原始 bytes。
     */
    public byte[] getValueByIndexId(long indexId) throws IOException {
        byte[] indexBucketData = getData(indexId, "index");
        if (indexBucketData == null || indexBucketData.length < INDEX_FIXED_PART_SIZE) return null;
        
        int storedValueLen = DsDataUtil.loadInt(indexBucketData, INDEX_OFFSET_VALUE_LEN);
        long valueId = DsDataUtil.loadLong(indexBucketData, INDEX_OFFSET_VALUE_ID);
        
        byte[] valueBucketData = getData(valueId, "value");
        if (valueBucketData == null) return null;
        
        byte[] valueContent = new byte[storedValueLen];
        System.arraycopy(valueBucketData, 0, valueContent, 0, storedValueLen);
        return valueContent;
    }

    /**
     * 读取一个 id 对应的底层 bucket 定长块（返回完整块，不自动按 length 截取）。
     */
    public byte[] getData(long id, String type) throws IOException {
        // getData 现在总是返回完整的 Bucket 数据块
        return getData(id, type, -1);
    }
    
    private byte[] getData(long id, String type, int expectedLen) throws IOException {
        int p = (int) (id >>> ID_OFFSET_BITS);
        long offset = id & 0x00FFFFFFFFFFFFFFL;
        
        DbBucket bucket = getBucket(INDEPENDENT_SPACE, type, p);
        byte[] raw = bucket.read(offset);
        
        // 注意：这里不再进行自动截取（除非显式要求），而是返回整个固定长度块。
        // 调用者负责根据 header 中的 length 字段解析有效载荷。
        return raw;
    }

    /**
     * 将字节大小转换为人类可读的格式（例如：128M, 1024K, 8B）
     */
    private String formatUnitSize(int bytes) {
        if (bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0) {
            return (bytes / (1024 * 1024)) + "M";
        } else if (bytes >= 1024 && bytes % 1024 == 0) {
            return (bytes / 1024) + "K";
        } else {
            return bytes + "B";
        }
    }

    private DbBucket getBucket(String space, String type, int power) throws IOException {
        String key = space + "_" + type + "_" + power;
        DbBucket bucket = buckets.get(key);
        if (bucket == null) {
            bucketLock.lock();
            try {
                bucket = buckets.get(key);
                if (bucket == null) {
                    int unitSize = 1 << power;
                    String readableSize = formatUnitSize(unitSize);
                    // 文件名: independent/value/data_8B.dat 或 data_128M.dat
                    File file = new File(rootDir + File.separator + space + File.separator + type + File.separator + "data_" + readableSize + ".dat");
                    if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
                    bucket = new DbBucket(file, unitSize);
                    buckets.put(key, bucket);
                }
            } finally {
                bucketLock.unlock();
            }
        }
        return bucket;
    }
    
    // --- 内部类 ---
    
    /**
     * 管理特定单元大小的存储桶（文件）。
     */
    static class DbBucket extends DsObject {
        
        private final int unitSize;
        private long nextOffset = 0;
        
        private RandomAccessFile freeRaf;
        private RandomAccessFile badRaf;
        private long freeHeadPointer = 8;
        private final ReentrantLock freeLock = new ReentrantLock();
        private final ReentrantLock badLock = new ReentrantLock();

        
        /**
         * 创建一个特定 unitSize 的 bucket 文件管理器。
         *
         * <p>会初始化 data/header、free 文件（.free）与 bad 文件（.bad）。</p>
         */
        public DbBucket(File file, int unitSize) {
            super(file, unitSize); // DsObject 构造函数
            this.unitSize = unitSize;
            checkHeader();
            initFreeAndBadFiles(file);
        }
        
        
        private void initFreeAndBadFiles(File file) {
            try {
                File freeFileObj = new File(file.getAbsolutePath() + ".free");
                File badFileObj = new File(file.getAbsolutePath() + ".bad");
                
                freeRaf = new RandomAccessFile(freeFileObj, "rw");
                if (freeRaf.length() >= 8) {
                    freeRaf.seek(0);
                    freeHeadPointer = freeRaf.readLong();
                } else {
                    freeRaf.seek(0);
                    freeRaf.writeLong(8);
                    freeHeadPointer = 8;
                }
                
                badRaf = new RandomAccessFile(badFileObj, "rw");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private long allocateOffset() throws IOException {
            freeLock.lock();
            try {
                if (freeHeadPointer < freeRaf.length()) {
                    freeRaf.seek(freeHeadPointer);
                    long offset = freeRaf.readLong();
                    freeHeadPointer += 8;
                    freeRaf.seek(0);
                    freeRaf.writeLong(freeHeadPointer);
                    return offset;
                }
            } finally {
                freeLock.unlock();
            }
            
            // fallback to nextOffset
            headerOpLock.lock();
            try {
                long offset = nextOffset;
                nextOffset += unitSize;
                headerBuffer.putLong(8, nextOffset);
                return offset;
            } finally {
                headerOpLock.unlock();
            }
        }

        /**
         * 把一个 offset 追加到 free 队列中，供后续 allocateOffset 复用。
         */
        public void markFree(long offset) {
            freeLock.lock();
            try {
                freeRaf.seek(freeRaf.length());
                freeRaf.writeLong(offset);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                freeLock.unlock();
            }
        }

        /**
         * 记录一个不可读的 offset（写入 .bad 文件）。
         */
        public void markBadBlock(long offset) {
            badLock.lock();
            try {
                badRaf.seek(badRaf.length());
                badRaf.writeLong(offset);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                badLock.unlock();
            }
        }

        /**
         * 紧缩 free 文件：把已消费的 headPointer 之前的数据丢弃，缩短文件长度。
         */
        public void compactFreeFile() {
            freeLock.lock();
            try {
                long len = freeRaf.length();
                if (freeHeadPointer > 8 && len > freeHeadPointer) {
                    long remaining = len - freeHeadPointer;
                    byte[] buf = new byte[(int)remaining];
                    freeRaf.seek(freeHeadPointer);
                    freeRaf.readFully(buf);
                    
                    freeRaf.seek(8);
                    freeRaf.write(buf);
                    freeRaf.setLength(8 + remaining);
                    
                    freeHeadPointer = 8;
                    freeRaf.seek(0);
                    freeRaf.writeLong(freeHeadPointer);
                } else if (freeHeadPointer > 8 && len == freeHeadPointer) {
                    freeRaf.setLength(8);
                    freeHeadPointer = 8;
                    freeRaf.seek(0);
                    freeRaf.writeLong(freeHeadPointer);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                freeLock.unlock();
            }
        }

        private void checkHeader() {
             try {
                headerBuffer = this.loadBuffer(0L);
                byte[] magicBytes = new byte[4];
                headerBuffer.get(0, magicBytes, 0, 4);
                String magic = ".BKT";
                if (Arrays.compare(magicBytes, magic.getBytes()) == 0) {
                    nextOffset = headerBuffer.getLong(8);
                } else {
                    // 初始化 Header
                    headerBuffer.put(0, magic.getBytes(), 0, 4);
                    headerBuffer.putInt(4, 0); // Total
                    // 起始偏移量跳过头部
                    // DsObject headerBuffer 是 map(0, BLOCK_SIZE)。
                    // 头部占用少量字节，为对齐方便，从 HEADER_SIZE 开始。
                    nextOffset = HEADER_SIZE; 
                    headerBuffer.putLong(8, nextOffset);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        
        /**
         * 追加写入一个定长单元并返回其 offset。
         *
         * <p>优先复用 free 队列中的 offset；否则使用 nextOffset 递增分配。</p>
         */
        public long add(byte[] data) throws IOException {
            long offset = allocateOffset();
            headerOpLock.lock();
            try {
                writeBytes(offset, data);
                return offset;
            } finally {
                headerOpLock.unlock();
            }
        }
        
        /**
         * 返回当前 nextOffset（下一次顺序分配的起点）。
         */
        public long getNextOffset() {
            return nextOffset;
        }

        /**
         * 读取一个定长单元（unitSize 字节）。
         *
         * <p>读取失败会把 offset 记入 bad 文件，并抛出 {@link UnreadableBlockException}。</p>
         */
        public byte[] read(long offset) throws IOException {
            byte[] data = new byte[unitSize];
            try {
                readBytes(offset, data);
            } catch (Exception e) {
                markBadBlock(offset);
                throw new UnreadableBlockException("Block unreadable at " + offset, offset);
            }
            return data;
        }
        
        private void writeBytes(long offsetIn, byte[] data) throws IOException {
            Long bufferIndex = offsetIn / BLOCK_SIZE;
            int offset = (int) (offsetIn % BLOCK_SIZE);
            MappedByteBuffer buffer = this.loadBuffer(bufferIndex);
            
            int len = data.length;
            if (len > unitSize) len = unitSize; // 截断
            
            if ((offset + len) > BLOCK_SIZE) {
                int first = BLOCK_SIZE - offset;
                int second = len - first;
                int pages = second / BLOCK_SIZE;
                int rest = second % BLOCK_SIZE;
                if (rest != 0) pages++;
                
                buffer.put(offset, data, 0, first);
                dirty(bufferIndex);
                
                for (int i = 0; i < pages; i++) {
                    bufferIndex++;
                    buffer = this.loadBuffer(bufferIndex);
                    int writeLen = (i == pages - 1 && rest != 0) ? rest : BLOCK_SIZE;
                    if (writeLen > second - i * BLOCK_SIZE) writeLen = second - i * BLOCK_SIZE;
                    buffer.put(0, data, first + i * BLOCK_SIZE, writeLen);
                    dirty(bufferIndex);
                }
            } else {
                buffer.put(offset, data, 0, len);
                dirty(bufferIndex);
            }
        }
        
        private void readBytes(long offsetIn, byte[] data) throws IOException {
             Long bufferIndex = offsetIn / BLOCK_SIZE;
            int offset = (int) (offsetIn % BLOCK_SIZE);
            MappedByteBuffer buffer = this.loadBuffer(bufferIndex);
            
            int len = data.length;
            
            if ((offset + len) > BLOCK_SIZE) {
                int first = BLOCK_SIZE - offset;
                int second = len - first;
                int pages = second / BLOCK_SIZE;
                int rest = second % BLOCK_SIZE;
                if (rest != 0) pages++;
                
                buffer.get(offset, data, 0, first);
                
                for (int i = 0; i < pages; i++) {
                    bufferIndex++;
                    buffer = this.loadBuffer(bufferIndex);
                    int readLen = (i == pages - 1 && rest != 0) ? rest : BLOCK_SIZE;
                    if (readLen > second - i * BLOCK_SIZE) readLen = second - i * BLOCK_SIZE;
                    buffer.get(0, data, first + i * BLOCK_SIZE, readLen);
                }
            } else {
                buffer.get(offset, data, 0, len);
            }
        }
    }
    
    /**
     * 总控 MD5 索引 (基于 DsHashSet 逻辑改造)。
     * 映射: MD5_Prefix (64bit) -> IndexID (64bit)。
     * 这是一个简单的哈希表实现，存储 16 字节的 Entry (Key, Value)。
     */
    
    /**
     * 总控 SHA-256 索引。
     * 映射: SHA-256 (256bit) -> IndexID (64bit)。
     * 这是一个简单的哈希表实现，存储 40 字节的 Entry (Key:32B, Value:8B)。
     */
    static class DbMasterIndex extends DsObject {
        // 每个 Bucket 存储: Key(32B) + Value(8B) = 40B
        private static final int SLOT_SIZE = 40;
        private static final int INITIAL_CAPACITY = 1024 * 1024; // 1M buckets
        
        private long capacity;
        
        /**
         * 创建一个总控索引文件（sha256(32B) -> indexId）。
         *
         * <p>该索引使用 hash64(sha256) 做线性探测哈希表。</p>
         */
        public DbMasterIndex(File file) {
            super(file, SLOT_SIZE);
            checkHeader();
        }
        
        private void checkHeader() {
            try {
                headerBuffer = this.loadBuffer(0L);
                byte[] magicBytes = new byte[4];
                headerBuffer.get(0, magicBytes, 0, 4);
                String magic = ".SHA";
                if (Arrays.compare(magicBytes, magic.getBytes()) == 0) {
                    capacity = headerBuffer.getLong(8);
                } else {
                    headerBuffer.put(0, magic.getBytes(), 0, 4);
                    capacity = INITIAL_CAPACITY;
                    headerBuffer.putLong(8, capacity);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        
        /**
         * 写入 sha256 -> indexId 映射。
         *
         * <p>冲突采用线性探测；满表会抛出异常（当前不实现自动扩容）。</p>
         */
        public void put(byte[] sha256, long indexId) throws IOException {
            // 使用 xxHash64 计算哈希值以均匀分布
            long hash = Math.abs(DsDataUtil.hash64(sha256)) % capacity;
            
            // 简单的线性探测 (Linear Probing)
            long startHash = hash;
            while (true) {
                long offset = HEADER_SIZE + hash * SLOT_SIZE; // Skip header
                byte[] slot = new byte[SLOT_SIZE];
                readBytesAt(offset, slot);
                boolean isEmpty = true;
                for (int i = 0; i < 32; i++) {
                    if (slot[i] != 0) {
                        isEmpty = false;
                        break;
                    }
                }

                if (isEmpty) {
                    System.arraycopy(sha256, 0, slot, 0, 32);
                    DsDataUtil.storeLong(slot, 32, indexId);
                    writeBytesAt(offset, slot);
                    return;
                }

                boolean match = true;
                for (int i = 0; i < 32; i++) {
                    if (slot[i] != sha256[i]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    DsDataUtil.storeLong(slot, 32, indexId);
                    writeBytesAt(offset, slot);
                    return;
                }
                
                // 冲突，探测下一个位置
                hash = (hash + 1) % capacity;
                if (hash == startHash) {
                    throw new IOException("Master Index full!");
                }
            }
        }
        
        /**
         * 查询 sha256 对应的 indexId，不存在返回 null。
         */
        public Long get(byte[] sha256) throws IOException {
            long hash = Math.abs(DsDataUtil.hash64(sha256)) % capacity;
            
            long startHash = hash;
            while (true) {
                long offset = HEADER_SIZE + hash * SLOT_SIZE;
                byte[] slot = new byte[SLOT_SIZE];
                readBytesAt(offset, slot);
                boolean isEmpty = true;
                for (int i = 0; i < 32; i++) {
                    if (slot[i] != 0) {
                        isEmpty = false;
                        break;
                    }
                }
                if (isEmpty) {
                    return null;
                }

                boolean match = true;
                for (int i = 0; i < 32; i++) {
                    if (slot[i] != sha256[i]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return DsDataUtil.loadLong(slot, 32);
                }
                
                hash = (hash + 1) % capacity;
                if (hash == startHash) return null;
            }
        }

        private void readBytesAt(long offsetIn, byte[] data) throws IOException {
            Long bufferIndex = offsetIn / BLOCK_SIZE;
            int offset = (int) (offsetIn % BLOCK_SIZE);
            MappedByteBuffer buffer = this.loadBuffer(bufferIndex);
            int len = data.length;

            if ((offset + len) <= BLOCK_SIZE) {
                buffer.get(offset, data, 0, len);
                return;
            }

            int first = BLOCK_SIZE - offset;
            buffer.get(offset, data, 0, first);
            int second = len - first;
            int pages = second / BLOCK_SIZE;
            int rest = second % BLOCK_SIZE;
            if (rest != 0) pages++;
            for (int i = 0; i < pages; i++) {
                bufferIndex++;
                buffer = this.loadBuffer(bufferIndex);
                int readLen = (i == pages - 1 && rest != 0) ? rest : BLOCK_SIZE;
                if (readLen > second - i * BLOCK_SIZE) readLen = second - i * BLOCK_SIZE;
                buffer.get(0, data, first + i * BLOCK_SIZE, readLen);
            }
        }

        private void writeBytesAt(long offsetIn, byte[] data) throws IOException {
            Long bufferIndex = offsetIn / BLOCK_SIZE;
            int offset = (int) (offsetIn % BLOCK_SIZE);
            MappedByteBuffer buffer = this.loadBuffer(bufferIndex);
            int len = data.length;

            if ((offset + len) <= BLOCK_SIZE) {
                buffer.put(offset, data, 0, len);
                dirty(bufferIndex);
                return;
            }

            int first = BLOCK_SIZE - offset;
            buffer.put(offset, data, 0, first);
            dirty(bufferIndex);
            int second = len - first;
            int pages = second / BLOCK_SIZE;
            int rest = second % BLOCK_SIZE;
            if (rest != 0) pages++;
            for (int i = 0; i < pages; i++) {
                bufferIndex++;
                buffer = this.loadBuffer(bufferIndex);
                int writeLen = (i == pages - 1 && rest != 0) ? rest : BLOCK_SIZE;
                if (writeLen > second - i * BLOCK_SIZE) writeLen = second - i * BLOCK_SIZE;
                buffer.put(0, data, first + i * BLOCK_SIZE, writeLen);
                dirty(bufferIndex);
            }
        }
    }

}
