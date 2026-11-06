# P17 OQ1 Composite Range Index V3 — Implementation Plan（中文；严格串行依赖，9 任务；每 TR rule/rubric 严格类型；NG-2 6 Facade 0 public 新；NG-3 0 依赖/0 集合/0 命令）

## Task 1: T1 — RegistryMetaPaths 复合 COMPOSITE_RANGE_ROOT + 9 个 compositeRangeIndex* helper（零外泄仅此处声明）

- **Status**：pending
- **Priority**：high
- **Depends On**：None
- **Description**：
  - 在 `RegistryMetaPaths.java` 新增 COMPOSITE_RANGE_ROOT = "/orm/index/composite/range" 与 ROW_RANGE_ROOT / COLUMN_RANGE_ROOT 同 /orm/index 平级；新增 9 个 compositeRangeIndex* helper（Root/Dir/Segment/Kind/Payload/Bucket/Bloom/Stat/Meta）。
  - 命名段复用 P10 `indexNameSegment` 16 hex 补零 + `compositeIndexName` safe 文件名段。
  - 非法参数 10 条 IAE（tableId<0 / indexId<0 / compositeIndexName empty / bucketId<0 / payloadSize<0 / segment<0 / kind null / bloomFilterId<0 / statType null / metaKey null）。
  - **零外泄硬要求**：grep p2p-db/src/main/java（排除测试）`COMPOSITE_RANGE_ROOT\|compositeRangeIndex` 仅 1 文件命中 = RegistryMetaPaths.java。
- **Acceptance Criteria Addressed**：AC-1, AC-8, AC-9
- **Test Requirements**：
  - `rule` TR-1.1：`P17_CompositeKeySpaceTest t01` 反射 10 个 compositeRangeIndex* helper + COMPOSITE_RANGE_ROOT 存在；返回值非空；首段前缀 `/orm/index`；路径段 count 与 P10 column/row range 完全平级（差≤1）。
  - `rule` TR-1.2：`P17_CompositeKeySpaceTest t02` 非法参数 10 条 IAE 全实锤（每类 1 例）；错误消息必须携带参数名。
  - `rule` TR-1.3：`P17_CompositeKeySpaceTest t03` 零外泄 grep p2p-db/src/main/java COMPOSITE_RANGE_ROOT/compositeRangeIndex 仅 1 个文件命中 = RegistryMetaPaths.java（计数=1）。
- **Notes**：与 P16 15 range helper 同文件零越界；不碰 6 Facade 类；indexNameSegment 复用 P10 safe 文件名段。

---

## Task 2: T2 — 复合索引 Key 编码 128bit VarInt Packing + 字典序 Comparator + BackwardCompat 双桶

- **Status**：pending
- **Priority**：high
- **Depends On**：T1
- **Description**：
  - `GenericManager.java` 新增 package-private：
    - `encodeCompositeKey(CompositeIndexInfo ci, Object[] colValuesPerCol)` → byte[] compKey（LONG=8B 无符号 BIG_ENDIAN 位翻转 v-Long.MIN_VALUE；STRING=UTF-8 字节 0x00 填/截 32B；总长度 8B 对齐）。
    - `decodeCompositeKeyFirstKCols(byte[] compKey,int k, CompositeIndexInfo ci)` → Object[] 前 k 列 还原。
    - `private static final Comparator<byte[]> COMPOSITE_KEY_UNSIGNED` 逐字节 (a&0xFF)-(b&0xFF) 无符号字典序。
  - BackwardCompat：旧 `compositeKeyValue(ci, criteria)` fnv1a64 hash 保留；新写入 `encodeCompositeKey`，双桶 0 冲突。
  - `IndexedValueKind.BYTES` 枚举项 package-private 扩展 DsEqIndexStore（0 public 改；内部 indexMap key 直接 哈希/比较 byte[]）。
- **Acceptance Criteria Addressed**：AC-2, AC-7
- **Test Requirements**：
  - `rule` TR-2.1：`P17_CompositeKeyCodecTest t01` N=1,000,000 随机 LONG+STRING 组合 → compKey 字典序 与 多列 逻辑 ORDER BY (a ASC,b ASC) 100% equals（0 mismatch）。
  - `rule` TR-2.2：`P17_CompositeKeyCodecTest t02` decodeCompositeKeyFirstKCols(k=1/2/3) 100,000 组 还原 值 与 原 值 equals（0 错）。
  - `rule` TR-2.3：`P17_CompositeBackwardCompatTest t02` 旧 FNV 模式 10,000 条 + 新 compKey 模式 10,000 条 → 混合 findByIndex 查询 0 冲突 0 错（每 key 1 次 验证）。
- **Notes**：6 Facade 0 公开签名改；允许 private/package-private；0 新 Maven 依赖；位运算 零分配；复用 P16 LEX_UNSIGNED 模式。

---

## Task 3: T3 — 复合索引前缀 Range Seek（EQ 前缀 k + RANGE k+1 列） + ORDER BY/LIMIT TopN 下推（跳过 sort）

- **Status**：pending
- **Priority**：high
- **Depends On**：T2
- **Description**：
  - `openCompositeIndexStore` 改 valueKind = BYTES；DsHashMap.subMap 走 compKey 字典序 seek。
  - `candidateIdsForQuery` 复合索引分支 扩：前缀 EQ k 列 + 第 k+1 列 RANGE（GT/GE/LT/LE/BETWEEN）→ 直接 subMap 定位；DESC 时 descendingMap()。
  - LIMIT 下推：每 bucket rowIds 取 `take = min(rowIds.size(), remainingLimit)`；total == limit 立即 break（P16 单列 findByRange TopN 算法 复用）。
  - `listEntities.sort` skip-sort 检查：wrapper.orders 列顺序/方向 与 bestCi 列 100% 对齐 → 跳过 Collections.sort 直接 return。
- **Acceptance Criteria Addressed**：AC-3, AC-4
- **Test Requirements**：
  - `rule` TR-3.1：`P17_CompositeRangeSeekTest t01` N=200,000 (a=1~200,b=1~1000,c 唯一)；`w.eq("a",100).between("b",200,500)` → 返回 id set = 全表 matchesAll 过滤 结果 100% equals（count + set 双 断言）。
  - `rule` TR-3.2：`P17_CompositeOrderBySkipTest t01` `orderByAsc("a","b","c") 对齐 复合索引` → Collections.sort 调用 次数=0（反射 插桩 计数 实锤）；LIMIT 100 返回 恰好 100 条（TopN 下推）。
  - `rule` TR-3.3：`P17_CompositeOrderBySkipTest t02` ORDER BY DESC + 非 对齐 ORDER BY 场景 sort 调用 正常；DESC 返回 行序 与 反向 sort 结果 100% equals。
- **Notes**：6 Facade 0 签名 改；仅 candidateIdsForQuery / sort 内部 检查 扩。

---

## Task 4: T4 — GROUP BY (a,b,c) + COUNT/SUM/MAX/MIN 流式聚合下推（package-private 0 public 新）

- **Status**：pending
- **Priority**：high
- **Depends On**：T3
- **Description**：
  - GenericManager 新增 package-private：
    - enum `AggType { COUNT, SUM, MAX, MIN }`（仅内部 可 测 反射）。
    - `Map<List<Object>,Object> aggregateByComposite(CompositeIndexInfo ci, List<String> groupByCols, AggType aggType, String aggCol, QueryWrapper<T> filterWrapper)`。
  - 流式 算法：DsHashMap 有序 compKey 迭代 → decodeCompositeKeyFirstKCols 取 分组前缀 → 与 上一 组 比较；不 同 组 入 map 重置 累加器；SUM = 累加 LONG；MAX/MIN = COMPOSITE_KEY_UNSIGNED compare；COUNT = 累加 bucket size。
  - filterWrapper 非 空：先 narrowByCriteria 白名单 过滤 rowId。
- **Acceptance Criteria Addressed**：AC-5
- **Test Requirements**：
  - `rule` TR-4.1：`P17_CompositeGroupByTest t01` N=500,000 (a=1~500,b=1~1000)；`groupByCols=["a","b"]` `aggType=COUNT` → 结果 map = 全表 findList 后 `stream().collect(groupingBy(a,b), counting())` 100% equals（键值 双 断言 全组 500×1000=500,000 每 组 覆盖）。
  - `rule` TR-4.2：`P17_CompositeGroupByTest t02` SUM/MAX/MIN 三 聚合 每 个 1 组 用 例 → 结果 = 全表 stream 聚合 值 100% equals。
  - `rule` TR-4.3：`P17_CompositeGroupByTest t03` filterWrapper 非 空 场景 → 白名单 过滤 后 聚合 结果 与 先 matchesAll 再 stream 聚合 100% equals。
- **Notes**：0 Facade public 改；仅 package-private；业务侧 通过 反射 验证（测试 专用）；0 新 集合类。

---

## Task 5: T5 — P16 遗留 列存 String Range Bridge 补齐（Class.forName + MethodHandle 反射 调用 列存 EqIndexStore 5 个 range 方法；取消 P16 t02 @Ignore）

- **Status**：pending
- **Priority**：high
- **Depends On**：T2
- **Description**：
  - `candidateRowIdsFromIndex` L1993 `非 LONG 且 range op` 分支 从 `return null` 改：
    - Class.forName("com.q3lives.ds.database.columnar.index.EqIndexStore")。
    - MethodHandle 调 对应 findRowIdsBetween/Ge/Gt/Le/Lt（loBytes = String.valueOf(lo).getBytes(UTF_8)；hiBytes 同理；asc=true；limit=Integer.MAX_VALUE 未 下推 后续 再 优化，先 保证 narrowing）。
    - 类 不 存在 / 方法 不 存在 / 异常 → 回退 return null（matchesAll 兜底）。
  - 0 编译期 import 列存 类；0 Facade public 改；仅 内部 分支 扩。
  - **取消 P16_RangeGenericManagerTest t02 @Ignore**：P16 因 bridge 空 跳过 的 String range 场景 必跑。
- **Acceptance Criteria Addressed**：AC-6
- **Test Requirements**：
  - `rule` TR-5.1：`P17_StringBridgeTest t01` N=100,000 String 列（"k"+0~99999 5 位 补零）；`w.between("name","k01000","k05000")` → 返回 id set = 全表 matchesAll 结果 100% equals（count + set 双 断言）。
  - `rule` TR-5.2：`P16_RangeGenericManagerTest`（取消 @Ignore 后）Tests run: 3（原 2 run + 1 skipped）→ now 3 run 0F0E 0 Skipped。
  - `rule` TR-5.3：列存 未 init / 异常 fallback 场景 → 不 抛 异常；matchesAll 兜底 结果 仍 正确。
- **Notes**：Class.forName + MethodHandle 方式 避免 行存 → 列存 编译期 耦合；0 Facade public 改 0 新 依赖。

---

## Task 6: T6 — 6 Facade Hook（NG-2 0 public 新，B6 基线 ±4） + c13 Gatekeeper 25 assertions 全通过（A6+B6+C5+D5+E3 五组）

- **Status**：pending
- **Priority**：high
- **Depends On**：T3, T4, T5
- **Description**：
  - 6 Facade 类（DsDatabaseLocal / ColumnarStore / TableMetaStore / ColumnRegistry / RowIdSequenceStore / EqIndexMetaStore）**public 签名 0 新增**；仅 package-private/private 内部 扩；B6 基线继承 P16 实锤：48/24/3/8/1/7 ±4。
  - c13 Gatekeeper 25 assertions：
    - A6（6 条）：6 Facade 反射 count public 方法 在 baseline±4 内 每类 1 条。
    - B6（6 条）：6 Facade 新 增 内部 方法 数（public 0）每类 1 条 = `publicCount == baseline ±4`。
    - C5（5 条）：①COMPOSITE_RANGE_ROOT + ②COLUMN_RANGE_ROOT + ③ROW_RANGE_ROOT 三 组 根路径 字面量 仅 RegistryMetaPaths 命中 （每 组 1 条）；④ P2PCommand enum count 不变（仅 31201-31303 6 条）；⑤ compositeRangeIndex* helper 声明 仅 RegistryMetaPaths 1 处。
    - D5（5 条）：fastutil / eclipse-collections / HPPC / chroniclemap / commons-collections4 五 通用集合 探针 grep 命中 = 0 每类 1 条。
    - E3（3 条）：2 个 RegistryMetaPaths 实现 类 + 1 个 EqIndexMetaStore 实现 类 public 方法 0 泄漏 每类 1 条。
- **Acceptance Criteria Addressed**：AC-10, AC-11, AC-12, AC-19
- **Test Requirements**：
  - `rule` TR-6.1：`P17_CompositeFacadeHookRealTest` 2 tests → 2/2 PASS（B6 6 Facade 基线 ±4 实锤 + 内部 扩 含 0 public 新增）。
  - `rule` TR-6.2：`P17_CompositeGatekeeperTest` 1 test 25 assertions → **25/25 必全 PASS**（A6 6+B6 6+C5 5+D5 5+E3 3 = 25）。
  - `rubric` TR-6.3：25/25 断言 覆盖度 score=5（必 满分 25/25=5）；threshold ≥4。
- **Notes**：继承 P15 c11 / P16 c12 Gatekeeper 模式 零 新 公共 类 改；仅 新增 测试 类 2 个（FacadeHook + Gatekeeper）。

---

## Task 7: T7 — Performance Rubric U1/U2/U3 3 项（双 4/5 达标 + 相对性能 ≥5×）

- **Status**：pending
- **Priority**：medium
- **Depends On**：T3, T4, T5
- **Description**：
  - U1：复合 ORDER BY+LIMIT P99 → N=500,000 100 rounds。
  - U2：GROUP BY 流式 mean + 与 P16 baseline（findList 全量 + stream groupBy）相对 提升 → N=500,000 20 rounds；同时 跑 P16 baseline 对 比 计算 ratio = P17 耗时 / P16 baseline 耗时。
  - U3：列存 String Bridge BETWEEN mean → N=100,000 30 rounds。
- **Acceptance Criteria Addressed**：AC-15, AC-16, AC-17, AC-18
- **Test Requirements**：
  - `rubric` TR-7.1 **U1 P99**：`P17_CompositePerfRubricTest U1` 100 rounds → P99=? ms → 5=≤80；4=≤120；threshold ≥4。
  - `rubric` TR-7.2 **U2 mean + 相对提升**：`P17_CompositePerfRubricTest U2` 20 rounds → mean=? ms + ratio=P17/P16 baseline → mean 5=≤2000；4=≤8000 threshold≥4；ratio 5=≤0.10；4=≤0.20 threshold≥4。
  - `rubric` TR-7.3 **U3 String Bridge mean**：`P17_CompositePerfRubricTest U3` 30 rounds → mean=? ms → 5=≤400；4=≤1200 threshold≥4。
- **Notes**：3 rubric 每 个 score≥4；U2 同时 验 AC-16（绝对）+ AC-18（相对 ≥5×）两 条 AC。

---

## Task 8: T8 — JV 合跑 P10~P17 ≥ 200 tests 0F0E（P15 CompactCrash 单跑 防 PEERS 静态污染；P16 t02 取消 @Ignore 计入）

- **Status**：pending
- **Priority**：high
- **Depends On**：T1~T7 全 completed
- **Description**：
  - 分 批 跑 避免 长 耗时 perf + 静态 污染 混 跑 超时：
    - Batch A：P10 13 + P11 23 + P12 26 + P13 Bloom 9 = 71。
    - Batch B：P14 全量 39（AcidGatekeeper c5 expected=2 已 调 对齐 P15 两 处 /tx；TpccPerf U2 ratio 阈值 ≥0.50 已 调）。
    - Batch C：P15 54（除 CompactCrash 2 单 独 跑 防 PEERS 静态 ConcurrentHashMap 跨 类 污染） + P15 CompactCrash 2 单 独。
    - Batch D：P16 18（P16_RangeGenericManagerTest t02 @Ignore 已 取消 → 3 run 原 2 run + 1 skipped）+ P17 ≥ 18（T1 3 + T2 3 + T3 3 + T4 3 + T5 3 + T6 2 + T7 3 perf = 合计 20）。
  - 总 和 ≥ 200 tests 0F0E ≥ 160 NFR-4 门槛 必 超。
- **Acceptance Criteria Addressed**：AC-13
- **Test Requirements**：
  - `rule` TR-8.1：Batch A 71 → 0F0E。
  - `rule` TR-8.2：Batch B 39 → 0F0E。
  - `rule` TR-8.3：Batch C 54 + CompactCrash 2 → 0F0E（CompactCrash 单 独 跑 隔离）。
  - `rule` TR-8.4：Batch D P16 18 + P17 ≥ 18 → 0F0E（P16 t02 3 run 计入）。
  - `rule` TR-8.5：总 和 ≥ 200 ≥ 160 门槛 实锤（计数 打印）。
- **Notes**：P15 CompactCrash 合 跑 120s TIMEOUT 历史 根因 已 记 project_memory Lessons；必 单 独 跑 隔离。

---

## Task 9: T9 — NeatFreak 三层交叉同步（Roadmap + AGENTS.md + project_memory.md）+ 12 Invariants × 6 代码索引 + 零相对日期（2026-09-28 absolute）

- **Status**：pending
- **Priority**：low
- **Depends On**：T8
- **Description**：
  - 三层 同步 零 相对 日期 硬要求（grep `今天/最近/上次/昨天/本阶段/下一阶段/待办` 0 语义 命中）。
  - **Roadmap L450**：OQ-1（P17 Composite Range V3）追加 CLOSED 2026-09-28 详情（200 tests JV PASS；c13 25/25；U1/U2/U3 三 4/5；相对 提升 ≥5×）；OQ-7 三选一 下阶段 候选项（① Online DDL 复合索引重建 ② Linux CFS 烟测 P10~P17 ③ Other）。
  - **AGENTS.md**：在 现有 P16 12 Invariants 块 后 不修改 旧 内容 **仅 追加** P17 12 Invariants 块；严格 对齐 P15/P16 格式；第 12 条 带 6+ file:// 代码索引（RegistryMetaPaths / GenericManager codec / DsHashMap / openCompositeIndexStore / EqIndexStore 列存 / P17 6 测试 类 等）；日期 全 绝对 2026-09-28。
  - **project_memory.md**：同步 ① P17 交付组件 6 项；② 性能 U1 P99/U2 mean/U3 mean + 相对 提升 比值；③ NG 红线 继承 2026-09-28 absolute；④ GROUP BY 下推 优化 原则 + BackwardCompat 双桶 原则；⑤ Lessons：P15 静态 PEERS 污染（老 条）+ P16 String bridge 延后 补 桥（老 条）+ 新增 1 条：`复合索引 BackwardCompat 双桶 必 保留 FNV 旧 模式 防 落 盘 旧 索引 不 可读`。
- **Acceptance Criteria Addressed**：AC-14, AC-20
- **Test Requirements**：
  - `rule` TR-9.1：Roadmap L450 grep P17 CLOSED 2026-09-28 绝对 日期 存在；OQ-7 三选一 存在。
  - `rule` TR-9.2：AGENTS.md P17 12 Invariants 12 条 条目 完整；第 12 条 6+ file:// 链接 指向 存在 文件；grep 相对 日期 关键词 0 语义 命中。
  - `rubric` TR-9.3 NeatFreak 零 相对 日期：Roadmap + AGENTS + project_memory 三 文档 grep 关键词 0 → score=5；threshold ≥4。
- **Notes**：NeatFreak 执行 顺序 固定（Roadmap → project_memory → AGENTS.md）；即使 中断 外部 可见 信息 也 是 正确 的。
