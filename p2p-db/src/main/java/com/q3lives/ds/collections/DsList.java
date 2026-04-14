package com.q3lives.ds.collections;

import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.CopyOnWriteArrayList;

import com.q3lives.ds.core.DsObject;
import com.q3lives.ds.index.record.DsIndexWrapper;
import com.q3lives.ds.spi.DsProcessLong;

/**
 * 基于内存映射文件(Memory Mapped Files)的持久化列表(List)实现。
 * <p>
 * <b>数据结构设计：</b>
 * 本实现采用了一种类似 "数组链表" (Linked List of Arrays) 的混合结构，旨在结合数组的快速随机访问和链表的动态扩展能力。
 * <ul>
 *     <li><b>文件头 (Header):</b> 存储全局元数据，包括元素总数 (total)、下一个可用存储偏移量 (nextOffset) 等。</li>
 *     <li><b>数据层 (Data Layers):</b> 数据存储的主体。逻辑上是一个链表，每个节点称为一个 "层" (Layer) 或 "块" (Block)。
 *         <ul>
 *             <li><b>层结构：</b> 每个层包含头部信息（本层偏移、已用数量、容量、下一层指针）和实际数据区。</li>
 *             <li><b>动态扩容：</b> 初始层容量较小 (16)，后续层容量呈指数级增长 (1024, 4096, ...)，以减少内存碎片并提高大数据量的读写性能。</li>
 *             <li><b>非连续存储：</b> 层与层之间在物理文件上可能是不连续的，通过 `rightOffset` 指针链接。</li>
 *         </ul>
 *     </li>
 * </ul>
 * </p>
 * <p>
 * <b>并发控制策略：</b>
 * 为了支持高并发读写，本类采用了细粒度的锁机制：
 * <ul>
 *     <li><b>分层锁 (Fine-grained Locking):</b> 每个 {@link DataLayer} 对象内部维护一个独立的 {@link ReentrantReadWriteLock}。
 *         <ul>
 *             <li>{@link #get(int)}: 获取读锁，允许多个线程同时读取同一个层的数据。</li>
 *             <li>{@link #set(int, long)}, {@link #remove(int)}: 获取写锁，仅锁定受影响的数据层，不影响其他层的读写。</li>
 *         </ul>
 *     </li>
 *     <li><b>结构安全 (Structural Safety):</b> `dataLayers` 列表使用 {@link CopyOnWriteArrayList}，确保在遍历层列表时不会抛出 {@link ConcurrentModificationException}。
 *     对于涉及全局结构变更的操作（如 {@link #add(long)} 导致的新增层），目前仍需获取全局 `headerOpLock` 或 `writeLock` 以保证元数据的一致性。</li>
 * </ul>
 * </p>
 */
public class DsList extends DsObject {

    public static final int DEFAULT_ROOT_HEAD_SIZE = 16;
    public static final int DATA_LAYER_DESC_SIZE = 16;
    public static final int MAGIC_STRING_LENGTH = 4;
    public static final String MAGIC_STRING = ".LIS";
    public static final int TOTAL_OFFSET = 4;
    public static final int NEXT_OFFSET_POSITION = 8;
    public static final int ELEMENT_SIZE = 8;


    private int total = 0;

    //TODO: 优化建议：将头部数据（元数据、位图、指针）与内容数据分离。
    // 头部数据访问频繁，可以存放在单独的文件或紧凑的缓冲区中以提高缓存命中率 (Cache Locality)。
    // 内容数据可以采用纯数组存储。
    // 考虑引入位图 (Bitmap) 来标记索引或指针的有效性。


    /**
     * 文件头结构定义：
     * magic = ".LIS", 4 bytes (魔数)
     * total = 0, 4 bytes (元素总数)
     * nextOffset = 32, 8 bytes (下一个可用空间偏移量)
     */
    private int rootHeadSize = DEFAULT_ROOT_HEAD_SIZE;//头大小

    /**
     * 数据层头部描述符定义 (16 bytes)：
     * levelOffset (4 bytes): 本层在文件中的起始偏移量 (实际偏移 = value << 3)
     * used (4 bytes): 本层已存储的元素数量
     * capacity (4 bytes): 本层最大容量
     * rightOffset (4 bytes): 下一层在文件中的起始偏移量 (实际偏移 = value << 3)
     */


    private final int dataLayerDescSize = DATA_LAYER_DESC_SIZE;//数据层描述大小4*int=16字节

    // 使用 CopyOnWriteArrayList 允许并发迭代，避免 ConcurrentModificationException
    private final List<DataLayer> dataLayers = new CopyOnWriteArrayList<>();

    //private static final int leftTotalIndex = 0;
    private static final int levelOffsetIndex = 0;

    private static final int usedIndex = 4;
    private static final int capacityIndex = 8;
    // private static final int rightTotalIndex = 4;
    private static final int rightOffsetIndex = 12;


    //private final long[] heads = new long[16];//头指针

    //private int headsOffset = rootHeadSize + dataLayerDescSize;//根数据偏移
    private long nextOffset = rootHeadSize + dataLayerDescSize;//头存储区域之后,32字节对齐


    private MappedByteBuffer headerBuffer;
    // 使用 ReentrantReadWriteLock 替代 ReentrantLock 以提升读并发性能
    private final ReentrantReadWriteLock headerLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = headerLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = headerLock.writeLock();


    private final int[] levelsCapacity = new int[]{16, 1024, 4096, 1024 * 64, 1024 * 1024, 1024 * 1024 * 64};//哈希层级可存储数据量


    // public static final int LEVEL_MAX_COUNT = 1024*1024*64;//单层最大可存储数据量;

    // private DataLayer currentLayer;//当前层
    private int level = 0;//当前层级索引
    private int levelUsed = 0;//当前层级已存储数据量 volatile
    private long levelOffset = 0;//当前层级已使用偏移量
    private int levelCapacity = 0;//当前层级容量

    //private long currentBufferIndex = 0;//当前页索引


    // 计算区域大小
    private static final int calcAreaSize(int level) {
        // 计算区域大小
        int areaSize = ((1 << level) / 32) * 4 * 2 + (1 << level) * 8;

        // 返回区域大小
        return areaSize;
    }

    // 计算头大小
    private static final int calcHeadSize(int level) {
        // 计算头大小
        int headSize = ((1 << level) / 32) * 4 * 2;
        if (headSize == 0) {
            return 4;
        }
        // 返回头大小
        return headSize;
    }

    // 计算扩容的层级容量
    private final int calcNextCapacity() {
        // 如果当前级别大于等于容量数组长度，则返回容量数组最后一个元素
        if (level >= levelsCapacity.length) {
            return levelsCapacity[levelsCapacity.length - 1];
        }
        // 否则，返回当前级别的容量，并将级别加1
        int c = levelsCapacity[level];
        level++;
        return c;
    }

    private final int calcCapacity(int level) {
        // 如果当前级别大于等于容量数组长度，则返回容量数组最后一个元素
        if (level >= levelsCapacity.length) {
            return levelsCapacity[levelsCapacity.length - 1];
        }
        // 否则，返回当前级别的容量
        return levelsCapacity[level];
    }

    /**
     * 创建一个持久化 long 列表（基于内存映射文件）。
     *
     * @param dataFile 底层数据文件
     */
    public DsList(File dataFile) {
        super(dataFile, ELEMENT_SIZE);//64位 long
        long rootOffset = rootHeadSize + dataLayerDescSize;
        //实际校正根数据区偏移,以便对齐到8字节
        int x = rootHeadSize + dataLayerDescSize;
        if (x % 8 != 0) {
            rootHeadSize = rootHeadSize + (x % 8);//对齐到8字节
            //headsOffset = rootHeadSize + dataLayerDescSize;//根数据偏移
            if (rootOffset == nextOffset) {//根数据区偏移和下一个数据区偏移相同,则说明根数据区为空,需要调整下一个数据区偏移
                nextOffset = rootHeadSize + dataLayerDescSize;
            }
        }
        checkHeader();

//        for(int i=0;i<levels.length;i++){
//            levelsAreaSize[i] = calcAreaSize(levels[i]);
//        }
//        for(int i=0;i<levels.length;i++){
//            levelsHeadSize[i] = calcHeadSize(levels[i]);
//        }
    }

    /**
     * 检查并初始化文件头信息。
     * 如果是新文件，写入魔数和初始元数据。
     * 如果是已有文件，加载元数据并重建 dataLayers 链表结构。
     */
    private void checkHeader() {

        // 加载缓冲区
        //System.out.println("bufferIndex="+bufferIndex+" offset="+offset);
        boolean needUpdate = false;
        try {
            headerBuffer = loadBufferForUpdate(0L);

            byte[] magicBytes = new byte[MAGIC_STRING_LENGTH];
            headerBuffer.get(magicBytes, 0, MAGIC_STRING_LENGTH);
            String magic = MAGIC_STRING;
            if (Arrays.compare(magicBytes, magic.getBytes()) == 0) {
                // 文件存在且魔数匹配，加载元数据
                total = headerBuffer.getInt(TOTAL_OFFSET);
                nextOffset = headerBuffer.getLong(NEXT_OFFSET_POSITION);
                DataLayer layer = new DataLayer();
                // layer.leftTotal = headerBuffer.getInt(rootHeadSize);
                layer.levelOffset = headerBuffer.getInt(rootHeadSize);
                layer.used = headerBuffer.getInt(rootHeadSize + usedIndex);
                layer.capacity = headerBuffer.getInt(rootHeadSize + capacityIndex);
                layer.rightOffset = (long) headerBuffer.getInt(rootHeadSize + rightOffsetIndex) << 3;
                dataLayers.add(layer);
                
                // 遍历加载所有后续数据层
                while (layer.rightOffset > 0L) {
                    levelOffset = layer.rightOffset;

                    long bufferIndexLeftTotal = (layer.rightOffset) / BLOCK_SIZE;
                    long bufferIndexRightOffset = (layer.rightOffset + rightOffsetIndex) / BLOCK_SIZE;
                    boolean isSameBuffer = bufferIndexLeftTotal == bufferIndexRightOffset;
                    int offset = (int) (layer.rightOffset % BLOCK_SIZE);
                    MappedByteBuffer buffer = loadBuffer(bufferIndexLeftTotal);
                    layer = new DataLayer();
                    if (isSameBuffer) {
                        //layer.leftTotal = buffer.getInt(offset+leftTotalIndex);
                        layer.levelOffset = buffer.getInt(offset + levelOffsetIndex);
                        layer.used = buffer.getInt(offset + usedIndex);
                        layer.capacity = buffer.getInt(offset + capacityIndex);
                        layer.rightOffset = (long) buffer.getInt(offset + rightOffsetIndex) << 3;

                    } else {
                        MappedByteBuffer bufferNext = loadBuffer(bufferIndexRightOffset);
                        //layer.leftTotal = readInt(offset+leftTotalIndex,buffer,bufferNext);
                        layer.levelOffset = buffer.getInt(offset + levelOffsetIndex);
                        layer.used = readInt(offset + usedIndex, buffer, bufferNext);
                        layer.capacity = readInt(offset + capacityIndex, buffer, bufferNext);
                        layer.rightOffset = (long) readInt(offset + rightOffsetIndex, buffer, bufferNext) << 3;
                    }
                    dataLayers.add(layer);
                }
                level = dataLayers.size() - 1;
                setCurrentLevel(level);
            } else {
                // 初始化新文件头
                headerBuffer.put(0, magic.getBytes(), 0, MAGIC_STRING_LENGTH);
                headerBuffer.putInt(TOTAL_OFFSET, 0);// total
                headerBuffer.putLong(NEXT_OFFSET_POSITION, nextOffset);// nextOffset

                level = 0;
                DataLayer layer = new DataLayer();
                levelOffset = layer.levelOffset = nextOffset;
                // currentBufferIndex = (nextOffset / BLOCK_SIZE);
                levelUsed = layer.used = 0;
                levelCapacity = calcCapacity(level);
                layer.capacity = levelCapacity;
                dataLayers.add(layer);
                headerBuffer.putInt(rootHeadSize + levelOffsetIndex, (int) (nextOffset >> 3));
                headerBuffer.putInt(rootHeadSize + usedIndex, 0);
                headerBuffer.putInt(rootHeadSize + capacityIndex, levelCapacity);
                headerBuffer.putInt(rootHeadSize + rightOffsetIndex, 0);


                needUpdate = true;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (needUpdate) {
                unlockBufferForUpdate(0L);
            } else {
                unlockBuffer(0L);
            }

        }

    }

    private final void addTotal(int count) {
        headerOpLock.lock();
        try {
            total += count;
            //nextOffset += count*8;
            headerBuffer.putInt(TOTAL_OFFSET, total);
            //headerBuffer.putLong(NEXT_OFFSET_POSITION, nextOffset);
            dirty(0l);
        } finally {
            headerOpLock.unlock();
        }

    }

    private final void minusTotal(int count) {
        headerOpLock.lock();
        try {
            total -= count;
            //nextOffset += count*8;
            headerBuffer.putInt(TOTAL_OFFSET, total);
            //headerBuffer.putLong(NEXT_OFFSET_POSITION, nextOffset);
            dirty(0l);
        } finally {
            headerOpLock.unlock();
        }

    }

    private final void setTotal(int count) {
        headerOpLock.lock();
        try {
            total = count;
            headerBuffer.putInt(TOTAL_OFFSET, total);
            dirty(0l);
        } finally {
            headerOpLock.unlock();
        }

    }

    /**
     * 为新增数据层/扩容预留空间：更新 total 与 nextOffset，并写回 header。
     *
     * @param count total 增量
     * @param size  预留的字节数
     * @return 更新后的 nextOffset
     */
    public final long updateHeaderForNewOffset(int count, long size) {
        headerOpLock.lock();
        try {
            total += count;
            //long base = nextOffset;
            nextOffset += size;

            headerBuffer.putInt(TOTAL_OFFSET, total);
            headerBuffer.putLong(NEXT_OFFSET_POSITION, nextOffset);
            dirty(0L);
            // currentBufferIndex = (nextOffset / BLOCK_SIZE);
            return nextOffset;
        } finally {
            headerOpLock.unlock();
        }
    }


    /**
     * 向列表添加一个值。
     * <p>
     * 如果当前层已满，会自动分配一个新的层（容量可能更大）。
     * </p>
     * @param value 要添加的值
     * @return 添加成功返回 true
     */
    public boolean add(long value) {
//        long bufferIndex = levelOffset / BLOCK_SIZE;
        headerOpLock.lock();
        try {
            if (levelUsed < levelCapacity) {//当前层有空位
                storeLongOffset(levelOffset + dataLayerDescSize + (long) levelUsed * dataUnitSize, value);
                levelUsed++;
                storeIntOffset(levelOffset + usedIndex, levelUsed);
                return true;
            } else {
                // 当前层已满，需要扩容
                level++;
                if (level >= dataLayers.size()) {
                    // 需要创建新的物理层
                    levelCapacity = calcNextCapacity();
                    // TODO: 在扩容前检查磁盘空间或文件大小限制。
                    long basePtrNext = updateHeaderForNewOffset(1, (long) levelCapacity * dataUnitSize + dataLayerDescSize);
                    // 更新前一层的 rightOffset 指向新层
                    storeIntOffset(levelOffset + rightOffsetIndex, (int) (basePtrNext >>> 3));
                    return storeValueWithNextBlock(basePtrNext, value);
                } else {
                    // 逻辑层已存在（可能是之前删除后保留的空闲层），复用之
                    DataLayer layer = dataLayers.get(level);
                    while (layer.used == layer.capacity) {
                        level++;
                        if (level < dataLayers.size()) {
                            layer = dataLayers.get(level);
                        }

                    }
                    levelOffset = layer.levelOffset;
                    //currentBufferIndex = (nextOffset / BLOCK_SIZE);
                    levelUsed = layer.used;
                    levelCapacity = layer.capacity;
                    return storeValueWithNextLevel(value);
                }

            }

        } catch (Exception e) {
            // TODO: 优化异常处理逻辑
            throw new RuntimeException("Failed to add value to DsList", e);
        } finally {
            //addTotal(1);
            headerOpLock.unlock();
        }
    }

    /**
     * 批量追加多个值到列表尾部。
     *
     * <p>该方法会尽量填满当前层，必要时继续向后续层写入或触发扩容。</p>
     */
    public boolean addAll(long[] values) {
        headerOpLock.lock();
        int count = values.length;
        try {
            long offsetData = levelOffset + dataLayerDescSize;//数据区
            long bufferIndexBase = offsetData / BLOCK_SIZE;
            int offsetDataUnit = (int) (offsetData % BLOCK_SIZE);
            long limit = offsetData + (long) levelCapacity * dataUnitSize;
            long bufferIndexLimit = limit / BLOCK_SIZE;
            boolean isSameBuffer = bufferIndexBase == bufferIndexLimit;
            MappedByteBuffer buffer = null;
            MappedByteBuffer longBuffer = null;
            if (isSameBuffer) {
                buffer = loadBuffer(bufferIndexBase);
                longBuffer = buffer.slice(offsetDataUnit, levelUsed * dataUnitSize);

            } else {
                buffer = loadFrame(bufferIndexBase, dataLayerDescSize + levelCapacity * dataUnitSize);
                longBuffer = buffer.slice(offsetDataUnit, levelUsed * dataUnitSize);
            }

            int rest = levelCapacity - levelUsed;
            if (rest >= count) {
                longBuffer.asLongBuffer().put(levelUsed, values, 0, count);

            } else {
                longBuffer.asLongBuffer().put(levelUsed, values, 0, rest);
                levelUsed += count;
                storeIntOffset(levelOffset + usedIndex, levelUsed);
                int allRest = count - rest;
                int offset = 0;
                DataLayer layer = dataLayers.get(level);
                while (allRest > 0) {
                    int currentCount = layer.capacity - layer.used;
                    if (currentCount > 0) {
                        allRest = allRest - currentCount;
                        if (allRest > 0) {
                            longBuffer.asLongBuffer().put(layer.used, values, offset, currentCount);
                            layer.used += currentCount;
                            storeIntOffset(layer.levelOffset + usedIndex, layer.used);
                            offset += currentCount;
                        } else {
                            rest = currentCount + allRest;
                            longBuffer.asLongBuffer().put(layer.used, values, offset, rest);
                            layer.used += rest;
                            storeIntOffset(layer.levelOffset + usedIndex, layer.used);
                            levelOffset = layer.levelOffset;
                            levelUsed = layer.used;
                            levelCapacity = layer.capacity;
                            setCurrentLevel(level);
                            return true;
                        }
                    }

                    level++;
                    if (level >= dataLayers.size()) {
                        levelCapacity = calcNextCapacity();
                        //计算新数据层容量
                        if (allRest > levelCapacity) {
                            levelCapacity = levelCapacity << 1;//翻倍
                            if (allRest > levelCapacity) {
                                levelCapacity = (allRest >> 3) << 4;//8字节对齐翻倍容量
                            }
                        }
                        long basePtrNext = updateHeaderForNewOffset(count, (long) levelCapacity * dataUnitSize + dataLayerDescSize);
                        storeIntOffset(levelOffset + rightOffsetIndex, (int) (basePtrNext >>> 3));
                        //当前层已满,需要扩容
                        //分配新的区域存储
                        levelOffset = basePtrNext;
                        levelUsed = 1;
                        DataLayer layerNew = new DataLayer();
                        layerNew.levelOffset = levelOffset;
                        layerNew.used = levelUsed;
                        layerNew.capacity = levelCapacity;
                        layerNew.rightOffset = 0;
                        dataLayers.add(layerNew);
                        layer = layerNew;
                        storeIntOffset(levelOffset, (int) (layerNew.levelOffset >> 3));//左指针偏移 8字节对齐
                        storeIntOffset(levelOffset + usedIndex, layerNew.used);
                        storeIntOffset(levelOffset + capacityIndex, layerNew.capacity);
                        //其他zero
                        storeIntOffset(levelOffset + rightOffsetIndex, (int) layerNew.rightOffset>>3);
                    } else {
                        layer = dataLayers.get(level);
                    }

                    offsetData = layer.levelOffset + dataLayerDescSize;//数据区
                    bufferIndexBase = offsetData / BLOCK_SIZE;
                    offsetDataUnit = (int) (offsetData % BLOCK_SIZE);
                    limit = offsetData + (long) layer.capacity * dataUnitSize;
                    bufferIndexLimit = limit / BLOCK_SIZE;
                    isSameBuffer = bufferIndexBase == bufferIndexLimit;
                    if (isSameBuffer) {
                        buffer = loadBuffer(bufferIndexBase);
                        longBuffer = buffer.slice(offsetDataUnit, layer.used * dataUnitSize);

                    } else {
                        buffer = loadFrame(bufferIndexBase, dataLayerDescSize + layer.capacity * dataUnitSize);
                        longBuffer = buffer.slice(offsetDataUnit, layer.used * dataUnitSize);
                    }
                }

            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //addTotal(count);
            headerOpLock.unlock();
        }
        return false;
    }

    private boolean storeValueWithNextBlock(long basePtrNext, long value) throws IOException {
        //当前层已满,需要扩容
        //分配新的区域存储

        levelOffset = basePtrNext;
        levelUsed = 1;
        DataLayer layer = new DataLayer();
        layer.levelOffset = levelOffset;
        layer.used = levelUsed;
        layer.capacity = levelCapacity;
        layer.rightOffset = 0;
        dataLayers.add(layer);
        storeIntOffset(levelOffset, (int) (layer.levelOffset >> 3));//左指针偏移 8字节对齐
        storeIntOffset(levelOffset + usedIndex, layer.used);
        storeIntOffset(levelOffset + capacityIndex, layer.capacity);
        //其他zero
        storeIntOffset(levelOffset + rightOffsetIndex, (int) layer.rightOffset>>3);

        storeLongOffset(levelOffset + dataLayerDescSize, value);
        return true;

    }

    private DataLayer newDataBlock(long basePtrNext, int levelCapacity) throws IOException {
        //当前层已满,需要扩容
        //分配新的区域存储

        DataLayer layer = new DataLayer();
        layer.levelOffset = basePtrNext;
        layer.used = 0;
        layer.capacity = levelCapacity;
        layer.rightOffset = 0;

        storeIntOffset(basePtrNext, (int) (layer.levelOffset >> 3));//左指针偏移 8字节对齐
        storeIntOffset(basePtrNext + usedIndex, layer.used);
        storeIntOffset(basePtrNext + capacityIndex, layer.capacity);
        //其他zero
        storeIntOffset(basePtrNext + rightOffsetIndex, 0);
        return layer;

    }

    private boolean storeValueWithNextLevel(long value) throws IOException {
        storeLongOffset(levelOffset + dataLayerDescSize + (long) levelUsed * dataUnitSize, value);
        levelUsed++;

        storeIntOffset(levelOffset + usedIndex, levelUsed);

        return true;

    }

    private int remove(long value) {
        writeLock.lock();
        int count = 0;
        try {
            for (DataLayer currentLayer : dataLayers) {
                if (currentLayer.used > 0) {

                    long offsetData = currentLayer.levelOffset + dataLayerDescSize;//数据区
                    int offsetDataUnit = (int) (offsetData % BLOCK_SIZE);
                    MappedByteBuffer buffer = null;
                    MappedByteBuffer longBuffer = null;
                    loadDataLayer(currentLayer, buffer, longBuffer);
                    for (int i = 0; i < currentLayer.used; i++) {
                        int longBufferIndex = i * dataUnitSize;
                        long v = longBuffer.getLong(longBufferIndex);
                        if (v == value) {
                            currentLayer.used--;
                            minusTotal(1);
                            storeIntOffset(currentLayer.levelOffset + usedIndex, currentLayer.used);
                            int currentIndex = offsetDataUnit + longBufferIndex;
                            buffer.put(currentIndex, longBuffer,
                                    longBufferIndex + dataUnitSize, (currentLayer.used - i - 1) * dataUnitSize);
                            count++;
                        }
                    }

                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            writeLock.unlock();
        }
        return count;
    }

    private void loadDataLayer(DataLayer dataLayer, MappedByteBuffer buffer, MappedByteBuffer longBuffer) throws IOException {
        long offsetData = dataLayer.levelOffset + dataLayerDescSize;//数据区
        long bufferIndexBase = offsetData / BLOCK_SIZE;
        int offsetDataUnit = (int) (offsetData % BLOCK_SIZE);
        long limit = offsetData + (long) dataLayer.capacity * dataUnitSize;
        long bufferIndexLimit = limit / BLOCK_SIZE;
        boolean isSameBuffer = bufferIndexBase == bufferIndexLimit;
        if (isSameBuffer) {
            buffer = loadBuffer(bufferIndexBase);
            longBuffer = buffer.slice(offsetDataUnit, dataLayer.used * dataUnitSize);

        } else {
            buffer = loadFrame(bufferIndexBase, offsetDataUnit + dataLayer.capacity * dataUnitSize);
            longBuffer = buffer.slice(offsetDataUnit, dataLayer.used * dataUnitSize);
            //longBuffer = loadFrame(offsetData, dataLayer.capacity * dataUnitSize);
        }
    }

    /**
     * 查找 value 在列表中的第一个位置（线性扫描）。
     *
     * @return 找到返回 index（从 0 开始），找不到返回 -1
     */
    public int index(long value) {
//        long bufferIndex = levelOffset / BLOCK_SIZE;
        int index = 0;
        readLock.lock();
        try {
            for (DataLayer currentLayer : dataLayers) {
                if (currentLayer.used > 0) {
                    long offsetData = currentLayer.levelOffset + dataLayerDescSize;//数据区
                    //int offsetDataUnit = (int) (offsetData % BLOCK_SIZE);
                    MappedByteBuffer buffer = null;
                    MappedByteBuffer longBuffer = null;
                    loadDataLayer(currentLayer, buffer, longBuffer);
                    for (int i = 0; i < currentLayer.used; i++) {
                        int longBufferIndex = i * dataUnitSize;
                        long v = longBuffer.getLong(longBufferIndex);
                        if (v == value) {
                            return index;
                        }
                        index++;
                    }

                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            readLock.unlock();
        }
        return -1;
    }

    /**
     * 是否包含指定 value（等价于 index(value)!=-1）。
     */
    public boolean contains(long value) {
        // 调用index方法，传入value参数，获取index值
        int index = index(value);
        // 如果index值不等于-1，则返回true，表示包含该值
        return index != -1;
    }

    /**
     * 是否包含集合中的所有元素（按值判断）。
     */
    public boolean containsAll(Collection<Long> c) {
        Set<Long> retains = new HashSet<>(c.size());
        retains.addAll(c);
        forEach((value, index) -> {
            retains.remove(value);
            if (retains.isEmpty()) {//已处理完
                return true;//结束循环
            }
            return false;//继续循环
        });

        return retains.isEmpty();
    }

    /**
     * 保留集合 c 中出现过的元素，移除其余元素。
     *
     * <p>该方法会重建列表：先遍历收集保留元素，再 clear 后批量 addAll。</p>
     */
    public boolean retainAll(Collection<Long> c) {
        // long[] valueArray = new long[c.size()];
        List<Long> retains = new ArrayList(c.size());
        Set<Long> values = new HashSet<>(c.size());
        values.addAll(c);
//        int i = 0;
//        for(Long value : c){
//            valueArray[i++] = value;
//        }
        forEach((value, index) -> {
//            for(int j = 0; j < valueArray.length; j++){
//                if(value == valueArray[j]){
//                    retains.add(value);
//                }
//            }
            if (values.remove(value)) {
                retains.add(value);
                if (values.isEmpty()) {//已处理完
                    return true;//结束循环
                }
            }
            return false;//继续循环
        });
        this.clear();
        if (!retains.isEmpty()) {
            addAll(retains);
        }
        return !this.isEmpty();
    }

    private boolean isEmpty() {
        return total == 0;
    }

    /**
     * 清空列表（保留层结构，仅把各层 used 置 0，并把 total 置 0）。
     */
    public void clear() {
        writeLock.lock();
        try {
            int layerCount = dataLayers.size();
            for (int i = 0; i < layerCount; i++) {
               DataLayer layer = dataLayers.get(i);
                layer.used = 0;
                storeIntOffset(layer.levelOffset + usedIndex, layer.used);
            }
            level = 0;
            setCurrentLevel(level);

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            setTotal(0);
            writeLock.unlock();
        }
    }


    /**
     * 返回一个迭代器（从头到尾）。
     *
     * <p>迭代器会按层扫描；在迭代过程中修改列表可能影响行为。</p>
     */
    public final Iterator<Long> iterator() {
        return new DsListIterator(this);
    }

    //@Override
    private boolean removeAll(Collection<Long> c) {
        DsIndexWrapper count = new DsIndexWrapper();
        Set<Long> retains = new HashSet<>(c.size());
        retains.addAll(c);
        reversedForEach((value, index) -> {//反向遍历,以防止重复移动数据
            if (retains.contains(value)) {//包含元素
                this.remove(index);
                count.value++;

            }
            return false;//继续循环
        });

        return count.value > 0;

    }


    /**
     * 压缩存储空间。
     * <p>
     * 通过从尾部移动数据填充前面层中的空隙，来回收空间并减少碎片。
     * 这是一个维护性操作，可能会比较耗时。
     * </p>
     */
    public void compact() {
        writeLock.lock();
        int totalSaved = total;
        try {

            List<DataLayer> removedLayers = new ArrayList<>();
            compactInner(removedLayers);
            level = dataLayers.size() - 1;
            if (!removedLayers.isEmpty()) {
                DataLayer lastLayer = dataLayers.get(level);
                if (lastLayer.used == lastLayer.capacity) {
                    dataLayers.add(removedLayers.remove(removedLayers.size() - 1));
                    level++;
                }
                lastLayer = removedLayers.remove(removedLayers.size() - 1);//最后一个block邻近在用内存区,特殊处理
                long offset = lastLayer.levelOffset << 3;
                long rest = offset % BLOCK_SIZE;
                if (rest == 0) {
                    long base = offset / BLOCK_SIZE;
                    unloadBuffer(base);
                    unloadFrame(base);
                }
                for (DataLayer layer : removedLayers) {
                    offset = layer.levelOffset << 3;
                    long base = offset / BLOCK_SIZE;
                    unloadBuffer(base);
                    unloadFrame(base);
                }
                return;
            }
            setCurrentLevel(level);
        } catch (Exception e) {
             // TODO: 优化异常处理逻辑
            throw new RuntimeException("Failed to compact DsList", e);
        } finally {
            setTotal(totalSaved);
            writeLock.unlock();
        }
    }


    private void compactInner(List<DataLayer> removedLayers) {
        try {
            boolean needNext = false;
            int layerCount = dataLayers.size() - 1;
            for (int i = 0; i < layerCount; i++) {
                if (compact(i)) {
                    DataLayer lastLayer = dataLayers.get(layerCount);
                    if (lastLayer.used == 0) {
                        removedLayers.add(lastLayer);
                        dataLayers.remove(layerCount - 1);
                        needNext = true;break;
                    }
                }

            }
            if (needNext) {
                compactInner(removedLayers);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean compact(int levleX) {
        boolean compacted = false;
        try {
            int layerCount = dataLayers.size();

            for (int i = levleX; i < layerCount; i++) {
                DataLayer layer = dataLayers.get(i);
                int rest = layer.capacity - layer.used;
                if (rest > 0) {
                    //当前层有剩余空间,从尾部开始移动数据到当前空闲位置,无序实现
                    //TODO: 有序实现,预估移动数据太多,不如新建文件全新存储
                    long[] values = removeTail(rest);
                    MappedByteBuffer buffer = null;
                    MappedByteBuffer longBuffer = null;
                    loadDataLayer(layer, buffer, longBuffer);
                    longBuffer.asLongBuffer().put(layer.used, values, 0, rest);
                    layer.used += rest;
                    storeIntOffset(layer.levelOffset + usedIndex, layer.used);
                    compacted = true;
                }

            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return compacted;
    }

    private void setCurrentLevel(int level) {
        DataLayer layer = dataLayers.get(level);
        levelOffset = layer.levelOffset;
        levelUsed = layer.used;
        levelCapacity = layer.capacity;
    }

    /**
     * 查找 value 在列表中的最后一个位置（反向线性扫描）。
     *
     * @return 找到返回 index（从 0 开始），找不到返回 -1
     */
    public int lastIndex(long value) {
//        long bufferIndex = levelOffset / BLOCK_SIZE;
        int index = 0;
        readLock.lock();
        try {
            int layerSize = dataLayers.size();
            for (int i = layerSize - 1; i >= 0; i--) {
                DataLayer currentLayer = dataLayers.get(i);
                if (currentLayer.used > 0) {
                    long offsetData = currentLayer.levelOffset + dataLayerDescSize;//数据区
//                    int offsetDataUnit = (int) (offsetData % BLOCK_SIZE);
                    MappedByteBuffer buffer = null;
                    MappedByteBuffer longBuffer = null;
                    loadDataLayer(currentLayer, buffer, longBuffer);
                    for (int j = currentLayer.used; j >= 0; j--) {
                        int longBufferIndex = j * dataUnitSize;
                        long v = longBuffer.getLong(longBufferIndex);
                        if (v == value) {
                            return index;
                        }
                        index++;
                    }

                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            readLock.unlock();
        }
        return -1;
    }

    /**
     * 移除指定索引处的元素。
     * @param index 要移除的元素索引 (0-based)
     * @return 被移除的元素值，如果失败则返回 null
     */
    public Long remove(int index) {
        // writeLock.lock(); // 已移除全局锁，使用分层锁
        try {
            int currentIndex = 0;
            for (DataLayer layer : dataLayers) {
                if (layer.used > 0) {
                    if (index <= (currentIndex + layer.used)) {
                        // 锁定当前层
                        layer.lock.writeLock().lock();
                        try {
                            long offsetData = layer.levelOffset + dataLayerDescSize;//数据区
                            int offsetDataUnit = (int) (offsetData % BLOCK_SIZE);
                            MappedByteBuffer buffer = null;
                            MappedByteBuffer longBuffer = null;
                            loadDataLayer(layer, buffer, longBuffer);
                            int i = index - currentIndex - 1;
                            int longBufferIndex = i * dataUnitSize;
                            long v = longBuffer.getLong(longBufferIndex);
                            layer.used--;
                            minusTotal(1);
                            //Long oldValue = loadLong(currentLayer.levelOffset + usedIndex);;
                            storeIntOffset(layer.levelOffset + usedIndex, layer.used);
                            int currentPosition = offsetDataUnit + longBufferIndex;
                            // 移动后续元素填补空缺
                            buffer.put(currentPosition, longBuffer,
                                    longBufferIndex + dataUnitSize, (layer.used - i - 1) * dataUnitSize);
                            return v;
                        } finally {
                            layer.lock.writeLock().unlock();
                        }
                    } else {
                        currentIndex += layer.used;
                    }
                }

            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // finally {
        //    writeLock.unlock();
        // }
        return null;
    }

    /**
     * 获取指定索引处的元素。
     * @param index 要获取的元素索引 (0-based)。
     * @return 对应的值，如果索引越界或发生错误则返回 null。
     */
    public Long get(int index) {
        try {
            // readLock.lock(); // 已移除全局锁，使用分层锁
            int currentIndex = 0;
            // 遍历所有层以找到包含该索引的层
            for (DataLayer currentLayer : dataLayers) {
                if (currentLayer.used > 0) {
                    if (index <= (currentIndex + currentLayer.used)) {
                        // 锁定当前层
                        currentLayer.lock.readLock().lock();
                        try {
                            long offsetData = currentLayer.levelOffset + dataLayerDescSize;//数据区
    //                        int offsetDataUnit = (int) (offsetData % BLOCK_SIZE);
                            MappedByteBuffer buffer = null;
                            MappedByteBuffer longBuffer = null;
                            loadDataLayer(currentLayer, buffer, longBuffer);
                            int i = index - currentIndex - 1;
                            int longBufferIndex = i * dataUnitSize;
                            return longBuffer.getLong(longBufferIndex);
                        } finally {
                            currentLayer.lock.readLock().unlock();
                        }
                    } else {
                        currentIndex += currentLayer.used;
                    }
                }

            }

        } catch (Exception e) {
             // TODO: 优化异常处理逻辑
            throw new RuntimeException("Failed to get value from DsList", e);
        } 
        // finally {
        //    readLock.unlock();
        // }
        return null;
    }

    /**
     * 从 index 开始移除 count 个元素并返回被移除的元素数组。
     *
     * <p>该方法会跨层移动数据填补空洞，时间复杂度与 count 以及跨越层数相关。</p>
     */
    public long[] remove(int index, int count) {
        writeLock.lock();
        try {
            int currentIndex = 0;
            int layerIndex = 0;
            for (DataLayer layer : dataLayers) {
                if (layer.used > 0) {
                    if (index <= (currentIndex + layer.used - 1)) {
                        long offsetData = layer.levelOffset + dataLayerDescSize;//数据区
                        int offsetDataUnit = (int) (offsetData % BLOCK_SIZE);
                        MappedByteBuffer buffer = null;
                        MappedByteBuffer longBuffer = null;
                        loadDataLayer(layer, buffer, longBuffer);
                        int i = index - currentIndex;
                        //int longBufferIndex = i * dataUnitSize;
                        int rest = layer.used - i;
                        if (rest >= count) {
                            layer.used -= count;
                            //Long oldValue = loadLong(currentLayer.levelOffset + usedIndex);;
                            storeIntOffset(layer.levelOffset + usedIndex, layer.used);
                            // int currentPosition = offsetDataUnit + longBufferIndex;
                            long[] oldValues = new long[count];
                            longBuffer.asLongBuffer().get(i, oldValues, 0, count);
                            //拷贝后面的数据到i的位置
                            buffer.put(offsetDataUnit + i * dataUnitSize, longBuffer,
                                    dataUnitSize * count, (layer.used - i - count) * dataUnitSize);
                            return oldValues;
                        } else {
                            long[] oldValues = new long[count];
                            layer.used -= rest;
                            //Long oldValue = loadLong(currentLayer.levelOffset + usedIndex);;
                            storeIntOffset(layer.levelOffset + usedIndex, layer.used);
                            // int currentPosition = offsetDataUnit + longBufferIndex;
                            longBuffer.asLongBuffer().get(i, oldValues, 0, rest);

                            int allRest = count - rest;
                            int offset = 0;
                            DataLayer layerX = layer;
                            while (allRest > 0) {
                                allRest = allRest - layerX.used;
                                int currentCount = layerX.used;
                                if (allRest > 0) {
                                    layerX.used = 0;
                                    storeIntOffset(layerX.levelOffset + usedIndex, layerX.used);
                                    longBuffer.asLongBuffer().get(0, oldValues, offset, currentCount);
                                    offset += currentCount;
                                } else {
                                    rest = layerX.used + allRest;
                                    layerX.used -= rest;
                                    storeIntOffset(layerX.levelOffset + usedIndex, layerX.used);
                                    longBuffer.asLongBuffer().get(offset, oldValues, offset, rest);
                                    if (layerX.used > 0) {// 如果层中仍有数据，将其存入缓冲区
                                        buffer.put(offsetDataUnit, longBuffer,
                                                dataUnitSize * layerX.used, (layerX.used - rest) * dataUnitSize);
                                    }
                                    return oldValues;
                                }
                                layerIndex++;
                                layerX = dataLayers.get(layerIndex);
                                offsetData = layerX.levelOffset + dataLayerDescSize;//数据区
                                //bufferIndexBase = offsetData / BLOCK_SIZE;
                                offsetDataUnit = (int) (offsetData % BLOCK_SIZE);
                                loadDataLayer(layerX, buffer, longBuffer);
                            }

                        }

                    } else {
                        currentIndex += layer.used;
                    }
                }
                layerIndex++;
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            minusTotal(count);
            writeLock.unlock();
        }
        return null;
    }

    /**
     * 在指定位置插入一个元素。
     *
     * <p>该实现可能触发数据层分裂/新增层，并会移动后续元素。</p>
     */
    public void add(int index, long value) {
        writeLock.lock();
        try {
            int currentIndex = 0;
            int layerIndex = 0;
            for (DataLayer layer : dataLayers) {
                if (layer.used > 0 || index == 0) {
                    if (index <= (currentIndex + layer.used - 1) || index == 0) {
                        long offsetData = layer.levelOffset + dataLayerDescSize;//数据区
                        int offsetDataUnit = (int) (offsetData % BLOCK_SIZE);
                        MappedByteBuffer buffer = null;
                        MappedByteBuffer longBuffer = null;
                        loadDataLayer(layer, buffer, longBuffer);
                        int i = index - currentIndex;
                        int longBufferIndex = i * dataUnitSize;
                        if (layer.used < layer.capacity) {
                            //更新数据区,后移一个位置
                            buffer.put(offsetDataUnit + dataUnitSize * (i + 1), longBuffer,
                                    dataUnitSize * i, (layer.used - i) * dataUnitSize);
                            longBuffer.putLong(longBufferIndex, value);
                            layer.used++;
                            storeIntOffset(layer.levelOffset + usedIndex, layer.used);
                            addTotal(1);
                            return;
                        } else if (index == 0 || i == 0) {//如果是队列头部或数据块边界添加,快速处理
                            //新增一个数据层
                            //计算新数据层容量
                            //计算新数据层ptr
                            long basePtrNext = updateHeaderForNewOffset(1, (long) layer.capacity * dataUnitSize + dataLayerDescSize);
                            DataLayer layerNew = newDataBlock(basePtrNext, layer.capacity);
                            //更新数据区
                            MappedByteBuffer bufferNew = null;
                            MappedByteBuffer longBufferNew = null;
                            loadDataLayer(layerNew, bufferNew, longBufferNew);
                            longBufferNew.putLong(0, value);//存储新数据

                            //更新数据层头信息
                            layerNew.used = 1;
                            storeIntOffset(layerNew.levelOffset + usedIndex, layerNew.used);

                            layerNew.rightOffset = layer.levelOffset;
                            storeIntOffset(layerNew.levelOffset + rightOffsetIndex, (int) (layerNew.rightOffset >> 3));
                            if (layerIndex > 0) {//如果不是队列头部,更新levelOffset
                                DataLayer layerPrev = dataLayers.get(layerIndex - 1);
                                layerPrev.rightOffset = layerNew.levelOffset;
                                storeIntOffset(layerPrev.levelOffset + rightOffsetIndex, (int) (layerPrev.rightOffset >> 3));
                            }
                            dataLayers.add(layerIndex, layerNew);
                            return;

                        } else {
                            //新增一个数据层
                            //计算新数据层容量
                            //计算新数据层ptr
                            long basePtrNext = updateHeaderForNewOffset(1, (long) layer.capacity * dataUnitSize + dataLayerDescSize);
                            DataLayer layerNew = newDataBlock(basePtrNext, layer.capacity);
                            //分裂并更新数据区
                            MappedByteBuffer bufferNew = null;
                            MappedByteBuffer longBufferNew = null;
                            loadDataLayer(layerNew, bufferNew, longBufferNew);
                            longBufferNew.putLong(0, value);//存储新数据
                            //拷贝剩余数据
                            int rest = layer.used - i;
                            longBufferNew.put(dataUnitSize, longBuffer,
                                    dataUnitSize * i, rest * dataUnitSize);
                            //更新数据层头信息
                            layerNew.used = 1 + rest;
                            storeIntOffset(layerNew.levelOffset + usedIndex, layerNew.used);

                            layerNew.rightOffset = layer.levelOffset;
                            storeIntOffset(layerNew.levelOffset + rightOffsetIndex, (int) (layerNew.rightOffset >> 3));

                            layer.used -= rest;
                            storeIntOffset(layer.levelOffset + usedIndex, layer.used);
                            layer.rightOffset = layerNew.levelOffset;
                            storeIntOffset(layer.levelOffset + rightOffsetIndex, (int) (layer.rightOffset >> 3));
                            dataLayers.add(layerIndex, layerNew);
                            return;
                        }

                    } else {
                        currentIndex += layer.used;
                    }
                }
                layerIndex++;
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //addTotal(1);
            writeLock.unlock();
        }
    }

    /**
     * 在指定位置插入多个元素（数组）。
     */
    public void addAll(int index, long[] values) {
        writeLock.lock();
        try {
            int currentIndex = 0;
            int layerIndex = 0;
            int count = values.length;
            for (DataLayer layer : dataLayers) {
                if (layer.used > 0 || index == 0) {
                    if (index <= (currentIndex + layer.used - 1) || index == 0) {
                        long offsetData = layer.levelOffset + dataLayerDescSize;//数据区
                        int offsetDataUnit = (int) (offsetData % BLOCK_SIZE);
                        MappedByteBuffer buffer = null;
                        MappedByteBuffer longBuffer = null;
                        loadDataLayer(layer, buffer, longBuffer);
                        int i = index - currentIndex;
                        int rest = layer.used - i;
                        //int longBufferIndex = i * dataUnitSize;
                        if ((layer.capacity - layer.used) > count) {//可容纳新数据
                            //更新数据区,后移count个位置
                            buffer.put(offsetDataUnit + dataUnitSize * (i + count + 1), longBuffer,
                                    dataUnitSize * (i + count), rest * dataUnitSize);
                            longBuffer.asLongBuffer().put(i, values);//存储新数据
                            layer.used += count;
                            storeIntOffset(layer.levelOffset + usedIndex, layer.used);
                            addTotal(count);
                            return;
                        } else if (index == 0 || i == 0) {//如果是队列头部或数据块边界添加,快速处理
                            //计算新数据层容量
                            int capacity = layer.capacity;
                            int half = capacity >> 1;
                            if (count > half) {
                                capacity = capacity << 1;//翻倍
                                if (count > capacity) {
                                    capacity = (count >> 3) << 4;//8字节对齐翻倍容量
                                }
                            }
                            //计算新数据层ptr
                            long basePtrNext = updateHeaderForNewOffset(count, (long) capacity * dataUnitSize + dataLayerDescSize);
                            DataLayer layerNew = newDataBlock(basePtrNext, layer.capacity);
                            //更新数据区
                            MappedByteBuffer bufferNew = null;
                            MappedByteBuffer longBufferNew = null;
                            loadDataLayer(layerNew, bufferNew, longBufferNew);
                            longBufferNew.asLongBuffer().put(0, values);//存储新数据

                            //更新数据层头信息
                            layerNew.used = count;
                            storeIntOffset(layerNew.levelOffset + usedIndex, layerNew.used);

                            layerNew.rightOffset = layer.levelOffset;
                            storeIntOffset(layerNew.levelOffset + rightOffsetIndex, (int) (layerNew.rightOffset >> 3));
                            if (layerIndex > 0) {//如果不是队列头部,更新levelOffset
                                DataLayer layerPrev = dataLayers.get(layerIndex - 1);
                                layerPrev.rightOffset = layerNew.levelOffset;
                                storeIntOffset(layerPrev.levelOffset + rightOffsetIndex, (int) (layerPrev.rightOffset >> 3));
                            }
                            dataLayers.add(layerIndex, layerNew);
                            return;

                        } else {
                            //新增一个数据层
                            //计算新数据层容量
                            int capacity = layer.capacity;
                            int half = capacity >> 1;
                            if (count > half) {
                                capacity = capacity << 1;//翻倍
                                if (count > capacity) {
                                    capacity = (count >> 3) << 4;//8字节对齐翻倍容量
                                }
                            }

                            //计算新数据层ptr
                            long basePtrNext = updateHeaderForNewOffset(count, (long) capacity * dataUnitSize + dataLayerDescSize);
                            DataLayer layerNew = newDataBlock(basePtrNext, layer.capacity);
                            //更新数据区
                            MappedByteBuffer bufferNew = null;
                            MappedByteBuffer longBufferNew = null;
                            loadDataLayer(layerNew, bufferNew, longBufferNew);

                            longBufferNew.asLongBuffer().put(0, values);//存储新数据
                            //拷贝剩余数据
                            longBufferNew.put(dataUnitSize * count, longBuffer,
                                    dataUnitSize * i, rest * dataUnitSize);

                            //更新数据层头信息
                            layerNew.used = count + rest;
                            storeIntOffset(layerNew.levelOffset + usedIndex, layerNew.used);

                            layerNew.rightOffset = layer.rightOffset;
                            storeIntOffset(layerNew.levelOffset + rightOffsetIndex, (int) (layerNew.rightOffset >> 3));

                            layer.used -= rest;
                            storeIntOffset(layer.levelOffset + usedIndex, layer.used);
                            layer.rightOffset = layerNew.levelOffset;
                            storeIntOffset(layer.levelOffset + rightOffsetIndex, (int) (layer.rightOffset >> 3));
                            dataLayers.add(layerIndex, layerNew);
                            return;
                        }

                    } else {
                        currentIndex += layer.used;
                    }
                }
                layerIndex++;
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //addTotal(1);
            writeLock.unlock();
        }
    }

    /**
     * 设置指定位置的元素值。
     */
    public void set(int index, long value) {
        // writeLock.lock(); // 已移除全局锁，使用分层锁
        try {
            int currentIndex = 0;
//            int layerIndex = 0;
            for (DataLayer layer : dataLayers) {
                if (layer.used > 0) {
                    if (index <= (currentIndex + layer.used - 1)) {
                        layer.lock.writeLock().lock();
                        try {
                            int i = index - currentIndex;
                            storeLongOffset(layer.levelOffset + dataLayerDescSize + (long) i * dataUnitSize, value);
                            //storeInt(layer.levelOffset + usedIndex, layer.used);
                            return;
                        } finally {
                            layer.lock.writeLock().unlock();
                        }
                    } else {
                        currentIndex += layer.used;
                    }
                }
//                layerIndex++;
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // finally {
        //    //addTotal(1);
        //    writeLock.unlock();
        // }

    }

    /**
     * 从 index 开始批量覆盖写入多个元素（数组）。
     */
    public void set(int index, long[] values) {
        writeLock.lock();
        try {
            int currentIndex = 0;
            int count = values.length;
            int countRest = values.length;
            boolean isLast = false;
            for (int layerIndex = 0; layerIndex < dataLayers.size(); layerIndex++) {
                DataLayer layer = dataLayers.get(layerIndex);
                if (layer.used > 0) {
                    if (index <= (currentIndex + layer.used - 1)) {
                        MappedByteBuffer buffer = null;
                        MappedByteBuffer longBuffer = null;
                        loadDataLayer(layer, buffer, longBuffer);
                        int i = index - currentIndex;
                        int rest = layer.used - i;
                        if (rest > countRest) {//可容纳新数据

                            longBuffer.asLongBuffer().put(i, values);//替换对应位置为新数据
                            return;
                        } else {
                            longBuffer.asLongBuffer().put(i, values, count - countRest, rest);//替换对应位置为新数据
                            //layer.used += rest;
                            //storeInt(layer.levelOffset + usedIndex, layer.used);
                            countRest -= rest;
                            DataLayer layerNew = null;
                            if (layerIndex < dataLayers.size()) {
                                layerNew = dataLayers.get(layerIndex);
                            } else {//最后一层特殊处理
                                isLast = true;
                                //新增一个数据层
                                //计算新数据层容量
                                int capacity = layer.capacity;
                                int half = capacity >> 1;
                                if (countRest > half) {
                                    capacity = capacity << 1;//翻倍
                                    if (count > capacity) {
                                        capacity = (countRest >> 3) << 4;//8字节对齐翻倍容量
                                    }
                                }

                                //计算新数据层ptr
                                long basePtrNext = updateHeaderForNewOffset(count, (long) capacity * dataUnitSize + dataLayerDescSize);
                                layerNew = newDataBlock(basePtrNext, layer.capacity);
                                layer.rightOffset = layerNew.levelOffset;
                                storeIntOffset(layer.levelOffset + rightOffsetIndex, (int) (layer.rightOffset >> 3));
                                dataLayers.add(layerNew);
                            }
                            int restNew = countRest - rest;
                            if (!isLast && restNew < layerNew.used) {
                                //更新数据区
                                MappedByteBuffer bufferNew = null;
                                MappedByteBuffer longBufferNew = null;
                                loadDataLayer(layerNew, bufferNew, longBufferNew);

                                longBufferNew.asLongBuffer().put(0, values, count - countRest, restNew);//存储新数据

                                //layerNew.used = restNew;
                                //storeInt(layerNew.levelOffset + usedIndex, layerNew.used);
                                //addTotal(restNew);
                                return;
                            } else if (isLast) {
                                //新增数据区存储,index+values.length超出当前数据最大索引
                                MappedByteBuffer bufferNew = null;
                                MappedByteBuffer longBufferNew = null;
                                loadDataLayer(layerNew, bufferNew, longBufferNew);

                                longBufferNew.asLongBuffer().put(0, values, count - countRest, restNew);//存储新数据

                                layerNew.used = restNew;
                                storeIntOffset(layerNew.levelOffset + usedIndex, layerNew.used);
                                addTotal(restNew);
                                return;
                            } else {
                                //更新数据区
                                MappedByteBuffer bufferNew = null;
                                MappedByteBuffer longBufferNew = null;
                                loadDataLayer(layerNew, bufferNew, longBufferNew);

                                longBufferNew.asLongBuffer().put(0, values, count - countRest, layerNew.used);//存储新数据
                                countRest -= layerNew.used;                                //layerNew.used = restNew;
                                //继续循环,直到countRest为0
                            }


                        }
                    } else {
                        currentIndex += layer.used;
                    }
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //addTotal(1);
            writeLock.unlock();
        }

    }

    /**
     * 从头部移除 count 个元素并返回。
     */
    public long[] removeHead(int count) {

        return remove(0, count);
    }

    /**
     * 从尾部移除 count 个元素并返回。
     */
    public long[] removeTail(int count) {

        return remove(total - count - 1, count);
    }

    /**
     * 在头部插入一个元素。
     */
    public void addHead(long value) {

        add(0, value);
    }

    /**
     * 在头部插入多个元素（数组）。
     */
    public void addAllHead(long[] values) {

        addAll(0, values);
    }

    /**
     * 在头部插入多个元素（集合）。
     */
    public void addAllHead(Collection<Long> values) {

        addAll(0, values);
    }

    /**
     * 在指定位置插入多个元素（集合）。
     */
    public void addAll(int index, Collection<Long> values) {
        long[] valueArray = new long[values.size()];
        int i = 0;
        for (Long value : values) {
            valueArray[i++] = value;
        }
        addAll(index, valueArray);
    }

    /**
     * 追加多个元素（集合）。
     */
    public void addAll(Collection<Long> values) {
        long[] valueArray = new long[values.size()];
        int i = 0;
        for (Long value : values) {
            valueArray[i++] = value;
        }
        addAll(valueArray);
    }

    /**
     * 从 index 开始读取 count 个元素并返回数组。
     */
    public long[] get(int index, int count) {
        try {
            int currentIndex = 0;
            int layerIndex = 0;
            for (DataLayer currentLayer : dataLayers) {
                if (currentLayer.used > 0) {
                    if (index <= (currentIndex + currentLayer.used - 1)) {
                        MappedByteBuffer buffer = null;
                        MappedByteBuffer longBuffer = null;
                        loadDataLayer(currentLayer, buffer, longBuffer);
                        int i = index - currentIndex;
                        int rest = currentLayer.used - i;
                        if (rest >= count) {
                            storeIntOffset(currentLayer.levelOffset + usedIndex, currentLayer.used);
                            long[] oldValues = new long[count];
                            longBuffer.asLongBuffer().get(i, oldValues, 0, count);
                            return oldValues;
                        } else {
                            long[] oldValues = new long[count];
                            minusTotal(count);
                            longBuffer.asLongBuffer().get(i, oldValues, 0, rest);

                            int allRest = count - rest;
                            int offset = 0;
                            DataLayer layer = currentLayer;
                            while (allRest > 0) {
                                allRest = allRest - layer.used;
                                int currentCount = layer.used;
                                if (allRest > 0) {
                                    storeIntOffset(layer.levelOffset + usedIndex, layer.used);
                                    longBuffer.asLongBuffer().get(0, oldValues, offset, currentCount);
                                    offset += currentCount;
                                } else {
                                    rest = layer.used + allRest;
                                    longBuffer.asLongBuffer().get(0, oldValues, offset, rest);
                                    return oldValues;
                                }
                                layerIndex++;
                                layer = dataLayers.get(layerIndex);
                                loadDataLayer(currentLayer, buffer, longBuffer);
                            }

                        }

                    } else {
                        currentIndex += currentLayer.used;
                    }
                }
                layerIndex++;
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    /**
     * 快速删除：在内部直接移动内存块以移除区间（通常比逐个 remove 更少的边界检查）。
     */
    public void fastRemove(int index, int count) {
        headerOpLock.lock();
        try {
            int currentIndex = 0;
            int layerIndex = 0;
            for (DataLayer currentLayer : dataLayers) {
                if (currentLayer.used > 0) {
                    if (index <= (currentIndex + currentLayer.used - 1)) {
                        long offsetData = currentLayer.levelOffset + dataLayerDescSize;//数据区
                        int offsetDataUnit = (int) (offsetData % BLOCK_SIZE);
                        MappedByteBuffer buffer = null;
                        MappedByteBuffer longBuffer = null;
                        loadDataLayer(currentLayer, buffer, longBuffer);
                        int i = index - currentIndex;
                        int longBufferIndex = i * dataUnitSize;
                        int rest = currentLayer.used - i;
                        if (rest >= count) {
                            currentLayer.used -= count;
                            minusTotal(count);
                            //Long oldValue = loadLong(currentLayer.levelOffset + usedIndex);;
                            storeIntOffset(currentLayer.levelOffset + usedIndex, currentLayer.used);
                            int currentPosition = offsetDataUnit + longBufferIndex;
                            buffer.put(currentPosition, longBuffer,
                                    longBufferIndex + dataUnitSize * count, (currentLayer.used - i - count) * dataUnitSize);
                            return;
                        } else {
                            currentLayer.used -= rest;
                            minusTotal(count);
                            //Long oldValue = loadLong(currentLayer.levelOffset + usedIndex);;
                            storeIntOffset(currentLayer.levelOffset + usedIndex, currentLayer.used);
                            //int currentPosition = offsetDataUnit + longBufferIndex;

                            int allRest = count - rest;
                            //int offset = 0;
                            DataLayer layer = currentLayer;
                            while (allRest > 0) {
                                allRest = allRest - layer.used;
                                //int currentCount = layer.used;
                                if (allRest > 0) {
                                    layer.used = 0;
                                    storeIntOffset(layer.levelOffset + usedIndex, layer.used);
                                    //offset += currentCount;
                                } else {
                                    rest = layer.used + allRest;
                                    layer.used -= rest;
                                    storeIntOffset(layer.levelOffset + usedIndex, layer.used);
                                    // currentPosition = offsetDataUnit;
                                    if (layer.used > 0) {// 如果层中仍有数据，将其存入缓冲区
                                        buffer.put(offsetDataUnit, longBuffer,
                                                longBufferIndex + dataUnitSize * layer.used, (layer.used - rest) * dataUnitSize);
                                    }

                                    return;
                                }
                                layerIndex++;
                                layer = dataLayers.get(layerIndex);
                                offsetData = layer.levelOffset + dataLayerDescSize;//数据区
                                offsetDataUnit = (int) (offsetData % BLOCK_SIZE);
                                loadDataLayer(layer, buffer, longBuffer);
                            }

                        }

                    } else {
                        currentIndex += currentLayer.used;
                    }
                }
                layerIndex++;
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            headerOpLock.unlock();
        }
    }


    /**
     * 数据层元数据描述类。
     * <p>
     * 对应文件中的结构：
     * <ul>
     *     <li>levelOffset: 本层偏移指针 (int, 4字节) >>> 3</li>
     *     <li>used: 本层已使用数量 (int, 4字节)</li>
     *     <li>capacity: 本层容量 (int, 4字节)</li>
     *     <li>rightOffset: 右分支/下一层偏移指针 (int, 4字节) >>> 3</li>
     * </ul>
     * </p>
     */
    static class DataLayer {
        public final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        //public int leftTotal;
        public long levelOffset;
        public int used;
        public int capacity;
        //        public int rightTotal;
        public long rightOffset;
    }


    final class DsListIterator implements Iterator<Long> {

        DsList list;
        //int expectedModCount;  // 用于快速失败机制 (fast-fail)
        int index;             // 当前索引
//

        DsListIterator(DsList list) {
            this.list = list;
            //expectedModCount = list.total;
            if (list.total > 0) {
                index = 0;
            }
            //next = new DsHashSet.DsHashNode(0,i,set.heads[i],set.rootHeadSize);
        }

        /**
         * 是否还有下一个元素。
         */
        public boolean hasNext() {
            if (index >= total) {
                return false;
            }
            return true;
        }

        /**
         * 返回下一个元素。
         */
        public Long next() {
//            if (modCount != expectedModCount)
//                throw new ConcurrentModificationException();
            Long current = get(index);
            if (current != null) {
                index++;
                return current;
            }
            throw new ConcurrentModificationException();

        }

        /**
         * 删除最近一次 next() 返回的元素。
         */
        public void remove() {
            int theIndex = index - 1;
            list.remove(theIndex);
        }
    }


    /**
     * 正向遍历列表，对每个元素执行 action。
     *
     * <p>action 返回 true 表示提前终止遍历。</p>
     */
    public final void forEach(DsProcessLong action) {
        readLock.lock();
        try {
            boolean complete = false;
            DsIndexWrapper index = new DsIndexWrapper();
            for (DataLayer layer : dataLayers) {
                if (layer.used > 0) {
                    for (int i = 0; i < layer.used; i++) {
                        MappedByteBuffer buffer = null;
                        MappedByteBuffer longBuffer = null;
                        loadDataLayer(layer, buffer, longBuffer);
                        complete = action.process(longBuffer.getLong(i * dataUnitSize), index.value);
                        index.value++;
                        if (index.value >= total) {
                            complete = true;
                        }
                        if (complete) {
                            return;
                        }
                    }


                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            readLock.unlock();
        }


    }

    /**
     * 反向遍历列表（从尾到头），对每个元素执行 action。
     *
     * <p>action 返回 true 表示提前终止遍历。</p>
     */
    public final void reversedForEach(DsProcessLong action) {
        readLock.lock();
        try {
            boolean complete = false;
            DsIndexWrapper index = new DsIndexWrapper();
            for (int i = dataLayers.size() - 1; i >= 0; i--) {
                DataLayer layer = dataLayers.get(i);
                if (layer.used > 0) {
                    for (int j = layer.used; j >= 0; j--) {
                        MappedByteBuffer buffer = null;
                        MappedByteBuffer longBuffer = null;
                        loadDataLayer(layer, buffer, longBuffer);
                        complete = action.process(longBuffer.getLong(j * dataUnitSize), index.value);
                        index.value++;
                        if (index.value >= total) {
                            complete = true;
                        }
                        if (complete) {
                            return;
                        }
                    }


                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            readLock.unlock();
        }


    }
}
