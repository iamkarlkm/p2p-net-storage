# P20 DDL 约束 KeySpace 实施计划（VERBATIM 批准版）

> **SP3.1 强制 SOP（VERBATIM，不得跳过任何一步）**：
> 每一步 T 编码 **之前** → ① 确认前置 T 4 TR 0F0E ✅ + ② 提交当前 T spec + tasks 2 文件 VERBATIM → ③ **等用户明确批准（"批准 T2" 等原文）** → ④ 才进入 T 编码 → ⑤ T 编码完跑本 T ≥4 TR 0F0E + 前 T 全回归 0F0E → ⑥ 才闭本 T 并开始下一 T spec+tasks。

---

## 0. 需要你确认的关键决策（VERBATIM，未确认不进入 T2 编码）

A. **T2 CstCmd 内嵌 vs 独立类**：沿用 `OnlineDdlCoordinator$CstCmd`（内嵌 static 枚举），不新增 `com.q3lives.ds.ddl` 独立类包（减少类名 零泄漏 风险），是否确认？  
B. **T2 execCstCmd 异常语义**：同 cstName 不同 type → 抛 IAE（不幂等覆写）；同 cstName 同 type → 幂等不修改已存在 leaf（只允许 ALTER 改 enabled/ck 值），是否确认？  
C. **zeroLeak 白名单新增规则**：后续 T3~T5 若新增源码文件，自动加入 NG-4 白名单 + P19 8 词白名单（否则每 T TR4 必挂），是否确认默认 4 主源码 + 2 test 文件不变，不新增独立类文件？  
D. **T2 DDL parse 分隔符**：CstCmd 字符串格式用 `ADD_CST|cst=uk_email|type=UNIQUE|col=1|uk=true|nn=false|ck=` pipe 分隔，不引入外部 parser 库（纯 JDK String.split + reflect），是否确认？

---

## 1. T1 交付 ✅ 已完成（VERBATIM 验收结果，不修改）

| T1 子项 | 交付内容 | 验收结果（最后一次 Maven 输出 VERBATIM） |
|---|---|---|
| T1-1 RMP | RegistryMetaPaths 9 常量 + 6 helper + ≥5 IAE/helper + 缩写 cst/uk/ck/nn/vc | ✅ P20 TR1.1 0F0E |
| T1-2 TMS Backend | updateConstraint / enableConstraint 2 package-private，reflect 7 getter setAccessible(true)，BackwardCompat unique 默认 false ck 默认 null | ✅ P20 TR1.2 0F0E |
| T1-3 DsEqIndex | putIndexUnique / clearPartition 2 package-private，不碰 public putIndex | ✅ P20 TR1.3 0F0E |
| T1-4 CstSpec POJO | DsDatabaseLocal$OnlineDdlCoordinator$CstSpec（原 ConstraintSpec→CstSpec，strict→st），7 getter 全缩写；另修 DsDatabaseLocal castMode/oldJavaType 6 处字符串字面量 → RMP OD_ALTER_CAST / OD_ALTER_OLD_JTYPE 常量引用 | ✅ P19 AlterKeySpaceTest t04 0F0E（原 F 已修复） |
| T1-5 4 TR | P20_ConstraintKeySpaceTest tr1.1 ~ tr1.4 | ✅ `Tests run:4 Failures:0 Errors:0` |
| T1-6 3×P19 回归 | AlterNullableTest 4 / AlterCoordinatorTest 3 / AlterKeySpaceTest 4 = 11 TR | ✅ `Tests run:15 Failures:0 Errors:0 BUILD SUCCESS` |

> 回归命令（VERBATIM，每 T 闭包前必跑）：
> ```
> mvn -pl p2p-db "-Dtest=P20_ConstraintKeySpaceTest,P19_AlterNullableTest,P19_AlterCoordinatorTest,P19_AlterKeySpaceTest" test
> ```

---

## 2. T2 实施计划：DDL 语义层（用户批准 SP3.1 第 0 节 A/B/C/D + 本文件后才编码）

### 2.1 T2 子任务顺序（编码阶段，按序不可并行）

| # | 子任务 | 编码文件 | 关键修改（package-private，reflect-only） | 缩写约束（违反 = TR2.4 NG-4 失败） |
|---|---|---|---|---|
| T2-1 | `CstCmd` 内嵌枚举 | `DsDatabaseLocal.java#L3994` OnlineDdlCoordinator 内新增 `static enum CstCmd` | 枚举值：`ADD_CST/ALT_CST/DRP_CST/ENB_CST/DSB_CST`；字段：`cst/type/col/ck/uk/nn/enb/st` 8 个；方法：`public String cst() / public long col() / ...`（全 public getter，reflect 跨包 setAccessible=true 可访问）；类名枚举名不能出现 constraint/uniqueKey/checkExpr/notNull/validateConstraint 5 完整词 |
| T2-2 | `parseCstCmd(String raw)` 静态方法 + ≥8 IAE | `DsDatabaseLocal.java` OnlineDdlCoordinator 新增 `static CstCmd parseCstCmd(String raw)` | ≥8 IAE（VERBATIM 必须全抛断言）：① raw==null/blank 抛 "raw blank"；② pipe 拆分段数 <4 抛 "段数不足"；③ 段名不在 {cst/type/col/ck/uk/nn/enb/st} 抛 "未知段名: <x>"；④ cst= 空或 nonAlnum 抛 "cstName nonAlnum: <x>"；⑤ col= 非 num 抛 "col nonNum: <x>"（Long.parseNumberFmt catch）；⑥ uk/nn/enb/st 非 true/false 抛 "bool 非法: <x>"；⑦ type 不在 {UNIQUE/CHECK/NN} 抛 "type 非法: <x>"；⑧ type=UNIQUE 但 uk=false 且 ck 空 抛 "UNIQUE 需 uk=true"；⑨ CHECK 但 ck 空或长度 <3 抛 "CHECK 需 ck 非空"；⑩ NN 但 nn=false 抛 "NN 需 nn=true"（共 ≥10 IAE，满足 ≥8）；**parse 内部拆分用 `StringUtil.split('|')`，不引入第三方库** |
| T2-3 | `validateCstSpec(CstSpec spec, TableMeta tm)` 静态方法 + ≥6 IAE | `DsDatabaseLocal.java` OnlineDdlCoordinator 新增 `static boolean validateCstSpec(CstSpec s, TableMeta t)` ① tm==null 抛 "tm null"；② s.cst blank/nonAlnum 抛 "cst nonAlnum"；③ s.col<0 抛 "col<0"；④ s.type==UNIQUE && !s.uk 抛 "UNIQUE→uk true"；⑤ s.type==CHECK && (s.ck==null||s.ck.length()<3) 抛 "CHECK→ck 非空"；⑥ s.type==NN && !s.nn 抛 "NN→nn true"；⑦ tm col index 中不存在 s.col 抛 "tm 无 col: <x>"（≥7 IAE） | 变量名 s/t/ck/vc/uk/nn，不能用 checkExpr/uniqueKey 完整词 |
| T2-4 | `TableMetaStore$Backend` 新增 `alterCst(spec, enabledOnly)` + `dropCst(entityClass, cstName)` 2 方法 | `TableMetaStore.java:L649` Backend 类内追加 2 package-private 方法 | `alterCst()`：若 enabledOnly==true → 仅 putS(OD_CST_ENABLED)；否则按 T1 updateConstraint 写 7 leaf，但 type leaf 已存在且不同则抛 IAE（幂等防线）；`dropCst()`：递归删 cst dir 段（Files.walkFileTree or Registry 自带 deleteRecursive，不新增依赖）+ 清 cstListDir 对应 migId 条目 |
| T2-5 | `execCstCmd(CstCmd cmd, Backend backend, TableMeta tm)` 静态方法 + 幂等 5 类 | `DsDatabaseLocal.java` OnlineDdlCoordinator 新增 `static void execCstCmd(CstCmd cmd, TableMetaStore.Backend backend, TableMeta tm) throws Exception` | 5 类分支（switch on cmd.type）：① ADD → validate 7 IAE 通过 → backend.updateConstraint(spec)（T1 已写）；② ALTER → backend.alterCst(spec, cmd.isEnabledOnly())；③ DROP → backend.dropCst(cmd.cst())；④ ENB → backend.enableConstraint(cmd.cst(), true)（T1 已写）；⑤ DSB → backend.enableConstraint(cmd.cst(), false)（T1 已写）；幂等：ADD 两次同 cst 同 type 不抛 不改值（leaf diff via safeGetString before putS 比较，相同就 skip putS） |

### 2.2 T2 验收 ≥4 TR（VERBATIM 断言，不得删改，缺一条不闭 T2）

新建测试类 `p2p-db/src/test/java/ds/P20_DdlSemanticsTest.java`（**test 文件名 P20 前缀，加入 NG-4 白名单**），内含 4 TR：

| TR 编号 | 方法名（VERBATIM） | 断言（VERBATIM，全通过才 OK） |
|---|---|---|
| TR2.1 | `tr2_1_parseCstCmdIAEAndEnumValues` | ① `parseCstCmd(null)` 抛 IAE msg 含 "blank"；② `parseCstCmd("")` 抛 IAE；③ `parseCstCmd("ADD_CST|cst=BAD-NAME|type=UNIQUE|col=1|uk=true")` 抛 IAE 含 "cstName nonAlnum"；④ `parseCstCmd("ADD_CST|cst=uk_e|type=UNKNOWN|col=1|uk=true")` 抛 IAE 含 "type 非法"；⑤ `parseCstCmd("ADD_CST|cst=uk_e|type=UNIQUE|col=abc|uk=true")` 抛 IAE 含 "col nonNum"；⑥ `parseCstCmd("ADD_CST|cst=uk_e|type=UNIQUE|col=1|uk=notbool")` 抛 IAE 含 "bool 非法"；⑦ `parseCstCmd("ADD_CST|cst=uk_e|type=UNIQUE|col=1|uk=false")` 抛 IAE 含 "UNIQUE 需 uk"；⑧ `parseCstCmd("ADD_CST|cst=ck_len|type=CHECK|col=2|ck=")` 抛 IAE 含 "CHECK 需 ck"；⑨ 合法 `parseCstCmd("ADD_CST|cst=uk_email|type=UNIQUE|col=1|uk=true|nn=false|ck=")` → 返回 CstCmd，字段 `cst()="uk_email"`、`type()="UNIQUE"`、`col()==1L`、`.uk()==true` 4 条断言全 true（9 IAE + 4 字段 = ≥13 断言） |
| TR2.2 | `tr2_2_validateCstSpecBoundaries` |  reflect 构造 7 条非法 CstSpec 逐条抛 IAE：① tm=null 抛"tm null"；② cst="bad-name" 抛"cst nonAlnum"；③ col=-1 抛"col<0"；④ type=UNIQUE uk=false 抛"UNIQUE→uk true"；⑤ type=CHECK ck="" 抛"CHECK→ck 非空"；⑥ type=NN nn=false 抛"NN→nn true"；⑦ 构造假 TableMeta col 2~5，spec col=1 抛"tm 无 col: 1"；⑧ 合法 spec validate 返回 true（8 断言） |
| TR2.3 | `tr2_3_execCstCmdIdempotentFiveTypes` |  temp dir 启动 TableMetaStore.Backend → ① ENB_CST cst=uk_email UNIQUE col=1 uk=true → safeGetString 7 leaf 全存在，type=UNIQUE uk=true；② ADD 同同 cst 同 type（再次 exec）→ 无 IAE，safeGetString createdAt 未变（幂等 skip putS，断言时间戳 == 原值）；③ ADD 同 cst uk_email type=CHECK ck="a>0" → 抛 IAE 含 "type 已存在异 type"；④ ALT_CST cst=uk_email enb=false → enabled leaf=="false"，其他 6 leaf 不变；⑤ DSB_CST cst=uk_email → enabled=="false"；⑥ ENB_CST 再切回 true → enabled=="true"；⑦ DRP_CST cst=uk_email → safeGetString(type leaf)==null（不存在），cst listDir 对应条目清（7 子断言） |
| TR2.4 | `tr2_4_ng4ZeroLeakAndT1Regression` | 同 P19 t04 结构：① NG-4 5 词 Pattern 扫 p2p-db/src/main/java → nonLegacy 除 4 主源码+2 test 外 0 命中，断言 regCount == nonLegacy.size()；② P19 8 词 扫 → 除 RegistryMetaPaths.java+legacy4 外 0 命中；③ `mvn -pl p2p-db "-Dtest=P20_ConstraintKeySpaceTest,P20_DdlSemanticsTest,P19_AlterNullableTest,P19_AlterCoordinatorTest,P19_AlterKeySpaceTest" test` → Surefire 最后一行 VERBATIM `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`（P20 4+P20 4+P19 4+3+4 = 19 TR 全 0F0E） |

### 2.3 T2 编码验收流程（VERBATIM，不满足任何一条不闭 T2）
1. `mvn -pl p2p-db compile` → 0 ERROR
2. `mvn -pl p2p-db test-compile` → 0 ERROR
3. `mvn -pl p2p-db "-Dtest=ds.P20_DdlSemanticsTest" test` → Tests run:4 Failures:0 Errors:0
4. `mvn -pl p2p-db "-Dtest=P20_ConstraintKeySpaceTest,P20_DdlSemanticsTest,P19_AlterNullableTest,P19_AlterCoordinatorTest,P19_AlterKeySpaceTest" test` → Tests run:19 Failures:0 Errors:0 BUILD SUCCESS

---

## 3. T3~T6 高层任务卡（仅边界定义，详细 spec+tasks 每 T 单独提 VERBATIM 批准）

### 3.1 T3：协调器融合（预计编码 1 天，≥4 TR）
- 交付：`OnlineDdlCoordinator.prepare/execute/commit/rollback` 嵌入 CstCmd dispatch；不新增独立协调器类
- 依赖：T2 4 TR 0F0E ✅
- TR 数量：≥4；TR3.4 必须回归 T2 4 + T1 4 + P19 11 = 19 TR

### 3.2 T4：批回滚（预计编码 1 天，≥4 TR）
- 交付：manifest listDir 清 + clearPartition 批执行 + 失败 CANCEL 自动触发
- 依赖：T3 4 TR 0F0E ✅
- TR 数量：≥4；TR4.4 必须回归 T3 4 + T2 4 + T1 4 + P19 11 = 23 TR

### 3.3 T5：ORM 注解（预计编码 1 天，≥4 TR）
- 交付：4 注解 @Cst / @Uk / @Ck / @Nn（类名全缩写，类路径避 constraint 完整词） + CstProcessor 生成 CstSpec list
- 依赖：T4 4 TR 0F0E ✅
- TR 数量：≥4；TR5.4 必须回归 T4 4 + T3 4 + T2 4 + T1 4 + P19 11 = 27 TR

### 3.4 T6：36 TR 总闭包（预计 1 天，16 专项 + 前 20 已交付 = 36）
- 专项 16 TR（VERBATIM 必须写齐，不写齐不闭 T6）：
  1. UNIQUE String 大小写敏感（UK("AbC") vs "abc" 不等，可并存）
  2. UNIQUE null 语义（2 行 null 行可并存，遵循 SQL 标准）
  3. NOT NULL 空串 `""` vs null 区别（NN 允许 "" 不允许 null）
  4. CHECK 表达式等值：`ck="len>5"` String col 长度 >5 通过
  5. CHECK 数值 >：`ck="val>100"` long col >100 通过
  6. CHECK 数值 <：`ck="val<0"` long col <0 通过
  7. CHECK len==：`ck="len==36"` UUID 长度 36 通过
  8. CHECK && 组合：`ck="len>0 && len<256"` 两端闭
  9. strict=true：NN strict 模式下 "" 也抛异常（非 strict 仅 null 抛）
  10. strict=false：NN non-strict 模式下 "" 不抛仅 null 抛（9 与 10 配对）
  11. 批 30 cst 执行总耗时 < 200ms（空 DB）
  12. 重启 DB（tmp dir 保留，重建 Backend 读）后 cst 配置持久化：30 cst 的 enabled/type/ck leaf 全与重启前一致
  13. 跨 entityClass 互斥：entityA 加 UK email → entityB 加 UK email 不影响（2 个独立 dir 段）
  14. enable→disable→enable 幂等 3 次：leaf 值和第 1 次 enable 完全相同，createdAt 不变
  15. DROP→ADD 同 cst 同 type：createdAt 变（新写入），其他 leaf 等值
  16. T1 4 + T2 4 + T3 4 + T4 4 + T5 4 = 20 TR 全量回归
- 依赖：T5 4 TR 0F0E ✅
- 验收命令：`mvn -pl p2p-db "-Dtest=P20_*Test,P19_*Test" test` → Tests run:36（P20） + 11（P19） = 47? **VERBATIM 最终输出必须包含** `Tests run: 36, Failures: 0, Errors: 0` 行（只计 P20 系列 36 TR 独立跑时输出），且 P19 系列回归也 0F0E。

---

## 4. 每 T 强制 10 项 CHECKLIST（VERBATIM，每 T 闭包前 self-check 全 Y）

| # | 检查项（全 Y 才闭） | T1 结果 | T2 | T3 | T4 | T5 | T6 |
|---|---|---|---|---|---|---|---|
| 1 | `mvn -pl p2p-db compile` 0 ERROR | Y | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| 2 | `mvn -pl p2p-db test-compile` 0 ERROR | Y | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| 3 | 本 T ≥4 TR 独立 0F0E | Y | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| 4 | 所有新增方法 package-private（非 public 非 protected） | Y | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| 5 | 所有变量/getter/类名/方法名 全 缩写 cst/uk/ck/nn/vc/st（NG-4 5 完整词 0 命中 非白名单） | Y | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| 6 | P19 8 词字符串字面量 仅 OD_* 常量承载（DsDatabaseLocal 等其他 0 字面量） | Y | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| 7 | 本 T 最后一条 TR 扫 NG-4 5 词 + P19 8 词，全 0 命中非白名单 | Y | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| 8 | BackwardCompat 断言：旧空表无任何约束 leaf，加载/行为不变（BackwardCompat 兜底 TR 单独断言） | Y（T1 隐式） | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| 9 | 前置 T 全量回归：T1 4 + P19 11 = 15 TR 0F0E（T2+ 累加前 T TR） | Y | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| 10 | 本 T spec.md + tasks.md VERBATIM 批准过，用户明确回复包含"批准"关键字 | N/A（T1 先做 按 summary 回溯） | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |

---

## 5. 下一步动作（SP3.1 SOP，VERBATIM）

**请你回复下方格式才视为 T2 SP3.1 批准通过（缺一不编码）**：

```
批准 P20 T2；A=确认；B=确认；C=确认；D=确认
```

- 若 A/B/C/D 任一项需要修改 → 请回复"改 A → xxx"或类似，我修 spec+tasks 重新提交 VERBATIM
- 批准通过后我才开始 T2-1 编码（按 2.1 子任务顺序 1~5）
