package javax.net.p2p.rpc.server;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.p2p.dfsmap.DfsMapBackend;
import javax.net.p2p.dfsmap.DfsMapRegistry;
import javax.net.p2p.dfsmap.model.DfsMapGetReq;
import javax.net.p2p.dfsmap.model.DfsMapGetResp;
import javax.net.p2p.dfsmap.model.DfsMapPutReq;
import javax.net.p2p.dfsmap.model.DfsMapPutResp;
import javax.net.p2p.dfsmap.model.DfsMapRangeReq;
import javax.net.p2p.dfsmap.model.DfsMapRangeResp;
import javax.net.p2p.dfsmap.model.DfsMapRemoveReq;
import javax.net.p2p.dfsmap.model.DfsMapRemoveResp;
import javax.net.p2p.dfsmap.model.DfsMapStatusCodes;
import javax.net.p2p.rpc.api.RpcServerStreamObserver;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapGetRequest;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapGetResponse;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapPutRequest;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapPutResponse;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapRangeItem;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapRangeRequest;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapRemoveRequest;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapRemoveResponse;
import javax.net.p2p.rpc.proto.RpcStatus;
import javax.net.p2p.rpc.proto.RpcStatusCode;

/**
 * DFS_MAP 现有能力的 RPC 适配层。
 */
public final class DfsMapRpcServices {

    public static final String SERVICE = "p2p.rpc.dfsmap.v1.DfsMapService";
    public static final String METHOD_GET = "Get";
    public static final String METHOD_PUT = "Put";
    public static final String METHOD_REMOVE = "Remove";
    public static final String METHOD_RANGE = "Range";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private DfsMapRpcServices() {
    }

    public static void registerDefaults() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        RpcBootstrap.registerUnary(
            SERVICE,
            METHOD_GET,
            "v1",
            true,
            DfsMapGetRequest.class,
            DfsMapGetResponse.class,
            (context, request) -> toProto(handleGet(request)),
            DfsMapRpcServices::resolveGetStatus
        );
        RpcBootstrap.registerUnary(
            SERVICE,
            METHOD_PUT,
            "v1",
            false,
            DfsMapPutRequest.class,
            DfsMapPutResponse.class,
            (context, request) -> toProto(handlePut(request)),
            DfsMapRpcServices::resolvePutStatus
        );
        RpcBootstrap.registerUnary(
            SERVICE,
            METHOD_REMOVE,
            "v1",
            false,
            DfsMapRemoveRequest.class,
            DfsMapRemoveResponse.class,
            (context, request) -> toProto(handleRemove(request)),
            DfsMapRpcServices::resolveRemoveStatus
        );
        RpcBootstrap.registerServerStream(
            SERVICE,
            METHOD_RANGE,
            "v1",
            true,
            DfsMapRangeRequest.class,
            DfsMapRangeItem.class,
            (context, request, observer) -> streamRange(request, observer)
        );
    }

    private static DfsMapGetResp handleGet(DfsMapGetRequest request) {
        DfsMapBackend backend = requireBackend();
        DfsMapGetReq req = new DfsMapGetReq();
        req.setApiVersion(request.getApiVersion());
        req.setEpoch(request.getEpoch());
        req.setKey(request.getKey());
        return backend.handleGet(req);
    }

    private static DfsMapGetResponse toProto(DfsMapGetResp response) {
        if (response == null) {
            return DfsMapGetResponse.newBuilder().build();
        }
        return DfsMapGetResponse.newBuilder()
            .setStatus(response.getStatus())
            .setEpoch(response.getEpoch())
            .setKey(response.getKey())
            .setFound(response.isFound())
            .setValue(response.getValue())
            .build();
    }

    private static DfsMapPutResp handlePut(DfsMapPutRequest request) {
        DfsMapBackend backend = requireBackend();
        DfsMapPutReq req = new DfsMapPutReq();
        req.setApiVersion(request.getApiVersion());
        req.setEpoch(request.getEpoch());
        req.setKey(request.getKey());
        req.setValue(request.getValue());
        req.setReturnOldValue(request.getReturnOldValue());
        return backend.handlePut(req);
    }

    private static DfsMapPutResponse toProto(DfsMapPutResp response) {
        if (response == null) {
            return DfsMapPutResponse.newBuilder().build();
        }
        return DfsMapPutResponse.newBuilder()
            .setStatus(response.getStatus())
            .setEpoch(response.getEpoch())
            .setKey(response.getKey())
            .setHadOld(response.isHadOld())
            .setOldValue(response.getOldValue())
            .build();
    }

    private static DfsMapRemoveResp handleRemove(DfsMapRemoveRequest request) {
        DfsMapBackend backend = requireBackend();
        DfsMapRemoveReq req = new DfsMapRemoveReq();
        req.setApiVersion(request.getApiVersion());
        req.setEpoch(request.getEpoch());
        req.setKey(request.getKey());
        req.setReturnOldValue(request.getReturnOldValue());
        return backend.handleRemove(req);
    }

    private static DfsMapRemoveResponse toProto(DfsMapRemoveResp response) {
        if (response == null) {
            return DfsMapRemoveResponse.newBuilder().build();
        }
        return DfsMapRemoveResponse.newBuilder()
            .setStatus(response.getStatus())
            .setEpoch(response.getEpoch())
            .setKey(response.getKey())
            .setRemoved(response.isRemoved())
            .setOldValue(response.getOldValue())
            .build();
    }

    private static DfsMapRangeResp handleRange(DfsMapRangeRequest request) {
        DfsMapBackend backend = requireBackend();
        DfsMapRangeReq req = new DfsMapRangeReq();
        req.setApiVersion(request.getApiVersion());
        req.setEpoch(request.getEpoch());
        req.setStart(request.getStart());
        req.setCount(request.getCount());
        req.setKeysOnly(request.getKeysOnly());
        return backend.handleRange(req);
    }

    private static void streamRange(DfsMapRangeRequest request, RpcServerStreamObserver<DfsMapRangeItem> observer) throws Exception {
        DfsMapRangeResp response = handleRange(request);
        if (response == null) {
            observer.onCompleted();
            return;
        }
        if (response.getStatus() != DfsMapStatusCodes.OK && response.getStatus() != DfsMapStatusCodes.NOT_FOUND) {
            observer.onError(new IllegalStateException("dfs_map_range status=" + response.getStatus()));
            return;
        }
        long[] keys = response.getKeys();
        long[] values = response.getValues();
        int emitted = Math.max(0, response.getEmitted());
        if (keys == null || emitted == 0) {
            observer.onCompleted();
            return;
        }
        int limit = Math.min(emitted, keys.length);
        for (int index = 0; index < limit; index++) {
            boolean hasValue = !request.getKeysOnly() && values != null && index < values.length;
            observer.onNext(DfsMapRangeItem.newBuilder()
                .setKey(keys[index])
                .setValue(hasValue ? values[index] : 0L)
                .setHasValue(hasValue)
                .build());
        }
        observer.onCompleted();
    }

    private static DfsMapBackend requireBackend() {
        DfsMapBackend backend = DfsMapRegistry.getBackend();
        if (backend == null) {
            throw new IllegalStateException("DFS_MAP backend not registered");
        }
        return backend;
    }

    private static RpcStatus resolveGetStatus(DfsMapGetResponse response) {
        return resolveStatus(response.getStatus(), response.getFound() ? "dfs_map_get_ok" : "dfs_map_get_not_found");
    }

    private static RpcStatus resolvePutStatus(DfsMapPutResponse response) {
        return resolveStatus(response.getStatus(), "dfs_map_put");
    }

    private static RpcStatus resolveRemoveStatus(DfsMapRemoveResponse response) {
        return resolveStatus(response.getStatus(), response.getRemoved() ? "dfs_map_remove_ok" : "dfs_map_remove_not_found");
    }

    private static RpcStatus resolveStatus(int domainStatus, String message) {
        RpcStatusCode code = switch (domainStatus) {
            case DfsMapStatusCodes.OK -> RpcStatusCode.OK;
            case DfsMapStatusCodes.NOT_FOUND -> RpcStatusCode.NOT_FOUND;
            case DfsMapStatusCodes.BAD_REQUEST -> RpcStatusCode.BAD_REQUEST;
            case DfsMapStatusCodes.RETRY, DfsMapStatusCodes.NOT_READY -> RpcStatusCode.SERVICE_UNAVAILABLE;
            case DfsMapStatusCodes.STALE_EPOCH, DfsMapStatusCodes.NOT_OWNER -> RpcStatusCode.CONFLICT;
            default -> RpcStatusCode.INTERNAL_ERROR;
        };
        boolean retriable = domainStatus == DfsMapStatusCodes.RETRY
            || domainStatus == DfsMapStatusCodes.NOT_READY
            || domainStatus == DfsMapStatusCodes.STALE_EPOCH
            || domainStatus == DfsMapStatusCodes.NOT_OWNER;
        return RpcStatus.newBuilder()
            .setCode(code)
            .setMessage(message == null ? "" : message)
            .setRetriable(retriable)
            .putDetails("domain", "dfs_map")
            .putDetails("domain_status", String.valueOf(domainStatus))
            .build();
    }
}
