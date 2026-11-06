---
created: "2026-08-30T14:50:00+08:00"
title: Verification for p2p-db binlog redo/undo log level control
updated: "2026-08-30T14:50:00+08:00"
---
# p2p-db binlog redo/undo 日志级别控制验证配方

本文件记录 p2p-db binlog 表级 redo/undo 日志级别控制功能的回归验证命令。

## 变更摘要

- 新增 `DsBinlogLogLevel` 枚举：`REDO`（默认，after-image）、`UNDO`（before-image）、`FULL`（before + after）。
- `DsBinlogLayout` 增加 `FLAG_BIT_UNDO_LOG` 标志；`DsBinlogEntry.isUndo()` 供回放侧快速识别 UNDO 帧。
- `DsBinlogStore.append(...)` 扩展日志级别参数，旧方法默认 `REDO` 保持兼容。
- `@DsTable` 新增 `binlogLogLevel()` 配置，默认 `REDO`。
- `DsDatabaseLocal` 写路径按表级级别生成 binlog：UPDATE 在 `FULL` 级别下先写 UNDO 再写 REDO；`UNDO` 级别写 before-image；`REDO` 级别写 after-image。
- 回放路径（`applyBinlogEntry`、`BatchReplayHandler.apply`、`ParallelBatchReplayHandler.apply`）显式跳过带 `FLAG_BIT_UNDO_LOG` 的帧，保证恢复后的数据库状态只向前推进。
- 修复非 MVCC `putEntity` 路径对全新 slot 全零字节的误判：读取旧行时增加 `isAllZero(bytes)` 校验，避免首次 INSERT 被错误记录为 UPDATE 并多写 UNDO 帧。

## 新增/修改文件

- `p2p-db/src/main/java/com/q3lives/ds/binlog/DsBinlogLogLevel.java`
- `p2p-db/src/main/java/com/q3lives/ds/binlog/DsBinlogLayout.java`
- `p2p-db/src/main/java/com/q3lives/ds/binlog/DsBinlogEntry.java`
- `p2p-db/src/main/java/com/q3lives/ds/binlog/DsBinlogStore.java`
- `p2p-db/src/main/java/com/q3lives/ds/annotation/DsTable.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java`
- `p2p-db/src/test/java/com/q3lives/ds/binlog/DsBinlogLogLevelTest.java`
- `p2p-db/src/test/java/com/q3lives/ds/binlog/DsDatabaseLocalBinlogLogLevelTest.java`

## 定向回归验证

```bash
# 新增专项测试（7 条）
mvn test -pl p2p-db "-Dtest=com.q3lives.ds.binlog.DsBinlogLogLevelTest,com.q3lives.ds.binlog.DsDatabaseLocalBinlogLogLevelTest" "-DfailIfNoTests=false"

# binlog 与备份恢复核心
mvn test -pl p2p-db "-Dtest=com.q3lives.ds.backup.DsBinlogReplayTest,com.q3lives.ds.backup.DsFullBackupTest,com.q3lives.ds.backup.DsIncrementalBackupTest" "-Dsurefire.useFile=false"
```

## 全量回归验证

```bash
mvn test -pl p2p-db
```

## 注意事项

- `DsBinlogStore.replayAll()` / `replayBinlog()` 本身不过滤 UNDO 帧；跳过语义只在 `DsDatabaseLocal` 回放 handler 中实现。验证跳过行为时应通过数据库恢复后的最终实体状态断言，而不是直接调用 store 层 replay。
- 非 MVCC `putEntity` 的 old-row 读取必须加 `isAllZero(bytes)` 保护，否则新 slot 全零字节会被误解析为旧行，造成 `FULL` / `UNDO` 级别下首次写入生成错误 UPDATE binlog。
- `DsDatabaseLocal.commitGroup()` 每次都会追加 CHECKPOINT 帧，测试验证 binlog 内容时应按 tableId 过滤或断言具体帧的 opType/level。
