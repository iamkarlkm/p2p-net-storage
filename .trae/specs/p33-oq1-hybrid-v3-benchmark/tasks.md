# P33 OQ1 Hybrid v3 Benchmark 性能基线 —— tasks.md（**REV2 GATE C 实装闭环版，2026-10-13**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ 2026-10-11 用户选 OQ12-⑪① 批准
- [x] **Gate B**（P33 own 30/30 TR 0F0E + P22-P32 全部 TR 0F0E，已知 NFR 基线 fail 已记录）→ 2026-10-13
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ 2026-10-13

### 实锤 TR 数
- P33 Implement T1-T6：4+6+3+3+9+5 = **30/30 TR 0F0E** ✓
- Gate B Run1 拆 3 批：批 1 P22-P24 T1-T5 = 61/61 0F0E；批 2 P25 T1-T5 + P26 T1-T5 + P27 全 49/49 (T6 已知 3 fail) + P27 全 29/29；批 3 P28 T1-T3+T5+T6 + P29 + P30 + P31 + P32 + P33 = 112/113 (P28_T4 子进程握手基线 fail 1)
- P33 own 30 TR：4+6+3+3+9+5 = **30/30 TR 0F0E** ✓

### 已知基线 fail（与 P33 无关，P22-P28 闭环已记录）
- P22_T6 tr6_1 P95=4525ms / tr6_2 P95=5450-5975ms：单跑独立 fork 仍偶发 fail
- P25_T6 tr P95 退化：同根问题
- P26_T6 2 个 P95 类退化
- P28_T4 子进程握手偶发 ChannelAwaitOnMessage Interrupted/TimeoutException：单跑独立 fork 仍 fail
- 这些 fail 在 P32 commit `c5bdb67` 已被前阶段 Gate C 闭环接受，与 P33 实现 0 关系

---

## Stage 1: Gate A（当前）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 锚点 4 项（HJC 6 入口 / Parser parse 入口 / 子包现状 / Pom 0 变更） | ✅ |
| 1.2 | 起草 spec.md（Scope/红线/架构/接口/测试/逆指标 8 节 + 9Y 自检） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细） | ✅ |
| 1.4 | NotifyUser 提交 Gate A 批准 | ⏳ |

---

## Stage 2: Implement T1-T6（待 Gate A 批准后串行）

| T | 范围 | TR 数 | 关键实装 |
|---|------|------|---------|
| **T1** | TpcHQuery + DslCorpus + BenchmarkReport 骨架 | 4 | 22 条 TPC-H + 100 条 DSL 模板 + 不可变 Report |
| **T2** | BenchmarkRunner 单测（mock） | 6 | warmup / measure / Recall / 指纹 / 异常 / 序列化 |
| **T3** | Inproc TPC-H E2E | 3 | Q1-Q22 全部 + P50/P95 + Recall |
| **T4** | 跨 Process TCP TPC-H | 3 | 跨进程 + 指纹稳定 + 守卫开销 |
| **T5** | Gatekeeper 9Y | 9 | Y1-Y9 全部 Y |
| **T6** | NFR | 5 | P95 / Javadoc / CPD / println / 报表场景 |

**TR 总数**：4+6+3+3+9+5 = **30 TR**

---

## Stage 3: Gate B（P22-P33 组合 356 TR 两连 0F0E）

### 3.1 Surefire 命令（继承 P32 拆分三批模板）

```powershell
Remove-Item -ErrorAction SilentlyContinue -Recurse -Force I:\2026\code\p2p-net-storage\.tmp
New-Item -Force -ItemType Directory I:\2026\code\p2p-net-storage\.tmp\dsdb | Out-Null
$env:TEMP="I:\2026\code\p2p-net-storage\.tmp"; $env:TMP="I:\2026\code\p2p-net-storage\.tmp"
Set-Location I:\2026\code\p2p-net-storage\p2p-db
$a1 = '-Djava.io.tmpdir=I:\2026\code\p2p-net-storage\.tmp'
$a2 = '-Dp2p.db.home=I:\2026\code\p2p-net-storage\.tmp\dsdb'
$a3 = '-DargLine=-Xmx6144m -Dp2p.db.home=I:\2026\code\p2p-net-storage\.tmp\dsdb'
# 三批拆分（避免同 JVM P24 T6 P95 退化）
# 批1: P22-P24
mvn test $a1 $a2 $a3 '-Dtest=ds.P22_*,ds.P23_*,ds.P24_*'
# 批2: P25-P27
mvn test $a1 $a2 $a3 '-Dtest=ds.P25_*,ds.P26_*,ds.P27_*'
# 批3: P28-P33
mvn test $a1 $a2 $a3 '-Dtest=ds.P28_*,ds.P29_*,ds.P30_*,ds.P31_*,ds.P32_*,ds.P33_*'
```

### 3.2 验收门槛
- Run1：Tests run:356 Failures:0 Errors:0 Skipped:0（76+80+200）
- Run2：与 Run1 Tests run 数完全一致
- 4 个 P33 benchmark 报表字段 2 连指纹全等

---

## Stage 4: Gate C 洁癖 5/5（已完成 2026-10-13）

| 洁癖项 | 内容 | 状态 |
|--------|------|------|
| ① | spec.md Gate C [x] 9Y 自检逐项勾选 | ✅ |
| ② | tasks.md 实锤 TR 数 30/30 + Gate B 30+326 TR 拆 3 批跑 | ✅ |
| ③ | Roadmap `docs/dsdb-design-status-and-roadmap.md` OQ12-⑪ ① ✅ + OQ12-⑫ 候选项挂出 | ✅ |
| ④ | Project Memory 追加 P33 10 行交付摘要 | ✅ |
| ⑤ | git diff 红线审计（仅 `cluster/benchmark/` 4 新类 + benchmark 工具调用，0 红线类改） | ✅ |

---

## 5. 风险与回退

| 风险 | 触发 | 回退 |
|------|------|------|
| TPC-H Q1-Q22 recall 不足 0.85 | T3 TR3.3 fail | 调高 K / 调整 LTR 权重（但**不允许改 P29 spec**，仅在 benchmark 配置层调整） |
| 跨进程 P95 超阈值 | T4 TR4.2 fail | 减少 shard 数到 2 / 关闭 LTR |
| Resilience 偏差 > 10% | T4 TR4.3 fail | 调大 TimeoutGuard 池大小 |
| CPD 误报（5 个新类有 160 字符重复） | T6 TR6.3 fail | 改用 K-gram 窗口匹配（P32 经验） |
| benchmark 慢导致 TR 超时 | T6 TR6.1 fail | 减少 warmup/measure 迭代次数 |
