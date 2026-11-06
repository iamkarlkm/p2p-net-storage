# P11 Tasks — OQ1 DML 真实数据（Row / Column payload）统一落 Registry

> 关联 spec：`spec.md`（同目录）；周期：2026-09-21 → 2026-10-15（4 周 MVP，可提前 1 周闭环）；所有任务 Status ∈ {pending, in_progress, blocked, completed, cancelled}

## 任务优先级与依赖图（串行执行，前置未 completed 禁止后移）

```
T1(路径脚手架) → T2(Backend 二进制抽象) → T3(行存接入) → T4(列存接入) → T5(c09 DataGatekeeper) → T6(正确性专项 12 tests) → T7(性能专项 2 tests) → T8(JV 合跑 + 回退验证) → T9(NeatFreak 归档)
```

---

## Task 1：RegistryMetaPaths 扩 DATA 三棵子树 + rowIdHex helper（路径唯一出处，零外泄前置）

- **AC Coverage**：AC-R1（命名统一路径存在）、AC-R2（"/orm/data" 零外泄 grep check）、AC-R9（c09 常量≥11 + public 字段 100% String）
- **Dependencies**：None（冷启动第一项，无前置）
- **Priority**：High
- **Scope**（严格包含，不得越界）：
  1. `RegistryMetaPaths.java` 新增 1 常量 + 4 public static helper + 1 package-private 内部函数：
     - `public static final String DATA_ROOT = ROOT + "/data";`（= `/orm/data`，与现有 ROW_ROOT/COLUMN_ROOT/DBS_ROOT 前缀无包含关系）
     - `public static String dataRowPayload(String entityClassName, long rowId)` → `/orm/data/row/<entitySegment>/<rowIdHex16>`
     - `public static String dataColumnPayload(String entityClassName, String logicalColName, long rowId)` → `/orm/data/column/<entitySegment>/<colSegment>/<rowIdHex16>`
     - `public static String dataGroupPayload(String entityClassName, String groupName, long rowId)` → `/orm/data/group/<entitySegment>/<groupSegment>/<rowIdHex16>`
     - 内部 `static String rowIdHex(long rowId)`：`String.format("%016x", rowId)` 小写，保证数字段名与写入顺序一致（便于 listChildren 按序观察）
  2. 不得新增 public 非 String 字段（c09 Gatekeeper 会钉死 public fields 100% String，违者 FAIL）。
  3. 不得改任何现有常量名/返回值（P10 7 类元路径零字符改硬约束）。
- **Local Test Requirements（TRs，仅 rule 或 rubric）**：
  - **TR-T1-R1 [rule]**：`P11_DataKeySpaceTest#ac_r1_paths_exist` 通过——assert DATA_ROOT == "/orm/data"；调用 dataRowPayload("com.foo.User", 1234L) → 返回字符串 contains "data/row" 且 endsWith "%016x".formatted(1234L)；dataColumnPayload/dataGroupPayload 同理断言段名包含 "column"/"group"。
  - **TR-T1-R2 [rule]**：`grep -rn "\"/orm/data\"" p2p-db/src/main/java` → 仅 RegistryMetaPaths.java 1 处命中（常量声明行），其他文件命中数 = 0。由 c09 Gatekeeper `p11_data_zero_leak_assert` 子断言实锤。
- **Status**：pending
- **Blocked By / Unblock Condition**：（空，无阻塞）
- **Completion Evidence**：（completed 时填入：mvn 单测 PASS 日志 snippet + grep 输出 0 命中截图/文字；c09 grep 子断言 PASS 行号）

---

## Task 2：AbstractRegistryDataBackend 二进制 Backend 抽象（extends P10 AbstractRegistryBackend）

- **AC Coverage**：AC-R3（行 roundtrip 字节一致）、AC-R4（列 roundtrip）、AC-R5（列组 roundtrip）、AC-R6（HYBRID 回填）、AC-R7（双删）、AC-R8（三态各 PASS）——Backend 能力基础
- **Dependencies**：T1 completed（需 DATA 路径常量生成 registryPath）
- **Priority**：High
- **Scope**：
  1. 新建 `com.q3lives.ds.registry.meta.AbstractRegistryDataBackend extends AbstractRegistryBackend`（与 TableMetaStore Backend 同父，复用 MetaMode/DsRegistryInstance/256-entry striped lock 数组）。
  2. 新增 4 个 binary helper（final，子类不可 override 路由逻辑，只能覆写 bucket side YamlSupplier/YamlConsumer）：
     - `final byte[] readLeafBytes(String registryPath, YamlSupplier<byte[]> bucketGetter, byte[] defaultValue) throws IOException`：MetaMode 路由 → Registry 读（REG_BINARY / REG_BINARY_ZIP 统一 asBytes()）→ miss 时 HYBRID 读 bucket → `writeRegistryBytes()` 回填 → 返回原 byte[]；YAML 模式直接 bucketGetter。
     - `final void writeLeafBytes(String registryPath, byte[] value, YamlConsumer<byte[]> bucketConsumer) throws IOException`：双写或单边；HYBRID bucketConsumer 抛 Exception→仅 WARN（含 registryPath/entityClass/rowId/rootDir 4 要素），不抛 IOException（保证 Registry 已写权威可回退）。
     - `final boolean removeLeaf(String registryPath, YamlSupplier<Boolean> bucketRemover) throws IOException`：双删（Registry remove + bucketRemover.get()），任一 true → return true。
     - `final boolean leafExists(String registryPath, YamlSupplier<Boolean> bucketExistsSupplier) throws IOException`：任一后端存在 → true。
  3. 新增 1 个 CAS helper（预留给 OQ3 事务；本阶段只保证单 JVM 原子，分布式由 OQ2/OQ3 扩）：
     - `final byte[] atomicModifyBytes(String registryPath, UnaryOperator<byte[]> op, byte[] seedIfAbsent, YamlSupplier<byte[]> bucketGetter, YamlConsumer<byte[]> bucketSetter) throws IOException`：per-path striped lock（复用父类 `lockFor(path)`，最大 attempt=256）；每次读→apply→write→verify（同 P10 atomicModifyLong 风格），失败重试；HYBRID 模式下 CAS 成功后同步 bucket（失败 WARN）。
  4. 不改动父类 AbstractRegistryBackend 的现有方法签名（String/Long 路径 helper 维持原样——保证 P10 38 tests 基线零干扰）。
- **Local TRs**：
  - **TR-T2-R1 [rule]**：单测 `P11_DataBackendPerfTest#t01_backend_roundtrip`（独立双 tmp 根，无 bucket）：`writeLeafBytes("/tmp/a", payload)` → `readLeafBytes("/tmp/a")` 返回 byte[] 与原 payload 逐字节 `Arrays.equals(...)` true；长度 0 / 8 / 128 / 2048 / 65536 五种长度分别循环 10 次全过。
  - **TR-T2-R2 [rule]**：HYBRID 回填模拟（tmp 根只有 bucket 有值，Registry 空）：`readLeafBytes` 首次返回 bucket 原字节；再次调用 `registry.exists(registryPath)` true → asBytes 与 bucket bytes 一致；验证回填逻辑确实执行（可由回显 WARN 日志数 = 0，因为 bucketConsumer 本次为空操作，不抛异常）。
  - **TR-T2-R3 [rule]**：atomicModifyBytes：seedIfAbsent = {1,2,3}，op = bytes→{4,5,6}，调用 1 次返回 Arrays.equals({4,5,6}) → true；并发 8 线程 × op=increment bytes[0]，跑 1000 次总原子计数等于 8000（无丢改）。
- **Status**：pending
- **Blocked By / Unblock Condition**：（空，T1 done 即进入）
- **Completion Evidence**：（填入 TR-T2-R1~3 各自 PASS 行号 + concurrent atomic 8×1000 = 8000 assert PASS 日志）

---

## Task 3：DsDatabaseLocal 行存 DML 接入 dataBackend（公开签名 0 改硬约束）

- **AC Coverage**：AC-R3（行 put→get bytes 一致）、AC-R6（HYBRID 回填）、AC-R7（remove 双删）、AC-R8（三态各自 roundtrip）、AC-R9（DsDatabaseLocal facade 0 改）
- **Dependencies**：T2 completed（需 AbstractRegistryDataBackend 实例化）
- **Priority**：High
- **Scope**：
  1. DsDatabaseLocal 新增 package-private 字段 `RowDataBackend rowData`（extends AbstractRegistryDataBackend）：构造函数中按 MetaMode 初始化，YAML 模式 rowData = null（完全短路，零 new Registry 实例，与 P10 收官基线行为字节级一致）。
  2. **putEntity(value, assignIdIfMissing)**（L1393-L1441 原 bucket 写逻辑不变）：
     - 新增 if (rowData != null) { `rowData.writeLeafBytes(RegistryMetaPaths.dataRowPayload(entityClassName, rowId), rowBytes, (b) -> {/* bucket 已经在前面写过，此处 no-op；HYBRID bucket 抛异常即无（已经写过） */})` }；MetaMode=YAML 下 rowData == null → 0 额外开销，行为 100% 等价。
  3. **getTable(clazz, id, readTimestamp)**（L1719 原 bucket.get 调用）：
     - if (rowData != null) { bytes = rowData.readLeafBytes(dataRowPayload, () -> bucketGetterCall(), null) } else { bytes = bucket.get(...) }；HYBRID 模式下 bucket 先 miss Registry → 读 bucket → 回填 → 返回，回填写成功后再返回（避免下一次同 key 再 miss）；不得异步回填（会产生竞态半回填脏读）。
  4. **removeTable(clazz, id, withRelations=true)**（L1761-L1821 原 bucket 删除）：if (rowData != null) rowData.removeLeaf(dataRowPayload, () -> bucketDeleteReturnBool) 双删；其他 getTables/queryIds 读路径全量改为走 rowData 入口（保证读回字节一致）。
  5. **公开方法签名 0 改**：所有 public 方法（putEntity/getTable/removeTable/registerTable/close/getBinlogStore/getIdSetForClass/replayBinlog/getCurrentBinlogPosition/rotateBinlog 等 24 个核心）的参数、返回、throws 列表不得改；如新增 helper 必须 protected/package-private（由 c09 Gatekeeper 子断言 `p11_facade_signature_capture_vs_p10_baseline` 实锤，逐字符串 compare 不相等即 FAIL）。
- **Local TRs**：
  - **TR-T3-R1 [rule]**：`P11_RowDataTest#t01_put_get_roundtrip`（HYBRID 模式，独立 tmp 根）：putEntity(User(id=0, name="foo", age=23)) → 返回 rowId=1；getTable(User.class, 1) name.equals("foo")&&age==23；且 `rowData.readLeafBytes` 返回 byte[] 与 bucket.get(...) 返回 byte[] Arrays.equals = true。
  - **TR-T3-R2 [rule]**：`t02_three_mode_roundtrip`（@Parameterized 3 runs：yaml/registry/hybrid）：每 mode 跑 putEntity→getTable deepEquals 全过，0F0E。
  - **TR-T3-R3 [rule]**：`t03_remove_double_delete`：put→get→remove；remove 后 `bucket.get` 返回空/抛 NotFound 且 `registry.exists(dataRowPayload)` false；再次 get 返回 null。
  - **TR-T3-R4 [rule]**：`t04_hybrid_backfill`：先在 YAML 模式 new DsDatabaseLocal putUser（Registry 空，因为 MetaMode.yaml→rowData==null）→ 重启 JVM 切 HYBRID→getTable(User, id) 后，Registry `dataRowPayload` leaf 非空，读回 byte[] 与 bucket 一致；即回填确实发生（可断言 registry.exists）。
- **Status**：pending
- **Blocked By / Unblock Condition**：（空）
- **Completion Evidence**：（T3-R1~4 PASS 行号 + c09 facade 0 改 DsDatabaseLocal 子断言 PASS）

---

## Task 4：ColumnarStore 列存 + 复合组 DML 接入 dataBackend（公开签名 0 改）

- **AC Coverage**：AC-R4（列 put→get 逐字节一致）、AC-R5（列组 compositeGroups roundtrip）、AC-R7（remove 双删）、AC-R8（三态）、AC-R9（ColumnarStore facade 0 改）
- **Dependencies**：T2 completed（Backend）+ T3 completed（同 dbRoot 共享 DsRegistryInstance，避免多实例冲突）
- **Priority**：High
- **Scope**：
  1. ColumnarStore 新增 package-private 字段 `ColumnDataBackend colData` + `GroupDataBackend groupData`；MetaMode.yaml 下均为 null（零短路）。
  2. **putValue(entityClassName, logicalName, rowId, valueBytes)**（L146-L169 原 putColumnValue / putBucketColumn）不变：新增 `if (colData != null) colData.writeLeafBytes(dataColumnPayload(...), valueBytes, no-op)`；复合组 compositeGroups 的 putGroupValue 路径加 `groupData.writeLeafBytes(dataGroupPayload(...), groupBytes, no-op)`。
  3. **getValue(...) / getValue with length**（L198-L240）：if (colData != null) bytes = colData.readLeafBytes(dataColumnPayload, () -> bucketColGetter, null)；否则走原 bucket；复合组 getGroupValue 路径对应 groupData.readLeafBytes 入口。
  4. **removeRow(rowId)**：逐列遍历 `colData.removeLeaf` + `groupData.removeLeaf` 双删；`markDeleted(col)` 对应 colData 写 tombstone leaf `deleted=<epochMs>`（软删只增不回收，与 P10 ColumnRegistry markDeleted 语义一致）。
  5. 公开签名 0 改：putValue/getValue/removeRow/close/getDbRoot/setCompressionThreshold/getCompressionThreshold/load/getTableMetaStore/getBucketStore/putValueByClass/getValueByClass 等 20 个核心公开方法逐字符串对比 P10 收官基线，不相等即 c09 FAIL。
- **Local TRs**：
  - **TR-T4-R1 [rule]**：`P11_ColumnDataTest#c01_put_get_roundtrip`：HYBRID 模式 ColumnarStore.load()；putValue("User", "age", rowId=1, bytes={0x00, 0x18})；getValue("User", "age", 1) → Arrays.equals = true；colData.readLeafBytes 与 bucketColGetter 读回 Arrays.equals = true。
  - **TR-T4-R2 [rule]**：`c02_three_mode_column` @Parameterized yaml/registry/hybrid 三跑 0F0E。
  - **TR-T4-R3 [rule]**：`c03_remove_row`：put 3 columns + 1 composite group → removeRow(1) → 4 个 dataColumnPayload/dataGroupPayload leaf 全 registry.exists=false；bucket get 返回空/NotFound。
  - **TR-T4-R4 [rule]**：`P11_GroupDataTest#g01_group_roundtrip`（compositeGroups 2 items：age+len 打包 groupBytes）→ put groupBytes；读回 group byte[] 解包后 age=23 len=6 两字段逐字节一致；groupData.readLeafBytes 与 bucket group 读回一致。
  - **TR-T4-R5 [rule]**：`g02_hybrid_backfill_group`、`g03_three_mode_group`、`g04_remove_group_double_delete` 4/4。
- **Status**：pending
- **Blocked By / Unblock Condition**：（空）
- **Completion Evidence**：（c01-c04/g01-g04 PASS 行号；c09 ColumnarStore 20 方法 0 改子断言 PASS）

---

## Task 5：c09 NG-3 Data Gatekeeper（延续 P10 c08，静态钉死 facade 0 改 + "/orm/data" 零外泄）

- **AC Coverage**：AC-R2（"/orm/data" 仅 RegistryMetaPaths.java 1 处声明命中）、AC-R9（facade 0 改 + RegistryMetaPaths public 常量≥11 + public 字段 100% String）
- **Dependencies**：T3 completed（行存接入完成后才能 capture DsDatabaseLocal 签名，避免接入时 capture 过早）、T4 completed（ColumnarStore 同）
- **Priority**：High
- **Scope**：
  1. 新建 @Test `P11_ColumnMetaTest#c09_ng3_gatekeeper_data`（沿用 P10 同一测试类，c08 之后追加 c09，保证同 JVM 类加载 baseline 一致性）。
  2. c09 共 **25 assertions**（5 防线）：
     - **防线 1（soft baseline 类加载未触碰）**：同 P10 c08①——DsData/DsString/DsTypeString/DsTypeFixedBucketStore soft initialize=false，类加载前后 method count ≥18（保证 Gatekeeper 本身未触发底层类被提前 load）。
     - **防线 2（DsDatabaseLocal 公开 facade 0 改）**：① 公开方法数 ∈ [26, 34]；② 24 个核心方法（putEntity/getTable/removeTable/...）的 `method.toGenericString()` 与 P10 收官基线 capture 文件 `p10_baseline_DsDatabaseLocal_signatures.txt`（资源文件，c09 测试时从 classpath 读取）逐字符串 compare 相等；①② 合计 2+24 = 26 sub-assertions，但防线 2 算 1 个 assertion（内部 24 子断言失败即防线 2 FAIL）。
     - **防线 3（ColumnarStore 公开 facade 0 改）**：① 公开方法数 ∈ [22, 30]；② 20 个核心方法 toGenericString 与 P10 基线 `p10_baseline_ColumnarStore_signatures.txt` compare 相等；1 assertion。
     - **防线 4（RegistryMetaPaths 零外泄 + Data 常量≥11）**：① public static final String 常量数 ≥ 11（原 7：ROOT/ROW_ROOT/COLUMN_ROOT/DBS_ROOT/META_SPACE 类旧常量 + 新增 DATA_ROOT → 实际≥8，但为了兼容未来扩展，阈值 ≥ 11 预留 3 条；若只写了 8 就通过会给漏洞 → 所以本防线要求"常量数 ≥ 11 且 public fields 100% String 且 grep "/orm/data" 仅 1 处命中"三合一，单 assertion）；② 内部跑 grep：对 `p2p-db/src/main/java` 搜 `\"/orm/data\"` 字符串 → 仅 1 行命中且命中文件叫 RegistryMetaPaths.java（由 c09 测试内部用 Java NIO Files.walk + BufferedReader.readLine 实锤，非系统命令，跨平台无依赖）。
     - **防线 5（capture 非空防假绿）**：4 条 capture 变量（DsDatabaseLocal method list / ColumnarStore method list / RegistryMetaPaths const list / grep result）全部 non-null 且 size() > 0；1 assertion。
  3. 防线 2+3 基线 capture 文件 `p10_baseline_DsDatabaseLocal_signatures.txt / p10_baseline_ColumnarStore_signatures.txt`：作为资源文件写入 `p2p-db/src/test/resources/p11_baselines/`（由 Implement T5 阶段一次性写出，内容为 P10 收官时反射 24/20 方法的 toGenericString，每行一条；如 T3/T4 接入确实违反 0 改，compare 会直接 FAIL，不能靠更新基线蒙混——基线更新必须由用户显式批准，属于 cancelled→新任务）。
- **Local TRs**：
  - **TR-T5-R1 [rule]**：`mvn -pl p2p-db -Dtest=P11_ColumnMetaTest#c09_ng3_gatekeeper_data test` → Tests run: 1 (c09), Failures: 0。
  - **TR-T5-R2 [rule]**：拆开 c09 单独跑 5 条防线的 PASS 数 ≥ 5（100%），内部 sub-assertions（24/20 方法签名 compare、11 常量数、1 处 grep）全部通过，无 sub-FAIL。
  - **TR-T5-R3 [rubric, Scale 0-2]**：**Workflow Fidelity**（c09 基线与 P10 收官 38 JV 关联度）。2=基线 capture 内容 24+20 方法签名与 P10 `mvn -Dtest=P10_*` 合跑 38 tests 时的反射结果（可由 T5 阶段再跑一次 P10 capture 对比——两结果字节级一致，证明基线是 P10 收官的真实锚点，非手工编造）；1=基线 capture 24+20 方法签名与 P10 JV 独立跑结果只有 ≤2 处差异（且差异是注释顺序、泛型擦除等非语义差异，由人工审查可过）；0=基线 capture 与 P10 JV 独立跑有 ≥3 方法签名不相等，说明基线被污染，必须重做 T5，重新捕获。**Pass Threshold ≥ 1**。
- **Status**：pending
- **Blocked By / Unblock Condition**：（空）
- **Completion Evidence**：（T5-R1 PASS 日志；T5-R2 5/5 PASS 行号；T5-R3 打分 + 理由 + 基线对比证据）

---

## Task 6：P11 正确性专项 12 tests（行 4 / 列 4 / 列组 4，覆盖 12 rule AC-R3~R8）

- **AC Coverage**：AC-R3~R8（行 4 AC + 列 2 AC + 列组 1 AC + 回填/双删/三态共 6 AC 合计 12 rule）
- **Dependencies**：T3 completed（行接入）、T4 completed（列+组接入）
- **Priority**：High
- **Scope**：
  1. **P11_RowDataTest.java（4 @Test）**：
     - t01_put_get_roundtrip（覆盖 AC-R3 行 roundtrip 字节一致）
     - t02_three_mode_roundtrip（覆盖 AC-R8 三态）
     - t03_remove_double_delete（覆盖 AC-R7 双删）
     - t04_hybrid_backfill（覆盖 AC-R6 回填）
  2. **P11_ColumnDataTest.java（4 @Test）**：
     - c01_put_get_roundtrip（覆盖 AC-R4 列 roundtrip）
     - c02_three_mode_column（AC-R8 三态列侧）
     - c03_remove_row（AC-R7 双删列侧）
     - c04_hybrid_backfill_column（AC-R6 回填列侧）
  3. **P11_GroupDataTest.java（4 @Test）**：
     - g01_group_roundtrip（覆盖 AC-R5 组 roundtrip）
     - g02_three_mode_group（AC-R8 三态组侧）
     - g03_remove_group_double_delete（AC-R7 双删组侧）
     - g04_hybrid_backfill_group（AC-R6 回填组侧）
  4. 所有 @Test 必须声明 `@Timeout(value = 60, unit = SECONDS)`，避免 Windows CFS 偶发卡死（P7/P10 习惯一致）；失败时打印 `rootDir`、`dataRowPayload`、`bucket space/type/id` 三元组、Arrays.hashCode(bytes) 四要素——便于排查。
- **Local TRs**：
  - **TR-T6-R1 [rule]**：`mvn -pl p2p-db -Dtest=P11_RowDataTest,P11_ColumnDataTest,P11_GroupDataTest test` → Tests run: 12, Failures: 0, Errors: 0, Skipped: 0。
  - **TR-T6-R2 [rule]**：每一项 test 都在 HYBRID 模式下跑（默认 MetaMode）；t04/c04/g04 三个 backfill 用例还要独立跑一个"先 YAML new DB put→重启 JVM→HYBRID→get→assert 回填→Registry exists"的**分 JVM 运行场景**（由 @Fork(jvmArgs = "-Dp2p.meta.mode=yaml" / @Fork(jvmArgs = "-Dp2p.meta.mode=hybrid") 实现；缺 Maven Surefire Fork 插件则由测试内部独立 tmp 根 + 显式 new DsDatabaseLocal 两次模拟，保证 Registry 第一次读时确实空）。
- **Status**：pending
- **Blocked By / Unblock Condition**：（空）
- **Completion Evidence**：（12/12 0F0E 行号；backfill 3 tests 的 "Registry 首次 miss→回填成功后 exists=true" 日志断言）

---

## Task 7：性能专项（DataBackendPerfTest 写入退化 + ColdBoot 冷启动，2 rubric AC）

- **AC Coverage**：AC-U1（写入 P99 ratio≤1.20）、AC-U2（冷启动 ratio≤0.75）
- **Dependencies**：T6 completed（正确性 12/12 PASS 后才跑性能，避免浪费时间测错误代码）
- **Priority**：Medium（性能回归可在后 Review 阶段打低分并修复，不影响正确性基线，但 P11 VERIFIED CLOSED 之前必须 rubric ≥1）
- **Scope**：
  1. **P11_DataBackendPerfTest.java（1 @Test）**：延续 P10 MetaBackendPerfTest 双 tmp 根风格。
     - `pureBucketRoot`：预置 DsFixedBucketStore + 写 5000 行 128B 随机 payload（warmup=2000），记录 P50/P90/P99 写入延迟；`registryRoot`：独立 new DsDatabaseLocal（HYBRID 模式，但 bucketConsumer 空 no-op 只写 Registry 模拟 pure registry；为与 pure bucket 公平比较，bucketConsumer 必须空，不双写），同样 warmup=2000 N=5000 记录 P99。
     - 计算 `ratio = registryRootP99 / pureBucketRootP99`；**同 JVM 跑 5 轮取 ratio 均值**（消除 Windows CFS tick 抖动）。
     - 列 64B put：同逻辑，再跑 5 轮。
     - PASS 断言（软）：`ratio <= 1.25`（rubric 门槛 1.20，但 mvn 单测 FAIL 门槛放宽到 1.25，避免 page-cache 一次抖动导致 tests FAIL；独立 Review 时用 5 轮均值严格打 AC-U1 分）。
  2. **P11_ColdBootPerfTest.java（1 @Test）**：
     - 准备数据：创建 1000 张实体类（用动态生成的 `T0001` ~ `T1000` adapter stub，每张 4 个 int/long 列 = rowLength ≤ 64B），在 YAML baseline 模式下 put 1000 行；在 HYBRID 模式下同样 put 1000 行。
     - 独立 JVM 重启 3 轮：每轮 `new DsDatabaseLocal(root)` → 注册 1000 表 → 加载全部 meta + 首行 getTable，记录总耗时 ms。
     - 计算 `ratio = hybrid3RoundMean / yaml3RoundMean`；AC-U2 rubric 打分；mvn 软断言 `ratio <= 0.85`（放宽便于 page-cache 噪声通过，Review 阶段取严格 0.75）。
  3. 所有 performance tests 用 `@Category(PerfTest.class)` 标记（与 P10 MetaBackendPerfTest 同 category）；默认 `mvn test` 不跑，只有显式 `-P perf` profile 或 `-Dtest=P11_*PerfTest` 时跑——避免日常 CI 耗时过长。
- **Local TRs**：
  - **TR-T7-U1 [rubric]**：AC-U1 小对象写入 P99 ratio。Scale 0-2：2=ratio≤1.05；1=1.05<ratio≤1.20；0=ratio>1.20。Pass ≥1。Evidence：P11_DataBackendPerfTest stdout 的 5 轮 ratio 均值打印行。
  - **TR-T7-U2 [rubric]**：AC-U2 冷启动 ratio。Scale 0-2：2=≤0.60；1=0.60<ratio≤0.75；0=>0.75。Pass ≥1。Evidence：ColdBootPerfTest 的 3 轮总耗时 mean 对比打印行。
  - **TR-T7-R1 [rule]**：显式触发 5 轮后 mvn 不 FAIL（即 ratio 软阈值 ≤1.25 通过；cold boot ≤0.85）；否则视为 blocked，进入 T8 前先返工 T7（但 correctness 12 条 rule 仍维持 PASS，不允许因为性能让 correctness baseline 倒退）。
- **Status**：pending
- **Blocked By / Unblock Condition**：（空）
- **Completion Evidence**：（U1/U2 各自打分 + 理由 + 5 轮/3 轮打印行；TR-T7-R1 PASS）

---

## Task 8：全量 JV 合跑 + 回退等价验证（覆盖 AC-R10/AC-R11/AC-R12 三条关键 rule）

- **AC Coverage**：AC-R10（P10 38 tests 在 P11 代码上合跑 0F0E）、AC-R11（P11 16 tests 16/16 PASS——12 专项正确性 + 4 c09/perf）、AC-R12（YAML 回退模式 38 与 P10 收官字节级等价）
- **Dependencies**：T5 completed（c09 跑通）、T6 completed（12 专项）、T7 completed（2 性能 2 rubric ≥1）
- **Priority**：High
- **Scope**：
  1. **JV 组 1（HYBRID 默认模式）**：mvn `p2p-db` 模块下命令：
     ```
     mvn -pl p2p-db -Dtest=P10_RowMetaTest,P10_ColumnMetaTest,P10_MetaBackendPerfTest,DsRegistryExtAttrs_P7_Exts_Test,Orm_P7_Tgt2_Handler_6_Test,DsRegistry_P7_Tgt3_HandlerTest,DsRegistryExtAttrs_P7_Tgt5_HandlerTest,ColumnarStoreTest,ColumnarStoreCompressionTest,P11_RowDataTest,P11_ColumnDataTest,P11_GroupDataTest,P11_ColumnMetaTest test
     ```
     → 汇总：Tests run = P10 6+8+1=15 + P7 12+6+7+7=32（即 P7 4 类≥28）+ ColumnarStore 8+4=12（原 JV 基线合计 15+32+12=59？实际 P10 38 JV 是 P10 15+P7 4 类门 4 + 列存 12 + 其他 7 = 38 精确。由命令汇总数按实际 classpath 命中的 method 数定，只要 Failures/Errors=0 即可）。
     → 日志保存到项目根 `p11-joint-verification-hybrid-2026XXXX.log`。
  2. **JV 组 2（YAML 回退模式）**：追加 `-Dp2p.meta.mode=yaml` 跑同一命令，日志保存到 `p11-joint-verification-yaml-2026XXXX.log`。
  3. **回退等价检查（AC-R12）**：对两个日志的 `Tests run: X, Failures: 0, Errors: 0, Skipped: 0` 行 + 每个 class 的 `Tests run: N` 分项行执行 diff：
     - diff 结果空 → 字节级等价（即 38 JV baseline 在 P11 代码下跑 HYBRID=0F0E 且 YAML 模式分项数 100% 相同，证明回退无回归）。
     - diff 非空但 Failures/Errors 仍 0，只是 Tests run N 数差 1-2 个 Skipped：通过，但在 Evidence 注明原因（比如某 perf 类 category 过滤）。
- **Local TRs**：
  - **TR-T8-R1 [rule]**：JV 组 1 `Failures: 0, Errors: 0`；Tests run 总数 ≥ 54（P10 38 + P11 16）。
  - **TR-T8-R2 [rule]**：JV 组 2 `Failures: 0, Errors: 0`；38 个 JV baseline 的分项 `Tests run: N` 与 P10 `p10-joint-verification-20260921.log` 分项行 `diff` = 空（或仅 Skipped 数差异）。
  - **TR-T8-R3 [rule]**：两份日志文件落项目根且 size>0（非空）；diff 脚本输出（或人工检查结果）记录到 Completion Evidence。
- **Status**：pending
- **Blocked By / Unblock Condition**：（空）
- **Completion Evidence**：（R1/R2/R3 各自 PASS 行号；两日志落盘路径；diff 空的文字证据）

---

## Task 9：NeatFreak 三层知识同步（AGENTS / 项目记忆 / Roadmap 文档，Workflow Fidelity 合规）

- **AC Coverage**：无直接 spec AC，但满足 Spec Mode Review 阶段的文档一致性要求（NeatFreak 规则 7：新增路径/API/架构 必须同时出现在 AGENTS 对应条目 + docs；Workflow Fidelity rubric 打分的必要条件）
- **Dependencies**：T8 completed（JV 合跑 0F0E 后才归档，避免写了错误结论）
- **Priority**：Low（但 Review 开始前必须 completed = PENDING，否则不能进 Review）
- **Scope**：
  1. **AGENTS.md**：P10 Invariant 段下追加「P11 Invariant 4 条」：
     - 1. `/orm/data` 三棵子树命名统一（RegistryMetaPaths DATA_ROOT + 3 helper，零外泄 grep 1 处声明）；
     - 2. Row/Column/Group 三 Backend 双写双读 HYBRID 回填；
     - 3. c09 DataGatekeeper 25 assertions 5 防线；
     - 4. 性能 ratio ≤ 1.20 P99 写入 / 冷启动 ≤0.75，AC 14 条（12 rule + 2 rubric）VERIFIED CLOSED。
  2. **project_memory.md（Agent 记忆目录）**：Lessons Learned 追加 2-3 条：
     - a. DML 值路径 `/orm/data` 与元路径 `/orm/{row,column,dbs}` 严格前缀不重叠；
     - b. YAML 回退模式下 dataBackend 全部 null（零 new Registry），保证字节级等价基线；
     - c. 回填写必须同步不异步（异步会产生半回填脏读，下一次读同一 key 再 miss 一次代价 < 脏读风险）。
  3. **docs/dsdb-design-status-and-roadmap.md**：
     - §六 推进路线图 P2 行追加「✅ P11 DML 真实数据 Registry 统一」（含 P11 14 AC、c09、写入 ratio 实测值如 1.02、冷启动 ratio 实测值如 0.63 等）；
     - §七 代码索引追加 2 条入口：`AbstractRegistryDataBackend` / `RowDataBackend+ColumnDataBackend`。
- **Local TRs**：
  - **TR-T9-R1 [rule]**：3 文档变更后 grep 相对时间"今天/最近/today/recently"在 3 个目标文件结果空（0 命中，已用绝对日期 2026-09-21~10-15）。
  - **TR-T9-R2 [rule]**：AGENTS.md 的 P11 4 Invariant 与 project_memory.md 的 3 Lessons 与 roadmap §六 §七 的描述**无矛盾**（比如写入 ratio 两处的数字必须一致，不能 AGENTS 写 1.02 roadmap 写 0.99）。
  - **TR-T9-R3 [rubric, Scale 0-2]**：Workflow Fidelity 归档完整性。2=3 文档全部改了且各自内容交叉一致；1=少 1 处但核心 AC 覆盖率描述仍齐全；0=少 2+ 处或存在数字矛盾。Pass Threshold ≥ 1。
- **Status**：pending
- **Blocked By / Unblock Condition**：（空）
- **Completion Evidence**：（T9-R1/R2 PASS；T9-R3 打分+理由；三文档变更的 diff --stat 行号）

---

## 验收完成标准（Spec Mode Implement Queue Drained）

```
all T1..T9 status ∈ {completed}
AND 无任何 pending/in_progress/blocked
AND 每 completed task 的 Completion Evidence 非空
AND 所有 rule TR 全 PASS
AND 所有 rubric TR 打分 ≥ 各自 Pass Threshold
AND JV T8-R1/R2/R3 全成立
→ 方可进入独立 Review Phase（由 fresh agent 单独 review.md）
```

> **取消任务的唯一合法方式**：用户在本会话中显式说"Tn 取消"并给出理由；任何 implementer 自行标记 cancelled 视为 Workflow Fidelity = 0（AC 覆盖破洞）。
