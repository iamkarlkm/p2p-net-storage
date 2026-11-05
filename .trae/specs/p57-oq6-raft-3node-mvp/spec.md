# P57 Spec — OQ6 限定 MVP：单组 3 节点 Raft 共识（props 驱动 N 节点 roster + quorum 泛化实锤）

> 启动：2026-11-04（P56 OQ12-㉞① 在线 DDL 全量迁移 CLOSED `8da2535` 之后）。
> OQ6 出处：`docs/dsdb-design-status-and-roadmap.md` P3 行「OQ6 Pending」；P15 spec §2.2 Non-Goal 原话「≥3 节点 等 OQ5 Linux 复核后另开 P17 OQ6（预计 12 周）」。
> **本阶段严格限定为「单组 3 节点」MVP，12 周「完整 Multi-Raft Shard」愿景不在本阶段。**

---

## 1. 问题与用户

### 1.1 现状问题

P15 已交付 **2 节点 Raft**（Leader Election + AppendEntry + PreVote + Snapshot + Log Compaction，CLOSED 2026-09-27 54 tests 0F0E），但其 quorum=2/2 = **无容错**：任一节点宕机即失去多数，写不可提交。roadmap 的 OQ6 明确要求 ≥3 节点，被 P15 显式划为 Non-Goal。

现状探针结论（本 spec 前置实测）：

1. **唯一硬编码点**：[RaftServer.java L180-182](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L180-L182) 默认工厂 `create(dbRoot, mode)` → `new long[] { 2L }` 把 roster 写死为「本地 1 + 对端 2」。这是唯一阻碍 ≥3 节点的硬编码。
2. **quorum 已天然泛化但无断言**：选举 [L287-288](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L287-L288) 与提交 [L578-579](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java#L578-L579) 两处均为 `int total = 1 + peers.size(); int q = total == 1 ? 1 : (total / 2) + 1;`（3 节点 → q=2、4 节点 → 3、5 节点 → 3）。**公式正确，但零 3/4/5 节点回归**，且公式在两处重复（无双出处单一真源）。
3. **多 peer 机制已就绪**：构造器按 `peerIds[]` 建 `PeerState`（过滤 `p <= 0L || p == localPeerId`）；`PEERS` 静态 map（in-JVM 对等体）+ 3 个网络 handler（31201-31203 / ACK 31301-31303）走 `PEERS.values()` 查找；`becomeLeader`/`advanceCommitIndex`/`tryAppendEntries`/`handleInstallSnapshot` 全部按 `peers` 集合泛化，无「恰好 2 个」假设。
4. **生产集成面**：`RaftServer.create` 在 `p2p-db/src/main` 无任何调用点（集成 hook 在测试 facade），2 参默认工厂仅被 `P15_RaftFacadeFenceTest` 使用。→ 本阶段改动的生产源文件**仅有 `RaftServer.java` 一个**。

### 1.2 目标用户与收益链路

| 用户/阶段 | 直接收益 | 最终打通（与 roadmap 对齐） |
|-----------|----------|-------------------------------|
| HA 运维（3 节点集群） | **容忍 1 节点宕机**：3 节点中任一 Follower 宕机，Leader 仍以 quorum=2 提交，写不中断（对比 2 节点任一宕机即停写） | 生产 HA 从「主备」升级为「多数派容错」 |
| OQ3 跨节点 2PC | majority 提交（2/3）比 2/2 更稳：单节点抖动不阻塞 CommitIndex 前进 | 跨节点强一致事务的容错底座 |
| 后续完整 Multi-Raft Shard | 「单组 3 节点」是 shard 的最小复制单元；本阶段把「单组 N 节点」泛化 + 回归做实，为多组独立 Leader 打地基 | roadmap P3 OQ6 的限定 MVP 前置 |

---

## 2. 目标（Goals）与非目标（Non-Goals）

### 2.1 Goals

**G1：props 驱动 N 节点 roster（3/4/5 参数化），缺省 100% 向后兼容 2 节点**
- 新增 props 键 `p2p.orm.raft.members`（CSV peerId 列表，如 `"1,2,3"`）+ `p2p.orm.raft.local`（本节点 peerId，缺省 1）。
- 缺省（无 `members`）→ 与既有 2 参默认工厂**值语义全等**（local=1, peers={2}）。
- 既有 2 参 + 5 参 `create` 签名 byte-for-byte 不变（2 参改为等价委托）。

**G2：quorum 单一出处 + 3/4/5 泛化断言**
- 把 L287-288 / L578-579 两处内联公式抽为唯一 `static int quorumOf(int clusterSize)`（**行为 0 变更**），并断言 1/2/3/4/5 → 1/2/2/3/3。

**G3：单组 3 节点共识正确性**
- 选举：3 节点（11,12,13）全 FOLLOWER → 收敛出**恰 1 个** LEADER（需 ≥2 票），其余为 FOLLOWER 且 `currentLeaderId` 一致，无 2 Leader 并存。
- 提交：quorum=2（self + ≥1 Follower matchIndex ≥ n）→ commitIndex 前进；**1 节点宕机仍可提交**；**2 节点宕机不可提交**（多数 < 2）。
- 无 split-brain：被隔离的少数节点不能当选。

**G4：3 节点快照 + InstallSnapshot fanout**
- Leader 触发 compact 后，向 2 个落后 Follower 分发 InstallSnapshot chunk；2 Follower 的 applied store 与 Leader **sha1 全等**。

**G5：回归零破坏 + Gatekeeper + NFR**
- P15 既有 2 节点 54 tests **0F0E 不破坏**；`raft.mode=off` 依旧 `create` 返回 null。
- Raft-3Node Gatekeeper ≥25 assertions 钉死 N 节点 roster / quorum / 容错边界 / 零外泄 / 向后兼容。
- 3 节点选举收敛 & 写提交 P99 相对 2 节点退化 ≤ +5%（用户设定 NFR 硬指标）。

### 2.2 Non-Goals（本阶段严格不做，越界 = FAIL）

- ❌ **完整 Multi-Raft Shard**：多组 Raft、每组独立 Leader、跨组分片路由、ShardRouter 编排 —— 12 周愿景，本阶段不做。
- ❌ **Membership Change（成员变更）**：joint consensus / 运行时动态增删节点 / 配置变更日志 —— roster 仅由启动 props 静态给出，运行期不可变。
- ❌ **真实网络 3 节点**：保持 in-JVM `PEERS` 对等体模型（3 个网络 handler 31201-31203 / 31301-31303 **零字符改**）；本阶段不验证跨进程/跨机 RPC。
- ❌ **改 P15 2 节点语义 / P18~P56 既有源码**：仅允许改 `RaftServer.java`；`MemoryRaftStateMachine` / `RaftStateMachineListener` / 3 handler / P18~P56 全部零字符改。
- ❌ **0 新 Maven 依赖 / 0 新生产顶级类 / 0 新线程设施**：`com.q3lives.ds.registry.meta.raft` 包 ZeroLeak3 守恒（严格 3 顶级类），保持十四重基线；线程仅复用既有 per-server `scheduler`（3 节点 = 3 个既有 scheduler，非新增线程设施）。
- ❌ **0 改既有 public 签名**：`create(dbRoot,mode)` 与 `create(dbRoot,mode,props,localPeerId,peerIds)` byte-for-byte 不变；不新增对外 SDK / examples / p2p-ws-sdk 改动。
- ❌ **CRDT / 无 Leader 多写**：scope 延续 P15 选定的 Raft 强一致路线。

---

## 3. 功能需求（Functional Requirements）

### 3.1 FR1：props 驱动 roster（`RaftServer.java` —— 本阶段唯一生产改动源）

1. 新增 package-private static helper：
   - `static long[] parseMembers(Map<String,String> props)`：读 `p2p.orm.raft.members`（CSV）。空/缺省 → `new long[] { 2L }`（与 2 参默认等价）；token 支持十进制与 `0x` 前缀十六进制；空 token / 非法数字 / 重复 peerId → `IllegalArgumentException`（message 含键名与非法 token）。
   - `static long parseLocalPeer(Map<String,String> props, long def)`：读 `p2p.orm.raft.local`，空/缺省 → `def`（=1L）；非法 → `IllegalArgumentException`。
2. 新增工厂重载 `public static RaftServer create(File dbRoot, MetaMode mode, Map<String,String> props)`：
   `return create(dbRoot, mode, props, parseLocalPeer(props, 1L), parseMembers(props));`
   （复用既有 5 参 create 的 `raft.mode=off` / YAML / 空 peerIds 全部分支，**零重复判断**。）
3. 既有 2 参 `create(dbRoot, mode)` 改为委托 `return create(dbRoot, mode, null, 1L, new long[] { 2L });`（**值语义 0 变更**，向后兼容）。
4. 既有 5 参 `create(dbRoot, mode, props, localPeerId, peerIds)` **签名与逻辑 byte-for-byte 不变**。

### 3.2 FR2：quorum 单一出处（去重，行为 0 变更）

1. 新增 `static int quorumOf(int clusterSize) { return clusterSize <= 1 ? 1 : (clusterSize / 2) + 1; }`。
2. 选举（原 L287-288）与提交（原 L578-579）两处 `int total = 1 + peers.size(); int q = total == 1 ? 1 : (total / 2) + 1;` → 替换为 `int q = quorumOf(1 + peers.size());`。
3. **0 控制流变更**：公式逐值等价（1→1, 2→2, 3→2, 4→3, 5→3）；`advanceCommitIndex` 的 `candidates[total - q]` 取值逻辑不动。

### 3.3 FR3：3 节点共识正确性（无新代码，依赖既有泛化逻辑 + 新回归）

1. 选举：3 server 注册进 `PEERS` → 收敛出恰 1 LEADER；`currentTerm` 单调；其余节点 FOLLOWER 且 `currentLeaderId == leader.peerId`。
2. 多数选举：仅 2 server 在线（第 3 未注册）→ 仍能选出 LEADER（2/3 ≥ quorum 2）。
3. 提交：Leader 写 n 条 → `commitIndex ≥ n` 且至少 1 Follower `matchIndex ≥ n`（quorum=2）。
4. 容错边界：1 Follower 宕机（`stop()` + 摘除 `PEERS`）→ 写 100 条全部成功提交；2 节点宕机（仅 self 在线）→ `awaitApplied(idx, 1500ms)` 返回 false，commitIndex 不前进，本地不产生 stale applied。
5. 快照 fanout：Leader `forceCompact()` 后，2 个落后 Follower 经 InstallSnapshot 补齐，applied store 与 Leader sha1 全等。

### 3.4 FR4：向后兼容（缺省 2 节点 + `raft.mode=off`）

1. `create(root, REGISTRY)` 与 `create(root, REGISTRY, null)` / `create(root, REGISTRY, props 无 members)` 三者产物 peerId 集合**全等**（local=1, peers={2}）。
2. `props{"p2p.orm.raft.mode":"off"}` → `create` 返回 `null`（既有短路不变）。
3. P15 全部 2 节点 54 tests **0F0E** 不破坏。

---

## 4. 非功能需求（Non-Functional Requirements）

### 4.1 正确性（硬门槛）

| 编号 | 描述 | 阈值 |
|------|------|------|
| NFR-C1 | P15 既有 2 节点 Raft 54 tests 回归 0F0E（本阶段改动面仅 `RaftServer.java`，2 节点语义必须 100% 不变） | 54/54 PASS 0F 0E，硬门槛 |
| NFR-C2 | P57 新增 3 节点专项 tests 全部 PASS（Roster 3 / Quorum 1 / Election 2 / Commit 3 / Snapshot 2 = ≥11 TR） | 全 PASS 0F 0E，硬门槛 |
| NFR-C3 | Raft-3Node Gatekeeper ≥25 assertions 全 PASS（A roster / B quorum / C 容错边界 / D 零外泄+签名 / E 向后兼容） | ≥25/25 PASS，硬门槛 |

### 4.2 性能（相对 2 节点 baseline，硬指标 ±5%）

| 编号 | 维度 | 目标阈值 | 环境约束 |
|------|------|----------|----------|
| NFR-P1 | 3 节点选举收敛时间（全 FOLLOWER → 稳定单 Leader） | ≤ 3000ms（heartbeat 25ms / election timeout 120-200ms 抖动） | in-JVM 3 server，5 轮取均值 |
| NFR-P2 | 3 节点写提交 P99 相对 P15 2 节点写提交 P99 的退化比 | ratio ≤ 1.05（+5% 内，用户 NFR 硬指标） | 同 JVM、同 N；写 100 行 128B，5 轮 P99 均值对比 |

### 4.3 可维护性 + 可观测性

| 编号 | 描述 |
|------|------|
| NFR-M1 | roster 与 quorum 各自**单一出处**：`parseMembers`/`parseLocalPeer`/`quorumOf` 各 1 处定义；不接受散落内联公式。 |
| NFR-M2 | props 非法输入 100% 快速失败（IAE），不得静默回退到默认 roster（避免「配置错但默默跑 2 节点」）。 |
| NFR-M3 | 零外泄：`/orm/raft` 字面量仍仅 `RegistryMetaPaths.java` 声明 1 处；新增代码不得硬编码路径/命令号。 |
| NFR-M4 | 零跨模块影响：p2p-core / p2p-sync / p2p-ws-sdk-* / p2p-cache / examples/IM **零字符改**。 |

---

## 5. 约束与假设

### 5.1 硬约束（突破 = Spec 失败，Implement 阶段必须 revert）

1. **唯一生产改动源 = `RaftServer.java`**：`MemoryRaftStateMachine` / `RaftStateMachineListener` / `javax.net.p2p.server.handler.Raft*Handler` / P18~P56 全部 0 字符改。
2. **0 新生产顶级类（ZeroLeak3 守恒）**：`com.q3lives.ds.registry.meta.raft` 包严格 3 顶级类 = {`MemoryRaftStateMachine`, `RaftServer`, `RaftStateMachineListener`}；维持项目十四重基线，不得新增第 15 重。
3. **0 新 Maven 依赖**（`pom.xml` diff 空）；props 解析用 JDK 原生 `String.split`/`Long.parseLong`。
4. **0 新线程设施**：仅复用既有 per-server `ScheduledExecutorService`（3 节点 = 3 个既有 scheduler 实例，非新线程模型）。
5. **既有 public 面 byte-for-byte 0 改**：2 参 + 5 参 `create` 签名不变；新增仅 1 个 3 参 `create` 重载与 package-private helper。
6. **R7 检测可复现 / R8 幂等断点续跑 / 0 TODO**：所有 TR 重复跑结果一致；不遗留 TODO。
7. **Git 最小侵入**：仅 `git add` 任务相关文件，严禁 `git add -A`。

### 5.2 依赖项（已 CLOSED，不做任何变更）

- **P15（2 节点 Raft）**：`RaftServer` 3 角色状态机 + PreVote + AppendEntries + InstallSnapshot + Log Compaction；`RegistryMetaPaths` 5 常量 + 6 helper；3 handler。
- **P10/P11/P12**：`/orm/{row,column,dbs,data,sync}` 命名与 helper（本阶段不触碰）。
- **P7**：`P2PCommand` DB 服务类别（31201-31203 / 31301-31303 已占用，本阶段不新增命令号）。

### 5.3 假设

1. peerId 全局唯一（P7 保证）；3 节点 MVP 的 peerId 由测试/调用方显式给出（如 11/12/13）。
2. in-JVM `PEERS` 静态对等体模型足以验证共识逻辑；真实网络 RPC 属 Non-Goal。
3. 3 节点场景单 JVM 同构，无跨机时钟/网络抖动，故选举收敛阈值取宽松 3000ms。

---

## 6. 验收标准（Acceptance Criteria — 仅 rule 或 rubric，无其他类型）

### 6.1 Rule AC

| 编号 | Type | 观测条件（客观 binary pass/fail） | 证据来源 |
|------|------|------------------------------------------|----------|
| R1 | rule | props 3 节点 roster：`create(root, REGISTRY, {members="1,2,3", local="2"})` → `getLocalPeerId()==2`；`peerState(1)!=null && peerState(3)!=null && peerState(2)==null`（本地自过滤） | `P57_T1_RosterFactoryTest#t01_members_csv_3node` |
| R2 | rule | 缺省向后兼容：`create(root,REGISTRY)` / `create(root,REGISTRY,null)` / `create(root,REGISTRY,{})` 三者产物 `getLocalPeerId()==1` 且 `peers` 键集 = {2}，三方全等 | `P57_T1_RosterFactoryTest#t02_default_backward_compat` |
| R3 | rule | 非法输入 IAE：`members="1,x,3"` / `members="1,,3"` / `members="2,2"`(重复) / `local="zz"` → `IllegalArgumentException`（message 含键名） | `P57_T1_RosterFactoryTest#t03_invalid_iae` |
| R4 | rule | quorum 单一出处泛化：`quorumOf(1)=1, quorumOf(2)=2, quorumOf(3)=2, quorumOf(4)=3, quorumOf(5)=3` | `P57_T2_QuorumTest#t01_quorum_scale` |
| R5 | rule | 3 节点单 Leader：11/12/13 全注册 → 4s 内恰 1 LEADER；其 `currentTerm` 单调；余 2 节点 FOLLOWER 且 `currentLeaderId==leader.peerId`；无 2 LEADER 并存 | `P57_T3_ThreeNodeElectionTest#t01_single_leader` |
| R6 | rule | 多数选举：仅 2 节点在线（第 3 未注册 PEERS）→ 仍选出 LEADER（2/3 ≥ quorum 2） | `P57_T3_ThreeNodeElectionTest#t02_majority_quorum` |
| R7 | rule | 3 节点多数提交：Leader 写 100 条 → `commitIndex ≥ 100`（含 noop）且 ≥1 Follower `matchIndex ≥ 100` | `P57_T4_CommitMajorityTest#t01_commit_2of3` |
| R8 | rule | 1 节点宕机仍提交：停 1 Follower（`stop()`+摘除 PEERS）→ Leader 续写 100 条全部 `awaitApplied` 成功，commitIndex 前进 | `P57_T4_CommitMajorityTest#t02_one_down_still_commits` |
| R9 | rule | 2 节点宕机不可提交：仅 self 在线 → 写后 `awaitApplied(idx,1500ms)==false`，commitIndex 不前进 | `P57_T4_CommitMajorityTest#t03_two_down_no_commit` |
| R10 | rule | 3 节点 InstallSnapshot fanout：Leader `forceCompact()` 后 2 落后 Follower 补齐，两个 applied store 与 Leader `sha1` 全等 | `P57_T5_SnapshotFanoutTest#t01_two_follower_fanout` |
| R11 | rule | 无 split-brain：隔离 1 Follower（PEERS 摘除）→ 其不能当选（拿不到多数）；复归后重新跟随，term 差 ≤2 | `P57_T5_SnapshotFanoutTest#t02_minority_no_leader` |
| R12 | rule | 向后兼容回归 + Gatekeeper：P15 54 tests 0F0E；`raft.mode=off` → `create` 返回 null；Gatekeeper ≥25 assertions 全 PASS | `P15_Raft*Test` 回归 + `P57_T7_GatekeeperTest` |

### 6.2 Rubric AC（≥1 分 = 通过；2 分 = 满分）

| 编号 | Type | 维度（Evaluative Scale） | 数值尺度 + 锚点 | Pass Threshold | 证据来源 |
|------|------|--------------------------|-----------------|----------------|----------|
| U1 | rubric | **容错能力**（3 节点故障容忍度）：1 节点宕机是否 100% 可提交 + 2 节点宕机是否 100% 拒绝 | 0=1 节点宕机即不可提交（等同 2 节点）；1=1 节点宕机可提交但 2 节点宕机行为不确定；**2=1 节点宕机 100% 提交 且 2 节点宕机 100% 拒绝（counter-terms 双实锤 quorum 正确）** | ≥1 分 | `P57_T4_CommitMajorityTest` 双场景计数 |
| U2 | rubric | **性能退化比**：ratio = 3 节点写提交 P99 mean / P15 2 节点写提交 P99 mean | 0=ratio>1.20；1=1.05<ratio≤1.20；**2=ratio≤1.05（+5% 内，NFR-P2）** | ≥1 分 | `P57_T8_NFRTest` 5 轮 P99 打印 + ratio 行 |

---

> **[SP1 红线]**：`spec.md` + `tasks.md` **必须拿到 User 显式批准**（NotifyUser 返回「已批准」/「User has approved...」）前，T1~T8 任一步不得写任何业务代码；违者 Workflow Fidelity Rubric = 0。
