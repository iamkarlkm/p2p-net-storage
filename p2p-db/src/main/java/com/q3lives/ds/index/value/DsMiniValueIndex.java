package com.q3lives.ds.index.value;

import java.io.File;
import java.io.IOException;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.index.master.DsTieredMasterIndex;
import com.q3lives.ds.util.DsDataUtil;

/**
 * valueBytes -> indexId 列表索引（微型化分级块：256B / 4KB / 4MB）。
 *
 * <p>用途：</p>
 * <ul>
 *   <li>为 {@link DsKVStore} 等上层提供“按 value 反查 indexId”的一对多索引。</li>
 *   <li>支持分页读取：{@link #getPage(byte[])} 返回 {@link Page}（index/size/total/ids），调用方可持久化 Page 继续翻页。</li>
 * </ul>
 *
 * <p>总体结构：</p>
 * <ul>
 *   <li>valueToHead：{@link DsTieredMasterIndex}，valueBytes -> headId（256B 的 block0）。</li>
 *   <li>block0（256B）：头部 8B + direct 区（存 indexId）+ 4KB/4MB 间接块指针区。</li>
 *   <li>block1（4KB）：用于承载更多 indexId（定长 long[] + 头部）。</li>
 *   <li>block2（4MB）：用于承载超大数量 indexId（定长 long[] + 头部）。</li>
 * </ul>
 *
 * <p>block0 头部（8B）布局：</p>
 * <ul>
 *   <li>byte0 head：direct 区第一个空洞的 offset（freed!=0 时必须有效；会做越界/非零校验）。</li>
 *   <li>byte1 tail：direct 区追加写入的 offset（freed==0 时才使用；满则为 0）。</li>
 *   <li>byte2 used：direct 区有效条目数（非 0 的槽位个数）。</li>
 *   <li>byte3 freed：direct 区空洞数（删除置 0 时 +1，复用时 -1）。</li>
 *   <li>int indirectTotal：两级间接区（4KB+4MB）合计有效条目数。</li>
 * </ul>
 *
 * <p>删除与复用策略（direct 区）：</p>
 * <ul>
 *   <li>删除：写 0，used--，freed++，head=min(head, off)。</li>
 *   <li>插入：若 freed!=0，优先使用 head 指向的空洞；若 head 错误（越界或槽位非 0）则重新扫描修正。</li>
 *   <li>乱序允许：索引只保证“集合语义”，不保证顺序稳定；分页按扫描顺序跳过 0。</li>
 * </ul>
 */
public class DsMiniValueIndex {

    public static final int BLOCK0_SIZE = 256;
    public static final int BLOCK1_SIZE = 4096;
    public static final int BLOCK2_SIZE = 4 * 1024 * 1024;

    private static final int ENTRY_BYTES = 8;
    private static final int HEADER0_SIZE = 8;
    private static final int HEADER0_OFF_HEAD = 0;
    private static final int HEADER0_OFF_TAIL = 1;
    private static final int HEADER0_OFF_USED = 2;
    private static final int HEADER0_OFF_FREED = 3;
    private static final int HEADER0_OFF_INDIRECT_TOTAL = 4;

    private static final int PTR1_SLOTS = 4;
    private static final int PTR2_SLOTS = 2;

    private static final int BLOCK1_HEADER_SIZE = 16;
    private static final int BLOCK2_HEADER_SIZE = 24;

    private static final int CAP1_PER_BLOCK = (BLOCK1_SIZE - BLOCK1_HEADER_SIZE) / ENTRY_BYTES;
    private static final int CAP2_PER_BLOCK = (BLOCK2_SIZE - BLOCK2_HEADER_SIZE) / ENTRY_BYTES;

    private static final int PTR1_OFFSET = BLOCK0_SIZE - (PTR1_SLOTS + PTR2_SLOTS) * ENTRY_BYTES;
    private static final int PTR2_OFFSET = PTR1_OFFSET + PTR1_SLOTS * ENTRY_BYTES;
    private static final int DIRECT_OFFSET = HEADER0_SIZE;
    private static final int DIRECT_BYTES = PTR1_OFFSET - DIRECT_OFFSET;

    public static final int DEFAULT_PAGE_SIZE = DIRECT_BYTES / ENTRY_BYTES;

    private static final int DIRECT_CAP = DEFAULT_PAGE_SIZE;
    private static final int CAP1_TOTAL = PTR1_SLOTS * CAP1_PER_BLOCK;
    private static final long CAP2_TOTAL = (long) PTR2_SLOTS * (long) CAP2_PER_BLOCK;

    private final DsFixedBucketStore store;
    private final DsTieredMasterIndex valueToHead;

    /**
     * 创建一个 valueIndex 实例。
     *
     * <p>dir 用于存放 block0/1/2 的 bucket 文件，以及 valueToHead 的 master 索引目录。</p>
     */
    public DsMiniValueIndex(File dir) throws IOException {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.store = new DsFixedBucketStore(dir.getAbsolutePath());
        File masterDir = new File(dir, DsFixedBucketStore.DATA_SPACE + File.separator + "master");
        if (!masterDir.exists()) {
            masterDir.mkdirs();
        }
        this.valueToHead = new DsTieredMasterIndex(masterDir);
    }

    /**
     * 获取 valueBytes 对应的 indexId 列表第一页。
     *
     * <p>默认分页大小为 {@link #DEFAULT_PAGE_SIZE}（block0 direct 区容量）。</p>
     */
    public Page getPage(byte[] valueBytes) throws IOException {
        return getPage(valueBytes, 0L, DEFAULT_PAGE_SIZE);
    }

    /**
     * 基于上一次返回的 Page 继续读取下一页。
     *
     * <p>由于索引允许空洞/乱序，推进步长使用 previous.ids.length（实际返回条目数）而不是 previous.size。</p>
     */
    public Page getPage(byte[] valueBytes, Page previous) throws IOException {
        if (previous == null) {
            return getPage(valueBytes);
        }
        long next = previous.index + (long) previous.ids.length;
        if (next < 0) {
            next = 0;
        }
        return getPage(valueBytes, next, previous.size);
    }

    /**
     * 按“有效条目 rank”分页读取 indexId。
     *
     * @param index 需要跳过的有效条目数（不是槽位下标）
     * @param size 期望返回的条目数（<=0 则使用默认值）
     */
    public Page getPage(byte[] valueBytes, long index, int size) throws IOException {
        if (valueBytes == null) {
            return new Page(index, size > 0 ? size : DEFAULT_PAGE_SIZE, 0L, new long[0]);
        }
        if (index < 0) {
            index = 0;
        }
        if (size <= 0) {
            size = DEFAULT_PAGE_SIZE;
        }
        Long headId = valueToHead.get(valueBytes);
        if (headId == null) {
            return new Page(index, size, 0L, new long[0]);
        }
        return scanPage(headId, index, size);
    }

    /**
     * 把一个 indexId 加入到 valueBytes 对应的集合中。
     *
     * <p>插入顺序：direct（复用空洞优先）-> 4KB 间接 -> 4MB 间接；插入成功后会更新 indirectTotal。</p>
     */
    public void add(byte[] valueBytes, long indexId) throws IOException {
        if (valueBytes == null) {
            throw new IllegalArgumentException("valueBytes cannot be null");
        }
        if (indexId == 0) {
            return;
        }
        Long headId = valueToHead.get(valueBytes);
        if (headId == null) {
            headId = allocBlock0();
            valueToHead.put(valueBytes, headId, false);
        }
        if (tryInsertDirect(headId, indexId)) {
            return;
        }
        if (tryInsertIndirect1(headId, indexId)) {
            incrementIndirectTotal(headId, 1);
            return;
        }
        if (tryInsertIndirect2(headId, indexId)) {
            incrementIndirectTotal(headId, 1);
            return;
        }
        throw new IllegalStateException("value index full");
    }

    /**
     * 从 valueBytes 对应集合中移除一个 indexId（删除置 0）。
     *
     * <p>当集合变空（total==0）时，会删除 valueToHead 映射。</p>
     */
    public void remove(byte[] valueBytes, long indexId) throws IOException {
        if (valueBytes == null || indexId == 0) {
            return;
        }
        Long headId = valueToHead.get(valueBytes);
        if (headId == null) {
            return;
        }
        long total = total(headId);
        if (total <= 0) {
            valueToHead.remove(valueBytes);
            return;
        }
        if (tryRemoveDirect(headId, indexId)) {
            if (total(headId) == 0) {
                valueToHead.remove(valueBytes);
            }
            return;
        }
        if (tryRemoveIndirect1(headId, indexId)) {
            incrementIndirectTotal(headId, -1);
            if (total(headId) == 0) {
                valueToHead.remove(valueBytes);
            }
            return;
        }
        if (tryRemoveIndirect2(headId, indexId)) {
            incrementIndirectTotal(headId, -1);
            if (total(headId) == 0) {
                valueToHead.remove(valueBytes);
            }
            return;
        }
        if (total(headId) == 0) {
            valueToHead.remove(valueBytes);
        }
    }

    /**
     * 关闭底层索引与 bucket 资源。
     */
    public void close() throws IOException {
        valueToHead.close();
        store.close();
    }

    private long total(long headId) throws IOException {
        int used0 = readU8("vix0", headId, HEADER0_OFF_USED);
        long indirect = readInt("vix0", headId, HEADER0_OFF_INDIRECT_TOTAL) & 0xFFFFFFFFL;
        return (long) used0 + indirect;
    }

    private Page scanPage(long headId, long startRank, int size) throws IOException {
        long total = total(headId);
        if (total <= 0 || startRank >= total) {
            return new Page(startRank, size, Math.max(0, total), new long[0]);
        }
        long needSkip = startRank;
        long[] tmp = new long[size];
        int filled = 0;

        for (int off = DIRECT_OFFSET; off < PTR1_OFFSET && filled < size; off += ENTRY_BYTES) {
            long v = readLong("vix0", headId, off);
            if (v == 0) {
                continue;
            }
            if (needSkip > 0) {
                needSkip--;
                continue;
            }
            tmp[filled++] = v;
        }

        for (int p = 0; p < PTR1_SLOTS && filled < size; p++) {
            long blockId = readLong("vix0", headId, PTR1_OFFSET + p * ENTRY_BYTES);
            if (blockId == 0) {
                continue;
            }
            for (int off = BLOCK1_HEADER_SIZE; off + ENTRY_BYTES <= BLOCK1_SIZE && filled < size; off += ENTRY_BYTES) {
                long v = readLong("vix1", blockId, off);
                if (v == 0) {
                    continue;
                }
                if (needSkip > 0) {
                    needSkip--;
                    continue;
                }
                tmp[filled++] = v;
            }
        }

        for (int p = 0; p < PTR2_SLOTS && filled < size; p++) {
            long blockId = readLong("vix0", headId, PTR2_OFFSET + p * ENTRY_BYTES);
            if (blockId == 0) {
                continue;
            }
            for (int off = BLOCK2_HEADER_SIZE; off + ENTRY_BYTES <= BLOCK2_SIZE && filled < size; off += ENTRY_BYTES) {
                long v = readLong("vix2", blockId, off);
                if (v == 0) {
                    continue;
                }
                if (needSkip > 0) {
                    needSkip--;
                    continue;
                }
                tmp[filled++] = v;
            }
        }

        long[] ids = new long[filled];
        System.arraycopy(tmp, 0, ids, 0, filled);
        return new Page(startRank, size, total, ids);
    }

    private void incrementIndirectTotal(long headId, int delta) throws IOException {
        int v = readInt("vix0", headId, HEADER0_OFF_INDIRECT_TOTAL);
        int n = v + delta;
        if (n < 0) {
            n = 0;
        }
        writeInt("vix0", headId, HEADER0_OFF_INDIRECT_TOTAL, n);
    }

    private boolean tryInsertDirect(long headId, long indexId) throws IOException {
        int freed = readU8("vix0", headId, HEADER0_OFF_FREED);
        if (freed != 0) {
            int found = readU8("vix0", headId, HEADER0_OFF_HEAD);
            if (found != 0) {
                if (found < DIRECT_OFFSET || found + ENTRY_BYTES > PTR1_OFFSET) {
                    found = 0;
                } else {
                    long cur = readLong("vix0", headId, found);
                    if (cur != 0L) {
                        found = 0;
                    }
                }
            }
            if (found == 0) {
                found = findZeroInRange("vix0", headId, DIRECT_OFFSET, PTR1_OFFSET);
                writeU8("vix0", headId, HEADER0_OFF_HEAD, found == 0 ? 0 : found);
            }
            if (found != 0) {
                writeLong("vix0", headId, found, indexId);
                int used0 = readU8("vix0", headId, HEADER0_OFF_USED);
                writeU8("vix0", headId, HEADER0_OFF_USED, used0 + 1);
                int newFreed = freed - 1;
                writeU8("vix0", headId, HEADER0_OFF_FREED, newFreed);
                if (newFreed == 0) {
                    writeU8("vix0", headId, HEADER0_OFF_HEAD, 0);
                } else {
                    int next = findZeroInRange("vix0", headId, found + ENTRY_BYTES, PTR1_OFFSET);
                    if (next == 0) {
                        next = findZeroInRange("vix0", headId, DIRECT_OFFSET, PTR1_OFFSET);
                    }
                    writeU8("vix0", headId, HEADER0_OFF_HEAD, next == 0 ? 0 : next);
                }
                return true;
            }
            writeU8("vix0", headId, HEADER0_OFF_FREED, 0);
            writeU8("vix0", headId, HEADER0_OFF_HEAD, 0);
        }

        int tailOff = readU8("vix0", headId, HEADER0_OFF_TAIL);
        if (tailOff != 0 && tailOff + ENTRY_BYTES <= PTR1_OFFSET) {
            writeLong("vix0", headId, tailOff, indexId);
            int used0 = readU8("vix0", headId, HEADER0_OFF_USED);
            writeU8("vix0", headId, HEADER0_OFF_USED, used0 + 1);
            int nextTail = tailOff + ENTRY_BYTES;
            writeU8("vix0", headId, HEADER0_OFF_TAIL, nextTail + ENTRY_BYTES <= PTR1_OFFSET ? nextTail : 0);
            return true;
        }
        return false;
    }

    private boolean tryInsertIndirect1(long headId, long indexId) throws IOException {
        for (int p = 0; p < PTR1_SLOTS; p++) {
            long blockId = readLong("vix0", headId, PTR1_OFFSET + p * ENTRY_BYTES);
            if (blockId == 0) {
                continue;
            }
            int headOff = readU16("vix1", blockId, 0);
            if (headOff != 0) {
                int found = findZeroInRange("vix1", blockId, headOff, BLOCK1_SIZE);
                if (found != 0) {
                    writeLong("vix1", blockId, found, indexId);
                    int used = readU16("vix1", blockId, 4);
                    writeU16("vix1", blockId, 4, used + 1);
                    int next = findZeroInRange("vix1", blockId, found + ENTRY_BYTES, BLOCK1_SIZE);
                    writeU16("vix1", blockId, 0, next);
                    return true;
                }
                writeU16("vix1", blockId, 0, 0);
            }
            int tailOff = readU16("vix1", blockId, 2);
            if (tailOff != 0 && tailOff + ENTRY_BYTES <= BLOCK1_SIZE) {
                writeLong("vix1", blockId, tailOff, indexId);
                int used = readU16("vix1", blockId, 4);
                writeU16("vix1", blockId, 4, used + 1);
                int nextTail = tailOff + ENTRY_BYTES;
                writeU16("vix1", blockId, 2, nextTail + ENTRY_BYTES <= BLOCK1_SIZE ? nextTail : 0);
                return true;
            }
        }

        for (int p = 0; p < PTR1_SLOTS; p++) {
            long blockId = readLong("vix0", headId, PTR1_OFFSET + p * ENTRY_BYTES);
            if (blockId != 0) {
                continue;
            }
            blockId = allocBlock1();
            writeLong("vix0", headId, PTR1_OFFSET + p * ENTRY_BYTES, blockId);
            initBlock1Header(blockId);
            int tailOff = readU16("vix1", blockId, 2);
            writeLong("vix1", blockId, tailOff, indexId);
            writeU16("vix1", blockId, 4, 1);
            int nextTail = tailOff + ENTRY_BYTES;
            writeU16("vix1", blockId, 2, nextTail + ENTRY_BYTES <= BLOCK1_SIZE ? nextTail : 0);
            return true;
        }
        return false;
    }

    private boolean tryInsertIndirect2(long headId, long indexId) throws IOException {
        for (int p = 0; p < PTR2_SLOTS; p++) {
            long blockId = readLong("vix0", headId, PTR2_OFFSET + p * ENTRY_BYTES);
            if (blockId == 0) {
                continue;
            }
            int headOff = readInt("vix2", blockId, 0);
            if (headOff != 0) {
                int found = findZeroInRange("vix2", blockId, headOff, BLOCK2_SIZE);
                if (found != 0) {
                    writeLong("vix2", blockId, found, indexId);
                    int used = readInt("vix2", blockId, 8);
                    writeInt("vix2", blockId, 8, used + 1);
                    int next = findZeroInRange("vix2", blockId, found + ENTRY_BYTES, BLOCK2_SIZE);
                    writeInt("vix2", blockId, 0, next);
                    return true;
                }
                writeInt("vix2", blockId, 0, 0);
            }
            int tailOff = readInt("vix2", blockId, 4);
            if (tailOff != 0 && tailOff + ENTRY_BYTES <= BLOCK2_SIZE) {
                writeLong("vix2", blockId, tailOff, indexId);
                int used = readInt("vix2", blockId, 8);
                writeInt("vix2", blockId, 8, used + 1);
                int nextTail = tailOff + ENTRY_BYTES;
                writeInt("vix2", blockId, 4, nextTail + ENTRY_BYTES <= BLOCK2_SIZE ? nextTail : 0);
                return true;
            }
        }

        for (int p = 0; p < PTR2_SLOTS; p++) {
            long blockId = readLong("vix0", headId, PTR2_OFFSET + p * ENTRY_BYTES);
            if (blockId != 0) {
                continue;
            }
            blockId = allocBlock2();
            writeLong("vix0", headId, PTR2_OFFSET + p * ENTRY_BYTES, blockId);
            initBlock2Header(blockId);
            int tailOff = readInt("vix2", blockId, 4);
            writeLong("vix2", blockId, tailOff, indexId);
            writeInt("vix2", blockId, 8, 1);
            int nextTail = tailOff + ENTRY_BYTES;
            writeInt("vix2", blockId, 4, nextTail + ENTRY_BYTES <= BLOCK2_SIZE ? nextTail : 0);
            return true;
        }
        return false;
    }

    private boolean tryRemoveDirect(long headId, long indexId) throws IOException {
        for (int off = DIRECT_OFFSET; off < PTR1_OFFSET; off += ENTRY_BYTES) {
            long v = readLong("vix0", headId, off);
            if (v == indexId) {
                writeLong("vix0", headId, off, 0L);
                int used0 = readU8("vix0", headId, HEADER0_OFF_USED);
                if (used0 > 0) {
                    writeU8("vix0", headId, HEADER0_OFF_USED, used0 - 1);
                }
                int freed = readU8("vix0", headId, HEADER0_OFF_FREED);
                if (freed < 255) {
                    freed++;
                }
                writeU8("vix0", headId, HEADER0_OFF_FREED, freed);
                int headOff = readU8("vix0", headId, HEADER0_OFF_HEAD);
                if (headOff == 0 || off < headOff) {
                    writeU8("vix0", headId, HEADER0_OFF_HEAD, off);
                }
                return true;
            }
        }
        return false;
    }

    private boolean tryRemoveIndirect1(long headId, long indexId) throws IOException {
        for (int p = 0; p < PTR1_SLOTS; p++) {
            long blockId = readLong("vix0", headId, PTR1_OFFSET + p * ENTRY_BYTES);
            if (blockId == 0) {
                continue;
            }
            for (int off = BLOCK1_HEADER_SIZE; off + ENTRY_BYTES <= BLOCK1_SIZE; off += ENTRY_BYTES) {
                long v = readLong("vix1", blockId, off);
                if (v == indexId) {
                    writeLong("vix1", blockId, off, 0L);
                    int used = readU16("vix1", blockId, 4);
                    if (used > 0) {
                        writeU16("vix1", blockId, 4, used - 1);
                    }
                    int headOff = readU16("vix1", blockId, 0);
                    if (headOff == 0 || off < headOff) {
                        writeU16("vix1", blockId, 0, off);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private boolean tryRemoveIndirect2(long headId, long indexId) throws IOException {
        for (int p = 0; p < PTR2_SLOTS; p++) {
            long blockId = readLong("vix0", headId, PTR2_OFFSET + p * ENTRY_BYTES);
            if (blockId == 0) {
                continue;
            }
            for (int off = BLOCK2_HEADER_SIZE; off + ENTRY_BYTES <= BLOCK2_SIZE; off += ENTRY_BYTES) {
                long v = readLong("vix2", blockId, off);
                if (v == indexId) {
                    writeLong("vix2", blockId, off, 0L);
                    int used = readInt("vix2", blockId, 8);
                    if (used > 0) {
                        writeInt("vix2", blockId, 8, used - 1);
                    }
                    int headOff = readInt("vix2", blockId, 0);
                    if (headOff == 0 || off < headOff) {
                        writeInt("vix2", blockId, 0, off);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private int findZeroInRange(String type, long id, int startOffset, int endExclusive) throws IOException {
        int start = startOffset;
        if (start < 0) {
            start = 0;
        }
        int header = 0;
        if ("vix0".equals(type)) {
            header = DIRECT_OFFSET;
        } else if ("vix1".equals(type)) {
            header = BLOCK1_HEADER_SIZE;
        } else if ("vix2".equals(type)) {
            header = BLOCK2_HEADER_SIZE;
        }
        if (start < header) {
            start = header;
        }
        int end = endExclusive;
        int limit = end - ENTRY_BYTES;
        for (int off = start; off <= limit; off += ENTRY_BYTES) {
            long v = readLong(type, id, off);
            if (v == 0L) {
                return off;
            }
        }
        return 0;
    }

    private long allocBlock0() throws IOException {
        byte[] zeros = new byte[BLOCK0_SIZE];
        long id = store.put(DsFixedBucketStore.DATA_SPACE, "vix0", zeros);
        initBlock0Header(id);
        return id;
    }

    private long allocBlock1() throws IOException {
        byte[] zeros = new byte[BLOCK1_SIZE];
        return store.put(DsFixedBucketStore.DATA_SPACE, "vix1", zeros);
    }

    private long allocBlock2() throws IOException {
        byte[] zeros = new byte[BLOCK2_SIZE];
        return store.put(DsFixedBucketStore.DATA_SPACE, "vix2", zeros);
    }

    private void initBlock0Header(long id) throws IOException {
        writeU8("vix0", id, HEADER0_OFF_HEAD, 0);
        writeU8("vix0", id, HEADER0_OFF_TAIL, DIRECT_OFFSET);
        writeU8("vix0", id, HEADER0_OFF_USED, 0);
        writeU8("vix0", id, HEADER0_OFF_FREED, 0);
        writeInt("vix0", id, HEADER0_OFF_INDIRECT_TOTAL, 0);
    }

    private void initBlock1Header(long id) throws IOException {
        writeU16("vix1", id, 0, 0);
        writeU16("vix1", id, 2, BLOCK1_HEADER_SIZE);
        writeU16("vix1", id, 4, 0);
        writeU16("vix1", id, 6, 0);
        writeInt("vix1", id, 8, 0);
        writeInt("vix1", id, 12, 0);
    }

    private void initBlock2Header(long id) throws IOException {
        writeInt("vix2", id, 0, 0);
        writeInt("vix2", id, 4, BLOCK2_HEADER_SIZE);
        writeInt("vix2", id, 8, 0);
        writeInt("vix2", id, 12, 0);
        writeInt("vix2", id, 16, 0);
        writeInt("vix2", id, 20, 0);
    }

    private long readLong(String type, long id, int offset) throws IOException {
        byte[] b = store.get(DsFixedBucketStore.DATA_SPACE, type, id, offset, 8);
        return DsDataUtil.loadLong(b, 0);
    }

    private void writeLong(String type, long id, int offset, long value) throws IOException {
        byte[] b = new byte[8];
        DsDataUtil.storeLong(b, 0, value);
        store.update(DsFixedBucketStore.DATA_SPACE, type, id, offset, b);
    }

    private int readInt(String type, long id, int offset) throws IOException {
        byte[] b = store.get(DsFixedBucketStore.DATA_SPACE, type, id, offset, 4);
        return DsDataUtil.loadInt(b, 0);
    }

    private void writeInt(String type, long id, int offset, int value) throws IOException {
        byte[] b = new byte[4];
        DsDataUtil.storeInt(b, 0, value);
        store.update(DsFixedBucketStore.DATA_SPACE, type, id, offset, b);
    }

    private int readU8(String type, long id, int offset) throws IOException {
        byte[] b = store.get(DsFixedBucketStore.DATA_SPACE, type, id, offset, 1);
        return b[0] & 0xFF;
    }

    private void writeU8(String type, long id, int offset, int value) throws IOException {
        byte[] b = new byte[] { (byte) (value & 0xFF) };
        store.update(DsFixedBucketStore.DATA_SPACE, type, id, offset, b);
    }

    private void writeU16(String type, long id, int offset, int value) throws IOException {
        byte[] b = new byte[2];
        b[0] = (byte) (value & 0xFF);
        b[1] = (byte) ((value >>> 8) & 0xFF);
        store.update(DsFixedBucketStore.DATA_SPACE, type, id, offset, b);
    }

    private int readU16(String type, long id, int offset) throws IOException {
        byte[] b = store.get(DsFixedBucketStore.DATA_SPACE, type, id, offset, 2);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
    }

    public static final class Page {
        public final long index;
        public final int size;
        public final long total;
        public final long[] ids;

        /**
         * 分页对象。
         *
         * @param index 当前页起始的“有效条目 rank”
         * @param size 期望页大小
         * @param total 当前集合有效条目总数
         * @param ids 当前页返回的 indexId 列表
         */
        public Page(long index, int size, long total, long[] ids) {
            this.index = index;
            this.size = size;
            this.total = total;
            this.ids = ids == null ? new long[0] : ids;
        }

        /**
         * 是否还有下一页。
         */
        public boolean hasMore() {
            return index + (long) ids.length < total;
        }

        /**
         * 构造下一页的游标（ids 为空，仅用于携带 index/size/total）。
         */
        public Page next() {
            return new Page(index + (long) ids.length, size, total, new long[0]);
        }
    }
}
