---
updated: "2026-05-09T14:24:25Z"
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

## Verification for dsdb 动态查询：多 EQ 索引求交优化
- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test`
- `mvn -pl p2p-db -Dtest=DsHashMapConcurrentTest test`
- `mvn -pl p2p-db test`

## Verification for dsdb 动态查询：IN 操作符与索引 union 候选集
- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test`
- `mvn -pl p2p-db -Dtest=DsHashMapConcurrentTest test`
- `mvn -pl p2p-db test`

## Verification for dsdb 动态查询：OR 分组（DNF：组内AND，组间OR）
- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test`
- `mvn -pl p2p-db test`

## Verification for dsdb 动态查询：NOT_IN 索引排除集加速
- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test`

## Verification for dsdb 动态查询：COUNT（DB_ROW_COUNT）
- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test`

## Verification for dsdb 动态查询：EXISTS（DB_ROW_EXISTS_BY_QUERY）
- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test`

## Verification for dsdb 动态查询：流式返回 rowId（DB_ROW_QUERY_IDS_STREAM）
- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-db "-Dtest=DbEntityP2PHandlersTest,DbRowQueryIdsStreamServerHandlerTest" test`

## Verification for dsdb 动态查询：stream 支持 orderBy（topK 限制）
- `mvn -pl p2p-db "-Dtest=DbRowQueryIdsStreamServerHandlerTest" test`

## Verification for dsdb 动态查询：流式查询取消（STD_CANCEL）
- `mvn -pl p2p-core -Dtest=MessageServiceSeqPreserveTest test`
- `mvn -pl p2p-db "-Dtest=DbRowQueryIdsStreamServerHandlerTest" test`

## Verification for dsdb 启动自检：关系字段扫描并拒绝启动
- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-core "-Dtest=javax.net.p2p.common.MessageServiceSeqPreserveTest,javax.net.p2p.startup.P2PStartupChecksTest" test`
- `mvn -pl p2p-db "-Dtest=com.q3lives.ds.database.startup.DsDbRelationStartupCheckTest" test`

## Verification for dsdb 客户端元数据缓存预校验（启动拒绝变更）
- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-db "-Dtest=com.q3lives.ds.database.startup.DsDbClientMetaPrecheckTest" test`
