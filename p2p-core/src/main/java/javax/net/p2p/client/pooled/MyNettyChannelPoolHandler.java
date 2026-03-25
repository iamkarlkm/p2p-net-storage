
package javax.net.p2p.client.pooled;

import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.socket.SocketChannel;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import javax.net.p2p.channel.PipelineInitializer;
import lombok.extern.slf4j.Slf4j;

/**
 * 
 * @author karl
 */
@Slf4j
public class MyNettyChannelPoolHandler implements ChannelPoolHandler {
//    private static final AtomicInteger COUNT = new AtomicInteger();
	
	private final ExecutorService executorService;
	private final int queueSize;
	private final InetSocketAddress key;
    private MyClientPoolMessagerProcessor myClientPoolMessagerProcessor;


	public MyNettyChannelPoolHandler(InetSocketAddress key,ExecutorService executorService, int queueSize) {
//		this.myClient = myClient;
		this.key = key;
		this.executorService = executorService;
		this.queueSize = queueSize;
	}
	@Override
	public void channelReleased(Channel ch) throws Exception {
		log.info("{} channelReleased. Channel ID: {}" ,ch.remoteAddress(), ch.id());
//				ChannelFuture closeFuture = ch.closeFuture();

	}

	@Override
	public void channelAcquired(Channel ch) throws Exception {
		//       System.out.println("channelAcquired. Channel ID: " + ch.id());
//		if (!ch.isOpen() || !ch.isActive()) {
//                    //ch.close();
//                    //System.out.println("channel is invalid. Channel ID: " + ch.id());
//                    throw new ChannleInvalidException("channel is invalid. Channel ID: " + ch.id());
//		}
	}

	@Override
	public void channelCreated(Channel ch) throws Exception {
		log.info("{} channelCreated. Channel ID: {}" ,ch.remoteAddress(), ch.id());
		 //System.out.println("channelCreated. Channel ID: " + ch.id());
         //单例MyClient获取已注册的对应P2PNode,并建立双向关联
         P2PNode node = MyClient.getInstance().getNode(key);
		myClientPoolMessagerProcessor = new MyClientPoolMessagerProcessor(node,ch,executorService, queueSize);
        node.offerBusinessProcessor(myClientPoolMessagerProcessor);
        //初始化流水线
        PipelineInitializer.initProcessors((SocketChannel) ch, myClientPoolMessagerProcessor);

	}

    public MyClientPoolMessagerProcessor getMyClientPoolMessagerProcessor() {
        return myClientPoolMessagerProcessor;
    }
    
    
}