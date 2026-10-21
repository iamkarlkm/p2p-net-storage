# P38 OQ1 DSL v3 聚合函数 —— tasks.md（**REV2 GATE C 实装闭环版，2026-10-21**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ 用户 VERBATIM 批准（OQ12-⑯①）
- [x] **Gate B**（P38 own 31/31 TR 0F0E + P22-P38 拆 4 批 510/511 + 1 已知基线 fail 沿用）→ 实跑闭环
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ 闭环

### 实跑 TR 数（Gate B 回填）
- P38 Implement T1-T6：5+6+3+3+9+5 = **31 TR（31/31 0F0E）**
- Gate B 拆 4 批：批 1（P22-P24）= **76**；批 2（P25-P27）= **80**；批 3（P28-P30）= **100（99 0F0E + 1 已知基线 fail）**；批 4（P31-P38）= **255 全部 0F0E**；合计 **511 = 510 0F0E + 1 已知基线 fail**
- 已知基线 fail 沿用：**P28_T6.tr64_noBarePrintAndGte5NullGuards**（P34 交付 `DnfParserHelperMain` 裸 System.out，跨进程 stdout 协议必需，与 P38 无关）

---

## Stage 1: Gate A（✅ 已闭环）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 5 锚点（P30 Parser 4 参 + DslToken 24 值 + KEYWORDS 13 项落 IDENT / P34 DnfParser.parseOr 6 参 + DnfClause + ConditionNode / ORM 无聚合能力 / HJC HybridHit ctor=2 / ZeroLeak3=3 实锤） | ✅ |
| 1.2 | 起草 spec.md（Scope/红线/架构/接口/测试/逆指标/风险/Probe 8+1 节 + 9Y 自检） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细 + Gate B 拆批命令） | ✅ |
| 1.4 | NotifyUser 提交 Gate A 批准 | ✅ |

### Probe 结论（5 锚点实锤）
1. **P30 Parser**（`parser/HybridQueryParser.java`）：`parse(entityClass, dsl, weightMap, ltrMap)` 4 参 public static（L51-131），纯解析；`DslToken` enum 24 值（L17-43，含 `KW_OR` 占位）；`DslTokenizer` 静态 `KEYWORDS` map 13 项（L26-41），未识别词 → `IDENT`（L233-237）。**结论：`AGG`/`COUNT`/`SUM` 等未注册关键字 → 天然落 `IDENT`，P38 零改词法复用**。
2. **P34 Parser**（`parser/v2/`）：`DnfParser.parseOr(Class, String, Map, Map, int maxDnfSize, DnfReturnMode)` 6 参 public static（L79-124）→ `DnfClause`（`List<List<ConditionNode>>`）；`DnfClause` 8 IAE + `fingerprint()` + `deepEquals`；`ConditionNode` 4 Kind（FT/ORM/IS_NULL/IS_NOT_NULL）+ 8 IAE。**结论：表达式段可直接调 `DnfParser.parseOr(..., DNF_ONLY)` 复用 DNF 拆分**。
3. **ORM 无聚合能力**：`QueryWrapper` 仅 WHERE/ORDER/IN-子查询构建（0 聚合方法）；`DsTableAdapter` 仅序列化 + `getId`；全仓仅 `OrmCountServerHandler` 走 `GenericManager.count(w)` 提供远程 COUNT。**结论：P38 只产计划，执行/合并归业务方（MVP 边界）**。
4. **HJC 结果类型**：`HybridJoinClient`（`cluster/join/`）9 入口全返回 `List<HybridHit<T>>`；`HybridHit` public final 5 字段 + `getDeclaredConstructors().length == 2`。**结论：P38 不改 HJC / HybridHit；计划与结果类型解耦**。
5. **ZeroLeak3=3 实锤**：fulltext 根包直接 `.java` = {FulltextSegmenter, FulltextStore, TermDictStore} 恰 3 个。**结论：新增 5 类须 100% 放 `cluster/parser/v3/` 子包**。

---

## Stage 2: Implement T1-T6（✅ 串行 T1→T6 完成，31/31 TR 0F0E）

| T | 范围 | TR 数 | 关键实装 | 目标测试类 |
|---|------|------|---------|--------|
| **T1** | AggFn + AggSpec skeleton | 5 | AggFn 5 值（COUNT/SUM/AVG/MIN/MAX + 唯一 code 'C','S','A','M','X'）+ code 往返（`code()`↔`fromCode`）+ `fromName` 大小写不敏感 + 4 IAE（fromCode 非法 / fromName null / fromName 空 / fromName 非法名）；AggSpec 6 IAE（fn null / column null / blank / 含空白 / 含非法字符 / 保留字 AGG）+ equals/hashCode/toString | `P38_T1_AggFnSpecTest` **5/5** |
| **T2** | MintermAggTask + AggQueryPlan | 6 | task 5 IAE（index<0 / minterm null / empty / 含 null 节点 / aggSpec null）+ 计划 `tasks()` 笛卡尔积 N=(minterms×aggs) 顺序=minterm 序×agg 声明序 + `taskCount()` + `fingerprint()` SHA-256 稳定（同 clause+aggs → 同 64-hex）+ `deepEquals` + aggs 不可变（5 IAE：clause null / aggs null / aggs 含 null / aggs.size>MAX_AGG / unmodifiable） | `P38_T2_TaskPlanTest` **6/6** |
| **T3** | AggParser 解析 | 3 | `splitAtAgg` 括号深度 0 + 跳过单引号字符串防误命中（字符串内 `'AGG'`/脚本内括号） + 5 aggFn IDENT 解析 + 边界 IAE（空表达式段 / 非法函数名 / 非法列 / AGG 后多余 token / maxAggCount 越界 / 无 AGG → aggs=空且 taskCount=0） | `P38_T3_AggParserTest` **3/3** |
| **T4** | Inproc E2E + 跨进程 fingerprint | 3 | DSL→计划→N task 业务模拟聚合（对每 task 手写 WHERE 条件查询 + 自算聚合，验证 N 条与笛卡尔积逐条全等）+ `(A OR B) AND C AGG(...)` 拆分 N 对（含顺序 + minterm 节点全等）+ 跨进程 `AggQueryPlan` fingerprint 一致（64-hex SHA-256）/ deepEquals=true | `P38_T4_E2eAggTest` **3/3** |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3 反射收集 fulltext 根包 top-level count=3（5 新类全在 v3 子包）+ Y2 pom diff=0/0 CNFE（0 新 dep/plugin）+ Y3 bucketAccessCounter[1025] 0 污染 + Y4 P30/P34 源码 0 改 grep（HybridQueryParser/DslToken/DslTokenizer/DnfParser/DnfClause/ConditionNode）+ Y5~Y8 公式/ctor=2/ShardTransport 5 方法不变 + Y9 0 TODO/FIXME/HACK | `P38_T5_Gatekeeper9YTest` **9/9** |
| **T6** | NFR | 5 | parse P95 ≤ 阈值（大 DNF + 多 agg） + 5 类 Javadoc ≥ 10 行 + CPD ≤ 1 + 0 println + 显式 IAE 守卫数 ≥ 8 | `P38_T6_NFR_Test` **5/5** |

**TR 总数目标**：5+6+3+3+9+5 = **31 TR**

---

## Stage 3: Gate B（待跑，P22-P38 拆 4 批）

### 3.1 Surefire 命令（继承 P37 拆批模板，实跑加 `-pl p2p-db` 规避聚合模块无测试）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
# 批1: P22-P24
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P22_*,ds.P23_*,ds.P24_*'
# 批2: P25-P27
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P25_*,ds.P26_*,ds.P27_*'
# 批3: P28-P30
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P28_*,ds.P29_*,ds.P30_*'
# 批4: P31-P38 (含 P38 own)
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*,ds.P38_*'
```

### 3.2 验收门槛（实跑回填）
- [x] P38 own **31/31** 0F0E（独立单跑 + 批 4 内含跑 双实锤）
- [x] 批 1（P22-P24）76 0F0E + 批 2（P25-P27）80 0F0E + 批 4（P31-P38）255 0F0E，0 P38 引入 fail
- [x] 批 3（P28-P30）100 TR 含 1 已知基线 fail（P28_T6.tr64）
- [x] Gate B 总计 = **511 TR（510 0F0E + 1 已知基线 fail）**（实跑回填，禁止沿用估计值，见 P37 教训3）
- [x] 拆分正确性：`AggQueryPlan.tasks()` 与手写笛卡尔积逐条全等（顺序 + minterm 节点全等）——`P38_T4`
- [x] 跨进程确定性：同 DSL 两进程 fingerprint 一致（64-hex）——`P38_T4`
- [x] 0 副作用：parse 期间 0 触 ORM/FT/DHQC、0 改 `bucketAccessCounter`——`P38_T3` / `P38_T5`

---

## Stage 4: Gate C 洁癖 5/5（✅ 闭环）

| 洁癖项 | 内容 | 状态 |
|--------|------|------|
| ① | spec.md Gate C [x] 9Y 自检逐项勾选 + REV2 闭环版头 + 实装偏差说明（§10） | ✅ |
| ② | tasks.md 实锤 TR 数 31/31 + Gate B 4 批实锤 | ✅ |
| ③ | Roadmap `docs/dsdb-design-status-and-roadmap.md` OQ12-⑯ ⏳→✅ + OQ12-⑰ 候选项挂出 | ✅ |
| ④ | Project Memory 追加 P38 交付摘要 | ✅ |
| ⑤ | 红线审计（`git diff --stat` 仅 `fulltext/cluster/parser/v3/` 5 新类 + 测试文件；P30/P34/pom 0 改） | ✅ |

---

## Stage 5: 交付（commit + push，遵循「做完一个任务就提交并推送」）
- [x] `git add` 仅 P38 相关（5 新类 + 6 测试 + spec/tasks + 2 文档）
- [x] commit message 对齐仓库风格
- [x] push 至 `origin main`
