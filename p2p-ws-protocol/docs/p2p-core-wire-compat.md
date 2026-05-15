# p2p-core wire-format 兼容（用于 p2p-ws-sdk 直连 p2p-core ServerWebSocket/Quic/Udp）

本文件描述如何让 `p2p-ws-sdk` 在不改变服务端实现的前提下，直接与 `p2p-core` 的服务端通信（尤其是 `P2PServerWebSocket`），即“走同一套 wire-format”。

## 1. 传输帧（WebSocket/QUIC）

- 帧头：8 字节
  - `length`: int32 big-endian，表示 **payload 字节数**
  - `magic`: int32 big-endian，与服务端 channel 的 `ChannelUtils.MAGIC` 一致
- 帧体：`payload[length]`
  - payload 是 **Protostuff** 编码后的消息体（通常是 `P2PWrapper`）
- XOR：
  - 当连接上已建立 `ChannelUtils.XOR_KEY` 时，XOR 只作用于 **payload 区间**（不包含 8 字节头）
  - XOR 算法：`data[i] ^= key[i % key.length]`

服务端 WebSocket 的关键编解码链路：
- [P2PWrapperSecureDecoder](file:///i:/2025/code/P2P-Net-StorageSystem/p2p-net-storage/p2p-core/src/main/java/javax/net/p2p/codec/P2PWrapperSecureDecoder.java)
- [P2PWrapperSecureEncoder](file:///i:/2025/code/P2P-Net-StorageSystem/p2p-net-storage/p2p-core/src/main/java/javax/net/p2p/codec/P2PWrapperSecureEncoder.java)
- [P2PServerWebSocket](file:///i:/2025/code/P2P-Net-StorageSystem/p2p-net-storage/p2p-core/src/main/java/javax/net/p2p/server/P2PServerWebSocket.java)

## 2. Protostuff 消息体：P2PWrapper（外壳）

`p2p-core` 侧的 `P2PWrapper<T>` 是 Protostuff runtime schema（无显式 tag 注解），字段序与编号由字段声明顺序决定：
- field 1: `seq`（int32 varint）
- field 2: `command`（enum，运行时策略决定编码；需要和 p2p-core 保持一致）
- field 3: `data`（通常是 bytes；握手/登录响应就是 bytes）

参考实现：
- [P2PWrapper.java](file:///i:/2025/code/P2P-Net-StorageSystem/p2p-net-storage/p2p-core/src/main/java/javax/net/p2p/model/P2PWrapper.java)
- [SerializationUtil.serialize/deserialize](file:///i:/2025/code/P2P-Net-StorageSystem/p2p-net-storage/p2p-core/src/main/java/javax/net/p2p/utils/SerializationUtil.java)

## 3. 握手（HAND）与登录（LOGIN）

当 `auth.enabled=true` 时，除 `HAND/HEART_*`（以及控制类 `STD_CANCEL/STD_STOP`）外，服务端会要求：
1) 先 `HAND`，建立 `XOR_KEY`
2) 再 `LOGIN`，将 `AUTH_LOGGED_IN=true`

### 3.1 HAND

- 请求命令：`P2PCommand.HAND`
- `P2PWrapper.data`：`HandshakeRequest` 的 protostuff bytes（注意：HAND 外壳本身是明文；后续才 XOR）
- 签名与加密逻辑：
  - `HandshakePayloads.requestSigPayload(req)` 生成签名 payload
  - 客户端用 `SHA256withRSA` 对 payload 签名写入 `req.signature`
  - 客户端生成 `xorKey`（长度通常 4096），用 **RSA private key + PKCS1Padding** 分块“加密”写入 `req.encryptedXorKey`

服务端处理入口：
- [HandServerHandler](file:///i:/2025/code/P2P-Net-StorageSystem/p2p-net-storage/p2p-core/src/main/java/javax/net/p2p/server/handler/HandServerHandler.java)
- [HandshakePayloads](file:///i:/2025/code/P2P-Net-StorageSystem/p2p-net-storage/p2p-core/src/main/java/javax/net/p2p/auth/utils/HandshakePayloads.java)
- [AuthCrypto](file:///i:/2025/code/P2P-Net-StorageSystem/p2p-net-storage/p2p-core/src/main/java/javax/net/p2p/auth/utils/AuthCrypto.java)

### 3.2 LOGIN

- 请求命令：`P2PCommand.LOGIN`
- `P2PWrapper.data`：`LoginRequest` 的 protostuff bytes
- `LoginPayloads.requestSigPayload(req)` 生成 payload，用 `SHA256withRSA` 签名

服务端处理入口：
- [LoginServerHandler](file:///i:/2025/code/P2P-Net-StorageSystem/p2p-net-storage/p2p-core/src/main/java/javax/net/p2p/server/handler/LoginServerHandler.java)
- [LoginPayloads](file:///i:/2025/code/P2P-Net-StorageSystem/p2p-net-storage/p2p-core/src/main/java/javax/net/p2p/auth/utils/LoginPayloads.java)

## 4. 命令编码（P2PCommand）与跨语言一致性

`p2p-core` 的 `P2PCommand` 是 Java enum，网络层的 command 字段属于 Protostuff enum 编码。为了跨语言严格兼容，建议：
- 从 `p2p-core/src/main/java/javax/net/p2p/api/P2PCommand.java` 自动生成 “enum 声明顺序 -> ordinal” 映射
- 各语言 SDK 均引用同一份生成物（JSON/代码常量），避免手写对齐

