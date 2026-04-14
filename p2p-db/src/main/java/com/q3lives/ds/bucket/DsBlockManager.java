package com.q3lives.ds.bucket;

import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

import com.q3lives.ds.core.DsObject;

/**
 * 固定大小 block 的分配与回收管理器（blockId -> blockBytes）。
 *
 * <p>职责拆分：</p>
 * <ul>
 *   <li>dataStore（{@link DsObject}）：真正承载 block 内容的数据文件，按 blockSize 做定长单元随机读写。</li>
 *   <li>meta（本类继承 {@link DsObject}）：维护 nextId 与 free-ring（可复用 id 队列）的持久化元数据。</li>
 * </ul>
 *
 * <p>分配策略：</p>
 * <ul>
 *   <li>优先从 free-ring 取回收的 id（复用）。</li>
 *   <li>free-ring 为空时从 nextId 递增分配新 id。</li>
 * </ul>
 *
 * <p>回收策略：</p>
 * <ul>
 *   <li>releaseId/releaseIds 仅把 id 放回 free-ring，不清零对应数据文件内容。</li>
 * </ul>
 *
 * <p>兼容性：</p>
 * <ul>
 *   <li>支持从 MAGIC_V1 迁移到 MAGIC_V2：V2 使用环形队列（freeHead/freeTail/freeCount）。</li>
 * </ul>
 */
public class DsBlockManager extends DsObject implements DsBlockService {
    private static final byte[] MAGIC_V1 = new byte[] {'.', 'M', 'G', 'R'};
    private static final byte[] MAGIC_V2 = new byte[] {'.', 'M', 'G', '2'};
    private static final int HEADER_SIZE = 128;
    private static final int OFFSET_MAGIC = 0;
    private static final int OFFSET_BLOCK_SIZE = 4;
    private static final int OFFSET_RESERVED = 8;
    private static final int OFFSET_NEXT_ID = 16;
    private static final int OFFSET_FREE_BASE = 24;
    private static final int OFFSET_FREE_CAP = 32;
    private static final int OFFSET_FREE_HEAD = 40;
    private static final int OFFSET_FREE_TAIL = 48;
    private static final int OFFSET_FREE_COUNT = 56;
    private static final long DEFAULT_FREE_CAP = 1024L;

    private final ReentrantLock lock = new ReentrantLock();
    private final DsObject dataStore;
    private final int blockSize;
    private final long reservedIds;

    private long freeBase;
    private long freeCap;
    private long freeHead;
    private long freeTail;
    private long freeCount;

    /**
     * 创建一个 block 管理器（默认 reservedIds=1，保留 id=0）。
     *
     * @param metaFile 元数据文件（保存 nextId 与 free-ring）
     * @param dataFile block 数据文件（按 blockSize 定长单元存储）
     * @param blockSize 每个 block 的固定大小（byte）
     */
    public DsBlockManager(File metaFile, File dataFile, int blockSize) {
        this(metaFile, dataFile, blockSize, 1L);
    }

    /**
     * 创建一个 block 管理器，允许指定保留 id 数量。
     *
     * <p>reservedIds 表示 [0,reservedIds) 区间不参与分配/回收。</p>
     */
    public DsBlockManager(File metaFile, File dataFile, int blockSize, long reservedIds) {
        this(metaFile, new DsObject(dataFile, blockSize), blockSize, reservedIds);
    }

    /**
     * 创建一个 block 管理器（dataStore 由外部注入）。
     *
     * <p>该构造器便于测试或复用已有的 {@link DsObject} 映射。</p>
     */
    public DsBlockManager(File metaFile, DsObject dataStore, int blockSize, long reservedIds) {
        super(metaFile, 8);
        if (dataStore == null) {
            throw new IllegalArgumentException("dataStore is null");
        }
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be > 0");
        }
        if (reservedIds < 0) {
            throw new IllegalArgumentException("reservedIds must be >= 0");
        }
        this.dataStore = dataStore;
        this.blockSize = blockSize;
        this.reservedIds = reservedIds;
        initHeader();
    }

    private void initHeader() {
        lock.lock();
        try {
            headerBuffer = this.loadBuffer(0L);
            byte[] b = new byte[4];
            headerBuffer.get(OFFSET_MAGIC, b, 0, 4);
            if (Arrays.equals(b, MAGIC_V2)) {
                int bs = headerBuffer.getInt(OFFSET_BLOCK_SIZE);
                long r = headerBuffer.getLong(OFFSET_RESERVED);
                if (bs != this.blockSize) {
                    throw new IllegalStateException("blockSize mismatch");
                }
                if (r != this.reservedIds) {
                    throw new IllegalStateException("reservedIds mismatch");
                }
                long nextId = headerBuffer.getLong(OFFSET_NEXT_ID);
                if (nextId < this.reservedIds) {
                    headerBuffer.putLong(OFFSET_NEXT_ID, this.reservedIds);
                    dirty(0L);
                }
                loadFreeHeader();
                return;
            }
            if (Arrays.equals(b, MAGIC_V1)) {
                migrateFromV1();
                return;
            }
            initNewV2();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    private void initNewV2() throws IOException {
        headerBuffer.put(OFFSET_MAGIC, MAGIC_V2, 0, 4);
        headerBuffer.putInt(OFFSET_BLOCK_SIZE, this.blockSize);
        headerBuffer.putLong(OFFSET_RESERVED, this.reservedIds);
        headerBuffer.putLong(OFFSET_NEXT_ID, this.reservedIds);
        freeBase = HEADER_SIZE;
        freeCap = DEFAULT_FREE_CAP;
        freeHead = 0L;
        freeTail = 0L;
        freeCount = 0L;
        headerBuffer.putLong(OFFSET_FREE_BASE, freeBase);
        headerBuffer.putLong(OFFSET_FREE_CAP, freeCap);
        headerBuffer.putLong(OFFSET_FREE_HEAD, freeHead);
        headerBuffer.putLong(OFFSET_FREE_TAIL, freeTail);
        headerBuffer.putLong(OFFSET_FREE_COUNT, freeCount);
        ensureFreeRegionSize(freeBase, freeCap);
        dirty(0L);
    }

    private void migrateFromV1() throws IOException {
        int bs = headerBuffer.getInt(OFFSET_BLOCK_SIZE);
        long r = headerBuffer.getLong(OFFSET_RESERVED);
        if (bs != this.blockSize) {
            throw new IllegalStateException("blockSize mismatch");
        }
        if (r != this.reservedIds) {
            throw new IllegalStateException("reservedIds mismatch");
        }

        long nextId = headerBuffer.getLong(OFFSET_NEXT_ID);
        if (nextId < this.reservedIds) {
            nextId = this.reservedIds;
        }

        long oldHeadBytes = headerBuffer.getLong(24);
        long oldTailBytes = headerBuffer.getLong(32);
        long oldBase = HEADER_SIZE;
        long remain = oldTailBytes > oldHeadBytes ? (oldTailBytes - oldHeadBytes) / 8 : 0;
        long[] pending = remain > 0 ? new long[(int) Math.min(remain, Integer.MAX_VALUE)] : new long[0];
        if (pending.length > 0) {
            loadLongOffset(oldBase + oldHeadBytes, pending);
        }

        headerBuffer.put(OFFSET_MAGIC, MAGIC_V2, 0, 4);
        headerBuffer.putInt(OFFSET_BLOCK_SIZE, this.blockSize);
        headerBuffer.putLong(OFFSET_RESERVED, this.reservedIds);
        headerBuffer.putLong(OFFSET_NEXT_ID, nextId);

        freeBase = HEADER_SIZE;
        long cap = DEFAULT_FREE_CAP;
        if (pending.length > 0) {
            cap = Math.max(DEFAULT_FREE_CAP, (long) pending.length * 2L);
        }
        freeCap = cap;
        freeHead = 0L;
        freeCount = pending.length;
        freeTail = freeCount;

        headerBuffer.putLong(OFFSET_FREE_BASE, freeBase);
        headerBuffer.putLong(OFFSET_FREE_CAP, freeCap);
        headerBuffer.putLong(OFFSET_FREE_HEAD, freeHead);
        headerBuffer.putLong(OFFSET_FREE_TAIL, freeTail);
        headerBuffer.putLong(OFFSET_FREE_COUNT, freeCount);

        ensureFreeRegionSize(freeBase, freeCap);
        if (pending.length > 0) {
            storeLongOffset(freeBase, pending);
        }
        dirty(0L);
    }

    private void ensureFreeRegionSize(long base, long cap) throws IOException {
        if (cap <= 0) {
            return;
        }
        long last = base + cap * 8L - 8L;
        storeLongOffset(last, 0L);
    }

    private void loadFreeHeader() {
        freeBase = headerBuffer.getLong(OFFSET_FREE_BASE);
        freeCap = headerBuffer.getLong(OFFSET_FREE_CAP);
        freeHead = headerBuffer.getLong(OFFSET_FREE_HEAD);
        freeTail = headerBuffer.getLong(OFFSET_FREE_TAIL);
        freeCount = headerBuffer.getLong(OFFSET_FREE_COUNT);
        if (freeBase < HEADER_SIZE) {
            freeBase = HEADER_SIZE;
        }
        if (freeCap <= 0) {
            freeCap = DEFAULT_FREE_CAP;
        }
        if (freeHead < 0 || freeHead >= freeCap) {
            freeHead = 0;
        }
        if (freeTail < 0 || freeTail >= freeCap) {
            freeTail = freeCount % freeCap;
        }
        if (freeCount < 0) {
            freeCount = 0;
        }
    }

    @Override
    /**
     * 返回单个 block 的固定大小（byte）。
     */
    public int dataSize() {
        return blockSize;
    }

    @Override
    /**
     * 返回当前已分配/可寻址的 blockId 上界（nextId）。
     *
     * <p>该值用于粗略估算容量，并不表示“有效 block 数”。</p>
     */
    public long capacity() {
        lock.lock();
        try {
            return headerBuffer.getLong(OFFSET_NEXT_ID);
        } finally {
            lock.unlock();
        }
    }

    @Override
    /**
     * 读取一个 block（按 id 定长读取 blockSize 字节）。
     *
     * @param id blockId（必须 >= reservedIds）
     */
    public byte[] read(long id) {
        if (id < reservedIds) {
            throw new IllegalArgumentException("invalid id");
        }
        byte[] out = new byte[blockSize];
        try {
            dataStore.readUnit(id, out, 0);
            return out;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /**
     * 覆盖写入一个 block（data.length 必须等于 blockSize）。
     */
    public void write(long id, byte[] data) {
        if (id < reservedIds) {
            throw new IllegalArgumentException("invalid id");
        }
        if (data == null || data.length != blockSize) {
            throw new IllegalArgumentException("invalid block data");
        }
        try {
            dataStore.writeUnit(id, data, 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /**
     * 分配一个新的 blockId。
     *
     * <p>优先从 free-ring 复用回收 id；为空时从 nextId 递增分配。</p>
     */
    public long getNewId() {
        lock.lock();
        try {
            if (freeCount > 0) {
                long id = readLongAt(freeBase + freeHead * 8L);
                freeHead = (freeHead + 1) % freeCap;
                freeCount--;
                persistFreeHeader();
                dirty(0L);
                return id;
            }
            long nextId = headerBuffer.getLong(OFFSET_NEXT_ID);
            headerBuffer.putLong(OFFSET_NEXT_ID, nextId + 1);
            dirty(0L);
            return nextId;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    /**
     * 批量分配 blockId。
     *
     * <p>实现先尽可能从 free-ring 取回收 id，再从 nextId 递增补足。</p>
     */
    public long[] getNewIds(int size) {
        if (size <= 0) {
            return new long[0];
        }
        lock.lock();
        try {
            long[] ids = new long[size];
            int fromFree = 0;
            if (freeCount > 0) {
                fromFree = (int) Math.min((long) size, freeCount);
                for (int i = 0; i < fromFree; i++) {
                    ids[i] = readLongAt(freeBase + freeHead * 8L);
                    freeHead = (freeHead + 1) % freeCap;
                    freeCount--;
                }
                persistFreeHeader();
            }
            if (fromFree < size) {
                long nextId = headerBuffer.getLong(OFFSET_NEXT_ID);
                for (int i = fromFree; i < size; i++) {
                    ids[i] = nextId++;
                }
                headerBuffer.putLong(OFFSET_NEXT_ID, nextId);
            }
            dirty(0L);
            return ids;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    /**
     * 回收一个 blockId（加入 free-ring 供后续复用）。
     *
     * <p>回收不会清零数据文件内容。</p>
     */
    public void releaseId(long id) {
        if (id < reservedIds) {
            return;
        }
        lock.lock();
        try {
            offerFree(id);
            dirty(0L);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    /**
     * 批量回收 blockId（加入 free-ring）。
     */
    public void releaseIds(long[] ids) {
        if (ids == null || ids.length == 0) {
            return;
        }
        lock.lock();
        try {
            for (long id : ids) {
                if (id >= reservedIds) {
                    offerFree(id);
                }
            }
            dirty(0L);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    private void offerFree(long id) throws IOException {
        if (freeCount >= freeCap) {
            expandFree();
        }
        storeLongOffset(freeBase + freeTail * 8L, id);
        freeTail = (freeTail + 1) % freeCap;
        freeCount++;
        persistFreeHeader();
    }

    private void persistFreeHeader() {
        headerBuffer.putLong(OFFSET_FREE_BASE, freeBase);
        headerBuffer.putLong(OFFSET_FREE_CAP, freeCap);
        headerBuffer.putLong(OFFSET_FREE_HEAD, freeHead);
        headerBuffer.putLong(OFFSET_FREE_TAIL, freeTail);
        headerBuffer.putLong(OFFSET_FREE_COUNT, freeCount);
    }

    private void expandFree() throws IOException {
        long newCap = freeCap * 2L;
        long newBase = freeBase + freeCap * 8L;
        ensureFreeRegionSize(newBase, newCap);
        for (long i = 0; i < freeCount; i++) {
            long slot = (freeHead + i) % freeCap;
            long v = readLongAt(freeBase + slot * 8L);
            storeLongOffset(newBase + i * 8L, v);
        }
        freeBase = newBase;
        freeCap = newCap;
        freeHead = 0L;
        freeTail = freeCount;
        persistFreeHeader();
    }

    private long readLongAt(long position) throws IOException {
        Long bufferIndex = position / BLOCK_SIZE;
        int offset = (int) (position % BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        return buffer.getLong(offset);
    }
}
