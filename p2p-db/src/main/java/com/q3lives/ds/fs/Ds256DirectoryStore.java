package com.q3lives.ds.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.util.DsDataUtil;

/**
 * 目录成员表存储（dirId -> entryId 列表），基于分级固定块：4K inode / 64K indirect / 64M indirect。
 *
 * <p>用途：</p>
 * <ul>
 *   <li>为 {@link DsFile} 提供“目录下包含哪些文件/子目录”的成员集合。</li>
 *   <li>目录项只存 long entryId，不存名字；名字/路径由上层 path->id 映射维护。</li>
 * </ul>
 *
 * <p>entryId 编码约定（由上层定义，本类不强制）：</p>
 * <ul>
 *   <li>正数：fileId</li>
 *   <li>负数：-dirId（子目录）</li>
 * </ul>
 *
 * <p>存储布局（4K inode）：</p>
 * <ul>
 *   <li>前半（2KB）为 direct 区：存 entryId（long）并允许空洞（0 表示空）。</li>
 *   <li>后半拆成两段指针区：指向 64K / 64M 的间接块（blockId）。</li>
 *   <li>inode 头部保存 direct 的 head/tail/used/freed，以及两级间接区总数（count64k/count64m）。</li>
 * </ul>
 *
 * <p>空洞复用（free-list 版本）：</p>
 * <ul>
 *   <li>删除：槽位置 0，used--，freed++，并更新 head（指向最靠前的空洞）。</li>
 *   <li>插入：若 freed!=0，优先复用 head 指向的空洞；head 越界或槽位非 0 时会重新扫描修正。</li>
 *   <li>乱序允许：成员表保证集合语义，不保证顺序稳定；分页 listEntries 会跳过 0。</li>
 * </ul>
 */
public class Ds256DirectoryStore {

    public static final int POWER_4K = 12;
    public static final int POWER_64K = 16;
    public static final int POWER_64M = 26;

    private static final int ENTRY_BYTES = 8;
    private static final int SIZE_4K = 1 << POWER_4K;
    private static final int SIZE_64K = 1 << POWER_64K;
    private static final int SIZE_64M = 1 << POWER_64M;

    private static final int DIRECT_BYTES = SIZE_4K / 2;
    private static final int PTR_BYTES_EACH = SIZE_4K / 4;

    private static final int INODE_HEADER_SIZE = 16;
    private static final int INODE_OFF_HEAD = 0;
    private static final int INODE_OFF_TAIL = 2;
    private static final int INODE_OFF_USED = 4;
    private static final int INODE_OFF_FREED = 6;
    private static final int INODE_OFF_64K_COUNT = 8;
    private static final int INODE_OFF_64M_COUNT = 12;
    private static final int INODE_DIRECT_OFFSET = INODE_HEADER_SIZE;

    private static final int DIRECT_CAP = (DIRECT_BYTES - INODE_DIRECT_OFFSET) / ENTRY_BYTES;
    private static final int PTR64K_CAP = PTR_BYTES_EACH / ENTRY_BYTES;
    private static final int PTR64M_CAP = PTR_BYTES_EACH / ENTRY_BYTES;

    private static final int BLK64K_HEADER_SIZE = 16;
    private static final int BLK64K_OFF_HEAD = 0;
    private static final int BLK64K_OFF_TAIL = 2;
    private static final int BLK64K_OFF_USED = 4;
    private static final int BLK64K_OFF_FREED = 6;
    private static final int BLK64K_DATA_OFFSET = BLK64K_HEADER_SIZE;
    private static final int BLK64K_CAP = (SIZE_64K - BLK64K_DATA_OFFSET) / ENTRY_BYTES;

    private static final int BLK64M_HEADER_SIZE = 24;
    private static final int BLK64M_OFF_HEAD = 0;
    private static final int BLK64M_OFF_TAIL = 4;
    private static final int BLK64M_OFF_USED = 8;
    private static final int BLK64M_OFF_FREED = 12;
    private static final int BLK64M_DATA_OFFSET = BLK64M_HEADER_SIZE;
    private static final int BLK64M_CAP = (SIZE_64M - BLK64M_DATA_OFFSET) / ENTRY_BYTES;

    private final DsFixedBucketStore store;

    /**
     * 创建目录成员表存储。
     *
     * @param rootDir bucket 根目录
     */
    public Ds256DirectoryStore(String rootDir) {
        this.store = new DsFixedBucketStore(rootDir);
    }

    /**
     * 创建一个新目录（分配一个 4K inode 块），并初始化 inode 头部字段。
     *
     * @return dirId（4K inode 的 bucket id）
     */
    public long createDir() throws IOException {
        byte[] zeros = new byte[SIZE_4K];
        long id = store.put(DsFixedBucketStore.META_SPACE, "dir4k", zeros);
        initInode(id);
        return id;
    }

    /**
     * 分配一个 fileId（当前实现为 8B 记录的 bucket id）。
     *
     * <p>该 fileId 只是一个稳定句柄；其具体含义由上层（如 DsFile）定义。</p>
     */
    public long allocateFileId() throws IOException {
        byte[] zeros = new byte[8];
        return store.put(DsFixedBucketStore.META_SPACE, "fid", zeros);
    }

    /**
     * 分配一个 dirId（等价于 {@link #createDir()}）。
     */
    public long allocateDirId() throws IOException {
        return createDir();
    }

    /**
     * 向目录追加一个成员 entryId。
     *
     * <p>优先插入 inode direct 区的空洞；满后依次使用 64K/64M 间接块。</p>
     *
     * @param dirId 目录 inode id
     * @param entryId 目录项 id（约定：正数=fileId，负数=-dirId，0 保留）
     */
    public void appendEntry(long dirId, long entryId) throws IOException {
        if (entryId == 0) {
            return;
        }
        if (tryInsertInodeDirect(dirId, entryId)) {
            return;
        }
        if (tryInsert64k(dirId, entryId)) {
            incrementInodeCount64k(dirId, 1);
            return;
        }
        if (tryInsert64m(dirId, entryId)) {
            incrementInodeCount64m(dirId, 1);
            return;
        }
        throw new IllegalStateException("directory is full");
    }

    /**
     * 分页列出目录成员（按“有效条目 rank”分页，跳过空洞 0）。
     *
     * <p>由于支持删除置 0 与空洞复用，条目顺序不保证稳定；该接口保证集合语义。</p>
     *
     * @param offset 需要跳过的“有效条目数”
     * @param limit 返回的最大条目数
     * @return entryId 数组
     */
    public long[] listEntries(long dirId, long offset, int limit) throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (limit <= 0) {
            return new long[0];
        }
        long[] out = new long[limit];
        int filled = 0;
        long needSkip = offset;

        byte[] inode = readBlock("dir4k", dirId, SIZE_4K);
        for (int off = INODE_DIRECT_OFFSET; off < DIRECT_BYTES && filled < limit; off += ENTRY_BYTES) {
            long v = DsDataUtil.loadLong(inode, off);
            if (v == 0) {
                continue;
            }
            if (needSkip > 0) {
                needSkip--;
                continue;
            }
            out[filled++] = v;
        }

        for (int p = 0; p < PTR64K_CAP && filled < limit; p++) {
            long blockId = DsDataUtil.loadLong(inode, DIRECT_BYTES + (p * ENTRY_BYTES));
            if (blockId == 0) {
                continue;
            }
            byte[] blk = readBlock("dir64k", blockId, SIZE_64K);
            for (int off = BLK64K_DATA_OFFSET; off + ENTRY_BYTES <= SIZE_64K && filled < limit; off += ENTRY_BYTES) {
                long v = DsDataUtil.loadLong(blk, off);
                if (v == 0) {
                    continue;
                }
                if (needSkip > 0) {
                    needSkip--;
                    continue;
                }
                out[filled++] = v;
            }
        }

        for (int p = 0; p < PTR64M_CAP && filled < limit; p++) {
            long blockId = DsDataUtil.loadLong(inode, DIRECT_BYTES + PTR_BYTES_EACH + (p * ENTRY_BYTES));
            if (blockId == 0) {
                continue;
            }
            byte[] blk = readBlock("dir64m", blockId, SIZE_64M);
            for (int off = BLK64M_DATA_OFFSET; off + ENTRY_BYTES <= SIZE_64M && filled < limit; off += ENTRY_BYTES) {
                long v = DsDataUtil.loadLong(blk, off);
                if (v == 0) {
                    continue;
                }
                if (needSkip > 0) {
                    needSkip--;
                    continue;
                }
                out[filled++] = v;
            }
        }

        if (filled == out.length) {
            return out;
        }
        long[] trimmed = new long[filled];
        System.arraycopy(out, 0, trimmed, 0, filled);
        return trimmed;
    }

    /**
     * 读取第 index 个槽位（按固定下标，不跳过空洞）。
     *
     * <p>与 {@link #listEntries(long, long, int)} 不同，该接口按“位置”读取；如果该槽位是空洞则返回 0。</p>
     */
    public long getEntry(long dirId, long index) throws IOException {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
        if (index < DIRECT_CAP) {
            return readLong("dir4k", dirId, INODE_DIRECT_OFFSET + (int) (index * ENTRY_BYTES));
        }

        long remain = index - DIRECT_CAP;
        long max64k = (long) PTR64K_CAP * (long) BLK64K_CAP;
        if (remain < max64k) {
            int ptrIndex = (int) (remain / BLK64K_CAP);
            int inner = (int) (remain % BLK64K_CAP);
            long blockId = readLong("dir4k", dirId, DIRECT_BYTES + (ptrIndex * ENTRY_BYTES));
            if (blockId == 0) {
                return 0;
            }
            return readLong("dir64k", blockId, BLK64K_DATA_OFFSET + inner * ENTRY_BYTES);
        }

        remain -= max64k;
        long max64m = (long) PTR64M_CAP * (long) BLK64M_CAP;
        if (remain >= max64m) {
            throw new IllegalArgumentException("directory index out of range");
        }
        int ptrIndex = (int) (remain / BLK64M_CAP);
        int inner = (int) (remain % BLK64M_CAP);
        long blockId = readLong("dir4k", dirId, DIRECT_BYTES + PTR_BYTES_EACH + (ptrIndex * ENTRY_BYTES));
        if (blockId == 0) {
            return 0;
        }
        return readLong("dir64m", blockId, BLK64M_DATA_OFFSET + inner * ENTRY_BYTES);
    }

    /**
     * 设置目录的第 index 个槽位（按固定下标，不跳过空洞）。
     *
     * <p>该接口可能创建间接块（64K/64M）以满足写入位置。</p>
     * <p>当 entryId 从 0->非0 或 非0->0 时，会同步维护 used/freed/head 等元数据。</p>
     */
    public void setEntry(long dirId, long index, long entryId) throws IOException {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
        if (index < DIRECT_CAP) {
            int off = INODE_DIRECT_OFFSET + (int) (index * ENTRY_BYTES);
            long prev = readLong("dir4k", dirId, off);
            writeLong("dir4k", dirId, off, entryId);
            if (prev == 0 && entryId != 0) {
                incrementInodeUsed(dirId, 1);
            } else if (prev != 0 && entryId == 0) {
                incrementInodeUsed(dirId, -1);
                incrementInodeFreed(dirId, 1);
                updateInodeHeadOnFree(dirId, off);
            }
            return;
        }

        long remain = index - DIRECT_CAP;
        long max64k = (long) PTR64K_CAP * (long) BLK64K_CAP;
        if (remain < max64k) {
            int ptrIndex = (int) (remain / BLK64K_CAP);
            int inner = (int) (remain % BLK64K_CAP);
            int ptrOffset = DIRECT_BYTES + (ptrIndex * ENTRY_BYTES);
            long blockId = readLong("dir4k", dirId, ptrOffset);
            if (blockId == 0) {
                blockId = allocate64kBlock();
                writeLong("dir4k", dirId, ptrOffset, blockId);
            }
            int dataOff = BLK64K_DATA_OFFSET + inner * ENTRY_BYTES;
            long prev = readLong("dir64k", blockId, dataOff);
            writeLong("dir64k", blockId, dataOff, entryId);
            if (prev == 0 && entryId != 0) {
                incrementBlock64kUsed(blockId, 1);
                incrementInodeCount64k(dirId, 1);
            } else if (prev != 0 && entryId == 0) {
                incrementBlock64kUsed(blockId, -1);
                incrementInodeCount64k(dirId, -1);
                incrementBlock64kFreed(blockId, 1);
                updateBlock64kHeadOnFree(blockId, dataOff);
            }
            return;
        }

        remain -= max64k;
        long max64m = (long) PTR64M_CAP * (long) BLK64M_CAP;
        if (remain >= max64m) {
            throw new IllegalArgumentException("directory index out of range");
        }
        int ptrIndex = (int) (remain / BLK64M_CAP);
        int inner = (int) (remain % BLK64M_CAP);
        int ptrOffset = DIRECT_BYTES + PTR_BYTES_EACH + (ptrIndex * ENTRY_BYTES);
        long blockId = readLong("dir4k", dirId, ptrOffset);
        if (blockId == 0) {
            blockId = allocate64mBlock();
            writeLong("dir4k", dirId, ptrOffset, blockId);
        }
        int dataOff = BLK64M_DATA_OFFSET + inner * ENTRY_BYTES;
        long prev = readLong("dir64m", blockId, dataOff);
        writeLong("dir64m", blockId, dataOff, entryId);
        if (prev == 0 && entryId != 0) {
            incrementBlock64mUsed(blockId, 1);
            incrementInodeCount64m(dirId, 1);
        } else if (prev != 0 && entryId == 0) {
            incrementBlock64mUsed(blockId, -1);
            incrementInodeCount64m(dirId, -1);
            incrementBlock64mFreed(blockId, 1);
            updateBlock64mHeadOnFree(blockId, dataOff);
        }
    }

    /**
     * 返回目录成员的有效条目数（direct.used + count64k + count64m）。
     *
     * <p>注意：这是“有效条目数”，不等同于最大槽位数。</p>
     */
    public long size(long dirId) throws IOException {
        int usedDirect = readU16("dir4k", dirId, INODE_OFF_USED);
        long c64k = readInt("dir4k", dirId, INODE_OFF_64K_COUNT) & 0xFFFFFFFFL;
        long c64m = readInt("dir4k", dirId, INODE_OFF_64M_COUNT) & 0xFFFFFFFFL;
        return (long) usedDirect + c64k + c64m;
    }

    /**
     * 关闭底层 bucket 资源。
     */
    public void close() throws IOException {
        store.close();
    }

    private long allocate64kBlock() throws IOException {
        byte[] zeros = new byte[SIZE_64K];
        long id = store.put(DsFixedBucketStore.META_SPACE, "dir64k", zeros);
        init64kBlock(id);
        return id;
    }

    private long allocate64mBlock() throws IOException {
        byte[] zeros = new byte[SIZE_64M];
        long id = store.put(DsFixedBucketStore.META_SPACE, "dir64m", zeros);
        init64mBlock(id);
        return id;
    }

    private long readLong(String type, long id, int offset) throws IOException {
        byte[] b = store.get(DsFixedBucketStore.META_SPACE, type, id, offset, 8);
        return DsDataUtil.loadLong(b, 0);
    }

    private void writeLong(String type, long id, int offset, long value) throws IOException {
        byte[] b = new byte[8];
        DsDataUtil.storeLong(b, 0, value);
        store.update(DsFixedBucketStore.META_SPACE, type, id, offset, b);
    }

    private byte[] readBlock(String type, long id, int size) throws IOException {
        return store.get(DsFixedBucketStore.META_SPACE, type, id, 0, size);
    }

    /**
     * 从目录成员表中移除一个 entryId（按值删除，删除置 0，优先删 direct）。
     *
     * @return 找到并删除返回 true，否则返回 false
     */
    public boolean removeEntry(long dirId, long entryId) throws IOException {
        if (entryId == 0) {
            return false;
        }
        if (removeFromInodeDirect(dirId, entryId)) {
            return true;
        }
        if (removeFrom64k(dirId, entryId)) {
            incrementInodeCount64k(dirId, -1);
            return true;
        }
        if (removeFrom64m(dirId, entryId)) {
            incrementInodeCount64m(dirId, -1);
            return true;
        }
        return false;
    }

    private void initInode(long dirId) throws IOException {
        writeU16("dir4k", dirId, INODE_OFF_HEAD, 0);
        writeU16("dir4k", dirId, INODE_OFF_TAIL, INODE_DIRECT_OFFSET);
        writeU16("dir4k", dirId, INODE_OFF_USED, 0);
        writeU16("dir4k", dirId, INODE_OFF_FREED, 0);
        writeInt("dir4k", dirId, INODE_OFF_64K_COUNT, 0);
        writeInt("dir4k", dirId, INODE_OFF_64M_COUNT, 0);
    }

    private void init64kBlock(long id) throws IOException {
        writeU16("dir64k", id, BLK64K_OFF_HEAD, 0);
        writeU16("dir64k", id, BLK64K_OFF_TAIL, BLK64K_DATA_OFFSET);
        writeU16("dir64k", id, BLK64K_OFF_USED, 0);
        writeU16("dir64k", id, BLK64K_OFF_FREED, 0);
    }

    private void init64mBlock(long id) throws IOException {
        writeInt("dir64m", id, BLK64M_OFF_HEAD, 0);
        writeInt("dir64m", id, BLK64M_OFF_TAIL, BLK64M_DATA_OFFSET);
        writeInt("dir64m", id, BLK64M_OFF_USED, 0);
        writeInt("dir64m", id, BLK64M_OFF_FREED, 0);
    }

    private boolean tryInsertInodeDirect(long dirId, long entryId) throws IOException {
        int freed = readU16("dir4k", dirId, INODE_OFF_FREED);
        if (freed != 0) {
            int head = readU16("dir4k", dirId, INODE_OFF_HEAD);
            if (head != 0) {
                if (head < INODE_DIRECT_OFFSET || head + ENTRY_BYTES > DIRECT_BYTES) {
                    head = 0;
                } else {
                    long cur = readLong("dir4k", dirId, head);
                    if (cur != 0) {
                        head = 0;
                    }
                }
            }
            if (head == 0) {
                head = findZeroInRange("dir4k", dirId, INODE_DIRECT_OFFSET, DIRECT_BYTES);
                writeU16("dir4k", dirId, INODE_OFF_HEAD, head);
            }
            if (head != 0) {
                writeLong("dir4k", dirId, head, entryId);
                incrementInodeUsed(dirId, 1);
                int newFreed = freed - 1;
                writeU16("dir4k", dirId, INODE_OFF_FREED, newFreed);
                if (newFreed == 0) {
                    writeU16("dir4k", dirId, INODE_OFF_HEAD, 0);
                } else {
                    int next = findZeroInRange("dir4k", dirId, head + ENTRY_BYTES, DIRECT_BYTES);
                    if (next == 0) {
                        next = findZeroInRange("dir4k", dirId, INODE_DIRECT_OFFSET, DIRECT_BYTES);
                    }
                    writeU16("dir4k", dirId, INODE_OFF_HEAD, next);
                }
                return true;
            }
            writeU16("dir4k", dirId, INODE_OFF_FREED, 0);
            writeU16("dir4k", dirId, INODE_OFF_HEAD, 0);
        }

        int tail = readU16("dir4k", dirId, INODE_OFF_TAIL);
        if (tail != 0 && tail + ENTRY_BYTES <= DIRECT_BYTES) {
            writeLong("dir4k", dirId, tail, entryId);
            incrementInodeUsed(dirId, 1);
            int nextTail = tail + ENTRY_BYTES;
            writeU16("dir4k", dirId, INODE_OFF_TAIL, nextTail + ENTRY_BYTES <= DIRECT_BYTES ? nextTail : 0);
            return true;
        }
        return false;
    }

    private boolean tryInsert64k(long dirId, long entryId) throws IOException {
        for (int p = 0; p < PTR64K_CAP; p++) {
            int ptrOff = DIRECT_BYTES + (p * ENTRY_BYTES);
            long blockId = readLong("dir4k", dirId, ptrOff);
            if (blockId == 0) {
                blockId = allocate64kBlock();
                writeLong("dir4k", dirId, ptrOff, blockId);
            }
            if (tryInsert64kBlock(blockId, entryId)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryInsert64m(long dirId, long entryId) throws IOException {
        for (int p = 0; p < PTR64M_CAP; p++) {
            int ptrOff = DIRECT_BYTES + PTR_BYTES_EACH + (p * ENTRY_BYTES);
            long blockId = readLong("dir4k", dirId, ptrOff);
            if (blockId == 0) {
                blockId = allocate64mBlock();
                writeLong("dir4k", dirId, ptrOff, blockId);
            }
            if (tryInsert64mBlock(blockId, entryId)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryInsert64kBlock(long blockId, long entryId) throws IOException {
        int freed = readU16("dir64k", blockId, BLK64K_OFF_FREED);
        if (freed != 0) {
            int head = readU16("dir64k", blockId, BLK64K_OFF_HEAD);
            if (head != 0) {
                if (head < BLK64K_DATA_OFFSET || head + ENTRY_BYTES > SIZE_64K) {
                    head = 0;
                } else {
                    long cur = readLong("dir64k", blockId, head);
                    if (cur != 0) {
                        head = 0;
                    }
                }
            }
            if (head == 0) {
                head = findZeroInRange("dir64k", blockId, BLK64K_DATA_OFFSET, SIZE_64K);
                writeU16("dir64k", blockId, BLK64K_OFF_HEAD, head);
            }
            if (head != 0) {
                writeLong("dir64k", blockId, head, entryId);
                incrementBlock64kUsed(blockId, 1);
                int newFreed = freed - 1;
                writeU16("dir64k", blockId, BLK64K_OFF_FREED, newFreed);
                if (newFreed == 0) {
                    writeU16("dir64k", blockId, BLK64K_OFF_HEAD, 0);
                } else {
                    int next = findZeroInRange("dir64k", blockId, head + ENTRY_BYTES, SIZE_64K);
                    if (next == 0) {
                        next = findZeroInRange("dir64k", blockId, BLK64K_DATA_OFFSET, SIZE_64K);
                    }
                    writeU16("dir64k", blockId, BLK64K_OFF_HEAD, next);
                }
                return true;
            }
            writeU16("dir64k", blockId, BLK64K_OFF_FREED, 0);
            writeU16("dir64k", blockId, BLK64K_OFF_HEAD, 0);
        }

        int tail = readU16("dir64k", blockId, BLK64K_OFF_TAIL);
        if (tail != 0 && tail + ENTRY_BYTES <= SIZE_64K) {
            writeLong("dir64k", blockId, tail, entryId);
            incrementBlock64kUsed(blockId, 1);
            int nextTail = tail + ENTRY_BYTES;
            writeU16("dir64k", blockId, BLK64K_OFF_TAIL, nextTail + ENTRY_BYTES <= SIZE_64K ? nextTail : 0);
            return true;
        }
        return false;
    }

    private boolean tryInsert64mBlock(long blockId, long entryId) throws IOException {
        int freed = readInt("dir64m", blockId, BLK64M_OFF_FREED);
        if (freed != 0) {
            int head = readInt("dir64m", blockId, BLK64M_OFF_HEAD);
            if (head != 0) {
                if (head < BLK64M_DATA_OFFSET || (long) head + ENTRY_BYTES > (long) SIZE_64M) {
                    head = 0;
                } else {
                    long cur = readLong("dir64m", blockId, head);
                    if (cur != 0) {
                        head = 0;
                    }
                }
            }
            if (head == 0) {
                head = findZeroInRange("dir64m", blockId, BLK64M_DATA_OFFSET, SIZE_64M);
                writeInt("dir64m", blockId, BLK64M_OFF_HEAD, head);
            }
            if (head != 0) {
                writeLong("dir64m", blockId, head, entryId);
                incrementBlock64mUsed(blockId, 1);
                int newFreed = freed - 1;
                writeInt("dir64m", blockId, BLK64M_OFF_FREED, newFreed);
                if (newFreed == 0) {
                    writeInt("dir64m", blockId, BLK64M_OFF_HEAD, 0);
                } else {
                    int next = findZeroInRange("dir64m", blockId, head + ENTRY_BYTES, SIZE_64M);
                    if (next == 0) {
                        next = findZeroInRange("dir64m", blockId, BLK64M_DATA_OFFSET, SIZE_64M);
                    }
                    writeInt("dir64m", blockId, BLK64M_OFF_HEAD, next);
                }
                return true;
            }
            writeInt("dir64m", blockId, BLK64M_OFF_FREED, 0);
            writeInt("dir64m", blockId, BLK64M_OFF_HEAD, 0);
        }

        int tail = readInt("dir64m", blockId, BLK64M_OFF_TAIL);
        if (tail != 0 && (long) tail + ENTRY_BYTES <= (long) SIZE_64M) {
            writeLong("dir64m", blockId, tail, entryId);
            incrementBlock64mUsed(blockId, 1);
            int nextTail = tail + ENTRY_BYTES;
            writeInt("dir64m", blockId, BLK64M_OFF_TAIL, (long) nextTail + ENTRY_BYTES <= (long) SIZE_64M ? nextTail : 0);
            return true;
        }
        return false;
    }

    private boolean removeFromInodeDirect(long dirId, long entryId) throws IOException {
        for (int off = INODE_DIRECT_OFFSET; off < DIRECT_BYTES; off += ENTRY_BYTES) {
            long v = readLong("dir4k", dirId, off);
            if (v == entryId) {
                writeLong("dir4k", dirId, off, 0L);
                incrementInodeUsed(dirId, -1);
                incrementInodeFreed(dirId, 1);
                updateInodeHeadOnFree(dirId, off);
                return true;
            }
        }
        return false;
    }

    private boolean removeFrom64k(long dirId, long entryId) throws IOException {
        for (int p = 0; p < PTR64K_CAP; p++) {
            long blockId = readLong("dir4k", dirId, DIRECT_BYTES + (p * ENTRY_BYTES));
            if (blockId == 0) {
                continue;
            }
            for (int off = BLK64K_DATA_OFFSET; off + ENTRY_BYTES <= SIZE_64K; off += ENTRY_BYTES) {
                long v = readLong("dir64k", blockId, off);
                if (v == entryId) {
                    writeLong("dir64k", blockId, off, 0L);
                    incrementBlock64kUsed(blockId, -1);
                    incrementBlock64kFreed(blockId, 1);
                    updateBlock64kHeadOnFree(blockId, off);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean removeFrom64m(long dirId, long entryId) throws IOException {
        for (int p = 0; p < PTR64M_CAP; p++) {
            long blockId = readLong("dir4k", dirId, DIRECT_BYTES + PTR_BYTES_EACH + (p * ENTRY_BYTES));
            if (blockId == 0) {
                continue;
            }
            for (int off = BLK64M_DATA_OFFSET; off + ENTRY_BYTES <= SIZE_64M; off += ENTRY_BYTES) {
                long v = readLong("dir64m", blockId, off);
                if (v == entryId) {
                    writeLong("dir64m", blockId, off, 0L);
                    incrementBlock64mUsed(blockId, -1);
                    incrementBlock64mFreed(blockId, 1);
                    updateBlock64mHeadOnFree(blockId, off);
                    return true;
                }
            }
        }
        return false;
    }

    private void updateInodeHeadOnFree(long dirId, int freedOffset) throws IOException {
        int head = readU16("dir4k", dirId, INODE_OFF_HEAD);
        if (head == 0 || freedOffset < head) {
            writeU16("dir4k", dirId, INODE_OFF_HEAD, freedOffset);
        }
    }

    private void updateBlock64kHeadOnFree(long blockId, int freedOffset) throws IOException {
        int head = readU16("dir64k", blockId, BLK64K_OFF_HEAD);
        if (head == 0 || freedOffset < head) {
            writeU16("dir64k", blockId, BLK64K_OFF_HEAD, freedOffset);
        }
    }

    private void updateBlock64mHeadOnFree(long blockId, int freedOffset) throws IOException {
        int head = readInt("dir64m", blockId, BLK64M_OFF_HEAD);
        if (head == 0 || freedOffset < head) {
            writeInt("dir64m", blockId, BLK64M_OFF_HEAD, freedOffset);
        }
    }

    private int findZeroInRange(String type, long id, int startOffset, int endExclusive) throws IOException {
        int start = startOffset;
        if (start < 0) {
            start = 0;
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

    private void incrementInodeUsed(long dirId, int delta) throws IOException {
        int v = readU16("dir4k", dirId, INODE_OFF_USED);
        int n = v + delta;
        if (n < 0) n = 0;
        if (n > 0xFFFF) n = 0xFFFF;
        writeU16("dir4k", dirId, INODE_OFF_USED, n);
    }

    private void incrementInodeFreed(long dirId, int delta) throws IOException {
        int v = readU16("dir4k", dirId, INODE_OFF_FREED);
        int n = v + delta;
        if (n < 0) n = 0;
        if (n > 0xFFFF) n = 0xFFFF;
        writeU16("dir4k", dirId, INODE_OFF_FREED, n);
    }

    private void incrementInodeCount64k(long dirId, int delta) throws IOException {
        int v = readInt("dir4k", dirId, INODE_OFF_64K_COUNT);
        int n = v + delta;
        if (n < 0) n = 0;
        writeInt("dir4k", dirId, INODE_OFF_64K_COUNT, n);
    }

    private void incrementInodeCount64m(long dirId, int delta) throws IOException {
        int v = readInt("dir4k", dirId, INODE_OFF_64M_COUNT);
        int n = v + delta;
        if (n < 0) n = 0;
        writeInt("dir4k", dirId, INODE_OFF_64M_COUNT, n);
    }

    private void incrementBlock64kUsed(long blockId, int delta) throws IOException {
        int v = readU16("dir64k", blockId, BLK64K_OFF_USED);
        int n = v + delta;
        if (n < 0) n = 0;
        if (n > 0xFFFF) n = 0xFFFF;
        writeU16("dir64k", blockId, BLK64K_OFF_USED, n);
    }

    private void incrementBlock64kFreed(long blockId, int delta) throws IOException {
        int v = readU16("dir64k", blockId, BLK64K_OFF_FREED);
        int n = v + delta;
        if (n < 0) n = 0;
        if (n > 0xFFFF) n = 0xFFFF;
        writeU16("dir64k", blockId, BLK64K_OFF_FREED, n);
    }

    private void incrementBlock64mUsed(long blockId, int delta) throws IOException {
        int v = readInt("dir64m", blockId, BLK64M_OFF_USED);
        int n = v + delta;
        if (n < 0) n = 0;
        writeInt("dir64m", blockId, BLK64M_OFF_USED, n);
    }

    private void incrementBlock64mFreed(long blockId, int delta) throws IOException {
        int v = readInt("dir64m", blockId, BLK64M_OFF_FREED);
        int n = v + delta;
        if (n < 0) n = 0;
        writeInt("dir64m", blockId, BLK64M_OFF_FREED, n);
    }

    private int readU16(String type, long id, int offset) throws IOException {
        byte[] b = store.get(DsFixedBucketStore.META_SPACE, type, id, offset, 2);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
    }

    private void writeU16(String type, long id, int offset, int value) throws IOException {
        byte[] b = new byte[2];
        b[0] = (byte) (value & 0xFF);
        b[1] = (byte) ((value >>> 8) & 0xFF);
        store.update(DsFixedBucketStore.META_SPACE, type, id, offset, b);
    }

    private int readInt(String type, long id, int offset) throws IOException {
        byte[] b = store.get(DsFixedBucketStore.META_SPACE, type, id, offset, 4);
        return DsDataUtil.loadInt(b, 0);
    }

    private void writeInt(String type, long id, int offset, int value) throws IOException {
        byte[] b = new byte[4];
        DsDataUtil.storeInt(b, 0, value);
        store.update(DsFixedBucketStore.META_SPACE, type, id, offset, b);
    }

    /**
     * long -> 8 字节 little-endian 编码（工具方法）。
     */
    public static byte[] longToBytes(long v) {
        byte[] b = new byte[8];
        DsDataUtil.storeLong(b, 0, v);
        return b;
    }

    /**
     * 8 字节 little-endian -> long（工具方法）。
     */
    public static long bytesToLong(byte[] b) {
        if (b == null || b.length < 8) {
            return 0;
        }
        return DsDataUtil.loadLong(b, 0);
    }

    /**
     * 把字符串编码为 UTF-8 bytes（用于上层构造 keyBytes）。
     */
    public static byte[] toKeyBytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
