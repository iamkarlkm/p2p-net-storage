---
updated: "2026-05-06T23:06:00Z"
---
# dsdb
 
## Verification for dsdb 等值二级索引：查询接入与增量维护
- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test`
- `mvn -pl p2p-db test`

## Verification for dsdb 等值二级索引：drop index（删除索引）
- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test`
- `mvn -pl p2p-db test`

## Verification for dsdb 等值二级索引：list/info（列出与查询索引元信息）
- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test`
- `mvn -pl p2p-db test`

