# P53 OQ12-㉛② 服务端聚合函数下发 / ORDER BY 服务端排序（聚合下推 handler + 专用命令/模型 + 列值聚合引擎）—— spec.md（**✅ CLOSED**）

> 用户「1,2,3顺序实现」→ 第 2 项（P53 = OQ12-㉛②，承接 P52 永久闭环 `daa4b1b`）。本文档 + `tasks.md` 为 SP3.1 Gate A 交付物；**须用户 VERBATIM 批准后方可进入 Implement T1-T6**。
>
> 上游输入：P46 §1.3「不做服务端聚合 handler（方案 B → 挂后续 OQ）」= 本阶段正题；P50 §10 D2/D3/D4；P52 `ColumnScanPushdown`（列扫描下推，可复用）。
>
> 本轮已闭环：§0 五项裁决（Q0-Q4）已采纳默认签批；§6.1 Gate B 实跑数字、§9 签批区、§10 实装偏差表已回填；Gate C 9Y×9 + 洁癖 5/5。

## 0. 待裁决（Gate A 必答，用户 VERBATIM 逐条签批）

> **Q0 Scope 确认**
>
> 本轮锚定 **OQ12-㉛ 候选 ② = 服务端聚合函数下发 / ORDER BY 服务端排序**：把 P45/P46 遗留的「客户端逐 id 物化 + `AggExecutor` 本地聚合」升级为 **服务端在列存上直接算聚合**，由服务端回传「分组键 + 聚合值」，并对结果做服务端 ORDER BY。**请确认：`Q0 = ②`。**

> **Q1 实现路线（决定上游 p2p-core 改动面）**
>
> Probe 实锤：现有通道 `DB_ROW_QUERY_IDS(24032)` 只回传 `long[] ids`（`DbRowQueryIdsResponse.idsBytes`）；`DbQuery{where,anyOf,orderBy}`（`DbQuery.java:6-15`）**无 agg/groupBy/having 字段**；聚合计划 `AggSpec`（`fulltext/cluster/parser/v3/AggSpec.java`）与 `AggExecutor`（`fulltext/cluster/agg/AggExecutor.java`）**只在客户端本地、且面向实体行**（`AggRowSource<T extends DsTableAdapter>`），服务端列存无实体可物化。
>
> | 选项 | 方案 | 新增/改动 | R5 段格式 | 结论 |
> |------|------|-----------|-----------|------|
> | **A** | **扩 `DbQuery` 加 `agg/groupBy/having` 字段**，复用 `DB_ROW_QUERY_IDS` 通道回传聚合结果 | 改现有 `DbQuery` 模型（语义混杂：id 查询 vs 聚合查询）；响应结构需多态兼容 | 0 变更 | 备选（污染既有模型） |
> | **B（推荐）** | **新命令 + 专用模型**：`P2PCommand` 新增 `DB_ROW_AGG`/`R_OK_DB_ROW_AGG`；新增 `DbRowAggRequest/Response` + `DbAggFn`/`DbAggItem`；新增 handler `DbRowAggServerHandler` + 列值聚合引擎 `ColumnAggPushdown`；客户端 `DsDatabaseClusterClient.queryAgg(...)` | 新 model/command/handler/engine；`DbQuery` **0 改** | **0 变更** ✅ | ⭐**推荐** |
>
> **请裁决：`Q1 = A` 或 `Q1 = B`（推荐）。**

> **Q2 新类落包（决定递归文件数守恒基线）**
>
> 既有锁定：`fulltext/` 根包 = 3；子包 `segment/`=5 · `parser/v3/`=5 · `agg/`=6 · `compact/`=5 · `groupby/`=5 · `layered/`=3 · `rowsource/`=2 · `sched/`=3 · `autocompact/`=3 · `autoseg/`=3 · `wordnet/`=3 · **`columnar/pushdown/`=2（P52）**。`fulltext/cluster/agg/`（=6）与 `columnar/pushdown/`（=2）均**已锁死，不得追加**。
>
> 建议：
> - 聚合引擎落 **新子包** `com.q3lives.ds.database.columnar.aggpushdown` → 新增第 13 处锁定 `columnar/aggpushdown/` = **1**（仅 `ColumnAggPushdown`）；
> - 新命令/模型落 p2p-core `javax.net.p2p.model` 与 `javax.net.p2p.api.P2PCommand`（非 fulltext/columnar 计数包）；
> - handler 落既有 `javax.net.p2p.server.handler`（P50/P52 已在此受控改动）。
>
> **请确认：`Q2 = 新锁 columnar/aggpushdown/=1（第 13 处）`。**

> **Q3 ORDER BY 服务端排序口径**
>
> Probe 实锤：`DbRowQueryIdsServerHandler` **已实现**服务端 ORDER BY（`buildRowSortKey`/`buildComparator`/`RowSortKey`，L71-84/L137-206）；`DbNodeQueryIdsServerHandler` **无** ORDER BY。
>
> | 选项 | 口径 |
> |------|------|
> | **A（推荐）** | **仅对聚合结果排序**：服务端对「分组键 + 聚合别名」做 ORDER BY（支持按 group key 或聚合别名 `SUM(price)` 排序），复用 P50/P52 的可空比较/多键比较范式（**不提取共享类**，避免触碰已锁包的守卫） |
> | B | 顺带统一 `DbNodeQueryIdsServerHandler` 的 ORDER BY（补齐行/节点两路），并抽取共享 `OrderByPushdown`（破 P52 `columnar/pushdown/`=2 锁定，需同步 3 个 T5 守卫） |
>
> **请裁决：`Q3 = A`（推荐）或 `Q3 = B`。**

> **Q4 上游受控改动白名单（R3 / R6）**
>
> Probe 实锤：`P2PCommand` 存在**双份定义** —— p2p-core 数值版（`24000-24460`，空闲段 `24048-24099`）+ `p2p-ws-sdk-java` ordinal 版（含 `P2PCommandOrdinals` 映射表）。新增命令须**双向同步**。
>
> P53 白名单：
> - `javax.net.p2p.api.P2PCommand`（p2p-core）—— 新增 `DB_ROW_AGG` / `R_OK_DB_ROW_AGG` 2 枚举（占用 `24048`/`24049`）
> - `p2p-ws-sdk-java` `P2PCommand` + `p2pws.sdk.core_compat.P2PCommandOrdinals` —— 同步追加 2 条（oc ordinal 递增）
> - `com.q3lives.ds.database.columnar.DsDatabaseClusterClient` —— 新增 `queryAgg(...)` 方法（**P50/P52 受改类，三次受控**）
> - handler 注册入口（SPI `META-INF/services/javax.net.p2p.interfaces.P2PCommandHandler` **或** 既有程序化注册）—— 待 Implement 实证后择一追加
>
> **不改**：`DbQuery`/`DbQueryOp`/`DbQueryCriterion`/`DbQueryOrder`、`ColumnScanPushdown`、`EqIndexStore`、`GenericManager`、`AggExecutor`/`AggSpec`（fulltext 全系）、`DbRowQueryIdsServerHandler`/`DbNodeQueryIdsServerHandler`（除 P50/P52 既有委派外 0 改）。
> **请确认：`Q4 = 上述白名单受控改动，其余 0 改`。**

> 其余 Scope / 红线 / 类清单 / MVP 边界见 §1-§8，请逐条签批（§9）。

---

## 1. Scope（本阶段交付）

**目标**：新增「服务端聚合下推」链路 —— 客户端下发 `DbRowAggRequest`（实体名 + `DbQuery` 谓词 + 聚合项列表 + 分组键 + HAVING + ORDER BY），服务端 `DbRowAggServerHandler` 复用 P52 `ColumnScanPushdown.filter` 取命中 rowId，再在**列值**上直接计算聚合（COUNT/SUM/AVG/MIN/MAX + GROUP BY + HAVING），对结果做服务端 ORDER BY 后回传；客户端 `DsDatabaseClusterClient.queryAgg(...)` 解析结果。消除 P45/P46 的「逐 id 物化 `getById` + 客户端聚合」开销。

### 1.2 Scope（P53 MVP 内）
1. 新命令：`P2PCommand.DB_ROW_AGG=24048` / `R_OK_DB_ROW_AGG=24049`（p2p-core + p2p-ws-sdk-java 双写）。
2. 新模型（p2p-core `javax.net.p2p.model`）：`DbAggFn`（COUNT/SUM/AVG/MIN/MAX）、`DbAggItem(fn, column)`、`DbRowAggRequest{entityClassName, query, aggs, groupBy, having, orderBy, offset, limit}`、`DbRowAggResponse{payload}`。
3. 新引擎 `com.q3lives.ds.database.columnar.aggpushdown.ColumnAggPushdown`（唯一静态类，纯函数式）：输入 `(dbRoot, entityClassName, aggs, groupBy, having, orderBy)`，输出分组结果（`List<Object[]>` 或定长编码）。
4. 新 handler `javax.net.p2p.server.handler.DbRowAggServerHandler`（`implements P2PCommandHandler`），`process` 走 `ColumnScanPushdown.filter` 复用 + `ColumnAggPushdown`。
5. 客户端入口 `DsDatabaseClusterClient.queryAgg(DbRowAggRequest) → DbRowAggResponse`（**仅本地分支**先落地；全集群路由 MVP 边界见 §1.3）。
6. 服务端 ORDER BY：对聚合结果按 group key / 聚合别名排序（复用可空/多键比较范式）。

### 1.3 MVP 边界（**不做**，防 scope creep）
- **不做** 全集群聚合归并（多节点各分区结果 `merge`）—— 挂后续 OQ；P53 先打通**单分区/本地**聚合下推。
- **不做** 跨 minterm DNF 聚合（仅单 `DbQuery.where/anyOf` 谓词）—— 与 `ColumnScanPushdown.filter` 语义对齐。
- **不做** `DbQuery` 模型扩展（`agg/groupBy/having` 不塞进 `DbQuery`）。
- **不做** 段格式变更（R5）/ 0 新 Maven dep（NG-3）/ 0 新线程（R10）。
- **不做** 流式聚合（沿用 P50 非流式 handler 语义）。

---

## 2. 红线（Gate A 批准即冻结，0 越界）

- **R1 架构守恒**：`fulltext/` 根包 = 3 不变；十二重子包计数全部不变；P53 **新增第 13 处锁定** `columnar/aggpushdown/` = 1。
- **R2 上游语义 0 改**：`AggExecutor`/`AggSpec`/`AggRowSource`（fulltext `agg/`/`parser/v3/`）源码 **0 改**；`ColumnScanPushdown`/`EqIndexStore` **0 改**。
- **R3 上游受控改动**：**仅** §0-Q4 白名单（`P2PCommand`×2 份、`DsDatabaseClusterClient`、handler 注册入口）+ 新文件。
- **R4 签名冻结**：`ColumnScanPushdown.scan/filter/matches` 签名不变；`DbQuery*` 模型字段不变；`P2PCommand` 既有枚举值不变（仅追加）。
- **R5 段格式 0 变更**：复用 `MAGIC="DIDX"`/`VERSION=1`；**严禁** VERSION=2。
- **R6 结果确定性**：输出分组结果按「group key 升序 → 聚合别名声明序」稳定排序；`orderBy` 非空时按其全序（含 rowId/序号 tie-break）。
- **R7 强一致**：`ColumnScanPushdown.filter` 命中 id 与列存值一致（沿用 P52 口径）；`null` 列值按 SQL 语义忽略（对齐 P39 `AggExecutor`：`COUNT(col)` 不计数 NULL，`MIN/MAX/SUM/AVG` 忽略 NULL，COUNT 空组 = 0）。
- **R8 无状态 & 0 新线程**：`ColumnAggPushdown` 纯静态无实例状态；handler 内联计算，0 `ExecutorService`/`Timer`。
- **R9 代码卫生**：新增源文件 0 `TODO/FIXME/HACK`、0 `printStackTrace`；Javadoc 中文 ≥10 行/类。
- **R10 兼容**：`P2PCommand` 追加后，既有 7 条 fulltext SPI + DS_DB 既有 handler 路由 **0 回归**。

---

## 3. 架构（0 新 Maven dep）

```
p2p-core/javax/net/p2p/
├── api/P2PCommand.java                     # ★ 受控：+DB_ROW_AGG(24048)/R_OK_DB_ROW_AGG(24049)
└── model/
    ├── DbAggFn.java                        # ★ 新增（COUNT/SUM/AVG/MIN/MAX 枚举）
    ├── DbAggItem.java                      # ★ 新增（fn + column）
    ├── DbRowAggRequest.java                # ★ 新增
    └── DbRowAggResponse.java               # ★ 新增
p2p-ws-sdk-java/.../P2PCommand.java         # ★ 受控：+2 枚举
p2p-ws-sdk-java/.../P2PCommandOrdinals.java # ★ 受控：+2 映射

p2p-db/com/q3lives/ds/database/columnar/
├── pushdown/          # P52（=2）锁定，0 改
├── aggpushdown/       # ★ P53 新增第 13 处锁定（=1）
│   └── ColumnAggPushdown.java            # 列值聚合引擎（复用 ColumnScanPushdown.filter）
└── DsDatabaseClusterClient.java          # ★ 受控：+queryAgg(...)

p2p-db/javax/net/p2p/server/handler/
└── DbRowAggServerHandler.java            # ★ 新增（implements P2PCommandHandler）
```

**类清单（P53 新增 = 6）**：`DbAggFn` / `DbAggItem` / `DbRowAggRequest` / `DbRowAggResponse` / `ColumnAggPushdown` / `DbRowAggServerHandler`。

---

## 4. 数据流

```
客户端：DsDatabaseClusterClient.queryAgg(entityClassName, query, aggs, groupBy, having, orderBy, offset, limit)
        │  → DbRowAggRequest
        ▼  P2PCommand.DB_ROW_AGG
服务端：DbRowAggServerHandler.process
        │
        ├─ ColumnScanPushdown.filter(dbRoot, entity, query)   // P52 复用，0 改 → 命中 rowId
        │
        └─ ColumnAggPushdown.compute(dbRoot, entity, rowIds, aggs, groupBy, having)
              ├─ 逐 rowId 从 ColumnarStore 读 groupBy 列 + agg 列（decode by ColumnDef.javaType）
              ├─ 按 group key 分桶；各 agg 项累加（NaN/NULL 忽略，对齐 P39）
              ├─ HAVING 过滤（对聚合别名/分组键的比较）
              └─ ORDER BY 排序（group key / 聚合别名；tie-break 稳定）
        │
        ▼  DbRowAggResponse（分组结果编码）
客户端：解析 → 分组结果（groupKey[], aggValues[]）
```

---

## 5. 接口契约（草案）

```java
// p2p-core：javax.net.p2p.model
public enum DbAggFn { COUNT, SUM, AVG, MIN, MAX }

public class DbAggItem {
    public DbAggFn fn;        // 非 null
    public String column;     // 非空白
    // 6 IAE：fn null / column null / blank / 含空白 / 非 [A-Za-z0-9_] / == "AGG"
}

public class DbRowAggRequest {
    public String entityClassName;
    public DbQuery query;
    public List<DbAggItem> aggs;        // 非空
    public List<String> groupBy;        // 可空（空 = 全局聚合无分组）
    public List<DbQueryCriterion> having; // 可空（HAVING 谓词，column=分组键或 "SUM(col)" 别名）
    public List<DbQueryOrder> orderBy;  // 可空
    public int offset;
    public int limit;
}

public class DbRowAggResponse {
    public byte[] payload;   // 编码后的分组结果（groupKey 列 + aggValue 列）
}

// p2p-db：columnar/aggpushdown
public final class ColumnAggPushdown {
    /** @return 分组结果行（每行 = groupKey[] + aggValue[][]），已按 orderBy 排序并分页 */
    public static List<Object[]> compute(File dbRoot, String entityClassName, List<Long> rowIds,
                                         List<DbAggItem> aggs, List<String> groupBy,
                                         List<DbQueryCriterion> having,
                                         List<DbQueryOrder> orderBy, int offset, int limit);
}
```

> **注（值编码）**：`DbQueryCriterion.a/b` 为 `String`，沿用 P46 §5「`String.valueOf(...)` 映射」口径；HAVING 的比较按列 `javaType` 解析。
> **注（复用）**：`ColumnAggPushdown` 只调 `ColumnScanPushdown.filter`（public static，0 改）取 id；聚合列值经 `ColumnarStore.getValue` + `TableMetaStore` 列元读取（P52 已验证该双源依赖）。

---

## 6. 测试计划（36 TR，Gate B 拆批）

| 批次 | 文件 | 数量 | 覆盖 |
|------|------|------|------|
| T1 | `P53_T1_AggPushdownSkeletonTest` | 5 | `DbAggItem` 6 IAE；`ColumnAggPushdown.compute` 参数 IAE（null/空 aggs）；空 rowIds 短路 |
| T2 | `P53_T2_AggSemanticsTest` | 7 | 5 聚合正确性（含 NULL 忽略、COUNT 空组=0）；GROUP BY 单/双键；HAVING 过滤；AVG 精度 |
| T3 | `P53_T3_OrderByServerTest` | 5 | ORDER BY group key / 聚合别名 asc/desc；tie-break 稳定；offset/limit 分页 |
| T4 | `P53_T4_ServerHandlerTest` | 5 | `DbRowAggServerHandler` 命令校验/非法 payload/entity 空；正常聚合往返；错误码 |
| T5 | `P53_T5_Gatekeeper9YTest` | 9 | 9Y 红线（R1-R10 反射/文件计数实锤，含第 13 处锁 =1、`P2PCommand` 既有值 0 变） |
| T6 | `P53_T6_NFR_Test` | 5 | 列值聚合 vs `AggExecutor` 结果等价；CPD 0 重复块；0 println；Javadoc ≥10 行 |

### 6.1 验收门槛（实跑回填，禁止预填）
- [x] P53 own **36/36 TR 0F0E**（T1=5 / T2=7 / T3=5 / T4=5 / T5=9 / T6=5）✅
- [x] Gate B 拆批全量回归（沿用 P52 六批口径 + 批 4c 纳入 P53）= **984 跑（76+112+68+193+186+349），0 Error，1 已知基线 fail（`P28_T6.tr64`）** ✅
- [x] Gate C 9Y×9 全 Y + 洁癖 5/5 ✅

---

## 7. 逆指标 / NFR

- 服务端聚合 `compute` 对 N 行 M 聚合项的耗时 ≤ 「N 次 `ColumnarStore.getValue` + 单遍累加」线性上界；
- 与 `AggExecutor`（实体级）在同数据集上**逐值等价**（T6 实锤）；
- CPD 160 字符窗口 **0 重复块**。

---

## 8. 风险与回退

| 风险 | 缓解 |
|------|------|
| `P2PCommand` 双份定义（p2p-core 数值版 + p2p-ws-sdk ordinal 版）漏改一侧 | T5 `y*` 反射断言两侧均含新命令；`P2PCommandOrdinals` 显式断言 |
| handler 注册入口不明（SPI 文件现仅 7 条 fulltext；DS_DB handler 注册点未实证） | Implement 前置 Probe 实证注册入口；若为 SPI 则追加服务文件，若为程序化则改对应 bootstrap（并入 Q4 白名单） |
| HAVING 引用聚合别名（`SUM(price)`）的解析歧义 | MVP 支持「分组键」+「`FN(col)` 别名」两种 HAVING 左值；文档显式标注 |
| NULL/类型语义与 P39 不一致 | R7 钉死对齐 P39；T2 以 `AggExecutor` 为 oracle 等价校验 |
| 段格式误触 | R5；`ColumnAggPushdown` 只读列值，0 写段 |

**回退**：若 Q1 裁决 A（扩 `DbQuery`）→ 规格重写（REV2）。

---

## 9. 签批区（用户 VERBATIM）

- [x] 采纳 P53 为 OQ12-㉛ 候选项 ②（服务端聚合函数下发 / ORDER BY 服务端排序）
- [x] **Q0**：`Q0 = ②`
- [x] **Q1**：`Q1 = B`（新命令 + 专用模型；`DbQuery` 0 改）
- [x] **Q2**：新锁 `columnar/aggpushdown/` = 1（第 13 处）
- [x] **Q3**：`Q3 = A`（仅对聚合结果排序）
- [x] **Q4**：认可 §0-Q4 白名单受控改动，其余 0 改
- [x] 认可 §1.3 MVP 边界（不做全集群聚合归并 / 不扩 `DbQuery` / 不做流式）
- [x] 认可 36 TR × 6 批测试计划与 Gate B/C 口径
- [x] 授权进入 Implement（T1-T6）

**用户批准原文（VERBATIM）**：

> 方向指派「1,2,3顺序实现」→ P53 = 第 2 项（OQ12-㉛②）；随后「继续」/「Continue」= 采纳 §0 全部默认签批：Q0=② / Q1=B / Q2=`columnar/aggpushdown/`=1（第 13 处）/ Q3=A / Q4=白名单受控改动，其余 0 改。

---

## 10. 实装偏差表（Implement 后回填）

| 编号 | 计划 | 实装 | 原因 |
|------|------|------|------|
| D1 | §0-Q4 白名单含「handler 注册入口（SPI 或程序化）— 待 Implement 实证后择一追加」 | 0 注册文件改动：新增 `DbRowAggServerHandler` 落既有 `javax.net.p2p.server.handler` 包 + public 无参构造，由 `AbstractUdpMessageProcessor` 静态扫描自动注册 | Probe 实证 DS_DB handler 不走 SPI，包路径扫描 + `P2PCommandHandler.isAssignableFrom` 即可自动注册 |

---

## 11. Probe 锚点（实锤文件/行）

1. `p2p-core .../model/DbQuery.java:6-15`（`where/anyOf/orderBy`，无 agg/groupBy/having）。
2. `p2p-core .../model/DbQueryOrder.java:3-14`（`name + asc`）；`DbRowQueryIdsRequest.java:3-17`（`entityClassName/query/offset/limit`）。
3. `p2p-core .../api/P2PCommand.java:485-533`（`DB_ROW_QUERY_IDS=24032`…`DB_NODE_QUERY_IDS=24107`；空闲段 24048-24099）。
4. `p2p-ws-sdk-java .../api/P2PCommand.java:162-163` + `.../core_compat/P2PCommandOrdinals.java:168-169`（第二套定义）。
5. `p2p-db .../server/handler/DbRowQueryIdsServerHandler.java:69`（`ColumnScanPushdown.filter`）+ `:71-84`/`:137-206`（服务端 ORDER BY）。
6. `p2p-db .../server/handler/DbNodeQueryIdsServerHandler.java:71-72`（`ColumnScanPushdown.scan`，无 ORDER BY）。
7. `p2p-db .../columnar/pushdown/ColumnScanPushdown.java`（P52 列扫描下推，`filter` 复用源）。
8. `p2p-db .../fulltext/cluster/agg/AggExecutor.java:46-155`（实体级 5 聚合 + NULL 语义 oracle）；`.../parser/v3/AggSpec.java:30-71`（6 IAE 范式）。
9. `p2p-db .../columnar/DsDatabaseClusterClient.java:203-255`（`queryIds` 2 重载，客户端入口范式）。
10. `p2p-db/src/main/resources/META-INF/services/javax.net.p2p.interfaces.P2PCommandHandler`（SPI，现 7 条 fulltext）。
11. 上游输入：`.trae/specs/p46-oq1-columnar-server-agg-pushdown/spec.md:57-59`（不做服务端聚合 handler，挂后续 OQ）；P52 spec。
