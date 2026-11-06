# P7 Verification & Promotion Target — DsRegistry 分布式命名空间 3 块 14 tests + 507 全模块

## Scope
本文件只定义 **"如何验证 P7 交付已正确"** 与 **"后续接手 AI 可推的下一个 promotion 目标"**，不重复 durable 的代码细节。

## 一、Verification（任何 P7 相关改动后必跑 3 条命令，顺序严格）
### 1A. 环境准备（每 session 首次）
```powershell
cd i:\2026\code\p2p-net-storage
mvn -pl p2p-core,p2p-db -am install -DskipTests -q
# exit 0 才算 OK（p2p-core 的 DbEntityBinlogFrameRequest/Ack 要先装到本地仓库，否则 DsDatabaseServer/DbEntityBinlogFrameServerHandler import 找不到符号）
```

### 1B. 专项 14 tests 快速烟测（≤90s，本地改完优先跑）
```powershell
mvn -pl p2p-db `
  '-Dtest=DsRegistry_P7_A1_DistributedNodeIdTest,DsDatabaseServer_P7_B1_OrmRoutingTest,com.q3lives.ds.binlog.DsBinlogReplicator_P7_C4_Test,com.q3lives.ds.binlog.DsBinlogReplicatorDebug_Test,ds.DsRegistry_P31_MasterIndexPrefixTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```
**Expected**：
- `Tests run: 6+6+2+1+5 = 20 tests，Failures:0 Errors:0`
- 若 **P31 有 1 Errors=UOE at Collections.sort** → 立即修：`DsRegistry.findByPrefixKeys / listKeys` 必须 `new ArrayList<>(keySet())`，绝不能 `stream().toList()`（JDK17+ immutable sort fail）。
- 若 **C4 T13/T14 GM findList empty size=0** → 立即修 3 条不变式（按 durable Invariant 顺序排查）：
  1. DsDatabaseLocal `classIdSetCache` 有没有 field？
  2. `putRowIntoIndexes` 非 MVCC 首行有没有 `addIdToClassIdSet(meta.clazz,rowId)`？（用 boxed Long 重载，不是 long 原语 throws IOException 那个 —— 否则 computeIfAbsent lambda 里 catch IOException=UOE 编译 fail L1145）
  3. GenericManager.ensureInit L164 有没有 `idSet = db.getIdSetForClass(persistentClass)`？绝不能 `new DsHashSet(index.idsFile)`（双 RAF 句柄同文件看不到写入）

### 1C. 全模块 507 tests 基线（正式 promote 前跑，≈4-6min）
```powershell
mvn -pl p2p-db test
```
**Expected hard**：`Tests run: 507, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`；
当前（2026-09-17）基线稳定 507 tests，**任何 <507 的数字** 都说明测试漏跑或 @Disabled，先查 surefire 报告。

## 二、Isolation Debug（如果 C4 专项 fail 先跑 2 条）
### 2A. Debug 单测（最快，绕过 scheduler 直接 replayBinlog，≈2s）
```powershell
mvn -pl p2p-db '-Dtest=com.q3lives.ds.binlog.DsBinlogReplicatorDebug_Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
# Expected: 1/1 0F0E，directCount=3，applyBinlogEntry→bucketWrite 100% OK
```
→ 如果 PASS 但 T13/T14 FAIL → **问题只在 GM classIdSet 单实例**（bucket 没问题）。
→ 如果 FAIL → applyBinlogEntry 链路问题（warm up Class 没注册 / binlog 帧版本号不兼容），查 per-frame println 的 `success[i] = name==null? "DECODE_FAIL":"OK"`。

### 2B. C4 专项 2 tests（带 verbose Wrapper，T13 会打 debugMsg）
```powershell
mvn -pl p2p-db -am '-Dtest=com.q3lives.ds.binlog.DsBinlogReplicator_P7_C4_Test' '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1 | Select-String -Pattern 'dbgPutOk|gmBSize|freshGM|forcedApplied|PERFRAME|phase1|cursor' -Context 0,0 | Select-Object -Last 40
```
**Expected T13 debugMsg 格式**：`dbgPutOk=10, forcedApplied=20, gmBSize=10, freshGM=10`
- `dbgPutOk<10` → bucket 没写进去（warm up 错 / decodeEntityFromBinlog fail）
- `dbgPutOk=10 but gmBSize=0` → **classIdSet 路径错**（用 durable 1B.3 条逐条核对）
- `forcedApplied=20`（10 warm + 10 main writes）正常，warm up 用 saveOrUpdate+remove 各占 1 帧所以 2 轮就是 20；不要把 warm 的 appliedFrames 当成 bug。

## 三、Promotion Target（下一个接手 AI 可交付的 4 个 candidate，按 ROI 排）
按 **"开发工作量小 / 价值高 / 风险低"** 排序：

### ✅ **[VERIFIED CLOSED — 2026-09-17 02:50] Tgt-1（ROI 最高）：C4 Handler 专项 1 tests + bytes→tempBinlog 落地实现**
- **修正（原 Scope 低估：纯加测试 0 代码改 → 实际需先修 Handler 3 bug 再写测试）**：
  1. Bug1 dayKey 合单：旧 Request 1 个 dayKey 无 startDayKey/endDayKey → 跨天错位；修法 p2p-core 升级 15 字段（4-tuple serverId/partitionId + startDayKey/endDayKey + offsets/sequences + 3 frames bytes）。
  2. Bug2 Ack 字段不足：旧 3 字段 → 新 9 字段（4-tuple + appliedFrames + endDayKey/endOffset/endSequence + error），error==null 语义化成功。
  3. Bug3 致命：payload 3 byte[] 没 target 落地就调 `targetDb.getBinlogStore().replayBinlog(start,end,...)` → appliedFrames=恒 0；修法方案 A（最小侵入，7 大底层 0 行）：Files.createTempDirectory → 写 3 dat → new DsBinlogStore(tmpRoot) try-with-resources → replayBinlog(startReq,endReq,target::applyBinlogEntry) → finally deleteTree(tmpRoot) 零残留。
- **配套 DsDatabaseLocal ThreadLocal 注入**：CURRENT_HOLDER(ThreadLocal) + setCurrent/db；Handler.process 顺序 `currentOrNull()` → fallback `loadOrNull()`，原 yaml 部署模式完全兼容。
- **Tgt-1 专项 1 test**：`ds.DbEntityBinlogFrame_P7_C4_HandlerTest.test_Tgt1_Handler_Process_10Rows_AppliedFrames_GE10`（1 @Test，~150 行）；7 步 = 2 tmpDir 建 dbA/dbB + gmA 写 10 条 UserC4 + gmB warm up(saveOrUpdate+remove) + read source 3 file bytes + build Request(15 字段) + try(setCurrent(dbB)) handler.process + finally setCurrent(null) + 3 asserts(Ack cmd / appliedFrames≥10 / gmB.findList size=10 & names set equals)。
- **烟测命令（顺序严格，2m30s）**：
  ```
  mvn -pl p2p-core,p2p-db -am install -DskipTests -q
  mvn -pl p2p-db '-Dtest=ds.DbEntityBinlogFrame_P7_C4_HandlerTest,com.q3lives.ds.binlog.DsBinlogReplicator_P7_C4_Test,com.q3lives.ds.binlog.DsBinlogReplicatorDebug_Test,ds.DsRegistry_P31_MasterIndexPrefixTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
  ```
  Expected：`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`（1+2+1+5=9）
- **全量验证（正式 promote）**：`mvn -pl p2p-db test` → `Tests run: ≥508, Failures:0, Errors:0, Skipped:0`（2026-09-17 实际=508，上回=507，净 +1 Tgt-1）
- **验收（已全部 VERIFIED，0 pending）**：
  - 烟测 9/9 PASS ✅（实际 2m36s exit=0）
  - 全模块 508/508 PASS ✅（15m50s BUILD SUCCESS）
  - AGENTS Invariant 追加 2 条 ✅ / current-state Tgt-1 block ✅ / Recipe 本节 ✅

### ✅ **[VERIFIED CLOSED — 2026-09-16 18:24] Tgt-5（中 ROI 并行可交付）：DsRegistryExtAttrsHelper 跨节点 Handler 23 命令号 + 7 tests 0F0E 烟测 46 0F0E**
- **Scope 实际落地（依赖 Tgt-3 完成，Reslover 零改复用）**：
  1. REQ 24313-24335 / ACK 24413-24435（共 23+23 命令号，与 Tgt-3 连续无空洞，BINLOG_FRAME 24300/24301 保留）
  2. 26 类 Model：23 Request（TAG 8 / DESC 5 / SPEC 5 / COMMENT 5，全参骨架对齐 Tgt-3：4-tuple + keyGlobalNodeId + 业务字段）+ 3 专用 Ack（ExtAttrsWriteAck / ExtAttrsListStringAck / ExtAttrsLastStringAck，不污染 Tgt-3 Ack 命名）
  3. 23 ServerHandler：复用 Tgt3RegistryResolver 双模式路由（resolveTarget/resolve + injectForTest/clearForTest），每 Handler 15-20 行，TAG listTagsFiltered Predicate 不可序列化 → Request 仅 `limit + tagPrefix` 本地转 startsWith
- **三 Ack 语义锁定（AGENTS Invariant 已追加）**：
  1. WriteAck：boolean 返回（addTag/addTags/removeTag/removeLastX）→ `ok=返回值`，numericValue=返回值?1:0；int 返回（removeTags/removeAllX）→ ok=true，numericValue=removed count；long 返回（addX=seq）→ ok=true，numericValue=seq
  2. ListStringAck：listTags/listXs values = new ArrayList<>(LinkedHashSet/List)，保留字母序稳定
  3. LastStringAck：lastX return null → present=false，否则 stringValue=return
- **专项 7 tests（15.07s 0F0E — DsRegistryExtAttrs_P7_Tgt5_HandlerTest）**：T01 TAG 幂等 + list/has；T02 批量 removeTags + removeAll + 空；T03 DESC seq 1-2-3 + resetSeq=1；T04 SPEC 5 删 3 剩 2；T05 COMMENT null+空；T06 4 类 3 entry 零交叉；T07 双模式 fallback + 双模式缺=STD_ERROR 3 种 Handler
- **烟测 46 tests 0F0E**：C4 Debug1 + C4 Replicator2 + HandlerTgt-1 1 + ExtAttrs12 + Tgt-5 7 + P31 5 + Tgt-3 4 + baseline14 = 46
- **烟测命令**：
  ```powershell
  mvn -pl p2p-core,p2p-db -am install -DskipTests -q
  mvn -pl p2p-db '-Dtest=ds.DsRegistryExtAttrs_P7_Tgt5_HandlerTest,ds.DsRegistry_P7_Tgt3_HandlerTest,ds.DsRegistryExtAttrs_P7_Exts_Test,ds.DsRegistryTest,ds.DsRegistry_P31_MasterIndexPrefixTest,ds.DbEntityBinlogFrame_P7_C4_HandlerTest,com.q3lives.ds.binlog.DsBinlogReplicator_P7_C4_Test,com.q3lives.ds.binlog.DsBinlogReplicatorDebug_Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
  ```
  Expected：`Tests run: 46, Failures: 0, Errors: 0, Skipped: 0`
- **Promote 全量**：`mvn -pl p2p-db test` → ≥531 tests（508 baseline + 12 ExtAttrs + 4 Tgt-3 + 7 Tgt-5 = 531）Failures:0 Errors:0
- **已交付工件 cross-link**：AGENTS.md Invariant Tgt-5 10 条 / current-state Tgt-5 block / P2PCommand.java#L583-L673 / 26 Model 类 / 23 Handler 类 / 7 tests 测试类

### ✅ **Tgt-2（中 ROI，下一轮唯一未交付 Follow-up）：ORM rowId 选 DsIdLayout 编码 + DsNodeRouter 真正跨节点 routeClient**
- **Scope**：当前 B1 因 rowId 本地自增（sid=0,pid=0）永远命中 local，只能短路；正式分布式需要 GM.saveOrUpdate 生成 rid 用 `DsIdLayout.compose(serverId,partitionId,localAddress)`，而 rid 解出 sid/pid≠本地时真正调 `routeClient.send(DB_ENTITY_PUT, remotePeer)` 拿返回。
- **Hard constraint**：必须保留 `DbEntityPut/Get/Remove/Exists 四条 Handler` 现有签名（不拆 Request/Ack 字段），只是 DsDatabaseServer putTable/getTable 的分支：先 `DsIdLayout.serverId(rid)/partitionId(rid)`，匹配 local 才短路，否则走 routeClient.send 异步→同步 CompletableFuture。
- **验收**：新增 3 tests 双 JVM（或同 proc 两 DB instance）跨节点 PUT→GET→REMOVE→EXISTS 全链路 PASS 0F0E。

### ✅ **[VERIFIED CLOSED — 2026-09-17 03:35] Tgt-ExtAttrs（中 ROI — P7 ExtAttrs 专项）：DsRegistry DIR 模式目录层级扩展属性 TAG/DESC/SPEC/COMMENT 一对多存储获取，零改 DsRegistry.java 核心**
- **需求消歧 4 点（用户确认）**：① 全 4 种均一对多（DESC/SPEC/COMMENT 多版本历史非单值覆盖）；② @tags/@meta 前缀；③ 独立 DsRegistryExtAttrsHelper 工具类零改核心；④ TAG 幂等。
- **Public API（23 项公共静态）**：TAG 8 = addTag/addTags/removeTag/removeTags/removeAllTags/listTags×2/hasTag；DESC/SPEC/COMMENT 各 5 = addX/listXs/lastX/removeLastX/removeAllXs。
- **存储路径**：`<entryKey>/@tags/<tagName>` REG_SZ（TAG 空串标记存在） + `<entryKey>/_extNextSeq/<seqKey>` REG_DWORD（4 类独立计数器）+ `<entryKey>/@meta/descs|specs|comments/<N>` 数字子目录 N∈1..N REG_SZ 严格递增。
- **nextSeq resetSeq 必须**：任何 removeAll<TYPE> 必 resetSeq 写 0；下次写入重新编号 1..（避免计数膨胀 + 数字连续无空洞）。
- **listTags limit<=0 修复**：旧实现 limit=0 → size(1)>=0 立即 break 仅返回 1；修法 `max = (limit<=0)?Integer.MAX_VALUE:limit`；tagFilter 在 sort 之后 apply 过滤。
- **空语义 / null 文本**：不存在 entryKey 或目录空 → listXs 空 Collection / lastX null；addX(null) → 空串 "" 写入不抛。
- **烟测命令（35 tests 2m41s）**：
  ```
  mvn -pl p2p-core,p2p-db -am install -DskipTests -q
  mvn -pl p2p-db '-Dtest=ds.DsRegistryExtAttrs_P7_Exts_Test,ds.DsRegistryTest,ds.DsRegistry_P31_MasterIndexPrefixTest,ds.DbEntityBinlogFrame_P7_C4_HandlerTest,com.q3lives.ds.binlog.DsBinlogReplicator_P7_C4_Test,com.q3lives.ds.binlog.DsBinlogReplicatorDebug_Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
  ```
  Expected：`Tests run: 35, Failures: 0, Errors: 0, Skipped: 0`（ExtAttrs专项12 + DsRegistry14 + P31 5 + HandlerTgt-1 1 + C4 3 = 35）
- **Promote 全量**：`mvn -pl p2p-db test` → `Tests run: ≥520 Failures:0 Errors:0`（508 + 新增 12 = 520）。
- **后续 Follow-up**：Tgt-5（ExtAttrs 跨节点 Handler 24310-24332 命令号）依赖 Tgt-3 DsRegistry 跨节点 Handler 完成后并行；其余 Tgt-2/Tgt-3/Tgt-4 顺序不变。

### ✅ **[VERIFIED CLOSED — 2026-09-16 18:06] Tgt-3（中 ROI）：DsRegistry 跨节点 Handler D1 完整实现 9 条命令号 + 6 Ack + 双模式路由 4 tests 0F0E 烟测 39 0F0E**
- **Scope 实际落地（比原计划从 6→9 条升级：额外 +LIST_CHILDREN +FIND_BY_PREFIX +FIND_BY_PREFIX_KEYS L1325）**：
  1. REQ 24304-24312 / ACK 24404-24412（共 9+9 命令号，BINLOG_FRAME 24300/24301 保留不占用）
  2. 16 类 Model：9 Request（MKDIRS/PUT_LONG/PUT_STRING/PUT_BYTES/GET/REMOVE/LIST_CHILDREN/FIND_BY_PREFIX/FIND_BY_PREFIX_KEYS）+ 6 Ack（WriteAck 5 条复用 / GetAck / ListChildrenAck / FindByPrefixAck / FindByPrefixKeysAck）+ SimpRegistryEntry
  3. 10 类 Handler：Tgt3RegistryResolver（双模式路由 + 单例复用 + test 注入）+ 9 Handler 各对应命令号（RegistryMkdirs..FindByPrefixKeysServerHandler）
- **双模式路由（VERIFIED — T04 fk2 globalNodeId fallback + fk3 IAE stdError 双重路径）**：① 4-tuple targetSP 优先（tgtS/tgtP ≥0 → 直接用）；② keyGlobalNodeId fallback（DsIdLayout.serverId()/partitionId() 反解）；③ 都缺 → `P2PErrors.stdError(IAE)`
- **rootDir 物理隔离（零单机破坏 + 零 bucket 碰撞）**：`baseRoot = System.getProperty("p2p.registry.home", user.home/.p2p/registry)` → 实际 `rootDir = <baseRoot>/<srv>_<pid>/`；Tgt3RegistryResolver.resolve(srv,pid) 走 INSTANCES CHM computeIfAbsent 单例复用
- **专项 4 tests（3.711s 0F0E — DsRegistry_P7_Tgt3_HandlerTest.java）**：
  | # | Test | 核心断言 |
  |---|---|---|
  | T01 | MkdirsPutString_Get_4tupleRoute_ok | A(12,0)→B(42,0) mkdirs→putString(v1)→get.present=true stringValue=v1；交叉 A 侧 get.present=false |
  | T02 | PutLongBytes_Remove_Recursive_ok | PutLong REG_QWORD inline=998877665544332211；PutBytes 10-byte assertArrayEquals；remove false=STD_ERROR 目录非空；remove true=成功后 get.present=false |
  | T03 | ListChildren_3items_setEqual | listChildren("/apps") size=3 Set{"a1","a2","b1"} 精确 |
  | T04 | FindByPrefix_Keys_dualRoute_L1325_ok | ① findByPrefix("/items") entries=3 REG_SZ；② **L1325 findByPrefixKeys("/docs")** → ["/docs/01","/docs/02"] 精确；③ globalNodeId fallback 路由到 SB/PB；④ 双模式失败=STD_ERROR |
- **烟测 39 tests（0F0E BUILD SUCCESS）**：C4 Debug 1 + C4 Replicator2 + HandlerTgt-1 1 + ExtAttrs 12 + P31 5 + Tgt-3 4 + DsRegistry baseline 14 = 39
- **烟测命令**：
  ```powershell
  mvn -pl p2p-core,p2p-db -am install -DskipTests -q
  mvn -pl p2p-db '-Dtest=ds.DsRegistry_P7_Tgt3_HandlerTest,ds.DsRegistryExtAttrs_P7_Exts_Test,ds.DsRegistryTest,ds.DsRegistry_P31_MasterIndexPrefixTest,ds.DbEntityBinlogFrame_P7_C4_HandlerTest,com.q3lives.ds.binlog.DsBinlogReplicator_P7_C4_Test,com.q3lives.ds.binlog.DsBinlogReplicatorDebug_Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
  ```
  Expected：`Tests run: 39, Failures: 0, Errors: 0, Skipped: 0`
- **Promote 全量**：`mvn -pl p2p-db test` → `Tests run: ≥524 Failures:0 Errors:0`（508 baseline + ExtAttrs 12 + Tgt-3 4 = 524）
- **已交付工件（cross-link 到 current-state / AGENTS Invariant / 源码路径）**：
  1. AGENTS.md Local Notes 追加 Tgt-3 11+1 Invariant
  2. current-state.md Updated 2026-09-16 Tgt-3 block（含 9 命令号表 / 4 tests 逐项 / 烟测 39 证据 / Joint Verification Cmd）
  3. P2PCommand.java#L543-L581 9+9 枚举注册
  4. p2p-core 16 类 Model（9 Request + 6 Ack + SimpEntry）
  5. p2p-db 10 类 Handler（Resolver + 9 Handler）
  6. 新测试 DsRegistry_P7_Tgt3_HandlerTest 4 tests

### ✅ **Tgt-4（低 ROI，需先做 Tgt-1/Tgt-2 攒稳定链路）：C4 binlog 三文件 LZ4 压缩 + checksum**
- **Scope**：参照 DsMemory cold-tier LZ4 不变式，DbEntityBinlogFrameRequest 的 payload `BINLOG_FIXED`/`BINLOG_DYN`/`BINLOG_BYTES` 三块 header 前加 `u32 LE originalLen + u32 LE compressedLen`，source 侧 doTick 内 LZ4.compress，target 侧 Handler.applyBinlogEntry 前 decompress。
- **注意**：必须先 `DsBinlogReplicator_P7_C4_Test` 现有 2 tests PASS 加 baseline，再加压缩；否则新变量太多难以定位问题。

## 四、Invariant Recheck（P7 每一次改动前，先过 6 条 mental checklist）
1. 7 大底层零改？（DsIdLayout/DsTypeString/DsTypeFixedBucketStore/DsFreeRing/DsHashMap/MultiTypeDataId/DsTieredMasterIndex）✅
2. 旧 Public API 签名 100% 保留？（旧 1 参构造 / 旧 saveOrUpdate 传参不变）✅
3. Q2=C2 ORM rowId 纯本地自增？（不碰 putEntity 内部 rid 分配）✅
4. Q3=C3 tableId=FNV-1a(className)？（不引入任何全局注册表映射）✅
5. classIdSet 单实例复用？（GM.ensureInit → db.getIdSetForClass 不 new DsHashSet）✅
6. findByPrefixKeys/listKeys 返回 mutable ArrayList？（不是 stream().toList()）✅

6 条过了才改代码。少任何 1 条 → 先修 invariant 再谈 feature。
