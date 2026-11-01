# P51 OQ12-㉙① 跨段词网聚合 —— tasks.md（**✅ Gate A 已签批 / ✅ Gate B 916 跑 / ✅ Gate C 9Y 9/9 + 洁癖 5/5 闭环**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ ✅ 已签批（OQ12-㉙① + §0 **Q0=① / Q1=A / Q2=`wordnet/` / Q3=纯显式 export / Q4=新段优先短路覆盖**）
- [x] **Gate B**（P51 own TR 0F0E + P22-P51 拆 6 批 + 已知基线 fail 沿用）→ ✅ 916 跑 / 1F（唯一 fail = 已知基线 `P28_T6.tr64`，不阻塞）
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ ✅ 9Y 9/9 + 洁癖 5/5 全闭环

### 实跑 TR 数（Implement 后实跑回填，**禁止预填**）
- P51 own = **33/33 TR 0F0E**（T1=5 / T2=6 / T3=4 / T4=4 / T5=9 / T6=5）
- Gate B 拆批实跑（6 批）= 批1 **76/76** · 批2 **112/1F** · 批3 **68/68** · 批4a **193/193** · 批4b **186/186** · 批4c **281/281** ⇒ 合计 **916 跑 1 Fail（已知基线 `P28_T6.tr64`）/ 0 Error**

---

## Stage 1: Gate A（✅ 已签批）

| # | 任务 | 状态 |
|---|------|------|
| 1.1 | Probe：`FulltextStore` 词网（positions + distL/distR + pairMap + uidRing，`DIST_MAX=1024`）/ P37 DIDX 段 4 section / P42 `LayeredInvertedView` 能力缺口 / P42·P43 spec「不做 跨段词网聚合」遗留 / 既有 10 处锁定包 | ✅ |
| 1.2 | 起草 spec.md（§0 待裁决 Q0-Q4 + Scope + 红线 R1-R13 + 架构 + 接口契约 + T1-T6 + 逆指标 + 风险 + 9Y + Probe 锚点） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细 + Gate B 拆 6 批命令） | ✅ |
| 1.4 | 提交 Gate A VERBATIM 逐条签批（含 §0 Q0-Q4 裁决） | ✅ 用户「继续」采纳全部默认 |

---

## Stage 2: Implement（T1-T6）

### T1 快照 + 入口骨架 ×5
- [x] tr11 null 输入 → IAE（`WordNetSnapshot` 6 守卫 + `WordNetExporter` 4 守卫 + `open` segIds 守卫）
- [x] tr12 `fingerprint()` 64-hex：同字段恒定 / 任一字段变即变
- [x] tr13 `deepEquals` 真值表（自身 / 等值 / 各字段差异 / null / 异类型）
- [x] tr14 `WordNetExporter` 构造零状态（0 实例字段 / 0 资源持有）
- [x] tr15 `WordNetSegmentView.open` null·空 rootDir / null·空 segIds → IAE

### T2 导出器等价性 ×6
- [x] tr21 `ctxCount` == 内存态上下文键数
- [x] tr22 `adjCount` == 内存态 L/R 邻接条目数
- [x] tr23 `posCount` == 内存态 positions 区间数
- [x] tr24 逐 (ctx,srcUid) L/R 邻接 `(uid,dir,d)` 全等
- [x] tr25 逐 (uid,ctx) positions `[start,end)` 全等
- [x] tr26 空词网导出（0/0/0 合法段可读）

### T3 只读视图语义 ×4
- [x] tr31 `neighborsWithin` 命中/miss 语义 + `level ∉ [0,5]` IAE + `maxD` 钳制 [1,1024]
- [x] tr32 `distanceBetween` 方向语义（L→R 正距离 / 不存在 = -1）与内存态一致
- [x] tr33 `topKNeighbors` TOPK 铁律（d 递增、满 K 立即返回、去重语义）
- [x] tr34 多段「新段优先」覆盖（含同键冲突）+ `fingerprint` 随段序变化 + `close` 幂等

### T4 E2E + 跨进程 + 等价性 ×4
- [x] tr41 真实 `FulltextStore` 构建词网 → export → `close()` → 重开 view 后邻接/距离/positions 逐条与导出前内存真值全等（零上游依赖）
- [x] tr42 冷启动 `open` P95 ≤ 200ms（R10 零重建实证）+ 词网段自描述（无上游扫描）
- [x] tr43 跨进程 `snapshot.fingerprint()` 64-hex 一致（子 JVM spawn）
- [x] tr44 **R12 等价性**：view 三 API ≡ `FulltextStore.neighborsWithin`/`distanceBetween`/`topKNeighbors` 输出逐条等价

### T5 Gatekeeper 9Y ×9
- [x] y1 ZeroLeak3=3 **且递归文件数守恒**（根包=3 + 11 处锁定包 + `wordnet/`=3）
- [x] y2 NG-3 零依赖（`wordnet/` forbidden 引用 + import 白名单 + pom banned 0 命中）
- [x] y3 TOPK 铁律（**去注释后** 0 `bucketAccessCounter` 污染 / 0 `SearchOptions`）
- [x] y4 Facade 单调不减（P37/P40/P42/P47/P48/P49/P50 目录源码 0 改 + P21~P50 探针签名全在 + **P21 新增 accessor 存在且旧签名不消失**）
- [x] y5 路由公式 0 改（`ShardRouter` / `DsIdLayout`）
- [x] y6 P21~P51 编译兼容（既有类型 0 改 + `wordnet/` 3 类 `Class.forName`）
- [x] y7 HybridHit ctor=2 0 改
- [x] y8 ShardTransport 5 方法 0 改
- [x] y9 0 TODO/FIXME/HACK（新增源码）

### T6 NFR ×5
- [x] tr61 export 1k ctx P95 ≤ 阈值（4000ms）
- [x] tr62 冷启动 open P95 ≤ 200ms
- [x] tr63 3 类 Javadoc ≥ 10 行 + CPD 160 字符窗口 0 重复块
- [x] tr64 0 `System.out.println` / `printStackTrace`（`wordnet/`）+ IAE 显式守卫 ≥ 8（实=32）
- [x] tr65 新增源码 0 TODO/FIXME/HACK

---

## Stage 3: Gate B（拆 6 批全量回归）

| 批 | 范围 | 命令 | 实跑 | 判定 |
|----|------|------|------|------|
| 批 1 | P22-P24 | `-Dtest=ds.P22_*,ds.P23_*,ds.P24_*` | 76/76 | ✅ 0F0E |
| 批 2 | P25-P28 | `-Dtest=ds.P25_*,ds.P26_*,ds.P27_*,ds.P28_*` | 112/1F | ⚠️ 唯一 fail = 已知基线 `P28_T6.tr64`（裸打印=3），不阻塞 |
| 批 3 | P29-P30 | `-Dtest=ds.P29_*,ds.P30_*` | 68/68 | ✅ 0F0E |
| 批 4a | P31-P36 | `-Dtest=ds.P31_*..ds.P36_*` | 193/193 | ✅ 0F0E |
| 批 4b | P37-P42 | `-Dtest=ds.P37_*..ds.P42_*` | 186/186 | ✅ 0F0E |
| 批 4c | P43-P51 | `-Dtest=ds.P43_*..ds.P51_*`（含 P51 own） | 281/281 | ✅ 0F0E |

> 每批前清 `target\tmp` + C 盘残留（`junit*`/`dsdb_*`）；`test-compile` 与 `surefire:test` 串联；长跑 `*> gatebN.log` 落盘再 `Grep`；flaky 单类隔离重跑 5/5 定性（P47 教训）。

---

## Stage 4: Gate C（洁癖 5/5）

- [x] spec.md 回填：标题 + 9Y 9/9 + §5.3 Gate B 实跑 + §9 签批 + §10 偏差表
- [x] tasks.md 回填：三状态 + TR 数 + Stage 2-5 全 `[x]`
- [x] `docs/dsdb-design-status-and-roadmap.md`：`OQ12-㉙①` ⏳→✅ + 挂出 `OQ12-㉚`
- [x] Project Memory：P51 条目 + 硬约束（十一重递归守恒 + `wordnet/`=3）+ Lessons
- [x] 洁癖 5/5（P51_T6 tr63/tr64/tr65 + P51_T5.y9）+ P37~P50 spec 一字不动验证（`git status -- .trae/specs` 仅 P51 新增）

---

## Stage 5: 交付

- [x] 逐路径 `git add`（仅 P51 相关，**严禁 `git add -A`**）→ `git diff --cached --name-status` 复核
- [x] `git commit -F <msgfile>` → `git push origin main`
- [x] 验证 `git log -1` + 远端 `main` 更新
