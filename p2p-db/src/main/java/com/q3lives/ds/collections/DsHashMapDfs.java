package com.q3lives.ds.collections;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.P2PClientQuic;
import javax.net.p2p.dfsmap.DfsMapBackend;
import javax.net.p2p.dfsmap.DfsMapRegistry;
import javax.net.p2p.dfsmap.model.DfsMapExecKvReq;
import javax.net.p2p.dfsmap.model.DfsMapExecKvResp;
import javax.net.p2p.dfsmap.model.DfsMapGetReq;
import javax.net.p2p.dfsmap.model.DfsMapGetResp;
import javax.net.p2p.dfsmap.model.DfsMapGetTopologyReq;
import javax.net.p2p.dfsmap.model.DfsMapGetTopologyResp;
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
import javax.net.p2p.dfsmap.model.DfsMapTablesEnableReq;
import javax.net.p2p.dfsmap.model.DfsMapTablesEnableResp;
import javax.net.p2p.model.P2PWrapper;

public class DsHashMapDfs implements DfsMapBackend, AutoCloseable {
    public record Endpoint(String host, int port, int queueSize) {
    }

    public interface ServerDirectory {
        int[] listServerIds();

        Endpoint endpoint(int serverId);
    }

    interface RemoteCaller {
        DfsMapExecKvResp execKv(int serverId, DfsMapExecKvReq req);

        DfsMapRangeLocalResp rangeLocal(int serverId, DfsMapRangeLocalReq req);

        DfsMapPingResp ping(int serverId, DfsMapPingReq req);

        DfsMapTablesEnableResp tablesEnable(int serverId, P2PCommand command, DfsMapTablesEnableReq req);
    }

    private static final int API_VERSION = 1;
    private static final int SERVER_ID_BITS = 12;
    private static final int SERVER_ID_MASK = (1 << SERVER_ID_BITS) - 1;
    private static final long SERVER_HASH_SHIFT = 64L - SERVER_ID_BITS;
    private static final int SIGN_BIT_SERVER_ID = 1 << (SERVER_ID_BITS - 1);
    private static final int[] SIGNED_ASC_TABLES = buildSignedAscTables();

    //private static final long HASH_PROVIDER_ID = 9001L;
    private static final int DEFAULT_QUEUE_SIZE = 4096;
    private static final ExecutorService IO_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors())),
            new ThreadFactory() {
                private int index = 0;

                @Override
                public synchronized Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "DsHashMapDfs-io-" + (index++));
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    private final int serverId;
    private final File baseDir;
    private final ServerDirectory directory;
    private volatile boolean tablesEnabled;
    private final RemoteCaller remoteCaller;

    private final Object tableInitLock = new Object();
    private final Object migrationLock = new Object();
    private volatile DsHashMap singleTable;
    private volatile DsHashMap[] shardedTables;
    private final P2PClientQuic[] clients = new P2PClientQuic[1 << SERVER_ID_BITS];
    private volatile long epoch;
    private volatile boolean migrating;
    private volatile long activeMigrationId;
    private volatile boolean closed;

    public DsHashMapDfs(File baseDir, int serverId, ServerDirectory directory) {
        this(baseDir, serverId, directory, false);
    }

    public DsHashMapDfs(File baseDir, int serverId, ServerDirectory directory, boolean tablesEnabled) {
        this(baseDir, serverId, directory, tablesEnabled, null);
    }

    DsHashMapDfs(File baseDir, int serverId, ServerDirectory directory, boolean tablesEnabled, RemoteCaller remoteCaller) {
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
        this.remoteCaller = remoteCaller == null ? new DefaultRemoteCaller() : remoteCaller;
        DfsMapRegistry.setBackend(this);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        synchronized (migrationLock) {
            if (closed) {
                return;
            }
            closed = true;
            migrating = true;
        }
        closeAllTables();
        deleteSingleTableFiles();
        synchronized (clients) {
            for (int i = 0; i < clients.length; i++) {
                P2PClientQuic client = clients[i];
                clients[i] = null;
                if (client == null) {
                    continue;
                }
                tryCloseClient(client);
            }
        }
    }

    public static void shutdownSharedIoExecutor() {
        IO_EXECUTOR.shutdown();
        try {
            IO_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            IO_EXECUTOR.shutdownNow();
        }
    }

    private static void tryCloseClient(P2PClientQuic client) {
        try {
            client.getClass().getMethod("close").invoke(client);
        } catch (Exception ignored) {
        }
        try {
            client.getClass().getMethod("shutdown").invoke(client);
        } catch (Exception ignored) {
        }
    }

    @Override
    public DfsMapGetResp handleGet(DfsMapGetReq req) {
        if (migrating) {
            DfsMapGetResp resp = new DfsMapGetResp();
            resp.setStatus(DfsMapStatusCodes.RETRY);
            return resp;
        }
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
        if (migrating) {
            DfsMapPutResp resp = new DfsMapPutResp();
            resp.setStatus(DfsMapStatusCodes.RETRY);
            return resp;
        }
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
        if (migrating) {
            DfsMapRemoveResp resp = new DfsMapRemoveResp();
            resp.setStatus(DfsMapStatusCodes.RETRY);
            return resp;
        }
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
        if (migrating) {
            DfsMapRangeResp resp = new DfsMapRangeResp();
            resp.setStatus(DfsMapStatusCodes.RETRY);
            if (req != null) {
                resp.setRequestedCount(req.getCount());
                resp.setStart(req.getStart());
            }
            return resp;
        }
        DfsMapRangeResp resp = new DfsMapRangeResp();
        if (req == null) {
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
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
        if (servers.length == 0) {
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setEmitted(0);
            resp.setKeys(new long[0]);
            resp.setValues(req.isKeysOnly() ? null : new long[0]);
            return resp;
        }

        if (tablesEnabled) {
            return handleRangeTablesEnabled(req, resp, servers);
        }

        long[] sizes = new long[servers.length];
        CompletableFuture<Void>[] sizeFutures = new CompletableFuture[servers.length];
        for (int i = 0; i < servers.length; i++) {
            final int index = i;
            final int sid = servers[i];
            sizeFutures[i] = CompletableFuture.runAsync(() -> sizes[index] = pingSize(sid), IO_EXECUTOR);
        }
        try {
            CompletableFuture.allOf(sizeFutures).join();
        } catch (CompletionException ex) {
            resp.setStatus(DfsMapStatusCodes.RETRY);
            resp.setEmitted(0);
            resp.setKeys(new long[0]);
            resp.setValues(req.isKeysOnly() ? null : new long[0]);
            return resp;
        }
        for (long size : sizes) {
            if (size < 0) {
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setEmitted(0);
                resp.setKeys(new long[0]);
                resp.setValues(req.isKeysOnly() ? null : new long[0]);
                return resp;
            }
        }

        int startServerIndex = 0;
        long localCursor = start;
        while (startServerIndex < servers.length) {
            long serverSize = sizes[startServerIndex];
            if (localCursor < serverSize) {
                break;
            }
            localCursor -= serverSize;
            startServerIndex++;
        }
        if (startServerIndex >= servers.length) {
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setEmitted(0);
            resp.setKeys(new long[0]);
            resp.setValues(req.isKeysOnly() ? null : new long[0]);
            return resp;
        }

        int remaining = count;
        long[] cursors = new long[servers.length];
        int[] limits = new int[servers.length];
        boolean[] planned = new boolean[servers.length];

        long cursor = localCursor;
        for (int i = startServerIndex; i < servers.length && remaining > 0; i++) {
            long serverSize = sizes[i];
            long available = serverSize - cursor;
            if (available <= 0) {
                cursor = 0;
                continue;
            }
            int limit = (int) Math.min((long) remaining, available);
            if (limit <= 0) {
                cursor = 0;
                continue;
            }
            planned[i] = true;
            cursors[i] = cursor;
            limits[i] = limit;
            remaining -= limit;
            cursor = 0;
        }

        CompletableFuture<DfsMapRangeLocalResp>[] rangeFutures = new CompletableFuture[servers.length];
        boolean keysOnly = req.isKeysOnly();
        for (int i = 0; i < servers.length; i++) {
            if (!planned[i]) {
                continue;
            }
            final int index = i;
            rangeFutures[i] = CompletableFuture.supplyAsync(
                    () -> rangeLocalFromServer(servers[index], cursors[index], limits[index], keysOnly),
                    IO_EXECUTOR
            );
        }

        long[] keys = new long[count];
        long[] values = keysOnly ? null : new long[count];
        int emitted = 0;
        for (int i = 0; i < servers.length && emitted < count; i++) {
            if (!planned[i]) {
                continue;
            }
            DfsMapRangeLocalResp local;
            try {
                local = rangeFutures[i].join();
            } catch (CompletionException ex) {
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setEmitted(emitted);
                resp.setKeys(trim(keys, emitted));
                resp.setValues(values == null ? null : trim(values, emitted));
                return resp;
            }
            if (local == null) {
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setEmitted(emitted);
                resp.setKeys(trim(keys, emitted));
                resp.setValues(values == null ? null : trim(values, emitted));
                return resp;
            }
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
            for (int j = 0; j < got && emitted < count; j++) {
                keys[emitted] = localKeys[j];
                if (values != null) {
                    values[emitted] = localValues[j];
                }
                emitted++;
            }
        }

        resp.setStatus(DfsMapStatusCodes.OK);
        resp.setEmitted(emitted);
        resp.setKeys(trim(keys, emitted));
        resp.setValues(values == null ? null : trim(values, emitted));
        return resp;
    }

    private DfsMapRangeResp handleRangeTablesEnabled(DfsMapRangeReq req, DfsMapRangeResp resp, int[] servers) {
        long[][] tableSizes = new long[servers.length][];
        CompletableFuture<Void>[] pingFutures = new CompletableFuture[servers.length];
        for (int i = 0; i < servers.length; i++) {
            final int index = i;
            final int sid = servers[i];
            pingFutures[i] = CompletableFuture.runAsync(() -> tableSizes[index] = pingTableSizes(sid), IO_EXECUTOR);
        }
        try {
            CompletableFuture.allOf(pingFutures).join();
        } catch (CompletionException ex) {
            resp.setStatus(DfsMapStatusCodes.RETRY);
            resp.setEmitted(0);
            resp.setKeys(new long[0]);
            resp.setValues(req.isKeysOnly() ? null : new long[0]);
            return resp;
        }
        for (long[] sizes : tableSizes) {
            if (sizes == null || sizes.length != 256) {
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setEmitted(0);
                resp.setKeys(new long[0]);
                resp.setValues(req.isKeysOnly() ? null : new long[0]);
                return resp;
            }
        }

        long remainingSkip = req.getStart();
        int remaining = req.getCount();

        int[] planServer = new int[16];
        int[] planTable = new int[16];
        long[] planCursor = new long[16];
        int[] planLimit = new int[16];
        int plannedCount = 0;

        for (int sIndex = 0; sIndex < servers.length && remaining > 0; sIndex++) {
            int sid = servers[sIndex];
            long[] sizes = tableSizes[sIndex];
            for (int tPos = 0; tPos < 256 && remaining > 0; tPos++) {
                int tableId = SIGNED_ASC_TABLES[tPos];
                long size = sizes[tableId & 0xFF];
                if (size <= 0) {
                    continue;
                }
                if (remainingSkip >= size) {
                    remainingSkip -= size;
                    continue;
                }
                long cursor = remainingSkip;
                remainingSkip = 0;
                long available = size - cursor;
                if (available <= 0) {
                    continue;
                }
                int limit = (int) Math.min((long) remaining, available);
                if (limit <= 0) {
                    continue;
                }
                if (plannedCount >= planServer.length) {
                    int newCap = planServer.length * 2;
                    planServer = Arrays.copyOf(planServer, newCap);
                    planTable = Arrays.copyOf(planTable, newCap);
                    planCursor = Arrays.copyOf(planCursor, newCap);
                    planLimit = Arrays.copyOf(planLimit, newCap);
                }
                planServer[plannedCount] = sid;
                planTable[plannedCount] = tableId & 0xFF;
                planCursor[plannedCount] = cursor;
                planLimit[plannedCount] = limit;
                plannedCount++;
                remaining -= limit;
            }
        }

        if (plannedCount == 0) {
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setEmitted(0);
            resp.setKeys(new long[0]);
            resp.setValues(req.isKeysOnly() ? null : new long[0]);
            return resp;
        }

        boolean keysOnly = req.isKeysOnly();
        CompletableFuture<DfsMapRangeLocalResp>[] rangeFutures = new CompletableFuture[plannedCount];
        for (int i = 0; i < plannedCount; i++) {
            int sid = planServer[i];
            int tid = planTable[i];
            long cursor = planCursor[i];
            int limit = planLimit[i];
            rangeFutures[i] = CompletableFuture.supplyAsync(
                    () -> rangeLocalFromServer(sid, tid, cursor, limit, keysOnly),
                    IO_EXECUTOR
            );
        }

        long[] keys = new long[req.getCount()];
        long[] values = keysOnly ? null : new long[req.getCount()];
        int emitted = 0;

        for (int i = 0; i < plannedCount && emitted < req.getCount(); i++) {
            DfsMapRangeLocalResp local;
            try {
                local = rangeFutures[i].join();
            } catch (CompletionException ex) {
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setEmitted(emitted);
                resp.setKeys(trim(keys, emitted));
                resp.setValues(values == null ? null : trim(values, emitted));
                return resp;
            }
            if (local == null) {
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setEmitted(emitted);
                resp.setKeys(trim(keys, emitted));
                resp.setValues(values == null ? null : trim(values, emitted));
                return resp;
            }
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
            for (int j = 0; j < got && emitted < req.getCount(); j++) {
                keys[emitted] = localKeys[j];
                if (values != null) {
                    values[emitted] = localValues[j];
                }
                emitted++;
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
        if (migrating) {
            DfsMapExecKvResp resp = new DfsMapExecKvResp();
            resp.setStatus(DfsMapStatusCodes.RETRY);
            return resp;
        }
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
        DsHashMap map = localTable(tableId);
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
        if (migrating) {
            DfsMapRangeLocalResp resp = new DfsMapRangeLocalResp();
            resp.setStatus(DfsMapStatusCodes.RETRY);
            return resp;
        }
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
        DsHashMap map = localTable(tableId);
        try {
            RangeBatch batch = rangeLocalOrderedByHash(map, req.getCursor(), req.getLimit(), req.isKeysOnly());
            int emitted = batch.emitted;
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setEmitted(emitted);
            resp.setNextCursor(req.getCursor() + emitted);
            resp.setKeys(batch.keys);
            resp.setValues(batch.values);
            return resp;
        } catch (RuntimeException ex) {
            resp.setStatus(DfsMapStatusCodes.ERROR);
            return resp;
        }
    }

    private static final long HASH_ORDER_XOR = 0x0080808080808080L;

    private static final class RangeBatch {
        final int emitted;
        final long[] keys;
        final long[] values;

        private RangeBatch(int emitted, long[] keys, long[] values) {
            this.emitted = emitted;
            this.keys = keys;
            this.values = values;
        }
    }

    private RangeBatch rangeLocalOrderedByHash(DsHashMap map, long cursor, int limit, boolean keysOnly) {
        if (cursor < 0 || limit <= 0) {
            return new RangeBatch(0, new long[0], keysOnly ? null : new long[0]);
        }
        long totalLong = map.sizeLong();
        if (totalLong <= 0 || cursor >= totalLong) {
            return new RangeBatch(0, new long[0], keysOnly ? null : new long[0]);
        }
        int total = (int) Math.min(Integer.MAX_VALUE, totalLong);
        long[] keys = new long[total];
        long[] values = keysOnly ? null : new long[total];
        long[] order = new long[total];
        int filled = 0;
        for (Map.Entry<Long, Long> e : map.entrySet()) {
            if (e == null) continue;
            Long kObj = e.getKey();
            Long vObj = e.getValue();
            if (kObj == null || vObj == null) continue;
            long k = kObj.longValue();
            long v = vObj.longValue();
            keys[filled] = k;
            if (values != null) {
                values[filled] = v;
            }
            order[filled] = k ^ HASH_ORDER_XOR;
            filled++;
            if (filled >= total) {
                break;
            }
        }
        if (filled <= 0) {
            return new RangeBatch(0, new long[0], keysOnly ? null : new long[0]);
        }
        if (filled < total) {
            keys = Arrays.copyOf(keys, filled);
            order = Arrays.copyOf(order, filled);
            if (values != null) {
                values = Arrays.copyOf(values, filled);
            }
            total = filled;
        }

        sortByOrderThenKey(order, keys, values, 0, total - 1);

        int start = cursor > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) cursor;
        if (start >= total) {
            return new RangeBatch(0, new long[0], keysOnly ? null : new long[0]);
        }
        int end = Math.min(total, start + limit);
        int emitted = end - start;
        long[] outKeys = Arrays.copyOfRange(keys, start, end);
        long[] outValues = values == null ? null : Arrays.copyOfRange(values, start, end);
        return new RangeBatch(emitted, outKeys, outValues);
    }

    private static void sortByOrderThenKey(long[] order, long[] keys, long[] values, int lo, int hi) {
        int left = lo;
        int right = hi;
        while (left < right) {
            int i = left;
            int j = right;
            int mid = (i + j) >>> 1;
            long pivotOrder = order[mid];
            long pivotKey = keys[mid];
            while (i <= j) {
                while (compare(order[i], keys[i], pivotOrder, pivotKey) < 0) i++;
                while (compare(order[j], keys[j], pivotOrder, pivotKey) > 0) j--;
                if (i <= j) {
                    swap(order, i, j);
                    swap(keys, i, j);
                    if (values != null) swap(values, i, j);
                    i++;
                    j--;
                }
            }
            if (j - left < right - i) {
                if (left < j) sortByOrderThenKey(order, keys, values, left, j);
                left = i;
            } else {
                if (i < right) sortByOrderThenKey(order, keys, values, i, right);
                right = j;
            }
        }
    }

    private static int compare(long orderA, long keyA, long orderB, long keyB) {
        int c = Long.compare(orderA, orderB);
        if (c != 0) return c;
        return Long.compare(keyA, keyB);
    }

    private static void swap(long[] a, int i, int j) {
        if (i == j) return;
        long t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    @Override
    public DfsMapPingResp handlePing(DfsMapPingReq req) {
        if (migrating) {
            DfsMapPingResp resp = new DfsMapPingResp();
            resp.setStatus(DfsMapStatusCodes.RETRY);
            resp.setEpoch(epoch);
            resp.setServerId(serverId);
            resp.setTablesEnabled(tablesEnabled);
            resp.setTotalSize(-1L);
            return resp;
        }
        DfsMapPingResp resp = new DfsMapPingResp();
        resp.setStatus(DfsMapStatusCodes.OK);
        resp.setEpoch(epoch);
        resp.setServerId(serverId);
        resp.setTablesEnabled(tablesEnabled);
        if (tablesEnabled) {
            long[] sizes = new long[256];
            long total = 0L;
            for (int i = 0; i < 256; i++) {
                long size = openShardedTable(i).sizeLong();
                sizes[i] = size;
                total += size;
            }
            resp.setTableSizes(sizes);
            resp.setTotalSize(total);
            return resp;
        }
        DsHashMap map = openSingleTable();
        resp.setTotalSize(map.sizeLong());
        return resp;
    }

    @Override
    public DfsMapGetTopologyResp handleGetTopology(DfsMapGetTopologyReq req) {
        DfsMapGetTopologyResp resp = new DfsMapGetTopologyResp();
        resp.setStatus(DfsMapStatusCodes.OK);
        resp.setEpoch(epoch);
        resp.setServerId(serverId);
        resp.setTablesEnabled(tablesEnabled);
        resp.setServerIds(directory.listServerIds());
        return resp;
    }

    @Override
    public DfsMapTablesEnableResp handleTablesEnable(P2PCommand command, DfsMapTablesEnableReq req) {
        DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
        resp.setServerId(serverId);
        resp.setEpoch(epoch);
        resp.setTablesEnabled(tablesEnabled);
        if (req == null) {
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            resp.setMessage("null req");
            return resp;
        }
        resp.setMigrationId(req.getMigrationId());
        if (command == null) {
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            resp.setMessage("null command");
            return resp;
        }
        if (command == P2PCommand.DFS_MAP_INT_TABLES_ENABLE_BEGIN) {
            return handleTablesEnableBegin(req);
        }
        if (command == P2PCommand.DFS_MAP_INT_TABLES_ENABLE_PREPARE) {
            return handleTablesEnablePrepare(req);
        }
        if (command == P2PCommand.DFS_MAP_INT_TABLES_ENABLE_STREAM_DUMP) {
            return handleTablesEnableStreamDump(req);
        }
        if (command == P2PCommand.DFS_MAP_INT_TABLES_ENABLE_STREAM_APPLY) {
            return handleTablesEnableStreamApply(req);
        }
        if (command == P2PCommand.DFS_MAP_INT_TABLES_ENABLE_COMMIT) {
            return handleTablesEnableCommit(req);
        }
        if (command == P2PCommand.DFS_MAP_INT_TABLES_ENABLE_ABORT) {
            return handleTablesEnableAbort(req);
        }
        resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
        resp.setMessage("unsupported command: " + command);
        return resp;
    }

    public DfsMapTablesEnableResp enableTablesStepByStepDistributed(long migrationId, boolean force, int batchLimit) {
        if (migrationId == 0L) {
            DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            resp.setServerId(serverId);
            resp.setEpoch(epoch);
            resp.setMigrationId(0L);
            resp.setTablesEnabled(tablesEnabled);
            resp.setMessage("migrationId required");
            return resp;
        }
        if (tablesEnabled) {
            DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setServerId(serverId);
            resp.setEpoch(epoch);
            resp.setMigrationId(migrationId);
            resp.setTablesEnabled(true);
            return resp;
        }

        DfsMapTablesEnableReq begin = new DfsMapTablesEnableReq();
        begin.setApiVersion(API_VERSION);
        begin.setEpoch(epoch);
        begin.setMigrationId(migrationId);
        begin.setCoordinatorServerId(serverId);
        begin.setForwarded(false);
        begin.setForce(force);
        begin.setStepByStep(true);
        DfsMapTablesEnableResp beginResp = handleTablesEnable(P2PCommand.DFS_MAP_INT_TABLES_ENABLE_BEGIN, begin);
        if (beginResp.getStatus() != DfsMapStatusCodes.OK) {
            return beginResp;
        }

        DfsMapTablesEnableReq prepare = new DfsMapTablesEnableReq();
        prepare.setApiVersion(API_VERSION);
        prepare.setEpoch(epoch);
        prepare.setMigrationId(migrationId);
        prepare.setCoordinatorServerId(serverId);
        prepare.setForwarded(false);
        prepare.setForce(force);
        prepare.setStepByStep(true);
        DfsMapTablesEnableResp prepareResp = handleTablesEnable(P2PCommand.DFS_MAP_INT_TABLES_ENABLE_PREPARE, prepare);
        if (prepareResp.getStatus() != DfsMapStatusCodes.OK) {
            return prepareResp;
        }

        int[] servers = directory.listServerIds();
        CompletableFuture<DfsMapTablesEnableResp>[] futures = new CompletableFuture[servers.length];
        int effectiveBatch = batchLimit <= 0 ? 1024 : Math.min(batchLimit, 4096);
        for (int i = 0; i < servers.length; i++) {
            int sid = servers[i];
            futures[i] = CompletableFuture.supplyAsync(
                    () -> streamDumpApplyServer(sid, migrationId, effectiveBatch),
                    IO_EXECUTOR
            );
        }
        try {
            CompletableFuture.allOf(futures).join();
        } catch (CompletionException ex) {
            DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
            resp.setStatus(DfsMapStatusCodes.RETRY);
            resp.setServerId(serverId);
            resp.setEpoch(epoch);
            resp.setMigrationId(migrationId);
            resp.setTablesEnabled(false);
            resp.setMessage("stream dump/apply failed");
            return resp;
        }
        for (CompletableFuture<DfsMapTablesEnableResp> f : futures) {
            DfsMapTablesEnableResp r = f.join();
            if (r == null || r.getStatus() != DfsMapStatusCodes.OK) {
                DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setServerId(serverId);
                resp.setEpoch(epoch);
                resp.setMigrationId(migrationId);
                resp.setTablesEnabled(false);
                resp.setMessage("stream dump/apply not ok");
                return resp;
            }
        }

        DfsMapTablesEnableReq commit = new DfsMapTablesEnableReq();
        commit.setApiVersion(API_VERSION);
        commit.setEpoch(epoch);
        commit.setMigrationId(migrationId);
        commit.setCoordinatorServerId(serverId);
        commit.setForwarded(false);
        commit.setStepByStep(true);
        return handleTablesEnable(P2PCommand.DFS_MAP_INT_TABLES_ENABLE_COMMIT, commit);
    }

    private DfsMapTablesEnableResp streamDumpApplyServer(int serverId, long migrationId, int batchLimit) {
        long cursor = 0L;
        for (;;) {
            DfsMapTablesEnableReq dump = new DfsMapTablesEnableReq();
            dump.setApiVersion(API_VERSION);
            dump.setEpoch(epoch);
            dump.setMigrationId(migrationId);
            dump.setCoordinatorServerId(this.serverId);
            dump.setForwarded(true);
            dump.setStepByStep(true);
            dump.setCursor(cursor);
            dump.setLimit(batchLimit);

            DfsMapTablesEnableResp dumpResp;
            if (serverId == this.serverId) {
                dumpResp = handleTablesEnable(P2PCommand.DFS_MAP_INT_TABLES_ENABLE_STREAM_DUMP, dump);
            } else {
                dumpResp = remoteCaller.tablesEnable(serverId, P2PCommand.DFS_MAP_INT_TABLES_ENABLE_STREAM_DUMP, dump);
            }
            if (dumpResp == null || dumpResp.getStatus() != DfsMapStatusCodes.OK) {
                DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setServerId(this.serverId);
                resp.setEpoch(epoch);
                resp.setMigrationId(migrationId);
                resp.setTablesEnabled(false);
                resp.setMessage("dump failed");
                return resp;
            }
            int emitted = dumpResp.getEmitted();
            if (emitted <= 0) {
                DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
                resp.setStatus(DfsMapStatusCodes.OK);
                resp.setServerId(this.serverId);
                resp.setEpoch(epoch);
                resp.setMigrationId(migrationId);
                resp.setTablesEnabled(false);
                return resp;
            }

            DfsMapTablesEnableReq apply = new DfsMapTablesEnableReq();
            apply.setApiVersion(API_VERSION);
            apply.setEpoch(epoch);
            apply.setMigrationId(migrationId);
            apply.setCoordinatorServerId(this.serverId);
            apply.setForwarded(true);
            apply.setStepByStep(true);
            apply.setKeys(dumpResp.getKeys());
            apply.setValues(dumpResp.getValues());

            DfsMapTablesEnableResp applyResp;
            if (serverId == this.serverId) {
                applyResp = handleTablesEnable(P2PCommand.DFS_MAP_INT_TABLES_ENABLE_STREAM_APPLY, apply);
            } else {
                applyResp = remoteCaller.tablesEnable(serverId, P2PCommand.DFS_MAP_INT_TABLES_ENABLE_STREAM_APPLY, apply);
            }
            if (applyResp == null || applyResp.getStatus() != DfsMapStatusCodes.OK) {
                DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setServerId(this.serverId);
                resp.setEpoch(epoch);
                resp.setMigrationId(migrationId);
                resp.setTablesEnabled(false);
                resp.setMessage("apply failed");
                return resp;
            }
            cursor = dumpResp.getNextCursor();
        }
    }

    private DfsMapTablesEnableResp handleTablesEnableBegin(DfsMapTablesEnableReq req) {
        DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
        resp.setServerId(serverId);
        resp.setMigrationId(req.getMigrationId());
        if (req.getMigrationId() == 0L) {
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            resp.setMessage("migrationId required");
            return resp;
        }
        if (req.isForwarded()) {
            if (req.isStepByStep()) {
                return beginStepByStepLocal(req.getMigrationId(), req.isForce());
            }
            return beginLocal(req.getMigrationId(), req.isForce());
        }

        DfsMapTablesEnableResp local;
        if (req.isStepByStep()) {
            local = beginStepByStepLocal(req.getMigrationId(), req.isForce());
        } else {
            local = beginLocal(req.getMigrationId(), req.isForce());
        }
        if (local.getStatus() != DfsMapStatusCodes.OK) {
            return local;
        }

        int[] servers = directory.listServerIds();
        CompletableFuture<DfsMapTablesEnableResp>[] futures = new CompletableFuture[servers.length];
        for (int i = 0; i < servers.length; i++) {
            int sid = servers[i];
            if (sid == serverId) {
                futures[i] = CompletableFuture.completedFuture(local);
                continue;
            }
            DfsMapTablesEnableReq forwarded = new DfsMapTablesEnableReq();
            forwarded.setApiVersion(API_VERSION);
            forwarded.setEpoch(req.getEpoch());
            forwarded.setMigrationId(req.getMigrationId());
            forwarded.setCoordinatorServerId(serverId);
            forwarded.setForwarded(true);
            forwarded.setForce(req.isForce());
            forwarded.setStepByStep(req.isStepByStep());
            futures[i] = CompletableFuture.supplyAsync(
                    () -> remoteCaller.tablesEnable(sid, P2PCommand.DFS_MAP_INT_TABLES_ENABLE_BEGIN, forwarded),
                    IO_EXECUTOR
            );
        }

        try {
            CompletableFuture.allOf(futures).join();
        } catch (CompletionException ex) {
            resp.setStatus(DfsMapStatusCodes.RETRY);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(tablesEnabled);
            resp.setMessage("remote begin failed");
            return resp;
        }

        for (CompletableFuture<DfsMapTablesEnableResp> f : futures) {
            DfsMapTablesEnableResp r = f.join();
            if (r == null || r.getStatus() != DfsMapStatusCodes.OK) {
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setEpoch(epoch);
                resp.setTablesEnabled(tablesEnabled);
                resp.setMessage("remote begin not ok");
                return resp;
            }
        }

        resp.setStatus(DfsMapStatusCodes.OK);
        resp.setEpoch(epoch);
        resp.setTablesEnabled(tablesEnabled);
        return resp;
    }

    private DfsMapTablesEnableResp handleTablesEnablePrepare(DfsMapTablesEnableReq req) {
        if (req.getMigrationId() == 0L) {
            DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            resp.setServerId(serverId);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(tablesEnabled);
            resp.setMigrationId(0L);
            resp.setMessage("migrationId required");
            return resp;
        }
        if (req.isForwarded()) {
            return prepareLocal(req.getMigrationId(), req.isForce());
        }
        DfsMapTablesEnableResp local = prepareLocal(req.getMigrationId(), req.isForce());
        if (local.getStatus() != DfsMapStatusCodes.OK) {
            return local;
        }
        int[] servers = directory.listServerIds();
        CompletableFuture<DfsMapTablesEnableResp>[] futures = new CompletableFuture[servers.length];
        for (int i = 0; i < servers.length; i++) {
            int sid = servers[i];
            if (sid == serverId) {
                futures[i] = CompletableFuture.completedFuture(local);
                continue;
            }
            DfsMapTablesEnableReq forwarded = new DfsMapTablesEnableReq();
            forwarded.setApiVersion(API_VERSION);
            forwarded.setEpoch(req.getEpoch());
            forwarded.setMigrationId(req.getMigrationId());
            forwarded.setCoordinatorServerId(serverId);
            forwarded.setForwarded(true);
            forwarded.setForce(req.isForce());
            forwarded.setStepByStep(true);
            futures[i] = CompletableFuture.supplyAsync(
                    () -> remoteCaller.tablesEnable(sid, P2PCommand.DFS_MAP_INT_TABLES_ENABLE_PREPARE, forwarded),
                    IO_EXECUTOR
            );
        }
        try {
            CompletableFuture.allOf(futures).join();
        } catch (CompletionException ex) {
            DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
            resp.setStatus(DfsMapStatusCodes.RETRY);
            resp.setServerId(serverId);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(tablesEnabled);
            resp.setMigrationId(req.getMigrationId());
            resp.setMessage("remote prepare failed");
            return resp;
        }
        for (CompletableFuture<DfsMapTablesEnableResp> f : futures) {
            DfsMapTablesEnableResp r = f.join();
            if (r == null || r.getStatus() != DfsMapStatusCodes.OK) {
                DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setServerId(serverId);
                resp.setEpoch(epoch);
                resp.setTablesEnabled(tablesEnabled);
                resp.setMigrationId(req.getMigrationId());
                resp.setMessage("remote prepare not ok");
                return resp;
            }
        }
        return local;
    }

    private DfsMapTablesEnableResp handleTablesEnableStreamDump(DfsMapTablesEnableReq req) {
        return streamDumpLocal(req.getMigrationId(), req.getCursor(), req.getLimit());
    }

    private DfsMapTablesEnableResp handleTablesEnableStreamApply(DfsMapTablesEnableReq req) {
        return streamApplyLocal(req.getMigrationId(), req.getKeys(), req.getValues());
    }

    private DfsMapTablesEnableResp handleTablesEnableCommit(DfsMapTablesEnableReq req) {
        if (req.getMigrationId() == 0L) {
            DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            resp.setServerId(serverId);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(tablesEnabled);
            resp.setMigrationId(0L);
            resp.setMessage("migrationId required");
            return resp;
        }
        if (req.isForwarded()) {
            return commitLocal(req.getMigrationId());
        }
        DfsMapTablesEnableResp local = commitLocal(req.getMigrationId());
        if (local.getStatus() != DfsMapStatusCodes.OK) {
            return local;
        }
        int[] servers = directory.listServerIds();
        CompletableFuture<DfsMapTablesEnableResp>[] futures = new CompletableFuture[servers.length];
        for (int i = 0; i < servers.length; i++) {
            int sid = servers[i];
            if (sid == serverId) {
                futures[i] = CompletableFuture.completedFuture(local);
                continue;
            }
            DfsMapTablesEnableReq forwarded = new DfsMapTablesEnableReq();
            forwarded.setApiVersion(API_VERSION);
            forwarded.setEpoch(req.getEpoch());
            forwarded.setMigrationId(req.getMigrationId());
            forwarded.setCoordinatorServerId(serverId);
            forwarded.setForwarded(true);
            forwarded.setStepByStep(true);
            futures[i] = CompletableFuture.supplyAsync(
                    () -> remoteCaller.tablesEnable(sid, P2PCommand.DFS_MAP_INT_TABLES_ENABLE_COMMIT, forwarded),
                    IO_EXECUTOR
            );
        }
        try {
            CompletableFuture.allOf(futures).join();
        } catch (CompletionException ex) {
            DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
            resp.setStatus(DfsMapStatusCodes.RETRY);
            resp.setServerId(serverId);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(tablesEnabled);
            resp.setMigrationId(req.getMigrationId());
            resp.setMessage("remote commit failed");
            return resp;
        }
        for (CompletableFuture<DfsMapTablesEnableResp> f : futures) {
            DfsMapTablesEnableResp r = f.join();
            if (r == null || r.getStatus() != DfsMapStatusCodes.OK) {
                DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setServerId(serverId);
                resp.setEpoch(epoch);
                resp.setTablesEnabled(tablesEnabled);
                resp.setMigrationId(req.getMigrationId());
                resp.setMessage("remote commit not ok");
                return resp;
            }
        }
        return local;
    }

    private DfsMapTablesEnableResp handleTablesEnableAbort(DfsMapTablesEnableReq req) {
        if (req.getMigrationId() == 0L) {
            DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            resp.setServerId(serverId);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(tablesEnabled);
            resp.setMigrationId(0L);
            resp.setMessage("migrationId required");
            return resp;
        }
        if (req.isForwarded()) {
            return abortLocal(req.getMigrationId());
        }
        DfsMapTablesEnableResp local = abortLocal(req.getMigrationId());
        if (local.getStatus() != DfsMapStatusCodes.OK) {
            return local;
        }
        int[] servers = directory.listServerIds();
        CompletableFuture<DfsMapTablesEnableResp>[] futures = new CompletableFuture[servers.length];
        for (int i = 0; i < servers.length; i++) {
            int sid = servers[i];
            if (sid == serverId) {
                futures[i] = CompletableFuture.completedFuture(local);
                continue;
            }
            DfsMapTablesEnableReq forwarded = new DfsMapTablesEnableReq();
            forwarded.setApiVersion(API_VERSION);
            forwarded.setEpoch(req.getEpoch());
            forwarded.setMigrationId(req.getMigrationId());
            forwarded.setCoordinatorServerId(serverId);
            forwarded.setForwarded(true);
            forwarded.setStepByStep(true);
            futures[i] = CompletableFuture.supplyAsync(
                    () -> remoteCaller.tablesEnable(sid, P2PCommand.DFS_MAP_INT_TABLES_ENABLE_ABORT, forwarded),
                    IO_EXECUTOR
            );
        }
        try {
            CompletableFuture.allOf(futures).join();
        } catch (CompletionException ex) {
            DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
            resp.setStatus(DfsMapStatusCodes.RETRY);
            resp.setServerId(serverId);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(tablesEnabled);
            resp.setMigrationId(req.getMigrationId());
            resp.setMessage("remote abort failed");
            return resp;
        }
        for (CompletableFuture<DfsMapTablesEnableResp> f : futures) {
            DfsMapTablesEnableResp r = f.join();
            if (r == null || r.getStatus() != DfsMapStatusCodes.OK) {
                DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setServerId(serverId);
                resp.setEpoch(epoch);
                resp.setTablesEnabled(tablesEnabled);
                resp.setMigrationId(req.getMigrationId());
                resp.setMessage("remote abort not ok");
                return resp;
            }
        }
        return local;
    }

    private DfsMapTablesEnableResp beginLocal(long migrationId, boolean force) {
        DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
        resp.setServerId(serverId);
        resp.setMigrationId(migrationId);
        synchronized (migrationLock) {
            if (tablesEnabled) {
                resp.setStatus(DfsMapStatusCodes.OK);
                resp.setEpoch(epoch);
                resp.setTablesEnabled(true);
                return resp;
            }
            if (migrating) {
                if (activeMigrationId == migrationId) {
                    resp.setStatus(DfsMapStatusCodes.NOT_READY);
                    resp.setEpoch(epoch);
                    resp.setTablesEnabled(false);
                    resp.setMessage("migration in progress");
                    return resp;
                }
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setEpoch(epoch);
                resp.setTablesEnabled(false);
                resp.setMessage("another migration active");
                return resp;
            }
            migrating = true;
            activeMigrationId = migrationId;
        }

        try {
            if (force) {
                deleteAllTableFiles();
            }
            DsHashMap source = openSingleTable();
            long cursor = 0;
            final int batchSize = 1024;
            while (true) {
                var entries = source.range(cursor, batchSize);
                if (entries.isEmpty()) {
                    break;
                }
                for (var e : entries) {
                    long key = e.getKey();
                    long value = e.getValue();
                    int tableId = tableId(key);
                    openShardedTable(tableId).put(key, value);
                }
                cursor += entries.size();
            }

            closeAllTables();
            source.close();
            deleteSingleTableFiles();

            synchronized (migrationLock) {
                tablesEnabled = true;
                epoch++;
                migrating = false;
                activeMigrationId = 0L;
            }

            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(true);
            return resp;
        } catch (Exception ex) {
            synchronized (migrationLock) {
                migrating = false;
                activeMigrationId = 0L;
            }
            resp.setStatus(DfsMapStatusCodes.ERROR);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(false);
            resp.setMessage(ex.getMessage());
            return resp;
        }
    }

    private DfsMapTablesEnableResp beginStepByStepLocal(long migrationId, boolean force) {
        DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
        resp.setServerId(serverId);
        resp.setMigrationId(migrationId);
        synchronized (migrationLock) {
            if (tablesEnabled) {
                resp.setStatus(DfsMapStatusCodes.OK);
                resp.setEpoch(epoch);
                resp.setTablesEnabled(true);
                return resp;
            }
            if (migrating) {
                if (activeMigrationId == migrationId) {
                    resp.setStatus(DfsMapStatusCodes.OK);
                    resp.setEpoch(epoch);
                    resp.setTablesEnabled(false);
                    return resp;
                }
                resp.setStatus(DfsMapStatusCodes.RETRY);
                resp.setEpoch(epoch);
                resp.setTablesEnabled(false);
                resp.setMessage("another migration active");
                return resp;
            }
            migrating = true;
            activeMigrationId = migrationId;
        }
        if (force) {
            deleteAllTableFiles();
        }
        resp.setStatus(DfsMapStatusCodes.OK);
        resp.setEpoch(epoch);
        resp.setTablesEnabled(false);
        return resp;
    }

    private DfsMapTablesEnableResp prepareLocal(long migrationId, boolean force) {
        DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
        resp.setServerId(serverId);
        resp.setMigrationId(migrationId);
        synchronized (migrationLock) {
            if (!migrating || activeMigrationId != migrationId) {
                resp.setStatus(DfsMapStatusCodes.NOT_READY);
                resp.setEpoch(epoch);
                resp.setTablesEnabled(tablesEnabled);
                resp.setMessage("migration not active");
                return resp;
            }
        }
        if (force) {
            deleteAllTableFiles();
        }
        resp.setStatus(DfsMapStatusCodes.OK);
        resp.setEpoch(epoch);
        resp.setTablesEnabled(false);
        return resp;
    }

    private DfsMapTablesEnableResp streamDumpLocal(long migrationId, long cursor, int limit) {
        DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
        resp.setServerId(serverId);
        resp.setMigrationId(migrationId);
        synchronized (migrationLock) {
            if (!migrating || activeMigrationId != migrationId) {
                resp.setStatus(DfsMapStatusCodes.NOT_READY);
                resp.setEpoch(epoch);
                resp.setTablesEnabled(tablesEnabled);
                resp.setMessage("migration not active");
                return resp;
            }
            if (tablesEnabled) {
                resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
                resp.setEpoch(epoch);
                resp.setTablesEnabled(true);
                resp.setMessage("tables already enabled");
                return resp;
            }
        }
        if (cursor < 0) {
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(false);
            resp.setMessage("cursor must be >= 0");
            return resp;
        }
        int batch = limit <= 0 ? 1024 : Math.min(limit, 4096);
        try {
            DsHashMap source = openSingleTable();
            var entries = source.range(cursor, batch);
            int emitted = entries.size();
            long[] keys = new long[emitted];
            long[] values = new long[emitted];
            for (int i = 0; i < emitted; i++) {
                var e = entries.get(i);
                keys[i] = e.getKey();
                values[i] = e.getValue();
            }
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(false);
            resp.setEmitted(emitted);
            resp.setKeys(keys);
            resp.setValues(values);
            resp.setNextCursor(cursor + emitted);
            return resp;
        } catch (Exception ex) {
            resp.setStatus(DfsMapStatusCodes.ERROR);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(false);
            resp.setMessage(ex.getMessage());
            return resp;
        }
    }

    private DfsMapTablesEnableResp streamApplyLocal(long migrationId, long[] keys, long[] values) {
        DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
        resp.setServerId(serverId);
        resp.setMigrationId(migrationId);
        synchronized (migrationLock) {
            if (!migrating || activeMigrationId != migrationId) {
                resp.setStatus(DfsMapStatusCodes.NOT_READY);
                resp.setEpoch(epoch);
                resp.setTablesEnabled(tablesEnabled);
                resp.setMessage("migration not active");
                return resp;
            }
            if (tablesEnabled) {
                resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
                resp.setEpoch(epoch);
                resp.setTablesEnabled(true);
                resp.setMessage("tables already enabled");
                return resp;
            }
        }
        if (keys == null || values == null || keys.length != values.length) {
            resp.setStatus(DfsMapStatusCodes.BAD_REQUEST);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(false);
            resp.setMessage("keys/values mismatch");
            return resp;
        }
        try {
            for (int i = 0; i < keys.length; i++) {
                long key = keys[i];
                long value = values[i];
                openShardedTable(tableId(key)).put(key, value);
            }
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(false);
            resp.setEmitted(keys.length);
            return resp;
        } catch (Exception ex) {
            resp.setStatus(DfsMapStatusCodes.ERROR);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(false);
            resp.setMessage(ex.getMessage());
            return resp;
        }
    }

    private DfsMapTablesEnableResp commitLocal(long migrationId) {
        DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
        resp.setServerId(serverId);
        resp.setMigrationId(migrationId);
        synchronized (migrationLock) {
            if (!migrating || activeMigrationId != migrationId) {
                resp.setStatus(DfsMapStatusCodes.NOT_READY);
                resp.setEpoch(epoch);
                resp.setTablesEnabled(tablesEnabled);
                resp.setMessage("migration not active");
                return resp;
            }
        }
        try {
            deleteSingleTableFiles();
            synchronized (migrationLock) {
                tablesEnabled = true;
                epoch++;
                migrating = false;
                activeMigrationId = 0L;
            }
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(true);
            return resp;
        } catch (Exception ex) {
            resp.setStatus(DfsMapStatusCodes.ERROR);
            resp.setEpoch(epoch);
            resp.setTablesEnabled(false);
            resp.setMessage(ex.getMessage());
            return resp;
        }
    }

    private DfsMapTablesEnableResp abortLocal(long migrationId) {
        DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
        resp.setServerId(serverId);
        resp.setMigrationId(migrationId);
        synchronized (migrationLock) {
            if (!migrating || activeMigrationId != migrationId) {
                resp.setStatus(DfsMapStatusCodes.NOT_READY);
                resp.setEpoch(epoch);
                resp.setTablesEnabled(tablesEnabled);
                resp.setMessage("migration not active");
                return resp;
            }
        }
        deleteAllTableFiles();
        synchronized (migrationLock) {
            epoch++;
            migrating = false;
            activeMigrationId = 0L;
        }
        resp.setStatus(DfsMapStatusCodes.OK);
        resp.setEpoch(epoch);
        resp.setTablesEnabled(false);
        return resp;
    }

    private DfsMapGetResp getLocal(long key) {
        DfsMapGetResp resp = new DfsMapGetResp();
        resp.setKey(key);
        DsHashMap map = localTable(tablesEnabled ? tableId(key) : 0);
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
        DsHashMap map = localTable(tablesEnabled ? tableId(key) : 0);
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
        DsHashMap map = localTable(tablesEnabled ? tableId(key) : 0);
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
        return remoteCaller.execKv(ownerServerId, req);
    }

    private long pingSize(int serverId) {
        if (serverId == this.serverId) {
            return openSingleTable().sizeLong();
        }
        DfsMapPingReq req = new DfsMapPingReq();
        req.setApiVersion(API_VERSION);
        req.setEpoch(0L);
        DfsMapPingResp resp = remoteCaller.ping(serverId, req);
        if (resp == null || resp.getStatus() != DfsMapStatusCodes.OK) {
            return -1L;
        }
        return resp.getTotalSize();
    }

    private long[] pingTableSizes(int serverId) {
        if (serverId == this.serverId) {
            long[] sizes = new long[256];
            for (int i = 0; i < 256; i++) {
                sizes[i] = openShardedTable(i).sizeLong();
            }
            return sizes;
        }
        DfsMapPingReq req = new DfsMapPingReq();
        req.setApiVersion(API_VERSION);
        req.setEpoch(0L);
        DfsMapPingResp resp = remoteCaller.ping(serverId, req);
        if (resp == null || resp.getStatus() != DfsMapStatusCodes.OK) {
            return null;
        }
        if (!resp.isTablesEnabled()) {
            return null;
        }
        return resp.getTableSizes();
    }

    private DfsMapRangeLocalResp rangeLocalFromServer(int serverId, long cursor, int limit, boolean keysOnly) {
        return rangeLocalFromServer(serverId, 0, cursor, limit, keysOnly);
    }

    private DfsMapRangeLocalResp rangeLocalFromServer(int serverId, int tableId, long cursor, int limit, boolean keysOnly) {
        if (serverId == this.serverId) {
            DfsMapRangeLocalReq req = new DfsMapRangeLocalReq();
            req.setApiVersion(API_VERSION);
            req.setEpoch(0L);
            req.setOwnerServerId(serverId);
            req.setTableId(tableId);
            req.setCursor(cursor);
            req.setLimit(limit);
            req.setKeysOnly(keysOnly);
            return handleRangeLocal(req);
        }
        DfsMapRangeLocalReq req = new DfsMapRangeLocalReq();
        req.setApiVersion(API_VERSION);
        req.setEpoch(0L);
        req.setOwnerServerId(serverId);
        req.setTableId(tableId);
        req.setCursor(cursor);
        req.setLimit(limit);
        req.setKeysOnly(keysOnly);
        DfsMapRangeLocalResp resp = remoteCaller.rangeLocal(serverId, req);
        if (resp == null) {
            DfsMapRangeLocalResp fallback = new DfsMapRangeLocalResp();
            fallback.setStatus(DfsMapStatusCodes.RETRY);
            return fallback;
        }
        return resp;
    }

    private final class DefaultRemoteCaller implements RemoteCaller {
        @Override
        public DfsMapExecKvResp execKv(int serverId, DfsMapExecKvReq req) {
            P2PClientQuic client = clientFor(serverId);
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

        @Override
        public DfsMapRangeLocalResp rangeLocal(int serverId, DfsMapRangeLocalReq req) {
            P2PClientQuic client = clientFor(serverId);
            if (client == null) {
                DfsMapRangeLocalResp resp = new DfsMapRangeLocalResp();
                resp.setStatus(DfsMapStatusCodes.RETRY);
                return resp;
            }
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

        @Override
        public DfsMapPingResp ping(int serverId, DfsMapPingReq req) {
            P2PClientQuic client = clientFor(serverId);
            if (client == null) {
                return null;
            }
            P2PWrapper request = P2PWrapper.build(P2PCommand.DFS_MAP_INT_PING, req);
            try {
                P2PWrapper response = client.excute(request, 10, TimeUnit.SECONDS);
                if (!(response.getData() instanceof DfsMapPingResp resp)) {
                    return null;
                }
                return resp;
            } catch (Exception ex) {
                return null;
            }
        }

        @Override
        public DfsMapTablesEnableResp tablesEnable(int serverId, P2PCommand command, DfsMapTablesEnableReq req) {
            P2PClientQuic client = clientFor(serverId);
            if (client == null) {
                return null;
            }
            P2PWrapper request = P2PWrapper.build(command, req);
            try {
                P2PWrapper response = client.excute(request, 120, TimeUnit.SECONDS);
                if (!(response.getData() instanceof DfsMapTablesEnableResp resp)) {
                    return null;
                }
                return resp;
            } catch (Exception ex) {
                return null;
            }
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

    private DsHashMap localTable(int tableId) {
        if (tablesEnabled) {
            return openShardedTable(tableId);
        }
        return openSingleTable();
    }

    private DsHashMap openSingleTable() {
        DsHashMap map = singleTable;
        if (map != null) {
            return map;
        }
        synchronized (tableInitLock) {
            DsHashMap current = singleTable;
            if (current != null) {
                return current;
            }
            File file = new File(baseDir, "dfs-map.dat");
            singleTable = new DsHashMap(file);
            return singleTable;
        }
    }

    private DsHashMap openShardedTable(int tableId) {
        DsHashMap[] tables = shardedTables;
        if (tables == null) {
            synchronized (tableInitLock) {
                if (shardedTables == null) {
                    shardedTables = new DsHashMap[256];
                }
                tables = shardedTables;
            }
        }

        int tid = tableId & 0xFF;
        DsHashMap map = tables[tid];
        if (map != null) {
            return map;
        }
        synchronized (tableInitLock) {
            DsHashMap current = tables[tid];
            if (current != null) {
                return current;
            }
            File file = new File(baseDir, "dfs-map-t" + tid + ".dat");
            tables[tid] = new DsHashMap(file);
            return tables[tid];
        }
    }

    private void deleteAllTableFiles() {
        closeAllTables();
        for (int i = 0; i < 256; i++) {
            File file = new File(baseDir, "dfs-map-t" + i + ".dat");
            deleteWithSidecars(file);
        }
    }

    private void deleteSingleTableFiles() {
        DsHashMap map = singleTable;
        if (map != null) {
            map.close();
        }
        singleTable = null;
        deleteWithSidecars(new File(baseDir, "dfs-map.dat"));
    }

    private void closeAllTables() {
        DsHashMap[] tables = shardedTables;
        if (tables == null) {
            return;
        }
        for (int i = 0; i < tables.length; i++) {
            DsHashMap map = tables[i];
            if (map != null) {
                map.close();
                tables[i] = null;
            }
        }
        shardedTables = null;
    }

    private static void deleteWithSidecars(File file) {
        File[] files = new File[] {
            file,
            new File(file.getAbsolutePath() + ".e16"),
            new File(file.getAbsolutePath() + ".e16.next"),
            new File(file.getAbsolutePath() + ".e16.free"),
            new File(file.getAbsolutePath() + ".e16.free.tmp"),
            new File(file.getAbsolutePath() + ".e32"),
            new File(file.getAbsolutePath() + ".e32.next"),
            new File(file.getAbsolutePath() + ".e32.free"),
            new File(file.getAbsolutePath() + ".e32.free.tmp"),
            new File(file.getAbsolutePath() + ".e64"),
            new File(file.getAbsolutePath() + ".e64.next"),
            new File(file.getAbsolutePath() + ".e64.free"),
            new File(file.getAbsolutePath() + ".e64.free.tmp"),
            new File(file.getAbsolutePath() + ".m32"),
            new File(file.getAbsolutePath() + ".m64")
        };
        for (File current : files) {
            if (current.exists()) {
                current.delete();
            }
        }
    }

    private void fillHashBytesFromHash64(long key, byte[] out) {
        Arrays.fill(out, (byte) 0);
        long hash64 = key;
        out[0] = (byte) (hash64 >>> 56);
        out[1] = (byte) (hash64 >>> 48);
        out[2] = (byte) (hash64 >>> 40);
        out[3] = (byte) (hash64 >>> 32);
        out[4] = (byte) (hash64 >>> 24);
        out[5] = (byte) (hash64 >>> 16);
        out[6] = (byte) (hash64 >>> 8);
        out[7] = (byte) hash64;
    }

    private int ownerServerId(long key) {
        return (int) ((key >>> SERVER_HASH_SHIFT) & SERVER_ID_MASK);
    }

    private int tableId(long key) {
        return (int) (key & 0xFFL);
    }

    private static int[] orderedServers(int[] serverIds) {
        if (serverIds == null || serverIds.length == 0) {
            return new int[0];
        }
        int[] out = Arrays.copyOf(serverIds, serverIds.length);
        long[] ranked = new long[out.length];
        for (int i = 0; i < out.length; i++) {
            int id = out[i] & SERVER_ID_MASK;
            int rank = (id ^ 0x808) & SERVER_ID_MASK;
            ranked[i] = (((long) rank) << 32) | (id & 0xFFFF_FFFFL);
        }
        Arrays.sort(ranked);
        for (int i = 0; i < ranked.length; i++) {
            out[i] = (int) ranked[i];
        }
        return out;
    }

    private static long[] trim(long[] values, int size) {
        if (size == values.length) {
            return values;
        }
        return Arrays.copyOf(values, size);
    }

    private static int[] buildSignedAscTables() {
        int[] out = new int[256];
        int index = 0;
        for (int i = 128; i < 256; i++) {
            out[index++] = i;
        }
        for (int i = 0; i < 128; i++) {
            out[index++] = i;
        }
        return out;
    }
}
