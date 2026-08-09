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
    //protected List<ByteBuf>  datatBuffers = new ArrayList(8192);
    protected List<ByteBuffer> dataBuffers = new ArrayList(8192);
    
    protected List<byte[]>  dataBytes = new ArrayList(8192);

   
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
    
    public final int headerSize;
    protected final File dataFile;

    /**
     * 构造函数
     *
     * @param dataFile 数据文件对象
     * @param headerSize
     * @param dataUnitSize 数据单元大小（字节）
     */
    public DsMemory(File dataFile,int headerSize, int dataUnitSize) {
        this.dataFile = dataFile;
        this.dataUnitSize = dataUnitSize;
        this.headerSize = headerSize;
        zero_block_unit = new byte[dataUnitSize];
        for (int i = 0; i < DATA_BUFFER_LOCK_STRIPES; i++) {
            dataBufferLocks[i] = new ReentrantReadWriteLock();
        }
        // 初始化锁池
        for (int i = 0; i < DEFAULT_LOCK_POOL_SIZE; i++) {
//        for(int i=0;i<5000;i++){
            idLockPool.add(new ReentrantLock());
        }
//        headerBuffer = alloc.heapBuffer(BLOCK_SIZE);
//        datatBuffers.addComponent(headerBuffer);
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
        loadBufferWithOffsetFromId(id).putLong( value);
    }

    /**
     * 写入 long 类型数据
     *
     * @param id 数据ID (用于计算块索引)
     * @param value 值
     * @ IO异常
     */
    public void writeLong(long id, long value)  {
        loadBufferWithOffsetFromId(id).putLong( value);
    }

    /**
     * 在指定绝对位置存储 long 类型数据
     *
     * @param position 绝对字节偏移量
     * @param value 值
     * @ IO异常
     */
    protected void storeLongOffset(long position, long value)  {
        loadBufferWithOffset(position).putLong(value);
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
         return loadBufferWithOffsetFromId(id,offset).getLong();

    }

    /**
     * 读取 long 类型数据
     *
     * @param id 数据ID
     * @return 值
     * @ IO异常
     */
    public long readLong(long id)  {
        return loadBufferWithOffsetFromId(id).getLong();
    }

    /**
     * 从指定绝对位置读取 long 类型数据
     *
     * @param position 绝对字节偏移量
     * @return 值
     */
    protected long loadLongOffset(long position) {
        return loadBufferWithOffset(position).getLong();
    }

    /**
     * 从指定绝对位置读取 u16 类型数据
     *
     * @param position 绝对字节偏移量
     * @return 值
     * @ IO异常
     */
    protected short loadShortOffset(long position)  {
        return loadBufferWithOffset(position).getShort();

    }

    /**
     * 从指定绝对位置读取 u16 类型数据
     *
     * @param postion 绝对字节偏移量
     * @return 值
     * @ IO异常
     */
    protected int loadU16ByOffset(long position)  {
        return loadBufferWithOffset(position).getShort() & 0xFFFF;

    }

    /**
     * 从指定绝对位置读取 u16 类型数据
     *
     * @param position 绝对字节偏移量
     * @return 值
     * @ IO异常
     */
    protected long loadU32ByOffset(long position)  {
        return loadBufferWithOffset(position).getInt() & 0xFFFFFFFFL;
    }

    /**
     * 从指定绝对位置读取 u8 类型数据
     *
     * @param position 绝对字节偏移量
     * @return 值
     * @ IO异常
     */
    protected int loadU8ByOffset(long position)  {
        return loadBufferWithOffset(position).get() & 0xFF;

    }

    protected void loadBytesOffset(long position, byte[] dest, int destOffset, int length)  {
        loadBufferWithOffset(position).get( dest, destOffset, length);
    }

    protected ReentrantReadWriteLock getDataBufferLock(long bufferIndex) {
        int idx = (int) (bufferIndex & (DATA_BUFFER_LOCK_STRIPES - 1));
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
    }



    protected void loadBytesOffset(long position, byte[] dest)  {
        loadBytesOffset(position, dest, 0, dest.length);
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
        loadBufferWithOffsetFromId(id,offset).putInt( value);
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
        return loadBufferWithOffsetFromId(id,offset).getShort();

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
        return loadBufferWithOffsetFromId(id,offset).getInt();
    }

    /**
     * 在指定绝对位置存储 int 类型数据
     *
     * @param position 绝对字节偏移量
     * @param value 值
     * @ IO异常
     */
    protected void storeShortOffset(long position, short value)  {
        loadBufferWithOffset(position).putShort( value);
    }

    /**
     * 在指定绝对位置存储 int 类型数据
     *
     * @param position 绝对字节偏移量
     * @param value 值
     * @ IO异常
     */
    protected void storeIntOffset(long position, int value)  {
        loadBufferWithOffset(position).putInt( value);
    }

    /**
     * 在指定绝对位置存储 byte 类型数据
     *
     * @param position 绝对字节偏移量
     * @param value 值
     * @ IO异常
     */
    protected void storeByteOffset(long position, byte value)  {
        loadBufferWithOffset(position).put(value);
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
    }

    /**
     * 从指定绝对位置读取 int 类型数据
     *
     * @param position 绝对字节偏移量
     * @return 值
     * @ IO异常
     */
    protected int loadIntOffset(long position)  {
        int bufferIndex = bufferIndexFromPosition(position);
        ByteBuffer buf = loadBuffer(bufferIndex);
        return buf.getInt((int) position);
    }

    /**
     * 写入 short 类型数据
     * @param id
     * @param offset
     * @param value
     * 
     */
    public void writeShort(long id, int offset, short value)  {
        int bufferIndex = bufferIndexFromId(id,offset);
        ByteBuffer buf = loadBuffer(bufferIndex);
        buf.putShort(bufferPositionFromId(id), value);
    }

    /**
     * 写入 float 类型数据
     * @param id
     * @param offset
     * @param value
     */
    public void writeFloat(long id, int offset, float value)  {
        int bufferIndex = bufferIndexFromId(id,offset);
        ByteBuffer buf = loadBuffer(bufferIndex);
        buf.putFloat(bufferPositionFromId(id), value);
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
        int bufferIndex = bufferIndexFromId(id,offset);
        ByteBuffer buf = loadBuffer(bufferIndex);
        return buf.getFloat(bufferPositionFromId(id));
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
        int bufferIndex = bufferIndexFromId(id,offset);
        ByteBuffer buf = loadBuffer(bufferIndex);
        buf.putDouble(bufferPositionFromId(id), value);
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
        int bufferIndex = bufferIndexFromId(id,offset);
        ByteBuffer buf = loadBuffer(bufferIndex);
        return buf.getDouble(bufferPositionFromId(id));

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
        int bufferIndex = bufferIndexFromId(id,offset);
        ByteBuffer buf = loadBuffer(bufferIndex);
        buf.put(bufferPositionFromId(id), value);
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
       int bufferIndex = bufferIndexFromId(id,offset);
        ByteBuffer buf = loadBuffer(bufferIndex);
        return buf.get(bufferPositionFromId(id));
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
        int bufferIndex = bufferIndexFromId(id,offset);
        ByteBuffer buf = loadBuffer(bufferIndex);
        if(buf.remaining()>count){
            buf.get(bufferPositionFromId(id), out, offsetOut, count);
        }else{//跨页读
            int rest = count - buf.remaining();
            for(int i = buf.remaining();i>=LONG_SIZE;i=i-LONG_SIZE){
                buf.get(bufferPositionFromId(id), out, offsetOut, buf.remaining());
            }
            offsetOut += buf.remaining();
            int pages = rest/BLOCK_SIZE ;
            for(int page = 0;page<pages;page++){
                bufferIndex++;
                buf = loadBuffer(bufferIndex);
                buf.get(bufferPositionFromId(id), out, offsetOut, BLOCK_SIZE);
                    rest -= BLOCK_SIZE;
                    offsetOut += BLOCK_SIZE;
                    if(rest<=0) return;
            }
            if(rest>0){
                bufferIndex++;
                buf = loadBuffer(bufferIndex);
                buf.get(bufferPositionFromId(id), out, offsetOut, rest);
            }
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
        int bufferIndex = bufferIndexFromId(id,offset);
        ByteBuffer buf = loadBuffer(bufferIndex);
        buf.get(bufferPositionFromId(id), value, offsetIn, count);
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
    protected ByteBuffer loadBuffer(long bufferIndex)  {
        int idx = (int) bufferIndex;
        bufferLock.lock();
        try {
            ByteBuffer buf = null;
            boolean allocatedNew = false;
            if (idx < dataBuffers.size()) {
                buf = dataBuffers.get(idx);
            }
            if (buf == null) {
                ensureCapacity(1, idx);
                byte[] data;
                if (idx < dataBytes.size()) {
                    data = dataBytes.get(idx);
                    if (data == null) {
                        data = readBlockFromFile(idx);
                        dataBytes.set(idx, data);
                        buf = ByteBuffer.wrap(data);
                        dataBuffers.set(idx, buf);
                        allocatedNew = true;
                    } else {
                        buf = dataBuffers.get(idx);
                        if (buf == null) {
                            buf = ByteBuffer.wrap(data);
                            dataBuffers.set(idx, buf);
                            allocatedNew = true;
                        }
                    }
                } else {
                    while (dataBytes.size() <= idx) {
                        dataBytes.add(null);
                        dataBuffers.add(null);
                    }
                    data = readBlockFromFile(idx);
                    dataBytes.set(idx, data);
                    buf = ByteBuffer.wrap(data);
                    dataBuffers.set(idx, buf);
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
            return buf;
        } finally {
            bufferLock.unlock();
        }
    }

    protected final void touchBuffer(int bufferIndex) {
        bufferLastAccessNanos.put(bufferIndex, System.nanoTime());
    }

    protected final void markDirty(int bufferIndex) {
        dirtyBufferIndices.add(bufferIndex);
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
        Integer[] cand = new Integer[EVICT_CANDIDATE_SLOT];
        long[] candAccess = new long[EVICT_CANDIDATE_SLOT];
        int n = 0;
        for (Map.Entry<Integer, Long> e : bufferLastAccessNanos.entrySet()) {
            int idx = e.getKey();
            if (idx == avoidBufferIndex) {
                continue;
            }
            long access = e.getValue();
            if (n < EVICT_CANDIDATE_SLOT) {
                int j = n;
                while (j > 0 && candAccess[j - 1] > access) {
                    cand[j] = cand[j - 1];
                    candAccess[j] = candAccess[j - 1];
                    j--;
                }
                cand[j] = idx;
                candAccess[j] = access;
                n++;
            } else if (access < candAccess[EVICT_CANDIDATE_SLOT - 1]) {
                int j = EVICT_CANDIDATE_SLOT - 1;
                while (j > 0 && candAccess[j - 1] > access) {
                    cand[j] = cand[j - 1];
                    candAccess[j] = candAccess[j - 1];
                    j--;
                }
                cand[j] = idx;
                candAccess[j] = access;
            }
        }

        for (int i = 0; i < n; i++) {
            Integer victim = cand[i];
            if (victim == null) {
                continue;
            }
            ReentrantReadWriteLock stripe = getDataBufferLock((long) victim);
            stripe.writeLock().lock();
            try {
                bufferLock.lock();
                try {
                    if (victim >= dataBuffers.size()) {
                        bufferLastAccessNanos.remove(victim);
                        continue;
                    }
                    byte[] victimBytes = dataBytes.get(victim);
                    ByteBuffer victimBuf = dataBuffers.get(victim);
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
        }
    }

    protected void writeBlockToFile(int bufferIndex, byte[] block) {
        if (dataFile == null) {
            return;
        }
        long start = (long) bufferIndex * (long) BLOCK_SIZE;
        ensureParentDirectory(dataFile);
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

    }

    /**
     * 释放缓冲区锁（不标记为脏）。
     *
     * @param bufferIndex 缓冲区索引
     */
    protected void unlockBuffer(long bufferIndex) {

    }
    
     /**
     * 将内存所有数据同步到底层存储。
     * 确保数据的持久性。
     */
    public void syncStore(){
        if (dataFile == null) {
            throw new IllegalStateException("syncStore requires a non-null data file");
        }
        if (syncOpLock.tryLock()) {
            try {
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
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            } finally {
                syncOpLock.unlock();
            }
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
        if (!dataFile.exists()) {
            return;
        }
        syncOpLock.lock();
        try {
            bufferLock.lock();
            try {
                dataBuffers.clear();
                dataBytes.clear();
                bufferLastAccessNanos.clear();
                dirtyBufferIndices.clear();
                activeCachedBlocks.set(0);
                cachedBytes.set(0);
                evictionAttempts.set(0);
                evictionSuccess.set(0);
                evictionBytes.set(0);
                evictionDirtyCount.set(0);
                highestBufferIndexEverSeen = -1;
                long fileLength = dataFile.length();
                if (fileLength > 0) {
                    highestBufferIndexEverSeen = requiredBlockCount(fileLength) - 1;
                }
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

}
