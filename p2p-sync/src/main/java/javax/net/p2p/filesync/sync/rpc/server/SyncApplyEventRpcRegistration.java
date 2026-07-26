package javax.net.p2p.filesync.sync.rpc.server;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import javax.net.p2p.filesync.config.P2PSyncConfig;
import javax.net.p2p.rpc.server.RpcBootstrap;
import javax.net.p2p.rpc.server.SyncRpcServices;
import javax.net.p2p.rpc.sync.proto.SyncFinalizeRequest;
import javax.net.p2p.rpc.sync.proto.SyncEventAck;
import javax.net.p2p.rpc.sync.proto.SyncEventRequest;
import javax.net.p2p.storage.SharedStorage;

public final class SyncApplyEventRpcRegistration {

    private SyncApplyEventRpcRegistration() {
    }

    public static AutoCloseable register(P2PSyncConfig config) {
        Objects.requireNonNull(config, "config");
        Path rootDir = Paths.get(config.getLocalDir()).toAbsolutePath().normalize();
        Path dsHome = config.getDsHome() == null || config.getDsHome().trim().isEmpty()
            ? rootDir.resolve(".p2p-sync").resolve("task-" + config.getTaskId()).toAbsolutePath().normalize()
            : Paths.get(config.getDsHome()).toAbsolutePath().normalize();

        int storeId = config.getStoreId();
        SharedStorage.registerStorageLocation(storeId, rootDir.toFile());

        SyncReceiverStateStore stateStore = new SyncReceiverStateStore(dsHome.resolve("receiver"));
        SyncEventApplier applier = new SyncEventApplier(rootDir);
        SyncReceiverRpcService service = new SyncReceiverRpcService(storeId, rootDir, stateStore, applier);

        RpcBootstrap.registerUnary(SyncRpcServices.SYNC_SERVICE, SyncRpcServices.APPLY_EVENT, "v1", true, SyncEventRequest.class, SyncEventAck.class,
            (ctx, req) -> service.applyEvent(req));

        RpcBootstrap.registerUnary(SyncRpcServices.SYNC_SERVICE, SyncRpcServices.FINALIZE_EVENT, "v1", true, SyncFinalizeRequest.class, SyncEventAck.class,
            (ctx, req) -> service.finalizeEvent(req));

        return stateStore;
    }
}
