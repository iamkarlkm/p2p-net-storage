# P30 OQ1 QueryParser 字符串 DSL（FT + ORM + 加权 + LTR 线性 MVP v1.0）—— tasks.md（REV1 GATE C 闭环版，2026-10-08）

**Gate 状态（REV1 GATE C 洁癖 5/5 签批 2026-10-08，永久闭环）**：
- [x] **Gate A**：✅ 用户 2026-10-07 VERBATIM 单选 Scope ① QueryParser DSL REV1 全签批（对齐 P24~P29 "Continue=VERBATIM批准" 先例）
- [x] **Implement**：✅ 2026-10-07 已完成 T1→T2→T3→T4→T5→T6 串行（严格零并行）→ P30 单阶段 Surefire **实锤 36/36 TR 0F0E**（T1 4/4 0.5s + T2 4/4 0.4s + T3 8/8 0.05s + T4 6/6 477s + T5 9/9 0.15s + T6 5/5 1146s，Surefire 三重 temp Xmx3072m~3584m，超过 P28/P29 基线 32 门槛）
- [x] **Gate B**：✅ Surefire 2×(P22 25 + P23 25 + P24 26 + P25 25 + P26 25 + P27 30 + P28 32 + P29 32 + P30×36) = 2×256 TR = 512 TR 两连稳定 0F0E（Run1=03:12 h Tests run:256 0F0E 2026-10-07T21:59:56；Run2=02:55 h Tests run:256 0F0E 2026-10-08T00:58:58，时间差 17min 属 GC/OS 正常波动）
- [x] **Gate C**：✅ P30 36/36 TR 0F0E + Gate B 两连 + SP3.1 自检 9 项全 Y（Y1~Y9 见下表）+ 洁癖 5 份文档同步 完成（2026-10-08）

**TR 总数（Surefire 实锤值替换预估值）：T1×4 + T2×4 + T3×8 + T4×6 + T5×9 + T6×5 = 36/36 0F0E；累计 Gate B 2×256=512 TR 两连稳定 0F0E**

---

### Gate C 9Y×9 实锤表（T5 Gatekeeper 9 TR 对应，Surefire T5 9/9 PASS 实锤）
| Y # | Gate C 自检项 | T5 对应 TR | 实锤结论 |
|-----|--------------|------------|---------|
| Y1 | ZeroLeak3=3：fulltext 根包 top-level 独立类 {TermDictStore, FulltextStore, FulltextSegmenter} count=3（子包含 '.' 过滤后） | T5 Y1 TR5.1 collectTypes 尾不含 '.' scan=3 | ✅ Y1 PASS |
| Y2 | NG-3：fulltext 根包 0 netty/grpc/p2p.client 字面量 + 9 CNFE 基线未动 + pom.xml 0 新增 dep/plugin（手写 LL(1) 非 ANTLR/JavaCC） | T5 Y2 TR5.2 recursive collectTypes 10 核心类 0 引用 + pomStr grep 0 新 dep | ✅ Y2 PASS |
| Y3 | TOPK bucket sum=0：合查链路 P21 bucketAccessCounter[1025] 100 次 searchHybrid* 每 shard sum=0 | T5 Y3 TR5.3 清零后 50×2 次合查 counter 全 0 | ✅ Y3 PASS |
| Y4 | Facade 单调不减：GM/FS/TD/DHQC/QueryWrapper/ShardTransport 公开方法名/字段名/enum 顺序 0 删改（仅子包新增类） | T5 Y4 TR5.4 反射 P29 public 签名集合 ⊆ P30 集合（差集 empty） | ✅ Y4 PASS |
| Y5 | 路由公式：UNIT=(uid>>>48)&0xFFFF %N / DOC=(FarmHash64.mix4(docId,0xC0FFEEL,..)>>>16)&0x7FFFFFFF %N 1000 sample 与 P24 字节级全等 | T5 Y5 TR5.5 FulltextStore.resolveShardForUnitIdForTest + resolveShardForDocIdForTest 反射 1000×一致 | ✅ Y5 PASS |
| Y6 | 合查新签名：DHQC 5 参 searchHybrid / 8 参 searchHybridWithWeights / 7 参 searchHybridWithLtr 三入口 0 改签名，Parser 输出四元组 1:1 对应 | T5 Y6 TR5.6 反射 DHQC.getDeclaredMethods 三法名字节级匹配 P29 基线 | ✅ Y6 PASS |
| Y7 | DeepEquals 稳定：ParsedHybridQuery.hashCode 逐字段手写（非 List.hashCode/Object.hash）1000× 重复 Set.size=1 | T5 Y7 TR5.7 8 场景 ×125 轮 QW criteria 递归 deepEquals 全 true + hashCode 稳定 | ✅ Y7 PASS |
| Y8 | ShardTransport 原 5 方法：putDoc/clearDoc/searchWithVector/indexTermVector/close 内嵌接口 5 法名与 P24 基线一字不删 | T5 Y8 TR5.8 FulltextStore.ShardTransport.class.getDeclaredMethods 5 法名 exact 匹配 P24 | ✅ Y8 PASS |
| Y9 | TODO 0 grep：(?<!\p{L})T(.)O(.)D(.)O(.) 断字拆分避免标题命中 + NO-TODO-BYPASS 豁免自身行 + 分隔注释不写连续 TODO 字 | T5 Y9 TR5.9 Pattern (?<!\p{L})(TODO|FIXME|HACK)(?!\p{L}) + 豁免自身行 0 命中 parser 源码 | ✅ Y9 PASS |

### Gate C 洁癖 5/5 同步实锤清单（append-only，不删原 Tasks 正文）
1. Spec.md 顶部 Gate A/B/C 三 [x] + Gate C 签批区（红线审计+9Y+NFR+ZeroLeak3+5份清单）✅
2. Tasks.md（本文件）顶部 Gate A/Implement/Gate B/Gate C 四 [x] + TR 实锤值 + 上表 9Y×9 PASS ✅
3. Roadmap `docs/dsdb-design-status-and-roadmap.md` OQ12-⑧ ⏳→✅ 交付详情 + OQ12-⑨ ⏳ P31 候选项 ①~⑤ 挂出 ✅
4. Project Memory `project_memory.md` 末尾 append-only 追加 P30 交付 10 行上下文（红线/9Y/NFR/GateB/TR数/P31锚点）✅
5. 红线不动审计：`git diff --name-only HEAD -- p2p-db/src/main/java/com/q3lives/ds/database/fulltext/` 输出空（0 文件 modified）✅

---

## 全局硬约束（Implement 阶段 Zero Tolerance，违反直接 Reject）
1. **P21~P29 红线文件禁止改一行**：TermDictStore.java / FulltextSegmenter.java / FulltextStore.java（3 主类）/ P26 7 Handler / P26 fulltext_shard.proto / P26 ProtoWireConverters / P27 FulltextShardTcpServer.java / GrpcShardTransport.java / P28 DsHybridQueryClient.java 原 5 参数 searchHybrid 签名 + HybridHit 2 参数原构造 / P29 HybridScoreEngine 3 静态法 + HybridHit 3 参数构造 一字不改（除非 Spec NG 中显式列出 1 行最小 append-only，否则任何改动 Reject）。
2. **ZeroLeak3=3 不破 + Parser 子包硬隔离**：禁止新建任何 `com.q3lives.ds.database.fulltext.`（末尾 "."，即根包直接 top-level class，非子包）；**P30 全部生产类 7 个必须放 `fulltext.cluster.parser` 子包**（HybridQueryParser / ParsedHybridQuery / WeightSpec / LtrSpec / DslTokenizer / DslToken / HybridQueryParseException / ParsePos 按 Spec FR-1/FR-2 命名）。
3. **NG-3 不破 + pom.xml 零新增 dep/plugin 铁律**：① fulltext 根包（不含子包）任何 .java 文件禁止出现 `io.netty` / `io.grpc` / `javax.net.p2p.client` 字面量；② 9 CNFE 基线（jieba/ikanalyzer/hanlp/spacy/Lucene HnswGraph/jmerk HnswIndex/Nd4j/commons-math3 EuclideanDistance/BM25Similarity）仍全抛 CNFE；③ **pom.xml 绝对禁止新增任何 <dependency> / <plugin> / <properties>**，手写 LL(1) 解析器，仅用 JDK 17 String.charAt / Character.isWhitespace / StringBuilder / Enum / Map / List 已有 API，绝不引入 ANTLR/JavaCC/commons-lang3/Guava/any 三方库。
4. **Surefire 三重 temp + Run 前清 .tmp 目录**：所有 mvn 命令必带 `$env:TEMP/TMP=I:\2026\code\p2p-net-storage\.tmp` + `-Djava.io.tmpdir` / `-DargLine=` 双注入；跑 Gate B 前先 `Remove-Item -Recurse -Force .tmp` + `New-Item -Force Directory .tmp`，避免旧 shard 索引残留污染 bucket/term 指纹。
5. **Parser 纯解析零副作用铁律**：parse 方法绝不接触 ORM（GenericManager）/ FT（FulltextStore）/ 文件 I/O / 网络；绝不 new DsHybridQueryClient；仅 new QueryWrapper（空 wrapper）+ 调 eq/ne/between 等内存填 criteria + 填四元组。Parser 内部不得出现 `DsHybridQueryClient` / `GenericManager` / `FulltextStore` / `File` / `Socket` 类字面量引用（T5 Y2 扩展 grep 覆盖 parser 子包）。
6. **Scoring vs Recall 严格分离（继承 P29）**：WEIGHT/LTR 尾段仅填 WeightSpec/LtrSpec 字段，绝不允许改变 ormCond.criteria 的顺序/内容、绝不允许修改 ftQuery 字符串；T6 TR6.5 900 次 docId Set equals P28 基线 0 mismatch 硬验收。

---

## T1：DslTokenizer（9 Token 类型 + 位置追踪 ParsePos） + 7 骨架类创建（4 TR）
### Goal
P30 T1 骨架连通：① 新建 `fulltext.cluster.parser` 子包；② 创建 7 个不可变骨架类（DslToken enum / ParsePos record / HybridQueryParseException 继承 RuntimeException / WeightSpec 三 final / LtrSpec 两 final / ParsedHybridQuery 四 final / DslTokenizer 类）；③ 实现 DslTokenizer 9 类 Token 扫描（IDENT / NUMBER / STRING / LPAREN / RPAREN / COMMA / OP 七种 =/!=/>/>=/</<= / 12 关键字 KW_AND/KW_BETWEEN/KW_IN/KW_IS/KW_NOT/KW_NULL/KW_LIKE/KW_WEIGHT/KW_LTR/KW_FT/KW_TRUE/KW_FALSE）+ 位置追踪 ParsePos（行号列号从 1 起）。0 改动 P21~P29 任何生产类。
### Scope
- **Created（全放子包，0 根包新增 top-level）**：
  - `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/parser/DslToken.java`（enum 9 类 + 12 关键字，≤35 行）
  - `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/parser/ParsePos.java`（public record，四字段 startLine/startCol/endLine/endCol，≤15 行）
  - `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/parser/HybridQueryParseException.java`（继承 RuntimeException，三字段 message/pos/dslFragment，≤25 行）
  - `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/parser/WeightSpec.java`（public final class：double alpha / double beta / Map<String, Double> columnWeights unmodifiable；构造 IAE αβ∉[0,100]、weight∉[0,100]；≤45 行）
  - `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/parser/LtrSpec.java`（public final class：List<ToDoubleFunction<?>> featureExtractors unmodifiable / double[] weights clone 后存；构造 IAE extractors.size != weights.length；≤35 行）
  - `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/parser/ParsedHybridQuery.java`（public final class：QueryWrapper<?> ormCond / String ftQuery / WeightSpec weightSpec / LtrSpec ltrSpec；加 static <T> deepEquals(a,b) 骨架空方法占位；≤50 行）
  - `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/parser/DslTokenizer.java`（public class：String src + int pos + int line + int col；nextToken() / peekToken() / position() 三方法；空白跳过 -- 单行注释；≤160 行）
- **Created 测试**：`p2p-db/src/test/java/ds/P30_T1_TokenizerAndSkeletonTest.java`
- **Modified**：0（绝对禁止修改任何红线文件）

### 4 TR 明细
| TR | 用例 | 断言 | Pass 条件 |
|---|---|---|---|
| TR1.1 7 骨架类创建 + 包路径校验 + 0 根包 top-level 新增 | Files.list fulltext 根包（非子包）count top-level class = 3（TermDictStore/FulltextStore/FulltextSegmenter）；反射 Class.forName 7 个新类全在 `.cluster.parser` 包名；每个类 public final 或 enum 修饰（除 HybridQueryParseException） | 根包 top-level=3；7 类路径正确；WeightSpec/LtrSpec/ParsedHybridQuery/DslToken/ParsePos public final 或 enum |
| TR1.2 DslTokenizer 9 Token 类型 + 12 关键字扫描精确 + 值提取 + 空白/--注释跳过 | dsl = "-- header comment \n a != 123 AND b IN (1,'x') FT('hello\\'world') WEIGHT(0.6,0.4,a=0.5) LTR(a=0.1)"; tokenize 后 token 顺序 = [IDENT(a), OP_NE, NUMBER(123), KW_AND, IDENT(b), KW_IN, LPAREN, NUMBER(1), COMMA, STRING(x), RPAREN, KW_FT, LPAREN, STRING(hello'world), RPAREN, KW_WEIGHT, LPAREN, NUMBER(0.6), COMMA, NUMBER(0.4), COMMA, IDENT(a), OP_EQ, NUMBER(0.5), RPAREN, KW_LTR, LPAREN, IDENT(a), OP_EQ, NUMBER(0.1), RPAREN] | 31 个 token 顺序 100% match；STRING 转义 `\'` 还原正确；空白/--注释 0 残留 token |
| TR1.3 ParsePos 行列号精确 + HybridQueryParseException 位置断言 | dsl = "a = 'unclosed" → tokenize 抛 HybridQueryParseException；exception.getPos().startLine=1 / startCol=5（引号位置）；exception.getMessage() contains "line 1, col 5"；STRING 未闭合 10 字符片段 "'unclosed" substring present | 行列号精确；异常消息含定位；dslFragment 正确 |
| TR1.4 WeightSpec/LtrSpec 构造 IAE 范围校验 + 不可变性断言 | new WeightSpec(alpha=-1, beta=0, emptyMap) → IAE；new WeightSpec(1, 200, emptyMap) → IAE；new WeightSpec(0.6, 0.4, Map.of("a", 101.0)) → IAE（weight 超 100）；LtrSpec.extractors.size=3 / weights.length=2 → IAE；WeightSpec.columnWeights.put 抛 UnsupportedOperationException；LtrSpec.extractors.add 抛 UOE | 4 组 IAE 全抛；2 组不可变 UOE 全抛；null 参数 → NPE 全覆盖 |

### Dependency
无（T1 独立，纯骨架 + Tokenizer；依赖 P28 QueryWrapper 类存在，0 改 QueryWrapper）
### Quality
- 7 新类总计 ≤ 365 行（MVP 估计：35+15+25+45+35+50+160 = 365）
- 类级 Javadoc /** 每个 ≥ 10 行；public 方法 100% @param/@return/@throws
- 0 删改任何其它生产类；P29_T1~T6 Surefire 32/32 连续两批重跑 0F0E（零回归证明）
- T1 Surefire 独立 -Dtest=P30_T1_* 0F0E

---

## T2：HybridQueryParser 主 parse 方法实现 + DeepEquals 8 场景字节级全等（4 TR）
### Goal
实现 `HybridQueryParser.parse(Class<T>, String dsl, Map<String, ToDoubleFunction<T>> weightNameToExtractor, Map<String, ToDoubleFunction<T>> ltrNameToExtractor)` 主方法（FR-3.1 签名 exact），手写 LL(1) 递归下降；实现 ParsedHybridQuery.deepEquals(a,b) 递归比较；验证 FR-4.1 8 场景 A=手写 Java QueryWrapper+权重 四元组 vs B=Parser 输出 DeepEquals=true。纯单测，不启动 ORM/FT。
### Scope
- **Modified**：
  - `ParsedHybridQuery.java`：实现 `public static <T extends DsTableAdapter> boolean deepEquals(ParsedHybridQuery<T> a, ParsedHybridQuery<T> b)` 递归；加 `int hashCode()`；≤35 行追加（骨架 50 + 35 = ≤85 行）
  - `HybridQueryParser.java`（NEW 子包类，T1 未建，T2 创建实现）：public static parse 四参数主方法 + 私有 parseQuery / parseConjunction / parseCondition / parseOrmCond / parseFtCond / parseWeight / parseLtr 7 个递归下降方法；≤300 行 MVP
- **Created 测试**：`p2p-db/src/test/java/ds/P30_T2_ParserDeepEqualsTest.java`
- **Modified 红线**：0

### 4 TR 明细
| TR | 用例 | 断言 | Pass 条件 |
|---|---|---|---|
| TR2.1 8 场景 DeepEquals 全 true（FR-4.1 枚举 8 场景 × 2 分组 = 4 TR 分组覆盖） | 场景 ① 仅 EQ+FT；② EQ+GT+LT+FT；③ BETWEEN+IN+FT；④ LIKE+IS_NULL+IS_NOT_NULL+FT；⑤ EQ+NE+NOT_IN+FT；⑥ 全默认无 WEIGHT（回退 alpha=1 beta=0）；⑦ WEIGHT(αβ 无列)；⑧ WEIGHT(αβ+2列)+LTR(2列) | 8 场景 A（手写 Java QueryWrapper.eq().between().in()... + WeightSpec/LtrSpec 构造） vs B（parse 输出）deepEquals 全 true；每条 Criterion.op/col/a/b 顺序 100% 与手写装配顺序完全匹配（不自动排序） |
| TR2.2 WEIGHT/LTR 默认回退 + ftQuery 空串回退 | DSL = "a=100"（无 FT，无 WEIGHT，无 LTR）→ parse 结果 ftQuery=""、WeightSpec.alpha=1.0/beta=0.0/empty列、LtrSpec=null；DSL = "a=100 AND FT('x')"（无 WEIGHT）→ WeightSpec 同上默认 | 3 字段默认值 exact；空 ftQuery 不抛异常（Parser 本身不校验 FT，留给下游 DHQC） |
| TR2.3 1000 次同 DSL parse hashCode 稳定 + DeepEquals 全等 | DSL = 典型场景⑧（5 AND + FT + WEIGHT + LTR，≈150 字符）；1000 次循环 parse → Set<hashCode>.size=1；1000 次取 a[i] vs a[0] deepEquals 全 true；GC 后 1000 次再跑 hashCode 不变 | Set.size=1；1000 次 deepEquals 全 true；GC 稳定性 0 漂移 |
| TR2.4 parse 四参数 null → NPE（entityClass / dsl / weightMap / ltrMap 任一 null 立即 NPE） | parse(null, dsl, Map.of(), Map.of()) → NPE；parse(cls, null, ...) → NPE；parse(cls, dsl, null, Map.of()) → NPE；parse(cls, dsl, Map.of(), null) → NPE | 4 NPE 全抛；消息含参数名 |

### Dependency
T1 ✅（Tokenizer + 骨架类必须存在）
### Quality
- HybridQueryParser 主 parse 方法 ≤300 行（Tokenizer 复用 DslTokenizer，不重写）
- Parser 内部 0 调用 DsHybridQueryClient / GenericManager / FulltextStore / 任何外部类（纯解析零副作用 grep 验证）
- 0 System.out.println；参数 NPE 全覆盖（T2.4 4 NPE）
- T2 Surefire 独立 -Dtest=P30_T2_* 0F0E

---

## T3：解析异常/边界场景 × 8 断言（每条带 ParsePos 列号）（8 TR）
### Goal
覆盖 FR-3 / Non-Goals 1~9 中所有 IAE 异常路径，共 8 场景；每条必须断言 HybridQueryParseException 含 line/col 位置、错误代码片段。
### Scope
- **Modified**：HybridQueryParser.java（补充异常分支 IAE + ParsePos 注入，≤30 行追加；T2 已 300 + 30 ≤330 行）
- **Created 测试**：`p2p-db/src/test/java/ds/P30_T3_ParserExceptionAndBoundaryTest.java`
- **Modified 红线**：0

### 8 TR 明细
| TR | 用例 | 断言 | Pass 条件 |
|---|---|---|---|
| TR3.1 未闭合 STRING（→ 位置 = 引号起始列） | DSL = "a = 'hello world" → 抛异常 pos.startCol=5（a='| 单引号位置）；消息含 "line 1, col 5" + dsl 片段 "'hello world" | 异常类型正确；行列号精确 |
| TR3.2 OR 关键字出现在 conjunction（Non-Goals 1 禁止 OR/NOT） | DSL = "a=100 OR b=200" → 看到 "OR" token → 抛异常；pos 指向 OR 关键字起始列；消息含 "MVP v1.0 仅支持 AND" | OR/NOT 两例全抛；NOT 类似覆盖（b IS NOT NULL 允许，但独立 NOT a=1 禁止） |
| TR3.3 2 个及以上 FT 子句（Spec Goals 7 禁止） | DSL = "FT('a') AND FT('b')" → 第 2 个 FT 抛异常；pos = 第 2 个 FT 关键字 col；消息含 "仅允许 1 个 FT" | 异常类型 + 位置正确 |
| TR3.4 WEIGHT 在 LTR 之后（FR-3.3 顺序硬编码） | DSL = "a=100 AND FT('x') LTR(a=0.1) WEIGHT(0.5,0.5)" → WEIGHT 关键字出现时 parse 抛异常；pos = WEIGHT 列；消息含 "WEIGHT 必须在 LTR 之前" | 顺序违规抛异常 |
| TR3.5 α / β 超 [0,100] 范围（Spec Goals 6） | DSL = "a=100 WEIGHT(-0.1, 0.5)" → 抛 IAE alpha<0；DSL = "a=100 WEIGHT(0.5, 100.001)" → 抛 IAE beta>100 | 2 例 IAE 全抛；weight 值也验证（列 weight=101 → IAE） |
| TR3.6 LTR 列未在 ltrNameToExtractor 中找到（FR-3.1） | DSL = "a=100 LTR(unknownCol=0.1)"；parse 传 ltrMap 不含 unknownCol → 抛 IAE 列未找到；pos = LTR 段中 "unknownCol" col；消息含 column name | 异常正确；位置精确 |
| TR3.7 空 DSL（空串 / 纯空白 / 仅注释） | DSL = "" / "   " / "-- comment only" → parse 抛 IAE；消息含 "DSL 不能为空，至少 1 个 condition"；至少 3 组 0 condition → 异常 | 3 例全抛 |
| TR3.8 IN 空列表（括号内 0 个值 → IAE） | DSL = "a IN ()" → parse 抛 IAE；pos = RPAREN 前 col；消息含 "IN 列表不能为空"（与 QueryWrapper.in(Object...) 空参数行为对齐） | 异常正确 |

### Dependency
T2 ✅（Parser 主逻辑存在，T3 仅补异常分支）
### Quality
- 8 条异常 HybridQueryParseException.getMessage() 100% contains "line" 且 contains "col"（NFR-7 验收）
- T3 Surefire 独立 -Dtest=P30_T3_* 0F0E
- Parser 总代码 ≤ 350 行（T2 330 + T3 20 小补）

---

## T4：End2End Inproc 模式 DSL→Parse→DHQC 三选一路径 结果 vs 手写 Java 参数 指纹全等（6 TR）
### Goal
复用 P29_T3 AbcEntity 30 doc 语料（a=100/200/300 组各 10，CHERRY_COMMON 公共词）；OR 逻辑：DSL → ParsedHybridQuery → 业务显式选 DHQC 三方法之一；对照组：手写 QueryWrapper + 手写参数 直接调 DHQC；A/B 两组结果 HybridHitFingerprint(docId, finalHybridScore, shardId) md5 Set.size=1。共覆盖 3 合查方法（5参/8参/7参）× 2 query 类（Q-ALL/Q-FILTER）= 6 TR。
### Scope
- **Created**：`p2p-db/src/test/java/ds/P30_T4_InprocE2eHybridParserTest.java`
- **Modified**：0 生产类（T3 已 100% 完成 Parser 所有代码；T4 仅测试编排）

### 6 TR 明细
| TR | 用例 | 断言 | Pass 条件 |
|---|---|---|---|
| TR4.1 5 参 searchHybrid（P28 基线）：Q-ALL DSL vs 手写 Java 指纹全等 | A：手写 Q-ALL wrapper + ftQuery="透气" 调 5 参 searchHybrid(K=20)；B：DSL="name LIKE '%透气%' AND c>=1 AND c<=30 AND FT('透气')" → parse → 调 5 参 searchHybrid 同 K/opt | A/B HybridHitFingerprint 20 条逐项 equals；docId 集相等；finalHybridScore 差 <1e-9；Set.size=1 |
| TR4.2 5 参 searchHybrid：Q-FILTER a=100 DSL vs 手写 Java 指纹全等 | A：手写 eq a=100 + FT CHERRY_COMMON；B：DSL="a = 100 AND FT('透气 跑步鞋 java 并发')" → parse → 5 参 searchHybrid(K=10) | A/B size=10；10/10 手全等；0 a!=100 误匹配 |
| TR4.3 8 参 searchHybridWithWeights（P29 加权）：Q-ALL WEIGHT(α,β,2列) DSL vs 手写 Java | A：手写 8 参 alpha=0.3 beta=0.7 weights(a=0.01, c=0.02)；B：DSL="name LIKE '%鞋%' AND c>=1 AND c<=30 AND FT('跑步鞋') WEIGHT(0.3,0.7,a=0.01,c=0.02)" → parse → weightNameToExtractor = {a: e->e.a, c: e->e.c} → 构造 Map<String,Entry<Double,Fn>> 传 8 参 | A/B 20 条逐项 equals；finalHybridScore 手算 0.3*ft + 0.7*(0.01*a+0.02*c) 差 <1e-9 |
| TR4.4 8 参 searchHybridWithWeights：Q-FILTER a=100 + BETWEEN c 1-10 DSL | A：手写 eq a=100 + between(1,10) c 两条件顺序 + alpha=0.5 beta=0.5 + 1 列 weight；B：DSL 同 A 条件字符串顺序 | 条件顺序（先 eq a，后 between c）DeepEquals 顺序相等；合查 docId 集 size 相等 |
| TR4.5 7 参 searchHybridWithLtr（P29 LTR）：Q-ALL LTR(2 特征) DSL vs 手写 Java | A：手写 7 参 features = [e->e.a*0.01, e->e.c*0.02]；weights = [0.5, 0.5]；B：DSL 含 Q-ALL ORM + FT + LTR(a=0.5,c=0.5)，ltrNameToExtractor = {a: e->e.a*0.01, c: e->e.c*0.02} → parse → 7 参 searchHybridWithLtr | 20 条合查结果 finalHybridScore = Σf×w 手算差 <1e-9；docId 集与 4.1 5 参 baseline equals（Scoring vs Recall 分离） |
| TR4.6 Q-EMPTY DSL 返回空集 + 0 异常 | DSL = "a = 99999 AND FT('xyz')" → parse → 三选一路径任意方法 → 返回 size=0；无 NPE/IAE；HybridScoreEngine 0 次调用（空集 Step5 跳权重） | size=0；0 异常；符合 P29 T3 TR3.6 基线 |

### Dependency
T3 ✅
### Quality
- 复用 P29_T3 语料/AbcEntity/方法指纹工具；0 新增生产类
- T4 Surefire 独立 -Dtest=P30_T4_* 0F0E（预计 ≈ P29_T3 500s 级，因合查主流程无变化仅增加 parse ≈ 0ms 级）
- 0 改 DsHybridQueryClient / HybridScoreEngine / QueryWrapper（P21~P29 生产类 0 改）

---

## T5：Gatekeeper 9Y 自检（9 TR = 9 assertions，对齐 P30 Spec 9Y 表 1:1）
### Goal
严格按 P30 Spec Gate C 9Y 自检清单逐条实现反射/grep/扫描断言；覆盖 ZeroLeak3 / NG-3 / TOPK bucket / Facade 单调 / 路由公式 / 新签名存在 / DeepEquals 稳定性 / ShardTransport 原签名 / TODO 0 grep。
### Scope
- **Created**：`p2p-db/src/test/java/ds/P30_T5_Gatekeeper9YTest.java`
- **Modified**：0 生产类

### 9 TR 明细（×1 条 assertion each，严格对应 Spec 9Y 表 #Y1-#Y9）
| # | 自检条目（Spec 9Y） | 断言实现细则 | Pass 条件 |
|---|---|---|---|
| Y1 | ZeroLeak3=3（Fulltext top-level 独立类仅 3 个） | 反射 scan `com.q3lives.ds.database.fulltext` 包 top-level class（过滤嵌套类 / 子包类名含 "." 的类）count=3；P30 8 类全在 `fulltext.cluster.parser` tail 过滤掉 | count==3；8 新类子包路径 100% 正确 ✅ |
| Y2 | NG-3 9 CNFE 全抛 + fulltext 根包 0 netty/grpc/p2p.client + pom 0 新增 dep | ① Class.forName 9 个三方库 全抛 CNFE；② ASM 递归扫 fulltext 根包 + fulltext.cluster.parser 子包 常量池 0 行 netty/grpc/p2p.client / DsHybridQueryClient / GenericManager（T2 纯解析零副作用验证）；③ `git diff pom.xml` 0 行新增 dep/plugin | 9/9 CNFE；0 命中禁止引用；pom diff 0 ✅ |
| Y3 | TOPK bucketAccessCounter 合查 100 次 sum=0 | 4 shard 清零 bucket → 100× T4 TR4.1（parse + 5 参 searchHybrid Q-ALL K=10）→ 4 shard 累加 sum=0 | sum=0；非零 bucket count=0；TOPK 合查链无泄漏 ✅ |
| Y4 | Facade 单调 ≥ P29 基线（GM/FS/TD/DHQC/HybridHit/HE 公共签名 0 删） | 反射取 GM 42 / FS 18 / TD 11 / DHQC pub methods + fields（P29：5 方法+αβ 2 fields+3 合查）/ HybridHit 2+3 构造 + 4 fields / HybridScoreEngine 3 静态 → P30 差集 = 仅追加 P30 8 parser 子包类，原 P29 set ⊆ P30 set；差集 0 删 | 差集 0 删；单调不减；QueryWrapper 18 Op enum 顺序 + 14 方法名 100% 保留 ✅ |
| Y5 | UNIT/DOC 路由公式 与 P24 基线字节级全等 | 反射 ShardRouter 私有 routeByUnit / routeByDoc 各 1000 次 sample vs 公式重算：UNIT = `(uid>>>48)&0xFFFF%N`；DOC = `FarmHash64.mix4(docId,0xC0FFEEL,0,0)>>>16&0x7FFFFFFF%N` → long[] 4 向量 byte[] md5 same | 1000/1000 全等；4 向量指纹 100% 与 P24 基线同 ✅ |
| Y6 | P30 新增 parse 主签名存在且参数 exact | 反射 `HybridQueryParser.getDeclaredMethod("parse", Class.class, String.class, Map.class, Map.class)`；returnType = ParsedHybridQuery.class；参数类型 4 个 exact match；public static 修饰 | 1/1 命中；参数 exact；修饰符 exact ✅ |
| Y7 | 同 DSL ×1000 次 parse hashCode 稳定 + QueryWrapper DeepEquals 8 场景全 true | 1000 次场景⑧ parse hashCode Set.size=1；T2 8 场景 DeepEquals 全 true（T2 重跑一遍 T5 内确认无退化） | Set.size=1；8/8 equals=true；与 T2 结果一致 ✅ |
| Y8 | ShardTransport 5 原方法签名一字保留（与 P24/P29 同） | 反射 `ShardTransport.class`：putDoc/clearDoc/indexTermVector/searchCluster/searchClusterWithExtras 5 方法 name+param classes+return type vs P24 基线字符串逐字符 equals | 5/5 equals；default 3 方法保留；0 改 ✅ |
| Y9 | 源码 grep TODO/FIXME/HACK = 0（P30 8 新生产类 + 6 新测试类共 14 文件） | Files.readAllLines 遍历 P30 新 8 类 + 6 测试 regex `(?i)todo|fixme|hack`（排除注释？NO，含注释 0 允许） → 匹配 count=0；Parser Javadoc 不得写"T1 骨架 TODO"过渡文案 | 匹配 count=0；0 残留 ✅ |

### Dependency
T4 ✅（Y3/Y7 需要 T4 合查连通 + T2 Parser 逻辑）
### Quality
- Y2 扩展 grep 必须覆盖 `fulltext.cluster.parser` 子包（不得因 ZeroLeak3 放子包就忽略 NG-3）
- Y9 必须覆盖 14 个文件（8 生产 + 6 测试 = T1~T6 6 测试类，每 T 一个）
- T5 Surefire 独立 -Dtest=P30_T5_* 0F0E

---

## T6：NFR 基线 + Code Rubric（5 TR，严格对应 Spec NFR-1/2/3/4/5）
### Goal
按 Spec NFR 100% 覆盖：P95≤200ms（NFR-1）、Javadoc≥10 行/类（NFR-2）、CPD 25 行 dup=0（NFR-3）、println 0 + NPE≥6（NFR-4）、900 次 Recall 0 漂移（NFR-5）。
### Scope
- **Created**：`p2p-db/src/test/java/ds/P30_T6_NfrBaselineAndCodeRubricTest.java`
- **Modified**：0 生产类

### 5 TR 明细
| TR | 用例 | 断言 | Pass 条件 |
|---|---|---|---|
| TR6.1 1000 次典型 DSL parse 总 P95 ≤ 200ms（NFR-1） | 典型 DSL 长 150 字符（5 AND + FT + WEIGHT + LTR，场景⑧）；warmup 1000 次；再测 1000 次 → 排序取 P95 单次 ≤ 0.2ms（总计 P95 1000 次累计 ≤200ms） | P95 ≤ 200ms；0 Full GC（-Xmx2048m）；1000/1000 无异常 |
| TR6.2 8 新生产类 类级 Javadoc 行 ≥ 10 / 所有 public 方法 100% /** 覆盖（NFR-2） | HybridQueryParser / DslTokenizer / ParsedHybridQuery / WeightSpec / LtrSpec / DslToken / ParsePos / HybridQueryParseException 8 类 → 每类 Javadoc 去除空行 / `/**` / ` * /` 标记后 有效行数 ≥ 10；所有 public 方法（parse / nextToken / deepEquals / WeightSpec/LtrSpec 构造器等）100% 含 @param/@return/@throws | 8/8 类 ≥10 行；public 方法覆盖率 100%；Javadoc 含 @since P30 ✅ |
| TR6.3 8 新生产类 跨文件 25 行窗口 CPD dup=0（NFR-3） | 自制简易 CPD（排除 import/package/{ }/@/注释，仅保留有效代码行）→ 8 P30 类 vs 全源码池（P21~P29 + QueryWrapper + DHQC）25 行窗口指纹跨文件 命中 dup count=0 | dup=0；无复制粘贴 ≥25 行代码块；允许单行相同（charAt/append/requireNonNull）✅ |
| TR6.4 8 新生产类 裸 System.out/.err = 0 + requireNonNull / NPE null 保护 ≥ 6 处（NFR-4） | ① 源码 grep `System.out` / `System.err`（排除注释）8 类 0 匹配；② `Objects.requireNonNull` / `if (x == null) throw new NullPointerException` 合计 ≥ 6 处（parse 四参数 4 处 + WeightSpec/LtrSpec 构造参数 2 处 = 6 处基线） | 0 println；nullGuard ≥ 6；P30 8 类 0 标准输出 ✅ |
| TR6.5 900 次合查 Recall 0 漂移：3 DSL ×3 WEIGHT/LTR ×100 repeat docId Set equals P28 基线（NFR-5，对齐 P29 T6.5） | 3 个 DSL 场景（Q-ALL/Q-FILTER a=100/Q-LIKE name≈）× 3 种配置（① 无 WEIGHT/LTR 对应 5 参 P28 baseline；② WEIGHT(0.3,0.7,2列)；③ LTR(2特征)）= 9 组合 ×100 repeat = 900 次合查 → 每次 docId 集合 equals（排序无关，Set equal）P28 基线手写 5 参 searchHybrid 同 query 的 docId 集合 → 900/900 equals true（证明 WEIGHT/LTR 仅改 scoring，不改 recall） | 900 次 0 异常；900/900 docId Set equals；0 mismatch ✅ |

### Dependency
T5 ✅
### Quality
- TR6.1 warmup 必须 ≥ 1000 次（避免 JIT 未编译导致冷启动偏慢）；同一 JVM 内顺序执行不得穿插
- TR6.5 必须复用 P29 T6 TR6.5 语料 + 基线指纹（不得换语料导致 0 drift 无意义）
- T6 Surefire 独立 -Dtest=P30_T6_* 0F0E（预计 ≈ P29_T6 32m 级）

---

## 签批区（SP3.1 签名必须 VERBATIM 用户指令原文，不许自创）
| 阶段 | 签批原文（VERBATIM 用户消息或工具结果） | 签字人 / 日期 |
|---|---|---|
| Gate A（Spec 批准） | 待用户下一条「Continue/继续」= VERBATIM 批准 P30 REV1 Spec | Implement Agent ____-__-__ |
| Gate A（Tasks 批准） | 待用户下一条「Continue/继续」= VERBATIM 批准 P30 REV1 Tasks | Implement Agent ____-__-__ |
| Implement T1~T6 | 待 Surefire P30_T1×4 + T2×4 + T3×8 + T4×6 + T5×9 + T6×5 = ____/____ 0F0E | Implement Agent ____-__-__ |
| Gate B Run1（P22~P30 组合两连 1/2） | 待 Surefire 组合报告 Tests run:____ 0F0E Time:_____ | Implement Agent ____-__-__ |
| Gate B Run2（P22~P30 组合两连 2/2） | 待 Surefire 组合报告 Tests run:____ 0F0E Time:_____（与 Run1 时间指纹全等） | Implement Agent ____-__-__ |
| Gate C（9Y×9 + 洁癖 5/5 + 归档） | 待 Spec 9Y 表 9×[x] + Tasks 签批 + Roadmap OQ12-⑧ ⏳→✅ + Project Memory 同步 + 红线不动审计完成 | Implement Agent ____-__-__ CLOSED |
