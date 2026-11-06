# P20 OQ1 ALTER CONSTRAINT 在线约束（NOT NULL/UNIQUE/CHECK）—— tasks.md

**绝对日期：2026-10-03（零相对日期，禁今天/昨天/最近等词）**
**每任务 Test Requirements（TR）仅 rule / rubric 两类，与 spec.md AC 一一对齐**
**严格串行依赖，禁止跳步；前一任务 TR 100% PASS 0F0E 才允许启动下一任务**

---

## 任务依赖图（严格串行，禁止跳步，锁步锁门）

```
T1 (KeySpace 9 常量 + 6 helper + 13 keys zeroLeak + BackwardCompat 双桶)
  ↓ (依赖 RegistryMetaPaths cst 路径 + TMS/EqIndexStore package-private 扩展)
T2 (NOT NULL 存量强校验 + HC-2 strict IAE + HC-3 两阶段)
  ↓ (依赖 updateConstraint/enableConstraint Backend package-private)
T3 (UNIQUE 在线加 + EqIndexStore putIndexUnique 冲突检测 + 复合键)
  ↓ (依赖 DsEqIndexStore package-private 2 新方法)
T4 (CHECK 白名单 LEN/BETWEEN/IN 三类 + 非白名单语法拒绝)
  ↓ (依赖 ck 解析器手写 + ColumnDef.ck 落盘)
T5 (Coordinator 三方法 prepareConstraint/applyConstraint/rollbackConstraint + 20x 幂等 + HC-3 10 中断 + 20 并发 90s)
  ↓ (依赖 T2/T3/T4 三种约束原语)
T6 (ReplayLoop 150 rounds 0 差（NOT NULL×50 + UNIQUE×50 + CHECK×50）
  ↓ (依赖 rollbackConstraint 逆操作零残留)
T7 (c16 ConstraintGatekeeper 30/30 assertions：A6/B6/C13/D5/E3/F3)
  ↓ (依赖所有内嵌 hook + Facade 计数)
T8 (Perf Rubric U1/U2 双 score≥4：NOT NULL 1M P99 ≤60s + UNIQUE 1M P99 ≤240s）
  ↓ (依赖三种约束原语稳定)
T9 (JV P10~P20 ≥408 tests 0F0E；P15 拆 3 批隔离 PEERS)
  ↓ (依赖全量功能回归)
T10 (NeatFreak 三层同步 + 12 Invariants + OQ10 三选一）
```

---

## Task 1：Constraint KeySpace + RegistryMetaPaths cst Path + Backend扩展 + P20_ConstraintKeySpaceTest 4 TR

**Status: pending**
**Priority: high**
**依赖: 无（Spec SP4 批准后第一个任务）**
**父 AC: AC-R3（13 keys zeroLeak）/ AC-R4（Manifest 路径合规）/ AC-R14（BackwardCompat 双桶）/ AC-R1（NG-2 Facade 计数）

### 1.1 实现范围（合并优先，0 新文件，0 新 Maven 依赖）

#### 文件 A：`p2p-db/src/main/java/com/q3lives/ds/registry/meta/RegistryMetaPaths.java`
（**NG-4 铁律**：P20 新 5 关键词 constraint/uniqueKey/checkExpr/notNull/validateConstraint 仅在此处写完整词 1 次；实现层变量名/JSON/helper 参数名一律缩写 cst/uk/ck/nn/vc）

- **新增 9 个 static final 常量（完整词仅此处写，13 条 zeroLeak 正则全部汇聚）**：
  1. `OD_MANIFEST_CST = "constraints"` → 子目录名
  2. `OD_CST_NAME = "cstName"` → 约束名 leaf
  3. `OD_CST_TYPE = "cstType"` → NOT_NULL/UNIQUE/CHECK 枚举 leaf
  4. `OD_CST_COL = "cstCol"` → colId hex leaf
  5. `OD_CST_CK = "checkExpr"` → CHECK 模式 leaf（完整词 checkExpr 只此处 1 次）
  6. `OD_CST_UK = "uniqueKey"` → UNIQUE 标记 leaf（完整词 uniqueKey）
  7. `OD_CST_NN = "notNull"` → NOT NULL 标记 leaf（完整词 notNull）
  8. `OD_CST_VC = "validateConstraint"` → 计数 leaf（完整词 validateConstraint）
  9. `OD_CST_ENABLED = "enabled"` → 两阶段切换 leaf
- **新增 6 个 package-private helper（命名缩写 cst/uk/ck/nn/vc）**：
  1. `odManifestCstDir(space,entity,migId) → /manifest/<mId>/constraints/`
  2. `odManifestCstLeaf(space,entity,migId,cstName,subLeaf) → constraints/<cstName>/<subLeaf>`（9 subLeaf: type/col/ck/uk/nn/enabled/vc_count/vc_sample/createdAt）
  3. （其余 4 helper 用 odManifestCstLeaf + 常量名通用，控制 helper 总数 ≤ 6 防过度设计）
- **每条 helper IAE ≥ 5 条**：space/entity/migId 空或 null → IAE；cstName 空→IAE；colId<=0 → IAE；idx<0 → IAE

#### 文件 B：`p2p-db/src/main/java/com/q3lives/ds/database/columnar/TableMetaStore.java`
（**NG-2 铁律**：public 方法数=3，全部扩展走 Backend 内嵌类 package-private）

- `Backend` 内嵌类加 **2 个 package-private 方法**：
  1. `void updateConstraint(String entity, ConstraintSpec spec)`：写 `constraints/<cstName>/{type,col,ck,uk/nn,strict,createdAt}` 6 leaf；写完 CAS 重新 computeSignature 覆盖 signature leaf
  2. `void enableConstraint(String entity, String cstName, boolean enabled)`：仅写 enabled 1 leaf（HC-3 绝对不能在 PREPARING 调用此方法；PREPARING 阶段 enabled 绝不写）
- `loadFromRegistry` BackwardCompat 兜底：ColumnDef `unique` leaf 不存在 → cd.unique=false；`ck` leaf 不存在 → cd.ck=null
- **0 public 方法新增**：public=3 基线不变；Backend 2 方法走 package-private，不占计数

#### 文件 C：`p2p-db/src/main/java/com/q3lives/ds/database/index/DsEqIndexStore.java`
（**NG-2 铁律**：EqIndexMetaStore=7 public 不变；DsEqIndexStore 非 Facade，新增走 package-private）

- **新增 2 个 package-private 方法**：
  1. `void putIndexUnique(Object value, long rowId) throws UniqueViolationException`：containsIndex(value, *rowId'≠rowId 已存在) → removeIndex(value,rowId) 回滚半写入 → 抛 IAE("uk dup=value
  2. `void clearPartition(String cstName)`：删除 indexName="uk_"+cstName 分区清 0；0 影响 non-unique 原有索引分区
- **public 方法签名不变：原 putIndex/removeIndex 13 methods 完全不动

#### 文件 D：`p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java`
（**NG-4 缩写铁律**：新增 POJO ConstraintSpec 内嵌类 变量名 cst/uk/ck/nn/vc，绝不写 constraint/uniqueKey/checkExpr/notNull/validateConstraint 完整词）

- `OnlineDdlCoordinator` 内嵌类加 **static final class ConstraintSpec** POJO（变量全缩写避 zeroLeak）：
  ```java
  static final class ConstraintSpec {
      String cst;      // 约束名（cstName 缩写，禁止写 constraintName）
      String type;      // NOT_NULL / UNIQUE / CHECK 枚举缩写 TN/TU/TC
      long col;         // colId
      String ck;        // checkExpr 缩写，null=非 CHECK
      boolean uk;      // uniqueKey 缩写
      boolean nn;      // notNull 缩写
      boolean strict;
  }
  ```

### 1.2 Test Requirements（TR 4/4 = 精准 4 测试方法）

**TR-1.1（rule，父 AC-R4 路径合规）**：
- 反射 odManifestCstDir 返回路径 startsWith `/orm/dbs/<space>/onlineDDL/<entity>/manifest/`；0 新增 `/orm/` 第一级子目录
- 反射 helper IAE 覆盖 5/5：space空/entity空/migId空/cstName空/colId<=0

**TR-1.2（rule，父 AC-R14 BackwardCompat 双桶）**：
- 构造 P19 前旧 meta（缺 unique/ck leaf）→ loadFromRegistry → cd.unique=false / cd.ck=null
- updateConstraint 写 6 leaf + enableConstraint 后再 load → 新值正确；signature diff 变更
- 旧数据读路径 0 NPE / 0 ClassCast

**TR-1.3（rule，父 AC-R1 NG-2 计数）**：
- 反射 6 Facade：DsDatabaseLocal=48（±4）/ ColumnarStore=24（±4）/ TableMetaStore=3（±4）/ ColumnRegistry=11（±4）/ RowIdSeq=1（±4）/ EqIndexMeta=7（±4）— c16 A6 6/6 in range

**TR-1.4（rule，父 AC-R3 13 keys zeroLeak files=1）**：
- PowerShell grep 正则：`\bconstraint\b|\buniqueKey\b|\bcheckExpr\b|\bnotNull\b|\bvalidateConstraint\b|\bmanifest\b|\bschemaVersion\b|\btombstones\b|\bmigrateId\b|\bonlineDdl\b|\balterCols\b|\bcastMode\b|\boldJavaType\b`
- 预期：files count = 1（RegistryMetaPaths.java only）；若 ≥ 2 → 改实现层变量名重命名为缩写后重测

### 1.3 Completion Evidence
- `mvn -pl p2p-db -Dtest=P20_ConstraintKeySpaceTest test` → Tests run: 4, Failures: 0, Errors: 0
- TR-1.4 zeroLeak 打印 VERBATIM `files=1 RegistryMetaPaths.java only`
- TR-1.2 BackwardCompat 5 assertions ALL PASS

---

## Task 2：NOT NULL 存量强校验 + HC-2 strict IAE + HC-3 两阶段 + P20_NotNullConstraintTest 4 TR

**Status: pending**
**Priority: high**
**依赖: T1（updateConstraint + enableConstraint package-private 已实现）**
**父 AC: AC-R5（HC-1 三 ID 不碰）/ AC-R6（HC-2 strict IAE）/ AC-R7（HC-3 两阶段）/ AC-R8（NOT NULL correctness）/ AC-R15（dry-run 零写盘）**

### 2.1 实现范围

#### 文件 A：`DsDatabaseLocal.java` OnlineDdlCoordinator 内嵌类
（prepareConstraint 子流程 NOT NULL 分支 + applyConstraint nn 批扫）

- prepareConstraint：
  1. 读 stored.nullable=true vs fresh.nullable=false 列 → diff 产出 ConstraintSpec type=NOT_NULL cst
  2. manifest.constraints/<cst>/ 写 6 leaf（type=NOT_NULL/col/cst=.../nn=true/strict/createdAt）
  3. HC-1 IAE：diff 前比对 stored.colId vs 任何 diff 发现 colId 不等 → 立即抛（AC-R5 实锤 IAE）
- applyConstraint NOT NULL 批扫：
  1. DRY_RUN：while 循环扫所有 row（复用 P19 batch=2048 框架），每行读列值 == null → vc.count++ 累加 sample 取前 100 rowId
  2. APPLYING 首行：strict=true && vc.count>0 → **立即 IAE HC-2**，enabled leaf 此时未写（HC-3 实锤 IAE 在写 enabled 前！HC-2：strict=false→通过则：写 columns.nullable=false → 重算 signature → enableConstraint(cst,true)（严格顺序）
- rollbackConstraint NOT NULL：删 constraints/<cst>/ → 恢复 columns.nullable=true → enableConstraint(cst,false)（若已写）

### 2.2 Test Requirements（TR 4/4）

**TR-2.1（rule，父 AC-R8 correctness）**：
- Seed N=2000 行，其中 5 行某列=null（colId 固定）；prepare → dry-run → vc.count=5 + sample rowIds 正确；strict=false apply 后 columns.nullable=false && enabled=true；随后新写入 value=null 自动抛（增量校验软保证）

**TR-2.2（rule，父 AC-R6 HC-2 strict IAE）**：
- 同 Seed 5 行 null；strict=true apply → IAE message contains "vc count=5"；columns.nullable 读回仍为 true（不修改）

**TR-2.3（rule，父 AC-R5 HC-1 IAE）**：
- 反射篡改 manifest.colId=X；准备 colId=Y 准备 → prepareConstraint → 立即 IAE("ALTER CONSTRAINT must not change colId")

**TR-2.4（rule，父 AC-R15 dry-run 零写盘）**：
- dry-run=true 前后 SHA256(dirHash)=dirHash_before；Registry 写计数=0；columns.nullable 不变；enabled leaf 不存在

### 2.3 Completion Evidence
- `mvn -pl p2p-db -Dtest=P20_NotNullConstraintTest test` → Tests run: 4, Failures: 0, Errors: 0

---

## Task 3：UNIQUE 在线加 + EqIndexStore putIndexUnique 冲突检测 + 复合键支持 + P20_UniqueConstraintTest 4 TR

**Status: pending**
**Priority: high**
**依赖: T2（NOT NULL 闭）+ T1（EqIndexStore 2 package-private 方法）**
**父 AC: AC-R5（HC-1）/ AC-R6（HC-2）/ AC-R7（HC-3）/ AC-R9（UNIQUE correctness）/ AC-R15（dry-run）**

### 3.1 实现范围

#### 文件 A：DsDatabaseLocal.java applyConstraint UNIQUE 分支
- prepareConstraint UNIQUE：manifest 写 6 leaf：type=UNIQUE/col=colId/uk=true/strict/ck=null 6 leaf）
- applyConstraint UNIQUE：
  1. DRY_RUN：批扫 2048 行/批，用 DsHashMap<K,List<Long>> value→rowIds；len>1=dup 存 vc 计数/采样
  2. APPLYING：①strict=true && vc>0 → IAE HC-2
  3. ② strict=false || vc=0：① 清 eqIndex 分区 → ② 重新扫每批调 eqIndexStore.putIndexUnique(value,rowId)（冲突回滚+IAE）③全部通过后 enableConstraint(cst,true+ 写 columns/<col>/unique=true

### 3.2 Test Requirements（TR 4/4）

**TR-3.1（rule，父 AC-R9 UNIQUE correctness）**：Seed N=2000，故意造 2 行 value="dup" rid=500/1501 → dry-run vc.count=1；strict=false apply eqIndex 最后 正确，strict=true apply IAE

**TR-3.2（rule，父 AC-R6 HC-2 strict IAE）**：同 TR-3.1 setup strict=true apply → IAE 含 "vc count=1

**TR-3.3（rule，父 AC-R5 HC-1）**：colId 篡改 → IAE

**TR-3.4（rule，父 AC-R7 HC-3）**：DRY_RUN 阶段 enabled leaf 不存在/null；APPLYING Thread.interrupt 5 次在 99% 处 → enabled=true 仅 0-1 次，不会半写状态

### 3.3 Completion Evidence
- `mvn -pl p2p-db -Dtest=P20_UniqueConstraintTest test` → Tests run: 4, Failures: 0, Errors: 0

---

## Task 4：CHECK 白名单 LEN/BETWEEN/IN 三类解析 + 非白名单拒绝 + P20_CheckConstraintTest 3 TR

**Status: pending**
**Priority: high**
**依赖: T3（UNIQUE 闭）**
**父 AC: AC-R10（三类白名单解析）/ AC-R11（非白名单拒绝）/ AC-R6（HC-2 strict IAE）/ AC-R15（dry-run）**

### 4.1 实现范围

#### 文件 A：OnlineDdlCoordinator 内嵌类 加 ck 解析（静态解析器手写）

- ck parse(String pattern) → 3 类白名单，严格正则匹配：
  1. `^LEN\\((\\w+)\\)<=(\\d+)$ → 提取 colKey + N
  2. `^(\\w+) BETWEEN (-?\\d+),(-?\\d+)$ → col + lo/hi
  3. `^(\\w+) IN \\{([^}]+)\\}$` → col + 逗号分隔值集合 (K≤64，超过 IAE)
- applyConstraint CHECK 批扫每行：按 col 值读 → 不匹配 → vc.count++；HC-2 strict=true 抛 IAE；通过后写 enabled=true + ck=<pattern> 写 columns.ck

### 4.2 Test Requirements（TR 3/3）

**TR-4.1（rule，父 AC-R10）**：LEN(name)<=5 seed 行 ABCDEF; age BETWEEN 0,130 seed 999; status IN{0,1,2} status=9 三类正1负，dry-run vc count=1 strict=false apply OK

**TR-4.2（rule，父 AC-R6 HC-2）**：同 TR-4.1 setup strict=true apply → IAE 三类场景

**TR-4.3（rule，父 AC-R11）**：4 类非白名单 `age > 18、LEN(x)<=N (变量)、IN {65 项}、SCRIPT(..} — strict=true 立即 IAE("ck pattern not whitelisted: X" 4/4

### 4.3 Completion Evidence
- `mvn -pl p2p-db -Dtest=P20_CheckConstraintTest test` → Tests run: 3, Failures: 0, Errors: 0

---

## Task 5：Coordinator 三方法 + 20x 幂等 + HC-3 10 中断 + 20 并发 90s + P20_ConstraintCoordinatorTest 3 TR

**Status: pending**
**Priority: high**
**依赖: T4（CHECK 闭）**
**父 AC: AC-R13（幂等 20×）/ AC-R7（HC-3 两阶段）/ NFR-1（并发无死锁）**

### 5.1 实现范围

#### 文件 A：DsDatabaseLocal.java OnlineDdlCoordinator 类
- 三 package-private 方法：`prepareConstraint(Class, List<ConstraintSpec>, boolean dryRun, boolean strict) / `applyConstraint(Class, int batchSize)` / `rollbackConstraint(Class)`
- 7-phase 复用 P18，cancel 黑名单守卫；cpDir per-cst 独立目录；while 结束 if(!batch.isEmpty()) 尾批 flush；apply 每批 cp SHA256

### 5.2 Test Requirements（TR 3/3）

**TR-5.1（rule，父 AC-R13 幂等 20×）**：同 NOT NULL Seed 2000 行 0 NULL → 20 次 apply；stepId/progress/ enabled/nullable/eqIndexSize 全等；重复写 IAE 0 次

**TR-5.2（rule，父 AC-R7 HC-3 10 中断）**：NOT NULL 场景 Thread.interrupt 在 APPLYING 99% 处 ×10次； enabled=true 出现 ≤1 次；不出现半写

**TR-5.3（rule，父 NFR-7 并发无死锁）**：ExecutorService 20 线程 × 独立临时目录隔离；10 NOT NULL + 5 UNIQUE + 5 CHECK；90s 全 done=20 deadlock=null

### 5.3 Completion Evidence
- `mvn -pl p2p-db -Dtest=P20_ConstraintCoordinatorTest test -DforkCount=1 -DreuseForks=false` → Tests run: 3, Failures: 0, Errors: 0

---

## Task 6：ReplayLoop 150 rounds（NOT NULL×50 + UNIQUE×50 + CHECK×50）apply→rollback→apply 0 字节全等 + P20_ConstraintReplayLoopTest 1 TR

**Status: pending**
**Priority: high**
**依赖: T5（Coordinator 闭）**
**父 AC: AC-R12（ROLLBACK 零残留）/ NFR-1（可逆正确性）**

### 6.1 Test Requirements（TR 1/1）

**TR-6.1（rule）**：ROUNDS=150；每轮 seed → apply → rollback → apply；每轮 dirHash(起点)=dirHash(终点)；bad=0；ms≤600_000

### 6.2 Completion Evidence
- `mvn -pl p2p-db -Dtest=P20_ConstraintReplayLoopTest test` → Tests run: 1, Failures: 0, Errors: 0；log VERBATIM `[ReplayLoop] rounds=150 bad=0`

---

## Task 7：c16 ConstraintGatekeeperTest A6/B6/C13/D5/E3/F3 30 assertions 全 PASS 1 TR

**Status: pending**
**Priority: high**
**依赖: T6（ReplayLoop 闭）**
**父 AC: NFR-6（c16 30/30 assertions）/ AC-R1~R7（NG/HC）**

### 7.1 Test Requirements（TR 1/1，30 assertions）

**TR-7.1（rule）**：c16 ConstraintGatekeeper：A6（6 Facade 计数 6）+ B6（非 Facade public ≤ 6）+ C13（13 keys zeroLeak files=1 13）+ D5（5 集合探针 ClassNotFound 5）+ E3（EqIndexStore.putIndexUnique package-private 反射可达 1）+ F3（HC-1/HC-2/HC-3 三红线实锤 IAE 3）— 合计 6+6+13+5+1+3=30/30 assertions ALL PASS

### 7.2 Completion Evidence
- `mvn -pl p2p-db -Dtest=P20_ConstraintGatekeeperTest test -DforkCount=1 -DreuseForks=false` → Tests run: 1, Failures: 0, Errors: 0；log VERBATIM `[c16 P20 Constraint Gatekeeper] 30/30 assertions ALL PASS`

---

## Task 8：Perf Rubric U1/U2 score≥4（NOT NULL 1M P99 + UNIQUE 1M P99）+ P20_ConstraintPerfRubricTest 2 TR

**Status: pending**
**Priority: high**
**依赖: T7（Gatekeeper 闭）**
**父 AC: AC-U1/U2（Rubric 4/5 双 threshold≥4）**

### 8.1 Test Requirements（TR 2/2）

**TR-8.1（rubric AC-U1 NOT NULL 存量扫 1M 行 P99）**：
- Seed N=1_000_000 行 String length=32 0 NULL 行；3 rounds prepare→apply 取 P99 最差
- 评分：≤30s=5 / ≤60s=4 / ≤120s=3 / ≤240s=2 / >240s=0；≥4 PASS

**TR-8.2（rubric AC-U2 UNIQUE 建索引 1M 行 P99）**：
- Seed N=1_000_000 行 32 字节 0 重复值；3 rounds prepare→apply 取 P99 最差
- 评分：≤120s=5 / ≤240s=4 / ≤480s=3 / ≤960s=2 / >960s=0；≥4 PASS

### 8.2 Completion Evidence
- `mvn -pl p2p-db -Dtest=P20_ConstraintPerfRubricTest test` → Tests run: 2, Failures: 0, Errors: 0
- 打印：`[P20 U1] score=X (P99=...)`；`[P20 U2] score=Y (P99=...)` 双 score ≥ 4

---

## Task 9：JV P10~P20 ≥408 tests 0F0E（P15 拆 3 批 + P20 独立类优先跑）1 TR

**Status: pending**
**Priority: high**
**依赖: T8（Perf 闭）**
**父 AC: AC-R16（JV ≥ 408）**

### 9.1 Test Requirements（TR 1/1，fallback 条款沿用 P19 9.2）

**TR-9.1（rule）**：JV 分 4 批串行，独立优先跑 P20 8 类（22 tests 最快正确性）

- 批 1：P20 独立 8 类：KeySpace(4)+NotNull(4)+Unique(4)+Check(3)+Coordinator(3)+ReplayLoop(1)+Gatekeeper(1)+Perf(2) = 22 tests
- 批 2：P15 PEERS 静态污染拆 3 子批：a)CompactCrash(2) b)StateMachine(10 fresh JVM) c)其余 42（RaftKeySpace/Election/FacadeFence/FacadeHookReal/InstallSnapshot/Perf）
- 批 3：P16/P17/P18/P19/P10~P13 历史基线 386（含历史 Perf cold cache 抖动按 tasks spec 9.2 fallback 豁免，P20 独立 22 全 PASS =≥ 386+22=408）

### 9.2 Completion Evidence
- Surefire 汇总 `Tests run: X Failures:0 Errors:0`，X≥408（fallback：P20 独立 22 全 PASS + P10~P19 基线 386 = 408 即合规）

---

## Task 10：NeatFreak 三层同步 + 12 P20 Invariants + OQ10 三选一 + 三步自检

**Status: pending**
**Priority: medium**
**依赖: T9（JV 408+ 闭）**

### 10.1 同步范围（3 files，严格按 tasks.md §10.1 要求）

#### 文件 A：AGENTS.md（P19 Invariants 末尾追加 12 P20 Invariants + 代码锚点）
1. HC-1 三 ID 不碰（colId/colKey/name）
2. HC-2 violations strict=true IAE
3. HC-3 两阶段 enabled 原子切换
4. NG-4 13 keys zeroLeak files=1（RegistryMetaPaths only）
5. NG-2 6 Facade ±4
6. 7-phase 状态机复用 P18 + cancel 守卫
7. Checkpoint SHA256(step:colId:payload32B) 20× 幂等 per-cst 独目录
8. NOT NULL 存量扫 + vc
9. UNIQUE EqIndexStore.putIndexUnique 冲突检测
10. CHECK 白名单 3 类 LEN/BETWEEN/IN
11. c16 Gatekeeper 30/30 assertions
12. ReplayLoop 150 rounds + JV ≥408

#### 文件 B：docs/dsdb-design-status-and-roadmap.md OQ-9 行 → ✅ CLOSED + OQ10 三选一（① ALTER VIEW ② Linux CFS 烟测 ③ Other）

#### 文件 C：project_memory.md 三区追加
- Context 区：P20 OQ1 ALTER CONSTRAINT Close 2026-10-0X 摘要
- Hard Constraints 区：HC-1/HC-2/HC-3 三条 P20 红线归档
- Lessons Learned 区：4 条 P20 踩坑（13 keys 提前缩写/UNIQUE 分区独立不碰非唯一索引/CHECK 白名单正则别贪多/HC-3 enabled 守卫位置陷阱）

### 10.2 验收三步自检（VERBATIM 硬要求）

1. **grep 相对日期词（今天/昨天/最近/today/yesterday/recently）三文件 = 0 违规语义命中
2. **AGENTS.md 锚点 10% 抽样 = 2 个 path exists**
3. **Roadmap 表格 `|` count 差值 ≤ 2（前后对齐）**

三步 3/3 PASS → 发布 T10 Completion Evidence

### 10.3 Completion Evidence
- 三文件 disk 持久化实锤
- Console 打印 `[T10 P20 OQ1 Constraint NeatFreak] 3 files synced + 3 self-checks PASS = P20 OQ1 CLOSED 2026-XX-XX` + OQ-10 三选一 waiting 提示
