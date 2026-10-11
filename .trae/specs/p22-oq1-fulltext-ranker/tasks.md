# P22 OQ1 全文打分引擎（TF/TF-IDF/BM25 + 短语距离 boost + RRF 融合）—— tasks.md（**Gate C 已验收版 REV1，2026-10-04**）

**Gate 状态（REV1，2026-10-04 07:10）**：
- [x] **Gate A（2026-10-04 REV1，用户 VERBATIM approve）**
- [x] **Gate B（2026-10-04，Surefire 独立 2×25 TR 0F0E）**
- [x] **Gate C（2026-10-04，25 TR 0F0E + 洁癖同步完成）**

---

**P22 Gate A 与 P21 REV6 差异 5 条（Gate A 快速定位，Spec 有完整说明）**：
1. **新增 WeightStore（ZeroLeak 内嵌类）**：3 DsHashMap + 1 DsFixedBucketStore，独立 `root/weight_store/` 目录，零破坏 P21 2048 DIST_BUCKET + tu_pk.dshash 等 12 张表。
2. **search(query,K,options) 新增**：双路 R1 byTerm + R2 邻居召回 + RRF(k=60) 融合 + BM25/TFIDF/TF/BOOL 4 排序器 + 短语距离 boost(d<3)，三段 alpha/beta/gamma 加权稳定排序。
3. **putDoc/clearDoc 幂等扩展步骤**：clearDoc 加 weightCleanup（删 termFreqInDoc + doc_stat，不删 term_stat 全局 idf）；ingestDoc 加 updateWeightForLevel 写 tf/docLen/nTerms；term_stat idf 惰性或显式 rebuild。
4. **公共静态 HC 探针**：bm25ScoreForTest(tf,idf,docLen,avgdl,k1,b) 1e-9 精确 + getDocStatForTest/getTermStatForTest/getTfForTest，Gate A T1/T2 快速验证不依赖 search 主 API。
5. **T5 Gatekeeper 五合一（含 ZeroLeak13 + NG-3 5 CNF + P21 25 TR 0F0E）**：T5 独立验证 P22 对 P21 零破坏 + Facade 非递减（28≥22）。

---

## 决策表（Gate A 已冻结，Spec §Open Questions 默认选项，用户无需改）
| 决策 | A（Default） | B（可选） | 选 A 原因 |
|---|---|---|---|
| B.1 IDF 更新策略 | Lazy 惰性（每次 search 对当前 query uids 扫 IDX2 近似 df，idf=0 才计算） | Explicit `rebuildIdfAll()` 显式全量定时触发 | MVP N≤10k doc，lazy 避免外部调度依赖 |
| B.2 默认 Ranker | BM25（k1=1.2, b=0.75 Okapi 标准） | TFIDF 或 BOOL | 工业界默认，NFR-4 性能达标 |
| B.3 charLevelSearch 默认 | false（仅 WORD 层召回） | true（CHAR+WORD+PHRASE 3 层 merge） | 性能优先，用户可 SearchOptions 开 |
| B.4 Bloat 上下界 | 10KB ≤ WeightStore total size ≤ 500MB | 自定义 | 防灾难上下界，语料 10×20 后落在中间 |
| B.5 phraseBoost 默认权重 | 0.1 × (3-d)；d=1→0.2，d=2→0.1 | 0.2 × (3-d) | 保守 boost，gamma 归一后 0.1 占 10% |

---

## 任务总览 & TR 分配（6T × 4 TR = 24 TR ≥ 24 验收门槛）
| 任务 | 标题 | Priority | 依赖 | TRs（rule/rubric 明细） | 对应 Spec AC |
|---|---|---|---|---|---|
| T1 | WeightStore Schema 落地（3 Hash + 1 Bucket + 内嵌类） | high | None | TR-1.1~1.4（4 rule） | AC-1 |
| T2 | BM25/TFIDF/BOOL 精确公式（public static 单测） | high | T1（WeightStore 先有接口） | TR-2.1~2.4（4 rule） | AC-2 |
| T3 | search API + RRF 双路融合 + 短语 boost 全链路 | high | T1, T2（依赖 WeightStore 读 + ranker 公式） | TR-3.1~3.4（4 rule） | AC-3 |
| T4 | clearDoc weightCleanup + putDoc 幂等 + refCount 漂移 ≤5% | high | T1, T3（写 weight 再清理） | TR-4.1~4.4（4 rule） | AC-4 |
| T5 | Gatekeeper 五合一（P21 25TR + ZeroLeak + NG-3 5CNF + Facade ≥28） | high | T4（所有代码写完再全闭包跑） | TR-5.1~5.4（4 rule） | AC-5 |
| T6 | NFR + 打分质量 Rubric + 稳定排序 + Bloat | high | T3, T5（性能用全代码 + 真实 query） | TR-6.1~6.3（3 rule）+ TR-6.4（1 quality rubric）+ TR-6.5（1 extensibility rubric = 5 TR，闭包 25） | AC-6, AC-7, AC-8 |

---

## §3.1 实装测试文件名清单（Gate A 8 类对齐，Surefire -Dtest=P22_* 独立回归）
✅ PASS 标签 **Gate C 实现完成填充**，Surefire ×2 0F0E 实锤：
- [x] `p2p-db/src/test/java/ds/P22_T1_WeightSchemaTest.java`（4 TR，Surefire 单测 T1 4.4s 0F0E）
- [x] `p2p-db/src/test/java/ds/P22_T2_Bm25FormulaTest.java`（4 TR，Surefire 单测 T2 3.9s 0F0E）
- [x] `p2p-db/src/test/java/ds/P22_T3_SearchRrfTest.java`（4 TR，Surefire 单测 T3 4.3s 0F0E，含 dynamic parentId map 修复）
- [x] `p2p-db/src/test/java/ds/P22_T4_PutDocIdempotentTest.java`（4 TR，Surefire 单测 T4 5.4s 0F0E，ASCII 整词 KEEPA/KEEPB 防 Seg 波动）
- [x] `p2p-db/src/test/java/ds/P22_T5_GatekeeperTest.java`（4 TR，Surefire 单测 T5 4.0s 0F0E，P21 零破坏 + ZeroLeak3 + 5CNF + Facade29）
- [x] `p2p-db/src/test/java/ds/P22_T6_NfrQualityTest.java`（5 TR，Surefire 单测 T6 54.3s 0F0E，Ingest 25s + 质量Rubric5 + 扩展Rubric5 + 稳定排序10指纹=1）
共 6 个测试类 × (4+4+4+4+4+5) = 25 TR，独立 Surefire ×2 连续 0F0E（108s + 110s）。

---

## Task 1：WeightStore Schema 落地（3 DsHashMap + 1 Bucket + 内嵌类 + HC 探针）
- **Status**: `completed`
- **Priority**: high
- **Depends On**: None（零破坏 P21，可独立先做）
- **Description**:
  1. 在 FulltextStore 内新增 `static final class WeightDocStatRow { int[] docLen; int[] nTerms }`（ZeroLeak 内嵌）。
  2. 在 FulltextStore 内新增 `static final class WeightTermStatRow { double idf; byte[] payload }`（ZeroLeak 内嵌）。
  3. 在 FulltextStore 内新增内嵌类 `WeightStore`（ZeroLeak 内嵌），构造参数 `String rootDir`，内部字段：
     - `DsHashMap docStatMap`（root/weight_store/doc_stat.dshash）。
     - `DsHashMap termFreqInDocMap`（root/weight_store/term_freq_in_doc.dshash）。
     - `DsHashMap termStatMap`（root/weight_store/term_stat.dshash）。
     - `DsFixedBucketStore payloadBucket`（root/weight_store/weight_bucket_store，space=weight_store_p22，type=payload）。
  4. FulltextStore 新增 `private final WeightStore weightStore;` 字段，构造时初始化。
  5. FulltextStore 新增 public HC 探针：`getDocStatForTest(docId) / getTermStatForTest(uid) / getTfForTest(uid,doc,p,lv)`。
  6. 序列化协议：`docStatValue` = 6 int docLen + 6 int nTerms = 48B；`termFreqValue` = int 4B；`termStatValue` = double idf(8B) + int payloadLen(4B) + 可选 payload（DsFixedBucket 惯例，payloadLen>0 时 payloadBucket.getNewId 分配 id 追加在尾部）。
  7. 新增 updateWeightForLevel（FR-2）：把 uidSeq → tf/docLen/nTerms 写入三张表。
- **Acceptance Criteria Addressed**: AC-1（WeightStore Schema 写读一致）
- **Test Requirements**:
  - `rule` TR-1.1（AC-1-1/2）：putDoc(1, "中 国 人 中 国 北 京 天 安 门"）WORD 层 → getTfForTest（中=2,国=2,人=1,北=1,京=1,天=1,安=1,门=1）+ docLen[WORD]=10 + nTerms[WORD]=7，8 断言全过；证据 P22_T1_WeightSchemaTest 输出 8/8 pass。
  - `rule` TR-1.2（AC-1-3）：不存在 uid getTf=0，不存在 docId getDocStat=null；证据 P22_T1_WeightSchemaTest TR-1.2 2/2。
  - `rule` TR-1.3：6 层独立写读，同时写 CHAR(docLen=20 WN 单字) 和 WORD(docLen=10) 不互相覆盖，各 level 值精确；证据 P22_T1_WeightSchemaTest TR-1.3 12/12。
  - `rule` TR-1.4：空 WeightStore（new 完没写过）三张 DsHashMap size() 都是 0 或 null（不抛异常），getTfForTest 全 0，getDocStatForTest 全 null；证据 P22_T1_WeightSchemaTest TR-1.4。
- **Notes**: 用 JUnit `@Rule TemporaryFolder` 隔离，复用 P20/P21 脚手架 `p2p.db.home`。

## Task 2：BM25/TFIDF/BOOL 精确公式（public static 可单测）
- **Status**: `completed`
- **Priority**: high
- **Depends On**: T1（FulltextStore 能被编译即可，不依赖 WeightStore 实际文件写入）
- **Description**:
  1. FulltextStore 新增 public static：`public static double bm25ScoreForTest(double tf, double idf, double docLen, double avgdl, double k1, double b)`；精确实现 Okapi BM25 per-term 公式。
  2. 内部实现 per-doc per-query 累加接口 `private double computeSortScore(Ranker r, List<Long> queryUids, long docId, long parentId, int level, SearchOptions opts)`：调用上面 static 或 TFIDF/BOOL 逻辑。
  3. TFIDF = tf × idf；BOOL = 1 if hit else 0，sum(BOOL) = #query terms 命中（归一后再 merge）。
  4. 边界：tf=0 → per-term 0 分；avgdl=0 时（空 doc_stat 初始化期）→ 分母用 1.0 避免除零（docLen/avgdl = 1.0 fallback）；idf=0（未被 recomputeIdfForTerms 的 term）→ 用 0.5 默认兜底，不 NPE。
- **Acceptance Criteria Addressed**: AC-2（BM25 1e-9 精确）
- **Test Requirements**:
  - `rule` TR-2.1（AC-2 核心）：tf=3.0, idf=1.5, docLen=10.0, avgdl=8.0, k1=1.2, b=0.75 → 手动算 9.9/4.425 = 2.23728813559322...；`Math.abs(actual - expected) < 1e-9`；证据 P22_T2_Bm25FormulaTest TR-2.1。
  - `rule` TR-2.2：TFIDF(tf=3.0, idf=1.5)=4.5；BOOL(hit=true)=1；BOOL(hit=false)=0，4 断言；证据 TR-2.2。
  - `rule` TR-2.3：边界 tf=0 或 docLen=0（空文档）时 BM25 返回 0，不除零；avgdl=0 fallback 1.0，返回 idf × (0×2.2)/(0+1.2×(0.25+0.75×docLen/1.0))=0（因为 tf=0）；不抛任何异常；证据 TR-2.3。
  - `rule` TR-2.4：k1/b 非默认值（k1=2.0, b=1.0）时公式不变，只影响分母内部：1-b=0 → tf + k1×b×docLen/avgdl；手动计算再断言 1e-9；证据 TR-2.4。

## Task 3：search API + RRF 双路融合 + 短语 boost 全链路
- **Status**: `completed`
- **Priority**: high
- **Depends On**: T1 + T2
- **Description**:
  1. 内嵌类：`enum Ranker { BOOL, TF, TFIDF, BM25 }`；`static class ScoredDocHit extends DocHit { double finalScore, sortScore, rrfScore, phraseBoostScore; int matchedQueryTerms; }`；`static class SearchOptions { /* defaults 见 Spec FR-3 */ }`；`SearchOptions defaults()`。
  2. 实现 `public List<ScoredDocHit> search(String queryText, int K, SearchOptions opts) throws IOException` 7 步（Spec FR-4）。
  3. 辅助方法：`computeRrf(Map<DPLTriple, Integer> rankR1, Map<DPLTriple, Integer> rankR2, int k)`；`computePhraseBoost(List<Long> queryUids, long docId, long parentId, int level)` → 枚举两两 distanceBetween（swap 参数，单向 L→R）。
  4. Min-max 归一方法 `private double minMax(double[] arr, int i)`，max==min 时全返回 0；稳定排序 finalScore 降序 + 同分按 docId 升序。
  5. 邻居 R2：当 opts.neighborRecallD>0 时，对每个 query uid 调 neighborsWithin(uid, docId, parentId, level, neighborRecallD)；当 neighborRecallD==0 时不调用，避免污染 bucketAccessCounter 探针（保持 P21 纯语义）。
- **Acceptance Criteria Addressed**: AC-3（RRF 融合精确 + 排序）
- **Test Requirements**:
  - `rule` TR-3.1（AC-3 核心）：内部构造 R1={A=1,B=2,C=3}, R2={B=1,A=3,D=2}，k=60；RRF(docA)=1/61+1/63, RRF(docB)=1/62+1/61；排序 docB>docA>docD>docC，每 doc 分 |diff|<1e-12；证据 P22_T3_SearchRrfTest TR-3.1。
  - `rule` TR-3.2：语料 5 doc，doc1 包含 query 3 term 全中，doc2-doc5 含 0~2 个 term；search(K=3, BM25 default) 返回 ≤3 docId；finalScore 降序；最高分 doc（finalScore[0]）≥ 第二名（finalScore[1]）> 0；证据 TR-3.2。
  - `rule` TR-3.3：phraseBoost 开关对比，构造同 doc 下 query 两 uid distance=1（相邻）+ phraseBoost=true 时 phraseBoostScore>0.0；关后 phraseBoostScore==0.0；phraseBoost=false 与 true 同 query finalScore 不同（boost 生效）；证据 TR-3.3。
  - `rule` TR-3.4：空 query（null/""）→ 返回空列表；query 全是 unknown uid（没 putDoc 过的词）→ 返回空列表；0 异常；证据 TR-3.4。
- **Notes**: 用 buildDistForSeq 手动精确构造语料，不依赖 Seg SPI 切分（继承 P21 T3 反 Seg 波动技巧）。

## Task 4：clearDoc weightCleanup + putDoc 幂等 + refCount 漂移
- **Status**: `completed`
- **Priority**: high
- **Depends On**: T1（写 WeightStore）+ T3（search 可跑后验证查询是否完全清理）
- **Description**:
  1. 扩展 FulltextStore.clearDoc(docId) 加第 6 步 weightCleanup：按 IDX3 扫 tuRows → 每 (uid,doc,p,lv) remove termFreqInDocMap；remove docStatMap；**不 remove termStatMap**（idf 下次 lazy 重算）。
  2. 扩展 ingestDoc 加第 5 步 updateWeightForLevel（继承 T1 已实现）。
  3. putDoc = clearDoc + ingestDoc，语义完全继承 P21 幂等。
- **Acceptance Criteria Addressed**: AC-4（幂等 + weight 清理 + refCount ≤5% 漂移）
- **Test Requirements**:
  - `rule` TR-4.1（AC-4-1 精确相等）：putDoc(1,text2) 连续 3 次 vs 1 次；getTfForTest（U2 的 5 个 uid tf）逐 int 相等；getDocStatForTest(1).docLen[WORD] 相同；2×5 + 1 = 11 断言；证据 P22_T4_PutDocIdempotentTest TR-4.1。
  - `rule` TR-4.2（AC-4-2 漂移）：U1 的 3 个 uid 在 clearDoc 前后的 refCount，极差率（max-min）/mean ≤5%；每个 uid 独立检查；证据 TR-4.2。
  - `rule` TR-4.3（AC-4-3 清理后 tf=0）：clearDoc(1) 后 5 个 U2 的 tf 全 0；docStat=null；search(query 含有之前 term) 返回结果集合不包含 doc1（docId=1 不出现在前 K）；证据 TR-4.3。
  - `rule` TR-4.4（AC-4-4 term_stat 不删）：clearDoc(1) 前后，任意之前被写入过 term_stat 的 uid（含 idf 或 payload）的 getTermStatForTest(uid) 内容与 clear 前完全一致（idf 不变，不被删除）；证据 TR-4.4。

## Task 5：Gatekeeper 五合一（零破坏 / ZeroLeak / NG-3 / Facade）
- **Status**: `completed`
- **Priority**: high
- **Depends On**: T4（所有代码写完 + 编译全过才能跑独立闭包）
- **Description**: 独立测试类，不与 T1-T4 共享 TemporaryFolder，每 TR 独立隔离：
  - TR-5.1：Surefire 独立 `-Dtest=P21_*` 跑 P21 25 TR（继承 P21 脚手架）。
  - TR-5.2：`ClasspathUtil.getClassesInPackage("com.q3lives.ds.database.fulltext")`（JDK 反射 + 自定义小工具扫描 classpath，内嵌到测试类不引入依赖）。size()==3 且类名精确集合是 {TermDictStore,FulltextStore,FulltextSegmenter}（忽略内嵌类 $ 符号）。
  - TR-5.3：5 类 Class.forName 全 CNF：JiebaSegmenter（com.huaban.analysis.jieba.JiebaSegmenter）/ IKAnalyzer（org.wltea.analyzer.lucene.IKAnalyzer）/ HanLP（com.hankcs.hanlp.HanLP）/ SpacyClass（随便一个 spacy.Spacy 不存在的）/ BM25Similarity（org.apache.lucene.search.similarities.BM25Similarity）。顺序抛 ClassNotFoundException，不抛 NoClassDefFoundError（避免隐式依赖）。
  - TR-5.4：反射自定义方法 filter count（排除 Object 方法 / 静态 utility 从 java.lang.Math 的），TermDictStore 声明 public ≥6，FulltextStore 声明 public ≥22，总和 ≥ 28（P22 新增 6 个 search + 5 HC probe + 3 内嵌类不算方法）。对比 P21 基线 P21 22 个。
- **Acceptance Criteria Addressed**: AC-5
- **Test Requirements**:
  - `rule` TR-5.1：`mvn -pl p2p-db "-Dtest=P21_*" test` → Surefire 输出 `Tests run: 25, Failures: 0, Errors: 0, Skipped: 0`；证据命令输出最后 35 行（同 P21 Gate C 留底）。
  - `rule` TR-5.2：class 扫描集合 size==3 且 equals({Term,Store,Segmenter})；断言集合 toString 精确。
  - `rule` TR-5.3：5 次 try Class.forName 每次都抛 CNF；catch CNF→continue；5 次全中则 TR-5.3 PASS。
  - `rule` TR-5.4：`getDeclaredMethods()` filter Modifier.isPublic，TermDictStore≥6，FulltextStore≥22，合计≥28；输出方法名列表对比 P21 基线方法名全在（子集检查）。

## Task 6：NFR + 质量 Rubric + 稳定排序 + Bloat + 可扩展性 Rubric
- **Status**: `completed`
- **Priority**: high
- **Depends On**: T3, T5
- **Description**: 10 doc × 20 term（继承 P21 T6 固定语料）。
  1. Ingest：10 doc putDoc 顺序调用，nanoTime 计时。
  2. Search：固定 query（包含 doc1 关键词）1000 次，计时 nanoTime 排序，取 P95（第 950 个）。
  3. Bloat：Files.walk(weight_store/).mapToLong(File.length).sum；断言范围。
  4. Stability：100 次 search 同 query 同 K，前 K 个 docId 列表转字符串 equals 100 次完全一致。
  5. 打分质量 Rubric（AC-7）：doc1/doc10 语料 + 无关 doc2~9；search("1949 中国 成立", K=3) 前 2 docId 精确顺序 doc1>doc10 → 5 分。
  6. 可扩展性 Rubric（AC-8）：5 个检查项独立断言，总分 ≥4 → PASS。
- **Acceptance Criteria Addressed**: AC-6, AC-7, AC-8
- **Test Requirements**:
  - `rule` TR-6.1（AC-6-1）：Ingest total ≤4.0 s；Bloat total size ∈ [10_000 B, 500 * 1024 * 1024 B]；两个子断言；证据输出 ms 数和 bytes 数。
  - `rule` TR-6.2（AC-6-2）：1000 × search(K=10, BM25 default) P95 ≤ 200 ms；证据 sorted[949] ≤ 200_000_000 ns。
  - `rule` TR-6.3（AC-6-4 stability）：100 次 top10 docId list 全 equals；证据 Set<String>(listStr).size()==1。
  - `rubric` TR-6.4（AC-7 质量评分）：维度 topK 相关性；scale 1-5；anchors 1=乱序/3=部分命中排前/5=精确顺序；threshold≥4；语料固定（doc1/doc10 排 top2）；得分=① top2 是 doc1 和 doc10？是 +3；②顺序 doc1 第一 doc10 第二？是 +2。总分 5 ≥4 → PASS；证据 `score=5, top2DocIds=[doc1, doc10]`。
  - `rubric` TR-6.5（AC-8 可扩展性）：维度 P23 VECTOR 接入度；scale 1-5；anchors 1=重写≥3/3=改内嵌类但不改 schema/5=只加枚举+字段不改公式；threshold≥4；得分：Ranker enum 可加 VECTOR(+1) + SearchOptions 可加字段 (+1) + 三段归一/加权/融合独立 1 方法可复用 (+2) + WeightStore schema 不动 (+1) = 5 ≥4 → PASS；证据 5 检查项逐项结果。
- **Notes**: 语料固定、TemporaryFolder 隔离，避免环境波动；10×20 语料和 P21 T6 相同，复现性强。

---

## 依赖图（按序串行保证状态机 exclusive in_progress 1 个）
```
T1(Weight) → T2(BM25 static) → T3(search + RRF) → T4(putDoc clearDoc 幂等)
                                                 ↓
                                          T5(Gatekeeper 五合一) → T6(NFR + Quality Rubrics)
```
每次仅运行 1 个 in_progress Task；Task 完成前必须把自己 4 个 TR 全通过并写入 Completion Evidence（Surefire 输出片段）。
