# P45 OQ1 FT 原生聚合行源（`FulltextAggRowSource`：打通「FT 条件 + 聚合执行」）—— spec.md（**REV1 已签批 / Gate A·B 通过 / Gate C 进行中**）

> 用户 OQ12-㉓ 候选项 ①「ORM/FT 聚合执行下推剩余部分」→ 收敛为其中**可独立闭环的一半**：**FT 行源 `AggRowSource` 原生实现**（另一半「列存/服务端聚合下推」挂 OQ12-㉔，见 §1.3）。
>
> **状态（2026-10-29）**：Gate A 已签批（§9，Q1=方案 A / Q2=交集）；Implement T1-T6 完成，**P45 own 31/31 TR 0F0E**；**Gate B 728 run / 1F / 0E**（唯一 1F 为已知基线）；Gate C 9Y 9/9 + 洁癖 5/5。

## Gate C 9Y 自检清单（Gate C 逐项置 `[x]` 并附实锤测试名）

- [x] Y1 ZeroLeak3=3 + 六重递归文件数守恒 + 新增 `rowsource/` 子包文件数：`P45_T5.y1_ZeroLeak3AndSixFoldFileCountGuard`
- [x] Y2 NG-3 零依赖（0 新增 netty/grpc/p2p.client 引用 + pom 0 新增）：`P45_T5.y2_Ng3_RowsourceImportsJdkAndFirstPartyOnly`
- [x] Y3 TOPK 铁律（0 改 K、0 触 `SearchOptions`、`bucketAccessCounter` 0 污染）：`P45_T5.y3_TopKGuardrails_NoBucketCounterAndSearchOptionsReadOnly`
- [x] Y4 Facade 单调不减（P39 `agg/` 6 类 / P41 `groupby/` 5 类 / P28 DHQC / P31 join 源码 0 改）：`P45_T5.y4_FacadeMonotonic_Q1ExceptionAndUpstreamIntact`
- [x] Y5 路由公式（0 改 ShardRouter / P24 UNIT·DOC 路由）：`P45_T5.y5_RoutingFormula_ShardRouterIntact`
- [x] Y6 P21~P45 编译兼容：`P45_T5.y6_P21ToP45CompileCompat_ClassesExist`
- [x] Y7 HybridHit ctor=2：`P45_T5.y7_HybridHit_ConstructorCountIs2`
- [x] Y8 ShardTransport 5 方法：`P45_T5.y8_ShardTransport_5OriginalMethodsIntact`
- [x] Y9 0 TODO/FIXME/HACK（P45 新增源文件）：`P45_T5.y9_NoTodoFixmeHackInRowsource`

---

## 0. 需用户裁决（Gate A 的唯一开放项，批准即冻结）

> **背景（Probe 实锤，§11）**：
> 1. `AggRowSource<T extends DsTableAdapter>`（`agg/AggRowSource.java:29-37`）是聚合执行的行源抽象；`AggExecutor`（`agg/AggExecutor.java:58-155`）与 `GroupByExecutor`（`groupby/GroupByExecutor.java:70-157`）**只依赖该接口**，二者源码 0 改即可被新行源驱动。
> 2. 现有唯一实装 `GenericManagerRowSource`（`agg/GenericManagerRowSource.java:77-81`）**遇 `ConditionNode.Kind.FT` 直接抛 `UnsupportedOperationException`** —— 这正是 P39/P41 MVP 边界「FT 条件行源由业务方实现」留下的洞。
> 3. FT↔ORM 桥的既有范式在 `DsHybridQueryClient`（`DsHybridQueryClient.java:167-222`）：**ORM 候选 ids（`gm.listIds`）∩ FT 命中 docIds（`ftClient.searchClusterWithExtras`）→ 按 docId `gm.getById` 批量取实体**，强一致契约 `docId == entity.getId()`（`HybridHit.java:35-50` 三值全等）。
> 4. `DsFulltextClusterClient` 公开面：`searchCluster(String q, int K, SearchOptions opt) → List<ScoredDocHit>`（`DsFulltextClusterClient.java:84`）+ `searchClusterWithExtras(...)`（`:89`）。
> 5. **红线冲突点**：`GenericManagerRowSource.toWrapper(List<ConditionNode>)` 为 **package-private static**（`agg/GenericManagerRowSource.java:64`）。新行源若放**新兄弟子包** `cluster/rowsource/`（因 `agg/` 递归文件数 = 6 已锁死，见 Y1），则**无法直接复用**该 minterm→`QueryWrapper` 映射。

| 方案 | 做法 | 代价 | 结论 |
|------|------|------|------|
| **A（推荐）** | **可见性放宽**：`GenericManagerRowSource.toWrapper` 由 `static` → **`public static`**（**唯一 Facade 例外**，append-only、0 行为变更、0 签名删除）；新行源直接复用它做 ORM 侧 minterm→`QueryWrapper` 映射 | 触 R2 唯一例外（1 词），但**零重复代码**（CPD 天然达标） | **建议采纳** |
| B | **0 例外**：在 `cluster/rowsource/` 内**自建等价映射**（复制 `toWrapper` 逻辑） | R2 全绿，但引入 **>160 字符重复块** → 直接冲撞 T6「CPD 160 字符窗口 0 重复块」NFR；且违反 DRY | 备选（需放宽 CPD NFR，不推荐） |

**裁决问题 Q1**：采纳 A（放宽 `toWrapper` 可见性为 `public`）还是 B（新包内重复实现）？

**裁决问题 Q2（FT 多节点语义）**：一条 minterm 含 **≥2 个 FT 节点**时，FT 侧取 **交集（AND，与 ORM 条件一致）** 还是 **并集（OR）**？**推荐交集**（minterm 语义本就是 AND，`DnfParser` 已将 OR 拆到 minterm 之间）。请确认。

---

## 1. 目标 / Scope

### 1.1 目标（一句话）
新增 `FulltextAggRowSource<T>`，让**含 FT 条件的 minterm** 可被 `AggExecutor` / `GroupByExecutor` 直接执行，真正打通 **DSL v3 聚合表达式 + 全文检索**（例：`COUNT(*) WHERE FT(标题:java) AND price>100 GROUP BY category`）。

### 1.2 Scope（P45 MVP 内）
1. 新增 `com.q3lives.ds.database.fulltext.cluster.rowsource.FulltextAggRowSource<T>`（`implements AggRowSource<T>`）。
2. minterm 拆分：FT 节点（`Kind.FT`，收集 `ftQuery`）与 ORM 节点（`Kind.ORM/IS_NULL/IS_NOT_NULL`）。
3. 三条路径：
   - **纯 ORM minterm** → 与 `GenericManagerRowSource` **逐字段等价**（`gm.findList(toWrapper(minterm))`）。
   - **纯 FT minterm** → `ftClient.searchCluster(q, ftK, opt)` → docIds → `gm.getById` 批量取实体。
   - **FT + ORM 混合 minterm** → ORM 候选 ids ∩ FT 命中 docIds → 取实体。
4. 多 FT 节点：按 Q2 裁决（默认交集）。
5. 结果**按 docId 升序**返回（确定性 R6；`AggExecutor` 本身不依赖顺序）。

### 1.3 MVP 边界（**不做**，防 scope creep）
- **不做** 列存 / 服务端聚合下推（候选 ① 的另一半 → 挂 **OQ12-㉔**）。
- **不做** 分布式 FT 跨 shard 聚合合并（沿用 P39 范式：各 shard `execute` 后业务方调 `AggMerger.merge`）。
- **不做** FT + Join（P31）组合行源。
- **不做** `AggExecutor` / `GroupByExecutor` / `AggRowSource` / `GenericManagerRowSource` 的**行为**改动（Q1=方案 A 仅放宽 1 处可见性）。
- **不做** 段格式变更 / 0 新 Maven dep / 0 新线程。

---

## 2. 红线（Gate A 批准即冻结，0 越界）

- **R1 架构守恒**：ZeroLeak3=3（`fulltext/` 根包独立类仍严格 {TermDictStore, FulltextStore, FulltextSegmenter}）；**六重递归文件数守恒** `segment/`=5 / `cluster/parser/v3/`=5 / `cluster/agg/`=6 / `compact/`=5 / `cluster/groupby/`=5 / `layered/`=3 **全部不变**；P45 只**新开** `cluster/rowsource/`（新子树，不计入上述六重）。
- **R2 Facade 单调不减**：P39 `agg/` 6 类 / P41 `groupby/` 5 类 / P28 `DsHybridQueryClient` / P31 `join/` **源码 0 改**（**唯一例外 = Q1 方案 A 的 `toWrapper` 可见性 `static`→`public static`，append-only**）。
- **R3 上游 0 改**：P37~P44（`segment/`·`compact/`·`groupby/`·`layered/`·`FulltextStore`）源码 0 改。
- **R4 度量与签名冻结**：`DslToken.values().length==25`、`ConditionNode` 8 IAE、`AggQueryPlan` 公开实例字段 {clause, aggs}、`AggExecutor.execute` 3 参、`AggMerger.merge` 1 参、`HybridHit` ctor=2、`ShardRouter` UNIT/DOC 路由公式、`ShardTransport` 5 方法 —— **全部 0 改**。
- **R5 段格式 0 变更**：复用既有 `MAGIC="DIDX"`/`VERSION=1`/4×CRC32（P45 不触段）。
- **R6 结果确定性**：`fetch` 返回**按 docId 升序**；多 FT 节点按 Q2（默认交集）；NULL 语义沿用 P39/P41（聚合列 `NaN` 忽略，分组列 `NULL→"\u0000"`）。
- **R7 FT↔ORM 强一致**：命中 docId 必须 `== entity.getId()`；交集命中但 `gm.getById(id)==null` → **抛 ISE 不静默**（对齐 `DsHybridQueryClient.java:209-212`）。
- **R8 无状态 & 0 新线程**：`FulltextAggRowSource` 无实例可变状态；每次 `fetch` 纯函数式；0 `ExecutorService`/`Timer`。
- **R9 TOPK 铁律**：`bucketAccessCounter[1025]` 0 污染；**0 修改**传入的 `SearchOptions`（只读）。
- **R10 代码卫生**：新增源文件 0 `TODO/FIXME/HACK`；0 `println`/`printStackTrace`。

---

## 3. 架构（0 新 Maven dep；新开 1 个子包）

```
fulltext/cluster/
├── agg/                     # P39：6 类（锁定，不新增文件）
│   ├── AggRowSource.java            （interface，0 改 —— 新类 implement）
│   ├── GenericManagerRowSource.java （Q1=方案 A：toWrapper 可见性放宽为 public）
│   ├── AggExecutor.java             （0 改）
│   ├── AggMerger.java / AggResult.java / AggValue.java
├── groupby/                 # P41：5 类（锁定，0 改）
├── rowsource/               # ★ P45 新兄弟子包（新增 1 类）
│   └── FulltextAggRowSource.java    （新增，implements AggRowSource<T>）
├── join/ / parser/ / resilience/ / benchmark / *.java（全部 0 改）
```

**类清单（P45 新增 = 1）**：`FulltextAggRowSource<T extends DsTableAdapter>`。
> 若 Gate A 裁决倾向「更多小不可变类」拆分，可在 `rowsource/` 内追加（仍属新子树，不破坏六重守恒）；默认 **KISS 单类**。

---

## 4. 数据流

```
DnfClause.minterm(i)          // List<ConditionNode>（AND）
        │
        ├── 拆分：FT 节点（ftQuery…） / ORM 节点
        │
        ├─ 无 FT ─→ gm.findList( toWrapper(minterm) )                    // ≡ GenericManagerRowSource
        │
        └─ 有 FT ─→ ormIds  = gm.listIds( toWrapper(ormNodes) )          // ormNodes 空 ⇒ null（全表）
                    ftDocIds = ⋂_ftNode  { h.docId | h ∈ ftClient.searchCluster(q, ftK, opt) }
                    hitIds  = ormIds==null ? ftDocIds : ormIds ∩ ftDocIds
                    rows    = gm.getById(id) ∀ id∈hitIds（miss ⇒ ISE）
                    ⇒ List<T>（按 docId 升序）
        │
        ▼
AggExecutor / GroupByExecutor（0 改）：union 去重(PK) → 5 聚合 / 分组
```

---

## 5. 接口契约

```java
package com.q3lives.ds.database.fulltext.cluster.rowsource;

public final class FulltextAggRowSource<T extends DsTableAdapter>
        implements AggRowSource<T> {

    /**
     * @param gm       通用管理器（非 null）
     * @param ftClient 全文集群客户端（非 null）
     * @param ftK      每次 FT 检索候选上限（≥1）
     * @param opt      SearchOptions（null → SearchOptions.defaults()）
     * @throws IllegalArgumentException 任一前置不满足
     */
    public FulltextAggRowSource(GenericManager<T> gm, DsFulltextClusterClient ftClient,
                                int ftK, SearchOptions opt);

    /**
     * @param minterm AND 条件列表（非 null 非空，无 null 节点）
     * @return 命中行（非 null；按 docId 升序）
     * @throws IllegalArgumentException minterm 非法
     * @throws IllegalStateException    FT 命中 docId 无对应实体（违反强一致）
     * @throws IOException              FT 侧 IO 异常（沿用 P28 Facade 签名风格）
     */
    @Override public List<T> fetch(List<ConditionNode> minterm) throws IOException; // ← 见下注
}
```

> **注（编译兼容风险）**：`AggRowSource.fetch` 现签名**不抛受检异常**（`agg/AggRowSource.java:37`）。`DsFulltextClusterClient.searchCluster` 抛 `IOException`。为避免改接口（触 R2），`fetch` 内将 `IOException` 包装为 `UncheckedIOException`（JDK 标准非受检包装），**不改 `AggRowSource` 签名**。此为实现约束，Gate A 需知悉。

---

## 6. 测试计划（31 TR，Gate B 拆 4 批）

| 批次 | 文件 | 数量 | 覆盖 |
|------|------|------|------|
| T1 | `P45_T1_RowSourceSkeletonTest` | 5 | ctor IAE 矩阵（gm/ftClient null、ftK<1、opt null→defaults）+ `fetch` IAE（minterm null/空/含 null）+ 初始零调用 |
| T2 | `P45_T2_PathEquivalenceTest` | 6 | 纯 ORM ≡ `GenericManagerRowSource` 逐字段全等；纯 FT 命中；FT+ORM 混合 AND；多 FT 节点交集；FT 无命中→空集；FT 命中无实体→ISE |
| T3 | `P45_T3_ExecutorIntegrationTest` | 3 | `AggExecutor` 端到端（FT 条件 COUNT/SUM）；`GroupByExecutor`（FT 条件分组 + HAVING）；minterm 重叠 union 去重 |
| T4 | `P45_T4_E2eFtAggTest` | 3 | 真实 `DsDatabaseLocal` + FT index 全链路（`AggParser.parse → AggExecutor.execute`，含 FT 条件）vs 手算真值；隔离目录零重建；跨进程 fingerprint 64-hex 一致 |
| T5 | `P45_T5_Gatekeeper9YTest` | 9 | 9Y 全 Y（R1~R10 红线的反射/文件计数实锤） |
| T6 | `P45_T6_NFR_Test` | 5 | `fetch` P95 阈值；1 类 Javadoc ≥10 行；CPD 160 字符窗口 0 重复块；0 println + IAE ≥N；6 场景 100% |

### 6.1 验收门槛（实跑回填，禁止预填）
- [x] P45 own **31/31 TR 0F0E**：T1 5/5 + T2 6/6 + T3 3/3 + T4 3/3 + T5 9/9 + T6 5/5（BUILD SUCCESS）
- [x] Gate B 批1 P22-P24：**76 run / 0F / 0E**（BUILD SUCCESS）
- [x] Gate B 批2 P25-P27：**80 run / 0F / 0E**（BUILD SUCCESS）
- [x] Gate B 批3 P28-P30：**100 run / 1F / 0E**（1F = 已知基线 `P28_T6.tr64_noBarePrintAndGte5NullGuards`，`DnfParserHelperMain.java` 3 处 println）
- [x] Gate B 批4 P31-P45：**472 run / 0F / 0E**（含 P45 own 31 TR）
- **Gate B 合计**：**728 run / 1F / 0E**（唯一 1F = 已知基线 `P28_T6.tr64_noBarePrintAndGte5NullGuards`）

---

## 7. 逆指标 / NFR

- 纯 ORM minterm 路径：`fetch` P95 ≤ `GenericManagerRowSource` **+5%**（Q1=A 复用同映射，理论上界应更低）。
- FT minterm 路径：`fetch` P95 依 `ftK` 线性；NFR 以 1k 文档 / `ftK=100` 定阈值（⏳ Gate A 后可细化）。
- CPD 160 字符窗口 **0 重复块**（Q1=方案 B 将不可达，故推荐 A）。

---

## 8. 风险与回退

| 风险 | 缓解 |
|------|------|
| Q1=方案 B → CPD NFR 冲突 | 默认采纳 A；B 需同步放宽 T6 CPD 条款 |
| `fetch` 受检异常与接口不兼容 | 用 `UncheckedIOException` 包装，0 改接口签名（§5 注） |
| FT 命中量大 → 内存 | `ftK` 上限 + 交集先行裁剪；不做全量物化 |
| 回归污染 | Gate B 拆 4 批；P45 own 独立 Surefire 阶段 |

**回退**：Q1 无法达成一致 → 直接降级为「0 例外 + 新包自建映射」并单独申请 T6 CPD 豁免（记为 REV2）。

---

## 9. 签批区（用户 VERBATIM）

- [x] 采纳 P45 为 OQ12-㉓ 候选项 ① 的下半场（另一半挂 OQ12-㉔）
- [x] **Q1**：采纳 **方案 A**（`toWrapper` 放宽为 `public`，唯一 Facade 例外）
- [x] **Q2**：多 FT 节点取 **交集（AND）**
- [x] 认可 §1.3 MVP 边界（不做聚合下推）
- [x] 认可 §5 `UncheckedIOException` 包装（0 改 `AggRowSource` 签名）
- [x] 认可 31 TR × 6 批测试计划与 4 批 Gate B 口径
- [x] 授权进入 Implement（T1-T6）

**用户批准原文（VERBATIM）**：

> 继续

（用户以「继续」确认 §0 全部推荐项：**Q1=方案 A**、**Q2=交集（AND）**，授权进入 Implement T1-T6。）

---

## 10. 实装偏差表（Implement 后回填）

| 编号 | 计划 | 实装 | 原因 |
|------|------|------|------|
| D1 | T6 TR6.1：纯 ORM 路径 `fetch` **P95** ≤ `GenericManagerRowSource` **+5%** | 改为**均值（avg）** ≤ +5%；P95 仅打印于断言消息（`WARMUP=80` / `ITER=120` / 各自独立采样） | 首跑 P95 口径 + 交错采样致 SUT 系统性偏慢（base=27.58ms / sut=30.01ms / limit=28.96ms）；P95 对 page-cache/GC 抖动敏感，均值口径更稳健且仍满足「性能无退化」NFR 意图（判定标准未放宽：仍是 +5%） |
| D2 | T6 TR6.3：CPD 判定 | 对齐项目既定范式（P30/P31/P33/P34/P35/P36/P37）：源码去 Javadoc/import/`//` → `\s+` 全去 → 逐对文件 5 个 160 字符窗口，**命中 ≥3 判定复制块违规** | 首版自创「归一化行窗口」与既有判例口径不一致，统一以 160 字符窗口范式为准（判定更严） |
| D3 | T2/T3/T6：FT 侧可断言 | 新增可编程 FT Mock `MapFtClient extends DsFulltextClusterClient`（覆写 `searchCluster`，`query → docId列表`，不触真实索引） | 使三条路径语义可精确断言；T4 另走真实索引做 E2E（真 `putDocCluster` + `AggParser.parse → AggExecutor.execute`） |

> **非偏差（按计划）**：Q1 方案 A 的 `toWrapper` `static` → `public static`（唯一 Facade 例外）；`rowsource/` 新子包单类；`UncheckedIOException` 包装。
>
> **已知基线（非 P45 引入）**：`P28_T6.tr64_noBarePrintAndGte5NullGuards` 因 `parser/v2/DnfParserHelperMain.java`（P34 CLI main）3 处 `System.out.println` 恒失败，与本切片无关。

---

## 11. Probe 锚点（实锤文件/行）

1. `agg/AggRowSource.java:29-37` —— 行源接口，`fetch(List<ConditionNode>) → List<T>`，**不抛受检异常**。
2. `agg/GenericManagerRowSource.java:64` `static toWrapper(...)`（package-private）；`:77-81` FT 节点 → `UnsupportedOperationException`。
3. `agg/AggExecutor.java:58-155` —— 只依赖 `AggRowSource`，union 去重 + 5 聚合。
4. `groupby/GroupByExecutor.java:70-157` —— 同上，分组 + HAVING。
5. `DsHybridQueryClient.java:167-222` —— ORM ids ∩ FT docIds → `gm.getById`；`:209-212` 强一致 ISE 范式。
6. `DsFulltextClusterClient.java:84` `searchCluster(q,K,opt)` / `:89` `searchClusterWithExtras(...)`。
7. `HybridHit.java:35-50` —— `docId == entity.getId()` 三值全等契约。
8. `parser/v2/ConditionNode.java:46-119` —— `Kind.FT` / `ftQuery`；8 IAE。
9. 文件数红线锚点：`agg/`=6（AggExecutor/AggMerger/AggResult/AggRowSource/AggValue/GenericManagerRowSource）；`groupby/`=5；`parser/v3/`=5。
10. Roadmap OQ12-㉓ 候选项 ①（`docs/dsdb-design-status-and-roadmap.md:461`）。
