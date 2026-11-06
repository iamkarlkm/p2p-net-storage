---
title: p2p-db binlog 回放优化：批量聚合 / 并行回放 / 跨日 rotate
updated: "2026-08-25T23:51:46Z"
---
# p2p-db binlog 回放优化：批量聚合 / 并行回放 / 跨日 rotate

## 变更摘要

- `DsBinlogReplayer.java` 引入 chunked read（64KB = 256 帧 × 256B），提升顺序帧读取吞吐。
- `DsDatabaseLocal.java` 新增：
  - `replayBinlog(start, end, batchSize)` —— `BatchReplayHandler` 按表缓冲 INSERT/UPDATE，遇 DELETE 或 batchSize 阈值 flush，通过 `putEntities(list, false)` 批量写索引，减少 read-modify-write。
  - `replayBinlog(start, end, batchSize, parallelPartitions)` —— `ParallelBatchReplayHandler` 单线程读帧，按 `rowKey` hash 分发到多个 `BlockingQueue`，每分区独立工作线程保序消费；因 `DsEqIndexStore` 非线程安全，实际写操作通过 `replayApplyLock` 全局串行化；工作线程显式设置/清除 `DsBinlogContext`，防止回放递归写 binlog。
  - `rotateBinlog()` / `rotateBinlog(String dayKey)` —— 暴露跨日 rotate 入口。
- `DsBinlogReplayTest.java` 新增 4 个测试覆盖批量聚合、并行回放、跨日 rotate、缺失日起始日场景。

## 验证命令

```bash
# 定向回归：binlog 回放 + 增量/全量备份
mvn test -pl p2p-db "-Dtest=com.q3lives.ds.backup.DsBinlogReplayTest,com.q3lives.ds.backup.DsFullBackupTest,com.q3lives.ds.backup.DsIncrementalBackupTest" "-Dsurefire.useFile=false"

# 全量模块回归
mvn test -pl p2p-db
```

## 注意事项

- 非 MVCC 表 `removeTable` 后，`DsFixedBucketStore` 仅将 baseId 加入 free-ring，未清零行数据，`getTable(clazz, id)` 仍可能读到旧行；涉及 DELETE 断言的回放测试应使用 MVCC 实体或索引查询验证删除语义。
- 并行回放分区数建议不超过 `availableProcessors() * 4`，当前实现优先正确性，写操作全局串行化。
