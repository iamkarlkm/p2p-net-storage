# P54 OQ12-㉜① 词网段增量导出 / tombstone / 段淘汰（**0 新类方法追加 / 哨兵墓碑 / 显式淘汰 + 只读审计**）—— spec.md（**✅ CLOSED：Gate A 已签批 / Gate B 已跑 / Gate C 已回填**）

> 用户「继续」推进（承接 P51 永久闭环 `850fc2a`+`1471a1b` 的明确遗留项：P51 §1 MVP 边界 L77 `- **不做** 多线程并发导出/合并 / tombstone / 段淘汰`）→ SP3.1 Gate A（本文件 + tasks.md）→ **用户 VERBATIM 批准** → Implement T1-T6 → Gate B 拆批回归 → Gate C 洁癖 5/5 → Stage 5 commit/push。
>
> 本文件为 **Gate C 已回填终稿**：§0 五项裁决已由用户「Continue」采纳全部默认；9Y 自检清单 / §5.2 TR 实跑 / §5.3 Gate B / §9 签批区 / §10 实装偏差表 均已回填（**数字全部实跑，禁止预填**）。

## Gate C 9Y 自检清单（Gate A 冻结目标；Gate C 回填实锤；目标 = `P54_T5_Gatekeeper9YTest` 9/9 0F0E）

- [x] Y1 **ZeroLeak3=3 + 递归文件数守恒**：`fulltext/` 根包 top-level 恒 = 3；现有 13 处锁定包（`segment/`=5、`cluster/parser/v3/`=5、`cluster/agg/`=6、`compact/`=5、`cluster/groupby/`=5、`layered/`=3、`cluster/rowsource/`=2、`sched/`=3、`autocompact/`=3、`autoseg/`=3、`wordnet/`=3、`columnar/pushdown/`=2、`columnar/aggpushdown/`=1）**全不变**（P54 **0 新类**，`wordnet/`=3 永保）→ `P54_T5.y1_rootPackageThreeAndThirteenLockedPackagesUnchanged`
- [x] Y2 **NG-3 零依赖**：0 新增 netty/grpc/p2p.client 引用 + `p2p-db/pom.xml` 0 新增 dep/plugin → `P54_T5.y2_wordnetZeroForbiddenImportsAndPomBannedZero`
- [x] Y3 **TOPK 铁律**：0 改 K、0 触 `SearchOptions`、`bucketAccessCounter[1025]` 0 污染（**去注释后**判定，允许 Javadoc 说明性提及）→ `P54_T5.y3_topkInvariantNoBucketCounterPollutionAfterStripComments`
- [x] Y4 **Facade 单调不减**：P21 `FulltextStore`/`WeightStore` **本阶段 0 改**；P37 `segment/` + P40 `compact/` + P42 `layered/` + P47/P48/P49/P50 + P52 `columnar/pushdown/` + P53 `columnar/aggpushdown/` 源码 **0 改**；P51 `wordnet/` 3 类**仅追加方法**（既有签名/字段一字不删改）→ `P54_T5.y4_facadeMonotonicP21ZeroChangeAndP51AppendOnly`
- [x] Y5 **路由公式**：0 改 `ShardRouter` / `DsIdLayout` → `P54_T5.y5_routerFormulasUnchanged`
- [x] Y6 **P21~P54 编译兼容**：既有类型 0 改 + 全类存在（含 `wordnet/` 3 类 `Class.forName` + P54 新方法 `getMethod` 可达）→ `P54_T5.y6_allClassesPresentAndCompileCompatible`
- [x] Y7 **HybridHit ctor=2**：0 改 → `P54_T5.y7_hybridHitCtorTwoUnchanged`
- [x] Y8 **ShardTransport 5 方法**：0 改 → `P54_T5.y8_shardTransportFiveMethodsUnchanged`
- [x] Y9 **0 TODO/FIXME/HACK**（新增/改源码）→ `P54_T5.y9_wordnetNoTodoFixmeHack`

---

## 0. 裁决（Gate A 已签批：用户「Continue」采纳 §0 全部默认）

> **Q0 Scope 确认（本阶段候选）**
>
> 本 spec 按用户方向指派「1,2,3顺序实现」+「继续」推进，默认锚定 **OQ12-㉜ 候选 ① = 词网段增量导出 / tombstone / 段淘汰**（承接 P51 `850fc2a`+`1471a1b` §1 MVP 边界 L77 明确遗留项）。若用户实为选择 ②（Other），请于签批时重定向，本文件作废重起。**请确认：`Q0 = ①`。**

> **Q1 新类落包（决定是否破 `wordnet/`=3 现有锁）**
>
> Probe 实锤（见 §11）：P51 `wordnet/` 已被既有 13 重递归计数锁死（`wordnet/`=3）；在其中新增任何 `.java` 会顶高递归计数、直接挂掉 `P51_T5.y1` 基线 TR。
>
> | 选项 | 方案 | 新增类 | 触碰红线 | 推荐 |
> |------|------|--------|---------|------|
> | **A** | **0 新类**：全部作为**方法追加**到 P51 既有 3 类 —— `WordNetExporter` 加 `exportDelta`/`tombstone`；`WordNetSegmentView`（+ 内嵌 `Store`）加墓碑识别 / `evict` / `auditSuperseded`。**递归守恒 13 重全不变**（`wordnet/`=3 永保），既有 Gatekeeper Y1 全绿 | **0** | **无**（纯方法追加） | ⭐**推荐** |
> | B | 新增新兄弟包 `wordnet/evict/`（=1），落新类 | 1..3 | **破计数**：须新增第 14 重锁 + 同步修订 P51/P52/P53 既有 Gatekeeper Y1 断言 → 改动面扩散 | 备选（若 P54 想更「物理隔离」） |
>
> **请裁决**：`Q1 = A`（0 新类，方法追加）或 `Q1 = B`（新增新兄弟包）。

> **Q2 增量导出口径（决定是否需调用方维护 watermark）**
>
> P51 `WordNetExporter.export` 为**全量快照**：无条件枚举 `ft.wordNetAllContextKeys()` 全上下文。P54 增量导出须「只导出变更」。
>
> | 选项 | 方案 | 调用方负担 | 覆盖删除 | 推荐 |
> |------|------|-----------|---------|------|
> | **A** | **纯显式变更上下文**：`exportDelta(ft, rootDir, segId, Collection<long[]> changedCtx)` —— 只导出**显式给定**的变更上下文三元组 `(docId,parentId,level)`（复用 P21 既有 3 只读 accessor 同款枚举）；Δ 变更集由调用方维护（对齐 P51 Q3「纯显式」，**导出器仍 0 字段零状态**） | 调用方维护 Δ | 需另调 `tombstone` | ⭐**推荐** |
> | B | **base 段键集差分**：`exportDelta(ft, rootDir, segId, long[] baseSegIdsOldToNew)` —— 与 base 段做键集差分（ft 有/无），自动为「base 有但 ft 无」的键写墓碑 | 0（自动） | 自动 | 备选（更「聪明」但需全量扫 base 键 + 值变更不可辨） |
>
> **请裁决**：`Q2 = A`（纯显式变更上下文）或 `Q2 = B`（base 段键集差分）。

> **Q3 tombstone 语义 / 编码（决定是否改词网段格式）**
>
> 词网段 payload 当前为 `[count 4B][...]×count`；读取端遍历 `for (i=0; i<count; i++)`，**`count < 0` 天然零迭代**（返回空）。
>
> | 选项 | 方案 | 段格式 | 读取端改动 | 推荐 |
> |------|------|--------|-----------|------|
> | **A** | **per-key 哨兵墓碑**：墓碑段在 ctx/pos section 写入**与原键相同**的键 → payload = `[count 4B = -1]`；读取端 `firstAdjPayload`/`firstPosPayload` 首命中哨兵 → 返回空并**短路**（既有「新→旧首命中」天然生效，**view 0 语义改**；旧读者亦天然兼容 `count<0 → 空`）。**词网段 `VERSION=1` 不升**（哨兵为 payload 约定） | 0 变更 | **0 语义改**（仅**显式**识别用于审计/短路） | ⭐**推荐** |
> | B | **context 级墓碑键**：`mix4(docId,parentId,level,-1L)` 单键墓碑整个上下文 | 0 变更 | 需 view 双键查询（每次查询多 1 次二分） | 备选 |
> | C | 升 `wordnet VERSION=2` + 显式 tombstone section | **VERSION 变更** | 需双版本兼容分支 | 备选（语义最显式，代价最大） |
>
> **请裁决**：`Q3 = A`（per-key 哨兵，不升 VERSION）或 `Q3 = B`（context 级墓碑键）或 `Q3 = C`（升 VERSION=2）。
>
> **为何推荐 A（关键）**：per-key 哨兵使「键覆盖」与「墓碑」**共享同一键空间** → §Q4 的「段淘汰审计」只须判「旧段每个键是否出现在任一更新段中（数据或墓碑）」即可，**无需反解哈希 → 上下文**（词网键 `mix4` 单向不可逆，context 级墓碑会破坏该可判定性）。

> **Q4 段淘汰触发与安全（决定是否后台 / 是否自动判定）**
>
> P51 `Store` manifest 为**纯追加**（`append` 拒重复，`list` 只读，**0 删除机制**）。
>
> - 默认：**纯显式淘汰 + 只读审计**（对齐 P51 Q3「纯显式」）——
>   - `WordNetSegmentView.auditSuperseded(rootDir, segIdsOldToNew) : long[]` **只读**返回「被更新段完全覆盖（数据或墓碑）」的 segId 列表（供调用方决策，**不自动删**）；
>   - `WordNetSegmentView.evict(rootDir, segIdsToEvict)` 由调用方**显式**触发：校验均已注册 → **先重写 manifest（移除）→ 后删段文件**（崩溃安全对偶 P51 R9）；**不做** 后台线程 / 定时器 / 自动淘汰（R10）。
> - **请确认：`Q4 = 纯显式 evict + 只读 auditSuperseded（0 自动 / 0 新线程）`。**

> **Q5 淘汰崩溃安全顺序（决定先删文件还是先改 manifest）**
>
> - 默认：**先 unregister（重写 manifest 移除 segId）→ 后 delete 段文件**。任一崩溃点下：① unregister 前崩 → 段仍在 manifest（可见，未损）；② unregister 后、delete 前崩 → 段文件残留但**不在 manifest**（`list()` 不可见，为孤儿文件，可被后续清理）；③ delete 后崩 → 已淘汰。**任一时刻 manifest 内 segId ⇔ 段文件存在**（强不变式）。**请确认：`Q5 = 先 unregister 后 delete`。**

> 其余 Scope / 红线 R1-R13 / 类清单 / MVP 边界如下，请逐条签批（§9）。

---

## 1. Scope（用户 OQ12-㉜ 候选项 ①）

> 原文（roadmap L461）：`① 词网段增量导出 / tombstone / 段淘汰（承接 P51 纯显式全量快照续作，需段淘汰策略 → 需 Gate A）`

**P54 = 词网段增量导出 / tombstone / 段淘汰（0 新类方法追加）**：在 P51「独立不可变词网段 + mmap 零重建跨段视图」基础上，为词网段补上**三项生命周期能力**——① **增量导出**（只导出调用方显式给定的变更上下文，替代全量快照）；② **tombstone**（墓碑哨兵，使叠加视图可表达「键删除」，修正 P51「新→旧首命中」**无法删除**旧键的语义缺口）；③ **段淘汰**（显式移除被完全覆盖的旧段 + 只读审计），使词网段从「只增不减」演进为「可增量、可删除、可回收」。

1. **`WordNetExporter.exportDelta(ft, rootDir, segId, changedCtx)`**：只枚举**显式给定的变更上下文三元组** `(docId,parentId,level)`（复用 P21 既有 3 只读 accessor 同款口径），打包为**增量段**并登记 manifest；返回 `WordNetSnapshot`（**增量计数**）。**导出器保持 0 字段零状态**。
2. **`WordNetExporter.tombstone(rootDir, segId, adjKeys, posKeys)`**：写出**墓碑段**——对给定 adj/pos 键写哨兵 payload `[count4=-1]`；叠加视图中「新→旧首命中」命中哨兵 → 该键**视为删除**（`neighborsWithin`/`positions` = 空，`distanceBetween` = -1）。
3. **`WordNetSegmentView.auditSuperseded(rootDir, segIdsOldToNew)`**：**只读**审计——返回「本段每个键均出现在某个更新段（数据或墓碑）」的 segId 列表（**完全被覆盖 → 安全可淘汰**）。
4. **`WordNetSegmentView.evict(rootDir, segIdsToEvict)`**：显式淘汰——**先重写 manifest 移除 → 后删段文件**（R9 对偶），淘汰后剩余段仍可 `open` / 查询。

**「删除 / 回收」如何被承载（关键）**：墓碑段与数据段**共享同一词网键空间**（`mix4` 口径），因此「删除某键」= 更新段写下同键的**哨兵 payload**；叠加视图的**「新→旧首命中 + 短路」**天然使新墓碑遮蔽旧数据（R8）。「淘汰旧段」= **先 unregister 后 delete** 的强不变式下移除（R9'）。**全程 0 新类、0 改词网段格式（`VERSION=1` 保持）、0 改 P21 `FulltextStore`**。

**MVP 边界（0 越界）**：
- 仅做「词网段的**增量导出** + **tombstone 墓碑** + **显式段淘汰 + 只读审计**（0 新类 / 方法追加）」
- **不做** 词网段格式变更（词网段 `MAGIC='DNET'`/`VERSION=1`/4 section **字面不变**；哨兵为 payload 约定，0 新列/section/分支）
- **不做** DIDX（`segment/`）段格式变更（R5，与本阶段无关）
- **不做** 后台自动导出 / 自动淘汰 / 定时器（**0 新增线程**，R10）；`auditSuperseded` 仅**建议**，淘汰恒由调用方**显式**触发
- **不做** 写路径挂钩：`ingestDoc` / `putDoc` / `clearDoc` / `updateWeightForLevel` 行为字节级不变（P22 `WeightStore` 0 改）
- **不做** `FulltextStore.search` 读路径切换；`WordNetSegmentView` 仍为**并行只读视图**
- **不做** 多线程并发导出 / 合并（沿用 P51 串行口径）
- P21 `FulltextStore` / `WeightStore` / `TermDictStore` / `FulltextSegmenter` **本阶段 0 改**；P37 `segment/`、P40 `compact/`、P42 `layered/`、P47 `sched/`、P48 `autocompact/`、P49 `autoseg/`、P50/P52 `columnar/pushdown/`、P53 `columnar/aggpushdown/` 源码 **0 改**；P51 `wordnet/` 3 类**仅追加方法**（既有签名/字段 0 改）
- **0 新增 Maven dep**（复用既有 `lz4-java`）
- **0 新类**：`wordnet/` 递归计数恒 = 3（ZeroLeak3=3 永保）

---

## 2. 红线（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3 + 递归文件数守恒** | `fulltext/` 根包 top-level 类数严格 = 3（`{TermDictStore, FulltextStore, FulltextSegmenter}`）；现有 13 处锁定包计数**全不变**；P54 **0 新类**（`wordnet/`=3 永保） |
| R2 | **NG-3 零依赖** | `p2p-db/pom.xml` 0 新增 dep/plugin；`wordnet/` 源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*`；仅 JDK + first-party import |
| R3 | **TOPK 铁律** | 增量导出 / 墓碑 / 淘汰均为纯数据搬运；0 改 K / 0 改 `SearchOptions`；`bucketAccessCounter[1025]` 0 污染 |
| R4 | **Facade 单调不减（P21 本阶段 0 改）** | P21 `FulltextStore` / P22 `WeightStore` / `TermDictStore` / `FulltextSegmenter` **本阶段字节级 0 改**；P37/P40/P42/P47/P48/P49/P50/P52/P53 目录源码**字节级 0 改**；P51 `wordnet/` 3 类**仅追加方法**（既有 public 方法/字段/内嵌类型一字不删改） |
| R5 | **词网段格式 0 变更** | 词网段 `MAGIC={'D','N','E','T'}` + `VERSION=1` + `HEADER_SIZE=4096` + `CRC_SECTIONS=4` + 4 section 字面不动；墓碑为 payload 约定（`count=-1`），**0 新列 / 0 新 section / 0 新 version 分支**；DIDX（`segment/`）字面不变 |
| R6 | **USER-HC-0 与 TermDict 零触碰** | `TermDictStore.toUid(String, boolean)` **永远无 level 参数**；`TermDictStore` / `FulltextSegmenter` 源码 0 改；词网键直接复用既有 `mix4` 口径，**不做 uid 重编码** |
| R7 | **导出确定性** | 增量/墓碑段三列升序由 `packIndex`（`TreeMap` 有序）保证（ctx 键↑ / pos 键↑）；同源同变更集导出 → `segmentFingerprint` 跨进程 / 跨 run 恒定 |
| R8 | **覆盖/删除语义确定性** | 单段内同键 = 写入口「后者覆盖」（继承 P37/P22 口径）；多段视图 = 「新→旧」首个命中；**命中哨兵 = 键删除**（`neighborsWithin`/`positions`=空、`distanceBetween`=-1）；**段序由调用方显式给定**（旧→新） |
| R9 | **崩溃安全（导出）** | 导出/墓碑写段顺序必须 `Files.move`（`.tmp` → finish）**先于** manifest 注册；任一崩溃点下，未注册的半成品段**不在 manifest**，`list()` 不可见 |
| R9' | **崩溃安全（淘汰，对偶）** | `evict` 顺序必须**先 unregister（重写 manifest 移除）→ 后 delete 段文件**；强不变式：**任一时刻 manifest 内 segId ⇔ 段文件存在** |
| R10 | **零重建 & 0 新线程** | `WordNetSegmentView.open` 仅 mmap + 头校验 + 4×CRC32；**禁止**把段内容全量读进堆建哈希表（逆指标：open 代价与段体积无关，冷启动 P95 ≤ 200ms）；`auditSuperseded` 为**只读**顺序扫键列（O(total keys)，允许）；**0 新增 `ExecutorService`/`Timer`/后台线程** |
| R11 | **写路径透明** | 本阶段 0 挂钩写路径；`ingestDoc`/`putDoc`/`clearDoc` 行为字节级不变（对齐 P48/P49 R11/R13 口径） |
| R12 | **等价性铁律** | **无墓碑时**：`exportDelta`(变更集) 叠加 base 段视图 ≡ `export`(全量) 视图，`neighborsWithin`/`distanceBetween`/`topKNeighbors`/`positions` 逐条等价；**有墓碑时**：被墓碑键视同不存在 |
| R13 | **0 TODO/FIXME/HACK** | 新增/改源码 0 处遗留标记 |

---

## 3. 架构（**0 新类**，P51 3 类方法追加，P21~P53 源码 0 改）

### 3.1 变更清单（全部为方法追加，0 新类 / 0 新文件）

| 类（既有） | 追加方法 | 职责 | 关键约束 |
|-----------|---------|------|---------|
| `WordNetExporter`（`wordnet/`） | `exportDelta(FulltextStore, String, long, Collection<long[]>) : WordNetSnapshot` | 只导出显式给定的变更上下文三元组 | 4 IAE；0 字段零状态；R9 崩溃安全 |
| `WordNetExporter`（`wordnet/`） | `tombstone(String, long, long[], long[]) : WordNetSnapshot` | 写墓碑段（哨兵 payload） | 4 IAE；`VERSION=1` 不升；`Files.move` 先于注册 |
| `WordNetSegmentView`（`wordnet/`） | `auditSuperseded(String, long[]) : long[]`（static） | 只读：返回被更新段完全覆盖的 segId | 4 IAE；0 自动删 |
| `WordNetSegmentView`（`wordnet/`） | `evict(String, long[]) : void`（static） | 显式淘汰：先 unregister 后 delete | 4 IAE；R9' 强不变式 |
| `WordNetSegmentView` + 内嵌 `Store` | 私有 `ctxKeys()` / `posKeys()` 枚举（内嵌 `Store`） | 键列顺序枚举（供 `auditSuperseded`） | O(N) 顺序读，0 建堆哈希表 |
| `WordNetSegmentView.Store` | `evictSegments(...)` / `supersededKeys(...)`（package-private static） | manifest 重写移除 + 段文件删除 + 键覆盖判定 | 复用既有 `list` / `segFile` / `dirOf` |

> **0 新类论证**：方法追加到 P51 既有 3 类的 public 面，`wordnet/` 递归计数恒 = 3 → 既有 13 重守恒锁与全部既有 Gatekeeper Y1 断言**逐字不动**（对齐 P42 §3.3「不可新增 `.java`」原则）。

### 3.2 数据流（增量导出 → 墓碑 → 淘汰）

```
写入期（P21/P22 既有，0 改）
  FulltextStore.ingestDoc(docId, text)  → 内存词网（positions + distL/distR 邻接）

增量导出期（P54 新增，显式触发，Q2=A）
  new WordNetExporter().exportDelta(ft, rootDir, segId=11, changedCtx=[(d,p,l)...]):
    1) 逐 changedCtx 三元组 → ft.wordNetNeighborsForCtx / selectByParentLevel / wordNetPositionsForCtx
    2) 列式打包 + LZ4 动态区（同 P51 export）
    3) finish()：.tmp → 原子 Files.move（崩溃安全点：尚未注册）
    4) 注册 manifest（Store.append）
    return new WordNetSnapshot(segId=11, ctxCount, adjCount, posCount, fingerprint)

墓碑期（P54 新增，显式触发，Q3=A）
  new WordNetExporter().tombstone(rootDir, segId=12, adjKeys=[K1,K2], posKeys=[P1]):
    1) ctx section：[(K1, off,len=4)] [(K2, off,len=4)]  payload=[count4 = -1]
    2) pos section：[(P1, off,len=4)]                      payload=[count4 = -1]
    3) finish() → 注册 manifest
    叠加视图查询命中 [count=-1] → 视为删除（短路）

淘汰期（P54 新增，显式触发，Q4/Q5）
  WordNetSegmentView.auditSuperseded(rootDir, [10,11,12]) → 只读返回 [10]（全键被 11/12 覆盖）
  WordNetSegmentView.evict(rootDir, [10]):
    1) 校验 10 已注册 → 重写 manifest（移除 10）   ← unregister 先（强不变式）
    2) 删 seg-10.dnet                              ← delete 后
```

### 3.3 包结构（**不变**，`wordnet/` 仍 = 3）

```
com/q3lives/ds/database/fulltext/
├── FulltextSegmenter.java          (P21 既有，0 改)
├── FulltextStore.java              (P21 既有，**本阶段 0 改**)
├── TermDictStore.java              (P21 既有，0 改)
├── segment/ ... autoseg/           (既有锁定包，0 改)
├── cluster/ ...                    (既有锁定包，0 改)
├── columnar/pushdown/ (=2)         (P52 既有，0 改；锁定 2)
├── columnar/aggpushdown/ (=1)      (P53 既有，0 改；锁定 1)
└── wordnet/                        (P51 既有子包；递归计数锁定 3 —— **P54 不新增 .java**)
    ├── WordNetSnapshot.java               (P51 既有，0 改)
    ├── WordNetExporter.java               (P51 既有；**追加 exportDelta / tombstone**)
    └── WordNetSegmentView.java            (P51 既有；**追加 auditSuperseded / evict + 内嵌 Store 键枚举/淘汰**)
```

---

## 4. 接口契约

### 4.0 P21 `FulltextStore`（**本阶段 0 改**）

> 复用 P51 已追加的 3 只读 accessor（`wordNetAllContextKeys` / `wordNetNeighborsForCtx` / `wordNetPositionsForCtx`）+ 既有 public `selectByParentLevel`；**P54 不新增任何 P21 方法**。

### 4.1 WordNetExporter（追加 2 方法）

```java
public final class WordNetExporter {
    public WordNetExporter();
    public WordNetSnapshot export(FulltextStore ft, String rootDir, long segId) throws IOException;  // P51 既有，0 改

    /** P54 Q2=A：只导出显式给定的变更上下文三元组 (docId,parentId,level)。finish() 先 → 注册后（R9）。 */
    public WordNetSnapshot exportDelta(FulltextStore ft, String rootDir, long segId,
                                       Collection<long[]> changedCtx) throws IOException;
    // 4 IAE 守卫: ft null / rootDir null·空 / segId < 0 / segId 已注册（防重复导出覆盖）

    /** P54 Q3=A：写墓碑段——ctx/pos section 对应键 → 哨兵 payload [count4=-1]。finish() 先 → 注册后（R9）。 */
    public WordNetSnapshot tombstone(String rootDir, long segId,
                                     long[] adjKeys, long[] posKeys) throws IOException;
    // 4 IAE 守卫: rootDir null·空 / segId < 0 / segId 已注册 / (adjKeys 与 posKeys 皆空)
}
```

### 4.2 WordNetSegmentView（追加 2 静态方法；查询面 0 改）

```java
public final class WordNetSegmentView implements AutoCloseable {
    public static WordNetSegmentView open(String rootDir, long[] segIdsOldToNew) throws IOException;  // P51 既有，0 改
    public List<Neighbor> neighborsWithin(...);      // P51 既有，0 改（哨兵 [count=-1] 天然返回空）
    public int             distanceBetween(...);     // P51 既有，0 改（哨兵 → -1）
    public List<Neighbor>  topKNeighbors(...);       // P51 既有，0 改（哨兵 → 空）
    public List<PositionSpan> positions(...);        // P51 既有，0 改（哨兵 → 空）
    public int    segmentCount();                    // P51 既有
    public String fingerprint();                     // P51 既有
    public void   close();                           // P51 既有

    /** P54 Q4：只读——返回被更新段完全覆盖（数据或墓碑）的 segId 列表（旧→新序，供调用方决策，0 自动删）。 */
    public static long[] auditSuperseded(String rootDir, long[] segIdsOldToNew) throws IOException;
    // 4 IAE 守卫: rootDir null·空 / segIds null / segIds 空 / 段未注册

    /** P54 Q4/Q5：显式淘汰——先 unregister（重写 manifest 移除）→ 后 delete 段文件（R9' 强不变式）。 */
    public static void evict(String rootDir, long[] segIdsToEvict) throws IOException;
    // 4 IAE 守卫: rootDir null·空 / segIds null / segIds 空 / 段未注册
}
```

> **哨兵常量**（package-private，`WordNetSegmentView.Store` 或 `WordNetExporter`）：`static final int TOMBSTONE_COUNT = -1;` payload = `[count4 = -1]`（4B）。

---

## 5. 测试（Gate B 拆 6 批）

### 5.1 Surefire 拆批（继承 P51 模板，P54 入批 4c）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P25_*,ds.P26_*,ds.P27_*,ds.P28_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P29_*,ds.P30_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*,ds.P42_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P43_*,ds.P44_*,ds.P45_*,ds.P46_*,ds.P47_*,ds.P48_*,ds.P49_*,ds.P50_*,ds.P51_*,ds.P52_*,ds.P53_*,ds.P54_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

> **每批前必须先清理 `target\tmp` 与仓库根 `.tmp` + `surefire-reports`**（P39/P41/P48/P49/P50 教训：累积磁盘 / 并发 run 串扰致 NFR 计时假失败与 TR 数字异常）；改源码或测试后必须先 `test-compile`；长跑用 `Start-Process -RedirectStandardOutput` 落盘再 `Grep`（规避 PowerShell 重定向早退）。

### 5.2 TR 计划（实跑数字 Gate B 回填，**禁止预填**）

| T | 范围 | 计划 TR | 实装内容 | 目标测试类 |
|---|------|--------|---------|--------|
| **T1** | 入口骨架 + IAE + 哨兵 | 5 | `exportDelta` 4 IAE + `tombstone` 4 IAE + `auditSuperseded`/`evict` IAE + 哨兵 payload 往返（写墓碑 → 读 payload == -1） | `P54_T1_SkeletonSentinelIaeTest` |
| **T2** | 增量导出等价性 | 6 | ① `exportDelta` `ctxCount` == 变更上下文键数 ② `adjCount` == delta L/R 条目数 ③ `posCount` == delta positions 区间数 ④ delta+base 叠加 L/R 邻接 ≡ 全量导出逐条 ⑤ delta+base 叠加 positions ≡ 全量导出逐条 ⑥ 空 `changedCtx` → 0/0/0 合法段可读 | `P54_T2_DeltaExportEquivalenceTest` |
| **T3** | tombstone 语义 | 5 | ① 墓碑段 → `neighborsWithin` 空（首命中短路）② 墓碑段 → `distanceBetween` == -1 ③ 墓碑段 → `positions` 空 ④ 更新段墓碑 遮蔽 旧段数据（段序显式）⑤ 旧读者兼容（`count<0` 不抛异常）+ `fingerprint` 随墓碑段变化 | `P54_T3_TombstoneSemanticsTest` |
| **T4** | 段淘汰 + 审计 | 5 | ① `auditSuperseded`：全键覆盖旧段判可淘汰 / 部分覆盖不可 ② 墓碑覆盖计入（墓碑也算覆盖）③ `evict`：manifest 移除 + 段文件删除 + 剩余段仍可 open/查询 ④ R9' 强不变式（先 unregister 后 delete，孤儿文件不可见）⑤ `evict` 幂等/边界（淘汰后可重 append 同 segId） | `P54_T4_EvictAuditTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 **且 13 重递归守恒全不变** + Y2 NG-3 + Y3 TOPK + Y4 Facade 单调不减（P21 0 改 + P51 3 类旧签名不消失）+ Y5 路由 0 改 + Y6 P21~P54 全类存在 + P54 新方法 `getMethod` 可达 + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 方法 + Y9 0 TODO/FIXME/HACK | `P54_T5_Gatekeeper9YTest` |
| **T6** | NFR | 5 | `exportDelta` 1k ctx P95 ≤ 阈值 + `auditSuperseded` 1k 键 P95 ≤ 阈值 + 改动类 Javadoc ≥ 10 行 + CPD 160 字符窗口 0 重复块 + 0 `System.out.println`/`printStackTrace` + IAE 显式守卫 ≥ 阈值 | `P54_T6_NFR_Test` |

**TR 计划总数**：5+6+5+5+9+5 = **35 TR**（实跑 **35/0F/0E**，Gate B 回填）

### 5.3 验收门槛（Gate C 实跑回填，**禁止预填**）

- [x] P54 own **35/35 TR 0F0E**（T1 5 + T2 6 + T3 5 + T4 5 + T5 9 + T6 5）
- [x] 批 1 P22-P24 = **76 TR / 0F / 0E**（BUILD SUCCESS）
- [x] 批 2 P25-P28 = **112 TR / 1F / 0E**（已知基线 `P28_T6.tr64`，沿用既有口径，不阻塞）
- [x] 批 3 P29-P30 = **68 TR / 0F / 0E**（BUILD SUCCESS）
- [x] 批 4a P31-P36 = **193 TR / 0F / 0E**（BUILD SUCCESS）
- [x] 批 4b P37-P42 = **186 TR / 0F / 0E**（BUILD SUCCESS）
- [x] 批 4c P43-P54 = **384 TR / 1F / 0E**（含 P54 own 35 TR；`P45_T6.tr61` 1F → 隔离重跑 5/5 定性为 load 时序 flaky，非 P54 回归）
- [x] **Gate B 合计 = 1019 TR / 0 Error**（2 F 均为已知基线 / flaky，已定性）
- [x] R12 等价性（无墓碑时 delta+base ≡ 全量）→ `P54_T2.tr24`/`tr25`
- [x] R8 删除语义（墓碑 → 空 / -1）→ `P54_T3`
- [x] R9' 淘汰崩溃安全（先 unregister 后 delete）→ `P54_T4.tr44`
- [x] R1 13 重递归守恒全不变 + R5 词网段格式 0 变更（`VERSION=1`）→ `P54_T5.y1` + `P54_T6`

---

## 6. 逆指标（禁止项）

1. **禁止把词网段内容全量读进堆**建哈希表（R10 零重建）→ `open` 仅 mmap + 头/CRC 校验；`auditSuperseded` 仅顺序扫键列。
2. **禁止后台线程 / 定时器 / 自动淘汰**（0 新增 `ExecutorService` / `Timer`）。
3. **禁止改词网段 / DIDX 段格式**（0 新增列 / magic / version 分支；0 触碰 `DsIndexSegment*`；词网段 `VERSION=1` 不升）。
4. **禁止新增 P51 `wordnet/` 之外的类 / 新增 `.java`**（13 重守恒）。
5. **禁止改 P21 `FulltextStore` / P22 `WeightStore` 任何签名或内嵌类型**（本阶段 P21 0 改）。
6. **禁止 uid 重编码 / level 前缀拼接**（USER-HC-0：直接复用既有 `mix4` 口径）。
7. **禁止触碰 `FulltextStore.search` 读路径**（本阶段不接管搜索）。
8. **禁止在 `evict` 中先删文件后改 manifest**（R9' 强不变式）。

---

## 7. 风险与回退

| 风险 | 影响 | 缓解 / 回退 |
|------|------|------------|
| Q3 选 B/C（context 级墓碑 / VERSION=2） | 破坏淘汰审计「键可判定性」/ 需双版本兼容 | 默认推荐 A（per-key 哨兵，键空间共享 → 审计可判定） |
| Q2 选 B（base 段差分） | 需全量扫 base 键 + 值变更不可辨 | 默认推荐 A（纯显式变更上下文） |
| 墓碑键与真实键碰撞 | 误删 / 误留 | 哨兵仅改 payload（键不变），`count=-1` 与原 `count≥0` 语义不重叠；T3 逐条验证 |
| 淘汰误删仍被引用的段 | 数据丢失 | `auditSuperseded` 只读建议 + 淘汰恒由调用方显式触发；R9' 强不变式（manifest ⇔ 文件） |
| 增量导出后叠加视图与全量不一致 | R12 等价性失败 | T2 以「全量导出」真值逐条对照（同一 `FulltextStore`） |
| Gate B 批 2 已知基线 fail | 批 2 非全绿 | 沿用既有口径（`P28_T6.tr64` 与 P54 0 关系），不修改基线测试 |
| Gate B 批 4c 时序 NFR 假失败 | `P45_T6.tr61` 满载超限 0.4% | 隔离重跑 5/5 定性为 load 时序 flaky（P54 改动与纯 ORM fetch 路径正交）；沿用「flaky 单类隔离定性」既有教训 |
| `-pl p2p-db` 找不到 P54 新方法 | 编译失败 | 本次 0 改 p2p-core，若不涉 p2p-core 则无需先 install；若需则先 `mvn -pl p2p-core install -DskipTests -q` |

---

## 8. 交付物清单

- 源码（**0 新类**）：`wordnet/WordNetExporter.java`（+`exportDelta`/`tombstone`）、`wordnet/WordNetSegmentView.java`（+`auditSuperseded`/`evict` + 内嵌 `Store` 键枚举/淘汰）
- **P21 0 改**（本阶段不新增 P21 方法）；P51 `WordNetSnapshot.java` 0 改
- 测试：`ds/P54_T1_*.java` ~ `P54_T6_*.java`（6 文件）
- 文档：本 `spec.md`（Gate C 回填）+ `tasks.md`；`docs/dsdb-design-status-and-roadmap.md`（`OQ12-㉜①` ⏳→✅ + 挂 `OQ12-㉝`）；Project Memory 追加
- Stage 5：逐路径 `git add`（仅 P54 相关）→ `git commit -F <msgfile>` → `git push origin main`

---

## 9. 签批区（Gate A，需用户 VERBATIM 批准）

> 用户以「继续」/「Continue」采纳 §0 全部默认裁决，逐条置 **[x]**。

- [x] **§0 Q0 Scope**：`Q0 = ①`（词网段增量导出 / tombstone / 段淘汰）
- [x] **§0 Q1 落包**：`Q1 = A`（0 新类，P51 3 类方法追加）
- [x] **§0 Q2 增量导出口径**：`Q2 = A`（纯显式变更上下文三元组）
- [x] **§0 Q3 tombstone 语义**：`Q3 = A`（per-key 哨兵 `[count=-1]`，词网段 `VERSION=1` 不升）
- [x] **§0 Q4 段淘汰触发**：`Q4 = 纯显式 evict + 只读 auditSuperseded`（0 自动 / 0 新线程）
- [x] **§0 Q5 淘汰崩溃安全**：`Q5 = 先 unregister 后 delete`
- [x] **§1 Scope**（增量导出 + 墓碑 + 显式淘汰 + 只读审计；MVP 边界 0 越界）
- [x] **§2 红线 R1-R13**（ZeroLeak3 + 13 重递归守恒 + R4 P21 0 改 + R5 词网段格式 0 变更 + R9' 淘汰崩溃安全 + R10 零重建）
- [x] **§3 架构**（0 新类，`wordnet/`=3 永保）
- [x] **§4 接口契约**（4.1 Exporter 追加 2 方法 / 4.2 View 追加 2 静态方法）
- [x] **§5 测试**（35 TR = 5+6+5+5+9+5；Gate B 拆 6 批）
- [x] **§6 逆指标** / **§7 风险与回退** / **§8 交付物清单**

**用户批准原文（VERBATIM）**：`Continue`（采纳 §0 全部默认裁决 Q0=① / Q1=A / Q2=A / Q3=A / Q4=纯显式 / Q5=先 unregister 后 delete）

---

## 10. 实装偏差表（Gate C 回填）

| 编号 | 计划 | 实装 | 原因 |
|------|------|------|------|
| D1 | T2/T3/T4 测试 `toKeySet(List<Neighbor>)` | `toKeySet(Collection<Neighbor>)` + `import java.util.Collection;` | `FulltextStore.neighborsWithin(...)` 实返 `Set<Neighbor>`（非 `List`），test-compile 失败后修正，0 改主源码 |
| D2 | 预测「批 4c 仅 P54 own 可能 flaky」 | 实际 `P45_T6.tr61_pureOrmFetchAvgWithin5PctOfBaseline` 满载下超限 0.4% 1F | 纯 ORM fetch 时序 NFR；隔离重跑 **5/5 0F0E 通过** → 判定 load 时序 flaky，非 P54 回归；P54 改动（`WordNetExporter`/`WordNetSegmentView` 新增方法）与纯 ORM fetch 路径正交 |
| D3 | Gate B 首跑批次 2-6 | 遇 C 盘 0GB → `磁盘空间不足` + `ConnectException` 连锁假失败 | 清 `$env:TEMP`（`junit*`/`dsdb_*`/`tomcat*`）后重跑（`gateb_p54b.*.log`）；0 代码改动 |

---

## 11. Probe 锚点（自核实锤，非转述）

| 锚点 | 文件 | 核实结论 |
|------|------|---------|
| P51 全量导出（无增量） | `wordnet/WordNetExporter.java:109-207` | `export` 无条件遍历 `ft.wordNetAllContextKeys()` 全上下文 → **无增量口径** |
| P51 墓碑缺口 | `wordnet/WordNetSegmentView.java:565-590`（`adjPayload`/`posPayload`） | 首命中返回 payload；**无「删除」表达**（旧键永远可用，新段无法遮蔽删除） |
| P51 manifest 纯追加 | `wordnet/WordNetSegmentView.java:470-540`（`list`/`append`） | `append` 拒重复 segId（`duplicate append`）+ 重写 manifest；**0 删除 / 0 淘汰机制** |
| payload 天然兼容 `count<0` | `WordNetSegmentView.java:128-137,179-193,199-213` | 读取端 `for (i=0; i<count; i++)` → `count=-1` 零迭代 → 返回空；**旧读者兼容哨兵** |
| 词网段格式 v1 | `WordNetSegmentView.java:380-439`（`open`/`buildHeader`） | `MAGIC={'D','N','E','T'}` / `VERSION=1` / `HEADER_SIZE=4096` / `CRC_SECTIONS=4` / `HEADER_FIXED=112`；4 section（ctx/pos/adjVar/posVar） |
| 键口径 | `wordnet/WordNetExporter.java:82-95`（`ctxKey`/`posKey`） | `ctxKey=mix4(docId,parentId,level,srcUid)`；`posKey=mix4(uid,docId,parentId,mix2(level,0xDEADBEEF))`；**单向哈希不可逆** → per-key 墓碑保审计可判定 |
| P21 只读 accessor（P51 追加） | `FulltextStore.java:3158-3232` | `wordNetAllContextKeys` / `wordNetNeighborsForCtx`（经 `selectByParentLevel`）/ `wordNetPositionsForCtx`；**P54 复用，P21 0 改** |
| P51 明确遗留 | `p51-oq1-cross-segment-wordnet/spec.md:77` | `- **不做** 多线程并发导出/合并 / tombstone / 段淘汰` → **P54 正题** |
| 13 重递归守恒 | P42/P47/P48/P49/P50/P51/P52/P53 Gatekeeper Y1 | `segment/`=5 · `cluster/parser/v3/`=5 · `cluster/agg/`=6 · `compact/`=5 · `cluster/groupby/`=5 · `layered/`=3 · `cluster/rowsource/`=2 · `sched/`=3 · `autocompact/`=3 · `autoseg/`=3 · `wordnet/`=3 · `columnar/pushdown/`=2 · `columnar/aggpushdown/`=1 全部**递归** `collectJavaFiles` → **不可新增 `.java`**（P54 → 0 新类） |
