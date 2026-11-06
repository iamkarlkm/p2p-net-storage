# P13 OQ4 列存 Bloom Filter（非 match 零 IO 跳过）+ 1M 行批量导入 实现计划

## Repository Research

### 1. 现状快照

**(1) EqIndex 现状（P2 已交付 eq index V0.11）**：
- 入口类：`EqIndexStore.java`（[findRowIds L121-L143](file:///I:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/columnar/index/EqIndexStore.java#L121-L143)）。当前 eq 查询路径：
  ```
  hash = DsDataUtil.hash64(normalizeBytes(valueBytes))
  → map = DsHashMap(mapFile(entity, colId))    // 每查一次 new DsHashMap 加载哈希表
  → head = map.get(hash)                       // 查 hash 头指针
  → linked list: while (cur != 0) bucketStore.get(NODE_BYTES=16) decode next
  → collect rowIds[]
  ```
  **问题**：99% 的 case「这个值根本不存在该列」→ 依然要做 DsHashMap 加载 + 哈希查找 + bucketStore.get(头节点 miss)，相当于 1 次 map lookup + 1 次 bucket IO。对于 `WHERE col = 999999999 (not exist) LIMIT 1` 过滤大量行的查询，80% CPU/IO 都耗在「查不存在的值」。

**(2) Range 索引现状**：
- 代码库**无 RangeIndex 类**（`grep RangeIndex/queryRange/betweenValues files_with_matches` → 全是 DsHashMap 内部 range view，不是列存 range 索引）。「eq/range 性能基线」定义：**eq 有 Bloom 前/后 TPS 对比（基线 = 已存在的 eq 路径）**，range 无索引跳过 OQ4 范围。

**(3) 批量导入现状**：
- 行存：`DsDatabaseLocal.putEntities(List<T>, assignId)` 已存在 [L1557](file:///I:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java#L1557-L1567)，对索引列做聚合后 1 次 read-modify-write 减少 IO 放大。
- 列存：**ColumnarStore 无批量 put API**（`grep putAll/batch/importRows in ColumnarStore.java → No matches`）。目前 1M 行导入只能 for 循环 `putValue(rowId, colId, bytes)` → 每行每列一次 `loadBuffer → absolute put → markDirty`，N 行×M 列共 N×M 次 buffer 锁 + dirty 标记 → 吞吐上限低。

**(4) 0 新增 Maven 依赖约束（P12/P11/P10 硬约束）**：
- `pom.xml` 不能动。Bloom Filter 不能用 Guava、不能用 orestes-bloomfilter → 纯 JDK 手写：`long[] bits + 2 个独立 64-bit hash（DsDataUtil.hash64 + secondHash = rotateLeft(hash, 21) ^ seed）`，double hashing 生成 k 个位置。BitSet 对齐 P0 经验：用 `long[]` 存（比 BitSet 好，连续缓存）。

**(5) 可复用组件**：
- `DsDataUtil.hash64(byte[])` 已在 EqIndexStore L73/L121 调用，可直接作 Bloom 主哈希。
- `java.util.BitSet` / `long[]` → 选 `long[]`（可直接对应 ByteBuffer `putLong` 批量持久化，比 BitSet.toByteArray 省一次转换）。
- P12 已交付的 HYBRID Registry/DataBackend 复用：Bloom 持久化路径 → `/orm/index/eq/<entity>/<columnSeg>/bloom`（对应 RegistryMetaPaths 加常量段）。

### 2. 硬约束继承（P10~P12 永久生效）
1. **NG-2 6 facade public 签名 0 改**：`ColumnarStore` / `EqIndexStore` 新增 method 只能 public final NEW 方法（不改变已有方法签名），不能 modify 已有 public method 的 arg/return/throws。
2. **P10 7 元 / P11 3 数据 / P12 sync 路径 0 字符改**：Bloom 新路径仅用新增 RegistryMetaPaths helper，不碰老路径。
3. **0 新增 Maven 依赖**：手写 Bloom，不引第三方。
4. **Bloom off 时 0 额外开销**：`EqIndexStore` 构造器加 bloom = null 字段，`if (bloom!=null && !bloom.mightContain(hash)) return new long[0];` 守卫，关 bloom 时 0 新分配。
5. **批量导入 backward 兼容**：原有单行 `putValue/putFieldGroup/putCompositeGroup` 行为 byte-for-byte 不变；新 `putBatch` 走内部优化路径，最终产出和 for 循环单行一样的物理存储（可双跑 byte-for-byte 断言）。

## Files and Modules

| 文件 | 变更类型 | 说明 |
|---|---|---|
| `p2p-db/src/main/java/com/q3lives/ds/util/BloomFilter64.java` | NEW 类 | 纯 JDK Bloom：`long[] bits + int k + long capacity + double hashing(2 hash → k 位)`，公共构造 `BloomFilter64(long expectedInserts, double fpp=0.01)`，`add(byte[])`、`mightContain(byte[])`、`toBytes()`、`fromBytes(byte[])` |
| `p2p-db/src/main/java/com/q3lives/ds/database/columnar/index/EqIndexStore.java` | Edit 2 处 | ① 构造器加 Bloom 字段（懒加载，从 `metaStore.bloomFile(entity,colId)` 反序列化或 NEW）；② `add()` 成功后 `bloom.add(valueBytes)`；③ `findRowIds()` 入口加 `if (bloom!=null && !bloom.mightContain(normalize(value))) return new long[0]` |
| `p2p-db/src/main/java/com/q3lives/ds/database/columnar/ColumnarStore.java` | Edit 3 处（NEW 3 public final 方法，不碰老） | ① `public final int putValueBatch(Map<Long, Map<Long, byte[]>> rowToColToValue)`（rowId→colId→bytes，批量 buffer 锁 + 每块 markDirty 1 次）；② `public final int putFieldGroupBatch(long groupId, Map<Long, byte[]> rowIdToGroupBytes)`；③ `public final int putCompositeGroupBatch(long groupId, Map<Long, byte[]> rowIdToGroupBytes)` |
| `p2p-db/src/main/java/com/q3lives/ds/registry/meta/RegistryMetaPaths.java` | Edit（NEW helper，不碰老） | 新增 `INDEX_ROOT="/orm/index"`、`INDEX_EQ_ROOT="/orm/index/eq"`、`indexBloomLeaf(entityClassName, colSeg)` 段 helper，零外泄（grep "/orm/index" 仅 RegistryMetaPaths 1 处） |
| `p2p-db/src/test/java/com/q3lives/ds/database/columnar/P13_BloomFilterTest.java` | NEW 类 | 单测：① FPP 实测（100k inserts 随机 not-exist 查询，实测 fp ≤ 0.02）；② roundtrip 序列化/反序列化相等；③ EqIndexStore findRowIds 未命中 Bloom → 直接 long[0] 不进入 DsHashMap（mock DsHashMap 统计调用数）；④ Bloom off 时 0 分配（对比内存占用） |
| `p2p-db/src/test/java/com/q3lives/ds/database/columnar/P13_BulkImportPerfTest.java` | NEW 类 | 性能基线：① 1M rows × 5 cols（固定长列）单行 for vs putValueBatch，assert 吞吐 putBatch/putSingle ≥ 1.0（要求 ≥ 原单行性能即 pass，通常 3~8×）；② byte-for-byte 相等（close 后读每个 cell 与单行模式结果完全相同）；③ 批量 MVCC fallback 与行存一致（不支持 MVCC 抛 UnsupportedOperationException 自动降级） |

## Implementation Steps（依赖顺序）

### Step 1：BloomFilter64 基础类 + 单测先行 NEW
- 手写 `BloomFilter64`：构造器 `(long n, double p)` → 用标准公式 `m = -n*ln(p)/(ln2)^2` bit 数；`k = (m/n)*ln2` 哈希数；用 long[] 存（64 对齐）。
- 哈希：`h1 = DsDataUtil.hash64(bytes)`（复用已有），`h2 = Long.rotateLeft(h1, 21) ^ 0x9E3779B97F4A7C15L`（独立二次哈希），每个位位置 = `(h1 + i*h2) & Long.MAX_VALUE % m`（i ∈ [0, k)）。
- 单测：100k inserts，查 200k 不存在的随机值，FPP 实测 ≤ 0.02（构造 0.01，留出测量误差）。

### Step 2：RegistryMetaPaths INDEX 段 + EqIndexStore 集成（零侵入 Bloom 守卫）
- 新增 RegistryMetaPaths 3 常量：INDEX_ROOT、INDEX_EQ_ROOT；helper `indexEqBloomPath(entity, colSeg)` → 对应 `/orm/index/eq/<entity>/<colSeg>/bloom`，grep "/orm/index" 全工程仅这里声明。
- EqIndexStore：
  - 字段 `Map<Pair<String,Long>, BloomFilter64> bloomByCol`（懒加载，按 entity+colId 粒度）
  - `createOrReplace()` 之后新建空 Bloom
  - `add()` 成功后 `bloom.add(valueBytes)`
  - `findRowIds()` **入口第一行** `if (bloom!=null && !bloom.mightContain(normalize(valueBytes))) return new long[0]` → 未命中 Bloom 直接空数组，不 new DsHashMap 不查 bucket

### Step 3：ColumnarStore 批量 put 3 NEW 方法（单行行为不变）
- 核心优化思路：
  - 单行 put：对 N×M 个 cell 每次都 `loadBuffer(position) → buf.put → markDirty(bufferIndex)`
  - 批量 put：**按 bufferIndex 分桶**（先把所有 cell 按 position 算 bufferIndex，group by bufferIndex，然后每个 buffer 只 load 1 次 → 把该 buffer 范围内的全部 cell put → markDirty 1 次），N×M cell 仅 O(N×M/BLOCK_SIZE) 次 buffer 锁。
- 批量方法**最终物理字节**与单行 for 循环写入后强制 flush 双跑，`Arrays.equals` 断言每个 cell 字节完全相同（byte-for-byte 兼容老数据）。
- MVCC 表：与 DsDatabaseLocal.putEntities L1565 一致 → throw UnsupportedOperationException 直接降级（用单行模式），保证版本链。

### Step 4：正确性单测 + 性能基线
- `P13_BloomFilterTest`：① EqIndexStore 构造 10k rowId，已知值查 → 全命中（Bloom 不影响 true positive）；② 99999 不存在值查 → 直接 long[0]，new DsHashMap count = 0（通过反射统计 DsHashMap 构造器调用次数）；③ Bloom 持久化 toBytes → fromBytes roundtrip 后 mightContain 不变。
- `P13_BulkImportPerfTest`：① 1M rows×5 cols (int/long fixed) 单行 for loop vs putValueBatch，mean 10 rounds 吞吐 ops/sec，assert ratio = batch/single ≥ 1.0（≥ 原单行性能即 PASS，通常 3~8×，实际比阈值松）；② byte-for-byte 双跑断言（单行写完 copy 目录 → 批量写完 → 每个 cell 读比对）。

## Dependencies and Considerations
- **Bloom false positive 语义**：Bloom 仅保证「不存在 100% 返回 false」，仍可能误判存在（进入老路径 findRowIds 正常查）→ 不影响正确性，只 90% case 加速。
- **Bloom 持久化懒加载**：初始化从 bloomFile读；运行期不实时 flush，进程退出时 / DsRegistry.close 时一次性 toBytes 落盘；万一崩溃丢 Bloom，重启后根据 EqIndexStore 重新 build（add 流程自动补写 Bloom，重建成本 = 索引全扫，对于 ≤10M 行可接受，后续 OQ5 可加增量 rebuild）。
- **批量导入无事务保证**：中途崩溃只部分写入，调用方需自行按 batch 大小拆分 + 幂等；不保证原子性，和单行 for 一样。
- **markDirty 频率优化**：批量模式下，每个 bufferIndex 仅 markDirty 一次（用 `Set<Integer> dirtyThisBatch`），避免每 cell 一次 ConcurrentHashMap put。

## Validation
1. 单元测试：
   ```bash
   mvn -pl p2p-db -Dtest=P13_BloomFilterTest,P13_BulkImportPerfTest test
   ```
   → Tests run: Bloom ≥ 4（FPP/roundtrip/zeroIO/unloaded）、BulkPerf ≥ 3（吞吐≥1×/byte-for-byte equal/MVCC fallback）。
2. 回归：P12 关联合规 Gatekeeper（c10）+ 已有 ColumnarStore*Test + EqIndex 相关单测：
   ```bash
   mvn -pl p2p-db -Dtest=P12_SyncGatekeeperTest,ColumnarStoreTest,ColumnarStoreCompressionTest,ColumnarStoreLayoutTest,P11_ColumnarDataTest test
   ```
   → 0F0E。
3. 手动：grep -rn "\"/orm/index\"" p2p-db/src/main/java → 仅 RegistryMetaPaths 1 处命中（零外泄约束）。

## Risks
- **Bloom 内存占用过大**：10M 行×100 列 → 每列 m=120M bit = 15MB，100 列 1.5GB（不可接受）→ 处理：Bloom 只对已 createOrReplace 有 eq index 的列创建；没配 EqIndex 的列 0 Bloom。且可配置 `p13.bloom.max.columns=32`，超限跳过。
- **批量导入 buffer 锁死锁风险**：按 bufferIndex 顺序处理（升序），避免 A→B 锁和 B→A 锁并发死锁 → 处理：rowId 排序后处理，bufferIndex 天然单调递增。
- **Bloom 重建漏值**：进程 crash 后 toBytes 没 flush，启动后 Bloom 空可能漏值 → 处理：EqIndexStore.drop/create 后强制重建（遍历 DsHashMap 所有 hash → 链表所有 rowId → 对应列值读回 → Bloom.add）。启动时发现 bloomFile 不存在或长度为 0 自动 rebuild。
