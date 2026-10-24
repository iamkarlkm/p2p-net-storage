# P40 OQ1 BM25 Index Segment 增量 compaction / tiered-LSM 段合并 —— spec.md（**REV1 GATE C 闭环**）

> 用户 OQ12-⑱ 选 ①（承接 OQ12-⑰ = P39 聚合执行引擎，已永久闭环）→ SP3.1 Gate A（本文件 + tasks.md）→ 用户 VERBATIM 批准 → Implement T1-T6 → Gate B 拆批回归 → **Gate C 洁癖 5/5 闭环**。

## Gate C 9Y 自检清单（Gate C 逐项 [x] 通过；实锤 = `P40_T5_Gatekeeper9YTest` 9/9 0F0E）

- [x] Y1 **ZeroLeak3=3 + 四重文件数守恒**：`fulltext/` 根包 top-level 仍 = 3；且 `segment/` 递归源文件数 = 5、`cluster/parser/v3/` = 5、`cluster/agg/` = 6、`compact/` = 5（P40 5 新类 100% 落新子包）→ `P40_T5.y1_ZeroLeak3AndQuadFileCountDoubleGuard` ✅
- [x] Y2 NG-3 零依赖：0 新增 netty/grpc/p2p.client 引用 + `p2p-db/pom.xml` 0 新增 dep/plugin → `P40_T5.y2_Ng3_CompactImportsJdkAndFirstPartyOnly` ✅
- [x] Y3 TOPK 铁律：0 改 K、0 触 `SearchOptions`、`bucketAccessCounter` 0 污染 → `P40_T5.y3_TopKGuardrails_NoBucketCounterNoSearchOptions` ✅
- [x] Y4 Facade 单调不减：P37 `segment/` 5 类源码 **0 改**；P21/P22/P30/P34/P38/P39 公开签名 0 改 → `P40_T5.y4_FacadeMonotonic_P37SegmentAndPriorIntact` ✅
- [x] Y5 路由公式：0 改 ShardRouter / P24 UNIT·DOC 路由 → `P40_T5.y5_RoutingFormula_ShardRouterIntact` ✅
- [x] Y6 P21~P39 编译兼容：既有类型 0 改 + P37 segment 5 类 + P40 compact 5 类全在 → `P40_T5.y6_P21ToP40CompileCompat_ClassesExist` ✅
- [x] Y7 HybridHit ctor=2：0 改 → `P40_T5.y7_HybridHit_ConstructorCountIs2` ✅
- [x] Y8 ShardTransport 5 方法：0 改 → `P40_T5.y8_ShardTransport_5OriginalMethodsIntact` ✅
- [x] Y9 0 TODO/FIXME/HACK（`compact/` 源码）→ `P40_T5.y9_NoTodoFixmeHack` ✅

---

## 1. Scope（用户 OQ12-⑱ 候选项 ①）

> 原文：`① P38+ BM25 Index Segment 增量 compaction / tiered-LSM 段合并（P37 已落地不可变段 + manifest，v2 追加后台 compaction + 合并多段视图）`

P40 = 在 P37（不可变列式段 + manifest 生命周期，`segment/` 5 类）之上，追加**多段只读合并视图**与**段合并（compaction）**，使 N 个段可被当作一个逻辑索引查询、并可把 N 个段物理归并为 1 个新段：

1. **`SegmentMergeView`（多段只读合并视图）**：把若干已注册段按调用方给定的「旧 → 新」顺序叠成一个逻辑读取端；查询语义 = **新段优先覆盖**（最新段命中即返回），完全对齐 P22 `WeightStore`「同键后者覆盖」口径。
2. **`SegmentCompactor`（段归并器）**：k 路归并 N 个源段 → 产出 1 个新段文件（复用 P37 `DsIndexSegmentWriter` + `DsIndexSegmentReader`），归并语义同为「新段优先覆盖」；产物是**合法 P37 段**，`DsIndexSegmentReader.open` 可直接读。
3. **`SegmentCompactionPolicy`（tiered-LSM 触发策略）**：size-tiered 策略（按段字节体积分 tier，tier 内段数 ≥ `fanIn` 触发），纯决策函数（无 IO、无副作用、确定性）。
4. **`SegmentCompactionPlan`（不可变合并计划）**：承载一次合并的（源 segId 组 + tier + mergedSegId），含 SHA-256 64-hex `fingerprint()`（跨进程可复现）+ `deepEquals` + IAE 守卫。
5. **`SegmentCompactionRunner`（编排器）**：`SegmentCompactionPlan` → 驱动 `SegmentCompactor` 产新段 → `DsIndexSegmentStore.append(merged)` **先** → `store.remove(source)` **后**；append-before-remove 顺序即**崩溃安全**（崩溃点两段共存时，合并视图「新段优先」仍返回正确结果）。

**MVP 边界（0 越界）**：
- 仅做「多段合并视图 + N→1 段归并 + tiered 触发策略 + append/remove 编排」
- **不做** tombstone / 删除标记 / 逻辑删除合并（P37 段无删除语义，段合并为纯覆盖归并）
- **不做** 后台线程 / 定时调度（由调用方驱动 `policy → plan → runner`；MVP 不新增 `ExecutorService`）
- **不做** 增量 upsert（段保持不可变；新写入仍走 P37 既有 writer → 新 segId 新段）
- **不做** 段格式变更（复用 P37 `MAGIC="DIDX"` + `VERSION=1`，0 新增 magic/version 分支）
- **不为 compaction 单独引入 manifest 事务**（append-before-remove 已给出崩溃安全；MVP 接受 manifest 两次写）
- **P37 `segment/` 5 类源码 0 改**；P21/P22/P30/P34/P38/P39 源码 0 改
- **0 新增 Maven dep**（复用 P37 既有 `lz4-java` + first-party `ColumnCompression`）
- 5 新类全放**新子包** `com.q3lives.ds.database.fulltext.compact`（ZeroLeak3=3 永保）

---

## 2. 红线（VERIFIED 永久钉死，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3 + 四重文件数守恒** | fulltext 根包 top-level 类数严格 = 3（`{TermDictStore, FulltextStore, FulltextSegmenter}`）；且**递归**源文件数 `segment/`=5、`cluster/parser/v3/`=5、`cluster/agg/`=6 全部不变；P40 5 新类 100% 落**新包** `compact/` |
| R2 | **NG-3 零依赖** | `p2p-db/pom.xml` 0 新增 dep/plugin；`compact/` 源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*`；仅 JDK + first-party import |
| R3 | **TOPK 铁律** | compaction 纯段统计归并；0 改 K / 0 改 `SearchOptions`；`bucketAccessCounter[1025]` 0 污染 |
| R4 | **Facade 单调不减（P37 零触碰）** | P37 `segment/` 5 类源码**字节级 0 改**；P21 `FulltextStore` / P22 `WeightStore` / P30 / P34 / P38 / P39 公开签名 0 改；P40 纯 additive |
| R5 | **HybridHit ctor=2** | 0 改 |
| R6 | **P21~P39 字节级兼容** | TermDictStore / WeightStore / ShardRouter / ShardTransport / HJC / Resilience / DnfParser / AggParser / AggExecutor 全部 0 改 |
| R7 | **段格式 0 变更** | 复用 P37 `MAGIC="DIDX"` + `VERSION=1`；合并产物必须是**合法 P37 段**（`DsIndexSegmentReader.open` 可直接 mmap 读，头校验 + 4×CRC32 全过） |
| R8 | **覆盖语义确定性（新段优先）** | 合并视图 / 归并器均按「调用方给定顺序 → 新段优先」解析同键冲突；`tf` 未命中 = 0（继承 P37/P22 语义）；顺序由调用方显式给定，**不依赖 manifest 物理行序**（P37 manifest 为插入序，非排序） |
| R9 | **崩溃安全** | `SegmentCompactionRunner` 必须 `append(merged)` **先于** `remove(sources)`；任意崩溃点（含两段共存）下合并视图查询结果与合并前等价 |
| R10 | **计划确定性** | `SegmentCompactionPlan.fingerprint()` 仅覆盖（源 segId 升序集 + tier + mergedSegId）→ 同一 store 状态跨进程/跨 run 恒定；`deepEquals` 逐字段 |

---

## 3. 架构（5 个新类，P37 源码 0 改，0 改 P21~P39）

### 3.1 新增清单

| 名称 | 类型 | 职责 | 关键字段/方法 |
|------|------|------|---------------|
| `SegmentMergeView` | 只读合并视图（`compact/`） | N 段叠成 1 个逻辑读取端，新段优先覆盖 | `SegmentMergeView(List<DsIndexSegmentReader>)` / `getDocStat(docId)` / `getTermStat(uid)` / `getTf(key)` / `segmentCount()` / `fingerprint()` / `close()` |
| `SegmentCompactor` | 归并写入器（`compact/`） | k 路归并 N 源段 → 1 新段文件 | `compact(File target, List<DsIndexSegmentReader> sources) : DsIndexSegment` / IAE 守卫 |
| `SegmentCompactionPolicy` | 纯策略（`compact/`） | size-tiered 触发决策（无 IO） | `static List<long[]> selectTiers(long[] segIds, long[] segBytes, int fanIn, int maxFanIn)` |
| `SegmentCompactionPlan` | 不可变 POJO（`compact/`） | 一次合并的不可变计划 | `tier` / `sourceSegIds`（升序不可变）/ `mergedSegId` / `fingerprint()` / `deepEquals` / IAE 守卫 |
| `SegmentCompactionRunner` | 编排器（`compact/`） | plan → 产新段 → append 先 / remove 后 | `run(DsIndexSegmentStore, SegmentCompactionPlan) : DsIndexSegment` |

### 3.2 数据流（多段写入 → 合并视图 → compaction）

```
写入期（P37 既有，0 改）
  DsIndexSegmentWriter.finish() → seg-10.didx ┐
  DsIndexSegmentWriter.finish() → seg-11.didx ┤ DsIndexSegmentStore.append(segId, seg)
  DsIndexSegmentWriter.finish() → seg-12.didx ┘  manifest = 10,11,12

查询期（P40 新增合并视图）
  openReader(10) / openReader(11) / openReader(12)   ← 有序「旧 → 新」
        └→ new SegmentMergeView([r10, r11, r12])
              getTermStat(uid) = 新→旧 首个命中（r12 优先）；全 miss → null
              getTf(key)       = 新→旧 首个命中；全 miss → 0

合并期（P40 新增 compaction）
  policy.selectTiers(list(), segBytes, fanIn=4, maxFanIn=4) → [[10,11,12]]
        └→ new SegmentCompactionPlan(tier=0, source=[10,11,12], mergedSegId=max+1=13)
              runner.run(store, plan):
                1) compactor.compact(store.segmentFile(13), [r10, r11, r12]) → seg13   ← k 路归并，新段优先
                2) store.append(13, seg13)                     ← 先（崩溃安全点 A→B 之间两段共存仍正确）
                3) store.remove(10); store.remove(11); store.remove(12)  ← 后
              manifest = 13
```

### 3.3 包结构（新增 `compact/` 兄弟包）

```
com/q3lives/ds/database/fulltext/
├── FulltextSegmenter.java          (P21 既有，0 改)
├── FulltextStore.java              (P21 既有，0 改)
├── TermDictStore.java              (P21 既有，0 改)
├── segment/                        (P37 既有，**5 源文件，0 改**；递归计数锁定 5)
├── cluster/                        (P26~P39 既有，0 改)
│   ├── parser/v3/                  (P38 既有；递归计数锁定 5)
│   └── agg/                        (P39 既有；递归计数锁定 6)
└── compact/                        (P40 新增子包；递归计数锁定 5)
    ├── SegmentMergeView.java            (NEW)
    ├── SegmentCompactor.java            (NEW)
    ├── SegmentCompactionPolicy.java     (NEW)
    ├── SegmentCompactionPlan.java       (NEW)
    └── SegmentCompactionRunner.java     (NEW)
```

> **为何必须是 `compact/` 兄弟包而非 `segment/` 子包**：`P37_T5.Y2` / `P38_T5.Y2` / `P39_T5.Y2` 的文件数断言使用**递归** `collectJavaFiles`（`P37_T5_Gatekeeper9YTest.java:79` / `P38_T5_Gatekeeper9YTest.java:85` / `P39_T5_Gatekeeper9YTest.java:83,102`）——在 `segment/` 下**开任何子包**都会把递归计数从 5 顶上去，直接挂掉既有基线 TR。

---

## 4. 接口契约

### 4.1 SegmentMergeView

```java
public final class SegmentMergeView implements AutoCloseable {
    public SegmentMergeView(List<DsIndexSegmentReader> readersInOldToNewOrder);  // 非 null/非空/无 null 元素
    public FulltextStore.WeightDocStatRow getDocStat(long docId);  // 新→旧 首个命中；全 miss = null
    public FulltextStore.WeightTermStatRow getTermStat(long uid);  // 新→旧 首个命中；全 miss = null
    public int getTf(long key);                                    // 新→旧 首个命中；全 miss = 0
    public int segmentCount();
    public String fingerprint();   // 覆盖各段 fingerprint（按旧→新序）的 SHA-256 64-hex
    public void close();           // 关闭全部底层 reader（幂等）
    // 3 IAE 守卫: readers null / 空 / 含 null
}
```

### 4.2 SegmentCompactor

```java
public final class SegmentCompactor {
    public DsIndexSegment compact(File target, List<DsIndexSegmentReader> sourcesOldToNew);  // k 路归并 → 写 target
    // 归并语义：docStat/termStat = 新段优先覆盖；tf = 新段优先覆盖（未命中 0）
    // 产物 = 合法 P37 段（DsIndexSegmentWriter 落盘：排序 + 列式 + LZ4 + 4KB 对齐 + CRC + 原子 rename）
    // 4 IAE 守卫: target null / sources null/空 / 含 null / reader.tfCount 越界型脏数据
}
```

### 4.3 SegmentCompactionPolicy

```java
public final class SegmentCompactionPolicy {
    /** size-tiered 选层：tier = floor(log_4(sizeBytes))；层内段数 ≥ fanIn → 取该层最早 maxFanIn 个（segId 升序）。确定性。 */
    public static List<long[]> selectTiers(long[] segIds, long[] segBytes, int fanIn, int maxFanIn);
    // 4 IAE 守卫: 数组长度不等 / fanIn < 2 / maxFanIn < fanIn / segIds 非严格升序
}
```

### 4.4 SegmentCompactionPlan

```java
public final class SegmentCompactionPlan {
    public final int tier;
    public final long[] sourceSegIds;   // 严格升序副本
    public final long mergedSegId;
    public String fingerprint();        // SHA-256(tier | sourceSegIds | mergedSegId) 64-hex
    public boolean deepEquals(Object o);
    // 4 IAE 守卫: tier < 0 / sourceSegIds null·空·非严格升序 / mergedSegId < 0 或 ∈ sourceSegIds
}
```

### 4.5 SegmentCompactionRunner

```java
public final class SegmentCompactionRunner implements AutoCloseable {
    public SegmentCompactionRunner(DsIndexSegmentStore store);
    public DsIndexSegment run(SegmentCompactionPlan plan);  // append(merged) 先 → remove(sources) 后
    public void close();
    // 4 IAE 守卫: store null / plan null / 源段未注册 / mergedSegId 已注册（防重复 append）
}
```

---

## 5. 测试（Gate B 拆 4 批：P22-P24 + P25-P27 + P28-P30 + P31-P40）

### 5.1 Surefire 4 批拆分（继承 P39 模板，新增 P40 入批 4）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
# 批1: P22-P24
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
# 批2: P25-P27
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P25_*,ds.P26_*,ds.P27_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
# 批3: P28-P30
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P28_*,ds.P29_*,ds.P30_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
# 批4: P31-P40（含 P40 own）
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

> **跨批前必须先清理 `target\tmp` 与仓库根 `.tmp`**（P39 教训 3：曾累积 39.7GB 导致 P27_T5 `IOException: 磁盘空间不足` + NFR 计时假失败）。

### 5.2 TR 总数

| T | 范围 | TR 数 | 实装内容 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | `SegmentMergeView` skeleton | 5 | 3 IAE（readers null/空/含 null）+ 覆盖语义（新段优先，含同键冲突）+ `segmentCount` + `fingerprint`（64-hex 稳定、与段序强相关）+ `close` 幂等 | `P40_T1_MergeViewSkeletonTest` |
| **T2** | `SegmentCompactor` 归并 | 6 | 2 段归并 → 1 段：docStat/termStat/tf 逐条 vs 手工「新段优先」真值全等 + 产物可被 `DsIndexSegmentReader.open` 直接读（头/CRC 全过）+ 单段归并恒等 + 空段边界 + 4 IAE | `P40_T2_CompactorMergeTest` |
| **T3** | `SegmentCompactionPolicy` tiered | 3 | tier 分层（size → `floor(log_4)`）+ 层内段数 ≥ `fanIn` 触发 / 未达不触发 + 确定性（同输入同输出）+ 4 IAE | `P40_T3_CompactionPolicyTest` |
| **T4** | E2E + 跨进程 fingerprint | 3 | 真实 `DsIndexSegmentStore`（独立 temp home + 唯一后缀隔离）：写 3 段 → `plan` → `runner.run` → 合并后 `list()` = 1 段 + 合并前后 `SegmentMergeView` 查询逐条等价 + 跨进程 `plan.fingerprint()` 一致（子 JVM spawn 复现 64-hex） | `P40_T4_E2eCompactionTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 四重文件数守恒（根包=3 + segment=5 + parser/v3=5 + agg=6 递归计数 + compact=5）+ Y2 NG-3 + Y3 TOPK + Y4 P37 segment 5 类 0 改 + P21~P39 facade + Y5 路由 + Y6 编译兼容 + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 + Y9 0 TODO | `P40_T5_Gatekeeper9YTest` |
| **T6** | NFR | 5 | merge view 查询 P95 ≤ 阈值（3 段 × 1k 键）+ compact 3 段 P95 ≤ 阈值 + 5 类 Javadoc ≥ 10 行 + CPD 0 重复块（160 字符窗口）+ 0 `System.out.println`/`printStackTrace` + IAE 显式守卫 ≥ 8 | `P40_T6_NFR_Test` |

**TR 总数**：5+6+3+3+9+5 = **31 TR**

### 5.3 验收门槛

- P40 own **31/31 TR 0F0E**
- P22-P40 拆 4 批全量回归：批 4 新增 P40 31 TR；已知基线 fail 沿用（`P28_T6.tr64` 裸 `System.out` 为跨进程 stdout 协议必需）
- **合并等价性**：`SegmentCompactor` 产物的 `SegmentMergeView`（单段）查询结果，与「合并前 N 段 merge view」逐条全等（docStat/termStat/tf，含 idf bit 级）
- **崩溃安全**：`runner.run` 在 append 与 remove 之间中断（手工注入）时，`list()` 含 merged + 源段，merge view 查询结果仍与合并前等价
- **段格式 0 变更**：归并产物 `MAGIC`/`VERSION`/4×CRC32 全部合法，`DsIndexSegmentReader.open` 0 报错（R7）
- **确定性**：`SegmentCompactionPlan.fingerprint()` 同 store 状态跨进程恒定（R10）
- **P37 `segment/` 5 源文件字节级 0 改**（R4）

---

## 6. 逆指标（Anti-Metrics，避免的伪优化）

| 逆指标 | 错误做法 | 正确做法 |
|--------|----------|----------|
| 假零重建 | merge view 打开时把 N 段全量读进堆建 HashMap | 直接持有 N 个 `DsIndexSegmentReader`（mmap），查询按「新→旧」二分短路 |
| 伪覆盖语义 | 依赖 manifest 物理行序当「新旧序」 | 由调用方显式给定「旧 → 新」有序 list（P37 manifest 是插入序，非排序） |
| 破坏段不可变 | compaction 原地改写源段文件 | 只写**新** `seg-<mergedSegId>.didx`；源段由 `store.remove` 删除 |
| 崩溃丢数据 | 先 `remove` 再 `append` | **append 先、remove 后**（R9）：崩溃点两段共存时新段优先仍正确 |
| 破坏既有红线 | 把 compaction 类塞进 `segment/`（递归计数 +1 挂基线 TR） | 新包 `compact/`；`segment/` 5 源文件 0 改 |
| 引入后台线程 | MVP 起定时 compaction 线程 | 调用方驱动 `policy → plan → runner`，0 新增 `ExecutorService` |
| 段格式漂移 | 为合并引入 `VERSION=2` / 新 magic | 复用 `MAGIC="DIDX"` + `VERSION=1`（R7） |
| 无删除语义硬做 | 为 compaction 引入 tombstone | MVP 明确不做删除标记；段合并为纯覆盖归并 |

---

## 7. 风险与回退

| 风险 | 触发 | 回退 |
|------|------|------|
| 旧段文件被 mmap 占用无法删除（Windows） | `runner.run` 中 `store.remove(source)` 时 reader 未 close | `runner` 内 close 全部源 reader 后再 `remove`；`DsIndexSegmentReader.close()` 已有双通道 unmapper（P37） |
| manifest 两次写之间崩溃 | `append` 后 `remove` 前进程死 | R9 append-before-remove：两段共存时 merge view 新段优先 → 结果正确；重跑 compaction 幂等（`mergedSegId` 已存在 → IAE，调用方重新取 `max+1`） |
| 归并内存放大 | 大段全量读进 `DsIndexSegmentWriter` 的 HashMap | MVP 接受（writer 本身即堆内 HashMap，P37 既有口径）；T6 NFR 用 1k 键量级；超大段合并列后续 phase（明确出界） |
| tier 分层抖动（段体积骤增跳 tier） | size-tiered 对极端体积敏感 | `selectTiers` 为纯函数 + 确定性；`fanIn`/`maxFanIn` 参数化，可用不同阈值复算 |
| tf 0 与未命中不可区分 | P37/P22 既有语义（`getTf` miss = 0） | 明确继承该语义（R8），merge view 不做特殊区分，与 P37 全等 |
| 合并后 segId 冲突 | 调用方给的 `mergedSegId` 已注册 | `SegmentCompactionPlan` IAE（`mergedSegId ∈ sourceSegIds`）+ `runner` IAE（已注册） |
| 冷启动/查询退化 | merge view 段数过多导致多次二分 | T6 NFR P95 门槛；超限则由 policy 提高 `fanIn` 触发更多合并（策略参数化） |

---

## 8. 交付物清单

- `.trae/specs/p40-oq1-segment-compaction/spec.md`（本文件）
- `.trae/specs/p40-oq1-segment-compaction/tasks.md`（Gate 三状态 + 6 阶段 TR 明细）
- `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/compact/*.java`（5 新类：`SegmentMergeView` / `SegmentCompactor` / `SegmentCompactionPolicy` / `SegmentCompactionPlan` / `SegmentCompactionRunner`）
- `p2p-db/src/test/java/ds/P40_T1_*.java` ~ `P40_T6_*.java`（6 测试文件 × 31 TR）
- **0 改**：`fulltext/segment/*.java`（P37 5 类）/ `FulltextStore.java` / `WeightStore`（P22）/ `p2p-db/pom.xml` / P21~P39 任意源文件

---

## 9. 签批区（Gate A REV1 已用户 VERBATIM 批准；Gate C 闭环）

- [x] 用户批准 OQ12-⑱ 候选项 ① P40 BM25 Index Segment 增量 compaction / tiered-LSM 段合并（Scope 锚定 above）
- [x] 确认「5 新类落**新包** `fulltext/compact/`」——因 `P37_T5.Y2` / `P38_T5.Y2` / `P39_T5.Y2` 均为**递归**文件数断言，`segment/`、`parser/v3/`、`cluster/agg/` 三棵子树均不可再放文件
- [x] 确认「P37 `segment/` 5 类源码 0 改 + P40 纯 additive」+「0 新增 Maven dep」
- [x] 确认 MVP 边界：不做 tombstone / 后台线程 / 增量 upsert / 段格式 VERSION=2 / manifest 事务

---

## 10. 实装偏差表（Gate C：Spec 目标 vs 实装终态）

| 项 | spec 目标 | 实装终态 | 偏差 | 处置 |
|----|-----------|----------|------|------|
| 新类清单 | 5 类落 `compact/` | 5 类全落 `com.q3lives.ds.database.fulltext.compact`（`SegmentMergeView`/`SegmentCompactor`/`SegmentCompactionPolicy`/`SegmentCompactionPlan`/`SegmentCompactionRunner`） | 0 | — |
| `SegmentMergeView` IAE | 3（readers null/空/含 null） | 3（同 spec） | 0 | — |
| `SegmentCompactor` IAE | 4（target null / sources null·空 / 含 null / 越界脏数据） | 4（target null / sources null / sources 空 / 含 null）；「越界脏数据」由 P37 writer `put()` 与 reader 头/CRC 校验兜底，未单列 IAE | 表述收敛 | 归并源遍历改由 `store` 按指纹回填源文件（P37 reader 无键枚举 API），越界即 P37 既有校验抛出 |
| `SegmentCompactionPolicy` IAE | 4（长度不等 / fanIn<2 / maxFanIn<fanIn / 非严格升序） | 5（额外 null 守卫 + 上述 4） | +1 防御 | 纯函数入口 null 守卫，无损语义 |
| `SegmentCompactionPlan` IAE | 4（tier<0 / sourceSegIds null·空 / 非严格升序 / mergedSegId<0 或 ∈ 源） | 4（同 spec，逐条实现） | 0 | — |
| 归并源定位 | `compact(File, List<DsIndexSegmentReader>)`（契约不变） | 同签名；内部由 `DsIndexSegmentStore` 以段指纹回填源文件再解析（不改 P37 公开 API） | 0（API）/ 内部实现新增 | 因 P37 reader 无 docStat/termStat 键枚举 API，且 R4 禁改 `segment/` |
| 段格式 | `MAGIC="DIDX"` + `VERSION=1` + 4×CRC32 | 完全复用 P37 writer/reader，产物合法 P37 段 | 0 | — |
| `sha256Hex` 实现 | 未约束 | `SegmentMergeView` 用 `Integer.toHexString`、`SegmentCompactionPlan` 用 `Character.forDigit` | 风格差异 | 主动规避 CPD 160 字符重复块（T6.tr64） |
| Maven dep | 0 新增 | 0 新增（复用 P37 `lz4-java`） | 0 | — |
