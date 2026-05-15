---
updated: "2026-05-11T13:27:20Z"
---
# RPC 完整度补齐：请求侧分片、错误上下文、STD_ERROR 映射（并同步到 ws-sdk-java）

## What Changed

- p2p-core：CLIENT_STREAM/BIDI_STREAM 请求侧支持按 `RpcCallOptions.initialMaxFrameBytes` 分片发送（DATA 帧使用 `chunk_index/end_of_message`），服务端入站自动重组后再进行 protobuf 反序列化与业务回调。
- p2p-core：unary 的 NOT_FOUND / DEADLINE_EXCEEDED / METHOD_NOT_ALLOWED 等错误分支也带 `RpcRequestContext`，使拦截器写入的 response headers/trailers/status.details 在错误帧中可见。
- p2p-core：客户端 unary 解析支持 `STD_ERROR`（P2P 层结构化错误）映射为 `RpcClientResponseException`，并把 std_error 的 key/code 写入 `RpcStatus.details`。
- p2p-ws-sdk-java：core_compat 增加 `P2PStdError` stub 并在 `CoreRpcClient` 中识别 `STD_ERROR`，避免把错误当作 `RpcFrame` 解析导致误报。

## Verification

- `mvn -q -pl p2p-core "-Dtest=javax.net.p2p.rpc.RpcCommandHandlersTest" test`
- `mvn -q -f p2p-ws-sdk-java/pom.xml test`
