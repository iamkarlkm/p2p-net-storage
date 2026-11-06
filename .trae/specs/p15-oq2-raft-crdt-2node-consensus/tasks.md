# P15 Tasks — OQ2 升级：2 节点 Raft 共识层（Leader Election + AppendEntries + Log Compaction + 2PC 强一致 ≤100ms RTT）

> 关联 spec：`spec.md`（同目录）；周期：2026-09-27 → 2026-11-10（6 周，MVP 4 周 2 节点主备）；所有任务 Status ∈ {pending, in_progress, blocked, completed, cancelled}
> 硬红线：**SP4 用户批准 spec.md + tasks.md 前，T1~T8 任一步禁止写任何业务源码**（Implement 步硬红线，违者 Workflow Fidelity Rubric = 0）

---

## 任务优先级 + 依赖图（严格串行，前置未 completed 禁止后移）

```
T1（Raft KeySpace + RegistryMetaPaths /orm/raft 5 常量 + 6 typed helper + c11 Gatekeeper C3 zero leak）
  → T2（Raft 状态机 3 角色 FOLLOWER/CANDIDATE/LEADER + 3 RPC RequestVote/AppendEntries/PreVote）
    → T3（State Machine 4 cmdType apply + Backward Compat P12 MVP magic=0x52 旧分支 + raft.mode=off 短路）
      → T4（6 facade hook 接入 + Raft Leader Proxy 转发写 + Follower fence 禁止本地写）
        → T5（Log Compaction 1000 entries 自动 Compact + InstallSnapshot 64KB 批 + Crash Recovery 重启 ≤12s）
          → T6（正确性专项 ≥16 tests 0F0E：Election3 + Append4 + Split-Vote1 + Partition2 + Snapshot2 + Crash2 + BackwardCompat2 + MetaReplicate4 → c11 Gatekeeper 25 assertions + R15 JV ≥128 tests 0F0E）
            → T7（性能专项 U1 Strong P99 ratio ≤1.20 × P12 MVP + U2 Crash Recovery ratio ≤0.20 × P12 binlog，2 rubric 各 2 分满分）
              → T8（NeatFreak 三层同步：AGENTS.md / 记忆 / roadmap L450 P3 OQ-2 CLOSED + 交叉一致性 + grep 相对时间 0 命中）
```

---

## Task 1：RegistryMetaPaths 扩 `/orm/raft` 子树（5 常量 + 6 typed helper，路径唯一出处 zero leak 前置）

- **AC Coverage**：R1（`/orm/raft` 5 常量 RegistryMetaPaths.java 1 处 grep 命中；raftLogLeaf(1000L)=`00000000000003e8` 16hex 对齐）、R14 部分（C5 RAFT_ROOT 值/String const≥20/16hex/txSeg32hex/zero leak 5 assertions）
- **Dependencies**：None（SP4 批准后第一个执行）
- **Priority**：High
- **Scope**（严格 ≤以下范围，越界 = NG 违规）：
  1. `RegistryMetaPaths.java` 新增 5 public static final String 常量（P10 7 + P11 4 + P12 4 = 原 15，新增后 ≥ 20，c11 C2 String const ≥20 断言 PASS）：
     - `RAFT_ROOT = ROOT + "/raft";` → "/orm/raft"，与 ROW/COLUMN/DBS/DATA/SYNC 五前缀零包含关系，grep `"/orm/raft"` 全工程只能 1 行（常量声明）命中。
     - `RAFT_STATE_LEAF = RAFT_ROOT + "/state";`（32B packed：currentTerm 8B + votedFor 8B + commitIndex 8B + lastApplied 8B，BigEndian 对齐）
     - `RAFT_LOG_ROOT = RAFT_ROOT + "/log";`
     - `RAFT_SNAPSHOT_ROOT = RAFT_ROOT + "/snapshot";`
     - `RAFT_TX_ROOT = RAFT_ROOT + "/tx";`（OQ3 后续扩展：/orm/raft/tx/<txId32hex>/state 1B PREPARED/COMMITTED/ABORTED）
  2. 新增 6 `public static` typed helper（peerId/logIndex/txId 全 hex 对齐 rowIdHex 风格 = `%016x` / `%032x` 小写）：
     - `raftLogLeaf(long logIndex)` → `RAFT_LOG_ROOT + "/" + String.format("%016x", logIndex)`（logIndex 16hex）
     - `raftSnapshotLeaf(long lastIncludedIndex, long lastIncludedTerm)` → `RAFT_SNAPSHOT_ROOT + "/" + String.format("%016x", lastIncludedIndex) + "_" + String.format("%08x", lastIncludedTerm)`
     - `raftTxState(long txId)` → `RAFT_TX_ROOT + "/" + String.format("%032x", txId) + "/state"`
     - `raftPeerVoteTerm(long peerId)` → `RAFT_ROOT + "/peer/" + String.format("%016x", peerId) + "/voteTerm"`
     - `raftPeerNextIndex(long peerId)` → `RAFT_ROOT + "/peer/" + String.format("%016x", peerId) + "/nextIndex"`
     - `raftPeerMatchIndex(long peerId)` → `RAFT_ROOT + "/peer/" + String.format("%016x", peerId) + "/matchIndex"`
  3. **Zero Leak 硬约束**：`grep -rn "\"/orm/raft\"" p2p-db/src/main/java` 仅允许 RegistryMetaPaths.java 常量声明行 1 处命中；其他文件命中数 = 0（c11 Gatekeeper C3 Raft zero leak 子断言，用 Java NIO Files.walk 遍历非系统命令，跨平台 Windows/Linux）。
  4. **P10/P11/P12 既有 helper/常量 0 字符改**：原 7+4+4 = 15 常量，5 P12 sync helper，3 P11 data helper，返回值、参数、throws 列表 0 改；byte-for-byte diff 0（R14 B6 facade 方法数 baseline ±4 实锤前置准备）。
- **Local Test Requirements（TRs，仅 rule 或 rubric，no other）**：
  - **TR-T1-R1 [rule]**（R1 ac_r1_raft_paths）：`P15_RaftKeySpaceTest#ac_r1_raft_paths` PASS → assert RAFT_ROOT=`"/orm/raft"`；raftLogLeaf(1000L) endsWith `"log/00000000000003e8"`；raftTxState(42L) contains `"tx/" + "%032x".formatted(42L) + "/state"`；6 helper 返回值全部含 peer/log 正确段。
  - **TR-T1-R2 [rule]**（R1 zero leak）：Java NIO walk `p2p-db/src/main/java` 所有 .java 文件，查字面量 `"/orm/raft"` 仅 RegistryMetaPaths.java 1 行命中（常量声明）；其他文件命中数 = 0。由 P15_RaftGatekeeperTest `c11_raft_zero_leak_assert` 子断言实锤（不用系统 grep 命令，避免 Windows/Linux 跨平台）。
  - **TR-T1-R3 [rule]**（R14 C2）：RegistryMetaPaths `public static final String` 常量数 ≥ 20（P10 7 + P11 4 + P12 4 + P15 5 = 20，刚好 ≥20 PASS）；所有 public fields 100% String 类型（非 String public field 0 个）。
- **Status**：pending
- **Blocked By / Unblock Condition**：SP4 用户显式批准 spec.md + tasks.md（必须拿到 IDEA 插件返回「User has approved the given files, continue working.」，不得自行跳过）
- **Completion Evidence**（完成 Status=completed 时填实锤，空 = 未完成）：TR-T1-R1/R2/R3 三项 PASS 日志行号；zero leak 1 行命中具体 L 行；RegistryMetaPaths public String const ≥20 计数输出。

---

## Task 2：Raft 共识核心（RaftState 3 角色 + 3 RPC RequestVote/AppendEntries/PreVote，严格 2 节点 quorum=2）

- **AC Coverage**：R2（3 角色状态机完整闭环 FOLLOWER→CANDIDATE→LEADER→FOLLOWER，currentTerm 单调不回退）、R3（PreVote partition 5s term 膨胀 ≤2）、R4（AppendEntries 4 TR 子专项 prevLog match/不 match/批量 64KB/heartbeat 20）、R11（Split-Vote 99th ≤500ms）、R14 部分（D5 3 RPC handler 存在 + voteGranted field 存在）
- **Dependencies**：T1 completed（RAFT_* 常量 + 6 helper 存在，peerNextIndex/matchIndex 状态字段路径已定义，避免 T2 手写 "/orm/raft" 字符串触发 zero leak FAIL）
- **Priority**：High
- **Scope**：
  1. **新增 package-private 类** `com.q3lives.ds.registry.meta.raft.RaftServer`（内嵌 `static enum Role { FOLLOWER, CANDIDATE, LEADER }`）：
     - 字段（private final / volatile 不对外暴露）：`volatile Role role;` `volatile long currentTerm;` `volatile long votedFor;` `volatile long commitIndex;` `volatile long lastApplied;` `List<LogEntry> logs;`（max 10000 entries，环形）、`ConcurrentHashMap<Long, PeerState> peers`（peerId → nextIndex + matchIndex + lastRpc）。
     - 线程池：ScheduledExecutorService 2 线程 = heartbeatThread（50ms 1 次空 AppendEntries LEADER 发） + electionThread（election timeout 随机 150-300ms 到期没 heartbeat → CANDIDATE 转 PreVote → Vote）。
     - Constructor 2 种（c11 Gatekeeper E4 ×2）：`(File dbRoot, MetaMode mode)` + `(File dbRoot, MetaMode mode, Map<String,String> props)`；MetaMode=YAML || props.get("p2p.orm.raft.mode").equals("off") → return null（RAFT_OFF 短路，零 new 内存分配，R14 E3 raft=off Raft==null 断言前置）。
  2. **LogEntry 序列化 layout（复用 P7 DsBinlog Frame，零新 byte 结构）**：Header 19B = `uint8 cmdType(0x00/01/02/03) | uint64 term | uint16 registryPathLen | uint32 payloadLen`；Body = registryPath bytes + payload bytes（≤64KB/entry，批量 AppendEntries entries[] ≤ 64KB 总大小 = 1 批 ≈ 100 entries 128B 行）。
  3. **3 RPC Handler（内部 handler，复用 P7 P2PCommand 路由，新增 3 command ID：24336 REQUEST_VOTE / 24337 APPEND_ENTRIES / 24338 INSTALL_SNAPSHOT，与 P7 ExtAttrs 24313-24335 不冲突，且不对外暴露 SDK 文档）**：
     - REQUEST_VOTE：args = term + candidateId + lastLogIndex + lastLogTerm；result = term + voteGranted（voteGranted = term>=currentTerm && (votedFor==0||votedFor==candidateId) && candidateLastLog>=localLastLog）。
     - APPEND_ENTRIES：args = term + leaderId + prevLogIndex + prevLogTerm + entries[] + leaderCommit；result = term + success + matchIndex；success=true 当 prevLogIndex/prevLogTerm 完全匹配本地日志；否则 success=false（leader 自动 nextIndex-- 回退重试）。
     - PRE_VOTE（R3 term no inflate 前置）：Candidate 转 CANDIDATE 前先发 PRE_VOTE，peer 本地 lastLog >= candidate lastLog → grant preVote；拿到 ≥ quorum preVote 才 term++ 发正式 Vote（网络 partition 5s term 差 ≤2）。
  4. **2 节点 Quorum 规则（hard rule，不得改 ≥3 节点逻辑）**：CommitIndex = min(newestLogIndexLeader, min(leader.matchIndex[leader], peer.matchIndex[Follower]))（即 Leader self applied + Follower applied = 2/2 = quorum，0 split-brain；保证强一致，NFR-C3 强一致写 quorum 实锤）。
  5. **Election Timeout = 150-300ms 抖动**（ThreadLocalRandom.current().nextInt(150, 300)），防 Split Vote（R11 99th ≤500ms）；PreVote 再加一层防 term 膨胀（R3 分区恢复 term ≤2）。
- **Local TRs**：
  - **TR-T2-R1 [rule]**（R2 3 角色闭环）：`P15_RaftElectionTest#t01_3_roles_cycle` PASS → ① 启动 Follower，300ms 无 heartbeat → CANDIDATE（election timeout 触发）；② CANDIDATE term+1 → Vote 拿到 quorum（2/2 self+peer 或 2 节点 self+peer grant）→ LEADER；③ LEADER 发 heartbeat 每 50ms 1 次；④ 伪造 Leader（更高 term）的 heartbeat 收 → LEADER 回退 FOLLOWER；⑤ 全程 currentTerm 单调：after ≥ before，每一步 transition term ≥ previous。
  - **TR-T2-R2 [rule]**（R3 PreVote 防 term 膨胀）：`P15_RaftPartitionTest#t01_term_no_infinite_inflate` PASS → ① Leader 与 Follower network partition（阻塞 socket 5s），Follower 自动转 CANDIDATE；② 发 PreVote，peer Leader side（offline，partition）PreVote 不 grant；③ 连续 10 轮 election timeout（5s）后，Follower term - 原 Leader term ≤ 2（PreVote 没拿到 quorum 不正式 vote，term 不无限 ++）；④ partition 恢复 2s 内 rejoin，数据 0 lost（Arrays.equals sha1 total）。
  - **TR-T2-R3 [rule]**（R4 AppendEntries 4 子专项）：`P15_RaftAppendTest` 4 TR → ① prevLog match 100 条 entries batch apply → success=true matchIndex+100；② 故意 prevLogIndex 错 1 → 回退 nextIndex 重试直到 match；③ 64KB/批 entries[] = 100 条 128B rows = 12.8KB ≤64KB，对端 apply 100 条全过；④ 1s 内 0 writes，LEADER 心跳 50ms × 20 = 20 条空 AppendEntries，peer ≥15 条收到算 PASS（允许 5 条丢因 Windows CFS 抖）。
  - **TR-T2-R4 [rule]**（R11 Split-Vote 99th ≤500ms）：`P15_RaftSplitVoteTest#t01_99th_500ms` → 100 次选举，Election Timeout 故意收紧 150-170ms（抖动小 = 提高 split 触发率）；99th percentile resolve time ≤500ms；分裂次数 ≤1 次（PreVote + 抖动=分裂 0.1% 概率，100 次 ≤1 PASS）。
- **Status**：pending
- **Blocked By / Unblock Condition**：T1 completed + SP4 已批准
- **Completion Evidence**：（填 4 TR PASS 行号；currentTerm 单调每步对比日志；PreVote 5s partition term 差 = X ≤ 2 数值；Append 100 条 entries 批量 12.8KB apply 100/100 日志；Split-Vote 99th=X≤500ms）

---

## Task 3：State Machine 4 cmdType + P12 MVP 向后兼容（100% 走 P10/P11 helper，零自造读写路径）

- **AC Coverage**：R6（4 cmdType apply 正确：PUT/REMOVE/META/NOOP → 100% 走 P10/P11 既有 helper）、R9（BackwardCompat：raft.mode=off → RaftServer=null；magic=0x52 frame → P12 旧 CAS side-version 分支 100% = P12 MVP 行为，无 regress）、R13（7 类元路径 replicate：TableMeta/ColumnRegistry/RowIdSeq/EqIndexMeta 4 类跨节点一致）、R14 部分（E3 raft=off backward compat）
- **Dependencies**：T2 completed（RaftServer 3 RPC 通道已跑通，log entry 才能 apply state machine；P12 旧 frame 接收 handler 必须已注册 magic=0x52 分支才不 NPE）
- **Priority**：High
- **Scope**（仅 4 cmdType，不得新增 cmdType，不得绕过 P10/P11 helper 自造 write/read）：
  1. **RaftServer.apply(LogEntry) 主 apply 入口**：switch(cmdType) → 4 分支 100% 调 P10/P11 已 VERIFIED CLOSED helper：
     - CMD_NOOP(0x00)：空 no-op，lastApplied++ 即可（LEADER 刚当选发 1 条，强制 commitIndex 前进）。
     - CMD_PUT_REGISTRY(0x01)：`AbstractRegistryDataBackend.writeLeafBytes(registryPath, payload, consumer=bytes -> { bucket put; })`（P11 5 路由 helper 之一，bucket-first-then-registry 双写语义 100% 保持，不得改双写顺序）。
     - CMD_REMOVE_REGISTRY(0x02)：`AbstractRegistryDataBackend.removeLeafBytes(registryPath, supplier -> { bucket remove; registry remove; })`（P11 remove helper，双删任一 true 返回 true 语义一致）。
     - CMD_META_SAVE(0x03)：`AbstractRegistryBackend.writeLeafBytes(metaPath, metaBytes, null)`（P10 元路径 7 类：TableMeta/ColumnRegistry/RowIdSeq/EqIndexMeta/OnlineDDL/ClassIdSet/ColumnDeleted，全由 1 个 writeLeafBytes 包装，不得改参数顺序）。
  2. **Backward Compat 两大分支（R9 BackwardCompat 2 TR 关键）**：
     - 分支 A：**raft.mode=off 系统属性**（`-Dp2p.orm.raft.mode=off` 启动）：RaftServer Constructor 2 种 读到 mode=off → return null；6 facade hook 全读 `if(raftServer != null)` → 全短路，行为 100% = P12 MVP（CAS side-version 高 v 赢 sibling）；P12 192 tests（P10 38+P11 64+P12 ≥26）合跑 ≥128 tests 0F0E。
     - 分支 B：**旧 P12 magic=0x52 DsRegistrySyncFrame（无 Raft logIndex）**：收到 DB_NODE_REPLICATE_FRAME handler 首字节 0x52 → 解析 P12 Frame（无 term/logIndex）→ 走原 P12 MVP applyRegistryFrame（CAS side-version 高 v 赢 + syncPeerOverwrites 计数）；不触发 Raft apply；新旧节点互通（P12 node 未 upgrade 也能继续 run，R14 E3 backward compat 2 种 scenario）。
  3. **7 类元路径 replicate（R13 4 TR）**：TableMetaStore/ColumnRegistry/RowIdSeq/EqIndexMeta 4 Metastore save/delete/markDeleted/allocate 变动后 → 6 facade hook 自动封装 CMD_META_SAVE(0x03) → 跨节点 apply，两端 bytes sha1 完全相等（含 TableMeta compositeGroups/ColumnRegistry.deleted/RowIdSeq nextRowId/EqIndexMeta bloomPath）。
- **Local TRs**：
  - **TR-T3-R1~R4 [rule]（R6 4 cmdType）**：`P15_RaftStateMachineTest` 4/4 PASS → c01 CMD_NOOP apply 0 state change lastApplied++；c02 CMD_PUT_REGISTRY rowId=42 128B → P11 dataBackend bytes = bucket bytes = Raft applied bytes，Arrays.equals；c03 CMD_REMOVE_REGISTRY rowId=42 → dataBackend.exists=false && bucket.exists=false；c04 CMD_META_SAVE TableMeta 16 columns new → 对端 TableMetaStore columns bidirectional write 16/16 全过。
  - **TR-T3-R5~R6 [rule]（R9 BackwardCompat）**：`P15_RaftBackwardCompatTest` 2 TR → ① raft.mode=off 启动，reflect assert RaftServer==null；跑 P12 MVP 192 tests（合跑命令见 T6 R15）≥128 tests 0F0E；② 发 P12 magic=0x52 frame（无 term/logIndex）→ 走 P12 旧分支，Follower 本地 payload 与 P12 MVP baseline 128B row byte-for-byte 相等。
  - **TR-T3-R7~R10 [rule]（R13 4 Meta）**：`P15_RaftMetaReplicateTest` 4/4 PASS → m01 TableMeta 新增 16 columns；m02 ColumnRegistry.colId 5 markDeleted；m03 RowIdSeq.allocate(2000) nextRowId 单调 2000+；m04 EqIndexMeta.save a_idx bloomEnabled → 两端 RegistryMeta bytes 完全相等 sha1 match。
- **Status**：pending
- **Blocked By / Unblock Condition**：T2 completed
- **Completion Evidence**：（10 TR PASS 行号；4 cmdType apply 每次都经过 P11/P10 辅助的 stack trace 片段（无自造 write）；BackwardCompat mode=off Raft==null reflect 断言日志；Meta replicate 4 类 bytes sha1 匹配 4/4）

---

## Task 4：6 Facade Hook 接入 + Follower Fence Write 禁止 + RaftLeaderProxy 透明转发（公开签名 0 改，NG-2 硬约束）

- **AC Coverage**：R5（强一致写 2/2 quorum applied：Leader 写阻塞等 commitIndex 才 return；Follower 收到 write 拒绝或转发，不本地写 split-brain = 0F0E FAIL）、R10（Follower 写 fence：本地 write 抛 IOE NotLeader，含 Leader 地址；不得 Follower 本地直接写 Registry）、R14 部分（B6 6 facade 方法数 baseline ±4；A6 修饰符 private final 内部 hook 字段）
- **Dependencies**：T3 completed（State machine apply 走通后才能写 hook；否则 hook 写→apply 顺序错 = data loss）
- **Priority**：High
- **Scope**（只增 package-private 条件分支；6 facade public 签名 byte-for-byte 零改，NG-2 硬红线）：
  1. **6 facade 新增 package-private `raftServer` private final 字段 + constructor 初始化**：
     - DsDatabaseLocal/ColumnarStore/TableMetaStore/ColumnRegistry/RowIdSequenceStore/EqIndexMetaStore 6 类各加 1 个 `private final RaftServer raftServer;`（构造函数中按 MetaMode + raft.mode 初始化，YAML 或 mode=off 时 =null，与 P12 MVP replicator=null 短路一致；c11 A6 修饰符 private final 断言 PASS）。
  2. **写侧 14 处 hook（包装成 `RaftLeaderProxy` 内部包装，0 facade public method 改）**：
     - DsDatabaseLocal：putEntity / putEntities / update / removeTable → 4 处 hook：**如果 raftServer.role == LEADER** → RaftServer.append(CMD_PUT_REGISTRY, dataRowPayload(...), rowBytes, **quorumCommitBlocking=true 阻塞等 commitIndex >= logIndex 才 return**；如果 role == FOLLOWER → 抛 `IOException("Not Leader; currentLeader=" + raftServer.currentLeaderAddress())` 或 透明转发（RaftLeaderProxy 内部包装远程调用 executeRemote）；如果 raftServer=null（mode=off）→ 全短路走 P12 MVP。
     - ColumnarStore：putValue×2 / putFieldGroup×2 / putCompositeGroup / removeValue×1 / removeRow×1 / deleteColumnHard×1 → 8 处 hook，同 DsDatabaseLocal；deleteColumnHard for 循环内 **逐条 replicate remove 帧**（整表清丢 1 帧会留 orphan leaf，P12 HOTFIX 延续同样逐条规则）。
     - 4 Metastore（TableMetaStore/ColumnRegistry/RowIdSeq/EqIndexMetaStore）：saveMeta / deleteMeta / markDeleted / allocate / reserveRange / save / remove → 2 处 hook，包装 CMD_META_SAVE(0x03)，阻塞等 quorum commit。
  3. **Follower Write Fence 双保险（R10 Follower fence）**：① hook 入口第一行 `if(raftServer != null && raftServer.role != Role.LEADER) throw new IOException("Not Leader; leader="+raftServer.currentLeaderAddress())`；② 兜底：DsRegistry 写入口 `writeLeafBytes` 再追加 package-private 校验 `if(raftServer != null && raftServer.role != Role.LEADER)` → 二次 assert Fail，绝不允许 Follower 本地写（双保险 = R10 0F0E FAIL 不出现 split brain）。
  4. **B6 方法数 baseline ±4 实锤（R14 B6）**：6 facade public 方法数 ∈ [P11 baseline -4, P11 baseline +4]；新增方法必须 protected 或 package-private；public method 0 新增（反射 count 校验 c11 Gatekeeper B1-B6 子断言）。
- **Local TRs**：
  - **TR-T4-R1 [rule]（R5 quorum 2/2 commit）**：`P15_RaftStrongConsistencyTest#t01_quorum_2of2` → Leader append rowId=42 128B，阻塞等 commitIndex ≥ logIndex；Follower 本地 Registry dataRowPayload 42 字节与 Leader 完全相等（Arrays.equals）；partition 期间 write 阻塞 2s 超时抛 IOException（不写 stale data）。
  - **TR-T4-R2 [rule]（R10 Follower fence）**：`P15_RaftLeaderFenceTest#t01_follower_no_writes` → 直接 new Follower 端 DsDatabaseLocal，putEntity(User(id=1)) → 抛 IOException("Not Leader")，message 含 "currentLeader="；Follower 端 Registry rowId=1 path leaf 未创建（new File(leafPath).exists()==false）。
  - **TR-T4-R3 [rule]（R14 B6 方法数 baseline）**：6 facade 每类 public method 数计数：DsDatabaseLocal 30 ∈ [26,34]；ColumnarStore 24 ∈ [22,30]；TableMetaStore 22 ∈ [18,26]；ColumnRegistry 16 ∈ [12,20]；RowIdSeq 10 ∈ [8,14]；EqIndexMetaStore 14 ∈ [10,18] → 6 类全在 baseline ±4 范围 PASS。
- **Status**：pending
- **Blocked By / Unblock Condition**：T3 completed
- **Completion Evidence**：（3 TR PASS 行号；quorum commit Leader 阻塞后 Follower bytes equality 断言；Fence IOException 日志 message；B6 6 类方法数计数输出）

---

## Task 5：Log Compaction 1000 entries 自动 Compact + InstallSnapshot 64KB 批 + Crash Recovery 重启 ≤12s（NFR-P3 硬门槛）

- **AC Coverage**：R7（Compact：1500 logs → log leaf ≤500；Snapshot.lastIncludedIndex≥1000；InstallSnapshot Follower apply 与 Leader sha1 total match）、R8（Crash Recovery：kill 10000 logs → 重启均值 ≤12s；lastApplied ≥原 commitIndex）、R14 D5 部分（INSTALL_SNAPSHOT RPC 存在，24338 命令号）
- **Dependencies**：T4 completed（写 hook 跑通 ≥1500 logs 才能触发 compact；crash 前必须写满 1 万行）
- **Priority**：High
- **Scope**：
  1. **Compact 触发条件（原子性：双条件任一触发）**：`lastLogIndex - snapshot.lastIncludedIndex >= 1000 || System.currentTimeMillis() - lastSnapshotTs >= 10*60*1000`（10 分钟）。
  2. **Snapshot Build（顺序遍历 4 子树，1MB 连续写 LZ4 Frame 复用 P7 binlog）**：按 `/orm/row` → `/orm/column` → `/orm/dbs` → `/orm/data` 4 子树顺序 listChildren，每个 key+value 按字典序（key 升序）写入 snapshot bytes；LZ4 FrameOutputStream 分块 1MB 段压缩（复用 P7 DsBinlog LZ4FrameOutputStream，零新依赖），lastIncludedIndex + lastIncludedTerm + totalLen 写 Header 16B（capture 完整性校验尾段 sha1 4B）。
  3. **InstallSnapshot RPC（64KB/批 gzip 可选）**：`RAFT_INSTALL_SNAPSHOT(24338)` → args = term + leaderId + lastIncludedIndex + lastIncludedTerm + offset 4B + data[]（≤64KB）+ done；Follower 收到：done=false → 追加写本地 snapshot 文件；done=true → 校验 16B Header + 20B sha1 尾 → 清空 `/orm/raft/log` ≤ lastIncludedIndex 所有 leaf → apply snapshot → commitIndex = max(local, leaderCommit) → lastApplied = lastIncludedIndex。
  4. **Crash Recovery（3 步，顺序严格）**：① 读 RAFT_STATE_LEAF 32B packed（currentTerm 8 + votedFor 8 + commitIndex 8 + lastApplied 8）；② 读最新 snapshot（若有）apply state machine；③ replay log(snapshotIndex+1..lastLogIndex) 逐条 apply；④ 启动后 role=FOLLOWER，300ms 无 heartbeat → PreVote → Candidate → Vote。
- **Local TRs**：
  - **TR-T5-R1 [rule]（R7 Compact 1500 logs）**：`P15_RaftSnapshotTest#t01_compact_and_install` → 写 1500 entries（超过 1000 阈值）；① `ls /orm/raft/log` 目录 leaf 数 ≤500；② Snapshot.lastIncludedIndex ≥ 1000；③ InstallSnapshot 从 Leader → Follower（64KB×2 批），Follower 数据与 Leader 总 bytes sha1 完全相等。
  - **TR-T5-R2 [rule]（R8 Crash 10k logs ≤12s）**：`P15_RaftCrashRecoveryTest @Fork(3)` → ① 写满 10000 logs（1 万 128B rows = 1.28MB data，1 万 log entries 约 ≥6MB）；② ProcessBuilder 独立进程 kill -9（destroyForcibly）；③ 重启 new 进程 → 自动读 state+snapshot+replay logs → 转 FOLLOWER；④ 3 次重启均值 ≤ 12s（NFR-P3 硬门槛，P12 MVP binlog baseline 60s，要求 ≤12s = 5× 快）。
- **Status**：pending
- **Blocked By / Unblock Condition**：T4 completed
- **Completion Evidence**：（Compact 1500 logs → /raft/log leaf count X≤500；Snapshot.lastIncludedIndex Y≥1000；Install 64KB×2 批 sha1 equality；Crash 3 次均值 11.2s ≤12s 具体数值）

---

## Task 6：正确性专项 ≥16 tests 0F0E + c11 Gatekeeper 25 assertions + JV ≥128 tests（R15 硬门槛 ≥128 0F0E）

- **AC Coverage**：R1-R14 共 14 rule AC 全部 cover，外加 R15 JV ≥128 tests（P10 38 + P11 64 + P12 ≥26 + P15 16 ≥144 ≥128 满足门槛）；合计 14+1=15 rule AC 实锤
- **Dependencies**：T5 completed（log compact/crash 必须通过才敢合跑；避免 128 tests 浪费时间在未完成 compact 代码上）
- **Priority**：High
- **Scope**（≥16+1 专项，1 个 gatekeeper；1 个 JV 合跑）：
  1. **≥16 tests 独立类**：P15_RaftKeySpaceTest、P15_RaftElectionTest、P15_RaftPartitionTest、P15_RaftAppendTest、P15_RaftSplitVoteTest、P15_RaftStateMachineTest、P15_RaftBackwardCompatTest、P15_RaftMetaReplicateTest、P15_RaftStrongConsistencyTest、P15_RaftLeaderFenceTest、P15_RaftSnapshotTest、P15_RaftCrashRecoveryTest = 12 类 × 平均 2-4 TR = ≥ 16 tests。
  2. **⭐ c11 NG-3 Raft Gatekeeper（核心 25 assertions 5 防线 A6+B6+C5+D5+E3=25）**：
     - **A 防线（6 assertions 修饰符）**：A1 RaftServer 内嵌 Role enum = static final；A2 RaftState 字段 private final；A3 6 facade raftServer 字段 private final；A4 RowDataBackend/Column/Group 3 内嵌类（P11）static final；A5 AbstractRegistryReplicator（P12）≥5 protected helper；A6 RegistryMetaPaths 6 个 raft helper（public static）。
     - **B 防线（6 assertions 6 facade 方法数）**：B1 DsDatabaseLocal ∈ [26,34]；B2 ColumnarStore ∈ [22,30]；B3 TableMetaStore ∈ [18,26]；B4 ColumnRegistry ∈ [12,20]；B5 RowIdSeq ∈ [8,14]；B6 EqIndexMetaStore ∈ [10,18]。
     - **C 防线（5 assertions RegistryMetaPaths 路径钉死）**：C1 RAFT_ROOT == "/orm/raft"；C2 public static final String const ≥20；C3 raftLogLeaf(1L) contains "0000000000000001" 16hex 对齐；C4 raftTxState(1L) contains "%032x 32hex" txId；C5 zero leak："/orm/raft" 全 main 源码仅 RegistryMetaPaths 常量 1 行命中。
     - **D 防线（5 assertions Raft 关键签名/RPC 存在）**：D1 REQUEST_VOTE(24336) handler 存在；D2 APPEND_ENTRIES(24337) handler 存在；D3 INSTALL_SNAPSHOT(24338) handler 存在；D4 4 cmdType 0/1/2/3 常量在 RaftServer 中 public static final byte；D5 RequestVoteResult 含 voteGranted boolean field。
     - **E 防线（3 assertions Backward Compat 2 场景 + 1 extra constructor）**：E1 RaftServer 2 种构造器（File,MetaMode）&&（File,MetaMode,Map）存在；E2 raft.mode=off → Constructor return RaftServer==null；E3 magic=0x52 frame → P12 旧 CAS 分支执行（无 logIndex 时 ==P12 MVP 结果）。**（A6+B6+C5+D5+E3=6+6+5+5+3=25，精确 25 条，c11 25/25 PASS）**
  3. **T6 JV 合跑命令（p2p-db 模块，默认 HYBRID + raft=on，与 spec R15 对齐：≥128 tests 0F0E）**：
     ```powershell
     mvn -pl p2p-db "-Dtest=P10_RowMetaTest,P10_ColumnMetaTest,P10_MetaBackendPerfTest,P11_DataKeySpaceTest,P11_DataBackendBasicTest,P11_RowDataTest,P11_ColumnarDataTest,P11_DataGatekeeperTest,ColumnarStoreTest,ColumnarStoreCompressionTest,ColumnarStoreLayoutTest,Orm_P7_Tgt2_Handler_6_Test,DsRegistry_P7_Tgt3_HandlerTest,DsRegistryExtAttrs_P7_Tgt5_HandlerTest,DsRegistryExtAttrs_P7_Exts_Test,P12_RowReplicateTest,P12_ColumnReplicateTest,P12_GroupReplicateTest,P12_SyncSubtreeTest,P12_SyncBackwardCompatTest,P12_SplitBrainTest,P12_PeerHeartbeatTest,P12_SyncGatekeeperTest,P12_SyncOffFallbackTest,P15_RaftKeySpaceTest,P15_RaftElectionTest,P15_RaftPartitionTest,P15_RaftAppendTest,P15_RaftSplitVoteTest,P15_RaftStateMachineTest,P15_RaftBackwardCompatTest,P15_RaftMetaReplicateTest,P15_RaftStrongConsistencyTest,P15_RaftLeaderFenceTest,P15_RaftSnapshotTest,P15_RaftCrashRecoveryTest,P15_RaftGatekeeperTest" test 2>&1 | Select-String -Pattern "Tests run:|BUILD "
     ```
     保守计数：P10 15 + P11 14 + 列回归 18 + P7 ≥ 24 + P12 ≥ 22 + P15 ≥16 + P15 Gatekeeper 1 = 110+；再列回归 P13 专项 35 tests（Bloom4+Bulk3+JV5+JV23=35）→ 合计 **≥ 145 tests 远超过 128**。
- **Local TRs**：
  - **TR-T6-R1 [rule]（≥16 tests 0F0E）**：上面 T1-T5 所有 12 test 类独立 `mvn -Dtest=P15_*Test test` → Tests run ≥16；Failures=0, Errors=0。
  - **TR-T6-R2 [rule]（c11 Gatekeeper 25/25）**：`mvn -Dtest=P15_RaftGatekeeperTest` → 内部 assertions 25/25 PASS，print "A6+B6+C5+D5+E3=25/25 OK"。
  - **TR-T6-R3 [rule]（R15 JV ≥128 0F0E）**：上面合跑命令 exit 0；Tests run N ≥128；Failures=0 Errors=0；分项 P10/P11/P12/P13/P15 5 大类单独 PASS 计数都 ≥类内 @Test 数（缺 1 class 分项 PASS 也要 0 FAIL）。
- **Status**：pending
- **Blocked By / Unblock Condition**：T5 completed
- **Completion Evidence**：（c11 25 assertions 每条防线 PASS 打印；JV 合跑 BUILD SUCCESS Tests run 149 ≥128 行号；分项 5 类各自 Pass 数 = @Test 数）

---

## Task 7：性能专项 2 tests（U1 强一致 P99 ≤1.20×P12 MVP + U2 Crash Recovery ≤0.20×P12 binlog，2 rubric 各 2 分满分）

- **AC Coverage**：U1 rubric（ratio ≤1.20 → 2 分 ≥1 分 PASS；≤1.50 → 1 分；>1.50 → 0 Fail）+ U2 rubric（ratio ≤0.20 → 2 分；≤0.50 →1 分；>0.50 → 0 Fail）
- **Dependencies**：T6 completed（正确性 25+128 0F0E 之后才测性能；避免错代码白跑性能）
- **Priority**：Medium
- **Scope**（2 @Test @Category(PerfTest.class)，默认不跑，需显式 `-P perf` 或 `-Dtest=P15_*PerfTest`）：
  1. **U1 P15_PerfStrongConsistencyTest（U1 强一致 vs P12 最终一致）**：
     - 双 tmp 根 + 双进程对端：`P12_FINAL_BASELINE`（raft=off + 100ms RTT 延迟注入 Thread.sleep 2×50ms=100ms） 对比 `P15_STRONG_CONSISTENCY`（raft=on 2 节点 LEADER 写 阻塞等 2/2 quorum，同样 100ms RTT）。
     - 场景：Row put 128B ×100 行 + Column put 64B ×100 行 = 200 次写；同 JVM 5 轮，WARMUP=500, N=2000。
     - Ratio = P15_STRONG P99 mean / P12_FINAL P99 mean；mvn soft assert ≤1.25（+5% 放宽）。
  2. **U2 P15_PerfCrashRecoveryTest（U2 Raft Snapshot+Replay vs P12 MVP Binlog replay）**：
     - 数据：10000 log entries（1 万行 128B）；P15 端 kill + restart 3 次均值；P12 端 baseline：写 1 万行 binlog，kill JVM 重启冷启动 Binlog 全量重放 3 次均值（取 P12 历史 U2 冷启动 baseline 60s）。
     - Ratio = Raft Recovery mean / P12 Binlog mean；mvn soft assert ≤0.25（+5% 放宽）。
- **Local TRs**：
  - **TR-T7-U1 [rubric, 0-2 Scale]（U1 强一致性能比）**：打分 2=ratio ≤1.20；1=1.20<ratio≤1.50；0=ratio>1.50。**Pass Threshold ≥ 1**（≥1 分通过）；满分 2。Evidence：U1 5 轮 P99 打印 + Ratio mean line。
  - **TR-T7-U2 [rubric, 0-2 Scale]（U2 崩溃恢复性能比）**：打分 2=ratio ≤0.20；1=0.20<ratio≤0.50；0=ratio>0.50。**Pass Threshold ≥ 1**；满分 2。Evidence：U2 3 次 restart 各耗时 + P12 baseline binlog 60s 对比 Mean Ratio line。
  - **TR-T7-R1 [rule]**：mvn soft assert U1 ≤1.25 且 U2 ≤0.25 BUILD SUCCESS；否则 blocked（返工 T2/T5 性能：Append 批量、Snapshot 分块优化，但不得改 P10/P11/P12 既有交付代码 0 字符改）。
- **Status**：pending
- **Blocked By / Unblock Condition**：T6 completed
- **Completion Evidence**：（U1/U2 2 rubric 各自得分 + 理由；5 轮 P99 数值；3 次重启耗时；Ratio 精确到 3 位小数；TR-T7-R1 BUILD SUCCESS）

---

## Task 8：NeatFreak 三层知识同步（AGENTS / 记忆 / roadmap L450 OQ-2 升级 CLOSED；Workflow Fidelity 2/2 前提条件）

- **AC Coverage**：无直接 AC，Workflow Fidelity Rubric 2/2 要求 3 文档交叉一致 + 绝对日期 + 代码索引锚点真实可点击
- **Dependencies**：T7 completed（U1/U2 2 rubric ≥1 分之后才归档；避免写错误数值）
- **Priority**：Low（但 Review 开始前必须 completed，Review 不可用缺文档缺 3 层一致性）
- **Scope**：
  1. **AGENTS.md（Agent 层规则）**：在 P14 Invariant 下追加「P15 Invariant 3 组 11 条」：
     - 组 1（命名+通道）：① /orm/raft 5 常量 + 6 typed helper zero leak（RegistryMetaPaths 仅 1 行 grep 命中）；② Raft 3 RPC 24336-24338 内部 handler；③ 4 cmdType（0x00/01/02/03）；④ Log compaction ≥1000 entries 自动；⑤ InstallSnapshot 64KB/批。
     - 组 2（正确性+强一致）：① c11 NG-3 Raft Gatekeeper 25 assertions A6+B6+C5+D5+E3=25；② 2 节点 quorum=2/2 applied ≥ commitIndex，强一致；③ Follower 写 fence（双保险 IOException NotLeader + DsRegistry secondary fence）；④ Split-Brain write=0（partition 期间 Leader 阻塞抛 IOE，不本地写）；⑤ BackwardCompat raft=off 行为 100% = P12 MVP 192 tests ≥128 0F0E。
     - 组 3（性能）：① U1 ratio=X.XXX ≤ 1.20（填实测值 如 1.08，2 分）；② U2 ratio=Y.YYY ≤0.20（填实测值 如 0.17，2 分）；③ JV N=149 tests 0F0E。
  2. **project_memory.md（Agent 记忆层）**：
     - Context 追加「P15 闭环：2 节点 Raft 强一致，3 RPC /4 cmdType / 2 rubric 满分；OQ3 2PC 强一致跨节点基础已 ready」；
     - Hard Constraints 追加 NG-5：`/orm/raft` zero leak 仅 RegistryMetaPaths 1 处声明 + c11 Gatekeeper 钉死；
     - Guidelines 追加：① Raft write 必须 Leader 阻塞等 quorum commit，不得 Follower 本地写；② BackwardCompat raft=off / old magic=0x52 frame 行为 100% = P12 MVP；③ Log Compact 双条件 1000 entries / 10 分钟，LZ4 Frame 复用 P7 无新依赖。
     - Lessons Learned 追加 3 条：① 2 节点 Raft quorum=2/2（非 majority，≥3 节点 majority 另开）；② PreVote 必加否则 partition 5s term 膨胀到 term=100+；③ Follower 双保险 fence（hook 入口 + Registry 写入口双层 check），漏一层 = split-brain 0.1% 概率出现。
     - P16 候选方向：OQ3 跨节点 2PC（Prepare→Commit→Undo + Retry 10 tx 并发 0 deadlock）/ OQ5 Linux CFS 复核 / OQ6 ≥3 节点 Raft multi-shard（三方向推荐先 OQ3）。
  3. **docs/dsdb-design-status-and-roadmap.md（人类 roadmap L450 P3 OQ-2 升级 CLOSED）**：
     - §六 推进路线图 P3 行：原「**OQ-2：跨节点 DML 数据同步（Raft/CRDT 2 节点 ≤100ms RTT 最终一致）**」→ 加粗 ✅ OQ-2（P15/CLOSED 2026-11-10）：2 节点 Raft 强一致（Leader Election 300ms/AppendEntries/PreVote term≤2 膨胀；Log Compact 1000 自动+InstallSnapshot；Crash Recovery 3 次均值 Xs≤12s；c11 25 assertions + JV 149 tests 0F0E；U1=X.XXX 2 分，U2=Y.YYY 2 分）。
     - §七 代码索引追加 6 条：
       1. RegistryMetaPaths Raft 5 const+6 helper → RegistryMetaPaths.java#L345-L450（估行号完成填实）
       2. RaftServer 3 角色+3 RPC → `com/q3lives/ds/registry/meta/raft/RaftServer.java#L40-L320`
       3. DsDatabaseLocal 写 hook 4 处 + Follower fence → DsDatabaseLocal.java#L1460-L1780
       4. ColumnarStore 写 hook 8 处 + deleteColumnHard 逐条 remove → ColumnarStore.java#L160-L630
       5. c11 Raft Gatekeeper 25 assertions → P15_RaftGatekeeperTest.java#L50-L380
       6. 4 cmdType apply state machine → RaftServer.apply L320-L420
- **Local TRs**：
  - **TR-T8-R1 [rule]**：grep `今天|昨天|最近|刚刚|today|yesterday|recently` 三目标文件（AGENTS.md / project_memory.md / roadmap L450 §六）→ 命中数 = 0（全用绝对日期 2026-09-27/2026-11-10 或专有名词 OceanBase today fallback 除外）。
  - **TR-T8-R2 [rule]**：三文件数值交叉一致：① U1 ratio 三文件同一；② U2 ratio 三文件同一；③ JV N tests 三文件同一；④ c11 25 assertions 三文件同一。
  - **TR-T8-R3 [rubric, Scale 0-2]**：Workflow Fidelity 归档完整性：2=3 文档全改完且数值交叉一致 + roadmap §七 6 条代码索引路径真实可点击（对应文件存在）；1=少 1 处文档或 1 条索引但核心 15+2 AC 覆盖描述齐全；0=少 2+处或数值矛盾。**Pass Threshold ≥ 1**。
- **Status**：pending
- **Blocked By / Unblock Condition**：T7 completed
- **Completion Evidence**：（TR-T8-R1 grep 0 命中输出；TR-T8-R2 U1/U2/N/25 4 数值 × 3 文件 12/12 交叉一致 PASS；TR-T8-R3 打分 + 理由；3 文档 diff --stat 改行数）

---

## 验收完成标准（Spec Mode Implement Queue Drained 才可进 Review Phase）

```
all T1..T8 status ∈ {completed}
&& 无 pending/in_progress/blocked
&& 每 completed task Completion Evidence 非空
&& 所有 rule TR（T1-T6 共 10+4+10+3+2+3 = 32 rule TR）全部 PASS
&& 所有 rubric TR（T7 U1/U2 + T8-R3）得分 ≥ 各 Pass Threshold（≥1 分）
&& T6-R1/R2/R3 全成立（≥16 tests 0F0E + c11 25/25 + JV ≥128 tests 0F0E）
&& T7 TR-T7-R1 软断言成立（U1 ≤1.25 且 U2 ≤0.25）
→ 方可进入独立 Review Phase（由 fresh agent 单独写 review.md，review 独立性 不可用本 implementer 自审）
```

> 取消任务唯一合法：用户显式说「Tn 取消」+ 理由；Implementer 自标 cancelled = Workflow Fidelity=0。
> 跳步禁令：T<n-1> completed 前，T<n> 任何源码写动作=违规，全部 revert + Fidelity=0。
