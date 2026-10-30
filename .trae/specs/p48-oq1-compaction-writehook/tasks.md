# P48 OQ12-㉖③ Compaction 调度与写路径自动挂钩（写路径装饰器 / 阈值触发 / 0 新线程）—— tasks.md（**REV1 Gate A 已批准 2026-10-30；Gate B/C 已闭环，待 Stage 5 交付**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ ✅ 用户已批准 2026-10-30（OQ12-㉖③ + **§0 Q0=③ / Q1=A / Q2=`autocompact/`**）
- [x] **Gate B**（P48 own 31/31 TR 0F0E + P22-P48 拆 4 批 + 已知基线 fail 沿用）→ ✅ 实跑回填完成
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ ✅ 已闭环（9Y 9/9 + 洁癖 5/5）

### 实跑 TR 数（Implement 后实跑回填，**禁止预填**）
- P48 own = **31 TR（5+4+5+4+9+4）** → 实跑 **31/31 0F0E**（`target/p48own.log`）
- Gate B 拆 4 批 = **批 1 76/76 + 批 2 80/80 + 批 3 100 run/1F（已知基线）+ 批 4 565/565（4a 193 + 4b 186 + 4c 186）** 全量回填完成

---

## Stage 1: Gate A（起草中，待签批）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 锚点（P37 `DsIndexSegmentStore.append` 写入口 / P21 `FulltextStore` 不产段 / P42 `LayeredIndexExporter` / P47 `CompactionScheduler` API / 递归文件数红线 / 线程现状） | ✅ |
| 1.2 | 起草 spec.md（§0 待裁决 Q0/Q1/Q2 / Scope / 红线 R1-R12 / 架构 3 类 + 数据流 / 接口契约 / 测试 / 逆指标 / 风险 / 交付物 + 9Y 自检） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细 + Gate B 拆批命令） | ✅ |
| 1.4 | 提交 Gate A VERBATIM 逐条签批（含 §0 Q0/Q1/Q2 裁决） | ✅ 已签批（Q0=③ / Q1=A / Q2=`autocompact/`） |

### Probe 结论（实锤锚点，详见 spec.md §1-§4）

1. **P37 `DsIndexSegmentStore`（`segment/`=5，0 改）**：`append(long segId, DsIndexSegment seg) : DsIndexSegment`（写 manifest，不改段内容；IAE：seg null / 文件缺失 / 重复 append / 头指纹不符）；`segmentFile(segId)` / `list() : long[]`（**manifest 原始插入序，非升序**）/ `openReader` / `load` / `remove` / `close()`（幂等空实现）。**唯一段生产写入口**（段不可变 append-only）。
2. **P21 `FulltextStore`（0 改）**：持有 `layeredView`（P43 `attachLayeredView`/`detachLayeredView`/`hasLayeredView`）；**不持** `DsIndexSegmentStore` / `CompactionScheduler`，**不调** `export`；写方法（`insert`/`ingestDoc`/`putDoc`/`clearDoc`）只写内存 WeightStore，**不产段**。
3. **P42 `LayeredIndexExporter.export(FulltextStore, DsIndexSegmentStore, long segId) : LayeredSnapshot`**：段导出编排（写 `.tmp` + 原子 rename → `store.append`）；目前主要由测试驱动，无生产 wiring。
4. **P47 `CompactionScheduler`（`sched/`=3，0 改）**：`CompactionScheduler(DsIndexSegmentStore store, CompactionSchedConfig config)`（2 IAE）；`int tick() throws IOException`（冷却限速 → `store.list()` 显式升序 → `effectiveFanIn` 自适应 → `selectTiers` → 逐组 plan → `runner.run` → 单 tick 上限，返回本轮合并次数）；`CompactionStats stats()`；`close()` 幂等。
5. **递归文件数红线（关键）**：`P47_T5.y1` 断言 `sched/` 递归 =3；`P40_T5`/`P45_T5`/`P46_T5` 同走**递归** `collectJavaFiles` → 在 `sched/` / `compact/` 下加类会挂既有基线 → P48 3 新类**必须**落**新兄弟包** `autocompact/`（递归 =3）。
6. **线程现状（R10 实锤）**：`fulltext/` 全子树（含 `sched/`）**0 处** `ExecutorService` / `ScheduledExecutor` / `new Thread` / `Timer`（`sched/` 唯一命中为 Javadoc 文案）→ P48 装饰器内联 tick，**0 新增线程**，R10 不破。

---

## Stage 2: Implement T1-T6（串行 T1→T6，实跑 **31/31 TR 0F0E** 待执行）

| T | 范围 | TR 数 | 关键实装 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | `AutoCompactionPolicy` | 5 | 2 IAE（`writesPerTrigger<1` / `minSegmentsToCompact<2`）+ `defaults()`=(4,4) + `shouldTrigger` 四象限（双达标/写入不足/段数不足/边界相等）+ 纯函数确定性 | `P48_T1_PolicyTest` |
| **T2** | `AutoCompactionStats` | 4 | 初值全 0 + 逐项累加 + `resetForTest` 归零 + 计数独立性 | `P48_T2_StatsTest` |
| **T3** | `AutoCompactionHook` 主路径 | 5 | 3 IAE（store/schedCfg/policy null）+ `append` 委托等价裸 `store.append`（R11 未达阈值逐字节等价）+ 达阈值自动 tick（`triggersFired`+1）+ 未达阈值不触发 + `tickNow()` 显式触发 | `P48_T3_HookMainTest` |
| **T4** | E2E | 4 | 真实 `DsIndexSegmentStore`（独立 temp home + 唯一后缀隔离）：多次 `hook.append` 后自动合并 N→1 + `list()` 收敛 + 合并前后 `SegmentMergeView` 查询逐条等价 + `close()` 幂等 | `P48_T4_E2eAutoHookTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 **且八重递归文件数守恒**（segment=5 + parser/v3=5 + agg=6 + compact=5 + groupby=5 + layered=3 + rowsource=2 + sched=3 + autocompact=3）+ Y2 NG-3 + Y3 TOPK + Y4 P37 segment 5 + P40 compact 5 + P47 sched 3 + P21 FulltextStore 源码 0 改 + P21~P47 facade + Y5 路由 0 改 + Y6 P21~P48 + autocompact 3 类 `Class.forName` + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 方法 + Y9 0 TODO/FIXME/HACK | `P48_T5_Gatekeeper9YTest` |
| **T6** | NFR | 4 | `hook.append`（未触发态）均值/P95 ≤ 裸 `store.append` + 阈值（4 段小写）+ 3 类 Javadoc ≥ 10 行 + CPD 0 重复块（160 字符窗口）+ 0 `System.out.println`/`printStackTrace` | `P48_T6_NFR_Test` |

**TR 总数**：5+4+5+4+9+4 = **31 TR**（实跑数字 Implement 后回填）

> **P46/P47 教训·守卫联动（须在 T5 落实）**：向受 Gatekeeper 计数的 `fulltext/` 子树**合法新增兄弟包** `autocompact/`（递归 =3）后，须同步在 P48_T5 新增 `autocompact/`=3 断言；同时确认 `P40_T5`/`P45_T5`/`P46_T5`/`P47_T5` 既有断言**不因新增 `autocompact/` 兄弟包而变动**（它们仅断言各自具名子目录）。属「实装偏差 D1」，仅测试守卫新增、0 改上游主源码。

---

## Stage 3: Gate B（拆 4 批全量回归，待执行）

### 3.1 命令（每批前清理 `target\tmp` + 仓库根 `.tmp` + C 盘残留；改源码/测试后先 `test-compile`）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P25_*,ds.P26_*,ds.P27_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P28_*,ds.P29_*,ds.P30_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*,ds.P42_*,ds.P43_*,ds.P44_*,ds.P45_*,ds.P46_*,ds.P47_*,ds.P48_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

> **每批 Gate B 前追加 C 盘残留清理**（`%LOCALAPPDATA%\Temp` 下 `junit*` / `dsdb_*`；P46 教训：残留 84 GB 致 C: 0 剩余 → `IOException: 磁盘空间不足`）。**长跑日志 `*> gatebN.log` 落盘再读**（P47 教训）。

### 3.2 验收门槛（Implement 后逐项回填实跑数字）

- [x] P48 own **31/31 TR 0F0E**（实跑 `target/p48own.log`：T1 5 / T2 4 / T3 5 / T4 4 / T5 9 / T6 4）
- [x] 批 1 P22-P24 = **76/76 0F0E**（实跑 `target/gateb1.log`，BUILD SUCCESS）
- [x] 批 2 P25-P27 = **80/80 0F0E**（实跑 `target/gateb2.log`，BUILD SUCCESS）
- [x] 批 3 P28-P30 = **100 run / 1F / 0E**（实跑 `target/gateb3.log`，BUILD FAILURE 仅因已知基线 `P28_T6.tr64_noBarePrintAndGte5NullGuards`：裸打印=3，基线既有）
- [x] 批 4 P31-P48 = **565 run / 0F / 0E**（拆 3 子段实跑：4a P31-P36 = 193/193 `target/gateb4a.log`；4b P37-P42 = 186/186 `target/gateb4b.log`；4c P43-P48 = 186/186 `target/gateb4c.log`；均 BUILD SUCCESS，含 P48 own 31 TR；0 flaky）
- [x] R11 写路径透明性（阈值未达时 `hook.append` == 裸 `store.append` 逐字节等价）→ `P48_T3.tr32`（`fingerprint()` + manifest 逐字节 + `store.list()` 全等）
- [x] 触发正确性（双达标自动 tick / 否则不触发）→ `P48_T1.tr13/tr14/tr15` + `P48_T4.tr41/tr43`
- [x] 合并等价性（自动触发合并产物 `SegmentMergeView` 逐条等价）→ `P48_T4.tr42`
- [x] 0 新增线程（`autocompact/` 源码 0 `ExecutorService`/`Thread`/`Timer`）→ `P48_T5.y1/y2` + `P48_T6.tr64`
- [x] P37 `segment/` 5 + P40 `compact/` 5 + P47 `sched/` 3 + P21 `FulltextStore` 源码字节级 0 改 → `P48_T5.y1`（八重递归文件数）+ `P48_T5.y4`（`FulltextStore.java` 源码断言不含 `AutoCompaction`/`autocompact`/`DsIndexSegmentStore`）

---

## Stage 4: Gate C（9Y 自检 + 洁癖 5/5）✅ 已闭环

- [x] ⓪ 9Y 自检 9 项全 `[x]`（spec.md 顶部清单，逐项附实锤测试名 = `P48_T5_Gatekeeper9YTest` 9/9 0F0E）
- [x] ① spec.md 回填 Gate C 9Y（9/9 ✅）+ §10 实装偏差表（0 实质偏差；2 项执行编排/测试内部轻偏差）+ 签批区
- [x] ② tasks.md 回填 Gate 三状态 + 实跑 TR 数字（P48 own 31/31 + Gate B 批 1/2/3/4 实跑）
- [x] ③ Roadmap `docs/dsdb-design-status-and-roadmap.md`：`OQ12-㉖③` ⏳ → ✅ 闭环描述 + 挂出 OQ12-㉗
- [x] ④ Project Memory 追加 P48 交付摘要 + 教训

### 洁癖 5/5（Gate C 硬门槛，实锤）
- [x] ① Gatekeeper 9Y 9/9 0F0E（`P48_T5`）
- [x] ② `autocompact/` 3 类 Javadoc 均 ≥10 行（`P48_T6.tr62`）
- [x] ③ CPD 160 字符窗口 0 复制块（`P48_T6.tr63`，`autocompact` vs `sched`）
- [x] ④ 0 `println`/`printStackTrace` + IAE 守卫 ≥5（`P48_T6.tr64`；实锤：Hook 3 + Policy 2 = 5）
- [x] ⑤ R10 0 新增线程（`autocompact/` 源码 0 处 `new Thread`/`ExecutorService`/`Timer`/`ScheduledExecutorService`；仅 Javadoc 文字提及）

---

## Stage 5: 交付（commit + push，待完成）

- [ ] `git add` P48 源码 3 类 + 测试 6 文件 + spec.md + tasks.md + Roadmap + Memory
- [ ] `git commit`（P48 T1-T6 + Gate C 5/5 closure）
- [ ] `git push origin main`

---

## 红线总表（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫 |
|------|------|------|
| R1 | ZeroLeak3=3 + 八重递归文件数守恒 | 根包=3；segment=5；parser/v3=5；agg=6；compact=5；groupby=5；layered=3；rowsource=2；sched=3；**autocompact=3** |
| R2 | NG-3 零依赖 | pom 0 新增 dep/plugin；0 netty/grpc/p2p.client；仅 JDK + first-party import |
| R3 | TOPK 铁律 | 0 改 K / 0 `SearchOptions` / `bucketAccessCounter` 0 污染 |
| R4 | Facade 单调不减 | P37 `segment/` 5 + P40 `compact/` 5 + P47 `sched/` 3 + P21 `FulltextStore` 字节级 0 改；P21~P47 公开签名 0 改 |
| R5 | HybridHit ctor=2 | 0 改 |
| R6 | P21~P47 字节级兼容 | 全类型 0 改 |
| R7 | 段格式 0 变更 | 复用 `MAGIC="DIDX"` + `VERSION=1`；产物合法 P37 段 |
| R8 | 覆盖语义确定性（新段优先） | 源段新旧序由 P47 调度器按 segId 升序显式给定 |
| R9 | 崩溃安全 | 继承 P40：`append(merged)` 先于 `remove(sources)` |
| R10 | **0 新增线程 / 0 `ExecutorService`** | 装饰器 `append` 内联 tick（写者线程）；严禁 `new Thread` / `ExecutorService` / `Timer` |
| R11 | 写路径透明性 | 阈值未达时 `hook.append` 与裸 `store.append` 逐字节等价 |
| R12 | 触发确定性 | 同写入序列 + 同状态 + 同 policy → 同触发；阈值不参与 `selectTiers` |
