# DsHashSetI64 / DsMemorySet FastPut Benchmark Baseline

## 1. 背景

`DsHashSetI64` 与 `DsMemorySet` 为了优化连续写入场景，引入了两级快速定位缓存：

- `lastPutCache`：最近一次成功写入路径缓存，适合单热点/顺序写入。
- `quick cache`：小型多条目前缀缓存，适合多热点前缀交替写入。

这套机制在修复正确性问题后，增加了：

- 完整路径前缀匹配
- 链路可达性校验
- 惰性淘汰
- 命中/失效统计
- benchmark 对比输出
- 实例级 `quickCacheSize` 调参能力

## 2. 统计字段

`FastPutStats` 当前包含：

- `lastHitCount`：一级 `lastPutCache` 命中次数
- `quickHitCount`：二级 `quick cache` 命中次数
- `missCount`：两级缓存都未命中的次数
- `rejectedCount`：前缀匹配但链路校验失败的次数
- `invalidatedCount`：二级缓存条目被惰性淘汰次数
- `quickCacheSize`：当前有效缓存条目数
- `quickCacheCapacity`：当前缓存容量

## 3. Benchmark 场景

测试入口位于 `DsHashSetTest` 与 `DsMemorySetFastPutTest`：

- `testFastPutScenarioComparison()`
  - 比较顺序写与多热点前缀交替写
- `testFastPutQuickCacheBenchmark()`
  - 单独观察多热点前缀交替写场景
- `testFastPutQuickCacheSizeTuning()`
  - 比较 `quickCacheSize = 32/64/128/256`
- `DsMemorySetFastPutTest`
  - 验证 `DsMemorySet` 的 `contains(Integer)`、FastPut 前缀复用、负数到正数顺序遍历

## 4. 当前基线

### 4.0 本轮同步范围

本轮已经把以下能力从 `DsHashSetI64` 同步到 `DsMemorySet`：

- FastPut 两级缓存
- 完整路径前缀匹配
- 链路可达性校验
- 惰性淘汰
- `FastPutStats` 统计接口
- `debugDump(...)` 调试输出
- `contains/remove(Number)` 兼容
- `first/last/toArrayLong/iterator` 顺序修正

另外，`DsHashSetI64` 近期底层结构有两项与 FastPut 强相关的改进（不改变 FastPut 的语义，仅改变寻址与存储布局）：

- **slot 区指针变长存储（2/4/8 字节）**：slot 内仅保存 `entryId/child nodeId`，实际 key 存入 entryStore，减少小 key 段的节点体积。
- **三段(16/32/64)快速跳跃寻址**：负数与超 32-bit 的 key 走 64 位段；`[0..0xFFFF)` 走 16 位段；`[0xFFFF..0xFFFFFFFF)` 走 32 位段；对外接口保持透明。

统计口径说明：

- `getFastPutStats()` 在三段模式下会对各段统计做合并，便于 benchmark 直接观察整体命中/失效情况。

### 4.1 顺序写场景

样本结论：

- `lastHitCount=19920`
- `quickHitCount=0`
- `missCount=80`
- 命中率约 `99%`

解释：

- 一级 `lastPutCache` 对顺序/单热点写入非常有效
- 二级 `quick cache` 基本没有实际收益

### 4.2 多热点前缀交替写场景

样本结论：

- `lastHitCount=8`
- `quickHitCount=762`
- `missCount=15614`
- `rejectedCount=4`
- `invalidatedCount=4`
- 命中率约 `4%`

解释：

- 二级 `quick cache` 确实能打出真实命中
- 但收益有限，且伴随少量校验失败/惰性淘汰

### 4.3 DsMemorySet 顺序写场景

样本结论：

- `lastHitCount=99606`
- `quickHitCount=390`
- `missCount=4`
- 命中率约 `99%`

解释：

- `DsMemorySet` 在纯内存场景下，一级缓存同样非常有效
- 二级 `quick cache` 也能打出少量真实命中
- 当前样本里没有出现 `rejected/invalidated`

### 4.4 DsMemorySet 顺序语义

`DsMemorySet` 这轮还同步修复了顺序访问语义：

- `contains(Integer)` / `contains(Long)` 都能正确工作
- `first()` / `last()` / `toArrayLong()` / `iterator()` 统一为 `负整数 -> 正整数`
- `iterator()` 当前为基于自身 trie 的惰性遍历，不再依赖全量快照数组
- `toArray()` / `toArrayLong()` / `toArray(T[])` 仅兼容保留，已标记为过时
- 超大数据量读取时，优先使用 `range(start, count)`，避免全量快照带来的额外内存压力

### 4.5 DsMemorySet sync/load 约束

`DsMemorySet` 新增的 `sync/load` 能力遵循以下约束：

- `sync()` / `syncStore()` 直接按当前内存块布局整块写入 `dataFile`
- 落盘时不做重编码、不重排节点、不转换 slot 布局，保持内存格式原样输出
- `syncLoad()` 按相同块布局整块装载，恢复后再重建运行期缓存状态
- 实际同步字节数为 `headerSize + nextNodeId * dataUnitSize`
- 构造 `DsMemorySet(File file, ...)` 时，如果目标文件已存在且非空，会先执行 `syncLoad()`

头部兼容性约束：

- 文件头新增 `HDR_CPU_ENDIAN = 24`
- 当前仅写入两种取值：`1=BIG_ENDIAN`、`2=LITTLE_ENDIAN`
- 初始化新文件时使用 `ByteOrder.nativeOrder()` 记录当前 CPU 内存序
- 读取已有文件时会先校验 `HDR_CPU_ENDIAN`
- 如果文件记录的内存序与当前运行环境不同，直接拒绝加载并抛出 `CPU byte order mismatch`

这套约束的目的不是做跨平台格式转换，而是保证原样内存布局持久化的安全性，避免不同 CPU 内存序下把同一块二进制布局误解释后写坏数据。

### 4.6 DsMemorySet sync/load 使用示例

```java
File dataFile = new File("data/ds-memory-set.dat");

DsMemorySet writer = new DsMemorySet(dataFile, 64);
writer.add(-10L);
writer.add(3L);
writer.add(5L);
writer.sync(); // 按当前内存布局原样落盘

DsMemorySet reader = new DsMemorySet(dataFile, 64); // 文件存在且非空时会自动装载
List<Long> firstPage = reader.range(0, 3); // 大数据量读取优先使用 range
Long firstValue = reader.first();
Long lastValue = reader.last();
```

使用建议：

- 常规持久化路径直接调用 `sync()`，不需要额外做格式转换
- 重启后重新创建同一路径的 `DsMemorySet` 即可自动回装
- 读取大批量数据时优先使用 `range(start, count)`，避免 `toArrayLong()` 全量快照
- 如果需要跨不同 CPU 内存序迁移数据，应通过上层导出/导入逻辑处理，不要直接复用底层原样文件

## 5. 容量调优基线

`testFastPutQuickCacheSizeTuning()` 当前输出：

```csv
capacity,throughput,lastHit,quickHit,miss,rejected,invalidated,quickSize,quickCapacity
32,143837,8,90,16286,4,4,32,32
64,266173,8,186,16190,4,4,64,64
128,259699,8,378,15998,4,4,128,128
256,253141,8,762,15614,4,4,256,256
```

当前样本里的自动结论：

- `FastPutCapacityBest=capacity=64`

解释：

- `256` 的 `quickHitCount` 最高
- 但 `64` 的吞吐量最高
- 说明容量不应只看命中数，而应以吞吐量为主

## 6. 当前建议

如果真实 workload 更接近顺序/单热点写入：

- 保留 `lastPutCache`
- `quick cache` 可优先尝试减到 `64`
- `DsMemorySet` 场景可继续优先观察一级缓存收益，二级缓存作为附加优化保留

如果真实 workload 更接近多热点前缀交替写入：

- 保留 `quick cache`
- 优先从 `64` 开始试，再结合真实业务数据调整

如果后续 benchmark 显示：

- `quickHitCount` 长期接近 `0`
- 吞吐量也没有提升

则可以考虑进一步简化实现，仅保留 `lastPutCache`。

关于读取接口的使用建议：

- `iterator()`：适合顺序流式消费，当前为惰性遍历
- `range(start, count)`：适合分页读取，推荐作为大数据量读取的默认接口
- `forEachRange(start, count, consumer)`：适合轻量分页/流式消费，避免构造 `List<Long>`，在大数据量分页读取下比 `range()` 更省内存
- `toArray()` / `toArrayLong()`：仅兼容保留，不建议在超大数据量场景中使用

## 7. 后续维护建议

- 新增写入模式 benchmark 时，优先复用 `runFastPutBenchmark(...)`
- 调整 `quickCacheSize` 时，优先对比：
  - 吞吐量
  - `quickHitCount`
  - `rejectedCount`
  - `invalidatedCount`
- 若 `rejectedCount/invalidatedCount` 持续升高，需要重新评估多条目前缀缓存的收益/复杂度比
