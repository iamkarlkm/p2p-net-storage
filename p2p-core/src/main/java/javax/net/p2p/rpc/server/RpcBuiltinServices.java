package javax.net.p2p.rpc.server;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.p2p.rpc.echo.proto.EchoRequest;
import javax.net.p2p.rpc.echo.proto.EchoResponse;

/**
 * RPC 内置服务注册入口。
 */
public final class RpcBuiltinServices {

    public static final String ECHO_SERVICE = "p2p.rpc.echo.v1.EchoService";
    public static final String ECHO_METHOD = "Echo";
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private RpcBuiltinServices() {
    }

    public static void registerDefaults() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        RpcBootstrap.registerUnary(
            ECHO_SERVICE,
            ECHO_METHOD,
            "v1",
            true,
            EchoRequest.class,
            EchoResponse.class,
            (context, request) -> EchoResponse.newBuilder()
                .setMessage(request.getMessage())
                .setServerTime(System.currentTimeMillis())
                .build()
        );
    }
}
