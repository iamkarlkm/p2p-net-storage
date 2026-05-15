---
updated: "2026-05-11T00:00:00Z"
---
# p2p-ws-sdk-c core_compat（p2p-core wire-format）

## What Changed

- 新增 `p2pws_core_compat`：core 帧（`length(int32)+magic(int32)+payload`）、payload XOR（repeat-key）、Protostuff runtime schema 兼容的 `P2PWrapper/HAND/LOGIN` 编解码，以及同步阻塞式 request/response（按 seq 匹配）。
- RSA 补齐：增加 PKCS1 v1.5 私钥分段加密（用于 HAND 的 xorKey）、公钥分段解密与 SHA256withRSA 验签（基于 Windows CNG）。
- 新增 `p2pws_core_rpc`：最小 RPC proto 编解码（RpcFrame/Meta + discover/health/echo request/response 的子集）。
- 新增 demo：`p2pws_core_ws_rpc_discover_echo`（HAND/LOGIN 后调用 `RPC_DISCOVER/RPC_HEALTH/RPC_UNARY(Echo)`）。

## Verification

- `cmake -S p2p-ws-sdk-c -B p2p-ws-sdk-c/build`
- `cmake --build p2p-ws-sdk-c/build --config Release`

