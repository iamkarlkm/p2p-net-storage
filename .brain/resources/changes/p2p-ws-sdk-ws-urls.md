---
updated: "2026-05-09T15:02:52Z"
---
# p2p-ws-sdk：加密模式与 ws_urls

## Summary
- 协议扩展：`p2pws.Hand`/`p2pws.HandAckPlain` 增加 `crypto_mode` 与随机 key 字段（向后兼容，仅新增 optional 字段）。
- 新增三类协商模式（字符串）：
  - `PLAIN`
  - `CLIENT_RANDOM_XOR_RSA_OAEP`
  - `SERVER_RANDOM_XOR_RSA_OAEP`
  - 旧默认保持：`KEYFILE_XOR_RSA_OAEP`
- Java 参考实现（center/demo server）支持按模式选择 `keyfile XOR` 或 `repeat XOR`，并在非 keyfile 模式下跳过 `CryptUpdate` 下发。
- TS 参考实现（PeerNode）支持：
  - 配置扩展：`encryption_enabled`、`encryption_mode`、`random_key_bytes`、`ws_urls`
  - 握手：按 `crypto_mode` 发送/接收并切换 cipher；keyfile 模式继续用 offset+keyfile，random 模式用 repeat XOR，plain 模式全程明文数据帧（仍保留握手/签名）。

## Verification
- `mvn -f p2p-ws-sdk-java/pom.xml test`
- `npm -C p2p-ws-sdk-ts run verify-handshake`
- `npm -C p2p-ws-sdk-ts run verify-vectors`
