# P14 Spec — OQ3：ACID 事务（row-level RR 可重复读 + 多叶子原子提交 + TPC-C New-Order 兼容）

> 立项：P13 OQ4 闭环后用户默认选择 OQ3；周期：2026-09-23 → 2026-10-21（4 周 MVP，可提前 1 周）；会话 ID：P14-OQ3-ACID

---

## 1. 问题与用户

### 1.1 现状（P13 交付后 4 个能力缺口）

P10~P13 已完成：**元/数据/同步/索引 四层承载 + Bloom 优化**：
- P10：7 类元数据 `/orm/{row,column,dbs}` Registry 承载，可逆 SHA256 manifest
- P11：3 类 DML 真实数据 `/orm/data/{row,column,group}` 三棵子树 + `AbstractRegistryDataBackend.atomicModifyBytes`（256-entry striped lock，256 次 CAS）
- P12：跨节点 2 路同步 + `/orm/sync/tx/<txId>/state` 段预留 + CAS side-version 幂等
- P13：Bloom Filter eq 索引零 IO + 列存 3 `put*Batch` 批量 1.214x 性能
- **底层 MVCC 已有**：行存 `DsDatabaseLocal` VersionRecord(beginTs/endTs/rowId/prev/dataId) + `readTimestamp` 可见性；列存 TableMetaStore 缺 `hasMvcc` 字段（降级在行存层兜底）
- **原子提交能力仅单叶子**：`atomicModifyBytes(registryPath, op, seed, bucketGetter, bucketSetter)` 仅针对单条 `/orm/...` 叶子路径；跨叶子（如 `row payload + col value + eq index + rowIdSeq allocate + group composite` 5 类叶子）**无 prepare/commit 两阶段协议**

→ OQ3 的核心缺失：
1. **事务生命周期入口**：代码库无 `beginTransaction()` / `commit()` / `rollback()`、无 `TransactionManager`、无 `ThreadLocal<TxContext>`（grep 0 命中）
2. **跨叶子原子性（2PC 简化版）**：单个事务可能写 `row payload + 2 列 + 1 组 + eq 索引 3 条`=6~20 片 Registry/bucket 叶子；崩溃重启后无法回滚到"要么 0 要么全部"
3. **隔离级 RR（可重复读）**：`getTable(id, readTimestamp)` 仅支持按参数传 readTs，但事务内两次读同一 rowId 会因 JVM 全局 `writeTimestamp.incrementAndGet` 推进读到不同版本；事务应绑定 `readTs = beginTs` 快照、`writeTs = commitTs`
4. **TPC-C New-Order 基线缺失**：New-Order 是 OLTP 黄金标准（5 表 1 事务：Warehouse→District→Customer→Order→OrderLine N 行 + Item + Stock 更新 10~15 条写），目前 0 对应 @DsTable schema，无 baseline 负载

### 1.2 用户与收益链路

| 用户/场景 | 直接收益 | 最终打通 |
|-----------|----------|----------|
| 业务层订单（TPC-C New-Order） | 下单事务 5 表 10~15 条写 → 要么全成功要么全回滚；RR 下两次读 customer.balance 一致 | 100% 覆盖生产迁移 MySQL/Postgres 的 OLTP 场景（TPC-C 是硬门槛） |
| 行存+列存混合写入方 | 行 putEntities + 列 putValueBatch + 组 putCompositeGroupBatch → 绑定同一 txId；崩溃后 TxCoordinator 从 `/orm/tx/<txId>` 自动回滚未提交预写 | 不再依赖"外部业务重试幂等"兜底，半写入永久消除 |
| OQ2 跨节点调用方 | `/orm/tx/<txId>/state` 段同步复用 P12 replicateKey 通道；2PC PREPARED→COMMITTED/ABORTED 后复制给 peer；跨节点 Raft 共识后续直接复用 | OQ6 3 节点共识只需加选主+日志复制，不用重写 2PC 骨架 |
| OQ4 Bloom + OQ5 Linux 烟测 | 事务内写 row payload 后，eq 索引 add + Bloom 置位绑定同一 commit；回滚时 Bloom 同步复位；Linux CFS 下锁竞争稳定性验证 | 整套 dsdb 从"单条写正确"→"多表多叶子事务正确" |

---

## 2. Goals（目标）与 Non-Goals（非目标）

### 2.1 Goals（4 周 MVP，按优先级）

**G1：事务生命周期 + 绑定 readTs/writeTs（RR 可重复读基础）**
- NEW 1 个 package-private `DsTransactionManager`：`begin(isolation=RR)` → `commit()` / `rollback()` + `ThreadLocal<TxContext>`（readTs=begin 时 writeTimestamp 快照；writeTs=commit 时 incrementAndGet 推进一次，整事务所有行共享同一 writeTs 版本号）
- NEW 3 个 public final facade 入口（挂在 DsDatabaseLocal，**不改任何老签名**）：`Transaction beginTransaction()`（默认 RR）、`Transaction beginTransaction(IsolationLevel level)`、`void runInTransaction(Runnable)` / `<T> T callInTransaction(Callable<T>)`；`Transaction` 实现 `AutoCloseable`（try-with-resources 未 commit 自动 rollback）
- 事务内 `getTable(id)` → 用 tx.readTs 替代 Long.MAX_VALUE；事务内两次读同一 rowId（MVCC 表）→ 返回相同版本（RR 硬特性）；非事务调用保持 Long.MAX_VALUE 语义，0 行为变化
- 隔离级枚举：`IsolationLevel.READ_UNCOMMITTED / READ_COMMITTED / REPEATABLE_READ（默认）/ SNAPSHOT`；MVP 仅实现 RR，其余 3 级抛 UnsupportedOperationException（避免混淆）

**G2：2PC 简化版 Prepare/Commit/Abort（跨 ≤100 叶子原子提交）**
- `/orm/tx` 新子树（RegistryMetaPaths helper 统一生成，零外泄）：`/orm/tx/<txIdHex16>/state`（PREPARING/COMMITTING/ABORTED/DONE 1B）、`/orm/tx/<txIdHex16>/begTs`、`/orm/tx/<txIdHex16>/writeTs`、`/orm/tx/<txIdHex16>/op/<opIdHex8>`（每条 op = type[1B:ROW_PUT/ROW_DEL/COL_PUT/GROUP_PUT/INDEX_ADD/INDEX_REM]+registryPath+payloadLen+payload[+expectedPrevBytesForCAS]）
- 2PC 三阶段模板：
  1. **PREPARING（持久化事务意图）**：写 `/orm/tx/<txId>` begTs/writeTs/allOps + state=PREPARING → fsync 成功 → 对每个 op 调 `atomicModifyBytes(path, CAS=only-if-prev==expected, new=payload)` 预留写入；所有 CAS 成功转 COMMITTING；任一 CAS 失败 → 写 state=ABORTING 进入回滚
  2. **COMMITTING（应用+版本）**：写 `state=COMMITTING`；事务内所有 row payload 的 `VersionRecord.beginTs=writeTs`；MVCC `headMap.put(rowId, newVersionId)`；同步 eq 索引 + Bloom 置位；最后写 `state=DONE`，保留 op 列表 30s 后 `cleanupTx(txId)` 懒删除
  3. **ABORTING（逆序回滚）**：写 `state=ABORTING`；按 `<opId>` 倒序遍历所有 ops：每条 op 记录了 prevBytes（CAS 时保留的旧值）→ `atomicModifyBytes(path, new=prevBytes)`；回滚所有 op 后写 `state=DONE`（保留用于观测）
- 崩溃恢复：`DsDatabaseLocal.start()` 构造后立即跑 `recoverIncompleteTxs()`：listChildren `/orm/tx` → state ∈ {PREPARING,COMMITTING,ABORTING} 未到 DONE → 重放：PREPARING→强制 ABORTING 回滚；COMMITTING→从 begTs/writeTs 重放剩余 op 到 DONE；ABORTING→继续回滚剩余 op

**G3：TPC-C New-Order 兼容基线（5 表 @DsTable + 1 事务 10~20 op）**
- NEW TPC-C 7 表定义（均为 package-private 测试内部类，不进生产 jar；字段兼容 TPC-C 5.11 规格：W=1 仓库）：
  1. `Warehouse`（1 行，主键 w_id：w_name/w_tax/w_ytd fixed）
  2. `District`（10 行，主键 d_id+d_w_id：d_name/d_street/d_city/d_tax/d_ytd/d_next_o_id 分配）
  3. `Customer`（3000 行，主键 c_id+c_d_id+c_w_id：c_first/c_middle/c_last/c_credit/c_credit_lim/c_balance/c_ytd_payment）
  4. `Order`（可变，主键 o_id+o_d_id+o_w_id：o_c_id/o_entry_d/o_ol_cnt 行计数）
  5. `OrderLine`（每个 Order 5~15 行，主键 ol_number+ol_o_id+ol_d_id+ol_w_id：ol_i_id+ol_supply_w_id+ol_quantity+ol_amount+ol_delivery_d）
  6. `Item`（100000 行，主键 i_id：i_name/i_price/i_data）
  7. `Stock`（100000 行，主键 s_i_id+s_w_id：s_quantity/s_dist_01~s_dist_10 10 列/s_ytd/s_order_cnt）
- 核心事务 `newOrder(wId, dId, cId, List<{iId,supplyWId,qty}>)`：BEGIN → 读 Warehouse.tax / District.tax / District.next_o_id allocate +1 / Customer.credit / 每条 OrderLine 读 Item.price + 读 Stock.quantity → 写 Stock -=qty / 写 1 条 Order / 写 N 条 OrderLine → 写 Customer.ytd_payment +=amount → COMMIT；全程 RR：两次读 Customer.credit 返回同一快照
- RR 硬验证：开启 2 线程并发 TX1=读 Customer→sleep 100ms→再读 Customer；TX2=在 100ms sleep 窗口写同一 Customer→断言 TX1 两次读结果 Arrays.equals（RR），TX2 写成功不影响 TX1 快照

**G4：正确性 + 性能门槛（33 测试 0F0E + 退化 ≤2.0×）**
- 正确性：P10 38 + P11 28 + P12 5（c10）+ P13 7（Bloom 4 + Bulk 3）基线 0F0E；新增 P14 专项 ≥ 24 tests：
  - TX 生命周期 3（begin+commit/rollback/close-autorollback）
  - RR 可重复读 3（单线程两次读/并发 TX2 写不影响 TX1/并发 deadlock 回滚）
  - 2PC 原子 6（2 叶/5 叶/20 叶全成功；第 5 叶 CAS 失败 → 前 4 叶 100% 回滚 byte-for-byte=原值；崩溃重启 PREPARING→ABORT；崩溃重启 COMMITTING→完成到 DONE）
  - Bloom 绑定事务 2（commit→Bloom 置位；rollback→Bloom 复位）
  - TPC-C New-Order 10（W=1，10 并发 newOrder 各 10 条 OrderLine 不超量、District.next_o_id 单调递增、各 Stock 减少正确=sum(OrderLine.quantity)、abort 1 条事务全回滚 Stock 不变）
  - `c11 AcidGatekeeper` ≥ 25 assertions（修饰符 6 / facade 方法数 6 / RegistryMetaPaths 5 / 2PC 签名 4 / TxManager+构造器 4）
- 性能：同 JVM N=2000 warmup=500，RR 事务 W=1 场景：① 单事务 5 叶 原子 commit P99 ≤ 2.0× 单条 putEntity 非事务 P99；② TPC-C New-Order（15 op/事务）并发 10 T × 100 tx → 总 throughput ≥ 非事务串行 for 循环 0.80×（锁代价 ≤25% 可接受）

### 2.2 Non-Goals（本阶段严格不做）
- ❌ **分布式跨节点 2PC（跨进程）**：本 MVP 仅单机 DsDatabaseLocal 内跨叶子（row+col+group+index）原子；跨节点 PREPARE→VOTE→COMMIT 需 OQ2 3 节点共识后另开 OQ7（预计 8 周）
- ❌ **死锁检测 + 超时回滚自动选牺牲者**：死锁 MVP 依赖 `StampedLock.writeLock 超时` 抛 `DeadlockSuspectedIOException` 触发调用方 rollback；全局 wait-for 图 OQ3-2 follow-up（预计 2 周）
- ❌ **隔离级 RC / RU / SNAPSHOT**：仅实现 RR（硬基线）；其余 3 级抛 UnsupportedOperationException，不抛静默降级
- ❌ **改 P10~P13 任何既有公开签名或路径常量**：6 门面类（DsDatabaseLocal/ColumnarStore/TableMetaStore/ColumnRegistry/RowIdSequenceStore/EqIndexMetaStore）public 方法 0 字符改；P10 7 元路径 + P11 3 数据路径 + P12 sync 路径 + P13 index 路径 **0 字符改**（由 c11 Gatekeeper 钉死）
- ❌ **新增 Maven 依赖**：2PC WAL 持久化用现有 `DsBinlogStore.append` 或 `Registry.putBytes` 做 op 列表，不加 Chronicle/WAL/Netty 任何 jar
- ❌ **TPC-C 标准 128MB W=10 + Payment/Order-Status/Delivery/Stock-Level 4 事务**：只覆盖 New-Order 主事务；另外 4 事务另开专项（预计 4 周）
- ❌ **列存 TableMeta 补齐 hasMvcc 字段**：保持 P13 Invariant 5 约定：MVCC 降级在行存 DsDatabaseLocal.putEntities 层，列存批量/单列调用方自行兜底

---

## 3. Functional Requirements（功能需求）

### FR1：RegistryMetaPaths 扩 `/orm/tx` 子树 + typed helper（零外泄）
1. 新增常量：
   - `TX_ROOT = ROOT + "/tx"`（`/orm/tx`）
   - `TX_OP_ROOT = TX_ROOT + "/op"`
2. 新增 typed helper（所有业务代码一律走 helper，零硬编码）：
   - `txState(txId)` → `/orm/tx/<txIdHex16>/state`
   - `txBegTs(txId)` → begTs 8B long LE
   - `txWriteTs(txId)` → writeTs 8B long LE
   - `txOpLeaf(txId, opId)` → `/orm/tx/<txId>/op/<opIdHex8>`（op payload packed）
   - `txIdHex(txIdLong)` → 16 位 0 补小写 hex（与 dataRowPayload rowIdHex 对齐，保证字典序=数值序）
3. 零外泄：grep -rn `"/orm/tx"` src/main/java → **仅 RegistryMetaPaths.java 常量声明 1 处命中**（c11 Gatekeeper C3 断言）

### FR2：DsTransactionManager（2PC + 崩溃恢复 + ThreadLocal 绑定）
1. **生命周期 API（package-private，仅 DsDatabaseLocal 持 1 单例）**：
   - `TxContext begin(IsolationLevel level)`：校验 level=RR（其他抛 UOE）；readTs = `writeTimestamp.get()` 快照；writeTs = `writeTimestamp.incrementAndGet()`（预留，commit 真正生效）；返回 txId = `txSeq.incrementAndGet()`；set ThreadLocal
   - `commit()`：执行 2PC → PREPARING → CAS 所有 op → COMMITTING → 应用索引+ Bloom → DONE → remove ThreadLocal
   - `rollback()`：PREPARING→ABORTING；若有 partial op 写入 → 按倒序 restore prevBytes → DONE；未 begin 就 rollback → no-op（安全）
   - `TxContext currentOrNull()`：返回事务上下文或 null（非事务调用方）
2. **崩溃恢复 `recoverIncompleteTxs()`（DsDatabaseLocal.start() 最后一步）**：
   - listChildren `/orm/tx` → 所有 state != DONE 的 txId
   - PREPARING → 强制 ABORTING：回滚所有已写入 op 的 prevBytes
   - COMMITTING → 从 op0→opN 重放所有 op（CAS 失败若因为已经是新值则跳过）
   - ABORTING → 从高 opId→op0 回滚所有未恢复 op 的 prevBytes
   - 完成后写 state=DONE，cleanup 延迟 30s 后 deleteSubtree
3. **RR 绑定**：`currentOrNull() != null` → 所有 `getTable(clazz, id)` 用 `ctx.readTs`；非事务 getTable → 原 `Long.MAX_VALUE` 语义 0 变化

### FR3：DsDatabaseLocal 3 NEW public final 事务入口（不碰老签名）
1. `public final Transaction beginTransaction() throws IOException`：默认 RR；`Transaction` 实现 `AutoCloseable` + `commit()/rollback()`
2. `public final Transaction beginTransaction(IsolationLevel level) throws IOException`：显式指定隔离级
3. `public final <T> T callInTransaction(Callable<T> c) throws Exception` / `public final void runInTransaction(Runnable r) throws IOException`：try-with-resources 语法糖；内部 `begin → call → commit；任何 Exception → rollback + 重新抛出`

### FR4：2PC 7 种 Op 类型（跨 row/col/group/index/Bloom 全链路）
op 打包格式（统一字节序 LE）：
- type[1B]：0x01=ROW_PUT、0x02=ROW_DEL、0x03=COL_PUT、0x04=GROUP_PUT、0x05=INDEX_ADD、0x06=INDEX_REMOVE、0x07=BLOOM_SET
- registryPathLen[2B] + registryPath[var]
- expectedPrevLen[4B]（-1 表示 absent = 预期 0 字节，CAS 才通过）+ expectedPrevBytes[var]
- newPayloadLen[4B] + newPayloadBytes[var]

每个 op 写入前必须记录 expectedPrevBytes（通过 `readLeafBytes` 先读保留），保证 ABORTING 时 100% 能恢复到原值。

### FR5：TPC-C New-Order 事务函数（测试基线，生产可直接复用 2PC）
```java
NewOrderResult newOrderTx(DsDatabaseLocal db, int wId, int dId, int cId, List<OLine> lines) throws Exception
```
1. BEGIN；读 District.next_o_id（通过 RowIdSequenceStore 等价 next_o_id allocate 自增）
2. 读 Warehouse.w_tax / District.d_tax / Customer.credit / Customer.discount
3. 每条 line：读 Item.i_price / Stock.s_quantity（s_dist_{dId}）→ Stock -= qty；if Stock<10 → Stock += 91（TPC-C 4.3.2 补满）+ s_ytd += qty；s_order_cnt += 1
4. 写 Order（1 行）、写 OrderLine（N 行）、写 Customer.ytd_payment、写 District.d_ytd +=amount、写 Warehouse.w_ytd += amount
5. COMMIT → 返回 {oId, totalAmount}
6. Abort 路径验证：lines 内任一条 Stock 减少后超出 s_quantity 阈值 → rollback 后读所有相关 Stock → Arrays.equals 与原值一致（2PC 原子性硬证明）

---

## 4. Non-Functional Requirements（非功能需求）

| 类 | 编号 | 指标 | 阈值 | 证据 |
|---|---|---|---|---|
| 正确性 | NFR-C1 | 历史基线 0F0E | P10 38 + P11 28 + P12 5(c10) + P13 7(Bloom4/Bulk3) **全部 PASS** | `mvn -Dtest=P10_*,P11_*,P12_SyncGatekeeperTest,P13_* test` |
| 正确性 | NFR-C2 | P14 新增专项 PASS | TX life-cycle 3 + RR 3 + 2PC 6 + Bloom 绑定 2 + TPC-C 10 = **≥24 tests 0F0E** | P14_Acid*Test.java |
| 正确性 | NFR-C3 | c11 AcidGatekeeper ≥ 25 assertions 全 PASS | A 修饰符 6（TxManager private final + 2 Backend 内嵌 static final + 3 辅助 protected）/ B facade 方法数 6（原方法数 ≤ 原+3，public final NEW 不超）/ C RegistryMetaPaths 5（TX_ROOT 值 / String const≥15 / txIdHex 16 位对齐 / txOp 含 seg / grep 命中≤1）/ D 2PC begin/commit/rollback/recover 4 签名存在 / E TxManager 3method + 构造器 1 | P14_AcidGatekeeperTest.java c11 |
| 性能 | NFR-P1 | 单事务 5 叶 P99 ≤ 2.0× 非事务 putEntity P99 | 2.00（硬门槛；含 fsync） | AcPerfTest：N=2000 warmup=500 5 轮 |
| 性能 | NFR-P2 | TPC-C New-Order 并发 10T×100tx throughput ≥ 串行 for 0.80× | 0.80（锁和 CAS 代价 ≤ 25% 可接受） | 同上；同 JVM 跑 |
| 可维护性 | NFR-M1 | `/orm/tx` 零外泄 | grep `"/orm/tx"` src/main/java → only RegistryMetaPaths 常量声明 1 行命中 | c11 C3 断言 |
| 可维护性 | NFR-M2 | 所有 NEW 方法 public final（防止外部 override 破坏 2PC 流程） | signature diff `ColumnarStore#put*Batch` 对齐，不做 protected/private open | c11 B1 反射 assert |

---

## 5. Constraints / Dependencies / Assumptions / Open Questions

### 5.1 Constraints（硬约束，违反即 Reject）
1. **NG-2（零改老签名）**：6 门面类 public 方法 0 字符改；所有事务入口 NEW public final 方法
2. **NG-3（零新 Maven）**：pom.xml diff 为空
3. **NG-4（零改既有路径）**：P10 7 元 / P11 3 数据 / P12 sync / P13 index 0 字符改
4. **RR only MVP**：其他隔离级必须抛 UOE，不能静默降级
5. **崩溃恢复必须幂等**：`recoverIncompleteTxs()` 连跑 2 次结果一致，不抛异常、不遗留 PREPARING

### 5.2 Dependencies
- 依赖 P11 `AbstractRegistryDataBackend.atomicModifyBytes` 256 CAS
- 依赖 P12 `/orm/sync` 预留段 + `DsRegistryReplicator.replicateKey`（P14 OQ3 内 txState 变更后自动 replicate 到 peer，跨节点 tx 状态一致）
- 依赖 P13 Bloom：INDEX_ADD / INDEX_REMOVE 后同步 BLOOM_SET / BLOOM_RESET op

### 5.3 Assumptions
1. 单机单进程：MVP 不考虑 2 进程并发写同一 DsDatabaseLocal dbHome（此场景走 OQ2 跨节点）
2. 2PC 阶段 PREPARING→ABORTING 回滚概率 ≤ 5%：大部分 commit 走 happy path，回滚 fsync 性能退化可接受
3. Stock.quantity 冲突用 CAS：TPC-C 10T×100tx 场景下 256 次 CAS 上限 99.9% 不超；超 256 次 → abort 事务
4. Windows CFS：JDK 21 virtual thread 可能造成 lock 粒度抖动，取 5 轮均值降噪

### 5.4 Open Questions（用户 Approve 前最后确认，暂空）
- Q1：TPC-C Item/Stock 100k 行初始化是否放测试 @BeforeAll（冷启动约 60s）还是另开工具脚本？→ 暂定 @BeforeAll，跑不动再调小 Item=10k Stock=10k
- Q2：崩溃重启 PREPARING→ABORT 是强制回滚（默认）还是让运维选？→ 暂定强制回滚，避免半写入脏数据
- Q3：2PC 期间 `replicateKey` 给 peer 的顺序是"state=PREPARING→state=COMMITTING→state=DONE"3 次还是只发 state=DONE 1 次？→ 默认 3 次（让 peer 跟随 2PC 进度）

---

## 6. Acceptance Criteria（验收标准 = rule / rubric 两类型）

| 编号 | 类型 | 可观测通过条件 | 证据 |
|---|---|---|---|
| AC-R1 | rule | `beginTransaction().commit()` 后，事务内所有 op 写入在非事务读时可见；`begin().rollback()` 后所有 op 写入 0 字节可见，byte-for-byte=原值 | P14_AcidLifeCycleTest 3 tests |
| AC-R2 | rule | RR：事务内两次 `getTable(Customer, cId)` 在并发线程 TX2 写同一 Customer 情况下，返回 `Arrays.equals`（SNAPSHOT）；TX2 写在 TX1 commit 后可见 | P14_AcidRepeatableReadTest 3 tests |
| AC-R3 | rule | 2PC 原子：5 叶事务第 5 叶 CAS 失败（注入 prev 不匹配）→ 前 4 叶全部恢复到原 bytes，`/orm/data/*` 对应路径 read 后与事务开始前 `Arrays.equals` | P14_AcidTwoPhaseTest.test5LeafRollbackByteForByteEquals |
| AC-R4 | rule | 崩溃恢复：测试进程 kill 在"state=COMMITTING 已写 op1~op5 未写 state=DONE"处 → 重启后 `recoverIncompleteTxs()` 返回 ≥1 已恢复，最终 state=DONE；所有 row 版本正确 | P14_AcidRecoverTest.testKillAtCommitting；用 ProcessBuilder + FileLock 模拟 |
| AC-R5 | rule | c11 AcidGatekeeper：25 assertions PASS（A修饰符6/B facade6/C RegistryMetaPaths5/D 2PC 签名4/E TxManager 4） | P14_AcidGatekeeperTest.java c11 |
| AC-R6 | rule | `/orm/tx` 零外泄：grep -rn `"/orm/tx"` src/main/java 仅 **RegistryMetaPaths.java 常量声明 1 处命中** | c11 C3 断言 + 手动 grep 报告 |
| AC-R7 | rule | Bloom 绑定事务：commit 后 `EqIndexStore.isBloomEnabled && bloom.mightContain(valueBytes)=true`；rollback 后 `mightContain=false`（fp 除外） | P14_AcidBloomBindingTest 2 tests |
| AC-R8 | rule | TPC-C New-Order：10 并发 TX × 100 tx → District.next_o_id 最终 = 初始值 + 成功事务数（单调递增，无重复/回跳）；总 Stock 减少量 = Σ 成功事务 Σ lines.quantity | P14_TpccNewOrderTest.test10T100tx |
| AC-R9 | rule | 历史回归：P10 38 + P11 28 + P12 5(c10) + P13 7 全部 PASS（0F0E） | `mvn -Dtest=P10_*,P11_*,P12_SyncGatekeeperTest,P13_* test` |
| AC-R10 | rule | 崩溃恢复幂等：recoverIncompleteTxs() 连跑 2 次，2 次完成后 `/orm/tx/*/state` = 全部 DONE（无 PREPARING/COMMITTING/ABORTING 残留） | P14_AcidRecoverTest.testIdempotent |
| AC-R11 | rule | 其他隔离级抛 UOE：`beginTransaction(READ_COMMITTED)` → 立即抛 UnsupportedOperationException（message 必须含 "MVP only RR"）；不静默降级 | P14_AcidIsolationLevelTest 3 tests |
| AC-R12 | rule | try-with-resources 自动回滚：`try (Transaction tx = db.beginTransaction()) { putEntity(X); throw new RuntimeException("abort!"); }` → X 0 可见（byte-for-byte 原） | P14_AcidLifeCycleTest.testTwrAutoRollback |
| AC-U1 | rubric（0~2，pass≥1） | 性能：单事务 5 叶 commit P99 / 非事务 putEntity P99 = ratio；2=≤1.50；1=≤2.00；0=>2.00 | AcPerfTest 5 轮均值 |
| AC-U2 | rubric（0~2，pass≥1） | 性能：TPC-C New-Order 并发 10T×100tx throughput / 串行非事务 for-loop for 1000 newOrder = ratio；2=≥1.0；1=≥0.80；0=<0.80 | AcPerfTestTpcc |

**验收通过条件：12 条 rule 全部 PASS + 2 条 rubric 得分 ≥1（阈值）**
