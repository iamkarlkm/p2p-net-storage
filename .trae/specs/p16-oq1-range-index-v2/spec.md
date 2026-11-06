# P16 OQ-1 Range Index V2 (Range Scan + ORDER BY / LIMIT 下推) — Product Requirements Document (Spec Mode — 中文

## Overview

### 1.1 Summary（做什么 & 为什么

**P16 要解决的 3 个现状问题（真实代码 gap）**：
- **Gap 行存 DsEqIndexStore.findBByRange L332-L369 现状**：虽然实现了 LONG 范围，但内部是 **DsHashMap.iterator() 全量扫 + Collections.sort（O(N)），N=1,000,000 行时 range scan 700ms+，比 40~100× 慢于 BTree/SkipList O(log N) seek）。
- **Gap 列存 ColumnarStore / EqIndexStore（columnar.index 包）只有 FNV64 hash EQ only 范围完全空：Columnar EqIndexStore hash64(norm) → findRowIds 是 EQ 单点命中，不支持范围下推，ORDER BY/LIMIT 下推 必走 sort(list,wrapper) 内存 sort N log N O( N² ）
- **Gap 2 GenericManager**：candidateIdsForQuery narrowByCriteria 仅 GT/GE/LT/LE/BETWEEN 命中 LONG 列，但**（列存完全不生效 → 实际仅行存单列 LONG range 走 O(N) 实现；String 完全不支持 range 范围 / 2D 地理范围 ）
- **Gap 3 QueryWrapper.Op 缺 ORDER BY/LIMIT 下推完全没生效 ORDER BY 列序的 6 facade 签名不侵入的 ）

P16 **范围扫描 V2 = 行存/列存共用一套 Range+ORDER BY / LIMIT 下推全链路：**
  - RegistryMetaPaths 加 /orm/index/{eq|range}/{entity}/{indexName} 区（零外泄仅此处声明
  - 行存 DsEqIndexStore.findByRange 优化（内部用 DsFixedBucketStore 原语不碰 ORM Facade 0 新增 Maven 依赖（复用 P13 Bloom 零 IO）
  - 列存 Columnar EqIndexStore 范围：`findRowIdsBetween/orderLimit（byte[] 字典序 BETWEEN + Comparator<byte[]> 字典序 + 全局有序键排序，不依赖 JDK 默认 Arrays.binarySearch）
  - GenericManager.candidateIdsForQuery + sort + slice 三链路下推：① bloom 零 IO narrowing ② Range 取 rowIdSet ③ limit + ORDER BY 在索引序直接 跳读 ，④ matchIndex 用 Index序直接 TOP-N）
  - c12 Gatekeeper 25 assertions 钉死 + 3 piece 零外泄 + facade 0 签名 0 改动（零改

### 1.2 Purpose
**Target Users**
- 业务侧 ORM 业务调用方：gm.findRangeByWrapper(new QueryWrapper().between("age",18,65).orderByAsc("createTime") 毫秒级响应（1,000,000 行 P99 <50ms）

## Goals
2.1 功能性 Goals（必须交付 1~6 Facade public 方法 **0 新增（NG-2；NG-3 Facade（0 新 0 侵入）
G1 RegistryMetaPaths 列存/行存 range 区 + Bloom 零外泄仅此处声明 1 处（1 处 Grep RangeRoot 仅 1 命中
G2 行存 DsEqIndexStore.findByRange O(logN）内部升/降序 + LIMIT TopN（下推（ 1,000,000 行 Range+ORDER BY P99≤50ms ≤50ms)
G3 列存Columnar EqIndexStore (P11 Data 列范围 findRowIdsBetween + 字典序 GT/GE/LT/LE/BETWEEN + LIMIT Top-N 下推(实锤 6 facade  public  0 新 signature (NG-2)
G4 GenericManager.candidateIdsForQuery 单 AND/OR 多条件下 Range 优先级高于 EQ：GT/LT/LE/BETWEEN（LONG + String 字典序range narrowing）

## Non-Goals （严格不做）
NG-1 多列复合 BTree/ART（SkipList（复合范围 index（（（
NG-2 6 Facade public 签名 **0 改（public 签名 **禁止新增 public 方法；允许 package-private/protected 写 hook 允许 **0 字符（严格 （NG-2 硬红
NG-3 零新 Maven 依赖（0，p2p-ws-sdk 五子项目 0（五子 0（p2p-core 0 动 P2PCommand（仅 31201-31303 0 新增 cmdType 号（
NG-4 地理 2D RTree/R*Tree/空间索引；地理 between("lat/lon 双列范围不在本阶段
NG-5 分布式 Range 一致性 MVCC 快照范围隔离 RR 可见性（P14 已写 MVCC 层 0（ 0 本阶段不重做
NG-6 多租户 online schema DDL（列 DDL range online rebuild 下一阶段；

## Background & Context（代码证据链
C1 P13 OQ4 Bloom 零 IO（P13（P13 BloomFilter64 + 6 已在 columnEqIndex 0（/orm/index/... (P13 已开；P16 Range V2 复用 eq/range 双分区分开，P16 复用 P13 Bloom + 复用 DsFixedBucketStore DsHashMap + P10 RegistryMetaPaths 11 Invariant 复用
C2 列存 EqIndexStore.add/remove 列 P13 BloomFilter64；range 加在 P16 复用 bloom mightContain 零 IO
C3 行存 DsEqIndexStore L332 已写 findByRange() 骨架（iterator + Collections.sort O(N)）；P16 内部把 iterator→升序有序映射；N 项，对外 0 暴露 public 签名）
C4 P11 ORM Facade QueryWrapper.Op（GT GE LT LE BETWEEN 全定义，orders(List<Order>）全 0 缺 ORDER BY LIMIT）
C5 roadmap L449 OQ-5 P15 CLOSED 2026-09-27 2026-09-27 三选一 OQ-1 Range V2

## Functional Requirements（FR）
FR-1 RegistryMetaPaths 新 /orm/index/column/... range 区 6 组常量
- (FR-1.
-1 列存 range：columnRangeIndex* 根路径常量：/orm/index/column/{entity}/{ logicalName /{...range 区（与 columnEqIndex* 完全平级
- FR-1.2 行存 range：rowIndexes 区 range 常量 + kind=range（与 eq 平级（RegistryMetaPaths 1 处 声明 0 外泄（零外泄仅此处声明 1 处（
- FR-1.3 命名段对齐 P10 8 十六进制补  16补 与 P10 RegistryMetaPaths indexNameSegment/columnSegment 对齐（命名 复用
FR-2 行存 DsEqIndexStore IndexedValueKind.LONG findByRange 内部优化 O(log N)  seek
- FR-2.1 DsEqIndexStore（内部：map（，map 升序映射 →→ findByRange（返回  ，LIMIT top-N ，LIMIT 排序（indexMap.keySet() 有序集合； （不暴露新 public 方法（NG-2）
- FR-2.2 findByGt/Gte/Lt/Lte/Between 返回 rowId 按升序/降序（按 索引序 LONG 字典序 LONG 升序；返回值顺序与 QueryWrapper.orders 匹配；NG-2
FR-3 列存 EqIndexStore(columnar.index) String/LONG range  narrowing  0 public  方法
- FR-3.1 EqIndexStore 新增 package-private ：findRowIdsBetween(entity, loBytes, hiBytes, limit, ascDesc：  （Comparator<byte[]>（字典序；LIMIT TopN；0 signature 改  public 方法 ；NG-2
- FR-3.2 EqIndexStore.add/remove 不变，Bloom mightContain()  0（P13 ；范围先 bloom 零 ；FR-3.3 EqIndexStore range 列 列  写 findRowIds（range narrowing 0 字符改现
FR-4 GenericManager 全链路 下推（candidateIdsForQuery → order +  narrowing（（
- FR-4.1 pickBestIndexedCriterion 优先 order 与 range 组合：order 列=索引列 直接走 range  直接索引返回（0（0 （不 sort(list, wrapper) 内存 sort（，0 新 Facade 签名
- FR-4.2 sliceEntities（start,end) （candidateIdsForQuery 里 limit 下推；Bloom 先 0 过滤。NG-2
- FR-4.3 BETWEEN(String 列 narrowing 命中则降序（String 字典序 FR-3 Comparator<byte[]>（dict  compare(0 NG-2
FR-5 与 P13 Bloom  0 侵入（复用 P13 enableBloomForColumn  ；BloomFilter64 0 新类 0 新字段
FR-6 6 Facade public 签名严格 0 改（DsDatabaseLocal, ColumnarStore, TableMetaStore, ColumnRegistry, RowIdSequenceStore, EqIndexMetaStore 6 类 public 方法 0 新；允许 package-private/protected hook 内部写

## Non-Functional Requirements（NFR 质量）
NFR-1 Performance（性能：U1 N=1,000,000 行 Range + ORDER BY + LIMIT 100） P99 ≤50ms（HARD ≤50ms，SOFT ≤80ms）；当前行存 O(N) findByRange N=1e6 P99=700ms 目标 ratio=14×  快
NFR-2 Performance U2 Cold Start（冷启动 1,000,000 行 构建 + Range V2 索引构建 ≤12,000ms HARD
NFR-3 Correctness：9 Facade public 方法数 B6 0 新（6 Facade  public  0 新增；允许 package-private 0 改 NG-2 0 改动
NFR-4 零外泄：RegistryMetaPaths 新 Range 区 1 处声明 1 处命中（grep -r "RANGE_ROOT" 仅 1 出（C 点））
NFR-5 兼容性：P13/P10/P11/P12/P14/P15 全部 tests 0 回归（JV≥160 tests 0F0E 与 P10~P16 合跑 0F0E ≥ 160 tests
NFR-6 NeatFreak 三层  0 相对日期 ：2026-09-27 绝对日期（禁止 next/约/几天/很快 相对描述）
## Constraints
### Technical
CT-1 零新 Maven 依赖（0，p2p-ws-sdk 五子项目 0 改 0 改 P2PCommand 枚举（0 改
CT-2 6 Facade public  0  0  signature，严格 0 新增（0（NG-2 硬红
CT-3 仅用 DsHashMap/DsFixedBucketStore/BloomFilter64 三 现有 3 个集合 0 新通用集合 类 新 Java SDK 不新（
CT-4 行/列 ：与 P13 复用 P11 双 0 写（P13 区 Bloom 0 改
### Business
CB-1 交付：NeatFreak 三层：AGENTS/project_memory/roadmap 三层  2026-09-27 绝对日期 0 相对日期
### Dependencies
CD-1：P10 Registry（P10 写 RegistryMetaPaths 必须已写（P13 Bloom（P11 列存（/orm/data 三棵 不
## Assumptions
A-1：String 字典序 range（列 字节 基于 UTF-8  字典序范围（不考虑 locale 不做 ICU  （NG-6 ）
A-2：范围  LIMIT N ≤ 100,000 rows（100k 行走内存 sort ；性能降级 （（（NG-2
A-3：LONG 索引列 8 无符号  范围（Long.compare 即字典序一致（（（DsEqIndexStore LONG 已一致

## Open Questions
Q-1 [待用户确认 String 比较方式： 字典序（Java UTF-8 bytes（默认 A-1字典序 vs 自然语言排序（P17 再做）→ 本阶段默认字典序
Q-2 ：降序返回 LIMIT 下推是否支持（默认 A-2 默认降序 下推支持？本阶段 默认 支持

## Acceptance Criteria AC （rule 或 rubric 二选一，每 AC 明确类型
### AC-1：RegistryMetaPaths Range 区 1 处声明 0 外泄
- **Type**：rule
- **Given**：代码仓库根，代码已编译 6 Facade
- **When**：执行 `grep -rn "columnRangeIndex\|rowRange" p2p-db/src/main/java | wc -l` 命令
- **Then**：RegistryMetaPaths 声明命中 0 外泄 = 0 其它处  0 其它类 引用（除测试类外）
- **Pass Condition**：RegistryMetaPaths 中 6+ 组 range 常量存在 定义命中  0 外泄命中 1 处且 grep 仅 RegistryMetaPaths 命中 1 1 命中；其他 0 改
- **Evidence**：Shell 命令输出 + AGENTS.md Invariant 锚点 grep 结果 0F0E
### AC-2：行存 LONG findByRange 1e6 行 P99 ≤50ms
- **Type**：rubric
- **Dimension**：Performance Range  性能
- **Scale**：0-5
- **Anchors**： 1 = 未优化 O(N) baseline 700ms(P13 原实现)；3 = 400ms ；5 = P99≤50ms（HARD ≤50ms SOFT ≤80ms）
- **Pass Threshold**：≥4
- **Evidence**：P16_RangePerfRubricTest U1 输出 N=1e6 P99 毫秒数
### AC-3：列存 EqIndexStore(String 字典序范围 2 TR 0F0E 全部通过
- **Type**：rule
- **Given**：Columnar EqIndexStore（ （6 Facade 0 新 0 新  public  方法 （NG-2
- **When**：在 写 N=100,000 行 UTF-8 bytes 值 aaa~zzz 字典序写 2000 个 between 条件
- **Then**：findRowIdsBetween 返回 行 rowId  按字典序升序 / 降序 各 1000 组 结果 100% 与内存 sort 完全 equals（Arrays.equals）
- **Pass Condition**：P16_RangeColumnarStoreTest t01 BETWEEN 字典序，t02 字典序 GT/LT/BETWEEN 排序  2/2 PASS Tests run Failures 0 Errors 0 Skipped 0
- **Evidence**：Surefire Report 输出 Tests run: 2 0F0E
### AC-4：GenericManager findRangeByWrapper(QueryWrapper + BETWEEN + orderByAsc + LIMIT(0,100) 全链路 range  narrowing 命中行数 P99 ≤ 80ms（1,000,000 行
- **Type**：rubric
- **Dimension**：全链路下推正确性+性能
- **Scale** ：0-5
- **Anchors** ：1 = 全表扫 + sort N 行 O(N²) baseline 2,000ms；3 = P99 500ms  narrowing 5 = P99≤80ms
- **Pass Threshold** ≥ ：≥4
- **Evidence**：P16_RangePerfRubricTest U2 输出 mean/p95 mean
### AC-5：6 Facade  public 方法 0 新 signature （NG-2 B6 真基线（48,24,3,8,1,7 ±4
- **Type**：rule
- **Given** P15 Facade  当前实际 真基线（P15 实际实测 public 方法数 ：DsDatabaseLocal=48、ColumnarStore=24、TableMetaStore=3、ColumnRegistry=8、RowIdSeq=1、EqIndexMetaStore=7
- **When**：P16 完成 全交付
- **Then** P16_RaftFacadeHookRealTest 模式：P16 Facade public 方法数 6 类（DsDatabaseLocal/ColumnarStore/TableMetaStore/ColumnRegistry/RowIdSequenceStore/EqIndexMetaStore 每类 0 新 public  ±4 与 P15 真基线 ±4  （(实际 ±4）
- **Pass Condition**：perClassPublicMethodCount actual ∈ [真 baseline-4, baseline +4] 区间内 0F0E 0 FAIL 命中 zero-raft-server 0 字段（NG-2 0 字符改严格 NG-2 严格 ： 严格合规
- **Evidence** P16_RangeFacadeHookRealTest t03 B6 断言结果 + stdout 输出 [WARN tasks baseline outdated] 0 条（若 0 条说明 baseline 匹配
### AC-6：Bloom 复用 P13 零 IO 守卫 range 命中 先 Bloom 后 ： 99,999 miss 行 → Bloom mightContain 0 IO narrowing DsHashMap 构造 ≤2000 次 （P13 FPP≤0.02
- **Type**：rule
- **Given** P16 Range 列存 Bloom 开启 enableBloomForColumn(expectedInsert=100k, fpp=0.02；99,999 个非候选 BETWEEN 值（range 范围外）和 1 候选 1 个 range 命中
- **When**：P16_RangeBloomGatekeeperTest 运行
- **Then**：AtomicInteger dsHashMap new DsHashMap 构造次数 ≤ 2000 次
- **Pass Condition**：25 assertions c12 Range Bloom 断言 ≤ 2000（FPP 上限 0.02）
- **Evidence**：Surefire 断言 25/25 assertions 全部 0F0E（A6+B6+C5+D5+E3=25 PASS
### AC-7：JV 回归 合跑 ≥ 160 tests 0F0E
- **Type**：rule
- **Given**：P10~P16 全部 测试
- **When** ：执行：mvn -pl p2p-db test "-Dtest=P10_*,P11_*,P12_*,P13_*,P14_*,P15_*,P16_*" "-DfailIfNoTests=false"
- **Then** Tests run ≥160 Failures 0 Errors：0，Errors 0  Skipped 0 ，BUILD SUCCESS
- **Pass Condition**：exit code 0 且 tests≥160
- **Evidence**：Surefire Report 汇总行输出
### AC-8：NeatFreak 三层 0 相对日期  绝对日期  2026-09-27
- **Type** ：rule
- **Given**： 仓库根目录（AGENTS/project_memory/roadmap
- **When**：grep -En "约|next|几天|很快|随后|大概|P16.*In Progress"
- **Then**：0 命中 语义  0  条 命中 全部 为 2026-09-27 绝对日期
- **Pass Condition** grep -En 命中 0  语义命中
- **Evidence**：grep 输出为空

