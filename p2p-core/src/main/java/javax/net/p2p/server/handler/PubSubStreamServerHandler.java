package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractStreamRequestAdapter;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.interfaces.StreamRequest;
import javax.net.p2p.model.P2PPubSubMessage;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.server.pubsub.PubSubBroker;

public class PubSubStreamServerHandler extends AbstractStreamRequestAdapter implements StreamRequest {
    private String topic;

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.PUBSUB_STREAM;
    }

    @Override
    public StreamP2PWrapper request(AbstractSendMesageExecutor executor, StreamP2PWrapper message) {
        Object data = message.getData();
        if (data instanceof P2PPubSubMessage m) {
            topic = m.topic;
            if (message.getIndex() == 0) {
                boolean ok = PubSubBroker.subscribe(topic, message.getSeq(), executor);
                if (!ok) {
                    continued = false;
                    try {
                        executor.sendResponse(P2PWrapper.build(message.getSeq(), P2PCommand.STD_ERROR, "pubsub subscribe rejected"));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void cancel(AbstractSendMesageExecutor executor, StreamP2PWrapper message) {
        if (topic != null) {
            PubSubBroker.unsubscribe(topic, message.getSeq(), executor);
        }
    }

    @Override
    public void processStream(AbstractSendMesageExecutor executor, P2PWrapper request) {
    }
}
