# P25 OQ1 全文高亮 / Snippet / Facet 聚合（term 位置 payload + 滑窗截取 + topK facet）—— spec.md（**Gate C 实装对齐版 REV1，2026-10-04**）

**Gate 状态（REV1，2026-10-04）**：
- [x] **Gate A（用户 VERBATIM approve Scope + Spec/Tasks 全文，2026-10-04 17:01）**
- [x] **Gate B**：两连 Surefire 101 TR（P22×25 + P23×25 + P24×26 + P25×25） 0F0E
  - Run1（2026-10-04 20:20）：Tests=101 Fail=0 Err=0 Skip=0，25m32s
  - Run2（2026-10-04 21:25）：Tests=101 Fail=0 Err=0 Skip=0，31m03s
- [x] **Gate C**：25 TR 0F0E + 洁癖 5 份文档同步 + 自检 9 项全 Y（2026-10-04 21:26）

---

### Gate C 自检 9Y（NG-3 / ZeroLeak3 / Facade / BackwardCompat）
1. [x] Y1 ZeroLeak3=3：top-level 独立类仅 `TermDictStore / FulltextStore / FulltextSegmenter`，4 新增结构（PositionSpan/SnippetHit/FacetHit/ScoredDocHitWithExtras）全 static 内嵌 `FulltextStore$*`
2. [x] Y2 NG-3 9 CNFE：jieba/ikanalyzer/hanlp/spacy/BM25Similarity/HNSW lucene/HnswIndex jmerk/Nd4j/commons-math3 EucDist 9 类 Class.forName → CNF；io.netty.* / io.grpc.* 0 符号引用
3. [x] Y3 P21 TOPK 铁律：`bucketAccessCounter[1..1024]` 在 P22/23/24/25 任何 search/* / searchCluster/* / searchWithExtras / searchClusterWithExtras 路径 sum=0（TR5.1 双套各 100 次实锤）
4. [x] Y4 Facade ≥52：`(TermDictStore×10)+(FulltextStore pubMethods×59 + pubFields×3)+(ShardRouter×19)=91`，非递减（≥P24 基线 77）
5. [x] Y5 Position payload 隔离：term_positions key = mix4(uid,docId,parentId, mix2(level, 0xDEADBEEFL)) 与 termFreqInDoc key=mix4(uid,docId,p,lv) 哈希无交集
6. [x] Y6 ScoredDocHitWithExtras 组合模式：持有 core 字段 NOT extends final ScoredDocHit（保持签名稳定向后兼容 P21~P24）
7. [x] Y7 BackwardCompat：CutUnit so<0 或 eo≤so → fallback start=seq end=seq+1（P24/老 Seg 不提供 start/end 不崩）
8. [x] Y8 排序稳定：Snippet startInDoc 升序 / Facet 三键 docCount↓ totalTf↓ termText↑ / ScatterGather 三键 finalScore↓ docId↑ shardId↑
9. [x] Y9 单调：P21~P25 任何 Test 方法签名不可删、不可降级（共 26+26+26+25=103 个方法全部保存在 Surefire classpath）

---

## Overview
- **Summary**：对 P24 ShardRouter 做**零破坏 Facade 扩展**（ZeroLeak3 仍 3，全 static 内嵌 FulltextStore）。新增 term 命中位置 payload：新增 DsHashMap `term_positions.dshash`（key=FarmHash64.mix4(uid, docId, parentId, level, 0xDEADBEEF)，value=DsFixedBucketStore bucket：4B count + count × [4B start, 4B end] char offset 对）。putDoc ingestDoc 内 updateWeightForLevel 同步写位置（每次 CutUnit.start/end 直接取，Seg SPI 提供）。search 返回 **ScoredDocHitWithExtras**（Final ScoredDocHit 组合不继承）：`ScoredDocHit core + List<PositionSpan> matchedSpans + List<SnippetHit> snippets + List<FacetHit> facets`。Snippet 滑窗 ±snippetWindow/2（默认 40）从 rawDocText 截取，每个 doc 最多 snippetMaxCount=3 条，按 span.score 排序保留。Facet 聚合基于 IDX2 + WeightStore.getTf，topK facetTopK（默认 10）按 docCount 降序、同分按 totalTf 降序。
- **Purpose**：P24 searchCluster 只返回 score + meta，**缺 UI 展示能力**。P25 提供：① 命中词高亮区间（前端 <b> 渲染）；② 摘要 snippet（搜索结果 3~5 行短摘要，高亮命中词上下文）；③ Facet 侧栏（按 level 分组，如 WORD/SENTENCE level top 词 docCount 聚合，支持筛选）。
- **Target Users**：DsDbWebUI（前端 Vue/React 展示层）、P26 GRPC 返回 Extras 字段、P27 Query Builder 按 facet 过滤复用 FacetHit。

## Goals
1. **G1 位置写入正确性**：Seg 切分 10 字句子 → PositionSpan start/end 0-based [start,end) 半开区间字符 index；10 doc 写入后，随机抽 doc × term 的 getTermPositionsForTest 返回 spans 与 Seg 原始切分 CutUnit 完全全等。
2. **G2 Snippet 滑窗正确**：rawDocText = "中华人民共和国成立于1949年，是一个伟大的国家"；命中 term "人民" start=2 end=4；命中 "国家" start=18 end=20；snippetWindow=10（左右各 5）→ snippet1 截取 "华人民共和国成立于"（2-5= -3 截到 0，4+5=9 → [0,9]），snippet2 [13,23]，内容断言包含目标词且区间 ±5 正确（T3 TR3.2）。
3. **G3 Facet 聚合正确**：10 doc × 5 WORD term（中/国/人/民/共，其中 "中" 出现在 D0 D1 D5 D7 共 4 doc，docCount=4；"国" 出现在 D0 D1 D3 D5 D7 共 5 doc）→ facetTopK=5 返回 docCount 降序 [国(5), 中(4), 民(x), 人(y), 共(z)]，手算 totalTf=Σ doc 的 tf(doc) 与聚合全等 1e-0 int。
4. **G4 零破坏**：P23/P22/P21 Facade 方法子集全 ∈；ZeroLeak3；bucketAccessCounter 零污染；11 CNF 全 CNF（P24 不增类，P25 也不增，保持 11 CNF）；Facade≥44 → P25 ≥52；ScoredDocHit 原 10/11/12 参构造不修改，ScoredDocHitWithExtras 组合 core。

## Non-Goals
- **NG-1 不做 HTML/Markdown Render**：PositionSpan/SnippetHit 只返回 start/end（纯 int offset）；HTML <b> 包裹、Markdown **bold** 是前端责任；本层零 HTML 字面量、零模板库、零 JSOUP（NG-3 11 CNF 不增）。
- **NG-2 不做 term 位置跨 level 合并**：按 (docId, parentId, level) 分组单独写 positions；WORD/PHRASE/SENTENCE level 独立 span，不做聚合。
- **NG-3 不做 Facet 过滤**：Facet 仅 "展示"（返回 facet.docCount + totalTf），filter "只看含 term X 的 doc" 不在 MVP；后续 P27 Query Builder 做。
- **NG-4 不修改 CutUnit / Seg SPI 签名**：CutUnit 原 text + seq（P21 已内嵌），新增 P25 CutUnit 字段 start/end：**P25 加字段不删原字段**（BackwardCompat：原 Seg SPI 不传 start/end 默认设 start=seq end=seq+1 兼容）。
- **NG-5 不做向量高亮 / 向量 Facet**：R3 vector 召回不做语义 span（只按 R1/R2 精确命中 term 生成 span）。

---

## Functional Requirements (FR)
### FR-1 位置写入 + 表结构（ZeroLeak3 内嵌 WeightStore 扩展）
- **FR-1.1 term_positions.dshash**：新增在 WeightStore maps 目录，key=FarmHash64.mix4(uid, docId, parentId, (long)level, 0xDEADBEEFL)；value bucket = `[totalLen4B][count4B][span1_start4B][span1_end4B]...[spanN_start4B][spanN_end4B]`，总长度 4+4+count×8。
- **FR-1.2 updateWeightForLevel 扩写位置**：P22 ingestDoc L791-L793 updateWeightForLevel 追加写 positions（每 TuRow.level、TuRow.unitId、TuRow.startCh/TuRow.endCh 若缺则默认 seq/seq+1）。
- **FR-1.3 幂等清理 clearDoc**：Step7 positionCleanup：按 (docId, parentId, level) 分组，对每个 TuRow.unitId 做 `weightStore.removePositions(r.unit_id, docId, r.parent_id, r.level)`；termStat/vector 不删（同 P22 AC-4、P23 AC-7、P24 AC-7）。
- **FR-1.4 HC probe 读**：`List<PositionSpan> getTermPositionsForTest(long uid, long docId, long parentId, int level)`（miss 返空 List 不 null，ZeroLeak PositionSpan 内嵌：`static final class PositionSpan{public final int start;public final int end;public PositionSpan(int s,int e){start=s;end=e;} @Override equals hashCode}`）。

### FR-2 命中 span 收集 + Snippet 滑窗
- **FR-2.1 matchedSpans**：searchCore 中 qUids 对命中 doc，按 (doc, parent, level) 合并所有 qUid 的 positions，去重按 start 升序，span 相邻/重叠合并（[0,4] + [2,6] → [0,6]，T3 TR3.1 手算 2 条）。
- **FR-2.2 SnippetHit 内嵌**：`static final class SnippetHit {public final String snippetText; public final int startInDoc; public final int endInDoc; public final double score; public final List<PositionSpan> highlightRangesInSnippet;}`（highlightRangesInSnippet 是 snippetText 内部的相对 offset，前端直接 <b> 包，不用再 rawDocText 再定位）。
- **FR-2.3 滑窗算法**：snippetWindow=W，每个 span 的 center = (s+e)/2，窗口 = [max(0, center - W/2), min(rawLen, center + W/2)]；窗口按 "包含命中词数量 + Σ BM25 该 term idf 分" 排序，保留前 snippetMaxCount=3 条。
- **FR-2.4 HC probe**：`List<SnippetHit> buildSnippetsForTest(String rawDocText, List<PositionSpan> spans, SearchOptions o)`（绕 search 注入 raw 文本测滑窗）。

### FR-3 Facet 聚合 topK
- **FR-3.1 FacetHit 内嵌**：`static final class FacetHit {public final long unitId; public final String termText; public final int level; public final long docCount; public final long totalTf;}`。docCount = IDX2 selectByUnit(uid) 的 set of docId 大小；totalTf = Σ doc∈docs Σ(parent, level) WeightStore.getTf(uid, doc, parent, level)。
- **FR-3.2 Facet 计算范围**：默认 level=WORD（用户只关心词级 facet，SearchOptions.facetLevel 默认=WORD=1）；charLevelFacet=true 时加 CHAR level（0），默认 false 性能。
- **FR-3.3 排序**：docCount 降序 → 同分 totalTf 降序 → 同分 termText 字典序升序（Comparator 稳定）。facetTopK=10 取头 10。
- **FR-3.4 HC probe**：`List<FacetHit> computeFacetsForTest(List<Long> docIds, int level)`（注入 doc 子集直接测聚合）。

### FR-4 searchWithExtras 主 API
- **FR-4.1 签名**：`ScoredDocHitWithExtras searchWithExtras(String query, String rawDocTextsByDocId<Long,String>, int K, SearchOptions options)`。rawDocTextsByDocId 若 null → snippets 空（只返回 matchedSpans + facets，不生成 snippet 文本）。返回 `static final class ScoredDocHitWithExtras { public final ScoredDocHit core; public final List<PositionSpan> matchedSpans; public final List<SnippetHit> snippets; public final List<FacetHit> facets; }`（ZeroLeak 内嵌，组合不继承 final）。
- **FR-4.2 search 不变**：原 P21/P22/P23/P24 search/searchWithVector/searchCluster 全不改，只新增 searchWithExtras（Facade 非递减）。
- **FR-4.3 SearchOptions 扩展（P25 新字段，向后兼容默认值）**：
  ```
  public boolean returnPositions = true;          // 关闭时 matchedSpans 空，节省 I/O
  public int facetLevel = 1;                       // WORD=1；0=CHAR, 2=PHRASE（不建议）
  public boolean charLevelFacet = false;           // 额外加 CHAR level facet
  public int facetTopK = 10;
  public int snippetWindow = 40;                   // 左右各 20
  public int snippetMaxCount = 3;
  ```
- **FR-4.4 Cluster 支持**：`searchClusterWithExtras(...)` 对应 P24 scatter-gather，snippet 用 rawDocTextsByDocId 在 gather 后生成（前端只负责提供 docId→rawText map）。

### FR-5 HC probes 公共
- getTermPositionsForTest / buildSnippetsForTest / computeFacetsForTest / mergeSpansForTest（span 合并测）/ sortFacetsForTest（排序注入假 Facet 测稳定排序）。

---

## Non-Functional Requirements (NFR)
- **NFR-1 ZeroLeak3**：独立类仍 3。ScoredDocHitWithExtras / PositionSpan / SnippetHit / FacetHit 全内嵌。
- **NFR-2 NG-3**：保持 P24 11 CNF（P22 5 + P23 4 + P24 2），P25 不引新库；No HTML/Markdown/JSOUP 字面量；facet 排序用 JDK Collections.sort，零外部集合库（fastutil 等）。
- **NFR-3 Facade≥44 → ≥52**：P24 ≥44；P25 新增：searchWithExtras / searchClusterWithExtras + 5 probes + 4 内嵌 class synthetic ≥8 → 合计 ≥52。P24 公共方法名全 ∈ P25。
- **NFR-4 bucketAccessCounter 零污染**：searchWithExtras 读 positions、snippet build（纯字符串不 I/O）、facet 计算（只走 IDX2 + WeightStore getTf 不扫 DIST_BUCKET）→ counter 前后全等。
- **NFR-5 性能 T6 TR6.2 轻量配置 P95 ≤5000ms**：10 doc × 50 term 写入位置；100 query × K=10，facetTopK=10，snippetWindow=40，returnPositions=true。P95 ≤5000ms（P26 可做 positions 批量缓存回 1000ms）。
- **NFR-6 稳定排序**：10 × searchWithExtras 结果 docId + facetTopK 顺序指纹 Set.size=1 无随机性。

---

## Constraints / Dependencies / Assumptions
- **C-1 P24 零破坏**：P24 ShardRouter / P23 HNSW / P22 7 步 / P21 USER-HC-0 全遵守。
- **C-2 CutUnit start/end BackwardCompat**：原 Seg5/MockJieba Seg SPI 若不返回 start/end，默认 start=seq, end=seq+1（位置 = seq 单字近似，不是 bug，MVP 近似可接受；P25 不修改 FulltextSegmenter 接口签名保证 back compat）。
- **C-3 rawDocTextsByDocId 由前端提供**：DsDb 不存 raw doc 文本（太大），searchWithExtras 入参传 Map<Long,String> rawDocTextsByDocId；null 时 snippet 空（不生成文本但 matchedSpans 仍可给 span 做 UI）。
- **C-4 Facet docCount 基于 IDX2（真实全量）**：不是基于当前 search topK 的 doc 子集，是全局（对所有 doc 该 term 的 docCount），保证 facet 聚合准确不随 query 变化（若需要基于当前结果集 facet，前端自己基于 matchedSpans.count 二次聚合，MVP 不做）。

---

## Open Questions（Default 立即采用）
| OQ | A Default | B Alt | 选 A 原因 |
|----|-----------|-------|-----------|
| Facet 范围 | 全局 IDX2 docCount | 当前 topK result 集 | 全局更准确，UI 侧栏一致 |
| Snippet 高亮 offset 类型 | 相对 snippetText 内部 offset | 相对 rawDocText 全局 offset | 前端 <b> 包直接用，不用再全局定位两次 |
| 位置默认值 | CutUnit 缺 start/end → start=seq,end=seq+1 | 不写位置 miss 抛 IAE | back compat 保护老 Seg |
| snippet null rawDocTexts | snippets 空，matchedSpans 正常 | 整段 rawDocText 不存在抛 IAE | 允许只取 positions/facets 不生成 snippet |
| Facet 默认 level | WORD=1 | SENTENCE=3 | WORD level UI 展示最直观 |

---

## Acceptance Criteria（AC：rule / rubric）
| ID | Type | Verbatim | T/TR |
|----|------|----------|------|
| AC-1 | rule | 编译 0 错 | T0 |
| AC-2 | rule | 位置写入 10 doc：随机抽 3 doc × 3 term → getTermPositionsForTest spans 与 Seg CutUnit 逐 start/end 全等；span 0-based [s,e) 半开，end-s=termText.length（中文多字 term len 匹配）。 | T1 TR1.1/TR1.2 |
| AC-3 | rule | clearDoc 位置幂等：putDoc(D1)→positions miss 空 → write → 非空 → clearDoc(D1) → 再读 positions 空（仅 D1 删，其他 doc 的该 term positions 仍保留）。 | T1 TR1.3/TR1.4 |
| AC-4 | rule | span merge：[0,4][2,6] → [0,6]；[1,2][3,5][7,9] → 两条 [1,5][7,9]（不跨合并 d>0）；snippet 滑窗 raw="中华人民共和国成立于1949年..." × "人民"/"国家" 命中词 snippetText 精确 substring 返回，highlightRangesInSnippet 相对偏移精确（例：snippetText 第 2~4 字符是 "人民" → highlightSpan(2,4)）。 | T3 TR3.1/TR3.2 |
| AC-5 | rule | Facet 10 doc × 5 WORD："国" docCount=5，"中"=4，排序 docCount 降序；totalTf 与 Σ getTf 手算全等 int 精确；同分按 totalTf 降序；再同分 termText 字典序。 | T3 TR3.3/TR3.4 |
| AC-6 | rule | searchWithExtras 参数：rawDocTextsByDocId=null → snippets 空；returnPositions=false → matchedSpans 空；facetTopK=3 → facets.size≤3；K=-1/K=0 → 空。 | T3 TR3.5/TR3.6？→ T3 4 TR 覆盖（合并 TR） |
| AC-7 | rule | putDoc 3× 幂等：positions 逐 span start/end 全等；searchWithExtras 结果 docId + facet 顺序全等（指纹 Set.size=1）。状态漂移 ≤5%。 | T4 TR4.1/TR4.2 |
| AC-8 | rule | clearDoc 保留 termStat/vector/idf：clear D1/D2 后，"国" 的 termStat.idf 非空；HNSW vector 仍存在。 | T4 TR4.3/TR4.4 |
| AC-9 | rule | ZeroLeak3=3；11 CNF；Facade≥52；bucketAccessCounter 零污染（P21 topK(C,3) counter[1]=2 counter[2]=1 不被 searchWithExtras 影响）。 | T5 TR5.1-5.4 |
| AC-10 | rubric | Quality 0~5 ≥4：snippet 精确高亮正确包含命中词且 window=40 合理（2）；facet top3 顺序与手算全相等（2）；matchedSpans 合并正确（1）。≥4 pass。 | T6 TR6.4 |
| AC-11 | rubric | Extensibility 0~5 ≥4：span merge 算法支持跨 level 合并开关（1）；Facet 结果可按 doc 子集（非全局）扩展位（1）；snippet 省略号 "..." 前缀后缀开关（1）；CutUnit start/end 不抛错 fallback 不破坏（1）；facet 多级 level 排序稳定不冲突（1）。≥4 pass。 | T6 TR6.5 |
| AC-12 | rule | P95 ≤5000ms；10× 指纹 Set.size=1；Ingest 30 doc 位置写入 ≤30000ms。 | T6 TR6.1/TR6.2/TR6.3 |

---

§End P25 spec.md REV1。
