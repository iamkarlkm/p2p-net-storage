# P51 OQ12-㉙① 跨段词网聚合（词网段导出 / mmap 零重建跨段邻接视图 / 上游 0 改）—— spec.md（**✅ Gate A 已签批 / ✅ Gate B / ⏳ Gate C**）

> 用户「继续」推进（承接 P50 永久闭环 `d7660eb` + P42/P43 明确「不做 跨段词网聚合」遗留项）→ SP3.1 Gate A（本文件 + tasks.md）→ **用户 VERBATIM 批准** → Implement T1-T6 → Gate B 拆批回归 → Gate C 洁癖 5/5 → Stage 5 commit/push。
>
> 本文件为 **Gate A 已签批稿**：§0 五项裁决已由用户以「继续」采纳全部默认（`Q0=①` / `Q1=A` / `Q2=fulltext/wordnet/` / `Q3=纯显式 export` / `Q4=新段优先短路覆盖 + 段序显式给定`）；9Y 自检清单 / §5.3 Gate B 实跑 / §9 签批区 / §10 实装偏差表已在 Gate C 回填。

## Gate C 9Y 自检清单（Gate C 回填实锤；目标 = `P51_T5_Gatekeeper9YTest` 9/9 0F0E）

> ✅ 实跑 `P51_T5_Gatekeeper9YTest`：`Tests run: 9, Failures: 0, Errors: 0`（Gate B 批 4c 实锤）。

- [x] Y1 **ZeroLeak3=3 + 递归文件数守恒**：`fulltext/` 根包 top-level 恒 = 3；现有 10 处锁定包（`segment/`=5、`cluster/parser/v3/`=5、`cluster/agg/`=6、`compact/`=5、`cluster/groupby/`=5、`layered/`=3、`cluster/rowsource/`=2、`sched/`=3、`autocompact/`=3、`autoseg/`=3）全不变 + **新增 `wordnet/`=3** → `P51_T5.y1_rootPackageThreeAndElevenLockedPackagesAndWordnet`
- [x] Y2 **NG-3 零依赖**：0 新增 netty/grpc/p2p.client 引用 + `p2p-db/pom.xml` 0 新增 dep/plugin → `P51_T5.y2_wordnetZeroForbiddenImportsAndPomBannedZero`
- [x] Y3 **TOPK 铁律**：0 改 K、0 触 `SearchOptions`、`bucketAccessCounter[1025]` 0 污染（**去注释后**判定，允许 Javadoc 说明性提及）→ `P51_T5.y3_topkInvariantNoBucketCounterPollutionAfterStripComments`
- [x] Y4 **Facade 单调不减**：P21 `FulltextStore` + P37 `segment/` 5 类 + P40 `compact/` 5 类 + P42 `layered/` 3 类 + P43/P47/P48/P49/P50 源码 **0 改**；P21 仅 append-only 追加只读 accessor（见 R4）→ `P51_T5.y4_facadeMonotonicAndP21AccessorsAdded`
- [x] Y5 **路由公式**：0 改 `ShardRouter` / `DsIdLayout` → `P51_T5.y5_routerFormulasUnchanged`
- [x] Y6 **P21~P51 编译兼容**：既有类型 0 改 + 全类存在（含 `wordnet/` 3 类 `Class.forName`）→ `P51_T5.y6_allClassesPresentAndCompileCompatible`
- [x] Y7 **HybridHit ctor=2**：0 改 → `P51_T5.y7_hybridHitCtorTwoUnchanged`
- [x] Y8 **ShardTransport 5 方法**：0 改 → `P51_T5.y8_shardTransportFiveMethodsUnchanged`
- [x] Y9 **0 TODO/FIXME/HACK**（新增源码）→ `P51_T5.y9_wordnetNoTodoFixmeHack`

---

## 0. 裁决（Gate A 已由用户「继续」采纳全部默认）

> **Q0 Scope 确认（本阶段候选）**
>
> 本 spec 按用户「继续」推进，默认锚定 **OQ12-㉙ 候选 ① = 跨段词网聚合**（自 OQ12-㉓/㉔/㉕/㉖/㉗/㉘ 候选项 ① 一路顺延至今）。若用户实为选择 ②（真·列扫描下推）或 ③（服务端聚合函数下发 / ORDER BY）或 ④（Other），请于签批时重定向，本文件作废重起。**请确认：`Q0 = ①`。**

> **Q1 词网数据落盘方式（决定是否触碰 R5 段格式红线 + 是否改 P37 `segment/`）**
>
> Probe 实锤（见 §11）：`neighborsWithin` / `distanceBetween` / `topKNeighbors` **100% 依赖内存态 WordNet** —— `WeightStore.termPositionsMap`（positions）+ `distL[d]`/`distR[d]` 距离桶（`DIST_MAX=1024`）+ `distPairMap` + `uidRing`；而 P37 DIDX 段（`MAGIC="DIDX"` / `VERSION=1`）**仅 4 section**（docStat / termStat / tf / var），**无 positions / 无邻接列**。且 `termStat.payload` 槽位**已被 P23 HNSW 向量占用**（`FulltextStore.java:406-418`）→ 无法复用该槽位存邻接。
>
> | 选项 | 方案 | 新增类 | 触碰红线 | 推荐 |
> |------|------|--------|---------|------|
> | **A** | **新建独立「词网段」工件**：独立目录 `rootDir/wordnet/` + 自有 MAGIC（`'D','N','E','T'`）+ 自有 `VERSION=1`，沿用 P37 的「4KB 头 + 4KB 对齐 section + 4×CRC32 + LZ4 动态区」自描述模式；键 = `mix4(docId,parentId,level,srcUid)`（long，无 uid≥0 约束），payload = L/R 邻接 uid 序列（含 d）。**DIDX 段格式字面 0 变更（R5 保持）；P37 `segment/` 5 类字面 0 改（R4 保持）** | 3（落新兄弟包 `wordnet/`） | **无**（纯 additive 新工件） | ⭐**推荐** |
> | B | **扩展 DIDX 为 `VERSION=2` 新增 pos/邻接段列**：复用同一 `seg-<id>.didx` 工件 | 3 | **破 R5（段格式变更）+ 破 R4（须改 `SegmentColumnCodec`/`Writer`/`Reader`/`DsIndexSegment`/`Store` 5 类）** → 须重跑 P37/P40/P42/P43 全量基线 | 备选（语义最「原生」，代价最大） |
> | C | **降级最小切片**：仅 positions 导出 + 跨段 positions 点查（**不做** 邻接/距离桶聚合） | 3 | 无 | 备选（若 P51 想更薄） |
>
> **请裁决**：`Q1 = A`（推荐，独立词网段工件）或 `Q1 = B`（扩展 DIDX VERSION=2）或 `Q1 = C`（最小切片）。

> **Q2 新类落包（决定是否破 ZeroLeak/递归基线）**
>
> `fulltext/` 根包 top-level 恒 = 3；现有 10 处锁定包递归文件数已全部锁死。P51 新增的词网类**不得**落任何已锁死计数包。默认落**新兄弟包** `com.q3lives.ds.database.fulltext.wordnet`（递归计数锁定 = 3，对齐 P42 `layered/` / P47 `sched/` / P48 `autocompact/` / P49 `autoseg/` 先例）。**请确认：`Q2 = 新兄弟包 fulltext/wordnet/（=3）`。**

> **Q3 触发语义（决定是否挂钩写路径）**
>
> - 默认：**纯显式 export**（对齐 P42 Slice A）—— `WordNetExporter.export(ft, rootDir, segId)` 由调用方显式触发；**不做** 写路径自动挂钩（P49 `AutoSegmentHook` 0 改）、**不做** 后台线程 / 定时器（R10）。
> - **请确认：`Q3 = 纯显式 export（0 新线程 / 0 写路径挂钩）`。**

> **Q4 跨段聚合语义（决定查询合并口径）**
>
> 多词网段叠加语义对齐 P40 `SegmentMergeView`：**「新段优先」短路覆盖**——对同一键，从新到旧取**首个命中**段；`neighborsWithin` / `distanceBetween` / `topKNeighbors` 在该合并视图上逐键查询。段序由调用方**显式给定**（旧→新），不依赖 manifest 物理行序。**请确认：`Q4 = 新段优先短路覆盖 + 段序由调用方显式给定`。**

> 其余 Scope / 红线 R1-R13 / 类清单 / MVP 边界如下，请逐条签批（§9）。

---

## 1. Scope（用户 OQ12-㉙ 候选项 ①）

> 原文：`① 跨段词网聚合（自 OQ12-㉓/㉔/㉕/㉖/㉗/㉘ 候选顺延，多段 positions/neighborsWithin 聚合，需 positions 入段 → 段格式变更需 Gate A）`

**P51 = 跨段词网聚合（Slice A：词网段导出 + mmap 零重建跨段邻接视图）**：把 P21 `FulltextStore` 已驻内存的**词网**（positions 命中区间 + 距离桶 L/R 邻接 + 距离对）**导出为独立不可变「词网段」工件**，并提供 **mmap 零重建**的**跨段词网只读视图**；使「词网」（`neighborsWithin` / `distanceBetween` / `topKNeighbors` 的底层数据）**首次具备「落盘 + 零 startup 重建 + 可被多段叠加聚合 + 可跨进程/跨机分发」**四项能力。

1. **`WordNetExporter`（词网段导出器）**：`FulltextStore` 内存词网 → 逐 (docId,parentId,level) 遍历 L/R 邻接桶 → 喂词网段写入口 → `finish()` 原子落盘 → 注册 manifest；返回 `WordNetSnapshot`。**导出即快照**（同源同批 → 段指纹恒定）。
2. **`WordNetSegmentView`（跨段词网只读视图）**：包装 N 段「新→旧」短路覆盖，提供 `neighborsWithin(targetUid, docId, parentId, level, maxD)` / `distanceBetween(leftUid, rightUid, docId, parentId, level)` / `topKNeighbors(targetUid, docId, parentId, level, K)` / `positions(uid, docId, parentId, level)`；`open` 仅 mmap + 头/CRC 校验，**禁止把段全量读进堆建哈希表**。
3. **`WordNetSnapshot`（不可变快照描述）**：`segId` + `adjCount`/`ctxCount`/`posCount` + `segmentFingerprint`(64-hex) + SHA-256 `fingerprint()` + `deepEquals` + IAE 守卫；跨进程 / 跨 run 恒定。

**「跨段聚合」如何被承载（关键）**：词网段以 `mix4(docId,parentId,level,srcUid)` 为键存「该上下文下 srcUid 的 L/R 邻接 uid 序列（按 d 升序，含 d）」；多段叠加时同键「新段优先」——因此「跨段词网聚合」= **多段同键短路合并**，与 P40 `SegmentMergeView` 语义对齐（R8），**0 新增段格式（R5）**、**0 改 P37 `segment/`（R4）**。

**MVP 边界（0 越界）**：
- 仅做「词网（positions + L/R 邻接 + 距离对）导出为独立词网段 + mmap 零重建跨段只读视图 + 不可变快照描述」
- **不做** DIDX 段格式变更（R5：DIDX `MAGIC`/`VERSION=1`/4 section 字面不变，0 新增列/分支）
- **不做** 写路径挂钩：`ingestDoc` / `putDoc` / `clearDoc` / `updateWeightForLevel` 仍 100% 写 P22 `WeightStore`（0 改）；段仅由**显式** `export` 触发
- **不做** 后台自动导出 / 定时器 / 增量导出（**0 新增线程**，R10）
- **不做** `FulltextStore.search` 读路径切换（P43 stat 列视图语义 0 改）；`WordNetSegmentView` 是**并行只读视图**，**不接管** P21 搜索
- **不做** 向量（P23 HNSW）入词网段
- **不做** 多线程并发导出/合并 / tombstone / 段淘汰
- P37 `segment/` 5 类 0 改；P40 `compact/` 5 类 0 改；P42 `layered/` 3 类 0 改；P47 `sched/` / P48 `autocompact/` / P49 `autoseg/` / P50 `columnar.pushdown` 0 改；P22 `WeightStore` 0 改
- **P21 `FulltextStore` 仅 append-only 追加只读 accessor（R4，需 Gate A 批准）**；`TermDictStore` / `FulltextSegmenter` 0 改
- **0 新增 Maven dep**（复用既有 `lz4-java`）
- 3 新类全放**新兄弟包** `com.q3lives.ds.database.fulltext.wordnet`（ZeroLeak3=3 永保）

---

## 2. 红线（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3 + 递归文件数守恒** | `fulltext/` 根包 top-level 类数严格 = 3（`{TermDictStore, FulltextStore, FulltextSegmenter}`）；现有 10 处锁定包全不变；P51 3 新类 100% 落**新包** `wordnet/`（=3） |
| R2 | **NG-3 零依赖** | `p2p-db/pom.xml` 0 新增 dep/plugin；`wordnet/` 源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*`；仅 JDK + first-party import |
| R3 | **TOPK 铁律** | 导出/读取均为纯数据搬运；0 改 K / 0 改 `SearchOptions`；`bucketAccessCounter[1025]` 0 污染 |
| R4 | **Facade 单调不减（P21 唯一例外：append-only）** | P37 `segment/` 5 类、P40 `compact/` 5 类、P42 `layered/` 3 类、P47 `sched/` 3 类、P48 `autocompact/` 3 类、P49 `autoseg/` 3 类、P50 系列源码**字节级 0 改**；P21 `FulltextStore` / P22 `WeightStore` / P23~P50 公开签名 0 改；**P21 仅新增只读 accessor（枚举词网邻接/positions），既有方法/内嵌类型一字不删改**（§0 方案 A） |
| R5 | **DIDX 段格式 0 变更** | 复用 P37 `MAGIC="DIDX"` + `VERSION=1` + `HEADER_SIZE=4096` + `CRC_SECTIONS=4` 字面不动；词网段为**独立工件**（自有 MAGIC/`VERSION`），不写入 `segment/` 目录、不改 `DsIndexSegment*` |
| R6 | **USER-HC-0 与 TermDict 零触碰** | `TermDictStore.toUid(String, boolean)` **永远无 level 参数**；`TermDictStore` / `FulltextSegmenter` 源码 0 改；词网键直接复用既有 `mix4` 口径，**不做 uid 重编码** |
| R7 | **导出确定性** | 三列升序由词网段写入口排序保证（ctx 键↑ / 邻接按 d↑）；同源同批导出 → `segmentFingerprint` 跨进程 / 跨 run 恒定 |
| R8 | **覆盖语义确定性** | 单段内同键 = 写入口「后者覆盖」（继承 P37/P22 口径）；多段视图 = 「新→旧」首个命中；**段序由调用方显式给定**（旧→新），不依赖 manifest 物理行序 |
| R9 | **崩溃安全** | 导出顺序必须 `finish()`（`.tmp` + 原子 `Files.move`）**先于** manifest 注册；任一崩溃点下，未注册的半成品段**不在 manifest**，`list()` 不可见 |
| R10 | **零重建 & 0 新线程** | `WordNetSegmentView.open` 仅 mmap + 头校验 + 4×CRC32；**禁止**把段内容全量读进堆建哈希表（逆指标：open 代价与段体积无关，冷启动 P95 ≤ 200ms）；**0 新增 `ExecutorService`/`Timer`/后台线程** |
| R11 | **写路径透明** | 本阶段 0 挂钩写路径；`ingestDoc`/`putDoc`/`clearDoc` 行为字节级不变（对齐 P48/P49 R11/R13 口径） |
| R12 | **等价性铁律** | 显式 `export` 忠实快照后，`WordNetSegmentView.neighborsWithin` / `distanceBetween` / `topKNeighbors` 与内存态 `FulltextStore` 同 API 输出**逐条等价**（uid + 方向 + 距离 d，集合/序列口径一致） |
| R13 | **0 TODO/FIXME/HACK** | 新增源码 0 处遗留标记 |

---

## 3. 架构（3 个新类，P37/P40/P42/P47/P48/P49/P50 源码 0 改，P21 仅 append-only）

### 3.1 新增清单

| 名称 | 类型 | 职责 | 关键字段/方法 |
|------|------|------|---------------|
| `WordNetSnapshot` | 不可变 POJO（`wordnet/`） | 一次导出的不可变快照描述 | `segId` / `ctxCount` / `adjCount` / `posCount` / `segmentFingerprint`(64-hex) / `fingerprint()` / `deepEquals` / IAE ≥ 6 |
| `WordNetExporter` | 导出器（`wordnet/`） | 内存词网 → 词网段 → 注册 | `export(FulltextStore, String wordNetRootDir, long segId) : WordNetSnapshot` / IAE 守卫 |
| `WordNetSegmentView` | 只读视图（`wordnet/`） | N 段 mmap → 跨段词网只读查询 | `open(String wordNetRootDir, long[] segIdsOldToNew)` / `neighborsWithin` / `distanceBetween` / `topKNeighbors` / `positions` / `segmentCount` / `fingerprint` / `close`（内嵌 `static Store` 承担 manifest/mmap 生命周期；ZeroLeak3 只计 top-level，内嵌类合法） |

### 3.2 数据流（写入 → 导出 → 零重建读取）

```
写入期（P21/P22 既有，0 改）
  FulltextStore.ingestDoc(docId, text)
      └→ WeightStore.termPositionsMap（positions）
      └→ distL[d] / distR[d] 距离桶（DIST_MAX=1024）+ distPairMap + uidRing   ← 内存词网

导出期（P51 新增，显式触发）
  new WordNetExporter()
      export(ft, rootDir, segId=10):
        1) ft.wordNetAllContextKeys()      [R4 新增]  → 每 (docId,parentId,level)
        2) ft.wordNetNeighborsForCtx(...)  [R4 新增]  → L/R 邻接 (uid,dir,d) 序列
        3) ft.wordNetPositionsForCtx(...)  [R4 新增]  → positions 命中区间
        4) finish() → seg-10.dnet（.tmp + 原子 rename）  ← 崩溃安全点：此时尚未注册
        5) 注册 manifest
      return new WordNetSnapshot(segId=10, ctxCount, adjCount, posCount, fingerprint)
```

### 3.3 包结构（新增 `wordnet/` 兄弟包）

```
com/q3lives/ds/database/fulltext/
├── FulltextSegmenter.java          (P21 既有，0 改)
├── FulltextStore.java              (P21 既有；**仅 append-only +只读 accessor**，见 R4/§0)
├── TermDictStore.java              (P21 既有，0 改)
├── segment/                        (P37 既有，5 源文件，0 改；递归计数锁定 5)
├── compact/                        (P40 既有，5 源文件，0 改；递归计数锁定 5)
├── layered/                        (P42 既有，3 源文件，0 改；递归计数锁定 3)
├── sched/                          (P47 既有，3；锁定 3)
├── autocompact/                    (P48 既有，3；锁定 3)
├── autoseg/                        (P49 既有，3；锁定 3)
├── cluster/                        (P26~P50 既有，0 改)
│   ├── parser/v3/                  (P38 既有；锁定 5)
│   ├── agg/                        (P39 既有；锁定 6)
│   ├── groupby/                    (P41 既有；锁定 5)
│   └── rowsource/                  (P45/P46 既有；锁定 2)
└── wordnet/                        (P51 新增子包；递归计数锁定 3)
    ├── WordNetSnapshot.java               (NEW)
    ├── WordNetExporter.java               (NEW)
    └── WordNetSegmentView.java            (NEW；内嵌 static Store)
```

> **为何必须是 `wordnet/` 新兄弟包**：既有各 `*_T5` Gatekeeper 用**递归** `collectJavaFiles` 锁定 10 处子树计数 —— 在其中**任一位置**新增 `.java` 都会顶高递归计数、直接挂掉既有基线 TR（对齐 P42 §3.3 / P47/P48/P49 先例）。

---

## 4. 接口契约

### 4.0 P21 `FulltextStore` 追加（R4 唯一例外，append-only）

```java
// 语义同款（只读快照/枚举，不改 WeightStore）
/** P51 R4：枚举词网全部上下文键 (docId,parentId,level) 快照。只读，既有方法/内嵌类型 0 改。 */
public List<long[]> wordNetAllContextKeys() throws IOException;

/** P51 R4：枚举某上下文下全部 L/R 邻接 (srcUid, neighborUid, dir, d)。只读。 */
public List<long[]> wordNetNeighborsForCtx(long docId, long parentId, int level) throws IOException;

/** P51 R4：枚举某上下文下某 uid 的 positions 命中区间。只读。 */
public List<PositionSpan> wordNetPositionsForCtx(long uid, long docId, long parentId, int level) throws IOException;
```

### 4.1 WordNetSnapshot

```java
public final class WordNetSnapshot {
    public final long segId;
    public final long ctxCount;
    public final long adjCount;
    public final long posCount;
    public final String segmentFingerprint;   // 64-hex
    public WordNetSnapshot(long segId, long ctxCount, long adjCount, long posCount, String segmentFingerprint);
    public String fingerprint();              // SHA-256(...) 64-hex
    public boolean deepEquals(Object o);
    // 6 IAE 守卫: segId < 0 / 任一 count < 0 / segmentFingerprint null / length != 64
}
```

### 4.2 WordNetExporter

```java
public final class WordNetExporter {
    public WordNetExporter();
    /** 把 ft 的内存词网（positions + L/R 邻接）导出为词网段并注册。finish() 先 → 注册后（R9）。 */
    public WordNetSnapshot export(FulltextStore ft, String wordNetRootDir, long segId) throws IOException;
    // 4 IAE 守卫: ft null / rootDir null·空 / segId < 0 / segId 已注册（防重复导出覆盖）
}
```

### 4.3 WordNetSegmentView

```java
public final class WordNetSegmentView implements AutoCloseable {
    /** 打开 1..N 个已注册词网段（旧→新），mmap 零重建（R10）。 */
    public static WordNetSegmentView open(String wordNetRootDir, long[] segIdsOldToNew) throws IOException;
    public List<Neighbor> neighborsWithin(long targetUid, long docId, long parentId, int level, int maxD);
    public int             distanceBetween(long leftUid, long rightUid, long docId, long parentId, int level);
    public List<Neighbor>  topKNeighbors(long targetUid, long docId, long parentId, int level, int K);
    public List<PositionSpan> positions(long uid, long docId, long parentId, int level);
    public int    segmentCount();
    public String fingerprint();              // 随段序变化
    public void   close();                    // 关闭全部 reader（幂等）
    // 4 IAE 守卫: rootDir null·空 / segIds null·空 / 段未注册 / level ∉ [0,5]
}
```

---

## 5. 测试（Gate B 拆 6 批）

### 5.1 Surefire 拆批（继承 P50 模板，P51 入批 4c）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P25_*,ds.P26_*,ds.P27_*,ds.P28_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P29_*,ds.P30_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*,ds.P42_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P43_*,ds.P44_*,ds.P45_*,ds.P46_*,ds.P47_*,ds.P48_*,ds.P49_*,ds.P50_*,ds.P51_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

> **每批前必须先清理 `target\tmp` 与仓库根 `.tmp` + `surefire-reports`**（P39/P41/P48/P49/P50 教训：累积磁盘 / 并发 run 串扰致 NFR 计时假失败与 TR 数字异常）；改源码或测试后必须先 `test-compile`（`surefire:test` 单独运行不重编测试类）；长跑用 `*> gatebN.log` 落盘再 `Grep`（规避管道缓冲）。

### 5.2 TR 计划（实跑数字 Gate B 回填，**禁止预填**）

| T | 范围 | 计划 TR | 实装内容 | 目标测试类 |
|---|------|--------|---------|--------|
| **T1** | `WordNetSnapshot` + 入口骨架 | 5 | 快照 6 IAE + `fingerprint()` 64-hex（同字段恒定/任一变即变）+ `deepEquals` 真值表 + 导出器构造零状态 + `open` null/空 segIds IAE | `P51_T1_SnapshotSkeletonTest` |
| **T2** | 导出器等价性 | 6 | 小语料 `buildDistForSeq` + positions 后 export：① `ctxCount` ② `adjCount` ③ `posCount` ④ 逐 (ctx,srcUid) L/R 邻接 (uid,dir,d) 全等 ⑤ positions 区间全等 ⑥ 空词网导出（0/0/0 合法段可读） | `P51_T2_ExporterEquivalenceTest` |
| **T3** | 只读视图语义 | 4 | ① `neighborsWithin` 命中/miss + level IAE ② `distanceBetween` 方向语义（L→R 与内存态一致）③ `topKNeighbors` TOPK 铁律（d 递增/满 K 即返）④ 多段「新段优先」覆盖 + `fingerprint` 随段序变化 + `close` 幂等 | `P51_T3_ViewSemanticsTest` |
| **T4** | E2E + 跨进程 | 4 | ① 真实 `FulltextStore`（独立 temp home + 唯一后缀）构建词网 → export → **`FulltextStore.close()`** → 重开 view 后邻接/距离/positions 与导出前内存真值逐条全等（零上游依赖）② 冷启动 `open` P95 ≤ 200ms（R10 实证）③ 跨进程 `snapshot.fingerprint()` 64-hex 一致（子 JVM spawn）④ **R12 等价性**：view 三 API ≡ `FulltextStore` 同 API 输出 | `P51_T4_E2eCrossSegmentWordNetTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 **且递归文件数守恒**（根包=3 + 10 处锁定包 + `wordnet/`=3）+ Y2 NG-3 + Y3 TOPK + Y4 Facade 单调不减（P21 增量 + 旧签名不消失）+ Y5 路由 0 改 + Y6 P21~P51 全类存在 + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 方法 + Y9 0 TODO/FIXME/HACK | `P51_T5_Gatekeeper9YTest` |
| **T6** | NFR | 5 | export 1k ctx P95 ≤ 阈值 + 冷启动 open P95 ≤ 200ms + 3 类 Javadoc ≥ 10 行 + CPD 160 字符窗口 0 重复块 + 0 `System.out.println`/`printStackTrace` + `wordnet/` IAE 显式守卫 ≥ 8 | `P51_T6_NFR_Test` |

**TR 计划总数**：5+6+4+4+9+5 = **33 TR**（实跑数字 Gate B 回填）

### 5.3 验收门槛（Gate B 实跑回填）

- [x] P51 own **33/33 TR 0F0E**（T1 5 + T2 6 + T3 4 + T4 4 + T5 9 + T6 5）
- [x] 批 1 P22-P24 = 76/76
- [x] 批 2 P25-P28 = 112/1F（唯一 fail = 已知基线 `P28_T6.tr64`，沿用既有口径，不阻塞）
- [x] 批 3 P29-P30 = 68/68
- [x] 批 4a P31-P36 = 193/193
- [x] 批 4b P37-P42 = 186/186
- [x] 批 4c P43-P51 = 281/281（含 P51 own）
- [x] **Gate B 合计 = 916 跑 1 Fail（已知基线）/ 0 Error**
- [x] R12 等价性（view 三 API ≡ 内存 `FulltextStore` 同 API）→ `P51_T4.tr44` + `P51_T2`
- [x] R10 零重建（`FulltextStore.close()` 后重开 view 等价 + 冷启动 open P95 ≤ 200ms）→ `P51_T4.tr41`/`tr42` + `P51_T6.tr62`
- [x] R5 DIDX 段格式 0 变更（`MAGIC`/`VERSION=1`/4×CRC32 字面不动）→ `P51_T5.y4` + `P51_T6.tr63`（CPD）
- [x] R4 P37/P40/P42/P47/P48/P49/P50 目录源码 0 改（Y4 实锤）→ `P51_T5.y4`

---

## 6. 逆指标（禁止项）

1. **禁止把词网段内容全量读进堆**建哈希表（R10 零重建）→ `open` 仅 mmap + 头/CRC 校验。
2. **禁止后台线程 / 定时器 / 自动导出**（0 新增 `ExecutorService` / `Timer`）。
3. **禁止改 DIDX 段格式**（0 新增列 / magic / version 分支；0 触碰 `DsIndexSegment*`）。
4. **禁止改 P21 既有方法签名或内嵌类型**（唯一例外 = append-only 只读 accessor）。
5. **禁止 uid 重编码 / level 前缀拼接**（USER-HC-0：uid 全局唯一，直接复用既有 `mix4` 口径）。
6. **禁止触碰 `FulltextStore.search` 读路径**（本阶段不接管搜索）。

---

## 7. 风险与回退

| 风险 | 影响 | 缓解 / 回退 |
|------|------|------------|
| R4 触碰 P21 被用户否决 | Gate A 需重写 | §0 已给方案 A/B/C 与降级路径（C：仅 positions 导出） |
| Q1 选 B（VERSION=2）触 R5/R4 | 须重跑 P37/P40/P42/P43 全量基线 | 默认推荐 A（独立工件，0 触碰 DIDX） |
| 词网段体积膨胀（邻接重复 uid） | 段文件偏大 | 复用「4KB 对齐 + LZ4 动态区」模式；`WordNetSnapshot.count` 供调用方决策 |
| 多段覆盖语义误用 | 查询结果错误 | R8：段序由调用方显式给定；`fingerprint` 随段序变化可审计 |
| 内存态与段态邻接口径漂移 | R12 等价性失败 | T4 以内存态真值逐条对照（同一 `FulltextStore` 实例导出前采样） |
| Gate B 批 2 已知基线 fail | 批 2 非全绿 | 沿用既有口径（`P28_T6.tr64` 与 P51 0 关系），不修改基线测试 |

---

## 8. 交付物清单

- 源码：`wordnet/WordNetSnapshot.java`、`wordnet/WordNetExporter.java`、`wordnet/WordNetSegmentView.java`（3 新类）
- P21 追加：`FulltextStore.java` append-only 只读 accessor
- 测试：`ds/P51_T1_*.java` ~ `P51_T6_*.java`（6 文件）
- 文档：本 `spec.md`（Gate C 回填）+ `tasks.md`；`docs/dsdb-design-status-and-roadmap.md`（`OQ12-㉙①` ⏳→✅ + 挂 `OQ12-㉚`）；Project Memory 追加
- Stage 5：逐路径 `git add`（仅 P51 相关）→ `git commit` → `git push origin main`

---

## 9. 签批区（Gate A，需用户 VERBATIM 批准）

> 用户以「继续」采纳 §0 全部默认裁决，逐条置 **[x]**。

- [x] **§0 Q0 Scope**：`Q0 = ①`（跨段词网聚合）
- [x] **§0 Q1 实现方式**：`Q1 = A`（独立词网段工件，DIDX 0 变更 / P37 `segment/` 0 改）
- [x] **§0 Q2 落包**：`Q2 = fulltext/wordnet/`（=3）
- [x] **§0 Q3 触发语义**：`Q3 = 纯显式 export`（0 新线程 / 0 写路径挂钩）
- [x] **§0 Q4 聚合语义**：`Q4 = 新段优先短路覆盖 + 段序调用方显式给定`
- [x] **§1 Scope**（Slice A：词网段导出 + mmap 零重建跨段视图 + MVP 边界 0 越界）
- [x] **§2 红线 R1-R13**（ZeroLeak3 + 递归守恒 + R4 P21 append-only 例外 + R5 DIDX 0 变更 + R10 零重建）
- [x] **§3 架构**（3 新类全落新子包 `wordnet/`，`wordnet/`=3）
- [x] **§4 接口契约**（4.0 P21 append-only / 4.1 Snapshot / 4.2 Exporter / 4.3 View）
- [x] **§5 测试**（33 TR = 5+6+4+4+9+5；Gate B 拆 6 批）
- [x] **§6 逆指标** / **§7 风险与回退** / **§8 交付物清单**

**用户批准原文（VERBATIM）**：`继续`（采纳 §0 全部默认：`Q0=①` / `Q1=A` / `Q2=fulltext/wordnet/` / `Q3=纯显式 export` / `Q4=新段优先短路覆盖 + 段序调用方显式给定`）

---

## 10. 实装偏差表（Gate C 回填）

| 编号 | 计划 | 实装 | 原因 |
|------|------|------|------|
| D1 | `P51_T1.tr14` 断言「`WordNetExporter` 0 字段（零状态）」= `getDeclaredFields().length == 0` | 改为仅统计**非静态**字段（`!Modifier.isStatic(...)`） | `WordNetExporter` 含 `private static final int INDEX_REC = 16;` 静态常量，原断言误计为 1 → **测试类自身缺陷，非源码缺陷**；零状态语义不变（0 实例字段 / 0 资源持有） |
| D2 | `P51_T4.tr43` 子 JVM 输出直接 `trim()` 比对 64-hex 指纹 | 逐行取**首个匹配 `[0-9a-f]{64}`** 的行再比对 | 环境变量 `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8` 使子 JVM stdout 首行输出 `Picked up JAVA_TOOL_OPTIONS: ...` 污染 → **测试类自身缺陷，非源码缺陷** |
| D3 | §5.2 T5 计划表述「根包=3 + 10 处锁定包 + `wordnet/`=3」 | 实装为「根包=3 + **11 处锁定包**（含 `wordnet/`）」 | `wordnet/` 纳入锁定包后总数为 11；表述统一，语义等价 |
| D4 | §5.2 T6「CPD 160 字符窗口 0 重复块」 | 实装：**去注释 / import / 空白后**跨文件 160 字符窗口采样 → `dupBlocks=0` | 3 新类共享少量结构性样板（如 `close()` 骨架），去噪后确 0 实质重复块 |
| D5 | 批 2 期望全绿 | 批 2 = 112/1F（`P28_T6.tr64_noBarePrintAndGte5NullGuards`：裸 `System.out/.err` 打印 = 3） | **已知基线 fail**（P28 既有口径，与 P51 0 关系，P51 未触 P28 任何源码）→ 沿用既有定性，不阻塞、不修改基线测试 |

---

## 11. Probe 锚点（自核实锤，非转述）

| 锚点 | 文件 | 核实结论 |
|------|------|---------|
| 根包 top-level = 3 | `fulltext/` 目录 | `{FulltextSegmenter, FulltextStore, TermDictStore}`（`cluster/`、`compact/`、`segment/`、`layered/`、`sched/`、`autocompact/`、`autoseg/`、`wordnet/` 均为子包） |
| DIDX 段格式 | `segment/DsIndexSegment.java:26,29,35` | `MAGIC={'D','I','D','X'}` / `VERSION=1` / `HEADER_SIZE=4096` / `CRC_SECTIONS=4`；**4 section：docStat / termStat / tf / var** |
| 段列布局 | `segment/SegmentColumnCodec.java:20-52` | `docStat:[docId8][docLen6×4][nTerms6×4]=56B` / `termStat:[uid8][idf8][payloadOff4][payloadLen4]=24B` / `tf:[key8][tf4]=12B` / `var:[flag1][storedLen4][bytes]` → **无 positions / 无邻接列** |
| 段仓库 | `segment/DsIndexSegmentStore.java:34-56,85-105` | 落 `rootDir/segment/seg-<id>.didx` + `manifest`（一行一 segId）；`append` 前校验文件存在 + 指纹一致 |
| 词网 = 内存态 | `FulltextStore.java:64,76,78-82,833-845` | `DIST_MAX=1024`；`distL[]/distR[]`（DsHashMap）+ `distPairMap` + `uidRingShards` + `bucketAccessCounter[1025]`；`dist_buckets` 目录 |
| 词网查询 | `FulltextStore.java:1387-1443` | `distanceBetween`（扫 d=1..1024 L 桶首命中）/ `neighborsWithin`（L/R 双向 1..maxD）/ `topKNeighbors`（d 递增 + `bucketAccessCounter[d]++`），**全部只读内存态** |
| positions 存储 | `FulltextStore.java:362-399` | `termPositionsMap`（DsHashMap，key=`posKey(uid,doc,parent,level)`，value=[total4][count4][s4e4]×N）→ **内存态，未入段** |
| `termStat.payload` 被占用 | `FulltextStore.java:406-418` | P23 HNSW 向量写入 `termStat.payload`（`[dim4][float×dim]`）→ **无法复用该槽位存 positions/邻接** |
| P42 视图能力 | `layered/LayeredInvertedView.java:85-137` | 仅 `docLen`/`nTerms`/`idf`/`termPayload`/`tf`/`hasDoc`/`hasTerm` → **无词网 API** |
| 上游明确「不做」 | `p42-.../spec.md:57`、`p43-.../spec.md:64` | 「**不做** 跨段词网聚合（P24 DIST / `neighborsWithin` 依赖 positions，不在段内）」→ P51 正是该遗留项 |
| 内嵌类合法 | `p42-.../spec.md:33`（§0 方案 C 论证） | ZeroLeak3 只计 top-level 类，内嵌类不计 → `WordNetSegmentView` 内嵌 `static Store` 合法 |
| 既有锁定包 | P42/P47/P48/P49/P50 Gatekeeper Y1 | `segment/`=5 · `cluster/parser/v3/`=5 · `cluster/agg/`=6 · `compact/`=5 · `cluster/groupby/`=5 · `layered/`=3 · `cluster/rowsource/`=2 · `sched/`=3 · `autocompact/`=3 · `autoseg/`=3 全部用**递归** `collectJavaFiles` → 不可新增 `.java` |
