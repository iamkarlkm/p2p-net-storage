# P36 OQ1 分布式熔断器 + Adaptive 自适应 —— tasks.md（**REV2 GATE C 实装闭环版，2026-10-20**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ 2026-10-16 用户选 OQ12-⑭① 批准
- [x] **Gate B**（P36 own 31/31 TR 0F0E + P22-P35 418 TR 拆 4 批 0F0E + 1 已知基线 fail）→ 2026-10-20
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ 2026-10-20

### 实锤 TR 数
- P36 Implement T1-T6：5+6+3+3+9+5 = **31/31 TR 0F0E** ✓
- Gate B 拆 4 批（合计 **449 TR**：448 0F0E + 1 已知基线 fail）：
  - 批 1 **P22-P24 = 76 TR 0F0E**（P22 25 + P23 25 + P24 26）
  - 批 2 **P25-P27 = 80 TR 0F0E**（P25 25 + P26 25 + P27 30）
  - 批 3 **P28-P30 = 100 TR**（P28 32 + P29 32 + P30 36；99 0F0E + 1 baseline fail）
  - 批 4 **P31-P36 = 193 TR 0F0E**（P31 34 + P32 36 + P33 30 + P34 31 + P35 31 + P36 31）
- 累计指纹锚点：P22-P30 = 256 TR / P22-P31 = 290 / P22-P32 = 326 / P22-P35 = 418（与历史 Gate B 记录连续对齐）

### 已知基线 fail（与 P36 无关，P34/P35 已记录同项）
- **P28_T6.tr64_noBarePrintAndGte5NullGuards**：源码审计扫 `fulltext/cluster` 全树发现 3 处裸 `System.out`，**全部来自 P34 交付的 `DnfParserHelperMain`**（跨进程子进程 stdout 协议必需，非 P36 引入）；P35 Gate B 已记录同一 baseline。
- 本轮 P22_T6 / P25_T6 / P26_T6 NFR 阈值边缘**未复现**（历史偶发退化，本次全 0F0E）；P28_T4 子进程握手本轮 4/4 0F0E。

---

## Stage 1: Gate A（已完成 2026-10-16）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 5 锚点（P32 CircuitBreakerGuard / ResilienceConfig / 3 守卫 / HJC 3 WithResilience 入口 / DsServerDirectory.replicas()） | ✅ |
| 1.2 | 起草 spec.md（Scope/红线/架构/接口/测试/逆指标 8 节 + 9Y 自检） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细） | ✅ |
| 1.4 | NotifyUser 提交 Gate A 批准 | ✅ 用户 OQ12-⑭① 批准 |

---

## Stage 2: Implement T1-T6（已实装 2026-10-20，串行 T1→T6 全部 0F0E）

| T | 范围 | TR 数 | 关键实装 | Commit |
|---|------|------|---------|--------|
| **T1** | DistributedCircuitBreakerState + AdaptiveThresholdController skeleton | 5 | 不可变 POJO（public final 6 字段）+ 5 IAE + SHA-256 fingerprint + Adaptive 4 IAE + AimdLike 算法 | `2fc2056` |
| **T2** | DistributedResilienceConfig + ClusterStateBroadcaster | 6 | 12 字段 Builder（P32 8 + P36 4）+ 4 IAE + broadcast 6 IAE + quorum 边界 + 异常状态广播 | `225c28a` |
| **T3** | Inproc E2E 分布式 CB + Adaptive 调参 | 3 | 状态机切换 + Adaptive 阈值更新 + 失败率反馈 AimdLike 收敛 | `ce4a7b2` |
| **T4** | 跨 Process TCP 分布式 CB 状态广播 | 3 | 2 子进程 state snapshot fingerprint 一致 + Adaptive 阈值收敛 + 失败本地熔断 0 僵尸 | `76eb53c` |
| **T5** | Gatekeeper 9Y | 9 | Y1-Y9 全 Y + HJC append-only 3 分布式守卫入口（34 入口反射验证） | `7855197` |
| **T6** | NFR | 5 | broadcast P95 ≤ 50ms + 5 类 Javadoc ≥10 行 + CPD ≤ 1 + 0 println/IAE ≥ 8 + 4 报表 6 场景 20× 100% | `3e62515` |

**TR 总数实锤**：5+6+3+3+9+5 = **31/31 TR 0F0E**

---

## Stage 3: Gate B（P22-P36 组合拆 4 批跑，已完成 2026-10-20）

### 3.1 Surefire 命令（继承 P35 拆批模板）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
# 四批拆分
# 批1: P22-P24
mvn surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P22_*,ds.P23_*,ds.P24_*'
# 批2: P25-P27
mvn surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P25_*,ds.P26_*,ds.P27_*'
# 批3: P28-P30
mvn surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P28_*,ds.P29_*,ds.P30_*'
# 批4: P31-P36 (含 P32 + P33 + P34 + P35 + P36 own)
mvn surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*'
```

### 3.2 验收门槛（实锤）
- [x] P36 own **31/31** 0F0E
- [x] 批 1/2/4 共 **349 TR 0F0E**（76 + 80 + 193）
- [x] 批 3 **100 TR**：99 0F0E + 1 已知基线 fail（P28_T6.tr64，P34/P35 已记录）
- [x] 合计 **449 TR**：448 0F0E + 1 known baseline fail（0 P36 引入 fail）
- [x] DistributedCircuitBreakerState fingerprint 跨进程完全一致（T4 3/3 0F0E）
- [x] Adaptive 调参 100% 收敛（T3/T4 0F0E）

---

## Stage 4: Gate C 洁癖 5/5（已完成 2026-10-20）

| 洁癖项 | 内容 | 状态 |
|--------|------|------|
| ① | spec.md Gate C [x] 9Y 自检逐项勾选 + REV2 闭环版头 | ✅ |
| ② | tasks.md 实锤 TR 数 31/31 + Gate B 449 TR（1 baseline fail） | ✅ |
| ③ | Roadmap `docs/dsdb-design-status-and-roadmap.md` OQ12-⑭ ① ✅ + OQ12-⑮ 候选项挂出 | ✅ |
| ④ | Project Memory 追加 P36 交付摘要 | ✅ |
| ⑤ | git diff 红线审计（`169ac39..HEAD`：仅 `resilience/distributed/` 5 新类 + HJC append-only 112+/0-；`ResilienceConfig.java` 0 改；pom 0 改；ShardRouter/ShardTransport 0 改） | ✅ |

---

## 5. 风险与回退

| 风险 | 触发 | 回退 |
|------|------|------|
| 分布式 CB 状态广播延迟高 | T4 跨进程 fingerprint fail | at-least-once 重试 + 超时本地熔断 |
| Adaptive 调参抖动 | T3 失败率波动大 → threshold 频繁变 | 适配 ≤0.05/cycle + min/max 边界 |
| quorum 永远达不成 | T2 quorum > N | DistributedResilienceConfig.build 严格校验 `quorum ≤ replicas().length` |
| 跨节点广播风暴 | T4 高频广播 | `distributedKpiSampleIntervalMs ≥ 1000ms` 硬下限 |
| HJC 3 WithDistributedResilience 与 P32 WithResilience 行为分裂 | T3 fail | 严格委托 P32 WithResilience → 仅在外层叠加 DistributedCB + Adaptive |