# P20 T3 DDL 协调器融合 详细设计（VERBATIM 批准版）

## 0. 前置校验（VERBATIM，缺一条不进入 T3 编码）

1. T2 闭包 ✅：`mvn ... P20_ConstraintKeySpaceTest,P20_DdlSemanticsTest,P19_*Alter*Test test` Surefire 输出 `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0 BUILD SUCCESS`（VERBATIM 已验证）
2. SP3.1 0 节 A/B/C/D ✅：T2 已按内嵌类、幂等异 type IAE、4+2 白名单、pipe 分隔符 4 决策实施（VERBATIM 不重审）
3. NG-4 zeroLeak 双词表白名单（VERBATIM 保持 4+2）：4 主源码=`RegistryMetaPaths/TableMetaStore/DsEqIndexStore/DsDatabaseLocal`；2 test=`P20_ConstraintKeySpaceTest.java/P20_DdlSemanticsTest.java` + T3 新 test `P20_DdlCstPipeTest.java` 自动追加为 T3 白名单（仅文件名 P20_ 前缀可扩，主源码 0 新增）
4. BackwardCompat 强兜底：空表 `listDir(cst listDir) = emptyList` 时，prepare/execute/commit/rollback 四步**零行为变**（等价 T2 前代码）；castMode/alterCols/castMode oldJavaType P19 8 词常量引用（OD_ALTER_CAST / OD_ALTER_OLD_JTYPE）保持 T2 已修，T3 零字面量新增

---

## 1. T3 总体交付（VERBATIM，不新增独立类，零 public API 暴露）

| # | 交付件 | 所在文件（内嵌 static class，package-private reflect-only） | 关键缩写约束 |
|---|---|---|---|
| T3-A | `DdlCstPipe` 4 步管道 | `DsDatabaseLocal.java` → 内嵌 `OnlineDdlCoordinator$DdlCstPipe`（static 嵌套类，不 new 独立 .java 文件） | 所有字段/方法/参数名 cst / ck / uk / nn / st / vc / migId（P19 8 词 migId 常量 RMP 引用，变量写 migId） |
| T3-B | `TableMetaStore$Backend.listCsts(entityClass)` package-private | `TableMetaStore.java` Backend 内追加 1 方法 | 返回 `List<Object>`（reflect 构 CstSpec 匿名 List，不写泛型 public 签名，跨包 reflect 读） |
| T3-C | `prepare(entityClass, CstSpec[]) → List<manifestLeaf>` 写 manifests/ 段 | `DdlCstPipe` 静态方法 | 只写 `cstManifestNameListDir(...)` 下的 `<migId>` leaf，不碰 `constraints/<cstName>/` 主目录；migId 自增 `System.currentTimeMillis()*100+seq`（seq=0..N） |
| T3-D | `execute(entityClass, CstSpec[], manifestList)` → 写 cst 主 leaf + 调 putIndexUnique | `DdlCstPipe` 静态方法 | putIndexUnique 冲突抛 `RuntimeException("UK 冲突: cst=<x> key=<y>")`；UNIQUE 调 T1 DsEqIndex.putIndexUnique reflect；CHECK/NOT NULL 仅写 leaf 不执行 SQL 校验（T6 专项） |
| T3-E | `commit(entityClass, manifestList, keepCreatedAt=true)` → 写 createdAt + 清 manifest | `DdlCstPipe` 静态方法 | keepCreatedAt=true：同 cst 旧 createdAt 存在不重写；清 manifest = 逐个 removeLeaf manifests/<migId> |
| T3-F | `rollback(entityClass, manifestList)` → 清 manifest + 删 cst 主 dir + 清 DsEq 脏 | `DdlCstPipe` 静态方法 | rollback 仅 execute 后/中触发；cst 主 dir 删 recursive；DsEq 清对应 partition（clearPartition reflect） |

---

## 2. T3 DdlCstPipe 4 步语义（VERBATIM，顺序不可并行）

### 2.1 prepare：仅写 manifests 段，不碰主约束数据

- 输入：`entityClass (String)`、`cstSpecs[] (Object[]  reflect)`
- 输出：`List<String> manifestLeafList`（每个元素 = `cstManifestNameListDir(...) + "/" + migId`）
- 每条 CstSpec manifest leaf 值 = JSON-like pipe 串（不引 Jackson，纯 JDK 自拼）：
  ```
  type=UNIQUE|cst=uk_email|col=1|uk=true|nn=false|ck=|st=false|enb=true
  ```
- ≥5 IAE：① entity blank / ② cst nonAlnum（array 内任一 element）/ ③ 同 entityClass 同 cst 重名（prepare 内先去重）/ ④ UNIQUE uk=false / ⑤ CHECK ck 空（strict=true 时 ck 空抛）

### 2.2 execute：写 cst 主 leaf + UNIQUE putIndexUnique

- 输入：同 prepare + `manifestList (List<String>)`（2.1 输出）
- 顺序：
  1. 先读 `listCsts(entityClass)` → Map `<cstName, old CstSpec>`
  2. 对每条新 CstSpec：
     a. 若 old cst 存在 `type != new type` → 抛 IAE `type 已存在异 type：old=X new=Y`
     b. 调 T2 `updateConstraint(entityClass, newCstSpec, skipExist=false)` → 写 8 leaf（type/col/ck/uk/nn/st/enabled/createdAt(暂写空或 keep old，commit 再补 createdAt 保证幂等)）
     c. **仅 UNIQUE**：reflect 调 `DsEqIndexStore.putIndexUnique(String table, long colId, Object key, long rowId)`，**table 取 entityClass 的 lastSimpleName**（"com.ex.T2User" → "T2User"）；**key 取测试桩值 `col=1` 用 `rowId*13`（避免真实反序列化，TR3 仅测冲突语义）**；若 putIndexUnique 抛 RuntimeException（UK 冲突）→ **立即抛 RuntimeException 外带 manifestList 供调用方 rollback 清**
  3. CHECK/NOT NULL 仅写 leaf，不做数据级校验（T6 专项 16 TR 再补）

### 2.3 commit：写 createdAt（幂等） + 清 manifests

- 输入：`entityClass`、`manifestList`、**keepCreatedAt=true（默认）**
- 顺序：
  1. 扫 manifestList → 解析每条 cst=段 拿到 cstName
  2. 读 cst 主 leaf `OD_CST_CREATED_AT`：若为空/blank → `putS(..., ""+System.currentTimeMillis())`；若非空 → **不写（幂等）**（对齐 T2 ADD skipExist 原则）
  3. 扫 manifestList → `removeLeaf(manifestLeaf, ()->F)` 逐个删（不存在不抛）

### 2.4 rollback：清 manifest + 删 cst 主 dir + 清 DsEq partition

- 输入：`entityClass`、`manifestList`、（可选）`failCst=CstSpec`（失败位置）
- 顺序：
  1. 扫 manifestList → 解析 cst=段 → 对每条 cst 调 `dropCst(entityClass, cstName)`（T2 已写，删 dir + 清 listDir migId）
  2. reflect 调 `DsEqIndexStore.clearPartition(String table, int partitionIdx)`（**partitionIdx=0，table=lastSimpleName**）清 UNIQUE 写的脏数据
  3. 扫 manifestList → `removeLeaf` 清 manifest

---

## 3. T3 强制 ≥4 TR（VERBATIM，严格写进 P20_DdlCstPipeTest）

新建测试类 `p2p-db/src/test/java/ds/P20_DdlCstPipeTest.java`（NG-4 白名单自动加），4 TR 独立 0F0E，然后加前 19 TR 回归：

| TR 编号 | 方法名（VERBATIM） | 断言（VERBATIM，缺一条不闭 T3） |
|---|---|---|
| TR3.1 | `tr3_1_listCstsAndManifestDirPaths` | ① 空 DB 启动 Backend，`listCsts("com.ex.T")` reflect 返回 size=0；② ADD_CST cst=uk_email UNIQUE col=1 uk=true → `listCsts(...)` size=1，item `cstName()="uk_email"` `type()="UNIQUE"` 2 字段全匹配；③ manifests 段路径 RMP helper `cstManifestNameListDir("default", "com.ex.T")` 含 `"manifests"` 段名（3 子断言） |
| TR3.2 | `tr3_2_executeUkConflictThenRollbackCleanAll` | ① prepare 2 cst：uk_email(UNIQUE col=1) + ck_len(CHECK col=2 ck="len>0") → manifestList size=2，每个 manifest leaf 值都含 `|cst=` 段；② execute 写主 leaf：`readStr(OD_CST_TYPE(uk_email))="UNIQUE"`，CK ck leaf `"len>0"`；③ 构 UK 冲突：**reflect 调 putIndexUnique 手动预写冲突 rowId**，再 execute 第 3 条 cst uk_email_new col=1 → 抛 RuntimeException 含 "UK 冲突"；④ rollback 后：uk_email type leaf==null，ck_len ck leaf==null（cst dir 全删），manifests 2 条全清（`listChildren manifests dir` 空），DsEq 清 partition=0 后 putIndexUnique 重写冲突可成功（7 子断言） |
| TR3.3 | `tr3_3_commitKeepCreatedAtAndClearManifests` | ① prepare 1 cst uk_email；② execute 无冲突；③ commit 前读 OD_CST_CREATED_AT=空；④ commit 后 OD_CST_CREATED_AT>0（有毫秒）；⑤ Thread.sleep(3) 再 commit 第二次（幂等）→ OD_CST_CREATED_AT 值 == 原值（不变）；⑥ manifests dir `listChildren == emptyList`（6 子断言） |
| TR3.4 | `tr3_4_zeroLeakAndPrevious19TR` | ① NG-4 5 词 Pattern 扫 main+test：非白名单（4主+3test）0 命中（白名单 P20_DdlCstPipeTest.java 已加）；② P19 8 词扫 main+test：除 RMP + legacy4（DsFullBackup / DsIncrementalBackup / DsTimelineRestore / DsDatabaseMetaMigrator）0 命中；③ 回归验收命令（VERBATIM 下一节）Surefire 输出包含 `Tests run: 23, Failures: 0, Errors: 0, Skipped: 0` |

---

## 4. T3 回归验收命令（VERBATIM，必须 23 TR 0F0E 才闭 T3）

```
mvn -pl p2p-db "-Dtest=ds.P20_ConstraintKeySpaceTest,ds.P20_DdlSemanticsTest,ds.P20_DdlCstPipeTest,ds.P19_AlterNullableTest,ds.P19_AlterCoordinatorTest,ds.P19_AlterKeySpaceTest" test
```

- Surefire 汇总行 VERBATIM 必须包含 `Tests run: 23, Failures: 0, Errors: 0, Skipped: 0`
- 附加必须包含 `BUILD SUCCESS`

---

## 5. BackwardCompat 兜底专项断言（VERBATIM，每条必验）

1. 空表（cst 无任何 leaf）：prepare/execute/commit/rollback 四步，`alterColumnSchema(...)` 原 P19 行为 100% 等价（TR3.4 内部会跑 P19 11 TR，零 F）
2. castMode / oldJavaType / onlineDdl / alterCols / migrateId / schemaVersion / tombstones / manifest P19 8 词：T3 新增代码零字符串字面量，全引用 OD_* 常量
3. UNIQUE cst 默认 uk=false：prepare 判 uk false 抛 IAE（execute 阶段不调 putIndexUnique）
4. ck 默认 null：strict=true 且 ck null 抛 IAE（execute 不执行 CHECK）
5. enabled 默认 "true"：safeGetString 读 enabled leaf 不存在时用 "true"（不写默认值，避免空表落盘变化）
