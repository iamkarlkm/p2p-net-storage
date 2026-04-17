package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.cache.model.LockOp;
import javax.net.p2p.cache.model.LockRequest;
import javax.net.p2p.cache.model.LockResponse;
import javax.net.p2p.cache.server.CacheBackend;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

/**
 * CACHE_LOCK_COMMAND 服务端处理器：提供基于 key 的互斥锁 acquire/release/renew。
 */
public class CacheLockCommandServerHandler implements P2PCommandHandler {

    public CacheLockCommandServerHandler() {
    }

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.CACHE_LOCK_COMMAND;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        LockResponse resp = new LockResponse();
        try {
            Object data = request.getData();
            if (!(data instanceof LockRequest)) {
                resp.setOk(false);
                resp.setError("invalid request type");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }
            LockRequest req = (LockRequest) data;
            if (req.getOp() == null) {
                resp.setOk(false);
                resp.setError("missing op");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }
            if (req.getKey() == null || req.getKey().isEmpty()) {
                resp.setOk(false);
                resp.setError("missing key");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }

            LockOp op = req.getOp();
            boolean success;
            switch (op) {
                case ACQUIRE -> {
                    success = CacheBackend.strings().tryLock(req.getKey(), req.getToken(), req.getTtlMillis());
                }
                case RELEASE -> {
                    success = CacheBackend.strings().unlock(req.getKey(), req.getToken());
                }
                case RENEW -> {
                    success = CacheBackend.strings().renewLock(req.getKey(), req.getToken(), req.getTtlMillis());
                }
                default -> {
                    resp.setOk(false);
                    resp.setError("unsupported op: " + op);
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_UNKNOWN, resp);
                }
            }
            resp.setOk(true);
            resp.setSuccess(success);
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, resp);
        } catch (Exception e) {
            resp.setOk(false);
            resp.setError(e.getMessage());
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
        }
    }
}
