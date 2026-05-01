package javax.net.p2p.rpc.server;

import com.google.protobuf.Message;
import java.lang.reflect.Method;
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
            if (context.isDeadlineExceeded(System.currentTimeMillis())) {
                return RpcFrames.error(requestFrame, RpcStatusCode.DEADLINE_EXCEEDED, "deadline exceeded", false);
            }
            RpcMethodDescriptor descriptor = registry.find(new RpcMethodKey(
                context.service(),
                context.method(),
                context.version()
            ));
            if (descriptor == null) {
                return RpcFrames.error(requestFrame, RpcStatusCode.NOT_FOUND, "RPC 方法不存在", false);
            }
            if (descriptor.callType() != RpcCallType.UNARY) {
                return RpcFrames.error(requestFrame, RpcStatusCode.METHOD_NOT_ALLOWED, "仅支持 unary 方法", false);
            }
            Message request = parseMessage(descriptor.requestType(), requestFrame.getPayload().toByteArray());
            @SuppressWarnings("unchecked")
            RpcMethodInvoker<Message, Message> invoker = (RpcMethodInvoker<Message, Message>) descriptor.invoker();
            Message response = invoker.invoke(context, request);
            return RpcFrames.complete(requestFrame, response.toByteArray(), resolveResponseStatus(descriptor, response), true);
        } catch (IllegalArgumentException ex) {
            return RpcFrames.error(requestFrame, RpcStatusCode.BAD_REQUEST, ex.getMessage(), false);
        } catch (Exception ex) {
            return RpcFrames.error(requestFrame, RpcStatusCode.INTERNAL_ERROR, ex.getMessage(), false);
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
}
