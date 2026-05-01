package javax.net.p2p.rpc.server;

import com.google.protobuf.Message;
import javax.net.p2p.rpc.api.RpcBidiStreamInvoker;
import javax.net.p2p.rpc.api.RpcClientStreamInvoker;
import javax.net.p2p.rpc.api.RpcMethodInvoker;
import javax.net.p2p.rpc.api.RpcResponseStatusResolver;
import javax.net.p2p.rpc.api.RpcServerStreamInvoker;
import javax.net.p2p.rpc.model.RpcMethodDescriptor;
import javax.net.p2p.rpc.proto.RpcCallType;

/**
 * RPC 服务端静态入口，兼容当前处理器无参构造注册方式。
 */
public final class RpcBootstrap {

    private static final RpcServiceRegistry REGISTRY = new RpcServiceRegistry();
    private static final RpcDispatcher DISPATCHER = new RpcDispatcher(REGISTRY);

    static {
        RpcBuiltinServices.registerDefaults();
        DfsMapRpcServices.registerDefaults();
        RpcPubSubServices.registerDefaults();
        RpcStreamingBuiltinServices.registerDefaults();
    }

    private RpcBootstrap() {
    }

    public static RpcServiceRegistry registry() {
        return REGISTRY;
    }

    public static RpcDispatcher dispatcher() {
        return DISPATCHER;
    }

    public static void register(RpcMethodDescriptor descriptor) {
        REGISTRY.register(descriptor);
    }

    public static <Req extends Message, Resp extends Message> void registerUnary(
        String service,
        String method,
        String version,
        boolean idempotent,
        Class<Req> requestType,
        Class<Resp> responseType,
        RpcMethodInvoker<Req, Resp> invoker
    ) {
        registerUnary(service, method, version, idempotent, requestType, responseType, invoker, null);
    }

    public static <Req extends Message, Resp extends Message> void registerUnary(
        String service,
        String method,
        String version,
        boolean idempotent,
        Class<Req> requestType,
        Class<Resp> responseType,
        RpcMethodInvoker<Req, Resp> invoker,
        RpcResponseStatusResolver<Resp> responseStatusResolver
    ) {
        REGISTRY.register(new RpcMethodDescriptor(
            service,
            method,
            version,
            RpcCallType.UNARY,
            idempotent,
            requestType,
            responseType,
            invoker,
            responseStatusResolver
        ));
    }

    public static <Req extends Message, Resp extends Message> void registerServerStream(
        String service,
        String method,
        String version,
        boolean idempotent,
        Class<Req> requestType,
        Class<Resp> responseType,
        RpcServerStreamInvoker<Req, Resp> invoker
    ) {
        REGISTRY.register(new RpcMethodDescriptor(
            service,
            method,
            version,
            RpcCallType.SERVER_STREAM,
            idempotent,
            requestType,
            responseType,
            invoker,
            null
        ));
    }

    public static <Req extends Message, Resp extends Message> void registerClientStream(
        String service,
        String method,
        String version,
        boolean idempotent,
        Class<Req> requestType,
        Class<Resp> responseType,
        RpcClientStreamInvoker<Req, Resp> invoker,
        RpcResponseStatusResolver<Resp> responseStatusResolver
    ) {
        REGISTRY.register(new RpcMethodDescriptor(
            service,
            method,
            version,
            RpcCallType.CLIENT_STREAM,
            idempotent,
            requestType,
            responseType,
            invoker,
            responseStatusResolver
        ));
    }

    public static <Req extends Message, Resp extends Message> void registerBidiStream(
        String service,
        String method,
        String version,
        boolean idempotent,
        Class<Req> requestType,
        Class<Resp> responseType,
        RpcBidiStreamInvoker<Req, Resp> invoker
    ) {
        REGISTRY.register(new RpcMethodDescriptor(
            service,
            method,
            version,
            RpcCallType.BIDI_STREAM,
            idempotent,
            requestType,
            responseType,
            invoker,
            null
        ));
    }
}
