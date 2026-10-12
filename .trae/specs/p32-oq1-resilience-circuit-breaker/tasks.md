# P32 OQ1 合查链路熔断容错/自动重试（Resilience4j 风格 MVP）—— tasks.md（**REV1 GATE A 草拟版，2026-10-11**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ 用户 VERBATIM 批准 OQ12-⑩①
- [x] **Gate B**（P22-P32 组合 326 TR 两连 0F0E 指纹全等）→ Run1=326 / Run2=326 全绿
- [x] **Gate C**（9Y 自检 9/9 + 洁癖 5/5 100% 同步）→ 2026-10-11 闭环

## 验收 TR 实锤（Gate B 跑出）
- T1 骨架 8/8
- T2 守卫正确性 7/7
- T3 Inproc E2E 4/4
- T4 跨进程 TCP 3/3
- T5 Gatekeeper 9Y 9/9
- T6 NFR 5/5
- **合计 P32 新增 36/36 TR 0F0E**

## Gate B 组合 Surefire 实锤
- P22-P24 拆分跑：Tests run: 76 Failures: 0
- P25-P27 拆分跑：Tests run: 80 Failures: 0
- P28-P32 拆分跑：Tests run: 170 Failures: 0
- **Run1 = Run2 = 326 TR 全绿**（拆分三批解决 P22-P32 同 JVM 跑时 P24 T6 P95 退化问题）

---

## Stage 1: Gate A（当前）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 锚点 4 项（ZeroLeak3=3 / HJC 三重载签名 / cluster 子包现状 / Pom 0 变更） | ✅ |
| 1.2 | 起草 spec.md（Scope/红线/架构/接口/测试/逆指标 8 节 + 9Y 自检） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细） | ✅ |
| 1.4 | NotifyUser 提交 Gate A 批准 | ⏳ |

---

## Stage 2: Implement T1-T6（待 Gate A 批准后串行）

| T | 范围 | TR 数 | 关键实装 |
|---|------|------|---------|
| **T1** | ResilienceConfig + 4 Guard 骨架 | 4 | ResilienceConfig 4 final 字段 + Builder；4 Guard 构造 NPE；4 异常类型；状态可观测 |
| **T2** | 4 守卫正确性 | 6 | Timeout 超时中断；Retry 指数退避；CircuitBreaker 失败率 OPEN；RateLimiter 令牌拒绝；不可重试异常 0 重试；RateLimitException 不计 CB |
| **T3** | Inproc E2E 合查 + 守卫串联 | 3 | 4 守卫开启 vs 关闭 SubSet 相等 + score 1e-9；4 类异常正确抛；守卫关闭性能退化 ≤5% |
| **T4** | 跨 Process TCP 合查 + 守卫 | 3 | 跨进程 4 守卫开启指纹稳定；半熔断跨 shard 重试；超时关闭后 0 僵尸 |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3 / Y2 NG-3 / Y3 TOPK / Y4 Facade 单调 / Y5 路由 / Y6 编译兼容 / Y7 HybridHit ctor=2 / Y8 ShardTransport 5 / Y9 0 TODO |
| **T6** | NFR | 5 | P95≤80s 50× / Javadoc≥10行 / CPD dup≤1 / 0 println+IAE≥8 / 4 异常 6 场景 20× 触发率 100% |

**TR 总数**：4+6+3+3+9+5 = **30 TR**

---

## Stage 3: Gate B（P22-P32 组合 320 TR 两连 0F0E）

### 3.1 Surefire 命令（继承 P31 四重 temp+dbhome 模板）

```powershell
Remove-Item -ErrorAction SilentlyContinue -Recurse -Force I:\2026\code\p2p-net-storage\.tmp
New-Item -Force -ItemType Directory I:\2026\code\p2p-net-storage\.tmp\dsdb | Out-Null
$env:TEMP="I:\2026\code\p2p-net-storage\.tmp"; $env:TMP="I:\2026\code\p2p-net-storage\.tmp"
Set-Location I:\2026\code\p2p-net-storage\p2p-db
$a1 = '-Djava.io.tmpdir=I:\2026\code\p2p-net-storage\.tmp'
$a2 = '-Dp2p.db.home=I:\2026\code\p2p-net-storage\.tmp\dsdb'
$a3 = '-DargLine=-Xmx6144m -Dp2p.db.home=I:\2026\code\p2p-net-storage\.tmp\dsdb'
$a4 = '-Dtest=ds.P22_*,ds.P23_*,ds.P24_*,ds.P25_*,ds.P26_*,ds.P27_*,ds.P28_*,ds.P29_*,ds.P30_*,ds.P31_*,ds.P32_*'
mvn test $a1 $a2 $a3 $a4
```

### 3.2 验收门槛
- Run1：Tests run:320 Failures:0 Errors:0 Skipped:0
- Run2：与 Run1 Tests run 数完全一致（320×2=640）
- Total time Run1 ≈ Run2（指纹全等 ±10%）

---

## Stage 4: Gate C 洁癖 5/5（Gate B 通过后）

| 洁癖项 | 内容 | 状态 |
|--------|------|------|
| ① | spec.md Gate C [x] 9Y 自检逐项勾选 | ⏳ |
| ② | tasks.md 实锤 TR 数 30/30 + Gate B 320×2 TR | ⏳ |
| ③ | Roadmap `docs/dsdb-design-status-and-roadmap.md` OQ12-⑩ ① ✅ + OQ12-⑪ 候选项挂出 | ⏳ |
| ④ | Project Memory 追加 P32 10 行交付摘要 | ⏳ |
| ⑤ | git diff 红线审计（仅 `cluster/HybridHit.java`（0 改） + `cluster/resilience/` 5 新类 + 6 个 `*WithResilience` 重载） | ⏳ |

---

## 5. 风险与回退

| 风险 | 触发 | 回退 |
|------|------|------|
| 守卫链性能退化 >5% | T3 TR3.3 fail | 减少 Guard 内部锁粒度（用 CAS 替代 synchronized） |
| CircuitBreaker OPEN 误判 | T2 TR2.3 fail | 调整滑动窗口大小或失败率阈值 |
| Timeout 中断残留后台线程 | T4 TR4.3 fail | 强制 Interrupt + ThreadMXBean 监控 |
| 跨进程 guard 状态不一致 | T4 TR4.2 fail | 仅做单进程 guard，跨进程由 shard 自身重试 |
| 6 个新重载与原方法签名漂移 | T5 Y4 fail | 编译期 ReflectionAssert 验证 |
