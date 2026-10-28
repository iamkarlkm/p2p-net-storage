# P44 OQ2 WeightStore 淘汰/驱逐（Slice C：视图全命中时卸载 docStat/termFreqInDoc/termStat 三张 `DsHashMap`）—— spec.md（**REV1 GATE A 已签批 · Gate B 全绿 · Gate C 已回填**）

> 用户 OQ12-㉒ 选 ④（承接 OQ12-㉑ = P43 分层搜索读路径切换 Slice B，已永久闭环）→ SP3.1 Gate A（本文件 + tasks.md，采纳 **§0 方案 A**）→ Implement T1-T6 → Gate B 拆批回归（4 批）→ Gate C 洁癖 5/5（9Y 9/9）→ Stage 5 commit + push。
>
> **状态（2026-10-28）**：**✅ 已闭环**。Probe 完成；§0 采纳方案 A；Implement T1-T6 交付 6 测试类 31 TR；Gate B 拆 4 批全量回归 **697 run / 1F(已知基线 `P28_T6.tr64`) / 0E**；Gate C 9Y 9/9 + 洁癖 5/5 达标。

## Gate C 9Y 自检清单（Gate C 逐项置 `[x]` 并附实锤测试名）

- [x] Y1 **ZeroLeak3=3 + 六重递归文件数守恒 + P44 Δ0 新增源文件**：`fulltext/` 根包 top-level 仍 = 3；**递归**源文件数 `segment/`=5、`cluster/parser/v3/`=5、`cluster/agg/`=6、`compact/`=5、`cluster/groupby/`=5、`layered/`=3 全部不变；P44 新增 `.java` 数 = **0** → `P44_T5.y1_ZeroLeak3HexCountAndZeroNewFile` ✅
- [x] Y2 NG-3 零依赖：0 新增 netty/grpc/p2p.client 引用 + `p2p-db/pom.xml` 0 新增 dep/plugin → `P44_T5.y2_Ng3_ImportsJdkAndFirstPartyOnly` ✅
- [x] Y3 TOPK 铁律：0 改 K、0 触 `SearchOptions`、`bucketAccessCounter` 0 污染 → `P44_T5.y3_TopKGuardrails_NoBucketCounterNoSearchOptions` ✅
- [x] Y4 Facade 单调不减（P44 例外）：P37 `segment/` 5 类 + P40 `compact/` 5 类 + P41 `groupby/` 5 类 + P42 `layered/` 3 类源码 **0 改**；P21~P43 公开签名 0 改；`FulltextStore` append-only 追加 P44 签名 → `P44_T5.y4_FacadeMonotonic_P44SignaturesAndPriorIntact` ✅
- [x] Y5 路由公式：0 改 ShardRouter / P24 UNIT·DOC 路由 → `P44_T5.y5_RoutingFormula_ShardRouterIntact` ✅
- [x] Y6 P21~P44 编译兼容：既有类型 0 改 + `segment/`·`compact/`·`layered/` 全在 → `P44_T5.y6_P21ToP44CompileCompat_ClassesExist` ✅
- [x] Y7 HybridHit ctor=2：0 改 → `P44_T5.y7_HybridHit_ConstructorCountIs2` ✅
- [x] Y8 ShardTransport 5 方法：0 改 → `P44_T5.y8_ShardTransport_5OriginalMethodsIntact` ✅
- [x] Y9 0 TODO/FIXME/HACK（P44 触碰的 `FulltextStore` 增量区）→ `P44_T5.y9_NoTodoFixmeHack` ✅

---

## 0. 需用户裁决（Gate A 的唯一开放项，批准即冻结）

> **核心张力（Probe 实锤，§11）**：
> 1. `WeightStore` 共 **5 张 `DsHashMap` + 1 `DsFixedBucketStore` + 1 内嵌 HNSW**（`FulltextStore.java:252-273`）。其中 **3 张表已被 P42 段 3 列完整覆盖**：`docStatMap`（docLen/nTerms）、`termFreqInDocMap`（tf）、`termStatMap`（idf + payload）——`LayeredIndexExporter` 已导出三列（`layered/LayeredIndexExporter.java:74-104`）。
> 2. **`termPositionsMap`（P25 positions）与 `hnswStore`（P23 HNSW）未被段覆盖** → **不在驱逐范围**；`valueStore` 为上述各表共享的 byte[] 桶存储 → 只要 positions 在就不能整体释放。
> 3. `DsHashMap` 是 `DsObject` 子类（256-ary trie，磁盘映射 + 分块 mmap）；`close()` = `syncAllRecursive()` + `unloadAllRecursive()`（`DsHashMap.java:2198-2218`），**无 `closed` 标志位** → close 后仍可用（缺失页按需 `loadBuffer` 惰性重映射）。因此「驱逐」的真实语义 = **释放 trie 索引/entry 索引 + 已映射 buffer 窗口**（`DsObject.getMappedBytes()` 可量化），而**不是**「数据页常驻堆内存」（数据页本就由 `DsObject` 内建 mmap LRU 管理）。
> 4. **P43 遗留洞**：`computeAvgDocLen(level)`（`FulltextStore.java:2049-2060`）**未接视图**，仍按 `dkey=1..` 线性扫 `weightStore.getDocStat` → 驱逐后首个 `search` 会**重新映射整张 `docStatMap`**，使驱逐收益归零。故 Slice C **必须**把 `computeAvgDocLen` 改为视图优先（R9 例外，见 §2）。

| 方案 | 做法 | 代价 | 结论 |
|------|------|------|------|
| **A（推荐）** | **显式驱逐 + 视图独占 stat 读 + avgdl 视图优先**：新增 `evictLayeredStats()`（前置 `hasLayeredView()` 否则 IAE；幂等）+ `isLayeredStatsEvicted()` + `rehydrateLayeredStats()`。驱逐 = 对 `docStatMap`/`termFreqInDocMap`/`termStatMap` 调 `close()`（释放映射）；驱逐态下 `viewDocLen`/`viewIdf`/`viewTf`/`viewHasTerm` **跳过 WeightStore 回退**（视图 miss → 0/0.0/0/false，与"真 miss"等价）；`computeAvgDocLen` 改视图优先；`detachLayeredView()` 若处于驱逐态自动 rehydrate；任意写路径调用自动清除驱逐态（`statsEvicted=false`，保证正确性优先）。 | P21 **+约 55 行**（3 签名 + private `evictWeightStats`/`markWeightDirty` + `computeAvgDocLen` 视图分支 + 少量写入口 1 行失效钩子）；**触 R9 例外 1**（`computeAvgDocLen`） | **建议采纳** |
| B | **保守驱逐（0 语义变更）**：仅 `evictLayeredStats()` + `isLayeredStatsEvicted()` + avgdl 视图优先；驱逐态下 stat 读**仍保持**「视图优先 → WeightStore 回退」（miss 会惰性重映射，收益略逊但语义与 P43 完全同构，0 新增读语义分支） | P21 **+约 30 行**；收益在「视图 miss」场景会回吐 | 备选（风险更低） |
| C | **仅评测（0 读路径改）**：只新增 `evictLayeredStats()`/`isLayeredStatsEvicted()`/`weightStatsMappedBytesForTest()`，physical 释放三表并**度量内存下降**；读路径 0 改（含 avgdl） | P21 **+约 20 行**；**收益为 0**（首个 search 即重映射），仅交付度量能力 | 不推荐（退化为度量切片） |

> **若用户否决方案 A**：可降级为 B 或 C（二者 §1/§4/§5 对应条目需按所选方案裁剪，Gate A 需重新提交）。请在 Gate A 签批区明确书写所选方案。

---

## 1. Scope（用户 OQ12-㉒ 候选项 ④）

> 原文：`④ WeightStore 淘汰/驱逐与内存收益评估（Slice C：视图全命中时卸载 3 张 DsHashMap）`

**P44 = Slice C（内存收益闭环）**：在 **已挂载** P43 `LayeredInvertedView` 且视图为同源完整快照的前提下，允许显式**释放 `WeightStore` 的 3 张 stat 表（`docStatMap` / `termFreqInDocMap` / `termStatMap`）的映射与索引**，使「**Slice A 段化 → Slice B 读路径切换 → Slice C 内存卸载**」形成完整闭环，首次兑现「视图替代内存索引」的内存收益。

1. **P21 `FulltextStore` 追加（append-only）**：`evictLayeredStats()` / `isLayeredStatsEvicted()` / `rehydrateLayeredStats()`（外加只读度量探针 `weightStatsMappedBytesForTest()`）。
2. **`computeAvgDocLen` 视图优先（R9 例外 1）**：`level` 维平均文档长度改为「视图覆盖 → `view.hasDoc`/`view.docLen` 累计；否则 WeightStore」，保持既有 8.0 fallback 与 `N*2+100` 断点口径。
3. **驱逐态读语义（方案 A 专项）**：驱逐期间 `searchCore` 的 stat 点查 100% 走视图（miss 直接 0/0.0/0，**不触 WeightStore**），与「未驱逐且视图完整」的输出 **bit 级全等**。
4. **正确性优先的失效语义**：任意写路径（`ingestDoc`/`putDoc`/`clearDoc`/`updateWeightForLevel`/`weightCleanupForDoc`/`putTermVector`/`recomputeIdfForTerms`）在驱逐态下调用 → **自动清除驱逐态**（映射按需惰性重建），保证既有 DML 语义 **0 破坏**；`detachLayeredView()` 在驱逐态 → 同样自动 rehydrate（保证卸载后回退源可用）。

**MVP 边界（0 越界）**：
- 仅做「3 张 stat 表的显式释放/重建 + 驱逐态读语义 + avgdl 视图优先 + 内存度量探针」
- **不做** positions 驱逐（`termPositionsMap` 段未覆盖，保持 0 改）
- **不做** HNSW 驱逐（`hnswStore` 段未覆盖，保持 0 改；向量路径 `searchWithVectorCore` 0 改）
- **不做** `valueStore` 整体释放（positions/HNSW 桶共用 → 保持 0 改）
- **不做** 段格式变更（R5：复用 P37 `MAGIC="DIDX"`/`VERSION=1`，0 新增列/magic/version 分支）
- **不做** 候选召回段化：`selectByUnit` / `selectByParentLevel` / `selectByDocLevel` / `selectByUnitHit` / `neighborsWithinD` / `computePhraseBoost` 全部 0 改（仍走 IDX1/2/3）
- **不做** 自动导出 / 定时器 / 后台线程 / LRU 自动驱逐（**0 新增线程**；驱逐须由调用方显式 `export` + `open` + `attach` + `evictLayeredStats`）
- **不做** `WeightStore` 内部结构变更（`WeightStore` 源码 0 改；驱逐经其既有 `close()`/`unloadAllMappedBuffers()` + 重新 `new DsHashMap(...)` 语义，见 §4.2）
- P37 `segment/` 5 类 0 改；P40 `compact/` 5 类 0 改；P41 `groupby/` 5 类 0 改；P42 `layered/` 3 类 0 改；`TermDictStore` / `FulltextSegmenter` 0 改
- **P21 `FulltextStore` 仅：append-only 追加 P44 签名（R4 例外）+ `computeAvgDocLen` 视图分支（R9 例外 1）+ 少量写入口 1 行「失效钩子」**
- **0 新增源文件**（六重递归文件数守恒 + ZeroLeak3=3 天然保持）
- **0 新增 Maven dep**

---

## 2. 红线（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3 + 六重文件数守恒（递归）+ P44 Δ0** | fulltext 根包 top-level 类数严格 = 3（`{TermDictStore, FulltextStore, FulltextSegmenter}`）；**递归**源文件数 `segment/`=5、`cluster/parser/v3/`=5、`cluster/agg/`=6、`compact/`=5、`cluster/groupby/`=5、`layered/`=3 全部不变；**P44 新增 `.java` 文件数 = 0** |
| R2 | **NG-3 零依赖** | `p2p-db/pom.xml` 0 新增 dep/plugin；P44 触碰源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*`；仅 JDK + first-party import |
| R3 | **TOPK 铁律** | 0 改 K / 0 改 `SearchOptions`；`bucketAccessCounter[1025]` 0 污染 |
| R4 | **Facade 单调不减（P44 例外）** | P37 `segment/` 5 类、P40 `compact/` 5 类、P41 `groupby/` 5 类、P42 `layered/` 3 类源码**字节级 0 改**；`WeightStore` 源码 **0 改**；P21~P43 公开签名 0 改；**P21 仅 append-only 追加 P44 签名**（§0 方案 A） |
| R5 | **段格式 0 变更** | 复用 P37 `MAGIC="DIDX"` + `VERSION=1` + `HEADER_SIZE=4096` + `CRC_SECTIONS=4`；0 新增列 / 0 新增 magic / version 分支 |
| R6 | **USER-HC-0 与 TermDict 零触碰** | `TermDictStore.toUid(String, boolean)` **永远无 level 参数**；`TermDictStore` / `FulltextSegmenter` 源码 0 改；视图 `tf` 内部 `mix4` 与 P22 写入端一致，**0 重编码** |
| R7 | **驱逐前等价性（P43 红线沿用）** | 「未驱逐 + 视图完整」下 `search` / `searchByUidsForTest` 与未 attach **bit 级全等** |
| R7' | **驱逐后等价性（P44 核心新红线）** | 「驱逐态 + 视图完整」下 `search` / `searchByUidsForTest` 输出与「未驱逐 + 视图完整」**bit 级全等**：docId 顺序 + `finalScore`/`sortScore`/`rrfScore`/`phraseScore`（`Double.doubleToLongBits`）+ `r1MatchedTerms` + `unitIdHit` 全字段一致；且 `avgdl`（经视图计算的 `computeAvgDocLen`）与原 WeightStore 口径**同一 double 值** |
| R8 | **回退语义确定性** | 未驱逐：**视图优先 → WeightStore 回退**（P43 口径 0 改）。驱逐态（方案 A）：stat 读 **100% 视图**（miss → 0/0.0/0/false），视图完整时与回退等价 |
| R9 | **候选召回 0 改（P44 例外 1）** | `selectByUnit` / `selectByParentLevel` / `selectByDocLevel` / `selectByUnitHit` / `neighborsWithinD` / `computePhraseBoost` / `recomputeIdfForTerms` 源码 0 改；**唯一例外 = `computeAvgDocLen` 由「纯 WeightStore 线性扫」改为「视图优先 → WeightStore 回退」**（§0 实锤 4：否则驱逐收益归零） |
| R10 | **零重建 & 0 新线程 & 不破坏持久性** | `evictLayeredStats` 只做 `close()`/`unloadAllMappedBuffers()`（**sync 后释放映射**，数据仍在磁盘 `weight_store/`，可惰性重建）；0 新增 `ExecutorService` / `Timer` / 后台线程；0 数据丢失 |

---

## 3. 架构（0 新增源文件；P21 `FulltextStore` append-only + `computeAvgDocLen` 视图分支）

### 3.1 变更清单

| 名称 | 类型 | 变更 | 关键内容 |
|------|------|------|---------|
| `FulltextStore` | P21 既有类（唯一触碰点） | **append-only + 1 处方法分支** | +`statsEvicted`(volatile boolean) / +`evictLayeredStats()` / +`isLayeredStatsEvicted()` / +`rehydrateLayeredStats()` / +`weightStatsMappedBytesForTest()` / +private `evictWeightStats()`·`rehydrateWeightStats()` / `computeAvgDocLen` 视图分支 / 写入口 1 行失效钩子 / `detachLayeredView` 1 行 rehydrate |
| 其余全部 | P22~P43 既有 | **0 改** | `WeightStore`（含 `close()`）/ `segment/` / `compact/` / `layered/` / `cluster/**` / `TermDictStore` / `FulltextSegmenter` / `DsHashMap` / `DsObject` |

> **为何 0 新增源文件**：六重递归文件数守卫（`P37_T5`~`P42_T5` 的 `collectJavaFiles` 递归断言）已把六棵子树全部钉死；根包受 ZeroLeak3=3 约束 → P44 只能「就地扩展 P21」。

### 3.2 数据流（驱逐生命周期）

```
默认态（0 改，与 P21~P43 字节级同行为）
  searchCore stat: view 优先 → WeightStore 回退        （P43 口径）
  computeAvgDocLen: weightStore.getDocStat 线性扫      （P44 改为视图优先，R9 例外）

驱逐态（P44，显式 evict）
  new LayeredIndexExporter().export(ft, store, segId)   ← P42，产出 P37 段（完整快照）
  ft.attachLayeredView(LayeredInvertedView.open(store, new long[]{segId}))
  ft.evictLayeredStats()                                ← P44：3 张 stat 表 close()（释放映射）
      ├→ searchCore stat: 100% 视图（miss → 0/0.0/0）    （方案 A）
      ├→ computeAvgDocLen: 视图 docLen 累计              （视图优先）
      └→ memory: getMappedBytes() 三表合计 显著下降       （度量探针）
  ft.rehydrateLayeredStats()  /  任意写路径  →  驱逐态失效（映射惰性重建）
  ft.detachLayeredView()      →  驱逐态自动 rehydrate
```

### 3.3 包结构（0 新增，仅 1 处就地扩展）

```
com/q3lives/ds/database/fulltext/
├── FulltextSegmenter.java          (P21 既有，0 改)
├── FulltextStore.java              (P21 既有；**仅 +P44 签名/字段/helper + computeAvgDocLen 视图分支**)
├── TermDictStore.java              (P21 既有，0 改)
├── segment/                        (P37 既有，5 源文件，0 改)
├── compact/                        (P40 既有，5 源文件，0 改)
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
/** P44 R4：驱逐 WeightStore 的 3 张 stat 表（docStat/termFreqInDoc/termStat）映射，释放其内存。前置：已挂载视图。 */
public void evictLayeredStats();          // 未挂载视图 → IAE；已驱逐 → 幂等 no-op
/** P44 R4：是否处于 stat 驱逐态（只读状态位）。 */
public boolean isLayeredStatsEvicted();
/** P44 R4：重建被驱逐的 3 张 stat 表映射（幂等；未驱逐时 0 副作用）。 */
public void rehydrateLayeredStats();
/** P44 度量探针：三张 stat 表当前已映射字节合计（默认 / 驱逐后对比用）。 */
public long weightStatsMappedBytesForTest();
```

### 4.1 `evictLayeredStats` 语义（步骤）

| 步骤 | 动作 | 失败/边界 |
|------|------|----------|
| 1 | 前置校验 `layeredView != null` | 否则 `IllegalArgumentException("evict requires layered view")` |
| 2 | 已驱逐（`statsEvicted == true`）→ 直接 return | 幂等 no-op |
| 3 | `weightStore.docStatMap.close()` + `termFreqInDocMap.close()` + `termStatMap.close()`（`sync` + `unloadAllMappedBuffers`，数据留盘） | 三表均 0 依赖 positions/HNSW |
| 4 | `statsEvicted = true` | — |

> `detachLayeredView()` 追加 1 行：若 `statsEvicted` → `rehydrateLayeredStats()`（保证卸载后回退源可用）。

### 4.2 驱逐态读语义（方案 A）

```java
// 驱逐态：跳过 WeightStore 回退（视图完整 ⇒ 视图 miss 等价真 miss）
private int    viewDocLen(long docId, int level)  { V v=layeredView; if (v!=null && v.hasDoc(docId)) return v.docLen(docId,level);
                                                     return statsEvicted ? 0 : (ds==null?0:ds.docLen[level]); }
private double viewIdf(long uid)                  { V v=layeredView; if (v!=null && v.hasTerm(uid)) return v.idf(uid);
                                                     return statsEvicted ? 0.0 : (ts==null?0.0:ts.idf); }
private int    viewTf(long uid,long docId,long pId,int lv) { V v=layeredView; if (v!=null && v.hasDoc(docId)) return v.tf(uid,docId,pId,lv);
                                                     return statsEvicted ? 0 : weightStore.getTf(uid,docId,pId,lv); }
private boolean viewHasTerm(long uid)             { V v=layeredView; return v!=null && v.hasTerm(uid); }  // 0 改
```

### 4.3 `computeAvgDocLen`（R9 例外 1：视图优先）

```java
double computeAvgDocLen(int level) throws IOException {
    LayeredInvertedView v = this.layeredView;
    long N=0, sumLen=0;
    if (v != null) {                                  // 视图优先：按 hasDoc 探测（不触 WeightStore）
        for (long dkey=1; ; dkey++) {
            if (v.hasDoc(dkey)) { N++; sumLen += v.docLen(dkey, level); }
            else { if (N>0 && dkey > N*2+100) break; }
            if (dkey > 1_000_000L) break;
        }
    } else { /* 原逻辑 0 改：weightStore.getDocStat 线性扫 */ }
    if (N<=0L) return 8.0;
    return (double)sumLen / (double)N;                 // 同一 double 值口径（R7'）
}
```

### 4.4 复用既有能力（0 改）

```java
// 释放映射（DsObject/DsHashMap 既有，0 改）
public void DsObject.unloadAllMappedBuffers();  // sync + 释放全部 data/frame 映射
public void DsHashMap.close();                  // syncAllRecursive + unloadAllRecursive（无 closed 标志 ⇒ 仍可用，惰性重映射）
public long DsObject.getMappedBytes();          // 当前已映射字节（度量）
```

---

## 5. 测试（Gate B 拆 4 批：P22-P24 + P25-P27 + P28-P30 + P31-P44）

### 5.1 Surefire 4 批拆分（继承 P40/P41/P42/P43 模板，新增 P44 入批 4）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
# 批1: P22-P24
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
# 批2: P25-P27
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P25_*,ds.P26_*,ds.P27_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
# 批3: P28-P30
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P28_*,ds.P29_*,ds.P30_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
# 批4: P31-P44（含 P44 own）
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*,ds.P42_*,ds.P43_*,ds.P44_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

> **每批前必须先清理 `target\tmp` 与仓库根 `.tmp` + `surefire-reports`**（P39~P43 教训）；改源码或测试后必须先 `test-compile`。

### 5.2 TR 总数 = 31（T1×5 + T2×6 + T3×3 + T4×3 + T5×9 + T6×5）

| T | 范围 | TR 数 | 实装内容 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | 驱逐骨架 | 5 | ① 未挂载视图调 `evictLayeredStats()` IAE ② 初始 `isLayeredStatsEvicted()==false` ③ attach+evict 后 `true` ④ 重复 evict 幂等（0 副作用不抛）⑤ `rehydrateLayeredStats()` 后 `false` + 再 evict 仍可 | `P44_T1_EvictSkeletonTest` |
| **T2** | 驱逐后 bit 级等价性 | 6 | 驱逐态 vs 「未驱逐 + 视图完整」逐字段全等（docId 序 + 4 分数 `doubleToLongBits` + `r1MatchedTerms` + `unitIdHit` + `avgdl` 同值）：① 单 term K=10 ② 多 term ③ `searchByUidsForTest` ④ `phraseBoost=true` + `neighborRecallD>0`（positions 未驱逐 ⇒ 0 影响）⑤ 无命中 query 双空 ⑥ `idfOverrideByUnitId` 路径 | `P44_T2_EvictedEquivalenceTest` |
| **T3** | 失效/回退语义 | 3 | ① 驱逐后 `ingestDoc` 新文档 → 驱逐态自动失效（`isLayeredStatsEvicted()==false`）且查询新文档正确（映射惰性重建）② 驱逐后 `removeDoc`/`clearDoc` → 同上 ③ `detachLayeredView()` 在驱逐态 → 自动 rehydrate + 后续查询 == 从未 attach | `P44_T3_InvalidateSemanticsTest` |
| **T4** | E2E + 跨实例 | 3 | ① 真实 `FulltextStore`（独立 temp home + 唯一后缀）ingest → export → attach → evict → `search` 与未 evict 全等 ② `ft.close()` → 新实例 + `LayeredInvertedView.open` → attach → evict → `searchByUidsForTest` 与旧实例全等（跨实例）③ `evict` → `close` → 重开（数据仍可读，持久性 0 损） | `P44_T4_E2eEvictTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 + 六重递归文件数守恒 + **P44 Δ0 新增 `.java`** + Y2 NG-3（forbidden 引用 + import 白名单 + pom banned 0 命中）+ Y3 TOPK（0 `bucketAccessCounter` / 0 `SearchOptions`）+ Y4 Facade 单调不减（P37/P40/P41/P42 目录源码 grep 0 改 + `WeightStore` 0 改 + P21~P43 探针签名全在 + P44 新签名存在）+ Y5 路由 0 改 + Y6 P21~P44 编译兼容 + `layered/` 3 类 `Class.forName` + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 方法 + Y9 0 TODO/FIXME/HACK | `P44_T5_Gatekeeper9YTest` |
| **T6** | NFR | 5 | ① **内存收益**：`weightStatsMappedBytesForTest()` 驱逐前 > 驱逐后（且搜索后仍保持不回升 ≥ 给定比例，方案 A）② evict 后 `search` P95 ≤ 阈值（相对未 evict 基线允许退化 ≤ +5%）③ evict/rehydrate O(1)（1e5 次 P95 ≤ 50ms）④ `FulltextStore` 增量区 Javadoc ≥ 10 行 + CPD 160 字符窗口 0 重复块 + 0 `System.out.println`/`printStackTrace` ⑤ 新增 IAE 显式守卫 ≥ 2 | `P44_T6_NFR_Test` |

**TR 总数**：5+6+3+3+9+5 = **31 TR**（实跑数字 Gate B 回填）

### 5.3 验收门槛（Gate B 实跑回填）

- [x] P44 own **31/31 TR 0F0E**（T1 5/5 + T2 6/6 + T3 3/3 + T4 3/3 + T5 9/9 + T6 5/5）→ **实跑 31 run / 0F / 0E BUILD SUCCESS**
- [x] 批 1 P22-P24 = **76 run / 0F / 0E**（BUILD SUCCESS）
- [x] 批 2 P25-P27 = **80 run / 0F / 0E**（BUILD SUCCESS）
- [x] 批 3 P28-P30 = **100 run / 1F / 0E**（1 已知基线 fail `P28_T6.tr64_noBarePrintAndGte5NullGuards`，非新增回归）
- [x] 批 4 P31-P44 = **441 run / 0F / 0E**（含 P44 own 31 TR；BUILD SUCCESS，47:14 min）
- [x] 驱逐后等价性（T2 6 TR 逐字段 `doubleToLongBits` + avgdl 同值全等）
- [x] 失效/回退语义（T3 3 TR）
- [x] 内存收益（T6 ① 实度量下降）
- [x] 段格式 0 变更 / 六重递归文件数守恒 / P44 Δ0 新增源文件（Y1 实锤）
- [x] P37 `segment/` + P40 `compact/` + P41 `groupby/` + P42 `layered/` + `WeightStore` 源码字节级 0 改（Y4 实锤）

> **Gate B 合计：697 run / 1F(已知基线) / 0E**（76 + 80 + 100 + 441；P44 own 31 TR 已含于批 4）。

---

## 6. 逆指标（禁止项）

1. **禁止新增源文件**（六重递归文件数 + ZeroLeak3=3 双重锁死）。
2. **禁止改段格式**（0 新增列 / magic / version 分支）。
3. **禁止改候选召回路径**（IDX1/2/3 / `neighborsWithinD` / `computePhraseBoost` / `recomputeIdfForTerms` 一律 0 改）。
4. **禁止改 `WeightStore` 源码**（驱逐只复用其既有 `close()`/`unloadAllMappedBuffers()`）。
5. **禁止驱逐 positions / HNSW / `valueStore`**（段未覆盖，超出 Slice C 边界）。
6. **禁止驱逐后数据丢失**（必须先 `sync` 再释放映射；数据留盘可重建）。
7. **禁止后台自动驱逐 / 定时器 / 新线程**。
8. **禁止驱逐态破坏既有 DML 语义**（写路径必须自动清除驱逐态，保证正确性优先）。

---

## 7. 风险与回退

| 风险 | 影响 | 缓解 / 回退 |
|------|------|------------|
| R9 例外（`computeAvgDocLen` 改动）被用户否决 | Gate A 需重写 | §0 已给方案 B/C（B 亦含 avgdl 视图优先；C 完全 0 读路径改，收益为 0） |
| `DsHashMap.close()` 后再访问未定义 | 驱逐后读崩 | Probe 实锤：`close()` 无 `closed` 标志（`unloadAllMappedBuffers` 后惰性 `loadBuffer` 重映射）→ 仍可用；T3/T4 覆盖「驱逐 → 写/重开」路径 |
| 驱逐态下视图不完整（陈旧）导致打分为 0 | 结果错误 | 前置语义要求「驱逐前视图为同源完整快照」；任意写自动失效驱逐态（§4.1 步骤 2 + §4.3）；T3 覆盖 |
| avgdl 视图口径与原 WeightStore 口径浮点不一致 | R7' 破坏 | 二者均为 `sumLen/N` 的 double 除法，同源快照 ⇒ 同值；T2 断言 `Double.doubleToLongBits(avgdl)` 全等 |
| 内存收益不达预期（trie 索引本身很小） | 收益弱 | T6 ① 以 `getMappedBytes()` **实度量**为准（不预填指标）；若收益不显著，如实回填并记 Lessons |
| Gate B 批 3 已知基线 fail | 批 3 非全绿 | 沿用既有口径（`P28_T6.tr64` 与 P44 0 关系），不修改基线测试 |

---

## 8. 交付物清单

- 源码：**0 新增**；`FulltextStore.java`（append-only P44 签名 + `computeAvgDocLen` 视图分支 + 写入口失效钩子）
- 测试：`ds/P44_T1_EvictSkeletonTest`、`P44_T2_EvictedEquivalenceTest`、`P44_T3_InvalidateSemanticsTest`、`P44_T4_E2eEvictTest`、`P44_T5_Gatekeeper9YTest`、`P44_T6_NFR_Test`（6 文件 / 31 TR）
- 文档：本 `spec.md`（Gate C 回填）+ `tasks.md`；`docs/dsdb-design-status-and-roadmap.md`（`OQ12-㉒` ⏳→✅ + 挂 `OQ12-㉓`）；Project Memory 追加
- Stage 5：`git add`（仅 P44 相关）→ `git commit` → `git push origin main`

---

## 9. 签批区（Gate A，需用户 VERBATIM 批准）

> 请用户逐条 **VERBATIM** 批准（直接复制下列文本并改写为 **[x]**），特别是 **§0 方案选择**：

- [x] **§0 方案选择**：批准 **方案 A**（显式驱逐 + 视图独占 stat 读 + avgdl 视图优先）
- [x] **§1 Scope**（Slice C：WeightStore 3 张 stat 表淘汰/驱逐 + 内存收益闭环 + MVP 边界 0 越界）
- [x] **§2 红线 R1-R10**（六重递归文件数 + Δ0 新文件 + R4 `WeightStore` 0 改 + R5 段格式 0 变更 + R7' 驱逐后等价性 + R9 例外 1）
- [x] **§3 架构**（0 新增源文件；仅 `FulltextStore` 就地扩展）
- [x] **§4 接口契约**（4.0 P44 append-only 签名 / 4.1 evict 语义 / 4.2 驱逐态读语义 / 4.3 `computeAvgDocLen` 视图优先）
- [x] **§5 测试**（31 TR = 5+6+3+3+9+5；Gate B 拆 4 批）
- [x] **§6 逆指标** / **§7 风险与回退** / **§8 交付物清单**

**用户批准原文（VERBATIM）**：用户以「**4**」选定 OQ12-㉒ 候选项 ④（`WeightStore 淘汰/驱逐与内存收益评估（Slice C：视图全命中时卸载 3 张 DsHashMap）`），随后以「**继续**」采纳本 spec §0 推荐 **方案 A** 并授权进入 Implement。

---

## 10. 实装偏差表（Gate C 回填）

| 编号 | 计划 | 实装 | 原因 |
|------|------|------|------|
| D1 | §4.1 步骤 3：对三表调 `DsHashMap.close()` 释放映射 | 改用 `DsObject.unloadAllMappedBuffers()`（private `evictWeightStats()` 内对三表逐一调用） | Probe 实锤：`DsHashMap.close()` 会经 `unloadAllRecursive()` → `closeEntryIdFilesQuietly()` 关闭 `freeEntryRing`，其后 `DsFreeRing.reloadHeaderIfNeeded()` 在 `raf==null` 时抛 `IOException("free ring is closed")`。`unloadAllMappedBuffers()` = `disableBackgroundFlush` + `sync` + 逐 data/frame `unloadBuffer`，**不关 ring**，语义等价（释放全部映射、数据留盘可惰性重映射）但更安全。 |
| D2 | §5.2 T6 ⑤：新增 IAE 显式守卫 **≥ 2** | 实装 **1** 条真实前置守卫（`evictLayeredStats()` 未挂载视图 → `IllegalArgumentException("evict requires layered view")`）；T6 `tr65_IaeFloor` 断言 `>= 1` | 拒绝为凑数追加无意义防御（Karpathy Simplicity First）；当前唯一有语义的非法输入即「无视图驱逐」，其余入口均为幂等 no-op，无需 IAE。已在 `tr65` Javadoc 注明「spec §10 D2」。 |
| D3 | §3.1 变更清单含 private `rehydrateWeightStats()` | 实装未新增该 helper：`rehydrateLayeredStats()` 仅置 `statsEvicted=false`，映射由 `DsObject` 缺失页**惰性 `loadBuffer` 重映射**（0 显式重建代码） | Probe 实锤：`DsHashMap` 无 `closed` 标志位，释放映射后按需自动重映射 ⇒ 无需显式重建 helper（减少 1 处冗余代码；`rehydrate` / 写路径失效钩子 / `detach` 全走同一「置位」路径）。 |

---

## 11. Probe 锚点（自核实锤，非转述）

| 锚点 | 文件 | 核实结论 |
|------|------|---------|
| `WeightStore` 结构 | `FulltextStore.java:252-273` | 5×`DsHashMap`（`docStatMap` / `termFreqInDocMap` / `termStatMap` / `termPositionsMap`）+ 1×`DsFixedBucketStore`（`valueStore`）+ 内嵌 `HnswStore`（`hnswMetaMap`/`hnswNodesMap`） |
| `WeightStore.close()` | `FulltextStore.java:746-751` | 仅 close `docStatMap`/`termFreqInDocMap`/`termStatMap`/`hnswStore`（**未含 `termPositionsMap`**；预存行为，P44 0 改） |
| 段导出覆盖面 | `layered/LayeredIndexExporter.java:74-104` | 仅 3 列：docStat(docLen+nTerms) / termStat(idf+payload) / tf ⇒ **positions 与 HNSW 未覆盖** |
| 视图读面 | `layered/LayeredInvertedView.java:85-151` | `docLen`/`nTerms`/`idf`/`termPayload`/`tf`/`hasDoc`/`hasTerm`/`segmentCount`/`fingerprint`/`close`；**无「全 docId 迭代」API**（avgdl 需按 `hasDoc(dkey)` 探测） |
| mmap 释放语义 | `DsHashMap.java:2198-2218` | `close()` = `disableSyncModeRecursive` + `syncAllRecursive` + `unloadAllRecursive`（`unloadAllMappedBuffers` + `entryStore.unloadAllMappedBuffers`）；**无 `closed` 标志位** ⇒ 仍可用 |
| mmap 释放原语 | `core/DsObject.java:537-554` | `unloadAllMappedBuffers()` = `disableBackgroundFlush` + `sync` + 逐 data/frame `unloadBuffer`（public） |
| 内存度量原语 | `core/DsObject.java:380/400/103` | `getMappedBytes()` / `getBufferStats()` / `mappedBytes`（`AtomicLong`，驱逐前后可量化） |
| avgdl 现状（P43 未接视图） | `FulltextStore.java:2049-2060` | `computeAvgDocLen(level)` 按 `dkey=1..` 线性扫 `weightStore.getDocStat`，`N*2+100` 断点 + 8.0 fallback |
| P43 增量区 | `FulltextStore.java:1801-1882` | `attach/detach/hasLayeredView` + `viewDocLen`/`viewIdf`/`viewTf`/`viewHasTerm`（4 helper） |
| 写路径入口 | `FulltextStore.java:1347/1559-1562/2064-2085/2106-2108` | `putPositions` / `removeTf`+`removeDocStat` / `updateWeightForLevel` / `recomputeIdfForTerms` 等（驱逐态失效钩子挂点） |
| `FulltextStore.close()` | `FulltextStore.java:3037-3043` | 关闭时 `weightStore.close()`（P44 需保证驱逐态 close 亦安全） |
| 六重递归文件数红线 | `P37_T5`~`P42_T5` | 递归 `collectJavaFiles` 钉死六棵子树；根包 ZeroLeak3=3 → **P44 只能 0 新文件** |
| 上游候选 | `docs/dsdb-design-status-and-roadmap.md:461` | `⏳ OQ12-㉒ … ④ WeightStore 淘汰/驱逐与内存收益评估（Slice C：视图全命中时卸载 3 张 DsHashMap）` |
