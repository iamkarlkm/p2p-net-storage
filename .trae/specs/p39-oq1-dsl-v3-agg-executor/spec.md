# P39 OQ1 DSL v3 聚合执行/合并引擎 —— spec.md（**REV2 GATE C CLOSED，2026-10-23**）

> 用户 OQ12-⑰ 选 ③（承接 OQ12-⑯ = P38 聚合计划）→ SP3.1 Gate A（本文件 + tasks.md 2026-10-21 VERBATIM 批准）→ Implement T1-T6（31/31 TR 0F0E）→ Gate B（拆 4 批 542 TR）→ Gate C（9Y 全 Y + 洁癖 5/5）。

## Gate C 9Y 自检清单（Gate C 逐项 [x]；任一未过 → 退 Gate A 整改）
- [x] Y1 ZeroLeak3=3：6 新类 100% 放 `cluster/agg/` 新子包；fulltext 根包 top-level 仍 = 3；**且** `parser/v3/` 源文件数仍 = 5（P38 基线不被破坏）→ `P39_T5.y1_ZeroLeak3AndParserV3FileCount` 反射 + 文件计数双实锤 9/9 0F0E
- [x] Y2 NG-3 零依赖：0 新增 netty/grpc/p2p.client 引用 + pom.xml 0 新增 dep/plugin → `P39_T5.y2_NG3ZeroDependency` 实锤（`git diff` 无 `p2p-db/pom.xml`）
- [x] Y3 TOPK 铁律：0 改 K、0 触 `SearchOptions`、`bucketAccessCounter` 0 污染 → `P39_T5.y3_TopkIronclad` 实锤
- [x] Y4 Facade 单调不减：P30/P34/P38 全部源码 0 改（`AggParser.parse` 6 参 / `AggQueryPlan` 公开实例字段 {clause, aggs} / `DslToken` 25 值 / `DnfParser.parseOr` 6 参）→ `P39_T5.y4_FacadeMonotonic_P30P34P38Intact` 实锤
- [x] Y5 路由公式：0 改 ShardRouter / P24 UNIT·DOC 路由 → `P39_T5.y5_RouteFormulaIntact` 实锤
- [x] Y6 P21~P38 编译兼容：既有类型 0 改 + P38 v3 5 类仍在 → `P39_T5.y6_ClassPresence` 实锤
- [x] Y7 HybridHit ctor=2：0 改 → `P39_T5.y7_HybridHitCtorTwo` 实锤
- [x] Y8 ShardTransport 5 方法：0 改 → `P39_T5.y8_ShardTransportFiveMethods` 实锤
- [x] Y9 0 TODO/FIXME/HACK（`cluster/agg/` 源码）→ `P39_T5.y9_NoTodoFixmeHack` 实锤

---

## 1. Scope（用户 OQ12-⑰ 候选项 ③）

> 承接原文：`P38 ... 编译期拆分为 N 个 (minterm, aggFn) 对，业务方拿 N 个查询结果聚合` → P39 把「业务方那一步」实装成引擎。

P39 = 在 P38（DSL → `AggQueryPlan`，N = minterms × aggs 个 `MintermAggTask`）之上，追加**执行 + 合并**两段能力：

1. **执行（单进程）**：对计划里的每个 minterm 取行（`AggRowSource` 抽象行源；提供 `GenericManager` 版实装），跨 minterm 按**主键去重**后单遍算出 `COUNT / SUM / AVG / MIN / MAX`，产出 `AggResult`（顺序 = `plan.aggs` 声明序，`fingerprint()` 跨进程稳定）。
2. **合并（多来源）**：`AggMerger` 把多个部分结果（如各 shard 各自执行的部分 `AggResult`）按聚合语义合并：`COUNT/SUM` 相加、`AVG` 走 Σsum/Σcount（精确，无浮点重算）、`MIN/MAX` 取极值。
3. **0 改 P30/P34/P38**：`AggQueryPlan.tasks()` / `AggSpec` / `AggParser` / `QueryWrapper` / `GenericManager` / `HybridJoinClient` 全部源码 **0 改**；P39 纯 additive（新子包 `cluster/agg/`）。
4. **6 新类 100% 放 `com.q3lives.ds.database.fulltext.cluster.agg` 新子包**（ZeroLeak3=3 永保；**不得**落 `parser/v3/`，见 R1）。

**MVP 边界（0 越界）**：
- **不做** `GROUP BY` / `DISTINCT` / `HAVING` / 窗口函数
- **不做** FT 条件执行：`GenericManagerRowSource` 遇到 `ConditionNode.Kind.FT` → `UnsupportedOperationException`（FT 行源由业务方实现 `AggRowSource`）
- **不做** 分布式计划下发：0 新 RPC / 0 新 handler；跨 shard 由业务方各跑 `execute` 后调 `AggMerger.merge`
- **不做** 聚合下推（无服务端聚合、无 ORM/列存改造）
- **不做** 排序 `ORDER BY` / 分页（聚合结果单行）
- **P30 / P34 / P38 / QueryWrapper / GenericManager / HJC 源码 0 改**；0 新增 Maven dep

---

## 2. 红线（VERIFIED 永久钉死，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3 + P38 基线双保** | 6 新类 100% 放 `cluster/agg/`；fulltext 根包 top-level = 3；`parser/v3/*.java` 文件数仍 = 5（P38 `P38_T5.Y2` 断言） |
| R2 | **NG-3 零依赖** | pom.xml 0 新增 dep/plugin；源码 0 引用 `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*` |
| R3 | **TOPK 铁律** | 0 改 K / 0 触 `SearchOptions`；`bucketAccessCounter[1025]` 0 污染 |
| R4 | **Facade 单调不减** | P30 `HybridQueryParser.parse` 4 参 / `DslToken` 25 值 / `DslTokenizer` + P34 `DnfParser.parseOr` 6 参 / `ConditionNode` / `DnfClause` + P38 `AggParser.parse` 6 参 / `AggQueryPlan` / `AggSpec` / `AggFn` / `MintermAggTask` 全部 **0 改** |
| R5 | **HybridHit ctor=2** | 0 改 |
| R6 | **P21~P38 字节级兼容** | TermDictStore / FulltextStore / DsIndexSegment* / HJC / ShardRouter / ShardTransport / Resilience / DnfParser / v3 5 类 / QueryWrapper / GenericManager 全部 0 改 |
| R7 | **union 语义（去重防重复计数）** | 跨 minterm 必须按主键（`getId()`）去重后再聚合；DNF minterm **允许重叠**（如 `(A OR B) AND (A OR C)` → `[A,A] ⊆ [A,C]`），不假设互斥 |
| R8 | **空集 / NULL 语义确定性** | 空行集或全 NULL 列 → 不抛异常；`value = 0.0` 且 `count = 0`（SQL 风格：COUNT/SUM/AVG/MIN/MAX 均忽略 NULL 值） |
| R9 | **结果确定性** | `AggResult.values` 顺序 = `plan.aggs` 声明序；`fingerprint()` = SHA-256 64-hex，跨 run / **跨进程**稳定 |
| R10 | **合并契约严格** | `AggMerger.merge` 仅接受 **agg 序列逐项相等** 的部分结果，否则 IAE（绝不静默错配/错位相加） |

---

## 3. 架构（6 个新类，新子包 `cluster/agg/`，0 改 P21~P38）

### 3.1 新增清单

| 名称 | 类型 | 职责 | 关键成员 |
|------|------|------|---------|
| `AggRowSource<T>` | `@FunctionalInterface`（agg/） | 行源抽象：给定 minterm 返回命中行 | `List<T> fetch(List<ConditionNode> minterm)` |
| `GenericManagerRowSource<T>` | final class（agg/） | `AggRowSource` 的 ORM 实装：minterm → `QueryWrapper` → `gm.findList(w)` | 构造 `(GenericManager<T>)` + `fetch` + `static toWrapper`（ORM 节点 → `eq/ne/gt/ge/lt/le/between/in/isNull/isNotNull`） |
| `AggValue` | 不可变 POJO（agg/） | 单个聚合项的结果 + 合并所需中间量 | `spec` / `count`(long) / `sum`(double) / `value`(double) + 4 IAE + equals/hashCode/toString |
| `AggResult` | 不可变 POJO（agg/） | 一次执行的完整结果（有序） | `values`(`List<AggValue>` 不可变) / `unionRowCount` / `size()` / `value(int)` / `fingerprint()` / `deepEquals()` + 3 IAE |
| `AggExecutor<T>` | final class（agg/） | 执行引擎：union 去重 + 单遍 5 聚合 | `execute(AggQueryPlan, AggRowSource<T>, Map<String,ToDoubleFunction<T>>)` + 4 IAE + 2 ISE |
| `AggMerger` | 静态工具类（agg/） | 合并多个部分结果（跨 shard / 跨来源） | `merge(List<AggResult>)` / `mergeValues(AggValue, AggValue)` + IAE（agg 序列不一致） |

### 3.2 执行数据流（union 去重 + 单遍聚合）

```
DSL: (a = 1 OR b = 2) AND (a = 1 OR c = 3)  AGG(COUNT(price), SUM(price), AVG(price))
        │  P38 AggParser.parse
        ▼
AggQueryPlan{ clause = 4 minterms: [a1,a1] [a1,c3] [b2,a1] [b2,c3], aggs = [COUNT(price), SUM(price), AVG(price)] }

        │  AggExecutor.execute(plan, source, cols)
        ▼
for each minterm（plan.tasks() 按 mintermIndex 分组，顺序固定）:
      rows = source.fetch(minterm)                     ← GenericManagerRowSource: QueryWrapper + findList
      for row in rows:
            pk = row.getId()                           ← null → IllegalStateException (R7 前提)
            if seen.add(pk):                            ← HashSet<Long> 跨 minterm 全局去重（union 语义）
                  for each agg in plan.aggs:
                         x = cols.get(agg.column).applyAsDouble(row)
                         x == null / NaN? → skip        ← SQL 语义：忽略 NULL（R8）
                         acc[agg]: count++, sum += x, min/max 更新
        ▼
AggResult{ values = [COUNT→(count,0,count), SUM→(count,sum,sum), AVG→(count,sum,sum/count)],
           unionRowCount = seen.size() }               ← 顺序 = aggs 声明序（R9）

        │  AggMerger.merge(各 shard 的部分 AggResult)
        ▼
AggResult（COUNT = Σcount；SUM = Σsum；AVG = Σsum/Σcount；MIN = min(value)；MAX = max(value)；unionRowCount = Σ）
```

### 3.3 包结构（新增 `cluster/agg/` 子包）

```
com/q3lives/ds/database/fulltext/
├── FulltextSegmenter.java / FulltextStore.java / TermDictStore.java   (P21 既有，0 改)
├── segment/                                                          (P37 既有，0 改)
└── cluster/
    ├── parser/
    │   ├── HybridQueryParser / DslToken / DslTokenizer               (P30 既有，0 改)
    │   ├── v2/                                                       (P34 既有，0 改)
    │   └── v3/  (AggFn/AggSpec/MintermAggTask/AggQueryPlan/AggParser) (P38 既有，0 改；文件数锁 5)
    └── agg/                                                          (P39 新增子包)
        ├── AggRowSource.java          (NEW)
        ├── GenericManagerRowSource.java (NEW)
        ├── AggValue.java              (NEW)
        ├── AggResult.java             (NEW)
        ├── AggExecutor.java           (NEW)
        └── AggMerger.java             (NEW)
```

---

## 4. 接口契约

### 4.1 AggRowSource / GenericManagerRowSource

```java
@FunctionalInterface
public interface AggRowSource<T extends DsTableAdapter> {
    /** 返回该 minterm 命中的所有行（非 null；返回 null → 调用方抛 ISE）。 */
    List<T> fetch(List<ConditionNode> minterm);
}

public final class GenericManagerRowSource<T extends DsTableAdapter> implements AggRowSource<T> {
    public GenericManagerRowSource(GenericManager<T> gm);      // gm == null → IAE
    @Override public List<T> fetch(List<ConditionNode> minterm);
    // minterm == null/empty → IAE；minterm 含 Kind.FT 节点 → UnsupportedOperationException（MVP 边界）
    static <T extends DsTableAdapter> QueryWrapper<T> toWrapper(List<ConditionNode> minterm);  // package-private
    // ORM 节点映射：OP_EQ→eq / OP_NE→ne / OP_GT→gt / OP_GE→ge / OP_LT→lt / OP_LE→le /
    //              KW_BETWEEN→between / KW_IN→in / IS_NULL→isNull / IS_NOT_NULL→isNotNull
    // 其他 ormOp（KW_IS / KW_LIKE 等）→ UnsupportedOperationException
}
```

### 4.2 AggValue

```java
public final class AggValue {
    public final AggSpec spec;   // 非 null
    public final long count;     // 参与聚合的非 NULL 值个数（≥ 0）
    public final double sum;     // Σ 非 NULL 值（COUNT 恒 0.0）
    public final double value;   // 聚合答案：COUNT→count｜SUM→sum｜AVG→sum/count（count=0 → 0.0）｜MIN/MAX→极值
    public AggValue(AggSpec spec, long count, double sum, double value);
    // 4 IAE: spec == null / count < 0 / sum 为 NaN 或 Infinite / value 为 NaN 或 Infinite
    // equals / hashCode / toString（"COUNT(price)=12.0" 形式）
}
```

### 4.3 AggResult

```java
public final class AggResult {
    public final List<AggValue> values;   // unmodifiable；顺序 = plan.aggs 声明序（可为空）
    public final long unionRowCount;      // union 去重后的行数（≥ 0）
    public int size();
    public AggValue value(int i);         // 越界 → IndexOutOfBoundsException
    public String fingerprint();          // SHA-256(fn.code : column ; count ; sumBits ; valueBits) → 64-hex
    public static boolean deepEquals(AggResult a, AggResult b);
    // 3 IAE: values == null / values 含 null / unionRowCount < 0；构造后 values 不可变
}
```

### 4.4 AggExecutor

```java
public final class AggExecutor<T extends DsTableAdapter> {
    public AggResult execute(AggQueryPlan plan,
                             AggRowSource<T> source,
                             Map<String, ToDoubleFunction<T>> columnExtractors);
    // 4 IAE: plan == null / source == null / columnExtractors == null / 某 AggSpec.column 无 extractor
    // 2 ISE: source.fetch 返回 null / 行 getId() == null（R7 去重前提不可满足）
    // plan.aggs 为空 → 返回 [values=空, unionRowCount=0]（0 次 fetch，短路）
}
```

### 4.5 AggMerger

```java
public final class AggMerger {
    public static AggResult merge(List<AggResult> partials);
    // IAE: partials == null / isEmpty / 含 null / 各 partial 的 agg 序列（AggSpec 逐项）不一致
    // COUNT → Σcount, value = Σcount ; SUM → Σsum, value = Σsum ;
    // AVG → Σsum / Σcount（Σcount == 0 → 0.0）；MIN → min(value)；MAX → max(value)
    // unionRowCount = Σ partials.unionRowCount（同 agg 序列 → 同顺序 → 逐位合并）
    public static AggValue mergeValues(AggValue a, AggValue b);   // IAE: null / spec 不等
}
```

---

## 5. 测试（T1-T6，31 TR；Gate B 拆 4 批：P22-P24 / P25-P27 / P28-P30 / P31-P39）

| T | 测试类 | TR | 覆盖 |
|---|--------|----|------|
| T1 | `P39_T1_AggValueResultTest` | 5 | AggValue 4 IAE + count/sum/value 语义 + equals/hashCode/toString；AggResult 3 IAE + 顺序/访问器 + fingerprint 64-hex 稳定 + deepEquals + values 不可变 |
| T2 | `P39_T2_ExecutorSingleMintermTest` | 6 | 内存行源：COUNT 非 NULL 计数 / SUM（含全 NULL→0.0,count=0）/ AVG=Σ/count / MIN / MAX / 4 IAE（plan·source·cols null + 未知列）/ 2 ISE（fetch null、id null）+ `aggs` 空短路 0 fetch |
| T3 | `P39_T3_UnionDedupTest` | 3 | 重叠 minterm 去重（`(a = 1 OR b = 2) AND (a = 1 OR c = 3)` 4 minterm 手写真值对比）+ minterm 顺序无关（同值同 fingerprint）+ unionRowCount = distinct PK 数 |
| T4 | `P39_T4_E2eAggExecutorTest` | 3 | 真实 `DsDatabaseLocal` + `GenericManagerRowSource` E2E：5 聚合 vs 手算真值（含 NULL 行）；`AggParser.parse` → `AggExecutor.execute` 全链路；跨进程 fingerprint 一致（子 JVM spawn，复用 P38_T4 模板） |
| T5 | `P39_T5_Gatekeeper9YTest` | 9 | Y1~Y9（ZeroLeak3=3 + `parser/v3` 文件数=5 双保 / NG-3 import 白名单 + pom 0 新 dep / TOPK / Facade 探针 / 路由 / 类存在 / HybridHit ctor=2 / ShardTransport 5 方法 / 0 TODO） |
| T6 | `P39_T6_NFR_Test` | 5 | execute P95 ≤ 2s（8 minterm × 2 agg × 1k 行）+ 6 类 Javadoc ≥ 10 行 + CPD 0 重复块 + 0 println/printStackTrace + IAE ≥ 8 + 6 场景 100% |

**TR 总数**：5+6+3+3+9+5 = **31 TR**

### 5.1 Gate B 拆批命令（继承 P37/P38 模板，必须 `-pl p2p-db`）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P22_*,ds.P23_*,ds.P24_*'
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P25_*,ds.P26_*,ds.P27_*'
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P28_*,ds.P29_*,ds.P30_*'
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*'
```

### 5.2 验收门槛
- P39 own **31/31 TR 0F0E**
- P22-P39 拆 4 批全量回归 0F0E（允许已知基线 fail `P28_T6.tr64` 沿用）
- **union 正确性**：重叠 minterm 去重后结果 = 手写「distinct PK 集合」真值（逐聚合全等）
- **合并正确性**：`AggMerger.merge(逐 shard 部分结果)` = 单进程一次性 execute 结果（逐聚合全等）
- **跨进程确定性**：同 DSL + 同数据 → 同 `fingerprint()`（64-hex）
- **0 副作用**：execute 期间 0 触 FulltextStore / 0 改 `bucketAccessCounter` / 0 写库
- 空集 / 全 NULL / 未知列 / agg 序列错配 → 语义明确（0.0+count=0 或 IAE），绝不静默

---

## 6. 逆指标（Anti-Metrics）

| 逆指标 | 错误做法 | 正确做法 |
|--------|----------|----------|
| 假设 minterm 互斥 | 直接 Σ 各 minterm 的 COUNT/SUM | **按 PK 去重** 后再聚合（R7）；DNF 允许重叠 |
| AVG 二次平均 | 各 minterm AVG 求算术平均 | 用 `sum`/`count` 加权：Σsum/Σcount（R10 + AggValue.sum） |
| 重复取行 | 每个 agg 各 fetch 一次 minterm | 每 minterm **只 fetch 一次**，行集复用于该 minterm 的所有 agg |
| 越界聚合下推 | 改 ORM/列存/服务端加聚合接口 | 纯客户端引擎；0 改 QueryWrapper/GenericManager/0 新 handler |
| 落错子包 | 6 新类塞 `parser/v3/` | 新子包 `cluster/agg/`（`parser/v3` 文件数锁 5，P38_T5.Y2 基线） |
| NULL 当 0 算 | NULL 计入 COUNT/AVG 分母 | SQL 语义：忽略 NULL（R8），`count`/`sum` 仅统计非 NULL |
| HashMap 存结果 | agg 结果无序 | `List<AggValue>` 保持 `plan.aggs` 声明序（R9） |

---

## 7. 风险与回退

| 风险 | 触发 | 回退 |
|------|------|------|
| 主键为空导致无法去重 | 行源自造对象（无 `getId()`） | `AggExecutor` 抛 `IllegalStateException`（显式 fail，不静默重复计数）；内存行源自测须自赋 id |
| 内存放大（大结果集去重） | 超大批量 union | `HashSet<Long>` 仅存 PK（8B/row）；MVP 不引入 spill，文档标注（P39 不做外部排序/分块） |
| 并发 | 多线程共用 `AggRowSource` | MVP 单线程语义；`AggExecutor` 无状态（每次 execute 局部变量），不承诺线程安全 |
| FT 混合条件 | `FT('x') AND a = 1` | `GenericManagerRowSource` 抛 `UnsupportedOperationException`（FT 行源由业务方实现 `AggRowSource`） |
| agg 序列错配合并 | 两 shard 用不同计划的 AggResult 合并 | `AggMerger` IAE（R10） |
| 浮点漂移 | Σ 大数 + 小数 | `fingerprint` 用 `doubleToLongBits`（位级确定）；AVG 只在 merge 时为 Σsum/Σcount 一次除法 |
| 破坏 P38 基线 | 新类落 `parser/v3/` | T5 Y1 同时断言「根包=3」+「parser/v3 = 5 文件」双保（R1） |

---

## 8. Probe 结论（7 锚点实锤）

1. **P38 `AggQueryPlan`**（`parser/v3/AggQueryPlan.java`）：`clause`(L51) / `aggs`(L54) / `tasks()`(L91-105，笛卡尔积 + 惰性缓存) / `taskCount()`(L112) / `MAX_AGG = 8`(L48)。**结论：P39 只需 `clause` + `aggs` 即可执行，`tasks()` 仅用于顺序对齐（0 改）**。
2. **P34 `ConditionNode`**（`parser/v2/ConditionNode.java`）：`Kind ∈ {FT, ORM, IS_NULL, IS_NOT_NULL}`(L48-50) + `ormCol/ormOp/ormValueA/ormValueB`(L52-57)；op 支持集见 `toString` 分派（L200-234）= `OP_EQ/OP_NE/OP_GT/OP_GE/OP_LT/OP_LE/KW_BETWEEN/KW_IN/KW_IS/KW_LIKE`。**结论：`toWrapper` 可直接覆盖 ORM + IS_NULL/IS_NOT_NULL，FT 显式拒绝**。
3. **`QueryWrapper`**（`database/integration/QueryWrapper.java`）：已提供 `eq`(L138)/`ne`(L134)/`gt`(L98)/`ge`(L106)/`lt`(L102)/`le`(L110)/`between`(L150)/`in`(L118)/`isNull`(L142~146)/`select`(L158)/`orderByAsc·Desc`。**结论：minterm → WHERE 0 改 QueryWrapper 即可表达**。
4. **`GenericManager`**（`database/integration/GenericManager.java`）：`findList(QueryWrapper)`(L1935-1953) 本地走 `listEntities`(L2783 → `db.getTable`) / 分布式走 `queryIds`，双通道同签名；`count(QueryWrapper)`(L1973-1985)。**结论：行源取值点唯一，本地/分布式同一实装复用**。
5. **PK 可用性**：`DsTableAdapter.getId()`(L99) + 取行路径 `obj.setId(rowId)`（`DsDatabaseLocal` L3174，`getTable(Class,long,long)` L2267→L2271 链路）。**结论：`findList` 取回的行带 PK → R7 union 去重可行**。
6. **P38 基线硬约束**：`P38_T5_Gatekeeper9YTest.y2`(L85) 断言 `assertEquals("parser/v3 源文件数应为 5", 5, javaFiles.size())`。**结论：P39 新类**必须**落新子包 `cluster/agg/`，否则既有基线 TR 直接 Fail**。
7. **ZeroLeak3=3 实锤**：fulltext 根包直接 `.class`（非 `$` 内部类）= {FulltextSegmenter, FulltextStore, TermDictStore}（`P38_T5.y1` L56-64 断言 `size() == 3`）。**结论：新子包方案天然满足 R1**。

---

## 9. 签批区（Gate A REV1，2026-10-21 用户 VERBATIM 批准）
- [x] 用户批准 OQ12-⑰ 候选项 ③：DSL v3 聚合执行/合并引擎（Scope 锚定 above）
- [x] 批准 6 新类全放**新子包** `cluster/agg/`（ZeroLeak3=3 + `parser/v3` 文件数=5 双保）
- [x] 批准 P30/P34/P38 源码 0 改红线（`AggParser.parse` / `AggQueryPlan` / `ConditionNode` 不动）
- [x] 批准 MVP 边界（union 去重执行 + AggMerger 合并；无 GROUP BY / 无 FT 条件 / 无分布式下发 / 无 ORM 下推）

---

## 10. 实装偏差说明（REV1 → REV2，Implement 完成后回填）

| 项 | 计划（REV1） | 实装（REV2 实锤） | 偏差 |
|----|------------|-----------------|------|
| 新类数 | 6 个，全放 `fulltext/cluster/agg/` | **6 个**（AggRowSource / GenericManagerRowSource / AggValue / AggResult / AggExecutor / AggMerger）100% 放 `cluster/agg/` | 0 |
| 0 改源码 | P30/P34/P38/QueryWrapper/GenericManager/HJC 全 0 改 | `git diff --stat` 中 `parser/` 0 处、fulltext 既有类 0 处、`p2p-db/pom.xml` 0 处改动（benchmark/P33 的 2 处 Javadoc 重排 mtime=2026-10-15，P33 时期历史残留，与 P39 无关） | 0 |
| pom | 0 新增 dep/plugin | `p2p-db/pom.xml` 未出现在 `git diff` | 0 |
| union 去重语义 | 跨 minterm 按 PK `getId()` 去重（R7） | `AggExecutor` 用 `HashSet<Long> seen` 全局去重；T3 手写 distinct-PK 真值 3/3 全等；T6 分片合并 = 单次执行全等 | 0 |
| NULL 语义 | SQL 风格忽略 NULL（R8） | `ToDoubleFunction` 无法返回引用 → **NaN 作 NULL 哨兵**，`Double.isNaN(x) → continue`；T2 全 NULL→SUM `0.0`+`count=0` 实锤 | REV1 未指定哨兵载体；REV2 明示 NaN |
| merge 语义 | COUNT/SUM 相加、AVG Σsum/Σcount、MIN/MAX 极值（R10） | `AggMerger.mergeValues` 实装；AVG 精确加权（T6 tr65 断言 ≠ 37.5 算术平均）；空部分 MIN/MAX `0.0` 哨兵不参与极值 | 0 |
| fingerprint | SHA-256 64-hex，跨进程稳定（R9） | `AggResult.fingerprint()` 逐 value 编码 `(fn.code, column, count, doubleToLongBits(sum), doubleToLongBits(value))`；T4 子 JVM spawn 校验 64-hex 一致 | 0 |
| IAE + ISE 计数 | 4 IAE + 2 ISE（AggExecutor） | 全 6 类：`throw new IllegalArgumentException` **23 处**、`throw new IllegalStateException` **4 处**（AggExecutor 2 + AggResult 1 + AggRowSource 1 契约声明）、`throw new UnsupportedOperationException` **2 处**（FT 边界 + 未支持 op）；T6 tr64 断言 IAE ≥ 8 通过 | 计数口径 REV1 仅述 AggExecutor；REV2 给全量 |
| Gate B 实跑数字 | 待实跑（禁止预填） | 批 1 `P22-P24` = **76/76 0F0E**；批 2 `P25-P27` = **80 run**（P25 25/25 + P26 25 run 含 2 项环境计时 fail tr61/tr62 + P27 30/30）；批 3 `P28-P30` = **100 run 含 1 已知基线 fail `P28_T6.tr64`**；批 4 `P31-P39` = **286/286 0F0E**（P31_T4 TCP 抖动 + P31_T6 900Runs 超时 2 项经单跑复验 4/4 + 5/5 全绿）。**总计 542 run，539/542 0F0E，3 项非通过 = 2 环境计时 + 1 已知基线，均与 P39 无关**；P39 own **31/31 0F0E** | 0（数字实跑回填，未沿用估计值） |
| 环境教训 | — | `target\tmp` 曾膨胀至 18.7GB + 仓库根 `.tmp` 曾累积 **39.7GB**（2026-10-13 陈旧残留）→ P27_T5 触发 `IOException: 磁盘空间不足`；清理后复跑 30/30 全绿。**跨批前须清理 `target\tmp` 与仓库根 `.tmp`** | 新增（REV2 首次记录） |
