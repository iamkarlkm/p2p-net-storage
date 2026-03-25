
package javax.net.p2p.common.pool;


import java.util.concurrent.ArrayBlockingQueue;

/**
 * 从已经存在的对象衍生的有界对象池
 * @author karl
 * @param <T>
 */
public class ClonePooledObjects<T extends ClonePooledable> {
    
  
    private transient final ArrayBlockingQueue<T> objects;
   

    public ClonePooledObjects(int size) {
        objects = new ArrayBlockingQueue<>(size);
    }
  
    
    /**
     * origin.clone()  when pool is empty
     * @param origin
     * @return
     */
    public T pollOrClone(T origin) {
        T t = objects.poll();
        if(t==null){
            try {
                t = (T) origin.clone();
            } catch (CloneNotSupportedException ex) {
                throw new RuntimeException(ex);
            } 
        }
        t.load();
        return t;
    }
    
    /**
     * origin.clone()  when pool is empty
     * @param origin
     * @param params
     * @return
     */
    public T pollParamsOrClone(T origin,Object... params) {
        T t = objects.poll();
        if(t==null){
            try {
                t = (T) origin.clone();
            } catch (CloneNotSupportedException ex) {
                throw new RuntimeException(ex);
            } 
        }
        t.loadParams(params);
        return t;
    }
    
   
    
    /**
     * do obj.clear()
     * returning {@code true} upon success  and {@code false} if this pool
     * is full, t will discarded
     * @param t
     * @return 
     */
    public boolean offer(T t) {
        t.clear();
        if(objects.offer(t)){
            return true;
        }
        return false;
    }
    
   
}
