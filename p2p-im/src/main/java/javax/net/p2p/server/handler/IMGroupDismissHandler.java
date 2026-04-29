package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.im.model.GroupDismissRequest;
import javax.net.p2p.im.runtime.ImRuntime;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

public class IMGroupDismissHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.IM_GROUP_DISMISS;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        if (!(request.getData() instanceof GroupDismissRequest r)) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "GroupDismissRequest required");
        }
        try {
            ImRuntime.dismissGroup(r.getOperatorId(), r.getGroupId());
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, String.valueOf(e.getMessage()));
        }
    }
}

