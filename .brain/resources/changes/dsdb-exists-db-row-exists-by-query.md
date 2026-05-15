---
id: dsdb-exists-db-row-exists-by-query
title: dsdb 动态查询：EXISTS（DB_ROW_EXISTS_BY_QUERY）
updated: "2026-05-09T01:09:00Z"
---

## Summary
- 新增 `DB_ROW_EXISTS_BY_QUERY`：复用 `DB_ROW_QUERY_IDS` 的过滤语义与索引候选集/排除集能力，但遇到首条命中行即返回 `exists=true`（用于 exists 场景快速短路）。

## Verification for dsdb 动态查询：EXISTS（DB_ROW_EXISTS_BY_QUERY）
- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test`
