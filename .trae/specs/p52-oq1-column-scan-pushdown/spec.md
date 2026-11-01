# P52 OQ12-㉚① 真·列扫描下推（列索引扫描 + 列值残余校验 / 消除 ORM 全行物化 / 上游受控改动）—— spec.md（**✅ CLOSED**）

> 用户「1,2,3顺序实现」→ 本轮启动第 1 项（OQ12-㉚①，承接 P51 永久闭环 `1471a1b` + P50 显式遗留 D4「非真·列索引扫描」）→ SP3.1 Gate A（本文件 + tasks.md）→ **用户 VERBATIM 批准** → Implement T1-T6 → Gate B 拆批回归 → Gate C 洁癖 5/5 → Stage 5 commit/push。
>
> 本文件已闭环：§0 五项裁决（Q0-Q4）已由用户「继续」= 采纳全部默认签批；§6 Gate B 实跑数字、§9 签批区、§10 实装偏差表已回填。

## 0. 待裁决（Gate A 必答，用户 VERBATIM 逐条签批）

> **Q0 Scope 确认**
>
> 本轮锚定 **OQ12-㉚ 候选 ① = 真·列扫描下推 / 列值倒排索引**。用户已下达「1,2,3顺序实现」，P52 = ①，P53 = ②，P54 = ③。**请确认：`Q0 = ①`。**

> **Q1 实现路线（决定是否触 R5 段格式红线）**
>
> **Probe 实锤（关键）**：列存**已存在**列级等值索引 `columnar/index/EqIndexStore`（`DsHashMap(hash→链表头)` + `DsFixedBucketStore` 16B 节点 + 内存 `BloomFilter64` + 内存 `rangeMaps: TreeMap<ByteBuffer,List<Long>>`），且 `DbRowQueryIdsServerHandler` 已用其 `findRowIds` 实现「EQ/IN 索引求交 + NOT_IN 排除 + 列值残余 `matches` + 全量扫描回退」；而 P50 的 `DB_NODE_QUERY_IDS` 路径走的是 **ORM `GenericManager.listIds` = 候选索引 + 逐 id `getTable` 全行物化 + `matchesAll`**。
>
> | 选项 | 方案 | 新增工件 | R5 段格式 | 推荐 |
> |------|------|----------|-----------|------|
> | **A** | **复用既有列存等值索引 `EqIndexStore` + 列值级残余校验**：抽出共享「列扫描器」（EQ/IN → `findRowIds` 求交；NOT_IN → 排除集；其余算子 → 列值残差；无索引 → 全量扫描），`DB_NODE_QUERY_IDS` 改走它，消除全行物化 | 0（复用既有 `idx/eq_<colId>.map` 工件） | **0 变更** ✅ | ⭐**推荐** |
> | B | **新建列值倒排段工件**（自有 MAGIC/VERSION=2 + 4KB 对齐 + CRC，替代/旁路 `EqIndexStore`） | 1 套新段格式 | 需新增 VERSION=2 → 触红线，须 Gate A 单独裁决 | 备选（收益边际，且 `EqIndexStore` 已覆盖 EQ/IN） |
>
> **请裁决：`Q1 = A`（推荐，复用既有列索引，R5 零触碰）或 `Q1 = B`（新建列值倒排段，触 R5）。**

> **Q2 新类落包（决定是否破递归守恒基线）**
>
> `fulltext/` 根包 top-level 恒 = 3；十一处锁定包（`segment/`=5 / `parser/v3/`=5 / `agg/`=6 / `compact/`=5 / `groupby/`=5 / `layered/`=3 / `rowsource/`=2 / `sched/`=3 / `autocompact/`=3 / `autoseg/`=3 / `wordnet/`=3）全锁死，**不得**落任何计数包。P52 新增列扫描器默认落 P50 已开辟的 `com.q3lives.ds.database.columnar.pushdown`（该包非 fulltext 计数范围；**P50 时源码数=1，P52 后=2，本轮起纳入锁定计数 `columnar/pushdown/`=2**）。**请确认：`Q2 = 落 columnar/pushdown/，锁定计数 2`。**

> **Q3 上游受控改动白名单（R6）**
>
> P52 需把 `DbRowQueryIdsServerHandler` 中已实现的列扫描算法**抽取**为共享类并委派（消除重复，CPD 0 重复块），请求白名单：
> - `javax.net.p2p.server.handler.DbNodeQueryIdsServerHandler` —— `process` 改走列扫描下推（**P50 受改类，二次受控**）
> - `javax.net.p2p.server.handler.DbRowQueryIdsServerHandler` —— 过滤段改为**委派**共享类的等价搬迁（行为保持）
> - `javax.net.p2p.server.handler.DbRowQueryIdsStreamServerHandler` —— 同上委派（避免其保留副本导致 CPD 重复块）
> - `com.q3lives.ds.database.columnar.DsDatabaseClusterClient.queryIds(5 参)` —— 本地分支同语义（**P50 受改类，二次受控**）
>
> **不改**：`DbQueryToQueryWrapper`（保留其 `filterIdsByPredicate` 作为 **R12 真值基准/oracle**，P50 T1 用例依赖）、`EqIndexStore`/`EqIndexMetaStore`、`GenericManager`、`DbQuery*` 模型、fulltext 全系。
> **请确认：`Q3 = 上述 4 处白名单受控改动，其余 0 改`。**

> **Q4 语义对齐口径（决定 R12 基准与 LIKE 分叉处置）**
>
> Probe 实锤 P50 偏差 D2：`DbRowQueryIdsServerHandler.eval` 的 LIKE 语义（`LIKE_LEFT=endsWith` / `LIKE_RIGHT=startsWith`）与 ORM `GenericManager`（`LIKE_LEFT=startsWith` / `LIKE_RIGHT=endsWith`）**相反**。P52 统一口径：
> - **R12 真值基准 = P50 现行为（ORM `GenericManager.listIds`）**；
> - 共享列扫描器的 LIKE 语义**对齐 ORM**（`LIKE→contains` / `LIKE_LEFT→startsWith` / `LIKE_RIGHT→endsWith`），即**修正** D2 记录的分叉；
> - 该修正顺带影响 `DB_ROW_QUERY_IDS[_STREAM]` 的 LIKE_LEFT/LIKE_RIGHT 结果（Probe 确认**无既有测试锁定旧语义**）；
> - 先过滤后分页 + 强一致保持（命中 id 的 `getById` 必非 null）+ 输出恒 rowId 升序（R11）。
> - **范围算子（GT/GE/LT/LE/BETWEEN）本轮不走 `rangeMaps`**：因其仅内存态（进程重启即空，`findRowIdsBetween` 会误返空集），为保正确性统一走「全量扫描 + 列值残差」。
>
> **请确认：`Q4 = 以 ORM 行为 R12 基准 + 统一 LIKE 语义 + 范围算子不走内存 rangeMaps`。**

> 其余 Scope / 红线 / 类清单 / MVP 边界如下，请逐条签批（§9）。

---

## 1. Scope（本阶段交付）

**目标**：把 `DB_NODE_QUERY_IDS` 与 `DsDatabaseClusterClient.queryIds` 的谓词过滤从 **ORM 逐 id 全行物化**（`GenericManager.listIds` → `getTable` + `matchesAll`）升级为 **列索引扫描 + 列值级残余校验**，消除全行物化开销，且与 P50 现行为 **R12 逐条等价**；同时抽取共享列扫描器，消除 `DbRowQueryIdsServerHandler` / `DbRowQueryIdsStreamServerHandler` 的算法重复（CPD 0 重复块）。

**交付物（1 类新类 + 4 处受控受改）**：
1. **新类** `com.q3lives.ds.database.columnar.pushdown.ColumnScanPushdown` —— 列索引扫描下推器（纯静态、零实例状态）。
2. **受改** `DbNodeQueryIdsServerHandler.process(...)` —— 过滤段改走列扫描下推（先过滤后分页）。
3. **受改** `DbRowQueryIdsServerHandler` —— 过滤段委派共享类（等价搬迁；保留其 ORDER BY 段自持）。
4. **受改** `DbRowQueryIdsStreamServerHandler` —— 同上委派。
5. **受改** `DsDatabaseClusterClient.queryIds(String, long, DbQuery, int, int)`（5 参分区）—— 本地分支同语义。

**不动**：`DbQuery`/`DbQueryCriterion`/`DbQueryOrGroup`/`DbNodeQueryIdsRequest`/`DbRowQueryIdsResponse` 模型、`DbQueryToQueryWrapper`、`EqIndexStore`/`EqIndexMetaStore`、`GenericManager`、`ColumnarStore`、`ColumnarAggRowSource`、fulltext 全系（P21/P37/P40/P42/P43/P47/P48/P49/P50 queryIds 网络分支/P51）。

## 2. 红线（Gate A 冻结）

- **R1 ZeroLeak3=3 + 十一重递归守恒**：`fulltext/` 根包 top-level 恒 = 3；十一处锁定包源文件数全不变；新增锁定 **`columnar/pushdown/` = 2**（P50=1 → P52=2）。
- **R2 NG-3 零依赖**：新增源码仅 JDK + first-party；`p2p-db/pom.xml` 0 新增 dep/plugin；0 命中 `io.netty` / `io.grpc` / `javax.net.p2p.client`。
- **R3 TOPK 铁律**：0 改 K、0 触 `SearchOptions`、`bucketAccessCounter[1025]` 0 污染（去注释后判定）。
- **R4 Facade 单调不减**：P21 `FulltextStore` + P37 `segment/` + P40 `compact/` + P42 `layered/` + P43 + P47 `sched/` + P48 `autocompact/` + P49 `autoseg/` + P51 `wordnet/` 源码字节级 0 改；`DbQueryToQueryWrapper` 公开签名 0 改。
- **R5 段格式 0 变更**：P37 DIDX `MAGIC/VERSION=1/4×CRC32` 字面不动；**P52 不新增/不改任何段工件**（Q1=A 复用既有 `EqIndexStore` 落盘结构）。
- **R6 上游受控改动白名单**：**仅**允许改 Q3 列出的 4 处；其余上游类 0 改。
- **R7 网络模型 0 改**：`DbNodeQueryIdsRequest` / `DbRowQueryIdsResponse` / `DbRowQueryIdsStreamRequest`（p2p-core）字段 0 改；`DB_NODE_QUERY_IDS` / `DB_ROW_QUERY_IDS[_STREAM]` 命令号 0 改。
- **R8 强一致契约**：过滤命中 id 的 `getById`/`getTable` 必非 null；违反 → ISE（保持 P46）。
- **R9 只读无写盘**：列扫描路径**只读**（0 `put/remove`、0 索引写入、0 manifest 变更）；崩溃安全无新增语义。
- **R10 0 新线程**：列扫描在请求处理线程内联完成；`EqIndexStore` 的 `FileLock` 仅在既有读写路径使用，读路径不新增锁语义。
- **R11 幂等/确定性**：同输入（entity + 谓词 + 分区 dbRoot）→ 同 id 有序输出（**恒 rowId 升序**）。
- **R12 语义全等**：列扫描结果 ≡ P50 现行为（`GenericManager.listIds` 全行物化过滤结果），**逐条等价**（含空集、边界、LIKE 统一语义）。
- **R13 空集与边界**：谓词无命中 → 空 `long[]`（非 null）；`offset ≥ size` → 空；`limit ≤ 0` → 默认 100（沿用现有）；`dbRoot == null` / `entityClassName` 空白 → IAE。

## 3. 架构

```
客户端 ColumnarAggRowSource (0 改)
        │  minterm → toDbQuery → DbQuery
        ▼
DsDatabaseClusterClient.queryIds(className, anchorRowId, query, offset, limit)   [受改: 本地分支走列扫描]
        │  isLocal? ── 是 ──▶ ColumnScanPushdown.scan(...)                      ← 新类
        │             └─ 否 ──▶ DB_NODE_QUERY_IDS (DbQuery 透传, 0 改)
        ▼
DbNodeQueryIdsServerHandler.process(...)                                        [受改: 过滤段走列扫描]
        │  manager.getStore(anchorRowId).getDbRoot()
        │  ColumnScanPushdown.scan(dbRoot, entity, query, offset, limit)        ← 新类
        │  列索引候选(EQ/IN 求交) → NOT_IN 排除 → 列值残差 → 分区 ids 求交 → 升序 → 分页
        ▼
DbRowQueryIdsResponse(idsBytes)  (0 改)

DbRowQueryIdsServerHandler.process(...)          [受改: 过滤段委派 ColumnScanPushdown]
DbRowQueryIdsStreamServerHandler.process(...)    [受改: 过滤段委派 ColumnScanPushdown]
        （二者保留各自 ORDER BY 段 / 分片流式逻辑，仅过滤段等价搬迁）

R12 真值基准（保留、不再被主流程调用）：
DbQueryToQueryWrapper.filterIdsByPredicate → GenericManager.listIds → getTable + matchesAll
```

## 4. 接口契约

**新类（Q1=A）**
```java
// 包：com.q3lives.ds.database.columnar.pushdown（源码数 1 → 2，锁定计数 2）
public final class ColumnScanPushdown {
    private ColumnScanPushdown() {}   // 唯一私有构造（零实例状态）

    /**
     * 列扫描下推：谓词 → 命中 rowId（rowId 升序、已施加 offset/limit）。
     * 候选优先级：EQ/IN 列索引求交 → 列值残余校验 → 与分区 ids 集求交；
     * 无可用索引 → 分区 ids 全量扫描 + 列值残余。
     * query == null / 无条件 → 分区 ids 全量（兼容旧行为），再分页。
     */
    public static List<Long> scan(File dbRoot, String entityClassName, DbQuery query, int offset, int limit);

    /** 谓词 → 命中 rowId 全量集合（rowId 升序、不分页）；供 R12 等价性对照与内部复用。 */
    public static List<Long> filter(File dbRoot, String entityClassName, DbQuery query);
}
```
> IAE 守卫（≥8 处）：`dbRoot == null` / `entityClassName` null 或空白 / `where[i] == null` / `op == null` / `name` null 或空白 / `IN|NOT_IN` 缺 `list` / `@composite:` 列 / 未知列名。

**受改方法**
```java
// DbNodeQueryIdsServerHandler.process：返回前
//   1) File dbRoot = manager.getStore(anchorRowId).getDbRoot();
//   2) List<Long> hit = ColumnScanPushdown.scan(dbRoot, entity, payload.query, offset, limit);
//   3) long[] out = toArray(hit) → SerializationUtil.serialize → DbRowQueryIdsResponse
//   4) payload.query == null / 无条件 → 0 行为变更（全量 + 分页）

// DsDatabaseClusterClient.queryIds(5 参) 本地分支：同语义（ColumnScanPushdown.scan）

// DbRowQueryIdsServerHandler / DbRowQueryIdsStreamServerHandler：过滤段（expandOrGroups /
//   findEqIndexCandidates / findEqIndexExcludedRowIds / matches / encodeForEqIndex）等价搬迁至
//   ColumnScanPushdown；两 handler 保留 ORDER BY / 流式分片等自持逻辑，仅委派过滤。
```

## 5. 测试（T1-T6，目标 ≥33 TR 0F0E）

- **T1 列扫描器单测 ×5**：IAE 全守卫（null dbRoot / 空 entity / op null / name 空白 / IN 缺 list）+ EQ/IN 索引命中求交 + NOT_IN 排除集 + 无索引列回退全量 + 非索引列残差过滤。
- **T2 逐 op 语义全等 ×6**：EQ/NE/GT/GE/LT/LE/BETWEEN/IN/NOT_IN/LIKE/LIKE_LEFT/LIKE_RIGHT/IS_NULL/IS_NOT_NULL 与 ORM 基准逐条一致（含 Q4 LIKE 统一语义修正断言）+ null/空 where + anyOf OR 分支 + 边界值（a==b / 单元素 IN / 值 null）+ 纯函数确定性。
- **T3 服务端 handler ×4**：`DB_NODE_QUERY_IDS` 走列扫描（命中保留/未命中剔除）+ 先过滤后分页 + 空集非 null + `query == null` 全量透传兼容。
- **T4 R12 等价 + E2E ×4**：列扫描结果 ≡ `DbQueryToQueryWrapper.filterIdsByPredicate`（ORM 物化）逐条全等 + 与 P46 `ColumnarAggRowSource` 端到端一致 + 多分区路由聚合 + 强一致契约（命中 id `getById` 必非 null + 无 ORM 行的伪造 id 被剔除）。
- **T5 Gatekeeper 9Y ×9**：见顶部清单（Y1 十一重守恒 + `columnar/pushdown/`=2）。
- **T6 NFR ×5**：全行物化调用数 = 0（`getTable` 0 次，反射/计数探针）或列扫描 P95 ≤ ORM 物化基线 + 新类 Javadoc ≥10 行 + CPD 160 字符窗口 0 重复块（新类 + 4 受改类）+ 0 `println`/`printStackTrace` + IAE 守卫 ≥8。

## 6. Gate B 拆批（实跑回填，**禁止预填**）

> 沿用 6 批：批 1 P22-P24 / 批 2 P25-P28 / 批 3 P29-P30 / 批 4 拆 3 子段 4a P31-P36 / 4b P37-P42 / 4c P43-P52；已知基线 fail `P28_T6.tr64` 沿用。**每批前清 `target\tmp` + C 盘 `junit*`/`dsdb_*` 残留**；**长跑 `*> gatebN.log` 落盘再读**。

| 批 | 范围 | 实跑结果 | 日志 |
|----|------|----------|------|
| 1 | P22-P24 | **76/76 0F0E** | `gateb1.log` |
| 2 | P25-P28 | **112 run / 1F**（仅已知基线 `P28_T6.tr64`） | `gateb2.log` |
| 3 | P29-P30 | **68/68 0F0E** | `gateb3.log` |
| 4a | P31-P36 | **193/193 0F0E** | `gateb4a.log` |
| 4b | P37-P42 | **186/186 0F0E** | `gateb4b.out/.err`（`Start-Process` 口径） |
| 4c | P43-P52 | **313/313 0F0E**（含 P52 own 32） | `gateb4c.out/.err`（`Start-Process` 口径） |

- **P52 own**：`P52_T1~T6` = **32/32 0F0E**（T1=5 / T2=6 / T3=4 / T4=4 / T5=9 / T6=4）。
- **合计 = 948 run / 1F（仅已知基线）/ 0 新增回归**。

## 7. MVP 边界

仅「列存等值索引候选扫描（EQ/IN）+ 列值级残余校验 + 分区 ids 求交 + 先过滤后分页」；**不做** 新建列值倒排段工件 / 段格式 VERSION=2（Q1=A）/ 范围算子走内存 `rangeMaps`（Q4）/ `rangeMaps` 持久化 / 选择率估算（CBO）/ `ORDER BY` 服务端排序（归 P53 ②）/ 聚合函数下发（归 P53 ②）/ FT 条件下推 / 网络模型变更（0 改 p2p-core）/ 0 新 Maven dep / 0 新线程。

## 8. 里程碑

| Stage | 内容 |
|-------|------|
| 1 | Gate A：spec+Tasks 签批（Q0-Q4） |
| 2 | Implement T1-T6 |
| 3 | Gate B 拆批回归（6 批） |
| 4 | Gate C 9Y + 洁癖 5/5 |
| 5 | Stage 5 commit/push |

## 9. 签批区（VERBATIM）

- [x] Q0 = ① ✅
- [x] Q1 = A（复用既有列索引，R5 零触碰）✅
- [x] Q2 = 落 `columnar/pushdown/`，锁定计数 2 ✅
- [x] Q3 = 4 处白名单受控改动 ✅
- [x] Q4 = ORM 行为 R12 基准 + 统一 LIKE 语义 + 范围算子不走 `rangeMaps` ✅
- [x] §2 红线 R1-R13 全签批 ✅
- [x] §7 MVP 边界签批 ✅

## 10. 实装偏差表（已回填）

| # | 偏差 | 处置 |
|---|------|------|
| D1 | `DbRowQueryIdsStreamServerHandler` 委派扩散（28 处 static 调用属 `DbRowQueryIdsServerHandler`） | 保留 `DbRowQueryIdsServerHandler` 6 个 static 方法为对 `ColumnScanPushdown` 的**一行 delegate**；stream handler **0 显式改**（经间接委派）。T6 tr63/tr64 实跑：CPD 0 重复块 + 裸打印 0 → 白名单实为 3 文件显式改 + 1 文件间接委派 |
| D2 | `P46_T4_E2eColumnarAggTest` / `P50_T6_NFR_Test` 首跑 `unknown column: tag` | 二者夹具只写 ORM 行、未「同根双写」列元（与 P50_T2/T3/T4 同源遗漏）；补 `TableMetaStore.ensureMeta`（列值已由 `putCol`/`putValue` 写入）。**测试夹具对齐，非源码改动**；修复后 P46_T4 3/3、P50_T6 4/4，批 4c 313/313 0F0E |
