# TypeID 物理分离：DsTypeFixedBucketStore / DsTypeString 独立子目录架构

## Verification for 按用户显式要求：DsTypeFixedBucketStore 和 DsTypeString 必须按 TypeID 物理独立子目录 rootDir/type_{HEX(typeId)}/ ，与其他 TypeID 的 data_*.dat / master_index / free-ring 互不可见，为未来不同类型跑不同搜索处理算法提供物理边界

- 37-test 全量回归：`brain session run --project . -- mvn -pl p2p-db '-Dtest=DsTypeFixedBucketStoreTest,DsTypeStringTest,DsStringTest,DsHashMapEntryFreeRingTest,DsHashMapTest,DsFixedValueStoreTest,DsFreeRingAsInodeSetTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` → Tests run: 37, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS
- 强制重编译：`brain session run --project . -- mvn -pl p2p-db -q test-compile` → exit 0（覆盖 Windows 旧 class 陷阱）
- Context Audit：`brain context audit --since 53a382e39827` → exit 0

## 物理目录隔离的精确定义

```
rootDir/
├── ds/oldstyle/data_128.dat                      ← plain DsFixedBucketStore(rootDir)
├── string_store/                                  ← plain DsString(rootDir)
│   ├── master_index/
│   └── data/data_value/ data_index/
├── type_A/                                        ← DsTypeFixedBucketStore(rootDir, 0xA)
│   └── ds/t/data_128.dat + *.free
├── type_BEEF/                                     ← DsTypeFixedBucketStore(rootDir, 0xBEEF)
│   └── ds/t/data_2048.dat + *.free
└── type_270F/                                     ← DsTypeString(rootDir, 0x270F)
    └── string_store/master_index, data_*/data_*.dat
```

- TypeID clamp：负数 → 0；>65535 → 65535
- 路径 HEX 后缀：`Integer.toHexString(clamp(typeId)).toUpperCase()`（最短表示，无 0 填充，无 sign-extend）
  - 17 → `type_11`；10 → `type_A`；0xBEEF → `type_BEEF`；0xFFFF → `type_FFFF`；0 → `type_0`
- plain DsFixedBucketStore / DsString **不**带 type_* 前缀；可与 typed store 安全同 rootDir 共存

## 语义边界（TypeID 守卫）

DsTypeString 与 DsTypeFixedBucketStore 均在 `get/refCount/remove/update/retain` 入口加 `matchesThis(id)`/`matchesDefault(id)`：
- 判定：`MultiTypeDataId.decodeTypeId(id) == this.typeId`（纯位运算 + int==，常量级）
- 不匹配时语义：
  - DsTypeString.get → null；refCount → 0；remove → no-op；retain → 抛 ISE；update 不匹配 id 走新分配
  - DsTypeFixedBucketStore.remove/update* → no-op

### 为什么必须有守卫？

DsFixedBucketStore 每个独立 bucket header 的 `nextId` 初值=0（[DsFixedBucketStore.java initHeader](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/bucket/DsFixedBucketStore.java#L995-L1015)）。
```
TypeID 0xA 第一次 put 同长度 bytes → typedId = 0x000A_p_0000000000
TypeID 0xB 第一次 put 同长度 bytes → typedId = 0x000B_p_0000000000
```
两者 `(power,indexId)` 位段完全相同 → 若直接 toInternal 读：
- sA bucket offset 0 存的是 "same-string"
- sB bucket offset 0 同样也存的是 "same-string"
- 因此 `sB.get(idA)` 原逻辑会读到值，与"物理隔离不可见"的用户预期**冲突**
加守卫后立即短路为 null / 0 / no-op → 语义彻底不可见，符合预期

## 物理隔离的四重证明（test 硬断言）

见 [DsTypeFixedBucketStoreTest](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsTypeFixedBucketStoreTest.java) 与 [DsTypeStringTest](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsTypeStringTest.java)：
1. **typedId 高位不等**：`decodeTypeId(idA)!=decodeTypeId(idB)`（不依赖 indexId 数值差异，避开 nextId=0 陷阱）
2. **跨读空**：`sB.get(idA)==null` (DsTypeString) / `sB.get(idA)` 返回全 0 bytes (DsFixedBucketStore)
3. **目录互斥**：`listRecursive(type_A)` 不包含 `type_B` 字符串片段，反之亦然
4. **跨 refCount=0**：`sB.refCount(idA)==0 && sA.refCount(idB)==0`

## Gotcha

- ❌ 不能用 `assertNotEquals(idxA, idxB)` 验证隔离：两个独立 bucket 首次分配 baseId 都返回 0 → 数字必然相等
- DsData master_index 只在本实例（rootDir+storeName）内去重；物理隔离后跨 TypeID 必然独立分配，不要假设"相同字符串跨类型去重"
- Windows 调试一致性：任何行为与源码不符，先跑 `mvn test-compile`；IDE 增量编译与 surefire 目标目录不同步非常常见
- DsTypeString.update(oldId, newStr) 后 oldId 可能读到新值：free-ring 立即复用物理 baseId → 这是空间立即回收的正确语义，不是 bug；调用方自行保证不再使用 oldId

## Zero-change 承诺（Performance First 延续）

- DsFixedBucketStore.java 0 行改动
- DsData.java 0 行改动
- DsString.java 0 行改动
- 组合优于继承：DsTypeFixedBucketStore / DsTypeString 仅做 id 转换 + rootDir 前置 + TypeID 守卫
