package javax.net.p2p.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import java.net.UnknownHostException;
import javax.net.p2p.codec.P2PWrapperEncoder;
import lombok.extern.slf4j.Slf4j;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.common.AbstractP2PMessageServiceAdapter;
import javax.net.p2p.common.ReferencedSingleton;

/**
 * 单一客户端多路(channel池)连接单一服务器
 *
 * @author karl
 */
@Slf4j
public abstract class AbstractP2PServer extends ServerReferencedSingleton {
    
    protected static final long DEFAULT_TIMEOUT = 300000;
    protected static final int DEFAULT_QUEUESIZE = 4096;
    protected static final int DEFAULT_CORESIZE = 2;
    protected static final int DEFAULT_MAGIC  = P2PWrapperEncoder.MAGIC;
    public static String SERVER_IP = "127.0.0.1";
    public static int SERVER_PORT = 6060;
    
    protected int coreSize;
    protected int queueSize;
    
   /**
     * 应用数据包验证以及自定义动态协议标记
     */
    protected int magic;

    /**
     * 服务器监听端口
     */
    protected int port;
    
    protected Bootstrap server;
    
    protected EventLoopGroup acceptBossGroup;
    
    public AbstractP2PServer(int port) {
        this(port,DEFAULT_QUEUESIZE);
    }
    
    public AbstractP2PServer(int port, int queueSize) {
        this(port,queueSize, DEFAULT_CORESIZE,P2PWrapperEncoder.MAGIC);
    }
    
    public AbstractP2PServer(int port, int queueSize,int coreSize) {
        this(port,queueSize, coreSize, P2PWrapperEncoder.MAGIC);
    }

   
    public AbstractP2PServer(int port, int queueSize, int coreSize, int magic) {
        this.coreSize = coreSize;
        this.queueSize = queueSize;
        this.magic = magic;
        this.port = port;
        ExecutorServicePool.createServerPools();
    }

    public static <T> T getInstance(Class<T> clazz) throws UnknownHostException {
        return getInstanceBySuper(clazz,SERVER_PORT);
    }

    public static <T> T getInstance(Class<T> clazz, Integer port) throws UnknownHostException {
        return getInstanceBySuper(clazz, port);
    }

    public static <T> T getInstance(Class<T> clazz, String serverIp, Integer port, Integer queueSize) throws UnknownHostException {

        return getInstanceBySuper(clazz, port, queueSize);
    }

    public static <T> T getInstance(Class<T> clazz, Integer port, Integer queueSize, Integer coreSize) throws UnknownHostException {

        return getInstanceBySuper(clazz, port, queueSize, coreSize);
    }

    public synchronized static <T> T getInstance(Class<T> clazz, Integer port, Integer queueSize, Integer coreSize, Integer magic) throws UnknownHostException {
        return getInstanceBySuper(clazz, port, queueSize, coreSize, magic);
    }

}
