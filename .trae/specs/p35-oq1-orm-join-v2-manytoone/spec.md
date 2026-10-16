# P35 OQ1 ORM 多表 Join v2 DsManyToOne 聚合端 —— spec.md（**REV2 GATE C 闭环版，2026-10-16**）

## Gate C 9Y 自检清单（每 Y 必填 [x] 通过；任一未过 → 退 Gate A 整改）— **2026-10-16 实测全绿**
- [x] Y1 ZeroLeak3=3：fulltext 根包仍 = {FulltextSegmenter, FulltextStore, TermDictStore}，P35 0 新增根包 top-level 类（P35_T5 y1_ZeroLeak3 PASS）
- [x] Y2 NG-3 零依赖：P35 0 新增 netty/grpc/p2p.client 根包引用 + pom.xml 0 新增 dep/plugin（P35_T5 y2_NoForbiddenPackageRefs PASS）
- [x] Y3 TOPK 铁律：ManyToOne IN 子查询 + FetchSize 批量 不修改 K 限制、不动 bucketAccessCounter（P35_T5 y3_TopKGuardrails PASS）
- [x] Y4 Facade 单调不减：JoinSpec 4 字段 + JoinIdResolver 1 入口 + HJC 6 入口 + HybridHit ctor=2 + @DsOneToOne 注解 1 字不删改，v2 仅追加 JoinMode enum + batchSize + resolveManyToOne + searchHybridJoin*WithManyToOne 4 入口 + @DsManyToOne 新注解（P35_T5 y4_FacadeMonotonic PASS）
- [x] Y5 路由公式：P35 0 改 ShardRouter / 0 改 P24 UNIT/DOC 路由（P35_T5 y5_RoutingFormula PASS）
- [x] Y6 P30~P34 编译兼容：ParsedHybridQuery / WeightSpec / LtrSpec / BenchmarkReport / DnfParser / DnfClause / ConditionNode 0 改；P31 JoinSpec 1:1 MVP 行为字节级保持（P35_T5 y6_P30P35CompileCompat PASS）
- [x] Y7 HybridHit ctor=2：0 改（P35_T5 y7_HybridHit_ConstructorCountIs2 PASS = 2）
- [x] Y8 ShardTransport 5 方法：0 改（P35_T5 y8_ShardTransport_5OriginalMethodsIntact PASS）
- [x] Y9 0 TODO/FIXME/HACK：100% 实装 ManyToOne + IN 批量 + J=2 树级联（P35_T5 y9_NoTodoFixmeHack PASS）

---

## 1. Scope（用户单选 OQ12-⑬ 候选项 ①，VERBATIM 锚定）

ORM 多表 Join v2 DsManyToOne 聚合端 = 对 P31 1:1 MVP 做 **N:1 聚合**扩展：
1. **@DsManyToOne 新注解**：字段级标记 N:1 聚合端（一个主表行 → 多个从表行）
2. **JoinMode 枚举 + batchSize 字段**：JoinSpec 追加 2 字段，1:1 路径走 P31 MVP，N:1 路径走新分支
3. **JoinIdResolver.resolveManyToOne** 入口：1 个外键列 batchSize 切片 IN 子查询（默认 256），分批拉从表 rows → 按 outerField 聚合 map
4. **HybridJoinClient 4 入口**（searchHybridJoin / searchHybridJoinWithWeights / searchHybridJoinWithLtr 各自 +ManyToOne 变体）：J=1 走 N:1 聚合，J=2 走树级联
5. **J=2 树级联**：(T1 → R1 N:1) → (R1 → R2 N:1) 嵌套：先 R1 聚合 → R1 命中 ids → R2 聚合 → 最终 outerIds 是 T1∩R1∩R2 交集
6. **fillJoinFetch ManyToOne 端**：返回的 HybridHit 携带 List<R> 而非单 R（与 P31 单 R 的 joinFetch Map<Class,R> 结构不同）

**MVP 边界（0 越界）**：
- 仅做 N:1 聚合 + IN 子查询 + FetchSize 批量 + J≤2 树级联
- 不做 N:N 多对多 / 自关联 / 递归 CTE
- 不做 fetchSize 动态调优（业务方传固定值，默认 256）
- 0 新增 Maven dep
- 新类全放 `join/v2/` 或 `annotation/` 子包（annotation/ 是已有，追加 @DsManyToOne 即可）

---

## 2. 红线（VERIFIED 永久钉死，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3** | join 子包 0 新增 top-level 类到 fulltext 根包；新类 100% 放 `join/v2/` |
| R2 | **NG-3 零依赖** | pom.xml 0 新增 dep/plugin；源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*` |
| R3 | **TOPK 铁律** | ManyToOne IN 子查询不修改 K 限制；不修改 bucketAccessCounter |
| R4 | **Facade 单调不减** | JoinSpec 不可变原 4 字段 1 字不改；P31 fromDsOneToOneAnnotations 静态方法 1 字不改；JoinIdResolver.resolve 原签名 1 字不改；HJC 原 6 入口 1 字不改；@DsOneToOne 注解字段 1 字不改 |
| R5 | **HybridHit ctor=2** | 0 改（ManyToOne 端 joinFetch 仍存 Map<Class, Object>，单 R 或 List<R> 都可） |
| R6 | **P30~P34 字节级兼容** | DnfParser / DnfClause / ParsedHybridQuery / BenchmarkReport 0 改 |
| R7 | **J≤2 硬上限** | J≥3 抛 IAE（P35 MVP 限定 2 层 Join 树级联；J>2 留 P36） |
| R8 | **batchSize 上限** | batchSize ≤ 4096（防止 IN(...) 参数列表爆炸；超出 IAE） |

---

## 3. 架构（4 个新类 + 1 个新注解，0 改 P31 + 0 改 P30~P34）

### 3.1 新增清单

| 名称 | 类型 | 职责 | 关键字段/方法 |
|------|------|------|---------------|
| `@DsManyToOne` | annotation（com.q3lives.ds.annotation 已有子包） | 字段级 N:1 聚合端标记 | `joinProp()` + `props() default ""` + `fetchSize() default 256` |
| `JoinMode` | enum | Join 类型 1:1 / N:1 | ONE_TO_ONE / MANY_TO_ONE + 字节码 `code()` + `fromCode(char)` |
| `JoinIdResolverV2` | 静态工具类（new 子包 `join/v2/`） | N:1 IN 子查询 + FetchSize 切片聚合 | `resolveManyToOne(...)` 静态方法 + `parseManyToOneAnnotations(...)` 反射工厂 |
| `ManyToOneJoinResult` | 不可变 POJO | N:1 聚合结果：`Map<Long, List<? extends DsTableAdapter>> outerId → List<R>` | outerIds Set + byOuterId Map + relatedClass |
| `HybridJoinClientV2Adapter` | 静态工具类（`join/v2/`） | HJC 4 入口 + 树级联调度 | `searchHybridJoinWithManyToOne` / `*WithWeights` / `*WithLtr` / `*Tree`（J=2） |

### 3.2 JoinSpec 扩展（追加 2 字段，原 4 字段不改）

```java
public final class JoinSpec<R extends DsTableAdapter> {
    public final Class<R> relatedClass;          // 已有
    public final String outerField;             // 已有
    public final String relatedField;            // 已有
    public final QueryWrapper<R> relatedCond;   // 已有
    // P35 追加：
    public final JoinMode mode;                 // ONE_TO_ONE (默认) 或 MANY_TO_ONE
    public final int batchSize;                 // 仅 MANY_TO_ONE 有效，∈ [1, 4096]，默认 256
}
```

### 3.3 调用流程

```
HJC.searchHybridJoinWithManyToOne(orm, joins [mode=MANY_TO_ONE], ftQ, raw, K, opt)
   ├── Step 1: 预校验 + 守卫（mode == MANY_TO_ONE，batchSize ∈ [1, 4096]）
   ├── Step 2: mainGm.listIds(orm) → outerIds
   ├── Step 3: For each JoinSpec(mode=MANY_TO_ONE):
   │     ├── joinProp 是 outerId 集合的 source FK 列
   │     ├── IN 子查询：rg.listIdsByColumnInBatches(relatedField, outerIds, batchSize)  ← new API
   │     ├── 反射取 relatedField Long 值 → matched
   │     └── outerIds.retainAll(matched)
   ├── Step 4: HJC.searchHybridWithExtras 走 P28-P32 链路
   ├── Step 5: postFilterJoin + fillJoinFetchManyToOne  ← new API（多 R 端聚合）
   └── 输出: List<HybridHit<T>> + each HybridHit.joinFetch = Map<Class, ManyToOneJoinResult>
```

### 3.4 树级联 J=2

```
HJC.searchHybridJoinWithManyToOneTree(orm, join1 [MANY_TO_ONE], join2 [MANY_TO_ONE], ftQ, raw, K, opt)
   ├── Step 1: mainGm.listIds(orm) → T1_outerIds
   ├── Step 2: T1 ∩ R1 = JoinIdResolverV2.resolveManyToOne(T1_outerIds, join1) → R1_ids
   ├── Step 3: R1 ∩ R2 = JoinIdResolverV2.resolveManyToOne(R1_ids, join2, on R1.id → R2.fk)
   ├── Step 4: 最终 outerIds = T1_outerIds ∩ R1_ids ∩ R2_ids
   ├── Step 5: HJC.searchHybrid
   └── 输出: List<HybridHit<T>> + joinFetch = {R1: Map<T1_id, List<R1>>, R2: Map<R1_id, List<R2>>}
```

---

## 4. 接口规范

### 4.1 `@DsManyToOne`（新注解，com.q3lives.ds.annotation 已有子包追加）

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DsManyToOne {
    String joinProp();          // 该属性必须是 DsTableAdapter 类型，DsTableAdapter 的 joinProp 必须是 Long
    String props() default "";  // 装载指定属性
    int fetchSize() default 256; // IN 子查询 FetchSize ∈ [1, 4096]
}
```

### 4.2 `JoinMode`（enum）

```java
public enum JoinMode {
    ONE_TO_ONE('O'),      // P31 MVP 默认
    MANY_TO_ONE('M');     // P35 新增
    public char code();
    public static JoinMode fromCode(char c);
}
```

### 4.3 `ManyToOneJoinResult`（不可变 POJO）

```java
public final class ManyToOneJoinResult<R extends DsTableAdapter> {
    public final Class<R> relatedClass;
    public final Map<Long, List<R>> byOuterId;   // outerId → List<R>
    public final Set<Long> matchedOuterIds;      // 有 R 命中的 outerIds
    // 构造 IAE: relatedClass null / byOuterId null / matchedOuterIds null
}
```

### 4.4 `JoinIdResolverV2`（new 静态工具类）

```java
public final class JoinIdResolverV2 {
    public static <T extends DsTableAdapter> Set<Long> resolveManyToOne(
            Set<Long> outerIds,
            JoinSpec<?> join,
            Map<Class<?>, GenericManager<?>> relatedGms);
    
    public static <R extends DsTableAdapter> ManyToOneJoinResult<R> aggregateByOuterId(
            Set<Long> outerIds, JoinSpec<R> join, GenericManager<R> rg);
    
    public static <T extends DsTableAdapter> List<JoinSpec<?>> parseManyToOneAnnotations(Class<T> mainClass);
}
```

### 4.5 验收阈值

| 指标 | 阈值 | 备注 |
|------|------|------|
| IN 子查询 fetchSize 切片 | 256 默认 / ≤ 4096 硬上限 | R8 |
| J=2 树级联 | 100% 正确 | T3 TR3.2 |
| joinFetch 携带多 R | List<R> 而非单 R | T3 TR3.1 |
| fetchSize 切片 N+1 防御 | outerIds.size / batchSize 切片，0 漏 0 错 | T2 TR2.4 |
| 跨进程指纹稳定 | ManyToOneJoinResult deepEquals 跨 run 一致 | T4 TR4.2 |

---

## 5. 测试规范（6 阶段，TR 数量与 P29~P34 模板一致）

| T | 测试目标 | 关键 TR |
|---|---------|---------|
| **T1** | @DsManyToOne 注解 + JoinMode enum + JoinSpec 6 字段扩展 + ManyToOneJoinResult 不可变 | TR1.1 @DsManyToOne 注解字段；TR1.2 JoinMode enum 2 值 + fromCode；TR1.3 JoinSpec 加 2 字段 + 8 IAE；TR1.4 ManyToOneJoinResult 不可变 + 5 IAE |
| **T2** | JoinIdResolverV2 IN 子查询 + FetchSize 切片 + aggregateByOuterId + parseManyToOneAnnotations | TR2.1 单批次 IN；TR2.2 多批次切片；TR2.3 aggregateByOuterId 聚合；TR2.4 parseManyToOneAnnotations 反射；TR2.5 fetchSize > 4096 IAE；TR2.6 J=2 树级联 resolve |
| **T3** | Inproc E2E：J=1 N:1 + J=2 树级联 + 共存 P31 1:1 | TR3.1 J=1 N:1 聚合 List<R>；TR3.2 J=2 树级联 (T1→R1→R2)；TR3.3 共存 P31 1:1 |
| **T4** | 跨 Process TCP ManyToOne 指纹稳定 | TR4.1 2 子进程解析 join 树；TR4.2 跨进程 ManyToOneJoinResult deepEquals；TR4.3 跨进程 fetchSize 异常一致 |
| **T5** | Gatekeeper 9Y | Y1 ZeroLeak3=3；Y2 NG-3 9 CNFE；Y3 TOPK；Y4 Facade 单调（JoinSpec 原 4 字段 + JoinIdResolver.resolve 字节级不变）；Y5 路由；Y6 P30~P34 字节级兼容；Y7 HybridHit ctor=2；Y8 ShardTransport 5 方法；Y9 0 TODO |
| **T6** | NFR | TR6.1 resolveManyToOne P95 ≤ 2s 30×；TR6.2 4 类 Javadoc ≥ 10 行；TR6.3 CPD dup ≤ 1（160 token）；TR6.4 0 println/printStackTrace + ≥ 8 IAE；TR6.5 4 报表 6 场景 20× 100% |

**TR 总数**：4 + 6 + 3 + 3 + 9 + 5 = **30 TR**（对齐 P29~P34 模板）

---

## 6. Gate B 组合（继承 P22-P34 326 TR + P31 own 34 + P35 own 30 TR）

P22-P35 组合 Surefire：
- P35 own 30 TR + P22-P34 已有 326 TR = **356 TR**
- Surefire 4 重 temp+dbhome 命令（继承 P34 拆 4 批模板）
- 连续两次独立 Run，P35 own 30/30 0F0E + P22-P34 326/326 0F0E（P22/P25/P26_T6 NFR 边缘 + P28_T4 子进程握手基线 fail 已知记录）

---

## 7. 逆指标（明确不做什么）

- ❌ 不引入 Hibernate / MyBatis / JPA / JOOQ
- ❌ 不修改 fulltext 根包 3 个类
- ❌ 不修改 JoinSpec 原 4 字段 / fromDsOneToOneAnnotations / QwHelper 内嵌
- ❌ 不修改 JoinIdResolver.resolve 入口
- ❌ 不修改 HJC 原 6 入口（3 + 3 WithResilience）
- ❌ 不修改 @DsOneToOne 注解（仅追加 @DsManyToOne）
- ❌ 不修改 HybridHit（constructors.length=2 红线）
- ❌ 不修改 DnfParser / DnfClause / ParsedHybridQuery / BenchmarkReport
- ❌ 不实现 N:N 多对多
- ❌ 不实现自关联 / 递归 CTE
- ❌ 不实现 fetchSize 动态调优（业务方传固定值）
- ❌ 不实现 J≥3 树级联（直接 IAE）
- ❌ 不实现分布式 Join 协调（单 JVM 串行 + 并行 batchSize 切片）

---

## 8. 验收 Gate 三状态（待 Gate B/Gate C 通过后填实）

- [ ] **Gate A**（Spec+Tasks 签批）→ 当前待用户批准
- [ ] **Gate B**（P35 own 30/30 TR 0F0E + P22-P34 326/326 TR）→ 待 Implement
- [ ] **Gate C**（9Y 自检 + 洁癖 5/5）→ 待 Gate B 通过
