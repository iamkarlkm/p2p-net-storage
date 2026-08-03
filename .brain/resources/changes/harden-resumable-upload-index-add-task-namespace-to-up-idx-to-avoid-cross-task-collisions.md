---
title: "Harden resumable upload index: add task namespace to up.idx to avoid cross-task collisions"
updated: "2026-08-03T00:00:00+08:00"
---
# Harden resumable upload index: add task namespace to up.idx to avoid cross-task collisions

## Verification

```bash
mvn -pl p2p-core "-Dmaven.repo.local=C:\Users\karl\.m2\repository" "-Dtest=FileUtilUpInfoTmpTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

```bash
mvn -pl p2p-sync "-Dmaven.repo.local=C:\Users\karl\.m2\repository" "-Dtest=P2PDirectorySyncE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```
