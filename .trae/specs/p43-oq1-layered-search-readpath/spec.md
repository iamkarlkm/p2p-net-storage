# P43 OQ1 Fulltext 搜索读路径切换（Slice B：`FulltextStore.search` 改走 `LayeredInvertedView`）—— spec.md（**REV1 GATE A 已批准**）

> 用户 OQ12-㉑ 选 ①（承接 OQ12-⑳ = P42 分层倒排索引段化 Slice A，已永久闭环）→ SP3.1 Gate A（本文件 + tasks.md）→ **用户 VERBATIM 批准（采纳 §0 方案 A）** → Implement T1-T6 → Gate B 拆批回归（76+80+100+410 = **666 run**，仅 1 已知基线 fail）→ Gate C 洁癖 5/5（9Y 9/9）→ Stage 5 commit + push。
>
> **Gate A 已批准（2026-10-25）：采纳 §0 方案 A（混合只读切换：stat 列视图优先 + WeightStore 回退；`searchCore` 6 处等价替换）。**
>
> **Gate B / Gate C 已闭环（2026-10-26）：** P43 own 31/31 0F0E + 4 批全量回归 666 run；9Y 自检 9/9 `[x]`；§10 实装偏差表已回填。

## Gate C 9Y 自检清单（Gate C 逐项置 `[x]` 并附实锤测试名）

- [x] Y1 **ZeroLeak3=3 + 六重递归文件数守恒 + P43 Δ0 新增源文件**：`fulltext/` 根包 top-level 仍 = 3；**递归**源文件数 `segment/`=5、`cluster/parser/v3/`=5、`cluster/agg/`=6、`compact/`=5、`cluster/groupby/`=5、`layered/`=3 全部不变；P43 新增 `.java` 数 = **0** → `P43_T5.y1_ZeroLeak3HexCountAndZeroNewFile`
- [x] Y2 NG-3 零依赖：0 新增 netty/grpc/p2p.client 引用 + `p2p-db/pom.xml` 0 新增 dep/plugin → `P43_T5.y2_Ng3_ImportsJdkAndFirstPartyOnly`
- [x] Y3 TOPK 铁律：0 改 K、0 触 `SearchOptions`、`bucketAccessCounter` 0 污染（视图读段，不经 DsHashMap bucket） → `P43_T5.y3_TopKGuardrails_NoBucketCounterNoSearchOptions`
- [x] Y4 Facade 单调不减（P43 例外：P21 追加 + `searchCore` 外科替换）：P37 `segment/` 5 类 + P40 `compact/` 5 类 + P41 `groupby/` 5 类 + P42 `layered/` 3 类源码 **0 改**；P21~P42 公开签名 0 改；`FulltextStore` 仅 **append-only** 追加 3 个挂载签名（`attachLayeredView`/`detachLayeredView`/`hasLayeredView`）→ `P43_T5.y4_FacadeMonotonic_LayeredAndPriorIntact`
- [x] Y5 路由公式：0 改 ShardRouter / P24 UNIT·DOC 路由 → `P43_T5.y5_RoutingFormula_ShardRouterIntact`
- [x] Y6 P21~P43 编译兼容：既有类型 0 改 + `segment/` 5 类 + `compact/` 5 类 + `layered/` 3 类全在 → `P43_T5.y6_P21ToP43CompileCompat_ClassesExist`
- [x] Y7 HybridHit ctor=2：0 改 → `P43_T5.y7_HybridHit_ConstructorCountIs2`
- [x] Y8 ShardTransport 5 方法：0 改 → `P43_T5.y8_ShardTransport_5OriginalMethodsIntact`
- [x] Y9 0 TODO/FIXME/HACK（P43 触碰的 `FulltextStore` 增量区）→ `P43_T5.y9_NoTodoFixmeHack`

> **9Y 自检实锤**：`P43_T5_Gatekeeper9YTest` 实跑 **9/9 0F0E**（`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`）。
>
> **Gate C 结论（2026-10-26）**：9Y 自检 **9/9 `[x]`**；洁癖 5/5 完成（本 spec §5.3 回填 + tasks.md + Roadmap `OQ12-㉑` ✅ + Project Memory + 红线审计）。

---

## 0. 需用户裁决（Gate A 的唯一开放项，批准即冻结）

> **核心张力（Probe 实锤）**：P42 段只含 **3 列**（`docStat` / `termStat` / `tf`），**不含 IDX 结构**；而 `FulltextStore.searchCore` 的**候选召回 100% 依赖 IDX1/IDX2/IDX3**（`selectByUnit(uid)` / `selectByParentLevel(docId,parentId,level)` / `selectByDocLevel(docId,level)`），且 tf 键 `FarmHash64.mix4(uid,docId,parentId,level)` 为**四元全混合**（**不保 uid 高位**）→ `DsIndexSegmentReader.scanTfPrefix` **无法按 uid 前缀反查**。因此「全段候选召回」必须**新增段列**（触 R5 段格式红线）。
>
> 结论：Slice B 的候选召回**必须保留 IDX1/2/3**；段视图只能接管 **stat 列点查**（`docLen` / `idf` / `tf`）。

| 方案 | 做法 | 代价 | 结论 |
|------|------|------|------|
| **A（推荐）** | **混合只读切换（Hybrid Stat-Column Switch）**：候选召回**保持 IDX1/2/3 不变**；把 `searchCore` 内 **3 类 stat 点查**（`getTermStat`(存在性+idf) / `getTf` / `getDocStat`(.docLen)，共 6 处调用点）改为经 **视图优先 → WeightStore 回退** 的 private helper。P21 追加 **3 个 append-only 挂载签名**（`attachLayeredView`/`detachLayeredView`/`hasLayeredView`）。**段格式 0 变更**；attach 忠实快照后输出 **bit 级全等**（R7） | P21 源码 **+约 40 行**（3 签名 + 3 helper + 6 处点查替换）；不再满足「P21 字节级 0 改」 | **建议采纳** |
| B | **全段候选召回（Segment-Only Recall）**：段新增 uid→postings 倒排列（**新列 + 新 `VERSION=2`**），候选 100% 从段取、彻底摆脱 IDX | **触 R5 段格式红线** → P37 `segment/` 5 类（`SegmentColumnCodec`/`Writer`/`Reader`）须改 → **破坏 R4 单调不减 + 六重文件数守恒**；需重跑 P37/P40 全量基线 | 不采纳 |
| C | **并行只读入口（0 触碰 `searchCore`）**：`FulltextStore.search` 0 改，新增 `searchBySegment(...)` 复制 `searchCore` 逻辑走视图 | 复制 ≈ 150 行 → **触发 P42/P43 NFR 的 CPD 160 字符 0 重复块红线**；若抽公共 Σ 逻辑则等价于方案 A 的 `searchCore` 改动 → **实际退化为 A** | 不采纳（降级为 A） |

> **若用户否决方案 A**：P43 降级为「**视图旁观**」极小切片 —— 仅追加 `attachLayeredView`/`detachLayeredView`/`hasLayeredView` 三签名（纯状态位，**search 读路径 0 改**），测试仅覆盖挂载/卸载幂等；本 spec §1/§3/§4/§5 需整体降级重写，Gate A 重新提交。请在 Gate A 签批区明确书写所选方案。

---

## 1. Scope（用户 OQ12-㉑ 候选项 ①）

> 原文：`① Fulltext 搜索读路径切换（Slice B：FulltextStore.search 改走 LayeredInvertedView，红线高建议单独 Gate A 评估）`

**P43 = Slice B（搜索读路径切换）**：让 `FulltextStore` 的**打分统计点查**（`idf` / `tf` / `docLen`）在**已挂载** P42 `LayeredInvertedView` 时**优先从列式段读取**，未挂载 / 视图 miss 时回退 P22 `WeightStore`（内存/持久 DsHashMap）；**候选召回（IDX1/2/3）、avgdl、phraseBoost、neighborRecall 全部不动**。使 P42 段视图**首次真正接管搜索热路径的 stat 读取**，具备「列式 + 可被 P40 compaction 管理 + 可跨机分发的打分读面」。

1. **P21 `FulltextStore` 追加（append-only）**：`attachLayeredView(LayeredInvertedView)` / `detachLayeredView()` / `hasLayeredView()` 三签名（挂载状态位，非线程安全单写者语义）。
2. **`searchCore` 外科式替换（3 类 stat / 6 处调用点）**：`getTermStat`(Step0 存在性 + idfMap) / `getTf`(R1 召回 / sortScore / unitIdHit) / `getDocStat`(sortScore docLen) → 经 private helper `viewDocLen` / `viewIdf` / `viewTf`（**视图命中优先，miss/无视图回退 WeightStore**）。
3. **等价性铁律（R7）**：attach 忠实快照视图（与当前 WeightStore 同源）后，`search` / `searchByUidsForTest` 输出与未 attach **逐字段 bit 级全等**（docId 序 + `finalScore`/`sortScore`/`rrfScore`/`phraseScore` `Double.doubleToLongBits` + `r1MatchedTerms` + `unitIdHit`）。

**「读路径切换」如何被承载（关键）**：候选集仍由 IDX 提供（**行**召回，已是持久结构）；**列**统计（打分输入）由段提供 —— 二者解耦后，搜索热路径**首次可完全不读 WeightStore 的 3 张 DsHashMap**（视图全命中时），且段读**不经 `bucketAccessCounter`**（R3 天然更强）。

**MVP 边界（0 越界）**：
- 仅做「stat 列点查（`idf`/`tf`/`docLen`）视图优先 + WeightStore 回退 + 挂载状态位」
- **不做** 段格式变更（R5：复用 P37 `MAGIC="DIDX"`/`VERSION=1`，0 新增列/magic/version 分支）
- **不做** 候选召回段化：`selectByUnit` / `selectByParentLevel` / `selectByDocLevel` / `selectByUnitHit` / `neighborsWithinD` / `computePhraseBoost` / `recomputeIdfForTerms` 全部 0 改（仍走 IDX1/2/3）
- **不做** avgdl 段化：`computeAvgDocLen(level)` 仍扫 WeightStore `getDocStat`（0 改；段视图无「avgdl 聚合」能力）
- **不做** 写路径切换：`ingestDoc` / `putDoc` / `clearDoc` / `updateWeightForLevel` 仍 100% 写 P22 WeightStore（0 改）
- **不做** WeightStore 淘汰/驱逐（0 eviction；视图与 WeightStore 双活，miss 回退保证正确性；内存收益评估列入后续 Slice C）
- **不做** 自动导出 / 定时器 / 后台线程（**0 新增线程**；视图须由调用方显式 `export` + `open` + `attach`）
- **不做** 向量路径切换（`searchWithVectorCore` 的 `getTermVector` 不在段内 → 保持 WeightStore，0 改）
- **不做** 跨段词网聚合（P24 DIST / `neighborsWithin` 依赖 positions，不在段内）
- P37 `segment/` 5 类 0 改；P40 `compact/` 5 类 0 改；P41 `groupby/` 5 类 0 改；P42 `layered/` 3 类 0 改；P22 `WeightStore` 0 改；`TermDictStore` / `FulltextSegmenter` 0 改
- **P21 `FulltextStore` 仅：append-only 追加 3 挂载签名（R4）+ `searchCore` 内 6 处 stat 点查外科替换（R4 例外 2，见 §0）**
- **0 新增源文件**（六重递归文件数守恒 + ZeroLeak3=3 天然保持）
- **0 新增 Maven dep**

---

## 2. 红线（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3 + 六重文件数守恒（递归）+ P43 Δ0** | fulltext 根包 top-level 类数严格 = 3（`{TermDictStore, FulltextStore, FulltextSegmenter}`）；**递归**源文件数 `segment/`=5、`cluster/parser/v3/`=5、`cluster/agg/`=6、`compact/`=5、`cluster/groupby/`=5、`layered/`=3 全部不变；**P43 新增 `.java` 文件数 = 0** |
| R2 | **NG-3 零依赖** | `p2p-db/pom.xml` 0 新增 dep/plugin；P43 触碰源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*`；仅 JDK + first-party import |
| R3 | **TOPK 铁律** | 0 改 K / 0 改 `SearchOptions`；`bucketAccessCounter[1025]` 0 污染（视图读段**不经 DsHashMap bucket** → 天然更强） |
| R4 | **Facade 单调不减（P43 例外 2）** | P37 `segment/` 5 类、P40 `compact/` 5 类、P41 `groupby/` 5 类、P42 `layered/` 3 类源码**字节级 0 改**；P21 / P22 `WeightStore` / P23~P42 公开签名 0 改；**P21 仅 append-only 追加 3 挂载签名，且 `searchCore` 内 6 处 stat 点查等价替换（默认无视图时行为字节级不变）**（§0 方案 A） |
| R5 | **段格式 0 变更** | 复用 P37 `MAGIC="DIDX"` + `VERSION=1` + `HEADER_SIZE=4096` + `CRC_SECTIONS=4`；0 新增列 / 0 新增 magic / version 分支 |
| R6 | **USER-HC-0 与 TermDict 零触碰** | `TermDictStore.toUid(String, boolean)` **永远无 level 参数**；`TermDictStore` / `FulltextSegmenter` 源码 0 改；视图 `tf` 内部 `mix4(uid,docId,parentId,level)` 与 P22 写入端一致，**0 重编码** |
| R7 | **切换等价性（P43 核心新红线）** | attach 忠实快照视图后，`search` / `searchByUidsForTest` 输出与未 attach **bit 级全等**：docId 顺序 + `finalScore`/`sortScore`/`rrfScore`/`phraseScore`（`Double.doubleToLongBits`）+ `r1MatchedTerms` + `unitIdHit` 全字段一致 |
| R8 | **回退语义确定性** | 读 stat 顺序 = **视图优先 → WeightStore 回退**；视图 `hasTerm(uid) == false` → idf 回退；视图 `hasDoc(docId) == false` → docLen/tf 回退；**无视图 → 100% WeightStore（默认态，与 P21~P42 字节级同行为）** |
| R9 | **候选召回 0 改** | `selectByUnit` / `selectByParentLevel` / `selectByDocLevel` / `selectByUnitHit` / `neighborsWithinD` / `computePhraseBoost` / `computeAvgDocLen` / `recomputeIdfForTerms` 源码 0 改（仍走 IDX1/2/3 + WeightStore） |
| R10 | **零重建 & 0 新线程** | `attachLayeredView` 为纯状态位（O(1)，**不触发重建/导出/扫描**）；视图零重建由 P42 `LayeredInvertedView.open` 保证；0 新增 `ExecutorService` / `Timer` / 后台线程 |

---

## 3. 架构（0 新增源文件；P21 `FulltextStore` append-only + 6 处外科替换）

### 3.1 变更清单

| 名称 | 类型 | 变更 | 关键内容 |
|------|------|------|---------|
| `FulltextStore` | P21 既有类（唯一触碰点） | **append-only + 外科替换** | +`layeredView`(volatile 字段) / +`attachLayeredView` / +`detachLayeredView` / +`hasLayeredView` / +private `viewDocLen`·`viewIdf`·`viewTf` / `searchCore` 内 6 处 stat 点查替换 |
| 其余全部 | P22~P42 既有 | **0 改** | `WeightStore` / `segment/` / `compact/` / `layered/` / `cluster/**` / `TermDictStore` / `FulltextSegmenter` |

> **为何 0 新增源文件**：六重递归文件数守卫（`P37_T5`/`P38_T5`/`P39_T5`/`P40_T5`/`P41_T5`/`P42_T5` 的 `collectJavaFiles` 递归断言）已把 `segment/` `parser/v3/` `agg/` `compact/` `groupby/` `layered/` 六棵子树全部钉死；而**根包受 ZeroLeak3=3 约束** → 系统内**无处**可放新类 → P43 只能「就地扩展 P21」。

### 3.2 数据流（读路径切换）

```
默认态（0 改，与 P21~P42 字节级同行为）
  FulltextStore.search(query, K, opts)
      └→ searchCore: 候选 = IDX1/2/3；stat = weightStore.getTermStat/getTf/getDocStat

切换态（P43，显式 attach）
  new LayeredIndexExporter().export(ft, store, segId)   ← P42，产出 P37 段
  ft.attachLayeredView(LayeredInvertedView.open(store, new long[]{segId}))
      └→ searchCore: 候选 = IDX1/2/3（0 改）
                     stat: viewIdf(view 优先) / viewTf(view 优先) / viewDocLen(view 优先)
                           └→ miss/hasDoc==false/hasTerm==false/no-view ⇒ 回退 weightStore.*
      ⇒ finalScore 与「默认态」bit 级全等（R7：视图为同源快照 ⇒ 取值恒等）
```

### 3.3 包结构（0 新增，仅 1 处就地扩展）

```
com/q3lives/ds/database/fulltext/
├── FulltextSegmenter.java          (P21 既有，0 改)
├── FulltextStore.java              (P21 既有；**仅 +挂载签名/字段/helper + searchCore 6 处点查替换**，见 R4/§0 方案 A)
├── TermDictStore.java              (P21 既有，0 改)
├── segment/                        (P37 既有，5 源文件，0 改；递归计数锁定 5)
├── compact/                        (P40 既有，5 源文件，0 改；递归计数锁定 5)
├── cluster/                        (P26~P41 既有，0 改)
│   ├── parser/v3/                  (P38 既有；递归计数锁定 5)
│   ├── agg/                        (P39 既有；递归计数锁定 6)
│   └── groupby/                    (P41 既有；递归计数锁定 5)
└── layered/                        (P42 既有，3 源文件，0 改；递归计数锁定 3)
```

---

## 4. 接口契约

### 4.0 P21 `FulltextStore` 追加（R4 例外，append-only）

```java
/** P43 R4：挂载分层倒排只读视图（读路径 stat 列视图优先，miss 回退 WeightStore）。 */
public void attachLayeredView(LayeredInvertedView view);   // view == null → IAE；已挂载 → IAE（须先 detachLayeredView）
/** P43 R4：卸载视图（幂等；未挂载时 0 副作用）。 */
public void detachLayeredView();
/** P43 R4：是否已挂载视图（只读状态位）。 */
public boolean hasLayeredView();
```

### 4.1 `searchCore` 外科替换（3 类 stat / 6 处调用点）

| 原调用点 | 原文 | 替换为 |
|---------|------|-------|
| Step0 存在性（L2105） | `weightStore.getTermStat(u) == null` | `!viewHasTerm(u) && weightStore.getTermStat(u) == null` |
| idfMap（L2115） | `weightStore.getTermStat(u)` → idf | `viewIdf(u)`（视图优先） |
| R1 tf（L2142） | `weightStore.getTf(qUid, docId, pId, LV)` | `viewTf(qUid, docId, pId, LV)` |
| sortScore docLen（L2172） | `weightStore.getDocStat(docId).docLen[LV]` | `viewDocLen(docId, LV)` |
| sortScore tf（L2180） | `weightStore.getTf(qUid, docId, pId, LV)` | `viewTf(qUid, docId, pId, LV)` |
| unitIdHit tf（L2248） | `weightStore.getTf(u, docId, pId, LV) > 0` | `viewTf(u, docId, pId, LV) > 0` |

```java
// private helper（P21 内新增；无视图 → 直接回退，保证默认态字节级同行为）
private int    viewDocLen(long docId, int level)   { V v = layeredView; return (v != null && v.hasDoc(docId))  ? v.docLen(docId, level) : weightStore.getDocStat(docId) ... ; }
private double viewIdf(long uid)                   { V v = layeredView; return (v != null && v.hasTerm(uid))   ? v.idf(uid)             : weightStore.getTermStat(uid) ... ; }
private int    viewTf(long uid, long docId, long parentId, int level) { V v = layeredView; return (v != null && v.hasDoc(docId)) ? v.tf(uid, docId, parentId, level) : weightStore.getTf(...); }
private boolean viewHasTerm(long uid)              { V v = layeredView; return v != null && v.hasTerm(uid); }
```

> **`searchWithVectorCore` 0 改**（向量路径的 `getTermVector` / `getTermStat` 不在段内，保持在 WeightStore）。

### 4.2 `LayeredInvertedView`（P42 既有，**0 改**，直接复用）

```java
public static LayeredInvertedView open(DsIndexSegmentStore store, long[] segIdsOldToNew) throws IOException;
public int     docLen(long docId, int level);   // miss = 0
public double  idf(long uid);                   // miss = 0.0
public int     tf(long uid, long docId, long parentId, int level);  // miss = 0（P22 写入端 tf ≥ 1 ⇒ tf==0 ⟺ miss）
public boolean hasDoc(long docId);
public boolean hasTerm(long uid);
public void    close();                          // 幂等
```

---

## 5. 测试（Gate B 拆 4 批：P22-P24 + P25-P27 + P28-P30 + P31-P43）

### 5.1 Surefire 4 批拆分（继承 P40/P41/P42 模板，新增 P43 入批 4）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
# 批1: P22-P24
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
# 批2: P25-P27
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P25_*,ds.P26_*,ds.P27_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
# 批3: P28-P30
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P28_*,ds.P29_*,ds.P30_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
# 批4: P31-P43（含 P43 own）
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*,ds.P42_*,ds.P43_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

> **每批前必须先清理 `target\tmp` 与仓库根 `.tmp` + `surefire-reports`**（P39/P40/P41/P42 教训：累积磁盘 / 并发 run 报告串扰会导致 NFR 计时假失败与 TR 数字异常）；改源码或测试后必须先 `test-compile`。

### 5.2 TR 总数 = 31（T1×5 + T2×6 + T3×3 + T4×3 + T5×9 + T6×5）

| T | 范围 | TR 数 | 实装内容 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | 挂载骨架 | 5 | ① `attachLayeredView(null)` IAE ② 初始 `hasLayeredView()==false` ③ attach 后 `true` + `detach` 后 `false` ④ `detachLayeredView()` 幂等（未挂载时 0 副作用不抛）⑤ 重复 attach IAE（须先 detach） | `P43_T1_AttachSkeletonTest` |
| **T2** | 切换 bit 级等价性 | 6 | 忠实快照视图 attach 后 vs 未 attach 逐字段全等（docId 序 + 4 分数 `doubleToLongBits` + `r1MatchedTerms` + `unitIdHit`）：① 单 term K=10 ② 多 term ③ `searchByUidsForTest` ④ `phraseBoost=true` + `neighborRecallD>0` ⑤ 无命中 query 双空 ⑥ `idfOverrideByUnitId` 路径 | `P43_T2_ReadpathEquivalenceTest` |
| **T3** | 回退语义 | 3 | ① 陈旧视图（export 后再 `ingestDoc` 新文档）→ 新旧混合查询结果 == 无视图（新文档经回退正确召回/打分）② 视图缺 term/doc（`hasTerm`/`hasDoc` false）→ idf/docLen/tf 回退 WeightStore ③ `detach` 后行为 == 从未 attach（幂等回退） | `P43_T3_FallbackSemanticsTest` |
| **T4** | E2E + 跨实例/进程 | 3 | ① 真实 `FulltextStore`（独立 temp home + 唯一后缀）ingest → `export` → attach → `search` 等价 + `detach` 后仍等价 ② `ft.close()` → 新 `FulltextStore` 实例 + `LayeredInvertedView.open` → attach → `searchByUidsForTest` 与旧实例全等（跨实例）③ 跨进程 attach 后 `search` 结果 fingerprint 64-hex 一致（子 JVM spawn） | `P43_T4_E2eReadpathSwitchTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 + 六重递归文件数守恒 + **P43 Δ0 新增 `.java`** + Y2 NG-3（forbidden 引用 + import 白名单 + pom banned 0 命中）+ Y3 TOPK（0 `bucketAccessCounter` / 0 `SearchOptions`）+ Y4 Facade 单调不减（P37/P40/P41/P42 目录源码 grep 0 改 + P21~P42 探针签名全在 + **新增 3 挂载签名存在且旧签名不消失**）+ Y5 路由 0 改 + Y6 P21~P43 编译兼容 + `layered/` 3 类 `Class.forName` + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 方法 + Y9 0 TODO/FIXME/HACK | `P43_T5_Gatekeeper9YTest` |
| **T6** | NFR | 5 | attach 后 `search` P95 ≤ 阈值（1k docs 语料，无视图基线 × 允许退化 ≤ +5% 或绝对阈值，Gate B 实测定档）+ `attach`/`detach` O(1)（1e6 次 P95 ≤ 50ms）+ `FulltextStore` 增量区 Javadoc ≥ 10 行 + CPD 160 字符窗口 0 重复块 + 0 `System.out.println`/`printStackTrace` + 新增 IAE 显式守卫 ≥ 5 | `P43_T6_NFR_Test` |

**TR 总数**：5+6+3+3+9+5 = **31 TR**（实跑数字 Gate B 回填）

### 5.3 验收门槛（Gate B 实跑回填）

- [x] P43 own **31/31 TR 0F0E**（T1 5/5 + T2 6/6 + T3 3/3 + T4 3/3 + T5 9/9 + T6 5/5）→ 实跑 `Tests run: 31, Failures: 0, Errors: 0, Skipped: 0` BUILD SUCCESS
- [x] 批 1 P22-P24 = **76** 0F0E（`Tests run: 76, Failures: 0, Errors: 0, Skipped: 0` BUILD SUCCESS）
- [x] 批 2 P25-P27 = **80** 0F0E（`Tests run: 80, Failures: 0, Errors: 0, Skipped: 0` BUILD SUCCESS）
- [x] 批 3 P28-P30 = **100**（`Tests run: 100, Failures: 1, Errors: 0, Skipped: 0`；唯一失败 = 1 已知基线 fail `P28_T6.tr64_noBarePrintAndGte5NullGuards`，**0E**）
- [x] 批 4 P31-P43 = **410** 0F0E（`Tests run: 410, Failures: 0, Errors: 0, Skipped: 0` BUILD SUCCESS；含 P43 own 31 TR）
- [x] 切换等价性（attach 忠实快照后 `search`/`searchByUidsForTest` bit 级全等；T2 6 TR 逐字段 `doubleToLongBits` 全等）
- [x] 回退语义（陈旧视图 / 视图缺项 / detach 后均与无视图全等；T3 3 TR）
- [x] 段格式 0 变更（复用 `MAGIC="DIDX"`/`VERSION=1`/4×CRC32；P42 `layered/` 3 类源码 0 改）
- [x] 六重递归文件数守恒 + P43 Δ0 新增源文件（Y1 实锤）
- [x] P37 `segment/` + P40 `compact/` + P41 `groupby/` + P42 `layered/` 源码字节级 0 改（Y4 实锤）

> **Gate B 汇总（2026-10-26 实跑）**：批1 76 + 批2 80 + 批3 100 + 批4 410 = **666 run**（其中 1 为已知基线 fail，其余全绿 0E）。

---

## 6. 逆指标（禁止项）

1. **禁止新增源文件**（六重递归文件数 + ZeroLeak3=3 双重锁死；只能就地扩展 P21）。
2. **禁止改段格式**（0 新增列 / magic / version 分支）。
3. **禁止改候选召回路径**（IDX1/2/3 / `neighborsWithinD` / `computePhraseBoost` / `computeAvgDocLen` 一律 0 改）。
4. **禁止改 P21 既有方法签名 / 内嵌类型 / `WeightStore`**（唯一例外 = 3 个 append-only 挂载签名 + `searchCore` 内 6 处 stat 点查等价替换）。
5. **禁止隐式导出/重建**：`attachLayeredView` 不得触发 `export` / `open` / 任何扫描（纯状态位）。
6. **禁止 `attach` 变更默认态行为**：无视图时输出必须与 P21~P42 字节级一致。
7. **禁止触碰 `searchWithVectorCore`**（向量路径仍走 WeightStore）。
8. **禁止后台线程 / 定时器 / 自动导出**（0 新增 `ExecutorService` / `Timer`）。

---

## 7. 风险与回退

| 风险 | 影响 | 缓解 / 回退 |
|------|------|------------|
| R4 触碰 `searchCore` 被用户否决 | Gate A 需重写 | §0 已给方案 A/B/C 与降级路径（纯挂载状态位，读路径 0 改） |
| 视图陈旧导致打分偏差 | 查询结果错误 | R8：视图 miss（`hasDoc`/`hasTerm` false）→ 回退 WeightStore；T3 专项覆盖「陈旧视图」 |
| `hasDoc(docId)` 命中但该 level 槽未写 | docLen 读到 0 而非回退 | P22 `putDocStat` 全 6 level 同批写入（`updateWeightForLevel` merge 其它 level），故 doc 命中 ⇒ 6 level 全有效；T3 覆盖 |
| 视图双活导致内存收益为 0（MVP 不驱逐 WeightStore） | 无内存下降 | MVP 只验证读路径正确性 + 等价性；驱逐/内存收益列入后续 Slice C（需单独 Gate A） |
| 等价性受浮点非确定性影响 | bit 级断言脆弱 | 视图为**同源快照** ⇒ 取值恒等；断言用 `Double.doubleToLongBits` 而非 `==` 容差；T2 覆盖 |
| Gate B 批 3 已知基线 fail | 批 3 非全绿 | 沿用既有口径（`P28_T6.tr64` 与 P43 0 关系），不修改基线测试 |

---

## 8. 交付物清单

- 源码：**0 新增**；`FulltextStore.java`（append-only 3 挂载签名 + 3 private helper + `searchCore` 6 处 stat 点查替换）
- 测试：`ds/P43_T1_AttachSkeletonTest`、`P43_T2_ReadpathEquivalenceTest`、`P43_T3_FallbackSemanticsTest`、`P43_T4_E2eReadpathSwitchTest`、`P43_T5_Gatekeeper9YTest`、`P43_T6_NFR_Test`（6 文件 / 31 TR）
- 文档：本 `spec.md`（Gate C 回填）+ `tasks.md`；`docs/dsdb-design-status-and-roadmap.md`（`OQ12-㉑` ⏳→✅ + 挂 `OQ12-㉒`）；Project Memory 追加
- Stage 5：`git add`（仅 P43 相关）→ `git commit` → `git push origin main`

---

## 9. 签批区（Gate A，需用户 VERBATIM 批准）

> 请用户逐条 **VERBATIM** 批准（直接复制下列文本并改写为 **[x]**），特别是 **§0 方案选择**：

- [x] **§0 方案选择**：批准 **方案 A**（混合只读切换：stat 列视图优先 + WeightStore 回退；`searchCore` 6 处等价替换）
- [x] **§1 Scope**（Slice B：搜索读路径切换 + MVP 边界 0 越界）
- [x] **§2 红线 R1-R10**（六重递归文件数 + Δ0 新文件 + R4 P21 例外 2 + R5 段格式 0 变更 + R7 切换等价性）
- [x] **§3 架构**（0 新增源文件；仅 `FulltextStore` 就地扩展）
- [x] **§4 接口契约**（4.0 P21 append-only 3 签名 / 4.1 `searchCore` 6 处替换 / 4.2 复用 `LayeredInvertedView`）
- [x] **§5 测试**（31 TR = 5+6+3+3+9+5；Gate B 拆 4 批）
- [x] **§6 逆指标** / **§7 风险与回退** / **§8 交付物清单**

**用户批准原文（VERBATIM）**：逐条 `[x]` 签署 §0 方案 A / §1 Scope / §2 红线 R1-R10 / §3 架构 / §4 接口契约 / §5 测试 / §6 逆指标 + §7 风险与回退 + §8 交付物清单（2026-10-25）。

---

## 10. 实装偏差表（Gate C 回填）

| 编号 | 计划 | 实装 | 原因 |
|------|------|------|------|
| D1 | `FulltextStore` 追加 3 挂载签名 + 3 helper + `searchCore` 6 处等价替换（append-only） | 实际 `git diff --numstat` = **105 插入 / 8 删除**（import 1 + 字段 5 + 方法块 83 + 站点 16 净增） | **无功能偏差**：逐条对齐 §3/§4；纯增量无删除既有语义（8 删除均为被替换的原行） |
| D2 | 段格式 0 变更 / 0 新源文件 / 0 新线程 | 全部满足（Y1/Y4/Y6 实锤） | 无偏差 |
| D3（过程） | — | P43 delta 一度因 IDE 保存触发**全文件格式化**，产生 2590/848 假 diff；已回退 HEAD 并以脚本 `target/p43_inject.py` 重放 P43 增量 | 编辑器保存钩子导致格式化 churn；改用脚本注入绕开，最终 diff 回归 105/8（教训记入 Project Memory） |

---

## 11. Probe 锚点（自核实锤，非转述）

| 锚点 | 文件 | 核实结论 |
|------|------|---------|
| 候选召回索引 | `FulltextStore.java:53-55` | `idx1`=(docId,parentId,level)→rowPkHash[] / `idx2`=unitId→rowPkHash[] / `idx3`=(docId,level)→rowPkHash[] |
| 候选召回入口 | `FulltextStore.java:898/913/919` | `selectByParentLevel(docId,parentId,level)`（IDX1，seq 升序）/ `selectByUnit(unitId)`（IDX2）/ `selectByDocLevel(docId,level)`（IDX3） |
| `searchCore` stat 点查 | `FulltextStore.java:2099-2258` | 6 处：L2105 `getTermStat`(存在性) / L2115 `getTermStat`(idf) / L2142 `getTf` / L2172 `getDocStat`(docLen) / L2180 `getTf` / L2248 `getTf`(unitIdHit) |
| avgdl（0 改） | `FulltextStore.java:1961-1971` | `computeAvgDocLen(level)` 按 `dkey=1..` 扫 `weightStore.getDocStat`（段无 avgdl 聚合能力） |
| 邻居召回（0 改，IDX2+IDX1） | `FulltextStore.java:1922-1959` | `neighborsWithinD(uid, maxD)` 经 `selectByUnit` + `selectByParentLevel` 滑窗 |
| phraseBoost（0 改，IDX1） | `FulltextStore.java:1891-1919` | `computePhraseBoost` 经 `selectByParentLevel`（IDX1 seq） |
| 向量路径（0 改） | `FulltextStore.java:2260+` | `searchWithVectorCore` 用 `weightStore.getTermVector`+`getTermStat`（向量不入段） |
| WeightStore 持久性 | `FulltextStore.java:251-272` | `WeightStore` 为 P21 `public static final` 内嵌类，3×`DsHashMap` + 1×`DsFixedBucketStore`（`weight_store/` 目录，**磁盘持久**） |
| tf 写入值域 | `FulltextStore.java:1978-1982` | `updateWeightForLevel` tf = 词频 count ≥ 1 ⇒ `tf==0 ⟺ miss`（支持 `viewTf` 回退判定） |
| `layered/` 视图面（0 改复用） | `layered/LayeredInvertedView.java:85-151` | `docLen`(miss 0)/`idf`(miss 0.0)/`tf`(miss 0)/`hasDoc`/`hasTerm`/`close` 幂等 |
| `mix4` 不保 uid 高位 | `TermDictStore.java`（`FarmHash64.mix4`） | 四元全混合 → `scanTfPrefix` **无法按 uid 前缀反查**（§0 方案 B 根因） |
| 六重递归文件数红线 | `P37_T5` / `P38_T5` / `P39_T5` / `P40_T5` / `P41_T5` / `P42_T5` | 全部使用**递归** `collectJavaFiles` → 六棵子树不可新增 `.java`，且根包受 ZeroLeak3=3 → **P43 只能 0 新文件** |
| 上游候选 | `docs/dsdb-design-status-and-roadmap.md:461` | `⏳ OQ12-㉑ … ① Fulltext 搜索读路径切换（Slice B：FulltextStore.search 改走 LayeredInvertedView，红线高建议单独 Gate A 评估）` |
