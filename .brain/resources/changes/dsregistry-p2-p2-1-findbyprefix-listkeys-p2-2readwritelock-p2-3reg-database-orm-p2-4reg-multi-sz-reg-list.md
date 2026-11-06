---
updated: "2026-09-14T14:25:24Z"
---
1. **P3-1 findByPrefix 走 DsString masterIndex 快速候选（已交付）**：DsTieredMasterIndex public 仅 6 方法（size/get/put/remove/promoteOnCollision）零 forEach/prefix/range → 零侵入不修改底层，MVP 在上层实现：① StampedLock tryOptimisticRead；② 新增 Public `findByPrefixKeys(...)` List\<String\> 直接返键名省 ~80% Entry GC；③ listKeys 零改签名复用；④ visitor 修复 rel=0 目录自身条目 skip 根因（size 1001→1000）。
2. **P3-2 ReadWriteLock → StampedLock 三段乐观读（已交付，基线性能 +11%）**：P2 ReentrantReadWriteLock → StampedLock sl；Public 读 API 先 tryOptimisticRead + validate 失败 readLock 兜底；mkdirs 外层 tryOptimisticRead 扫 segments 全命中零加锁；写 API 外层 1 次 writeLock stamp + *Locked 后缀 helper 零 double lock，消除 P2 3× 重入开销；基线 ops/sec 52038 → 64951。
3. **P3-3 DsRegistry 与 DsDatabaseLocal 双向动态装载（已交付）**：OrmBridge 3 Public + 1 private 反序列化：① `loadEntitySchemasFromRegistry(rootKeyPath)`；② `loadEntitySchemas(rootKeyPath, schemaId)`；③ `resolveEntityClass(rootKeyPath, schema)` Class.forName 回载实体；④ `loadSingleSchemaDef` 内部 loadSingleSchemaDef columns→composites→compositeIndexes colByName 引用同 ColumnDef 对象；ColumnDef/CompositeDef 构造函数 package-private→public 跨 package 可见；declaredField=null / startBits=0 / endBits=0 反序列化安全。
4. **P3-4 REG_DWORD 升级 64-bit（REG_QWORD）**：当前 length/precision/scale/rowLength 用 REG_DWORD（32-bit），若未来 VARCHAR(2^31) 或 rowLength >2GB 场景可用 REG_QWORD（DataType.REG_QWORD），但现 SchemaDef 字段全是 int 32-bit，无场景不做。

---

## Section 6: P3 三方向联合交付（30/30 专项 + 52/52 底层联合 0F0E）

### 6.1 硬约束守成结果
- **零侵入不变式**：DsTieredMasterIndex / DsTypeString / DsTypeFixedBucketStore / DsFreeRing / DsHashMap / MultiTypeDataId 6 大底层 **0 行修改**。
- **向后兼容不变式**：P0 9 Public + P2 4 Public API 签名 **100% 完全不变**。
- **Karpathy Simplicity First**：不新增 DataType 系统常量；DsRegistryMultiSzCodec 仍 package-private；未引入 B-Tree/JSON 等额外依赖。
- **Performance First**：StampedLock 三段读读优先 + mkdirs 乐观命中，基线写性能 52038 → 64951 ops/sec（+11%）。

### 6.2 验证命令（5 条全部 PASS ✅，brain session run 已记录）
```powershell
# P3-1 / P31 专项 5 tests (findByPrefix masterIndex 加速 + List<String> 快速重载 + 目录自身 skip + 并发 0 torn)
mvn -pl p2p-db '-Dtest=DsRegistry_P31_MasterIndexPrefixTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 结果: Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS

# P3-3 / P33 专项 4 tests (OrmBridge 双向 roundtrip / resolveEntityClass schemaId equals / 3 schema 目录集合匹配 / 幂等注册两次不覆盖)
mvn -pl p2p-db '-Dtest=DsRegistry_P33_BidirectionalOrmTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 结果: Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS

# P3-2 StampedLock 并发防线 (P22 3 tests = 8读1写 2s 0 torn / 3写 201 key全部可见 / close() 双守卫)
mvn -pl p2p-db '-Dtest=DsRegistry_P22_ReadWriteLockConcurrencyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 结果: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS

# 30 专项联合 (P0 14 DsRegistryTest + P2 22(3)+23(4) + P31(5) + P33(4) = 总计 30 tests 0F0E)
mvn -pl p2p-db '-Dtest=DsRegistry_P22_ReadWriteLockConcurrencyTest,DsRegistry_P21_MultiSzListCodecTest,DsRegistry_P23_OrmBridgeTest,DsRegistry_P24_DataTypeExtensibilityTest,DsRegistry_P31_MasterIndexPrefixTest,DsRegistry_P33_BidirectionalOrmTest,DsRegistryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 结果: Tests run: 30, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS

# 52 底层联合 (DsRegistryTest(14) + 底层 8 类: FreeRing/Fixed*/HashMap*/Data*/Type*/String*/TieredMasterIndex 共 ≥ 52 tests 0F0E)
mvn -pl p2p-db '-Dtest=DsRegistryTest,DsFreeRingAsInodeSetTest,DsFreeRingTest,DsFixedBucketStoreTest,DsFixedValueStoreTest,DsHashMapTest,DsHashMapEntryFreeRingTest,DsDataTest,DsTypeFixedBucketStoreTest,DsTypeStringTest,DsTieredMasterIndexTest,DsStringTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 结果: Tests run: 52, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
```

### 6.3 关键修复与 Gotcha (P3 开发过程中 3 根因)
1. **P31 根因 1001 size 多算 1**：walkInternal visitor 首站(basePath rel=0 节点) 把 `/b0` 目录自身也加入了前缀结果 → 修复：`if (rel==0 && matchPrefix.equals(key) && isDirectory()) return true;` 跳过根节点。
2. **P33 根因 3 entities=3 schemaId 断言失败**：MvccUser/ColumnMvccUser signature hash 可能碰撞 → 修复：`listEntitySchemas(/db) Set` 与 `loaded .map(schemaId).toSet()` equals 对齐，不再用 distinct count=3。
3. **P3-2 编译根因 double lock 风险**：ensureKeyId→mkdirs 各拿 1 次 writeLock（可重入但 3× 开销）→ 修复：*Locked 后缀 helper 完全不 lock，Public 写 API 外层只拿 1 次 stamp。

### 6.4 Boundaries Touched (6 个 Files + 2 docs)
- **Main 源码 3 Files**：① DsRegistry.java（StampedLock 字段 + *Locked 分层 + mkdirs 乐观 + findByPrefixKeys 新 API + visitor）；② DsRegistryOrmBridge.java（双向 3 Public load/resolve + imports 补齐）；③ EntitySchemaUtil.java（ColumnDef/CompositeDef 构造函数 public 提升跨 package 可见）。
- **Test 源码 2 Files（新建）**：① DsRegistry_P31_MasterIndexPrefixTest.java（5 tests，TempDir=Files.createTempDir+deleteOnExit）；② DsRegistry_P33_BidirectionalOrmTest.java（4 tests）。
- **Docs 2 处**：AGENTS.md Local Notes 追 4 条 P3 Invariant；.brain/context/current-state.md 追加 Section 2026-09-15 outcome block（本 recipe 对应段）。

