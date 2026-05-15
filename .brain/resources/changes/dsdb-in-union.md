---
id: dsdb-in-union
title: dsdb 动态查询：IN 操作符与索引 union 候选集
updated: "2026-05-08T06:50:00Z"
---

## Summary
- `DB_ROW_QUERY_IDS` 已支持 `IN/NOT_IN/LIKE` 语义（由 `DbQueryCriterion.list/a/b` 承载）；当 where 中出现 `IN` 且该列存在 EQ 索引时，会对每个 IN 值走索引并 union 候选集，再与其他 EQ/IN 索引候选集求交后进入过滤/排序/分页。

## Verification
- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test`
- `mvn -pl p2p-db -Dtest=DsHashMapConcurrentTest test`
- `mvn -pl p2p-db test`
