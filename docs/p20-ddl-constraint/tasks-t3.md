# P20 T3 DDL 协调器融合 实施任务（VERBATIM 批准版）

> **SP3.1 强制 SOP（VERBATIM）**：本 tasks.md + spec-t3.md **必须用户明确回复包含「批准 P20 T3」关键字** → 才进入 T3-1..T3-5 编码 → 最后跑 23 TR 0F0E 才闭 T3。

---

## 0. T3 关键决策（VERBATIM，4 项缺一项不进入编码，请回复 A=.. B=.. C=.. D=.. 确认）

A. **DdlCstPipe 内嵌 vs 独立类**：沿用 `DsDatabaseLocal$OnlineDdlCoordinator$DdlCstPipe`（3 层 static 嵌套），不新增 `com.q3lives.ds.cst` 独立包（减少类名 NG-4 零泄漏风险）。是否确认？  
B. **DdlCstPipe UNIQUE execute 冲突值策略**：TR3 仅测管道语义，key 值简化取 `rowId * 13 + colId`（不引入真实反序列化）；真实 ORM key 序列化留 T5。是否确认？  
C. **zeroLeak 白名单 + 主源码不变**：T3 全程零新增独立 .java；仅追加 P20_DdlCstPipeTest.java test 文件；4 主源码白名单保持不变（RegistryMetaPaths / TableMetaStore / DsEqIndexStore / DsDatabaseLocal）。是否确认？  
D. **manifest 序列化格式**：pipe 分隔 `type=X|cst=Y|col=Z|uk=..`（纯 JDK，不引 Jackson / Gson），migId = `System.currentTimeMillis()*100+seq`。是否确认？

---

## 1. 前置闭包结果（VERBATIM，修改失败 = 立即停）

| 闭包项 | 结果（最后 Maven VERBATIM） |
|---|---|
| T2 P20 4 TR 单测 | ✅ `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`（P20_DdlSemanticsTest 独立） |
| T2 前 T 回归 19 TR 总闭包 | ✅ `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0 BUILD SUCCESS`（P20 T1+T2 8 + P19 11 = 19） |
| T2 NG-4 zeroLeak | ✅ P20_ConstraintKeySpaceTest tr1.4 + P20_DdlSemanticsTest tr2.4 双 0F0E |
| T2 P19 8 词 zeroLeak | ✅ P19 AlterKeySpaceTest t04 0F0E |
| BackwardCompat 空表不变 | ✅ P19 3× AlterTest 11 TR 全 0F0E（T2 4+2 白名单未改行为） |

---

## 2. T3 子任务顺序（编码阶段，按 1→2→3→4→5 严格序，不可并行）

| # | 子任务 | 编码文件 | 关键修改（package-private，reflect-only） | 缩写约束（违反 = TR3.4 NG-4 直接 F） |
|---|---|---|---|---|
| T3-1 | `TableMetaStore.Backend.listCsts(entityClass)` 新增 package-private | `TableMetaStore.java#L880`（L720~L880 Backend 尾部追加） | ① listChildren 读 `cstDir(..., cstName)` 段（非 list/ 非 manifests）拿到全 cstName；② 对每个 cstName 读 8 条 leaf（type/col/ck/uk/nn/st/enabled/createdAt）全部 safeGetString 兜底；③ reflect 调 `CstSpec 8 参构造 (cst,typ,col,ck,uk,nn,st,enb)` 构 List<Object> 返回（类型擦除，跨包 reflect 迭代读字段）；≥4 IAE：entity blank / 8 参构造 NoSuchMethodException → 抛 IAE 包装 / listChildren 抛 IOE 包装 IAE | 方法内变量 cstList / cstName / ckVal / ukVal，**不能写 checkExpr / uniqueKey 完整词**（读 leaf 用常量 OD_CST_UK / OD_CST_CK / OD_CST_NN） |
| T3-2 | `DsDatabaseLocal$OnlineDdlCoordinator$DdlCstPipe`（内嵌 static 类）新增 | `DsDatabaseLocal.java#L4245`（DsDatabaseLocal 尾部，execCstCmd 后面追加） | DdlCstPipe **全 static 方法**：`prepare()` / `execute()` / `commit()` / `rollback()`（4 步）；内部字段 0（纯函数式）；migId 生成 = `System.currentTimeMillis()*100+seq`（seq=int 0..N 本地栈） | 类名 DdlCstPipe、方法名 4 个、形参名（ent/cstArr/cst/migList/keepCa）全部缩写，**类/方法/参数不得出现 5 完整词**（manifest 字面值，P19 8 词变量名写 migId，常量写 RMP 调用） |
| T3-3 | DdlCstPipe.prepare + execute + putIndexUnique 冲突 + rollback 清全 3 方法实现 | 同上 DdlCstPipe 内嵌类 | prepare: manifest leaf 内容 pipe 拼 `type=X|cst=Y|col=Z|uk=..|nn=..|ck=..|st=..|enb=..`；execute: UNIQUE 分支 reflect 调 DsEqIndexStore.putIndexUnique(table=lastSimpleName(ent), colId, key, rowId)，key=`rowId*13+colId`；冲突抛 RuntimeException 含 "UK 冲突: cst=<cst> key=<rowId>"；rollback：reflect 调 `dropCst` + `clearPartition`（T2/DsEq 已写） | putIndexUnique reflect 签名 4 参：`putIndexUnique(String tableName, long colId, Object keyValue, long dataId)`，**参数名不能写 keyValue/uniqueValue → 写 kv/dv** |
| T3-4 | DdlCstPipe.commit（keepCreatedAt 幂等）+ 清 manifests 段 | 同上 DdlCstPipe 内嵌类 | ① keepCreatedAt=true：读 `OD_CST_CREATED_AT` leaf → exist == null/blank → putS(now)；否则 skip；② manifests 清：遍历 migList 逐个 `removeLeaf(mig, ()->Boolean.FALSE)`（不存在不抛）；③ ≥3 IAE：migList==null 抛、entity blank 抛、cst dir 段 path 非法抛 | 内部变量 existCa / mig / keepCa（写 keepCa，不要写 keepCreatedAt → 长名含 create 非 NG，但 keepCreatedAt OK，优先 keepCa 缩写） |
| T3-5 | 新建 P20_DdlCstPipeTest.java 4 TR（TR3.1 ~ TR3.4） | `p2p-db/src/test/java/ds/P20_DdlCstPipeTest.java`（新建，0 历史） | P19 JUnit4 风格：TemporaryFolder + `p2p.db.home` set/restore；全部 Backend / DdlCstPipe 方法 reflect 调 setAccessible(true)；UK 冲突测试：**先 reflect 调 putIndexUnique 预写冲突 rowId**，再 execute 抛 RuntimeException 拿 message 断言 | 测试文件名 P20_ 前缀、方法名 tr3_x；断言消息中不能出现 5 完整词 → 写「UK 冲突」「cst 名重复」（UK 缩写 OK，cst OK） |

---

## 2.2 T3 验收 4 TR（VERBATIM 断言，缺一条子断言不闭 T3）

| TR 编号 | 方法名（VERBATIM 必须一致） | 断言列表（每条 assertTrue/assertEquals VERBATIM，不得合并简化） |
|---|---|---|
| TR3.1 | `tr3_1_listCstsAndManifestDirPaths` | 1. `listCsts reflect 返回` size 0；2. ADD 完 uk_email（T2 execCstCmd ADD_CST）后 size 1；3. list 第 0 项 `cstName()` reflect 调 返回值 equals "uk_email"；4. list 第 0 项 `type()` equals "UNIQUE"；5. RMP helper cstManifestNameListDir("default", "com.ex.T") 调用结果 `contains("/manifests/")` 为 true（5 断言全过 = TR3.1 0F） |
| TR3.2 | `tr3_2_executeUkConflictThenRollbackCleanAll` | 1. prepare 返回 manifestList size=2；2. manifest 第 0 个 leaf 值 `contains("|cst=")`；3. execute 后 OD_CST_TYPE(uk_email) 读 leaf = "UNIQUE"；4. 预写冲突（putIndexUnique 同一 table 同一 col 同一 kv 不同 rowId）后 execute 第 3 条 cst → 抛 RuntimeException 消息 `contains("UK 冲突")`；5. rollback 后 OD_CST_TYPE(uk_email) 读 leaf == null；6. rollback 后 OD_CST_CK(ck_len) 读 leaf == null；7. `listChildren manifests dir` 返回 empty（7 断言全过 = TR3.2 0F） |
| TR3.3 | `tr3_3_commitKeepCreatedAtAndClearManifests` | 1. prepare 返回 1 条 manifest；2. execute 无异常；3. commit 前 OD_CST_CREATED_AT == null 或 blank；4. commit 后 OD_CST_CREATED_AT parseLong > 0；5. Thread.sleep(3) 再 commit → OD_CST_CREATED_AT 原值 equals 新值（不变）；6. `listChildren manifests dir` 返回 empty（6 断言全过 = TR3.3 0F） |
| TR3.4 | `tr3_4_zeroLeakAndPrevious19TR` | 1. NG-4 5 词 Pattern 扫 main+test：非白名单 hit 数量 == 白名单 Set size（白名单 4 主 + P20_ConstraintKeySpaceTest / P20_DdlSemanticsTest / P20_DdlCstPipeTest = 7）；2. P19 8 词 Pattern 扫 main：非白名单 hit == 0（RMP + legacy4 白名单）；3. 回归命令 `mvn -pl p2p-db "-Dtest=P20_ConstraintKeySpaceTest,P20_DdlSemanticsTest,P20_DdlCstPipeTest,P19_AlterNullableTest,P19_AlterCoordinatorTest,P19_AlterKeySpaceTest" test` Surefire 输出 `contains("Tests run: 23, Failures: 0, Errors: 0, Skipped: 0")`（3 断言全过 = TR3.4 0F，必含 BUILD SUCCESS） |

---

## 2.3 T3 验收 CHECKLIST（10 项全 Y 才闭 T3，必须 self-check）

| # | 检查项（全 Y 才闭） | T3 自测结果 |
|---|---|---|
| 1 | `mvn -pl p2p-db compile` 0 ERROR | ⬜ |
| 2 | `mvn -pl p2p-db test-compile` 0 ERROR | ⬜ |
| 3 | 本 T 4 TR 独立 0F0E：`mvn -pl p2p-db "-Dtest=ds.P20_DdlCstPipeTest" test` → Tests run:4 Failures:0 Errors:0 | ⬜ |
| 4 | 所有新增方法 package-private（非 public / 非 protected） | ⬜ |
| 5 | 变量/getter/类名/方法名 全 缩写 cst/ck/uk/nn/vc/st；main+test 非白名单 NG-4 5 完整词 0 命中 | ⬜ |
| 6 | P19 8 词字符串字面量 全 RMP 常量引用，新增 0 字面量 | ⬜ |
| 7 | TR3.4 独立跑 NG-4 + P19 双词表，非白名单 0 命中 | ⬜ |
| 8 | BackwardCompat：空表 listCsts 返回 empty 时 prepare/execute/commit/rollback 不写任何 leaf | ⬜ |
| 9 | 前 T 全量回归：T1 4 + T2 4 + P19 11 = 19 TR 0F0E，累计 23 TR 0F0E | ⬜ |
| 10 | 本 T spec-t3.md + tasks-t3.md VERBATIM 批准过，用户回复包含「批准 P20 T3」 | ⬜ |

---

## 3. 下一步动作（SP3.1 SOP VERBATIM）

**请你严格复制下方格式回复，才视为 T3 SP3.1 批准通过，缺一不进入编码**：

```
批准 P20 T3；A=确认；B=确认；C=确认；D=确认
```

- 若 A/B/C/D 任一项不接受 → 回复「改 A → xxx」，我立即重写 VERBATIM 2 文件再提交
- 批准通过后我按 T3-1 → T3-2 → T3-3 → T3-4 → T3-5 严格编码
- T3 编码完成后产出：`mvn ... Tests run: 23, Failures: 0, Errors: 0, Skipped: 0 BUILD SUCCESS`（VERBATIM）
