# p2p-ws-sdk-c：demo 全量支持 crypto_mode

## 变更

- 新增 `p2pws_cipher`：封装 `crypto_mode` 选择、Hand/HandAckPlain 协商落地、以及 keyfile/repeat/plain 的统一加解密与 wire flags 选择。
- 新增 `p2pws_rand_bytes`（基于系统 RNG），用于 `CLIENT_RANDOM_XOR_RSA_OAEP` 等模式生成随机 key。
- 更新 demo：`center_join` / `peer_connect` / `relay_echo` 不再假设一定使用 keyfile offset XOR，可按 `crypto_mode` 运行（含 plain）。

## 验证

- `cmake --build p2p-ws-sdk-c/build-msvc --config Release`

