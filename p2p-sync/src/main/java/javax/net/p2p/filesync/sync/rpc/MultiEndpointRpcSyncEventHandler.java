package javax.net.p2p.filesync.sync.rpc;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.p2p.client.P2PClientTcp;
import javax.net.p2p.filesync.sync.FileSyncAcker;
import javax.net.p2p.filesync.sync.FileSyncEventHandler;
import javax.net.p2p.filesync.sync.FileSyncEventType;
import javax.net.p2p.filesync.sync.P2PSyncStateStore;
import javax.net.p2p.filesync.sync.SyncUploadStatus;
import javax.net.p2p.filesync.sync.SyncUploadStatusProvider;
import javax.net.p2p.filesync.sync.transport.P2PFallbackConnector;
import javax.net.p2p.filesync.sync.transport.P2PTransport;
import javax.net.p2p.filesync.sync.transport.P2PTransportClient;
import javax.net.p2p.interfaces.P2PFileService;
import javax.net.p2p.rpc.api.RpcClient;
import javax.net.p2p.rpc.client.P2PRpcClient;
import javax.net.p2p.utils.P2PUDPUtils;
import javax.net.p2p.utils.P2PUtils;

public final class MultiEndpointRpcSyncEventHandler implements FileSyncEventHandler, SyncUploadStatusProvider, AutoCloseable {

    private static final String WRITE_CONFLICT = "write_conflict";
    private final long taskId;
    private final List<EndpointClient> clients;
    private final Map<EventKey, DeliveryState> deliveryStates;
    private volatile P2PSyncStateStore stateStore;

    public MultiEndpointRpcSyncEventHandler(long taskId, List<InetSocketAddress> endpoints) {
        Objects.requireNonNull(endpoints, "endpoints");
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints is empty");
        }
        this.taskId = taskId;
        this.clients = buildClients(taskId, endpoints);
        this.deliveryStates = new ConcurrentHashMap<>();
    }

    public static MultiEndpointRpcSyncEventHandler forHandlers(long taskId, List<FileSyncEventHandler> handlers) {
        Objects.requireNonNull(handlers, "handlers");
        if (handlers.isEmpty()) {
            throw new IllegalArgumentException("handlers is empty");
        }
        List<EndpointClient> clients = new ArrayList<>(handlers.size());
        int idx = 0;
        for (FileSyncEventHandler handler : handlers) {
            idx++;
            clients.add(new EndpointClient("handler-" + idx, null, null, Objects.requireNonNull(handler, "handler")));
        }
        return new MultiEndpointRpcSyncEventHandler(taskId, clients, true);
    }

    private MultiEndpointRpcSyncEventHandler(long taskId, List<EndpointClient> clients, boolean trusted) {
        this.taskId = taskId;
        this.clients = clients;
        this.deliveryStates = new ConcurrentHashMap<>();
    }

    @Override
    public void bindStateStore(P2PSyncStateStore store) {
        this.stateStore = store;
    }

    @Override
    public void handle(FileSyncEventType type, long fileId, String relativePath, Path absolutePath, boolean directory, FileSyncAcker acker) {
        dispatchToAll(type, fileId, relativePath, absolutePath, null, directory, acker);
    }

    @Override
    public void handleRename(FileSyncEventType type, long targetFileId, String targetRelativePath, Path targetAbsolutePath,
                             String sourceRelativePath, boolean directory, FileSyncAcker acker) {
        Objects.requireNonNull(acker, "acker");
        dispatchToAll(type, targetFileId, targetRelativePath, targetAbsolutePath, sourceRelativePath, directory, acker);
    }

    private void dispatchToAll(FileSyncEventType type, long fileId, String relativePath, Path absolutePath,
                               String sourceRelativePath, boolean directory, FileSyncAcker acker) {
        Objects.requireNonNull(acker, "acker");
        EventKey eventKey = new EventKey(type, directory, fileId);
        DeliveryState deliveryState = deliveryStates.computeIfAbsent(eventKey, key -> loadDeliveryState(type, directory, fileId));
        List<PendingDispatch> pending = pendingDispatches(deliveryState);
        if (pending.isEmpty()) {
            deliveryStates.remove(eventKey, deliveryState);
            clearReplicaStates(type, directory, fileId);
            acker.ack();
            return;
        }
        AtomicInteger remaining = new AtomicInteger(pending.size());
        AtomicReference<AggregateResult> result = new AtomicReference<>(new AggregateResult(AggregateAction.ACK, ""));
        for (PendingDispatch dispatch : pending) {
            EndpointClient client = dispatch.client;
            try {
                FileSyncAcker nested = new FileSyncAcker() {
                    @Override
                    public void ack() {
                        deliveryState.markStatus(dispatch.index, P2PSyncStateStore.REPLICA_ACKED);
                        markReplicaState(type, directory, fileId, client.label, P2PSyncStateStore.REPLICA_ACKED);
                        finish(AggregateAction.ACK, "");
                    }

                    @Override
                    public void retry() {
                        deliveryState.markStatus(dispatch.index, P2PSyncStateStore.REPLICA_RETRY);
                        markReplicaState(type, directory, fileId, client.label, P2PSyncStateStore.REPLICA_RETRY);
                        finish(AggregateAction.RETRY, "");
                    }

                    @Override
                    public void fail(String reason) {
                        deliveryState.markStatus(dispatch.index, P2PSyncStateStore.REPLICA_FAILED);
                        markReplicaState(type, directory, fileId, client.label, P2PSyncStateStore.REPLICA_FAILED);
                        finish(AggregateAction.FAIL, decorateReason(client.label, reason));
                    }

                    private void finish(AggregateAction action, String reason) {
                        result.getAndUpdate(prev -> choose(prev, action, reason));
                        if (remaining.decrementAndGet() == 0) {
                            AggregateResult finalResult = result.get();
                            if (finalResult.action == AggregateAction.FAIL) {
                                acker.fail(finalResult.reason);
                                return;
                            }
                            if (finalResult.action == AggregateAction.RETRY) {
                                acker.retry();
                                return;
                            }
                            if (deliveryState.hasOutstandingReplicas()) {
                                acker.fail(deliveryState.outstandingReason(clients));
                                return;
                            }
                            deliveryStates.remove(eventKey, deliveryState);
                            clearReplicaStates(type, directory, fileId);
                            acker.ack();
                        }
                    }
                };
                if (type.isRenameKind()) {
                    client.handler.handleRename(type, fileId, relativePath, absolutePath, sourceRelativePath, directory, nested);
                } else {
                    client.handler.handle(type, fileId, relativePath, absolutePath, directory, nested);
                }
            } catch (Exception e) {
                deliveryState.markStatus(dispatch.index, P2PSyncStateStore.REPLICA_RETRY);
                markReplicaState(type, directory, fileId, client.label, P2PSyncStateStore.REPLICA_RETRY);
                result.getAndUpdate(prev -> choose(prev, AggregateAction.RETRY, decorateReason(client.label, e.getMessage())));
                if (remaining.decrementAndGet() == 0) {
                    AggregateResult finalResult = result.get();
                    if (finalResult.action == AggregateAction.FAIL) {
                        acker.fail(finalResult.reason);
                    } else if (finalResult.action == AggregateAction.RETRY) {
                        acker.retry();
                    } else {
                        if (deliveryState.hasOutstandingReplicas()) {
                            acker.fail(deliveryState.outstandingReason(clients));
                            return;
                        }
                        deliveryStates.remove(eventKey, deliveryState);
                        clearReplicaStates(type, directory, fileId);
                        acker.ack();
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        for (EndpointClient c : clients) {
            try {
                if (c.handler instanceof AutoCloseable) {
                    ((AutoCloseable) c.handler).close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (c.transportClient != null) {
                    c.transportClient.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public List<SyncUploadStatus> snapshotActiveUploads(int limit) {
        return aggregateUploadStatuses(limit, UploadStatusSource.ACTIVE);
    }

    @Override
    public List<SyncUploadStatus> snapshotRecentCompletedUploads(int limit) {
        return aggregateUploadStatuses(limit, UploadStatusSource.COMPLETED);
    }

    @Override
    public List<SyncUploadStatus> snapshotRecentFailedUploads(int limit) {
        return aggregateUploadStatuses(limit, UploadStatusSource.FAILED);
    }

    private static AggregateResult choose(AggregateResult prev, AggregateAction nextAction, String nextReason) {
        if (prev.action.priority >= nextAction.priority) {
            return prev;
        }
        return new AggregateResult(nextAction, nextReason == null ? "" : nextReason);
    }

    private List<SyncUploadStatus> aggregateUploadStatuses(int limit, UploadStatusSource source) {
        if (limit <= 0) {
            return new ArrayList<SyncUploadStatus>();
        }
        List<SyncUploadStatus> merged = new ArrayList<SyncUploadStatus>();
        for (EndpointClient client : clients) {
            if (!(client.handler instanceof SyncUploadStatusProvider)) {
                continue;
            }
            SyncUploadStatusProvider provider = (SyncUploadStatusProvider) client.handler;
            List<SyncUploadStatus> statuses = source.snapshot(provider, limit);
            for (SyncUploadStatus status : statuses) {
                merged.add(withReplicaLabel(status, client.label));
            }
        }
        merged.sort(Comparator.comparingLong(SyncUploadStatus::getUpdatedAtMillis).reversed());
        if (merged.size() <= limit) {
            return merged;
        }
        return new ArrayList<SyncUploadStatus>(merged.subList(0, limit));
    }

    private static SyncUploadStatus withReplicaLabel(SyncUploadStatus status, String label) {
        if (status == null) {
            return null;
        }
        String safeLabel = label == null ? "" : label.trim();
        return new SyncUploadStatus(
            status.getEventUid(),
            status.getFileId(),
            status.getPath(),
            status.getPhase(),
            status.getFileSize(),
            status.isSegmented(),
            status.getTotalSegments(),
            status.getUploadedSegments(),
            status.getStartedAtMillis(),
            status.getUpdatedAtMillis(),
            status.getLastProgressAtMillis(),
            status.getResumedSegments(),
            safeLabel,
            status.getMessage());
    }

    private static List<EndpointClient> buildClients(long taskId, List<InetSocketAddress> endpoints) {
        List<EndpointClient> clients = new ArrayList<>(endpoints.size());
        for (InetSocketAddress ep : endpoints) {
            P2PTransportClient transportClient = P2PFallbackConnector.connect(ep);
            RpcClient rpc = new P2PRpcClient(transportClient.getMessageService());
            P2PFileService fileClient = transportClient.getTransport() == P2PTransport.TCP
                ? new P2PUtils((P2PClientTcp) transportClient.getMessageService())
                : new P2PUDPUtils(transportClient.getMessageService());
            RpcSyncEventHandler handler = new RpcSyncEventHandler(rpc, fileClient, taskId);
            clients.add(new EndpointClient(endpointLabel(ep), ep, transportClient, handler));
        }
        return clients;
    }

    private DeliveryState loadDeliveryState(FileSyncEventType type, boolean directory, long fileId) {
        DeliveryState state = new DeliveryState(clients.size());
        P2PSyncStateStore store = stateStore;
        if (store == null) {
            return state;
        }
        for (P2PSyncStateStore.ReplicaState replicaState : store.getReplicaStates(type, directory, fileId)) {
            markStatusByLabel(state, replicaState.getLabel(), replicaState.getStatus());
        }
        return state;
    }

    private void markStatusByLabel(DeliveryState state, String label, String status) {
        if (label == null || label.trim().isEmpty()) {
            return;
        }
        for (int i = 0; i < clients.size(); i++) {
            EndpointClient client = clients.get(i);
            if (label.equals(client.label)) {
                state.markStatus(i, status);
                return;
            }
        }
    }

    private List<PendingDispatch> pendingDispatches(DeliveryState deliveryState) {
        List<PendingDispatch> pending = new ArrayList<>(clients.size());
        boolean targeted = deliveryState.hasTargetedReplicas();
        for (int i = 0; i < clients.size(); i++) {
            if (deliveryState.shouldDispatch(i, targeted)) {
                pending.add(new PendingDispatch(i, clients.get(i)));
            }
        }
        return pending;
    }

    private void markReplicaState(FileSyncEventType type, boolean directory, long fileId, String label, String status) {
        P2PSyncStateStore store = stateStore;
        if (store == null) {
            return;
        }
        store.markReplicaState(type, directory, fileId, label, status);
    }

    private void clearReplicaStates(FileSyncEventType type, boolean directory, long fileId) {
        P2PSyncStateStore store = stateStore;
        if (store == null) {
            return;
        }
        store.clearReplicaStates(type, directory, fileId);
    }

    private static String decorateReason(String label, String reason) {
        String safeReason = reason == null ? "" : reason.trim();
        if (safeReason.isEmpty() || WRITE_CONFLICT.equals(safeReason) || safeReason.startsWith(WRITE_CONFLICT + ":")) {
            return safeReason;
        }
        if (label == null || label.trim().isEmpty()) {
            return safeReason;
        }
        return safeReason + " [replica=" + label + "]";
    }

    private static String endpointLabel(InetSocketAddress endpoint) {
        if (endpoint == null) {
            return "";
        }
        return endpoint.getHostString() + ":" + endpoint.getPort();
    }

    private enum AggregateAction {
        ACK(0),
        RETRY(1),
        FAIL(2);

        private final int priority;

        AggregateAction(int priority) {
            this.priority = priority;
        }
    }

    private enum UploadStatusSource {
        ACTIVE {
            @Override
            List<SyncUploadStatus> snapshot(SyncUploadStatusProvider provider, int limit) {
                return provider.snapshotActiveUploads(limit);
            }
        },
        COMPLETED {
            @Override
            List<SyncUploadStatus> snapshot(SyncUploadStatusProvider provider, int limit) {
                return provider.snapshotRecentCompletedUploads(limit);
            }
        },
        FAILED {
            @Override
            List<SyncUploadStatus> snapshot(SyncUploadStatusProvider provider, int limit) {
                return provider.snapshotRecentFailedUploads(limit);
            }
        };

        abstract List<SyncUploadStatus> snapshot(SyncUploadStatusProvider provider, int limit);
    }

    private static final class AggregateResult {
        private final AggregateAction action;
        private final String reason;

        private AggregateResult(AggregateAction action, String reason) {
            this.action = action;
            this.reason = reason;
        }
    }

    private static final class PendingDispatch {
        private final int index;
        private final EndpointClient client;

        private PendingDispatch(int index, EndpointClient client) {
            this.index = index;
            this.client = client;
        }
    }

    private static final class DeliveryState {
        private final String[] statuses;

        private DeliveryState(int size) {
            this.statuses = new String[size];
        }

        private synchronized void markStatus(int index, String status) {
            statuses[index] = status;
        }

        private synchronized boolean hasTargetedReplicas() {
            for (String status : statuses) {
                if (P2PSyncStateStore.REPLICA_TARGETED.equals(status)) {
                    return true;
                }
            }
            return false;
        }

        private synchronized boolean shouldDispatch(int index, boolean targetedOnly) {
            String status = statuses[index];
            if (P2PSyncStateStore.isReplicaSatisfied(status)) {
                return false;
            }
            if (!targetedOnly) {
                return true;
            }
            return P2PSyncStateStore.REPLICA_TARGETED.equals(status);
        }

        private synchronized boolean hasOutstandingReplicas() {
            for (String status : statuses) {
                if (status != null && !status.isEmpty() && !P2PSyncStateStore.isReplicaSatisfied(status)) {
                    return true;
                }
            }
            return false;
        }

        private synchronized String outstandingReason(List<EndpointClient> clients) {
            StringBuilder sb = new StringBuilder("replicas_pending:");
            boolean first = true;
            for (int i = 0; i < statuses.length; i++) {
                String status = statuses[i];
                if (status == null || status.isEmpty() || P2PSyncStateStore.isReplicaSatisfied(status)) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(clients.get(i).label);
                sb.append('=');
                sb.append(status);
            }
            return first ? "replicas_pending" : sb.toString();
        }
    }

    private static final class EventKey {
        private final FileSyncEventType type;
        private final boolean directory;
        private final long fileId;

        private EventKey(FileSyncEventType type, boolean directory, long fileId) {
            this.type = type;
            this.directory = directory;
            this.fileId = fileId;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof EventKey)) {
                return false;
            }
            EventKey other = (EventKey) obj;
            return directory == other.directory
                && fileId == other.fileId
                && type == other.type;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, Boolean.valueOf(directory), Long.valueOf(fileId));
        }
    }

    private static final class EndpointClient {
        private final String label;
        private final InetSocketAddress endpoint;
        private final P2PTransportClient transportClient;
        private final FileSyncEventHandler handler;

        private EndpointClient(String label, InetSocketAddress endpoint, P2PTransportClient transportClient, FileSyncEventHandler handler) {
            this.label = label;
            this.endpoint = endpoint;
            this.transportClient = transportClient;
            this.handler = handler;
        }
    }
}
