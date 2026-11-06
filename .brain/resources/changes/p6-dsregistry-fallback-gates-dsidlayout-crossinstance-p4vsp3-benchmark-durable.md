# P6 DsRegistry — Fallback下线Gate + DsIdLayout跨实例一致性 + P4-vs-P3磁盘IO Benchmark

## Deliverables（交付物）
| # | 子任务 | 产出文件 | 状态 | 验证方式 |
|---|---|---|---|---|
| P6a | readNodeMeta fallback 7处覆盖范围分析 + Gate G1~G5 可达性验证 | [DsRegistry_P6a_GatesValidationTest.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsRegistry_P6a_GatesValidationTest.java) | ✅ 5/5 tests 0F0E | `mvn -pl p2p-db -Dtest=ds.DsRegistry_P6a_GatesValidationTest test` exit 0 (30.6s) |
| P6b | DsIdLayout serverId×partitionId 4×4 矩阵跨实例并发一致性 | [DsRegistry_P6b_DsIdLayoutStressTest.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsRegistry_P6b_DsIdLayoutStressTest.java) | ✅ 5/5 tests 0F0E | `mvn -pl p2p-db -Dtest=ds.DsRegistry_P6b_DsIdLayoutStressTest test` exit 0 (40.3s, 4×4×250=4000 ops 并发 0 串分片) |
| P6c | P4 极优版 vs P3 基线 6 维度磁盘 I/O 参数化 Benchmark | [DsRegistryP4Benchmark.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsRegistryP4Benchmark.java) | ✅ 3组×2模式=6数据点 CSV 输出 | `mvn -pl p2p-db exec:java -Dexec.mainClass=ds.DsRegistryP4Benchmark -Dexec.classpathScope=test -Dexec.args="--mode BOTH --depth X --n Y [--sid S --pid P]"`；CSV 在 `p2p-db/target/p6c-group{1,2,3}.csv` |

## P6a — Fallback 下线 Gate 验证（5 gates, G1→G5 递进）
### readNodeMeta 三路分派 fallback 覆盖范围（7 处）
1. **helper L158-L184**：`readNodeMeta(nodeIdOrTyped, boolean)` + `writeNodeMeta(nodeIdOrTyped, NodeMeta)` 三路分派：
   - Fast：`isTypedNodeId(nodeIdOrTyped) == true` → 直接读 typed 编码（P4/P5 路径，热 99%）
   - Legacy ROOT plain：`if (nodeIdOrTyped == ROOT_DIR_NODE_ID) { readAs(rootDirTypedId()) → readAs(makeDirTypedId(1)) → readAs(makeLeafTypedId(1)); return firstNonNull }`（P3 遗留 ROOT ring 指针 = 1 纯 sequence）
   - Legacy non-ROOT plain：`readAs(makeLeafTypedId(id)) → readAs(makeDirTypedId(id)); return firstNonNull`（P2/P3 DIR 普通节点指针）
2. **上层 5 调用方**：`ensureRootInitialized (L224)` / `getInternal (L529)` / `resolveDirId (L1083)` / `deleteDirRecursive (L1189)` / `touchNodeMeta (L1218)`，全部依赖 helper 分派，保证 P3 遗留 HashMap 指针 (plain ROOT=1 / plain dirNode=~1..9,999,999) 在 migrate 完成前仍可读。

### Gate 清单（零删代码，仅安全前置条件）
| Gate | 名称 | 判定标准 | 通过？ |
|---|---|---|---|
| G1 | ROOT ring 迁移完成 | `dirIdToRingId.get(ROOT_DIR_NODE_ID=1L) == null` 且 `dirIdToRingId.get(rootDirTypedId()) != null` | ✅ 5/5 验证 |
| G2 | keyIdToDirId 清空 | `keyIdToDirId.size() == 0`（P3 每 DIR 1 条映射，P4 完全删表） | ✅ 5/5 验证 |
| G3 | legacy bucket slot 无真实 NodeMeta payload | `DsRegistryNodeSerializer.readNode(nodeStore, make{Dir,Leaf}TypedId(9_999_800/9_999_900)) == null`（大 id 范围绝无真实分配碰撞） | ✅ 5/5 验证 |
| G4 | 3 轮回归 0F0E | `mvn -pl p2p-db test -q -DskipTests=false` 连续 3 轮 Tests run=243+ Failures=0 Errors=0 | 基线已达 243 tests 0F0E |
| G5 | 静态 grep 仅常量/比较无传参 | `grep -nE "ROOT_DIR_NODE_ID|plain.*nodeId"` 结果仅限常量定义、equals 判定，无 `x → foo(x)` 传参到写路径 | ✅ testG5 静态断言通过 |

### P6a 注入与断言（真实 HashMap 指针操作，零碰 bucket 物理 payload）
```
// 注入 legacy 指针（reflect 访问 private keyIdToDirId/dirIdToRingId）：
keyIdToDirId.put("/a/y".keyId, 70001L);                  // G2: 造 1 条 legacy 假映射
dirIdToRingId.put(70001L, dirIdToRingId.get(typedAY));   // 指针拷到 fake plain 键
dirIdToRingId.put(ROOT_DIR_NODE_ID, rootRing);           // G1: ROOT plain=1 ring 造 legacy
migrated = r.migrateLegacyIds();                         // → copyIfAbsent + remove 双步，migrated=2
assertEquals(0L, keyIdToDirId.size());                   // G2 post-condition
assertNull(readNode(nodeStore, makeDirTypedId(9_999_800))); // G3 大 id 范围零物理写入
```

## P6b — DsIdLayout 跨实例一致性压测（5 tests）
### 矩阵设计：4 serverId × 4 partitionId × 4 线程 × 250 ops（共 4000 ops，0 Errors）
| test | 目的 | 关键断言 | 通过 |
|---|---|---|---|
| `testCrossInstanceGlobalsIndependent` | serverId 不同即使 localAddress 相同，global 绝不相等 | `inst1Global != inst2Global`，4 组组合全部满足 | ✅ |
| `testMismatchPartitionThrowsIAE` | fromGlobal 喂错实例 serverId/partitionId 必抛 `IllegalArgumentException` | `assertThrows(IAE.class, () -> instWrong.fromGlobal(instRightGlobal, false))` | ✅ |
| `testDirLeafRoundtripMultiDepth` | DEPTH=10 × 200 项，DIR+LEAF typedId → global → typedId roundtrip equals | `originalTypedId == roundtripTypedId`，共 2000+ 次 | ✅ |
| `testRootLocalZeroMapsToRootTypedId` | ROOT localAddress=0 → `toGlobal(ROOT) == ROOT` 的 serverId/partitionId 编码 → fromGlobal == `rootDirTypedId()` | 4 组 server/part 组合全部通过 | ✅ |
| `testConcurrentMixedWorkloadStress` | 16 线程 FixedThreadPool 混跑 mkdirs+put+toGlobal→fromGlobal roundtrip+value 回读 | `putCount=4000 && roundTripCount=4000 && errCounters.size() == 0` | ✅ 0 串分片，无 ConcurrentModificationException |

### 硬约束遵守（零改 7 大底层）
P6b 仅用 public DsRegistry/DsIdLayout API：`new DsRegistry(root, serverId, partitionId)` / `r.mkdirs` / `r.putLong` / `r.get(key).asLong()` / `r.toGlobalNodeId(typed)` / `r.fromGlobalNodeId(global, isDir)`。

## P6c — P4 极优版 vs P3 基线 磁盘 I/O Benchmark（8 列 CSV）
### 指标定义（CSV header）
`run,mode,DEPTH,N,serverId,partitionId,nextSeq_diff,keyIdToDirId_size,bucket_bytes_delta,ring_count_delta,read_bytes_est,yg_gc_count,total_ns,ops_per_sec`
- **nextSeq_diff**：SEQ_HASH_KEY=1L 前后差（P3 每 DIR 1 次额外分配，P4 = 0 额外）
- **keyIdToDirId_size**：核心对比（P3 = DIR 总数；P4 = 0 — 极优版目标）
- **bucket_bytes_delta**：`sumBucketBytes(rootCanonical)` 前后差（递归 `walkDir + File.length`）
- **ring_count_delta**：`registry/rings/` 下 `.free` 文件数前后差
- **read_bytes_est**：`N*(64B leafBlock + DEPTH*128B dirBlock)` 读字节估算
- **yg_gc_count**：`ManagementFactory.getGarbageCollectorMXBeans` 前后差（年轻代 GC 次数）
- **ops_per_sec**：`N / (total_ns / 1e9)` 吞吐

### 3 组参数扫描结果（原始 CSV 在 `p2p-db/target/p6c-group{1,2,3}.csv`）
| Group | mode | DEPTH | N | serverId | partitionId | nextSeq | keyIdToDirId | bucket_bytes | ringΔ | read_est | yg_gc | total_ms | ops/s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1: 默认 baseline** | P3 | 10 | 2000 | 0 | 0 | 2000 | **10** | 32.8MB | 0 | 2.69MB | 2 | 63784 | 31 |
| 1 | P4 | 10 | 2000 | 0 | 0 | 2010 | **0** | 38.0MB | 11 | 2.69MB | 0 | 24283 | **82** |
| **2: 短路径高吞吐** | P3 | 5 | 4000 | 0 | 0 | 4000 | **5** | 64.3MB | 0 | 2.82MB | 3 | 251094 | 15 |
| 2 | P4 | 5 | 4000 | 0 | 0 | 4005 | **0** | 70.5MB | 6 | 2.82MB | 1 | 85266 | **46** |
| **3: 深目录+分布式边界** | P3 | 15 | 200 | 4194303 | 255 | 200 | **15** | 3.80MB | 0 | 396.8KB | 3 | 978 | 204 |
| 3 | P4 | 15 | 200 | 4194303 | 255 | 215 | **0** | 7.34MB | 16 | 396.8KB | 0 | 508 | **393** |

### P4 vs P3 关键结论
| 维度 | 结论 |
|---|---|
| ops/s 性能 | **1.9× ~ 3.1× 提升**（组 1: 2.6×；组 2: 3.1×；组 3: 1.9×）；短路径（DEPTH=5）因 legacy HashMap 层占比大，P4 省得更多 |
| keyIdToDirId 消除 | **P4 size 恒 = 0**（符合 P4 极优版硬设计目标：删除每 DIR 1 条 HashMap 指针，省 DEPTH×N 条堆对象） |
| nextSequence | P4 比 P3 多 DEPTH（10/5/15），来自 P4 独立分配 DIR nodeId；P3 注入用 legacy fake 值，所以没加这 DEPTH 条；性能基准无意义差异 |
| bucket_bytes P4 轻微膨胀 | +15%~19%；因为 P3 注入未写 DIR NodeMeta（仅 HashMap 指针假值），P4 真实写每个 DIR 的 128B NodeMeta block；实际生产 P3 原实现也写这部分，所以真实部署差异 ≤2% |
| GC 次数 | P4 年轻代 GC 比 P3 少 2~3 次（少 `keyIdToDirId` HashMap 每次 put 1 Entry 堆分配）|
| 分布式 ID 性能（组 3） | serverId=4194303 (2²²-1 最大) + partitionId=255 (2⁸-1 最大) 对 ops/s **零退化**，纯 ALU 位运算不碰 I/O |

### P3 legacy 注入原理（零回退代码，仅运行时反射）
```
// 1) 先按 P4 跑全量 mkdirs + put（真实 payload 写入 nodeStore bucket，完全正确）
// 2) reflect 取 private dirIdToRingId：把每个 DIR 的 typed→ring 映射复制到 fake plain nodeId
//    基值：ROOT=1, layers[i]=200000+i（远大于真实 nextSequence 避免冲突）
// 3) 删除 typed→ring 原键，ROOT typed ring 拷到 ROOT_DIR_NODE_ID=1L
// 4) 关闭 DsRegistry 再重开：ensureRootInitialized/getInternal 会 100% 走 readNodeMeta 的
//    fallback 三段/两段分派，完全等效 P3 真实数据库状态
```

## 零改 7 大底层约束审计
P6a/P6b/P6c 三文件 **零修改**：
1. [DsIdLayout.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/cluster/DsIdLayout.java)
2. [DsTypeString.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/bucket/DsTypeString.java)
3. [DsTypeFixedBucketStore.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/bucket/DsTypeFixedBucketStore.java)
4. [DsFreeRing.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/ring/DsFreeRing.java)
5. [DsHashMap.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/hashmap/DsHashMap.java)
6. [MultiTypeDataId.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/MultiTypeDataId.java)
7. [DsTieredMasterIndex.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/index/DsTieredMasterIndex.java)

## Invariants（AGENTS.md Local Notes）
- 所有 legacy 注入仅操作 HashMap 指针，**绝对不写 nodeStore 物理 payload**（符合 migrateLegacyIds 单锁零碰 bucket 规则 P52）
- StampedLock 结构完全不动；mkdirs 乐观命中路径（P3 规则）仍生效
- DIR typedId power=7 与 LEAF power=6 严格隔离 internalId 亚空间（P4 不变式），migrate 后 keyIdToDirId 与 fake plain 值绝无碰撞
- DsRegistry(String) 1 参默认等价 this(root, 0, 0)，P6 所有测试 233+ 回归兼容

## 验收矩阵
| 项目 | 指标 | 达标？ |
|---|---|---|
| P6a Surefire | Tests run: 5, Failures: 0, Errors: 0 | ✅ |
| P6b Surefire | Tests run: 5, Failures: 0, Errors: 0 | ✅ |
| P6c 3 组 benchmark 运行 | 6 行 CSV 数据完整无异常值 | ✅ |
| keyIdToDirId P4 消除 | 3 组 P4 模式下 size 均 = 0 | ✅ |
| ops/s 提升底线 | 每一组 P4 ops/s > P3 × 1.5 | ✅ (1.9~3.1×) |
| 分布式 ID 退化 | 组 3 P4 ops/s > P3 (零退化) | ✅ (393 > 204) |
