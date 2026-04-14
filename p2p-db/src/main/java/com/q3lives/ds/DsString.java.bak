package ds;

import cn.hutool.core.lang.Pair;

import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
//import org.apache.commons.lang3.tuple.Pair;

/**
 * 持久化字符串/字节数组存储类。
 * <p>
 * 采用分离存储策略：
 * <ul>
 *     <li><b>索引区 ({@link DsDataIndex}):</b> 存储字符串的元数据（ID、长度、偏移量、哈希值等）。</li>
 *     <li><b>数据区 (本类):</b> 存储实际的字节内容。支持跨块 (Block) 存储变长数据。</li>
 * </ul>
 * </p>
 */
public class DsString extends DsObject{

    private final static ReentrantLock LOCAL_LOCK = new ReentrantLock();

    private final String magic = ".STR";
    private int total = 0;

    /** 下一个可用的数据偏移量 */
    private long nextOffset = 0l;

    /** 关联的数据索引对象 */
    private DsDataIndex indexOffset;


    public DsString(File dataFile, DsDataIndex indexOffset) {
        super(dataFile,64);
        checkHeader();
        this.indexOffset = indexOffset;
    }

    public void checkHeader()  {

        // 加载缓冲区
        //System.out.println("bufferIndex="+bufferIndex+" offset="+offset);

        try {
            headerBuffer = this.loadBuffer(0l);
            byte[] magicBytes = new byte[4];
            headerBuffer.get(0, magicBytes, 0, 4);
            if( Arrays.compare(magicBytes, magic.getBytes())==0){
                total = headerBuffer.getInt(4);
                nextOffset = headerBuffer.getLong(8);
            }else{
                // TODO: 锁定缓冲区
                headerBuffer.put(0,magic.getBytes(),0,4);
                headerBuffer.putInt(4,0);
                headerBuffer.putLong(8, (long) this.dataUnitSize); // 起始偏移量应跳过头部
                nextOffset = this.dataUnitSize;
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public final void updateHeader(int count,int size)  {
        headerOpLock.lock();
        try {
            total += count;
            nextOffset += size;
            headerBuffer.putInt(4,total);
            headerBuffer.putLong(8, nextOffset);
            dirty(0l);
        }finally {
            headerOpLock.unlock();
        }

    }

    public final void minusTotal(int count)  {
        headerOpLock.lock();
        try {
            total -= count;
            headerBuffer.putInt(4,total);
            dirtyBuffers.add(0l);
        }finally {
            headerOpLock.unlock();
        }

    }

    /**
     * 添加字符串。
     * @param str 要存储的字符串
     * @return 存储的 ID
     * @throws IOException IO异常
     * @throws InterruptedException 中断异常
     */
    public long add(String str) throws IOException, InterruptedException {
        byte[] bytes = str.getBytes(UTF_8);
        return add(bytes);
    }

    /**
     * 添加字节数组。
     * @param bytes 要存储的字节数组
     * @return 存储的 ID
     * @throws IOException IO异常
     * @throws InterruptedException 中断异常
     */
    public long add(byte[] bytes) throws IOException, InterruptedException {
        int size = (bytes.length%this.dataUnitSize==0?bytes.length/this.dataUnitSize:bytes.length/this.dataUnitSize+1)*this.dataUnitSize;
        Pair<ReentrantLock,Long> pair = takeNewIdLock(size);
        ReentrantLock lock =  pair.getKey();
        lock.lock();
        long offset = pair.getValue();
        try {
            //TODO: 通过哈希搜索 - 查询已有字符串以实现复用
            //long id = search(str);
            long id = indexOffset.add((short)1, (short) bytes.length,(int)offset,DsDataUtil.hash64ForString(bytes));
            updateByOffset(offset,bytes);
            return id;
        } finally {
            idLocks.remove(offset);
            lock.unlock();
            idLockPool.offer(lock);
        }

    }

    public Pair<ReentrantLock,Long> takeNewIdLock(int size) throws InterruptedException {
        idOpLock.lock();
        long offset = nextOffset;
        updateHeader(1,size);
        ReentrantLock lock = idLocks.get(offset);
        try {
            if( lock == null) {
                lock = idLockPool.take();
                idLocks.put(offset, lock);
            }
        } finally {
            idOpLock.unlock();
        }
        return Pair.of(lock, offset);
    }

    /**
     * 更新指定 ID 的字符串。
     */
    public int update(long id, String str) throws IOException {
        return update(id, str.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 更新指定 ID 的字节数组数据。
     */
    public int update(long id, byte[] data) throws IOException {

        DsDataIndexNode node = indexOffset.get(id);
        if(node.size == 0){
            throw new IOException("旧数据大小无效");
        }
        int size = (node.size%this.dataUnitSize==0?node.size/this.dataUnitSize:node.size/this.dataUnitSize+1)*this.dataUnitSize;
        if(data.length > size){
            throw new IOException("数据大小超出原分配空间");
        }
        byte[] dataOld = new byte[node.size];
        readByOffset(node.offset, dataOld);
        long hash = DsDataUtil.hash64ForString(dataOld);
        if(hash != node.hash){
            throw new IOException("旧数据无效: 哈希值不匹配");
        }
        node.size = (short) data.length;
        node.hash = DsDataUtil.hash64ForString(data);
        updateByOffset(node.offset, data);
        indexOffset.update(id, node);
        return 1;

    }

    /**
     * 根据物理偏移量写入数据（支持跨块写入）。
     */
    private int updateByOffset(long offsetIn, byte[] data) throws IOException {

        // 计算缓冲区索引
        Long bufferIndex = offsetIn/BLOCK_SIZE;
        // 计算缓冲区内的偏移量
        int offset = (int) (offsetIn%BLOCK_SIZE);
        // 加载缓冲区
        //System.out.println("bufferIndex="+bufferIndex+" offset="+offset);
        MappedByteBuffer buffer = this.loadBuffer(bufferIndex);
        if((offset+data.length) > BLOCK_SIZE){// 跨越了块边界
            int first = BLOCK_SIZE - offset;
            int second = data.length - first;
            int rest = second%BLOCK_SIZE;
            int pages = (int) second/BLOCK_SIZE;
            if(rest != 0){
                pages++;
            }
            buffer.put(offset,data,0,first);
            dirty(bufferIndex);
            for(int i=0;i<pages;i++){
                bufferIndex++;
                buffer = this.loadBuffer(bufferIndex);
                buffer.put(0,data,first+i*BLOCK_SIZE,rest==0?BLOCK_SIZE:rest);
                dirty(bufferIndex);
            }
        }else{
            buffer.put(offset,data);
        }
        // TODO: 锁定缓冲区

        return 1;

    }

    /**
     * 根据物理偏移量读取数据（支持跨块读取）。
     */
    private void readByOffset(long offsetIn, byte[] data) throws IOException {

        // 计算缓冲区索引
        Long bufferIndex = offsetIn/BLOCK_SIZE;
        // 计算缓冲区内的偏移量
        int offset = (int) (offsetIn%BLOCK_SIZE);
        // 加载缓冲区
        //System.out.println("bufferIndex="+bufferIndex+" offset="+offset);
        MappedByteBuffer buffer = this.loadBuffer(bufferIndex);
        if((offset+data.length) > BLOCK_SIZE){// 跨越了块边界
            int first = BLOCK_SIZE - offset;
            int second = data.length - first;
            int rest = second%BLOCK_SIZE;
            int pages = (int) second/BLOCK_SIZE;
            if(rest != 0){
                pages++;
            }
            buffer.get(offset,data,0,first);

            for(int i=0;i<pages;i++){
                bufferIndex++;
                buffer = this.loadBuffer(bufferIndex);
                buffer.get(0,data,first+i*BLOCK_SIZE,rest==0?BLOCK_SIZE:rest);
            }
        }else{
            buffer.get(offset,data);
        }


    }



    public boolean remove(long id){


        return false;
    }
    
    /**
     * 根据 ID 获取字符串。
     * @param id 数据 ID
     * @return 字符串内容
     * @throws IOException IO异常
     */
    public String get(long id) throws IOException {
        DsDataIndexNode node = indexOffset.get(id);
        if(node.size == 0){
            throw new IOException("数据大小无效");
        }

        byte[] data = new byte[node.size];
        readByOffset(node.offset, data);

//        long hash = DsDataUtil.hash64ForString(data);
//        if(hash != node.hash){
//            throw new IOException("数据无效: 哈希值不匹配");
//        }
        return new String(data,UTF_8);
    }

    /**
     * 根据 ID 获取原始字节数组。
     */
    public byte[] readRaw(long id) throws IOException {
        DsDataIndexNode node = indexOffset.get(id);
        if(node.size == 0){
            throw new IOException("数据大小无效");
        }

        byte[] data = new byte[node.size];
        readByOffset(node.offset, data);
        return data;
    }




}
