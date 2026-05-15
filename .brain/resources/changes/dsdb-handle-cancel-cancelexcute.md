---
updated: "2026-05-09T13:23:21Z"
---
# dsdb-handle-cancel-cancelexcute

## 变更

- `DsDatabaseServer.DbRowQueryStreamHandle.cancel()` 改为幂等，并统一调用 `client.cancelExcute(seq)` 发送 `STD_CANCEL(seq)`，不再走同步 `excute(...)` 等待服务端响应。
- 新增单测 `DbRowQueryStreamHandleCancelTest`，验证 cancel 只触发一次 cancelExcute，且不会调用 excute。

## 验证

- `mvn -pl p2p-db -Dtest=DbRowQueryStreamHandleCancelTest test`
- `mvn -pl p2p-db test`
