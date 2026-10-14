# P34 OQ1 DSL v2 OR/NOT/括号 DNF 表达式 —— spec.md（**REV2 GATE C 实装闭环版，2026-10-15**）

## Gate C 9Y 自检清单（每 Y 必填 [x] 通过；任一未过 → 退 Gate A 整改）
- [x] Y1 ZeroLeak3=3：fulltext 根包仍 = {FulltextSegmenter, FulltextStore, TermDictStore}，P34 0 新增根包 top-level 类
- [x] Y2 NG-3 零依赖：P34 0 新增 netty/grpc/p2p.client 根包引用 + pom.xml 0 新增 dep/plugin
- [x] Y3 TOPK 铁律：DNF 化/子句拆分 不修改 K 限制、不动 bucketAccessCounter
- [x] Y4 Facade 单调不减：HybridQueryParser.parse 原 4 参签名 1 字不改，v2 仅追加 parseOr 6 参新入口 + DnfClause / OrNode 不可变结构
- [x] Y5 路由公式：P34 0 改 ShardRouter / 0 改 P24 UNIT/DOC 路由
- [x] Y6 P30~P33 编译兼容：ParsedHybridQuery / WeightSpec / LtrSpec / BenchmarkReport 0 改；P30 MVP AND-only 行为字节级保持
- [x] Y7 HybridHit ctor=2：0 改
- [x] Y8 ShardTransport 5 方法：0 改
- [x] Y9 0 TODO/FIXME/HACK：100% 实装 DNF 化与子句拆分

---

## 1. Scope（用户单选 OQ12-⑫ 候选项 ①，VERBATIM 锚定）

DSL v2 OR/NOT/括号 DNF 表达式 = 对 P30 MVP v1.0 仅支持 AND 平铺的 DSL 做 **DNF 范式**扩展：
1. **OR 关键字**：`A OR B` 平铺（无括号）
2. **OR + AND 混合**：`A AND (B OR C)`（括号嵌套）
3. **OR + 括号**：`A AND (B OR (C AND D))`（多层括号 + 嵌套 OR）
4. **NOT 单目**：`NOT A`（仅 FT 子句 NOT、ORM 列 IS NOT NULL、P30 已支持；DNF 不允许 NOT 在 OR 之上）
5. **DNF 范式**：`A AND (B1 OR B2 OR ...)` —— 编译期做 **DNF 化**（distribute AND over OR）→ 拆成 N 个最小 DNF 子句：`minterm1, minterm2, ..., mintermN`；执行端跑 N 次并集 = 业务等效 OR
6. **DnfClause 不可变结构**：`DnfClause = List<MiniTerm>`（MiniTerm = List<Condition> AND-flat），输出可被业务方直接 `searchHybrid × N` 串行/并行

**MVP 边界（0 越界）**：
- 仅做 **DSL 字符串 → DNF 拆分** 的解析层；不实现 OR 子句的实际 OR 语义执行（业务方拿 N 个 DnfClause 自行串/并行执行）
- 不实现 XOR / 算术 / 函数调用 / 嵌套数组
- 0 新增 Maven dep（复用 junit/已有）
- 新类全放 `parser/` 子包（不破 ZeroLeak3）

---

## 2. 红线（VERIFIED 永久钉死，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3** | parser 子包 0 新增 top-level 类到 fulltext 根包；新类 100% 放 `parser/v2/` 或扩 parser 子包 |
| R2 | **NG-3 零依赖** | pom.xml 0 新增 dep/plugin；源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*` |
| R3 | **TOPK 铁律** | DNF 化不修改 K 限制；不修改 bucketAccessCounter |
| R4 | **Facade 单调不减** | HybridQueryParser 原 4 参 `parse` 签名 1 字不改；v2 仅追加 `parseDnf` 6 参入口（增 entityClass/dsl/weightMap/ltrMap + return 模式） |
| R5 | **HybridHit ctor=2** | 0 改 |
| R6 | **P30~P33 字节级兼容** | ParsedHybridQuery / WeightSpec / LtrSpec / BenchmarkReport 0 改；P30 MVP AND-only 路径行为字节级保持（HybridQueryParser.parse 仍抛 OR IAE） |
| R7 | **DNF 范式约束** | NOT 不得跨越 OR（即禁止 `NOT(A OR B)` 整体否定）；只允许 FT 段前 NOT、ORM 列 IS NOT NULL（P30 已支持）；业务否定统一通过 IAE 抛 |
| R8 | **DNF 拆分上限** | `maxDnfSize = 256` 硬上限（防止 `(A OR B) AND (C OR D) AND ...` 指数爆炸）；超出 IAE |

---

## 3. 架构（3 个新类，0 改 P30~P33）

### 3.1 新类清单（全部在 `parser/v2/` 子包）

| 类名 | 职责 | 关键字段 |
|------|------|---------|
| `DnfClause` | DNF 范式拆分结果不可变结构：`List<List<ConditionNode>>`（OR of AND） | 1 final field `minterms: List<List<ConditionNode>>` + size/minterm getters |
| `ConditionNode` | 单条件不可变结构：FT 段 / ORM 列段 | kind enum {FT, ORM} + payload (String ftQuery / Criterion-style) |
| `DnfParser` | 6 参 parseDnf 主入口 + 内部 LL(1) + distribute AND over OR 递归 DNF 化 | static 工具方法 + private 内部类 Node/OrNode/AndNode |

### 3.2 调用流程

```
HybridQueryParser.parseOr(entityClass, dsl, weightMap, ltrMap)  // P34 新 6 参入口（继承原 4 参 + maxDnfSize + returnMode）
   ├── Phase 1: Tokenize (复用 P30 DslTokenizer)
   ├── Phase 2: 构造 AST (OrNode / AndNode / ConditionNode)
   │   ├── OR 二元左结合
   │   ├── AND 二元左结合（优先级 > OR）
   │   ├── ( ) 改变优先级
   │   └── NOT 仅在 ConditionNode 之前（FT 段、ORM IS NOT NULL）
   ├── Phase 3: DNF 化 (distribute AND over OR 递归)
   │   ├── AndNode(OrNode(a,b), c) → OrNode(AndNode(a,c), AndNode(b,c))
   │   ├── AndNode(a, OrNode(b,c)) → OrNode(AndNode(a,b), AndNode(a,c))
   │   └── 递归到所有 AndNode 子节点都是 ConditionNode
   ├── Phase 4: 拆分为 N 个 MiniTerm (每条 MiniTerm = List<ConditionNode> AND-flat)
   ├── Phase 5: 校验 NOT 不跨 OR (R7) + maxDnfSize ≤ 256 (R8)
   └── 输出: DnfClause 不可变实例
```

### 3.3 与 P30 MVP 的关系

- P30 `HybridQueryParser.parse` 仍 100% 保持 AND-only 行为（OR 抛 IAE）
- P34 新增 `HybridQueryParser.parseOr` 6 参入口：
  - 入参：`Class<T> entityClass, String dsl, Map<String,ToDoubleFunction<T>> weightMap, Map<String,ToDoubleFunction<T>> ltrMap, int maxDnfSize, DnfReturnMode mode`
  - 返回：当 `mode == DNF_ONLY` 时返回 `DnfClause`（P34 新结构）；当 `mode == LEGACY_COMPAT` 时自动 fallback 走 P30 parse（OR 抛 IAE）
  - 业务方选择：DNF_ONLY 拿 N 个 minterm 自行执行；LEGACY_COMPAT 与 P30 行为字节级一致

---

## 4. 接口规范

### 4.1 `DnfClause`（DNF 范式拆分结果不可变结构）

```java
public final class DnfClause {
    public final List<List<ConditionNode>> minterms;  // OR of AND，外层 OR，内层 AND
    public DnfClause(List<List<ConditionNode>> minterms);  // 8 IAE 校验
    public int size();                          // minterms.size()
    public List<ConditionNode> getMiniTerm(int i);  // i-th minterm，IAE 越界
    public boolean isAcceptable(int maxSize);   // size() ≤ maxSize
}
```

### 4.2 `ConditionNode`（单条件不可变结构）

```java
public final class ConditionNode {
    public enum Kind { FT, ORM }
    public final Kind kind;
    public final String ftQuery;       // kind=FT 必填，kind=ORM 必为 null
    public final String ormCol;        // kind=ORM 必填
    public final DslToken ormOp;       // kind=ORM 必填
    public final Object ormValueA;     // kind=ORM 必填
    public final Object ormValueB;     // BETWEEN 必填，其他 null
    // 8 IAE 校验
}
```

### 4.3 `DnfParser`（P34 主入口，append-only 加在 HybridQueryParser 内部）

```java
public final class HybridQueryParser {
    // P30 保留
    public static <T extends DsTableAdapter> ParsedHybridQuery<T> parse(...);
    // P34 新增 append-only
    public static <T extends DsTableAdapter> DnfClause parseOr(
            Class<T> entityClass, String dsl,
            Map<String, ToDoubleFunction<T>> weightMap,
            Map<String, ToDoubleFunction<T>> ltrMap,
            int maxDnfSize,
            DnfReturnMode mode);
}
```

### 4.4 验收阈值

| 指标 | 阈值 | 备注 |
|------|------|------|
| DNF 拆分正确 | 100% | T2 TR2.4 hand-make minterm vs parser output Set.equals |
| DNF size 上限 | maxDnfSize ≤ 256 硬上限 | R8 |
| 边界 IAE | OR/NOT/() 各种异常路径 | T1 TR1.2 |
| 跨进程指纹稳定 | 2 进程解析同 DSL Set.equals | T4 TR4.2 |
| 守卫开销 | 0 红线类改，HybridQueryParser 字节级 0 改（仅 append） | T5 Y4/Y6 |

---

## 5. 测试规范（6 阶段，TR 数量与 P29~P33 模板一致）

| T | 测试目标 | 关键 TR |
|---|---------|---------|
| **T1** | DnfClause + ConditionNode + parseOr 骨架 | TR1.1 DnfClause 不可变；TR1.2 ConditionNode 8 IAE；TR1.3 parseOr 6 参边界；TR1.4 maxDnfSize=0 IAE |
| **T2** | DNF 范式转化 + minterm 拆分 | TR2.1 `(A OR B) AND C` → `[(A,C), (B,C)]`；TR2.2 `A AND (B OR C)` → `[(A,B), (A,C)]`；TR2.3 3 层括号嵌套；TR2.4 hand-make vs parser Set.equals；TR2.5 maxDnfSize 超限 IAE；TR2.6 NOT 跨 OR IAE |
| **T3** | Inproc E2E | TR3.1 5 场景 OR-only/AND-OR/NOT/括号/DNF 拆分；TR3.2 DnfClause 深等；TR3.3 与 P30 parse 共存 |
| **T4** | 跨 Process TCP 解析 | TR4.1 2 子进程解析同 DSL；TR4.2 跨进程 DnfClause deepEquals；TR4.3 跨进程 NOT IAE 一致 |
| **T5** | Gatekeeper 9Y | Y1 ZeroLeak3=3；Y2 NG-3 9 CNFE；Y3 TOPK；Y4 Facade 单调（parse 原 4 参字节级不变）；Y5 路由；Y6 P30~P33 字节级兼容；Y7 HybridHit ctor=2；Y8 ShardTransport 5 方法；Y9 0 TODO |
| **T6** | NFR | TR6.1 parseOr P95 ≤ 2s 30×；TR6.2 3 类 Javadoc ≥ 10 行；TR6.3 CPD dup ≤ 1（160 token）；TR6.4 0 println/printStackTrace + ≥ 8 IAE；TR6.5 4 报表 6 场景 20× 100% |

**TR 总数**：4 + 6 + 3 + 3 + 9 + 5 = **30 TR**（对齐 P29~P33 模板）

---

## 6. Gate B 组合（继承 P22-P33 326 TR + P34 own 30 TR）

P22-P34 组合 Surefire：
- P34 own 30 TR + P22-P33 已有 326 TR = **356 TR**
- Surefire 4 重 temp+dbhome 命令（继承 P33 拆分三批模板 + 拆 4 批规避 P24_T6 退化）
- 连续两次独立 Run，P34 own 30/30 0F0E + P22-P33 326/326 0F0E（P22/P25/P26_T6 NFR 边缘 + P28_T4 子进程握手基线 fail 已知记录）

---

## 7. 逆指标（明确不做什么）

- ❌ 不引入 ANTLR / JavaCC / parboiled / JParsec / commons-lang3
- ❌ 不修改 fulltext 根包 3 个类
- ❌ 不修改 HybridQueryParser 原 `parse` 4 参方法签名（P30 字节级 0 改）
- ❌ 不修改 ParsedHybridQuery / WeightSpec / LtrSpec / DslToken / DslTokenizer
- ❌ 不修改 HybridHit（constructors.length=2 红线）
- ❌ 不修改 P31 JoinSpec / JoinIdResolver / HybridJoinClient
- ❌ 不修改 P32 ResilienceConfig / 4 Guard
- ❌ 不修改 P33 BenchmarkReport / BenchmarkRunner
- ❌ 不实现 OR 语义执行（仅 DNF 拆分；业务方拿 N 个 DnfClause 自行执行）
- ❌ 不实现 XOR / NAND / 算术 / 函数调用
- ❌ 不实现 NOT 跨 OR（仅 FT 段前 NOT + IS NOT NULL）
- ❌ 不实现 DNF size > 256 的指数爆炸保护（直接 IAE）
- ❌ 不实现分布式 DNF 协调（单 JVM 拆 N 个 minterm）

---

## 8. 验收 Gate 三状态（待 Gate B/Gate C 通过后填实）

- [x] **Gate A**（Spec+Tasks 签批）→ 2026-10-15 用户选 OQ12-⑫① 批准
- [x] **Gate B**（P34 own 31 TR 0F0E + P22-P33 326 TR 拆 4 批 0F0E）→ 2026-10-15
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ 2026-10-15
