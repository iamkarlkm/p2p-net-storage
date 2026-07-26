---
updated: "2026-05-11T00:00:00Z"
---
# p2p-ws-sdk-dart core_compat（p2p-core wire-format）

## What Changed

- 新增 `p2p-ws-sdk-dart/lib/src/core_compat`：实现 p2p-core 的 WS core 帧（`length(int32)+magic(int32)+payload`）、payload XOR（repeat-key）、以及 Protostuff runtime schema 兼容的 `P2PWrapper/HAND/LOGIN` 编解码。
- 新增最小 RPC（proto-lite）：支持构造 `RpcFrame(OPEN, UNARY)` 并调用 `RPC_DISCOVER/RPC_HEALTH/RPC_UNARY(Echo)`。
- 新增示例：`p2p-ws-sdk-dart/example/core_ws_rpc_discover_echo.dart`（握手/登录后 discover+health+echo）。

## Verification

- `dart analyze p2p-ws-sdk-dart`
- `cmake -S p2p-ws-sdk-c -B p2p-ws-sdk-c/build`
- `cmake --build p2p-ws-sdk-c/build --config Release`
- `python tools/gen_p2p_command_ordinals.py`
- `python -m compileall -q p2p-ws-sdk-python/src/p2p_ws_sdk/core_compat/ws_client.py`
