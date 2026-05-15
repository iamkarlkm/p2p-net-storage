---
updated: "2026-05-09T15:10:43Z"
---
# p2p-ws-sdk-ts：network_mode 与 ServerGroupClient

## Summary
- 配置扩展：`network_mode`（`p2p`/`server_group`）、`ws_urls`（固定服务器组）。
- 新增 `ServerGroupClient`：固定服务器组模式下仅做握手协商（crypto_mode）与 wrapper 请求/响应收发，不依赖 center 控制平面。
- 文档更新：`p2p-ws-protocol/docs/config.md` 补充 network_mode/encryption_mode/ws_urls 说明。

## Verification
- `npm -C p2p-ws-sdk-ts run verify-wrapper`
- `npm -C p2p-ws-sdk-ts run verify-handshake`
- `npm -C p2p-ws-sdk-ts run verify-vectors`
