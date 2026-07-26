---
id: dsdb-count-db-row-count
title: dsdb 动态查询：COUNT（DB_ROW_COUNT）
updated: "2026-05-08T18:29:00Z"
---

## Summary
- 新增 `DB_ROW_COUNT`：在服务端复用 `DB_ROW_QUERY_IDS` 的过滤逻辑（含 EQ/IN 索引候选集、NOT_IN 索引排除集、OR 分组去重），但只返回匹配行数而不是 rowId 列表。

## Verification for dsdb 动态查询：COUNT（DB_ROW_COUNT）
- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test`
