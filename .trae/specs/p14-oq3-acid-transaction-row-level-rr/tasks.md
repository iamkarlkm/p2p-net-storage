# P14 Tasks — OQ3：ACID 事务（row-level RR + 多叶子 2PC 原子提交 + TPC-C New-Order 兼容）

> 关联 spec：`.trae/specs/p14-oq3-acid-transaction-row-level-rr/spec.md`（同目录）；周期：2026-09-23 → 2026-10-21（4 周 MVP）
> 硬红线：SP4 用户批准 spec.md + tasks.md 前，T1~T8 任一步禁止写任何业务代码（Spec Mode Implement 步前硬约束，违者 Workflow Fidelity=0）

---

## 优先级与依赖链（串行执行，前置 completed 禁止后移）

```
T1(RegistryMetaPaths /orm/tx 子树 + txId/txOp typed helper + c11 C 组部分)
  → T2(NEW DsTransactionManager 单例 + ThreadLocal<TxContext> + 生命周期 begin/commit/rollback + 崩溃恢复 recoverIncompleteTxs + RR 绑定 readTs/writeTs)
    → T3(DsDatabaseLocal 3 NEW public final 事务入口 + 7 Op 类型打包 + 2PC 三阶段 PREPARING→COMMITTING→ABORTING)
      → T4(2PC 7 Op 接入行/列/组/索引/Bloom 5 大模块（复用 P13 eq 索引 + Bloom；复用 P11 atomicModifyBytes CAS）)
        → T5(正确性专项 ≥ 24 tests：TX life-cycle 3 + RR 3 + 2PC 原子 6 + Bloom 绑定 2 + TPC-C New-Order 10 + c11 AcidGatekeeper 25 assertions)
          → T6(JV 合跑 ≥ 78 tests 0F0E：P10 38 + P11 28 + P12 c10 5 + P13 7 = 78)
            → T7(性能专项 2 tests U1/U2 rubric：U1 5 叶事务 P99 ≤ 2.0×；U2 TPC-C New-Order 并发 ≥ 0.80× 串行 for)
              → T8(NeatFreak 三层同步：AGENTS.md P14 Invariant + roadmap P3 OQ-3 标 CLOSED + project_memory 追加 ACID 血训)
```

---

## Task 1：RegistryMetaPaths 扩 `/orm/tx` 子树（常量 4 + typed helper 5，零外泄）

- **AC Coverage**：AC-R6（`/orm/tx` 零外泄，仅 RegistryMetaPaths 1 处命中）、AC-R5（c11 C 组 5 assertions：TX_ROOT 值 / String const≥15 / txIdHex 16 位对齐 / txOp 含 seg / grep ≤1 处命中）
- **Dependencies**：None（冷启动第一，Implement 步 SP4 批准后首个执行）
- **Priority**：High
- **Scope（严格包含，不得越界）**：
  1. **4 个 NEW public static final String 常量**（P10/P11/P12/P13 所有旧常量 0 字符改）：
     - `TX_ROOT = ROOT + "/tx";` = `/orm/tx`
     - `TX_OP_ROOT = TX_ROOT + "/op";` = `/orm/tx/op`
     - （内部）`STATE_LEAF = "state";`、`BEG_TS_LEAF = "begTs";`、`WRITE_TS_LEAF = "writeTs";`
  2. **5 个 NEW public static typed helper**（txId = long → 16 位 0 补小写 hex，对齐 `rowIdHex` 风格，保证字典序=数值序）：
     - `txState(long txId)` → `/orm/tx/<txIdHex16>/state`（1B enum：0x01=PREPARING, 0x02=COMMITTING, 0x03=ABORTING, 0x04=DONE）
     - `txBegTs(long txId)` → begTs 8B long LE（事务快照 readTs）
     - `txWriteTs(long txId)` → writeTs 8B long LE（commit 生效版本号）
     - `txOpLeaf(long txId, int opId)` → `/orm/tx/<txIdHex16>/op/<opIdHex8>`（opId 0~255，8 位 hex）
     - `txIdHex(long txId)` → 16 位小写 hex（String.format("%016x", txId)，0 补左）
  3. **零外泄铁律**：业务代码手写 `"/orm/tx"` 禁止；所有 tx 路径走上述 helper；c11 C3 断言通过 `Java NIO walk 源文件检查`（跨平台，不用系统命令）。
  4. **零越界**：不得改 P10 7 元/P11 3 数据/P12 sync/P13 index 所有旧常量/helper 返回值（c11 C4 子断言钉死）。
- **Local Test Requirements（TRs，仅 rule/rubric）**：
  - **TR-T1-R1 [rule]**：`P14_AcidKeySpaceTest#ac_r1_tx_paths` PASS——assert TX_ROOT=`"/orm/tx"`；txState(0x1234L) contains `"tx/" + "%016x".formatted(0x1234L) + "/state"`；txOpLeaf(42L, 5) endsWith `"/op/" + "%02x".formatted(5)`；txBegTs 含 `begTs`；txWriteTs 含 `writeTs`。
  - **TR-T1-R2 [rule]（零外泄核心）**：`p14_acid_zero_leak_assert` PASS——`Files.walk(src/main/java)` 收集所有 .java，逐文件 Pattern `"\"/orm/tx\""` 匹配，**命中数 == 1 && 命中文件 = RegistryMetaPaths.java && 命中行 = TX_ROOT 声明行**。
  - **TR-T1-R3 [rule]（c11 C2）**：RegistryMetaPaths `public static final String` 常量数 ≥ 16（P13 末 ≥15 + 新增 TX_ROOT/TX_OP_ROOT 2 条）&& 所有 public fields 100% String（无 int/long/Object 公开静态字段暴露）。
- **Status**：pending
- **Blocked By**：SP4 用户批准 spec.md + tasks.md（必须拿到「User has approved the given files」才 T1→in_progress）
- **Completion Evidence**：（完成后填入）T1-R1/R2/R3 3/3 PASS 行号 + 零外泄 grep 命中行号（RegistryMetaPaths.java 仅 1 处）+ c11 C 组 assertions 全 Pass 片段。

---

## Task 2：NEW DsTransactionManager（单例 + ThreadLocal + 2PC 骨架 + 崩溃恢复）

- **AC Coverage**：AC-R1（commit 可见/rollback 字节一致）、AC-R10（recoverIncompleteTxs 幂等 2 次）、AC-R12（try-with-resources 自动 rollback）、AC-U1（部分——2PC 骨架就绪）
- **Dependencies**：T1 completed（需 txState/txOpLeaf/txBegTs/txWriteTs helper 存在才能写 op 列表持久化）
- **Priority**：High
- **Scope**：
  1. **NEW package-private class `com.q3lives.ds.database.tx.DsTransactionManager`**（包内 1 单例，由 DsDatabaseLocal 构造器唯一初始化，禁止 new 2 份）：
     - 字段：`private final AtomicLong txSeq = new AtomicLong(0L);`（txId 单调递增）、`private final ThreadLocal<TxContext> CURRENT = new ThreadLocal<>();`（事务上下文）、`private final DsDatabaseLocal db;`（回连宿主 DB）。
     - 核心方法（package-private，仅 DsDatabaseLocal 调用，不对外 new）：
       - `TxContext begin(IsolationLevel level)`：校验 level=RR（其他 throw UOE："MVP only RR"）→ txId=txSeq.incrementAndGet → readTs=db.writeTimestamp.get()（快照）→ writeTs=db.writeTimestamp.incrementAndGet（预留 commit 生效版本）→ 写 `/orm/tx/<txId>/begTs` + `/orm/tx/<txId>/writeTs` + state=PREPARING(0x01) → set ThreadLocal；返回 ctx。
       - `void commit(TxContext ctx)`：PREPARING 阶段持久化 op 列表（所有 TxOp 调 atomicModifyBytes CAS 执行）→ 成功写 state=COMMITTING(0x02) → 应用 index/Bloom/VersionRecord 版链更新 → state=DONE(0x04) → remove ThreadLocal。
       - `void rollback(TxContext ctx)`：state=ABORTING(0x03) → 倒序遍历 opId 从高→低，每条 op 记录的 prevBytes 调 atomicModifyBytes 还原 → state=DONE(0x04) → remove ThreadLocal。
       - `TxContext currentOrNull()`：return CURRENT.get()。
       - `int recoverIncompleteTxs()`：启动时 listChildren `/orm/tx` → state != DONE：PREPARING→强制 ABORTING 回滚；COMMITTING→补完剩余 op；ABORTING→继续回滚剩余 op；幂等：跑完 1 次后再跑 1 次无新 side-effect。
  2. **NEW class `TxContext`（package-private，含 10+ 字段）**：txId/readTs/writeTs/isolation/state/opList(List<TxOp>)/opSeq(int)/closedFlag/startNanos。
  3. **NEW class `TxOp`（package-private，pack/unpack）**：type(1B)/registryPath/expectedPrevBytes/newPayloadBytes（序列化对应 spec FR4 7 种 type 字节布局 LE）。
- **Local TRs**：
  - **TR-T2-R1 [rule]（life-cycle）**：begin→commit → state 文件 state==DONE(0x04)；begin→rollback → state==DONE 且所有写入 leaf == 事务开始前 bytes（byte-for-byte）。
  - **TR-T2-R2 [rule]（recover 幂等）**：在 PREPARING 未到 COMMITTING 的半成品上连续跑 2 次 recoverIncompleteTxs()，两次后 `/orm/tx/*/state` 全部 DONE（无 PREPARING/COMMITTING/ABORTING 残留）。
  - **TR-T2-R3 [rule]（其他隔离级抛 UOE）**：begin(READ_COMMITTED)、begin(READ_UNCOMMITTED)、begin(SNAPSHOT) → 全部立即抛 UnsupportedOperationException && message 含 `"MVP only RR"`。
- **Status**：pending
- **Blocked By**：T1 completed && SP4 批准
- **Completion Evidence**：T2-R1/R2/R3 3/3 PASS 行号；/orm/tx/<txId>/op/<opId> 目录实际截图（至少 20 个 op 文件正确生成）；recover 前后 state listChildren diff。

---

## Task 3：DsDatabaseLocal 3 NEW public final 事务入口 + 2PC 三阶段主流程

- **AC Coverage**：AC-R1（commit 可见/rollback 零可见）、AC-R12（TWR 自动回滚）、AC-R11（隔离级 UOE）、AC-R5（c11 B 组 facade 方法数基线 6：新增 3 方法 ≈ 原方法数+3，B 组断言窗口内）
- **Dependencies**：T2 completed（TxManager 存在才能挂 facade 入口）
- **Priority**：High
- **Scope**：
  1. **3 个 NEW public final 方法（挂在 DsDatabaseLocal，** NG-2 零改老签名**，c11 B 组反射断言钉死方法数窗口 [29,37]，不超）：
     - `public final Transaction beginTransaction() throws IOException`：默认 RR → TxManager.begin(RR) → 返回 Transaction。
     - `public final Transaction beginTransaction(IsolationLevel level) throws IOException`：显式隔离级 → 内部 begin(level)。
     - `public final void runInTransaction(Runnable r) throws IOException` + `public final <T> T callInTransaction(Callable<T> c) throws Exception`：语法糖，try-with-resources 内部 begin→c.call→commit；任何 Exception→rollback 再 rethrow。
  2. **NEW interface `Transaction extends AutoCloseable`**：`void commit() throws Exception;`、`void rollback() throws Exception;`、`void close() throws Exception`（未 commit 自动 rollback，保证 TWR 安全）。
  3. **2PC 三阶段主流程骨架（T4 再填充各 op 接入点）**：commit 时先按 TxOp 序号调 atomicModifyBytes CAS 写 registry；任一 CAS 失败→立即 rollback。
- **Local TRs**：
  - **TR-T3-R1 [rule]（TWR 自动回滚）**：`try (Transaction tx = db.beginTransaction()) { putEntity(X id=42, name="foo"); throw new RuntimeException("ABORT"); }` → 后 db.getTable(X, 42) == null（0 字节可见），byte-for-byte 与事务开始前 `bucketStore.get(space,type,rowId)` 字节全 0 或完全等于原 oldRow。
  - **TR-T3-R2 [rule]（commit 可见）**：begin → putEntity(X id=43, name="bar") → commit → db.getTable(X, 43).getName().equals("bar")。
  - **TR-T3-R3 [rule]（facade 方法数窗口）**：c11 B1 DsDatabaseLocal public 方法数 ∈ [29,37]（P13 末基线 + 新增 3 + 原 2~3 波动）；所有新增方法 `Modifier.isFinal(method.getModifiers()) == true`（不可 override，防破坏 2PC 顺序）。
- **Status**：pending
- **Blocked By**：T2 completed
- **Completion Evidence**：T3-R1/R2/R3 3/3 PASS 行号；c11 B1 反射断言片段；beginTransaction() 堆栈截图（TxManager.begin ← DsDatabaseLocal 调用链正确）。

---

## Task 4：2PC 7 Op 接入 5 大模块（行/列/组/索引/Bloom，复用 P11 CAS + P13 eq+Bloom）

- **AC Coverage**：AC-R3（5 叶事务第 5 叶 CAS 失败，前 4 叶 100% 回滚 byte-for-byte=原值）、AC-R7（commit→Bloom true；rollback→Bloom false(fp 除外)）、AC-R2（RR 行存+列存统一 readTs 绑定）
- **Dependencies**：T3 completed（TxManager + DsDatabaseLocal 3 事务入口 ready 才能接各 op 到 begin/commit）
- **Priority**：High
- **Scope（5 大模块，每模块对应 Op type，一一映射，禁止新增第 8 种 type）**：
  1. **行存（ROW_PUT/ROW_DEL）**：Tx.begin 内 putEntity → 记录 TxOp(ROW_PUT, dataRowPayload, oldBytes, newBytes)；commit 时 CAS 写 row payload + 追加 VersionRecord 版链；rollback 还原 oldBytes。
  2. **列存（COL_PUT / GROUP_PUT）**：对应 putValue / putFieldGroup / putCompositeGroup → 记录 TxOp（调用 P11 ColumnarColumnDataBackend / GroupDataBackend atomicModifyBytes 接口）。
  3. **索引（INDEX_ADD / INDEX_REMOVE）**：EqIndexStore.add → INDEX_ADD；EqIndexStore.remove → INDEX_REMOVE；commit 时同步写 eq 索引。
  4. **Bloom（BLOOM_SET / BLOOM_RESET）**：commit 后 Bloom 置位 = BLOOM_SET；rollback 复位 = BLOOM_RESET（复用 P13 EqIndexStore `enableBloomForColumn / mightContain`）。
  5. **所有 7 种 Op 必须记录 expectedPrevBytes**：回滚时 100% 还原，不能只"写空 byte[]"。
- **Local TRs**：
  - **TR-T4-R1 [rule]（5 叶原子回滚）**：构造 5 叶事务（row + 2 cols + 1 group + 1 eq index），在第 5 叶 CAS 注入 prev 不匹配（seed 构造错误 expectedPrev）→ 回滚后读前 4 叶，全部 `Arrays.equals(事务开始前 bytes)`。
  - **TR-T4-R2 [rule]（Bloom 绑定）**：Bloom 场景开启后 → Tx 内 add eq 索引值 V1 → commit → `bloom.mightContain(V1)=true`；Tx 内 add V2 → rollback → `mightContain(V2)=false`（fp 例外，允许 2% 概率 true，单测循环 5 次取 majority 消除 FPP 噪声）。
  - **TR-T4-R3 [rule]（RR 快照绑定）**：在 begin 时 readTs = T；事务持续 100ms 期间外部线程写 new Customer(id=X, balance=B+10)；事务内两次读 Customer(X) → 两次 balance 字节一致（Arrays.equals），不能读到外部线程的新值。
- **Status**：pending
- **Blocked By**：T3 completed
- **Completion Evidence**：T4-R1 5 叶回滚 byte-for-byte 断言截图；T4-R2 Bloom 置位/复位断言值；T4-R3 RR 2 次读 bytes[] hash（前==后，外部写入 hash 不同）。

---

## Task 5：正确性专项 ≥ 24 tests + c11 AcidGatekeeper 25 assertions + TPC-C New-Order 10 tests

- **AC Coverage**：全 12 条 rule（AC-R1~R12）+ c11 Gatekeeper 25 assertions A6+B6+C5+D4+E4
- **Dependencies**：T4 completed（所有 Op 接入点 ready 后 capture facade 基线）
- **Priority**：High
- **Scope（按 test class 分组）**：
  1. **P14_AcidLifeCycleTest（3 tests）**：begin+commit / begin+rollback / TWR 自动回滚。
  2. **P14_AcidRepeatableReadTest（3 tests）**：单线程两次读相等；并发 TX2 写不影响 TX1 快照；事务关闭后 ThreadLocal 清空（currentOrNull()==null）。
  3. **P14_AcidTwoPhaseTest（6 tests）**：2 叶 / 5 叶 / 20 叶 happy 全通过；5 叶第 5 失败回滚；崩溃重启 PREPARING→ABORT（进程外 ProcessBuilder 模拟 kill）；崩溃重启 COMMITTING→complete；recoverIncompleteTxs 幂等 2 次。
  4. **P14_AcidBloomBindingTest（2 tests）**：commit 置位；rollback 复位。
  5. **P14_TpccNewOrderTest（10 tests）**：W=1, D=10, C=3000, Item=10000；10 并发 TX × 100 newOrder → District.next_o_id 单调；Stock 减量 = Σ qty；1 条事务 abort 后 Stock 全不变；1000 newOrder 全部成功；Customer.ytd_payment 增量 = Σ amount；Warehouse.w_ytd + District.d_ytd = Σ amount。
  6. **⭐ c11 AcidGatekeeper（25 assertions 5 防线 A6+B6+C5+D4+E4）**：
     - **A 6 修饰符**：A1 DsTransactionManager private final；A2 TxContext static final；A3 TxOp static final；A4 RowDataBackend 内嵌类 P11 静态；A5 TransactionManager ≥ 5 protected helper；A6 tx helpers（txState 等 5）public static。
     - **B 6 facade 方法数**：B1 DsDatabaseLocal ∈ [29,37]；B2 ColumnarStore ∈ [22,30]（P13 已加 3 批量，0 再改）；B3 TableMetaStore ∈ [18,26]；B4 ColumnRegistry ∈ [12,20]；B5 RowIdSequenceStore ∈ [8,14]；B6 EqIndexMetaStore ∈ [10,18]。
     - **C 5 RegistryMetaPaths 命名**：C1 TX_ROOT=="/orm/tx"；C2 String 常量数 ≥ 16；C3 txIdHex 16 位 0 补 `0000000000000001`；C4 txOpLeaf 含 `/op/` 段；C5 grep `"/orm/tx"` 仅 1 处。
     - **D 4 2PC 签名**：D1 TxManager.begin(IsolationLevel) 存在；D2 TxManager.commit 存在；D3 TxManager.rollback 存在；D4 DsDatabaseLocal.beginTransaction 存在且 final。
     - **E 4 TxManager + 构造器**：E1 构造器 (DsDatabaseLocal) package-private；E2 4 个核心方法 begin/commit/rollback/currentOrNull 数 exact（不多不少）；E3 begin(非 RR) → throw UOE；E4 DsTransactionManager 单例实例 == DsDatabaseLocal.getTxManager（1 份）。
- **Local TRs**：
  - **TR-T5-R1 [rule]（专项 ≥ 24 tests 0F0E）**：`mvn -pl p2p-db -Dtest=P14_AcidLifeCycleTest,P14_AcidRepeatableReadTest,P14_AcidTwoPhaseTest,P14_AcidBloomBindingTest,P14_TpccNewOrderTest test` → Tests run ≥ 3+3+6+2+10=24，Failures=0 Errors=0。
  - **TR-T5-R2 [rule]（c11 Gatekeeper 25 assertions）**：`mvn -pl p2p-db -Dtest=P14_AcidGatekeeperTest test` → 内部 assertions 25/25 PASS（打印防线编号便于定位）。
  - **TR-T5-R3 [rule]（TPC-C District.next_o_id 单调）**：10 并发事务结束后 `District(d=1).next_o_id == 初始 + 成功事务数`（无重复/回跳，原子性硬证明）。
- **Status**：pending
- **Blocked By**：T4 completed
- **Completion Evidence**：T5-R1 Tests run N≥24 BUILD SUCCESS 行号；c11 25/25 每条防线编号 PASS 日志；TPC-C next_o_id 最终数值截图 + Stock 减量逐行断言。

---

## Task 6：全量 JV 合跑 ≥ 78 tests 0F0E（历史基线零破坏）

- **AC Coverage**：AC-R9（P10 38 + P11 28 + P12 c10 5 + P13 7 = 78 tests 0F0E）
- **Dependencies**：T5 completed（正确性 24+ 专项通过后再合跑，避免浪费）
- **Priority**：High
- **Scope**：
  1. **合跑命令（p2p-db 模块，默认 HYBRID 模式）**：
     ```
     mvn -pl p2p-db -Dtest='P10_RowMetaTest,P10_ColumnMetaTest,P10_MetaBackendPerfTest,P11_DataKeySpaceTest,P11_DataBackendBasicTest,P11_RowDataTest,P11_ColumnarDataTest,P11_DataGatekeeperTest,P12_SyncGatekeeperTest,P13_BloomFilterTest,P13_BulkImportPerfTest,ColumnarStoreTest,ColumnarStoreCompressionTest,ColumnarStoreLayoutTest,Orm_P7_Tgt2_Handler_6_Test,DsRegistry_P7_Tgt3_HandlerTest,DsRegistryExtAttrs_P7_Tgt5_HandlerTest,DsRegistryExtAttrs_P7_Exts_Test' test
     ```
  2. **分项统计（保守 ≥ 78）**：P10 15 + P11 14 + P12 c10 5 + P13 7 + 列回归 18 + P7 核心 ≥ 24 → 保守 83 ≥ 78 门槛。
  3. 日志落盘 `p14-joint-verify-2026XXXX.log`。
- **Local TRs**：
  - **TR-T6-R1 [rule]**：BUILD SUCCESS；Tests run N ≥ 78；Failures=0；Errors=0。
  - **TR-T6-R2 [rule]（分项 0F0E）**：P10 15、P11 14、P12 c10 5、P13 7、列回归 18、P7 核心 ≥24 六类分项各自全部 PASS（无单项 FAIL）。
- **Status**：pending
- **Blocked By**：T5 completed
- **Completion Evidence**：合跑 BUILD SUCCESS 行号；各分项 Tests run/Pass 计数；日志文件 size > 10KB。

---

## Task 7：性能专项 2 tests（U1/U2 rubric 打分 ≥ 1 门槛）

- **AC Coverage**：AC-U1（5 叶事务 P99 ≤ 2.0×，≥1 分 threshold）、AC-U2（TPC-C New-Order 并发 ≥ 0.80× 串行，≥1 分 threshold）
- **Dependencies**：T6 completed（正确性 ≥ 78 通过才测性能；性能回归允许 U1/U2 得分 =1 不阻塞）
- **Priority**：Medium
- **Scope**：
  1. **P14_AcidPerfTest#U1_fiveLeafTxP99（rubric 0-2）**：同 JVM `SingleEntity`（单行 putEntity 非事务 baseline）vs `Tx 5 叶`（row+2col+group+index 5 op 事务）；warmup=500，N=2000，**5 轮取 P99 均值**；ratio = TxP99 / BaselineP99；打分 2=≤1.50；1=≤2.00；0=>2.00；Pass Threshold ≥ 1。
  2. **P14_TpccPerfTest#U2_newOrderConcurrent（rubric 0-2）**：W=1，1000 条 newOrder；场景 A=非事务串行 for-loop for 1000 次单写；场景 B=10 并发线程 × 每线程 100 事务（1000 总 tx）；throughput 单位 tx/sec；ratio = B / A；打分 2=≥1.00；1=≥0.80；0=<0.80；Pass Threshold ≥ 1。
- **Local TRs**：
  - **TR-T7-U1 [rubric]**：U1 分数 ≥ 1（ratio ≤ 2.00），Evidence=5 轮 P99 打印 + ratio 均值行。
  - **TR-T7-U2 [rubric]**：U2 分数 ≥ 1（ratio ≥ 0.80），Evidence=1000 串行 for throughput + 10 并发 100 throughput + ratio。
- **Status**：pending
- **Blocked By**：T6 completed
- **Completion Evidence**：U1/U2 得分 + ratio 数值；软断言 ratio 不超阈值 5% 截图；mvn `-P perf` Profile 启动命令行。

---

## Task 8：NeatFreak 三层同步（AGENTS/roadmap/project_memory 洁癖对齐）

- **AC Coverage**：（无 AC 直接对应，属于交付闭环要求，必须完成才能 P14 VERIFIED CLOSED）
- **Dependencies**：T7 completed（U1/U2 得分 ≥1 后写）
- **Priority**：Low
- **Scope**：
  1. **AGENTS.md 末尾追加 P14 ACID Invariant 段（6 条子钉）**：1.NG-2 零改老签名 + 3 NEW 事务入口 final；2. 2PC 7 Op 绑定行/列/组/索引/Bloom；3. RR 快照 beginTs=readTs 绑定；4. /orm/tx 零外泄仅 RegistryMetaPaths 1 处；5. hasMvcc 列存降级在行存层；6. TPC-C 10T×100tx 专项证据（38 tests 0F0E + U1/U2 得分）。
  2. **roadmap.md §六 P3 行 OQ-3 修改**：把 OQ-3 从「候选」→ ✅ **P3/CLOSED 2026-10-21**（含链接：ACID row-level RR + 2PC 原子 + TPC-C New-Order 兼容，38 tests / 25 assertions / 0F0E，U1/U2≥1）。
  3. **project_memory 追加 3 条血训**：① 2PC 崩溃恢复必须有 `/orm/tx/<txId>/state` 永久意图日志，不能只靠内存；② RR 读快照 = beginTs，writeTs 提前在 begin 就分配，commit 时再推进 VersionRecord；③ TPC-C Stock.quantity 冲突必须 256 CAS 上限，超 256 自动 abort 事务（不 try 死循环）。
- **Local TRs**：
  - **TR-T8-R1 [rule]**：AGENTS.md P14 Invariant 段存在，6 子钉清晰。
  - **TR-T8-R2 [rule]**：roadmap P3 OQ-3 前加 ✅ CLOSED，含日期+分数。
  - **TR-T8-R3 [rule]**：project_memory 3 条血训追加。
- **Status**：pending
- **Blocked By**：T7 completed
- **Completion Evidence**：三处文件新增段行号；grep AGENTS.md `P14 Invariant` 命中；roadmap.md `P3/CLOSED` 命中；project_memory `/orm/tx` 永久意图日志 命中。
