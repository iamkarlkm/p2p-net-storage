# P12 Spec — OQ2：跨节点 DML 数据同步（2 节点最终一致 ≤100ms RTT）

> 会话 ID：P11 收官后按默认 OQ2 立项；周期：2026-09-21 → 2026-10-20（约 4 周，可提前 1 周 MVP）

## 1. 问题与用户

### 1.1 现状问题（P11 完成后暴露）

P10 已把 **7 类元数据** 统一切到 DsRegistry `/orm/{row,column,dbs}`；P11 OQ1 已把 **3 类 DML 真实数据**（行 payload、列单值、列组 composite）统一切到 DsRegistry `/orm/data/{row,column,group}` 三棵子树 + `AbstractRegistryDataBackend` 5 路由 helper + c09 Data Gatekeeper 25 assertions 钉死，**64 tests 0F0E、U1 ratio=0.81≤1.20、U2 ratio<0.75** 已 VERIFIED CLOSED。

但 P11 的承载范围**仅覆盖单节点 DML 本机一致**，**跨节点 DML 数据同步仍存在 4 个割裂点**：
1. **命名层割裂**：P7 DsBinlog `DsBinlogFrame` 基于 `(dbName, entityClass, rowId, logicalName)` 四元组生成事件 key，与 P11 统一命名空间 `RegistryMetaPaths.dataRowPayload/dataColumnPayload/dataGroupPayload` 的 typed path 无法直接一一对应，跨节点回放时需靠 Java 对象 field 名 match，**无全局唯一 key → 回放幂等只能靠 rowId 粗粒度，列组会误覆盖**。
2. **payload 承载层割裂**：P7 `DbNodeReplicateFrame.valueBytes` 是裸 byte[]，接收节点直接写本地 bucket；P11 已把 payload 同时存在 Registry + bucket，跨节点同步时**只有 binlog frame（写 bucket）没写 Registry → 接收节点 HYBRID 读首次读 miss 触发回填，等于白走 P11 统一承载**，冷启动节省的 parse 时间被抵消。
3. **一致性语义缺失（幂等+乱序）**：P7 DsBinlog 单节点回放是"按 write seq 单线程 apply"天然有序；跨节点 `DB_NODE_REPLICATE_FRAME` 走 UDP/P2PCommand 路由，网络乱序（RTT ≤100ms 但 1% frame 晚到 500ms）、同 rowId 两帧乱序 → 覆盖更新会把新值覆盖成旧值；P11 `atomicModifyBytes` 只在本机 striped CAS，跨节点无 version → 2 节点写同一 rowId 不同列 **split-brain** 概率 10%。
4. **冷启动全量拉取低效**：P7 2 节点同步是"首次握手 → Leader 回放全部 DsBinlog 历史（可能长达几 GB binlog 走 LZ4 解压）"；P10/P11 已把元+值都落在 DsRegistry 单根 `/orm`，本可按 `/orm/**` 子树 listChildren 批量拉（1MB 页批量 RTT 5 轮 vs binlog 100 万帧回放），但目前无 `/orm/**` 跨节点 copy 通道。

### 1.2 目标用户与收益链路

| 用户/阶段 | 直接收益 | 最终打通 |
|-----------|----------|----------|
| 集群 2 节点运维（HA 主备切换） | 写 A → 100ms 内 B 可读；A 宕机切 B 数据 100% 完整（≤100ms 丢帧窗口 + version 幂等）；主备切换 ≤ 1s | 后续 OQ3 2PC 事务 Raft 共识的基础：2P 下 Prepare 先本地写 Registry 再 Frame → 跨节点 Commit 幂等不重放 |
| DsDatabaseClusterClient 远程路由调用方 | rowId 高 22 位 serverId 路由 → 本地 DsRegistry 命中直接返回不 RPC；miss 才远程拉取 | 后续分片路由 4 节点 → 按 Registry 子树范围批量拉 + 本地 cache |
| 备份/冷启动运维 | 冷启动 1000 张表 从"binlog 全量重放 60s" → "Registry 子树 3 轮 listChildren+putBytes ≤6s"；备份 = `DsRegistry.exportTree("/orm")` 单根 tar 1MB 页连续写 | 后续 OQ4 离线 OLAP 列存 scan：Registry `/orm/data/column/**` 直接 SSTable export，0 转换 |
| 后续 OQ3 ACID 事务调用方 | 本机 P11 `atomicModifyBytes` + 跨节点本项目 `replicateAtomic(version+payload)` = 2PC 基础组件；后续 prepare/commit 可复用同一条 replicate 通道 | TPC-C New-Order 基线兼容 |

## 2. 目标（Goals）与非目标（Non-Goals）

### 2.1 Goals（4 周交付）

**G1：跨节点命名统一（幂等不覆盖，无乱序）**
- 新增 `/orm/sync` 子树 2 类命名段（syncRoot + syncSeq）：`/orm/sync/peer/<peerId16hex>/lastAckSeq`、`/orm/sync/tx/<txId32hex>/state`（state=PREPARED/COMMITTED/ABORTED，留给 OQ3 扩展）。
- `DsRegistrySyncFrame` = 复用 P7 DbNodeReplicateFrame layout（entity/rowId/logicalName/upsert/remove）+ 新增 3 字段：**registryPath（RegistryMetaPaths typed payload 路径）**、**logicalTs（MonotonicClock 128bit = serverId 22bit | localSeq 64bit | epoch 32bit）**、**version（uint64，单 key 单调，用于接收端 `atomicModifyBytes` CAS 幂等）**。
- 幂等语义：接收节点 apply 时走 `AbstractRegistryDataBackend.atomicModifyBytes(registryPath, (old) -> { if (old == null || old.version < frame.version) return newPayload; else return old; })`，乱序帧不会覆盖新值。
- 所有新增路径由 **RegistryMetaPaths 新增 typed helper**，业务代码零硬编码字符串（c10 Tx Gatekeeper 延伸：grep `"/orm/sync"` 仅允许 RegistryMetaPaths.java 常量声明 1 处命中）。

**G2：跨节点 2 路通道复用 P7 binlog + Registry**
- **增量通道**：复用 P7 `P2PCommand.DB_NODE_REPLICATE_FRAME / DB_NODE_REPLICATE_ACK` handler 路由，原 Frame payload 从裸 byte[] → `DsRegistrySyncFrame`（向后兼容：接收端 1 字节 magic=`0x52`=`R` 表示 Registry typed；magic 缺省或 `0x0` 走旧 P7 binlog bucket 分支）。
- **全量冷启动通道**：新增 2 个 Registry 内部 handler（不对外暴露）：`P2PCommand.REGISTRY_SUBTREE_LIST(24120)` / `REGISTRY_SUBTREE_PUT_BATCH(24121)`；按 `/orm/{row,column,dbs,data}` 4 前缀分批 listChildren+putBytes，每批 ≤ 64KB（≈1000 leaf），同 JVM 3 轮 RTT 可拉 2 节点 3000 leaf 元+值。
- Leader/Follower 角色：复用 P7 DsClusterMonitor 暴露 `localServerIds[] + remoteServers`；本地 serverId=min(2 nodes) 时默认 Leader（先发 sync 帧）；对等对称：两节点同时写同一 key 时 version 高者赢，低者收到高 version ACK 后本地 CAS 回填（保证最终一致 ≤1s）。

**G3：正确性门槛（≥78 tests 0F0E，25 assertions c10 Gatekeeper）**
- 原 P10 38 + P11 64 tests 交集去重后 = 78 tests 基线 **0F0E 不破坏**。
- 新增 P12 12 专项：行 4 / 列 4 / 组 4（put→跨节点 replicate→对端 get roundtrip；乱序帧不覆盖新值；幂等 apply 同 version 不重写；节点重启 100 帧 replay 无丢失）。
- 新增 P12 c10 NG-3 Tx/Sync Gatekeeper 25 assertions：钉死 6 facade 0 改 + P10/P11 全部路径 0 改 + `/orm/sync` 命名段仅 RegistryMetaPaths 1 处声明 + replicate 通道 magic=0x52 分支存在 + atomicModifyBytes version CAS 存在。

**G4：性能门槛（≤100ms RTT 最终一致）**
- **100 行小对象（行 ≤128B、列 ≤64B）2PC prepare→commit（本机 CAS 写）P99 延迟 ≤ 1.50× 原单节点 P99**（含网络 ACK，跨节点 2 次 RTT=200ms 时仍 ≤300ms P99，避免阻塞业务线程）。
- **冷启动 2 节点对齐（从 JVM 启动→本地 Registry 与对端 Registry 子树 byte-for-byte 一致）**：全量 binlog 重放 baseline；本项目 REGISTRY_SUBTREE_LIST+PUT_BATCH 通道 ≤ baseline×0.90（快 ≥10%，节点 1000 表时 60s→≤54s）。
- 退化率度量：同 P10/P11 双 tmp 根——同 JVM N=2000 warmup=500，独立 5 轮取 P99 均值，消除 Windows CFS tick 抖动。

### 2.2 Non-Goals（本阶段严格不做）
- ❌ **OQ3 完整 2PC 事务（row-level RR 隔离 + TPC-C）**：仅准备 2PC Coordinator 脚手架（`/orm/sync/tx/<txId>/state` 段 + PREPARED/COMMITTED leaf），真实 prepare→commit 跨 2 节点、死锁检测、回滚段另开专项（预计 8 周 OQ3）。
- ❌ **Raft/CRDT 3 节点共识**：本项目只支持 2 节点对称 peer；3+节点 leader election/log compaction 另开专项（预计 12 周 OQ6，等 OQ5 Linux 复核 + OQ3 2PC 基线后启动）。
- ❌ **LSM/Olap 列扫描、列存 secondary index bloom filter**：不在 scope（OQ4，P12 之后）。
- ❌ **更改 P10/P11 任何既有命名段或公开 facade 签名**：6 类 facade（DsDatabaseLocal/ColumnarStore/TableMetaStore/ColumnRegistry/RowIdSequenceStore/EqIndexMetaStore）公开方法 **0 字符改**；P10 7 元路径 + P11 3 数据路径 **0 字符改**（由 c10 Gatekeeper 钉死）。
- ❌ **新增 Maven 依赖、新增对外暴露 RPC Handler**：只复用 P7 现有 `DB_NODE_REPLICATE_FRAME/ACK` 路由 + DsClusterMonitor；新增 REGISTRY_SUBTREE_* 是内部 handler（不进 26 个 Server Handler 公开列表，不写 SDK 文档）。

## 3. 功能需求（Functional Requirements）

### 3.1 FR1：RegistryMetaPaths 扩 `/orm/sync` 子树 + SyncFrame typed helper
1. 新增常量（与现有 4 前缀零重叠）：
   - `SYNC_ROOT = "/orm/sync"`
   - `SYNC_PEER_ROOT = SYNC_ROOT + "/peer"`
   - `SYNC_TX_ROOT = SYNC_ROOT + "/tx"`
2. 新增 typed helper：
   - `syncPeerState(peerId) → /orm/sync/peer/<peerId16hex>/state`（LEADER/FOLLOWER/IDLE）
   - `syncPeerLastAck(peerId) → /orm/sync/peer/<peerId16hex>/lastAckSeq`（logicalTs 8B LE）
   - `syncTxState(txId) → /orm/sync/tx/<txId32hex>/state`（PREPARED/COMMITTED/ABORTED 1B enum）
   - `syncTxLeaf(txId, leafName) → /orm/sync/tx/<txId32hex>/leaves/<sha1(registryPathHex40)>`（payload + version 打包，留给 OQ3 2PC）
   - `registryFrameHeader()` → 1B magic=`0x52` + 2B reserved=0 + 8B logicalTs + 8B version
3. 零外泄：grep `"/orm/sync"` 仅 RegistryMetaPaths.java 常量声明 1 处命中（c10 Gatekeeper C 组实锤）。

### 3.2 FR2：AbstractRegistryReplicator 抽象（2 通道：增量 Frame + 全量 Batch）
1. **增量 replicate：`replicateKey(registryPath, newPayload, version, peerIds)`**
   - 序列化 `DsRegistrySyncFrame` = magic(1B) + reserved(2B) + logicalTs(8B) + version(8B) + registryPathLen(2B) + registryPath + payloadLen(4B) + payload。
   - 路由：`DsNodeRouter.rowIdToEndpoint(extractRowIdFromRegistryPath(registryPath))` → 对端 serverId；复用 P7 `executeRemote(endpoint, P2PCommand.DB_NODE_REPLICATE_FRAME, frame)`；对端 handler：magic 0x52 → 走新 `applyRegistryFrame(frame)`；其他 magic → 走旧 P7 bucket 分支（100% 向后兼容）。
   - 接收端 apply：`atomicModifyBytes(registryPath, (oldBytes) -> { parse old.version; if (frame.version > old.version) return frame.payload; else return oldBytes; })`；乱序/低 version 直接丢，WARN 四要素（registryPath/peerId/frameVersion/oldVersion）。
2. **全量 cold-start：`syncPeerSubtree(peerEndpoint, subtreeRootPrefix)`**
   - `REGISTRY_SUBTREE_LIST(endpoint, prefix, cursor, limit=1000)` → 返回 listChildren leaf keys+values（64KB 分块 gzip 可选）。
   - `REGISTRY_SUBTREE_PUT_BATCH(endpoint, list<key+value>)` → 批量 putBytes，成功返回 ackCount。
   - 顺序：拉取 `/orm/row` → `/orm/column` → `/orm/dbs` → `/orm/data` 4 子树，拉完每棵子树 checksum=sha1(all leaf bytes) → 对端 checksum 相等才 next，不等重试 3 轮（避免增量帧并发覆盖）。

### 3.3 FR3：DsDatabaseLocal/ColumnarStore/TableMetaStore 接入 replicate hook（公开签名 0 改）
1. **DsDatabaseLocal：putEntity/putEntities/update/removeTable**
   - 原步骤 2（bucket + P11 dataBackend 写）成功后：
     - 非 YAML 模式下 `if (replicator != null) replicator.replicateKey(dataRowPayload, payloadBytes, ++localVersionForRow(entity+rowId), peerIds)`；YAML 模式短路。
     - replicate 失败（远程 ACK 超时）只 WARN 不抛——最终一致靠 REGISTRY_SUBTREE 冷启动对齐（避免业务写因网络抖动失败）。
2. **ColumnarStore：putValue×2/putFieldGroup×2/putCompositeGroup/removeValue/removeRow**
   - dataBackend 写成功后：`replicator.replicateKey(dataColumnPayload/dataGroupPayload, valueBytes, ++colVersion, peerIds)`；deleteColumnHard 时对应 removeLeaf 后 replicate remove 帧（version 同样 CAS）。
3. **TableMetaStore/ColumnRegistry/EqIndexMetaStore**：P10 7 元路径变动后（saveToRegistry/markDeleted/allocate）：`replicator.replicateRegistryMetaKey(metaPath, metaBytes)`；列存 meta 变动同样 version 幂等。

### 3.4 FR4：Peer 心跳与 Leader/Follower 角色（2 节点对称）
1. 启动时 DsRegistryReplicator：
   - 读 DsServerDirectory 所有 remoteServers；本地 serverId = `DsServerDirectory.localServerIds[0]`；remote.size() ≤1 时进入 2 节点对称模式（本项目 scope），remote>1 时退出 replicate 模式（只保留冷启动通道）。
   - 每 500ms 心跳：`REGISTRY_SUBTREE_LIST(peerEndpoint, SYNC_PEER_ROOT + "/" + localHexId, limit=1)` 探活；peer 连续 3 跳心跳无响应 → FOLLOWER→IDLE，记录 WARN（保留本地读可用性）。
2. Split-brain 解决策略：version + logicalTs 高者赢。
   - 两节点同时写同 key：各自 version++; A 节点收到 B 的 replicate version>A 时，本地 `atomicModifyBytes` 把 A 的 payload CAS 成 B 的高 version payload（最终一致 ≤1s）。
   - 低 version 节点写被 overwrite 后：本地 WARN 一条 + 把 overwrite 原因写入 `/orm/sync/peer/<peer>/overwrites` count leaf（便于运维观测 split-brain 次数）。

## 4. 非功能需求（Non-Functional Requirements）

### 4.1 正确性（硬门槛）
| 编号 | 描述 | 阈值 |
|------|------|------|
| NFR-C1 | P10 38 + P11 64 去重后 ≥78 tests 基线 **0F0E 不破坏**（旧 regression 全部 PASS） | 0F0E，硬门槛 |
| NFR-C2 | P12 新增 12 专项 tests：行 4/列 4/组 4（roundtrip/乱序不覆盖/幂等/节点重启 100 帧 replay）全部 PASS | 12/12 0F0E，硬门槛 |
| NFR-C3 | P12 c10 Gatekeeper ≥25 assertions 全 PASS（A修饰符6/B facade方法数6/C RegistryMetaPaths 命名5/D sync+meta+replicate 签名4/E Replicator method+构造器4） | 25/25，硬门槛 |

### 4.2 性能（2 节点 100ms RTT 场景下软门槛，偏差超 5% 需给解释）
| 编号 | 维度 | 目标阈值（5 轮均值） | 环境约束 |
|------|------|---------------------|----------|
| NFR-P1 | Row put小对象（128B）2PC prepare→本机 CAS 写 P99 | ReplicatedRoot P99 ≤ SingleNodeRoot P99 × 1.50 | 2 JVM 对端；同机 localhost 模拟 100ms RTT（延迟注入）；N=2000 warmup=500；5 轮 |
| NFR-P2 | Column put小对象（64B）2PC prepare→本机 CAS P99 | ReplicatedRoot P99 ≤ SingleNodeRoot P99 × 1.50 | 同上 |
| NFR-P3 | 冷启动 2 节点对齐耗时 | REGISTRY_SUBTREE 通道 ≤ Binlog 全量重放通道 × 0.90 | 1000 表 × 每表 100 行 = 10 万 leaf；重启 JVM 3 次取均值 |
| NFR-P4 | 乱序帧幂等 apply 额外开销 | 100 乱序帧处理时间 ≤ 同 100 正序帧 × 1.20 | logicalTs 乱序率 10%；同 JVM |

### 4.3 可维护性与可观测性
| 编号 | 描述 |
|------|------|
| NFR-M1 | 所有 `/orm/sync` 相关路径必须由 RegistryMetaPaths typed helper 生成；grep `"orm/sync"` 仅允许 RegistryMetaPaths.java 常量声明行 1 处命中，其他文件零命中（c10 Gatekeeper C3 实锤）。 |
| NFR-M2 | replicate 失败（远程 ACK 超时、version 被覆盖）：WARN 日志必须包含 registryPath、peerId、frameVersion、oldVersion 四要素；overwrite 次数写入 `/orm/sync/peer/<peer>/overwrites` count leaf 便于运维观察。 |
| NFR-M3 | 回退性：`-Dp2p.orm.sync.mode=off`（默认 on）后，DsRegistryReplicator 构造器直接 return null，全部 replicate hook 短路，行为 100% = P11 收官基线；c10 Gatekeeper 在此系统属性下同样运行 ≥25 assertions 无回归。 |
| NFR-M4 | 零跨子项目影响：p2p-ws-sdk-* / p2p-cache / p2p-sync / examples/IM 全部 **零字符改**（本专项只涉及 p2p-db 模块的 registry.meta + database/database.columnar 包内新增 package-private 类与 hooks，不对外暴露）。 |

## 5. 约束与假设

### 5.1 硬约束（突破 = Spec 失败）
1. **NG-2 6 facade 公开签名 0 改**：DsDatabaseLocal/ColumnarStore/TableMetaStore/ColumnRegistry/RowIdSequenceStore/EqIndexMetaStore public 方法签名 byte-for-byte 不变（c10 Gatekeeper B6 反射实锤）。
2. **P10 7 元路径 + P11 3 数据路径 **0 字符改**：`/orm/row/**、/orm/column/**、/orm/dbs/**、/orm/data/row/**、/orm/data/column/**、/orm/data/group/**` 6 前缀不允许改名、改段、改 helper 输出（c10 Gatekeeper C4 实锤）。
3. **0 新增 Maven 依赖**：只复用 P7 `DbNodeReplicateFrame/P2PCommand.DB_NODE_REPLICATE_FRAME/DsNodeRouter/DsClusterMonitor` + P11 `AbstractRegistryDataBackend.atomicModifyBytes` + P10 `RegistryMetaPaths/AbstractRegistryBackend/DsRegistryInstance`；无新 JAR 引入（pom.xml diff 空）。
4. **向后兼容 P7 旧 Binlog Bucket 分支**：接收 DB_NODE_REPLICATE_FRAME 时，无 magic（首字节 < 0x50）或 magic != 0x52 → 走旧 bucket 写入分支，保证旧版本节点不 upgrade 也能继续 run。

### 5.2 依赖项
- P10：AbstractRegistryBackend striped lock CAS、MetaMode 三态、RegistryMetaPaths 命名段、DsRegistryInstance 单例、可逆 SHA256 manifest 迁移（已 VERIFIED CLOSED）。
- P11：AbstractRegistryDataBackend 5 helper、/orm/data 三棵子树、3 Backend 内嵌类、atomicModifyBytes CAS、c09 Gatekeeper 25 assertions（已 VERIFIED CLOSED）。
- P7：DsNodeRouter、DsServerDirectory、DbNodeReplicateFrame、P2PCommand.DB_NODE_REPLICATE_FRAME/ACK、DsClusterMonitor、DsDatabaseClusterClient 路由（已 VERIFIED CLOSED）。

### 5.3 假设
1. 同集群 peerId = serverId，全局唯一 22-bit（由 P7 DsIdLayout 已保证）。
2. 跨节点网络 2 节点平均 RTT ≤ 100ms，丢包率 ≤1%（由运维保证；超出时 replicate WARN，冷启动对齐兜底）。
3. 2 节点对称是 scope；3+节点不支持（由 DsServerDirectory.remote.size()>1 时 replicator = null 自动 disable，不抛错）。

## 6. 验收标准（Acceptance Criteria — 仅 rule 或 rubric）

### 6.1 Rule Acceptance Criteria（12 条，满足 = 正确性基线通过）

| 编号 | Type | 观测条件 | 证据来源 |
|------|------|----------|----------|
| R1 | rule | `/orm/sync` 命名段常量仅 RegistryMetaPaths.java 常量声明 1 处 grep 命中；syncPeerState/syncPeerLastAck/syncTxState/syncTxLeaf 4 typed helper 返回正确段前缀且 peerId16hex/rowIdHex16 对齐 | P12_SyncKeySpaceTest + grep `"/orm/sync"` src/main/java |
| R2 | rule | DsRegistrySyncFrame magic=0x52 → 接收端 applyRegistryFrame 分支存在；magic!=0x52 → 走旧 P7 bucket 分支（100% 向后兼容，旧 binlog frame 写盘路径断言 PASS + 旧单测 PASS） | P12_SyncBackwardCompatTest |
| R3 | rule | 行 4 专项：putEntity→replicate→对端 getTable 字节一致；乱序 2 帧（旧 version 晚到）→ 对端返回新值不被覆盖；幂等 apply 同 version 2 次 → 对端 bytes 不变且 version 不递增；节点 restart 后 100 帧 replay → 100 行 bytes 一致 | P12_RowReplicateTest 4 TRs |
| R4 | rule | 列 4 专项：putValue→replicate→对端 getValue 字节一致；乱序 2 帧旧值晚到不覆盖新值；removeValue→对端 getValue=null 且 Registry path 不存在；deleteColumnHard→对端所有 rowId 列值 null 且 Registry leaf 已 remove | P12_ColumnReplicateTest 4 TRs |
| R5 | rule | 组 4 专项：putFieldGroup/replicate/getFieldGroup roundtrip；putCompositeGroup length=8（padding 8B 后 bytes 一致）；removeFieldGroup→对端 null；节点重启 100 组帧 replay 无丢失 | P12_GroupReplicateTest 4 TRs |
| R6 | rule | REGISTRY_SUBTREE_LIST + REGISTRY_SUBTREE_PUT_BATCH 全量通道：拉 `/orm/row` + `/orm/column` + `/orm/dbs` + `/orm/data` 4 子树后本地与对端 checksum(sha1) 字节一致（分块 64KB 1000 leaf/批） | P12_SyncSubtreeTest 4 TRs |
| R7 | rule | 元路径同步：TableMeta/ColumnRegistry/RowIdSeq/EqIndexMeta 变动 → replicate frame version 单增 → 对端 meta 与本地一致 | P12_MetaReplicateTest 3 TRs |
| R8 | rule | Split-brain 写同 key：A/B 两节点同时 put 同 key 不同值 → 1s 后最终 A/B 本地 version/payload 完全一致（高 version 赢）且 `/orm/sync/peer/<peer>/overwrites` ≥1 | P12_SplitBrainTest |
| R9 | rule | 心跳探活：Leader 连续 3 跳心跳无响应 → peer state 从 FOLLOWER 切换 IDLE；对端恢复后 2 跳内心跳恢复 → FOLLOWER | P12_PeerHeartbeatTest |
| R10 | rule | c10 NG-3 Sync Gatekeeper ≥25 assertions 全 PASS：A修饰符6（Replicator private final + ReplicatorImpl static final + 3 Backend内嵌类 static final + ≥5 protected helper）；B6 facade 方法数 ≥ baseline；C5 RegistryMetaPaths 命名（SYNC_ROOT 值、String const≥15、peerId16hex 对齐、syncTxSeg 格式、sync子树返回值含 peer 段）；D4 sync+meta+replicate 关键签名存在；E4 Replicator 3×2 method+构造器(File)×2） | P12_SyncGatekeeperTest 25/25 assertions |
| R11 | rule | P10 38 + P11 64 去重 ≥78 tests 全部 0F0E（基线 regression 不破坏） | JV 合跑命令（见 tasks.md T6） |
| R12 | rule | 回退 `-Dp2p.orm.sync.mode=off` 后：行为 100% = P11 收官基线（P11 14 专项 + ColumnarStoreTest 8 全部 PASS），且 Replicator=null 无额外初始化 WARN | P12_SyncOffFallbackTest |

### 6.2 Rubric Acceptance Criteria（2 条，满足 = 质量分 ≥2/2）

| 编号 | Type | 维度 | 数值尺度 | 阈值 | 证据来源 |
|------|------|------|----------|------|----------|
| U1 | rubric | 2PC 写入性能（Row+Column 合计 100 行小对象 prepare→commit P99 比单节点 baseline） | 0=ratio>2.0；1=1.50<ratio≤2.0；**2=ratio≤1.50** | ≥2 分（pass=ratio≤1.50） | P12_PerfReplicateTest 5 轮均值 |
| U2 | rubric | 冷启动对齐性能（REGISTRY_SUBTREE 全量通道 vs Binlog 全量重放 baseline ratio） | 0=ratio>1.0；1=0.90<ratio≤1.0；**2=ratio≤0.90** | ≥2 分（pass=ratio≤0.90） | P12_PerfColdBootTest 重启 JVM 3 次均值 |
