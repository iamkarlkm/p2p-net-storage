# P43 OQ1 Fulltext 搜索读路径切换（Slice B：`FulltextStore.search` 改走 `LayeredInvertedView`）—— tasks.md（**REV1 GATE A 已批准**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ 用户 VERBATIM 批准（OQ12-㉑①，采纳 §0 方案 A）
- [x] **Gate B**（P43 own 31/31 TR 0F0E + P22-P43 拆 4 批：实跑 76+80+100+410 = **666 run**，仅 1 已知基线 fail）
- [x] **Gate C**（9Y 自检 9/9 + 洁癖 5/5）

### 实跑 TR 数（Implement 后实跑回填）
- P43 own = **31 TR（5+6+3+3+9+5）→ 实跑 31 run, 0F0E** ✅
  - T1 `P43_T1_AttachSkeletonTest` = 5、T2 `P43_T2_ReadpathEquivalenceTest` = 6、T3 `P43_T3_FallbackSemanticsTest` = 3
  - T4 `P43_T4_E2eReadpathSwitchTest` = 3、T5 `P43_T5_Gatekeeper9YTest` = 9、T6 `P43_T6_NFR_Test` = 5
- Gate B 拆 4 批 = 批1 **76** 0F0E / 批2 **80** 0F0E / 批3 **100**（1F 已知基线，0E）/ 批4 **410** 0F0E

---

## Stage 1: Gate A（起草完成，待签批）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 锚点（`searchCore` 6 处 stat 点查 / 候选召回 IDX1-3 / `computeAvgDocLen` / `neighborsWithinD` / `computePhraseBoost` / `LayeredInvertedView` 视图面 / `mix4` 不保高位 / WeightStore 持久性 / 六重递归文件数红线 / Roadmap OQ12-㉑①） | ✅ |
| 1.2 | 起草 spec.md（§0 需用户裁决 + Scope/MVP 边界 + 红线 R1-R10 + 架构 0 新文件 + 数据流 + 接口契约 + 测试 31 TR + 逆指标 + 风险回退 + Probe 锚点 + 9Y 自检全 `[ ]`） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + Stage 1-5 + 六阶段 TR 明细 + Gate B 拆批命令 + 红线总表） | ✅ |
| 1.4 | 提交 Gate A 批准 | ✅（用户 VERBATIM 批准，采纳方案 A） |

### Probe 结论（实锤锚点，详见 spec.md §11）
1. **核心张力（本 spec 的 §0 根因）**：P42 段只含 **3 列**（`docStat`/`termStat`/`tf`），**不含 IDX 结构**；`searchCore` 候选召回 **100% 依赖 IDX1/2/3**（`selectByUnit`/`selectByParentLevel`/`selectByDocLevel`），且 tf 键 `mix4(uid,docId,parentId,level)` 四元全混合（**不保 uid 高位**）→ `scanTfPrefix` 无法按 uid 反查 → **全段候选召回必须新增段列（触 R5）** ⇒ Slice B 只能「IDX 召回 + 段 stat」混合（§0 方案 A）。
2. **stat 点查面（6 处）**：`FulltextStore.java` `searchCore`（L2099-2258）内 L2105 `getTermStat` 存在性 / L2115 `getTermStat` idf / L2142 `getTf`（R1）/ L2172 `getDocStat` docLen（sortScore）/ L2180 `getTf`（sortScore）/ L2248 `getTf`（unitIdHit）。
3. **0 改面**：`selectByUnit`(L913)/`selectByParentLevel`(L898)/`selectByDocLevel`(L919)/`selectByUnitHit`(L2475)/`neighborsWithinD`(L1922)/`computePhraseBoost`(L1891)/`computeAvgDocLen`(L1961)/`recomputeIdfForTerms`(L1998)/`searchWithVectorCore`(L2260) 全部保持。
4. **`LayeredInvertedView` 可 0 改直接复用**：`docLen`(miss 0)/`idf`(miss 0.0)/`tf`(miss 0)/`hasDoc`/`hasTerm`/`close` 幂等（L85-151）。
5. **回退判定可行性**：P22 `updateWeightForLevel` tf = 词频 count ≥ 1 ⇒ `tf==0 ⟺ miss`；`putDocStat` 全 6 level 同批写入 ⇒ `hasDoc(docId)` 命中即 6 level 全有效。
6. **WeightStore 为 P21 内嵌持久类**（L251-272，3×`DsHashMap`+`DsFixedBucketStore`）→ 段视图提供的是**并行/替代读面**（更省内存 + 可 compaction + 可分发），而非「冷启动重建」替代。
7. **六重递归文件数红线 + ZeroLeak3=3**：`segment/`=5 / `parser/v3/`=5 / `agg/`=6 / `compact/`=5 / `groupby/`=5 / `layered/`=3 六棵子树 + 根包=3 全部钉死 → **P43 新增源文件数必须 = 0**。

---

## Stage 2: Implement T1-T6（串行 T1→T6，目标 **31/31 TR 0F0E**）

| T | 范围 | TR 数 | 关键实装 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | 挂载骨架 | 5 | `attach(null)` IAE + 初始 `hasLayeredView()==false` + attach/detach 状态切换 + `detach` 幂等 + 重复 attach IAE | `P43_T1_AttachSkeletonTest` |
| **T2** | 切换 bit 级等价性 | 6 | 忠实快照视图 attach 后 vs 未 attach 逐字段全等（docId 序 + 4 分数 `doubleToLongBits` + `r1MatchedTerms` + `unitIdHit`）：单 term / 多 term / `searchByUidsForTest` / phraseBoost+neighborRecall / 无命中双空 / `idfOverrideByUnitId` | `P43_T2_ReadpathEquivalenceTest` |
| **T3** | 回退语义 | 3 | 陈旧视图（export 后再 ingest）新旧混合 == 无视图 + 视图缺 term/doc 回退 WeightStore + detach 后 == 从未 attach | `P43_T3_FallbackSemanticsTest` |
| **T4** | E2E + 跨实例/进程 | 3 | 真实 FT ingest→export→attach→search 等价（+detach 后仍等价）+ 跨实例（新 FT + `open` + attach）全等 + 跨进程 fingerprint 64-hex 一致 | `P43_T4_E2eReadpathSwitchTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 + 六重递归文件数 + **Δ0 新文件** + Y2 NG-3 + Y3 TOPK + Y4 Facade 单调不减（P37/P40/P41/P42 目录 0 改 + P21 新旧签名并存）+ Y5 路由 + Y6 P21~P43 编译兼容 + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 + Y9 0 TODO | `P43_T5_Gatekeeper9YTest` |
| **T6** | NFR | 5 | attach 后 search P95 ≤ 阈值（基线 × ≤ +5% 或绝对阈值，Gate B 定档）+ attach/detach O(1) + Javadoc ≥ 10 行 + CPD 160 字符窗口 0 重复块 + 0 `println`/`printStackTrace` + 新增 IAE ≥ 5 | `P43_T6_NFR_Test` |

**TR 总数（目标）**：5+6+3+3+9+5 = **31 TR**

---

## Stage 3: Gate B（拆 4 批全量回归，待实跑）

### 3.1 命令（每批前清理 `target\tmp` + 仓库根 `.tmp` + `surefire-reports`；改源码/测试后先 `test-compile`）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P25_*,ds.P26_*,ds.P27_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P28_*,ds.P29_*,ds.P30_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*,ds.P42_*,ds.P43_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

### 3.2 验收门槛（Implement 后逐项回填实跑数字）

- [x] P43 own **31/31 TR 0F0E**（T1 5/5 + T2 6/6 + T3 3/3 + T4 3/3 + T5 9/9 + T6 5/5）→ 实跑 31 run 0F0E
- [x] 批 1 P22-P24 = **76** 0F0E
- [x] 批 2 P25-P27 = **80** 0F0E
- [x] 批 3 P28-P30 = **100**（1F 已知基线 `P28_T6.tr64_noBarePrintAndGte5NullGuards`，0E）
- [x] 批 4 P31-P43 = **410** 0F0E（含 P43 own 31 TR）
- [x] 切换等价性（attach 忠实快照后 bit 级全等）
- [x] 回退语义（陈旧视图 / 视图缺项 / detach 后均全等）
- [x] 段格式 0 变更（`MAGIC`/`VERSION`/4×CRC32 全合法）
- [x] 六重递归文件数守恒 + P43 Δ0 新增源文件
- [x] P37 `segment/` + P40 `compact/` + P41 `groupby/` + P42 `layered/` 源码字节级 0 改

---

## Stage 4: Gate C（9Y 自检 + 洁癖 5/5，待执行）

- [ ] ⓪ 9Y 自检 9 项全 `[x]`（spec.md 顶部清单，逐项附实锤测试名）
- [ ] ① spec.md 回填 Gate C 9Y + §10 实装偏差表 + 签批区
- [ ] ② tasks.md 回填 Gate 三状态 + 实跑 TR 数字
- [ ] ③ Roadmap `docs/dsdb-design-status-and-roadmap.md`：`OQ12-㉑` ⏳ → ✅ 闭环描述 + 挂出 `OQ12-㉒`
- [ ] ④ Project Memory 追加 P43 交付摘要 + 教训
- [ ] ⑤ 红线审计（仅 `FulltextStore` 增量区 + 6 测试文件）

---

## Stage 5: 交付（commit + push，待完成）

- [ ] `git add` P21 `FulltextStore` 增量 + 测试 6 文件 + spec.md + tasks.md + Roadmap + Memory
- [ ] `git commit`（P43 T1-T6 + Gate C 5/5 closure）
- [ ] `git push origin main`

---

## 红线总表（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫 |
|------|------|------|
| R1 | ZeroLeak3=3 + 六重文件数守恒（递归）+ P43 Δ0 | 根包=3；segment=5；parser/v3=5；agg=6；compact=5；groupby=5；layered=3；新增 `.java`=0 |
| R2 | NG-3 零依赖 | pom 0 新增 dep/plugin；0 netty/grpc/p2p.client；仅 JDK + first-party import |
| R3 | TOPK 铁律 | 0 改 K / 0 `SearchOptions` / `bucketAccessCounter` 0 污染 |
| R4 | Facade 单调不减（P43 例外 2） | P37/P40/P41/P42 目录 0 改；P21~P42 签名 0 改；P21 +3 挂载签名 + `searchCore` 6 处等价替换 |
| R5 | 段格式 0 变更 | 复用 `MAGIC="DIDX"` + `VERSION=1`；0 新增列/magic/version 分支 |
| R6 | USER-HC-0 / TermDict 零触碰 | `toUid` 无 level 参数；`TermDictStore`/`FulltextSegmenter` 0 改；uid 0 重编码 |
| R7 | 切换等价性（核心新红线） | attach 忠实快照后 `search`/`searchByUidsForTest` bit 级全等 |
| R8 | 回退语义确定性 | 视图优先 → WeightStore 回退；无视图 = 默认态字节级同行为 |
| R9 | 候选召回 0 改 | IDX1/2/3 + `neighborsWithinD` + `computePhraseBoost` + `computeAvgDocLen` 源码 0 改 |
| R10 | 零重建 & 0 新线程 | `attach` 纯状态位 O(1)；0 `ExecutorService`/`Timer` |
