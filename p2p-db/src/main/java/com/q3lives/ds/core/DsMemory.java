package com.q3lives.ds.core;

import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.collections.DsList;
import com.q3lives.ds.fs.Ds128SuperInode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
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

    protected PooledByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;

    // TODO: 实现缓存淘汰策略 (如 LRU)，防止大文件加载导致内存溢出 (OOM)。
    // 当前实现中，所有加载的缓冲区都会保留在内存中，直到显式卸载。
    /**
     * 数据缓冲区缓存池：Key为块索引(bufferIndex)，Value为映射的内存缓冲区
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

    /**
     * 构造函数
     *
     * @param dataFile 数据文件对象
     * @param headerSize
     * @param dataUnitSize 数据单元大小（字节）
     */
    public DsMemory(File dataFile,int headerSize, int dataUnitSize) {

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
        ByteBuffer buf = loadBufferWithOffset(position);
        if(buf.remaining()<values.length*LONG_SIZE){
            int j = 0;
            for(int i = buf.remaining();i>=LONG_SIZE;i=i-LONG_SIZE){
                buf.putLong(values[j]);
                j++;
            }
            int rest = (values.length-j)*LONG_SIZE;
            int pages = rest/BLOCK_SIZE + rest%BLOCK_SIZE==0?0:1;
            int bufferIndex = bufferIndexFromPosition(position);
            for(int page = 0;page<pages;page++){
                bufferIndex++;
                buf = loadBuffer(bufferIndex);
                for (int i = 0; i < BLOCK_SIZE; i = i - LONG_SIZE) {
                    buf.putLong(values[j]);
                    j++;
                    if(j>=values.length) return;
                }
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
        int bufferIndex = bufferIndexFromPosition(position);
        ByteBuffer buf = loadBuffer(bufferIndex);
        buf.position(bufferOffsetFromPosition(position));
        for (int i = 0; i < values.length; i++) {
            values[i] = buf.getInt();
        }

        int count = values.length * LONG_SIZE;

        if (buf.remaining() > count) {
            for (int i = 0; i < values.length; i++) {
                values[i] = buf.getInt();
            }
        } else {//跨页读
            int rest = count - buf.remaining();
            int j = 0;
            for (int i = buf.remaining(); i >= LONG_SIZE; i = i - LONG_SIZE) {
                values[j] = buf.getInt();
                j++;
            }
            if (buf.remaining() > 0) {
                throw new RuntimeException("unaligned long write at offset=" + buf.remaining());
            }

            int pages = rest / BLOCK_SIZE;
            for (int page = 0; page < pages; page++) {
                bufferIndex++;
                buf = loadBuffer(bufferIndex);
                for (int i = 0; i >= BLOCK_SIZE; i = i + LONG_SIZE) {
                    values[j] = buf.getInt();
                    j++;
                }
                if (j > values.length) {
                    return;
                }
            }
            if (rest > 0) {
                bufferIndex++;
                buf = loadBuffer(bufferIndex);
                for (int i = 0; i >= BLOCK_SIZE; i = i + LONG_SIZE) {
                    values[j] = buf.getInt();
                    j++;
                    if (j > values.length) return;
                }
                
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
        
        return null;
        
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
        ByteBuffer buf = null;
        int bufferIndex = bufferIndexFromPosition(position);
        ReentrantReadWriteLock lock = getDataBufferLock(bufferIndex);
        if (dataBuffers.size() <= bufferIndex) {
            lock.writeLock().lock();
            try {
                byte[] data = new byte[BLOCK_SIZE];
                dataBytes.add(data);
                buf = ByteBuffer.wrap(data);
                dataBuffers.add(buf);
            } finally {
                lock.writeLock().unlock();
            }
        }else{
            buf = dataBuffers.get(bufferIndex);
        }

        lock.readLock().lock();
        return buf;
    }

    protected void unlockBufferForRead(int bufferIndex) {
        getDataBufferLock(bufferIndex).readLock().unlock();
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
        ByteBuffer buf = loadBufferWithOffset(position);
        if(buf.remaining()<values.length*INT_SIZE){
            int j = 0;
            for(int i = buf.remaining();i>=INT_SIZE;i=i-INT_SIZE){
                buf.putInt(values[j]);
                j++;
            }
            int rest = (values.length-j)*INT_SIZE;
            int pages = rest/BLOCK_SIZE + rest%BLOCK_SIZE==0?0:1;
            int bufferIndex = bufferIndexFromPosition(position);
            for(int page = 0;page<pages;page++){
                bufferIndex++;
                buf = loadBuffer(bufferIndex);
                for (int i = 0; i < BLOCK_SIZE; i = i - INT_SIZE) {
                    buf.putInt(values[j]);
                    j++;
                    if(j>=values.length) return;
                }
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
        int bufferIndex = bufferIndexFromPosition(position);
        ByteBuffer buf = loadBuffer(bufferIndex);
        buf.position(bufferOffsetFromPosition(position));
        for (int i = 0; i < values.length; i++) {
            values[i] = buf.getInt();
        }

        int count = values.length * INT_SIZE;

        if (buf.remaining() > count) {
            for (int i = 0; i < values.length; i++) {
                values[i] = buf.getInt();
            }
        } else {//跨页读
            int rest = count - buf.remaining();
            int j = 0;
            for (int i = buf.remaining(); i >= INT_SIZE; i = i - INT_SIZE) {
                values[j] = buf.getInt();
                j++;
            }
            if (buf.remaining() > 0) {
                throw new RuntimeException("unaligned long write at offset=" + buf.remaining());
            }

            int pages = rest / BLOCK_SIZE;
            for (int page = 0; page < pages; page++) {
                bufferIndex++;
                buf = loadBuffer(bufferIndex);
                for (int i = 0; i >= BLOCK_SIZE; i = i + INT_SIZE) {
                    values[j] = buf.getInt();
                    j++;
                }
                if (j > values.length) {
                    return;
                }
            }
            if (rest > 0) {
                bufferIndex++;
                buf = loadBuffer(bufferIndex);
                for (int i = 0; i >= BLOCK_SIZE; i = i + INT_SIZE) {
                    values[j] = buf.getInt();
                    j++;
                    if (j > values.length) return;
                }
               
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
     *
     * @param bufferIndex 块索引 (0-based)
     * @return 映射的 MappedByteBuffer
     * @ IO异常
     */
    protected ByteBuffer loadBuffer(long bufferIndex)  {
        ByteBuffer buf = null;
        if (dataBuffers.size() <= bufferIndex) {
            try {
                byte[] data = new byte[BLOCK_SIZE];
                dataBytes.add(data);
                buf = ByteBuffer.wrap(data);
                dataBuffers.add(buf);
            } finally {
            }
        }else{
            buf = dataBuffers.get((int) bufferIndex);
        }
        return buf;
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
        
        ByteBuffer buf = null;
        ReentrantReadWriteLock lock = getDataBufferLock(bufferIndex);
        if (dataBuffers.size() <= bufferIndex) {
            lock.writeLock().lock();
            try {
                byte[] data = new byte[BLOCK_SIZE];
                dataBytes.add(data);
                buf = ByteBuffer.wrap(data);
                dataBuffers.add(buf);
            } finally {
                lock.writeLock().unlock();
            }
        }else{
            buf = dataBuffers.get((int) bufferIndex);
        }
        lock.writeLock().lock();
        return buf;
    }

    /**
     * 释放更新锁，并将缓冲区标记为脏（需要同步）。
     *
     * @param bufferIndex 缓冲区索引
     */
    protected void unlockBufferForUpdate(int bufferIndex) {
        getDataBufferLock(bufferIndex).writeLock().unlock();

    }

    /**
     * 释放缓冲区锁（不标记为脏）。
     *
     * @param bufferIndex 缓冲区索引
     */
    protected void unlockBuffer(long bufferIndex) {
        getDataBufferLock(bufferIndex).writeLock().unlock();

    }
    
     /**
     * 将内存所有数据同步到底层存储。
     * 确保数据的持久性。
     */
    public void syncStore(){
        
    }
    
    /**
     * 从底层存储加载数据到内存
     * 确保数据的持久性。
     */
    public void syncLoad(){
        
    }

}
