# P41 OQ1 DSL v3 聚合 v2 —— GROUP BY / HAVING / DISTINCT 执行引擎 —— spec.md（**REV2 CLOSED，2026-10-25**）

> 用户 OQ12-⑲ 候选 ②（承接 OQ12-⑱ = P40 段合并）→ SP3.1 Gate A（本文件 + tasks.md，已用户 VERBATIM 批准）→ Implement T1-T6（**31/31 TR 0F0E**）→ Gate B（**拆 4 批 604 TR：603 0F0E + 1 已知基线 fail**）→ Gate C（**9Y×9 全 Y + 洁癖 5/5，永久闭环**）。

## Gate C 9Y 自检清单（Gate A 起草时全 `[ ]`；Gate C 逐项 `[x]`，任一未过 → 退 Gate A 整改；**2026-10-25 Gate C 已全 `[x]` 实锤，`P41_T5_Gatekeeper9YTest` 9/9 0F0E**）
- [x] Y1 ZeroLeak3=3：5 新类 100% 放 `cluster/groupby/` 新兄弟子包；fulltext 根包 top-level 仍 = 3；**且** `segment/`=5 / `parser/v3/`=5 / `agg/`=6 / `compact/`=5 四重递归文件数守恒 → `P41_T5.y1_ZeroLeak3AndPentaFileCount` 反射 + 文件计数双实锤
- [x] Y2 NG-3 零依赖：0 新增 netty/grpc/p2p.client 引用 + pom.xml 0 新增 dep/plugin → `P41_T5.y2_Ng3ZeroDependency` 实锤
- [x] Y3 TOPK 铁律：0 改 K、0 触 `SearchOptions`、`bucketAccessCounter` 0 污染 → `P41_T5.y3_TopkIronclad` 实锤
- [x] Y4 Facade 单调不减：P30/P34/P38/P39 全部源码 0 改（`AggParser.parse` 6 参 / `AggQueryPlan` 公开字段 {clause, aggs} / `AggSpec` / `AggFn` / `AggExecutor.execute` / `AggMerger.merge` 签名）→ `P41_T5.y4_FacadeMonotonic_P30P34P38P39Intact` 实锤
- [x] Y5 路由公式：0 改 ShardRouter / P24 UNIT·DOC 路由 → `P41_T5.y5_RouteFormulaIntact` 实锤
- [x] Y6 P21~P40 编译兼容：既有类型 0 改 + P39 agg 6 类 + P40 compact 5 类仍在 → `P41_T5.y6_ClassPresence` 实锤
- [x] Y7 HybridHit ctor=2：0 改 → `P41_T5.y7_HybridHitCtorTwo` 实锤
- [x] Y8 ShardTransport 5 方法：0 改 → `P41_T5.y8_ShardTransportFiveMethods` 实锤
- [x] Y9 0 TODO/FIXME/HACK（`cluster/groupby/` 源码）→ `P41_T5.y9_NoTodoFixmeHack` 实锤

---

## 0. Scope 选择说明（OQ12-⑲ 候选对比，Probe 结论）

| 候选 | 内容 | 红线风险 | 工作量 | 结论 |
|------|------|---------|--------|------|
| ① 全文分层倒排索引段化 | 把 P21 `term_dict` / `text_units` / `unit_distances` 六级倒排改造成 P37/P40 段式 | **高**：直插 `FulltextStore`（2923 行、P21 核心、USER-HC-0 纯文本 unit_id、TOPK `bucketAccessCounter` 铁律） | 大 | 暂缓（建议 P42+ 单独评估） |
| **② ORM/FT 聚合执行下推** | P39 执行引擎的**列存/服务端下推** + **FT 行源原生实现** + **GROUP BY/DISTINCT/HAVING** | **低**：纯新兄弟子包 additive，0 触 P21 核心 | 中 | ✅ **本阶段取②的「GROUP BY / HAVING / DISTINCT」子集（P41 Scope A）** |
| ③ compaction 后台调度器 | P40 `policy → plan → runner` 之上加自适应触发 / 限速 / 统计 | 中：需裁决「常驻后台线程」红线（当前仅 `TimeoutGuard` 有 daemon 池、`FulltextStore.concurrentScatter` 有临时池） | 小~中 | 暂缓（建议 P42+ 单独裁决线程红线） |
| ④ Other | 用户自定义 | — | — | 待用户补充 |

**推荐 Scope A = 候选 ② 的 GROUP BY / HAVING / DISTINCT 子集**，理由：① P39 MVP 边界「**不做 GROUP BY / DISTINCT / HAVING**」是**显式挂账**，本阶段正是清账；② 属 P38（聚合计划）→ P39（聚合执行）→ **P41（聚合分组）** 的一条完整编排线；③ 纯 additive 新子包，红线风险最低、可一次闭环。

> 候选 ② 的剩余部分（**FT 行源 `AggRowSource` 原生实现** + **列存 / 服务端聚合下推**）拆分至 P42+，本阶段列为 Non-Goal。

---

## 1. Scope（用户 OQ12-⑲ 候选 ② 的 GROUP BY/HAVING/DISTINCT 子集）

> 承接原文：P39 `AggExecutor` 只产出**单行全局聚合**（`AggResult`），MVP 边界明示「不做 GROUP BY / DISTINCT / HAVING」 → P41 把「分组 + 分组过滤」实装成引擎。

P41 = 在 P39（`AggExecutor` 全局单行聚合）之上，追加**分组**能力：

1. **GROUP BY（多列分桶）**：按 `GroupBySpec.groupColumns` 把 union 去重后的行分桶，每桶**单遍**算出 `COUNT / SUM / AVG / MIN / MAX`，产出有序 `GroupBucket` 列表。
2. **HAVING（桶级过滤）**：`HavingSpec`（`aggIndex` + 比较算子 + 阈值）作用在桶的 `AggValue` 上；多谓词 **AND** 语义；不满足的桶被剔除（0 输出）。
3. **DISTINCT**：
   - **行级 `SELECT DISTINCT`** = `GROUP BY groupColumns` + **空 `aggs`**（天然退化为按分组列去重，`rowCount` = 组内行数）。
   - **`COUNT(DISTINCT col)`** = `GroupBySpec.distinctAggIndexes` 标记的聚合项，按该桶内**非 NULL 值去重后计数**（**仅支持 `COUNT`**，其余 fn + distinct → IAE）。
4. **0 改 P30/P34/P38/P39**：`AggQueryPlan` / `AggSpec` / `AggFn` / `MintermAggTask` / `AggExecutor` / `AggMerger` / `QueryWrapper` / `GenericManager` 全部源码 **0 改**；P41 纯 additive（新兄弟子包 `cluster/groupby/`）。
5. **5 新类 100% 放 `com.q3lives.ds.database.fulltext.cluster.groupby` 新兄弟子包**（ZeroLeak3=3 永保；**不得**落 `agg/` / `parser/v3/` / `segment/` / `compact/`，见 R1）。

**MVP 边界（0 越界）**：
- **不做** FT 行源原生实现：`AggRowSource` 仍由业务方实现（`GenericManagerRowSource` 遇 `Kind.FT` → `UnsupportedOperationException`，P39 边界不变）
- **不做** 聚合下推：无服务端聚合 / 无列存改造 / 0 新 RPC / 0 新 handler
- **不做** 窗口函数 / `ORDER BY` 结果排序 / 分页（桶序仅按分组键**字典序**确定性给出）
- **不做** `COUNT(DISTINCT)` 之外的 DISTINCT 聚合（`SUM/AVG/MIN/MAX(DISTINCT)` → IAE）
- **不做** 分布式分组下发：跨 shard 由业务方各跑 `execute` 后自行合并（合并语义不在本阶段）
- **P30 / P34 / P38 / P39 / `QueryWrapper` / `GenericManager` / HJC 源码 0 改**；0 新增 Maven dep

---

## 2. 红线（VERIFIED 永久钉死，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3 + 五重递归文件数守恒** | 5 新类 100% 放 `cluster/groupby/`；fulltext 根包 top-level = 3；`segment/`=5（P37_T5.Y2）/ `parser/v3/`=5（P38_T5.Y2）/ `agg/`=6（P39_T5.Y2）/ `compact/`=5（P40_T5.Y1）四基线**递归**计数**不得变动**（P41 必须开**新兄弟包**） |
| R2 | **NG-3 零依赖** | pom.xml 0 新增 dep/plugin；`groupby/` 源码 0 引用 `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*`；import 仅 JDK + `com.q3lives.ds.*` |
| R3 | **TOPK 铁律** | 0 改 K / 0 触 `SearchOptions`；`bucketAccessCounter[1025]` 0 污染（`groupby/` 源码 0 命中） |
| R4 | **Facade 单调不减** | P30 `HybridQueryParser.parse` / `DslToken` 25 值 / P34 `DnfParser.parseOr` / `DnfClause` / `ConditionNode` / P38 `AggParser.parse` 6 参 / `AggQueryPlan`（公开字段 `{clause, aggs}`）/ `AggSpec` / `AggFn` / `MintermAggTask` / P39 `AggExecutor.execute` 3 参 / `AggMerger.merge` 全部 **0 改**（仅**读**用） |
| R5 | **HybridHit ctor=2** | 0 改 |
| R6 | **P21~P40 字节级兼容** | TermDictStore / FulltextStore / `segment/` / `compact/` / HJC / ShardRouter / ShardTransport / Resilience / DnfParser / v3 5 类 / agg 6 类 / QueryWrapper / GenericManager 全部 0 改 |
| R7 | **union 语义（去重防重复计数）** | 跨 minterm 必须按主键（`getId()`）**先去重再分组**；DNF minterm **允许重叠**，不假设互斥 |
| R8 | **NULL 语义确定性** | 聚合列 NULL（`ToDoubleFunction` → `NaN` 哨兵）不计入 COUNT/SUM 分母；**分组列为 NULL** 合法（独立成键，canonical 哨兵 `"\u0000"`）；空组不产生桶 |
| R9 | **结果确定性** | 桶序 = 分组键 `List<String>` **字典序**（逐元素 `compareTo`）；桶内 `values` 顺序 = `plan.aggs` 声明序；`fingerprint()` = SHA-256 64-hex，跨 run / **跨进程**稳定 |
| R10 | **HAVING 契约严格** | `HavingSpec.aggIndex` 必须命中 `plan.aggs` 下标，否则 IAE；`test(null)` → IAE（绝不静默） |

---

## 3. 架构（5 个新类，新兄弟子包 `cluster/groupby/`，0 改 P21~P40）

### 3.1 新增清单

| 名称 | 类型 | 职责 | 关键成员 |
|------|------|------|---------|
| `HavingSpec` | 不可变 POJO（groupby/） | HAVING 单谓词：对某聚合项的答案做比较 | `aggIndex`(int) / `op`(内嵌 enum `HavingOp{GT,GE,LT,LE,EQ,NE}`) / `threshold`(double) + `test(AggValue)` + 3 IAE + equals/hashCode/toString |
| `GroupBySpec` | 不可变 POJO（groupby/） | GROUP BY 规格：复用 P38 `AggQueryPlan` + 分组列 + DISTINCT 标记 + HAVING 列表 | `plan`(`AggQueryPlan`) / `groupColumns`(`List<String>`) / `distinctAggIndexes`(`List<Integer>`) / `havings`(`List<HavingSpec>`) + `fingerprint()` + 6 IAE |
| `GroupBucket` | 不可变 POJO（groupby/） | 单个分组桶 | `key`(`List<String>` 不可变) / `values`(`List<AggValue>` 顺序 = aggs 声明序) / `rowCount`(long) + `fingerprint()` + 3 IAE + equals/hashCode/toString |
| `GroupByResult` | 不可变 POJO（groupby/） | 一次 GROUP BY 的完整结果（有序） | `buckets`(`List<GroupBucket>` 不可变，桶序 = 键字典序) / `totalRowCount`(long，union 去重后总行数) + `size()` / `bucket(int)` / `fingerprint()` / `deepEquals()` + 2 IAE |
| `GroupByExecutor<T>` | final class（groupby/） | 执行引擎：union 去重 → 分桶 → 单遍 5 聚合（+ COUNT DISTINCT）→ HAVING 过滤 → 键排序 | `execute(GroupBySpec, AggRowSource<T>, Map<String,Function<T,String>>, Map<String,ToDoubleFunction<T>>)` + ≥6 IAE + 2 ISE |

### 3.2 执行数据流（union 去重 → 分桶 → 单遍聚合 → HAVING → 排序）

```
DSL: (a = 1 OR b = 2) AND (a = 1 OR c = 3)  AGG(COUNT(price), COUNT(DISTINCT(price)), SUM(price))  GROUP BY dept HAVING COUNT(price) >= 3
        │  P38 AggParser.parse → AggQueryPlan{clause=4 minterms, aggs=[COUNT(price), COUNT(price D), SUM(price)]}
        │  P41 new GroupBySpec(plan, ["dept"], distinctAggIndexes=[1], havings=[HavingSpec(0, GE, 3.0)])
        ▼
GroupByExecutor.execute(spec, source, groupCols, aggCols)
for each minterm（spec.plan.clause 顺序，固定）:
      rows = source.fetch(minterm)                      ← 每 minterm 仅 fetch 一次
      for row in rows:
            pk = row.getId()                            ← null → IllegalStateException (R7 前提)
            if seen.add(pk):                            ← HashSet<Long> 跨 minterm 全局去重（union 语义，先在）
                  gk = [ groupCols.get(c).apply(row) 规范化 | c in groupColumns ]   ← null → "\u0000"（R8）
                  b  = buckets.computeIfAbsent(gk, ...)  ← 新桶
                  b.rowCount++
                  for j in aggs:
                        x = aggCols.get(aggs[j].column).applyAsDouble(row)
                        if x is NaN: skip               ← SQL 忽略 NULL（R8）
                        if j ∈ distinctAggIndexes: b.distinct[j].add(x)   ← HashSet<Double>
                        else: b 直接累加 count/sum/min/max
        ▼
for each bucket（分桶完成，单遍收尾）:
      values[j] = j ∈ distinct ?
                    AggValue(aggs[j], (long) b.distinct[j].size(), Σ, (double) size)      ← 仅 COUNT 合法
                  : AggValue(aggs[j], count, sum, COUNT/SUM/AVG/MIN/MAX)
      if all(havings.test(values[hv.aggIndex])):  keep     ← AND 语义；否则剔除
        ▼
GroupByResult{ buckets = 按 key 字典序排序后的保留桶, totalRowCount = seen.size() }
```

### 3.3 包结构（新增 `cluster/groupby/` 兄弟子包）

```
com/q3lives/ds/database/fulltext/
├── FulltextSegmenter.java / FulltextStore.java / TermDictStore.java   (P21 既有，0 改)
├── segment/                                                          (P37 既有，0 改；递归锁 5)
├── compact/                                                          (P40 既有，0 改；递归锁 5)
└── cluster/
    ├── parser/
    │   ├── HybridQueryParser / DslToken / DslTokenizer               (P30 既有，0 改)
    │   ├── v2/                                                       (P34 既有，0 改)
    │   └── v3/  (AggFn/AggSpec/MintermAggTask/AggQueryPlan/AggParser) (P38 既有，0 改；递归锁 5)
    ├── agg/  (AggRowSource/GenericManagerRowSource/AggValue/AggResult/AggExecutor/AggMerger) (P39 既有，0 改；递归锁 6)
    └── groupby/                                                      (P41 新增兄弟子包；递归锁 5)
        ├── HavingSpec.java        (NEW)
        ├── GroupBySpec.java       (NEW)
        ├── GroupBucket.java       (NEW)
        ├── GroupByResult.java     (NEW)
        └── GroupByExecutor.java   (NEW)
```

---

## 4. 接口契约

### 4.1 HavingSpec

```java
public final class HavingSpec {
    public enum HavingOp { GT, GE, LT, LE, EQ, NE }   // 内嵌，不占 top-level 计数

    public final int aggIndex;         // 指向 GroupBySpec.plan.aggs 的下标（≥ 0）
    public final HavingOp op;          // 非 null
    public final double threshold;     // 有限（非 NaN / 非 Infinite）

    public HavingSpec(int aggIndex, HavingOp op, double threshold);
    // 3 IAE: aggIndex < 0 / op == null / threshold 为 NaN 或 Infinite
    public boolean test(AggValue v);   // v == null → IAE；按 op 比较 v.value 与 threshold
    // equals / hashCode / toString（"aggs[0] >= 3.0" 形式）
}
```

### 4.2 GroupBySpec

```java
public final class GroupBySpec {
    public final AggQueryPlan plan;            // P38 复用（非 null）；plan.aggs 可空（= SELECT DISTINCT 分组列）
    public final List<String> groupColumns;    // 不可变；非空、无 null/空串、无重复
    public final List<Integer> distinctAggIndexes; // 不可变；可为空；每项 ∈ [0, plan.aggs.size()) 且对应 fn == COUNT
    public final List<HavingSpec> havings;     // 不可变；可为空；每项 aggIndex ∈ [0, plan.aggs.size())

    public GroupBySpec(AggQueryPlan plan, List<String> groupColumns,
                       List<Integer> distinctAggIndexes, List<HavingSpec> havings);
    // 6 IAE: plan == null / groupColumns == null 或空 / groupColumns 含 null·空串 / groupColumns 有重复
    //        / distinctAggIndexes 含 null 或越界 或 对应非 COUNT / havings 含 null 或 aggIndex 越界
    public String fingerprint();   // SHA-256(plan.fingerprint() | groupColumns | distinctIdx | havings) → 64-hex
}
```

### 4.3 GroupBucket

```java
public final class GroupBucket {
    public final List<String> key;        // 不可变；与 GroupBySpec.groupColumns 一一对应（NULL → "\u0000"）
    public final List<AggValue> values;   // 不可变；顺序 = plan.aggs 声明序（aggs 空时为空）
    public final long rowCount;           // 本桶 union 去重后的行数（≥ 1）

    // 3 IAE: key == null 或含 null / values == null 或含 null / rowCount < 0
    public String fingerprint();          // SHA-256(key | 逐 AggValue 同 P39 编码) → 64-hex
    // equals / hashCode / toString（"{dept=eng, COUNT(price)=12.0, ...}" 形式）
}
```

### 4.4 GroupByResult

```java
public final class GroupByResult {
    public final List<GroupBucket> buckets;   // 不可变；桶序 = key 字典序（R9）
    public final long totalRowCount;          // union 去重后总行数（HAVING 过滤前，≥ 0）

    // 2 IAE: buckets == null 或含 null / totalRowCount < 0
    public int size();
    public GroupBucket bucket(int i);         // 越界 → IndexOutOfBoundsException
    public String fingerprint();              // SHA-256(逐 bucket.fingerprint()) → 64-hex
    public static boolean deepEquals(GroupByResult a, GroupByResult b);
}
```

### 4.5 GroupByExecutor

```java
public final class GroupByExecutor<T extends DsTableAdapter> {
    public GroupByResult execute(GroupBySpec spec,
                                 AggRowSource<T> source,
                                 Map<String, Function<T, String>> groupColumnExtractors,
                                 Map<String, ToDoubleFunction<T>> aggColumnExtractors);
    // ≥6 IAE: spec / source / groupColumnExtractors / aggColumnExtractors == null
    //          / 某 groupColumns c 无 groupExtractor / 某 AggSpec.column 无 aggExtractor
    // 2 ISE: source.fetch 返回 null / 行 getId() == null（R7 去重前提不可满足）
    // 语义: union 去重 → 分桶 → 单遍聚合（NaN 忽略；distinct 仅 COUNT）→ HAVING AND → 键字典序
    //        plan.aggs 空 → 每桶 values 空（= SELECT DISTINCT groupColumns）；groupColumns 空 → IAE（避免与 P39 全局聚合重复）
}
```

---

## 5. 测试（T1-T6，31 TR；Gate B 拆 4 批：P22-P24 / P25-P27 / P28-P30 / P31-P41）

| T | 测试类 | TR | 覆盖 |
|---|--------|----|------|
| T1 | `P41_T1_SpecBucketResultTest` | 5 | `HavingSpec` 3 IAE + `test` 6 算子语义 + `test(null)` IAE；`GroupBySpec` 6 IAE + `fingerprint` 64-hex 稳定；`GroupBucket` 3 IAE + fingerprint；`GroupByResult` 2 IAE + `size/bucket` 越界 + `deepEquals` + 不可变 |
| T2 | `P41_T2_ExecutorGroupingTest` | 6 | 内存行源：单列/双列 GROUP BY + 5 聚合 vs 手算真值 / `COUNT(DISTINCT)` 去重计数 / 空 `aggs` → SELECT DISTINCT 行 / HAVING 单谓词 + 多谓词 AND / 桶序字典序确定性 / ≥6 IAE（spec·source·两 map null + 无 extractor + groupColumns 空 + DISTINCT 非 COUNT）+ 2 ISE |
| T3 | `P41_T3_UnionHavingTest` | 3 | 重叠 minterm（`[a1,a1] [a1,c3] [b2,a1] [b2,c3]`）去重后分组 vs 手写真值 + HAVING 剔除整桶 + minterm 顺序无关（同值同 fingerprint） |
| T4 | `P41_T4_E2eGroupByTest` | 3 | 真实 `DsDatabaseLocal` + `GenericManagerRowSource` E2E：双列 GROUP BY + HAVING vs 手算（含 NULL 分组列 + NULL 聚合列）；`AggParser.parse` → `GroupBySpec` → `execute` 全链路；跨进程 fingerprint 一致（子 JVM spawn） |
| T5 | `P41_T5_Gatekeeper9YTest` | 9 | Y1~Y9（ZeroLeak3=3 + 五重递归文件守恒 / NG-3 import 白名单 + pom 0 新 dep / TOPK / Facade 探针 P30·P34·P38·P39 / 路由 / 类存在 / HybridHit ctor=2 / ShardTransport 5 方法 / 0 TODO） |
| T6 | `P41_T6_NFR_Test` | 5 | execute P95 ≤ 2s（8 minterm × 3 agg × 1k 行 × 50 组）+ 5 类 Javadoc ≥ 10 行 + CPD 160 字符窗口 0 重复块 + 0 `System.out.println`/`printStackTrace` + IAE ≥ 8 + 6 场景 100%（重叠去重 / 空 aggs DISTINCT / NULL 分组 / HAVING 全剔除空结果 / 桶序确定性 / fingerprint 跨进程一致） |

**TR 总数**：5+6+3+3+9+5 = **31 TR**

### 5.1 Gate B 拆批命令（继承 P40 模板，必须 `-pl p2p-db`）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P22_*,ds.P23_*,ds.P24_*'
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P25_*,ds.P26_*,ds.P27_*'
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P28_*,ds.P29_*,ds.P30_*'
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*'
```

### 5.2 验收门槛
- P41 own **31/31 TR 0F0E**
- P22-P41 拆 4 批全量回归 0F0E（允许已知基线 fail `P28_T6.tr64` 沿用）
- **分组正确性**：GROUP BY 分组 + 逐聚合结果 = 手写「按分组键分组的 distinct-PK 集合」真值（逐桶逐聚合全等）
- **HAVING 正确性**：多谓词 AND 剔除桶后 = 手写过滤真值
- **DISTINCT 正确性**：`COUNT(DISTINCT col)` = 手写桶内 distinct 非 NULL 值个数
- **跨进程确定性**：同 DSL + 同数据 → 同 `fingerprint()`（64-hex）
- **0 副作用**：execute 期间 0 触 FulltextStore / 0 改 `bucketAccessCounter` / 0 写库
- 空 aggs / NULL 分组 / 全 NULL 列 / HAVING 全剔除 / DISTINCT 非 COUNT → 语义明确（空/剔除/IAE），绝不静默

---

## 6. 逆指标（Anti-Metrics）

| 逆指标 | 错误做法 | 正确做法 |
|--------|----------|----------|
| 先分组后去重 | 各 minterm 的行直接分桶（重叠行重复计数） | **先按 PK 全局去重** 再分桶（R7） |
| 假设 minterm 互斥 | 直接 Σ 各 minterm 的桶 | 同 P39：union 语义（R7） |
| COUNT(DISTINCT) 用普通 COUNT | 忽略 `distinctAggIndexes` | 桶内 `HashSet<Double>` 去重后计数（R8 非 NULL） |
| NULL 分组列丢弃 | 跳过 NULL 分组列的行 | NULL 合法成键（canonical `"\u0000"`），独立成桶（R8） |
| 桶序依赖 HashMap 迭代序 | 直接返回 `HashMap.values()` | 按分组键 `List<String>` 字典序显式排序（R9） |
| 越界 HAVING 静默 | `aggIndex` 越界返回 false | `GroupBySpec` 构造期 IAE（R10） |
| 落错子包 | 5 新类塞 `agg/` / `parser/v3/` | 新**兄弟**子包 `cluster/groupby/`（四基线递归锁不变，R1） |
| 重复造全局聚合 | `groupColumns` 空也建 1 桶 | `groupColumns` 空 → IAE（全局聚合归 P39 `AggExecutor`） |

---

## 7. 风险与回退

| 风险 | 触发 | 回退 |
|------|------|------|
| 主键为空导致无法去重 | 行源自造对象（无 `getId()`） | `GroupByExecutor` 抛 `IllegalStateException`（显式 fail，不静默重复计数） |
| 内存放大（大基数分组） | 超多分桶 | 桶表 `HashMap<List<String>, Accum>` 仅存分组键 + 累加器；MVP 不引入 spill，文档标注 |
| 分组键比较不确定 | 不同 JVM `String.compareTo` 差异 | 用 UTF-16 `String.compareTo`（JDK 规范保证），逐元素字典序（R9） |
| 浮点漂移 | Σ 大数 + 小数 | `fingerprint` 用 `doubleToLongBits`（位级确定）；DISTINCT 求和前按 `Double.compare` 升序排序以消除加法顺序差异 |
| agg 序列错配 | 不同 plan 的结果拼接 | 本阶段不提供跨 plan 合并（Non-Goal）；桶内 values 严格 = `plan.aggs` 声明序（R9） |
| 并发 | 多线程共用 `GroupByExecutor` | MVP 单线程语义；`GroupByExecutor` 无状态（每次 execute 局部变量），不承诺线程安全 |
| 破坏 P40 基线 | 新类落 `agg/` / `compact/` | T5 Y1 同时断言「根包=3」+ 四基线递归文件数（R1） |
| 磁盘膨胀（跨批假失败） | `target\tmp` + 仓库根 `.tmp` 累积 | Gate B 跨批前清理（P39 教训3） |

---

## 8. Probe 结论（实锤锚点）

1. **P39 `AggExecutor`**（`cluster/agg/AggExecutor.java` L58-155）：`execute(plan, source, columnExtractors)` 已实现「跨 minterm PK 去重 → 单遍 5 聚合」，**但仅产出单行全局 `AggResult`**（L81 `aggs` 空短路、L105-123 单遍累加、L154 `seen.size()`）。**结论：P41 分组可在同去重框架上扩展，P39 源码 0 改**。
2. **P38 `AggQueryPlan`**（`parser/v3/AggQueryPlan.java` L50-54）：公开字段 `clause` + `aggs`（`MAX_AGG=8`） + `tasks()` / `fingerprint()`。**结论：P41 `GroupBySpec` 直接**组合**（非继承）`AggQueryPlan`，读 `clause`（取 minterm 顺序）+ `aggs`（声明序），0 改**。
3. **`AggSpec` / `AggFn`**（`parser/v3/`）：`AggSpec.fn`（`AggFn.code()`）+ `AggSpec.column`。**结论：DISTINCT 标记不能改 `AggSpec`（R4），改由 `GroupBySpec.distinctAggIndexes: List<Integer>` 侧挂**。
4. **`AggValue` 4 参构造**（`cluster/agg/AggValue.java` L59）：`(spec, count, sum, value)` + 4 IAE（含 NaN/Inf 拒绝）。**结论：P41 桶内可直接复用 `AggValue` 构造，0 改**。
5. **`AggRowSource`**（`cluster/agg/AggRowSource.java`）：`@FunctionalInterface fetch(List<ConditionNode>)`，契约「返回 null → 调用方抛 ISE」「行必须带 PK」。**结论：`GroupByExecutor` 复用同契约，0 改**。
6. **P40 基线硬约束**：`P40_T5_Gatekeeper9YTest.y1`（L69-72）断言 `segment`=5 / `parser/v3`=5 / `cluster/agg`=6 / `compact`=5 **均递归** `collectJavaFiles`；L64-66 断言根包 top-level=3。**结论：P41 5 新类**必须**落**新兄弟子包** `cluster/groupby/`，否则既有四基线 TR 直接 Fail**。
7. **P39 基线硬约束**：`P39_T5.Y1` 断言 `parser/v3` 文件数 = 5。**结论：DISTINCT 不得通过改 `parser/v3/` 实现（如给 `AggFn` 加 DISTINCT），必须侧挂 `GroupBySpec`**。

---

## 9. 签批区（Gate A REV1 → **已由用户 VERBATIM 批准，2026-10-24**）
- [x] 用户批准 OQ12-⑲ 候选 ② 的 **GROUP BY / HAVING / DISTINCT 子集**（Scope 锚定 above；FT 行源 + 聚合下推 → P42+ Non-Goal）
- [x] 批准 5 新类全放**新兄弟子包** `cluster/groupby/`（ZeroLeak3=3 + 四基线递归文件数守恒）
- [x] 批准 P30/P34/P38/P39 源码 0 改红线（`AggQueryPlan` / `AggSpec` / `AggFn` / `AggExecutor` / `AggMerger` 不动，仅组合复用）
- [x] 批准 MVP 边界（GROUP BY 多列分桶 + HAVING AND 过滤 + DISTINCT（行级 + COUNT D）；无 FT 行源 / 无下推 / 无窗口 / 无 ORDER BY 分页 / 无分布式分组下发）

---

## 10. 实装偏差说明（REV1 → REV2，Implement 完成后回填）

| 项 | 计划（REV1） | 实装（REV2 实锤） | 偏差 |
|----|------------|-----------------|------|
| 新类数 | 5 个，全放 `fulltext/cluster/groupby/` | 5 个实装（`HavingSpec` / `GroupBySpec` / `GroupBucket` / `GroupByResult` / `GroupByExecutor`），100% 落 `fulltext/cluster/groupby/`（递归锁 5 守恒） | 0 |
| 0 改源码 | P30/P34/P38/P39 全 0 改 | 0 改：`AggQueryPlan` / `AggSpec` / `AggFn` / `AggExecutor` / `AggMerger` / `AggRowSource` 源码字节级未变；P30 `DslToken` 25 值 / P34 `DnfParser` / P38 `AggParser.parse` 6 参 / P39 `AggExecutor.execute` 3 参 / `AggMerger.merge` 1 参签名全保（Y4 实锤） | 0 |
| IAE / ISE 计数 | ≥6 IAE + 2 ISE（仅 `GroupByExecutor`） | `GroupByExecutor` 6 IAE + 3 ISE（≥ 下限）；5 类合计 **IAE 31 处**（`HavingSpec`=4 / `GroupBySpec`=13 / `GroupBucket`=5 / `GroupByResult`=3 / `GroupByExecutor`=6）+ **ISE 7 处** | 超下限（校验更严，逐元素守卫） |
| Gate B 实跑数字 | 待实跑（禁止预填） | **P41 own 31/31 TR 0F0E**（T1 5 + T2 6 + T3 3 + T4 3 + T5 9 + T6 5）；**Gate B 拆 4 批 604 TR**（批 1 P22-P24=76/76 0F0E + 批 2 P25-P27=80/80 0F0E + 批 3 P28-P30=100 含 1 已知基线 fail `P28_T6.tr64_noBarePrintAndGte5NullGuards` + 批 4 P31-P41=348/348 0F0E），**603 0F0E + 1 已知基线 fail** | 与门槛一致 |
