package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.im.model.GroupUpdateInfoRequest;
import javax.net.p2p.im.runtime.ImRuntime;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.IMGroupModel;
import javax.net.p2p.model.P2PWrapper;

public class IMGroupUpdateInfoHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.IM_GROUP_UPDATE_INFO;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        if (!(request.getData() instanceof GroupUpdateInfoRequest r)) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "GroupUpdateInfoRequest required");
        }
        try {
            IMGroupModel patch = new IMGroupModel();
            patch.setGroupId(r.getGroupId());
            patch.setName(r.getName());
            patch.setAnnouncement(r.getAnnouncement());
            patch.setAvatar(r.getAvatar());
            IMGroupModel out = ImRuntime.updateGroupInfo(r.getOperatorId(), patch);
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, out);
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, String.valueOf(e.getMessage()));
        }
    }
}

