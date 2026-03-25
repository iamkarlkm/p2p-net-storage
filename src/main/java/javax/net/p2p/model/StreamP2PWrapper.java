package javax.net.p2p.model;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.pool.PooledObjectFactory;
import javax.net.p2p.common.pool.PooledObjects;

/**
 * <p>
 * 流消息封装
 * </p>
 *
 * @author iamkarl@163.com
 * @param <T>
 */
public class StreamP2PWrapper<T> extends P2PWrapper<T> {
    
    private int index;
    
    private boolean completed;
    
    private boolean canceled;

    protected StreamP2PWrapper() {
        index = 0;
    }
   
    
    public final static <T> StreamP2PWrapper<T> buildStream(int seq,boolean canceled) {
        StreamP2PWrapper<T> t = ConcurrentObjectPool.get().poll();
        t.seq = seq;
        t.canceled = canceled;
        return t;
    }
       
    public final static <T> StreamP2PWrapper<T> buildStream(int seq,int index,P2PCommand command, T data) {
        StreamP2PWrapper<T> t = ConcurrentObjectPool.get().poll();
        t.seq = seq;
        t.command = command;
        t.data = data;
        t.index = index;
        return t;
    }
        
    public final static <T> StreamP2PWrapper<T> buildStream(int seq,int index,P2PCommand command, T data,boolean completed) {
        StreamP2PWrapper<T> t = ConcurrentObjectPool.get().poll();
        t.seq = seq;
        t.command = command;
        t.data = data;
        t.index = index;
        t.completed = completed;
        return t;
    }

    public int getIndex() {
        return index;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isCanceled() {
        return canceled;
    }
    
    @Override
    public void clear() {
        seq = 0;
        command = null;
        data = null;
    }
    
    @Override
    public boolean release() {
        return ConcurrentObjectPool.get().offer(this);
    }
    
    static class ConcurrentObjectPool {

        private static final ThreadLocal<PooledObjects<StreamP2PWrapper>> LOCAL_POOL = new ThreadLocal<>();

        private static PooledObjects<StreamP2PWrapper> get() {
            PooledObjects pool = LOCAL_POOL.get();
            if (pool == null) {
                pool = new PooledObjects(4096, new PooledObjectFactory<StreamP2PWrapper>() {
                    @Override
                    public StreamP2PWrapper newInstance() {
                        return new StreamP2PWrapper();
                    }
                });
                LOCAL_POOL.set(pool);
            }
            return pool;
        }
    }

    @Override
    public String toString() {
        return "seq:" + seq  + ",index:" +  ",completed:" + completed+ ",command:" + command + ",data:" + data;
    }

}
