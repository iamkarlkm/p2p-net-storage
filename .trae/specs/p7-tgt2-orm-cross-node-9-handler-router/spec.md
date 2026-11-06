# P7 Follow-up Tgt-2 — ORM GenericManager 9 方法跨节点路由（Q2=C2 rowId本地自增 / Q3=C3 FNV tableId 不变式）Spec

## 1. Problem & Scope

### 1.1 用户目标（Verbatim 两次『执行开发，直到完成』+ P7 follow-up 剩余最高 ROI 项）
P7 Main A1/B1/C4 + Tgt-1 + ExtAttrs + Tgt-3/5 + Phase1 DB Meta Packed **全 VERIFIED CLOSED** 后，当前 follow-up 顺序（ROI 从高到低）：**① Tgt-2 ORM 跨节点路由**（当前 Session 执行） → ② Tgt-4 C4 LZ4 压缩。

用户问题 = B1 架构只实现了 ORM **本地短路 GenericManager(Class, DsDatabaseLocal, sId, pId)** + **客户端 DsDatabaseServer(cfg) 直连 TCP client excute**；**缺少 Handler 层**：`SA=12` 的进程收到 ORM PUT/GET 请求后，无法以 Handler.process() 形式路由到 `SB=42/PB=0` 实例本地 DsDatabaseLocal（4tuple=源/目 serverId+partitionId + globalRowId fallback）。

**本 Spec 只补 Handler 层 + 18 P2PCommand 区段 + 18 Model + 6 @Test 专项；B1 GenericManager 9 方法路由骨架（isDistributed() → distClient）、DsDatabaseServer.putTable/getTable 逻辑、Q2=C2/Q3=C3 不变式 **零修改**（只读不写）。

### 1.2 Non-Goals（本 Phase 绝不做）
- ❌ 不改 B1 Q2=C2 不变式：ORM rowId **永远本地自增**（DsHashMapIdAllocator 0..N），**绝对不嵌入 serverId/partitionId/DsIdLayout 三段位**；跨节点路由靠调用者持有 `DsDatabaseServer(sId,pId)` 实例，不靠 rowId 解 sid/pid → 如果违反直接 fail。
- ❌ 不改 Q3=C3 不变式：tableId 永远 `FNV-1a(className UTF-8 bytes)`；**无全局 tableId↔类名分布式映射中心**。任何要求全局 Registry 存 tableId↔className 的方案一律 Reject。
- ❌ 不新增 DsDatabaseLocal 公开 API（QueryWrapper 远程序列化已有）。
- ❌ 不碰 24304-24335 区段（Tgt-3/Tgt-5 在用），不碰 24001-24044 区段（DB_ENTITY_* 旧 client 在用）。
- ❌ 不开发 fan-out coordinator（跨 serverId 查询分散列），留给 Tgt-6。
- ❌ 7 大底层（DsFixedBucketStore/DsFreeRing/...）+ DsRegistry.java 零改；只在 Tgt3RegistryResolver（package-private resolveTarget 复用） + 新 18 枚举 + 新 18 Model + 新 9 Handler + 新 1 Test 文件。

## 2. Functional Requirements（Acceptance Criteria = rule 或 rubric）

### FR-1 (rule)：P2PCommand 9×2=18 条连续区段，严格锁死命令号
- REQ 区间 = **24336-24344**（9 条：PUT/GET/EXISTS/REMOVE/FIND_RANGE/GET_ONE/COUNT/LIST_IDS/SAVE_OR_UPDATE）
- ACK 区间 = **24436-24444**（对应 R_OK_ORM_*_ACK 9 条）
- 枚举命名 = `ORM_<VERB>`（REQ）/ `R_OK_ORM_<VERB>_ACK`（ACK），全部带 `P2PServiceCategory.DS_DB`
- 绝不与 EXTA_REMOVE_ALL_COMMENTS(24335)/R_OK_EXTA_REMOVE_ALL_COMMENTS_ACK(24435) 冲突（≤24335 是 Tgt-5；≥24336 是 ORM）
- **验收证据**：Grep P2PCommand.java 枚举名全部包含在 24336-24444 连续段内；专项测试 `int[] {ORM_PUT.getValue(), ORM_GET.getValue(), ...}` 全部连续+不重复+在区间。

### FR-2 (rule)：18 Model 类严格对齐 Tgt-3 Request 4tuple 双模式路由契约（4tuple 优先 + globalRowId fallback）
- 每个 Request 必须字段（同 Tgt-3 Model L1-L8）：
  ```java
  int sourceServerId, sourcePartitionId; // 源 SA/PA
  int targetServerId, targetPartitionId; // 目 SB/PB（-1 未指定）
  long globalRowId = 0L; // global DsIdLayout (serverId|partitionId|localRowId)，-1 目不指定时 fallback 路由
  ```
- 9 Request 业务字段（每个独立 public 类，p2p-core model 包）：
  1. OrmPutRequest：`String className, byte[] entityBytes, boolean withRelations, byte[] relations` → DsTableAdapter.toBytes()
  2. OrmGetRequest：`String className, long rowId, boolean withRelations`
  3. OrmExistsRequest：`String className, long rowId`
  4. OrmRemoveRequest：`String className, long rowId, boolean withRelations`
  5. OrmFindRangeRequest：`String className, byte[] queryWrapperBytes, int start, int end` → QueryWrapper 序列化复用 SerializationManager
  6. OrmGetOneRequest：`String className, byte[] queryWrapperBytes`
  7. OrmCountRequest：`String className, byte[] queryWrapperBytes`
  8. OrmListIdsRequest：`String className, byte[] queryWrapperBytes, int start, int end`
  9. OrmSaveOrUpdateRequest：`String className, byte[] entityBytes, boolean withRelations, byte[] relations`（等价 PUT，但 Handler 端必须 `id==0 → INSERT else UPDATE`，跟 GM.saveOrUpdate 语义对齐）
- 9 Ack 固定字段 + 业务字段：
  ```
  boolean ok;
  long rowId;         // PUT/SAVE_OR_UPDATE 新分配 rowId
  byte[] entityBytes; // GET/FIND_RANGE 首条/GET_ONE
  List<byte[]> entityList; // FIND_RANGE 批量
  long count;         // COUNT/listIds.size
  List<Long> ids;     // LIST_IDS
  String error;       // null=成功
  ```
- **验收证据**：Tgt-3 Request 模式字段顺序完全一致（L1..L8），Handler.process 开头可复用 `Tgt3RegistryResolver.resolveTarget(targetSP, globalId)` 抛 IAE 转 STD_ERROR 路径。

### FR-3 (rule)：9 Handler 类 100% 复用 Tgt3RegistryResolver.resolveTarget 双模式（零新 Resolver）
- Handler 路径 = `javax.net.p2p.server.handler.Orm*ServerHandler`，全部 `implements P2PCommandHandler`，L3 `P2PCommand getCommand()` 返回对应 ORM_* 枚举
- 开头路由契约（严格同 Tgt3 Resolver）：
  ```
  (targetServerId>=0 && targetPartitionId>=0) → 直接用；
  else if (globalRowId>0) → DsIdLayout.serverId(g)/partitionId(g) 解；
  else → P2PErrors.stdError(seq, INVALID_REQUEST, "双模式均失败")；
  ```
- 拿到目标 DsDatabaseLocal 后：
  1. **warm-up 守卫（classIdSet）**：`Class.forName(className)` → 如果 `tableIdToClass.get(tableId)==null`（= applyBinlogEntry 解码路径没注册）→ 强制 `dummy = clazz.getDeclaredConstructor().newInstance(); dummy.setId(1L); localDb.putEntity(dummy, ...); localDb.removeTable(clazz,1L,true);`（等价 C4 warm-up 规则）→ idsFile 建立 + tableId 注册；避免 findList(findRange) 返回 size=0
  2. OrmPut/SaveOrUpdate → `obj.load(ByteBuffer.wrap(entityBytes)); localDb.putTable(obj, withRelations);`
  3. OrmGet → `obj = localDb.getTable(clazz, rowId, Long.MAX_VALUE); obj.toBytes()`
  4. OrmExists → 直接 `localDb.getTable != null`
  5. OrmRemove → `localDb.removeTable(clazz, rowId, withRelations)`
  6. OrmFindRange/ListIds/Count/OrmGetOne → `new GenericManager<>(clazz, localDb, targetSId, targetPId)`（B1 本地短路构造）→ 调对应 `findList(wrapper).stream().map(toBytes).toList()` / `count(wrapper)` / `getOne(wrapper)`
- **验收证据**：T04 专项 SA=12 SB=42 warm-up saveOrUpdate+remove 后 findList size=2（之前 C4 bug=0）+ put 1 条 GET 深 equals 全部字段。

### FR-4 (rule)：Q2=C2 rowId 本地自增不变式——跨节点 PUT 返回 rowId = **target 实例 DsHashMapIdAllocator 下一个自增**
- 禁止 Handler 端对 PUT rowId 做 `toGlobalNodeId(rowId)` 包装；禁止对 entity.getId() 做任何位运算
- Ack.rowId 永远 = target localDb 的原始 long rowId（1..N 纯本地）
- 调用者要 globalId，必须自己在调用端用 `DsIdLayout.compose(targetSId, targetPId, ackRowId)` 做 ALU 转换
- **验收证据**：T01 专项断言 `rowId == 1`（SB/PB 新建本地第一张表首条分配 1，不是 DsIdLayout 三段高 22 位 SB=42）；**绝对不得 rowId >> 40 != 0**（断言 fail）

### FR-5 (rule)：Q3=C3 tableId=FNV-1a(className UTF-8 bytes) 不变式——Handler 端绝不查注册表、绝不分配全局 tableId
- classIdSet 注册（warm-up）路径必须走 `localDb.putEntity → createMeta → FNV-1a(className)`，禁止 Handler 自己手动 `tableIdToClass.put(randomId, clazz)`
- 跨节点 OrmPutRequest 里 className 必须是 Class.getCanonicalName 完整字符串；禁止用 short name / alias
- **验收证据**：T02 专项 Grep Handler 代码无任何 `tableIdToClass.put(`（除了 createMeta 内部调用外），PUT 后 SB 实例 DsDatabaseLocal.tableIdToClass.get(FNV("...TestUser")) == clazz。

### FR-6 (rule)：双模式路由冲突必须统一 STD_ERROR
- 场景 A：targetSP=(-1,-1) 且 globalRowId=0 → **双模式均失败**，Handler.process 返回 `P2PCommand.STD_ERROR`，payload 带 "missing target routing info"
- 场景 B：globalRowId>0 解出的 sid/pid 不在 Tgt3RegistryResolver INSTANCES TEST_INJECTS → STD_ERROR
- 场景 C：Class.forName(className) 抛 CNFE → STD_ERROR "class not found"
- **验收证据**：T03 专项三个场景全部 Ack.command == STD_ERROR。

### FR-7 (rule)：跨节点 QueryWrapper/UpdateWrapper 序列化复用 SerializationManager，不新增 ORM 专用 codec
- OrmFindRangeRequest.queryWrapperBytes = SerializationManager.serialize(wrapper)
- Handler 端反序列化：`QueryWrapper<?> w = (QueryWrapper<?>) SerializationManager.deserialize(bytes)`
- 失败抛 IAE → 转 STD_ERROR
- **验收证据**：T05 专项 `wrapper.eq("name","Alice").orderBy("id",true)` → 远端 findRange 2 条，byte[] 还原后实体 fields 全匹配。

### FR-8 (rule)：withRelations 传递不丢（DbEntityRelationsCodec 复用）
- Request.relations bytes 非空 → Handler 端 `DbEntityRelationsCodec.apply(obj, relations)`
- GET/FIND_RANGE Ack.relations 字段必须一并返回给客户端（OrmGetResponse/OrmFindRangeResponse 每条都带 relations）
- **验收证据**：T05 专项（2 条关联），远端 withRelations=true 返回 relations!=null 长度正确。

### FR-9 (rule)：命名空间/区段锁死硬断言
- 本 Tgt-2 新增 18 命令号绝不溢出到 24000-24335（旧 DB_* + Tgt-3 + Tgt-5 区段）
- Tgt-5 24335 EXTA_REMOVE_ALL_COMMENTS + ORM_PUT 24336 + R_OK_ORM_PUT 24436 必须严格连续
- 24336-24344 REQ 每编号比上一条 +1；24436-24444 ACK 每编号 +1
- **验收证据**：专项断言 `ORM_PUT=24336 / ORM_SAVE_OR_UPDATE=24344` 递增 8；ACK 同理。

### FR-10 (rule)：零 7 大底层修改 + 零 DsRegistry.java 修改 + 零 DsDatabaseLocal/GenericManager 路由骨架修改
- git diff（自 P7 Phase1 commit `53a382e39827`）针对：
  - DsFixedBucketStore/DsFreeRing/DsString/DsTypeFixedBucketStore/DsTypeString/DsData/DsHashMap **文件 size=0**
  - DsRegistry.java size=0
  - DsDatabaseServer.java / GenericManager.java size=0（只读不写 B1 骨架）
- **验收证据**：Final Review `git diff --stat 53a382e39827 <上述 9 文件>` 全部 0 行。

## 3. Non-Functional Requirements（rubric 2/2 通过阈值）

### NFR-1 (rubric, scale 0-2, threshold≥2)：路由正确性——双模式 4tuple/globalRowId fallback 覆盖 100% Handler
- Score 0 = 任意 Handler 路由不到目标；Score 1 = 只支持 4tuple；**Score 2 = 4tuple 先 + globalRowId fallback 都 OK + 双失败 STD_ERROR 统一**
- **锚点测试**：T02 4tuple路由 SA=12→SB=42 PUT→GET 实体相等；T03 globalRowId=DsIdLayout.compose(SB,PB,rowId) 走 fallback 路由 GET 成功；T03 双模式均失败返回 STD_ERROR。

### NFR-2 (rubric, scale 0-2, threshold≥2)：classIdSet warm-up 正确性——findList(findRange) size != 0（解决 C4 classIdSet bug）
- Score 0 = warm-up 缺失 findList size=0（C4 root cause）；Score 1 = warm-up 手动 new 第二个 DsHashSet（不共享 RAF 句柄）；**Score 2 = Handler 端 warm-up 强制走 `localDb.putEntity + removeTable` → createMeta 注册 tableId + idsFile 单实例共享；findList 确认为实际 put 条数**
- **锚点测试**：T04 saveOrUpdate×2 → OrmFindRangeRequest count=2。

### NFR-3 (rubric, scale 0-2, threshold≥2)：7零改 + 2不变式(Q2=C2/Q3=C3) 不破坏
- Score 0 = 破 Q2=C2 改 rowId 嵌入（Reject）；Score 1 = 改 7 大底层 1-5 行；**Score 2 = 所有 7零改/2不变式 git diff 全 0 + 专项断言 rowId 纯自增（高 22bit=0） + tableId=FNV(className) 无 Registry 查询**
- **锚点测试**：T01 rowId < (1L<<22)（高 22 bit 零 → 没嵌入 serverId）；T02 Handler 代码 grep zero `Registry.` 调用。

## 4. Constraints & Assumptions

- **Constraint C1（强制）**：7 大底层 + DsRegistry.java + B1 GenericManager.java + DsDatabaseServer.java **只读不写**。
- **Constraint C2（强制）**：ORM rowId 永远纯本地自增（Q2=C2），禁止 DsIdLayout 位运算改 rowId 本体；globalId 必须调用者在自己侧 compose/decompose。
- **Constraint C3（强制）**：tableId=FNV-1a(className UTF-8 bytes)，无 Registry 全局映射表（Q3=C3）。
- **Constraint C4（强制）**：命令号区段锁死 24336-24444，绝不占用 Tgt-3/5 24304-24335，绝不回退 24000 旧段。
- **Constraint C5（强制）**：Handler 双模式路由统一 Tgt3RegistryResolver.resolveTarget，零新 Resolver class。
- **Assumption A1**：QueryWrapper/UpdateWrapper 可序列化（SerializationManager.serialize 已支持），不引入新库。
- **Assumption A2**：JUnit 注入 Tgt3RegistryResolver.injectForTest(SA,PA,rA) + (SB,PB,rB)，跨节点 Handler.process = 本地调，不需要 TCP client。
- **Assumption A3**：测试实体 = UserEntity @DsTable(name="user_orm_tgt2") 带 @DsField id / name / age / createdAt 4 列，无 MVCC。
