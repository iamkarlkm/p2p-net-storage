---
id: dsdb-rowid-db-row-query-ids-stream
title: dsdb 动态查询：流式返回 rowId（DB_ROW_QUERY_IDS_STREAM）
updated: "2026-05-09T01:38:00Z"
---

## Summary
- 新增 `DB_ROW_QUERY_IDS_STREAM`：服务端以响应侧流的方式返回匹配的 rowId（分 chunk 发送），用于大结果集场景避免一次性聚合全部 idsBytes。
- `orderBy` 支持：当 `orderBy` 非空时启用 topK 模式（要求 `limit>0` 且 `offset+limit<=20000`），扫描后保留 topK、排序后再分 chunk 输出。

## Verification for dsdb 动态查询：流式返回 rowId（DB_ROW_QUERY_IDS_STREAM）
- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-db "-Dtest=DbEntityP2PHandlersTest,DbRowQueryIdsStreamServerHandlerTest" test`
