# P10 DsRegistry 统一 ORM 元数据（行存+列存）- Product Requirements Document

## Overview
- **Summary**：用通用注册表 DsRegistry（已交付 P6a/P6b/P7/P8/P9 五层正确性）作为 **DsDatabase 行存 + DsColumnar 列存** 两类数据库引擎的 **单一 authoritative metadata store**，统一替换当前 6+ 类散落在 `indexes/` 目录下、各实现独立 `YAML + FileLock (.lock)` 机制的元数据存盘文件，以及补齐行存侧现有 `DsRegistryOrmBridge` 只覆盖 schema 但未覆盖 ClassIdSet 位置、IndexStore 实例配置、Online DDL 状态的 3 类缺口。
- **Purpose**：对齐用户最初设计意图——"通用注册表就是用来存数据库元数据的"。终结当前 6 套元数据各自 YAML 解析 + Windows FileLock 锁冲突 + 跨表/跨引擎元数据格式不统一 + 散文件迁移/备份/一致性校验难的问题；为 P11 跨节点 ORM 元数据同步（Tgt-2 ORM Handler 扩展 metadata 路由）提供天然 DsRegistry 节点树基础能力，不用再二次编解码 YAML。
- **Target Users**：① 数据库作者/维护者（改 schema/索引/列存不用维护 6 套格式）；② 跨节点 P2P 元数据同步 Handler；③ DsFullBackup/DsTimelineRestore（备份/恢复时只处理 DsRegistry 树路径 + row 数据 2 类）。

## Goals
1. 统一：行存/列存 **7 类元数据** 100% 只存在 DsRegistry 路径树（含 Phase2 V2 `countAttrs=25` 扩展节点），老 YAML/散配置文件 0 残留（新库纯 Registry，老库 lazy 双读后迁移工具一键删除）。
2. 接口稳定：列存 4 个 MetaStore（TableMeta/EqIndexMeta/RowIdSeq/ColumnRegistry）**公开方法签名 0 改**；行存 DsDatabaseLocal `createMeta/resolveEqIndex/idSetForClass` 公开方法签名 0 改；DsRegistryOrmBridge 公开方法签名 0 改。
3. 零侵入可回退：lazy 双读模式下 DsDatabase DsColumnar 不出现任何数据丢失；一旦 Registry 模式启用失败（元数据不一致/权限问题），一行配置回退到 YAML mode 功能 100% 等价。
4. 性能不退化：热路径元数据 get（列存 allocate rowId、行存 `idSetForClass`、列存 `getOrCreateColId`）p99 延迟 ≤ 原 YAML 模式（Registry 节点缓存 + DsTypeFixedBucketStore 分桶 16B 小读写天然比 YAML+FileLock 快，仅需 verify ≤ 原 p99）。
5. 验收门槛：烟测 ≥103 PASS 零回归；新增 P10 专项 12@Test 0F0E；老 P7 Registry19 专项 + P8/P9 4+4 竞态 + 行存/列存基线 18tests 全部 0F0E。

## Non-Goals（明确不在本期范围）
1. **不改 row 数据存盘**：行存 DsFixedBucketStore row 桶、列存 ColumnarStore 列数据文件、EqIndexStore 索引数据、DsHashSet ids.set bitmap **内容与位置完全不动**；本项目只改"元数据"（schema/列 id 分配/rowid seq/index meta 指针/online ddl 状态/id set 文件位置指针）的 **描述与指向**，不碰实际数据。
2. **不引入 DsRegistry 事务/多 key 原子性框架**：Registry 已具备单 key putList/putString 原子性；元数据需要"跨 key 一致"的场景（TableMeta + ColumnRegistry 分配 colId 成对写入）沿用原有 DsTypeFixedBucketStore Windows FileLock 等价的 Registry DirNode 锁；**不新增 distributed tx 协议**。
3. **不重构写路径**：MVCC 列路由、binlog 并行 replay、EqIndex putIndex/removeIndex 逻辑 0 改；只把 `createMeta` 里读取 schema 的 source 从反射+散 YAML 换成 Registry 路径树。
4. **不修改 DsRegistry facade 公开方法**（NG-3 铁律）：DsRegistry/DsRegistryNodeSerializer/7 大底层公开 API 0 字符改。Registry 缺能力时内部用 ExtAttrs（Phase1 已交付）+ TAG（P7 Tgt5 已交付）+ DsRegistryOrmBridge 私有 helper 封装，绝不回改 DsRegistry 公开签名。
5. **不处理跨节点元数据同步实现**：P10 只做单机单进程内 DsDatabase/DsColumnar 统一元数据后端；跨节点同步留 P11。

## Background & Context（双向探索证据）

### A. 现状：DsDatabase/DsColumnar 元数据 7 类散落（6 套 YAML + 1 套文件名约定）
| # | 元数据类别 | 存盘位置 | 格式 | 并发控制 | 实现文件 | 覆盖情况 |
|---|---|---|---|---|---|---|
| 1 | 行存 ORM Schema（列/composite/复合索引/rowLength/schemaId/className）| DsRegistry（`/<root>/<schemaId>/<ClassName>/*` 16 个 leaf）| Registry DataType（REG_SZ/REG_DWORD/REG_MULTI_SZ）| DsTypeFixedBucketStore striped locks（P9 已交付）| DsRegistryOrmBridge.java L15-247 | ✅ 已落地（本次不重写，仅补缺口） |
| 2 | 行存 ClassIdSet 位置指针（ids.set vs ids_{schemaId}.set 是否 legacy）| `indexes/<spacePath>/ids.set` / `ids_{schemaId}.set`（文件名约定）| 无独立 meta，legacy 位藏在 `EntityIndexUtil.IndexDef.legacy` **内存布尔** | 无（读时按文件是否存在判断）| EntityIndexUtil.java L11-26 / DsDatabaseLocal.java L1150-1154 | ❌ 未进 Registry：散文件约定 + 内存判定，重启后只能看文件存在，丢失"为什么 legacy"上下文 |
| 3 | 行存 EqIndex/CompositeIndex 实例配置（index 子目录路径 / schemaId / MVCC 开关 / valueKind）| **无独立 meta 文件**，字符串拼 `indexes/<safeSpace>/<schemaId>/eq_<safeCol>`，参数藏在 DsDatabaseLocal L976-1004 代码常量里 | 无（indexCache 内存 Map 防重创建）| DsDatabaseLocal.java L976-L1004 / resolveEqIndex / resolveCompositeIndex | ❌ 未进 Registry：散代码 + 散目录命名约定，改 index 布局时全项目搜字符串 |
| 4 | 列存 TableMeta（列/compositeGroup/signature）| `indexes/<spacePath>/table.meta.yaml` | YAML（snakeyaml）| Windows `FileLock`（独立 `table.meta.lock` 文件）| TableMetaStore.java L37-176 | ❌ 未进 Registry：独立 YAML+lock，不兼容 Registry 跨节点天然同步树 |
| 5 | 列存 EqIndexMeta（entityClass/logicalName/colId 三绑定）| `indexes/<spacePath>/idx/<logicalName>.eq.idx.yaml` | YAML + 文件 .lock | 同上 Windows FileLock | EqIndexMetaStore.java L20-146 | ❌ 同上 |
| 6 | 列存 RowIdSequence（nextRowId 单调递增）| `indexes/<spacePath>/rowid.meta.yaml` | YAML + FileLock | 同上 Windows FileLock | RowIdSequenceStore.java L18-87 | ❌ 同上（性能最关键热路径，每次 insert 读+写 YAML）|
| 7 | 列存 ColumnRegistry（colKey→colId 双向 map / deleted 集合 / nextColId 单调）| `indexes/<spacePath>/columns.meta.yaml` | YAML + FileLock | 同上 Windows FileLock | ColumnRegistry.java L23-200 | ❌ 同上（每次 ensureMeta 对每个 DsField 读+写 YAML，N 字段跑 N 次）|

### B. DsRegistry 可用能力清单（100% 已交付，本期直接复用零新 API）
1. **路径树 5 种 DataType**（已实测 5 类）：REG_SZ（字符串）/REG_DWORD（long）/REG_MULTI_SZ（列名列表）/REG_BINARY（字节数组，ids.set 大 bitmap 可选）/REG_QWORD（8 字节大整数，rowId/colId seq）→ `DsRegistry.putString/putLong/putBytes/putList` 对应，可承载 7 类元数据全部字段。
2. **节点属性（Phase2 V2 countAttrs 8-bit max255）**：Registry 目录节点本身自带 `/@dbmeta/attrs.bin`，可存 TableMeta signature、schemaId 映射等不适合当 leaf value 的节点属性，避免额外 leaf。
3. **TAG（P7 Tgt5 已交付）**：`addTag/hasTag/removeTag` 天然适配 online DDL state（例如 `"SCHEMA_MIGRATING"` / `"COLUMN_DELETED"` / `"INDEX_BUILDING"`），比 YAML enum 字符串 + 类型转换更可靠。
4. **ExtAttrs（P7 Exts 已交付）**：25 条 DbMetaAttr enum 中未被 Registry 自身占用的 6 条（`SCHEMA_SIG`/`COLUMN_COUNT`/`INDEX_COUNT`/`ROW_COUNT_EST`/`ONLINE_DDL_STEP`/`MIGRATION_SOURCE`）可直接挂在 Table 节点上做跨节点同步 hint。
5. **并发原子性（P8/P9 已交付 striped locks）**：同一 key putString/putLong 原子性已有 refCount CAS 保证（P9 竞态 4/0F0E）；跨 key 顺序一致性用 `mkdirs` + `putLong(seq)` 顺序语义，与原 YAML + FileLock 严格一致。

### C. 已验证基础：DsRegistryOrmBridge 已具备 Schema 双向读写能力
- **写路径** [registerEntity](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistryOrmBridge.java#L24-L108)：`mkdirs` 建 `/<root>/<schemaId>/<ClassName>/{columns,composites,compositeIndexes}` 三目录 + 每列 7 个 leaf（type/length/indexed/mvcc/precision/scale/default）+ 复合列组 2 叶 + 复合索引 1 叶 REG_MULTI_SZ + 根 3 叶（schemaId/rowLength/className）= **单向映射已正确**。
- **读路径** [loadSingleSchemaDef](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistryOrmBridge.java#L172-L234)：反向重建 SchemaDef 完全对称。
- 仅缺 **列存 4 类 + 行存 3 类指针** 的 7 个子树扩展。

## Functional Requirements
- **FR-1（行存 ClassIdSet 指针登记 Registry）**：`EntityIndexUtil.indexOf(dbRoot, clazz)` 写路径时，在 Registry `/orm/row/<entityClass>/idSet/` 下登记 3 个 leaf：`legacySchemaId`（老 schema 或 NULL）、`idsFileAbsolutePath`（实际 ids.set/ids_{schemaId}.set 的绝对路径）、`createdAtEpochMs`（创建时间）；读路径 Registry 有值优先取 Registry，无值 lazy 扫描磁盘（兼容老库）并写回 Registry。
- **FR-2（行存 Index 实例配置登记 Registry）**：`DsDatabaseLocal.resolveEqIndex/resolveCompositeIndex` 首次创建 index store 时，在 `/orm/row/<entityClass>/indexes/<indexName>/` 下登记 5 个 leaf：`kind`（eq/composite）/`schemaId`/`mvcc`（0/1）/`valueKind`（0=LONG/1=STRING）/`dirAbsolutePath`；读路径 cache miss 时优先 Registry 取，无值懒写回。
- **FR-3（列存 TableMeta 替换 YAML→Registry）**：`TableMetaStore.java` 公开方法（getMeta/ensureMeta/2 种 build）**签名不变**，YAML 读 改成 从 `/orm/column/<entityClass>/table/` 下读节点（含 columns/compositeGroups/signature 三子树通过 ExtAttrs 存），写入保持幂等（signature 不变 no-op）；老 YAML 文件存在时 lazy 双读 迁移 一次（读 YAML→写 Registry→rename .yaml→.yaml.migrated），双写 N>=1 runs 稳定后后续启动只读 Registry。
- **FR-4（列存 ColumnRegistry 替换 YAML→Registry）**：`ColumnRegistry.getOrCreateColId/findColId/markDeleted/isDeleted` 4 个公开方法签名 0 改，原 `columns.meta.yaml` 映射到 `/orm/column/<entityClass>/columns/` 下：`nextColId`（REG_QWORD 单 leaf）+ `byKey/<colKey>`（colId leaf）+ `byId/<colId>`（colKey leaf）+ `deleted/<colId>`（REG_DWORD=1 存在即 deleted，不存不存在）；保证"colId 只增不回收"与原 YAML 语义 100% 一致。
- **FR-5（列存 RowIdSequence 替换 YAML→Registry）**：`RowIdSequenceStore.allocate(entityClass)` 公开方法 0 改，原 `rowid.meta.yaml` 单字段 `nextRowId` 映射到 `/orm/column/<entityClass>/seq/nextRowId`（REG_QWORD 8 字节原子 getAndIncrement，P9 striped locks 已保证原子 ++，比 YAML 读-改-写 + FileLock 少 2 次磁盘 I/O）。
- **FR-6（列存 EqIndexMeta 替换 YAML→Registry）**：`EqIndexMetaStore.exists/get/list/put/delete/mapFile` 6 个公开方法签名 0 改，原 `<logicalName>.eq.idx.yaml` 三字段（entityClass/logicalName/colId）映射到 `/orm/column/<entityClass>/indexes/eq/<logicalName>/colId`（单 REG_QWORD leaf），colId 存在即 exists。
- **FR-7（Online DDL State Registry 登记 + 统一开关）**：在 DsDatabase 根节点 `/orm/dbs/<dbSpaceName>/onlineDDL/<entityClass>/` 用 TAG 标记状态（SCHEMA_MIGRATING / APPLYING / ROLLBACK / DONE）+ 4 个 leaf（`fromSchemaId`/`toSchemaId`/`startedAt`/`lastHeartbeat`），对应 `DsDatabaseLocal.forceApplyOnlineDDL/cancelOnlineDDL/forceRollbackSchema` 三个入口（原只清 tableMetaCache，现在同步写 Registry state）。
- **FR-8（双模式切换开关 + 一键迁移工具）**：新增 JVM 启动属性 `p2p.orm.meta.mode=registry | yaml | hybrid`（默认 hybrid：新库直接 registry，老库双读 迁移后 registry）；迁移工具 `DsDatabaseMetaMigrator.migrateAll(dbRoot)` 遍历所有 `<spacePath>` 把 YAML/散文件 100% 写进 Registry 并 `.migrated` rename；回滚工具 `rollbackAll(dbRoot)` 删除 Registry orm 子树并 rename `.migrated` 回 `.yaml`。

## Non-Functional Requirements
- **NFR-1（热路径性能）**：列存 `allocate(rowId)` / `getOrCreateColId(colKey)` / 行存 `resolveEqIndex` 三个热路径 **new Registry backend 的 p99 延迟 ≤ 原 YAML backend 的 p99 + 5%**（因为 Registry 走 DsTypeFixedBucketStore 分桶 8B 小对象随机读写，天然比 YAML parse + FileLock 快，阈值放宽到 +5% 是为防 Windows 下偶发 PageCache miss）。
- **NFR-2（零回归）**：P7 原 19 专项（Exts12+Tgt57+Phase16+Phase2V27=32tests）+ P8/P9 4+4=8 竞态 + 行存基线（BlockStore3+RefCount2+DsData1=6）+ 列存基线（如果存在的 N 个）≥ 46tests 全部 0F0E；烟测累计 PASS ≥ 103。
- **NFR-3（零侵入回退）**：`-Dp2p.orm.meta.mode=yaml` 时，TableMetaStore/EqIndexMetaStore/RowIdSequenceStore/ColumnRegistry **100% 回到原 YAML 实现**（同一 JVM 实例重启切换可验证）；0 行元数据因切换丢失。
- **NFR-4（Windows 兼容性）**：Registry 后端不能出现任何 `channel.lock + 读同一文件` 死锁。DsRegistry 原有 striped locks（P9 已交付）完全走 DsTypeFixedBucketStore/DsData refCount CAS，不含原生 FileChannel.lock，天然规避。
- **NFR-5（原子性）**：对"跨 key 一致性最关键"的两对写入（① ensureMeta 分配 colId + 写 TableMeta.columns；② allocate 写 nextRowId++）必须保持原子性：要么全写要么全不写。用 Registry 同一目录节点的 per-dir striped lock（复用 DsRegistryOrmBridge 已有的 per-entityClass 路径锁语义）保证顺序，与原 YAML `table.meta.lock`/`columns.meta.lock` 范围一致。
- **NFR-6（内存占用）**：DsRegistryOrmBridge 新增的 7 类元数据缓存（每个 entity 3 个 leaf × N columns）单表总新增常驻内存 ≤ 原 YAML 模式的 `snakeyaml Yaml 实例 + LinkedHashMap<colKey→colId> 常驻副本 + 锁文件句柄表` 的 60%（Registry 走 DsData refCount，masterIndex 本来就缓存 key，预期 30%~50%）。

## Constraints
- **Technical（铁律 NG-0 ~ NG-3 继承 P9）**：
  - NG-3：DsRegistry.java / DsTypeFixedBucketStore / DsFixedBucketStore / DsFreeRing / DsHashMap / DsData / DsTieredMasterIndex / MultiTypeDataId **8 大底层公开 API 0 字符改**（缺能力一律在 OrmBridge/RegistryMetaStore 里 private helper 封装）。
  - NG-2：行存 `DsDatabaseLocal` / `EntitySchemaUtil` / `EntityIndexUtil` / `EqIndexStore` 公开方法 0 改；列存 `TableMetaStore` / `EqIndexMetaStore` / `RowIdSequenceStore` / `ColumnRegistry` 公开方法 0 改（仅 backend 换实现）。
  - NG-1：Performance First，锁内零 I/O 约束继续（Registry 内部 striped locks 已经保证，上层不允许引入全局 synchronized(this)）。
  - NG-0：正确性优先。任何一处修改导致 P9 4竞态 FAIL → 立即 revert 该修改，不进入下一轮。
- **Business**：用户明确说"通用注册表就是用来存数据库元数据的" → 只允许收敛，不允许新增独立 YAML 元数据类型；未来所有新增 ORM 元数据（例如 partial index、function index）必须 100% 落在 Registry。
- **Dependencies**：依赖 P9 DsData 三处修复（CAS loser 清 / tombstone 清 masterIndex / attempt=64）已 merge（当前 repo state 已 VERIFIED），否则 P10 的 REG_DWORD/REG_QWORD 小 key 高频 put 会再现 16T 并发 loser 泄漏；依赖 Phase2 V2 countAttrs=25 已交付（DbMetaAttr 25 条 enum 已验证）。

## Assumptions
1. 单机单进程内 DsDatabase/DsColumnar **只有一个 DsRegistry 实例**（通过 DsRegistry 同 rootDir 单例复用，和当前 DsRegistryOrmBridge 构造参数一致）；多进程并发修改同一 dbRoot 不在本期范围（原 YAML 模式也没支持过，跨进程靠 DsRegistry 跨节点 Handler 留 P11）。
2. DsColumnar 的 4 个 MetaStore 目前仅被 DsColumnarStore / DsColumnarTable 调用（Grep 确认 EqIndexMetaStore.list/put/delete 只有 2~3 个调用点），改 backend 不会触发 3rd-party 插件。
3. snakeyaml 依赖后续可以移除（非本期必须），但本期要保证 YAML→Registry lazy 迁移时 YAML 解析逻辑与原 ensureMeta 完全一致（可以静态 delegate 给原 build/load 方法）。
4. DsRegistry 启动 warm-up 时 `/orm/` 子目录首次加载在 500ms 内完成（原 DsRegistry 10K 节点启动验证过，<100ms）。

## Acceptance Criteria

### AC-1：行存 ClassIdSet 指针 Registry 一致性
- **Type**: `rule`
- **Given**: 一个全新空 dbRoot，注册 2 个 ORM class（1 个 legacy ids.set 模拟 + 1 个新 schemaId 名 ids_{sha}.set）
- **When**: 调用两次 `EntityIndexUtil.indexOf` + 一次重启 JVM（冷启动）
- **Then**: 重启后从 Registry `/orm/row/<entityClass>/idSet/idsFileAbsolutePath` 读出的路径 = 冷启动前写入的一致路径；legacy 位与原 `IndexDef.legacy` 布尔值一致
- **Pass Condition**: 断言两处相等，无歧义
- **Evidence**: P10 专项 `DsDatabase_P10_RowMetaRegistryTest` T01 的 assertTrue 输出

### AC-2：行存 Index 实例配置 Registry 一致性
- **Type**: `rule`
- **Given**: 一个带 3 个 @Indexed 列 + 1 个 compositeIndex 的 ORM class，首次 `metaOf(clazz)` + 1000 次 putRow（触发 index store 创建）
- **When**: 清 `indexCache` 内存 + 重启 JVM 后再次 metaOf 并 resolveEqIndex 三次
- **Then**: Registry `/orm/row/<entityClass>/indexes/*/valueKind` / `mvcc` / `schemaId` 5 leaf 与实际创建的 store 类型一致；第二次 resolve 不需要重新 scan 字符串拼路径（`indexCache.get()` 命中优先）
- **Pass Condition**: 0 次字符串路径扫描 fall-through 到代码约定；所有 leaf 值与预期一致
- **Evidence**: T02 Tracing 通过拦截 `DsPathUtil.toSafeFileName` 调用次数 = 0

### AC-3：列存 TableMeta Registry Backend 幂等等价
- **Type**: `rule`
- **Given**: 一个带 10 个 @DsField + 2 个 @CompositeField 的 ORM class，原 YAML backend 已 ensureMeta 生成 table.meta.yaml
- **When**: 设置 mode=hybrid → 再次 ensureMeta → 重启 JVM，mode=registry → 调用 getMeta(entityClassName)
- **Then**: signature.equals(fresh signature) 为 true；所有 columns 的 colId/javaType/length/precision/scale 与 YAML backend 同一实例 ensureMeta 输出 byte-for-byte 相等（用 Java deepEquals 比 TableMeta 全部字段）
- **Pass Condition**: deepEquals=TRUE，colId 分配完全一致（同 colKey→同 colId，跨重启）
- **Evidence**: T03 deepEquals 断言 + colId 跨重启比对表

### AC-4：列存 ColumnRegistry 只增不回收不变式
- **Type**: `rule`
- **Given**: 20 线程并发 T=20，每线程对 100 个新 colKey 调 getOrCreateColId（共 2000 次）+ 200 次 markDeleted
- **When**: 结束后重启 JVM（mode=registry）+ 再次 findColId 2000 次 + isDeleted 200 次
- **Then**: 同一 colKey 两次 JVM 的 colId 完全一致（只增不回收）；isDeleted 200 次全部 TRUE；deleted colId 不被重新分配（新 colKey 分配 nextColId ≥ 原最大值）
- **Pass Condition**: colId 一致性 2000/2000；isDeleted 200/200；nextColId 单调 >= T0 初值+2000
- **Evidence**: T04 并发断言计数

### AC-5：列存 RowIdSequence 跨重启单调性
- **Type**: `rule`
- **Given**: T=16 线程并发 allocate 8000 次（原 YAML 模式 baseline）
- **When**: 切 mode=registry → 重启 JVM → 再 allocate 8000 次
- **Then**: 16000 次返回值去重后 set.size()=16000（零冲突）；最大值 ≤ nextRowId 读值（读一个 registry leaf 校验）；前后两段最小值差 ≥ 8000（跨重启不重复）
- **Pass Condition**: 三条件全 TRUE
- **Evidence**: T05 8000×2 计数器 + HashSet size 断言

### AC-6：列存 EqIndexMeta 四操作等价
- **Type**: `rule`
- **Given**: 原 YAML 已存 50 个 index（50 个 .yaml 文件），mode=hybrid 启动后
- **When**: 执行 100 次操作 mix（put 30 / exists 40 / get 20 / delete 10）+ 切 mode=registry 重启 → 再 list()
- **Then**: YAML backend 同一操作序列的 list().size() 与 Registry backend list().size() 完全一致；每个 (entityClass, logicalName) 的 colId 一致
- **Pass Condition**: list 深对比 equals，size 相等，colId 映射 bijective
- **Evidence**: T06 list/colId 双向 map 对比

### AC-7：Online DDL TAG 状态可观测
- **Type**: `rule`
- **Given**: 一个已登记 schema 的 ORM class，改 DsField length（触发 schema rowLength change）
- **When**: 调 `forceApplyOnlineDDL(clazz)` → 立即读 Registry TAG + state leaf → 之后 `cancelOnlineDDL(clazz)` → 清 cache 再读
- **Then**: apply 阶段 TAG 含 SCHEMA_MIGRATING、state=APPLYING；cancel 后 TAG 清、state=DONE 或 NULL
- **Pass Condition**: TAG has/remove 序列与预期一致；state 4 leaf 与状态机对应
- **Evidence**: T07 TAG.hasTag / state leaf assertEquals

### AC-8：双模式 100 次切换零丢失
- **Type**: `rule`
- **Given**: mode=hybrid 下先写 10 张表的 TableMeta+ColumnRegistry+RowIdSeq（共 1000 列+ allocate 1 万 rowId）
- **When**: 连续切 mode=yaml → mode=registry ×100 次，每次切换重启 JVM（用独立进程或 classloader 模拟），最后切回 registry 跑所有 AC-1~AC-6 同断言
- **Pass Condition**: 最终与初始 hybrid 值 100% 相等，无列丢失、无 colId 冲突、无 rowId 重复；期间 mode=yaml 下 功能 100%
- **Evidence**: T08 100-round 循环计数器 + 最终断言

### AC-9：P9 DsRegistry 竞态专项无回归
- **Type**: `rule`
- **Given**: 本项目 FR-1~FR-8 实现完成后的 repo state
- **When**: 跑 P8/P9 4+4=8 个竞态专项
- **Then**: Tests run:8, Failures:0, Errors:0, Skipped:0 → 0F0E exit 0
- **Pass Condition**: BUILD SUCCESS 且 8/8
- **Evidence**: `mvn surefire` exit 0 stdout

### AC-10：热路径 p99 延迟退化上限
- **Type**: `rubric`
- **Dimension**: 热路径（RowIdSeq allocate / ColumnRegistry.getOrCreateColId / resolveEqIndex）3 条路径的 Registry backend 相对 YAML backend p99 延迟退化率
- **Scale**: 1-5
- **Anchors**:
  - 1 = ≥ 3 条退化 > +10%（严重回归，不允许合入）
  - 3 = 2 条退化在 +5%~+10%，1 条 ≤ +5%（勉强过，需要再性能调优）
  - 5 = 3 条 p99 退化 ≤ +5%，至少 1 条 Registry backend 比 YAML 快（符合天然性能预期）
- **Pass Threshold**: >= 4
- **Evidence**: P10 `DsDatabase_P10_MetaBackendPerfTest` 跑 20 iterations × best3 = 60 samples，HdrHistogram p99 输出

### AC-11：迁移工具 全量 2ways 可逆
- **Type**: `rubric`
- **Dimension**: DsDatabaseMetaMigrator.migrateAll / rollbackAll 可逆性、完整性、工具鲁棒性
- **Scale**: 1-5
- **Anchors**:
  - 1 = migrateAll 后 Registry 数据 缺失 / rollback 失败（不可用）
  - 3 = migrateAll 完整 rollback 可用 但需要手动清 migrated 标记（需要 2-3 条额外命令）
  - 5 = migrateAll → 100% YAML → Registry + 生成 manifest；rollbackAll → 100% Registry → YAML + 清 Registry orm 子树；完全一键操作，每步均有 SHA256 完整性校验
- **Pass Threshold**: >= 4
- **Evidence**: 工具执行日志 + manifest SHA256 比对 + 模式切换 2round 断言

### AC-12：代码整洁度与文档锚点
- **Type**: `rubric`
- **Dimension**: 新增实现的结构清晰度、零侵入、AGENTS Invariant、current-state 归档完整性
- **Scale**: 1-5
- **Anchors**:
  - 1 = 引入 3 处以上公开方法签名改 / 3 层以上 if/else 嵌套（严重违反约束）
  - 3 = 结构正确，但缺 AGENTS/current-state 锚点或未对齐 DsRegistryOrmBridge 风格
  - 5 = 4 个 MetaStore backend 切换通过 Composition（不是长 if 链）继承 RegistryBackend 抽象；AGENTS 追加 P10 Invariant ≥ 3；current-state 追加 P10 Block ≥ 200 行；每类元数据路径根常量在 `RegistryMetaPaths.java` 1 文件统一管理
- **Pass Threshold**: >= 4
- **Evidence**: Code diff 审查 + AGENTS/current-state 文件存在性与行计数器

## Open Questions
- [ ] OQ-1：`ids.set`（ClassIdSet 大容量 bitmap，10M rows = 1.25MB）是否需要用 REG_BINARY 存进 DsRegistry 进一步收敛？当前 FR-1 仅登记"位置指针"。保守：先指针不动内容；看 P11 跨节点同步需求再定，本期留接口不实现。
- [ ] OQ-2：Online DDL state 的 TIMEOUT 心跳判定是否要 DsDatabase 内部定时线程刷新 `lastHeartbeat`？当前只写不检，留 P10 FR-7 仅登记、P12 再做 timeout 自动回滚。
- [ ] OQ-3：列存 Columnar 现有单元测试基线（本次 exploration 发现 TableMetaStore 有调用点但没搜到 baseline test 类）数量未知。如基线 < 6tests，则 P10 专项补足到 6tests 才能验收。
