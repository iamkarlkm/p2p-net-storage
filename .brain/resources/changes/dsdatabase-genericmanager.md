---
updated: "2026-05-04T11:19:47Z"
---
## Verification for ?????DsDatabase???GenericManager??

- `mvn -pl p2p-core -DskipTests install`
- `mvn -pl p2p-db -am test -Dtest=GenericManagerTest -Dsurefire.failIfNoSpecifiedTests=false`
