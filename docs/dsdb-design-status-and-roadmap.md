# DsDb 分布式数据库 — 设计现状与未来方向

> 生成时间：2026-08-09
> 目标读者：p2p-net-storage 核心开发
> 配套模块：`p2p-db`（核心存储引擎）、`p2p-sync`（主使用方）、`p2p-core`（P2P RPC 通道）

---

## 一、现状：四层架构总览

DsDb 目前是一套**自研持久化 KV + 行/列存储 + ORM + P2P RPC** 组合的嵌入式（可远程）数据库，核心代码集中在 `p2p-db` 模块。模块 4 层清晰分层，自底向上依次为：

| 层次 | 核心类 | 位置（相对模块根） | 关键能力 |
|------|--------|--------------------|----------|
| **① 基础 I/O 层** | DsMemory | `src/main/java/com/q3lives/ds/core/DsMemory.java` | 64KB 块、堆内 byte[]、LRU-16 采样淘汰、脏页跟踪、syncStore |
| **② 集合原语层** | DsHashMap / DsHashSet / DsList / DsString | `src/main/java/com/q3lives/ds/collections/`、`src/main/java/com/q3lives/ds/core/DsString.java` | 持久化 hash 表（master index + bucket chain + free ring）、字符串字典去重 |
| **③ 存储引擎层** | DsFixedBucketStore（行存）、ColumnarStore（列存雏形）、DsKVStore | `bucket/DsFixedBucketStore.java`、`database/columnar/ColumnarStore.java`、`kv/DsKVStore.java` | 定长桶 page allocator、列存 `rowId→valueId` 映射、chunked KV 自动扩容 |
| **④ ORM + 远程层** | DsDatabaseLocal / DsDatabaseServer + 26 个 P2P DB Handler | `database/DsDatabaseLocal.java`、`database/DsDatabaseServer.java`、`src/main/java/javax/net/p2p/server/handler/` | DsTableAdapter 对象序列化、OneToOne/OneToMany/ManyToMany 关系、P2P 远程调用 |

---

## 二、分层细节拆解

### 2.1 基础 I/O 层：DsMemory（最核心底座）

关键实现（`DsMemory.java`）：

1. **存储形态 = 堆内 byte[] + RandomAccessFile 回写**
   - 不是 MappedByteBuffer 直写，而是 `dataBytes: List<byte[]>` 每个元素 `new byte[64KB]`，通过 `readBlockFromFile/writeBlockToFile` 手动和磁盘同步。
   - 好处：OOM 可控（由 JVM heap bound），不受 OS mmap 回收影响；坏处：需要手动 sync，没有 kernel page cache 的自动预读。

2. **LRU 缓存 = 16 slot 采样近似 LRU（Redis 同款算法）**
   - `evictOne(avoidBufferIndex)`：遍历 `bufferLastAccessNanos`，在候选里选 nanoTime 最小的 16 个，取最小 victim 驱逐。
   - 驱逐前若脏页（`dirtyBufferIndices`），先 `writeBlockToFile` 回写磁盘。
   - 默认上限：`ds.memory.maxCachedBlocks=2048` ≈ `2048×64KB = 128MB`。

3. **并发控制 = 粗粒度 bufferLock + 256 stripe RWLock + evictionLock 三重结构**
   - `bufferLock`（全局 ReentrantLock）：保护 `dataBuffers/dataBytes/activeCachedBlocks` 等 list 结构变更。
   - `dataBufferLocks[256]`（stripe 分片）：驱逐时先拿对应 stripe 的 writeLock，保证并发读不会读到正在 flush 的块。
   - `evictionLock`：保证同一时间只有一个线程执行 LRU 淘汰循环，不会两个线程同时 victim 同一个 slot。

4. **落盘策略 = 用户手动 sync() + 强同步 100ms 模式**
   - `syncStore()`：用 `tryLock()`，若锁被抢则跳过本次，丢给下次 sync。⚠️ **可靠性缺口：并发写时 sync 可能静默跳过，无 WAL 兜底。**
   - 上层 DsHashMap 有 `setSyncModeStrong100ms()`（被 P2PSyncStateStore 8 个 map 使用），说明已有定时刷盘机制但 DsMemory 自身无心跳线程，需调用方驱动。

5. **CacheStats 可观测**（已完整）：max/active blocks、cachedBytes、dirtyCount、evictionAttempts/Success/Bytes、evictionDirtyCount。P2PSync 模块 `DsMemoryEvictionTest` 3 tests PASS 验证了 LRU 行为。

### 2.2 集合原语层："搭积木式"持久化原语

这是 DsDb 的设计亮点 — **不先做数据库再做表，而是先做 4 个可独立使用的持久化原语**，然后调用方按需要"组合成表/队列/状态机"。

在 p2p-sync 里的两个典型组合范式：

#### 范式 A：DsPersistentQueue（4 原语组合成 FIFO 队列）
`p2p-sync/src/main/java/javax/net/p2p/filesync/store/DsPersistentQueue.java`

| 原语 | 文件名 | 职责 |
|------|--------|------|
| `DsHashSet entryIds` | name.entries.set | FIFO 顺序，物理插入顺序 = dequeue 顺序（entryIds.iterator() 取最早） |
| `DsHashMap entryIdToPayloadId` | name.entry_payload.map | entryId → payloadId（字符串字典键） |
| `DsHashMap meta` | name.meta.map | 存 `META_NEXT_ID` 自增键 = 单调队列号 |
| `DsString payloadStrings` | name.strings/ | 实际 payload 内容，字符串去重字典 |

所有方法用 `synchronized (this)` 包裹，保证 enqueue/dequeue/remove 原子性（但跨原语无 WAL，进程崩溃可能出现 entryId 写入但 payloadId 未写入的半写状态）。

#### 范式 B：P2PSyncStateStore（36 sets + 10 maps + 3 dicts 组合成 4 阶段状态机）
`p2p-sync/src/main/java/javax/net/p2p/filesync/sync/P2PSyncStateStore.java`

- 9 类事件（DIR_CREATE / FILE_DELETE / FILE_MOVE …）× 4 stages（ACTIVE/STARTUP/INFLIGHT/FAILED）= 36 `DsHashSet`
- 8 个 `DsHashMap` 存失败重试次数、失败时间、副本状态、rename source 路径等 side metadata
- 3 个 `DsString` 字典：fileIdStrings / failureReasonStrings / replicaStateStrings —— 将长字符串转成 long id，减少 set/map 存 string 的开销（DsHashSet 存 long 比存 blob 快 3-10x）

### 2.3 存储引擎层：行存 + 列存双轨并行

#### 行存 DsFixedBucketStore — 定长桶 + 变长分配
- `getNewId(space, type, length)`：按空间分配新行 id；`update(space, type, id, bytes, KEEP_BUCKET)`：尽量原地更新，不够则迁移；`get(space, type, id, off, len)`：定位桶 + 精确读。
- 被 DsDatabaseLocal ORM 和 ColumnarStore 同时依赖，是**底层唯一的 page allocator**。

#### 列存 ColumnarStore — 雏形已完整，但缺扫描/压缩
`p2p-db/src/main/java/com/q3lives/ds/database/columnar/ColumnarStore.java`：
- 元数据三件套：`TableMetaStore`（表字段定义）、`ColumnRegistry`（列 id 分配 + 软删除标记）、`RowIdSequenceStore`
- 每列一个独立 `DsHashMap<rowId, valueId>`，value 存在 DsFixedBucketStore 对应 type 空间里
- `removeRow(rowId)`：逐列 remove，保证行级逻辑删除 — 但**无批量列扫描、无压缩、无向量化计算**，离 ClickHouse/Vertica 那种真实列存还有距离。

### 2.4 ORM + 远程层：Schema 驱动 + P2P RPC

#### DsDatabaseLocal（嵌入式 ORM）
`p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java`：
- `putEntity()`：DsTableAdapter.toBytes() → 长度校验 → bucketStore.update()；若 id=0 自动用 bucketStore.getNewId() 分配。
- `getTable(id, withRelations=true)`：反序列化后，按 @DsOneToMany/@DsManyToMany 注解去 `relationMapCache` 读关联 DsHashMap，拼完整对象图。
- Schema 校验：`sampleRowLength()`（反射实例化 sample 对象转 byte[]）长度必须 == `EntityIndexUtil.indexOf()` 计算的 rowLength，**不允许字段增减（schema 硬绑定）**。

#### DsDatabaseServer（远程 RPC 客户端）+ 26 个 Server Handler
`p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseServer.java` + `p2p-db/src/main/java/javax/net/p2p/server/handler/`：
- 完整 RPC 面：`DB_ENTITY_PUT/GET/QUERY_IDS`、`DB_ROW_PUT/GET/QUERY_IDS_STREAM`、`DB_COL_PUT/GET`、`DB_META_PUT/GET`、`DB_INDEX_CREATE/DROP/LIST/INFO`、`DB_ROW_COUNT`、`DB_ROW_EXISTS_BY_QUERY`、`STD_CANCEL`
- Brain 里的 distill proposal 已规划：COUNT、EXISTS、NOT_IN、OR_DNF、STD_CANCEL 5 个算子，框架已准备好，只差工程实现。

#### 分布式能力现状：标志位先行，逻辑未落地
DsMemory 两个字段 `isPatitioned / isDistributed`：
```java
protected boolean isPatitioned = false;  // 纵向：同服务器分磁盘（空间隔离 + 并行 IO）
protected boolean isDistributed = false; // 横向：多服务器（分片 + 复制）
```
**目前两个 boolean 从未被置 true，也无路由/分片逻辑代码** — 这是"设计上预留分布式、实现上仍是单节点嵌入式"的现状定位。

---

## 三、p2p-sync × DsDb：当前依赖深度与约束

### 调用面（p2p-sync → p2p-db）
```text
P2PSyncStateStore        → DsHashMap ×10  DsHashSet ×36  DsString ×3
DsPersistentQueue<T>     → DsHashMap ×2  DsHashSet ×1   DsString ×1
SyncReceiverStateStore   → DsHashMap (pending-path lock, write_conflict tracking)
```
合计：**单节点单同步任务 = 46 个 DsMemory 派生实例 × 每个默认 128MB 缓存上限 = 理论缓存上限可控，但无跨实例共享 page cache，实际 RAM 开销偏冗余**。

### 约束（从使用模式反向推导出的 DsDb 短板）
1. **无事务**：P2PSyncStateStore 跨 DsHashSet（stage 迁移）+ DsHashMap（metadata 更新）的原子操作需要调用方手动 try/finally，崩溃时可能出现"INFLIGHT 加了、ACTIVE 还没删"双写重复。
2. **无 Crash Recovery**：`tryLock()` 跳过 + 无 WAL，进程崩溃后需等下一次调用 `sync()` 但未 sync 的 buffer 已经丢 — 当前 p2p-sync 靠 setSyncModeStrong100ms + 启动时从 state 目录 re-scan 来兜底。
3. **只有 hash 索引**：DsHashMap/hashSet 只能 `O(1)` by key 点查；p2p-sync 想做 "query all INFLIGHT events where lastRetriedAt < T-300s" 目前只能遍历 set，复杂度 `O(n)`。
4. **无法跨实例共享**：46 个 DsMemory 实例各有独立的 LRU/脏页池/锁 stripe，不能合并，多任务并行时内存呈 N×128MB 增长。

---

## 四、现存可靠性与性能缺口（按风险排序）

| # | 风险 | 影响面 | 根因代码位置 |
|---|------|--------|-------------|
| 1 | **无 WAL，sync 并发跳过** | 崩溃丢数据 | DsMemory.syncStore() `tryLock()` 抢不到就直接 return |
| 2 | **读 long[] 时错用 getInt** | 跨页读返回高 32 位永远是 0 | DsMemory.loadLongOffset(position, long[]) 三处 `values[i]=buf.getInt()` |
| 3 | **loadLongOffset 跨页死循环边界** | 跨块读第二页及之后永不进入 | `for (i=0; i>=BLOCK_SIZE; i+=LONG_SIZE)` 条件永远 false |
| 4 | **Schema 硬绑定** | DsTableAdapter 加字段 = 旧数据全废；无 online DDL | DsDatabaseLocal.createMeta() rowLength mismatch 直接 throw |
| 5 | **LRU 采样迭代 + 排序**：n 候选内 O(n log 16)，非近似但慢 | 大规模 activeCachedBlocks 上千后 GC 压力高 | DsMemory.evictOne() 全量 entrySet 遍历 |
| 6 | **bufferLock 全局**：loadBuffer 任何读都拿全局锁 | 多读吞吐卡在单锁；不能并发 load 不同块 | DsMemory.loadBuffer() 入口第一行 bufferLock.lock() |
| 7 | **跨页写的 int/long 递减计数 bug**：`i = buf.remaining(); i>=INT_SIZE; i-=INT_SIZE` 递减次数少算 1 | 大数组写尾部丢字节 | DsMemory.storeIntOffset/loadLongOffset 尾部剩余字节处理循环 |
| 8 | **DsHashMap 无范围查询**：只能点查；RANGE / GT / LT 操作需遍历 | p2p-sync 想做时间范围回放只能 O(n) | DsHashMap 内部 bucket chain 结构天然不支持 range |

---

## 五、未来方向（按 P0-P3 优先级排序，对齐高强度生产级目标）

### P0：可靠性与正确性（必须先做，否则分布式越跑越错）

**① WAL + 原子多写（解决风险 #1）**
- 在每个 DsMemory 派生实例根目录追加 `_wal.log`，写操作顺序：(1) 写 WAL 记录 `(blockIdx, newBytes)` → `fsync()` WAL → (2) 写 `dataBytes[idx]` + 标脏。
- DsPersistentQueue/P2PSyncStateStore 的跨原语操作包装成 `Txn{op1,op2,...}` → 一条 batch WAL entry → 一次 fsync，解决 `ACTIVE→INFLIGHT` stage 迁移崩溃半写问题。

**② loadLongOffset/storeIntOffset 死循环/截断位修复（解决风险 #2/3/7）**
- `values[i]=buf.getInt()` → `buf.getLong()`；
- 跨页循环 `for (i=0; i<BLOCK_SIZE; i+=LONG_SIZE)` 而不是 `i>=BLOCK_SIZE`；
- `i = buf.remaining(); i>=INT_SIZE; i-=INT_SIZE` 先算 `remaining / INT_SIZE` 得到正确 count。

**③ Crash Recovery：启动时 replay WAL**（与 ① 配套）
- 启动时 scan `_wal.log` 找到最后一个 commit 标记，把未 apply 到 `dataBytes` 的 block 重新 apply；WAL 做 checksum，坏记录直接 stop 不继续 replay。
- 校验通过后 rename `_wal.log → _wal.checkpoint.<ts>`，定期删除。

### P1：多副本 + 分片（让 "isDistributed" boolean 真正活起来）

**① 复制协议：复用现有 MultiEndpoint + P2PSync 的 Raft-like ACK/Retry**
- p2p-sync 已实现：`SyncConflictPolicy.LAST_WRITE_WINS`、pending-path 锁、acker.fail 不自动 retry、人工按钮。
- DsDb 复制层直接"拿来主义"：把 rowId → hash(rowId) % N → 选择 primary 节点，同时 FanOut 到 R 个 secondary（R=2 起步，可配）。
- 复制状态机 = `DsMemory.syncStore()` 的增量 WAL：每个节点按 same sequenceId 重放，和 p2p-sync 的 event queue 同构。

**② 分片路由（isPatitioned 先做）：同机多磁盘 → 跨机分片**
- 先本地 partition：`bucket hash(dsHome) % K` 落到不同磁盘路径，解决现在 46 DsMemory 实例同盘 IOPS 打满的问题；
- 再分布式 partition：引入 `DsShardRouter`（consistent hash 1024 vnode），`DB_ROW_PUT` 客户端先本地 router 找对应 shard → 发对应 P2P endpoint。

**③ 与 p2p-sync 的协同点**：让 SyncMonitorServer 同时监控 DsDb replication lag（`lastAppliedSequenceId` per shard），直接用现有 `recentCompletedUploads` 同构面板展示。

### P2：功能与性能（让 DsDb 真正能跑复杂查询）

**① 二级索引：补齐 EqIndexStore 骨架 → RANGE 索引**
- p2p-db 已有 `database/columnar/index/EqIndexStore.java`（等值索引骨架），先把 putValue/removeValue 自动维护 eq 索引；
- 然后扩展 `ORDER BY / > / < / BETWEEN`：每个值做 (value, rowId) 组合键存在 DsHashMap 的 ordered set（新 DsSkiplist 或复用 DsHashMap 有序链），支持按值 scan + 反查 rowId。
- p2p-sync 场景直接收益：`P2PSyncMonitorServer /sync/api/queues?orderBy=updatedAt&status=FAILED` 从 O(n) 扫 set → O(log n) 索引 seek。

**② MVCC + 快照隔离**
- row version = (valueId, timestamp)，存 `<rowId, versionId>`；读操作带 `readTs` 拿快照版本，写操作写新版本不覆盖旧版本；
- ColumnarStore 天然适合 MVCC（每个列单独 append-only，旧版本保留直到 compaction）。
- 收益：长事务读不阻塞写，p2p-sync 的 Monitor 长轮询 `/sync/api/queues` 不会卡主 enqueue 主路径。

**③ Compaction + 冷热分层**
- 冷数据（lastAccessed > 30d）的 DsMemory 块 flush 后从内存淘汰并 gzip 压缩落到 `_cold/` 子目录；
- 需要时懒加载解压缩，配合 LRU 2Q（hot/cold 双队列）减少冷热抖动；
- ColumnarStore 追加 LZ4 列式压缩（pom.xml 已有 lz4-java 1.8.0 依赖）。

### P3：生产化（企业级能力补齐）

**① Online Schema 变更**
- DsTableAdapter 增加 `schemaVersion` 字段；新 schema version 加列时，ColumnarStore 新增一个 colId 并标记 default 值，读时回退 default 补全；
- `EntityIndexUtil` 增加 version 化 rowLength 校验，不再抛 "mismatch" 异常。

**② 可观测性增强**
- 现有 CacheStats 扩展：每个 DsMemory 派生实例挂 Micrometer-like Gauge：`dsdb.cache.hitRate`、`dsdb.wal.replayLag`、`dsdb.txn.latency.p99`、`dsdb.compaction.bytesPerSec`；
- P2PSyncMonitorServer 增加 `/sync/api/db-stats` endpoint。

**③ 备份与恢复**
- `SSTable`-style snapshot：syncStore() 后创建硬链接 snapshot 目录，增量备份 WAL；
- 恢复流程 = 恢复 snapshot + 重放后续 WAL，与 P0 #3 的 Crash Recovery 共用同一套代码。

**④ Query Planner / Cost Model**
- OR_DNF / NOT_IN / COUNT / EXISTS 5 个算子落地后，加一个轻量 planner：
  - 是否命中 eqIndex → index scan；
  - 否则走全表 set 扫描；
  - 多条件 AND 先选最选择性的 index。

---

## 六、推进路线图（与 p2p-sync P1/P2 对齐的时间轴）

| 阶段 | 周期 | DsDb 交付物 | p2p-sync 收益 |
|------|------|-------------|---------------|
| P0 | 1~2 周 | WAL + fix loadLongOffset bugs + recovery replay | 跨原语状态机不丢数据、sync() 静默跳不过 |
| P1 | 3~4 周 | 分片路由 + MultiEndpoint FanOut 到 R=2 副本 + Monitor lag 展示 | 单节点宕机切换副本，sync queue 不中断 |
| P2 | 4~6 周 | 二级 eq/range 索引 + MVCC + LZ4 列压缩 | status=FAILED&orderBy=updatedAt 查询毫秒级；长轮询不阻塞主链 |
| P3 | 持续迭代 | Online schema、db-stats endpoint、SSTable 备份 | 多租户上线支持、生产事故可快速回滚 |

---

## 七、关键代码索引（便于快速跳转到对应实现）

| 能力 | 文件入口（相对仓库根） | 锚点方法/说明 |
|------|------------------------|---------------|
| LRU 驱逐核心 | `p2p-db/src/main/java/com/q3lives/ds/core/DsMemory.java` | loadBuffer()（块加载）、evictOne()（16-slot 采样 LRU） |
| 持久化 FIFO 队列 | `p2p-sync/src/main/java/javax/net/p2p/filesync/store/DsPersistentQueue.java` | enqueue() / peek() / remove() |
| p2p-sync 状态机 (36 sets) | `p2p-sync/src/main/java/javax/net/p2p/filesync/sync/P2PSyncStateStore.java` | 构造器；QueueKey × QueueStage = 36 queues |
| ORM 行存 | `p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java` | putEntity() / getTable(id, withRelations) |
| 列式存储 | `p2p-db/src/main/java/com/q3lives/ds/database/columnar/ColumnarStore.java` | putValue() / getValue() / removeRow() |
| 远程 RPC 客户端 | `p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseServer.java` | load()（配置加载+登录）、putTable() / getTable() |
| 26 个 Server Handler 入口 | `p2p-db/src/main/java/javax/net/p2p/server/handler/` | DB_ROW_* / DB_ENTITY_* / DB_COL_* / DB_INDEX_* |

---

## 八、最优先的 1 个立即动作（不碰架构，纯 bug fix，半天可交付）

**修复 loadLongOffset / storeIntOffset 数组读写截断 bug（风险 #2/3/7）**。

p2p-sync 目前没踩雷是因为没人调用 `loadLongOffset(position, long[])` 这个重载，但一旦 P1 二级索引做批量 long[] rowId 返回，立刻 hit。步骤：

1. **单测先行**：新增 `DsMemoryBulkArrayTest`，覆盖 3 种跨 BLOCK_SIZE 边界场景：
   - 场景 A：数组刚好在单个 BLOCK 内（不跨块）；
   - 场景 B：数组首元素在块尾，跨 2 个块；
   - 场景 C：数组横跨 3 个或更多 BLOCK。
   每种场景分别测 `storeLongOffset → loadLongOffset 往返` 与 `storeIntOffset → 对应 load 往返`，最后断言与写入前原始数组 `Arrays.equals()` 精确相等。

2. **修代码 3 处**：
   - `loadLongOffset(position, long[])` 内部 `buf.getInt()` 改 `buf.getLong()`（共 3 处）；
   - 跨块 for 循环条件 `i>=BLOCK_SIZE` 改为 `i<BLOCK_SIZE`；
   - 块内剩余元素递减计数改为 `int cnt = buf.remaining() / ELEM_SIZE; for (int j=0; j<cnt; j++)`，不再用 `i>=ELEM_SIZE; i-=ELEM_SIZE` 边界漏洞写法。

3. **全量回归**：p2p-db 单模块全 PASS → p2p-sync 70 tests 全 PASS → git commit + push。
