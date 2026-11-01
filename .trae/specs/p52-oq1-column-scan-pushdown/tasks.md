# P52 OQ12-㉚① 真·列扫描下推 —— tasks.md（**✅ Gate A 已签批 / ✅ Gate B / ✅ Gate C**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ ✅ 已签批（用户「继续」= 采纳全部默认：OQ12-㉚① + §0 **Q0=① / Q1=A / Q2=`columnar/pushdown`锁定2 / Q3=4 处白名单 / Q4=ORM 基准 + 统一 LIKE**）
- [x] **Gate B**（P52 own TR 0F0E + P22-P52 拆 6 批 + 已知基线 fail 沿用）→ ✅ 实跑完成
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ ✅ 完成

### 实跑 TR 数（实跑回填）
- P52 own = **32/32 0F0E**（T1=5 / T2=6 / T3=4 / T4=4 / T5=9 / T6=4）
- Gate B 拆批实跑（6 批）= 76 + 112(1F 已知基线) + 68 + 193 + 186 + 313 = **948 run / 1F（仅已知基线 `P28_T6.tr64`）/ 0 新增回归**

---

## Stage 1: Gate A（✅ 已签批）

| # | 任务 | 状态 |
|---|------|------|
| 1.1 | Probe：列存 `ColumnarStore` 布局 / **既有列级 eq 索引 `EqIndexStore`（EQ/IN 落盘 + 内存 Bloom/rangeMaps）** / `DbRowQueryIdsServerHandler` 已有列扫描算法 / P50 `DB_NODE_QUERY_IDS` 走 ORM `GenericManager.listIds` 全行物化 / P50 D2 LIKE 语义分叉 / P50 D4 显式遗留 | ✅ |
| 1.2 | 起草 spec.md（§0 Q0-Q4 + Scope + 红线 R1-R13 + 架构 + 接口契约 + T1-T6 + Gate B 拆批 + MVP 边界 + 签批区 + 偏差表） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细 + Gate B 6 批口径） | ✅ |
| 1.4 | 提交 Gate A VERBATIM 逐条签批（含 §0 Q0-Q4 裁决） | ✅（用户「继续」= 采纳全部默认） |

---

## Stage 2: Implement（T1-T6）

### T1 列扫描器骨架 + IAE ×5
- [x] tr11 `ColumnScanPushdown` 唯一私有构造 + `public final` + 0 实例字段（`Modifier.isStatic` 过滤静态常量）
- [x] tr12 `scan`/`filter` null `dbRoot` → IAE
- [x] tr13 `entityClassName` null/空白 → IAE
- [x] tr14 `where[i] == null` / `op == null` / `name` 空白 / `IN|NOT_IN` 缺 `list` → IAE
- [x] tr15 `@composite:` 列 / 未知列名 → IAE

### T2 逐 op 语义全等 ×6
- [x] tr21 EQ/IN → `EqIndexStore.findRowIds` 索引候选求交（命中集最小化）
- [x] tr22 NOT_IN → 排除集（`findRowIds` 逐个并集后剔除）
- [x] tr23 比较类算子 GT/GE/LT/LE/BETWEEN 走列值残差（**不走** 内存 `rangeMaps`）
- [x] tr24 LIKE/LIKE_LEFT/LIKE_RIGHT **统一为 ORM 语义**（contains / startsWith / endsWith），修正 P50 D2
- [x] tr25 IS_NULL/IS_NOT_NULL + `anyOf` OR 分支展开（`global ∩ group` 组合）
- [x] tr26 边界值（BETWEEN a==b / 单元素 IN / 值 null）+ 纯函数确定性（同输入同输出）

### T3 服务端 handler 受控改动 ×4
- [x] tr31 `DbNodeQueryIdsServerHandler.process` 走列扫描（命中保留 / 未命中剔除）
- [x] tr32 先过滤后分页（offset/limit 施加于过滤后集合）
- [x] tr33 谓词无命中 → 空 `long[]`（非 null）
- [x] tr34 `query == null` → 全量透传（兼容旧行为，0 变更）

### T4 R12 等价 + E2E ×4
- [x] tr41 列扫描结果 ≡ `DbQueryToQueryWrapper.filterIdsByPredicate`（ORM `listIds` 全行物化）**逐条全等**
- [x] tr42 与 P46 `ColumnarAggRowSource` 端到端一致（AggParser → 列扫描 → id 列表）
- [x] tr43 多分区路由（跨 partitionId 的 id 聚合 + 过滤）
- [x] tr44 强一致契约（命中 id `getById` 必非 null + 伪造 id 被剔除）

### T5 Gatekeeper 9Y ×9
- [x] y1 ZeroLeak3=3 **且十二重递归守恒**（根包=3 + 11 处锁定包 + **`columnar/pushdown/`=2**）
- [x] y2 NG-3 零依赖（`pushdown/` forbidden 引用 + import 白名单 + pom banned 0 命中）
- [x] y3 TOPK 铁律（**去注释后** 0 `bucketAccessCounter` 污染 / 0 `SearchOptions`）
- [x] y4 Facade 单调不减（P21/P37/P40/P42/P47/P48/P49/P51 目录源码 0 改 + `DbQueryToQueryWrapper` 公开签名 0 改）
- [x] y5 路由公式 0 改（`ShardRouter` / `DsIdLayout`）
- [x] y6 P21~P52 编译兼容（既有类型 0 改 + `ColumnScanPushdown` 存在 + `DbQueryToQueryWrapper` 存在）
- [x] y7 HybridHit ctor=2 0 改
- [x] y8 ShardTransport 5 方法 0 改
- [x] y9 0 TODO/FIXME/HACK（新增源码）

### T6 NFR ×5
- [x] tr61 列扫描路径端到端 ≤ 全透传基线 + 20% 松弛（硬断言返回量/字节数单调 + 列扫描 ≡ oracle）
- [x] tr62 新类 Javadoc ≥ 10 行
- [x] tr63 CPD 160 字符窗口 0 重复块（新类 + 4 受改类）
- [x] tr64 0 `System.out.println` / `printStackTrace`（新增/受改源码）+ IAE 显式守卫 ≥ 8（实跑 `ColumnScanPushdown` = 13）
- [x] tr65 新增源码 0 TODO/FIXME/HACK

---

## Stage 3: Gate B（拆 6 批全量回归）✅

| 批 | 范围 | 命令 | 实跑 | 判定 |
|----|------|------|------|------|
| 批 1 | P22-P24 | `-Dtest=ds.P22_*,ds.P23_*,ds.P24_*` | **76/76 0F0E** | ✅ |
| 批 2 | P25-P28 | `-Dtest=ds.P25_*,ds.P26_*,ds.P27_*,ds.P28_*` | **112 run / 1F** | ✅（仅已知基线 `P28_T6.tr64`） |
| 批 3 | P29-P30 | `-Dtest=ds.P29_*,ds.P30_*` | **68/68 0F0E** | ✅ |
| 批 4a | P31-P36 | `-Dtest=ds.P31_*..ds.P36_*` | **193/193 0F0E** | ✅ |
| 批 4b | P37-P42 | `-Dtest=ds.P37_*..ds.P42_*` | **186/186 0F0E** | ✅ |
| 批 4c | P43-P52 | `-Dtest=ds.P43_*..ds.P52_*` | **313/313 0F0E**（含 P52 own 32） | ✅ |

> 命令模板（每批前清 `target\tmp` + C 盘残留）：
> `mvn -pl p2p-db "-Djava.io.tmpdir=i:\2026\code\p2p-net-storage\target\tmp" "-Dp2p.db.home=i:\2026\code\p2p-net-storage\target\tmp\home" "-Dtest=<pattern>" test-compile surefire:test`
> （PS 原生命令 stderr 触发 NativeCommandError → 改用 `Start-Process -RedirectStandardOutput/Error -NoNewWindow -Wait` 口径）
>
> **合计 = 948 run / 1F（仅已知基线）/ 0 新增回归**。

---

## Stage 4: Gate C（9Y 自检 + 洁癖 5/5）✅

- [x] 9Y 全 Y（`P52_T5_Gatekeeper9YTest` 9/9）
- [x] 洁癖 5/5：① roadmap 回填 ② spec/tasks 回填 ③ Project Memory ④ AGENTS/current-state ⑤ 0 残留临时文件
- [x] Roadmap：`OQ12-㉚①` 标 ✅ + 挂 `OQ12-㉛`（P53 ②）

---

## Stage 5: commit + push

- [x] `git add` 精确路径（**严禁 `git add -A`**）
- [x] `git diff --cached --name-status` 复核
- [x] 上游受改类 `git diff --numstat <上游commit> HEAD` 断言（**纯追加/约定改动边界**）
- [x] 写消息文件 → `git commit -F <msgfile>` → `git push origin main`
