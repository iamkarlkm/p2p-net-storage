package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.im.runtime.ImRuntime;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.IMUserModel;
import javax.net.p2p.model.P2PWrapper;

public class IMUserLogoutHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.IM_USER_LOGOUT;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        Object data = request.getData();
        String userId = null;
        if (data instanceof IMUserModel u) {
            userId = u.getUserId();
        } else if (data instanceof String s) {
            userId = s;
        }
        if (userId == null || userId.isEmpty()) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "userId required");
        }
        ImRuntime.logout(userId);
        return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
    }
}
