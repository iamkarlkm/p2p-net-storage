# DsRegistry P2 四方向扩展 Implementation Plan

**变更名**：DsRegistry P2-1 前缀查询 + P2-2 ReadWriteLock 并发读 + P2-3 REG_DATABASE ORM 桥接 + P2-4 REG_MULTI_SZ/REG_LIST 语义

---

## 1. Repository Research 结论（Research 阶段已验证）

### 1-1. 底层存储 API 表面限制（不可修改，Performance First 不变式）

| 存储 | Public 方法 | 可用/不可用 |
|------|------------|------------|
| DsTypeString | `getIndexId(key, createIfAbsent)` / `contains(key)` / `get(typedId)` | ✅ 可用；但无 `keySet()` / `prefixScan()` / `iterator()` public API → 注册表自身实现前缀查询，不能改底层 |
| DsFreeRing | `offerUnique(id)` / `snapshot()` / `count()` / `contains(id)` / `forEachSlotLocked` | ✅ 可用于目录子项遍历；当前 listChildren 已复用 snapshot() |
| DsHashMap | `put/get/remove` + `iterator()` + `forEachRange(offset,count)` | ✅ iterator() 可用，可用于 keyId→dirId/leafId 的前缀候选 |
| DsDatabaseLocal MVCC | `getTable(clazz, id, readTimestamp)` + versionHeadMapCache/mvccSpace()/resolveVisibleVersion() | ❌ 架构完全不兼容（DsDatabaseLocal 用 single DsFixedBucketStore + versionRecord bucket，DsRegistry 用 6 TypeID 物理隔离 + 5 HashMap + DsFreeRing）→ **不能直接复用 DsDatabaseLocal MVCC**，采用 Simplicity First 的 ReadWriteLock 替换单 ReentrantLock 方案 |
| EntitySchemaUtil.SchemaDef | `schemaOf(clazz)` → ColumnDef{name, typeName, length, indexed, mvcc} + CompositeDef + CompositeIndexDef | ✅ 1:1 可映射到 REG_DATABASE_TABLE/REG_DATABASE_COLUMN 语义 |
| DataType | `REG_MULTI_SZ` / `REG_LIST` 均已声明（STRING/COMPLEX category），但尚无 put/get 语义 | ✅ 只需在 Facade 层加编解码，不修改 DataType |

### 1-2. 性能/正确性关键风险识别

1. **REG_MULTI_SZ 序列化格式歧义**：如果用 `String.join("\0", list)`，则元素自身含 `\u0000` 会破坏分隔（罕见但存在）→ 必须采用 **[count:2B LE] + foreach([len_i:2B LE][byte_i])** 显式 length prefix 格式，不依赖分隔符
2. **ReadWriteLock 升级死锁**：当前 `get()` 内会调 keyStrings 读（底层有自己的 masterIndex synchronized），不能让 readLock 持有期间再写锁；只在 DsRegistry 公共 Facade 入口加锁（get/exists/listChildren → readLock，put/remove → writeLock），不穿透到底层存储内部
3. **findByPrefix 大规模目录 O(N) 扫描**：子目录 10k+ 条时单 walk 全量 depth cut 可能慢 → 限制 `findByPrefix(prefix, maxDepth=3, limit=1000)` 参数可配置，**默认 limit=1000 maxDepth=3**，超大集合用 pathFilter 精确 cut

---

## 2. Files and Modules（严格 Karpathy Simplicity First，只改/加最小集）

### ✅ 修改（仅对已交付的注册表文件做 surgical changes，零侵入其他模块）

| # | 文件 | 改动 |
|---|------|------|
| 1 | [DsRegistry.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java#L1-L760) | ① 写锁 `ReentrantLock writeLock` → 替换为 `ReadWriteLock rwLock = new ReentrantReadWriteLock(false)`；② Public 新增 3 API：`findByPrefix(prefix, maxDepth, limit, filter)` + `listKeys(prefix, maxDepth)` + `putList(keyPath, values, listType)` + `asList/RegistryEntry 内部` 编解码；③ 写路径：putLong/putString/putBytes/putBytesFromFile/putList/remove 用 writeLock；④ 读路径：get/exists/listChildren/listChildrenEntries/findByPrefix/listKeys/walk 用 readLock；⑤ 内部 walk()/snapshotChildren() 不加外层锁（因为调用方已经加了 readLock，避免 double lock） |
| 2 | [RegistryEntry.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/RegistryEntry.java#L1-L138) | ① 新增 `Optional<List<String>> listValue`；② 新增 `Builder.listValue(List<String>)`；③ 新增 `List<String> asList()`（REG_MULTI_SZ/REG_LIST DataType 时返回 listValue.orElse(empty)），其余类型仍抛 `UnsupportedOperationException` 或空列表 |

### ✅ 新增（不修改既有文件边界，独立类职责单一）

| # | 文件 | 作用 |
|---|------|------|
| 3 | `p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistryOrmBridge.java` | 独立桥接类：`registerEntity(Class<T extends DsTableAdapter>, String rootKeyPath)` 在 `{rootKeyPath}/{schemaId}/{tableName}/columns/{colName}` 下自动建目录树，并将 ColumnDef 字段（typeName/length/indexed/mvcc）写入 REG_DATABASE_TYPECODE/REG_DATABASE_COLUMN_LENGTH/REG_DATABASE_INDEXED/REG_DATABASE_MVCC 等值；配套 `getEntityRootKeyPath(clazz)` / `listEntities(rootKeyPath)` 返回 `List<Class<?>>` |
| 4 | `p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistryMultiSzCodec.java` | 独立编解码工具（非 public）：`encode(List<String>)` → **[count:short LE][len_0:short LE][b0..][len_1:short LE][b1..]...** 格式 byte[]；`decode(byte[])` → `List<String>` 反向解析；显式 length prefix 不依赖分隔符，元素允许含 NUL |

### ✅ 新增测试（不修改现有 DsRegistryTest 断言，仅追加测试类）

| # | 文件 | 测试覆盖（每方向 ≥3 用例）|
|---|------|------|
| 5 | `p2p-db/src/test/java/ds/DsRegistry_P21_PrefixFindTest.java` | ① 5 层目录下 `findByPrefix("/a", maxDepth=2, limit=100)` 返回前 100 条；② `findByPrefix("/a/b/depth4", maxDepth=1, limit=10)` depth cut 正确；③ pathFilter 精确过滤 `endsWith("/x")` 仅含 x 条目；④ listKeys("/a/b", maxDepth=3) 100% 与 walk ToMap 集合 equals |
| 6 | `p2p-db/src/test/java/ds/DsRegistry_P22_ReadWriteLockConcurrencyTest.java` | ① 8 读线程 get() 并发 + 1 写线程 putLong() 持续 500ms：所有 get() 不抛异常、数据无 torn（读不出来半写半读的 value）；② 3 写线程 putString() 顺序写入，读线程读回 value 与写入一致（WriteLock 序列化）；③ close() 之后所有读写抛 IllegalStateException（锁生命周期） |
| 7 | `p2p-db/src/test/java/ds/DsRegistry_P23_OrmBridgeTest.java` | ① `registerEntity(SampleEntity.class, "/orm")` 后 `/orm/{schemaId}/SampleEntity/columns/{id,name,age}` 目录存在，leaf entry DataType=REG_DATABASE_TYPECODE 对应 String class；② `getEntityRootKeyPath(SampleEntity.class)` 返回正确根路径；③ `listEntities("/orm")` 集合包含 SampleEntity，schemaId 正确；④ 重复 registerEntity 幂等（不重复创建目录、不覆盖已存在的 column leaf） |
| 8 | `p2p-db/src/test/java/ds/DsRegistry_P24_MultiSzListTest.java` | ① `putList("/k", ["a","bb","ccc"], REG_MULTI_SZ)` → `get("/k").asList()` equals ["a","bb","ccc"]；② 元素含 NUL `["x\0y", "", "\u1234"]` round-trip 不损坏（显式 length prefix 正确）；③ 空列表 `[]` round-trip 为 empty；④ 单元素 1MB String round-trip（valueComplex bucket 大 value 正确）；⑤ close/reopen 后 asList() 依旧相等（持久化）|

---

## 3. Implementation Steps（严格依赖顺序，后序依赖前序）

> **不变式**：每一步结束后 `mvn -pl p2p-db -q test-compile` exit 0；每一步结束后 `DsRegistryTest 14/14` 依旧 PASS（回归零破坏）。

### Phase 0：Pre-flight 基线验证（1 min，保证 baseline 全绿）
- 命令：`mvn -pl p2p-db -Dtest=DsRegistryTest,DsTypeFixedBucketStoreTest,DsTypeStringTest,DsFreeRingAsInodeSetTest,DsHashMapTest,DsHashMapEntryFreeRingTest,DsFixedValueStoreTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 验收：Tests run: 51, Failures: 0, Errors: 0

### Phase 1：P2-2 ReadWriteLock 并发读（先上锁，后续新 API 可复用）
1. **DsRegistry.java 加锁迁移**：
   - 字段：删除 `private final ReentrantLock writeLock` → 替换为 `private final ReadWriteLock rwLock = new ReentrantReadWriteLock(false);`（非公平，吞吐优先）
   - Helper：`readLock = rwLock.readLock()` / `writeLock = rwLock.writeLock()`；所有 `finally { writeLock.unlock(); }` 替换为对应的锁
   - 写操作（writeLock）：allocateNextSequenceLocked / putInlineOrTypedIdRaw / mkdirs / remove(recursive=true 递归子项) / putList(新增) / sync / close
   - 读操作（readLock）：get / exists / listChildren / listChildrenEntries / walk / findByPrefix(新增) / listKeys(新增)
   - 内联 helper（snapshotChildren / openRing / parseKey 等）：**不加锁**（调用方已加锁，避免 double lock）
2. **加锁最小验证**：Phase 0 全 51 tests 再次 PASS，无回归
3. **交付测试 DsRegistry_P22_* 3 tests**

### Phase 2：P2-4 REG_MULTI_SZ / REG_LIST 编解码（先做 codec 验证独立）
1. **新增 DsRegistryMultiSzCodec.java**：
   - `encode(List<String>)`：count=list.size() 先写 short LE（最多 65535 项），逐元素 len=bytes.length short LE + bytes；**空列表** 编码为 `[0x0000]`（2B）；**最大项长度** = Short.MAX_VALUE = 32767 bytes（UTF-8），超长抛 `IllegalArgumentException`（避免 OOM）
   - `decode(byte[])`：先读 count short LE，再迭代读 [len short LE + bytes]，严格按 len 取，不依赖分隔符；若 bytes 长度不够或 len/count 超范围 → 抛 `IllegalArgumentException`（严格校验不静默损坏）
2. **RegistryEntry.java 追加 listValue**：`asList()` 对 `REG_MULTI_SZ/REG_LIST` DataType 返回 listValue.orElse(Collections.emptyList())，其余类型抛 `UnsupportedOperationException`
3. **DsRegistry.java 追加 Public API `putList(keyPath, values, listType)`**：
   - listType 必须是 `REG_MULTI_SZ` 或 `REG_LIST`，否则抛 `IllegalArgumentException`
   - values 为 null → 等同 `Collections.emptyList()`
   - 编解码：encode → 结果 byte[]：if length ∈ [1..7]，走 packSmallBytes 内联；else → 走 `valueComplex.put("reg_vals", "cmplx", payload)`（Category=COMPLEX，与 4B LE header 外部 BINARY 格式完全一致路径，复用 getValueBytes(valueComplex, typedId) 读）
   - get() 的 Category.COMPLEX 分支末尾追加：**若 DataType==REG_MULTI_SZ or REG_LIST**，读回 bytes 后 `decode(bytes)` 调用 `builder.listValue(decoded)`，随后返回 entry
4. **交付测试 DsRegistry_P24_* 5 tests**

### Phase 3：P2-1 前缀查询 findByPrefix + listKeys（依赖 Phase 1 readLock）
1. **DsRegistry.java 新增 Public API**（签名全部 `throws IOException` / `throws Exception` 与 walk 一致）：
   - `List<RegistryEntry> findByPrefix(String dirPrefix, int maxDepth, int limit, Predicate<String> pathFilter)`：
     - 等价 `walk(ToMap visitor, maxDepth, path -> path.startsWith(dirPrefix) && (pathFilter==null || pathFilter.test(path)))`，但 visitor 内 count 达到 limit 后返回 `false` 做短路终止（零额外扫描）
     - 先 parseKey(dirPrefix) → 若目录不存在则返回 `Collections.emptyList()`
     - maxDepth 默认 3，limit 默认 1000，pathFilter 默认 null
   - `List<String> listKeys(String dirPrefix, int maxDepth)`：
     - 简化 wrapper，调用 findByPrefix(limit=Integer.MAX_VALUE, pathFilter=null)，逐 entry 取 key，返回 key 字符串 List
2. **RegistryWalker ToMapVisitor** 不新增，直接复用已存在 ToMap 但在 visit 入口加 `if(visited.size()>=limit) return false` 短路（可在 findByPrefix 内部匿名 visitor 实现，不修改 RegistryWalker 类，Karpathy Simplicity First）
3. **交付测试 DsRegistry_P21_* 4 tests**

### Phase 4：P2-3 REG_DATABASE_* ORM 桥接（依赖 Phase 1 锁 + Phase 3 mkdirs 自动建目录）
1. **新增 DsRegistryOrmBridge.java**（独立类，持有 DsRegistry 引用，零修改 DsDatabaseLocal / EntitySchemaUtil 一行）：
   - 构造器：`DsRegistryOrmBridge(DsRegistry registry)` → 非 null 校验
   - 常量：`REG_DATABASE_TYPECODE` / `REG_DATABASE_COLUMN_LENGTH` / `REG_DATABASE_INDEXED` / `REG_DATABASE_MVCC`（都已在 DataType.java 声明，直接引用）
   - `String registerEntity(Class<T extends DsTableAdapter> clazz, String rootKeyPath)`：
     1) `SchemaDef schema = EntitySchemaUtil.schemaOf(clazz)` → 获取 schemaId（shortSha256）
     2) `String base = rootKeyPath + "/" + schemaId + "/" + clazz.getSimpleName()`
     3) `registry.mkdirs` 自动建 `base/columns` 目录（mkdirs 已经在 DsRegistry 内部 public？→ **NO，mkdirs 当前是 private**，所以 DsRegistry 内部把 mkdirs 提升为 package-private 或让 Bridge 调用内部包可见 helper → Karpathy 最小改：把 `mkdirs(String)` 提升为 **public**（签名不变，throws IOException，幂等）
     4) 对 `columns/` 下每一列 `ColumnDef col`：
        - `keyPath = base + "/columns/" + col.name + "/type"` → putString(REG_DATABASE_TYPECODE, col.typeName)
        - `+ "/length"` → putLong(REG_DWORD, col.length)
        - `+ "/indexed"` → putLong(REG_DWORD, col.indexed ? 1L : 0L)
        - `+ "/mvcc"` → putLong(REG_DWORD, col.mvcc ? 1L : 0L)
     5) 返回 `base`（实体根路径）；**幂等**：若 `"/type"` 叶子已存在则跳过该列（不覆盖）
   - `String getEntityRootKeyPath(Class<T> clazz, String rootKeyPath)`：内部计算 schemaId，返回 `rootKeyPath + "/" + schemaId + "/" + clazz.getSimpleName()`；不写磁盘纯计算方法
   - `List<String> listEntitySchemas(String rootKeyPath)`：调用 `registry.listChildren(rootKeyPath)` 返回 schemaId 子目录名集合（每个是 hex schemaId）；注意返回 schemaId 不是 class，因为反向 class→schemaId 映射需在用户侧注册（Simplicity First 不缓存类加载器）
2. **DsRegistry.java 小改**：把 `private long mkdirs(String path)` → `public long mkdirs(String path)`（无其他改动）
3. **交付测试 DsRegistry_P23_* 4 tests**

### Phase 5：全量联合回归 + Durable 收尾
1. 回归命令（≥ 51 + 3+5+4+4 = **≥ 67 tests 0F 0E**）：
   ```bash
   mvn -pl p2p-db '-Dtest=DsRegistryTest,DsRegistry_P21_PrefixFindTest,DsRegistry_P22_ReadWriteLockConcurrencyTest,DsRegistry_P23_OrmBridgeTest,DsRegistry_P24_MultiSzListTest,DsTypeFixedBucketStoreTest,DsTypeStringTest,DsFreeRingAsInodeSetTest,DsHashMapTest,DsHashMapEntryFreeRingTest,DsFixedValueStoreTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
   ```
2. Durable 三件套：
   - ① AGENTS.md Local Notes Invariant 追加 4 条（P2-1 findByPrefix limit/maxDepth 截断 / P2-2 ReadWriteLock 非公平吞吐优先 / P2-3 REG_DATABASE Bridge 幂等不覆盖 / P2-4 MultiSz 短 LE length prefix）
   - ② current-state.md 追加本 P2 outcome 块（含验证命令 / 边界 / Gotcha）
   - ③ 新建 `.brain/resources/changes/dsregistry-p2-4directions-prefixfind-rwlock-ormbridge-multisz.md` verification recipe
3. brain session run 记录两条回归命令 + brain session finish

---

## 4. Dependencies and Considerations（关键假设 & 兼容性）

### 4-1. 兼容性保证（0 破坏 Public API）
- DsRegistry 所有现有 Public API（putLong/putString/putBytes/putBytesFromFile/get/exists/remove/listChildren/listChildrenEntries/walk/sync/close）**签名不变、语义不变、异常不变**
- 所有现有 DsRegistryTest 14/14 tests 每 Phase 结束后必绿，任何改动导致回归 → 立即 rollback 该 Phase
- mkdirs 仅权限 private→public：行为 100% 相同（幂等、已存在返回 dirId），仅扩大访问范围

### 4-2. ReadWriteLock 正确性假设
- 假设底层 DsTypeString/DsFixedBucketStore/DsFreeRing/DsHashMap **单方法内部是线程安全的**（DsFreeRing 有 lock、DsHashMap 有 synchronized、DsTypeString masterIndex 有 synchronized——这些底层已经做了内部锁保证），因此 DsRegistry Facade 级的 read/writeLock 只负责**跨方法复合操作**的一致性（如 mkdirs 期间不能并行 remove 父目录），不依赖底层绝对线程安全；此假设在 51 tests 已隐含通过并发 put/get 的历史单测

### 4-3. P2-3 ORM Bridge 类加载假设
- `listEntities(rootKeyPath)` 返回 schemaId 集合而非 Class<?>，因为反向 `Class.forName(schemaId)` 不可靠且安全风险高；用户侧若要反向映射可在调用方维护 `Map<String, Class<?>> schemaIdToClass`，Bridge 不做缓存（Simplicity First 零持久化 Class 对象）

### 4-4. P2-4 MultiSz 元素大小上限
- 单个元素 UTF-8 字节上限 = Short.MAX_VALUE bytes (32767B ≈ 32KB)；超过抛 `IllegalArgumentException`（明确失败不静默截断）；若未来需要 >32KB 元素，可升级为 4-byte LE header，但当前 REG_MULTI_SZ 是注册表标签/小文本列表场景，32KB 足够

### 4-5. findByPrefix 性能边界
- 当 `dirPrefix="/"` 且 maxDepth=10, limit=10^6 时退化为全表 walk；Plan 中**强制默认 limit=1000、maxDepth=3**，调用方可显式调大，但 API doc 明确标注"超过 10 万条目应分目录"（和 DsFreeRing count 两段遍历 O(N) 复杂度一致，无静默无限扩容）

---

## 5. Validation（严格可重复的命令 & 断言）

### 5-1. 每 Phase 强制门（Gate Check）
- **Phase 1/2/3/4 结束后必执行**：
  ```bash
  mvn -pl p2p-db -q test-compile && mvn -pl p2p-db '-Dtest=DsRegistryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
  ```
  验收：exit 0，Tests run: 14, Failures: 0, Errors: 0

### 5-2. P2-1 专项断言（findByPrefix）
1. `findByPrefix("/a", maxDepth=3, limit=100)` 返回的 entry.key 均 startsWith("/a")，长度 ≤ 100
2. depth=4 下创建的条目 `/a/b/c/d/x` 在 maxDepth=3 参数下不出现在返回集合（depth cut 生效）
3. `listKeys("/a/b", 5)` 结果与 `DsRegistryTest 已有 5 层 listChildren + 逐级拼接` 的集合完全 equals（shallow compare 可，entry 正确性由 DsRegistryTest 保证）

### 5-3. P2-2 并发断言（ReadWriteLock）
1. 8 读 + 1 写并行 500ms：**所有读线程 get() 不抛异常、entry.getDataType() 不为 null、value 不出现 torn（写入前值 / 写入后值，不出现半写入的混合）**
2. 3 写线程按顺序 put "0..99"：读线程在写完成后读回 `"99"` 与最后写入一致（WriteLock 串行化）
3. close() 后任何新线程调 get() / putLong() 抛 IOException 或 IllegalStateException（锁 / 底层资源生命周期正确）

### 5-4. P2-3 ORM 桥接断言
1. registerEntity 后 `listChildren("/{root}/{schemaId}/{entityName}/columns")` 返回的列名集合 1:1 等于 EntitySchemaUtil.schemaOf(clazz).columns 映射的 name 集合
2. 每列 `/type` 叶子 REG_DATABASE_TYPECODE 对应的 asString() == ColumnDef.typeName（Java 全限定类名一致）
3. registerEntity 调用两次：第二次写入后 `/type` 不变（幂等不覆盖）

### 5-5. P2-4 MultiSz 断言
1. `asList()` 返回列表按插入顺序（REG_MULTI_SZ / REG_LIST 顺序敏感）
2. 含 NUL 元素 + 空元素 + 中文 Unicode 元素 round-trip 后 equals（无分隔符损坏）
3. 空列表 `putList("/empty", Collections.emptyList(), REG_LIST)` → `asList().isEmpty()==true`
4. close/reopen 后 asList() 与写前 equals（持久化落盘不损坏）

### 5-6. 最终联合回归断言
```bash
mvn -pl p2p-db '-Dtest=DsRegistryTest,DsRegistry_P21_PrefixFindTest,DsRegistry_P22_ReadWriteLockConcurrencyTest,DsRegistry_P23_OrmBridgeTest,DsRegistry_P24_MultiSzListTest,DsTypeFixedBucketStoreTest,DsTypeStringTest,DsFreeRingAsInodeSetTest,DsHashMapTest,DsHashMapEntryFreeRingTest,DsFixedValueStoreTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```
**验收：Tests run ≥ 67, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS**

---

## 6. Risks and their Handling

| # | 风险 | 概率 | 影响 | 处理策略 |
|---|------|------|------|----------|
| R1 | P2-2 ReadWriteLock 写线程饥饿（读多写少场景非公平锁写饿死）| 中 | 中 | lock 设置为非公平是吞吐量优先；若测试发现写线程等待 >1s，改为 `new ReentrantReadWriteLock(true)` 公平锁，但需在测试中证明性能下降 <20% 才切换 |
| R2 | P2-4 MultiSz 空字节数组（len=0）与 packSmallBytes 的 empty=0 冲突 | 低 | 高 | 处理：putList 时 encode 空列表 = [0x00 0x00]（2 bytes），len=2 → 走 packSmallBytes（len<=7 内联）→ storedVal=pack([0x00,0x00]) → get() COMPLEX 分支 unpack len=2 小字节 → decode 正确。若 storedVal=0（完全无写入）返回 empty 列表，符合语义。**已在 codec 设计中覆盖** |
| R3 | P2-3 ORM Bridge 重复注册 Entity 但 DsDatabaseLocal 的 schemaId 因 @DsField 属性变化变更，导致旧 schema 目录残留 | 低 | 中 | 处理：Bridge 的幂等仅在 *同一 schemaId* 下起作用；不同 schemaId 是不同目录（互不干扰），这是符合"schemaId 变更=新表=新注册表"的语义；用户侧清理旧 schema 目录用 `remove(rootKeyPath+"/"+oldSchemaId, recursive=true)` 即可 |
| R4 | P2-1 findByPrefix pathFilter 抛出 RuntimeException 导致 walk visitor 终止 | 中 | 低 | 处理：findByPrefix 内部 visitor 用 `try/catch` 包 pathFilter.test()，异常时过滤条目不纳入结果（静默跳过，不抛）；但用户传的 filter 异常是否暴露？选 **wrap & rethrow with 路径上下文** `IllegalArgumentException("filter failed for path=xxx: "+msg)`，不吞异常，便于用户定位 |
| R5 | P2-2 并发测试 CI 机器（CPU 少）触发 500ms 窗口太小未测出竞争 | 中 | 低 | 处理：测试中 8 读线程用 `while(System.currentTimeMillis() - start < 2000ms)`（延长到 2s，不是 500ms）；每个线程用 `ThreadLocalRandom` 随机 key，增加碰撞概率；退出条件"所有线程完成 >=100 次 get"且时间 >=2s |

---

## Plan Integrity Check（是否符合 TRAE-plan-mode 要求）

| Plan Requirement | 覆盖 | 位置 |
|------------------|------|------|
| Repository research | ✅ 4 类底层 API 表面 + 架构不匹配性实锤 | §1 |
| Files and Modules (affected) | ✅ 2 修改 + 2 新增 + 4 测试新增，精确到行 | §2 |
| Dependency-ordered steps | ✅ Phase 0→1→2→3→4→5 + 每步 Gate Check | §3 |
| Dependencies and considerations | ✅ 5 条兼容性/假设/边界 | §4 |
| Validation (repeatable commands) | ✅ 5 子方向专项断言 + 1 最终联合回归命令 exit 0 | §5 |
| Risks and handling | ✅ 5 条风险 + 概率/影响/策略，覆盖 R1 锁饥饿 ~ R5 CI 窗口 | §6 |
| 0 实现发生在 plan 前 | ✅ 仅做 Grep/Read 调研，无源码修改 | §1 研究命令 |
