---
title: Verification for p2p-db index subsystem V0.6/V0.7/V0.8
updated: "2026-08-18T16:14:05Z"
---
# Verification for p2p-db index subsystem V0.6/V0.7/V0.8

V0.6 composite index leftmost prefix + V0.7 multi-index intersection + V0.8 batch putEntity index I/O aggregation.

- `mvn test -pl p2p-db -Dtest=DsEqIndexStoreTest,DsEqIndexRangeTest,DsEqIndexOrmAutoMaintainTest,DsEqIndexQueryPlannerTest,DsEqIndexPlannerMultiIndexIntersectTest,DsEqIndexCompositeIndexTest,DsDatabaseLocalBatchPutTest,DsBinlogBasicTest,GenericManagerTest -q`
