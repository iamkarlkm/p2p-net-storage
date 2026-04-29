package javax.net.p2p.server.handler;

import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.im.model.ChatHistoryRequest;
import javax.net.p2p.im.runtime.ImRuntime;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.IMChatModel;
import javax.net.p2p.model.P2PWrapper;

public class IMChatHistoryHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.IM_CHAT_HISTORY_REQUEST;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        if (!(request.getData() instanceof ChatHistoryRequest q)) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "ChatHistoryRequest required");
        }
        String userId = q.getUserId();
        String peerId = q.getPeerId();
        if (peerId == null || peerId.isEmpty()) {
            peerId = q.getTargetId();
        }
        if (userId == null || userId.isEmpty() || peerId == null || peerId.isEmpty()) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "userId and peerId required");
        }
        List<IMChatModel> items = ImRuntime.history(userId, peerId, q.getLimit());
        return P2PWrapper.build(request.getSeq(), P2PCommand.IM_CHAT_HISTORY_RESPONSE, items);
    }
}
