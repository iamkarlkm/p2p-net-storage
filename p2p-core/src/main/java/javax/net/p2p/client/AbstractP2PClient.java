package javax.net.p2p.client;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import lombok.extern.slf4j.Slf4j;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.common.AbstractP2PMessageServiceAdapter;

/**
 * 单一客户端多路(channel池)连接单一服务器
 * @author karl
 */
@Slf4j
public abstract class AbstractP2PClient extends AbstractP2PMessageServiceAdapter implements AutoCloseable{
    
    public static String SERVER_IP = "127.0.0.1";
    public static int SERVER_PORT = 6060;
    
    private int clientRefCount = 0;


    public AbstractP2PClient(InetSocketAddress remoteServer, int queueSize, int coreSize, int magic) {
        super(queueSize, coreSize, magic);
        this.remoteServer = remoteServer;
        
        // 初始化Bootstrap
        this.bootstrap = new io.netty.bootstrap.Bootstrap();
        // this.io_work_group = ExecutorServicePool.createClientPools();
        // this.io_work_group = new io.netty.channel.nio.NioEventLoopGroup();
        // do not set group here, let connect method set it
        // this.bootstrap.group(this.io_work_group)
        //         .channel(io.netty.channel.socket.nio.NioDatagramChannel.class)
        //         .handler(new io.netty.channel.ChannelInitializer<io.netty.channel.socket.nio.NioDatagramChannel>() {
        //             @Override
        //             protected void initChannel(io.netty.channel.socket.nio.NioDatagramChannel ch) throws Exception {
        //                 javax.net.p2p.channel.PipelineInitializer.initProcessorsUdpWithReliability(ch, 
        //                         new ClientUdpMessageProcessor(AbstractP2PClient.this, magic, queueSize), 
        //                         magic, true);
        //             }
        //         });
        
        ExecutorServicePool.createClientPools();
        clientRefCount++;
    }

    
    
    /** 
     * 连接服务器 -> P2PServerUdp.SERVER_IP,P2PServerUdp.SERVER_PORT
     *
     * @param <T>
     * @param clazz
     * @return
     * @throws UnknownHostException
     */
    public static <T> T getInstance(Class<T> clazz) throws UnknownHostException {
        return getInstanceBySuper(clazz,new InetSocketAddress(SERVER_IP, SERVER_PORT));
    }

    public static <T> T getInstance(Class<T> clazz,String serverIp, int port) throws UnknownHostException {

        return getInstanceBySuper(clazz,new InetSocketAddress(serverIp, port));
    }

    public static <T> T getInstance(Class<T> clazz,InetAddress server, int port) throws UnknownHostException {
        return getInstanceBySuper(clazz,new InetSocketAddress(server, port));
    }

    public synchronized static <T> T getInstance(Class<T> clazz,InetSocketAddress remoteServer) throws UnknownHostException {
        return getInstanceBySuper(clazz, remoteServer);


    }

    public static  <T> T getInstance(Class<T> clazz,String serverIp, int port, int queueSize) throws UnknownHostException {

        return getInstanceBySuper(clazz,new InetSocketAddress(serverIp, port), queueSize);
    }

    public synchronized static <T> T getInstance(Class<T> clazz,InetSocketAddress remoteServer, int queueSize) throws UnknownHostException {
        return  getInstanceBySuper(clazz, remoteServer, queueSize);
    }
    
    public static <T> T getInstance(Class<T> clazz,String serverIp, int port, int queueSize,int coreSize) throws UnknownHostException {

        return getInstanceBySuper(clazz,new InetSocketAddress(serverIp, port), queueSize,coreSize);
    }

    public synchronized static <T> T getInstance(Class<T> clazz,InetSocketAddress remoteServer, int queueSize,int coreSize) throws UnknownHostException {
        
       return getInstanceBySuper(clazz,remoteServer, queueSize,coreSize);
    }
    
    public synchronized static <T> T getInstance(Class<T> clazz,InetSocketAddress remoteServer, int queueSize,int coreSize,int magic) throws UnknownHostException {
       return getInstanceBySuper(clazz,remoteServer, queueSize,coreSize,magic);
    }
    
    /**
     * 
     */
   public void shutdown() {
       super.released();
       clientRefCount--;
       if(clientRefCount<=0){
           ExecutorServicePool.releaseP2PClientPools();
       }
   }
   
    @Override
   public void close(){
       shutdown();
   }
    
}
