# P15 Spec — OQ2 升级：2 节点 Raft 共识层（Leader Election + AppendEntry + Log Compaction + 2PC 强一致 ≤100ms RTT）

> 会话 ID：P14 OQ3 ACID 闭环后，按 roadmap L450 P3 立即启动 P15 OQ2 升级；周期：2026-09-27 → 2026-11-10（约 6 周，提前 1 周 MVP 2 节点 HA 主备切换）

---

## 1. 问题与用户

### 1.1 现状问题（P12 OQ2 MVP 交付后遗留 4 个生产级缺口）

P12 OQ2 MVP 已在 [AGENTS.md L328-L333](file:///i:/2026/code/p2p-net-storage/AGENTS.md#L328-L333) **2026-09-22 VERIFIED CLOSED**，通过：① CAS side-version sibling 高 v 赢幂等 apply；② `/orm/sync` 子树心跳追踪；③ REGISTRY_SUBTREE_LIST/PUT_BATCH 全量冷启动 4 子树 64KB 分块；④ c10 25 assertions 钉死 NG-2 零改；T6 JV 合跑 192 tests 0F0E；U1 replicate P99 ratio=0.8252 ≤ 1.50；U2 cold-start 全量对齐 ratio=0.0732 ≤0.90。共 14 AC 全闭。

但 roadmap L450 OQ2 **P3（下一阶段立即启动）要求** Raft/CRDT 2 节点 ≤100ms RTT 最终一致，**P12 MVP 的「CAS side-version 高 v 赢最终一致」无法覆盖 4 个生产级场景**：

1. **严格顺序写缺口（OQ3 ACID 2PC 强一致依赖）**：P12 无全局 Leader，两节点对称写同 key 时「高 version + logicalTs(serverId|seq)」赢，**同一 serverId 内部 写 seq 单调但跨节点无法保证 write order 线性一致**，OQ3 2PC Prepare→Commit 需要跨节点按「txId 全局顺序 commit」，P12 MVP 无法保证 T6 JV OQ3 10 tx 并发 0 环形死锁的跨节点一致性，强一致事务会死锁 20%+（参考 P14 T5 t03 deadlock baseline）。
2. **Leader Election + 自动主备切换（HA）缺口**：P12 心跳探活只有「3 跳 1500ms 未响应 → FOLLOWER→IDLE」，**无 Leader election / 选票 / term / 任期号 / 任期比较机制**，节点重启后无法在 1s 内自动选主，主备切换需要人工介入（不符合生产 HA ≤1s 切换目标）。
3. **Raft Log Compaction + 崩溃恢复缺口**：P12 binlog 跨节点回放是「DbNodeReplicateFrame 裸帧」，无 Log Index / CommitIndex / LastApplied / Snapshot 机制，节点 crash 重启 + 冷启动 + 网络分区 10 分钟 场景下 **Registry 子树 ≥0.1% 概率出现 key 丢失 或 stale data**（参考 P0 bulk-array bug 风险 #2/3/7，无 Log Compaction 时日志 ≥ 10GB 后回放耗 10 分钟）。
4. **2 PC 协调者基础（OQ3 OQ4 依赖）缺口**：P12 无 Coordinator 角色、无 tx_state 两阶段、无 Prepare → COMMIT/ABORT 状态机驱动，OQ3 ACID 跨节点 2PC（Prepare→Commit 写本地 tx + Raft 同步 + CommitIndex 递增）需要 Raft AppendEntry 可靠通道，P12 MVP 单 key CAS 无法承载多叶子原子提交（2PC 需要「Prepare 全成功才 Commit，任一失败全局 Rollback」的严格有序 Raft Log）。

### 1.2 目标用户与收益链路（严格对应 roadmap L450 P3 OQ-2 交付物）

| 用户/阶段 | 直接收益 | 最终打通（与 roadmap 对齐） |
|-----------|----------|-------------------------------|
| HA 运维（生产 2 节点主备） | A 节点宕机 ≤1s 内 B 自动当选 Leader；旧 Leader 恢复后自动 Follower；写 ≤ 100ms RTT 强一致（线性一致，无 split-brain overwrite） | **OQ-3 2PC ACID 跨节点事务基础**：Prepare 写本地 Registry + Raft AppendEntry（Log Index 单调）→ Commit 等 CommitIndex ≥ txId → 跨节点 0 lost data |
| DsDatabaseClusterClient 分片路由调用方 | rowId 高 22 位 serverId → 路由到当前 Leader；Follower 只读（read-your-writes 可见性：从 Leader 读） | 后续 OQ-5 4+ 节点分片：Raft 单 Group 2 node → Multi-Raft Shard Group，每 shard 独立 Leader |
| 冷启动 + 崩溃恢复运维 | 节点 crash 重启 → 自动 Raft 选主 → Log replay ≥ CommitIndex → Snapshot 每 1000 条 log 自动 compact；**冷启动 ≤ 12s（P12 MVP baseline 60s 冷启动再优化 5×）** | 后续 OQ-5 线上烟测：Windows→Linux，Raft 选主 + AppendEntry + Log Compact 跨 OS 一致 |
| OQ3/OQ4 后续专项 | Raft Log Index = 全局顺序 write order → 2PC Prepare/Commit 有序；Follower 状态机 = `/orm/raft` 子树 + P11 `/orm/data` 三棵子树 | **TPC-C NewOrder 跨节点 2PC baseline 兼容**（P14 单节点 0 deadlock → 跨节点保持 0 deadlock） |

---

## 2. 目标（Goals）与非目标（Non-Goals）

### 2.1 Goals（6 周交付，MVP 4 周 2 节点主备）

**G1：Raft 共识核心（Leader Election + AppendEntry + Term 任期 + Election Timeout + PreVote）**
- 新增 `/orm/raft` 子树（P12 `/orm/sync` 子树零字符改）：`/orm/raft/state`（currentTerm / votedFor / commitIndex / lastApplied）、`/orm/raft/log/<logIndex 16hex>`（term 8B + cmdType 1B + registryPathLen 2B + registryPath + payloadLen 4B + payload）。
- Raft 角色状态机：LEADER → FOLLOWER → CANDIDATE；Election Timeout = 150ms~300ms 随机抖动（100ms RTT 环境下 split-vote 概率 ≤ 0.1%）；PreVote 机制（candidate term 未超过 peer currentTerm，则 peer 拒绝 vote，避免 network partition 后 term 无限膨胀）。
- AppendEntries RPC：heartbeat 空 entries 每 50ms 1 次（保持 Leader 权威）；entries 批量 64KB/批（复用 P12 REGISTRY_SUBTREE_PUT_BATCH 64KB 分块）；quorum = 2 节点：Leader + Follower 都 applied ≥ commitIndex → commit。
- 所有新增 `/orm/raft` 路径由 **RegistryMetaPaths 新增 typed helper** 唯一出处，业务代码零硬编码（c11 Raft Gatekeeper C 组延伸：grep `"/orm/raft"` 仅 RegistryMetaPaths 常量声明 1 处命中）。

**G2：Log State Machine = `/orm/data` 三棵子树 + `/orm/{row,column,dbs}` 7 类元**
- Raft Log 4 类 cmdType：CMD_PUT_REGISTRY(0x01) / CMD_REMOVE_REGISTRY(0x02) / CMD_META_SAVE(0x03) / CMD_NOOP(0x00 Leader heartbeart No-op)。
- State Machine apply：`CMD_PUT_REGISTRY` → 直接走 P11 `AbstractRegistryDataBackend.writeLeafBytes(registryPath, payload, null)`（bucket-first-then-registry）；`CMD_REMOVE_REGISTRY` → P11 removeLeafBytes；**`CMD_META_SAVE` → P10 AbstractRegistryBackend.writeLeafString/writeLeafLong（7 类元路径）**。
- 与 P12 MVP 向后兼容：如果收到 P12 magic=0x52 frame（无 logIndex），走旧 CAS side-version sibling 分支，P15 Raft 未启动场景下 100% = P12 MVP 行为（c11 E3 Backward Compat 断言钉死）。

**G3：Snapshot + Log Compaction（崩溃恢复 ≤ 12s，日志 ≥ 10GB 自动 compact）**
- Snapshot 触发：每 1000 条 log 或每 10 分钟自动触发，state = lastIncludedIndex + lastIncludedTerm + snapshotBytes（= SHA256(所有 Registry 4 子树 `/orm/row + /orm/column + /orm/dbs + /orm/data` leaf bytes 顺序遍历，1MB 连续写）。
- InstallSnapshot RPC：Follower log 落后于 Leader lastIncludedIndex（缺 segment 已被 compact）→ Leader 发 InstallSnapshot（64KB/批 gzip）→ Follower 清空本地 `/orm/raft/log` 子树 + apply snapshot。
- 崩溃恢复：节点 restart → 读 `/orm/raft/state`（currentTerm + votedFor + commitIndex）→ apply snapshot（若有）→ replay 所有 log[lastIncludedIndex+1..lastLogIndex] → 自动 FOLLOWER 等待 heartbeat；超时 300ms → CANDIDATE → PreVote → 选举。

**G4：正确性门槛（≥ 78 tests 0F0E，c11 Raft Gatekeeper 25 assertions）**
- 原 P10 38 + P11 64 + P12 192 去重后 = 128 tests 基线 0F0E 不破坏（G3 不能动 P10/P11/P12 既有写路径）。
- 新增 P15 16 专项：Leader Election 3 / AppendEntry 4 / Split-Vote 1 / Network Partition 2 / Log Compaction + InstallSnapshot 2 / Crash Recovery 2 / Backward Compat with P12 MVP 2。
- 新增 c11 NG-3 Raft Gatekeeper ≥25 assertions：钉死 6 facade 0 改 + P12 `/orm/sync` 命名段 0 改 + `/orm/raft` 命名段仅 RegistryMetaPaths 1 处声明 + RaftState machine 3 角色状态机存在 + AppendEntries RPC 存在 + InstallSnapshot RPC 存在。

**G5：性能门槛（100ms RTT 网络 ≤100ms 写延迟 P99）**
- **强一致写（Leader 写 → replicate Follower → CommitIndex >= logIndex → applied）P99**：2 Node 100ms RTT（Thread.sleep 模拟延迟注入），100 行小对象（行 ≤128B / 列 ≤64B）PUT P99 ≤ 1.20 × P12 MVP 最终一致 P99 baseline（P12 U1=0.8252 ≤1.50 → 强一致 预期 ≤ 0.99 即强一致只比最终一致慢 20%）。
- **Crash Recovery 重启恢复到 FOLLOWER 可用**：3 次重启均值 ≤ 12s（P12 MVP baseline 60s binlog 全量重放 → 5× 快）。
- **Split Vote Resolve 时间**：3 节点？ No，2 节点 split vote timeout 300ms，PreVote 机制下 resolve ≤ 1 轮，P(分裂) ≤ 0.1%，99th 分裂解决时间 ≤ 500ms。

### 2.2 Non-Goals（本阶段严格不做，越界 = FAIL）

- ❌ **3+ 节点 Raft Group / Multi-Raft Shard / Membership Change 成员变更**：scope 严格 2 节点（HA 主备，quorum=2 Leader+Follower both applied ≥ commitIndex）；≥3 节点 等 OQ5 Linux 复核后另开 P17 OQ6（预计 12 周 roadmap L451 持续迭代）。
- ❌ **完整 Raft 规范：Log Checkpoint in memory（snapshot on disk 足够）/ ReadIndex Linearizable Read（Follower 只读走 Leader 转发即可，本阶段 Follower 允许 stale read ≤ heartbeat 50ms）/ PreVote 之外的 CheckQuorum / Lease Read 暂不做**。
- ❌ **OQ3 完整跨节点 2PC（Prepare→Commit→Abort Rollback + tx timeout undo + deadlock detection）**：Raft AppendEntry 通道 + tx_state 段 + Coordinator 脚手架（/orm/raft/tx/<txId32hex>/state = PREPARED/COMMITTED/ABORTED） 已经到位；真实跨节点 2PC Deadlock + Undo + Retry 10 tx 并发 0 deadlock 另开 P16 OQ3 升级版（预计 8 周，等 Raft 通道先闭环）。
- ❌ **改 P10/P11/P12 任何既有命名段或公开 facade 签名**：6 类 facade（DsDatabaseLocal/ColumnarStore/TableMetaStore/ColumnRegistry/RowIdSequenceStore/EqIndexMetaStore）公开方法 **0 字符改**；P10 7 元 + P11 3 数据 + P12 2 sync 路径 **0 字符改**（c11 Gatekeeper B/C 钉死）。
- ❌ **新增 Maven 依赖 / 新增对外暴露 RPC Handler / 改 p2p-ws-sdk / p2p-cache / p2p-sync / examples/IM**：Raft RPC 走 P7 P2PCommand（复用 DB_NODE_REPLICATE_FRAME 扩展 cmdType = RAFT_APPEND_ENTRIES = 0x53 / RAFT_VOTE = 0x54 / RAFT_INSTALL_SNAPSHOT = 0x55）；不新命令号走 24313-24335 区间外的（P7 EXTATTR 已占用 24313-24335，再用 = 冲突 → 24336 24337 24338 3 新号？ → 重新调研：本项 non-goal = **零新 Maven，零新对外 SDK handler 但允许 P2PCommand 3 新 command ID（内部用，不对外暴露 SDK 文档）**。
- ❌ **CRDT ORSet（P2P 无 Leader 多写）**：roadmap L450 要求 Raft/CRDT 二选一，本项目 scope 选 Raft（强一致，为 OQ3 2PC 打基础），CRDT 多写无 Leader 不适合 OQ3 2PC Prepare→Commit 严格顺序 → 拒绝 CRDT 路线（如果强一致需求 CRDT 则未来 P18 OQ6）。

---

## 3. 功能需求（Functional Requirements）

### 3.1 FR1：RegistryMetaPaths 扩 `/orm/raft` 子树 5 常量 + 6 typed helper（路径唯一出处，零外泄）
1. 新增常量（零重叠 P10 7 / P11 3 / P12 4 = 全不相交）：
   - `RAFT_ROOT = "/orm/raft"`
   - `RAFT_STATE_LEAF = RAFT_ROOT + "/state"`（currentTerm 8B + votedFor 8B + commitIndex 8B + lastApplied 8B = 32B packed）
   - `RAFT_LOG_ROOT = RAFT_ROOT + "/log"`（logIndex 16hex 对齐 rowIdHex 风格）
   - `RAFT_SNAPSHOT_ROOT = RAFT_ROOT + "/snapshot"`（lastIncludedIndex 8B + lastIncludedTerm 8B + snapshotLen 4B + bytes）
   - `RAFT_TX_ROOT = RAFT_ROOT + "/tx"`（OQ3 扩展：/orm/raft/tx/<txId32hex>/state PREPARED/COMMITTED/ABORTED 1B）
2. 新增 6 `public static` typed helper：`raftLogLeaf(logIndex)`、`raftSnapshotLeaf(index, term)`、`raftTxState(txId)`、`raftPeerVoteTerm(peerId)`、`raftPeerNextIndex(peerId)`、`raftPeerMatchIndex(peerId)`（peerId 16hex，Leader 侧对每个 Follower 维护 nextIndex/matchIndex pair）。
3. 零外泄：grep `"/orm/raft"` src/main/java → **仅 RegistryMetaPaths.java 常量声明行 1 处命中**（c11 Gatekeeper C3 实锤，Java NIO walk 跨平台非系统命令 grep）。

### 3.2 FR2：Raft 共识核心（RaftState 状态机 3 角色 + 3 RPC）
1. **RaftState（package-private 内嵌类 RaftServer.State）**：
   - 字段：volatile Role role（LEADER/FOLLOWER/CANDIDATE）；long currentTerm（单调）；long votedFor（peerId 0=未投）；long commitIndex（≤ lastApplied ≤ lastLogIndex）；long lastApplied；long[] logs（term[] + cmd[]，环形 buffer，max 10000 entries，超了自动 compact）。
   - Election Timeout = `ThreadLocalRandom.current().nextInt(150, 300)` ms（抖动防 split-vote）；Leader heartbeat = 50ms（空 AppendEntries）。
   - PreVote：Candidate 转 CANDIDATE 前先发 PreVote RPC，peer 本地 lastLogTerm/index >= candidate lastLog → grant preVote；拿到 preVote quorum 才 term++ 发正式 Vote（避免 partition term 无限膨胀）。
2. **3 RPC（P2PCommand 新 3 命令号 24336/24337/24338，内部 handler 不暴露 SDK 文档）**：
   - `RAFT_REQUEST_VOTE(24336)`：args=term+candidateId+lastLogIndex+lastLogTerm；result=term+voteGranted（voteGranted = term>=currentTerm && votedFor==0 && candidateLastLog>=localLastLog）。
   - `RAFT_APPEND_ENTRIES(24337)`：args=term+leaderId+prevLogIndex+prevLogTerm+entries[]+leaderCommit；result=term+success+matchIndex（success=prevLogIndex/prevLogTerm match local log）。
   - `RAFT_INSTALL_SNAPSHOT(24338)`：args=term+leaderId+lastIncludedIndex+lastIncludedTerm+offset+data[]+done；result=term+success（Follower 清空 log 子树，按 offset 拼 snapshot）。
3. **Quorum 规则（严格 2 节点）**：CommitIndex = min(leader.lastNewLogIndex, min(leader.matchIndex[leader], matchIndex[follower]))（即 Leader 自己 applied + Follower applied = 2/2，无 split-brain，保证强一致）。

### 3.3 FR3：State Machine apply（4 cmdType，100% 走 P10/P11 已交付 helper，零自造读写）
- CMD_NOOP(0x00)：空 apply，Leader 刚当选发 1 条 Noop log，强制 commitIndex 前进。
- CMD_PUT_REGISTRY(0x01)：dataPath = parse(registryPath)，payload → P11 `AbstractRegistryDataBackend.writeLeafBytes(registryPath, payload, consumer=byte[] -> bucket.put)`（P11 bucket-first-then-registry 双写语义保持）。
- CMD_REMOVE_REGISTRY(0x02) → P11 removeLeafBytes（双删任一 true=true）。
- CMD_META_SAVE(0x03)：metaPath = parse，payloadBytes → P10 `AbstractRegistryBackend.writeLeafBytes(metaPath, metaBytes, null)`（7 类元路径 TableMeta/ColumnRegistry/RowIdSeq/EqIndexMeta/DDL 等）。

### 3.4 FR4：Hook 接入（公开签名 0 改，NG-2 硬约束）
- **DsDatabaseLocal / ColumnarStore / 4 MetaStore（TableMetaStore/ColumnRegistry/RowIdSeq/EqIndexMetaStore）**：P12 MVP replicateKey hook 成功后追加「如果 Raft enabled 且我是 Leader，则 RaftServer.append(CMD_PUT_REGISTRY, registryPath, payloadBytes, quorumCommit 阻塞等 applied ≥ commitIndex 才返回 success）」；如果 Follower，则 转发写请求给 Leader（Leader IP:Port 从 `/orm/raft/state` votedFor / peerNextIndex 映射得到），本地不写，避免 split-brain。
- **P12 MVP Backward Compat 模式**：`-Dp2p.orm.raft.mode=off`（默认 on，与 P12 sync.mode 独立） → RaftServer 构造器直接 return null；所有 hook 短路 100% = P12 MVP CAS side-version 行为，P12 192 tests 0F0E 不破坏（c11 Gatekeeper E3 断言）。

### 3.5 FR5：Log Compaction + Crash Recovery（G3 崩溃恢复 ≤12s）
- **Compact 触发**：lastLogIndex - snapshot.lastIncludedIndex >= 1000 || 上次 snapshot 超过 10 min → 顺序遍历 4 子树 `/orm/row` → `/orm/column` → `/orm/dbs` → `/orm/data`，所有 leaf key bytes 按字典序 sha1 写 snapshotBytes（1MB/段，LZ4 frame 压缩，与 P7 Binlog LZ4 框架复用，零新依赖）；snapshot 写完后 `/orm/raft/log/<index>` ≤ lastIncludedIndex 的全部 remove。
- **InstallSnapshot**：Leader lastIncludedIndex > Follower.nextIndex - 1 → 直接发 InstallSnapshot（64KB/批），Follower 写完 snapshot + 清空本地 log + lastApplied = lastIncludedIndex + commitIndex = max(local,leaderCommit)。
- **Crash Restart**：启动后读 RAFT_STATE_LEAF 32B packed → 读最新 snapshot（若有）apply 到 memory → replay log(snapshotIndex+1..lastLogIndex) apply state machine → 角色 = FOLLOWER，等 heartbeat；超过 300ms 无 heartbeat → PreVote → Candidate → Vote。

---

## 4. 非功能需求（Non-Functional Requirements）

### 4.1 正确性（硬门槛，不达标 Fail 不允许进性能）
| 编号 | 描述 | 阈值 |
|------|------|------|
| NFR-C1 | P10 38 + P11 64 + P12 192 去重 ≥ 128 tests 基线 0F0E（P10/P11/P12 regression 零破坏） | 0F0E，硬门槛 |
| NFR-C2 | P15 16 专项 tests 全部 PASS（Leader Election 3 / AppendEntry 4 / Split-Vote 1 / Partition 2 / LogCompact+Snapshot 2 / Crash 2 / BackwardCompat 2） | 16/16 0F0E，硬门槛 |
| NFR-C3 | c11 NG-3 Raft Gatekeeper ≥25 assertions 全 PASS：A修饰符6 / B facade 方法数6 / C RegistryMetaPaths 路径5 / D Raft RPC+cmdType 5 / E StateMachine 方法数3 + 构造器2 × 2 BackwardCompat） | 25/25，硬门槛 |

### 4.2 性能（2 节点 100ms RTT 场景下软门槛，偏差超 5% 需给解释）
| 编号 | 维度 | 目标阈值（5 轮均值） | 环境约束 |
|------|------|---------------------|----------|
| NFR-P1 | Row put 小对象（128B）强一致写 P99 | Raft Strong P99 ≤ P12 MVP Final P99 × 1.20 | 2 JVM 对端；localhost 100ms RTT 延迟注入（Thread.sleep 2 次 50ms = 100ms）；warmup=500 N=2000 5 轮 |
| NFR-P2 | Column put 小对象（64B）强一致写 P99 | Raft Strong P99 ≤ P12 MVP Final P99 × 1.20 | 同上 |
| NFR-P3 | Crash Restart Recovery Time（进程 kill + new DsDatabaseLocal + Raft recover 到 FOLLOWER 接受读） | 3 次重启均值 ≤ 12s（P12 MVP binlog 60s baseline → 5× 快） | 写满 10000 logs（1 万行 128B = ~1.28MB log）后 kill -9；独立 JVM 3 轮 |
| NFR-P4 | Split-Vote Resolve 99th 时间 | ≤ 500ms（PreVote 机制下 split-vote ≤ 0.1%） | 模拟网络乱序：vote 丢包率 20%；100 次选举 99th ≤ 500ms |

### 4.3 可维护性 + 可观测性
| 编号 | 描述 |
|------|------|
| NFR-M1 | `/orm/raft` 命名段 zero leak（NFR-C3 C3 实锤） |
| NFR-M2 | Raft State 变化日志：LEADER↔CANDIDATE↔FOLLOWER 转换必须 INFO 含 term、reason、peerId、lastLogIndex/term；apply log 失败（state machine 抛 IOE）必须 ERROR 含 logIndex、cmdType、registryPath、rootCause |
| NFR-M3 | `-Dp2p.orm.raft.mode=off` → RaftServer=null，所有 hook 短路，行为 100%=P12 MVP；c11 Gatekeeper 在此模式下必须同样 25/25 assertions PASS（P12 MVP 向后兼容零 regress） |
| NFR-M4 | 零跨子项目影响：p2p-ws-sdk-java/ts/dart/c/python / p2p-cache / p2p-sync / examples/IM 5 子项目 **零字符改**（本专项只动 p2p-db 包内 registry.meta.raft.* 新包 + 既有 6 facade hook 只增 package-private 条件分支，不碰公开方法） |

---

## 5. 约束与假设

### 5.1 硬约束（突破 = Spec 失败，Implement 阶段必须 revert）
1. **NG-2 6 facade 公开签名 0 字符改**：DsDatabaseLocal/ColumnarStore/TableMetaStore/ColumnRegistry/RowIdSequenceStore/EqIndexMetaStore 所有 public 方法签名 byte-for-byte 不变（c11 Gatekeeper B6 反射实锤，方法数 ∈ baseline ±4）。
2. **P10 7 元 + P11 3 数据 + P12 2 sync 路径 0 字符改**：`/orm/row/** /orm/column/** /orm/dbs/** /orm/data/** /orm/sync/**` 前缀不许改、不许动 helper 返回值（c11 C4 断言）。
3. **0 新增 Maven 依赖（pom.xml diff 空）**：LZ4 Frame 复用 P7 binlog 已有 LZ4FrameOutputStream；Thread 用 Executors.newSingleThreadScheduledExecutor（JDK 自带）；Raft Log pack 用 ByteBuffer（JDK）；不引入 Raft library（sofajraft/ratis 等 外部库 = NG-3 FAIL）。
4. **向后兼容 P12 MVP**：raft.mode=off → 行为 100% = P12 MVP，old node（P12 build 不含 RaftServer 代码）继续能与 new node（raft=off）互通（magic=0x52 旧帧走旧分支，c11 E3 断言实锤）。

### 5.2 依赖项（已 VERIFIED CLOSED 不做任何变更）
- P10：MetaMode 三态 / RegistryMetaPaths 命名 / AbstractRegistryBackend striped CAS / DsRegistryInstance 单例 / SHA256 manifest 可逆迁移。
- P11：/orm/data 三棵子树 / AbstractRegistryDataBackend 5 helper（read/write/remove/exist/atomicModifyBytes）/ RowDataBackend+Column+Group 3 Backend 内嵌类 / c09 Gatekeeper 25 assertions。
- P12：/orm/sync 子树 4 常量 + 5 helper / DsRegistrySyncFrame magic=0x52 向后兼容 / 2 通道（Frame+Subtree）/ c10 25 assertions / Split-brain 高 v 赢 / heartbeat 500ms。
- P7：DsNodeRouter / DsServerDirectory / P2PCommand DB_NODE_REPLICATE_FRAME 路由 / DsClusterMonitor / DsIdLayout serverId 22bit 全局唯一。

### 5.3 假设
1. peerId = DsServerDirectory.localServerIds[0]（P7 已保证 22-bit 全局唯一，节点数 ≤ 2，>2 自动 raft.mode=off + warn）。
2. 2 节点平均 RTT ≤ 100ms，丢包率 ≤ 1%（运维保证；超出后 Raft retry AppendEntries，数据强一致但延迟增大 ≤ 1s 重试 10 次）。
3. Local disk fsync 延迟 ≤ 10ms（/orm/raft/state pack 32B + log leaf 写入 DsRegistry → bucket 走 P11 bucket-first，文件系统 flush ≤ 10ms，Windows 下 Windows CFS 偶尔抖 100ms → retry 3 次）。

---

## 6. 验收标准（Acceptance Criteria — 仅 rule 或 rubric，无其他类型）

### 6.1 Rule AC（15 条，满足 = 正确性基线 PASS）

| 编号 | Type | 观测条件（可客观验证 binary pass/fail） | 证据来源（test class + 命令 + 锚点） |
|------|------|------------------------------------------|--------------------------------------|
| R1 | rule | `/orm/raft` 5 常量仅 RegistryMetaPaths.java 常量声明 1 处 grep 命中；raftLogLeaf(1000L) 返回 contains `"log/00000000000003e8"`（1000=0x3e8 16hex） | `P15_RaftKeySpaceTest#ac_r1_raft_paths` + grep `"/orm/raft"` src/main/java |
| R2 | rule | Raft 状态机 3 角色转换 FOLLOWER → CANDIDATE（election timeout 300ms no heartbeat）→ LEADER（拿到 Follower voteGranted=true）→ FOLLOWER（收到 term≥currentTerm Leader heartbeat）完整闭环，状态机正确，currentTerm 单调不回退 | `P15_RaftElectionTest#t01_3_roles_cycle`，断言 currentTerm 每个 transition ≥ 前值 |
| R3 | rule | PreVote 防 term 膨胀：network partition 5s（Leader 与 Follower 不通），Follower 自动转 CANDIDATE 发 PreVote，peer（Leader side）拒绝 PreVote（因为 peer LastLogTerm/Index < candidate lastLog？ No，反之 Follower 自己 lastLog < Leader 恢复后的 lastLog → PreVote 不 grant，term 不 ++）；partition 恢复后 term 差值 ≤ 2（不无限膨胀） | `P15_RaftPartitionTest#t01_term_no_infinite_inflate` |
| R4 | rule | AppendEntries 4 子专项：① prevLogIndex/prevLogTerm match → success=true + matchIndex 前进；② prevLog 不 match → success=false + nextIndex 回退；③ entries[] 批量 64KB/批 ≥100 entries 全部 apply state machine；④ heartbeat 空 entries 保持 Leader 权威，1s 内无任何 write 仍 20 heartbeats（≥15 算 PASS） | `P15_RaftAppendTest` 4 TR |
| R5 | rule | 强一致写 quorum=2/2 applied：Leader 写 CMD_PUT_REGISTRY(rowId=42, name=foo) → 阻塞等 commitIndex ≥ 该 logIndex → Follower 本地 Registry dataRowPayload 42 字节与 Leader 完全一致（Arrays.equals）；网络 partition 期间 write 阻塞超时后抛 IOException（不写 stale data） | `P15_RaftStrongConsistencyTest#t01_quorum_2of2` |
| R6 | rule | State machine apply 四 cmdType：① CMD_PUT_REGISTRY → P11 writeLeafBytes 执行 + bucket+registry 双写 byte-for-byte 一致；② CMD_REMOVE_REGISTRY → P11 removeLeafBytes 双删；③ CMD_META_SAVE → P10 TableMetaStore saveMeta bytes 对端一致；④ CMD_NOOP → 0 state change，lastApplied 前进 | `P15_RaftStateMachineTest` 4 TR |
| R7 | rule | Log Compaction：写 1500 logs（超过 compact 阈值 1000）→ `/orm/raft/log` leaf 数 ≤ 500（old logs removed）；Snapshot.lastIncludedIndex ≥ 1000；InstallSnapshot 从 Leader 发 64KB/批 → Follower snapshot+log 清空+apply 后 data 与 Leader byte-for-byte 一致（sha1 total match） | `P15_RaftSnapshotTest#t01_compact_and_install` |
| R8 | rule | Crash Recovery：写满 10000 logs → kill -9 process → 独立 new DsDatabaseLocal + RaftServer 启动 → 恢复到 FOLLOWER 状态（接受 heartbeat）→ 本地 lastApplied ≥ 原 commitIndex；3 次重启均值时间 ≤ 12s（NFR-P3 硬门槛） | `P15_RaftCrashRecoveryTest` @Fork(3) 3 轮 |
| R9 | rule | Backward Compat with P12 MVP：① raft.mode=off → RaftServer == null；reflect capture 断言所有 hook null；P12 192 tests（P10/P11/P12 合跑）≥128 tests 0F0E；② 收到 P12 magic=0x52 frame（无 Raft logIndex）→ applyRegistryFrame P12 旧 CAS side-version 分支执行，Follower 本地 bytes 与 P12 MVP 结果一致（byte-for-byte same） | `P15_RaftBackwardCompatTest` 2 TR |
| R10 | rule | 写 Leader / Follower 转发：Follower 收到 client 写请求 → 拒绝（抛 IOException：NotLeader，含 Leader 地址）或 透明转发到 Leader（实现转发 = 加分，但必须 0 facade 改 → 内部包装 `RaftLeaderProxy` 调用）；禁止 Follower 本地直接写 registry（否则 split-brain write = 0F0E FAIL） | `P15_RaftLeaderFenceTest#t01_follower_no_writes` |
| R11 | rule | Split-Vote 99th：100 次选举（Election Timeout 收紧范围 150-170 抖动弱提高 split 触发率），99th election resolve time ≤ 500ms；PreVote 机制下分裂次数 ≤ 1/100（≤1 次算 PASS） | `P15_RaftSplitVoteTest#t01_99th_500ms` |
| R12 | rule | Network Partition 2 场景：① Partition Leader ↔ Follower 3s → Follower 转 Candidate 拿不到 quorum → election timeout 继续 retry；Partition 恢复后 2 心跳（≤100ms）内 rejoin Leader，currentTerm 差 ≤ 2，0 data loss；② Partition 期间 old Leader 写 = 阻塞超时抛 IOE，不写本地（不 split brain） | `P15_RaftPartitionTest` 2 TR（t02 partition write fence） |
| R13 | rule | 7 类元路径 replicate：TableMeta.saveMeta（新增 16 columns）/ ColumnRegistry.markDeleted（colId=5 删除）/ RowIdSeq.allocate（nextRowId=2000）/ EqIndexMeta.save（新 index a_idx）/ DDL OnlineDDL start → Raft CMD_META_SAVE 跨节点 apply 一致，两端 TableMetaStore/ColumnRegistry bytes 完全 sha1 相等 | `P15_RaftMetaReplicateTest` 4 TR |
| R14 | rule | c11 NG-3 Raft Gatekeeper ≥25 assertions 5 防线：A6（修饰符：RaftState private final + RaftServer 内嵌 static final + 6 facade 新增 hook 是 package-private）；B6（6 facade 方法数 baseline ±4）；C5（RAFT_ROOT 值 + String const≥20 + 16hex + txSeg32hex + zero leak）；D5（3 RPC 24336-24338 command handler 存在 + 4 cmdType 存在 + voteGranted result field 存在）；E3（raft=off Raft==null + old magic=0x52 frame 走 P12 分支） | `P15_RaftGatekeeperTest c11_ng3_raft_gatekeeper`（≥25 assertions 25/25 PASS） |
| R15 | rule | JV 合跑 ≥ 128 tests（P10 38 + P11 64 + P12 ≥26 = 128） 0F0E，P10/P11/P12/P15 四项分项各 tests pass = 类内 @Test 数（无 FAIL） | 见 T6（tasks.md）合跑命令 |

### 6.2 Rubric AC（2 条，≥ 2/2 分 = 质量通过；≥1 分 半通过；0 分 = Spec 失败）

| 编号 | Type | 维度（Evaluative Scale） | 数值尺度 + 锚点（Low/Mid/High） | Pass Threshold | 证据来源 |
|------|------|--------------------------|---------------------------------|----------------|----------|
| U1 | rubric | **强一致写性能比 P12 MVP（最终一致）**：ratio = Raft Strong P99 mean（Row+Column 合计 100 行小对象）/ P12 MVP Final P99 mean（同样机同样 N=2000 warmup=500 5 轮） | 0=ratio>1.50 强一致慢 50%+；1=1.20<ratio≤1.50 慢 20-50% 可接受；**2=ratio≤1.20 强一致比最终一致慢 ≤20%** | ≥ 2 分（Full Score；mid 1=≥1 门槛；0 直接 Fail） | `P15_PerfStrongConsistencyTest`，5 轮 P99 打印行 + ratio 计算行；同 JVM；延迟注入 100ms RTT |
| U2 | rubric | **崩溃恢复性能比 P12 MVP binlog 重放**：ratio = Raft Snapshot+Log Recovery mean（3 次重启均值，1 万行 logs 后 kill）/ P12 MVP Binlog Replay mean（同样 1 万行） | 0=ratio>0.50 恢复比 P12 慢（≥30s）；1=0.20<ratio≤0.50 快 2-5×（12s-30s）；**2=ratio≤0.20 快 ≥5×（≤12s，NFR-P3 硬门槛）** | ≥ 2 分（Full Score；1=≥1 门槛；0=Fail） | `P15_PerfCrashRecoveryTest` @Fork(3)，3 轮各自耗时 + 均值对比；对比 P12 `P12_PerfColdBootTest` 历史 U2 baseline |

---

> **[SP1 红线]**：spec.md + tasks.md **必须拿到 User 显式批准**（NotifyUser 返回「已批准」或「User has approved...」） 前，T1~T8 任何一步不得写业务代码；违者 Workflow Fidelity Rubric = 0 分。
