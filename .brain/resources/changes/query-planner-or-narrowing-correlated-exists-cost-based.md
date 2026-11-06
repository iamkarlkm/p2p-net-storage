# Verification for Query Planner OR narrowing / correlated EXISTS / cost-based index selection

Joint verification command covering the p2p-db index subsystem (EqIndexStore, Range, ORM auto-maintain, Query Planner, MultiIndex intersect, Composite index, BatchPut, Binlog, GenericManager, and the operator suite):

```bash
mvn test -pl p2p-db -Dtest=DsEqIndexStoreTest,DsEqIndexRangeTest,DsEqIndexOrmAutoMaintainTest,DsEqIndexQueryPlannerTest,DsEqIndexPlannerMultiIndexIntersectTest,DsEqIndexCompositeIndexTest,DsDatabaseLocalBatchPutTest,DsBinlogBasicTest,GenericManagerTest,DsEqIndexPlannerOperatorsTest -q
```

Broader p2p-db regression:

```bash
mvn -pl p2p-db '-Dtest=com.q3lives.ds.database.*Test,ds.*Test' '-DfailIfNoTests=false' test
```

Result: 160 tests, 0 failures, 0 errors, 0 skipped.
