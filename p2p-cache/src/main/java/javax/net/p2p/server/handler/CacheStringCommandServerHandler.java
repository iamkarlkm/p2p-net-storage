package javax.net.p2p.server.handler;

import java.util.ArrayList;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.cache.model.CacheOp;
import javax.net.p2p.cache.model.CacheStringEntry;
import javax.net.p2p.cache.model.CacheStringRequest;
import javax.net.p2p.cache.model.CacheStringResponse;
import javax.net.p2p.cache.server.CacheBackend;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

/**
 * CACHE_STRING_COMMAND 服务端处理器：处理 String KV 的 get/set/del/exists/ttl/incr 等操作。
 */
public class CacheStringCommandServerHandler implements P2PCommandHandler {

    public CacheStringCommandServerHandler() {
    }

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.CACHE_STRING_COMMAND;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        CacheStringResponse resp = new CacheStringResponse();
        try {
            Object data = request.getData();
            if (!(data instanceof CacheStringRequest)) {
                resp.setOk(false);
                resp.setError("invalid request type");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }
            CacheStringRequest req = (CacheStringRequest) data;
            CacheOp op = req.getOp();
            if (op == null) {
                resp.setOk(false);
                resp.setError("missing op");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }

            switch (op) {
                case GET -> {
                    String v = CacheBackend.strings().get(req.getKey());
                    resp.setOk(true);
                    resp.setValue(v);
                }
                case SET -> {
                    CacheBackend.strings().set(req.getKey(), req.getValue(), req.getTtlMillis());
                    resp.setOk(true);
                }
                case DEL -> {
                    CacheBackend.strings().del(req.getKey());
                    resp.setOk(true);
                }
                case EXISTS -> {
                    boolean exists = CacheBackend.strings().exists(req.getKey());
                    resp.setOk(true);
                    resp.setExists(exists);
                }
                case EXPIRE -> {
                    boolean ok = CacheBackend.strings().expire(req.getKey(), req.getTtlMillis());
                    resp.setOk(true);
                    resp.setExists(ok);
                }
                case TTL -> {
                    long ttl = CacheBackend.strings().ttlMillis(req.getKey());
                    resp.setOk(true);
                    resp.setTtlMillis(ttl);
                }
                case INCRBY -> {
                    long n = CacheBackend.strings().incrBy(req.getKey(), req.getDelta());
                    resp.setOk(true);
                    resp.setNumber(n);
                }
                case DECRBY -> {
                    long n = CacheBackend.strings().decrBy(req.getKey(), req.getDelta());
                    resp.setOk(true);
                    resp.setNumber(n);
                }
                case MGET -> {
                    List<String> keys = req.getKeys();
                    List<CacheStringEntry> entries = new ArrayList<>();
                    if (keys != null) {
                        for (String k : keys) {
                            entries.add(new CacheStringEntry(k, CacheBackend.strings().get(k)));
                        }
                    }
                    resp.setOk(true);
                    resp.setEntries(entries);
                }
                case MSET -> {
                    List<CacheStringEntry> entries = req.getEntries();
                    if (entries != null) {
                        for (CacheStringEntry e : entries) {
                            CacheBackend.strings().set(e.getKey(), e.getValue(), req.getTtlMillis());
                        }
                    }
                    resp.setOk(true);
                }
                default -> {
                    resp.setOk(false);
                    resp.setError("unsupported op: " + op);
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_UNKNOWN, resp);
                }
            }
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, resp);
        } catch (Exception e) {
            resp.setOk(false);
            resp.setError(e.getMessage());
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
        }
    }
}
