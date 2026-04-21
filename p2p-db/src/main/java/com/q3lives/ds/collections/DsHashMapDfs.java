package com.q3lives.ds.collections;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.P2PClientQuic;
import javax.net.p2p.dfsmap.DfsMapBackend;
import javax.net.p2p.dfsmap.DfsMapRegistry;
import javax.net.p2p.dfsmap.model.DfsMapExecKvReq;
import javax.net.p2p.dfsmap.model.DfsMapExecKvResp;
import javax.net.p2p.dfsmap.model.DfsMapGetReq;
import javax.net.p2p.dfsmap.model.DfsMapGetResp;
import javax.net.p2p.dfsmap.model.DfsMapOp;
import javax.net.p2p.dfsmap.model.DfsMapPingReq;
import javax.net.p2p.dfsmap.model.DfsMapPingResp;
import javax.net.p2p.dfsmap.model.DfsMapPutReq;
import javax.net.p2p.dfsmap.model.DfsMapPutResp;
import javax.net.p2p.dfsmap.model.DfsMapRangeLocalReq;
import javax.net.p2p.dfsmap.model.DfsMapRangeLocalResp;
import javax.net.p2p.dfsmap.model.DfsMapRangeReq;
import javax.net.p2p.dfsmap.model.DfsMapRangeResp;
import javax.net.p2p.dfsmap.model.DfsMapRemoveReq;
import javax.net.p2p.dfsmap.model.DfsMapRemoveResp;
import javax.net.p2p.dfsmap.model.DfsMapStatusCodes;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.XXHashUtil;

public class DsHashMapDfs implements DfsMapBackend {
    public record Endpoint(String host, int port, int queueSize) {
    }

    public interface ServerDirectory {
        int[] listServerIds();

        Endpoint endpoint(int serverId);
    }

    private static final int API_VERSION = 1;
    private static final int SERVER_ID_BITS = 12;
    private static final int SERVER_ID_MASK = (1 << SERVER_ID_BITS) - 1;
    private static final long SERVER_HASH_SHIFT = 64L - SERVER_ID_BITS;
    private static final int SIGN_BIT_SERVER_ID = 1 << (SERVER_ID_BITS - 1);

    private static final long HASH_PROVIDER_ID = 9001L;
    private static final int DEFAULT_QUEUE_SIZE = 4096;

    private final int serverId;
    private final File baseDir;
    private final ServerDirectory directory;
    private final boolean tablesEnabled;

    private final Object tableInitLock = new Object();
    private volatile DsHashMapV2 singleTable;
    private volatile DsHashMapV2[] shardedTables;
    private final P2PClientQuic[] clients = new P2PClientQuic[1 << SERVER_ID_BITS];

    private final ThreadLocal<byte[]> hashInput = ThreadLocal.withInitial(() -> new byte[8]);

    public DsHashMapDfs(File baseDir, int serverId, ServerDirectory directory) {
        this(baseDir, serverId, directory, false);
    }

    public DsHashMapDfs(File baseDir, int serverId, ServerDirectory directory, boolean tablesEnabled) {
        if (baseDir == null) {
            throw new NullPointerException("baseDir");
        }
        if (directory == null) {
            throw new NullPointerException("directory");
        }
        if (serverId < 0 || serverId > SERVER_ID_MASK) {
            throw new IllegalArgumentException("serverId must be in [0," + SERVER_ID_MASK + "]");
        }
        this.baseDir = baseDir;
        this.serverId = serverId;
        this.directory = directory;
        this.tablesEnabled = tablesEnabled;
        DfsMapRegistry.setBackend(this);
    }

    @Override
    public DfsMapGetResp handleGet(DfsMapGetReq req) {
        if (req == null) {
            DfsMapGetResp resp = new DfsMapGetResp();
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            return resp;
        }
        long key = req.getKey();
        int owner = ownerServerId(key);
        if (owner == serverId) {
            return getLocal(key);
        }
        DfsMapExecKvResp inner = execRemote(owner, buildExecReq(DfsMapOp.GET, key, 0L, false));
        return mapGetFromInner(key, inner);
    }

    @Override
    public DfsMapPutResp handlePut(DfsMapPutReq req) {
        if (req == null) {
            DfsMapPutResp resp = new DfsMapPutResp();
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            return resp;
        }
        long key = req.getKey();
        long value = req.getValue();
        boolean returnOld = req.isReturnOldValue();
        int owner = ownerServerId(key);
        if (owner == serverId) {
            return putLocal(key, value, returnOld);
        }
        DfsMapExecKvResp inner = execRemote(owner, buildExecReq(DfsMapOp.PUT, key, value, returnOld));
        return mapPutFromInner(key, returnOld, inner);
    }

    @Override
    public DfsMapRemoveResp handleRemove(DfsMapRemoveReq req) {
        if (req == null) {
            DfsMapRemoveResp resp = new DfsMapRemoveResp();
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            return resp;
        }
        long key = req.getKey();
        boolean returnOld = req.isReturnOldValue();
        int owner = ownerServerId(key);
        if (owner == serverId) {
            return removeLocal(key, returnOld);
        }
        DfsMapExecKvResp inner = execRemote(owner, buildExecReq(DfsMapOp.REMOVE, key, 0L, returnOld));
        return mapRemoveFromInner(key, returnOld, inner);
    }

    @Override
    public DfsMapRangeResp handleRange(DfsMapRangeReq req) {
        DfsMapRangeResp resp = new DfsMapRangeResp();
        if (req == null) {
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            return resp;
        }
        if (tablesEnabled) {
            resp.setStatus(DfsMapStatusCodes.RETRY);
            resp.setRequestedCount(req.getCount());
            resp.setStart(req.getStart());
            return resp;
        }
        long start = req.getStart();
        int count = req.getCount();
        resp.setStart(start);
        resp.setRequestedCount(count);
        if (start < 0 || count <= 0) {
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            return resp;
        }

        int[] servers = orderedServers(directory.listServerIds());
        long remainingSkip = start;
        int remaining = count;
        long[] keys = new long[count];
        long[] values = req.isKeysOnly() ? null : new long[count];
        int emitted = 0;

        for (int sid : servers) {
            if (remaining <= 0) {
                break;
            }
            long size = pingSize(sid);
            if (size < 0) {
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setEmitted(emitted);
                resp.setKeys(trim(keys, emitted));
                resp.setValues(values == null ? null : trim(values, emitted));
                return resp;
            }
            if (remainingSkip >= size) {
                remainingSkip -= size;
                continue;
            }
            long localCursor = remainingSkip;
            remainingSkip = 0;

            DfsMapRangeLocalResp local = rangeLocalFromServer(sid, localCursor, remaining, req.isKeysOnly());
            if (local.getStatus() != DfsMapStatusCodes.OK) {
                resp.setStatus(local.getStatus());
                resp.setEmitted(emitted);
                resp.setKeys(trim(keys, emitted));
                resp.setValues(values == null ? null : trim(values, emitted));
                return resp;
            }
            long[] localKeys = local.getKeys();
            long[] localValues = local.getValues();
            int got = local.getEmitted();
            for (int i = 0; i < got && emitted < count; i++) {
                keys[emitted] = localKeys[i];
                if (values != null) {
                    values[emitted] = localValues[i];
                }
                emitted++;
                remaining--;
            }
        }

        resp.setStatus(DfsMapStatusCodes.OK);
        resp.setEmitted(emitted);
        resp.setKeys(trim(keys, emitted));
        resp.setValues(values == null ? null : trim(values, emitted));
        return resp;
    }

    @Override
    public DfsMapExecKvResp handleExecKv(DfsMapExecKvReq req) {
        DfsMapExecKvResp resp = new DfsMapExecKvResp();
        if (req == null || req.getOp() == null) {
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            return resp;
        }
        long key = req.getKey();
        int expectedOwner = ownerServerId(key);
        if (expectedOwner != serverId) {
            resp.setStatus(DfsMapStatusCodes.NOT_OWNER);
            resp.setRedirectServerId(expectedOwner);
            return resp;
        }
        int tableId = tablesEnabled ? (req.getTableId() & 0xFF) : 0;
        DsHashMapV2 map = localTable(tableId);
        try {
            if (req.getOp() == DfsMapOp.GET) {
                Long value = map.get(key);
                if (value == null) {
                    resp.setStatus(DfsMapStatusCodes.NOT_FOUND);
                    resp.setAffected(false);
                    return resp;
                }
                resp.setStatus(DfsMapStatusCodes.OK);
                resp.setAffected(true);
                resp.setValueOrOldValue(value.longValue());
                return resp;
            }
            if (req.getOp() == DfsMapOp.PUT) {
                Long old = map.put(key, req.getValue());
                resp.setStatus(DfsMapStatusCodes.OK);
                resp.setAffected(old != null);
                if (req.isReturnOldValue() && old != null) {
                    resp.setValueOrOldValue(old.longValue());
                }
                return resp;
            }
            if (req.getOp() == DfsMapOp.REMOVE) {
                Long old = map.remove(key);
                resp.setStatus(old == null ? DfsMapStatusCodes.NOT_FOUND : DfsMapStatusCodes.OK);
                resp.setAffected(old != null);
                if (req.isReturnOldValue() && old != null) {
                    resp.setValueOrOldValue(old.longValue());
                }
                return resp;
            }
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            return resp;
        } catch (IOException ex) {
            resp.setStatus(DfsMapStatusCodes.ERROR);
            return resp;
        }
    }

    @Override
    public DfsMapRangeLocalResp handleRangeLocal(DfsMapRangeLocalReq req) {
        DfsMapRangeLocalResp resp = new DfsMapRangeLocalResp();
        if (req == null) {
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            return resp;
        }
        if (req.getOwnerServerId() != serverId) {
            resp.setStatus(DfsMapStatusCodes.NOT_OWNER);
            resp.setRedirectServerId(req.getOwnerServerId());
            return resp;
        }
        if (req.getCursor() < 0 || req.getLimit() <= 0) {
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            return resp;
        }
        int tableId = tablesEnabled ? (req.getTableId() & 0xFF) : 0;
        DsHashMapV2 map = localTable(tableId);
        try {
            var entries = map.rangeEntries(req.getCursor(), req.getLimit());
            int emitted = entries.size();
            long[] keys = new long[emitted];
            long[] values = req.isKeysOnly() ? null : new long[emitted];
            for (int i = 0; i < emitted; i++) {
                var e = entries.get(i);
                keys[i] = e.getKey();
                if (values != null) {
                    values[i] = e.getValue();
                }
            }
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setEmitted(emitted);
            resp.setNextCursor(req.getCursor() + emitted);
            resp.setKeys(keys);
            resp.setValues(values);
            return resp;
        } catch (RuntimeException ex) {
            resp.setStatus(DfsMapStatusCodes.ERROR);
            return resp;
        }
    }

    @Override
    public DfsMapPingResp handlePing(DfsMapPingReq req) {
        DfsMapPingResp resp = new DfsMapPingResp();
        resp.setStatus(DfsMapStatusCodes.OK);
        resp.setServerId(serverId);
        resp.setTablesEnabled(tablesEnabled);
        if (tablesEnabled) {
            resp.setTotalSize(-1L);
            return resp;
        }
        DsHashMapV2 map = localTable(0);
        resp.setTotalSize(map.sizeLong());
        return resp;
    }

    private DfsMapGetResp getLocal(long key) {
        DfsMapGetResp resp = new DfsMapGetResp();
        resp.setKey(key);
        DsHashMapV2 map = localTable(0);
        try {
            Long value = map.get(key);
            if (value == null) {
                resp.setStatus(DfsMapStatusCodes.NOT_FOUND);
                resp.setFound(false);
                return resp;
            }
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setFound(true);
            resp.setValue(value.longValue());
            return resp;
        } catch (IOException ex) {
            resp.setStatus(DfsMapStatusCodes.ERROR);
            return resp;
        }
    }

    private DfsMapPutResp putLocal(long key, long value, boolean returnOld) {
        DfsMapPutResp resp = new DfsMapPutResp();
        resp.setKey(key);
        DsHashMapV2 map = localTable(0);
        try {
            Long old = map.put(key, value);
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setHadOld(old != null);
            if (returnOld && old != null) {
                resp.setOldValue(old.longValue());
            }
            return resp;
        } catch (IOException | RuntimeException ex) {
            resp.setStatus(DfsMapStatusCodes.ERROR);
            return resp;
        }
    }

    private DfsMapRemoveResp removeLocal(long key, boolean returnOld) {
        DfsMapRemoveResp resp = new DfsMapRemoveResp();
        resp.setKey(key);
        DsHashMapV2 map = localTable(0);
        try {
            Long old = map.remove(key);
            resp.setRemoved(old != null);
            resp.setStatus(old == null ? DfsMapStatusCodes.NOT_FOUND : DfsMapStatusCodes.OK);
            if (returnOld && old != null) {
                resp.setOldValue(old.longValue());
            }
            return resp;
        } catch (IOException | RuntimeException ex) {
            resp.setStatus(DfsMapStatusCodes.ERROR);
            return resp;
        }
    }

    private static DfsMapGetResp mapGetFromInner(long key, DfsMapExecKvResp inner) {
        DfsMapGetResp resp = new DfsMapGetResp();
        resp.setKey(key);
        if (inner == null) {
            resp.setStatus(DfsMapStatusCodes.RETRY);
            return resp;
        }
        if (inner.getStatus() == DfsMapStatusCodes.OK) {
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setFound(inner.isAffected());
            resp.setValue(inner.getValueOrOldValue());
            return resp;
        }
        resp.setStatus(inner.getStatus());
        resp.setFound(false);
        return resp;
    }

    private static DfsMapPutResp mapPutFromInner(long key, boolean returnOld, DfsMapExecKvResp inner) {
        DfsMapPutResp resp = new DfsMapPutResp();
        resp.setKey(key);
        if (inner == null) {
            resp.setStatus(DfsMapStatusCodes.RETRY);
            return resp;
        }
        resp.setStatus(inner.getStatus());
        resp.setHadOld(inner.isAffected());
        if (returnOld && inner.isAffected()) {
            resp.setOldValue(inner.getValueOrOldValue());
        }
        return resp;
    }

    private static DfsMapRemoveResp mapRemoveFromInner(long key, boolean returnOld, DfsMapExecKvResp inner) {
        DfsMapRemoveResp resp = new DfsMapRemoveResp();
        resp.setKey(key);
        if (inner == null) {
            resp.setStatus(DfsMapStatusCodes.RETRY);
            return resp;
        }
        resp.setStatus(inner.getStatus());
        resp.setRemoved(inner.isAffected());
        if (returnOld && inner.isAffected()) {
            resp.setOldValue(inner.getValueOrOldValue());
        }
        return resp;
    }

    private DfsMapExecKvReq buildExecReq(DfsMapOp op, long key, long value, boolean returnOldValue) {
        DfsMapExecKvReq req = new DfsMapExecKvReq();
        req.setApiVersion(API_VERSION);
        req.setEpoch(0L);
        req.setOp(op);
        req.setKey(key);
        req.setValue(value);
        req.setReturnOldValue(returnOldValue);
        req.setOwnerServerId(ownerServerId(key));
        req.setTableId(tablesEnabled ? tableId(key) : 0);
        return req;
    }

    private DfsMapExecKvResp execRemote(int ownerServerId, DfsMapExecKvReq req) {
        int currentOwner = ownerServerId;
        for (int attempt = 0; attempt < 2; attempt++) {
            DfsMapExecKvResp resp = execRemoteOnce(currentOwner, req);
            if (resp == null) {
                return null;
            }
            if (resp.getStatus() != DfsMapStatusCodes.NOT_OWNER) {
                return resp;
            }
            int redirect = resp.getRedirectServerId();
            if (redirect < 0 || redirect > SERVER_ID_MASK || redirect == currentOwner) {
                return resp;
            }
            currentOwner = redirect;
        }
        return execRemoteOnce(currentOwner, req);
    }

    private DfsMapExecKvResp execRemoteOnce(int ownerServerId, DfsMapExecKvReq req) {
        P2PClientQuic client = clientFor(ownerServerId);
        if (client == null) {
            return null;
        }
        P2PWrapper request = P2PWrapper.build(P2PCommand.DFS_MAP_INT_EXEC_KV, req);
        try {
            P2PWrapper response = client.excute(request, 15, TimeUnit.SECONDS);
            if (!(response.getData() instanceof DfsMapExecKvResp resp)) {
                return null;
            }
            return resp;
        } catch (Exception ex) {
            return null;
        }
    }

    private long pingSize(int serverId) {
        if (serverId == this.serverId) {
            return localTable(0).sizeLong();
        }
        P2PClientQuic client = clientFor(serverId);
        if (client == null) {
            return -1L;
        }
        DfsMapPingReq req = new DfsMapPingReq();
        req.setApiVersion(API_VERSION);
        req.setEpoch(0L);
        P2PWrapper request = P2PWrapper.build(P2PCommand.DFS_MAP_INT_PING, req);
        try {
            P2PWrapper response = client.excute(request, 10, TimeUnit.SECONDS);
            if (!(response.getData() instanceof DfsMapPingResp resp)) {
                return -1L;
            }
            if (resp.getStatus() != DfsMapStatusCodes.OK) {
                return -1L;
            }
            return resp.getTotalSize();
        } catch (Exception ex) {
            return -1L;
        }
    }

    private DfsMapRangeLocalResp rangeLocalFromServer(int serverId, long cursor, int limit, boolean keysOnly) {
        if (serverId == this.serverId) {
            DfsMapRangeLocalReq req = new DfsMapRangeLocalReq();
            req.setApiVersion(API_VERSION);
            req.setEpoch(0L);
            req.setOwnerServerId(serverId);
            req.setTableId(0);
            req.setCursor(cursor);
            req.setLimit(limit);
            req.setKeysOnly(keysOnly);
            return handleRangeLocal(req);
        }
        P2PClientQuic client = clientFor(serverId);
        if (client == null) {
            DfsMapRangeLocalResp resp = new DfsMapRangeLocalResp();
            resp.setStatus(DfsMapStatusCodes.RETRY);
            return resp;
        }
        DfsMapRangeLocalReq req = new DfsMapRangeLocalReq();
        req.setApiVersion(API_VERSION);
        req.setEpoch(0L);
        req.setOwnerServerId(serverId);
        req.setTableId(0);
        req.setCursor(cursor);
        req.setLimit(limit);
        req.setKeysOnly(keysOnly);
        P2PWrapper request = P2PWrapper.build(P2PCommand.DFS_MAP_INT_RANGE_LOCAL, req);
        try {
            P2PWrapper response = client.excute(request, 30, TimeUnit.SECONDS);
            if (!(response.getData() instanceof DfsMapRangeLocalResp resp)) {
                DfsMapRangeLocalResp fallback = new DfsMapRangeLocalResp();
                fallback.setStatus(DfsMapStatusCodes.ERROR);
                return fallback;
            }
            return resp;
        } catch (Exception ex) {
            DfsMapRangeLocalResp fallback = new DfsMapRangeLocalResp();
            fallback.setStatus(DfsMapStatusCodes.RETRY);
            return fallback;
        }
    }

    private P2PClientQuic clientFor(int serverId) {
        Endpoint endpoint = directory.endpoint(serverId);
        if (endpoint == null) {
            return null;
        }
        P2PClientQuic existing = clients[serverId];
        if (existing != null) {
            return existing;
        }
        synchronized (clients) {
            P2PClientQuic current = clients[serverId];
            if (current != null) {
                return current;
            }
            try {
                int queueSize = endpoint.queueSize() <= 0 ? DEFAULT_QUEUE_SIZE : endpoint.queueSize();
                P2PClientQuic created = P2PClientQuic.getInstance(P2PClientQuic.class, endpoint.host(), endpoint.port(), queueSize);
                clients[serverId] = created;
                return created;
            } catch (UnknownHostException ex) {
                return null;
            }
        }
    }

    private DsHashMapV2 localTable(int tableId) {
        if (!tablesEnabled) {
            DsHashMapV2 map = singleTable;
            if (map != null) {
                return map;
            }
            synchronized (tableInitLock) {
                DsHashMapV2 current = singleTable;
                if (current != null) {
                    return current;
                }
                File file = new File(baseDir, "dfs-map.dat");
                singleTable = new DsHashMapV2(file, this::fillHashBytesFromHash64, HASH_PROVIDER_ID);
                return singleTable;
            }
        }

        DsHashMapV2[] tables = shardedTables;
        if (tables == null) {
            synchronized (tableInitLock) {
                if (shardedTables == null) {
                    shardedTables = new DsHashMapV2[256];
                }
                tables = shardedTables;
            }
        }

        int tid = tableId & 0xFF;
        DsHashMapV2 map = tables[tid];
        if (map != null) {
            return map;
        }
        synchronized (tableInitLock) {
            DsHashMapV2 current = tables[tid];
            if (current != null) {
                return current;
            }
            File file = new File(baseDir, "dfs-map-t" + tid + ".dat");
            tables[tid] = new DsHashMapV2(file, this::fillHashBytesFromHash64, HASH_PROVIDER_ID);
            return tables[tid];
        }
    }

    private void fillHashBytesFromHash64(long key, byte[] out) {
        Arrays.fill(out, (byte) 0);
        long hash64 = hash64(key);
        out[0] = (byte) (hash64 >>> 56);
        out[1] = (byte) (hash64 >>> 48);
        out[2] = (byte) (hash64 >>> 40);
        out[3] = (byte) (hash64 >>> 32);
        out[4] = (byte) (hash64 >>> 24);
        out[5] = (byte) (hash64 >>> 16);
        out[6] = (byte) (hash64 >>> 8);
        out[7] = (byte) hash64;
    }

    private long hash64(long key) {
        byte[] buf = hashInput.get();
        buf[0] = (byte) (key >>> 56);
        buf[1] = (byte) (key >>> 48);
        buf[2] = (byte) (key >>> 40);
        buf[3] = (byte) (key >>> 32);
        buf[4] = (byte) (key >>> 24);
        buf[5] = (byte) (key >>> 16);
        buf[6] = (byte) (key >>> 8);
        buf[7] = (byte) key;
        return XXHashUtil.hash64(buf);
    }

    private int ownerServerId(long key) {
        long hash = hash64(key);
        return (int) ((hash >>> SERVER_HASH_SHIFT) & SERVER_ID_MASK);
    }

    private int tableId(long key) {
        long hash = hash64(key);
        return (int) (hash & 0xFFL);
    }

    private static int[] orderedServers(int[] serverIds) {
        if (serverIds == null || serverIds.length == 0) {
            return new int[0];
        }
        int[] out = Arrays.copyOf(serverIds, serverIds.length);
        Arrays.sort(out);
        int[] ordered = new int[out.length];
        int index = 0;
        for (int id : out) {
            if ((id & SIGN_BIT_SERVER_ID) != 0) {
                ordered[index++] = id;
            }
        }
        for (int id : out) {
            if ((id & SIGN_BIT_SERVER_ID) == 0) {
                ordered[index++] = id;
            }
        }
        return ordered;
    }

    private static long[] trim(long[] values, int size) {
        if (size == values.length) {
            return values;
        }
        return Arrays.copyOf(values, size);
    }
}
