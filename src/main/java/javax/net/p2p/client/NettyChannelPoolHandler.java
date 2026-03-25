/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package javax.net.p2p.client;

import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelPoolHandler;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * 
 * @author karl
 */
@Slf4j
public class NettyChannelPoolHandler implements ChannelPoolHandler {
    private static final AtomicInteger COUNT = new AtomicInteger();
	
	private  P2PClient client;

	public P2PClient getClient() {
		return client;
	}

	public void setClient(P2PClient client) {
		this.client = client;
	}

	public NettyChannelPoolHandler(P2PClient client) {
		this.client = client;
	}
	
	
	@Override
	public void channelReleased(Channel ch) throws Exception {
		        System.out.println("channelReleased. Channel ID: " + ch.id());
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
        System.out.println("channelCreated total:"+COUNT.incrementAndGet());

	}
}