package ds;

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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于内存映射文件(Memory Mapped Files, MappedByteBuffer)的持久化数据结构基类。
 * <p>
 * 本类主要负责底层的文件I/O操作和内存管理，为上层数据结构（如 {@link DsList}, {@link DsHashSet}）提供支持。
 * 主要功能包括：
 * <ul>
 *     <li><b>文件I/O管理：</b> 通过 {@link RandomAccessFile} 和 {@link FileChannel} 进行文件的读写操作。</li>
 *     <li><b>内存映射：</b> 将文件分块（Block）映射到内存中，生成 {@link MappedByteBuffer}，实现高效的随机访问。</li>
 *     <li><b>基本类型读写：</b> 提供了针对 long, int, short, byte, float, double 等基本数据类型的读写方法。</li>
 *     <li><b>缓冲池管理：</b> 维护了一个 {@link MappedByteBuffer} 的缓存池 (`datatBuffers`)，减少重复映射的开销。</li>
 *     <li><b>脏页管理：</b> 跟踪修改过的缓冲区 (`dirtyBuffers`)，并提供 {@link #sync()} 方法将数据持久化到磁盘。</li>
 *     <li><b>并发控制：</b> 提供了基础的锁机制（如 `headerOpLock`），并支持子类实现更细粒度的并发控制。</li>
 * </ul>
 * </p>
 */
public class DsObject {
    
    /** 启用分区(同一服务器分表空间(不同硬盘)存储,以纵向扩展/并行读写提高性能) */
    protected boolean isPatitioned = false;
    
    /** 启用分布式(不同服务器存储,以横向扩展/并行读写提高性能) */
    protected boolean isDistributed = false;
    
    protected static final Charset UTF_8 = Charset.forName("UTF-8");
    protected static final int LONG_SIZE = 8;
    protected static final int INT_SIZE = 4;
    protected static final int MD5_SIZE = 16;
    /** 计算 Ds128SuperInode 的字节大小: 8*10 + 4*2 + 16 */
    protected static final int SIZE = 8 * 10 + 4 * 2 + 16; 
    
    /** 默认块尺寸 64KB -> 对应单个 MappedByteBuffer 的大小 */
    protected static final int BLOCK_SIZE = 64*1024;
    /** 用于初始化新块的零字节数组 */
    protected static final byte[] ZERO_BLOCK_BYTES = new byte[BLOCK_SIZE];
    
    // TODO: 实现缓存淘汰策略 (如 LRU)，防止大文件加载导致内存溢出 (OOM)。
    // 当前实现中，所有加载的缓冲区都会保留在内存中，直到显式卸载。
    /** 数据缓冲区缓存池：Key为块索引(bufferIndex)，Value为映射的内存缓冲区 */
    protected Map<Long,MappedByteBuffer> datatBuffers = new ConcurrentHashMap<>();
    
    /** 脏缓冲区集合：记录所有被修改但尚未同步到磁盘的缓冲区索引 */
    protected Set<Long> dirtyBuffers = new HashSet();
    
    // TODO: 考虑统一 datatBuffers 和 frameBuffers 的管理逻辑。
    /** 帧缓冲区缓存池：用于存储非标准块大小的数据帧 */
    protected Map<Long,MappedByteBuffer> frameBuffers = new ConcurrentHashMap<>();

    /** 脏帧缓冲区集合 */
    protected Set<Long> dirtyFrameBuffers = new HashSet();

    /** 数据缓冲区锁：用于控制对特定缓冲区的并发访问 */
    protected Map<Long,ReentrantLock> datatBufferLocks = new ConcurrentHashMap<>();
    
    //protected Map<Long,Long> datatBufferLastModified = new ConcurrentHashMap<>();//buffer最后修改时间

    /** 默认的锁池大小 */
    protected static final int DEFAULT_LOCK_POOL_SIZE = 50;

    /** ID锁池：预先创建的一组锁，用于减少频繁创建/销毁锁对象的开销。容量通常对应批处理大小。 */
    protected ArrayBlockingQueue<ReentrantLock> idLockPool = new ArrayBlockingQueue<>(DEFAULT_LOCK_POOL_SIZE);


    protected ReentrantLock idOpLock = new ReentrantLock();

    protected Map<Long,ReentrantLock> idLocks = new ConcurrentHashMap<>();
    
    /** 底层数据文件 */
    protected File dataFile;

    protected MappedByteBuffer headerBuffer;

    /** 头信息操作锁：用于保护文件头部的并发修改 */
    protected ReentrantLock headerOpLock = new ReentrantLock();
    
    /** 默认数据单元大小 */
    public int dataUnitSize;
    
    private DsObject systemNames;
    
    private DsObject freeBlocks;
    
    private DsObject strData;
    
    private DsObject binData;
    
    /** 同步操作锁：用于 sync() 方法 */
    protected ReentrantLock syncOpLock = new ReentrantLock();
    /** 缓冲区操作锁 */
    protected ReentrantLock bufferLock = new ReentrantLock();

    /**
     * 构造函数
     * @param dataFile 数据文件对象
     * @param dataUnitSize 数据单元大小（字节）
     */
    public DsObject(File dataFile, int dataUnitSize) {
        this.dataFile = dataFile;
        this.dataUnitSize = dataUnitSize;
        // 初始化锁池
        for(int i=0;i<DEFAULT_LOCK_POOL_SIZE;i++){
//        for(int i=0;i<5000;i++){
            idLockPool.add(new ReentrantLock());
        }
    }
    

    /**
     * 将 SuperInode 写入文件
     * @param inode 索引节点对象
     * @param filePath 文件路径
     * @throws IOException IO异常
     */
    public static void writeToFile(Ds128SuperInode inode, String filePath) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(new File(filePath), "rw");
             FileChannel channel = file.getChannel()) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, SIZE);
            inode.writeToMappedByteBuffer(buffer);
        }
    }

    /**
     * 从文件读取 SuperInode
     * @param filePath 文件路径
     * @return 索引节点对象
     * @throws IOException IO异常
     */
    public static Ds128SuperInode readFromFile(String filePath) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(new File(filePath), "r");
             FileChannel channel = file.getChannel()) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, SIZE);
            Ds128SuperInode inode = new Ds128SuperInode();
            inode.readFromMappedByteBuffer(buffer);
            return inode;
        }
    }

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
     * @param id 数据ID (用于计算块索引)
     * @param position 块内偏移量
     * @param value 值
     * @throws IOException IO异常
     */
    public void writeLong(long id,int position, long value) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        buffer.putLong(offset, value);
        dirty(bufferIndex);
    }

    /**
     * 在指定绝对位置存储 long 类型数据
     * @param position 绝对字节偏移量
     * @param value 值
     * @throws IOException IO异常
     */
    public void storeLong(long position, long value) throws IOException {
       Long bufferIndex = position/BLOCK_SIZE;
        int offset = (int) (position%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        buffer.putLong(offset, value);
        dirty(bufferIndex);
    }
    
   /**
    * 读取 long 类型数据
    * @param id 数据ID
    * @param position 块内偏移量
    * @return 值
    * @throws IOException IO异常
    */
   public long readLong(long id,int position) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        return buffer.getLong(offset);
        
    }

    /**
     * 从指定绝对位置读取 long 类型数据
     * @param position 绝对字节偏移量
     * @return 值
     * @throws IOException IO异常
     */
    public long loadLong(long position) throws IOException {
        Long bufferIndex = position/BLOCK_SIZE;
        int offset = (int) (position%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        return buffer.getLong(offset);

    }
   
    /**
     * 写入 int 类型数据
     * @param id 数据ID
     * @param position 块内偏移量
     * @param value 值
     * @throws IOException IO异常
     */
    public void writeInt(long id,int position, int value) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        buffer.putInt(offset, value);
        dirty(bufferIndex);
    }
    
   /**
    * 读取 int 类型数据
    * @param id 数据ID
    * @param position 块内偏移量
    * @return 值
    * @throws IOException IO异常
    */
   public int readInt(long id,int position) throws IOException {
        long index = id*dataUnitSize+position;
        long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        return buffer.getInt(offset);
        
    }

    /**
     * 在指定绝对位置存储 int 类型数据
     * @param position 绝对字节偏移量
     * @param value 值
     * @throws IOException IO异常
     */
    public void storeInt(long position, int value) throws IOException {
        Long bufferIndex = position/BLOCK_SIZE;
        int offset = (int) (position%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        buffer.putInt(offset, value);
        dirty(bufferIndex);
    }

    /**
     * 从指定绝对位置读取 int 类型数据
     * @param position 绝对字节偏移量
     * @return 值
     * @throws IOException IO异常
     */
    public int loadInt(long position) throws IOException {
        long bufferIndex = position/BLOCK_SIZE;
        int offset = (int) (position%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        return buffer.getInt(offset);

    }

    /**
     * 从给定的缓冲区数组中读取 int 数据（支持跨缓冲区）
     * @param position 绝对字节偏移量
     * @param buffers 缓冲区数组
     * @return 值
     * @throws IOException IO异常
     */
    public int readInt(int position,MappedByteBuffer... buffers) throws IOException {
        int bufferIndex = position/BLOCK_SIZE;
        int offset = (int) (position%BLOCK_SIZE);
        MappedByteBuffer buffer = buffers[bufferIndex];;
        return buffer.getInt(offset);
    }

    /**
     * 从给定的缓冲区数组中读取 long 数据
     * @param position 绝对字节偏移量
     * @param buffers 缓冲区数组
     * @return 值
     * @throws IOException IO异常
     */
    public long readLong(int position,MappedByteBuffer... buffers) throws IOException {
        int bufferIndex = position/BLOCK_SIZE;
        int offset = (int) (position%BLOCK_SIZE);
        MappedByteBuffer buffer = buffers[bufferIndex];;
        return buffer.getLong(offset);
    }

    /**
     * 向给定的缓冲区数组写入 int 数据
     * @param position 绝对字节偏移量
     * @param value 值
     * @param buffers 缓冲区数组
     * @return 缓冲区索引
     * @throws IOException IO异常
     */
    public int writeInt(int position,int value,MappedByteBuffer... buffers) throws IOException {
        int bufferIndex = position/BLOCK_SIZE;
        int offset = (int) (position%BLOCK_SIZE);
        MappedByteBuffer buffer = buffers[bufferIndex];;
        buffer.putInt(offset,value);
        return bufferIndex;
    }

    /**
     * 向给定的缓冲区数组写入 long 数据
     * @param position 绝对字节偏移量
     * @param value 值
     * @param buffers 缓冲区数组
     * @return 缓冲区索引
     * @throws IOException IO异常
     */
    public int writeLong(int position,long value,MappedByteBuffer... buffers) throws IOException {
        int bufferIndex = position/BLOCK_SIZE;
        int offset = (int) (position%BLOCK_SIZE);
        MappedByteBuffer buffer = buffers[bufferIndex];;
        buffer.putLong(offset,value);
        return bufferIndex;
    }
   
   /**
    * 写入 short 类型数据
    */
   public void writeShort(long id,int position, short value) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        buffer.putShort(offset, value);
        dirty(bufferIndex);
    }
    
   /**
    * 读取 short 类型数据
    */
   public short readShort(long id,int position) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        return buffer.getShort(offset);
        
    }
   
   /**
    * 写入 float 类型数据
    */
   public void writeFloat(long id,int position, float value) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        buffer.putFloat(offset, value);
        dirty(bufferIndex);
    }
    
   /**
    * 读取 float 类型数据
    */
   public float readFloat(long id,int position) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        return buffer.getFloat(offset);
    }
   
   /**
    * 写入 double 类型数据
    */
   public void writeDouble(long id,int position, double value) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        buffer.putDouble(offset, value);
        dirty(bufferIndex);
    }
    
   /**
    * 读取 double 类型数据
    */
   public double readDouble(long id,int position) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        return buffer.getDouble(offset);
        
    }
   
   /**
    * 写入 byte 类型数据
    */
   public void writeByte(long id,int position, byte value) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        buffer.put(offset, value);
        dirty(bufferIndex);
    }
    
   /**
    * 读取 byte 类型数据
    */
   public byte readByte(long id,int position) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        return buffer.get(offset);
        
    }
   
   /**
    * 写入字节数组
    * @throws DsDataOverFlowException 如果写入超出块边界
    */
   public void writeBytes(long id,int position, byte[] value) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        if((offset+value.length)<=BLOCK_SIZE){
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            buffer.put(offset, value);
            dirty(bufferIndex);
        }else{
            throw new DsDataOverFlowException();
        }
    }
    
   /**
    * 读取字节数组
    * @throws DsDataReadingLessThanException 如果读取超出块边界
    */
   public void readBytes(long id,int position, byte[] out) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        if((offset+out.length)<=BLOCK_SIZE){
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            buffer.get(offset,out);
        }else{
            throw new DsDataReadingLessThanException();
        }
        
    }
   
   /**
    * 写入字节数组的部分内容
    */
   public void writeBytes(long id,int position, byte[] value,int offsetIn,int count) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        if((offset+count)<=BLOCK_SIZE){
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            buffer.put(offset, value,offsetIn,count);
            dirty(bufferIndex);
        }else{
            throw new DsDataOverFlowException();
        }
    }
    
   /**
    * 读取数据到字节数组的部分位置
    */
   public void readBytes(long id,int position, byte[] out,int offsetOut,int count) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        if((offset+count)<=BLOCK_SIZE){
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            buffer.get(offset,out,offsetOut,count);
        }else{
            throw new DsDataOverFlowException();
        }
        
    }
   
   /**
    * 写入 UTF-8 字符串
    */
   public void writeUtf8(long id,int position, String value,int offsetIn,int count) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        byte[] data = value.getBytes(UTF_8);
        if((offset+count)<=BLOCK_SIZE){
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
           // buffer.put(offset, value,offsetIn,count);
            dirty(bufferIndex);
        }else{
            throw new DsDataOverFlowException();
        }
    }
    
   /**
    * 读取 UTF-8 字符串
    */
   public void readUtf8(long id,int position, byte[] out,int offsetOut,int count) throws IOException {
        long index = id*dataUnitSize+position;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        if((offset+count)<=BLOCK_SIZE){
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            buffer.get(offset,out,offsetOut,count);
        }else{
            throw new DsDataOverFlowException();
        }
        
    }
   
   /**
    * 写入整个数据单元
    */
   public void writeUnit(long id, byte[] value,int offsetIn) throws IOException {
        long index = id*dataUnitSize;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        if((offset+dataUnitSize)<=BLOCK_SIZE){
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            buffer.put(offset, value,offsetIn,dataUnitSize);
            dirty(bufferIndex);
        }else{
            throw new DsDataOverFlowException();
        }
    }
    
   /**
    * 读取整个数据单元
    */
   public void readUnit(long id, byte[] out,int offsetOut) throws IOException {
        long index = id*dataUnitSize;
        Long bufferIndex = index/BLOCK_SIZE;
        int offset = (int) (index%BLOCK_SIZE);
        if((offset+dataUnitSize)<=BLOCK_SIZE){
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            buffer.get(offset,out,offsetOut,dataUnitSize);
        }else{
            throw new DsDataOverFlowException();
        }
        
    }
    
    /**
     * 加载指定索引的块(buffer)到内存。
     * 如果文件不够大，会自动扩展文件大小。
     *
     * @param bufferIndex 块索引 (0-based)
     * @return 映射的 MappedByteBuffer
     * @throws IOException IO异常
     */
    protected MappedByteBuffer loadBuffer(Long bufferIndex) throws IOException {
            MappedByteBuffer buffer = datatBuffers.get(bufferIndex);
            if (buffer != null) {
                return buffer;
            }
            try (RandomAccessFile file = new RandomAccessFile(dataFile, "rw");
                 FileChannel channel = file.getChannel()) {
                long position = bufferIndex*BLOCK_SIZE;
                if(file.length()>=position+BLOCK_SIZE){
                    // 映射现有文件区域
                    buffer = channel.map(FileChannel.MapMode.READ_WRITE, bufferIndex*BLOCK_SIZE, BLOCK_SIZE);
                }else{//初始化并新增数据区
                    file.setLength(position+BLOCK_SIZE);
                    buffer = channel.map(FileChannel.MapMode.READ_WRITE, bufferIndex*BLOCK_SIZE, BLOCK_SIZE);
                    buffer.put(ZERO_BLOCK_BYTES); // 填零初始化
                    buffer.position(0); // 重置 position 为 0
                }

                datatBuffers.put(bufferIndex, buffer);
                return buffer;
            }

    }

    /**
     * 卸载指定的缓冲区，并确保数据同步到磁盘。
     * @param bufferIndex 缓冲区索引
     * @throws IOException IO异常
     */
    protected void unloadBuffer(Long bufferIndex) throws IOException {
        MappedByteBuffer buffer = datatBuffers.get(bufferIndex);
        if (buffer != null) {
            sync();
            unloadBuffer(buffer);
        }

    }

    /**
     * 卸载指定的帧缓冲区。
     * @param position 帧起始位置
     * @throws IOException IO异常
     */
    protected void unloadFrame(Long position) throws IOException {
        MappedByteBuffer buffer = frameBuffers.get(position);
        if (buffer != null) {
            sync();
            unloadBuffer(buffer);
        }

    }

    /**
     * 加载指定位置和长度的数据帧。
     * 类似于 loadBuffer，但支持非标准块大小。
     * @param position 起始位置
     * @param length 长度
     * @return 映射的缓冲区
     * @throws IOException IO异常
     */
    protected MappedByteBuffer loadFrame(long position, int length) throws IOException {
        MappedByteBuffer buffer = frameBuffers.get(position);
        if (buffer != null) {
            return buffer;
        }
        try (RandomAccessFile file = new RandomAccessFile(dataFile, "rw");
             FileChannel channel = file.getChannel()) {
            long size = position+length;
            if(file.length()>=size){
                buffer = channel.map(FileChannel.MapMode.READ_WRITE, position, length);
            }else{//初始化并新增数据区
                long oldSize = file.length();
                file.setLength(size);
                buffer = channel.map(FileChannel.MapMode.READ_WRITE, position, length);
               int count = length/BLOCK_SIZE;
               int rest = length%BLOCK_SIZE;
               //初始化新增数据区
                for(int i=0;i<count;i++){
                    buffer.put(ZERO_BLOCK_BYTES);
                }
                if(rest>0){
                    byte[] data = new byte[rest];
                    buffer.put(data);
                }
                // 如果是扩展文件，保留原有数据（如果这部分逻辑是用于迁移数据的话）
                if(position>oldSize){
                    byte[] data = new byte[(int) (position-oldSize)];
                    try (
                            ByteArrayInputStream in = new ByteArrayInputStream(data);
                            ReadableByteChannel rc = Channels.newChannel(in);) {
                        channel.transferFrom(channel, oldSize, data.length);
                    }

                }
            }
            frameBuffers.put(position, buffer);
            return buffer;
        }

    }

    /**
     * 加载缓冲区用于更新，并加锁。
     * @param bufferIndex 缓冲区索引
     * @return 映射的缓冲区
     * @throws IOException IO异常
     */
    protected MappedByteBuffer loadBufferForUpdate(Long bufferIndex) throws IOException {
        ReentrantLock lock = null;
        try {
            lock = datatBufferLocks.get(bufferIndex);
            if(lock==null){
                lock = new ReentrantLock();
                datatBufferLocks.put(bufferIndex, lock);
            }
            //System.out.println("loadBuffer bufferIndex="+bufferIndex+" lock->"+lock.isLocked());
            lock.lock();
            MappedByteBuffer buffer = datatBuffers.get(bufferIndex);
            if (buffer != null) {
                return buffer;
            }
            try (RandomAccessFile file = new RandomAccessFile(dataFile, "rw");
                 FileChannel channel = file.getChannel()) {
                long position = bufferIndex*BLOCK_SIZE;
                if(file.length()>=position+BLOCK_SIZE){

                    buffer = channel.map(FileChannel.MapMode.READ_WRITE, bufferIndex*BLOCK_SIZE, BLOCK_SIZE);
                }else{//初始化并新增数据区
                    file.setLength(position+BLOCK_SIZE);
                    //file.seek(position);
                    //file.write(0);
                    buffer = channel.map(FileChannel.MapMode.READ_WRITE, bufferIndex*BLOCK_SIZE, BLOCK_SIZE);
                    buffer.put(ZERO_BLOCK_BYTES);
                    buffer.position(0); // 重置 position 为 0
                }

                datatBuffers.put(bufferIndex, buffer);
                return buffer;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * 释放更新锁，并将缓冲区标记为脏（需要同步）。
     * @param bufferIndex 缓冲区索引
     */
    protected void unlockBufferForUpdate(Long bufferIndex)  {
        ReentrantLock lock = null;
        try {
            dirtyBuffers.add(bufferIndex);
            lock = datatBufferLocks.get(bufferIndex);
        }finally {
            if(lock!=null){
                lock.unlock();
                //System.out.println("finally loadBuffer bufferIndex="+bufferIndex+" lock->"+lock.isLocked());
            }

        }

    }

    /**
     * 释放缓冲区锁（不标记为脏）。
     * @param bufferIndex 缓冲区索引
     */
    protected void unlockBuffer(Long bufferIndex)  {
        ReentrantLock lock = null;
        try {
            lock = datatBufferLocks.get(bufferIndex);
        }finally {
            if(lock!=null){
                lock.unlock();
                //System.out.println("finally loadBuffer bufferIndex="+bufferIndex+" lock->"+lock.isLocked());
            }

        }

    }
    
      
    /**
     * 将所有脏缓冲区（dirty buffers）同步到磁盘。
     * 确保数据的持久性。
     */
    public void sync(){
        if (syncOpLock.tryLock()) {
            try {
                // TODO: 优化同步策略。目前遍历Set可能在大数据量下较慢。
                // 考虑批量处理或异步同步。
                for(Long i:dirtyBuffers){
                    MappedByteBuffer buffer = datatBuffers.get(i);
                    if(buffer!=null){
                        buffer.force();
                    }
                }
                dirtyBuffers.clear();
                for(Long i:dirtyFrameBuffers){
                    MappedByteBuffer buffer = frameBuffers.get(i);
                    if(buffer!=null){
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
    
    /**
     * 释放 MappedByteBuffer 占用的堆外内存。
     * <p>
     * <b>警告：</b> 此方法使用反射访问 JDK 内部 API。
     * 在较新版本的 JDK (9+) 中，如果未允许非法访问，可能会失败。
     * </p>
     * @param buffer 要释放的缓冲区
     */
    public void unloadBuffer(MappedByteBuffer buffer) {
        // TODO: 为 JDK 9+ 替换为更干净的实现 (例如 Unsafe.invokeCleaner)
        // 或者使用 MethodHandles (如果可用)。
        if(UNMAPPER_METHOD==null) {
            try {
                UNMAPPER_METHOD = MappedByteBuffer.class.getDeclaredMethod("unmapper");
                UNMAPPER_METHOD.setAccessible(true);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("无法访问 unmapper 方法", e);
            }

        }
        try {
            ( (UnmapperProxy) UNMAPPER_METHOD.invoke( buffer)).unmap();
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("无法释放缓冲区", e);
        }

    }
}
