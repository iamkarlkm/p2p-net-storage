package javax.net.p2p.client;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import lombok.extern.slf4j.Slf4j;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.common.AbstractP2PMessageServiceAdapter;
import javax.net.p2p.server.P2PServer;

/**
 * 单一客户端多路(channel池)连接单一服务器
 * @author karl
 */
@Slf4j
public abstract class AbstractP2PClient extends AbstractP2PMessageServiceAdapter {


    public AbstractP2PClient(InetSocketAddress remoteServer,int queueSize,  int coreSize, int magic) {
        super(queueSize, coreSize, magic);
        this.remoteServer = remoteServer;
               
        ExecutorServicePool.createClientPools();
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
        return getInstanceBySuper(clazz,new InetSocketAddress(P2PServer.SERVER_IP, P2PServer.SERVER_PORT));
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
    
   
    
}
