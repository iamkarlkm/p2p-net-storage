package javax.net.p2p.client;

import io.netty.channel.ChannelHandlerContext;
import java.util.concurrent.CountDownLatch;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractQuicMessageProcessor;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author karl
 */
@Slf4j
public class ClientQuicMessageProcessor extends AbstractQuicMessageProcessor {

    private P2PMessageService client;
    private CountDownLatch latch;

    public ClientQuicMessageProcessor(P2PMessageService client, int magic, int queueSize) {
        super(magic, queueSize);
        this.client = client;
    }

    public ClientQuicMessageProcessor(P2PMessageService client, int magic, int queueSize, CountDownLatch latch) {
        super(magic, queueSize);
        this.client = client;
        this.latch = latch;
    }

    public CountDownLatch getLatch() {
        return latch;
    }

    public void setLatch(CountDownLatch latch) {
        this.latch = latch;
    }

    @Override
    public void processMessage(ChannelHandlerContext ctx, P2PWrapper response) {
        if(log.isDebugEnabled()){
            log.debug("channel {} read msg ->\n{}", ctx.channel().id(), response);
        }
       log.info("client recieve response:{}", response);
        client.complete(response);
    }
}