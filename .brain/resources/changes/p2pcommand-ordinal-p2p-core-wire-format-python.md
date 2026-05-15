---
updated: "2026-05-11T00:00:00Z"
---
# p2p-core wire-format Python 对齐

## What Changed

- `p2p-ws-sdk-python` 增加 `core_compat`：在 WebSocket 上直接发送 p2p-core 的 `length+magic+protostuff(P2PWrapper)` 帧，并在握手后对 payload 做 XOR。
- `core_compat` 增加 RPC unary 封装：基于 `p2p_rpc.proto` 的 `RpcFrame`，支持 `RPC_DISCOVER`、`RPC_HEALTH`、`RPC_UNARY`，并提供内置 Echo 的便捷调用。
- 生成并纳入 `p2p_rpc.proto`、`p2p_rpc_echo.proto` 的 Python pb2 代码（用于构造/解析 `RpcFrame` 与 Echo request/response）。
- 修正 HAND 的 PKCS1 v1.5 private-encrypt padding：使用 type-1 的 `0xFF` padding，便于与其它语言实现保持一致。
- `tools/gen_p2p_command_ordinals.py` 生成物扩展：额外输出 C 侧的 `p2pws_p2p_command_ordinals.h`，用于 core_compat 直接引用 ordinal 常量。

## Verification

- `mvn -q -pl p2p-core test`
- `python -c "import glob,py_compile; files=glob.glob('p2p-ws-sdk-python/src/p2p_ws_sdk/core_compat/*.py')+['p2p-ws-sdk-python/demo/core_ws_ping.py']; [py_compile.compile(f,doraise=True) for f in files]; print('compiled',len(files))"`
- `python -c "import sys; sys.path.insert(0,'p2p-ws-sdk-python/src'); from p2p_ws_sdk.core_compat import CoreWsClient, CoreWsClientConfig; from p2p_ws_sdk.gen import p2p_rpc_pb2; print('ok', p2p_rpc_pb2.RpcFrameType.Name(p2p_rpc_pb2.OPEN))"`
- `python -m compileall -q p2p-ws-sdk-python/src p2p-ws-sdk-python/demo`
- `python -m grpc_tools.protoc -I p2p-core/src/main/proto --python_out p2p-ws-sdk-python/src/p2p_ws_sdk/gen p2p_rpc.proto p2p_rpc_echo.proto`
