# P40 OQ1 BM25 Index Segment 增量 compaction / tiered-LSM 段合并 —— tasks.md（**REV1 GATE C 闭环**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ 用户 VERBATIM 批准（OQ12-⑱①）
- [x] **Gate B**（P40 own 31/31 TR 0F0E + P22-P40 拆 4 批 + 已知基线 fail 沿用）
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）

### 实跑 TR 数（Implement 后实跑回填）
- P40 own = **31 TR（5+6+3+3+9+5）实跑 31/31 0F0E**
- Gate B 拆 4 批 = 批1 76/76 0F0E、批2 80/80 0F0E、批3 100 run/1 fail（已知基线 `P28_T6.tr64`）/0 error、批4 317/317 0F0E（含 P40 31 TR）

---

## Stage 1: Gate A（起草完成，待签批）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 锚点（P37 `DsIndexSegment`/`DsIndexSegmentStore`/`Writer`/`Reader`/`SegmentColumnCodec` API / 三重递归文件数红线 / `WeightDocStatRow`·`WeightTermStatRow` 可复用 / manifest 语义 / `DailyMergeService` 未接入） | ✅ |
| 1.2 | 起草 spec.md（Scope/红线 R1-R10/架构 5 类 + 数据流/接口契约/测试/逆指标/风险/Probe 锚点 + 9Y 自检） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细 + Gate B 拆批命令） | ✅ |
| 1.4 | NotifyUser 提交 Gate A 批准 | ✅ |

### Probe 结论（实锤锚点，详见 spec.md §3-§4）
1. **P37 `DsIndexSegment`**：`MAGIC={'D','I','D','X'}` / `VERSION=1` / `HEADER_SIZE=4096` / `CRC_SECTIONS=4` + 12 个 off/len 字段 + `fingerprint()` 齐备 → compaction 产物直接构造合法段描述，P37 0 改。
2. **P37 `DsIndexSegmentStore`**：`dir()` / `segmentFile(segId)` / `append(segId,seg)` / `load(segId)` / `openReader(segId)` / `list()` / `remove(segId)` / `close()`；manifest = 纯文本一行一 segId（**插入序，非排序** → R8 要求调用方显式给定新旧序）；**无任何 compaction/merge/段上限/回收逻辑（无 TODO）**。
3. **P37 `DsIndexSegmentWriter`**：`ctor(File)` / `addDocStat` / `addTermStat` / `addTf` / `finish()`（幂等，二次 IAE）；`.tmp` + `Files.move` 原子落盘 → `SegmentCompactor` 复用它产新段，0 改。
4. **P37 `DsIndexSegmentReader`**：`open(File)`（mmap + magic/version + 4×CRC32 校验）/ `getDocStat` / `getTermStat`（按需解压 payload）/ `getTf`（miss=0）/ `scanTfPrefix` / `close`（双通道 unmapper）→ merge view 直接持有 N 个 reader。
5. **P37 `SegmentColumnCodec`**：`DOC_STAT_REC=56` / `TERM_STAT_REC=24` / `TF_REC=12` / `MIN_PAGE=4096` / `binarySearchKey` / `checkStrictAscending` → 归并器只需遍历 + 覆盖，无需触碰编码细节。
6. **三重递归文件数红线（关键新发现）**：`P37_T5.Y2`（`P37_T5_Gatekeeper9YTest.java:79`，segment=5）、`P38_T5.Y2`（`P38_T5_Gatekeeper9YTest.java:85`，parser/v3=5）、`P39_T5.Y2`（`P39_T5_Gatekeeper9YTest.java:83,102`，parser/v3=5 + agg=6）三处均用**递归** `collectJavaFiles` → `segment/`、`parser/v3/`、`cluster/agg/` 三棵子树均不可再放 `.java` → P40 5 新类**必须**落**新包** `compact/`。
7. **可复用行为对象**：`FulltextStore.WeightDocStatRow`（public `docLen[6]`/`nTerms[6]`）+ `WeightTermStatRow(idf, payload)` 为 P37 reader 的返回类型 → merge view/归并器 0 改即可承载。

---

## Stage 2: Implement T1-T6（串行 T1→T6，实跑 **31/31 TR 0F0E**）

| T | 范围 | TR 数 | 关键实装 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | `SegmentMergeView` skeleton | 5 | 3 IAE（readers null/空/含 null）+ 覆盖语义（新段优先，含同键冲突：新段命中即返回）+ `segmentCount` + `fingerprint()`（SHA-256 64-hex，随段序变化）+ `close` 幂等 | `P40_T1_MergeViewSkeletonTest` **5/5 0F0E** |
| **T2** | `SegmentCompactor` 归并 | 6 | 2 段归并 → 1 段：docStat/termStat/tf 逐条 vs 手工「新段优先」真值全等（idf bit 级）+ 产物 `DsIndexSegmentReader.open` 直接可读（头/4×CRC 全过）+ 单段归并恒等 + 空段边界（0 记录段）+ 4 IAE（target null / sources null·空 / 含 null / 越界脏数据） | `P40_T2_CompactorMergeTest` **6/6 0F0E** |
| **T3** | `SegmentCompactionPolicy` tiered | 3 | tier 分层（`floor(log_4(sizeBytes))`）+ 层内段数 ≥ `fanIn` → 取该层最早 `maxFanIn` 个（segId 升序）/ 未达不触发 + 确定性（同输入同输出）+ 4 IAE（长度不等 / `fanIn<2` / `maxFanIn<fanIn` / segIds 非严格升序） | `P40_T3_CompactionPolicyTest` **3/3 0F0E** |
| **T4** | E2E + 跨进程 fingerprint | 3 | 真实 `DsIndexSegmentStore`（独立 temp home + 唯一后缀隔离，继承 P31/P34/P39 模板）：写 3 段 → `selectTiers` → `plan` → `runner.run` → 合并后 `list()` = 1 段 + 合并前后 `SegmentMergeView` 查询逐条等价 + 崩溃安全注入（append 后 remove 前中断 → 两段共存仍等价）+ 跨进程 `plan.fingerprint()` 一致（子 JVM spawn 复现 64-hex） | `P40_T4_E2eCompactionTest` **3/3 0F0E** |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 **且四重递归文件数守恒**（segment=5 + parser/v3=5 + agg=6 + compact=5；根包 top-level=3）+ Y2 NG-3（compact 目录 forbidden 引用 + import 白名单 + pom banned 0 命中）+ Y3 TOPK（0 `bucketAccessCounter` / 0 `SearchOptions`）+ Y4 Facade 单调不减（**P37 segment 5 类源码 0 改 grep** + P21~P39 探针签名）+ Y5 路由 0 改 + Y6 P21~P40 + compact 5 类 `Class.forName` + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 方法 + Y9 0 TODO/FIXME/HACK | `P40_T5_Gatekeeper9YTest` **9/9 0F0E** |
| **T6** | NFR | 5 | merge view 查询 P95 ≤ 阈值（3 段 × 1k 键）+ compact 3 段 P95 ≤ 阈值 + 5 类 Javadoc ≥ 10 行 + CPD 0 重复块（160 字符窗口）+ 0 `System.out.println`/`printStackTrace` + IAE 显式守卫 ≥ 8 | `P40_T6_NFR_Test` **5/5 0F0E** |

**TR 总数（实跑）**：5+6+3+3+9+5 = **31 TR（31/31 0F0E）**

---

## Stage 3: Gate B（拆 4 批全量回归，实跑完成）

### 3.1 命令（每批前清理 `target\tmp` + 仓库根 `.tmp`；改源码/测试后先 `test-compile`）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P25_*,ds.P26_*,ds.P27_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P28_*,ds.P29_*,ds.P30_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

### 3.2 验收门槛（Implement 后逐项回填实跑数字）

- [x] P40 own 31/31 TR 0F0E
- [x] 批 1 P22-P24 = **76/76 0F0E**（Total time 16:48 min）
- [x] 批 2 P25-P27 = **80/80 0F0E**（Total time 54:41 min）
- [x] 批 3 P28-P30 = **100 run / 1 fail / 0 error**（已知基线 fail 沿用 `P28_T6.tr64_noBarePrintAndGte5NullGuards`；Total time 1:42 h）
- [x] 批 4 P31-P40 = **317/317 0F0E**（含 P40 own 31 TR；Total time 43:44 min）
- [x] 合并等价性（归并产物 merge view 单段 == 合并前 N 段 merge view 逐条全等）→ `P40_T4.tr41_e2ePolicyPlanRunner`
- [x] 崩溃安全（append 后 remove 前中断 → 查询仍等价）→ `P40_T4.tr42_crashSafeAppendBeforeRemove`
- [x] 段格式 0 变更（产物 `MAGIC`/`VERSION`/4×CRC32 全合法）→ `P40_T2` / `P40_T4`
- [x] `SegmentCompactionPlan.fingerprint()` 跨进程恒定 → `P40_T4.tr43_crossProcessPlanFingerprint`
- [x] P37 `segment/` 5 源文件字节级 0 改 → `P40_T5.y4_FacadeMonotonic_P37SegmentAndPriorIntact`

---

## Stage 4: Gate C（9Y 自检 + 洁癖 5/5，已闭环）

- [x] ⓪ 9Y 自检 9 项全 `[x]`（spec.md 顶部清单，逐项附实锤测试名）
- [x] ① spec.md 回填 Gate C 9Y + §10 实装偏差表 + 签批区
- [x] ② tasks.md 回填 Gate 三状态 + 实跑 TR 数字
- [x] ③ Roadmap `docs/dsdb-design-status-and-roadmap.md`：`OQ12-⑱` ⏳ → ✅ 闭环描述 + 挂出 `OQ12-⑲`
- [x] ④ Project Memory 追加 P40 交付摘要 + 教训

---

## Stage 5: 交付（commit + push，待完成）

- [ ] `git add` P40 源码 5 类 + 测试 6 文件 + spec.md + tasks.md + Roadmap + Memory
- [ ] `git commit`（P40 T1-T6 + Gate C 5/5 closure）
- [ ] `git push origin main`

---

## 红线总表（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫 |
|------|------|------|
| R1 | ZeroLeak3=3 + 四重文件数守恒（递归） | 根包=3；segment=5；parser/v3=5；agg=6；compact=5 |
| R2 | NG-3 零依赖 | pom 0 新增 dep/plugin；0 netty/grpc/p2p.client；仅 JDK + first-party import |
| R3 | TOPK 铁律 | 0 改 K / 0 `SearchOptions` / `bucketAccessCounter` 0 污染 |
| R4 | Facade 单调不减 | P37 `segment/` 5 类字节级 0 改；P21~P39 公开签名 0 改 |
| R5 | HybridHit ctor=2 | 0 改 |
| R6 | P21~P39 字节级兼容 | 全类型 0 改 |
| R7 | 段格式 0 变更 | 复用 `MAGIC="DIDX"` + `VERSION=1`；产物合法 P37 段 |
| R8 | 覆盖语义确定性（新段优先） | 顺序由调用方显式给定，不依赖 manifest 行序 |
| R9 | 崩溃安全 | `append(merged)` 先于 `remove(sources)` |
| R10 | 计划确定性 | `plan.fingerprint()` 仅覆盖（源 segId 升序集 + tier + mergedSegId） |
