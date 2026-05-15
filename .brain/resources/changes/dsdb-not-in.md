---
id: dsdb-not-in
title: dsdb 动态查询：NOT_IN 索引排除集加速
updated: "2026-05-08T18:16:00Z"
---

## Summary
- `DB_ROW_QUERY_IDS` 在 where 包含 `NOT_IN` 且列存在 EQ 索引时，会先用索引把需要排除的 rowId 收集为排除集合；扫描/候选集遍历时先按排除集合跳过，再进行 where 的完整过滤。

## Verification for dsdb 动态查询：NOT_IN 索引排除集加速
- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test`
