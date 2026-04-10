package javax.net.p2p.common.pool;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 轻量级有界对象池
 *
 * @author karl
 * @param <T>
 */
public class PooledObjects<T extends Pooledable> {

    private PooledObjectFactory objectFactory;

    private Object[] objects;

    /**
     * Main lock guarding all access
     */
    private final ReentrantLock lock = new ReentrantLock();

    private int head;

    private int tail;

    private int count;

    private int size;

    public PooledObjects(PooledObjectFactory objectFactory) {
        this(4096, objectFactory);

    }

    public PooledObjects(int size, PooledObjectFactory objectFactory) {
        this.objectFactory = objectFactory;
        this.size = size;
        objects = new Object[size];
    }

    /**
     * newInstance() when pool is empty else do obj.load()
     *
     * @return
     */
    public T poll() {
        lock.lock(); // Lock only for visibility, not mutual exclusion
        try {
            T t;
            if (count > 0) {
                t = (T) objects[head];
                count--;
                head++;
                if (head >= size) {
                    head = 0;
                }
            } else {
                try {
                    t = (T) objectFactory.newInstance();
                    //t.setPool(this);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
            //t.load();
            return t;
        } finally {
            lock.unlock();
        }

    }

    /**
     * objectFactory.newInstance() when pool is empty else do obj.load(params)
     *
     * @param params
     * @return
     */
    public T pollParams(Object... params) {
        T t = poll();

        t.loadParams(params);
        return t;
    }

    /**
     * returning {@code true} upon success do obj.clear() and {@code false} if
     * this pool is full, do obj.empty(),t will discarded
     *
     * @param t
     * @return
     */
    public boolean offer(T t) {
        t.clear();
        lock.lock(); // Lock only for visibility, not mutual exclusion
        try {
            if (count < size) {
                objects[tail] = t;
                count++;
                tail++;
                if (tail >= size) {
                    tail = 0;
                }
                return true;
            }
            //超出容量,回归 jvm gc
        } finally {
          lock.unlock();
        }
        return false;

    }

    public static void main(String[] args) throws InterruptedException {
        long timeout = 1000L;
        PooledObjects<PooledableTest> pool = new PooledObjects(512, new PooledObjectFactory<PooledableTest>() {
            @Override
            public PooledableTest newInstance() {
                return new PooledableTest(timeout, "neame1");
            }
        });

        PooledableTest t = pool.poll();
        System.out.println(t);

        //测试带参数实时修改对象值
        t = pool.pollParams(3000L, "name2");
        System.out.println(t);
    }

}
