# Ds系列存储系统 — 核心设计规则Spec

> **定位（VERBATIM）**：本文件是 Ds 系列新功能设计与编码的**唯一基准红线**。违反 §1 任何一条红线的 PR 直接打回。
>
> **基线版本**：P20 T3 Close（2026-10-03，23 TR 0F0E）。每次 Phase 关闭后在 §末尾「版本日志」追加记录。

---

## §1 永久红线（Hard Red Lines，VERBATIM 不得修改）

### 1.1 SP3.1 流程铁律
- **分 T 开发**：单 T 代码变更 ≤ 500 行（不含测试）。
- **三步 Gate，缺一不可**：
  1. **Gate A 文档批准**：先写 `spec-t<n>.md` + `tasks-t<n>.md` **VERBATIM 批准 → 才编码**（未批准前禁止任何代码编辑，含本地临时分支）。
  2. **Gate B 本 T 测试**：编码完成 → 本 T **≥4 TR**（Test Record）全 0F0E。
  3. **Gate C 全量回归**：本 T 4+ TR 0F0E + 前序所有已闭 T TR **全量 0F0E** + zeroLeak 双词表 0 命中 → 才闭 T。
- **验收命令 VERBATIM**：
  ```
  mvn -pl p2p-db "-Dtest=ds.P20_<TName>Test,ds.<PrevPhase>Test" test
  # 输出必须：Tests run: <N> Failures: 0 Errors: 0 Skipped: 0 BUILD SUCCESS
  ```

### 1.2 NG-4 ZeroLeak 5 词红线
- **词表（VERBATIM，5 词精准）**：`constraint` / `uniqueKey` / `checkExpr` / `notNull` / `validateConstraint`
- **白名单（仅 7 文件）**：
  - 主源码：`RegistryMetaPaths.java`、`TableMetaStore.java`、`DsEqIndexStore.java`、`DsDatabaseLocal.java`
  - 测试：`P20_ConstraintKeySpaceTest.java`、`P20_DdlSemanticsTest.java`、`P20_DdlCstPipeTest.java`
- **强制缩写（标识符全程使用）**：
  | 完整词 | 缩写 |
  |--------|------|
  | constraint | **cst** |
  | uniqueKey | **uk** |
  | checkExpr | **ck** |
  | notNull | **nn** |
  | validateConstraint | **vc** |
  | strict | **st** |
- **豁免例外**：仅 **常量 leaf literal 值**不缩写（例：`OD_CST_UK = "uniqueKey"`，常量名必须缩写 OD_CST_UK）。

### 1.3 P19 ZeroLeak 8 词红线
- **词表（VERBATIM，8 词精准）**：`manifest` / `schemaVersion` / `tombstones` / `migrateId` / `onlineDdl` / `alterCols` / `castMode` / `oldJavaType`
- **白名单（仅 5 文件）**：`RegistryMetaPaths.java` + legacy4（`DsFullBackup` / `DsIncrementalBackup` / `DsTimelineRestore` / `DsDatabaseMetaMigrator`）
- **合规方式**：非白名单代码需使用这些字符串时，**必须引用 `RegistryMetaPaths.OD_*` 常量**，禁止手写字面量。
- **缩写建议**：castMode → cMode，oldJavaType → oJType，alterCols → altCols。

### 1.4 签名与行为硬线（HC-4/5/6 固化）
- **CstSpec 命名**：POJO 类名必须是 `CstSpec`，`strict` 字段缩写为 `st`；新增字段必须保留旧构造函数签名（backward compat）。
- **UNIQUE 冲突行为**：`DsEqIndexStore.putIndexUnique(value, rowId)` **必须抛 `IllegalArgumentException("uk dup: ...")`**，上层捕获后包装为 `RuntimeException("UK 冲突: ...")`。
  - **UNIQUE kv 算法（VERBATIM）**：`long kv = rowId * 13L + col;`（rowId > 0，col 可负）
  - **共享索引实例（VERBATIM）**：同表多 UNIQUE cst 循环检测时，**必须用表级单实例**（`indexName = tableName + "_uk_all"`），禁止循环内 newInstance。
- **反射方法定位（VERBATIM）**：存在重载的类（TMS Backend / AbstractRegistryBackend）中，**必须按 `名称 + 参数数量` 双过滤，或显式指定参数类型数组**；禁止 `getDeclaredMethods()[0]` 依赖 JVM 顺序。
  - **继承方法查找（VERBATIM）**：查找父类 protected 方法（如 `removeLeaf`）必须做 **superclass fallback 循环**，`getDeclaredMethod` 不检索父类。
- **公开访问修饰符硬线**：所有新增方法/字段/构造**默认 package-private**；测试一律 `setAccessible(true)` 反射访问；**禁止为了测试方便改成 public**。P20 全周期 **0 新增独立 .java 文件**（除测试），新逻辑必须内嵌为 `static nested class`。

---

## §2 架构原则（Architecture Rules，Performance First 核心）

### 2.1 Performance First 三禁令（Karpathy Simplicity First + 零分配）
1. **禁过度保护**：禁止"为了安全"全量 `new ArrayList<>(src)` 防御性拷贝；方法参数已是私有局部作用域的不拷贝。
2. **禁兜底吞异常**：禁止 `catch (Exception e) { log; return default; }` 吞异常；异常统一 unwrap 到最上层处理。
3. **禁无意义装箱**：热路径（循环/每秒调用>100次）禁止把 `int/long` 包装成 `Integer/Long` 导致自动装箱 GC；小对象分配率 NFR：新功能引入后 ≤ 基线的 150%。

### 2.2 幂等语义默认（Idempotency-by-Default）
- **ADD 同 cst 同 type → 静默通过**；异 type → 抛 IAE。
- **DROP 不存在 → 静默通过**。
- **COMMIT 写时间戳 → 先读已存在非空则跳过**（keepCa 语义）：
  ```java
  String existCa = (String) readStr.invoke(backend, caLeaf);
  if (existCa == null || existCa.isBlank()) // 仅第一次才写
      putS.invoke(backend, caLeaf, String.valueOf(now));
  ```

### 2.3 BackwardCompat 兜底三原则
1. **Null 安全读**：Registry leaf 不存在时返回安全默认值（boolean→false，long→-1，String→null），绝不抛 NPE。
2. **默认禁用新功能**：uk=false、ck=null、nn=false → 旧数据零校验开销。
3. **条件性包裹新逻辑**：新代码路径全用 `if (featureFlag)` 包裹；flag=false 时行为 100% 等价于上线前。

### 2.4 Reflect-Only + 内嵌类
- 访问 package-private 成员：**永远反射 + setAccessible(true)**，不升级 public。
- 跨包函数式接口（`YamlSupplier<T>` / `YamlConsumer<T>`）：**用 Proxy.newProxyInstance 构造动态代理**，绝不能把参数类型当成 `j.u.f.Supplier`。
  - **推荐范式**：优先用 Backend 自己的 `putS(path, val)` 2 参封装，绕过父类接口。
- Registry 路径唯一来源：**全部通过 `RegistryMetaPaths` helper/常量生成**，禁止硬编码拼接。
- KeySpace 名字符集：`cstName/migId/leafName` 必须全是 `[A-Za-z0-9_]`，helper ≥5 条 IAE 校验。

### 2.5 索引架构偏好
- **Flat Lookup 为主，目录导航为辅**：ID 映射优先单层 DsHashMap 平查，不做多级目录树。
- **相对路径 → 唯一 ID**：文件实例管理用「相对路径 → long id」映射模式，避免字符串路径到处传。
- **位运算 ID 编码**：组合键（rowId/partition/serverId）必须用位掩码移位编解码，零分配、O(1) 编解码：
  ```java
  // 例：54位 = serverId22 | localAddr24 | partition8
  static long enc(int s22, int a24, byte p8) {
      return (((long)s22 & 0x3FFFFFL) << 32) | (((long)a24 & 0xFFFFFFL) << 8) | (p8 & 0xFFL);
  }
  ```

### 2.6 存储设计偏好
- **WAL 单文件 + 4KB 页对齐**：元数据日志倾向单共享 WAL，帧定长 HEAD + 变长 PAYLOAD 分离存储。
- **页对齐写**：二进制写操作对齐 4KB 边界，减少写放大（参考 DsWAL/DsBinlog 帧格式）。

---

## §3 编码规范（Coding Norms，极简缩写 + 强制范式）

### 3.1 变量/方法命名：极简缩写优先
- **局部变量名 ≤ 8 字符，≤5 最佳**。推荐缩写集：
  ent(entityClass)、tm(TableMeta)、spec(CstSpec)、cst(constraint)、col(column/colId)、
  uk(unique)、ck(check)、nn(notNull)、st(strict)、enb(enabled)、ca(createdAt)、
  mig(migrate)、eq(equality/EqIndex)、kv(key-value)、arr(array)、cmd(command)、
  rec(record)、p(pipe/parser)、alt(alter)、cMode(castMode)、oJType(oldJavaType)、sv(schemaVersion)。

### 3.2 反射调用四件套
1. **显式参数类型定位方法**：
   ```java
   Method upd = backendCls.getDeclaredMethod("updateConstraint", String.class, Object.class, boolean.class);
   // 或：名称+参数数量双过滤（参数类型不确定时）
   for (Method m : cls.getDeclaredMethods())
       if ("updateConstraint".equals(m.getName()) && m.getParameterCount() == 3) { upd = m; break; }
   upd.setAccessible(true); // 永远先 setAccessible
   ```
2. **嵌套类递归查找**：
   ```java
   static Class<?> findSub(Class<?> outer, String name) {
       for (Class<?> c : outer.getDeclaredClasses()) {
           if (name.equals(c.getSimpleName())) return c;
           Class<?> r = findSub(c, name);
           if (r != null) return r;
       } return null;
   }
   ```
3. **InvocationTargetException unwrap**：
   ```java
   static Throwable unwrap(Throwable t) {
       while (t instanceof InvocationTargetException && t.getCause() != null && t.getCause() != t)
           t = t.getCause();
       return t;
   }
   ```
4. **继承方法 fallback 查父类**（查 `removeLeaf` 等）：
   ```java
   Method m = null; Class<?> cur = clz;
   while (cur != null && m == null) {
       try { m = cur.getDeclaredMethod("removeLeaf", String.class, yamlSupCls); }
       catch (NoSuchMethodException e) { cur = cur.getSuperclass(); }
   }
   ```

### 3.3 CstSpec 转换层：显式赋值禁反射拷贝
- 新增字段必须**手动在所有转换层（CmdRec→Spec / Yaml→Spec / Registry→Spec）1 条 1 条写赋值**，禁止 BeanUtils / 通用反射 copyFields。
- 新增字段时**保留旧构造函数**，旧构造体内给新字段赋默认值。

### 3.4 validate 校验顺序
- 先校验 **spec 本身字段**（tm==null 也能抛的那些），再校验 **tm 关联字段**，保证断言消息符合测试预期。

### 3.5 IAE 异常消息三要素
- 每条 `throw new IllegalArgumentException(msg)` 必须包含：**触发条件 + 关键参数值 + 上下文（cstName/migList/阶段）**，30~150 字符最佳。

---

## §4 测试验收（Testing Rules）

### 4.1 单 T 4 TR 强制结构
| TR | 名称 | 内容要求 | 断言数下限 |
|----|------|----------|------------|
| TR-1 | API 存在性 + IAE 边界 | 新增方法反射可达 + ≥5 条独立非法输入各抛 IAE + ≥1 合法返回匹配 | ≥6 |
| TR-2 | 主路径 + 异常分支 | 端到端走通 + 关键异常触发（含 unwrap 后类型/消息校验）+ 中间态断言 | ≥6 |
| TR-3 | 第二条主路径 / 幂等 / BackwardCompat | 幂等重复操作不变 or 旧数据无新元数据时行为完全等价 | ≥5 |
| TR-4 | ZeroLeak 双词表 + 前序回归 | 递归 grep NG-4/P19 → 非白名单 0 hit；前 T Surefire 回归 0F0E | 0 hit |

- **ZeroLeak Pattern 注意**：P19 词表扫描**必须 CASE_SENSITIVE**（去 `CASE_INSENSITIVE` flag），否则误命中 `OnlineDdlCoordinator` 类名。

### 4.2 总闭包要求
- 全量 Surefire 命令必须**逐类列出**，不得通配符；输出 `Tests run: <合计> Failures:0 Errors:0 Skipped:0`（Skipped>0 也不行）。
- **Gatekeeper 测试（推荐）**：用反射类加载器分析「6 门面 public API 字节码签名零变更」、「新 public 方法数 = 0」等架构约束，永久锁死 NG-2 六门面零触碰。
- **NFR 相对性能**：热路径（putEntity / getTable / DDL pipeline）中位数耗时较上一闭 T 基线**退化 ≤ +5%**；>+10% 禁止闭 T。

---

## §5 历史踩坑禁令（Lessons Learned，同错再犯直接打回）

| LL 编号 | 坑位描述 | 根因 | 正确做法 |
|---------|----------|------|----------|
| LL-1 | 反射隐式取第 0 个重载 → 调错方法或 NoSuchMethod | 重载后 JVM 返回顺序不确定 | HC-6：**名称+参数数量双过滤** 或 **显式指定参数类型** |
| LL-2 | commit 无条件覆盖 createdAt → 破坏幂等与追溯 | 未先读旧值判断是否已写 | **先 readStr → 非空才跳过不写**（keepCa 范式） |
| LL-3 | 循环内每次 new DsEqIndexStore → 跨 cst 永远检测不到冲突 | indexMap/rowStore 是**实例字段**，跨实例不可见 | **循环外 new 表级共享单实例**（`tableName_uk_all`），循环内复用 |
| LL-4 | 把 `YamlSupplier<T>` 当成 `j.u.f.Supplier` → NoSuchMethod | 是两个完全不兼容的接口 | `Class.forName(...AbstractRegistryBackend$YamlSupplier)` 加载真实类型；优先用 Backend 的 `putS 2 参` 绕开 |
| LL-5 | `Class.forName("com.q3lives.ds.database.DsEqIndexStore")` 没包 `.index.` → ClassNotFound | 子包名记错 | Grep 真实包名；正确：`com.q3lives.ds.database.index.DsEqIndexStore` |
| LL-6 | toCstSpec 用通用反射拷贝 → 新增 enb 字段丢失 | 反射工具对 final constructor 字段匹配失败 | 转换层**逐字段显式赋值**，不依赖通用反射拷贝 |
| LL-7 | P19 ZeroLeak 加 CASE_INSENSITIVE → 误命中 OnlineDdlCoordinator 类名 | 大小写不敏感匹配标识符 | **P19 扫描必须 SENSITIVE**；类名误判追加白名单类名并注理由 |
| LL-8 | `Arrays.asList(...)` 初始化可变容器字段 → clear()/add() 抛 UOE | 返回定长 List 包装 | 一律 `new ArrayList<>()` / `new CopyOnWriteArrayList<>()` 可变实现 |

---

## §6 新功能设计自检清单（写 spec-t<n>.md 前打勾）

- [ ] 单 T 代码 ≤500 行？Gate A/B/C 三步齐全？
- [ ] NG-4/P19 双词表是否涉及？白名单是否明确列出？
- [ ] 新增方法是否默认 package-private？public 是否有 AR 级理由？
- [ ] 是否新增独立 .java？能否改成 static nested class？
- [ ] 六门面 public API 签名是否 0 修改？Registry 路径是否全用 RMP helper？
- [ ] 反射调用是否显式参数类型 / 父类 fallback？DsEq 是否表级共享单实例？
- [ ] commit/add 是否幂等？时间戳是否 keepCa 条件写？旧数据 null leaf 兜底是否齐全？
- [ ] 4 TR 结构是否齐全？Surefire 命令是否逐类列出？
- [ ] 命名是否 100% 用缩写？标识符 ≤8 字符？
- [ ] 位运算编码是否掩码位数精确？性能退化 NFR-1 阈值可接受？

---

### 版本日志
| 版本 | 日期 | 对应 Phase | 新增规则 |
|------|------|------------|----------|
| v1.0 | 2026-10-03 | P20 T3 Close | 初版：§1 永久红线（SP3.1 + NG4/P19 + 签名硬线）、§2 架构原则 6 条、§3 编码规范 5 条、§4 测试验收 2 条、§5 LL-1~LL-8 踩坑禁令、§6 10 项自检清单 |
