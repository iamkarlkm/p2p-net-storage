---
updated: "2026-05-06T22:23:21Z"
---
# dsdb-dynamic-query

## Verification for ???????????where/order/limit??
- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-db -am -Dtest=com.q3lives.ds.database.DbEntityP2PHandlersTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -pl p2p-db test`

