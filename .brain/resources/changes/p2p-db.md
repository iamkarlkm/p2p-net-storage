---
title: Verification for p2p-db
updated: "2026-08-25T01:29:40Z"
---
## Verification for p2p-db backup & restore regression

- `mvn test -pl p2p-db "-Dtest=com.q3lives.ds.backup.DsIncrementalBackupTest,com.q3lives.ds.backup.DsFullBackupTest" "-Dsurefire.useFile=false"`
