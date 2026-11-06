# P7 Follow-up Tgt-2 — ORM 跨节点 9 Handler 路由 Tasks（9 atomic tasks TR → AC）

## 任务顺序

| ID | 任务 | 依赖 | AC 绑定 | 优先级 |
|---|---|---|---|---|
| 1 | P2PCommand 18 枚举（24336-24444 连续 9×2）| — | FR-1 / FR-9 | HIGH |
| 2 | 18 Model 类：9 Request（4tuple+globalRowId+业务字段）+ 9 Ack（ok/rowId/entityBytes/list/count/ids/error）| Task 1 | FR-2 | HIGH |
| 3 | 9 Handler 类：Orm*ServerHandler.process（Tgt3Resolver.resolveTarget 双模式 → target DsDatabaseLocal → warm-up classIdSet → localDb 路由 → Ack）| Task 2 | FR-3 / FR-4 / FR-5 / FR-6 / FR-7 / FR-8 | HIGH |
| 4 | GenericManager 分布式构造新增 1 个构造（Class, DsDatabaseServer, serverId, partitionId）复用现有 distClient；Tgt3RegistryResolver 增加 public 方法 resolveOrmTarget 复用 registry 双模式（不新建 Resolver）| Task 3 | FR-2 / FR-3 | HIGH |
| 5 | 测试实体 UserEntity（@DsTable id/name/age/createdAt 4 列）+ 测试工具 warmUp（GM.saveOrUpdate dummy + remove 建立 idsFile + createMeta）| Task 3 | FR-3 warm-up / FR-7 QW 序列化 | HIGH |
| 6 | 6 专项 @Test：T01 Q2=C2 rowId纯自增 / T02 4tuple路由 PUT→GET→EXISTS / T03 双模式失败→STD_ERROR / T04 classIdSet warm-up→findRange size=2 / T05 QW 序列化 withRelations / T06 globalRowId fallback 路由 GET | Task 5 | FR-1/2/3/4/5/6/7/8 全覆盖 | HIGH |
| 7 | 烟测 ≥58：-pl p2p-db 9 个测试类汇总 0F0E | Task 6 | 所有 FR + NFR 锚点 | HIGH |
| 8 | AGENTS Invariant 追加 10 条（Q2=C2/Q3=C3/24336区段锁死/双模式路由/warm-up/classIdSet/零改7底层/零改DsRegistry/0新Resolver/FNV tableId）+ current-state VERIFIED CLOSED Block | Task 7 | FR-10 / NFR-3 | MEDIUM |
| 9 | Brain context audit + distill proposal + session finish exit 0 + 独立 Review（7零改/2不变式/3 rubric score=2/2 锚点证据齐全）| Task 8 | NFR-1 NFR-2 NFR-3 =2/2 | MEDIUM |

---

## Task 1: P2PCommand 18 枚举（REQ 24336-24344 / ACK 24436-24444）
- **FR 绑定**：FR-1 / FR-9
- **Scope**：`p2p-core P2PCommand.java` L670-674 DATA_TRANSFER(21000) 前插入 18 条（Tgt-5 24335/24435 末尾之后立刻接 ORM 区段，保证连续）
- **TR 1.1 (rule)**：断言 18 个枚举全部存在，命名正确：
  ```
  ORM_PUT(24336, DS_DB)                        / R_OK_ORM_PUT_ACK(24436)
  ORM_GET(24337)                               / R_OK_ORM_GET_ACK(24437)
  ORM_EXISTS(24338)                            / R_OK_ORM_EXISTS_ACK(24438)
  ORM_REMOVE(24339)                            / R_OK_ORM_REMOVE_ACK(24439)
  ORM_FIND_RANGE(24340)                        / R_OK_ORM_FIND_RANGE_ACK(24440)
  ORM_GET_ONE(24341)                           / R_OK_ORM_GET_ONE_ACK(24441)
  ORM_COUNT(24342)                             / R_OK_ORM_COUNT_ACK(24442)
  ORM_LIST_IDS(24343)                          / R_OK_ORM_LIST_IDS_ACK(24443)
  ORM_SAVE_OR_UPDATE(24344)                    / R_OK_ORM_SAVE_OR_UPDATE_ACK(24444)
  ```
- **TR 1.2 (rule)**：`ORM_PUT.getValue()-EXTA_REMOVE_ALL_COMMENTS.getValue()==1` / `R_OK_ORM_PUT.getValue()-R_OK_EXTA_REMOVE_ALL_COMMENTS_ACK.getValue()==1` 严格连续
- **TR 1.3 (rule)**：枚举 9 个 REQ 差值 = 24344-24336 = 8（9 条）；ACK 差值 = 24444-24436 = 8；所有 P2PServiceCategory 必须 DS_DB
- **Verification**：`int[] code = Arrays.stream(values()).filter(c -> c.name().startsWith("ORM_")).mapToInt(P2PCommand::getValue).sorted().toArray(); Assertions.assertEquals(24336, code[0]); Assertions.assertEquals(24344, code[8]);`

---

## Task 2: 18 Model 类（9 Request + 9 Ack）p2p-core model 包
- **FR 绑定**：FR-2
- **Scope**：`p2p-core/src/main/java/javax/net/p2p/model/` 新建 18 个 public class
  - Request（9）：OrmPutRequest / OrmGetRequest / OrmExistsRequest / OrmRemoveRequest / OrmFindRangeRequest / OrmGetOneRequest / OrmCountRequest / OrmListIdsRequest / OrmSaveOrUpdateRequest
  - Ack（9）：OrmPutAck / OrmGetAck / OrmExistsAck / OrmRemoveAck / OrmFindRangeAck / OrmGetOneAck / OrmCountAck / OrmListIdsAck / OrmSaveOrUpdateAck
- **公共字段（所有 Request 必须按 Tgt-3 字段顺序，确保 resolveTarget 双模式一致）**：
  1. `int sourceServerId, int sourcePartitionId`（范围 0..SERVER_ID_MASK / 0..PARTITION_MASK）
  2. `int targetServerId, int targetPartitionId`（=-1 未指定，走 globalRowId fallback）
  3. `long globalRowId`（= 0 未指定，走 4tuple；4tuple 全 -1 + globalRowId=0 → STD_ERROR）
  4. `String className`（完整 Class.getCanonicalName()，禁止空串）
- **TR 2.1 (rule)**：OrmPutRequest / OrmSaveOrUpdateRequest 业务字段 = `byte[] entityBytes, boolean withRelations, byte[] relations`；Ack 必须返回 `long rowId`
- **TR 2.2 (rule)**：OrmFindRangeRequest / OrmListIdsRequest 带 `int start, int end`；OrmCountRequest / OrmFindRangeRequest / OrmGetOneRequest / OrmListIdsRequest 带 `byte[] queryWrapperBytes`
- **TR 2.3 (rule)**：OrmGetAck / OrmGetOneAck 字段 = `byte[] entityBytes, byte[] relations, boolean present`；OrmFindRangeAck = `List<byte[]> entityBytesList, List<byte[]> relationsList`；OrmCountAck = `long count`；OrmListIdsAck = `List<Long> ids`；所有 Ack 尾部带 `String error（null=成功）`
- **Verification**：mvn -pl p2p-core compile -DskipTests -q exit=0；专项测试 Handler 前能 new OrmPutRequest 9 参数。

---

## Task 3: 9 Handler 类（p2p-db handler 包）Orm*ServerHandler
- **FR 绑定**：FR-3 / FR-4 / FR-5 / FR-6 / FR-7 / FR-8
- **Scope**：`p2p-db/src/main/java/javax/net/p2p/server/handler/` 新建 9 个 public class（每个 implements P2PCommandHandler；getCommand() 对应枚举）
- **通用 process() 流程（所有 Handler 必须严格遵循，顺序不得变）**：
  ```
  1. cmd 匹配：if (wrapper.getCommand() != expectedReq) return P2PErrors.stdError(seq, ROUTING_HANDLER_MISMATCH)
  2. payload 强转：instanceof Orm*Request 失败 → INVALID_REQUEST STD_ERROR
  3. 路由：Tgt3RegistryResolver.resolveOrmTarget(req.targetSP, req.globalRowId)
     3a. 4tuple 优先：t=(req.targetServerId>=0 && req.targetPartitionId>=0) → (tSid,tPid)
     3b. fallback globalRowId>0 → DsIdLayout.serverId/partitionId 解
     3c. 都无 → 返回 STD_ERROR "missing target routing info"
  4. Class.forName(req.className)：CNFE → STD_ERROR
  5. warm-up classIdSet（只有 first ever put 走一次；可每次 saveOrUpdate dummy 幂等）：
     T dummy = clazz.getDeclaredConstructor().newInstance(); dummy.setId(1L);
     targetDb.putTable(dummy, false); targetDb.removeTable(clazz, 1L, false);
  6. 业务执行（各 Handler 各自）
  7. build Ack wrapper → return new P2PWrapper(R_OK_ORM_*_ACK, ackPayload)
  ```
- **业务路由映射（步骤 6）**：
  1. **ORM_PUT / SAVE_OR_UPDATE**：load entity = clazz.newInstance(); entity.load(ByteBuffer.wrap(entityBytes)); relations!=null → DbEntityRelationsCodec.apply(entity, relations); `long rowId = targetDb.putTable(entity, withRelations); entity.setId(rowId)` → Ack rowId
  2. **ORM_GET**：`obj = targetDb.getTable(clazz, rowId, Long.MAX_VALUE);` → if (withRelations) DbEntityRelationsCodec.encode(obj) → Ack present/entityBytes/relations
  3. **ORM_EXISTS**：`targetDb.getTable(clazz, rowId, Long.MAX_VALUE) != null` → Ack.present
  4. **ORM_REMOVE**：`targetDb.removeTable(clazz, rowId, withRelations)` → Ack.ok=返回值
  5. **ORM_FIND_RANGE**：`GenericManager gm = new GenericManager(clazz, targetDb, tSid, tPid); QueryWrapper w = SerializationManager.deserialize(qwBytes); List<T> list = gm.findList(w).subList(start, end);` → entityBytesList + relationsList
  6. **ORM_GET_ONE**：gm.getOne(wrapper) → entityBytes
  7. **ORM_COUNT**：gm.count(wrapper) → long count
  8. **ORM_LIST_IDS**：gm.list(wrapper).map(id) subList → ids
- **TR 3.1 (rule)**：Q2=C2 校验（Handler 代码中零 DsIdLayout.compose 调用除了步骤 3b globalRowId **解**）→ entity.setId(rowId) 后 rowId 不做位运算；Ack.rowId = 原始 long
- **TR 3.2 (rule)**：Q3=C3 校验（Handler 中 zero `Registry.` 调用；zero `tableIdToClass.put` 手动调用，只允许 localDb.putEntity → createMeta 内部注册）
- **TR 3.3 (rule)**：双模式失败统一 STD_ERROR，Handler 不抛 RuntimeException
- **Verification**：专项 T02-T04 全部通过。

---

## Task 4: Tgt3RegistryResolver 新增 public resolveOrmTarget（零新 Resolver）+ GenericManager 分布式构造小增强
- **FR 绑定**：FR-3 / FR-2
- **Scope**：Tgt3RegistryResolver.java（Locate: `javax.net.p2p.server.handler.Tgt3RegistryResolver`）新增方法，**不破坏原有 resolveRegistryTarget 签名**；GenericManager 保持 isDistributed()→distClient 不变，零改路由逻辑。
- **新增方法（Tgt3RegistryResolver.java）**：
  ```java
  public static DsDatabaseLocal resolveOrmTarget(int targetServerId, int targetPartitionId, long globalRowId)
      throws IllegalArgumentException {
      // 同 resolveRegistryTarget 双模式逻辑，只是返回 DsDatabaseLocal 不是 DsRegistry
      // 返回 INSTANCES.computeIfAbsent(srv_pid) 的 DsRegistry.localDbRef？No → 直接单独 new ConcurrentHashMap<srv_pid, DsDatabaseLocal> dbInstances + TEST_INJECTS
      // 注意：TEST_INJECTS 优先（生产用 dbInstances.computeIfAbsent）
  }
  public static void injectOrmForTest(int sId, int pId, DsDatabaseLocal db) { ... }
  public static void clearOrmForTest() { ... }
  ```
- **TR 4.1 (rule)**：零 new Resolver 类，只在同一个 Tgt3RegistryResolver 追加 2 个 map + 2 static 方法
- **Verification**：Handler 中 OrmPutServerHandler.process 调 `Tgt3RegistryResolver.resolveOrmTarget(req.targetServerId, req.targetPartitionId, req.globalRowId)` exit=0；T02 专项找到目标 DsDatabaseLocal。

---

## Task 5: 测试实体 UserEntity + warm-up 工具
- **FR 绑定**：FR-3 warm-up / FR-7 序列化
- **Scope**：`p2p-db/src/test/java/ds/UserEntity.java` + `p2p-db/src/test/java/ds/OrmTestHelper.java`
- **UserEntity 字段**：
  ```java
  @DsTable(name = "user_orm_tgt2")
  public class UserEntity extends DsTableAdapter {
    @DsField(indexed = true) String name;
    @DsField(indexed = true) int age;
    @DsField long createdAt;
    // getter/setter
  }
  ```
- **OrmTestHelper.warmUp(clazz, db)**：saveOrUpdate dummy(id=1L) + removeTable(id=1L)
- **TR 5.1 (rule)**：UserEntity 能通过 `EntitySchemaUtil.schemaOf(UserEntity.class)` 有 id/name/age/createdAt 4 列
- **Verification**：T01-T06 new UserEntity 成功。

---

## Task 6: 专项 6 @Test（Orm_P7_Tgt2_Handler_6_Test.java）
- **FR 绑定**：全覆盖 FR-1..FR-10 / NFR-1..NFR-3 = 2/2 锚点
- **@Test 清单**：
  1. **T01_CMD_Range_Q2=C2_rowIdLocalOnly**
     - TR：断言 18 ORM_* 枚举 24336-24444 连续；双实例 SA=12 PA=0 SB=42 PB=0 → injectOrmForTest(SB, PB, rB) → new OrmPutRequest(SA,PA,SB,PB,0,UserEntity.class.getName(), bytes, false, null) → Handler.process → Ack.rowId < (1L<<22)（高 22bit 零）；rowId=1L（第一张表首分配）
  2. **T02_4tuple_PUT_GET_EXISTS_Remove_idempotent**
     - PUT name="Alice" age=25 → rowId=1
     - GET rowId=1 → name 相等，age=25
     - EXISTS rowId=1 → true；REMOVE rowId=1 → ok=true
     - EXISTS rowId=1 → false；REMOVE again → ok=false（幂等）
  3. **T03_DualMode_Fail_STD_ERROR_3_scenarios**
     - Sc A：targetSP=(-1,-1) globalRowId=0 → STD_ERROR
     - Sc B：globalRowId = DsIdLayout.compose(SB=999, PB=9, 1L) 不存在实例 → STD_ERROR
     - Sc C：className = "not.Exist.Class" → STD_ERROR
  4. **T04_ClassIdSetWarmUp_FindRange_Count**
     - saveOrUpdate ×2（name="A"/"B"）→ OrmFindRangeRequest → list.size=2；OrmCountRequest → count=2
  5. **T05_QW_Serialize_withRelations_entitiesMatch**
     - put Alice age=25, Bob age=30；Qw eq(age,25) → findRange.size=1, name=Alice
     - withRelations=true → Ack.relationsList.size=relations!=null
  6. **T06_GlobalRowId_Fallback_GET**
     - 先 PUT → rowId=1；globalRowId = DsIdLayout.compose(SB, PB, 1L)
     - OrmGetRequest(SA, PA, -1, -1, globalRowId, clazz, 1L, false) → present=true, name match
- **TR 6.1 (rule)**：6 methods 全部 0F0E
- **Verification**：mvn -Dtest=Orm_P7_Tgt2_Handler_6_Test test ≥6 PASS。

---

## Task 7: 烟测 ≥58 PASS 0F0E
- **FR 绑定**：所有 FR + NFR
- **命令链**：
  ```powershell
  mvn -pl p2p-core,p2p-db -am install -DskipTests -q
  mvn -pl p2p-db -Dtest='ds.DsRegistry_Phase1_DbMetaPackedAttrs_8_Test,ds.Orm_P7_Tgt2_Handler_6_Test,ds.DsRegistry_P7_Tgt3_HandlerTest,ds.DsRegistryExtAttrs_P7_Tgt5_HandlerTest,ds.DsRegistryExtAttrs_P7_Exts_Test,ds.DsRegistryTest,ds.DsRegistry_P31_MasterIndexPrefixTest,ds.DbEntityBinlogFrame_P7_C4_HandlerTest,com.q3lives.ds.binlog.*' -Dsurefire.failIfNoSpecifiedTests=false test
  ```
- **期望**：Phase16 + Tgt26 + Tgt34 + Tgt57 + ExtAttrs12 + DsRegistry14 + P315 + C41 + Binlog2 + 其他=2 → ≥59 PASS 0F0E BUILD SUCCESS。

---

## Task 8: AGENTS Invariant 10 条追加 + current-state VERIFIED CLOSED Block
- **FR 绑定**：FR-10 / NFR-3
- **AGENTS Invariant 10 条（Tgt-2 专项）**：
  1. Q2=C2：ORM rowId 永远本地自增（DsHashMapIdAllocator 0..N），不嵌入 serverId/partitionId；globalId=调用端 ALU 合成
  2. Q3=C3：tableId=FNV-1a(className UTF-8 bytes)，无全局 Registry 映射表，Handler 不查 DsRegistry
  3. 命令号区段锁死：ORM REQ=24336-24344，ACK=24436-24444，严格续于 Tgt-5 24335/24435；24000-24335 绝不回碰
  4. 双模式路由优先级：4tuple(targetSP) 先，globalRowId fallback 后；双失败统一 STD_ERROR
  5. classIdSet warm-up 必须走 `dummy put+remove`，不能手动 new DsHashSet，保证与 localDb.putEntity→idsFile 单实例共享 RAF
  6. Handler 端零 new 第二 DsHashSet 实例，ids 读写完全复用 DsDatabaseLocal classIdSetCache 单实例
  7. 零改 7 大底层 + DsRegistry.java + DsDatabaseServer.java + GenericManager.java B1 骨架
  8. Resolver 单例复用：Tgt3RegistryResolver 追加 resolveOrmTarget（不新建 OrmTargetResolver class）
  9. FNV-1a tableId 注册路径：localDb.putEntity→createMeta→register tableIdToClass.put；Handler 绝不手动 tableIdToClass.put 绕过
  10. QueryWrapper 序列化：复用 SerializationManager.serialize/deserialize，零新 codec；跨节点 withRelations：复用 DbEntityRelationsCodec，零新 relations 格式
- **current-state Block**：含 ① 需求来源 ② 9×2 命令号 ③ 交付 4 文件清单（P2PCommand/18Model/9Handler/ResolverOrm/6Test）④ 验收证据（6 专项 + 59 烟测）⑤ rubric 3 项 score=2/2 锚点 ⑥ Joint Verification Cmd ⑦ 后续 Follow-up：Tgt-4 LZ4（仅剩）
- **Verification**：grep AGENTS.md "Q2=C2" "24336" 命中。

---

## Task 9: Brain 三命令 + 独立 Final Review
- **FR 绑定**：NFR-1 NFR-2 NFR-3 = 2/2
- **命令**：
  ```powershell
  brain context audit --since 53a382e39827
  brain distill --session   # 产出 proposal
  brain session finish      # exit 0 或 force
  ```
- **Final Review Checkpoints（独立只读）**：
  1. 7 大底层 + DsRegistry.java + GenericManager.java + DsDatabaseServer.java git diff --stat = 0 行
  2. rubric NFR-1（双模式覆盖）：T02+T03+T06 ≥3 tests 通过证据；NFR-2 warm-up：T04 通过；NFR-3 7零改+2不变式：T01 rowId 低 40bit + grep zero Registry
  3. P2PCommand 枚举 diff 只新增 18，无旧枚举修改
- **完成标准**：review.md `Result: pass`，session finish exit 0。
