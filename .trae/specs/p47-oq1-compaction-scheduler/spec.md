# P47 OQ12-㉕① Compaction 自适应调度器（tick 驱动 / 限速 / 统计）—— spec.md（**✅ CLOSED 2026-10-30 REV1**）

> 用户 OQ12-㉕ 选 ①（承接 P46 列存/服务端聚合下推，已永久闭环）→ SP3.1 Gate A（本文件 + tasks.md）→ 用户 VERBATIM 批准 → Implement T1-T6 → Gate B 拆批回归 → Gate C 洁癖 5/5 闭环 → Stage 5 commit/push。

## Gate C 9Y 自检清单（Gate A 预置目标；Gate C 逐项 [x]，实锤 = `P47_T5_Gatekeeper9YTest` 9/9 0F0E）

- [x] Y1 **ZeroLeak3=3 + 七重递归文件数守恒**：`fulltext/` 根包 top-level 仍 = 3；且 `segment/`=5、`cluster/parser/v3/`=5、`cluster/agg/`=6、`compact/`=5、`cluster/groupby/`=5、`layered/`=3、`cluster/rowsource/`=2 全不变；P47 3 新类 100% 落**新兄弟包** `sched/`（递归 =3）→ 实锤 `P47_T5.y1_ZeroLeak3AndSevenFoldFileCountGuard` ✅（Gate B 批 4 0F0E）
- [x] Y2 NG-3 零依赖：0 新增 netty/grpc/p2p.client 引用 + `p2p-db/pom.xml` 0 新增 dep/plugin → 实锤 `P47_T5.y2_Ng3_SchedImportsJdkAndFirstPartyOnly` ✅
- [x] Y3 TOPK 铁律：0 改 K、0 触 `SearchOptions`、`bucketAccessCounter` 0 污染 → 实锤 `P47_T5.y3_TopKGuardrails_NoBucketCounterAndSearchOptionsReadOnly` ✅（含运行期 tick 前后 `SearchOptions` 快照全等）
- [x] Y4 Facade 单调不减：P37 `segment/` 5 类源码 **0 改**；P40 `compact/` 5 类源码 **0 改**；P21~P46 公开签名 0 改 → 实锤 `P47_T5.y4_FacadeMonotonic_UpstreamIntact` ✅（另锁 agg=6 / groupby=5）
- [x] Y5 路由公式：0 改 `ShardRouter` / P24 UNIT·DOC 路由 → 实锤 `P47_T5.y5_RoutingFormula_ShardRouterAndIdLayoutIntact` ✅（500 iter × 确定性 + `DsIdLayout` 往返）
- [x] Y6 P21~P47 编译兼容：既有类型 0 改 + P37 segment 5 + P40 compact 5 + P47 sched 3 全在 → 实锤 `P47_T5.y6_P21ToP47CompileCompat_ClassesExist` ✅
- [x] Y7 HybridHit ctor=2：0 改 → 实锤 `P47_T5.y7_HybridHit_ConstructorCountIs2` ✅
- [x] Y8 ShardTransport 5 方法：0 改 → 实锤 `P47_T5.y8_ShardTransport_5OriginalMethodsIntact` ✅
- [x] Y9 0 TODO/FIXME/HACK（`sched/` 源码）→ 实锤 `P47_T5.y9_NoTodoFixmeHackInSched` ✅

---

## 0. 待裁决（Gate A 必答，用户 VERBATIM 逐条签批）

> **Q1 线程模型（触 R10 红线，必须先裁决）**
>
> P40 spec **明文**（`.trae/specs/p40-oq1-segment-compaction/spec.md` L34 / L237）：「**不做** 后台线程 / 定时调度（由调用方驱动 `policy → plan → runner`；MVP 不新增 `ExecutorService`）」，并列为逆指标「引入后台线程」。P46 后 Roadmap 挂出的 OQ12-㉕① 字面为「compaction **后台调度器**」——二者存在措辞张力。
>
> Probe 实锤：`fulltext/` 全子树 **0 处** `ExecutorService` / `ScheduledExecutor` / `new Thread` / `Timer`；既有唯一限流范式 `RateLimiterGuard` 为**惰性令牌桶（零自有线程）**。故 P47 「调度器」的安全落法有二：

| 选项 | 方案 | 线程 | 对 R10 | 推荐 |
|------|------|------|--------|------|
| **A** | **纯 tick 驱动调度器**：`CompactionScheduler.tick()` 为纯决策+执行函数，由调用方在**既有线程**（写路径 / 既有后台任务 / 测试）显式驱动；限速用惰性令牌桶 + `System.nanoTime()` 冷却，**零自有线程** | 0 新增 | **完全满足**（P40 R10 继承不破） | ⭐**推荐** |
| B | 授权**单个守护线程**（`ScheduledExecutorService(1)` 或 `ScheduledThreadPoolExecutor`）周期自动 tick | +1 守护线程 | **放宽 R10**（须用户显式授权，并同步修订 P40「0 新增 ExecutorService」口径） | 备选 |

**请裁决**：`Q1 = A`（推荐，零自有线程，P40 红线不破）或 `Q1 = B`（授权单守护线程，需同步放宽 R10）。

> 其余无待裁决项；Scope / 红线 / 类清单 / MVP 边界如下，请逐条签批（§9）。

---

## 1. Scope（用户 OQ12-㉕ 候选项 ①）

> 原文（P46 闭环后 Roadmap 挂出）：`① compaction 后台调度器（顺延）`——在 P40 `compact/` 的 `SegmentCompactionPolicy → SegmentCompactionPlan → SegmentCompactionRunner` 之上追加「**自适应触发 / 限速 / 统计**」。

P47 = 在 P40 已有「策略 → 计划 → 编排执行」三件套之上，新增一个 **tick 驱动的自适应调度层**，把「何时合并、一轮最多合并几次、合并了几次/多少字节」从调用方手写变成可复用、可观测、确定性的组件：

1. **`CompactionSchedConfig`（不可变配置 + 自适应阈值）**：承载 `fanIn` / `maxFanIn` / `maxRunsPerTick`（限速：单 tick 最多执行合并次数）/ `cooldownNanos`（限速：两轮执行性 tick 的最小间隔）/ `highWatermarkSegments`（自适应水位）；纯函数 `effectiveFanIn(int segCount)`：段数 ≥ 水位时收紧 `fanIn`（更激进触发），否则维持默认；4+ IAE 守卫。
2. **`CompactionScheduler`（tick 驱动编排器，零自有线程）**：`tick()` 一轮 = ① 冷却限速判断 → ② `store.list()` 排序 + 逐段 `File.length()` 取体积 → ③ `CompactionSchedConfig.effectiveFanIn` 自适应 → ④ `SegmentCompactionPolicy.selectTiers` 选段 → ⑤ 逐组构造 `SegmentCompactionPlan`（`tier` 由组内段体积复算，`mergedSegId = max(segId)+1` 递增）→ ⑥ `SegmentCompactionRunner.run` 执行（继承 **append-before-remove** 崩溃安全）→ ⑦ `maxRunsPerTick` 二次限速；返回本轮实际合并次数。
3. **`CompactionStats`（可观测计数）**：`ticks` / `plansSelected` / `runsSucceeded` / `runsFailed` / `skippedCooldown` / `skippedRateLimit` / `segmentsMerged` / `mergedBytes`；`AtomicLong` 计数 + `resetForTest()`。

**MVP 边界（0 越界）**：
- 仅做「tick 驱动自适应触发 + 双限速（冷却 + 单 tick 上限）+ 统计」
- **不做** 后台线程 / 定时调度（**Q1=A 时** 由调用方驱动 `tick()`；0 新增 `ExecutorService`）
- **不做** 段格式变更（复用 P37 `MAGIC="DIDX"` + `VERSION=1`）
- **不做** tombstone / 删除标记 / 增量 upsert（继承 P40 段不可变语义）
- **不做** 多线程并发合并 / 锁 / 分段并行（MVP 单线程顺序 tick）
- **不做** 动态持久化调度状态（`cooldownNanos` 游标仅进程内存，重启归零）
- **P37 `segment/` 5 类源码 0 改；P40 `compact/` 5 类源码 0 改**；P21~P46 源码 0 改
- **0 新增 Maven dep**；3 新类全放**新兄弟包** `com.q3lives.ds.database.fulltext.sched`（因 `compact/` 递归计数=5 已锁死）

---

## 2. 红线（VERIFIED 永久钉死，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3 + 七重递归文件数守恒** | fulltext 根包 top-level 严格 = 3（`{TermDictStore, FulltextStore, FulltextSegmenter}`）；且**递归**源文件数 `segment/`=5、`cluster/parser/v3/`=5、`cluster/agg/`=6、`compact/`=5、`cluster/groupby/`=5、`layered/`=3、`cluster/rowsource/`=2 全不变；P47 3 新类 100% 落**新兄弟包** `sched/`（递归 =3） |
| R2 | **NG-3 零依赖** | `p2p-db/pom.xml` 0 新增 dep/plugin；`sched/` 源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*`；仅 JDK + first-party import |
| R3 | **TOPK 铁律** | 调度器纯段生命周期编排；0 改 K / 0 改 `SearchOptions`；`bucketAccessCounter[1025]` 0 污染 |
| R4 | **Facade 单调不减（P37 + P40 零触碰）** | P37 `segment/` 5 类源码**字节级 0 改**；P40 `compact/` 5 类源码**字节级 0 改**；P21~P46 公开签名 0 改；P47 纯 additive |
| R5 | **HybridHit ctor=2** | 0 改 |
| R6 | **P21~P46 字节级兼容** | TermDictStore / WeightStore / ShardRouter / ShardTransport / HJC / Resilience / DnfParser / AggParser / AggExecutor / P37 segment / P40 compact 全部 0 改 |
| R7 | **段格式 0 变更** | 复用 P37 `MAGIC="DIDX"` + `VERSION=1`；调度器产生的合并段必须是**合法 P37 段** |
| R8 | **覆盖语义确定性（新段优先）** | 继承 P40：源段新旧序由调度器按 **segId 升序** 显式给定（不依赖 manifest 插入序） |
| R9 | **崩溃安全** | 继承 P40 `SegmentCompactionRunner`：`append(merged)` **先于** `remove(sources)` |
| R10 | **0 新增线程 / 0 新增 `ExecutorService`** | **Q1=A**：`tick()` 纯函数式驱动，零自有线程；限速用惰性令牌桶 + `System.nanoTime()`，**严禁** `new Thread` / `ExecutorService` / `Timer` / `ScheduledExecutorService`（**Q1=B 时本红线须用户显式放宽**） |
| R11 | **计划确定性** | 继承 P40：`SegmentCompactionPlan.fingerprint()` 仅覆盖（源 segId 升序集 + tier + mergedSegId） |
| R12 | **调度确定性** | 同 store 状态 + 同 config + 同 tick 序 → 同「选段序列」；限速（冷却/上限）仅影响「本轮是否/执行几次」，**不影响选哪些段**（`selectTiers` 纯函数） |

---

## 3. 架构（3 个新类，P37/P40 源码 0 改，0 改 P21~P46）

### 3.1 新增清单

| 名称 | 类型 | 职责 | 关键字段/方法 |
|------|------|------|---------------|
| `CompactionSchedConfig` | 不可变配置（`sched/`） | 阈值 + 自适应水位 + 双限速参数 | `CompactionSchedConfig(int fanIn, int maxFanIn, int maxRunsPerTick, long cooldownNanos, int highWatermarkSegments)` / `effectiveFanIn(int segCount)` / `defaults()` |
| `CompactionScheduler` | 编排器（`sched/`，`AutoCloseable`） | tick 驱动：限速 → 自适应 → policy → plan → runner | `CompactionScheduler(DsIndexSegmentStore, CompactionSchedConfig)` / `int tick() throws IOException` / `CompactionStats stats()` / `close()` |
| `CompactionStats` | 可观测计数（`sched/`） | 调度指标累计 | 8 个 `AtomicLong` 计数 + getters + `resetForTest()` |

### 3.2 数据流（tick 驱动调度）

```
调用方（既有线程，0 新增线程）
   └→ scheduler.tick()
        1) 冷却限速：now - lastRunNanos < cooldownNanos → skippedCooldown++; return 0
        2) segIds = sort(store.list())  ; segBytes[i] = store.segmentFile(segIds[i]).length()
        3) effFanIn = config.effectiveFanIn(segIds.length)          ← 自适应
        4) groups = SegmentCompactionPolicy.selectTiers(segIds, segBytes, effFanIn, config.maxFanIn)
        5) for g in groups:
             if runsThisTick >= config.maxRunsPerTick → skippedRateLimit += 剩余; break   ← 二次限速
             tier     = tierOf(segBytes[g内首段])                    ← 复算（组内同层）
             mergedId = max(segIds) + 1 (+ runsThisTick)             ← 递增
             plan     = new SegmentCompactionPlan(tier, g, mergedId)
             runner.run(plan)  →  append(merged) 先 → remove(sources) 后   ← 继承 P40 崩溃安全（R9）
             runsThisTick++; runsSucceeded++; segmentsMerged += g.length; mergedBytes += Σ
        6) lastRunNanos = now ; return runsThisTick
```

### 3.3 包结构（新增 `sched/` 兄弟包）

```
com/q3lives/ds/database/fulltext/
├── FulltextSegmenter.java          (P21 既有，0 改)
├── FulltextStore.java              (P21 既有，0 改)
├── TermDictStore.java              (P21 既有，0 改)
├── segment/                        (P37 既有，**5 源文件，0 改**；递归计数锁定 5)
├── compact/                        (P40 既有，**5 源文件，0 改**；递归计数锁定 5)
├── layered/                        (P43 既有；递归计数锁定 3)
├── cluster/                        (P26~P46 既有，0 改)
│   ├── parser/v3/                  (P38 既有；递归计数锁定 5)
│   ├── agg/                        (P39 既有；递归计数锁定 6)
│   ├── groupby/                    (既有；递归计数锁定 5)
│   └── rowsource/                  (P45/P46 既有；递归计数锁定 2)
└── sched/                          (P47 新增兄弟包；递归计数锁定 3)
    ├── CompactionSchedConfig.java      (NEW)
    ├── CompactionScheduler.java        (NEW)
    └── CompactionStats.java            (NEW)
```

> **为何必须是 `sched/` 兄弟包而非 `compact/schedule/` 子包**：`P40_T5` / `P45_T5` / `P46_T5` 的文件数断言使用**递归** `collectJavaFiles`（`P46_T5_Gatekeeper9YTest.java:404-422`：`countJava` → `collectJavaFiles` 递归下钻）——在 `compact/` 下**开任何子包**都会把递归计数从 5 顶上去，直接挂掉既有基线 TR。故 P47 必须落 `fulltext/` 下的**新兄弟包** `sched/`。

---

## 4. 接口契约

### 4.1 CompactionSchedConfig

```java
public final class CompactionSchedConfig {
    public CompactionSchedConfig(int fanIn, int maxFanIn, int maxRunsPerTick,
                                 long cooldownNanos, int highWatermarkSegments);
    public static CompactionSchedConfig defaults();      // (4, 4, 1, 0L, Integer.MAX_VALUE)
    public int  fanIn();  public int maxFanIn();  public int maxRunsPerTick();
    public long cooldownNanos();  public int highWatermarkSegments();
    /** 自适应：segCount >= highWatermarkSegments → max(2, fanIn-1)（更激进）；否则 fanIn。确定性纯函数。 */
    public int effectiveFanIn(int segCount);
    // 5 IAE 守卫: fanIn < 2 / maxFanIn < fanIn / maxRunsPerTick < 1 / cooldownNanos < 0 / highWatermarkSegments < 1
}
```

### 4.2 CompactionScheduler

```java
public final class CompactionScheduler implements AutoCloseable {
    public CompactionScheduler(DsIndexSegmentStore store, CompactionSchedConfig config);  // 2 IAE: store null / config null
    /** 执行一轮 tick；返回本轮实际合并次数（0 = 无候选 / 冷却跳过 / 限速跳过）。 */
    public int tick() throws IOException;
    public CompactionStats stats();
    public void close();      // 关闭底层 SegmentCompactionRunner（幂等）
    // 3 IAE 守卫: store null / config null /（runner 继承：源段未注册 / mergedSegId 已注册）
}
```

### 4.3 CompactionStats

```java
public final class CompactionStats {
    public long ticks();  public long plansSelected();  public long runsSucceeded();  public long runsFailed();
    public long skippedCooldown();  public long skippedRateLimit();
    public long segmentsMerged();   public long mergedBytes();
    public void resetForTest();
    // 语义: ticks 每次 tick() 入口 +1；runsFailed 单组失败即 +1 并向上抛（本轮终止于该组，见 §10 D3）
}
```

---

## 5. 测试（Gate B 拆 4 批：P22-P24 + P25-P27 + P28-P30 + P31-P47）

### 5.1 Surefire 4 批拆分（继承 P46 模板，P47 入批 4）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P25_*,ds.P26_*,ds.P27_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P28_*,ds.P29_*,ds.P30_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*,ds.P42_*,ds.P43_*,ds.P44_*,ds.P45_*,ds.P46_*,ds.P47_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

> **跨批前必须先清理 `target\tmp` + 仓库根 `.tmp` + C 盘 `%LOCALAPPDATA%\Temp` 下 `junit*`/`dsdb_*` 残留**（P46 教训：残留 84 GB 曾致 C: 0 剩余 → P27_T6 `IOException: 磁盘空间不足`）。**每批 Gate B 前追加 C 盘残留清理**。

### 5.2 TR 总数（Gate A 目标口径；实跑数字 Implement 后回填）

| T | 范围 | TR 数 | 实装内容 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | `CompactionSchedConfig` | 5 | 5 IAE（fanIn<2 / maxFanIn<fanIn / maxRunsPerTick<1 / cooldownNanos<0 / highWatermark<1）+ `defaults()` 常量 + `effectiveFanIn` 自适应收放（≥水位收紧、<水位默认）| `P47_T1_SchedConfigTest` |
| **T2** | `CompactionStats` | 4 | 初值全 0 + 逐项累加 + `resetForTest` 归零 + 计数独立性 | `P47_T2_StatsTest` |
| **T3** | `CompactionScheduler` tick 主路径 | 5 | 无候选返回 0 + 单组合并 1 次 + 多组按 tier 序执行 + `mergedSegId` 递增不冲突 + 与 P40 手工 `policy→plan→runner` 等价 | `P47_T3_SchedulerTickTest` |
| **T4** | E2E + 限速 + 自适应 | 4 | 真实 `DsIndexSegmentStore`（独立 temp home + 唯一后缀隔离）：tick 合并 N→1 + `list()` 收敛 + 合并前后 `SegmentMergeView` 查询逐条等价 + 冷却跳过（`cooldownNanos` 内二连 tick）+ `maxRunsPerTick` 单轮上限 + 水位自适应触发差异 | `P47_T4_E2eSchedulerTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 + 七重递归文件数守恒（segment=5 / parser/v3=5 / agg=6 / compact=5 / groupby=5 / layered=3 / rowsource=2）+ sched=3；Y2 NG-3；Y3 TOPK；Y4 P37 segment 5 + P40 compact 5 源码 0 改 + P21~P46 facade；Y5 路由 0 改；Y6 P21~P47 编译兼容 + sched 3 类 `Class.forName`；Y7 HybridHit ctor=2；Y8 ShardTransport 5 方法；Y9 0 TODO/FIXME/HACK | `P47_T5_Gatekeeper9YTest` |
| **T6** | NFR | 4 | tick P95/均值 ≤ 阈值（4 段 × 1k 键）+ 3 类 Javadoc ≥ 10 行 + CPD 0 重复块（160 字符窗口）+ 0 `System.out.println`/`printStackTrace` | `P47_T6_NFR_Test` |

**TR 总数（实跑）**：5+4+5+4+9+4 = **31 TR** → 实跑 **31 run / 0F / 0E** ✅（2026-10-30）

### 5.3 验收门槛

- P47 own **31/31 TR 0F0E**（实跑回填）
- P22-P47 拆 4 批全量回归：批 4 新增 P47 31 TR；已知基线 fail 沿用（`P28_T6.tr64`）
- **合并等价性**：`tick()` 产物的 `SegmentMergeView`（单段）查询结果与「合并前 N 段 merge view」逐条全等
- **限速正确性**：`cooldownNanos` 内二次 `tick()` 返回 0 且 `skippedCooldown` +1；单轮合并次数 ≤ `maxRunsPerTick`
- **自适应正确性**：段数 ≥ 水位时 `effectiveFanIn` 收紧且触发更早（同段集下与默认配置触发差异可复现）
- **0 新增线程**：`sched/` 源码 0 处 `new Thread` / `ExecutorService` / `Timer` / `ScheduledExecutorService`（R10）
- **P37 `segment/` 5 + P40 `compact/` 5 源文件字节级 0 改**（R4）

---

## 6. 逆指标（Anti-Metrics，避免的伪优化）

| 逆指标 | 错误做法 | 正确做法 |
|--------|----------|----------|
| 引入后台线程 | 为「调度器」起定时线程 | **Q1=A**：`tick()` 由调用方在既有线程驱动；0 新增 `ExecutorService`（R10） |
| 依赖 manifest 插入序 | 直接拿 `store.list()` 原序喂 `selectTiers` | 显式 **segId 升序**排序后再选段（P40 `selectTiers` 要求严格升序） |
| 破坏段不可变 | 调度器原地改写段 | 只经 P40 `runner.run` 产新段 + remove 源段 |
| 崩溃丢数据 | 先 remove 后 append | 继承 P40 `append` 先 / `remove` 后（R9） |
| 破坏既有红线 | 把调度类塞进 `compact/`（递归 +N 挂基线 TR） | 新兄弟包 `sched/`；`compact/` 5 源文件 0 改 |
| 限速耦合选段 | 冷却跳过时改变选段结果 | 限速仅决定「本轮是否/几次执行」，不参与 `selectTiers`（R12） |
| 假自适应 | 水位只做展示不生效 | `effectiveFanIn` 为纯函数且真正参与 `selectTiers` 入参 |
| 段格式漂移 | 为调度引入 VERSION=2 | 复用 P37 `MAGIC="DIDX"` + `VERSION=1`（R7） |

---

## 7. 风险与回退

| 风险 | 触发 | 回退 |
|------|------|------|
| `store.list()` 非升序导致 `selectTiers` IAE | 直接透传 manifest 插入序 | 调度器内显式 `Arrays.sort`（R8/R12） |
| 同 tick 内 `mergedSegId` 冲突 | 多组连续合并取同一 `max+1` | 递增游标 `max(segIds)+1+runsThisTick`（T3 覆盖） |
| 单组失败 | `runner.run` 抛 `IOException`/`RuntimeException` | `runsFailed++` 后**向上抛**（本轮终止于该组，交由调用方决定重试/放弃；见 §10 D3）|
| 冷却游标重启归零 | 进程重启后首个 tick 立即执行 | MVP 接受（无持久化，明确出界）；`cooldownNanos` 仅进程内 |
| 旧段文件被 mmap 占用无法删（Windows） | `runner.run` 内源 reader 未 close | 继承 P40：runner 内 close 全部源 reader 后再 remove |
| 大段合并内存放大 | 全量读进 writer | 继承 P40 口径（MVP 接受，超大段合并列后续 phase） |
| `effectiveFanIn` 收紧后 `maxFanIn < fanIn` | 自适应 `fanIn-1` 下溢 | 下界钳制 `max(2, fanIn-1)`，且 `maxFanIn ≥ 原 fanIn ≥ 收紧值`（T1 覆盖） |

---

## 8. 交付物清单

- `.trae/specs/p47-oq1-compaction-scheduler/spec.md`（本文件）
- `.trae/specs/p47-oq1-compaction-scheduler/tasks.md`（Gate 三状态 + 6 阶段 TR 明细）
- `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/sched/*.java`（3 新类：`CompactionSchedConfig` / `CompactionScheduler` / `CompactionStats`）
- `p2p-db/src/test/java/ds/P47_T1_*.java` ~ `P47_T6_*.java`（6 测试文件 × 31 TR）
- **0 改**：`fulltext/segment/*.java`（P37 5 类）/ `fulltext/compact/*.java`（P40 5 类）/ `FulltextStore.java` / `WeightStore`（P22）/ `p2p-db/pom.xml` / P21~P46 任意源文件

---

## 9. 签批区（Gate A REV1 —— ✅ 用户已 VERBATIM 批准 2026-10-30）

- [x] **Q1 线程模型裁决**：`Q1 = A`（纯 tick 驱动，零自有线程，P40 R10 不破；**推荐**）—— 用户裁决「A（推荐）」
- [x] 批准 OQ12-㉕ 候选项 ① P47 Compaction 自适应调度器（Scope 锚定 §1）
- [x] 确认「3 新类落**新兄弟包** `fulltext/sched/`」——因 `P40_T5` / `P45_T5` / `P46_T5` 为**递归**文件数断言，`compact/` 下不可再开子包
- [x] 确认「P37 `segment/` 5 类 + P40 `compact/` 5 类源码 0 改；P47 纯 additive」+「0 新增 Maven dep」
- [x] 确认 MVP 边界：不做后台线程（Q1=A）/ tombstone / 增量 upsert / 段格式 VERSION=2 / 多线程并发合并 / 调度状态持久化

---

## 10. 实装偏差表（Gate C 回填：Spec 目标 vs 实装终态）

| 项 | spec 目标 | 实装终态 | 偏差 | 处置 |
|----|-----------|----------|------|------|
| 新类清单 | 3 类落新兄弟包 `sched/` | `CompactionSchedConfig` / `CompactionScheduler` / `CompactionStats` 3 类 100% 落 `fulltext/sched/`（递归计数 =3） | 无 | 符合 |
| Q1 线程模型 | 待裁决 | **Q1=A**：`tick()` 纯函数式驱动，`sched/` 源码 0 `new Thread` / 0 `ExecutorService` / 0 `Timer`（仅 1 处 Javadoc 文案命中 `ExecutorService` 字样，非代码） | 无 | R10 不破，符合 |
| IAE 守卫 | 见 §4 | `CompactionSchedConfig` 5 IAE（`fanIn<2` / `maxFanIn<fanIn` / `maxRunsPerTick<1` / `cooldownNanos<0` / `highWatermark<1`）；`CompactionScheduler` 2 IAE（store/config null）| 无 | 符合 |
| 限速实现 | 冷却 + 单 tick 上限 | 双限速实装：`lastRunNanos` + `System.nanoTime()` 冷却（`skippedCooldown`）；`maxRunsPerTick` 单 tick 上限（`skippedRateLimit` 记剩余组数）| 无 | 符合 |
| 自适应实现 | `effectiveFanIn` 水位收放 | `effectiveFanIn(segCount)`：`segCount ≥ highWatermarkSegments` → `max(2, fanIn-1)`，纯函数确定性 | 无 | 符合 |
| Maven dep | 0 新增 | `p2p-db/pom.xml` 0 新增 dep/plugin；`sched/` 仅 JDK + first-party import（P47_T5.y2 实锤） | 无 | 符合 |
| **D1 守卫联动** | P46 教训预置：新增兄弟包须同步子包计数守卫 | P47_T5 新增 `sched/`=3 断言；P40_T5/P45_T5/P46_T5 既有 7 项断言**不因新增 `sched/` 兄弟包而变动**（仅断言各自具名子目录）| 仅测试守卫更新，主源码 0 改 | 符合 |
| **D2 选段上限修复** | P40 `selectTiers` 入参 `maxFanIn` | P40 选层**恒取 `maxFanIn` 个**段（不按层内实际段数裁剪）→ 自适应收紧 `fanIn=3` 而 `maxFanIn=4` 时越界（tr44 实触发）。P47 侧新增私有 `compatibleMaxFanIn(ids,bytes,effFanIn,maxFanIn)`：取「命中层最小段数」与配置 `maxFanIn` 较小者作安全上限 | 调用侧兜底，**P40 `compact/` 5 类源码 0 改**（R4/R5 不破）| 符合，见 tasks.md D2 |
| **D3 单组失败处置** | §7「`runsFailed++` 后 `continue`（不中断整轮）；异常向上抛由调用方决定」（表述二义）| 实装取后者：`catch (IOException \| RuntimeException e)` → `recordRunFailed()` → **rethrow**（向上抛由调用方决定，本轮终止于该组）| 与「continue 不中断整轮」字面不符，与「异常向上抛」一致 | 保留实装语义；R7/R9 崩溃安全不受影响（`runner.run` 内 append-before-remove 已由 P40 保证）|
