

package javax.net.p2p.client.pooled;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.AbstractChannelPoolMap;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.pool.SimpleChannelPool;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.PipelineInitializer;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.StopWatch;

/**
 * 统一管理多个客户端连接多个服务器
 * @author karl
 */
@Slf4j
public class MyClient implements Closeable {
	private static final int NODE_THREADS = Runtime.getRuntime().availableProcessors();

	private static final int MAX_QUEUED_REQUESTS = 4096;

	//private final MyClientHandler clientHandler;

	private final EventLoopGroup ioWorkerEventLoopGroup;//io工作线程(事件)组

	private final Bootstrap acceptBootstrap;//主线程

	//private final ExecutorService executorService;

	private final AbstractChannelPoolMap<InetSocketAddress, SimpleChannelPool> channelPoolMap;

	private final ConcurrentMap<InetSocketAddress, P2PNode> nodesMap;
    
    //private final ArrayBlockingQueue<MyNettyChannelPoolHandler> businessChannelPoolHandlerMap;
	
	//private final ConcurrentMap<InetSocketAddress, ExecutorService> executorServiceMap;
    private final ExecutorService executorService;

	private volatile static MyClient CLIENT;
    
    private final int queueSize = 4096;

	public static MyClient getInstance() throws UnknownHostException {
		if (CLIENT == null) {
			synchronized (MyClient.class) {
				if (CLIENT == null) {
					CLIENT = new MyClient();
				}
			}
		}
		return CLIENT;
	}

	private MyClient() {
		nodesMap = new ConcurrentHashMap<>();
        //this.queueSize 
        //businessChannelPoolHandlerMap = new ConcurrentHashMap<>();
		executorService = Executors.newFixedThreadPool(NODE_THREADS);
		//executorService = Executors.newFixedThreadPool(NODE_THREADS);
		//		clientHandler = new MyClientHandler(executorService, MAX_QUEUED_REQUESTS);
		ioWorkerEventLoopGroup = new NioEventLoopGroup();//默认cpu核心数*2
		acceptBootstrap = new Bootstrap();
		acceptBootstrap.group(ioWorkerEventLoopGroup).channel(NioSocketChannel.class);
        PipelineInitializer.initClientOptions(acceptBootstrap);
       
		channelPoolMap = new AbstractChannelPoolMap<InetSocketAddress, SimpleChannelPool>() {
			@Override
			protected SimpleChannelPool newPool(InetSocketAddress key) {
				MyNettyChannelPoolHandler businessChannelPoolHandler = new MyNettyChannelPoolHandler(key, executorService, MAX_QUEUED_REQUESTS);
                //businessChannelPoolHandlerMap.put(key, businessChannelPoolHandler);
				return new FixedChannelPool(acceptBootstrap.remoteAddress(key), businessChannelPoolHandler,
						ChannelHealthChecker.ACTIVE, FixedChannelPool.AcquireTimeoutAction.FAIL, 900000, Runtime.getRuntime().availableProcessors() * 2, MAX_QUEUED_REQUESTS);
			}
		};
		
	}

	public synchronized P2PNode newNode(String remoteHost, int remotePort) {
		try {
			InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(remoteHost), remotePort);
			P2PNode node = nodesMap.get(address);
			if (node != null) {
				return node;
			}
			node = new P2PNode(address, channelPoolMap,queueSize);
			nodesMap.put(address, node);
			log.info("客户端{}创建成功,服务器节点:{}", node, address);
			//System.out.println(nodesMap);
			return node;
		} catch (UnknownHostException ex) {
			throw new RuntimeException(ex);
		}
	}

	public P2PNode getNode(InetSocketAddress address) {
		P2PNode node = nodesMap.get(address);
		if (node == null) {
			System.out.println("create node -> "+address);
			node = newNode(address.getHostName(), address.getPort());
		}
		return node;
	}
    
    public P2PNode removeNode(InetSocketAddress address) {
		P2PNode node = nodesMap.remove(address);
		if (node != null) {
            try {
                node.close();
            } catch (IOException ex) {
                log.error(ex.getMessage(), ex);
            }
		}
		return node;
	}

	public void shutdown() {
		// Clean up resources
		for(P2PNode node:nodesMap.values()){
			try {
				node.close();
			} catch (IOException ex) {
				Logger.getLogger(MyClient.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
//		for(ExecutorService service:executorServiceMap.values()){
//			service.shutdown();
//		}
        executorService.shutdown();
		channelPoolMap.close();
		ioWorkerEventLoopGroup.shutdownGracefully();
		//bootstrap.group().shutdown();
		CLIENT = null;
	}
	
	public void shutdownNow() {
		// Clean up resources
		for(P2PNode node:nodesMap.values()){
			try {
				node.close();
			} catch (IOException ex) {
				Logger.getLogger(MyClient.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
//		for(ExecutorService service:executorServiceMap.values()){
//			service.shutdownNow();
//		}
        executorService.shutdownNow();
		channelPoolMap.close();
		ioWorkerEventLoopGroup.shutdownNow();
		CLIENT = null;
	}

	public static void checkAndRelease() {
		if (CLIENT != null) {
			CLIENT.shutdown();
		}
	}

	public static String echo(P2PNode node, String msg) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.ECHO, msg);
		P2PWrapper response = (P2PWrapper) node.excute(p2p);
		//P2PCommandHandler handler = (P2PCommandHandler) registryMap.get(response.getCommand());
		//System.out.println(handler);
		//if(handler!=null){
		if (response.getCommand().getValue() == P2PCommand.ECHO.getValue()) {

			return (String) response.getData();
		}
		//}
		throw new RuntimeException("服务器内部错误：" + response.getData());
	}

	public static void test(P2PNode node, int count) throws Exception {
		StopWatch stopWatch = new StopWatch();
		System.out.println("test执行开始...");
		stopWatch.start();
		for (int i = 0; i < count; i++) {
			//System.out.println("echo -> "+i);
			System.out.println(echo(node, "test-" + i));
		}
		stopWatch.stop();
		// 统计执行时间（秒）
		System.out.println("执行时长：" + stopWatch.getTime() / 1000 + " 秒.");
		// 统计执行时间（毫秒）
		System.out.println("执行时长：" + stopWatch.getTime() + " 毫秒.");
		// 统计执行时间（纳秒）
		System.out.println("执行时长：" + stopWatch.getNanoTime() + " 纳秒.");
	}

	static int currrentFailCount = 0;
	public static void main(String[] args) throws Exception {
		MyClient myClient = MyClient.getInstance();
		test(myClient.newNode("127.0.0.1", 6060), 10);
//		while (currrentFailCount <60) {
//			try {
//				Thread.sleep(10000l);
//				currrentFailCount++;
//				System.out.println("...");
//			} catch (InterruptedException e) {
//				log.error(e.getMessage());
//			}
//		}
		myClient.shutdownNow();
		System.out.println("MyClient shurdown.");
		CLIENT = null;
	}

	@Override
	public void close() throws IOException {
		shutdown() ;
	}
}
