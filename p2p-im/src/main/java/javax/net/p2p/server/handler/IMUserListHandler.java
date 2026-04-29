package javax.net.p2p.server.handler;

import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.im.runtime.ImRuntime;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.IMUserModel;
import javax.net.p2p.model.P2PWrapper;

public class IMUserListHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.IM_USER_LIST;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        List<IMUserModel> users = ImRuntime.listUsers();
        return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, users);
    }
}
