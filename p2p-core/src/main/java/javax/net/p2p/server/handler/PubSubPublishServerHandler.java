package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PPubSubMessage;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.pubsub.PubSubBroker;

public class PubSubPublishServerHandler implements P2PCommandHandler<P2PPubSubMessage> {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.PUBSUB_PUBLISH;
    }

    @Override
    public P2PWrapper process(P2PWrapper<P2PPubSubMessage> request) {
        P2PPubSubMessage msg = request.getData();
        if (msg == null || !PubSubBroker.isTopicAllowed(msg.topic)) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "pubsub publish rejected");
        }
        PubSubBroker.publish(msg);
        return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
    }
}
