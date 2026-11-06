# P11 Spec — OQ1：DML 真实数据（Row / Column payload）统一落 Registry

> 会话 ID：延续 P10 收官后专项立项；周期：2026-09-21 → 2026-10-15（约 4 周，可提前 1 周 MVP）

## 1. 问题与用户

### 1.1 现状问题（P10 完成后暴露）

P10 已将 **7 类元数据（行存 ClassIdSet / Index / DBS 状态 + 列存 TableMeta / ColumnRegistry / RowIdSeq / EqIndexMeta / OnlineDDL）** 全量切到 DsRegistry 统一承载，三态 HYBRID 默认、可逆 SHA256 manifest 迁移、实测 RowIdSeq 退化率 **-14.91%（Registry 还快 15%）**、38 类 JV tests 0F0E 已 VERIFIED CLOSED。

但 P10 的承载范围**仅覆盖元数据**，**DML 真实数据（Row payload bytes、Column value bytes、列组 payload）仍直接散落在**：
1. **DsFixedBucketStore bucket 层**：行存 `{spacePath}/{type}/data_{2^power}.dat` 定长桶（行 rowLength 128B 以内占比 90%+）；列变长 `reg_vals/bin` 也走 bucket。
2. **DsHashMap colMaps / DsFixedValueStore colValueStores / DsColumnGroupStore**：列存按列分桶 `rowId → valueId` 映射。
3. **没有统一的 key 命名空间与原子 CAS 写入接口**：bucket 层 `space/type/id` 三元组无法跨 DsDatabaseLocal / ColumnarStore / DsEqIndexStore / WAL 统一寻址，给 OQ-2（跨节点同步）、OQ-3（ACID 事务）直接落地制造了**命名层割裂**。

### 1.2 目标用户与收益链路
| 用户/阶段 | 直接收益 | 最终打通 |
|-----------|----------|----------|
| 本机 DsDatabaseLocal 嵌入式 ORM 调用方 | 统一 `/orm/data` 三棵子树寻址，0 公开 facade 改可灰度 | 为后续 ACID row-level 事务（OQ3）准备**单 key CAS writer** |
| ColumnarStore 列存批量调用方 | payload + 元（TableMeta col info）共存在同一 Registry 根，冷启动 1000 表元+值 parse ≥2s 进一步压到 ≤1.5s | 为后续 LSM/Olap 列扫描统一**值定位器**（元→值同路径前缀） |
| OQ2 跨节点同步调用方 | 全部 DML 操作 → Registry 单 key 变更 → commit log 增量同步 0 猜测 | 跨 2 节点 Raft/CRDT ≤100ms RTT 最终一致 |
| 运维 | 备份：`DsRegistry` 单根 `/orm` 全量复制=元+值快照；恢复：单 rootDir → 0 散文件挂载 | 备份窗口从 ≥30min（1000 张表 200 万 YAML + data_*.dat 散文件）→ ≤2min（Registry 单根连续页） |

## 2. 目标（Goals）与非目标（Non-Goals）

### 2.1 Goals（4 周交付）
**G1：命名统一（0 业务侵入）**
- 新增 `/orm/data` 子树三棵路径前缀：行 `/orm/data/row/<entityClass>/<rowId>`、列 `/orm/data/column/<entityClass>/<colName>/<rowId>`、列组 `/orm/data/group/<entityClass>/<groupName>/<rowId>`。
- 所有路径由 `RegistryMetaPaths.java` 新增 `DATA_ROOT / dataRowPayload(dataRowKey) / dataColumnPayload(dataColKey) / dataGroupPayload(dataGroupKey)` 三个 typed helper 统一生成，禁止业务代码直接硬编码字符串（P10 c08 Gatekeeper 延伸——RegistryMetaPaths public 字段 100% String 零外泄）。

**G2：接入统一（复用 P10 三态 HYBRID 双写双读 + 回填）**
- 抽象 `AbstractRegistryDataBackend extends AbstractRegistryBackend`：新增 `readLeafBytes` / `writeLeafBytes` / `removeLeaf` / `leafExists` 4 个 binary helper（P10 原 Backend 只有 String/Long）。
- 行存：`DsDatabaseLocal.putEntity/getTable/removeTable` 的 bucket 调用前后加 `dataBackend` 钩子（HYBRID=双写双读；REGISTRY=只走 Registry；YAML=只走 bucket），公开签名 **0 字符改**。
- 列存：`ColumnarStore.putValue/getValue/removeRow/markDeleted` 加 `dataBackend` 钩子，公开签名 **0 字符改**。

**G3：性能门槛（目标 P10 RowIdSeq deg=-14.91% 类似）**
- **1B 小对象（行 payload ≤128B、列 payload ≤64B）写入 P99 延迟 ≤ 1.20× 原纯 bucket P99**；热页 miss 时回填不影响主路径（256 条 per-path lock CAS 最多 3 次 attempt）。
- 读取 P99：纯 Registry 命中 ≤1.30× bucket；HYBRID 命中 bucket → 回填 Registry 异步化（不阻塞调用方）。
- 退化率度量：同 P10 MetaBackendPerfTest 双 tmp 根——`pureBucketRoot`（预置 payload）vs `registryRoot`，同 JVM N=5000 warmup=2000，独立 5 轮取 P99 均值（消除 Windows CFS tick 抖动）。

**G4：正确性回归（38+ JV 基线 + 12 新增 ≥50 0F0E）**
- 原 P10 JV：38 tests（P10 15 专项 + P7 4 类回归门 + ColumnarStore 12）**全量 PASS** 作为正确性回归基线（不允许引入正确性回归，哪怕是散 bucket 的行为差异）。
- 新增 P11 专项 12 tests：行/列/列组 各 4（put→get roundtrip、双写一致性、HYBRID miss 回填、remove 双删）。
- 新增 P11 c09 Gatekeeper（延续 P10 c08 NG-3 静态钉死）：反射校验 DsDatabaseLocal 28 个公开方法签名 0 改；ColumnarStore 24 个公开方法签名 0 改；RegistryMetaPaths 新增 public 常量≥4，且 100% String。

### 2.2 Non-Goals（本阶段严格不做）
- ❌ **OQ2 跨节点 Raft/CRDT**：仅为 OQ2 准备命名空间 + 统一 key，OQ2 专项另开（预计 8 周，等 P11 VERIFIED CLOSED）。
- ❌ **OQ3 ACID row-level 事务 / TPC-C**：仅为 OQ3 准备单 key CAS 语义（通过 `AbstractRegistryDataBackend.atomicModifyBytes` 预支 CAS 接口），OQ3 事务隔离级、死锁检测、回滚段另开专项。
- ❌ **LSM / Olap 列扫描 / 列存 secondary index**：列扫描不在范围。
- ❌ **更改 bucket 层 free-ring 回收策略、更改 DsFixedBucketStore 内部布局**：Gs1-4 全部在 Registry 命名 + Backend 层，不动底层存储实现。
- ❌ **对外暴露新 RPC Handler**：26 个现有 Server Handler 通过 DsDatabaseLocal/ColumnarStore 0 签名改天然兼容，不新增 `DB_REGISTRY_DATA_PUT/GET` 对外 RPC。

## 3. 功能需求（Functional Requirements）

### 3.1 FR1：RegistryMetaPaths 扩 DATA 三棵子树（路径唯一出处）
1. 新增常量 `DATA_ROOT = ROOT + "/data"`（即 `/orm/data`），且与现有 `ROW_ROOT` / `COLUMN_ROOT` / `DBS_ROOT` 三前缀严格不重叠（无包含关系）。
2. 新增 typed helper（零硬编码）：
   - `dataRowPayload(entityClassName, long rowId) → String`：行 payload `/orm/data/row/<entitySegment>/<rowIdHex16>`（rowId 用 16 位 0 补齐 hex，保证段名合法不重）。
   - `dataColumnPayload(entityClassName, String logicalColName, long rowId) → String`：列单值 `/orm/data/column/<entitySegment>/<colSegment>/<rowIdHex16>`。
   - `dataGroupPayload(entityClassName, String groupName, long rowId) → String`：列复合组打包 `/orm/data/group/<entitySegment>/<groupSegment>/<rowIdHex16>`。
   - `rowIdHex(long)`：内部 helper，rowId 用 `%016x` 小写，保证数字段名排序与写入顺序一致（避免 listChildren 乱序影响可观测性）。

### 3.2 FR2：AbstractRegistryDataBackend 二进制 Backend 抽象（extends P10 原 AbstractRegistryBackend）
1. 新增 4 个 binary helper（同 P10 String/Long 路由风格——HYBRID 双写双读+回填）：
   - `readLeafBytes(registryPath, bucketSupplier, defaultValue) → byte[]`：按 MetaMode 路由；HYBRID miss→读 bucket 旧值→writeRegistryBytes 回填→返回原 byte[]。
   - `writeLeafBytes(registryPath, byte[] value, bucketConsumer)`：双写或单边；HYBRID bucket 失败→WARN 不抛（Registry 已权威，可回退）。
   - `removeLeaf(registryPath, bucketRemover) → boolean`：双删。
   - `leafExists(registryPath, bucketExistsSupplier) → boolean`：任一后端存在→true。
2. 新增 1 个原子 CAS helper（预留给 OQ3 事务）：
   - `atomicModifyBytes(registryPath, UnaryOperator<byte[]> op, byte[] seedIfAbsent, bucketGetter, bucketSetter) → byte[]`：per-path striped lock（复用 AbstractRegistryBackend 现有 256-entry lock 数组），最多 256 次 attempt；成功返回**新值 byte[]**。

### 3.3 FR3：行存 DsDatabaseLocal 接入（公开方法签名 0 改）
1. **putEntity(value, assignIdIfMissing)**：
   - 原步骤 2（rowId 分配→`bucketStore.update(space, type, rowId, bytes, KEEP_BUCKET)`）不变；新增：`dataBackend.writeLeafBytes(dataRowPayload(entityClass, rowId), bytes, (b) -> { /* bucket 已经写过了，这里只写 Registry；YAML 模式下此 consumer no-op */ })`。
   - MetaMode=YAML：整个 `dataBackend` 调用短路，行为完全等价于 P10 收官基线（无回归风险，可通过系统 property `-Dp2p.meta.mode=yaml` 一键回退）。
2. **getTable(clazz, id, withRelations=true)**：
   - 原 `bucketStore.get(space, type, id, 0, rowLength)` 前增加 `dataBackend.readLeafBytes(dataRowPayload, () -> bucketBytesGetter, null)` 优先读 Registry；Registry miss 才读 bucket，并在 HYBRID 模式下同步回填 Registry（写入成功后再返回 byte[]，不异步，避免下一次读同一 key 再 miss 一次）。
3. **removeTable(clazz, id, withRelations)**：原 bucket 删除 + `dataBackend.removeLeaf(dataRowPayload, bucketRemover)` 双删；YAML 模式短路。
4. **getTable / getTables / queryIds**：所有**读路径**必须一致通过 dataBackend（避免读回的 byte[] 版本与写入时不一致）。

### 3.4 FR4：列存 ColumnarStore 接入（公开方法签名 0 改）
1. **putValue(entityClassName, logicalName, rowId, valueBytes)**：
   - 原步骤 3（layout 解析→`putColumnValue / putBucketColumn`）不变；新增 `dataBackend.writeLeafBytes(dataColumnPayload, valueBytes, ...)`（HYBRID 双写；REGISTRY 只走 Registry）。
2. **getValue(entityClassName, logicalName, rowId, length)**：
   - 优先读 `dataBackend.readLeafBytes(dataColumnPayload, () -> bucketColGetter, null)`；Registry miss→读 bucket + 回填。
3. **removeRow(rowId) / markDeleted(col)**：对应 `dataBackend.removeLeaf` 或 `dataBackend.atomicModifyBytes`（软删除时写 tombstone leaf `deleted=<epochMs>`）。
4. **列复合组 group payload**（compositeGroups 场景下 cg.items 合成的 bytes）：走 `dataGroupPayload`，与单列 payload 路径严格分开（避免 group 和同名单列冲突）。

### 3.5 FR5：三态全局开关一致复用 P10 MetaMode
1. 不新增系统 property，**完全复用** P10 已交付：
   - `p2p.meta.mode`（`yaml | registry | hybrid`，缺省 `hybrid`）
   - `MetaMode.global()` JVM 启动后 static final 不可变（保证同进程不切出奇怪的"写了 registry 读回 yaml"不一致状态）。
2. `DsRegistryInstance` 复用：dataBackend 同 DsDatabaseLocal/ColumnMetaStore 共享同一个全局 DsRegistry（保证 `/orm` 根下元+值同实例、同 serverId、同 free-ring，避免多 Registry 实例导致 COW 冲突）。

## 4. 非功能需求（Non-Functional Requirements）

### 4.1 正确性（硬门槛，未满足 = Spec 失败）
| 编号 | 描述 | 阈值 |
|------|------|------|
| NFR-C1 | 原 JV 基线（38 tests：P10 15 专项 + P7 4 类门 + ColumnarStore 8 + Compression 4）**全部 PASS**，0F0E | 0F0E，硬门槛 |
| NFR-C2 | P11 新增专项 12 tests（行 4 / 列 4 / 列组 4）：put→get 校验 bytes 完全一致、双写一致性、HYBRID 回填、remove 双删——**全部 PASS** | 12/12 0F0E |
| NFR-C3 | P11 c09 Gatekeeper（静态反射钉死 facade 0 改）——**25 assertions 全 PASS**，含：① DsDatabaseLocal 公开方法数∈[26,34] 且 24 个核心签名 0 改；② ColumnarStore 公开方法数∈[22,30] 且 20 个核心签名 0 改；③ RegistryMetaPaths public static final String 常量 ≥11（原 7 + 新增 4 DATA_ROOT + 3 helper 段）；④ RegistryMetaPaths public 字段 100% String；⑤ capture 非空防止假绿。 | 25/25 全 PASS |

### 4.2 性能（软门槛，Windows CFS 偏差不超过 5% 视为通过，如超需给出解释）
| 编号 | 维度 | 目标阈值（5 轮 均值） | 环境约束 |
|------|------|------------------------|----------|
| NFR-P1 | Row put 小对象（128B）P99 延迟 | RegistryRoot P99 ≤ PureBucketRoot P99 × 1.20 | 同 JVM，独立双 tmp 根；N=5000 warmup=2000；5 轮 |
| NFR-P2 | Row get 小对象（128B）P99 延迟 | RegistryRoot P99 ≤ PureBucketRoot P99 × 1.30 | 同上；HYBRID 回填路径不阻塞（写成功后再返回） |
| NFR-P3 | Column put 小对象（64B）P99 延迟 | RegistryRoot P99 ≤ PureBucketRoot P99 × 1.20 | 同上 |
| NFR-P4 | Column get 小对象（64B）P99 延迟 | RegistryRoot P99 ≤ PureBucketRoot P99 × 1.30 | 同上 |
| NFR-P5 | 冷启动 1000 张表元+值 parse（从 JVM 启动→DsDatabaseLocal new 完毕→全部表 meta+first-row 载入） | 纯 YAML = baseline；Registry+HYBRID ≤ baseline × 0.75（快 ≥25%） | 无 page-cache（重启 JVM 3 次取均值）|

### 4.3 可维护性与可观测性
| 编号 | 描述 |
|------|------|
| NFR-M1 | 所有 `/orm/data` 相关路径必须走 RegistryMetaPaths typed helper，代码库中 **grep "orm/data" 只允许在 RegistryMetaPaths.java 中 1 处出现（常量声明）**，其他文件零命中（由 c09 Gatekeeper 延伸的 grep check 实锤）。 |
| NFR-M2 | Backend 双写失败（Registry 成功 / bucket 失败的 HYBRID 场景）：只打 `WARN`，不抛异常；WARN 日志必须包含 `registryPath`、`entityClass`、`rowId`、`rootDir` 四要素（便于回滚对齐脚本定位）。 |
| NFR-M3 | 回退性：通过 `-Dp2p.meta.mode=yaml` 后，P10 基线（38 tests）行为 100% 等价（YAML 模式下 dataBackend 全部短路，Registry 实例根本不 new，由 MetaMode 控制）。 |
| NFR-M4 | 零跨子项目影响：p2p-ws-sdk-* / p2p-cache / docs/ds-mft-filesystem / examples/IM 全部**零字符改**（本专项只涉及 p2p-db 模块）。 |

## 5. 约束、依赖、假设、开放问题

### 5.1 Constraints（硬约束，不可协商）
1. **公开 facade 0 签名改**：`DsDatabaseLocal` / `ColumnarStore` / `TableMetaStore` / `ColumnRegistry` / `EqIndexMetaStore` / `RowIdSequenceStore` —— 6 个公开类的 public 方法签名（参数类型/数量、返回类型、throws 列表）0 改。新增 method 仅允许在 `protected` / package-private 层（如 dataBackend）。**（违反 = AC 不通过，立即回滚）**
2. **P10 已交付 7 类元数据路径零字符改**：不能动 `/orm/row` / `/orm/column` / `/orm/dbs` 三棵子树中的任何现有 leaf 命名（如 signature / nextRowId / colId 等），只能加兄弟 `/orm/data` 前缀。
3. **不引入任何新 Maven 依赖**：复用 SnakeYAML、LZ4 Java（P7 Tgt4 已）、Java NIO / ConcurrentHashMap；零新增 commons-*、guava、jackson 等库。

### 5.2 Dependencies（已完成 / 可复用）
- P10 已交付：`AbstractRegistryBackend`（String/Long 路由 + 三态 HYBRID + per-path striped lock CAS）、`RegistryMetaPaths`（路径零外泄、c08 Gatekeeper 通过）、`MetaMode`（全局 static final 不可变）、`DsRegistryInstance`（同 dbRoot 共享单例 DsRegistry）、`DsDatabaseMetaMigrator`（SHA256 manifest 双向可逆迁移脚手架——未来 P11 值迁移可复用 migrateAll/rollbackAll 模式）。
- P7 已交付：binlog、26 RPC Handler、DsEqIndexStore。
- P6a 已交付：DsRegistry readNodeMeta 只接受 typed id，纯 id fallback 已拆除（避免路径寻址中 cow_ex=1 竞态叠加）。

### 5.3 Assumptions
1. **payload 大小分布**：生产 95% 行 payload ∈ [24B, 512B]，列 payload ∈ [8B, 128B]；> 64KB 的超大 payload（BLOB）当前仍走 bucket free-ring（DsRegistry putBytes 对 > INLINE_BYTES_MAX_LEN（8B）的值已经内部落到 valueBinaries bucket，大对象性能等价，不做额外优化）。
2. **同一 rowId 的并发写**：由 P7 binlog 回放阶段的 `replayApplyLock`（DsDatabaseLocal L109）控制串行化——同一 rowId 不存在真正并发写；跨 rowId 并发由 per-path 256 striped lock 承接（冲突率 ≤ 1/256）。
3. **冷启动 page-cache**：NFR-P5 的"无 page-cache"通过 `JVM 重启 + 独立 tmp 根（避免跨轮页命中）` 实现，不强制要求清 OS 级 page-cache（开发机权限受限，用"独立根+重启"近似代替）。

### 5.4 Open Questions（待用户/未来专项确认）
1. **OQ2 同步触发点**：P11 只把值落到 Registry；OQ2 的"per-key commit log 监听→Raft/CRDT 广播"要在 `/orm/data` 三棵子树下新增 `/commitlog/<seq>` 吗？还是沿用 P7 binlog？（等 P11 性能验证通过后由 OQ2 专项决定，本阶段不做。）
2. **大对象 BLOB 分块**：> 1MB 的图片/文件是否需要按 64KB chunk 分片存 Registry？（目前 bucket 层可直接承接，不在 P11 MVP 范围。）
3. **回填失败的冲突处理**：HYBRID 模式下 bucket 读出来的 byte[] 与 Registry 已有 byte[] 内容不一致（极端情况下的人工误操作导致）——当前策略"Registry 权威覆盖 bucket"是否合理？（本阶段按此实现，冲突写 WARN；若不合理 OQ3 事务专项调整。）

## 6. 验收标准（Acceptance Criteria）—— 仅 rule 或 rubric

### Rule ACs（客观二元条件 = 通过 / 不通过）
1. **AC-R1 [命名统一]**：`RegistryMetaPaths.java` 中存在 `DATA_ROOT = "/orm/data"` 常量，且存在 3 个 public static helper 方法（`dataRowPayload` / `dataColumnPayload` / `dataGroupPayload`），方法返回 String，签名不返回 null。证据：`mvn -pl p2p-db -Dtest=P11_DataKeySpaceTest#ac_r1_paths_exist test` 输出 PASS。
2. **AC-R2 [零外泄]**：在 p2p-db src/main/java 代码库中 grep 字符串 `"/orm/data"`，只允许 **RegistryMetaPaths.java 1 处命中（常量声明）**，其他所有文件 0 命中（由 c09 Gatekeeper 中的 grep 子断言实锤）。证据：c09 Gatekeeper assertions 全 PASS。
3. **AC-R3 [行存 roundtrip 正确性]**：HYBRID 模式下 `DsDatabaseLocal.putEntity(user)` → `getTable(User.class, id)` 返回对象与原对象 deep equals；且**同一 byte[] 通过 dataBackend.readLeafBytes 读取与 bucketStore.get 读取逐字节 compare 相等**。证据：`P11_RowDataTest#t01_put_get_roundtrip` PASS。
4. **AC-R4 [列存 roundtrip 正确性]**：HYBRID 模式下 `ColumnarStore.putValue("User", "age", rowId, ageBytes)` → `getValue(...)` 返回 byte[] 与 put 时逐字节 compare 相等。证据：`P11_ColumnDataTest#c01_put_get_roundtrip` PASS。
5. **AC-R5 [列组 roundtrip]**：复合组 `cgAgeLen` 2 个 item 打包写入 `dataGroupPayload`，读回 group byte[] 解包后两字段逐字节一致。证据：`P11_GroupDataTest#g01_group_roundtrip` PASS。
6. **AC-R6 [HYBRID 回填]**：先在 YAML 模式下 `putEntity(user)`（写 bucket，Registry 零）→ 切 HYBRID 模式重启 JVM→`getTable(id)` 后，Registry 对应 `dataRowPayload` leaf 存在（非空），内容与 bucket 读回 byte[] 逐字节一致。证据：`P11_RowDataTest#t04_hybrid_backfill` PASS。
7. **AC-R7 [双删]**：HYBRID 模式 removeTable(id) 后，Registry `dataRowPayload` leaf 不存在，bucket `space/type/id` get 返回空 byte[] 或抛出 NotFound。证据：`P11_RowDataTest#t03_remove_double_delete` PASS。
8. **AC-R8 [三态一致性]**：三个 mode（yaml / registry / hybrid）各自跑 putEntity→getTable roundtrip 全 PASS，0F0E。证据：`P11_RowDataTest#t02_three_mode_roundtrip`（3 parameterized runs）全 PASS。
9. **AC-R9 [facade 0 改（c09 Gatekeeper 钉死）]**：DsDatabaseLocal 24 个核心 public 方法签名（putEntity/getTable/removeTable/getTables/queryIds/registerTable/close/bucketStore 等）的 `method.toGenericString()` 与 P10 收官基线 capture 文件逐字符串 compare 相等；ColumnarStore 20 个核心 public 签名同理；RegistryMetaPaths public 常量≥11 且 100% String。证据：`P11_ColumnMetaTest#c09_ng3_gatekeeper_data`（25 assertions）PASS。
10. **AC-R10 [JV 回归 0F0E]**：P10 38 tests 基线（P10 RowMeta 6 + ColumnMeta 8 + MetaBackendPerf 1 + P7 ExtAttrs 12/ORM 6/Tgt3 7/Tgt5 7 + ColumnarStoreTest 8 + Compression 4 = 38）在 P11 代码上合跑 mvn，输出 `Tests run: 38, Failures: 0, Errors: 0`。证据：`p11-joint-verification-2026XXXX.log` 项目根留存。
11. **AC-R11 [P11 新增专项 12/12 PASS]**：P11_RowDataTest 4 + P11_ColumnDataTest 4 + P11_GroupDataTest 4 = 12 @Test，0F0E。证据：JV 日志分项 PASS。
12. **AC-R12 [回退等价]**：`-Dp2p.meta.mode=yaml` 下跑 38 JV 基线，结果**字节级与 P10 收官日志完全一致**（Tests run 数 0 diff，Failures/Errors 同 0）。证据：`p11-fallback-baseline-2026XXXX.log` vs `p10-joint-verification-20260921.log` diff 为空。

### Rubric ACs（评分制，需打分 + 理由 + 证据）
13. **AC-U1 [小对象写入 P99（行 128B / 列 64B）]**：独立双 tmp 根（pureBucketRoot vs registryRoot），N=5000 warmup=2000，同一 JVM 跑 5 轮，取 P99 均值计算 ratio = `registryP99 / pureBucketP99`。
   - Scale 0-2：**2=ratio≤1.05（Registry 比 bucket 还快 5% 以上，P10 RowIdSeq deg=-14.91% 同量级，优秀）；1=1.05<ratio≤1.20（达到 G3 目标门槛，可接受）；0=ratio>1.20（不通过，需优化 putBytes 内 INLINE 策略或 valueBinaries 写入 free-ring cache）**。
   - Pass Threshold：≥ 1。
   - Evidence Source：`P11_DataBackendPerfTest` System.out 打印 5 轮 ratio 均值。
14. **AC-U2 [冷启动元+值解析（1000 表 × 首行载入）]**：重启 JVM 3 轮 × 两模式（YAML baseline vs HYBRID registry），取总耗时均值 ratio = `hybridTotalMs / yamlTotalMs`。
   - Scale 0-2：**2=ratio≤0.60（快 40% 以上）；1=0.60<ratio≤0.75（达到 NFR-P5 门槛，快 ≥25%）；0=ratio>0.75（未达成，需优化 listChildren 批量预取或 per-table mkdirs 合并）**。
   - Pass Threshold：≥ 1。
   - Evidence Source：`P11_ColdBootPerfTest` 3 轮 ratio 均值打印。

---
* 以上 AC 14 条（12 rule + 2 rubric）= P11 VERIFIED CLOSED 的**唯一通过标准**，缺一不可。
* 独立 Review 阶段将由 fresh agent 实锤每一条 AC，不允许 implementer 自证。
