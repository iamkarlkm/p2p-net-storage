# P54 OQ12-㉜① 词网段增量导出 / tombstone / 段淘汰 —— tasks.md（**Gate A 已签批 / Gate B 已跑 / Gate C 待回填**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ ✅ 用户「Continue」采纳 §0 全部默认（OQ12-㉜① + §0 **Q0=① / Q1=A 0 新类 / Q2=A 纯显式变更上下文 / Q3=A per-key 哨兵 / Q4=纯显式 evict+只读审计 / Q5=先 unregister 后 delete**）
- [x] **Gate B**（P54 own TR 0F0E + P22-P54 拆 6 批 + 已知基线 fail 沿用）→ ✅ 见下实跑数字
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ ✅ 9Y 9/9 + 洁癖 5/5 全达成（P37~P53 spec 一字不动、0 残留临时文件）

### 实跑 TR 数（Implement 后实跑回填，**禁止预填**）
- P54 own = **35 / 0F / 0E**（T1=5 / T2=6 / T3=5 / T4=5 / T5=9 / T6=5）
- Gate B 拆批实跑（6 批）= **1019 TR**（批1=76 0F0E / 批2=112 1F 已知基线 `P28_T6.tr64` / 批3=68 0F0E / 批4=193 0F0E / 批5=186 0F0E / 批6=384 1F `P45_T6.tr61` 隔离重跑 5/5 定性为 flaky）

---

## Stage 1: Gate A（✅ 已签批）

| # | 任务 | 状态 |
|---|------|------|
| 1.1 | Probe：P51 `export`（全量 / 无增量）/ `Store.list`+`append`（纯追加 / 0 删除）/ 视图首命中（无删除表达）/ payload `count<0` 天然零迭代兼容 / 词网段 v1 格式 / 13 重递归守恒锁 | ✅ |
| 1.2 | 起草 spec.md（§0 待裁决 Q0-Q5 + Scope + 红线 R1-R13 + 架构 0 新类 + 接口契约 + T1-T6 + 逆指标 + 风险 + 9Y + Probe 锚点） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细 + Gate B 拆 6 批命令） | ✅ |
| 1.4 | 提交 Gate A VERBATIM 逐条签批（含 §0 Q0-Q5 裁决） | ✅ 用户「Continue」采纳 §0 全部默认 |

---

## Stage 2: Implement（T1-T6，实跑 35 TR / 0F / 0E）

### T1 入口骨架 + IAE + 哨兵 ×5
- [x] tr11 `exportDelta` 4 IAE（ft null / rootDir null·空 / segId < 0 / segId 已注册）
- [x] tr12 `tombstone` 4 IAE（rootDir null·空 / segId < 0 / segId 已注册 / adjKeys∧posKeys 皆空）
- [x] tr13 `auditSuperseded` 4 IAE（rootDir null·空 / segIds null / segIds 空 / 段未注册）
- [x] tr14 `evict` 4 IAE（rootDir null·空 / segIds null / segIds 空 / 段未注册）
- [x] tr15 哨兵 payload 往返（写墓碑段 → 读 payload `count == -1`）

### T2 增量导出等价性 ×6
- [x] tr21 `exportDelta` `ctxCount` == 变更上下文键数
- [x] tr22 `exportDelta` `adjCount` == delta L/R 邻接条目数
- [x] tr23 `exportDelta` `posCount` == delta positions 区间数
- [x] tr24 delta 段 + base 段叠加 L/R 邻接 ≡ 全量导出逐条
- [x] tr25 delta 段 + base 段叠加 positions ≡ 全量导出逐条
- [x] tr26 空 `changedCtx` → 0/0/0 合法段可读

### T3 tombstone 语义 ×5
- [x] tr31 墓碑段 → `neighborsWithin` 空（首命中哨兵短路）
- [x] tr32 墓碑段 → `distanceBetween` == -1
- [x] tr33 墓碑段 → `positions` 空
- [x] tr34 更新段墓碑 遮蔽 旧段数据（段序显式给定）
- [x] tr35 旧读者兼容（`count<0` 不抛异常）+ `fingerprint` 随墓碑段变化

### T4 段淘汰 + 审计 ×5
- [x] tr41 `auditSuperseded`：全键覆盖旧段判可淘汰 / 部分覆盖不可
- [x] tr42 `auditSuperseded` 墓碑覆盖计入（墓碑也算覆盖）
- [x] tr43 `evict`：manifest 移除 + 段文件删除 + 剩余段仍可 open/查询
- [x] tr44 R9' 强不变式（先 unregister 后 delete；孤儿文件不在 manifest → 不可见）
- [x] tr45 `evict` 幂等/边界（淘汰后可重 append 同 segId）

### T5 Gatekeeper 9Y ×9
- [x] y1 ZeroLeak3=3 **且 13 重递归守恒全不变**（`wordnet/`=3）
- [x] y2 NG-3 零依赖（`wordnet/` forbidden 引用 + import 白名单 + pom banned 0 命中）
- [x] y3 TOPK 铁律（**去注释后** 0 `bucketAccessCounter` 污染 / 0 `SearchOptions`）
- [x] y4 Facade 单调不减（P21 本阶段 0 改 + P37~P53 目录源码 0 改 + P51 3 类旧签名不消失）
- [x] y5 路由公式 0 改（`ShardRouter` / `DsIdLayout`）
- [x] y6 P21~P54 编译兼容（既有类型 0 改 + `wordnet/` 3 类 `Class.forName` + P54 新方法 `getMethod` 可达）
- [x] y7 HybridHit ctor=2 0 改
- [x] y8 ShardTransport 5 方法 0 改
- [x] y9 0 TODO/FIXME/HACK（新增/改源码）

### T6 NFR ×5
- [x] tr61 `exportDelta` 1k ctx P95 ≤ 阈值
- [x] tr62 `auditSuperseded` 1k 键 P95 ≤ 阈值
- [x] tr63 改动类 Javadoc ≥ 10 行 + CPD 160 字符窗口 0 重复块
- [x] tr64 0 `System.out.println` / `printStackTrace`（`wordnet/`）+ IAE 显式守卫 ≥ 阈值
- [x] tr65 新增/改源码 0 TODO/FIXME/HACK

---

## Stage 3: Gate B（拆 6 批全量回归）—— ✅ 已完成

| 批 | 范围 | 命令 | 实跑 | 判定 |
|----|------|------|------|------|
| 批 1 | P22-P24 | `-Dtest=ds.P22_*,ds.P23_*,ds.P24_*` | 76 TR / 0F / 0E | ✅ BUILD SUCCESS |
| 批 2 | P25-P28 | `-Dtest=ds.P25_*,ds.P26_*,ds.P27_*,ds.P28_*` | 112 TR / 1F / 0E | ⚠️ 已知基线 `P28_T6.tr64` 1F（不阻塞） |
| 批 3 | P29-P30 | `-Dtest=ds.P29_*,ds.P30_*` | 68 TR / 0F / 0E | ✅ BUILD SUCCESS |
| 批 4a | P31-P36 | `-Dtest=ds.P31_*..ds.P36_*` | 193 TR / 0F / 0E | ✅ BUILD SUCCESS |
| 批 4b | P37-P42 | `-Dtest=ds.P37_*..ds.P42_*` | 186 TR / 0F / 0E | ✅ BUILD SUCCESS |
| 批 4c | P43-P54 | `-Dtest=ds.P43_*..ds.P54_*`（含 P54 own 35 TR） | 384 TR / 1F / 0E | ⚠️ `P45_T6.tr61` 1F → 隔离重跑 5/5 定性为 load 时序 flaky（非 P54 回归） |

> 合计 **1019 TR**。每批前清 `target\tmp` + C 盘残留（`junit*`/`dsdb_*`）；`test-compile` 与 `surefire:test` 串联；长跑 `Start-Process -RedirectStandardOutput` 落盘再 `Grep`；flaky 单类隔离重跑 5/5 定性（P47 教训，P45_T6.tr61 已实锤）。
> 环境记录：首次跑批 2-6 遇 C 盘 0GB（`磁盘空间不足` + `ConnectException` 假失败）→ 清 `$env:TEMP` 后重跑（`gateb_p54b.*.log`）。

---

## Stage 4: Gate C（洁癖 5/5）—— ✅ 已完成

- [x] spec.md 回填：标题 + 9Y 9/9 + §5.3 Gate B 实跑 + §9 签批 + §10 偏差表
- [x] tasks.md 回填：三状态 + TR 数 + Stage 2-5 全 `[x]`
- [x] `docs/dsdb-design-status-and-roadmap.md`：`OQ12-㉜①` ⏳→✅ + 挂出 `OQ12-㉝`
- [x] Project Memory：P54 条目 + 硬约束（13 重递归守恒全不变）+ Lessons
- [x] 洁癖 5/5（P54_T6 tr63/tr64/tr65 + P54_T5 9/9）+ P37~P53 spec 一字不动验证（仅 P54 新增）

---

## Stage 5: 交付

- [ ] 逐路径 `git add`（仅 P54 相关，**严禁 `git add -A`**）→ `git diff --cached --name-status` 复核
- [ ] `git commit -F <msgfile>` → `git push origin main`
- [ ] 验证 `git log -1` + 远端 `main` 更新
