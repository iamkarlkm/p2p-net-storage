package ds;

import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 空闲ID管理类。
 * <p>
 * 用于管理已被释放的ID（或数据块索引），以便重复利用空间。
 * 本质上是一个持久化的链表或栈结构。
 * </p>
 */
public class DsFreeId extends DsObject{

    private final static ReentrantLock LOCAL_LOCK = new ReentrantLock();

    public DsFreeId(File dataFile) {
        super(dataFile,8);
    }

   /**
    * 添加一个空闲块节点。
    * @param id ID
    * @param node 节点信息
    * @return 成功返回 true
    * @throws IOException IO异常
    */
   public boolean add(long id,DsBlockIndexNode node) throws IOException {
       // 计算数据单元在缓冲区中的索引
       long index = id*this.dataUnitSize;
       // 计算缓冲区索引
       Long bufferIndex = index/BLOCK_SIZE;
       // 计算缓冲区内的偏移量
       int offset = (int) (index%BLOCK_SIZE);
       // 加载缓冲区
       // System.out.println("bufferIndex="+bufferIndex+" offset="+offset);
       MappedByteBuffer buffer = this.loadBuffer(bufferIndex);
       // TODO: 考虑加锁机制
       buffer.putShort(offset,node.ref_count);
       buffer.putLong(offset+2,node.offset);
       buffer.putLong(offset+4,node.size);
       buffer.putLong(offset+8,node.hash);
        return true;
       
   }
   
   public boolean remove(long id){
       return false;
   }

   /**
    * 获取指定ID的节点信息。
    * @param id ID
    * @return 节点对象
    * @throws IOException IO异常
    */
   public DsBlockIndexNode get(long id) throws IOException {
       // 计算数据单元在缓冲区中的索引
       long index = id*this.dataUnitSize;
       // 计算缓冲区索引
        Long bufferIndex = index/BLOCK_SIZE;
       // 计算缓冲区内的偏移量
        int offset = (int) (index%BLOCK_SIZE);
       // 加载缓冲区
       // System.out.println("bufferIndex="+bufferIndex+" offset="+offset);
        MappedByteBuffer buffer = this.loadBuffer(bufferIndex);
       // 从缓冲区读取数据并返回新的 DsBlockIndexNode 对象
        return new DsBlockIndexNode(buffer.getShort(offset),buffer.getShort(offset+2),buffer.getInt(offset+4),buffer.getLong(offset+8));
       
   }

   public byte[] readRaw(long id) throws IOException {
        byte[] out = new byte[this.dataUnitSize];
        this.readBytes(id,0, out);
        return out;
    }

    public void writeRaw(long id,byte[] value) throws IOException {
        if(this.dataUnitSize != value.length){
            throw new IOException("数据单元大小不匹配");
        }
        this.writeBytes(id,0, value);
    }
   
    /**
     * 数据块索引节点类。
     */
    static class DsBlockIndexNode {
        public short ref_count; // 引用计数
        public short size;      // 大小
        public int offset;      // 偏移量
        public long hash;       // 哈希值

        public DsBlockIndexNode(short ref_count, short size, int offset, long hash) {
            this.ref_count = ref_count;
            this.size = size;
            this.offset = offset;
            this.hash = hash;
        }
        
        public DsBlockIndexNode(short ref_count, short size, int offset, byte[] data) {
            this.ref_count = ref_count;
            this.size = size;
            this.offset = offset;
            this.hash = DsDataUtil.hash64(data);
        }
        
        public DsBlockIndexNode(short ref_count, short size, int offset, String str) {
            this.ref_count = ref_count;
            this.size = size;
            this.offset = offset;
            this.hash = DsDataUtil.hash64(str);
        }
        
    }
}
