package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.im.model.GroupIdRequest;
import javax.net.p2p.im.runtime.ImRuntime;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.IMGroupModel;
import javax.net.p2p.model.P2PWrapper;

public class IMGroupMembersHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.IM_GROUP_MEMBERS;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        if (!(request.getData() instanceof GroupIdRequest r)) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "GroupIdRequest required");
        }
        IMGroupModel g = ImRuntime.getGroupWithMembers(r.getGroupId());
        if (g == null) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "group not found");
        }
        return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, g);
    }
}

