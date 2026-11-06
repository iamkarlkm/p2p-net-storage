# DsRegistry P2 四方向扩展：前缀查询 + ReadWriteLock 并发读 + ORM Bridge + REG_MULTI_SZ/REG_LIST 语义

## 1. 背景

承接 P0 已交付 100% 闭环的 `DsRegistry` 通用注册表核心（9 Public API + Category/DataType 13×69 规范 + 目录树 DsFreeRing + 5 DsHashMap 映射 + value 双存储 + 51 tests 0F0E）。用户 2026-09-12 AskUserQuestion 多选 4 项 P2 方向：① P2-1 DsRegistry 索引与快速查询；② P2-2 DsRegistry MVCC 快照隔离；③ P2-3 REG_DATABASE_* 与 ORM 桥接；④ P2-4 REG_MULTI_SZ / REG_LIST 语义实现。

### 核心不变式（4 条，每条都必须在任何 future change 中保持）

1. **零侵入不改底层**：DsTypeString/DsTypeFixedBucketStore/DsFreeRing/DsHashMap/MultiTypeDataId 一行不动，不新增 TypeID。
2. **完全向后兼容**：P0 旧 9 个 Public API（putLong/putString/putBytes/putBytesFromFile / get / exists / remove / listChildren / listChildrenEntries + walk + sync + close）的签名、语义、异常完全不变；旧数据零迁移；任何 P0 测试每阶段必绿。
3. **Karpathy Simplicity First**：每个方向最小代码集（不引入 B-Tree/JSON 库/额外 MVCC 版本记录表等），复用 JDK 标准库 + 复用 P0 已交付 DsRegistry 基础能力；**不新增 DataType 系统常量**（REG_DATABASE_* 缺失用 REG_SZ/REG_DWORD 等价替代）；**不新增公开 Codec API**（DsRegistryMultiSzCodec package-private，经 Facade 间接访问）。
4. **Performance First**：读锁并发（非公平 ReentrantReadWriteLock 吞吐优先）、写锁串行化；walk 内 snapshotChildren 数组不递归 listChildren 省锁；MultiSz 显式 length prefix 零 JSON 依赖（O(N) 单次遍历）。

## 2. 实现方案

### 2.1 P2-1 前缀索引与快速查询（findByPrefix + listKeys Public API）

**文件**：`p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java:L877-L960`

- `findByPrefix(String dirPrefix, int maxDepth, int limit, Predicate<String> pathFilter)`：
  - 默认 `limit=1000`（防超大集合 OOM），`pathFilter=null` 即全扫不截子树
  - 基底调 `walkInternal(visitor)`：visitor.onEntry 计算 `relDepth = entry.key().split("/").length - baseDepth`，relDepth>maxDepth → skip；`out.size()>=stopAt` → **visitor.onEntry 立即 return false 终止整棵 walk（短路控制）**
  - `pathFilter` 仅 visitor 内部对 entry 过滤；**绝不阻断 walk 下降子树**（若 pathFilter.test("/")=false 则 walkInternal 直接 return true 截整树，是本 P2 中 P21 首次实现的 gotcha，已在 invariant 固化）
- `listKeys(String dirPrefix, int maxDepth)`：`return findByPrefix(dirPrefix, maxDepth, Integer.MAX_VALUE, null).stream().map(e->e.key()).toList()` 薄封装。

### 2.2 P2-2 MVCC 快照隔离（降级 ReadWriteLock 方案）

**文件**：`p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java:L57-L60`（字段）+ L430-L960（Facade 加锁）

- **Research 否决原 MVCC 计划**：DsDatabaseLocal MVCC 架构是单 DsFixedBucketStore row bucket + 独立 versionRecord bucket（versionRecord 长度/rowId 与 versionId bucket power 不同），与 DsRegistry 6 TypeID 物理隔离+5 DsHashMap+DsFreeRing 子目录架构**完全不兼容**；硬搬会新增 TypeID=205、大改底层 5 大存储原语，破坏不变式 1。
- **降级方案 Simplicity First**：
  - 字段替换：`ReentrantLock writeLock` → `ReadWriteLock rwLock = new ReentrantReadWriteLock(false)`（非公平，读多写少吞吐优先）
  - 加锁分层（Facade 入口加，内部 helper 不加）：
    - **读锁（共享）**：get / exists / listChildren / listChildrenEntries / findByPrefix / listKeys / walk
    - **写锁（独占）**：putLong / putString / putBytes / putBytesFromFile / putList / remove（含 keyId 查询整链路包裹，避免 stale put）/ sync / close
  - 生命周期守卫：`volatile boolean closed = false` + `private checkOpen() { if (closed) throw new IllegalStateException("registry closed"); }`，所有 Public API 开头调用；close() 结尾 `closed = true`。

### 2.3 P2-3 REG_DATABASE_* 与 ORM 桥接（DsRegistryOrmBridge 独立类）

**文件**：`p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistryOrmBridge.java:L1-L155`

- 3 Public Methods：
  - `registerEntity(Class<?> entityClass, String baseDir)`：返回 `base + "/" + schemaId + "/" + simpleName`
    - 目录结构：`baseDir/schemaId/simpleName/` 下存：
      - `.className`（REG_SZ：entityClass.getName()）
      - `.rowLength`（REG_DWORD：schemaDef.rowLength）
      - `columns/<colName>/type`（REG_SZ：c.typeName）
      - `columns/<colName>/length` / `precision` / `scale` / `defaultValue`（REG_SZ or REG_DWORD）
      - `columns/<colName>/indexed` / `mvcc`（REG_DWORD：0/1）
    - **幂等守卫**：每个叶子前 `if (!r.exists(typeKey)) r.putString(typeKey, c.typeName, DataType.REG_SZ);`，重复 registerEntity 绝不覆盖。
  - `getEntityRootKeyPath(Class<?> entityClass, String baseDir)`：纯计算 `EntitySchemaUtil.schemaOf(clazz).schemaId` + clazz.getSimpleName()，零 I/O，与 registerEntity 返回值严格 equals。
  - `listEntitySchemas(String baseDir)`：`r.listChildren(baseDir)` → 返回 schemaId 集合（过滤非十六进制/非 schema 目录由上层过滤，当前 baseDir 只放 schema 子目录）。
- **Karpathy 决策不新增 REG_DATABASE_* 常量**：
  - Grep 确认 DataType 系统预定义仅 REG_DATABASE_CATALOG~REG_DATABASE_COLUMN_GROUP（0x60~0x6A），缺 TYPECODE/COLUMN_LENGTH/DATASIZE/MVCC/FULL_RESOURCE_DESCRIPTOR；
  - 不新增 6 个系统常量（会扩大 public surface、无既有业务使用证据），统一用 REG_SZ（存字符串元数据）+ REG_DWORD（存 32-bit 数值元数据），业务含义由 key 名（"/type" "/indexed" "/mvcc" 等）表达。
- **配套 DsRegistry 权限提升**：P0 L206 `private long mkdirs(...)` → `public long mkdirs(...)`，P2-3 OrmBridge 需要在 "/db/schemaId/simpleName/columns" 多级目录中建中间节点，必须可外调；内部 checkOpen + writeLock 包裹仍保留，对 P0 零行为差异。

### 2.4 P2-4 REG_MULTI_SZ / REG_LIST 完整语义实现

**文件**：
- Codec：`p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistryMultiSzCodec.java:L1-L90`（package-private）
- Entry：`p2p-db/src/main/java/com/q3lives/ds/registry/RegistryEntry.java:L3-L155`（listValue 字段 + asList()）
- Public API：`p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java:L343-L363`（putList）+ L466-L572（getInternal 解码）

1. **Codec 格式（short LE length prefix，零分隔符依赖）**：
   ```
   [count:2B LE unsigned] × foreach([len_i:2B LE signed][bytes_i UTF-8])
   ```
   - 项数上限 65535（count unsigned 2B），单元素 UTF-8 上限 32767B（len_i signed 2B，负值=非法抛 IAE）
   - encode/decode 严格按切 len，不依赖分隔符；元素含 NUL(0x00)、Unicode、空串全安全
2. **RegistryEntry listValue 类型安全**：
   - Builder + 构造器：`Optional<List<String>> listValue`，构造器用 `Collections.unmodifiableList(...)` 不可变包装
   - `asList()`：仅当 dataType ∈ {REG_MULTI_SZ, REG_LIST} 才 return listValue.orElse(emptyList)；其他 DataType 抛 `UnsupportedOperationException("asList only supported for REG_MULTI_SZ/REG_LIST, got=" + dataType)`
3. **Public API putList(keyPath, values, listType)**：
   - listType 必须是 REG_MULTI_SZ（Category.STRING）或 REG_LIST（Category.COMPLEX），否则抛 IAE；
   - null/empty values 合法（→ empty list，空短帧 2B `00 00`，packSmallBytes inline 仍能塞下）；
   - 复用 P0 双通路：`byte[] frame = DsRegistryMultiSzCodec.encode(values)`
     - frame.length == 0 → `storedVal = 0L`（空列表）
     - frame.length ∈ [1..7] → packSmallBytes inline
     - frame.length ≥ 8 → 4-byte LE length header + payload 写 valueComplex DsTypeFixedBucketStore
   - **核心 bug 修（valueComplex key 对齐）**：原 L361 写 `valueComplex.put("reg_vals", "cmplx", frame)`，但 getInternal 走 Category.STRING/COMPLEX → getValueBytes → store.get("reg_vals", "bin", typedId, readLen) → bucket key 不一致读 null → decode 空列表；L361 统一改 `"bin"` 后 P24 5/5 断言 PASS。
4. **getInternal 解码分支**：Category.STRING（REG_MULTI_SZ）或 Category.COMPLEX（REG_LIST）时：先 MultiTypeDataId.decodeTypeId 判外部 typedId（同 NUMERIC/BINARY 守卫，避免 8B typedId 误 unpack），inline 解 packSmallBytes 或 external 解 getValueBytes LE header，最后 DsRegistryMultiSzCodec.decode → builder.listValue(decoded)。

## 3. 边界条件与失败模式（5 类已知 Gotcha）

1. **valueComplex bucket key "bin" vs "cmplx" 不一致**：getValueBytes 读通路内部 store.get 用 "bin" 作第二个参数（与 valueBinaries 写通路对齐），写通路错写 "cmplx" → bucket 隔离不读，返回 null，DsRegistryMultiSzCodec.decode(null) 返回空列表，无显式报错极难定位；**invariant 固化：valueComplex 第二参数必须是 "bin"**。
2. **walk pathFilter 截子树 gotcha**：DsRegistry.walk(basePath, 0, maxDepth, pathFilter, visitor) 先对 basePath 调 pathFilter.test(basePath)，若 basePath（通常是 "/"）不 match → walkInternal 直接 return true 整棵树不遍历；**修法：pathFilter=null，visitor 内部 if(entry.key().startsWith(prefix)) 过滤**，P21 首次实现的 13 keys 集合空断言就是这个根因。
3. **remove stale keyId 查询 gotcha**：P2-2 ReadWriteLock 下，若 keyId 查询（allocateNextSequenceLocked 内部）在 writeLock 之外，并发 put 同 key → stale keyId 写入 HashMap 导致 put 值被删或删错叶子；**invariant：remove() 开头 writeLock.lock()，全链路包裹**。
4. **12 DsRegistryOrmBridge 编译错误分两类**：
   - 类 1（6 次）：`mkdirs(java.lang.String) 在 DsRegistry 中是 private` → P0 L206 `private` → `public`，内部 checkOpen + writeLock 仍保留；
   - 类 2（6 次）：`找不到符号: REG_DATABASE_TYPECODE/COLUMN_LENGTH/INDEXED/MVCC/DATASIZE/FULL_RESOURCE_DESCRIPTOR` → 替换为 REG_SZ/REG_DWORD，**绝不硬加 6 个 DataType 系统常量**（Karpathy 禁止无证据扩大 public surface）。
5. **Windows JUnit5 @TempDir cleanup ERROR**：DsFreeRing/DsTypeFixedBucketStore 的 RAF 句柄在 try-with-resources 后仍未 finalize，@TempDir @AfterEach 强制 cleanup 抛"另一个程序正在使用此文件"升级为 surefire Errors（断言 0 Failures 但 mvn 报红）；**统一基线：每 @Test 开头 `File tmp = Files.createTempDirectory("dsreg<prefix>-").toFile(); tmp.deleteOnExit();`，JVM 退出时 finalizer 释放后才 deleteOnExit 删目录**，P21-P24 16 tests 0 Errors 验证。

## 4. 验证命令（5 条全部 exit 0 实锤，brain session run 已记录 2 条）

```powershell
# Step 1 — 必做编译校验：任何 mvn 行为与代码不一致第一步
mvn -pl p2p-db -q test-compile
# 预期：BUILD SUCCESS，0 ERROR

# Step 2 — P0 基线 14/14：任何 P2 改动后必须绿
mvn -pl p2p-db '-Dtest=DsRegistryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 预期：Tests run: 14, Failures: 0, Errors: 0, Skipped: 0

# Step 3 — 16/16 四专项：P21(4) + P22(3) + P23(4) + P24(5)
mvn -pl p2p-db '-Dtest=DsRegistry_P21_PrefixFindTest,DsRegistry_P22_ReadWriteLockConcurrencyTest,DsRegistry_P23_OrmBridgeTest,DsRegistry_P24_MultiSzListTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 预期：Tests run: 16, Failures: 0, Errors: 0, Skipped: 0

# Step 4 — 12 类联合回归 ≥53 tests：底层存储不回归
mvn -pl p2p-db '-Dtest=DsRegistryTest,DsHashMapTest,DsDataTest,DsStringTest,DsTypeFixedBucketStoreTest,DsTypeStringTest,DsRegistry_P21_PrefixFindTest,DsRegistry_P22_ReadWriteLockConcurrencyTest,DsRegistry_P23_OrmBridgeTest,DsRegistry_P24_MultiSzListTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 预期：Tests run: 53, Failures: 0, Errors: 0, Skipped: 0

# Step 5 — 可选：性能基线回归（P0 baseline 约 27k ops/sec，Step 2 已包含 perf128x100Update 断言）
# 预期：Step 2 INFO 行中 "ops/sec" 数字 >25000（无回归 10% 内）
```

## 5. 文件变动清单（9 个文件变动 + 2 个文档，零底层 5 大存储修改）

### 变动类文件（共 6 个 + 2 个新类 = 8 个源码）

1. **`DsRegistry.java`（核心变动 7 处，P0 9 API 零签名破坏）**：
   - L12-L16 import：Lock / ReadWriteLock / ReentrantReadWriteLock
   - L57-L60 字段：ReentrantLock → ReadWriteLock + writeLock view + readLock view + volatile boolean closed=false
   - L206 mkdirs：private → public（P23 OrmBridge 需外调）
   - L262-L341 putLong/putString/putBytes/putBytesFromFile：前置 checkOpen() + writeLock 外移
   - L343-L363 新增 Public API putList(keyPath, values, listType)：类型校验（只接受 REG_MULTI_SZ/REG_LIST）、null/empty 安全、双通路、valueComplex key="bin"（对齐）
   - L430-L572 get/getInternal：前置 checkOpen + readLock 包裹 + getInternal 私有拆分 + Category.STRING/COMPLEX 分支 REG_MULTI_SZ/REG_LIST 自动 DsRegistryMultiSzCodec.decode → builder.listValue()
   - L544-L602 remove：前置 checkOpen + writeLock 外移包裹 keyId 查询（避免 stale put）
   - L686-L784 listChildren/listChildrenEntries：整体 readLock + getInternal(dir) 不锁
   - L799-L875 walk + walkInternal：readLock 包裹 walk，内部 snapshotChildren(dirNodeId) 数组不递归 listChildren 省锁；sync/close 写锁包裹，close() 结尾 closed=true
   - L877-L960 新增 Public API findByPrefix + listKeys
2. **`RegistryEntry.java`（新增 listValue，向后兼容零破坏）**：import Collections/List + listValue Optional 字段 + 构造器不可变包装 + asList() 按 DataType 路由 + Builder.listValue(v)
3. **`new DsRegistryMultiSzCodec.java`（package-private，不公开 API）**：encode/decode short LE length prefix + 严格上限校验
4. **`new DsRegistryOrmBridge.java`（独立类，0 侵入 core）**：registerEntity（幂等 exists 前置）/ getEntityRootKeyPath（纯计算）/ listEntitySchemas

### 测试类（4 个全新 16 tests，全部 TempDir=Files.createTempDirectory+deleteOnExit）

5. `DsRegistry_P21_PrefixFindTest.java`：4 tests（前缀查询/PathFilter 语义/listKeys 深度差/listChildrenEntries walk 省锁）
6. `DsRegistry_P22_ReadWriteLockConcurrencyTest.java`：3 tests（8读1写 2s 并发无 torn read / 3写 201key 序列化可见 / close 拒后续）
7. `DsRegistry_P23_OrmBridgeTest.java`：4 tests（目录结构+列值匹配 / getEntityRootKeyPath 与 registerEntity 一致 / listEntitySchemas 3 schemaId / 幂等不覆盖）
8. `DsRegistry_P24_MultiSzListTest.java`：5 tests（codec 空/NUL/Unicode 往返 / 短 inline / 大 1000 项 close-reopen 外部持久化 / 非法类型+null 安全）

### 文档类（2 个，durable 三件套补全）

9. **`AGENTS.md` Local Notes 末尾追加 4 Invariants + 1 Gotcha**：Public API 加锁分层 / 前缀查询 pathFilter 不截子树 / OrmBridge 幂等 REG_DATABASE_* 不新增常量 / MultiSz length prefix + valueComplex key="bin" / Windows TempDir 策略
10. **`.brain/context/current-state.md` 追加本块 outcome**：16 专项 + 53 联合回归防线 + Gotcha 列表 + 验证命令

## 6. 后续扩展建议（P3，不阻塞本 P2 100% 交付闭环）

1. **P3-1 findByPrefix 走 DsString masterIndex 快速候选**：当前 walkInternal 深度 cut 仍是 O(N) 树遍历；DsRegistry.keyStrings 内部 masterIndex 的 indexId 排序可天然支持前缀扫描（按 key UTF-8 bytes prefix 做 iterator 过滤），未来 P3-1 可 O(logN+K) 定位候选，再用 DsRegistry.get() 读值，超大目录场景（>10M keys）性能更优。
2. **P3-2 ReadWriteLock → StampedLock 乐观读**：当前 ReentrantReadWriteLock 仍是悲观读锁，写线程多时竞争仍重；StampedLock 的 tryOptimisticRead() 纯读无锁，适合 get()/exists() 这种纯读+短路径；但会引入 validate stamp 二次验证复杂度，当前 13 万 ops/sec 规模无瓶颈，不做。
3. **P3-3 DsRegistry 与 DsDatabaseLocal 双向同步**：当前 OrmBridge 是单向"把 EntitySchemaDef 写注册表"，未来 P3-3 可写反方向 DsRegistrySchemaManager（从注册表读取 className + 列属性 → 动态 Class.forName() 构建 EntitySchemaDef → 调用 DsDatabaseLocal.registerSchema 动态装载），支持运行时无编译新增实体。
4. **P3-4 REG_DWORD 升级 64-bit（REG_QWORD）**：当前 length/precision/scale/rowLength 用 REG_DWORD（32-bit），若未来 VARCHAR(2^31) 或 rowLength >2GB 场景可用 REG_QWORD（DataType.REG_QWORD），但现 SchemaDef 字段全是 int 32-bit，无场景不做。
