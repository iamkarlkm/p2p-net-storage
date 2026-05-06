package javax.net.p2p.rpc.server;

import com.google.protobuf.Message;
import java.lang.reflect.Method;
import javax.net.p2p.rpc.api.RpcServerInterceptor;
import javax.net.p2p.rpc.api.RpcMethodInvoker;
import javax.net.p2p.rpc.api.RpcResponseStatusResolver;
import javax.net.p2p.rpc.model.RpcMethodDescriptor;
import javax.net.p2p.rpc.model.RpcMethodKey;
import javax.net.p2p.rpc.model.RpcRequestContext;
import javax.net.p2p.rpc.proto.RpcCallType;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcStatus;
import javax.net.p2p.rpc.proto.RpcStatusCode;

/**
 * RPC 二级分发器，按 service/method/version 定位方法。
 */
public final class RpcDispatcher {

    private final RpcServiceRegistry registry;

    public RpcDispatcher(RpcServiceRegistry registry) {
        this.registry = registry;
    }

    public RpcFrame dispatchUnary(RpcRequestContext context, RpcFrame requestFrame) {
        try {
            RpcStatus intercepted = beforeHandle(context, requestFrame);
            if (intercepted != null) {
                afterComplete(context, intercepted);
                return RpcFrames.complete(requestFrame, new byte[0], intercepted, true, context);
            }
            if (context.isDeadlineExceeded(System.currentTimeMillis())) {
                afterError(context, RpcStatusCode.DEADLINE_EXCEEDED, "deadline exceeded");
                return RpcFrames.error(requestFrame, RpcStatusCode.DEADLINE_EXCEEDED, "deadline exceeded", false);
            }
            RpcMethodDescriptor descriptor = registry.find(new RpcMethodKey(
                context.service(),
                context.method(),
                context.version()
            ));
            if (descriptor == null) {
                afterError(context, RpcStatusCode.NOT_FOUND, "RPC 方法不存在");
                return RpcFrames.error(requestFrame, RpcStatusCode.NOT_FOUND, "RPC 方法不存在", false);
            }
            if (descriptor.callType() != RpcCallType.UNARY) {
                afterError(context, RpcStatusCode.METHOD_NOT_ALLOWED, "仅支持 unary 方法");
                return RpcFrames.error(requestFrame, RpcStatusCode.METHOD_NOT_ALLOWED, "仅支持 unary 方法", false);
            }
            Message request = parseMessage(descriptor.requestType(), requestFrame.getPayload().toByteArray());
            @SuppressWarnings("unchecked")
            RpcMethodInvoker<Message, Message> invoker = (RpcMethodInvoker<Message, Message>) descriptor.invoker();
            Message response = invoker.invoke(context, request);
            RpcStatus status = resolveResponseStatus(descriptor, response);
            afterComplete(context, status);
            return RpcFrames.complete(
                requestFrame,
                response.toByteArray(),
                status,
                true,
                context
            );
        } catch (IllegalArgumentException ex) {
            afterError(context, RpcStatusCode.BAD_REQUEST, ex.getMessage());
            return RpcFrames.error(requestFrame, RpcStatusCode.BAD_REQUEST, ex.getMessage(), false, context);
        } catch (Exception ex) {
            afterError(context, RpcStatusCode.INTERNAL_ERROR, ex.getMessage());
            return RpcFrames.error(requestFrame, RpcStatusCode.INTERNAL_ERROR, ex.getMessage(), false, context);
        }
    }

    private Message parseMessage(Class<? extends Message> messageType, byte[] payload) throws Exception {
        Method parseFrom = messageType.getMethod("parseFrom", byte[].class);
        return (Message) parseFrom.invoke(null, payload == null ? new byte[0] : payload);
    }

    private RpcStatus resolveResponseStatus(RpcMethodDescriptor descriptor, Message response) {
        @SuppressWarnings("unchecked")
        RpcResponseStatusResolver<Message> resolver = (RpcResponseStatusResolver<Message>) descriptor.responseStatusResolver();
        if (resolver == null) {
            return RpcStatus.newBuilder().setCode(RpcStatusCode.OK).setRetriable(false).build();
        }
        RpcStatus status = resolver.resolve(response);
        return status == null ? RpcStatus.newBuilder().setCode(RpcStatusCode.OK).setRetriable(false).build() : status;
    }

    private RpcStatus beforeHandle(RpcRequestContext context, RpcFrame requestFrame) {
        for (RpcServerInterceptor interceptor : RpcServerInterceptors.all()) {
            RpcStatus status = interceptor.beforeHandle(context);
            if (status != null) {
                return status;
            }
        }
        return null;
    }

    private void afterComplete(RpcRequestContext context, RpcStatus status) {
        for (RpcServerInterceptor interceptor : RpcServerInterceptors.all()) {
            interceptor.afterComplete(context, status);
        }
    }

    private void afterError(RpcRequestContext context, RpcStatusCode code, String message) {
        for (RpcServerInterceptor interceptor : RpcServerInterceptors.all()) {
            interceptor.afterError(context, code, message);
        }
    }
}
