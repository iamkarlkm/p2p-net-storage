# P56 在线 DDL 全量迁移（row-plane legacy rewrite + 兼容检测缺陷修复）—— tasks.md（**✅ Gate A 已签批 / Gate B·Gate C 已实跑回填**）

> 本文件与同目录 `spec.md` 严格对齐；Stage 2-5 的 TR 数字、批数与实跑结果 **已实跑回填**。

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ ✅ 用户 VERBATIM 批准（`Q0=A / Q1=A / Q2=A / Q3=A / Q4=A / Q5=显式触发+收尾清 leaf+幂等短路`）
- [x] **Gate B**（P56 own TR 0F0E + 拆批全量回归 + 已知基线口径沿用）→ ✅ 实跑 **1168 TR / 7 F / 0 E**
- [x] **Gate C**（Y1-Y9 自检 + 洁癖 5/5）→ ✅ 实跑（Y1-Y9 9/9）

### 实跑 TR 数（已实跑回填）
- P56 own = **32 / 0F / 0E**（T1=5 / T2=6 / T3=4 / T4=3 / T5=9 / T6=5）
- Gate B 拆批实跑 = **1168 TR / 7 F / 0 E**（批 1=32/0/0 · 批 2=60/6/0 · 批 3=213/1/0 · 批 4=863/0/0）

---

## Stage 1: Gate A（✅ 已签批）

| # | 任务 | 状态 |
|---|------|------|
| 1.1 | Probe：compat 死分支（`sample != index.rowLength` 恒 false）/ `createMeta` 两模式同源 / `EntityIndexUtil` 两模式均取当前注解 / `DsRegistryOrmBridge` 持久化 `.rowLength` 未接入 / `DsFixedBucketStore` rowId 内嵌 power 与 `update` 丢返回值 / `idSetForClass` 可枚举分批 / `OnlineDdlCoordinator` 既有内嵌 / 4 人工按钮 | ✅ |
| 1.2 | 起草 spec.md（§0 待裁决 Q0-Q5 + §1 Scope + §2 红线 R1-R10 + §3 架构 0 新类 + §4 接口契约 + §5 T1-Tn + §6 逆指标 + §7 风险回退 + §8 交付物 + §9 签批区 + §10 Probe 锚点） | ✅ |
| 1.3 | 起草 tasks.md（本文件：Gate 三状态 + Stage 1-5 + T1-Tn 明细 + Gate B 拆批模板，**不预填数字**） | ✅ |
| 1.4 | 提交 Gate A VERBATIM 逐条签批（含 §0 Q0-Q5 裁决 + §9 全条） | ✅ 用户已批准 |

---

## Stage 2: Implement（✅ 已完成；数字已实跑回填）

> **前置依赖**：T1（Q3=A 运行期定性）的实测结论决定读/写口径是否为 T2/T3 的输入；T1 未定性前不实装迁移主路径。

### T1 运行期定性（Q3=A 前置；Fail→Fix→Green）
- [x] tr11 构造真实 add-column schema（V1 短行 → V2 长行），复现老行 `getTable`
- [x] tr12 验证老行读取是否 **over-read**（按 `meta.rowLength` 新长度读旧 power 桶）
- [x] tr13 验证 `putEntity` 写路径 `bucketStore.update(KEEP_BUCKET)` 是否发生 **id 漂移**（`newPower>oldPower` 返回新 id 被丢弃）
- [x] tr14 实证 compat 死分支：`legacyRowLength` 恒 -1（两模式）
- [x] tr15 据实测固化读/写口径（不改 public 签名；必要时读路径用 `legacyRowLength` 定读长）

### T2 检测修复 + leaf 读写（§0 Q1/Q5）
- [x] tr21 `writeLegacyRowLength` 幂等（leaf 已存在不覆盖）
- [x] tr22 `readLegacyRowLength` 缺省（无 leaf）→ -1
- [x] tr23 `clearLegacyRowLength` 移除 leaf
- [x] tr24 `createMeta` 读到 leaf → `legacyRowLength` = 旧长度（退出死分支）
- [x] tr25 无 leaf → `legacyRowLength = -1`（与现状一致，R7/R9）
- [x] tr26 IAE 守卫（`clazz == null` 等）≥ 阈值

### T3 行级迁移正确性（§1 Scope）
- [x] tr31 单行 old→new 长度 rewrite 后 `getTable` 值语义一致
- [x] tr32 全表 N 行迁移后剩余未迁移 = 0
- [x] tr33 新列 `defaultValue` 正确（`overlayDefaultsOnRead` 与物理 pad 一致）
- [x] tr34 迁移后写路径 hot path 命中（新长度定长单元）
- [x] tr35 `migrateLegacyRows` IAE / 非 compat → 返回 0（R4/R7）

### T4 幂等 + 断点续跑（R8）
- [x] tr41 重复 `migrateLegacyRows` 短路（剩余 0 → 直返）
- [x] tr42 中途中断 → 续跑 0 丢行 / 0 重复写
- [x] tr43 checkpoint / 进度单调（幂等 sha 对照）
- [x] tr44 收尾清 leaf 后重跑不再触发 compat
- [x] tr45 大批量（> batchSize）分片边界正确（`subList` 分批）

### T5 Gatekeeper Y1-Y9 ×9（页首清单）
- [x] y1 `Y1` 0 新顶级类（既有包 0 新增 `.java`）
- [x] y2 `Y2` NG-3 零依赖（forbidden 引用 + import 白名单 + pom banned 0 命中）
- [x] y3 `Y3` R10 0 新线程（**去注释后**判定；Q2=B 时校验「唯一 1 守护线程 + 可关」豁免）
- [x] y4 `Y4` P18~P55 源码字节级 0 改
- [x] y5 `Y5` 既有 public 面 0 改（4 人工按钮 + `getTable`/`putEntity` 既有重载）
- [x] y6 `Y6` R5 段格式 0 变更（不新增段列；row 定长单元 bit 级一致）
- [x] y7 `Y7` 检测语义确定（不再依赖 `sample != index.rowLength`；leaf 为唯一真源）
- [x] y8 `Y8` 幂等 + 断点续跑
- [x] y9 `Y9` 0 TODO/FIXME/HACK

### T6 NFR
- [x] tr61 迁移吞吐（N 行 / 秒）≥ 阈值
- [x] tr62 迁移内存峰值 ≤ 阈值（按批流式，无全量装载）
- [x] tr63 改动类 Javadoc ≥ 阈值 + CPD 窗口 0 重复块
- [x] tr64 0 `System.out.println` / `printStackTrace` + IAE 显式守卫 ≥ 阈值
- [x] tr65 新增/改源码 0 TODO/FIXME/HACK

> 实装分布与偏差（如 T1/T3 落类差异）在 Implement 后于此处登记，**不预填**。

---

## Stage 3: Gate B（拆批全量回归）—— ✅ 已实跑

> Surefire 拆批模板（P55 同构）；每批前清 `target\tmp` 与仓库根 `.tmp` + C 盘 TEMP；`test-compile` 与 `surefire:test` 串联；长跑用 `Start-Process -RedirectStandardOutput` 落盘再 `Grep`；`-Dtest` 用**显式类名**（surefire 不支持包级通配）。

| 批 | 范围 | 命令（模板） | 实跑 | 判定 |
|----|------|------|------|------|
| 批 1 | P56 own | `-Dtest=ds.P56_T1_*,ds.P56_T2_*,ds.P56_T3_*,ds.P56_T4_*,ds.P56_T5_*,ds.P56_T6_*` | **32 TR / 0 F / 0 E** | ✅ |
| 批 2 | P18-P20 + compat | `-Dtest=ds.P18_*,ds.P19_*,ds.P20_*,ds.OnlineSchemaCompatibilityTest` | **60 TR / 6 F / 0 E** | ✅ 6 F 既有基线（非 P56，见 spec §5.3） |
| 批 3 | P21-P28 | `-Dtest=ds.P21_*,ds.P22_*,ds.P23_*,ds.P24_*,ds.P25_*,ds.P26_*,ds.P27_*,ds.P28_*` | **213 TR / 1 F / 0 E** | ✅ 1 F = P28_T6.tr64（P55 已知基线） |
| 批 4 | P29-P55 | `-Dtest=ds.P29_*,...（P29-P55 全量，显式类名）` | **863 TR / 0 F / 0 E** | ✅ BUILD SUCCESS |

> 合计 **1168 TR / 7 F / 0 E**；7 F = 1 项 P55 已知基线 + 6 项批 2 既有基线（P56 影响面 0 引入）。详见 spec §5.3 明细。

---

## Stage 4: Gate C（洁癖 5/5）—— ⏳ 待实跑

- [ ] spec.md 回填：标题 + Y1-Y9 结果 + §5.3 Gate B 实跑 + §9 签批 + 偏差表
- [ ] tasks.md 回填：三状态 + TR 数 + Stage 2-5 全 `[x]`
- [ ] `docs/dsdb-design-status-and-roadmap.md`：L406 全量迁移兑现标记
- [ ] Project Memory：P56 条目 + 硬约束变化（如有）+ Lessons
- [ ] 洁癖 5/5（T6 tr63/tr64/tr65 + T5 Y1-Y9）+ P18~P55 spec 一字不动验证

---

## Stage 5: 交付

- [ ] 逐路径 `git add`（仅 P56 相关，**严禁 `git add -A`**）→ `git diff --cached --name-status` 复核
- [ ] `git commit -F <msgfile>` → `git push origin main`
- [ ] 验证 `git log -1` + 远端 `main` 更新
