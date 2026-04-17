# P2P WebSocket 协议规范（v1）

## 1. 范围

本规范定义基于 WebSocket Binary Frame 的 P2P 通信层：
- 统一 8 字节明文头（big-endian）
- payload 使用 protobuf `P2PWrapper`
- payload 混淆：XOR（每包从 0 开始，offset 固定）
- 密钥材料来自离线共享随机文件（keyfile）
- 控制/系统命令使用负数号段 `[-99999..-10001]`

本规范不依赖 TLS；握手阶段使用 RSA-OAEP(SHA-256) 交换会话参数。

## 2. 术语

- keyfile：离线交换的随机文件，双方持有同一份内容
- keyId：`SHA256(keyfileBytes)` 的 32 字节摘要（协议内使用 raw bytes）
- session：一次连接/重连关联的会话，持有 `(sessionId, keyId, offset, headerPolicy)`

## 3. WebSocket 传输

- 使用 WebSocket Binary Frame 承载协议数据
- 默认 path：`/p2p`（可配置）
- `max_frame_payload` 默认：4MB（不含 8 字节头）

## 3.1 YAML 配置（SDK 约定）

为便于跨语言 SDK 落地，推荐使用统一的 YAML 配置文件提供运行参数。

服务端示例：`examples/server.yaml`

客户端示例：`examples/client.yaml`

客户端可选字段：
- `key_id_sha256_hex`：期望的 keyfile sha256(hex)。设置后客户端应校验本地 keyfile 是否匹配。
- `rsa_private_key_pem_path`：客户端 RSA 私钥 pem 路径（PKCS8）。设置后客户端可复用私钥，避免每次启动生成临时 keypair。

另见：
- `docs/config.md`
- `docs/overview.md`

## 4. 线协议（Wire Frame）

### 4.1 帧结构（big-endian）

WebSocket Binary Frame payload：

- `length:u32`（4 bytes）
- `magic:u16`（2 bytes）
- `version:u8`（1 byte）
- `flags:u8`（1 byte）
- `cipherPayload:bytes[...]`（长度 = WebSocketFrameLen - 8）

说明：
- 头部 8 字节明文，不参与混淆
- 真实 payload 长度以 WebSocket Frame 长度为准：`payloadLen = frameLen - 8`
- `length` 字段允许可变：用于校验/混淆/未来兼容 TCP

### 4.2 flags 位定义（建议）

- bit0 `ENCRYPTED`：cipherPayload 已按 XOR 混淆
- bit1 `COMPRESSED`：cipherPayload 对应的明文是压缩数据（未定义压缩算法时为 0）
- bit2 `LEN_CHECK`：启用 `length == payloadLen` 强校验
- bit3..bit7：保留

解析规则：
- 若 `LEN_CHECK=1`：接收端必须校验 `length == payloadLen`，否则丢弃
- 若 `LEN_CHECK=0`：接收端可忽略 length（建议仍记录异常值用于监控）

## 5. Payload（protobuf）

`plainPayload` 为 protobuf 编码后的 `P2PWrapper`：

- `seq:int32`
- `command:int32`（支持负数）
- `data:bytes`
- `headers:map<string,string>`（可选）

混淆流程：
- `plainPayload = ProtobufSerialize(P2PWrapper)`
- `cipherPayload = Xor(plainPayload, keyfile, offset)`

解混淆流程：
- `plainPayload = Xor(cipherPayload, keyfile, offset)`
- `P2PWrapper = ProtobufDeserialize(plainPayload)`

## 6. 命令号段与分发

- 控制/系统命令号段：`[-99999..-10001]`
- 业务命令号段：其他值

SDK 处理规则：
- `command` 在控制号段：由 SDK 内置处理，不进入业务 handler
- 否则：进入业务 handler（请求/响应匹配语义由上层定义，通常 `response.seq == request.seq`）

### 6.1 安全与管控（实现要求）

本网络按“强管控网络（默认拒绝）”实现，推荐实现至少满足：

- 未知命令：命令未注册/未实现，必须拒绝（建议 `STD_UNKNOWN` 或 `STD_ERROR`），不得进入业务逻辑
- 无效数据：payload 无法解析或不符合约定模型，必须拒绝（建议 `INVALID_DATA` 或 `STD_ERROR`）
- 未知/未授权用户：未握手/未登录/不在 allowlist 的用户请求，必须拒绝
- 未启用服务：命令所属服务类别未启用或未加载，必须拒绝并提示 `service unavailable`

建议保留命令（v1）：
- `-10001`：HAND
- `-10002`：HAND_ACK
- `-10010`：CRYPT_UPDATE
- `-10011`：HEADER_UPDATE

## 7. keyfile 与 keyId

### 7.1 keyId 定义

- `keyId = SHA256(keyfileBytes)`，长度 32 bytes
- 协议内传输使用 raw bytes（长度必须为 32）

### 7.2 本地查找

客户端/服务端必须实现：
- `KeyFileProvider.get(keyIdBytes32) -> RandomAccessReader`

若找不到对应 keyfile：
- HAND 阶段：握手失败
- 运行阶段：连接应断开或请求更新 keyId（取决于实现策略）

## 8. XOR 混淆算法（固定 offset）

### 8.1 参数

- `keyfile`：字节序列，长度 `K`
- `offset`：会话固定偏移，`0 <= offset < K`
- `plainLen`：本帧 `plainPayload` 字节长度 `L`

### 8.2 约束（默认）

不允许循环取模（推荐默认）：
- 必须满足 `offset + L <= K`
- 若不满足：应通过 `CRYPT_UPDATE` 下发新的 offset，或断开连接

### 8.3 算法

对每个 WebSocket frame 独立执行（每包从 0 开始）：

- `cipher[i] = plain[i] XOR keyfile[offset + i]`，`i in [0, L)`

解密同算法：

- `plain[i] = cipher[i] XOR keyfile[offset + i]`

## 9. 握手（RSA-OAEP SHA-256）

### 9.1 目标

握手用于协商并下发 session 参数：
- `sessionId`：16 bytes random
- `selectedKeyId`：32 bytes
- `offset`：服务端随机生成（会话固定）
- `maxFramePayload`：默认 4MB，可协商
- `headerPolicyId`：可选

握手不传输 keyfile 内容。

### 9.2 HAND（-10001）

客户端发送 `P2PWrapper(command=-10001)`，其 `data` 为 protobuf `Hand`。

### 9.3 HAND_ACK（-10002）

服务端发送 `P2PWrapper(command=-10002)`，其 `data` 为 RSA-OAEP(SHA-256) 加密后的 `HandAckPlain` 字节。

客户端解密 `data` 后得到 `HandAckPlain`，并进入 ENCRYPTED 状态（后续 payload 按 XOR 混淆）。

### 9.4 offset 生成规则（服务端）

设：
- `K = keyfileLen`
- `M = negotiatedMaxFramePayload`（默认 4MB）

要求：
- `K > M`

生成：
- `offset = rand_u32() % (K - M)`

## 10. 动态更新

### 10.1 加密参数更新（CRYPT_UPDATE，-10010）

用于按需切换 `(keyId, offset)`，并指定生效点 `effectiveFromSeq`。

### 10.2 头策略更新（HEADER_UPDATE，-10011）

用于按需更新 `magic/version/flags/length` 的混淆策略，建议以 `effectiveFromSeq` 作为生效边界，避免两端不同步。
