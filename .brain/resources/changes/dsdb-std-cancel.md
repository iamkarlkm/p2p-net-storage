---
id: dsdb-std-cancel
title: dsdb 动态查询：流式查询取消（STD_CANCEL）
updated: "2026-05-09T12:32:30Z"
---

## Summary
- `DB_ROW_QUERY_IDS_STREAM` 支持用 `STD_CANCEL(seq)` 终止服务端对应流任务；服务端可能仍会输出少量“在途/已入队”的 chunk，但应很快停止持续产出。
- 客户端 `DsDatabaseServer.openQueryRowIdsStreaming(...)` 返回可取消句柄（内部复用同一 seq 发送 `STD_CANCEL`）。
- 修复客户端发送层：`excute(...)` 不再覆盖非 0 的 `seq`，确保 `STD_CANCEL(seq)` 这类需要固定 seq 的请求在真实客户端链路中生效。

## Verification for dsdb 动态查询：流式查询取消（STD_CANCEL）
- `mvn -pl p2p-core -Dtest=MessageServiceSeqPreserveTest test`
- `mvn -pl p2p-db "-Dtest=DbRowQueryIdsStreamServerHandlerTest" test`
