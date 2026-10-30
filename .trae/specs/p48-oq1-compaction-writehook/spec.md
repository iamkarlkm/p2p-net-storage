# P48 OQ12-㉖③ Compaction 调度与写路径自动挂钩（写路径装饰器 / 阈值触发 / 0 新线程）—— spec.md（**REV1 Gate A 已批准 2026-10-30；Gate B 全量回归通过；Gate C 已闭环**）

> 用户 OQ12-㉖ 选 ③（承接 P47 Compaction 自适应调度器，已永久闭环 `d430812`）→ SP3.1 Gate A（本文件 + tasks.md）→ 用户 VERBATIM 批准 → Implement T1-T6 → Gate B 拆批回归 → Gate C 洁癖 5/5 闭环 → Stage 5 commit/push。

## Gate C 9Y 自检清单（✅ 全 9 项通过；实锤 = `P48_T5_Gatekeeper9YTest` 9/9 0F0E）

- [x] Y1 **ZeroLeak3=3 + 八重递归文件数守恒**：`fulltext/` 根包 top-level 仍 = 3；且 `segment/`=5、`cluster/parser/v3/`=5、`cluster/agg/`=6、`compact/`=5、`cluster/groupby/`=5、`layered/`=3、`cluster/rowsource/`=2、`sched/`=3 全不变；P48 3 新类 100% 落**新兄弟包** `autocompact/`（递归 =3）→ `P48_T5.y1_ZeroLeak3AndEightFoldFileCountGuard` ✅
- [x] Y2 NG-3 零依赖：0 新增 netty/grpc/p2p.client 引用 + `p2p-db/pom.xml` 0 新增 dep/plugin → `P48_T5.y2_Ng3_AutoCompactionImportsJdkAndFirstPartyOnly` ✅
- [x] Y3 TOPK 铁律：0 改 K、0 触 `SearchOptions`、`bucketAccessCounter` 0 污染 → `P48_T5.y3_TopKGuardrails_NoBucketCounterAndSearchOptionsReadOnly` ✅
- [x] Y4 Facade 单调不减：P37 `segment/` 5 类源码 **0 改**；P40 `compact/` 5 类 + P47 `sched/` 3 类源码 **0 改**；P21 `FulltextStore` 源码 **0 改**；P21~P47 公开签名 0 改 → `P48_T5.y4_FacadeMonotonic_UpstreamIntact` ✅（含 `FulltextStore.java` 源码断言不含 `AutoCompaction`/`autocompact`/`DsIndexSegmentStore`）
- [x] Y5 路由公式：0 改 `ShardRouter` / P24 UNIT·DOC 路由 → `P48_T5.y5_RoutingFormula_ShardRouterAndIdLayoutIntact` ✅
- [x] Y6 P21~P48 编译兼容：既有类型 0 改 + P37 segment 5 + P40 compact 5 + P47 sched 3 + P48 autocompact 3 全在 → `P48_T5.y6_P21ToP48CompileCompat_ClassesExist` ✅（19 类含 `autocompact` 3 类）
- [x] Y7 HybridHit ctor=2：0 改 → `P48_T5.y7_HybridHit_ConstructorCountIs2` ✅
- [x] Y8 ShardTransport 5 方法：0 改 → `P48_T5.y8_ShardTransport_5OriginalMethodsIntact` ✅
- [x] Y9 0 TODO/FIXME/HACK（`autocompact/` 源码）→ `P48_T5.y9_NoTodoFixmeHackInAutoCompaction` ✅

---

## 0. 待裁决（Gate A 必答，用户 VERBATIM 逐条签批）

> **Q0 Scope 确认（本阶段候选）**
>
> 本 spec 按用户「继续」推进，默认锚定 **OQ12-㉖ 候选 ③ = compaction 调度与写路径自动挂钩**。若用户实为选择 ①②④，请于签批时重定向，本文件作废重起。**请确认：`Q0 = ③`。**

> **Q1 挂载方式（决定是否触「上游 0 改」红线）**
>
> Probe 实锤：P37 `DsIndexSegmentStore.append(long, DsIndexSegment)` 是**唯一段生产写入口**（段不可变、append-only）；`segment/` 递归计数=5 已锁死（R4 红线禁改）；P21 `FulltextStore` 不持段仓库、不调 `export`，其写方法（`insert`/`ingestDoc`/`putDoc`）只写内存 WeightStore，**不产段**。故「写路径自动挂钩」的安全落法有二：

| 选项 | 方案 | 改上游 | R10 | 推荐 |
|------|------|--------|-----|------|
| **A** | **写路径装饰器**：新增 `AutoCompactionHook`，其 `append(segId, seg)` **委托** P37 `store.append` 后，按阈值策略在**同一写入线程内联**调用 P47 `CompactionScheduler.tick()`；调用方以 hook 替代裸 `store.append`。**P37 `segment/` 0 改 + P21 `FulltextStore` 0 改**（纯 composition，新兄弟包） | 0 | 0 新线程（内联写者线程） | ⭐**推荐** |
| B | 直接改 P21 `FulltextStore` 写方法内联触发（append-only 扩展：加段仓库 + scheduler 引用 + 写计数） | P21 +N（append-only 例外） | 0 新线程 | 备选（变更面更大，且 `FulltextStore` 写方法不产段，语义错位） |

**请裁决**：`Q1 = A`（推荐，纯 composition，上游 0 改）或 `Q1 = B`（改 P21，须登记 append-only 例外）。

> **Q2 新类落包（决定是否破 P47 基线）**
>
> P47 `P47_T5.y1` 断言 `sched/` 递归源文件数 **恰 = 3**；若 P48 新类进 `sched/` 会直接挂掉该基线（须改 P47 测试守卫，违背「P37~P47 Spec 一字不动」偏好）。故 P48 的 3 新类**必须**落 **新兄弟包** `fulltext/autocompact/`（递归 =3）。
>
> **请确认：`Q2 = autocompact/` 新兄弟包。**

> 其余 Scope / 红线 / 类清单 / MVP 边界如下，请逐条签批（§9）。

---

## 1. Scope（用户 OQ12-㉖ 候选项 ③）

> 原文（P47 闭环后 Roadmap 挂出）：`③ compaction 调度与写路径自动挂钩（把 tick() 挂到既有写路径 / 既有后台任务，仍 0 新线程红线需用户裁决）`。

P48 = 在 P47 `CompactionScheduler.tick()`（调用方手动驱动）之上，新增一个 **写路径装饰器**，把「每次段写入后是否顺带触发一轮 compaction」从「调用方记得手动调 tick」变成**自动、可配阈值、可观测**，且 **0 新线程**（内联于写入者线程）：

1. **`AutoCompactionPolicy`（不可变阈值策略）**：承载 `writesPerTrigger`（累计写入段数达到即触发一次 `tick()`，≥ 1）/ `minSegmentsToCompact`（参与合并的段数下限，低于即便达写入阈值也跳过，≥ 2）；纯函数 `shouldTrigger(int writesSinceLastTick, int segCount)`：两条件同时满足才触发；2 IAE 守卫。
2. **`AutoCompactionHook`（写路径装饰器，`AutoCloseable`，零自有线程）**：`append(segId, seg)` = ① 委托 `store.append` ② `writesSinceLastTick++` ③ `policy.shouldTrigger(...)` 为真 → 同一线程内联 `CompactionScheduler.tick()` 并复位写计数；返回落盘段描述；`tickNow()` 显式手动触发；持有并暴露 P47 `CompactionScheduler`（`stats()` 委托）。
3. **`AutoCompactionStats`（自动触发可观测计数）**：`writesObserved` / `triggersFired` / `triggersSkipped`（写入达标但段数不足）；`AtomicLong` 计数 + `resetForTest()`。

**MVP 边界（0 越界）**：
- 仅做「写路径装饰器 + 阈值触发 + 自动/显式 tick + 统计」
- **不做** 后台线程 / 定时调度（0 新增 `ExecutorService` / `Timer` / `new Thread`；tick 内联写者线程）
- **不做** 段格式变更（复用 P37 `MAGIC="DIDX"` + `VERSION=1`）
- **不做** tombstone / 删除标记 / 增量 upsert（继承 P40 段不可变语义）
- **不做** 写路径段化本身（`insert`/`ingestDoc`/`putDoc` 仍走 P22 内存 WeightStore；段生产仍由 P42 `LayeredIndexExporter.export` 决定）
- **不做** 多线程并发合并 / 锁 / 分段并行（单线程顺序写 + 内联 tick）
- **不做** 调度状态持久化（写计数与冷却游标仅进程内存，重启归零）
- **P37 `segment/` 5 类 + P40 `compact/` 5 类 + P47 `sched/` 3 类 + P21 `FulltextStore` 源码 0 改**；**0 新增 Maven dep**；3 新类全放**新兄弟包** `fulltext/autocompact/`

---

## 2. 红线（VERIFIED 永久钉死，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3 + 八重递归文件数守恒** | fulltext 根包 top-level 严格 = 3；且**递归**源文件数 `segment/`=5、`cluster/parser/v3/`=5、`cluster/agg/`=6、`compact/`=5、`cluster/groupby/`=5、`layered/`=3、`cluster/rowsource/`=2、`sched/`=3 全不变；P48 3 新类 100% 落**新兄弟包** `autocompact/`（递归 =3） |
| R2 | **NG-3 零依赖** | `p2p-db/pom.xml` 0 新增 dep/plugin；`autocompact/` 源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*`；仅 JDK + first-party import |
| R3 | **TOPK 铁律** | 写路径装饰器纯段生命周期编排；0 改 K / 0 改 `SearchOptions`；`bucketAccessCounter[1025]` 0 污染 |
| R4 | **Facade 单调不减（P37 + P40 + P47 + P21 零触碰）** | P37 `segment/` 5 类 + P40 `compact/` 5 类 + P47 `sched/` 3 类 + P21 `FulltextStore` 源码**字节级 0 改**；P21~P47 公开签名 0 改；P48 纯 additive |
| R5 | **HybridHit ctor=2** | 0 改 |
| R6 | **P21~P47 字节级兼容** | TermDictStore / WeightStore / ShardRouter / ShardTransport / HJC / Resilience / DnfParser / AggParser / AggExecutor / GroupByExecutor / P37 segment / P40 compact / P42 layered / P47 sched 全部 0 改 |
| R7 | **段格式 0 变更** | 复用 P37 `MAGIC="DIDX"` + `VERSION=1`；hook 产出的段仍是**合法 P37 段**（经 P40 runner） |
| R8 | **覆盖语义确定性（新段优先）** | 继承 P40/P47：源段新旧序由 P47 调度器按 **segId 升序** 显式给定 |
| R9 | **崩溃安全** | 继承 P40 `SegmentCompactionRunner`：`append(merged)` **先于** `remove(sources)` |
| R10 | **0 新增线程 / 0 新增 `ExecutorService`** | 装饰器 `append` 内联调用 `tick()`（**写者线程**）；**严禁** `new Thread` / `ExecutorService` / `Timer` / `ScheduledExecutorService` |
| R11 | **写路径透明性** | `hook.append(segId,seg)` 除「可能追加合并」外，其 observable 效果（manifest 登记 + 返回值 + 异常语义）与裸 `store.append` **一致**；阈值未达时**逐字节等价**于裸调用 |
| R12 | **触发确定性** | 同写入序列 + 同 store 状态 + 同 policy → 同「是否触发 / 触发几次」；阈值仅影响「是否顺带 tick」，不影响 `selectTiers` 选段（继承 P47 R12） |

---

## 3. 架构（3 个新类，P37/P40/P47/P21 源码 0 改，0 改 P21~P47）

### 3.1 新增清单

| 名称 | 类型 | 职责 | 关键字段/方法 |
|------|------|------|---------------|
| `AutoCompactionPolicy` | 不可变策略（`autocompact/`） | 触发阈值 + 段数下限 | `AutoCompactionPolicy(int writesPerTrigger, int minSegmentsToCompact)` / `shouldTrigger(int, int)` / `defaults()` |
| `AutoCompactionHook` | 写路径装饰器（`autocompact/`，`AutoCloseable`） | append 委托 + 阈值内联 tick | `AutoCompactionHook(DsIndexSegmentStore, CompactionSchedConfig, AutoCompactionPolicy)` / `DsIndexSegment append(long, DsIndexSegment)` / `int tickNow()` / `CompactionStats stats()` / `AutoCompactionStats autoStats()` / `close()` |
| `AutoCompactionStats` | 可观测计数（`autocompact/`） | 写/触发计数 | 3 个 `AtomicLong` 计数 + getters + `record*` + `resetForTest()` |

### 3.2 数据流（写路径装饰 + 内联触发）

```
调用方（既有线程，0 新增线程）
   └→ hook.append(segId, seg)
        1) onDisk = store.append(segId, seg)          ← 委托 P37（写 manifest，0 改）
        2) autoStats.recordWrite(); writesSinceLastTick++
        3) segCount = store.list().length
        4) if policy.shouldTrigger(writesSinceLastTick, segCount):
             runs = scheduler.tick()                   ← P47 内联（R10：写者线程）
             if runs > 0: autoStats.recordTrigger(); writesSinceLastTick = 0
        5) return onDisk
```

### 3.3 包结构（新增 `autocompact/` 兄弟包）

```
com/q3lives/ds/database/fulltext/
├── FulltextSegmenter.java          (P21 既有，0 改)
├── FulltextStore.java              (P21 既有，0 改)
├── TermDictStore.java              (P21 既有，0 改)
├── segment/                        (P37 既有，**5 源文件，0 改**；递归计数锁定 5)
├── compact/                        (P40 既有，**5 源文件，0 改**；递归计数锁定 5)
├── layered/                        (P42 既有；递归计数锁定 3)
├── sched/                          (P47 既有，**3 源文件，0 改**；递归计数锁定 3)
├── cluster/                        (P26~P46 既有，0 改)
│   ├── parser/v3/                  (P38 既有；递归计数锁定 5)
│   ├── agg/                        (P39 既有；递归计数锁定 6)
│   ├── groupby/                    (P41 既有；递归计数锁定 5)
│   └── rowsource/                  (P45/P46 既有；递归计数锁定 2)
└── autocompact/                    (P48 新增兄弟包；递归计数锁定 3)
    ├── AutoCompactionPolicy.java       (NEW)
    ├── AutoCompactionHook.java         (NEW)
    └── AutoCompactionStats.java        (NEW)
```

> **为何必须是 `autocompact/` 新兄弟包**：`P40_T5` / `P45_T5` / `P46_T5` / `P47_T5` 的文件数断言使用**递归** `collectJavaFiles` —— 在 `sched/` 下加类会把 `sched/` 递归计数从 3 顶上去（挂掉 `P47_T5.y1` 基线），在 `compact/` 下同理；故 P48 必须落 `fulltext/` 下的**新兄弟包** `autocompact/`。

---

## 4. 接口契约

### 4.1 AutoCompactionPolicy

```java
public final class AutoCompactionPolicy {
    public AutoCompactionPolicy(int writesPerTrigger, int minSegmentsToCompact);
    public static AutoCompactionPolicy defaults();      // (4, 4)
    public int  writesPerTrigger();  public int minSegmentsToCompact();
    /** 纯函数：writesSinceLastTick >= writesPerTrigger 且 segCount >= minSegmentsToCompact。 */
    public boolean shouldTrigger(int writesSinceLastTick, int segCount);
    // 2 IAE 守卫: writesPerTrigger < 1 / minSegmentsToCompact < 2
}
```

### 4.2 AutoCompactionHook

```java
public final class AutoCompactionHook implements AutoCloseable {
    public AutoCompactionHook(DsIndexSegmentStore store, CompactionSchedConfig schedCfg,
                              AutoCompactionPolicy policy);      // 3 IAE: store/schedCfg/policy null
    /** 写路径装饰器：委托 store.append 后按策略机会式同一线程内联 tick()，返回落盘段。 */
    public DsIndexSegment append(long segId, DsIndexSegment seg) throws IOException;
    /** 显式手动触发一轮 tick（绕过写入阈值），返回本轮合并次数。 */
    public int tickNow() throws IOException;
    public int writesSinceLastTick();
    public CompactionStats stats();        // 委托底层 P47 scheduler
    public AutoCompactionStats autoStats();
    public void close();                   // 幂等 → scheduler.close()
}
```

### 4.3 AutoCompactionStats

```java
public final class AutoCompactionStats {
    public long writesObserved();  public long triggersFired();  public long triggersSkipped();
    public void recordWrite();  public void recordTrigger();  public void recordSkip();
    public void resetForTest();
    // 语义: writesObserved 每次 append 入口 +1；triggersFired 每次自动 tick 成功触发 +1；
    //       triggersSkipped 写入达阈值但段数不足而未触发 +1
}
```

---

## 5. 测试（Gate B 拆 4 批：P22-P24 + P25-P27 + P28-P30 + P31-P48）

### 5.1 Surefire 4 批拆分（继承 P47 模板，P48 入批 4）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P25_*,ds.P26_*,ds.P27_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P28_*,ds.P29_*,ds.P30_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*,ds.P42_*,ds.P43_*,ds.P44_*,ds.P45_*,ds.P46_*,ds.P47_*,ds.P48_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

> **跨批前必须先清理 `target\tmp` + 仓库根 `.tmp` + C 盘 `%LOCALAPPDATA%\Temp` 下 `junit*`/`dsdb_*` 残留**（P46/P47 教训：残留曾致 C: 0 剩余 → `IOException: 磁盘空间不足`）。**长跑日志用 `*> gatebN.log` 落盘再读**（P47 教训：`| Select-String` 管道贪婪缓冲致无输出）。

### 5.2 TR 总数（Gate A 目标口径；实跑数字 Implement 后回填）

| T | 范围 | TR 数 | 实装内容 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | `AutoCompactionPolicy` | 5 | 2 IAE（`writesPerTrigger<1` / `minSegmentsToCompact<2`）+ `defaults()` 常量 + `shouldTrigger` 四象限（双达标 / 写入不足 / 段数不足 / 边界相等）+ 纯函数确定性 | `P48_T1_PolicyTest` |
| **T2** | `AutoCompactionStats` | 4 | 初值全 0 + 逐项累加 + `resetForTest` 归零 + 计数独立性 | `P48_T2_StatsTest` |
| **T3** | `AutoCompactionHook` 主路径 | 5 | 3 IAE（store/schedCfg/policy null）+ `append` 委托等价裸 `store.append`（R11 未达阈值时逐字节等价）+ 达阈值自动触发 tick + 未达阈值不触发 + `tickNow()` 显式触发 | `P48_T3_HookMainTest` |
| **T4** | E2E | 4 | 真实 `DsIndexSegmentStore`（独立 temp home + 唯一后缀隔离）：多次 `hook.append` 后自动合并 N→1 + `list()` 收敛 + 合并前后查询逐条等价 + `close()` 幂等 | `P48_T4_E2eAutoHookTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 **且八重递归文件数守恒**（segment=5 + parser/v3=5 + agg=6 + compact=5 + groupby=5 + layered=3 + rowsource=2 + sched=3 + autocompact=3）+ Y2 NG-3 + Y3 TOPK + Y4 P37 segment 5 + P40 compact 5 + P47 sched 3 + P21 FulltextStore 源码 0 改 + Y5 路由 0 改 + Y6 P21~P48 + autocompact 3 类 `Class.forName` + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 方法 + Y9 0 TODO/FIXME/HACK | `P48_T5_Gatekeeper9YTest` |
| **T6** | NFR | 4 | `hook.append`（未触发态）均值/P95 ≤ 裸 `store.append` + 阈值（4 段小写）+ 3 类 Javadoc ≥ 10 行 + CPD 0 重复块（160 字符窗口）+ 0 `System.out.println`/`printStackTrace` | `P48_T6_NFR_Test` |

**TR 总数**：5+4+5+4+9+4 = **31 TR**（实跑数字 Implement 后回填）

### 5.3 验收门槛

- P48 own **31/31 TR 0F0E**（实跑回填）
- P22-P48 拆 4 批全量回归：批 4 新增 P48 31 TR；已知基线 fail 沿用（`P28_T6.tr64`）
- **R11 写路径透明性**：阈值未达时 `hook.append` 与裸 `store.append` 逐字节等价（manifest + 返回值）
- **触发正确性**：达 `writesPerTrigger` 且段数 ≥ `minSegmentsToCompact` → 自动 tick 且 `triggersFired` +1；否则不触发
- **合并等价性**：自动触发的合并产物 `SegmentMergeView` 查询与合并前逐条全等
- **0 新增线程**：`autocompact/` 源码 0 处 `new Thread` / `ExecutorService` / `Timer` / `ScheduledExecutorService`（R10）
- **P37 `segment/` 5 + P40 `compact/` 5 + P47 `sched/` 3 + P21 `FulltextStore` 源码字节级 0 改**（R4）

---

## 6. 逆指标（Anti-Metrics，避免的伪优化）

| 逆指标 | 错误做法 | 正确做法 |
|--------|----------|----------|
| 引入后台线程 | 为「自动挂钩」起定时线程 | tick 内联**写者线程**；0 新增 `ExecutorService`（R10） |
| 破坏写路径透明 | 阈值未达也改写 manifest / 改返回值 | 未达阈值时逐字节等价裸 `store.append`（R11） |
| 破坏段不可变 | 装饰器原地改写段 | 只经 P37 `store.append` + P47 runner 产新段 + remove 源段 |
| 崩溃丢数据 | 先 remove 后 append | 继承 P40 `append` 先 / `remove` 后（R9） |
| 破坏既有红线 | 把类塞进 `sched/`（递归 +N 挂 P47 基线） | 新兄弟包 `autocompact/`；`sched/` 3 源文件 0 改 |
| 触发耦合选段 | 阈值影响选哪些段 | 阈值仅决定「是否顺带 tick」，不参与 `selectTiers`（R12） |
| 段格式漂移 | 为自动触发引入 VERSION=2 | 复用 P37 `MAGIC="DIDX"` + `VERSION=1`（R7） |
| 假自动 | 只提供手动 `tickNow()` 不自动 | `append` 内联按策略自动触发（真实「写路径挂钩」） |

---

## 7. 风险与回退

| 风险 | 触发 | 回退 |
|------|------|------|
| `store.list()` 非升序 | 装饰器透传插入序 | P47 调度器内已显式 `Arrays.sort`（R8/R12） |
| 自动触发拖慢写入 | 每次 append 都跑 tick | 双阈值（写入数 + 段数）+ 继承 P47 `cooldownNanos`；未触发态等价裸写 |
| tick 抛异常污染写路径 | `tick()` 内 runner 抛 `IOException` | 继承 P47：异常向上抛由调用方决定；R11 记录该语义（append 已成功落 manifest，合并失败不回滚登记） |
| 段数不足反复跳过计数膨胀 | 写入达阈值但段少 | `triggersSkipped++` 且写计数**保留下轮**（不因跳过清零），下轮段齐即触发 |
| 旧段文件被 mmap 占用无法删（Windows） | runner 内源 reader 未 close | 继承 P40：runner 内 close 全部源 reader 后再 remove |
| 写计数重启归零 | 进程重启后首个 append 计数从 0 | MVP 接受（无持久化，明确出界） |

---

## 8. 交付物清单

- `.trae/specs/p48-oq1-compaction-writehook/spec.md`（本文件）
- `.trae/specs/p48-oq1-compaction-writehook/tasks.md`（Gate 三状态 + 6 阶段 TR 明细）
- `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/autocompact/*.java`（3 新类：`AutoCompactionPolicy` / `AutoCompactionHook` / `AutoCompactionStats`）
- `p2p-db/src/test/java/ds/P48_T1_*.java` ~ `P48_T6_*.java`（6 测试文件 × 31 TR）
- **0 改**：`fulltext/segment/*.java`（P37 5 类）/ `fulltext/compact/*.java`（P40 5 类）/ `fulltext/sched/*.java`（P47 3 类）/ `fulltext/layered/*.java`（P42 3 类）/ `FulltextStore.java`（P21）/ `WeightStore`（P22）/ `p2p-db/pom.xml` / P21~P47 任意源文件

---

## 9. 签批区（Gate A REV1 —— ✅ 用户已批准 2026-10-30）

- [x] **Q0 Scope 确认**：本阶段 = OQ12-㉖ 候选 ③ Compaction 调度与写路径自动挂钩（用户以「继续」确认③）
- [x] **Q1 挂载方式裁决**：**`Q1 = A`**（写路径装饰器 `AutoCompactionHook`，纯 composition，P37 `segment/` + P21 `FulltextStore` 0 改）
- [x] **Q2 新类落包确认**：3 新类落**新兄弟包** `fulltext/autocompact/`（因 `sched/` 递归=3 已锁死）
- [x] 确认「P37 `segment/` 5 + P40 `compact/` 5 + P47 `sched/` 3 + P21 `FulltextStore` 源码 0 改；P48 纯 additive」+「0 新增 Maven dep」
- [x] 确认 MVP 边界：不做后台线程 / 写路径段化本身 / tombstone / 增量 upsert / 段格式 VERSION=2 / 多线程并发合并 / 调度状态持久化

---

## 10. 实装偏差表（Gate C 回填：Spec 目标 vs 实装终态）

| 项 | spec 目标 | 实装终态 | 偏差 | 处置 |
|----|-----------|----------|------|------|
| 新类清单 | 3 类落 `autocompact/` | `AutoCompactionPolicy` / `AutoCompactionHook` / `AutoCompactionStats` 3 类，递归计数 =3 | **0** | ✅ 与 spec 一致 |
| Q1 挂载方式 | A（写路径装饰器，纯 composition） | `AutoCompactionHook.append` 委托 `DsIndexSegmentStore.append` 后内联 `tick()`；P37/P40/P47/P21 源码 0 改 | **0** | ✅ 与 spec 裁决一致 |
| IAE 守卫 | Policy 2 + Hook 3 = 5 | Policy 2（`writesPerTrigger<1` / `minSegmentsToCompact<2`）+ Hook 3（store/schedCfg/policy null）= **5** | **0** | ✅ P48_T6.tr64 实锤 ≥5 |
| 阈值触发 | 写入数 + 段数双阈值 | `writesSinceLastTick >= writesPerTrigger && segCount >= minSegmentsToCompact`；段数不足记 `triggersSkipped` 且**不复位写计数**（下轮段齐即触发，见 §7） | **0** | ✅ 与 spec 一致 |
| 写路径透明性 | 未触发态逐字节等价 | `P48_T3.tr32` 断言 `fingerprint()` + manifest 逐字节 + `store.list()` 全等 | **0** | ✅ R11 实锤 |
| Maven dep | 0 新增 | `p2p-db/pom.xml` 0 改；`autocompact/` 仅 JDK + first-party import | **0** | ✅ R2 实锤 |
| Gate B 批次 | 拆 4 批（批 4 = P31-P48 单命令） | 批 4 拆 3 子段（4a P31-P36 / 4b P37-P42 / 4c P43-P48）以换取可靠性与可观测性 | **轻微（执行编排，非交付物）** | ✅ 全量覆盖 P31-P48，合计 565 run / 0F / 0E，语义等价 |
| T4 合并前视图构造 | 隐含 `store.list()` 构造 pre 视图 | 改为**登记前直读源段文件** `DsIndexSegmentReader.open(store.segmentFile(id))` 构造 pre（`store.list()` 在 append 登记后不含全部将合并源段） | **轻微（测试内部实现，非产品码）** | ✅ 修复后 `P48_T4.tr42` 绿 |
