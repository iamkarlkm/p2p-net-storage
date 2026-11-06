# P19 OQ1 ALTER COLUMN TYPE 列属性变更（Nullable/Size/TypeCast 可逆 Manifest + 数据回填 Batch）—— spec.md

**绝对日期：2026-09-30（零相对日期，禁今天/昨天/最近等词）**

---

## 1. 问题陈述（Problem / Users / Goals / Non-Goals）

### 1.1 问题是什么

p2p-db 存储引擎当前列属性变更存在 **6 类功能断层**（P18 Online DDL ADD/DROP/RENAME 三原语交付后，ALTER 四原语闭环缺失），生产环境列属性调整必须停机重建：

| 断层编号 | 现状（探查实锤） | 业务影响 |
|---------|---------------|---------|
| F-1 | **Nullable 翻转 0% 实现**：P18 在 TableMetaStore.ColumnDef L460-L469 补齐了 `public Boolean nullable` 持久化字段，computeSignature L359-L373 已包含 nullable 参与哈希，但 **无 DDL API 改 nullable**（true↔false 翻转需要重建 ColumnDef + 重算 signature + 扫行校验 NOT NULL，目前只能删列重建） | 上线后发现某列必须改 NOT NULL = 停机全量 DUMP→改表→LOAD，大表小时级 |
| F-2 | **Length/Precision/Scale 扩容 0% 实现**：DbColumnSchema L1-L19 已有 `length/precision/scale` 三 int 字段；TableMetaStore L509-L512 读 Registry 时三字段已落盘 `columns/<colHex>/{length,precision,scale}` 三 leaf，但 **无 DDL API 调这些值**；String length=10→20 或 Decimal prec=10→18 只能删列重建 | 业务字段长度规划不足 = 数据丢失风险 + 停机迁移 |
| F-3 | **JavaType 兼容向上转型 0% 实现**：byte→short→int→long / float→double 兼容转型在 ColumnarStore putValue 层已有 padding 逻辑（L171-L203 `Arrays.copyOf(valueBytes, fixedLen)`），但 **无 DDL API 做类型升级 + 旧值批量 cast 回填**；现有 4 字节 int 值升级到 long(8) 后读回字节截断 0xFF 错值 | 小表预估 int 不够用扩到 long = 数据错乱 + 人工逐行 cast |
| F-4 | **ALTER 无 batch data migration 幂等**：P18 ADD/DROP/RENAME 三原语 checkpoint SHA256 幂等已实现，但 ALTER 涉及数据改写（length 扩容 padding / type cast 字节重写）**无断点续跑**；中途 kill 重启后要么重复改要么漏改行 | 大表 ALTER 中途失败 = 数据半新半旧不可逆 |
| F-5 | **ALTER 无 dry-run 风险预览**：String length=20→10 缩容 / long→int 缩窄 / nullable=true→false 翻转 涉及数据 truncate 或 NOT NULL 校验，dry-run 只预览清单不做任何写入 0% 实现 | 运维误操作 ALTER TABLE name VARCHAR(10) = 百万行数据截断不可逆 |
| F-6 | **反向 ROLLBACK 无逆操作**：P18 ROLLBACK ADD=DELETE / ROLLBACK DROP=UNDELETE / ROLLBACK RENAME=反向 SWAP 三逆操作闭环；ALTER 对应逆操作（length 缩→扩 / type long→int 反向 cast / nullable false→true）0% 实现 | ALTER 后发现问题无法秒级回滚到原属性 |

### 1.2 用户 / 受益人

- **运维/DBA 同事**：ALTER 列属性不停机，支持 dry-run 风险预览、断点续跑 batch 回填、秒级 rollback 恢复原属性定义
- **业务产品同事**：字段长度扩容 / 类型升级 / NOT NULL 约束上线无需排期停机窗口
- **未来接手 AI**：P19 后 ADD/DROP/RENAME/ALTER 四种 Schema Evolution DDL 标准流程化，不再手动改 Registry leaf 触发 signature mismatch

### 1.3 交付目标（Goals — 必须可验证）

1. **ALTER 五属性全闭环**：Nullable 翻转 / Length 扩容缩容 / Precision / Scale / JavaType 兼容向上转型 五种属性变更全量 rewrite 可编排，dry-run→apply→（失败）rollback 三阶段状态机完整；colId 不变，所有历史数据行 ID 稳定
2. **可逆 0 数据丢失**：rollback 从 manifest.alterCols/<i>/old* 读旧属性恢复 100% ColumnDef 定义；length 缩容→扩容回滚后旧值字节 100% 等于起点值；type cast 向上→向下逆 cast 字节相等
3. **零 Facade 新 public 签名**：NG-2 6 Facade（DsDatabaseLocal=48 / ColumnarStore=24 / TableMetaStore=3 / ColumnRegistry=11≤12 / RowIdSeq=1 / EqIndexMeta=7）±4 容差，0 新增 public 方法；新 prepareAlter/applyAlter/rollbackAlter 三方法放 OnlineDdlCoordinator 内嵌类 package-private
4. **SHA256 幂等对账**：每套 ALTER 有唯一 migId + manifest，old/new 属性均有 SHA256；每个 batch cast/padding 完成写 checkpoint；重启从 max(stepId)+1 续跑，重复 apply 20 次终态字节相等
5. **回填性能基线**：100 万行 String length=10→20 扩容 padding batch=2048，P99 ≤ 60s（SOFT ≤ 120s）；Nullable-only 变更 O(1) ≤ 50ms（只改元数据，不扫数据）

### 1.4 Non-Goals（明确不做，防范围蔓延）

- ❌ **跨节点 2PC ALTER 编排**：单机 Registry 内 manifest 落盘 + TAG 状态机完整；跨节点 Raft 2PC 是 OQ 后续阶段，不在 P19 范围内（可复用 P15 Raft CMD_PUT_REGISTRY 包装）
- ❌ **Narrowing 缩窄自动接受**：String 长→短 / long→int / double→float 缩窄转型默认 IAE，dry-run 只列受影响行数清单，需运维显式 strict=false 才执行 truncate（防数据静默丢失）
- ❌ **在线索引重建（ALTER 后重建二级索引）**：索引列属性变更后 eqIndex/compositeIndex 需要 rebuild，属于二级索引 DDL 专项（可复用 P16/P17 manifest 骨架），本阶段不碰
- ❌ **物理文件级真空（VACUUM / compaction 回收磁盘空间）**：ALTER 改写后的旧 bucket 空间回收属于底层 compaction 专项，本阶段只写新值保留旧 bucket，读路径用新属性
- ❌ **列重命名（RENAME COLUMN）复用 P18**：RENAME 已在 P18 完整实现，P19 只处理同 colId 下同列名的属性变更，不碰 colKey 双向映射
- ❌ **NOT NULL 存量强校验**：nullable=true→false 翻转不扫存量值校验（dry-run 可选列出 NULL 行清单），业务需自行提前填值；强校验属于 OQ9 ALTER CONSTRAINT 专项

---

## 2. 硬约束（Hard Constraints / 延续 NG 铁律）

**所有约束属于 rule 类型，Gatekeeper c15（P19 新 gatekeeper）27/27 assertions 全 PASS 为前提。**

### 2.1 NG-2 六门面公共签名冻结（6 Facade ±4 容差，沿用 P18 实锤值）

| Facade 类 | 基线 public 方法数（2026-09-29 实锤） | 容差 | 违规判定 |
|----------|------------------------------------|------|---------|
| `DsDatabaseLocal` | 48 | ±4 | 45~52 合法，超出 FAIL |
| `ColumnarStore` | 24 | ±4 | 21~28 合法，超出 FAIL |
| `TableMetaStore` | 3 | ±4 | 0~7 合法，超出 FAIL |
| `ColumnRegistry` | 11（P18 新增 unDelete/renameColKey 扩展） | ±4 | 7~15 合法，超出 FAIL |
| `RowIdSequenceStore` | 1 | ±4 | 0~5 合法，超出 FAIL |
| `EqIndexMetaStore` | 7 | ±4 | 4~11 合法，超出 FAIL |

- **允许的合规扩展方式**：在 DsDatabaseLocal 内嵌 `OnlineDdlCoordinator` static final class 里加 package-private 方法 `prepareAlter/applyAlter/rollbackAlter` 三方法；TableMetaStore Backend 加 package-private `updateColumnDef(String entity, long colId, ColumnDef newDef)` 重写 5 个 leaf 并更新 signature；用 Java 反射从测试侧调内部 API

### 2.2 NG-3 零侵入铁律（三 0 原则，沿用 P18 实锤值）

- **0 新增 Maven 依赖**：`mvn dependency:tree` 与 P18 基线 byte-for-byte 0 新 artifact；Status JSON 仍用手写拼接 + escJson 自研转义，不引入 Jackson/Gson
- **0 新增 P2PCommand enum**：31201-31303 区间仅保留 P15 Raft 6 条命令，P19 ALTER 全走本地 Registry 路径
- **0 第三方通用集合类**：fastutil / eclipse-collections / HPPC / chroniclemap / commons-collections4 五探针 `Class.forName` 全抛 `ClassNotFoundException`，grep 源码 0 import 上述 5 包

### 2.3 NG-4 路径零外泄（延续 P18 铁律 + 新增 ALTER 关键词）

- 第一级 `/orm/` 下禁止新增根目录；所有 ALTER manifest 结构必须落在 **`/orm/dbs/<space>/onlineDDL/<entity>/manifest/alterCols/<i>/`** 的下级扩展，零新增第一级根
- 5 关键词零外泄正则：`\bmanifest\b|\bschemaVersion\b|\btombstones\b|\bmigrateId\b|\bonlineDdl\b`，仅 RegistryMetaPaths.java 1 files 命中；实现层变量名/JSON 字段名一律缩写：odManifest / sVer / tbs / mId / od（沿用 P18 缩写）
- **新增 ALTER 3 关键词**：`alterCols` / `castMode` / `oldJavaType`（3 个完整词只在 RegistryMetaPaths 声明，实现层用缩写 altCols / cMode / oJType 避 zeroLeak grep 命中）

### 2.4 ALTER 专有铁律（P19 新安全栅，延续 project_memory Hard Constraints 区末尾追加）

| 编号 | 红线内容 | 违规判定 |
|------|---------|---------|
| HC-1 | **colId 不变原则**：ALTER 只改 ColumnDef 5 个属性（nullable/length/precision/scale/javaType），colId / colKey / name 三 ID 字段永不修改；rollback 也不碰 colId | prepareAlter 发现 manifest 里 colId 与 stored 不一致 → IAE("ALTER must not change colId") |
| HC-2 | **Cast 白名单原则（默认 strict=true）**：允许的安全向上转型：① byte→short→int→long（整型升链，逐字节 0x00 sign extend BE）；② float(4)→double(8)（字节扩展）；③ String(n)→String(m) where m≥n（byte[] padding 0x00）；④ boolean 不变；白名单外的 narrowing（long→int、String 长→短、double→float）默认 strict=true 一律 IAE，需运维显式 strict=false + dry-run 预览 truncate 行数 ≥ 1 后通过 | applyAlter 检测到 narrowing 且 strict=true → IAE("narrowing requires strict=false and dry-run preview") |
| HC-3 | **元数据优先 + 数据懒回填**：Nullable 翻转 / javaType 同类型 length 小扩容（4→8 且无数据）等场景，prepare→apply 的第一阶段 O(1) 只改 Registry 5 个 leaf 并重算 signature（不扫数据）；第二阶段 batch cast/padding 按 rowId 升序扫，batch=2048 写 checkpoint 幂等 | Nullable-only 变更 N=1M 行总耗时 ≤ 50ms（O(1)，T2 t01 计时断言） |

---

## 3. 功能需求（Functional Requirements — FR）

| 编号 | 功能 | 关键子步骤 |
|------|------|-----------|
| FR-1 | **Manifest KeySpace + ALTER 子目录 helper（T1 实现）** | 1. RegistryMetaPaths 加 `OD_MANIFEST_ALTER = "alterCols"` 子目录常量 + 9 helper：odManifestAlterDir / odManifestAlterOldJavaType / odManifestAlterNewJavaType / odManifestAlterOldLength / odManifestAlterNewLength / odManifestAlterOldNullable / odManifestAlterNewNullable / odManifestAlterCastMode / odManifestAlterStrict<br>2. 每个 helper 5 条 IAE（colId≤0 / length<0 / entity 空 / space 空 / strict 非 boolean）<br>3. CastMode 枚举三值字符串：`SAFE_UPCAST`（白名单内扩）、`NARROW_WARN`（narrowing strict=false）、`METADATA_ONLY`（nullable 翻转等不碰数据） |
| FR-2 | **Nullable 翻转 O(1) 元数据变更（T2 实现）** | 1. PREPARING：diff(old,new) 产出 nullable 有变化的列清单 → 写 manifest.alterCols/<i>/{oldNullable,newNullable=METADATA_ONLY,colId,colKey}<br>2. APPLY：① O(1) 调 TableMetaStore.Backend.updateColumnDef(entity, colId, newDef) 重写 nullable leaf 并 CAS 更新 signature；② 清 tableMetaCache；③ progress=100 phase=DONE<br>3. ROLLBACK：按 manifest 读 oldNullable 反向 updateColumnDef，phase=ROLLBACK |
| FR-3 | **Length/Precision/Scale 扩容 + Batch Padding 回填（T3 实现）** | 1. PREPARING：diff 产 length/newLength 清单；String 扩容 castMode=SAFE_UPCAST；String 缩容 strict=true→IAE / strict=false→NARROW_WARN 列 truncate 行数；Decimal prec/scale 同理<br>2. DRY-RUN：统计总需 padding / truncate 行数 + 预览前 100 行旧值字节→新值字节，0 写 Registry/列存<br>3. APPLY：① 先 O(1) 改 3 个 leaf + signature（立即生效，新写入走新 length）；② batch=2048 按 rowId 升序扫列存旧值：newLen>oldLen 用 Arrays.copyOf(old,newLen) 补 0 写回；newLen<oldLen strict=false 用 Arrays.copyOf(old,newLen) 截断写回；③ 每 batch 写 checkpoint（sha256(batchStartRowId:batchEndRowId:newLen)）；④ progress=batchIndex/totalBatches<br>4. ROLLBACK：反向 swap oldLen↔newLen 重做 padding/truncate |
| FR-4 | **JavaType 兼容向上转型 + Cast 字节重写（T4 实现）** | 1. PREPARING：检测 cast 是否在白名单（byte→short→int→long / float→double / String 同类型）；白名单外 narrowing strict=true→IAE；写 manifest.alterCols/<i>/{oldJType,newJType,castMode,strict}<br>2. APPLY：① 先 O(1) 改 javaType leaf + signature；② batch=2048 扫旧值按 javaType 对应字节数读：byte(1)/short(2)/int(4)/long(8)/float(4)/double(8)，转型后 BE 序列化写新字节：intBE→longBE (高位补 0) / floatBE→doubleBE / String→String padding<br>3. ROLLBACK：反向 cast（long→int 高位截断、double→float 截断、String padding 截原长）恢复原字节 |
| FR-5 | **OnlineDdlCoordinator 扩展 ALTER 三方法 + 状态机复用（T5 实现）** | 1. 在 DsDatabaseLocal 已有 OnlineDdlCoordinator 内嵌类内加 3 个 package-private 方法：`prepareAlter(Class, List<AlterSpec>, boolean dryRun, boolean strict)` / `applyAlter(Class, int batchSize)` / `rollbackAlter(Class)`<br>2. 7-phase 状态机 + cancel 白名单完全复用 P18：PREPARING→DRY_RUN→APPLYING→DONE→ROLLBACK / CANCELLED；cancel 仅 PREP/DRY/APPLY 三态允许<br>3. checkpoint SHA256(step:colId:payload32B) 幂等完全复用 P18 代码，无需新工具<br>4. Status JSON 7 字段（phase/progress/checkpointCount/mId/sVer/startedAt/tbs）结构 0 改，沿用 P18 缩写 NG-4 合规 |
| FR-6 | **TableMetaStore Backend 扩展 updateColumnDef（NG-2 合规 package-private）** | 1. Backend 静态内部类加 `void updateColumnDef(String entityClassName, long colId, ColumnDef newDef)`：逐 leaf 覆盖写 `columns/<colHex>/{javaType,length,precision,scale,nullable}` 5 个 leaf（原 leaf 存在即覆盖，不存在补写）<br>2. 写完 5 个 leaf 后立即重新 `computeSignature(TableMeta)` CAS 更新 signature leaf；旧 signature 归档 manifest.fromSig<br>3. BackwardCompat 双桶：旧 P18 前 TableMeta 缺 nullable/length 字段，读时默认 nullable=true/length=0/javatype=String（不影响 ALTER 新老并存） |

---

## 4. 非功能需求（Non-Functional Requirements — NFR）

| 编号 | 维度 | 目标 | 观测方式 |
|------|------|------|---------|
| NFR-1 | **可逆正确性（rule）**：Null/Size/Type 三类 ALTER 各跑 50 次 apply→rollback→apply 循环：最终 TableMeta.signature ≡ 起点 signature × 100%；随机抽 1% 行值字节 ≡ 起点值字节 × 100% | P19 T6 ReplayLoopTest（3×50=150 rounds） |
| NFR-2 | **ALTER Padding 回填性能（rubric 5 级）**：String length=10→20，N=1M 行 × batch=2048：<br>• P99 ≤ 30s → 5 分（HARD）<br>• P99 ≤ 60s → 4 分（SOFT）<br>• P99 ≤ 120s → 3 分<br>• P99 ≤ 300s → 2 分<br>• >300s → 0 分（FAIL）<br>验收阈值：score ≥ 4（SOFT 达标） | P19 T8 U1 PerfRubric N=1M×3 rounds |
| NFR-3 | **元数据-only ALTER O(1)（rule）**：Nullable 翻转（不扫数据）N=1M 行耗时 ≤ 50ms；Registry 仅写 5 leaf + 1 signature = 6 write op | P19 T2 t01 计时断言 |
| NFR-4 | **测试量门槛（rule）**：P10~P19 合跑 ≥ 382 tests 0F0E（P18 基线 364 + P19 ≥ 18，≥ 382）；P15 仍拆 3 批隔离 PEERS 静态污染；P16/P17/P18 独立测试类全保留不删 | P19 T9 JV 合跑 surefire 合计 |
| NFR-5 | **幂等性（rule）**：同一 manifest 重复调用 applyAlter 20 次 → 终态数据字节一致 100%；中间态 checkpoint stepId 不重复累加；progress 值稳定在 100%；20 次 apply 间 0 行重复写无 0 行漏写（计数 1M×20 次 write 不丢） | P19 T5 t01 IdempotentTest 20× repeat |
| NFR-6 | **NG 合规性（rule）**：c15 Gatekeeper 27/27 assertions PASS（A6/B6 基线计数 + C7 8 关键词 zeroLeak + D5 集合类 + E3 非 facade public≤5 + F2 ALTER HC-1/HC-2 红线实锤=6+6+7+5+3+2=27） | P19 T7 AlterGatekeeperTest |
| NFR-7 | **ALTER 并发无死锁（rule）**：20 线程并发 ALTER 同表 20 列不同属性（10 nullable × 5 length × 5 javatype），90s 内全跑完 0 死锁；每列终态属性正确，每列 5 个 leaf 值终态符合 Manifest.new* | P19 T5 t03 AlterConcurrentTest |

---

## 5. 接受准则（Acceptance Criteria — 仅 rule / rubric 两类）

### 5.1 rule 类（客观二进制，可观测 PASS/FAIL，共 14 条）

| 编号 | 准则 | 证据来源 / 观测方式 |
|------|------|------------------|
| AC-R1 | **NG-2 六门面 public 签名冻结**：DsDatabaseLocal=48 / ColumnarStore=24 / TableMetaStore=3 / ColumnRegistry=11 / RowIdSeq=1 / EqIndexMeta=7，每个 ±4 内 | c15 Gatekeeper A6/B6 反射计数，T7 PASS |
| AC-R2 | **NG-3 零侵入**：0 新 Maven 依赖 / 0 新 P2PCommand / 5 通用集合探针 ClassNotFound 全抛；Status JSON 手写拼接无 Jackson import | c15 D5 5 probes + diff `mvn dependency:tree` P18 vs P19，T7 PASS |
| AC-R3 | **NG-4 8 关键词零外泄**：5 原关键词（manifest/schemaVersion/tombstones/migrateId/onlineDdl）+ 3 新关键词（alterCols/castMode/oldJavaType）合计 8 正则词边界匹配，仅 RegistryMetaPaths.java 1 files 命中；实现层一律用缩写（odManifest/sVer/tbs/mId/od/altCols/cMode/oJType） | c15 C7 grep regex 实锤；T1 t04 zeroLeak PASS（files=1） |
| AC-R4 | **Manifest ALTER 路径合规**：所有 alterCols 路径均以 `/orm/dbs/<space>/onlineDDL/<entity>/manifest/alterCols/` 为前缀，不新增 /orm/ 第一级根目录；不新增非 RegistryMetaPaths 声明的常量字面量 | T1 t01 反射枚举常量 + 路径段检查 10 项 PASS |
| AC-R5 | **Nullable O(1) ≤ 50ms + rollback 双态正确**：N=1M 行 nullable=true→false 元数据变更计时 ≤ 50ms（只改 5 个 leaf，不扫数据）；rollback 后 nullable 恢复 true；computeSignature 前后哈希匹配 | T2 t01/t03 PASS |
| AC-R6 | **Length 扩容 dry-run 零写盘**：dryRun=true 时 Registry / 列存 / 行存 三端写操作 0；manifest.phase=DRY_RUN 仅输出需 padding 行数 + 前 100 行预览值；totalRows=N 与全表行数一致 | T3 t01 dryRun 前后 SHA256(dirHash) 相等 PASS |
| AC-R7 | **Length 扩容 batch=2048 checkpoint 断点续跑**：在 batch 32/488 中间 kill Coordinator，新实例重启后从 max(stepId)=33 续跑，终态 1M 行 100% 值字节等于 Arrays.copyOf(origin, newLen)，无重复写无遗漏 | T3 t03 中途 kill（Thread.interrupt + new Coordinator）→ 续跑 PASS |
| AC-R8 | **Narrowing 守卫 HC-2 strict=true IAE**：String length=20→10 strict=true → applyAlter 立即抛 IllegalArgumentException("narrowing requires strict=false")；strict=false 先 dry-run 输出 truncate 行数≥1，再 apply 才真正截断；rollback 用 oldLength 恢复原长 | T3 t02 守卫 5 条分支（4 type narrowing × 1 strict bool）全覆盖 PASS |
| AC-R9 | **JavaType 白名单 SAFE_UPCAST 正确**：int(4)→long(8) N=1M 行 batch=2048 转型后，每行值 = (long)oldInt；高位补 0 BE 序列化后读回值数学相等；float→double 同理；白名单外 (long→int strict=true) IAE | T4 t01/t02 PASS（int→long 1M 行数学值相等 × 白名单外 IAE） |
| AC-R10 | **colId 不变 HC-1 守卫**：prepareAlter 检测 manifest.alterCols 中任何 colId 与 stored TableMeta 的 colId 对不上 → 立即抛 IAE("ALTER cannot change colId")；所有 manifest 新旧属性 diff 不包含 colId/colKey/name 三 ID 字段 | T2 t05 / T5 t02 colId invariant 双测 PASS |
| AC-R11 | **ROLLBACK 逆操作 apply→rollback→apply 字节相等**：3 类 ALTER（Nullable×50 + Length×50 + TypeCast×50 = 150 rounds）循环；起点 dirHash = 终点 dirHash；随机抽 1000 行字节 100% 相同 | T6 t04 ReplayLoop150Rounds PASS |
| AC-R12 | **幂等 20× 终态一致**：同一 prepareAlter 产出的 manifest，applyAlter 调 20 次 → stepId 最终稳定在 maxStep；20 次 progress 差值=0；TableMeta.signature 每次返回值字节相等；1M 行值 SHA256(fullScan) 每次相等 | T5 t01 20 repeat PASS |
| AC-R13 | **BackwardCompat 双桶**：P18 旧表（nullable 字段缺失）读 ALTER 新代码，缺 nullable leaf 默认 true、缺 length leaf 默认 0、缺 javatype leaf 默认 String；P19 ALTER 生成的新 manifest 不影响 P18 以前的数据读路径 0 NPE | T2 t04 LegacyRead 旧元数据读 PASS |
| AC-R14 | **NFR-7 20 并发列 ALTER 90s 无死锁**：20 线程同时对同一张表的 20 个不同列做属性变更（10 nullable × 5 length × 5 javatype），90s 内所有线程正常返回；最终 20 列终态属性每个符合预期；manifest.phase=全部 DONE | T5 t03 ConcurrentTest 90s timeout PASS |

### 5.2 rubric 类（评估分级 0-5，共 6 条，验收阈值 threshold≥4）

| 编号 | 维度 | 0 分 | 3 分 | 4 分（SOFT 阈值） | 5 分（HARD 阈值） | 证据来源 |
|------|------|------|------|------------------|------------------|---------|
| AC-U1 | **Length 扩容 1M 行 Padding P99** | >300s | ≤120s | ≤60s | ≤30s | T8 U1 PerfRubric N=1M×3 rounds |
| AC-U2 | **JavaType Cast 全流程内存峰值**（vs P18 基线 30MB = P18 U2 score=5 基线） | >256MB | ≤128MB | ≤64MB | ≤40MB | T8 U2 Runtime.maxMemory 快照 × 3 rounds 取 peak |
| AC-U3 | **c15 Gatekeeper 断言密度** | <22 assertions | ≥25 assertions | ≥27 assertions (A6+B6+C7+D5+E3+F2=27 标配) | ≥30 assertions (扩展 F2 额外 narrowing / colId invariant 守卫 3 项) | T7 AlterGatekeeperTest assertion counter 打印 |
| AC-U4 | **ALTER Manifest 细粒度检查点完整性** | <8 项 checkpoint | ≥12 项 checkpoint | ≥16 项 checkpoint（prepare/改 5 leaf/改 signature/每 50 batches/rollback 每 50 batches/phase=DONE/CANCELLED） | ≥20 项 checkpoint（含每个列 5 leaf 单独 checksum） | T3 t04 枚举 manifest 子树 leaf 数量统计 |
| AC-U5 | **NeatFreak 三层文档一致性**（project_memory / AGENTS / roadmap） | 缺 2 层以上 / 含相对日期 | 三层有更新但缺 ≤3 条 Invariants | 三层同步完整 + 12 Invariants 全覆盖 + 0 相对日期 | 三层 + 额外 history 归档 3 条 Hard Constraints（HC-1/2/3）+ OQ9 三选一预填 | T10 NeatFreak 输出 |
| AC-U6 | **JV 测试总量（P10~P19）** | <350 tests | ≥370 tests | ≥382 tests (HARD 门槛，P18 364 + P19 18) | ≥395 tests（超门槛 13+，P19 补了额外专项） | T9 Surefire report 合计 batchX 3 批 |

**验收阈值**：6 rubric 每条评分 ≥ 4（SOFT 达标），至少 2 条 ≥ 5（HARD 达标）。14 rule 100% PASS。

---

## 6. 上下文 / 依赖 / 开放问题

### 6.1 前置依赖

- ✅ **P18 Online DDL 骨架 90% 复用**：7-phase 状态机、checkpoint SHA256(step:colId:payload32B) 幂等、entLocks 按 entity 级别 synchronized、cancel 黑名单 5 态 IAE、Status JSON 7 字段手写拼接 + escJson、migId monotonically increasing + history 归档 — 全部 0 重写直接复用
- ✅ **TableMetaStore.ColumnDef 5 属性已落盘**：P18 已实装 nullable=true/false、length/precision/scale、javaType 6 字段（加 colId/colKey/name 三 ID），updateColumnDef 只需要在 Backend 里做 5 leaf 覆盖写 + 重算 signature
- ✅ **ColumnarStore padding 逻辑已实现**：L188-L192 `if (fixedLen > 0 && valueBytes.length < fixedLen) padded = Arrays.copyOf(valueBytes, fixedLen)`；ALTER 后新写入自动走新 length，无需代码改动
- ✅ **ColumnarStore getBucketColumnValue rc.length 已对齐**：L871-L891 读时 `len = readLen>0 ? readLen : c.length`；ALTER length 改后读取旧值若字节不足，会自然 return 实际 raw 字节（返回 shorter 数组，符合 Java 数组读语义）
- ✅ **P18 测试 4 大 hack 可复用**：① `saveLegacyMeta()` 模拟旧版 stored signature；② `odCoord()` 反射取 OnlineDdlCoordinator 内嵌实例；③ `tmpRoot = Files.createTempDirectory("dsdb_p19_xxx_")` 每测试独立隔离空间；④ `dirHash()` 目录 SHA256 断言 0 差异

### 6.2 开放问题（非阻塞，T1~T3 阶段可定，不影响 Spec SP4 批准）

- **Q1：默认 batchSize？** 暂定 2048（与 P13 BulkImportPerf 基线、P18 ADD 回填对齐），可通过 applyAlter 参数覆盖；T3 Perf 阶段验证最优值
- **Q2：history 归档保留几份？** 暂定 30 份（P18 20 份 + ALTER 变更更频繁加 10 份）；nextMigId 满 30 份删最旧一份；Registry 膨胀风险 T1 KeySpace 阶段验证
- **Q3：nullable=true→false 翻转是否默认扫存量 NULL 计数？** 暂定 dry-run 模式下扫并列出数量，apply 模式不扫（遵循 Non-Goal 第 5 条：NOT NULL 强校验属于 OQ9）；业务需要自行填值再 ALTER

### 6.3 版本对照（2026-09-30 absolute）

- P16 Range V2：6 Facade 48/24/3/8/1/7，c12 25/25，192 tests
- P17 Composite V3：compRangeIndexMaps 改名避 NG-4 命中，c13 25/25，203 tests
- **P18 Online DDL（CLOSED 2026-09-29）**：6 Facade 48/24/3/11/1/7（ColumnRegistry=11≤12），c14 25/25，364 tests ≥230，U1 score=4 U2 score=5
- **P19 ALTER COLUMN TYPE（本 spec）目标**：6 Facade 同（±4），**c15 27/27 assertions**（新增 C7 8 关键词 + F2 HC-1/HC-2 两组=27），≥ 382 tests，14 rule + 6 rubric threshold≥4 全达标
