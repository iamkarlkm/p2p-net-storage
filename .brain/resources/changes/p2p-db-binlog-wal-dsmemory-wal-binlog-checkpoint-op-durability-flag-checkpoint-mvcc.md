---
created: "2026-08-27T00:00:00+08:00"
title: Verification for p2p-db binlog-as-WAL refactor
updated: "2026-08-27T16:45:52Z"
---
# p2p-db binlog 改为唯一 WAL 验证配方

本文件记录将 p2p-db 物理 WAL 移除、以 binlog 作为唯一恢复日志这一批改动的回归验证命令。

## 变更摘要

- 移除 `DsMemory` 物理 WAL（`_wal.log` / `DsWAL` 不再使用），`DsMemory` 实现 `AutoCloseable` 释放文件句柄。
- 扩展 binlog 格式：新增 `CHECKPOINT` op、`durability flag`，新增独立 checkpoint 文件 `DsBinlogCheckpoint`。
- 新增 binlog 水位 `DsBinlogPosition { dayKey, fixedOffset, sequence }`，用于 snapshot / 增量备份 / 时间线恢复。
- `DsDatabaseLocal` 写路径（非 MVCC/MVCC 单条 put、批量非 MVCC put、非 MVCC/MVCC remove）均产生 binlog INSERT/UPDATE/DELETE 事件。
- 新增 binlog 回放入口 `replayBinlog(start, end)`，支持批量聚合与按 `rowKey` 分区的并行回放，回放期间 `DsBinlogContext.isInReplay()` 短路避免二次写 binlog。
- 改造全量/增量/时间线备份恢复：备份目录按 role 划分（`bucket_data/` / `index_eq/` / `meta/` / `binlog_fixed/` / `binlog_dyn/` / `binlog_bytes/`），增量仅捕获 binlog 水位之后的新增数据。
- 修复非 MVCC 删除语义与 MVCC 列级版本链索引路由（MVCC 列索引指向 `versionId`，非 MVCC 列索引指向 `rowId`）。
- 修复 DsMemory 并发缓存 flaky 测试（`DsMemoryBufferLockStripedTest`、`DsMemoryLruSamplingTest`）阈值，全量回归稳定。

## 定向回归验证

```bash
# binlog 回放 + 全量/增量/时间线备份
mvn test -pl p2p-db "-Dtest=com.q3lives.ds.backup.DsBinlogReplayTest,com.q3lives.ds.backup.DsFullBackupTest,com.q3lives.ds.backup.DsIncrementalBackupTest" "-Dsurefire.useFile=false"

# MVCC 与索引核心
mvn test -pl p2p-db -Dtest=DsEqIndexStoreTest,DsEqIndexRangeTest,DsEqIndexOrmAutoMaintainTest,DsEqIndexQueryPlannerTest,DsEqIndexPlannerMultiIndexIntersectTest,DsEqIndexCompositeIndexTest,DsEqIndexPlannerOperatorsTest,DsDatabaseLocalBatchPutTest,DsBinlogBasicTest,GenericManagerTest,DsDatabaseLoclalOrmTest,DsDatabaseLocalMvccTest -DfailIfNoTests=false

# DsMemory 并发缓存 flaky 复跑
mvn test -pl p2p-db "-Dtest=DsMemoryBufferLockStripedTest,DsMemoryLruSamplingTest"
```

## 全量回归验证

```bash
mvn test -pl p2p-db -am
```

## 注意事项

- binlog 水位是排他水位：`fixedOffset` 为下一帧待写入偏移，`sequence` 为最后写入帧序号；回放区间为 `[start, end)`。
- 物理 WAL 已移除，崩溃恢复与时间线恢复完全依赖 `DsBinlogStore`。
- 并行回放按 `rowKey` 分区保序，但所有实际写操作（`putEntities` / `removeTable`）通过 `replayApplyLock` 全局串行化，因为 `DsEqIndexStore` 非线程安全。
- 非 MVCC 表 `removeTable` 后 `DsFixedBucketStore` 仅标记 free，未清零行数据；涉及 DELETE 断言应使用 MVCC 实体或索引查询验证。

