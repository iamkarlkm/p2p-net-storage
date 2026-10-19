# P36 OQ1 分布式熔断器 + Adaptive 自适应 —— spec.md（**REV2 GATE C 实装闭环版，2026-10-20**）

## 实装偏差说明（REV1 → REV2）
REV1 草拟时设想「`ResilienceConfig` append-only 加 4 字段」；实装时选择**更严格的 0 改方案**：
新建独立类 `DistributedResilienceConfig`（含 P32 `ResilienceConfig` 8 字段投影 + P36 4 字段 Builder），
使 **P32 `ResilienceConfig.java` 保持 1 字不改**（红线 R4 由「append-only 加 4 字段」收紧为「0 改 + 新类承载」）。
下文凡标「append-only 加 4 字段」处均按此实装口径理解：**4 字段全部落在新类 `DistributedResilienceConfig`，P32 `ResilienceConfig` 0 改。**

## Gate C 9Y 自检清单（每 Y 必填 [x] 通过；任一未过 → 退 Gate A 整改）
- [x] Y1 ZeroLeak3=3：fulltext 根包仍 = {FulltextSegmenter, FulltextStore, TermDictStore}，P36 0 新增根包 top-level 类（新类 100% 放 `resilience/` + `resilience/distributed/` 子包）
- [x] Y2 NG-3 零依赖：P36 0 新增 netty/grpc/p2p.client 根包引用 + pom.xml 0 新增 dep/plugin
- [x] Y3 TOPK 铁律：分布式 CB 共享状态不修改 K
- [x] Y4 Facade 单调不减：P32 CircuitBreakerGuard / ResilienceConfig / RateLimiterGuard / RetryGuard / TimeoutGuard 0 改；P31 HJC.searchHybridJoin*WithResilience 3 入口 0 改；4 字段落在新类 `DistributedResilienceConfig`（P32 `ResilienceConfig` 0 改，收紧实装）
- [x] Y5 路由公式：P36 0 改 ShardRouter / 0 改 P24 UNIT/DOC 路由
- [x] Y6 P30~P35 编译兼容：ParsedHybridQuery / WeightSpec / LtrSpec / BenchmarkReport / DnfParser / DnfClause / ConditionNode / @DsManyToOne / JoinMode / JoinSpec / JoinIdResolverV2 / ManyToOneJoinResult / HybridJoinClientV2Adapter 0 改
- [x] Y7 HybridHit ctor=2：0 改
- [x] Y8 ShardTransport 5 方法：0 改
- [x] Y9 0 TODO/FIXME/HACK：100% 实装分布式 CB + Adaptive + 跨节点状态广播

---

## 1. Scope（用户单选 OQ12-⑭ 候选项 ①，VERBATIM 锚定）

分布式熔断器 + Adaptive 自适应 = 对 P32 单 JVM CB 做 **跨节点扩展 + 自适应调参**：

1. **DistributedCircuitBreakerState**：跨节点状态共享抽象（CLOSED/OPEN/HALF_OPEN 三态机在 N 个节点间同步）
2. **AdaptiveThresholdController**：根据实时失败率 / QPS / 节点数动态调整 `failureRateThreshold` + `openDurationMs`（AimdLike 算法：失败率 ↑ → 阈值 ↓；提高响应快 → openDuration ↓）
3. **DistributedResilienceConfig（新类）承载 4 字段**：`distributedKpiSampleIntervalMs`（跨节点状态广播间隔 ≥ 1000ms）+ `distributedQuorum`（投票分布节点数 1~N）+ `adaptiveMinFailureRate`（Adaptive 下限 0~1）+ `adaptiveMaxOpenDurationMs`（Adaptive 上限 ≥ 1）；P32 `ResilienceConfig` 8 字段 0 改，由新类投影承载
5. **ClusterStateBroadcaster**：通过现有 `DsServerDirectory.replicas()` fan-out 跨节点广播 CB 状态变更（异步 + at-least-once + 失败本地熔断保护）
6. **HybridJoinClient append-only 3 入口**：`searchHybridJoinWithDistributedResilience` / `*WithWeights` / `*WithLtr` —— 走 P32 守卫链后再叠加分布式 CB + Adaptive 调参

**MVP 边界（0 越界）**：
- 仅做分布式 CB 状态共享（CLOSED/OPEN/HALF_OPEN）+ Adaptive 阈值自动调参
- 不做 Raft/Paxos 共识（沿用 P12 现有 Raft 通道做广播，0 新协议）
- 不做动态节点发现（沿用 `DsServerDirectory` 现成目录）
- 不做跨节点 RateLimiter 同步（仅 CB 状态共享）
- 0 新增 Maven dep
- 新类全放 `resilience/distributed/` 子包（resilience 已有 `resilience/` 子包）

---

## 2. 红线（VERIFIED 永久钉死，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3** | resilience 子包 0 新增 top-level 类到 fulltext 根包；新类 100% 放 `resilience/distributed/` |
| R2 | **NG-3 零依赖** | pom.xml 0 新增 dep/plugin；源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*` |
| R3 | **TOPK 铁律** | 分布式 CB 共享状态不修改 K；广播不修改下游配置 |
| R4 | **Facade 单调不减** | P32 CircuitBreakerGuard / ResilienceConfig 不可变原 8 字段 + 4 守卫 0 改；P31 HJC.searchHybridJoin*WithResilience 3 入口 1 字不改；4 字段落在新类 `DistributedResilienceConfig`（P32 `ResilienceConfig` 0 改，收紧实装） |
| R5 | **HybridHit ctor=2** | 0 改（分布式 CB 状态仅写日志/广播，不影响 joinFetch Map） |
| R6 | **P30~P35 字节级兼容** | P30 DSL / P31 Join / P32 Resilience / P33 Benchmark / P34 DNF / P35 ManyToOne 全部 0 改 |
| R7 | **Adaptive 阈值边界** | `adaptiveMinFailureRate ∈ [0, 1]`（防止阈值跌破 0 导致永不熔断）；`adaptiveMaxOpenDurationMs ≥ 1`（防止恢复期过短导致抖动） |
| R8 | **broadcast 间隔** | `distributedKpiSampleIntervalMs ≥ 1000ms`（防止高频广播风暴） |
| R9 | **quorum 安全** | `distributedQuorum ≤ N`（节点数硬上限，避免非法 quorum 永远不达） |

---

## 3. 架构（5 个新类，P32 `ResilienceConfig` 0 改，0 改 P30~P35）

### 3.1 新增清单

| 名称 | 类型 | 职责 | 关键字段/方法 |
|------|------|------|---------------|
| `DistributedCircuitBreakerState` | 不可变 POJO（`resilience/distributed/`） | 跨节点 CB 状态快照 | `serverId` + `state` (CLOSED/OPEN/HALF_OPEN) + `openedAtNanos` + `failureRate` + `currentThreshold` + `currentOpenDurationMs` + 5 IAE 守卫 + fingerprint |
| `DistributedCircuitBreakerGuard` | 静态工具类（`resilience/distributed/`） | 分布式 CB 状态机（封装 P32 CircuitBreakerGuard + 状态广播 hook） | `wrap(callable, dist)` + `getDistributedState()` + `onStateChanged(callback)` + state snapshot 广播 |
| `AdaptiveThresholdController` | 静态工具类（`resilience/distributed/`） | 自适应阈值调参（AimdLike 算法：失败率 ↑ → 阈值 ↓） | `adjust(currentThreshold, sampleHistory, params)` + `adjustOpenDuration(...)` + 4 IAE 守卫 |
| `ClusterStateBroadcaster` | 静态工具类（`resilience/distributed/`） | 通过 `DsServerDirectory.replicas()` fan-out 异步广播 CB 状态 | `broadcast(serverId, state, dir)` + at-least-once 重试 + 失败本地熔断 + 6 IAE 守卫 |
| `DistributedResilienceConfig` | 不可变 POJO（`resilience/distributed/`） | 新类承载 P32 8 字段投影 + P36 4 字段（P32 `ResilienceConfig` 0 改） | `distributedKpiSampleIntervalMs` + `distributedQuorum` + `adaptiveMinFailureRate` + `adaptiveMaxOpenDurationMs` + 4 IAE 守卫 + fromBase（保留 8 原字段不变） |
| `HybridJoinClient` append-only 3 入口 | 扩展（`cluster/join/` 既有） | 3 守卫重载：`*WithDistributedResilience` / `*WithWeights` / `*WithLtr` | 委托 P32 守卫链 + 叠加 DistributedCircuitBreakerGuard + AdaptiveThresholdController |

### 3.2 DistributedResilienceConfig（新类，P32 8 字段投影 + P36 4 字段）

```java
// P32 ResilienceConfig 原 8 字段（源码 1 字不改，此处为新类投影承载）
public final long timeoutMs;
public final int maxRetries;
public final long baseDelayMs;
public final double failureRateThreshold;
public final int slidingWindowSize;
public final long openDurationMs;
public final int qps;
public final int burst;

// P36 4 字段（落在新类 DistributedResilienceConfig）
public final long distributedKpiSampleIntervalMs;  // ≥ 1000
public final int distributedQuorum;                // 1 ≤ x ≤ 1024
public final double adaptiveMinFailureRate;        // [0, 1]
public final long adaptiveMaxOpenDurationMs;       // ≥ 1000
```

### 3.3 包结构（追加 `resilience/distributed/` 子包）

```
com/q3lives/ds/database/fulltext/cluster/resilience/
├── CircuitBreakerGuard.java        (P32 既有，0 改)
├── CircuitOpenException.java       (P32 既有，0 改)
├── QueryTimeoutException.java      (P32 既有，0 改)
├── RateLimitExceededException.java (P32 既有，0 改)
├── RateLimiterGuard.java           (P32 既有，0 改)
├── ResilienceConfig.java           (P32 既有，0 改；4 字段由新类 DistributedResilienceConfig 承载)
├── RetryExhaustedException.java     (P32 既有，0 改)
├── RetryGuard.java                 (P32 既有，0 改)
├── TimeoutGuard.java               (P32 既有，0 改)
└── distributed/                    (P36 新增子包)
    ├── DistributedCircuitBreakerState.java   (NEW)
    ├── DistributedCircuitBreakerGuard.java   (NEW)
    ├── AdaptiveThresholdController.java      (NEW)
    ├── ClusterStateBroadcaster.java           (NEW)
    └── DistributedResilienceConfig.java       (NEW)
```

---

## 4. 接口契约

### 4.1 DistributedCircuitBreakerState 不可变 POJO

```java
public final class DistributedCircuitBreakerState {
    public final int serverId;
    public final CircuitBreakerGuard.State state;  // P32 enum 重用
    public final long openedAtNanos;
    public final double failureRate;
    public final double currentThreshold;
    public final long currentOpenDurationMs;
    // 5 IAE 守卫: serverId ∈ [0, 1024], failureRate ∈ [0, 1], 
    //             currentThreshold ∈ [0, 1], currentOpenDurationMs ≥ 1,
    //             state 非 null
    public static String fingerprint(DistributedCircuitBreakerState s);  // SHA-256
}
```

### 4.2 DistributedCircuitBreakerGuard

```java
public final class DistributedCircuitBreakerGuard {
    public static <T> T wrap(Callable<T> call, DistributedResilienceConfig config,
                              ClusterStateBroadcaster broadcaster,
                              AdaptiveThresholdController adaptive);
    public static DistributedCircuitBreakerState getDistributedState(int serverId);
    public static void onStateChanged(int serverId, DistributedCircuitBreakerState newState,
                                       DistributedCircuitBreakerState oldState);
    // 不计入失败的情况（同 P32 CircuitBreakerGuard.onFailure 不计入 RateLimit/CircuitOpen）
}
```

### 4.3 AdaptiveThresholdController

```java
public final class AdaptiveThresholdController {
    // AimdLike: failureRate ↑ (multiplicative 0.9x) threshold; failureRate ↓ (additive +0.05) threshold
    // bounds: [adaptiveMinFailureRate, 1.0]
    public static double adjustThreshold(double currentThreshold,
                                          double[] recentFailureRates,
                                          DistributedResilienceConfig config);
    // openDuration: same logic, bounds [1000, adaptiveMaxOpenDurationMs]
    public static long adjustOpenDuration(long currentOpenDurationMs,
                                            double[] recentFailureRates,
                                            DistributedResilienceConfig config);
    // 4 IAE 守卫: currentThreshold ∈ [0, 1], recentFailureRates 非 null, config 非 null, recentFailureRates.length ≥ 1
}
```

### 4.4 ClusterStateBroadcaster

```java
public final class ClusterStateBroadcaster {
    // at-least-once broadcast via DsServerDirectory.replicas()
    public static void broadcast(int serverId, DistributedCircuitBreakerState s,
                                   DsServerDirectory directory,
                                   DistributedResilienceConfig config);
    // 6 IAE 守卫: serverId ∈ [0, 1024], state 非 null, directory 非 null,
    //             config 非 null, config.distributedQuorum ≤ replicas().length,
    //             config.distributedQuorum ≥ 1
    // 失败本地熔断: broadcast 抛异常时本地 CB 保持原状态，不影响主链路
}
```

### 4.5 DistributedResilienceConfig（新类，P32 `ResilienceConfig` 0 改）

```java
public final class DistributedResilienceConfig {
    // P32 8 字段投影 (来自 P32 ResilienceConfig, 不可变复制构造；P32 源码 0 改)
    public final long timeoutMs;
    public final int maxRetries;
    public final long baseDelayMs;
    public final double failureRateThreshold;
    public final int slidingWindowSize;
    public final long openDurationMs;
    public final int qps;
    public final int burst;
    // P36 4 字段 append-only
    public final long distributedKpiSampleIntervalMs;  // ≥ 1000
    public final int distributedQuorum;                // 1 ≤ x ≤ 1024
    public final double adaptiveMinFailureRate;        // [0, 1]
    public final long adaptiveMaxOpenDurationMs;       // ≥ 1000
    // 4 IAE 守卫 + builder 模式（保留 P32 ResilienceConfig.Builder 不动）
    public static Builder builder();
}
```

### 4.6 HybridJoinClient append-only 3 入口

```java
// 委托 P32 searchHybridJoin*WithResilience 3 入口 + 叠加分布式 CB + Adaptive
public List<HybridHit<T>> searchHybridJoinWithDistributedResilience(
        QueryWrapper<T> orm, List<JoinSpec<?>> joins, String ftQ,
        Map<Long, String> raw, int K, SearchOptions opt,
        DistributedResilienceConfig config,
        DsServerDirectory directory);

public List<HybridHit<T>> searchHybridJoinWithWeightsWithDistributedResilience(
        QueryWrapper<T> orm, List<JoinSpec<?>> joins, String ftQ,
        Map<Long, String> raw, int K, SearchOptions opt,
        double alpha, double beta,
        Map<String, Map.Entry<Double, ToDoubleFunction<T>>> colW,
        DistributedResilienceConfig config,
        DsServerDirectory directory);

public List<HybridHit<T>> searchHybridJoinWithLtrWithDistributedResilience(
        QueryWrapper<T> orm, List<JoinSpec<?>> joins, String ftQ,
        Map<Long, String> raw, int K, SearchOptions opt,
        List<ToDoubleFunction<T>> extractors, double[] ltrModelWeights,
        DistributedResilienceConfig config,
        DsServerDirectory directory);
```

---

## 5. 测试（Gate B 拆 4 批：P22-P35 组合 + P36 own）

### 5.1 Surefire 4 批拆分（继承 P35 模板）

```powershell
# 批1: P22-P24
mvn surefire:test '-Dtest=ds.P22_*,ds.P23_*,ds.P24_*'
# 批2: P25-P27
mvn surefire:test '-Dtest=ds.P25_*,ds.P26_*,ds.P27_*'
# 批3: P28-P30
mvn surefire:test '-Dtest=ds.P28_*,ds.P29_*,ds.P30_*'
# 批4: P31-P36 (含 P35 P35 + P36 新)
mvn surefire:test '-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*'
```

### 5.2 TR 总数

| T | 范围 | TR 数 | 实装内容 |
|---|------|------|---------|
| **T1** | DistributedCircuitBreakerState + AdaptiveThresholdController skeleton | 5 | state 不可变 POJO 5 IAE + fingerprint + Adaptive 4 IAE |
| **T2** | DistributedResilienceConfig + ClusterStateBroadcaster | 6 | append-only 4 字段 + 4 IAE + broadcast 6 IAE + quorum 边界 |
| **T3** | Inproc E2E 分布式 CB + Adaptive 调参 | 3 | 状态机切换 + Adaptive 阈值更新 + 失败率反馈 |
| **T4** | 跨 Process TCP 分布式 CB 状态广播 | 3 | 2 子进程 state snapshot 一致 + Adaptive 阈值收敛 |
| **T5** | Gatekeeper 9Y | 9 | Y1-Y9 全 Y + 反射验证 3 入口 |
| **T6** | NFR | 5 | broadcast P95 + 5 类 Javadoc + CPD + 0 println + 4 NFR 字段 |

**TR 总数**：5+6+3+3+9+5 = **31 TR**

### 5.3 验收门槛
- P36 own 31/31 0F0E
- P22-P35 全部 TR 0F0E（无 P36 引入 fail）
- DistributedCircuitBreakerState fingerprint 跨进程完全一致
- Adaptive 调参 100% 收敛（失败率 ≥ 0.8 → threshold ≤ 0.3；失败率 ≤ 0.2 → threshold ≥ 0.7）
- broadcast 失败本地熔断不传播（P32 守卫链成功率 100% 保持）

---

## 6. 逆指标（Anti-Metrics，避免的伪优化）

| 逆指标 | 错误做法 | 正确做法 |
|--------|----------|----------|
| 频繁广播 | 每请求广播 state | `distributedKpiSampleIntervalMs ≥ 1000` 批量采样 |
| 共识过度 | Raft/CONSENSUS 跨节点 | at-least-once + 失败本地熔断 |
| 阈值越界 | threshold 可跌破 0 | `adaptiveMinFailureRate ≥ 0` 硬下限 |
| quorum 永远达不成 | `distributedQuorum > N` | `distributedQuorum ≤ replicas().length` 校验 |
| 重叠测试 | 重复 P32 守卫测试 | 仅测分布式 + Adaptive 增量行为 |

---

## 7. 风险与回退

| 风险 | 触发 | 回退 |
|------|------|------|
| 分布式 CB 状态广播延迟高 | T4 跨进程 fingerprint fail | 走 at-least-once 重试 + 超时本地熔断 |
| Adaptive 调参抖动 | T3 失败率波动大 → threshold 频繁变 | 适配 ≤0.05/cycle + min/max 边界 |
| quorum 永远达不成 | T2 quorum > N | DistributedResilienceConfig.build 严格校验 `quorum ≤ replicas().length` |
| 跨节点广播风暴 | T4 高频广播 | `distributedKpiSampleIntervalMs ≥ 1000ms` 硬下限 |

---

## 9. 交付物清单

- `.trae/specs/p36-oq1-distributed-circuit-breaker-adaptive/spec.md`（本文件）
- `.trae/specs/p36-oq1-distributed-circuit-breaker-adaptive/tasks.md`（Gate 三状态 + 6 阶段 TR 明细）
- `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/resilience/distributed/*.java`（5 新类；含承载 P32 8 字段投影 + P36 4 字段的 `DistributedResilienceConfig.java`）
- `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/join/HybridJoinClient.java`（append-only 加 3 入口，112+/0-）
- `p2p-db/src/test/java/ds/P36_T1_*.java` ~ `P36_T6_*.java`（6 测试文件 × 31 TR）