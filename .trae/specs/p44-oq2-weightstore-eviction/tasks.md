# P44 OQ2 WeightStore 淘汰/驱逐（Slice C：视图全命中时卸载 docStat/termFreqInDoc/termStat 三张 `DsHashMap`）—— tasks.md（**REV1：Gate A 已签批 · Gate B 全绿 · Gate C 已回填**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ **已签批**（OQ12-㉒④，§0 采纳 **方案 A**）
- [x] **Gate B**（P44 own 31/31 TR 0F0E + P22-P44 拆 4 批全量回归：76+80+100+441 = **697 run / 1F(已知基线) / 0E**）
- [x] **Gate C**（9Y 自检 9/9 + 洁癖 5/5）

### 实跑 TR 数（Implement 后实跑回填）
- P44 own = **31 TR（5+6+3+3+9+5）→ 实跑 31 run / 0F / 0E ✅**
  - T1 `P44_T1_EvictSkeletonTest` = 5、T2 `P44_T2_EvictedEquivalenceTest` = 6、T3 `P44_T3_InvalidateSemanticsTest` = 3
  - T4 `P44_T4_E2eEvictTest` = 3、T5 `P44_T5_Gatekeeper9YTest` = 9、T6 `P44_T6_NFR_Test` = 5
- Gate B 拆 4 批 = 批1 P22-P24 **76** / 批2 P25-P27 **80** / 批3 P28-P30 **100**（1F 已知基线 `P28_T6.tr64`）/ 批4 P31-P44 **441**（含 P44 own 31 TR）→ 合计 **697 run / 1F / 0E**

---

## Stage 1: Gate A（已签批）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 锚点（`WeightStore` 5 表结构 / 段 3 列覆盖 vs positions·HNSW 未覆盖 / `DsHashMap.close()` 无 `closed` 标志 + `unloadAllMappedBuffers` / `getMappedBytes` 度量 / avgdl 未接视图 / P43 增量区 / 写路径入口 / 六重递归文件数红线 / Roadmap OQ12-㉒④） | ✅ |
| 1.2 | 起草 spec.md（§0 三方案需用户裁决 + Scope/MVP 边界 + 红线 R1-R10 含 R7'/R9 例外 + 架构 0 新文件 + 数据流 + 接口契约 + 测试 31 TR + 逆指标 + 风险回退 + Probe 锚点 + 9Y 自检全 `[ ]`） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + Stage 1-5 + 六阶段 TR 明细 + Gate B 拆批命令 + 红线总表） | ✅ |
| 1.4 | 提交 Gate A 批准 | ✅ 已签批（用户「4」选 ④ + 「继续」采纳方案 A） |

### Probe 结论（实锤锚点，详见 spec.md §11）
1. **`WeightStore` 结构（5 表 + 1 桶存 + HNSW）**：`docStatMap` / `termFreqInDocMap` / `termStatMap` / `termPositionsMap` + `valueStore` + 内嵌 `hnswStore`（`FulltextStore.java:252-273`）。
2. **段覆盖面 = 3 列**：`LayeredIndexExporter`（`layered/LayeredIndexExporter.java:74-104`）仅导出 docStat(docLen+nTerms) / termStat(idf+payload) / tf ⇒ **`termPositionsMap` 与 `hnswStore` 未覆盖（不在驱逐范围）**。
3. **释放原语（0 改复用）**：`DsHashMap.close()` = `syncAllRecursive` + `unloadAllRecursive`（`DsHashMap.java:2198-2218`），**无 `closed` 标志位**（`close` 后仍可用，缺失页惰性 `loadBuffer` 重映射）；底层 `DsObject.unloadAllMappedBuffers()`（`core/DsObject.java:537-554`，public）。
4. **度量原语**：`DsObject.getMappedBytes()`（`core/DsObject.java:380`）/ `getBufferStats()`（`:400`）/ `mappedBytes`（`:103`）→ 驱逐前后可量化内存下降。
5. **P43 遗留洞（本切片必闭）**：`computeAvgDocLen(level)`（`FulltextStore.java:2049-2060`）按 `dkey=1..` 线性扫 WeightStore → 驱逐后首个 search 会**重映射整张 docStatMap**，收益归零 ⇒ 必须改视图优先（R9 例外 1）。
6. **0 改面**：`WeightStore`（含其 `close()`）/ `selectByUnit`/`selectByParentLevel`/`selectByDocLevel`/`selectByUnitHit`/`neighborsWithinD`/`computePhraseBoost`/`recomputeIdfForTerms`/`searchWithVectorCore`/`ingestDoc` 全部保持。
7. **六重递归文件数红线 + ZeroLeak3=3**：`segment/`=5 / `parser/v3/`=5 / `agg/`=6 / `compact/`=5 / `groupby/`=5 / `layered/`=3 六棵子树 + 根包=3 全部钉死 → **P44 新增源文件数必须 = 0**。

---

## Stage 2: Implement T1-T6（串行 T1→T6，目标 **31/31 TR 0F0E**）

| T | 范围 | TR 数 | 关键实装 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | 驱逐骨架 | 5 | 未挂载视图 evict IAE + 初始 `isLayeredStatsEvicted()==false` + attach+evict→true + 重复 evict 幂等 + rehydrate→false 且可再 evict | `P44_T1_EvictSkeletonTest` |
| **T2** | 驱逐后 bit 级等价性 | 6 | 驱逐态 vs 未驱逐（视图完整）逐字段全等（4 分数 `doubleToLongBits` + `r1MatchedTerms` + `unitIdHit` + avgdl 同值）：单/多 term、`searchByUidsForTest`、phraseBoost+neighborRecall、无命中双空、`idfOverrideByUnitId` | `P44_T2_EvictedEquivalenceTest` |
| **T3** | 失效/回退语义 | 3 | 驱逐后 ingest → 自动失效 + 新文档查询正确；驱逐后 remove/clear → 同上；驱逐态 detach → 自动 rehydrate + 后续 == 从未 attach | `P44_T3_InvalidateSemanticsTest` |
| **T4** | E2E + 跨实例 | 3 | 真实 FT ingest→export→attach→evict→search 等价；跨实例（新 FT + open + attach + evict）全等；evict→close→重开 数据仍可读 | `P44_T4_E2eEvictTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 + 六重递归文件数 + **Δ0 新文件** + Y2 NG-3 + Y3 TOPK + Y4 Facade 单调不减（P37/P40/P41/P42 目录 0 改 + `WeightStore` 0 改 + P21 旧签名并存 + P44 新签名存在）+ Y5 路由 + Y6 P21~P44 编译兼容 + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 + Y9 0 TODO | `P44_T5_Gatekeeper9YTest` |
| **T6** | NFR | 5 | 内存收益（`getMappedBytes` 前后实度量下降且搜索后不回升）+ evict 后 search P95 ≤ 基线 × ≤ +5% + evict/rehydrate O(1) + Javadoc ≥ 10 行 + CPD 160 字符窗口 0 重复块 + 0 `println`/`printStackTrace` + 新增 IAE ≥ 2 | `P44_T6_NFR_Test` |

**TR 总数（目标）**：5+6+3+3+9+5 = **31 TR**

---

## Stage 3: Gate B（拆 4 批全量回归，✅ 已实跑）

### 3.1 命令（每批前清理 `target\tmp` + 仓库根 `.tmp` + `surefire-reports`；改源码/测试后先 `test-compile`）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P25_*,ds.P26_*,ds.P27_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P28_*,ds.P29_*,ds.P30_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*,ds.P42_*,ds.P43_*,ds.P44_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

### 3.2 验收门槛（Implement 后逐项回填实跑数字）

- [x] P44 own **31/31 TR 0F0E**（T1 5/5 + T2 6/6 + T3 3/3 + T4 3/3 + T5 9/9 + T6 5/5）→ **31 run / 0F / 0E BUILD SUCCESS**
- [x] 批 1 P22-P24 = **76 run / 0F / 0E**（BUILD SUCCESS）
- [x] 批 2 P25-P27 = **80 run / 0F / 0E**（BUILD SUCCESS）
- [x] 批 3 P28-P30 = **100 run / 1F / 0E**（1F 已知基线 `P28_T6.tr64_noBarePrintAndGte5NullGuards`，非新增回归）
- [x] 批 4 P31-P44 = **441 run / 0F / 0E**（含 P44 own 31 TR；BUILD SUCCESS）
- [x] 驱逐后等价性（驱逐态 bit 级全等 + avgdl 同值）
- [x] 失效/回退语义（写/ detach 后驱逐态自动失效）
- [x] 内存收益（`getMappedBytes` 实度量下降）
- [x] 段格式 0 变更（`MAGIC`/`VERSION`/4×CRC32 全合法）
- [x] 六重递归文件数守恒 + P44 Δ0 新增源文件
- [x] P37 `segment/` + P40 `compact/` + P41 `groupby/` + P42 `layered/` + `WeightStore` 源码字节级 0 改

> **合计：697 run / 1F(已知基线) / 0E**（76+80+100+441）。

---

## Stage 4: Gate C（9Y 自检 + 洁癖 5/5，✅ 已执行）

- [x] ⓪ 9Y 自检 9 项全 `[x]`（spec.md 顶部清单，逐项附实锤测试名）
- [x] ① spec.md 回填 Gate C 9Y + §10 实装偏差表（D1/D2/D3）+ 签批区
- [x] ② tasks.md 回填 Gate 三状态 + 实跑 TR 数字
- [x] ③ Roadmap `docs/dsdb-design-status-and-roadmap.md`：`OQ12-㉒` ⏳ → ✅ 闭环描述 + 挂出 `OQ12-㉓`
- [x] ④ Project Memory 追加 P44 交付摘要 + 教训
- [x] ⑤ 红线审计（仅 `FulltextStore` 增量区 + 6 测试文件）

---

## Stage 5: 交付（commit + push，待完成）

- [ ] `git add` P21 `FulltextStore` 增量 + 测试 6 文件 + spec.md + tasks.md + Roadmap + Memory
- [ ] `git commit`（P44 T1-T6 + Gate C 5/5 closure）
- [ ] `git push origin main`

---

## 红线总表（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫 |
|------|------|------|
| R1 | ZeroLeak3=3 + 六重文件数守恒（递归）+ P44 Δ0 | 根包=3；segment=5；parser/v3=5；agg=6；compact=5；groupby=5；layered=3；新增 `.java`=0 |
| R2 | NG-3 零依赖 | pom 0 新增 dep/plugin；0 netty/grpc/p2p.client；仅 JDK + first-party import |
| R3 | TOPK 铁律 | 0 改 K / 0 `SearchOptions` / `bucketAccessCounter` 0 污染 |
| R4 | Facade 单调不减（P44 例外） | P37/P40/P41/P42 目录 0 改；**`WeightStore` 源码 0 改**；P21~P43 签名 0 改；P21 +P44 签名 |
| R5 | 段格式 0 变更 | 复用 `MAGIC="DIDX"` + `VERSION=1`；0 新增列/magic/version 分支 |
| R6 | USER-HC-0 / TermDict 零触碰 | `toUid` 无 level 参数；`TermDictStore`/`FulltextSegmenter` 0 改；uid 0 重编码 |
| R7 | 驱逐前等价性（P43 沿用） | 未驱逐 + 视图完整 ⇒ bit 级全等 |
| R7' | 驱逐后等价性（核心新红线） | 驱逐态 + 视图完整 ⇒ 与未驱逐 bit 级全等（含 avgdl 同 double 值） |
| R8 | 回退语义确定性 | 未驱逐：视图优先 → WeightStore 回退；驱逐态：100% 视图（miss → 0/0.0/0） |
| R9 | 候选召回 0 改（P44 例外 1） | IDX1/2/3 + `neighborsWithinD` + `computePhraseBoost` + `recomputeIdfForTerms` 0 改；**例外 = `computeAvgDocLen` 改视图优先** |
| R10 | 零重建 & 0 新线程 & 持久性 0 损 | evict = sync + 释放映射（数据留盘可重建）；0 `ExecutorService`/`Timer` |
