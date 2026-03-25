package javax.net.p2p.common;

import javax.net.p2p.common.pool.PooledableAdapter;
import io.netty.channel.Channel;
import io.netty.channel.pool.SimpleChannelPool;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.pool.PooledObjectFactory;
import javax.net.p2p.common.pool.PooledObjects;
import javax.net.p2p.model.P2PWrapper;

/**
 *
 * @author karl
 * @param <T>
 */
public class ChannelAwaitOnMessage<T> extends PooledableAdapter implements Callable<T> {


    private SimpleChannelPool channelPool;

    private T result;

    private Exception exception;

    private final Condition awaitCondition;

    private final ReentrantLock lock;

    private long timeout;
    
    private boolean timeoutReset = false;

    private boolean isFree = true;

    private Future<T> future;

    public ChannelAwaitOnMessage() {
        this(0);
    }

    public ChannelAwaitOnMessage(long timeout) {
        this.lock = new ReentrantLock();
        this.awaitCondition = lock.newCondition();
        this.timeout = timeout;
    }

    public ChannelAwaitOnMessage(SimpleChannelPool channelPool, long timeout) {
        this.channelPool = channelPool;
        this.lock = new ReentrantLock();
        this.awaitCondition = lock.newCondition();
        this.timeout = timeout;
    }

    @Override
    public T call() throws Exception {
        lock.lock();
        try {
            isFree = false;//标识使用中
            if (timeout > 0) {
                awaitCondition.await(timeout, TimeUnit.MILLISECONDS);
            } else {
                awaitCondition.await();
            }
            while (timeoutReset) {
                timeoutReset = false;
                if (timeout > 0) {
                    awaitCondition.await(timeout, TimeUnit.MILLISECONDS);
                } else {
                    awaitCondition.await();
                }
            }
            T r = result;
            result = null;
            if (r != null) {
                return r;
            }
            if (exception == null) {
                exception = this.exception = new RuntimeException("ChannelAwaitOnMessage future is interrupted or canceled");
            }
            P2PWrapper e = P2PWrapper.build(P2PCommand.STD_ERROR, exception.getMessage()!=null?exception.getMessage():exception.getClass());
            return (T) e;
        } finally {
            release();//回归对象池
            lock.unlock();
        }

    }

    public void cancel() {
        lock.lock();
        try {
            if(isFree){
               return;
            }
            this.exception = new RuntimeException("ChannelAwaitOnMessage canceled");
            this.future = null;
            this.result = null;
            awaitCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    public void complete(T result) {
        lock.lock();
        try {
            this.result = result;
            awaitCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    public void completeExceptionally(Exception exception) {
        lock.lock();
        try {
            if(isFree){
               return;
            }
            this.exception = exception;
            this.future = null;
            this.result = null;
            awaitCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    public long getTimeout() {
        return timeout;//
    }
    
    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }
    
    public void setTimeout(long timeout, TimeUnit unit) {
        this.timeout = unit.toMillis(timeout);
    }
    
    public void resetTimeout(long timeout, TimeUnit unit) {
        resetTimeout(unit.toMillis(timeout));
    }

    public void resetTimeout(long timeout) {
        lock.lock();
        try {
            this.timeout = timeout;
            if(!isFree){
                timeoutReset = true;
                awaitCondition.signal();
            }
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public boolean release() {
        lock.lock();
        try {
            if(isFree){
                return ConcurrentObjectPool.get().offer(this);
            }
        } finally {
            lock.unlock();
        }
        return false;
    }

    public void release(Channel c) {
        this.channelPool.release(c);
    }

    public boolean isFree() {
        if (lock.tryLock()) {
            try {
                return isFree;
            } finally {
                lock.unlock();
            }

        } else {
            return false;
        }

    }

    public Future<T> getFuture() {
        return future;
    }

    public void setFuture(Future<T> future) {
        this.future = future;
    }

    @Override
    public void clear() {
        this.isFree = true;
        this.future = null;
        this.result = null;
    }

       

    @Override
    public String toString() {
        return "ChannelAwaitOnMessage{" + "result=" + result + ", exception=" + exception + ", timeout=" + timeout + ", timeoutReset=" + timeoutReset + ", isFree=" + isFree + ", future=" + future + '}';
    }

    
 
    public final static <T> ChannelAwaitOnMessage build(long timeout, TimeUnit unit) {
        ChannelAwaitOnMessage t = ConcurrentObjectPool.get().poll();
        t.timeout = unit.toMillis(timeout);
        return t;
    }

  
    

    static class ConcurrentObjectPool {

        private static final ThreadLocal<PooledObjects<ChannelAwaitOnMessage>> LOCAL_POOL = new ThreadLocal<>();

        private static PooledObjects<ChannelAwaitOnMessage> get() {
            PooledObjects<ChannelAwaitOnMessage> pool = LOCAL_POOL.get();
            if (pool == null) {
                pool = new PooledObjects(4096, new PooledObjectFactory<ChannelAwaitOnMessage>() {
                    @Override
                    public ChannelAwaitOnMessage newInstance() {
                        return new ChannelAwaitOnMessage();
                    }
                });
               LOCAL_POOL.set(pool);
            }
            return pool;
        }

    }
    
}
