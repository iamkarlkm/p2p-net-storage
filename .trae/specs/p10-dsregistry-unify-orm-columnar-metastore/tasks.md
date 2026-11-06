# P10 DsRegistry 统一 ORM 元数据（行存+列存）- Implementation Plan

## Task 1: 路径常量 + RegistryBackend 抽象脚手架（含 mode 切换工厂）
- **Status**: `completed`
- **Priority**: high
- **Depends On**: None（所有后续任务的根任务）
- **Description**:
  - 新建 `com.q3lives.ds.registry.meta.RegistryMetaPaths` 统一管理 6 类根路径常量：
    - `/orm/row/<entityClass>/idSet/`（行存 ClassIdSet 指针）
    - `/orm/row/<entityClass>/indexes/<indexName>/`（行存 Index 配置）
    - `/orm/column/<entityClass>/table/`（列存 TableMeta）
    - `/orm/column/<entityClass>/columns/`（列存 ColumnRegistry）
    - `/orm/column/<entityClass>/seq/`（列存 RowIdSeq）
    - `/orm/column/<entityClass>/indexes/eq/<logicalName>/`（列存 EqIndexMeta）
    - `/orm/dbs/<dbSpaceName>/onlineDDL/<entityClass>/`（Online DDL state）
  - 新建 `com.q3lives.ds.registry.meta.AbstractRegistryBackend`，封装 `mode` 三态枚举（`REGISTRY`/`YAML`/`HYBRID`），提供 `readLeaf/writeLeaf/atomicModifyLong` 5 个底层 helper，自动按 mode 路由到原 YAML 或新 Registry 实现；HYBRID 模式下写双份、读优先 Registry miss 则回读 YAML 并回填 Registry。
  - 读 JVM 属性 `p2p.orm.meta.mode`（默认 HYBRID）作为全局 mode；同一 JVM 实例内运行时不可切（static final，保证原子性）。
  - 新建 `com.q3lives.ds.registry.meta.DsRegistryInstance`：按 dbRoot 单例持有 DsRegistry，避免每个 MetaStore 各自创建（和当前 DsRegistryOrmBridge 构造参数兼容）。
- **Acceptance Criteria Addressed**: NFR-3（零侵入回退）、NFR-5（原子性）、AC-8（双模式 100 次切换零丢失）基础结构
- **Test Requirements**:
  - `rule` TR-1.1：`RegistryMetaPaths` 全部路径常量通过 `RegistryMetaPathsTest` 验证：每个路径 prefix 不包含空格、中文、大写字母、`..` 相对段；每类路径 root 不冲突（无重叠 prefix）。
  - `rule` TR-1.2：`AbstractRegistryBackend mode=YAML` 时，所有 write/read 调用走原 YAML 实现类（通过 Mockito spy 原方法 + verify 调用次数验证：Registry 相关方法 call count=0，YAML 方法 call count=预期）。
  - `rule` TR-1.3：`mode=HYBRID` 连续 `writeLong("foo", 1) → writeLong("foo", 2)` 后，mode=REGISTRY 单独读 ==2；mode=YAML 单独读 ==2（双写等价性）。
  - `rubric` TR-1.4：代码结构内聚度；scale 1-5；1=散 4 处硬编码 prefix、`if(mode==)` 散在业务方法里；3=路径常量集中、但 mode switch 仍散在业务；5=路径 1 处集中、mode 路由完全在 AbstractRegistryBackend 内部、4 个 MetaStore 实现看不到任何 `if(mode)`。阈值 >=4。

## Task 2: 行存 ClassIdSet 指针登记 Registry (FR-1)
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 修改 `EntityIndexUtil.indexOf(File dbRoot, Class<?> clazz)`：在原散文件判断基础上（返回 IndexDef 前），通过 `DsRegistryInstance.get(dbRoot)` + `AbstractRegistryBackend` 写入 3 个 leaf（legacySchemaId、idsFileAbsolutePath、createdAtEpochMs）。读取路径：优先读 Registry 3 leaf 构造 IndexDef，miss 则回原散文件逻辑（HYBRID）并回填 Registry。
  - 新增 IndexDef 字段 `String absolutePath`（向后兼容，null 时沿用原字段 idsFile 相对路径 + dbRoot 拼）。
  - 保持 `legacy=true/false` 判定逻辑完全等价（老库 legacyIds.exists() && !schemaIds.exists() → 原判定）。
- **Acceptance Criteria Addressed**: FR-1、AC-1
- **Test Requirements**:
  - `rule` TR-2.1：空库新创建 → JVM 重启 → 读 Registry leaf 3 字段值与创建时完全一致；`IndexDef.legacy` 与预期 FALSE。
  - `rule` TR-2.2：老库模式（手工创建 legacyIds.set）→ 首次调用 indexOf 后，Registry leaf `legacySchemaId=NULL`、`absolutePath=<legacyIds.set>`；重启后仍为 legacy=true（与原先 IndexDef 同）。
  - `rule` TR-2.3：原 EntityIndexUtil 公开调用点（目前 Grep 出 DsDatabaseLocal 1 处 + GenericManager 0~1 处）全部不改签名；编译 exit 0。

## Task 3: 行存 Index 实例配置登记 Registry (FR-2)
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 2
- **Description**:
  - 修改 `DsDatabaseLocal.resolveEqIndex(TableMeta<?>, IndexedColumn)` 和 `resolveCompositeIndex(TableMeta<?>, CompositeIndex)`：首次 new DsEqIndexStore 后，写 Registry 5 leaf（kind/schemaId/mvcc/valueKind/dirAbsolutePath）。cache miss 时先查 Registry 5 leaf 并按参数构造 store，miss 才走原 `DsPathUtil.toSafeFileName` 字符串拼路径。
  - 在 `DsDatabaseLocal` 构造函数中注入 `AbstractRegistryBackend` 实例（通过 DsRegistryInstance 拿到），不对外暴露。
  - 保持 indexCache cache key 原语义不变（`meta.space + "#" + meta.schemaId + "#" + col.columnName`）。
- **Acceptance Criteria Addressed**: FR-2、AC-2
- **Test Requirements**:
  - `rule` TR-3.1：创建 3 个 indexed 列 + 1 compositeIndex 类 → 1K 次 putRow → 清 indexCache → 再次 resolve 4 次时 `DsPathUtil.toSafeFileName` 调用次数 = 0（全 Registry 取）。
  - `rule` TR-3.2：Registry 5 leaf 值与实际 new store 的参数 deep equals。
  - `rule` TR-3.3：MVCC 列的 mvcc leaf=1 / 非 MVCC = 0，valueKind 列 LONG=0 STRING=1，与原 store IndexedValueKind 对应。

## Task 4: 列存 TableMetaStore 后端切换 Registry (FR-3)
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - `TableMetaStore.getMeta/ensureMeta` 2 个公开方法签名 0 改；内部 backend 路由到 `AbstractRegistryBackend`。
  - 原 build(entityClass) 生成后，写 Registry：`/orm/column/<entityClass>/table/signature`（REG_SZ）+ 每个 column 的 6 个字段（colId/colKey/name/javaType/length/precision/scale 共 6 leaf per column）+ compositeGroup 的 colId/group/length 3 leaf per group；通过 ExtAttrs 挂节点属性（如 columnCount）与 signature 一致性。
  - HYBRID 模式下：ensureMeta 幂等判断优先读 signature（Registry 有且与 fresh 相等→直接返回），否则回读 YAML 并同步写 Registry。
  - 列存 DbTableSchema 入参的 ensureMeta(entityClassName, DbTableSchema, overwrite) 同样后端切换，保持 overwrite 语义。
- **Acceptance Criteria Addressed**: FR-3、AC-3
- **Test Requirements**:
  - `rule` TR-4.1：同 AC-3 的给定条件 → `TableMeta.equals(fresh, loadedYaml)` deep equals = true；colId 跨 JVM 重启一致。
  - `rule` TR-4.2：原 YAML 文件存在时，HYBRID 首次调用后 `table.meta.yaml → table.meta.yaml.migrated`（rename 成功）；Registry backend 二次调用不读 YAML。
  - `rubric` TR-4.3：幂等性；scale 1-5；1=每次 ensureMeta 都 覆写 Registry leaf；3=signature 相同不覆写但有额外磁盘 stat；5=signature 相同 0 次 masterIndex write、0 次 bucket I/O（只做一次 get）。阈值 >=4。

## Task 5: 列存 ColumnRegistry 后端切换 Registry (FR-4)
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 公开方法 `getOrCreateColId/findColId/markDeleted/isDeleted` 签名 0 改；内部 backend 路由。
  - 映射：`nextColId` → single REG_QWORD leaf（atomicModifyLong 用 DsRegistry striped locks CAS + verify 语义）；`byKey/<colKey>` → REG_QWORD=colId；`byId/<colId>` → REG_SZ=colKey；`deleted/<colId>` → REG_DWORD=1 存在即 deleted（删除只 mkdirs + write DWORD，不做双向 map 反向 remove，保持原"只增不回收"语义）。
  - 与原 loadMeta/saveMeta 完全一致：nextColId 初始值 1L；colId 永不复用；markDeleted 只加 deleted set，不回改 byKey/byId。
- **Acceptance Criteria Addressed**: FR-4、AC-4
- **Test Requirements**:
  - `rule` TR-5.1：20×100=2000 并发 getOrCreateColId → 200 次 markDeleted → 重启 → findColId 2000/2000 一致；isDeleted 200/200 TRUE；nextColId ≥ 2001。
  - `rule` TR-5.2：Deleted colId 的 findColId 仍正常返回（原语义：markDeleted 不改分配结果，只标记）。
  - `rule` TR-5.3：跨 JVM 同 colKey 得到同 colId（只增不回收）。

## Task 6: 列存 RowIdSequenceStore 后端切换 Registry (FR-5)
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 公开方法 `allocate(entityClass)` 签名 0 改。内部通过 `atomicModifyLong("/orm/column/<entityClass>/seq/nextRowId", prev -> prev + 1, 初始值 1L)` 实现（用 DsData 的 striped locks CAS retry 保证原子 ++，比原 YAML read-modify-write 少 2 次 FileLock+parse）。
  - HYBRID 模式下：启动时若 rowid.meta.yaml 存在，读 YAML nextRowId → 写 Registry leaf → rename `.migrated`；后续只走 Registry。
- **Acceptance Criteria Addressed**: FR-5、AC-5、NFR-1（热路径）
- **Test Requirements**:
  - `rule` TR-6.1：16T×500=8000 并发 allocate → set.size=8000 无冲突；maxVal == Registry nextRowId leaf - 1；重启后再 allocate 8000 → set.size 16000 仍无冲突。
  - `rule` TR-6.2：初始值 1L 与原 YAML 一致（老库迁移后 nextRowId = 原 max + 1，不回退从 1 重新开始）。
  - `rubric` TR-6.3：性能；scale 1-5；1=Registry backend p99 比 YAML 慢 > 20%；3=慢 5~20%；5=快 ≥ 30%（Registry striped locks CAS 避免 FileLock + YAML parse）。阈值 >= 4。

## Task 7: 列存 EqIndexMetaStore 后端切换 Registry (FR-6)
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: Task 1
- **Description**:
  - 公开方法 `exists/get/list/put/delete/mapFile` 签名 0 改。映射：`/orm/column/<entityClass>/indexes/eq/<logicalName>/colId = REG_QWORD`；colId 存在即 exists。
  - list(entityClass)：listChildren 列出 eq 目录下全部 logicalName → 逐个读 colId leaf，组装 IndexDef（与原 YAML 读到的字段集合一致，3 字段）。
  - mapFile/lockFile：保留原实现（这两个方法返回数据 map 路径，不属元数据范畴，保留原散文件命名符合 Non-Goals #1 要求）。
- **Acceptance Criteria Addressed**: FR-6、AC-6
- **Test Requirements**:
  - `rule` TR-7.1：50 个初始 index → 100 次 mix 操作 → 切 mode 重启 → list size 与原 YAML backend 深对比 相等。
  - `rule` TR-7.2：delete 后 Registry colId leaf 不存在（exists 返回 false）；与原 delete 语义完全一致。
  - `rule` TR-7.3：mapFile/lockFile 方法实现不变（diff 验证这两个方法的 body 0 字符改）。

## Task 8: Online DDL State Registry 登记 + TAG (FR-7)
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: Task 1, Task 2, Task 3, Task 4
- **Description**:
  - `DsDatabaseLocal.forceApplyOnlineDDL/cancelOnlineDDL/forceRollbackSchema` 三个入口：原只是 `tableMetaCache.remove(clazz)`；本次在 remove 之前用 DsRegistry TAG 标记 `/orm/dbs/<dbSpaceName>/onlineDDL/<entityClass>/` 节点状态：
    - forceApply → `TAG SCHEMA_MIGRATING` + state leaf APPLYING + fromSchemaId/toSchemaId/startedAt/lastHeartbeat。
    - cancel → TAG 清 + state=CANCELLED。
    - forceRollback → TAG ROLLBACK + state=ROLLBACK。
  - createMeta 时若发现节点 TAG=SCHEMA_MIGRATING 且 lastHeartbeat > 300s，print WARN but proceed（timeout 自动清理留 OQ-2，本次不做）。
  - dbSpaceName 取 DsDatabaseLocal constructor 的 space 参数或 clazz.getName 的 package 前缀（通过构造函数注入统一取法）。
- **Acceptance Criteria Addressed**: FR-7、AC-7
- **Test Requirements**:
  - `rule` TR-8.1：调用 forceApply → 立即 hasTag(SCHEMA_MIGRATING) = true；state leaf APPLYING；4 leaf 值与传入 fromSchemaId/toSchemaId/startedAt/now 对应。
  - `rule` TR-8.2：cancel 后 TAG SCHEMA_MIGRATING 清 = false；state leaf = CANCELLED（或 NULL，二选一但实现固定一种）。
  - `rule` TR-8.3：三方法公开签名 0 改（参数表、返回值、throws 完全一致）。

## Task 9: 双模式切换开关 + 迁移工具 (FR-8)
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: Task 2-7 全部
- **Description**:
  - 新建 `com.q3lives.ds.registry.meta.DsDatabaseMetaMigrator`：`migrateAll(File dbRoot)` 遍历 dbRoot/indexes/* 下所有 YAML/散 idSet 指针文件；按 entityClass 维度逐项调用 Task2-7 的 Registry 写入方法，完成后 rename `.yaml` → `.yaml.migrated`；每步写 SHA256 checksum 到 manifest。
  - `rollbackAll(File dbRoot)`：遍历 manifest，双向把 Registry 数据读回 YAML，rename `.yaml.migrated` → `.yaml`，再清 `/orm/` 子树。
  - 支持 `dryRun=true/false` 参数；dry-run 只打印不写。
- **Acceptance Criteria Addressed**: FR-8、AC-8、AC-11
- **Test Requirements**:
  - `rule` TR-9.1：migrateAll 之后 → 所有 YAML 文件均 `.migrated`；Registry 下 6 类元数据 100% 有值（通过 listChildren 统计，原 N 张表 × 6 类 = 6N entries）。
  - `rule` TR-9.2：rollbackAll 之后 → 所有 `.migrated` 还原为 `.yaml`；Registry `/orm/` 子树不存在。
  - `rule` TR-9.3：连续 mode=yaml → mode=registry 切换 × 100 次（每次 JVM 独立启动或 classloader 隔离级模拟），最后 AC-3/AC-4/AC-5 断言全 PASS。
  - `rubric` TR-9.4：可逆完整性；scale 1-5；1=migrate 缺 1-2 类元数据、rollback 缺字段；3=migrate/rollback 完整但无 manifest 校验；5=manifest SHA256 每步验证，失败立即中断 + 自动 undo 已迁移段。阈值 >= 4。

## Task 10: P10 专项测试类实现（含正确性 + 性能）
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1-9
- **Description**:
  - 新建 3 个测试类（放 p2p-db/src/test/java/ds/ 下，与 P8/P9 专项同目录）：
    - `DsDatabase_P10_RowMetaRegistryTest.java`（AC-1、AC-2、AC-7）→ 6@Test
    - `DsDatabase_P10_ColumnMetaRegistryTest.java`（AC-3、AC-4、AC-5、AC-6）→ 7@Test
    - `DsDatabase_P10_MetaBackendPerfTest.java`（NFR-1、AC-10 TR 6.3、TR-10.1）→ 1@Test（含 3 条路径的 HDR p99 采集）
  - 每个测试类都设 `@TempDir` JUnit 5 temporary dbRoot，避免与其他测试共享。
- **Acceptance Criteria Addressed**: AC-1~AC-7、AC-10
- **Test Requirements**:
  - `rule` TR-10.1：全部专项类 Tests run = 15（新增 ColumnMetaTest c08 NG-3 gatekeeper 防 P8/P9 回归），0F0E exit 0。
  - `rubric` TR-10.2：覆盖率；scale 1-5；1=专项仅覆盖 AC-1~AC-3 happy path；3=覆盖 AC-1~AC-7 happy path + 1 个 edge case per AC；5=每类 edge case：并发 (T≥16)、空库、老库 legacy、双模式切换 10 次、迁移中断后 resume。阈值 >= 4。

## Task 11: 回归门验证 (P7/P8/P9 + 基线 + 烟测)
- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 10 完成（专项 PASS 才跑回归）
- **Description**:
  - 串行跑 4 条 mvn 命令，任何一条 FAIL 立即 revert 最后一次提交：
    1. `mvn -pl p2p-db '-Dtest=ds.DsRegistryExtAttrs_P7_Exts_ConcurrentStressTest,...,Phase2V2'` → P7 19专项 32tests 0F0E
    2. `mvn -pl p2p-db '-Dtest=ds.DsString_DsData_P8_RefCount_ConcurrentStressTest,ds.DsTypeString_P9_RefCount_ConcurrentStressTest'` → 8tests 0F0E
    3. `mvn -pl p2p-db '-Dtest=ds.DsStringBlockStoreTest,ds.DsStringRefCountTest,ds.DsDataTest'` → 6tests 0F0E × 2 rounds
    4. `mvn -pl p2p-db test '-Dtest=!*Bench' '-q'` → 汇总烟测 PASS 计数 ≥ 103（实测 P10 正确性相关 ≥159 PASS，DsRegistryExtAttrs_P7_Tgt5_PerfDeep_16x16 T02 throughput=7825 tps < 10K 归 Windows CFS 调度差环境项，不纳入 correctness 回归，独立记录在 current-state §L B3 小节）。
- **Acceptance Criteria Addressed**: NFR-2、AC-9
- **Test Requirements**:
  - `rule` TR-11.1：P7 32 / P8 4 / P9 4 / 基线 6×2 = 总计 ≥52 tests，0F0E。
  - `rule` TR-11.2：烟测 PASS ≥ 103；如果有 FAIL 类不属于本项目改动范围（如 benchmark/无关模块），需列白名单并在 evidence 中说明。

## Task 12: 归档 (Brain 三件套 + current-state/AGENTS 锚点)
- **Status**: `completed`
- **Priority**: medium
- **Depends On**: Task 11 完成（回归 PASS 才归档）
- **Description**:
  - Brain audit → 13 Findings（stale_ref 4 + changed_surface 9 expected）
  - Brain distill --session → 生成 P10 提案
  - Brain session finish exit 0
  - `current-state.md` 追加 §L P10 VERIFIED CLOSED Block（≥300 行，含：探索结论 7 类元数据盘点 / 路径设计 / 实现任务 1-12 映射 / 迁移 manifest 示例片段 / 实测 14专项+32+8+12 数据表 / Gotcha 8 条 / Joint Verification Cmd 6 条 / Follow-up=OQ 留 2 条）
  - `AGENTS.md` 追加 P10 Invariant ≥ 3 条：
    1. 路径常量唯一出处原则：`RegistryMetaPaths.java` 1 处管理，禁止业务代码硬拼 `/orm/row/...` 字符串。
    2. 模式切换三态原子性：`p2p.orm.meta.mode` JVM 启动后 static final，运行时不可改；任何动态改必须 JVM 重启。
    3. 列存 4 MetaStore 签名零改原则：TableMetaStore/EqIndexMetaStore/RowIdSequenceStore/ColumnRegistry 公开方法**永不改签名**，backend 变化必须在内部路由，不能破坏调用方。
- **Acceptance Criteria Addressed**: AC-12
- **Test Requirements**:
  - `rule` TR-12.1：Brain session finish exit 0；distill proposal 文件名存在。
  - `rule` TR-12.2：current-state.md §L 行数 ≥ 300；AGENTS.md 新增 P10 Invariant ≥ 3 条。
  - `rubric` TR-12.3：文档完整性；scale 1-5；1=缺实测数据表/Joint Cmd；3=有表但缺 Gotcha/Follow-up；5=Gotcha ≥8、Joint Verification Cmd ≥6、Follow-up 明确对应 OQ-1/2 留接口不实现。阈值 >= 4。
