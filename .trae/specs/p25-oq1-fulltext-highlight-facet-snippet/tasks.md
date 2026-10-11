# P25 OQ1 全文高亮/Snippet/Facet（term 位置 payload + 滑窗截取 + topK facet）—— tasks.md（**Gate C 验收版 REV1，2026-10-04**）

**Gate 状态（REV1，2026-10-04）**：
- [x] **Gate A（用户 VERBATIM approve Spec/Tasks 全文，2026-10-04 17:01）**
- [x] **Gate B**：两连 Surefire 101 TR（P22×25 + P23×25 + P24×26 + P25×25） 0F0E
  - Run1（2026-10-04 20:20）：Tests=101 Fail=0 Err=0 Skip=0，25m32s
  - Run2（2026-10-04 21:25）：Tests=101 Fail=0 Err=0 Skip=0，31m03s
- [x] **Gate C**：25 TR 0F0E + 洁癖 5 份文档同步 + 自检 9 项全 Y（2026-10-04 21:26）

**§3.1 测试文件命名约定（测试放 ds/ package 非 fulltext 包）**：
- [x] `P25_T1_TermPositionWriteTest`（T1 4/4 TR PASS）
- [x] `P25_T2_PositionSpanMergeTest`（T2 4/4 TR PASS）
- [x] `P25_T3_SnippetFacetExtrasTest`（T3 4/4 TR PASS）
- [x] `P25_T4_PutDocIdempotentClearTest`（T4 4/4 TR PASS）
- [x] `P25_T5_GatekeeperTest`（T5 4/4 TR PASS）
- [x] `P25_T6_NfrQualityTest`（T6 5/5 TR PASS）

**§3.2 累计 25/25 TR 0F0E 明细**：
| T | TR 数 | TR1.x ~ TRy.y | Surefire 实测耗时 |
|---|---|---|---|
| T1 TermPositionWrite | 4 | TR1.1 (CutUnit 全等) / TR1.2 (fallback seq) / TR1.3 (clear 仅 target) / TR1.4 (3×幂等 + 空 get 不崩) | 7.4s |
| T2 PositionSpanMerge | 4 | TR2.1 (重叠/相邻/不跨) / TR2.2 (乱序 sort+merge) / TR2.3 (非法 IAE) / TR2.4 (空 doc 空 spans) | 4.3s |
| T3 SnippetFacetExtras | 4 | TR3.1 (滑窗手算) / TR3.2 (边界夹取) / TR3.3 (三键排序同分"人"<"民") / TR3.4 (参数 6 防御) | 11.2s |
| T4 PutDocIdempotentClear | 4 | TR4.1 (3×幂等 positions+docLen 全等) / TR4.2 (refCount 单调) / TR4.3 (clear 保留 termStat/vec/idf) / TR4.4 (facet docCount 清零后 search 不命中) | 32.7s |
| T5 Gatekeeper | 4 | TR5.1 (bucket sum=0，100+100 search 双套) / TR5.2 (ZeroLeak3=3，内嵌 `$`) / TR5.3 (9 CNFE + 0 netty/grpc) / TR5.4 (Facade=91≥52) | 113.8s |
| T6 NfrQuality | 5 | TR6.1 (Ingest 1898ms≤80s) / TR6.2 (P95 4193ms≤8000ms) / TR6.3 (指纹 2 Set.size=1) / TR6.4 (Quality=5/5, Facade=91) / TR6.5 (Ext=5/5) | 393.6s |
| Σ | 25/25 | 0F0E BUILD SUCCESS（单命令 11:12 min） | 563s (≈9.4 min) |


---

## P25 Gate A 与 P24 REV1 差异 7 条（Gate A 快速定位，Spec 有完整）
1. **WeightStore 新增 term_positions.dshash**：key=mix4(uid,docId,parentId,level,0xDEADBEEF)，value=4B header + 4B count + count×(4B start+4B end)。
2. **updateWeightForLevel 扩写位置**：Seg5 CutUnit.start/end 直接取；老 Seg 缺失默认 start=seq end=seq+1 back-compat。clearDoc Step7 removePositions（仅 doc 级，不删 termStat/idf/vector）。
3. **PositionSpan/SnippetHit/FacetHit/ScoredDocHitWithExtras 4 内嵌类 ZeroLeak3 合规**。Span merge 算法（相邻/重叠合并，d>0 不跨）；Snippet 滑窗 ±snippetWindow/2，按命中数+Σidf 排序留 snippetMaxCount。
4. **Facet 聚合基于 IDX2 全局**：docCount=IDX2 selectByUnit(uid).distinct docId 集合 size；totalTf=Σ doc×(parent,level) WeightStore.getTf。按 docCount↓totalTf↓termText 字典序↑，facetTopK=10。
5. **searchWithExtras 新增主 API**：ScoredDocHitWithExtras（ScoredDocHit core 组合不继承 final），rawDocTextsByDocId null 时 snippet 空，matchedSpans/facets 正常。
6. **SearchOptions 扩 5 字段**：returnPositions=true / facetLevel=WORD=1 / charLevelFacet=false / facetTopK=10 / snippetWindow=40 / snippetMaxCount=3。
7. **T5 Gatekeeper 扩 6 合一（ZeroLeak3 / 11 CNF / Facade≥52 / P21 bucket 零污染 / P22-24 语义零破坏 / HNSW×Shard×Position 三文件独立）**。

---

## 决策表（Gate A 冻结）
| 决策 | A Default | B Alt | 选 A 原因 |
|------|-----------|-------|-----------|
| Facet 范围 | 全局 IDX2（所有 doc） | 当前 topK 结果集 | UI 侧栏稳定一致 |
| snippet highlight 坐标 | 相对 snippetText（内部偏移） | 相对 rawDocText（全局） | 前端 <b> 包直接用，少一次 substring 定位 |
| CutUnit 缺 start/end | 默认 start=seq end=seq+1 | 抛 IAE 强依赖 Seg 改 | BackwardCompat 老 Seg 不崩 |
| rawDocTextsByDocId=null | snippets 空 matchedSpans 正常 | IAE 必须提供 raw | 允许只拿 positions/facets 不生成 snippet |
| Facet 默认 level | WORD=1 | SENTENCE=3 | WORD level UI 侧栏最直观 |

---

## 任务清单（6T 25 TR；T1→T2→T3→T4→T5→T6 串行；TemporaryFolder + p2p.db.home 隔离）

### Task 1: 位置 payload 写入 + removePositions（4 TR，AC-2/3）
**Priority**: high
**Status**: pending
**Scope**:
- WeightStore 扩：`putPositions(uid, docId, parentId, level, List<PositionSpan>)` / `getPositions(uid, docId, parentId, level)` / `removePositions(uid, docId, parentId, level)`。
- 位置 key=FarmHash64.mix4(uid, docId, parentId, (long)level, 0xDEADBEEFL)。
- updateWeightForLevel 末尾写 positions：CutUnit.start/end 可用则用，否则默认 start=seq, end=seq+1。
- HC probe：`getTermPositionsForTest`。

**Test Requirements (TR)**：
| # | Type | Verbatim | AC |
|---|------|----------|----|
| TR1.1 | rule | 10 doc × 10 WORD term 写入：随机抽 3 doc × 3 term → getTermPositionsForTest 返回 spans 与 Seg5 CutUnit 原始 start/end 逐 int 全等；每 span end-start = termText.length（"中"=1, "中国"=2 校验）。 | AC-2 |
| TR1.2 | rule | **缺 start/end back-compat**：老 Seg（MockNoOffsetSeg SPI priority=999 覆盖，不提供 start）→ P25 默认 start=seq end=seq+1 → span 逐 seq 全等（不抛错）。 | AC-2 FR-1.4 |
| TR1.3 | rule | **幂等清理**：putDoc(D0) → positions non-empty → clearDoc(D0) → getTermPositionsForTest(u_zhong, D0, parent, lv) 返回 empty；putDoc(D1) 同 term → D1 positions non-empty（仅删 D0，不影响其他 doc）。 | AC-3 |
| TR1.4 | rule | **3× 幂等写入**：同 doc × 同 text × 3 putDoc → positions span 集合逐 start/end 全等（无重复 write 膨胀 DsFixedBucketStore）。空 WeightStore 不崩：getPositions(9999, 888, 0, 1) 返 empty list（不 null）。 | AC-3 AC-7 |

---

### Task 2: Span Merge 算法 + Position 边界（4 TR，AC-4）
**Priority**: high
**Status**: pending
**Scope**:
- `mergeSpans(List<PositionSpan>)`：按 start 升序 → 相邻/重叠合并：
  - [a,b][c,d] 若 c ≤ b（重叠）→ 合并为 [a, max(b,d)]
  - 若 c = b（相邻）→ 合并为 [a,d]
  - 若 c > b（不相邻不重叠）→ 两条独立保留
- HC probe：`mergeSpansForTest` 公共方法。

**Test Requirements (TR)**：
| # | Type | Verbatim | AC |
|---|------|----------|----|
| TR2.1 | rule | 重叠合并：[0,4][2,6] → 合并为 [0,6]（1 span）；[1,2][3,5][7,9] → 合并为 [1,5],[7,9]（2 spans）；[0,1][1,2][2,3]（相邻）→ [0,3]（1 span）。 | AC-4 |
| TR2.2 | rule | 空列表返空；1 span 返原样；乱序 spans：[7,9][0,4][2,6] 先 sort start 再 merge → 结果同上（合并前必排序）。 | AC-4 |
| TR2.3 | rule | **非法 span 保护**：start<0 抛 IAE；end≤start 抛 IAE；null spans 抛 IAE。 | FR-1 |
| TR2.4 | rule | **空字符串边界**：doc text=""（长度 0）→ 无任何 span；Seg 不切分时 positions 为空。 | FR-1.4 |

---

### Task 3: Snippet 滑窗 + Facet 聚合 + searchWithExtras（4 TR，AC-4/5/6）
**Priority**: high
**Status**: pending
**Scope**:
- buildSnippetsForTest(String rawDocText, List<PositionSpan> spans, SearchOptions o) → List<SnippetHit>
- computeFacetsForTest(List<Long> docIds, int level) → List<FacetHit>
- searchWithExtras / ScoredDocHitWithExtras 主 API 骨架
- HC probes：mergeSpansForTest 复用、buildSnippetsForTest、computeFacetsForTest、sortFacetsForTest

**Test Requirements (TR)**：
| # | Type | Verbatim | AC |
|---|------|----------|----|
| TR3.1 | rule | **Snippet 滑窗手算**：raw = "0123456789".repeat(5)（50 字符，索引 0~49）；spans = [PositionSpan(10,12,"人民"), PositionSpan(25,27,"国家")]；snippetWindow=10（左右 5）。<br>snippet1 center=(10+12)/2=11 → 窗口 [6,16]，highlightRangesInSnippet = 10-6=4 → (4,6)。<br>snippet2 center=26 → [21,31]，highlightRangesInSnippet = 25-21=4 → (4,6)。<br>snippetText ×2 = raw.substring(6,16) + substring(21,31) 逐字全等；snippetMaxCount=3。 | AC-4 |
| TR3.2 | rule | **Snippet 边界夹取**：span(0,2) center=1 window=10 → 窗口 [max(0,1-5)=0, 1+5=6] = [0,6]（左边界夹到 0）；span(47,49) center=48 window=10 → [43, min(50,53)=50] = [43,50]（右边界夹到 rawLen）。 | AC-4 |
| TR3.3 | rule | **Facet 手算全等**：10 doc × 5 term（中/国/人/民/共），设 "国" docCount=5，totalTf=Σ tf = 6（出现 6 次）；"中" docCount=4 totalTf=5；"民"=3/4；"人"=2/3；"共"=1/1。computeFacetsForTest(level=WORD=1) 返回顺序：`国(5,6) → 中(4,5) → 民(3,4) → 人(2,3) → 共(1,1)`；**同分测试**：两 term docCount=3 totalTf=4 → 字典序升序（"人"< "民"，人在前）。 | AC-5 |
| TR3.4 | rule | **searchWithExtras 参数防御**：K=0 / K=-1 → 空；q=null+"" 且 qUids 空 → 空；returnPositions=false → matchedSpans 空；rawDocTextsByDocId=null → snippets 空（matchedSpans/facets 正常）；facetTopK=3 → facets.size()≤3。 | AC-6 |

---

### Task 4: putDoc 3× 幂等 + clearDoc 保留 termStat/vector/idf（4 TR，AC-7/8）
**Priority**: high
**Status**: pending
**Scope**: 复用 P22/P23/P24 T4 脚手架。

**Test Requirements (TR)**：
| # | Type | Verbatim | AC |
|---|------|----------|----|
| TR4.1 | rule | 3× putDoc(D1, U2 fullText)：① positions 逐 span start/end 全等（P25 扩）；② termFreq/docLen/nTerms 全等（P22/P23）；③ HnswMeta.nodeCount/entryPointUid 全等（P23）；④ searchWithExtras 返回 docId 集合 + facet order 指纹 Set.size=1（P25）。 | AC-7 |
| TR4.2 | rule | 状态漂移：SHARED 跨 3 doc × 3 状态，单态内极差率 ≤5%（同 P22 T4 TR4.2）。 | AC-7 |
| TR4.3 | rule | **clearDoc 保留 termStat/vector/idf**：indexTermVector(KEEPA, v) + putDoc(D1/D2) → recomputeIdf → idf = log((2-2+0.5)/(2+0.5)+1) → clear D1 + clear D2。<br>getTermStatForTest(KEEPA).idf 1e-9 与 clear 前全等；getTermVectorForTest(KEEPA) 逐元素 1e-9 全等；positions 对 D1/D2 空（仅 term_stat/vector 保留）。 | AC-8 |
| TR4.4 | rule | **search 不命中 cleared doc**：clearDoc(D1) + clearDoc(D2) 后，searchWithExtras("KEEPA", K=10, rawMap, opts) 结果中 docId 集合不包含 D1/D2；facet 中 "KEEPA" 的 docCount=0（全局 IDX2 无 doc），totalTf=0。 | AC-8 |

---

### Task 5: Gatekeeper 六合一（ZeroLeak3 / 11 CNF / Facade≥52 / P21 bucket 零污染 / P22-24 语义零破坏 / 三文件独立）（4 TR，AC-9）
**Priority**: high
**Status**: pending
**Scope**: 复用 P24 T5 + 扩展 Facade≥52。

**Test Requirements (TR)**：
| # | Type | Verbatim | AC |
|---|------|----------|----|
| TR5.1 | rule | **P21 bucket 零污染**：A..F 6 uid buildDistForSeq topK(C,3) 调 counter[1]=2/counter[2]=1；接着 100 × searchWithExtras（轻量 vectorRecall=true neighborRecallD=0 phraseBoost=false returnPositions=true），再调 topK(C,3) 前后 counter 逐 byte 全等（snippet 字符串截取 / facet 计算不 touch DIST_BUCKET）。P22 T5 TR5.2 ZeroLeak3 + P23 HNSW 自一致性 均通过在同 JUnit 类内重复调用。 | AC-9 NFR-4 |
| TR5.2 | rule | **ZeroLeak3**：fulltext 独立类 = 3。ScoredDocHitWithExtras/PositionSpan/SnippetHit/FacetHit 全类名含 `$` 内嵌。 | AC-9 NFR-1 |
| TR5.3 | rule | **NG-3 11 CNF**：P22(5) + P23(4) + P24(2) = 11。新增 2 个 netty/gRPC，P25 不新增 CNF 类。全 11 Class.forName() 全 ClassNotFoundException（NoClassDefFoundError 算泄漏）。 | AC-9 NFR-2 |
| TR5.4 | rule | **Facade≥52 子集非递减**：P24 ≥44（TermDict≥6 + Fulltext≥38）。P25 加 searchWithExtras / searchClusterWithExtras / mergeSpansForTest / buildSnippetsForTest / computeFacetsForTest / getTermPositionsForTest / sortFacetsForTest 至少 7 个 → 合计 ≥52。P24 基线 30 Fulltext public 方法名全 ∈ P25。 | AC-9 NFR-3 |

---

### Task 6: NFR 性能 + 质量 Rubric（5 TR，AC-10/11/12）
**Priority**: high
**Status**: pending
**Scope**: 复用 P24 T6 脚手架 + 10 doc × 50 term。

**Test Requirements (TR)**：
| # | Type | Verbatim | AC |
|---|------|----------|----|
| TR6.1 | rule | Ingest 30 doc × 50 term WORD positions + P23 indexTermVector(dim=768) + P24 4 shard putDocCluster 总耗时 ≤30000ms；Bloat 10KB~800MB。 | AC-12 |
| TR6.2 | rule | 轻量配置（returnPositions=true/neighborRecallD=0/phraseBoost=false/concurrentScatter=false/facetTopK=10/snippetWindow=40）100 query P95 ≤5000ms。 | AC-12 NFR-5 |
| TR6.3 | rule | **稳定排序**：10 × searchWithExtras → 两个指纹 Set：① docId 集合；② facets 前 3 termText 拼接字符串集合 → 两 Set 都 size=1（无随机性）。 | AC-12 NFR-6 |
| TR6.4 | rubric | **Quality 0~5 ≥4**：<br>① snippet 精确高亮 3 个 rawDoc × 2 span → 每 snippetText 包含命中词 substring，highlightRangesInSnippet 语义正确 3×1=3 分（2 错=2, 1 错=1, 全错=0）<br>② facet top3 顺序与手算全相等 → 1 分<br>③ matchedSpans 合并 3 个测试用例（重叠/相邻/不跨）全对 → 1 分<br>≥4 pass。 | AC-10 |
| TR6.5 | rubric | **Extensibility 0~5 ≥4**：<br>① mergeSpans 可加 maxSpanGap 参数（跨 gap≤2 自动合并）不破坏 → 1<br>② Facet 支持 "按当前结果集 docIds 子集"（参数子集）不破坏 → 1<br>③ snippet 前后省略号开关（options.snippetEllipsis=true/false，默认为 false）不破坏 → 1<br>④ CutUnit 缺 start/end fallback 不改 Seg SPI 签名 → 1<br>⑤ multi-level facet（WORD + SENTENCE 并返回）无冲突 → 1<br>≥4 pass。 | AC-11 |

---

§End tasks.md REV1。
