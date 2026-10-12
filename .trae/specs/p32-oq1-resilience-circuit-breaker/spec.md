# P32 OQ1 合查链路熔断容错/自动重试（Resilience4j 风格 MVP）—— spec.md（**REV2 GATE C 实装闭环版，2026-10-11**）

## Gate C 9Y 自检清单（每 Y 必填 [x] 通过；任一未过 → 退 Gate A 整改）
- [x] Y1 ZeroLeak3=3：fulltext 根包仍 = {FulltextSegmenter, FulltextStore, TermDictStore}，P32 0 新增根包 top-level 类
- [x] Y2 NG-3 零依赖：P32 0 新增 netty/grpc/p2p.client 根包引用 + pom.xml 0 新增 dep/plugin
- [x] Y3 TOPK 铁律：合查后裁剪调用 K≤topK 限制恒成立，守卫开启/关闭对最终结果集 SubSet 相等
- [x] Y4 Facade 单调不减：HJC/DHQC/Parser 全部公开签名 1 字不删改，append-only 新增 3 个 HJC 守卫重载 + 5 个新类（ResilienceConfig+4Guard）
- [x] Y5 路由公式：UNIT/DOC 路由公式（P24 钉死）守卫开启/关闭下 P50/P95 偏差 ≤10%
- [x] Y6 P30/P31 编译兼容：P30 Parser 解析输出 ParsedHybridQuery 字节级一致；P31 JoinSpec/JoinIdResolver 0 改
- [x] Y7 HybridHit 构造：constructors.length 仍 =2；HJC postFilterJoin 链路 0 改
- [x] Y8 ShardTransport 5 方法：FulltextStore 内嵌 ShardTransport 5 方法签名 1 字不删改
- [x] Y9 0 TODO/FIXME/HACK：守卫实现必须 100% 已实装，无占位符

---

## 1. Scope（用户单选 OQ12-⑩ 候选项 ①，VERBATIM 锚定）

合查链路（`HybridJoinClient` / `DsHybridQueryClient` / `DsFulltextClusterClient` / P30 DSL Parser 调用）追加 4 层守卫：
1. **timeout 守卫**：单次合查超时强制中断（默认值 30s，可通过 ResilienceConfig 自定义）
2. **retry 守卫**：可重试异常（IOException/TimeoutException）指数退避重试（默认 2 次，baseDelay=100ms）
3. **circuitBreaker 守卫**：滑动窗口失败率超阈值（默认 50% / 50 次请求窗口）后熔断打开（默认 5s 半开探测）
4. **rateLimiter 守卫**：令牌桶限流（默认 100 QPS / 200 burst），超出直接拒绝（无降级）

**MVP 边界（0 越界）**：
- 仅守护**合查链路**（searchHybrid* 与 searchHybridJoin* 共 6 个方法），不守护写入/索引/Schema DDL 路径
- 不引入 Resilience4j / Hystrix / Guava RateLimiter 等外部依赖
- 0 新增 Maven dep（pom.xml 0 变更）
- facade **append-only**：HJC/DHQC 6 个原方法 1 字不删改，新增 6 个 `*WithResilience` 重载

---

## 2. 红线（VERIFIED 永久钉死，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3** | fulltext 根包 0 新增 top-level 类；新类 100% 放 `fulltext/cluster/resilience/` 子包 |
| R2 | **NG-3 零依赖** | pom.xml 0 新增 dep/plugin；源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*` |
| R3 | **TOPK 铁律** | 守卫开启/关闭下 TopK 裁剪在 searchHybrid* / searchHybridJoin* 调用前后完全一致 |
| R4 | **Facade 单调不减** | HJC/DHQC/DsFulltextClusterClient/Parser 共 6 个原方法签名 1 字不删改；append-only 新增 6 个 `*WithResilience` 重载 |
| R5 | **HybridHit ctor=2** | 0 新增第 3/4 构造器；守卫不可破坏 P31 fillJoinFetch Unsafe 链路 |
| R6 | **P30/P31 字节级兼容** | ParsedHybridQuery / JoinSpec / JoinIdResolver 0 改；守卫接入走新重载，原 6 个方法不挂守卫 |

---

## 3. 架构（4 个新类，1 子包，6 个新重载）

### 3.1 新增子包 `fulltext/cluster/resilience/`（新顶级子包，**非** fulltext 根包类）

| 类名 | 职责 | 关键字段 |
|------|------|---------|
| `ResilienceConfig` | 不可变配置（timeout/retry/circuitBreaker/rateLimiter 4 段） | 4 final 字段 + Builder |
| `TimeoutGuard` | 单次调用超时（用 Future.get(timeoutMs) + 取消） | timeoutMs |
| `RetryGuard` | 指数退避重试（区分可重试/不可重试异常） | maxRetries, baseDelayMs, retryOn |
| `CircuitBreakerGuard` | 滑动窗口失败率（CLOSED/OPEN/HALF_OPEN 三态机） | failureRateThreshold, slidingWindowSize, openDurationMs |
| `RateLimiterGuard` | 令牌桶（按时间窗补充） | qps, burst |

> 注：`ResilienceConfig` 与 4 个 Guard 类共 5 个新类，**全**在 `resilience/` 子包（非 fulltext 根包，ZeroLeak3 红线豁免范围）。

### 3.2 6 个新重载（append-only，0 删改原方法）

| 类 | 新增方法 | 原方法 | 签名差异 |
|----|---------|--------|---------|
| HJC | `searchHybridJoinWithResilience(...)` | `searchHybridJoin(...)` | 末尾追加 `ResilienceConfig cfg` |
| HJC | `searchHybridJoinWithWeightsWithResilience(...)` | `searchHybridJoinWithWeights(...)` | 同上 |
| HJC | `searchHybridJoinWithLtrWithResilience(...)` | `searchHybridJoinWithLtr(...)` | 同上 |
| DHQC | `searchHybridWithResilience(...)` | `searchHybrid(...)` | 同上 |
| DHQC | `searchHybridWithWeightsWithResilience(...)` | `searchHybridWithWeights(...)` | 同上 |
| DHQC | `searchHybridWithLtrWithResilience(...)` | `searchHybridWithLtr(...)` | 同上 |

### 3.3 Guard 编排顺序（4 守卫层叠，链式包装）

```
RateLimiterGuard → CircuitBreakerGuard → RetryGuard → TimeoutGuard → 原方法调用
  ↑                 ↑                     ↑              ↑
  限流拒绝          熔断打开时              指数退避         超时取消
  抛 RateLimitExc   抛 CircuitOpenExc     抛原异常       抛 TimeoutExc
```

**包装器调用规范**：
```java
public List<HybridHit<T>> searchHybridJoinWithResilience(
    String ftQuery, Class<?> mainEntityClass, Object orm, K, joins, ResilienceConfig cfg) {
    return RateLimiterGuard.wrap(cfg.rateLimiter, () ->
        CircuitBreakerGuard.wrap(cfg.circuitBreaker, () ->
            RetryGuard.wrap(cfg.retry, () ->
                TimeoutGuard.wrap(cfg.timeout, () ->
                    this.searchHybridJoin(ftQuery, mainEntityClass, orm, K, joins)))));
}
```

---

## 4. 接口规范

### 4.1 `ResilienceConfig`（不可变 4 段配置）

```java
public final class ResilienceConfig {
    public final TimeoutConfig timeout;         // 不可变，timeoutMs ≥ 1
    public final RetryConfig retry;             // maxRetries ≥ 0, baseDelayMs ≥ 1
    public final CircuitBreakerConfig circuitBreaker; // failureRateThreshold 0~1, slidingWindowSize ≥ 1
    public final RateLimiterConfig rateLimiter; // qps ≥ 1, burst ≥ 1
    public static Builder builder() { ... }     // 4 段必填，任一缺 → IAE
}
```

### 4.2 异常体系（4 个新异常，全放 `resilience/` 子包）

| 异常 | 触发 | 是否计入 circuitBreaker 失败 |
|------|------|-----------------------------|
| `RateLimitExceededException` | rateLimiter 拒绝 | **否**（业务正常限流） |
| `CircuitOpenException` | circuitBreaker OPEN 状态 | **否**（已熔断） |
| `RetryExhaustedException` | 重试次数耗尽 | **是**（最后一次失败） |
| `QueryTimeoutException` | 单次调用超时 | **是** |

### 4.3 守卫状态可观测

- `CircuitBreakerGuard.getState()`：CLOSED / OPEN / HALF_OPEN
- `RateLimiterGuard.getAvailableTokens()`：剩余令牌
- `TimeoutGuard.getActiveCallCount()`：当前活跃调用数

---

## 5. 测试规范（6 阶段，TR 数量与 P28~P31 模板一致）

| T | 测试目标 | 关键 TR |
|---|---------|---------|
| **T1** | ResilienceConfig + 4 Guard 骨架 | TR1.1 ResilienceConfig 4 段 IAE；TR1.2 4 Guard 构造 NPE；TR1.3 4 异常类型；TR1.4 状态可观测方法 |
| **T2** | 4 守卫正确性 | TR2.1 Timeout 超时强制中断；TR2.2 Retry 指数退避（2 次重试 100ms+200ms）；TR2.3 CircuitBreaker 失败率超阈值 OPEN；TR2.4 RateLimiter 令牌耗尽拒绝；TR2.5 不可重试异常 0 重试；TR2.6 RateLimitException 不计入 circuitBreaker |
| **T3** | Inproc E2E 合查 + 守卫串联 | TR3.1 4 守卫开启 vs 全关闭结果 SubSet 相等 + score 1e-9；TR3.2 守卫抛 RateLimitExc / CircuitOpenExc 时调用方拿到正确异常；TR3.3 4 守卫关闭时性能退化 ≤5% |
| **T4** | 跨 Process TCP 合查 + 守卫 | TR4.1 跨进程 4 守卫全开启指纹稳定；TR4.2 跨进程 守卫半熔断跨 shard 重试；TR4.3 跨进程 守卫超时关闭后 2 子进程 0 僵尸 |
| **T5** | Gatekeeper 9Y | Y1 ZeroLeak3=3；Y2 NG-3 9 CNFE+0 netty/grpc/p2p.client；Y3 TOPK 铁律 100× 确定性 + 0 bucket；Y4 Facade 单调（HJC/DHQC 6 个原方法签名 1 字不删改）；Y5 路由公式 1000× exact；Y6 P30/P31 编译兼容；Y7 HybridHit ctor=2；Y8 ShardTransport 5 方法；Y9 0 TODO |
| **T6** | NFR | TR6.1 P95 ≤80s 50×（守卫开启 vs 全关闭）；TR6.2 5 类 Javadoc ≥10 行；TR6.3 CPD dup ≤1（160 token）；TR6.4 0 println/printStackTrace + ≥8 IAE；TR6.5 4 异常类 6 场景 20× 触发率 100% |

**TR 总数**：4+6+3+3+9+5 = **30 TR**（对齐 P29/P30 模板）

---

## 6. Gate B 组合（继承 P22-P31 290 TR 模板）

P22-P32 组合 Surefire：
- T1-T6 新增 30 TR + P22-P31 已有 290 TR = **320 TR**
- Surefire 4 重 temp+dbhome 命令（继承 P31 PowerShell 模板）
- 连续两次独立 Run，Tests run:320 Failures:0 Errors:0 Skipped:0 指纹全等

---

## 7. 逆指标（明确不做什么）

- ❌ 不引入 Resilience4j/Hystrix/Guava RateLimiter 外部依赖
- ❌ 不修改 fulltext 根包 3 个类（FulltextSegmenter/FulltextStore/TermDictStore）
- ❌ 不修改 DHQC/HJC 原 6 个公开方法签名
- ❌ 不修改 HybridHit（constructors.length=2 红线）
- ❌ 不修改 P30 Parser 解析输出（ParsedHybridQuery 字节级一致）
- ❌ 不修改 P31 JoinSpec/JoinIdResolver
- ❌ 不守护写入/索引/DDL 路径
- ❌ 不引入 Spring/AOP/字节码增强
- ❌ 不实现分布式熔断器（仅单 JVM 进程内滑动窗口）
- ❌ 不实现 adaptive 动态调参

---

## 8. 验收 Gate 三状态（待 Gate B/Gate C 通过后填实）

- [ ] **Gate A**（Spec+Tasks 签批）→ 当前待用户批准
- [ ] **Gate B**（Surefire 320 TR 两连 0F0E）→ 待 Implement
- [ ] **Gate C**（9Y 自检 + 洁癖 5/5）→ 待 Gate B 通过
