# WebSocket SDK 与 Java P2P WebSocket（目录清单与使用方法）

本文用于给其他 Agent/开发者快速定位本仓库里所有 “WebSocket 相关 SDK” 以及 “Java 侧 P2P WebSocket（p2p-core）” 的代码入口与运行方式。

## 1. 总览（按协议族）

### 1.1 p2p-ws-protocol（跨语言 SDK 的共同协议）

- 协议规范：
  - `p2p-ws-protocol/spec.md`
  - `p2p-ws-protocol/README.md`
- 配套示例配置（YAML）：
  - `p2p-ws-protocol/examples/*.yaml`
- 测试向量：
  - `p2p-ws-protocol/test-vectors/*`
- 生成 keyfile：
  - `p2p-ws-protocol/scripts/gen_keyfile.py`

这些 SDK（TS/Dart/Java/Python/C）都对齐上述协议与 test-vectors。

### 1.2 p2p-core 的 “Java P2P WebSocket”

`p2p-core` 内实现的是 Java/Netty 版本的 P2P WebSocket Server/Client（带可靠性重传层），属于“生产实现方向”，并不依赖 `p2p-ws-sdk-*`。

## 2. WebSocket SDK（跨语言）

### 2.0 IM（即时通讯）命令与载荷（跨语言对齐）

**协议侧（protobuf）：**
- `p2p-ws-protocol/proto/p2p_im.proto`：IM 载荷（用于 `P2PWrapper.data`）
  - `IMUserModel`、`IMChatModel`、`IMChatAck`、`IMChatHistoryRequest/Response`、`IMUserListResponse`
  - 群：`IMGroupModel`、`IMGroupListResponse`、`IMGroupMembersResponse`
  - `IMChatModel.file_info` 复用 `p2p-ws-protocol/proto/p2p_data.proto` 的 `FileDataModel`
  - `IMChatAck` 增加 `ack_type/peer_id`（用于送达/已读/状态，以及路由辅助）

**命令号段（与 p2p-core 的 `P2PCommand` 对齐）：**
- 用户：`10000-10004`（login/logout/list/heartbeat/status_update）
- 单聊：`11000-11007`（send/receive/ack/history/recall/forward...）
- 群：`12000-12010`
- 系统：`13000`

**当前 SDK 已落地的最小 IM 交互：**
- **Dart**：`p2p-ws-sdk-dart/lib/src/server.dart` 内置 IM handler；`lib/src/messages/im.dart` 提供 IM 编解码。
- **TS**：`p2p-ws-sdk-ts/src/PeerNode.ts`（Peer WS Server）内置 IM handler；`src/im.ts` 提供 IM 编解码；`src/commands.ts` 提供命令常量。
- **Java**：`p2p-ws-sdk-java/src/main/java/p2pws/sdk/demo/DemoServerHandler.java` 内置 IM handler；`p2pws/sdk/im/ImCommands.java` 提供命令常量；`p2pws/sdk/im/ImMemory.java` 提供内存态在线表/历史。

**响应/推送约定（与 Dart/TS/Java demo 一致）：**
- `IM_USER_LOGIN(10000)` → 响应 `STD_OK(6)`，data=`IMUserModel`
- `IM_USER_LIST(10002)` → 响应 `STD_OK(6)`，data=`IMUserListResponse`
- `IM_CHAT_SEND(11000)` → 响应 `STD_OK(6)`，data=`IMChatAck(ack_type=DELIVERED, peer_id=receiver_id)`；若接收者在线，服务端推送 `IM_CHAT_RECEIVE(11001)`，data=`IMChatModel`
- **IM 文件交换（基于 `IMChatModel.file_info` + 文件命令）**：
  - 发送端在 `IM_CHAT_SEND/IM_GROUP_MESSAGE_SEND` 的 `IMChatModel.file_info.data` 填入文件内容即可（可选填 `file_info.path` 作为文件名）
  - 服务端会把 `file_info.data` 落盘到 `im_storage_locations` 对应的共享空间，并把转发/历史中的 `file_info.data` 清空，仅保留 `store_id/path/length/md5`
  - 接收端用 `GET_FILE(7)`（或 `GET_FILE_SEGMENTS(20)`）按 `store_id + path` 拉取文件内容
- `IM_CHAT_ACK(11002)` → 响应 `STD_OK(6)`；若可定位发送者，服务端推送 `IM_CHAT_ACK(11002)` 给发送者，data=`IMChatAck`
- `IM_CHAT_STATUS_UPDATE(11003)` → 响应 `STD_OK(6)`；用于已读/状态（例如 `ack_type=READ`），服务端转发给发送者
- `IM_CHAT_HISTORY_REQUEST(11004)` → 响应 `IM_CHAT_HISTORY_RESPONSE(11005)`，data=`IMChatHistoryResponse`
  - 当 `peer_id` 为 `group_id` 且群存在时，返回群历史
  - 当存在 `IM_CHAT_STATUS_UPDATE(11003)` 更新时，历史返回会把状态写入 `IMChatModel.extra`（追加/覆盖 `|status:<ack_type>`）
- `IM_GROUP_CREATE(12000)` → 响应 `STD_OK(6)`，data=`IMGroupModel`（自动补齐 `group_id/owner_id`）
- `IM_GROUP_DISMISS(12001)` → 响应 `STD_OK(6)`（仅 owner）
- `IM_GROUP_SET_ADMIN(12008)` → 响应 `STD_OK(6)`，data=`IMGroupModel`（仅 owner；更新 `admin_ids`）
- `IM_GROUP_JOIN(12002)` / `IM_GROUP_LEAVE(12003)` → 响应 `STD_OK(6)`
- `IM_GROUP_LIST(12004)` → 响应 `STD_OK(6)`，data=`IMGroupListResponse`
  - 请求可携带 `IMGroupListRequest{user_id}`：当 `user_id` 非空时，仅返回“该用户已加入的群”；为空/未登录时返回全量
- `IM_GROUP_MEMBERS(12005)` → 响应 `STD_OK(6)`，data=`IMGroupMembersResponse`
- `IM_GROUP_MESSAGE_SEND(12006)` → 响应 `STD_OK(6)`，data=`IMChatAck(ack_type=DELIVERED, peer_id=group_id)`；服务端推送 `IM_GROUP_MESSAGE_RECEIVE(12007)` 给在线成员，data=`IMChatModel`
- `IM_GROUP_REMOVE_MEMBER(12009)` / `IM_GROUP_UPDATE_INFO(12010)` → 响应 `STD_OK(6)`（owner/admin）
- `IM_SYSTEM_STATUS(13000)`：服务端推送系统事件，data=`IMSystemEvent`（例如群解散/踢人/角色变更/群信息更新）
  - 广播范围：群内事件（join/leave/remove_member/update_info/set_admin/dismiss）会向群成员广播；remove_member 额外向被移除者推送

### 2.1 TypeScript SDK：p2p-ws-sdk-ts

**目录：** `p2p-ws-sdk-ts/`

**目录清单（关键入口）：**
- `p2p-ws-sdk-ts/bin/p2pd.ts`：CLI/守护进程入口
- `p2p-ws-sdk-ts/demo/*.ts`：示例（echo/center_join/peer_node 等）
- `p2p-ws-sdk-ts/src/PeerNode.ts`：PeerNode 主要逻辑（含 WS Server）
- `p2p-ws-sdk-ts/src/frame.ts`：8 字节帧头 encode/decode
- `p2p-ws-sdk-ts/src/handshake.ts`：握手相关（RSA-OAEP 等）
- `p2p-ws-sdk-ts/src/wrapper.ts`：P2PWrapper 编解码
- `p2p-ws-sdk-ts/package.json`：npm scripts/依赖

**使用方法（来自 README）：**
- Echo demo：

```bash
cd p2p-ws-sdk-ts
npm install
npm run demo-echo -- ..\\p2p-ws-protocol\\examples\\client.yaml
```

- Center join demo：

```bash
cd p2p-ws-sdk-ts
npm install
npm run demo-center-join -- ..\\p2p-ws-protocol\\examples\\center_client.yaml
```

更多见：`p2p-ws-sdk-ts/README.md`

### 2.2 Dart SDK：p2p-ws-sdk-dart

**目录：** `p2p-ws-sdk-dart/`

**目录清单（关键入口）：**
- `p2p-ws-sdk-dart/bin/p2pd.dart`：Peer node（server）入口
- `p2p-ws-sdk-dart/example/*.dart`：示例（echo/center_join/peer_connect/e2e_file_ops）
- `p2p-ws-sdk-dart/lib/p2p_ws_sdk.dart`：SDK 对外导出
- `p2p-ws-sdk-dart/lib/src/session.dart`：WebSocket 会话（握手 + 加密 wrapper I/O）
- `p2p-ws-sdk-dart/lib/src/server.dart`：WS Server（HttpServer upgrade）
- `p2p-ws-sdk-dart/lib/src/frame.dart`：8B 头 encode/decode
- `p2p-ws-sdk-dart/lib/src/handshake.dart`：RSA-OAEP(SHA-256) 解/加密
- `p2p-ws-sdk-dart/tool/verify_vectors.dart`：校验 test-vectors

**使用方法（来自 README）：**
- 校验 test-vectors：

```bash
cd p2p-ws-sdk-dart
dart pub get
dart run tool/verify_vectors.dart
```

- Echo client：

```bash
cd p2p-ws-sdk-dart
dart pub get
dart run example/echo_client.dart ../p2p-ws-protocol/examples/client.yaml
```

- Center join：

```bash
cd p2p-ws-sdk-dart
dart pub get
dart run example/center_join.dart ../p2p-ws-protocol/examples/center_client.yaml
```

- Peer node（server）：

```bash
cd p2p-ws-sdk-dart
dart pub get
dart run bin/p2pd.dart ../p2p-ws-protocol/examples/peer1.yaml
```

更多见：`p2p-ws-sdk-dart/README.md`

### 2.3 Java 参考实现：p2p-ws-sdk-java

**目录：** `p2p-ws-sdk-java/`

**目录清单（关键入口）：**
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/demo/WsServerMain.java`：WS Server demo
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/demo/DemoServerHandler.java`：demo handler
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/center/CenterServerMain.java`：Center server demo
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/center/CenterServerHandler.java`：center handler
- `p2p-ws-sdk-java/pom.xml`：构建/生成 protobuf（引用 `../p2p-ws-protocol/proto`）

**使用方法（来自 README）：**
- Demo server（需要 keyfile + YAML）：

```bash
python ..\\p2p-ws-protocol\\scripts\\gen_keyfile.py ..\\p2p-ws-protocol\\keyfiles\\demo.key 8388608
mvn -DskipTests package exec:java "-Dexec.mainClass=p2pws.sdk.demo.WsServerMain" "-Dexec.args=..\\p2p-ws-protocol\\examples\\server.yaml"
```

- Center server：

```bash
mvn -DskipTests package exec:java "-Dexec.mainClass=p2pws.sdk.center.CenterServerMain" "-Dexec.args=..\\p2p-ws-protocol\\examples\\center.yaml"
```

更多见：`p2p-ws-sdk-java/README.md`

### 2.4 Python SDK 骨架：p2p-ws-sdk-python

**目录：** `p2p-ws-sdk-python/`

**使用方法（来自 README）：**

```bash
python -m pip install websockets cryptography PyYAML grpcio-tools
python p2p-ws-sdk-python/scripts/gen_proto.py
python p2p-ws-sdk-python/demo/echo_client.py p2p-ws-protocol/examples/client.yaml
```

更多见：`p2p-ws-sdk-python/README.md`

### 2.5 C demo（Windows CNG）：p2p-ws-sdk-c

**目录：** `p2p-ws-sdk-c/`

**特点：**
- demo 级别实现：WS(Binary) + 最小 protobuf + RSA-OAEP(SHA-256) + keyfile XOR
- 依赖 Windows CNG（bcrypt/ncrypt），只支持 Windows 构建

**使用方法（来自 README）：**

```powershell
cd p2p-ws-sdk-c
.\p2pws_center_join.exe ..\p2p-ws-protocol\examples\peer1_c.yaml
.\p2pws_peer_node.exe ..\p2p-ws-protocol\examples\peer1_c.yaml
```

更多见：`p2p-ws-sdk-c/README.md`

## 3. Java P2P WebSocket（p2p-core）

### 3.1 WebSocket Server（Netty）

**入口类：**
- `p2p-core/src/main/java/javax/net/p2p/server/P2PServerWebSocket.java`

**默认 WS Path：**
- `P2PServerWebSocket.DEFAULT_PATH = "/p2p"`

**核心 pipeline（阅读入口）：**
- `HttpServerCodec`
- `HttpObjectAggregator`
- `WebSocketServerProtocolHandler(DEFAULT_PATH)`
- `WebSocketServerHandshakeMarkHandler`
- `WebSocketFrameToByteBufDecoder`
- `P2PWrapperSecureDecoder`
- `WebSocketReliabilityHandler`
- `ServerMessageProcessor`
- `ByteBufToWebSocketFrameEncoder`
- `P2PWrapperSecureEncoder`

### 3.2 WebSocket Client（Netty）

**入口类：**
- `p2p-core/src/main/java/javax/net/p2p/client/P2PClientWebSocket.java`

**关键特性：**
- 支持通过系统属性指定会话：`-Dp2p.ws.sessionId=...`
- 创建发送执行器时会建立 WS 连接：`ClientSendWebSocketMesageExecutor`

### 3.3 可靠性/重传层（WebSocketReliability）

**实现：**
- `p2p-core/src/main/java/javax/net/p2p/websocket/reliability/WebSocketReliabilityHandler.java`
- `p2p-core/src/main/java/javax/net/p2p/websocket/reliability/WebSocketReliabilitySession.java`

**常用参数（System properties）：**
- `p2p.ws.reliability.dropRate`
- `p2p.ws.reliability.ackTimeoutMs`
- `p2p.ws.reliability.tickMs`
- `p2p.ws.reliability.maxRetries`

### 3.4 最小可运行示例（参考单测）

最直接的“怎么启动 server/client 并发消息验证”的例子在：
- `p2p-core/src/test/java/javax/net/p2p/websocket/WebSocketReliabilityTest.java`

其中展示了：
- 启动 server：`P2PServerWebSocket.getInstance(P2PServerWebSocket.class, port)`
- 启动 client：`P2PClientWebSocket.getInstance(P2PClientWebSocket.class, "127.0.0.1", port, 4096)`
- 发送消息：`client.excute(P2PWrapper.build(seq, P2PCommand.HEART_PING, ...))`
- 关闭：`server.released()` / `client.shutdown()`

## 4. 非 P2P 协议的 WebSocket（仅供排查时区分）

这两类 WebSocket 与 `p2p-ws-protocol` 无关，常见用途是监控/IM 网关：

- UDP 监控 WebSocket（推 JSON 文本）：
  - `p2p-core/src/main/java/javax/net/p2p/monitor/web/WebSocketServer.java`
  - `p2p-core/src/main/resources/static/index.html`（前端 WebSocket 订阅）
- IM 模块 WebSocket 网关（MobileIMSDK 协议族）：
  - `p2p-im/src/main/java/net/x52im/mobileimsdk/server/network/GatewayWebsocket.java`
  - `p2p-im/src/main/java/net/x52im/mobileimsdk/server/network/websocket/MBWebsocketClientInboundHandler.java`

## 5. 快速定位清单（复制粘贴用）

- 协议规范：`p2p-ws-protocol/spec.md`
- TS SDK：`p2p-ws-sdk-ts/README.md`，`p2p-ws-sdk-ts/bin/p2pd.ts`
- Dart SDK：`p2p-ws-sdk-dart/README.md`，`p2p-ws-sdk-dart/bin/p2pd.dart`
- Java 参考 SDK：`p2p-ws-sdk-java/README.md`，`p2p-ws-sdk-java/src/main/java/p2pws/sdk/demo/WsServerMain.java`
- Python SDK：`p2p-ws-sdk-python/README.md`
- C demo：`p2p-ws-sdk-c/README.md`
- Java p2p-core WebSocket Server：`p2p-core/src/main/java/javax/net/p2p/server/P2PServerWebSocket.java`
- Java p2p-core WebSocket Client：`p2p-core/src/main/java/javax/net/p2p/client/P2PClientWebSocket.java`
- Java p2p-core WS 可靠性层：`p2p-core/src/main/java/javax/net/p2p/websocket/reliability/WebSocketReliabilityHandler.java`
