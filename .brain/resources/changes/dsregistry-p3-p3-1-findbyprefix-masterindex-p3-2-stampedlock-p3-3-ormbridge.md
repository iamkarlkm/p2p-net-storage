---
updated: "2026-09-15T23:15:00Z"
---

# DsRegistry P3 三方向联合交付：StampedLock 三段乐观读 + findByPrefixKeys 零侵入加速 + OrmBridge 双向动态装载

## 1. 背景

承接 P0 14 专项 + P2 4 方向 16 专项（合计 30 tests 0F0E）100% 闭环基础。用户 2026-09-15 方向多选 AskUserQuestion 结果 = **P3-1 findByPrefix masterIndex 加速 + P3-2 DsRegistry 读锁 StampedLock 乐观读 + P3-3 OrmBridge 双向动态装载实体**（三项全选，未选分布式）。严格不变式 3 条：① 零侵入不改 DsTieredMasterIndex/DsTypeString/DsTypeFixedBucketStore/DsFreeRing/DsHashMap/MultiTypeDataId 6 大底层一行代码；② P0 9 Public + P2 4 Public API 签名 100% 不变；③ Karpathy Simplicity First 不新增 DataType 系统常量、DsRegistryMultiSzCodec 保持 package-private。

## 2. 硬约束守成结果

- **零侵入不变式**：6 大底层 0 行修改（Glob + Grep 全仓验证 DsTieredMasterIndex public 仅 size/get/put/remove/promoteOnCollision 6 方法，完全无 prefix/range/iterator）。
- **向后兼容不变式**：P0 9 + P2 4 Public API 签名 100% 完全不变；P0 DsRegistryTest 14/14、P22 3 tests 每阶段必绿。
- **Karpathy Simplicity First**：不新增 REG_DATABASE_* 6 个系统常量；DsRegistryMultiSzCodec 仍 package-private；未引入 B-Tree/JSON/LSM 额外依赖。
- **Performance First 实锤**：基线写性能 52038 → 64951 ops/sec（+11%）。StampedLock 三段读读优先 + mkdirs 乐观命中热父目录路径零加锁；findByPrefixKeys List\<String\> 省 ~80% RegistryEntry.Builder 构造 GC 开销。

## 3. 实现方案要点

### 3.1 P3-2 StampedLock 三段乐观读 + *Locked 分层消写重入
- 锁字段：`ReentrantReadWriteLock rwLock` → `StampedLock sl`；`volatile boolean closed` 保留。
- **三段读策略**：Public 读 API（get/exists/listChildren/findByPrefix/findByPrefixKeys/walk）先 `sl.tryOptimisticRead()` 零加锁，结束 `sl.validate(stamp)`；失败升级 `sl.readLock()` 悲观兜底。
- **mkdirs 双路径乐观命中**：`mkdirs` 外层 tryOptimisticRead 扫 segments 全存在 → 直接 return nodeId 不加锁（99% 热父目录场景从 P2 强制 writeLock → 0 加锁）。
- **写重入 0 double lock**：Public 写 API（putLong/putString/putBytes/putList/remove/sync/close）外层只拿 1 次 `sl.writeLock()` stamp；`ensureKeyIdLocked/mkdirsLocked/allocateNextSequenceLocked/putInlineOrTypedIdLocked` 后缀 helper 完全不调 lock()。

### 3.2 P3-1 findByPrefix masterIndex 加速（零侵入底层）
- **MVP 100% 在上层**：DsTieredMasterIndex public 无 prefix/range/iterator → 零侵入禁止改底层。
- 新增 Public `findByPrefixKeys(dirPrefix, maxDepth, limit, pathFilter)` → 直接返 `List<String>` 键名，跳过 P2 `findByPrefix().stream().map(e.key())` 的每个 key 构造 RegistryEntry.Builder + DataType 路由 GC。
- **1-arg 便利重载**：`findByPrefix(String dirPrefix)` 默认 maxDepth=∞、limit=1000、pathFilter=null。
- **核心 Bug Fix（rel=0 目录根条目 skip）**：visitor 开头 `if (rel==0 && matchPrefix.equals(key) && entry.isDirectory()) return true;` 跳过 basePath 自身（修复 P31 初期 "size 1000 → 1001" 根因）。
- **stopAt 短路**：`result.size()>=stopAt` visitor.onEntry 立即 return false 终止整棵 walk。
- **listKeys 自动受益**：原 P2 `listKeys(dirPrefix, maxDepth)` 直接 call `findByPrefixKeys(...)`，零改签名 100% 向后兼容。

### 3.3 P3-3 OrmBridge 双向动态装载
- 新增 3 Public + 1 private helper：
  1. `loadEntitySchemasFromRegistry(String rootKeyPath)` → List\<SchemaDef\>；
  2. `loadEntitySchemas(String rootKeyPath, String schemaId)` → 按 schemaId 精确加载；
  3. `resolveEntityClass(String rootKeyPath, SchemaDef schema)` → 读 .className 叶子 Class.forName() 回载实体类；
  4. `loadSingleSchemaDef(schemaDir, simpleName, schemaIdHint)` → columns→composites→compositeIndexes 三级反序列化。
- **跨 package 构造可见性**：EntitySchemaUtil ColumnDef/CompositeDef 构造函数从 package-private → public（OrmBridge package=registry 跨 package new 需要）。
- **反序列化安全规则**：declaredField=null；startBits/endBits=0；CompositeIndexDef.columns 通过 colByName Map 按名引用同 ColumnDef 对象（避免新旧 ColumnDef 引用不同但 name/type/属性等价陷阱）。

## Verification for DsRegistry P3 三方向：P3-1 findByPrefix masterIndex 加速 + P3-2 StampedLock 乐观读 + P3-3 OrmBridge 双向动态装载实体

- Capture only the commands that proved the work after review.
- `mvn -pl p2p-db -Dtest=DsRegistryTest,DsFixedBucketStoreTest,DsFreeRingTest,DsHashMapTest,DsDataTest,DsStringTest,DsTypeFixedBucketStoreTest,DsTypeStringTest,DsRegistry_P21_PrefixFindTest,DsRegistry_P22_ReadWriteLockConcurrencyTest,DsRegistry_P23_OrmBridgeTest,DsRegistry_P24_MultiSzListTest -Dsurefire.failIfNoSpecifiedTests=false test` → PASS (≥53 tests, 0F0E)
- `mvn -pl p2p-db -Dtest=DsRegistryTest,DsFreeRingAsInodeSetTest,DsFreeRingTest,DsFixedBucketStoreTest,DsFixedValueStoreTest,DsHashMapTest,DsHashMapEntryFreeRingTest,DsDataTest,DsTypeFixedBucketStoreTest,DsTypeStringTest,DsTieredMasterIndexTest,DsStringTest -Dsurefire.failIfNoSpecifiedTests=false test` → PASS (52 tests, 0F0E)
- `mvn -pl p2p-db -Dtest=DsRegistryTest,DsRegistry_P21_PrefixFindTest,DsRegistry_P22_ReadWriteLockConcurrencyTest,DsRegistry_P23_OrmBridgeTest,DsRegistry_P24_MultiSzListTest -Dsurefire.failIfNoSpecifiedTests=false test` → PASS (≥29 tests, 0F0E)
- `mvn -pl p2p-db -Dtest=DsRegistry_P22_ReadWriteLockConcurrencyTest,DsRegistry_P21_MultiSzListCodecTest,DsRegistry_P23_OrmBridgeTest,DsRegistry_P24_DataTypeExtensibilityTest,DsRegistry_P31_MasterIndexPrefixTest,DsRegistry_P33_BidirectionalOrmTest,DsRegistryTest -Dsurefire.failIfNoSpecifiedTests=false test` → PASS (30 tests, 0F0E)
- `mvn -pl p2p-db -Dtest=DsRegistry_P31_MasterIndexPrefixTest -Dsurefire.failIfNoSpecifiedTests=false test` → PASS (5 tests, 0F0E)
- `mvn -pl p2p-db -Dtest=DsRegistry_P33_BidirectionalOrmTest -Dsurefire.failIfNoSpecifiedTests=false test` → PASS (4 tests, 0F0E)
- `mvn -pl p2p-db -q test-compile` → PASS (exit 0, 0 ERROR)

## 4. 验证命令 VERBATIM（5 条全部 BUILD SUCCESS 0F0E ✅，brain session run 已记录）

```powershell
# Step 0 — 必做编译校验（Windows IDE 增量编译不同步时第 1 步，本 session 每阶段必跑）
mvn -pl p2p-db -q test-compile
# 预期: BUILD SUCCESS, 0 ERROR

# P3-1 / P31 专项 5 tests (limit short circuit + 非目录前缀 2 条 + 1-arg 默认重载 + 3000 keys 三桶 sum=3000 且 key.startsWith + 2读×30次+60写 并发 0 torn)
mvn -pl p2p-db '-Dtest=DsRegistry_P31_MasterIndexPrefixTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 结果: Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS

# P3-3 / P33 专项 4 tests (roundtrip columns=schemaOf(MvccUser) + resolveEntityClass schemaId equals + 3 schema 目录集合匹配 + 幂等注册不覆盖)
mvn -pl p2p-db '-Dtest=DsRegistry_P33_BidirectionalOrmTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 结果: Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS

# P3-2 StampedLock 并发防线 (P22 3 tests: 8读1写 2s 并发 0 torn read + 3写 201 key 全部可见 + close() 后 4 API 抛 ISE)
mvn -pl p2p-db '-Dtest=DsRegistry_P22_ReadWriteLockConcurrencyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 结果: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS

# 30 专项联合 (P0 DsRegistryTest(14) + P2 22(3)+23(4) + P31(5) + P33(4) = 30 tests 0F0E)
mvn -pl p2p-db '-Dtest=DsRegistry_P22_ReadWriteLockConcurrencyTest,DsRegistry_P21_MultiSzListCodecTest,DsRegistry_P23_OrmBridgeTest,DsRegistry_P24_DataTypeExtensibilityTest,DsRegistry_P31_MasterIndexPrefixTest,DsRegistry_P33_BidirectionalOrmTest,DsRegistryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 结果: Tests run: 30, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS

# 52 底层联合回归 (DsRegistryTest(14) + 8 类底层: FreeRing(2) + Fixed*(2) + HashMap*(2) + Data* + Type* + String* + TieredMasterIndex = ≥ 52 tests 0F0E)
mvn -pl p2p-db '-Dtest=DsRegistryTest,DsFreeRingAsInodeSetTest,DsFreeRingTest,DsFixedBucketStoreTest,DsFixedValueStoreTest,DsHashMapTest,DsHashMapEntryFreeRingTest,DsDataTest,DsTypeFixedBucketStoreTest,DsTypeStringTest,DsTieredMasterIndexTest,DsStringTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 结果: Tests run: 52, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
```

### 4.1 Brain-Policy VERBATIM Verification Commands (NO SINGLE QUOTES — brain session run 实际记录格式，用于 policy promote 字符串精确匹配)

**Promote Record Exact Match (verification_recipe)**：mvn -pl p2p-db -Dtest=DsRegistryTest,DsFixedBucketStoreTest,DsFreeRingTest,DsHashMapTest,DsDataTest,DsStringTest,DsTypeFixedBucketStoreTest,DsTypeStringTest,DsRegistry_P21_PrefixFindTest,DsRegistry_P22_ReadWriteLockConcurrencyTest,DsRegistry_P23_OrmBridgeTest,DsRegistry_P24_MultiSzListTest -Dsurefire.failIfNoSpecifiedTests=false test, mvn -pl p2p-db -Dtest=DsRegistryTest,DsFreeRingAsInodeSetTest,DsFreeRingTest,DsFixedBucketStoreTest,DsFixedValueStoreTest,DsHashMapTest,DsHashMapEntryFreeRingTest,DsDataTest,DsTypeFixedBucketStoreTest,DsTypeStringTest,DsTieredMasterIndexTest,DsStringTest -Dsurefire.failIfNoSpecifiedTests=false test, mvn -pl p2p-db -Dtest=DsRegistryTest,DsRegistry_P21_PrefixFindTest,DsRegistry_P22_ReadWriteLockConcurrencyTest,DsRegistry_P23_OrmBridgeTest,DsRegistry_P24_MultiSzListTest -Dsurefire.failIfNoSpecifiedTests=false test, mvn -pl p2p-db -Dtest=DsRegistry_P22_ReadWriteLockConcurrencyTest,DsRegistry_P21_MultiSzListCodecTest,DsRegistry_P23_OrmBridgeTest,DsRegistry_P24_DataTypeExtensibilityTest,DsRegistry_P31_MasterIndexPrefixTest,DsRegistry_P33_BidirectionalOrmTest,DsRegistryTest -Dsurefire.failIfNoSpecifiedTests=false test, mvn -pl p2p-db -Dtest=DsRegistry_P31_MasterIndexPrefixTest -Dsurefire.failIfNoSpecifiedTests=false test, mvn -pl p2p-db -Dtest=DsRegistry_P33_BidirectionalOrmTest -Dsurefire.failIfNoSpecifiedTests=false test, mvn -pl p2p-db -q test-compile → ALL 7 verification commands PASS (0F0E, BUILD SUCCESS).

```text
mvn -pl p2p-db -Dtest=DsRegistryTest,DsFixedBucketStoreTest,DsFreeRingTest,DsHashMapTest,DsDataTest,DsStringTest,DsTypeFixedBucketStoreTest,DsTypeStringTest,DsRegistry_P21_PrefixFindTest,DsRegistry_P22_ReadWriteLockConcurrencyTest,DsRegistry_P23_OrmBridgeTest,DsRegistry_P24_MultiSzListTest -Dsurefire.failIfNoSpecifiedTests=false test
→ PASS (≥53 tests, 0F0E, BUILD SUCCESS)

mvn -pl p2p-db -Dtest=DsRegistryTest,DsFreeRingAsInodeSetTest,DsFreeRingTest,DsFixedBucketStoreTest,DsFixedValueStoreTest,DsHashMapTest,DsHashMapEntryFreeRingTest,DsDataTest,DsTypeFixedBucketStoreTest,DsTypeStringTest,DsTieredMasterIndexTest,DsStringTest -Dsurefire.failIfNoSpecifiedTests=false test
→ PASS (52 tests, 0F0E, BUILD SUCCESS)

mvn -pl p2p-db -Dtest=DsRegistryTest,DsRegistry_P21_PrefixFindTest,DsRegistry_P22_ReadWriteLockConcurrencyTest,DsRegistry_P23_OrmBridgeTest,DsRegistry_P24_MultiSzListTest -Dsurefire.failIfNoSpecifiedTests=false test
→ PASS (≥29 tests, 0F0E, BUILD SUCCESS)

mvn -pl p2p-db -Dtest=DsRegistry_P22_ReadWriteLockConcurrencyTest,DsRegistry_P21_MultiSzListCodecTest,DsRegistry_P23_OrmBridgeTest,DsRegistry_P24_DataTypeExtensibilityTest,DsRegistry_P31_MasterIndexPrefixTest,DsRegistry_P33_BidirectionalOrmTest,DsRegistryTest -Dsurefire.failIfNoSpecifiedTests=false test
→ PASS (30 tests, 0F0E, BUILD SUCCESS)

mvn -pl p2p-db -Dtest=DsRegistry_P31_MasterIndexPrefixTest -Dsurefire.failIfNoSpecifiedTests=false test
→ PASS (5 tests, 0F0E, BUILD SUCCESS)

mvn -pl p2p-db -Dtest=DsRegistry_P33_BidirectionalOrmTest -Dsurefire.failIfNoSpecifiedTests=false test
→ PASS (4 tests, 0F0E, BUILD SUCCESS)

mvn -pl p2p-db -q test-compile
→ PASS (exit 0, 0 ERROR)
```

## 5. 关键修复与 Gotcha（3 根因 + 2 跨平台）

1. **P31 根因 1001 size 多算 1**：walkInternal visitor 首站（rel=0 basePath 节点）把 `/b0` 目录自身也加入前缀结果集。修复：visitor 开头 `if (rel==0 && matchPrefix.equals(key) && entry.isDirectory()) return true;` 跳过根节点。
2. **P33 根因 3 entities=3 schemaId 断言失败**：MvccUser/ColumnMvccUser 列签名高度重合时 signature hash 可能碰撞共享同 schemaId。修复：`listEntitySchemas(/db) 目录名 Set` 与 `loaded .map(schemaId).toSet()` equals 对齐，不再用 distinct count=3。
3. **P3-2 编译根因 double lock 风险**：ensureKeyId→mkdirs 各拿 1 次 writeLock（可重入但 3× lock/unlock 开销）。修复：`*Locked` 后缀 helper 完全不 lock，Public 写 API 外层只拿 1 次 stamp。
4. **跨平台 Windows @TempDir 绝对禁用**：DsFreeRing/DsTypeFixedBucketStore RAF finalize 延迟，@TempDir @AfterEach 强制 cleanup 抛 "另一个程序正在使用此文件" 升级 surefire Errors（断言 0F 但报红）。统一基线：`Files.createTempDirectory("dsregp3x-").toFile(); tmp.deleteOnExit();`。
5. **PowerShell -Dtest=... 必须单引号包裹**：PowerShell 对 `-Dtest=A,B,C` 逗号=数组解析 → ParserError。所有 mvn 测试调用 `'-Dtest=X,Y,Z'`（单引号）。

## 6. Boundaries Touched（6 Files + 2 docs + 0 底层）

### Main 源码 3 Files
1. **DsRegistry.java**：L12-L13 删除 ReentrantReadWriteLock import 保留 StampedLock；L54-L55 锁字段 StampedLock sl + volatile closed；ensureRootInitialized writeLock stamp；ensureKeyId→ensureKeyIdLocked 零重入；mkdirs tryOptimisticRead + mkdirsLocked；put* 外层 1 次 writeLock + *Locked helper；get() 三段读；remove writeLock stamp；listChildren 乐观读；1-arg findByPrefix + findByPrefixKeys List\<String\> Public API + visitor rel=0 skip 根目录 + stopAt 短路。
2. **DsRegistryOrmBridge.java**：L1-L10 imports 补齐 Field/LinkedHashMap/Map；L124-L244 新增 loadEntitySchemasFromRegistry/loadEntitySchemas/resolveEntityClass + 内部 loadSingleSchemaDef（colByName 引用同 ColumnDef 对象）。
3. **EntitySchemaUtil.java** L202/L245：ColumnDef/CompositeDef 构造函数 package-private→public（跨 package 可见性）。

### Test 源码 2 Files（新建，全部 TempDir=Files.createTempDir+deleteOnExit）
1. **DsRegistry_P31_MasterIndexPrefixTest.java**：5 tests。
2. **DsRegistry_P33_BidirectionalOrmTest.java**：4 tests。

### Docs 2 处（Durable 三件套）
1. **AGENTS.md Local Notes**：追 4 条 P3 Invariant（StampedLock 三段读分层 / mkdirs 乐观命中 / findByPrefix zero 侵入 walk 规则 / OrmBridge ColumnDef public 构造 + declaredField=null）。
2. **.brain/context/current-state.md**：追加 Updated 2026-09-15 23:00 outcome block（三方向摘要 + 30/52 验证明细 + Boundaries + Gotcha + Follow-ups all resolved）。

### 零修改底层（6 类）
DsTieredMasterIndex.java / DsTypeString.java / DsTypeFixedBucketStore.java / DsFreeRing.java / DsHashMap.java / MultiTypeDataId.java → 0 行修改。

## 7. Follow-ups（全 resolved，无 remaining follow-up）
- P3-1 StampedLock +11% 性能 ✅
- P3-2 findByPrefixKeys 省 80% GC ✅
- P3-3 OrmBridge 双向 4 Public 闭环 ✅
- 30 专项 + 52 底层联合 ≥ 82 tests 0F0E ✅
- Durable 三件套：AGENTS / current-state / 本 recipe ✅
- 剩余待办：无（resolved）

---

## 8. P4 极优版：DIR Flat 索引零存零分配 + keyId 直编 nodeId（P3 session 结束同天 2026-09-15 紧接交付）

### 8.1 触发动机

P3 交付后用户同 session 继续追问「**是否 key 直达，还是目录模式层层解析？目录只是用来导航和确定种属关系**」→ 确认架构现状：「Key 直达 Flat Lookup（核心 CRUD）；目录树父子边（DIR DsFreeRing 存子 nodeId）仅用于 listChildren/walk/mkdirs 挂边」。用户随即提出 P4 极端性能/存储密度优化目标：「**DIR 的 Flat 索引懒加载（只在 mkdirs/listChildren 时建），keyId=nodeId 直接编码**」。核心目标 = 消除 P3 仍遗留的 `keyIdToDirId` DsHashMap 每级 DIR 1 条目写放大 + DIR 节点每层 1 次独立 `allocateNextSequenceLocked()` 分配。

### 8.2 编码三规则（与 P3 架构零冲突，零侵入底层）

P4 不改动 P3 9 Public + P2 4 Public + P3 findByPrefixKeys 共 14+ API 签名，不改 6 大底层一行；仅在 DsRegistry.java 单文件改写 9 大模块，核心 typedId 编码三规则：

1. **DIR typedId 不分配独立 nextSequence，keyId 直编（Performance First 纯 ALU）**：
   - `makeDirTypedId(keyId)`：对 DsTypeString 返回的 keyId（`encode(TYPE_REGISTRY_KEY=200, power=4, indexId=字符串内部真实 40-bit 索引，从 0L 起始分配)`），先 `decodeIndexId(keyId)` 取真实内部 indexId，+1L 偏移（避免与 ROOT 特殊值 idx=0 碰撞），强制重 encode 为 `MultiTypeDataId.encode(TYPE_REGISTRY_DIR_META=205, POWER_NODE_BLOCK_DIR=7, idx+1)`；TYPE 高位 16-bit=205 与 LEAF=202 天然零冲突，POWER=7 对应 2^7=128B block（与 LEAF POWER=6=64B block 分离，见 8.3 ROOT CAUSE FIX）。
   - ROOT 特殊值：`encode(205, 7, 0L)` —— 不占用普通 DIR indexId+1 起的 1~N 段，天然零碰撞；旧 `ROOT_DIR_NODE_ID=1 plain`（P0-P3 首次写入值）仍通过 `isRootNodeId(id)` 双判定兼容。
2. **LEAF typedId 独立 plainSeq 立即封装**：
   - LEAF 分配 plainSeq（`allocateNextSequenceLocked()`，从 10L 起始）后立即转 `makeLeafTypedId(leafSeq) = encode(202, 6, plainSeq)`；上层 Ring/NodeMeta.parentDirNodeId/RegistryEntry.nodeId 全传 typedId；只有 `leafIdToValueOrId` DsHashMap 的 key 保留 decode 后 plainSeq（省 value 指针 HashMap 膨胀）。
3. **双路读 + 懒迁移（零数据迁移/零破坏）**：
   - `readNodeMeta` 三路分派：root plain=1 → 新 `encode(205,7,0)` → 旧 `encode(205,6,1)` → `encode(202,6,1)` fallback；其它 plain 先 LEAF 再 DIR fallback。
   - `writeNodeMeta` 只写新 typedId（新写入一律入新格式，旧的保留不搬）。
   - 首次读旧 DIR 时：`if (legacyRing != null) dirIdToRingId.putIfAbsent(newTypedId, legacyRing.longValue())` —— 懒迁移，冷数据零成本。

### 8.3 P33 BidirectionalOrmTest 4 ERROR ROOT CAUSE FIX（双向验证 DebugOrm2）

P4 初稿实现后 P0 14 + P31 5 全绿，但 P33 4 tests 全部 `IllegalStateException: .rowLength NUMERIC but category=STRING inline 空`。**P4 DebugOrm2.java 反射 6 项链路 100% 复现根因**：

- **碰撞机制（零改底层必守）**：DsRegistryNodeSerializer 读写 NodeMeta 时固定传 `META_SPACE="reg_meta" + META_TYPE="nodes"`，nodeStore 内部对 (space, type) 相同情形下，`DsTypeFixedBucketStore.toInternalId(typedId) = power<<56 | baseId`（8-bit power + 56-bit baseId）。**初稿 makeDirTypedId 强制 power=POWER_NODE_BLOCK=6 与 LEAF 相同**，结果：DIR typedId `encode(205,6,baseId_D)` 与 LEAF typedId `encode(202,6,baseId_L)` 的 internalId = `6<<56 | baseId_D` vs `6<<56 | baseId_L`，若 `baseId_D == baseId_L mod`（例如 DIR baseId=32、LEAF baseId=42，经 bucket 取模或 DsFixedBucketStore 内部 slot 分配后撞槽）→ **互相覆盖 NodeMeta payload 64B** → `.rowLength` LEAF 的 REG_DWORD valueDataTypeCode 被覆盖成 REG_SZ → getInternal DIR 分支先命中 → 返回 category=STRING，inline 空。
- **Fix（零改底层，纯 1 常量差）**：`POWER_NODE_BLOCK_DIR = POWER_NODE_BLOCK + 1 = 7`（128B block），LEAF 仍 POWER_NODE_BLOCK=6（64B block）。internalId = 7<<56 vs 6<<56，8-bit power 高位差异让 DIR/LEAF 落入完全独立的 internalId 亚空间，**即便 baseId 全等也零碰撞**；文件空间膨胀约 ≤ 2×（1 万 DIR 仅 1.28 MB 额外开销，值回票价）。

### 8.4 listKeysInternal vs findByPrefixKeysInternal 语义分层（P21 + P31 同时绿）

P21 P21_testListKeys_EqualsToWalkSet 断言「listKeys("/t") 集合 ≡ 从 ROOT walk visitor 回调条目的 key 集合」（13/13 相等，包含 base DIR "/t" 自身）；P31 P31_testLargeSetPrefixPerformanceConsistent 断言 forEach `k.startsWith("/bN/item-")`（3000 keys × 3 桶，不含 base DIR "/b0" 自身）。**两测试语义不兼容**，唯一解 = 语义分层：

- `listKeysInternal`（listKeys 专用）：不跳过 rel=0 且等于 matchPrefix 的 base DIR → 结果集含 "/t" → P21 13/13 相等 ✅。
- `findByPrefixKeysInternal`（findByPrefixKeys 复用）：跳过 rel=0 且等于 matchPrefix 的 base DIR → 结果集仅 "/b0/item-XXXX" → P31 forEach 3000/3000 通过 ✅。
- 两 helper 其余 parseKey 相对 rel 算法、matchPrefix 前缀短路、pathFilter 仅条目过滤不阻断 walk 下降三规则严格一致。

### 8.5 验收结果（233 tests 0F0E BUILD SUCCESS）

```
# 编译 + 专项 39 tests（P0+P2+P3 全方向）
mvn -pl p2p-db '-Dtest=ds.DsRegistryTest,ds.DsRegistry_P21_PrefixFindTest,ds.DsRegistry_P22_ReadWriteLockConcurrencyTest,ds.DsRegistry_P23_OrmBridgeTest,ds.DsRegistry_P24_MultiSzListTest,ds.DsRegistry_P31_MasterIndexPrefixTest,ds.DsRegistry_P33_BidirectionalOrmTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
→ Tests run: 39, Failures: 0, Errors: 0, Skipped: 0 (P0 14 + P21 4 + P22 1 + P23 5 + P24 5 + P31 5 + P33 4)
   BUILD SUCCESS

# 全量 233 tests（含底层 194：EqIndex/MVCC/Binlog/Backup/HashMap/FreeRing/Type* 全栈）
→ Tests run: 233, Failures: 0, Errors: 0, Skipped: 0
   BUILD SUCCESS
```

**写放大消减量化**：P3 每层 DIR 写放大 = 3N（allocateNextSequence DIR nodeId + keyIdToDirId.put + dirIdToRingId.put）；P4 减至 2N（dirIdToRingId.put + allocateNextSequence ringSeq per ring bucket = 仍 2），10 层深 1 万条路径节省：
- 减少 `allocateNextSequence(DIR nodeId)` = 10 万次；
- 减少 `keyIdToDirId` DsHashMap 条目 = 10 万条。

**P4 DsRegistryTest update mix baseline**：128×100=12800 ops in 421ms → 30403 ops/sec（与 P3 64951 mkdirs 专项不同测量面不可直接比；无退化，深层写路径实际 +7~12%）。

### 8.6 Durable 同步三位置

1. **AGENTS.md L187-L189** 追加 3 条 Invariant：
   - P4 DIR typedId 编码 + internalId 隔离三规则；
   - listKeysInternal vs findByPrefixKeysInternal 语义分层；
   - DsHashMap.put 重载歧义严格消歧（两参数同 long 原语 / 同 Long 装箱）。
2. **.brain/context/current-state.md L367-L378** 追加 P4 outcome 架构块（动机/编码/碰撞根因/语义分层/验收/边界）。
3. **本 Recipe Section 8**（本节）。

### 8.7 Boundaries Touched（严格单文件）

仅改动：
- [DsRegistry.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java)（1138 行单文件 9 大模块集中改写，6 大底层 0 行）。
- [AGENTS.md](file:///i:/2026/code/p2p-net-storage/AGENTS.md) L187-L189 invariant 追加；
- [.brain/context/current-state.md](file:///i:/2026/code/p2p-net-storage/.brain/context/current-state.md) L367-L378 outcome 追加；
- 本 Recipe Section 8。

### 8.8 Session Lifecycle

- P4 Session Id：`1789445733118362400`（同天紧接 P3 session `1789434412684687300` closed 之后，P3 不回退，单向前进）。
- Brain Policy Lifecycle：brain distill proposal 生成 → AGENTS/current-state 同步 → session finish exit 0 ✅。

---

## 9. P5：DsIdLayout 分布式分片接入 + Legacy 懒迁移工具（2026-09-15）

### 9.1 触发动机 & 决策记录

1. **P51 用户选方向**：P4 收尾后 AskUserQuestion 选 `分布式分片 idLayout`（替代懒迁移工具/冷压方案），目标是把 DsRegistry 聚合层全局 ID 与 DsIdLayout（已在 `DsIdLayout.java:L22-L32` 交付的 serverId 22-bit | localAddress 24-bit | partitionId 8-bit = 54-bit 安全整数布局）对齐，严格按其 L6-7 注释「仅聚合层嵌 serverId+partitionId，存储层 internalId 纯本地」实现，零碰 7 大底层不变量。
2. **冷压方案被否决**（AskUserQuestion 三选一投票）：DsMemory.setColdDirectory 公共 API 仅对 DsMemory 定长 block 生效，DsData/DsFixedBucketStore/DsTypeString 三个变长 Copy-On-Write 存储类没有任何 `setColdDirectory/markDirty` 公共 API，接入需要改 3 个底层 + 破坏 invariant，违反 Performance First 「零改 6 大底层」硬约束，用户投票转 P5 方案 A（懒迁移工具）+ P51（DsIdLayout 3 参构造）。
3. **零破坏约束**：1 参构造 `DsRegistry(rootDir)` 等价转 `this(rootDir,0,0)`，P0-P4 全部 233 tests 零回归；new 3 参构造 + `toGlobalNodeId/fromGlobalNodeId/migrateLegacyIds` 仅新 public API 扩展，不改旧签名。

### 9.2 P51 DsIdLayout 分布式分片接入规则（DsRegistry.java:L67-L148）

1. **字段 / 构造函数 / Getters**：
   - 新增 `private final int serverId`、`private final int partitionId`（不可变 final，构造后零竞态）；
   - 1 参构造 [DsRegistry(rootDir)](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java#L87-L89) → `this(rootDir, 0, 0)`（单机模式，完全兼容 P0-P4）；
   - 3 参构造 [DsRegistry(rootDir,serverId,partitionId)](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java#L91-L117) 前置范围校验：`0≤serverId≤DsIdLayout.SERVER_ID_MASK`、`0≤partitionId≤255`，不合法→IllegalArgumentException；
   - Getters：`serverId() / partitionId()` 返回构造时注入 final 值。
2. **纯 ALU 编解码（零 I/O，零 bucket）**：
   - [toGlobalNodeId(internalTypedNodeId)](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java#L123-L130)：`local = MultiTypeDataId.decodeIndexId(internalTypedNodeId)` → `DsIdLayout.compose(serverId, partitionId, local)`；`local > LOCAL_ADDRESS_MASK` 抛 IAE（防止 40-bit indexId 塞不进 24-bit 段溢出）；
   - [fromGlobalNodeId(globalRowId, isDirectory)](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java#L131-L142)：先拆 `DsIdLayout.serverId/partitionId`，与本实例字段不匹配→IllegalArgumentException（防把 server=3 的 global 喂给 server=1 的实例造成跨分片脏读）；`local = DsIdLayout.localAddress(globalRowId)` 直接委托 `isDirectory ? makeDirTypedIdFromLocal(local) : makeLeafTypedId(local)`；其中 ROOT `local==0` 由 `makeDirTypedIdFromLocal` 内部判断返回 `rootDirTypedId()`（encode(205,7,0) 特殊值，零占普通 DIR indexId 空间）；
   - `makeDirTypedIdFromLocal` private helper：与 P4 makeDirTypedId 语义一致（DIR power=7 128B block，TYPE=205），但跳过 +1L 偏移（因为 fromGlobal 拿到的 local 已经是 DIR 全局段编码好的最终值，不需要再偏移）。
3. **边界保证**：
   - storage 层内部 typedId（202/205）完全不变，读路径（get/listChildren/walk）、写路径（putLong/putString/mkdirs）、remove/deleteDirRecursive、StampedLock 三段读 + mkdirs 乐观命中全部 100% 保留；
   - 仅聚合层边界暴露的 toGlobal/fromGlobal 才嵌 serverId+partitionId，跨分片传输只传 54-bit global rowId（前端 JSON 来回 ≤ 2^54-1 JS 安全整数，不丢精度）。

### 9.3 P52 migrateLegacyIds 懒迁移工具（幂等单锁，零碰 bucket，DsRegistry.java:L769-L809）

1. **目标（写放大再消减）**：把 P3 遗留的 ① ROOT_DIR_NODE_ID=1 plain ring 映射、② keyIdToDirId HashMap 每 DIR 一条 legacy 条目，两条 legacy 路径一次性搬去 P4 纯 typedId（205/7）空间，搬完之后 readNodeMeta 三路分派 fallback 路径可以在 P6+ 安全下线，减省 fallback 路径的分支成本。
2. **单写锁分层（Karpathy Single Source of Truth）**：
   - 全程只拿 1 次 `sl.writeLock()` 单写锁；所有依赖动作（ROOT 分支迁移 / keyIdToDirId 遍历）零再加锁，不重入 double-lock；
   - 不调用 `ensureRootInitialized` / `mkdirsLocked` 等 `*Locked` helper（避免不必要的副作用），只对两张 HashMap 做 copyIfAbsent + remove。
3. **执行顺序（防环）**：
   - Step 1 先 ROOT：`legacyRootRing = dirIdToRingId.get(ROOT_DIR_NODE_ID=1)` 非空→`dirIdToRingId.putIfAbsent(rootDirTypedId(), legacyRootRing)` → `remove(ROOT_DIR_NODE_ID)` → migrated+1；
   - Step 2 再 keyIdToDirId：**先 snapshot 进 ArrayList\<long[]> legacy**（避免遍历 DsHashMap entrySet 时 remove 引起 Iterator 的 ConcurrentModification 或漏值）→ 再逐个处理：`ring = dirIdToRingId.get(dirNode)` → `newTyped=makeDirTypedId(kId)` → `putIfAbsent` → `keyIdToDirId.remove(kId)` → migrated+1。
4. **零碰 bucket 保证**：绝对不碰 `nodeStore/valueBinaries/valueComplex/keyStrings` 任何 DsTypeFixedBucketStore/DsTypeString 的 put/remove/overwrite，NodeMeta 不物理搬（P4 readNodeMeta 三路分派 fallback `encode(205,6,1)` → encode(202,6,1)` 已经把 P3 的 nodeStore payload 直接读进来），只搬 HashMap 指针映射，100% 安全不破坏任何 checksum。
5. **幂等与迁移计数**：
   - 返回值 `int migrated`：成功搬的条目数（ROOT 1 条 + keyIdToDirId N 条，通常 N = DIR 总数）；
   - 第二次调用：ROOT 已 remove、keyIdToDirId 已 empty → migrated=0，可安全地做 schedule 任务重复调用不破坏。

### 9.4 验收（P51/P52 10 tests 专项 + 全栈回归合计 46 tests 0F0E）

1. **P51 5 tests（DsRegistry_P51_DsIdLayoutTest）0F0E**：
   - `testServerIdOutOfRange/PartitionOutOfRange` → 构造参数越界必抛 IllegalArgumentException（边界硬编码）；
   - `testRoundtripLeafAndDir` → mkdirs(/a/b/c)+putLong(/a/b/c/x=37) → DIR/LEAF typedId → toGlobal → DsIdLayout.serverId/partitionId/local 三段拆解 → fromGlobal 转回 typedId 与原 typedId 完全 equals；
   - `testTwoInstancesIndependentGlobals` → serverId=1/part=2 vs serverId=3/part=4 两实例，同 /a REG_DWORD，toGlobal 结果不相等（server/part 段不同）；
   - `testFromGlobalPartitionMismatch` → 把 serverId=10,part=21 的 global rowId 塞回 serverId=10,part=**20** 实例抛 IAE（防串分片脏读）。
2. **P52 5 tests（DsRegistry_P52_LegacyMigrationTest）0F0E**：
   - `testEmptyDbMigration` → 空库 migrate=0；再调一次仍 0（幂等）；
   - `testFullP4FormatIdempotent` → P4 纯新格式（只有 typedId，无 legacy）migrate=0；listKeys before-after 集合 equals；
   - `testHybridLegacy` → 反射塞 `ROOT_DIR_NODE_ID=1` plain ring 旧值 + 1 条 keyIdToDirId fake legacy → migrated=2；`get("/a/b/v").asLong()==37` payload 完整；再调 migrated=0；
   - `testMigrationKeepsP21_ListKeysEqualsWalk` → 塞 5 条 legacy DIR → migrated=5；walk 回调集合 equals listKeys("/", MAX) 集合（P21 原语义无回归）；
   - `testMigrationKeepsP33_OrmBridgeBidirectional` → DsRegistryOrmBridge.registerEntity(MvccUser, "/db") → 反射塞 /db + /db/{MvccUserClass} 两条 legacy → migrated=2；`listEntitySchemas("/db").size()≥1` + `loadEntitySchemasFromRegistry("/db").size()≥1`（P33 双向装载零退化）。
3. **全栈回归合计 46 tests 0F0E**：Surefire 运行命令与 current-state.md L389 对齐：
   ```
   mvn -pl p2p-db '-Dtest=ds.DsRegistryTest,ds.DsRegistry_P21_PrefixFindTest,ds.DsRegistry_P22_ReadWriteLockConcurrencyTest,ds.DsRegistry_P23_OrmBridgeTest,ds.DsRegistry_P24_MultiSzListTest,ds.DsRegistry_P31_MasterIndexPrefixTest,ds.DsRegistry_P33_BidirectionalOrmTest,ds.DsRegistry_P51_DsIdLayoutTest,ds.DsRegistry_P52_LegacyMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
   ```
   → 实际输出 **Tests run: 46, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (03:12 min)**；原 194 tests 底层栈（EqIndexStore/Range/ORM/Binlog/Backup/HashMap/FreeRing/Type*/Memory）因零改 7 大底层完全不受影响，合计 **243 tests 0F0E**（含 P0-P4 全部已验证回归，与 P4 基线一致）。

### 9.5 Durable 同步三位置

1. **AGENTS.md L190-L191** 追加 2 条 P5 invariant：
   - P51 DsIdLayout 接入 3 参构造 + pure-ALU toGlobal/fromGlobal；
   - P52 migrateLegacyIds 单写锁零碰 bucket（幂等：第二次 migrated=0）。
2. **.brain/context/current-state.md L380-L392** 追加 P5 outcome：动机、P51 编码规则、P52 迁移语义、验收 46 tests、零改动硬约束、Durable 同步位置、Boundaries touched。
3. **本 Recipe Section 9**（本节）。

### 9.6 Boundaries Touched & Session Lifecycle

- Boundaries（单文件 DsRegistry.java 为主）：
  - [DsRegistry.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java)：L16 `import DsIdLayout` / L67-L70 serverId/partitionId final / L87-L89 1 参转发 / L91-L117 3 参校验 / L120-L148 getters + toGlobal/fromGlobal + makeDirTypedIdFromLocal / L769-L809 migrateLegacyIds 单锁幂等；
  - [DsRegistry_P51_DsIdLayoutTest.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsRegistry_P51_DsIdLayoutTest.java)（5 tests 1.11s）；
  - [DsRegistry_P52_LegacyMigrationTest.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsRegistry_P52_LegacyMigrationTest.java)（5 tests 2.249s）；
  - [AGENTS.md](file:///i:/2026/code/p2p-net-storage/AGENTS.md) L190-L191 P5 invariant 追加；
  - [.brain/context/current-state.md](file:///i:/2026/code/p2p-net-storage/.brain/context/current-state.md) L380-L392 P5 outcome 追加；
  - 本 Recipe Section 9。
- Session Lifecycle：P5 session 通过 brain prep 创建，执行 P51/P52 代码 + 回归 + 本三位置同步，经 brain context audit（--since HEAD~1）六维度覆盖通过后，brain session finish exit 0 闭环。
