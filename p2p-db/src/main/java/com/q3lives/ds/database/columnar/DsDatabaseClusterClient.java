package com.q3lives.ds.database.columnar;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.cluster.DsNodeRouter;
import com.q3lives.ds.cluster.DsPartitionRouter;
import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.collections.DsHashMapIdAllocator;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.columnar.pushdown.DbQueryToQueryWrapper;
import com.q3lives.ds.database.schema.DynamicIndexUtil;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import com.q3lives.ds.annotation.DsTable;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.P2PClientTcp;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.model.DbColGetRequest;
import javax.net.p2p.model.DbColGetResponse;
import javax.net.p2p.model.DbColPutRequest;
import javax.net.p2p.model.DbColPutResponse;
import javax.net.p2p.model.DbColRemoveRequest;
import javax.net.p2p.model.DbColRemoveResponse;
import javax.net.p2p.model.DbNodeQueryIdsRequest;
import javax.net.p2p.model.DbNodeReplicateAck;
import javax.net.p2p.model.DbNodeReplicateFrame;
import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbRowQueryIdsResponse;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.utils.SerializationUtil;

/**
 * 分布式数据库集群客户端。
 *
 * <p>
 * 持有本地节点信息、分区管理器与 rowId 分配器。对本地 rowId 直接调用 {@link ColumnarStore}；对远程 rowId 通过
 * {@link DsNodeRouter} 解析 endpoint， 复用 {@link P2PClientTcp} 发送 DB_NODE_*
 * 命令。</p>
 */
public class DsDatabaseClusterClient implements AutoCloseable {

    private final int localServerId;
    private final DsPartitionManager localManager;
    private final DsHashMapIdAllocator idAllocator;
    private final DsNodeRouter nodeRouter;
    private final ConcurrentHashMap<Integer, P2PClientTcp> clients = new ConcurrentHashMap<>();

    public DsDatabaseClusterClient(
            int localServerId,
            DsPartitionManager localManager,
            DsHashMapIdAllocator idAllocator,
            DsServerDirectory directory
    ) {
        this.localServerId = localServerId;
        this.localManager = Objects.requireNonNull(localManager, "localManager cannot be null");
        this.idAllocator = Objects.requireNonNull(idAllocator, "idAllocator cannot be null");
        this.nodeRouter = new DsNodeRouter(Objects.requireNonNull(directory, "directory cannot be null"));
    }

    public int getLocalServerId() {
        return localServerId;
    }

    public DsPartitionManager getLocalManager() {
        return localManager;
    }

    public DsNodeRouter getNodeRouter() {
        return nodeRouter;
    }

    /**
     * 分配一个目标节点与分区的全局 rowId。
     */
    public long allocateRowId(int serverId, int partitionId) throws IOException {
        return idAllocator.allocateRowId(serverId, partitionId);
    }

    private boolean isLocal(long rowId) {
        return DsIdLayout.serverId(rowId) == localServerId;
    }

    private DsHashSet idsSet(String entityClassName, long rowId) throws IOException {
        ColumnarStore store = localManager.getStore(rowId);
        return new DsHashSet(DynamicIndexUtil.idsFile(store.getDbRoot(), entityClassName));
    }

    /**
     * 写入列值；若 upsertRow 为 true 则将该 rowId 加入 ids 集合。
     */
    public long putCol(String entityClassName, long rowId, String logicalName, byte[] valueBytes, boolean upsertRow) throws IOException {
        if (isLocal(rowId)) {
            ColumnarStore store = localManager.getStore(rowId);
            if (upsertRow) {
                idsSet(entityClassName, rowId).add(rowId);
            }
            long valueId = store.putValue(entityClassName, logicalName, rowId, valueBytes);
            replicateToReplicas(entityClassName, rowId, logicalName, valueBytes, upsertRow, false);
            return valueId;
        }
        P2PWrapper resp = executeRemote(rowId, P2PCommand.DB_NODE_PUT,
                new DbColPutRequest(entityClassName, rowId, logicalName, valueBytes, upsertRow));
        if (resp.getCommand() == P2PCommand.STD_ERROR) {
            throw P2PErrors.asRuntimeException(resp);
        }
        if (resp.getCommand() != P2PCommand.R_OK_DB_NODE_PUT || !(resp.getData() instanceof DbColPutResponse ok)) {
            throw new IOException("unexpected response: " + resp.getCommand());
        }
        replicateToReplicas(entityClassName, rowId, logicalName, valueBytes, upsertRow, false);
        return ok.valueId;
    }

    public byte[] getCol(String entityClassName, long rowId, String logicalName) throws IOException {
        if (isLocal(rowId)) {
            ColumnarStore store = localManager.getStore(rowId);
            return store.getValue(entityClassName, logicalName, rowId);
        }
        P2PWrapper resp = executeRemote(rowId, P2PCommand.DB_NODE_GET,
                new DbColGetRequest(entityClassName, rowId, logicalName));
        if (resp.getCommand() == P2PCommand.STD_ERROR) {
            throw P2PErrors.asRuntimeException(resp);
        }
        if (resp.getCommand() != P2PCommand.R_OK_DB_NODE_GET || !(resp.getData() instanceof DbColGetResponse ok)) {
            throw new IOException("unexpected response: " + resp.getCommand());
        }
        return ok.found ? ok.valueBytes : null;
    }

    public boolean removeCol(String entityClassName, long rowId, String logicalName) throws IOException {
        if (isLocal(rowId)) {
            ColumnarStore store = localManager.getStore(rowId);
            boolean removed = store.removeValue(entityClassName, logicalName, rowId);
            replicateToReplicas(entityClassName, rowId, logicalName, null, false, true);
            return removed;
        }
        P2PWrapper resp = executeRemote(rowId, P2PCommand.DB_NODE_REMOVE,
                new DbColRemoveRequest(entityClassName, rowId, logicalName));
        if (resp.getCommand() == P2PCommand.STD_ERROR) {
            throw P2PErrors.asRuntimeException(resp);
        }
        if (resp.getCommand() != P2PCommand.R_OK_DB_NODE_REMOVE || !(resp.getData() instanceof DbColRemoveResponse ok)) {
            throw new IOException("unexpected response: " + resp.getCommand());
        }
        replicateToReplicas(entityClassName, rowId, logicalName, null, false, true);
        return ok.removed;
    }

    /**
     * 将主节点写入同步 fan-out 到该 rowId 对应 serverId 的所有副本节点。
     */
    private void replicateToReplicas(String entityClassName, long rowId, String logicalName,
            byte[] valueBytes, boolean upsertRow, boolean remove) throws IOException {
        int primaryServerId = DsIdLayout.serverId(rowId);
        int[] replicas = nodeRouter.getDirectory().replicas(primaryServerId);
        if (replicas == null || replicas.length == 0) {
            return;
        }
        long localAddress = DsIdLayout.localAddress(rowId);
        int partitionId = DsIdLayout.partitionId(rowId);
        DbNodeReplicateFrame frame = new DbNodeReplicateFrame(entityClassName, rowId, logicalName, valueBytes, upsertRow, remove);
        Exception firstError = null;
        for (int replicaServerId : replicas) {
            if (replicaServerId == primaryServerId || replicaServerId == localServerId) {
                // 主节点自身或已经在本地写入的场景无需再次复制
                continue;
            }
            long replicaRowId = DsIdLayout.compose(replicaServerId, partitionId, localAddress);
            try {
                P2PWrapper resp = executeRemote(replicaRowId, P2PCommand.DB_NODE_REPLICATE_FRAME, frame);
                if (resp.getCommand() == P2PCommand.STD_ERROR) {
                    throw P2PErrors.asRuntimeException(resp);
                }
                if (resp.getCommand() != P2PCommand.DB_NODE_REPLICATE_ACK
                        || !(resp.getData() instanceof DbNodeReplicateAck ack) || !ack.ok) {
                    throw new IOException("replicate failed to server " + replicaServerId + ": " + resp.getCommand());
                }
            } catch (Exception e) {
                if (firstError == null) {
                    firstError = e;
                }
            }
        }
        if (firstError != null) {
            throw new IOException("replicate to replicas failed for rowId " + rowId, firstError);
        }
    }

    /**
     * 全集群查询：遍历所有 serverId 与本地分区，聚合后排序并分页返回 rowId 列表。
     *
     * <p>
     * 当前实现按 {@link DsServerDirectory#listServerIds()} 与
     * {@link DsPartitionRouter#maxPartitionCount()} 推导访问范围，对每个分区调用 单分区
     * queryIds，最后做全局排序与 offset/limit 截取。DbQuery 条件暂时透传， 由各分区本地 ids 集合过滤。</p>
     */
    public List<Long> queryIds(String entityClassName, DbQuery query, int offset, int limit) throws IOException {
        int[] serverIds = nodeRouter.getDirectory().listServerIds();
        if (serverIds == null || serverIds.length == 0) {
            return Collections.emptyList();
        }
        int partitionCount = DsPartitionRouter.maxPartitionCount();
        // 每个分区拉取足够多的候选 id，避免单分区结果过多导致全局聚合后分页错误。
        int perPartitionLimit = Math.max(limit + offset, 1000);
        ArrayList<Long> all = new ArrayList<>();
        for (int serverId : serverIds) {
            for (int partitionId = 0; partitionId < partitionCount; partitionId++) {
                long anchorRowId = DsIdLayout.compose(serverId, partitionId, 1L);
                all.addAll(queryIds(entityClassName, anchorRowId, query, 0, perPartitionLimit));
            }
        }
        if (all.isEmpty()) {
            return Collections.emptyList();
        }
        all.sort(null);
        int from = Math.min(Math.max(0, offset), all.size());
        int to = Math.min(from + Math.max(0, limit), all.size());
        return new ArrayList<>(all.subList(from, to));
    }

    /**
     * 查询指定分区内的 rowId 列表（anchorRowId 决定节点与分区）。
     *
     * <p>
     * P50 OQ12-㉘②：本地路径与远端一致地施加 {@code query} 谓词过滤（先过滤后分页）； {@code query} 为 null
     * 或无条件时全量透传（兼容旧行为）。远端分支照旧透传 {@code query}。
     */
    public List<Long> queryIds(String entityClassName, long anchorRowId, DbQuery query, int offset, int limit) throws IOException {
        if (isLocal(anchorRowId)) {
            ColumnarStore store = localManager.getStore(anchorRowId);
            DsHashSet ids = new DsHashSet(DynamicIndexUtil.idsFile(store.getDbRoot(), entityClassName));
            ArrayList<Long> list = new ArrayList<>();
            for (Long idObj : ids) {
                if (idObj == null || idObj <= 0L) {
                    continue;
                }
                list.add(idObj);
            }
            list.sort(null);
            // P50 谓词过滤下推（Q1=A）：先过滤后分页；无谓词则全量透传。
            list = new ArrayList<>(DbQueryToQueryWrapper.filterIdsByPredicate(entityClassName, query, list));
            int from = Math.min(Math.max(0, offset), list.size());
            int to = Math.min(from + (limit <= 0 ? 100 : limit), list.size());
            return new ArrayList<>(list.subList(from, to));
        }
        P2PWrapper resp = executeRemote(anchorRowId, P2PCommand.DB_NODE_QUERY_IDS,
                new DbNodeQueryIdsRequest(entityClassName, anchorRowId, query, offset, limit));
        if (resp.getCommand() == P2PCommand.STD_ERROR) {
            throw P2PErrors.asRuntimeException(resp);
        }
        if (resp.getCommand() != P2PCommand.R_OK_DB_NODE_QUERY_IDS || !(resp.getData() instanceof DbRowQueryIdsResponse ok) || ok.idsBytes == null) {
            throw new IOException("unexpected response: " + resp.getCommand());
        }
        long[] ids = SerializationUtil.deserialize(long[].class, ok.idsBytes);
        if (ids == null || ids.length == 0) {
            return Collections.emptyList();
        }
        List<Long> out = new ArrayList<>(ids.length);
        for (long v : ids) {
            if (v > 0L) {
                out.add(v);
            }
        }
        return out;
    }

    private P2PWrapper executeRemote(long rowId, P2PCommand command, Object payload) throws IOException {
        DsServerDirectory.Endpoint endpoint = nodeRouter.endpoint(rowId);
        if (endpoint == null) {
            throw new IOException("no endpoint for rowId: " + rowId);
        }
        P2PClientTcp client = clients.computeIfAbsent(DsIdLayout.serverId(rowId), k -> {
            P2PClientTcp c = new P2PClientTcp(new InetSocketAddress(endpoint.host(), endpoint.port()), endpoint.queueSize());
            c.newSendMesageExecutorToQueue();
            return c;
        });
        try {
            return client.excute(StreamP2PWrapper.buildStream(0, 0, command, payload));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public void close() {
        for (P2PClientTcp client : clients.values()) {
            try {
                client.close();
            } catch (Exception e) {
                // ignore
            }
        }
        clients.clear();
    }
}
