# P57 OQ6 限定 MVP：单组 3 节点 Raft 共识 — 独立审查结论（Gate C）

审查日期：**2026-11-05**（绝对日期）
审查范围：
- 规格：[spec.md](file:///i:/2026/code/p2p-net-storage/.trae/specs/p57-oq6-raft-3node-mvp/spec.md)（6 章：12 rule AC + 2 rubric AC；FR1~FR4 / NFR-C1~C3 / NFR-P1~P2 / NFR-M1~M4）
- 任务：[tasks.md](file:///i:/2026/code/p2p-net-storage/.trae/specs/p57-oq6-raft-3node-mvp/tasks.md)（T1~T9，全部 completed）
- 唯一生产改动源：[RaftServer.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java)（git 未跟踪，无 HEAD 基线）
- 测试：[p2p-db/src/test/java/ds/P57_*.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/)（8 类）；Surefire：[p2p-db/target/surefire-reports](file:///i:/2026/code/p2p-net-storage/p2p-db/target/surefire-reports/)
- 三层文档：`AGENTS.md` / `project_memory.md` / `docs/dsdb-design-status-and-roadmap.md`

审查方式：**独立静态核实 + 实跑复现**（非仅引用 tasks.md 自述）。所有下文数字均为本人用 Glob/Grep/Read 亲自核对或实跑 `mvn -Dtest=... test` 所得。

---

## 1. 结论

| 阶段 | 结论 | 阻塞结论 |
|------|------|----------|
| Specify 阶段（spec.md 6 章：12 rule AC + 2 rubric AC） | **Pass** | 无阻塞 |
| Plan 阶段（tasks.md T1~T9 严格串行依赖图 + AC Coverage 齐全） | **Pass** | 无阻塞 |
| Approve（SP1 用户显式批准 spec.md + tasks.md） | **Pass** | 无阻塞（tasks.md T1 Blocked By 记录「User has approved the given files」） |
| Implement（T1~T8；T3~T8 为 0 生产改动 + 纯新增测试） | **Pass（有条件）** | 无红线阻塞；见 §3 时序 flake 风险 |
| NeatFreak 三层同步（AGENTS.md / project_memory.md / roadmap） | **Pass** | 无阻塞，三文档数值交叉一致 |

**总评：PASS（有条件）。**

- 规格红线（§5.1 硬约束）**逐条不破**：唯一生产改动源 = `RaftServer.java`；ZeroLeak3 守恒（严格 3 顶级类）；0 新依赖 / 0 新线程设施；2 参 + 5 参 `create` 签名不变；`/orm/raft` 字面量零外泄仅 1 处。
- 12 条 rule AC（R1~R12）+ 2 条 rubric AC（U1/U2）均有源码 + 测试锚点覆盖，且经我独立复核成立（见 §2 / §4）。
- **唯一需要披露的非阻塞问题**：P57 八类**合并批次实跑存在时序 flake**——本审查第一次合跑得 `16 run / 2 Failures`（T5.t02、T8.t02 均为「3 节点收敛出 LEADER」超时），第二次合跑得 `16 run / 0F0E BUILD SUCCESS`。即「16 tests 0F0E」**可复现但非确定性**（抖动源为 in-JVM 静态 `PEERS` 模型下的选举收敛调度）。此与项目既有的 PEERS 静态污染/时序 flake 教训同源，**不构成 spec 红线违反，不阻塞交付**，但应在文档中显式披露（详见 §3 #1、#3）。

---

## 2. Evidence 链（T1~T9 逐条 file 锚点 + 实测数字 + 独立复核结论）

> 图例：✅已复核 PASS（与 tasks.md 一致）；⚠️与 tasks.md 不一致/需披露；➕本人新增实跑证据。

### T1 props 驱动 roster（parseMembers / parseLocalPeer / 3 参 create / 2 参委托）
- 源码锚点（✅已复核）：
  - `parseMembers` [RaftServer.java:196-212](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L196-L212)：读 `p2p.orm.raft.members`，`raw==null || trim().isEmpty()` → `new long[]{2L}`；`split(",", -1)` 后逐 token `parsePeerId` + O(n²) 重复检测 → IAE。
  - `parseLocalPeer` [:214-220](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L214-L220)：读 `p2p.orm.raft.local`，空/缺省 → `def`。
  - `parsePeerId` [:222-234](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L222-L234)：空 token → IAE；`0x/0X` 前缀 → `Long.parseLong(..,16)`；否则十进制；NFE → IAE（message 含键名与非法 token）。
  - 3 参 `create(File,MetaMode,Map)` [:192-194](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L192-L194)：`return create(dbRoot, mode, props, parseLocalPeer(props,1L), parseMembers(props));`（复用 5 参全分支）。
  - 2 参 `create` [:236-238](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L236-L238)：`return create(dbRoot, mode, null, 1L, new long[] { 2L });`（值语义等价）。
  - 5 参 `create` [:178-190](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L178-L190)：`YAML → null` / `raft.mode=off → null` / `peerIds 空 → new long[0]` 全分支保留，签名与逻辑未改。
- 测试锚点：[P57_T1_RosterFactoryTest](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T1_RosterFactoryTest.java)（t01 3 节点 roster / t02 三方向后兼容 / t03 四类 IAE）→ surefire [ds.P57_T1_RosterFactoryTest.txt](file:///i:/2026/code/p2p-net-storage/p2p-db/target/surefire-reports/ds.P57_T1_RosterFactoryTest.txt) `Tests run: 3, Failures: 0, Errors: 0` ✅。
- ➕实跑复现：合跑中 T1 = 3 run 0F0E（两次合跑均一致，无抖动）。
- 对齐 spec：R1 / R2 / R3、FR1、NFR-M2。

### T2 quorum 单一出处（quorumOf 抽取）
- 源码锚点（✅已复核）：`quorumOf(int)` [RaftServer.java:240-242](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L240-L242) = `clusterSize <= 1 ? 1 : (clusterSize / 2) + 1`。
- ➕独立 grep 实锤（RaftServer.java 内）：`(clusterSize / 2) + 1` 命中 **1**（仅定义行）；`total == 1 ? 1` 命中 **0**；`(total / 2) + 1` 命中 **0** → 两处内联公式确已移除，单一出处成立 ✅。
- 调用点（✅已复核）：选举 [RaftServer.java:398](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L398) `int q = quorumOf(1 + peers.size());`；提交 [:707-708](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L707-L708) `int total = 1 + peers.size(); int q = quorumOf(total);`，`candidates[total - q]`（L717）取值逻辑未动 → 行为逐值等价（1→1/2→2/3→2/4→3/5→3）。
- 测试锚点：[P57_T2_QuorumTest](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T2_QuorumTest.java)（t01 反射 1..5→{1,2,2,3,3}；t02 源码扫描单一出处）→ [ds.P57_T2_QuorumTest.txt](file:///i:/2026/code/p2p-net-storage/p2p-db/target/surefire-reports/ds.P57_T2_QuorumTest.txt) `Tests run: 2, Failures: 0, Errors: 0` ✅。
- 对齐 spec：R4、FR2、NFR-M1。

### T3 3 节点选举收敛（单 Leader / 多数选举）
- 测试锚点：[P57_T3_ThreeNodeElectionTest](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T3_ThreeNodeElectionTest.java)（t01_single_leader：4s 内恰 1 LEADER + term 单调 + 2 Follower `currentLeaderId` 一致；t02_two_of_three_online_still_elects：仅 2 peer 进 PEERS 仍选出 Leader）→ [ds.P57_T3_ThreeNodeElectionTest.txt](file:///i:/2026/code/p2p-net-storage/p2p-db/target/surefire-reports/ds.P57_T3_ThreeNodeElectionTest.txt) `Tests run: 2, Failures: 0, Errors: 0` ✅。
- ⚠️（LOW）**peerId 取值与文档不一致**：spec §3.3 FR3 与 tasks T3 叙述使用「11/12/13」，实现与测试统一使用「1/2/3」。语义完全等价（peerId 为任意唯一 long，构造器 [:157-167](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L157-L167) 仅按 `p<=0 || p==localPeerId` 过滤），**不影响 AC 判定**，仅文档叙述与代码不严格同字（见 §3 #4）。
- 对齐 spec：R5 / R6、FR3.1 / FR3.2。

### T4 多数提交 + 容错边界（R7/R8/R9 + U1）
- 测试锚点：[P57_T4_CommitMajorityTest](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T4_CommitMajorityTest.java)：
  - t01_commit_2of3：写 100 条 → `commitIndex >= 100` 且 ≥1 Follower `matchIndex >= 100`；
  - t02_one_down_still_commits：`stop()+deregister+PEERS.remove` 1 Follower → 续写 100 条 100/100 `awaitApplied` 成功、`commitIndex > before`；
  - t03_two_down_no_commit：摘除 2 Follower（仅 self）→ `awaitApplied(idx,1500ms)==false` 且 `commitIndex == commitBefore`。
- → [ds.P57_T4_CommitMajorityTest.txt](file:///i:/2026/code/p2p-net-storage/p2p-db/target/surefire-reports/ds.P57_T4_CommitMajorityTest.txt) `Tests run: 3, Failures: 0, Errors: 0` ✅。
- **U1 容错 rubric（✅已复核 = 2/2 满分）**：t02 证明「1 节点宕机 100% 提交」，t03 证明「2 节点宕机 100% 拒绝」，双 counter-term 实锤 quorum=2 → 满足 spec U1 的 2 分锚点。
- 对齐 spec：R7 / R8 / R9、FR3.3 / FR3.4、U1。

### T5 Snapshot fanout + 无 split-brain（R10/R11）
- 测试锚点：[P57_T5_SnapshotFanoutTest](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T5_SnapshotFanoutTest.java)：
  - t01_two_follower_fanout：2 Follower applied store 与 Leader SHA-1 全等 + `forceCompact()` 后 `snapshotLastIncludedIndex >= 1` + 落后 Follower 经 InstallSnapshot 补齐（nextIndex=snap+1 / matchIndex=snap）；
  - t02_minority_no_leader：隔离 1 Follower → 其 `!= LEADER`；复归后 `|term 差| <= 2`。
- ➕**实跑复现（重要）**：本次合跑 **第一次运行 t02 FAIL**（`AssertionError: 3 节点收敛出 LEADER` @ [P57_T5_SnapshotFanoutTest.java:155](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T5_SnapshotFanoutTest.java#L155)，即 `awaitLeader(..,4000ms)` 超时）；**第二次运行 T5 = 2 run 0F0E**。判定为时序 flake（非逻辑缺陷），见 §3 #1。
- 最终 on-disk：[ds.P57_T5_SnapshotFanoutTest.txt](file:///i:/2026/code/p2p-net-storage/p2p-db/target/surefire-reports/ds.P57_T5_SnapshotFanoutTest.txt) `Tests run: 2, Failures: 0, Errors: 0`（第二次合跑结果）。
- 对齐 spec：R10 / R11、FR3.5。

### T6 正确性专场合跑（P57 own ≥11 TR + P15 54 tests）
- **P57 八类合跑（➕实跑复现）**：T1 3 + T2 2 + T3 2 + T4 3 + T5 2 + T6 1 + T7 1 + T8 2 = **16 run**。
  - 第一次合跑：`16 run / 2 Failures`（T5.t02、T8.t02，皆选举收敛超时）→ 与 tasks.md「16 tests 0F0E」⚠️不一致；
  - 第二次合跑：`16 run / 0 Failures / 0 Errors / BUILD SUCCESS` ✅（复现 tasks.md 结论）。
  - 结论：NFR-C2「全 PASS 0F0E」**可复现但非确定性**（flaky）。
- **P15 九类合跑（✅已复核）**：FacadeHookReal 3 + InstallSnapshotChunk 2 + CompactCrash 2 + KeySpace 3 + FacadeFence 3 + Election 4 + PerfRubric 2 + Gatekeeper 25 + StateMachine 10 = **54 run**，与 spec NFR-C1 数字一致 ✅。
  - ⚠️**on-disk Surefire 证据并非全绿**：`ds.P15_RaftPerfRubricTest.txt` 曾显示 `Tests run: 2, Failures: 1`（`u1_linearizable_p99_latency_1_20x_p12_mvp` 在 P15_RaftPerfRubricTest.java:51 处 `AssertionError: leader elected`），`ds.P15_RaftGatekeeperTest.txt` 显示 `Tests run: 25, Failures: 0, Errors: 1`（c5 基线，见下）。即 tasks.md TR-T6-R2 所述「Failures=0」在**合并批次快照下不成立**。
  - ➕**实跑复现（重要）**：我对 `P15_RaftPerfRubricTest` 单独隔离跑（`-DforkCount=1 -DreuseForks=false`）→ `Tests run: 2, Failures: 0, Errors: 0` ✅。证实 PerfRubric 的 Failures:1 是**合并批次时序 flake**（选举收敛超时），非 P57 引入的回归。
- **P15 c5 IllegalAccess 既有基线（✅已复核成立）**：
  - 失败点 [P15_RaftGatekeeperTest.java:243-252](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P15_RaftGatekeeperTest.java#L243-L252)：`c5_path_constants_no_backslash_or_uppercase_prefix_orm` 遍历 `RegistryMetaPaths.class.getDeclaredFields()` 并对 static String 字段直接 `f.get(null)`（未 `setAccessible(true)`）。
  - 根因 [RegistryMetaPaths.java:486](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/RegistryMetaPaths.java#L486)：`static final String SCH_VER_LEAF = "version";` 为 **package-private**；测试类位于 `ds` 包、`RegistryMetaPaths` 位于 `com.q3lives.ds.registry.meta`，跨包反射访问 → `IllegalAccessException`（surefire 栈顶确为 `Field.get` → `newIllegalAccessException`）。
  - **是否 P57 改动面？** 否。P57 的红线改动源仅 `RaftServer.java`；c5 只反射 `RegistryMetaPaths`，与 Raft roster/quorum 无关。`git diff --stat` 亦显示 tracked 文件中 `RegistryMetaPaths.java` 0 改动。该字段属 P18 OQ1 在线 DDL 基线字段。→ **tasks.md 结论成立** ✅。
- `raft.mode=off` 短路：[P57_T6_ModeOffShortCircuitTest](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T6_ModeOffShortCircuitTest.java) → [ds.P57_T6_ModeOffShortCircuitTest.txt](file:///i:/2026/code/p2p-net-storage/p2p-db/target/surefire-reports/ds.P57_T6_ModeOffShortCircuitTest.txt) `Tests run: 1, Failures: 0, Errors: 0` ✅。
- 对齐 spec：NFR-C1 / NFR-C2、R12（短路子断言）。

### T7 Raft-3Node Gatekeeper ≥25 assertions（R12 / NFR-C3 / NFR-M3）
- 测试锚点：[P57_T7_GatekeeperTest](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T7_GatekeeperTest.java)：`t01_25_assertions` 内 `check(...)` 计数（A 6 + B 5 + C 5 + D 5 + E 4 = 25），末尾 `assertEquals("A6+B5+C5+D5+E4=25/25 OK", 25, asserts)`。
- ➕实跑复现：stdout `A6+B5+C5+D5+E4=25/25 OK`（合跑两次均打印一致）；XML [TEST-ds.P57_T7_GatekeeperTest.xml](file:///i:/2026/code/p2p-net-storage/p2p-db/target/surefire-reports/TEST-ds.P57_T7_GatekeeperTest.xml) 同源 → ✅ 25/25。
- **D 防线零外泄（✅独立复核）**：D1 用 Java NIO `Files.walk(src/main/java)` 统计字面量 `"/orm/raft"`。我独立 Grep（`p2p-db/src/main/java` 全量）得**恰好 1 行命中**：[RegistryMetaPaths.java:960](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/RegistryMetaPaths.java#L960) `public static final String RAFT_ROOT = "/orm/raft";`，其余文件 0 命中 ✅。
- **D5 ZeroLeak3（✅独立复核）**：Glob `com/q3lives/ds/registry/meta/raft/*.java` 得**严格 3 个**：`RaftServer` / `MemoryRaftStateMachine` / `RaftStateMachineListener` ✅。
- 对齐 spec：R12、NFR-C3、NFR-M3。

### T8 NFR 性能 rubric（U2 + NFR-P1）
- 测试锚点：[P57_T8_NFRTest](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T8_NFRTest.java)（t01 P99 ratio；t02 NFR-P1 选举收敛 5 轮均值）。
- ➕**实跑复现 stdout**：
  - 第二次合跑（on-disk）：`[U2] 2-node P99 mean=16.6460ms` / `[U2] 3-node P99 mean=16.6120ms` / `[U2] ratio=0.998 2pt<=1.05 1pt<=1.2 score=2/2`；`[NFR-P1] 3-node election convergence rounds=[174, 208, 278, 187, 196] mean=208ms HARD<=3000ms`。
  - tasks.md 记录值：`ratio=0.993 score=2/2`；`mean=254ms rounds=[122,135,333,526,156]`。数值随运行波动（均 ≤ 阈值），**结论一致** ✅。
- **U2 rubric（✅已复核 = 2/2 满分）**：ratio≈0.998~0.999 ≤ 1.05 → 2 分。
- **NFR-P1（✅已复核）**：5 轮均值 208~254ms ≤ 3000ms HARD 通过。
- ⚠️**实跑抖动**：本次第一次合跑时 `T8.t02` FAIL，`round 0 ... cost=7999ms`（逼近该测试逐轮 8000ms 上限，见 [P57_T8_NFRTest.java:198](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T8_NFRTest.java#L198)）——说明负载下单轮选举可严重抖动（均值仍达标）。见 §3 #1/#3。
- 对齐 spec：U2、NFR-P1 / NFR-P2。

### T9 NeatFreak 三层同步（✅已复核，三文档数值交叉一致）
- [AGENTS.md:487-492](file:///i:/2026/code/p2p-net-storage/AGENTS.md#L487-L492)：P57 Invariant 5 条 ——「P57 own 16 tests / 23 TR 0F0E，P15 独立 54 tests 0F0E，c16 Gatekeeper 25/25，U1 容错 score=2 / U2 ratio≤1.05 score=2」。
- [project_memory.md:6](file:///c:/Users/karl/.trae/memory/projects/-i-2026-code-p2p-net-storage--p2-47bed0e10976bce5d956/project_memory.md#L6)：P57 Context —— 同四数值；L15 Hard Constraints 追加 OQ6 边界；L30-33 Lessons Learned 三条。
- [docs/dsdb-design-status-and-roadmap.md:452](file:///i:/2026/code/p2p-net-storage/docs/dsdb-design-status-and-roadmap.md#L452)：§六 P3 行 OQ6 兑现 + 同四数值；[:489](file:///i:/2026/code/p2p-net-storage/docs/dsdb-design-status-and-roadmap.md#L489)：§七 代码索引新增 `RaftServer.java` 锚点行（3 参 create / quorumOf / parseMembers / parseLocalPeer）✅。
- 相对日期 grep：`docs/...roadmap.md` 命中 9 处，**全部为既有 OceanBase 双日 delta（today+yesterday fallback）语义基线**（L144/145/157-164），P57 新增行 0 相对日期 ✅；AGENTS.md / project_memory.md 0 命中。
- Git 改动面（➕复核）：`git diff --stat` → `AGENTS.md | 7 +`、`docs/dsdb-design-status-and-roadmap.md | 3 +-`（= +2/-1），与 tasks.md T9 自述（+7 / +2/-1）一致 ✅。
- 对齐 spec：T9 TR-T9-R1/R2/R3、Workflow Fidelity。

### 唯一生产改动源核验（红线 §5.1-1）
- ➕`git status --short`：`?? p2p-db/src/main/java/com/q3lives/ds/registry/`（整个 registry 目录未跟踪），`?? p2p-db/src/main/java/javax/net/p2p/server/handler/`（handler 目录未跟踪）→ 与「RaftServer.java 无 HEAD 基线」一致 ✅。
- ➕`git diff --stat`：全部 tracked 改动均为 `.brain/`、`AGENTS.md`、`docs/...roadmap.md` 及若干无关既有 test/ptom 文件；**无任何 `p2p-db/src/main` 生产源进入 tracked diff**。
- 局限声明：因 raft 包 / handler 包整体未跟踪（无 HEAD 基线），**无法用 `git diff` 逐字节证明** `MemoryRaftStateMachine.java` / `RaftStateMachineListener.java` / 3 个 `Raft*Handler` 相对上一阶段 0 字符改；仅能确认 (a) 未出现在 tracked diff，(b) 本轮红线所述新增符号（parseMembers/parseLocalPeer/quorumOf/3 参 create）**均只存在于 RaftServer.java**，(c) raft 包顶级类数 == 3 未变。此为「无基线」客观限制，非缺陷。

---

## 3. 已知遗留风险（均为真实存在；标注严重度 + 是否阻塞交付）

| # | 风险项 | 严重度 | 是否阻塞 | 建议动作 |
|---|--------|--------|----------|----------|
| 1 | **P57 八类合跑时序 flake（实跑实测）**：本审查第一次合跑 `16 run / 2 Failures`（`P57_T5_SnapshotFanoutTest.t02` L155「3 节点收敛出 LEADER」超时 + `P57_T8_NFRTest.t02` L198 `round 0 cost=7999ms` 超逐轮上限），第二次合跑 `16 run / 0F0E`。抖动源为 in-JVM 静态 `RaftServer.PEERS` 模型下 3 节点选举收敛在负载下的调度抖动（与 P15 review §3 #2 / project_memory L33 同源）。因此「16 tests 0F0E」非确定性可复现。 | MEDIUM | **否**（逻辑正确性未被推翻：单独/第二次合跑均 0F0E；两次失败均为收敛超时断言，非语义错误） | ①短期：P57 各测试类加 `@Fork(value=1, jvmArgs="-Xmx2g")` 或沿用 P15 拆批隔离范式；②放宽对调度抖动敏感的逐轮硬上限（T8.t02 的 `PER_ROUND_CEIL_MS=8000` 已比 spec 的「5 轮均值」严格，可仅保留均值断言）；③文档显式披露「合跑非确定性」 |
| 2 | **P15 c5 IllegalAccess 既有基线（非 P57）**：`P15_RaftGatekeeperTest.c5_...` 反射 `RegistryMetaPaths.SCH_VER_LEAF`（[:486](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/RegistryMetaPaths.java#L486) package-private）跨包 `f.get(null)` 未 `setAccessible` → IllegalAccessException。 | LOW | **否**（属既有基线；`RegistryMetaPaths.java` 0 改动） | 后续单独任务：c5 内对字段 `setAccessible(true)`，或将该字段改 public（需评估 ZeroLeak 白名单） |
| 3 | **P15 合跑时序 flake（实跑实测）**：`ds.P15_RaftPerfRubricTest.txt` on-disk 曾显示 `Failures: 1`（`u1_...` L51 `leader elected` 超时）；本审查单独隔离跑 → `2 run 0F0E`。证明 P15 合跑同样偶发收敛超时。 | LOW-MEDIUM | **否**（逐类隔离 0F0E；与 P57 改动无关） | 采 P15 review 已建议的 `@Fork` 隔离 / 拆批 |
| 4 | **peerId 取值文档与实现不同字**：spec §3.3 FR3 / tasks T3 叙述「11/12/13」，实现与测试用「1/2/3」。语义等价。 | LOW（文档一致性） | **否** | 统一文档叙述为「1/2/3」或注明「示例 peerId，任意唯一 long 等价」 |
| 5 | **NFR-P1 单轮抖动可逼近逐轮上限**：第一次合跑 round 0 cost=7999ms（上限 8000ms），均值仍 208~254ms 达标。负载稍增即可能触碰逐轮上限而误报。 | LOW-MEDIUM | **否**（spec 判据为 5 轮均值） | 降低逐轮上限的耦合或移除逐轮硬断言，仅对均值断言（与 project_memory L33 教训一致） |

---

## 4. spec 规则对齐表（R1~R12 + U1/U2 + NFR-C/P/M）

| 编号 | Type | 判据（观测条件） | 源码 / 测试锚点 | 独立复核 |
|------|------|------------------|-----------------|----------|
| R1 | rule | `create(root,REGISTRY,{members="1,2,3",local="2"})` → local=2、peerState(1)/(3)!=null、peerState(2)==null | [RaftServer.java:196-220](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L196-L220)；[P57_T1 t01](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T1_RosterFactoryTest.java#L61-L72) | ✅ PASS |
| R2 | rule | 2 参 / null / `{}` 三方产物 local=1、peers={2} 全等 | [RaftServer.java:236-238](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L236-L238)；[P57_T1 t02](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T1_RosterFactoryTest.java#L74-L84) | ✅ PASS |
| R3 | rule | `"1,x,3"` / `"1,,3"` / `"2,2"` / `local="zz"` → IAE（message 含键名） | [RaftServer.java:222-234](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L222-L234)；[P57_T1 t03](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T1_RosterFactoryTest.java#L86-L107) | ✅ PASS |
| R4 | rule | `quorumOf(1..5)` = {1,2,2,3,3} | [RaftServer.java:240-242](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L240-L242)；[P57_T2 t01](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T2_QuorumTest.java#L17-L26) | ✅ PASS |
| R5 | rule | 11/12/13（实测用 1/2/3）全注册 → 4s 内恰 1 LEADER、term 单调、余 FOLLOWER `currentLeaderId==leader`、无 2 Leader | [P57_T3 t01](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T3_ThreeNodeElectionTest.java#L55-L103) | ✅ PASS（peerId 取值见 §3 #4） |
| R6 | rule | 仅 2 节点在线 → 仍选出 LEADER（2/3≥quorum 2） | [P57_T3 t02](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T3_ThreeNodeElectionTest.java#L105-L135) | ✅ PASS |
| R7 | rule | 写 100 条 → `commitIndex>=100` 且 ≥1 Follower `matchIndex>=100` | [P57_T4 t01](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T4_CommitMajorityTest.java#L67-L98) | ✅ PASS |
| R8 | rule | 1 Follower 宕机 → 续写 100 条全 `awaitApplied` 成功、commitIndex 前进 | [P57_T4 t02](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T4_CommitMajorityTest.java#L100-L129) | ✅ PASS |
| R9 | rule | 仅 self 在线 → `awaitApplied(idx,1500ms)==false`、commitIndex 不前进 | [P57_T4 t03](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T4_CommitMajorityTest.java#L131-L165) | ✅ PASS |
| R10 | rule | `forceCompact()` 后 2 落后 Follower 补齐，两 applied store 与 Leader SHA-1 全等 | [P57_T5 t01](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T5_SnapshotFanoutTest.java#L73-L144) | ✅ PASS（合跑 flake，§3 #1） |
| R11 | rule | 隔离 1 Follower → 不能当选；复归重新跟随、`|term 差|<=2` | [P57_T5 t02](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T5_SnapshotFanoutTest.java#L146-L188) | ✅ PASS（合跑 flake，§3 #1） |
| R12 | rule | P15 54 tests 0F0E；`raft.mode=off`→null；Gatekeeper ≥25/25 | [P57_T6](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T6_ModeOffShortCircuitTest.java) + [P57_T7](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T7_GatekeeperTest.java) + P15 九类 | ✅ PASS（P15 合跑 flake / c5 基线见 §3 #2/#3） |
| U1 | rubric (0-2) | 1 宕机 100% 提交 且 2 宕机 100% 拒绝 | [P57_T4](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T4_CommitMajorityTest.java) t02/t03 双场景 | ✅ score=2/2 |
| U2 | rubric (0-2) | ratio = 3 节点 P99 mean / 2 节点 P99 mean ≤1.05 | [P57_T8 t01](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T8_NFRTest.java#L140-L159) 实测 ratio≈0.993~0.999 | ✅ score=2/2 |
| NFR-C1 | rule | P15 既有 54 tests 0F0E | P15 九类（3+2+2+3+3+4+2+25+10=54） | ✅ 数字一致（合跑 flake 见 §3 #3） |
| NFR-C2 | rule | P57 专项 ≥11 TR 全 PASS 0F0E | P57 八类 = 16 run | ✅ 16≥11（但合跑 flaky，§3 #1） |
| NFR-C3 | rule | Gatekeeper ≥25/25 PASS | [P57_T7](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T7_GatekeeperTest.java) | ✅ 25/25 |
| NFR-P1 | rule | 3 节点选举收敛 5 轮均值 ≤3000ms | [P57_T8 t02](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P57_T8_NFRTest.java#L161-L210) 实测 mean=208~254ms | ✅ PASS（单轮抖动 §3 #5） |
| NFR-P2 | rule | 写提交 P99 ratio ≤1.05 | 同 U2 | ✅ PASS |
| NFR-M1 | rule | roster/quorum 单一出处 | [RaftServer.java:196-242](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L196-L242)；grep `(clusterSize / 2) + 1`=1、`total == 1`=0 | ✅ PASS |
| NFR-M2 | rule | 非法 props 100% IAE 快速失败 | [RaftServer.java:222-234](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L222-L234) | ✅ PASS |
| NFR-M3 | rule | `/orm/raft` 仅 RegistryMetaPaths 1 处 | [RegistryMetaPaths.java:960](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/RegistryMetaPaths.java#L960)（Grep 全 main 命中=1） | ✅ PASS |
| NFR-M4 | rule | p2p-core / p2p-sync / p2p-ws-sdk-* / p2p-cache / examples/IM 零字符改 | `git diff --stat` 无上述模块生产源 | ✅ PASS |

---

## 5. 结论签字

- **红线合规**：唯一生产改动源 `RaftServer.java` ✅；ZeroLeak3 守恒 ✅；零外泄 ✅；0 新依赖/线程 ✅；public 签名不变 ✅。
- **AC 覆盖**：R1~R12 全 PASS，U1/U2 均满分，NFR-C/P/M 全达标。
- **非确定性披露**：合跑级时序 flake（P57 T5/T8、P15 PerfRubric）为已知 in-JVM 模型抖动，**不阻塞交付**，建议按 §3 #1/#3/#5 治理。

**审查签字（独立第三方）**：TRAE-spec-mode Reviewer 2026-11-05 = **PASS（有条件，无红线阻塞）**
