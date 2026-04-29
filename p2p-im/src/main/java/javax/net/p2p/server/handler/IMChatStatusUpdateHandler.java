package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.im.model.ChatMessageAck;
import javax.net.p2p.im.runtime.ImRuntime;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

public class IMChatStatusUpdateHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.IM_CHAT_STATUS_UPDATE;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        if (!(request.getData() instanceof ChatMessageAck ack)) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "ChatMessageAck required");
        }
        if (ack.getMessageId() == null || ack.getMessageId().isEmpty()) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "messageId required");
        }
        ImRuntime.setMsgStatus(ack.getMessageId(), ack.getAckType());
        return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
    }
}

