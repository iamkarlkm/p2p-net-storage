---
updated: "2026-05-12T00:20:00Z"
---
# p2p-core：流式 RPC onError 统一为 RpcClientResponseException（携带 context）

## What Changed

- `P2PRpcClient.MessageStreamAdapter`：
  - 收到 `RpcFrameType.ERROR` 时，`onError` 不再抛 `IllegalStateException`，改为抛 `RpcClientResponseException(message, context)`，并确保 `onResponseContext(context)` 先于 `onError`。
  - 收到 `STD_ERROR` 时不再尝试按 `RpcFrame` 解析，而是复用 unary 的 `STD_ERROR -> RpcStatus` 映射，构造 `RpcClientResponseException` 返回给流式调用方。
  - `cancel(...)` 也会生成 `RpcStatusCode.CANCELED` 的 response context，并用 `RpcClientResponseException` 回调。

## Verification

- `mvn -pl p2p-core "-Dtest=javax.net.p2p.rpc.RpcCommandHandlersTest" test`

