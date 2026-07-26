---
updated: "2026-05-11T00:00:00Z"
---
# p2p-ws-sdk-ts core_compat（p2p-core wire-format）

## What Changed

- 新增 `p2p-ws-sdk-ts/src/core_compat`：在 WebSocket 上直接发送 p2p-core 的 `length+magic+protostuff(P2PWrapper)` 帧，并在握手完成后对 payload 做 XOR。
- 支持 HAND/LOGIN 后直接调用 `RPC_DISCOVER/RPC_HEALTH/RPC_UNARY`，并提供 Echo 的端到端 demo（discover + health + echo）。

## Verification

- `npm -C p2p-ws-sdk-ts install`
- `node p2p-ws-sdk-ts/node_modules/tsx/dist/cli.mjs -e "import('./p2p-ws-sdk-ts/src/core_compat/CoreWsClient.ts').then(()=>console.log('core_compat ok'))"`
- `node -p "require.resolve('typescript', { paths: ['p2p-ws-sdk-ts'] })"`

