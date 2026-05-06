## Verification for 同步DsDatabase查询/CRUD到server模式

- `mvn -pl p2p-core -DskipTests install`
- `mvn -pl p2p-db "-Dtest=com.q3lives.ds.database.DbEntityP2PHandlersTest" test`
- `mvn -pl p2p-db test`

