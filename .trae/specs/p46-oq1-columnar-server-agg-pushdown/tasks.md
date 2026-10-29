# P46 OQ1 列存/服务端聚合下推（`ColumnarAggRowSource`）—— tasks.md（**REV1 Gate A ✅ 已签批**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ ✅ 已签批（OQ12-㉔①，Q1=方案 A / Q2=双模；VERBATIM 见 spec §9）
- [x] **Gate B**（P46 own 31/31 TR 0F0E + P22-P46 拆 4 批全量回归）→ ✅ 完成（合计 **790 run / 1F（已知基线）/ 0E**）
- [ ] **Gate C**（9Y 自检 9/9 + 洁癖 5/5）→ ⏳ 进行中

### 实跑 TR 数（Implement 后实跑回填，**禁止预填**）
- P46 own = `31 TR`（T1 5 + T2 6 + T3 3 + T4 3 + T5 9 + T6 5；`Tests run: 31, Failures: 0, Errors: 0`）
- Gate B 拆 4 批 = `76 + 80 + 100 + 503 = 759 run`（+P46 own 31 = 790；1F = 已知基线 `P28_T6.tr64`）

---

## Stage 1: Gate A（✅ 已签批）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 锚点（`AggRowSource` 接口不抛受检异常 / 两实装 `GenericManagerRowSource`·`FulltextAggRowSource` / `DsDatabaseClusterClient.queryIds` 2 重载 / `DbQuery`·`DbQueryOp` 谓词模型 / `GenericManager.getById` 分布式感知 / `findList`·`listIds` / P17 T4 `aggregateByComposite` / 六重递归文件数红线 / Roadmap OQ12-㉔①） | ✅ |
| 1.2 | 起草 spec.md（§0 两处待裁决 Q1/Q2 + Scope/MVP 边界 + 红线 R1-R10 + 架构 `rowsource/` +1 + 数据流 + 接口契约 + 31 TR 计划 + 逆指标 + 风险回退 + Probe 锚点 + 9Y 自检全 `[ ]`） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + Stage 1-5 + 六阶段 TR 明细 + Gate B 拆批命令 + 红线总表） | ✅ |
| 1.4 | 提交 Gate A 批准 | ✅ 已签批（Q1=方案 A / Q2=双模） |

### Probe 结论（实锤锚点，详见 spec.md §11）
1. **行源接口**：`AggRowSource<T extends DsTableAdapter>`（`agg/AggRowSource.java:29-37`）`fetch(List<ConditionNode>) → List<T>`，**不抛受检异常**。
2. **两实装**：`GenericManagerRowSource`（`agg/GenericManagerRowSource.java:53-55`，ORM `QueryWrapper` 路径）与 P45 `FulltextAggRowSource`（`rowsource/`，FT 路径）——**列存/服务端路径缺位**。
3. **服务端 id 下推入口**：`DsDatabaseClusterClient.queryIds(entityClassName, DbQuery, offset, limit)`（`:201`）+ 单分区重载（`:228`）。
4. **谓词模型**：`DbQuery{where, anyOf, orderBy}` + `DbQueryCriterion{op,name,a,b,list}` + `DbQueryOp`（14 枚举），与 `ConditionNode` ORM op 可 1:1 映射。
5. **物化**：`GenericManager.getById`（`:470-481`）分布式感知。
6. **执行器 0 改可被驱动**：`AggExecutor`（`:58-155`）+ `GroupByExecutor`（`:70-157`）只依赖 `AggRowSource` 接口。
7. **六重递归文件数红线**：`agg/`=6 已锁死 → 新类落 `rowsource/`（P45 新子树，1 → 2）。
8. **P17 T4 既有内部聚合**：`GenericManager.aggregateByComposite`（`:3260-3351`，package-private，复合索引前缀 GROUP BY）——P46 不触（0 改），仅记录为后续「真·列扫描」参考。

---

## Stage 2: Implement T1-T6（串行 T1→T6，目标 **31/31 TR 0F0E**）

| T | 范围 | TR 数 | 关键实装 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | 行源骨架 | 5 | ctor IAE 矩阵（gm null、colClient!=null 且 entityClassName 空白）+ `fetch` IAE（minterm null/空/含 null）+ 含 FT → UOE + 初始 0 次调用 | `P46_T1_ColumnarRowSourceSkeletonTest` |
| **T2** | `DbQuery` 下推等价性 | 6 | minterm→`DbQuery` 逐 op 映射全等；双模本地路径 ≡ `GenericManagerRowSource`；服务端路径 id 集合正确；空结果；miss → ISE；docId 升序 | `P46_T2_DbQueryPushdownEquivalenceTest` |
| **T3** | 执行器集成 | 3 | `AggExecutor` 端到端（列存条件 COUNT/SUM）；`GroupByExecutor`（列存条件分组 + HAVING）；minterm 重叠 → union 按 PK 去重 | `P46_T3_ExecutorIntegrationTest` |
| **T4** | E2E + 跨进程 | 3 | 真实 `DsDatabaseLocal`/列存全链路（`AggParser.parse → AggExecutor.execute`）vs 手算真值；隔离目录零重建；跨进程 fingerprint 64-hex 一致 | `P46_T4_E2eColumnarAggTest` |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 + 六重递归文件数 + `rowsource/`=2 + Y2 NG-3（pom 0 新增）+ Y3 TOPK（0 触 `SearchOptions`）+ Y4 Facade 单调不减（P39 `agg/`·P41 `groupby/`·P28 DHQC·P31 join·P45 `FulltextAggRowSource` 源码 0 改）+ Y5 路由（`ShardRouter`·`DsIdLayout`）+ Y6 P21~P46 编译兼容 + Y7 HybridHit ctor=2 + Y8 ShardTransport 5 + Y9 0 TODO | `P46_T5_Gatekeeper9YTest` |
| **T6** | NFR | 5 | 本地双模路径 `fetch` 均值 ≤ `GenericManagerRowSource` +5%；1 类 Javadoc ≥10 行；CPD 160 字符窗口 0 重复块；0 `println`/`printStackTrace` + IAE ≥N；6 场景 100% | `P46_T6_NFR_Test` |

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
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*,ds.P42_*,ds.P43_*,ds.P44_*,ds.P45_*,ds.P46_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
```

### 3.2 验收门槛（Implement 后逐项回填实跑数字）
- [x] P46 own **31/31 TR 0F0E**（T1 5/5 + T2 6/6 + T3 3/3 + T4 3/3 + T5 9/9 + T6 5/5）
- [x] 批 1 P22-P24 = `76 run / 0F / 0E`
- [x] 批 2 P25-P27 = `80 run / 0F / 0E`
- [x] 批 3 P28-P30 = `100 run / 1F / 0E`（唯一失败 = 已知基线 `P28_T6.tr64_noBarePrintAndGte5NullGuards`）
- [x] 批 4 P31-P46 = `503 run / 0F / 0E`（含 P46 own 31 TR）
- [x] 双模本地路径与 `GenericManagerRowSource` 逐字段等价（T2）
- [x] 列存↔ORM 强一致（queryIds 命中 id 无实体 → ISE）（T2 / T6）
- [x] 段格式 0 变更（`MAGIC`/`VERSION`/4×CRC32 全合法）
- [x] 六重递归文件数守恒 + `rowsource/` 文件数 = 2（T5.y1）
- [x] P39 `agg/`·P41 `groupby/`·P28 DHQC·P31 join·P45 `FulltextAggRowSource` 源码 0 改（T5.y4）

---

## Stage 4: Gate C（9Y 自检 + 洁癖 5/5）

- [ ] ⓪ 9Y 自检 9 项全 `[x]`（spec.md 顶部清单，逐项附实锤测试名）—— `P46_T5` 9/9 0F0E
- [ ] ① spec.md 回填 Gate C 9Y + §6.1 实跑数字 + §10 实装偏差表 + §9 签批区
- [ ] ② tasks.md 回填 Gate 三状态 + 实跑 TR 数字
- [ ] ③ Roadmap `docs/dsdb-design-status-and-roadmap.md`：`OQ12-㉔` 候选项 ① 划除（引用 P46 commit）+ 顺延 ②compaction 后台调度器 / ③跨段词网聚合
- [ ] ④ Project Memory 追加 P46 交付摘要 + 教训
- [ ] ⑤ 红线审计（`rowsource/` +1 类 + 6 测试文件）—— 实锤：rowsource=2 主类 + 6 测试类；上游 0 改

---

## Stage 5: 交付（commit + push）

- [ ] `git add` `ColumnarAggRowSource.java`（新）+ 测试 6 文件 + spec.md + tasks.md（→ commit `______`）
- [ ] `git commit`（P46 T1-T6 + Gate C 5/5 closure）→ `______`
- [ ] `git push origin main`（含 Roadmap `OQ12-㉔①` 划除增量 commit `______`）

---

## 红线总表（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫 |
|------|------|------|
| R1 | 架构守恒 + `rowsource/` +1 | ZeroLeak3=3（根包=3）；segment=5；parser/v3=5；agg=6；compact=5；groupby=5；layered=3；`rowsource/`=2 |
| R2 | Facade 单调不减 | P39 `agg/` 6 类 / P41 `groupby/` 5 类 / P28 DHQC / P31 `join/` / P45 `FulltextAggRowSource` 源码 0 改 |
| R3 | 上游 0 改 | P37~P45 + `columnar/*` + `DbQuery`/`DbQueryOp`/`DbQueryCriterion` + `GenericManager` 源码 0 改 |
| R4 | 度量与签名冻结 | `DslToken.values().length==25`、`ConditionNode` 8 IAE、`AggQueryPlan` 字段、`AggExecutor.execute` 3 参、`AggMerger.merge` 1 参、`HybridHit` ctor=2、`ShardRouter` 路由、`ShardTransport` 5、`DsDatabaseClusterClient.queryIds` 2 重载、`DbQueryOp` 14 枚举 —— 0 改 |
| R5 | 段格式 0 变更 | 复用 `MAGIC="DIDX"` + `VERSION=1` + 4×CRC32；0 新增列/magic/version 分支 |
| R6 | 结果确定性 | `fetch` 按 docId 升序；NULL 语义沿用 P39/P41 |
| R7 | 列存↔ORM 强一致 | queryIds 命中 id `getById==null` → 抛 ISE 不静默（对齐 P45 R7 / `DsHybridQueryClient:209-212`） |
| R8 | 无状态 & 0 新线程 | `ColumnarAggRowSource` 无实例可变状态；0 `ExecutorService`/`Timer` |
| R9 | TOPK 铁律 | `bucketAccessCounter[1025]` 0 污染；0 修改传入 `SearchOptions`（只读） |
| R10 | 代码卫生 | 新增源文件 0 `TODO/FIXME/HACK`；0 `println`/`printStackTrace` |
