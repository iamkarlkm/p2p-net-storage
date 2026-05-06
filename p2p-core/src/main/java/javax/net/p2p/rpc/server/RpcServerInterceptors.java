package javax.net.p2p.rpc.server;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.p2p.rpc.api.RpcServerInterceptor;

/**
 * RPC 服务端全局拦截器注册表。
 */
public final class RpcServerInterceptors {
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private static final CopyOnWriteArrayList<RpcServerInterceptor> INTERCEPTORS = new CopyOnWriteArrayList<>();

    private RpcServerInterceptors() {
    }

    public static Registration register(RpcServerInterceptor interceptor) {
        if (interceptor == null) {
            return () -> {
            };
        }
        INTERCEPTORS.addIfAbsent(interceptor);
        return () -> INTERCEPTORS.remove(interceptor);
    }

    public static List<RpcServerInterceptor> all() {
        return List.copyOf(INTERCEPTORS);
    }
}
