# P5 DsIdLayout 3 参 + toGlobal/fromGlobal + migrateLegacyIds 验证 Recipe

## Goal
证明 DsRegistry P5 接入了 DsIdLayout 分布式全局 ID 编解码、并提供 P3→P4 一键懒迁移工具，零改 7 大底层，Public API 签名兼容，46 tests 0F0E。

## Preconditions
- Repo at HEAD，无本地修改；Windows PowerShell 环境；JDK 17+；maven 安装。
- 工作目录：`i:\2026\code\p2p-net-storage`

## Step 1：P51 专项 5 tests（DsIdLayout 边界 + roundtrip + 双实例隔离 + mismatch 抛错）
```powershell
cd i:\2026\code\p2p-net-storage
mvn -pl p2p-db '-Dtest=ds.DsRegistry_P51_DsIdLayoutTest' '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1 | Select-Object -Last 12
```
Expect: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`

## Step 2：P52 专项 5 tests（空库幂等 / 全 P4 幂等 / hybrid 塞 legacy 2→migrated=2 / P21 listSet=walkSet / P33 OrmBridge）
```powershell
mvn -pl p2p-db '-Dtest=ds.DsRegistry_P52_LegacyMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1 | Select-Object -Last 12
```
Expect: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`

## Step 3：全栈回归 46 tests（P0+P21+P22+P23+P24+P31+P33+P51+P52 联合）
```powershell
mvn -pl p2p-db '-Dtest=ds.DsRegistryTest,ds.DsRegistry_P21_PrefixFindTest,ds.DsRegistry_P22_ReadWriteLockConcurrencyTest,ds.DsRegistry_P23_OrmBridgeTest,ds.DsRegistry_P24_MultiSzListTest,ds.DsRegistry_P31_MasterIndexPrefixTest,ds.DsRegistry_P33_BidirectionalOrmTest,ds.DsRegistry_P51_DsIdLayoutTest,ds.DsRegistry_P52_LegacyMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1 | Select-Object -Last 12
```
Expect: `Tests run: 46, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS`（实测耗时 ~03:12 min）

## Step 4：零改 7 大底层校验（可选，静态确认无回归）
```powershell
git diff HEAD~1 -- p2p-db/src/main/java/com/q3lives/ds/cluster/DsIdLayout.java p2p-db/src/main/java/com/q3lives/ds/index/master/DsTieredMasterIndex.java p2p-db/src/main/java/com/q3lives/ds/core/{DsTypeString,DsTypeFixedBucketStore,DsFreeRing,DsHashMap,MultiTypeDataId}.java
```
Expect: 空输出（上述 7 个底层类 0 行改动）。

## Acceptance
Steps 1/2/3 全通过，46 tests 0F0E；Step 4 空 diff → P5 验收通过，可进入 P6。
