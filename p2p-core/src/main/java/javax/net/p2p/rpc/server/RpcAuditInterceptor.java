package javax.net.p2p.rpc.server;

import javax.net.p2p.rpc.api.RpcServerInterceptor;
import javax.net.p2p.rpc.model.RpcRequestContext;
import javax.net.p2p.rpc.proto.RpcStatus;
import javax.net.p2p.rpc.proto.RpcStatusCode;
import lombok.extern.slf4j.Slf4j;

/**
 * 默认 RPC 审计拦截器，把关键治理字段写入响应上下文并输出审计日志。
 */
@Slf4j(topic = "rpc.audit")
public final class RpcAuditInterceptor implements RpcServerInterceptor {

    @Override
    public RpcStatus beforeHandle(RpcRequestContext context) {
        context.markHandlingStarted();
        context.putResponseHeader("x-rpc-service", context.service());
        context.putResponseHeader("x-rpc-method", context.method());
        return null;
    }

    @Override
    public void afterComplete(RpcRequestContext context, RpcStatus status) {
        long durationMs = context.elapsedHandlingMillis();
        context.putResponseTrailer("x-rpc-status", status == null ? RpcStatusCode.OK.name() : status.getCode().name());
        context.putResponseTrailer("x-rpc-duration-ms", String.valueOf(durationMs));
        context.putResponseStatusDetail("audit.service", context.service());
        context.putResponseStatusDetail("audit.method", context.method());
        context.putResponseStatusDetail("audit.trace_id", context.traceId());
        context.putResponseStatusDetail("audit.caller_user_id", context.callerUserId());
        context.putResponseStatusDetail("audit.duration_ms", String.valueOf(durationMs));
        log.info(
            "rpc audit success service={} method={} requestId={} traceId={} callerUserId={} status={} durationMs={}",
            context.service(),
            context.method(),
            context.requestId(),
            context.traceId(),
            context.callerUserId(),
            status == null ? RpcStatusCode.OK : status.getCode(),
            durationMs
        );
    }

    @Override
    public void afterError(RpcRequestContext context, RpcStatusCode code, String message) {
        long durationMs = context.elapsedHandlingMillis();
        context.putResponseTrailer("x-rpc-status", code == null ? RpcStatusCode.INTERNAL_ERROR.name() : code.name());
        context.putResponseTrailer("x-rpc-duration-ms", String.valueOf(durationMs));
        context.putResponseStatusDetail("audit.service", context.service());
        context.putResponseStatusDetail("audit.method", context.method());
        context.putResponseStatusDetail("audit.trace_id", context.traceId());
        context.putResponseStatusDetail("audit.caller_user_id", context.callerUserId());
        context.putResponseStatusDetail("audit.duration_ms", String.valueOf(durationMs));
        log.warn(
            "rpc audit error service={} method={} requestId={} traceId={} callerUserId={} status={} durationMs={} message={}",
            context.service(),
            context.method(),
            context.requestId(),
            context.traceId(),
            context.callerUserId(),
            code == null ? RpcStatusCode.INTERNAL_ERROR : code,
            durationMs,
            message == null ? "" : message
        );
    }
}
