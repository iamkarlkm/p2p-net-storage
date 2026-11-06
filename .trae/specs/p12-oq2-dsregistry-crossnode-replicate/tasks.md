# P12 Tasks — OQ2：跨节点 DML 数据同步（2 节点最终一致 ≤100ms RTT）

> 关联 spec：`spec.md`（同目录）；周期：2026-09-21 → 2026-10-20（4 周 MVP，可提前 1 周闭环）；所有任务 Status ∈ {pending, in_progress, blocked, completed, cancelled}
> 硬红线：SP4 用户批准 spec.md + tasks.md 前，T1~T8 任一步禁止写任何业务代码（Implement 步硬红线，违者 Workflow Fidelity=0）

## 任务优先级与依赖图（串行执行，前置未 completed 禁止后移）

```
T1(Sync KeySpace 脚手架 + /orm/sync 命名段 + c10 C/E 部分)
  → T2(AbstractRegistryReplicator + 双通道 + BackwardCompat magic=0x52)
    → T3(行存 replicate hook 4 TR)
      → T4(列+组+meta replicate hook 12 TR = 5+4+3)
        → T5(正确性专项 ≥22 tests + SplitBrain + Heartbeat + c10 Gatekeeper 25 assertions + R12 SyncOff Fallback)
          → T6(JV 合跑 ≥78 tests 0F0E = P10 38 + P11 64 去重 + P12 专项 + P7 回归)
            → T7(性能专项 2 tests U1/U2 rubric)
              → T8(NeatFreak 三层知识同步 AGENTS/记忆/roadmap)
```

---

## Task 1：RegistryMetaPaths 扩 `/orm/sync` 子树 4 常量 + 5 typed helper（命名统一出处，零外泄前置）

- **AC Coverage**：R1（`/orm/sync` 命名段常量仅 RegistryMetaPaths.java 1 处 grep 命中；4 helper 返回正确段前缀 peerId16hex 对齐）、R10 部分（C 组 5 assertions 中的 SYNC_ROOT 值、String const≥15、peerId16hex、syncTxSeg 格式、sync 子树返回值含 peer 段）
- **Dependencies**：None（冷启动第一项，Implement 步 SP4 批准后首个执行）
- **Priority**：High
- **Scope**（严格包含，不得越界）：
  1. `RegistryMetaPaths.java` 新增 4 常量（`public static final String`，与现有 11 常量零字符改）：
     - `SYNC_ROOT = ROOT + "/sync";`（= `/orm/sync`，与 ROW/COLUMN/DBS/DATA 前缀零包含关系）
     - `SYNC_PEER_ROOT = SYNC_ROOT + "/peer";`
     - `SYNC_TX_ROOT = SYNC_ROOT + "/tx";`
     - 可选内部常量 `SYNC_OVERWRITES_LEAF = "overwrites";`（包内复用）
  2. 新增 5 个 `public static` typed helper（返回值统一用 peerId16hex = `%016x` 小写对齐 rowIdHex 风格）：
     - `syncPeerState(long peerId)` → `/orm/sync/peer/<peerId16hex>/state`（LEADER/FOLLOWER/IDLE 1B enum）
     - `syncPeerLastAck(long peerId)` → `/orm/sync/peer/<peerId16hex>/lastAckSeq`（logicalTs 8B LE）
     - `syncPeerOverwrites(long peerId)` → `/orm/sync/peer/<peerId16hex>/overwrites`（uint64 count leaf）
     - `syncTxState(long txId)` → `/orm/sync/tx/<txId32hex>/state`（PREPARED/COMMITTED/ABORTED 1B）
     - `syncTxLeaf(long txId, String registryPath)` → `/orm/sync/tx/<txId32hex>/leaves/<sha1(registryPath) 40hex>`
  3. **零外泄硬约束**：业务代码禁止手写 `"/orm/sync"` 字符串；所有 sync 路径必须经上述 5 helper 生成（c10 Gatekeeper C3 实锤 + grep 1 处命中）。
  4. **不得改任何 P10/P11 既有常量/helper 返回值**（P10 7 元 + P11 3 数据路径 0 字符改硬约束，c10 Gatekeeper C4 断言）。
- **Local Test Requirements（TRs，仅 rule 或 rubric）**：
  - **TR-T1-R1 [rule]**：`P12_SyncKeySpaceTest#ac_r1_sync_paths_exist` PASS——assert SYNC_ROOT=`"/orm/sync"`；syncPeerState(0x1234L) 返回 contains `"peer/" + "%016x".formatted(0x1234L) + "/state"`；syncTxState(42L) 返回 contains `"tx/" + "%032x".formatted(42L) + "/state"`；syncPeerOverwrites 返回 endsWith `"overwrites"`。
  - **TR-T1-R2 [rule]**：`grep -rn "\"/orm/sync\"" p2p-db/src/main/java` → 仅 RegistryMetaPaths.java SYNC_ROOT 常量声明行 1 处命中；其他文件命中数 = 0。由 c10 Gatekeeper `p12_sync_zero_leak_assert` 子断言实锤（Java NIO walk 非系统命令跨平台）。
  - **TR-T1-R3 [rule]**：RegistryMetaPaths `public static final String` 常量数 ≥ 15（原 P10 7 + P11 4 + 新增 P12 4 = ≥15），且 public fields 100% String（c10 C2 子断言）。
- **Status**：pending
- **Blocked By / Unblock Condition**：SP4 用户批准 spec.md + tasks.md（必须拿到「User has approved the given files, continue working.」才启动）
- **Completion Evidence**：（completed 时填入：P12_SyncKeySpaceTest PASS 日志；grep 输出 1 行命中行号；c10 C1~C5 子断言 PASS 片段）

---

## Task 2：AbstractRegistryReplicator 抽象 + 双通道复制（增量 Frame magic=0x52 向后兼容 + 全量 REGISTRY_SUBTREE_LIST/PUT_BATCH）

- **AC Coverage**：R2（DsRegistrySyncFrame magic=0x52 → applyRegistryFrame；magic!=0x52 → 旧 P7 bucket 分支 100% 向后兼容）、R6（REGISTRY_SUBTREE_LIST/PUT_BATCH 4 子树 checksum 一致）、R10 部分（D 组 4 签名存在 + E 组 Replicator 3×2 method + 构造器 × 2）
- **Dependencies**：T1 completed（需 SYNC 路径常量生成 registryPath + syncPeerOverwrites 计数）
- **Priority**：High
- **Scope**：
  1. **新建 package-private 抽象类** `com.q3lives.ds.registry.meta.AbstractRegistryReplicator`：
     - 字段（private final，不对外暴露）：`DsRegistryInstance registry;` `MetaMode mode;` `File dbRoot;` `Map<Long, PeerState> peers;`（peerId→state/lastAck）
     - 3×2 method（public final，不可 override，c10 E 组断言钉死存在）：
       - `final void replicateKey(String registryPath, byte[] payload, long version, long[] peerIds)`：增量通道，magic=0x52 序列化 DsRegistrySyncFrame，复用 P7 executeRemote(DB_NODE_REPLICATE_FRAME)。
       - `final void replicateRegistryMetaKey(String metaPath, byte[] metaBytes, long version, long[] peerIds)`：元路径变动 replicate（TableMeta/ColumnRegistry/RowIdSeq/EqIndexMeta 用）。
       - `final CompletableFuture<Boolean> syncPeerSubtree(InetSocketAddress peerEndpoint, String subtreePrefix)`：全量冷启动通道，REGISTRY_SUBTREE_LIST→PUT_BATCH 4 子树 64KB/批。
       - 构造器 2 种（c10 E4 ×2）：`(File dbRoot, MetaMode mode)` + `(File dbRoot, MetaMode mode, Map<String,String> props)`；MetaMode=YAML 或 props 含 `p2p.orm.sync.mode=off` 时，构造器直接 WARN + return null，零 new Registry 实例（与 P11 Backend 短路一致）。
  2. **DsRegistrySyncFrame 序列化 layout（向后兼容关键）**：
     - Header 19B = `uint8 magic(0x52) | uint16 reserved=0 | uint64 logicalTs(serverId22|localSeq64|epoch32) | uint64 version`
     - Body = `uint16 registryPathLen | byte[] registryPath | uint32 payloadLen | byte[] payload | uint8 op(0=UPSERT 1=REMOVE)`
     - **向后兼容策略**：接收 DB_NODE_REPLICATE_FRAME handler 读首字节，首字节 != 0x52（或首字节 < 0x50 表示无 magic 的旧裸 byte[]）→ 直接走 P7 旧 bucket 写入分支，旧节点不 upgrade 也能 run（R2 BackwardCompat 单测实锤）。
  3. **接收端 applyRegistryFrame（幂等 CAS，乱序不覆盖新值）**：
     - 解析 frame → `atomicModifyBytes(registryPath, (old)->{ if(old==null || parseVersion(old) < frame.version) return frame.payload; else return old; })`
     - 低 version 被 overwrite 后：`registry.putLong(syncPeerOverwrites(peerId), +1)` 计数 + WARN 日志 4 要素（registryPath/peerId/frameVersion/oldVersion）。
  4. **全量 REGISTRY_SUBTREE 通道（内部 Handler，不对外暴露）**：
     - `P2PCommand.REGISTRY_SUBTREE_LIST(24120)` → listChildren(prefix, cursor, limit=1000) → 返回 (key[][], byte[][], nextCursor)，64KB/批 gzip 可选。
     - `P2PCommand.REGISTRY_SUBTREE_PUT_BATCH(24121)` → 批量 writeLeafBytes，成功返回 ackCount；顺序按 `/orm/row` → `/orm/column` → `/orm/dbs` → `/orm/data` 4 子树，每棵子树拉完 checksum=sha1(all leaf bytes) → 对端 checksum 相等才 next，不等重试 3 轮。
- **Local TRs**：
  - **TR-T2-R1 [rule]**（R2 BackwardCompat）：`P12_SyncBackwardCompatTest` 3 场景全过——① 发 magic=0x52 frame → 对端 applyRegistryFrame 执行且 payload bytes 一致；② 发 magic=0x01 旧帧 → 对端走旧 P7 bucket 写入路径且旧 bucket.get bytes 正确；③ 发裸 byte[]（无 magic，首字节=0x00 payload）→ 旧分支 PASS，无 NullPointerException。
  - **TR-T2-R2 [rule]**（R6 Subtree）：`P12_SyncSubtreeTest` 4 TR 全过——① `/orm/row` 子树拉取 3000 leaf，checksum 对端相等；② `/orm/column` 同；③ `/orm/dbs` 同；④ `/orm/data` 同；4 子树合计 12000 leaf，64KB 分块 13 批，耗时 < 3s（localhost 无延迟注入）。
  - **TR-T2-R3 [rule]**（乱序幂等 apply）：100 乱序帧（乱序率 10%）处理，对端最终 bytes 与正序 100 帧结果 byte-for-byte 相等，syncPeerOverwrites count ≥10（被覆盖计数真实）。
- **Status**：pending
- **Blocked By / Unblock Condition**：T1 completed，且 SP4 已批准
- **Completion Evidence**：（填入 T2-R1/R2/R3 PASS 行号；magic=0x52 vs 旧分支的 byte-level 断言日志；overwrites count≥10 的数值截图）

---

## Task 3：行存接入 replicate hook（putEntity/putEntities/update/removeTable，公开签名 0 改）

- **AC Coverage**：R3（行 4 TR：roundtrip / 乱序不覆盖 / 幂等 / 节点 restart 100 帧 replay 无丢失）
- **Dependencies**：T2 completed（需 AbstractRegistryReplicator.replicateKey 存在）
- **Priority**：High
- **Scope**：
  1. DsDatabaseLocal 新增 package-private 字段 `AbstractRegistryReplicator replicator;`：构造函数中按 MetaMode 初始化，YAML 模式 replicator=null（完全短路，零 new Replicator 实例，与 P11 rowData Backend 短路风格一致）；`-Dp2p.orm.sync.mode=off` 时同样 =null。
  2. **4 处 replicate hook（包内调用，不碰公开签名，c10 B 组钉死方法数基线）**：
     - `putEntity(value, assignIdIfMissing)`：P11 dataBackend.writeRowPayload 成功之后 → `if(replicator!=null) replicator.replicateKey(dataRowPayload(entityClass, rowId), rowBytes, ++rowVersionFor(entityClass,rowId), peerIds)`
     - `putEntities(List<E> entities)`：循环内同上，批量 put 后逐条 replicate
     - `update(entity, id, readTimestamp)`：更新后 writeRowPayload 成功 → replicate
     - `removeTable(clazz, id, withRelations=true)`：rowData.removeLeaf 双删成功 → replicate remove 帧（op=REMOVE，payload=空 byte[0]，version 同样 ++，CAS 删除标记）
  3. **公开签名 0 改硬约束**：DsDatabaseLocal 所有 public 方法（putEntity/getTable/removeTable/registerTable/close/getBinlogStore/... 24 核心）的参数/返回/throws 列表 0 字符改；新增 helper 必须 protected/package-private（c10 B 组基线方法数 ∈ [26,34]，超范围即 FAIL）。
  4. 本地 version 管理：package-private `ConcurrentHashMap<String, LongAdder> rowVersionMap`（key = entityClass + ":" + rowIdHex）；缺省从 Registry 读该 row 的 max version + 1，避免 restart 后 version 回退（幂等语义基础）。
- **Local TRs**：
  - **TR-T3-R1 [rule]**：`P12_RowReplicateTest#t01_row_roundtrip`（2 tmp 根模拟 2 节点，localhost）——A 节点 putEntity(User(id=0, name="foo", age=23)) → 500ms 后 B 节点 getTable(User, rowId) deepEquals A 节点原对象；B 节点 Registry dataRowPayload leaf bytes 与 A 节点 bytes Arrays.equals=true。
  - **TR-T3-R2 [rule]**：`t02_out_of_order_no_override`——A 先发 version=2 帧（新值 age=25），再延迟发 version=1 帧（旧值 age=23）；B 节点最终 age=25（新值不被旧值覆盖）。
  - **TR-T3-R3 [rule]**：`t03_idempotent_apply_same_version`——连续 2 次 replicate 同 version=3 的 payload；B 节点 bytes 不变（CAS 检测 version 相等 → return old），rowVersionMap 计数器不递增（日志打印）。
  - **TR-T3-R4 [rule]**：`t04_restart_replay_100`——A 节点 put 100 行（rowId 1~100），记录 100 replicate 帧到内存 buffer；kill B 节点进程（模拟 restart，独立 new DsDatabaseLocal + Replicator），重放 buffer 中 100 帧，B 节点 100 行 bytes 与 A 节点 100 行 byte-for-byte 一致。
- **Status**：pending
- **Blocked By / Unblock Condition**：T2 completed
- **Completion Evidence**：（R1~R4 4/4 PASS 行号；c10 B1 DsDatabaseLocal 方法数断言 PASS；restart replay 100 行 checksum 一致日志）

---

## Task 4：列 + 复合组 + Meta 变动 replicate hook（公开签名 0 改）

- **AC Coverage**：R4（列 4 TR：roundtrip / 乱序 / removeValue / deleteColumnHard 跨节点清）、R5（组 4 TR：roundtrip / composite padding 8B / remove / replay）、R7（元路径同步 3 TR：TableMeta / ColumnRegistry / RowIdSeq/EqIndexMeta 单增 version）
- **Dependencies**：T3 completed（同 dbRoot 共享 DsRegistryInstance + Replicator 实例，避免多实例冲突）
- **Priority**：High
- **Scope**（分三模块，逐块接入，公开签名 0 改硬约束贯穿）：
  1. **列存 ColumnarStore（9 入口 replicate，覆盖 R4 全部 + R5 部分）**：
     - `putValue(entityClass, logicalName, rowId, valueBytes)` × 2（含 fixedLen 版）：P11 dataBackend.writeColumn 成功后（已 padding Arrays.copyOf 对齐 col.length！）→ `replicator.replicateKey(dataColumnPayload(...), paddedBytes, ++colVersion, peerIds)`
     - `removeValue(entityClass, logicalName, rowId)`：dataBackend.removeLeaf 双删成功 → replicate remove 帧（op=REMOVE，CAS 删除标记）
     - `removeRow(rowId)`：逐列遍历 removeColumn → 每条都 replicate remove（避免跨节点残留 orphan 列 leaf）
     - `deleteColumnHard(entityClass, logicalName)`：P11 已 HOTFIX for 循环内清 Registry；此处追加 for 循环内逐条 replicate remove 帧（高 version CAS，跨节点同步清所有 rowId 对应列 leaf）
     - `putFieldGroup` × 2 + `getFieldGroup` 读侧短路不动（写侧 replicate）
     - **公开签名 0 改**：ColumnarStore 20 核心公开方法数基线 ∈ [22,30]（c10 B2 断言）。
  2. **复合组 ColumnarStore compositeGroups（覆盖 R5 全部 4 TR）**：
     - `putCompositeGroup(entityClass, groupName, rowId, groupBytes)`：P11 已 HOTFIX Arrays.copyOf(groupBytes, g.length) 右补 0 保证 fixedLen padding；写成功后 → `replicator.replicateKey(dataGroupPayload(...), paddedGroupBytes, ++groupVersion, peerIds)`
     - `removeFieldGroup(entityClass, groupName, rowId)`：双删成功 → replicate remove 帧
     - **关键 byte 语义契约**：跨节点 replicate 的 groupBytes 必须与 bucket 物理存储（padding 后）byte-for-byte 100% 相等（避免 P11 HOTFIX length assertion 回归 FAIL，c10 Gatekeeper 实锤 ColumnarStore padding 代码 0 字符改）。
  3. **Meta 变动 replicate（覆盖 R7 3 TR）**：
     - `TableMetaStore.saveMeta/deleteMeta`：P10 saveToRegistry 成功后 → `replicator.replicateRegistryMetaKey(tableMetaPath(entityClass), metaBytes, ++tableMetaVersion, peerIds)`
     - `ColumnRegistry.allocateId/markDeleted`：变动后 → replicateRegistryMetaKey（columnRegistryPath(entityClass), metaBytes, ++colMetaVersion）
     - `RowIdSequenceStore.reserveRange` + `EqIndexMetaStore.save/remove`：变动后 → 各自 meta 路径 replicate，version 单调递增。
- **Local TRs**：
  - **TR-T4-R1~R4 [rule]（R4 列 4 TR）**：`P12_ColumnReplicateTest` 4/4 PASS——c01 putValue→replicate→对端 getValue bytes 相等；c02 乱序 2 帧旧值晚到 → 对端保留新值；c03 removeValue→对端 getValue=null 且 Registry path !exists；c04 deleteColumnHard 清 100 rowId → 对端 100 rowId 全部 null 且 100 列 Registry leaf 全部 !exists。
  - **TR-T4-R5~R8 [rule]（R5 组 4 TR）**：`P12_GroupReplicateTest` 4/4 PASS——g01 putFieldGroup roundtrip；g02 putCompositeGroup length=8（padding 8B 后 B 节点 bytes.length==8 断言 PASS）；g03 removeFieldGroup→对端 null；g04 节点 restart replay 100 组帧，B 节点 100 组与 A 节点 byte-for-byte 相等。
  - **TR-T4-R9~R11 [rule]（R7 元 3 TR）**：`P12_MetaReplicateTest` 3/3 PASS——m01 TableMeta saveMeta→对端 meta version 单增且 bytes 一致；m02 ColumnRegistry allocateId/markDeleted→对端 ColumnRegistry 一致；m03 RowIdSeq.reserveRange + EqIndexMeta.save→对端单增 version 一致。
- **Status**：pending
- **Blocked By / Unblock Condition**：T3 completed
- **Completion Evidence**：（R1~R11 12/12 PASS 行号；c10 B2 ColumnarStore + B3 TableMetaStore 等 facade 方法数基线断言 PASS 片段；deleteColumnHard 清 100 leaf 断言日志）

---

## Task 5：正确性专项 ≥22 tests + SplitBrain + Heartbeat + c10 NG-3 Sync Gatekeeper 25 assertions + R12 SyncOff 回退

- **AC Coverage**：R8（Split-brain 高 version 赢最终一致 ≤1s 且 overwrite count≥1）、R9（心跳 3 跳失效切 IDLE，恢复 2 跳回 FOLLOWER）、R10（c10 Gatekeeper 25 assertions A6+B6+C5+D4+E4 合跑全过）、R12（sync=off 回退 100%=P11 基线）、以及 P12 专项 12 tests（R3行4 + R4列4 + R5组4）独立跑 0F0E、Subtree 4 TR（R6）独立、BackwardCompat 1（R2）独立 = 合计 ≥ 22 tests。
- **Dependencies**：T4 completed（行/列/组/Meta 4 类 hook 接入完成后才能 capture facade 签名基线）
- **Priority**：High
- **Scope**：
  1. **专项 correctness 12 tests（T3/T4 TR 独立复跑，避免接入时 capture 过早）**：
     - P12_RowReplicateTest 4 / P12_ColumnReplicateTest 4 / P12_GroupReplicateTest 4 = 12 tests，必须独立 `mvn -Dtest=P12_*ReplicateTest test` 12/12 0F0E 实锤。
  2. **R8 SplitBrain Test**：
     - 2 节点 A（serverId=2）B（serverId=3），同 key rowId=42，A put `name="A"` version=1；B 同时 put `name="B"` version=1（同 version）→ A 收到 B replicate frame 时 compare logicalTs（B.localSeq>A.localSeq → B 赢）；A 本地 CAS 回填 overwrite；1s 后断言 A 与 B 的 payload 字节完全一致；`syncPeerOverwrites(A, B) + syncPeerOverwrites(B, A) ≥ 1` 至少一方计数≥1。
  3. **R9 Heartbeat Test**：
     - 默认心跳 500ms；模拟对端 socket 假死（block ServerSocketChannel 不读）→ Leader 连续 3 跳（≥1500ms）心跳无响应 → peer state 从 FOLLOWER → IDLE；解除 block → 2 跳（≤1000ms）内心跳 ACK → state IDLE → FOLLOWER。
  4. **R6 Subtree 4 TR + R2 BackwardCompat 1 TR**：独立复跑 T2 2 个 test 类，确保 5/5 PASS（避免 T2 通过后接入 hook 破坏通道语义）。
  5. **R12 SyncOff Fallback Test**：
     - `-Dp2p.orm.sync.mode=off` JVM 参数下，new DsDatabaseLocal + new ColumnarStore → 反射 assert replicator 字段 == null；随后跑 P11 14 专项（P11_RowDataTest 4 / P11_ColumnarDataTest 5 / P11_DataBackendBasicTest 3 / P11_DataKeySpaceTest 1 / P11_DataGatekeeperTest 1 = 14）+ ColumnarStoreTest 8 = 合计 22 tests 0F0E；行为 100%=P11 收官基线字节级等价。
  6. **⭐ c10 NG-3 Sync Gatekeeper（核心 — 25 assertions 5 防线，A6+B6+C5+D4+E4=25）**：
     - **防线 A（6 assertions，修饰符钉死）**：A1 Replicator 字段 replicator ∈ DsDatabaseLocal → `Modifier.isPrivate(mod) && Modifier.isFinal(mod)`；A2 Replicator 字段 ∈ ColumnarStore → private final；A3 AbstractRegistryReplicator 内嵌类 ReplicatorImpl class → static final；A4 RowDataBackend/ColumnDataBackend/GroupDataBackend 3 内嵌类（P11 已交付 0 改）→ static final；A5 AbstractRegistryReplicator ≥5 protected helper 方法；A6 所有 sync helper（syncPeerState 等 5）→ public static。
     - **防线 B（6 assertions，6 facade 方法数基线）**：B1 DsDatabaseLocal public 方法数 ∈ [26,34]（P11 baseline +-4 窗口，只允许 package-private 新增）；B2 ColumnarStore ∈ [22,30]；B3 TableMetaStore ∈ [18,26]；B4 ColumnRegistry ∈ [12,20]；B5 RowIdSequenceStore ∈ [8,14]；B6 EqIndexMetaStore ∈ [10,18]。
     - **防线 C（5 assertions，RegistryMetaPaths 命名钉死）**：C1 SYNC_ROOT 常量值 == `"/orm/sync"`；C2 RegistryMetaPaths public static final String 常量数 ≥ 15；C3 peerId 16hex 格式正确（syncPeerState(1L) contains `"0000000000000001"`）；C4 syncTx 段 txId 32hex 格式正确；C5 grep `"/orm/sync"` src/main/java → 仅 RegistryMetaPaths.java SYNC_ROOT 行 1 处命中。
     - **防线 D（4 assertions，关键签名存在钉死）**：D1 AbstractRegistryReplicator 含 `replicateKey(String, byte[], long, long[])` → signature exact match；D2 含 `replicateRegistryMetaKey(String, byte[], long, long[])`；D3 含 `syncPeerSubtree(InetSocketAddress, String)`；D4 applyRegistryFrame 接收方法含 `(magic, registryPath, version, payload)` 参数名。
     - **防线 E（4 assertions，Replicator 构造器 + method 数）**：E1 AbstractRegistryReplicator 2 种构造器（File,MetaMode）+（File,MetaMode,Map）存在；E2 3 个 replicate 方法数 exact（不多不少，防多余暴露）；E3 构造器 MetaMode=YAML 时 new Replicator 返回值 == null（短路实锤）；E4 构造器 props 含 sync=off → 返回值 == null。
     - c10 测试类 = `P12_SyncGatekeeperTest`，`@Test c10_ng3_sync_gatekeeper`，单测内合计 25 assertions，必须 25/25 0F0E；失败时打印防线编号便于定位。
- **Local TRs**：
  - **TR-T5-R1 [rule]**：`mvn -pl p2p-db -Dtest=P12_RowReplicateTest,P12_ColumnReplicateTest,P12_GroupReplicateTest,P12_SyncSubtreeTest,P12_SyncBackwardCompatTest,P12_SplitBrainTest,P12_PeerHeartbeatTest,P12_SyncOffFallbackTest test` → Tests run ≥ 12+4+1+1+1+22 = 41 ≥22，Failures: 0, Errors: 0。
  - **TR-T5-R2 [rule]**（R8 SplitBrain）：splitBrain 1s 最终一致断言 PASS，syncPeerOverwrites count ≥ 1。
  - **TR-T5-R3 [rule]**（R9 Heartbeat）：3 跳 FOLLOWER→IDLE + 恢复 2 跳→FOLLOWER 状态切换时序断言 PASS。
  - **TR-T5-R4 [rule]**（R12 Fallback）：sync=off 下 22 tests 0F0E 且 reflect replicator == null。
  - **TR-T5-R5 [rule]**（R10 c10 Gatekeeper）：`mvn -pl p2p-db -Dtest=P12_SyncGatekeeperTest test` → Tests run: 1, 内部 assertions 25/25 PASS，FAIL=0。
- **Status**：pending
- **Blocked By / Unblock Condition**：T4 completed
- **Completion Evidence**：（T5-R1 Tests run N≥22 Failures=0 行号；T5-R2 overwrites count 值 + 最终一致 bytes[] hash；T5-R3 状态机切换时序日志；T5-R4 reflect null 断言 + 22 tests PASS；T5-R5 c10 25/25 PASS 每项防线编号日志）

---

## Task 6：全量 JV 合跑 ≥78 tests 0F0E（P10 基线 + P11 基线 + P12 专项 + P7 核心回归 + 列回归）

- **AC Coverage**：R11（P10 38 + P11 64 去重 ≥78 tests 全部 0F0E，旧 regression 零破坏）
- **Dependencies**：T5 completed（c10 25 assertions + 正确性专项 ≥22 通过后才合跑，避免浪费时间合跑错误代码）
- **Priority**：High
- **Scope**：
  1. **合跑命令（p2p-db 模块，默认 HYBRID 模式）**：
     ```
     mvn -pl p2p-db -Dtest='P10_RowMetaTest,P10_ColumnMetaTest,P10_MetaBackendPerfTest,P11_DataKeySpaceTest,P11_DataBackendBasicTest,P11_RowDataTest,P11_ColumnarDataTest,P11_DataGatekeeperTest,ColumnarStoreTest,ColumnarStoreCompressionTest,ColumnarStoreLayoutTest,Orm_P7_Tgt2_Handler_6_Test,DsRegistry_P7_Tgt3_HandlerTest,DsRegistryExtAttrs_P7_Tgt5_HandlerTest,DsRegistryExtAttrs_P7_Exts_Test,P12_RowReplicateTest,P12_ColumnReplicateTest,P12_GroupReplicateTest,P12_SyncSubtreeTest,P12_SyncBackwardCompatTest,P12_SplitBrainTest,P12_PeerHeartbeatTest,P12_SyncGatekeeperTest,P12_SyncOffFallbackTest' test
     ```
  2. **合跑门槛统计**（按实际 classpath 命中 @Test 数，Failures/Errors=0 即可，N 不达标需解释并追加命令）：
     - P10 专项：15 tests（RowMeta 6 + ColumnMeta 8 + MetaBackendPerf 1）
     - P11 专项：14 tests（KeySpace 1 + BackendBasic 3 + RowData 4 + ColumnarData 5 + DataGatekeeper 1）
     - 列回归：18 tests（ColumnarStore 8 + Compression 4 + Layout 6）
     - P7 核心正确性：~32 tests（Tgt2 12 + Tgt3 7 + Tgt5 7 + Exts 6 或实际命中值，≥24 即 PASS）
     - P12 专项：~22 tests（Row 4 + Col 4 + Grp 4 + Subtree 4 + Backward 1 + Split 1 + Heart 1 + Gatekeeper 1 + Fallback 22/实际）
     - **合计保守：15+14+18+24+22 = 93 ≥ 78**（超过门槛 15 项，满足 R11 硬门槛）
  3. **日志保存**：输出 Tee 到项目根 `p12-joint-verification-hybrid-2026XXXX.log`；如 sync=off 独立跑，另存 `p12-joint-verification-sync-off-2026XXXX.log`。
- **Local TRs**：
  - **TR-T6-R1 [rule]**：合跑命令 exit code = 0，`Tests run: N, Failures: 0, Errors: 0, Skipped: 0`（或 Skipped 仅 perf 类，属 category 过滤正常），N ≥ 78。
  - **TR-T6-R2 [rule]**：逐项检查 P10 15 / P11 14 / 列回归 18 / P7 核心 ≥ 24 四类分项，每一类单独 tests PASS 数 = 类内 @Test 数（无 FAIL）。
  - **TR-T6-R3 [rule]**：日志落盘路径存在且 size > 10KB（非空，含分项 PASS 片段）。
- **Status**：pending
- **Blocked By / Unblock Condition**：T5 completed
- **Completion Evidence**：（合跑 BUILD SUCCESS Tests run N≥78 行号；P10/P11/列回归/P7 分项各自 Pass 片段；日志路径 size 确认）

---

## Task 7：性能专项 2 tests（U1 2PC 写入 P99 ≤ 1.50× / U2 冷启动对齐 ≤ 0.90× binlog）

- **AC Coverage**：U1（2PC prepare→commit P99 ratio ≤ 1.50 → 得分≥2）、U2（冷启动 REGISTRY_SUBTREE vs Binlog 重放 ratio ≤ 0.90 → 得分≥2）
- **Dependencies**：T6 completed（正确性 ≥78 tests 0F0E 后才测性能，避免测错误代码浪费时间；性能回归不阻塞 correctness 基线通过，但 U1/U2 打分 ≥1 才能 P12 VERIFIED CLOSED）
- **Priority**：Medium
- **Scope**：
  1. **P12_PerfReplicateTest（U1 小对象写入 P99，1 @Test @Category(PerfTest.class)）**：
     - 双 tmp 根对比：`SingleNodeRoot`（P11 收官 baseline，无 replicate hook = replicator=null）vs `Replicated2NodeRoot`（2 JVM 对端，localhost 100ms RTT 延迟注入用 Thread.sleep 模拟）。
     - 场景：Row put 128B × 100 行 + Column put 64B × 100 行 = 200 次 2PC prepare→本机 CAS 写；warmup=500，N=2000 正式取数，**同 JVM 跑 5 轮，取 P99 均值**（消除 Windows CFS tick 抖动）。
     - 计算 `ratio = Replicated2NodeRootP99Mean / SingleNodeRootP99Mean`。
     - mvn 软断言 `ratio ≤ 1.55`（放宽 5% 过 page-cache 噪声，U1 rubric 打分按严格 1.50 阈值）。
  2. **P12_PerfColdBootTest（U2 冷启动对齐，1 @Test @Category(PerfTest.class)）**：
     - 准备数据：1000 表 × 每表 100 行 = 10 万 leaf 元+值；用 YAML 模式（binlog baseline）+ HYBRID + sync=on（REGISTRY_SUBTREE baseline）两套独立环境各自写满。
     - **重启 JVM 3 次**（@Fork(3) 或独立 ProcessBuilder 启动 3 次）：每次启动 → new DsDatabaseLocal → new Replicator → `syncPeerSubtree` 拉 4 子树直到 checksum 对端一致；记录冷启动总耗时 ms（从 new 进程 → 4 子树 checksum 一致）。
     - 计算 `ratio = RegistrySubtree3RoundMean / BinlogReplay3RoundMean`；mvn 软断言 ≤ 0.95（放宽），U2 rubric 打分按严格 0.90。
  3. 所有 perf test 用 `@Category(PerfTest.class)`，默认 `mvn test` 不跑，必须显式 `-P perf` 或 `-Dtest=P12_*PerfTest` 触发。
- **Local TRs**：
  - **TR-T7-U1 [rubric, Scale 0-2]**：U1 2PC 写入 P99 ratio 打分——2=ratio≤1.50；1=1.50<ratio≤2.00；0=ratio>2.00。**Pass Threshold ≥ 1**。Evidence：P12_PerfReplicateTest stdout 5 轮 P99 打印行 + ratio 均值行。
  - **TR-T7-U2 [rubric, Scale 0-2]**：U2 冷启动对齐 ratio 打分——2=ratio≤0.90；1=0.90<ratio≤1.00；0=ratio>1.00。**Pass Threshold ≥ 1**。Evidence：ColdBootPerfTest 重启 3 次各自耗时 + Mean 对比打印行。
  - **TR-T7-R1 [rule]**：mvn 软断言通过（U1 ratio≤1.55 且 U2 ratio≤0.95），否则 blocked（返工 T1~T5 性能优化，但不允许动 P10/P11 交付代码 0 字符改）。
- **Status**：pending
- **Blocked By / Unblock Condition**：T6 completed
- **Completion Evidence**：（U1/U2 各自打分 + 理由 + 5 轮/3 轮数值打印行；TR-T7-R1 BUILD SUCCESS）

---

## Task 8：NeatFreak 三层知识同步（AGENTS / 项目记忆 / Roadmap 文档，Workflow Fidelity 合规）

- **AC Coverage**：无直接 spec AC，但满足 Spec Mode Review 阶段的三层知识无冲突 + Durable 三件套（AGENTS/current-state.md/changes resource）归档要求（Workflow Fidelity 打分必要条件）
- **Dependencies**：T7 completed（JV ≥78 PASS + 性能 ≥1 打分通过后才归档，避免写错误结论）
- **Priority**：Low（但 Review 开始前必须 completed = PENDING，否则不能进 Review）
- **Scope**（三层受众分离，严格合并改旧不追加相对词，绝对日期 2026-09-21 → 2026-10-20）：
  1. **AGENTS.md**（Agent 执行层规则）：P11 Invariant 段下追加「P12 Invariant 2 组」：
     - 组 1（命名+通道）：① `/orm/sync` 4 常量 + 5 typed helper（RegistryMetaPaths 零外泄 grep 1 处声明）；② DsRegistrySyncFrame magic=0x52 向后兼容；③ 双通道（DB_NODE_REPLICATE_FRAME 增量 + REGISTRY_SUBTREE_LIST/PUT_BATCH 全量 64KB/批 4 子树）。
     - 组 2（正确性+性能）：① c10 NG-3 Sync Gatekeeper 25 assertions（A6+B6+C5+D4+E4=25）；② Split-brain 高 version+logicalTs 赢最终一致 ≤1s；③ 心跳 3 跳失效 IDLE；④ 行4+列4+组4 专项 ≥12 PASS；⑤ U1 ratio=X.XX ≤ 1.50（填实测值如 1.32）；⑥ U2 ratio=Y.YY ≤0.90（填实测值如 0.83）；⑦ R11 JV ≥ 实测 93 tests 0F0E。
  2. **project_memory.md（Agent 记忆层）**：
     - Context 追加「P12 已闭环：2 节点对称同步，2 通道，magic=0x52，14 AC（12 rule+2 rubric）通过」；
     - Hard Constraints 追加 NG-4：`/orm/sync` 命名段仅 RegistryMetaPaths.java 1 处命中，c10 钉死；
     - Engineering Conventions 追加：① Replicator MetaMode=YAML / sync=off → 构造器短路 null；② applyRegistryFrame 幂等 CAS 语义 = version 高赢，低者 syncPeerOverwrites + WARN；③ 全量顺序 row→column→dbs→data 4 子树 per-subtree checksum sha1；
     - Lessons Learned 追加 3 条：① 跨节点 replicate payload 必须与 bucket 物理字节 padding 100% 一致（P11 HOTFIX 契约延续，否则列回归 length FAIL）；② deleteColumnHard 跨节点清所有 rowId 列 leaf 必须 for 循环内逐条 replicate remove（整表清帧丢包会留 orphan leaf）；③ Split-brain overwrite count 必须落 syncPeerOverwrites 可数 leaf（运维可观测 ≠ 日志打印）。
     - P13 候选方向同步：OQ3 完整 2PC MVCC / OQ4 Bloom+批量 / OQ5 Linux 复核 / OQ6 3+节点 Raft（四方向 + 建议先 OQ3 或 OQ5）。
  3. **docs/dsdb-design-status-and-roadmap.md（人类可读路线图+代码索引）**：
     - §六 推进路线图 P2 行追加：「✅ P12 OQ2 跨节点 2 节点 DML 同步（2 通道；magic=0x52；14 AC 12/12 rule + U1=1.XX U2=0.YY；JV 实测 ≥ N tests 0F0E；c10 25 assertions）」
     - §七 代码索引追加 5 条：
       - `RegistryMetaPaths SYNC 段：/orm/sync 4 const + 5 helper → RegistryMetaPaths.java#L345-L420`（估行号，完成后填实）
       - `AbstractRegistryReplicator 双通道 replicateKey/syncPeerSubtree → AbstractRegistryReplicator.java#L40-L220`
       - `DsDatabaseLocal 行 replicate hook（4 入口）→ DsDatabaseLocal.java#L1460-L1770`
       - `ColumnarStore 列+组 replicate hook（9 入口 + deleteColumnHard 清）→ ColumnarStore.java#L160-L630`
       - `c10 Sync Gatekeeper 25 assertions → P12_SyncGatekeeperTest.java#L50-L380`
- **Local TRs**：
  - **TR-T8-R1 [rule]**：grep 相对时间「今天/最近/today/recently/刚刚」在 AGENTS.md / project_memory.md / roadmap.md 三个文件 → 命中数 = 0（全部用绝对日期 2026-09-21/2026-10-20）。
  - **TR-T8-R2 [rule]**：三层交叉一致性——AGENTS.md 的「U1 ratio X.XX」= project_memory.md 的 U1 实测值 = roadmap §六 P2 行的 U1 数值（X.XX 三文件同一；U2 Y.YY 三文件同一；JV N tests 三文件同一）。
  - **TR-T8-R3 [rubric, Scale 0-2]**：Workflow Fidelity 归档完整性。2=3 文档全部改完且数值交叉一致 + roadmap §七 追加 5 条代码索引路径真实可点击；1=少 1 处文档或索引但核心 14 AC 覆盖率描述齐全；0=少 2+ 处或存在数值矛盾（如 AGENTS U1=1.2 roadmap 写 1.5）。**Pass Threshold ≥ 1**。
- **Status**：pending
- **Blocked By / Unblock Condition**：T7 completed
- **Completion Evidence**：（T8-R1 grep 0 命中输出；T8-R2 三文件三处数值 X.XX/Y.YY/N 三重对比 pass；T8-R3 打分+理由；三文档 diff --stat 行号）

---

## 验收完成标准（Spec Mode Implement Queue Drained）

```
all T1..T8 status ∈ {completed}
AND 无任何 pending/in_progress/blocked
AND 每 completed task 的 Completion Evidence 非空
AND 所有 rule TR 全 PASS
AND 所有 rubric TR（U1/U2/T8-R3）打分 ≥ 各自 Pass Threshold（≥1）
AND T6-R1/R2/R3 全成立（JV ≥78 tests 0F0E）
AND T5 c10 25 assertions 25/25 PASS
→ 方可进入独立 Review Phase（由 fresh agent 单独出 review.md）
```

> **取消任务的唯一合法方式**：用户在本会话中显式说「Tn 取消」并给出理由；任何 implementer 自行标记 cancelled 视为 Workflow Fidelity = 0（AC 覆盖破洞）。
>
> **跳步禁令**：未完成前序 T<n-1>.Status=completed 时，禁止对 T<n> 做任何代码写动作；违者 SP 阶段 Implement 步的所有改动全部 revert，Workflow Fidelity 直接 0 分。
