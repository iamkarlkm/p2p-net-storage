---
updated: "2026-05-12T00:45:00Z"
---
# ws-sdk-java：流式 RPC 错误携带 response context

## What Changed

- `CoreRpcStreamObserver` 新增默认回调 `onResponseContext(...)`，用于暴露服务端返回的 meta/status/headers/trailers。
- 新增 `CoreRpcResponseContext` 与 `CoreRpcResponseException`：
  - `CoreRpcServerStreamSession` 在 `RPC_STREAM/RPC_EVENT` 收到 `ERROR` 或 `STD_ERROR` 时，统一回调 `onResponseContext`，并以 `CoreRpcResponseException(context)` 触发 `onError`。
  - `STD_ERROR` 会映射为 `RpcStatus`（带 `p2p.std_error_*` details），避免误按 `RpcFrame` 解析导致丢失错误信息。
- 订阅/流句柄资源释放一致性：
  - `CoreRpcEventSubscription.cancel()` 与 `CoreRpcStreamHandle.cancel()` 使用 try/finally，保证 cancel 失败也会执行 handler 清理。
  - `CoreRpcServerStreamSession` 支持 `close()`，`CoreRpcEventClient/CoreRpcStreamClient` 的 cancel/close 会先关闭 session 再解除 handler 注册，避免 race 下继续发 WINDOW_UPDATE。

## Verification

- `mvn -f p2p-ws-sdk-java/pom.xml test`
