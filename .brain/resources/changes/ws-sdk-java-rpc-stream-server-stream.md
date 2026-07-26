---
updated: "2026-05-11T14:34:00Z"
---
# ws-sdk-java：补齐 RPC_STREAM（server-stream）与传输层 StreamP2PWrapper 兼容

## What Changed

- p2p-core：`P2PWrapperSecureDecoder` 统一按 `StreamP2PWrapper` 反序列化（仍向上传递为 `P2PWrapper`），保证 `AbstractStreamRequestAdapter` 路径不会因类型转换失败而中断（覆盖 TCP/WS）。
- p2p-ws-sdk-java：增加 `StreamP2PWrapper` stub，并在 `CoreWsClient` 中支持：
  - 分配 requestId
  - 发送任意 protostuff 对象（用于发送 StreamP2PWrapper）
  - 按 requestId 注册流回调并路由入站 `RPC_STREAM` 消息
- p2p-ws-sdk-java：新增 `CoreRpcStreamClient`（server-stream）：
  - 发送 OPEN（包含 flow_control）
  - 解析 DATA/CLOSE/ERROR
  - 按 `chunk_index/end_of_message` 重组
  - 消费到 `windowUpdateBatch` 后自动发 WINDOW_UPDATE（RPC_CONTROL）
- p2p-ws-sdk-java：新增 `RPC_EVENT`（PubSub Subscribe）订阅侧能力：
  - 增加 `p2p_rpc_pubsub.proto` 并生成 `PubSubSubscribeRequest/PubSubEvent`
  - 新增 `CoreRpcEventClient`/`CoreRpcEventSubscription`，复用同一套服务端流会话（分片重组 + 自动 WINDOW_UPDATE）
  - `CoreWsClient` 入站路由避免 `RPC_STREAM/RPC_EVENT` 数据帧意外完成 pending（覆盖 ACK 晚到场景）
  - 增加 `CoreWsClientRoutingTest` 覆盖 ACK/DATA 顺序与路由行为

## Verification

- `mvn -q -pl p2p-core "-Dtest=javax.net.p2p.rpc.RpcCommandHandlersTest" test`
- `mvn -q -f p2p-ws-sdk-java/pom.xml test`
