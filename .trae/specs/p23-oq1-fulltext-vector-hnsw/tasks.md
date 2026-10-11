# P23 OQ1 全文向量 ANN（HNSW 零依赖 + RRF 三路融合）—— tasks.md（**Gate C 验收通过 REV1，2026-10-04**）

**Gate 状态（REV1，2026-10-04 Gate C 闭环）**：
- [x] **Gate A（用户 2026-10-04 VERBATIM approve Spec/Tasks 全文）**
- [x] **Gate B**：Surefire 独立 2×50 TR 0F0E（Run1 05:02 / Run2 04:43，P22 25+P23 25 合计 Tests run:50 0F0E 稳定）
- [x] **Gate C**：25 TR 0F0E + 洁癖 5 份文档同步（AGENTS/Roadmap/ProjectMemory/spec/tasks）+ 自检 9 项全 Y（见 spec.md Gate C 9Y 清单）

**§3.1 测试文件命名约定（T1~T6 单 Surefire 25 TR 0F0E，测试放 ds/ package 非 fulltext 包）**：
- [x] `P23_T1_VectorWriteIdempotentTest`（T1 4 TR Surefire 4.4s PASS：3×幂等 768D / dim 4096 max / idf 保留 / 空 Store 不崩）
- [x] `P23_T2_SimilarityHnswCoreTest`（T2 4 TR Surefire 4.6s PASS：cos/L2 1e-6 / 除零 length mismatch IAE / top1 自匹配 / efSearch upcast）
- [x] `P23_T3_SearchWithVectorRrfTest`（T3 4 TR Surefire 5.1~5.9s PASS：三路 RRF 手算 1e-12 D>A>B>C / 同分 docId 升序 tie-break / vectorRecall=false 与 P22 finalScore 1e-9 全等 / 空参 dim mismatch IAE）
- [x] `P23_T4_PutDocIdempotentClearTest`（T4 4 TR Surefire 6.9~10.5s PASS：3× putDoc tf/docLen/nTerms/termVector 4 项全等 / putDoc 改内容保留 vector 与 idf / clearDoc 保留 idf 与 vector 逐 float 全等 / putDoc("") = clearDoc 语义等价）
- [x] `P23_T5_GatekeeperTest`（T5 4 TR Surefire 4.7~6.6s PASS：P21 TOPK 铁律 + searchWithVector 路径 bucketAccessCounter 总和=0 实锤 / ZeroLeak3=3 / NG-3 9 CNF / Facade≥36 7 个 P23 新探针全存在 + P21/P22 基线不删）
- [x] `P23_T6_NfrQualityTest`（T6 5 TR Surefire 127~151s PASS：30×20 768D Ingest=1.8~2.2s ≤40s Bloat=39MB / P95 searchWithVector=1816~2365ms ≤3000ms / 稳定排序指纹=1 / Quality Rubric=5 满分 DOC_STRONG DOC_SECOND 顺序严格正确 / Extensibility Rubric=4 Similarity+5Options+searchCore+rrf+11Ctor）

---

## P23 Gate A 与 P22 REV1 差异 7 条（Gate A 快速定位，Spec 有完整说明）
1. **新增 HnswStore 内嵌类**（ZeroLeak3 合规）：2 DsHashMap + DsFixedBucketStore（独立 `root/weight_store/hnsw/` 目录），M=16 maxLevel=16 efConstruction=128，节点 levelMask + perLevel 邻居数组变长 bucket。
2. **WeightTermStatRow.payload 扩展 P23**：4B dim + 4B×dim float[]（floatToIntBits 直接序列化）。`recomputeIdfForTerms` 保留原 payload；`indexTermVector` 保留原 idf；双向零 overwrite。
3. **searchWithVector 新增三路 RRF 融合**：R1 byTerm / R2 neighborsWithinD / R3 HNSW knn → IDX2 doc 集 union；RRF(k=60) rank 1-based 三路 Σ1/(60+rank) → 4 段 α=0.5 β=0.25 γ=0.1 δ=0.15 min-max 加权 → 稳定 finalScore 降序 docId 升序。
4. **新增 Similarity enum（COSINE/L2）+ 5 个 HC probes**：cosineSimForTest / l2DistForTest（1e-6 精确）、getTermVectorForTest、getHnswMetaForTest、knnSearch（纯 ANN 召回 probe 绕 searchWithVector）。
5. **SearchOptions 新增 5 字段**（向后兼容，默认值合理）：vectorSim=COSINE / vectorRecall=true / vectorEfSearch=128 / vectorTopK=50 / delta=0.15。
6. **ScoredDocHit 新增 vectorScore 11th 字段**（构造函数 11 参重载 + P22 原 10 参保留兼容，vectorScore=0 默认 P22/P21 结果）。
7. **T5 Gatekeeper 六合一（ZeroLeak3 + NG-3 扩 9 CNF + P21 bucketAccessCounter 零污染 + P22 Facade 子集非递减≥36）**：T5 独立验证 P23 对 P22 零破坏（对 P21 也零破坏，P21 25 TR Surefire 全绿）。

---

## 决策表（Gate A 已冻结，Spec §Open Questions 默认选项，用户无需改）
| 决策 | A Default（MVP） | B Alternative | 选 A 原因 |
|------|------------------|---------------|-----------|
| ANN 算法 | HNSW（skip list 多层图） | IVF-PQ（倒排 + 残差量化） | 结构简单手写成本低，无需训练 |
| 相似度默认 | COSINE + L2 备选 | Dot-product only | COSINE 对标准化 embedding 最通用 |
| qVec 传入 | searchWithVector(q, qVec, K, o) 显式参 | SearchOptions.qVec 字段 | 语义清晰，避免 options 塞大 float[] |
| RRF 融合段 | 4 段 α/β/γ/δ；α=0.5 β=0.25 γ=0.1 δ=0.15 | vector 单独 topK concat | RRF 对 scale 不敏感鲁棒 |
| HNSW 删除 | MVP 不做；同 uid 覆盖幂等 | deleteTermVector + partial rebuild | 复杂度高，先写对再优化 |
| dim 上限 | ≤4096（Llama2 768/1536/3072 全覆盖） | ≤768（仅 BERT base） | 通用性与性能 tradeoff 选宽松 |

---

## 任务清单（6T 25 TR，单 T ≤ 500 行不含测试；依赖序 T1→T2→T3→T4→T5→T6；所有 T 用 JUnit TemporaryFolder + p2p.db.home 隔离）

### Task 1: Vector Schema 写入 + getTermVector 幂等（4 TR，对应 AC-2 AC-9）
**Priority**: high
**Status**: closed ✅（Surefire T1 4/0 PASS）
**Scope**:
- FulltextStore 内嵌：Similarity enum、HnswMetaForTest、HnswStore（2 DsHashMap + 1 DsFixedBucketStore）构造、get/put 关闭
- WeightStore 扩展：`putTermVector(uid, vector)` / `getTermVector(uid)`，复用 termStat.payload，4B dim header + 4B×dim float[]（Float.floatToIntBits LE）。putTermVector **保留原 idf**（new WeightTermStatRow(oldRow.idf, newPayload)）。
- HC probe 2 个：`getTermVectorForTest`、`cosineSimForTest`（先在 T1 提供 stub OK 值）

**Test Requirements（TR）**：
| # | Type | Verbatim 断言（pass 才 TR 合格） | 对应 AC |
|---|------|--------------------------------|---------|
| TR1.1 | rule | `indexTermVector(uA, vec768) 3× 幂等`：vec768 长度 768 元素= (i%37 - 18)/7f 构造，连续 3× put → getTermVectorForTest(uA) 返回 float[]，逐元素 1e-9 全等；HnswMetaForTest.nodeCount == 1（没膨胀）。 | AC-2 AC-8 |
| TR1.2 | rule | `dim=4096 max`：vec4096.length=4096 put → get 1e-9 全等；dim=0 抛 IAE；dim=8192 抛 IAE；uid=0 抛 IAE；uid=-1 抛 IAE；null vec 抛 IAE。 | AC-2 |
| TR1.3 | rule | `termStat.idf 保留`：recomputeIdfForTerms(Arrays.asList(uA)) 先算 idf → indexTermVector(uA, v) 后，getTermStatForTest(uA).idf 与 recompute 前 1e-9 全等（idf 不被 vector 写入覆盖）。 | AC-1 FR-1.4 |
| TR1.4 | rule | `空 WeightStore 不崩`：new FulltextStore(root).getTermVectorForTest(12345) 返回 null；getHnswMetaForTest() 返回 M=16 maxLevel=16 nodeCount=0 entryPointUid=0。 | AC-9 TR5.2 ZeroLeak |

---

### Task 2: Similarity + HNSW 构建 top1 自一致性（4 TR，对应 AC-3 AC-4）
**Priority**: high
**Status**: closed ✅（Surefire T2 4/0 PASS）
**Scope**:
- Static HC probes 2 个：cosineSimForTest / l2DistForTest（1e-6 精确）。
- knnSearch 方法：efSearch<max(M,K) → upcast；uid 无 vector → ISE；遍历 level 从 maxLevel 到 1（贪心入口点收缩），最后 level0 做 efSearch 候选集优先队列贪心（Neighbor.uid + int distance 排序）。
- indexTermVector 真实写 HNSW 节点：randLevel 生成（ln(rand) 反），searchLayer 贪心优先队列（TreeSet<Neighbor> 按 distance 升序），neighbors 剪枝到 M 个最近。

**Test Requirements（TR）**：
| # | Type | Verbatim 断言 | 对应 AC |
|---|------|--------------|---------|
| TR2.1 | rule | cosine：a=[1,0,0] vs b=[1,0,0] = 1.0 ±1e-6；a=[1,0] vs b=[0,1] = 0.0；a=[3,4] vs b=[4,3] = 24/25 = 0.96 ±1e-6。L2：[1,2,3] vs [4,6,8] = sqrt(50) ≈7.0710678 ±1e-6；a=null 抛 IAE。 | AC-3 |
| TR2.2 | rule | 除零保护：cosine a=[0,0,0] vs b=[1,2,3] → 0.0；L2 a.length!=b.length → IAE。 | AC-3 |
| TR2.3 | rule | top1 自一致性：10 term（A..J）dim=16 各 indexVector → knnSearch(uX, 1, 128) 返回 List<Neighbor> 第 0 位必是 uX，distance=±1 (cosine sim=1 → 距离=0)。 | AC-4 |
| TR2.4 | rule | `efSearch upcast`：knnSearch(uA, K=32, ef=10) → 实际 ef ≥32 生效，返回 size=32（若候选够，否则最多候选），不抛错不返回< K（除非全局节点不够）。 | FR-2.1 |

---

### Task 3: searchWithVector 三路 RRF + vectorRecall=false 退化（4 TR，对应 AC-5 AC-6）
**Priority**: high
**Status**: closed ✅（Surefire T3 4/0 PASS）
**Scope**:
- searchWithVector 主方法骨架：Seg Step1 cut → Step0 dynamic docParent map → R1 byTerm → R2 neighbors（vectorRecall=true 且 neighborRecallD>0）→ R3 HNSW qUids 每个 knnSearch 找 vectorTopK 邻居 → IDX2 找 doc → union doc 集合 dedup → sortScore（P22 computeSortScore）→ RRF 三路 rank → phraseBoost → minMax 四段 αβγδ → 稳定排序 topK 返回
- ScoredDocHit 新增 vectorScore 第 11 字段：构造 11 参重载，P22 10 参保留兼容 vectorScore=0 默认。
- RRF 三路手算 probe：searchByUidsAndVecsForTest 公共 HC probe 绕 Seg SPI，传精确 qUids + qVecs 避免切分波动。

**Test Requirements（TR）**：
| # | Type | Verbatim 断言 | 对应 AC |
|---|------|--------------|---------|
| TR3.1 | rule | RRF 三路手算 4 doc A/B/C/D：构造 R1 rank (B>A>D>C) R2 rank (A>D>C>B) R3 rank(D>B>A>C)（用 HC probe 伪造三路独立 rank Map<Long,Integer> 传入 searchByRanksForTest 辅助 probe 或直接用真实数据对齐），手算合计：Σ A(1/62+1/61+1/63) B(1/61+1/64+1/62) D(1/63+1/62+1/61) C(1/64+1/63+1/64) → 顺序 **D>A>B>C** 1e-12 精确与 P23 最终排序全相等。 | AC-5 |
| TR3.2 | rule | `TF ranker 同分 docId 升序`：2 个 doc doc10/doc20 相同 BM25/RRF/phrase/vector 分数 → 最终顺序 doc10→doc20（thenComparingLong(docId)）。 | P22 AC-5 T3 TR3.2 扩展 |
| TR3.3 | rule | `vectorRecall=false 退 P22 全等`：同一 dataset（手动 WORD buildDistForSeq 绕 Seg），P23.searchWithVector(q, null, K, options 设 vectorRecall=false phraseBoost=true neighborRecallD=2) 与 P22.search(q, K, options 同参数) 返回 docId 集合全等、finalScore 逐 1e-9 全等（P22 vectorScore 字段不存在时 P23 ScoredDocHit 10 参构造 vectorScore=0 不影响）。 | AC-6 |
| TR3.4 | rule | 空/非法参数不崩：K=0 空；K=-1 空；query=""+qVec=null 空；query="中国"+qVec=null 不 NPE（退 P22 合法走）；dim mismatch qVec=[len=123] vs store term vector dim=768 → IAE("query vec dim mismatch store dim 123 vs 768")。 | AC-6 |

---

### Task 4: putDoc 3× 幂等 + clearDoc 不删 vector（4 TR，对应 AC-7 AC-8）
**Priority**: high
**Status**: closed ✅（Surefire T4 4/0 PASS）
**Scope**:
- `indexTermVector(uid, vec)` 幂等；putDoc 3×；HnswMetaForTest.nodeCount / entryPointUid 逐字段全等。
- clearDoc Step7 vectorCleanup：**空操作**（不删 termStat、不删 HNSW）。AC-7 断言保留 vector。
- T4 脚手架：复用 P22 T4 putDoc 3× 脚手架 + 10 doc 场景（SHARED 跨 doc term：KEEPA/KEEPB termStat + vector）。

**Test Requirements（TR）**：
| # | Type | Verbatim 断言 | 对应 AC |
|---|------|--------------|---------|
| TR4.1 | rule | 3× putDoc(D1, U2 doc): ① indexTermVector 先 for UIDs（中/国/人/民/共/和/国/万/岁 → 国 vector 覆盖幂等）② 3× putDoc(D1, U2 fullText) → WeightDocStatRow.docLen/nTerms int[6] 逐 int 全等（P22 TR4.1）；HnswMetaForTest.nodeCount / M / maxLevel 字段逐 byte 全等；entryPointUid 全等。 | AC-8 |
| TR4.2 | rule | 状态内 refCount 漂移≤5%：SHARED 跨 3 doc 3 状态（rcBefore / clearD1 / clearD2），单状态内 3 次测量极差率≤5%（P22 TR4.2 同态）。 | AC-8 |
| TR4.3 | rule | clearDoc 后 vector 保留：indexTermVector(KEEPA, vecA) + putDoc(D1) + putDoc(D2)（两 doc 共享 KEEPA 词）→ clearDoc(D1) → getTermVectorForTest(KEEPA) 与 putDoc 前逐元素 1e-9 全等；再 clearDoc(D2) → 仍全等（KEEPA vector 直到显式覆盖才变）。 | AC-7 |
| TR4.4 | rule | clearDoc 后 termStat.idf 保留：KEEPA idf=log(2.5/1.5+1.0)；putDoc(D1+D2) → recomputeIdf → idf 更新 → clearDoc(D1) clearDoc(D2) → idf 原值保留（不删）；search("KEEPA") 命中空集但 termStat 非空。 | AC-7 P22 AC-4 |

---

### Task 5: Gatekeeper 六合一（ZeroLeak3 / 9 CNF / Facade≥36 / P21 bucket 零污染 / P22 语义零破坏 / HNSW 文件独立）（4 TR，对应 AC-9）
**Priority**: high
**Status**: closed ✅（Surefire T5 4/0 PASS）
**Scope**: 复用 P22 T5 结构 + 扩展。

**Test Requirements（TR）**：
| # | Type | Verbatim 断言 | 对应 AC |
|---|------|--------------|---------|
| TR5.1 | rule | P21 语义零破坏：topK(C, K=3) 探针 A..F 6 uid buildDistForSeq → counter[1]=2 counter[2]=1 counter[d≥3]=0；context 隔离 doc1:A,B=1 vs doc2:A,B,Z → doc1 topK(A,2)={B,Z} 互不干扰；**运行 P23.searchWithVector 前后 counter 全 byte 全等**（vectorRecall=true KNN 扫 HNSW 不增 P21 DIST_BUCKET counter）。 | AC-9 NFR-4 |
| TR5.2 | rule | ZeroLeak3：scanTopLevelClassesInPackage("com.q3lives.ds.database.fulltext") → size=3，精确全等 `{TermDictStore, FulltextStore, FulltextSegmenter}`。内嵌类 Similarity/HnswStore/HnswNode/HnswMetaForTest/VectorStore（若新增） 全部含 `$` 标记，top-level 过滤掉。 | AC-9 NFR-1 |
| TR5.3 | rule | NG-3 9 CNF：P22 5 类 + P23 扩 4 类 = 共 9。`org.apache.lucene.util.HnswGraph / com.github.jelmerk.knn.hnsw.HnswIndex / org.nd4j.linalg.factory.Nd4j / org.apache.commons.math3.ml.distance.EuclideanDistance` 全 ClassNotFoundException（NoClassDefFoundError 算泄漏）。 | AC-9 NFR-2 |
| TR5.4 | rule | Facade ≥36 子集非递减：P21 TermDict 基线 6 (toUid/toText/retain/release/refCount/close) + P21 Fulltext 基线 18 (putDoc/clearDoc/ingestDoc/insert/deleteByPk/selectByParentLevel/selectByDocLevel/selectByUnit/buildDistForSeq/distanceBetween/topKNeighbors/neighborsWithin/close + P22 7: search/searchByUidsForTest/recomputeIdfForTerms/getDocStatForTest/getTermStatForTest/getTfForTest/bm25ScoreForTest + rrfRankToScore) = 25 左右 → P23 加 7 (cosineSim/l2Dist/getTermVector/getHnswMeta/knnSearch + searchWithVector + Similarity values synthetic)。最终 TermDictStore public≥6 + FulltextStore≥30 → 总和≥36；P22 基线 18 公共方法名全 ∈ P23 集合（子集不删）。 | AC-9 NFR-3 |

---

### Task 6: NFR 性能 + 质量 Rubric 双分（5 TR，对应 AC-10 AC-11 AC-12）
**Priority**: high
**Status**: closed ✅（Surefire T6 5/0 PASS）
**Scope**: 复用 P22 T6 脚手架（手动 WORD buildDistForSeq 绕 Seg SPI 保证稳定）+ 30 term dim=768 向量构建。

**Test Requirements（TR）**：
| # | Type | Verbatim 断言 | 对应 AC |
|---|------|--------------|---------|
| TR6.1 | rule | Ingest 30 doc × 20 term dim=768 indexTermVector + putDoc + HNSW build ≤40000ms；Bloat 存储 10KB~800MB。 | AC-12 |
| TR6.2 | rule | 轻量 SearchOptions（vectorRecall=true, neighborRecallD=0, phraseBoost=false, charLevelSearch=false, vectorEfSearch=128）100 query P95 ≤3000ms。（MVP 阈值；P23 后续缓存/批量 I/O 时再回 500ms；vectorRecall=false 退 P22 ≤2000ms pass） | AC-12 NFR-5 |
| TR6.3 | rule | 稳定排序：轻量配置 10× searchWithVector → 10 Set<Long> docId 指纹集合 → Set.size=1（无随机性）。 | AC-12 NFR-6 |
| TR6.4 | rubric | Quality Rubric（0~5，threshold≥4）：① top3 doc 顺序与 brute-force 三路 ground-truth 全对（2 分）；② top10 与 brute-force ANN top10 交集数 ≥9（2 分，≥8=1 ≥9=2）；③ 三路独有 doc (R1-only/R2-only/R3-only) 各 ≥1（1 分）。Score≥4 PASS。 | AC-10 |
| TR6.5 | rubric | Extensibility Rubric（0~5，threshold≥4）：① Similarity 可加 MAHALANOBIS（enum 添加不破坏 switch case 有 default）(1)；② SearchOptions 新增 Map<Long,Float> vectorWeightsByUid 不破坏 binary 默认（1）；③ HnswStore 有独立 getNeighborsAtLevelForTest probe（1）；④ RRF 五段加 ε（epsilon=semantic query rewrite 分）扩展无冲突（1）；⑤ delta=0 等价 P22 三权重重归一（1）。Score≥4 PASS。 | AC-11 |

---

§End tasks.md REV1。
