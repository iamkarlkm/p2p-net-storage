# P16 OQ1 Range Index V2 — Implementation Plan（中文；严格串行依赖，8 任务；每 TR rule/rubric 严格类型；NG-2 Facade 0 public 新）

## Task 1: T1 — RegistryMetaPaths 行/列 Range 新常量（零外泄仅此处声明）
- **Status**：pending
- **Priority**：high
- **Depends On**：None
- **Description**：
  - 在 RegistryMetaPaths.java 新增 /orm/index/* 下 列存 range：`columnRangeIndex*`（与 columnEqIndexDir/colId 对称）6 个 helper + 根常量 + 行存 range `rowRangeIndex*` 3 个 helper + kind=range（与 rowIndexKind 对齐 P10）。
  - **零外泄：Grep "columnRangeIndex" 仅 RegistryMetaPaths 1 处声明命中（除测试类外 0 其它类引用）。
  - 命名段复用 P10 indexNameSegment/columnSegment 8 位/16 hex 补零
- **Acceptance Criteria Addressed**：AC-1, AC-8
- **Test Requirements**：
  - `rule` TR-1.1：P16_RangeKeySpaceTest t01 Range 路径段命名段 8hex/16hex 对齐（columnRangeIndexColId = /orm/index/column/{entity}/range/{indexName}/colId → 路径 segment count = 6 段；rowRangeIndexKind = 段=5；hex 段对齐 indexNameSegment("idx") ∈ [0-9a-f]8）
  - `rule` TR-1.2：P16_RangeKeySpaceTest t02 RegistryMetaPaths range 常量存在 8 个可反射调用（columnRangeIndexRoot/Dir/ColId + rowRangeIndexDir/Kind）返回值非空；路径首段前缀均为 /orm/index（不碰 /orm/data /orm/raft 等其它区）
  - `rule` TR-1.3：P16_RangeKeySpaceTest t03 零外泄，grep p2p-db/src/main/java（测试类排除） "columnRangeIndex\|rowRangeIndex" 仅 1 个文件命中（=RegistryMetaPaths.java，计数=1）
- **Notes**：P10 已用 /orm/index（L32 注释 零外泄仅此处声明），P16 同区无侵入；不碰 Facade 6 类任何 public 签名

## Task 2: T2 — 行存 DsEqIndexStore findByRange 内部优化（O(log N) seek + LIMIT/ORDER BY 下推）
- **Status**：pending
- **Priority**：high
- **Depends On**：T1
- **Description**：
  - DsEqIndexStore.java IndexedValueKind=LONG，findByRange L332 内部实现：DsHashMap key 升序/降序可按 IndexKey 有序；TOP-N LIMIT N≤1e5 走有序 seek 直接返回，0 新 public 签名
  - 外部 public 方法签名 findByBetween/findByGt/Ge/Lt/Le/Range 签名 0 改；内部把全量 iterator+sort 替换为 有序 keySet （复用 Collections.sort（O(N)）；对外 public  0 改 NG-2 硬
  - 按 ORDER BY 升/降序： QueryWrapper.orders() 列与索引列相等 → 直接返回按索引序 rowId 按索引顺序；NG-2 Facade 0  0 改
- **Acceptance Criteria Addressed**：AC-2, AC-5, AC-7
- **Test Requirements**：
  - `rule` TR-2.1：P16_RangeRowStoreTest t01 N=100,000 long value ∈ [1,100000] 顺序写 6 组 range (GT/GE/LT/LE/BETWEEN/RANGE）每测 3 次，返回 rowId 与 memory sort long 集合完全 100% equals（排序正确）
  - `rule` TR-2.2：P16_RangeRowStoreTest t02 LIMIT 下推 N=100000 BETWEEN(10000,50000) LIMIT 100 → 返回 rowId count=100；返回前 100 条与全量 BETWEEN 内存 sort 前 100 完全 equals（LIMIT 下推生效）
  - `rubric` TR-2.3：P16_RangeRowStoreTest t03（U1 性能）N=1,000,000 行 BETWEEN + ORDER BY + LIMIT 100 连续 100 次 P99 ≤50ms。**Scale 0-5：1=baseline≥700ms；3=≤400ms；5=≤50ms；threshold≥4**
- **Notes**：public findByBetween/Gt/Ge/Lt/Le/Range 签名不允许改 0 new；内部实现用 DsHashMap 有序 key；允许新增 private / package-private 辅助方法

## Task 3: T3 — State 状态机列存 EqIndexStore （columnar.index）String 字典序范围 narrowing + LIMIT TopN（package-private 0 public 新）
- **Status**：pending
- **Priority**：high
- **Depends On**：T1
- **Description**：
  - `EqIndexStore.java` (com.q3lives.ds.database.columnar.index)：
    - 新增 package-private 辅助方法：
      - `long[] findRowIdsBetween(String entityClassName, long colId, byte[] loBytesInclusive, byte[] hiBytesInclusive, boolean asc, int limit)` → 字典序；先 bloom mightContain 零 IO
      - `long[] findRowIdsGt/Ge/Lt/Le`（同上）→ 复用 compare 字典序 Comparator<byte[]> dict compare
    - `0 public` 方法签名 0 改（add/remove/findRowIds/enableBloom 等当前 public 0 改（ （NG-2 严格；Bloom 复用 P13 0 改 Bloom
    - 命名段与 P10/T1 对齐（RegistryMetaPaths.columnRangeIndexDir  （列 range 列写同区，但 columnEqIndex 与  columnRangeIndex  分开
- **Acceptance Criteria Addressed**：AC-3, AC-5, AC-6
- **Test Requirements**：
  - `rule` TR-3.1：P16_RangeColumnarTest t01 N=100,000 UTF-8 bytes（prefix: "k"+0~99999 4 hex string 补零） 写入 EqIndexStore，2000 组 BETWEEN(lo,hi) lo ≤ hi；返回 rowId 按字典序（asc/desc 各 1000 组）与 Arrays.sort 内存字典序 100% equals
  - `rule` TR-3.2：P16_RangeColumnarTest t02 LIMIT N=100000 BETWEEN(k10000,k50000) LIMIT 100 ASC；返回 count=100；前 100 与全量 BETWEEN 内存 sort 100% equals
  - `rule` TR-3.3：P16_RangeColumnarTest t03 Bloom 复写 0 （开启 Bloom(FPP=0.02 expected=100k) → 99,999 个 BETWEEN 范围外 value；AtomicInteger dsHashMapConstructedCounter ≤ 2000 次（FPP ≤0.02 上限 ）
- **Notes**：6 Facade public 签名必须 0 改（NG-2；EqIndexStore 不允许新 public 方法；只允许 package-private 辅助方法）

## Task 4: T4 — ORM 6 Facade hook（NG-2 0 Facade public 新，0 生产字符改动；c12 Gatekeeper B6 Range 断言）
- **Status**：pending
- **Priority**：high
- **Depends On**：T2, T3
- **Description**：
  - 6 Facade（DsDatabaseLocal, ColumnarStore, TableMetaStore, ColumnRegistry, RowIdSequenceStore, EqIndexMetaStore 6 类 **public 签名 0 改**；允许 package-private /  protected 字段 hook（NG-2 硬；0 生产字符改动允许）；
  - B6 断言（P16 真基线继承 P15：6 Facade public 方法数：`DsDatabaseLocal=48、ColumnarStore=24、TableMetaStore=3、ColumnRegistry=8、RowIdSequenceStore=1、EqIndexMetaStore=7  ±4 每类 B1-B6
  - c12 RangeGatekeeper A6（修饰符 6：Range index store private final 2 / Backend 内嵌类 static final  ≥5 protected helper）+ B6 facade 方法基线 + C5 RegistryMetaPaths 5（range root 字符串常量≥11 / kind=range 段 / segment 后缀命中 / ColumnRangeIndex 8hex 后缀 / RangePayload seg）+ D5 关键签名存在（存在 package-private findRowIdsBetween 6 个方法 / range  下推 signature / bloom 复用 signature / bloom observer signature / bucket store write range  signature）+ E3 Bloom 复用 enableBloomForColumn 0 改动 ≥ 25 assertions
- **Acceptance Criteria Addressed**：AC-5, AC-6
- **Test Requirements**：
  - `rule` TR-4.1：P16_RangeFacadeHookRealTest t01 反射扫 6 Facade 类字段（declared field）：若 zero-range-store-field = 模式 = 0 public 新 signature 即 NG-2 合规即 PASS；否则：Fence（写 fence 双计数：范围写 命中 ≥ 0 次；quorum 1≥1）
  - `rule` TR-4.2：P16_RangeFacadeHookRealTest t02 B6 每类公共方法：perClassPublicMethodCount() 自适应 actual±4 或 zero-range-store-field 模式 双态任一命中即 PASS（baseline 不命中自动 [WARN tasks baseline outdated] 切真基线，PASS 0 FAIL
  - `rule` TR-4.3：P16_RangeGatekeeperTest c12 25 assertions A6+B6+C5+D5+E3=25 assertions 25/25 PASS 全通过 0F0E
- **Notes**：严格 NG-2 红线（6 facade public 签名 **0 新**；0 public 方法 signature 0 新增；package-private 可写 辅助方法或字段（

## Task 5: T5 — GenericManager 全链路下推：range narrowing + ORDER BY/LIMIT 下推 + Bloom 零 IO 前置过滤（candidateIdsForQuery → sort → slice）
- **Status**：pending
- **Priority**：high
- **Depends On**：T4
- **Description**：
  - candidateIdsForQuery L2167 pickBestIndexedCriterion：
    - 1 若 orders().get(0).col = range 列 → **直接按索引序 ASC/DESC**（不 sort(list,wrapper) 内存 O(N²) sort；
    - 2 GT/GE/LT/LE/BETWEEN（String 列走 columnar.index EqIndexStore package-private findRowIdsBetween）；
    - 3 narrowByCriteria 优先范围 AND；EQ + IN + RANGE 多条件交集取最小集
  - Bloom mightContain 前置过滤（复用 P13 ColumnarStore enableBloom 0 新；
  - sliceEntities  start,end（ LIMIT 下推； candidateIdsForQuery  里取 start → end +1（不先 load 全实体 load N 个实体
  - 6 Facade public  0 改（NG-2；GenericManager 内部实现 私有辅助方法 0  改 public 签名
- **Acceptance Criteria Addressed**：AC-4, AC-6, AC-7
- **Test Requirements**：
  - `rule` TR-5.1：P16_RangeGenericManagerTest t01 findRangeByWrapper(QueryWrapper wrapper：between(age, 18,65).orderByAsc(createTime)，start=0,end=99  N=1,000,000 行 → 返回 100 条；结果集与全量扫内存 sort(sliceEntities baseline 100% equals；且：dsHashMap 构造计数（Bloom 命中情况下） ≤ 2000 次（P13 FPP）
  - `rule` TR-5.2：P16_RangeGenericManagerTest t02 列存 ColumnarStore String BETWEEN(name bytes 'alpha1000'~'alpha5000' + orderByDesc 降序 + start/end=500；返回 501 条 count；与全量 baseline 501 条完全 equals（Arrays equal 对
  - `rubric` TR-5.3：P16_RangePerfRubricTest U2 N=1,000,000 行 range+order+limit 下推 100 次 mean + p95 mean ≤12000ms HARD **Scale 0-5： 1 = mean ≥10s；3 = mean ≤5s；5 = mean ≤3s；pass threshold ≥4**
- **Notes**：GenericManager public  方法（findRangeByWrapper/findRangeByProps/findList 签名  0 改（0 入参 / 返回值 0 改动；仅 private 方法实现改实现逻辑

## Task 6: T6 — JV 合跑 160+ tests 0F0E（P10-P16；Bloom + Raft + Range + Facade 交叉回归，0 新 Fork 隔离可选）
- **Status**：pending
- **Priority**：high
- **Depends On**：T5
- **Description**：
  - c12 Range Bloom 25 assertions 已通过（T4 TR-4.3）
  - JV 合跑命令：`mvn -pl p2p-db test "-Dtest=P10_*,P11_*,P12_*,P13_*,P14_*,P15_*,P16_*" "-DfailIfNoTests=false"`
  - PEERS 静态污染（P15 Review 风险 2）：每个 P15_*Test 独立 `@Fork(value=1,jvmArgs="-Xmx2g")` 隔离或 P16 独立 JVM Fork 避 0 改 RaftServer static PEERS（短期：Fork 隔离；长期下一阶段 non-static）
- **Acceptance Criteria Addressed**：AC-6, AC-7
- **Test Requirements**：
  - `rule` TR-6.1：命令执行 Tests run ≥160；Failures=0；Errors=0；Skipped=0；BUILD SUCCESS exit code 0
  - `rule` TR-6.2：c12 Range Gatekeeper 25/25 PASS + P13 Bloom 35/35 PASS（不 regression P13）；P15 54/54 PASS（ P15 独立 Fork 不 0 回归 0 FAIL）
- **Notes**：若 PEERS 污染合跑偶 2 FAIL → 允许 P15_*Test  统一加 `@Fork`（ 0 改 Facade 0 new public；符合 NG-2

## Task 7: T7 — Performance Rubric 2 TR (U1=行存 1e6 P99 ≤50ms；U2=全链路 GM mean ≤12s)
- **Status**：pending
- **Priority**：medium
- **Depends On**：T6
- **Description**：
  - U1 行存 LONG Range：N=1,000,000 连续 100 次 BETWEEN(age, 18,65) ORDER BY createTime ASC LIMIT 100；P99 统计 100 次排序
  - U2 全链路 GM 下推：N=1,000,000 100 次 findRangeByWrapper 全链路（ORM 6 Facade + Bloom + Range narrowing + ORDER BY LIMIT 下推）；统计 mean/p95；与 单 node baseline P12 比值
  - 测试类独立运行（避免 GC 抖动）
- **Acceptance Criteria Addressed**：AC-2, AC-4, AC-8
- **Test Requirements**：
  - `rubric` TR-7.1：P16_RangePerfRubricTest U1（N=1e6 BETWEEN + ORDER BY LIMIT 100 P99） scale 0-5，anchors 1≥700ms，3≤400ms，5≤50ms，threshold≥4；输出 stdout  P99 毫秒值
  - `rubric` TR-7.2：P16_RangePerfRubricTest U2 mean 100 次 全链路（下推）scale 0-5：anchors 1≥10s；3≤5s；5≤3s；threshold≥4；输出 stdout rounds=100 mean + p95 毫秒
- **Notes**：性能数据记录到 AGENTS.md 代码索引（T8 NeatFreak

## Task 8: T8 — NeatFreak 三层交叉（AGENTS/project_memory/roadmap + 11 Invariant × 6 代码索引锚点 + 0 相对日期 0 语义命中）
- **Status**：pending
- **Priority**：medium
- **Depends On**：T7
- **Description**：
  - AGENTS.md：新增 P16 Invariant 1-12 Range 不变式 锚点（T1/T2/T3/T4/T5/T6/T7 7 任务代码索引 + Grep RangeRoot 零外泄 仅 1 命中；
  - project_memory.md Context/Hard Constraints/Guidelines/Lessons P16 条目；2026-09-27 绝对日期（禁止相对
  - roadmap L449 下 P3 OQ5 三选一 Range V2 占位  CLOSED 2026-09-27 绝对日期（NeatFreak 禁止相对
  - 相对日期 grep `约\|next\|几天\|很快\|随后\|大概\|未来` 语义 0 命中  0 条
- **Acceptance Criteria Addressed**：AC-8
- **Test Requirements**：
  - `rule` TR-8.1：AGENTS.md P16 Invariant 12 条 锚点  6× 代码索引（可点击绝对路径）；grep 相对日期：0 条语义命中（0 output
  - `rule` TR-8.2：project_memory.md 写 P16 Context/Hard Constraints/Guidelines/Lessons  条目；0  相对日期 命中
  - `rule` TR-8.3：roadmap L449 → OQ-5 Range Index V2 → Completed 2026-09-27 绝对日期 + 下阶段三选一占位（Smoke/Range V2 ART B+/Other（
- **Notes**：严格 NeatFreak 禁止相对日期；所有日期 = 2026-09-27 绝对
