
package com.q3lives.ds.bucket;

/**
 * 通用数据块管理类(统一以64位id索引->数据偏移),分配数据空间，回收数据空间。修改/更新指定空间数据。
 * @author Administrator
 */
public interface DsBlockService {
    
    /**
     * 当前数据空间存储的定长数据尺寸
     * @return 
     */
    int dataSize();
    
    /**
     * 查询当前数据空间存储容量。
     * @return 
     */
    long capacity();
    
    /**
     * 
     * @param id
     * @return 
     */
    byte[] read(long id);
    
    /**
     * 更新指定数据空间数据
     * @param id
     * @param data 
     */
    void write(long id,byte[] data);
    /**
     * 分配新数据块并返回ID。
     * @return 
     */
    long getNewId();
    
    /**
     * 批量分配新数据块并返回ID。
     * @param size
     * @return 
     */
    long[] getNewIds(int size);
    
    /**
     * 回收数据块
     * @param id
     */
    void releaseId(long id);
    
    /**
     * 批量回收数据块
     * @param ids
     */
    void releaseIds(long[] ids);
    
}
