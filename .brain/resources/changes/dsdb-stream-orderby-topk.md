---
id: dsdb-stream-orderby-topk
title: dsdb 动态查询：stream 支持 orderBy（topK 限制）
updated: "2026-05-09T04:25:00Z"
---

## Summary
- `DB_ROW_QUERY_IDS_STREAM` 在 `query.orderBy` 非空时启用 topK 模式：要求 `limit>0` 且 `offset+limit<=20000`，服务端扫描匹配集后保留 topK，再按 `orderBy` 排序并分 chunk 输出。

## Verification for dsdb 动态查询：stream 支持 orderBy（topK 限制）
- `mvn -pl p2p-db "-Dtest=DbRowQueryIdsStreamServerHandlerTest" test`
