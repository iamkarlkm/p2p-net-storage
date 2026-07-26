---
id: dsdb-or-dnf-and-or
title: dsdb 动态查询：OR 分组（DNF：组内AND，组间OR）
updated: "2026-05-08T10:42:00Z"
---

## Summary
- `DB_ROW_QUERY_IDS` 新增 OR 分组能力：`DbQuery.anyOf` 表示多组 where（组内 AND，组间 OR）；并把 `DbQuery.where` 作为全局 AND 附加到每个分组（等价于 `where AND (g1 OR g2 ...)`）。

## Verification for dsdb 动态查询：OR 分组（DNF：组内AND，组间OR）
- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test`
- `mvn -pl p2p-db test`
