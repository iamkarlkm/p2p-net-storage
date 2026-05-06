---
title: RPC Governance Audit
updated: "2026-05-01T20:12:07Z"
---
## Verification

- `mvn -pl p2p-core -DskipTests=false -Dtest=RpcCommandHandlersTest test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=RpcCommandHandlersTest,ServerQuicMessageProcessorTest test`

## Notes

- RPC client metadata now exposes larger call-shaping helpers through `RpcCallOptions`, including service version, tracing fields, caller identity, custom headers, and idempotent hints.
- Unary calls now have a detailed path: `RpcClient#unaryDetailed(...)` returns `RpcUnaryResult`, while error responses raise `RpcClientResponseException` so callers can still inspect response metadata, status, frame type, headers, and trailers.
- Stream observers now receive per-frame response context through `RpcClientStreamObserver#onResponseContext(...)`, while transport-only `WINDOW_UPDATE` frames stay internal to flow control.
- The wire contract now carries response headers and trailers explicitly, and `RpcRequestContext` can accumulate governance data for handlers and interceptors to write back through `RpcFrames`.
- RPC server handling now has a shared interceptor chain: `RpcServerInterceptor` hooks `beforeHandle`, `afterComplete`, and `afterError` across unary, server-stream, client-stream, bidi, and event paths.
- `RpcBootstrap` now registers a default `RpcAuditInterceptor` that stamps response headers/trailers/status details with service, method, status, trace, caller, and duration fields, and emits `rpc.audit` logs for both success and error outcomes.
- `RpcCommandHandlersTest` now covers both governance edges: audit metadata is visible on `unaryDetailed(...)`, and a custom interceptor can short-circuit a unary call with `FORBIDDEN` before the invoker runs.
- The combined `RpcCommandHandlersTest` plus `ServerQuicMessageProcessorTest` regression confirms the same response-context and metadata contract still survives the real `ServerQuicMessageProcessor` path.
