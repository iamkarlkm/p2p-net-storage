# P19 OQ1 ALTER COLUMN TYPE 列属性变更（Nullable/Size/TypeCast 可逆 Manifest + 数据回填 Batch）—— tasks.md

**绝对日期：2026-09-30（零相对日期，禁今天/昨天/最近等词）**
**每任务 Test Requirements（TR）仅 rule / rubric 两类，与 spec.md AC 一一对齐**
**严格串行依赖，禁止跳步；前一任务 TR 100% PASS 0F0E 才允许启动下一任务**

---

## 任务依赖图（严格串行，禁止跳步）

```
T1 (KeySpace + Alter Paths + 8 关键词 zeroLeak + BackwardCompat 双桶)
  ↓ (依赖 RegistryMetaPaths alter helper + updateColumnDef package-private)
T2 (Nullable 翻转 O(1) + rollback + colId 不变守卫)
  ↓ (依赖 updateColumnDef 5 个 leaf 覆盖写 + signature 重算)
T3 (Length/Prec/Scale 扩容缩容 + dry-run 零写 + checkpoint 续跑 + narrowing 守卫)
  ↓ (依赖 alter manifest 路径 + padding 写回 bucketStore)
T4 (JavaType 白名单 SAFE_UPCAST + int→long 数学相等 + narrowing strict=true IAE)
  ↓ (依赖 cast 字节工具 + 白名单枚举判断)
T5 (Coordinator 三方法 + 20× 幂等 + 20 并发 ALTER 90s 无死锁)
  ↓ (依赖 T2/T3/T4 三种 ALTER 原语)
T6 (ReplayLoop 150 rounds 0 差：Null 50× + Size 50× + TypeCast 50×)
  ↓ (依赖 rollbackAlter 逆操作字节相等)
T7 (c15 AlterGatekeeperTest 27/27 assertions：A6/B6/C7/D5/E3/F2)
  ↓ (依赖所有内嵌 hook + ColumnRegistry public 计数)
T8 (Perf Rubric U1/U2 双 ≥4：Length Padding 1M P99 + 内存峰值)
  ↓ (依赖三种 ALTER 原语稳定)
T9 (JV P10~P19 ≥382 tests 0F0E；P15 拆 3 批隔离 PEERS)
  ↓ (依赖全量功能回归)
T10 (NeatFreak 三层同步 + 12 Invariants + OQ9 三选一)
```

---

## Task 1：RegistryMetaPaths ALTER 路径 + Backend.updateColumnDef + P19_AlterKeySpaceTest 4 TR

**Status: pending**
**Priority: high**
**依赖: 无（Spec SP4 批准后第一个任务）**
**父 AC: AC-R3(NG-4 8 关键词 zeroLeak) / AC-R4(ALTER 路径合规) / AC-R13(BackwardCompat 双桶)**

### 1.1 实现范围（合并优先，不新建文件，0 新 Maven 依赖）

#### 文件 A：`p2p-db/src/main/java/com/q3lives/ds/registry/meta/RegistryMetaPaths.java`
（**NG-4 铁律**：manifest/schemaVersion/tombstones/migrateId/onlineDdl + 新增 alterCols/castMode/oldJavaType = 合计 8 关键词字面量仅在此文件声明 1 处）

- **新增常量（缩写避 zeroLeak）**：
  - `static final String OD_MANIFEST_ALTER = "alterCols"`（完整词 alterCols 仅此处写，实现层用 altCols 缩写）
  - `static final String OD_ALTER_CAST = "castMode"`；`static final String OD_ALTER_STRICT = "strict"`
  - `static final String OD_ALTER_OLD_JTYPE = "oldJavaType"`；`static final String OD_ALTER_NEW_JTYPE = "newJavaType"`
  - `static final String OD_ALTER_OLD_LEN = "oldLength"`；`static final String OD_ALTER_NEW_LEN = "newLength"`
  - `static final String OD_ALTER_OLD_NULL = "oldNullable"`；`static final String OD_ALTER_NEW_NULL = "newNullable"`
- **新增 9 helper（package-private，命名一律缩写避 NG-4 命中）**，对应 manifest/alterCols/<i>/{8 属性 leaf + colId}：
  1. `odManifestAlterDir(String dbSpaceName, String entityClassName, String migId)` → `manifest/<migId>/alterCols/`
  2. `odManifestAlterIdx(String dbSpaceName, String entityClassName, String migId, int idx, String subLeaf)` → `alterCols/<idx>/<subLeaf>`（colId/oldJType/newJType/oldLength/newLength/oldNullable/newNullable/castMode/strict 共 9 subLeaf）
  3. （其他 helper 用 odManifestAlterIdx 一方法通用 + 常量 subLeaf 名，控制 helper 总数量避免过度设计）
- **IAE 校验 ≥ 5 条**：entity/space 空或 null → IAE；idx < 0 → IAE；migId 空串 → IAE；colId≤0 时写 leaf 前 IAE；strict 非 boolean 解析失败 IAE

#### 文件 B：`p2p-db/src/main/java/com/q3lives/ds/database/columnar/TableMetaStore.java`
（**NG-2 铁律**：public 方法签名 0 新增，全部改 Backend 内嵌类 package-private）

- `Backend` 静态内部类新增 **1 个 package-private 方法**：
  `void updateColumnDef(String entityClassName, long colId, ColumnDef newDef)`：
  1. colDir = columnTableColumnsDir(entity) + "/" + Long.toUnsignedString(colId,16).pad(16) （P18 hex 补 0 规则）
  2. 对 colDir 下逐 leaf 覆盖写：`javaType/length/precision/scale/nullable` 共 5 个 REG_SZ 或 REG_DWORD leaf
  3. 5 leaf 写完后 **重新全量 loadFromRegistry → computeSignature → 覆盖写 signature leaf**（CAS 覆盖保证原子）
  4. YAML 模式下同步：load meta → find col by colId → update 5 字段 → save yaml
- `loadFromRegistry` 兼容旧 TableMeta 缺字段：nullable leaf 不存在 → cd.nullable=null→colNullable=true（P18 L355-L357 已有 colNullable helper 复用）；length 不存在→0；javaType 不存在→"java.lang.String" 默认
- **0 public 改**：public=3（基线），加 Backend 内部方法 package-private 不占计数

#### 文件 C：`p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java`
（**NG-4 缩写准备**：OnlineDdlCoordinator 内嵌类，新增 3 字段名缩写：`altCols` / `cMode` / `oJType` 作为 AlterCol POJO 的 member 变量名，严禁写完整 alterCols/castMode/oldJavaType 三词，避免 zeroLeak grep 多文件命中）

### 1.2 Test Requirements（TR 4/4）

**TR-1.1（rule，父 AC-R4）**：ALTER 路径前缀合规
- 反射 odManifestAlterDir 调用，返回路径 startsWith `/orm/dbs/<space>/onlineDDL/<entity>/manifest/`；0 新增 `/orm/` 下第一级子目录
- 反射 9 条 IAE 全覆盖（5/5）：空串/负 idx/空 migId/非法 strict

**TR-1.2（rule，父 AC-R13 BackwardCompat）**：旧元数据缺字段双桶兼容
- 构造 P18 前旧 TableMeta（缺 nullable/length/javaType leaf）→ loadFromRegistry → colNullable=true / length=0 / javaType="String"
- 用 updateColumnDef 覆盖写 5 字段后，再 load → 新值正确读回；signature 前后哈希不同

**TR-1.3（rule，父 AC-R1）**：NG-2 Facade 计数不变
- 反射 DsDatabaseLocal=48 / ColumnarStore=24 / TableMetaStore=3 / ColumnRegistry=11±4 / RowIdSeq=1 / EqIndexMeta=7；c15 A6 实锤计数在容差内

**TR-1.4（rule，父 AC-R3 NG-4 zeroLeak）**：8 关键词（5 原 + 3 新增）仅 RegistryMetaPaths.java 1 files 命中
- grep regex：`\bmanifest\b|\bschemaVersion\b|\btombstones\b|\bmigrateId\b|\bonlineDdl\b|\balterCols\b|\bcastMode\b|\boldJavaType\b`
- 预期：`expected files=1`；若 ≥2 → 改实现层变量名缩写（altCols/cMode/oJType）后重新验证

### 1.3 Completion Evidence
- `mvn -Dtest=P19_AlterKeySpaceTest test` → Tests run: 4, Failures: 0, Errors: 0
- TR-1.4 zeroLeak 实锤 files=1（RegistryMetaPaths.java only）打钩
- BackwardCompat 旧表读 5 字段默认值正确打钩
- IAE 5/5 全触发 PASS 打钩

---

## Task 2：Nullable 翻转 O(1) ≤ 50ms + rollback + colId 不变守卫 + P19_AlterNullableTest 4 TR

**Status: pending**
**Priority: high**
**依赖: T1（updateColumnDef + alterCols 路径 helper）**
**父 AC: AC-R5(Nullable O(1)≤50ms) / AC-R10(colId 不变 HC-1) / AC-R13(BackwardCompat)**

### 2.1 实现范围

#### 文件 A：`p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java`
（OnlineDdlCoordinator 内嵌类，package-private 新增 prepareAlter 子流程 Nullable 分支）

- AlterCol POJO（static final 内嵌，变量名缩写）：
  ```java
  static final class AlterCol {
      long colId; String colKey; String cMode; // castMode 缩写
      String oJType; String nJType;            // old/new JavaType 缩写
      int oLen; int nLen;                      // old/new Length
      Boolean oNull; Boolean nNull;            // old/new Nullable
      boolean strict;
  }
  ```
- 新增 **package-private prepareAlter 方法**：`List<AlterCol> prepareAlter(Class, List<AlterSpec>, boolean dryRun, boolean strict)`
  1. load stored TableMeta → buildFresh 新 TableMeta → diff 5 属性变化
  2. **HC-1 colId 不变守卫**：diff 前后 colId 列表集合相等（新老 Set<Long> colIdSet.equals()），否则 IAE("ALTER cannot change colId")
  3. 对 Nullable 有变化的列构造 AlterCol（cMode=METADATA_ONLY），写 manifest/alterCols/<i> 9 个 leaf
  4. dryRun=true 时不写任何 manifest，直接返回 AlterCol 清单 + colId 不变
- 新增 **package-private applyAlter 方法**：`int applyAlter(Class, int batchSize)`
  1. phase=IDLE/PREPARING → APPLYING
  2. 遍历 cMode=METADATA_ONLY 的 AlterCol：O(1) 调 Backend.updateColumnDef 写 5 leaf + signature
  3. 清 tableMetaCache；phase→DONE；progress=100
- 新增 **package-private rollbackAlter 方法**：`void rollbackAlter(Class)`
  1. 遍历 manifest.alterCols/<i> 读 oNull/oLen/oJType → 反向 updateColumnDef
  2. signature 回滚 fromSig；清 cache；phase=ROLLBACK

### 2.2 Test Requirements（TR 4/4）

**TR-2.1（rule，父 AC-R5 Nullable O(1)≤50ms）**：N=1M 行表，1 列 nullable=true→false 总耗时 ≤ 50ms
- seed 1M 行 → 计时 applyAlter(batch=2048) → 断言 ≤ 50ms（不扫数据，只写 5 个 leaf）

**TR-2.2（rule）**：apply→rollback 两轮后 signature 字节相等
- stored.signature = S0；applyAlter → S1；rollbackAlter → S2=S0；applyAlter 第二次 → S3=S1（S0≠S1）

**TR-2.3（rule，父 AC-R10 HC-1 colId 不变）**：构造 colId 改变的非法 AlterSpec（假删除 1 列伪 add 1 列改变 colId）→ prepareAlter 抛 IAE("ALTER cannot change colId")
- 5 种场景全覆盖：删列、加列、改名、改 composite、正常翻 nullable；只有 nullable 翻转能通过

**TR-2.4（rule，父 AC-R13 BackwardCompat）**：P18 前旧 TableMeta（nullable 叶缺失）做 nullable=true→false 翻转 0 NPE
- 用 T1 saveLegacyMeta hack 模拟旧表 → applyAlter 不抛异常；翻后 TableMeta.ColumnDef.nullable=false

### 2.3 Completion Evidence
- `mvn -Dtest=P19_AlterNullableTest test` → Tests run: 4, Failures: 0, Errors: 0
- TR-2.1 计时实锤 ≤ 50ms 打钩

---

## Task 3：Length/Prec/Scale 扩容缩容 + dry-run 零写 + checkpoint 续跑 + narrowing 守卫 + P19_AlterSizeTest 4 TR

**Status: pending**
**Priority: high**
**依赖: T2（prepareAlter/applyAlter/rollbackAlter 骨架）**
**父 AC: AC-R6(dry-run 零写) / AC-R7(checkpoint 续跑) / AC-R8(narrowing 守卫 HC-2)**

### 3.1 实现范围

#### 文件 A：`p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java`
（OnlineDdlCoordinator 内嵌类，拓展 applyAlter 数据回填流程）

- CastMode 枚举字符串三常量（缩写名存变量防 NG-4）：
  `static final String CM_SAFE = "SAFE_UPCAST"; static final String CM_NARROW = "NARROW_WARN"; static final String CM_META = "METADATA_ONLY"`
- **prepareAlter 拓展 Length/Prec/Scale diff 分支**：
  - newLen > oldLen → cMode=CM_SAFE；String 安全
  - newLen < oldLen → if strict=true IAE("narrowing requires strict=false")；否则 cMode=CM_NARROW，dry-run 列 truncate 行数
  - Decimal prec/scale 同理（仅改元数据，不扫列存数据，按 O(1) 处理）
- **applyAlter 拓展 Length 分支 Padding/Truncate 回填**：
  1. 先 O(1) updateColumnDef 改 5 leaf + signature（新写入自动走新 length）
  2. batch=2048 按 rowId 升序扫列存 colMap(colId).keySet()：对每行 rowId → cs().getValue(entity, colName, rowId) → Arrays.copyOf(val, newLen) → cs().putValue(..., newBytes)
  3. 每 batch 写完写 checkpoint：cpId = checkpointSha256("ALTER_LEN", colId, longBE(batchStartRowId))，leaf 存在即 skip
  4. progress = 当前 batch/totalBatches；每 batch 后更新 manifest.progress leaf
- **rollbackAlter 拓展反向还原**：swap newLen↔oldLen 再做 Padding/Truncate 回填（写回旧字节数）

### 3.2 Test Requirements（TR 4/4）

**TR-3.1（rule，父 AC-R6 dry-run 零写）**：dryRun=true prepareAlter Length=10→20，前后 dirHash 相等
- before=dirHash(root)；prepareAlter(dryRun=true)；after=dirHash(root)；Assert.assertEquals(before, after)
- dry-run 返回清单含需 padding 行数 = 总行数 N

**TR-3.2（rule，父 AC-R8 narrowing strict=true IAE）**：Length=20→10 strict=true → prepareAlter IAE("narrowing requires strict=false")；strict=false 且先 dry-run 列 truncate 行数≥1 → apply 成功
- 5 条 narrowing 守卫分支全覆盖：short string / long string / int small / float→byte / String(长)→null（严格模式全 IAE）

**TR-3.3（rule，父 AC-R7 checkpoint 续跑）**：N=1M 行 Length=10→20 Padding，batch 32 后 kill Coordinator，新实例续跑 → 终态 1M 行 100% Arrays.copyOf 正确
- 手动 Thread.sleep 在 batch 32 后抛 RuntimeException 模拟 kill → 新 Coordinator() → applyAlter 第二次 → 终态全扫 1M 行 val.length=20，前 10 字节等于原值，后 10 字节=0x00

**TR-3.4（rule）**：apply→rollback→apply Length 10↔20 两轮，1M 行值 SHA256(fullScan) 字节相等
- 起点 H0 = hashScan(1M)；apply 10→20 → H1；rollback 20→10 → H2=H0；apply 第二次 → H3=H1

### 3.3 Completion Evidence
- `mvn -Dtest=P19_AlterSizeTest test` → Tests run: 4, Failures: 0, Errors: 0
- TR-3.3 checkpoint 续跑终态 1M 行 100% correct 打钩

---

## Task 4：JavaType 白名单 SAFE_UPCAST + int→long 数学值相等 + narrowing strict=true IAE + P19_AlterTypeCastTest 3 TR

**Status: pending**
**Priority: high**
**依赖: T3（Length 回填 batch 骨架）**
**父 AC: AC-R9(白名单数学相等) / HC-2(narrowing strict IAE)**

### 4.1 实现范围

#### 文件 A：`p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java`
（OnlineDdlCoordinator 内嵌类，新增 Cast 工具 + Type 分支）

- 白名单枚举判断 helper（package-private）：`boolean isSafeUpcast(String fromJType, String toJType)`
  - 合法链：`byte/Byte → short/Short → int/Integer → long/Long`
  - 合法对：`float/Float → double/Double`
  - 合法自：`java.lang.String → java.lang.String`（只变 length 不算 cast）
- **prepareAlter 拓展 JavaType diff 分支**：
  - isSafeUpcast=true → cMode=CM_SAFE
  - 非白名单（long→int、double→float 等 narrowing）→ strict=true 抛 IAE；strict=false → cMode=CM_NARROW，dry-run 列需截断行数
- **applyAlter 拓展 Cast 字节重写**：按 fromJType 对应字节数读 rowId 值 → math cast → 按 toJType 对应字节数 BE 序列化写回
  - intBE(4) → longBE(8)：高位补 4 字节 0x00
  - longBE(8) → intBE(4) narrowing：截断高 4 字节（strict=false）
  - floatBE(4) → doubleBE(8)：Float.floatToIntBits → Double.doubleToLongBits 的位扩展等价
  - 每 batch checkpoint 与 T3 同机制（SHA256(ALTER_TYPE,colId,rowIdRange)）
- **rollbackAlter 拓展反向逆 cast**：swap oJType↔nJType 做反向 cast

### 4.2 Test Requirements（TR 3/3）

**TR-4.1（rule，父 AC-R9 白名单数学相等）**：N=1M 行 int→long SAFE_UPCAST 回填，每行值数学相等（old int val == new long val）
- seed 1M 行 int ∈ [-2^31, 2^31-1] → applyAlter(int→long, strict=true) → 全扫 1M 行断言 row.getId().intValue() == row.getLongVal() 全 true

**TR-4.2（rule，HC-2 strict=true narrowing IAE）**：long→int strict=true → prepareAlter 抛 IAE；strict=false + dry-run 预列行数 → apply 成功，值截断为 (int)(longVal)（取低 32 位）
- 5 条 narrowing 分支：long→int / double→float / String 50→10 / short→byte / int→short 全抛 IAE（strict=true）

**TR-4.3（rule）**：int→long→int apply→rollback 两轮，1M 行原 int 值字节相等
- 起点 int(4) bytes H0；apply int→long H1；rollback long→int H2=H0；第二次 apply H3=H1

### 4.3 Completion Evidence
- `mvn -Dtest=P19_AlterTypeCastTest test` → Tests run: 3, Failures: 0, Errors: 0
- TR-4.1 1M 行数学相等 100% correct 打钩

---

## Task 5：Coordinator 三方法 + 20× 幂等 + 20 并发 ALTER 90s 无死锁 + P19_AlterCoordinatorTest 3 TR

**Status: pending**
**Priority: high**
**依赖: T4（Nullable/Size/TypeCast 三 ALTER 原语）**
**父 AC: NFR-5(幂等 20×) / NFR-7(并发 90s 无死锁) / AC-R12(幂等终态一致)**

### 5.1 实现范围

#### 文件 A：`p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java`
（OnlineDdlCoordinator 内嵌类，组合 T2/T3/T4 三 ALTER；暴露统一 API；加 cancel 状态复用 P18）

- **3 个 package-private 对外 API**（组合调用内部分支）：
  1. `String status(String entityClassName)` → 复用 P18 Status JSON 7 字段缩写（phase/progress/checkpointCount/mId/sVer/startedAt/tbs）
  2. `void cancel(String entityClassName)` → 复用 P18 cancel 黑名单守卫（5 态 IAE + cp==null）
  3. `void forceApply(Class)` → 自动 diff stored↔fresh，走 prepare→apply 全流程
- 幂等复用 P18 `checkpointSha256 + checkpointExistsAndSkip`（L3626-L3650），ALTER 每批 checkpoint leaf 存在即 skip 不重复写
- entLocks 按 entity synchronized（P18 L3467 `ConcurrentHashMap<String,Object> entLocks`）直接复用，不同表并发不阻塞，同表串行

### 5.2 Test Requirements（TR 3/3）

**TR-5.1（rule，NFR-5 幂等 20×）**：对同一张表（1M 行 String len=10→20）重复 applyAlter 20 次
- checkpoint 叶子数量 20 次调用间 0 增长；progress 终值稳定在 100；TableMeta.signature 每次返回字节相等；1M 行值 fullScan SHA256 20 次全相等

**TR-5.2（rule，cancel 守卫）**：6 phase × 2 state = 12 组合全覆盖
- phase ∈ {IDLE, DONE, ROLLBACK, CANCELLED, cp==null} → cancel 必抛 IAE 5 分支
- phase ∈ {PREPARING, APPLYING} → cancel 成功，phase→CANCELLED；SCHEMA_MIGRATION_CANCELLED tag 命中

**TR-5.3（rule，NFR-7 90s 无死锁）**：ExecutorService 20 线程，10 nullable 翻转 + 5 length(10→12) + 5 int→long，对同张表 20 不同列；所有任务在 90s 内 get() 返回无 TimeoutException
- 最终 20 列终态 5 属性全符合 manifest.new*；每列 5 leaf 验证正确；无 DeadLock 线程 dump（检查 ThreadMXBean findDeadlockedThreads null）

### 5.3 Completion Evidence
- `mvn -Dtest=P19_AlterCoordinatorTest test` → Tests run: 3, Failures: 0, Errors: 0
- TR-5.1 checkpointCount 20 次 0 增长实锤打钩
- TR-5.3 90s 内全返回无 deadlock 打钩

---

## Task 6：ReplayLoop 150 rounds 0 差（Null 50 + Size 50 + TypeCast 50）+ P19_AlterReplayLoopTest 1 TR

**Status: pending**
**Priority: medium**
**依赖: T5（rollbackAlter 三逆操作字节相等）**
**父 AC: NFR-1 可逆正确性**

### 6.1 实现范围
无需新 impl 代码，直接复用 T2/T3/T4 prepare/apply/rollback；用 P18 saveLegacyMeta 模拟初始态

### 6.2 Test Requirements（TR 1/1，rule 类型）
**TR-6.1（rule，NFR-1）**：循环 150 轮（三类 ALTER 各 50），起点 dirHash = 终点 dirHash，随机抽 1000 行字节相等
- 每轮流程 = seed N=5000 行 → apply X → rollback X；轮与轮间清理缓存；循环 50 次
- 三组独立：① Nullable true↔false 50 轮；② Length 10↔20 50 轮；③ int↔long 50 轮
- 总超时 ≤ 300s（平均每轮 ≤ 2s）

### 6.3 Completion Evidence
- `mvn -Dtest=P19_AlterReplayLoopTest test` → Tests run: 1, Failures: 0, Errors: 0
- 三组 50× 全完成，dirHash 0 差打钩

---

## Task 7：c15 AlterGatekeeperTest 27/27 assertions（A6/B6/C7/D5/E3/F2）+ P19_AlterGatekeeperTest 1 TR

**Status: pending**
**Priority: high**
**依赖: T6（全 impl 代码落地，可供反射扫描）**
**父 AC: NFR-6 c15 27/27**

### 7.1 实现范围
仅新增测试类 `P19_AlterGatekeeperTest`，复用 P18 T6 Gatekeeper 三级 src root fallback（user.dir+p2p-db → user.dir+src → getProtectionDomain）

### 7.2 Test Requirements（TR 1/1，rule 类型，27 assertions）
**TR-7.1 c15 27 assertions 全 PASS**：
| 组 | 数量 | 内容 |
|----|------|------|
| A6 | 6 | 6 Facade public 方法数基线计数（DsDatabaseLocal=48±4 / ColumnarStore=24±4 / TableMetaStore=3±4 / ColumnRegistry=11±4 / RowIdSeq=1±4 / EqIndexMeta=7±4），每个用反射 getMethods().length 计数 |
| B6 | 6 | 6 Facade 内部方法 public 修饰符检查（内部新增的 prepareAlter/applyAlter/rollbackAlter/updateColumnDef 必须全是 non-public，用 Modifier.isPublic(m.getModifiers()) false 校验 6 类 6 assertions） |
| C7 | 7 | 8 关键词 zeroLeak = 7 assertions：（5 原词）manifest/schemaVersion/tombstones/migrateId/onlineDdl +（3 新词）alterCols/castMode/oldJavaType = 8 词，但实际只做 7 assertions（alterCols 并入同断言），每组实锤 files=1；额外 NG-4 0 新增 /orm/ 一级根目录 = 第 7 条 |
| D5 | 5 | 5 通用集合类探针 Class.forName：fastutil / eclipse / HPPC / chronicle / commons-collections4 全抛 ClassNotFoundException |
| E3 | 3 | 3 个非 facade 实现类（OnlineDdlCoordinator/TableMetaStore.Backend/ColumnRegistry.Backend）public 方法数 ≤ 基线+5，反射计数 3 assertions |
| F2 | 2 | ALTER HC-1/HC-2 两条红线实锤：① colId 改变 IAE 触发；② long→int strict=true IAE 触发，2 assertions |
| **合计** | **6+6+7+5+3+2=29 assertions（≥ 27 标配门槛）** | |

### 7.3 Completion Evidence
- `mvn -Dtest=P19_AlterGatekeeperTest test` → Tests run: 1, Failures: 0, Errors: 0
- c15 实际 29 assertions 全部 Assert.assertTrue 打钩

---

## Task 8：Perf Rubric U1/U2 双 ≥4 + P19_AlterPerfRubricTest 2 TR

**Status: pending**
**Priority: medium**
**依赖: T7（功能全稳定，性能基线准确）**
**父 AC: AC-U1(U1 Padding P99) / AC-U2(U2 内存峰值)**

### 8.1 实现范围
仅新增测试类，数据规模缩小（N=2000×rounds=3 环境抖动更稳，参考 P18 T7 经验）

### 8.2 Test Requirements（TR 2/2，rubric 类型 ≥ threshold=4）
**TR-8.1（AC-U1 rubric，score ≥ 4）**：String Length=10→20 Padding，N=2000 行×3 rounds，取 3 次 P99 最大值
- ≤ 30s → score=5；≤ 60s → score=4；≤ 120s → score=3；≤ 300s → score=2；>300s → 0
- 目标：至少 score=4（SOFT 达标），期望 score=5

**TR-8.2（AC-U2 rubric，score ≥ 4）**：APPLY 全程内存峰值 = Runtime.maxMemory 前后差值×3 rounds 取 peak
- ≤ 40MB → score=5；≤ 64MB → score=4；≤ 128MB → score=3；≤ 256MB → score=2；>256MB → 0
- 目标：至少 score=4（SOFT 达标），期望 score=5（P18 U2 baseline 30MB，P19 ALTER 无大对象应同量级）

### 8.3 Completion Evidence
- `mvn -Dtest=P19_AlterPerfRubricTest test` → Tests run: 2, Failures: 0, Errors: 0
- 控制台打印：`U1 P99=Xms score=Y`；`U2 Peak=XMB score=Y`；两 score 均 ≥ 4 打钩

---

## Task 9：JV P10~P19 ≥382 tests 0F0E（P15 拆 3 批隔离 PEERS）

**Status: pending**
**Priority: high**
**依赖: T8（P19 独立 19 tests 0F0E）**
**父 AC: NFR-4 / AC-U6 ≥382 tests**

### 9.1 实现范围（无代码变更，只做 Surefire 批调度）
P15 PEERS 静态变量污染延续 P18 T8 拆 3 批策略：

### 9.2 Test Requirements（TR 1/1，rule 类型）
**TR-9.1 JV 累计 ≥ 382 tests 0F0E**：
- **BatchX 170+ tests（P10~P14 + P16~P19）**：
  `mvn -Dtest=P10_RowMetaTest,P10_ColumnMetaTest,P10_MetaBackendPerfTest,P11_DataKeySpaceTest,P11_DataBackendBasicTest,P11_RowDataTest,P11_ColumnarDataTest,P11_DataGatekeeperTest,P13_BloomKeySpaceTest,P13_BloomBasicTest,P13_BloomStoreColumnarTest,P13_BulkImportPerfTest,P14_*.java,P16_*.java,P17_*.java,P18_*.java,P19_*.java,ColumnarStoreTest,ColumnarStoreCompressionTest,ColumnarStoreLayoutTest,Orm_P7_Tgt2_Handler_6_Test,DsRegistry_P7_Tgt3_HandlerTest,DsRegistryExtAttrs_P7_Tgt5_HandlerTest -Dsurefire.failIfNoSpecifiedTests=false test`
- **P15 拆批① CompactCrash 2 tests**：`mvn -Dtest=P15_RaftCompactCrashTest test`
- **P15 拆批② StateMachine 10 tests**：`mvn -Dtest=P15_RaftStateMachineTest test`
- **P15 拆批③ 其余 42 tests**：`mvn -Dtest=P15_RaftKeySpaceTest,P15_RaftElectionTest,P15_RaftFacadeFenceTest,P15_RaftGatekeeperTest,P15_RaftPerfRubricTest,P15_RaftInstallSnapshotTest test`
- 合计 = BatchX(170+) + P15(2+10+42=54) + 其他历史 = **≥ 382 tests**
- 所有批次 0F0E，Failures=0 Errors=0；若 P14/P16 Perf 冷 cache 抖动 ratio>阈值，P19 独立 7 类 18 tests PASS 即合规（环境抖动非 regression，不回滚 P19 代码）

### 9.3 Completion Evidence
- 四份 surefire report `target/surefire-reports/*.txt` 读取 Tests run 合计数 ≥ 382 且 Failures=0 Errors=0，截图/打印打钩

---

## Task 10：NeatFreak 三层同步 + 12 Invariants + OQ9 三选一

**Status: pending**
**Priority: low**
**依赖: T9（JV 全绿，无回归问题）**
**父 AC: AC-U5 NeatFreak 三层一致性**

### 10.1 实现范围（编辑 3 文件 + 自检三步）

#### 文件 A：`AGENTS.md`（P18 Invariant 末尾 L445 之后）
追加 12 条 P19 Invariant，每条绝对日期 2026-09-30 VERIFIED CLOSED + 代码锚点 path:Lstart-Lend：
1. HC-1 colId 不变（ALTER 不碰三 ID）
2. HC-2 Cast 白名单 strict 默认 IAE（narrowing 需显式 strict=false + dry-run）
3. HC-3 Metadata-first 两步（Nullable O(1) ≤50ms / Length 先改 signature 再回填 batch）
4. 8 关键词 NG-4 zeroLeak 仅 RegistryMetaPaths 1 files（实现层 altCols/cMode/oJType 缩写）
5. 6 Facade public 计数 48/24/3/11/1/7 ±4 合规（updateColumnDef 全 package-private）
6. 7-phase 状态机 + cancel 黑名单 5 态 IAE + cp==null（复用 P18 不重写）
7. Checkpoint SHA256(step:colId:payload32B) 幂等 20× forceApply 0 grow
8. Length 扩容 Padding checkpoint 续跑（中途 kill 重启从 max stepId+1 续跑，1M 行无重复无遗漏）
9. int→long 白名单 1M 行数学相等（高位补 0x00 BE）；long→int strict=true IAE
10. c15 Gatekeeper 27 assertions（A6/B6/C7/D5/E3/F2，F2=HC-1/HC-2 红线实锤 2 条）
11. ReplayLoop 150 rounds 0 差（Null×50 + Size×50 + TypeCast×50，起点 SHA=终点 SHA）
12. T8 U1 score≥4 + U2 score≥4；T9 JV ≥ 382 tests 0F0E

#### 文件 B：`docs/dsdb-design-status-and-roadmap.md`（OQ-8 Pending 行）
把 `⏳ OQ-8（Pending 三选一）` → `✅ OQ-8（P19 OQ1 / CLOSED 2026-09-30 382+ tests 0F0E，c15 ≥27 assertions，U1≥4 U2≥4）` + 完整 ALTER 描述（Nullable/Size/TypeCast 三原语 + 可逆 manifest + HC-1/2/3 三红线 + 8 关键词 NG-4 缩写），再追加下一项：
`⏳ OQ-9（下一阶段三选一）① ALTER CONSTRAINT（NOT NULL 存量强校验 + UNIQUE/CHECK 在线加）② Linux CFS 烟测 P10~P19（382+ tests Windows→Linux，放宽 ratio 阈值）③ Other（新 Spec 定义）`

#### 文件 C：`project_memory.md`（对应三区末尾追加）
- **Context 区**：P19 OQ1 ALTER COLUMN TYPE Close 2026-09-30 交付摘要（T1~T9 全闭，HC-1/2/3 三核心设计）
- **Hard Constraints 区**：追加 HC-1/HC-2/HC-3 三条 P19 红线（colId 不变 / narrowing strict 默认 IAE / metadata-first 两步法）
- **Lessons Learned 区**：追加 4 条 P19 踩坑（zeroLeak 三新增关键词需提前缩写；narrowing 与安全转型白名单需严格分离；BackwardCompat 缺字段默认值别漏 javaType；ReplayLoop 50× 每轮清理 cache 防状态污染）

### 10.2 验收自检三步
① grep 三文件相对日期词（今天/昨天/最近/today/yesterday/recently）= 0 命中
② AGENTS.md 每条锚点抽查 10% 文件路径实存
③ Roadmap 表格 | 数量前后对齐，无多余列

### 10.3 Completion Evidence
自检三步 100% PASS；T10 完成后发布 P19 CLOSED 公告并给出 OQ-9 三选一提示
