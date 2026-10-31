# P49 OQ12-㉗③ 写路径段化自动挂钩（段导出装饰器 / 阈值触发 / 0 新线程）—— tasks.md（**REV1 Gate A 已签批**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ 已批准（OQ12-㉗③ + **§0 Q0=③ / Q1=A / Q2=`autoseg/` / Q3=双阈值**）
- [x] **Gate B**（P49 own 31/31 TR 0F0E + P22-P49 拆批 + 已知基线 fail 沿用）→ **已完成**（own 31/31 0F0E；拆批 6 批合计 877 TR，仅 1 例已知基线 fail `P28_T6.tr64`）
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ **已完成**（`P49_T5_Gatekeeper9YTest` 9/9 0F0E + 洁癖 5/5 全闭环）

### 实跑 TR 数（Implement 后实跑回填，**禁止预填**）
- P49 own = **31 TR（5+4+5+4+9+4）= 31/31 0F0E**（`target/../p49own.log`：T1 5 / T2 4 / T3 5 / T4 4 / T5 9 / T6 4）
- Gate B 拆批实跑（6 批，合计 **877 TR**，1 已知基线 fail）：
  - 批 1 P22-P24 = **101 TR 0F0E**（`gateb1.log`，17:23 min）
  - 批 2 P25-P28 = **112 TR，1 failure = 已知基线 `P28_T6.tr64`（裸打印=3）**（`gateb2.log`，01:14 h）
  - 批 3 P29-P30 = **68 TR 0F0E**（`gateb3.log`，01:14 h）
  - 批 4a P31-P36 = **193 TR 0F0E**（`gateb4a.log`，42:52 min）
  - 批 4b P37-P42 = **186 TR 0F0E**（`gateb4b.log`）
  - 批 4c P43-P49 = **217 TR 0F0E**（`gateb4c.log`，08:54 min；含 P49 own 31 TR）

---

## Stage 1: Gate A（起草中，待签批）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 锚点（P21 `FulltextStore` 写方法 `insert`/`ingestDoc`/`putDoc` / P42 `LayeredIndexExporter.export` / P37 `DsIndexSegmentStore` / P47 `CompactionScheduler`/`CompactionSchedConfig` / P48 `AutoCompactionHook` / 递归文件数红线 / 线程现状） | ✅ |
| 1.2 | 起草 spec.md（§0 待裁决 Q0/Q1/Q2/Q3 / Scope / 红线 R1-R13 / 架构 3 类 + 数据流 / 接口契约 / 测试 / 逆指标 / 风险 / 交付物 + 9Y 自检） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细 + Gate B 拆批命令） | ✅ |
| 1.4 | 提交 Gate A VERBATIM 逐条签批（含 §0 Q0/Q1/Q2/Q3 裁决） | ⏳ 待签批 |

### Probe 结论（实锤锚点，详见 spec.md §1-§4）

1. **P21 `FulltextStore`（`fulltext/` 根包，`implements AutoCloseable`，0 改）**：写方法 `insert(TuRow) : void`（L866）/ `ingestDoc(long docId, String text) : IngestStat`（L1277）/ `putDoc(long docId, String newText) : IngestStat`（L1766）/ `clearDoc(long)`（L1525）——**均只写内存 WeightStore，不产段**；ctor `FulltextStore(String rootDir) throws IOException`；`IngestStat` 为 `public static final class IngestStat`（L795）；`TuRow` = `TermDictStore.TuRow`（import L32）。**P42 追加的只读枚举**：`weightStoreAllDocKeysForTest()` / `weightStoreAllTermKeys()` / `weightStoreAllTfEntries()`（L1775/1784/1793）。**注意**：`FulltextStore` 自身 import `java.util.concurrent.*`（既有 P21 行为，非 P49 引入）。
2. **P42 `LayeredIndexExporter.export(FulltextStore, DsIndexSegmentStore, long segId) : LayeredSnapshot`（`layered/`=3，0 改）**：**自身完成**「`writer.finish()`（`.tmp` + 原子 rename）→ `store.append(segId, seg)` 登记」（L106~107）；4 IAE（ft null / store null / segId<0 / segId 已注册）。
3. **P37 `DsIndexSegmentStore`（`segment/`=5，0 改）**：`append(long, DsIndexSegment) : DsIndexSegment`（写 manifest；IAE：seg null / 文件缺失 / 重复 append / 头指纹不符）；`segmentFile(long)` / `list() : long[]`（**manifest 原始插入序，非升序**）/ `load` / `openReader` / `remove` / `close()`（幂等空实现）。
4. **P47 `CompactionScheduler(DsIndexSegmentStore store, CompactionSchedConfig config)`（`sched/`=3，0 改）**：`int tick() throws IOException`；`CompactionStats stats()`；`close()` 幂等；`CompactionSchedConfig` 5 IAE + `defaults()`=(4,4,1,0L,Integer.MAX_VALUE) + `effectiveFanIn(int)`。
5. **P48 `AutoCompactionHook(DsIndexSegmentStore, CompactionSchedConfig, AutoCompactionPolicy)`（`autocompact/`=3，0 改）**：`append(long, DsIndexSegment) : DsIndexSegment`（**自身 `store.append` 登记**）；`tickNow()`；`stats()`；`autoStats()`；`close()`。**关键实锤**：`AutoCompactionHook.append` 与 `LayeredIndexExporter.export` **均登记段** → 二者不可对同一 segId 连用（会触发 P37 `duplicate append` IAE）→ 决定 §0 Q1 选项。
6. **递归文件数红线（关键）**：`P40_T5`/`P45_T5`/`P46_T5`/`P47_T5`/`P48_T5` 的文件数断言使用**递归** `collectJavaFiles`；`P47_T5.y1` 断言 `sched/` 递归=3、`P48_T5.y1` 断言 `autocompact/` 递归=3 —— P49 3 新类**必须**落**新兄弟包** `autoseg/`（递归 =3）。
7. **线程现状（R10 实锤）**：`fulltext/` 全子树（含 `sched/`、`autocompact/`）**0 处** `ExecutorService` / `ScheduledExecutor` / `new Thread` / `Timer`（仅 Javadoc 文案命中）→ P49 装饰器内联 `export` + `tick()`，**0 新增线程**，R10 不破。

---

## Stage 2: Implement T1-T6（串行 T1→T6，目标 **31/31 TR 0F0E**，待实跑）

| T | 范围 | TR 数 | 关键实装 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | `AutoSegmentPolicy` | 5 | 2 IAE（`writesPerFlush<1` / `minSegmentsToCompact<2`）+ `defaults()`=(4,4) + `shouldFlush` 边界（<、=、>）+ `shouldCompact` 边界 + 纯函数确定性 | `P49_T1_PolicyTest` |
| **T2** | `AutoSegmentStats` | 4 | 初值全 0 + 逐项累加 + `resetForTest` 归零 + 计数独立性 | `P49_T2_StatsTest` |
| **T3** | `AutoSegmentHook` 主路径 | 5 | 4 IAE（ft/store/schedCfg/policy null）+ 未达阈值 `putDoc` 等价裸 `ft.putDoc`（R13：返回值 + WeightStore + `store.list()` 全等）+ 达阈值自动 flush（`list()`+1 & `flushesFired`+1）+ 段数达阈值自动 tick（`compactionsFired`+1）+ `flushNow()` 显式 | `P49_T3_HookMainTest` |
| **T4** | E2E | 4 | 真实 `FulltextStore` + `DsIndexSegmentStore`（独立 temp home + 唯一后缀隔离）：多次 `putDoc` 后自动产段 + 段数达阈值自动 compaction 收敛 `list()` + 产段后 `LayeredInvertedView`/`SegmentMergeView` 查询与内存 WeightStore 逐条等价 + `close()` 幂等 | `P49_T4_E2eAutoSegmentTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 **且九重递归文件数守恒**（segment=5 + parser/v3=5 + agg=6 + compact=5 + groupby=5 + layered=3 + rowsource=2 + sched=3 + autocompact=3 + autoseg=3）+ Y2 NG-3 + Y3 TOPK + Y4 P21~P48 源码 0 改（含 `FulltextStore.java` 不含 `AutoSegment`/`autoseg`）+ Y5 路由 0 改 + Y6 P21~P49 + autoseg 3 类 `Class.forName` + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 方法 + Y9 0 TODO/FIXME/HACK | `P49_T5_Gatekeeper9YTest` |
| **T6** | NFR | 4 | `putDoc`（未触发态）均值/P95 ≤ 裸 `ft.putDoc` + SLACK + 3 类 Javadoc ≥ 10 行 + CPD 0 重复块（160 字符窗口）+ 0 `System.out.println`/`printStackTrace` + IAE ≥ 4 | `P49_T6_NFR_Test` |

**TR 总数**：5+4+5+4+9+4 = **31 TR**（实跑数字 Implement 后回填）

> **P46/P47/P48 教训·守卫联动（须在 T5 落实）**：向受 Gatekeeper 计数的 `fulltext/` 子树**合法新增兄弟包** `autoseg/`（递归 =3）后，须同步在 P49_T5 新增 `autoseg/`=3 断言；同时确认 `P40_T5`/`P45_T5`/`P46_T5`/`P47_T5`/`P48_T5` 既有断言**不因新增 `autoseg/` 兄弟包而变动**。属「实装偏差 D1」，仅测试守卫新增、0 改上游主源码。

---

## Stage 3: Gate B（拆批全量回归，待执行）

### 3.1 命令（每批前清理 `target\tmp` + 仓库根 `.tmp` + C 盘残留；改源码/测试后先 `test-compile`）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P25_*,ds.P26_*,ds.P27_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P28_*,ds.P29_*,ds.P30_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*,ds.P42_*,ds.P43_*,ds.P44_*,ds.P45_*,ds.P46_*,ds.P47_*,ds.P48_*,ds.P49_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

> **每批 Gate B 前追加 C 盘残留清理**（`%LOCALAPPDATA%\Temp` 下 `junit*` / `dsdb_*`；P46 教训：残留 84 GB 致 C: 0 剩余 → `IOException: 磁盘空间不足`）。**长跑日志 `*> gatebN.log` 落盘再读**（P47 教训）。P48 教训：末批（P31-P49）单命令过长时**拆子段**（如 4a P31-P36 / 4b P37-P42 / 4c P43-P49）以抗环境干扰与超时。

### 3.2 验收门槛（Implement 后逐项回填实跑数字）
- [x] P49 own **31/31 TR 0F0E**（实跑 `p49own.log`：T1 5 / T2 4 / T3 5 / T4 4 / T5 9 / T6 4）
- [x] 批 1 P22-P24 = **101 TR 0F0E**
- [x] 批 2 P25-P28 = **112 TR，1 failure = 已知基线 `P28_T6.tr64`（裸打印=3，沿用不阻塞）**
- [x] 批 3 P29-P30 = **68 TR 0F0E**
- [x] 末批 P31-P49 拆 3 子段 = 4a **193** / 4b **186** / 4c **217**（均 0F0E，含 P49 own 31 TR）
- [x] R13 写路径透明性（未达阈值 `hook.putDoc` == 裸 `ft.putDoc`：返回值 + WeightStore + `store.list()` 全等）→ `P49_T3.tr33`
- [x] 触发正确性（达阈值自动 flush / 段数达阈值自动 tick / 否则不触发）→ `P49_T1.tr13/tr14` + `P49_T4.tr41/tr43`
- [x] 合并等价性（自动导出/合并产物 `SegmentMergeView` 逐条等价）→ `P49_T4.tr42`
- [x] 0 新增线程（`autoseg/` 源码 0 `ExecutorService`/`Thread`/`Timer`）→ `P49_T5.y1/y2` + `P49_T6.tr64`
- [x] P21 `FulltextStore` + P37 `segment/` 5 + P40 `compact/` 5 + P42 `layered/` 3 + P47 `sched/` 3 + P48 `autocompact/` 3 源码字节级 0 改 → `P49_T5.y1`（九重递归文件数）+ `P49_T5.y4`（`FulltextStore.java` 源码断言不含 `AutoSegment`/`autoseg`）

---

## Stage 4: Gate C（9Y 自检 + 洁癖 5/5）已完成

- [x] ⓪ 9Y 自检 9 项全 `[x]`（spec.md 顶部清单，逐项附实锤测试名 = `P49_T5_Gatekeeper9YTest` 9/9 0F0E）
- [x] ① spec.md 回填 Gate C 9Y（9/9 ✅）+ §10 实装偏差表 + 签批区
- [x] ② tasks.md 回填 Gate 三状态 + 实跑 TR 数字
- [x] ③ Roadmap `docs/dsdb-design-status-and-roadmap.md`：`OQ12-㉗③` ⏳ → ✅ 闭环描述 + 挂出 OQ12-㉘
- [x] ④ Project Memory 追加 P49 交付摘要 + 教训

### 洁癖 5/5（Gate C 硬门槛，实锤）
- [x] ① Gatekeeper 9Y 9/9 0F0E（`P49_T5`）
- [x] ② `autoseg/` 3 类 Javadoc 均 ≥10 行（`P49_T6.tr62`）
- [x] ③ CPD 160 字符窗口 0 复制块（`P49_T6.tr63`，`autoseg` vs `sched`/`autocompact`）
- [x] ④ 0 `println`/`printStackTrace` + IAE 守卫 ≥5（`P49_T6.tr64`；实锤：Hook 4 + Policy 2 = 6）
- [x] ⑤ R10 0 新增线程（`autoseg/` 源码 0 处 `new Thread`/`ExecutorService`/`Timer`/`ScheduledExecutorService`；仅 Javadoc 文字提及）

---

## Stage 5: 交付（commit + push）待完成

- [ ] `git add` P49 源码 3 类 + 测试 6 文件 + spec.md + tasks.md + Roadmap（**仅 P49 相关路径，禁用 `git add -A`**）
- [ ] `git commit -F`（P49 T1-T6 + Gate C 5/5 closure）
- [ ] `git push origin main`
- [ ] Project Memory 追加 P49 交付摘要 + 教训（仓库外路径，不入 git）

---

## 红线总表（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫 |
|------|------|------|
| R1 | ZeroLeak3=3 + 九重递归文件数守恒 | 根包=3；segment=5；parser/v3=5；agg=6；compact=5；groupby=5；layered=3；rowsource=2；sched=3；autocompact=3；**autoseg=3** |
| R2 | NG-3 零依赖 | pom 0 新增 dep/plugin；0 netty/grpc/p2p.client；仅 JDK + first-party import |
| R3 | TOPK 铁律 | 0 改 K / 0 `SearchOptions` / `bucketAccessCounter` 0 污染 |
| R4 | Facade 单调不减 | P21 `FulltextStore` + P37 `segment/` 5 + P40 `compact/` 5 + P42 `layered/` 3 + P47 `sched/` 3 + P48 `autocompact/` 3 字节级 0 改；P21~P48 公开签名 0 改 |
| R5 | HybridHit ctor=2 | 0 改 |
| R6 | P21~P48 字节级兼容 | 全类型 0 改 |
| R7 | 段格式 0 变更 | 复用 `MAGIC="DIDX"` + `VERSION=1`；flush 产物经 P42 exporter 合法 |
| R8 | 覆盖语义确定性（新段优先） | 源段新旧序由 P47 调度器按 segId 升序显式给定 |
| R9 | 崩溃安全 | 继承 P40/P42：`finish/append` 先 / `remove` 后 |
| R10 | **0 新增线程 / 0 `ExecutorService`** | 装饰器写方法内联 `export` + `tick()`（写者线程）；严禁 `new Thread` / `ExecutorService` / `Timer` |
| R11 | 段 id 分配确定性 | `segId = max(store.list())+1`；不与既存/在飞冲突 |
| R12 | 合并触发确定性 | 阈值仅决定「是否顺带 flush/tick」，不参与 `selectTiers` |
| R13 | 写路径透明性 | 未达 flush 阈值时与裸 `ft.putDoc/ingestDoc/insert` 全等，不产段 |
