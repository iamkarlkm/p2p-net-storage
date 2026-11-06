# P5 决策：DsIdLayout 分布式分片 3 参构造 + toGlobal/fromGlobal + migrateLegacyIds 懒迁移

## Context
P4 极优版已消除 `keyIdToDirId` HashMap、DIR 不分配独立 nextSequence、`makeDirTypedId(keyId)` 纯位运算直编，实现写放大从 3N/每层 DIR 降至 2N，233 tests 0F0E。P4 收尾后方向级 AskUserQuestion：用户优先级最高选 **「分布式分片 idLayout」**，同时放弃「keyStrings ZSTD cold tier」（因违反 Performance First 零改 6 大底层不变量：DsMemory.setColdDirectory 仅对 DsMemory 自身生效，DsData/DsFixedBucketStore/DsTypeString 无公共 cold API，改底层 233 tests 回归风险不可控），冷压 AskUserQuestion 单选转 **方案 A：懒迁移工具**。

目标：① 聚合层暴露 54-bit 全局 rowId（对齐 DsIdLayout.java L6-7 注释：存储层纯本地、聚合层嵌 serverId+partitionId）；② P3 legacy 格式（ROOT plain=1 ring 映射 + keyIdToDirId HashMap 条目）一键式懒迁移到 P4 typedId，零改底层。

## Options Considered
1. **方案 A（选定）**：P51 3 参构造 + pure-ALU toGlobal/fromGlobal；P52 migrateLegacyIds 只动两张 HashMap（copyIfAbsent+remove）零碰 bucket。代价：零改 7 大底层（DsIdLayout/DsTypeString/DsTypeFixedBucketStore/DsFreeRing/DsHashMap/MultiTypeDataId/DsTieredMasterIndex）0 行；收益：233 tests 零回归，46 tests 0F0E。
2. **方案 B**：硬破底层加 DsData/DsTypeString ZSTD cold tier；代价：改 3 个底层类 + 破坏 6 大 invariant，233 tests 必 regress；收益：keyStrings 冷压 10~30% 磁盘空间。用户否决，不选。
3. **方案 C**：结束 P4 阶段不推进；收益：零工作量；代价：丢分布式 ID 接入点 + legacy fallback 分支永久挂着，后续 P6 重构复杂度升。

## Decision
选方案 A，两子阶段同步交付：
- **P51 DsIdLayout 接入**：`DsRegistry(String rootDir)` → `this(rootDir,0,0)` 单机零破坏；3 参构造前置 `0≤serverId≤2^22-1,0≤partitionId≤255` IAE；`toGlobalNodeId`=decodeIndexId→DsIdLayout.compose，`fromGlobalNodeId`=server/part mismatch IAE→makeDirTypedIdFromLocal/makeLeafTypedId（ROOT local=0 经 helper 隐式返 rootDirTypedId）。
- **P52 migrateLegacyIds**：单 StampedLock writeLock 一次全程；先 ROOT plain=1 ring→rootDirTypedId copyIfAbsent+remove，再 keyIdToDirId snapshot ArrayList 逐条目 migrate，零碰 nodeStore/valueBinaries/valueComplex/keyStrings bucket，幂等（第二次调用 migrated=0）。

## Tradeoffs
- ✅ **零破坏**：P0-P4 233 tests 零回归，1 参构造 100% 兼容，所有 Public API 签名不变。
- ✅ **纯 ALU 零 I/O**：toGlobal/fromGlobal 不读不写任何 bucket/NodeMeta/DsHashMap，可高频调用零成本。
- ✅ **零物理搬**：migrateLegacyIds 只搬 HashMap 指针，利用 P4 readNodeMeta 三路分派 fallback 直接读 legacy bucket payload，100% 安全无 checksum 风险。
- ⚠️ **fallback 暂保留**：P5 交付后 P3 legacy→P4 读路径（readNodeMeta 三路分派）仍不删，给运维留 schedule migrateLegacyIds 的时间窗口，P6+ 可安全下线。
- ⚠️ **40-bit→24-bit 截断**：`local = MultiTypeDataId.decodeIndexId(typedId)` 理论上限 2^40，但 DsIdLayout localAddress 只塞 24-bit=16M；超 16M 项/实例必抛 IAE，符合分布式分片预设（单 server/part 不超过 16M DIR+LEAF）。
