---
title: Verification recipe for p2p-db MVCC
updated: "2026-08-24T12:00:00Z"
---
# Verification recipe for p2p-db MVCC

Column-level MVCC opt-in via `@DsField(mvcc=true)`. `@DsTable(mvcc=true)` remains the "all columns MVCC" shortcut.

Core MVCC regression:
- `mvn test -pl p2p-db -Dtest=ds.DsDatabaseLocalMvccTest -Dsurefire.useFile=false`

Joint targeted regression (index subsystem + ORM + batch + binlog + MVCC):
- `mvn test -pl p2p-db -Dtest=DsEqIndexStoreTest,DsEqIndexRangeTest,DsEqIndexOrmAutoMaintainTest,DsEqIndexQueryPlannerTest,DsEqIndexPlannerMultiIndexIntersectTest,DsEqIndexCompositeIndexTest,DsEqIndexPlannerOperatorsTest,DsDatabaseLocalBatchPutTest,DsBinlogBasicTest,GenericManagerTest,DsDatabaseLoclalOrmTest,DsDatabaseLocalMvccTest -DfailIfNoTests=false`

Notes:
- `p2p-db/pom.xml` currently uses the JUnit4 surefire provider, so `GenericManagerTest` and `DsDatabaseLoclalOrmTest` (JUnit5) are not executed by the Maven commands above; verify them via IDE/JUnit5 runner or after switching surefire to a JUnit5 provider.
- `DsEqIndexStore.findByIndex(long)` returns an empty `long[0]` when the key is absent, not `null`.
- MVCC column indexes point to `versionId`; non-MVCC column indexes point to `rowId`.
