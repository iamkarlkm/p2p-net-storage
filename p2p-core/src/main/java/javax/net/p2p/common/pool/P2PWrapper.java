package javax.net.p2p.common.pool;



import io.netty.util.Recycler;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.pool.Recyclable;
import lombok.Data;

/**
 *
 * P2P消息包装类（完整支持对象池）
 *
 * 特性：
 *
 * 实现Recyclable接口，支持自定义对象池 支持Netty Recycler，兼容Netty对象池 提供完整的状态清理逻辑 线程安全的对象复用
 *
 * @author iamkarl@163.com
 */
@Data
public class P2PWrapper implements Recyclable {

    /**
     * Recycler句柄，用于Netty对象池回收（可选）
     */
    private transient Recycler.Handle handle;

    /**
     * 消息序列号
     */
    private int seq;

    /**
     * 命令类型
     */
    private P2PCommand command;

    /**
     * 消息数据
     */
    private Object data;

    /**
     * 时间戳（毫秒）
     */
    private long timestamp;

    /**
     * 扩展字段（不序列化）
     */
    private transient Object extra;

    /**
     * 消息优先级（可选，0-9，数字越大优先级越高）
     */
    private transient int priority;

    /**
     * 重试次数（可选）
     */
    private transient int retryCount;

    /**
     * 最大重试次数（可选）
     */
    private transient int maxRetries;
/**
         *
         * 默认构造函数（供自定义对象池使用）
         */ 

    public P2PWrapper() {
        this.handle = null;
        this.maxRetries = 3;
    }
    /**
     *
     * 构造函数（供Netty Recycler使用）
     *
     * @param handle Recycler句柄
     */
    public P2PWrapper(Recycler.Handle handle) {
        this.handle = handle;
        this.maxRetries = 3; // 默认最大重试3次 }
        
    }
    /**
     *
     * 静态工厂方法（兼容旧代码）
     *
     * @param seq 序列号
     * @param command 命令类型
     * @return P2PWrapper实例
     */
    public static P2PWrapper build(int seq, P2PCommand command) {
        P2PWrapper wrapper = new P2PWrapper();
        wrapper.setSeq(seq);
        wrapper.setCommand(command);
        wrapper.setTimestamp(System.currentTimeMillis());
        return wrapper;
    }

    /**
     *
     * 静态工厂方法（带数据）
     *
     * @param seq 序列号
     * @param command 命令类型
     * @param data 数据对象
     * @return P2PWrapper实例
     */
    public static P2PWrapper build(int seq, P2PCommand command, Object data) {
        P2PWrapper wrapper = new P2PWrapper();
        wrapper.setSeq(seq);
        wrapper.setCommand(command);
        wrapper.setData(data);
        wrapper.setTimestamp(System.currentTimeMillis());
        return wrapper;
    }

    /**
     *
     * 清理对象状态（实现Recyclable接口） 重要： 不清理handle字段（对象池需要） 清理所有业务字段 重置为初始状态 不抛出异常
     */
    @Override
    public void clear() {
        this.seq = 0;
        this.command = null;
        this.data = null;
        this.timestamp = 0;
        this.extra = null;
        this.priority = 0;
        this.retryCount = 0; // maxRetries保持不变，作为配置项 }
        /**
         *
         * 回收对象到Netty对象池 注意：仅当使用Netty Recycler创建时有效
         */ 
    }
    public void recycle() {
        if (handle != null) {
            handle.recycle(this);
        }
    }

    /**
     *
     * 获取Recycler句柄
     *
     * @return Recycler句柄，可能为null
     */
    public Recycler.Handle getHandle() {
        return handle;
    }

    /**
     *
     * 判断是否可以重试
     *
     * @return true表示可以重试
     */
    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    /**
     *
     * 增加重试次数
     *
     * @return 当前重试次数
     */
    public int incrementRetry() {
        return ++retryCount;
    }

    /**
     *
     * 判断是否为心跳消息
     *
     * @return true表示是心跳消息
     */
    public boolean isHeartbeat() {
        return command == P2PCommand.HEART_PING || command == P2PCommand.HEART_PONG;
    }

    /**
     *
     * 判断是否为高优先级消息
     *
     * @return true表示高优先级（priority >= 7）
     */
    public boolean isHighPriority() {
        return priority >= 7;
    }

    @Override
    public String toString() {
        return String.format(
            "P2PWrapper{seq=%d, command=%s, timestamp=%d, priority=%d, retry=%d/%d, data=%s}",
            seq, command, timestamp, priority, retryCount, maxRetries,
            data != null ? data.getClass().getSimpleName() : "null"
        );
    }

    /**
     *
     * 生成简短描述（用于日志）
     *
     * @return 简短描述
     */
    public String toShortString() {
        return String.format("P2PWrapper{seq=%d, cmd=%s}", seq, command);
    }
}
