---
updated: "2026-05-11T17:15:00Z"
---
# p2p-core：RPC_STREAM 服务端异常统一返回 ERROR 帧

## What Changed

- `RpcServerStreamHandler` 在处理 server-stream 时，若 invoker 抛异常，不再返回 `STD_ERROR`，而是返回 `P2PCommand.RPC_STREAM` + `RpcFrameType.ERROR`，并使用 `RpcStatusCode.INTERNAL_ERROR`。
- 错误帧构造改为传入 `RpcRequestContext`，保证 `response_headers/response_trailers/status.details` 能随 ERROR 一并回传（与 unary/close 语义一致）。
- 增加回归测试覆盖“invoker 抛异常 -> ERROR 帧”的一致性。

## Verification

- `mvn -pl p2p-core "-Dtest=javax.net.p2p.rpc.RpcCommandHandlersTest" test`

