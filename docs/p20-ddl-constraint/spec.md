# P20 DDL 约束 KeySpace + Backend 详细设计（VERBATIM 批准版）

## 0. 核心原则（VERBATIM，不得修改）

1. **SP3.1 流程**：6 T 分阶段；每 T 必须 **先提交 spec.md + tasks.md 2 份 VERBATIM 批准 → 批准后才编码 → 编码完本 T ≥4 TR 全 0F0E + 前 T 回归 0F0E 才闭 T**。
2. **NG-4 zeroLeak 双词表（VERBATIM，不得增减）**：
   - **NG-4 5 词（P20 强制）**：`constraint` / `uniqueKey` / `checkExpr` / `notNull` / `validateConstraint` — 只能出现在 4 白名单主源码 + 2 已知 test 文件，其余文件（含所有 POJO/getter/变量名）**一律缩写**（cst/uk/ck/nn/vc）。常量 leaf 字面量值（如 `OD_CST_CK="checkExpr"`）作为 leaf name，**P19 风格显式豁免**，不缩写。
   - **P19 8 词（T1 回归强制）**：`manifest` / `schemaVersion` / `tombstones` / `migrateId` / `onlineDdl` / `alterCols` / `castMode` / `oldJavaType` — 只能出现在 `RegistryMetaPaths.java`（或 legacy 4 文件），其他源码 **必须通过 RegistryMetaPaths `OD_*` 常量引用**，不能直接写字符串字面量。
   - **白名单（VERBATIM）**：4 主源码 = `RegistryMetaPaths.java` + `TableMetaStore.java` + `DsEqIndexStore.java` + `DsDatabaseLocal.java`；2 test = `P20_ConstraintKeySpaceTest.java` + `DsRegistryExtAttrs_P7_Tgt5_PerfDeep_16x16_MatrixStressTest.java`。
3. **侵入性最小化（VERBATIM）**：T1~T6 所有新增方法 **必须 package-private（或更窄）**，测试用 **reflect setAccessible(true)** 调用；**不得新增 public API**（除非 T5 ORM 层明确需要）。
4. **BackwardCompat 兜底（VERBATIM）**：UNIQUE 默认 false，ck 默认 null，enabled 默认 true；所有旧数据文件**不包含任何约束段**时，加载/查询/写入行为必须与无约束完全等价，不能因读 null leaf 抛 NPE。
5. **每 T 强制 ≥4 TR（VERBATIM）**：TR1 API 存在性 + IAE 边界，TR2 主路径 1 条，TR3 主路径 2 条/异常分支，TR4 zeroLeak 双词表扫描 + 回归上一 T。
6. **KeySpace 命名强制（VERBATIM）**：所有 cstName/migId/leafName **只能 [A-Za-z0-9_]**，每条 helper IAE 校验 ≥5 条（blank/null/非字母数字+_/entityClass 非法/migId 非数字 等）。

---

## 1. 背景与目标

P20 为 p2p-db 引入**表级 DDL 约束持久化 + 执行后端最小闭包**，覆盖 UNIQUE/CHECK/NOT NULL 三类，为后续 T5 ORM 层与分布式场景（T6 36 TR）打基础。T1 已完成 KeySpace + 最小 Backend 闭包，本 spec 定义 T2~T6 完整设计。

### 1.1 目标（VERBATIM，T1~T6 全周期）

- **KeySpace 持久化**：约束元数据 + 启用状态 + 校验历史 全量落 Registry，无外部 KV 依赖。
- **DDL 语义层**：`ADD/ALTER/DROP/ENABLE/DISABLE CONSTRAINT` 5 类 DDL 语义解析、校验、执行，幂等 + IAE 边界。
- **协调器**：与 OnlineDdlCoordinator 现有 4 步 prepare→execute→commit→rollback 融合，不新增外部分发器。
- **批回滚**：约束执行失败或显式 CANCEL 时，基于 clearPartition(partitionIdx) + Registry manifest 清脏数据。
- **ORM 层接入**：`@Cst` / `@Uk` / `@Ck` / `@Nn` 4 注解 + CstProcessor 自动生成 CstSpec，**避 NG-4 5 词**。
- **T6 36 TR 总闭包**：T1+T2+T3+T4+T5 各 ≥4 TR（共 20） + T6 专项 16 = 36 TR 全 0F0E。

### 1.2 非目标（本期 6 T 不做）

- FOREIGN KEY / 外键级联（T7 以后）
- 跨 entityClass 的约束全局去重（T6 36 TR 不覆盖，T7 做）
- 约束执行计划优化器（CHECK 表达式全量解释执行，不做 AST 编译）

---

## 2. 总体架构（6 T 分层）

```
┌──────────────────────────────────────────────────────────┐
│  T6  36 TR 总闭包（跨 T1~T5 全链路 + NG-4/P19 全量扫描）   │
├──────────────────────────────────────────────────────────┤
│  T5  ORM 层：@Cst/@Uk/@Ck/@Nn + CstProcessor + 注解解析器 │
├──────────────────────────────────────────────────────────┤
│  T4  批回滚：prepare/rollback manifest + 失败自动 CANCEL   │
├──────────────────────────────────────────────────────────┤
│  T3  协调器：OnlineDdlCoordinator 融合 5 DDL 命令          │
├──────────────────────────────────────────────────────────┤
│  T2  DDL 语义层：parse/validate/execute 5 DDL 类型幂等     │
├──────────────────────────────────────────────────────────┤
│  T1 ✅ Backend KeySpace：RMP 9C+6H + TMS Backend 2 方法   │
│       + DsEq 2 方法 + CstSpec POJO + 4 TR 0F0E           │
└──────────────────────────────────────────────────────────┘
```

---

## 3. KeySpace 布局（T1 ✅ 完成，VERBATIM）

Registry 根：`<registryRoot>/orm/column/<entityClass>/table/constraints/`
```
constraints/
├── <cstName>/
│   ├── type          (UNIQUE | CHECK | NN)          ← OD_CST_TYPE
│   ├── col           (colId long)                   ← OD_CST_COL
│   ├── ck            (checkExpr leaf literal, 可空) ← OD_CST_CK
│   ├── uk            (true | false)                 ← OD_CST_UK
│   ├── nn            (true | false)                 ← OD_CST_NN
│   ├── st            (true | false, strict 模式)    ← OD_CST_ST
│   ├── enabled       (true | false)                 ← OD_CST_ENABLED
│   └── createdAt     (millis long)                  ← OD_CST_CREATED_AT
├── list/             (cstName 段 ↔ migId long)      ← cstListDir
└── manifests/        (migId ↔ manifest blob)        ← cstManifestNameListDir
```

6 条 helper（RegistryMetaPaths，T1 已写）：
1. `columnTableConstraintsDir(entityClass)` — L196
2. `odCstRootDir(space, entityClass)` — constraints 完整根
3. `odCstLeaf(entityClass, cstName, subLeaf)` — 具体 leaf，≥5 IAE（entity blank / cstName blank / cstName nonAlnum / subLeaf blank / subLeaf nonAlnum）
4. `cstListDir(space, entityClass, migId)` — list 段，≥5 IAE（space blank / migId blank / migId nonNum / entity blank / null 任何参）
5. `cstDir(space, entityClass, cstName)` — cstName 段目录
6. `odCstManifestLeaf(space, entityClass, migId, leafName)` + `cstManifestNameListDir(space, entityClass)` — 回滚 manifest

9 条常量（RegistryMetaPaths，T1 已 public）：
```java
OD_CST_TYPE = "type";     OD_CST_COL = "col";     OD_CST_CK = "checkExpr";
OD_CST_UK = "uniqueKey";  OD_CST_NN = "notNull";  OD_CST_ST = "strict";
OD_CST_ENABLED = "enabled"; OD_CST_CREATED_AT = "createdAt";
OD_MANIFEST_CST = "cst_manifest";
```
> 注意：`OD_CST_CK="checkExpr"`、`OD_CST_UK="uniqueKey"`、`OD_CST_NN="notNull"` 是 **leaf literal**，P19 风格显式豁免（常量名必须缩写 cst/ck/uk/nn，值不缩写）。

---

## 4. T2 详细设计：DDL 语义层（≥4 TR，下阶段编码）

### 4.1 T2 交付物 VERBATIM

| # | 交付 | 所在文件（package-private，reflect-only） | 关键缩写约束 |
|---|---|---|---|
| T2-1 | `DDL CstCmd` enum + parse 方法 | `DsDatabaseLocal.java` → 内嵌 `OnlineDdlCoordinator$CstCmd`（不新增独立类，避类名 constraint 词） | 命令类型：`ADD_CST` / `ALT_CST` / `DRP_CST` / `ENB_CST` / `DSB_CST`（全缩写，禁完整词） |
| T2-2 | `parseCstCmd(rawCmdStr)` ≥8 IAE | `OnlineDdlCoordinator`（静态方法，package-private） | 命令段非法、缺段、cstName nonAlnum、colId nonNum、enabled nonBool、ck 空括号、UK+CK 互斥冲突、NN+CK 冲突 8 条 IAE 全抛 |
| T2-3 | `validateCstSpec(CstSpec spec, TableMeta tm)` ≥6 IAE | `OnlineDdlCoordinator`（静态方法，package-private） | tm==null、spec 字段 7 条非法校验（ck 空但 type=CHECK、uk==false type=UNIQUE 等 6 条分支） |
| T2-4 | `execCstCmd(CstCmd cmd, TableMetaStore.Backend backend)` 幂等执行 | `TableMetaStore$Backend` 新增 2 条 package-private 方法：`alterCst()` / `dropCst()`（T1 已有 updateCst + enableCst，复用） | DROP 幂等（不存在不抛）；ALTER 改 ck/uk/nn 不允许改 type；ADD 同 cstName 存在且 same type 幂等通过，异 type 抛 IAE |

### 4.2 T2 依赖（VERBATIM）：
- 只依赖 T1 ✅ 交付的：RMP 9C+6H、TMS Backend 2 方法（updateCst + enableCst）、DsEq 2 方法（putIndexUnique + clearPartition）、CstSpec POJO
- **不能触碰 public API**：`alterColumnSchema` / `initColumnSchema` / `commitColumnSchema`（T3 才融合）

### 4.3 T2 强制 4 TR（VERBATIM，编码时严格写）

| TR 编号 | 强制内容（断言 VERBATIM） | 预期 |
|---|---|---|
| TR2.1 `tr2_1_parseCstCmdIAEAndEnum` | parse 8 条非法输入每条都抛 IAE，断言 message 含段名；合法 ADD_CST cst=uk_email col=1 uk=true parse 后 CstCmd 字段 7 值全匹配 | 8 IAE ✅ + 1 合法字段全匹配 ✅ |
| TR2.2 `tr2_2_validateCstSpecBoundaries` | validate 6 条非法 spec（ck 空 + type=CHECK / uk false + type=UNIQUE / nn false + type=NN / col<0 / cstName blank / type blank）每条都抛 IAE；合法 spec+tm validate 返回 true | 6 IAE ✅ + 1 合法 true ✅ |
| TR2.3 `tr2_3_execCstCmdIdempotentFiveTypes` | reflect 调 execCstCmd：①ADD uk_email UNIQUE → Registry key 7 leaf 全写；②ADD 同 cstName 同 type 幂等不抛，leaf 值不变；③ADD 同 cstName 异 type 抛 IAE；④ALT 改 enabled=false 仅 enabled leaf 变；⑤DROP cst dir 段删，list migId 清；⑥ENB / DSB 仅 enabled 变 | 6 子断言全通过 |
| TR2.4 `tr2_4_ng4ZeroLeakAndT1Regression` | p2p-db main+test 全局 grep NG-4 5 词 非 6 白名单 0 命中；P19 8 词 grep 非 RMP+legacy4 0 命中；P20_ConstraintKeySpaceTest 4 TR + P19 3×AlterTest 11 TR 回归 0F0E | 双词表 0 命中 + 15 TR 回归 0F0E |

---

## 5. T3~T6 高层设计（VERBATIM，仅定义边界与验收 ≥4 TR）

### 5.1 T3：协调器融合（≥4 TR）
- 交付：`OnlineDdlCoordinator.prepare/execute/commit/rollback` 4 步 嵌入 CstCmd dispatch，不新增独立 Coordinator 类。
- 4 TR：TR3.1 prepare 写 4 个 manifest leaf 不写主数据；TR3.2 execute 写 cst leaf + 调 putIndexUnique 冲突抛 RuntimeException + rollback 自动清；TR3.3 commit 写 createdAt + 清 prepare manifest；TR3.4 融合 prepare/alterCols castMode 不冲突，T2 4 TR + T1 4 TR 回归 0F0E。

### 5.2 T4：批回滚（≥4 TR）
- 交付：`manifest listDir` 批清 + `clearPartition(int)` 批执行 + 失败 CANCEL 自动触发
- 4 TR：TR4.1 CANCEL prepare 未 commit 清 manifest 不碰 cst dir；TR4.2 execute 中途 UK 冲突 → rollback：原 col 旧值仍在，新 cst dir 不存在；TR4.3 多 cst 批回滚（ADD 3 cst → 第 3 个 CK 非法 → 前 2 个全部回滚）；TR4.4 T3 + T2 + T1 + P19 回归 0F0E。

### 5.3 T5：ORM 层注解（≥4 TR）
- 交付：4 注解 `@Cst` / `@Uk` / `@Ck` / `@Nn` + `CstProcessor` 生成 CstSpec list（注解名全缩写，避 NG-4 5 词）
- 4 TR：TR5.1 @Uk(col="email") 生成 CstSpec type=UNIQUE；TR5.2 @Ck(expr="len>0") + @Nn 合并 2 条 CstSpec；TR5.3 类无注解 Cst list 空；TR5.4 T4 + T3 + T2 + T1 回归 0F0E。

### 5.4 T6：36 TR 总闭包（16 专项 TR + 前 20 已交付 = 36）
- 专项 16 TR：UNIQUE String 大小写、UNIQUE null 语义、NOT NULL 空串非 null 区别、CHECK 5 类表达式（=/>/</len/&&）、strict vs non-strict 模式切换（2 条）、批 30 cst 执行性能 <200ms、重启 DB 后 cst 配置持久化、跨 entityClass 3 类互斥不影响、enable→disable→enable 幂等 3 次、T1 4 TR + T2 4 TR + T3 4 TR + T4 4 TR + T5 4 TR 全量 20 TR 回归。

---

## 6. 零泄漏双词表扫描规则（VERBATIM，每 T 最后一条 TR 必跑）

### 6.1 扫描实现（沿用 P20/P19 现有实现）：
```java
Pattern ng4P = Pattern.compile("\\bconstraint\\b|\\buniqueKey\\b|\\bcheckExpr\\b|\\bnotNull\\b|\\bvalidateConstraint\\b");
Pattern p19P = Pattern.compile("\\bmanifest\\b|\\bschemaVersion\\b|\\btombstones\\b|\\bmigrateId\\b|\\bonlineDdl\\b|\\balterCols\\b|\\bcastMode\\b|\\boldJavaType\\b");
```
- ng4P 白名单 Set = {4 主源码} ∪ {2 test}；p19P 白名单 Set = {RegistryMetaPaths.java} ∪ {legacy 4 文件：DsFullBackup / DsIncrementalBackup / DsTimelineRestore / DsDatabaseMetaMigrator}
- 命中即 FAIL，不得豁免（除 leaf literal 显式注明的常量值）。

### 6.2 缩写映射（VERBATIM，所有变量/getter/方法/类名 必须遵守）：
| 完整词 | 缩写（强制） | 例外（仅 leaf literal 值） |
|---|---|---|
| constraint | cst | `OD_CST_*` 常量值 `= "xxx"` 豁免，名不豁免 |
| uniqueKey | uk | `OD_CST_UK = "uniqueKey"` 豁免值，名 OD_CST_UK 必须缩写 |
| checkExpr | ck | `OD_CST_CK = "checkExpr"` 豁免值，名 OD_CST_CK 必须缩写 |
| notNull | nn | `OD_CST_NN = "notNull"` 豁免值，名 OD_CST_NN 必须缩写 |
| validateConstraint | vc | 无 leaf literal，全部缩写（T6 TR 专用） |
| strict | st | 字段名 st，getter st()，strict 完整词只能出现在 OD_CST_ST = "strict" 常量值 |
| castMode | — 全变量名不能出现 | 只能引用 `OD_ALTER_CAST` 常量名，字符串字面量值由常量承载 |
| oldJavaType | — 全变量名不能出现 | 只能引用 `OD_ALTER_OLD_JTYPE` 常量名 |

---

## 7. BackwardCompat 兜底（VERBATIM，每 T 必验）
- **UNIQUE 默认 false**：`if (uk) putIndexUnique(...)`；不加 UK 的代码路径完全等价 T1 前代码。
- **ck 默认 null**：`if (ck != null) putS(OD_CST_CK, ck)`；无 CHECK 的旧表 Registry 中完全没有 ck leaf。
- **NN 默认 false**：`if (nn) 执行 NN 校验`；旧 entity 不加 @Nn 时校验跳过。
- **enabled 默认 true**：读 enabled leaf 用 `safeGetString(... , "true")` 兜底；旧表不存在 enabled leaf → 默认启用（等价 T1 前无约束行为，但因 uk/ck/nn 全 false/空，实际不生效）。
- **T2~T6 每 T TR-3/TR-4 必须专门断言旧表无任何约束段数据时行为不变**。
