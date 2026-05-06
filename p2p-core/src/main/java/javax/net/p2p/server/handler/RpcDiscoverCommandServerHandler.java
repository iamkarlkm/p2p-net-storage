package javax.net.p2p.server.handler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PChannelAwareCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.rpc.api.RpcServerInterceptor;
import javax.net.p2p.rpc.model.RpcMethodDescriptor;
import javax.net.p2p.rpc.model.RpcMethodKey;
import javax.net.p2p.rpc.model.RpcRequestContext;
import javax.net.p2p.rpc.proto.DiscoverRequest;
import javax.net.p2p.rpc.proto.DiscoverResponse;
import javax.net.p2p.rpc.proto.MethodDescriptor;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcStatus;
import javax.net.p2p.rpc.proto.RpcStatusCode;
import javax.net.p2p.rpc.proto.ServiceDescriptor;
import javax.net.p2p.rpc.server.RpcBootstrap;
import javax.net.p2p.rpc.server.RpcFrames;
import javax.net.p2p.rpc.server.RpcServerInterceptors;

/**
 * RPC 服务发现入口。
 */
public class RpcDiscoverCommandServerHandler implements P2PChannelAwareCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.RPC_DISCOVER;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        return process((P2PWrapper<byte[]>) request, null);
    }

    @Override
    public P2PWrapper process(ChannelHandlerContext ctx, P2PWrapper request) {
        return process((P2PWrapper<byte[]>) request, ctx == null ? null : ctx.channel());
    }

    private P2PWrapper process(P2PWrapper<byte[]> request, Channel channel) {
        try {
            if (request.getCommand() != P2PCommand.RPC_DISCOVER) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH, "指令分发内部校验错误！");
            }
            RpcFrame frame = RpcFrame.parseFrom(request.getData());
            RpcRequestContext context = RpcRequestContext.from(request, frame, channel);
            RpcStatus intercepted = beforeHandle(context);
            if (intercepted != null) {
                afterComplete(context, intercepted);
                return wrapResponse(request, RpcFrames.complete(frame, new byte[0], intercepted, true, context));
            }
            if (context.isDeadlineExceeded(System.currentTimeMillis())) {
                afterError(context, RpcStatusCode.DEADLINE_EXCEEDED, "deadline exceeded");
                return wrapResponse(request, RpcFrames.error(frame, RpcStatusCode.DEADLINE_EXCEEDED, "deadline exceeded", false, context));
            }
            DiscoverRequest discoverRequest = DiscoverRequest.parseFrom(frame.getPayload());
            DiscoverResponse response = buildResponse(discoverRequest);
            RpcStatus status = RpcStatus.newBuilder().setCode(RpcStatusCode.OK).setRetriable(false).build();
            afterComplete(context, status);
            return wrapResponse(request, RpcFrames.complete(frame, response.toByteArray(), status, true, context));
        } catch (Exception ex) {
            RpcFrame requestFrame = RpcFrame.getDefaultInstance();
            try {
                requestFrame = RpcFrame.parseFrom(request.getData());
            } catch (Exception ignored) {
            }
            RpcRequestContext context = RpcRequestContext.from(request, requestFrame, channel);
            afterError(context, RpcStatusCode.INTERNAL_ERROR, ex.toString());
            return wrapResponse(request, RpcFrames.error(requestFrame, RpcStatusCode.INTERNAL_ERROR, ex.toString(), false, context));
        }
    }

    private static P2PWrapper<byte[]> wrapResponse(P2PWrapper<byte[]> request, RpcFrame rpcResponse) {
        return P2PWrapper.build(request.getSeq(), P2PCommand.RPC_DISCOVER, rpcResponse.toByteArray());
    }

    private static RpcStatus beforeHandle(RpcRequestContext context) {
        for (RpcServerInterceptor interceptor : RpcServerInterceptors.all()) {
            RpcStatus status = interceptor.beforeHandle(context);
            if (status != null) {
                return status;
            }
        }
        return null;
    }

    private static void afterComplete(RpcRequestContext context, RpcStatus status) {
        for (RpcServerInterceptor interceptor : RpcServerInterceptors.all()) {
            interceptor.afterComplete(context, status);
        }
    }

    private static void afterError(RpcRequestContext context, RpcStatusCode code, String message) {
        for (RpcServerInterceptor interceptor : RpcServerInterceptors.all()) {
            interceptor.afterError(context, code, message);
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
