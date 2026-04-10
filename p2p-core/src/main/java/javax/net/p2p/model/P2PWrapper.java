package javax.net.p2p.model;

import io.netty.util.ReferenceCounted;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.pool.PooledObjectFactory;
import javax.net.p2p.common.pool.PooledableAdapter;
import javax.net.p2p.common.pool.PooledObjects;

/**
 * P2PWrapper - P2P协议消息包装类，基于对象池技术的高性能消息容器
 * 
 * <p>
 * 序列化/反序列化对象包装类，专为基于 Protostuff 进行序列化/反序列化而定义。 
 * Protostuff 是基于POJO进行序列化和反序列化操作，如果需要进行序列化/反序列化的对象
 * 不知道其类型，不能进行序列化/反序列化；比如Map、List、String、Enum等是不能进行
 * 正确的序列化/反序列化。因此需要引入一个包装类，把这些需要序列化/反序列化的对象
 * 放到这个包装类中。这样每次 Protostuff 都是对这个类进行序列化/反序列化,不会出现
 * 不能/不正常的操作出现。
 * </p>
 * 
 * 主要功能：
 * 1. 消息包装：将P2P协议命令、序列号和业务数据封装为统一的消息格式
 * 2. 对象池管理：使用ThreadLocal对象池减少GC压力，提高性能
 * 3. 序列化支持：为Protostuff序列化提供类型安全的包装
 * 4. 资源管理：实现ReferenceCounted接口，支持Netty的引用计数机制
 * 
 * 技术特点：
 * - 使用ThreadLocal对象池，每个线程独立的对象缓存
 * - 支持泛型，可包装任意类型的数据
 * - 实现Netty的ReferenceCounted接口，与Netty生态无缝集成
 * - 提供多种build方法，方便创建消息实例
 * 
 * 性能优化：
 * 1. 对象复用：通过对象池减少对象创建和GC压力
 * 2. 线程安全：使用ThreadLocal保证线程安全
 * 3. 零GC：在高并发场景下可避免频繁GC
 * 
 * 使用场景：
 * - P2P协议消息的封装和传输
 * - 高性能网络通信中的消息包装
 * - 需要对象复用的高并发场景
 * 
 * @author iamkarl@163.com
 * @param <T> 包装的数据类型  
 * @version 1.0
 * @since 2025
 */
public class P2PWrapper<T> extends PooledableAdapter implements ReferenceCounted{

    /** 
     * 消息序列号 - 用于消息的唯一标识和顺序控制
     * 
     * 作用：
     * 1. 消息去重：避免重复处理相同的消息
     * 2. 顺序保证：确保消息按发送顺序处理
     * 3. 请求响应匹配：将响应与对应的请求关联
     * 
     * 生成规则：
     * - 通常由客户端生成递增的序列号
     * - 服务器在响应中返回相同的序列号
     * - 建议使用原子计数器生成
     */
    protected int seq;
    
    /** 
     * P2P协议命令 - 定义消息的类型和操作
     * 
     * 命令类型包括：
     * - 握手命令：HAND, R_OK_HAND
     * - 文件操作：GET_FILE, PUT_FILE, R_OK_GET_FILE
     * - 云存储操作：GET_COS_FILE, PUT_COS_FILE
     * - 心跳检测：HEART_BEAT
     * - 错误响应：R_ERROR
     * 
     * @see javax.net.p2p.api.P2PCommand
     */
    protected P2PCommand command;
    
    /** 
     * 业务数据 - 消息携带的具体业务信息
     * 
     * 数据类型根据命令不同而不同：
     * - 文件操作：FileDataModel 或 FileSegmentsDataModel
     * - 云存储操作：CosFileDataModel
     * - HDFS操作：HdfsFileDataModel
     * - 配置信息：ConfigDataModel
     * 
     * 泛型T允许包装任意类型的数据对象
     */
    protected T data;

    public P2PWrapper() {
    }

    public P2PWrapper(P2PCommand command, T data) {
        this.command = command;
        this.data = data;
    }

    public P2PWrapper(int seq, P2PCommand command, T data) {
        this.seq = seq;
        this.command = command;
        this.data = data;
    }

    /**
     * 创建带命令和数据的消息包装对象（无序列号）
     * 
     * 使用场景：
     * - 不需要序列号控制的简单消息
     * - 服务器主动推送的消息
     * - 广播消息
     * 
     * 实现原理：
     * 1. 从线程本地对象池获取可重用的P2PWrapper实例
     * 2. 设置命令类型和业务数据
     * 3. 返回配置好的消息包装对象
     * 
     * 性能优化：
     * - 使用对象池避免频繁创建新对象
     * - 减少GC压力，提高吞吐量
     * 
     * @param <T> 数据的泛型类型
     * @param command P2P协议命令
     * @param data 业务数据
     * @return 配置好的消息包装对象
     */
    public final static <T> P2PWrapper<T> build(P2PCommand command, T data) {
        P2PWrapper<T> wrapper = ConcurrentObjectPool.get().poll();
        wrapper.setData(data);
        wrapper.setCommand(command);
        return wrapper;
    }

    /**
     * 创建仅带命令的消息包装对象（无序列号和数据）
     * 
     * 使用场景：
     * - 简单的心跳消息
     * - 命令确认消息
     * - 空数据消息
     * 
     * @param <T> 数据的泛型类型
     * @param command P2P协议命令
     * @return 仅包含命令的消息包装对象
     */
    public final static <T> P2PWrapper<T> build(P2PCommand command) {
        P2PWrapper<T> wrapper = ConcurrentObjectPool.get().poll();
        wrapper.setCommand(command);
        return wrapper;
    }

    /**
     * 创建完整的消息包装对象（包含序列号、命令和数据）
     * 
     * 使用场景：
     * - 需要序列号控制的请求响应模式
     * - 文件传输的请求消息
     * - 需要保证顺序的业务操作
     * 
     * 参数说明：
     * @param <T> 数据的泛型类型
     * @param seq 消息序列号，用于请求响应匹配
     * @param command P2P协议命令
     * @param data 业务数据
     * @return 完整的消息包装对象
     */
    public final static <T> P2PWrapper<T> build(int seq, P2PCommand command, T data) {
        P2PWrapper<T> wrapper = ConcurrentObjectPool.get().poll();
        wrapper.setSeq(seq);
        wrapper.setData(data);
        wrapper.setCommand(command);
        return wrapper;
    }
      
    /**
     * 创建带序列号和命令的消息包装对象（无数据）
     * 
     * 使用场景：
     * - 需要序列号但无数据的心跳消息
     * - 命令确认带序列号
     * - 简单的控制命令
     * 
     * @param <T> 数据的泛型类型
     * @param seq 消息序列号
     * @param command P2P协议命令
     * @return 带序列号的消息包装对象
     */
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
        data = null;
    }
    
    @Override
    public void recycle() {
        ConcurrentObjectPool.get().offer(this);
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
    public boolean release(int decrement) {
        this.recycle();
        return true;
    }

    @Override
    public boolean release() {
        this.recycle();
        return true;
    }


    
    
   
    /**
     * ConcurrentObjectPool - 线程本地对象池实现类
     * 
     * 功能说明：
     * 1. 为每个线程维护独立的P2PWrapper对象池
     * 2. 提供对象的获取(poll)和释放(offer)操作
     * 3. 避免多线程竞争，提高并发性能
     * 
     * 设计原理：
     * - 使用ThreadLocal保证每个线程有自己的对象池
     * - 对象池初始容量为4096，可根据需要调整
     * - 对象工厂负责创建新的P2PWrapper实例
     * 
     * 性能优势：
     * 1. 线程安全：无需同步锁，提高并发性能
     * 2. 内存局部性：对象在同一个线程内复用，缓存友好
     * 3. 减少GC：对象复用减少垃圾产生
     * 
     * 注意事项：
     * - 对象使用后必须调用release()方法释放回池中
     * - 不要在多个线程间共享P2PWrapper实例
     * - 对象池大小需要根据实际负载调整
     */
    static class ConcurrentObjectPool {

        /** 线程本地对象池，每个线程独立维护自己的对象缓存 */
        private static final ThreadLocal<PooledObjects<P2PWrapper>> LOCAL_POOL = new ThreadLocal<>();

        /**
         * 获取当前线程的对象池实例
         * 
         * 实现机制：
         * 1. 检查当前线程是否已有对象池
         * 2. 如没有则创建新的对象池（懒加载）
         * 3. 对象池容量为4096，使用默认对象工厂
         * 
         * 性能考虑：
         * - 第一次访问时创建对象池，避免不必要的初始化
         * - 对象池按需创建，减少内存占用
         * 
         * @return 当前线程的P2PWrapper对象池
         */
        private static PooledObjects<P2PWrapper> get() {
            PooledObjects pool = LOCAL_POOL.get();
            if (pool == null) {
                // 创建容量为4096的对象池，使用匿名工厂类创建新实例
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
