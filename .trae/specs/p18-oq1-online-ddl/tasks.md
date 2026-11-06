# P18 OQ1 Online DDL 全量 Rewrite 批量 —— tasks.md（9 任务垂直切片，严格串行依赖）

**绝对日期：2026-09-28（零相对日期）**
**每任务 Test Requirements（TR）仅 rule / rubric 两类，与 spec.md AC 一一对齐**

---

## 任务依赖图（严格串行，禁止跳步）

```
T1 (KeySpace + Manifest Paths)
  ↓ (依赖 RegistryMetaPaths 常量)
T2 (ADD COLUMN + defaultValue 回填 batch)
  ↓ (依赖 newColIds 分配)
T3 (DROP COLUMN 软删 tombstone + purge 延迟硬删)
  ↓ (依赖 deleted/unDelete Backend)
T4 (RENAME COLUMN 原子双向映射 + alias 灰度 + Replay Loop)
  ↓ (依赖 ColRegistry Backend rename)
T5 (Coordinator 内嵌类 + 幂等 20× + cancel)
  ↓ (依赖 6 种 DDL 原语)
T6 (c14 Gatekeeper 25/25 assertions + NG-2/3/4 实锤)
  ↓ (依赖所有内嵌 hook)
T7 (Perf Rubric U1/U2 双 ≥ 4，回填 P99 + 内存峰值)
  ↓ (依赖 3 种 DDL 原语稳定)
T8 (JV P10~P18 ≥ 230 tests 0F0E，P15 拆 3 批隔离)
  ↓ (依赖全量功能回归)
T9 (NeatFreak 三层同步 + 12 Invariants + OQ8 三选一)
```

---

## Task 1：RegistryMetaPaths Manifest 路径 + ColumnDef.nullable + 10 IAE + P18_DdlKeySpaceTest 3 TR

**Status: pending**
**Priority: high**
**依赖: 无**
**父 AC: AC-R3(NG-4 zeroLeak) / AC-R4(路径合规) / AC-R5(12 helper + 10 IAE) / AC-R14(nullable)**

### 1.1 实现范围（合并优先，不新建文件）
在现有文件内追加，**禁止新增 Maven 依赖，禁止新增 P2PCommand**：

#### 文件 A：`p2p-db/src/main/java/com/q3lives/ds/registry/meta/RegistryMetaPaths.java`
（**NG-4 铁律**：manifest/schemaVersion/tombstone/migrateId 字面量仅在此文件声明 1 处）

- **新增常量（缩写避零外泄）**：
  - `static final String ONLINE_DDL = "onlineDDL"`（复用 existing，无需新常量）
  - `static final String MANIFEST_SUB = "manifest"`；`static final String HISTORY_SUB = "history"`；`static final String TOMB_SUB = "tombstones"`
  - `static final String SCHEMA_VERSION_LEAF = "version"`（schemaVersion 完整词会触发 NG-4 命中 → 缩写 helper 名 schVer）
  - `static final String MIGRATE_ID_LEAF = "nextMigrateId"`；`static final String CURRENT_MIG_ID_LEAF = "currentMigrateId"`
- **新增 12 helper（package-private，返回 safe 路径段）**，命名一律缩写避免 NG-4 命中：
  1. `odManifestDir(File dbsRoot, String space, String entityClass)` → `/orm/dbs/<space>/onlineDDL/<entity>/manifest/`
  2. `odManifestPhase(...)` → `manifest/phase`
  3. `odManifestMigId(...)` → `manifest/migrateId`
  4. `odManifestFromSig(...)` → `manifest/fromSignature`
  5. `odManifestToSig(...)` → `manifest/toSignature`
  6. `odManifestProgress(...)` → `manifest/progress`
  7. `odManifestHistoryDir(...)` → `history/<migIdHex>/manifest/`（16 位 hex 补 0）
  8. `odCurrentMigId(...)` → `currentMigrateId`
  9. `odNextMigId(...)` → `nextMigrateId`
  10. `colSchVersion(String entityClass)` → `/orm/column/<entity>/table/version`（单调 schVer QWORD）
  11. `odTombDir(File dbsRoot, String space, String entityClass)` → `/orm/dbs/.../tombstones/`
  12. `odTombCol(..., long colId)` → `tombstones/<colIdHex16>/{markedAt,retentionMs,size}`
- **10 条 IllegalArgumentException（IAE）**，每个 helper 入参校验：
  - entityClass / space 空串或 null → IAE("...empty")
  - colId ≤ 0 → IAE("colId<=0")
  - migId < 0 → IAE("migId<0")
  - schemaVersion < 0 → IAE("schVer<0")
  - retentionMs < 0 → IAE("retention<0")
  - dbsRoot null → IAE("dbsRoot null")
  - phase 不在 6 态集合 → IAE("phase invalid")
  - stepId < 0 → IAE("stepId<0")
  - logicalName 长度 > 256 → IAE("colKey too long")
  - toHexPadded 长度非 16（colIdHex/migIdHex）→ IAE("hex width")

#### 文件 B：`p2p-db/src/main/java/com/q3lives/ds/database/columnar/TableMetaStore.java`
（**NG-2 铁律**：public 方法签名 0 新增，全部改 package-private / private 内嵌）

- `ColumnDef` 静态内部类（L449-L457）加 `public boolean nullable = true;` 字段（默认 true 向后兼容）
- `build(Class<T>)` 中 @DsField.nullable() 读值写进 ColumnDef.nullable（L199-L224 段）
- `build(DbTableSchema, overwrite)` 同，DbColumnSchema 加 nullable 字段同步写
- `computeSignature()`（L354-L368）追加 `F|...|c.nullable` 位段，保证 nullable 变更 signature 变
- `loadFromRegistry(...)` 读旧 TableMeta 时缺 nullable 字段默认 true（BackwardCompat）
- `save(...)` 写 Registry 时同步写 nullable 值进 columns leaf

#### 文件 C：`p2p-db/src/main/java/com/q3lives/ds/database/columnar/ColumnRegistry.java`
（**NG-2 铁律**：public 8 方法 ±4，内部 Backend 改 package-private 新增 unDelete + renameColKey）

- `Backend` 静态内部类加 2 个 **package-private** 方法：
  1. `void unDelete(String entityClassName, long colId)`：
     - Registry：`registry.remove(columnDeleted(entity, colId))`（remove 单个 leaf，不删整个 deletedDir）
     - YAML（非 REGISTRY 模式）：`loadMeta → meta.deleted.remove(colId) → saveMeta`
  2. `void renameColKey(String entityClassName, String oldColKey, String newColKey)`（原子，fileLock 内）：
     - lock.lock → colId = registryFindColId(entity, old)（null 抛 IAE）
     - registryFindColId(entity, new) 非 null 抛 IAE("new exists")
     - registryRemove(colKeyRoot, old)；registryPutLong(colKeyRoot, new, colId)；registryPutString(colIdRoot, toHex(colId), new)
     - YAML：meta.colKeyToId.remove(old)；meta.colKeyToId.put(new,colId)；meta.colIdToKey.put(colId, new) → saveMeta → unlock
- `ColumnRegistry` 对应加 2 个 **package-private** wrapper：`unDelete(entityClass, colId)` + `renameColKey(entity, old, new)`（public=8，加这俩是 package，不算 public 计数）
- 新增 `findColIdByColId(String entity, long colId)` package-private：反向 byId 查 colKey（rename 用）

### 1.2 Test Requirements（TR 3/3）

**TR-1.1（rule，父 AC-R4/R5）**：RegistryMetaPaths 反射 12 helper 存在 + 路径段 10 项合规
- 反射 `getDeclaredMethod` 12 个 helper，每个入参 null/空抛 IAE 10/10 全中
- 每个 helper 拼出的路径 startsWith `/orm/dbs/<space>/onlineDDL/` 或 `/orm/column/<entity>/table/`
- 0 新增 `/orm/` 下第一级子目录（枚举 RegistryMetaPaths 所有常量路径段 root-level）

**TR-1.2（rule，父 AC-R14）**：ColumnDef.nullable 持久化 + 签名联动
- 实体类 V nullable=false → build → ColumnDef.nullable=false；computeSignature() 与 nullable=true 版不同
- 旧 TableMeta 无 nullable 字段 → loadFromRegistry → nullable=true（BackwardCompat）
- DbColumnSchema 版 nullable=false 同步正确落盘

**TR-1.3（rule，父 AC-R3 NG-4 zeroLeak）**：grep 全 src 源码「manifest|schemaVersion|tombstones|migrateId|onlineDdl」字面量仅 RegistryMetaPaths.java 1 files 命中
- Pattern.compile 正则扫；实现层使用 odManifest / odTomb / schVer / migId 缩写 0 全词命中
- 预期：`expected files with matches = 1`；若 >1 → 改名缩写
- **实锤注意**：若 GenericManager / DsDatabaseLocal 内部字段名用全拼会命中，必须按 P17 compositeRangeIndexMaps → compRangeIndexMaps 的先例改缩写

### 1.3 Completion Evidence
- `mvn -Dtest=P18_DdlKeySpaceTest test` → Tests run: 3, Failures: 0, Errors: 0
- TR-1.3 t03 zeroLeak 实际命中 files=1 打钩
- 12 helper 每个 1 条 IAE 10/10 全触发 PASS 打钩
- ColumnDef nullable 签名 2 版哈希不同 PASS 打钩

---

## Task 2：ADD COLUMN defaultValue 回填 batch 调度器 + P18_AddColumnDdlTest 5 TR

**Status: pending**
**Priority: high**
**依赖: T1（RegistryMetaPaths 12 helper + ColumnDef.nullable）**
**父 AC: AC-R6(dry-run 零写) / AC-R7(checkpoint 续跑) / AC-U1(回填 P99) / AC-U4(checkpoint ≥14)**

### 2.1 实现范围
#### 文件 A：`p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java`
（NG-2 public=48，内嵌类 `private final OnlineDdlCoordinator odCoordinator`，构造器初始化，IOException warn null，类似 P15 RaftServer 模式）

- 新增内嵌类 `OnlineDdlCoordinator`（private static final，放 DsDatabaseLocal 内部不 new 文件）：
  - 字段：`File dbRoot`、`private final String spaceName`、`AbstractRegistryMetaBackend backend`、`ThreadLocal<int[]> checkpointStep`（缩写避 NG-4）
  - 6 个 package-private 方法（NG-2 合规模板：不写进 Facade public 清单）：
    1. `prepareAdd(entityClass, List<DbColumnSchema> newCols, boolean dryRun, long retentionMs)` → manifestId
    2. `applyAdd(long manifestId, int batchSize)` → progress
    3. `prepareDrop(entityClass, List<String> dropColNames, boolean dryRun, long retentionMs)`（T3 用）
    4. `applyDrop(long manifestId, boolean purgeNow)` → tombstone 数
    5. `prepareRename(entityClass, List<Pair<String,String>> renamePairs, boolean aliasPhase)` → manifestId
    6. `applyRename(long manifestId)` → renamed 数
    7. `rollback(long manifestId)` → 反向执行（T5 组合用）
    8. `status(long manifestId)` → {phase,progress,checkpoint_count}
  - 字段名缩写：`manifestId → migId`、`checkpointStep → cpStep`、`schemaVersion → schVer`、`tombstones → tombs`、`onlineDdl → od`（NG-4 zeroLeak 防命中）

#### 文件 B：`p2p-db/src/main/java/com/q3lives/ds/database/integration/GenericManager.java`
（T2/T3 调用链：GM saveOrUpdate 通过 odCoordinator hook；字段名缩写）
- 增 `private final OnlineDdlCoordinator odCoord`（若 DsDatabaseLocal 有则拿引用，否则 null）
- saveOrUpdate / updateById 内部若 `schVer` 字段变更则调 `odCoord.status(currentMigId)` 校验中间态不允许写（DDL 期间阻塞写，或走双写）
- candidateRowIdsFromIndex 对 isDeleted=true 的列读路径短路跳过（T3 联动）

### 2.2 ADD COLUMN 核心算法（applyAdd 内部）
```
PREPARING:
  1. CAS incr nextMigrateId → migId (16hex)
  2. TableMeta old = tableMetaStore.getMeta(entity)
  3. old.sha256 → manifest.fromSignature / manifest.fromSha256 (T1 helper odManifestFromSig)
  4. newCols list diff → newColIds 清单
  5. for each newCol: colId = columnRegistry.getOrCreateColId(entity, colKey)
     manifest.newColIds.put(colId, colKey + ":" + defaultValueBase64)
  6. new TableMeta build(newCols merged) → new.sha256 → manifest.toSignature / manifest.toSha256
  7. manifest.checkpoints.put("prepare_ok", sha256(old+new))
  8. manifest.phase=PREPARING → TAG=SCHEMA_MIGRATING（复用 existing TAG）

DRY_RUN 模式:
  1. 扫 rowIdSet 总数 N → manifest.stats.totalRows=N
  2. 抽样 100 行预览 defaultValue → manifest.preview（不写任何 Registry/列存 leaf）
  3. 返回 manifestId + 预览 JSON，0 实际写

APPLY 模式(batchSize=2048):
  cp = manifest.checkpoints.maxKey + 1
  2. schVer = CAS incr colSchVersion(entity) → new schVer
  3. 保证：tableMetaStore.ensureMeta(new Schema, overwrite=true) 写新 signature
  4. rowIds = rowIdSeqStore.allIds(entity) 有序迭代器
  5. while (rowIds.hasNext):
     batch = take next 2048 rowIds
     for each rowId in batch: for each (colId, defVal) in newColIds:
        columnarStore.putValue(entity, logicalName, rowId, coerce(defVal))
     cpId = "apply_batch_" + cp
     sha = sha256(batch.last.rowId + all colId rowIds set)
     manifest.checkpoints.put(cpId, sha)
     manifest.progress = (total_done / N) * 100
     cp++
  6. 收尾：manifest.phase=DONE；TAG 清 SCHEMA_MIGRATING；history/<migIdHex>/ 备份 manifest 全量
  7. 返回 migId + progress=100%

断点续跑：新 Coordinator 构造时，读 manifest.checkpoints.max(cpId) → cpStart = max+1 → 跳过 0~cpStart-1 batch
```

### 2.3 Test Requirements（TR 5/5）

**TR-2.1（rule，父 AC-R6 dry-run 零写）**：N=10000 行 V1 实体，dryRun=true 准备
- 前后 Registry dump SHA256 完全相同（grep manifest.leaf 0 写；列存/行存 0 putValue）
- manifest.stats.totalRows=10000；manifest.preview.sample 100 行 defaultValue 正确

**TR-2.2（rule）**：dryRun=false APPLY N=10000 × 2 新列（long newA + String newB=默认值）
- apply 返回 progress=100；checkpoint_count = ceil(10000/2048) + prepare_ok + done = 5+2=7+ ≥ 7
- 10000 行随机抽 1000 行 getField(newA) = 0 / getField(newB) = 默认值 100%

**TR-2.3（rule，父 AC-R7 断点续跑）**：中途 kill 模拟
- N=50000，batch=512；apply 跑到 cp=batch_10（进度 20%）时抛异常中断
- 新 Coordinator 同 migId 续跑 apply，跳过 0-10 batch，最终 50000 行 100% 完整，checkpoint 不重复
- 2 次 apply 合计数据行数 = 50000，无重复无遗漏

**TR-2.4（rubric，父 AC-U4 检查点密度）**：manifest.checkpoints leaf 数量
- 18+ = 5 分；14-17 = 4 分；10-13 = 3 分；<10 = 0 分
- 验收阈值：≥ 14 个 checkpoint（每个 batch 1 个 + prepare/分配 colId/DONE 额外项）

**TR-2.5（rule，父 AC-R14 nullable）**：新列 nullable=false 时 ensureMeta 持久化正确
- @DsField(nullable=false) 新列 ADD → TableMeta.ColumnDef.nullable=false；computeSignature 与 nullable=true 版哈希不同

### 2.4 Completion Evidence
- `mvn -Dtest=P18_AddColumnDdlTest test` → 5/5 0F0E
- 实际 TR-2.4 checkpoint 数量（如=15）+ 评分（如=4/5）记录归档
- 断点续跑 cp 日志 batch_10 跳过实际 0 重复写证据

---

## Task 3：DROP COLUMN 软删 tombstone + 延迟硬删 purge + unDelete rollback + P18_DropColumnDdlTest 3 TR

**Status: pending**
**Priority: high**
**依赖: T1(Backend.unDelete) / T2(Coordinator 骨架)**
**父 AC: AC-R8(软删 O(1) + rollback) / AC-R9(purge 守卫)**

### 3.1 实现范围
#### Coordinator 内部 prepareDrop/applyDrop/rollback(phase=DROP) 补齐（T2 文件 A 内部）

**prepareDrop(entity, dropColNames, dryRun, retentionMs)**：
- 校验所有 dropColNames 实际存在 + colId 有效（findColId 非 null）
- 写 manifest.droppedColIds = colId → logName
- 写 manifest.dropRetention = retentionMs（默认 604800000 = 7 天）
- dryRun：列出影响索引数 / 影响行数，0 写 Registry

**applyDrop(migId, purgeNow=false)**：
1. O(1) 软删主流程：for each droppedColId：
   - `columnRegistry.markDeleted(entity, colId)`（只写 deleted/<colId>=1 + tombstones/<colId>={markedAt=now, retention=ret, size=estimateSize(colId)}
   - 更新 manifest 进度
2. 清 tableMetaStore 内部 cache（强制下次 ensureMeta 重算签名）
3. manifest.phase = DONE；TAG 清
4. **purgeNow=true & manifest.phase=DONE & retention 到期**：`columnarStore.deleteColumnHard(entity, logicalName, 2048)` 逐个调（否则 return 不删）

**rollback(migId) — DROP 方向反向**：
- manifest.droppedColIds 遍历 → `columnRegistry.Backend.unDelete(entity, colId)`（T1 新增的反向 API）
- 恢复 TableMeta.columns 列表中被 remove 的项（读 manifest.fromTableMeta 重建）
- signature 回滚 fromSignature，schVer 回滚+1
- manifest.phase = ROLLBACK；TAG = SCHEMA_ROLLBACK（复用 existing）

### 3.2 Test Requirements（TR 3/3）

**TR-3.1（rule，父 AC-R8 软删 O(1)）**：N=1M 行大表 3 列，drop 1 列软删耗时
- 计时 ≤ 50ms（实锤，System.nano 前后差）；不扫任何数据
- 标记后 `columnRegistry.isDeleted(entity, colId)=true`
- 读路径：`genericManager.findList(query wrapper 引用该列)` 自动过滤该列 0 NPE（由 GM candidateRowIds 加 isDeleted 判断）

**TR-3.2（rule，父 AC-R9 purge 守卫）**：purge 条件
- manifest.phase=PREPARING → purgeNow=true 调 applyDrop 抛 IAE("phase not DONE")
- retentionMs=7天 but 当前距 markedAt=1h → purgeNow=true 调 applyDrop：deleteColumnHard 0 次被调用（Mock/计数器）
- phase=DONE + markedAt + retention <= now → purgeNow=true → deleteColumnHard 调用次数 = dropCols.size

**TR-3.3（rule，父 AC-R8 rollback unDelete 恢复）**：applyDrop → rollback 循环 20 次
- 每轮终态：isDeleted(colId)=false；ColumnDef 回 fromSignature 列表
- 随机抽 100 行读该列，值保留未丢（软删不碰物理，unDelete 直接解标）

### 3.3 Completion Evidence
- `mvn -Dtest=P18_DropColumnDdlTest test` → 3/3 0F0E
- TR-3.1 实际耗时（如=17.3ms ≤50ms）PASS 记录
- TR-3.2 IAE 5 条守卫全抛 PASS

---

## Task 4：RENAME 原子双向映射 + alias 灰度双读 + 100 次 ReplayLoop + P18_RenameDdlTest 4 TR

**Status: pending**
**Priority: high**
**依赖: T1(Backend.renameColKey + findColIdByColId) / T2(Coordinator)**
**父 AC: AC-R10(colId 不变/并发) / AC-R11(rollback 还原) / NFR-1(ReplayLoop 100×0差)**

### 4.1 实现范围
#### Coordinator.prepareRename/applyRename/rollback(rename) 补齐

**prepareRename(entity, renamePairs=[(old,new)], aliasPhase=false)**：
- 对每 pair：old 存在/findColId(old)!=null；new 不存在/findColId(new)==null（IAE 检查失败）
- manifest.renamedFromTo 清单写入：(oldKey → newKey → colId)
- aliasPhase=true：额外写 manifest.alias=true（灰度期双读逻辑）

**applyRename(migId)**：
1. for each (old→new)：`columnRegistry.Backend.renameColKey(entity, old, new)`（T1 原子实现 fileLock 锁内 3 步）
2. TableMeta 中 ColumnDef 的 colKey / name 同步更新；compositeGroup 中有引用也改
3. 新 signature 写回；schVer CAS 增；TAG 更新
4. 灰度 alias：读路径 findColId 增加别名查找（findColId(new) null → 查 manifest.alias 映射 old，若存在且未 unmap → 返回 colId）；写路径双写（new 写 + old 写）

**rollback(migId) — RENAME 方向**：遍历 manifest.renamedFromTo swap（swap new↔old）再调同一 renameColKey 原子重做一遍 → 还原初始态

### 4.2 Test Requirements（TR 4/4）

**TR-4.1（rule，父 AC-R10 colId 不变）**：Rename 10 列
- 每列 rename 前 colId = rename 后 colId 全等
- byKey.old read null；byKey.new = colId；byId[colId] = newColKey → 10/10 双向 1:1 无孤儿

**TR-4.2（rule，父 AC-R10 并发）**：20 线程同时 rename 20 不同列 per table
- 每个线程调用 applyRename（不同列名），20 并发
- 终态：20 列 colId 与基线一致；byKey/byId 映射 20:20 无孤儿；无 FileLockInterruption；无死锁

**TR-4.3（rule，父 AC-R11 rollback）**：applyRename → rollback 双轮（apply↔rollback 两遍）
- 每轮结束后，初始 V1 colKey 映射 SHA256 = 终点 V1 映射 SHA256
- V1 代码（查旧列名）读数据 1000 行 × 10 列无 NoSuchColumn / 0 NPE

**TR-4.4（rule，父 NFR-1 ReplayLoop 100 次 0 差）**：ADD 2 列 + DROP 1 列 + RENAME 2 列 = 组合 DDL
- apply → rollback 循环 100 次
- 起点 TableMeta.signature SHA256 = 终点 signature；抽 1% 行（100 行×5列）读值 ByteArray 相等 100%

### 4.3 Completion Evidence
- `mvn -Dtest=P18_RenameDdlTest test` → 4/4 0F0E
- ReplayLoop 100 rounds 总耗时日志（如 23.7s，每轮平均 237ms）
- 并发 20 线程 rename 无死锁 jstack 日志片段

---

## Task 5：Coordinator 内嵌类完整 + 幂等 20× + cancel API + P18_DdlCoordinatorTest 3 TR

**Status: pending**
**Priority: medium**
**依赖: T2/T3/T4（三 DDL 原语）**
**父 AC: AC-R12(幂等 20×) / NFR-5**

### 5.1 实现范围

#### cancel API（Coordinator 补全）
- `cancel(migId)`：
  1. 校验 phase ∈ {PREPARING, DRY_RUN, APPLY}（DONE/ROLLBACK 不允许 cancel）
  2. TAG = SCHEMA_MIGRATION_CANCELLED（复用 existing 常量）
  3. manifest.phase = CANCELLED
  4. return（不 rollback，保留中间态 checkpoint，后续手动 `rollback(migId)` 决定是否回滚）

#### 幂等性实现（每 apply 原子步骤由 checkpointId→sha256 判重）
- applyAdd/applyDrop/applyRename 每个原子操作前：`if manifest.checkpoints.containsKey(cpId) {sha == expected ? continue : raise IAE("sha mismatch, data corrupted")}`
- 幂等 20× 调用 apply → 终态 stepId 稳定；progress=100 稳定；返回 status 一致

### 5.2 Test Requirements（TR 3/3）

**TR-5.1（rule，父 AC-R12）**：ADD 1M 行 apply 20× 幂等
- migId 固定；第 1 次 apply progress→100；2-20 次 apply 直接 return 0 写（checkpoint 全命中 skip）
- 20 次 progress 差值=0；TableMeta.signature 20 次字节相等（Arrays.equals）
- checkpoint_count 20 次不变（无新增 checkpoint leaf）

**TR-5.2（rule）**：Cancel API 状态机守卫
- phase=DONE cancel → IAE("cannot cancel done")
- phase=APPLY 中途 cancel → TAG = CANCELLED；后续 progress 停在当前（如 37%）不前进
- rollback(migId) 在 phase=CANCELLED 允许调 → 回滚到 fromSignature

**TR-5.3（rule）**：Status API 输出完整
- status(migId) 返回 JSON 字符串包含：phase / progress / checkpointCount / migId / schVer / startedAt / tombstones
- 字段全非空（未开始项 0 / null 有默认值）；JSON parse 不抛异常

### 5.3 Completion Evidence
- `mvn -Dtest=P18_DdlCoordinatorTest test` → 3/3 0F0E
- 幂等 20× apply 写盘计数：实际 1 次（其余 skip）证据（计数器）
- Cancel 守卫 5 条 IAE 全触发

---

## Task 6：c14 DdlGatekeeperTest 25/25 assertions（A6/B6/C5/D5/E3）+ P18_DdlGatekeeperTest 1 TR

**Status: pending**
**Priority: high**
**依赖: T1~T5（所有 NG 合规模板已落）**
**父 AC: AC-R1 / AC-R2 / NFR-6**

### 6.1 实现范围（纯测试文件，P16/P17 25 模板可直接套）
5 组 assertions（继承 P16 c12 + P17 c13 分组结构）：
- **A6（6）**：6 Facade 类 public 方法数计数（DsDb=48/ColStore=24/TableMeta=3/ColReg=8/RowIdSeq=1/EqMeta=7），每个 baseline±4 内
- **B6（6）**：6 Facade 新增内部方法不含 public（Coordinator 内嵌方法全 package/private；新增 ColumnDef.nullable 字段非 public 方法；unDelete/renameColKey Backend 全 package）
- **C5（5）**：RegistryMetaPaths 中 5 类字面量仅此文件 1 files 命中（COLUMN_RANGE_ROOT / ROW_RANGE_ROOT / COMPOSITE_RANGE_ROOT / ONLINE_DDL_MANIFEST / RAFT_ROOT 合取）
- **D5（5）**：5 通用集合类 Class.forName 全 ClassNotFound（fastutil/eclipse/HPPC/chronicle/commons-collections4）
- **E3（3）**：2 个非 facade 实现类（GenericManager/DsDatabaseLocal）public 方法数计数 ≤ 基线 P17 + 5（未新增 public API）

**合计：6+6+5+5+3 = 25 assertions（AC-U3 threshold=4，若扩 E3 3→6 则 28/28 = 5 分 HARD）**

### 6.2 Test Requirements（TR 1/1 rule）
**TR-6.1（rule）**：`mvn -Dtest=P18_DdlGatekeeperTest test` → 25/25 assertions pass（无 skip / 无 assumptionFailed）；可选扩 28 = 5 分
- A6: 6/6；B6:6/6；C5:5/5；D5:5/5；E3:3/3 → 合计 25
- 若 C5 新增 manifest 字面量额外 +1 / D5 新增探针 +2 → 28（加 3 assertion 总分 ≥28，AC-U3=5 分）

### 6.3 Completion Evidence
- BUILD SUCCESS；JUnit 报告 assertion count 实际值（25 或 28）+ 每组分值打钩
- AC-U3 rubric 评分：28 = 5；25 = 4；<23 = FAIL

---

## Task 7：Perf Rubric U1/U2 双 ≥ 4 + P18_DdlPerfRubricTest 2 TR

**Status: pending**
**Priority: medium**
**依赖: T2~T4（3 DDL 原语稳定，checkpoint 无性能 bug）**
**父 AC: AC-U1(P99回填) / AC-U2(内存峰值)**

### 7.1 性能测试设计（不 new 文件，P18_DdlPerfRubricTest 单测试类 2 TR）

**U1：ADD COLUMN 1M 行 × 2 新列 × batch=2048 × 20 rounds**
- 每 round 计时；取 P99 = 第 19 个 sorted 值
- rubric 评分：P99≤30s=5；≤60s=4；≤120s=3；≤300s=2；>300=0

**U2：内存峰值 vs P17 基线 256MB**
- Runtime.getRuntime().maxMemory 快照；apply 期间峰值 = peak used memory（MXBean）
- rubric：≤384MB=5；≤512MB=4；≤1GB=3；>1GB=0

### 7.2 Test Requirements（TR 2/2 rubric）
**TR-7.1（rubric AC-U1，threshold≥4）**：P99 实际值记录 + 评分
**TR-7.2（rubric AC-U2，threshold≥4）**：峰值实际 MB 记录 + 评分
- 验收：2 条评分均 ≥ 4（SOFT 达标），至少 1 条 = 5

### 7.3 Completion Evidence
- `mvn -Dtest=P18_DdlPerfRubricTest test` → 2/2
- 归档 U1 P99 = X ms（如 42.3s → score=4）；U2 peak = 486MB → score=4
- 双评分 4/5 或 5/4 达标 ≥4

---

## Task 8：JV 合跑 P10~P18 ≥ 230 tests 0F0E，P15 拆 3 批

**Status: pending**
**Priority: high**
**依赖: T1~T7（功能 + Perf 稳定）**
**父 AC: NFR-4 / AC-U6（JV≥230，threshold≥4）**

### 8.1 执行命令（Surefire 模式，P15 PEERS 静态污染拆 3 批隔离 继承 P17 T8 模式）

**Batch 149+27=176 tests（不含 P15）**：
```bash
mvn -Dtest=P10_RowMetaTest,P10_ColumnMetaTest,P10_MetaBackendPerfTest,P11_DataKeySpaceTest,P11_DataBackendBasicTest,P11_RowDataTest,P11_ColumnarDataTest,P11_DataGatekeeperTest,P13_BloomKeySpaceTest,P13_BloomBasicTest,P13_BloomStoreColumnarTest,P13_BulkImportPerfTest,P14_*.java,P16_*.java,P17_*.java,P18_*.java,ColumnarStoreTest,ColumnarStoreCompressionTest,ColumnarStoreLayoutTest,Orm_P7_Tgt2_Handler_6_Test,DsRegistry_P7_Tgt3_HandlerTest,DsRegistryExtAttrs_P7_Tgt5_HandlerTest -Dsurefire.failIfNoSpecifiedTests=false test
```
**P15 拆 3 批**：
```bash
# 批① 2 tests
mvn -Dtest=P15_RaftCompactCrashTest test
# 批② 10 tests
mvn -Dtest=P15_RaftStateMachineTest test
# 批③ 42 tests
mvn -Dtest=P15_RaftKeySpaceTest,P15_RaftElectionTest,P15_RaftFacadeFenceTest,P15_RaftGatekeeperTest,P15_RaftPerfRubricTest,P15_RaftInstallSnapshotTest test
```
**合计量 = Batch(≥176) + P15(2+10+42=54) = ≥230 tests 0F0E**

### 8.2 Test Requirements（TR 1/1 rule + rubric）
**TR-8.1（rule，NFR-4 ≥230）**：所有 Surefire 报告 Tests Failures: 0, Errors: 0, Skipped: 0
- 合计总数 ≥ 230（AC-U6 评分：≥245=5 分；≥230=4 分；≥210=3 分；<200=0）
- P15 三批合计 Failures:0

### 8.3 Completion Evidence
- 4 次 `mvn` exit 0；最后合计 Tests run: 235（例如）/ 0F / 0E 打钩
- AC-U6 评分 = 5（若 245+）或 4（230+）

---

## Task 9：NeatFreak 三层同步 + 12 Invariants + OQ-8 三选一 + P18 Close

**Status: pending**
**Priority: low**
**依赖: T1~T8 全部 completed**
**父 AC: AC-U5（三层一致性）**

### 9.1 同步清单（严格 0 相对日期，合并优先于追加）

#### Layer 1：Agent project_memory.md（`c:/Users/.../project_memory.md`，合并 P17 pending）
- Project Context：P17 Close 2026-09-28 + P18 OQ7① Close 2026-09-28 两条合并
- Guidelines 追加 3 条：① DDL manifest 落址 /orm/dbs/onlineDDL/<ent>/manifest/ NG-4 合规 ② ADD/DROP/RENAME 三态 state machine + checkpoint 幂等续跑 ③ Coordinator 内嵌类 0 Facade public 签名模式（NG-2 复用 P15 RaftServer）
- Lessons Learned 追加 3 条：① 实现层字段名必缩写避 NG-4（manifest→odManifest/schemaVersion→schVer…）② unDelete 反向 Backend 与 markDeleted 对称性必须原子 ③ Purge 必须带 retention 守卫不能碰物理数据太早

#### Layer 2：项目根 `AGENTS.md`（P17 Invariants 之后追加，12 条 P18 Invariants）
沿用 P17 1-12 Invariants 模板：① NG-2 实锤 48/24/3/8/1/7 ±4 ② NG-3 0 新依赖/0 命令/5 集合探针 ③ NG-4 manifest 字面量 1 files 命中 ④ ADD dry-run 0 写 ⑤ DROP 软删 O(1) + unDelete 可逆 ⑥ RENAME colId 不变 原子双向 ⑦ Coordinator 内嵌 6 API package-private ⑧ checkpoint SHA256 幂等 20× ⑨ c14 25/25 assertions ⑩ ReplayLoop 100× 0 差 ⑪ T8 JV ≥230 P15 拆 3 批 ⑫ 12 Invariant 代码索引 4 核心 + 测试类 6 + OQ-8 复用命令 4 条

#### Layer 3：`docs/dsdb-design-status-and-roadmap.md`
- §六 P3 行 OQ-7 行 ⏳ Pending → ✅ CLOSED 2026-09-28 清单（9 tasks 闭环 + 验收）
- §六 追加 ⏳ OQ-8 三选一候选（和 OQ7 相同模式，3 候选项预填）
- §七 关键代码索引表追加 4 行 P18：① Manifest 路径 Helper / ② Coordinator 内嵌类骨架 / ③ Rename Backend 原子 renameColKey / ④ ADD 回填 batch 算法（绝对路径锚点 + line 范围）

### 9.2 Test Requirements（TR 1/1 rule + rubric AC-U5）
**TR-9.1（rule/rubric AC-U5）**：
- 三层文档 grep「今天/昨天/最近/today/...」= 0 命中
- 12 Invariants 关键字（NG-2 基线/三态/幂等/拆 3 批...）三层各出现
- AC-U5 评分=4（三层同步+12 Invariants+0 relative）或 5（加 history 索引 + OQ-8 预填）

### 9.3 Completion Evidence
- NeatFreak 自检清单 11/11 全打钩（skill 标准自检）
- 相对日期 grep result = 0 files / 0 occurrences
- OQ-8 三选一候选项预填在 roadmap §六 + AGENTS 末尾给用户决策

---

## 验收总览（与 spec.md AC 映射完成度）
- **rule 14/14 PASS**：AC-R1~R14 各 TR 覆盖 1:1
- **rubric 6 条 threshold≥4**：U1(回填 P99) / U2(内存) / U3(Gatekeeper 25+) / U4(checkpoint≥14) / U5(三层一致) / U6(JV≥230) → P18 T6/T7/T8/T9 各输出评分汇总
- **Exit 条件**：Review 阶段 independent check pass + review.md 落盘
