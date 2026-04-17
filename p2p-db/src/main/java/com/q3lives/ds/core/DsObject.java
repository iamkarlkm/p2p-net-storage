package com.q3lives.ds.core;

import static com.q3lives.ds.core.DsMemory.BLOCK_SIZE;
import com.q3lives.ds.fs.Ds128SuperInode;
import jdk.internal.access.foreign.UnmapperProxy;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
public class DsObject {

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
    /**
     * 用于初始化新块的零字节数组
     */
    protected static final byte[] ZERO_BLOCK_BYTES = new byte[BLOCK_SIZE];

    // TODO: 实现缓存淘汰策略 (如 LRU)，防止大文件加载导致内存溢出 (OOM)。
    // 当前实现中，所有加载的缓冲区都会保留在内存中，直到显式卸载。
    /**
     * 数据缓冲区缓存池：Key为块索引(bufferIndex)，Value为映射的内存缓冲区
     */
    protected Map<Long, MappedByteBuffer> datatBuffers = new ConcurrentHashMap<>();

    /**
     * 脏缓冲区集合：记录所有被修改但尚未同步到磁盘的缓冲区索引
     */
    protected Set<Long> dirtyBuffers = ConcurrentHashMap.newKeySet();

    // TODO: 考虑统一 datatBuffers 和 frameBuffers 的管理逻辑。
    /**
     * 帧缓冲区缓存池：用于存储非标准块大小的数据帧
     */
    protected Map<Long, MappedByteBuffer> frameBuffers = new ConcurrentHashMap<>();

    /**
     * 脏帧缓冲区集合
     */
    protected Set<Long> dirtyFrameBuffers = ConcurrentHashMap.newKeySet();

    protected volatile long maxMappedBytes = 512L * 1024L * 1024L;
    protected final AtomicLong mappedBytes = new AtomicLong(0);
    protected final ReentrantLock evictionLock = new ReentrantLock();
    protected volatile boolean evictPreferClean = false;
    protected volatile ScheduledExecutorService flushExecutor;
    protected volatile long flushIntervalMs = 1000L;
    protected volatile int flushMaxPerCycle = 64;
    protected volatile boolean adaptiveFlushEnabled = false;
    protected volatile long adaptiveMinIntervalMs = 200L;
    protected volatile long adaptiveMaxIntervalMs = 5000L;
    protected final AtomicLong adaptiveNextFlushNanos = new AtomicLong(0);
    protected final AtomicLong evictionAttempts = new AtomicLong(0);
    protected final AtomicLong evictionSuccess = new AtomicLong(0);
    protected final AtomicLong evictionBytes = new AtomicLong(0);
    protected final AtomicLong evictionDirtyCount = new AtomicLong(0);
    protected final AtomicLong flushCycles = new AtomicLong(0);
    protected final AtomicLong flushItems = new AtomicLong(0);
    protected final AtomicLong flushBytes = new AtomicLong(0);
    protected final AtomicLong flushNanos = new AtomicLong(0);

    protected static final byte BUF_KIND_DATA = 1;
    protected static final byte BUF_KIND_FRAME = 2;
    protected final int headerSize;

    protected static final class BufferMeta {

        final long key;
        final int bytes;
        final byte kind;
        volatile long lastAccessNanos;

        BufferMeta(long key, int bytes, byte kind, long lastAccessNanos) {
            this.key = key;
            this.bytes = bytes;
            this.kind = kind;
            this.lastAccessNanos = lastAccessNanos;
        }
    }

    protected final Map<Long, BufferMeta> dataMetas = new ConcurrentHashMap<>();
    protected final Map<Long, BufferMeta> frameMetas = new ConcurrentHashMap<>();

    public static final class BufferStats {

        private final long maxMappedBytes;
        private final long mappedBytes;
        private final int dataBuffers;
        private final int frameBuffers;
        private final int dirtyDataBuffers;
        private final int dirtyFrames;
        private final long evictionAttempts;
        private final long evictionSuccess;
        private final long evictionBytes;
        private final long evictionDirtyCount;
        private final long flushCycles;
        private final long flushItems;
        private final long flushBytes;
        private final long flushNanos;

        public BufferStats(long maxMappedBytes, long mappedBytes, int dataBuffers, int frameBuffers, int dirtyDataBuffers, int dirtyFrames,
            long evictionAttempts, long evictionSuccess, long evictionBytes, long evictionDirtyCount,
            long flushCycles, long flushItems, long flushBytes, long flushNanos) {
            this.maxMappedBytes = maxMappedBytes;
            this.mappedBytes = mappedBytes;
            this.dataBuffers = dataBuffers;
            this.frameBuffers = frameBuffers;
            this.dirtyDataBuffers = dirtyDataBuffers;
            this.dirtyFrames = dirtyFrames;
            this.evictionAttempts = evictionAttempts;
            this.evictionSuccess = evictionSuccess;
            this.evictionBytes = evictionBytes;
            this.evictionDirtyCount = evictionDirtyCount;
            this.flushCycles = flushCycles;
            this.flushItems = flushItems;
            this.flushBytes = flushBytes;
            this.flushNanos = flushNanos;
        }

        public long getMaxMappedBytes() {
            return maxMappedBytes;
        }

        public long getMappedBytes() {
            return mappedBytes;
        }

        public int getDataBuffers() {
            return dataBuffers;
        }

        public int getFrameBuffers() {
            return frameBuffers;
        }

        public int getDirtyDataBuffers() {
            return dirtyDataBuffers;
        }

        public int getDirtyFrames() {
            return dirtyFrames;
        }

        public long getEvictionAttempts() {
            return evictionAttempts;
        }

        public long getEvictionSuccess() {
            return evictionSuccess;
        }

        public long getEvictionBytes() {
            return evictionBytes;
        }

        public long getEvictionDirtyCount() {
            return evictionDirtyCount;
        }

        public long getFlushCycles() {
            return flushCycles;
        }

        public long getFlushItems() {
            return flushItems;
        }

        public long getFlushBytes() {
            return flushBytes;
        }

        public long getFlushNanos() {
            return flushNanos;
        }
    }

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
     * 底层数据文件
     */
    protected File dataFile;

    protected MappedByteBuffer headerBuffer;

    /**
     * 头信息操作锁：用于保护文件头部的并发修改
     */
    protected ReentrantReadWriteLock headerOpLockRW = new ReentrantReadWriteLock();

    protected ReentrantReadWriteLock.ReadLock headerOpLockRead = headerOpLockRW.readLock();
    protected ReentrantReadWriteLock.WriteLock headerOpLockWrite = headerOpLockRW.writeLock();
    
    /**
     * 全局读写锁
     */
    protected final ReentrantReadWriteLock globalLock;
    protected final ReentrantReadWriteLock.ReadLock readLock;
    protected final ReentrantReadWriteLock.WriteLock writeLock;

   


    /**
     * 固定长度。数据单元大小
     */
    public int dataUnitSize;

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

    /**
     * 构造函数
     *
     * @param dataFile 数据文件对象
     * @param dataUnitSize 数据单元大小（字节）
     */
    public DsObject(File dataFile, int dataUnitSize) {
        this(dataFile, 0, dataUnitSize);
    }

    public DsObject(File dataFile, int headerSize, int dataUnitSize) {
        this.dataFile = dataFile;
        this.dataUnitSize = dataUnitSize;
        this.headerSize = headerSize;
        zero_block_unit = new byte[dataUnitSize];
        this.globalLock = new ReentrantReadWriteLock();
        this.readLock = globalLock.readLock();
        this.writeLock = globalLock.writeLock();
        for (int i = 0; i < DATA_BUFFER_LOCK_STRIPES; i++) {
            dataBufferLocks[i] = new ReentrantReadWriteLock();
        }
        // 初始化锁池
        for (int i = 0; i < DEFAULT_LOCK_POOL_SIZE; i++) {
//        for(int i=0;i<5000;i++){
            idLockPool.add(new ReentrantLock());
        }
    }

    public void setMaxMappedBytes(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be > 0");
        }
        this.maxMappedBytes = maxBytes;
        trimMappedBuffers();
    }

    public long getMaxMappedBytes() {
        return maxMappedBytes;
    }

    public long getMappedBytes() {
        return mappedBytes.get();
    }

    public int getDirtyBufferCount() {
        return dirtyBuffers.size();
    }

    public int getDirtyFrameCount() {
        return dirtyFrameBuffers.size();
    }

    public int getDataBufferCount() {
        return datatBuffers.size();
    }

    public int getFrameBufferCount() {
        return frameBuffers.size();
    }

    public BufferStats getBufferStats() {
        return new BufferStats(
            maxMappedBytes,
            mappedBytes.get(),
            datatBuffers.size(),
            frameBuffers.size(),
            dirtyBuffers.size(),
            dirtyFrameBuffers.size(),
            evictionAttempts.get(),
            evictionSuccess.get(),
            evictionBytes.get(),
            evictionDirtyCount.get(),
            flushCycles.get(),
            flushItems.get(),
            flushBytes.get(),
            flushNanos.get()
        );
    }

    public BufferStats getAndResetBufferStats() {
        return new BufferStats(
            maxMappedBytes,
            mappedBytes.get(),
            datatBuffers.size(),
            frameBuffers.size(),
            dirtyBuffers.size(),
            dirtyFrameBuffers.size(),
            evictionAttempts.getAndSet(0),
            evictionSuccess.getAndSet(0),
            evictionBytes.getAndSet(0),
            evictionDirtyCount.getAndSet(0),
            flushCycles.getAndSet(0),
            flushItems.getAndSet(0),
            flushBytes.getAndSet(0),
            flushNanos.getAndSet(0)
        );
    }

    public void resetBufferStats() {
        evictionAttempts.set(0);
        evictionSuccess.set(0);
        evictionBytes.set(0);
        evictionDirtyCount.set(0);
        flushCycles.set(0);
        flushItems.set(0);
        flushBytes.set(0);
        flushNanos.set(0);
    }

    public void trimMappedBuffers() {
        ensureCapacity(0, -1, -1);
    }

    public void setEvictPreferClean(boolean preferClean) {
        this.evictPreferClean = preferClean;
    }

    public boolean isEvictPreferClean() {
        return evictPreferClean;
    }

    public void enableBackgroundFlush(long intervalMs, int maxPerCycle) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("intervalMs must be > 0");
        }
        if (maxPerCycle <= 0) {
            throw new IllegalArgumentException("maxPerCycle must be > 0");
        }
        adaptiveFlushEnabled = false;
        this.flushIntervalMs = intervalMs;
        this.flushMaxPerCycle = maxPerCycle;
        if (flushExecutor != null) {
            return;
        }
        synchronized (this) {
            if (flushExecutor != null) {
                return;
            }
            ThreadFactory tf = (Runnable r) -> {
                Thread t = new Thread(r, "dsobject-flush");
                t.setDaemon(true);
                return t;
            };
            ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(tf);
            ex.scheduleWithFixedDelay(this::flushDirtyOnce, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
            flushExecutor = ex;
        }
    }

    public void enableAdaptiveBackgroundFlush(long minIntervalMs, long maxIntervalMs, int baseMaxPerCycle) {
        if (minIntervalMs <= 0) {
            throw new IllegalArgumentException("minIntervalMs must be > 0");
        }
        if (maxIntervalMs < minIntervalMs) {
            throw new IllegalArgumentException("maxIntervalMs must be >= minIntervalMs");
        }
        if (baseMaxPerCycle <= 0) {
            throw new IllegalArgumentException("baseMaxPerCycle must be > 0");
        }
        adaptiveFlushEnabled = true;
        adaptiveMinIntervalMs = minIntervalMs;
        adaptiveMaxIntervalMs = maxIntervalMs;
        flushMaxPerCycle = baseMaxPerCycle;
        flushIntervalMs = minIntervalMs;
        adaptiveNextFlushNanos.set(0);

        if (flushExecutor != null) {
            return;
        }
        synchronized (this) {
            if (flushExecutor != null) {
                return;
            }
            ThreadFactory tf = (Runnable r) -> {
                Thread t = new Thread(r, "dsobject-flush");
                t.setDaemon(true);
                return t;
            };
            ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(tf);
            ex.scheduleWithFixedDelay(this::flushDirtyOnce, minIntervalMs, minIntervalMs, TimeUnit.MILLISECONDS);
            flushExecutor = ex;
        }
    }

    public void disableBackgroundFlush() {
        adaptiveFlushEnabled = false;
        ScheduledExecutorService ex = flushExecutor;
        flushExecutor = null;
        if (ex != null) {
            ex.shutdownNow();
        }
    }

    public boolean isBackgroundFlushEnabled() {
        return flushExecutor != null;
    }

    protected void flushDirtyOnce() {
        long t0 = System.nanoTime();
        flushCycles.incrementAndGet();
        if (adaptiveFlushEnabled) {
            long now = System.nanoTime();
            long next = adaptiveNextFlushNanos.get();
            if (next > 0 && now < next) {
                return;
            }
        }

        int budget = flushMaxPerCycle;
        if (budget <= 0) {
            budget = 1;
        }
        if (adaptiveFlushEnabled) {
            long max = maxMappedBytes;
            double pressure = max > 0 ? (double) mappedBytes.get() / (double) max : 0.0;
            int dirtyCount = dirtyBuffers.size() + dirtyFrameBuffers.size();
            int m = 1 + (int) Math.floor(Math.min(4.0, Math.max(0.0, pressure) * 4.0));
            if (dirtyCount > 1000) {
                m += 4;
            } else if (dirtyCount > 200) {
                m += 2;
            } else if (dirtyCount > 0) {
                m += 1;
            }
            long prod = (long) budget * (long) m;
            budget = (int) Math.min(Integer.MAX_VALUE, prod);
        }

        for (Long bufferIndex : dirtyBuffers) {
            if (budget <= 0) {
                break;
            }
            MappedByteBuffer buf = datatBuffers.get(bufferIndex);
            if (buf == null) {
                dirtyBuffers.remove(bufferIndex);
                continue;
            }
            ReentrantReadWriteLock lock = getDataBufferLock(bufferIndex);
            if (!lock.writeLock().tryLock()) {
                continue;
            }
            try {
                MappedByteBuffer b2 = datatBuffers.get(bufferIndex);
                if (b2 != null) {
                    b2.force();
                }
                dirtyBuffers.remove(bufferIndex);
                budget--;
                flushItems.incrementAndGet();
                flushBytes.addAndGet(BLOCK_SIZE);
            } catch (Throwable t) {
            } finally {
                lock.writeLock().unlock();
            }
        }

        if (budget <= 0) {
            return;
        }

        if (!bufferLock.tryLock()) {
            return;
        }
        try {
            for (Long pos : dirtyFrameBuffers) {
                if (budget <= 0) {
                    break;
                }
                MappedByteBuffer buf = frameBuffers.get(pos);
                if (buf == null) {
                    dirtyFrameBuffers.remove(pos);
                    continue;
                }
                try {
                    buf.force();
                    dirtyFrameBuffers.remove(pos);
                    budget--;
                    flushItems.incrementAndGet();
                    BufferMeta meta = frameMetas.get(pos);
                    if (meta != null) {
                        flushBytes.addAndGet(meta.bytes);
                    }
                } catch (Throwable t) {
                }
            }
        } finally {
            bufferLock.unlock();
        }

        flushNanos.addAndGet(System.nanoTime() - t0);
        if (adaptiveFlushEnabled) {
            long max = maxMappedBytes;
            double pressure = max > 0 ? (double) mappedBytes.get() / (double) max : 0.0;
            int dirtyCount = dirtyBuffers.size() + dirtyFrameBuffers.size();
            double p = Math.min(1.0, Math.max(0.0, pressure + (dirtyCount > 0 ? 0.25 : 0.0)));
            long min = adaptiveMinIntervalMs;
            long maxI = adaptiveMaxIntervalMs;
            long interval = (long) (maxI - (maxI - min) * p);
            adaptiveNextFlushNanos.set(System.nanoTime() + interval * 1_000_000L);
        }
    }

    /**
     * 将 SuperInode 写入文件
     *
     * @param inode 索引节点对象
     * @param filePath 文件路径
     * @throws IOException IO异常
     */
    public static void writeToFile(Ds128SuperInode inode, String filePath) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(new File(filePath), "rw"); FileChannel channel = file.getChannel()) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, SIZE);
            inode.writeToMappedByteBuffer(buffer);
        }
    }

    /**
     * 从文件读取 SuperInode
     *
     * @param filePath 文件路径
     * @return 索引节点对象
     * @throws IOException IO异常
     */
    public static Ds128SuperInode readFromFile(String filePath) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(new File(filePath), "r"); FileChannel channel = file.getChannel()) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, SIZE);
            Ds128SuperInode inode = new Ds128SuperInode();
            inode.readFromMappedByteBuffer(buffer);
            return inode;
        }
    }
    
    
      /**
     *
     * DsObject 优化版本 主要改进： 统一使用辅助方法计算 buffer 索引和偏移 修复类型不一致问题 增强边界检查 添加性能优化
     */
// ============================================================================
// 辅助方法优化版本
// ============================================================================
    /**
     *
     * 从绝对位置计算 buffer 索引
     *
     * @param position 绝对字节位置
     * @return buffer 索引
     */
    protected long bufferIndexFromPosition(long position) {
        return (int) (position / BLOCK_SIZE);
    }

    /**
     *
     * 从绝对位置计算 buffer 内偏移
     *
     * @param position 绝对字节位置
     * @return buffer 内偏移量
     */
    protected int bufferOffsetFromPosition(long position) {
        return (int) (position % BLOCK_SIZE);
    }

    /**
     *
     * 从数据 ID 计算 buffer 索引（考虑 headerSize）
     *
     * @param id 数据 ID
     * @return buffer 索引
     */
    protected long bufferIndexFromId(long id) {
        return bufferIndexFromPosition(id * dataUnitSize + headerSize);
    }

    /**
     *
     * 从数据 ID 计算绝对位置（考虑 headerSize）
     *
     * @param id 数据 ID
     * @return 绝对字节位置
     */
    protected long bufferPositionFromId(long id) {
        return id * dataUnitSize + headerSize;
    }

    /**
     *
     * 从数据 ID 和偏移计算 buffer 索引
     *
     * @param id 数据 ID
     * @param offset 块内偏移
     * @return buffer 索引
     */
    protected long bufferIndexFromId(long id, int offset) {
        return bufferIndexFromPosition(id * dataUnitSize + headerSize + offset);
    }

    /**
     *
     * 从数据 ID 和偏移计算 buffer 内偏移
     *
     * @param id 数据 ID
     * @param offset 块内偏移
     * @return buffer 内偏移量
     */
    protected int bufferOffsetFromId(long id, int offset) {
        return bufferOffsetFromPosition(id * dataUnitSize + headerSize + offset);
    }

    /**
     *
     * 检查读取操作是否会跨越 buffer 边界
     *
     * @param offset buffer 内偏移
     * @param size 要读取的字节数
     * @return true 如果会跨界
     */
    protected boolean willCrossBoundary(int offset, int size) {
        return offset + size > BLOCK_SIZE;
    }
// ============================================================================
// 优化后的读取方法
// ============================================================================

    /**
     *
     * 读取 byte 类型数据（优化版）
     */
    public byte readByte(long id, int position) throws IOException {
        long absolutePos = bufferPositionFromId(id) + position;
        long bufferIndex = bufferIndexFromPosition(absolutePos);
        int offset = bufferOffsetFromPosition(absolutePos);

        MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
        try {
            return buffer.get(offset);
        } finally {
            unlockBufferForRead(bufferIndex);
        }
    }

    /**
     *
     * 读取 short 类型数据（优化版，增加边界检查）
     */
    public short readShort(long id, int position) throws IOException {
        long absolutePos = bufferPositionFromId(id) + position;
        long bufferIndex = bufferIndexFromPosition(absolutePos);
        int offset = bufferOffsetFromPosition(absolutePos);

// 边界检查：short 需要 2 字节
        if (willCrossBoundary(offset, 2)) {
            throw new IOException("Unaligned short read at offset=" + offset
                + ", would cross buffer boundary");
        }

        MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
        try {
            return buffer.getShort(offset);
        } finally {
            unlockBufferForRead(bufferIndex);
        }
    }

    /**
     *
     * 读取 int 类型数据（优化版，增加边界检查）
     */
    public int readInt(long id, int position) throws IOException {
        long absolutePos = bufferPositionFromId(id) + position;
        long bufferIndex = bufferIndexFromPosition(absolutePos);
        int offset = bufferOffsetFromPosition(absolutePos);

// 边界检查：int 需要 4 字节
        if (willCrossBoundary(offset, 4)) {
            throw new IOException("Unaligned int read at offset=" + offset
                + ", would cross buffer boundary");
        }

        MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
        try {
            return buffer.getInt(offset);
        } finally {
            unlockBufferForRead(bufferIndex);
        }
    }

    /**
     *
     * 读取 long 类型数据（优化版，增加边界检查）
     */
    public long readLong(long id, int position) throws IOException {
        long absolutePos = bufferPositionFromId(id) + position;
        long bufferIndex = bufferIndexFromPosition(absolutePos);
        int offset = bufferOffsetFromPosition(absolutePos);

// 边界检查：long 需要 8 字节
        if (willCrossBoundary(offset, 8)) {
            throw new IOException("Unaligned long read at offset=" + offset
                + ", would cross buffer boundary");
        }

        MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
        try {
            return buffer.getLong(offset);
        } finally {
            unlockBufferForRead(bufferIndex);
        }
    }

    /**
     *
     * 读取 long 数组（优化版，修复原有 bug）
     */
    public void readLongs(long position, long[] values) throws IOException {
        if (values == null || values.length == 0) {
            return;
        }

        for (int j = 0; j < values.length; j++) {
            long pos = position + (long) j * 8L;
            long bufferIndex = bufferIndexFromPosition(pos);
            int offset = bufferOffsetFromPosition(pos);

            // 边界检查
            if (willCrossBoundary(offset, 8)) {
                throw new IOException("Unaligned long read at offset=" + offset
                    + ", array index=" + j);
            }

            MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
            try {
                values[j] = buffer.getLong(offset);
            } finally {
                unlockBufferForRead(bufferIndex);
            }
        }
    }

    /**
     *
     * 读取字节数组（优化版，支持跨 buffer 读取）
     */
    public void readBytes(long id, int position, byte[] out, int offsetOut, int count)
        throws IOException {
        if (out == null || count <= 0) {
            return;
        }

        if (offsetOut < 0 || offsetOut + count > out.length) {
            throw new IllegalArgumentException(
                "Invalid output array bounds: offsetOut=" + offsetOut
                + ", count=" + count + ", array length=" + out.length);
        }

        long absolutePos = bufferPositionFromId(id) + position;
        int remaining = count;
        int outPos = offsetOut;

        while (remaining > 0) {
            long bufferIndex = bufferIndexFromPosition(absolutePos);
            int offset = bufferOffsetFromPosition(absolutePos);
            int take = Math.min(remaining, BLOCK_SIZE - offset);

            MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
            try {
                buffer.get(offset, out, outPos, take);
            } finally {
                unlockBufferForRead(bufferIndex);
            }

            remaining -= take;
            outPos += take;
            absolutePos += take;
        }
    }

// ============================================================================
// 优化后的写入方法
// ============================================================================
    /**
     *
     * 写入 byte 类型数据（优化版）
     */
    public void writeByte(long id, int position, byte value) throws IOException {
        long absolutePos = bufferPositionFromId(id) + position;
        long bufferIndex = bufferIndexFromPosition(absolutePos);
        int offset = bufferOffsetFromPosition(absolutePos);

        MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
        try {
            buffer.put(offset, value);
        } finally {
            unlockBufferForUpdate(bufferIndex);
        }
    }
 
    /**
     *
     * 写入字节数组
     */
    public void writeBytesOffset(long position, byte[] data, int offset, int length)
        throws IOException {

        int remaining = length;
        int srcPos = offset;
        long currentPos = position;

        while (remaining > 0) {
            long bufferIndex = bufferIndexFromPosition(currentPos);
            int bufferOffset = bufferOffsetFromPosition(currentPos);
            int take = Math.min(remaining, BLOCK_SIZE - bufferOffset);

            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            buffer.put(bufferOffset, data, srcPos, take);
            dirty(currentPos);

            remaining -= take;
            srcPos += take;
            currentPos += take;
        }
    }

    /**
     *
     * 写入 short 类型数据（优化版，增加边界检查）
     */
    public void writeShort(long id, int position, short value) throws IOException {
        long absolutePos = bufferPositionFromId(id) + position;
        long bufferIndex = bufferIndexFromPosition(absolutePos);
        int offset = bufferOffsetFromPosition(absolutePos);

        if (willCrossBoundary(offset, 2)) {
            throw new IOException("Unaligned short write at offset=" + offset);
        }

        MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
        try {
            buffer.putShort(offset, value);
        } finally {
            unlockBufferForUpdate(bufferIndex);
        }
    }

    /**
     *
     * 写入 int 类型数据（优化版，增加边界检查）
     */
    public void writeInt(long id, int position, int value) throws IOException {
        long absolutePos = bufferPositionFromId(id) + position;
        long bufferIndex = bufferIndexFromPosition(absolutePos);
        int offset = bufferOffsetFromPosition(absolutePos);

        if (willCrossBoundary(offset, 4)) {
            throw new IOException("Unaligned int write at offset=" + offset);
        }

        MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
        try {
            buffer.putInt(offset, value);
        } finally {
            unlockBufferForUpdate(bufferIndex);
        }
    }

    /**
     *
     * 写入 long 类型数据（优化版，增加边界检查）
     */
    public void writeLong(long id, int position, long value) throws IOException {
        long absolutePos = bufferPositionFromId(id) + position;
        long bufferIndex = bufferIndexFromPosition(absolutePos);
        int offset = bufferOffsetFromPosition(absolutePos);

        if (willCrossBoundary(offset, 8)) {
            throw new IOException("Unaligned long write at offset=" + offset);
        }

        MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
        try {
            buffer.putLong(offset, value);
        } finally {
            unlockBufferForUpdate(bufferIndex);
        }
    }

    /**
     *
     * 写入 float 类型数据（优化版，增加边界检查）
     */
    public void writeFloat(long id, int position, float value) throws IOException {
        long absolutePos = bufferPositionFromId(id) + position;
        long bufferIndex = bufferIndexFromPosition(absolutePos);
        int offset = bufferOffsetFromPosition(absolutePos);

        if (willCrossBoundary(offset, 4)) {
            throw new IOException("Unaligned float write at offset=" + offset);
        }

        MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
        try {
            buffer.putFloat(offset, value);
        } finally {
            unlockBufferForUpdate(bufferIndex);
        }
    }

    /**
     *
     * 写入字节数组（优化版，支持跨 buffer 写入）
     */
    public void writeBytes(long id, int position, byte[] value, int offsetIn, int count)
        throws IOException {
        if (value == null || count <= 0) {
            return;
        }

        if (offsetIn < 0 || offsetIn + count > value.length) {
            throw new IllegalArgumentException(
                "Invalid input array bounds: offsetIn=" + offsetIn
                + ", count=" + count + ", array length=" + value.length);
        }

        long absolutePos = bufferPositionFromId(id) + position;
        int remaining = count;
        int inPos = offsetIn;

        while (remaining > 0) {
            long bufferIndex = bufferIndexFromPosition(absolutePos);
            int offset = bufferOffsetFromPosition(absolutePos);
            int take = Math.min(remaining, BLOCK_SIZE - offset);

            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            try {
                buffer.put(offset, value, inPos, take);
            } finally {
                unlockBufferForUpdate(bufferIndex);
            }

            remaining -= take;
            inPos += take;
            absolutePos += take;
        }
    }

// ============================================================================
// 内部辅助方法优化
// ============================================================================
    /**
     *
     * 从绝对位置读取 long（优化版）
     */
    protected long loadLongOffset(long position) {
        try {
            long bufferIndex = bufferIndexFromPosition(position);
            int offset = bufferOffsetFromPosition(position);

            if (willCrossBoundary(offset, 8)) {
                throw new IOException("Unaligned long read at position=" + position);
            }

            MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
            try {
                return buffer.getLong(offset);
            } finally {
                unlockBufferForRead(bufferIndex);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load long at position=" + position, e);
        }
    }

    /**
     *
     * 向绝对位置存储 short（优化版）
     */
    protected void storeShortOffset(long position, short value) throws IOException {
        long bufferIndex = bufferIndexFromPosition(position);
        int offset = bufferOffsetFromPosition(position);

        if (willCrossBoundary(offset, 2)) {
            throw new IOException("Unaligned short write at position=" + position);
        }

        MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
        try {
            buffer.putShort(offset, value);
        } finally {
            unlockBufferForUpdate(bufferIndex);
        }
    }

    /**
     *
     * 从缓冲区数组读取 int（优化版）
     */
    public int readInt(int position, MappedByteBuffer... buffers) throws IOException {
        int bufferIndex = (int) bufferIndexFromPosition(position);
        int offset = bufferOffsetFromPosition(position);

        if (bufferIndex >= buffers.length) {
            throw new IOException("Buffer index out of bounds: " + bufferIndex);
        }

        if (willCrossBoundary(offset, 4)) {
            throw new IOException("Unaligned int read at position=" + position);
        }

        return buffers[bufferIndex].getInt(offset);
    }

    /**
     *
     * 向缓冲区数组写入 int（优化版）
     */
    public int writeInt(int position, int value, MappedByteBuffer... buffers)
        throws IOException {
        int bufferIndex = (int) bufferIndexFromPosition(position);
        int offset = bufferOffsetFromPosition(position);

        if (bufferIndex >= buffers.length) {
            throw new IOException("Buffer index out of bounds: " + bufferIndex);
        }

        if (willCrossBoundary(offset, 4)) {
            throw new IOException("Unaligned int write at position=" + position);
        }

        buffers[bufferIndex].putInt(offset, value);
        return bufferIndex;
    }

    /**
     *
     * 从缓冲区数组读取 long（优化版）
     */
    public long readLong(int position, MappedByteBuffer... buffers) throws IOException {
        int bufferIndex = (int) bufferIndexFromPosition(position);
        int offset = bufferOffsetFromPosition(position);

        if (bufferIndex >= buffers.length) {
            throw new IOException("Buffer index out of bounds: " + bufferIndex);
        }

        if (willCrossBoundary(offset, 8)) {
            throw new IOException("Unaligned long read at position=" + position);
        }

        return buffers[bufferIndex].getLong(offset);
    }

    /**
     *
     * 向缓冲区数组写入 long（优化版）
     */
    public int writeLong(int position, long value, MappedByteBuffer... buffers)
        throws IOException {
        int bufferIndex = (int) bufferIndexFromPosition(position);
        int offset = bufferOffsetFromPosition(position);

        if (bufferIndex >= buffers.length) {
            throw new IOException("Buffer index out of bounds: " + bufferIndex);
        }

        if (willCrossBoundary(offset, 8)) {
            throw new IOException("Unaligned long write at position=" + position);
        }

        buffers[bufferIndex].putLong(offset, value);
        return bufferIndex;
    }

    /**
     *
     * 读取字节数组到指定位置（优化版）
     */
    protected void loadBytesOffset(long position, byte[] dest, int destOffset, int length)
        throws IOException {
        if (dest == null || length <= 0) {
            return;
        }

        if (destOffset < 0 || destOffset + length > dest.length) {
            throw new IllegalArgumentException(
                "Invalid destination bounds: destOffset=" + destOffset
                + ", length=" + length + ", array length=" + dest.length);
        }

        int remaining = length;
        int dp = destOffset;
        long currentPos = position;

        while (remaining > 0) {
            long bufferIndex = bufferIndexFromPosition(currentPos);
            int offset = bufferOffsetFromPosition(currentPos);
            int take = Math.min(remaining, BLOCK_SIZE - offset);

            MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
            try {
                buffer.get(offset, dest, dp, take);
            } finally {
                unlockBufferForRead(bufferIndex);
            }

            remaining -= take;
            dp += take;
            currentPos += take;
        }
    }

    /**
     * 本类的简单自测入口（演示 super inode 的读写）。
     *
     * <p>
     * 仅用于开发调试，不参与系统主流程。</p>
     */
    public static void main(String[] args) {
        Ds128SuperInode inode = new Ds128SuperInode();
        //inode.magic = 0x123456789ABCDEFL;
        inode.i_root_node = 1L;

        String filePath = "super_inode.dat";
        try {
            writeToFile(inode, filePath);
            Ds128SuperInode readInode = readFromFile(filePath);
            System.out.println("Magic: " + readInode.magic);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

  

    /**
     * 写入 long 类型数据
     *
     * @param id 数据ID (用于计算块索引)
     * @param value 值
     * @throws IOException IO异常
     */
    public void writeLong(long id, long value) throws IOException {
        long index = id * dataUnitSize;
        Long bufferIndex = index / BLOCK_SIZE;
        int offset = (int) (index % BLOCK_SIZE);
        MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
        try {
            buffer.putLong(offset, value);
        } finally {
            unlockBufferForUpdate(bufferIndex);
        }
    }

    /**
     * 在指定绝对位置存储 long 类型数据
     *
     * @param position 绝对字节偏移量
     * @param value 值
     * @throws IOException IO异常
     */
    protected void storeLongOffset(long position, long value) throws IOException {
        Long bufferIndex = position / BLOCK_SIZE;
        int offset = (int) (position % BLOCK_SIZE);
        MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
        try {
            buffer.putLong(offset, value);
        } finally {
            unlockBufferForUpdate(bufferIndex);
        }
    }

    /**
     * 在指定绝对位置存储 long 类型数据
     *
     * @param position 绝对字节偏移量
     * @param values
     * @throws IOException IO异常
     */
    protected void storeLongOffset(long position, long[] values) throws IOException {
        for (int j = 0; j < values.length; j++) {
            long pos = position + (long) j * 8L;
            long bufferIndex = pos / BLOCK_SIZE;
            int offset = (int) (pos % BLOCK_SIZE);
            if (offset > BLOCK_SIZE - 8) {
                throw new IOException("unaligned long write at offset=" + offset);
            }
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            try {
                buffer.putLong(offset, values[j]);
            } finally {
                unlockBufferForUpdate(bufferIndex);
            }
        }

    }

    /**
     * 在指定绝对位置读取 long 类型数据
     *
     * @param position 绝对字节偏移量
     * @param values
     * @throws IOException IO异常
     */
    protected void loadLongOffset(long position, long[] values) throws IOException {
        for (int j = 0; j < values.length; j++) {
            long pos = position + (long) j * 8L;
            long bufferIndex = pos / BLOCK_SIZE;
            int offset = (int) (pos % BLOCK_SIZE);
            if (offset > BLOCK_SIZE - 8) {
                throw new IOException("unaligned long read at offset=" + offset);
            }
            MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
            try {
                values[j] = buffer.getLong(offset);
            } finally {
                unlockBufferForRead(bufferIndex);
            }
        }

    }

  

    /**
     * 读取 long 类型数据
     *
     * @param id 数据ID
     * @return 值
     * @throws IOException IO异常
     */
    public long readLong(long id) throws IOException {
        long index = id * dataUnitSize;
        Long bufferIndex = index / BLOCK_SIZE;
        int offset = (int) (index % BLOCK_SIZE);
        MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
        try {
            return buffer.getLong(offset);
        } finally {
            unlockBufferForRead(bufferIndex);
        }

    }


    /**
     * 从指定绝对位置读取 u16 类型数据
     *
     * @param position 绝对字节偏移量
     * @return 值
     * @throws IOException IO异常
     */
    protected short loadShortOffset(long position) throws IOException {
        Long bufferIndex = position / BLOCK_SIZE;
        int offset = (int) (position % BLOCK_SIZE);
        MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
        try {
            return buffer.getShort(offset);
        } finally {
            unlockBufferForRead(bufferIndex);
        }

    }

    /**
     * 从指定绝对位置读取 u16 类型数据
     *
     * @param position 绝对字节偏移量
     * @return 值
     * @throws IOException IO异常
     */
    protected int loadU16ByOffset(long position) throws IOException {
        Long bufferIndex = position / BLOCK_SIZE;
        int offset = (int) (position % BLOCK_SIZE);
        MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
        try {
            return buffer.getShort(offset) & 0xFFFF;
        } finally {
            unlockBufferForRead(bufferIndex);
        }

    }

    /**
     * 从指定绝对位置读取 u16 类型数据
     *
     * @param position 绝对字节偏移量
     * @return 值
     * @throws IOException IO异常
     */
    protected long loadU32ByOffset(long position) throws IOException {
        Long bufferIndex = position / BLOCK_SIZE;
        int offset = (int) (position % BLOCK_SIZE);
        MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
        try {
            return buffer.getInt(offset) & 0xFFFFFFFFL;
        } finally {
            unlockBufferForRead(bufferIndex);
        }

    }

    /**
     * 从指定绝对位置读取 u8 类型数据
     *
     * @param position 绝对字节偏移量
     * @return 值
     * @throws IOException IO异常
     */
    protected int loadU8ByOffset(long position) throws IOException {
        Long bufferIndex = position / BLOCK_SIZE;
        int offset = (int) (position % BLOCK_SIZE);
        MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
        try {
            return buffer.get(offset) & 0xFF;
        } finally {
            unlockBufferForRead(bufferIndex);
        }

    }

 
    protected ReentrantReadWriteLock getDataBufferLock(Long bufferIndex) {
        int idx = (int) (bufferIndex & (DATA_BUFFER_LOCK_STRIPES - 1));
        return dataBufferLocks[idx];
    }


    protected MappedByteBuffer loadBufferForRead(Long bufferIndex) throws IOException {
        ReentrantReadWriteLock lock = getDataBufferLock(bufferIndex);
        MappedByteBuffer buffer = datatBuffers.get(bufferIndex);
        if (buffer != null) {
            touchDataBuffer(bufferIndex);
            lock.readLock().lock();
            return buffer;
        }

        lock.writeLock().lock();
        try {
            buffer = datatBuffers.get(bufferIndex);
            if (buffer == null) {
                buffer = loadBuffer(bufferIndex);
            }

        } finally {
            lock.writeLock().unlock();
        }
        touchDataBuffer(bufferIndex);
        lock.readLock().lock();
        return buffer;
    }

    protected void unlockBufferForRead(Long bufferIndex) {
        getDataBufferLock(bufferIndex).readLock().unlock();
    }

    protected void touchDataBuffer(Long bufferIndex) {
        BufferMeta m = dataMetas.get(bufferIndex);
        if (m != null) {
            m.lastAccessNanos = System.nanoTime();
        }
    }

    protected void touchFrame(Long position) {
        BufferMeta m = frameMetas.get(position);
        if (m != null) {
            m.lastAccessNanos = System.nanoTime();
        }
    }

    protected void ensureCapacity(long needBytes, long avoidDataBufferIndex, long avoidFramePosition) {
        long max = maxMappedBytes;
        if (max <= 0) {
            return;
        }
        if (!evictionLock.tryLock()) {
            return;
        }
        try {
            for (;;) {
                long cur = mappedBytes.get();
                if (cur + needBytes <= max) {
                    return;
                }
                if (!evictOne(avoidDataBufferIndex, avoidFramePosition)) {
                    return;
                }
            }
        } finally {
            evictionLock.unlock();
        }
    }

    private boolean evictOne(long avoidDataBufferIndex, long avoidFramePosition) {
        evictionAttempts.incrementAndGet();
        BufferMeta[] cand = new BufferMeta[16];
        int n = 0;

        boolean preferClean = evictPreferClean;
        if (preferClean) {
            for (BufferMeta m : dataMetas.values()) {
                if (m.key == avoidDataBufferIndex) {
                    continue;
                }
                if (dirtyBuffers.contains(m.key)) {
                    continue;
                }
                n = insertCandidate(cand, n, m);
            }
            for (BufferMeta m : frameMetas.values()) {
                if (m.key == avoidFramePosition) {
                    continue;
                }
                if (dirtyFrameBuffers.contains(m.key)) {
                    continue;
                }
                n = insertCandidate(cand, n, m);
            }
        }
        if (n == 0) {
            for (BufferMeta m : dataMetas.values()) {
                if (m.key == avoidDataBufferIndex) {
                    continue;
                }
                n = insertCandidate(cand, n, m);
            }
            for (BufferMeta m : frameMetas.values()) {
                if (m.key == avoidFramePosition) {
                    continue;
                }
                n = insertCandidate(cand, n, m);
            }
        }

        for (int i = 0; i < n; i++) {
            BufferMeta victim = cand[i];
            if (victim == null) {
                continue;
            }

            if (victim.kind == BUF_KIND_DATA) {
                Long bufferIndex = victim.key;
                ReentrantReadWriteLock lock = getDataBufferLock(bufferIndex);
                if (!lock.writeLock().tryLock()) {
                    continue;
                }
                try {
                    MappedByteBuffer buf = datatBuffers.remove(bufferIndex);
                    BufferMeta meta = dataMetas.remove(bufferIndex);
                    if (buf == null || meta == null) {
                        continue;
                    }
                    if (dirtyBuffers.remove(bufferIndex)) {
                        buf.force();
                        evictionDirtyCount.incrementAndGet();
                    }
                    mappedBytes.addAndGet(-meta.bytes);
                    evictionSuccess.incrementAndGet();
                    evictionBytes.addAndGet(meta.bytes);
                    try {
                        unloadBuffer(buf);
                    } catch (Exception e) {
                    }
                    return true;
                } catch (Exception e) {
                    return true;
                } finally {
                    lock.writeLock().unlock();
                }
            }

            if (victim.kind == BUF_KIND_FRAME) {
                Long pos = victim.key;
                if (!bufferLock.tryLock()) {
                    continue;
                }
                try {
                    MappedByteBuffer buf = frameBuffers.remove(pos);
                    BufferMeta meta = frameMetas.remove(pos);
                    if (buf == null || meta == null) {
                        continue;
                    }
                    if (dirtyFrameBuffers.remove(pos)) {
                        buf.force();
                        evictionDirtyCount.incrementAndGet();
                    }
                    mappedBytes.addAndGet(-meta.bytes);
                    evictionSuccess.incrementAndGet();
                    evictionBytes.addAndGet(meta.bytes);
                    try {
                        unloadBuffer(buf);
                    } catch (Exception e) {
                    }
                    return true;
                } catch (Exception e) {
                    return true;
                } finally {
                    bufferLock.unlock();
                }
            }
        }

        return false;
    }

    private int insertCandidate(BufferMeta[] cand, int n, BufferMeta m) {
        if (n == 0) {
            cand[0] = m;
            return 1;
        }
        int cap = cand.length;
        if (n < cap) {
            cand[n] = m;
            n++;
        } else if (cand[cap - 1] != null && m.lastAccessNanos >= cand[cap - 1].lastAccessNanos) {
            return n;
        } else {
            cand[cap - 1] = m;
        }
        for (int i = Math.min(n - 1, cap - 1); i > 0; i--) {
            BufferMeta a = cand[i - 1];
            BufferMeta b = cand[i];
            if (a == null || b == null) {
                break;
            }
            if (b.lastAccessNanos >= a.lastAccessNanos) {
                break;
            }
            cand[i - 1] = b;
            cand[i] = a;
        }
        return Math.min(n, cap);
    }

    protected void loadBytesOffset(long position, byte[] dest) throws IOException {
        loadBytesOffset(position, dest, 0, dest.length);
    }

   

    /**
     * 读取 int 类型数据
     *
     * @param id 数据ID
     * @return 值
     * @throws IOException IO异常
     */
    public short readShort(long id) throws IOException {

        return readShort(id, 0);

    }

 

    /**
     * 读取 int 类型数据
     *
     * @param id 数据ID
     * @return 值
     * @throws IOException IO异常
     */
    public int readInt(long id) throws IOException {

        return readInt(id, 0);

    }

 

    /**
     * 在指定绝对位置存储 int 类型数据
     *
     * @param position 绝对字节偏移量
     * @param value 值
     * @throws IOException IO异常
     */
    protected void storeIntOffset(long position, int value) throws IOException {
        Long bufferIndex = position / BLOCK_SIZE;
        int offset = (int) (position % BLOCK_SIZE);
        MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
        try {
            buffer.putInt(offset, value);
        } finally {
            unlockBufferForUpdate(bufferIndex);
        }
    }

    /**
     * 在指定绝对位置存储 byte 类型数据
     *
     * @param position 绝对字节偏移量
     * @param value 值
     * @throws IOException IO异常
     */
    protected void storeByteOffset(long position, byte value) throws IOException {
        Long bufferIndex = position / BLOCK_SIZE;
        int offset = (int) (position % BLOCK_SIZE);
        MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
        try {
            buffer.put(offset, value);
        } finally {
            unlockBufferForUpdate(bufferIndex);
        }
    }

    /**
     * 在指定绝对位置存储 long 类型数据
     *
     * @param position 绝对字节偏移量
     * @param values
     * @throws IOException IO异常
     */
    protected void storeIntOffset(long position, int[] values) throws IOException {
        if ((position & 3L) != 0) {
            throw new IOException("unaligned int write at position=" + position);
        }
        long pos = position;
        int idx = 0;
        int remaining = values.length;
        while (remaining > 0) {
            Long bufferIndex = pos / BLOCK_SIZE;
            int offset = (int) (pos % BLOCK_SIZE);
            int can = Math.min(remaining, (BLOCK_SIZE - offset) / 4);
            if (can <= 0) {
                throw new IOException("unaligned int write at offset=" + offset);
            }
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            try {
                for (int i = 0; i < can; i++) {
                    buffer.putInt(offset + i * 4, values[idx + i]);
                }
            } finally {
                unlockBufferForUpdate(bufferIndex);
            }
            idx += can;
            remaining -= can;
            pos += (long) can * 4L;
        }

    }

    /**
     * 在指定绝对位置读取 long 类型数据
     *
     * @param position 绝对字节偏移量
     * @param values
     * @throws IOException IO异常
     */
    protected void loadIntOffset(long position, int[] values) throws IOException {
        if ((position & 3L) != 0) {
            throw new IOException("unaligned int read at position=" + position);
        }
        long pos = position;
        int idx = 0;
        int remaining = values.length;
        while (remaining > 0) {
            Long bufferIndex = pos / BLOCK_SIZE;
            int offset = (int) (pos % BLOCK_SIZE);
            int can = Math.min(remaining, (BLOCK_SIZE - offset) / 4);
            if (can <= 0) {
                throw new IOException("unaligned int read at offset=" + offset);
            }
            MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
            try {
                for (int i = 0; i < can; i++) {
                    values[idx + i] = buffer.getInt(offset + i * 4);
                }
            } finally {
                unlockBufferForRead(bufferIndex);
            }
            idx += can;
            remaining -= can;
            pos += (long) can * 4L;
        }

    }

    /**
     * 从指定绝对位置读取 int 类型数据
     *
     * @param position 绝对字节偏移量
     * @return 值
     * @throws IOException IO异常
     */
    protected int loadIntOffset(long position) throws IOException {
        long bufferIndex = position / BLOCK_SIZE;
        int offset = (int) (position % BLOCK_SIZE);
        MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
        try {
            return buffer.getInt(offset);
        } finally {
            unlockBufferForRead(bufferIndex);
        }

    }



    /**
     * 读取 float 类型数据
     *
     * @param id
     * @param position
     * @return
     * @throws java.io.IOException
     */
    public float readFloat(long id, int position) throws IOException {
        long index = id * dataUnitSize + position;
        Long bufferIndex = index / BLOCK_SIZE;
        int offset = (int) (index % BLOCK_SIZE);
        MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
        try {
            return buffer.getFloat(offset);
        } finally {
            unlockBufferForRead(bufferIndex);
        }
    }

    /**
     * 写入 double 类型数据
     *
     * @param id
     * @param position
     * @param value
     * @throws java.io.IOException
     */
    public void writeDouble(long id, int position, double value) throws IOException {
        long index = id * dataUnitSize + position;
        Long bufferIndex = index / BLOCK_SIZE;
        int offset = (int) (index % BLOCK_SIZE);
        MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
        try {
            buffer.putDouble(offset, value);
        } finally {
            unlockBufferForUpdate(bufferIndex);
        }
    }

    /**
     * 读取 double 类型数据
     *
     * @param id
     * @param position
     * @return
     * @throws java.io.IOException
     */
    public double readDouble(long id, int position) throws IOException {
        long index = id * dataUnitSize + position;
        Long bufferIndex = index / BLOCK_SIZE;
        int offset = (int) (index % BLOCK_SIZE);
        MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
        try {
            return buffer.getDouble(offset);
        } finally {
            unlockBufferForRead(bufferIndex);
        }

    }

   


    /**
     * 读取字节数组
     *
     * @param id
     * @return
     * @throws java.io.IOException
     * @throws DsDataReadingLessThanException 如果读取超出块边界
     */
    public byte[] readBytesWithId(long id) throws IOException {
        byte[] out = new byte[dataUnitSize];
        readBytes(id, 0, out);
        return out;
    }

    /**
     * 读取字节数组
     *
     * @param id
     * @param position
     * @param out
     * @throws java.io.IOException
     * @throws DsDataReadingLessThanException 如果读取超出块边界
     */
    public void readBytes(long id, int position, byte[] out) throws IOException {
        readBytes(id, position, out, 0, out.length);

    }

 

    /**
     * 写入字节数组的部分内容
     *
     * @param id
     * @param value
     * @throws java.io.IOException
     */
    public void writeBytesWithId(long id, byte[] value) throws IOException {
        writeBytes(id, 0, value);
    }

    /**
     * 初始化数据块的零字节
     *
     * @param id
     * @throws java.io.IOException
     */
    public void clearWithId(long id) throws IOException {
        writeBytes(id, 0, zero_block_unit);
    }

    /**
     * 写入字节数组
     *
     * @param id
     * @param position
     * @param value
     * @throws java.io.IOException
     * @throws DsDataOverFlowException 如果写入超出块边界
     */
    public void writeBytes(long id, int position, byte[] value) throws IOException {
        writeBytes(id, position, value, 0, value.length);
    }

 

    /**
     * 写入 UTF-8 字符串
     *
     * @param id
     * @param position
     * @param value
     * @param offsetIn
     * @param count
     * @throws java.io.IOException
     */
    public void writeUtf8(long id, int position, String value, int offsetIn, int count) throws IOException {
        long index = id * dataUnitSize + position;
        Long bufferIndex = index / BLOCK_SIZE;
        int offset = (int) (index % BLOCK_SIZE);
        byte[] data = value.getBytes(UTF_8);
        if ((offset + count) <= BLOCK_SIZE) {
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            try {
                buffer.put(offset, data, offsetIn, count);
            } finally {
                unlockBufferForUpdate(bufferIndex);
            }
        } else {
            throw new DsDataOverFlowException();
        }
    }

    /**
     * 读取 UTF-8 字符串
     *
     * @param id
     * @param position
     * @param out
     * @param offsetOut
     * @param count
     * @throws java.io.IOException
     */
    public void readUtf8(long id, int position, byte[] out, int offsetOut, int count) throws IOException {
        long index = id * dataUnitSize + position;
        Long bufferIndex = index / BLOCK_SIZE;
        int offset = (int) (index % BLOCK_SIZE);
        if ((offset + count) <= BLOCK_SIZE) {
            MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
            try {
                buffer.get(offset, out, offsetOut, count);
            } finally {
                unlockBufferForRead(bufferIndex);
            }
        } else {
            throw new DsDataOverFlowException();
        }

    }

    /**
     * 写入整个数据单元
     *
     * @param id
     * @param value
     * @param offsetIn
     * @throws java.io.IOException
     */
    public void writeUnit(long id, byte[] value, int offsetIn) throws IOException {
        long index = id * dataUnitSize;
        Long bufferIndex = index / BLOCK_SIZE;
        int offset = (int) (index % BLOCK_SIZE);
        if ((offset + dataUnitSize) <= BLOCK_SIZE) {
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            try {
                buffer.put(offset, value, offsetIn, dataUnitSize);
            } finally {
                unlockBufferForUpdate(bufferIndex);
            }
        } else {
            throw new DsDataOverFlowException();
        }
    }

    /**
     * 读取整个数据单元
     *
     * @param id
     * @param out
     * @param offsetOut
     * @throws java.io.IOException
     */
    public void readUnit(long id, byte[] out, int offsetOut) throws IOException {
        long index = id * dataUnitSize;
        Long bufferIndex = index / BLOCK_SIZE;
        int offset = (int) (index % BLOCK_SIZE);
        if ((offset + dataUnitSize) <= BLOCK_SIZE) {
            MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
            try {
                buffer.get(offset, out, offsetOut, dataUnitSize);
            } finally {
                unlockBufferForRead(bufferIndex);
            }
        } else {
            throw new DsDataOverFlowException();
        }

    }

    /**
     * 加载指定索引的块(buffer)到内存。 如果文件不够大，会自动扩展文件大小。
     *
     * @param bufferIndex 块索引 (0-based)
     * @return 映射的 MappedByteBuffer
     * @throws IOException IO异常
     */
    protected MappedByteBuffer loadBuffer(Long bufferIndex) throws IOException {
        MappedByteBuffer buffer = datatBuffers.get(bufferIndex);
        if (buffer != null) {
            touchDataBuffer(bufferIndex);
            return buffer;
        }
        ReentrantReadWriteLock lock = getDataBufferLock(bufferIndex);
        lock.writeLock().lock();
        try {
            buffer = datatBuffers.get(bufferIndex);
            if (buffer != null) {
                touchDataBuffer(bufferIndex);
                return buffer;
            }
            ensureCapacity(BLOCK_SIZE, bufferIndex, -1);
            try (RandomAccessFile file = new RandomAccessFile(dataFile, "rw"); FileChannel channel = file.getChannel()) {
                long position = bufferIndex * BLOCK_SIZE;
                if (file.length() >= position + BLOCK_SIZE) {
                    buffer = channel.map(FileChannel.MapMode.READ_WRITE, position, BLOCK_SIZE);
                } else {
                    file.setLength(position + BLOCK_SIZE);
                    buffer = channel.map(FileChannel.MapMode.READ_WRITE, position, BLOCK_SIZE);
                    buffer.put(ZERO_BLOCK_BYTES);
                    buffer.position(0);
                }
                datatBuffers.put(bufferIndex, buffer);
                dataMetas.put(bufferIndex, new BufferMeta(bufferIndex, BLOCK_SIZE, BUF_KIND_DATA, System.nanoTime()));
                mappedBytes.addAndGet(BLOCK_SIZE);
                return buffer;
            }
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * 卸载指定的缓冲区，并确保数据同步到磁盘。
     *
     * @param bufferIndex 缓冲区索引
     * @throws IOException IO异常
     */
    protected void unloadBuffer(Long bufferIndex) throws IOException {
        ReentrantReadWriteLock lock = getDataBufferLock(bufferIndex);
        lock.writeLock().lock();
        try {
            MappedByteBuffer buffer = datatBuffers.remove(bufferIndex);
            BufferMeta meta = dataMetas.remove(bufferIndex);
            if (buffer != null) {
                if (dirtyBuffers.remove(bufferIndex)) {
                    buffer.force();
                }
                if (meta != null) {
                    mappedBytes.addAndGet(-meta.bytes);
                }
                unloadBuffer(buffer);
            }
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * 卸载指定的帧缓冲区。
     *
     * @param position 帧起始位置
     * @throws IOException IO异常
     */
    protected void unloadFrame(Long position) throws IOException {
        bufferLock.lock();
        try {
            MappedByteBuffer buffer = frameBuffers.remove(position);
            BufferMeta meta = frameMetas.remove(position);
            if (buffer != null) {
                if (dirtyFrameBuffers.remove(position)) {
                    buffer.force();
                }
                if (meta != null) {
                    mappedBytes.addAndGet(-meta.bytes);
                }
                unloadBuffer(buffer);
            }
        } finally {
            bufferLock.unlock();
        }

    }

    /**
     * 加载指定位置和长度的数据帧。 类似于 loadBuffer，但支持非标准块大小。
     *
     * @param position 起始位置
     * @param length 长度
     * @return 映射的缓冲区
     * @throws IOException IO异常
     */
    protected MappedByteBuffer loadFrame(long position, int length) throws IOException {
        bufferLock.lock();
        try {
            MappedByteBuffer buffer = frameBuffers.get(position);
            if (buffer != null) {
                touchFrame(position);
                return buffer;
            }
            ensureCapacity(length, -1, position);
            try (RandomAccessFile file = new RandomAccessFile(dataFile, "rw"); FileChannel channel = file.getChannel()) {
                long size = position + length;
                if (file.length() >= size) {
                    buffer = channel.map(FileChannel.MapMode.READ_WRITE, position, length);
                } else {//初始化并新增数据区
                    long oldSize = file.length();
                    file.setLength(size);
                    buffer = channel.map(FileChannel.MapMode.READ_WRITE, position, length);
                    int count = length / BLOCK_SIZE;
                    int rest = length % BLOCK_SIZE;
                    //初始化新增数据区
                    for (int i = 0; i < count; i++) {
                        buffer.put(ZERO_BLOCK_BYTES);
                    }
                    if (rest > 0) {
                        byte[] data = new byte[rest];
                        buffer.put(data);
                    }
                    if (position > oldSize) {
                        byte[] data = new byte[(int) (position - oldSize)];
                        try (
                            ByteArrayInputStream in = new ByteArrayInputStream(data); ReadableByteChannel rc = Channels.newChannel(in);) {
                            channel.transferFrom(channel, oldSize, data.length);
                        }

                    }
                }
                frameBuffers.put(position, buffer);
                frameMetas.put(position, new BufferMeta(position, length, BUF_KIND_FRAME, System.nanoTime()));
                mappedBytes.addAndGet(length);
                return buffer;
            }
        } finally {
            bufferLock.unlock();
        }

    }

    /**
     * 加载缓冲区用于更新，并加锁。
     *
     * @param bufferIndex 缓冲区索引
     * @return 映射的缓冲区
     * @throws IOException IO异常
     */
    protected MappedByteBuffer loadBufferForUpdate(Long bufferIndex) throws IOException {
        ReentrantReadWriteLock lock = getDataBufferLock(bufferIndex);
        
        MappedByteBuffer buffer = datatBuffers.get(bufferIndex);
        if (buffer != null) {
            touchDataBuffer(bufferIndex);
            lock.writeLock().lock();
            return buffer;
        }
        try {
            buffer = loadBuffer(bufferIndex);
            touchDataBuffer(bufferIndex);
            lock.writeLock().lock();
            return buffer;
        } catch (IOException e) {
            throw e;
        }
        
    }

    /**
     * 释放更新锁，并将缓冲区标记为脏（需要同步）。
     *
     * @param bufferIndex 缓冲区索引
     */
    protected void unlockBufferForUpdate(Long bufferIndex) {
        dirtyBuffers.add(bufferIndex);
        getDataBufferLock(bufferIndex).writeLock().unlock();

    }

    /**
     * 释放缓冲区锁（不标记为脏）。
     *
     * @param bufferIndex 缓冲区索引
     */
    protected void unlockBuffer(Long bufferIndex) {
        getDataBufferLock(bufferIndex).writeLock().unlock();

    }

    /**
     * 将所有脏缓冲区（dirty buffers）同步到磁盘。 确保数据的持久性。
     */
    public void sync() {
        if (syncOpLock.tryLock()) {
            try {
                // TODO: 优化同步策略。目前遍历Set可能在大数据量下较慢。
                // 考虑批量处理或异步同步。
                for (Long i : dirtyBuffers) {
                    MappedByteBuffer buffer = datatBuffers.get(i);
                    if (buffer != null) {
                        buffer.force();
                    }
                }
                dirtyBuffers.clear();
                for (Long i : dirtyFrameBuffers) {
                    MappedByteBuffer buffer = frameBuffers.get(i);
                    if (buffer != null) {
                        buffer.force();
                    }
                }
                dirtyFrameBuffers.clear();
            } finally {
                syncOpLock.unlock();
            }
        }
    }

    /**
     * 标记指定缓冲区为脏（已修改）。
     *
     * @param bufferIndex 缓冲区索引
     */
    public void dirty(Long bufferIndex) {

        // try {
        //syncOpLock.lock();
        dirtyBuffers.add(bufferIndex);
        // } finally {
        //syncOpLock.unlock();
        // }

    }

    /**
     * 标记指定帧缓冲区为脏（已修改）。
     *
     * @param position 帧位置
     */
    public void dirtyFrame(Long position) {

        // try {
        //syncOpLock.lock();
        dirtyFrameBuffers.add(position);
        // } finally {
        //syncOpLock.unlock();
        // }

    }

    private static Method UNMAPPER_METHOD;
    private static Object UNSAFE_OBJ;
    private static Method UNSAFE_INVOKE_CLEANER;

    /**
     * 释放 MappedByteBuffer 占用的堆外内存。
     * <p>
     * <b>警告：</b> 此方法使用反射访问 JDK 内部 API。 在较新版本的 JDK (9+) 中，如果未允许非法访问，可能会失败。
     * </p>
     *
     * @param buffer 要释放的缓冲区
     */
    public void unloadBuffer(MappedByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        try {
            if (UNMAPPER_METHOD == null) {
                UNMAPPER_METHOD = MappedByteBuffer.class.getDeclaredMethod("unmapper");
                UNMAPPER_METHOD.setAccessible(true);
            }
            ((UnmapperProxy) UNMAPPER_METHOD.invoke(buffer)).unmap();
            return;
        } catch (Throwable t) {
        }

        try {
            if (UNSAFE_INVOKE_CLEANER == null || UNSAFE_OBJ == null) {
                Class<?> uc = Class.forName("sun.misc.Unsafe");
                Method gf = uc.getDeclaredMethod("getUnsafe");
                gf.setAccessible(true);
                UNSAFE_OBJ = gf.invoke(null);
                UNSAFE_INVOKE_CLEANER = uc.getMethod("invokeCleaner", java.nio.ByteBuffer.class);
            }
            UNSAFE_INVOKE_CLEANER.invoke(UNSAFE_OBJ, buffer);
        } catch (Throwable t) {
        }

    }

  
}
