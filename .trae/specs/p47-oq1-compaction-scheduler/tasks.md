# P47 OQ12-㉕① Compaction 自适应调度器（tick 驱动 / 限速 / 统计）—— tasks.md（**✅ CLOSED 2026-10-30 REV1**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ ✅ 用户 VERBATIM 批准 2026-10-30（OQ12-㉕① + **§0 Q1 = A**）
- [x] **Gate B**（P47 own 31/31 TR 0F0E + P22-P47 拆 4 批 + 已知基线 fail 沿用）→ ✅ 实跑 2026-10-30
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ ✅ 9Y 9/9 全 Y + 洁癖 5/5 100% 同步（2026-10-30）

### 实跑 TR 数（Implement 后实跑回填，**禁止预填**）
- P47 own = **31 TR（5+4+5+4+9+4）= 31 run / 0F / 0E**（实跑回填 2026-10-30）
- Gate B 拆 4 批 = 批1 P22-P24 = **76/76 0F0E** ✅ / 批2 P25-P27 = **80/80 0F0E** ✅ / 批3 P28-P30 = **100 run / 1F（已知基线 `P28_T6.tr64_noBarePrintAndGte5NullGuards`）/ 0E** ✅ / 批4 P31-P47 = **534 run / 1F（环境性 flaky `P44_T6.tr62_EvictedSearchP95WithinThreshold`，隔离重跑 5/5 绿）/ 0E** ✅（实跑回填 2026-10-30）

---

## Stage 1: Gate A（起草中，待签批）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 锚点（P40 `compact/` 5 类 API / `DsIndexSegmentStore` 生命周期 / 限流范式 `RateLimiterGuard` / 递归文件数红线 / 线程现状） | ✅ |
| 1.2 | 起草 spec.md（§0 待裁决 Q1 / Scope / 红线 R1-R12 / 架构 3 类 + 数据流 / 接口契约 / 测试 / 逆指标 / 风险 / 交付物 + 9Y 自检） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细 + Gate B 拆批命令） | ✅ |
| 1.4 | 提交 Gate A VERBATIM 逐条签批（含 §0 Q1 线程模型裁决） | ✅ 已签批（Q1=A） |

### Probe 结论（实锤锚点，详见 spec.md §1-§4）

1. **P40 `compact/` 5 类（唯一上游，0 改）**：
   - `SegmentCompactionPolicy.selectTiers(long[] segIds, long[] segBytes, int fanIn, int maxFanIn)`：**纯决策函数**；`tier = floor(log_4(sizeBytes))`（私有 `tierOf`）；层内段数 ≥ `fanIn` → 取该层最早 `maxFanIn` 个（segId 升序）；**要求 segIds 严格升序**（否则 IAE）；4 IAE（null / 长度不等 / `fanIn<2` / `maxFanIn<fanIn` / 非严格升序）
   - `SegmentCompactionPlan`：`public final int tier` / `public final long[] sourceSegIds`（clone）/ `public final long mergedSegId`；`fingerprint()` = SHA-256 64-hex；4 IAE
   - `SegmentCompactionRunner implements AutoCloseable`：`run(SegmentCompactionPlan) : DsIndexSegment`；执行序 **append-before-remove**（R9 崩溃安全）；4 IAE
   - `SegmentCompactor.compact(File, List<DsIndexSegmentReader>)`：k 路归并（新段优先），实际实现差异亦已核对（源定位经 store 按指纹回填）
2. **`DsIndexSegmentStore`（P37）**：`dir()` / `segmentFile(segId)` / `append(segId,seg)` / `load(segId)` / `openReader(segId)` / `list() : long[]` / `remove(segId)` / `close()`（幂等空实现）；manifest = 纯文本一行一 segId（**插入序，非排序** → 调度器必须显式排序）；**无任何 compaction/调度逻辑（无 TODO）**。
3. **限流范式（可借鉴，不重复造）**：`cluster/resilience/RateLimiterGuard` = **惰性令牌桶**（`AtomicLong` + `System.nanoTime()` 懒补，零自有线程）；`ClusterStateBroadcaster` = 同步 fan-out（`AtomicLong` 计数 + `resetCountersForTest`）。→ P47 限速/统计沿用同范式，**不新增线程**。
4. **线程现状（R10 实锤）**：`fulltext/` 全子树 **0 处** `ExecutorService` / `ScheduledExecutor` / `new Thread` / `Timer`；P40 spec L34/L237 明文「不做后台线程 / 0 新增 `ExecutorService`」→ **P47 触 R10，须 §0 Q1 裁决**。
5. **递归文件数红线（关键）**：`P40_T5` / `P45_T5` / `P46_T5` 的文件数断言均走**递归** `collectJavaFiles`（`P46_T5_Gatekeeper9YTest.java:404-422`）→ `compact/` 递归=5 已锁死 → P47 3 新类**必须**落 **新兄弟包** `fulltext/sched/`（不可 `compact/schedule/`）。
6. **`compact/` 5 类文件名**（`LS` 实锤）：`SegmentCompactionPlan` / `SegmentCompactionPolicy` / `SegmentCompactionRunner` / `SegmentCompactor` / `SegmentMergeView`。

---

## Stage 2: Implement T1-T6（串行 T1→T6，实跑 **31/31 TR 0F0E** ✅ 2026-10-30）

| T | 范围 | TR 数 | 关键实装 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | `CompactionSchedConfig` | 5 | 5 IAE（fanIn<2 / maxFanIn<fanIn / maxRunsPerTick<1 / cooldownNanos<0 / highWatermark<1）+ `defaults()` 常量 + `effectiveFanIn` 自适应收放 | `P47_T1_SchedConfigTest` |
| **T2** | `CompactionStats` | 4 | 初值全 0 + 逐项累加 + `resetForTest` 归零 + 计数独立性 | `P47_T2_StatsTest` |
| **T3** | `CompactionScheduler` tick 主路径 | 5 | 无候选返回 0 + 单组合并 1 次 + 多组按 tier 序执行 + `mergedSegId` 递增不冲突 + 与 P40 手工 `policy→plan→runner` 等价 | `P47_T3_SchedulerTickTest` |
| **T4** | E2E + 限速 + 自适应 | 4 | 真实 `DsIndexSegmentStore`（独立 temp home + 唯一后缀隔离）：tick 合并 N→1 + `list()` 收敛 + 合并前后查询逐条等价 + 冷却跳过 + `maxRunsPerTick` 上限 + 水位自适应触发差异 | `P47_T4_E2eSchedulerTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 **且七重递归文件数守恒**（segment=5 + parser/v3=5 + agg=6 + compact=5 + groupby=5 + layered=3 + rowsource=2 + sched=3）+ Y2 NG-3 + Y3 TOPK + Y4 P37 segment 5 + P40 compact 5 源码 0 改 + P21~P46 facade + Y5 路由 0 改 + Y6 P21~P47 + sched 3 类 `Class.forName` + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 方法 + Y9 0 TODO/FIXME/HACK | `P47_T5_Gatekeeper9YTest` |
| **T6** | NFR | 4 | tick P95/均值 ≤ 阈值（4 段 × 1k 键）+ 3 类 Javadoc ≥ 10 行 + CPD 0 重复块（160 字符窗口）+ 0 `System.out.println`/`printStackTrace` | `P47_T6_NFR_Test` |

**TR 总数（实跑）**：5+4+5+4+9+4 = **31 TR** → 实跑 **31 run / 0F / 0E** ✅（2026-10-30）

> **Implement 偏差 D2（已落实）**：P40 `selectTiers` 在命中层内**恒取 `maxFanIn` 个**段（不按层内实际段数裁剪），故自适应把 `fanIn` 收紧到 3 而 `maxFanIn=4` 时会越界。P47 侧新增私有 `compatibleMaxFanIn(...)`：取「命中层最小段数」与配置 `maxFanIn` 的较小者作为选段上限，**保证不越界且 P40 `compact/` 5 类源码 0 改**（R4/R5 不破）。

> **P46 教训·守卫联动（须在 T5 落实）**：向受 Gatekeeper 计数的 `fulltext/` 子树**合法新增兄弟包** `sched/` 后，须同步推进所有钉死计数的历史守卫（本段 P47_T5 新增 `sched/`=3 断言；同时确认 P40_T5/P45_T5/P46_T5 既有 7 项断言**不因新增兄弟包而变动**——因它们只断言各自具名子目录）。属「实装偏差 D1」，仅测试守卫更新、0 改上游主源码。

---

## Stage 3: Gate B（拆 4 批全量回归，✅ 已完成 2026-10-30）

### 3.1 命令（每批前清理 `target\tmp` + 仓库根 `.tmp` + C 盘残留；改源码/测试后先 `test-compile`）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P25_*,ds.P26_*,ds.P27_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P28_*,ds.P29_*,ds.P30_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*,ds.P42_*,ds.P43_*,ds.P44_*,ds.P45_*,ds.P46_*,ds.P47_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

> **每批 Gate B 前追加 C 盘残留清理**（`%LOCALAPPDATA%\Temp` 下 `junit*` / `dsdb_*`；P46 教训：残留 84 GB 致 C: 0 剩余 → `IOException: 磁盘空间不足`）。

### 3.2 验收门槛（Implement 后逐项回填实跑数字）

- [x] P47 own **31/31 TR 0F0E**（实跑 2026-10-30：31 run / 0F / 0E）
- [x] 批 1 P22-P24 = **76/76 0F0E** ✅（实跑 2026-10-30，BUILD SUCCESS）
- [x] 批 2 P25-P27 = **80/80 0F0E** ✅（实跑 2026-10-30，BUILD SUCCESS）
- [x] 批 3 P28-P30 = **100 run / 1F / 0E** ✅（实跑 2026-10-30；唯一 fail = 已知基线 `P28_T6.tr64_noBarePrintAndGte5NullGuards`，沿用不阻塞）
- [x] 批 4 P31-P47 = **534 run / 1F / 0E** ✅（实跑 2026-10-30；唯一 fail = `P44_T6.tr62_EvictedSearchP95WithinThreshold` 环境性 flaky，隔离重跑 5/5 0F0E 绿；P47 own 31/31 0F0E；**P40/P37 源码 0 改，非回归**）

> **批 4 环境性 flaky 说明**：本机同时运行用户其他项目的 dev server（`leifan-platform-bff`、`dhbyd-admin`，见 `Win32_Process`) 与本批 P31/P35 等**跨进程测试派生的子 JVM**，CPU 争抢致 P44_T6 的 P95 计时门限（`base=64ms ev=234ms`，阈值 ≤+5%/25ms 容差）瞬时超限。**隔离重跑 `ds.P44_T6_NFR_Test` = 5/5 0F0E BUILD SUCCESS**，且 P47 纯段生命周期编排，0 触 WeightStore / 搜索读路径 → 判定为环境性计时抖动，非 P47 回归。
- [x] 合并等价性（tick 后单段 merge view == 合并前 N 段 merge view 逐条全等）→ 实锤 `P47_T4_E2eSchedulerTest` ✅
- [x] 限速正确性（冷却内二次 tick 返回 0；单轮 ≤ `maxRunsPerTick`）→ 实锤 `P47_T4` 冷却跳过 + 单轮上限 ✅
- [x] 自适应正确性（段数 ≥ 水位时 `effectiveFanIn` 收紧且触发更早）→ 实锤 `P47_T4` 水位触发差异（默认 4 段不触发 / 水位=3 触发 1 次）+ `P47_T1` ✅
- [x] 0 新增线程（`sched/` 源码 0 `ExecutorService`/`Thread`/`Timer`）→ 实锤 `P47_T5.y2` + grep 仅 1 Javadoc 文案命中 ✅
- [x] P37 `segment/` 5 + P40 `compact/` 5 源文件字节级 0 改 → 实锤 `P47_T5.y4` + `git diff --stat` ✅

---

## Stage 4: Gate C（9Y 自检 + 洁癖 5/5，✅ 已完成 2026-10-30）

- [x] ⓪ 9Y 自检 9 项全 `[x]`（spec.md 顶部清单，逐项附实锤测试名）→ ✅ 9/9 全 Y
- [x] ① spec.md 回填 Gate C 9Y + §10 实装偏差表（D1/D2/D3）+ 签批区 → ✅
- [x] ② tasks.md 回填 Gate 三状态 + 实跑 TR 数字 → ✅
- [x] ③ Roadmap `docs/dsdb-design-status-and-roadmap.md`：`OQ12-㉕①` ⏳ → ✅ 闭环描述 + 挂出 OQ12-㉖ → ✅
- [x] ④ Project Memory 追加 P47 交付摘要 + 教训 → ✅

---

## Stage 5: 交付（commit + push，待完成）

- [ ] `git add` P47 源码 3 类 + 测试 6 文件 + spec.md + tasks.md + Roadmap + Memory
- [ ] `git commit`（P47 T1-T6 + Gate C 5/5 closure）
- [ ] `git push origin main`

---

## 红线总表（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫 |
|------|------|------|
| R1 | ZeroLeak3=3 + 七重递归文件数守恒 | 根包=3；segment=5；parser/v3=5；agg=6；compact=5；groupby=5；layered=3；rowsource=2；**sched=3** |
| R2 | NG-3 零依赖 | pom 0 新增 dep/plugin；0 netty/grpc/p2p.client；仅 JDK + first-party import |
| R3 | TOPK 铁律 | 0 改 K / 0 `SearchOptions` / `bucketAccessCounter` 0 污染 |
| R4 | Facade 单调不减 | P37 `segment/` 5 类 + P40 `compact/` 5 类字节级 0 改；P21~P46 公开签名 0 改 |
| R5 | HybridHit ctor=2 | 0 改 |
| R6 | P21~P46 字节级兼容 | 全类型 0 改 |
| R7 | 段格式 0 变更 | 复用 `MAGIC="DIDX"` + `VERSION=1`；产物合法 P37 段 |
| R8 | 覆盖语义确定性（新段优先） | 源段新旧序由调度器按 segId 升序显式给定，不依赖 manifest 行序 |
| R9 | 崩溃安全 | 继承 P40：`append(merged)` 先于 `remove(sources)` |
| R10 | **0 新增线程 / 0 `ExecutorService`** | **Q1=A**：`tick()` 纯函数式驱动，零自有线程；**Q1=B 时须用户显式放宽** |
| R11 | 计划确定性 | `plan.fingerprint()` 仅覆盖（源 segId 升序集 + tier + mergedSegId） |
| R12 | 调度确定性 | 同 store 状态 + 同 config + 同 tick 序 → 同选段序列；限速不影响选段 |
