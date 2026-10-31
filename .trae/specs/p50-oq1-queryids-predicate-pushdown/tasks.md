# P50 OQ12-㉘② 服务端 queryIds 真·谓词过滤下推 —— tasks.md（**✅ 全闭环**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ ✅ 已签批（OQ12-㉘② + §0 **Q0=② / Q1=A / Q2=`columnar/pushdown` / Q3=网络模型 0 改 / Q4=先过滤后分页**）
- [x] **Gate B**（P50 own 31/31 TR 0F0E + P22-P50 拆批 + 已知基线 fail 沿用）→ ✅ 实跑 883 跑 0 Error（2 非阻塞 fail：1 基线 + 1 环境 flaky）
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ ✅ 9/9 + 5/5 闭环

### 实跑 TR 数（Implement 后实跑回填，**禁止预填**）
- P50 own = **31/31 TR 0F0E**（T1=5 / T2=4 / T3=5 / T4=4 / T5=9 / T6=4）
- Gate B 拆批实跑（6 批）= 批1 **76/76** · 批2 **112（1 基线 fail `P28_T6.tr64`，0 Error）** · 批3 **68/68** · 批4a **193/193** · 批4b **186/186** · 批4c **248（1 flaky `P49_T6.tr61`，隔离 5/5 绿，0 Error）** ⇒ **合计 883 跑 0 Error**

---

## Stage 1: Gate A（✅ 已签批）

| # | 任务 | 状态 |
|---|------|------|
| 1.1 | Probe：`DbNodeQueryIdsServerHandler` / `DsDatabaseClusterClient.queryIds` / `ColumnarStore` / `OrmFindRange` 范式 / P46 边界 | ✅ |
| 1.2 | 起草 spec.md（§0 待裁决 Q0-Q4 + Scope + 红线 R1-R13 + 架构 + 接口契约 + T1-T6 + 9Y） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细 + Gate B 拆批命令） | ✅ |
| 1.4 | 提交 Gate A VERBATIM 逐条签批（含 §0 Q0-Q4 裁决） | ✅ 已签批（Q1=A + 确认全部默认） |

---

## Stage 2: Implement（T1-T6）

### T1 转换器/评估器单测 ×5
- [x] tr11 null 输入 → IAE
- [x] tr12 `DbQuery`→`QueryWrapper` 逐 op 映射全等（EQ/NE/GT/GE/LT/LE/BETWEEN/IN/IS_NULL/IS_NOT_NULL）
- [x] tr13 空 where → 空 wrapper（全透传语义）
- [x] tr14 边界值（BETWEEN a==b / IN 单元素 / 值 null）
- [x] tr15 纯函数确定性（同输入同输出）

### T2 服务端过滤单测 ×4
- [x] tr21 命中保留 / 未命中剔除
- [x] tr22 先过滤后分页（offset/limit 施加于过滤后集合）
- [x] tr23 空集 → 空 `long[]`（非 null）
- [x] tr24 `query == null` → 全透传（兼容旧行为）

### T3 客户端方法单测 ×5
- [x] tr31 本地路径谓词生效
- [x] tr32 远端分支 `query` 透传不变
- [x] tr33 `isLocal` 判定（serverId 命中/未命中）
- [x] tr34 offset/limit 边界（0 / ≥size / 负）
- [x] tr35 异常路径（IO → IOException 上抛）

### T4 E2E 等价 ×4
- [x] tr41 服务端过滤结果 ≡ 本地全量物化过滤结果（逐条等价，R12）
- [x] tr42 与 P46 `ColumnarAggRowSource` 端到端一致
- [x] tr43 多分区路由（跨 serverId/partitionId）
- [x] tr44 强一致契约：过滤命中 id 的 `getById` 非 null（否则 ISE）

### T5 Gatekeeper 9Y ×9
- [x] tr51 Y1 ZeroLeak3=3 + 九重递归守恒
- [x] tr52 Y2 NG-3 零依赖
- [x] tr53 Y3 TOPK 铁律
- [x] tr54 Y4 Facade 单调不减（P21/P37/P40/P42/P47/P48/P49 源码 0 改）
- [x] tr55 Y5 路由公式 0 改（ShardRouter / DsIdLayout）
- [x] tr56 Y6 P21~P50 编译兼容
- [x] tr57 Y7 HybridHit ctor=2
- [x] tr58 Y8 ShardTransport 5 方法
- [x] tr59 Y9 0 TODO/FIXME/HACK（新增源码）

### T6 NFR ×4
- [x] tr61 过滤后服务端响应 P95/均值 ≤ 全透传基线（或注明口径 + 松弛量）
- [x] tr62 新类 Javadoc ≥10 行
- [x] tr63 CPD 160 字符窗口 0 重复块
- [x] tr64 0 println/printStackTrace + IAE 守卫 ≥5

---

## Stage 3: Gate B（✅ 拆批全量回归完成）

> 6 批：批 1 P22-P24 / 批 2 P25-P28 / 批 3 P29-P30 / 批 4 拆 3 子段 4a P31-P36 / 4b P37-P42 / 4c P43-P50。
> **每批 Gate B 前追加 C 盘残留清理**（`%LOCALAPPDATA%\Temp` 下 `junit*` / `dsdb_*`）。**长跑日志 `*> gatebN.log` 落盘再读**。已知基线 fail `P28_T6.tr64` 沿用不阻塞。

| 批 | 范围 | 实跑 | 日志 |
|----|------|------|------|
| 1 | P22-P24 | 76/76 0F0E | `gateb1.log` |
| 2 | P25-P28 | 112 跑 / 1 基线 fail / 0E | `gateb2.log` |
| 3 | P29-P30 | 68/68 0F0E | `gateb3.log` |
| 4a | P31-P36 | 193/193 0F0E | `gateb4a.log` |
| 4b | P37-P42 | 186/186 0F0E | `gateb4b.log` |
| 4c | P43-P50 | 248 跑 / 1 flaky（`P49_T6.tr61`，隔离 5/5 绿）/ 0E | `gateb4c.log` / `p49t6_iso.log` |

**合计 883 跑 0 Error**（2 非阻塞 fail：基线 `P28_T6.tr64` + 环境 flaky `P49_T6.tr61`）。

---

## Stage 4: Gate C（✅ 9Y + 洁癖 5/5 闭环）
- [x] ① spec.md 回填 Gate C 9Y（9/9）+ §10 实装偏差表（D1-D4）+ 签批区
- [x] ② tasks.md 回填 Gate B/C 状态与实跑数字
- [x] ③ 洁癖 5/5（0 println / IAE 守卫 ≥5 / Javadoc ≥10 / CPD 0 重复块 / 0 TODO）→ 由 `P50_T6`（tr62/tr63/tr64）+ `P50_T5.y9` 实跑覆盖，全绿
- [x] ④ Roadmap OQ12-㉘② ⏳→✅ + OQ12-㉙ 候选项挂出
- [x] ⑤ P37~P49 Spec 一字不动 + Project Memory 同步

---

## Stage 5: 交付
- [x] 逐路径 `git add`（**严禁 `git add -A`**）
- [x] `git commit -F`（P50 T1-T6 + Gate C 5/5 closure）
- [x] `git push origin main`

---

## 红线总表（Gate A 冻结，0 越界）
R1 递归守恒 / R2 NG-3 / R3 TOPK / R4 Facade 单调不减 / R5 段格式 0 变更 / **R6 上游受控改动白名单（仅 handler + client 查询方法）** / R7 网络模型 0 改 / R8 强一致契约 / R9 只读无写盘 / R10 0 新线程 / R11 确定性升序 / R12 谓词语义全等 / R13 空集与边界。

## 类清单（预计）
- 新增：`DbQueryToQueryWrapper`（Q1=A）或 `DbQueryPredicateEvaluator`（Q1=B）—— 落 `com.q3lives.ds.database.columnar(.pushdown)`（Q2）
- 受改：`DbNodeQueryIdsServerHandler`、`DsDatabaseClusterClient`
- 测试：`p2p-db/src/test/java/ds/P50_T1_*.java` ~ `P50_T6_*.java`（6 文件）
