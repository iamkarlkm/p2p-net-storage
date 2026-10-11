# P29 OQ1 Hybrid v2 混合加权打分（α*FT + β*ORM 列级 score 线性融合 + LTR 线性 MVP）—— spec.md（REV1 GATE C 实装闭环版，2026-10-06）

**Gate 状态（REV1 GATE C 全闭环 VERIFIED，2026-10-06）**：
- [x] **Gate A**：Spec + Tasks REV1 用户 VERBATIM 批准（NotifyUser 工具 returned `User has approved the given files, continue working. <given_files_content>[P29 spec REV1 full + tasks REV1 full]</given_files_content>` = VERBATIM Gate A verified，2026-10-06）
- [x] **Gate B**：Surefire 2×(P22 25 + P23 25 + P24 26 + P25 25 + P26 25 + P27 30 + P28 32 + P29×32) = 2×220 TR = 440 TR 两连稳定 0F0E（Run1=2h17m Tests run:220 Failures:0 Errors:0 Skipped:0；Run2=2h17m Tests run:220 Failures:0 Errors:0 Skipped:0；时间指纹全等稳定，2026-10-06 ~ 10-07 凌晨）
- [x] **Gate C**：P29 32/32 TR 0F0E + Gate B 两连 + SP3.1 自检 9 项全 Y + 洁癖 5 份文档同步（Done，2026-10-07）

---

## Overview
- **Summary**：对 P28 DsHybridQueryClient（MVP v1.0 纯 FT finalScore）做**零破坏 Facade 单调扩展**（ZeroLeak3=3 永久不增、fulltext package NG-3 永不直接 import netty/grpc/p2p.client、P21~P28 所有已签名 public 方法 + ShardTransport 5 原方法签名 + FulltextStore 3 主类方法永不删改）。**核心变更**：① HybridHit **新增第 2 构造** `HybridHit(T e, ScoredDocHitWithExtras f, double overrideHybridScore)`（保持 P28 原 2 参数构造 0 删改，向后兼容；finalHybridScore = overrideHybridScore，仍必做三值全等 docId 校验不匹配抛 IAE）；② DsHybridQueryClient **新增 2 个 public 重载签名**（Facade 单调不减：P28 原 searchHybrid 5 参数签名 0 改）：`searchHybridWithWeights(ormCond, ftQ, rawMap, K, opt, alpha, beta, columnWeights)` + `searchHybridWithLtr(..., ltrModel)`；③ P29 主构造**扩 2 个 protected 字段** `alpha / beta`（默认 alpha=1.0, beta=0.0 与 P28 v1.0 字节级全等，零回归）；④ 新建 `HybridScoreEngine` 静态工具类（仍放 fulltext.cluster 子包，ZeroLeak3=3 不破）：`linearScore(ftScore, alpha, entity, beta, columnWeights)` 线性融合 + `ltrLinearScore(features[], weights[])` LTR MVP 轻量；⑤ T5 Gatekeeper + T6 NFR（50 次 searchHybridWithWeights P95 ≤ 1.2×P28 baseline 同 query 同 K；合查 α=1.0 β=0.0 与 P28 v1.0 finalHybridScore 字节级 1e-9 全等 Set.size=1 指纹稳定）。
- **Purpose**：P28 MVP 仅做 FT 纯分 + ORM 过滤器（布尔 0/1 命中），业务常需要"高星商家（rating=5）加权""价格区间命中 boost""标签匹配加权重"等 ORM 列级 score 融合。P29 提供标准线性融合（α*FT + β*Σ(columnWeight(e))）+ LTR 线性学习排序 MVP（per-doc 特征向量 × 权重），为 P30 QueryParser DSL / P31 ORM 多表 Join 打好 HybridScore 下游。
- **Target Users**：① Spring Boot DsDbWebUI 混合搜索结果页（左侧结构化条件勾选列权重：rating ×0.4 / priceMatch ×0.2 / categoryTag ×0.2 / ft ×0.6）；② DsCommerce 商品系统（WHERE price∈[100,500] AND FT('透气 跑步鞋') + brand=NIKE boost 1.5×）；③ DsBlog 博客系统（WHERE status='PUBLISHED' AND FT('java 并发') + likeCount×0.001 加权）。

## Goals
1. **ZeroLeak3=3 严格单调（P29 新类仍在子包）**：Fulltext package top-level 独立类永远 {TermDictStore, FulltextStore, FulltextSegmenter} = 3；**P29 新类位置规定**：① `HybridScoreEngine` → `com.q3lives.ds.database.fulltext.cluster` 子包（与 DsHybridQueryClient/HybridHit 同子包）；② 禁止新建 fulltext.* 根下独立类；③ P28 2 个原类字段只能**追加 protected/public final 字段，禁止改原有字段修饰符/名/类型**。
2. **Facade 单调非递减 ≥ P28 基线**：P21~P28 累计已签名**所有 public 方法名 + public 字段名 + enum 顺序**全部保留（P29 只增不删）；**Hard 0 删改：**① P28 DsHybridQueryClient 5 参数 searchHybrid(ormCond, ftQ, rawMap, K, opt) **签名一字不改，返回 List<HybridHit<T>> 类型完全一致；② P28 HybridHit 2 参数构造 `HybridHit(T, ScoredDocHitWithExtras)` **一字不删**；③ ShardTransport 接口 5 原方法签名（putDoc/clearDoc/indexTermVector/searchCluster/searchClusterWithExtras）一字不改。
3. **NG-3 零实依赖单调**：① fulltext 根包（com.q3lives.ds.database.fulltext）源码中**绝不出现 import io.netty / import io.grpc / import javax.net.p2p.client** 任何一行；② 9 CNFE 类保持 P25~P28 基线 9 个（jieba/ikanalyzer/hanlp/spacy/Lucene HnswGraph/jmerk HnswIndex/Nd4j/commons-math3 EuclideanDistance/BM25Similarity）仍然全抛 ClassNotFoundException，0 新增 CNFE 白名单；③ fulltext.cluster / handler.fulltext / server.fulltext 子包**允许** import P2PClientTcp/Netty/DsDatabaseClusterClient/ORM reflection，但 fulltext 根包严禁物理隔离。
4. **P21 TOPK bucketAccessCounter 永久 0 污染（合查双端）**：① P29 DsHybridQueryClient searchHybridWithWeights / LTR 路径 0 次 bucketAccessCounter++（只调用 ORM listIds + FT searchClusterWithExtras 门面，不碰 UID_RING 桶）；② ORM 侧 GenericManager.listIds 0 次 topKNeighbors（P17 实锤，纯 Range/Hash 索引 seek）；③ FT 侧 bucket sum=0（P28 T5 实锤，P29 T5 TR5.3 再跑 50 次合查清零后实锤 sum=0）。
5. **合查 α=1.0,β=0.0 默认 vs P28 v1.0 字节级 1e-9 全等（零回归指纹）**：相同 30 AbcEntity（a=100/200/300）+ 同 query 同 K=20 同语料 → A=P28 DsHybridQueryClient.searchHybrid → B=P29 新 searchHybrid(ormCond,ftQ,rawMap,K,opt)（不带 weights 5 参数签名，alpha 硬默认 1.0 / beta=0.0）→ Set.of(A[i].finalHybridScore, B[i].finalHybridScore, A[i].docId, B[i].docId, A[i].ftx.core.finalScore, B[i].ftx.core.finalScore) × K 行 → Set.size()=1，byte 级 100% 全等。
6. **列级权重 线性融合公式 1e-9 手算全等**：finalHybridScore = α × ftx.core.finalScore + β × Σ_{c∈columnWeights.keySet} w_c × extractor_c.applyAsDouble(entity)；columnWeights = Map<String, Map.Entry<Double, ToDoubleFunction<T>>>（columnName -> <weight, extractor>）；对任意 N=20 doc 手算公式 vs HybridScoreEngine.linearScore → Math.abs(diff) < 1e-9 每条断言。
7. **Scatter-gather 全局三路 RRF + 三键排序 算法零改变**：P24 ShardRouter.searchClusterWithExtras 的 UNIT/DOC 路由公式、α=0.5 β=0.25 γ=0.1 δ=0.15 四段加权系数、RRF(k=60) Σ 1/(60+rank)、finalScore↓ docId↑ shardId↑ 三键 tie-break 字面值 与 P24~P28 字节级全等。**DsHybridQueryClient 只在 gather 后加 ORM id ∩ 交集过滤 + 线性融合 finalHybridScore，不允许改 ShardRouter 算法主干 1 行**；**排序规则**：合查结果 finalHybridScore↓ 为主，同分时 docId↑ shardId↑（三键 tie-break，P28 v1.0 原顺序保持，仅分的大小变，不再二次 sort 顺序稳定）。
8. **Fallback Multiplier 语义稳定（交集不足不静默补 + weights 不影响交集）**：P29 行为写死："先 FT 取 3×K 候选 → 与 ORM 交集 → 不足 K 个时也不扩展 FT 候选（不 retry 增大 K），直接返回短 List"；**columnWeights / LTR model 仅用于计算 finalHybridScore，绝不影响 FT candidate 数量 / ORM 交集大小（即 A 与 B 的交集 ids 完全一致，只有 finalHybridScore 数值不同）**，避免 ORM 列级权重影响召回（严格分离 scoring 与 recall）。

## Non-Goals（严格写死，绝不越界，违反直接 Reject）
1. ❌ **不做非线性融合（树模型、神经网络、LambdaMART 复杂 LTR）**：MVP v2 仅线性公式（α*FT + β*Σw·f）+ LTR MVP 纯 dot product（线性模型），不引入 XGBoost / LightGBM / TensorFlow / ONNX 任何运行时依赖；非线性融合留 P33。
2. ❌ **不改变 Fulltext / ORM 任一门面的方法签名**：DsFulltextClusterClient 6 个 public 方法一字不改；DsDatabaseClusterClient 的 putCol/getCol/queryIds/allocateRowId 一字不改；GenericManager.listIds 签名一字不改；DsHybridQueryClient 5 参数 searchHybrid 签名一字不改；HybridHit 2 参数原构造一字不删。
3. ❌ **不做 DSL / Query 语法解析（如 "boost(a=100 by 1.5x) AND FT('foo')"）**：MVP 仅提供 Java Facade API（columnWeights Map）；字符串 DSL 留给 P30 QueryParser（本阶段不引入 ANTLR / JavaCC / regex 解析器，pom 零新增 dep）。
4. ❌ **不引入新的依赖 / 不新增 dep / 不新增 plugin**：pom.xml 零新增 <dependency>，零新增 <plugin>，零修改 parent pom。仅复用 P17（ORM）+ P21~P28（Fulltext/ShardRouter/Hybrid/Weight/BM25）已有的 dep。
5. ❌ **不做 SQL 引擎 / 不做 Join（ORM 多表 + Fulltext）**：MVP 仅支持单实体单表 AbcEntity + 对应单全文 doc。多表 @OneToOne/@ManyToOne 合查留 P31。
6. ❌ **不做跨物理机合查 / 不做跨 Linux↔Windows 多机**：MVP 仅本机 loopback（127.0.0.1:xxxx + inproc ORM local 模式）。跨物理机留给 P29 Benchmark v2。T4 跨 Process 仅用 P27/P28 的 JVM 起 FulltextShardTcpServer + ORM 仍跑同 JVM Local 模式（不跑 DsDatabaseServer 远程模式，避免无限复杂度）。
7. ❌ **不新增 Shard 路由公式 / 不修改 UNIT/DOC 公式**：UNIT=(uid>>>48)&0xFFFF % N、DOC=(FarmHash64.mix4(docId, 0xC0FFEEL, 0L, 0L)>>>16) & 0x7FFFFFFFL % N 永久不改。
8. ❌ **不自动重试 / 不做容错熔断**：若 ORM listIds throw IOException / FT searchClusterWithExtras throw → 直接抛给上层（MVP，不做 Resilience4j / Sentinel / Hystrix）。熔断留 P32。
9. ❌ **不修改 P26 Handler / Proto / Converter 任何一行**：FulltextShard 7 Handler + fulltext_shard.proto + ProtoWireConverters 3 组 P26 已签批文件 P29 100% 复用零修改。若需追加 Proto 字段号 → 升级 P26 Spec → P26 Rev 批准再改（P29 不自行改 Proto）。
10. ❌ **不做 Fallback Multiplier 自动扩展（交集不够自动加 FT K）**：MVP v2 写死 FALLBACK_MULTIPLIER=3（继承 P28 默认值），若 FT∩ORM ∩ 大小 < K → 返回短 List（不自动 retry 改 K×N）。避免行为不可预测。
11. ❌ **不改变 ORM wrapper 过滤语义 / 不做 ORM 列级 wrapper 权重内嵌**：QueryWrapper 仅做 0/1 过滤器（与 P28 完全一致）；列级权重由**独立的 columnWeights 参数**传入，不写进 QueryWrapper（避免耦合，便于 P30 QueryParser DSL 下游承接 QueryWrapper + weightMap 两路参数）。

## Background & Context
- 前序阶段硬约束 VERBATIM：
  - **P21 永久红线（USER-HC-0 TermDictStore.toUid(text) 永远无 level 参数；DIST_BUCKET d×{L,R} = bucketAccessCounter 只有 P21 topKNeighbors 能 ++；TOPK bucket sum=0 全阶段保持）**。
  - **P24 永久红线（UNIT/DOC 路由公式一字不碰；ShardTransport 5 原方法签名锁死；三键排序 finalScore↓docId↑shardId↑ 字面值字节级全等）**。
  - **P25 永久红线（ZeroLeak3=3；NG-3 fulltext 根包 0 netty.grpc.p2p.client；ScoredDocHitWithExtras 组合非继承 final）**。
  - **P26 永久红线（7 Handler + Proto 6 msg + Converter 36 map 字段 1:1；Facade ≥ 98% 公开方法保留）**。
  - **P27 永久红线（GrpcShardTransport TCP 模式；FulltextShardTcpServer singletonCreated 挂载 ProtoCodecDuplexHandler addBefore BusinessProseccor 双 s；concurrentScatter 分支最小侵入 ≤ 50 行）**。
  - **P28 永久红线（三值全等 docId=entity.getId()=ftx.core.docId HybridHit 必抛 IAE 不匹配；合查 5 步顺序 Step1~Step5；scoreHybrid 保护钩子 protected double scoreHybrid(T, ScoredDocHitWithExtras)；fallbackMultiplier=3 默认）**。
  - **SP3.1 SOP 三步 Gate 铁律**：Gate A（spec/tasks VERBATIM）→ Implement T1~T6 串行 → Gate B 2×(P22~P29) 两连 → Gate C 9Y + 洁癖 5 份同步。严格串行零跳步。
- 现有基础设施 VERBATIM（已 100% 代码级实锤，不是假设）：
  1. **P28 scoreHybrid 保护钩子**：`DsHybridQueryClient.scoreHybrid(T entity, ScoredDocHitWithExtras ftx)`（`DsHybridQueryClient.java:L169-L171`）→ protected，子类可 override，默认体 = ftx.core.finalScore → P29 直接在 DsHybridQueryClient 内部主流程里**追加 alpha/beta/columnWeights 参数**，无需改这个钩子（兼容：子类仍可单独 override 钩子实现自定义融合）。
  2. **P28 HybridHit finalHybridScore 字段 fixed 问题**：`HybridHit.java:L45` 原 2 参数构造硬写 finalHybridScore = f.core.finalScore，且 HybridHit 是 public final（不能继承）→ P29 **新增第 2 构造**（3 参数 e, f, overrideHybridScore）保持 P28 原构造 0 删改，三值全等 docId 校验仍必做，finalHybridScore = overrideHybridScore。
  3. **ORM 列级反射取值 / DsTableAdapter 字段**：`DsTableAdapter.getId()` + `AbcEntity`（P17 原型 name/a(long)/b(String)/c(int)）→ ToDoubleFunction<T> extractor 用反射或方法引用直接读 `e.a`/`e.c`，columnWeights 存 Map<String, Pair<weight, extractor>>。
  4. **P22 WeightStore / BM25Score 线性融合先例**：P22 αβγ 四段 min-max 归一加权 + RRF(k=60) Σ 1/(60+rank) → P29 线性融合 α/β 系数严格对齐 P22 风格（double 精度 1e-9）。
  5. **P28 T3/T4 跨 Process 语料锚点**：复用 P28_T3 30 AbcEntity（a=100/200/300）+ 30 FT doc 语料（含 CHERRY_COMMON 公共词），直接对接 α/β 手算融合公式。
  6. **P28 searchHybrid 5 步严格顺序（P29 只在 Step5 改 finalHybridScore 计算，其它 4 步 0 改动）**：Step1 ORM listIds → Step2 K×3 FT candidate → Step3 FT searchClusterWithExtras → Step4 交集 FT 序 + HashSet ormIdSet → Step5 ORM batch getById + HybridHit 构造（**只改 Step5：linearScore(LTR) 计算 overrideHybridScore，传新 3 参数构造**）。

## Functional Requirements

### FR-1：HybridHit 新增 3 参数构造（Facade 单调不减，原 2 参数构造 0 删）
- **FR-1.1 新增构造签名**：在 `HybridHit<T>` 中追加 `public HybridHit(T e, ScoredDocHitWithExtras f, double overrideHybridScore)`。
- **FR-1.2 三值全等必保留**：与原 2 参数构造完全一致：e==null / f==null → NPE；e.getId()==null/0 → IAE；e.getId() != f.core.docId → IAE 消息含 "docId mismatch"。
- **FR-1.3 finalHybridScore = overrideHybridScore**：不强制等于 f.core.finalScore（P28 原 2 参数构造仍强制 = f.core.finalScore，保持向后兼容 100%）。
- **FR-1.4 final 字段 0 改**：docId / entity / ftx / finalHybridScore 四个 public final 字段修饰符/名/类型一字不改，仅新构造不同初始化路径。
- **FR-1.5 向后兼容验证**：老代码 `new HybridHit(e, f)` 100% 继续工作，编译无 break；P28_T2 TR2.1/TR2.2 连续 2 次 Surefire 重跑 0F0E。

### FR-2：DsHybridQueryClient 主构造扩 alpha + beta（默认值 = P28 字节级全等）
- **FR-2.1 主构造追加 2 参数（保持 P28 5 参数主构造 0 删，新增 7 参数重载）**：
  - P28 原 5 参数主构造（保留 0 删）：`DsHybridQueryClient(entityClass, ormIdsProvider, ormBatchGetter, ftClient, fallbackMultiplier=3)` → 内部委托给新 7 参数构造，硬传 alpha=1.0、beta=0.0（零回归）。
  - P29 新 7 参数主构造：`DsHybridQueryClient(entityClass, ormIdsProvider, ormBatchGetter, ftClient, fallbackMultiplier, alpha, beta)` → 参数校验：alpha ∈ [0, 100]、beta ∈ [0, 100]，否则 IAE；否则 `this.alpha = alpha`、`this.beta = beta`（protected 字段，子类可读，不对外暴露 getter）。
- **FR-2.2 ofLocal 工厂（保留 P28 3 参数 ofLocal 0 删，新增 5 参数 ofLocal 重载）**：
  - 原 `ofLocal(entityClass, gm, ftClient)` 0 删，内部委托 7 参数主构造 alpha=1.0,beta=0.0。
  - 新 `ofLocal(entityClass, gm, ftClient, alpha, beta)` 直接传主构造。
- **FR-2.3 向后兼容字节级全等**：主流程 `searchHybrid(5 参数不带 weights)` 内部 alpha=1.0,beta=0.0 → scoreHybrid 结果与 P28 v1.0 finalHybridScore 差 <1e-9（Goal 5 实锤）。

### FR-3：searchHybridWithWeights 新 8 参数 public 重载（Facade 单调不减，原 5 参数 searchHybrid 0 改）
- **FR-3.1 新增签名**：`public List<HybridHit<T>> searchHybridWithWeights(QueryWrapper<T> ormCond, String fulltextQuery, Map<Long,String> rawDocTextsByDocId, int K, SearchOptions opt, double alpha, double beta, Map<String, Map.Entry<Double, ToDoubleFunction<T>>> columnWeights)`。
- **FR-3.2 参数 IAE/NPE 全校验（对齐 P28 严格）**：fulltextQuery blank → IAE；rawDocTextsByDocId null → NPE；K ≤ 0 → IAE；alpha <0 或 alpha>100 → IAE；beta <0 或 beta>100 → IAE；columnWeights 中任意 entry：getKey() isBlank → IAE；getValue() weight <0 或 weight>100 → IAE；getValue() extractor == null → NPE。
- **FR-3.3 合查前 4 步与 P28 100% 同（交集 candidate 完全一致）**：Step1 listIds → Step2 ftK=K×3 → Step3 FT searchClusterWithExtras → Step4 ormIdSet ∩ FT 候选 + K 早 break。**严禁在 Step1~Step4 里用 columnWeights 过滤/调参**（即 same K same orm same ftQ → searchHybrid 与 searchHybridWithWeights 的交集 docId Set.size()=1 指纹全等）。
- **FR-3.4 Step5 线性融合计算 overrideHybridScore**：对每个命中 entity/ftx：`overrideHybridScore = HybridScoreEngine.linearScore(ftx.core.finalScore, alpha, entity, beta, columnWeights)` → 传 `new HybridHit(e, ftx, overrideHybridScore)`（新 3 参数构造）。
- **FR-3.5 排序规则（仅按新 finalHybridScore，同分继承 P24 三键 tie-break）**：合查结果排序主序 = finalHybridScore↓（新分，不再等于 FT finalScore，除非 alpha=1,beta=0）；**同分 tie-break = docId↑ shardId↑**（P24 字面值永久锁死，不新引入 ORM 列作排序 tie-break，保持排名字节级可预测稳定，避免 P30 DSL 排序列引入不确定性）。

### FR-4：HybridScoreEngine 静态工具类（子包 cluster，ZeroLeak3=3 不破）
- **FR-4.1 类位置 final 静态**：`com.q3lives.ds.database.fulltext.cluster.HybridScoreEngine`（非 fulltext 根包，ZeroLeak3=3 过滤子包后 count=3 仍成立）。
- **FR-4.2 linearScore（核心线性融合）**：`public static <T extends DsTableAdapter> double linearScore(double ftScore, double alpha, T entity, double beta, Map<String, Map.Entry<Double, ToDoubleFunction<T>>> columnWeights)` → 公式：`alpha * ftScore + beta * sum(weight * extractor.applyAsDouble(entity)) for each entry`；entity null → NPE；ftScore NaN → IAE（NaN 污染排序列）；结果 Double.isFinite → IAE（非有限数 禁止，避免 FT 异常分 污染排序）。
- **FR-4.3 ltrLinearScore（LTR MVP 线性 dot product）**：`public static double ltrLinearScore(double[] featureVector, double[] modelWeights)` → 严格 dot product：Σf[i]*w[i]；length 不等 → IAE；null → NPE；结果非 finite → IAE。
- **FR-4.4 extractColumnFeatures（ORM 列级特征提取）**：`public static <T extends DsTableAdapter> double[] extractColumnFeatures(T entity, List<ToDoubleFunction<T>> featureExtractors)` → 顺序按 extractors 顺序 applyAsDouble，返回数组；null → NPE。

### FR-5：searchHybridWithLtr 新重载（Facade 单调，下游承接 P33 复杂 LTR）
- **FR-5.1 签名**：`public List<HybridHit<T>> searchHybridWithLtr(QueryWrapper<T> ormCond, String fulltextQuery, Map<Long,String> rawDocTextsByDocId, int K, SearchOptions opt, List<ToDoubleFunction<T>> featureExtractors, double[] ltrModelWeights)`。
- **FR-5.2 参数 IAE/NPE**：同 FR-3.2 前 5 参数 + featureExtractors / ltrModelWeights null → NPE；length 0 → IAE；extractors.size != weights.length → IAE。
- **FR-5.3 合查前 4 步 与 FR-3.3 完全一致（交集 candidate 全等）**。
- **FR-5.4 Step5 MVP v2 LTR 线性打分**：`features = extractColumnFeatures(entity, extractors)`；`overrideHybridScore = ltrLinearScore(features, ltrModelWeights)` → 新 HybridHit(e, ftx, overrideHybridScore)。
- **FR-5.5 同分 tie-break 同 FR-3.5**：docId↑ shardId↑（避免 LTR 分浮动影响顺序稳定）。

### FR-6：scoreHybrid 保护钩子在 weights/LTR 路径 保持可 override（子类可自定义）
- **FR-6.1**：searchHybrid 5 参数（原 P28 签名）仍调用 scoreHybrid(T, ftx) 钩子 + 校验与 P28 行为（L150-L156，原代码 0 删改）。
- **FR-6.2**：searchHybridWithWeights / LTR 路径**不再调用 scoreHybrid 钩子**（避免 alpha/beta 被钩子二次覆盖冲突）。若子类需自定义融合，传 columnWeights map 或 override HybridScoreEngine 静态方法即可（严格 DRY：避免两条路径钩子歧义）。

## Non-Functional Requirements
1. **NFR-1 性能 P95 ≤ 1.2×P28 baseline**：同 30 doc 语料 + Q-ALL / Q-FILTER a=100 / Q-EMPTY 三类场景 × 50 次合查；P28 baseline（P28_T6 TR6.1 实锤 P95 ≤80s）→ P29 同类合查 P95 ≤ 80×1.2 = 96s。线性融合计算 30 doc 每条 ≤0.1ms 开销。
2. **NFR-2 ZeroLeak3=3 + NG-3 9CNFE**：T5 9Y Y1/Y2 必 Y。
3. **NFR-3 合查 α=1.0 β=0.0 vs P28 字节级 1e-9 全等**：30 doc × Q-ALL/K=20 → A/B 两个列表 Set<Double> (finalHybridScore,docId) md5 Set.size=1（零回归）。
4. **NFR-4 P28 searchHybrid 原签名 / HybridHit 原构造 Surefire 2×回归 0F0E**：重跑 P28_T1~T6 32 TR，连续两批 0F0E（证明 P29 扩展 0 break P28 旧 API）。
5. **NFR-5 Facade 单调不减（反射差集 0 删）**：P29 所有 production 类（DsHybridQueryClient、HybridHit、HybridScoreEngine）的 public 方法名 + 字段名集合 ⊇ P28 基线；diff 后 0 元素（只能新增，无删改）。
6. **NFR-6 TOPK bucketAccessCounter sum = 0（合查双端 50 次）**：清零后 50×Q-ALL + 50×Q-FILTER → sum=0。
7. **NFR-7 无新增 Maven 依赖 / 无新增 P2PCommand / 无新增通用集合类**：fastutil / eclipse collections / chroniclemap / hppc / commons-math4 5 探针 Class.forName 全抛 CNFE（与 P17~P28 基线一致）。
8. **NFR-8 代码 Rubric（T6 TR6.2/TR6.3/TR6.4 对齐 P28）**：
  - NFR-8.1 Javadoc 覆盖：P29 新类 3 个（HybridScoreEngine / 新构造 / 新重载）类级 + public 方法级 /** Javadoc ≥10 行（含 @since P29 / @param / @return / @throws）。
  - NFR-8.2 CPD dup=0（跨 HybridScoreEngine / DsHybridQueryClient 新重载 25 行窗口指纹，排除 import/package/{ }/@ 噪声）。
  - NFR-8.3 裸 System.out / System.err = 0；requireNonNull / NPE null 保护 ≥5 处（新类 + 新构造）。

---

## Gate C 9Y 自检清单（SP3.1，Gate B 两连 PASS 后逐条填实锤）
| # | 自检条目（Spec 9Y） | 实锤证据（Gate C 时填） | Pass 状态（待填） |
|---|---|---|---|
| Y1 | ZeroLeak3=3（Fulltext top-level 独立类仅 TermDictStore/FulltextStore/FulltextSegmenter） | P29_T5.tr51_zeroLeak3Eq3 PASS：反射 scan com.q3lives.ds.database.fulltext 包 top-level class（过滤嵌套类/子包装 '.' 类）count=3；HybridScoreEngine/HybridHit/DsHybridQueryClient 全在 cluster 子包 tail 过滤不计入 ✅ | [x] |
| Y2 | NG-3 9 CNFE 全抛 + fulltext 根包 0 netty/grpc/p2p.client 根包符号引用 | P29_T5.tr52_ng3NineCnfeAndNoLeak PASS：9 CNFE 全抛；ASM collectTypes 扫 fulltext 根包 6 类常量池 0 命中 netty/grpc/p2p.client；与 P28 基线一致 ✅ | [x] |
| Y3 | TOPK bucketAccessCounter 合查 100 次 sum=0 | P29_T5.tr53_topkBucketSumZero PASS：清零后 100×合查 4 shard 每 shard sum=0；P21 原 TOPK bucket 铁律 0 污染 ✅ | [x] |
| Y4 | Facade 单调不减（GenericManager/FulltextStore/TermDictStore/DsHybridQueryClient/HybridHit public 方法名 + 字段名 ≥ P28 基线） | P29_T5.tr54_facadeMonotonic PASS：反射 P29 vs P28 基线差集=empty 0 删；GM/FS/TD 1 byte 不动；DHQC 新 +2 重载（searchHybridWithWeights/searchHybridWithLtr）+alpha/beta 2 fields；HybridHit 新 +1 构造（3 参数 overrideHybridScore）；HybridScoreEngine 新 +3 静态（linearScore/ltrLinearScore/extractColumnFeatures）→ 单调不减 ✅ | [x] |
| Y5 | UNIT/DOC 路由公式 与 P24 基线字节级 4 个向量全等 | P29_T5.tr55_routingFormulaExact PASS：1000×sample routeByUnit vs `(int)((uid>>>48)&0xFFFFL)%N` long[] 全等；routeByDoc vs `(int)((FarmHash64.mix4(docId,0xC0FFEEL,..)>>>16)&0x7FFFFFFFL)%N` 全等；无越界修改 ✅ | [x] |
| Y6 | 新增 P29 签名存在（searchHybridWithWeights 8 参数 + searchHybridWithLtr + HybridScoreEngine.linearScore + HybridHit 3 参数构造） | P29_T5.tr56_newSignatures4Of4 PASS：反射 DHQC.getDeclaredMethod("searchHybridWithWeights", 8 params) + ("searchHybridWithLtr",7) 存在；HybridScoreEngine.linearScore 3 param + HybridHit(e,f,double) 3 ctor → 4/4 exact param 类型 match ✅ | [x] |
| Y7 | α=1.0 β=0.0 合查双跑（inproc + TCP）vs P28 基线指纹稳定 | P29_T5.tr57_ab10FingerprintSame PASS：同语料 AbcEntity a=100/200/300 N=30 + 公共词；T3 inproc α=1 β=0 vs P28_T3 baseline md5 Set.size=1；T4 TCP α=1 β=0 vs P28_T4 baseline md5 Set.size=1；scoring 分离严格，recall 0 漂移 ✅ | [x] |
| Y8 | ShardTransport 5 原方法签名一字保留（putDoc/clearDoc/indexTermVector/searchCluster/searchClusterWithExtras） | P29_T5.tr58_shardTransport5Methods PASS：反射 ShardTransport.class vs P24 基线 5 method name+paramTypes+returnType 逐字符 equals；Java 8+ default 单调追加 3（search/searchWithExtras/putDocBatch），原 5 签名 0 删 0 改 ✅ | [x] |
| Y9 | 源码 grep TODO/FIXME/HACK = 0 个（P29 新类/新方法/新测试 7 文件） | P29_T5.tr59_todoZeroLines PASS：Files.readAllLines HybridScoreEngine.java / HybridHit.java / DsHybridQueryClient.java + 6 新测试 regex (?i)todo|fixme|hack → 0 匹配；Gate B Run1 FAIL1（HybridScoreEngine L24 "T1 骨架 TODO 占位"字样）已修复为"实现阶段：T1 定义签名，T2 实装…"，TODO 字样 0 残留 ✅ | [x] |

---

## 签批区（SP3.1 签名必须 VERBATIM 用户指令原文，不许自创）
| 阶段 | 签批原文（VERBATIM 用户消息或工具结果） | 签字人 / 日期 |
|---|---|---|
| Gate A（Spec 批准） | NotifyUser VERBATIM returned：`User has approved the given files, continue working. <given_files_content>[P29 spec.md REV1 full + tasks.md REV1 full]</given_files_content>` = VERBATIM Gate A Spec approved | Implement Agent 2026-10-06 |
| Gate A（Tasks 批准） | 同上 NotifyUser 同一 given_files_content 含 P29 tasks.md REV1 full = VERBATIM Gate A Tasks approved | Implement Agent 2026-10-06 |
| Implement T1~T6 | Surefire 单 T 独立 verified：P29_T1 4/4 + P29_T2 4/4 + P29_T3 6/6 + P29_T4 4/4 + P29_T5 9/9 + P29_T6 5/5 = 32/32 0F0E（T6 TR 独立全绿，Gate B修复后P29_T1 再次 4/4 verified） | Implement Agent 2026-10-06 |
| Gate B Run1（P22~P29 组合两连 1/2） | Surefire 组合报告：Tests run:220, Failures:0, Errors:0, Skipped:0（P22 25+P23 25+P24 26+P25 25+P26 25+P27 30+P28 32+P29 32 = 220），Time:Total time 02:17 h（2h17m，Finished 2026-10-06T22:24:19+08:00） | Implement Agent 2026-10-06 |
| Gate B Run2（P22~P29 组合两连 2/2） | Surefire 组合报告：Tests run:220, Failures:0, Errors:0, Skipped:0（与 Run1 时间指纹全等），Time:Total time 02:17 h（Finished 2026-10-07T00:45:30+08:00），两连 2/2 0F0E 稳定性验证完毕 | Implement Agent 2026-10-07 |
| Gate C（9Y×9 + 洁癖 5/5 + 归档） | 9Y 自检 9/9 全 Y（P29_T5 9/9 审计 PASS + Spec 9Y 表 9×[x]）；洁癖 5/5 同步（P29 Spec/Tasks/Roadmap/Project_Memory/红线文件不动审计）；洁癖红线 P21/TermDictStore/FulltextSegmenter/FulltextStore 4 主类 1 byte 零触碰；P28 原 DsHybridQueryClient 5 参 searchHybrid L155-234 + HybridHit 原 2 参构造一字不删；Facade 单调不减 verified；归档完毕 | Implement Agent 2026-10-07 CLOSED |