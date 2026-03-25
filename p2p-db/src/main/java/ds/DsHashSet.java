package ds;

import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.ReentrantLock;
//import org.apache.commons.lang3.tuple.Pair;

/**
 * 基于内存映射文件(Memory Mapped Files)的持久化哈希集合(HashSet)实现。
 * <p>
 * <b>实现原理：</b>
 * 本类采用了多级哈希(Multi-level Hashing)和位图(Bitmap)技术，以支持高效的冲突解决和空间利用。
 * <ul>
 *     <li><b>多级哈希结构 (Multi-level Hashing):</b> 当发生哈希冲突时，不是使用链表或红黑树，而是动态分配一个新的下一级哈希表。
 *     这实际上形成了一个哈希树结构。每一级的哈希位移和掩码不同，确保数据均匀分布。</li>
 *     <li><b>位图状态管理 (Bitmaps):</b> 使用位图 (`isHasValueBitmap`) 来快速判断每个哈希桶(Bucket)的状态。
 *     每个桶占用位图中的 2 个比特位，表示以下三种状态：
 *         <ul>
 *             <li><b>3 (Value):</b> 桶中存储的是实际的数据值 (long)。(无冲突)</li>
 *             <li><b>2 (Pointer):</b> 桶中存储的是指向下一级哈希表的指针 (long)。(发生了冲突)</li>
 *             <li><b>0 (Empty):</b> 桶为空。</li>
 *         </ul>
 *     </li>
 * </ul>
 * </p>
 * <p>
 * <b>并发控制策略：</b>
 * <ul>
 *     <li><b>分段锁 (Segmented Locks):</b> 使用一个 {@link ReentrantLock} 数组 (`segmentLocks`) 将哈希空间划分为多个段（默认为 16 个）。
 *     对于 {@link #add(long)}, {@link #remove(long)}, {@link #contains(long)} 等操作，根据值的哈希结果只锁定对应的段，从而支持高并发访问。</li>
 *     <li><b>全局元数据锁:</b> 对于涉及全局计数器 (`total`) 和新内存区域分配 (`nextOffset`) 的操作，使用单独的 `headerOpLock` 进行保护，确保元数据一致性。</li>
 * </ul>
 * </p>
 */
public class DsHashSet extends DsObject{

    public static final int DEFAULT_SEGMENT_LOCKS_COUNT = 16;
    public static final int DEFAULT_ROOT_HEAD_SIZE = 20;
    public static final int MAGIC_STRING_LENGTH = 4;
    public static final String MAGIC_STRING = ".SET";
    public static final int TOTAL_OFFSET = 4;
    public static final int NEXT_OFFSET_POSITION = 8;
    public static final int ELEMENT_SIZE = 8;
    public static final int MAX_LEVEL = 7;


    private final static ReentrantLock LOCAL_LOCK = new ReentrantLock();

    private int total = 0;

    //TODO: 优化建议：将头部数据与内容数据分离。
    // 头部数据（位图、指针）访问频繁，可以存放在单独的文件或缓存中以提高缓存局部性。
    // 内容数据可以使用数组存储。
    // 考虑使用位图来存储是否存在索引/指针。


    private int rootHeadSize = DEFAULT_ROOT_HEAD_SIZE;//头大小


    //使用数组存储,使用位图存储是否存储值,使用位图存储是否存储索引/指针
     private int isHasValueBitmap = 0;//是否有值
     

    private final long[]  heads = new long[DEFAULT_SEGMENT_LOCKS_COUNT];//头指针

    private int headsOffset = rootHeadSize + MAGIC_STRING_LENGTH;//根数据偏移
    private long nextOffset = rootHeadSize + MAGIC_STRING_LENGTH+heads.length * ELEMENT_SIZE;//头存储区域之后,32字节对齐

    // 分段锁数组，用于细粒度并发控制
    private final ReentrantLock[] segmentLocks = new ReentrantLock[DEFAULT_SEGMENT_LOCKS_COUNT];
    {
        for (int i = 0; i < segmentLocks.length; i++) {
            segmentLocks[i] = new ReentrantLock();
        }
    }


    private MappedByteBuffer headerBuffer;
    // 头信息操作锁
    private final ReentrantLock headerOpLock = new ReentrantLock();

    private int[] levels = new int[]{4,10,10,10,10,10,10};//哈希层级（每级占用的比特数）
    private int[] levelsDataCount = new int[]{16,1024,1024,1024,1024,1024,1024};//哈希层级可存储数据量 (2^bits)
    private int[] levelsMask = new int[]{0xf,0x3ff,0x3ff,0x3ff,0x3ff,0x3ff,0x3ff};//哈希掩码
    private int[] levelsShift = new int[]{0,4,14,24,34,44,54};//哈希移位

    private int[] levelsAreaSize = new int[levels.length];//哈希层级区域大小
    private int[] levelsHeadSize = new int[levels.length];//哈希层级头大小
    // 计算区域大小
    
    private static int getHasValue(int data, int start, int count) {
        int shift = 32 - start - count;
        return (data >>> shift) & ((1 << count) - 1);
    }

    private static final int calcDataAreaSize(int bits){
        // 计算数据存储区域大小 (每个槽位 8 字节)
        return (1<<bits) * ELEMENT_SIZE;
    }

    // 计算头大小
    private static final int calcHeadSize(int bits){
        // 计算头大小 (位图所需字节数)
        //        if(bits == 4){//level 0
        //           return 4;
        //        }
        // 返回头大小
        return ((1<<bits)*2/32)*4;
    }

    public DsHashSet(File dataFile) {
        super(dataFile, ELEMENT_SIZE);//64位
        long rootOffset = rootHeadSize + MAGIC_STRING_LENGTH+heads.length * ELEMENT_SIZE;
        //实际校正根数据区偏移,以便对齐到8字节
        int x = rootHeadSize + MAGIC_STRING_LENGTH;
        if(x%8!=0){
            rootHeadSize = rootHeadSize+(x%8);//对齐到8字节
            headsOffset = rootHeadSize + MAGIC_STRING_LENGTH;//根数据偏移
            if(rootOffset == nextOffset){//根数据区偏移和下一个数据区偏移相同,则说明根数据区为空,需要调整下一个数据区偏移
                nextOffset = rootHeadSize + MAGIC_STRING_LENGTH+heads.length * ELEMENT_SIZE;
            }
        }
        checkHeader();

        for(int i=0;i<levels.length;i++){
            levelsAreaSize[i] = calcDataAreaSize(levels[i]);
        }
        for(int i=0;i<levels.length;i++){
            levelsHeadSize[i] = calcHeadSize(levels[i]);
        }
    }

   private void checkHeader()  {

       // Load the buffer
       //System.out.println("bufferIndex="+bufferIndex+" offset="+offset);
       boolean needUpdate = false;
       try {
           headerBuffer = this.loadBufferForUpdate(0L);

           byte[] magicBytes = new byte[MAGIC_STRING_LENGTH];
           headerBuffer.get(magicBytes, 0, MAGIC_STRING_LENGTH);
           String magic = MAGIC_STRING;
           if( Arrays.compare(magicBytes, magic.getBytes())==0){
              total = headerBuffer.getInt(TOTAL_OFFSET);
              nextOffset = headerBuffer.getLong(NEXT_OFFSET_POSITION);
              isHasValueBitmap = headerBuffer.getInt(rootHeadSize);
               headerBuffer.asLongBuffer().get(headsOffset>>3,heads);
          }else{
              headerBuffer.put(0, magic.getBytes(), 0, MAGIC_STRING_LENGTH);
              headerBuffer.putInt(TOTAL_OFFSET,0);
              headerBuffer.putLong(NEXT_OFFSET_POSITION, nextOffset);
              headerBuffer.putInt(rootHeadSize,isHasValueBitmap);
               headerBuffer.asLongBuffer().get(headsOffset>>3,heads);
               needUpdate = true;
          }
       } catch (IOException e) {
           throw new RuntimeException(e);
       }finally {
           if(needUpdate){
               unlockBufferForUpdate(0L);
           }else{
               unlockBuffer(0L);
           }

       }
       
   }

    public final void addTotal(int count)  {
        headerOpLock.lock();
        try {
            total += count;
            //nextOffset += count * ELEMENT_SIZE;
            headerBuffer.putInt(TOTAL_OFFSET,total);
            //headerBuffer.putLong(NEXT_OFFSET_POSITION, nextOffset);
            dirty(0l);
        }finally {
            headerOpLock.unlock();
        }

    }
    
    public final long updateHeaderForNewOffset(int count, int size, int hash)  {
        headerOpLock.lock();
        try {
            total += count;
            long base = nextOffset;
            nextOffset += size;

            headerBuffer.putInt(TOTAL_OFFSET,total);
            headerBuffer.putLong(NEXT_OFFSET_POSITION, nextOffset);
            isHasValueBitmap = DsDataUtil.setBit(isHasValueBitmap, hash*2);
            isHasValueBitmap = DsDataUtil.setBit(isHasValueBitmap, hash*2+1);
                headerBuffer.putInt(rootHeadSize,isHasValueBitmap);
//            isValueBitmap = DsDataUtil.setBit(isValueBitmap, hash);
            //headerBuffer.putInt(rootHeadSize + MAGIC_STRING_LENGTH,isValueBitmap);
            heads[hash] = base;
            headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE, base);
            dirty(0l);
            return base;
        }finally {
            headerOpLock.unlock();
        }
    }

    public final long updateHeaderWithOldValue(int level,int count,int size, int hash,long value)  {
        headerOpLock.lock();
        try {
            total += count;
            long base = nextOffset;
            nextOffset += size;
            headerBuffer.putInt(TOTAL_OFFSET,total);
            headerBuffer.putLong(NEXT_OFFSET_POSITION, nextOffset);
            if(level == 0){//第一层特殊处理
                heads[hash] = base;
                isHasValueBitmap = DsDataUtil.clearBit(isHasValueBitmap, hash*2+1);
                headerBuffer.putInt(rootHeadSize,isHasValueBitmap);
                headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE, base);
            }

            dirty(0l);
            return base;
        }finally {
            headerOpLock.unlock();
        }
    }

    public final void updateHeaderByValue(int count,int hash,long value)  {
        headerOpLock.lock();
        try {
            total += count;
            isHasValueBitmap = DsDataUtil.setBit(isHasValueBitmap, hash*2);
            isHasValueBitmap = DsDataUtil.setBit(isHasValueBitmap, hash*2+1);
            //isValueBitmap = DsDataUtil.setBit(isValueBitmap, hash);
            long offset = rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE;
            heads[hash] = value;
            headerBuffer.putInt(TOTAL_OFFSET, total).putInt(rootHeadSize,isHasValueBitmap)
                    .putLong((int) offset,value);
            //headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH * 2+hash * ELEMENT_SIZE, value);
            dirty(0l);
        }finally {
            headerOpLock.unlock();
        }
    }

    public final void minusTotal(int count)  {
        headerOpLock.lock();
        try {
            total -= count;
            headerBuffer.putInt(TOTAL_OFFSET,total);
            dirtyBuffers.add(0l);
        }finally {
            headerOpLock.unlock();
        }

    }

    public final void minusTotalByHash(int count,int hash)  {
        headerOpLock.lock();
        try {
            total -= count;
            headerBuffer.putInt(TOTAL_OFFSET,total);
            isHasValueBitmap = DsDataUtil.setBit(isHasValueBitmap, hash*2+1);
            //isValueBitmap = DsDataUtil.clearBit(isValueBitmap, hash);
            headerBuffer.putInt(rootHeadSize,isHasValueBitmap);
            heads[hash] = 0;
            headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE, 0);
            dirty(0l);
        }finally {
            headerOpLock.unlock();
        }
    }

    /**
     * 向集合中添加一个值。
     * @param value 要添加的值。
     * @return 如果集合中之前不包含该元素，则返回 true。
     * @throws IOException 如果发生 I/O 错误。
     * @throws InterruptedException 如果操作被中断。
     */
    public boolean add(long value) throws IOException, InterruptedException {
        // 初始化层级为 0
        int level = 0;
        // 计算当前层级的哈希值
        int hash = (int) ((value >>> levelsShift[level]) & levelsMask[level]);
        segmentLocks[hash].lock();
        try {
            // 获取当前层级的区域大小
            int areaSize = levelsAreaSize[level];
            // 获取当前层级的头部大小
            int headSize = levelsHeadSize[level];
            // 检查该哈希桶是否有值 (从位图中读取状态)
            int hasValue = getHasValue(isHasValueBitmap, hash*2, 2);
            
            switch (hasValue) {
                case 3 -> {// 状态 3: 存在值 (Value)
                    // 获取旧值
                    long oldValue = heads[hash];
                    // 如果旧值等于新值，返回 false (元素已存在)
                    if (oldValue == value) {
                        return false;
                    }
                    // 哈希冲突：分配新区域来存储旧值和新值
                    // updateHeaderWithOldValue 会分配新的空间，并更新头部指针指向新空间
                    int nextLvl = level + 1;
                    long base = updateHeaderWithOldValue(level, 1, levelsAreaSize[nextLvl]+levelsHeadSize[nextLvl], hash, value);
                    // 在下一层存储旧值和新值
                    return updateValues(base, level, hash, levelsAreaSize[nextLvl], levelsHeadSize[nextLvl], value, oldValue);
                }
                case 2 -> {// 状态 2: 存在指针 (Pointer)
                    // 获取指向下一层的指针
                    long oldPtr = heads[hash];
                    // 递归地将值放入下一层
                    return put(oldPtr, 1, value);
                }
                case 0 -> {// 状态 0: 空桶 (Empty)
                    // 更新头信息，直接存储新值
                    updateHeaderByValue(1, hash, value);
                    return true;
                }
                default ->// 不应发生的情况
                        // TODO: 添加 ECC 校验或恢复机制
                        throw new RuntimeException("意外的位图状态值:" + hasValue + ", 期望值: 3, 2, 0");
            }
        } finally {
            segmentLocks[hash].unlock();
        }


    }

    private boolean updateValues(long base,int level,int hash,int areaSize,int headSize,long value,long oldValue) throws InterruptedException, IOException {

        long limit = base+headSize+areaSize;
        long bufferIndex = base/BLOCK_SIZE;
        long bufferIndexLimit = limit/BLOCK_SIZE;
        boolean isSameBuffer = bufferIndex == bufferIndexLimit;
        int offset = (int) (base%BLOCK_SIZE);
        int offsetData = offset+headSize;//数据区
        int nextLevel = level+1;
        if(nextLevel >= levels.length){//达到最大层级,直接end list存储值
            return storeEndValues(false,base,level,hash,areaSize,headSize/2,value,oldValue);
        }
        //更新下一层数据区
        int hashOld = (int) ((oldValue >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);
        int hashNew = (int) ((value >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);
        //TODO: 哈希冲突,分配新的区域存储新/旧值
        if(hashOld != hashNew) {//哈希冲突
            //哈希冲突,分配新的区域存储新/旧值
            long baseNext = updateHeaderWithOldValue(nextLevel, 0, levelsAreaSize[nextLevel]+levelsHeadSize[nextLevel], hash, value);
            put(baseNext, nextLevel,  oldValue);
            return put(baseNext, nextLevel,  value);
        }
        int positionOld = offsetData+hashOld * ELEMENT_SIZE;
        int positionNew = offsetData+hashNew * ELEMENT_SIZE;
        if(isSameBuffer){
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            buffer.putLong(positionOld,oldValue);
            buffer.putLong(positionNew,value);
            unlockBufferForUpdate(bufferIndex);
        }else{//跨页
            long bufferIndexOld = positionOld/BLOCK_SIZE;
            long bufferIndexNew = positionNew/BLOCK_SIZE;
            if(bufferIndexOld == bufferIndexNew){
                MappedByteBuffer buffer = loadBufferForUpdate(bufferIndexOld);
                buffer.putLong(positionOld,oldValue);
                buffer.putLong(positionNew,value);
                unlockBufferForUpdate(bufferIndexOld);
            }else {
                MappedByteBuffer buffer = loadBufferForUpdate(bufferIndexOld);
                buffer.putLong(positionOld,oldValue);
                unlockBufferForUpdate(bufferIndexOld);
                buffer = loadBufferForUpdate(bufferIndexNew);
                positionNew = positionNew%BLOCK_SIZE;
                buffer.putLong(positionNew,value);
                unlockBufferForUpdate(bufferIndexNew);
            }
        }
        //更新头信息
        addTotal(1);
        return true;
    }

    private boolean storeEndValues(boolean isOldAdded,long base,int level,int hash,int areaSize,int headSize,long value,long oldValue) throws InterruptedException, IOException {
        //最后一层特殊处理:理论上,单值且哈希位穷尽应用的情形下,不会到达这里
        long limit = base+headSize+areaSize;
        long bufferIndex = base/BLOCK_SIZE;
        long bufferIndexLimit = limit/BLOCK_SIZE;
        boolean isSameBuffer = bufferIndex == bufferIndexLimit;
        int offset = (int) (base%BLOCK_SIZE);
        int offsetData = offset+headSize;//数据区
        boolean isValueExists = false;
        if(isSameBuffer){
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            int count = levelsDataCount[level]-1;//最后一个位置存储指针
            //当前层寻找下一个节点
            for(int i =0; i <count ; i++){

                int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(i);
                int bitPosition = DsDataUtil.intBitsMapPosition(i);
                int isHasValue = buffer.getInt(offsetIsHasValue);
                if(!DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置空
                    if(isOldAdded){//旧值已经存储
                        //存储新值
                        isHasValue = DsDataUtil.setBit(isHasValue, bitPosition);
                        buffer.putInt(offsetIsHasValue,isHasValue);
                        int positionValue = offsetData+i * ELEMENT_SIZE;
                        buffer.putLong(positionValue,value);
                        unlockBufferForUpdate(bufferIndex);
                        //更新头信息
                        addTotal(1);
                        return true;
                    }else{
                        //存储旧值
                        isHasValue = DsDataUtil.setBit(isHasValue, bitPosition);
                        buffer.putInt(offsetIsHasValue,isHasValue);
                        int positionValue = offsetData+hash * ELEMENT_SIZE;
                        buffer.putLong(positionValue,oldValue);
                        isOldAdded = true;
                    }

                }else{
                    //对应位置有值
                    int positionValue = offsetData+i * ELEMENT_SIZE;
                    long valueOld = buffer.getLong(positionValue);
                    if(valueOld == value) {//旧值和新值相同
                        isValueExists = true;
                        if(isOldAdded){
                            unlockBuffer(bufferIndex);
                            return false;
                        }
                    }
                }
                if(isValueExists && isOldAdded){
                    unlockBuffer(bufferIndex);
                    return false;
                }

            }
            //最后一个层级指针处理
            int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(count);
            int bitPosition = DsDataUtil.intBitsMapPosition(count);
            int isHasValue = buffer.getInt(offsetIsHasValue);
            int positionValue = offsetData+count * ELEMENT_SIZE;
            long basePtrNext = buffer.getLong(positionValue);
            if(!DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置空
                //分配新的区域存储新/旧值
                basePtrNext = updateHeaderWithOldValue(level,0, areaSize+headSize,hash,value);
                //更新位图
                isHasValue = DsDataUtil.setBit(isHasValue, bitPosition);
                buffer.putInt(offsetIsHasValue,isHasValue);
                unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
            }else{
                unlockBuffer(bufferIndex);
            }
            return storeEndValues(isOldAdded,basePtrNext,level,hash,areaSize,headSize,value,oldValue);

        }else{//跨页
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            MappedByteBuffer bufferNext = loadBufferForUpdate(bufferIndexLimit);

            int count = levelsDataCount[level]-1;//最后一个位置存储指针
            //当前层寻找下一个节点
            for(int i =0; i <count ; i++){

                int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(i);
                int bitPosition = DsDataUtil.intBitsMapPosition(i);
                int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);
                if(!DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置空
                    if(isOldAdded){//旧值已经存储
                        //存储新值
                        int positionValue = offsetData+i * ELEMENT_SIZE;
                        int j = writeLong(positionValue,value,buffer,bufferNext);
                        if(j == 0){//如果脏数据在第一个缓冲区
                            unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                            unlockBuffer(bufferIndexLimit);
                        }else{
                            unlockBuffer(bufferIndex);
                            unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                        }
                        // 更新isHasValueBitMap
                        isHasValue = DsDataUtil.setBit(isHasValue, bitPosition);
                        j = writeInt(offsetIsHasValue,isHasValue,buffer,bufferNext);
                        if(j == 0){//如果脏数据在第一个缓冲区
                            unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                            unlockBuffer(bufferIndexLimit);
                        }else{
                            unlockBuffer(bufferIndex);
                            unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                        }
                        //更新头信息
                        addTotal(1);
                        return true;
                    }else{
                        //存储旧值
                        int positionValue = offsetData+i * ELEMENT_SIZE;
                        int j = writeLong(positionValue,oldValue,buffer,bufferNext);
                        if(j == 0){//如果脏数据在第一个缓冲区
                            unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                            unlockBuffer(bufferIndexLimit);
                        }else{
                            unlockBuffer(bufferIndex);
                            unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                        }
                        // 更新isHasValueBitMap
                        isHasValue = DsDataUtil.setBit(isHasValue, bitPosition);
                        j = writeInt(offsetIsHasValue,isHasValue,buffer,bufferNext);
                        if(j == 0){//如果脏数据在第一个缓冲区
                            unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                            unlockBuffer(bufferIndexLimit);
                        }else{
                            unlockBuffer(bufferIndex);
                            unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                        }
                        isOldAdded = true;
                    }

                }else{
                    //对应位置有值
                    int positionValue = offsetData+i * ELEMENT_SIZE;
                    long valueOld =  readLong(positionValue,buffer,bufferNext);
                    if(valueOld == value) {//旧值和新值相同
                        isValueExists = true;
                        if(isOldAdded){
                            unlockBuffer(bufferIndex);
                            unlockBuffer(bufferIndexLimit);
                            return false;
                        }
                    }
                }
                if(isValueExists && isOldAdded){
                    unlockBuffer(bufferIndex);
                    unlockBuffer(bufferIndexLimit);
                    return false;
                }

            }
            //最后一个层级指针处理
            int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(count);
            int bitPosition = DsDataUtil.intBitsMapPosition(count);
            int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);
            int positionValue = offsetData+count * ELEMENT_SIZE;
            long basePtrNext = readLong(positionValue,buffer,bufferNext);
            if(!DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置空
                //分配新的区域存储新/旧值
                basePtrNext = updateHeaderWithOldValue(level,0, areaSize+headSize,hash,value);
                // 更新isHasValueBitMap
                isHasValue = DsDataUtil.setBit(isHasValue, bitPosition);
                int j = writeInt(offsetIsHasValue,isHasValue,buffer,bufferNext);
                if(j == 0){//如果脏数据在第一个缓冲区
                    unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                    unlockBuffer(bufferIndexLimit);
                }else{
                    unlockBuffer(bufferIndex);
                    unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                }
            }else{
                unlockBuffer(bufferIndex);
                unlockBuffer(bufferIndexLimit);
            }
            return storeEndValues(isOldAdded,basePtrNext,level,hash,areaSize,headSize,value,oldValue);
        }
    }


    private Long getEndValue(long base,int level,int hash,int areaSize,int headSize,long value) throws InterruptedException, IOException {
        //最后一层特殊处理
        long limit = base+headSize+areaSize;
        long bufferIndex = base/BLOCK_SIZE;
        long bufferIndexLimit = limit/BLOCK_SIZE;
        boolean isSameBuffer = bufferIndex == bufferIndexLimit;
        int offset = (int) (base%BLOCK_SIZE);
        int offsetData = offset+headSize;//数据区
        if(isSameBuffer){
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            int count = levelsDataCount[level]-1;//最后一个位置存储指针
            //当前层寻找下一个节点
            for(int i =0; i <count ; i++){

                int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(i);
                int bitPosition = DsDataUtil.intBitsMapPosition(i);
                int isHasValue = buffer.getInt(offsetIsHasValue);
                if(!DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置空
                    continue;

                }else{
                    //对应位置有值
                    int positionValue = offsetData+i * ELEMENT_SIZE;
                    long valueOld = buffer.getLong(positionValue);
                    if(valueOld == value) {//旧值和新值相同
                        return null;
                    }
                }

            }
            //最后一个层级指针处理
            int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(count);
            int bitPosition = DsDataUtil.intBitsMapPosition(count);
            int isHasValue = buffer.getInt(offsetIsHasValue);
            int positionValue = offsetData+count * ELEMENT_SIZE;
            long basePtrNext = buffer.getLong(positionValue);
            if(!DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置空
                return null;
            }
            return getEndValue(basePtrNext,level,hash,areaSize,headSize,value);

        }else{//跨页
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            MappedByteBuffer bufferNext = loadBuffer(bufferIndexLimit);

            int count = levelsDataCount[level]-1;//最后一个位置存储指针
            //当前层寻找下一个节点
            for(int i =0; i <count ; i++){

                int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(i);
                int bitPosition = DsDataUtil.intBitsMapPosition(i);
                int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);
                if(!DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置空
                    return null;
                }else{
                    //对应位置有值
                    int positionValue = offsetData+i * ELEMENT_SIZE;
                    long valueOld =  readLong(positionValue,buffer,bufferNext);
                    if(valueOld == value) {//旧值和新值相同
                        return null;
                    }
                }

            }
            //最后一个层级指针处理
            int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(count);
            int bitPosition = DsDataUtil.intBitsMapPosition(count);
            int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);
            int positionValue = offsetData+count * ELEMENT_SIZE;
            long basePtrNext = readLong(positionValue,buffer,bufferNext);
            if(!DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置空
                return null;
            }
            return getEndValue(basePtrNext,level,hash,areaSize,headSize,value);
        }
    }

    private boolean removeEnd(long base,int level,int hash,int areaSize,int headSize,long value) throws InterruptedException, IOException {
        //最后一层特殊处理
        long limit = base+headSize+areaSize;
        long bufferIndex = base/BLOCK_SIZE;
        long bufferIndexLimit = limit/BLOCK_SIZE;
        boolean isSameBuffer = bufferIndex == bufferIndexLimit;
        int offset = (int) (base%BLOCK_SIZE);
        int offsetData = offset+headSize;//数据区
        if(isSameBuffer){
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            int count = levelsDataCount[level]-1;//最后一个位置存储指针
            //当前层寻找下一个节点
            for(int i =0; i <count ; i++){

                int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(i);
                int bitPosition = DsDataUtil.intBitsMapPosition(i);
                int isHasValue = buffer.getInt(offsetIsHasValue);
                if(DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置有值

                    int positionValue = offsetData+i * ELEMENT_SIZE;
                    long valueOld = buffer.getLong(positionValue);
                    if(valueOld == value) {//旧值和新值相同
                        isHasValue = DsDataUtil.clearBit(isHasValue, bitPosition);
                        buffer.putInt(offsetIsHasValue, isHasValue);
                        buffer.putLong(positionValue,0L);
                        unlockBufferForUpdate(bufferIndex);
                        minusTotal(1);
                        return true;
                    }

                }

            }
            //最后一个层级指针处理
            int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(count);
            int bitPosition = DsDataUtil.intBitsMapPosition(count);
            int isHasValue = buffer.getInt(offsetIsHasValue);
            int positionValue = offsetData+count * ELEMENT_SIZE;
            long basePtrNext = buffer.getLong(positionValue);
            if(!DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置空
                return false;
            }
            return removeEnd(basePtrNext,level,hash,areaSize,headSize,value);

        }else{//跨页
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            MappedByteBuffer bufferNext = loadBufferForUpdate(bufferIndexLimit);

            int count = levelsDataCount[level]-1;//最后一个位置存储指针
            //当前层寻找下一个节点
            for(int i =0; i <count ; i++){

                int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(i);
                int bitPosition = DsDataUtil.intBitsMapPosition(i);
                int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);
                if(DsDataUtil.testBit(isHasValue,bitPosition)) { //对应位置有值

                    int positionValue = offsetData+i * ELEMENT_SIZE;
                    long valueOld =  readLong(positionValue,buffer,bufferNext);
                    if(valueOld == value) {//旧值和新值相同
                        isHasValue = DsDataUtil.clearBit(isHasValue, bitPosition);
                        int j = writeLong(positionValue, 0L,buffer,bufferNext);
                        if(j == 0){//如果脏数据在第一个缓冲区
                            unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                            unlockBuffer(bufferIndexLimit);
                        }else{
                            unlockBuffer(bufferIndex);
                            unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                        }
                        j = writeInt(offsetIsHasValue, isHasValue,buffer,bufferNext);
                        if(j == 0){//如果脏数据在第一个缓冲区
                            unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                            unlockBuffer(bufferIndexLimit);
                        }else{
                            unlockBuffer(bufferIndex);
                            unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                        }
                        minusTotal(1);
                        return true;
                    }
                }

            }
            //最后一个层级指针处理
            int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(count);
            int bitPosition = DsDataUtil.intBitsMapPosition(count);
            int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);
            int positionValue = offsetData+count * ELEMENT_SIZE;
            long basePtrNext = readLong(positionValue,buffer,bufferNext);
            unlockBuffer(bufferIndex);
            unlockBuffer(bufferIndexLimit);
            if(!DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置空
                return false;
            }
            return removeEnd(basePtrNext,level,hash,areaSize,headSize,value);
        }
    }

    private void clearEnd(long base,int level,int hash,int areaSize,int headSize) throws InterruptedException, IOException {
        //最后一层特殊处理
        long limit = base+headSize+areaSize;
        long bufferIndex = base/BLOCK_SIZE;
        long bufferIndexLimit = limit/BLOCK_SIZE;
        boolean isSameBuffer = bufferIndex == bufferIndexLimit;
        int offset = (int) (base%BLOCK_SIZE);
        int offsetData = offset+headSize;//数据区
        if(isSameBuffer){
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            int count = levelsDataCount[level]-1;//最后一个位置存储指针
            //当前层寻找下一个节点
            for(int i =0; i <count ; i++){

                int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(i);
                int bitPosition = DsDataUtil.intBitsMapPosition(i);
                int isHasValue = buffer.getInt(offsetIsHasValue);
                if(DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置有值

                    int positionValue = offsetData+i * ELEMENT_SIZE;
//                    long valueOld = buffer.getLong(positionValue);
//                    if(valueOld == value) {//旧值和新值相同
                        isHasValue = DsDataUtil.clearBit(isHasValue, bitPosition);
                        buffer.putInt(offsetIsHasValue, isHasValue);
                        buffer.putLong(positionValue,0L);
                        unlockBufferForUpdate(bufferIndex);
                        //minusTotal(1);
                        return;
//                    }

                }

            }
            //最后一个层级指针处理
            int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(count);
            int bitPosition = DsDataUtil.intBitsMapPosition(count);
            int isHasValue = buffer.getInt(offsetIsHasValue);

            if(DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置存在指针
                int positionValue = offsetData+count * ELEMENT_SIZE;
                long basePtrNext = buffer.getLong(positionValue);
                unlockBufferForUpdate(bufferIndex);
                clearEnd(basePtrNext,level,hash,areaSize,headSize);
            }else{
                unlockBufferForUpdate(bufferIndex);
            }


        }else{//跨页
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            MappedByteBuffer bufferNext = loadBufferForUpdate(bufferIndexLimit);

            int count = levelsDataCount[level]-1;//最后一个位置存储指针
            //当前层寻找下一个节点
            for(int i =0; i <count ; i++){

                int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(i);
                int bitPosition = DsDataUtil.intBitsMapPosition(i);
                int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);
                if(DsDataUtil.testBit(isHasValue,bitPosition)) { //对应位置有值

                    int positionValue = offsetData+i * ELEMENT_SIZE;
//                    long valueOld =  readLong(positionValue,buffer,bufferNext);
//                    if(valueOld == value) {//旧值和新值相同
                        int j = writeLong(positionValue, 0L,buffer,bufferNext);
                        if(j == 0){//如果脏数据在第一个缓冲区
                            unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                            unlockBuffer(bufferIndexLimit);
                        }else{
                            unlockBuffer(bufferIndex);
                            unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                        }
                        isHasValue = DsDataUtil.clearBit(isHasValue, bitPosition);
                        j = writeInt(offsetIsHasValue, isHasValue,buffer,bufferNext);
                        if(j == 0){//如果脏数据在第一个缓冲区
                            unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                            unlockBuffer(bufferIndexLimit);
                        }else{
                            unlockBuffer(bufferIndex);
                            unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                        }
//                        minusTotal(1);
//                    }
                }

            }
            //最后一个层级指针处理
            int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(count);
            int bitPosition = DsDataUtil.intBitsMapPosition(count);
            int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);

            if(DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置存在指针
                int positionValue = offsetData+count * ELEMENT_SIZE;
                long basePtrNext = readLong(positionValue,buffer,bufferNext);
                unlockBuffer(bufferIndex);
                unlockBuffer(bufferIndexLimit);
                clearEnd(basePtrNext,level,hash,areaSize,headSize);
            }else{
                unlockBuffer(bufferIndex);
                unlockBuffer(bufferIndexLimit);
            }
        }
    }


    boolean put(long base,int level,long value) throws InterruptedException, IOException {
        int areaSize = levelsAreaSize[level];
        int headSize = levelsHeadSize[level];
        int hash = (int) ((value >>> levelsShift[level]) & levelsMask[level]);
        long limit = base+headSize+areaSize;
        long bufferIndex = base/BLOCK_SIZE;
        long bufferIndexLimit = limit/BLOCK_SIZE;
        boolean isSameBuffer = bufferIndex == bufferIndexLimit;
        int offset = (int) (base%BLOCK_SIZE);
        int offsetData = offset+headSize;//数据区
        int positionValue = offsetData+hash * ELEMENT_SIZE;
        int offsetIsHasValue = offset+DsDataUtil.int2BitsMapIndex(hash);
        int bitPosition = DsDataUtil.int2BitsMapPosition(hash);
        if(isSameBuffer){
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            int isHasValue = buffer.getInt(offsetIsHasValue);
            //获取当前level,hash的hasValue值
            int hasValue = getHasValue(isHasValue, bitPosition, 2);
            //根据hasValue值进行不同的操作
            switch (hasValue) {
                case 3 -> {//有值-原始值
                    //获取当前hash对应的旧值
                    long oldValue = buffer.getLong(positionValue);
                    if(oldValue == value){//如果旧值和新值相同，则返回false
                        return false;
                    }
                    //哈希冲突,分配新的区域存储新/旧值
                    int nextLvl = level + 1;
                    long baseNew = updateHeaderWithOldValue(level,1, levelsAreaSize[nextLvl]+levelsHeadSize[nextLvl],hash,value);
                    // 更新isHasValueBitMap
                    isHasValue = DsDataUtil.clearBit(isHasValue, bitPosition+1);
                    buffer.putInt(offsetIsHasValue,isHasValue);
                    //更新指针
                    buffer.putLong(positionValue,baseNew);
                    unlockBufferForUpdate(bufferIndex);

                    //存储(旧值\新值)到下一层
                    return updateValues(baseNew,level,hash,levelsAreaSize[nextLvl],levelsHeadSize[nextLvl],value,oldValue);
                }
                case 2 -> {//有值-指针
                    //获取当前hash对应的旧指针
                    long basePtr = buffer.getLong(positionValue);
                    unlockBuffer(bufferIndex);
                    int nextLevel = level+1;
                    if(nextLevel >= levels.length){//达到最大层级,直接end list存储值
                        return storeEndValues(true,basePtr,level,hash,areaSize,headSize/2,value,0);
                    }
                    //将新值放入旧指针指向的区域
                    return put(basePtr,nextLevel,value);
                }
                case 0 -> {//无值
                    isHasValue = DsDataUtil.setBit(isHasValue, bitPosition);
                    isHasValue = DsDataUtil.setBit(isHasValue, bitPosition+1);
                    buffer.putInt(offsetIsHasValue,isHasValue);

                    buffer.putLong(positionValue,value);
                    unlockBufferForUpdate(bufferIndex);
                    //更新头信息
                    addTotal(1);
                    return true;
                }
                default ->//理论上不会出现 TODO: ECC校验

                        throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
            }

        }else{//跨页
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            MappedByteBuffer bufferNext = loadBufferForUpdate(bufferIndexLimit);

            int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);
            //获取当前level,hash的hasValue值
            int hasValue = getHasValue(isHasValue, bitPosition, 2);

            //根据hasValue值进行不同的操作
            switch (hasValue) {
                case 3 -> {//有值-原始值
                    //获取当前hash对应的旧值
                    long oldValue = readLong(positionValue,buffer,bufferNext);
                    if(oldValue == value){//如果旧值和新值相同，则返回false
                        return false;
                    }
                    //哈希冲突,分配新的区域存储新/旧值
                    int nextLvl = level + 1;
                    long baseNew = updateHeaderWithOldValue(level,1, levelsAreaSize[nextLvl]+levelsHeadSize[nextLvl],hash,value);
                    //写入偏移量
                    int i = writeLong(positionValue,baseNew,buffer,bufferNext);
                    if(i == 0){//如果脏数据在第一个缓冲区
                        unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                        unlockBuffer(bufferIndexLimit);
                    }else{
                        unlockBuffer(bufferIndex);
                        unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                    }
                    // 更新isHasValueBitMap
                    isHasValue = DsDataUtil.clearBit(isHasValue, bitPosition+1);
                    buffer.putInt(offsetIsHasValue,isHasValue);
                    i = writeInt(offsetIsHasValue,isHasValue,buffer,bufferNext);
                    if(i == 0){//如果脏数据在第一个缓冲区
                        unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                        unlockBuffer(bufferIndexLimit);
                    }else{
                        unlockBuffer(bufferIndex);
                        unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                    }
                    //存储(旧值\新值)到下一层
                    return updateValues(baseNew,level,hash,levelsAreaSize[nextLvl],levelsHeadSize[nextLvl],value,oldValue);
                }
                case 2 -> {//有值-指针
                    //获取当前hash对应的旧指针
                    long basePtr = buffer.getLong(positionValue);
                    unlockBuffer(bufferIndex);
                    int nextLevel = level+1;
                    if(nextLevel >= levels.length){//达到最大层级,直接end list存储值
                        return storeEndValues(true,basePtr,level,hash,areaSize,headSize/2,value,0L);
                    }
                    //将新值放入指针指向的区域
                    return put(basePtr,level+1,value);
                }
                case 0 -> {//无值
                    int i = writeLong(positionValue,value,buffer,bufferNext);
                    if(i == 0){//如果脏数据在第一个缓冲区
                        unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                        unlockBuffer(bufferIndexLimit);
                    }else{
                        unlockBuffer(bufferIndex);
                        unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                    }
                    // 更新isHasValueBitMap
                    isHasValue = DsDataUtil.setBit(isHasValue, bitPosition);
                    isHasValue = DsDataUtil.setBit(isHasValue, bitPosition+1);
                    i = writeInt(offsetIsHasValue,isHasValue,buffer,bufferNext);
                    if(i == 0){//如果脏数据在第一个缓冲区
                        unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                        unlockBuffer(bufferIndexLimit);
                    }else{
                        unlockBuffer(bufferIndex);
                        unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                    }

                    //更新头信息
                    addTotal(1);
                    return true;

                }
                default ->//理论上不会出现 TODO: ECC校验

                        throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
            }

        }

    }

    boolean remove(long base,int level,long value) throws InterruptedException, IOException {
        int areaSize = levelsAreaSize[level];
        int headSize = levelsHeadSize[level];
        int hash = (int) ((value >>> levelsShift[level]) & levelsMask[level]);
        long limit = base+headSize+areaSize;
        long bufferIndex = base/BLOCK_SIZE;
        long bufferIndexLimit = limit/BLOCK_SIZE;
        boolean isSameBuffer = bufferIndex == bufferIndexLimit;
        int offset = (int) (base%BLOCK_SIZE);
        int offsetData = offset+headSize;//数据区
        int positionValue = offsetData+hash * ELEMENT_SIZE;
        int offsetIsHasValue = offset+DsDataUtil.int2BitsMapIndex(hash);
        int bitPosition = DsDataUtil.int2BitsMapPosition(hash);
        if(isSameBuffer){
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            int isHasValue = buffer.getInt(offsetIsHasValue);
            //获取当前level,hash的hasValue值
            int hasValue = getHasValue(isHasValue, bitPosition, 2);
            //根据hasValue值进行不同的操作
            switch (hasValue) {
                case 3 -> {//有值-原始值
                    long oldValue = buffer.getLong(positionValue);
                    if(oldValue == value){//清除对应hash位
                        // 更新isHasValueBitMap
                        isHasValue = DsDataUtil.clearBit(isHasValue, bitPosition+1);
                        buffer.putInt(offsetIsHasValue,isHasValue);
                        buffer.putLong(positionValue,0l);
                        unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                        minusTotal(1);
                        return true;
                    }
                    return false;//对应值不存在集合中

                }
                case 2 -> {//有值-指针
                    //获取当前hash对应的旧指针
                    long basePtr = buffer.getLong(positionValue);
                    unlockBuffer(bufferIndex);
                    int nextLevel = level+1;
                    if(nextLevel >= levels.length){//达到最大层级
                        return removeEnd(basePtr,level,hash,areaSize,headSize/2,value);
                    }
                    //继续下一层处理
                    return remove(basePtr,nextLevel,value);
                }
                case 0 -> {//无值
                    return false;//对应值不存在集合中
                }
                default ->//理论上不会出现 TODO: ECC校验

                        throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
            }


        }else{//跨页
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            MappedByteBuffer bufferNext = loadBufferForUpdate(bufferIndexLimit);

            int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);

            //获取当前level,hash的hasValue值
            int hasValue = getHasValue(isHasValue, bitPosition, 2);
            //根据hasValue值进行不同的操作
            switch (hasValue) {
                case 3 -> {//有值-原始值
                    long oldValue = readLong(positionValue,buffer,bufferNext);
                    if(oldValue == value){//清除对应hash位
                        //更新数据区
                        int i = writeLong(positionValue,0l,buffer,bufferNext);
                        if(i == 0){//如果脏数据在第一个缓冲区
                            unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                            unlockBuffer(bufferIndexLimit);
                        }else{
                            unlockBuffer(bufferIndex);
                            unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                        }
                        // 更新isHasValueBitMap
                        isHasValue = DsDataUtil.clearBit(isHasValue, bitPosition+1);
                        i = writeInt(offsetIsHasValue,isHasValue,buffer,bufferNext);
                        if(i == 0){//如果脏数据在第一个缓冲区
                            unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                            unlockBuffer(bufferIndexLimit);
                        }else{
                            unlockBuffer(bufferIndex);
                            unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                        }

                        minusTotal(1);
                        return true;
                    }
                    return false;//对应值不存在集合中

                }
                case 2 -> {//有值-指针
                    //获取当前hash对应的旧指针
                    long basePtr = buffer.getLong(positionValue);
                    unlockBuffer(bufferIndex);
                    unlockBuffer(bufferIndexLimit);
                    int nextLevel = level+1;
                    if(nextLevel >= levels.length){//达到最大层级
                        return removeEnd(basePtr,level,hash,areaSize,headSize/2,value);
                    }
                    //继续下一层处理
                    return remove(basePtr,nextLevel,value);
                }
                case 0 -> {//无值
                    return false;//对应值不存在集合中
                }
                default ->//理论上不会出现 TODO: ECC校验

                        throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
            }

        }

    }


    Long get(long base,int level,long value) throws InterruptedException, IOException {
        int areaSize = levelsAreaSize[level];
        int headSize = levelsHeadSize[level];
        int hash = (int) ((value >>> levelsShift[level]) & levelsMask[level]);
        long limit = base+headSize+areaSize;
        long bufferIndex = base/BLOCK_SIZE;
        long bufferIndexLimit = limit/BLOCK_SIZE;
        boolean isSameBuffer = bufferIndex == bufferIndexLimit;
        int offset = (int) (base%BLOCK_SIZE);
        int offsetData = offset+headSize;//数据区
        int positionValue = offsetData+hash * ELEMENT_SIZE;
        int offsetIsHasValue = offset+DsDataUtil.int2BitsMapIndex(hash);
        int bitPosition = DsDataUtil.int2BitsMapPosition(hash);
        if(isSameBuffer){
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            int offsetIsValue = offset+hash*4+headSize/2;
            int isHasValue = buffer.getInt(offsetIsHasValue);
            //获取当前level,hash的hasValue值
            int hasValue = getHasValue(isHasValue, bitPosition, 2);
            //根据hasValue值进行不同的操作
            switch (hasValue) {
                case 3 -> {//有值-原始值
                    long oldValue = buffer.getLong(positionValue);
                    if(oldValue == value){
                        return oldValue;
                    }
                    return null;//对应值不存在集合中

                }
                case 2 -> {//有值-指针
                    //获取当前hash对应的旧指针
                    long basePtr = buffer.getLong(positionValue);
                    unlockBuffer(bufferIndex);
                    int nextLevel = level+1;
                    if(nextLevel >= levels.length){//达到最大层级
                        return getEndValue(basePtr,level,hash,areaSize,headSize/2,value);
                    }
                    //继续下一层处理
                    return get(basePtr,nextLevel,value);
                }
                case 0 -> {//无值
                    return null;//对应值不存在集合中
                }
                default ->//理论上不会出现 TODO: ECC校验

                        throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
            }

        }else{//跨页
            MappedByteBuffer buffer = loadBuffer(bufferIndex);
            MappedByteBuffer bufferNext = loadBuffer(bufferIndexLimit);
            int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);

            //获取当前level,hash的hasValue值
            int hasValue = getHasValue(isHasValue, bitPosition, 2);
            //根据hasValue值进行不同的操作
            switch (hasValue) {
                case 3 -> {//有值-原始值
                    long oldValue = readLong(positionValue,buffer,bufferNext);
                    if(oldValue == value){
                        return oldValue;
                    }

                    return null;//对应值不存在集合中

                }
                case 2 -> {//有值-指针
                    //获取当前hash对应的旧指针
                    long basePtr = readLong(positionValue,buffer,bufferNext);
                    unlockBuffer(bufferIndex);
                    unlockBuffer(bufferIndexLimit);
                    int nextLevel = level+1;
                    if(nextLevel >= levels.length){//达到最大层级
                        return getEndValue(basePtr,level,hash,areaSize,headSize/2,value);
                    }
                    //将新值放入旧指针指向的区域
                    return get(basePtr,nextLevel,value);
                }
                case 0 -> {//无值
                    return null;//对应值不存在集合中
                }
                default ->//理论上不会出现 TODO: ECC校验

                        throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
            }
        }
    }



    /**
     * 从集合中移除一个值。
     * @param value 要移除的值。
     * @return 如果集合中包含该元素，则返回 true。
     */
    public boolean remove(long value){
        // 初始化层级为 0
       int level = 0;
       // 计算哈希值
       int hash = (int) ((value >>> levelsShift[level]) & levelsMask[level]);
       segmentLocks[hash].lock();
       try {
           // 获取区域大小
           int areaSize = levelsAreaSize[level];
           // 获取头部大小
           int headSize = levelsHeadSize[level];
           // 检查值是否存在
           int hasValue = getHasValue(isHasValueBitmap, hash*2, 2);
           
           switch (hasValue) {
               case 3 -> {// 状态 3: 存在值 (Value)
                   // 获取旧值
                   long oldValue = heads[hash];
                   // 如果匹配，移除它
                   if (oldValue == value) {
                       minusTotalByHash(1,hash);
                       return true;
                   }
                   return false;// 未找到
               }
               case 2 -> {// 状态 2: 存在指针 (Pointer)
                   // 获取指针
                   long ptr = heads[hash];
                   try {
                       // 递归地从下一层移除
                       return remove(ptr,1,value);
                   } catch (InterruptedException | IOException e) {
                       // TODO: 优化异常处理
                       throw new RuntimeException("移除值失败", e);
                   }
               }
               case 0 -> {// 状态 0: 空 (Empty)
                   return false;// 未找到
               }
               default ->// 不应发生
                       // TODO: 添加 ECC 校验
                       throw new RuntimeException("意外的位图状态值:" + hasValue + ", 期望值: 3, 2, 0");
           }
       } finally {
           segmentLocks[hash].unlock();
       }

   }

   public boolean contains(long value) {
       // 初始化level为0
       int level = 0;
       // 计算value的hash值
       int hash = (int) ((value >>> levelsShift[level]) & levelsMask[level]);
       segmentLocks[hash].lock();
       try {
           // 获取当前level的areaSize
           int areaSize = levelsAreaSize[level];
           // 获取当前level的headSize
           int headSize = levelsHeadSize[level];

           //获取当前level,hash的hasValue值
           int hasValue = getHasValue(isHasValueBitmap, hash*2, 2);
           //根据hasValue值进行不同的操作
           switch (hasValue) {
               case 3 -> {//有值-原始值
                   //获取当前hash对应的旧值
                   long oldValue = heads[hash];
                   //如果旧值和新值相同，则返回false
                   if (oldValue == value) {
                       return true;
                   }
                   return false;//对应值不存在集合中
               }
               case 2 -> {//有值-指针
                   // 获取heads中hash位置的值
                   long ptr = heads[hash];
                   try {
                       // 调用get方法获取ptr位置的值
                       Long oldValue = get(ptr,1,value);
                       // 判断oldValue是否为null
                       if(oldValue != null){
                           // 判断oldValue是否等于value
                           return oldValue == value;
                       }else{
                           return false;
                       }
                   } catch (InterruptedException e) {
                       // 抛出RuntimeException异常
                       throw new RuntimeException(e);
                   } catch (IOException e) {
                       // 抛出RuntimeException异常
                       throw new RuntimeException(e);
                   }
               }
               case 0 -> {//无值
                   // 返回false
                   return false;//对应值不存在集合中
               }
               default ->//理论上不会出现 TODO: ECC校验

                       throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
           }
       } finally {
           segmentLocks[hash].unlock();
       }

   }

    void clear() {
        if(0 == total){
            return;
        }
        headerOpLock.lock();
        try {

            //目前实现是清理所有数据标志位,但不回收空间,以便重新使用
            DsIndexWrapper index = new DsIndexWrapper();
            int half = heads.length / 2;
            //负整数遍历
            for(int i = heads.length-1; i >=half; i--){
                //获取当前level,hash的hasValue值
                int hasValue = getHasValue(isHasValueBitmap, i*2, 2);
                //根据hasValue值进行不同的操作
                switch (hasValue) {
                    case 3 -> {//有值-原始值
                        //clear to zero
                        isHasValueBitmap = DsDataUtil.clearBit(isHasValueBitmap, i*2+1);
                        headerBuffer.putInt(rootHeadSize,isHasValueBitmap);
                        heads[i] = 0L;
                        headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+i * ELEMENT_SIZE,0L);
                        index.value++;

                    }
                    case 2 -> {//有值-指针
                        // 获取heads中hash位置的值
                        //继续下一层处理
                        long basePtr = heads[i];
                        clearNode(index,basePtr,1,i);
                    }
                    case 0 -> {//无值
                        continue;
                    }
                    default ->//理论上不会出现 TODO: ECC校验

                            throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
                }
                if(index.value == total){
                    return;
                }
            }

            //正整数遍历
            for(int i = 0; i < half; i++){
                //获取当前level,hash的hasValue值
                int hasValue = getHasValue(isHasValueBitmap, i*2, 2);
                //根据hasValue值进行不同的操作
                switch (hasValue) {
                    case 3 -> {//有值-原始值
                        //clear to zero
                        isHasValueBitmap = DsDataUtil.clearBit(isHasValueBitmap, i*2+1);
                        headerBuffer.putInt(rootHeadSize,isHasValueBitmap);
                        heads[i] = 0L;
                        headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+i * ELEMENT_SIZE,0L);
                        index.value++;

                    }
                    case 2 -> {//有值-指针
                        // 获取heads中hash位置的值
                        //继续下一层处理
                        long basePtr = heads[i];
                        clearNode(index,basePtr,1,i);
                    }
                    case 0 -> {//无值
                        continue;
                    }
                    default ->//理论上不会出现 TODO: ECC校验

                            throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
                }
                if(index.value == total){
                    return;
                }
            }

            total = 0;
            headerBuffer.putInt(TOTAL_OFFSET,total);
            dirty(0l);
        }finally {
            headerOpLock.unlock();
        }

    }

    private final void clearNode(DsIndexWrapper index,long base,int level,int hash)  {
        try {
            int areaSize = levelsAreaSize[level];
            int headSize = levelsHeadSize[level];
            long limit = base+headSize+areaSize;
            long bufferIndex = base/BLOCK_SIZE;
            long bufferIndexLimit = limit/BLOCK_SIZE;
            boolean isSameBuffer = bufferIndex == bufferIndexLimit;
            int offset = (int) (base%BLOCK_SIZE);
            int offsetData = offset+headSize;//数据区
            int positionValue = offsetData+hash * ELEMENT_SIZE;
            int offsetIsHasValue = offset+DsDataUtil.int2BitsMapIndex(hash);
            int bitPosition = DsDataUtil.int2BitsMapPosition(hash);
            if(isSameBuffer){
                MappedByteBuffer buffer  = loadBufferForUpdate(bufferIndex);
                int isHasValue = buffer.getInt(offsetIsHasValue);
                //获取当前level,hash的hasValue值
                int hasValue = getHasValue(isHasValue, bitPosition, 2);

                //根据hasValue值进行不同的操作
                switch (hasValue) {
                    case 3 -> {//有值-原始值
                        //clear to zero
                        buffer.putLong(positionValue, 0L);
                        // 更新isHasValueBitMap
                        isHasValue = DsDataUtil.clearBit(isHasValue, bitPosition+1);
                        buffer.putInt(offsetIsHasValue, isHasValue);
                        unlockBufferForUpdate(bufferIndex);
                        index.value++;
                        if(index.value == total){
                            return;
                        }
                        //minusTotal(1);
                    }
                    case 2 -> {//有值-指针
                        //获取当前hash对应的旧指针
                        long basePtr =  buffer.getLong(positionValue);
                        unlockBuffer(bufferIndex);
                        unlockBuffer(bufferIndexLimit);
                        int nextLevel = level+1;
                        if(nextLevel >= levels.length){//达到最大层级
                            clearEnd(basePtr,level,hash,areaSize,headSize/2);
                            return;
                        }
                        //继续下一层处理
                        clearNode(index,basePtr,nextLevel,hash);
                    }
                    case 0 -> {//无值
                        int count = levelsDataCount[level];
                        //当前层寻找下一个节点
                        for(int i = hash+1; i <count ; i++){
                            clearNode(index,base, level, i);
                            if(index.value == total){
                                return;
                            }
                        }
                    }
                    default ->//理论上不会出现 TODO: ECC校验
                            throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3");
                            
                }

            }else{//跨页
                MappedByteBuffer buffer = loadBuffer(bufferIndex);
                MappedByteBuffer bufferNext = loadBuffer(bufferIndexLimit);

                int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);

                //获取当前level,hash的hasValue值
                int hasValue = getHasValue(isHasValue, bitPosition, 2);
                //根据hasValue值进行不同的操作
                switch (hasValue) {
                    case 3 -> {//有值-原始值
                        //clear to zero
                        //更新数据区
                        int i = writeLong(positionValue,0l,buffer,bufferNext);
                        if(i == 0){//如果脏数据在第一个缓冲区
                            unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                            unlockBuffer(bufferIndexLimit);
                        }else{
                            unlockBuffer(bufferIndex);
                            unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                        }
                        // 更新isHasValueBitMap
                        isHasValue = DsDataUtil.clearBit(isHasValue, bitPosition+1);
                        i = writeInt(offsetIsHasValue,isHasValue,buffer,bufferNext);
                        if(i == 0){//如果脏数据在第一个缓冲区
                            unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                            unlockBuffer(bufferIndexLimit);
                        }else{
                            unlockBuffer(bufferIndex);
                            unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                        }

                        //minusTotal(1);
                    }
                    case 2 -> {//有值-指针
                        //获取当前hash对应的旧指针
                        long basePtr =  readLong(positionValue,buffer,bufferNext);
                        unlockBuffer(bufferIndex);
                        unlockBuffer(bufferIndexLimit);
                        int nextLevel = level+1;
                        if(nextLevel >= levels.length){//达到最大层级
                            clearEnd(basePtr,level,hash,areaSize,headSize/2);
                            return;
                        }
                        //继续下一层处理
                        clearNode(index,basePtr,nextLevel,hash);
                    }
                    case 0 -> {//无值
                        int count = levelsDataCount[level];
                        //当前层寻找下一个节点
                        for(int i = hash+1; i <count ; i++){
                            clearNode(index,base, level, i);
                            if(index.value == total){
                                return;
                            }
                        }
                    }
                    default ->//理论上不会出现 TODO: ECC校验
                            throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3");

                }

            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public final Iterator<Long> iterator() {
        return new LongIterator(this);
    }

    //@Override
    public boolean removeAll(Collection<Long> c) {

        Iterator<Long> it = c.iterator();
        int count = 0;
        while (it.hasNext()) {
            Long obj = it.next();
            if (remove(obj)) {
                count++;
            }

        }
        return count != 0;

    }

    /* ------------------------------------------------------------ */
    // iterators
static class DsHashNode{

    int level;
    int hash;
    long value;

    long base;
    //Object data;
        DsHashNode parent;

        public DsHashNode(int level, int hash, long value, long base, Object data, MappedByteBuffer buffer) {
            this.level = level;
            this.hash = hash;
            this.value = value;
            this.base = base;
            //this.data = data;
        }

        public DsHashNode(int level, int hash, long value, long base) {
            this.level = level;
            this.hash = hash;
            this.value = value;
            this.base = base;
        }
    }
    abstract class DsSetIterator {

        DsHashSet set;
        DsHashNode next;        // next entry to return
        DsHashNode current;     // current entry
        int expectedModCount;  // for fast-fail
        int index;             // current index
//

        DsSetIterator(DsHashSet set) {
            this.set = set;
            expectedModCount = set.total;
            int half = set.heads.length / 2;
            //负整数遍历
            for(int i = set.heads.length-1; i >=half; i--){

                //获取当前level,hash的hasValue值
                int hasValue = getHasValue(set.isHasValueBitmap, i*2, 2);
                //根据hasValue值进行不同的操作
                switch (hasValue) {
                    case 3 -> {//有值-原始值
                        next = new DsHashNode(0,i,set.heads[i],set.rootHeadSize);
                        break;
                    }
                    case 2 -> {//有值-指针
                        // 获取heads中hash位置的值
                        //继续下一层处理
                        long basePtr = set.heads[i];
                        DsHashNode currentNode = new DsHashNode(0, i, basePtr, set.rootHeadSize);
                        //currentNode.parent = null;
                        next = nextNode(currentNode, basePtr, 1, i);
                        if(next != null){
                            break;
                        }
                    }
                    case 0 -> {//无值
                        continue;
                    }
                    default ->//理论上不会出现 TODO: ECC校验

                            throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
                }
            }
            if(next != null){
                return;
            }
           //正整数遍历
            for(int i = 0; i < half; i++){
                //获取当前level,hash的hasValue值
                int hasValue = getHasValue(set.isHasValueBitmap, i*2, 2);
                //根据hasValue值进行不同的操作
                switch (hasValue) {
                    case 3 -> {//有值-原始值
                        next = new DsHashNode(0,i,set.heads[i],set.rootHeadSize);
                        break;
                    }
                    case 2 -> {//有值-指针
                        // 获取heads中hash位置的值
                        //继续下一层处理
                        long basePtr = set.heads[i];
                        DsHashNode currentNode = new DsHashNode(0, i, basePtr, set.rootHeadSize);
                        //currentNode.parent = null;
                        next = nextNode(currentNode, basePtr, 1, i);
                        if(next != null){
                            break;
                        }
                    }
                    case 0 -> {//无值
                        continue;
                    }
                    default ->//理论上不会出现 TODO: ECC校验

                            throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
                }
            }
        }

        public final boolean hasNext() {
            if(index == expectedModCount){
                return false;
            }
            boolean hasNext = next != null;
            if(hasNext){
                index++;
            }
            return hasNext;
        }

        final DsHashNode nextNode() {
//            if (modCount != expectedModCount)
//                throw new ConcurrentModificationException();
            DsHashNode current = next;
            if(current != null){
                next = nextNode(current.parent, current.base, current.level, current.hash);
            }
            if (current == null)
                throw new NoSuchElementException();

            return current;
        }

        final DsHashNode nextNode(DsHashNode parent,long base,int level,int hash)  {
            try {
            int areaSize = set.levelsAreaSize[level];
            int headSize = set.levelsHeadSize[level];
            long limit = base+headSize+areaSize;
            long bufferIndex = base/BLOCK_SIZE;
            long bufferIndexLimit = limit/BLOCK_SIZE;
            boolean isSameBuffer = bufferIndex == bufferIndexLimit;
            int offset = (int) (base%BLOCK_SIZE);
            int offsetData = offset+headSize;//数据区
            int positionValue = offsetData+hash * ELEMENT_SIZE;
                int offsetIsHasValue = offset+DsDataUtil.int2BitsMapIndex(hash);
                int bitPosition = DsDataUtil.int2BitsMapPosition(hash);
            if(isSameBuffer){
                MappedByteBuffer buffer  = loadBuffer(bufferIndex);
                int isHasValue = buffer.getInt(offsetIsHasValue);
                //获取当前level,hash的hasValue值
                int hasValue = getHasValue(isHasValue, bitPosition, 2);
                //根据hasValue值进行不同的操作
                switch (hasValue) {
                    case 3 -> {//有值-原始值
                        long oldValue = buffer.getLong(positionValue);
                        DsHashNode currentNode = new DsHashNode(level, hash, oldValue, base);
                        currentNode.parent = parent;
                        return currentNode;

                    }
                    case 2 -> {//有值-指针
                        //获取当前hash对应的旧指针
                        //继续下一层处理
                        long basePtr = buffer.getLong(positionValue);
                        DsHashNode currentNode = new DsHashNode(level, hash, basePtr, base);
                        currentNode.parent = parent;
                        return nextNode(currentNode, basePtr, level+1, hash);
                    }
                    case 0 -> {//无值
                        int count = set.levelsDataCount[level];
                        long basePtr = buffer.getLong(positionValue);
                        //当前层寻找下一个节点
                        for(int i = hash+1; i <count ; i++){
                            DsHashNode nextNode = nextNode(parent, basePtr, level, i);
                            if(nextNode != null){
                                return nextNode;
                            }
                        }
                        return null;
                    }
                    default ->//理论上不会出现 TODO: ECC校验

                            throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
                }

            }else{//跨页
                MappedByteBuffer buffer = loadBuffer(bufferIndex);
                MappedByteBuffer bufferNext = loadBuffer(bufferIndexLimit);

                int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);

                //获取当前level,hash的hasValue值
                int hasValue = getHasValue(isHasValue, bitPosition, 2);
                //根据hasValue值进行不同的操作
                switch (hasValue) {
                    case 3 -> {//有值-原始值
                        long oldValue = readLong(positionValue,buffer,bufferNext);
                        DsHashNode currentNode = new DsHashNode(level, hash, oldValue, base);
                        currentNode.parent = parent;
                        return currentNode;

                    }
                    case 2 -> {//有值-指针
                        //获取当前hash对应的旧指针
                        long basePtr = readLong(positionValue,buffer,bufferNext);
                        DsHashNode currentNode = new DsHashNode(level, hash, basePtr, base);
                        currentNode.parent = parent;
                        return nextNode(currentNode, basePtr, level+1, hash);
                    }
                    case 0 -> {//无值
                        int count = set.levelsDataCount[level];
                        long basePtr = readLong(positionValue,buffer,bufferNext);
                        //当前层寻找下一个节点
                        for(int i = hash+1; i <count ; i++){
                            DsHashNode nextNode = nextNode(parent, basePtr, level, i);
                            if(nextNode != null){
                                return nextNode;
                            }
                        }
                        return null;
                    }
                    default ->//理论上不会出现 TODO: ECC校验

                            throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
                }
            }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private final void removeNode(long base,int level,int hash)  {
            try {
                int areaSize = set.levelsAreaSize[level];
                int headSize = set.levelsHeadSize[level];
                long limit = base+headSize+areaSize;
                long bufferIndex = base/BLOCK_SIZE;
                long bufferIndexLimit = limit/BLOCK_SIZE;
                boolean isSameBuffer = bufferIndex == bufferIndexLimit;
                int offset = (int) (base%BLOCK_SIZE);
                int offsetData = offset+headSize;//数据区
                int positionValue = offsetData+hash * ELEMENT_SIZE;
                int offsetIsHasValue = offset+DsDataUtil.int2BitsMapIndex(hash);
                int bitPosition = DsDataUtil.int2BitsMapPosition(hash);
                if(isSameBuffer){
                    MappedByteBuffer buffer  = loadBufferForUpdate(bufferIndex);
                    int isHasValue = buffer.getInt(offsetIsHasValue);
                    //获取当前level,hash的hasValue值
                    int hasValue = getHasValue(isHasValue, bitPosition, 2);
                    //根据hasValue值进行不同的操作

                    if (hasValue == 3) {//有值-原始值
                        //clear to zero
                        buffer.putLong(positionValue, 0L);
                        unlockBufferForUpdate(bufferIndex);
                        // 更新isHasValueBitMap
                        isHasValue = DsDataUtil.clearBit(isHasValue, bitPosition+1);
                        buffer.putInt(offsetIsHasValue, isHasValue);
                        set.minusTotal(1);
                    } else {
                        throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3");
                    }

                }else{//跨页
                    MappedByteBuffer buffer = loadBuffer(bufferIndex);
                    MappedByteBuffer bufferNext = loadBuffer(bufferIndexLimit);

                    int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);

                    //获取当前level,hash的hasValue值
                    int hasValue = getHasValue(isHasValue, bitPosition, 2);
                    //根据hasValue值进行不同的操作
                    if (hasValue == 3) {//有值-原始值
                        //clear to zero
                        //更新数据区
                        int i = writeLong(positionValue,0l,buffer,bufferNext);
                        if(i == 0){//如果脏数据在第一个缓冲区
                            unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                            unlockBuffer(bufferIndexLimit);
                        }else{
                            unlockBuffer(bufferIndex);
                            unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                        }
                        // 更新isHasValueBitMap
                        isHasValue = DsDataUtil.clearBit(isHasValue, bitPosition+1);
                        i = writeInt(offsetIsHasValue,isHasValue,buffer,bufferNext);
                        if(i == 0){//如果脏数据在第一个缓冲区
                            unlockBufferForUpdate(bufferIndex);//释放锁并更新缓冲区
                            unlockBuffer(bufferIndexLimit);
                        }else{
                            unlockBuffer(bufferIndex);
                            unlockBufferForUpdate(bufferIndexLimit);//释放锁并更新缓冲区
                        }

                        set.minusTotal(1);
                    } else {
                        throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3");
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public final void remove() {
            DsHashNode current = next;
            if(current != null){
                next = nextNode(current.parent, current.base, current.level, current.hash);
            }
            if (current == null)
                throw new NoSuchElementException();
            removeNode(current.base, current.level, current.hash);
        }
    }

    final class LongIterator extends DsSetIterator
            implements Iterator<Long> {
        LongIterator(DsHashSet set) {
            super(set);
        }

        public final Long next() { return nextNode().value; }
    }


    public final void forEach(DsProcessLong action) {
        int half = heads.length / 2;
        boolean  complete = false;
        DsIndexWrapper index = new DsIndexWrapper();
        Long current = null;
        //负整数遍历
        for(int i = heads.length-1; i >=half; i--){
            //获取当前level,hash的hasValue值
            int hasValue = getHasValue(isHasValueBitmap, i*2, 2);
            //根据hasValue值进行不同的操作
            switch (hasValue) {
                case 3 -> {//有值-原始值
                    complete = action.process(heads[i],index.value);
                    index.value++;
                    if(index.value >= total){
                        complete = true;
                    }
                    if(complete){
                        break;
                    }
                }
                case 2 -> {//有值-指针
                    // 获取heads中hash位置的值
                    //继续下一层处理
                    long basePtr = heads[i];
                    complete = nextValueProcess(index,action,basePtr, 1, i);
                    if(complete){
                        break;
                    }
                }
                case 0 -> {//无值
                    continue;
                }
                default ->//理论上不会出现 TODO: ECC校验

                        throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
            }

        }
        if(complete){
            return;
        }
        //正整数遍历
        for(int i = 0; i < half; i++){
            //获取当前level,hash的hasValue值
            int hasValue = getHasValue(isHasValueBitmap, i*2, 2);
            //根据hasValue值进行不同的操作
            switch (hasValue) {
                case 3 -> {//有值-原始值
                    complete = action.process(heads[i],index.value);
                    index.value++;
                    if(index.value >= total){
                        complete = true;
                    }
                    if(complete){
                        break;
                    }
                }
                case 2 -> {//有值-指针
                    // 获取heads中hash位置的值
                    //继续下一层处理
                    long basePtr = heads[i];
                    complete = nextValueProcess(index,action,basePtr, 1, i);
                    if(complete){
                        break;
                    }
                }
                case 0 -> {//无值
                    continue;
                }
                default ->//理论上不会出现 TODO: ECC校验

                        throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
            }
        }
    }

    private final boolean nextValueProcess(DsIndexWrapper index ,DsProcessLong action, long base, int level, int hash)  {
        try {
            boolean  complete = true;
            //获取当前level的areaSize
            int areaSize = levelsAreaSize[level];
            //获取当前level的headSize
            int headSize = levelsHeadSize[level];
            //获取当前level的limit
            long limit = base+headSize+areaSize;
            //获取当前level的bufferIndex
            long bufferIndex = base/BLOCK_SIZE;
            //获取当前level的bufferIndexLimit
            long bufferIndexLimit = limit/BLOCK_SIZE;
            //判断当前level的bufferIndex和bufferIndexLimit是否相同
            boolean isSameBuffer = bufferIndex == bufferIndexLimit;
            //获取当前level的offset
            int offset = (int) (base%BLOCK_SIZE);
            int offsetData = offset+headSize;//数据区
            int positionValue = offsetData+hash * ELEMENT_SIZE;
            int offsetIsHasValue = offset+DsDataUtil.int2BitsMapIndex(hash);
            int bitPosition = DsDataUtil.int2BitsMapPosition(hash);
            if(isSameBuffer){
                MappedByteBuffer buffer = null;

                buffer = loadBuffer(bufferIndex);

                int isHasValue = buffer.getInt(offsetIsHasValue);

                //获取当前level,hash的hasValue值
                int hasValue = getHasValue(isHasValue, bitPosition, 2);
                //根据hasValue值进行不同的操作
                switch (hasValue) {
                    case 3 -> {//有值-原始值
                        long oldValue = buffer.getLong(positionValue);
                        complete = action.process(oldValue,index.value);
                        index.value++;
                        if(index.value >= total){
                            complete = true;
                        }
                        if(complete){
                            return complete;
                        }

                    }
                    case 2 -> {//有值-指针
                        //获取当前hash对应的旧指针
                        long basePtr = buffer.getLong(positionValue);
                        return nextValueProcess(index,action,basePtr, level+1, hash);
                    }
                    case 0 -> {//无值
                        int count = levelsDataCount[level];
                        //当前层寻找下一个节点
                        for(int i = hash+1; i <count ; i++){
                            complete = nextValueProcess(index,action,base, level, i);
                            if(complete){
                                return complete;
                            }
                        }
                        return false;
                    }
                    default ->//理论上不会出现 TODO: ECC校验

                            throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
                }
            }else{//跨页
                MappedByteBuffer buffer = loadBuffer(bufferIndex);
                MappedByteBuffer bufferNext = loadBuffer(bufferIndexLimit);
                int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);
                //获取当前level,hash的hasValue值
                int hasValue = getHasValue(isHasValue, bitPosition, 2);
                //根据hasValue值进行不同的操作
                switch (hasValue) {
                    case 3 -> {//有值-原始值
                        long oldValue = readLong(positionValue,buffer,bufferNext);
                        complete = action.process(oldValue,index.value);
                        index.value++;
                        if(index.value >= total){
                            complete = true;
                        }
                        if(complete){
                            return complete;
                        }

                    }
                    case 2 -> {//有值-指针
                        //获取当前hash对应的旧指针
                        long basePtr = readLong(positionValue,buffer,bufferNext);
                        return nextValueProcess( index,action,basePtr, level+1, hash);
                    }
                    case 0 -> {//无值
                        int count = levelsDataCount[level];
                        //当前层寻找下一个节点
                        for(int i = hash+1; i <count ; i++){
                            complete = nextValueProcess(index, action,base, level, i);
                            if(complete){
                                return complete;
                            }
                        }
                        return false;
                    }
                    default ->//理论上不会出现 TODO: ECC校验

                            throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
                }

            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    private final boolean endValueProcess(DsIndexWrapper index ,DsProcessLong action, long base, int level)  {
        try {
            boolean  complete = true;
            //获取当前level的areaSize
            int areaSize = levelsAreaSize[level];
            //获取当前level的headSize
            int headSize = levelsHeadSize[level];
            //获取当前level的limit
            long limit = base+headSize+areaSize;
            //获取当前level的bufferIndex
            long bufferIndex = base/BLOCK_SIZE;
            //获取当前level的bufferIndexLimit
            long bufferIndexLimit = limit/BLOCK_SIZE;
            //判断当前level的bufferIndex和bufferIndexLimit是否相同
            boolean isSameBuffer = bufferIndex == bufferIndexLimit;
            //获取当前level的offset
            int offset = (int) (base%BLOCK_SIZE);
            int offsetData = offset+headSize;//数据区

            if(isSameBuffer){
                MappedByteBuffer buffer = loadBuffer(bufferIndex);
                int count = levelsDataCount[level]-1;//最后一个位置存储指针
                //当前层寻找下一个节点
                for(int i =0; i <count ; i++){

                    int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(i);
                    int bitPosition = DsDataUtil.intBitsMapPosition(i);
                    int isHasValue = buffer.getInt(offsetIsHasValue);
                    if(DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置有值

                        int positionValue = offsetData+i * ELEMENT_SIZE;
                        long oldValue = buffer.getLong(positionValue);
                        complete = action.process(oldValue,index.value);
                        index.value++;
                        if(index.value >= total){
                            complete = true;
                        }
                        if(complete){
                            return complete;
                        }

                    }

                }
                //最后一个层级指针处理
                int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(count);
                int bitPosition = DsDataUtil.intBitsMapPosition(count);
                int isHasValue = buffer.getInt(offsetIsHasValue);
                int positionValue = offsetData+count * ELEMENT_SIZE;
                long basePtrNext = buffer.getLong(positionValue);
                if(!DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置空
                    return false;
                }
                return endValueProcess(index,action,basePtrNext, level);


            }else{//跨页

                MappedByteBuffer buffer = loadBuffer(bufferIndex);
                MappedByteBuffer bufferNext = loadBuffer(bufferIndexLimit);

                int count = levelsDataCount[level]-1;//最后一个位置存储指针
                //当前层寻找下一个节点
                for(int i =0; i <count ; i++){

                    int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(i);
                    int bitPosition = DsDataUtil.intBitsMapPosition(i);
                    int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);
                    if(DsDataUtil.testBit(isHasValue,bitPosition)) { //对应位置有值

                        int positionValue = offsetData+i * ELEMENT_SIZE;
                        long oldValue = readLong(positionValue,buffer,bufferNext);
                        complete = action.process(oldValue,index.value);
                        index.value++;
                        if(index.value >= total){
                            complete = true;
                        }
                        if(complete){
                            return complete;
                        }
                    }

                }
                //最后一个层级指针处理
                int offsetIsHasValue = offset+DsDataUtil.intBitsMapIndex(count);
                int bitPosition = DsDataUtil.intBitsMapPosition(count);
                int isHasValue = readInt(offsetIsHasValue,buffer,bufferNext);

                if(DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置存在指针
                    int positionValue = offsetData+count * ELEMENT_SIZE;
                    long basePtrNext = readLong(positionValue,buffer,bufferNext);
                    if(!DsDataUtil.testBit(isHasValue,bitPosition)) {//对应位置空
                        return false;
                    }
                    return endValueProcess(index,action,basePtrNext, level);
                }

            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return false;
    }

}
