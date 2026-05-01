package javax.net.p2p.interfaces;

import javax.net.p2p.channel.AbstractStreamResponseAdapter;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.model.P2PWrapper;

/**
 * 为长流请求暴露与首帧绑定的发送 executor，避免同一流后续帧漂移到其他连接。
 */
public interface BoundStreamMessageService {

    BoundStreamRequest openBoundStreamRequest(P2PWrapper request, AbstractStreamResponseAdapter streamMessage) throws Exception;

    record BoundStreamRequest(P2PWrapper ack, AbstractSendMesageExecutor executor) {
    }
}
