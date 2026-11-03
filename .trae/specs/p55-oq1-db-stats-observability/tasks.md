# P55 OQ12-㉝① db-stats 可观测性增强 —— tasks.md（**✅ Gate A 已签批 / Gate B 1064 跑 1F（基线 P28_T6.tr64）0E / Gate C 9Y 9/9 + 洁癖 5/5 闭环**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ ✅ 用户「继续」VERBATIM 批准（§0 **Q0=A / Q1=A 新包 2 类 / Q2=A 环形 p99 / Q3=A binlog lag 真值 / Q4=A 同构内嵌 handler / Q5=无源→全 0+ok=true**）
- [x] **Gate B**（P55 own TR 0F0E + p2p-db 拆批 + p2p-sync monitor + 已知基线 fail 沿用）→ ✅ **1064 TR / 1F / 0E**
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ ✅ 9Y **9/9 0F0E** + 洁癖 5/5

### 实跑 TR 数（Implement 后实跑回填，**禁止预填**）
- P55 own = **35 / 0F / 0E**（T1=5 / T2=6 / T3=5 / T4=5 / T5=9 / T6=5；p2p-db 32 + p2p-sync 3）
- Gate B 拆批实跑 = **1064 TR / 1F / 0E**（p2p-db 1051 + p2p-sync monitor 13）

---

## Stage 1: Gate A（✅ 已签批）

| # | 任务 | 状态 |
|---|------|------|
| 1.1 | Probe：`DsMemory.CacheStats`（14 字段）/ `CompactionStats`（8 计数器）/ `DsBinlogReplicator`（游标 lag）/ `DsTransactionManager.commit`（无计时）/ `DsClusterMonitor:86 replicaLag TODO` / `P2PSyncMonitorServer`（9 context + `DbClusterHandler` 5 行 + `writeJson`）/ 无 DsMemory 注册表 | ✅ |
| 1.2 | 起草 spec.md（§0 待裁决 Q0-Q5 + Scope + 红线 R1-R10 + 架构 2 类 + 接口契约 + T1-T6 + 逆指标 + 风险 + 9Y + Probe 锚点） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细 + Gate B 拆批命令） | ✅ |
| 1.4 | 提交 Gate A VERBATIM 逐条签批（含 §0 Q0-Q5 裁决） | ✅ 用户「继续」批准 |

---

## Stage 2: Implement（T1-T6，实跑 35 TR / 0F / 0E）

### T1 快照模型 + 注册表骨架 + IAE ×5
- [x] tr11 `DbStats.toMap` 字段齐全（5 组键）
- [x] tr12 `registerCache`/`registerCompaction`/`registerReplicator` 3 类 IAE（name null/空 + src null）
- [x] tr13 `unregister(name)` 移除注册源
- [x] tr14 `clear()` 归零全部注册源 + txn 采样
- [x] tr15 空注册 → `snapshot()` 全 0 快照（不抛）

### T2 指标聚合正确性 ×6
- [x] tr21 `cache.hitRate` = hits/(hits+misses)，分母 0 → 0
- [x] tr22 `cacheActiveBlocks` / `cacheCachedBytes` 透传
- [x] tr23 `compaction.bytesPerSec` = mergedBytes/秒，ticks=0 → 0
- [x] tr24 `wal.replayLag` = Σ `replicationLag()`
- [x] tr25 `replica.lag` 透传注册真值
- [x] tr26 多源注册 → 聚合语义（累加 / 覆盖率）

### T3 txn p99 采样 ×5
- [x] tr31 空样本 → p99 = 0
- [x] tr32 单样本 → p99 = 该值
- [x] tr33 1000 样本已知分布 → p99 精确（第 `ceil(0.99×n)-1` 个）
- [x] tr34 环形覆盖（> N 样本）只保留最近 N
- [x] tr35 `txnCount` 单调累加

### T4 端点 + replicaLag 真值 ×5
- [x] tr41 `/sync/api/db-stats` context 注册成功
- [x] tr42 handler 返回 200 + `stats` 字段
- [x] tr43 未注册源 → `ok=true` 全 0（R8）
- [x] tr44 `DsBinlogReplicator.replicationLag()` = src−applied
- [x] tr45 `DsClusterMonitor.replicaLag` = 注册真值（非 0）

> 实装分布（偏差 D1）：tr44/tr45 → `P55_T4_ReplicaLagTruthTest`（p2p-db）；tr41/tr42/tr43 → `P55_T4_DbStatsEndpointTest`（p2p-sync）。

### T5 Gatekeeper 9Y ×9
- [x] y1 ZeroLeak3 ≤3（新包 `cluster/stats/`=2）+ 既有包 0 新增 `.java`
- [x] y2 NG-3 零依赖（forbidden 引用 + import 白名单 + pom banned 0 命中）
- [x] y3 R10 0 新线程（**去注释后**判定）
- [x] y4 Facade 单调不减（P21~P54 fulltext 0 改 + `DsMemory` public 面 0 改）
- [x] y5 既有 API 0 改（`DsClusterMonitor.snapshotMap` 结构兼容 / `DsServerDirectory` 0 改）
- [x] y6 P21~P55 编译兼容 + 新方法 `getMethod` 可达
- [x] y7 `/sync/api/db-stats` 端点已注册
- [x] y8 注册表并发安全（ConcurrentHashMap / 环形采样）
- [x] y9 0 TODO/FIXME/HACK（含移除 `DsClusterMonitor` 既有 TODO）

### T6 NFR ×5
- [x] tr61 `snapshot()` 1k 源 P95 ≤ 阈值
- [x] tr62 `recordTxnLatency` 1M 次 P95 ≤ 阈值（0 分配路径）
- [x] tr63 改动类 Javadoc ≥ 10 行 + CPD 160 窗口 0 重复块
- [x] tr64 0 `System.out.println`/`printStackTrace` + IAE 显式守卫 ≥ 阈值
- [x] tr65 新增/改源码 0 TODO/FIXME/HACK

---

## Stage 3: Gate B（拆批全量回归）—— ✅ 1064 TR / 1F / 0E

| 批 | 范围 | 命令 | 实跑 | 判定 |
|----|------|------|------|------|
| 批 1 | P22-P24 | `-Dtest=ds.P22_*,ds.P23_*,ds.P24_*` | 76 TR / 0F / 0E | ✅ |
| 批 2 | P25-P28 | `-Dtest=ds.P25_*,ds.P26_*,ds.P27_*,ds.P28_*` | 112 TR / 1F / 0E | ✅（1F = 已知基线 `P28_T6.tr64`） |
| 批 3 | P29-P30 | `-Dtest=ds.P29_*,ds.P30_*` | 68 TR / 0F / 0E | ✅ |
| 批 4a | P31-P36 | `-Dtest=ds.P31_*..ds.P36_*` | 193 TR / 0F / 0E | ✅ |
| 批 4b | P37-P42 | `-Dtest=ds.P37_*..ds.P42_*` | 186 TR / 0F / 0E | ✅ |
| 批 4c | P43-P55 | `-Dtest=ds.P43_*..ds.P55_*`（含 P55 own 35 TR） | 416 TR / 0F / 0E | ✅ |
| 批 5 | p2p-sync monitor | `mvn -o -pl p2p-sync surefire:test -Dtest=P2PSyncMonitorServerTest,P55_T4_DbStatsEndpointTest` | 13 TR / 0F / 0E | ✅ |

> 合计 **1064 TR / 1F / 0E**（p2p-db 1051 + p2p-sync 13）；1F = 已知基线 `P28_T6.tr64`（spec §7 登记、不阻塞）。每批前清 `target\tmp` + C 盘残留；`test-compile` 与 `surefire:test` 串联；长跑 `Start-Process -RedirectStandardOutput` 落盘再 `Grep`；`-Dtest` 须显式类名（surefire 不支持包级通配，偏差 D2）。

---

## Stage 4: Gate C（洁癖 5/5）—— ✅

- [x] spec.md 回填：标题 + 9Y 9/9 + §5.3 Gate B 实跑 + §9 签批 + §10 偏差表
- [x] tasks.md 回填：三状态 + TR 数 + Stage 2-5 全 `[x]`
- [x] `docs/dsdb-design-status-and-roadmap.md`：§五 P3② 兑现标记 + §五 P1③ 兑现标记
- [x] Project Memory：P55 条目 + 硬约束 + Lessons
- [x] 洁癖 5/5（P55_T6 tr63/tr64/tr65 + P55_T5 9/9）+ P37~P54 spec 一字不动验证

---

## Stage 5: 交付

- [ ] 逐路径 `git add`（仅 P55 相关，**严禁 `git add -A`**）→ `git diff --cached --name-status` 复核
- [ ] `git commit -F <msgfile>` → `git push origin main`
- [ ] 验证 `git log -1` + 远端 `main` 更新
