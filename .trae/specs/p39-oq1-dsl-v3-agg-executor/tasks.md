# P39 OQ1 DSL v3 聚合执行/合并引擎 —— tasks.md（**REV2 GATE C CLOSED，2026-10-23**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ 2026-10-21 用户 VERBATIM 批准（OQ12-⑰③）
- [x] **Gate B**（P39 own 31/31 TR 0F0E + P22-P39 拆 4 批 + 已知基线 fail 沿用）→ 实跑完成（542 TR）
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ 完成

### 实跑 TR 数（Gate B 回填）
- P39 own = **31/31 TR 0F0E**（T1 5 + T2 6 + T3 3 + T4 3 + T5 9 + T6 5）
- Gate B 拆 4 批 = **542 run / 539 0F0E**（批 1 P22-P24 = 76/76；批 2 P25-P27 = 80 run（2 项环境计时 fail：P26_T6.tr61/tr62）；批 3 P28-P30 = 100 run（1 已知基线 fail `P28_T6.tr64`）；批 4 P31-P39 = 286/286）

---

## Stage 1: Gate A（起草完成，已签批）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 7 锚点（P38 AggQueryPlan / P34 ConditionNode op 集 / QueryWrapper 谓词 / GenericManager.findList 双通道 / PK 可用性 / P38_T5.Y2 「parser/v3 文件数=5」硬约束 / ZeroLeak3=3） | ✅ |
| 1.2 | 起草 spec.md（Scope/红线 R1-R10/架构 6 类 + union 数据流/接口契约/测试/逆指标/风险/Probe 7 锚点 + 9Y 自检） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细 + Gate B 拆批命令） | ✅ |
| 1.4 | NotifyUser 提交 Gate A 批准 | ✅（2026-10-21 VERBATIM 批准）|

### Probe 结论（7 锚点实锤，详见 spec.md §8）
1. **P38 `AggQueryPlan`**：`clause` / `aggs` / `tasks()` / `taskCount()` / `MAX_AGG=8` 齐备 → 执行只需 `clause` + `aggs`，0 改。
2. **P34 `ConditionNode`**：`Kind` 4 选一 + op ∈ {EQ,NE,GT,GE,LT,LE,BETWEEN,IN,IS,LIKE} → `toWrapper` 全覆盖，FT 显式拒绝。
3. **`QueryWrapper`**：`eq/ne/gt/ge/lt/le/between/in/isNull/isNotNull` 全有 → minterm → WHERE 0 改可表达。
4. **`GenericManager.findList(w)`**（L1935）/ **`count(w)`**（L1973）：本地 + 分布式双通道同签名 → 行源实装一套复用。
5. **PK 可用性**：`DsTableAdapter.getId()`(L99) + `getTable` 链路 `obj.setId(rowId)`(DsDatabaseLocal L3174) → union 按 PK 去重可行（R7）。
6. **P38 基线硬约束**：`P38_T5.Y2`(L85) 断言 `parser/v3` 源文件数 **严格 = 5** → P39 6 新类**必须**落**新子包** `cluster/agg/`。
7. **ZeroLeak3=3 实锤**：fulltext 根包 top-level 恰 3 类 → 新子包方案天然合规。

---

## Stage 2: Implement T1-T6（串行 T1→T6，实锤 31/31 TR 0F0E）

| T | 范围 | TR 数 | 关键实装 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | AggValue + AggResult skeleton | 5 | AggValue 4 IAE（spec null / count<0 / sum NaN·Inf / value NaN·Inf）+ `count/sum/value` 语义 + equals/hashCode/toString；AggResult 3 IAE（values null / 含 null / unionRowCount<0）+ 顺序 = aggs 声明序 + `value(int)` 越界 IOOBE + `fingerprint()` 64-hex 稳定 + `deepEquals` + values unmodifiable | `P39_T1_AggValueResultTest` **5/5** |
| **T2** | AggExecutor 单 minterm | 6 | 内存行源 lambda：COUNT（非 NULL 计数）/ SUM（全 NULL → 0.0 + count=0）/ AVG = Σsum/Σcount / MIN / MAX / 4 IAE（plan·source·cols null + 未知列无 extractor）/ 2 ISE（fetch 返回 null、行 id null）+ `plan.aggs` 空 → 空结果 短路 0 fetch | `P39_T2_ExecutorSingleMintermTest` **6/6** |
| **T3** | union 去重（多 minterm 重叠） | 3 | `(a = 1 OR b = 2) AND (a = 1 OR c = 3)` 4 minterm（`[a1,a1] [a1,c3] [b2,a1] [b2,c3]` 重叠）去重后 vs 手写 distinct-PK 真值逐聚合全等 + minterm 顺序无关（同值同 fingerprint）+ `unionRowCount` = distinct PK 数 | `P39_T3_UnionDedupTest` **3/3** |
| **T4** | E2E + 跨进程 fingerprint | 3 | 真实 `DsDatabaseLocal` + `GenericManagerRowSource`（temp home + forceReset + 唯一表名后缀隔离，继承 P31/P34 模板）：5 聚合 vs 手算真值（含 NULL 行）+ `AggParser.parse` → `AggExecutor.execute` 全链路 + 跨进程 fingerprint 一致（子 JVM spawn 复现 64-hex） | `P39_T4_E2eAggExecutorTest` **3/3** |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3=3 **且** `parser/v3` 源文件数 = 5（双保）+ agg 子包 6 类齐；Y2 NG-3（agg 目录 forbidden 引用 + import 白名单 + pom banned 0 命中）；Y3 TOPK（0 `bucketAccessCounter` / 0 `SearchOptions`）；Y4 Facade 单调不减（`AggParser.parse` 6 参反射 + `DnfParser.parseOr` 6 参 + `DslToken.values().length == 25`）；Y5 路由 0 改；Y6 P21~P38 + agg 6 类 `Class.forName`；Y7 HybridHit ctor=2；Y8 ShardTransport 5 方法；Y9 0 TODO/FIXME/HACK | `P39_T5_Gatekeeper9YTest` **9/9** |
| **T6** | NFR | 5 | execute P95 ≤ 2s（8 minterm × 2 agg × 1k 行）+ 6 类 Javadoc ≥ 10 行 + CPD 0 重复块（160 字符窗口）+ 0 `System.out.println`/`printStackTrace` + IAE 显式守卫 ≥ 8 + 6 场景 100%（重叠去重 / 空集不抛 / NULL 跳过 / AggMerger 跨 shard = 单机 / fingerprint 跨进程一致 / agg 序列错配 IAE） | `P39_T6_NFR_Test` **5/5** |

**TR 总数实锤**：5+6+3+3+9+5 = **31 TR 全 0F0E**

---

## Stage 3: Gate B（实跑完成，P22-P39 拆 4 批）

### 3.1 Surefire 命令（继承 P37/P38 模板，实跑加 `-pl p2p-db`；改源码/测试后先 `test-compile`）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
# 批1: P22-P24
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P22_*,ds.P23_*,ds.P24_*'
# 批2: P25-P27
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P25_*,ds.P26_*,ds.P27_*'
# 批3: P28-P30
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P28_*,ds.P29_*,ds.P30_*'
# 批4: P31-P39 (含 P39 own)
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*,ds.P39_*'
```

### 3.2 验收门槛（实跑回填）
- [x] P39 own **31/31** 0F0E ✅
- [x] 批 1 / 批 4 全绿（76/76 + 286/286）；批 2 仅 2 项环境计时 fail（P26_T6.tr61 149487ms>120000ms / tr62 31167ms>30000ms，该机整体比基准慢 2-3×，非 P39 回归）；批 3 仅已知基线 fail `P28_T6.tr64`（P28 时期 `DnfParserHelperMain` 裸 `System.out` 为跨进程协议必需，预期勿改）
- [x] Gate B 总计数字**实跑回填**：**542 run / 539 0F0E / 3 项非通过（2 环境计时 + 1 已知基线）**（批 1=76 + 批 2=80 + 批 3=100 + 批 4=286）
- [x] union 正确性：重叠 minterm 去重后 = 手写 distinct-PK 真值（逐聚合全等）——`P39_T3` 3/3 ✅
- [x] 合并正确性：`AggMerger.merge(逐 shard 部分结果)` = 单进程一次性 execute（逐聚合全等）——`P39_T6` 5/5 ✅
- [x] 跨进程确定性：同 DSL + 同数据 → 同 `fingerprint()`（64-hex）——`P39_T4` 3/3 ✅
- [x] 0 副作用：execute 期间 0 触 FulltextStore / 0 改 `bucketAccessCounter` / 0 写库——`P39_T5` 9/9 ✅

---

## Stage 4: Gate C 洁癖 5/5（完成）

| 洁癖项 | 内容 | 状态 |
|--------|------|------|
| ① | spec.md Gate C [x] 9Y 自检逐项勾选 + REV2 闭环版头 + 实装偏差说明（§10） | ✅ |
| ② | tasks.md 实锤 TR 数 31/31 + Gate B 4 批实锤 | ✅ |
| ③ | Roadmap `docs/dsdb-design-status-and-roadmap.md` OQ12-⑰ ⏳→✅ + OQ12-⑱ 候选项挂出 | ✅ |
| ④ | Project Memory 追加 P39 交付摘要 + 教训 | ✅ |
| ⑤ | 红线审计（`git diff --stat` 仅 `fulltext/cluster/agg/` 6 新类 + 6 测试；P30/P34/P38/pom 0 改） | ✅ |

---

## Stage 5: 交付（commit + push，遵循「做完一个任务就提交并推送」）
- [x] `git add` 仅 P39 相关（6 新类 + 6 测试 + spec/tasks + Roadmap）
- [x] commit message 对齐仓库风格
- [x] push 至 `origin main`
