package javax.net.p2p.cache.client;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.cache.model.CacheBytesEntry;
import javax.net.p2p.cache.model.CacheBytesRequest;
import javax.net.p2p.cache.model.CacheBytesResponse;
import javax.net.p2p.cache.model.CacheOp;
import javax.net.p2p.cache.model.CacheStringEntry;
import javax.net.p2p.cache.model.CacheStringRequest;
import javax.net.p2p.cache.model.CacheStringResponse;
import javax.net.p2p.cache.model.LockOp;
import javax.net.p2p.cache.model.LockRequest;
import javax.net.p2p.cache.model.LockResponse;
import javax.net.p2p.client.P2PClientQuic;
import javax.net.p2p.model.P2PWrapper;

/**
 * 基于 QUIC 的缓存客户端（对接 p2p-cache 服务端指令）。
 *
 * <p>封装了字符串/二进制 KV 与分布式锁的请求/响应编解码与发送流程。</p>
 */
public final class P2PQuicCacheClient {

    private final P2PClientQuic client;

    public P2PQuicCacheClient(String host, int port, int queueSize) throws Exception {
        this.client = P2PClientQuic.getInstance(P2PClientQuic.class, host, port, queueSize);
    }

    public P2PQuicCacheClient(InetSocketAddress remote, int queueSize) throws Exception {
        this.client = new P2PClientQuic(remote, queueSize);
        client.newSendMesageExecutorToQueue();
    }

    public void shutdown() {
        client.shutdown();
    }

    public String getString(String key) throws Exception {
        CacheStringRequest req = new CacheStringRequest();
        req.setOp(CacheOp.GET);
        req.setKey(key);
        CacheStringResponse resp = callString(req);
        return resp.getValue();
    }

    public void setString(String key, String value, long ttlMillis) throws Exception {
        CacheStringRequest req = new CacheStringRequest();
        req.setOp(CacheOp.SET);
        req.setKey(key);
        req.setValue(value);
        req.setTtlMillis(ttlMillis);
        callString(req);
    }

    public void delString(String key) throws Exception {
        CacheStringRequest req = new CacheStringRequest();
        req.setOp(CacheOp.DEL);
        req.setKey(key);
        callString(req);
    }

    public boolean existsString(String key) throws Exception {
        CacheStringRequest req = new CacheStringRequest();
        req.setOp(CacheOp.EXISTS);
        req.setKey(key);
        CacheStringResponse resp = callString(req);
        return resp.isExists();
    }

    public boolean expireString(String key, long ttlMillis) throws Exception {
        CacheStringRequest req = new CacheStringRequest();
        req.setOp(CacheOp.EXPIRE);
        req.setKey(key);
        req.setTtlMillis(ttlMillis);
        CacheStringResponse resp = callString(req);
        return resp.isExists();
    }

    public long ttlString(String key) throws Exception {
        CacheStringRequest req = new CacheStringRequest();
        req.setOp(CacheOp.TTL);
        req.setKey(key);
        CacheStringResponse resp = callString(req);
        return resp.getTtlMillis();
    }

    public long incrByString(String key, long delta) throws Exception {
        CacheStringRequest req = new CacheStringRequest();
        req.setOp(CacheOp.INCRBY);
        req.setKey(key);
        req.setDelta(delta);
        CacheStringResponse resp = callString(req);
        return resp.getNumber();
    }

    public long decrByString(String key, long delta) throws Exception {
        CacheStringRequest req = new CacheStringRequest();
        req.setOp(CacheOp.DECRBY);
        req.setKey(key);
        req.setDelta(delta);
        CacheStringResponse resp = callString(req);
        return resp.getNumber();
    }

    public List<CacheStringEntry> mgetString(List<String> keys) throws Exception {
        CacheStringRequest req = new CacheStringRequest();
        req.setOp(CacheOp.MGET);
        req.setKeys(keys == null ? null : new ArrayList<>(keys));
        CacheStringResponse resp = callString(req);
        return resp.getEntries();
    }

    public void msetString(List<CacheStringEntry> entries, long ttlMillis) throws Exception {
        CacheStringRequest req = new CacheStringRequest();
        req.setOp(CacheOp.MSET);
        req.setEntries(entries == null ? null : new ArrayList<>(entries));
        req.setTtlMillis(ttlMillis);
        callString(req);
    }

    public byte[] getBytes(String key) throws Exception {
        CacheBytesRequest req = new CacheBytesRequest();
        req.setOp(CacheOp.GET);
        req.setKey(key);
        CacheBytesResponse resp = callBytes(req);
        return resp.getValue();
    }

    public void setBytes(String key, byte[] value, long ttlMillis) throws Exception {
        CacheBytesRequest req = new CacheBytesRequest();
        req.setOp(CacheOp.SET);
        req.setKey(key);
        req.setValue(value);
        req.setTtlMillis(ttlMillis);
        callBytes(req);
    }

    public void delBytes(String key) throws Exception {
        CacheBytesRequest req = new CacheBytesRequest();
        req.setOp(CacheOp.DEL);
        req.setKey(key);
        callBytes(req);
    }

    public boolean existsBytes(String key) throws Exception {
        CacheBytesRequest req = new CacheBytesRequest();
        req.setOp(CacheOp.EXISTS);
        req.setKey(key);
        CacheBytesResponse resp = callBytes(req);
        return resp.isExists();
    }

    public boolean expireBytes(String key, long ttlMillis) throws Exception {
        CacheBytesRequest req = new CacheBytesRequest();
        req.setOp(CacheOp.EXPIRE);
        req.setKey(key);
        req.setTtlMillis(ttlMillis);
        CacheBytesResponse resp = callBytes(req);
        return resp.isExists();
    }

    public long ttlBytes(String key) throws Exception {
        CacheBytesRequest req = new CacheBytesRequest();
        req.setOp(CacheOp.TTL);
        req.setKey(key);
        CacheBytesResponse resp = callBytes(req);
        return resp.getTtlMillis();
    }

    public long incrByBytes(String key, long delta) throws Exception {
        CacheBytesRequest req = new CacheBytesRequest();
        req.setOp(CacheOp.INCRBY);
        req.setKey(key);
        req.setDelta(delta);
        CacheBytesResponse resp = callBytes(req);
        return resp.getNumber();
    }

    public long decrByBytes(String key, long delta) throws Exception {
        CacheBytesRequest req = new CacheBytesRequest();
        req.setOp(CacheOp.DECRBY);
        req.setKey(key);
        req.setDelta(delta);
        CacheBytesResponse resp = callBytes(req);
        return resp.getNumber();
    }

    public List<CacheBytesEntry> mgetBytes(List<String> keys) throws Exception {
        CacheBytesRequest req = new CacheBytesRequest();
        req.setOp(CacheOp.MGET);
        req.setKeys(keys == null ? null : new ArrayList<>(keys));
        CacheBytesResponse resp = callBytes(req);
        return resp.getEntries();
    }

    public void msetBytes(List<CacheBytesEntry> entries, long ttlMillis) throws Exception {
        CacheBytesRequest req = new CacheBytesRequest();
        req.setOp(CacheOp.MSET);
        req.setEntries(entries == null ? null : new ArrayList<>(entries));
        req.setTtlMillis(ttlMillis);
        callBytes(req);
    }

    public String tryLock(String key, long ttlMillis) throws Exception {
        String token = UUID.randomUUID().toString();
        LockRequest req = new LockRequest();
        req.setOp(LockOp.ACQUIRE);
        req.setKey(key);
        req.setToken(token);
        req.setTtlMillis(ttlMillis);
        LockResponse resp = callLock(req);
        return resp.isSuccess() ? token : null;
    }

    public String lock(String key, long ttlMillis, long waitMillis, long retryIntervalMillis) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(0, waitMillis);
        long sleep = Math.max(1, retryIntervalMillis);
        while (true) {
            String token = tryLock(key, ttlMillis);
            if (token != null) {
                return token;
            }
            if (waitMillis <= 0 || System.currentTimeMillis() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                return null;
            }
        }
    }

    public boolean unlock(String key, String token) throws Exception {
        LockRequest req = new LockRequest();
        req.setOp(LockOp.RELEASE);
        req.setKey(key);
        req.setToken(token);
        LockResponse resp = callLock(req);
        return resp.isSuccess();
    }

    public boolean renewLock(String key, String token, long ttlMillis) throws Exception {
        LockRequest req = new LockRequest();
        req.setOp(LockOp.RENEW);
        req.setKey(key);
        req.setToken(token);
        req.setTtlMillis(ttlMillis);
        LockResponse resp = callLock(req);
        return resp.isSuccess();
    }

    private CacheStringResponse callString(CacheStringRequest req) throws Exception {
        P2PWrapper wrapper = P2PWrapper.build(P2PCommand.CACHE_STRING_COMMAND, req);
        P2PWrapper resp = client.excute(wrapper, 30, TimeUnit.SECONDS);
        Object data = resp.getData();
        if (!(data instanceof CacheStringResponse)) {
            throw new IllegalStateException("invalid response type");
        }
        CacheStringResponse r = (CacheStringResponse) data;
        if (!r.isOk()) {
            throw new IllegalStateException(r.getError());
        }
        return r;
    }

    private CacheBytesResponse callBytes(CacheBytesRequest req) throws Exception {
        P2PWrapper wrapper = P2PWrapper.build(P2PCommand.CACHE_BYTES_COMMAND, req);
        P2PWrapper resp = client.excute(wrapper, 30, TimeUnit.SECONDS);
        Object data = resp.getData();
        if (!(data instanceof CacheBytesResponse)) {
            throw new IllegalStateException("invalid response type");
        }
        CacheBytesResponse r = (CacheBytesResponse) data;
        if (!r.isOk()) {
            throw new IllegalStateException(r.getError());
        }
        return r;
    }

    private LockResponse callLock(LockRequest req) throws Exception {
        P2PWrapper wrapper = P2PWrapper.build(P2PCommand.CACHE_LOCK_COMMAND, req);
        P2PWrapper resp = client.excute(wrapper, 30, TimeUnit.SECONDS);
        Object data = resp.getData();
        if (!(data instanceof LockResponse)) {
            throw new IllegalStateException("invalid response type");
        }
        LockResponse r = (LockResponse) data;
        if (!r.isOk()) {
            throw new IllegalStateException(r.getError());
        }
        return r;
    }
}
