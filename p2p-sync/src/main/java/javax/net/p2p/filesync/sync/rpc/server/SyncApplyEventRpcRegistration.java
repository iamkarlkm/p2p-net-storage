package javax.net.p2p.filesync.sync.rpc.server;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.p2p.filesync.config.P2PSyncConfig;
import javax.net.p2p.rpc.server.RpcBootstrap;
import javax.net.p2p.rpc.server.SyncRpcServices;
import javax.net.p2p.rpc.model.RpcRequestContext;
import javax.net.p2p.rpc.sync.proto.SyncFinalizeRequest;
import javax.net.p2p.rpc.sync.proto.SyncEventAck;
import javax.net.p2p.rpc.sync.proto.SyncEventRequest;
import javax.net.p2p.storage.SharedStorage;

public final class SyncApplyEventRpcRegistration {

    private static final AtomicBoolean HANDLERS_REGISTERED = new AtomicBoolean(false);
    private static final Map<Integer, SyncReceiverRpcService> SERVICES_BY_PORT = new ConcurrentHashMap<Integer, SyncReceiverRpcService>();

    private SyncApplyEventRpcRegistration() {
    }

    public static AutoCloseable register(P2PSyncConfig config) {
        Objects.requireNonNull(config, "config");
        Path rootDir = Paths.get(config.getLocalDir()).toAbsolutePath().normalize();
        Path dsHome = config.getDsHome() == null || config.getDsHome().trim().isEmpty()
            ? rootDir.resolve(".p2p-sync").resolve("task-" + config.getTaskId()).toAbsolutePath().normalize()
            : Paths.get(config.getDsHome()).toAbsolutePath().normalize();

        int storeId = config.getStoreId();
        int listenPort = config.getListenPort();
        if (listenPort <= 0) {
            throw new IllegalArgumentException("listenPort is required");
        }
        SharedStorage.registerStorageLocation(storeId, rootDir.toFile());

        SyncReceiverStateStore stateStore = new SyncReceiverStateStore(dsHome.resolve("receiver"), config.getReceiverPendingExpireMillis());
        SyncEventApplier applier = new SyncEventApplier(rootDir);
        SyncReceiverRpcService service = new SyncReceiverRpcService(storeId, rootDir, stateStore, applier, config.getConflictPolicy());
        SyncReceiverRpcService previous = SERVICES_BY_PORT.putIfAbsent(Integer.valueOf(listenPort), service);
        if (previous != null) {
            try {
                stateStore.close();
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("sync receiver already registered on port " + listenPort);
        }
        ensureHandlersRegistered();

        return () -> {
            SERVICES_BY_PORT.remove(Integer.valueOf(listenPort), service);
            stateStore.close();
        };
    }

    private static void ensureHandlersRegistered() {
        if (!HANDLERS_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        RpcBootstrap.registerUnary(SyncRpcServices.SYNC_SERVICE, SyncRpcServices.APPLY_EVENT, "v1", true, SyncEventRequest.class, SyncEventAck.class,
            (ctx, req) -> resolveService(ctx).applyEvent(req));

        RpcBootstrap.registerUnary(SyncRpcServices.SYNC_SERVICE, SyncRpcServices.FINALIZE_EVENT, "v1", true, SyncFinalizeRequest.class, SyncEventAck.class,
            (ctx, req) -> resolveService(ctx).finalizeEvent(req));
    }

    private static SyncReceiverRpcService resolveService(RpcRequestContext context) {
        int localPort = resolveLocalPort(context);
        SyncReceiverRpcService service = SERVICES_BY_PORT.get(Integer.valueOf(localPort));
        if (service != null) {
            return service;
        }
        throw new IllegalStateException("sync receiver not registered on port " + localPort);
    }

    private static int resolveLocalPort(RpcRequestContext context) {
        if (context == null || context.channel() == null) {
            throw new IllegalStateException("rpc context channel is missing");
        }
        SocketAddress address = context.channel().localAddress();
        if (address instanceof InetSocketAddress) {
            return ((InetSocketAddress) address).getPort();
        }
        throw new IllegalStateException("unsupported local address: " + address);
    }
}
