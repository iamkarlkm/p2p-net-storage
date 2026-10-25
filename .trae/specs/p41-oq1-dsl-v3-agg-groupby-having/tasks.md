# P41 OQ1 DSL v3 聚合 v2 —— GROUP BY / HAVING / DISTINCT 执行引擎 —— tasks.md（**REV2 CLOSED，2026-10-25**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ 用户 VERBATIM 批准（OQ12-⑲ 候选 ② GROUP BY/HAVING/DISTINCT 子集），2026-10-24
- [x] **Gate B**（P41 own 31/31 TR + P22-P41 拆 4 批 + 已知基线 fail 沿用）→ **已实跑**（604 TR = 603 0F0E + 1 已知基线 fail）
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ **已完成**（9Y×9 全 Y + 洁癖 5/5，永久闭环）

### 实跑 TR 数（Gate B 回填，禁止预填）
- P41 own = **31/31 TR 0F0E**（T1 5/5 + T2 6/6 + T3 3/3 + T4 3/3 + T5 9/9 + T6 5/5）
- Gate B 拆 4 批 = **604 TR**（批 1 P22-P24=**76/76 0F0E** + 批 2 P25-P27=**80/80 0F0E** + 批 3 P28-P30=**100**（99 0F0E + 1 已知基线 fail `P28_T6.tr64_noBarePrintAndGte5NullGuards`）+ 批 4 P31-P41=**348/348 0F0E**（含 P41 own 31 TR））→ **603 0F0E + 1 已知基线 fail**

---

## Stage 1: Gate A（已 VERBATIM 签批，2026-10-24）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 7 锚点（P39 AggExecutor 全局单行 / P38 AggQueryPlan 公开字段 / AggSpec·AggFn 不可改 / AggValue 4 参可复用 / AggRowSource 契约 / P40_T5.Y1 四重递归文件数 / P39_T5.Y1 parser/v3=5） | ✅ |
| 1.2 | 起草 spec.md（Scope 选择说明 / 红线 R1-R10 / 架构 5 类 + 数据流 / 接口契约 / 测试 / 逆指标 / 风险 / Probe 7 锚点 + 9Y 自检 + §10 偏差占位） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细 + Gate B 拆批命令 + 洁癖 5/5 表 + Stage 5 交付） | ✅ |
| 1.4 | NotifyUser 提交 Gate A 批准 | ✅ 已 VERBATIM 批准 |

### Probe 结论（7 锚点实锤，详见 spec.md §8）
1. **P39 `AggExecutor`**：已实现 PK 去重 + 单遍 5 聚合，但只产**单行全局** `AggResult` → P41 在同去重框架上加分组，P39 源码 0 改。
2. **P38 `AggQueryPlan`**：公开 `clause` + `aggs` + `fingerprint()` → `GroupBySpec` **组合**（非继承）复用，0 改。
3. **`AggSpec` / `AggFn`**：DISTINCT 标记不能改（R4）→ 侧挂 `GroupBySpec.distinctAggIndexes`。
4. **`AggValue` 4 参构造**：`(spec, count, sum, value)` + 4 IAE → 桶内直接复用，0 改。
5. **`AggRowSource`**：`@FunctionalInterface fetch(minterm)`，「null → ISE」「行带 PK」契约 → P41 复用，0 改。
6. **P40_T5.Y1 硬约束**：`segment`=5 / `parser/v3`=5 / `agg`=6 / `compact`=5 均**递归**计数 + 根包 top-level=3 → P41 5 新类**必须**落**新兄弟子包** `cluster/groupby/`。
7. **P39_T5.Y1 硬约束**：`parser/v3` 文件数 = 5 → DISTINCT 不得改 `parser/v3/`，必须侧挂 `GroupBySpec`。

---

## Stage 2: Implement T1-T6（串行 T1→T6，31/31 TR 0F0E 实锤，2026-10-25）

| T | 范围 | TR 数 | 关键实装 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | `HavingSpec` + `GroupBySpec` + `GroupBucket` + `GroupByResult` skeleton | 5 | `HavingSpec` 3 IAE（aggIndex<0 / op null / threshold NaN·Inf）+ 6 算子 `test` 语义 + `test(null)` IAE；`GroupBySpec` 6 IAE + `fingerprint` 64-hex 稳定；`GroupBucket` 3 IAE + fingerprint；`GroupByResult` 2 IAE + `size/bucket` 越界 IOOBE + `deepEquals` + 不可变 | `P41_T1_SpecBucketResultTest` ✅ 5/5 0F0E |
| **T2** | `GroupByExecutor` 本地分桶 | 6 | 内存行源 lambda：单列 / 双列 GROUP BY + 5 聚合 vs 手算真值 / `COUNT(DISTINCT)` 去重计数 / 空 `aggs` → SELECT DISTINCT 分组列 / HAVING 单谓词 + 多谓词 AND / 桶序字典序确定性 / ≥6 IAE（spec·source·两 map null + 无 extractor + groupColumns 空 + DISTINCT 非 COUNT）+ 2 ISE（fetch null、行 id null） | `P41_T2_ExecutorGroupingTest` ✅ 6/6 0F0E |
| **T3** | union 去重 + HAVING 剔除 | 3 | 重叠 minterm（`[a1,a1] [a1,c3] [b2,a1] [b2,c3]`）去重后分组 vs 手写真值逐桶逐聚合全等 + HAVING 剔除整桶（含全剔除空结果）+ minterm 顺序无关（同值同 fingerprint） | `P41_T3_UnionHavingTest` ✅ 3/3 0F0E |
| **T4** | E2E + 跨进程 fingerprint | 3 | 真实 `DsDatabaseLocal` + `GenericManagerRowSource`（temp home + forceReset + 唯一表名后缀隔离，继承 P39/P40 模板）：双列 GROUP BY + HAVING vs 手算（含 NULL 分组列 + NULL 聚合列）+ `AggParser.parse` → `GroupBySpec` → `execute` 全链路 + 跨进程 fingerprint 一致（子 JVM spawn 复现 64-hex） | `P41_T4_E2eGroupByTest` ✅ 3/3 0F0E |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 **且** `segment`=5 / `parser/v3`=5 / `agg`=6 / `compact`=5 / `groupby`=5 **五重递归**守恒 + groupby 5 类齐；Y2 NG-3（groupby forbidden 引用 + import 白名单 + pom banned 0 命中）；Y3 TOPK（0 `bucketAccessCounter` / 0 `SearchOptions`）；Y4 Facade 单调不减（`AggParser.parse` 6 参 + `AggQueryPlan` 公开字段 + `AggExecutor.execute` 3 参 + `AggMerger.merge` 反射 + `DslToken.values().length == 25`）；Y5 路由 0 改；Y6 P21~P40 + groupby 5 类 `Class.forName`；Y7 HybridHit ctor=2；Y8 ShardTransport 5 方法；Y9 0 TODO/FIXME/HACK | `P41_T5_Gatekeeper9YTest` ✅ 9/9 0F0E |
| **T6** | NFR | 5 | execute P95 ≤ 2s（8 minterm × 3 agg × 1k 行 × 50 组）+ 5 类 Javadoc ≥ 10 行 + CPD 160 字符窗口 0 重复块 + 0 `System.out.println`/`printStackTrace` + IAE 显式守卫 ≥ 8 + 6 场景 100%（重叠去重 / 空 aggs DISTINCT / NULL 分组 / HAVING 全剔除空结果 / 桶序确定性 / fingerprint 跨进程一致） | `P41_T6_NFR_Test` ✅ 5/5 0F0E |

**TR 实跑总数**：5+6+3+3+9+5 = **31 TR 0F0E**（2026-10-25 实跑实锤）

---

## Stage 3: Gate B（已实跑，P22-P41 拆 4 批）

### 3.1 Surefire 命令（继承 P40 模板，实跑加 `-pl p2p-db`；改源码/测试后先 `mvn -o -q -pl p2p-db test-compile`）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
# 批1: P22-P24
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P22_*,ds.P23_*,ds.P24_*'
# 批2: P25-P27
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P25_*,ds.P26_*,ds.P27_*'
# 批3: P28-P30
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P28_*,ds.P29_*,ds.P30_*'
# 批4: P31-P41 (含 P41 own)
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*,ds.P40_*,ds.P41_*'
```

### 3.2 验收门槛（已实跑回填）
- [x] P41 own **31/31** 0F0E（T1 5/5 + T2 6/6 + T3 3/3 + T4 3/3 + T5 9/9 + T6 5/5）
- [x] 批 1（76/76 0F0E）/ 批 2（80/80 0F0E）全绿；批 3（100 = 99 0F0E + 1 已知基线 fail `P28_T6.tr64_noBarePrintAndGte5NullGuards`）沿用；批 4（348/348 0F0E，含 P41 own）全绿
- [x] Gate B 总计数字**实跑回填**：604 TR = **603 0F0E + 1 已知基线 fail**
- [x] 分组正确性：GROUP BY 逐桶逐聚合 = 手写「按分组键分组 distinct-PK」真值
- [x] HAVING 正确性：多谓词 AND 剔除桶后 = 手写过滤真值
- [x] DISTINCT 正确性：`COUNT(DISTINCT col)` = 手写桶内 distinct 非 NULL 值个数
- [x] 跨进程确定性：同 DSL + 同数据 → 同 `fingerprint()`（64-hex）
- [x] 0 副作用：execute 期间 0 触 FulltextStore / 0 改 `bucketAccessCounter` / 0 写库

---

## Stage 4: Gate C 洁癖 5/5（已完成，2026-10-25）

| 洁癖项 | 内容 | 状态 |
|--------|------|------|
| ① | spec.md Gate C `[x]` 9Y 自检逐项勾选 + REV2 闭环版头 + 实装偏差说明（§10 回填） | ✅ |
| ② | tasks.md 实锤 TR 数 31/31 + Gate B 4 批实锤 | ✅ |
| ③ | Roadmap `docs/dsdb-design-status-and-roadmap.md` OQ12-⑲ ⏳→✅ + OQ12-⑳ 候选项挂出 | ✅ |
| ④ | Project Memory 追加 P41 交付摘要 + 教训 | ✅ |
| ⑤ | 红线审计（`git diff --stat` 仅 `fulltext/cluster/groupby/` 5 新类 + 6 测试；P30/P34/P38/P39/pom 0 改） | ✅ |

---

## Stage 5: 交付（commit + push，遵循「做完一个任务就提交并推送」）
- [ ] `git add` 仅 P41 相关（5 新类 + 6 测试 + spec/tasks + Roadmap）
- [ ] commit message 对齐仓库风格
- [ ] push 至 `origin main`
