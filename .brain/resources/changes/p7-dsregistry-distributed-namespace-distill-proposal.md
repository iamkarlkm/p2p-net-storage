# P7 Distill Proposal（提交给 Project Owner 的 5 句精简 summary）
## Proposal Title
P7：DsRegistry 接入 p2p-dsdb 分布式命名空间路由（零改 7 大底层，14 tests 0F0E，507 全模块 0F0E 基线）

## Why Now
P6 DsIdLayout/Registry DIR 编码、P31 Prefix 性能基线、P0 ORM 233 tests 三大能力稳定后，已具备「Registry 自身三段 + ORM 路由 + binlog 异步」三层分布式能力交付条件，再不交付会导致「跨节点数据只能手工拷贝 DB_HOME」，为后续 D1（Registry 跨节点 Handler）和 Tgt-2（ORM rowId 三段真实跨节点路由）提供可叠加底座。

## What
1. **A1（Registry 自身 nodeId 三段）**：6 tests ✅。`listChildrenGlobalIds/getChildGlobalId/getGlobalId/toGlobalNodeId/fromGlobalNodeId` 5 重载 + `DsRegistry(root,sid,pid)` 3 参构造，纯 ALU 转换零 I/O 零 bucket 写，单机 1 参构造 100% 向后兼容。
2. **B1（ORM DB_ENTITY_* 路由 + Q2=C2 架构锁定）**：6 tests ✅。GenericManager 2 新构造 + 9 方法路由 + DsDatabaseServer 4 参构造 distributedMode；**架构决策锁定 ORM rowId 永纯本地自增**（不嵌 DsIdLayout 三段），跨节点通过显式持 (sid,pid) 的 DsDatabaseServer 实例，保留 routeClient/remoteClients 基础设施给后续扩展。
3. **C4（异步 binlog 复制最终一致）**：2+1 tests ✅。DsBinlogReplicator Pull 模式 scheduler + cursorFile 原子持久化（`setLength(0)` Windows 无锁冲突安全）+ `DbEntityBinlogFrameServerHandler` 24300/24301 P2P 跨节点通道；C4 根因五层定位修复：GM.findList(empty) fullScanIds → ids.set 文件之前仅 Primitive API 写，applyBinlogEntry→putEntity 路径漏写 classIdSet，修复为 db.classIdSetCache 单实例 + putRowIntoIndexes/removeRowFromIndexes 非 MVCC 首行注入 add/remove + GM.ensureInit 复用同一实例绝不 new 第二份 DsHashSet。

## Evidence（硬指标不可伪造）
- 专项：A1 6 + B1 6 + C4 2 + C4 Debug 1 + P31 修复 5 = **20 tests 0F0E**
- 全模块基线：**507 tests Failures:0 Errors:0 Skipped:0 BUILD SUCCESS**（≥243 P0-P6 基线要求 ×2.08×）
- 回归保护：AGENTS.md 新增 7 条 Invariant（classIdSet 单实例 / C4 warm-up / cursor 原子 / .stream().toList 禁 / Q2+C2+Q3=C3 锁定 等）；2 份 Recipe（durable 代码细节 + verification promote 下阶段目标）已写 `.brain/resources/changes/p7-*`。

## Next（给 Project Owner 的选项）
推荐顺序 Tgt-1（C4 Handler 专项 1 tests 约 2h，覆盖率补全）→ Tgt-2（ORM rowId 三段 + routeClient 跨节点 PUT/GET 3 tests，正式打破短路）→ Tgt-3（DsRegistry 跨节点 Handler D1 完整）→ Tgt-4（C4 LZ4 压缩低价值末位做）。
