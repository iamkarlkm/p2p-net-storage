# P17 OQ-1 Composite Range Index V3（复合多列 B+Tree 等效 Range Seek + ORDER BY / GROUP BY 下推 + 列存 String Bridge 补齐）— Product Requirements Document（Spec Mode — 中文）

## Overview

### 1.1 Summary（做什么 & 为什么）

**P17 要解决的 5 个真实代码 gap（代码证据）**：

- **Gap 1 复合索引现仅支持全 EQ 最左前缀 + FNV 哈希 不可逆 range**：
  GenericManager L1939 `compositeKeyValue()` 把 (a,b,c) 多列拼成 `\u0001` 分隔 String 后 FNV1a64 哈希 → 仅支持 `a=? AND b=? AND c=?` 全 EQ 组合 1:1 `idx.findByIndex(fnvHash)` 单点命中；对 `a=? AND b BETWEEN (?,?)`（前缀 1 列 EQ + 后续列 RANGE）、`ORDER BY a,b,c ASC LIMIT 100`（ORDER BY 列与复合索引完全对齐）等场景 **完全无法利用复合索引，退回 O(N) 全表扫 + Collections.sort O(N log N)**。
- **Gap 2 ORDER BY 多列 0 下推**：
  `listEntities()` L2159 `sort(out, wrapper)` 对任何多列排序（含 与复合索引 100% 对齐的 ORDER BY）都 做 内存 sort；N=200,000 行 时 `Collections.sort` 占 80%+ 耗时；单列 range P16 已 下推，但多列 复合 ORDER BY/GROUP BY 下推 空。
- **Gap 3 GROUP BY (a,b,c) + 聚合 0 下推**：
  无任何复合 GROUP BY 机制；业务侧 `findList` 全量拉回后 Java stream().collect(groupingBy)，N=500,000 行 时 O(N) 拉 表 + O(N) 聚合 必 超 120s 超时。
- **Gap 4 列存 String Range → GenericManager Bridge 未补（P16 T5 t02 @Ignore 遗留）**：
  列存 EqIndexStore 已实现 `findRowIdsBetween/Ge/Gt/Le/Lt` 5 个 package-private range API（P16 T3 3/3 PASS），但 `candidateRowIdsFromIndex()` L1993 对 `非 LONG 且 range op` 直接 `return null` 防御；String 列 range narrowing 0 生效 → matchesAll 全表扫必超时。
- **Gap 5 6 Facade public 签名基线（P16 NG-2 红线）必须 0 新增（DsDatabaseLocal=48 / ColumnarStore=24 / TableMetaStore=3 / ColumnRegistry=8 / RowIdSequenceStore=1 / EqIndexMetaStore=7 ±4）**。

**P17 交付 = 复合多列 Range + ORDER BY / GROUP BY 下推 全链路 + 列存 String bridge 补齐 + 0 Facade 新增**：

1. **复合索引重构（(a,b,c) → 可比较 Byte 序列 + 位运算编码 128bit VarInt Packing，取代 FNV Hash，O(log N) seek）**。
2. **前缀 seek 支持 EQ(a) + RANGE(b) + 前缀 ORDER BY(a,b,c) ASC/DESC 索引序直接返回，跳过内存 sort（TopN LIMIT 下推）**。
3. **GROUP BY (a,b) + COUNT/SUM/MAX/MIN 聚合下推：复用复合索引同前缀连续 bucket → 流式聚合 不 拉 全表**。
4. **P16 String bridge 补：GenericManager candidateRowIdsFromIndex 对 列存 且 String range op → 反射 调用 列存 EqIndexStore 5 个 range 方法（0 public 改 0 Facade 新增）**。
5. **c13 Gatekeeper 25 assertions 钉死：继承 P12 c10 / P14 c11 / P15 c11 / P16 c12 A6+B6+C5+D5+E3 五组架构零侵入**。

### 1.2 Purpose

**Target Users & Use Cases**：
- **TPC-C Order-Status / Stock-Level 核心业务场景**：`w.eq("warehouseId",100).between("createTime",t0,t1).orderByAsc("warehouseId","districtId","orderId").limit(20)` → 复合索引 (warehouseId,districtId,orderId) O(log N) seek + LIMIT TopN P99 ≤ 100ms。
- **OLAP 报表 GROUP BY**：`gm.groupBy(new String[]{"province","city"}, QueryWrapper.Aggregator.COUNT, "id")` → 复合索引 (province,city) 流式聚合 O(N_groups)，非 O(N_rows) 全拉。

**Goal Scenario 硬指标**：N=500,000 行 (a∈[1,500],b∈[1,1000],c 唯一) 场景，P17 较 P16 baseline（单列 range + 内存 sort + 无 GROUP BY 下推）**相对性能退化 ≥ 5×（即 P17 耗时 ≤ 0.20 × P16 baseline 耗时）**。

---

## 2. Goals & Non-Goals

### 2.1 Goals（必交付 4 大能力 + 4 NG 红线 0 越界）

| ID | 目标 | 代码级约束 |
|----|------|------------|
| G1 | **复合索引 Key 编码 128bit VarInt Packing（取代 FNV）** | 多列 (a,b,c) 按序拼 `列类型 VarInt 定长字节序列`（LONG=8B BIG_ENDIAN 无符号 + STRING=UTF-8 字节 零补 32B 对齐）→ 形成 可比较 字节数组 `byte[] compKey`；comparator 字典序按列边界 → **字典序等价 多列 逻辑 ORDER BY (a ASC,b ASC,c ASC)** |
| G2 | **前缀 Range + ORDER BY 下推（EQ(k 前缀) + RANGE(k+1) + ORDER BY 100% 对齐 → 跳 sort）** | candidateIdsForQuery 新增 复合索引 narrowing 分支（除 原 单列 EQ）；**返回的 List<Long> 直接 按 复合索引 序 ASC/DESC**；上层 sort() 若检测 wrapper.orders 与 复合索引列 100% 对齐（按列顺序、方向）→ 直接 skip sort；LIMIT 在索引层 TopN 截断 |
| G3 | **GROUP BY (列1,列2) + 聚合 下推（COUNT/SUM/MAX/MIN）** | 新增 package-private `aggregateByComposite(CompositeIndexInfo ci, List<String> groupByCols, Aggregator agg, String aggCol)` → 走 DsHashMap composite 有序键，同组前缀连续 → O(N_groups) 流式聚合；无 GROUP BY 列 不 拉 全表实体；0 Facade 新增；业务侧 gm 新增 0 public 方法（仅 package-private 反射可测） |
| G4 | **P16 遗留 String Range Bridge 补（String 列 range 走 列存 EqIndexStore 5 个方法）** | candidateRowIdsFromIndex 对 `valueKind==STRING 且 range op` → 用 classLoader 加载 `com.q3lives.ds.database.columnar.index.EqIndexStore` → MethodHandle.invokeExact 调 对应 findRowIds*（0 new public 0 Facade 签名 改 ） |
| G5 | **NG-2 6 Facade 基线 ±4 钉死；NG-3 0 Maven 依赖 0 新 P2PCommand 0 新通用集合；NG-4 路径零外泄；NG-5 0 新 Facade public 方法** | c13 Gatekeeper 25 assertions 必全过 |

### 2.2 Non-Goals（严格不做）

| ID | 不做 | 原因 |
|----|------|------|
| NG-1 | 持久化 B+Tree 页 / Slotted Page / 页分裂 / WAL 重做日志 | P17 = **内存复合索引 DsHashMap Navigable 复用 （DsHashMap 现已有 subMap/descendingMap P16 行存已用）+ 编码可比较键**；不落盘 BTree 页结构（下阶段 OQ-7 在线 DDL 重建时再考虑） |
| NG-2 | `DISTINCT / HAVING / JOIN / UNION` SQL 算子下推 | 不在 ORM MVP 范围；本阶段仅 GROUP BY + 4 基本聚合（COUNT/SUM/MAX/MIN） |
| NG-3 | 跨 OR 的复合索引 narrowing（DNF 多合取式） | 仅单 AND 合取式（P16 现 candidateIdsForQuery 复合索引 仅 单 AND 分支）；OR 多分支 保留 P16 单列 union 机制 |
| NG-4 | 通用集合类引入（fastutil / eclipse-collections / HPPC / chroniclemap / commons-collections4 5 类 0 存在） | NG-3 红线（P16 c12 D5 5 probes 实锤继承） |
| NG-5 | 修改 / 新增 6 Facade public 方法签名（DsDatabaseLocal / ColumnarStore / TableMetaStore / ColumnRegistry / RowIdSequenceStore / EqIndexMetaStore 6 类 public 方法 baseline ±4） | NG-2 红线（P16 实锤 48/24/3/8/1/7 ±4 必须 0 改） |
| NG-6 | 新增 P2PCommand enum（31201-31303 区间仅 Raft 保留 0 扩） | NG-3 红线（P15/P16 已 钉 死） |
| NG-7 | Online DDL 复合索引重建 / ALTER INDEX | 下一阶段 OQ-7 范围；本阶段仅 表首次 init 时 Annotation @DsCompositeIndex 声明的索引 自动 创建（沿用 P10 EntitySchemaUtil 解析） |

---

## 3. Background & Context（代码证据链 + 基线锚点）

| ID | 背景证据 | 锚点 |
|----|----------|------|
| C1 | **P16 单列 Range 已 交付（192 tests JV PASS c12 25/25 U1/U2 双 4/5）**：RegistryMetaPaths.columnRangeIndex* 区 15 个 helper + DsHashMap.subMap O(log N) 行存 seek + 列存 EqIndexStore LEX_UNSIGNED TreeMap + GM 5 range op 进 usable | P16 AGENTS.md L377-L395；project_memory.md L1-L31 |
| C2 | **现有复合索引 实现位置（P10 已写 0 Facade 改）**：GenericManager L179-L194 CompositeIndexInfo 解析 + L2184 单 AND 合取式 bestCi 匹配 + L1909 openCompositeIndexStore 直接复用 DsEqIndexStore(space=`indexes/xxx/schemaId`, indexName=`cidx_xxx`, valueKind=LONG) → 当前 compositeKeyValue=fnv1a64 hash 仅 EQ findByIndex | GenericManager L82-L89（CompositeIndexInfo）、L179-L194（解析）、L2184-L2203（narrowing）、L1909-L1915（open） |
| C3 | **列存 EqIndexStore 5 range 方法 已 存在（P16 T3 3/3 PASS 0 Facade 改）**：`com.q3lives.ds.database.columnar.index.EqIndexStore` 已 新增 package-private findRowIdsBetween(lo,hi,asc,limit)/Ge/Gt/Le/Lt + LEX_UNSIGNED Comparator + rangeMaps TreeMap<ByteBuffer,List<Long>> | EqIndexStore (Columnar) L25-L234；P16_RangeColumnarTest 3/3 PASS |
| C4 | **6 Facade 基线（c13 Gatekeeper B6 锚点 2026-09-27 absolute）**：DsDatabaseLocal=48 / ColumnarStore=24 / TableMetaStore=3 / ColumnRegistry=8 / RowIdSequenceStore=1 / EqIndexMetaStore=7；±4 容差 | P16_RangeFacadeHookRealTest + P15_RaftFacadeHookRealTest 实测 2026-09-27 |
| C5 | **NG-3 通用集合 5 probes 锚点（2026-09-27 absolute）**：fastutil / eclipse-collections / HPPC / chroniclemap / commons-collections4 五探针 grep 命中数=0（D5 5 断言） | P16_RangeGatekeeperTest D5 2026-09-27 实锤 |
| C6 | **DsHashMap 已 实现 Navigable 接口 subMap/descendingMap/tailMap/headMap（P16 T2 已 复用）**：无需 重写 BTree 直接 复用 有序 key seek O(log N) | DsHashMap.java（P16 行存 findByRange 实锤 复用） |

---

## 4. Functional Requirements（FR，20 条）

### FR-1 RegistryMetaPaths 复合 range 区（零外泄仅此处声明）

- **FR-1.1**：新增 `COMPOSITE_RANGE_ROOT="/orm/index/composite/range"` 常量；与 `ROW_RANGE_ROOT` / `COLUMN_RANGE_ROOT` 完全 平级 同 属 `/orm/index` 区。
- **FR-1.2**：新增 9 个 compositeRangeIndex* helper（compositeRangeIndexRoot/compositeRangeIndexDir/compositeRangeIndexSegment/compositeRangeIndexKind/compositeRangeIndexPayload/compositeRangeIndexBucket/compositeRangeIndexBloom/compositeRangeIndexStat/compositeRangeIndexMeta），命名段 复用 P10 `indexNameSegment` 16 hex 补 零 + `compositeIndexName` safe 文件名 段。
- **FR-1.3**：非法参数抛 IllegalArgumentException 10 条（tableId<0 / indexId<0 / compositeIndexName empty / bucketId<0 / payloadSize<0 / segment<0 / kind null / bloomFilterId<0 / statType null / metaKey null）。

### FR-2 复合索引 Key 编码 128bit VarInt Packing（替换 FNV，支持 字典序 compare）

- **FR-2.1**：新增 GenericManager `package-private encodeCompositeKey(CompositeIndexInfo ci, Object[] colValuesPerCol)` → 返回 `byte[] compKey`；每列编码规则：
  - LONG 列 = 8 字节 BIG_ENDIAN **无符号 直接位运算 翻转**：实际 Java 值 v 被转 `v - Long.MIN_VALUE` 后 writeLong 大端 → 字节字典序 = 数值升序。
  - STRING 列 = UTF-8 字节 后 `0x00` 填 至 32B（短于 32B 补 0x00，长于 32B 截 前 32B，0x00 为 sentinel 保证 短串 字典序 先于 长串 前缀）。
  - 总 compKey 字节 = Σ 每列 定长段 → 对齐 8B 边界。
- **FR-2.2**：新增 `package-private decodeCompositeKeyFirstKCols(byte[] compKey,int k, CompositeIndexInfo ci)` → 取 前 k 列 还原 Object 值；用于 前缀 ORDER BY skip sort 检查 / GROUP BY 流式 分桶。
- **FR-2.3**：新增 `private static final Comparator<byte[]> COMPOSITE_KEY_UNSIGNED` → 逐字节 `(a&0xFF) - (b&0xFF)` 无符号 字典序 compare（P16 LEX_UNSIGNED 模式 复用）。
- **FR-2.4**：`compositeKeyValue(ci, criteria)` 旧 fnv 分支 **保留 兼容 旧 已 落 盘 索引**（BackwardCompat 双桶：当 compKey 模式 未 开启 时 走 fnv）；新 写入 必走 compKey；T2 测试 验证 双桶 0 冲突。

### FR-3 复合索引前缀 Range Seek + ORDER BY/LIMIT 下推（跳过 sort）

- **FR-3.1**：`openCompositeIndexStore` valueKind 从 `LONG` 改成 `BYTES`（新增 DsEqIndexStore IndexedValueKind.BYTES 枚举项 → 内部 indexMap key 直接用 byte[] 哈希 / compare；注意：0 Facade public 改，仅 package-private 内部 enum 扩展 1 项）。
- **FR-3.2**：`selectBestCompositeIndex` 返回 后，新增 `longestEqPrefix(ci, criteria)` 检测：若 prefix=k ≥1 且 第 k+1 列 有 range 条件（GT/GE/LT/LE/BETWEEN）→ **前缀 EQ + 单 range 组合 支持**，直接 走 复合 seek；否则 仅 全 EQ 走 旧 compKey 单点。
- **FR-3.3**：candidateIdsForQuery 复合分支 返回 `List<Long>` **按 复合索引序 ASC/DESC**；DESC 直接 走 DsHashMap.subMap(...).descendingMap() 原语。
- **FR-3.4**：LIMIT 下推在 seek 层：每 bucket take=min(rowIds, remainingLimit)；total 达到 limit 立即 break（P16 单列 findByRange TopN 算法 复用），避免 多列 O(N) 取 全 桶。
- **FR-3.5**：`listEntities.sort()` 新增 skip-sort 检查：若 `wrapper.orders` 列 顺序/方向 与 bestCi 列 100% 按序 对齐 → 直接 `return out` 跳过 sort；通过 反射 compare（0 Facade public 改）。

### FR-4 GROUP BY (a,b,c) + 聚合 下推（4 基本聚合 COUNT/SUM/MAX/MIN）

- **FR-4.1**：新增 GenericManager `package-private aggregateByComposite(CompositeIndexInfo ci, List<String> groupByCols, AggType aggType, String aggCol, QueryWrapper<T> filterWrapper)` → 返回 `Map<List<Object>, Object>`（key=分组列 多值；value=聚合 结果）。
- **FR-4.2**：AggType enum（package-private）：`COUNT / SUM / MAX / MIN`；SUM 仅 LONG 列，MAX/MIN 适用于 LONG/STRING（走 COMPOSITE_KEY_UNSIGNED 比较），COUNT 忽略 aggCol。
- **FR-4.3**：流式聚合 算法：DsHashMap 有序 key 迭代，每 遇到 分组前缀 变化（decodeCompositeKeyFirstKCols 比 上一条 不同）→ 上 一 组 入 map，当前 组 重置；0 全表 实体 拉 回。
- **FR-4.4**：filterWrapper 非 空：先 走 candidateIdsForQuery narrowing 取 行 id 白名单，再 按 id 过滤 聚合 bucket（0 matchesAll 全表 扫）。

### FR-5 列存 String Range Bridge（P16 遗留 补）

- **FR-5.1**：`candidateRowIdsFromIndex` 对 `info.valueKind==STRING 且 range op` 分支，**不再 直接 return null**；尝试 classLoader 加载 `com.q3lives.ds.database.columnar.index.EqIndexStore`（用 Class.forName 不 new 依赖 import，避免行存 module 编译期 依赖 列存）。
- **FR-5.2**：MethodHandle 调 对应 findRowIds*(loBytes, hiBytes, asc, limit)；lo/hi 参数 用 `toIndexStringValue(val).getBytes(UTF_8)`；0 Facade public 改；0 编译期 import 列存 类。
- **FR-5.3**：列存 EqIndexStore 若 未 init 或 类 未 找到 → fallback return null（matchesAll 兜底，不 抛 异常）。
- **FR-5.4**：取消 P16_RangeGenericManagerTest t02 @Ignore；断言 100% 正确（P16 因 bridge 空 跳过 的 场景 必跑）。

### FR-6 零侵入硬约束（NG 家族 继承 2026-09-27 absolute）

- **FR-6.1 NG-2**：6 Facade 类（DsDatabaseLocal/ColumnarStore/TableMetaStore/ColumnRegistry/RowIdSequenceStore/EqIndexMetaStore）public 方法签名 **byte-for-byte 0 新增**；内部仅 package-private/private 扩。
- **FR-6.2 NG-3**：0 Maven 依赖 新增；fastutil/eclipse-collections/HPPC/chroniclemap/commons-collections4 五探针 grep 命中 数=0；P2PCommand enum 0 新增。
- **FR-6.3 NG-4**：COMPOSITE_RANGE_ROOT/COLUMN_RANGE_ROOT/ROW_RANGE_ROOT 三 组 根路径 字面量 仅 RegistryMetaPaths.java 内 声明（grep 零外泄仅此处）。
- **FR-6.4 NG-5**：Raft RPC 命令 号 区间 31201-31303 0 扩；P16 Range 区 常量 0 改。

---

## 5. Non-Functional Requirements（NFR 10 条）

| ID | 维度 | 指标（绝对阈值） | 验收方式 |
|----|------|------------------|----------|
| NFR-1 **U1 复合 ORDER BY+LIMIT P99** | 性能 | N=500,000 行 (a=1~500, b=1~1000, c 唯一) 场景；`w.eq("a",100).between("b",200,500).orderByAsc("a","b","c").limit(100)` 100 rounds P99 ≤ 80ms HARD；≤ 120ms SOFT；score=0-5（HARD=5 分 SOFT=4 分 threshold ≥4） | P17 U1 性能 专项 rubric |
| NFR-2 **U2 GROUP BY 流式 聚合 mean** | 性能 | 同一数据集 N=500,000；`aggregateByComposite(groupBy=["a","b"], aggType=COUNT, aggCol="id")` 20 rounds mean ≤ 2000ms HARD；≤ 8000ms SOFT；score=0-5（HARD=5；SOFT=4；threshold≥4）；对比 P16 baseline（findList 全量 + stream groupBy）≥ 5× 提升（P17 ≤ 0.20 × P16 耗时） | P17 U2 性能 专项 rubric |
| NFR-3 **U3 列存 String Bridge BETWEEN mean** | 性能 | N=100,000 String 行；`w.between("name","k01000","k05000").limit(200)` 30 rounds mean ≤ 400ms HARD；≤ 1200ms SOFT；score=0-5 threshold≥4 | P17 U3 性能 专项 rubric |
| NFR-4 **JV 合跑 ≥160 tests 0F0E（含 P17 全部）** | 稳定性 | P10~P17 合跑（P15 CompactCrash 单跑 防 PEERS 静态污染）≥ 160 tests 0F0E；P17 新增 ≥ 18 tests | T6 JV 合跑 + 计数 |
| NFR-5 **c13 Gatekeeper 25 assertions 25/25** | 架构零侵入 | A6 6 Facade public 计数 ±4；B6 Facade 内部 扩 含 0 public 新增；C5 三组 根路径 常量 仅 RegistryMetaPaths 命中；D5 5 通用集合 探针 0；E3 非 facade 类 public 方法 0 泄漏 → 合计 25 assertions PASS | P17_CompositeGatekeeperTest 5 组 × 每条 独立 断言 |
| NFR-6 **BackwardCompat 双桶** | 可回退 | P16 及 以前 复合索引（fnv1a64 hash 模式）仍 能 正确 findByIndex；`backwardCompatBucket` flag 对 旧 hash key 与 新 compKey 0 冲突（10,000 随机 组合 实锤） | P17_CompositeBackwardCompatTest t02 |
| NFR-7 **复合 Key 编码正确性** | 正确性 | 按字典序 排序 1,000,000 个 LONG+STRING 随机 compKey → compare 结果 与 先按 a 升序 再 b 升序 的 逻辑 排序 100% 对齐（0 错位） | P17_CompositeKeyCodecTest t01 百万级 |
| NFR-8 **ORDER BY skip-sort 有效性** | 性能 对齐 | 检测 ORDER BY 列 与 复合索引 对齐 时 实际 `Collections.sort` 被 调用 次数 = 0；非 对齐 时 sort 调用 正常（计数 探针 反射 插桩 验证） | P17_CompositeOrderBySkipTest t01/t02 |
| NFR-9 **零 Facade / 零 P2PCommand** | 硬约束 | grep `public.*(` 6 Facade 计数 在 baseline±4；P2PCommand 枚举 count 不变 31201-31303 唯 6 条 | Gatekeeper A6/B6 + grep |
| NFR-10 **相对性能提升 ≥5×（GROUP BY 场景）** | 相对 退化 | P17 U2 耗时 / P16 baseline（findList 全量 + stream groupBy）≤ 0.20 → 5× 以上 提升；0 退化（≤ 1.00 × 也算 PASS 但 score 扣） | T7 U2 双 基线 对 比 断言 |

---

## 6. Acceptance Criteria（AC；全部 必达 ；仅 rule 或 rubric 二选一 类型）

### 6.1 Rule 类 AC（14 条；客观 二值）

| ID | Type | Acceptance Criterion（可 观测 PASS 条件） | Evidence 来源 |
|----|------|-------------------------------------------|---------------|
| AC-1 | rule | RegistryMetaPaths 新增 COMPOSITE_RANGE_ROOT + 9 个 compositeRangeIndex* helper 反射 可 调 用 10 项 全 non-null；路径 首段 为 `/orm/index`（零 越界） | P17_CompositeKeySpaceTest t01 |
| AC-2 | rule | compositeKey codec 1,000,000 随机 LONG+STRING 组合 compare 字典序 = 多列 逻辑 ORDER BY 100% 对齐（0 mismatch） | P17_CompositeKeyCodecTest t01 |
| AC-3 | rule | 复合索引 EQ(a,1) + BETWEEN(b,10,50) 返回 rowId 与 全表 matchesAll 过滤 结果 100% 集 相等（count + id set 双 断言） | P17_CompositeRangeSeekTest t01 |
| AC-4 | rule | ORDER BY(a,b,c) ASC 对齐 复合索引 时 Collections.sort 调用 次数=0（插桩 反射 计数 实锤）；LIMIT 100 返回 恰好 100 条（TopN 下推生效） | P17_CompositeOrderBySkipTest t01 |
| AC-5 | rule | GROUP BY(a,b) COUNT 结果 = 全表 stream().collect(groupingBy(a,b),counting()) 100% equals（键 值 双 断言 500,000 行） | P17_CompositeGroupByTest t01 |
| AC-6 | rule | 列存 String bridge 生效：P16_RangeGenericManagerTest t02 取消 @Ignore 后 tests run 3（原 1 skipped）→ 3 run 0F0E | P16_RangeGenericManagerTest（取消 @Ignore） |
| AC-7 | rule | BackwardCompat：旧 FNV compositeKeyValue 模式 写 10,000 条 + 新 compKey 模式 写 10,000 条 混合 findByIndex 0 冲突 0 错 | P17_CompositeBackwardCompatTest t02 |
| AC-8 | rule | 非法参数 10 条 IAE 全 实锤（tableId<0 / indexId<0 / ... / metaKey null） | P17_CompositeKeySpaceTest t02 |
| AC-9 | rule | 路径零外泄：grep p2p-db/src/main/java（排除 测试）`COMPOSITE_RANGE_ROOT\|columnRangeIndex\|rowRangeIndex\|compositeRangeIndex` 仅 1 文件 命中 = RegistryMetaPaths.java | P17_CompositeKeySpaceTest t03（ac_r2 零外泄） |
| AC-10 | rule | 6 Facade public 方法计数（DsDatabaseLocal=48 / ColumnarStore=24 / TableMetaStore=3 / ColumnRegistry=8 / RowIdSequenceStore=1 / EqIndexMetaStore=7）±4 每类 内 | Gatekeeper A6 6 条 |
| AC-11 | rule | 通用集合类 5 探针（fastutil/eclipse-collections/HPPC/chroniclemap/commons-collections4）grep 命中 数=0（每探针 1 断言） | Gatekeeper D5 5 条 |
| AC-12 | rule | P2PCommand enum 数量 不变（31201-31303 唯 6 条：RAFT_REQUEST_VOTE 31201 → RAFT_INSTALL_SNAPSHOT_ACK 31303）；0 新增 cmd id | Gatekeeper C5 第 6 子断言 |
| AC-13 | rule | JV 合跑（P10 71 + P14 39 + P15 54 + P16 18 + P17 ≥18）= 合计 ≥ 200 tests 0F0E（P15 CompactCrash 独立 跑 隔离 静态 PEERS 污染）；≥ 160 的 NFR-4 门槛 必超 | T6 JV 合跑 计数 汇总 |
| AC-14 | rule | NeatFreak 三层 同步 完成：① Roadmap OQ-1 标 CLOSED 2026-09-28 绝对日期 ② AGENTS.md 追加 P17 12 Invariants 块 + 6+ 代码索引 ③ project_memory.md 同步 P17 交付/性能/硬约束/指南/Lessons 全 绝对日期 0 相对 日期 | T9 NeatFreak 验证 |

### 6.2 Rubric 类 AC（6 条；score 0-5 阈值 ≥4）

| ID | Type | 维度 | 0-5 Scale（anchor） | Pass 阈值 | Evidence 来源 |
|----|------|------|---------------------|-----------|---------------|
| AC-15 | rubric | **U1 复合 ORDER BY+LIMIT P99** | 5=≤80ms；4=≤120ms；3=≤250ms；2=≤500ms；1=≤1000ms；0=>1000ms | ≥ 4 | P17_CompositePerfRubricTest U1（N=500k 100 rounds） |
| AC-16 | rubric | **U2 GROUP BY 流式 mean** | 5=≤2000ms；4=≤8000ms；3=≤20000ms；2=≤60000ms；1=≤120000ms；0=>120s（超时） | ≥ 4 | P17_CompositePerfRubricTest U2（N=500k 20 rounds） |
| AC-17 | rubric | **U3 列存 String Bridge mean** | 5=≤400ms；4=≤1200ms；3=≤3000ms；2=≤6000ms；1=≤12000ms；0=>12s | ≥ 4 | P17_CompositePerfRubricTest U3（N=100k 30 rounds） |
| AC-18 | rubric | **NFR-10 相对性能提升 ≥5×（GROUP BY P17/P16 baseline）** | 5=≤0.10×（10×+提升）；4=≤0.20×（5×+）；3=≤0.50×（2×+）；2=≤1.00×（0 退化）；1=≤2.00×（≤2× 退化）；0=>2× 退化 | ≥ 4 | P17_CompositePerfRubricTest U2 双 基线 对比 |
| AC-19 | rubric | **c13 Gatekeeper 25 assertions 覆盖度** | 5=A6(6)+B6(6)+C5(5)+D5(5)+E3(3)=25 全 25/25；4=≥23 过；3=≥20；2=≥15；1=≥10；0=<10 | ≥ 4 → 必 25/25 实锤（继承 P15/P16 铁律 25 必 全） | P17_CompositeGatekeeperTest |
| AC-20 | rubric | **NeatFreak 零 相对 日期** | 5=Roadmap+AGENTS+project_memory 三文档 grep `今天/最近/上次/昨天/本阶段/下一阶段/待办` 0 语义命中；4=≤2 非 日期 类 口语化 命中；3=≤5；2=≤10；1=≤20；0=>20 或 相对 日期 | ≥ 4（必 0 命中 5 分） | T9 grep 验证 |
