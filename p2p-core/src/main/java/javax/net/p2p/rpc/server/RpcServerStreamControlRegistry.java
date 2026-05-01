package javax.net.p2p.rpc.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.net.p2p.rpc.proto.RpcFlowControl;

/**
 * 服务端流控制注册表。
 */
final class RpcServerStreamControlRegistry {
    private static final ConcurrentMap<Integer, WindowController> CONTROLLERS = new ConcurrentHashMap<>();

    private RpcServerStreamControlRegistry() {
    }

    static void register(int requestId, WindowController controller) {
        if (requestId <= 0 || controller == null) {
            return;
        }
        CONTROLLERS.put(requestId, controller);
    }

    static void remove(int requestId, WindowController controller) {
        if (requestId <= 0 || controller == null) {
            return;
        }
        CONTROLLERS.remove(requestId, controller);
    }

    static void remove(int requestId) {
        if (requestId <= 0) {
            return;
        }
        CONTROLLERS.remove(requestId);
    }

    static boolean applyWindowUpdate(int requestId, RpcFlowControl flowControl) throws Exception {
        WindowController controller = CONTROLLERS.get(requestId);
        if (controller == null) {
            return false;
        }
        controller.applyWindowUpdate(flowControl);
        return true;
    }

    interface WindowController {
        void applyWindowUpdate(RpcFlowControl flowControl) throws Exception;
    }
}
