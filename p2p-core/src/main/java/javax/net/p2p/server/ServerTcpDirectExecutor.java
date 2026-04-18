package javax.net.p2p.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.model.P2PWrapper;

public class ServerTcpDirectExecutor extends AbstractSendMesageExecutor {

    public ServerTcpDirectExecutor(int queueSize, Channel channel) {
        super(queueSize);
        this.channel = channel;
        this.connected = true;
    }

    @Override
    public void connect(EventLoopGroup io_work_group, Bootstrap bootstrap) {
    }

    @Override
    public void recycle() {
    }

    @Override
    public void sendResponse(P2PWrapper response) {
        channel.writeAndFlush(response);
    }
}

