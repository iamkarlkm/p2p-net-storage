package javax.net.p2p.model;

import io.netty.util.ReferenceCounted;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.pool.PooledObjectFactory;
import javax.net.p2p.common.pool.PooledableAdapter;
import javax.net.p2p.common.pool.PooledObjects;

/**
 * <p>
 * 序列化/反序列化对象包装类 专为基于 Protostuff 进行序列化/反序列化而定义。 Protostuff 是基于POJO进行序列化和反序列化操作。
 * 如果需要进行序列化/反序列化的对象不知道其类型，不能进行序列化/反序列化；
 * 比如Map、List、String、Enum等是不能进行正确的序列化/反序列化。
 * 因此需要映入一个包装类，把这些需要序列化/反序列化的对象放到这个包装类中。 这样每次 Protostuff
 * 都是对这个类进行序列化/反序列化,不会出现不能/不正常的操作出现
 * </p>
 *
 * @author iamkarl@163.com
 * @param <T>
 */
public class P2PWrapper<T> extends PooledableAdapter implements ReferenceCounted {

    protected int seq;//消息序列号
    protected P2PCommand command;
    protected T data;

    protected P2PWrapper() {
    }

    protected P2PWrapper(P2PCommand command, T data) {
        this.command = command;
        this.data = data;
    }

    protected P2PWrapper(int seq, P2PCommand command, T data) {
        this.seq = seq;
        this.command = command;
        this.data = data;
    }

    public final static <T> P2PWrapper<T> build(P2PCommand command, T data) {
        P2PWrapper<T> wrapper = ConcurrentObjectPool.get().poll();
        wrapper.setData(data);
        wrapper.setCommand(command);
        return wrapper;
    }

    public final static <T> P2PWrapper<T> build(P2PCommand command) {
        P2PWrapper<T> wrapper = ConcurrentObjectPool.get().poll();
        wrapper.setCommand(command);
        return wrapper;
    }

    public final static <T> P2PWrapper<T> build(int seq, P2PCommand command, T data) {
        P2PWrapper<T> wrapper = ConcurrentObjectPool.get().poll();
        wrapper.setSeq(seq);
        wrapper.setData(data);
        wrapper.setCommand(command);
        return wrapper;
    }
      
    public final static <T> P2PWrapper<T> build(int seq, P2PCommand command) {
        P2PWrapper<T> wrapper = ConcurrentObjectPool.get().poll();
        wrapper.setSeq(seq);
        wrapper.setCommand(command);
        return wrapper;
    }

    public P2PCommand getCommand() {
        return command;
    }

    public void setCommand(P2PCommand command) {
        this.command = command;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    @Override
    public String toString() {
        return "seq:" + seq + ",command:" + command + ",data:" + data;
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

    @Override
    public int refCnt() {
        return 0;
    }

    @Override
    public ReferenceCounted retain() {
        return this;
    }

    @Override
    public ReferenceCounted retain(int i) {
        return this;
    }

    @Override
    public ReferenceCounted touch() {
        return this;
    }

    @Override
    public ReferenceCounted touch(Object o) {
        return this;
    }


    @Override
    public boolean release(int i) {
        return release();
    }
    
    
   
     static class ConcurrentObjectPool {

        private static final ThreadLocal<PooledObjects<P2PWrapper>> LOCAL_POOL = new ThreadLocal<>();

        private static PooledObjects<P2PWrapper> get() {
            PooledObjects pool = LOCAL_POOL.get();
            if (pool == null) {
                pool = new PooledObjects(4096, new PooledObjectFactory<P2PWrapper>() {
                    @Override
                    public P2PWrapper newInstance() {
                        return new P2PWrapper();
                    }
                });
                LOCAL_POOL.set(pool);
            }
            return pool;
        }

        //用SoftReference包装可以让jvm在内存不足时自动回收ThreadLocal引用对象池,这将会损失一些性能
//        private static final ThreadLocal<SoftReference<ThreadLocalPooledObjects<P2PWrapper>>> LOCAL_HASH = new ThreadLocal<>();
//
//        private static ThreadLocalPooledObjects<P2PWrapper> get() {
//            ThreadLocalPooledObjects pool;
//            SoftReference<ThreadLocalPooledObjects<P2PWrapper>> ref = LOCAL_HASH.get();
//             
//            if (ref == null) {
//                pool = new ThreadLocalPooledObjects(4096,new PooledObjectFactory<P2PWrapper>() {
//                    @Override
//                    public P2PWrapper newInstance() {
//                        return new P2PWrapper();
//                    }
//                });
//                ref = new SoftReference(pool);
//                LOCAL_HASH.set(ref);
//            }else{
//                pool = ref.get();
//            }
//            return pool;
//        }
    }  
     
    
    public static void main(String[] args) throws InterruptedException {
        long start = System.currentTimeMillis();
//        for(int i=0;i<10000000;i++){
//            P2PWrapper test = P2PWrapper.builder(99, P2PCommand.HAND, Integer.valueOf(77));
//            test.release();
//        }

        while (true) {
            start = System.currentTimeMillis();
            //测试有gc停顿 -Xlog:gc*:logs/gc.log:time  -Xms8m -Xmx8m
//        for(int i=0;i<10000000;i++){
//            P2PWrapper test = new P2PWrapper(99, P2PCommand.HAND, Integer.valueOf(77));
//            test.clear();
//        }
            //测试无gc停顿 -Xlog:gc*:logs/gc.log:time  -Xms8m -Xmx8m
//            for (int i = 0; i < 10000000; i++) {
//                P2PWrapper test = P2PWrapper.builder(99, P2PCommand.HAND, Integer.valueOf(77));
//                test.release();
//            }
            System.out.println(System.currentTimeMillis() - start);
            Thread.sleep(10000L);
        }


    }

}
