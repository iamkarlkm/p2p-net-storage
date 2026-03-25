
package javax.net.p2p.common.pool;


/**
 *
 * @author karl
 * @param <T>
 */
public interface ClonePooledable<T> {
    
   
    T clone() throws CloneNotSupportedException;
    
    
    void clear();
    
    /**
     * 对象池peek获取对象时调用,以便对象可立即使用
     * 从空对象重建可用对象
     */
    void load();
    
    /**
     * 对象池peekParams获取对象时调用
     * 从空对象重建可用对象
     * @param params 必须对应于对象池的peekParams
     */
    void loadParams(Object... params);
   
    /**
     * 设置池引用到对象本身,以便release
     * @param pool 
     */
    void setPool(ClonePooledObjects pool);
    
}
