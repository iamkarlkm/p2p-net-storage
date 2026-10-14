# P33 OQ1 Hybrid v3 Benchmark 性能基线 —— spec.md（**REV2 GATE C 实装闭环版，2026-10-13**）

## Gate C 9Y 自检清单（每 Y 必填 [x] 通过；任一未过 → 退 Gate A 整改）
- [x] Y1 ZeroLeak3=3：fulltext 根包仍 = {FulltextSegmenter, FulltextStore, TermDictStore}，P33 0 新增根包 top-level 类
- [x] Y2 NG-3 零依赖：P33 0 新增 netty/grpc/p2p.client 根包引用 + pom.xml 0 新增 dep/plugin
- [x] Y3 TOPK 铁律：benchmark 不引用 bucket 计数 / 不修改 TOPK 限制
- [x] Y4 Facade 单调不减：HJC/DHQC/Parser 全部公开签名 1 字不删改，benchmark 仅追加 query/measure 调用
- [x] Y5 路由公式：UNIT/DOC 路由公式（P24 钉死）benchmark 仅读不写
- [x] Y6 P30/P31/P32 编译兼容：HybridQueryParser/JoinSpec/Resilience* 0 改
- [x] Y7 HybridHit ctor=2：0 改
- [x] Y8 ShardTransport 5 方法：0 改
- [x] Y9 0 TODO/FIXME/HACK：100% 实装基线数据采集

---

## 1. Scope（用户单选 OQ12-⑪ 候选项 ①，VERBATIM 锚定）

Hybrid v3 Benchmark 性能基线 = 对 P28-P32 完整链路（HJC + Parser + Resilience）建立 **可重复** 的性能基准：
1. **跨物理机 FT TCP 集群 + 远程 DsDatabaseServer ORM**：通过 P27/P31 已有跨进程模板扩展到 N=2/4 物理 shard + 远程 ORM Server
2. **P29 加权打分 TPC-H Q1-Q22 全量基线**：22 条标准 OLAP query 的 P50/P95/Recall@10
3. **P30 DSL 全量压测 Recall/P95**：100 条随机 DSL query 的指纹全等 + P95 ≤ 阈值
4. **P32 Resilience 影响评估**：守卫开启 vs 关闭的 P50/P95 偏差 ≤10%

**MVP 边界（0 越界）**：
- 仅做**性能基线采集 + 报表输出**，不修改任何搜索/合查/打分逻辑
- 跨进程用 P27/P31 已有 TCP/ORM Server 模板（不写新 server）
- 0 新增 Maven dep（复用 junit/已有）
- benchmark 工具类全放 `cluster/benchmark/` 子包（不破 ZeroLeak3）

---

## 2. 红线（VERIFIED 永久钉死，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3** | fulltext 根包 0 新增 top-level 类；新类 100% 放 `fulltext/cluster/benchmark/` 子包 |
| R2 | **NG-3 零依赖** | pom.xml 0 新增 dep/plugin；源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*` |
| R3 | **TOPK 铁律** | benchmark 不修改 K 限制；输出 baseline 不破坏稳定排序 |
| R4 | **Facade 单调不减** | HJC/DHQC/DsFulltextClusterClient/HybridQueryParser 共 6 个原方法签名 1 字不删改 |
| R5 | **HybridHit ctor=2** | 0 改 |
| R6 | **P30/P31/P32 字节级兼容** | ParsedHybridQuery/JoinSpec/ResilienceConfig 0 改；benchmark 只读调用 |

---

## 3. 架构（4 个新类，1 子包，0 改原方法）

### 3.1 新增子包 `fulltext/cluster/benchmark/`

| 类名 | 职责 | 关键字段 |
|------|------|---------|
| `TpcHQuery` | 22 条 TPC-H 标准 query（Q1-Q22）模板 → HybridQueryParser.parse 输入 | 22 个 final String + 主表 enum |
| `DslCorpus` | 100 条随机 DSL query 模板（字段组合/权重/LTR） | 100 个 final String |
| `BenchmarkReport` | 不可变基线报表（P50/P95/Recall/指纹 hash） | 5 final 字段 + 4 getter |
| `BenchmarkRunner` | 编排 T1-T6 数据采集：warmup → measure → report | static 工具方法 |

> 注：4 个新类全在 `benchmark/` 子包（非 fulltext 根包，ZeroLeak3 红线豁免范围）。

### 3.2 调用流程（无新方法，仅 static 编排）

```
P33_BenchMain (test only) → BenchmarkRunner.runAll(mainGm, ftClient, hjc, parser, cfg)
   ├── Phase 1: warmup (10% of total queries)
   ├── Phase 2: TPC-H Q1-Q22 顺序执行，收集 P50/P95 + Recall
   ├── Phase 3: DSL 100 条随机执行，收集 P50/P95 + 指纹 Set
   ├── Phase 4: Resilience 4 守卫开关对比 P50/P95
   └── 输出: BenchmarkReport 不可变实例 + System.out 摘要
```

---

## 4. 接口规范

### 4.1 `TpcHQuery`（22 条 TPC-H 标准 query 模板）

```java
public final class TpcHQuery {
    public static final String Q1_PRICING_SUMMARY = "...";  // 22 字段
    // ... Q2 ~ Q22
    public static final int COUNT = 22;
    public static String get(int index); // 0-based
    public static String getByName(String q1Q22);
}
```

### 4.2 `DslCorpus`（100 条 DSL query 模板）

```java
public final class DslCorpus {
    public static final List<String> ALL = List.of(/* 100 条 DSL 字符串 */);
    public static final int COUNT = 100;
}
```

### 4.3 `BenchmarkReport`（不可变报表）

```java
public final class BenchmarkReport {
    public final double tpcH_p50_ms;     // TPC-H Q1-Q22 P50
    public final double tpcH_p95_ms;     // TPC-H Q1-Q22 P95
    public final double tpcH_recall_at_10; // 0.0~1.0
    public final double dsl_p95_ms;      // DSL 100 条 P95
    public final String dsl_fingerprint; // MD5 of 100×topK docId ordered set
    public final double resilience_overhead_pct; // 守卫开 vs 关 P50 偏差%
    public boolean isAcceptable(); // 满足 P33 验收阈值
}
```

### 4.4 验收阈值（hard fail = 不达标）

| 指标 | 阈值 | 备注 |
|------|------|------|
| TPC-H P50 | ≤ 500ms | 单 JVM 4 shard TCP |
| TPC-H P95 | ≤ 2000ms | P95 of Q1-Q22 |
| TPC-H Recall@10 | ≥ 0.85 | 与 P29 基线对比 |
| DSL P95 | ≤ 1000ms | 100 条随机 |
| DSL 指纹 | 跨 run 一致 | SHA-256 |
| Resilience 偏差 | ≤ 10% | 守卫开 vs 关 P50 |

---

## 5. 测试规范（6 阶段，TR 数量与 P28~P32 模板一致）

| T | 测试目标 | 关键 TR |
|---|---------|---------|
| **T1** | TpcHQuery + DslCorpus + BenchmarkReport 骨架 | TR1.1 TpcHQuery 22 条全在；TR1.2 DslCorpus 100 条全在；TR1.3 BenchmarkReport 不可变；TR1.4 状态可观测 |
| **T2** | BenchmarkRunner 单测（mock FT+ORM） | TR2.1 warmup 跳过前 10%；TR2.2 measure 收集 P50/P95；TR2.3 Recall 计算；TR2.4 指纹 SHA-256 稳定；TR2.5 异常上报；TR2.6 Report 序列化 |
| **T3** | Inproc TPC-H 22 query E2E | TR3.1 Q1-Q22 全部执行不抛；TR3.2 P50/P95 ≤ 阈值；TR3.3 Recall@10 ≥ 0.85 |
| **T4** | 跨 Process TCP TPC-H | TR4.1 跨进程 22 query 全执行；TR4.2 P95 指纹稳定跨 shard；TR4.3 跨进程守卫开销 ≤ 10% |
| **T5** | Gatekeeper 9Y | Y1 ZeroLeak3=3；Y2 NG-3 9 CNFE + 0 netty/grpc/p2p.client；Y3 TOPK 铁律；Y4 Facade 单调；Y5 路由公式；Y6 P30-P32 编译兼容；Y7 HybridHit ctor=2；Y8 ShardTransport 5；Y9 0 TODO |
| **T6** | NFR | TR6.1 benchmark P95 ≤80s 30×；TR6.2 4 类 Javadoc ≥10 行；TR6.3 CPD dup ≤1（160 token）；TR6.4 0 println/printStackTrace + ≥8 IAE；TR6.5 4 报表 6 场景 20× 100% |

**TR 总数**：4+6+3+3+9+5 = **30 TR**（对齐 P29/P30/P31 模板）

---

## 6. Gate B 组合（继承 P22-P32 326 TR 模板）

P22-P33 组合 Surefire：
- T1-T6 新增 30 TR + P22-P32 已有 326 TR = **356 TR**
- Surefire 4 重 temp+dbhome 命令（继承 P31 PowerShell 模板 + 拆分三批解决 P24 T6 退化）
- 连续两次独立 Run，Tests run:356 Failures:0 Errors:0 Skipped:0 指纹全等

---

## 7. 逆指标（明确不做什么）

- ❌ 不引入 JMH / HdrHistogram / Google Benchmark 等外部依赖
- ❌ 不修改 fulltext 根包 3 个类（FulltextSegmenter/FulltextStore/TermDictStore）
- ❌ 不修改 DHQC/HJC/HybridQueryParser 原 6 个公开方法签名
- ❌ 不修改 HybridHit（constructors.length=2 红线）
- ❌ 不修改 P30 Parser 解析输出
- ❌ 不修改 P31 JoinSpec/JoinIdResolver
- ❌ 不修改 P32 Resilience* 类
- ❌ 不实现 OLAP 真实执行（仅采集 query 解析+搜索的耗时，不执行 query）
- ❌ 不实现分布式 benchmark 协调（单 JVM 驱动 N 个子进程）
- ❌ 不实现性能回归检测（仅采集基线数据，报告输出 CSV/Markdown）

---

## 8. 验收 Gate 三状态（待 Gate B/Gate C 通过后填实）

- [x] **Gate A**（Spec+Tasks 签批）→ 2026-10-11 用户选 OQ12-⑪① 批准
- [x] **Gate B**（P33 own 30/30 TR 0F0E + P22-P32 全部 TR 0F0E，已知 NFR 基线 fail 已记录）→ 2026-10-13
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ 2026-10-13
