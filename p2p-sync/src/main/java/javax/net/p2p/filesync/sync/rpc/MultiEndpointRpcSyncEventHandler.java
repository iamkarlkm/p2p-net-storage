package javax.net.p2p.filesync.sync.rpc;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
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
import javax.net.p2p.filesync.sync.transport.P2PFallbackConnector;
import javax.net.p2p.filesync.sync.transport.P2PTransport;
import javax.net.p2p.filesync.sync.transport.P2PTransportClient;
import javax.net.p2p.interfaces.P2PFileService;
import javax.net.p2p.rpc.api.RpcClient;
import javax.net.p2p.rpc.client.P2PRpcClient;
import javax.net.p2p.utils.P2PUDPUtils;
import javax.net.p2p.utils.P2PUtils;

public final class MultiEndpointRpcSyncEventHandler implements FileSyncEventHandler, AutoCloseable {

    private static final String WRITE_CONFLICT = "write_conflict";
    private static final String REPLICA_ACKED = "ACKED";
    private static final String REPLICA_RETRY = "RETRY";
    private static final String REPLICA_FAILED = "FAILED";
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
                client.handler.handle(type, fileId, relativePath, absolutePath, directory, new FileSyncAcker() {
                    @Override
                    public void ack() {
                        deliveryState.markAcked(dispatch.index);
                        markReplicaState(type, directory, fileId, client.label, REPLICA_ACKED);
                        finish(AggregateAction.ACK, "");
                    }

                    @Override
                    public void retry() {
                        markReplicaState(type, directory, fileId, client.label, REPLICA_RETRY);
                        finish(AggregateAction.RETRY, "");
                    }

                    @Override
                    public void fail(String reason) {
                        markReplicaState(type, directory, fileId, client.label, REPLICA_FAILED);
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
                            deliveryStates.remove(eventKey, deliveryState);
                            clearReplicaStates(type, directory, fileId);
                            acker.ack();
                        }
                    }
                });
            } catch (Exception e) {
                markReplicaState(type, directory, fileId, client.label, REPLICA_RETRY);
                result.getAndUpdate(prev -> choose(prev, AggregateAction.RETRY, decorateReason(client.label, e.getMessage())));
                if (remaining.decrementAndGet() == 0) {
                    AggregateResult finalResult = result.get();
                    if (finalResult.action == AggregateAction.FAIL) {
                        acker.fail(finalResult.reason);
                    } else if (finalResult.action == AggregateAction.RETRY) {
                        acker.retry();
                    } else {
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

    private static AggregateResult choose(AggregateResult prev, AggregateAction nextAction, String nextReason) {
        if (prev.action.priority >= nextAction.priority) {
            return prev;
        }
        return new AggregateResult(nextAction, nextReason == null ? "" : nextReason);
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
            if (REPLICA_ACKED.equals(replicaState.getStatus())) {
                markAckedByLabel(state, replicaState.getLabel());
            }
        }
        return state;
    }

    private void markAckedByLabel(DeliveryState state, String label) {
        if (label == null || label.trim().isEmpty()) {
            return;
        }
        for (int i = 0; i < clients.size(); i++) {
            EndpointClient client = clients.get(i);
            if (label.equals(client.label)) {
                state.markAcked(i);
                return;
            }
        }
    }

    private List<PendingDispatch> pendingDispatches(DeliveryState deliveryState) {
        List<PendingDispatch> pending = new ArrayList<>(clients.size());
        for (int i = 0; i < clients.size(); i++) {
            if (!deliveryState.isAcked(i)) {
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
        private final boolean[] acked;

        private DeliveryState(int size) {
            this.acked = new boolean[size];
        }

        private synchronized boolean isAcked(int index) {
            return acked[index];
        }

        private synchronized void markAcked(int index) {
            acked[index] = true;
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
