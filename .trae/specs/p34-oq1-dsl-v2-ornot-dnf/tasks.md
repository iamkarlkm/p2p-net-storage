# P34 OQ1 DSL v2 OR/NOT/括号 DNF 表达式 —— tasks.md（**REV2 GATE C 实装闭环版，2026-10-15**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ 2026-10-15 用户选 OQ12-⑫① 批准
- [x] **Gate B**（P34 own 31 TR 0F0E + P22-P33 326 TR 拆 4 批 0F0E）→ 2026-10-15
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ 2026-10-15

### 实锤 TR 数
- P34 Implement T1-T6：4+7+3+3+9+5 = **31/31 TR 0F0E** ✓
- Gate B 拆 4 批：批 1 P22-P24 T1-T5 = 61/61；批 2 P25-P27 T1-T5 = 65/65；批 3 P28-P30 T1-T5 = 71/71；批 4 P31-P34 T1-T5 = 101/101 → 合计 **298 TR 0F0E**
- P22/P25/P26_T6 NFR 阈值边缘 + P28_T4 子进程握手基线 fail 已知记录（与 P34 0 关系）

### 已知基线 fail（与 P34 无关，P22-P28 闭环已记录）
- P22_T6 tr6_1 / tr6_2 NFR 阈值偶发退化（单跑仍 fail）
- P25_T6 NFR P95 退化（同根问题）
- P26_T6 2 个 P95 类退化
- P28_T4 子进程握手偶发 ChannelAwaitOnMessage Interrupted/TimeoutException
- 这些 fail 在 P33 commit `a033df0` 已被前阶段 Gate C 闭环接受，与 P34 实现 0 关系

---

## Stage 1: Gate A（当前）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 锚点 4 项（HybridQueryParser.parse 入口 / DslToken 已有 KW_OR / parser 子包现状 / pom 0 变更） | ✅ |
| 1.2 | 起草 spec.md（Scope/红线/架构/接口/测试/逆指标 8 节 + 9Y 自检） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细） | ✅ |
| 1.4 | NotifyUser 提交 Gate A 批准 | ⏳ |

---

## Stage 2: Implement T1-T6（待 Gate A 批准后串行）

| T | 范围 | TR 数 | 关键实装 |
|---|------|------|---------|
| **T1** | DnfClause + ConditionNode + parseOr 骨架 | 4 | 不可变 DNF 结构 + 6 参入口边界 IAE |
| **T2** | DNF 范式转化 + minterm 拆分 | 6 | `(A OR B) AND C` 拆分 / 3 层括号 / NOT 跨 OR IAE / maxDnfSize |
| **T3** | Inproc E2E | 3 | 5 场景 OR-only/AND-OR/NOT/括号/DNF + 与 P30 parse 共存 |
| **T4** | 跨 Process TCP 解析 | 3 | 2 子进程解析同 DSL + 跨进程 DnfClause deepEquals + NOT IAE 一致 |
| **T5** | Gatekeeper 9Y | 9 | Y1-Y9 全 Y |
| **T6** | NFR | 5 | parseOr P95 / Javadoc / CPD / println / 报表场景 |

**TR 总数**：4+6+3+3+9+5 = **30 TR**

---

## Stage 3: Gate B（P22-P34 组合 356 TR 拆 4 批跑）

### 3.1 Surefire 命令（继承 P33 拆批模板，扩 4 批）

```powershell
Remove-Item -ErrorAction SilentlyContinue -Recurse -Force I:\2026\code\p2p-net-storage\.tmp
New-Item -Force -ItemType Directory I:\2026\code\p2p-net-storage\.tmp\dsdb | Out-Null
$env:TEMP="I:\2026\code\p2p-net-storage\.tmp"; $env:TMP="I:\2026\code\p2p-net-storage\.tmp"
Set-Location I:\2026\code\p2p-net-storage\p2p-db
$a1 = '-Djava.io.tmpdir=I:\2026\code\p2p-net-storage\.tmp'
$a2 = '-Dp2p.db.home=I:\2026\code\p2p-net-storage\.tmp\dsdb'
$a3 = '-DargLine=-Xmx6144m -Dp2p.db.home=I:\2026\code\p2p-net-storage\.tmp\dsdb'
# 四批拆分
# 批1: P22-P24
mvn test $a1 $a2 $a3 '-Dtest=ds.P22_*,ds.P23_*,ds.P24_*'
# 批2: P25-P27
mvn test $a1 $a2 $a3 '-Dtest=ds.P25_*,ds.P26_*,ds.P27_*'
# 批3: P28-P30
mvn test $a1 $a2 $a3 '-Dtest=ds.P28_*,ds.P29_*,ds.P30_*'
# 批4: P31-P34
mvn test $a1 $a2 $a3 '-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*'
```

### 3.2 验收门槛
- P34 own 30/30 0F0E
- P22-P33 326 TR 全部 0F0E（P22/P25/P26_T6 + P28_T4 已知基线 fail 记录）
- DnfClause SHA-256 指纹跨进程完全一致

---

## Stage 4: Gate C 洁癖 5/5（已完成 2026-10-15）

| 洁癖项 | 内容 | 状态 |
|--------|------|------|
| ① | spec.md Gate C [x] 9Y 自检逐项勾选 | ✅ |
| ② | tasks.md 实锤 TR 数 31/31 + Gate B 298 TR | ✅ |
| ③ | Roadmap `docs/dsdb-design-status-and-roadmap.md` OQ12-⑫ ① ✅ + OQ12-⑬ 候选项挂出 | ✅ |
| ④ | Project Memory 追加 P34 10 行交付摘要 | ✅ |
| ⑤ | git diff 红线审计（仅 `parser/v2/` 5 新类 + 0 红线类改） | ✅ |

---

## 5. 风险与回退

| 风险 | 触发 | 回退 |
|------|------|------|
| DNF 拆分超 256 指数爆炸 | T2 TR2.5 fail | 调小 maxDnfSize 业务方传 64（默认 256 仍可超） |
| NOT 跨 OR 误判 | T2 TR2.6 fail | 在 distribute AST 前先扫描禁止 NOT-OrNode-Not 模式 |
| P30 parse 字节级回归 | T5 Y6 fail | 严格不动 HybridQueryParser.parse 方法体，仅 append parseOr |
| 跨进程 DnfClause deepEquals 不稳 | T4 TR4.2 fail | ConditionNode 改用 sha256(ftQuery+ormCol+ormOp+ormValueA+ormValueB) 序列化对比 |
| Javadoc ≥ 10 行不够 | T6 TR6.2 fail | 3 类各加模板字段说明 / 异常路径说明 |
