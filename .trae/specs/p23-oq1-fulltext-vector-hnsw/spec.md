# P23 OQ1 全文向量 ANN（HNSW 零依赖 + RRF 三路融合）—— spec.md（**Gate C 验收通过 REV1，2026-10-04**）

**Gate 状态（REV1，2026-10-04 Gate C 闭环）**：
- [x] **Gate A（用户 2026-10-04 VERBATIM approve Scope + Spec/Tasks 全文）**
- [x] **Gate B**：Surefire 独立 2×50 TR 0F0E（Run1 05:02 Tests run:50 0F0E；Run2 04:43 Tests run:50 0F0E，P22 25+P23 25 合计）
- [x] **Gate C**：25 TR 0F0E + 洁癖 5 份文档同步（AGENTS/Roadmap/ProjectMemory/spec/tasks）+ 自检 9 项全 Y

**Gate C 9Y 自检清单（2026-10-04 全 Y）**：
1. [Y] USER-HC-0：TermDictStore.toUid(text,createIfAbsent) 永远无 level 参数，unit_id 纯文本 DsString.getIndexId
2. [Y] P21 TOPK 铁律：bucketAccessCounter[1025] 仅 P21 topKNeighbors 可++，P23 searchWithVector/search 路径严格 0 污染（T5 TR5.1 零 ++ 实锤）
3. [Y] ZeroLeak3：fulltext 包独立类仍严格 3 主类 {TermDictStore, FulltextStore, FulltextSegmenter}，HnswStore/WeightStore/Similarity/HnswMetaForTest 全 static 内嵌 FulltextStore
4. [Y] NG-3 零实依赖单调：5→9 CNF 全 ClassNotFoundException（Jieba/IK/HanLP/spacy/BM25Similarity + HnswGraph/HnswIndex/Nd4j/EuclideanDistance）
5. [Y] Facade 非递减 ≥36：TermDict public≥6 + FulltextStore public≥30（P22 基线 22 + P23 新增 8=getTermVectorForTest/indexTermVector/cosineSimForTest/l2DistForTest/knnSearchForTest/getHnswMetaForTest/searchWithVectorForTest + rrfRankToScore/computeSortScore/searchByUidsForTest...），P21/P22 基线方法∈P23 集合（子集不删）
6. [Y] putDoc 3× 幂等：tf/docLen/nTerms/termVector 4 项逐值全等（T4 TR4.1），clearDoc 不删 termStat.payload(=vector) 不删 idf（T4 TR4.3/TR4.4）
7. [Y] searchWithVector 三路 RRF 融合正确性：R1/R2/R3 rank 手算 1e-12 全等 D>A>B>C（T3 TR3.1）；vectorRecall=false 时与 P22.search finalScore 逐 1e-9 全等（T3 TR3.3）
8. [Y] NFR 轻量 ≤3000ms P95：T6 TR6.2 Run1=1954ms / Run2=1816ms / Ingest 30×20 768D 1812~2166ms ≤40s，Quality Rubric=5 满分，Extensibility Rubric=4/5
9. [Y] 串行依赖严格遵守：P23 实现完全建立在 P22 Gate C 通过基线上，无并行，P24/P25 未启动（SP3.1 铁律）

---

## Overview
- **Summary**：对 P22 全文打分做**零破坏 Facade 扩展**（仍 ZeroLeak3=3 独立主类，新逻辑全 static 内嵌 FulltextStore）。新增 HNSW（Hierarchical Navigable Small World）分层图 ANN（纯 Java 手写零外部依赖，M=16 efConstruction=128 efSearch=128 maxLevel=16），每个 term_uid 关联 float[] dense vector，复用 P22 WeightTermStatRow.payload 4B len+变长 byte[] 存 dim+float[] 字节。`searchWithVector(query, qVec, K, options)` 三路召回 R1=byTerm IDX2 + R2=neighborsWithinD + R3=HNSW vector knn，RRF(k=60) 三路 1/(60+rank) 加总，4 段 α/β/γ/δ min-max 归一加权，稳定排序（finalScore 降序，同分 docId 升序）。`putDoc/clearDoc` 幂等：`clearDoc` 不删 termStat.payload（vector 跨 doc 共享，下次 indexTermVector 覆盖），仅删 docStat+termFreq。
- **Purpose**：P22 只有词法搜索（byTerm/neighbors/BM25），无法处理同义词/上位词/语义相似查询（"CPU" 搜不到"处理器"）。P23 引入稠密向量 ANN 混合召回，立刻可做"关键词+语义"双路搜索产品能力。
- **Target Users**：上层语义搜索服务（P22 search 返回词法排序 topK → P23 searchWithVector 追加 RRF 三路融合）；P24 ShardRouter 跨节点 scatter-gather（每 shard HNSW 本地独立，P24 全局合并 rank）；P25 Snippet/Facet 不关心 vector 只复用 termStat payload 存储格式。

## Goals
1. **G1 HNSW ANN 写入正确性**：同一 term indexTermVector(uid, vec) 后，knnSearch(uid, K=1, ef) 返回必含自己且向量完全全等 byte-for-byte；30 term HNSW 构建后 top1 余弦最相似的 3 条邻居顺序与 brute-force 精确全扫排序完全一致（T6 rubric score≥4）。
2. **G2 三路 RRF 融合**：searchWithVector K=10 返回 ScoredDocHit 集合必包含 R1/R2/R3 各至少 1 条三路召回交集外的独有命中；RRF 三路独立 rank 手算 1e-12 精确断言通过（T3 TR3.1）。
3. **G3 ZeroLeak3 + NG-3 零破坏**：P22 Facade≥29 基线 18 公共方法名全 ∈ P23；ZeroLeak fulltext 独立类仍 3；5 类 CNF 全 CNF；P22 T5 Gatekeeper TR5.1 topK 铁律 bucketAccessCounter[1]=2/[2]=1 不被 P23 HNSW/searchWithVector 污染 0 ++。
4. **G4 存储层零冲突**：HnswStore 独立目录 `root/weight_store/hnsw/`，新增 2 DsHashMap（hnsw_meta / hnsw_nodes），绝不 touch P22 已有的 term_stat.dshash / term_freq_in_doc.dshash / doc_stat.dshash / weight_bucket_store 任何文件。termStat.payload 仅 4B dim + 4B×dim float[]，vector 与 idf 存储完全独立（putTermStat 时保留原 idf 不覆盖，只写 payload）。

## Non-Goals
- **NG-1 不做 IVF-PQ / FAISS / jfaiss / any JNI / any native lib**：NG-3 红线禁止，HNSW 纯 Java 手写够用。
- **NG-2 不做 query 端自动 embedding 生成**：embedding 生成是 P23 上游离线模型/外部 HTTP embedding service 责任；本层只接受 `indexTermVector(uid, float[])` 和 `searchWithVector(q, float[], K, o)` 传入已算好的 qVec。
- **NG-3 不做向量压缩（Product Quantization）**：MVP 存原始 float32，dim≤768 常见 BERT 输出单 term ≈3KB payload，DsFixedBucketStore 单 bucket 16MB 上限容得（T1 验证 dim=768 读写全等）。
- **NG-4 不做 HNSW 动态删除 rebuild**：term 的 vector 删除不在 MVP；clearDoc 只清理文档相关结构，term vector 保留（重写覆盖 indexTermVector 幂等）。
- **NG-5 不修改 ScoredDocHit P22 原有 10 字段语义**：仅加 vectorScore 11th 字段 + 构造函数 11 参重载（原 10 参仍可用兼容 P22）。

---

## Functional Requirements (FR)
### FR-1 HNSW 写入
- **FR-1.1 indexTermVector(uid, vector)**：uid≤0 抛 IAE("uid invalid")；vector==null 或 vector.length==0 或 vector.length>4096 抛 IAE("vector dim illegal")。幂等：同 uid 连续 3× indexTermVector 同一 vector，getTermVectorForTest 返回 float[] 元素 1e-9 全等，HNSW 邻居数不变（不会重复插入导致邻居膨胀）。
- **FR-1.2 HNSW meta**：M=16（每层固定邻居数，不可配置，MVP 钉死），efConstruction=128（构建阶段搜索候选集数，钉死），maxLevel=16（概率 level=min(floor(-ln(rand())×ln(1/M)), 16)），entryPointUid（全局入口）单调更新，HnswNode count≤termStat 已 index vector 的 uid 数。
- **FR-1.3 HNSW 节点存储**：每个有 vector 的 uid 对应 HnswNode（内嵌类 ZeroLeak）：`{byte levelMask; short[][] neighborUidsPerLevel}`（levelMask bit=1 表示该 level 有邻居数组，短数组 short uid 节省内存；uid>65535 用 long 存但 short 覆盖 P21 常见 <65535 场景，溢出时自动升级 long[]——节点 bucket 变长，DsFixedBucketStore 自适应）。
- **FR-1.4 putTermStat 保留 idf**：`indexTermVector(uid, vec)` 调 weightStore.putTermStat(uid, new WeightTermStatRow(old.idf, newPayload4Bdim+floatBytes))，**绝不改 idf**；原 recomputeIdfForTerms 调 putTermStat 时也**保留原 payload**（L1203-L1205 已实装），双向零破坏。

### FR-2 HNSW 查询
- **FR-2.1 knnSearch(uid, K, efSearch)**：uid 没 vector 抛 ISE("uid no vector")；K≤0 抛 IAE；efSearch<max(M,K) 自动 upcast 到 max(M,K)。返回 `List<Neighbor>`（复用 P21 Neighbor 内嵌类，Neighbor.uid 是被召回 term_uid，Neighbor.distance=cosine 转成 int 距离（1-sim 归一到 0..1_000_000）。顺序：距离从小到大（cosine 最相似排前）。
- **FR-2.2 相似度函数**：cosine（default）：`Σa[i]b[i] / (||a||·||b||)`，||a||=0 或 ||b||=0 返 0；L2：`sqrt(Σ(a[i]-b[i])²)`。两个 static HC probe 精确断言 1e-6。
- **FR-2.3 top1 brute-force 对齐 rubric**：T6 30 term dim=32 随机向量，HNSW knnSearch(topTerm, 10, ef=256) 的 top10 列表与 brute-force 全扫 top10 的 intersection 数 ≥7（recall@10≥70%，rubric 5 分制 anchors 7=3, 8=4, ≥9=5）。

### FR-3 三路 RRF 融合主 API searchWithVector
- **FR-3.1 参数**：`List<ScoredDocHit> searchWithVector(String query, float[] qVec, int K, SearchOptions options)`。K≤0 返空 List；query=null/"" 且 qVec==null 返空（双 miss）；qVec==null 时退化为 P22.search（只两路 R1/R2，vector 路径跳过）。
- **FR-3.2 三路召回定义**：
  - **R1 byTerm**：P22 R1 原样（IDX2 selectByUnit(qUids)，dedup by (doc,p,lv)）
  - **R2 neighbors**：P22 R2 原样（neighborRecallD>0 才开，options.neighborRecallD）
  - **R3 vector**：对 qUids 每个 uid knnSearch(uid, vectorTopK=options.vectorTopK 默认 50, ef=options.vectorEfSearch 默认 128) → 收集 (term_uid, cosine_sim) 邻居 → 对这些 vUids 再走 IDX2 selectByUnit(vUids) 得到 doc 集合（dedup by (doc,p,lv)），与 R1/R2 doc 集合做 union
- **FR-3.3 RRF rank 1-based 三路 Σ**：
  - R1 rank 源：R1 sortScore（P22 BM25/TFIDF）降序 docId 升序
  - R2 rank 源：R2 doc 集按 "neighbor hit count" 降序 docId 升序
  - R3 rank 源：R3 doc 集按 "Σ(1..qUids × ΣtopNeighbors cosine_sim × termFreq)" 降序 docId 升序
  - RRF 合计 = Σr∈{R1,R2,R3} Σdoc∈r_rank 1/(rrfK+rank)，rrfK 复用 options.rrfK=60（P22 默认）
- **FR-3.4 4 段 min-max 归一 + 加权**：
  - sortScore_norm（P22 computeSortScore 返回）、rrf_norm、phraseBoost_norm 复用 P22，新增 **vectorScore_norm**：R3 向量贡献分（min=max→0）
  - finalScore = α·sortScore_norm + β·rrf_norm + γ·phraseBoost_norm + δ·vectorScore_norm
  - **默认权重**：α=0.5 β=0.25 γ=0.1 **δ=0.15**（P22 α=0.6 β=0.3 γ=0.1 若 options.vectorRecall=false 时保留 P22 默认不破坏；vectorRecall=true 才用 P23 默认四权重）
- **FR-3.5 稳定排序**：finalScore 降序 + 同分 docId 升序（Comparator.thenComparingLong）；T6 TR6.3 10× search 指纹 Set.size=1 无随机性。

### FR-4 清理幂等
- **FR-4.1 clearDoc 不删 termStat 不删 vector**：同 P22 AC-4；`clearDoc(docId)` 仍只 removeTf + removeDocStat，termStat.payload（vector 4Bdim+bytes）原封不动保留。
- **FR-4.2 putDoc 3× 幂等**：3 次 putDoc(docId, sameText)，HNSW meta nodeCount / entryPointUid / M / maxLevel 字段值逐 byte 全等（T4 TR4.1 断言）。

### FR-5 SearchOptions 新增字段（P22 内嵌类扩展，向后兼容）
```
// P22 原有 12 字段保留默认值不变；P23 追加：
public Similarity vectorSim = Similarity.COSINE;  // COSINE / L2（内嵌 enum ZeroLeak）
public boolean vectorRecall = true;                // false = 关 R3，等价退化为 P22.search
public int vectorEfSearch = 128;
public int vectorTopK = 50;
public double delta = 0.15;                        // 向量段权重
```
- P22 已有 SearchOptions.defaults() 不变：12 字段老默认值保留，vectorRecall=true 新默认（用户可手动设 false 跑纯 P22 行为，T5 TR5.4 Facade 子集不删）。

### FR-6 HC Probes（public 方法，T1~T3 不依赖 searchWithVector 主 API）
```java
public static double cosineSimForTest(float[] a, float[] b)       // 1e-6 精确
public static double l2DistForTest(float[] a, float[] b)          // 1e-6 精确
public float[] getTermVectorForTest(long uid)                     // miss 返 null
public HnswMetaForTest getHnswMetaForTest()                       // {M, efConstruction, maxLevel, entryPointUid, nodeCount}
public List<Neighbor> knnSearch(long uid, int K, int efSearch)    // 纯 ANN 召回 probe
```
- HnswMetaForTest 内嵌类 ZeroLeak。

---

## Non-Functional Requirements (NFR)
- **NFR-1 ZeroLeak3 永久红线**：com.q3lives.ds.database.fulltext 独立类仍严格 3 主类 {TermDictStore, FulltextStore, FulltextSegmenter}。P23 新增：WeightTermStatRow/SearchOptions/ScoredDocHit 已内嵌，新增 HnswNode / HnswStore（内嵌 WeightStore 内或 FulltextStore 内）/ Similarity enum / HnswMetaForTest 全 static nested FulltextStore，绝不新增独立 .java。
- **NFR-2 NG-3 永久红线**：pom 永不引入任何向量库/ANN 包（lucene misc、faiss、jfaiss、hnswlib、fastutil 的 ObjectHeapPriorityQueue、commons-math、ejml、nd4j）；T5 TR5.3 5 类 CNF + 新增 4 类（`org.apache.lucene.util.HnswGraph` / `com.github.jelmerk.HnswIndex` / `org.nd4j.linalg.factory.Nd4j` / `org.apache.commons.math3.ml.distance.EuclideanDistance`）共 9 类全 CNF。
- **NFR-3 Facade 非递减 ≥29（P22 基线）**：P23 新增 7 个 public（cosineSimForTest/l2DistForTest/getTermVectorForTest/getHnswMetaForTest/knnSearch + searchWithVector + Similarity enum synthetic），合计 ≥36。P22 基线 18 公共方法名全 ∈ P23 集合（子集）。
- **NFR-4 TOPK bucketAccessCounter 不被 P23 污染**：searchWithVector / knnSearch / indexTermVector 三个入口绝不 touch `FulltextStore.bucketAccessCounter[d] ++`，T5 TR5.1 验证调用 P23 searchWithVector 前后 counter 全 0，topK(C,3) 调用后 counter[1]=2/[2]=1 仍正确。
- **NFR-5 性能 T6 TR6.2 轻量配置 P95 ≤3000ms**：dim=768 30 term 构建 + 10 次 searchWithVector（轻量配置：vectorRecall=true neighborRecallD=0 phraseBoost=false charLevelSearch=false），单 query P95 ≤3000ms（HNSW ANN 路径相对 P22 纯词法 +50% 内可接受；vectorRecall=false 回 P22 ≤2000ms）。
- **NFR-6 稳定排序**：轻量配置 10 次 searchWithVector 的 ScoredDocHit docId 指纹 Set.size=1（无随机性 T6 TR6.3）。
- **NFR-7 存储独立**：HnswStore 目录 root/weight_store/hnsw/ 下新建 2 张 DsHashMap 不碰 P22 任何已有表。

---

## Constraints / Dependencies / Assumptions
- **C-1 P22 P21 Zero 破坏硬约束（永久）**：USER-HC-0（unit_id 纯文本，无 level 编码）、DIST_BUCKET 2048 上限、TOPK 铁律、ZeroLeak3、NG-3、Facade≥29 全不得违反；P22 T5 Gatekeeper 4 TR 全 Pass 后才能谈 P23 通过。
- **C-2 DsFixedBucketStore 4B length header 必守（P22 L152-L156 已实装）**：HNSW 节点 bucket 写入必须先写 totalLen header 4B，否则 get 读区间抛 IAE。
- **C-3 FarmHash64.mix4 必须复用（不引入新 hash）**：HNSW key 必须复用 `FarmHash64.mix4(uid, 0xCAFEBABE, 0xBEEFCAFE, 0x5AA5)` 四元 mix，不得新写 hash 函数（NG-3 零外部依赖，内部 hash 也 DRY）。
- **C-4 term vector 与 term 文本一对一**：同一 uid（同文本）只有一个 vector；异 uid（异文本）各自独立 vector，不共享。user 保证 indexTermVector 时 uid=toUid(text, true)（probe 不校验，但违反后召回不一致不算 bug）。
- **C-5 HNSW efSearch ≥ max(M,K) 自动 upcast**：避免 K>M 但 ef 太小召回为空的坑（常见 HNSW 新手错误）。

---

## Open Questions（Default 决定；如需调整 Gate A 告知；默认值立即采用）
| OQ | A Default（MVP） | B Alternative | 选 A 原因 |
|----|------------------|---------------|-----------|
| OQ1 ANN 算法 | **HNSW（skip list 多层图，M=16 maxLevel=16）** | IVF-PQ（倒排 + 残差量化） | HNSW 结构简单手写成本低，recall 高；IVF 要训练中心向量；MVP 选最简实现 |
| OQ2 相似度 | **COSINE 默认 + L2 备选** | Dot-product only | COSINE 对 embedding 标准化后最通用，L2 兜底 |
| OQ3 qVec 传入方式 | **searchWithVector(q, qVec, K, o) 显式传** | SearchOptions.qVec 字段 | 显式语义清楚；避免 options 里塞大 float[] 混淆；双 null 时退 P22 |
| OQ4 RRF 三路 / 四段归一 | **RRF(k=60) 三路 Σ；α=0.5 β=0.25 γ=0.1 δ=0.15 默认** | 独立 vector topK 后 concat | RRF 对 scale 不敏感，鲁棒；四权重相对比例合理（词法>召回融合>短语>向量） |
| OQ5 HNSW 删除 / 增量 | **MVP 不做删除；同 uid 覆盖 indexTermVector 幂等** | deleteTermVector(uid) + rebuild partial | 复杂性高，MVP 先写对再优化删除 |
| OQ6 dim 上限 | **4096（dim≤4096）** | 768（只支持 BERT base） | 4096 覆盖 GPT-3/Llama2 embedding 常见 dim，性能与通用性 tradeoff 选宽松 |

---

## Acceptance Criteria（AC：rule / rubric 两类，每 AC 至少映射到 1 个 T 的 1 条 TR）
| ID | Type | Verbatim（必须 100% 实现；否则 AC fail） | T/ TR 映射 |
|----|------|------------------------------------------|------------|
| AC-1 | rule | P23 编译通过：364 源（+P23）0 错。 | T0/compile |
| AC-2 | rule | indexTermVector 幂等 3× 与 getTermVectorForTest float[] 1e-9 全等，dim=768 写入读取无越界。 | T1/ TR1.1, TR1.2 |
| AC-3 | rule | cosineSimForTest 对 a=[1,0,0]/b=[1,0,0]=1.0；a=[1,0]/b=[0,1]=0；a=[3,4]/b=[4,3]=24/25=0.96；L2 a=[1,2,3]/b=[4,6,8]=sqrt(9+16+25)=sqrt(50)≈7.0710678。1e-6 精确。 | T2/ TR2.1, TR2.2 |
| AC-4 | rule | HNSW top1 自一致性：index uid→vector 后 knn(uid, 1, ef=128) 返回必含 uid 自己，距离=0。 | T2/ TR2.3 |
| AC-5 | rule | 三路 RRF 手算精确：4 doc A/B/C/D，R1 rank B>A>D>C（rrfK=60 → B=1/61 A=1/62 D=1/63 C=1/64）；R2 rank A>D>C>B（A=1/61 D=1/62 C=1/63 B=1/64）；R3 rank D>B>A>C（D=1/61 B=1/62 A=1/63 C=1/64）。合计：A(1/62+1/61+1/63) vs B(1/61+1/64+1/62) vs D(1/63+1/62+1/61) vs C(1/64+1/63+1/64) → 排序 D>A>B>C（手算 1e-12）与 P23 RRF 排序全等。 | T3/ TR3.1 |
| AC-6 | rule | searchWithVector K≤0 / q=null+""/qVec=null 全部空 List 不 NPE。vectorRecall=false 退 P22 结果与 P22.search 100% 逐 docId/score 相等。 | T3/ TR3.4 |
| AC-7 | rule | clearDoc 不删 termStat.payload：indexTermVector(uid,v) → putDoc(D1) → clearDoc(D1) → getTermVectorForTest(uid) 返回 v 1e-9 全等；termStat.idf 原封不动。 | T4/ TR4.3, TR4.4 |
| AC-8 | rule | putDoc 3× 幂等：HnswMetaForTest.nodeCount逐字段全等；WeightStore termFreq/docLen/nTerms int 逐 int 全等（P22 T4 TR4.1 同条件）。 | T4/ TR4.1, TR4.2 |
| AC-9 | rule | ZeroLeak3 独立类=3；9 类 CNF（P22 5 + P23 4）全 CNF；Facade≥36；P21 bucketAccessCounter 不被 P23 污染。 | T5/ TR5.1, TR5.2, TR5.3, TR5.4 |
| AC-10 | rubric | Quality Score（0~5）：top3 doc 顺序对=2 分；top10 R3 recall 交集 brute-force ≥7=1 分；三路独有 doc 都出现在结果=1 分；向量路径贡献分 vectorScore_norm 非零=1 分。Pass threshold ≥4。 | T6/ TR6.4 |
| AC-11 | rubric | Extensibility Score（0~5）：Similarity enum 可加 MAHALANOBIS = 1；SearchOptions 新增 vectorWeights map = 1；HnswStore 独立方法 getNeighborsAtLevel public probe = 1；RRF 四路无冲突扩展 = 1；delta 权重不破坏 P22 默认 = 1。Pass ≥4。 | T6/ TR6.5 |
| AC-12 | rule | 轻量配置 P95 ≤3000ms；稳定排序 Set.size=1；Ingest+Build 30 term dim=768 ≤40000ms。 | T6/ TR6.1, TR6.2, TR6.3 |

---

§End spec.md REV1。
