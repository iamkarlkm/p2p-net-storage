package javax.net.p2p.server.handler;

import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.im.model.GroupListRequest;
import javax.net.p2p.im.runtime.ImRuntime;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.IMGroupModel;
import javax.net.p2p.model.P2PWrapper;

public class IMGroupListHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.IM_GROUP_LIST;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        Object data = request.getData();
        String userId = null;
        if (data instanceof GroupListRequest r) {
            userId = r.getUserId();
        } else if (data instanceof String s) {
            userId = s;
        } else if (data != null) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "GroupListRequest required");
        }
        List<IMGroupModel> items = ImRuntime.listGroupsForUser(userId);
        return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, items);
    }
}
