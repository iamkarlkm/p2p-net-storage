package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.im.runtime.ImRuntime;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.IMChatModel;
import javax.net.p2p.model.P2PWrapper;

public class IMGroupMessageSendHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.IM_GROUP_MESSAGE_SEND;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        if (!(request.getData() instanceof IMChatModel msg)) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "IMChatModel required");
        }
        try {
            ImRuntime.normalizeGroupChat(msg);
            ImRuntime.appendGroupChat(msg);
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, msg.getMsgId());
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, String.valueOf(e.getMessage()));
        }
    }
}

