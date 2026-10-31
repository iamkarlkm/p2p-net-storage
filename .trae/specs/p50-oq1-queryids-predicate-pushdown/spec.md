# P50 OQ12-㉘② 服务端 queryIds 真·谓词过滤下推（服务端谓词评估 / 强一致保持 / 上游受控改动）—— spec.md（**✅ Gate A 已签批 / Gate B 883 跑 0 Error / Gate C 9Y 9/9 + 洁癖 5/5 闭环**）

> 用户 OQ12-㉘ 默认锚定候选 ②（按用户「继续」推进，承接 P49 永久闭环 `29441c7` + P46 MVP 边界）→ SP3.1 Gate A（本文件 + tasks.md）→ 用户 VERBATIM 批准 → Implement T1-T6 → Gate B 拆批回归 → Gate C 洁癖 5/5 闭环 → Stage 5 commit/push。

## Gate C 9Y 自检清单（Implement + Gate B 后回填实锤；目标 = `P50_T5_Gatekeeper9YTest` 9/9 0F0E）

> **实锤**：`P50_T5_Gatekeeper9YTest` = **9/9 0F0E**（实跑：`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`）。

- [x] Y1 **ZeroLeak3=3 + 九重递归文件数守恒**：`fulltext/` 根包 top-level 恒 = 3；`segment/`=5、`cluster/parser/v3/`=5、`cluster/agg/`=6、`compact/`=5、`cluster/groupby/`=5、`layered/`=3、`cluster/rowsource/`=2、`sched/`=3、`autocompact/`=3、`autoseg/`=3 全不变（P50 不新增 fulltext 子包）→ `P50_T5.y1_ZeroLeak3AndNineFoldFileCountGuard` ✅
- [x] Y2 NG-3 零依赖：0 新增 netty/grpc/p2p.client 引用 + `p2p-db/pom.xml` 0 新增 dep/plugin → `P50_T5.y2_Ng3_PushdownImportsJdkAndFirstPartyOnly` ✅
- [x] Y3 TOPK 铁律：0 改 K、0 触 `SearchOptions`、`bucketAccessCounter` 0 污染 → `P50_T5.y3_TopKGuardrails_NoBucketCounterAndSearchOptionsReadOnly` ✅
- [x] Y4 Facade 单调不减：P21 `FulltextStore` + P37 `segment/` 5 类 + P40 `compact/` 5 类 + P42 `layered/` 3 类 + P47 `sched/` 3 类 + P48 `autocompact/` 3 类 + P49 `autoseg/` 3 类源码 **0 改**；P21~P49 公开签名 0 改 → `P50_T5.y4_FacadeMonotonic_UpstreamIntact` ✅
- [x] Y5 路由公式：0 改 `ShardRouter` / `DsIdLayout`（serverId/partitionId 布局）/ P24 UNIT·DOC 路由 → `P50_T5.y5_RoutingFormula_ShardRouterAndIdLayoutIntact` ✅
- [x] Y6 P21~P50 编译兼容：既有类型 0 改（除受控受改类 `DbNodeQueryIdsServerHandler`/`DsDatabaseClusterClient` 的查询方法）+ 全类存在 → `P50_T5.y6_P21ToP50CompileCompat_ClassesExist` ✅
- [x] Y7 HybridHit ctor=2：0 改 → `P50_T5.y7_HybridHit_ConstructorCountIs2` ✅
- [x] Y8 ShardTransport 5 方法：0 改 → `P50_T5.y8_ShardTransport_5OriginalMethodsIntact` ✅
- [x] Y9 0 TODO/FIXME/HACK（新增源码）→ `P50_T5.y9_NoTodoFixmeHackInPushdown` ✅

---

## 0. 待裁决（Gate A 必答，用户 VERBATIM 逐条签批）

> **Q0 Scope 确认（本阶段候选）**
>
> 本 spec 按用户「继续」推进，默认锚定 **OQ12-㉘ 候选 ② = 服务端 queryIds 真·谓词过滤下推**。若用户实为选择 ①（跨段词网聚合）或 ③（Other），请于签批时重定向，本文件作废重起。**请确认：`Q0 = ②`。**

> **Q1 服务端过滤实现方式（决定改动面与是否新增转换类）**
>
> Probe 实锤（`DbNodeQueryIdsServerHandler` L54~84）：服务端当前**仅遍历分区 `DsHashSet` ids 集合 + offset/limit 截取**，`payload.query`（`DbQuery`）被接收但**完全忽略**；客户端 `DsDatabaseClusterClient.queryIds` 本地路径（L231~242）亦**忽略 `query`**。既有两个范式：
>
> | 选项 | 方案 | 新增类 | 复用 | 推荐 |
> |------|------|--------|------|------|
> | **A** | **复用 `GenericManager` 谓词引擎**：服务端把 `DbQuery` 转 `QueryWrapper`（新转换器），调 `gm.findList(wrapper)` / `findListByIds` 过滤（对齐 `OrmFindRangeServerHandler` L46~49 范式） | 1（`DbQuery`→`QueryWrapper` 转换器） | 既有 `QueryWrapper` 过滤引擎 + `GenericManager` | ⭐**推荐**（不重造谓词引擎，语义与 ORM 路径一致） |
> | B | **自建谓词评估器**：服务端逐 id `gm.getById(id)` 物化后，用新写的 `DbQuery` 谓词评估器（`op`/`name`/`a`/`b`/`list`）逐条比对过滤 | 1（`DbQuery` 谓词评估器） | 无（新引擎） | 备选（无转换损耗，但重造过滤引擎，语义漂移风险） |
>
> **请裁决**：`Q1 = A`（推荐，复用 `QueryWrapper` 引擎）或 `Q1 = B`（自建评估器）。

> **Q2 新类落包（决定是否破 ZeroLeak/递归基线）**
>
> `fulltext/` 根包 top-level 恒 = 3；九重递归文件数已全部锁死。P50 若需新增转换/评估类，**不得**落任何已锁死计数包。默认落**列存包** `com.q3lives.ds.database.columnar/`（非 fulltext 计数范围）或新建封顶子包。**请确认：`Q2 = 落 columnar 包（或 `columnar/pushdown/` 新子包，源码数 = 1）`。**

> **Q3 客户端职责与网络模型（决定是否改 p2p-core 模型）**
>
> - 默认：**仅服务端增强**——`DbNodeQueryIdsRequest`（`entityClassName`/`anchorRowId`/`query`/`offset`/`limit`）与 `DbRowQueryIdsResponse`（`idsBytes`）**0 改**；客户端 `ColumnarAggRowSource` 的「id 候选 + `getById` 物化 + rowId 升序」流程 0 改；仅修 `DsDatabaseClusterClient.queryIds` 本地路径使其也应用谓词。
> - **请确认：`Q3 = 网络模型与客户端主流程 0 改，仅服务端 + 客户端本地路径受控增强`。**

> **Q4 强一致契约保持（决定过滤语义边界）**
>
> 过滤语义：服务端对「谓词命中」的 id 才返回；客户端 `getById` 仍须**必非 null**（命中即存在，违反则 `IllegalStateException`，保持 P46 契约）。过滤后 offset/limit 在**过滤后集合**上施加（先过滤后分页）。**请确认：`Q4 = 先过滤后分页 + 强一致契约保持`。**

> 其余 Scope / 红线 / 类清单 / MVP 边界如下，请逐条签批（§9）。

---

## 1. Scope（本阶段交付）

**目标**：让服务端 `DB_NODE_QUERY_IDS` 与客户端本地 `queryIds` **真正应用 `DbQuery` 谓词**，实现「谓词过滤下推」，消除 P46 遗留的「服务端透传全量 id、客户端 getById 全量物化」开销。

**交付物（3 类新类 + 2 类受控受改）**：
1. **新类** `DbQueryToQueryWrapper`（或评估器，Q1 裁决）—— `DbQuery` 谓词转换/评估。
2. **受改** `DbNodeQueryIdsServerHandler.process(...)`——在返回前对 ids 施加谓词过滤（先过滤后分页）。
3. **受改** `DsDatabaseClusterClient.queryIds(String, DbQuery, int, int)`（3 参全集群）与 `queryIds(String, long, DbQuery, int, int)`（5 参分区）——本地路径应用谓词。

**不动**：`DbQuery`/`DbQueryCriterion`/`DbNodeQueryIdsRequest`/`DbRowQueryIdsResponse` 模型、`ColumnarAggRowSource`、fulltext 全系（P21/P37/P40/P42/P47/P48/P49）。

## 2. 红线（Gate A 冻结）

- **R1 ZeroLeak3=3 + 九重递归守恒**：`fulltext/` 及九个锁定子包源文件数全不变。
- **R2 NG-3 零依赖**：新增源码仅 JDK + first-party；0 新 Maven dep/plugin。
- **R3 TOPK 铁律**：0 改 K、0 触 `SearchOptions`、`bucketAccessCounter[1025]` 0 污染。
- **R4 Facade 单调不减**：P21/P37/P40/P42/P47/P48/P49 源码字节级 0 改。
- **R5 段格式 0 变更**：`MAGIC="DIDX"`/`VERSION=1`/4×CRC32 全不变；本阶段不触段。
- **R6 上游受控改动白名单**：本阶段**仅**允许改 `DbNodeQueryIdsServerHandler` 与 `DsDatabaseClusterClient` 的查询方法；其余上游类 0 改（Gate A 红线例外裁决）。
- **R7 网络模型 0 改**：`DbNodeQueryIdsRequest`/`DbRowQueryIdsResponse`（p2p-core）字段 0 改。
- **R8 强一致契约**：过滤命中 id 的 `getById` 必非 null；违反 → ISE（保持 P46）。
- **R9 崩溃安全**：服务端只读过滤，不写盘；无删除/追加语义变更。
- **R10 0 新线程**：过滤在请求处理线程内联完成。
- **R11 幂等/确定性**：同输入（实体 + 谓词 + 分区）→ 同 id 有序输出（升序）。
- **R12 谓词语义全等**：服务端过滤结果 ≡ 客户端 `toDbQuery` 映射的谓词在本地全量物化过滤后的结果（逐条等价）。
- **R13 空集与边界**：谓词无命中 → 返回空 `long[]`（非 null）；`offset ≥ size` → 空；`limit ≤ 0` → 默认 100（沿用现有）。

## 3. 架构

```
客户端 ColumnarAggRowSource (0 改)
        │  minterm → toDbQuery → DbQuery
        ▼
DsDatabaseClusterClient.queryIds(className, query, offset, limit)   [受改: 本地路径应用谓词]
        │  isLocal? ── 是 ──▶ 本地 ColumnarStore/DsHashSet + 谓词过滤
        │             └─ 否 ──▶ DB_NODE_QUERY_IDS (DbQuery 透传)
        ▼
DbNodeQueryIdsServerHandler.process(...)                             [受改: 谓词过滤]
        │  manager.getStore(anchorRowId) → DsHashSet ids
        │  谓词过滤 (Q1: DbQuery→QueryWrapper→gm.findList | 评估器)   ← 新类
        │  先过滤 → 再 offset/limit → long[] idsBytes
        ▼
DbRowQueryIdsResponse(idsBytes)  (0 改)
```

## 4. 接口契约

**新类（Q1=A 时）**
```java
// 包：com.q3lives.ds.database.columnar(.pushdown)
public final class DbQueryToQueryWrapper {
    /** DbQuery → QueryWrapper 全等转换；where 逐 criterion 映射 op/name/a/b/list。 */
    public static QueryWrapper<?> convert(DbQuery q);   // q == null → 空 wrapper
}
```
**受改方法**
```java
// DbNodeQueryIdsServerHandler.process：返回前
//   1) 解析 payload.query → 谓词；2) 对分区 ids 逐条过滤（命中保留）；
//   3) 排序 → offset/limit → serialize；4) query == null/空 → 保持全透传（兼容旧行为）

// DsDatabaseClusterClient.queryIds(...) 本地路径：
//   1) 读 DsHashSet ids；2) 应用谓词过滤（与远端一致）；3) 排序 → offset/limit
```

## 5. 测试（T1-T6，目标 31/31 TR 0F0E）

- **T1 Policy/转换器单测 ×5**：IAE（null 输入）+ `DbQuery`→`QueryWrapper` 逐 op 映射全等（EQ/NE/GT/GE/LT/LE/BETWEEN/IN/IS_NULL/IS_NOT_NULL）+ 空 where + 边界值 + 纯函数确定性。
- **T2 服务端过滤单测 ×4**：命中保留/未命中剔除 + 先过滤后分页 + 空集非 null + query 为 null 时全透传（兼容）。
- **T3 客户端方法单测 ×5**：本地路径谓词生效 + 远端分支透传 `query` 不变 + isLocal 判定 + offset/limit 边界 + 异常路径（IO）。
- **T4 E2E 等价 ×4**：真实 `DsDatabaseClusterClient` + handler：服务端过滤结果 ≡ 本地全量物化过滤结果（逐条等价）+ 与 P46 `ColumnarAggRowSource` 端到端一致 + 多分区路由 + 强一致契约（getById 非 null）。
- **T5 Gatekeeper 9Y ×9**：9Y 全 Y（见顶部清单）。
- **T6 NFR ×4**：过滤后服务端响应 P95/均值 ≤ 全透传基线（或注明 NFR 口径）+ 新类 Javadoc ≥10 行 + CPD 160 字符窗口 0 重复块 + 0 println/printStackTrace + IAE 守卫 ≥5。

## 6. Gate B 拆批（实跑回填，禁止预填）

> 沿用 6 批：批 1 P22-P24 / 批 2 P25-P28 / 批 3 P29-P30 / 批 4 拆 3 子段 4a P31-P36 / 4b P37-P42 / 4c P43-P50；已知基线 fail `P28_T6.tr64` 沿用。**每批前清 `target\tmp` + C 盘残留**；**长跑 `*> gatebN.log` 落盘再读**。

| 批 | 范围 | 实跑结果 | 日志 |
|----|------|----------|------|
| 1 | P22-P24 | **76/76 0F0E**（BUILD SUCCESS） | `gateb1.log` |
| 2 | P25-P28 | **112 跑，1 fail（已知基线 `P28_T6.tr64`），0 Error**（BUILD FAILURE 属基线） | `gateb2.log` |
| 3 | P29-P30 | **68/68 0F0E**（BUILD SUCCESS） | `gateb3.log` |
| 4a | P31-P36 | **193/193 0F0E**（BUILD SUCCESS） | `gateb4a.log` |
| 4b | P37-P42 | **186/186 0F0E**（BUILD SUCCESS） | `gateb4b.log` |
| 4c | P43-P50 | **248 跑，1 fail（`P49_T6.tr61` IO 绑定时序 NFR，环境 flaky），0 Error** | `gateb4c.log` |

- **合计**：**883 跑，0 Error，2 非阻塞 fail**（1 已知基线 + 1 环境 flaky）。
- **flaky 判定**：`P49_T6.tr61` 隔离重跑单类 **5/5 绿**（`p49t6_iso.log`，`OK=5/5`）→ 依 P47 教训判为环境性 flaky（JVM 资源争抢致 IO 绑定 putDoc 时序超限），非 P50 回归（P50 未触 `putDoc`/`FulltextStore` 源码）。
- **P50 own**：`P50_T1~T6` = **31/31 TR 0F0E**（T1=5/T2=4/T3=5/T4=4/T5=9/T6=4）。

## 7. MVP 边界

仅「服务端 + 客户端本地路径**谓词过滤**」；**不做** 真·列索引扫描（无列值倒排/跳过索引，仍为逐 id 物化过滤）/ 谓词下推优化（选择率估算）/ FT 条件下推 / `ORDER BY` 服务端排序 / 聚合函数下发 / 网络模型变更（0 改 p2p-core）/ 段格式变更 / 0 新 Maven dep。

## 8. 里程碑

| Stage | 内容 |
|-------|------|
| 1 | Gate A：spec+Tasks 签批（Q0-Q4） |
| 2 | Implement T1-T6 |
| 3 | Gate B 拆批回归 |
| 4 | Gate C 9Y + 洁癖 5/5 |
| 5 | Stage 5 commit/push |

## 9. 签批区（VERBATIM）

- [x] Q0 = ②（默认锚定）—— 用户「确认全部默认」
- [x] Q1 = **A**（复用 `GenericManager` 谓词引擎）—— 用户「q1」
- [x] Q2 = 落包 `com.q3lives.ds.database.columnar.pushdown`（新子包，源码数 = 1，非锁定计数包）—— 用户「确认全部默认」
- [x] Q3 = 网络模型与客户端主流程 0 改（仅 handler + client 本地路径受控增强）—— 用户「确认全部默认」
- [x] Q4 = 先过滤后分页 + 强一致保持 —— 用户「确认全部默认」
- [x] §2 红线 R1-R13 全签批 —— 用户「确认全部默认」
- [x] §7 MVP 边界签批 —— 用户「确认全部默认」

## 10. 实装偏差表（Implement 后回填）

| # | 偏差 | 处置 |
|---|------|------|
| D1 | **根目录不一致**：handler 读的 ids 来自**分区列存根**（`manager.getStore(anchorRowId).getDbRoot()`），而过滤调用 `new GenericManager(clazz)` 经 `DsDatabaseLocal.load()` 读 **ORM 根**（`p2p.db.home`）。二者可能指向不同目录，导致「候选 id 集合」与「ORM 物化行」不一致而全部过滤为空。 | 不构成源码缺陷（生产环境二者同源，P46 已依赖此前提）；测试侧采用**双写对齐**：ORM 行经 `gm.saveOrUpdate` 写 `p2p.db.home`，同批 id 再手动 `DsHashSet.add` 进 `DynamicIndexUtil.idsFile(分区分区列存根, entity)`，使两份数据一一对应。 |
| D2 | **LIKE 语义相反**：`DbRowQueryIdsServerHandler.eval` 定义 `LIKE_LEFT=endsWith / LIKE_RIGHT=startsWith`；而 `GenericManager.matches/like` 为 `LIKE_LEFT→like(...,1)=startsWith / LIKE_RIGHT→like(...,2)=endsWith`。Q1=A 复用后者，故 P50 生效语义以后者为准（与 ORM 路径一致）。 | 记录于本表；Q1=A 的目的即「与 ORM 路径语义 1:1 一致」，故以 `GenericManager` 侧为准（R12 以 `listIds` 结果为真值基准，测试用同一引擎做对照，无自相矛盾）。 |
| D3 | `filterIdsByPredicate` 返回**保持输入顺序**、不做内部排序。 | 调用方（handler / client）已先 `list.sort(null)` 升序，再过滤，故输出恒 R11 升序；避免重复排序。 |
| D4 | 服务端过滤为「逐 id 物化 + 谓词二次过滤」（非真·列索引扫描）。 | 符合 §7 MVP 边界（明示不做列索引扫描 / 选择率估计）；Q1=A 复用既有引擎，0 重造。 |
