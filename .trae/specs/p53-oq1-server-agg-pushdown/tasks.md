# P53 OQ12-㉛② 服务端聚合函数下发 / ORDER BY 服务端排序 —— tasks.md（**✅ CLOSED：Gate A 已签批 / Gate B 984 跑 1 已知基线 fail / Gate C 9Y×9 + 洁癖 5/5**）

> 配套 `spec.md`（同目录）。**须用户 VERBATIM 批准 spec §9 后方可进入 Implement T1-T6**（SP3.1 硬约束）。
> 上游输入：P46 §1.3「不做服务端聚合 handler（方案 B → 挂后续 OQ）」= 本阶段正题；P50 §10 D2/D3/D4；P52 `ColumnScanPushdown`（列扫描下推，可复用）。

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ ✅ 用户 VERBATIM（§0 Q0-Q4）
- [x] **Gate B**（P53 own TR 0F0E + P22-P53 拆批全量回归 + 已知基线 fail 沿用）→ ✅ 984 跑 0F0E + 1 已知基线 `P28_T6.tr64`
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ ✅ 9Y 9/9 Y + 洁癖 5/5

### 目标 TR 数（**Implement 后实跑回填，禁止预填**）
- P53 own = **36/36 0F0E**（T1=5 / T2=7 / T3=5 / T4=5 / T5=9 / T6=5）—— 实跑：**36/36 0F0E** ✅
- Gate B 拆批（沿用 P52 六批口径 + 批 4c 扩至 P53）= **984 跑（76+112+68+193+186+349），0 Error，1 已知基线 fail（`P28_T6.tr64`）** ✅

---

## Stage 1: Gate A（✅ 已签批）

| # | 任务 | 状态 |
|---|------|------|
| 1.1 | Probe：`DbQuery{where,anyOf,orderBy}` 无 agg 字段 / 客户端 `AggExecutor`+`AggRowSource`（实体级）/ `AggSpec` 6 IAE 范式 / `DbRowQueryIdsServerHandler` 已有服务端 ORDER BY / `DbNodeQueryIdsServerHandler` 无 ORDER BY / `P2PCommand` 双份定义（空闲段 24048-24099）/ SPI 现仅 7 条 fulltext / P46 §1.3 遗留方案 B | ✅ |
| 1.2 | 起草 spec.md（§0 Q0-Q4 + Scope + 红线 R1-R10 + 架构 + 数据流 + 接口契约 + 36 TR + MVP 边界 + 风险 + 签批区 + 偏差表 + Probe 锚点） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细 + Gate B 拆批口径） | ✅（本文档） |
| 1.4 | 提交 Gate A VERBATIM 逐条签批（含 §0 Q0-Q4 裁决） | ✅ 用户签批 |

---

## Stage 2: Implement（T1-T6，36 TR）

### T1 列值聚合引擎骨架 + IAE ×5
- [x] tr11 `ColumnAggPushdown` 唯一私有构造 + `public final` + 0 实例字段（`Modifier.isStatic` 过滤静态常量）
- [x] tr12 `compute` null `dbRoot` → IAE
- [x] tr13 `entityClassName` null/空白 → IAE
- [x] tr14 `aggs` null/空 → IAE（`DbAggItem` 6 IAE：`fn==null` / `column==null` / blank / 含空白 / 非 `[A-Za-z0-9_]` / `column=="AGG"`）
- [x] tr15 未知列名 / `groupBy` 含未知列 → IAE（复用 `TableMetaStore` 列元校验）

### T2 聚合语义（对齐 P39 `AggExecutor` oracle）×7
- [x] tr21 COUNT(col) 忽略 NULL / COUNT 空组 = 0
- [x] tr22 SUM 忽略 NULL / 全 NULL 组 NaN 哨兵语义对齐
- [x] tr23 AVG 精度（先累加后除，非逐项平均）
- [x] tr24 MIN/MAX 忽略 NULL + 单调正确
- [x] tr25 GROUP BY 单键分桶正确（groupKey 升序稳定）
- [x] tr26 GROUP BY 双键分桶正确（(k1,k2) 复合键）
- [x] tr27 HAVING 过滤（分组键 + `FN(col)` 聚合别名两类左值）

### T3 服务端 ORDER BY ×5
- [x] tr31 ORDER BY 分组键 asc/desc
- [x] tr32 ORDER BY 聚合别名（`SUM(col)`）asc/desc
- [x] tr33 多键 ORDER BY + tie-break 稳定（R6 全序）
- [x] tr34 offset/limit 分页施加于「HAVING 过滤 + 排序后」结果
- [x] tr35 orderBy 为空 → 默认按 group key 升序（R6）

### T4 服务端 handler 受控改动 ×5
- [x] tr41 `DbRowAggServerHandler` 命令校验（`DB_ROW_AGG` 路由）+ 非法 payload 错误码
- [x] tr42 `entityClassName` 空 / `query == null` 边界
- [x] tr43 正常聚合往返（请求 `DbRowAggRequest` → `DbRowAggResponse` payload 解码）
- [x] tr44 复用 `ColumnScanPushdown.filter` 取 id（命中保留 / 未命中剔除）
- [x] tr45 `aggs` 空 → 显式错误码（非 500 崩溃）

### T5 Gatekeeper 9Y ×9
- [x] y1 ZeroLeak3=3 **且十三重递归守恒**（根包=3 + 12 处锁定包 + **`columnar/aggpushdown/`=1（第 13 处）**）
- [x] y2 NG-3 零依赖（`aggpushdown/` forbidden 引用 + import 白名单 + pom banned 0 命中）
- [x] y3 `P2PCommand` 既有枚举值 0 变（仅追加 `DB_ROW_AGG`/`R_OK_DB_ROW_AGG`）+ 双份定义两侧均含新命令
- [x] y4 Facade 单调不减（P21/P37/P40/P42/P47/P48/P49/P51/P52 目录源码 0 改 + `ColumnScanPushdown` 公开签名 0 改）
- [x] y5 路由公式 0 改（`ShardRouter` / `DsIdLayout`）
- [x] y6 P21~P53 编译兼容（既有类型 0 改 + `ColumnAggPushdown`/`DbRowAggServerHandler` 存在）
- [x] y7 `DbQuery`/`DbQueryOrder`/`DbQueryCriterion` 模型字段 0 改（Q1=B 路线实锤）
- [x] y8 段格式 0 变更（复用 `MAGIC="DIDX"`/`VERSION=1`，0 VERSION=2）
- [x] y9 0 TODO/FIXME/HACK（新增源码）+ 0 新线程（R8）

### T6 NFR ×5
- [x] tr61 列值聚合端到端 ≤ 「N 次 `ColumnarStore.getValue` + 单遍累加」线性上界（硬断言）
- [x] tr62 列值聚合结果 ≡ `AggExecutor`（实体级 oracle）**逐值等价**
- [x] tr63 新类 Javadoc ≥ 10 行
- [x] tr64 CPD 160 字符窗口 0 重复块（新类 + 受改类）
- [x] tr65 0 `System.out.println` / `printStackTrace`（新增/受改源码）+ IAE 显式守卫足量

---

## Stage 3: Gate B（拆批全量回归）✅

| 批 | 范围 | 命令 | 实跑 | 判定 |
|----|------|------|------|------|
| 批 1 | P22-P24 | `-Dtest=ds.P22_*,ds.P23_*,ds.P24_*` | 76 跑 0F0E | ✅ |
| 批 2 | P25-P28 | `-Dtest=ds.P25_*,ds.P26_*,ds.P27_*,ds.P28_*` | 112 跑 1F（已知基线 `P28_T6.tr64`）0E | ✅ |
| 批 3 | P29-P30 | `-Dtest=ds.P29_*,ds.P30_*` | 68 跑 0F0E | ✅ |
| 批 4a | P31-P36 | `-Dtest=ds.P31_*..ds.P36_*` | 193 跑 0F0E | ✅ |
| 批 4b | P37-P42 | `-Dtest=ds.P37_*..ds.P42_*` | 186 跑 0F0E | ✅ |
| 批 4c | P43-P53 | `-Dtest=ds.P43_*..ds.P53_*` | 349 跑 0F0E（含 P53 own 36） | ✅ |

> **合计 = 984 跑，0 Error，1 已知基线 fail（`P28_T6.tr64` 裸打印=3，P53 零触碰该目录）**。

> 命令模板（每批前清 `target\tmp` + C 盘残留）：
> `mvn -pl p2p-db "-Djava.io.tmpdir=i:\2026\code\p2p-net-storage\target\tmp" "-Dp2p.db.home=i:\2026\code\p2p-net-storage\target\tmp\home" "-Dtest=<pattern>" test-compile surefire:test`
> （PS 原生命令 stderr 触发 NativeCommandError → 改用 `Start-Process -RedirectStandardOutput/Error -NoNewWindow -Wait` 口径；p2p-core 新模型类须先 `mvn -pl p2p-core install -DskipTests` 使 `-pl p2p-db` 可见）

---

## Stage 4: Gate C（9Y 自检 + 洁癖 5/5）✅

- [x] 9Y 全 Y（`P53_T5_Gatekeeper9YTest` 9/9）
- [x] 洁癖 5/5：① roadmap 回填 ② spec/tasks 回填 ③ Project Memory（`project_memory.md` + `user_profile.md`）④ AGENTS/current-state（无阶段状态段需同步，沿袭 P51/P52）⑤ 0 残留临时文件（`gb*.log`/`p53_run*.log`/`target/roadmap_tail.txt` 已清）
- [x] Roadmap：`OQ12-㉛②` 标 ✅ + 挂 `OQ12-㉜`（P54 ③）

---

## Stage 5: commit + push

- [ ] `git add` 精确路径（**严禁 `git add -A`**）
- [ ] `git diff --cached --name-status` 复核
- [ ] 上游受改类 `git diff --numstat <上游commit> HEAD` 断言（**纯追加/约定改动边界**）
- [ ] 写消息文件 → `git commit -F <msgfile>` → `git push origin main`
