package com.q3lives.ds.fs.mft;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.util.DsDataUtil;

import java.io.IOException;

/**
 * MFT 目录存储：16 字节条目 + free-ring 管理 + 4K->64K->64M 三级分级块。
 *
 * <p>存储布局：</p>
 * <ul>
 *   <li>4K 块：16B 目录元数据头 + 127 直接条目(16B) + 128 指针→64K(8B) + 128 指针→64M(8B)</li>
 *   <li>64K 块：16B free-ring 头 + 4095 条目(16B)</li>
 *   <li>64M 块：16B free-ring 头 + 4,194,303 条目(16B)</li>
 * </ul>
 *
 * <p>4K 块目录元数据头（16 字节）：</p>
 * <pre>
 *   parentDirId (long,  8B) — 父目录 fileId，用于反向递归恢复目录层级
 *   head        (short, 2B) — 第一个空闲 slot 的 1-based 索引，0 表示无空闲
 *   tail        (short, 2B) — 下一个顺序分配的 0-based 索引，≥127 表示直接区满
 *   entryCount  (int,   4B) — 目录下总条目数（包含直接区 + 64K + 64M）
 * </pre>
 *
 * <p>条目格式（16 字节）：</p>
 * <pre>
 *   fileId   (long, 8B)
 *   nameHash (int,  4B)
 *   i_mode   (short,2B)
 *   reserved (short,2B)
 * </pre>
 *
 * <p>free-ring 行为（仅 4K 块）：</p>
 * <ul>
 *   <li>tail 只增不减，到 DIRECT_CAP(127) 停止，之后走下一级索引页</li>
 *   <li>head 初始 0，remove 时设对应位置为 0 并扫描下一个 0 值 slot 更新 head；无空闲时 head=0</li>
 *   <li>插入优先复用 head 指向的空洞，无空洞时用 tail 顺序分配</li>
 * </ul>
 */
public class DsMftDirStore {

    // ---- 块大小 ----
    private static final int SIZE_4K = 4096;
    private static final int SIZE_64K = 65536;
    private static final int SIZE_64M = 1 << 26; // 67,108,864

    // ---- 条目大小 ----
    private static final int ENTRY_BYTES = 16;

    // ---- 4K 块目录元数据头偏移 ----
    private static final int HDR_SIZE = 16;
    private static final int OFF_PARENT_DIR_ID = 0;   // long, 8B
    private static final int OFF_HEAD = 8;              // short, 2B (1-based, 0=无空洞)
    private static final int OFF_TAIL = 10;             // short, 2B (0-based, ≥127=满)
    private static final int OFF_ENTRY_COUNT = 12;      // int, 4B

    // ---- 4K 块直接区 ----
    private static final int DIRECT_OFFSET = HDR_SIZE;              // 16
    private static final int DIRECT_BYTES = SIZE_4K / 2 - HDR_SIZE; // 2032
    private static final int DIRECT_CAP = DIRECT_BYTES / ENTRY_BYTES; // 127

    // ---- 4K 块间接指针区（后 2K 拆成两段） ----
    private static final int PTR64K_OFFSET = SIZE_4K / 2;           // 2048
    private static final int PTR64K_BYTES = SIZE_4K / 4;            // 1024
    private static final int PTR64K_CAP = PTR64K_BYTES / 8;         // 128

    private static final int PTR64M_OFFSET = SIZE_4K / 2 + PTR64K_BYTES; // 3072
    private static final int PTR64M_BYTES = SIZE_4K / 4;            // 1024
    private static final int PTR64M_CAP = PTR64M_BYTES / 8;         // 128

    // ---- 64K/64M 块 free-ring 头（保持现状） ----
    private static final int BLK_HDR_SIZE = 16;
    private static final int BLK_OFF_FREE_BASE = 0;
    private static final int BLK_OFF_FREE_COUNT = 4;
    private static final int BLK_OFF_FREE_HEAD = 8;
    private static final int BLK_OFF_FREE_TAIL = 12;

    // ---- 64K 块 ----
    private static final int BLK64K_DATA_OFFSET = BLK_HDR_SIZE;     // 16
    private static final int BLK64K_CAP = (SIZE_64K - BLK_HDR_SIZE) / ENTRY_BYTES; // 4095

    // ---- 64M 块 ----
    private static final int BLK64M_DATA_OFFSET = BLK_HDR_SIZE;     // 16
    private static final int BLK64M_CAP = (SIZE_64M - BLK_HDR_SIZE) / ENTRY_BYTES; // 4,194,303

    // ---- 条目内偏移 ----
    private static final int E_OFF_FILE_ID = 0;
    private static final int E_OFF_NAME_HASH = 8;
    private static final int E_OFF_I_MODE = 12;
    private static final int E_OFF_RESERVED = 14;

    private final DsFixedBucketStore store;

    public DsMftDirStore(String rootDir) {
        this.store = new DsFixedBucketStore(rootDir);
    }

    // ================== 目录操作 ==================

    /**
     * 创建新目录（分配 4K 块并初始化头）。
     *
     * @param parentDirId 父目录 fileId（根目录传 0）
     * @return dirId（bucket 编码 id）
     */
    public long createDir(long parentDirId) throws IOException {
        byte[] zeros = new byte[SIZE_4K];
        long id = store.put(DsFixedBucketStore.META_SPACE, "mft_dir4k", zeros);
        initHeader(id, parentDirId);
        return id;
    }

    /**
     * 获取目录的父目录 fileId。
     *
     * @return 父目录 fileId；根目录返回 0
     */
    public long getParentDirId(long dirId) throws IOException {
        return readLong("mft_dir4k", dirId, OFF_PARENT_DIR_ID);
    }

    /**
     * 向目录追加一个条目。
     *
     * @param dirId    目录 id
     * @param fileId   文件/子目录的 fileId
     * @param nameHash 文件名哈希（用于快速比较）
     * @param i_mode   inode 模式（文件类型 + 权限）
     */
    public void appendEntry(long dirId, long fileId, int nameHash, short i_mode) throws IOException {
        if (fileId == 0) {
            return;
        }
        if (tryInsertDirect(dirId, fileId, nameHash, i_mode)) {
            return;
        }
        if (tryInsert64k(dirId, fileId, nameHash, i_mode)) {
            incrementEntryCount(dirId, 1);
            return;
        }
        if (tryInsert64m(dirId, fileId, nameHash, i_mode)) {
            incrementEntryCount(dirId, 1);
            return;
        }
        throw new IllegalStateException("directory is full");
    }

    /**
     * 从目录中移除指定 fileId 的条目（按值删除）。
     *
     * @return 找到并删除返回 true
     */
    public boolean removeEntry(long dirId, long fileId) throws IOException {
        if (fileId == 0) {
            return false;
        }
        if (removeFromDirect(dirId, fileId)) {
            incrementEntryCount(dirId, -1);
            return true;
        }
        if (removeFrom64k(dirId, fileId)) {
            incrementEntryCount(dirId, -1);
            return true;
        }
        if (removeFrom64m(dirId, fileId)) {
            incrementEntryCount(dirId, -1);
            return true;
        }
        return false;
    }

    /**
     * 分页列出目录成员（跳过空洞，只返回有效条目）。
     *
     * @param offset 跳过的有效条目数
     * @param limit  最大返回数
     * @return Entry 数组
     */
    public Entry[] listEntries(long dirId, long offset, int limit) throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (limit <= 0) {
            return new Entry[0];
        }
        Entry[] out = new Entry[limit];
        int filled = 0;
        long needSkip = offset;

        // 直接区
        byte[] inode = readBlock("mft_dir4k", dirId, SIZE_4K);
        for (int off = DIRECT_OFFSET; off < DIRECT_OFFSET + DIRECT_BYTES && filled < limit; off += ENTRY_BYTES) {
            long fid = DsDataUtil.loadLong(inode, off + E_OFF_FILE_ID);
            if (fid == 0) {
                continue;
            }
            if (needSkip > 0) {
                needSkip--;
                continue;
            }
            out[filled++] = decodeEntry(inode, off);
        }

        // 64K 间接块
        for (int p = 0; p < PTR64K_CAP && filled < limit; p++) {
            long blockId = DsDataUtil.loadLong(inode, PTR64K_OFFSET + p * 8);
            if (blockId == 0) {
                continue;
            }
            byte[] blk = readBlock("mft_dir64k", blockId, SIZE_64K);
            for (int off = BLK64K_DATA_OFFSET; off + ENTRY_BYTES <= SIZE_64K && filled < limit; off += ENTRY_BYTES) {
                long fid = DsDataUtil.loadLong(blk, off + E_OFF_FILE_ID);
                if (fid == 0) {
                    continue;
                }
                if (needSkip > 0) {
                    needSkip--;
                    continue;
                }
                out[filled++] = decodeEntry(blk, off);
            }
        }

        // 64M 间接块
        for (int p = 0; p < PTR64M_CAP && filled < limit; p++) {
            long blockId = DsDataUtil.loadLong(inode, PTR64M_OFFSET + p * 8);
            if (blockId == 0) {
                continue;
            }
            byte[] blk = readBlock("mft_dir64m", blockId, SIZE_64M);
            for (int off = BLK64M_DATA_OFFSET; off + ENTRY_BYTES <= SIZE_64M && filled < limit; off += ENTRY_BYTES) {
                long fid = DsDataUtil.loadLong(blk, off + E_OFF_FILE_ID);
                if (fid == 0) {
                    continue;
                }
                if (needSkip > 0) {
                    needSkip--;
                    continue;
                }
                out[filled++] = decodeEntry(blk, off);
            }
        }

        if (filled == out.length) {
            return out;
        }
        Entry[] trimmed = new Entry[filled];
        System.arraycopy(out, 0, trimmed, 0, filled);
        return trimmed;
    }

    /**
     * 返回目录有效条目数（直接读取 entryCount，O(1)）。
     */
    public long size(long dirId) throws IOException {
        return readInt("mft_dir4k", dirId, OFF_ENTRY_COUNT) & 0xFFFFFFFFL;
    }

    /**
     * 查找目录中指定 fileId 的条目。
     *
     * @return 找到返回 Entry，否则 null
     */
    public Entry findEntry(long dirId, long fileId) throws IOException {
        if (fileId == 0) {
            return null;
        }
        // 直接区
        byte[] inode = readBlock("mft_dir4k", dirId, SIZE_4K);
        for (int off = DIRECT_OFFSET; off < DIRECT_OFFSET + DIRECT_BYTES; off += ENTRY_BYTES) {
            long fid = DsDataUtil.loadLong(inode, off + E_OFF_FILE_ID);
            if (fid == fileId) {
                return decodeEntry(inode, off);
            }
        }
        // 64K 块
        for (int p = 0; p < PTR64K_CAP; p++) {
            long blockId = DsDataUtil.loadLong(inode, PTR64K_OFFSET + p * 8);
            if (blockId == 0) {
                continue;
            }
            byte[] blk = readBlock("mft_dir64k", blockId, SIZE_64K);
            for (int off = BLK64K_DATA_OFFSET; off + ENTRY_BYTES <= SIZE_64K; off += ENTRY_BYTES) {
                long fid = DsDataUtil.loadLong(blk, off + E_OFF_FILE_ID);
                if (fid == fileId) {
                    return decodeEntry(blk, off);
                }
            }
        }
        // 64M 块
        for (int p = 0; p < PTR64M_CAP; p++) {
            long blockId = DsDataUtil.loadLong(inode, PTR64M_OFFSET + p * 8);
            if (blockId == 0) {
                continue;
            }
            byte[] blk = readBlock("mft_dir64m", blockId, SIZE_64M);
            for (int off = BLK64M_DATA_OFFSET; off + ENTRY_BYTES <= SIZE_64M; off += ENTRY_BYTES) {
                long fid = DsDataUtil.loadLong(blk, off + E_OFF_FILE_ID);
                if (fid == fileId) {
                    return decodeEntry(blk, off);
                }
            }
        }
        return null;
    }

    /**
     * 释放目录块及其所有间接块。
     */
    public void removeDir(long dirId) throws IOException {
        // 释放 64K 间接块
        byte[] inode = readBlock("mft_dir4k", dirId, SIZE_4K);
        for (int p = 0; p < PTR64K_CAP; p++) {
            long blockId = DsDataUtil.loadLong(inode, PTR64K_OFFSET + p * 8);
            if (blockId != 0) {
                store.remove(DsFixedBucketStore.META_SPACE, "mft_dir64k", blockId);
            }
        }
        // 释放 64M 间接块
        for (int p = 0; p < PTR64M_CAP; p++) {
            long blockId = DsDataUtil.loadLong(inode, PTR64M_OFFSET + p * 8);
            if (blockId != 0) {
                store.remove(DsFixedBucketStore.META_SPACE, "mft_dir64m", blockId);
            }
        }
        // 释放 4K 主块
        store.remove(DsFixedBucketStore.META_SPACE, "mft_dir4k", dirId);
    }

    public void close() throws IOException {
        store.close();
    }

    // ================== 目录项数据结构 ==================

    public static final class Entry {
        public final long fileId;
        public final int nameHash;
        public final short i_mode;

        public Entry(long fileId, int nameHash, short i_mode) {
            this.fileId = fileId;
            this.nameHash = nameHash;
            this.i_mode = i_mode;
        }

        public boolean isDirectory() {
            return (i_mode & 0x4000) != 0;
        }
    }

    // ================== 内部方法 ==================

    private void initHeader(long dirId, long parentDirId) throws IOException {
        writeLong("mft_dir4k", dirId, OFF_PARENT_DIR_ID, parentDirId);
        writeShort("mft_dir4k", dirId, OFF_HEAD, (short) 0);
        writeShort("mft_dir4k", dirId, OFF_TAIL, (short) 0);
        writeInt("mft_dir4k", dirId, OFF_ENTRY_COUNT, 0);
    }

    private void init64kHeader(long blockId) throws IOException {
        writeInt("mft_dir64k", blockId, BLK_OFF_FREE_BASE, 0);
        writeInt("mft_dir64k", blockId, BLK_OFF_FREE_COUNT, 0);
        writeInt("mft_dir64k", blockId, BLK_OFF_FREE_HEAD, 0);
        writeInt("mft_dir64k", blockId, BLK_OFF_FREE_TAIL, 0);
    }

    private void init64mHeader(long blockId) throws IOException {
        writeInt("mft_dir64m", blockId, BLK_OFF_FREE_BASE, 0);
        writeInt("mft_dir64m", blockId, BLK_OFF_FREE_COUNT, 0);
        writeInt("mft_dir64m", blockId, BLK_OFF_FREE_HEAD, 0);
        writeInt("mft_dir64m", blockId, BLK_OFF_FREE_TAIL, 0);
    }

    private void incrementEntryCount(long dirId, int delta) throws IOException {
        int v = readInt("mft_dir4k", dirId, OFF_ENTRY_COUNT);
        int n = v + delta;
        if (n < 0) {
            n = 0;
        }
        writeInt("mft_dir4k", dirId, OFF_ENTRY_COUNT, n);
    }

    // ---- 插入逻辑 ----

    private boolean tryInsertDirect(long dirId, long fileId, int nameHash, short i_mode) throws IOException {
        int head = readShort("mft_dir4k", dirId, OFF_HEAD) & 0xFFFF;
        // head 为 1-based 索引，head > 0 表示有空洞
        if (head > 0) {
            int idx = head - 1;
            if (idx >= 0 && idx < DIRECT_CAP) {
                int off = DIRECT_OFFSET + idx * ENTRY_BYTES;
                long cur = DsDataUtil.loadLong(readBytes("mft_dir4k", dirId, off, 8), 0);
                if (cur == 0) {
                    writeEntry("mft_dir4k", dirId, off, fileId, nameHash, i_mode);
                    // 立即扫描下一个空洞更新 head
                    int next = findZeroEntry("mft_dir4k", dirId, idx + 1, DIRECT_CAP);
                    if (next < 0) {
                        next = findZeroEntry("mft_dir4k", dirId, 0, idx);
                    }
                    writeShort("mft_dir4k", dirId, OFF_HEAD, (short) (next >= 0 ? next + 1 : 0));
                    incrementEntryCount(dirId, 1);
                    return true;
                }
            }
            // head 失效，重新扫描
            int next = findZeroEntry("mft_dir4k", dirId, 0, DIRECT_CAP);
            if (next >= 0) {
                int off = DIRECT_OFFSET + next * ENTRY_BYTES;
                writeEntry("mft_dir4k", dirId, off, fileId, nameHash, i_mode);
                int next2 = findZeroEntry("mft_dir4k", dirId, next + 1, DIRECT_CAP);
                if (next2 < 0) {
                    next2 = findZeroEntry("mft_dir4k", dirId, 0, next);
                }
                writeShort("mft_dir4k", dirId, OFF_HEAD, (short) (next2 >= 0 ? next2 + 1 : 0));
                incrementEntryCount(dirId, 1);
                return true;
            }
            // 确实无空洞
            writeShort("mft_dir4k", dirId, OFF_HEAD, (short) 0);
        }

        int tail = readShort("mft_dir4k", dirId, OFF_TAIL) & 0xFFFF;
        if (tail >= 0 && tail < DIRECT_CAP) {
            int off = DIRECT_OFFSET + tail * ENTRY_BYTES;
            writeEntry("mft_dir4k", dirId, off, fileId, nameHash, i_mode);
            writeShort("mft_dir4k", dirId, OFF_TAIL, (short) (tail + 1));
            incrementEntryCount(dirId, 1);
            return true;
        }
        return false;
    }

    private boolean tryInsert64k(long dirId, long fileId, int nameHash, short i_mode) throws IOException {
        for (int p = 0; p < PTR64K_CAP; p++) {
            int ptrOff = PTR64K_OFFSET + p * 8;
            long blockId = readLong("mft_dir4k", dirId, ptrOff);
            if (blockId == 0) {
                blockId = allocate64kBlock();
                writeLong("mft_dir4k", dirId, ptrOff, blockId);
            }
            if (tryInsert64kBlock(blockId, fileId, nameHash, i_mode)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryInsert64kBlock(long blockId, long fileId, int nameHash, short i_mode) throws IOException {
        int freed = readInt("mft_dir64k", blockId, BLK_OFF_FREE_COUNT);
        if (freed != 0) {
            int head = readInt("mft_dir64k", blockId, BLK_OFF_FREE_HEAD);
            if (head >= 0 && head < BLK64K_CAP) {
                int off = BLK64K_DATA_OFFSET + head * ENTRY_BYTES;
                long cur = DsDataUtil.loadLong(readBytes("mft_dir64k", blockId, off, 8), 0);
                if (cur == 0) {
                    writeEntry("mft_dir64k", blockId, off, fileId, nameHash, i_mode);
                    int newFreed = freed - 1;
                    writeInt("mft_dir64k", blockId, BLK_OFF_FREE_COUNT, newFreed);
                    if (newFreed == 0) {
                        writeInt("mft_dir64k", blockId, BLK_OFF_FREE_HEAD, 0);
                    } else {
                        int next = findZeroEntry64k(blockId, head + 1, BLK64K_CAP);
                        if (next < 0) {
                            next = findZeroEntry64k(blockId, 0, head);
                        }
                        writeInt("mft_dir64k", blockId, BLK_OFF_FREE_HEAD, Math.max(next, 0));
                    }
                    return true;
                }
            }
            int next = findZeroEntry64k(blockId, 0, BLK64K_CAP);
            if (next >= 0) {
                int off = BLK64K_DATA_OFFSET + next * ENTRY_BYTES;
                writeEntry("mft_dir64k", blockId, off, fileId, nameHash, i_mode);
                int newFreed = freed - 1;
                writeInt("mft_dir64k", blockId, BLK_OFF_FREE_COUNT, newFreed);
                if (newFreed == 0) {
                    writeInt("mft_dir64k", blockId, BLK_OFF_FREE_HEAD, 0);
                } else {
                    int next2 = findZeroEntry64k(blockId, next + 1, BLK64K_CAP);
                    if (next2 < 0) {
                        next2 = findZeroEntry64k(blockId, 0, next);
                    }
                    writeInt("mft_dir64k", blockId, BLK_OFF_FREE_HEAD, Math.max(next2, 0));
                }
                return true;
            }
            writeInt("mft_dir64k", blockId, BLK_OFF_FREE_COUNT, 0);
            writeInt("mft_dir64k", blockId, BLK_OFF_FREE_HEAD, 0);
        }

        int tail = readInt("mft_dir64k", blockId, BLK_OFF_FREE_TAIL);
        if (tail >= 0 && tail < BLK64K_CAP) {
            int off = BLK64K_DATA_OFFSET + tail * ENTRY_BYTES;
            writeEntry("mft_dir64k", blockId, off, fileId, nameHash, i_mode);
            writeInt("mft_dir64k", blockId, BLK_OFF_FREE_TAIL, tail + 1);
            return true;
        }
        return false;
    }

    private boolean tryInsert64m(long dirId, long fileId, int nameHash, short i_mode) throws IOException {
        for (int p = 0; p < PTR64M_CAP; p++) {
            int ptrOff = PTR64M_OFFSET + p * 8;
            long blockId = readLong("mft_dir4k", dirId, ptrOff);
            if (blockId == 0) {
                blockId = allocate64mBlock();
                writeLong("mft_dir4k", dirId, ptrOff, blockId);
            }
            if (tryInsert64mBlock(blockId, fileId, nameHash, i_mode)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryInsert64mBlock(long blockId, long fileId, int nameHash, short i_mode) throws IOException {
        int freed = readInt("mft_dir64m", blockId, BLK_OFF_FREE_COUNT);
        if (freed != 0) {
            int head = readInt("mft_dir64m", blockId, BLK_OFF_FREE_HEAD);
            if (head >= 0 && head < BLK64M_CAP) {
                int off = BLK64M_DATA_OFFSET + head * ENTRY_BYTES;
                long cur = DsDataUtil.loadLong(readBytes("mft_dir64m", blockId, off, 8), 0);
                if (cur == 0) {
                    writeEntry("mft_dir64m", blockId, off, fileId, nameHash, i_mode);
                    int newFreed = freed - 1;
                    writeInt("mft_dir64m", blockId, BLK_OFF_FREE_COUNT, newFreed);
                    if (newFreed == 0) {
                        writeInt("mft_dir64m", blockId, BLK_OFF_FREE_HEAD, 0);
                    } else {
                        int next = findZeroEntry64m(blockId, head + 1, BLK64M_CAP);
                        if (next < 0) {
                            next = findZeroEntry64m(blockId, 0, head);
                        }
                        writeInt("mft_dir64m", blockId, BLK_OFF_FREE_HEAD, Math.max(next, 0));
                    }
                    return true;
                }
            }
            int next = findZeroEntry64m(blockId, 0, BLK64M_CAP);
            if (next >= 0) {
                int off = BLK64M_DATA_OFFSET + next * ENTRY_BYTES;
                writeEntry("mft_dir64m", blockId, off, fileId, nameHash, i_mode);
                int newFreed = freed - 1;
                writeInt("mft_dir64m", blockId, BLK_OFF_FREE_COUNT, newFreed);
                if (newFreed == 0) {
                    writeInt("mft_dir64m", blockId, BLK_OFF_FREE_HEAD, 0);
                } else {
                    int next2 = findZeroEntry64m(blockId, next + 1, BLK64M_CAP);
                    if (next2 < 0) {
                        next2 = findZeroEntry64m(blockId, 0, next);
                    }
                    writeInt("mft_dir64m", blockId, BLK_OFF_FREE_HEAD, Math.max(next2, 0));
                }
                return true;
            }
            writeInt("mft_dir64m", blockId, BLK_OFF_FREE_COUNT, 0);
            writeInt("mft_dir64m", blockId, BLK_OFF_FREE_HEAD, 0);
        }

        int tail = readInt("mft_dir64m", blockId, BLK_OFF_FREE_TAIL);
        if (tail >= 0 && tail < BLK64M_CAP) {
            int off = BLK64M_DATA_OFFSET + tail * ENTRY_BYTES;
            writeEntry("mft_dir64m", blockId, off, fileId, nameHash, i_mode);
            writeInt("mft_dir64m", blockId, BLK_OFF_FREE_TAIL, tail + 1);
            return true;
        }
        return false;
    }

    // ---- 删除逻辑 ----

    private boolean removeFromDirect(long dirId, long fileId) throws IOException {
        for (int i = 0; i < DIRECT_CAP; i++) {
            int off = DIRECT_OFFSET + i * ENTRY_BYTES;
            long fid = readLong("mft_dir4k", dirId, off + E_OFF_FILE_ID);
            if (fid == fileId) {
                clearEntry("mft_dir4k", dirId, off);
                int head = readShort("mft_dir4k", dirId, OFF_HEAD) & 0xFFFF;
                // head 为 1-based；若当前空洞更靠前则更新
                if (head == 0 || i < (head - 1)) {
                    writeShort("mft_dir4k", dirId, OFF_HEAD, (short) (i + 1));
                }
                return true;
            }
        }
        return false;
    }

    private boolean removeFrom64k(long dirId, long fileId) throws IOException {
        byte[] inode = readBlock("mft_dir4k", dirId, SIZE_4K);
        for (int p = 0; p < PTR64K_CAP; p++) {
            long blockId = DsDataUtil.loadLong(inode, PTR64K_OFFSET + p * 8);
            if (blockId == 0) {
                continue;
            }
            for (int i = 0; i < BLK64K_CAP; i++) {
                int off = BLK64K_DATA_OFFSET + i * ENTRY_BYTES;
                long fid = readLong("mft_dir64k", blockId, off + E_OFF_FILE_ID);
                if (fid == fileId) {
                    clearEntry("mft_dir64k", blockId, off);
                    int freed = readInt("mft_dir64k", blockId, BLK_OFF_FREE_COUNT);
                    writeInt("mft_dir64k", blockId, BLK_OFF_FREE_COUNT, freed + 1);
                    int head = readInt("mft_dir64k", blockId, BLK_OFF_FREE_HEAD);
                    if (head == 0 || i < head) {
                        writeInt("mft_dir64k", blockId, BLK_OFF_FREE_HEAD, i);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private boolean removeFrom64m(long dirId, long fileId) throws IOException {
        byte[] inode = readBlock("mft_dir4k", dirId, SIZE_4K);
        for (int p = 0; p < PTR64M_CAP; p++) {
            long blockId = DsDataUtil.loadLong(inode, PTR64M_OFFSET + p * 8);
            if (blockId == 0) {
                continue;
            }
            for (int i = 0; i < BLK64M_CAP; i++) {
                int off = BLK64M_DATA_OFFSET + i * ENTRY_BYTES;
                long fid = readLong("mft_dir64m", blockId, off + E_OFF_FILE_ID);
                if (fid == fileId) {
                    clearEntry("mft_dir64m", blockId, off);
                    int freed = readInt("mft_dir64m", blockId, BLK_OFF_FREE_COUNT);
                    writeInt("mft_dir64m", blockId, BLK_OFF_FREE_COUNT, freed + 1);
                    int head = readInt("mft_dir64m", blockId, BLK_OFF_FREE_HEAD);
                    if (head == 0 || i < head) {
                        writeInt("mft_dir64m", blockId, BLK_OFF_FREE_HEAD, i);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    // ---- 辅助：查找空洞 ----

    private int findZeroEntry(String type, long id, int startIdx, int endIdx) throws IOException {
        for (int i = startIdx; i < endIdx; i++) {
            int off = DIRECT_OFFSET + i * ENTRY_BYTES;
            long v = readLong(type, id, off + E_OFF_FILE_ID);
            if (v == 0) {
                return i;
            }
        }
        return -1;
    }

    private int findZeroEntry64k(long blockId, int startIdx, int endIdx) throws IOException {
        for (int i = startIdx; i < endIdx; i++) {
            int off = BLK64K_DATA_OFFSET + i * ENTRY_BYTES;
            long v = readLong("mft_dir64k", blockId, off + E_OFF_FILE_ID);
            if (v == 0) {
                return i;
            }
        }
        return -1;
    }

    private int findZeroEntry64m(long blockId, int startIdx, int endIdx) throws IOException {
        for (int i = startIdx; i < endIdx; i++) {
            int off = BLK64M_DATA_OFFSET + i * ENTRY_BYTES;
            long v = readLong("mft_dir64m", blockId, off + E_OFF_FILE_ID);
            if (v == 0) {
                return i;
            }
        }
        return -1;
    }

    // ---- 块分配 ----

    private long allocate64kBlock() throws IOException {
        byte[] zeros = new byte[SIZE_64K];
        long id = store.put(DsFixedBucketStore.META_SPACE, "mft_dir64k", zeros);
        init64kHeader(id);
        return id;
    }

    private long allocate64mBlock() throws IOException {
        byte[] zeros = new byte[SIZE_64M];
        long id = store.put(DsFixedBucketStore.META_SPACE, "mft_dir64m", zeros);
        init64mHeader(id);
        return id;
    }

    // ---- 条目编解码 ----

    private Entry decodeEntry(byte[] buf, int off) {
        long fileId = DsDataUtil.loadLong(buf, off + E_OFF_FILE_ID);
        int nameHash = DsDataUtil.loadInt(buf, off + E_OFF_NAME_HASH);
        short i_mode = (short) ((buf[off + E_OFF_I_MODE] & 0xFF) | ((buf[off + E_OFF_I_MODE + 1] & 0xFF) << 8));
        return new Entry(fileId, nameHash, i_mode);
    }

    private void writeEntry(String type, long id, int off, long fileId, int nameHash, short i_mode) throws IOException {
        byte[] b = new byte[ENTRY_BYTES];
        DsDataUtil.storeLong(b, E_OFF_FILE_ID, fileId);
        DsDataUtil.storeInt(b, E_OFF_NAME_HASH, nameHash);
        b[E_OFF_I_MODE] = (byte) (i_mode & 0xFF);
        b[E_OFF_I_MODE + 1] = (byte) ((i_mode >>> 8) & 0xFF);
        store.update(DsFixedBucketStore.META_SPACE, type, id, off, b);
    }

    private void clearEntry(String type, long id, int off) throws IOException {
        byte[] b = new byte[ENTRY_BYTES];
        store.update(DsFixedBucketStore.META_SPACE, type, id, off, b);
    }

    // ---- 底层读写 ----

    private byte[] readBlock(String type, long id, int size) throws IOException {
        return store.get(DsFixedBucketStore.META_SPACE, type, id, 0, size);
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

    private int readInt(String type, long id, int offset) throws IOException {
        byte[] b = store.get(DsFixedBucketStore.META_SPACE, type, id, offset, 4);
        return DsDataUtil.loadInt(b, 0);
    }

    private void writeInt(String type, long id, int offset, int value) throws IOException {
        byte[] b = new byte[4];
        DsDataUtil.storeInt(b, 0, value);
        store.update(DsFixedBucketStore.META_SPACE, type, id, offset, b);
    }

    private short readShort(String type, long id, int offset) throws IOException {
        byte[] b = store.get(DsFixedBucketStore.META_SPACE, type, id, offset, 2);
        return (short) ((b[0] & 0xFF) | ((b[1] & 0xFF) << 8));
    }

    private void writeShort(String type, long id, int offset, short value) throws IOException {
        byte[] b = new byte[2];
        b[0] = (byte) (value & 0xFF);
        b[1] = (byte) ((value >>> 8) & 0xFF);
        store.update(DsFixedBucketStore.META_SPACE, type, id, offset, b);
    }

    private byte[] readBytes(String type, long id, int offset, int length) throws IOException {
        return store.get(DsFixedBucketStore.META_SPACE, type, id, offset, length);
    }
}
