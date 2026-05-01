package javax.net.p2p.rpc.server;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.p2p.rpc.model.RpcMethodDescriptor;
import javax.net.p2p.rpc.model.RpcMethodKey;

/**
 * RPC 方法注册表。
 */
public final class RpcServiceRegistry {

    private final Map<RpcMethodKey, RpcMethodDescriptor> methods = new ConcurrentHashMap<>();

    public void register(RpcMethodDescriptor descriptor) {
        RpcMethodDescriptor previous = methods.putIfAbsent(descriptor.key(), descriptor);
        if (previous != null) {
            throw new IllegalStateException("RPC 方法重复注册: " + descriptor.key());
        }
    }

    public RpcMethodDescriptor find(RpcMethodKey key) {
        return methods.get(key);
    }

    public Collection<RpcMethodDescriptor> allMethods() {
        return Collections.unmodifiableCollection(methods.values());
    }
}
