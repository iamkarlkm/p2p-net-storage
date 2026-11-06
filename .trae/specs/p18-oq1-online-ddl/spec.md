# P18 OQ1 Online DDL 全量 Rewrite 批量（Schema Evolution 可逆 Manifest）—— spec.md

**绝对日期：2026-09-28（零相对日期，禁今天/昨天/最近等词）**

---

## 1. 问题陈述（Problem / Users / Goals / Non-Goals）

### 1.1 问题是什么

p2p-db 存储引擎当前 Schema 演化能力存在 **5 类功能断层**（Step 1.1 探查 8 Gap 收敛为 5 类断层），导致生产环境加/删/改列必须停机、无法灰度平滑、无法可逆回滚：

| 断层编号 | 现状（探查实锤） | 业务影响 |
|---------|---------------|---------|
| F-1 | **ADD COLUMN 无 defaultValue 回填 batch 调度器**：注解 `@DsField.defaultValue` 实装 V1→V2 读 fallback，但存量百万行 V1 数据物理仍是 NULL，没有全表 batch 回填物理值的 DML 编排器；新列上建二级索引时 eqIndex 数据断裂（NPE / NULL key 哈希 0 撞桶） | 加列建索引 = 数据脏；大表回填业务手写 DML loop O(N²) 逐行 |
| F-2 | **DROP COLUMN 立即物理删无法回滚**：ColumnarStore.deleteColumnHard L618-L687 = while(true) 逐行扫 → bucketStore.remove / valueStore.recycle → markDeleted → 删 colMap 文件 → 清缓存；中途失败或需回滚数据层不可逆；无 tombstone 阶段 | 删列手抖 = 数据永久丢失；无法做"先软删观察 7 天再硬删"的运维安全栅 |
| F-3 | **RENAME COLUMN 完全 0% 实现**：ColumnRegistry colKey=`entity#name` 双向 byKey/byId 映射无 rename 原子 API；改名 = 删旧 + 加新，colId 不复用，历史 colId=5 的数据全部孤立，V1/V2 双代码灰度期新老 colKey 无法指向同 colId（alias 机制 0） | schema 改名 = 停机全量迁移；新老版本并行发布读同一列断裂 |
| F-4 | **ColumnRegistry markDeleted 单向不可逆（无 unDelete）**：markDeleted 写 tombstone 无反向 unDelete Backend 实现；ROLLBACK ADD COLUMN 场景新分配 colId 被标 deleted 后无法恢复，下次同 colKey getOrCreateColId 触发 byKey 冲突或分配新 id 历史引用失效 | 可逆迁移骨架缺关键反向 API，rollback 无法闭环 |
| F-5 | **DDL 中间态无 SHA256 Manifest 审计幂等（现有 onlineDDL/ 只写 4 个 leaf）**：RegistryMetaPaths L425-L455 只有 fromSchemaId / toSchemaId / startedAt / lastHeartbeat 4 leaf + TAG，manifest 结构 0（无 from/to signature、无 prev payload 细粒度 checksum、无 per-column diff、无 migrateId 历史归档、无单调 schemaVersion CAS 乐观锁） | 两次 DDL 覆盖写互相践踏；中间态崩溃重启无法断点续跑；无法做"dry-run 预检清单→人工确认→apply"的运维流程 |

### 1.2 用户 / 受益人

- **人同事（运维/DBA）**：加列/删列/改名不停机，支持 dry-run 预览、断点续跑、秒级回滚（恢复所有列定义指向，不丢数据）
- **下游项目 p2p-sync**：跨节点 DDL 同步（manifest 落 Registry 子树，P12 SYNC_ROOT 自动同步 manifest 子目录）
- **未来接手 AI**：P18 后加/删/改列标准流程化，不再手动调 ColumnarStore.deleteColumnHard 触发数据丢失

### 1.3 交付目标（Goals — 必须可验证）

1. **三操作全闭环**：ADD COLUMN / DROP COLUMN / RENAME COLUMN 三种 DDL 全量 rewrite 可编排，dry-run→apply→（失败）rollback 三阶段状态机完整
2. **可逆 0 数据丢失**：rollback 时从 manifest 恢复 100% 列定义指向，软删的列 100% 能 unDelete 恢复，硬删（purge 阶段）前永不碰物理数据
3. **零 Facade 新 public 签名**：NG-2 6 Facade（DsDatabaseLocal=48 / ColumnarStore=24 / TableMetaStore=3 / ColumnRegistry=8 / RowIdSeq=1 / EqIndexMeta=7）±4 容差，0 新增 public 方法
4. **SHA256 幂等对账**：每套 DDL 有唯一 migrateId + manifest，prev/to payload 均有 SHA256，重启断点续跑不重复执行步骤，重复 apply 幂等返回成功
5. **回填性能基线**：100 万行 ADD COLUMN + defaultValue 回填 batch=2048，P99 ≤ 60s / SOFT ≤ 120s；DROP COLUMN 软删 O(1) 不扫全表

### 1.4 Non-Goals（明确不做，防范围蔓延）

- ❌ **跨节点 2PC DDL 编排**：单机 Registry 内 manifest 落盘 + TAG 状态机完整；跨节点 Raft 2PC 是 OQ 后续阶段，不在 P18 范围内（可复用 P15 Raft CMD_PUT_REGISTRY 包装）
- ❌ **ALTER COLUMN 类型变更（int→long / length 扩容）**：同列名不同类型属于列内容 rewrite + 旧值 cast，复杂度超 OQ1 单项范围；P18 只处理 ADD/DROP/RENAME 三种 DDL，ALTER 留 OQ2
- ❌ **在线索引重建（CREATE INDEX/DROP INDEX）**：属于二级索引 DDL 专项（可复用 P16/P17 manifest 骨架），本阶段不碰
- ❌ **物理文件级真空（VACUUM / compaction 回收磁盘空间）**：DROP COLUMN 软删阶段只标 tombstone 不碰物理数据，purge 只调现有 deleteColumnHard；底层 page 级别 compaction 是 OQ3 专项
- ❌ **NOT NULL 约束强校验**：@DsField.nullable() 注解本次补齐持久化（ColumnDef 加 nullable 字段），但 DDL 阶段不跑存量值校验；NOT NULL 强校验属于 ALTER 专项

---

## 2. 硬约束（Hard Constraints / 延续 NG 铁律）

**所有约束属于 rule 类型，Gatekeeper c14 25 assertions 全 PASS 为前提。**

### 2.1 NG-2 六门面公共签名冻结（6 Facade ±4 容差）

| Facade 类 | 基线 public 方法数（2026-09-28 实锤） | 容差 | 违规判定 |
|----------|------------------------------------|------|---------|
| `DsDatabaseLocal` | 48 | ±4 | 45~52 合法，超出 FAIL |
| `ColumnarStore` | 24 | ±4 | 21~28 合法，超出 FAIL |
| `TableMetaStore` | 3 | ±4 | 0~7 合法，超出 FAIL |
| `ColumnRegistry` | 8 | ±4 | 5~12 合法，超出 FAIL |
| `RowIdSequenceStore` | 1 | ±4 | 0~5 合法，超出 FAIL |
| `EqIndexMetaStore` | 7 | ±4 | 4~11 合法，超出 FAIL |

- **允许的合规扩展方式**：新增 package-private / protected / private 内嵌类和方法；用 Java 反射（Class.forName + MethodHandle）从实现侧调内部 API 而不在 Facade 上公开签名；构造 `OnlineDdlCoordinator` 内嵌类放实现层，所有入口走 Facade 原有的 package-private hook 内嵌字段（类似 P15 `private final RaftServer raftServer` 模式）

### 2.2 NG-3 零侵入铁律（三 0 原则）

- **0 新增 Maven 依赖**：`mvn dependency:tree` 与 P17 基线 byte-for-byte 0 新 artifact
- **0 新增 P2PCommand enum**：31201-31303 区间仅保留 P15 Raft 6 条命令，P18 DDL 全走本地 Registry 路径，不新增跨节点命令
- **0 第三方通用集合类**：fastutil / eclipse-collections / HPPC / chroniclemap / commons-collections4 五探针 `Class.forName` 全抛 `ClassNotFoundException`，grep 源码 0 import 上述 5 包

### 2.3 NG-4 路径零外泄（延续 P10/P15/P16/P17 铁律）

- 第一级 `/orm/` 下禁止新增根目录；所有 DDL 相关结构必须落在 **`/orm/dbs/<space>/onlineDDL/<entity>/`** 的下级扩展（manifest/ / history/ / tombstones/），零新增第一级根
- `ONLINE_DDL_MANIFEST` 相关 常量字面量（包括但不限于 `ONLINE_DDL_`、`manifest`、`migrateId`、`schemaVersion`、`tombstone`）仅允许在 `RegistryMetaPaths.java` 内部声明，gatekeeper grep 实锤仅 1 files 命中；实现层字段名不得完整包含 `onlineDdlManifest` / `onlineDdl` / `schemaVersion` 完整字面量，必须缩写避泄露（如 `odManifest`、`schVer`、`tombCol`）

### 2.4 单向递增 / 幂等 / 原子铁律（DDL 数据安全栅）

- **colId 只增不回收**：RENAME 保持 colId 不变；DROP 只打 deleted 标记；任何场景 rollback 不会回收/复用已分配 colId（避免老数据 colId 碰撞）
- **DDL 全步骤 SHA256 对账**：每个原子步骤（写 manifest leaf / 更新 signature / 标 deleted / unDelete / 回填 batch N-M 行）都有 `stepId→sha256(prev, new)` 的 checkpoint，重启从 max stepId+1 续跑
- **物理数据永不提前删**：DROP COLUMN 在 manifest.phase=DONE + retentionMs 到期前，不得调用 ColumnarStore.deleteColumnHard 物理删；软删期间读路径自动 isDeleted 过滤

---

## 3. 功能需求（Functional Requirements — FR）

| 编号 | 功能 | 关键子步骤 |
|------|------|-----------|
| FR-1 | **Manifest 结构 + 单调 CAS 版本**（Gap-1/Gap-4/Gap-8 补） | 1. RegistryMetaPaths 加 manifest 子树 12 个 helper + history/nextMigrateId/schVer 路径<br>2. ColumnRegistry Backend 加 unDelete() 反向 + findColId 原子 2 步<br>3. manifest.phase 6 状态机：IDLE→PREPARING→DRY_RUN→APPLY→DONE→ROLLBACK |
| FR-2 | **ADD COLUMN + defaultValue 回填 batch 调度器**（F-1 补） | 1. PREPARING：TableMeta.diff(old,new) 产出 newCols 清单 → 写 manifest.newColIds / fromTableMetaSha256 / toTableMetaSha256<br>2. DRY_RUN：列出所有受影响行 id 范围 + 行数统计 + 回填值预览，0 写盘<br>3. APPLY：getOrCreateColId 分配新 id → ensureMeta save 新 signature → 按 rowId 升序 batch=2048 扫行存 → putValue 填 defaultValue → checkpoint sha256 → 更新 progress<br>4. 幂等：每个 batch 完成后写 checkpoint，重启跳过已完成 stepId |
| FR-3 | **DROP COLUMN 软删 → 延迟硬删两阶段 tombstone**（F-2/Gap-3 补） | 1. PREPARING：diff 产 droppedCols 清单 → 写 manifest.droppedColIds + tombstone 路径预期<br>2. APPLY：**① 软删 O(1)** 写 `deleted/<colId>=1` + `tombstones/<colId>/{markedAt,retentionMs,size}` → 清 TableMetaCache；**② 延迟硬删 purge** 另起 API（显式调用或独立调度器）：manifest.phase=DONE && now-startedAt>retentionMs 时才调 deleteColumnHard<br>3. ROLLBACK：按 manifest.droppedColIds 逐个 unDelete → signature 回滚 from |
| FR-4 | **RENAME COLUMN colId 不变原子双向映射 + alias 灰度双读**（F-3/G-R1/G-R2/G-R3/G-R4 补） | 1. PREPARING：renamePairs = [(old, new)] → 校验 new 不存在 / old 存在 / colId 有效 → 写 manifest.renamedFromTo<br>2. APPLY：ColumnRegistry Backend 原子 `renameColKey(entity, oldColKey, newColKey)` 实现：<br>   a. fileLock.lock() → b. read colId = byKey.old → c. remove byKey.old → d. put byKey.new=colId → e. put byId.colId=newColKey → f. YAML 同步改双向映射 → g. saveMeta / unlock<br>3. alias 双读（灰度期可选）：manifest.alias=true 时，读路径 findColId(old) 命中但 deleted=false 时同时查 findColId(new)，任一存在返回 colId（灰度期新老代码同 colId 共享）<br>4. ROLLBACK：manifest.renamedFromTo 反向 swap（new↔old）按同原子流程重做 |
| FR-5 | **OnlineDdlCoordinator 内嵌类（0 Facade 新签名）**（NG-2 合规模板） | 1. 在 DsDatabaseLocal 内部加 `private final OnlineDdlCoordinator odCoordinator`（构造器初始化，IOException 降级 WARN null）<br>2. 暴露的 6 个 API（prepare/apply/purge/cancel/rollback/status）全是 Coordinator 内部 package-private 方法，Facade public 方法签名 0 改<br>3. NG-2 合规：通过 DsDatabaseLocal 已有的 package-private hook 入口访问，无需新增 public |
| FR-6 | **ColumnDef 补齐 nullable 持久化（P10 遗留 @DsField.nullable 半实装）** | 1. TableMeta.ColumnDef 加 `boolean nullable` 字段（默认 true，兼容旧 signature 读时补 true）<br>2. @DsField.nullable() 值写入 build() 时同步写入 ColumnDef<br>3. computeSignature 追加 nullable 位段参与哈希 |

---

## 4. 非功能需求（Non-Functional Requirements — NFR）

| 编号 | 维度 | 目标 | 观测方式 |
|------|------|------|---------|
| NFR-1 | **可逆正确性（rule）** | ADD/DROP/RENAME 三类 DDL 各跑 100 次 apply→rollback→apply 循环：最终 TableMeta.signature ≡ 起点 signature × 100%；数据行（随机抽 1%）值 ≡ 起点值 × 100% | P18 T4 ReplayLoopTest |
| NFR-2 | **回填性能（rubric 5 级）** | ADD COLUMN 1M 行 × defaultValue=long/String 混合 × batch=2048：<br>• P99 ≤ 30s → 5 分（HARD）<br>• P99 ≤ 60s → 4 分（SOFT）<br>• P99 ≤ 120s → 3 分<br>• P99 ≤ 300s → 2 分<br>• >300s → 0 分（FAIL）<br>验收阈值：score ≥ 4（SOFT 达标） | P18 T7 U1 PerfRubric 1M×20 rounds |
| NFR-3 | **软删 O(1)（rule）** | DROP COLUMN soft-delete N=1M 行耗时 ≤ 50ms（不扫数据，只写 3 个 leaf） | P18 T3 t01 计时断言 |
| NFR-4 | **测试量门槛（rule）** | P10~P18 合跑 ≥ 230 tests 0F0E（P17 基线 203 + P18 ≥ 27，≥ 230）；P15 仍拆 3 批隔离 PEERS 静态污染 | P18 T8 JV 合跑 |
| NFR-5 | **幂等性（rule）** | 同一 manifestId 重复调用 apply 20 次 → 终态数据一致 100%，中间态检查点 stepId 不重复累加，进度值最终稳定在 100% | P18 T5 IdempotentTest 20× repeat |
| NFR-6 | **NG 合规性（rule）** | c14 Gatekeeper 25/25 assertions PASS（A6/B6/C5/D5/E3 五组全绿） | P18 T6 DdlGatekeeperTest |
| NFR-7 | **RENAME 原子性（rule）** | 并发 20 线程同时对同一张表不同列做 rename，无文件锁死；每列 colId 最终唯一且稳定；byKey/byId 双向映射 1:1 无孤儿无冲突 | P18 T4 t02 RenameConcurrentTest |

---

## 5. 接受准则（Acceptance Criteria — 仅 rule / rubric 两类）

### 5.1 rule 类（客观二进制，可观测 PASS/FAIL，共 14 条）

| 编号 | 准则 | 证据来源 / 观测方式 |
|------|------|------------------|
| AC-R1 | **NG-2 六门面 public 签名冻结**：DsDatabaseLocal=48 / ColumnarStore=24 / TableMetaStore=3 / ColumnRegistry=8 / RowIdSeq=1 / EqIndexMeta=7，每个 ±4 内 | c14 Gatekeeper A6/B6 反射计数，P18 T6 PASS |
| AC-R2 | **NG-3 零侵入**：0 新 Maven 依赖 / 0 新 P2PCommand / 5 通用集合探针 ClassNotFound 全抛 | c14 D5 5 probes + diff `mvn dependency:tree` P17 vs P18，T6 PASS |
| AC-R3 | **NG-4 零外泄**：`ONLINE_DDL_` / `manifest` / `schemaVersion` / `tombstone` 字面量仅 RegistryMetaPaths.java 1 files 命中；其余实现层使用缩写（odManifest / schVer / tombCol） | c14 C5 grep regex 实锤，T1 t03 zeroLeak PASS |
| AC-R4 | **Manifest 路径 NG-4 合规**：所有 manifest / history / tombstones 路径均以 `/orm/dbs/<space>/onlineDDL/<entity>/` 为前缀，不新增 /orm/ 第一级根目录 | T1 t01 反射枚举 RegistryMetaPaths 常量 + 路径段拼接检查 10 项 PASS |
| AC-R5 | **Manifest 12 个 helper + 10 个 IAE**：RegistryMetaPaths 提供 manifestDir / manifestPhase / manifestMigrateId / manifestFromSig / manifestToSig / manifestProgress / manifestHistoryDir / manifestCurrentMigrateId / nextMigrateId / schemaVersion / tombDir / tombCol 共 12 helper；id<0 / colId≤0 / entity 空串 抛 IllegalArgumentException ≥ 10 项 | T1 t02 反射 IAE 10/10 触发 PASS |
| AC-R6 | **ADD COLUMN dry-run 零写盘**：dryRun=true 时 Registry / 列存 / 行存 三端写操作 0；manifest.phase=DRY_RUN 仅产出 stats + preview | T2 t01 dryRun 前后 snapshot Registry.dump SHA256 相等 PASS |
| AC-R7 | **ADD COLUMN 回填 batch=2048 checkpoint 断点续跑**：在 batch 1/2 中间 kill Coordinator 新实例重启后，从 max(stepId)+1 续跑，最终 1M 行 100% 填值无重复无遗漏 | T2 t03 中途 kill 模拟（Thread.stop → 新 Coordinator 构造）→ 续跑 PASS |
| AC-R8 | **DROP COLUMN 软删 O(1) ≤ 50ms + rollback unDelete 恢复 100%**：N=1M 行软删计时 ≤50ms；rollback 后 isDeleted(colId)=false；随机抽 1000 行列值读取 100% 保留未丢 | T3 t01/t03 PASS |
| AC-R9 | **DROP COLUMN purge 调用条件 = DONE + retentionMs 到期**：非 DONE 或 retentionMs 未到，调 purge() 直接 return（抛 IAE），零调用 deleteColumnHard | T3 t02 守卫断言 5 条 PASS |
| AC-R10 | **RENAME colId 不变 + byKey/byId 双向 1:1**：rename 前 colId = rename 后 colId；旧 byKey.old 读 null，新 byKey.new=colId，byId.colId=newColKey 全匹配；并发 20 线程 rename 20 列最终 20/20 无孤儿 | T4 t01/t02 PASS |
| AC-R11 | **RENAME ROLLBACK 反向 swap 正确**：apply→rollback 两轮后，初始 colKey 映射 100% 还原，V1 代码读旧列名无报错 | T4 t03 双轮 apply↔rollback PASS |
| AC-R12 | **幂等 20× 终态一致**：同一 prepare 产出的 manifest，apply 调 20 次 → stepId 最终稳定在 maxStep；20 次 progress 差值=0；TableMeta.signature 每次返回值字节相等 | T5 t01 20 repeat PASS |
| AC-R13 | **NFR-1 100 次 Replay Loop 0 差**：ADD+DROP+RENAME 三种 DDL 组合 apply→rollback 循环 100 次；起点 TableMeta SHA256 = 终点 TableMeta SHA256；随机抽 1% 行读值字节相等 | T4 t04 replay 100 rounds PASS |
| AC-R14 | **ColumnDef.nullable 持久化正确**：新 @DsField(nullable=false) 的列 build 后 ColumnDef.nullable=false；computeSignature 参与哈希，nullable 由 true→false signature 变更；旧 TableMeta（迁移前）读回 nullable 默认 true（向后兼容） | T2 t05 nullable 读写 PASS |

### 5.2 rubric 类（评估分级 0-5，共 6 条，验收阈值 threshold≥4）

| 编号 | 维度 | 0 分 | 3 分 | 4 分（SOFT 阈值） | 5 分（HARD 阈值） | 证据来源 |
|------|------|------|------|------------------|------------------|---------|
| AC-U1 | **ADD COLUMN 1M 行回填 P99** | >300s | ≤120s | ≤60s | ≤30s | T7 U1 PerfRubric N=1M×20 rounds |
| AC-U2 | **APPLY 全流程内存峰值**（vs P17 基线 256MB） | >2GB | ≤1GB | ≤512MB | ≤384MB | T7 U2 VisualVM / Runtime.maxMemory 快照 |
| AC-U3 | **c14 Gatekeeper 断言密度** | <20 assertions | ≥23 assertions | ≥25 assertions (A6+B6+C5+D5+E3=25 标配) | ≥28 assertions (扩展 E3 额外 3 项) | T6 DdlGatekeeperTest assertion counter |
| AC-U4 | **Manifest 细粒度检查点完整性** | <6 项 checkpoint | ≥10 项 checkpoint | ≥14 项 checkpoint（prepare/分配 colId/每个 batch/改 signature/alias 切换/DONE…） | ≥18 项 checkpoint（含 rollback 每步 checksum） | T2 t04 检查 manifest 子树 leaf 数量 |
| AC-U5 | **NeatFreak 三层文档一致性**（project_memory / AGENTS / roadmap） | 缺 2 层以上 / 含相对日期 | 三层有更新但缺 ≤3 条 Invariants | 三层同步完整 + 12 Invariants 全覆盖 + 0 相对日期 | 三层 + 额外 history 归档路径索引 + OQ8 三选一预填 | T9 NeatFreak 输出 |
| AC-U6 | **JV 测试总量（P10~P18）** | <200 tests | ≥210 tests | ≥230 tests (HARD 门槛) | ≥245 tests (超门槛 15+) | T8 Surefire report 合计 |

**验收阈值**：6 rubric 每条评分 ≥ 4（SOFT 达标），至少 2 条 ≥ 5（HARD 达标）。14 rule 100% PASS。

---

## 6. 上下文 / 依赖 / 开放问题

### 6.1 前置依赖

- ✅ P10 RegistryMetaPaths SHA256 可逆骨架（DsDatabaseMetaMigrator migrate/rollback）可复用 ~80%
- ✅ P15 在线状态机 TAG 模式（TAG_SCHEMA_MIGRATING / CANCELLED / ROLLBACK）已存在常量，可直接复用
- ✅ P16/P17 ColumnarStore.deleteColumnHard 硬删实现存在，DROP COLUMN purge 阶段直接调
- ✅ DsDatabaseLocal L3324-L3400 已有 cancelOnlineDDL / forceRollbackSchema / writeOnlineDdlState 骨架（注释写"未来可拓展"），本次补实装
- ✅ @DsField.defaultValue() / nullable() 注解存在（defaultValue 实装，nullable 半实装需补持久化）

### 6.2 开放问题（非阻塞，T1~T3 阶段可定）

- **Q1：alias 灰度期默认长度？** 暂定 7 天（retentionMs=604800000），tombstone purge 同值；可通过 manifest.prepare 参数覆盖，不阻塞
- **Q2：history 归档保留几份？** 暂定 20 份（nextMigrateId 满 20 份删最旧一份）；避免 Registry 存储膨胀
- **Q3：batch 默认值？** 暂定 2048（与 P13 BulkImportPerf 基线对齐），可通过 Coordinator 构造参数调，不阻塞

### 6.3 版本对照（2026-09-28 absolute）

- P15 Raft 基线：6 Facade=48/24/3/8/1/7，c11 Gatekeeper 25/25
- P16 Range V2：6 Facade 同，c12 25/25，192 tests ≥160
- P17 Composite V3：6 Facade 同，compRangeIndexMaps 改避 NG-4 命中，c13 25/25，203 tests ≥200
- **P18 Online DDL（本 spec）目标**：6 Facade 同 ±4，c14 25/25，≥ 230 tests，14 rule + 6 rubric threshold≥4 全达标
