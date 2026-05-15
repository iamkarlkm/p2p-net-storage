# p2p-ws-sdk：同步 Dart/Python/C（crypto_mode + ws_urls/server_group）

## 目标

- 将 `p2p-ws-protocol` 的握手协商扩展（`crypto_mode` + 随机 key 字段）同步到 Dart/Python/C SDK。
- 补齐最小 “固定服务器组” 能力：`ws_urls` 按顺序尝试连接，完成握手后提供 request/response。

## 变更摘要

- Dart
  - 新增/补齐：`P2PCryptoMode` 常量、`xorRepeat`、会话侧按 `crypto_mode` 切换（keyfile / client_random / server_random / plain）。
  - 新增：`ServerGroupClient`（最小 server_group 客户端）。
- Python
  - 新增：`P2PClient`（最小连接/握手/请求响应；支持 keyfile/client_random/server_random/plain）。
  - 更新：`demo/echo_client.py` 支持 `ws_urls`、`encryption_enabled/encryption_mode/crypto_mode/random_key_bytes`。
- C
  - 扩展：Hand/HandAckPlain 的编解码（`crypto_mode`、`client_random_key`、`server_random_key`）。
  - 新增：`p2pws_xor_repeat`，并在 `peer_node` demo 中按 `crypto_mode` 切换 cipher 与 wire flags。
  - YAML：支持 `ws_urls`（取首项回填 `ws_url`），支持 `encryption_enabled/encryption_mode -> crypto_mode` 推导。

## 验证

- Dart：`dart analyze p2p-ws-sdk-dart`
- Python：`python -c "import glob,py_compile; ..."`
- C：`cmake -S p2p-ws-sdk-c -B p2p-ws-sdk-c/build-msvc -G \"Visual Studio 17 2022\" -A x64` + `cmake --build p2p-ws-sdk-c/build-msvc --config Release`

