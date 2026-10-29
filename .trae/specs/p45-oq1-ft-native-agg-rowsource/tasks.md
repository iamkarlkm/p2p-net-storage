# P45 OQ1 FT 原生聚合行源（`FulltextAggRowSource`：打通「FT 条件 + 聚合执行」）—— tasks.md（**REV1 Gate A 已签批，Gate B 通过，Gate C 进行中**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ ✅ 已签批（OQ12-㉓①，Q1=方案 A / Q2=交集）
- [x] **Gate B**（P45 own 31/31 TR 0F0E + P22-P45 拆 4 批全量回归）→ ✅ **728 run / 1F / 0E**（唯一 1F = 已知基线）
- [x] **Gate C**（9Y 自检 9/9 + 洁癖 5/5）→ ✅ 9Y 9/9（`P45_T5`）+ 洁癖 5/5

### 实跑 TR 数（Implement 后实跑回填，**禁止预填**）
- P45 own = **31 TR（5+6+3+3+9+5）= 31/31 TR 0F0E ✅**
  - T1 `P45_T1_RowSourceSkeletonTest` = 5、T2 `P45_T2_PathEquivalenceTest` = 6、T3 `P45_T3_ExecutorIntegrationTest` = 3
  - T4 `P45_T4_E2eFtAggTest` = 3、T5 `P45_T5_Gatekeeper9YTest` = 9、T6 `P45_T6_NFR_Test` = 5
- Gate B 拆 4 批 = 批1 P22-P24 **76 run / 0F / 0E** ✅ / 批2 P25-P27 **80 run / 0F / 0E** ✅ / 批3 P28-P30 **100 run / 1F / 0E** ✅（1F = 已知基线 `P28_T6.tr64`）/ 批4 P31-P45 **472 run / 0F / 0E** ✅（含 P45 own 31 TR）→ **合计 728 run / 1F / 0E（唯一 1F 为已知基线）**

---

## Stage 1: Gate A（待签批）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 锚点（`AggRowSource` 接口不抛受检异常 / `GenericManagerRowSource.toWrapper` package-private + FT→UOE / `AggExecutor`·`GroupByExecutor` 只依赖接口 / `DsHybridQueryClient` ORM∩FT 范式 + 强一致 ISE / `DsFulltextClusterClient.searchCluster` 抛 IOException / `ConditionNode.Kind.FT` / 六重递归文件数红线 / Roadmap OQ12-㉓①） | ✅ |
| 1.2 | 起草 spec.md（§0 两处待裁决 Q1/Q2 + Scope/MVP 边界 + 红线 R1-R10 + 架构新开 `rowsource/` + 数据流 + 接口契约 + 31 TR 计划 + 逆指标 + 风险回退 + Probe 锚点 + 9Y 自检全 `[ ]`） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + Stage 1-5 + 六阶段 TR 明细 + Gate B 拆批命令 + 红线总表） | ✅ |
| 1.4 | 提交 Gate A 批准 | ⏳ 待用户 VERBATIM 签批 |

### Probe 结论（实锤锚点，详见 spec.md §11）
1. **行源接口**：`AggRowSource<T extends DsTableAdapter>`（`agg/AggRowSource.java:29-37`）`fetch(List<ConditionNode>) → List<T>`，**不抛受检异常**。
2. **MVP 洞**：`GenericManagerRowSource`（`agg/GenericManagerRowSource.java:64` package-private `static toWrapper`；`:77-81`）遇 `ConditionNode.Kind.FT` **抛 `UnsupportedOperationException`** —— P39/P41 留下的洞。
3. **执行器 0 改可被驱动**：`AggExecutor`（`agg/AggExecutor.java:58-155`）+ `GroupByExecutor`（`groupby/GroupByExecutor.java:70-157`）**只依赖 `AggRowSource` 接口**。
4. **FT↔ORM 桥既有范式**：`DsHybridQueryClient`（`DsHybridQueryClient.java:167-222`）ORM ids ∩ FT docIds → `gm.getById`；**`:209-212` 强一致 ISE**（交集命中但实体 null → 抛 ISE 不静默）。
5. **FT 客户端签名**：`DsFulltextClusterClient.searchCluster(q,K,opt)`（`:84`）+ `searchClusterWithExtras(...)`（`:89`）**均抛 `IOException`** → 与 `fetch` 签名冲突，用 `UncheckedIOException` 包装（§5 注）。
6. **契约**：`HybridHit`（`join/HybridHit.java:35-50`）`docId == entity.getId()` 三值全等。
7. **节点结构**：`ConditionNode`（`parser/v2/ConditionNode.java:46-119`）`Kind{FT,ORM,IS_NULL,IS_NOT_NULL}` + 8 IAE。
8. **六重递归文件数红线**：`segment/`=5 / `parser/v3/`=5 / `agg/`=6 / `compact/`=5 / `groupby/`=5 / `layered/`=3 + 根包=3 → `agg/` 已锁死 6，**新行源必须开新子包 `cluster/rowsource/`**。

---

## Stage 2: Implement T1-T6（串行 T1→T6，目标 **31/31 TR 0F0E**）

| T | 范围 | TR 数 | 关键实装 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | 行源骨架 | 5 | ctor IAE 矩阵（gm/ftClient null、ftK<1、opt null→defaults）+ `fetch` IAE（minterm null/空/含 null）+ 初始 0 次 FT/ORM 调用 | `P45_T1_RowSourceSkeletonTest` |
| **T2** | 路径等价性 | 6 | 纯 ORM minterm ≡ `GenericManagerRowSource` 逐字段全等（复用 Q1=A 的 `toWrapper`）；纯 FT 命中；FT+ORM 混合取交集；多 FT 节点按 Q2 交集；FT 无命中→空集；FT 命中无实体→ISE | `P45_T2_PathEquivalenceTest` |
| **T3** | 执行器集成 | 3 | `AggExecutor` 端到端（FT 条件 COUNT/SUM）；`GroupByExecutor`（FT 条件分组 + HAVING）；minterm 重叠 → union 按 PK 去重 | `P45_T3_ExecutorIntegrationTest` |
| **T4** | E2E + 跨进程 | 3 | 真实 `DsDatabaseLocal` + FT index 全链路（`AggParser.parse → AggExecutor.execute`，含 FT 条件）vs 手算真值；隔离目录零重建；跨进程 fingerprint 64-hex 一致 | `P45_T4_E2eFtAggTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 + 六重递归文件数 + 新开 `rowsource/` 文件数 + Y2 NG-3（pom 0 新增）+ Y3 TOPK（0 触 `SearchOptions`）+ Y4 Facade 单调不减（P39 `agg/`·P41 `groupby/`·P28 DHQC·P31 join 源码 0 改，**唯一例外 `toWrapper` 可见性**）+ Y5 路由 + Y6 P21~P45 编译兼容 + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 + Y9 0 TODO | `P45_T5_Gatekeeper9YTest` |
| **T6** | NFR | 5 | 纯 ORM 路径 `fetch` P95 ≤ `GenericManagerRowSource` +5%；1 类 Javadoc ≥10 行；CPD 160 字符窗口 0 重复块；0 `println`/`printStackTrace` + IAE ≥N；6 场景 100% | `P45_T6_NFR_Test` |

**TR 总数（目标）**：5+6+3+3+9+5 = **31 TR**

---

## Stage 3: Gate B（拆 4 批全量回归，⏳ 待实跑）

### 3.1 命令（每批前清理 `target\tmp` + 仓库根 `.tmp` + `surefire-reports`；改源码/测试后先 `test-compile`）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P25_*,ds.P26_*,ds.P27_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P28_*,ds.P29_*,ds.P30_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*,ds.P42_*,ds.P43_*,ds.P44_*,ds.P45_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

### 3.2 验收门槛（Implement 后逐项回填实跑数字）
- [x] P45 own **31/31 TR 0F0E**（T1 5/5 + T2 6/6 + T3 3/3 + T4 3/3 + T5 9/9 + T6 5/5）
- [x] 批 1 P22-P24 = **76 run / 0F / 0E**
- [x] 批 2 P25-P27 = **80 run / 0F / 0E**
- [x] 批 3 P28-P30 = **100 run / 1F / 0E**（1F = 已知基线 `P28_T6.tr64_noBarePrintAndGte5NullGuards`）
- [x] 批 4 P31-P45 = **472 run / 0F / 0E**（含 P45 own 31 TR）
- [x] 纯 ORM 路径与 `GenericManagerRowSource` 逐字段等价（T2.tr21）
- [x] FT↔ORM 强一致（命中 docId 无实体 → ISE）（T2.tr26 / T6.tr65）
- [x] 段格式 0 变更（`MAGIC`/`VERSION`/4×CRC32 全合法）
- [x] 六重递归文件数守恒 + 新开 `rowsource/` 文件数 = 1（T5.y1）
- [x] P39 `agg/`·P41 `groupby/`·P28 DHQC·P31 join 源码 0 改（**唯一例外 = `toWrapper` 可见性**）（T5.y4）

---

## Stage 4: Gate C（9Y 自检 + 洁癖 5/5）

- [x] ⓪ 9Y 自检 9 项全 `[x]`（spec.md 顶部清单，逐项附实锤测试名）—— `P45_T5` 9/9 0F0E
- [x] ① spec.md 回填 Gate C 9Y + §10 实装偏差表 + 签批区
- [x] ② tasks.md 回填 Gate 三状态 + 实跑 TR 数字
- [x] ③ Roadmap `docs/dsdb-design-status-and-roadmap.md`：`OQ12-㉓` 候选项 ① 划除（引用 `e76d062`）+ 挂出独立 `⏳ OQ12-㉔`（列存/服务端聚合下推 为 ①，②compaction 后台调度器 / ③跨段词网聚合 顺延）
- [x] ④ Project Memory 追加 P45 交付摘要 + 教训
- [x] ⑤ 红线审计（新包 `rowsource/` + 1 类 + 6 测试文件）—— 实锤：rowsource=1 主类 + P45 主文件 `FulltextAggRowSource.java` + 6 测试类；GenericManagerRowSource 仅 `toWrapper` 可见性 1 词

---

## Stage 5: 交付（commit + push）

- [x] `git add` `FulltextAggRowSource.java`（新）+ 测试 6 文件 + `GenericManagerRowSource.java`（Q1=A 1 词可见性）+ spec.md + tasks.md（→ commit `e76d062`，10 files / 2409 insertions / 8 deletions）
- [x] `git commit`（P45 T1-T6 + Gate C 5/5 closure）→ `e76d062`
- [x] `git push origin main`（含本条 Roadmap `OQ12-㉔` 挂出增量 commit `7a76960`）

---

## 红线总表（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫 |
|------|------|------|
| R1 | 架构守恒 + 新开 `rowsource/` | ZeroLeak3=3（根包=3）；segment=5；parser/v3=5；agg=6；compact=5；groupby=5；layered=3；新子树 `rowsource/`=1 |
| R2 | Facade 单调不减（**唯一例外**） | P39 `agg/` 6 类 / P41 `groupby/` 5 类 / P28 DHQC / P31 `join/` 源码 0 改；**例外 = Q1=A `toWrapper` `static`→`public static`** |
| R3 | 上游 0 改 | P37~P44（`segment/`·`compact/`·`groupby/`·`layered/`·`FulltextStore`）源码 0 改 |
| R4 | 度量与签名冻结 | `DslToken.values().length==25`、`ConditionNode` 8 IAE、`AggQueryPlan` 字段、`AggExecutor.execute` 3 参、`AggMerger.merge` 1 参、`HybridHit` ctor=2、`ShardRouter` 路由、`ShardTransport` 5 方法 —— 0 改 |
| R5 | 段格式 0 变更 | 复用 `MAGIC="DIDX"` + `VERSION=1` + 4×CRC32；0 新增列/magic/version 分支 |
| R6 | 结果确定性 | `fetch` 按 docId 升序；多 FT 节点按 Q2（默认交集）；NULL 语义沿用 P39/P41 |
| R7 | FT↔ORM 强一致 | 命中 docId `== entity.getId()`；交集命中但 `getById==null` → 抛 ISE 不静默（对齐 `DsHybridQueryClient:209-212`） |
| R8 | 无状态 & 0 新线程 | `FulltextAggRowSource` 无实例可变状态；0 `ExecutorService`/`Timer` |
| R9 | TOPK 铁律 | `bucketAccessCounter[1025]` 0 污染；0 修改传入 `SearchOptions`（只读） |
| R10 | 代码卫生 | 新增源文件 0 `TODO/FIXME/HACK`；0 `println`/`printStackTrace` |
