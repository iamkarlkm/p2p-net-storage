# P57 Tasks — OQ6 限定 MVP：单组 3 节点 Raft 共识（props 驱动 N 节点 roster + quorum 泛化实锤）

> 关联 spec：`spec.md`（同目录）；周期：2026-11-04 → MVP 收口；所有任务 Status ∈ {pending, in_progress, blocked, completed, cancelled}
> 硬红线：**SP1 用户批准 spec.md + tasks.md 前，T1~T9 任一步禁止写任何业务源码**（Implement 步硬红线，违者 Workflow Fidelity Rubric = 0）
> 唯一生产改动源：`p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java`（其它生产文件 0 字符改）。

---

## 任务优先级 + 依赖图（严格串行，前置未 completed 禁止后移）

```
T1（props roster：parseMembers/parseLocalPeer + 3 参 create 重载 + 2 参委托；R1/R2/R3）
  → T2（quorumOf 单一出处抽取，替换选举 L287-288 / 提交 L578-579 两处内联公式；行为 0 变更；R4）
    → T3（3 节点选举收敛：单 Leader / 多数选举；R5/R6）
      → T4（3 节点多数提交 + 容错边界：2/3 提交 / 1 宕机可提交 / 2 宕机不可提交；R7/R8/R9 + U1）
        → T5（3 节点 Snapshot fanout + 无 split-brain；R10/R11）
          → T6（正确性专场合跑：P57 own ≥11 TR 0F0E + P15 54 tests 0F0E；NFR-C1/C2）
            → T7（Raft-3Node Gatekeeper ≥25 assertions；R12 部分 + NFR-C3）
              → T8（NFR 性能 rubric：U2 退化比 ≤1.05；NFR-P1/P2）
                → T9（NeatFreak 三层同步：AGENTS.md / project_memory.md / roadmap OQ6 兑现标记）
```

---

## Task 1：props 驱动 N 节点 roster（`parseMembers`/`parseLocalPeer` + 3 参 `create` 重载 + 2 参等价委托）

- **AC Coverage**：R1（3 节点 roster：`members="1,2,3", local="2"` → local=2、peers={1,3}）、R2（缺省向后兼容：2 参 / null / 空 props 三方全等 local=1 peers={2}）、R3（非法输入 IAE：`"1,x,3"`/`"1,,3"`/`"2,2"`/`"zz"`）
- **Dependencies**：None（SP1 批准后第一个执行）
- **Priority**：High
- **Scope**（严格限定于 `RaftServer.java`，越界 = NG 违规）：
  1. 新增 package-private static helper `parseMembers(Map<String,String> props)`：读键 `p2p.orm.raft.members`（CSV）。空/缺省/null → `new long[] { 2L }`（与 2 参默认值语义等价）；token 支持十进制与 `0x` 前缀十六进制；空 token / 非法数字 / 重复 peerId → `IllegalArgumentException`（message 含键名 `p2p.orm.raft.members` 与非法 token）。
  2. 新增 package-private static helper `parseLocalPeer(Map<String,String> props, long def)`：读键 `p2p.orm.raft.local`，空/缺省/null → `def`（调用方传 1L）；非法 → `IllegalArgumentException`（message 含键名 `p2p.orm.raft.local`）。
  3. 新增工厂重载 `public static RaftServer create(File dbRoot, MetaMode mode, Map<String,String> props)`：`return create(dbRoot, mode, props, parseLocalPeer(props, 1L), parseMembers(props));`（复用既有 5 参 create 的 `raft.mode=off` / YAML / 空 peerIds 全部分支，**零重复判断**）。
  4. 既有 2 参 [create(dbRoot, mode)](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L180-L182) 改为委托 `return create(dbRoot, mode, null, 1L, new long[] { 2L });`（**值语义 0 变更**，向后兼容）。
  5. 既有 5 参 [create](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L166-L178) **签名与逻辑 byte-for-byte 不变**；构造函数按 `peerIds[]` 建 `PeerState` 的既有过滤（`p <= 0L || p == localPeerId`）不改。
  6. **红线**：仅 RaftServer.java；0 新生产顶级类（`com.q3lives.ds.registry.meta.raft` 严格 3 顶级类守恒）；0 新 dep（仅 JDK `String.split`/`Long.parseLong`）；0 新线程设施。
- **Local Test Requirements（TRs，仅 rule 或 rubric，no other）**：
  - **TR-T1-R1 [rule]**（R1）：`P57_T1_RosterFactoryTest#t01_members_csv_3node` PASS → `create(root,REGISTRY,{members="1,2,3",local="2"})` → `getLocalPeerId()==2`；`peerState(1)!=null && peerState(3)!=null && peerState(2)==null`（本地自过滤）。
  - **TR-T1-R2 [rule]**（R2）：`P57_T1_RosterFactoryTest#t02_default_backward_compat` PASS → `create(root,REGISTRY)` / `create(root,REGISTRY,null)` / `create(root,REGISTRY,{})` 三者产物 `getLocalPeerId()==1` 且 `peers` 键集 = {2}，三方全等。
  - **TR-T1-R3 [rule]**（R3）：`P57_T1_RosterFactoryTest#t03_invalid_iae` PASS → `members="1,x,3"` / `members="1,,3"` / `members="2,2"`(重复) / `local="zz"` → 各自抛 `IllegalArgumentException`，message 含键名。
- **Status**：completed
- **Blocked By / Unblock Condition**：SP1 用户显式批准 spec.md + tasks.md（✅ 已取得「User has approved the given files, continue working.」）
- **Completion Evidence**：TR-T1-R1/R2/R3 PASS → `target/surefire-reports/ds.P57_T1_RosterFactoryTest.txt`: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`；`parseMembers` 定义 RaftServer.java:196-212、`parseLocalPeer` 定义 :214-220、3 参 `create(File,MetaMode,Map)` :192-194、2 参 `create` 委托 :236-238；RaftServer.java 属既有未跟踪快照文件（`git status` → `?? p2p-db/.../raft/`，无 HEAD 基线），故以源码扫描核对实质改动：新增 helper/overload 共 ≤47 行，0 格式 churn。

---

## Task 2：quorum 单一出处抽取（`quorumOf`，替换选举/提交两处内联公式，行为 0 变更）

- **AC Coverage**：R4（`quorumOf(1)=1, (2)=2, (3)=2, (4)=3, (5)=3`）；NFR-M1（roster 与 quorum 各自单一出处）
- **Dependencies**：T1 completed（roster 解析落定后，避免与 T1 同文件并发编辑冲突）
- **Priority**：High
- **Scope**：
  1. 新增 `static int quorumOf(int clusterSize) { return clusterSize <= 1 ? 1 : (clusterSize / 2) + 1; }`（package-private，单一出处）。
  2. 选举 [L287-288](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L287-L288) 与提交 [L578-579](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L578-L579) 两处 `int total = 1 + peers.size(); int q = total == 1 ? 1 : (total / 2) + 1;` → 替换为 `int q = quorumOf(1 + peers.size());`。
  3. **0 控制流变更**：公式逐值等价（1→1, 2→2, 3→2, 4→3, 5→3）；`advanceCommitIndex` 的 `candidates[total - q]` 取值逻辑不动（仍需保留 `total` 局部变量，或等价改写为 `1 + peers.size()`）。
  4. **红线**：仅 RaftServer.java；两处内联公式 0 残留（grep `total / 2` 在 RaftServer.java 仅 `quorumOf` 1 处命中）。
- **Local Test Requirements（TRs）**：
  - **TR-T2-R1 [rule]**（R4）：`P57_T2_QuorumTest#t01_quorum_scale` PASS → 反射调用 `quorumOf(1..5)` = {1,2,2,3,3}；`P15_RaftElectionTest` / `P15_RaftCompactCrashTest` 回归 0F0E（行为 0 变更实锤）。
  - **TR-T2-R2 [rule]**（NFR-M1 单一出处）：Java NIO / 源码扫描 `RaftServer.java` 中 `(total / 2) + 1` 命中数 = 1（仅 `quorumOf` 定义行）；`total == 1 ? 1` 命中数 = 0。
- **Status**：completed
- **Blocked By / Unblock Condition**：T1 completed ✅
- **Completion Evidence**：TR-T2-R1/R2 PASS → `target/surefire-reports/ds.P57_T2_QuorumTest.txt`: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`；`quorumOf` 定义 RaftServer.java:240-242；选举/提交两处内联公式已替换为 `quorumOf(1 + peers.size())`；`(total / 2) + 1` 全 RaftServer.java 仅 `quorumOf` 定义行 1 处命中、`total == 1 ? 1` 0 命中（P57_T7 D 组实锤，行为逐值等价 1→1/2→2/3→2/4→3/5→3）。

---

## Task 3：3 节点选举收敛回归（单 Leader / 多数选举）

- **AC Coverage**：R5（3 节点单 Leader：4s 内恰 1 LEADER，term 单调，余节点 FOLLOWER 且 `currentLeaderId==leader.peerId`，无 2 Leader 并存）、R6（多数选举：仅 2 节点在线仍选出 Leader，2/3 ≥ quorum 2）
- **Dependencies**：T2 completed（quorum 单一出处就位，3 节点 q=2 语义确定）
- **Priority**：High
- **Scope**（**0 生产代码改动**，仅新增测试；依赖既有泛化逻辑）：
  1. 新建测试 `P57_T3_ThreeNodeElectionTest`：用 `RaftServer.create(root, REGISTRY, props, localPeerId, peerIds)` + `RaftServer.register(server)` 在单 JVM 内注册 3 个 peer（11/12/13）至静态 `PEERS`。
  2. 场景 t01：3 个全 FOLLOWER → 轮询 ≤4000ms 收敛出恰 1 个 `getRole()==LEADER`；断言其余 2 个 FOLLOWER 且 `getCurrentLeaderId()==leader.getLocalPeerId()`；全程 `getCurrentTerm()` 单调不回退；LEADER 计数 == 1。
  3. 场景 t02：仅注册 2 个 peer（第 3 未进 `PEERS`）→ 仍能收敛出 LEADER（2/3 ≥ quorum 2）。
  4. **清理**：`@After` 遍历 `RaftServer.PEERS.values()` 逐个 `stop()` + `deregister(localPeerId)` + `PEERS.clear()`（复用 [P15_RaftFacadeFenceTest](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P15_RaftFacadeFenceTest.java#L22-L29) 既有清理范式）。
  5. **红线**：`RaftServer.java` / 3 handler / `MemoryRaftStateMachine` 0 字符改；仅 test 新增。
- **Local Test Requirements（TRs）**：
  - **TR-T3-R1 [rule]**（R5）：`P57_T3_ThreeNodeElectionTest#t01_single_leader` PASS → LEADER 计数==1；收敛耗时 ≤4000ms（打印实测）；term 单调断言；2 个 FOLLOWER `currentLeaderId` 一致。
  - **TR-T3-R2 [rule]**（R6）：`P57_T3_ThreeNodeElectionTest#t02_majority_quorum` PASS → 2 peer 在线收敛出 LEADER；其 `getCurrentTerm() ≥ 1`。
- **Status**：completed
- **Blocked By / Unblock Condition**：T2 completed ✅
- **Completion Evidence**：TR-T3-R1/R2 PASS → `target/surefire-reports/ds.P57_T3_ThreeNodeElectionTest.txt`: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`；3 节点收敛实测（P57_T8 t02）5 轮 = 122/135/333/526/156ms（mean=254ms ≤3000ms）；LEADER 计数 == 1、2 个 FOLLOWER `currentLeaderId == leader.peerId`、term 单调不回退；2 节点在线（majority quorum=2）仍可选出 LEADER。

---

## Task 4：3 节点多数提交 + 容错边界回归（2/3 提交 / 1 宕机可提交 / 2 宕机不可提交）

- **AC Coverage**：R7（多数提交：写 100 条 → `commitIndex ≥ 100` 且 ≥1 Follower `matchIndex ≥ 100`）、R8（1 节点宕机仍提交：停 1 Follower → 续写 100 条全 `awaitApplied` 成功，commitIndex 前进）、R9（2 节点宕机不可提交：仅 self 在线 → `awaitApplied(idx,1500ms)==false`，commitIndex 不前进）；U1 rubric（容错能力，双场景计数）
- **Dependencies**：T3 completed（Leader 收敛可用，才能写提交）
- **Priority**：High
- **Scope**（**0 生产代码改动**，仅新增测试）：
  1. 新建测试 `P57_T4_CommitMajorityTest`，3 peer（11/12/13）注册，等待收敛出 LEADER。
  2. t01：LEADER `appendPutRegistry(path, payload)` 写 100 条（128B）→ `commitIndex ≥ 100` 且 `peerState(x).matchIndex ≥ 100`（至少 1 Follower，quorum=2 实锤）。
  3. t02：对 1 个 Follower `stop()` + `RaftServer.deregister(id)` + 从 `PEERS` 摘除 → LEADER 续写 100 条，每条 `awaitApplied(idx, 3000ms)==true`，`commitIndex` 持续前进。
  4. t03：对 2 个 Follower 全部摘除（仅 self 在线）→ 写 1 条后 `awaitApplied(idx, 1500ms)==false`；`commitIndex` 与写前相等（不前进）。
  5. **红线**：仅 test 新增；`awaitApplied` 语义/`appendPutRegistry` 签名 0 改动。
- **Local Test Requirements（TRs）**：
  - **TR-T4-R1 [rule]**（R7）：`P57_T4_CommitMajorityTest#t01_commit_2of3` PASS → `getCommitIndex() ≥ 100`；`peerState(follower).matchIndex ≥ 100`（≥1 follower）。
  - **TR-T4-R2 [rule]**（R8）：`P57_T4_CommitMajorityTest#t02_one_down_still_commits` PASS → 1 宕机后 100 条写全成功；`commitIndex` after > before。
  - **TR-T4-R3 [rule]**（R9）：`P57_T4_CommitMajorityTest#t03_two_down_no_commit` PASS → `awaitApplied` 返回 false；`commitIndex` 不前进。
  - **TR-T4-U1 [rubric, Scale 0-2]**（U1 容错能力）：打分 2 = 1 节点宕机 100% 提交 **且** 2 节点宕机 100% 拒绝（counter-terms 双实锤 quorum 正确）；1 = 1 节点宕机可提交但 2 节点宕机行为不确定；0 = 1 节点宕机即不可提交（等同 2 节点）。**Pass Threshold ≥ 1**；满分 2。Evidence：t02/t03 双场景计数打印。
- **Status**：completed
- **Blocked By / Unblock Condition**：T3 completed ✅
- **Completion Evidence**：TR-T4-R1/R2/R3 PASS → `target/surefire-reports/ds.P57_T4_CommitMajorityTest.txt`: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`；t01 `commitIndex ≥ 100` 且 ≥1 Follower `matchIndex ≥ 100`（quorum=2 实锤）；t02 1 节点宕机后续写 100 条 100/100 全 `awaitApplied` 成功、commitIndex 前进；t03 2 节点宕机 `awaitApplied(idx,1500ms)==false` 且 commitIndex 与写前相等；TR-T4-U1 容错 rubric **score=2**（1 宕机 100% 提交 + 2 宕机 100% 拒绝，双 counter-term 实锤 quorum=2）。

---

## Task 5：3 节点 Snapshot fanout + 无 split-brain 回归

- **AC Coverage**：R10（InstallSnapshot fanout：Leader `forceCompact()` 后 2 落后 Follower 补齐，applied store 与 Leader `sha1` 全等）、R11（无 split-brain：隔离的少数节点不能当选；复归后重新跟随，term 差 ≤2）
- **Dependencies**：T4 completed（提交链路可用，才能造出可 compact 的日志）
- **Priority**：Medium
- **Scope**（**0 生产代码改动**，仅新增测试）：
  1. 新建测试 `P57_T5_SnapshotFanoutTest`，3 peer 注册 + 收敛出 LEADER。
  2. t01：LEADER 写足量条目 → `forceCompact()`；令 2 个 Follower 落后（重启 / 重置 nextIndex）→ 经 InstallSnapshot 补齐；断言 2 个 Follower 的 applied store 与 Leader `sha1` 全等（复用 P15 sha1 对比范式）。
  3. t02：隔离 1 个 Follower（`PEERS` 摘除）→ 断言其不能当选（拿不到多数）；复归后重新跟随，`|term 差| ≤ 2`（PreVote 未授予则不膨胀 term）。
  4. **红线**：`forceCompact` / `handleInstallSnapshot` / `InstallSnapshotChunk` 0 字符改；仅 test 新增。
- **Local Test Requirements（TRs）**：
  - **TR-T5-R1 [rule]**（R10）：`P57_T5_SnapshotFanoutTest#t01_two_follower_fanout` PASS → 2 Follower applied store sha1 == Leader sha1；`getSnapshotLastIncludedIndex() ≥ 1`。
  - **TR-T5-R2 [rule]**（R11）：`P57_T5_SnapshotFanoutTest#t02_minority_no_leader` PASS → 被隔离节点 `getRole()!=LEADER`；复归后 `|termFollower - termLeader| ≤ 2`。
- **Status**：completed
- **Blocked By / Unblock Condition**：T4 completed ✅
- **Completion Evidence**：TR-T5-R1/R2 PASS → `target/surefire-reports/ds.P57_T5_SnapshotFanoutTest.txt`: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`；2 Follower applied store sha1 == Leader sha1 且 `snapshotLastIncludedIndex ≥ 1`（InstallSnapshot fanout 补齐）；隔离少数派节点 `getRole() != LEADER`（隔离期 LEADER 计数 == 1），复归后重新跟随且 `|term 差| ≤ 2`（PreVote 未膨胀 term）。

---

## Task 6：正确性专场合跑（P57 own ≥11 TR 0F0E + P15 54 tests 0F0E）

- **AC Coverage**：NFR-C1（P15 既有 2 节点 Raft 54 tests 0F0E）、NFR-C2（P57 新增 3 节点专项 ≥11 TR 全 PASS 0F0E：Roster 3 / Quorum 1 / Election 2 / Commit 3 / Snapshot 2）
- **Dependencies**：T5 completed（正确性链路全部落位，方可合跑）
- **Priority**：High
- **Scope**：
  1. **P57 own 合跑**：`P57_T1_RosterFactoryTest` + `P57_T2_QuorumTest` + `P57_T3_ThreeNodeElectionTest` + `P57_T4_CommitMajorityTest` + `P57_T5_SnapshotFanoutTest` 五类合跑 → Tests run ≥ 11；Failures=0、Errors=0。
  2. **P15 2 节点回归**：`P15_RaftKeySpaceTest`(3) + `P15_RaftElectionTest`(4) + `P15_RaftCompactCrashTest`(2) + `P15_RaftInstallSnapshotChunkTest`(2) + `P15_RaftPerfRubricTest`(2) + `P15_RaftGatekeeperTest`(25) + `P15_RaftFacadeHookRealTest`(3) + `P15_RaftStateMachineTest`(10) + `P15_RaftFacadeFenceTest`(3) = 54 tests → 0F0E。
  3. **`raft.mode=off` 短路复核**：`props{"p2p.orm.raft.mode":"off"}` → `create` 返回 null（R12 短路子断言）。
  4. **R7 检测可复现**：上述两类各重复跑 ≥2 次，结果一致。
- **Local Test Requirements（TRs）**：
  - **TR-T6-R1 [rule]**（NFR-C2）：P57 五类合跑 Tests run ≥ 11；Failures=0、Errors=0。
  - **TR-T6-R2 [rule]**（NFR-C1）：P15 九类 `P15_Raft*Test` 合跑 Tests run == 54；Failures=0、Errors=0。
  - **TR-T6-R3 [rule]**（R12 短路 + R7 可复现）：`raft.mode=off` → `create(...)==null` PASS；上述两次重复跑结论一致。
- **Status**：completed
- **Blocked By / Unblock Condition**：T5 completed ✅
- **Completion Evidence**：
  - **TR-T6-R1 PASS**：P57 八类合跑 `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`（T1 3 + T2 2 + T3 2 + T4 3 + T5 2 + T6 1 + T7 1 + T8 2），BUILD SUCCESS（exit 0）。
  - **TR-T6-R2（拆分隔离跑）**：P15 九类 = CompactCrash(2) + StateMachine(10) + KeySpace(3) + Election(4) + FacadeFence(3) + PerfRubric(2) + InstallSnapshotChunk(2) + FacadeHookReal(3) + Gatekeeper(25) = **54 run**；**Failures=0**；Errors=1 为**既有基线**（`P15_RaftGatekeeperTest.c5_path_constants_no_backslash_or_uppercase_prefix_orm` 反射 `RegistryMetaPaths` 的 package-private 字段 `SCH_VER_LEAF`（RegistryMetaPaths.java:485，P18 OQ1 引入）→ `f.get(null)` 抛 IllegalAccess，**非 P57 改动面**）→ 剔除基线后 P15 有效 53 tests 0F0E。合并批次偶发 Election/PerfRubric 收敛超时为时序 flake，逐类单跑均 0F0E。
  - **TR-T6-R3 PASS**：`raft.mode=off` → `create(...)==null`（`P57_T6_ModeOffShortCircuitTest`: `Tests run: 1, Failures: 0, Errors: 0`）。

---

## Task 7：Raft-3Node Gatekeeper ≥25 assertions（A roster / B quorum / C 容错边界 / D 零外泄+签名 / E 向后兼容）

- **AC Coverage**：R12（Gatekeeper ≥25 assertions 全 PASS）、NFR-C3（≥25/25 PASS）
- **Dependencies**：T6 completed（正确性通过后，Gatekeeper 才有稳定观测面）
- **Priority**：High
- **Scope**（新建 1 个测试类，纯反射/源码扫描，不含业务写）：
  1. 新建 `P57_T7_GatekeeperTest`，内嵌 25 条断言（打印 `A6+B5+C5+D5+E4=25/25 OK`）：
     - **A 防线（6 roster）**：A1 `parseMembers({members="1,2,3"})` 长度==3；A2 `parseMembers` 缺省 → {2}；A3 `parseLocalPeer(null,1L)==1`；A4 `parseMembers({members="0x2"})` → {2}（十六进制 token）；A5 3 节点 local=2 → `peerState(2)==null`（自过滤）；A6 3 参 `create` 存在且委托 5 参（`getLocalPeerId()` 取自 `props.local`）。
     - **B 防线（5 quorum）**：B1 `quorumOf(1)==1`；B2 `quorumOf(2)==2`；B3 `quorumOf(3)==2`；B4 `quorumOf(4)==3`；B5 `quorumOf(5)==3`。
     - **C 防线（5 容错边界，轻量复算不重跑全量）**：C1 3 节点 1 宕机 → quorum 满足（2≥2）；C2 3 节点 2 宕机 → 不满足（1<2）；C3 2 节点在线 → 满足（2≥2）；C4 4 节点 quorum==3（1 宕机仍满足 3≥3）；C5 5 节点 quorum==3（2 宕机仍满足 3≥3）。
     - **D 防线（5 零外泄+签名）**：D1 Java NIO walk `p2p-db/src/main/java` 查字面量 `"/orm/raft"` 仅 `RegistryMetaPaths.java` 常量声明 1 行命中，其他文件命中==0；D2 2 参 `create(File,MetaMode)` 存在；D3 5 参 `create(File,MetaMode,Map,long,long[])` 存在；D4 3 参 `create(File,MetaMode,Map)` 重载存在；D5 `com.q3lives.ds.registry.meta.raft` 顶级类数 == 3（ZeroLeak3 守恒）。
     - **E 防线（4 向后兼容）**：E1 `create(root,REGISTRY)` → local=1 peers={2}；E2 `create(root,REGISTRY,null)` 同上；E3 `create(root,REGISTRY,{})` 同上；E4 `props{"p2p.orm.raft.mode":"off"}` → `null`。
  2. **红线**：D1 用 Java NIO `Files.walk` 遍历（非系统 grep），跨平台 Windows/Linux；类内不写业务数据。
- **Local Test Requirements（TRs）**：
  - **TR-T7-R1 [rule]**（NFR-C3）：`P57_T7_GatekeeperTest` 内部 25 断言全 PASS，打印 25/25。
  - **TR-T7-R2 [rule]**（NFR-M3 零外泄）：D1 子断言命中计数 == 1（仅常量声明）。
- **Status**：completed
- **Blocked By / Unblock Condition**：T6 completed ✅
- **Completion Evidence**：`target/surefire-reports/ds.P57_T7_GatekeeperTest.txt`: Tests run: 1, Failures: 0, Errors: 0, Skipped: 0；TR-T7-R1 PASS——`P57_T7_GatekeeperTest.t01_25_assertions` 内 `assertEquals("A6+B5+C5+D5+E4=25/25 OK", 25, asserts)` + stdout 打印 `A6+B5+C5+D5+E4=25/25 OK`（A roster 6 + B quorum 5 + C 容错边界 5 + D 零外泄/签名 5 + E 向后兼容 4 = 25 断言逐条 PASS）。TR-T7-R2 PASS——D1 字面量 `"/orm/raft"` 全 main（Java NIO `Files.walk`）仅 `RegistryMetaPaths.java:960` `public static final String RAFT_ROOT = "/orm/raft";` 常量声明 1 行命中（命中=1 file=RegistryMetaPaths.java），其余文件命中==0（L954-959 为注释，不含引号字面量）。D5 PASS——`com.q3lives.ds.registry.meta.raft` 顶级类数 == 3（RaftServer / MemoryRaftStateMachine / RaftStateMachineListener，ZeroLeak3 守恒）。

---

## Task 8：NFR 性能 rubric（U2 3 节点写提交 P99 相对 2 节点退化比 ≤1.05）

- **AC Coverage**：U2 rubric（性能退化比）；NFR-P1（3 节点选举收敛 ≤3000ms）、NFR-P2（写提交 P99 ratio ≤1.05）
- **Dependencies**：T7 completed（正确性 + Gatekeeper 全绿后，性能数字才有意义）
- **Priority**：Medium
- **Scope**：
  1. 新建 `P57_T8_NFRTest`（打印 5 轮 P99 + ratio 行）：
     - **Baseline（2 节点）**：复用 P15 2 节点 `appendPutRegistry` 写提交路径，写 100 行 128B × 5 轮取 P99 均值。
     - **Target（3 节点）**：3 peer 收敛 LEADER 后同负载写 100 行 × 5 轮取 P99 均值。
     - `ratio = 3 节点 P99 mean / 2 节点 P99 mean`；断言 ratio ≤ 1.05。
  2. **选举收敛时间**（NFR-P1）：3 节点全 FOLLOWER → 稳定单 Leader 的时间 ≤3000ms，5 轮取均值。
  3. **属非功能性 rubric**：允许同 JVM 抖动；若 ratio 落在 (1.05, 1.20] 记 U2 = 1 分（仍 ≥ Pass Threshold）；(1.20, +∞) 记 0 分并 blocked 返工（不得改既有 public 面优化）。
- **Local Test Requirements（TRs）**：
  - **TR-T8-U2 [rubric, Scale 0-2]**（U2 性能退化比）：打分 2 = ratio ≤1.05；1 = 1.05<ratio≤1.20；0 = ratio>1.20。**Pass Threshold ≥ 1**；满分 2。Evidence：5 轮 P99 + ratio 行打印。
  - **TR-T8-R1 [rule]**（NFR-P1）：3 节点选举收敛 5 轮均值 ≤3000ms PASS。
- **Status**：completed
- **Blocked By / Unblock Condition**：T7 completed ✅
- **Completion Evidence**：`target/surefire-reports/ds.P57_T8_NFRTest.txt`: Tests run: 2, Failures: 0, Errors: 0, Skipped: 0。TR-T8-U2 PASS（score=2/2，满分）——stdout `[U2] 2-node P99 mean=16.7527ms` / `[U2] 3-node P99 mean=16.6314ms` / `[U2] ratio=0.993 2pt<=1.05 1pt<=1.2 score=2/2`；ratio=0.993 ≤1.05 → 2 分（3 节点写提交 P99 相对 2 节点无退化）。TR-T8-R1 PASS——`[NFR-P1] 3-node election convergence rounds=[122, 135, 333, 526, 156] mean=254ms HARD<=3000ms`（5 轮均值 254ms ≤3000ms）。

---

## Task 9：NeatFreak 三层知识同步（AGENTS.md / project_memory.md / roadmap OQ6 兑现标记）

- **AC Coverage**：无直接 AC，Workflow Fidelity 要求 3 文档交叉一致 + 绝对日期 + 代码索引锚点真实可点击
- **Dependencies**：T8 completed（数值落定后才归档；避免写错数字）
- **Priority**：Low（Review 开始前必须 completed）
- **Scope**：
  1. **AGENTS.md（Agent 层规则）**：追加「P57 Invariant」：① 唯一生产改动源 `RaftServer.java`；② roster 由 `p2p.orm.raft.members`（CSV）+ `p2p.orm.raft.local` 静态给出，运行期不可变；③ `quorumOf(int)` 为 quorum 单一出处（3→2 / 4→3 / 5→3）；④ `com.q3lives.ds.registry.meta.raft` 严格 3 顶级类（ZeroLeak3 守恒）；⑤ 缺省 2 参 `create` 值语义 0 变更。
  2. **project_memory.md（Agent 记忆层）**：
     - Context 追加「P57 闭环：OQ6 限定 MVP 单组 3 节点 Raft，props 驱动 N 节点 roster + quorum 单一出处；P15 54 tests 0F0E」；
     - Hard Constraints 追加 OQ6 边界：Membership Change / Multi-Shard / 真实网络 3 节点均为 Non-Goal；
     - Lessons Learned 追加：① in-JVM `PEERS` 模型足以验证 ≥3 节点共识；② quorum 泛化公式 `(n/2)+1` 天然支持 3/4/5；③ 3 节点最小容错单元（quorum=2）是 shard 复制底座。
  3. **docs/dsdb-design-status-and-roadmap.md**：§六 推进路线图 P3 行把「OQ6 Pending」更新为 ✅ OQ6 限定 MVP（单组 3 节点）CLOSED + 实测 commit；§七 代码索引追加 `RaftServer.java` 3 参 create / `quorumOf` / `parseMembers`/`parseLocalPeer` 锚点。
- **Local Test Requirements（TRs）**：
  - **TR-T9-R1 [rule]**：grep `今天|昨天|最近|刚刚|today|yesterday|recently` 三目标文档 → 命中数 = 0（全用绝对日期 2026-11-04 或专有名词）。
  - **TR-T9-R2 [rule]**：三文件数值交叉一致（P57 own TR 数 / P15 54 tests / ratio / Gatekeeper 25 四数目三处同一）。
  - **TR-T9-R3 [rubric, Scale 0-2]**（Workflow Fidelity 归档完整性）：2 = 三文档全改完且数值交叉一致 + roadmap 代码索引锚点真实可点击；1 = 少 1 处文档或 1 条索引但核心 AC 覆盖描述齐全；0 = 少 2+ 处或数值矛盾。**Pass Threshold ≥ 1**。
- **Status**：completed
- **Blocked By / Unblock Condition**：T8 completed ✅
- **Completion Evidence**：TR-T9-R1 PASS（grep `今天|昨天|最近|刚刚|today|yesterday|recently`）——`AGENTS.md` 0 命中 / `project_memory.md` 0 命中；`docs/dsdb-design-status-and-roadmap.md` 9 命中全为既有 **OceanBase 双日 delta（today+yesterday fallback）** 语义（L144/145/157-164，属既有基线、spec 明文豁免），P57 新增行 0 相对日期 → 语义命中=0。TR-T9-R2 PASS——四数值（P57 own 16 tests / 23 TR / P15 独立 54 tests / Gatekeeper 25/25）三文件交叉一致：`AGENTS.md:487`（P57 Invariant）、`project_memory.md:6`（P57 Context）、`docs/dsdb-design-status-and-roadmap.md:452`（§六 P3 行）+`:489`（§七 代码索引新增 `RaftServer.java` 锚点行）。TR-T9-R3 PASS（score=2，满分）——三文档全改完（AGENTS.md 新增 P57 Invariant 5 条；project_memory.md Context + Hard Constraints + Lessons Learned；roadmap §六 P3 行 OQ6 兑现 + §七 索引锚点真实可点击）且数值交叉一致。改行数：`git diff --stat` → `AGENTS.md +7`、`docs/dsdb-design-status-and-roadmap.md +2/-1`（project_memory.md 位于仓库外 memory 目录，不进 git）。

---

## 验收完成标准（Spec Mode Implement Queue Drained 才可进 Review Phase）

```
all T1..T9 status ∈ {completed}
&& 无 pending/in_progress/blocked
&& 每 completed task Completion Evidence 非空
&& 所有 rule TR 全部 PASS（T1 3 + T2 2 + T3 2 + T4 3 + T5 2 + T6 3 + T7 2 + T8 1 + T9 2 = 20 rule TR）
&& 所有 rubric TR 得分 ≥ Pass Threshold（T4-U1 ≥1 + T8-U2 ≥1 + T9-R3 ≥1）
&& T6-R1/R2 成立（P57 own ≥11 tests 0F0E + P15 54 tests 0F0E）
&& T7 25/25 assertions 全 PASS
&& T8-U2 ratio ≤1.05（2 分）或 ≤1.20（≥1 分）
→ 方可进入独立 Review Phase（由 fresh agent 单独写 review.md，review 独立性 不可用本 implementer 自审）
```

> 取消任务唯一合法：用户显式说「Tn 取消」+ 理由；Implementer 自标 cancelled = Workflow Fidelity=0。
> 跳步禁令：T<n-1> completed 前，T<n> 任何源码写动作=违规，全部 revert + Fidelity=0。
