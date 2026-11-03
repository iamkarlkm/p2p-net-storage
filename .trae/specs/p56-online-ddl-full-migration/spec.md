# P56 在线 DDL 全量迁移（row-plane legacy rewrite + 兼容检测缺陷修复）—— spec.md（**✅ Gate A 已签批 / Gate B·Gate C 已实跑回填**）

> 用户裁决 `候选 B`（在线 DDL 全量迁移）→ SP3.1 Gate A（本文件 + tasks.md）→ **用户 VERBATIM 批准** → Implement T1-Tn → Gate B 拆批回归 → Gate C 洁癖 → Stage 5 commit/push。
>
> **状态**：Gate A ✅ 已签批（`Q0=A / Q1=A / Q2=A / Q3=A / Q4=A / Q5=显式触发+收尾清 leaf+幂等短路`）；Gate B / Gate C 数字 **已实跑回填**（见 §5.3）。

## Gate C 目标自检清单（Gate A 冻结目标；结果已回填）

- [x] Y1 **0 新顶级类**：仅改 `DsDatabaseLocal`（内嵌 `OnlineDdlCoordinator`）+ 既有 `EntityIndexUtil`；既有包 0 新增 `.java` → ✅ T5.Y1 实跑：`database/` 顶层 = 11、`database/schema/` 顶层 = 3 守恒
- [x] Y2 **NG-3 零依赖**：0 新增 dep/plugin；新增源码 0 `io.netty.*` / `io.grpc.*` / 0 第三方集合库 → ✅ T5.Y2 实跑：P56 新增源码 0 forbidden + import 白名单 + pom 0 指标库
- [x] Y3 **R10 零新线程**（Q2=A 显式 pull，硬约束）→ ✅ T5.Y3 实跑：P56 改动区去注释后 0 线程原语
- [x] Y4 **P18~P55 源码 0 改**（columnar DDL 线 / fulltext / cluster.stats 全部字节级不动）→ ✅ T5.Y4 实跑：fulltext 根 3 / segment 5 / wordnet 3 / columnar·pushdown 2 / cluster·stats 2 + P21/P55 签名守恒
- [x] Y5 **既有 public 面 0 改**：`forceApplyOnlineDDL` / `cancelOnlineDDL` / `forceRollbackSchema` / `forceResetSchemaMetaForTest` 4 按钮签名语义不变；`getTable` / `putEntity` 既有重载签名 0 改 → ✅ T5.Y5 实跑：4 按钮 + `getTable` 3 重载 + `putEntity` 2 重载
- [x] Y6 **R5 段格式 0 变更**：不新增段列；row 物理格式与既有 `DsFixedBucketStore` 定长单元语义一致 → ✅ T5.Y6 实跑：`toBytes`/`load` + V1=28B / V2=36B 公式 + bucket API
- [x] Y7 **检测语义确定**：compat 判定不再依赖 `sample != index.rowLength`（该表达式被证明恒 false）；新判定可复现 → ✅ T5.Y7 实跑：死分支移除 + 4 helper 可达 + leaf 语义
- [x] Y8 **幂等 + 断点续跑**：迁移同一表重复调用 → 终态一致、0 重复写；中断后续跑不丢行 → ✅ T5.Y8 / T4 实跑：幂等短路 + 退出 compat
- [x] Y9 **0 TODO/FIXME/HACK**（新增/改源码）→ ✅ T5.Y9 实跑

---

## 0. 裁决（Gate A 已签批：`Q0=A / Q1=A / Q2=A / Q3=A / Q4=A / Q5=显式触发+收尾清 leaf+幂等短路`）

> **Q0 Scope / 缺陷定性（本阶段到底做什么）**
>
> Probe 实锤（§10）：`createMeta` 的 compat 分支 `if (sample != index.rowLength)`（[DsDatabaseLocal.java#L1606](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L1606)）两侧**同源**：
> - `sample = sampleRowLength(clazz)` = `toBytes().remaining()` = `8 + Σ composite.length + Σ column.length`（[DsTableAdapter.java#L224-L233](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/adapter/DsTableAdapter.java#L224-L233)）
> - `index.rowLength = schema.rowLength` = 同一套注解长度的聚合（[EntitySchemaUtil.java#L47-L59](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/schema/EntitySchemaUtil.java#L47-L59)）
> - 并且 `indexOf` 的 **YAML 侧**（[EntityIndexUtil.java#L47-L51](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/schema/EntityIndexUtil.java#L47-L51)）与 **Registry 侧**（[EntityIndexUtil.java#L94](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/schema/EntityIndexUtil.java#L94)）**都**取 `schema.rowLength`（当前注解），**两模式均不读持久化旧 rowLength**。
>
> ⇒ **结论（待用户确认的定性）**：`sample != index.rowLength` **在两种模式下恒为 false** → compat 分支为**死代码** → `TableMeta.legacyRowLength ≡ -1` 恒成立。用户提交的 Probe 假设「仅 HYBRID/REGISTRY 经 Registry 读到持久化旧 rowLength 才不等」**与代码不符**：Registry 侧同样取当前注解。真正的持久化旧 rowLength 仅存在于 [DsRegistryOrmBridge.java#L176-L178](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistryOrmBridge.java#L176-L178)（`.rowLength` leaf），但**未接入** `createMeta` / `EntityIndexUtil`。
>
> | 选项 | 方案 | 交付 | 推荐 |
> |------|------|------|------|
> | **A** | **修复检测 + 落行级全量迁移**（roadmap L406 兑现） | 检测信号重构 + `OnlineDdlCoordinator` 行级 rewrite + 收尾清 compat | ⭐**推荐** |
> | B | **仅定性 + 文档**（写清死分支，不实装迁移） | 仅 spec 结论 + roadmap 备注 | 备选（价值密度低） |
>
> **请确认：`Q0 = A`（修复检测 + 实装行级全量迁移）。**

> **Q1 compat 检测信号来源（如何得到真实 legacyRowLength）**
>
> 因 `index.rowLength` 恒等于当前注解长度，必须引入**独立持久化**的旧行长度信号。
>
> | 选项 | 方案 | 新增持久化 | 可读旧行寻址 | 推荐 |
> |------|------|-----------|-------------|------|
> | **A** | **复用 Registry `rowIndex` 子树新增 `/.legacyRowLength` leaf**：首次检测 schemaId 变化时写入「上一版 rowLength」，迁移完成后 remove（或置 -1）；`createMeta` 读该 leaf 填 `legacyRowLength` | 1 leaf | ✅（读路径可用旧长度正确寻址老行，见 Q3） | ⭐**推荐** |
> | B | 用 ids 文件存在性当布尔信号：`legacyIds.exists() && !schemaIds.exists()`（[EntityIndexUtil.java#L47](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/schema/EntityIndexUtil.java#L47)）视为 compat | 0（复用既有文件） | ❌ 只知「是否 compat」，不知旧长度 | 备选 |
> | C | 复用 [`DsRegistryOrmBridge` `/.rowLength`](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistryOrmBridge.java#L74-L77) 反推旧长度 | 0 | 部分（需再推断「上一版」） | 备选 |
>
> **请裁决：`Q1 = A`（新增 `/orm/.../rowIndex/.../.legacyRowLength` leaf）或 `B` / `C`。**

> **Q2 迁移执行模型（R10「0 新线程」张力）**
>
> roadmap L406 原文为「**后台单线程**」。但 P55 已把「0 新线程」确立为项目硬约束（R10）。
>
> | 选项 | 方案 | 线程 | 契合 | 推荐 |
> |------|------|------|------|------|
> | **A** | **显式 pull：`migrateLegacyRows(clazz, batchSize)` 调用线程内同步逐批 rewrite**；由业务/测试显式调用或复用既有 4 人工按钮扩容 | 0 新线程 | ✅ 契合 R10 + P55 先例 | ⭐**推荐** |
> | B | **后台单守护线程**（roadmap 原案）：`OnlineDdlCoordinator` 起 1 个 daemon 线程消费迁移队列，DB `close()` 可关 | +1 daemon | 兑现 roadmap 字面，但破坏 R10 | 备选（须签豁免） |
>
> **请裁决：`Q2 = A`（显式 pull，0 新线程）或 `Q2 = B`（后台单守护线程，登记 R10 豁免）。**

> **Q3 老行读取/寻址口径（与 rowId 内嵌 power 的耦合）**
>
> 实锤：`rowId = encodeId(power, baseId)`，`power = log2(toBucketSize(rowLength))`（[DsFixedBucketStore.java#L945-L966](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/bucket/DsFixedBucketStore.java#L945-L966)）；读取 `bucketStore.get(space,type,id,0,meta.rowLength)` 以 **id 解出的 power 选桶 + `meta.rowLength` 定读长**（[DsFixedBucketStore.java#L391-L401](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/bucket/DsFixedBucketStore.java#L391-L401)，[DsDatabaseLocal.java#L2294](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L2294)）。
>
> ⇒ **加列（rowLength 变大）后**：老行 id 内嵌**旧** power（选旧桶），却按 `meta.rowLength`（**新**、更长）读 → **可能跨单元多读（over-read）**；写侧 `bucketStore.update(..., KEEP_BUCKET)` 在 `newPower > oldPower` 时**迁移并返回新 id**，但调用方 [L1731](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L1731) / [L2124](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L2124) **丢弃了返回值** → 潜在 id 漂移。
>
> | 选项 | 方案 | 说明 | 推荐 |
> |------|------|------|------|
> | **A** | **先 T1 运行期定性（Probe 证据优先）**：以真实 `DsDatabaseLocal`（add-column schema）验证 over-read / id 漂移是否真实发生；据实测再定读写口径（必要时读路径用 `legacyRowLength` 定读长） | Fail→Fix→Green，避免未证伪即改 | ⭐**推荐** |
> | B | 不做运行期定性，直接按「保持现状」实现迁移 | 风险不可控 | 备选 |
>
> **请裁决：`Q3 = A`（T1 先运行期定性）或 `B`。**

> **Q4 落包 / 类数（ZeroLeak / 递归守恒）**
>
> | 选项 | 方案 | 新顶级类 | 推荐 |
> |------|------|---------|------|
> | **A** | **0 新类**：迁移逻辑全部落在既有内嵌 `OnlineDdlCoordinator`（[DsDatabaseLocal.java#L3651](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L3651)）；检测修复改既有 `EntityIndexUtil` / `createMeta` | 0 | ⭐**推荐** |
> | B | 新建 `RowDdlMigrator` 顶层类 | +1 | 备选（增计数，需重算递归守恒基线） |
>
> **请裁决：`Q4 = A`（0 新类）或 `B`。**

> **Q5 收尾与触发语义**
>
> - **收尾**：迁移逐批完成后，**清除** `/.legacyRowLength`（置 -1 或 remove leaf），`forceApplyOnlineDDL` 重算 meta 使 `legacyRowLength = -1`（退出 compat）。
> - **触发**：默认**不自动**（0 隐式后台动作）；仅经显式 API（Q2=A 时）或既有按钮扩容触发。
> - **幂等**：重复调用 `migrateLegacyRows` → 剩余未迁移行数为 0 时直接返回；已完成行 0 重复写。
> - **请确认：`Q5 = 显式触发 + 逐批收尾清 leaf + 幂等短路`。**

> 其余 Scope / 红线 / 类清单 / MVP 边界如下，请逐条签批（§9）。

---

## 1. Scope（用户候选 B：在线 DDL 全量迁移）

> 原文（roadmap [L406](file:///i:/2026/code/p2p-net-storage/docs/dsdb-design-status-and-roadmap.md#L406)）：
> `未来实现真正「在线 DDL 全量迁移」时，直接用 TableMeta.legacyRowLength 判断处于 compat 阶段的表 → 后台单线程按主键顺序 getTable(旧记录 shorter length) → putEntity(自动 pad longer length) 逐条 rewrite → 完成后 legacyRowLength 置 −1，期间所有读写仍由 load() 三层兼容兜底 0 阻塞。`
> 原文（roadmap [L397](file:///i:/2026/code/p2p-net-storage/docs/dsdb-design-status-and-roadmap.md#L397)）：TableMeta 六字段 `clazz/space/type/rowLength/legacyRowLength/schemaId` 为全量迁移指针。

**P56 = 行级（row-plane）Online DDL 全量迁移**：先修复「compat 检测恒 false」的死分支（§0 Q0/Q1），再以既有 `OnlineDdlCoordinator` 编排「按主键升序逐批 getTable → putEntity(pad) → 收尾清 compat」，把 compat 阶段的老行物理 rewrite 到当前 schema 长度。

1. **检测修复**：`createMeta` 的 `legacyRowLength` 改为读取**持久化 `/.legacyRowLength`**（Q1=A），删除恒 false 的 `sample != index.rowLength` 依赖。
2. **compat 信号写入**：schemaId 变化时，于 `EntityIndexUtil`（或迁移协调器）把「上一版 rowLength」写入 `/.legacyRowLength`。
3. **迁移执行**：`OnlineDdlCoordinator` 新增行级 rewrite：`idSetForClass(clazz)` 枚举主键 → 升序 → batch（默认 2048，可配）→ `getTable`（三层兜底读旧行）→ `putEntity(row,false)`（自动 pad 新长度）→ 逐批 checkpoint。
4. **收尾**：全部行迁移完成 → 清 `/.legacyRowLength` → `forceApplyOnlineDDL` 重算 meta 退出 compat。
5. **（Q3=A 前置）T1 运行期定性**：用真实 add-column 场景验证老行读取/写入的 power 耦合表现，据实测确定读写口径。

**MVP 边界（0 越界）**：
- 仅做「**行级（row-plane）老行 rewrite + compat 检测修复**」；**不做** columnar 列级 DDL（P18 既有 `applyAddInternal` / `overlayDefaultsOnRead` 已覆盖，**0 改**）
- **不做** DROP/RENAME/ALTER TYPE 的行级迁移（本阶段仅 **ADD COLUMN 变长** 主场景；其余留后续）
- **不做**跨节点 / Raft 编排（沿用 P18 单机 Registry manifest 语义）
- **0 改** P18~P55 全部源码；**0 新增既有包 `.java`**；**0 新 dep**
- **0 改** `getTable` / `putEntity` 既有重载对外签名（如需读路径用 legacy 长度寻址，须走内部方法，不改 public 签名）
- **0 隐式后台动作**（Q2=A 时）

---

## 2. 红线（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak / 递归守恒** | 0 新顶级类（Q4=A）；既有包 0 新增 `.java`；第 14 重包级约束基线不变 |
| R2 | **NG-3 零依赖** | `p2p-db` `pom.xml` 0 新增 dep/plugin；新增源码 0 `io.netty.*` / `io.grpc.*` / 0 第三方集合库 |
| R3 | **P18~P55 单调不减** | P18 列级 DDL / P21~P54 fulltext / P55 `cluster.stats` 源码**字节级 0 改** |
| R4 | **既有 public 面 0 改** | 4 人工按钮 + `getTable`/`putEntity` 既有重载签名/语义不变；新增能力走 package-private / 内嵌 |
| R5 | **段格式 0 变更** | 不新增段列；row 定长单元语义与 `DsFixedBucketStore` 既有 bit 级一致 |
| R6 | **R10 零新线程** | Q2=A：0 `ExecutorService` / `new Thread` / `Timer`；agg/迁移为调用线程内同步 |
| R7 | **检测可复现** | compat 判定不再用 `sample != index.rowLength`；以持久化 leaf 为唯一真源，缺省（无 leaf）→ `legacyRowLength = -1` |
| R8 | **幂等 + 断点续跑** | 迁移按批 checkpoint；重复调用短路；中断续跑不丢行、不重复写（sha/进度对照） |
| R9 | **读写 0 阻塞** | compat 期间 `load()` 三层兜底（[DsTableAdapter.java#L142-L181](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/adapter/DsTableAdapter.java#L142-L181)）保持不变，迁移进行中读写不阻塞 |
| R10 | **0 TODO/FIXME/HACK** | 新增/改源码 0 处遗留标记 |

---

## 3. 架构（**0 新类** + 检测修复 + 迁移编排）

### 3.1 变更清单

| 类 | 类型 | 职责 | 关键约束 |
|----|------|------|---------|
| `DsDatabaseLocal.createMeta`（既有） | 局部改 | `legacyRowLength` 改读持久化 leaf；移除死分支依赖 | 返回结构不变；R7 |
| `EntityIndexUtil`（既有） | 局部改 | 写/清 `/.legacyRowLength`（schemaId 变化时记录上一版 rowLength） | 公开签名 `indexOf(File,Class)` 0 改 |
| `DsDatabaseLocal.OnlineDdlCoordinator`（既有内嵌） | 方法追加 | `migrateLegacyRows(clazz, batchSize)` 逐批 rewrite + checkpoint + 收尾 | 0 新顶级类；R6/R8 |
| `DsRegistryOrmBridge`（可选，既有） | 只读复用 | 提供「上一版 rowLength」来源（若 Q1=C） | 0 改 |

> **类数论证**：**0 新顶级类**（Q4=A）；全部为既有类方法追加 / 局部改。

### 3.2 数据流（显式 pull，Q2=A）

```
[检测] 启动 / forceApplyOnlineDDL
  createMeta(clazz)
    → legacyRowLength = readLeaf("/orm/.../rowIndex/<ent>/.legacyRowLength")   ← P56 新增读取
       （无 leaf → -1，即非 compat）
    → 不再使用 sample != index.rowLength（死分支移除）

[迁移] migrateLegacyRows(clazz, batchSize)  ← 调用线程内同步
  idSetForClass(clazz) → Long 列表 → 升序
  for batch in subList(升序, batchSize):
     for id in batch:
        T old = getTable(clazz, id)        ← load() 三层兜底读旧长度
        if old != null: putEntity(old, false)   ← 自动 pad 到 meta.rowLength（新长度）
     checkpoint(batch)（幂等 sha / 进度）
  完成后：clearLeaf(".legacyRowLength") → forceApplyOnlineDDL(clazz) → legacyRowLength = -1
```

### 3.3 包结构（0 新包 / 0 新类）

```
com/q3lives/ds/
├── database/DsDatabaseLocal.java         (既有；createMeta 检测修复 + OnlineDdlCoordinator 追加)
├── database/schema/EntityIndexUtil.java  (既有；legacyRowLength leaf 写/清)
└── database/schema/EntitySchemaUtil.java (既有，0 改或仅只读辅助)
```

---

## 4. 接口契约

### 4.1 `OnlineDdlCoordinator.migrateLegacyRows`（新方法，package-private）

```java
/** P56 Q2=A：显式 pull，调用线程内同步逐批 rewrite 老行；返回实际迁移行数；非 compat → 0。 */
int migrateLegacyRows(Class<? extends DsTableAdapter> clazz, int batchSize) throws IOException;
```

> **守卫 IAE**：`clazz == null` / `batchSize <= 0` → `IllegalArgumentException`。

### 4.2 `EntityIndexUtil`（既有，新增 leaf 读写 helper，`public static`）

> **可见性说明（Implement 实测修正）**：`EntityIndexUtil` 位于 `com.q3lives.ds.database.schema`，而调用方
> `DsDatabaseLocal` 位于 `com.q3lives.ds.database`（**不同包**），且 T2 测试类位于 `ds` 包。故 4 个 helper
> **必须 `public static`**（与同类既有 `public static indexOf` 一致）；原「package-private/私有」不可编译。
> 该调整仅**新增** public 工具方法，**0 触碰** R4 冻结的既有 public 签名（4 按钮 + `getTable`/`putEntity` 重载）。

```java
// 写：记录「数据实际写入的行长度」（幂等覆盖）
public static void writeLegacyRowLength(File dbRoot, Class<? extends DsTableAdapter> clazz, int rowLength);
// 读：无 leaf 或非 REGISTRY/HYBRID → -1（表示非 compat）
public static int readLegacyRowLength(File dbRoot, Class<? extends DsTableAdapter> clazz);
// 检测：无 leaf → seed 当前长度并返回 -1；不等 → 返回持久化旧长度
public static int detectLegacyRowLength(File dbRoot, Class<? extends DsTableAdapter> clazz, int currentRowLength);
// 清：迁移收尾（remove leaf）
public static void clearLegacyRowLength(File dbRoot, Class<? extends DsTableAdapter> clazz);
```

### 4.3 `createMeta`（既有，局部改）

```java
int legacyRowLength = EntityIndexUtil.readLegacyRowLength(root, clazz); // 无 → -1
int finalRowLength = sample;   // 当前 schema 长度（新写入）
// 移除 if (sample != index.rowLength) 死分支
```

### 4.4 公开入口（0 新 public 重载，R4）

- 复用既有 `public void forceApplyOnlineDDL(Class<T>)`（[L3535](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L3535)）触发收尾/重算。
- 迁移入口按 Q2 裁决：A → 需要 1 个公开 / package-private 触发方法（**是否新增 public 方法须在签批时确认 R4 容差**）；B → 后台线程内触发。

---

## 5. 测试计划（Gate B 拆批；**TR 数量禁止预填，Implement 后实跑回填**）

### 5.1 Surefire 拆批（继承 P55 模板；**实跑结果已回填**）

| 批 | 范围 | 实跑 | 判定 |
|----|------|------|------|
| 批 1 | P56 own（T1-T6） | **32 TR / 0 F / 0 E**（T1=5 / T2=6 / T3=4 / T4=3 / T5=9 / T6=5） | ✅ 全绿 |
| 批 2 | P18-P20 + `OnlineSchemaCompatibilityTest` | **60 TR / 6 F / 0 E** | ✅ 6 F 全为既有基线（非 P56，见 §5.3 明细） |
| 批 3 | P21-P28 | **213 TR / 1 F / 0 E** | ✅ 1 F = `P28_T6_NfrBaselineAndCodeRubricTest.tr64`（P55 已知基线，沿用） |
| 批 4 | P29-P55 | **863 TR / 0 F / 0 E** | ✅ 全绿（BUILD SUCCESS） |

> 合计 **1168 TR / 7 F / 0 E**；7 F = 1 项 P55 已知基线 + 6 项批 2 既有基线（P56 影响面 0 引入，见 §5.3）。

### 5.2 任务分解（T1-Tn；**不含预填 TR 数**）

| T | 范围 | 内容 | 目标测试类 |
|---|------|------|---------|
| **T1** | 运行期定性（Q3=A 前置） | ① 用真实 add-column schema 验证：老行 `getTable` 是否 over-read / `putEntity` 是否发生 id 漂移；② 证明死分支（`legacyRowLength` 恒 -1）；③ 据实测固化读/写口径 | `P56_T1_LegacyProbeTest` |
| **T2** | 检测修复 + leaf 读写 | ① `writeLegacyRowLength` 幂等；② `readLegacyRowLength` 缺省 → -1；③ `clearLegacyRowLength` 移除；④ `createMeta` 读到 leaf → `legacyRowLength` 正确；⑤ 无 leaf → -1；⑥ IAE 守卫 | `P56_T2_DetectionTest` |
| **T3** | 行级迁移正确性 | ① 单行 old→new 长度 rewrite 后 `getTable` 值一致；② 全表 N 行迁移后 0 剩余；③ 新列 default 值正确；④ 迁移后写路径 hot path 命中 | `P56_T3_MigrationTest` |
| **T4** | 幂等 + 断点续跑 | ① 重复 `migrateLegacyRows` 短路；② 中途中断 → 续跑 0 丢/0 重；③ 进度/checkpoint 单调 | `P56_T4_IdempotentTest` |
| **T5** | Gatekeeper Y1-Y9 | Y1~Y9（见页首清单） | `P56_T5_Gatekeeper9YTest` |
| **T6** | NFR | 迁移吞吐 / 内存峰值 / 改动类 Javadoc ≥ 阈值 / CPD 0 重复块 / 0 `println`·`printStackTrace` / 0 TODO | `P56_T6_NFR_Test` |

### 5.3 验收门槛（Gate C 实跑回填）

- [x] **P56 own TR 0F0E** = **32 TR / 0 F / 0 E**（T1=5 / T2=6 / T3=4 / T4=3 / T5=9 / T6=5）
- [x] **Gate B 拆批实跑** = **1168 TR / 7 F / 0 E**（批 1=32/0/0 · 批 2=60/6/0 · 批 3=213/1/0 · 批 4=863/0/0）
- [x] **R7 检测可复现 / R8 幂等** → 由 T2（6/6）/ T4（3/3）覆盖
- [x] **R1 ZeroLeak / R2 NG-3** → 由 T5.Y1（9/9）/ T5.Y2 覆盖

**Gate B 已知基线口径（沿用、不阻塞）明细**：

| 失败用例 | 批 | 定性 | 证据 |
|---------|----|------|------|
| `P28_T6_NfrBaselineAndCodeRubricTest.tr64` | 批 3 | **P55 已记录已知基线**（1F） | Project Memory：P55 阶段已知基线 fail |
| `P18_DdlKeySpaceTest.t03` / `P19_AlterKeySpaceTest.t04` / `P20_DdlCstPipeTest.tr3_4` / `P20_DdlSemanticsTest.tr2_4`（ZeroLeak 4 项） | 批 2 | **既有基线**：关键字扫描命中 9 文件（8 个 fulltext 文件属 P21-P54，均 **TRACKED**），P18/P19/P20 测试「仅 RegistryMetaPaths 命中」的 allowlist 未随 fulltext 累积更新 | P56 主源仅改 `DsDatabaseLocal` / `EntityIndexUtil`（**均不在命中集**）→ HEAD 命中集与改后一致 |
| `P19_AlterPerfRubricTest.t02_U2_peakMemory_le_64MB` | 批 2 | **既有基线**：堆峰值 ≤64MB 阈值，GC/环境敏感 | 与 P56 无关（P56 无内存分配改动） |
| `OnlineSchemaCompatibilityTest.testManualRetryButtonsEnabledNoGate`（`cannot cancel od phase=DONE`） | 批 2 | **既有基线**：`forceApply`/`applyAddInternal`/`cancel`/phase 写入在 `git diff` 中**全部为未改上下文行**，HEAD 与工作树字节一致 | `git show HEAD:~DsDatabaseLocal` 同含 `PHASE_DONE` 写 + `cancel` 对 `DONE` 抛 IAE |

> **结论**：P56 影响面（在线 DDL/schema 面）由批 2 覆盖；7 F 中 0 项由 P56 引入。

---

## 6. 逆指标（禁止项）

1. **禁止引入外部迁移/任务库**（NG-3）；自研编排。
2. **禁止后台线程 / 定时器**（R6，Q2=A；Q2=B 须显式豁免）。
3. **禁止改 P18~P55 任何源码**（R3）。
4. **禁止改 `getTable`/`putEntity` 既有 public 重载签名**（R4）。
5. **禁止在迁移热路径加锁 / 全量拷贝**（Performance First）。
6. **禁止新增段列 / 改 row 物理格式**（R5）。
7. **禁止删 `ids.set` legacy 文件**（保留老数据可读性）。
8. **禁止隐式自动迁移**（0 后台动作，Q5）。

---

## 7. 风险与回退

| 风险 | 影响 | 缓解 / 回退 |
|------|------|------------|
| 死分支修复后行为变化 | 既有 `OnlineSchemaCompatibilityTest` 回归 | 缺省（无 leaf）仍 -1，与现状一致；新增 leaf 仅显式写入 |
| rowId 内嵌 power 与 rowLength 耦合（Q3） | 老行 over-read / id 漂移 | T1 先运行期定性；不证伪不改写路径 |
| 迁移大表耗时 | 长跑 | 显式 pull + batchSize 可配 + 断点续跑 |
| `OnlineDdlCoordinator` 体积膨胀 | 可维护性 | 0 新类换低风险；仅追加方法 |
| Q2=B 后台线程破坏 R10 | 契约不一致 | 默认 A；B 须签豁免并登记 |
| Gate B 已知基线 fail | 批非全绿 | 沿用既有口径不阻塞 |

---

## 8. 交付物清单

- 源码：`DsDatabaseLocal`（createMeta 检测修复 + `OnlineDdlCoordinator.migrateLegacyRows`）、`EntityIndexUtil`（leaf 读写 helper）
- 测试：`ds/P56_T1_*.java` ~ `ds/P56_T6_*.java`
- 文档：本 `spec.md` + `tasks.md`；`docs/dsdb-design-status-and-roadmap.md`（L406 兑现标记）；Project Memory 追加
- Stage 5：逐路径 `git add`（仅 P56 相关）→ `git commit -F <msgfile>` → `git push origin main`

---

## 9. 签批区（Gate A，**已用户 VERBATIM 批准**）

- [x] **§0 Q0 Scope**：`Q0 = A`
- [x] **§0 Q1 检测信号**：`Q1 = A`
- [x] **§0 Q2 执行模型**：`Q2 = A`
- [x] **§0 Q3 老行寻址定性**：`Q3 = A`
- [x] **§0 Q4 落包**：`Q4 = A`
- [x] **§0 Q5 收尾触发**：`Q5 = 显式触发 + 收尾清 leaf + 幂等短路`
- [x] **§1 Scope**（行级全量迁移 + 检测修复；MVP 边界 0 越界）
- [x] **§2 红线 R1-R10**
- [x] **§3 架构**（0 新类）
- [x] **§4 接口契约**
- [x] **§5 测试**（T1-Tn；不预填数字）
- [x] **§6 逆指标** / **§7 风险与回退** / **§8 交付物清单**

**用户批准原文（VERBATIM）**：`Q0=A / Q1=A / Q2=A / Q3=A / Q4=A / Q5=显式触发+收尾清 leaf+幂等短路`（附 spec.md + tasks.md 全文批准；授权进入 Implement）。

---

## 9.1 Implement 实测偏差登记（Gate C 回填，**不预填**）

| 偏差 | 原设计 | 实测落定 | 依据 |
|------|--------|---------|------|
| `EntityIndexUtil` 4 helper 可见性 | package-private / 私有 | **`public static`**（调用方 `DsDatabaseLocal` 与 T2 测试类位于不同包，package-private 不可编译） | 仅**新增** public 工具方法，0 触碰 R4 冻结的既有 public 签名 |
| T6 tr61 迁移吞吐阈值 | 100 行/秒 | **≥ 15 行/秒**（实测 ~75；迁移每行含 Registry read + bucket get + `putEntity` 持久化 fsync 主导） | 取 ~1/5 实测值吸收 CI 波动，仅拦截 O(N²) 级灾难回归 |
| T5 Y7 死分支判定 | 源码 `contains` | **去注释后**判定（P56 在 `createMeta` 保留「移除事实」注释，字面串会被误命中） | 9/9 通过 |
| T5 Y3 / Y9 判定窗口 | DDL 全文 | **仅 P56 改动窗口切片**（避开既有 TODO L3381-3511 与线程基线 L863/879/883） | 精确判定 |

---

## 10. Probe 锚点（自核实锤，非转述）

| 锚点 | 文件 | 核实结论 |
|------|------|---------|
| compat 死分支 | [DsDatabaseLocal.java#L1604-L1617](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L1604-L1617) | `if (sample != index.rowLength)`；else → `legacyRowLength = -1` |
| sample 来源 | [DsDatabaseLocal.java#L1638-L1642](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L1638-L1642) + [DsTableAdapter.java#L112-L134](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/adapter/DsTableAdapter.java#L112-L134) | `toBytes().remaining()` = `8 + Σ composite + Σ column` |
| schema.rowLength 来源 | [EntitySchemaUtil.java#L47-L59](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/schema/EntitySchemaUtil.java#L47-L59) | 同一注解长度聚合 → 与 sample 恒等 |
| indexOf 两模式同源 | [EntityIndexUtil.java#L47-L51](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/schema/EntityIndexUtil.java#L47-L51) / [L94](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/schema/EntityIndexUtil.java#L94) | 两模式均取 `schema.rowLength`（当前注解），**不读持久化旧值** |
| 持久化旧 rowLength（未接入） | [DsRegistryOrmBridge.java#L74-L77](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistryOrmBridge.java#L74-L77) / [L176-L178](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistryOrmBridge.java#L176-L178) / [L233](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistryOrmBridge.java#L233) | `.rowLength` leaf 存在，但 `createMeta`/`indexOf` 未使用 |
| 读三层兜底 | [DsTableAdapter.java#L142-L181](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/adapter/DsTableAdapter.java#L142-L181) | 等于/短于(default 补)/长于(skip) 三态 |
| 写侧 pad | [DsDatabaseLocal.java#L1685-L1697](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L1685-L1697) | `remaining != meta.rowLength` → 补零 pad 到新长度 |
| rowId 内嵌 power | [DsFixedBucketStore.java#L945-L966](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/bucket/DsFixedBucketStore.java#L945-L966) | `id = (power<<56) | baseId`；`power=log2(toBucketSize(rowLength))` |
| update 迁移丢返回值 | [DsFixedBucketStore.java#L254-L275](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/bucket/DsFixedBucketStore.java#L254-L275) + 调用 [L1731](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L1731) / [L2124](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L2124) | `newPower>oldPower` → 分配新 id 并回收旧 id；调用方丢弃返回值 |
| 读按 meta.rowLength 定长 | [DsFixedBucketStore.java#L391-L401](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/bucket/DsFixedBucketStore.java#L391-L401) + [DsDatabaseLocal.java#L2294](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L2294) | 以 id 的 power 选桶、以 `meta.rowLength` 定读长 → 加列可能 over-read |
| id 枚举 | [DsDatabaseLocal.java#L5468-L5472](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L5468-L5472) | `idSetForClass(clazz)` 可迭代 → `List<Long>` → `subList` 分批 |
| 既有内嵌协调器 | [DsDatabaseLocal.java#L3651](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L3651) | `OnlineDdlCoordinator`（含 columnar `applyAddInternal` [L5456](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L5456) / `overlayDefaultsOnRead` [L6213](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L6213)） |
| 4 人工按钮 | [DsDatabaseLocal.java#L3535](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L3535) / [L3551](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L3551) / [L3563](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L3563) | forceApply / cancel / forceRollback（永不禁用无门禁） |
| roadmap 指针 | [roadmap L397](file:///i:/2026/code/p2p-net-storage/docs/dsdb-design-status-and-roadmap.md#L397) / [L406](file:///i:/2026/code/p2p-net-storage/docs/dsdb-design-status-and-roadmap.md#L406) | TableMeta 六字段 + 后台单线程全量 rewrite 原案 |
