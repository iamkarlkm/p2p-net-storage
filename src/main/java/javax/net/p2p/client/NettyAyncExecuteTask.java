/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package javax.net.p2p.client;

import io.netty.channel.Channel;
import io.netty.channel.pool.SimpleChannelPool;
import java.io.File;
import java.util.concurrent.Callable;
import javax.net.p2p.model.P2PWrapper;

/**
 *
 * @author Administrator
 */
public class NettyAyncExecuteTask   implements Callable<P2PWrapper>{
    
    private final Channel channel;
    private final P2PWrapper request;
    private final long timeout;
	private final SimpleChannelPool channelPool;
	
	private final ClientMessageProcessor clientMessageProcessor;
    

    public NettyAyncExecuteTask(ClientMessageProcessor clientMessageProcessor,SimpleChannelPool channelPool,Channel channel,P2PWrapper request, long timeout) {
        this.channel = channel;
        this.request = request;
        this.timeout = timeout;
		this.channelPool = channelPool;
		this.clientMessageProcessor = clientMessageProcessor;
    }

   
    

    @Override
    public P2PWrapper call() throws Exception {
        return clientMessageProcessor.syncWait(channelPool,channel,request, timeout);
    }
    
}
