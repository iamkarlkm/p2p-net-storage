package javax.net.p2p.filesync.sync.rpc;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.p2p.client.P2PClientTcp;
import javax.net.p2p.filesync.sync.FileSyncAcker;
import javax.net.p2p.filesync.sync.FileSyncEventHandler;
import javax.net.p2p.filesync.sync.FileSyncEventType;
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
    private final long taskId;
    private final List<EndpointClient> clients;

    public MultiEndpointRpcSyncEventHandler(long taskId, List<InetSocketAddress> endpoints) {
        this.taskId = taskId;
        Objects.requireNonNull(endpoints, "endpoints");
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints is empty");
        }
        this.clients = new ArrayList<>(endpoints.size());
        for (InetSocketAddress ep : endpoints) {
            P2PTransportClient transportClient = P2PFallbackConnector.connect(ep);
            RpcClient rpc = new P2PRpcClient(transportClient.getMessageService());
            P2PFileService fileClient = transportClient.getTransport() == P2PTransport.TCP
                ? new P2PUtils((P2PClientTcp) transportClient.getMessageService())
                : new P2PUDPUtils(transportClient.getMessageService());
            RpcSyncEventHandler handler = new RpcSyncEventHandler(rpc, fileClient, taskId);
            clients.add(new EndpointClient(endpointLabel(ep), ep, transportClient, handler));
        }
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
    }

    @Override
    public void handle(FileSyncEventType type, long fileId, String relativePath, Path absolutePath, boolean directory, FileSyncAcker acker) {
        Objects.requireNonNull(acker, "acker");
        AtomicInteger remaining = new AtomicInteger(clients.size());
        AtomicReference<AggregateResult> result = new AtomicReference<>(new AggregateResult(AggregateAction.ACK, ""));
        for (EndpointClient client : clients) {
            try {
                client.handler.handle(type, fileId, relativePath, absolutePath, directory, new FileSyncAcker() {
                    @Override
                    public void ack() {
                        finish(AggregateAction.ACK, "");
                    }

                    @Override
                    public void retry() {
                        finish(AggregateAction.RETRY, "");
                    }

                    @Override
                    public void fail(String reason) {
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
                            acker.ack();
                        }
                    }
                });
            } catch (Exception e) {
                result.getAndUpdate(prev -> choose(prev, AggregateAction.RETRY, decorateReason(client.label, e.getMessage())));
                if (remaining.decrementAndGet() == 0) {
                    AggregateResult finalResult = result.get();
                    if (finalResult.action == AggregateAction.FAIL) {
                        acker.fail(finalResult.reason);
                    } else if (finalResult.action == AggregateAction.RETRY) {
                        acker.retry();
                    } else {
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
