---
updated: "2026-05-09T15:10:00Z"
---
# p2p-ws-sdk：network_mode 与 ServerGroupClient

## Summary
- TS 配置扩展：`network_mode`（`p2p`/`server_group`），并补充 `ws_urls` 以支持固定服务器组。
- TS 新增 `ServerGroupClient`：固定服务器组模式下仅做握手协商（crypto_mode）与 wrapper 请求/响应收发，不依赖 center 控制平面。

## Verification
- `npm -C p2p-ws-sdk-ts run verify-wrapper`
- `npm -C p2p-ws-sdk-ts run verify-handshake`
- `npm -C p2p-ws-sdk-ts run verify-vectors`
