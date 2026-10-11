# P28 OQ13 Query Builder ORM+Fulltext 混合合查（HybridJoin：DsDatabaseClusterClient + DsFulltextClusterClient 按 docId 1:1 对齐 TopN 下推）—— spec.md（REV1 GATE C 签批版，2026-10-06）

**Gate 状态（REV1 GATE C 2026-10-06 签批闭环，0F0E）**：
- [x] **Gate A**：Spec + Tasks REV1 用户 VERBATIM 批准（2026-10-05 NotifyUser 后 given_files_content 通过）
- [x] **Gate B**：Surefire 2×(P22+P23+P24+P25+P26+P27+P28) = 2×188 TR 两连稳定（Run1 1h37m / Run2 1h36m，Tests run:188 Failures:0 Errors:0 Skipped:0，42 XML 全绿）
- [x] **Gate C**：P28 32/32 TR 0F0E + Gate B 2×188 两连 + SP3.1 自检 9 项全 Y + 洁癖 5 份文档同步（2026-10-06 签批）

---

## Overview
- **Summary**：对 P27 真实 TCP 集群 + P17/P7 ORM 基础设施做**零破坏 Facade 单调扩展**（ZeroLeak3=3 永久不增、fulltext package NG-3 永不直接 import netty/grpc/p2p.client、P21~P27 所有已签名 public 方法 + ShardTransport 8 方法签名 + FulltextStore 3 主类方法永不删改）。**核心变更**：① 新建组合类 `HybridHit<T>`（位置：`com.q3lives.ds.database.fulltext.cluster` 内嵌，非 fulltext 根包，不破坏 ZeroLeak3=3），T 为 `DsTableAdapter` 子类，包含 `T entity` + `ScoredDocHitWithExtras ftx` + `double finalHybridScore`；② 新建 Facade `DsHybridQueryClient<T>`（同 .cluster 子包），`public <E extends DsTableAdapter> List<HybridHit<E>> searchHybrid(QueryWrapper<E> ormCond, String fulltextQ, Map<Long,String> rawDocTextsByDocId, int K, SearchOptions opt)`，内部三步走：Step-1 ORM `candidateIdsForQuery(ormCond)` 过滤得 Set<Long> ormIds（T1~T2 用 GenericManager.public 新增 `listIds(QueryWrapper)`，T3~T6 用 DsDatabaseClusterClient.queryIds）→ Step-2 Fulltext `searchClusterWithExtras(fulltextQ, docTexts, K×FALLBACK_MULTIPLIER, opt)` 取 FALLBACK_MULTIPLIER=3×K 候选 → Step-3 交集 merge（ormIds ∩ fulltextIds），按 fulltext.finalScore↓ docId↑ shardId↑ 三键排序 TopK，然后 ORM 批量 getTable(entityClass, id) 填 entity，最后 `finalHybridScore = fulltext.finalScore`（MVP v1.0 纯 Fulltext 分打 ORM 过滤器，不引入线性混合系数 alpha，避免 HyperParameter 调参）；③ 单调新增 GenericManager.public `List<Long> listIds(QueryWrapper<T> w)` = 包装私有 `candidateIdsForQuery`（Facade 单调不减，无 break）；④ T5 Gatekeeper + T6 NFR（100 实体 × 100 query P95 ≤2×P27_TCP，合查交集结果与单 Fulltext K=50 TopK ∩ ORM 结果 1:1 字节级指纹全等）。
- **Purpose**：P21~P27 已把全文 7 阶段闭环（倒排→打分→向量→分片→Extras→GRPC→TCP），但业务系统最常见需求 = "WHERE name LIKE '%foo%' AND category='x'（ORM 条件）AND 全文匹配 'bar baz'（文本相关度）合查 + 返回前 K 行实体 + Extras(snippet/facet/span) 展示"。P28 实现该 MVP：① 允许 ORM QueryWrapper 做结构化过滤（WHERE a>10 AND b IN (...)），再套全文相关度排序 TopN 下推；② 零破坏 ORM 与 Fulltext 两个独立门面（DsDatabaseClusterClient / DsFulltextClusterClient 永不合并，HybridQueryClient 仅做组合门面 Facade 模式）；③ 为 P29 真实多机 Benchmark / P30 Query DSL（如 "(a > 10) AND FT('hello world')"）打底层 MVP 基础；④ 验证 docId === entity.getId()（DsIdLayout 全局 rowId）命名空间共享的可行性，避免不必要的中间映射表。
- **Target Users**：① Spring Boot DsDbWebUI（混合搜索页：左侧结构化条件表单 + 中间全文 query 输入框 + 右侧结果卡片 = snippet + 结构化列）；② DsBlog 博客系统（WHERE status='PUBLISHED' AND publish_time < NOW() AND FT('java 并发')）；③ DsCommerce 商品系统（WHERE price BETWEEN 100 AND 500 AND category_id=7 AND FT('透气 跑步鞋')）。

## Goals
1. **ZeroLeak3=3 严格单调**：Fulltext package top-level 独立类永远 {TermDictStore, FulltextStore, FulltextSegmenter} = 3；**P28 新类位置规定**：① `HybridHit` + `DsHybridQueryClient` → 全放 `com.q3lives.ds.database.fulltext.cluster`（子包，不进根）；② GenericManager 新增 public 方法仅加方法签名，不改类所属 package；③ **禁止**新建 `com.q3lives.ds.database.fulltext.*` 根下独立类，违反直接 Reject。
2. **Facade 单调非递减 ≥ P27 基线**：P21~P27 累计已签名**所有 public 方法名 + public 字段名 + enum 顺序**全部保留（P28 只增不删）；ShardTransport 接口 8 方法签名（5 原+3 default）一字不改；FulltextStore/TermDictStore/FulltextSegmenter **三个独立主类禁止改任何一行**（仅允许 .cluster 子包新建 2 类 + GenericManager 新增 1~2 个 public 方法；若必须改内嵌类 ShardRouter 等必须 NG-2 显式列最小改动）。
3. **NG-3 零实依赖单调**：① fulltext 根包（`com.q3lives.ds.database.fulltext`）源码中**绝不出现 `import io.netty` / `import io.grpc` / `import javax.net.p2p.client`** 任何一行；② Class.forName 9 CNFE 类保持 P25/P26/P27 基线 9 个（jieba/ikanalyzer/hanlp/spacy/Lucene HnswGraph/jmerk HnswIndex/Nd4j/commons-math3 EuclideanDistance/BM25Similarity）仍然全抛 ClassNotFoundException，0 新增 CNFE 白名单；③ fulltext.cluster / server.fulltext / handler.fulltext 子包**允许** import P2PClientTcp/Netty/DsDatabaseClusterClient（ORM），但 fulltext 根包严禁物理隔离。
4. **P21 TOPK bucketAccessCounter 永久 0 污染（合查双端）**：① DsHybridQueryClient 合查流程 0 次 bucketAccessCounter++（只调用 ORM listIds + FT searchClusterWithExtras 门面，不碰 UID_RING 桶）；② ORM 侧 GenericManager.candidateIdsForQuery 0 次 topKNeighbors（已 P17 实锤，纯 Range/Hash 索引 seek）；③ FT 侧 bucket sum=0（P27 T5 实锤，P28 T5 TR5.1 再跑 50 次合查清零后实锤 sum=0）。
5. **合查结果 vs 手算交集（Fulltext ∩ ORM）指纹 100% 全等**：相同 30 AbcEntity（带 4 列 name/a/b/c），其中 name 字段同步写入 Fulltext doc（docId = entity.getId() 全局同值）；query 组合：`QueryWrapper.eq("a", 100).in("b", {"x","y"})` + FT query "CHERRY_COMMON" + K=10 → ① 手算：先 listIds(QueryWrapper) → ids_A，再 FT searchClusterWithExtras 取 K×3=30 → ids_F = hit.core.docId，交集 I = ids_A ∩ ids_F → 按 FT 三键排序取前 10，再 getTable 填 entity；② DsHybridQueryClient.searchHybrid 自动化；两者 `Set<HybridHitFingerprint>` Set.size() = 1，HybridHitFingerprint = (docId, entity所有字段值md5, FT.finalScore, FT.sortScore, FT.rrfScore, FT.phraseBoostScore, FT.matchedTerms.size, FT.vectorScore) × K 行。
6. **ORM QueryWrapper 与 P17 复合索引 seek 1:1 对齐**：DsHybridQueryClient 不自定义任何过滤逻辑；**ormCond 条件过滤必须 100% 委托给 GenericManager.listIds / DsDatabaseClusterClient.queryIds**，合查 Facade 不能二次再写 where 过滤（避免双重过滤不一致 bug）。若 QueryWrapper 包含复合索引列（P17 idx_abc 列 a,b,c），ORM 底层自动用 P17 复合 Range seek；若 QueryWrapper 条件为无索引全表 → ORM 侧 candidateIdsForQuery 自动回退 ids 集合全扫（合查 Facade 不介入，保持 P17 MVP 行为）。
7. **Scatter-gather 全局三路 RRF + 三键排序 算法零改变**：P24 ShardRouter.searchClusterWithExtras 的 UNIT/DOC 路由公式、α=0.5 β=0.25 γ=0.1 δ=0.15 四段加权系数、RRF(k=60) Σ 1/(60+rank)、finalScore↓ docId↑ shardId↑ 三键 tie-break 字面值 与 P24~P27 字节级全等。**DsHybridQueryClient 只在 gather 后加 ORM id ∩ 交集过滤，不允许改 ShardRouter 算法主干 1 行**。
8. **Fallback Multiplier 语义稳定（交集不足不静默补）**：MVP v1.0 语义写死："先 FT 取 3×K 候选 → 与 ORM 交集 → 不足 K 个时也不扩展 FT 候选（不 retry 增大 K），直接返回不足 K 的较短 List"；若业务需要 "交集不足则放宽 ORM 条件 / 扩大 FT K×N"，留 P29 Future（本 Spec Non-Goals 禁止实现自动补全算法）。

## Non-Goals（严格写死，绝不越界，违反直接 Reject）
1. ❌ **不做加权混合分（alpha*FT + beta*ORM）/ 学习排序（LTR）**：MVP v1.0 合查排序 = 纯 FT finalScore↓ 三键，ORM 条件仅做布尔过滤器（在/不在集合），不引入混合打分公式、不调 alpha/beta/gamma 超参、不引入 LR/LambdaMART 模型。混合打分留给 P29 HybridScore v2。
2. ❌ **不改变 Fulltext / ORM 任一门面的方法签名**：DsFulltextClusterClient 6 个 public 方法（putDocCluster/clearDocCluster/indexTermVectorCluster/searchCluster/searchClusterWithExtras/close）一字不改；DsDatabaseClusterClient 的 putCol/getCol/queryIds/allocateRowId 一字不改；HybridQueryClient 仅新增门面，不侵入旧门面。
3. ❌ **不做 DSL / Query 语法解析（如 "(a > 10) AND FT('foo')" 字符串 DSL）**：MVP 仅提供 Java Facade API。字符串 DSL 留给 P30 QueryParser（本阶段不引入 ANTLR / JavaCC / regex 解析器，pom 零新增 dep）。
4. ❌ **不引入新的依赖 / 不新增 dep / 不新增 plugin**：pom.xml 零新增 `<dependency>`，零新增 `<plugin>`，零修改 parent pom。仅复用 P17（ORM）+ P21~P27（Fulltext）已有的 dep。
5. ❌ **不做 SQL 引擎 / 不做 Join（ORM 多表 + Fulltext）**：MVP 仅支持单实体单表 `AbcEntity` + 对应单全文 doc（name/text 字段）。多表 @OneToOne/@ManyToOne 合查留 P31。
6. ❌ **不做跨物理机合查 / 不做跨 Linux↔Windows 多机**：MVP 仅本机 loopback（127.0.0.1:xxxx + inproc ORM local 模式）。跨物理机留给 P29 Benchmark。T4 跨 Process 仅用 P27 的 JVM 起 FulltextShardTcpServer + ORM 仍跑同 JVM Local 模式（不跑 DsDatabaseServer 远程模式，避免无限复杂度）。
7. ❌ **不新增 Shard 路由公式 / 不修改 UNIT/DOC 公式**：UNIT=`(uid>>>48)&0xFFFF % N`、DOC=`(FarmHash64.mix4(docId, 0xC0FFEEL, 0L, 0L)>>>16) & 0x7FFFFFFFL % N` 永久不改。
8. ❌ **不自动重试 / 不做容错熔断**：若 ORM listIds throw IOException / FT searchClusterWithExtras throw → 直接抛给上层（MVP，不做 Resilience4j / Sentinel / Hystrix）。熔断留 P32。
9. ❌ **不修改 P26 Handler / Proto / Converter 任何一行**：FulltextShard 7 Handler + fulltext_shard.proto + ProtoWireConverters 3 组 P26 已签批文件 P28 100% 复用零修改。若需追加 Proto 字段号 → 升级 P26 Spec → P26 Rev 批准再改（P28 不自行改 Proto）。
10. ❌ **不做 Fallback Multiplier 自动扩展（交集不够自动加 FT K）**：MVP v1.0 写死 FALLBACK_MULTIPLIER=3，若 FT∩ORM ∩ 的大小 < K → 返回短 List（不自动 retry 改 K×N）。避免行为不可预测。

## Background & Context
- 前序阶段硬约束 VERBATIM：
  - **P21 永久红线（USER-HC-0 TermDictStore.toUid(text) 永远无 level 参数；DIST_BUCKET d×{L,R} = bucketAccessCounter 只有 P21 topKNeighbors 能 ++；TOPK bucket sum=0 全阶段保持）**。
  - **P24 永久红线（UNIT/DOC 路由公式一字不碰；ShardTransport 5 原方法签名锁死；三键排序 finalScore↓docId↑shardId↑ 字面值全等）**。
  - **P25 永久红线（ZeroLeak3=3；NG-3 fulltext 根包 0 netty.grpc.p2p.client；ScoredDocHitWithExtras 组合非继承 final）**。
  - **P26 永久红线（7 Handler + Proto 6 msg + Converter 36 map 字段 1:1；Facade ≥ 98% 公开方法保留）**。
  - **P27 永久红线（GrpcShardTransport TCP 模式；FulltextShardTcpServer singletonCreated 挂载 ProtoCodecDuplexHandler addBefore BusinessProseccor（双 s）；concurrentScatter 分支最小侵入 ≤ 50 行）**。
  - **SP3.1 SOP 三步 Gate 铁律**：Gate A（spec/tasks VERBATIM）→ Implement T1~T6 串行 → Gate B 2×(P22~P28) 两连 → Gate C 9Y + 洁癖 5 份同步。严格串行零跳步。
- 现有基础设施 VERBATIM（已 100% 代码级实锤，不是假设）：
  1. **DsTableAdapter 主键与全文 docId 的天然 1:1（核心 Join Key）**：
     - `DsTableAdapter.getId()` / `setId(long id)`（`DsTableAdapter.java:99-L104`）→ 全局 long 型主键 = DsIdLayout 编码 rowId（serverId | partitionId | counter）。
     - `DsFulltextClusterClient.putDocCluster(long docId, String text)`（`DsFulltextClusterClient.java:L75-L77`）→ 全文 docId 也是 long。
     - 合查 Join Key 语义：**业务写入时必须显式保证 putDocCluster(docId=entity.getId(), text=entity.name + ... ) 一致**，合查按 docId = entity.getId() 等值 Join。
  2. **ORM QueryWrapper + GenericManager 过滤候选 id 基础设施（P17 已验证）**：
     - `QueryWrapper<T>` 支持 eq/ne/gt/ge/lt/le/in/like/between/or 等（P17 已用）。
     - `GenericManager.candidateIdsForQuery(QueryWrapper<T> wrapper)`（`GenericManager.java:L2796` private）→ 返回 `Iterable<Long>`，是复合索引 seek + ids 集合 seek 的结果。
     - P28 单调新增 public `GenericManager.listIds(QueryWrapper)`（包装 candidateIdsForQuery 转 ArrayList）。
  3. **Fulltext Cluster 100% 完整 TCP 模式 8 方法（P27 Gate C 已实锤 156 TR 两连稳定）**：DsFulltextClusterClient.searchClusterWithExtras() 返回 `ScoredDocHitWithExtras`（core 含 docId/finalScore/sortScore/rrfScore/phraseBoostScore/matchedTerms/vectorScore/shardId + matchedSpans/snippets/topFacets）。
  4. **Test Entity 原型 P17.AbcEntity（零新增，直接复用）**：`AbcEntity extends DsTableAdapter`（@DsCompositeIndex(name=idx_abc, columns=a,b,c) + 字段 name/a(long)/b(String)/c(int)）→ 合查场景：a 结构化 WHERE + FT 搜 name 字段文本，完美契合无需再新建 Test Entity。

## Functional Requirements

### FR-1：GenericManager 单调新增 public `listIds` 门面（Facade 单调不减）
- **FR-1.1 签名**：在 `GenericManager<T>` 中新增 public `List<Long> listIds(QueryWrapper<T> wrapper)`（返回 ArrayList<Long>，顺序与 candidateIdsForQuery 迭代顺序一致）。
- **FR-1.2 体**：`return toList(candidateIdsForQuery(wrapper))`，其中 toList = Iterator → ArrayList 单调包装。
- **FR-1.3 约束**：零删改任何已有 public 方法；listIds 不抛检查异常（RuntimeException 直通）。
- **FR-1.4 约束**：当 wrapper 为 null 时，等价空条件 → 返回**全表所有行 id**（与 P17 findList(null) 行为 1:1 对齐）。

### FR-2：`HybridHit<T>` 组合类（fulltext.cluster 子包，非继承 final）
- **FR-2.1 位置**：`com.q3lives.ds.database.fulltext.cluster.HybridHit<T extends DsTableAdapter>`（static final 类）。
- **FR-2.2 字段**（全 public final，无 setters，线程安全）：
  - `public final T entity`（ORM 实体，若合查交集命中但 ORM getTable 为 null → 抛 IllegalStateException，不允许 null entity，违反强一致性）。
  - `public final ScoredDocHitWithExtras ftx`（全文结果，若命中则非 null，MVP 永远非 null）。
  - `public final double finalHybridScore`（MVP v1.0 = ftx.core.finalScore，纯 FT 分，不加权）。
  - `public final long docId`（冗余字段，= ftx.core.docId，也 = entity.getId()，便于调试；三值必须全等，若不等抛 IllegalStateException，T3/T4 fingerprint 必测）。
- **FR-2.3 构造**：唯一 public 构造 `HybridHit(T e, ScoredDocHitWithExtras f)`，构造内校验 `Objects.requireNonNull(e/f)` + `e.getId() == f.core.docId` 三值全等，若任何校验失败抛 IAE。

### FR-3：`DsHybridQueryClient<T extends DsTableAdapter>` 组合门面（构造 + 核心 API）
- **FR-3.1 位置**：`com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient<T>`（non-final，允许单元测试 mock）。
- **FR-3.2 依赖注入构造（非反射，可测）**：
  ```java
  public DsHybridQueryClient(
      Class<T> entityClass,
      BiFunction<QueryWrapper<T>, Integer, List<Long>> ormIdsProvider,  // (w, maxHint) -> ids
      Function<Collection<Long>, Map<Long, T>> ormBatchGetter,          // ids -> {id:entity}
      DsFulltextClusterClient ftClient,                                 // 复用 P27
      int fallbackMultiplier                                            // MVP 默认 3
  )
  ```
- **FR-3.3 便捷构造（MVP 场景：同 JVM Local ORM + Local/Inproc FT Cluster）**：
  ```java
  public static <E extends DsTableAdapter> DsHybridQueryClient<E> ofLocal(
      Class<E> entityClass, GenericManager<E> gm, DsFulltextClusterClient ftClient
  )
  ```
  - 体：ormIdsProvider = (w,hint) → gm.listIds(w)（忽略 hint）；ormBatchGetter = ids → 遍历 ids `gm.getById(id)`（或 findList 一次批量 TODO：MVP v1.0 用循环 getById，批量 findListByIds 留 P29）。
- **FR-3.4 核心 API 签名**：
  ```java
  public List<HybridHit<T>> searchHybrid(
      QueryWrapper<T> ormCondition,
      String fulltextQuery,
      Map<Long,String> rawDocTextsByDocId, // 传 null 允许？不允许 MVP 必须传（P25 Extras 需要），若用户传 null → 抛 IAE
      int K,
      SearchOptions opt
  ) throws IOException
  ```
- **FR-3.5 步骤体写死（MVP v1.0 严格 5 步，顺序不允许改）**：
  1. **Step-ORM：取 ORM 过滤集合**：`ormIds = ormIdsProvider.apply(ormCondition, null)`，转 `HashSet<Long> ormIdSet = new HashSet<>(ormIds)`（避免 O(N) contains）。
  2. **Step-FT：FT 扩大候选 = K × fallbackMultiplier**：`ftCandidateK = Math.max(1, K) * Math.max(1, this.fallbackMultiplier)`。
  3. **Step-FT：取 FT 候选**：`ftAll = ftClient.searchClusterWithExtras(fulltextQuery, rawDocTextsByDocId, ftCandidateK, opt)`。
  4. **Step-MERGE：交集过滤 + 排序 + 截断**：
     - 遍历 ftAll，filter：`ormIdSet.contains(hit.core.docId)` → 保留；
     - 保留顺序 = ftAll 顺序（即 Fulltext 三键排序 finalScore↓ docId↑ shardId↑，合查继承该顺序，不再二次排序）；
     - limit = 前 K 个 → filtered = ftAll.stream().filter(...).limit(K).collect(toList())。
  5. **Step-ORM：批量填实体 + 构造 HybridHit**：
     - 取 filtered 所有 docId 集合 `ftHitIds = filtered.map(h->h.core.docId)`；
     - 调 `ormBatchGetter.apply(ftHitIds)` 得 `Map<Long,T> entityMap`；
     - 遍历 filtered，按顺序 new HybridHit(entityMap.get(docId), hit) → 构造内必验三值全等 + 非 null；
     - 返回结果 List（顺序 = filtered 顺序）。
- **FR-3.6 约束**：若 K ≤ 0 → IAE；若 fulltextQuery 为 null/blank → IAE；若 rawDocTextsByDocId 为 null → IAE（MVP v1.0 Extras 必须，P25 实装必须 rawDocTextsByDocId）；若 opt 为 null → 用 `SearchOptions.defaults()`（P25 定义）。

### FR-4：单 JVM inproc 模式端到端验证（T3 使用）
- **FR-4.1 数据集**：复用 P27 T3 30 doc 语料，同步写入 AbcEntity（N=30，a 值分 3 组：a=100 的 10 条 / a=200 的 10 条 / a=300 的 10 条；b 字段再做二次拆分；name 字段内容 = FT doc text）；写入时 docId = entity.getId() 强制一致。
- **FR-4.2 Query 组合**：
  - Q-ALL：`ormCond = null`（全表）+ FT query = CHERRY_COMMON + K=20 → 结果与 P27 T3 纯 FT searchClusterWithExtras 前 20 结果 1:1（entity 非 null + docId 三值全等）。
  - Q-FILTER：`ormCond = eq("a", 100)`（仅 10 条 ORM 候选）+ FT query = CHERRY_COMMON + K=20 → 合查结果 List size ∈ [0,10]（因交集最大 10）且命中的 hit.entity.a 必须 == 100（ORM 过滤生效）。
  - Q-EMPTY：`ormCond = eq("a", 99999)`（0 条 ORM 候选）+ FT query = CHERRY_COMMON → 返回空 List。
- **FR-4.3 指纹**：Q-ALL 合查结果 HybridHitFingerprint × 20 与纯 FT searchClusterWithExtras × 20 手动 new HybridHit(GM.getById(id), ftx) 结果 Set.size=1。

### FR-5：跨 Process 真实 TCP FT + 同 JVM ORM 合查（T4 使用）
- **FR-5.1 跨 Process FT**：复用 P27 T4 ProcessBuilder 起子 JVM FulltextShardTcpServer（端口 p，shardCount=N），父 JVM DsFulltextClusterClient 构造时传 `{0:p}` endpointMap = TCP 真实模式。
- **FR-5.2 ORM Local 模式**：父 JVM 同 JVM 用 GenericManager<AbcEntity> Local（不起 DsDatabaseServer 远程，避免 MVP 无限复杂）。
- **FR-5.3 指纹**：合查 vs 单 JVM inproc 模式同 Query 结果，Set.size()=1（TCP FT 结果 + Local ORM 结果 字节级全等）。
- **FR-5.4 约束**：T4 必须复用 P27 T4 子 JVM drain stdout 线程（防止 pipe buffer 64KB 阻塞），零重写，仅调用其方法。

### FR-6：单调可扩展（P29 HybridScore v2 预留钩子）
- **FR-6.1 钩子**：`DsHybridQueryClient` 提供 protected 构造 + 可 override 的 `double scoreHybrid(T entity, ScoredDocHitWithExtras ftx)`（默认体 = ftx.core.finalScore，与 MVP 一致），P29 子类可重写加权混合分。
- **FR-6.2 约束**：P28 不实际用该钩子（仅空预留），任何 P28 单元测试不准重写 scoreHybrid。

## Non-Functional Requirements

### NFR-1：正确性 / 指纹全等（硬红线 0 容差）
- T3（单 JVM）Q-ALL：合查 × 纯手动 HybridHit(GM.getById + FT) 指纹 Set.size=1，100% match。
- T4（跨 Process TCP FT）Q-ALL：合查 vs T3 inproc 同 query 指纹 Set.size=1。

### NFR-2：TOPK bucket 0 污染
T5 TR5.1：Reset bucket sum → 50 次合查 Q-ALL/Q-FILTER/Q-EMPTY 轮询 → 结束后双端 sum = 0。

### NFR-3：ZeroLeak3
T5 TR5.2 Gatekeeper：fulltext 根包 3 个独立类 ZeroLeak tail(contains '.') = 3（HybridHit / DsHybridQueryClient 在 .cluster 子包被 Gatekeeper 过滤掉，根包仍 3）。

### NFR-4：NG-3 9 CNFE + 根包 0 netty.grpc.p2p.client
T5 TR5.3：反射 collectTypes FulltextStore/TermDictStore/FulltextSegmenter/ShardRouter/ShardTransport/LocalShardTransport 6 类常量池 0 io.grpc / io.netty / javax.net.p2p.client。Class.forName 9 CNFE 全抛。

### NFR-5：Facade ≥ 98% 单调不减
T5 TR5.4：P21~P27 累计 public 方法 100% 保留（P28 仅增不删，≥98% trivially 100%）。

### NFR-6：合查性能相对纯 Fulltext 的额外开销（NFR-6）
T6 单 JVM inproc：纯 FT searchClusterWithExtras（30 doc, query=CHERRY_COMMON, K=20）平均耗时 T_ft；合查 searchHybrid（同参数 ormCond=null 全表）平均耗时 T_hybrid；要求 `T_hybrid / T_ft ≤ 2.5×`（ORM 两次 roundtrip 允许 overhead，MVP 不追求极致性能）。

### NFR-7：代码 Rubric
T6 Code Rubric：① 公共方法都有 Javadoc（≥ 90%）；② 无重复代码块 ≥ 20 行（cpd score ≥ 0.9）；③ 无 System.out / e.printStackTrace 裸打印（logger 或 throw，≥ 98% 合规）；④ Facade public 方法 null 校验全覆盖（100%）。

### NFR-8：单阶段 TR 30 / Gate B 两连稳定 0F0E
T1 4 + T2 4 + T3 6 + T4 4 + T5 9 + T6 5 = 32 → MVP v1.0 预计 25 TR；Surefire 单阶段 0F0E；Gate B 2×(P22~P28) 2 两连稳定 0F0E（跑满 P28 单 TR 数）。

## SP3.1 自检 9Y Gate C 待验项（Gate C 实锤后填 Y/N，目前 REV1 评审先打 ⏳ Pending）

| 编号 | 检查项（Y=通过 ⏳=待 N=不通过，必须 Y×9 才能 Gate C） | 目前状态 | P28 实锤证据（Gate C 完成时填） |
|---|---|---|---|
| Y1 | P28 单阶段所有 TR 全绿 0F0E，Surefire 2× Gate B 两连稳定 | ⏳ Pending | T5.1 Surefire 报告 Tests run:____ 0F0E _____s |
| Y2 | ZeroLeak3=3：com.q3lives.ds.database.fulltext 根包 top-level 独立类 {TermDictStore, FulltextStore, FulltextSegmenter} = 3，未新增独立类 | ⏳ Pending | T5.2 Gatekeeper 扫描根包 → 3，HybridHit/DsHybridQueryClient 在 .cluster 子包被过滤 |
| Y3 | TOPK bucketAccessCounter：合查双端（合查 Client + FT search 内部 + ORM candidateIdsForQuery）50 次合查后双端 bucket sum=0 | ⏳ Pending | T5.1 Reset 后 50 次合查 → sum=0（FT 侧 / ORM 侧分别测双组） |
| Y4 | NG-3 根包 0 netty.grpc.p2p.client：反射 collectTypes 6 类常量池 0 行命中 9 CNFE 全抛 | ⏳ Pending | T5.3 constantPoolAnalyzer → 0；CNFE 9/9 |
| Y5 | Facade 单调不删 ≥ 98%：P21~P27 所有 public 方法名 + 字段名 + enum 顺序全部保留，P28 仅 GenericManager 新增 listIds + fulltext.cluster 新增 HybridHit/DsHybridQueryClient | ⏳ Pending | T5.4 反射扫 public 方法名 baseline 全集 vs 新 jar 全集 100% 相等 |
| Y6 | ShardTransport 原 5 方法签名一字不改，UNIT/DOC 路由公式一字不改，三键排序字面值全等 | ⏳ Pending | T5.4 反射 getMethods 5 名签名一致；src grep 路由公式与 P24 字节级 diff |
| Y7 | 合查指纹 100% 全等：T3/Q-ALL vs 手动 HybridHit + T4/TCP vs T3/inproc 共 2 组 Set.size() = 1 | ⏳ Pending | T3/T4 fingerprint md5 字节比较 Set.size=1 × 2 组 |
| Y8 | NFR-6 开销 ≤ 2.5×；Rubric 4 项全部 ≥ 90% 达标 | ⏳ Pending | T6 NFR 报告 ratio ≤ 2.5x；Rubric 分数 |
| Y9 | 全源码 grep `com.q3lives.ds.database.fulltext`（仅 fulltext 根目录 3 类源码）0 行命中 `io.grpc` / `io.netty` / `javax.net.p2p.client` 字面量；Gatekeeper 9 CNFE 0 新增 | ⏳ Pending | T5.9 源码 grep 0 命中；9 CNFE 9/9 全抛 |

---

## 签批区（SP3.1 Gate A/B/C 签名必须 VERBATIM 用户指令原文，不许自创）

| 阶段 | 签批原文（VERBATIM 用户消息或工具结果） | 签字人 / 日期 |
|---|---|---|
| Gate A（Spec 批准） | 待用户下一条「Continue/继续」= VERBATIM 批准 P28 REV1 Spec+Tasks | Implement Agent ____-__-__ |
| Implement（T1~T6 串行） | 待 Surefire 报告 | Implement Agent ____-__-__ |
| Gate B Run1（组合 P22~P28 两连 1/2） | 待 Surefire 组合报告 Tests run:____ 0F0E | Implement Agent ____-__-__ |
| Gate B Run2（组合两连 2/2） | 待 Surefire 组合报告 Tests run:____ 0F0E | Implement Agent ____-__-__ |
| Gate C（9Y×9 + 洁癖 5/5 + 归档） | 待自检 + Gatekeeper + 洁癖文档同步 | Implement Agent ____-__-__ CLOSED |
