# 通用注册表系统 (DsRegistry) Implementation Plan

## 1. Repository Research & 现有语义基线

### 1.1 底层存储能力（已验证可直接使用）

| 组件 | 位置 | 关键 API | 注册表中的用途 |
|---|---|---|---|
| [DsTypeString](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/core/DsTypeString.java) | `p2p-db/src/main/java/.../core/` | `add(String)→typedId` / `get(typedId)→String` / `remove(typedId)` / `contains(String)` / `update(oldId,newVal)→newId` / `getIndexId(key, createIfAbsent)` / `refCount` / `retain` / `release` | ① 存储注册表 `/a/b/c/d` 形式的路径字符串；② 注册表 value 属 STRING/TEXT/CONFIG/SCRIPT/DOCS/CODE 的内容串 |
| [DsTypeFixedBucketStore](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/bucket/DsTypeFixedBucketStore.java) | `p2p-db/src/main/java/.../bucket/` | `put(space,type,byte[])→typedId` / `get(space,type,typedId,len)→byte[]` / `overwrite(space,type,typedId,byte[])` / `update(...UpdatePolicy...)→typedId` / `remove(space,type,typedId)` / `getMeta/putMeta/removeMeta` / `typeDirFor(rootDir,typeId)` | ① 存储 >64 位 / 变长 byte[] 的 REG_BINARY/IMAGE/BLOB/MULTI_SZ/ARRAY/MAP/DOCS...；② 小对象可以直接塞 value 编码 |
| [DsFreeRing](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/core/DsFreeRing.java) | `p2p-db/src/main/java/.../core/` | `offerUnique(long)→boolean` / `offer(long)→boolean` / `poll→long` / `remove(long)→boolean` / `contains(long)→boolean` / `snapshot→long[]` / `count/size/clear` | 目录子项 id 集合：每个 DIR node 拥有一个 DsFreeRing，子项（DIR 节点 id 或 LEAF key node id）作为 ring 的成员；用作"目录 → 子项集合"的有序/无序列表 |
| [DsHashMap](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/collections/DsHashMap.java) | `p2p-db/src/main/java/.../collections/` | `put(key:long,val:long)→Long` / `get(key:long)→Long` / `remove(key:long)→Long` / `entrySet/forEach/range` / `size/sizeLong` | 核心 64→64 映射：<br>① `keyPathId → dirNodeId`（DIR 目录映射）<br>② `keyPathId → leafNodeId`（LEAF 叶子映射）<br>③ `leafNodeId → valueOrValueId`（64 位内 value，或指向 DsTypeString/DsTypeFixedBucketStore 的存储 typedId）<br>④ `dirNodeId → childRingId`（DsFreeRing file id） |
| [MultiTypeDataId](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/util/MultiTypeDataId.java) | `p2p-db/src/main/java/.../util/` | `encode/decodeTypeId/decodePower/decodeIndexId` + `MAX_TYPE=65535` | 注册表专用 TypeID 分配范围（用户自定义最高位置 1 与系统预定义区分） |

### 1.2 关键已存在的不变式 / 约束（直接继承不重改）

- **DsType* 物理隔离不变式（AGENTS.md L160-L165）**：每个 TypeID → `rootDir/type_{HEX}/` 独立子目录；matchesThis 守卫确保 typedId 不被跨类型"碰巧读到"。
- **DsFreeRing 有序遍历 + expand 零拷贝不变式（AGENTS.L168-L181）**：count + 两段 [head→cap)∪[0→head)；expand 只 setLength + 调整 tail，zero-copy。
- **Performance First 核心规则（AGENTS Local Notes）**：禁止全量拷贝、禁止无证据防御性检查；所有 I/O/分配最小化。
- **用户需求显式硬约束（VERBATIM）**：
  1. key：DsTypeString 存 / 开头的路径（Linux 风格）；
  2. 目录树：key 解析为目录树，DsFreeRing 存子目录项 id；
  3. keyId → value / valueId：DsHashMap 存映射；
  4. 超过 64 位的 value：DsTypeFixedBucketStore 存储（≤64 位的 value 直接存进 DsHashMap.value，零额外存储）；
  5. Category + DataType 枚举：Category = 物理形态；DataType = REG_ 业务类型 + code 位运算 id；
  6. 用户自定义 DataType：code 最高位 bit15 = 1，以示与系统预定义区分。

---

## 2. 文件与模块修改清单（NEW 为主，MODIFY 仅 2 处测试注册 + 1 处 invariant）

| 类型 | 路径 | 作用 | 规模估计 |
|---|---|---|---|
| **NEW** | `p2p-db/src/main/java/com/q3lives/ds/enums/Category.java` | 顶层物理形态枚举：12 大类 BINARY/STRING/NUMERIC/COMPLEX/CONFIG/SCRIPT/DATABASE/DOCS/ZIP/SETUP/EXEC/P2P | ~60 行 |
| **NEW** | `p2p-db/src/main/java/com/q3lives/ds/enums/DataType.java` | REG_ 业务类型 + code + generateId/parseFromId 位运算 + getByCode(code) + 自定义动态注册表（ConcurrentHashMap + code bit15=1）| ~300 行 |
| **NEW** | `p2p-db/src/main/java/com/q3lives/ds/registry/RegistryEntry.java` | 单条注册表项的 immutable 包装：keyPathId + dirId/leafId + DataType + 64-bit inline value 或 typedId 引用；提供 `asLong/asString/asBytes/asInt/asDouble/asObject<T>` 类型安全的 getter，+ `Category getCategory()` 路由 | ~180 行 |
| **NEW** | `p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java` | **核心注册表门面类（Facade）**：构造(rootDir) / close / put / get / remove / exists / listChildren(dirPath) / walk(visitor) / mkdirs(path) / createKey / putBytes / putString / putLong / putInt / putDouble / putTimestamp / putMultiSz / putBytesFromFile / sync | ~550 行 |
| **NEW** | `p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistryNodeSerializer.java` | DIR node 与 LEAF node 的二进制序列化：`nodeHeaderByte(directory=1/0 + reserved7bits + keyType8bits + valueType8bits + flags16bits) + 4-byte keyLen + 8-byte parentDirId` 用于持久化 node meta；供 DsRegistry 路由 | ~160 行 |
| **NEW** | `p2p-db/src/main/java/com/q3lives/ds/registry/RegistryWalker.java` | 目录遍历 visitor 接口 + 4 个默认实现（dumpPaths/toMap/toJsonSchema/toKeysFilteredByDataType） | ~200 行 |
| **NEW** | `p2p-db/src/test/java/ds/DsRegistryTest.java` | 注册表核心集成测试：① put/get 12 类 REG_*；② 目录树 mkdirs + listChildren；③ keyId DsHashMap 持久化；④ DsFreeRing 子项集合；⑤ ≤64 位 inline vs >64 位 external 存储；⑥ 用户自定义 DataType（code bit15=1）动态注册；⑦ 重启 rootDir 后全量 reload 一致性；⑧ AGENTS matchesThis 守卫下的跨 typedId 越权读失败；⑨ 超大 JSON 文档（1MB）写回读一致性；⑩ 128 paths × 100 次 update = 12,800 ops 性能基线（warmup 后 ≥ 1M ops/sec 吞吐下限，不做硬性 assert 只在 INFO 打）| ~700 行 |
| **MODIFY** | `AGENTS.md L160-L181` | 在 MultiTypeDataId invariant 下追加一条 DsRegistry 不变式："注册表 key 路径以 `/` 开头；DIR node 与 LEAF node 分离存储；≤64-bit value 内联在 DsHashMap value，>64-bit 存 DsTypeString/DsTypeFixedBucketStore 再把 typedId 放 DsHashMap；`code|=0x8000` 标记用户自定义 DataType" | +~12 行 |
| **MODIFY** | `.brain/context/current-state.md` 末尾 | 追加 DsRegistry 2026-09-11 session outcome 块（37+new tests = N 个 0F0E、核心设计决策、关键 invariant 3 条、gotcha 列表）| +~80 行 |
| **NEW** | `.brain/resources/changes/dsregistry-category-datatype-registry-directorytree-dsfreering-dshashmap.md` | DsRegistry verification recipe（verification 命令 + 物理/逻辑双层隔离证明 + 位运算 generateId/parseFromId 向量表）| ~180 行 |

---

## 3. 依赖顺序的实现步骤（从上到下，每一步都可编译 + 局部 UT）

### Step 1：Category + DataType 枚举基础 + 位运算 ID 生成器 + 动态注册

1. 定义 **Category enum**（12 大类）：
   - BINARY / STRING / NUMERIC / COMPLEX / CONFIG / SCRIPT / DATABASE / DOCS / ZIP / SETUP / EXEC / P2P
   - 每个 Category 有 `public String desc()` + `public static final long MASK = 1<<ordinal`（可选，用于未来按 Category 批量过滤）
2. 定义 **DataType enum**（系统预定义，code bit15=0）：
   - 完全按用户提供的完整列表（REG_BINARY ~ REG_CODE_PYTHON）逐项填入；
   - 字段：`final Category category` / `final int code`（低 15 位有效，0≤code<0x8000）/ `final String desc`；
   - 构造器强制校验 `code >=0 && code < 0x8000` → 系统预定义 **bit15 必须 0**。
3. DataType 内静态常量：
   ```java
   private static final int CODE_BITS = 16;
   private static final int SEQ_BITS = 64 - CODE_BITS; // = 48
   private static final long SEQ_MASK = (1L << SEQ_BITS) - 1;
   ```
4. DataType 内 public 方法：
   - `long generateId(long sequenceId)`：`((long)code << SEQ_BITS) | (sequenceId & SEQ_MASK)`；
   - `static DataType parseFromId(long id)`：`code = (int)(id >>> SEQ_BITS);` → 先查系统预定义 CODE_MAP；命中返回；否则查动态用户定义 CUSTOM_MAP；两不命中 fallback REG_BINARY；
   - `static Optional<DataType> getByCode(int code)`；
   - `Category getCategory()`、`int getCode()`、`String getDesc()`、`boolean isUserDefined()`（= code & 0x8000）。
5. DataType 动态注册 API（用于用户自定义类型，code bit15=1 强制）：
   ```java
   private static final ConcurrentHashMap<Integer, DataType> CUSTOM_MAP = new ConcurrentHashMap<>();
   public static synchronized DataType registerUserDefined(int code, Category cat, String desc) {
       if ((code & 0x8000) == 0) throw new IllegalArgumentException("User-defined DataType code must set bit15=1");
       // 低 15 位可用作 subtype，唯一检查：CUSTOM_MAP.putIfAbsent
       DataType dt = new DataType(cat, code, desc);  // 走专用 private 构造器（不进 values()）
       CUSTOM_MAP.put(code, dt);
       return dt;
   }
   ```
6. 性能约束（Performance First）：
   - `generateId` / `parseFromId` / `getByCode` → **无堆分配、无数组遍历、纯位运算 + 1 次 HashMap 查**；
   - 系统预定义 CODE_MAP 用 `Collectors.toUnmodifiableMap`，不可变。

### Step 2：RegistryNodeSerializer — DIR/LEAF 节点元数据持久化格式

1. DIR node 和 LEAF node 分别有一个独立的 64-byte meta block（恰好 fit 进 64-byte bucket 的 power=6=64B，避免浪费）。
2. Meta block 布局（都存进同一个 TypeID 的 DsTypeFixedBucketStore，space=`reg_meta` / `type=nodes`）：
   ```
   byte  0      : flags.7 = isDirectory (1=DIR,0=LEAF); flags.[6:5]=reserved; flags.[4:0]=reserved
   byte  1      : keyDataTypeId (high 8-bit of DataType.code，低 8-bit 在下 byte)
   byte  2      : keyDataTypeId (low 8-bit)  => 合成 16-bit code = DataType.code 的原始值
   byte  3      : valueDataTypeId (high 8-bit)
   byte  4      : valueDataTypeId (low 8-bit) => 合成 value 的 DataType.code
   byte  5-7    : reserved (0)
   byte  8-15   : keyPathId (8B long，DsTypeString typedId，key="/a/b/c")
   byte 16-23   : parentDirNodeId (8B long，根 = 0)
   byte 24-31   : siblingOrderOrReserved (8B long，目录列表顺序/自定义排序)
   byte 32-39   : valueOrValueId (8B long：≤64-bit inline value；或 STRING/BINARY typedId)
   byte 40-47   : childRingFileId (8B long：DIR node 才有效，对应 DsFreeRing 的 File id，DsFreeRing 用 long[目录子项 nodeId] 存)
   byte 48-55   : created / modified 时间戳 低 8B（System.currentTimeMillis()）
   byte 56-63   : 校验和 FNV-1a 64 (56B header)，0 表示不校验（默认 0，遵循"只在有 bug 证据时加校验"原则）
   ```
3. 提供 `long allocateNodeId(DsTypeFixedBucketStore store, boolean isDirectory)`：power=6=64B 恒定分配，零条件分支。
4. 两个主要方法：
   - `writeNode(store, nodeId, NodeMeta meta)`：`store.overwrite("reg_meta","nodes", nodeId, serialize(meta))`
   - `NodeMeta readNode(store, nodeId)`：反序列化，返回 immutable 对象（字段访问 getter）。

### Step 3：DsRegistry Facade 核心类（注册表主 API）

**构造器签名（不做重载泛滥）**：
```java
public DsRegistry(String rootDir) throws IOException
```
构造内部：
- 分配 6 个 TypeID：
  | TypeID 用途 | value | 常量名建议（package-private）|
  |---|---|---|
  | 注册表 key 路径字符串 | 200 | `TYPE_REGISTRY_KEY = 200` |
  | 注册表 value STRING/TEXT/CONFIG/SCRIPT/DOCS/CODE | 201 | `TYPE_REGISTRY_VALUE_STRING = 201` |
  | 注册表 node meta（64B bucket）| 202 | `TYPE_REGISTRY_NODE_META = 202` |
  | 注册表 >64-bit BINARY value 内容 | 203 | `TYPE_REGISTRY_VALUE_BINARY = 203` |
  | 注册表 value MULTI_SZ / COMPLEX ARRAY 序列化（变长 bytes）| 204 | `TYPE_REGISTRY_VALUE_COMPLEX = 204` |
  | 保留 | 205 | `TYPE_RESERVED = 205` |
- 实例化 2 个 DsTypeString：
  - `keyStrings = new DsTypeString(rootDir, TYPE_REGISTRY_KEY)`
  - `valueStrings = new DsTypeString(rootDir, TYPE_REGISTRY_VALUE_STRING)`
- 实例化 3 个 DsTypeFixedBucketStore（默认 TypeID 分别 = 202/203/204）
- 实例化 4 个 DsHashMap（文件分别放在 `<rootDir>/registry/` 下）：
  | HashMap 文件 | key 含义 | value 含义 |
  |---|---|---|
  | `registry/keyId_to_leafOrDirId.hash` | keyPathId（DsTypeString typedId）| leafNodeId（>0） 或 dirNodeId（负数用第 63 位标记 / 更简单：另开一个 `keyId_to_dirId.hash`，两者并存，查到谁算谁，先查 dir 再查 leaf）|
  | `registry/dirId_to_ringId.hash` | dirNodeId | childRingFileId（对应 DsFreeRing 构造的 File id） |
  | `registry/leafId_to_valueOrId.hash` | leafNodeId | ≤64-bit 内联 value 或 external typedId（DsTypeString typedId / DsTypeFixedBucketStore typedId） |
  | `registry/next_sequence.hash` | 固定 key = 1L | 下一个可用的 sequenceId（DataType.generateId 时加锁递增，避免重复） |
- 根目录初始化：`/` 对应 dirNodeId = 1（0 保留无效）。根 freeRing 自动创建。

**核心 API（9 个公开方法，不多不少）**：

1. `long put(String keyPath, DataType type, long inlineValue)` — **64-bit 内联 value 直接写**：
   - keyPath 必须以 `/` 开头，禁止 `..` 段，禁止空段（`//` 跳过）；
   - mkdirs(parent keyPath) 创建缺失的中间 DIR node；
   - 若 LEAF 已存在 → 只更新 leafId_to_valueOrId.hash 与 node meta；
   - 返回 leafNodeId。
2. `long put(String keyPath, String value, DataType stringType)` — **STRING/TEXT/CONFIG/DOCS/CODE 类型**：
   - 验证 `stringType.getCategory()` ∈ {STRING, CONFIG, SCRIPT, DOCS}；
   - 写 `valueStrings.add(value)` 得到 typedId；
   - 调 `put(keyPath, REG_VALUEPAYLOAD_PTR, typedId)`（自定义中间 DataType，内部用，不暴露 public）。
3. `long put(String keyPath, byte[] value, DataType binaryType)` — **BINARY/IMAGE/BLOB 类型**：
   - `value.length > 8` → 写 DsTypeFixedBucketStore(type=TYPE_REGISTRY_VALUE_BINARY, space=`reg_vals`, type=`bin`) 得 typedId，leaf.value 存这个 typedId；
   - `value.length ≤ 8` → 8-byte 内联（前 byte=len+flag，后跟 bytes），`inlineValue` 写 DsHashMap，零额外 bucket。
4. `RegistryEntry get(String keyPath)` — **统一读入口**：
   - 解析 keyPath → keyStrings.getIndexId(key,false)，keyId null→return 空；
   - 先查 `keyId_to_dirId.hash` → 命中 return DIR RegistryEntry（附带 listChildren 迭代器能力）；
   - 再查 `keyId_to_leafOrDirId.hash` → 命中 → 拿 leafNodeId → 查 `leafId_to_valueOrId.hash` → 返回内联或反解 typedId 返回 STRING/BYTES。
5. `boolean exists(String keyPath)`：get != null。
6. `boolean remove(String keyPath, boolean recursive)`：删除 DIR 用 recursive=true 递归子项；
7. `List<String> listChildren(String dirPath)`：返回目录下一层的 **相对名字**（不含父路径），不含根前缀；
8. `List<RegistryEntry> listChildrenEntries(String dirPath)`：带类型与 value 概要的条目列表；
9. `void walk(RegistryEntryVisitor visitor, int maxDepth, Predicate<String> pathFilter)`：通用遍历器。

**内部 helper（10+ 个 private）**：
- `List<String> parseAndValidateKey(String path)`："/a/b/c" → ["a","b","c"]，校验规则；
- `long ensureKeyStringId(String key)`：keyStrings.getIndexId(key, createIfAbsent=true)；
- `long mkdirs(String parentPath)`：递归创建目录，复用 DIR node；
- `long allocateNextSequence()`：next_sequence.hash CAS 式递增（HashMap 单线程锁，不用 CAS j.u.c，Performance First：锁开销 10ns 级，足够）；
- `DsFreeRing openOrCreateChildRing(long dirNodeId)`：查 `dirId_to_ringId.hash`，无则 new DsFreeRing 并回写。

### Step 4：RegistryEntry + RegistryWalker（类型安全 + 遍历工具）

1. `RegistryEntry` immutable：
   - 字段：`String key`, `boolean isDirectory`, `DataType dataType`, `OptionalLong inlineValue`, `Optional<String> stringValue`, `Optional<byte[]> bytesValue`, `long nodeId`, `long parentNodeId`；
   - Type-safe getter：`int asInt()` / `long asLong()` / `double asDouble()` / `String asString()` / `byte[] asBytes()` / `Date asDatetime()` / `<T> T asObject(Class<T>, Serializer)`；
   - 内部用 `Category` 路由：NUMERIC → 直接 LongBits；STRING → DsTypeString.get；BINARY → DsTypeFixedBucketStore.get；COMPLEX → DataType 自己的自定义反序列化器（可选）。
2. `RegistryWalker`：
   - `interface RegistryEntryVisitor`：`boolean onEntry(RegistryEntry entry, int depth)`；
   - `DumpAllVisitor`：System.out 或 StringBuilder；
   - `ToMapVisitor`：Map<String, Object>；
   - `FilterByDataTypeVisitor`：按 DataType collect。

### Step 5：集成测试（DsRegistryTest）

9 大类覆盖（按依赖顺序写，前 3 个 smoke test 先绿，再推进）：

1. **Smoke put/get（≤64-bit inline）**：`/a/dword` + REG_DWORD=42；`/a/qword` + REG_QWORD=1L<<42；`/a/double` + REG_DOUBLE=3.14；restart DsRegistry(同 rootDir) → get 一致。
2. **STRING/TEXT put/get**：`/etc/profile` 写 REG_JSON=`{"a":1,"b":"x"}`；get JSON parse 还原；1MB JSON（`/huge/doc` REG_TEXT）往返 sha256 一致。
3. **BINARY/IMAGE put/get 外部存储**：200 bytes / 2KB / 200KB 三段，FNV-1a 写回读一致；8 bytes 以内（"/config/signature" 7B）验证写 DsHashMap.value 内联（assert DsTypeFixedBucketStore 的 data_size.dat 对应目录不存在 → 证明没走 bucket）。
4. **目录树 mkdirs + listChildren**：`/a/b/c/d/e` 叶子，`listChildren("/")={a}`；`listChildren("/a/b/c")={d}`；`listChildren("/a/b/c/d")={e}`。
5. **Dynamic DataType 用户自定义**：`registerUserDefined(0x8001, Category.BINARY, "MyBlobV2")` → assert `isUserDefined()==true`；`generateId(5)` 反向 `parseFromId` 拿到自定义实例；restart 后 CUSTOM_MAP 为空 → fallback 到 REG_BINARY（这是正确语义：动态类型不会持久化，除非上层自己持久化配置到 / 注册表的 reserved path，**本实现不做自动持久化**——遵循"最小集"原则，不引入额外 I/O）。
6. **matchesThis 守卫验证（防止巧合同 indexId 跨类型读到值）**：拿 TYPE_REGISTRY_KEY 的 DsTypeString id 去调 valueStrings.get(该 id) → assert null，refCount=0（matchesThis 短路）。
7. **walk + visitor**：构造 50 条键 → walk / depth=3 → count==50；depth=1 → 仅顶层。
8. **remove recursive**：`/a/*` 递归删除后 → listChildren("/") 不含 "a"；keyStrings.getIndexId("/a/b/c",false) == null 或 DsHashMap.get(keyId)==null。
9. **性能基线（INFO 打印，不 assert，防止 CI 环境波动）**：warmup 5000 次 put/get；再 128×100 = 12,800 ops → 打印 ops/sec。

---

## 4. Dependencies and Considerations（关键依赖 & 重要注意事项）

1. **依赖项（本项目已存在，无需额外引入 Maven 包）**：
   - Java 标准库：`java.io.*`、`java.nio.charset.StandardCharsets`、`java.util.*`、`java.util.concurrent.ConcurrentHashMap`、`java.util.function.*`
   - 现有 p2p-db 组件：DsTypeString / DsTypeFixedBucketStore / DsFreeRing / DsHashMap / MultiTypeDataId
2. **不引入的依赖（Performance First）**：Jackson / Gson（序列化/反序列化不在注册表内建，外部调用方做）；Guava / Commons Lang（不用）；Spring（不用）。
3. **DataType.code 冲突处理**：
   - 系统预定义（bit15=0）若有重复项？`Collectors.toUnmodifiableMap` 会在类加载时抛 `IllegalStateException`—— fail-fast 是预期行为（编译或启动就能发现）。
   - 用户 registerUserDefined(重复 code)？`CUSTOM_MAP.putIfAbsent` 拒绝（抛或返回旧值，选抛 IllegalArgumentException，fail-fast）。
4. **DsHashMap 构造参数确定**：默认 `new DsHashMap(new File(rootDir, "registry/xxx.hash"))`，不覆盖 hashOffset/hashLen/ptrSize 默认值；entryFile 不独立，用 DsHashMap 默认。
5. **并发安全策略（遵循 DsHashMap / DsFreeRing 单写多读策略）**：
   - DsRegistry 内部 1 把 `ReentrantLock` 覆盖 mkdirs/remove 递归写路径；热 put/get 无锁（依赖底层 DsHashMap 的内部锁语义）；
   - 不引入 ConcurrentHashMap 以外的高级同步器。
6. **DataType.generateId(sequenceId) 的序列分配**：
   - 系统用 `DsHashMap next_sequence.hash` 持久化；每次分配：`long seq = nextHash.get(1L) or 1; nextHash.put(1L, seq+1); return seq;`
   - 最大支持 seq 2^48-1（≈ 281万亿，足够）。
7. **与 AGENTS.md "DsType* 物理隔离" 不变式的对齐**：
   - 本注册表使用的 TypeID（200-205）均为独立 TypeID，每个都落在 `type_00C8 / type_00C9 / ...`（HEX(200)=C8）独立子目录里 → 物理隔离自动符合。
8. **Windows 路径长度限制注意**：DsFreeRing 构造时 file path 可能 >260 chars？用户 rootDir 极深的情况下会 hit，DsRegistry 构造时先 `root = new File(rootDir).getCanonicalPath()`，否则交给 JVM 处理（本项目不负责启用 longPaths）。
9. **"注册表 key=DsTypeString 以 / 开头"的编码 / 解码陷阱**：
   - keyPath 全部用 UTF-8；null key → NPE；空 key → 抛；不以 `/` 开头 → 抛 IllegalArgumentException。

---

## 5. Validation（验收 & 验证）

### 5.1 单元 / 集成测试（JUnit）

```powershell
brain session run --project . -- mvn -pl p2p-db '-Dtest=DsRegistryTest,DsTypeFixedBucketStoreTest,DsTypeStringTest,DsFreeRingAsInodeSetTest,DsHashMapTest,DsHashMapEntryFreeRingTest,DsFixedValueStoreTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

**PASS 判据**：
- Tests run: (37 原有 + DsRegistryTest N 个 new) = ≥ 46；Failures=0；Errors=0。
- DsRegistryTest 每个子断言独立可单跑 + 类级并行不冲突（@BeforeEach 用独立临时目录 `Files.createTempDirectory("dsreg").toFile()`，测试完 deleteOnExit）。

### 5.2 静态验证 / Code Review Checklist

1. **Category enum 12 大类齐全**（BINARY, STRING, NUMERIC, COMPLEX, CONFIG, SCRIPT, DATABASE, DOCS, ZIP, SETUP, EXEC, P2P）；用户枚举列表中的 "CODE" 归入 P2P（或保留 CODE 作为第 13 类 → 实际在 Step1 看用户原文，用户给了 REG_CODE_JAVA 等，最终 Category 列表应该 = 用户原始 + 合并冗余 → 计划里明确写：**Category 采用用户枚举中出现的所有大类名，合并 REG_FS_PATH 等归入 STRING，保持与用户原文一对一；总 Category 数 = 13 类**：BINARY / STRING / NUMERIC / COMPLEX / CONFIG / SCRIPT / DATABASE / DOCS / ZIP / SETUP / EXEC / P2P / CODE → 第 13 类 CODE，原计划 12 类 → 修正为 13 类）。
2. **DataType 位运算验证向量表**（手工写 6 条 assert，放进 DsRegistryTest 单测 static block 前跑一次）：
   ```
   REG_DWORD(code=0x20).generateId(1L) = 0x0020_000000000001
   parseFromId(0x0020_000000000001) = REG_DWORD
   REG_JSON(code=0x12).generateId(0xFFFFFF) = 0x0012_0000FFFFFFFFFFFFFF
   MyBlob(code=0x8001).generateId(0) = 0x8001_000000000000
   MyBlob.isUserDefined() == true
   REG_DWORD.isUserDefined() == false
   ```
3. **DsTypeString/DsTypeFixedBucketStore matchesThis 守卫不被绕过**：DsRegistry 内部**从不**直接调用底层 dataStore 的 toInternal/toTyped，只通过已公开 API（matchesThis 守卫已在 public get/refCount/remove 入口生效）。
4. **64-bit value 内联零额外存储证明（测试 #3）**：`/tiny/flag` 写入 3 bytes `REG_BINARY`（0xFF,0x01,0x02）→ `listFiles(rootDir/type_00CB)`（TYPE_REGISTRY_VALUE_BINARY=203=0xCB）的 `reg_vals/bin/` 目录内不应有对应 data_*.dat（assert 不存在），证明走了 inline。
5. **AGENTS.md invariant 追加**：在 MultiTypeDataId 区块下一条追加 **DsRegistry 三条不变式**（key slash 前缀；≤64 inline/>64 external；DIR/LEAF 分离 + DsFreeRing 子项；自定义 DataType code bit15=1）。

### 5.3 Perf & 资源

- `mvn -pl p2p-db -q test-compile` → exit 0；
- DsRegistryTest 全流程：堆上热点路径分配数 < 80 bytes/op（通过 JProfiler 人工检查非 mandatory；CI 里不 enforce，只要求 CPU time 在现代 Ryzen/Intel 上 10K 次 put 总耗时 < 200ms）。

---

## 6. Risks & Handling

| ID | 风险 | 影响 | 处理方式 |
|---|---|---|---|
| R1 | DsFreeRing 只存 long，但 DIR node 需要"子项是 DIR 还是 LEAF"的信息？用什么标记？ | 目录 listChildren 时不知道子项是什么类型，漏判 | **ring 存 nodeId 不分 LEAF/DIR**；listChildren 时对每个子 nodeId 读 `reg_meta/nodes/...` 64B meta block 的 byte0 的 bit7（isDirectory）。正好 1 次读（64B），性能 OK。如果未来热点 listChildren 需要更大吞吐再改（比如把 bit7 打进 nodeId 的 bit63，但这会让 nodeId 空间压缩到 63 位，目前不做）。 |
| R2 | mkdirs 并发下两个线程同时 create 同 dirNode → 重复写同一 keyPathId，DsHashMap.put 返回旧 value 但 DIR node 未初始化 | 丢子项，数据不一致 | mkdirs 内部写 **ReentrantLock** 锁住整段 ensureDir；putIfAbsent 语义（HashMap.get(keyId)==null 才 put 新 dirNodeId）。冲突时拿赢家的 dirNodeId 继续。 |
| R3 | DsFreeRing 的 count 两段遍历 invariant 如果被误写（比如直接改 head/tail），导致漏值 | listChildren 漏子项 | 不直接改 DsFreeRing，只用公开 offerUnique/poll/remove/snapshot；所有 DsFreeRing 操作在 DsRegistry 里走 `openOrCreateChildRing(dirId)`，不暴露底层引用；DsFreeRing 相关 invariant 已在 AGENTS.md L168-L181 强约束。 |
| R4 | DataType.code 冲突（用户原文 REG_REGISTRY_DIR 和 REG_FS_PATH code=0x18 重复）| 类加载时 CODE_MAP throw ISE | **明确修复用户原文冲突**：在 DataType enum 定义里，**REG_FS_PATH code=0x18；REG_REGISTRY_DIR 顺延 code=0x1A**（跳过 0x19 让 REG_TAG 保持 0x19）。Ditto REG_DATETIME/REG_TIMESTAMP 用户原文有缺失逗号或重复，在实现时 sanity check 一遍发现冲突就按"先出现在用户原文为准，后续相同 code 的项向后顺延 +1"的策略自动处理（写一段 private static int fixCode(Set<Integer> used, int desired) 处理，加载时跑一次）。 |
| R5 | Windows 下 rootDir 临时目录在 JVM 退出时被挂起的 DsMemory/DsFixedBucketStore file handle 锁住 → test tearDown FileUtils.deleteDirectory 抛 | 测试挂起或 PASS 但留垃圾目录 | 所有 @BeforeEach/@AfterEach 对 DsRegistry 显式 `registry.close()` → 释放 DsTypeString/DsTypeFixedBucketStore/DsFreeRing/DsHashMap 的底层资源；遵循 AGENTS DsMemory implements AutoCloseable 的约束。 |
| R6 | 动态用户自定义 DataType 注册未持久化 → 重启后 parseFromId fallback REG_BINARY，get 能取 bytes 但 Category 错了 | 语义错误概率低但业务分发错（STRING vs BINARY 错 → 拿 UTF-8 当 bytes）| 在 DsRegistry 内部固定一条 `/__system__/datatypes/custom`（REG_MULTI_SZ 或 REG_JSON）作为持久化区？→ **不做，最小集**；在 JavaDoc 里明确提示"若需要动态类型持久化，请在上层调用显式 serialize CUSTOM_MAP 的条目到注册表自定义 key"；我们只保证 parseFromId fallback 不崩，返回 REG_BINARY（BINARY Category 能正确拿到 bytes）。 |
| R7 | DsHashMap 的 `put(Long, Long)` 方法签名与 JDK Map 接口一致但会 throw IOException（因为 DsObject 写盘）→ 在 DsRegistry 的 private 写路径里 try/catch 包装成 UncheckedIOException 是否合规？ | 代码可读性 | **不包 UncheckedIOException**；DsRegistry 所有写 API（put/remove/mkdirs/sync）全部 `throws IOException`；调用方必须显式处理。符合 p2p-db 整体风格（DsType* 所有方法都 throws IOException）。 |
| R8 | 注册表超大 key（>1MB path）→ DsTypeString 的 data_value bucket 能存吗？ | 功能或性能 | DsTypeString 底层 DsData 对变长 value 能任意长（已验证 1MB DsString 之前 OK）；但文档化 keyPath 建议 ≤ 4KB；超长 key 抛 IllegalArgumentException，拒绝写入（防止恶意 1GB key path OOM DsHashMap key buffer）。 |
