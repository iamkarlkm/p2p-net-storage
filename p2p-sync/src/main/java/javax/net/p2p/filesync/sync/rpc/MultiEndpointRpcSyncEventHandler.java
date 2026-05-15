package javax.net.p2p.filesync.sync.rpc;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.p2p.filesync.sync.FileSyncAcker;
import javax.net.p2p.filesync.sync.FileSyncEventHandler;
import javax.net.p2p.filesync.sync.FileSyncEventType;
import javax.net.p2p.filesync.sync.transport.P2PFallbackConnector;
import javax.net.p2p.filesync.sync.transport.P2PTransport;
import javax.net.p2p.filesync.sync.transport.P2PTransportClient;
import javax.net.p2p.rpc.api.RpcClient;
import javax.net.p2p.rpc.client.P2PRpcClient;
import javax.net.p2p.utils.P2PUDPUtils;
import javax.net.p2p.utils.P2PUtils;

public final class MultiEndpointRpcSyncEventHandler implements FileSyncEventHandler, AutoCloseable {

    private final long taskId;
    private final List<EndpointClient> clients;
    private final AtomicInteger rr = new AtomicInteger(ThreadLocalRandom.current().nextInt());

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
            var fileClient = transportClient.getTransport() == P2PTransport.UDP
                ? new P2PUDPUtils(transportClient.getMessageService())
                : new P2PUtils(transportClient.getMessageService());
            RpcSyncEventHandler handler = new RpcSyncEventHandler(rpc, fileClient, taskId);
            clients.add(new EndpointClient(ep, transportClient, handler));
        }
    }

    @Override
    public void handle(FileSyncEventType type, long fileId, String relativePath, Path absolutePath, boolean directory, FileSyncAcker acker) {
        int idx = Math.floorMod(rr.getAndIncrement(), clients.size());
        clients.get(idx).handler.handle(type, fileId, relativePath, absolutePath, directory, acker);
    }

    @Override
    public void close() {
        for (EndpointClient c : clients) {
            try {
                c.transportClient.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class EndpointClient {
        private final InetSocketAddress endpoint;
        private final P2PTransportClient transportClient;
        private final RpcSyncEventHandler handler;

        private EndpointClient(InetSocketAddress endpoint, P2PTransportClient transportClient, RpcSyncEventHandler handler) {
            this.endpoint = endpoint;
            this.transportClient = transportClient;
            this.handler = handler;
        }
    }
}
