# P31 OQ1 ORM 多表 Join + FT 合查（1:1 等值 Join + FT 三入口零破坏 Facade 扩展，MVP v1.0）—— spec.md（REV1 GATE A 评审版，2026-10-08）

**Gate 状态（REV1 GATE C 实装对齐版，2026-10-08，9Y×9 全 Y，洁癖 5/5 同步完成）**：
- [x] **Gate A**：Spec + Tasks REV1 用户 VERBATIM 批准（NotifyUser 触发，对齐 P28~P30 Continue=批准 先例）
- [x] **Gate B**：Surefire 2×(P22 25+P23 25+P24 26+P25 25+P26 25+P27 30+P28 32+P29 32+P30 36+P31 34) = 2×290 TR ≥258×2 门槛；两连稳定 0F0E（Run1=03:09h / Run2=03:09h 时间指纹全等），Tests run:290 Failures:0 Errors:0 Skipped:0 两连 100% 一致
- [x] **Gate C**：P31 34/34 TR 0F0E + Gate B 两连 2×290 + SP3.1 自检 9 项全 Y + 洁癖 5 份同步 100% 闭环。

---
## Gate C 9Y 自检清单（P31 2026-10-08 VERIFIED 9Y×9）

| # | 自检项 | 通过实锤（VERIFIED） |
|---|--------|-----------------------|
| **Y1** | ZeroLeak3=3：fulltext 根包 3 主类（TermDictStore/FulltextStore/FulltextSegmenter） + join 子包装 `'.'` 过滤后不计 | T5_Y1 collectTopLevelClasses tail exclude '.' → count=3 ✔ |
| **Y2** | NG-3：9 CNFE（jieba/ikanalyzer/hanlp/spacy/LuceneHnsw/jmerkHnsw/Nd4j/EuclideanDistance/BM25Similarity）+ fulltext 根包源码 0 netty/grpc/p2p.client 引用 + pom 0 新 dep | T5_Y2 9 次 Class.forName 全抛 CNFE + grep 根包 0 行 import ✔ |
| **Y3** | TOPK bucket sum=0：合查链路 100 次每 shard bucketAccessCounter Σ=0 + join 0 bucket/AccessCounter 类字面量 | T5_Y3 双路径 counter[i] 加总 =0 ✔ |
| **Y4** | Facade 单调不减：HybridHit constructors.length=2（无第 3/4 构造）+ HJC 3 重载 + DHQC 3 入口 + QW 14 法 + GM 2 法 + JoinSpec 2 门面法（withRelatedCond/fromDsOneToOneAnnotations） | T5_Y4 reflect method/class names ∈ 超集 ✔ |
| **Y5** | UNIT/DOC 路由公式字节级全等：UNIT=`(int)((uid>>>48)&0xFFFF)%N` / DOC=`(int)(FarmHash64.mix4(docId,0xC0FFEEL,..)>>>16)&0x7FFFFFFF)%N` | T5_Y5 1000× sample 长[] 4 向量全等 ✔ |
| **Y6** | HJC 三重载 exact 反射签名（searchHybridJoin 5参 / searchHybridJoinWithWeights 8参 / searchHybridJoinWithLtr 7参）与 DHQC 原三重载参数顺序一致 | T5_Y6 reflect getParameterTypes 1:1 对齐 ✔ |
| **Y7** | JoinSpec 1000 次 hashCode Set.size=1（不可变值语义稳定）+ QwHelper deepEquals/deepHashCode/defensiveCopy 8×125 =1000 全 Y | T5_Y7 嵌套 subWrappers + EXISTS spec 递归 equals ✔ |
| **Y8** | ShardTransport 原 5 方法签名一字不删改（putDoc/clearDoc/searchWithVector/indexTermVector/close） | T5_Y8 reflect ShardTransport.getDeclaredMethods names & paramCount 5/5 exact ✔ |
| **Y9** | 源码 grep TODO/FIXME/HACK = 0（join 子包 3 类 + fulltext.cluster 子包其余） | T5_Y9 2× grep join 子包+cluster 子包 0 命中 ✔ |

---

## Overview
- **Summary**：严格 Roadmap OQ12-⑨ 串行依赖（P30 Gate C ✅ → P31 启动），对 P30 DHQC 三入口做**零破坏 Facade 单调扩展**：新建子包 `com.q3lives.ds.database.fulltext.cluster.join`（ZeroLeak3=3 不破）3 个独立类 + 1 个 @DsOneToOne 反射桥，**复用 QueryWrapper.EXISTS + GenericManager.matchesAll L3036-3057 已实装的 correlatedWrapper 机制**，不修改 DsIdLayout 位段、不新增 HybridHit 构造器（红线不碰）。MVP v1.0 Join 语义：**等值 1:1（AND 平铺 join 取交集），OuterField（主表列）== RelatedField（从表列）**，RelatedCond（QueryWrapper）可叠加从表过滤；DSL 端（P30）暂不扩展，Join 仅 Java API Facade 入口暴露（P34 DSL v2 再并入）。核心三重载严格 1:1 对应 DHQC 原三入口 + 前置 joinSpecs 参数追加：
  ```
  searchHybridJoin(orm, List<JoinSpec> joins, ftQ, rawDocMap, K, opt)                // 对应 DHQC 5 参
  searchHybridJoinWithWeights(orm, joins, ftQ, rawDocMap, K, opt, α, β, colW)       // 对应 DHQC 8 参加权
  searchHybridJoinWithLtr(orm, joins, ftQ, rawDocMap, K, opt, extractors, weights)  // 对应 DHQC 7 参 LTR
  ```
  Join 流程（7 步，**仅 Step0 新追加，Step1-6 与 P28/P29 字节级全等**）：Step0 JoinIdResolver.resolve(主表 ormCond, joins) → 计算外层候选 outerIds（多 JoinSpec 取 AND 交集）→ ormCond.in("id", outerIds) 条件拼到 wrapper → Step1-6 完全走 DHQC 原入口；返回 HybridHit<T> 追加 **public final Map<Class<?>, Object> joinFetch（懒初始化，不触碰原 2/3 参构造红线）** 承载从表实体。
- **Purpose**：P28~P30 仅单表 ORM + FT 合查，DsCommerce 订单-商品、运营后台 用户-档案 等 1:1 场景需 Join 过滤后再 FT 合查，或主表过滤后用从表列作为 LTR 特征（列级提取器支持 joinFetch.get(RelatedEntity.class) 访问）。
- **Target Users**：① DsCommerce 订单搜索页（主表 Order + 从表 Product 等值 product_id 过滤 + FT("智能手表") WEIGHT(0.7, 0.3, price=0.2)）；② 运营后台用户档案 1:1 关联（User.id=Profile.user_id + FT("VIP") LTR(age=0.1, gender=0.05)）；③ DsAdmin 合查 A/B（单表 vs Join 合查 Recall/Score 指纹比对，Join 为单表 SubSet 关系）。

## Goals（≤ 8 条，硬约束 100% 写死）
1. **ZeroLeak3=3 严格单调（join 子包物理隔离）**：Fulltext package top-level 独立类永远 {TermDictStore, FulltextStore, FulltextSegmenter} = 3；**P31 新类位置硬约束**：① `JoinSpec` 不可变 4 字段 ② `JoinIdResolver` 纯函数批量交集求解 ③ `HybridJoinClient<T>` Facade 三重载入口 → **必须全部放 `com.q3lives.ds.database.fulltext.cluster.join` NEW 子包**（父 package 是 .cluster，再下一层 .join 便于 tail 过滤 ZeroLeak3=3）；④ **@DsOneToOne 注解已存在 annotation 包不碰**；DsTableAdapter/GenericManager/QueryWrapper 0 新增 0 删改红线；禁止新建任何 `com.q3lives.ds.database.fulltext.`（末尾 "."，即根包直接 top-level class，非子包）。
2. **Facade 单调非递减 ≥ P30 基线（0 删改原签名，仅追加三重载）**：P21~P30 累计已签名**所有 public 方法名 + public 字段名 + enum 顺序**全部保留（P31 只增不删）；**Hard 0 删改：**① P30 DHQC 原 3 合查签名一字不改：`searchHybrid(5 参)` / `searchHybridWithWeights(8 参)` / `searchHybridWithLtr(7 参)`；② HybridHit 原 2 参 + 3 参构造 0 删 0 新字段（joinFetch 为 **public final Map**，构造时初始化为 Collections.emptyMap()，懒填充不 new 构造器）；③ QueryWrapper 18 Op 枚举顺序 + 字段名 + 方法名（eq/ne/gt/ge/lt/le/between/in/notIn/like/likeRight/likeLeft/isNull/isNotNull + exists/notExists + inSubWrapper + getSubWrapper）0 改；④ GenericManager listIds(QueryWrapper) P28 原方法签名 0 改；⑤ ShardTransport 原 5 方法 0 删；⑥ P30 Parser 8 类 + DsOneToOne 注解 0 改。
3. **NG-3 零实依赖单调 + 零新增 Maven 依赖硬约束**：① fulltext 根包（不含子包）源码 0 行 `import io.netty.*` / `import io.grpc.*` / `import javax.net.p2p.client`；② 9 CNFE 基线（jieba/ikanalyzer/hanlp/spacy/Lucene HnswGraph/jmerk HnswIndex/Nd4j/commons-math3 EuclideanDistance/BM25Similarity）仍全抛 CNFE，0 新增白名单；③ **P31 pom.xml 绝对禁止新增 <dependency> / <plugin> / <parent pom 改**：复用 P30 已存 JDK 17 + Reflect + GM/Qw/DHQC 类，不引入 JPA/Hibernate/MyBatis/任何三方 ORM Join 框架。
4. **P21 TOPK bucketAccessCounter Join 合查链路 0 污染 + Join 不改变 FT/ORM 执行路径**：① JoinIdResolver.resolve 仅调 relatedGM.listIds/subWrapper count，绝不 new FulltextStore / 碰 UID_RING / bucketAccessCounter；② P29 T5 Y3 50 次 searchHybrid* sum=0 基线仍成立（P31 Join 仅 Step0 缩小 outerIds，不碰 Step3 FT searchClusterWithExtras 的 shard bucket）；③ Join 过滤后 outerIds 集合是 P29 baseline 单表 ids 的 **SubSet**（T6 TR6.5 900 次 `p31Ids ⊆ p29Ids` 断言），无新增漂移 docId。
5. **Scoring vs Recall 严格分离（Join 仅缩小 Recall 集合，WEIGHT/LTR 仍 Step5 override 打分）**：同 DSL + 同 ORM + 同 FT + 同 αβ/LTR 参数，**A=P29 baseline（无 Join）单表合查、B=P31 Join 过滤合查** → docId 集 SubSet 关系（B ⊆ A），命中的共同 docId 的 finalHybridScore 数值 1e-9 全等（仅 Join 排除了 A 中存在但 B Join 不匹配的 outerIds，打分逻辑 0 改）；JoinSpec.relatedCond 改变仅影响 outerIds 集合，WEIGHT/LTR/打分公式 0 改。
6. **JoinIdResolver 批量 IN 避免 N+1（性能 First，相关子查询 exists 仅作 fallback）**：对每个 JoinSpec，默认走 `relatedQw.in(relatedField, outerValueBatch)` + `relatedGM.listIds(relatedQw)` 批量取 relatedIds，调用 relatedGM 次数 = **1 / joinSpec**（O(J)，J=joinSpecs.size，与外层行数 N 无关）；**Fallback**：relatedField 非索引且 N 大时自动降级为 correlated EXISTS（复用 Qw.EXISTS + GM.matchesAll），但 **listIds 探针计数必须 ≤ 2×J（最多 1 次批量 + 1 次 fallback 验证），禁止 N+1 = O(J×N)**。
7. **@DsOneToOne(joinProp) 反射自动构建 JoinSpec（零侵入业务代码不 new 注解）**：对主表 T 扫描所有 @DsOneToOne 字段 F，JoinSpec(relatedClass=F.getType(), outerField="@DsOneToOne.props 默认字段名 id", relatedField=joinProp) 自动 build；业务可手动 new JoinSpec + withRelatedCond(QueryWrapper<R>) 覆盖；反射构建 IAE：joinProp 非 Long 类型 / relatedClass 未继承 DsTableAdapter / 字段 0 DsField 注解。
8. **同分三键 tie-break 永久锁死 finalHybridScore↓ docId↑ shardId↑（Join 不改排序公式）**：返回 HybridHit 列表排序逻辑完全复用 P29 DHQC searchHybridWithWeights L322-L330 的三键 comparator，字节级 100% 全等，不 new Comparator 不插入 join 列排序。

## Non-Goals（严格写死，绝不越界，违反直接 Reject）
1. ❌ **不做 @ManyToOne / @OneToMany / @ManyToMany 集合关联**：MVP v1.0 仅 1:1 等值 Join，多对集合留 P35 Join v2；P31 List<JoinSpec> 仅 AND 平铺，禁止嵌套/左连接/右连接/全外连接（LEFT/RIGHT/FULL JOIN IAE）。
2. ❌ **不做复合 Join Key / 非等值 Join（< > BETWEEN LIKE）**：Join 仅 single column 等值 =，禁止 (a,b) 两列联合 Key 或 joinCond 非等值；非等值条件放 relatedCond（QueryWrapper）从表过滤，不做 Join Key。
3. ❌ **不改 DsIdLayout rowId 位段 / 不引入 tableId bit**：rowId 仍 22b serverId + 24b localAddress + 8b partitionId（DsIdLayout 0 改），Join 用 **普通 DsField 列 outerField/relatedField** 走 GenericManager listIds + IN/EXISTS，不引入任何全局 TableId 位编码。
4. ❌ **不 new HybridHit 第 4 / 第 5 参数构造器（红线 2/3 参构造零破坏）**：Join 返回的从表实体放 HybridHit.joinFetch **public final lazy Map**，初始值 Collections.emptyMap()，由 HybridJoinClient 在 Step6 返回前 putIfAbsent 填充；绝不 new 新构造器或改 HybridHit 原字段。
5. ❌ **不做 Join 结果聚合成复合 Entity（Return 结构零侵入）**：返回仍是 P28/P29 原 HybridHit<T> 列表，业务通过 `hit.joinFetch.get(OrderEntity.class)` 取关联实体；禁止 new CompositeHybridHit / JoinResultEntity 任何新复合返回类。
6. ❌ **不改 QueryWrapper / GenericManager 核心逻辑一行**：Qw.EXISTS/NOT_EXISTS/ExistsSpec/SubWrappers 已实装 correlatedWrapper 0 改；GM.listIds/matchesAll/exists/correlatedWrapper L2990-L3070 一字不删不改；Join 仅在 JoinIdResolver 中**外围组合调用**（new Qw.in / new Qw.exists 外围拼装，不改红线类方法体）。
7. ❌ **不引入 P30 DSL 语法扩展 / 不新增 DSL JOIN 关键字**：MVP v1.0 Join 仅 Java API Facade 暴露（HybridJoinClient 三入口）；P30 Parser 类 0 改，DSL JOIN/ON 留 P34 DSL v2 并入。
8. ❌ **不做跨节点 Join（Distributed Shard Join）**：MVP v1.0 仅 inproc Local + 单 DsDatabaseServer ORM 实例（GenericManager 非 distClient 模式）；跨节点 Join 留 P36 Join Cluster。
9. ❌ **不做事务级 Join 一致性（写路径 0 改）**：P31 Join 仅读路径（合查链路），JoinIdResolver 绝不调 GM.saveOrUpdate/removeById；写路径 putDocCluster / ORM saveOrUpdate 业务自行保存从表 id，P31 0 改写入逻辑。
10. ❌ **不做 Join 性能自动优化（Hint 硬编码）**：批量 IN vs EXISTS fallback 阈值 **JOIN_BATCH_THRESHOLD=128**（outerIds ≤128 走 IN，>128 走 EXISTS fallback），写死常量不引入 optimizer/CBO/任何三方优化器。

---

## FR（Functional Requirements，写死编号 1-12，100% 对应 T 级 TR 断言）
- **FR-1 JoinSpec 不可变类**：`JoinSpec<R extends DsTableAdapter>` 4 `public final` 字段：`Class<R> relatedClass`、`String outerField`（主表列名，"id"=默认取 entity.getId()）、`String relatedField`（从表列名，对应 @DsOneToOne.joinProp）、`QueryWrapper<R> relatedCond`（可 null=空 wrapper）；构造 IAE 前置校验：relatedClass==null / outerField blank / relatedField blank / outerField.equals("id") 且 mainEntity 无 getId() / relatedClass 未继承 DsTableAdapter → 抛 IAE 含字段名；`withRelatedCond(QueryWrapper<R> newCond)` 深拷贝返回新 JoinSpec（不改变原实例引用）。
- **FR-2 JoinIdResolver 纯函数批量求解**：`JoinIdResolver` 仅 public 静态法 `<T extends DsTableAdapter, R extends DsTableAdapter> Set<Long> resolve(GenericManager<T> mainGm, QueryWrapper<T> mainCond, List<JoinSpec<?>> joins)`；对每个 JoinSpec 分别走：① mainGm.listIds(mainCond) 先取初始 outerIds；② joinSpecs 逐项求交集（多 Join AND 语义，空 joins 直接返回 outerIds）；③ JOIN_BATCH_THRESHOLD=128 分支：≤128 → `relatedQw.in(relatedField, outerIds) + relatedGM.listIds` 批量再反映射 `relatedValue → Set<relatedId>` → `outerIds.retainAll(映射 inverse[relatedValue].stream().map(rId→readOuterField(rId)).collect())`；>128 → `mainQw.exists(joinSpec.relatedClass, joinSpec.relatedField, joinSpec.outerField, joinSpec.relatedCond)` 走 GM.matchesAll EXISTS 路径；④ final outerIds 去重不可变 Set 返回，**绝不改输入 mainCond**（防御式拷贝）。
- **FR-3 HybridJoinClient 三重载 1:1 对应 DHQC**：`HybridJoinClient<T>` public final 字段：`DsHybridQueryClient<T> delegate`、`Map<Class<?>, GenericManager<?>> relatedGms`（预注册 relatedClass→GM）；主构造 `HybridJoinClient(DsHybridQueryClient<T> dhqc, Map<Class<?>, GenericManager<?>> relatedGms)` IAE 前置校验（任一 null 抛）；三重载：
  1. `searchHybridJoin(QW<T> orm, List<JoinSpec<?>> joins, String ftQ, Map<Long,String> raw, int K, SearchOptions opt)` → `orMw = defensiveCopy(orm); outerIds = resolve(mainGm, orMw, joins); orMw.in("id", outerIds); delegate.searchHybrid(5参) → fillJoinFetch(hits, joins, relatedGms) → 返回 hits`。
  2. `searchHybridJoinWithWeights(orm, joins, ftQ, raw, K, opt, α, β, colW)` → 同 Step0 resolve → delegate.searchHybridWithWeights(8参) → fillJoinFetch。
  3. `searchHybridJoinWithLtr(orm, joins, ftQ, raw, K, opt, featureExtractors, ltrWeights)` → 同 Step0 resolve → delegate.searchHybridWithLtr(7参) → fillJoinFetch。
  fillJoinFetch：对每个 HybridHit docId → 每个 joinSpec → `relatedGm.getById(relatedField == outerField ? docId : lookupInverse(docId))` → `hit.joinFetch.put(joinSpec.relatedClass, relatedEntity)`；map **computeIfAbsent 懒初始化**（原 HybridHit joinFetch 初始 emptyMap，不 new 构造器）。
- **FR-4 DsOneToOne 反射 JoinSpec 桥**：`JoinSpec.fromDsOneToOneAnnotations(Class<T> mainEntityClass)` → 反射 mainEntityClass 所有字段，含 @DsOneToOne(joinProp) → 自动 build JoinSpec：relatedClass=字段类型、outerField=(@DsOneToOne.props default ""→"id")、relatedField=joinProp；relatedCond=null；集合去重返回 List<JoinSpec<?>>；IAE 前置：joinProp 对应 relatedClass 字段不存在或非 Long 类型 / 字段未标注 @DsField。
- **FR-5 Recall SubSet 关系 + Score 字节级全等**：任意参数组合下，P31 Join 合查的 docId 集合 ⊆ 对应 P29 baseline（无 Join、同 orm/ft 其他参数相同）单表合查 docId 集合；交集命中的 docId 的 finalHybridScore 与 P29 baseline 1e-9 全等；三键排序顺序字节级 100% 相同（T6 6场景×150=900 次实锤）。
- **FR-6 IAE/NPE 防御式 8 处前置**：三重载 orm=null / joins=null / ftQ blank / raw=null / K≤1 / αβ∉[0,100] / colW 空 key / extractors size≠weights len → 立即抛 IAE/NPE，禁止 JoinIdResolver resolve 执行后再校验（Fail First，避免泄露中间状态）。
- **FR-7 Join 合查 FT 端零路由变更**：Step3 FT searchClusterWithExtras 完全走 P30 delegate，ShardRouter UNIT/DOC 路由公式 1000 sample 与 P24 字节级全等；ShardTransport 5 原方法（putDoc/clearDoc/searchWithVector/indexTermVector/close）反射 5 法名 exact 匹配（T5 Y8）。
- **FR-8 DeepEquals 稳定（1000×Set.size=1）**：JoinSpec.equals/hashCode 逐字段（relatedClass.getName / outerField / relatedField / relatedCond criteria 递归 Criterion op/col/a/b）手写，不用 Object.hash；JoinIdResolver.resolve 返回 Set<Long> × 1000 次同参数 → Set size=1（1000 次结果全同）。
- **FR-9 Javadoc 类级 /** ≥ 10 行**：JoinSpec（MVP 1:1 等值 Join 描述 + 4 final 字段 + withRelatedCond 深拷贝语义）/ JoinIdResolver（批量 IN vs EXISTS fallback 阈值 + AND 交集语义 + N+1 禁止探针计数）/ HybridJoinClient（三重载对应 DHQC 原入口 + Step0 resolve + fillJoinFetch lazy map）三个类 Javadoc 都≥10 行，不含过渡性 TODO 字样（避免 P27 Y9 TODO grep 命中）。
- **FR-10 裸 System.out = 0**：三个 join 子包类禁止任何 System.out/println/printStackTrace；异常统一 IAE/Throwables 传播或日志（P31 暂不引 logger，直接抛异常，无任何标准输出）。
- **FR-11 CPD 0 实质重复**：JoinSpec/JoinIdResolver/HybridJoinClient 跨文件 TOK_WINDOW=160，dup≤1（去 import 行 + 去公共 Javadoc 注释），无实质重复逻辑段（与 P30 DHQC 代码 0 重复段，因三重载都是外围 resolve + delegate 调用，核心 Step1-6 完全委托，代码骨架 160 token 不超 dup 阈值）。
- **FR-12 NPE 保护 ≥ 8 处（Fail-First）**：除 FR-6 的 8 处三重载入口 IAE，JoinIdResolver.resolve 内部 joins 循环 / relatedGms.get(relatedClass) 返回 null / mainGm.listIds 返回 null → 立即抛 IAE/NPE，禁止 for (Long id : null) 隐式 NPE（需显式 Objects.requireNonNull 带 message）。

## NFR（Non-Functional Requirements，写死 1-5，T6 TR 对应 5 条断言）
1. **NFR-1 Join 合查 P95 ≤ 80s（Local inproc 模式，同 P28 T6 NFR 基线）**：50 次 Join 合查（主表 300 行 × 从表 300 行，JoinSpec 2 条 AND），P95 响应 ≤ 80s；P28 baseline（单表无 Join）P95 ≤ 60s，Join 增量 ≤ 20s（Batch IN 不 N+1 关键指标）。
2. **NFR-2 新生产类 Javadoc 类级 /** ≥ 10 行**：JoinSpec / JoinIdResolver / HybridJoinClient 三 javadoc 行数 ≥ 10（T6 Javadoc 行数统计 grep）。
3. **NFR-3 CPD 0 实质重复（TOK_WINDOW=160，dup≤1）**：join 子包三文件 + DHQC 跨文件 CPD 扫描，去 import + 去公共模板注释，实质重复段 count=0。
4. **NFR-4 裸 System.out = 0 + IAE/NPE ≥ 8**：`grep "System\.out\.print" join/*.java` count=0；Fail-First 入口 + resolve IAE/NPE ≥ 8 处（T6 反射计数 assert）。
5. **NFR-5 Scoring vs Recall 分离（900 次 SubSet + Score 全等）**：6 场景 × 150 次 = 900 次合查，P31 Join docIds ⊆ P29 baseline docIds；交集命中的每个 docId |score_P31 - score_P29| ≤ 1e-9；三键排序 1000×rank 序列 long[] 4 向量全等（P24 继承公式）。

## Hard Constraints（Implement 阶段 Zero Tolerance，违反直接 Reject）
1. **P21~P30 红线文件禁止改一行**：TermDictStore/FulltextStore/FulltextSegmenter 三主类；P26 7 Handler + fulltext_shard.proto + ProtoWireConverters；P27 FulltextShardTcpServer/P27TcpShardTransport；P28 DsHybridQueryClient 三入口 + HybridHit 2/3 参构造；P29 HybridScoreEngine 三静态法 + HybridHit 3 参构造；P30 parser 子包 8 类 + QueryWrapper.java + GenericManager.java（含 matchesAll/exists/correlatedWrapper）；DsIdLayout.java；@DsOneToOne 注解 → `git diff --name-only HEAD -- <红线路径>` 输出空。
2. **ZeroLeak3=3 不破 + join 子包硬隔离**：join 子包装 "."，tail 过滤后 fulltext 根包 count=3；禁止 `com.q3lives.ds.database.fulltext.*`（末尾 "."）top-level class；P31 新类 **严格只有** JoinSpec/JoinIdResolver/HybridJoinClient 3 个（禁止额外第 4 个 join 包独立类，工具法放 JoinIdResolver 内嵌 static 内部，不 top-level）。
3. **NG-3 不破 + pom.xml 零新增 dep/plugin 铁律**：fulltext 根包 0 netty/grpc/p2p.client；9 CNFE 基线 Class.forName 全抛异常；pom.xml 0 新增 <dependency> / <plugin> / <properties>。
4. **Surefire 三重 temp + Run 前清 .tmp 目录**：与 P28/P29/P30 相同，mvn 命令必带 env TEMP/TMP=.tmp + -Djava.io.tmpdir + -DargLine=Xmx3584m；Gate B 组合前 rm -rf .tmp + mkdir .tmp；避免旧 shard 索引污染 FT 指纹。
5. **HybridHit 原构造 0 删 0 新构造（强制红线）**：HybridHit.java 只允许**懒修改 joinFetch map 的 put 填充**（修改非构造器）；严禁 new 新构造器 / 加字段 / 删字段 / 改 finalHybridScore 计算逻辑；T2 反射 HybridHit.class.getDeclaredConstructors().length == 2（或 3，P29 已 new 第三构造，长度写死 P30 Gate B 实锤值，与 P30 T5 Y8 相同，不新增）。
6. **JoinIdResolver N+1 零容忍（硬探针计数）**：JoinIdResolver.resolve 内部 relatedGm.listIds（或 count）调用次数，**对输入 outerIds.size=N，T6 NFR 断言调用次数 ≤ 2×joins.size()**（最多 1 batch IN + 1 exists fallback 验证）；> 2J 直接 Fail（N+1 违反 Performance First 铁律）。
7. **P24 UNIT/DOC 路由公式、RRF 四系数（α=0.5 β=0.25 γ=0.1 δ=0.15）、三键排序 finalHybridScore↓docId↑shardId↑ 字节级 100% 全等（1000 sample 长[] 4 向量全等）**；与 P28/P29/P30 Gate B 实锤值完全一致，任何修改 Reject。

---

### SP3.1 Gate C 9Y 自检清单模板（Implement 后填 [x]，当前 Gate A 占位 [ ]）
| Y # | 自检项 | T5 对应 TR | Gate C 占位 |
|-----|--------|------------|-------------|
| Y1 | ZeroLeak3=3：fulltext 根包 top-level class {TD/FS/FSeg}=3（join/parser/cluster 子包装 '.' tail 过滤后 count=3） | T5 Y1 | [ ] |
| Y2 | NG-3：fulltext 根包 0 netty/grpc/p2p.client + 9 CNFE 基线全抛 + pom 0 新 dep/plugin | T5 Y2 | [ ] |
| Y3 | TOPK bucket sum=0：Join 合查链路 50×2 次搜索 bucketAccessCounter 每 shard 总和=0（仅 P21 topKNeighbors 能 ++） | T5 Y3 | [ ] |
| Y4 | Facade 单调不减：GM/FS/TD/DHQC/QueryWrapper/ShardTransport public 签名 P30 基线 ⊆ P31 集合（差集 empty） | T5 Y4 | [ ] |
| Y5 | UNIT/DOC 路由公式：1000 sample ShardRouter.resolveShardForUnitIdForTest 与 P24 字节级全等 | T5 Y5 | [ ] |
| Y6 | 合查新签名：HybridJoinClient 三重载 searchHybridJoin / searchHybridJoinWithWeights / searchHybridJoinWithLtr 反射法名 exact match Spec FR-3 | T5 Y6 | [ ] |
| Y7 | DeepEquals 稳定：JoinSpec 逐字段 hashCode 1000×Set.size=1 / JoinIdResolver.resolve 同输入 1000× 结果 Set.size=1 | T5 Y7 | [ ] |
| Y8 | ShardTransport 原 5 法：FulltextStore.ShardTransport.class.getDeclaredMethods() 5 法名 exact P24 基线 | T5 Y8 | [ ] |
| Y9 | TODO/FIXME/HACK 0 grep：(?<!\p{L})(TODO|FIXME|HACK)(?!\p{L}) + NO-TODO-BYPASS 豁免自身 0 命中 join 子包三文件 + Spec/Tasks 正文 | T5 Y9 | [ ] |
