# IM-over-P2P 应用协议规范 v1.0 — 补充章节

> 本文档为 `IM_OVER_P2P_PROTOCOL.md` 的补充，基于 `p2p-ws-protocol/spec.md` 和 `.proto` 定义。

---

## 附录 A：完整 Protobuf Schema

### A.1 P2PWrapper（通用封装）

```protobuf
syntax = "proto3";
package p2pws;

message P2PWrapper {
  int32 seq = 1;           // 序列号，客户端自增，从1开始
  int32 command = 2;       // 命令号（负数=控制命令，正数=业务命令）
  bytes data = 3;          // 载荷数据（protobuf序列化或JSON字节）
  map<string, string> headers = 4;  // 可选扩展头
}
```

### A.2 握手消息（p2p_control.proto）

```protobuf
syntax = "proto3";
package p2pws;

// HAND (-10001) 请求载荷
message Hand {
  bytes client_pubkey = 1;        // 客户端 RSA 公钥 SPKI (DER)
  repeated bytes key_ids = 2;     // 客户端持有的 keyId 列表
  uint32 max_frame_payload = 3;   // 客户端期望的最大帧负载
  string client_id = 4;           // 客户端标识
}

// HAND_ACK (-10002) 明文载荷（由服务端 RSA-OAEP 加密后传输）
message HandAckPlain {
  bytes session_id = 1;           // 16 bytes 随机会话ID
  bytes selected_key_id = 2;      // 选定的 keyId (32 bytes)
  uint32 offset = 3;              // XOR 混淆偏移量
  uint32 max_frame_payload = 4;   // 协商后的最大帧负载
  uint32 header_policy_id = 5;    // 头策略ID（可选，默认0）
}

// CRYPT_UPDATE (-10010) 动态密钥更新
message CryptUpdate {
  bytes key_id = 1;
  uint32 offset = 2;
  int32 effective_from_seq = 3;   // 从哪个seq开始生效
}

// HEADER_UPDATE (-10011) 头策略更新
message HeaderUpdate {
  int32 effective_from_seq = 1;
  uint32 header_policy_id = 2;
}
```

### A.3 文件传输消息（p2p_data.proto）

```protobuf
syntax = "proto3";
package p2pws;

message FileDataModel {
  uint32 store_id = 1;
  uint64 length = 2;
  bytes data = 3;
  string path = 4;
  string md5 = 5;
  uint32 block_size = 6;
}

message FileSegmentsDataModel {
  uint32 store_id = 1;
  uint64 length = 2;
  uint64 start = 3;
  uint32 block_index = 4;
  uint32 block_size = 5;
  bytes block_data = 6;
  string block_md5 = 7;
  string path = 8;
  string md5 = 9;
}

message FilesCommandModel {
  uint32 store_id = 1;
  string command = 2;
  repeated string params = 3;
  bytes data = 4;
}
```

---

## 附录 B：Wire Frame 详细定义

### B.1 帧结构（8字节头 + N字节载荷）

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         length (u32)                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|          magic (u16)          | version(u8) |  flags  (u8)   |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          cipherPayload...                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

**字段说明：**

| 字段 | 类型 | 大小 | 说明 |
|------|------|------|------|
| `length` | u32 | 4 bytes | 帧长度校验（建议启用 LEN_CHECK） |
| `magic` | u16 | 2 bytes | 魔数，默认 `0x1234` |
| `version` | u8 | 1 byte | 协议版本，当前 `1` |
| `flags` | u8 | 1 byte | 标志位 |
| `cipherPayload` | bytes | N | protobuf序列化后的XOR混淆数据 |

### B.2 Flags 位定义

```
  7   6   5   4   3   2   1   0
+---+---+---+---+---+---+---+---+
|  Rsv  |  Rsv  |  Rsv  |CMP|ENC|
+---+---+---+---+---+---+---+---+
```

| 位 | 名称 | 说明 |
|----|------|------|
| bit0 | `ENCRYPTED` | `1`=cipherPayload已XOR混淆，`0`=明文 |
| bit1 | `COMPRESSED` | `1`=cipherPayload对应明文已压缩（预留） |
| bit2 | `LEN_CHECK` | `1`=强制校验 `length == payloadLen` |
| bit3-7 | Reserved | 保留，必须为0 |

### B.3 帧状态流转

```
PLAIN (flags=4)  →  握手完成  →  ENCRYPTED (flags=5)
     ↑                                          |
     └───────── 重连 / 密钥更新 ─────────────────┘
```

---

## 附录 C：握手状态机

### C.1 状态定义

| 状态 | 说明 |
|------|------|
| `DISCONNECTED` | 未连接 |
| `WS_CONNECTING` | WebSocket 连接中 |
| `WS_CONNECTED` | WebSocket 已连接，等待 HAND |
| `HAND_SENT` | HAND 已发送，等待 HAND_ACK |
| `ENCRYPTED` | 握手完成，正常通信 |
| `RECONNECTING` | 断开重连中 |

### C.2 状态转换图

```
                    connect()
DISCONNECTED  ───────────────→  WS_CONNECTING
                                     │
                              onOpen()
                                     ▼
                             WS_CONNECTED
                                     │
                                send HAND
                                     ▼
                              HAND_SENT
                                     │
                             receive HAND_ACK
                              decrypt & verify
                                     ▼
                              ENCRYPTED ◄───────┐
                                     │          │
                           send/receive data     │
                                     │          │
                              onClose/Error      │
                                     ▼          │
                              RECONNECTING       │
                                     │          │
                              reconnect()        │
                                     └───────────┘
```

### C.3 握手详细流程

**Step 1：建立 WebSocket 连接**
```
Client ──WebSocket Binary──→ Server
         ws://host:port/p2p?token=<jwt>&userId=<uid>&deviceId=<did>
```

**Step 2：客户端发送 HAND (-10001)**
```
P2PWrapper {
  seq: 1
  command: -10001
  data: protobuf(Hand {
    client_pubkey: <RSA公钥SPKI>,
    key_ids: [<keyId1>, <keyId2>],
    max_frame_payload: 4194304,
    client_id: "im_mobile_001"
  })
}
```

**Step 3：服务端返回 HAND_ACK (-10002)**
```
P2PWrapper {
  seq: 1
  command: -10002
  data: RSA-OAEP-Encrypt(protobuf(HandAckPlain {
    session_id: <16 bytes random>,
    selected_key_id: <32 bytes>,
    offset: <rand_u32 % (K-M)>,
    max_frame_payload: 4194304,
    header_policy_id: 0
  }))
}
```

**Step 4：客户端解密 HAND_ACK**
- 使用客户端 RSA 私钥解密 `data`
- 反序列化 `HandAckPlain`
- 保存 `(sessionId, selectedKeyId, offset)`
- 后续所有帧 flags 改为 `ENCRYPTED=5`

---

## 附录 D：命令号段完整对照表

### D.1 控制命令（SDK内置处理）

| 命令值 | 名称 | 方向 | 说明 |
|--------|------|------|------|
| `-10001` | `HAND` | C→S | 握手请求 |
| `-10002` | `HAND_ACK` | S→C | 握手响应 |
| `-10010` | `CRYPT_UPDATE` | S→C | 密钥参数更新 |
| `-10011` | `HEADER_UPDATE` | S→C | 头策略更新 |

### D.2 IM 业务命令

| 命令值 | 名称 | 方向 | 说明 |
|--------|------|------|------|
| `20001` | `IM_CHAT` | C↔S | 单聊消息 |
| `20002` | `IM_GROUP_CHAT` | C↔S | 群聊消息 |
| `20003` | `IM_READ_RECEIPT` | C↔S | 已读回执 |
| `20004` | `IM_RECALL` | C↔S | 消息撤回 |
| `20005` | `IM_TYPING` | C↔S | 正在输入 |
| `20006` | `IM_PRESENCE` | C↔S | 在线状态 |
| `20007` | `IM_ACK` | S→C | 服务端确认 |
| `20008` | `IM_DELIVERY_RECEIPT` | S→C | 送达回执 |
| `20009` | `IM_SYSTEM_NOTIFICATION` | S→C | 系统通知 |
| `20010` | `IM_HEARTBEAT` | C↔S | 应用层心跳 |
| `20011` | `IM_HISTORY_SYNC` | C↔S | 历史消息同步 |
| `20012` | `IM_ERROR` | S→C | 业务错误通知 |

### D.3 文件传输命令（P2P原生）

| 命令值 | 名称 | 说明 |
|--------|------|------|
| `7` | `GET_FILE` | 获取文件 |
| `14` | `PUT_FILE` | 上传文件 |
| `19` | `FILES_COMMAND` | 文件命令 |
| `20` | `GET_FILE_SEGMENTS` | 获取文件分片 |
| `21` | `PUT_FILE_SEGMENTS` | 上传文件分片 |
| `50` | `FILE_RENAME` | 文件重命名 |
| `51` | `FILE_LIST` | 文件列表 |
| `1001` | `FILE_PUT_REQUEST` | 文件上传请求 |
| `1002` | `FILE_PUT_RESPONSE` | 文件上传响应 |
| `1003` | `FILE_GET_REQUEST` | 文件下载请求 |
| `1004` | `FILE_GET_RESPONSE` | 文件下载响应 |

---

## 附录 E：错误码表

### E.1 标准错误码（SDK层）

| 错误码 | 名称 | 说明 | 处理建议 |
|--------|------|------|----------|
| `STD_UNKNOWN` | 未知命令 | 命令未注册/未实现 | 检查 command 值 |
| `STD_ERROR` | 通用错误 | 内部错误 | 记录日志，上报监控 |
| `INVALID_DATA` | 数据无效 | payload 无法解析 | 检查 protobuf/JSON 格式 |
| `UNAUTHORIZED` | 未授权 | 未握手/未登录 | 重新握手或登录 |
| `SERVICE_UNAVAILABLE` | 服务不可用 | 命令所属服务未启用 | 检查服务配置 |

### E.2 IM业务错误码（应用层）

| 错误码 | 名称 | 说明 |
|--------|------|------|
| `MESSAGE_PARSE_ERROR` | 消息解析失败 | JSON payload 格式错误 |
| `INVALID_SENDER` | 发送者无效 | senderId 与 token 不匹配 |
| `CONVERSATION_NOT_FOUND` | 会话不存在 | conversationId 无效 |
| `NOT_IN_GROUP` | 不在群内 | 发送者非群成员 |
| `MESSAGE_TOO_LARGE` | 消息过大 | 超过 maxFramePayload |
| `RATE_LIMITED` | 频率限制 | 发送过于频繁 |
| `RECALL_EXPIRED` | 撤回超时 | 超过可撤回时间窗口 |

---

## 附录 F：XOR 混淆算法详解

### F.1 参数

- `keyfile`: 离线共享随机文件，长度 `K`
- `offset`: 会话固定偏移，`0 <= offset < K`
- `plainLen`: 本帧明文长度 `L`

### F.2 约束

```
offset + L <= K
```

若不满足：
1. 发送 `CRYPT_UPDATE` 请求新的 offset
2. 或断开连接重新握手

### F.3 伪代码

```python
def xor_cipher(plain: bytes, keyfile: bytes, offset: int) -> bytes:
    K = len(keyfile)
    L = len(plain)
    assert 0 <= offset < K, "offset out of range"
    assert offset + L <= K, "offset+plainLen exceeds keyfile length"
    
    cipher = bytearray(L)
    for i in range(L):
        cipher[i] = plain[i] ^ keyfile[offset + i]
    return bytes(cipher)

def xor_decipher(cipher: bytes, keyfile: bytes, offset: int) -> bytes:
    # 解密与加密使用同一算法
    return xor_cipher(cipher, keyfile, offset)
```

### F.4 每帧独立执行

**重要**：每帧从 `i=0` 开始，不累积偏移。

```
Frame 1: cipher[0] = plain[0] ^ keyfile[offset + 0]
         cipher[1] = plain[1] ^ keyfile[offset + 1]

Frame 2: cipher[0] = plain[0] ^ keyfile[offset + 0]  ← 重新从0开始
         cipher[1] = plain[1] ^ keyfile[offset + 1]
```

---

## 附录 G：SDK 实现对照

### G.1 TypeScript（桌面端）

```typescript
// frame.ts
export type WireHeader = { length: number; magic: number; version: number; flags: number }
export type WireFrame = { header: WireHeader; cipherPayload: Uint8Array }
export const HEADER_LEN = 8

export function decodeFrame(wsBinaryPayload: Uint8Array): WireFrame { ... }
export function encodeFrame(header: WireHeader, cipherPayload: Uint8Array): Uint8Array { ... }

// xor.ts
export function xorNoWrap(plain: Uint8Array, keyfile: Uint8Array, offset: number): Uint8Array { ... }

// handshake.ts
export async function generateRsaKeyPair(): Promise<CryptoKeyPair> { ... }
export async function exportPublicKeySpki(publicKey: CryptoKey): Promise<Uint8Array> { ... }
export async function rsaOaepSha256Decrypt(privateKey: CryptoKey, cipher: Uint8Array): Promise<Uint8Array> { ... }

// wrapper.ts
export type P2PWrapper = { seq: number; command: number; data?: Uint8Array; headers?: Record<string, string> }
export function encodeWrapper(root: protobuf.Root, w: P2PWrapper): Uint8Array { ... }
export function decodeWrapper(root: protobuf.Root, data: Uint8Array): P2PWrapper { ... }
```

### G.2 Dart（移动端）

```dart
// p2p_frame.dart
class WireHeader { ... }
class WireFrame { ... }

// p2p_xor.dart
Uint8List xorNoWrap(Uint8List plain, Uint8List keyfile, int offset) { ... }

// p2p_handshake.dart
class P2PHandshake {
  static AsymmetricKeyPair<RSAPublicKey, RSAPrivateKey> generateKeyPair() { ... }
  static Uint8List exportPublicKeySpki(RSAPublicKey publicKey) { ... }
  static Uint8List rsaOaepSha256Decrypt(RSAPrivateKey privateKey, Uint8List cipher) { ... }
  static Uint8List rsaOaepSha256Encrypt(RSAPublicKey publicKey, Uint8List plain) { ... }
}
```

### G.3 Java（后端）

```java
// P2PWrapperCodec.java
public class P2PWrapperCodec {
    public static byte[] encode(P2PWrapper wrapper) { ... }
    public static P2PWrapper decode(byte[] data) { ... }
}

// P2PFrameCodec.java
public class P2PFrameCodec {
    public static WireFrame decode(byte[] frameBytes) { ... }
    public static byte[] encode(WireFrame frame) { ... }
}

// XorCipher.java
public class XorCipher {
    public static byte[] cipher(byte[] plain, byte[] keyfile, int offset) { ... }
}

// P2PHandshakeHandler.java
public class P2PHandshakeHandler {
    public void handleHand(ChannelHandlerContext ctx, P2PWrapper hand) { ... }
    public void handleHandAck(ChannelHandlerContext ctx, P2PWrapper handAck) { ... }
}
```

---

## 附录 H：关键常量汇总

| 常量 | 值 | 说明 |
|------|-----|------|
| `HEADER_LEN` | 8 | WireHeader 字节长度 |
| `DEFAULT_MAGIC` | `0x1234` | 默认魔数 |
| `DEFAULT_VERSION` | 1 | 协议版本 |
| `FLAGS_PLAIN` | 4 | 明文帧标志 |
| `FLAGS_ENCRYPTED` | 5 | 加密帧标志（bit0=1） |
| `DEFAULT_MAX_FRAME_PAYLOAD` | 4194304 | 默认最大帧负载（4MB） |
| `SESSION_ID_LEN` | 16 | sessionId 字节长度 |
| `KEY_ID_LEN` | 32 | keyId 字节长度（SHA-256） |
| `RSA_KEY_SIZE` | 2048 | RSA 密钥长度 |
| `RSA_HASH` | SHA-256 | RSA-OAEP 哈希算法 |
| `HAND_COMMAND` | -10001 | HAND 命令 |
| `HAND_ACK_COMMAND` | -10002 | HAND_ACK 命令 |
| `CRYPT_UPDATE_COMMAND` | -10010 | CRYPT_UPDATE 命令 |
| `HEADER_UPDATE_COMMAND` | -10011 | HEADER_UPDATE 命令 |
| `IM_COMMAND_BASE` | 20001 | IM 业务命令起始 |

---

## 附录 I：参考实现路径

| 语言 | 路径 | 状态 |
|------|------|------|
| TypeScript | `multi_agent/projects/im-desktop/src/p2p/` | ✅ 已实现 |
| Dart | `multi_agent/projects/im-mobile/lib/p2p/` | ✅ 已实现 |
| Java | `multi_agent/projects/im-backend/im-service-websocket/src/main/java/p2pws/` | ✅ 已有Protobuf类 |
| Python | `I:/2025/code/P2P-Net-StorageSystem/p2p-net-storage/p2p-ws-sdk-python/` | ✅ 已有 |
| C | `I:/2025/code/P2P-Net-StorageSystem/p2p-net-storage/p2p-ws-sdk-c/` | ✅ 已有 |

---

*补充文档生成时间*: 2026-04-20
*基于*: `p2p-ws-protocol/spec.md` v1 + `proto/*.proto`
*适用版本*: IM-over-P2P v1.0
