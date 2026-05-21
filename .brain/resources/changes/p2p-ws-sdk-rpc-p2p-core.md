---
title: p2p-ws-sdk RPC verification
updated: "2026-05-17T21:20:29Z"
---
## Verification for 提升各语言 p2p-ws-sdk RPC 覆盖度到与 p2p-core 全对齐，并满足生产级要求

- TypeScript（p2p-ws-sdk-ts）
  - `npm --prefix p2p-ws-sdk-ts run verify-wrapper`
  - `npm --prefix p2p-ws-sdk-ts exec -- tsx p2p-ws-sdk-ts/scripts/verify_core_compat_stream_wrapper.ts`
  - `npm --prefix p2p-ws-sdk-ts exec -- tsx p2p-ws-sdk-ts/scripts/verify_core_compat_rpc_frame_chunking.ts`
  - `npm --prefix p2p-ws-sdk-ts exec -- tsx -e "import './p2p-ws-sdk-ts/src/core_compat/CoreWsClient.ts'; import './p2p-ws-sdk-ts/src/core_compat/rpc.ts'; console.log('ok=1')"`

- Dart（p2p-ws-sdk-dart）
  - `powershell -NoProfile -Command "cd p2p-ws-sdk-dart; dart pub get"`
  - `powershell -NoProfile -Command "cd p2p-ws-sdk-dart; dart analyze"`

- Python（p2p-ws-sdk-python）
  - `python -c "import sys, runpy; sys.path.insert(0,'p2p-ws-sdk-python/src'); runpy.run_path('p2p-ws-sdk-python/scripts/verify_core_compat_stream_wrapper.py', run_name='__main__')"`
  - `python -c "import sys; sys.path.insert(0,'p2p-ws-sdk-python/src'); import p2p_ws_sdk.core_compat.ws_client as w; import p2p_ws_sdk.core_compat.rpc as r; print('ok=1')"`

- C（p2p-ws-sdk-c）
  - Build prerequisites: install one of `cmake` + `gcc/clang` 或 Visual Studio `cl`
  - Suggested build: `cmake -S p2p-ws-sdk-c -B p2p-ws-sdk-c/build && cmake --build p2p-ws-sdk-c/build --config Release`
  - Suggested run:
    - `p2pws_core_ws_verify_stream_wrapper`
    - `p2pws_core_ws_rpc_stream_collect_chat <wsUrl> <magic> <privateKeyPemPath> <userId>`
