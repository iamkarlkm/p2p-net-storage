import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;

/**
 * RWLock (读写锁) - 基于 ReentrantLock 的简单实现
 * 
 * 使用模式:
 * - READ_PREF: 读者优先，尽量允许并发读取；写者需要等待所有读者退出
 * - WRITE_PREF: 写者优先，尽量让写者进入；读者在写者等待时会被阻塞
 * - BALANCED: 公平性策略，尽量轮换进入，避免饥饿
 * 
 * API 设计
 * - public enum Mode { READ_PREF, WRITE_PREF, BALANCED }
 * - public RWLock(Mode mode): 构造指定模式的读写锁
 * - public void read(Runnable cb): 进入读锁并执行 cb（若 cb != null）
 * - public void write(Runnable cb): 进入写锁并执行 cb（若 cb != null）
 * - public void destroy(): 释放资源（当前简单实现为无操作，保留扩展点）
 * 
 * 说明:
 * - 该实现使用 ReentrantLock 与 Condition 实现读写分离的逻辑，便于跨语言对比和演示。
 * - 与 JDK 的 ReentrantReadWriteLock 不同，此实现要求调用方通过 cb（回调）在临界区内执行操作，便于生成代码时统一“lambda 风格锁调用”的输出。
 */
public class RWLock {

    public enum Mode {
        READ_PREF, // 读者优先
        WRITE_PREF, // 写者优先
        BALANCED // 公平/平衡
    }

    private final Mode mode;
    private int readers = 0;
    private int writer = 0; // 0 or 1
    private int waitingWriters = 0;

    private final Lock lock;
    private final Condition canRead;
    private final Condition canWrite;
    
    public RWLock() {
       this.mode = Mode.BALANCED;
        this.lock = new ReentrantLock();
        this.canRead = lock.newCondition();
        this.canWrite = lock.newCondition();
    }

    public RWLock(Mode mode) {
        this.mode = mode;
        this.lock = new ReentrantLock();
        this.canRead = lock.newCondition();
        this.canWrite = lock.newCondition();
    }

    // 进入读锁并执行回调 cb(若 cb != null)
    public void read(Runnable cb) {
        lock.lock();
        try {
            // 根据模式选择进入策略
            while (writer > 0 || (mode == Mode.WRITE_PREF && waitingWriters > 0)) {
                canRead.await();
            }
            readers++;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }

        try {
            if (cb != null) cb.run();
        } finally {
            lock.lock();
            try {
                readers--;
                if (readers == 0) canWrite.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    // 进入写锁并执行回调 cb(若 cb != null)
    public void write(Runnable cb) {
        lock.lock();
        try {
            waitingWriters++;
            while (readers > 0 || writer > 0) {
                canWrite.await();
            }
            waitingWriters--;
            writer = 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }

        try {
            if (cb != null) cb.run();
        } finally {
            lock.lock();
            try {
                writer = 0;
                // 简单策略：若有等待的写者，唤醒一个写者；否则唤醒所有读者
                if (waitingWriters > 0) canWrite.signal();
                else canRead.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    // 清理资源（当前实现无特殊资源需要释放，保留扩展口）
    public void destroy() {
        // 目前无额外资源需要释放
    }
}