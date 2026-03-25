package javax.net.p2p.client;

import javax.net.p2p.channel.ChannelUtils;
import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.pool.SimpleChannelPool;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.pooled.MyClient;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.P2PServerTcp;
import lombok.extern.slf4j.Slf4j;
import javax.net.p2p.interfaces.P2PClientService;
import static javax.net.p2p.server.AbstractP2PServer.SERVER_IP;
import static javax.net.p2p.server.AbstractP2PServer.SERVER_PORT;

/**
 * 单一客户端多路(channel池)连接单一服务器
 * @author karl
 */
@Slf4j
public class P2PClient  implements Closeable {
    

    private final InetAddress address;
    private final int port;
    private static final long DEFAULT_TIMEOUT = 300000;
    
    private static final AtomicInteger MESSAGE_SEQUENCE = new AtomicInteger();
    
    private final InetSocketAddress remoteServer;
    
       // public final static String SERVER_IP = "86.85.160.18";
       // public static String SERVER_IP = "86.84.250.66";//hdfs image server
//    public final static String SERVER_IP = "127.0.0.1";
    private volatile static P2PClient CLIENT;
    
    private  SimpleChannelPool tcpChannelPool;
	
	
	private NettyPoolClient nettyPoolClient;
	
	

    //@Override
    public SimpleChannelPool getChannelPool() {
        return tcpChannelPool;
    }
	
	

    //@Override
	public InetSocketAddress getRemoteServer() {
		return remoteServer;
	}

	public NettyPoolClient getNettyPoolClient() {
		return nettyPoolClient;
	}

    //private MyClient myClient;
	
	private P2PClient(InetAddress address, int port) {
        this.address = address;
        this.port = port;
        this.remoteServer = new InetSocketAddress(address, port);
		this.init();
    }
    
    
    public static P2PClient getInstance(String serverIp,int port) throws UnknownHostException{
        
        return new P2PClient(InetAddress.getByName(serverIp),port);
    }
    
    public static P2PClient getInstance(InetAddress server,int port) throws UnknownHostException{
        //ChannelGroup channelGroup = SessionUtil.getChannelGroup(groupId);
        return new P2PClient(server,port);
    }
   
   private void init() {
	   this.nettyPoolClient = NettyPoolClient.getInstance(this);
        this.tcpChannelPool = this.nettyPoolClient.getChannelPool(remoteServer);
   }
   
    public static P2PClient getInstance() throws UnknownHostException{
        if(CLIENT==null){
            synchronized(P2PClient.class){
                if(CLIENT==null){
                    CLIENT = new P2PClient(InetAddress.getByName(SERVER_IP),SERVER_PORT);
										//System.out.println(CLIENT.remoteServer);
										log.info("客户端{}创建成功,服务器节点:{}", CLIENT,CLIENT.remoteServer);
                }
            }
        }
        return CLIENT;
    }
	
	 public static P2PClient getInstance(String serverIp) throws UnknownHostException{
        
        return new P2PClient(InetAddress.getByName(serverIp),SERVER_PORT);
    }


    public static long getTimeout() {
        return DEFAULT_TIMEOUT;
    }

    
    public InetAddress getAddress() {
        return address;
    }
    
   
    public int getPort() {
        return port;
    }

    

    /**
     * 实现接口的核心方法
     *
     * @param request
     * @return
	 * @throws java.lang.Exception
     */
   // @Override
    public P2PWrapper excute(final P2PWrapper request) throws Exception {

        //程序需要维护channel生命周期 
        io.netty.util.concurrent.Future<Channel> future = tcpChannelPool.acquire();
        Channel channel = future.get();
        
//        Channel channel = nettyChannelPool.syncGetChannel();
        //为每个线程建立一个callback,当消息返回的时候,在callback中获取结果
        //CallbackService callbackService = new CallbackService();
//        int seq = ChannelUtils.getSequenceNextVal(channel);//消息序列号
        //int seq = MESSAGE_SEQUENCE.incrementAndGet();//消息序列号
        //request.setSeq(seq);
        //利用Channel的attr方法,建立消息与callback的对应关系
        //ChannelUtils.putCallback2DataMap(channel, seq, callbackService);

        //channel.writeAndFlush(request);
		
        //simpleChannelPool.release(channel);
        // Release back to pool
//        nettyChannelPool.release(channel);
				//        return callbackService.result;
        return ChannelUtils.getClientMessageProcessor(channel).syncWait(tcpChannelPool,channel,request, DEFAULT_TIMEOUT);
        
    }
	
    
     /**
     * 请求异步执行
     *
     * @param request
     * @return
     */
   // @Override
    public Future<P2PWrapper> asyncExcute(final P2PWrapper request) throws Exception {

        //final ClientMessageHandler clientMessageHandler = new ClientMessageHandler();
        io.netty.util.concurrent.Future<Channel> future = tcpChannelPool.acquire();
        Channel channel = future.get();
        //为每个线程建立一个callback,当消息返回的时候,在callback中获取结果
        //CallbackService callbackService = new CallbackService();
        //int seq = ChannelUtils.getSequenceNextVal(channel);//消息序列号
		int seq = MESSAGE_SEQUENCE.incrementAndGet();//消息序列号
        request.setSeq(seq);
        //利用Channel的attr方法,建立消息与callback的对应关系
        //ChannelUtils.putCallback2DataMap(channel, seq, callbackService);

        //channel.writeAndFlush(request);
//        channelPool.release(channel);
        NettyAyncExecuteTask task = new NettyAyncExecuteTask(
            ChannelUtils.getClientMessageProcessor(channel),
            tcpChannelPool,channel,request, DEFAULT_TIMEOUT);
        Future<P2PWrapper> futureTask = ExecutorServicePool.P2P_REFERENCED_CLIENT_ASYNC_POOLS.submit(task);
        return futureTask;
        
    }
	
	 
   
    
	
    public static void main(String[] args) throws Exception {
        P2PClient client = new P2PClient(InetAddress.getLocalHost(), SERVER_PORT);
				System.out.println("test udp client...");
				for(int i=0;i<10;i++){
			P2PWrapper p2p = P2PWrapper.build(P2PCommand.ECHO, "test udp-"+i);
			System.out.println(client.excute(p2p).getData());
		}
        

    }
    
//    public static class CallbackService{
//        public volatile P2PWrapper result;
//        public void receiveMessage(P2PWrapper msg) throws Exception {
//            synchronized (this) {
//                result = msg;
//                this.notify();
//            }
//        }
//    }

    @Override
    public void close() throws IOException {
         try {
             tcpChannelPool.close();
               nettyPoolClient.close();
        } catch (Exception ex) {
            Logger.getLogger(P2PClient.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
}
