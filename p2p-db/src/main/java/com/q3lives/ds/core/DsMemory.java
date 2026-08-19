package com.q3lives.ds.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.collections.DsList;
import com.q3lives.ds.fs.Ds128SuperInode;
import com.q3lives.ds.header.HeaderTieredStore;
import com.q3lives.ds.header.HeaderTieredStoreFactory;

/**
 * 基于内存映射文件(Memory Mapped Files, MappedByteBuffer)的持久化数据结构基类。
 * <p>
 * 本类主要负责底层的文件I/O操作和内存管理，为上层数据结构（如 {@link DsList}, {@link DsHashSet}）提供支持。
 * 主要功能包括：
 * <ul>
 * <li><b>文件I/O管理：</b> 通过 {@link RandomAccessFile} 和 {@link FileChannel}
 * 进行文件的读写操作。</li>
 * <li><b>内存映射：</b> 将文件分块（Block）映射到内存中，生成
 * {@link MappedByteBuffer}，实现高效的随机访问。</li>
 * <li><b>基本类型读写：</b> 提供了针对 long, int, short, byte, float, double
 * 等基本数据类型的读写方法。</li>
 * <li><b>缓冲池管理：</b> 维护了一个 {@link MappedByteBuffer} 的缓存池
 * (`datatBuffers`)，减少重复映射的开销。</li>
 * <li><b>脏页管理：</b> 跟踪修改过的缓冲区 (`dirtyBuffers`)，并提供 {@link #sync()}
 * 方法将数据持久化到磁盘。</li>
 * <li><b>并发控制：</b> 提供了基础的锁机制（如 `headerOpLock`），并支持子类实现更细粒度的并发控制。</li>
 * </ul>
 * </p>
 */
public class DsMemory {

    /**
     * 启用分区(同一服务器分表空间(不同硬盘)存储,以纵向扩展/并行读写提高性能)
     */
    protected boolean isPatitioned = false;

    /**
     * 启用分布式(不同服务器存储,以横向扩展/并行读写提高性能)
     */
    protected boolean isDistributed = false;

    protected static final Charset UTF_8 = Charset.forName("UTF-8");
    protected static final int LONG_SIZE = 8;
    protected static final int INT_SIZE = 4;
    protected static final int MD5_SIZE = 16;
    /**
     * 计算 Ds128SuperInode 的字节大小: 8*10 + 4*2 + 16
     */
    protected static final int SIZE = 8 * 10 + 4 * 2 + 16;

    /**
     * 默认块尺寸 64KB -> 对应单个 MappedByteBuffer 的大小
     */
    protected static final int BLOCK_SIZE = 64 * 1024;
    public static final int BLOCK_SIZE_REFLECT = BLOCK_SIZE;
    /**
     * 用于初始化新块的零字节数组
     */
    protected static final byte[] ZERO_BLOCK_BYTES = new byte[BLOCK_SIZE];

    protected static final int DEFAULT_MAX_CACHED_BLOCKS = Integer.getInteger("ds.memory.maxCachedBlocks", 2048);
    protected static final long DEFAULT_MAX_CACHED_BYTES = (long) DEFAULT_MAX_CACHED_BLOCKS * (long) BLOCK_SIZE;
    protected static final int EVICT_CANDIDATE_SLOT = 16;

    protected volatile int maxCachedBlocks = DEFAULT_MAX_CACHED_BLOCKS;
    protected volatile long maxCachedBytes = DEFAULT_MAX_CACHED_BYTES;
    protected final AtomicInteger activeCachedBlocks = new AtomicInteger(0);
    protected final AtomicLong cachedBytes = new AtomicLong(0);
    protected final ReentrantLock evictionLock = new ReentrantLock();
    protected volatile int highestBufferIndexEverSeen = -1;

    protected final Map<Integer, Long> bufferLastAccessNanos = new ConcurrentHashMap<>();
    protected final Set<Integer> dirtyBufferIndices = ConcurrentHashMap.newKeySet();
    protected final AtomicLong evictionAttempts = new AtomicLong(0);
    protected final AtomicLong evictionSuccess = new AtomicLong(0);
    protected final AtomicLong evictionBytes = new AtomicLong(0);
    protected final AtomicLong evictionDirtyCount = new AtomicLong(0);
    protected final int[] evictCandIdxs = new int[EVICT_CANDIDATE_SLOT];
    protected final long[] evictCandAccess = new long[EVICT_CANDIDATE_SLOT];

    public static final class CacheStats {
        private final int maxCachedBlocks;
        private final long maxCachedBytes;
        private final int activeCachedBlocks;
        private final long cachedBytes;
        private final int dirtyBuffers;
        private final int highestIndex;
        private final long evictionAttempts;
        private final long evictionSuccess;
        private final long evictionBytes;
        private final long evictionDirtyCount;

        public CacheStats(int maxCachedBlocks, long maxCachedBytes, int activeCachedBlocks, long cachedBytes,
            int dirtyBuffers, int highestIndex, long evictionAttempts, long evictionSuccess,
            long evictionBytes, long evictionDirtyCount) {
            this.maxCachedBlocks = maxCachedBlocks;
            this.maxCachedBytes = maxCachedBytes;
            this.activeCachedBlocks = activeCachedBlocks;
            this.cachedBytes = cachedBytes;
            this.dirtyBuffers = dirtyBuffers;
            this.highestIndex = highestIndex;
            this.evictionAttempts = evictionAttempts;
            this.evictionSuccess = evictionSuccess;
            this.evictionBytes = evictionBytes;
            this.evictionDirtyCount = evictionDirtyCount;
        }

        public int getMaxCachedBlocks() { return maxCachedBlocks; }
        public long getMaxCachedBytes() { return maxCachedBytes; }
        public int getActiveCachedBlocks() { return activeCachedBlocks; }
        public long getCachedBytes() { return cachedBytes; }
        public int getDirtyBuffers() { return dirtyBuffers; }
        public int getHighestIndex() { return highestIndex; }
        public long getEvictionAttempts() { return evictionAttempts; }
        public long getEvictionSuccess() { return evictionSuccess; }
        public long getEvictionBytes() { return evictionBytes; }
        public long getEvictionDirtyCount() { return evictionDirtyCount; }
    }

    // DONE (was TODO): 实现缓存淘汰策略 (如 LRU)，防止大文件加载导致内存溢出 (OOM)。
    /**
     * 数据缓冲区缓存池：Key为块索引(bufferIndex)，Value为映射的内存缓冲区。
     * 被淘汰的槽会被置为 null（保持绝对 bufferIndex 不变，避免索引错位）。
     */
    // 2026-08-17 row6 优化：将 ArrayList -> volatile Object[] 快照，
    // 读路径 fast path 0 lock，仅扩容/覆盖写时 bufferLock 内复制引用覆盖 bufSnap/byteSnap，
    // 写成本约等于 CopyOnWriteArrayList 但无泛型 boxing + 无内部 ReentrantLock
    protected volatile Object[] bufSnap = new Object[1024];
    protected volatile Object[] byteSnap = new Object[1024];

    /**
     * 保留 dataBuffers/dataBytes 老字段引用（接口兼容：eviction/外部调试仍可按 List 风格访问）
     * 语义：等价于 Arrays.asList(bufSnap/byteSnap)，内部真实存储走 volatile snapshot；
     * 写入统一走 applyBuf/ByteSnap 保证 bufSnap/byteSnap 与 List 内容同步（bufferLock 内写入）
     */
    protected List<ByteBuffer> dataBuffers = new java.util.concurrent.CopyOnWriteArrayList<>();
    protected List<byte[]> dataBytes = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 数据缓冲区锁：用于控制对特定缓冲区的并发访问
     */
    protected static final int DATA_BUFFER_LOCK_STRIPES = 256;
    protected final ReentrantReadWriteLock[] dataBufferLocks = new ReentrantReadWriteLock[DATA_BUFFER_LOCK_STRIPES];

    //protected Map<Long,Long> datatBufferLastModified = new ConcurrentHashMap<>();//buffer最后修改时间
    /**
     * 默认的锁池大小
     */
    protected static final int DEFAULT_LOCK_POOL_SIZE = 50;

    /**
     * ID锁池：预先创建的一组锁，用于减少频繁创建/销毁锁对象的开销。容量通常对应批处理大小。
     */
    protected ArrayBlockingQueue<ReentrantLock> idLockPool = new ArrayBlockingQueue<>(DEFAULT_LOCK_POOL_SIZE);

    protected ReentrantLock idOpLock = new ReentrantLock();

    protected Map<Long, ReentrantLock> idLocks = new ConcurrentHashMap<>();

    /**
     * 头信息操作锁：用于保护文件头部的并发修改
     */
    protected ReentrantReadWriteLock headerOpLockRW = new ReentrantReadWriteLock();

    protected ReentrantReadWriteLock.ReadLock headerOpLockRead = headerOpLockRW.readLock();
    protected ReentrantReadWriteLock.WriteLock headerOpLockWrite = headerOpLockRW.writeLock();

    /**
     * 固定长度。数据单元大小
     */
    protected int dataUnitSize;

    /**
     * 用于初始化数据块的零字节数组
     */
    protected final byte[] zero_block_unit;
    /**
     * 元数据块尺寸。TODO 用一个或多个统一的文件，统一管理元数据。元数据固定长度的4个文件:128,512,1024,4096
     */
    public int metaUnitSize;

    /**
     * 同步操作锁：用于 sync() 方法
     */
    protected ReentrantLock syncOpLock = new ReentrantLock();
    /**
     * 缓冲区操作锁
     */
    protected ReentrantLock bufferLock = new ReentrantLock();
    
    protected ByteBuffer headerBuffer;

    protected HeaderTieredStore headerTier;

    private boolean headerTierAttached = false;
    
    public final int headerSize;
    protected final File dataFile;
    protected DsWAL wal;

    public DsMemory(File dataFile,int headerSize, int dataUnitSize) {
        this.dataFile = dataFile;
        this.dataUnitSize = dataUnitSize;
        this.headerSize = headerSize;
        zero_block_unit = new byte[dataUnitSize];
        for (int i = 0; i < DATA_BUFFER_LOCK_STRIPES; i++) {
            dataBufferLocks[i] = new ReentrantReadWriteLock();
        }
        for (int i = 0; i < DEFAULT_LOCK_POOL_SIZE; i++) {
            idLockPool.add(new ReentrantLock());
        }
        this.headerTier = HeaderTieredStoreFactory.create(dataFile, dataFile == null ? "dsmem" : dataFile.getName());
        if (this.dataFile != null) {
            this.wal = new DsWAL(this.dataFile);
        }
    }

    protected final void ensureHeaderTierAttached() {
        if (headerTierAttached || headerBuffer == null || headerTier == null) return;
        try {
            headerTier.attachBase(headerBuffer);
            headerTierAttached = true;
        } catch (IOException e) {
            throw new IllegalStateException("attach header tier failed", e);
        }
    }

    protected final void markHeaderFieldDirty(int offset, int len) {
        ensureHeaderTierAttached();
        if (headerTier != null) {
            headerTier.markFieldDirty(offset, len);
        }
    }

    protected final void markHeaderFullDirty() {
        ensureHeaderTierAttached();
        if (headerTier != null) {
            headerTier.markFullDirty();
        }
        markDirty(0);
    }

    
    /**
     * 将 SuperInode 写入文件
     *
     * @param inode 索引节点对象
     * @param filePath 文件路径
     * @ IO异常
     */
    public static void writeToFile(Ds128SuperInode inode, String filePath)  {
        try (RandomAccessFile file = new RandomAccessFile(new File(filePath), "rw"); FileChannel channel = file.getChannel()) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, SIZE);
            inode.writeToMappedByteBuffer(buffer);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * 从文件读取 SuperInode
     *
     * @param filePath 文件路径
     * @return 索引节点对象
     * @ IO异常
     */
    public static Ds128SuperInode readFromFile(String filePath)  {
        try (RandomAccessFile file = new RandomAccessFile(new File(filePath), "r"); FileChannel channel = file.getChannel()) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, SIZE);
            Ds128SuperInode inode = new Ds128SuperInode();
            inode.readFromMappedByteBuffer(buffer);
            return inode;
        }catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * 写入 long 类型数据
     *
     * @param id 数据ID (用于计算块索引)
     * @param offset 块内偏移量
     * @param value 值
     * @ IO异常
     */
    public void writeLong(long id, int offset, long value)  {
        runWithBufferLocks(() -> {
            loadBufferWithOffsetFromId(id, offset).putLong(value);
        });
    }

    /**
     * 写入 long 类型数据
     *
     * @param id 数据ID (用于计算块索引)
     * @param value 值
     * @ IO异常
     */
    public void writeLong(long id, long value)  {
        runWithBufferLocks(() -> {
            loadBufferWithOffsetFromId(id).putLong(value);
        });
    }

    /**
     * 在指定绝对位置存储 long 类型数据
     *
     * @param position 绝对字节偏移量
     * @param value 值
     * @ IO异常
     */
    protected void storeLongOffset(long position, long value)  {
        runWithBufferLocks(() -> {
            loadBufferWithOffset(position).putLong(value);
        });
    }

    /**
     * 在指定绝对位置存储 long 类型数据
     *
     * @param position 绝对字节偏移量
     * @param values
     * @ IO异常
     */
    protected void storeLongOffset(long position, long[] values)  {
        if (values == null || values.length == 0) {
            return;
        }
        runWithBufferLocks(() -> {
            int bufferIndex = bufferIndexFromPosition(position);
            ByteBuffer buf = loadBuffer(bufferIndex);
            buf.position(bufferOffsetFromPosition(position));
            int j = 0;

            int firstChunkElems = Math.min(values.length, buf.remaining() / LONG_SIZE);
            for (int i = 0; i < firstChunkElems; i++) {
                buf.putLong(values[j]);
                j++;
            }
            if (j >= values.length) {
                markDirty(bufferIndex);
                return;
            }
            markDirty(bufferIndex);

            if (buf.remaining() > 0 && buf.remaining() < LONG_SIZE) {
                throw new RuntimeException("unaligned long write at position=" + position + " remainder=" + buf.remaining());
            }

            while (j < values.length) {
                bufferIndex++;
                buf = loadBuffer(bufferIndex);
                buf.position(0);
                int chunkElems = Math.min(values.length - j, buf.remaining() / LONG_SIZE);
                for (int i = 0; i < chunkElems; i++) {
                    buf.putLong(values[j]);
                    j++;
                }
                markDirty(bufferIndex);
                if (j >= values.length) {
                    return;
                }
            }
        });
    }

    /**
     * 在指定绝对位置读取 long 类型数据
     *
     * @param position 绝对字节偏移量
     * @param values
     * @ IO异常
     */
    protected void loadLongOffset(long position, long[] values)  {
        if (values == null || values.length == 0) {
            return;
        }
        runWithBufferLocks(() -> {
            int bufferIndex = bufferIndexFromPosition(position);
            ByteBuffer buf = loadBuffer(bufferIndex);
            buf.position(bufferOffsetFromPosition(position));
            int j = 0;

            int firstChunkElems = Math.min(values.length, buf.remaining() / LONG_SIZE);
            for (int i = 0; i < firstChunkElems; i++) {
                values[j] = buf.getLong();
                j++;
            }
            if (j >= values.length) {
                return;
            }

            if (buf.remaining() > 0 && buf.remaining() < LONG_SIZE) {
                throw new RuntimeException("unaligned long read at position=" + position + " remainder=" + buf.remaining());
            }

            while (j < values.length) {
                bufferIndex++;
                buf = loadBuffer(bufferIndex);
                buf.position(0);
                int chunkElems = Math.min(values.length - j, buf.remaining() / LONG_SIZE);
                for (int i = 0; i < chunkElems; i++) {
                    values[j] = buf.getLong();
                    j++;
                }
                if (j >= values.length) {
                    return;
                }
            }
        });
    }

    /**
     * 读取 long 类型数据
     *
     * @param id 数据ID
     * @param offset 块内偏移量
     * @return 值
     * @ IO异常
     */
    public long readLong(long id, int offset)  {
         return runWithBufferLocksGet(() -> loadBufferWithOffsetFromId(id,offset).getLong());

    }

    /**
     * 读取 long 类型数据
     *
     * @param id 数据ID
     * @return 值
     * @ IO异常
     */
    public long readLong(long id)  {
        return runWithBufferLocksGet(() -> loadBufferWithOffsetFromId(id).getLong());
    }

    /**
     * 从指定绝对位置读取 long 类型数据
     *
     * @param position 绝对字节偏移量
     * @return 值
     */
    protected long loadLongOffset(long position) {
        return runWithBufferLocksGet(() -> loadBufferWithOffset(position).getLong());
    }

    /**
     * 从指定绝对位置读取 u16 类型数据
     *
     * @param position 绝对字节偏移量
     * @return 值
     * @ IO异常
     */
    protected short loadShortOffset(long position)  {
        return runWithBufferLocksGet(() -> loadBufferWithOffset(position).getShort());

    }

    /**
     * 从指定绝对位置读取 u16 类型数据
     *
     * @param postion 绝对字节偏移量
     * @return 值
     * @ IO异常
     */
    protected int loadU16ByOffset(long position)  {
        return runWithBufferLocksGet(() -> loadBufferWithOffset(position).getShort() & 0xFFFF);

    }

    /**
     * 从指定绝对位置读取 u16 类型数据
     *
     * @param position 绝对字节偏移量
     * @return 值
     * @ IO异常
     */
    protected long loadU32ByOffset(long position)  {
        return runWithBufferLocksGet(() -> loadBufferWithOffset(position).getInt() & 0xFFFFFFFFL);
    }

    /**
     * 从指定绝对位置读取 u8 类型数据
     *
     * @param position 绝对字节偏移量
     * @return 值
     * @ IO异常
     */
    protected int loadU8ByOffset(long position)  {
        return runWithBufferLocksGet(() -> loadBufferWithOffset(position).get() & 0xFF);

    }

    protected void loadBytesOffset(long position, byte[] dest, int destOffset, int length)  {
        runWithBufferLocks(() -> {
            loadBufferWithOffset(position).get( dest, destOffset, length);
        });
    }

    protected ReentrantReadWriteLock getDataBufferLock(long bufferIndex) {
        int idx = (int) ((bufferIndex ^ (bufferIndex >>> 7) ^ (bufferIndex >>> 13)) & (DATA_BUFFER_LOCK_STRIPES - 1));
        return dataBufferLocks[idx];
    }

    protected ByteBuffer loadBufferForRead(int bufferIndex){
        return loadBuffer(bufferIndex);
    }



    protected int bufferIndexFromPosition(long position){
        return (int) (position/BLOCK_SIZE);
    }

    protected int bufferOffsetFromPosition(long position){
        return (int) (position%BLOCK_SIZE);
    }

    protected int bufferIndexFromId(long id){
        return (int) ((id*dataUnitSize+headerSize)/BLOCK_SIZE);
    }

    protected int bufferPositionFromId(long id){
        return (int) (id*dataUnitSize+headerSize);
    }

    protected int bufferIndexFromId(long id,int offset){
        return (int) ((id*dataUnitSize+headerSize+offset)/BLOCK_SIZE);
    }
    protected ByteBuffer loadBufferForRead(long position){
        int bufferIndex = bufferIndexFromPosition(position);
        return loadBuffer(bufferIndex);
    }

    protected void unlockBufferForRead(int bufferIndex) {
        releaseAllBufferLocks();
    }



    protected void loadBytesOffset(long position, byte[] dest)  {
        runWithBufferLocks(() -> {
            loadBytesOffset(position, dest, 0, dest.length);
        });
    }

    /**
     * 写入 int 类型数据
     *
     * @param id 数据ID
     * @param offset 块内偏移量
     * @param value 值
     * @ IO异常
     */
    public void writeInt(long id, int offset, int value)  {
        runWithBufferLocks(() -> {
            loadBufferWithOffsetFromId(id,offset).putInt(value);
        });
    }

    /**
     * 读取 int 类型数据
     *
     * @param id 数据ID
     * @return 值
     * @ IO异常
     */
    public short readShort(long id)  {

        return readShort(id, 0);

    }

    /**
     * 读取 int 类型数据
     *
     * @param id 数据ID
     * @param offset 块内偏移量
     * @return 值
     * @ IO异常
     */
    public short readShort(long id, int offset)  {
        return runWithBufferLocksGet(() -> loadBufferWithOffsetFromId(id,offset).getShort());

    }

    /**
     * 读取 int 类型数据
     *
     * @param id 数据ID
     * @return 值
     * @ IO异常
     */
    public int readInt(long id)  {

        return readInt(id, 0);

    }

    /**
     * 读取 int 类型数据
     *
     * @param id 数据ID
     * @param offset 块内偏移量
     * @return 值
     * @ IO异常
     */
    public int readInt(long id, int offset)  {
        return runWithBufferLocksGet(() -> loadBufferWithOffsetFromId(id,offset).getInt());
    }

    /**
     * 在指定绝对位置存储 int 类型数据
     *
     * @param position 绝对字节偏移量
     * @param value 值
     * @ IO异常
     */
    protected void storeShortOffset(long position, short value)  {
        runWithBufferLocks(() -> {
            loadBufferWithOffset(position).putShort(value);
        });
    }

    /**
     * 在指定绝对位置存储 int 类型数据
     *
     * @param position 绝对字节偏移量
     * @param value 值
     * @ IO异常
     */
    protected void storeIntOffset(long position, int value)  {
        runWithBufferLocks(() -> {
            loadBufferWithOffset(position).putInt(value);
        });
    }

    /**
     * 在指定绝对位置存储 byte 类型数据
     *
     * @param position 绝对字节偏移量
     * @param value 值
     * @ IO异常
     */
    protected void storeByteOffset(long position, byte value)  {
        runWithBufferLocks(() -> {
            loadBufferWithOffset(position).put(value);
        });
    }

    /**
     * 在指定绝对位置存储 long 类型数据
     *
     * @param position 绝对字节偏移量
     * @param values
     * @ IO异常
     */
    protected void storeIntOffset(long position, int[] values)  {
        if (values == null || values.length == 0) {
            return;
        }
        runWithBufferLocks(() -> {
            int bufferIndex = bufferIndexFromPosition(position);
            ByteBuffer buf = loadBuffer(bufferIndex);
            buf.position(bufferOffsetFromPosition(position));
            int j = 0;

            int firstChunkElems = Math.min(values.length, buf.remaining() / INT_SIZE);
            for (int i = 0; i < firstChunkElems; i++) {
                buf.putInt(values[j]);
                j++;
            }
            if (j >= values.length) {
                markDirty(bufferIndex);
                return;
            }
            markDirty(bufferIndex);

            if (buf.remaining() > 0 && buf.remaining() < INT_SIZE) {
                throw new RuntimeException("unaligned int write at position=" + position + " remainder=" + buf.remaining());
            }

            while (j < values.length) {
                bufferIndex++;
                buf = loadBuffer(bufferIndex);
                buf.position(0);
                int chunkElems = Math.min(values.length - j, buf.remaining() / INT_SIZE);
                for (int i = 0; i < chunkElems; i++) {
                    buf.putInt(values[j]);
                    j++;
                }
                markDirty(bufferIndex);
                if (j >= values.length) {
                    return;
                }
            }
        });
    }

    /**
     * 在指定绝对位置读取 long 类型数据
     *
     * @param position 绝对字节偏移量
     * @param values
     * @ IO异常
     */
    protected void loadIntOffset(long position, int[] values)  {
        if (values == null || values.length == 0) {
            return;
        }
        runWithBufferLocks(() -> {
            int bufferIndex = bufferIndexFromPosition(position);
            ByteBuffer buf = loadBuffer(bufferIndex);
            buf.position(bufferOffsetFromPosition(position));
            int j = 0;

            int firstChunkElems = Math.min(values.length, buf.remaining() / INT_SIZE);
            for (int i = 0; i < firstChunkElems; i++) {
                values[j] = buf.getInt();
                j++;
            }
            if (j >= values.length) {
                return;
            }

            if (buf.remaining() > 0 && buf.remaining() < INT_SIZE) {
                throw new RuntimeException("unaligned int read at position=" + position + " remainder=" + buf.remaining());
            }

            while (j < values.length) {
                bufferIndex++;
                buf = loadBuffer(bufferIndex);
                buf.position(0);
                int chunkElems = Math.min(values.length - j, buf.remaining() / INT_SIZE);
                for (int i = 0; i < chunkElems; i++) {
                    values[j] = buf.getInt();
                    j++;
                }
                if (j >= values.length) {
                    return;
                }
            }
        });
    }

    /**
     * 从指定绝对位置读取 int 类型数据
     *
     * @param position 绝对字节偏移量
     * @return 值
     * @ IO异常
     */
    protected int loadIntOffset(long position)  {
        return runWithBufferLocksGet(() -> {
            int bufferIndex = bufferIndexFromPosition(position);
            ByteBuffer buf = loadBuffer(bufferIndex);
            return buf.getInt(bufferOffsetFromPosition(position));
        });
    }

    /**
     * 写入 short 类型数据
     * @param id
     * @param offset
     * @param value
     * 
     */
    public void writeShort(long id, int offset, short value)  {
        runWithBufferLocks(() -> {
            int bufferIndex = bufferIndexFromId(id,offset);
            ByteBuffer buf = loadBuffer(bufferIndex);
            buf.putShort(bufferPositionFromId(id), value);
        });
    }

    /**
     * 写入 float 类型数据
     * @param id
     * @param offset
     * @param value
     */
    public void writeFloat(long id, int offset, float value)  {
        runWithBufferLocks(() -> {
            int bufferIndex = bufferIndexFromId(id,offset);
            ByteBuffer buf = loadBuffer(bufferIndex);
            buf.putFloat(bufferPositionFromId(id), value);
        });
    }

    /**
     * 读取 float 类型数据
     *
     * @param id
     * @param offset
     * @return
     * 
     */
    public float readFloat(long id, int offset)  {
        return runWithBufferLocksGet(() -> {
            int bufferIndex = bufferIndexFromId(id,offset);
            ByteBuffer buf = loadBuffer(bufferIndex);
            return buf.getFloat(bufferPositionFromId(id));
        });
    }

    /**
     * 写入 double 类型数据
     *
     * @param id
     * @param offset
     * @param value
     * 
     */
    public void writeDouble(long id, int offset, double value)  {
        runWithBufferLocks(() -> {
            int bufferIndex = bufferIndexFromId(id,offset);
            ByteBuffer buf = loadBuffer(bufferIndex);
            buf.putDouble(bufferPositionFromId(id), value);
        });
    }

    /**
     * 读取 double 类型数据
     *
     * @param id
     * @param offset
     * @return
     * 
     */
    public double readDouble(long id, int offset) {
        return runWithBufferLocksGet(() -> {
            int bufferIndex = bufferIndexFromId(id,offset);
            ByteBuffer buf = loadBuffer(bufferIndex);
            return buf.getDouble(bufferPositionFromId(id));
        });

    }

    /**
     * 写入 byte 类型数据
     *
     * @param id
     * @param offset
     * @param value
     * 
     */
    public void writeByte(long id, int offset, byte value)  {
        runWithBufferLocks(() -> {
            int bufferIndex = bufferIndexFromId(id,offset);
            ByteBuffer buf = loadBuffer(bufferIndex);
            buf.put(bufferPositionFromId(id), value);
        });
    }

    /**
     * 读取 byte 类型数据
     *
     * @param id
     * @param offset
     * @return
     * 
     */
    public byte readByte(long id, int offset)  {
        return runWithBufferLocksGet(() -> {
            int bufferIndex = bufferIndexFromId(id,offset);
            ByteBuffer buf = loadBuffer(bufferIndex);
            return buf.get(bufferPositionFromId(id));
        });
    }

    /**
     * 读取字节数组
     *
     * @param id
     * @return
     * 
     * @throws DsDataReadingLessThanException 如果读取超出块边界
     */
    public byte[] readUnitWithId(long id)  {
        
        byte[] out = new byte[dataUnitSize];
        readBytes(id, 0, out);
        return out;
    }

    /**
     * 读取字节数组
     *
     * @param id
     * @param offset
     * @param out
     * 
     * @throws DsDataReadingLessThanException 如果读取超出块边界
     */
    public void readBytes(long id, int offset, byte[] out)  {
        readBytes(id, offset, out, 0, out.length);

    }

    /**
     * 读取数据到字节数组的部分位置
     *
     * @param id
     * @param offset
     * @param out
     * @param offsetOut
     * @param count
     * 
     */
    public void readBytes(long id, int offset, byte[] out, int offsetOut, int count)  {
        try {
            long position = (long) id * dataUnitSize + headerSize + offset;
            int bufferIndex = bufferIndexFromPosition(position);
            ByteBuffer buf = loadBuffer(bufferIndex);
            int bufPos = bufferOffsetFromPosition(position);
            int remaining = count;
            int curOut = offsetOut;

            int firstChunk = Math.min(remaining, BLOCK_SIZE - bufPos);
            buf.position(bufPos);
            buf.get(out, curOut, firstChunk);
            remaining -= firstChunk;
            curOut += firstChunk;
            if (remaining <= 0) {
                return;
            }

            while (remaining > 0) {
                bufferIndex++;
                buf = loadBuffer(bufferIndex);
                buf.position(0);
                int chunk = Math.min(remaining, BLOCK_SIZE);
                buf.get(out, curOut, chunk);
                remaining -= chunk;
                curOut += chunk;
            }
        } finally {
            releaseAllBufferLocks();
        }
    }

    /**
     * 写入字节数组的部分内容
     *
     * @param id
     * @param value
     * 
     */
    public void writeBytesWithId(long id, byte[] value)  {
        writeBytes(id, 0, value);
    }

    /**
     * 初始化数据块的零字节
     *
     * @param id
     * 
     */
    public void clearWithId(long id)  {
        writeBytes(id, 0, zero_block_unit);
    }

    /**
     * 写入字节数组
     *
     * @param id
     * @param offset
     * @param value
     * 
     * @throws DsDataOverFlowException 如果写入超出块边界
     */
    public void writeBytes(long id, int offset, byte[] value)  {
        writeBytes(id, offset, value, 0, value.length);
    }

    /**
     * 写入字节数组的部分内容
     *
     * @param id
     * @param offset
     * @param value
     * @param offsetIn
     * @param count
     * 
     */
    public void writeBytes(long id, int offset, byte[] value, int offsetIn, int count)  {
        try {
            long position = (long) id * dataUnitSize + headerSize + offset;
            int bufferIndex = bufferIndexFromPosition(position);
            ByteBuffer buf = loadBuffer(bufferIndex);
            int bufPos = bufferOffsetFromPosition(position);
            int remaining = count;
            int curIn = offsetIn;

            int firstChunk = Math.min(remaining, BLOCK_SIZE - bufPos);
            buf.position(bufPos);
            buf.put(value, curIn, firstChunk);
            markDirty(bufferIndex);
            remaining -= firstChunk;
            curIn += firstChunk;
            if (remaining <= 0) {
                return;
            }

            while (remaining > 0) {
                bufferIndex++;
                buf = loadBuffer(bufferIndex);
                buf.position(0);
                int chunk = Math.min(remaining, BLOCK_SIZE);
                buf.put(value, curIn, chunk);
                markDirty(bufferIndex);
                remaining -= chunk;
                curIn += chunk;
            }
        } finally {
            releaseAllBufferLocks();
        }
    }

    /**
     * 写入 UTF-8 字符串
     *
     * @param id
     * @param offset
     * @param value
     * 
     */
    public void writeUtf8(long id, int offset, String value)  {
        byte[] data = value.getBytes(UTF_8);
         writeBytes(id, offset, data, 0, data.length);
    }

    /**
     * 写入整个数据单元
     *
     * @param id
     * @param value
     * @param offsetIn
     * 
     */
    public void writeUnit(long id, byte[] value, int offsetIn)  {
        writeBytes(id, 0, value, offsetIn, dataUnitSize);
    }

    /**
     * 读取整个数据单元
     *
     * @param id
     * @param out
     * @param offsetOut
     * 
     */
    public void readUnit(long id, byte[] out, int offsetOut)  {
        
        readBytes(id, 0, out, offsetOut, dataUnitSize);

    }

    /**
     * 加载指定索引的块(buffer)到内存。 如果文件不够大，会自动扩展文件大小。
     * 当缓存命中上限时，会按 LRU 顺序逐出最近最少使用的块（逐出前写回磁盘）。
     *
     * @param bufferIndex 块索引 (0-based)
     * @return 映射的 ByteBuffer（位置未设置，使用方需自行 position）
     */
    private static final ThreadLocal<java.util.ArrayDeque<ReentrantReadWriteLock.ReadLock>> TL_READ_HELD = ThreadLocal.withInitial(java.util.ArrayDeque::new);
    private static final ThreadLocal<java.util.ArrayDeque<Object[]>> TL_SNAP_HELD = ThreadLocal.withInitial(java.util.ArrayDeque::new);
    private static final ThreadLocal<java.util.ArrayDeque<ByteBuffer>> TL_BUF_HELD = ThreadLocal.withInitial(java.util.ArrayDeque::new);
    private static final ThreadLocal<java.util.ArrayDeque<Integer>> TL_IDX_HELD = ThreadLocal.withInitial(java.util.ArrayDeque::new);

    private static final void pushReadHold(ReentrantReadWriteLock stripe, Object[] snap, ByteBuffer buf, int idx) {
        ReentrantReadWriteLock.ReadLock rl = stripe.readLock();
        TL_READ_HELD.get().addLast(rl);
        TL_SNAP_HELD.get().addLast(snap);
        TL_BUF_HELD.get().addLast(buf);
        TL_IDX_HELD.get().addLast(idx);
    }

    static final void releaseAllBufferLocks() {
        java.util.ArrayDeque<ReentrantReadWriteLock.ReadLock> rs = TL_READ_HELD.get();
        while (!rs.isEmpty()) rs.pollLast().unlock();
        TL_SNAP_HELD.get().clear();
        TL_BUF_HELD.get().clear();
        TL_IDX_HELD.get().clear();
    }

    protected ByteBuffer loadBuffer(long bufferIndex)  {
        int idx = (int) bufferIndex;
        Object[] bs = bufSnap;
        if (idx >= 0 && idx < bs.length) {
            Object o = bs[idx];
            if (o != null) {
                ByteBuffer buf = (ByteBuffer) o;
                ReentrantReadWriteLock stripe = getDataBufferLock((long) idx);
                boolean hit = false;
                stripe.readLock().lock();
                try {
                    Object[] curBs = bufSnap;
                    if (curBs == bs || (idx < curBs.length && curBs[idx] == buf)) {
                        touchBuffer(idx);
                        pushReadHold(stripe, curBs, buf, idx);
                        hit = true;
                        return buf.duplicate();
                    }
                } finally {
                    if (!hit) {
                        stripe.readLock().unlock();
                    }
                }
            }
        }
        bufferLock.lock();
        try {
            ByteBuffer buf = null;
            boolean allocatedNew = false;
            if (idx < bufSnap.length) {
                buf = (ByteBuffer) bufSnap[idx];
            }
            if (buf == null) {
                ensureCapacity(1, idx);
                byte[] data;
                if (idx < byteSnap.length) {
                    data = (byte[]) byteSnap[idx];
                    if (data == null) {
                        data = readBlockFromFile(idx);
                        applyByteSnap(idx, data);
                        buf = ByteBuffer.wrap(data);
                        applyBufSnap(idx, buf);
                        allocatedNew = true;
                    } else {
                        if (idx < bufSnap.length) {
                            buf = (ByteBuffer) bufSnap[idx];
                        }
                        if (buf == null) {
                            buf = ByteBuffer.wrap(data);
                            applyBufSnap(idx, buf);
                            allocatedNew = true;
                        }
                    }
                } else {
                    growSnapTo(idx + 1);
                    data = readBlockFromFile(idx);
                    applyByteSnap(idx, data);
                    buf = ByteBuffer.wrap(data);
                    applyBufSnap(idx, buf);
                    allocatedNew = true;
                }
                if (allocatedNew) {
                    activeCachedBlocks.incrementAndGet();
                    cachedBytes.addAndGet(BLOCK_SIZE);
                    if (idx > highestBufferIndexEverSeen) {
                        highestBufferIndexEverSeen = idx;
                    }
                }
            }
            touchBuffer(idx);
            ReentrantReadWriteLock stripe = getDataBufferLock((long) idx);
            stripe.readLock().lock();
            pushReadHold(stripe, bufSnap, buf, idx);
            return buf.duplicate();
        } finally {
            bufferLock.unlock();
        }
    }

    private final void growSnapTo(int minCap) {
        int bLen = bufSnap.length;
        if (minCap <= bLen && minCap <= byteSnap.length) return;
        int newCap = Math.max(minCap, bLen < 16 ? 16 : (bLen * 3) / 2 + 1);
        Object[] nb = new Object[newCap];
        Object[] ny = new Object[newCap];
        Object[] ob = bufSnap;
        Object[] oy = byteSnap;
        int cpb = Math.min(ob.length, newCap);
        int cpy = Math.min(oy.length, newCap);
        System.arraycopy(ob, 0, nb, 0, cpb);
        System.arraycopy(oy, 0, ny, 0, cpy);
        while (dataBuffers.size() < newCap) dataBuffers.add(null);
        while (dataBytes.size() < newCap) dataBytes.add(null);
        for (int i = 0; i < cpb; i++) if (ob[i] != null) dataBuffers.set(i, (ByteBuffer) ob[i]);
        for (int i = 0; i < cpy; i++) if (oy[i] != null) dataBytes.set(i, (byte[]) oy[i]);
        this.bufSnap = nb;
        this.byteSnap = ny;
    }

    private final void ensureListCapAtLeast(List<?> list, int minCap) {
        while (list.size() < minCap) list.add(null);
    }

    private final void applyBufSnap(int idx, ByteBuffer v) {
        growSnapTo(idx + 1);
        ensureListCapAtLeast(dataBuffers, idx + 1);
        Object[] nb = bufSnap.clone();
        nb[idx] = v;
        bufSnap = nb;
        dataBuffers.set(idx, v);
    }

    private final void applyByteSnap(int idx, byte[] v) {
        growSnapTo(idx + 1);
        ensureListCapAtLeast(dataBytes, idx + 1);
        Object[] ny = byteSnap.clone();
        ny[idx] = v;
        byteSnap = ny;
        dataBytes.set(idx, v);
    }

    protected final void touchBuffer(int bufferIndex) {
        bufferLastAccessNanos.put(bufferIndex, System.nanoTime());
    }

    protected final void markDirty(int bufferIndex) {
        dirtyBufferIndices.add(bufferIndex);
        if (bufferIndex == 0) {
            try {
                ensureHeaderTierAttached();
                if (headerTier != null) {
                    headerTier.markFullDirty();
                }
            } catch (Throwable ignore) {
            }
        }
    }

    public void setMaxCachedBlocks(int maxBlocks) {
        if (maxBlocks <= 0) {
            throw new IllegalArgumentException("maxBlocks must be > 0");
        }
        this.maxCachedBlocks = maxBlocks;
        this.maxCachedBytes = (long) maxBlocks * (long) BLOCK_SIZE;
        trimCachedBuffers();
    }

    public int getMaxCachedBlocks() {
        return maxCachedBlocks;
    }

    public long getMaxCachedBytes() {
        return maxCachedBytes;
    }

    public int getActiveCachedBlocks() {
        return activeCachedBlocks.get();
    }

    public long getCachedBytes() {
        return cachedBytes.get();
    }

    public CacheStats getCacheStats() {
        return new CacheStats(
            maxCachedBlocks, maxCachedBytes,
            activeCachedBlocks.get(), cachedBytes.get(),
            dirtyBufferIndices.size(), highestBufferIndexEverSeen,
            evictionAttempts.get(), evictionSuccess.get(),
            evictionBytes.get(), evictionDirtyCount.get()
        );
    }

    public CacheStats getAndResetCacheStats() {
        return new CacheStats(
            maxCachedBlocks, maxCachedBytes,
            activeCachedBlocks.get(), cachedBytes.get(),
            dirtyBufferIndices.size(), highestBufferIndexEverSeen,
            evictionAttempts.getAndSet(0), evictionSuccess.getAndSet(0),
            evictionBytes.getAndSet(0), evictionDirtyCount.getAndSet(0)
        );
    }

    public void resetCacheStats() {
        evictionAttempts.set(0);
        evictionSuccess.set(0);
        evictionBytes.set(0);
        evictionDirtyCount.set(0);
    }

    public void trimCachedBuffers() {
        ensureCapacity(0, -1);
    }

    protected void ensureCapacity(int needBlocks, int avoidBufferIndex) {
        int max = maxCachedBlocks;
        if (max <= 0) {
            return;
        }
        evictionLock.lock();
        try {
            for (;;) {
                int cur = activeCachedBlocks.get();
                if (cur + needBlocks <= max) {
                    return;
                }
                if (!evictOne(avoidBufferIndex)) {
                    return;
                }
            }
        } finally {
            evictionLock.unlock();
        }
    }

    private boolean evictOne(int avoidBufferIndex) {
        evictionAttempts.incrementAndGet();
        int[] candIdxs = evictCandIdxs;
        long[] candAccess = evictCandAccess;
        int slot = EVICT_CANDIDATE_SLOT;

        Object[] keys = bufferLastAccessNanos.keySet().toArray();
        int total = keys.length;
        if (total == 0) {
            return false;
        }
        int sampleMax = Math.min(slot, total);

        int n = 0;
        if (total <= slot) {
            for (int i = 0; i < total; i++) {
                Object k = keys[i];
                if (k == null) continue;
                int idx = ((Integer) k).intValue();
                if (idx == avoidBufferIndex) continue;
                Long v = bufferLastAccessNanos.get(k);
                if (v == null) continue;
                long access = v.longValue();
                int j = n;
                while (j > 0 && candAccess[j - 1] > access) {
                    candIdxs[j] = candIdxs[j - 1];
                    candAccess[j] = candAccess[j - 1];
                    j--;
                }
                candIdxs[j] = idx;
                candAccess[j] = access;
                n++;
            }
        } else {
            int picked = 0;
            long rngState = (long) System.nanoTime() ^ ((long) avoidBufferIndex << 32L);
            boolean[] used = null;
            if (total <= slot * 8) {
                used = new boolean[total];
            }
            int tries = 0;
            int maxTries = sampleMax * 4;
            while (picked < sampleMax && tries < maxTries) {
                tries++;
                rngState ^= (rngState << 21);
                rngState ^= (rngState >>> 35);
                rngState ^= (rngState << 4);
                int pos = (int) ((rngState & Long.MAX_VALUE) % total);
                if (used != null) {
                    if (used[pos]) continue;
                    used[pos] = true;
                }
                Object k = keys[pos];
                if (k == null) continue;
                int idx = ((Integer) k).intValue();
                if (idx == avoidBufferIndex) continue;
                Long v = bufferLastAccessNanos.get(k);
                if (v == null) continue;
                long access = v.longValue();
                boolean dup = false;
                for (int kk = 0; kk < picked; kk++) if (candIdxs[kk] == idx) { dup = true; break; }
                if (dup) continue;
                int j = n;
                while (j > 0 && candAccess[j - 1] > access) {
                    candIdxs[j] = candIdxs[j - 1];
                    candAccess[j] = candAccess[j - 1];
                    j--;
                }
                candIdxs[j] = idx;
                candAccess[j] = access;
                n++;
                picked++;
            }
            if (n == 0) {
                for (int i = 0; i < total; i++) {
                    Object k = keys[i];
                    if (k == null) continue;
                    int idx = ((Integer) k).intValue();
                    if (idx == avoidBufferIndex) continue;
                    Long v = bufferLastAccessNanos.get(k);
                    if (v == null) continue;
                    long access = v.longValue();
                    candIdxs[0] = idx;
                    candAccess[0] = access;
                    n = 1;
                    break;
                }
            }
        }

        if (n == 0) return false;

        for (int i = 0; i < n; i++) {
            int victim = candIdxs[i];
            ReentrantReadWriteLock stripe = getDataBufferLock((long) victim);
            stripe.writeLock().lock();
            try {
                bufferLock.lock();
                try {
                    if (victim >= bufSnap.length) {
                        bufferLastAccessNanos.remove(victim);
                        continue;
                    }
                    byte[] victimBytes = (byte[]) byteSnap[victim];
                    ByteBuffer victimBuf = (ByteBuffer) bufSnap[victim];
                    if (victimBytes == null && victimBuf == null) {
                        bufferLastAccessNanos.remove(victim);
                        continue;
                    }
                    boolean wasDirty = dirtyBufferIndices.remove(victim);
                    if (wasDirty) {
                        evictionDirtyCount.incrementAndGet();
                    }
                    if (victimBytes != null) {
                        writeBlockToFile(victim, victimBytes);
                    }
                    Object[] nb = bufSnap.clone();
                    nb[victim] = null;
                    bufSnap = nb;
                    Object[] ny = byteSnap.clone();
                    ny[victim] = null;
                    byteSnap = ny;
                    dataBuffers.set(victim, null);
                    dataBytes.set(victim, null);
                    bufferLastAccessNanos.remove(victim);
                    activeCachedBlocks.decrementAndGet();
                    cachedBytes.addAndGet(-BLOCK_SIZE);
                    evictionSuccess.incrementAndGet();
                    evictionBytes.addAndGet(BLOCK_SIZE);
                    return true;
                } finally {
                    bufferLock.unlock();
                }
            } finally {
                stripe.writeLock().unlock();
            }
        }
        return false;
    }

    protected byte[] readBlockFromFile(int bufferIndex) {
        byte[] out = new byte[BLOCK_SIZE];
        if (dataFile == null || !dataFile.exists()) {
            return out;
        }
        long start = (long) bufferIndex * (long) BLOCK_SIZE;
        long len;
        syncOpLock.lock();
        try (RandomAccessFile raf = new RandomAccessFile(dataFile, "r")) {
            len = raf.length();
            if (start >= len) {
                return out;
            }
            raf.seek(start);
            int remaining = (int) Math.min((long) BLOCK_SIZE, len - start);
            if (remaining > 0) {
                raf.readFully(out, 0, remaining);
            }
            return out;
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read block index=" + bufferIndex
                + " from " + dataFile, ex);
        } finally {
            syncOpLock.unlock();
        }
    }

    protected void writeBlockToFile(int bufferIndex, byte[] block) {
        if (dataFile == null) {
            return;
        }
        long start = (long) bufferIndex * (long) BLOCK_SIZE;
        ensureParentDirectory(dataFile);
        syncOpLock.lock();
        try (RandomAccessFile raf = new RandomAccessFile(dataFile, "rw")) {
            long needed = start + BLOCK_SIZE;
            if (raf.length() < needed) {
                raf.setLength(needed);
            }
            raf.seek(start);
            raf.write(block, 0, BLOCK_SIZE);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to write block index=" + bufferIndex
                + " to " + dataFile, ex);
        } finally {
            syncOpLock.unlock();
        }
    }
    
    protected ByteBuffer loadBufferWithOffset(long position)  {
        ByteBuffer buf = loadBuffer(position/BLOCK_SIZE);
        buf.position((int) (position%BLOCK_SIZE));
        return buf;
    }
    
    protected ByteBuffer loadBufferWithOffsetFromId(long id)  {
        ByteBuffer buf = loadBuffer((id*dataUnitSize+headerSize)/BLOCK_SIZE);
        buf.position((int) ((id*dataUnitSize+headerSize)%BLOCK_SIZE));
        return buf;
    }
    
    protected ByteBuffer loadBufferWithOffsetFromId(long id,int offset)  {
        ByteBuffer buf = loadBuffer((id*dataUnitSize+headerSize+offset)/BLOCK_SIZE);
        buf.position((int) ((id*dataUnitSize+headerSize+offset)%BLOCK_SIZE));
        return buf;
    }

    protected final void runWithBufferLocks(Runnable r) {
        try { r.run(); } finally { releaseAllBufferLocks(); }
    }

    protected final <T> T runWithBufferLocksGet(java.util.function.Supplier<T> s) {
        try { return s.get(); } finally { releaseAllBufferLocks(); }
    }

    /**
     * 加载缓冲区用于更新，并加锁。
     *
     * @param bufferIndex 缓冲区索引
     * @return 映射的缓冲区
     */
    protected ByteBuffer loadBufferForUpdate(long bufferIndex) {
        ByteBuffer buf = loadBuffer(bufferIndex);
        markDirty((int) bufferIndex);
        return buf;
    }

    /**
     * 释放更新锁，并将缓冲区标记为脏（需要同步）。
     *
     * @param bufferIndex 缓冲区索引
     */
    protected void unlockBufferForUpdate(int bufferIndex) {
        releaseAllBufferLocks();
    }

    /**
     * 释放缓冲区锁（不标记为脏）。
     *
     * @param bufferIndex 缓冲区索引
     */
    protected void unlockBuffer(long bufferIndex) {
        releaseAllBufferLocks();
    }
    
     /**
     * 将内存所有数据同步到底层存储。
     * 确保数据的持久性。
     */
    public void syncStore(){
        if (dataFile == null) {
            throw new IllegalStateException("syncStore requires a non-null data file");
        }
        syncOpLock.lock();
        try {
            if (wal != null && !dirtyBufferIndices.isEmpty()) {
                for (Integer idx : dirtyBufferIndices) {
                    if (idx != null && idx >= 0 && idx < dataBytes.size() && dataBytes.get(idx) != null) {
                        try {
                            wal.appendBlockEntry(idx, dataBytes.get(idx));
                        } catch (IOException ioe) {
                            throw new RuntimeException("Failed to append WAL for block index=" + idx, ioe);
                        }
                    }
                }
                try {
                    wal.fsync();
                } catch (IOException ioe) {
                    throw new RuntimeException("Failed to fsync WAL before data write", ioe);
                }
            }
            long totalBytes = syncByteSize();
            ensureParentDirectory(dataFile);
            try (RandomAccessFile raf = new RandomAccessFile(dataFile, "rw")) {
                raf.setLength(Math.max(raf.length(), totalBytes));
                int required = requiredBlockCount(totalBytes);
                byte[] tempRead = null;
                for (int i = 0; i < required; i++) {
                    byte[] block;
                    if (i < dataBytes.size()) {
                        block = dataBytes.get(i);
                    } else {
                        block = null;
                    }
                    if (block != null) {
                        raf.seek((long) i * BLOCK_SIZE);
                        raf.write(block, 0, BLOCK_SIZE);
                        continue;
                    }
                    if (tempRead == null) {
                        tempRead = new byte[BLOCK_SIZE];
                    } else {
                        Arrays.fill(tempRead, (byte) 0);
                    }
                    long filePos = (long) i * BLOCK_SIZE;
                    long flen = raf.length();
                    if (filePos + BLOCK_SIZE <= flen) {
                        raf.seek(filePos);
                        raf.readFully(tempRead, 0, BLOCK_SIZE);
                        raf.seek(filePos);
                        raf.write(tempRead, 0, BLOCK_SIZE);
                    } else if (filePos < flen) {
                        int rest = (int) (flen - filePos);
                        raf.seek(filePos);
                        raf.readFully(tempRead, 0, rest);
                        raf.seek(filePos);
                        raf.write(tempRead, 0, BLOCK_SIZE);
                    } else {
                        raf.seek(filePos);
                        raf.write(ZERO_BLOCK_BYTES, 0, BLOCK_SIZE);
                    }
                }
                dirtyBufferIndices.clear();
                if (wal != null) {
                    try {
                        wal.truncate();
                    } catch (IOException ioe) {
                        throw new RuntimeException("Failed to truncate WAL after successful sync", ioe);
                    }
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        } finally {
            syncOpLock.unlock();
        }
    }
    
    /**
     * 从底层存储加载数据到内存
     * 确保数据的持久性。
     */
    public void syncLoad(){
        if (dataFile == null) {
            throw new IllegalStateException("syncLoad requires a non-null data file");
        }
        syncOpLock.lock();
        try {
            bufferLock.lock();
            try {
                int startCap = Math.max(8192, requiredBlockCount(dataFile == null || !dataFile.exists() ? 0 : dataFile.length()) + 1);
                Object[] nb = new Object[startCap];
                Object[] ny = new Object[startCap];
                List<ByteBuffer> lb = new java.util.concurrent.CopyOnWriteArrayList<>();
                List<byte[]> ly = new java.util.concurrent.CopyOnWriteArrayList<>();
                while (lb.size() < startCap) lb.add(null);
                while (ly.size() < startCap) ly.add(null);
                this.bufSnap = nb;
                this.byteSnap = ny;
                this.dataBuffers = lb;
                this.dataBytes = ly;
                bufferLastAccessNanos.clear();
                dirtyBufferIndices.clear();
                activeCachedBlocks.set(0);
                cachedBytes.set(0);
                evictionAttempts.set(0);
                evictionSuccess.set(0);
                evictionBytes.set(0);
                evictionDirtyCount.set(0);
                highestBufferIndexEverSeen = -1;
                if (dataFile.exists()) {
                    long fileLength = dataFile.length();
                    if (fileLength > 0) {
                        highestBufferIndexEverSeen = requiredBlockCount(fileLength) - 1;
                    }
                }
                if (wal != null) {
                    try {
                        wal.replayAll(dataBytes, this);
                    } catch (IOException ioe) {
                        throw new RuntimeException("Failed to replay WAL during syncLoad", ioe);
                    }
                }
                while (dataBuffers.size() < dataBytes.size()) {
                    dataBuffers.add(null);
                }
                Object[] fb = new Object[dataBuffers.size()];
                Object[] fy = new Object[dataBytes.size()];
                for (int i = 0; i < dataBuffers.size(); i++) fb[i] = dataBuffers.get(i);
                for (int i = 0; i < dataBytes.size(); i++) fy[i] = dataBytes.get(i);
                this.bufSnap = fb;
                this.byteSnap = fy;
            } finally {
                bufferLock.unlock();
            }
        } finally {
            syncOpLock.unlock();
        }
    }

    public void sync() {
        syncStore();
    }

    protected long syncByteSize() {
        return (long) (highestBufferIndexEverSeen + 1) * (long) BLOCK_SIZE;
    }

    protected int requiredBlockCount(long totalBytes) {
        if (totalBytes <= 0) {
            return 0;
        }
        return (int) ((totalBytes + BLOCK_SIZE - 1) / BLOCK_SIZE);
    }

    protected void ensureParentDirectory(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    public void forceFlushAllWALAndSync() {
        syncOpLock.lock();
        try {
            if (wal != null && wal.isOpen()) {
                try {
                    wal.fsync();
                } catch (IOException ioe) {
                    throw new RuntimeException(ioe);
                }
            }
            syncStore();
        } finally {
            syncOpLock.unlock();
        }
    }

    public int replayWALFromScratch() {
        syncOpLock.lock();
        try {
            bufferLock.lock();
            try {
                if (wal == null) return 0;
                try {
                    return wal.replayAll(dataBytes, this);
                } catch (IOException ioe) {
                    throw new RuntimeException(ioe);
                }
            } finally {
                bufferLock.unlock();
            }
        } finally {
            syncOpLock.unlock();
        }
    }

    public void truncateWALNow() {
        syncOpLock.lock();
        try {
            if (wal != null && wal.isOpen()) {
                try {
                    wal.truncate();
                } catch (IOException ioe) {
                    throw new RuntimeException(ioe);
                }
            }
        } finally {
            syncOpLock.unlock();
        }
    }

    public static void forceResetWALForTest(File dataFile) {
        DsWAL.forceResetForTest(dataFile);
    }

}
