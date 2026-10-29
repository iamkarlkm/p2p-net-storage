# P46 OQ1 列存/服务端聚合下推（`ColumnarAggRowSource`：把聚合行源下沉到列存/分布式客户端）—— spec.md（**REV1 已签批：Gate A ✅**）

> 用户 OQ12-㉔ 候选项 ①「列存/服务端聚合下推」（承接 P45 `e76d062` 拆分后剩余的「列存/服务端聚合下推」一半，另一半「FT 原生行源」已由 P45 闭环）。
>
> **状态（2026-10-30）**：Gate A ✅ 已签批（Q1=方案 A / Q2=双模）；Implement T1-T6 ✅ 完成（P46 own **31/31 TR 0F0E**）；Gate B ✅ 拆 4 批全量回归完成（合计 **790 run / 1F（已知基线）/ 0E**）；Gate C 进行中。

## Gate C 9Y 自检清单（Gate C 逐项置 `[x]` 并附实锤测试名）

- [x] Y1 ZeroLeak3=3 + 六重递归文件数守恒 + `rowsource/` 子包文件数：`P46_T5.y1_ZeroLeak3AndSixFoldFileCountGuard`
- [x] Y2 NG-3 零依赖（0 新增 netty/grpc/p2p.client 引用 + pom 0 新增）：`P46_T5.y2_Ng3_RowsourceImportsJdkAndFirstPartyOnly`
- [x] Y3 TOPK 铁律（0 改 K、0 触 `SearchOptions`、`bucketAccessCounter` 0 污染）：`P46_T5.y3_TopKGuardrails_NoBucketCounterAndSearchOptionsReadOnly`
- [x] Y4 Facade 单调不减（P39 `agg/` 6 类 / P41 `groupby/` 5 类 / P28 DHQC / P31 join 源码 0 改）：`P46_T5.y4_FacadeMonotonic_UpstreamIntact`
- [x] Y5 路由公式（0 改 `ShardRouter` / P24 UNIT·DOC 路由 / 0 改 `DsIdLayout`）：`P46_T5.y5_RoutingFormula_ShardRouterAndIdLayoutIntact`
- [x] Y6 P21~P46 编译兼容：`P46_T5.y6_P21ToP46CompileCompat_ClassesExist`
- [x] Y7 `HybridHit` ctor=2：`P46_T5.y7_HybridHit_ConstructorCountIs2`
- [x] Y8 `ShardTransport` 5 方法：`P46_T5.y8_ShardTransport_5OriginalMethodsIntact`
- [x] Y9 0 TODO/FIXME/HACK（P46 新增源文件）：`P46_T5.y9_NoTodoFixmeHackInRowsource`

---

## 0. 需用户裁决（Gate A 的唯一开放项，批准即冻结）

> **背景（Probe 实锤，§11）**：
> 1. `AggRowSource<T extends DsTableAdapter>`（`agg/AggRowSource.java:29-37`）是聚合执行的行源抽象；`AggExecutor`（`agg/AggExecutor.java:58-155`）与 `GroupByExecutor`（`groupby/GroupByExecutor.java:70-157`）**只依赖该接口**，二者源码 0 改即可被新行源驱动。
> 2. 现有两实装：`GenericManagerRowSource`（`agg/GenericManagerRowSource.java:53-55` → `gm.findList(toWrapper(minterm))`，**ORM `QueryWrapper` 路径**）与 P45 `FulltextAggRowSource`（`rowsource/FulltextAggRowSource.java`，**FT 路径**）。**尚无列存/服务端路径的行源**。
> 3. **列存/服务端下推入口已存在但未被聚合引擎使用**：`DsDatabaseClusterClient.queryIds(String entityClassName, DbQuery query, int offset, int limit)`（`columnar/DsDatabaseClusterClient.java:201`，**全集群分区路由 + 服务端 id 下推**）+ 单分区重载（`:228`）。
> 4. **谓词模型已具备**：`DbQuery{where, anyOf, orderBy}`（`p2p-core .../model/DbQuery.java:6-15`）+ `DbQueryCriterion{op,name,a,b,list}`（`DbQueryCriterion.java:6-23`）+ `DbQueryOp{EQ,NE,GT,GE,LT,LE,BETWEEN,IN,NOT_IN,LIKE,IS_NULL,IS_NOT_NULL}`（`DbQueryOp.java:3-18`）——**与 `ConditionNode` 的 ORM op 集可 1:1 映射**。
> 5. **物化入口已具备**：`GenericManager.getById(Serializable)`（`integration/GenericManager.java:470-481`）**分布式感知**（`isDistributed()` → `distClient.getTable`），可对 `queryIds` 返回的全局 rowId 直接物化。
> 6. **红线冲突点**：`agg/` 递归文件数 = **6 已锁死**（Y1），新类须落 P45 新开的兄弟子包 `cluster/rowsource/`（现 = 1）。

| 方案 | 做法 | 代价 | 结论 |
|------|------|------|------|
| **A（推荐）** | **列存/服务端 id 下推行源**：新增 `rowsource/ColumnarAggRowSource<T>`（`implements AggRowSource<T>`）；`fetch` 把 minterm → `DbQuery`（谓词下推）→ `DsDatabaseClusterClient.queryIds(...)`（**服务端分区路由 + id 下推**）→ 逐 id `gm.getById` 物化 → 按 docId 升序。纯 FT 节点 → UOE（FT 走 P45 `FulltextAggRowSource`，职责分离） | 仅 +1 类（`rowsource/`=2，属 P45 新子树，不破六重守恒）；**0 改 `DbQuery`/`DbQueryOp`/`DsDatabaseClusterClient`/`GenericManager`**；0 新 RPC/handler；0 触 p2p-core | **建议采纳** |
| B | **服务端聚合下推（handler 级）**：新增 P2PCommand + 服务端聚合 handler（接收 `AggQueryPlan` 在服务端跑 `AggExecutor` 后回传 `AggResult`），客户端用 `AggMerger.merge` 合并 | 需扩 `DbQuery`（加 agg/groupBy 字段）+ 新 `P2PCommand` + 新 handler（`p2p-core` server 端）+ 新 model → **触 R3（上游 0 改）/ NG-3 / 文件数守恒 / R10（服务端执行）**，风险与工作量远高于 A | 备选（**不推荐**；建议挂后续 OQ） |
| C | **A + B 全量** | 体量过大，违反 KISS / 最小侵入 | 不推荐 |

**裁决问题 Q1**：采纳 **A**（列存/服务端 id 下推**行源**，自包含）还是 **B**（服务端**聚合 handler** 下推）或 **C**（全量）？

**裁决问题 Q2（本地/分布式双模）**：`ColumnarAggRowSource` 是否**同时支持**「有 `DsDatabaseClusterClient`（走 `queryIds` 服务端下推）」与「无客户端（回退 `gm.findList` 本地路径，与 `GenericManagerRowSource` 逐字段等价）」双模？**推荐双模**（保证无列存集群时仍可用 + 便于单元测试）。请确认。

---

## 1. 目标 / Scope

### 1.1 目标（一句话）
新增 `ColumnarAggRowSource<T>`，让**不含 FT 的单 minterm** 可经**列存/分布式客户端**（`DsDatabaseClusterClient.queryIds` + `DbQuery` 谓词）解析候选 id 并物化行，被 `AggExecutor` / `GroupByExecutor` 直接执行，填补 P39/P41 执行引擎在**列存/服务端**侧的行源空白（例：` COUNT(*) WHERE price>100 GROUP BY category` 直接跑在列存集群上）。

### 1.2 Scope（P46 MVP 内）
1. 新增 `com.q3lives.ds.database.fulltext.cluster.rowsource.ColumnarAggRowSource<T>`（`implements AggRowSource<T>`）。
2. **minterm → `DbQuery` 映射**（谓词下推）：ORM 节点 `OP_EQ→EQ / OP_NE→NE / OP_GT→GT / OP_GE→GE / OP_LT→LT / OP_LE→LE / KW_BETWEEN→BETWEEN / KW_IN→IN`；`IS_NULL→IS_NULL / IS_NOT_NULL→IS_NOT_NULL`；FT 节点 → `UnsupportedOperationException`（边界：FT 由 P45 行源承担）。
3. **服务端 id 下推**：`colClient.queryIds(entityClassName, dbQuery, 0, Integer.MAX_VALUE)` → 候选全局 rowId。
4. **物化**：逐 id `gm.getById(id)` → 行列表；`getById == null` → 抛 `IllegalStateException`（强一致，对齐 P45 R7）。
5. **双模（Q2）**：`colClient == null` → 回退 `gm.findList(GenericManagerRowSource.toWrapper(minterm))`（与 `GenericManagerRowSource` 逐字段等价）。
6. 结果**按 docId 升序**返回（确定性 R6）。

### 1.3 MVP 边界（**不做**，防 scope creep）
- **不做** 服务端**聚合 handler**（方案 B → 若采纳 B 则另起 REV；默认挂后续 OQ）。
- **不做** 列存**逐列扫描/投影下推**（无 `ColumnarStore.scan/filter` API；新增属大改，挂后续 OQ）。
- **不做** 扩 `DbQuery`（0 加 agg/groupBy 字段）。
- **不做** FT 条件（UOE，职责归 P45 `FulltextAggRowSource`）。
- **不做** 分布式 FT 跨 shard 聚合合并（沿用 P39 范式：各 shard `execute` 后业务方 `AggMerger.merge`）。
- **不做** 段格式变更 / 0 新 Maven dep / 0 新线程。

---

## 2. 红线（Gate A 批准即冻结，0 越界）

- **R1 架构守恒**：ZeroLeak3=3（`fulltext/` 根包独立类仍严格 {TermDictStore, FulltextStore, FulltextSegmenter}）；**六重递归文件数守恒** `segment/`=5 / `cluster/parser/v3/`=5 / `cluster/agg/`=6 / `compact/`=5 / `cluster/groupby/`=5 / `layered/`=3 **全部不变**；P46 只在 P45 新子树 `cluster/rowsource/` 内 **+1**（1 → 2）。
- **R2 Facade 单调不减**：P39 `agg/` 6 类 / P41 `groupby/` 5 类 / P28 `DsHybridQueryClient` / P31 `join/` / P45 `rowsource/FulltextAggRowSource` **源码 0 改**（复用其 `public static toWrapper`，0 新增可见性放宽）。
- **R3 上游 0 改**：P37~P45（`segment/`·`compact/`·`groupby/`·`layered/`·`FulltextStore`·`rowsource/FulltextAggRowSource`）+ `columnar/*` + `javax.net.p2p.model.DbQuery/DbQueryOp/DbQueryCriterion` + `GenericManager` **源码 0 改**。
- **R4 度量与签名冻结**：`DslToken.values().length==25`、`ConditionNode` 8 IAE、`AggQueryPlan` 公开实例字段 {clause, aggs}、`AggExecutor.execute` 3 参、`AggMerger.merge` 1 参、`HybridHit` ctor=2、`ShardRouter` UNIT/DOC 路由公式、`ShardTransport` 5 方法、`DsDatabaseClusterClient.queryIds` 2 重载签名、`DbQueryOp` 14 枚举 —— **全部 0 改**。
- **R5 段格式 0 变更**：复用既有 `MAGIC="DIDX"`/`VERSION=1`/4×CRC32（P46 不触段）。
- **R6 结果确定性**：`fetch` 返回**按 docId 升序**；NULL 语义沿用 P39/P41（聚合列 `NaN` 忽略）。
- **R7 列存↔ORM 强一致**：`queryIds` 返回 id 经 `gm.getById(id)` 为 `null` → **抛 ISE 不静默**（对齐 P45 R7 / `DsHybridQueryClient.java:209-212`）。
- **R8 无状态 & 0 新线程**：`ColumnarAggRowSource` 无实例可变状态；每次 `fetch` 纯函数式；0 `ExecutorService`/`Timer`（`queryIds` 内部复用既有 P2P 客户端）。
- **R9 TOPK 铁律**：`bucketAccessCounter[1025]` 0 污染；**0 修改**传入的 `SearchOptions`（只读；P46 不触 FT）。
- **R10 代码卫生**：新增源文件 0 `TODO/FIXME/HACK`；0 `println`/`printStackTrace`。

---

## 3. 架构（0 新 Maven dep；P45 子包内 +1 类）

```
fulltext/cluster/
├── agg/                     # P39：6 类（锁定，0 改）
│   ├── AggRowSource.java            （interface，0 改 —— 新类 implement）
│   ├── GenericManagerRowSource.java （0 改；复用其 public static toWrapper）
│   ├── AggExecutor.java / AggMerger.java / AggResult.java / AggValue.java（0 改）
├── groupby/                 # P41：5 类（锁定，0 改）
├── rowsource/               # P45 新子树（P46：1 → 2）
│   ├── FulltextAggRowSource.java    （P45，0 改）
│   └── ColumnarAggRowSource.java    （★ P46 新增，implements AggRowSource<T>）
├── join/ / parser/ / resilience/ / benchmark / *.java（全部 0 改）
columnar/                    # 0 改（仅调用其公开 queryIds）
```

**类清单（P46 新增 = 1）**：`ColumnarAggRowSource<T extends DsTableAdapter>`。

---

## 4. 数据流

```
DnfClause.minterm(i)          // List<ConditionNode>（AND，无 FT）
        │
        ├─ 校验：null/空/含 null 节点 → IAE；含 FT 节点 → UOE
        │
        ├─ colClient == null（本地双模）
        │     ⇒ gm.findList( GenericManagerRowSource.toWrapper(minterm) )      // ≡ GenericManagerRowSource
        │
        └─ colClient != null（服务端下推）
              dbQuery = toDbQuery(minterm)                                     // 谓词下推（ORM op → DbQueryOp）
              ids     = colClient.queryIds(entityClassName, dbQuery, 0, MAX)   // 服务端分区路由 + id 下推
              rows    = gm.getById(id) ∀ id（miss ⇒ ISE）
        │
        ▼
mock/real rows ⇒ 按 docId 升序
        │
        ▼
AggExecutor / GroupByExecutor（0 改）：union 去重(PK) → 5 聚合 / 分组
```

---

## 5. 接口契约

```java
package com.q3lives.ds.database.fulltext.cluster.rowsource;

public final class ColumnarAggRowSource<T extends DsTableAdapter>
        implements AggRowSource<T> {

    /**
     * @param gm          通用管理器（非 null；用于 getById 物化 + 双模回退）
     * @param colClient   列存/分布式客户端（可 null；null → 回退 gm.findList 本地路径）
     * @param entityClassName 实体类名（colClient != null 时必填非空）
     * @throws IllegalArgumentException gm 为 null，或 colClient != null 而 entityClassName 空白
     */
    public ColumnarAggRowSource(GenericManager<T> gm,
                                DsDatabaseClusterClient colClient,
                                String entityClassName);

    /**
     * @param minterm AND 条件列表（非 null 非空，无 null 节点，无 FT 节点）
     * @return 命中行（非 null；按 docId 升序）
     * @throws IllegalArgumentException minterm 非法
     * @throws UnsupportedOperationException 含 FT 节点（FT 走 FulltextAggRowSource）
     * @throws IllegalStateException    queryIds 命中 id 无对应实体（违反强一致）
     * @throws UncheckedIOException     colClient.queryIds 抛 IOException（包装，0 改接口签名）
     */
    @Override public List<T> fetch(List<ConditionNode> minterm);
}
```

> **注（编译兼容）**：`AggRowSource.fetch` **不抛受检异常**（`agg/AggRowSource.java:37`），而 `DsDatabaseClusterClient.queryIds` 抛 `IOException` → 包内以 `UncheckedIOException` 包装（沿用 P45 §5 注范式，0 改接口签名）。
>
> **注（值序列化）**：`DbQueryCriterion.a/b` 为 `String`，`ConditionNode.ormValueA/B` 为 `Object` → 映射时 `String.valueOf(...)`（`null` 语义按 `IS_NULL`/`IS_NOT_NULL` 分支处理）。此为 Gate A 需知悉的实现约束。

---

## 6. 测试计划（31 TR，Gate B 拆 4 批）

| 批次 | 文件 | 数量 | 覆盖 |
|------|------|------|------|
| T1 | `P46_T1_ColumnarRowSourceSkeletonTest` | 5 | ctor IAE 矩阵（gm null、colClient!=null 且 entityClassName 空白、colClient null 双模）；`fetch` IAE（minterm null/空/含 null）；含 FT → UOE；初始零调用 |
| T2 | `P46_T2_DbQueryPushdownEquivalenceTest` | 6 | minterm→`DbQuery` 逐 op 映射全等（EQ/NE/GT/GE/LT/LE/BETWEEN/IN/IS_NULL/IS_NOT_NULL）；双模本地路径 ≡ `GenericManagerRowSource` 逐字段全等；服务端路径 id 集合正确；空结果；miss → ISE；结果按 docId 升序 |
| T3 | `P46_T3_ExecutorIntegrationTest` | 3 | `AggExecutor` 端到端（列存条件 COUNT/SUM）；`GroupByExecutor`（列存条件分组 + HAVING）；minterm 重叠 → union 按 PK 去重 |
| T4 | `P46_T4_E2eColumnarAggTest` | 3 | 真实 `DsDatabaseLocal`/列存全链路（`AggParser.parse → AggExecutor.execute`）vs 手算真值；隔离目录零重建；跨进程 `fingerprint` 64-hex 一致 |
| T5 | `P46_T5_Gatekeeper9YTest` | 9 | 9Y 全 Y（R1~R10 红线的反射/文件计数实锤） |
| T6 | `P46_T6_NFR_Test` | 5 | `fetch` 均值阈值；1 类 Javadoc ≥10 行；CPD 160 字符窗口 0 重复块；0 println + IAE ≥N；6 场景 100% |

### 6.1 验收门槛（实跑回填，禁止预填）
- [x] P46 own **31/31 TR 0F0E**：T1 5/5 + T2 6/6 + T3 3/3 + T4 3/3 + T5 9/9 + T6 5/5（BUILD SUCCESS，`Tests run: 31, Failures: 0, Errors: 0, Skipped: 0`）
- [x] Gate B 批1 P22-P24：`76 run / 0F / 0E`（BUILD SUCCESS）
- [x] Gate B 批2 P25-P27：`80 run / 0F / 0E`（BUILD SUCCESS）
- [x] Gate B 批3 P28-P30：`100 run / 1F / 0E`（唯一失败 = 已知基线 `P28_T6.tr64_noBarePrintAndGte5NullGuards`）
- [x] Gate B 批4 P31-P46：`503 run / 0F / 0E`（BUILD SUCCESS，含 P46 own 31 TR）
- [x] **Gate B 合计**：`790 run / 1F（已知基线）/ 0E`

---

## 7. 逆指标 / NFR

- 本地双模路径（`colClient == null`）：`fetch` **均值（avg）** ≤ `GenericManagerRowSource` **+5%**（沿用 P45 D1 均值口径，规避 P95 抖动敏感性）。
- 服务端路径（`colClient != null`）：NFR 以「mock `queryIds` 返回 K 个 id + `getById` 桩」定阈值（⏳ Gate A 后可细化）。
- CPD 160 字符窗口 **0 重复块**（项目既定范式）。

---

## 8. 风险与回退

| 风险 | 缓解 |
|------|------|
| 方案 A 与 `GenericManagerRowSource` 语义重叠（`findList` 分布式亦走 `queryIds`） | 明确 A 的独立价值 = **绕开 `QueryWrapper` 层、以列存客户端 `DbQuery` 谓词直推**；并在 T2 以「双模逐字段等价」实锤一致性 |
| `DbQueryCriterion.a/b` 为 String，值序列化丢精度（如 `BigDecimal`/`Date`） | MVP 以字符串谓词为准；T2 覆盖数值/字符串；文档显式标注 |
| `fetch` 受检异常与接口不兼容 | `UncheckedIOException` 包装，0 改接口签名（§5 注） |
| 回归污染 | Gate B 拆 4 批；P46 own 独立 Surefire 阶段 |

**回退**：若 Q1 裁决 B（服务端聚合 handler）→ 规格整体重写（REV2，含 p2p-core 改动）并重新送 Gate A。

---

## 9. 签批区（用户 VERBATIM）

- [x] 采纳 P46 为 OQ12-㉔ 候选项 ①（列存/服务端聚合下推）
- [x] **Q1**：采纳 **方案 A**（列存/服务端 id 下推行源 `ColumnarAggRowSource`；非 B/C）
- [x] **Q2**：认可 **双模**（`colClient != null` 走服务端 `queryIds`；`null` 回退 `gm.findList`）
- [x] 认可 §1.3 MVP 边界（不做服务端聚合 handler / 列存逐列扫描 / 扩 `DbQuery`）
- [x] 认可 §5 `UncheckedIOException` 包装 + 值 `String.valueOf` 序列化约束（0 改接口签名）
- [x] 认可 31 TR × 6 批测试计划与 4 批 Gate B 口径
- [x] 授权进入 Implement（T1-T6）

**用户批准原文（VERBATIM）**：

> Q1 ：采纳 方案 A （列存/服务端 id 下推行源`ColumnarAggRowSource` ）
> 继续

---

## 10. 实装偏差表（Implement 后回填）

| 编号 | 计划 | 实装 | 原因 |
|------|------|------|------|
| D1 | 新类仅落 `rowsource/ColumnarAggRowSource`（rowsource 1 → 2） | 一致；**连带更新 P45 `P45_T5_Gatekeeper9YTest`** 的 `rowsource/` 源文件数守卫 `1 → 2`（`y1`+`y2`）与 `y2` import 白名单（+ `javax.net.p2p.model.`） | P45_T5 原钉死 `rowsource=1`（P45 时点快照），P46 合法新增 1 类后该守恒计数须同步推进至 2（对齐「六重递归文件数守恒」范式；P44_T5 等早期 T5 不含 `rowsource` 计数，故仅 P45_T5 受影响）。**仅测试守卫更新，0 改上游主源码** |
| D2 | T4 聚合列 NULL 承载方式未指定 | 聚合列 `price` 以**文本列** `price_txt` 承载（`Double.parseDouble`，`null` 保留） | `Double` 字段的 `null` 不能经真实存储往返（会变非 null），沿用 P39 T4 文本列范式以保证 `COUNT` 语义正确 |

---

## 11. Probe 锚点（实锤文件/行）

1. `agg/AggRowSource.java:29-37` —— 行源接口，`fetch(List<ConditionNode>) → List<T>`，**不抛受检异常**。
2. `agg/GenericManagerRowSource.java:53-55`（`gm.findList(toWrapper(minterm))`）；`:67` `public static toWrapper(...)`（P45 Q1 已放宽为 public）；`:80-84` FT → UOE。
3. `agg/AggExecutor.java:58-155` —— 只依赖 `AggRowSource`，union 去重 + 5 聚合。
4. `groupby/GroupByExecutor.java:70-157` —— 同上，分组 + HAVING。
5. `rowsource/FulltextAggRowSource.java:48-190` —— P45 FT 原生行源（三条路径范式 + `intersectFtDocIds` + `sortedByDocId`）。
6. `columnar/DsDatabaseClusterClient.java:201` `queryIds(entityClassName, DbQuery, offset, limit)`；`:228` 单分区重载；`:96/118/134` `putCol/getCol/removeCol`。
7. `p2p-core .../model/DbQuery.java:6-15`（`where/anyOf/orderBy`）；`DbQueryCriterion.java:6-23`；`DbQueryOp.java:3-18`（14 枚举）。
8. `integration/GenericManager.java:470-481` `getById`（分布式感知）；`:1935-1953` `findList(QueryWrapper)`；`:3433-3452` `listIds(QueryWrapper)`；`:3260-3351` `aggregateByComposite`（P17 T4 复合索引前缀分组聚合，package-private）。
9. `adapter/DsTableAdapter.java:28-106` —— 实体基类（`getId`/`setId`/`toBytes`/`load`）。
10. 文件数红线锚点：`agg/`=6（AggExecutor/AggMerger/AggResult/AggRowSource/AggValue/GenericManagerRowSource）；`groupby/`=5；`parser/v3/`=5；`rowsource/`=1（P45）。
11. Roadmap OQ12-㉔ 候选项 ①（`docs/dsdb-design-status-and-roadmap.md:461`）。
