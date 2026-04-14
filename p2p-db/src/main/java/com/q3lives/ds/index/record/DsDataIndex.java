package com.q3lives.ds.index.record;

import cn.hutool.core.lang.Pair;

import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
//import org.apache.commons.lang3.tuple.Pair;

import com.q3lives.ds.core.DsObject;

/**
 * 定长记录索引存储。
 * <p>
 * 每个记录 ({@link DsDataIndexNode}) 存储在根据其 ID 计算出的位置。
 * ID 充当主键/索引。
 * </p>
 * <p>
 * 记录结构 (16 字节):
 * <ul>
 *     <li>引用计数 (Ref Count): 2 字节</li>
 *     <li>大小 (Size): 2 字节</li>
 *     <li>偏移量 (Offset): 4 字节</li>
 *     <li>哈希值 (Hash): 8 字节</li>
 * </ul>
 * </p>
 */
public class DsDataIndex extends DsObject{

    private final static ReentrantLock LOCAL_LOCK = new ReentrantLock();

    private final String magic = ".IDX";
    
    /** 魔数和 Total 的长度偏移 */
    public static final int HEADER_MAGIC_LEN = 4;
    public static final int HEADER_OFFSET_TOTAL = 4;
    public static final int HEADER_OFFSET_NEXT_VAL = 8;
    
    private int total = 0;

    private long nextVal = 1l;
    
    
    /**
     * 创建一个定长索引文件。
     *
     * <p>每条记录固定 16B；header 保存 total 与 nextVal。</p>
     */
    public DsDataIndex(File dataFile) {
        super(dataFile,16);
        checkHeader();
    }

   private void checkHeader()  {

       // 加载缓冲区
       //System.out.println("bufferIndex="+bufferIndex+" offset="+offset);

       try {
           headerBuffer = this.loadBuffer(0l);

           byte[] magicBytes = new byte[HEADER_MAGIC_LEN];
           headerBuffer.get(0, magicBytes, 0, HEADER_MAGIC_LEN);
          if( Arrays.compare(magicBytes, magic.getBytes())==0){
              total = headerBuffer.getInt(HEADER_OFFSET_TOTAL);
              nextVal = headerBuffer.getLong(HEADER_OFFSET_NEXT_VAL);
          }else{
              // TODO: 锁定缓冲区
              headerBuffer.put(0,magic.getBytes(),0,HEADER_MAGIC_LEN);
              headerBuffer.putInt(HEADER_OFFSET_TOTAL,0);
              headerBuffer.putLong(HEADER_OFFSET_NEXT_VAL,1);
          }
       } catch (IOException e) {
           throw new RuntimeException(e);
       }
       
   }

    /**
     * 递增 total 与 nextVal，并同步写回 header。
     *
     * <p>用于分配新 id 时更新全局计数。</p>
     */
    public final void updateHeader(int count)  {
        headerOpLock.lock();
        try {
            total += count;
            nextVal += count;
            headerBuffer.putInt(HEADER_OFFSET_TOTAL,total);
            headerBuffer.putLong(HEADER_OFFSET_NEXT_VAL,nextVal);
            dirty(0l);
        }finally {
            headerOpLock.unlock();
        }

    }

    /**
     * 仅递减 total 并写回 header（不改变 nextVal）。
     */
    public final void minusTotal(int count)  {
        headerOpLock.lock();
        try {
            total -= count;
            headerBuffer.putInt(HEADER_OFFSET_TOTAL,total);
            dirtyBuffers.add(0l);
        }finally {
            headerOpLock.unlock();
        }

    }

    /**
     * 分配一个新 id 并写入一条索引记录。
     *
     * @return 新分配的 id
     */
    public long add(short refCount, short size, int offset, long hash) throws IOException, InterruptedException {
        Pair<ReentrantLock,Long> pair = takeNewIdLock();
        ReentrantLock lock =  pair.getKey();
        lock.lock();
        long id = pair.getValue();
        try {
            update(id,refCount,size,offset,hash);
        } finally {
            idLocks.remove(id);
            lock.unlock();
            idLockPool.offer(lock);
        }

        return id;
    }

    /**
     * 分配一个新 id，并返回该 id 对应的写入锁。
     *
     * <p>调用方需负责在写入完成后释放锁并归还到池中。</p>
     */
    public Pair<ReentrantLock,Long> takeNewIdLock() throws InterruptedException {
        idOpLock.lock();
        long id = nextVal;
        updateHeader(1);
        ReentrantLock lock = idLocks.get(id);
        try {
            if( lock == null) {
                lock = idLockPool.take();
                idLocks.put(id, lock);
            }
        } finally {
            idOpLock.unlock();
        }
        return Pair.of(lock, id);
    }

    /**
     * 获取下一个将要分配的 ID（即当前最大 ID + 1）。
     * @return nextVal
     */
    public long getNextVal() {
        return nextVal;
    }

    /** 记录结构中的偏移量常量 */
    public static final int RECORD_OFFSET_SIZE = 2;
    public static final int RECORD_OFFSET_OFFSET = 4;
    public static final int RECORD_OFFSET_HASH = 8;
    
    /**
     * 更新指定 ID 的记录。
     * @param id 记录 ID
     * @param refCount 引用计数
     * @param size 数据大小
     * @param offsetIn 偏移量值
     * @param hash 数据哈希值
     * @return 成功返回 1
     * @throws IOException 如果发生 IO 错误
     */
    public int update(long id, short refCount, short size, int offsetIn, long hash) throws IOException {
        // 计算数据单元在缓冲区中的索引
        long index = id*this.dataUnitSize;
        // 计算缓冲区索引
        Long bufferIndex = index/BLOCK_SIZE;
        // 计算缓冲区内的偏移量
        int offset = (int) (index%BLOCK_SIZE);
        // 加载缓冲区
        //System.out.println("bufferIndex="+bufferIndex+" offset="+offset);
        MappedByteBuffer buffer = this.loadBuffer(bufferIndex);
        
        // TODO: 锁定缓冲区以确保写入期间的线程安全
        // 目前依赖外部锁定或单线程访问来保证安全。
        
        buffer.putShort(offset,refCount);
        buffer.putShort(offset+RECORD_OFFSET_SIZE,size);
        buffer.putInt(offset+RECORD_OFFSET_OFFSET,offsetIn); // 修正: 使用 offsetIn
        buffer.putLong(offset+RECORD_OFFSET_HASH,hash);
        dirty(bufferIndex);
        return 1;

    }

    public int update(long id, DsDataIndexNode node) throws IOException {
        // 计算数据单元在缓冲区中的索引
        long index = id*this.dataUnitSize;
        // 计算缓冲区索引
        Long bufferIndex = index/BLOCK_SIZE;
        // 计算缓冲区内的偏移量
        int offset = (int) (index%BLOCK_SIZE);
        // 加载缓冲区
        //System.out.println("bufferIndex="+bufferIndex+" offset="+offset);
        MappedByteBuffer buffer = this.loadBuffer(bufferIndex);
        // TODO: 锁定缓冲区
        buffer.putShort(offset,node.refCount);
        buffer.putShort(offset+RECORD_OFFSET_SIZE,node.size);
        buffer.putInt(offset+RECORD_OFFSET_OFFSET,node.offset);
        buffer.putLong(offset+RECORD_OFFSET_HASH,node.hash);
        dirty(bufferIndex);
        return 1;

    }

   /**
    * 删除一条索引记录（当前实现保留接口，未实现）。
    */
   public boolean remove(long id){


       return false;
   }
    /**
     * 根据 ID 获取记录。
     * @param id 记录 ID
     * @return 数据索引节点
     * @throws IOException 如果发生 IO 错误
     */
   public DsDataIndexNode get(long id) throws IOException {
       // 计算数据单元在缓冲区中的索引
       long index = id*this.dataUnitSize;
       // 计算缓冲区索引
        Long bufferIndex = index/BLOCK_SIZE;
       // 计算缓冲区内的偏移量
        int offset = (int) (index%BLOCK_SIZE);
       // 加载缓冲区
       //System.out.println("bufferIndex="+bufferIndex+" offset="+offset);
        MappedByteBuffer buffer = this.loadBuffer(bufferIndex);
       //System.out.println("bufferIndex="+bufferIndex+" offset="+offset+"::"+buffer.asIntBuffer());
       // 从缓冲区数据创建并返回新的 DsBlockIndexNode 对象
        return new DsDataIndexNode(buffer.getShort(offset),buffer.getShort(offset+RECORD_OFFSET_SIZE),buffer.getInt(offset+RECORD_OFFSET_OFFSET),buffer.getLong(offset+RECORD_OFFSET_HASH));
       
   }

   /**
    * 读取指定 id 的原始 16B 记录（不解析）。
    */
   public byte[] readRaw(long id) throws IOException {
        byte[] out = new byte[this.dataUnitSize];
        this.readBytes(id,0, out);
        return out;
    }

    /**
     * 覆盖写入指定 id 的原始 16B 记录（不解析）。
     */
    public void writeRaw(long id,byte[] value) throws IOException {
        if(this.dataUnitSize != value.length){
            throw new IOException("无效的数据单元大小");
        }
        this.writeBytes(id,0, value);
    }

    /**
     * 分配新 id 并写入 node。
     *
     * @return 新分配的 id
     */
    public long add(DsDataIndexNode node) throws IOException, InterruptedException {
        return this.add(node.refCount,node.size,node.offset,node.hash);
    }

}
