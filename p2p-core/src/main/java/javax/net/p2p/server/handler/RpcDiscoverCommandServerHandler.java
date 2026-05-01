package javax.net.p2p.server.handler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.rpc.model.RpcMethodDescriptor;
import javax.net.p2p.rpc.model.RpcMethodKey;
import javax.net.p2p.rpc.proto.DiscoverRequest;
import javax.net.p2p.rpc.proto.DiscoverResponse;
import javax.net.p2p.rpc.proto.MethodDescriptor;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.ServiceDescriptor;
import javax.net.p2p.rpc.server.RpcBootstrap;
import javax.net.p2p.rpc.server.RpcFrames;

/**
 * RPC 服务发现入口。
 */
public class RpcDiscoverCommandServerHandler implements P2PCommandHandler<byte[]> {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.RPC_DISCOVER;
    }

    @Override
    public P2PWrapper process(P2PWrapper<byte[]> request) {
        try {
            if (request.getCommand() != P2PCommand.RPC_DISCOVER) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令分发内部校验错误！");
            }
            RpcFrame frame = RpcFrame.parseFrom(request.getData());
            DiscoverRequest discoverRequest = DiscoverRequest.parseFrom(frame.getPayload());
            DiscoverResponse response = buildResponse(discoverRequest);
            RpcFrame rpcResponse = RpcFrames.ok(frame, response.toByteArray(), true);
            return P2PWrapper.build(request.getSeq(), P2PCommand.RPC_DISCOVER, rpcResponse.toByteArray());
        } catch (Exception ex) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, ex.toString());
        }
    }

    private DiscoverResponse buildResponse(DiscoverRequest request) {
        Map<String, ServiceDescriptor.Builder> services = new TreeMap<>();
        Map<String, Map<String, MethodDescriptor>> methodsByService = new TreeMap<>();
        for (RpcMethodDescriptor descriptor : RpcBootstrap.registry().allMethods()) {
            RpcMethodKey key = descriptor.key();
            if (!request.getService().isBlank() && !request.getService().equals(key.service())) {
                continue;
            }
            String serviceKey = key.service() + "#" + key.version();
            ServiceDescriptor.Builder service = services.computeIfAbsent(
                serviceKey,
                ignored -> ServiceDescriptor.newBuilder().setService(key.service()).setVersion(key.version())
            );
            if (request.getIncludeMethods()) {
                methodsByService.computeIfAbsent(serviceKey, ignored -> new TreeMap<>())
                    .put(key.method(), MethodDescriptor.newBuilder()
                        .setMethod(key.method())
                        .setInputType(descriptor.requestType().getName())
                        .setOutputType(descriptor.responseType().getName())
                        .setCallType(descriptor.callType())
                        .setIdempotent(descriptor.idempotent())
                        .build());
            }
        }
        DiscoverResponse.Builder response = DiscoverResponse.newBuilder();
        for (Map.Entry<String, ServiceDescriptor.Builder> entry : services.entrySet()) {
            ServiceDescriptor.Builder service = entry.getValue();
            if (request.getIncludeMethods()) {
                for (MethodDescriptor method : methodsByService.getOrDefault(entry.getKey(), Map.of()).values()) {
                    service.addMethods(method);
                }
            }
            response.addServices(service.build());
        }
        return response.build();
    }
}
