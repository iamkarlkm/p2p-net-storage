---
title: Verification for p2p-db index subsystem V0.9
updated: "2026-08-20T09:30:00Z"
---
# Verification for p2p-db index subsystem V0.9

Query Planner OR/NOT_IN/IN/COUNT/EXISTS operators.

- `mvn test -pl p2p-db -Dtest=DsEqIndexStoreTest,DsEqIndexRangeTest,DsEqIndexOrmAutoMaintainTest,DsEqIndexQueryPlannerTest,DsEqIndexPlannerMultiIndexIntersectTest,DsEqIndexCompositeIndexTest,DsDatabaseLocalBatchPutTest,DsBinlogBasicTest,GenericManagerTest,DsEqIndexPlannerOperatorsTest -q`
