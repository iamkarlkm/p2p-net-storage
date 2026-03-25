package ds;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 基于 DsString 和 DsDataIndex 实现的高性能 KV 存储系统。
 * <p>
 * 设计思想：
 * 1. 采用追加/就地更新模式存储 KV 数据（利用 DsString）。
 * 2. 数据格式为: [Key长度(4字节)] [Key字节] [Value长度(4字节)] [Value字节]。
 * 3. 内存中维护 Key -> ID 的映射，实现 O(1) 的快速查找（类似 Bitcask 模型）。
 * 4. 启动时通过遍历 DsDataIndex 恢复内存索引。
 * </p>
 */
public class DsKVStore {

    /** 整型字节长度 */
    public static final int INT_BYTES = 4;


    private final DsDataIndex index;
    private final DsString storage;
    
    // 内存索引：Key -> 数据记录的 ID
    private final ConcurrentHashMap<String, Long> keyMap;
    
    // 读写锁，保证并发安全
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    public DsKVStore(String dirPath, String storeName) throws IOException {
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        
        File indexFile = new File(dir, storeName + ".idx");
        File dataFile = new File(dir, storeName + ".dat");
        
        this.index = new DsDataIndex(indexFile);
        this.storage = new DsString(dataFile, this.index);
        this.keyMap = new ConcurrentHashMap<>();
        
        loadIndex();
    }

    /**
     * 启动时加载并重建内存索引。
     */
    private void loadIndex() throws IOException {
        long maxId = index.getNextVal();
        
        for (long id = 1; id < maxId; id++) {
            try {
                DsDataIndexNode node = index.get(id);
                // 检查记录是否有效 (refCount > 0 且 size > 0)
                if (node != null && node.size > 0 && node.refCount > 0) {
                    byte[] rawData = storage.readRaw(id);
                    String key = extractKey(rawData);
                    if (key != null) {
                        keyMap.put(key, id);
                    }
                }
            } catch (Exception e) {
                // 忽略读取失败或已损坏的记录，继续加载下一条
            }
        }
    }

    /**
     * 存储键值对。
     * @param key 键
     * @param value 值
     * @throws IOException IO异常
     * @throws InterruptedException 中断异常
     */
    public void put(String key, String value) throws IOException, InterruptedException {
        if (key == null || value == null) {
            throw new IllegalArgumentException("Key and Value cannot be null");
        }
        rwLock.writeLock().lock();
        try {
            byte[] kvData = encode(key, value);
            Long existingId = keyMap.get(key);
            
            if (existingId != null) {
                // 尝试就地更新
                try {
                    storage.update(existingId, kvData);
                    return; // 就地更新成功
                } catch (IOException e) {
                    // 如果大小超出原分配空间，则废弃旧记录，追加新记录
                    DsDataIndexNode oldNode = index.get(existingId);
                    if (oldNode != null) {
                        oldNode.refCount = 0; // 标记旧记录为删除 (逻辑删除)
                        index.update(existingId, oldNode);
                    }
                }
            }
            
            // 追加新记录
            long newId = storage.add(kvData);
            keyMap.put(key, newId);
            
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 获取键对应的值。
     * @param key 键
     * @return 对应的值，如果不存在或已删除则返回 null
     * @throws IOException IO异常
     */
    public String get(String key) throws IOException {
        if (key == null) return null;
        
        rwLock.readLock().lock();
        try {
            Long id = keyMap.get(key);
            if (id == null) {
                return null;
            }
            byte[] rawData = storage.readRaw(id);
            return extractValue(rawData);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 删除键值对。
     * @param key 键
     * @return 成功删除返回 true，否则返回 false
     * @throws IOException IO异常
     */
    public boolean remove(String key) throws IOException {
        if (key == null) return false;
        
        rwLock.writeLock().lock();
        try {
            Long id = keyMap.remove(key);
            if (id != null) {
                DsDataIndexNode node = index.get(id);
                if (node != null) {
                    node.refCount = 0; // 软删除
                    index.update(id, node);
                    return true;
                }
            }
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 判断是否包含指定的键。
     * @param key 键
     * @return 是否包含
     */
    public boolean containsKey(String key) {
        if (key == null) return false;
        rwLock.readLock().lock();
        try {
            return keyMap.containsKey(key);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 获取当前存储的键值对数量。
     * @return 数量
     */
    public int size() {
        rwLock.readLock().lock();
        try {
            return keyMap.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // --- 序列化与反序列化工具方法 ---

    /**
     * 将 Key 和 Value 编码为统一的字节数组：
     * [Key长度(4字节)] + [Key字节] + [Value长度(4字节)] + [Value字节]
     */
    private byte[] encode(String key, String value) {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        byte[] vb = value.getBytes(StandardCharsets.UTF_8);
        byte[] res = new byte[INT_BYTES + kb.length + INT_BYTES + vb.length];
        
        DsDataUtil.storeInt(res, 0, kb.length);
        System.arraycopy(kb, 0, res, INT_BYTES, kb.length);
        
        DsDataUtil.storeInt(res, 4 + kb.length, vb.length);
        System.arraycopy(vb, 0, res, 4 + kb.length + 4, vb.length);
        
        return res;
    }

    /**
     * 从原始数据中提取 Key。
     */
    private String extractKey(byte[] rawData) {
        if (rawData == null || rawData.length < 4) return null;
        int kLen = DsDataUtil.loadInt(rawData, 0);
        if (kLen <= 0 || 4 + kLen > rawData.length) return null;
        return new String(rawData, 4, kLen, StandardCharsets.UTF_8);
    }

    /**
     * 从原始数据中提取 Value。
     */
    private String extractValue(byte[] rawData) {
        if (rawData == null || rawData.length < 8) return null;
        int kLen = DsDataUtil.loadInt(rawData, 0);
        if (kLen <= 0 || 4 + kLen + 4 > rawData.length) return null;
        
        int vLen = DsDataUtil.loadInt(rawData, 4 + kLen);
        if (vLen < 0 || 4 + kLen + 4 + vLen > rawData.length) return null;
        
        return new String(rawData, 4 + kLen + 4, vLen, StandardCharsets.UTF_8);
    }
    
    /**
     * 清理资源。
     */
    public void close() {
        // 由于依赖内存映射文件，实际关闭交由操作系统和垃圾回收处理。
        // 此处可执行一些必要的清理工作。
        keyMap.clear();
    }
}
