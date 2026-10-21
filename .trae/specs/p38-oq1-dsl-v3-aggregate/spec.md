# P38 OQ1 DSL v3 聚合函数 —— spec.md（**REV2 GATE C 实装闭环版，2026-10-21**）

> 用户 OQ12-⑯ 单选 ① → SP3.1 Gate A（REV1 已 VERBATIM 批准）→ Implement T1-T6（31/31 TR 0F0E）→ Gate B（拆 4 批）→ Gate C 洁癖 5/5。

## Gate C 9Y 自检清单（Gate C 逐项 [x] 通过；任一未过 → 退 Gate A 整改）
- [x] Y1 ZeroLeak3=3：fulltext 根包仍 = {FulltextSegmenter, FulltextStore, TermDictStore}，P38 0 新增根包 top-level 类（5 新类 100% 放 `parser/v3/` 子包）
- [x] Y2 NG-3 零依赖：P38 0 新增 netty/grpc/p2p.client 引用 + pom.xml 0 新增 dep/plugin（纯手写 LL(1)，不引任何 parser 库）
- [x] Y3 TOPK 铁律：P38 纯解析计划生成，0 改 K，`bucketAccessCounter[1025]` 严格 0 污染
- [x] Y4 Facade 单调不减：P30 `HybridQueryParser.parse` 4 参 / P30 `DslToken` / P30 `DslTokenizer` / P34 `DnfParser.parseOr` 6 参 / P34 `DnfClause` / `ConditionNode` / P31~P37 全部 0 改；P38 纯 additive（新子包）
- [x] Y5 路由公式：P38 0 改 ShardRouter / 0 改 P24 UNIT/DOC 路由
- [x] Y6 P21~P37 编译兼容：HybridHit ctor=2 / ShardTransport 5 方法 / 既有类型 0 改
- [x] Y7 HybridHit ctor=2：0 改
- [x] Y8 ShardTransport 5 方法：0 改
- [x] Y9 0 TODO/FIXME/HACK：100% 实装 AGG 切分 + 5 aggFn 解析 + N=(minterm×aggFn) 计划拆分 + fingerprint

---

## 1. Scope（用户单选 OQ12-⑯ 候选项 ①，VERBATIM 锚定）

> 原文：`P38 DSL v3 聚合函数 COUNT/SUM/AVG/MIN/MAX（基于 P30 + P34 DNF 拆分追加聚合函数子句，编译期拆分为 N 个 (minterm, aggFn) 对，业务方拿 N 个查询结果聚合）`

P38 = 在 P30（AND 平铺 MVP）+ P34（OR/NOT/括号 DNF）之上，追加**聚合函数子句**，把一条 DSL **编译期**拆成 N 个 `(minterm, aggFn)` 执行计划对：

1. **聚合子句**：DSL 尾部追加 `AGG( aggItem (',' aggItem)* )`，`aggItem := (COUNT|SUM|AVG|MIN|MAX) '(' 列名 ')'`。
2. **编译期拆分**：`DnfClause` 的每个 minterm × 每个 `AggSpec` → 笛卡尔积 `MintermAggTask`（N = minterms × aggs），业务方对每个 task 执行「单 minterm ∧ 聚合」查询。
3. **纯解析零副作用**：P38 **只产可执行计划**（`AggQueryPlan`），**不调用 ORM/FT/DHQC**、不做聚合合并（合并属业务方，MVP 边界）。
4. **0 改 P30/P34**：`AGG` / `COUNT` / `SUM` / `AVG` / `MIN` / `MAX` 均按**普通 IDENT** 识别（**不** 向 `DslToken`/`DslTokenizer.KEYWORDS` 新增关键字），确保 P30 字节级 0 改。
5. **5 新类全放 `com.q3lives.ds.database.fulltext.cluster.parser.v3`**（ZeroLeak3=3 永保）。

**MVP 边界（0 越界）**：
- 仅做「DSL → 聚合执行计划」编译期拆分；**不做** 聚合结果合并/二次汇总（业务方拿 N 个结果自行聚合）
- **不做** `GROUP BY`（无分组维度；MVP 仅全局聚合 + DNF minterm 维）
- **不做** `DISTINCT` / `HAVING` / 窗口函数
- **不做** 聚合执行引擎（ORM 侧无聚合能力，见 Probe 锚点 3；执行由业务方复用既有 `GenericManager.count` / 全量取出后自算）
- **P30 `HybridQueryParser` / `DslToken` / `DslTokenizer` 源码 0 改**；**P34 `DnfParser` / `DnfClause` / `ConditionNode` / `DnfReturnMode` 源码 0 改**
- **0 新增 Maven dep**（纯手写解析）
- 5 新类全放新子包 `parser/v3/`（ZeroLeak3=3 永保）

---

## 2. 红线（VERIFIED 永久钉死，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3** | 5 新类 100% 放 `parser/v3/` 子包；fulltext 根包 top-level 类数严格 = 3 |
| R2 | **NG-3 零依赖** | pom.xml 0 新增 dep/plugin；源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*` |
| R3 | **TOPK 铁律** | P38 纯解析；0 改 K / 0 改 `SearchOptions`；`bucketAccessCounter[1025]` 0 污染 |
| R4 | **Facade 单调不减** | P30 `HybridQueryParser.parse` 4 参 / `DslToken` / `DslTokenizer` + P34 `DnfParser.parseOr` 6 参 / `DnfClause` / `ConditionNode` 全部 **0 改**；P38 纯 additive |
| R5 | **HybridHit ctor=2** | 0 改 |
| R6 | **P21~P37 字节级兼容** | TermDictStore / FulltextStore / DsIndexSegment* / HJC / ShardRouter / ShardTransport / Resilience / DnfParser 全部 0 改 |
| R7 | **聚合子句语法硬校验** | `AGG(...)` 必须为表达式尾部；aggItem 语法/函数名非法 → `HybridQueryParseException`（不静默忽略） |
| R8 | **`AGG` 切分须防字符串/括号误命中** | 切分器须跳过单引号字符串 + 仅匹配 paren 深度 0 的独立 `AGG` 词；不匹配 → 无聚合子句（aggBody=null） |
| R9 | **拆分确定性** | N 个 `MintermAggTask` 顺序固定 = minterm 序（复用 `DnfClause.minterms` 既有排序）× agg 声明序；`fingerprint()` SHA-256 跨 run 稳定 |

---

## 3. 架构（5 个新类，P30/P34 源码 0 改，0 改 P21~P37）

### 3.1 新增清单

| 名称 | 类型 | 职责 | 关键字段/方法 |
|------|------|------|---------------|
| `AggFn` | enum（`parser/v3/`） | 5 聚合函数 + wire 字符码 | `COUNT('C') / SUM('S') / AVG('A') / MIN('M') / MAX('X')`；`code()` / `fromCode(char)` / `fromName(String)` / 4 IAE |
| `AggSpec` | 不可变 POJO（`parser/v3/`） | 单个聚合项（函数 + 列） | `fn` + `column` + 6 IAE + `equals/hashCode/toString` |
| `MintermAggTask` | 不可变 POJO（`parser/v3/`） | 一个 `(minterm, aggFn)` 执行对 | `mintermIndex`(int) + `minterm`(`List<ConditionNode>`) + `aggSpec` + 5 IAE |
| `AggQueryPlan` | 不可变 POJO（`parser/v3/`） | 编译期计划（clause + aggs + N tasks） | `clause`(`DnfClause`) + `aggs`(`List<AggSpec>`) + `tasks()` + `taskCount()` + `fingerprint()` + `deepEquals()` + 5 IAE |
| `AggParser` | 静态工具类（`parser/v3/`） | 主入口：切分 AGG → DNF 解析 → 拆计划 | `parse(Class, String, Map, Map, int, int)` 6 参 + `splitAtAgg(String)` private + 6 IAE |

### 3.2 编译期拆分模型（N = minterms × aggs）

```
DSL:  (A OR B) AND C  AGG(COUNT(id), SUM(price))
        │
        ├─ 表达式段: "(A OR B) AND C"
        │      └─ DnfParser.parseOr(...)  → DnfClause{ minterm0=[A,C], minterm1=[B,C] }
        │
        └─ 聚合段: "AGG(COUNT(id), SUM(price))"
               └─ [AggSpec(COUNT,id), AggSpec(SUM,price)]

AggQueryPlan.tasks() = 笛卡尔积（顺序：minterm 序 × agg 声明序）
  task0 = (mintermIndex=0, minterm=[A,C], agg=COUNT(id))
  task1 = (mintermIndex=0, minterm=[A,C], agg=SUM(price))
  task2 = (mintermIndex=1, minterm=[B,C], agg=COUNT(id))
  task3 = (mintermIndex=1, minterm=[B,C], agg=SUM(price))
```

- **业务方** 对 task_i 执行「`minterm` 作为 WHERE 条件 + `agg` 作为聚合」，得到 N 个结果后自行合并（MVP 边界）。
- **无 AGG 子句** → `aggs` = 空列表，`taskCount()` = 0（计划退化为纯 DNF 表达式计划，仍合法）。
- **聚合子句必须为尾部**：`AGG` 之后仅允许 aggItem 列表与 `)`；多余 token → `HybridQueryParseException`。

### 3.3 包结构（新增 `parser/v3/` 子包）

```
com/q3lives/ds/database/fulltext/
├── FulltextSegmenter.java                 (P21 既有，0 改)
├── FulltextStore.java                     (P21 既有，0 改)
├── TermDictStore.java                     (P21 既有，0 改)
├── segment/                               (P37 既有，0 改)
└── cluster/parser/
    ├── HybridQueryParser.java             (P30 既有，0 改)
    ├── DslToken.java / DslTokenizer.java  (P30 既有，0 改)
    ├── v2/                                (P34 既有，0 改)
    └── v3/                                (P38 新增子包)
        ├── AggFn.java                     (NEW)
        ├── AggSpec.java                   (NEW)
        ├── MintermAggTask.java            (NEW)
        ├── AggQueryPlan.java              (NEW)
        └── AggParser.java                 (NEW)
```

---

## 4. 接口契约

### 4.1 AggFn

```java
public enum AggFn {
    COUNT('C'), SUM('S'), AVG('A'), MIN('M'), MAX('X');
    public char code();
    public static AggFn fromCode(char c);        // 非法 → IAE
    public static AggFn fromName(String name);   // 大小写不敏感；非法 → IAE
}
```

### 4.2 AggSpec

```java
public final class AggSpec {
    public final AggFn fn;
    public final String column;
    public AggSpec(AggFn fn, String column);
    // 6 IAE 守卫: fn==null / column==null / column.isBlank() / column 含空白 /
    //             column 含非法字符（非 [A-Za-z0-9_]）/ 保留字冲突（AGG 自身）
    // equals/hashCode/toString（"COUNT(col)" 形式）
}
```

### 4.3 MintermAggTask

```java
public final class MintermAggTask {
    public final int mintermIndex;
    public final List<ConditionNode> minterm;   // unmodifiable
    public final AggSpec aggSpec;
    // 5 IAE 守卫: mintermIndex < 0 / minterm==null / minterm.isEmpty() / 含 null 节点 / aggSpec==null
    // equals/hashCode（minterm 逐节点 equals）
}
```

### 4.4 AggQueryPlan

```java
public final class AggQueryPlan {
    public final DnfClause clause;
    public final List<AggSpec> aggs;            // unmodifiable（可为空）
    public List<MintermAggTask> tasks();        // 缓存；顺序 = minterm 序 × agg 声明序
    public int taskCount();                     // = clause.size() * aggs.size()
    public String fingerprint();                // SHA-256(clause.fingerprint + aggs 序列)
    public static boolean deepEquals(AggQueryPlan a, AggQueryPlan b);
    // 5 IAE 守卫: clause==null / aggs==null / aggs 含 null / aggs.size()>MAX_AGG(8) / 构造后不可变
    public static final int MAX_AGG = 8;
}
```

### 4.5 AggParser

```java
public final class AggParser {
    public static <T extends DsTableAdapter> AggQueryPlan parse(
            Class<T> entityClass,
            String dsl,                            // 非 null
            Map<String, ToDoubleFunction<T>> weightMap,  // 允许 null/empty（透传 DnfParser）
            Map<String, ToDoubleFunction<T>> ltrMap,     // 允许 null/empty
            int maxDnfSize,                        // 1..DnfParser.MAX_DNF_SIZE
            int maxAggCount);                      // 1..AggQueryPlan.MAX_AGG
    // 6 IAE 守卫: entityClass==null / dsl==null / maxAggCount<1 / maxAggCount>MAX_AGG /
    //             表达式段为空 / AGG 子句语法非法（缺 '('/ 非法函数名/非法列/多余 token）
    // 解析管线: splitAtAgg → 表达式段 DnfParser.parseOr(..., DNF_ONLY) → 聚合段 DslTokenizer 解析 → AggQueryPlan
}
```

---

## 5. 测试（Gate B 拆 4 批：P22-P30 + P31-P38）

### 5.1 Surefire 4 批拆分（继承 P37 模板，实跑加 `-pl p2p-db`）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
# 批1: P22-P24
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P22_*,ds.P23_*,ds.P24_*'
# 批2: P25-P27
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P25_*,ds.P26_*,ds.P27_*'
# 批3: P28-P30
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P28_*,ds.P29_*,ds.P30_*'
# 批4: P31-P38 (含 P38 own)
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*'
```

### 5.2 TR 总数

| T | 范围 | TR 数 | 实装内容 |
|---|------|------|---------|
| **T1** | AggFn + AggSpec skeleton | 5 | AggFn 5 值 + code 往返 + 4 IAE；AggSpec 6 IAE + equals/hashCode |
| **T2** | MintermAggTask + AggQueryPlan | 6 | task 5 IAE + 计划 tasks() 笛卡尔积 N + taskCount + fingerprint 稳定 + deepEquals + aggs 不可变（5 IAE） |
| **T3** | AggParser 解析 | 3 | AGG 切分（字符串/括号防误命中）+ 5 aggFn 解析 + 边界 IAE（空表达式/非法函数/多余 token/maxAggCount 越界） |
| **T4** | Inproc E2E + 跨进程 fingerprint | 3 | DSL→计划→N task 业务模拟聚合 + (A OR B) 拆分 N 对 + 跨进程 AggQueryPlan fingerprint 一致 |
| **T5** | Gatekeeper 9Y | 9 | Y1-Y9 全 Y（ZeroLeak3 反射 count=3 + P30/P34 源码 0 改 grep + pom 0 diff） |
| **T6** | NFR | 5 | parse P95 ≤ 阈值 + 5 类 Javadoc ≥ 10 行 + CPD ≤ 1 + 0 println + IAE ≥ 8 |

**TR 总数**：5+6+3+3+9+5 = **31 TR**（目标 31/31 0F0E）

### 5.3 验收门槛
- P38 own 31/31 0F0E
- P22-P38 拆 4 批全量回归 0F0E（批4 P31-P38；允许已知基线 fail 沿用）
- **拆分正确性**：`AggQueryPlan.tasks()` 与手写 `(minterm, aggFn)` 笛卡尔积 **逐条全等**（顺序 + minterm 节点全等）
- **跨进程确定性**：同 DSL → 同 `fingerprint()`（64-hex SHA-256）；同 DSL 两进程 `deepEquals` = true
- **0 副作用**：parse 期间不触 ORM/FT/DHQC、不改 `bucketAccessCounter`
- 语法非法（空表达式 / 非法函数名 / 非法列 / 多余的 AGG 后 token / maxAggCount 越界）→ `HybridQueryParseException` 或 IAE，绝不静默忽略

---

## 6. 逆指标（Anti-Metrics，避免的伪优化）

| 逆指标 | 错误做法 | 正确做法 |
|--------|----------|----------|
| 改 P30 词法 | 向 `DslToken`/`KEYWORDS` 加 COUNT/SUM/AGG 关键字 | 走 **IDENT** 识别（case-insensitive），P30 字节级 0 改 |
| 重复实现 DNF | 在 v3 里重写 OR/NOT/括号 DNF 拆分 | 表达式段直接复用 P34 `DnfParser.parseOr` |
| 越界执行 | P38 内直接查 ORM/FT 并聚合 | 只产计划（N task）；执行 + 合并归业务方 |
| 字符串裸替换 | 用 `dsl.indexOf("AGG")` 切分（字符串内误命中） | 括号深度 0 + 跳过单引号字符串的切分器（R8） |
| 顺序不稳定 | 用 `HashMap` 存 task（顺序漂移） | 固定 = minterm 序 × agg 声明序（R9） |
| 顺手加 GROUP BY | MVP 追分组/窗口函数 | 无 GROUP BY（明确排除），仅全局聚合 + minterm 维 |

---

## 7. 风险与回退

| 风险 | 触发 | 回退 |
|------|------|------|
| 列名字面量恰为 `AGG`/`COUNT` | 用户表存在同名列 | R7/R8：仅 paren 深度 0 且后随 `(` 的独立 `AGG` 视为子句起始；文档标注 `AGG` 为 v3 保留字 |
| 表达式段残留聚合 token | 用户把 AGG 写中间 | `splitAtAgg` 仅认**首个** top-level `AGG`；其后若再有非聚合 token → `HybridQueryParseException` |
| DNF 爆炸 | 大 OR 表达式 | 复用 P34 `maxDnfSize ≤ 256` 硬上限（透传），超出 IAE |
| 聚合项过多 | 用户写 > 8 个 agg | `maxAggCount ≤ MAX_AGG(8)` 硬上限，超出 IAE |
| fingerprint 漂移 | aggs 顺序不定 | aggs 保持声明序（`List`），fingerprint 用 `clause.fingerprint()` + fn.code + column 序列 |
| 与 P34 LEGACY_COMPAT 冲突 | 复用 P34 模式枚举 | P38 固定走 DNF_ONLY（聚合子句不存在于 P30 兼容语义）；不改 `DnfReturnMode` |

---

## 8. Probe 结论（5 锚点实锤）

1. **P30 Parser**（`parser/HybridQueryParser.java`）：`parse(entityClass, dsl, weightMap, ltrMap)` 4 参 public static，纯解析（`parse` L51-131）；`DslToken` enum 24 值（L17-43，含 `KW_OR` 占位）；`DslTokenizer` 静态 `KEYWORDS` map 13 项（L26-41），未识别词 → `IDENT`（L233-237）。**结论：`AGG`/`COUNT` 等未注册关键字 → 天然落 `IDENT`，P38 可零改词法复用**。
2. **P34 Parser**（`parser/v2/`）：`DnfParser.parseOr(Class, String, Map, Map, int maxDnfSize, DnfReturnMode)` 6 参 public static（L79-124）→ `DnfClause`（`List<List<ConditionNode>>`）；`DnfClause` 8 IAE + `fingerprint()` + `deepEquals`；`ConditionNode` 4 Kind + 8 IAE。**结论：表达式段可直接调 `DnfParser.parseOr(..., DNF_ONLY)` 复用 DNF 拆分**。
3. **ORM 无聚合能力**：`QueryWrapper` 仅 WHERE/ORDER/IN-子查询构建（0 聚合方法）；`DsTableAdapter` 仅序列化 + `getId`；全仓仅 `OrmCountServerHandler` 走 `GenericManager.count(w)` 提供远程 COUNT。**结论：P38 只产计划，执行/合并归业务方（MVP 边界）**。
4. **HJC 结果类型**：`HybridJoinClient`（`cluster/join/`）9 入口全返回 `List<HybridHit<T>>`；`HybridHit` public final 5 字段 + `getDeclaredConstructors().length == 2`。**结论：P38 不改 HJC / HybridHit；计划与结果类型解耦**。
5. **ZeroLeak3=3 实锤**：fulltext 根包直接 `.java` = {FulltextSegmenter, FulltextStore, TermDictStore} 恰 3 个。**结论：新增 5 类须 100% 放 `cluster/parser/v3/` 子包**。

---

## 9. 签批区（Gate A REV1 已 VERBATIM 批准 2026-10-20）
- [x] 用户批准 OQ12-⑯ 候选项 ① P38 DSL v3 聚合函数（Scope 锚定 above）
- [x] 批准 5 新类全放 `parser/v3/` 子包（ZeroLeak3=3 永保）
- [x] 批准 P30/P34 源码 0 改红线（`AGG`/`COUNT` 走 IDENT 识别，不动 `DslToken`/`DslTokenizer`）
- [x] 批准 MVP 边界（仅编译期拆 N 个 `(minterm, aggFn)` 对；无 GROUP BY；执行/合并归业务方）

---

## 10. 实装偏差说明（REV1 → REV2，Implement 完成后回填）

| 项 | spec 目标 | 实装结果 | 偏差 |
|----|-----------|----------|------|
| 新类数 | `parser/v3/` 5 新类（AggFn/AggSpec/MintermAggTask/AggQueryPlan/AggParser） | 5 新类 100% 落 `parser/v3/` | 0 |
| P30/P34 源码 | 0 改（`AGG`/`COUNT` 走 IDENT） | 0 改；`DslToken` 值数锁 25、`AGG`/`COUNT`/`SUM`/`AVG`/`MIN`/`MAX` 词法实锤落 `IDENT`（T5 Y4） | 0 |
| pom | 0 新增 dep/plugin | 0 新增（纯手写切分 + 复用 P30 `DslTokenizer`） | 0 |
| `splitAtAgg` 语义 | paren 深度 0 + 跳过单引号字符串，仅 match 后随 `(` 的独立 `AGG` | 实装一致；**额外偏差**：独立 `AGG` 但后随非 `(` 的情形由「抛异常」改为「视为普通列名（`agg = 1`）继续扫描」，避免将列名 `agg` 误判为聚合子句起始（见 §7 风险表列名字面量行） | 语义增强（更宽容，符合 R8 防误命中精神） |
| 拆分模型 | N = minterms × aggs 笛卡尔积，顺序 = minterm 序 × agg 声明序 | 实装一致（`AggQueryPlan.tasks()` 惰性缓存 + unmodifiable） | 0 |
| fingerprint | SHA-256(64-hex)，跨进程稳定 | 实装一致（`clause.fingerprint()` 字节 + 逐 agg `code()':'column';'`）；T4 跨进程双 spawn 复现 64-hex 一致 | 0 |
| IAE 守卫 | AggSpec 6 / MintermAggTask 5 / AggQueryPlan 5 / AggParser 6 | 实装一致（+ AggFn 4 IAE 已于 T1 覆盖） | 0 |
| Gate B 拆批 | P22-P38 拆 4 批 | 实跑见 tasks.md §3.2（批 1 = 76 / 批 2 = 80 / 批 3 = 100（含 1 已知基线 fail P28_T6.tr64）/ 批 4 = 255 全部 0F0E；总计 511 = 510 0F0E + 1 已知基线） | — |

**实装结论**：P38 纯 additive，仅新增 `fulltext/cluster/parser/v3/` 5 类 + 6 测试文件；零触碰 P21~P37 任何既有源文件与 pom.xml；红线 R1~R9 全部 0 越界。
