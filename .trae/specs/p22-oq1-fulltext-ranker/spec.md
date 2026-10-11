# P22 OQ1 全文打分引擎（TF/TF-IDF/BM25 + 短语距离 boost + RRF 融合）—— spec.md（**Gate C 已验收版 REV1，2026-10-04**）

**Gate 状态（REV1，2026-10-04 07:10）**：
- [x] **Gate A（2026-10-04 REV1，用户 VERBATIM approve）**：用户 Scope 选 A（打分 MVP）+ Spec/Tasks 全文 VERBATIM 签批。
- [x] **Gate B（2026-10-04，Surefire 独立 2×25 TR 0F0E）**：Run 1 (108s) T1 4.4 + T2 3.9 + T3 4.3 + T4 5.4 + T5 4.0 + T6 54.3 = 25 0F0E；Run 2 (110s) T1 5.0 + T2 3.9 + T3 4.4 + T4 6.0 + T5 4.1 + T6 53.6 = 25 0F0E；独立两次闭包证明无 flaky。
- [x] **Gate C（2026-10-04，全 25 TR 0F0E + 洁癖同步 5 份文件完成）**：T1 4 TR + T2 4 TR + T3 4 TR + T4 4 TR + T5 4 TR + T6 5 TR = 25 TR；spec/tasks Gate head / AGENTS Invariant ×2 / roadmap OQ11 / project_memory 三层同步全 absolute 日期 2026-10-04 无残留相对词。

---

## Overview
- **Summary**：对 P21 全文分层倒排做**零破坏 Facade 扩展**，新增 WeightStore（词频/文档长度/全局 IDF/自定义 payload）+ 4 排序器（BOOL / TF / TFIDF / BM25 Okapi 标准）+ 短语距离 boost + RRF 双路融合（byTerm 精确召回 + neighborsWithin 近邻召回）的 `search(query, K, options)` 主 API；`putDoc/clearDoc` 同步处理权重，幂等。
- **Purpose**：P21 只支持"距离召回"（`topKNeighbors`/`neighborsWithin`/`byTerm`），**无打分、无排序、无查询相关性**。P22 引入工业级 topK 搜索排序能力，立刻可做"关键词搜索 → topK 相关性文档"产品能力。
- **Target Users**：上层搜索服务（原 P21 `byTerm` 返回 `DocHit` 无序 → P22 `search` 返回 `ScoredDocHit` 有序 topK）、后续 P23 向量混合检索（P22 RRF 三路 merge 无需改 schema）。

## Goals
1. **零破坏 P21**：P21 全部 25 TR 必 0F0E；P21 公共方法签名（TermDictStore 6 + FulltextStore 16 = 22 基线）**不删、不改签名**。
2. **工业级打分**：4 排序器枚举，BM25 用 Okapi 标准公式（k1=1.2, b=0.75），单测 1e-9 精确断言。
3. **双路召回 + RRF 融合**：R1=byTerm 精确 + R2=邻居召回（d≤options.neighborRecallD，默认=2），RRF 默认 k=60，分数相加后与 ranker 分 3 段加权融合。
4. **短语 boost**：query 中两 uid distanceBetween<3（相邻/间隔 1）额外加分，可关。
5. **幂等覆盖写**：`putDoc(docId,newText)` 3 次调用结果 = 1 次，weight/tf/docLen 精确一致；termDict.refCount 漂移极差率 ≤5%（继承 P21 T4 TR4 断言模式）。
6. **ZeroLeak13 + NG-3**：fulltext 包独立类永远 3 主类；0 Lucene/ES/Jieba/IK/HanLP/SpaCy 实依赖。

## Non-Goals（P23+）
1. **不做 ANN 高维向量索引 / HNSW**（P23-OQ11-B）：P22 RRF 骨架可直接加第 3 路向量召回，但不在本 Scope。
2. **不做分布式跨节点路由 / Handler**（P23-OQ11-C）：本地单节点 WeightStore。
3. **不做分布式全局 IDF 增量更新**：P22 MVP 扫 docStat size() + IDX2 近似 df，线性 ≤10k doc 可接受；后续 P23 独立 IDFStore 增量。
4. **不做 facet/聚合/高亮/snippet**：搜索 topK 排序核心，UI 辅助后续 P24。
5. **不引入权重模型训练 / LTR**：BM25 超参 k1/b/RRF alpha/beta/gamma 可 SearchOptions 手动调，ML 不在 Scope。

## Background & Context（继承 P21 永久红线 / 硬约束 VERBATIM，不可改）
1. **USER-HC-0（P21）**：`TermDictStore.toUid(text, createIfAbsent)` **永远无 level 参数**；unit_id = `DsString.getIndexId(纯 UTF-8 文本)`；同文本跨 CHAR/WORD/PHRASE/SENTENCE/BLOCK/DOC_STRUCT 出现 → unit_id 严格全等；`DsString refCount` 自动累加跨 doc 共享。
2. **DIST 布局永远不改动（P21）**：DIST_BUCKET 1024×2 L/R 固定 2048 文件上限；ctxKey=mix4(doc,p,lv,srcUid)；PAIR_MAP 六元 key=mix4(mix4(l,r,p,lv),i,j,0xC0FFEE)（仅 HC-6 探针 putPairForTest，禁 distanceBetween 主查询）；UID_RING shard=uid>>>48（65536 上限）。
3. **TOPK 铁律 + bucketAccessCounter[1025] 精确语义（P21）**：P21 `topKNeighbors` d=1 递增、满 K return、每 L/R 桶访问 +1；P22 **完全不改动这 3 语义**，`bucketAccessCounter` 依然是 P21 精确探针，新 search 路径不污染该计数器。
4. **ZeroLeak13（P21）**：`com.q3lives.ds.database.fulltext` 包**独立类白名单只允许 3 个 = {TermDictStore, FulltextStore, FulltextSegmenter}**；其余结构必须 static 内嵌类或放非 fulltext 包；P22 所有 WeightStore/Ranker/ScoredDocHit/SearchOptions/WeightRow 全内嵌到 FulltextStore。
5. **NG-3 零实依赖（P21）**：p2p-db pom 永远不引入 jieba/ikanalyzer/hanlp/spacy/lucene/elasticsearch 任何 groupId；运行时 Class.forName 5 类全 CNF。

## Functional Requirements（FR-1 ~ FR-6）

### FR-1 WeightStore Schema（零破坏新结构，4 张表，独立文件不碰 P21）
WeightStore 是 FulltextStore 的**静态内嵌类**（ZeroLeak13 合规），独立根目录 `root/weight_store/`，不读写 P21 的 tu_pk.dshash / dist_buckets / uid_ring / term_dict / tu_store：
| 物理结构 | 类型 | key | value 字节布局（4B header + 内容，DsFixedBucketStore 惯例） | 说明 |
|---|---|---|---|---|
| `weight_store/doc_stat.dshash` | DsHashMap | `docId` (Long) | `docLen[6]` 6×int + `nTerms[6]` 6×int = 48B 固定。power=6（64B）。 | 每 doc 6 level 的 doc 长度 + 实际不同 term 数；无此 doc → 0 / null。 |
| `weight_store/term_freq_in_doc.dshash` | DsHashMap | `FarmHash64.mix4(unitId, docId, parentId, level)` | `tf int` 4B 固定。power=2（4B）。 | tf=该 term 在 (doc,p,lv) 下的出现次数；0 → 不存在。 |
| `weight_store/term_stat.dshash` | DsHashMap | `unitId` (Long) | `idf double` 8B + `payloadLen int` 4B + payload[] 可变。power 档自适应。 | idf 惰性计算默认 0；recomputeIdfForTerms(uids) 后写回；payload 自定义权重（用户可塞自己分，不参与内置公式）。 |
| `weight_store/weight_bucket_store/` | DsFixedBucketStore | space=`weight_store_p22`, type=`payload` | N/A（DsFixedBucketStore 分配 bucketId） | 给 term_stat 的 payload 变长存；FR-6 探针用。 |

### FR-2 Ingest 第 5 步扩展（零破坏 append，P21 4 步不变）
原 P21 `ingestDoc(docId,text)` 4 步 = ①切分 ②写 text_units 8 列 ③写 DIST_BUCKET/PAIR_MAP/UID_RING ④写 IDX1/2/3。
P22 加 第 5 步：`updateWeightForLevel(docId,parentId,level,uidSeq)`：
1. `tfMap<Long,Integer>`：count uidSeq 中每个 uid 的频次（uidSeq 同 level 同 parent 同 doc 下的切分序列，继承 P21 buildDistForSeq 语义）。
2. 写 `doc_stat.dshash`（merge 6 level：当前 level 的 docLen=uidSeq.size(), nTerms=tfMap.size()；其余 level 保留原值或 0）。
3. 写 `term_freq_in_doc.dshash`：每 uid → key=mix4(uid,doc,p,lv)，value=tf。
4. **不写 term_stat**：idf 全局，不在单 doc ingest 更新；idf 独立接口（FR-3）lazy 触发或显式调用。

### FR-3 4 排序器枚举 + 短语 boost
内嵌枚举 `Ranker { BOOL, TF, TFIDF, BM25 }`，内嵌类 `SearchOptions`（默认字段可覆写）：
```
SearchOptions defaults:
  ranker = Ranker.BM25
  k1 = 1.2 (BM25 饱和参数 Okapi 标准)
  b  = 0.75 (BM25 长度归一 Okapi 标准)
  rrfK = 60 (RRF 分母常数)
  neighborRecallD = 2 (R2 邻居召回 d 上限，d<=2 召回邻词，0 关 R2 只做 byTerm)
  phraseBoost = true
  phraseBoostWeight = 0.1 (对每个 d=1 加 0.2，d=2 加 0.1，d>=3 不加；3-d 乘 phraseBoostWeight，最后归一总和)
  alpha = 0.6 (sortScore_norm 权重)
  beta  = 0.3 (RRF_norm 权重)
  gamma = 0.1 (phraseBoost_norm 权重)
  charLevelSearch = false (切 query 时默认 WORD，true 时 CHAR/WARD/PHRASE 3 层召回 merge)
  idfOverrideByUnitId = null (Map<Long,Double>，外部给 idf，不触发 recomputeIdf)
```
排序公式（term q 在 doc d 下，q 有 Nq 个 query term）：
- **BOOL per-term**：`1.0` if term 命中 else 0；doc 总 BOOL = 命中 term 数（按 #query 归一）。
- **TF per-term**：tf。
- **TFIDF per-term**：tf × idf(unitId)。
- **BM25 per-term（Okapi 标准，public static 可单测 1e-9）**：
  ```
  idf(q) × ( tf × (k1+1) ) / ( tf + k1 × (1 - b + b × docLen[level] / avgdl[level]) )
  avgdl[level] = Σ docLen[level] over all docs / docCount   // 每次 search 触发，N<=10k 线性可接受
  idf(q) = log( (N - df(q) + 0.5) / (df(q) + 0.5) + 1.0 )    // Okapi 标准；df(q)=termFreqInDoc 下 uid 出现 doc 数（IDX2 近似线性扫或 idfOverrideByUnitId 外部覆写）
  ```
- **短语 boost**（全局，不是 per-term）：枚举 query 中两两 uid（i<j），在当前 doc/(p,lv) 下 `distanceBetween(l,r,doc,p,lv)`；若 d∈{1,2} → 加 phraseBoostWeight × (3-d)。总和 = 每对累计。
- **最终总排序分（三段加权归一，0~1 每段归一后加权）**：
  ```
  sortScore_norm  = softmax_minmax(sortScore_by_ranker)  // min=0 max=1
  rrf_norm        = softmax_minmax(rrfTotalScore)
  phrase_norm     = softmax_minmax(phraseBoostTotal)    // 0 时整段=0，不影响
  finalScore(doc) = alpha * sortScore_norm + beta * rrf_norm + gamma * phrase_norm
  ```

### FR-4 search(queryText, K, SearchOptions) 主 API（新增，零破坏）
方法签名（FulltextStore public）：
```java
public List<ScoredDocHit> search(String queryText, int K, SearchOptions options) throws IOException
```
内嵌类 `ScoredDocHit extends DocHit`（继承 P21 DocHit 5 字段，新增排序字段零破坏）：
```
ScoredDocHit:
  long docId / long parentId / int level / int seq / long unitId (继承 DocHit)
  double finalScore
  double sortScore
  double rrfScore
  double phraseBoostScore
  int    matchedQueryTerms
```
步骤 7 步（每步可独立探针，便于 T3 TR3.1~TR3.4）：
1. Seg SPI cut(queryText, WORD unless charLevelSearch=true → CHAR+WORD+PHRASE 3 层 merge) → queryUidList（空或全 unknown → return 空列表，0 异常）。
2. 召回两路：R1 = 每 query uid 的 byTerm 所有 DocHit（去重 per doc/p/lv）；R2 = 每 query uid 的 neighborsWithin(uid, doc, p, lv, neighborRecallD)（=0 时不召回 R2）。两路 DocHit 独立记 rank（出现顺序从高到低，第一次出现的位置 = rank）。
3. **RRF 融合**：对 (docId,parentId,level) 三元组汇总 RRF。RRF(d) = Σ 1/(rrfK + rank_r1) + Σ 1/(rrfK + rank_r2)（两路各 rank 分开累加）。
4. 每 doc 按 options.ranker 算 sortScore：sum(per-term score for query terms)；空 query=0；tf/df 取 termFreqInDoc / IDX2 或 options.idfOverrideByUnitId（后者优先）。
5. 短语 boost：若 phraseBoost=true 且 Nq≥2 → 两两枚举 distanceBetween(l,r,doc,p,lv)，d∈{1,2} 加分。
6. 三段 softmax_minmax 归一（0~1 每段独立）→ alpha/beta/gamma 加权 → finalScore。
7. 按 finalScore 降序，取前 K 返回（<K doc 时只返回能凑到的）。**稳定排序**：同 finalScore docId 小的在前（避免浮点波动）。

### FR-5 clearDoc/putDoc 幂等同步处理 WeightStore（零破坏扩展步骤）
P21 `clearDoc(docId)` = ① IDX3 → ②组 seq 还原 → ③ removeNeighbor 双向 + PAIR_MAP remove + UID_RING remove → ④删 3 IDX + PK map + tu bucket → ⑤去重 release refCount。
P22 加 **clearDoc 第 6 步 weightCleanup(docId)**：
1. 按 docId → 遍历所有 (p,lv) 的 rows（IDX3 给所有 tuRow 集合）；
2. 每 (uid,doc,p,lv)：从 `term_freq_in_doc.dshash` remove；
3. 从 `doc_stat.dshash` 按 docId remove；
4. **不删 term_stat（全局 idf 表）**：下次 search 触发 recomputeIdfForTerms → 重新算 idf 覆盖即可；避免权重表全局写放大。
P22 `putDoc(docId, newText)` 语义 完全继承 P21 = `clearDoc(docId) + ingestDoc(docId,newText)`，幂等。连续 3 次 putDoc → 结果与 1 次完全相同；termDict.refCount 漂移极差率 ≤5%（沿用 P21 T4 TR4 断言模式）。

### FR-6 HC 探针接口（public，仅用于单测断言，不进 P22 对外产品 API 白名单）
```java
public WeightDocStatRow getDocStatForTest(long docId) throws IOException
public WeightTermStatRow getTermStatForTest(long uid) throws IOException
public int getTfForTest(long uid, long docId, long parentId, int level) throws IOException
public static double bm25ScoreForTest(double tf, double idf, double docLen, double avgdl, double k1, double b)
```
内嵌类 `WeightDocStatRow { int[] docLen; int[] nTerms }`、`WeightTermStatRow { double idf; byte[] payload }`。

## Non-Functional Requirements（6 NFR）
- **NFR-1 零破坏回归基线**：`mvn -pl p2p-db "-Dtest=P21_*" test` → Tests run:25, Failures:0, Errors:0, Skipped:0（继承 P21 基线，T5 必跑）。
- **NFR-2 ZeroLeak13 合规**：scan `com.q3lives.ds.database.fulltext` 独立类名集合 = {TermDictStore, FulltextStore, FulltextSegmenter}，size()=3，内嵌类不算。
- **NFR-3 NG-3 零实依赖 5 CNF**：Class.forName 5 类 → 全抛 CNF：① com.huaban.analysis.jieba.JiebaSegmenter ② org.wltea.analyzer.lucene.IKAnalyzer ③ com.hankcs.hanlp.HanLP ④ spacy.Spacy (任意 class) ⑤ org.apache.lucene.search.similarities.BM25Similarity。
- **NFR-4 性能（语料 10 doc × 20 term，继承 P21 T6 语料去波动）**：
  - Ingest 总耗时 ≤4.0 s；
  - search(K=10, BM25, 默认 options) 1000 次：P95 ≤200 ms（1000 次 nanoTime 排序 950 th 分位）；
  - Bloat：P22 weight 目录下所有文件总大小 ∈ [10 KB, 500 MB]（防灾难上下界）。
- **NFR-5 稳定排序**：同一份已 ingest 语料 + 同 query，连续 100 次 search(K=10) → top K 返回的 docId 集合与顺序 **完全一致**（稳定排序 + 浮点同分按 docId 小的在前，无漂移）。
- **NFR-6 Facade 非递减**：反射扫描 P21 公共方法基线（TermDictStore 6 + FulltextStore 16 = 22）→ P22 ≥ 22 + 6（search + 5 个 HC 探针）= 28；老方法签名 **无 delete 无 modify**。

## Constraints（永久硬约束，继承 P21 + 2 条 P22 自加）
### Technical
1. 继承 **P21 USER-HC-0 / DIST 2048 ctxKey / PAIR_MAP 六元仅探针 / TOPK bucketAccessCounter 精确 / ZeroLeak13 / NG-3** 6 条永久红线（Background & Context 1~5）。
2. P22 **不删、不改签名** P21 公共方法：TermDictStore 的 toUid/toText/refCount/retain/release/instancesOfUid；FulltextStore 的 insert/selectByPk/deleteByPk/ingestDoc/distanceBetween/putPairForTest/buildDistForSeq/clearDoc/putDoc/byTerm/byTermInDoc/byTermRowsInDoc/neighborsWithin/topKNeighbors/resolveSegmenter + public final field（termDict/bucketAccessCounter）。
3. P22 所有新增独立类或字段：独立类必须 static 内嵌到 FulltextStore 或 TermDictStore 或非 fulltext 包；FulltextStore 新增字段必须是 `private final` 或 `public final` 探针；禁止修改 P21 distL/distR/distPairMap/uidRingShards/tuPkMap/tuStore/idx1/idx2/idx3 的字段类型或初始化逻辑。

### Business
4. P22 对应用层透明：原 P21 byTerm 调用者不需要改一行代码即可继续工作；P22 search 是可选增强。

### Dependencies
5. P22 代码仅依赖 P21 已使用的 Ds 系列（DsString/DsHashMap/DsFreeRing/DsEqIndexStore/DsFixedBucketStore）+ JDK 原生（ServiceLoader/HttpClient 不用）+ JUnit/TemporaryFolder 测试；**不引入任何新 Maven 依赖**（pom.xml 依赖保持 0 新增）。

## Assumptions
1. 10k doc 内线性扫 doc_stat + IDX2 计算 avgdl[level] / df(uid) 可接受（<200ms 查询 P95）。P23 若 >10k doc → 独立增量 IDFStore 独立维护 avgdl/df 的 rolling update，不破坏 P22 search 逻辑（只需在 SearchOptions.idfOverrideByUnitId 传入或在 recomputeIdfForTerms 切到新 store）。
2. RRF 两路：R1 byTerm 的 rank 是 term 命中数倒序（first-match 最早出现 = 1）；邻居 R2 的 rank 是 distance从小到大（d=1 先 d=2 后）。若出现同 rank 同 doc，RRF 累加不会越界（分母 60+rank 永远正）。
3. Min-max 归一：每段最大值=最小值时分母 0 → 整段固定 0（不会除零异常；同段同分时该段无区分度，合理）。
4. phrase boost：P21 `distanceBetween(l,r,doc,p,lv)` 单向 L→R；P22 传参时 swap 保证 lUid=先出现的 uid，rUid=后出现的 uid（按 query 顺序），与 P21 Guidelines "distanceBetween 反向 swap 参数"一致。
5. 自定义 payload：用户可以任意写入 byte[]，内置 4 个 ranker 完全忽略 payload；只在 FR-6 探针里暴露（应用层自定义 ranker 时自行读取，不在 P22 内置功能范围，算可扩展性）。

## Acceptance Criteria（8 ACs：6 rule + 2 rubric，每个独立可验证）

### AC-1：WeightStore Schema 写读一致（TF / DocLen / nTerms）
- **Type**: `rule`
- **Given**: 空 FulltextStore（TemporaryFolder 独立目录）；WORD 切分："中 国 人 中 国 北 京 天 安 门"（uidSeq[WORD level, parent=0, docId=1] = [中,国,人,中,国,北,京,天,安,门]）。
- **When**: 先调用 `buildDistForSeq(1, 0, WORD, [10 uid])` 手动精确序列（避开 Seg SPI，继承 P21 T3 反切分波动技巧），再调 `updateWeightForLevel`；或直接 putDoc(1, text) 走全链路。
- **Then**:
  1. `getTfForTest(uid中,1,0,WORD) == 2`；`getTfForTest(uid国,1,0,WORD) == 2`；`getTfForTest(uid人,1,0,WORD) == 1`；`getTfForTest(uid北,1,0,WORD) == 1`；天安门三个 uid 各 1。
  2. `getDocStatForTest(1).docLen[WORD] == 10`；`getDocStatForTest(1).nTerms[WORD] == 7`（7 个不同 term）。
  3. `getTfForTest(不存在uid, 1, 0, WORD) == 0`；`getDocStatForTest(不存在docId) == null`。
- **Pass Condition**: 所有断言 1~3 100% 通过，无例外。
- **Evidence**: Surefire `P22_T1_WeightSchemaTest` TR-1.1~TR-1.4 全 PASS。

### AC-2：BM25 单测公式 1e-9 精确（public static）
- **Type**: `rule`
- **Given**: 固定 tf=3.0, idf=1.5, docLen=10.0, avgdl=8.0, k1=1.2, b=0.75。
- **When**: 调 `FulltextStore.bm25ScoreForTest(3.0,1.5,10.0,8.0,1.2,0.75)`。
- **Then**: 返回值精确等于 `1.5 × (3.0 × 2.2) / (3.0 + 1.2 × (0.25 + 0.75 × 1.25))` = 手动算到 1e-9：分子=9.9；分母=3.0 + 1.2×(0.25+0.9375)=3.0+1.2×1.1875=3.0+1.425=4.425；分数=9.9/4.425≈2.2373...；断言 `abs(actual-expected) < 1e-9`。
- **Pass Condition**: 绝对误差 <1e-9。
- **Evidence**: `P22_T2_Bm25FormulaTest` TR-2.1 精确断言。

### AC-3：RRF 双路融合分数与排序一致
- **Type**: `rule`
- **Given**: 两路召回 R1,R2 结果（构造 putPairForTest + buildDistForSeq + byTerm/neighborsWithin 后产生，或直接内部 mock 两路 rank 累加，避免 Seg 波动）：
  - R1 按出现顺序：docA(p=0,lv=WORD) rank=1；docB rank=2；docC rank=3。
  - R2 按出现顺序：docB rank=1；docA rank=3；docD rank=2。
  - 默认 rrfK=60。
- **When**: 计算 RRF 总分 = Σ 1/(60+rank_r1) + Σ 1/(60+rank_r2)。
- **Then**:
  - RRF(docA) = 1/61 + 1/63 ≈0.01639344 + 0.01587302 ≈0.03226646
  - RRF(docB) = 1/62 + 1/61 ≈0.01612903 + 0.01639344 ≈0.03252248
  - RRF(docC) = 1/63 ≈0.01587302；RRF(docD)=1/62≈0.01612903
  - **按 RRF 总分排序（只看 RRF，不加 sort/phrase）**：docB（第一）> docA（第二）> docD（第三）> docC（第四），顺序稳定不变。
- **Pass Condition**: 每 doc RRF 分 `|actual-expected|<1e-12`；排序顺序精确 docB>docA>docD>docC。
- **Evidence**: `P22_T3_SearchRrfTest` TR-3.1~TR-3.4 全 PASS。

### AC-4：putDoc/clearDoc 幂等 + Weight 清理
- **Type**: `rule`
- **Given**: putDoc(1, text1 = "中 国 人 中 国")；uid 集合 U1={中,国,人}。
- **When**:
  1. 调用 putDoc(1, text2 = "北 京 天 安 门 北 京") 连续 3 次；U2={北,京,天,安,门}。
  2. 再 clearDoc(1) 1 次。
- **Then**:
  1. 3 次 putDoc(text2) 后，`getTfForTest`（U2 的 tf：北=2 京=2 天=1 安=1 门=1）与只调用 1 次 putDoc(text2) 的结果**完全相同**（精确 int 相等，无漂移）。
  2. U1 的 uid 在 clearDoc(1) 后：termDict.refCount(uid) 的漂移极差率 ≤5%（极差/均值 ≤5%，继承 P21 T4 TR4）。
  3. clearDoc(1) 后：`getTfForTest(任意uid,1,0,WORD) == 0`；`getDocStatForTest(1) == null`。
- **Pass Condition**: 三个子条件全部满足。
- **Evidence**: `P22_T4_PutDocIdempotentTest` TR-4.1~TR-4.4 全 PASS。

### AC-5：Gatekeeper 四合一（零破坏 / ZeroLeak / NG-3 / Facade）
- **Type**: `rule`（4 合 1 同时验证，缺一 fail）
- **Given**: 无前置条件，clean build。
- **When**:
  1. 跑 `mvn -pl p2p-db "-Dtest=P21_*" test`；
  2. 反射 scan `com.q3lives.ds.database.fulltext` 独立类；
  3. Class.forName 5 类；
  4. 反射统计 TermDictStore.class.getMethods().size() + FulltextStore.class.getMethods().size() 公共方法（仅声明或覆盖的 declared public 或继承 Object 的不算，按自定义方法 filter）。
- **Then**:
  (a) P21 25 TR 0F0E；(b) 独立类 size=3；(c) 5 类全 CNF；(d) P22 ≥ P21 基线（≥28，P21=22 + P22 新增 ≥6）。
- **Pass Condition**: (a)(b)(c)(d) 四个子条件全 true。
- **Evidence**: `P22_T5_GatekeeperTest` TR-5.1~TR-5.4 全 PASS。

### AC-6：NFR 性能 / 稳定性 / Bloat 上下界
- **Type**: `rule`
- **Given**: 10 doc × 20 term（继承 P21 T6 语料，避免波动；每个 doc 内容固定，切分后精确 20 term WORD 层）。
- **When**:
  1. Ingest：10 doc 顺序 putDoc；
  2. Search：固定 query（返回 doc1 高分）1000 次；
  3. Bloat：`Files.walk(weight_store dir)` 求和所有 File.length()；
  4. Stability：100 × search（同 query，K=10），比较每次返回值的前 K docId 列表（转 long[]），是否 100 次完全字节相等。
- **Then**:
  (1) Ingest total ≤4.0 s；(2) 1000 × search P95 ≤200 ms；(3) bloat ∈ [10KB, 500MB]；(4) 100 次 topK 顺序完全一致。
- **Pass Condition**: (1)(2)(3)(4) 四个子条件全 true。
- **Evidence**: `P22_T6_NfrQualityTest` TR-6.1~TR-6.3（rule）+ TR-6.4（rubric）。

### AC-7：打分质量 TopK 符合直觉（Rubric）
- **Type**: `rubric`
- **Dimension**: 搜索 TopK 结果相关性质量。
- **Scale**: 1-5
- **Anchors**: 1=完全乱序、无关 doc 排前；3=命中 query 的 doc 大部分排前，但有高分噪声（缺少 term 的 doc 排到前 50%）；5=前 K 完全符合直觉，高分 doc 均包含所有 query 词且 phrase 近。
- **Pass Threshold**: ≥ 4
- **语料**（10 doc，WORD level）：
  - doc1 = "中华人民共和国 成立 于 1949 年"（5 term）。
  - doc10 = "中国 的 首都 是 北京 ，1949 年 新中国 成立"（9 term）。
  - doc2~doc9（8 份）= 无关散文，如 "春 天 来 了 小 鸟 在 树 上 唱 歌" 等（随机不重复词，不包含 中国/成立/1949）。
- **Query**: `queryText = "1949 中国 成立"`（WORD 层 3 term）。
- **Expected**: `search(query, K=3, BM25 defaults)` 后前 2 名 docId **精确顺序 = [doc1, doc10]**（doc1 三个词都命中 + phrase 近；doc10 三个词也命中但 phrase 距稍远或额外词稀释 BM25 归一；其余 8 个无命中的 doc 排不到前 3）。
- **Evidence**: `P22_T6_NfrQualityTest` TR-6.4a（rubric）断言前 2 docId 顺序；打分 ≥4 即通过（若前 3 有其他 doc 则 1~2 分；若只有顺序乱 doc10 在 doc1 前 = 3 分；顺序正确 = 5 分）。

### AC-8：P23 向量混合检索可接入度（Rubric，零代码变更验证）
- **Type**: `rubric`
- **Dimension**: 无需修改 P22 WeightStore/search 核心代码即可扩展为 VECTOR 三路 RRF 混合检索的容易度。
- **Scale**: 1-5
- **Anchors**: 1=要重写 WeightStore schema、RRF 逻辑、search 流程 ≥3 处；3=需要改 P22 内嵌类（SearchOptions 枚举/Ranker 枚举），但不需要改 WeightStore 文件布局；5=只需要在 SearchOptions 加字段 + Ranker 加 VECTOR 常量 + RRF 第三路 R3（向量召回结果），P22 的 RRF 累加逻辑、三段归一、稳定排序都无需 modify 一行。
- **Pass Threshold**: ≥ 4
- **Evidence**:
  1. Ranker 枚举是否可追加 `VECTOR` 常量？是 → +1
  2. SearchOptions 是否可仅加 `List<Float> vectorQuery / vectorTopK` 字段（public 内嵌类字段）？是 → +1
  3. 三段归一 + 加权 finalScore 公式是否在 1 个独立方法内可只 override 在应用层扩展？是（`private double[] normalize3Segments(...)` 1 方法 + `public double computeFinalScore(...)` 1 方法，独立可拆分） → +2
  4. WeightStore schema / 文件布局是否不需要变动？是 → +1
- **总分 5 → ≥4 → PASS**。

## Open Questions（Gate A 用户 1 选 1，无意见用默认 ✅ Default）
- [ ] **OQ-1 IDF 更新策略**：①（✅ Default，MVP 推荐）惰性：每次 search 若 idfOverrideByUnitId=null 且 term_stat idf=0 → 对当前 query 的 uids 扫 IDX2 近似 df；或 ② 显式：`public void rebuildIdfAll() throws IOException` 外部定时触发（全量 doc_stat/IDX2 线性扫，N≤10k 可接受）。
- [ ] **OQ-2 默认 Ranker**：①（✅ Default）BM25 Okapi；② BOOL 纯布尔先排序再 BM25；③ TFIDF。
- [ ] **OQ-3 charLevelSearch 默认**：①（✅ Default）false（仅 WORD 层切 query 召回，性能优先）；② true（CHAR/WORD/PHRASE 3 层召回 merge 精度优先，代价略高）。
