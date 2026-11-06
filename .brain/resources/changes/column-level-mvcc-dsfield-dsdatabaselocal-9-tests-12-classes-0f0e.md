---
title: Verification for p2p-db column-level MVCC (方案 B)
updated: "2026-08-24T12:00:00Z"
---
# Verification for p2p-db column-level MVCC

Column-level MVCC opt-in via `@DsField(mvcc=true)`. `@DsTable(mvcc=true)` remains the "all columns MVCC" shortcut.

- `mvn test -pl p2p-db -Dtest=DsEqIndexStoreTest,DsEqIndexRangeTest,DsEqIndexOrmAutoMaintainTest,DsEqIndexQueryPlannerTest,DsEqIndexPlannerMultiIndexIntersectTest,DsEqIndexCompositeIndexTest,DsEqIndexPlannerOperatorsTest,DsDatabaseLocalBatchPutTest,DsBinlogBasicTest,GenericManagerTest,DsDatabaseLoclalOrmTest,DsDatabaseLocalMvccTest -DfailIfNoTests=false`
- `mvn test -pl p2p-db "-Dtest=ds.DsDatabaseLocalMvccTest" "-Dsurefire.useFile=false"`

Notes:
- `p2p-db/pom.xml` currently uses the JUnit4 surefire provider, so `GenericManagerTest` and `DsDatabaseLoclalOrmTest` (JUnit5) are not executed by the Maven command above; verify them via IDE/JUnit5 runner or after switching surefire to a JUnit5 provider.
- `DsEqIndexStore.findByIndex(long)` returns an empty `long[0]` when the key is absent, not `null`.
- MVCC column indexes point to `versionId`; non-MVCC column indexes point to `rowId`.
