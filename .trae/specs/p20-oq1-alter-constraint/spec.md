# P20 OQ1 ALTER CONSTRAINT 在线约束（NOT NULL 存量强校验 + UNIQUE/CHECK 在线加）—— spec.md

**绝对日期：2026-10-03（零相对日期，禁今天/昨天/最近等词）**

---

## 1. 问题陈述（Problem / Users / Goals / Non-Goals）

### 1.1 问题是什么

p2p-db 存储引擎在 P18 Online DDL（ADD/DROP/RENAME）+ P19 ALTER COLUMN TYPE（Nullable/Size/TypeCast）六原语闭环后，**数据库约束（Constraint）四类完整性控制存在 3 类功能断层 + 2 类风险点**，当前只能在离线批量导入时人工校验，不支持在线加约束：

| 断层编号 | 现状（探查实锤 2026-10-03） | 业务影响 |
|---------|------------------------|---------|
| F-1 | **NOT NULL 强校验 0% 实现**：P19 Nullable 翻转只 O(1) 改 `columns/<colHex>/nullable` leaf（true→false 元数据），存量 NULL 行不扫描不报错；TableMetaStore L355-L357 `colNullable(c)` 读路径仍允许 `nullable==null` 兜底 true；apply 后新写入仍可能 bypass 校验（ColumnarStore putValue 未连 `!colNullable(c) && value==null` 抛 IAE 路径），导致 nullable=false 声明形同虚设，存量/增量仍有 NULL 数据 | 金融核心场景订单号声明 NOT NULL 后实际仍存若干历史遗留 NULL → JOIN 聚合丢数 + 下游对账 million 级差异 |
| F-2 | **UNIQUE 唯一约束 0% 在线实现**：DsEqIndexStore L18 注释明确 "V0.2 non-unique"；所有 putIndex(value,rowId) 全走 `Map<K,long[]>` 追加 rowId 数组，允许重复值；无冲突检测分支（putIndexUnique）；TableMetaStore.ColumnDef L460-L469 缺 `unique` / `constraintName` 两字段；CompositeGroup L471-L477 也缺 unique flag → 手机号、邮箱、订单流水号这类业务天然唯一键只能靠外部 MySQL 双写做去重，单引擎内无法保证全局唯一性 | 注册系统同一手机号重复创建 3 个账号，P19 UNIQUE 缺失无法检测，需人工脚本日更 + 补偿修复 |
| F-3 | **CHECK 校验 0% 实现**：grep 全代码库 "CHECK/checkExpr/expression eval" 0 匹配（只有 CRC32 checksum/CHECKPOINT 常量名）；无表达式求值器、无白名单语法树、无 ColumnDef.checkExpr 字段落盘；`length<=N` / `age BETWEEN 0,150` / `status IN {0,1,2}` 三类最常见 CHECK 无法约束 | 风控规则 age=99999 绕过人工校验入库，下游模型训练污染 |
| F-4 | **约束 apply 断点续跑 0% 实现**：UNIQUE 1000 万行全表扫 + EqIndexStore 冲突检测中途 kill → 重启重跑会重复写入 index（无 cp step 幂等）；NOT NULL 100 万行扫至 60% 失败 → 重跑从 rowId=0 重新扫浪费 CPU；违反 P18/P19 建立的 SHA256(step:colId:payload32B) checkpoint 幂等原则 | 大表加约束 6h 跑到 95% 崩溃 → 全量重来 累计 12h，运维 SLA 严重超标 |
| F-5 | **dry-run 预览 0% 实现**：dry-run=true 加约束不扫数据 → 只列约束类型名不列 violating rows 数量和前 100 行样例 → 运维误加 NOT NULL 立即抛 IAE 影响线上（HC-2 守卫），或加 UNIQUE 才发现 5000+ 重复值需 2 天人工清洗 | 加约束前无法评估风险 = 保守派永远不加约束，导致数据质量长期漂移 |
| F-6 | **rollback 逆操作 0% 实现**：约束 add 失败/误操作后无法秒级回滚（删除 constraint leaf + 清 EqIndexStore unique 分区 + 恢复 nullable=true）；只能手工删 registry leaf 重建 schema，P18/P19 ROLLBACK 三态（PREPARING→ROLLBACK→DONE）优势在约束分支完全断裂 | 误加 NOT NULL 触发 10000+ IAE 业务写入被拒绝 30min，回滚需删 registry 重启 DB，RTO=45min > 5min 目标 |

### 1.2 用户 / 受益人

- **数据治理/DBA 同事**：在线加 NOT NULL/UNIQUE/CHECK 三类约束，dry-run 预览 violating 样例 → 清洗 → 再 apply 正式生效；rollback 秒级撤销无 RTO；中途 kill 自动续跑
- **风控/业务产品同事**：历史遗留脏数据可控，约束上线不排停机窗口；UNIQUE 保证订单/手机号不重复；CHECK 保障枚举值和范围合规
- **未来接手 AI**：P18 ADD/DROP/RENAME + P19 ALTER + P20 CONSTRAINT 合计 10 原语 Schema Evolution 完整闭环，DDL 能力对齐单机 MySQL 80% 常用语法

### 1.3 交付目标（Goals — 必须可验证）

1. **ALTER CONSTRAINT 三原语全闭环**：NOT NULL（存量校验 + 增量拒绝）+ UNIQUE（单键 + 复合键 在线加，全表扫冲突检测）+ CHECK（白名单 3 类表达式 LEN/BETWEEN/IN），dry-run→apply→rollback 7-phase 完整；colId 不变，约束名持久化 `constraints/<name>/enabled` leaf
2. **violations 默认严格 HC-2**：apply 阶段发现 ≥1 违反行 → IAE 抛出清单 + 数量；dry-run 只列不抛；strict=false 跳过行并记录报告；UNIQUE 冲突检测用 EqIndexStore "先 put 再查 len>1 回滚" 原子分支
3. **零 Facade 新 public 签名**：NG-2 6 Facade 基线 P19 实锤（DsDatabaseLocal=48/ColumnarStore=24/TableMetaStore=3/ColumnRegistry=11/RowIdSeq=1/EqIndexMeta=7）±4 容差，0 新增 public；新 prepareConstraint/applyConstraint/rollbackConstraint 三方法放 OnlineDdlCoordinator 内嵌类 package-private
4. **SHA256 幂等对账复用 P19**：每约束 1 条 manifest.constraint/<name>；批扫每 2048 行写 checkpoint(step:colId:payload32B)；20× 重复 apply 终态一致；UNIQUE 索引分区写 checkpoint 独立目录
5. **性能基线**：N=1M 行 NOT NULL 存量扫 P99 ≤ 30s（HARD）/60s（SOFT）；UNIQUE 全表扫 + 建索引 P99 ≤ 120s（HARD）/240s（SOFT）；CHECK LEN 扫 P99 ≤ 40s（HARD）/80s（SOFT）；dry-run 模式下三种约束纯读 0 写盘

### 1.4 Non-Goals（明确不做，防范围蔓延）

- ❌ **跨节点 2PC 约束编排**：单机 Registry 内 manifest 落盘 + 状态机完整；跨节点 Raft 2PC 属于 OQ10 后续阶段
- ❌ **FOREIGN KEY 外键约束**：JOIN 完整性 + CASCADE DELETE 属于 FK 专项（需事务 2PL 保证），P20 只做单表三类约束
- ❌ **表达式通用求值器（JavaScript/Groovy 插件）**：CHECK 白名单仅支持 LEN(col)<=N / col BETWEEN lo,hi / col IN {v1,v2,...,vN} 三类静态模式；禁止动态表达式，防注入和性能抖动
- ❌ **全文 UNIQUE（TEXT/BLOB 大字段唯一）**：UNIQUE 支持 STRING fixedLen<=4096、LONG、INT、SHORT、BYTE 定长类型；超过 4KB 的 TEXT/BLOB 跳过加 unique 警告并抛（strict=true）/忽略（strict=false）
- ❌ **ALTER CONSTRAINT DROP 逆向**（DROP NOT NULL / DROP UNIQUE / DROP CHECK）：三 DROP 操作复用 P18 DROP COLUMN 骨架（删除 constraints/<name> leaf + tbs updateColumnDef unique=false + EqIndexStore clear 分区），复用 P18 ROLLBACK UNDELETE 逆操作，不单独做专项，直接用现有能力拼
- ❌ **增量写入实时校验触发器（写入路径 hook，需改 ColumnarStore.putValue 公共签名）**：NOT NULL/UNIQUE/CHECK 三类增量校验走「先 apply 约束 meta → 下次 ColumnarStore putValue 检查 colNullable(c) && value==null 抛 + UNIQUE 索引冲突实时抛 + CHECK 表达式实时代入」；严禁修改 NG-2 公共签名，走注解支路 layoutColumn 解析 ColumnDef 扩展字段；增量校验属于 NFR 软保证，只要 apply 全表扫通过即满足 AC，写入路径 hook 不纳入强制验收

---

## 2. 硬约束（Hard Constraints / 延续 NG 铁律 + P20 HC 三条新红线）

**所有约束属于 rule 类型，Gatekeeper c16（P20 新 gatekeeper）30/30 assertions 全 PASS 为前提。**

### 2.1 NG-2 六门面公共签名冻结（沿用 P19 实锤值，±4 容差）

| Facade 类 | 基线 public 方法数（2026-09-30 P19 Close 实锤） | 容差 | 合法区间 |
|----------|-----------------------------------------------|------|---------|
| `DsDatabaseLocal` | 48 | ±4 | 45~52 |
| `ColumnarStore` | 24 | ±4 | 21~28 |
| `TableMetaStore` | 3 | ±4 | 0~7 |
| `ColumnRegistry` | 11（P18 扩展 unDelete/renameColKey 已到顶） | ±4 | 7~15 |
| `RowIdSequenceStore` | 1 | ±4 | 0~5 |
| `EqIndexMetaStore` | 7 | ±4 | 4~11 |

- **合规扩展方式**：在 DsDatabaseLocal 内嵌 `OnlineDdlCoordinator` static final class 里加 3 个 package-private 方法 `prepareConstraint/applyConstraint/rollbackConstraint`；TableMetaStore Backend 加 package-private `updateConstraint(Class, ConstraintSpec)` + `getConstraints(Class)`；DsEqIndexStore 加 package-private `putIndexUnique(value,rowId) throws UniqueViolationException` + `clearPartition(String name)`，全部走 package-private 内嵌，绝不碰 Facade public
- **表 2.1 校验铁律**：c16 Gatekeeper A6（6 Facade 计数 单独测）+ B6（非 Facade public ≤ 5，其余扩展全走 protected/package-private/reflect）

### 2.2 NG-3 零侵入铁律（三 0 原则，沿用 P18/P19 实锤）

- **0 新 Maven 依赖**：`mvn dependency:tree` 与 P19 基线 0 新 artifact；表达式解析与状态机枚举手写，不引入 Apache Commons JEXL / Jackson 等依赖
- **0 新增 P2PCommand enum**：31201-31303 区间仍只保留 P15 Raft 6 条命令（CMD_ASK_VOTE/APPEND_ENTRIES/INSTALL_SNAPSHOT/VOTE_RESP/APPEND_RESP/SNAPSHOT_RESP），约束全走本地 Registry 路径
- **0 第三方通用集合类**：fastutil/eclipse-collections/HPPC/chroniclemap/commons-collections4 5 探针 `Class.forName` 全抛 `ClassNotFoundException`；UNIQUE 临时集合仍复用项目内置 `DsHashMap`（P10 已存在，NG-3 豁免）

### 2.3 NG-4 路径零外泄（延续 P18/P19 + P20 新增 5 约束关键词）

- 第一级 `/orm/` 下禁止新增根目录；约束 manifest 全部落在 **`/orm/dbs/<space>/onlineDDL/<entity>/manifest/constraints/<name>/`** 的下级扩展
- **P18 5 关键词 + P19 3 关键词 续用 zeroLeak 正则 8 条**：manifest / schemaVersion / tombstones / migrateId / onlineDdl / alterCols / castMode / oldJavaType → 仅 RegistryMetaPaths.java 声明；实现层缩写 odManifest/sVer/tbs/mId/od/altCols/cMode/oJType
- **P20 新增 5 关键词 zeroLeak 铁律（absolute）**：
  | 完整词（仅 RegistryMetaPaths.java 写 1 处） | 实现层缩写（所有 impl/测试 变量名 JSON 字段名必须用） |
  |----------------------------------------|--------------------------------------------------|
  | `constraint`                           | `cst`                                            |
  | `uniqueKey`                            | `uk`                                             |
  | `checkExpr`                            | `ck`                                             |
  | `notNull`                              | `nn`                                             |
  | `validateConstraint`                   | `vc`                                             |
- **P20 zeroLeak 正则**：`\bconstraint\b|\buniqueKey\b|\bcheckExpr\b|\bnotNull\b|\bvalidateConstraint\b` + P18/P19 8 条 = 合计 13 条全部 1 files 命中（RegistryMetaPaths.java）才算 AC-R3 PASS

### 2.4 P20 ALTER CONSTRAINT 专有三条 HC 红线（absolute 写入 project_memory Hard Constraints 区）

| 编号 | 红线内容 | 违规判定 / IAE 触发点 |
|------|---------|----------------------|
| HC-1 | **三 ID 不碰 + 约束名唯一定位**：任何 ALTER CONSTRAINT 分支（NOT NULL / UNIQUE / CHECK）绝不修改 colId / colKey / name 三列标识；UNIQUE/CHECK 新增字段写 `constraints/<cstName>/...` 子树通过 `<cstName>` 唯一定位，不覆盖原 columns/<colHex>/ 5 leaf；NOT NULL 翻转只写 `columns/<colHex>/nullable` + `constraints/<cstName>/type=NOT_NULL/col=<colHex>/enabled=false→true`，colId/colKey 全程只读比对 | prepareConstraint 读 manifest 中 colId 与 stored TableMeta 比对不等 → IAE("ALTER CONSTRAINT must not change colId, expected=X actual=Y") |
| HC-2 | **存量 violations 默认 strict=true 立即 IAE（HC-2 absolute）**：applyConstraint 默认 strict=true；NOT NULL 扫到 1+ NULL 行 / UNIQUE 扫到 1+ 重复值 / CHECK 扫到 1+ 表达式 false 行 → 立即 IAE("[vc] cstName=X type=Y violations=N sampleRowIds=[...]")；strict=false 只跳过行并写 violations 报告到 manifest.constraints/<name>/violations.count + violations.sample leaf，不抛 IAE；dry-run 模式永远只写报告不抛 IAE | apply 阶段 strict=true 且 violations.count>=1 → IAE 必须第 1 行（紧接 scan 统计完）抛出；严禁先写 enabled=true 再抛（顺序：扫→计数→IAE 或 扫→计数→写 enabled） |
| HC-3 | **约束两阶段提交 + enabled 原子切换（HC-3 absolute）**：applyConstraint 必须严格两阶段：① PREPARING+DRY_RUN 只写 manifest.constraints/<name>/{type,colRef,ckPattern,ukIndexed,strict,createdAt} 6 leaf，enabled 一律不写或=false；② APPLYING 批扫校验全通过（violations=0 或 strict=false 跳过）后，才写最后 1 个 leaf `enabled=true`；中途失败 rollbackConstraint 删整个 constraints/<name>/ 子树 + 恢复 columns/<colHex>/nullable 旧值（NOT NULL 场景） | PREPARING/DRY_RUN 阶段 enabled leaf 存在且=bool → 立即 IAE("enabled must not exist before APPLYING: cstName=X phase=Y")；Gatekeeper c16 F1 专项拦截 |

---

## 3. 功能需求（Functional Requirements — FR）

| 编号 | 功能（对应实现任务） | 关键子步骤（1~N，串行） |
|------|-------------------|----------------------|
| FR-1 | **Constraint KeySpace + Manifest 路径 helper（T1 实现）** | 1. RegistryMetaPaths 加 9 常量：`OD_MANIFEST_CST = "constraints"` / `OD_CST_NAME="cstName"` / `OD_CST_TYPE="cstType"` / `OD_CST_COL="cstCol"` / `OD_CST_CK="ck"` / `OD_CST_UK="uk"` / `OD_CST_NN="nn"` / `OD_CST_VIOLATIONS="vc"` / `OD_CST_ENABLED="enabled"`；2. 加 6 helper（缩写 cst/ck/uk/nn/vc 避 NG-4）：`odManifestCstDir(space,entity,migId)` → `manifest/<mId>/constraints/`；`odManifestCstLeaf(space,entity,migId,cstName,sub)` → `constraints/<cstName>/<sub>` （type/col/ck/uk/nn/enabled/vc_count/vc_sample）；3. 每 helper 5 条 IAE（space/entity/migId 空串、cstName 空、colId<=0、idx<0）；4. vc_sample 格式定义固定：半角逗号分隔最多 100 个 rowIdHex16 |
| FR-2 | **NOT NULL 存量强校验（T2 实现）**：nullable=true→false 先扫存量再写 enabled | 1. PREPARING：diff(stored.nullable=true vs fresh.nullable=false) 产出列；manifest.constraints/`<cstName>`/{type=NOT_NULL,col=<colHex>,nn=true,strict=<s>,createdAt} 写 5 leaf；2. DRY-RUN：批扫 N 行 统计 NULL 行数 count + 前 100 行 sample，写 vc.count + vc.sample leaf，0 写 columns/；3. APPLYING：① strict=true && vc.count>0 → IAE（HC-2 第 1 行）；② strict=false 或 vc.count=0 → 先写 `columns/<colHex>/nullable=false` 并重算 signature → 再写 `constraints/<cstName>/enabled=true`；4. ROLLBACK：删 constraints 子树 + 恢复 columns/<colHex>/nullable=true |
| FR-3 | **UNIQUE 在线加 + EqIndexStore 冲突检测（T3 实现）** | 1. PREPARING：manifest.constraints/<cstName>/{type=UNIQUE,col=<colHex 或 compositeGroup=X>,uk=true,strict=<s>}；2. DRY-RUN：批扫全表按列值建 DsHashMap<K,Integer> 计数，重复值 count>1 写 vc.count；3. APPLYING：① strict=true && vc.count>0 → IAE；② 清空并重扫：每批 2048 行调 `eqIndexStore.putIndexUnique(value, rowId)`（package-private 新分支：containsIndex(value, rowId'!=rowId) → false → removeIndex(value,rowId) 回滚+IAE vc 计数）；③ 全部通过写 constraints/<cstName>/enabled=true + columns/<colHex>/unique=true（BackwardCompat 旧代码 0 读 unique 字段不影响）；4. ROLLBACK：eqIndexStore.clearPartition(cstName) + 删 constraints 子树 + 恢复 columns/<colHex>/unique=false |
| FR-4 | **CHECK 白名单三类表达式（LEN / BETWEEN / IN）在线加（T4 实现）** | 1. PREPARING：manifest.constraints/<cstName>/{type=CHECK,ck="<pattern>",col=<colHex>}；ck 语法严格白名单 3 选 1：`LEN(<colKey>)<=N`（STRING 长度）、`<colKey> BETWEEN <lo>,<hi>`（LONG/INT/SHORT 数值范围）、`<colKey> IN {<v1>,<v2>,...,<vK>}` K<=64（枚举集合）；语法不在 3 类 strict=true 立即 IAE("ck pattern not whitelisted: X")；2. DRY-RUN：批扫每行解析 ck；不匹配行写 vc.count+vc.sample；3. APPLYING：① strict=true && vc>0 → IAE；② 通过后写 enabled=true + columns/<colHex>/ck=<pattern>（BackwardCompat 缺 ck=null 不校验）；4. ROLLBACK：删 constraints 子树 + 恢复 ck=null |
| FR-5 | **Coordinator 三方法 + 7-phase 复用 + cp 幂等（T5 实现）** | 1. DsDatabaseLocal 内嵌 OnlineDdlCoordinator 类加 3 package-private 方法：`List<ConstraintSpec> prepareConstraint(Class<? extends DsTableAdapter> clazz, List<ConstraintSpec> specs, boolean dryRun, boolean strict)` / `int applyConstraint(Class<?>, int batchSize)` / `void rollbackConstraint(Class<?>)`；2. 7-phase 状态机 100% 复用 P18：IDLE/PREPARING/DRY_RUN/APPLYING/DONE/ROLLBACK/CANCELLED；cancel 仅在 PREP/DRY/APPLY 三态允许，其他态 IAE；3. checkpoint 路径：`cpDir0 = altDir+"/checkpoints/<cstName>"`（每个约束独立子目录，复用 P19 cpDir 结构，SHA256 格式一致 0 新工具）；批扫每 batch=2048 写 1 cp，while 结束 `if(!batch.isEmpty())` 尾批 flush；3. Status JSON 7 字段复用 P19：phase/progress/cpCount/mId/sVer/startedAt/tbs，新增 cstCount（约束总数）用缩写，仍手写拼接无 Jackson |
| FR-6 | **TableMetaStore / EqIndexStore 扩展（NG-2 package-private 内嵌，T1 末实现）** | 1. TableMetaStore Backend 加 `void updateConstraint(String entity, ConstraintSpec spec)`：写 `constraints/<cstName>/*` 子树 6 leaf；加 `void enableConstraint(String entity, String cstName, boolean enabled)` 写 enabled 单 leaf；2. TableMetaStore loadFromRegistry 缺 unique/ck 字段 → cd.unique=false、cd.ck=null（BackwardCompat 双桶，旧表不受影响）；computeSignature 含 unique/ck 两字段（改 unique/ck 会导致 signature 变更走 CAS 更新）；3. DsEqIndexStore 加 2 package-private 方法：`void putIndexUnique(Object value, long rowId) throws UniqueViolationException`（contains 检查 → 冲突回滚 → IAE）、`void clearPartition(String cstName)` 清约束分区；不改动 public putIndex 接口 |

---

## 4. 非功能需求（Non-Functional Requirements — NFR）

| 编号 | 维度 / 类型 | 目标 / 观测方式 |
|------|-----------|---------------|
| NFR-1 | **可逆正确性（rule）**：NOT NULL + UNIQUE + CHECK 三类约束各 50 次 apply→rollback→apply 循环（150 rounds）；最终目录 hash(dirHash)=起点 hash；随机抽 1% 行值字节 = 起点字节；每个 constraints 子树存在/不存在与 apply/rollback 状态 1:1 | P20 T6 ReplayLoopTest（3×50=150 rounds） |
| NFR-2 | **NOT NULL 存量扫性能（rubric 5 级）**：N=1M 行 nullable=true→false strict=false 扫：<br>• P99 ≤ 30s → 5（HARD）<br>• P99 ≤ 60s → 4（SOFT）<br>• ≤120s → 3<br>• ≤240s → 2<br>• >240s → 0<br>验收 threshold≥4 | P20 T8 U1 PerfRubric（1M×3 rounds，P99 取 3 runs 最差） |
| NFR-3 | **UNIQUE 建索引性能（rubric 5 级）**：N=1M 行 STRING 32 字节 无重复值，strict=false 扫+建 EqIndexStore：<br>• P99 ≤ 120s → 5（HARD）<br>• ≤240s → 4（SOFT）<br>• ≤480s → 3<br>• ≤960s → 2<br>• >960s → 0<br>threshold≥4 | P20 T8 U2 PerfRubric（1M×3 rounds） |
| NFR-4 | **两阶段 HC-3 enabled 原子切换（rule）**：c16 Gatekeeper F1 专项 10 次随机中断在 APPLYING 99% 处（Thread.interrupt 模拟 kill）→ enabled leaf 要么=false/不存在（9 次中断），要么=true（1 次完成）；绝不出现 constraints 子树部分写入 + enabled=true 中间态 | P20 T5 t02 TwoPhaseCommit10InterruptTest 10/10 PASS |
| NFR-5 | **幂等性（rule）**：同一 manifest 重复 applyConstraint 20 次 → stepId 稳定 maxStep；progress=100% 不变化；constraints.enabled=true 始终为 true 不变；enabled leaf 值 20 次读取全等；1M 行扫 rowCount=1M×0 新增写入（无重复建索引 / 无重复写 nullable） | P20 T5 t01 Apply20xIdempotentTest |
| NFR-6 | **NG 合规（rule）**：c16 ConstraintGatekeeperTest 30/30 assertions 100% PASS：A6 Facade 计数 / B6 非 Facade ≤5 / C13 13 关键词 zeroLeak files=1（RegistryMetaPaths.java）/ D5 5 集合探针 ClassNotFound / E3 EqIndexStore.putIndexUnique package-private 反射可访问 / F3 HC-1/HC-2/HC-3 三红线各 1 次专项实锤 IAE（合计 6+6+13+5=30，精确） | P20 T7 P20_ConstraintGatekeeperTest |
| NFR-7 | **测试量门槛（rule）**：P10~P20 合跑 ≥ 408 tests 0F0E（P19 基线 386 + P20 ≥ 22）；P15 仍拆 3 批（CompactCrash 2 / StateMachine 10 / 其余 42）隔离 PEERS 静态污染；P16~P19 独立测试类保留不删不弱化 | P20 T9 JV 合跑 surefire 合计 |

---

## 5. 接受准则（Acceptance Criteria — 仅 rule / rubric 两类）

### 5.1 rule 类（客观二进制，16 条，100% PASS 为硬门槛）

| 编号 | 准则 | 证据来源 / TR 对齐 |
|------|------|------------------|
| AC-R1 | **NG-2 六门面 public 计数冻结**：6 Facade 各值 ±4 容差，全部合法区间内 | c16 A6 + T1 t03 ReflectCount |
| AC-R2 | **NG-3 三 0 原则**：0 新 Maven artifact / 0 新 P2PCommand enum / 5 通用集合探针 ClassNotFound 全抛 | c16 D5 5 probes + `mvn dependency:tree` diff P19→P20 |
| AC-R3 | **NG-4 13 关键词 zeroLeak files=1**：P18 5 + P19 3 + P20 5（constraint/uniqueKey/checkExpr/notNull/validateConstraint）= 合计 13 条词边界正则 → 仅 RegistryMetaPaths.java 命中；其余 impl/测试 0 命中（实现层 cst/uk/ck/nn/vc 缩写） | c16 C13 grep 实锤；T1 t04 zeroLeak PASS expectedFiles=1 |
| AC-R4 | **Constraint Manifest 路径合规**：所有 constraints 路径 startsWith `/orm/dbs/<space>/onlineDDL/<entity>/manifest/constraints/`；0 新增 `/orm/` 第一级子目录；constraints 子树 leaf 固定 9 种（type/col/ck/uk/nn/enabled/vc_count/vc_sample/createdAt）无额外 leaf | T1 t01 反射枚举常量 + 路径段 10 项检查 |
| AC-R5 | **HC-1 三 ID 不碰守卫**：prepareConstraint（NOT NULL/UNIQUE/CHECK 三场景各 1 次）故意构造 manifest.colId != stored.colId → 立即 IAE("ALTER CONSTRAINT must not change colId")；3/3 全触发 | T2 t03 / T3 t04 / T4 t03 三场景 IAE |
| AC-R6 | **HC-2 violations strict=true IAE**：① NOT NULL 1 行 NULL strict=true apply → IAE；② UNIQUE 2 行重复 strict=true apply → IAE；③ CHECK LEN<=4 1 行 "ABCDE" strict=true apply → IAE；3/3 IAE 抛在 enabled 写之前 | T2 t02 / T3 t02 / T4 t02 三分支 strict IAE |
| AC-R7 | **HC-3 两阶段 enabled 原子切换**：PREPARING/DRY_RUN 阶段读 enabled leaf → null 或 =false；APPLYING 中断 10 次 Thread.interrupt（9 次在 99% 处、1 次在最后写 enabled 前）→ enabled=true 仅出现 1/10 次；绝不出现 partial write + enabled=true | T5 t02 10Interrupts PASS；Gatekeeper F1 实锤 |
| AC-R8 | **NOT NULL 存量扫 correctness**：N=2000 行 seed 故意注入 5 行 NULL（colId 固定）；dry-run vc.count=5 + sample 含 5 行 rid；strict=false apply 后 columns.nullable=false + enabled=true；strict=true apply → IAE 且 columns.nullable 仍为 true（不修改） | T2 t01/t02 correctness 4 TR |
| AC-R9 | **UNIQUE EqIndexStore.putIndexUnique 冲突检测**：seed N=2000 行重复 value="dup" × 2 行 rid=500/rid=1501；dry-run vc.count=1 + sample 含 rid 500,1501；strict=false apply 时先删重复行后保留 1 行最终 eqIndex unique；strict=true apply IAE | T3 t01/t02 4 TR |
| AC-R10 | **CHECK 白名单 LEN/BETWEEN/IN 三类解析**：① LEN(name)<=5 + 行 "ABCDEF" → vc；② age BETWEEN 0,130 + age=999 → vc；③ status IN {0,1,2} + status=9 → vc；三类各 1 正例合法、1 负例不合法 strict=true IAE → 3/3 IAE + 3/3 PASS | T4 t01/t02 6 TR 全覆盖 |
| AC-R11 | **CHECK 非白名单语法拒绝**：`age > 18`（非三类）、`LEN(name)<N`（变量未展开）、`IN 无限集合 K>64`、`SCRIPT(...)` 四类场景 strict=true → IAE("ck pattern not whitelisted") | T4 t04 4/4 IAE |
| AC-R12 | **ROLLBACK 逆操作零残留**：三类约束 apply→rollback 各 1 轮；① constraints/<cstName>/ 子树全删（ls 返回空）；② columns/<colHex>/{nullable,unique,ck} 恢复旧值；③ eqIndex 约束分区 clearPartition 后 size=0；dirHash(起点) = dirHash(终点) | T6 t01/t02 dirHash 全等 |
| AC-R13 | **幂等 20× apply 终态一致**：同一 NOT NULL manifest + N=2000 行 0 NULL 行 → applyConstraint 调 20 次；stepId 稳定 maxStep；progress=100；enabled=true 20 次不变；nullable=false 全量不变；eqIndexStore.size() 相同；重复写 IAE 0 次 | T5 t01 20 repeat PASS |
| AC-R14 | **BackwardCompat 双桶**：P19 旧表（缺 unique/ck 两字段）loadFromRegistry ColumnDef → unique=false、ck=null；P20 加完约束后新代码读旧表 0 NPE 0 ClassCast；旧签名 computeSignature 与旧签名完全一致 | T1 t02 LegacyRead 5 条断言 |
| AC-R15 | **dry-run 模式纯读 0 写盘**：三类约束 dry-run=true，before/after SHA256(dirHash) 全等；Registry 写操作计数=0；列存 / 行存 写操作计数=0；constraints 子树存在或不存在取决于实现，但 enabled leaf 绝对 false/null | T2 t04 / T3 t05 / T4 t05 dryRunHash 3 PASS |
| AC-R16 | **JV 测试总量 ≥ 408 tests 0F0E**：P10-P19 基线 386（verified）+ P20 独立 ≥ 22（T1 4+T2 4+T3 4+T4 3+T5 3+T6 1+T7 1+T8 2 = 22 精准）= 408 门槛 | T9 Surefire 3 批合计统计 |

### 5.2 rubric 类（评估分级 0-5，6 条，每条 threshold≥4，至少 2 条 ≥ 5）

| 编号 | 维度 | 0 分 | 3 分 | 4 分（SOFT threshold） | 5 分（HARD） | 证据来源 |
|------|------|------|------|---------------------|------------|---------|
| AC-U1 | **NOT NULL 存量扫 1M 行 P99** | >240s | ≤120s | **≤60s** | ≤30s | T8 U1（3 rounds 取 P99 最差） |
| AC-U2 | **UNIQUE 1M 行建索引 P99** | >960s | ≤480s | **≤240s** | ≤120s | T8 U2（3 rounds 最差） |
| AC-U3 | **c16 Gatekeeper 断言密度** | <25 assertions | ≥28 | **≥30 assertions**（A6+B6+C13+D5+E3+F3 精准=30） | ≥33 assertions（HC-1/2/3 各加 1 分支覆盖） | T7 ConstraintGatekeeper assertionCount print |
| AC-U4 | **Constraint Manifest 检查点完整性** | <10 项 cp | ≥15 项 | **≥20 项**（prepare/type leaf 各 1/每 50 batches 1/rollback 每 50 batches 1/enabled 写 1/cancel 1/vc 采样 2） | ≥25 项（每列 vc 单独 checksum + cp 目录 per-cst 独写） | T3 t06 枚举 constraints 子树 + cp 子树 leaf 数量 |
| AC-U5 | **NeatFreak 三层文档一致性** | 缺 2 层以上 / 含相对日期 | 三层更新但缺 ≤3 条 Invariants | **三层完整 + 12 条 P20 Invariants 全覆盖 + 0 相对日期** | 三层 + HC-1/2/3 归档 project_memory Hard Constraints + OQ10 三选一预填 | T10 NeatFreak 三步自检 |
| AC-U6 | **JV 测试总量 P10-P20** | <380 tests | ≥400 | **≥408 tests**（P19 386 + P20 22） | ≥420 tests（超门槛 12+） | T9 Surefire batchCount 合计 |

**验收硬门槛**：16 rule AC 100% PASS；6 rubric AC 每条评分 ≥ 4（SOFT 达标），且至少 2 条 ≥ 5（HARD 达标）。

---

## 6. 上下文 / 依赖 / 开放问题

### 6.1 前置依赖（verified 可复用）

- ✅ **P18 Online DDL 7-phase 状态机骨架 100% 复用**：PREPARING→DRY_RUN→APPLYING→DONE→ROLLBACK/CANCELLED 流转、entLocks entity 级别 synchronized、cancel 黑名单 5 态 IAE、Status JSON 7 字段手写+escJson、migId 单调递增+history 30 份归档
- ✅ **P19 ALTER while-batch 框架复用**：batch=2048 累计 → 阈值 flush → `if(!batch.isEmpty())` 尾批 flush + cpDir per-migId（P20 扩展 cpDir per-cstName per-migId 双级隔离）+ SHA256(step:colId:payload32B) 幂等
- ✅ **TableMetaStore Backend L486-L644 完整可扩展**：updateColumnDef 已打通 5 leaf 覆盖写+重算 signature；新增 updateConstraint/enableConstraint 走相同 FileRegistry putS/putD 工具类；BackwardCompat 兜底默认值模式成熟（nullable==null→true/length==null→0）
- ✅ **DsEqIndexStore 已实现 range/contains/findByIndex 13 方法**：package-private 反射可达；新增 putIndexUnique 只在 `containsIndex(value, anotherRowId!=rowId)` 冲突检测+回滚，不改动现有 putIndex 公共签名（NG-2 合规）
- ✅ **P18/P19 测试 hack 4 件套复用**：`saveLegacyMeta()` 模拟旧 stored / `odCoord()` 反射取 Coordinator 实例 / `tmpRoot = Files.createTempDirectory("dsdb_p20_xxx_")` 每测独立空间 / `dirHash()` 目录 SHA256 断言 0 差异

### 6.2 开放问题（非阻塞，T1~T4 验证，不影响 SP4 批准）

- **Q1：UNIQUE 复合键（多列）如何索引？** 暂定复用 P17 Composite Range Index V3 的 128bit VarInt Packing `encodeCompositeKey` 打包多列值 → 转为 STRING key 写入 EqIndexStore；T3 实现阶段验证 compositeGroup.unique 标记是否与 CompositeRange 冲突，冲突则 IAE 提示
- **Q2：UNIQUE 分区 vs 旧 non-unique 分区？** 暂定 EqIndexStore 构造时 indexName = `uk_<cstName>` 独立分区，不干扰旧 non-unique indexName（P16 单 EQ indexName 独立）；rollback 时 clearPartition(indexName) 只清约束分区，0 影响业务原有非唯一索引
- **Q3：CHECK LEN 语义对 BINARY/STRING 一致？** 暂定 LEN(col) 一律走 `valueBytes.length`（二进制字节数，非 Unicode code point 数）；与 P19 ColumnarStore putValue validateValueLength `fixedLen` 定义统一；T4 t01 实锤 IAE 覆盖

### 6.3 版本对照（absolute 2026-10-03）

| Phase | 交付日期 | 关键指标（实锤） |
|-------|---------|----------------|
| P16 Range V2 | 2026-09-28 | 6 Facade 48/24/3/8/1/7；c12 25/25；192 tests |
| P17 Composite V3 | 2026-09-28 | c13 25/25；zeroLeak 改名避 NG-4；203 tests |
| **P18 Online DDL（CLOSED 2026-09-29）** | 2026-09-29 | 6 Facade 48/24/3/11/1/7；c14 25/25；364 tests≥230；U1=4 U2=5 |
| **P19 ALTER COLUMN TYPE（CLOSED 2026-10-03）** | 2026-10-03 | c15 29/29；zeroLeak files=1（8 keys）；JV 386；T8 U1=5 U2=5 |
| **P20 ALTER CONSTRAINT（本 spec，TARGET CLOSED 2026-10-0X）** | TBD | 目标：6 Facade 基线 ±4 不变；**c16 30/30 assertions**；13 keys zeroLeak files=1；JV ≥ 408 tests 0F0E；Rubric U1/U2/U3 threshold≥4，至少 2 条≥5 |
