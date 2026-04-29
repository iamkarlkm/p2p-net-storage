package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.im.model.GroupMemberRequest;
import javax.net.p2p.im.runtime.ImRuntime;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.IMGroupModel;
import javax.net.p2p.model.P2PWrapper;

public class IMGroupJoinHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.IM_GROUP_JOIN;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        if (!(request.getData() instanceof GroupMemberRequest r)) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "GroupMemberRequest required");
        }
        try {
            IMGroupModel g = ImRuntime.joinGroup(r.getUserId(), r.getGroupId());
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, g);
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, String.valueOf(e.getMessage()));
        }
    }
}

