# P42 OQ1 全文分层倒排索引段化（Slice A：读路径段化）—— spec.md（**REV1 GATE A 已批准**）

> 用户 OQ12-⑳ 选 ①（承接 OQ12-⑲ = P41 DSL v3 聚合 v2，已永久闭环）→ SP3.1 Gate A（本文件 + tasks.md）→ **用户 VERBATIM 批准（采纳 §0 方案 A）** → Implement T1-T6 → Gate B 拆批回归 → Gate C 洁癖 5/5 → Stage 5 commit + push。
>
> **Gate A 已批准（2026-10-25）：采纳 §0 方案 A（P21 追加 2 个 append-only 只读 accessor）。**
>
> **本文件 §0 已冻结；Gate C 9Y 自检清单已于 Gate C 逐项置 `[x]`（9/9 实锤）；§10 实装偏差表已回填。**

## Gate C 9Y 自检清单（Gate C 已逐项置 `[x]` 并附实锤测试名）

- [x] Y1 **ZeroLeak3=3 + 六重递归文件数守恒**：`fulltext/` 根包 top-level 仍 = 3；且**递归**源文件数 `segment/`=5、`cluster/parser/v3/`=5、`cluster/agg/`=6、`compact/`=5、`cluster/groupby/`=5 全部不变；P42 3 新类 100% 落**新子包** `layered/`（=3）→ `P42_T5.y1_ZeroLeak3AndHexFileCountDoubleGuard` ✅
- [x] Y2 NG-3 零依赖：0 新增 netty/grpc/p2p.client 引用 + `p2p-db/pom.xml` 0 新增 dep/plugin → `P42_T5.y2_Ng3_LayeredImportsJdkAndFirstPartyOnly` ✅
- [x] Y3 TOPK 铁律：0 改 K、0 触 `SearchOptions`、`bucketAccessCounter` 0 污染 → `P42_T5.y3_TopKGuardrails_NoBucketCounterNoSearchOptions` ✅
- [x] Y4 Facade 单调不减：P37 `segment/` 5 类 + P40 `compact/` 5 类 + P41 `groupby/` 5 类源码 **0 改**；P21~P41 公开签名 0 改；P21 仅 **append-only** 追加 2 只读 accessor（见 R4）→ `P42_T5.y4_FacadeMonotonic_P21AppendOnlyAndPriorIntact` ✅
- [x] Y5 路由公式：0 改 ShardRouter / P24 UNIT·DOC 路由 → `P42_T5.y5_RoutingFormula_ShardRouterIntact` ✅
- [x] Y6 P21~P41 编译兼容：既有类型 0 改 + `segment/` 5 类 + `compact/` 5 类 + `layered/` 3 类全在 → `P42_T5.y6_P21ToP42CompileCompat_ClassesExist` ✅
- [x] Y7 HybridHit ctor=2：0 改 → `P42_T5.y7_HybridHit_ConstructorCountIs2` ✅
- [x] Y8 ShardTransport 5 方法：0 改 → `P42_T5.y8_ShardTransport_5OriginalMethodsIntact` ✅
- [x] Y9 0 TODO/FIXME/HACK（`layered/` 源码）→ `P42_T5.y9_NoTodoFixmeHack` ✅

> **9Y 自检实锤**：`P42_T5_Gatekeeper9YTest` 实跑 **9/9 0F0E**（`Tests run: 9, Failures: 0, Errors: 0`，批 4 实测）。

---

## 0. 需用户裁决（Gate A 的唯一开放项，批准即冻结）

> **R4 的 P21 触碰边界**：P42 导出器需要读取 P22 `WeightStore` 的 **termStat 键集** 与 **tf 键→值映射**；而 `WeightStore.termStatMap` / `termFreqInDocMap` 为**包私有**字段（`com.q3lives.ds.database.fulltext` 包），其 bucket 值需 `readBucketVal`（private）才能解出 tf。既有公开面**只能按已知 uid/docId 逐点探针**，无法枚举。

| 方案 | 做法 | 代价 | 结论 |
|------|------|------|------|
| **A（推荐）** | P21 `FulltextStore` 追加 **2 个 append-only 只读 accessor**：`Set<Long> weightStoreAllTermKeys()` + `Map<Long,Integer> weightStoreAllTfEntries()`；既有签名 / 内嵌类型 0 改 | P21 源码 **+约 18 行**（唯一偏差）；不再满足 P37/P40 自我加严的「P21 字节级 0 改」 | **建议采纳** |
| B | P21 字节级 0 改；导出退化为 `docIds × DsString 全 uid × 6 level` 逐点 `getTf` 探针 | 依赖 `parentId = docId*10+level` 隐式约定（脆弱）；O(U×D×6) 次探针（大语料不可接受） | 不采纳 |
| C | 导出器作为 `FulltextStore` **内嵌类**（ZeroLeak3 只计 top-level，内嵌类合法） | 破坏「新功能落新子包」约定；P21 体量再增；`layered/` 文件数守卫无从建立 | 不采纳 |

> **若用户否决方案 A**：P42 改为「**仅 docStat 段化**」的极小切片（termStat/tf 不入段）→ 本 spec §1/§3/§4 需整体降级重写，Gate A 重新提交。请在 Gate A 签批区明确书写所选方案。

---

## 1. Scope（用户 OQ12-⑳ 候选项 ①）

> 原文：`① 全文分层倒排索引段化（char/word/phrase/sentence/sentence_block/document 多级倒排段化 + 跨段词网聚合 + 段化 unit_id 全局唯一，红线高建议单独 Gate A 评估）`

**P42 = Slice A（读路径段化）**：把 P22 `WeightStore` 已驻内存的**分层倒排**（`docStat` 的 6 level `docLen`/`nTerms` + `termStat` 的 idf/payload + tf 的 `mix4(uid,docId,parentId,level)` 键值）**导出为 P37 不可变列式段**，并提供 **mmap 零重建**的 **level-aware 只读视图**；使「分层倒排」首次具备「落盘 + 零 startup 重建 + 可被 P40 compaction 管理 + 可跨进程/跨机分发（unit_id 全局唯一）」四项能力。

1. **`LayeredIndexExporter`（分层倒排导出器）**：`FulltextStore` 内存 WeightStore → 遍历 docStat / termStat / tf → 喂 P37 `DsIndexSegmentWriter`（`addDocStat`/`addTermStat`/`addTf`）→ `finish()` 原子落盘 → `DsIndexSegmentStore.append(segId, seg)` 注册；返回 `LayeredSnapshot`。**导出即快照**（同源同批 → 段指纹恒定）。
2. **`LayeredInvertedView`（分层倒排只读视图）**：包装 P40 `SegmentMergeView`（N 段「新→旧」短路），提供 level-aware 查询：`docLen(docId, level)` / `nTerms(docId, level)` / `idf(uid)` / `termPayload(uid)` / `tf(uid, docId, parentId, level)`（内部 `FarmHash64.mix4` 合成键）/ `hasTerm(uid)` / `hasDoc(docId)`；`open` 仅 mmap + 头/CRC 校验，**禁止把段全量读进堆建哈希表**。
3. **`LayeredSnapshot`（不可变快照描述）**：`segId` + `docCount`/`termCount`/`tfCount` + `segmentFingerprint`(64-hex) + SHA-256 `fingerprint()` + `deepEquals` + IAE 守卫；跨进程/跨 run 恒定。

**「分层」如何被承载（关键）**：P37 段格式的 `docStat` 列**本来就是 6 level 定长列**（`docLen[6] + nTerms[6]`，`DOC_STAT_REC=56`），tf 键**本就把 `level` 烘焙进 `mix4(uid,docId,parentId,level)`** —— 因此「分层倒排段化」**0 新增段格式**即天然成立（R5）。

**MVP 边界（0 越界）**：
- 仅做「docStat(6 level) + termStat + tf 三列导出 + mmap 零重建只读视图 + 不可变快照描述」
- **不做** 写路径段化：`ingestDoc` / `putDoc` / `clearDoc` 仍 100% 走 P22 `WeightStore`（内存 DsHashMap），段仅由**显式** `export` 触发
- **不做** positions（P25 `termPositionsMap`）与向量（P23 HNSW）入段 —— 段格式无该列，**拒绝改段格式**
- **不做** 后台自动导出 / 定时器 / 增量导出（**0 新增线程**）
- **不做** Fulltext 搜索读路径切换：`FulltextStore.search` / `searchWithExtras` 仍走 `WeightStore`；`LayeredInvertedView` 是**并行只读视图**，**不接管** P21 搜索（搜索切换 = Slice B，明确不在本次范围）
- **不做** 跨段词网聚合（P24 DIST / `neighborsWithin` 依赖 positions，不在段内）；多段仅提供 P40 `SegmentMergeView` 的「新段优先」覆盖语义
- **不做** 段格式变更（复用 P37 `MAGIC="DIDX"` + `VERSION=1`，0 新增 magic/version 分支）
- P37 `segment/` 5 类 0 改；P40 `compact/` 5 类 0 改；P41 `groupby/` 5 类 0 改；P22~P41 源码 0 改
- **P21 `FulltextStore` 仅 append-only 追加 2 只读 accessor（R4，需 Gate A 批准）**；`TermDictStore` / `FulltextSegmenter` 0 改
- **0 新增 Maven dep**（复用 P37 既有 `lz4-java`）
- 3 新类全放**新子包** `com.q3lives.ds.database.fulltext.layered`（ZeroLeak3=3 永保）

---

## 2. 红线（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3 + 六重文件数守恒（递归）** | fulltext 根包 top-level 类数严格 = 3（`{TermDictStore, FulltextStore, FulltextSegmenter}`）；且**递归**源文件数 `segment/`=5、`cluster/parser/v3/`=5、`cluster/agg/`=6、`compact/`=5、`cluster/groupby/`=5 全部不变；P42 3 新类 100% 落**新包** `layered/`（=3） |
| R2 | **NG-3 零依赖** | `p2p-db/pom.xml` 0 新增 dep/plugin；`layered/` 源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*`；仅 JDK + first-party import |
| R3 | **TOPK 铁律** | 导出/读取均为纯统计搬运；0 改 K / 0 改 `SearchOptions`；`bucketAccessCounter[1025]` 0 污染 |
| R4 | **Facade 单调不减（P21 唯一例外：append-only）** | P37 `segment/` 5 类、P40 `compact/` 5 类、P41 `groupby/` 5 类源码**字节级 0 改**；P21 `FulltextStore` / P22 `WeightStore` / P30 / P34 / P38 / P39 / P41 公开签名 0 改；**P21 仅新增 2 个只读 accessor（`weightStoreAllTermKeys` / `weightStoreAllTfEntries`），既有方法/内嵌类型一字不删改**（§0 方案 A） |
| R5 | **段格式 0 变更** | 复用 P37 `MAGIC="DIDX"` + `VERSION=1` + `HEADER_SIZE=4096` + `CRC_SECTIONS=4`；导出产物必须是**合法 P37 段**（`DsIndexSegmentReader.open` 可直接 mmap 读，头校验 + 4×CRC32 全过）；0 新增列 / 0 新增 magic /version 分支 |
| R6 | **USER-HC-0 与 TermDict 零触碰** | `TermDictStore.toUid(String, boolean)` **永远无 level 参数**；`TermDictStore` / `FulltextSegmenter` 源码 0 改；导出 uid 直接复用 `WeightStore` 键，**不做任何 uid 重编码** → 段化 unit_id 全局唯一 |
| R7 | **导出确定性** | 三列升序由 P37 `DsIndexSegmentWriter.finish()` 的 `sortedKeys` 保证（docStat 按 docId↑ / termStat 按 uid↑ / tf 按 key↑）；同源同批导出 → `segmentFingerprint` 跨进程/跨 run 恒定 |
| R8 | **覆盖语义确定性** | 单段内同键 = `writer.put` 后者覆盖（继承 P37/P22）；多段视图 = P40 `SegmentMergeView`「新→旧」首个命中；`tf` 未命中 = 0；**顺序由调用方显式给定**（旧→新），不依赖 manifest 物理行序 |
| R9 | **崩溃安全** | 导出顺序必须 `writer.finish()`（`.tmp` + 原子 `Files.move`）**先于** `store.append(segId, seg)`；任一崩溃点下，未 `append` 的半成品段**不在 manifest**，`list()` 不可见 |
| R10 | **零重建** | `LayeredInvertedView.open` 仅做 mmap + 头校验 + 4×CRC32；**禁止**把段内容全量读进堆（逆指标：open 代价与段体积无关，冷启动 P95 ≤ 200ms） |

---

## 3. 架构（3 个新类，P37/P40/P41 源码 0 改，P21 仅 append-only）

### 3.1 新增清单

| 名称 | 类型 | 职责 | 关键字段/方法 |
|------|------|------|---------------|
| `LayeredSnapshot` | 不可变 POJO（`layered/`） | 一次导出的不可变快照描述 | `segId` / `docCount` / `termCount` / `tfCount` / `segmentFingerprint`(64-hex) / `fingerprint()` / `deepEquals` / IAE ≥ 6 |
| `LayeredIndexExporter` | 导出器（`layered/`） | 内存分层倒排 → P37 段 → 注册 | `export(FulltextStore, DsIndexSegmentStore, long segId) : LayeredSnapshot` / IAE 守卫 |
| `LayeredInvertedView` | 只读视图（`layered/`） | N 段 mmap → level-aware 只读查询 | `open(DsIndexSegmentStore, long[] segIdsOldToNew) : LayeredInvertedView` / `docLen` / `nTerms` / `idf` / `termPayload` / `tf` / `hasDoc` / `hasTerm` / `segmentCount` / `fingerprint` / `close` |

### 3.2 数据流（写入 → 导出 → 零重建读取）

```
写入期（P21/P22 既有，0 改）
  FulltextStore.ingestDoc(docId, text)
      └→ WeightStore.docStatMap / termStatMap / termFreqInDocMap   ← 内存分层倒排（level 烘焙进 mix4 键）

导出期（P42 新增，显式触发）
  new LayeredIndexExporter()
      export(ft, store, segId=10):
        1) ft.weightStoreAllDocKeysForTest()            → 每 docId: ft.getDocStatForTest(docId)      → writer.addDocStat(docId, docLen[6], nTerms[6])
        2) ft.weightStoreAllTermKeys()   [R4 新增]      → 每 uid:   ft.getTermStatForTest(uid)      → writer.addTermStat(uid, idf, payload)
        3) ft.weightStoreAllTfEntries()  [R4 新增]      → 每 (key,tf)                              → writer.addTf(key, tf)
        4) writer.finish()  → seg-10.didx（.tmp + 原子 rename）   ← 崩溃安全点：此时尚未注册
        5) store.append(10, seg)  → manifest = 10
      return new LayeredSnapshot(segId=10, docCount, termCount, tfCount, seg.fingerprint())

读取期（P42 新增，零重建）
  openReader(10) → new SegmentMergeView([r10]) → LayeredInvertedView
      docLen(docId, lv)  = mergeView.getDocStat(docId).docLen[lv]   miss → 0
      nTerms(docId, lv)  = mergeView.getDocStat(docId).nTerms[lv]   miss → 0
      idf(uid)           = mergeView.getTermStat(uid).idf           miss → 0.0
      tf(uid,doc,p,lv)   = mergeView.getTf(FarmHash64.mix4(uid,doc,p,lv))   miss → 0
```

### 3.3 包结构（新增 `layered/` 兄弟包）

```
com/q3lives/ds/database/fulltext/
├── FulltextSegmenter.java          (P21 既有，0 改)
├── FulltextStore.java              (P21 既有；**仅 append-only +2 只读 accessor**，见 R4/§0)
├── TermDictStore.java              (P21 既有，0 改)
├── segment/                        (P37 既有，5 源文件，0 改；递归计数锁定 5)
├── compact/                        (P40 既有，5 源文件，0 改；递归计数锁定 5)
├── cluster/                        (P26~P41 既有，0 改)
│   ├── parser/v3/                  (P38 既有；递归计数锁定 5)
│   ├── agg/                        (P39 既有；递归计数锁定 6)
│   └── groupby/                    (P41 既有；递归计数锁定 5)
└── layered/                        (P42 新增子包；递归计数锁定 3)
    ├── LayeredSnapshot.java              (NEW)
    ├── LayeredIndexExporter.java         (NEW)
    └── LayeredInvertedView.java          (NEW)
```

> **为何必须是 `layered/` 新兄弟包**：`P37_T5.Y2`（`segment/`=5）、`P38_T5.Y2`（`parser/v3/`=5）、`P39_T5.Y2`（`parser/v3/`=5 + `agg/`=6）、`P40_T5.Y1`（`compact/`=5）、`P41_T5.Y1`（`groupby/`=5）五处均用**递归** `collectJavaFiles` —— 在这五棵子树的**任一位置**新增 `.java` 都会顶高递归计数、直接挂掉既有基线 TR。

---

## 4. 接口契约

### 4.0 P21 `FulltextStore` 追加（R4 唯一例外，append-only）

```java
// 追加在既有 weightStoreAllDocKeysForTest() 邻近；语义同款（快照拷贝，不改 WeightStore）
/** P42 R4：枚举 termStat 全键集（uid 升序快照）。只读，既有方法/内嵌类型 0 改。 */
public Set<Long> weightStoreAllTermKeys() throws IOException;

/** P42 R4：导出 tf 键→值全映射（key = FarmHash64.mix4(uid,docId,parentId,level)）。只读，bucket 值在包内解出。 */
public Map<Long, Integer> weightStoreAllTfEntries() throws IOException;
```

### 4.1 LayeredSnapshot

```java
public final class LayeredSnapshot {
    public final long segId;
    public final long docCount;
    public final long termCount;
    public final long tfCount;
    public final String segmentFingerprint;   // = DsIndexSegment.fingerprint()（64-hex）
    public LayeredSnapshot(long segId, long docCount, long termCount, long tfCount, String segmentFingerprint);
    public String fingerprint();              // SHA-256(segId|docCount|termCount|tfCount|segmentFingerprint) 64-hex
    public boolean deepEquals(Object o);
    // 6 IAE 守卫: segId < 0 / 任一 count < 0 / segmentFingerprint null / segmentFingerprint.length() != 64
}
```

### 4.2 LayeredIndexExporter

```java
public final class LayeredIndexExporter {
    public LayeredIndexExporter();
    /**
     * 把 ft 的内存分层倒排（docStat 6-level / termStat / tf）导出为 P37 段并注册。
     * 顺序：writer.finish() 先 → store.append() 后（R9 崩溃安全）。
     */
    public LayeredSnapshot export(FulltextStore ft, DsIndexSegmentStore store, long segId) throws IOException;
    // 4 IAE 守卫: ft null / store null / segId < 0 / segId 已注册（防重复导出覆盖）
}
```

### 4.3 LayeredInvertedView

```java
public final class LayeredInvertedView implements AutoCloseable {
    /** 打开 1..N 个已注册段（旧→新），mmap 零重建（R10）。 */
    public static LayeredInvertedView open(DsIndexSegmentStore store, long[] segIdsOldToNew) throws IOException;
    public int    docLen(long docId, int level);        // 命中 = docStat.docLen[level]；miss = 0
    public int    nTerms(long docId, int level);        // 命中 = docStat.nTerms[level]；miss = 0
    public double idf(long uid);                        // 命中 = termStat.idf；miss = 0.0
    public byte[] termPayload(long uid);                // 命中 = payload；miss = null
    public int    tf(long uid, long docId, long parentId, int level);  // = mergeView.getTf(mix4(...))；miss = 0
    public boolean hasDoc(long docId);
    public boolean hasTerm(long uid);
    public int    segmentCount();
    public String fingerprint();                        // = SegmentMergeView.fingerprint()
    public void   close();                              // 关闭全部 reader（幂等）
    // 4 IAE 守卫: store null / segIds null·空 / 段未注册 / level ∉ [0,5]
}
```

---

## 5. 测试（Gate B 拆 4 批：P22-P24 + P25-P27 + P28-P30 + P31-P42）

### 5.1 Surefire 4 批拆分（继承 P40/P41 模板，新增 P42 入批 4）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
# 批1: P22-P24
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
# 批2: P25-P27
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P25_*,ds.P26_*,ds.P27_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
# 批3: P28-P30
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P28_*,ds.P29_*,ds.P30_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
# 批4: P31-P42（含 P42 own）
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*,ds.P42_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

> **每批前必须先清理 `target\tmp` 与仓库根 `.tmp` + `surefire-reports`**（P39/P41 教训：累积磁盘 / 并发 run 报告串扰会导致 NFR 计时假失败与 TR 数字异常）；改源码或测试后必须先 `test-compile`。

### 5.2 TR 总数 = 31（T1×5 + T2×6 + T3×3 + T4×3 + T5×9 + T6×5）

| T | 范围 | TR 数 | 实装内容 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | `LayeredSnapshot` + 入口骨架 | 5 | `LayeredSnapshot` 6 IAE + `fingerprint()` 64-hex（同字段恒定 / 任一字段变即变）+ `deepEquals` 真值表 + `LayeredIndexExporter` 构造零状态 + `LayeredInvertedView.open` null/空 segIds IAE | `P42_T1_SnapshotSkeletonTest` |
| **T2** | 导出器等价性 | 6 | 小语料 `ingestDoc` 2 文档后 export：① 段 `docCount` == `weightStoreAllDocKeysForTest().size()` ② 段 `termCount` == `weightStoreAllTermKeys().size()` ③ 段 `tfCount` == `weightStoreAllTfEntries().size()` ④ docStat 逐 docId 6-level `docLen`+`nTerms` 全等 ⑤ termStat 逐 uid `idf`（`Double.doubleToLongBits` bit 级）+ payload 全等 ⑥ tf 逐 (key,tf) 全等 + 空 store 导出（0/0/0 合法段可读） | `P42_T2_ExporterEquivalenceTest` |
| **T3** | 只读视图语义 | 3 | ① `docLen`/`nTerms` 命中/miss=0 + level ∉ [0,5] IAE + `hasDoc` ② `tf(uid,doc,parent,level)` 与 `DsIndexSegmentReader.getTf(mix4)` 全等 + miss=0 + `idf`/`termPayload`/`hasTerm` ③ 多段：2 段 `SegmentMergeView` 新段优先覆盖（含同键冲突）+ 经 P40 `SegmentCompactor` 归并后单段 view 与 2 段 view 逐条等价 + `fingerprint` 随段序变化 + `close` 幂等 | `P42_T3_ViewSemanticsTest` |
| **T4** | E2E + 跨进程 | 3 | ① 真实 `FulltextStore`（独立 temp home + 唯一后缀隔离，继承 P31/P34/P39/P40 模板）`ingestDoc` 若干文档 → export → **`FulltextStore.close()`** → 重开 `LayeredInvertedView` 后 docStat/termStat/tf 逐条与导出前内存真值全等（证明「零上游依赖」）② 冷启动 `open` P95 ≤ 200ms（R10 零重建实证）+ 段文件自描述（无上游扫描）③ 跨进程 `snapshot.fingerprint()` 64-hex 一致（子 JVM spawn 复现） | `P42_T4_E2eLayeredSegmentTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 **且六重递归文件数守恒**（根包=3 + segment=5 + parser/v3=5 + agg=6 + compact=5 + groupby=5 + layered=3）+ Y2 NG-3（`layered/` forbidden 引用 + import 白名单 + pom banned 0 命中）+ Y3 TOPK（0 `bucketAccessCounter` / 0 `SearchOptions`）+ Y4 Facade 单调不减（P37/P40/P41 目录源码 grep 0 改 + P21~P41 探针签名全在 + **新增 2 accessor 存在且旧签名不消失**）+ Y5 路由 0 改 + Y6 P21~P42 + `layered/` 3 类 `Class.forName` + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 方法 + Y9 0 TODO/FIXME/HACK | `P42_T5_Gatekeeper9YTest` |
| **T6** | NFR | 5 | export 1k docs P95 ≤ 阈值 + 冷启动 open P95 ≤ 200ms + 3 类 Javadoc ≥ 10 行 + CPD 160 字符窗口 0 重复块 + 0 `System.out.println`/`printStackTrace` + `layered/` IAE 显式守卫 ≥ 8 | `P42_T6_NFR_Test` |

**TR 总数**：5+6+3+3+9+5 = **31 TR**（实跑数字 Gate B 回填）

### 5.3 验收门槛（Gate B 实跑回填）

- [x] P42 own **31/31 TR 0F0E**（T1 5/5 + T2 6/6 + T3 3/3 + T4 3/3 + T5 9/9 + T6 5/5）
- [x] 批 1 P22-P24 = **76/76 0F0E** ✅
- [x] 批 2 P25-P27 = **80/80 0F0E** ✅
- [x] 批 3 P28-P30 = **100 run / 1 已知基线 fail `P28_T6.tr64_noBarePrintAndGte5NullGuards`**（99 0F0E）✅
- [x] 批 4 P31-P42 = **379/379 0F0E**（含 P42 own 31 TR）✅
- [x] 导出等价性（段三列逐条 == 内存 WeightStore 真值）
- [x] 零重建（`FulltextStore.close()` 后重开 view 等价 + 冷启动 open P95 ≤ 200ms）
- [x] 段格式 0 变更（产物 `MAGIC="DIDX"`/`VERSION=1`/4×CRC32 全合法）
- [x] `LayeredSnapshot.fingerprint()` 跨进程恒定
- [x] P37/P40/P41 目录源码 0 改（Y4 实锤）

> **Gate B 实跑汇总**：4 批合计 **635 TR = 634 pass + 1 已知基线 fail**（批 3 唯一 fail 为 P28 遗留，非 P42 引入，沿用不修）。

---

## 6. 逆指标（禁止项）

1. **禁止把段内容全量读进堆**建哈希表（R10 零重建）→ `open` 仅 mmap + 头/CRC 校验。
2. **禁止后台线程 / 定时器 / 自动导出**（0 新增 `ExecutorService` / `Timer`）。
3. **禁止改段格式**（0 新增列 / magic / version 分支；不做 positions/HNSW 入段）。
4. **禁止改 P21 既有方法签名或内嵌类型**（唯一例外 = 2 个 append-only 只读 accessor）。
5. **禁止 uid 重编码 / level 前缀拼接**（USER-HC-0：uid 全局唯一，直接复用 WeightStore 键）。
6. **禁止触碰 `FulltextStore.search` 读路径**（Slice A 不接管搜索）。

---

## 7. 风险与回退

| 风险 | 影响 | 缓解 / 回退 |
|------|------|------------|
| R4 触碰 P21 被用户否决 | Gate A 需重写 | §0 已给方案 A/B/C 与降级路径（仅 docStat 段化） |
| 导出大语料内存峰值 | 导出时 `weightStoreAllTfEntries()` 返回全量 `Map` 占堆 | MVP 限定「显式导出 + 单段」；分片导出/流式枚举列入 Slice B |
| 段体积膨胀（tf 键不可压缩） | 段文件偏大 | P37 列式 + LZ4 已内建（R5 复用）；`LayeredSnapshot.count` 供调用方决策 |
| 多段覆盖语义误用 | 查询结果错误 | R8：段序由调用方显式给定；`fingerprint` 随段序变化可审计 |
| Gate B 批 3 已知基线 fail | 批 3 非全绿 | 沿用既有口径（`P28_T6.tr64` 与 P42 0 关系），不修改基线测试 |

---

## 8. 交付物清单

- 源码：`layered/LayeredSnapshot.java`、`layered/LayeredIndexExporter.java`、`layered/LayeredInvertedView.java`（3 新类）
- P21 追加：`FulltextStore.java` append-only 2 只读 accessor
- 测试：`ds/P42_T1_SnapshotSkeletonTest`、`P42_T2_ExporterEquivalenceTest`、`P42_T3_ViewSemanticsTest`、`P42_T4_E2eLayeredSegmentTest`、`P42_T5_Gatekeeper9YTest`、`P42_T6_NFR_Test`（6 文件 / 31 TR）
- 文档：本 `spec.md`（Gate C 回填）+ `tasks.md`；`docs/dsdb-design-status-and-roadmap.md`（`OQ12-⑳` ⏳→✅ + 挂 `OQ12-㉑`）；Project Memory 追加
- Stage 5：`git add`（仅 P42 相关）→ `git commit` → `git push origin main`

---

## 9. 签批区（Gate A，需用户 VERBATIM 批准）

> 请用户逐条 **VERBATIM** 批准（直接复制下列文本并改写为 **[x]**），特别是 **§0 方案选择**：

- [x] **§0 方案选择**：批准 **方案 A**（P21 追加 2 个 append-only 只读 accessor）
- [x] **§1 Scope**（Slice A：读路径段化 + MVP 边界 0 越界）
- [x] **§2 红线 R1-R10**（六重递归文件数守恒 + R4 P21 例外 + R5 段格式 0 变更）
- [x] **§3 架构**（3 新类全落新子包 `layered/`，`layered/`=3）
- [x] **§4 接口契约**（4.0 P21 append-only / 4.1 Snapshot / 4.2 Exporter / 4.3 View）
- [x] **§5 测试**（31 TR = 5+6+3+3+9+5；Gate B 拆 4 批）
- [x] **§6 逆指标** / **§7 风险与回退** / **§8 交付物清单**

**用户批准原文（VERBATIM）**：`User has approved the given files, continue working.`（采纳 §0 方案 A）

---

## 10. 实装偏差表（Gate C 回填）

| 编号 | 计划 | 实装 | 原因 |
|------|------|------|------|
| D1 | T2 直接 `ingestDoc` 后 export 即校验 termStat 等价 | 测试装置 `@Before` 追加显式 `ft.recomputeIdfForTerms(uids)` 物化 termStat 后再导出 | P21 `updateWeightForLevel` 第 4 步明确 **idf 惰性不落盘**（`FulltextStore.java:1989`）；导出器只搬运内存既有 termStat（R7：0 计算、0 主动 recompute）→ 属测试装置修正，非契约偏差 |
| D2 | T2 `tr25` 断言「段文件总长 4KB 对齐」 | 改为断言 `docStatOff % HEADER_SIZE == 0` + `varOff % HEADER_SIZE == 0` | 段文件总长 = `varOff + varLen`，不保证整体对齐；**section 偏移** 4KB 对齐才是 P37 真契约 |
| D3 | T4 `tr42` 断言 `docLen(1000L,0) == 1001` | 修正为 `docLen(1000L,0) == 1` + `docLen(3999L,0) == 3000` | 原断言混淆 docId 与 docLen 语义（`addDocStat(1000+i, six(i+1), ...)` → `docLen[0] = i+1`）；测试内部修正 |
| D4 | T3 `@Before setUp()` 无 throws | `setUp() throws Exception` | `new DsIndexSegmentStore(String)` 声明 `IOException` → 编译期修正 |

> **净偏差结论**：0 契约偏差（R1-R10 全守恒）；D1-D4 全部为**测试装置内部修正**，主源码（`layered/` 3 类 + P21 2 accessor）与 spec §3/§4 契约完全一致。

---

## 11. Probe 锚点（自核实锤，非转述）

| 锚点 | 文件 | 核实结论 |
|------|------|---------|
| 根包 top-level = 3 | `fulltext/` 目录 | `{FulltextSegmenter, FulltextStore, TermDictStore}`（`cluster/`、`compact/`、`segment/` 均为子包） |
| 分层常量 | `TermDictStore.java:12-17` | `DS0_CHAR=0 … DS5_DOC_STRUCT=5` |
| USER-HC-0 | `TermDictStore.java:36` | `toUid(String text, boolean createIfAbsent)` 无 level 参数 |
| `mix4` 键 | `TermDictStore.java:319/327` | `FarmHash64.mix4(uid, docId, parentId, (long)level)` |
| docStat 6-level 列 | `SegmentColumnCodec.java:20,35-43` | `[docId 8B][docLen[0..5] 6×4B][nTerms[0..5] 6×4B] = 56B` |
| tf 列 | `SegmentColumnCodec.java:22,52` | `[key 8B][tf 4B] = 12B`；reader `getTf` miss=0 |
| writer 原子落盘 | `DsIndexSegmentWriter.java:174-187` | `.tmp` + `Files.move(REPLACE_EXISTING)`；`finish` 二次 IAE |
| writer 三列升序 | `DsIndexSegmentWriter.java:196-204` | `sortedKeys`（docId/uid/key 升序） |
| store manifest | `DsIndexSegmentStore.java:85-105,130-156` | `append` 前校验段文件已存在 + 指纹一致；重复 append IAE；manifest 为**插入序** |
| reader 零重建 | `DsIndexSegmentReader.java:69-91` | mmap + `MAGIC`/`VERSION` + 4×CRC32 校验，0 上游扫描 |
| merge view | `SegmentMergeView.java:53-111` | 3 IAE；`getDocStat`/`getTermStat` 新→旧首个命中，`getTf` 首个非零 |
| compaction | `compact/` 5 类 | ≈ `SegmentCompactor.compact(target, sources)` / `SegmentCompactionRunner.run(store, plan)` |
| 枚举缺口 | `FulltextStore.java:755,1762-1773` | 仅有 `weightStorePublic()`（注明「生产代码不依赖」）+ `weightStoreAllDocKeysForTest()`；**termStat 键集 / tf 键值映射无公开枚举 → R4 偏差根因** |
| 六重递归文件数红线 | `P37_T5:79` / `P38_T5:85` / `P39_T5:83,102` / `P40_T5:Y1` / `P41_T5:Y1` | 全部使用**递归** `collectJavaFiles` → 五棵子树不可新增 `.java` |
