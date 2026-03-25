package javax.net.p2p.common.pool;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * ThreadLocal对象池（极致性能）
 * 
 * 优势：
 * 1. 零竞争：每个线程独立
 * 2. 极快速度：~5-10ns/op
 * 3. 简单实现：无需同步
 * 
 * 劣势：
 * 1. 内存占用高：每线程一个池
 * 2. 不适合线程数动态变化的场景
 * 
 * @param <T> 对象类型
 * @author iamkarl@163.com
 */
@Slf4j
public class ThreadLocalObjectPool<T extends Recyclable> {

    private final ThreadLocal<LocalPool<T>> localPools;
    private final Supplier<T> factory;
    private final int maxSizePerThread;

    public ThreadLocalObjectPool(Supplier<T> factory, int maxSizePerThread) {
        this.factory = factory;
        this.maxSizePerThread = maxSizePerThread;
        this.localPools = ThreadLocal.withInitial(() -> new LocalPool<>(factory, maxSizePerThread));
        
        log.info("ThreadLocal对象池初始化: 每线程容量={}", maxSizePerThread);
    }

    public T acquire() {
        return localPools.get().acquire();
    }

    public boolean release(T obj) {
        if (obj == null) {
            return false;
        }
        return localPools.get().release(obj);
    }

    /**
     * 本地对象池（单线程）
     */
    private static class LocalPool<T extends Recyclable> {
        private final Deque<T> stack;
        private final Supplier<T> factory;
        private final int maxSize;
        private int createCount;

        LocalPool(Supplier<T> factory, int maxSize) {
            this.stack = new ArrayDeque<>(maxSize);
            this.factory = factory;
            this.maxSize = maxSize;
            this.createCount = 0;
        }

        T acquire() {
            T obj = stack.pollLast();
            if (obj != null) {
                return obj;
            }
            
            if (createCount < maxSize) {
                createCount++;
                return factory.get();
            }
            
            return null;
        }

        boolean release(T obj) {
            obj.clear();
            if (stack.size() < maxSize) {
                stack.offerLast(obj);
                return true;
            }
            return false;
        }
    }
}
