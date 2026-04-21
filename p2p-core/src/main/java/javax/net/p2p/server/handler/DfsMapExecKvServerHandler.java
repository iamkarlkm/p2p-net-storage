package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.dfsmap.DfsMapBackend;
import javax.net.p2p.dfsmap.DfsMapRegistry;
import javax.net.p2p.dfsmap.model.DfsMapExecKvReq;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

public class DfsMapExecKvServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DFS_MAP_INT_EXEC_KV;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        DfsMapBackend backend = DfsMapRegistry.getBackend();
        if (backend == null) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "DFS_MAP backend not registered");
        }
        if (!(request.getData() instanceof DfsMapExecKvReq req)) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "invalid DFS_MAP_INT_EXEC_KV payload");
        }
        return P2PWrapper.build(request.getSeq(), request.getCommand(), backend.handleExecKv(req));
    }
}
