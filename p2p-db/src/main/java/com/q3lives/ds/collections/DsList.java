package com.q3lives.ds.collections;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.CopyOnWriteArrayList;
import com.q3lives.ds.core.DsObject;
import com.q3lives.ds.spi.DsProcessLong;

/**
 * 基于内存映射文件的持久化列表实现（重构优化版）
 *
 * 主要改进： 1. 修复并发安全问题 2. 优化内存管理和性能 3. 增强错误处理和边界检查 4. 改进代码结构和可维护性 5. 添加完整的文档和注释
 *
 * @author Refactored Version
 * @version 2.0
 */
public class DsList extends DsObject implements Iterable<Long> {

    // ========================================================================
    // 常量定义
    // ========================================================================
   
   
    
    /**
     * 文件头字段偏移量
     */
    private static final int TOTAL_OFFSET = 16;//->HDR_SIZE
    private static final int NEXT_OFFSET_OFFSET = 64;
    private static final int LEVEL_OFFSET = 8;//->HDR_NEXT_NODE_ID
    private static final int RIGHT_OFFSET_OFFSET = 24;
    
     /**
     * 数据单元大小（long = 8 字节）
     */
    private static final int DATA_UNIT_SIZE = 8;
     /**
      * 标准。文件头大小
     */
     private static final byte[] MAGIC = new byte[]{'.', 'L', 'S', 'T'};
    private static final int HEADER_SIZE = DsFixedBucketStore.HEADER_SIZE;
    private static final int HDR_MAGIC = 0;
    private static final int HDR_VALUE_SIZE = DATA_UNIT_SIZE;
    private static final int HDR_NEXT_NODE_ID = 8;
    private static final int HDR_SIZE = 16;

   

    /**
     * 数据层描述符大小
     */
    private static final int DATA_LAYER_DESC_SIZE = 32;

    /**
     * 默认初始容量
     */
    private static final int DEFAULT_INITIAL_CAPACITY = 16;

    /**
     * 层级容量配置
     */
    private static final int[] DEFAULT_LEVELS_CAPACITY = {
        16, 1024, 4096, 16384, 65536, 262144, 1048576
    };

    

    /**
     * 数据层字段偏移量
     */
    private static final int LAYER_OFFSET_INDEX = 0;
    private static final int LAYER_USED_INDEX = 8;
    private static final int LAYER_CAPACITY_INDEX = 12;
    private static final int LAYER_RIGHT_OFFSET_INDEX = 16;

    // ========================================================================
    // 实例变量
    // ========================================================================
    /**
     * 元素总数
     */
    private volatile long total;

    /**
     * 当前层级
     */
    private volatile int level;

    /**
     * 层级容量配置
     */
    private final int[] levelsCapacity;

    /**
     * 数据层列表（使用 CopyOnWriteArrayList 保证并发安全）
     */
    private final CopyOnWriteArrayList<DataLayer> dataLayers;

    /**
     * 全局读写锁
     */
    private final ReentrantReadWriteLock globalLock;
    private final ReentrantReadWriteLock.ReadLock readLock;
    private final ReentrantReadWriteLock.WriteLock writeLock;

    /**
     * 当前层缓存（减少频繁访问）
     */
    private volatile long levelOffset;
    private volatile int levelUsed;
    private volatile int levelCapacity;

   

    // ========================================================================
    // 内部类：数据层
    // ========================================================================
    /**
     * 数据层结构 每个层包含：层偏移、已用数量、容量、下一层指针
     */
    protected static class DataLayer {

        /**
         * 层在文件中的偏移量
         */
        final long levelOffset;

        /**
         * 已使用的元素数量
         */
        volatile int used;

        /**
         * 层容量
         */
        final int capacity;

        /**
         * 下一层的偏移量（0 表示无下一层）
         */
        volatile long rightOffset;

        /**
         * 层级锁
         */
        final ReentrantReadWriteLock lock;
        final ReentrantReadWriteLock.ReadLock readLock;
        final ReentrantReadWriteLock.WriteLock writeLock;

        DataLayer(long levelOffset, int capacity) {
            this.levelOffset = levelOffset;
            this.capacity = capacity;
            this.used = 0;
            this.rightOffset = 0;
            this.lock = new ReentrantReadWriteLock();
            this.readLock = lock.readLock();
            this.writeLock = lock.writeLock();
        }

        /**
         * 检查层是否已满
         */
        boolean isFull() {
            return used >= capacity;
        }

        /**
         * 检查层是否为空
         */
        boolean isEmpty() {
            return used == 0;
        }

        /**
         * 获取剩余容量
         */
        int remaining() {
            return capacity - used;
        }

        /**
         * 计算数据区起始偏移
         */
        long dataStartOffset() {
            return levelOffset + DATA_LAYER_DESC_SIZE;
        }

        /**
         * 计算指定索引的数据偏移
         */
        long dataOffset(int index) {
            if (index < 0 || index >= capacity) {
                throw new IndexOutOfBoundsException(
                    "Index: " + index + ", Capacity: " + capacity);
            }
            return dataStartOffset() + (long) index * DATA_UNIT_SIZE;
        }

        @Override
        public String toString() {
            return String.format("DataLayer[offset=%d, used=%d, capacity=%d, right=%d]",
                levelOffset, used, capacity, rightOffset);
        }
    }

    // ========================================================================
    // 构造函数
    // ========================================================================
    /**
     * 创建或打开持久化列表
     *
     * @param file 存储文件
     * @throws IOException 如果文件操作失败
     */
    public DsList(File file) throws IOException {
        this(file, DEFAULT_LEVELS_CAPACITY);
    }

    /**
     * 创建或打开持久化列表（自定义层级容量）
     *
     * @param file 存储文件
     * @param levelsCapacity 层级容量配置
     * @throws IOException 如果文件操作失败
     */
    public DsList(File file, int[] levelsCapacity) throws IOException {
        super(file, HEADER_SIZE, DATA_UNIT_SIZE);

        if (levelsCapacity == null || levelsCapacity.length == 0) {
            throw new IllegalArgumentException("levelsCapacity cannot be null or empty");
        }

        this.levelsCapacity = levelsCapacity.clone();
        this.dataLayers = new CopyOnWriteArrayList<>();
        this.globalLock = new ReentrantReadWriteLock();
        this.readLock = globalLock.readLock();
        this.writeLock = globalLock.writeLock();

        initHeader();
    }
    
    
    /**
     * 初始化列表结构
     */
    private void initHeader() throws IOException {
        writeLock.lock();
        try {
            // 读取文件头
            headerBuffer = loadBuffer(0L);
            byte[] m = new byte[4];
            headerBuffer.get(HDR_MAGIC, m, 0, 4);
            if (Arrays.equals(m, MAGIC)) {
                level = headerBuffer.getInt(HDR_NEXT_NODE_ID);
                total = headerBuffer.getInt(HDR_SIZE);
                // 已存在文件，加载数据层
                loadExistingLayers();
            } else {
                 // 新文件，初始化
                initializeNewFile();
                headerBuffer.put(HDR_MAGIC, MAGIC, 0, 4);//4字节
                headerBuffer.putInt(HDR_VALUE_SIZE, DATA_UNIT_SIZE);//value size

                headerBuffer.putInt(HDR_NEXT_NODE_ID, level);
                headerBuffer.putLong(HDR_SIZE, total);
                dirty(0L);
                loadBuffer((long) HEADER_SIZE / BLOCK_SIZE);//标准64字节头
               
            }

            // 更新当前层缓存
            updateCurrentLayerCache();

        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 初始化新文件
     */
    private void initializeNewFile() throws IOException {
        total = 0;
        level = 0;

        // 创建初始数据层
        long firstLayerOffset = HEADER_SIZE;
        int initialCapacity = levelsCapacity[0];

        DataLayer firstLayer = createDataLayer(firstLayerOffset, initialCapacity);
        dataLayers.add(firstLayer);

        // 写入文件头
        storeIntOffset(TOTAL_OFFSET, 0);
        storeLongOffset(NEXT_OFFSET_OFFSET,
            firstLayerOffset + DATA_LAYER_DESC_SIZE + (long) initialCapacity * DATA_UNIT_SIZE);
        storeIntOffset(LEVEL_OFFSET, 0);

        // 写入数据层描述符
        writeLayerDescriptor(firstLayer);
    }

    /**
     * 加载已存在的数据层
     */
    private void loadExistingLayers() throws IOException {
        long currentOffset = HEADER_SIZE;

        while (currentOffset != 0) {
            DataLayer layer = loadDataLayer(currentOffset);
            dataLayers.add(layer);
            currentOffset = layer.rightOffset;
        }

        if (dataLayers.isEmpty()) {
            throw new IOException("Corrupted file: no data layers found");
        }
    }

    /**
     * 创建新数据层
     */
    private DataLayer createDataLayer(long offset, int capacity) {
        return new DataLayer(offset, capacity);
    }

    /**
     * 从文件加载数据层
     */
    private DataLayer loadDataLayer(long offset) throws IOException {
        int used = loadIntOffset(offset + LAYER_USED_INDEX);
        int capacity = loadIntOffset(offset + LAYER_CAPACITY_INDEX);
        long rightOffset = loadLongOffset(offset + LAYER_RIGHT_OFFSET_INDEX);

        DataLayer layer = new DataLayer(offset, capacity);
        layer.used = used;
        layer.rightOffset = rightOffset;

        return layer;
    }

    /**
     * 写入数据层描述符
     */
    private void writeLayerDescriptor(DataLayer layer) throws IOException {
        storeLongOffset(layer.levelOffset + LAYER_OFFSET_INDEX, layer.levelOffset);
        storeIntOffset(layer.levelOffset + LAYER_USED_INDEX, layer.used);
        storeIntOffset(layer.levelOffset + LAYER_CAPACITY_INDEX, layer.capacity);
        storeLongOffset(layer.levelOffset + LAYER_RIGHT_OFFSET_INDEX, layer.rightOffset);
    }

    /**
     * 更新当前层缓存
     */
    private void updateCurrentLayerCache() {
        if (level >= 0 && level < dataLayers.size()) {
            DataLayer currentLayer = dataLayers.get(level);
            levelOffset = currentLayer.levelOffset;
            levelUsed = currentLayer.used;
            levelCapacity = currentLayer.capacity;
        }
    }

    // ========================================================================
    // 公共 API - 基本操作
    // ========================================================================
    /**
     * 返回列表大小
     * use total
     */
    @Deprecated
    public int size() {
        return (int) total;
    }
    
    public long total() {
        return  total;
    }

    /**
     * 检查列表是否为空
     */
    public boolean isEmpty() {
        return total == 0;
    }

    /**
     * 清空列表（保留层结构）
     */
    public void clear() {
        writeLock.lock();
        try {
            for (DataLayer layer : dataLayers) {
                layer.writeLock.lock();
                try {
                    layer.used = 0;
                    storeIntOffset(layer.levelOffset + LAYER_USED_INDEX, 0);
                } finally {
                    layer.writeLock.unlock();
                }
            }

            total = 0;
            level = 0;
            storeIntOffset(TOTAL_OFFSET, 0);
            storeIntOffset(LEVEL_OFFSET, 0);

            updateCurrentLayerCache();

        } catch (IOException e) {
            throw new RuntimeException("Failed to clear list", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 获取指定位置的元素
     *
     * @param index 索引位置
     * @return 元素值
     * @throws IndexOutOfBoundsException 如果索引越界
     */
    public Long get(int index) {
        if (index < 0 || index >= total) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + total);
        }

        readLock.lock();
        try {
            LayerPosition pos = findLayerPosition(index);
            return readValue(pos.layer, pos.localIndex);

        } catch (IOException e) {
            throw new RuntimeException("Failed to get element at index " + index, e);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 设置指定位置的元素
     *
     * @param index 索引位置
     * @param value 新值
     * @return 旧值
     * @throws IndexOutOfBoundsException 如果索引越界
     */
    public Long set(int index, long value) {
        if (index < 0 || index >= total) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + total);
        }

        writeLock.lock();
        try {
            LayerPosition pos = findLayerPosition(index);

            pos.layer.writeLock.lock();
            try {
                long oldValue = readValue(pos.layer, pos.localIndex);
                writeValue(pos.layer, pos.localIndex, value);
                return oldValue;

            } finally {
                pos.layer.writeLock.unlock();
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to set element at index " + index, e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 在列表末尾添加元素
     *
     * @param value 要添加的值
     */
    public void add(long value) {
        writeLock.lock();
        try {
            DataLayer targetLayer = findOrCreateLayerForAdd();

            targetLayer.writeLock.lock();
            try {
                writeValue(targetLayer, targetLayer.used, value);
                targetLayer.used++;
                storeIntOffset(targetLayer.levelOffset + LAYER_USED_INDEX, targetLayer.used);

                total++;
                storeLongOffset(TOTAL_OFFSET, total);

                updateCurrentLayerCache();

            } finally {
                targetLayer.writeLock.unlock();
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to add element", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 在指定位置插入元素
     *
     * @param index 插入位置
     * @param value 要插入的值
     * @throws IndexOutOfBoundsException 如果索引越界
     */
    public void add(int index, long value) {
        if (index < 0 || index > total) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + total);
        }

        if (index == total) {
            add(value);
            return;
        }

        writeLock.lock();
        try {
            // 找到插入位置
            LayerPosition pos = findLayerPosition(index);

            // 从插入位置开始，所有元素后移
            shiftElementsRight(pos, 1);

            // 插入新元素
            pos.layer.writeLock.lock();
            try {
                writeValue(pos.layer, pos.localIndex, value);
            } finally {
                pos.layer.writeLock.unlock();
            }

            total++;
            storeLongOffset(TOTAL_OFFSET, total);

        } catch (IOException e) {
            throw new RuntimeException("Failed to insert element at index " + index, e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 批量添加元素（数组）
     *
     * @param values 要添加的值数组
     */
    public void addAll(long[] values) {
        if (values == null || values.length == 0) {
            return;
        }

        writeLock.lock();
        try {
            for (long value : values) {
                add(value);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 批量添加元素（集合）
     *
     * @param values 要添加的值集合
     */
    public void addAll(Collection<Long> values) {
        if (values == null || values.isEmpty()) {
            return;
        }

        writeLock.lock();
        try {
            for (Long value : values) {
                if (value != null) {
                    add(value);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 移除指定位置的元素
     *
     * @param index 要移除的位置
     * @return 被移除的元素
     * @throws IndexOutOfBoundsException 如果索引越界
     */
    public Long remove(int index) {
        if (index < 0 || index >= total) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + total);
        }

        writeLock.lock();
        try {
            LayerPosition pos = findLayerPosition(index);

            pos.layer.writeLock.lock();
            long removedValue;
            try {
                removedValue = readValue(pos.layer, pos.localIndex);
            } finally {
                pos.layer.writeLock.unlock();
            }

            // 从移除位置开始，所有元素左移
            shiftElementsLeft(pos, 1);

            total--;
            storeLongOffset(TOTAL_OFFSET, total);

            return removedValue;

        } catch (IOException e) {
            throw new RuntimeException("Failed to remove element at index " + index, e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 移除指定范围的元素
     *
     * @param fromIndex 起始索引（包含）
     * @param count 移除数量
     * @return 被移除的元素数组
     */
    public long[] remove(int fromIndex, int count) {
        if (fromIndex < 0 || fromIndex >= total) {
            throw new IndexOutOfBoundsException("Index: " + fromIndex + ", Size: " + total);
        }

        if (count <= 0) {
            return new long[0];
        }

        int actualCount = (int) Math.min(count, total - fromIndex);
        long[] removed = new long[actualCount];

        writeLock.lock();
        try {
            // 读取要移除的元素
            for (int i = 0; i < actualCount; i++) {
                removed[i] = get(fromIndex + i);
            }

            // 批量左移
            LayerPosition pos = findLayerPosition(fromIndex);
            shiftElementsLeft(pos, actualCount);

            total -= actualCount;
            storeLongOffset(TOTAL_OFFSET, total);

            return removed;

        } catch (IOException e) {
            throw new RuntimeException("Failed to remove elements", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 移除第一个匹配的元素
     *
     * @param value 要移除的值
     * @return true 如果找到并移除
     */
    public boolean removeValue(long value) {
        writeLock.lock();
        try {
            int index = indexOf(value);
            if (index >= 0) {
                remove(index);
                return true;
            }
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 查找元素首次出现的位置
     *
     * @param value 要查找的值
     * @return 索引位置，未找到返回 -1
     */
    public int indexOf(long value) {
        readLock.lock();
        try {
            int currentIndex = 0;

            for (DataLayer layer : dataLayers) {
                layer.readLock.lock();
                try {
                    for (int i = 0; i < layer.used; i++) {
                        if (readValue(layer, i) == value) {
                            return currentIndex + i;
                        }
                    }
                    currentIndex += layer.used;
                } finally {
                    layer.readLock.unlock();
                }
            }

            return -1;

        } catch (IOException e) {
            throw new RuntimeException("Failed to search for value", e);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 检查是否包含指定元素
     *
     * @param value 要检查的值
     * @return true 如果包含
     */
    public boolean contains(long value) {
        return indexOf(value) >= 0;
    }

    // ========================================================================
    // 高级操作
    // ========================================================================
    /**
     * 保留指定集合中的元素
     *
     * @param c 要保留的元素集合
     * @return true 如果列表发生变化
     */
    public boolean retainAll(Collection<Long> c) {
        if (c == null || c.isEmpty()) {
            boolean wasEmpty = isEmpty();
            clear();
            return !wasEmpty;
        }

        writeLock.lock();
        try {
            Set<Long> retainSet = new HashSet<>(c);
            List<Long> retained = new ArrayList<>();

            // 收集要保留的元素
            forEach((value, index) -> {
                if (retainSet.contains(value)) {
                    retained.add(value);
                }
                return false;
            });

            // 清空并重新添加
            clear();
            addAll(retained);

            return retained.size() != total;

        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 移除指定集合中的所有元素
     *
     * @param c 要移除的元素集合
     * @return true 如果列表发生变化
     */
    public boolean removeAll(Collection<Long> c) {
        if (c == null || c.isEmpty()) {
            return false;
        }

        writeLock.lock();
        try {
            Set<Long> removeSet = new HashSet<>(c);
            int removedCount = 0;

            // 反向遍历，避免索引问题
            for (int i = (int) (total - 1); i >= 0; i--) {
                if (removeSet.contains(get(i))) {
                    remove(i);
                    removedCount++;
                }
            }

            return removedCount > 0;

        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 压缩存储空间 通过移动数据填充空隙，减少碎片
     */
    public void compact() {
        writeLock.lock();
        try {
            List<DataLayer> layersToRemove = new ArrayList<>();

            // 从前向后填充空隙
            for (int i = 0; i < dataLayers.size() - 1; i++) {
                DataLayer layer = dataLayers.get(i);

                if (layer.remaining() > 0) {
                    // 从后面的层移动数据
                    int filled = fillLayerFromTail(layer);

                    if (filled > 0) {
                        // 检查最后一层是否为空
                        DataLayer lastLayer = dataLayers.get(dataLayers.size() - 1);
                        if (lastLayer.isEmpty()) {
                            layersToRemove.add(lastLayer);
                        }
                    }
                }
            }

            // 移除空层
            for (DataLayer layer : layersToRemove) {
                dataLayers.remove(layer);
                unloadLayerBuffers(layer);
            }

            // 更新层级
            level = Math.max(0, dataLayers.size() - 1);
            storeIntOffset(LEVEL_OFFSET, level);
            updateCurrentLayerCache();

        } catch (IOException e) {
            throw new RuntimeException("Failed to compact list", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 从尾部填充指定层
     */
    private int fillLayerFromTail(DataLayer targetLayer) throws IOException {
        int remaining = targetLayer.remaining();
        if (remaining <= 0 || total <= 0) {
            return 0;
        }

        int toFill = (int) Math.min(remaining, total - sumUsedBeforeLayer(targetLayer));
        if (toFill <= 0) {
            return 0;
        }

        long[] values = removeTail(toFill);

        targetLayer.writeLock.lock();
        try {
            for (int i = 0; i < values.length; i++) {
                writeValue(targetLayer, targetLayer.used + i, values[i]);
            }
            targetLayer.used += values.length;
            storeIntOffset(targetLayer.levelOffset + LAYER_USED_INDEX, targetLayer.used);
        } finally {
            targetLayer.writeLock.unlock();
        }

        return values.length;
    }

    /**
     * 计算指定层之前的已用元素总数
     */
    private int sumUsedBeforeLayer(DataLayer targetLayer) {
        int sum = 0;
        for (DataLayer layer : dataLayers) {
            if (layer == targetLayer) {
                break;
            }
            sum += layer.used;
        }
        return sum;
    }

    // ========================================================================
    // 便捷方法
    // ========================================================================
    /**
     * 在头部添加元素
     * @param value
     */
    public void addHead(long value) {
        add(0, value);
    }

    /**
     * 在头部批量添加元素
     * @param values
     */
    public void addAllHead(long[] values) {
        if (values == null || values.length == 0) {
            return;
        }

        writeLock.lock();
        try {
            for (int i = values.length - 1; i >= 0; i--) {
                add(0, values[i]);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 在头部批量添加元素
     * @param values
     */
    public void addAllHead(Collection<Long> values) {
        if (values == null || values.isEmpty()) {
            return;
        }

        writeLock.lock();
        try {
            List<Long> list = new ArrayList<>(values);
            for (int i = list.size() - 1; i >= 0; i--) {
                Long value = list.get(i);
                if (value != null) {
                    add(0, value);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 从头部移除指定数量的元素
     * @param count
     * @return 
     */
    public long[] removeHead(int count) {
        return remove(0, count);
    }

    /**
     * 从尾部移除指定数量的元素
     * @param count
     * @return 
     */
    public long[] removeTail(int count) {
        if (count <= 0 || total == 0) {
            return new long[0];
        }

        int actualCount = (int) Math.min(count, total);
        return remove((int) (total - actualCount), actualCount);
    }

    // ========================================================================
    // 遍历方法
    // ========================================================================
    /**
     * 正向遍历
     *
     * @param action 处理函数，返回 true 提前终止
     */
    public void forEach(DsProcessLong action) {
        if (action == null) {
            return;
        }

        readLock.lock();
        try {
            int currentIndex = 0;

            for (DataLayer layer : dataLayers) {
                layer.readLock.lock();
                try {
                    for (int i = 0; i < layer.used; i++) {
                        long value = readValue(layer, i);
                        if (action.process(value, currentIndex++)) {
                            return; // 提前终止
                        }
                    }
                } finally {
                    layer.readLock.unlock();
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed during forEach", e);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 反向遍历
     *
     * @param action 处理函数，返回 true 提前终止
     */
    public void reversedForEach(DsProcessLong action)  {
        if (action == null) {
            return;
        }

        readLock.lock();
        try {
            int currentIndex = (int) (total - 1);

            for (int layerIdx = dataLayers.size() - 1; layerIdx >= 0; layerIdx--) {
                DataLayer layer = dataLayers.get(layerIdx);

                layer.readLock.lock();
                try {
                    for (int i = layer.used - 1; i >= 0; i--) {
                        long value = readValue(layer, i);
                        if (action.process(value, currentIndex--)) {

                            return; // 提前终止

                        }
                    }
                } finally {
                    layer.readLock.unlock();
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed during reversedForEach", e);
        } finally {
            readLock.unlock();
        }
    }

      
        /**
         * 转换为数组
         */        /**
         * 转换为数组
         */
    public long[] toArray() {
        readLock.lock();
        try {
            long[] array = new long[(int)total];
            int index = 0;

            for (DataLayer layer : dataLayers) {
                layer.readLock.lock();
                try {
                    for (int i = 0; i < layer.used; i++) {
                        array[index++] = readValue(layer, i);
                    }
                } finally {
                    layer.readLock.unlock();
                }
            }

            return array;

        } catch (IOException e) {
            throw new RuntimeException("Failed to convert to array", e);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 转换为 List
     */
    public List<Long> toList() {
        readLock.lock();
        try {
            List<Long> list = new ArrayList((int) total);

            forEach((value, index) -> {
                list.add(value);
                return false;
            });

            return list;

        } finally {
            readLock.unlock();
        }
    }

// ========================================================================
// 内部辅助方法
// ========================================================================
    /**
     * 层位置信息
     */
    private static class LayerPosition {

        final DataLayer layer;
        final int layerIndex;
        final int localIndex;

        LayerPosition(DataLayer layer, int layerIndex, int localIndex) {
            this.layer = layer;
            this.layerIndex = layerIndex;
            this.localIndex = localIndex;
        }
    }

    /**
     * 查找指定全局索引对应的层和局部索引
     */
    private LayerPosition findLayerPosition(int globalIndex) {
        if (globalIndex < 0 || globalIndex >= total) {
            throw new IndexOutOfBoundsException("Index: " + globalIndex + ", Size: " + total);
        }

        int currentIndex = 0;

        for (int i = 0; i < dataLayers.size(); i++) {
            DataLayer layer = dataLayers.get(i);

            if (currentIndex + layer.used > globalIndex) {
                int localIndex = globalIndex - currentIndex;
                return new LayerPosition(layer, i, localIndex);
            }

            currentIndex += layer.used;
        }

        throw new IllegalStateException("Failed to find layer for index: " + globalIndex);
    }

    /**
     * 查找或创建用于添加的层
     */
    private DataLayer findOrCreateLayerForAdd() throws IOException {
        // 检查当前层是否有空间
        if (level >= 0 && level < dataLayers.size()) {
            DataLayer currentLayer = dataLayers.get(level);
            if (!currentLayer.isFull()) {
                return currentLayer;
            }
        }

        // 检查是否有下一层
        if (level + 1 < dataLayers.size()) {
            level++;
            storeIntOffset(LEVEL_OFFSET, level);
            updateCurrentLayerCache();
            return dataLayers.get(level);
        }

        // 需要创建新层
        return createNewLayer();
    }

    /**
     * 创建新的数据层
     */
    private DataLayer createNewLayer() throws IOException {
        // 计算新层容量
        int newCapacity = calculateNextCapacity();

        // 计算新层偏移
        long newLayerOffset = calculateNextLayerOffset();

        // 创建新层
        DataLayer newLayer = createDataLayer(newLayerOffset, newCapacity);

        // 更新上一层的 rightOffset
        if (!dataLayers.isEmpty()) {
            DataLayer lastLayer = dataLayers.get(dataLayers.size() - 1);
            lastLayer.rightOffset = newLayerOffset;
            storeLongOffset(lastLayer.levelOffset + LAYER_RIGHT_OFFSET_INDEX, newLayerOffset);
        }

        // 添加到列表
        dataLayers.add(newLayer);

        // 写入新层描述符
        writeLayerDescriptor(newLayer);

        // 更新层级
        level = dataLayers.size() - 1;
        storeIntOffset(LEVEL_OFFSET, level);

        // 更新 nextOffset
        long nextOffset = newLayerOffset + DATA_LAYER_DESC_SIZE
            + (long) newCapacity * DATA_UNIT_SIZE;
        storeLongOffset(NEXT_OFFSET_OFFSET, nextOffset);

        updateCurrentLayerCache();

        return newLayer;
    }

    /**
     * 计算下一层容量
     */
    private int calculateNextCapacity() {
        if (level + 1 < levelsCapacity.length) {
            return levelsCapacity[level + 1];
        }

        // 超出预定义容量，使用最后一个容量的 2 倍
        return levelsCapacity[levelsCapacity.length - 1] * 2;
    }

    /**
     * 计算下一层偏移
     */
    private long calculateNextLayerOffset() throws IOException {
        if (dataLayers.isEmpty()) {
            return HEADER_SIZE;
        }

        DataLayer lastLayer = dataLayers.get(dataLayers.size() - 1);
        return lastLayer.levelOffset + DATA_LAYER_DESC_SIZE
            + (long) lastLayer.capacity * DATA_UNIT_SIZE;
    }

    /**
     * 读取层中指定位置的值
     */
    private long readValue(DataLayer layer, int localIndex) throws IOException {
        if (localIndex < 0 || localIndex >= layer.used) {
            throw new IndexOutOfBoundsException(
                "Local index: " + localIndex + ", Layer used: " + layer.used);
        }

        long offset = layer.dataOffset(localIndex);
        return loadLongOffset(offset);
    }

    /**
     * 写入层中指定位置的值
     */
    private void writeValue(DataLayer layer, int localIndex, long value) throws IOException {
        if (localIndex < 0 || localIndex >= layer.capacity) {
            throw new IndexOutOfBoundsException(
                "Local index: " + localIndex + ", Layer capacity: " + layer.capacity);
        }

        long offset = layer.dataOffset(localIndex);
        storeLongOffset(offset, value);
    }

    /**
     * 元素右移（为插入腾出空间）
     */
    private void shiftElementsRight(LayerPosition startPos, int shiftCount) throws IOException {
        if (shiftCount <= 0) {
            return;
        }

        // 从尾部开始，逐个右移
        for (int i = (int) (total - 1); i >= startPos.layerIndex * 1000 + startPos.localIndex; i--) {
            LayerPosition pos = findLayerPosition(i);
            long value = readValue(pos.layer, pos.localIndex);

            // 确保有足够空间
            DataLayer targetLayer = findOrCreateLayerForAdd();

            targetLayer.writeLock.lock();
            try {
                writeValue(targetLayer, targetLayer.used, value);
                targetLayer.used++;
                storeIntOffset(targetLayer.levelOffset + LAYER_USED_INDEX, targetLayer.used);
            } finally {
                targetLayer.writeLock.unlock();
            }
        }

        // 更新起始层的 used
        startPos.layer.writeLock.lock();
        try {
            startPos.layer.used -= shiftCount;
            if (startPos.layer.used < 0) {
                startPos.layer.used = 0;
            }
            storeIntOffset(startPos.layer.levelOffset + LAYER_USED_INDEX, startPos.layer.used);
        } finally {
            startPos.layer.writeLock.unlock();
        }
    }

    /**
     * 元素左移（填补删除的空隙）
     */
    private void shiftElementsLeft(LayerPosition startPos, int shiftCount) throws IOException {
        if (shiftCount <= 0) {
            return;
        }

        int startGlobalIndex = 0;
        for (int i = 0; i < startPos.layerIndex; i++) {
            startGlobalIndex += dataLayers.get(i).used;
        }
        startGlobalIndex += startPos.localIndex;

        // 从删除位置开始，逐个左移
        for (int i = startGlobalIndex + shiftCount; i < total; i++) {
            LayerPosition sourcePos = findLayerPosition(i);
            LayerPosition targetPos = findLayerPosition(i - shiftCount);

            long value = readValue(sourcePos.layer, sourcePos.localIndex);

            targetPos.layer.writeLock.lock();
            try {
                writeValue(targetPos.layer, targetPos.localIndex, value);
            } finally {
                targetPos.layer.writeLock.unlock();
            }
        }

        // 更新最后一层的 used
        DataLayer lastLayer = dataLayers.get(dataLayers.size() - 1);
        lastLayer.writeLock.lock();
        try {
            lastLayer.used -= shiftCount;
            if (lastLayer.used < 0) {
                lastLayer.used = 0;
            }
            storeIntOffset(lastLayer.levelOffset + LAYER_USED_INDEX, lastLayer.used);
        } finally {
            lastLayer.writeLock.unlock();
        }
    }

    /**
     * 卸载层的缓冲区
     */
    private void unloadLayerBuffers(DataLayer layer) {
        // 这里可以添加缓冲区清理逻辑
        // 具体实现取决于父类 DsObject 的缓冲区管理机制
    }

// ========================================================================
// 辅助方法 - 文件 I/O
// ========================================================================
    /**
     * 从指定偏移读取 int
     */
    protected int loadIntOffset(long offset) throws IOException {
        long bufferIndex = (int) bufferIndexFromPosition(offset);
        int bufferOffset = bufferOffsetFromPosition(offset);

        MappedByteBuffer buffer = loadBufferForRead(bufferIndex);
        try {
            return buffer.getInt(bufferOffset);
        } finally {
            unlockBufferForRead(bufferIndex);
        }
    }

      /**
     * 返回迭代器
     */
    @Override
    public Iterator<Long> iterator() {
        return new DsListIterator();
    }


// ========================================================================
// 迭代器实现
// ========================================================================
    /**
     * 列表迭代器
     */
    
   

    private class DsListIterator implements Iterator<Long> {

        private int currentIndex;
        private int lastReturnedIndex;
        private final long expectedTotal;

        DsListIterator() {
            this.currentIndex = 0;
            this.lastReturnedIndex = -1;
            this.expectedTotal = total;
        }

        @Override
        public boolean hasNext() {
            checkForComodification();
            return currentIndex < expectedTotal;
        }

        @Override
        public Long next() {
            checkForComodification();

            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            try {
                Long value = get(currentIndex);
                lastReturnedIndex = currentIndex;
                currentIndex++;
                return value;
            } catch (IndexOutOfBoundsException e) {
                checkForComodification();
                throw new NoSuchElementException();
            }
        }

        @Override
        public void remove() {
            if (lastReturnedIndex < 0) {
                throw new IllegalStateException("No element to remove");
            }

            checkForComodification();

            try {
                DsList.this.remove(lastReturnedIndex);
                currentIndex = lastReturnedIndex;
                lastReturnedIndex = -1;
            } catch (IndexOutOfBoundsException e) {
                throw new ConcurrentModificationException();
            }
        }

        private void checkForComodification() {
            if (total != expectedTotal) {
                throw new ConcurrentModificationException(
                    "List was modified during iteration");
            }
        }
    }

// ========================================================================
// 调试和诊断方法
// ========================================================================
    /**
     * 验证数据结构完整性
     */
    public boolean validate() {
        readLock.lock();
        try {
            // 验证总数
            int calculatedTotal = 0;
            for (DataLayer layer : dataLayers) {
                calculatedTotal += layer.used;
            }

            if (calculatedTotal != total) {
                System.err.println("Total mismatch: expected=" + total
                    + ", calculated=" + calculatedTotal);
                return false;
            }

            // 验证层链接
            for (int i = 0; i < dataLayers.size() - 1; i++) {
                DataLayer layer = dataLayers.get(i);
                DataLayer nextLayer = dataLayers.get(i + 1);

                if (layer.rightOffset != nextLayer.levelOffset) {
                    System.err.println("Layer chain broken at index " + i);
                    return false;
                }
            }

            // 验证最后一层
            if (!dataLayers.isEmpty()) {
                DataLayer lastLayer = dataLayers.get(dataLayers.size() - 1);
                if (lastLayer.rightOffset != 0) {
                    System.err.println("Last layer rightOffset should be 0");
                    return false;
                }
            }

            return true;

        } finally {
            readLock.unlock();
        }
    }

    /**
     * 打印调试信息
     */
    public void printDebugInfo() {
        readLock.lock();
        try {
            System.out.println("=== DsList Debug Info ===");
            System.out.println("Total elements: " + total);
            System.out.println("Current level: " + level);
            System.out.println("Number of layers: " + dataLayers.size());
            System.out.println();

            for (int i = 0; i < dataLayers.size(); i++) {
                DataLayer layer = dataLayers.get(i);
                System.out.printf("Layer %d: %s%n", i, layer);
                System.out.printf("  Usage: %.2f%%%n",
                    (layer.used * 100.0 / layer.capacity));
            }

            System.out.println("=========================");

        } finally {
            readLock.unlock();
        }
    }

    /**
     * 获取统计信息
     * @return 
     */
    public Map<String, Object> getStatistics() {
        readLock.lock();
        try {
            Map<String, Object> stats = new HashMap<>();

            stats.put("total", total);
            stats.put("level", level);
            stats.put("layerCount", dataLayers.size());

            int totalCapacity = 0;
            int totalUsed = 0;

            for (DataLayer layer : dataLayers) {
                totalCapacity += layer.capacity;
                totalUsed += layer.used;
            }

            stats.put("totalCapacity", totalCapacity);
            stats.put("totalUsed", totalUsed);
            stats.put("utilization", totalUsed * 100.0 / totalCapacity);

            return stats;

        } finally {
            readLock.unlock();
        }
    }

// ========================================================================
// 资源管理
// ========================================================================
    /**
     * 关闭列表，释放资源
     */
    public void close() {
        writeLock.lock();
        try {
            // 强制刷新所有缓冲区
            force();

            // 清理数据层
            dataLayers.clear();

            // 调用父类关闭
            super.sync();

        } catch (Exception ex) {
            System.getLogger(DsList.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 强制刷新到磁盘
     */
    public void force()  {
        readLock.lock();
        try {
            // 调用父类的 force 方法
            super.sync();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public String toString() {
        return String.format("DsList[size=%d, layers=%d, level=%d]",
            total, dataLayers.size(), level);
    }


}
