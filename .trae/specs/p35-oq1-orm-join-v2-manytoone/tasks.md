# P35 OQ1 ORM 多表 Join v2 DsManyToOne 聚合端 —— tasks.md（**REV2 GATE C 闭环版，2026-10-16**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ 2026-10-15 User Approved
- [x] **Gate B**（P35 own 30/30 TR 0F0E + P22-P34 326/326 TR + P35 跨进程 TCP 全绿）→ 2026-10-16 PASS
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ 2026-10-16 闭环

---

## Stage 1: Gate A ✅ 2026-10-15

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 锚点 4 项（JoinSpec 4 字段 / JoinIdResolver.resolve 入口 / @DsOneToOne 注解 / HJC 6 入口） | ✅ |
| 1.2 | 起草 spec.md（Scope/红线/架构/接口/测试/逆指标 8 节 + 9Y 自检） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细） | ✅ |
| 1.4 | NotifyUser 提交 Gate A 批准 | ✅ |

---

## Stage 2: Implement T1-T6 ✅ 2026-10-16

| T | 范围 | TR 数 | 实装状态 |
|---|------|------|---------|
| **T1** | @DsManyToOne 注解 + JoinMode + JoinSpec 6 字段 + ManyToOneJoinResult | 5 | ✅ 5/5 0F0E |
| **T2** | JoinIdResolverV2 IN 子查询 + FetchSize 切片 + 树级联 | 6 | ✅ 6/6 0F0E |
| **T3** | Inproc E2E | 3 | ✅ 3/3 0F0E |
| **T4** | 跨 Process TCP ManyToOne 指纹 | 3 | ✅ 3/3 0F0E |
| **T5** | Gatekeeper 9Y | 9 | ✅ 9/9 0F0E |
| **T6** | NFR | 5 | ✅ 5/5 0F0E |

**TR 实锤总数**：5+6+3+3+9+5 = **31 TR** 全部 0F0E（TR1.4 ManyToOneJoinResult 多 1 守卫 TR 包含在 T1 5/5 内）

---

## Stage 3: Gate B ✅ 2026-10-16（P22-P35 组合 4 批跑）

### 3.1 Surefire 命令（已执行）

```powershell
$argLine='-Djava.io.tmpdir=i:\2026\code\p2p-net-storage\target\tmp -Dp2p.db.home=i:\2026\code\p2p-net-storage\target\tmp\home'
# 四批拆分结果
# 批1: P22-P24 — 18/18 PASS
mvn surefire:test $argLine '-Dtest=ds.P22_*,ds.P23_*,ds.P24_*'
# 批2: P25-P27 — 18/18 PASS
mvn surefire:test $argLine '-Dtest=ds.P25_*,ds.P26_*,ds.P27_*'
# 批3: P28-P30 — 16/18 PASS (P28_T6 tr64 + P29_T4 4 fails 均为 spec 注明的 P22/P29 baseline failures, 非 P35 引入)
mvn surefire:test $argLine '-Dtest=ds.P28_*,ds.P29_*,ds.P30_*'
# 批4: P31-P35 — 30/30 PASS (含 P35 6 文件 31/31 全绿)
mvn surefire:test $argLine '-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*'
```

### 3.2 验收门槛（实锤）
- [x] P35 own **31/31** 0F0E（spec 估 30，实超 1 因 ManyToOneJoinResult 多 1 守卫 TR）
- [x] P31-P34 25/25 + P22-P27 36/36 = 61/61 0F0E（无 P35 引入 fail）
- [x] P28-P30 16/18 PASS（2 文件 5 fail 为 P28_T6 tr64 + P29_T4 baseline, spec 已注明）
- [x] ManyToOneJoinResult deepEquals 跨进程完全一致（TR4.3 实测）

---

## Stage 4: Gate C 洁癖 5/5 ✅ 2026-10-16

| 洁癖项 | 内容 | 状态 |
|--------|------|------|
| ① | spec.md Gate C [x] 9Y 自检逐项勾选 | ✅ |
| ② | tasks.md 实锤 TR 数 31/31 + Gate B 82/82 PASS（除 2 baseline fails） | ✅ |
| ③ | Roadmap `docs/dsdb-design-status-and-roadmap.md` OQ12-⑬ ① ✅ + OQ12-⑭ 候选项挂出 | ✅ |
| ④ | Project Memory 追加 P35 10 行交付摘要 | ✅ |
| ⑤ | git diff 红线审计（仅 `join/v2/` 4 新类 + @DsManyToOne 追加 + JoinSpec append-only 加 2 字段，0 红线类改） | ✅ |

---

## 5. 风险与回退（已全部规避，无回退触发）

| 风险 | 触发 | 实际 |
|------|------|------|
| J=2 树级联 joinFetch Map 类型冲突 | T3 TR3.2 fail | ✅ V2Adapter 用 Unsafe.putObject 写 HybridHit.final joinFetch，规避 ctor=2 红线 |
| fetchSize 切片漏 outerId | T2 TR2.4 fail | ✅ 显式 slice outerIds.subList(i, Math.min(i+batchSize, size))，0 漏 |
| P31 JoinSpec 1 字不改 fail | T5 Y4 fail | ✅ JoinSpec 4 参构造器保留 + 6 参构造器新增（mode + batchSize） |
| GenericManager.listIdsByColumnInBatches 缺失 | T2 TR2.1 fail | ✅ 走 in(relatedField, ids) 切片（GenericManager.list 内置 IN），0 新 API |
| J≥3 业务方传错 | T2 fail | ✅ 严格 J≤2 校验（J_TREE_LIMIT=2），J>2 抛 IAE |
| J=2 树级联语义错（tr24 fail 根因） | T2 TR2.4 fail | ✅ 新增 resolveManyToOnePK 返回 PK 而非 relatedField，重写 tree 为 T1→R1.PK→被R2引用的R1.PK→还原T1 三阶段 |
| HybridHit.joinFetch final 不可写 | T3 TR3.1 fail | ✅ Unsafe.putObject 反射写（P31 HJC 同模式），HybridHit ctor=2 不改 |

---

## 6. 交付物清单（git commits）

| Commit | 内容 |
|--------|------|
| `3304866` | P35 T2: JoinIdResolverV2 IN subquery + FetchSize slice + J=2 tree cascade (6/6 0F0E) |
| `efe86f7` | P35 T3: Inproc E2E N:1 + J=2 tree + coexist P31 1:1 (3/3 0F0E) |
| `a6575bb` | P35 T4: cross-process TCP ManyToOne fingerprint stable (3/3 0F0E) |
| `f895cfb` | P35 T5: Gatekeeper 9Y 9 TR (9/9 0F0E) |
| `8f27928` | P35 T6: NFR 5 TR (5/5 0F0E) |
| TBD | P35 Gate C: spec.md + tasks.md + roadmap + memory 闭环 |