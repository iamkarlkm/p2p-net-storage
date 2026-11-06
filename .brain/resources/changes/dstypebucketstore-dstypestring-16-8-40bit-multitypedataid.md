# DsTypeFixedBucketStore / DsTypeString / MultiTypeDataId 三层 16/8/40 位 ID 实现

## 背景与设计约束

**用户两次需求串联演进（最终架构 V2，用户硬约束：必须物理分离）**：

### V1（已被用户否决，仅保留作为 one-line 上下文 trace）
- 高位加 16 位 TypeID → 只做位段编码，底层 bucket 文件与 plain DsFixedBucketStore/DsString **共享**同一 rootDir；37 tests 全绿但不满足未来搜索算法需求。

### V2（当前生效版本，VERBATIM 用户约束）
用户问："每一种数据类型+大小对应不同的数据文件,是这样实现的吗?未来会针对不同的文件实现不同的搜索处理算法,所以必须物理分开"。

因此必须在不破坏 V1 位布局的前提下，升级为**每个 TypeID 独占独立子目录**的物理隔离：
1. **零修改底层 DsFixedBucketStore / DsData / DsString**（Performance First + 零侵入组合延续）；
2. 64-bit 位布局保持 V1 不变：`TypeID(16)|power(8)|indexId(40)`；
3. **新增硬约束**：每个 TypeID 的物理文件（data_size.dat、*.free、master_index、bucket cache）必须 100% 独立互不干扰，以便未来不同类型可以跑独立的扫描/搜索/处理算法；
4. plain DsFixedBucketStore / DsString 仍使用原始 rootDir，typed 与 plain 物理不交叉。

## 最终代码布局（5 个文件 + 2 test）

| 文件 | 作用（V2 物理分离版本） |
|---|---|
| [MultiTypeDataId.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/util/MultiTypeDataId.java) | 纯静态位运算：TYPE_SHIFT=48, POWER_SHIFT=40；encode/decodeTypeId/decodePower/decodeIndexId（本轮 zero-change） |
| [DsTypeFixedBucketStore.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/bucket/DsTypeFixedBucketStore.java) | 组合 DsFixedBucketStore；根目录使用 `typeDirFor(rootDir, typeId)`；新增 matchesDefault(id) TypeID 守卫 |
| [DsTypeString.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/core/DsTypeString.java) | 组合 DsData；根目录使用 `DsTypeFixedBucketStore.typeDirFor(rootDir, typeId)`；新增 matchesThis(id) TypeID 守卫 |
| [DsTypeFixedBucketStoreTest.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsTypeFixedBucketStoreTest.java) 8 tests | 物理隔离硬断言：type_* 目录不同、跨读全 0 bytes、listFiles 互不可见对方桶 |
| [DsTypeStringTest.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsTypeStringTest.java) 6 tests | 物理隔离硬断言：跨读 null、listRecursive 互斥、跨 refCount=0、typedId 高位不等、与 plain DsString 不冲突 |

### 关键新增公开 API

```java
// DsTypeFixedBucketStore
public static String typeDirFor(String rootDir, int typeId)  // type_0 / type_2A / type_BEEF
public String getTypeDir()
public int getDefaultTypeId()

// DsTypeString
public int getTypeId()
public String getTypeDir()
```

### 物理目录命名规则
```
rootDir/
├── ds/oldstyle/data_128.dat                     ← plain DsFixedBucketStore(rootDir)
├── string_store/                                 ← plain DsString(rootDir)
│   ├── master_index/
│   └── data/data_value/
├── type_A/                                       ← DsTypeFixedBucketStore(rootDir, 0xA)
│   └── ds/t/data_128.dat, *.free
├── type_BEEF/                                    ← DsTypeFixedBucketStore(rootDir, 0xBEEF)
│   └── ds/t/data_2048.dat, *.free
└── type_270F/                                    ← DsTypeString(rootDir, 0x270F)
    └── string_store/master_index, data_*/data_*.dat
```

TypeID clamp 规则：负数→0，>65535→65535；HEX 大写、最少位数（0→"0"、17→"11"、10→"A"、65535→"FFFF"）。

## 位布局精确常量（零改动，与 V1 一致）

```
encode(typeId, power, indexId)
    = ((typeId & 0xFFFFL) << 48)
    | ((power  & 0x00FFL) << 40)
    | (indexId & 0xFFFFFFFFFFL)
```

| 字段 | bit 范围 | 位数 | 最大值 |
|---|---|---|---|
| TypeID | [63:48] | 16 | 65,535 |
| power | [47:40] | 8 | 255（实际 0..27）|
| indexId | [39:0] | 40 | ~1 万亿 |

## 关键工程决策

### 决策 1：目录前缀隔离 ≡ 物理隔离（零侵入底层 + zero I/O overhead）

**Why 不改动 DsFixedBucketStore 内部加 typeId 入 header / 文件名？**——会污染所有旧文件、旧 test、旧类 100+ 读路径。
**Why 目录前缀**：构造时把 `typeDir` 作为底层实例的新 rootDir。底层所有 "rootDir/space/type/data_*.dat" 内部逻辑一字不改——只是传入的根目录不同 → 物理隔离零成本、零风险、零性能损失。

### 决策 2：TypeID 语义守卫 matchesThis(id)

**为什么需要？**——DsFixedBucketStore 每个独立 bucket 的 `initHeader` 写 `nextId = 0L`（[DsFixedBucketStore.java L995-L1015](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/bucket/DsFixedBucketStore.java#L995-L1015)）。于是：
```
TypeID 0xA 第一次分配 baseId=0 → typedId = 0x000A_pp_0000000000
TypeID 0xB 第一次分配 baseId=0 → typedId = 0x000B_pp_0000000000
```
两者的 `(power, indexId)` 位段完全相同 → 如果 sB 直接 toInternal(idA) 再读底层，会**物理命中 B 自己 bucket offset 0 里刚好写着的值**——这是"物理隔离 + 巧合同 indexId"的必然现象，和"语义上 A 的 id 对 B 不可见"的用户预期冲突。

**加守卫后语义**：
- DsTypeString.get(id) / refCount(id)：typeId 不匹配 → return null / 0
- DsTypeString.remove/retain/update：typeId 不匹配 → no-op
- DsTypeFixedBucketStore.remove/update*：typeId 不匹配 → no-op

守卫是一条 `decodeTypeId(id)==this.typeId` 的 `and + ==` 比较，常量级性能。

### 决策 3：不做跨 TypeID 去重（用户物理分离约束的直接推论）

物理隔离后，`DsTypeString(typeId=A).add("same")` 与 `DsTypeString(typeId=B).add("same")` 写入两个**不同 typeDir 下独立的 master_index 与 data_value bucket**，因此即使 bytes 相同也必然分配两个不同的 typedId（各自独立 nextId 计数器）。这是期望行为——既然要给不同类型写独立搜索算法，就必须允许各自独立索引与独立磁盘布局。

## 验证命令（全绿）

```powershell
brain session run --project . -- mvn -pl p2p-db '-Dtest=DsTypeFixedBucketStoreTest,DsTypeStringTest,DsStringTest,DsHashMapEntryFreeRingTest,DsHashMapTest,DsFixedValueStoreTest,DsFreeRingAsInodeSetTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果汇总（V2 物理分离版本，2026-09-10 13:54 全回归通过）：
```
DsFixedValueStoreTest              Tests run: 6,  0F 0E
DsFreeRingAsInodeSetTest           Tests run: 8,  0F 0E
DsHashMapEntryFreeRingTest         Tests run: 1,  0F 0E
DsHashMapTest                      Tests run: 8,  0F 0E
DsTypeFixedBucketStoreTest         Tests run: 8,  0F 0E
DsTypeStringTest                   Tests run: 6,  0F 0E
TOTAL  37 tests / 0 failures / 0 errors  →  BUILD SUCCESS
```

### 踩坑清单（gotcha，后续修改必看）

1. **DsFixedBucketStore header.nextId = 0 陷阱**：
   ❌ 不能用 `assertNotEquals(idxA, idxB)` 做物理隔离断言——两个独立 bucket 首次分配都返回 0。
   ✅ 正确断言四重组合：typedId 不等(高位保证) + 跨读 null/全0 + 目录互斥 + 跨 refCount=0。
2. **DsData master_index 域范围**：去重只在同一个 DsData 实例（rootDir+storeName）内生效。物理隔离后跨 TypeID 不会去重。
3. **Windows Maven 编译陷阱**：Surefire / exec:java 可能吃 IDE 增量编译的旧 class → `mvn test-compile` 强制重编后再 run。
4. **DsTypeString update 后 oldId 非 null**：free-ring 立即复用同一个物理 baseId。调用方自行保证不再用 oldId（空间回收语义，不得通过加 tombstone 延缓）。

## Durable Invariant 追加（[AGENTS.md L160-L165](file:///i:/2026/code/p2p-net-storage/AGENTS.md#L160-L165)）

> MultiTypeDataId 64-bit 三段子 16/8/40，纯位运算；(c) **物理隔离**：每个 TypeID → rootDir/type_{HEX}/ 独立子目录，bucket 数据 / master_index / free-ring 100% 独立；(d) **TypeID 语义守卫**：读/删/改时 typedId 外 16-bit 与实例 typeId 不匹配→语义不存在（null/0/no-op）。

## Files changed count

```
MODIFY  p2p-db/src/main/java/com/q3lives/ds/bucket/DsTypeFixedBucketStore.java   # V2 物理隔离全量重写
MODIFY  p2p-db/src/main/java/com/q3lives/ds/core/DsTypeString.java               # V2 物理隔离全量重写
MODIFY  p2p-db/src/test/java/ds/DsTypeFixedBucketStoreTest.java                  # 重写物理隔离断言
MODIFY  p2p-db/src/test/java/ds/DsTypeStringTest.java                            # 重写物理隔离断言
EDIT    AGENTS.md (MultiTypeDataId invariant 追加 (c)(d))
EDIT    .brain/context/current-state.md (L596-L615 升级为 V2 session outcome block + gotcha list)
EDIT    .brain/resources/changes/dstypebucketstore-dstypestring-16-8-40bit-multitypedataid.md (本文档)
```
