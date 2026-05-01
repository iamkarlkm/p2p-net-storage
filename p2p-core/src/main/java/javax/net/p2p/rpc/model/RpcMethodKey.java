package javax.net.p2p.rpc.model;

/**
 * RPC 方法唯一键。
 */
public record RpcMethodKey(String service, String method, String version) {

    public RpcMethodKey {
        service = requireText(service, "service");
        method = requireText(method, "method");
        version = normalizeVersion(version);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private static String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "v1";
        }
        return version.trim();
    }
}
