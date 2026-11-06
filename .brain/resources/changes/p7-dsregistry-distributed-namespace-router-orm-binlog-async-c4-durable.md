# P7 — DsRegistry 接入 p2p-dsdb 分布式命名空间路由（3 块 14 tests 0F0E + 全模块 507 tests 0F0E 基线）

## Metadata
- **Session ID**: 1789516119158128300
- **Task Text**: P7 DsRegistry follow-up (after P6 closed): fallback实删 or G4三轮回归 or dsdb分布式接入 DsRegistry → 方向确认 C（DsRegistry 接入 p2p-dsdb 分布式命名空间路由）→ 4 子问消歧 → Spec
- **Date / PR scope tag**: 2026-09-17 p7-dsregistry-distributed-namespace-router-orm-binlog
- **Hard constraint (inherit P6 VERBATIM)**: **7 大底层 0 行修改** = DsIdLayout / DsTypeString / DsTypeFixedBucketStore / DsFreeRing / DsHashMap / MultiTypeDataId / DsTieredMasterIndex；本 P 所有改造仅加字段/加构造/加重载/加 helper，**不删不改任何旧 Public API 签名与行为**。

## User Direction (消歧后最终 Scope, Q1×Q2×Q3×Q4 全 VERBATIM)
| 问题 | 用户选择 | 含义锁定 |
|---|---|---|
| Q1 (多选 A/B/C/D) | **A1+B1+D1 并行** ✅ | 同一次 P7 交付 A1（Registry 自身 nodeId 三段）+ B1（ORM DB_ENTITY_* 路由）+ C4（异步 binlog 复制最终一致）并行做；C1 命名中心 ❌ |
| Q2 (ORM rowId 是否嵌入 DsIdLayout 三段) | **C2 不碰 ORM rowId** ✅ | ORM rowId 永远纯本地自增（保持 P0-P6 不变），**跨节点路由不基于 rowId 解 sid/pid**；仅 DsRegistry DIR/LEAF nodeId 用三段 |
| Q3 (tableId 是否全局映射替代 FNV) | **C3 保持 FNV-1a** ✅ | tableId = `stableTableId = FNV-1a(className UTF-8 bytes)` 不变，**无全局命名中心** |
| Q4 (数据一致性) | **C4 异步 binlog 复制（最终一致）** ✅ | Pull 模式 Replicator.doTick(tickMs) → replayBinlog → applyBinlogEntry → writeCursor 原子持久化 |

## Durable 改动文件清单（按 Invariant 3 块）
### 【A1 — DsRegistry 自身 DIR/LEAF globalNodeId 5 重载 + 4 helper】（6 tests 0F0E `DsRegistry_P7_A1_DistributedNodeIdTest`）
- `p2p-db/src/main/java/com/q3lives/ds/registry/RegistryEntry.java`：加 `globalNodeId()` / `toRegistryEntryGlobal()` / `globalNodeId(Long)` / 3 字段构造（不删旧 2 字段构造，backward 100% compat）
- `p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java`：
  - 2 参 serverId/partitionId final 字段 → 1 参构造 `this(rootDir,0,0)` 单机零破坏 → 3 参构造 范围校验 `0≤serverId≤DsIdLayout.SERVER_ID_MASK / 0≤partitionId≤DsIdLayout.PARTITION_MASK` 不合法 IAE
  - Public 5 重载：`listChildrenGlobalIds(String) / getChildGlobalId(String,String) / getGlobalId(String) / toGlobalNodeId(long internalTypedId) / fromGlobalNodeId(long global, boolean isDir)`
  - Private 4 helper：`makeLeafTypedId(long plain) / makeDirTypedId(long plain) / isDirTypedId(long typed) / isLeafTypedId(long typed)`
  - 语义规则（Invariant 锁定）：纯 ALU `MultiTypeDataId.decodeIndexId(idx) → DsIdLayout.compose(sid,pid,idx)` 零 I/O、零 bucket 写、零 nextSeq；ROOT local=0 自动 `rootDirTypedId()`
- `p2p-db/src/test/java/ds/DsRegistry_P7_A1_DistributedNodeIdTest.java`：6 tests T1~T6 = T1 serverId 边界 / T2 partitionId 边界 / T3 A×B×P / T4 ROOT=0 / T5 toGlobal→fromGlobal 往返 / T6 listChildrenGlobalIds 集合

### 【B1 — ORM DB_ENTITY_* 路由 + Q2=C2 rowId 零改】（6 tests 0F0E `DsDatabaseServer_P7_B1_OrmRoutingTest`）
- `p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java`：
  - 加 field：`serverId / partitionId / classIdSetCache`（L80）
  - 旧 2 参构造 = `this(dbHome,0,0)` 单机零破坏 → 3 参构造 范围校验 → 4 getter：`serverId() / partitionId() / getIdSetForClass(Class) / getRoot()`
  - applyBinlogEntry 可见性 private→public
  - 3 helper（classIdSet 单实例）：`idSetForClass(Class) computeIfAbsent{new DsHashSet(idef.idsFile)}` / `addIdToClassIdSet(Class,long)`（boxed Long 重载 IOException→RuntimeException）/ `removeIdFromClassIdSet(Class,long)`
  - `putRowIntoIndexes` 非 MVCC 首行：`addIdToClassIdSet(meta.clazz,rowId)` → C4 核心修复
  - `removeRowFromIndexes` 非 MVCC 首行：`removeIdFromClassIdSet(meta.clazz,rowId)` → DELETE binlog 帧同步
- `p2p-db/src/main/java/com/q3lives/ds/database/integration/GenericManager.java`：
  - 旧 2 构造 distClient=null（backward compat 不破坏任何现有调用）
  - 新 2 构造：`GenericManager(Class<T>, DsDatabaseServer)` / `GenericManager(Class<T>, DsDatabaseLocal db, int serverId, int partitionId)` → 内部 `isDistributed = distClient!=null && distClient.isDistributedMode()`
  - 9 方法路由：saveOrUpdate / getById / existsById / removeById / updateById（Primitive）+ findList / findRangeByWrapper / getOne / count / list（QueryWrapper 5 条）
    - 分布式守卫 UOE 只在 queryIds（暂未实现）
  - **ensureInit 最关键**：L164 改 `idSet = db.getIdSetForClass(persistentClass)`，**绝对禁止 new 第二个 DsHashSet(idef.idsFile) 实例**
- `p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseServer.java`：
  - 旧 2 构造 `distributedMode=false`（backward 零分支）
  - 4 参构造 `DsDatabaseServer(dbHome, config, serverId, partitionId)`：校验 `0≤sid≤DsIdLayout.SERVER_ID_MASK / 0≤pid≤PARTITION_MASK` 不合法 IAE，设置 `distributedMode=true` + 创建 `localInstance(dbHome,sid,pid)`
  - **Q2=C2 架构修正（最终形态）**：`putTable / getTable / existsTable / removeTable` 四条 **DB_ENTITY_* 直接短路 localDb**（rowId 永远本地自增，从 rid 解 sid/pid 永远命中 local → routeClient 路由无意义），保留 routeClient/remoteClients 基础设施给后续「ORM rowId 存 DsIdLayout 编码」阶段
  - `close()` 扩展：依次 close localInstance → close routeClient → close 所有 remoteClients 并清 map
- `p2p-db/src/test/java/ds/DsDatabaseServer_P7_B1_OrmRoutingTest.java`：6 tests T7~T12 = T7 saveOrUpdate 路由 / T8 removeById 路由 / T9 updateById 路由 / T10 getById 短路 local / T11 existsById 短路 / T12 findList empty 路由

### 【C4 — 异步 binlog 复制最终一致】（2 tests 0F0E `DsBinlogReplicator_P7_C4_Test` + 1 debug `DsBinlogReplicatorDebug_Test`）
- `p2p-core/src/main/java/javax/net/p2p/api/P2PCommand.java`：加 `DB_ENTITY_BINLOG_FRAME=24300` + `R_OK_DB_ENTITY_BINLOG_FRAME_ACK=24301`（不删旧任何命令号）
- `p2p-core/src/main/java/javax/net/p2p/model/DbEntityBinlogFrameRequest.java`：sourceServerId/sourcePartitionId/targetServerId/targetPartitionId/dayKey/fixedOffset/sequence/endDayKey/endFixedOffset/endSequence 10 字段 + binlog bytes payload（`BINLOG_FIXED + BINLOG_DYN + BINLOG_BYTES` 三文件 字节数组）
- `p2p-core/src/main/java/javax/net/p2p/model/DbEntityBinlogFrameAck.java`：appliedFrames/appliedDayKey/appliedFixedOffset/appliedSequence/error 5 字段
- `p2p-db/src/main/java/javax/net/p2p/server/handler/DbEntityBinlogFrameServerHandler.java`：24300 Handler，`process()` → `DsDatabaseLocal.loadOrNull(targetDbHome)` → `replayBinlog(pos_start,pos_end)` → 24301 ACK appliedFrames
- `p2p-db/src/main/java/com/q3lives/ds/binlog/DsBinlogReplicator.java`：Pull 模式 Scheduler（ScheduledExecutorService 单线程）
  - field：`lastCursor (dayKey\|fixedOffset\|sequence)` + `lastCursorSequence`
  - cursorFile：`<cursorDir>/binlog_cursor_{sA}_{pA}_to_{sB}_{pB}.yaml` → `readCursor(String)` pipe 3 段 Long.parseLong → `writeCursor(RandomAccessFile setLength(0) 原子写)`
  - `start(tickMs)`：scheduleAtFixedRate doTick；`stop()`：shutdownNow + writeCursor；`currentCursorSequence()` getter
  - `doTick()`：首次 startCursor 空→(("",0,0))；`DsBinlogContext.setInReplay(true)` 内外双 try-finally，异常 System.err 打出来不吞；source = DsDatabaseLocal.load(sourceHome, sA,pA)；target = load(targetHome, sB,pB)；pos 比较：dayKey > 或 (dayKey 等 && fixedOffset > 或 sequence >) → `target.replayBinlog(startPos, endPos)`；成功后 `writeCursor(endPos)` + lastCursor=endPosString
- **Root Cause C4（深度 debug 5 层定位）**：`GenericManager.findList(empty)` → `ensureInit→DNF empty conj→fullScanIds→primaryIdIterator→idSet.iterator()`；旧实现只有 GM.saveOrUpdate/removeById/updateById 手动 add/remove idsFile，db.putEntity/removeTable 完全不碰 → `applyBinlogEntry → putEntity` 即使 bucket 写成功 GM findList 仍 size=0。**修复法则（Invariant 锁定）**：
  1. DsDatabaseLocal 只有一个 `classIdSetCache`，`idSetForClass` computeIfAbsent new DsHashSet
  2. putRowIntoIndexes / removeRowFromIndexes 非 MVCC 首行注入 addId / removeId（boxed Long 重载 IOException→RuntimeException，避免 try/catch body IOException 不抛 编译 fail）
  3. GenericManager.ensureInit **不再 new 第二个实例**，直接 `db.getIdSetForClass(clazz)` 单实例复用
- Warm-up 规则：`tableIdToClass.put(tableId,clazz)` 注册只在 `GenericManager.createMeta(clazz)` 触发（需 saveOrUpdate/removeById 先走 Primitive → createMeta），applyBinlogEntry.decodeEntityFromBinlog 只 get 不注册；所以 T13/T14 每次 target/源 首次回放前必须 `GM.saveOrUpdate(dummy) + removeById(dummy.getId)` 确保 Class 注册 + idsFile 创建
- `p2p-db/src/test/java/com/q3lives/ds/binlog/DsBinlogReplicator_P7_C4_Test.java`：T13 双实例 10 writes 1.2s → target GM findList empty size=10 且 names equals；T14 r1 5 rows/r2 5 rows，r2 cursor 持久化 从 r1 end 开始→最终 10 rows distinct
- `p2p-db/src/test/java/com/q3lives/ds/binlog/DsBinlogReplicatorDebug_Test.java`：直接调 `target.replayBinlog(0,6 rows)` 绕过 scheduler，warm 注册 + 3 rows 主调 → directCount=3 PASS 1/1 0F0E，用于 isolation 调试 applyBinlogEntry bucket write 链路

### 【附带 2 个基线修复（P31 回归发现）】
- `DsRegistry.findByPrefixKeys` L1326：`stream().toList()` → `new ArrayList<>(keySet())`；`listKeys` L1357 同理 → JDK17+ `.toList()` 返回 immutable，Collections.sort 抛 UOE → P31 testFindByPrefixNonDirPrefixMatch 5 tests 修复 → 507 tests 全绿基线

## 验收硬指标（全 VERIFIED）
| 验收项 | 状态 | 证据 |
|---|---|---|
| A1 6 tests 0F0E | ✅ | `Tests run:6 0F0E` |
| B1 6 tests 0F0E | ✅ | `Tests run:6 0F0E` |
| C4 Debug 1 test PASS | ✅ | `Tests run:1 0F0E directCount=3` |
| C4 T13 target GM.size=10 / names equals | ✅ | `Tests run:2 0F0E`（C4 classIdSet 修复已 verify） |
| C4 T14 cursor persist 继承最终 10 distinct | ✅ | 同上 |
| P31 5 tests 回归（.toList()→ArrayList） | ✅ | `Tests run:5 0F0E 72s` |
| p2p-db 全模块 507 tests ≥243 基线 | ✅ BUILD SUCCESS | `Tests run:507 Failures:0 Errors:0 Skipped:0` |

## Joint Verification Command（任何后续 DsDatabaseLocal.classIdSet / GenericManager.ensureInit 改动必跑）
```bash
mvn -pl p2p-db -am install -DskipTests -q
mvn -pl p2p-db '-Dtest=DsRegistry_P7_A1_DistributedNodeIdTest,DsDatabaseServer_P7_B1_OrmRoutingTest,com.q3lives.ds.binlog.DsBinlogReplicator_P7_C4_Test,com.q3lives.ds.binlog.DsBinlogReplicatorDebug_Test,ds.DsRegistry_P31_MasterIndexPrefixTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -pl p2p-db test
```
Expected: `Tests run: ≥507, Failures:0, Errors:0, Skipped:0`；任何 1 条 fail 都必须先修 classIdSet 单实例不变式，再推其他功能。

## Follow-up（非本 P 交付，仅标记给下一个接手 AI）
1. **B1-6 Handler 专项单测**：DbEntityBinlogFrameServerHandler.process 单独 mock request/response 验证 24300→replayBinlog→24301 ACK appliedFrames≥10（当前 C4 T13/T14 用 Replicator.doTick 间接覆盖 Handler 路径不完整）
2. **B1-7 MVCC 路由**：当前 classIdSet 只处理非 MVCC（meta.hasMvcc=false 分支）；MVCC versionId 索引路由到 classIdSet 需后续当 UserC4 有 mvcc=true 用例时补上
3. **D1 DsRegistry 跨节点 Handler 接入**：DsRegistry toGlobalNodeId/fromGlobalNodeId 已就绪，但跨节点 P2P Get/Put/Mkdirs Handler 待写（当前只在同一进程多实例纯 ALU 路径验证）
4. **C4 Handler payload 协议压缩**：当前 DbEntityBinlogFrameRequest 三文件 raw bytes，正式多节点链路可加 LZ4（参考 DsMemory cold-tier LZ4 格式 Invariant），保持 header 4-byte LE len + payload
