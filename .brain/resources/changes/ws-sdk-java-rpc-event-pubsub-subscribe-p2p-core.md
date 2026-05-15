---
updated: "2026-05-11T17:15:00Z"
---
# ws-sdk-java：RPC_EVENT（PubSub Subscribe）订阅能力

## What Changed

- `p2p-ws-sdk-java` 增加 `p2p_rpc_pubsub.proto`，生成 `PubSubSubscribeRequest/PubSubEvent`。
- 新增 `CoreRpcEventClient`/`CoreRpcEventSubscription`：用 `RPC_EVENT` OPEN 建立订阅，DATA/CLOSE/ERROR 走统一的服务端流会话处理（分片重组 + 自动 WINDOW_UPDATE，控制面走 `RPC_CONTROL`）。
- `CoreWsClient` 入站路由调整：`RPC_STREAM/RPC_EVENT` 数据帧不会意外完成 pending（避免 OPEN 阶段 ACK 晚到时被数据帧“抢占”）。
- `CoreWsClient` 增加 `sendAndAwait(...)`，允许外部指定 seq 并等待响应（便于构造 `meta.request_id == wrapper.seq` 的 unary 请求）。
- `CoreRpcEventClient` 增加 `publish(...)`（RPC_UNARY）：可发布 topic/message，并解析 `PubSubPublishResponse`。
- 增加 `CoreWsClientRoutingTest` 覆盖 ACK/DATA 到达顺序与路由行为。

## Verification

- `mvn -f p2p-ws-sdk-java/pom.xml test`
