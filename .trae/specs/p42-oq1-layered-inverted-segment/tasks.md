# P42 OQ1 全文分层倒排索引段化（Slice A：读路径段化）—— tasks.md（**REV1 GATE A 已批准**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ 用户 VERBATIM 批准（OQ12-⑳①，采纳 §0 方案 A）
- [x] **Gate B**（P42 own 31/31 TR 0F0E + P22-P42 拆 4 批：76/80/100/379 = 634 pass + 1 已知基线 fail）
- [x] **Gate C**（9Y 自检 9/9 + 洁癖 5/5 全闭环）

### 实跑 TR 数（Implement 后实跑回填）
- P42 own = **31 TR（5+6+3+3+9+5）→ 实跑 31/31 0F0E ✅**
  - T1 `P42_T1_SnapshotSkeletonTest` = 5/5、T2 `P42_T2_ExporterEquivalenceTest` = 6/6、T3 `P42_T3_ViewSemanticsTest` = 3/3
  - T4 `P42_T4_E2eLayeredSegmentTest` = 3/3、T5 `P42_T5_Gatekeeper9YTest` = 9/9、T6 `P42_T6_NFR_Test` = 5/5
- Gate B 拆 4 批 = 待实跑

---

## Stage 1: Gate A（起草完成，待签批）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 锚点（`TermDictStore` 分层常量 + USER-HC-0 + `mix4`；P37 `segment/` 5 类 API；P40 `compact/` 5 类；`FulltextStore` 公开面枚举缺口；六重递归文件数红线） | ✅ |
| 1.2 | 起草 spec.md（§0 需用户裁决 + Scope/MVP 边界 + 红线 R1-R10 + 架构 3 类 + 数据流 + 接口契约 + 测试 31 TR + 逆指标 + 风险回退 + Probe 锚点 + 9Y 自检全 `[ ]`） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + Stage 1-5 + 六阶段 TR 明细 + Gate B 拆批命令 + 红线总表） | ✅ |
| 1.4 | NotifyUser 提交 Gate A 批准 | ✅（用户 VERBATIM 批准，采纳方案 A） |

### Probe 结论（实锤锚点，详见 spec.md §11）
1. **枚举缺口（本 spec 的核心新发现）**：`FulltextStore` 仅公开 `weightStorePublic()`（注释明示「生产代码不依赖」）+ `weightStoreAllDocKeysForTest()`（docStat 键集）；**termStat 键集与 tf 键→值映射无任何公开枚举**，且 `WeightStore.termStatMap` / `termFreqInDocMap` 为**包私有**、tf 值需 `readBucketVal`（private）解出 → 非 root 包导出器无法读取 → **R4 的 P21 append-only accessor 是导出器的必要条件**（§0 方案 A）。
2. **分层 0 成本**：P37 段格式的 `docStat` **本就是 6 level 定长列**（`docLen[6]+nTerms[6]`，56B），tf 键**本就把 level 烘焙进 `mix4(uid,docId,parentId,level)`** → 「分层倒排段化」**0 新增段格式**（R5）即天然成立。
3. **P37 `DsIndexSegmentWriter`**：`ctor(File)` / `addDocStat(docId, docLen[6], nTerms[6])` / `addTermStat(uid, idf, payload)` / `addTf(key, tf)` / `finish()`（`.tmp` + `Files.move` 原子落盘；二次 `finish` IAE；`sortedKeys` 保证三列升序）→ 导出器直接复用，0 改。
4. **P37 `DsIndexSegmentStore`**：`segmentFile` / `append(segId, seg)`（段文件必须已存在 + 指纹一致 + 重复 IAE）/ `openReader` / `list()`（**插入序，非排序**）/ `remove`；**写入必须先 `finish` 再 `append`**（R9）。
5. **P37 `DsIndexSegmentReader`**：`open(File)`（mmap + `MAGIC`/`VERSION` + 4×CRC32，0 上游扫描）/ `getDocStat` / `getTermStat`（按需解压 payload）/ `getTf`（miss=0）/ `scanTfPrefix` / `close`（双通道 unmapper，幂等）→ 视图直接持有。
6. **P40 `SegmentMergeView`**：`ctor(List<DsIndexSegmentReader> oldToNew)`（3 IAE）/ `getDocStat`/`getTermStat`（新→旧首个命中，全 miss null）/ `getTf`（首个非零，全 miss 0）/ `segmentCount` / `fingerprint`（随段序变化）/ `close`（幂等）→ 视图 0 改复用，多段语义天然继承。
7. **P21 `WeightStore` 行为对象**：`WeightDocStatRow`（public `docLen[6]`/`nTerms[6]`）+ `WeightTermStatRow(idf, payload)` 为 P37 reader 返回类型 → 视图/导出器 0 改即承载。
8. **六重递归文件数红线**：`P37_T5`/`P38_T5`/`P39_T5`/`P40_T5`/`P41_T5` 五处 `collectJavaFiles` **递归**断言 → `segment/`=5、`parser/v3/`=5、`agg/`=6、`compact/`=5、`groupby/`=5 五棵子树不可新增 `.java` → P42 3 新类**必须**落新包 `layered/`（=3）。

---

## Stage 2: Implement T1-T6（串行 T1→T6，目标 **31/31 TR 0F0E**）

| T | 范围 | TR 数 | 关键实装 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | `LayeredSnapshot` + 入口骨架 | 5 | 6 IAE（segId<0 / count<0 / fingerprint null / len≠64）+ `fingerprint()` 64-hex 恒定且随字段变化 + `deepEquals` 真值表 + `LayeredIndexExporter` 无状态构造 + `LayeredInvertedView.open` null/空 segIds IAE | `P42_T1_SnapshotSkeletonTest` |
| **T2** | 导出器等价性 | 6 | 2 文档小语料 export：docCount/termCount/tfCount 三计数与内存键集 size 全等 + docStat 逐 docId 6-level `docLen`+`nTerms` 全等 + termStat 逐 uid `idf`（bit 级）+ payload 全等 + tf 逐 (key,tf) 全等 + 空 store 边界（0/0/0 合法段） | `P42_T2_ExporterEquivalenceTest` |
| **T3** | 只读视图语义 | 3 | `docLen`/`nTerms` 命中/miss=0 + level ∉ [0,5] IAE + `hasDoc`；`tf` 与 `reader.getTf(mix4)` 全等 + miss=0 + `idf`/`termPayload`/`hasTerm`；多段 2 段「新段优先」覆盖 + P40 `SegmentCompactor` 归并后单段等价 + `fingerprint` 随段序变化 + `close` 幂等 | `P42_T3_ViewSemanticsTest` |
| **T4** | E2E + 跨进程 | 3 | 真实 `FulltextStore`（独立 temp home + 唯一后缀）ingest → export → `FulltextStore.close()` → 重开 `LayeredInvertedView` 逐条等价（零上游依赖）；冷启动 `open` P95 ≤ 200ms（零重建）+ 段自描述；跨进程 `snapshot.fingerprint()` 64-hex 一致（子 JVM spawn） | `P42_T4_E2eLayeredSegmentTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 + 六重递归文件数守恒（根包=3 / segment=5 / parser/v3=5 / agg=6 / compact=5 / groupby=5 / layered=3）+ Y2 NG-3 + Y3 TOPK + Y4 Facade 单调不减（P37/P40/P41 目录 grep 0 改 + P21~P41 签名 + 2 新 accessor 存在且旧签名不消失）+ Y5 路由 + Y6 P21~P42 编译兼容 + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 + Y9 0 TODO/FIXME/HACK | `P42_T5_Gatekeeper9YTest` |
| **T6** | NFR | 5 | export 1k docs P95 ≤ 阈值 + 冷启动 open P95 ≤ 200ms + 3 类 Javadoc ≥ 10 行 + CPD 160 字符窗口 0 重复块 + 0 `println`/`printStackTrace` + `layered/` IAE ≥ 8 | `P42_T6_NFR_Test` |

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
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*,ds.P42_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

### 3.2 验收门槛（Implement 后逐项回填实跑数字）

- [x] P42 own **31/31 TR 0F0E**（T1 5/5 + T2 6/6 + T3 3/3 + T4 3/3 + T5 9/9 + T6 5/5）
- [x] 批 1 P22-P24 = **76/76 0F0E** ✅
- [x] 批 2 P25-P27 = **80/80 0F0E** ✅
- [x] 批 3 P28-P30 = **100 TR（99 0F0E + 1 已知基线 fail `P28_T6.tr64_noBarePrintAndGte5NullGuards`）** ✅
- [x] 批 4 P31-P42 = **379/379 0F0E** ✅
- [x] 导出等价性（三列逐条 == 内存 WeightStore 真值）
- [x] 零重建（`FulltextStore.close()` 后重开 view 等价 + 冷启动 open P95 ≤ 200ms）
- [x] 段格式 0 变更（`MAGIC`/`VERSION`/4×CRC32 全合法）
- [x] `LayeredSnapshot.fingerprint()` 跨进程恒定
- [x] P37 `segment/` + P40 `compact/` + P41 `groupby/` 源码字节级 0 改

> **Gate B 实跑汇总（2026-10-25）**：4 批合计 **635 TR = 634 pass + 1 已知基线 fail**；P42 own 31/31 0F0E。批 3 唯一 fail 为 P28 遗留基线（非 P42 引入），沿用不修。

---

## Stage 4: Gate C（9Y 自检 + 洁癖 5/5，已闭环）

- [x] ⓪ 9Y 自检 9 项全 `[x]`（spec.md 顶部清单，逐项附实锤测试名；实锤 `P42_T5` = 9/9 0F0E）
- [x] ① spec.md 回填 Gate C 9Y + §10 实装偏差表（D1-D4 全为测试装置修正，0 契约偏差）+ 签批区
- [x] ② tasks.md 回填 Gate 三状态 + 实跑 TR 数字
- [x] ③ Roadmap `docs/dsdb-design-status-and-roadmap.md`：`OQ12-⑳` ⏳ → ✅ 闭环描述 + 挂出 `OQ12-㉑`
- [x] ④ Project Memory 追加 P42 交付摘要 + 教训
- [x] ⑤ 红线审计（仅 `layered/` 3 新类 + `FulltextStore` append-only 2 accessor + 6 测试文件）

---

## Stage 5: 交付（commit + push，待完成）

- [ ] `git add` P42 源码 3 类 + P21 append-only + 测试 6 文件 + spec.md + tasks.md + Roadmap + Memory
- [ ] `git commit`（P42 T1-T6 + Gate C 5/5 closure）
- [ ] `git push origin main`

---

## 红线总表（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫 |
|------|------|------|
| R1 | ZeroLeak3=3 + 六重文件数守恒（递归） | 根包=3；segment=5；parser/v3=5；agg=6；compact=5；groupby=5；layered=3 |
| R2 | NG-3 零依赖 | pom 0 新增 dep/plugin；0 netty/grpc/p2p.client；仅 JDK + first-party import |
| R3 | TOPK 铁律 | 0 改 K / 0 `SearchOptions` / `bucketAccessCounter` 0 污染 |
| R4 | Facade 单调不减（P21 唯一例外：append-only） | P37/P40/P41 目录 0 改；P21~P41 签名 0 改；P21 仅 +2 只读 accessor |
| R5 | 段格式 0 变更 | 复用 `MAGIC="DIDX"` + `VERSION=1`；产物合法 P37 段 |
| R6 | USER-HC-0 / TermDict 零触碰 | `toUid` 无 level 参数；`TermDictStore`/`FulltextSegmenter` 0 改；uid 不重编码 |
| R7 | 导出确定性 | 三列升序（writer `sortedKeys`）；同源同批 fingerprint 恒定 |
| R8 | 覆盖语义确定性 | 单段后者覆盖；多段「新→旧」首个命中；tf miss=0；段序调用方显式给定 |
| R9 | 崩溃安全 | `writer.finish()` 先于 `store.append()`；未注册半成品不可见 |
| R10 | 零重建 | `open` 仅 mmap + 头/CRC 校验；禁止全量进堆；冷启动 P95 ≤ 200ms |
