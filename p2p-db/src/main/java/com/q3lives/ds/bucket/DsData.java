package com.q3lives.ds.bucket;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import com.q3lives.ds.index.master.DsTieredMasterIndex;
import com.q3lives.ds.util.DsDataUtil;
import com.q3lives.ds.util.DsPathUtil;

/**
 * 基于 bucket 层的“内容寻址 + 引用计数”的通用数据存储（valueBytes -> indexId）。
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li>去重：相同 valueBytes 只存一份 value，重复 put 会返回同一个 indexId。</li>
 *   <li>引用计数：每个 indexId 维护 refCount；remove 只做 refCount--，归零才物理删除 value 与索引。</li>
 *   <li>定长索引记录：index record 固定 16B（便于上层当作“基本属性/指针表”使用）。</li>
 *   <li>局部读写：支持按 offset/len 读取 value 的子片段；写入采用 Copy-On-Write 语义。</li>
 * </ul>
 *
 * <p>为什么需要两层（masterIndex + bucketStore）：</p>
 * <ul>
 *   <li>masterIndex 负责“内容寻址”：用 valueBytes 找到 indexId。这样上层不需要自己维护 hash->id 的结构。</li>
 *   <li>bucketStore 负责“定长/变长数据落盘”：valueBytes 存在 TYPE_VALUE；索引 record（固定 16B）存在 TYPE_INDEX。</li>
 *   <li>拆分的理由是为了同时满足：查找快（索引层） + 数据可复用/可回收（valueId、refCount）。</li>
 * </ul>
 *
 * <p>index record（16B）布局：</p>
 * <ul>
 *   <li>int valueLen</li>
 *   <li>short valueHash16（hash32 的低 16 位，用于快速过滤）</li>
 *   <li>short refCount</li>
 *   <li>long valueId（bucket 层的 value 记录 id）</li>
 * </ul>
 *
 * <p>索引：</p>
 * <ul>
 *   <li>masterIndex：{@link DsTieredMasterIndex}，以 valueBytes 为 key 映射到 indexId。</li>
 *   <li>碰撞处理：当 hash32/hash64/md5/sha256 发生碰撞时，由 {@link DsTieredMasterIndex#promoteOnCollision(byte[], byte[], long)} 升级。</li>
 * </ul>
 *
 * <p>Copy-On-Write（重要语义）：</p>
 * <ul>
 *   <li>当 refCount>1 时，修改 value 不会原地覆盖，而是 refCount-- 并创建新 value+新 indexId 返回给调用者。</li>
 *   <li>调用者需自行判断返回的 indexId 是否变化。</li>
 * </ul>
 */
public class DsData {
    public static final int INDEX_RECORD_SIZE = 16;

    private static final String TYPE_VALUE = "data_value";
    private static final String TYPE_INDEX = "data_index";

    private final DsFixedBucketStore bucketStore;
    private final DsTieredMasterIndex masterIndex;

    private final String space;

    /**
     * 创建一个 DsData（默认 space=independent）。
     *
     * @param dirPath 根目录
     * @param storeName 存储名（单段名称）
     */
    public DsData(String dirPath, String storeName) throws IOException {
        this(dirPath, storeName, DsFixedBucketStore.DATA_SPACE);
    }

    /**
     * 创建一个 DsData。
     *
     * <p>space 用于隔离不同业务域的数据与索引目录；调用方可传入 independent/shared 或自定义空间名。</p>
     *
     * @param dirPath 根目录
     * @param storeName 存储名（单段名称）
     * @param space bucket space（可为 dotted）
     */
    public DsData(String dirPath, String storeName, String space) throws IOException {
        DsPathUtil.validateSegment(storeName, "storeName");
        this.space = space;
        File dir = new File(dirPath, storeName);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.bucketStore = new DsFixedBucketStore(dir.getAbsolutePath());
        File masterDir = new File(dir, space + File.separator + "master_data");
        if (!masterDir.exists()) {
            masterDir.mkdirs();
        }
        this.masterIndex = new DsTieredMasterIndex(masterDir);
    }

    /**
     * 写入 valueBytes 并返回 indexId。
     *
     * <p>如果 value 已存在，会把对应 indexId 的 refCount++ 并返回旧 id；否则创建新 valueId+indexId（refCount=1）。</p>
     */
    public long put(byte[] valueBytes) throws IOException {
        if (valueBytes == null) {
            // 统一把 null 视为“空值”，避免上层出现两套语义（null vs empty）导致去重/索引不一致
            valueBytes = new byte[0];
        }
        int valueLen = valueBytes.length;
        // 这里提前缓存 hash16 的原因：
        // 1) readIndexRecord 只拿到 valueLen/valueHash16/refCount/valueId，不需要每次都把 value 读出来；
        // 2) 短哈希只用于快速过滤，最终仍以 Arrays.equals 做强校验，避免误判。
        short hash16 = (short) (DsDataUtil.hash32(valueBytes) & 0xFFFF);

        for (int attempt = 0; attempt < 8; attempt++) {
            Long indexId = masterIndex.get(valueBytes);
            if (indexId == null) {
                // “未命中”：需要创建新 value 与新 index record，并把 valueBytes->indexId 放入 masterIndex
                // 注意：masterIndex.put(...) 在最后执行，是为了避免在 value/index 写入失败时留下“悬挂映射”
                long valueId = bucketStore.put(space, TYPE_VALUE, valueBytes);
                byte[] record = buildIndexRecord(valueLen, hash16, (short) 1, valueId);
                long newIndexId = bucketStore.put(space, TYPE_INDEX, record);
                masterIndex.put(valueBytes, newIndexId, false);
                return newIndexId;
            }

            IndexRecord r = readIndexRecord(indexId);
            if (r.valueLen == valueLen && r.valueHash16 == hash16) {
                // 快速过滤通过后，再把 value 拉出来做强校验，避免 hash/长度碰撞导致误复用
                byte[] stored = bucketStore.get(space, TYPE_VALUE, r.valueId, valueLen);
                if (Arrays.equals(stored, valueBytes)) {
                    // 同值：只增加 refCount，不重复写 value
                    // refCount 用 short 的原因：index record 固定 16B，且引用计数通常不会巨大
                    short newRef = (short) (r.refCount + 1);
                    byte[] record = buildIndexRecord(r.valueLen, r.valueHash16, newRef, r.valueId);
                    bucketStore.overwrite(space, TYPE_INDEX, indexId, record);
                    return indexId;
                }
            }

            byte[] otherValue = bucketStore.get(space, TYPE_VALUE, r.valueId, r.valueLen);
            // 发生碰撞：masterIndex 的层级哈希命中到了同一个 indexId，但 value 不相同
            // 通过 promoteOnCollision(...) 升级索引层级（例如从 hash32 升级到更强层），然后重试
            masterIndex.promoteOnCollision(valueBytes, otherValue, indexId);
        }

        throw new IOException("Too many collisions");
    }

    /**
     * 查询 valueBytes 对应的 indexId（不改变引用计数）。
     */
    public Long getIndexId(byte[] valueBytes) throws IOException {
        if (valueBytes == null) {
            return null;
        }
        return masterIndex.get(valueBytes);
    }

    /**
     * 通过 indexId 读取完整 valueBytes。
     */
    public byte[] getValueByIndexId(long indexId) throws IOException {
        IndexRecord r = readIndexRecord(indexId);
        return bucketStore.get(space, TYPE_VALUE, r.valueId, r.valueLen);
    }

    /**
     * 通过 indexId 读取 valueBytes 的子片段。
     *
     * @param offset 起始偏移（>=0）
     * @param length 读取长度（>=0）
     */
    public byte[] getPartialValueByIndexId(long indexId, int offset, int length) throws IOException {
        IndexRecord r = readIndexRecord(indexId);
        if (offset < 0 || length < 0 || offset + length > r.valueLen) {
            throw new IndexOutOfBoundsException("Invalid offset/length for valueLen " + r.valueLen);
        }
        return bucketStore.get(space, TYPE_VALUE, r.valueId, offset, length);
    }

    /**
     * 局部修改 valueBytes（Copy-On-Write）。
     *
     * <p>当 refCount==1：尽量原地更新，并保持 indexId 不变；同时更新 masterIndex 的 key（oldVal->newVal）。</p>
     * <p>当 refCount&gt;1：执行 COW（old ref--，put(newVal) 返回新的 indexId）。调用方需自行判断返回值是否变化。</p>
     *
     * @return 修改后对应的 indexId（可能与入参相同，也可能返回新的 id）
     */
    public long updatePartialValueByIndexId(long indexId, int offset, byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            // 空写入没有意义，直接返回避免无谓 IO
            return indexId;
        }
        IndexRecord r = readIndexRecord(indexId);
        if (offset < 0 || offset + data.length > r.valueLen) {
            throw new IndexOutOfBoundsException("Invalid offset/data length for valueLen " + r.valueLen);
        }

        // 先构造“修改后的新值”，用于判断是否真的发生变化，以及后续去重/升级。
        // 这里选择“读全量 value 再 patch”的原因：
        // 1) masterIndex 是以内容寻址的，更新必须知道 newVal 才能正确改 key；
        // 2) 还需要与潜在重复值合并（newVal 可能已经存在），因此必须得到完整 newVal。
        byte[] oldVal = bucketStore.get(space, TYPE_VALUE, r.valueId, r.valueLen);
        byte[] newVal = Arrays.copyOf(oldVal, oldVal.length);
        System.arraycopy(data, 0, newVal, offset, data.length);
        if (Arrays.equals(oldVal, newVal)) {
            // 实际没有变化：保持 indexId 不变，避免写放大
            return indexId;
        }

        if ((r.refCount & 0xFFFF) > 1) {
            // Copy-On-Write：非唯一拥有者不允许原地修改。
            // 1) 先把旧 indexId 的 refCount--（释放一次引用）
            // 2) 再 put(newVal) 创建/复用新的 indexId，并把新 id 返回给调用者
            short newRef = (short) (r.refCount - 1);
            byte[] record = buildIndexRecord(r.valueLen, r.valueHash16, newRef, r.valueId);
            bucketStore.overwrite(space, TYPE_INDEX, indexId, record);
            return put(newVal);
        }

        for (int attempt = 0; attempt < 8; attempt++) {
            Long otherIndexId = masterIndex.get(newVal);
            if (otherIndexId == null) {
                // 唯一拥有者 + 新值未存在：原地更新 value，并把 masterIndex 从 oldVal->indexId 改为 newVal->indexId
                // 先 remove(oldVal) 再 put(newVal) 的理由：
                // - masterIndex 的 key 是 valueBytes 本身，更新时必须把旧 key 移除
                // - 先移除再写入，避免 oldVal/newVal 同时指向同一个 indexId 造成歧义
                masterIndex.remove(oldVal);
                bucketStore.update(space, TYPE_VALUE, r.valueId, offset, data);
                short newHash16 = (short) (DsDataUtil.hash32(newVal) & 0xFFFF);
                byte[] record = buildIndexRecord(r.valueLen, newHash16, r.refCount, r.valueId);
                bucketStore.overwrite(space, TYPE_INDEX, indexId, record);
                masterIndex.put(newVal, indexId, false);
                return indexId;
            }
            if (otherIndexId == indexId) {
                // 保护性分支：如果 masterIndex 已经映射到自身（可能由上一次写入/重试造成），仍按“原地更新+重绑映射”处理
                // 这里 put(..., true) 的理由：允许覆盖同 key 的既有映射，避免并发/重试导致的异常
                masterIndex.remove(oldVal);
                bucketStore.update(space, TYPE_VALUE, r.valueId, offset, data);
                short newHash16 = (short) (DsDataUtil.hash32(newVal) & 0xFFFF);
                byte[] record = buildIndexRecord(r.valueLen, newHash16, r.refCount, r.valueId);
                bucketStore.overwrite(space, TYPE_INDEX, indexId, record);
                masterIndex.put(newVal, indexId, true);
                return indexId;
            }

            IndexRecord other = readIndexRecord(otherIndexId);
            if (other.valueLen == r.valueLen && other.valueHash16 == (short) (DsDataUtil.hash32(newVal) & 0xFFFF)) {
                byte[] otherVal = bucketStore.get(space, TYPE_VALUE, other.valueId, other.valueLen);
                if (Arrays.equals(otherVal, newVal)) {
                    // 新值已存在：合并到已有 indexId（otherIndexId），并释放旧 indexId（引用归零会回收）
                    // 合并理由：保证“内容寻址去重”的核心不变量——同内容只保留一份 value
                    short otherNewRef = (short) (other.refCount + 1);
                    byte[] otherRecord = buildIndexRecord(other.valueLen, other.valueHash16, otherNewRef, other.valueId);
                    bucketStore.overwrite(space, TYPE_INDEX, otherIndexId, otherRecord);
                    remove(indexId);
                    return otherIndexId;
                }
            }

            byte[] otherVal = bucketStore.get(space, TYPE_VALUE, other.valueId, other.valueLen);
            // 确认发生碰撞（hash 相同但 value 不同），让 masterIndex 升级到更强哈希层后重试
            masterIndex.promoteOnCollision(newVal, otherVal, otherIndexId);
        }

        throw new IOException("Too many collisions");
    }

    /**
     * 获取 indexId 当前的引用计数（读取失败返回 0）。
     */
    public int getRefCountByIndexId(long indexId) throws IOException {
        try {
            IndexRecord r = readIndexRecord(indexId);
            return r.refCount & 0xFFFF;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 把 indexId 的引用计数 +1（存在且 refCount&gt;0 时生效）。
     *
     * @return 成功增加返回 true；不存在/已删除返回 false
     */
    public boolean retain(long indexId) throws IOException {
        try {
            IndexRecord r = readIndexRecord(indexId);
            int ref = r.refCount & 0xFFFF;
            if (ref <= 0) {
                // refCount<=0 代表已删除或无效，retain 不应把“死记录”复活
                return false;
            }
            if (ref >= 0xFFFF) {
                // 饱和保护：short 只有 16-bit，达到上限后继续 +1 会溢出变负数
                return true;
            }
            short newRef = (short) (ref + 1);
            byte[] record = buildIndexRecord(r.valueLen, r.valueHash16, newRef, r.valueId);
            bucketStore.overwrite(space, TYPE_INDEX, indexId, record);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 释放一次引用（refCount--）。
     *
     * <p>当 refCount 递减到 0 时，会删除 masterIndex 映射并回收 index/value 的 bucket id。</p>
     *
     * @param indexId
     * @return 成功释放返回 true；不存在/已删除返回 false
     * @throws java.io.IOException
     */
    public boolean remove(long indexId) throws IOException {
        try {
            IndexRecord r = readIndexRecord(indexId);
            if (r.refCount <= 0) {
                return false;
            }
            if (r.refCount > 1) {
                // 还有其他引用：仅做 ref--，不做真实删除
                short newRef = (short) (r.refCount - 1);
                byte[] record = buildIndexRecord(r.valueLen, r.valueHash16, newRef, r.valueId);
                bucketStore.overwrite(space, TYPE_INDEX, indexId, record);
                return true;
            }

            // refCount==1：这是最后一个引用，需要把“内容寻址映射 + 数据记录”一起回收
            byte[] valueBytes = bucketStore.get(space, TYPE_VALUE, r.valueId, r.valueLen);
            masterIndex.remove(valueBytes);
            
            // 先把 refCount 写成 0 再 remove 的理由：
            // - getRefCountByIndexId 对异常会返回 0，但“记录仍存在但 refCount=0”更容易被诊断/调试；
            // - 即使随后 remove 失败，上层至少不会再把它当作有效引用。
            byte[] record = buildIndexRecord(r.valueLen, r.valueHash16, (short) 0, r.valueId);
            bucketStore.overwrite(space, TYPE_INDEX, indexId, record);
            
            bucketStore.remove(space, TYPE_INDEX, indexId);
            bucketStore.remove(space, TYPE_VALUE, r.valueId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 关闭底层 masterIndex 与 bucketStore。
     */
    public void close() {
        masterIndex.close();
        try {
            bucketStore.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private IndexRecord readIndexRecord(long indexId) throws IOException {
        byte[] b = bucketStore.get(space, TYPE_INDEX, indexId, INDEX_RECORD_SIZE);
        IndexRecord r = new IndexRecord();
        // index record 使用固定布局的原因：读取时无需反序列化对象结构，且便于随机访问与 mmap/RAF 实现复用
        r.valueLen = DsDataUtil.loadInt(b, 0);
        r.valueHash16 = loadShort(b, 4);
        r.refCount = loadShort(b, 6);
        r.valueId = DsDataUtil.loadLong(b, 8);
        return r;
    }

    private static byte[] buildIndexRecord(int valueLen, short valueHash16, short refCount, long valueId) {
        byte[] b = new byte[INDEX_RECORD_SIZE];
        // 这里是小端编码（loadShort/storeShort 与 DsDataUtil.loadInt/storeInt 配套）
        // 选择小端仅为与本项目内部工具保持一致；不对外暴露二进制格式时，可优先考虑实现一致性
        DsDataUtil.storeInt(b, 0, valueLen);
        storeShort(b, 4, valueHash16);
        storeShort(b, 6, refCount);
        DsDataUtil.storeLong(b, 8, valueId);
        return b;
    }

    private static short loadShort(byte[] b, int off) {
        int v = (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
        return (short) v;
    }

    private static void storeShort(byte[] b, int off, short v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
    }

    private static final class IndexRecord {
        int valueLen;
        short valueHash16;
        short refCount;
        long valueId;
    }
}
