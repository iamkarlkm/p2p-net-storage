# P55 OQ12-㉝① db-stats 可观测性增强（**可观测快照注册表 + `/sync/api/db-stats` 端点 + replication lag 真值**）—— spec.md（**✅ Gate A 已签批 / Gate B 1064 跑 1F（基线 P28_T6.tr64）0E / Gate C 9Y 9/9 + 洁癖 5/5 闭环**）

> 用户「继续」推进（承接 roadmap §五 P3② + §五 P1③，见 §11 Probe 锚点）→ SP3.1 Gate A（本文件 + tasks.md）→ **用户 VERBATIM 批准** → Implement T1-T6 → Gate B 拆批回归 → Gate C 洁癖 5/5 → Stage 5 commit/push。
>
> **状态（Gate C 回填）**：Gate A ✅ 已签批；Gate B ✅ 实跑 **1064 TR / 1F（已知基线 `P28_T6.tr64`）/ 0E**；Gate C 9Y **9/9** + 洁癖 5/5 ✅。§5.2 TR 计划 / §5.3 Gate B 数字已实跑回填。

## Gate C 9Y 自检清单（Gate A 冻结目标；Gate C 回填实锤；目标 = `P55_T5_Gatekeeper9YTest` 9/9 0F0E）

- [x] Y1 **ZeroLeak3 ≤3 + 既有类计数守恒**：新包 `com.q3lives.ds.cluster.stats` top-level **≤ 3**（实装 = 2：`DbStats` / `DbStatsRegistry`）；既有全部包 **0 新增 `.java`** → `P55_T5.y1_newPackageWithinThreeAndNoLegacyClassAdded`
- [x] Y2 **NG-3 零依赖**：0 新增 netty/grpc/p2p.client 引用 + 相关 `pom.xml` 0 新增 dep/plugin → `P55_T5.y2_zeroForbiddenImportsAndPomBannedZero`
- [x] Y3 **R10 零新线程**：`cluster/stats/` 源码 0 `ExecutorService` / `new Thread` / `Timer` / `scheduleAtFixedRate`（**去注释后**判定）→ `P55_T5.y3_noNewThreadInStatsPackageAfterStripComments`
- [x] Y4 **Facade 单调不减**：P21~P54 fulltext 全部源码 **0 改**；`DsMemory` public 面 **0 改**（仅复用既有 `getCacheStats()`）→ `P55_T5.y4_facadeMonotonicFulltextZeroChangeAndDsMemoryPublicUnchanged`
- [x] Y5 **既有 API 0 改**：`DsClusterMonitor.snapshotMap()` 返回结构向后兼容（`replicaLag` 语义由常量 0 → 真值，字段名/类型不变）；`DsServerDirectory` 0 改 → `P55_T5.y5_existingApiSignatureUnchanged`
- [x] Y6 **P21~P55 编译兼容 + 新方法可达**：既有类型 0 改 + 全类存在（含新包 2 类 + `DsBinlogReplicator.replicationLag` / `DsClusterMonitor` / `DbStatsRegistry` 新方法 `getMethod` 可达）→ `P55_T5.y6_allClassesPresentAndCompileCompatible`
- [x] Y7 **端点注册**：`/sync/api/db-stats` context 存在且与既有 handler 同构（`P2PSyncMonitorServer` 构造器注册）→ `P55_T5.y7_dbStatsEndpointRegistered`
- [x] Y8 **注册表并发安全**：静态注册表基于 `ConcurrentHashMap` / `AtomicLongArray`，快照读取 0 锁写路径 → `P55_T5.y8_registryConcurrencySafe`
- [x] Y9 **0 TODO/FIXME/HACK**（新增/改源码；含移除 `DsClusterMonitor` 既有 `replicaLag` TODO）→ `P55_T5.y9_noTodoFixmeHack`

---

## 0. 裁决（Gate A ✅ 已签批：Q0-Q5 全部采纳 A；Q5 = 无源 → 全 0 快照 + ok=true）

> **Q0 Scope 确认（本阶段候选）**
>
> 本 spec 按用户「继续」推进 + 助手裁决采纳 **OQ12-㉝① = 可观测性增强 / db-stats**（承接 roadmap §五 P3② + §五 P1③，两者同主题）。
>
> | 选项 | 方案 | 交付 | 推荐 |
> |------|------|------|------|
> | **A** | **完整 §五 P3② + P1③**：4 指标聚合（`cache.hitRate` / `wal.replayLag` / `txn.latency.p99` / `compaction.bytesPerSec`）+ `/sync/api/db-stats` 端点 + `DsClusterMonitor.replicaLag` 真值 | 端点 + 注册表 + 2 处采样插桩 | ⭐**推荐** |
> | B | **精简**：仅端点 + 已有数据源聚合（不新增 txn 采样 / 不修 replicaLag） | 端点 + 注册表 | 备选（更小但 roadmap 未兑现） |
>
> **请确认：`Q0 = A`**（若实为 ② Other，请签批时重定向，本文件作废重起）。

> **Q1 新类落包（决定是否新增包与类数）**
>
> Probe 实锤（见 §11）：`dsdb` 侧无「DsMemory 实例注册表」，`DsBinlogReplicator` 亦无全局注册；要聚合 4 类指标必须引入**统一注册表**。ZeroLeak3 规定新包 top-level ≤ 3。
>
> | 选项 | 方案 | 新增类 | 红线 | 推荐 |
> |------|------|--------|------|------|
> | **A** | **新包 `com.q3lives.ds.cluster.stats`（2 类）**：`DbStats`（不可变快照 + `toMap`）/ `DbStatsRegistry`（静态注册 + 聚合 + 内嵌 txn 采样）。职责隔离清晰，既有包 0 新增类 | 2 | 无（≤3） | ⭐**推荐** |
> | B | **0 新类**：全部方法追加到既有 `DsClusterMonitor` | 0 | `DsClusterMonitor` 体积膨胀、职责混杂 | 备选 |
>
> **请裁决：`Q1 = A`（新包 2 类）或 `Q1 = B`（0 新类方法追加）。**

> **Q2 txn 延迟 p99 采样口径（决定内存/精度权衡）**
>
> `DsTransactionManager.commit(ctx)` 现无任何耗时统计。p99 需采样。
>
> | 选项 | 方案 | 内存 | 精度 | 推荐 |
> |------|------|------|------|------|
> | **A** | **固定环形缓冲（N=1024 `long[]` + 游标）**：`record(nanos)` 覆盖写；`p99()` 对快照副本 `Arrays.sort` 取 99 分位。0 装箱、0 分配（读时一次快照拷贝） | 8KB/实例 | 精确（对最近 1024 笔） | ⭐**推荐** |
> | B | **固定桶直方图**（log2 桶，~64 `long[]`）：`record` O(1) 累加，`p99()` O(桶数) 近似 | ~0.5KB | 近似（桶宽） | 备选 |
>
> **请裁决：`Q2 = A`（环形缓冲精确 p99）或 `Q2 = B`（桶直方图近似）。**

> **Q3 `wal.replayLag` 口径（`dsdb` 无 WAL，只有 binlog 复制游标）**
>
> Probe 实锤（见 §11）：`DsMemory` 无 WAL replay lag；真实「未应用」度量位于 `DsBinlogReplicator`（`source.sequence − lastCursorSequence`）。
>
> | 选项 | 方案 | 语义 | 推荐 |
> |------|------|------|------|
> | **A** | **binlog 复制 lag = `sourceDb.getBinlogStore().getCurrentPosition().sequence() − currentCursorSequence()`**：`DsBinlogReplicator` 追加只读方法 `replicationLag()`（0 新类），聚合为 `wal.replayLag` | 「未应用 binlog 序列数」 | ⭐**推荐** |
> | B | 保留字段返回 `0`（占位，不新增方法） | 无意义 | 备选 |
>
> **请裁决：`Q3 = A`（binlog 复制 lag 真值）或 `Q3 = B`（占位 0）。**

> **Q4 端点实现方式（`P2PSyncMonitorServer` 为 191KB 既有大文件）**
>
> 既有 `DbClusterHandler`（L103-108）为 5 行内嵌 `final class`：`writeJson(exchange, 200, toJson(DsClusterMonitor.snapshotMap()))`。
>
> | 选项 | 方案 | 改动面 | 推荐 |
> |------|------|--------|------|
> | **A** | **同构内嵌 `DbStatsHandler`**（`final class`，~5 行）+ 构造器 `createContext("/sync/api/db-stats", new DbStatsHandler())`；复用既有 `writeJson` / `toJson`，**0 新类文件 / 0 改既有 handler** | 1 context + 1 内嵌类 | ⭐**推荐** |
> | B | 独立 handler 顶层类 | 新类（增计数） | 备选 |
>
> **请裁决：`Q4 = A`（同构内嵌 handler）。**

> **Q5 未注册时的降级语义（对齐既有 `DbClusterHandler` 风格）**
>
> - 默认：**`DbStatsRegistry` 无任何注册源时，`snapshot()` 返回全 0 快照且 `ok=true`**（「已就绪但无数据」），与 `DsClusterMonitor` 的 `ok=false`（目录未注册）**语义区分**：端点返回 `{ok, stats:{...}}`，`stats` 恒存在（不再抛 500）。
> - **请确认：`Q5 = 无源 → 全 0 快照 + ok=true（端点恒 200）`。**

> 其余 Scope / 红线 / 类清单 / MVP 边界如下，请逐条签批（§9）。

---

## 1. Scope（用户 OQ12-㉝ 候选项 ①）

> 原文（roadmap L408-410）：
> `**② 可观测性增强**`
> `- 现有 CacheStats 扩展：每个 DsMemory 派生实例挂 Micrometer-like Gauge：dsdb.cache.hitRate、dsdb.wal.replayLag、dsdb.txn.latency.p99、dsdb.compaction.bytesPerSec；`
> `- P2PSyncMonitorServer 增加 /sync/api/db-stats endpoint。`
> 原文（roadmap L204）：`**③ 与 p2p-sync 的协同点**：让 SyncMonitorServer 同时监控 DsDb replication lag（lastAppliedSequenceId per shard）……`

**P55 = db-stats 可观测性增强（新包 2 类 + 2 处采样插桩 + 1 端点）**：引入统一**可观测快照注册表**，把分散的运行时指标（缓存命中率 / binlog 复制 lag / 事务 p99 延迟 / compaction 吞吐）聚合为一个 `DbStats` 快照，经 `/sync/api/db-stats` 端点对外暴露；并兑现 `DsClusterMonitor.replicaLag` 的既有 TODO（常量 0 → 真值）。

1. **`DbStats`**（新类，不可变快照）：`cacheHitRate` / `cacheActiveBlocks` / `cacheCachedBytes` / `walReplayLag` / `txnCount` / `txnLatencyP99Nanos` / `compactionBytesPerSec` / `compactionRunsSucceeded` / `replicaLag` + `toMap()`（JSON 友好）。
2. **`DbStatsRegistry`**（新类，静态）：注册/注销指标源（cache / compaction / replicator）+ `recordTxnLatency(long)` 采样入口 + `snapshot()` 聚合 + `clear()`；内嵌 `TxnLatency` 环形采样器（见 Q2）。
3. **`DsBinlogReplicator.replicationLag()`**（方法追加，0 新类）：`source.sequence − currentCursorSequence()`。
4. **`DsTransactionManager`**：`commit(ctx)` 前后 `System.nanoTime()` 计时 → `DbStatsRegistry.recordTxnLatency`（**0 改对外签名**）。
5. **`DsClusterMonitor`**：`replicaLag` 由常量 `0L` → `DbStatsRegistry` 真值（移除既有 TODO）。
6. **`P2PSyncMonitorServer`**：新增 `/sync/api/db-stats` context + 同构内嵌 `DbStatsHandler`。

**MVP 边界（0 越界）**：
- 仅做「**运行时指标聚合快照 + HTTP 端点**」；**不做** Micrometer 等外部依赖引入（NG-3）
- **不做** 后台推送 / 定时导出 / 指标持久化（**0 新增线程**，R10）；`snapshot()` 为**调用即算**（pull）
- **不做** 前端面板 HTML 改造（仅 JSON 端点；前端可后续消费）
- **不做** 分布式全局聚合（仅**本 JVM 内**已注册源；跨节点聚合沿用 `DsClusterMonitor` 既有 per-server 结构）
- **不做** 写路径语义变更：`DsMemory` / `DsBinlogReplicator` / `DsTransactionManager` 的**读写行为字节级不变**（仅新增只读采样/计时）
- **0 改** P21~P54 fulltext 全部源码；**0 新增既有包 `.java`**
- **0 新增 Maven dep**

---

## 2. 红线（Gate A 冻结，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3 ≤3** | 新包 `com.q3lives.ds.cluster.stats` top-level **≤ 3**（本方案 = 2）；既有包 0 新增 `.java` |
| R2 | **NG-3 零依赖** | `p2p-db` / `p2p-sync` `pom.xml` 0 新增 dep/plugin；新包源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*`；仅 JDK + first-party import |
| R3 | **Facade 单调不减** | P21~P54 全部源码**字节级 0 改**；`DsMemory` public 面 0 改（仅复用 `getCacheStats()`）；`DsTransactionManager` **仅** `commit` 内部追加计时 + 新增只读入口，既有签名/语义 0 改 |
| R4 | **既有 API 向后兼容** | `DsClusterMonitor.snapshotMap()` 返回 Map 字段名/类型不变（`replicaLag` 语义由常量升级为真值）；`DsServerDirectory` / `DsNodeContext` / `DsPartitionManager` 0 改 |
| R5 | **R10 零新线程** | `cluster/stats/` 0 `ExecutorService` / `new Thread` / `Timer` / `scheduleAtFixedRate`；所有聚合为调用线程内同步计算 |
| R6 | **采样 0 阻塞写路径** | `recordTxnLatency` 仅一次 `long` 环形写 + 一次 `AtomicLong` 增；**0 锁 / 0 分配 / 0 IO**；`snapshot()` 读时才对样本做一次拷贝+排序 |
| R7 | **注册表并发安全** | 注册源容器用 `ConcurrentHashMap`；采样器用 `long[]` + `AtomicInteger` 游标（或 `AtomicLongArray`）；`snapshot()` 无锁遍历弱一致快照 |
| R8 | **端点语义确定** | 未注册源 → 全 0 快照 + `ok=true`（Q5）；`stats` 字段恒存在；HTTP 恒 200；异常内部消化为 `{ok:false,message}`，不抛 500 |
| R9 | **p99 语义确定** | 样本数 < 阈值时退化为「已有样本的最大值」；空样本 → 0；分位定义 = 升序第 `ceil(0.99×n)−1` 个 |
| R10 | **0 TODO/FIXME/HACK** | 新增/改源码 0 处遗留标记；**移除** `DsClusterMonitor` 既有 `replicaLag` TODO |

---

## 3. 架构（**新包 2 类** + 2 处插桩 + 1 端点）

### 3.1 变更清单

| 类 | 类型 | 职责 | 关键约束 |
|----|------|------|---------|
| `DbStats`（`cluster/stats/`，新） | 新类 | 不可变快照 + `toMap()` | 纯 POJO，0 依赖 |
| `DbStatsRegistry`（`cluster/stats/`，新） | 新类 | 静态注册表 + 聚合 + 内嵌 `TxnLatency` 采样 | `ConcurrentHashMap`；R5/R6/R7 |
| `DsBinlogReplicator`（`binlog/`，既有） | 方法追加 | `replicationLag()` 只读 | 0 字段 / 0 签名改 |
| `DsTransactionManager`（`database/tx/`，既有） | 内部插桩 | `commit` 计时 → `recordTxnLatency` | 0 对外签名改 |
| `DsClusterMonitor`（`cluster/`，既有） | 局部改 | `replicaLag` 真值 + 移除 TODO | 返回结构不变 |
| `P2PSyncMonitorServer`（`p2p-sync`，既有） | 局部改 | `/sync/api/db-stats` + 内嵌 handler | 同构既有 `DbClusterHandler` |

> **类数论证**：新包 `cluster/stats/` = **2** ≤ 3（ZeroLeak3 满足）；其余为既有类方法追加/内部插桩，**0 新增既有包 `.java`**。

### 3.2 数据流（pull 快照）

```
指标产生（既有，插桩点 0 语义改）
  DsMemory.getCacheStats()            ← 注册源（cache 命中率/占用）
  CompactionScheduler.stats()         ← 注册源（CompactionStats 8 计数器）
  DsBinlogReplicator.replicationLag() ← 注册源（P55 新增只读方法）
  DsTransactionManager.commit(ctx)    → DbStatsRegistry.recordTxnLatency(nanos)   ← P55 插桩

端点请求（GET /sync/api/db-stats）
  P2PSyncMonitorServer.DbStatsHandler.handle
    → DbStatsRegistry.snapshot()  ← 同步聚合（0 锁 / 0 新线程）
       ├─ cache.hitRate   = coldReadHits / (coldReadHits + coldReadMisses)
       ├─ wal.replayLag   = Σ replicationLag()
       ├─ txn.latency.p99 = TxnLatency.p99()
       └─ compaction.bytesPerSec = mergedBytes / (ticks × tickMs / 1000)   [0 分母 → 0]
    → toJson(DbStats.toMap()) → writeJson(exchange, 200, ...)
```

### 3.3 包结构

```
com/q3lives/ds/
├── cluster/
│   ├── DsClusterMonitor.java      (既有；**局部改** replicaLag 真值)
│   ├── DsServerDirectory.java     (既有，0 改)
│   ├── DsNodeContext.java         (既有，0 改)
│   ├── DsIdLayout.java            (既有，0 改)
│   ├── DsPartitionRouter.java     (既有，0 改)
│   └── stats/                     (新包；top-level = 2)
│       ├── DbStats.java                   (新)
│       └── DbStatsRegistry.java           (新；内嵌 TxnLatency)
├── binlog/DsBinlogReplicator.java (既有；**方法追加** replicationLag)
└── database/tx/DsTransactionManager.java (既有；**内部插桩** commit 计时)
```

---

## 4. 接口契约

### 4.1 DbStats（新类，不可变快照）

```java
public final class DbStats {
    public DbStats(double cacheHitRate, int cacheActiveBlocks, long cacheCachedBytes,
                   long walReplayLag, long txnCount, long txnLatencyP99Nanos,
                   double compactionBytesPerSec, long compactionRunsSucceeded, long replicaLag);
    public double cacheHitRate();
    public int    cacheActiveBlocks();
    public long   cacheCachedBytes();
    public long   walReplayLag();
    public long   txnCount();
    public long   txnLatencyP99Nanos();
    public double compactionBytesPerSec();
    public long   compactionRunsSucceeded();
    public long   replicaLag();
    /** JSON 友好 Map：{cache:{hitRate,activeBlocks,cachedBytes}, wal:{replayLag},
     *  txn:{count,latencyP99Nanos}, compaction:{bytesPerSec,runsSucceeded}, replica:{lag}} */
    public Map<String, Object> toMap();
}
```

### 4.2 DbStatsRegistry（新类，静态）

```java
public final class DbStatsRegistry {
    public static void registerCache(String name, Supplier<DsMemory.CacheStats> src);
    public static void registerCompaction(String name, Supplier<CompactionStats> src);
    public static void registerReplicator(String name, Supplier<Long> lagNanosOrSeq);
    public static void unregister(String name);
    public static void recordTxnLatency(long nanos);   // 写入口（0 锁 / 0 分配）
    public static long txnCount();
    public static long txnLatencyP99Nanos();
    public static DbStats snapshot();                  // 聚合当前注册源
    public static void clear();                        // 测试用
}
```

> **守卫 IAE**：`registerXxx` 的 `name` 为 null/空 / `src` 为 null → `IllegalArgumentException`。

### 4.3 DsBinlogReplicator（既有，追加 1 只读方法）

```java
/** P55 Q3=A：未应用 binlog 序列数 = source.sequence − currentCursorSequence()；source 无 position → 0。 */
public long replicationLag() throws IOException;
```

### 4.4 DsTransactionManager（既有，内部插桩）

```java
public void commit(TxContext ctx) throws Exception {
    long t0 = System.nanoTime();
    try {
        ... 既有逻辑 0 改 ...
    } finally {
        DbStatsRegistry.recordTxnLatency(System.nanoTime() - t0);
    }
}
```

### 4.5 P2PSyncMonitorServer（既有，追加端点）

```java
// 构造器新增一行（与既有 9 个 context 同构）
this.server.createContext("/sync/api/db-stats", new DbStatsHandler());

// 内嵌 handler（与既有 DbClusterHandler 同构）
private final class DbStatsHandler implements HttpHandler {
    @Override public void handle(HttpExchange exchange) throws IOException {
        writeJson(exchange, 200, toJson(DbStatsRegistry.snapshot().toMap()));
    }
}
```

---

## 5. 测试（Gate B 拆批）

### 5.1 Surefire 拆批（继承 P54 模板；P55 入批 4c）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
mvn -o -q -pl p2p-core install -DskipTests          # 若新类落 p2p-core 才需要；本方案不涉
mvn -o -q -pl p2p-db test-compile
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P22_*,ds.P23_*,ds.P24_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
# ...（P25-P42 批次同 P54，略）
mvn -o -pl p2p-db surefire:test "-Dtest=ds.P43_*,...,ds.P55_*" "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh"
mvn -o -pl p2p-sync surefire:test "-Dtest=javax.net.p2p.filesync.monitor.*" "-Djava.io.tmpdir=$tmp"
```

> 每批前必须先清理 `target\tmp` 与仓库根 `.tmp` + C 盘 TEMP 残留；改源码或测试后必须先 `test-compile`；长跑用 `Start-Process -RedirectStandardOutput` 落盘再 `Grep`（规避 PowerShell 重定向早退）。

### 5.2 TR 计划（实跑数字 Gate B 回填，**禁止预填**）

| T | 范围 | 计划 TR | 实装内容 | 目标测试类 |
|---|------|--------|---------|--------|
| **T1** | 快照模型 + 注册表骨架 + IAE | 5 | `DbStats.toMap` 字段齐全 / `registerXxx` 3 类 IAE / `unregister` 移除 / `clear` 归零 / 空注册 → 全 0 快照 | `P55_T1_DbStatsSkeletonTest` |
| **T2** | 指标聚合正确性 | 6 | ① `cache.hitRate` = hits/(hits+misses)，分母 0 → 0；② `cacheActiveBlocks`/`cacheCachedBytes` 透传；③ `compaction.bps` = mergedBytes/秒，ticks=0 → 0；④ `wal.replayLag` = Σ replicationLag；⑤ `replica.lag` 透传；⑥ 多源注册 → 累加/覆盖率语义 | `P55_T2_AggregationTest` |
| **T3** | txn p99 采样 | 5 | ① 空样本 → 0；② 单样本 → 该值；③ 1000 样本已知分布 → p99 精确；④ 环形覆盖（>N 样本）只保留最近 N；⑤ `txnCount` 单调累加 | `P55_T3_TxnLatencyTest` |
| **T4** | 端点 + replicaLag 真值 | 5 | ① `/sync/api/db-stats` context 注册成功；② handler 返回 200 + `stats` 字段；③ 未注册源 → `ok=true` 全 0；④ `DsBinlogReplicator.replicationLag()` = src−applied；⑤ `DsClusterMonitor.replicaLag` = 注册真值（非 0） | `P55_T4_ReplicaLagTruthTest`（p2p-db，④⑤ 2 TR）+ `P55_T4_DbStatsEndpointTest`（p2p-sync，①②③ 3 TR） |
| **T5** | Gatekeeper 9Y | 9 | Y1~Y9（见页首清单） | `P55_T5_Gatekeeper9YTest` |
| **T6** | NFR | 5 | `snapshot()` 1k 源 P95 ≤ 阈值 + `recordTxnLatency` 1M 次 P95 ≤ 阈值（0 分配路径）+ 改动类 Javadoc ≥ 10 行 + CPD 160 窗口 0 重复块 + 0 `System.out.println`/`printStackTrace` + IAE 显式守卫 ≥ 阈值 | `P55_T6_NFR_Test` |

**TR 计划总数**：5+6+5+5+9+5 = **35 TR**（实跑 = 35 TR 0F0E，见 §5.3）

### 5.3 验收门槛（Gate C 实跑回填，**禁止预填**）

- [x] P55 own **35/35 TR 0F0E**（T1 5 + T2 6 + T3 5 + T4 5 + T5 9 + T6 5；p2p-db 32 + p2p-sync 3）
- [x] Gate B 拆批 p2p-db 实跑 = **1051 TR / 1F / 0E**（1F = 已知基线 `P28_T6.tr64`）
- [x] Gate B p2p-sync monitor 实跑 = **13 TR / 0F / 0E**
- [x] **Gate B 合计 = 1064 TR / 1F / 0E**（拆批：批1=76 / 批2=112(1F) / 批3=68 / 批4a=193 / 批4b=186 / 批4c=416 / 批5=13）
- [x] R8 端点语义（未注册 → 全 0 + ok=true）→ `P55_T4.tr43`
- [x] R9 p99 语义 → `P55_T3`
- [x] R10 移除 `DsClusterMonitor` TODO → `P55_T5.y9`
- [x] R1 ZeroLeak3（新包 ≤3）+ R2 NG-3 → `P55_T5.y1` / `y2`

---

## 6. 逆指标（禁止项）

1. **禁止引入 Micrometer / 任何外部指标库**（NG-3）；自研轻量快照。
2. **禁止后台线程 / 定时器 / 定时导出**（R10，0 `ExecutorService`/`Timer`）。
3. **禁止在写路径加锁 / 分配 / IO**（R6）；`recordTxnLatency` 仅 `long` 环形写。
4. **禁止改 P21~P54 fulltext 任何源码**（R3）。
5. **禁止改 `DsMemory` public 面 / `DsServerDirectory`**（R3/R4）。
6. **禁止改端点既有 context 的路径 / 语义**（仅**新增** `/sync/api/db-stats`）。
7. **禁止在快照聚合中抛出异常给 HTTP 层**（R8，内部消化）。
8. **禁止把指标源强引用导致无法 GC**（注册表用 `Supplier` + 显式 `unregister`；测试 `clear()`）。

---

## 7. 风险与回退

| 风险 | 影响 | 缓解 / 回退 |
|------|------|------------|
| `DsTransactionManager.commit` 插桩引入开销 | 事务热路径退化 | 仅 2 次 `nanoTime` + 1 次环形写（0 锁/0 分配）；T6 以 1M 次采样 P95 设阈值 |
| p2p-sync 模块编译/测试面扩大 | Gate B 阻塞 | 端点改动同构既有 `DbClusterHandler`（5 行）；p2p-sync monitor 测试独立跑 |
| 环形缓冲 vs 桶直方图（Q2） | 精度/内存权衡 | 默认 A（精确）；若内存敏感改 B |
| `wal.replayLag`（Q3）语义与命名不符（无 WAL） | 命名歧义 | 字段名沿用 roadmap `wal.replayLag`，Javadoc 显式说明「binlog 复制 lag」口径 |
| 注册源为 0 → 端点全 0 | 用户误判「无数据」 | Q5 语义：全 0 快照 + `ok=true`；文档/Javadoc 说明需先注册源 |
| 既有 `DsClusterMonitor` 返回结构变更 | 前端解析失败 | 仅 `replicaLag` 值语义升级（字段名/类型不变），前端无需改 |
| Gate B 已知基线 fail | 批非全绿 | 沿用既有口径（`P28_T6.tr64`）不阻塞；flaky 单类隔离重跑定性 |

---

## 8. 交付物清单

- 源码（**新包 2 类**）：`cluster/stats/DbStats.java`、`cluster/stats/DbStatsRegistry.java`
- 既有类改动（方法追加 / 内部插桩）：`binlog/DsBinlogReplicator.java`（+`replicationLag`）、`database/tx/DsTransactionManager.java`（commit 计时）、`cluster/DsClusterMonitor.java`（replicaLag 真值 + 移除 TODO）、`p2p-sync` `P2PSyncMonitorServer.java`（+端点）
- 测试：`ds/P55_T1_*.java` ~ `P55_T6_*.java`（6 文件）+ p2p-sync 端点测试（如命中）
- 文档：本 `spec.md` + `tasks.md`；`docs/dsdb-design-status-and-roadmap.md`（§五 P3② 兑现标记 + §五 P1③）；Project Memory 追加
- Stage 5：逐路径 `git add`（仅 P55 相关）→ `git commit -F <msgfile>` → `git push origin main`

---

## 9. 签批区（Gate A，需用户 VERBATIM 批准）

> ✅ 用户已以「继续」采纳 §0 全部默认裁决（Q0-Q5 全部 = A）。签批后进入 Implement（SP3.1 铁律）。

- [x] **§0 Q0 Scope**：`Q0 = A`（完整 §五 P3② + P1③）
- [x] **§0 Q1 落包**：`Q1 = A`（新包 `cluster/stats/` 2 类）
- [x] **§0 Q2 p99 采样**：`Q2 = A`（环形缓冲精确 p99）
- [x] **§0 Q3 wal.replayLag**：`Q3 = A`（binlog 复制 lag 真值）
- [x] **§0 Q4 端点**：`Q4 = A`（同构内嵌 `DbStatsHandler`）
- [x] **§0 Q5 降级语义**：`Q5 = 无源 → 全 0 快照 + ok=true`
- [x] **§1 Scope**（聚合快照 + 端点 + replicaLag 真值；MVP 边界 0 越界）
- [x] **§2 红线 R1-R10**（ZeroLeak3 ≤3 + NG-3 + R10 零新线程 + 端点语义确定 + 移除既有 TODO）
- [x] **§3 架构**（新包 2 类）
- [x] **§4 接口契约**（DbStats / DbStatsRegistry / replicator / txn / endpoint）
- [x] **§5 测试**（35 TR = 5+6+5+5+9+5；Gate B 拆批）
- [x] **§6 逆指标** / **§7 风险与回退** / **§8 交付物清单**

**用户批准原文（VERBATIM）**：`继续`

---

## 10. 实装偏差表（Gate C 回填）

| 编号 | 计划 | 实装 | 原因 |
|------|------|------|------|
| D1 | T4 单一测试类 `P55_T4_EndpointReplicaLagTest`（p2p-db，5 TR） | 拆为 `P55_T4_ReplicaLagTruthTest`（p2p-db，④⑤ 2 TR）+ `P55_T4_DbStatsEndpointTest`（p2p-sync，①②③ 3 TR） | 端点测试须与 `P2PSyncMonitorServer` 同模块（p2p-sync）；TR 总数不变（5），跨模块分布 |
| D2 | §5.1 拆批命令 `-Dtest=javax.net.p2p.filesync.monitor.*` | `-Dtest=P2PSyncMonitorServerTest,P55_T4_DbStatsEndpointTest`（显式类名） | surefire 不支持包级通配 `*`；实跑须显式类名 |
| D3 | Gate B 批 2 全绿 | 批 2 = 112 TR / **1F**（`P28_T6.tr64`） / 0E | 已知基线（`P28_T6.tr64` 裸打印计数），spec §7 已登记、不阻塞 |

---

## 11. Probe 锚点（自核实锤，非转述）

| 锚点 | 文件 | 核实结论 |
|------|------|---------|
| roadmap §五 P3② 原文 | `docs/dsdb-design-status-and-roadmap.md:408-410` | 4 gauge（`cache.hitRate`/`wal.replayLag`/`txn.latency.p99`/`compaction.bytesPerSec`）+ `/sync/api/db-stats` endpoint |
| roadmap §五 P1③ 原文 | `docs/dsdb-design-status-and-roadmap.md:204` | SyncMonitorServer 监控 DsDb replication lag（`lastAppliedSequenceId` per shard） |
| CacheStats 数据源 | `core/DsMemory.java:119-169,1400-1410` | `CacheStats` 14 字段 + `getCacheStats()`；命中率 = `coldReadHits/(coldReadHits+coldReadMisses)` |
| compaction 数据源 | `fulltext/sched/CompactionStats.java:28-139` + `CompactionScheduler.java:140 stats()` | 8 `AtomicLong`（`ticks`/`mergedBytes`/`runsSucceeded`…）；`stats()` 可达 |
| 无 WAL replay lag | `core/DsMemory.java`（Grep `replayLag` 0 命中） | `dsdb` 无 WAL 对象；真实 lag 只在 binlog 复制侧 |
| binlog 复制游标 | `binlog/DsBinlogReplicator.java:65-67,88-90` | `currentCursorSequence()` + `sourceDb.getBinlogStore().getCurrentPosition()` → lag = 差 |
| txn 无耗时统计 | `database/tx/DsTransactionManager.java:114 commit` | `commit` 无计时；插桩点确定 |
| replicaLag TODO | `cluster/DsClusterMonitor.java:86` | `server.put("replicaLag", 0L); // TODO: 后续从副本同步状态计算` |
| 端点挂载点 | `p2p-sync/.../P2PSyncMonitorServer.java:52-60,103-108,249-257` | 9 个 `createContext`；`DbClusterHandler` 5 行同构；`writeJson`/`toJson` 既有 |
| 无 DsMemory 注册表 | Grep `getCacheStats()`/`new DsMemory` | 无全局实例注册表 → 需引入注册表（Q1） |
| ZeroLeak3 参考 | P54 spec §2 R1 | 新包 top-level ≤ 3；既有包 0 新增 `.java` |
