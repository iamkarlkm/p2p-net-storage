---
title: p2p-db DsRegistry 通用注册表（Category+DataType 位运算枚举 + /prefix 目录树 DsFreeRing 子项 + DsHashMap 5 张映射 + value 双存储三分支 + 9 Facade API）
updated: "2026-09-12T15:00:02+08:00"
status: verified
version: "1.0.0"
verification:
  exit_code: 0
  tests_run: 51
  failures: 0
  errors: 0
  skipped: 0
---

# p2p-db DsRegistry 通用注册表子系统完整交付

## 1. 变更范围

**目标**：在严格 Performance First（零侵入不改底层存储一行代码）+ 组合优于继承 的不变式约束下，交付一个语义对齐 Windows 注册表 `REG_` 前缀规范、底层复用已有 5 大持久化原语（DsTypeString / DsTypeFixedBucketStore / DsFreeRing / DsHashMap / MultiTypeDataId 64-bit typedId）的通用注册表系统。

### 核心交付物（6 个源码新文件 + 1 个测试）

| # | 文件 | 作用 | 行数(约) |
|---|------|------|----------|
| 1 | [Category.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/enums/Category.java) | 顶层物理形态 13 大类枚举（BINARY/STRING/NUMERIC/COMPLEX/CONFIG/SCRIPT/DATABASE/DOCS/ZIP/SETUP/EXEC/P2P/CODE），带中文 desc | 53 |
| 2 | [DataType.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/enums/DataType.java) | REG_ 前缀具体业务类型 final class（非 enum，支持动态扩展），69+ 系统预定义常量 + registerUserDefined 动态注册（强制 code bit15=1）+ 位运算 generateId/parseFromId | 270 |
| 3 | [DsRegistryNodeSerializer.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistryNodeSerializer.java) | 64B 定长 Node Meta Block 序列化/反序列化（power=6，bucket=64B），NodeMeta immutable + allocateNodeId 返回 typedId | 128 |
| 4 | [RegistryEntry.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/RegistryEntry.java) | RegistryEntry immutable Builder + type-safe getter（asLong/asInt/asDouble/asDate/asString/asBytes），asBytes 走 DsRegistry.unpackSmallBytes 保证 LE 序一致 | 138 |
| 5 | [RegistryWalker.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/RegistryWalker.java) | `@FunctionalInterface RegistryEntryVisitor.onEntry(entry,depth)→boolean` + DumpAll/ToMap/FilterByDataType/Count 4 impl，depth 参数过滤 | 120 |
| 6 | [DsRegistry.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java) | 核心 Facade：9 个 public API + parseKey/packSmallBytes/unpackSmallBytes public 静态 + 3 个 ID 自动包装 helper（makeNodeTypedId/readNodeMeta/writeNodeMeta）+ getValueBytes 4B LE header 读 | 760 |
| 7 | [DsRegistryTest.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsRegistryTest.java) | 14 项专项防线（smoke/1MB JSON/testBinaryExternalVsInline 8B 边界/5 层目录/动态 DataType/matchesThis 守卫/walk/remove 递归/位运算向量/pack unpack/listChildrenEntries/非法 key/perf/root 条目） | 377 |

### 物理架构：6 个 TypeID 物理隔离（零修改继承上一轮不变式）

```
p2p-db 注册表独占 TypeID 200..204（每类型独立 type_{HEX}/ 子目录，与其他业务零冲突）：
  200 = TYPE_REGISTRY_KEY            → DsTypeString 存 /prefix key（UTF-8 上限 4096 bytes）
  201 = TYPE_REGISTRY_VALUE_STRING   → DsTypeString 存 REG_SZ/REG_JSON/REG_CONFIG_* 等 STRING 家族 value
  202 = TYPE_REGISTRY_NODE_META      → DsTypeFixedBucketStore power=6 (64B) 存 DIR/LEAF Node Meta
  203 = TYPE_REGISTRY_VALUE_BINARY   → DsTypeFixedBucketStore 存 ≥8B BINARY/ZIP/SETUP/EXEC value（4B LE header + payload）
  204 = TYPE_REGISTRY_VALUE_COMPLEX  → DsTypeFixedBucketStore 存 REG_OBJECT/REG_ARRAY/REG_MAP 等 COMPLEX value（同 BINARY 格式）
  额外：5 张 DsHashMap 位于 <rootCanonical>/registry/*.hash
    - keyId_to_dirId.hash           : keyId → dirNodeId (plain int, 不是 typedId)
    - keyId_to_leafOrDirId.hash     : keyId → leafNodeId (plain int)
    - leafId_to_valueOrId.hash      : leafNodeId → 内联 long 或 外部 typedId
    - dirId_to_ringId.hash          : dirNodeId → DsFreeRing 文件分配 ringSeq(plain int)
    - next_sequence.hash            : 固定 key=1L → 单调分配 plain sequence（dir/leaf/ring 共用）
```

**零修改保证**：底层 `DsTypeString` / `DsFixedBucketStore` / `DsFreeRing` / `DsHashMap` / `MultiTypeDataId` 源码 0 行修改，严格通过组合包装实现——符合 Performance First + 组合优于继承不变式。

---

## 2. 验收标准（6 项，全部 PASS）

### 2-1. Category + DataType 枚举（位运算 + 动态扩展）

| 项 | 验收点 | 结果 |
|----|--------|------|
| 1 | Category 13 大类（BINARY/STRING/NUMERIC/COMPLEX/CONFIG/SCRIPT/DATABASE/DOCS/ZIP/SETUP/EXEC/P2P/CODE）编译全部通过 | PASS |
| 2 | DataType 69+ REG_* 系统预定义常量：BINARY 7 / STRING 14 / NUMERIC 10 / COMPLEX 6 / CONFIG 7 / SCRIPT 5 / DATABASE 11 / DOCS 6 / CODE 4 = 合计 70 | PASS |
| 3 | DataType 位运算：`generateId(sequenceId) = code << 48 | seq & 0xFFFFFFFFFFFF`、`parseFromId(id)` 先查系统 CODE_MAP → CUSTOM_MAP，fallback REG_BINARY | PASS |
| 4 | 动态注册：`registerUserDefined(code, cat, name, desc)` 强制 `code & 0x8000!=0` (bit15=1)，`pickCode` 顺延防冲突 | PASS（testUserDefinedDataTypeAndCategory） |

### 2-2. Key 语义 & 目录树导航

| 项 | 验收点 | 结果 |
|----|--------|------|
| 1 | Key 严格 `/` 前缀，UTF-8 上限 4096 bytes，含 `..` 抛 IllegalArgumentException | PASS（testInvalidKeyPaths） |
| 2 | 中间空段 `//` 抛 IllegalArgumentException（开头 `//` 跳过，兼容路径绝对化） | PASS（testInvalidKeyPaths） |
| 3 | `mkdirs` 自动创建中间 DIR 节点，每个 DIR 独立 DsFreeRing 存子 nodeId（count 两段遍历） | PASS（5 层 testDirectoryTreeAndListChildren） |
| 4 | `listChildren(path)` 返回相对名集合；`listChildrenEntries(path)` 返回带 DataType/value 的 entry 列表；`walk(visitor, depth)` 通用 visitor + depth 过滤 | PASS（testListChildrenEntries/testWalkDepthAndFilter） |

### 2-3. Value 双存储三分支（Performance First 零歧义）

| 分支 | 条件 | 存储策略 | 边界校验 | 结果 |
|------|------|----------|----------|------|
| ① NUMERIC 内联 | Category=NUMERIC 或 REG_CHAR_DATETIME | 直接内联 leafIdToValueOrId HashMap，64-bit 原始位 | 0..2^63-1 往返一致 | PASS |
| ② 小 BINARY/COMPLEX 内联 | value.length ∈ [1..7] | `packSmallBytes` 高字节 bit56..63=len hint，payload bit0..55 LE 序 | len=1..7 / empty 三组 pack→unpack 字节完全相等，RegistryEntry.asBytes() 一致 | PASS（testPackUnpackSmallBytes） |
| ③ ≥8B 外部引用（或 STRING/CONFIG/SCRIPT/DOCS/CODE 所有长度）| BINARY/ZIP/SETUP/EXEC len ≥8；STRING 家族所有长度；COMPLEX len ≥8； | STRING→valueStrings DsTypeString 原始；BINARY/COMPLEX→valueBinaries/valueComplex 4-byte LITTLE_ENDIAN length header + payload；**永不 strip 尾部 0x00** | 8B exactly (tiny/signature8/signature_zero) / mid200 / big200k / midWithZeroTail 五组 get().asBytes() 完全 equals；midWithZeroTail[199]=0x00 不被 strip | PASS（testBinaryExternalVsInline 5/5 assertions） |

### 2-4. 核心 Bug 双重修复（最终 8-byte external 边界 case）

| Bug # | 根因 | 修复 | 验证 |
|-------|------|------|------|
| #1 ID 错位 (plain→typed) | allocateNextSequence 返回纯单调 int(0x0D=13) → writeNode/readNode 当作 typedId→DsTypeFixedBucketStore.toInternalId(13) 错位，meta 永远 null | 引入 3 helper：`makeNodeTypedId(seq)=encode(202,6,seq)` + `writeNodeMeta(store,idOrTyped,meta)` + `readNodeMeta(store,idOrTyped)`；内部 nodeId 继续使用 plain sequence，仅 meta 通路自动包装 | debug3/debug8 trace 100% 实锤；13/14 tests 先行 PASS |
| #2 packSmallBytes typedId 误拆解 | 8-byte BINARY → valueBinaries.put 返回 typedId = `0x00CB<<48 | ...`，高字节 bit56..63=0，触发 unpackSmallBytes 的「len not in [1..7] → whole 8B payload」分支，把 typedId 位拆解当作真实字节返回（首字节恒=0x00），并满足 `small.length>0 &&<=8` 直接 return，跳过 getValueBytes | `get()` 分支前置守卫：BINARY/ZIP/SETUP/EXEC → `decodeTypeId(storedVal)==203` 跳过 unpack；COMPLEX → decodeTypeId==204 跳过；强制走 getValueBytes 外部读 | 14/14 tests 全绿；L130 signature8 expected[0]=FF != actual=0 的 assertion 修复 |

### 2-5. 9 个 Public Facade API + sync + close

| API | 签名 | 语义 | 测试覆盖 |
|-----|------|------|----------|
| 1 | `long putLong(String key, DataType type, long value)` | 内联 NUMERIC | smokeInline |
| 2 | `long putString(String key, String value, DataType stringType)` | 写 valueStrings DsTypeString | hugeJsonSha256 |
| 3 | `long putBytes(String key, byte[] value, DataType binaryType)` | 1..7→pack inline；≥8→4B LE header valueBinaries | testBinaryExternalVsInline 5/5 |
| 4 | `long putBytesFromFile(String key, File file, DataType type)` | Files.readAllBytes 代理 putBytes | doc implicit |
| 5 | `RegistryEntry get(String key)` | DIR entry 或 LEAF entry（含 type/value/bytes/string/inline）；Category 分治 + decodeTypeId 守卫 | 全 14 tests 核心依赖 |
| 6 | `boolean exists(String key)` | get().isPresent() 薄封装 | remove 前后校验 |
| 7 | `boolean remove(String key, boolean recursive)` | 删 LEAF / 删 DIR（非空 recursive=false 抛 ISE；true 内联删子项避免重入死锁） | testRemoveLeafAndRecursiveDeleteDir |
| 8 | `List<String> listChildren(String dirPath)` | 读 DsFreeRing snapshot → 逐项读 meta → 过滤 root 伪节点 → 取 keyStrings 字符串 basename | testDirectoryTreeAndListChildren (5 层) |
| 9 | `List<RegistryEntry> listChildrenEntries(String dirPath)` | 同上 + 逐项 get() 组装完整 entry | testListChildrenEntries |
| + | `void walk(RegistryEntryVisitor v, int maxDepth)` | DFS（非递归 Stack），depth 参数过滤，visitor 返回 false 提前终止（短路） | testWalkDepthAndFilter |
| + | `void sync()` / `void close()` | 5 张 HashMap + 3 DsTypeString + 3 DsTypeFixedBucketStore 全量 sync/close，AutoCloseable 语义 | smokeInlineValueCloseReopen（close→new→get 幂等） |

### 2-6. 回归零破坏（51 tests 0F 0E）

| 测试类 | 数量 | 目的 |
|--------|------|------|
| DsRegistryTest | 14 | 注册表专项防线（0F0E） |
| DsFixedValueStoreTest | 6 | 底层定长存储回归 |
| DsFreeRingAsInodeSetTest | 8 | DsFreeRing count 两段遍历不变式回归 |
| DsHashMapEntryFreeRingTest | 1 | DsHashMap free ring 回收回归 |
| DsHashMapTest | 8 | DsHashMap put/get 性能与正确性回归 |
| DsTypeFixedBucketStoreTest | 8 | MultiTypeDataId 物理隔离 + matchesThis 守卫回归 |
| DsTypeStringTest | 6 | DsTypeString typedId 物理隔离回归 |
| **合计** | **51** | **BUILD SUCCESS，0 Failures，0 Errors** ✅ |

---

## 3. 验证命令与结果（exit 0 已实锤两次，含 test-compile 重编）

### Step 1. 强制重编（Windows 增量编译同步陷阱规避）
```bash
mvn -pl p2p-db -q test-compile
# Expected: exit 0，无编译错误
# Actual  : exit 0 ✅
```

### Step 2. DsRegistry 专项 14 tests （8-byte 边界 case 必过）
```bash
mvn -pl p2p-db '-Dtest=DsRegistryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# Expected: Tests run: 14, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
# Actual  : Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.389 s ✅
```

### Step 3. 联合回归 51 tests（底层 + 注册表，回归零破坏必过）
```bash
mvn -pl p2p-db '-Dtest=DsRegistryTest,DsTypeFixedBucketStoreTest,DsTypeStringTest,DsFreeRingAsInodeSetTest,DsHashMapTest,DsHashMapEntryFreeRingTest,DsFixedValueStoreTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# Expected: Tests run: 51, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
# Actual  :
#   DsFixedValueStoreTest:       6/6  (0.359s)
#   DsFreeRingAsInodeSetTest:    8/8  (0.168s)
#   DsHashMapEntryFreeRingTest:  1/1  (0.580s)
#   DsHashMapTest:               8/8  (6.089s)
#   DsRegistryTest:             14/14 (7.923s)
#   DsTypeFixedBucketStoreTest:  8/8  (0.230s)
#   DsTypeStringTest:            6/6  (0.862s)
#   TOTAL:                      51/51 BUILD SUCCESS ✅
```

---

## 4. 不变式清单（已同步追加到 [AGENTS.md](file:///i:/2026/code/p2p-net-storage/AGENTS.md#L175-L178)）

1. **Invariant: DsRegistry key 语义严格**：`/` 前缀 + 中间空段 `//` 抛 IAE + UTF-8 4096 bytes；ROOT_DIR_NODE_ID=1 plain sequence；3 个 ID helper 自动包装 typedId；每 DIR 独立 DsFreeRing 子项；5 张 DsHashMap 持久化映射。
2. **Invariant: DsRegistry value 三分支严格分治**：① NUMERIC 内联；② 1..7 BINARY/COMPLEX packSmallBytes 高字节 56 len hint LE 序；③ ≥8 BINARY 或 STRING/CONFIG/SCRIPT/DOCS/CODE 外部 typedId 引用（BINARY 4B LE header 永不 strip 尾 0x00）。
3. **Invariant: DsRegistry get() decodeTypeId 守卫优先**：BINARY/ZIP/SETUP/EXEC → decodeTypeId==203 跳过 unpack 避免 typedId 误拆 8B 假 payload；COMPLEX → decodeTypeId==204 同理。
4. **Invariant: DsRegistry DataType 动态扩展**：final class（非 enum）69+ REG_* 常量；registerUserDefined 强制 code bit15=1；generateId=code<<48|seq，parseFromId 系统→custom→REG_BINARY fallback。

---

## 5. Boundaries Touched（严格 Karpathy Simplicity First 审计）

### ✅ 新文件：无回归风险（仅新增，不修改既有）
- `p2p-db/src/main/java/com/q3lives/ds/enums/Category.java` — 新增 13 大类 enum
- `p2p-db/src/main/java/com/q3lives/ds/enums/DataType.java` — 新增 final class，69+ 常量 + 动态注册
- `p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistryNodeSerializer.java` — 新增 64B Node Meta 序列化
- `p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java` — 新增核心 Facade
- `p2p-db/src/main/java/com/q3lives/ds/registry/RegistryEntry.java` — 新增 immutable entry + type-safe getter
- `p2p-db/src/main/java/com/q3lives/ds/registry/RegistryWalker.java` — 新增 visitor 与 4 impl
- `p2p-db/src/test/java/ds/DsRegistryTest.java` — 新增 14 项专项测试

### ✅ Durable 文档更新（brain-managed，已通过 audit 前置检查）
- [AGENTS.md](file:///i:/2026/code/p2p-net-storage/AGENTS.md#L175-L178) — 追加 4 条 DsRegistry invariant
- [current-state.md](file:///i:/2026/code/p2p-net-storage/.brain/context/current-state.md#L664-L702) — 追加 DsRegistry session outcome（含验证命令/Boundaries/Follow-ups/Gotcha）
- 本文件 `.brain/resources/changes/dsregistry-category-datatype-registry-directorytree-dsfreering-dshashmap.md` — verification recipe

### ❌ 未修改（严格证明零侵入底层）
- `DsTypeString.java` / `DsFixedBucketStore.java` / `DsFreeRing.java` / `DsHashMap.java` / `MultiTypeDataId.java`：**0 行修改**
- 其他非注册表相关的类：**0 行修改**

---

## 6. Follow-ups（下一阶段 P2 方向，不阻塞本 P0）

| 编号 | 方向 | 说明 | 优先级 |
|------|------|------|--------|
| P2-1 | DsRegistry 索引与快速查询 | key 前缀扫描、DataType 过滤；复用 `DsTypeString masterIndex` 做候选集快速收敛，避免 walk 全表扫 | 中 |
| P2-2 | DsRegistry 事务与快照隔离 | 当前单 writeLock 粗粒度；可接入 DsDatabaseLocal MVCC 的 `readTimestamp` 快照模型做无锁一致性读 | 高（如需并发） |
| P2-3 | REG_DATABASE_* 与实体桥接 | REG_DATABASE_CATALOG / SCHEMA / TABLE / COLUMN 等 DataType 与 DsDatabaseLocal Entity 的双向映射 | 中（业务驱动） |
| P2-4 | REG_MULTI_SZ / REG_LIST 语义实现 | 当前 STRING 家族用 valueStrings 存单字符串；REG_MULTI_SZ 需约定 NUL 分隔或 JSON 数组 serialization | 中 |

---

## 7. Gotcha 清单（后续注册表开发 / 改测试必看）

### 7-1. Plain nodeId vs typedId 双体系陷阱 ⚠️
```
DsRegistry 内部分配 = allocateNextSequenceLocked() → 纯单调 int（1,2,3,...,13,...）
  → HashMap 存储的 dirId/leafId/ringSeq = 纯 int（不要 decodeTypeId！）
  → 仅 Node Meta 读写通路：readNodeMeta/writeNodeMeta 自动包装为 typedId（encode(202,6,seq)）
```
**违反后果**：对 HashMap value 做 `decodeTypeId(plain)` 返回 0（因为 plain<2^40 → typeId 部分=0），会误走 external 分支。

### 7-2. packSmallBytes 8-byte 冲突禁区（零歧义唯一方案）⚠️
```
NUMERIC value ≤2^56 → bit56..63 = 0x00
typedId(203,x,y)   → bit56..63 = 0x00 （203 = 0x00CB，高字节为 0x00）
BINARY len=8 unpack → len not in [1..7] → whole 8B payload 分支
```
**三者位分布完全重叠**，任何「len=8 也 inline」的方案都会导致 NUMERIC/typedId/8B-BINARY 三选一歧义。
**唯一零歧义方案**：8B BINARY 绝不 inline，强制 valueBinaries 外部存储 + get() 前置 `decodeTypeId==203` 守卫。

### 7-3. Windows 增量编译头号假失败 ⚠️
任何 mvn 看到行为与源码不一致（println 没出现 / 改完没生效 / assertion 指向旧行号）：
**第一步：`mvn -pl p2p-db -q test-compile` 强制重编**（IDE 增量编译 target 目录不同步 = Windows 常态）。

### 7-4. putBytes length==0 语义
空 `byte[]` → `storedVal = packSmallBytes(new byte[0]) = 0L` → 不走内联分支（small.length=0 被 skip）也不走外部分支（getValueBytes storedVal==0 提前 return null）→ `get().asBytes()` 返回 `new byte[0]`，符合预期空 blob 语义。

---

**最终状态**：`verified` / 51 tests 0F 0E / BUILD SUCCESS / Karpathy Simplicity First 审计通过（0 侵入底层原语）。
