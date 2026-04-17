package javax.net.p2p.server.handler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.cache.model.CacheBytesEntry;
import javax.net.p2p.cache.model.CacheBytesRequest;
import javax.net.p2p.cache.model.CacheBytesResponse;
import javax.net.p2p.cache.model.CacheOp;
import javax.net.p2p.cache.server.CacheBackend;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

/**
 * CACHE_BYTES_COMMAND 服务端处理器：处理 bytes KV 的 get/set/del/exists/ttl/incr 等操作。
 */
public class CacheBytesCommandServerHandler implements P2PCommandHandler {

    public CacheBytesCommandServerHandler() {
    }

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.CACHE_BYTES_COMMAND;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        CacheBytesResponse resp = new CacheBytesResponse();
        try {
            Object data = request.getData();
            if (!(data instanceof CacheBytesRequest)) {
                resp.setOk(false);
                resp.setError("invalid request type");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }
            CacheBytesRequest req = (CacheBytesRequest) data;
            CacheOp op = req.getOp();
            if (op == null) {
                resp.setOk(false);
                resp.setError("missing op");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }

            switch (op) {
                case GET -> {
                    byte[] v = CacheBackend.bytes().get(req.getKey());
                    resp.setOk(true);
                    resp.setValue(v);
                }
                case SET -> {
                    CacheBackend.bytes().set(req.getKey(), req.getValue(), req.getTtlMillis());
                    resp.setOk(true);
                }
                case DEL -> {
                    CacheBackend.bytes().del(req.getKey());
                    resp.setOk(true);
                }
                case EXISTS -> {
                    boolean exists = CacheBackend.bytes().exists(req.getKey());
                    resp.setOk(true);
                    resp.setExists(exists);
                }
                case EXPIRE -> {
                    boolean ok = CacheBackend.bytes().expire(req.getKey(), req.getTtlMillis());
                    resp.setOk(true);
                    resp.setExists(ok);
                }
                case TTL -> {
                    long ttl = CacheBackend.bytes().ttlMillis(req.getKey());
                    resp.setOk(true);
                    resp.setTtlMillis(ttl);
                }
                case INCRBY -> {
                    long n = CacheBackend.bytes().incrBy(req.getKey(), req.getDelta());
                    resp.setOk(true);
                    resp.setNumber(n);
                }
                case DECRBY -> {
                    long n = CacheBackend.bytes().decrBy(req.getKey(), req.getDelta());
                    resp.setOk(true);
                    resp.setNumber(n);
                }
                case MGET -> {
                    List<String> keys = req.getKeys();
                    List<CacheBytesEntry> entries = new ArrayList<>();
                    if (keys != null) {
                        for (String k : keys) {
                            entries.add(new CacheBytesEntry(k, CacheBackend.bytes().get(k)));
                        }
                    }
                    resp.setOk(true);
                    resp.setEntries(entries);
                }
                case MSET -> {
                    List<CacheBytesEntry> entries = req.getEntries();
                    if (entries != null) {
                        for (CacheBytesEntry e : entries) {
                            CacheBackend.bytes().set(e.getKey(), e.getValue(), req.getTtlMillis());
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
