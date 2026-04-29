package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.im.model.GroupSetAdminRequest;
import javax.net.p2p.im.runtime.ImRuntime;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.IMGroupModel;
import javax.net.p2p.model.P2PWrapper;

public class IMGroupSetAdminHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.IM_GROUP_SET_ADMIN;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        if (!(request.getData() instanceof GroupSetAdminRequest r)) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "GroupSetAdminRequest required");
        }
        try {
            IMGroupModel g = ImRuntime.setAdmin(r.getOperatorId(), r.getGroupId(), r.getMemberId(), r.isAdmin());
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, g);
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, String.valueOf(e.getMessage()));
        }
    }
}

