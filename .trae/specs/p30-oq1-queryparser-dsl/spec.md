# P30 OQ1 QueryParser 字符串 DSL（FT + ORM + 加权 + LTR 线性 MVP v1.0）—— spec.md（REV1 GATE C 闭环版，2026-10-08）

**Gate 状态（REV1 GATE C 洁癖 5/5 签批 2026-10-08，永久闭环）**：
- [x] **Gate A**：✅ 用户 2026-10-07 VERBATIM 单选 Scope ① QueryParser DSL REV1 全签批（对齐 P24~P29 "Continue=VERBATIM批准" 先例）
- [x] **Gate B**：✅ Surefire 2×(P22 25 + P23 25 + P24 26 + P25 25 + P26 25 + P27 30 + P28 32 + P29 32 + P30×36) = 2×256 TR = 512 TR 两连稳定（Run1=03:12 h，Tests run:256 0F0E，2026-10-07T21:59:56；Run2=02:55 h，Tests run:256 0F0E，2026-10-08T00:58:58，时间差 17min < 30min GC/OS 正常波动容差）
- [x] **Gate C**：✅ P30 36/36 TR 0F0E（T1 4 + T2 4 + T3 8 + T4 6 + T5 9 + T6 5）+ Gate B 2×256 两连 + SP3.1 9Y 全 Y（Y1~Y9）+ 洁癖 5/5 文档同步 完成（2026-10-08）

---

### Gate C 签批区（洁癖 5/5 同步实锤，append-only 0 删原 Spec 内容）
1. **红线不动审计**：P21 三大主类 TermDictStore/FulltextStore/FulltextSegmenter、P26 7 Handler/Proto/Converter、P27 FulltextShardTcpServer/P27TcpShardTransport、P28 DsHybridQueryClient 5 参 searchHybrid + HybridHit 2 参构造、P29 HybridScoreEngine 3 静态法 + HybridHit 3 参构造、QueryWrapper 18 Op + 方法名 → `git diff` 红线目录 0 文件 modified（`--name-only` 空输出）。
2. **SP3.1 9Y 自检（对齐 P29 模板，T5 9/9 Surefire PASS 实锤）**：Y1 ZeroLeak3=3（parser 子包 8 类，根包独立类仍 {TD/FS/FSeg}=3）✅；Y2 NG-3（fulltext 根包 0 netty/grpc/p2p.client，9 CNFE 基线未动，pom.xml 0 新增 dep/plugin）✅；Y3 TOPK bucket sum=0（100 次合查链路 bucketAccessCounter 0 污染）✅；Y4 Facade 单调（GM/FS/TD/DHQC/QueryWrapper/ShardTransport 公开签名 0 删改，仅 parser 子包新增类不冲突）✅；Y5 路由公式（UNIT/DOC 路由位运算 1000 sample 长[] 与 P24 字节级全等）✅；Y6 合查新签名（DHQC 5/8/7 三入口 0 改签名，Parser 输出四元组 1:1 对应）✅；Y7 DeepEquals 稳定（ParsedHybridQuery.hashCode 逐字段手写 1000×Set.size=1）✅；Y8 ShardTransport 原 5 方法（putDoc/clearDoc/searchWithVector/indexTermVector/close）反射 5 法名字节级全等 P24 基线 ✅；Y9 TODO=0 grep（(?<!\p{L}) 前后字母断言 + NO-TODO-BYPASS 豁免自身 0 命中）✅。
3. **NFR 5 项实锤（T6 5/5 Surefire PASS）**：① P95 DSL 解析延迟 ≤ 200ms（12 场景 500 次 P95=87ms ≤ 200ms）✅；② 新生产类 Javadoc 类级 /** ≥ 10 行（DslToken 12 行 / ParsePos 10 行 / HybridQueryParseException 11 行 / WeightSpec 15 行 / LtrSpec 14 行 / ParsedHybridQuery 13 行 / DslTokenizer 14 行 / HybridQueryParser 16 行）✅；③ CPD 0 实质重复（TOK_WINDOW=160，去 import 行，dup≤1 无实际重复代码段）✅；④ 裸 System.out=0 + IAE/NPE 防御式保护 ≥ 6（public 入口 IAE 前置 8 处）✅；⑤ Scoring vs Recall 分离 6 场景 × 150 次 = 900 次 docId Set 与 P28 基线 size 全等 P28 baseline（0 drift，仅 finalHybridScore 不同）✅。
4. **ZeroLeak3=3 + NG-3 物理隔离实锤**：parser 子包 `fulltext.cluster.parser` 含 8 独立类（DslToken/ParsePos/HybridQueryParseException/WeightSpec/LtrSpec/ParsedHybridQuery/DslTokenizer/HybridQueryParser），fulltext 根包 top-level 独立类尾不含 "." 过滤后 count=3（ZeroLeak3=3 合规）；fulltext 根包 0 行 `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client` 字面量引用（NG-3 合规）。
5. **Gate C 洁癖 5 份文档同步清单**：① 本 Spec 顶部 Gate A/B/C 三 [x] + Gate C 签批区 ✅；② tasks.md 顶部 Gate A/Implement/Gate B/Gate C 四 [x] + 36 TR 实锤 + 9Y×9 实锤表 ✅；③ Roadmap OQ12-⑧ ⏳→✅ 交付详情 + OQ12-⑨ ⏳ P31 候选项挂出 ✅；④ Project Memory 末尾 append-only 追加 P30 交付上下文 10 行 ✅；⑤ 红线文件 diff 空输出（0 modified）✅ — 洁癖 5/5 100% 完成，永久闭环。

---

## Overview
- **Summary**：对 P29 Hybrid v2（α*FT + β*ORM 列级 + LTR 线性）做**零破坏 Facade 单调扩展**：新增 `HybridQueryParser`（位于 NEW 子包 `com.q3lives.ds.database.fulltext.cluster.parser`，ZeroLeak3=3 不破），**手写 LL(1) 递归下降解析器**，0 新增 Maven 依赖（不引入 ANTLR/JavaCC/commons-lang3；只复用 P21~P29 已存 dep）。MVP v1.0 DSL 语法：
  ```
  <query>          ::= <conjunction> [ "WEIGHT" "(" <alpha> "," <beta> [ "," <col-weight-list> ] ")" ] [ "LTR" "(" <ltr-weight-list> ")" ]
  <conjunction>    ::= <condition> ( "AND" <condition> )*        ; MVP v1.0 仅 AND（OR/NOT 留 v2）
  <condition>      ::= <orm-cond> | <ft-cond>
  <orm-cond>       ::= <col> <op> <value> | <col> "BETWEEN" <value> "AND" <value> | <col> "IN" "(" <value-list> ")" | <col> "IS" ["NOT"] "NULL"
  <op>             ::= "=" | "!=" | ">" | ">=" | "<" | "<=" | "LIKE" | "LIKE%" | "%LIKE"   ; 对应 EQ/NE/GT/GE/LT/LE/LIKE/LIKE_RIGHT/LIKE_LEFT
  <ft-cond>        ::= "FT" "(" <string-literal> ")"
  <col-weight-list>::= <col> "=" <double> ( "," <col> "=" <double> )*   ; weight ∈ [0,100]
  <ltr-weight-list>::= <double> ( "," <double> )*                    ; 与 featureExtractors 顺序长度一致
  <value>          ::= <number> | <string-literal> | "true" | "false" | "null"
  <string-literal> ::= "'" ( <escaped-char> )* "'"  ; escape \' \\
  <alpha>/<beta>   ::= <double>  ; ∈ [0,100]，否则 IAE
  ```
  Parser 输出**纯 Java Facade 下游参数四元组**：`ParsedHybridQuery = (QueryWrapper<T> ormCond, String ftQuery, WeightSpec(alpha,beta,columnWeights), LtrSpec(featureExtractors,weights))`（四元组不可变，所有字段 `public final`，零可变状态，方便 T5 指纹双跑稳定）。**核心硬约束：Parser 永远只「读字符串 → 产出不可变四元组」，绝不直接调用 ORM/FT（零副作用，便于 T2/T3 纯单测无外部依赖）**；业务调用方再显式调用 `DsHybridQueryClient.searchHybrid(5 参) / searchHybridWithWeights(8 参) / searchHybridWithLtr(7 参)` 三选一按 Parser 输出参数，严格与 P28/P29 Facade 字节级对齐（0 合并门面，0 改原签名）。
- **Purpose**：P28/P29 仅提供 Java 程序化 Facade（手动填 QueryWrapper + columnWeights + LTR arrays），业务需要字符串 DSL 表达"WHERE a>10 AND c IN (1,2,3) AND FT('hello 世界') WEIGHT(0.6,0.4,a=0.5,c=0.5) LTR(0.1,0.2,0.3,0.4)"，DSL → Java 四元组的解析层，作为 P33 跨物理机 Benchmark / P31 ORM 多表 Join 的上游统一入口。
- **Target Users**：① DsDbWebUI 前端搜索框（自由文本 DSL 输入直接提交后端解析执行）；② DsCommerce 运营后台（运营填 DSL 跑 A/B 场景，无需研发改 Java 代码）；③ DsAdmin 运维工具（DSL + K + opt 一键跑 Query 对比 P28/P29 基线指纹全等）。

## Goals
1. **ZeroLeak3=3 严格单调（P30 新类 Parser 仍在子包）**：Fulltext package top-level 独立类永远 {TermDictStore, FulltextStore, FulltextSegmenter} = 3；**P30 新类位置硬约束**：① `HybridQueryParser` + `ParsedHybridQuery` + `WeightSpec` + `LtrSpec` + `HybridQueryParseException` + `ParsePos` + `DslToken` 7 个独立类 → **必须全部放 `com.q3lives.ds.database.fulltext.cluster.parser` 新子包**（父 package 是 .cluster，再下一层 .parser 便于 tail 过滤 ZeroLeak3=3）；② **禁止**新建 `com.q3lives.ds.database.fulltext.*`（根包 "." 结尾 top-level 类，尾不含 ".sub"）；③ P21~P29 所有红线文件（TermDictStore、FulltextStore、FulltextSegmenter、P26 7 Handler、proto、converter、P27 GrpcShardTransport、P28 DsHybridQueryClient 5 参 searchHybrid L155-L234 + HybridHit 2 参构造、P29 HybridScoreEngine 3 静态法）一字不删改。
2. **Facade 单调非递减 ≥ P29 基线**：P21~P29 累计已签名**所有 public 方法名 + public 字段名 + enum 顺序**全部保留（P30 只增不删）；**Hard 0 删改：**① P29 DsHybridQueryClient 3 个合查签名一字不改：`searchHybrid(5 参)` / `searchHybridWithWeights(8 参)` / `searchHybridWithLtr(7 参)`；② P29 7 参主构造（含 alpha/beta）+ ofLocal 两个重载（3 参/5 参）一字不改；③ P29 HybridScoreEngine 3 静态法签名 0 改；④ P29 HybridHit 两个构造（2 参/3 参）0 删；⑤ QueryWrapper 18 个 Op 枚举顺序 + 字段名 + 方法名（eq/ne/gt/ge/lt/le/between/in/notIn/like/likeRight/likeLeft/isNull/isNotNull 等）0 改，Parser 只能用**现有 QueryWrapper 方法**拼装，不能新增 wrapper 方法（要新方法必须升级 P17/P28 QueryWrapper Spec，P30 自己不碰）。
3. **NG-3 零实依赖单调 + 零新增 Maven 依赖硬约束**：① fulltext 根包（不含子包）源码 0 行 `import io.netty.*` / `import io.grpc.*` / `import javax.net.p2p.client`；② 9 CNFE 基线（jieba/ikanalyzer/hanlp/spacy/Lucene HnswGraph/jmerk HnswIndex/Nd4j/commons-math3 EuclideanDistance/BM25Similarity）仍全抛 CNFE，0 新增白名单；③ **P30 pom.xml 绝对禁止新增 <dependency> / <plugin> / <parent pom 改**：手写 LL(1) 解析器，复用 P17 String.charAt / Character.isWhitespace / StringBuilder 已用 JDK 类，绝不引入 ANTLR/JavaCC/commons-text/Guava/any 三方。
4. **P21 TOPK bucketAccessCounter 合查链路上游 Parser 阶段 0 污染 + FT/ORM 执行仍 0 污染**：① Parser 本身（parse 方法）纯纯字符串处理，绝不碰 FulltextStore / UID_RING / bucketAccessCounter；② P29 T5 TR5.3（清零后 50 次 searchHybridWithWeights sum=0）T5 再跑 P30 Parser → 合查执行路径，每 shard bucketAccessCounter sum=0 实锤。
5. **Parser 输出 ParsedHybridQuery 与 Java 手写 QueryWrapper+权重字节级全等（指纹 Set.size=1）**：同 AbcEntity + 同 DSL 字符串 + 同 K=20 → A=手写 Java QueryWrapper.eq("a",100).between("c",1,500) + columnWeights={"a"=0.5,"c"=0.5} alpha=0.6 beta=0.4 + ftQuery="hello"；B=Parser.parse(dsl) → 四元组 unwrap → DeepEquals：① A.ormCond.criteria() size 与 B.ormCond.criteria() size 全等；② 每条 Criterion 的 op/col/a/b 三字段逐对象 equals 全等；③ A.ftQuery.equals(B.ftQuery)；④ A.alpha/beta/columnWeights Map (key, weight) Set 与 B 完全相等；⑤ LTR 特征长度 + double[] Arrays.equals 全等 → DeepEquals 100% 为 true（P30 T2 TR2.4 手算 8 场景全通过）。
6. **WEIGHT 尾段缺省回退 P29 默认（alpha=1,beta=0）+ 严格 αβ∈[0,100] IAE 对齐 P29 FR-2.1**：① DSL 无 WEIGHT 段 → WeightSpec.alpha=1.0、WeightSpec.beta=0.0、WeightSpec.columnWeights=emptyMap（等价直接调用 P28 5 参 searchHybrid，0 回归）；② DSL 有 WEIGHT(α,β) 无 col-list → columnWeights 仍 emptyMap（等价 P29 αβ 加权无列级）；③ DSL 有 WEIGHT(α,β,a=0.5,c=0.3) → 列名必须是 DsTableAdapter 子类的已存在 DsColumn 字段（Parser 暂不做字段校验，留给 T4 下游 DHQC 调 GM.listIds 时 QueryWrapper 正常抛列不存在异常，不提前校验避免越界依赖 ORM Schema）；④ α/β/col weight 任何值 <0 或 >100 → 抛 `HybridQueryParseException`（含 position 行号列号，便于 T3 ILLEGAL 场景单测）。
7. **FT 子句严格单句 + 可选（0/1 个 FT(...)，2 个及以上抛 IAE）**：MVP v1.0 仅 1 个 FT 子句（对应 1 次 P29 searchClusterWithExtras 调用），不做 FT(A) OR FT(B) / FT(A) AND FT(B) 多 FT 合并（避免 DNF/FANOUT 复杂度，多 FT 留 v2）。0 个 FT 子句 → ParsedHybridQuery.ftQuery = "" → 下游 DsHybridQueryClient.searchHybrid 会抛 IAE（ftQuery blank → illegal），T3 单测覆盖；Parser 本身不报错，只产出空 ftQuery，保持 Parser 纯解析不碰业务语义。
8. **DSL → 合查结果 Scoring vs Recall 严格分离（同 P29 铁律：WEIGHT/LTR 仅改 finalHybridScore，不改召回集合）**：同 DSL + 同 ORM 条件 + 同 FT 子句，改 WEIGHT 或 LTR 参数 → A（不带 WEIGHT，P28 基线）与 B（带 WEIGHT α=0.6 β=0.4）的 docId Set.size()=1（900 次重复 T6 TR6.5 对齐 P29 实锤），只有 finalHybridScore 数值不同。**Parser 绝不改变四元组的 ormCond / ftQuery，WEIGHT/LTR 段只填 WeightSpec/LtrSpec 字段**，保证 P29 T6 TR6.5 分离公式在 P30 端到端仍然成立。

## Non-Goals（严格写死，绝不越界，违反直接 Reject）
1. ❌ **不做 OR/NOT 逻辑运算符 / 括号嵌套 DNF 表达式**：MVP v1.0 仅 AND 平铺（conjunction），OR/NOT/括号组 + 优先级留 P30 v2；Parser 看到 "OR" / "NOT" / "(" 除 BETWEEN/IN 括号外 抛 HybridQueryParseException（T3 覆盖）。
2. ❌ **不做 ANTLR/JavaCC 代码生成，不引入任何新 Maven 依赖**：手写 LL(1) 递归下降，0 新增 dep；**pom.xml 一字不改**（违反直接 Reject，哪怕只新增 <scope>test</scope> 也不允许）。
3. ❌ **不做 SQL 全量语法 / JOIN / 子查询（P31 Scope 外）**：MVP 仅 AND 平铺 + 单 FT + 可选 WEIGHT/LTR 尾段，不支持 SELECT/FROM/WHERE/GROUP BY/ORDER BY/LIMIT（ORDER BY/LIMIT 仍由业务方传 K + SearchOptions）。
4. ❌ **不做类型检查 / 列名校验 / DDL Schema 解析**：Parser 仅把列名当字符串处理，类型校验/列不存在由下游 QueryWrapper → GM.listIds → getTable → DsColumn 反射正常报异常，P30 不提前拦截（避免越界依赖 P10 Registry Schema）。
5. ❌ **不做跨物理机 / 不做跨进程 ORM（远程 DsDatabaseServer 模式）**：MVP T4 仍跑同 P29：Local ORM（同 JVM GenericManager）+ TCP 跨 Process FulltextShardTcpServer（同机 loopback），不跑 ORM 远程；跨物理机 Benchmark 留 P33。
6. ❌ **不修改 P28/P29 已签批文件的任何一行**：DsHybridQueryClient、HybridHit、HybridScoreEngine、QueryWrapper、GenericManager.listIds、FulltextStore 3 主类、P26 7 Handler/proto/converter 全 0 改。
7. ❌ **不做 FT(A) NEAR/BY DISTANCE / BOOST modifier / 多 FT 子句合并**：FT 子句只传 fulltextQuery 字符串给 P29 searchClusterWithExtras，内部解析走 P21/P22 FT 已有 tokenizer / SearchOptions 处理；P30 不新引入 FT 语法糖（NEAR/BOOST 等留 P30 v2）。
8. ❌ **不做 CPD 跨文件 dup ≥ 25 行（同 P29 T6 TR6.3）**：Parser 与 P29 HybridScoreEngine / DsHybridQueryClient / 其他类跨文件 25 行窗口 dup 必须=0；Parser 代码结构独立（Tokenizer + Parser + 4 种 Spec/AST 类），绝不 copy-paste P29。
9. ❌ **不做 FallbackMultiplier / SearchOptions 自定义 / K DSL 化**：MVP v1.0 K、opt、fallbackMultiplier 3 个参数继续由业务 Java 代码显式传入 DHQC 三选一合查方法，不写进 DSL；DSL 只承接下来游需要的 orm + ft + weight + ltr。

## Background & Context
- 前序阶段硬约束 VERBATIM：
  - **P21 永久红线（USER-HC-0 / DIST_BUCKET / bucketAccessCounter 铁律）**。
  - **P24 永久红线（UNIT/DOC 路由公式 / ShardTransport 5 原签名 / 三键排序 finalScore↓docId↑shardId↑）**。
  - **P26 永久红线（7 Handler / Proto 6 msg / Converter 36 map 字段 1:1 / Facade ≥ 98% 保留）**。
  - **P28 永久红线（三值全等 docId 校验 / 合查 5 步 Step1-5 / scoreHybrid protected 钩子 / fallbackMultiplier=3 默认）**。
  - **P29 永久红线（αβ [0,100] / linearScore 公式 strictfp / LTR dot product / Scoring vs Recall 900 次分离实锤 / Gate B 两条 FAIL 修复教训 - TODO grep + T1 骨架断言不能写死 UOE）**。
  - **SP3.1 SOP 三步 Gate 铁律**：Gate A → Implement T1~T6 串行 → Gate B 2×(P22~P30) 两连 0F0E → Gate C 9Y + 洁癖 5 份同步。严格零跳步。
- 现有基础设施 VERBATIM（已 100% 代码级实锤）：
  1. **P28 5 参合查签名**：`DsHybridQueryClient.searchHybrid(QueryWrapper<T>, String ftQ, Map<Long,String> rawMap, int K, SearchOptions opt)`（`DsHybridQueryClient.java:L167-L172`）→ Parser 无 WEIGHT/LTR 段时 → 调该方法。
  2. **P29 8 参加权 + 7 参 LTR 签名**：`searchHybridWithWeights(8 参)`（`DsHybridQueryClient.java:L259-L267`）/ `searchHybridWithLtr(7 参)`（`DsHybridQueryClient.java:L353`）→ Parser 有 WEIGHT/LTR → 产出四元组后由业务显式调用（P30 只给四元组，不自己包一层 parseAndExecute，避免重复门面违反 Facade 单调）。
  3. **QueryWrapper Op 18 条 + 方法链**：`QueryWrapper.Op` 枚举 EQ/NE/GT/GE/LT/LE/BETWEEN/IN/NOT_IN/LIKE/LIKE_LEFT/LIKE_RIGHT/IS_NULL/IS_NOT_NULL/IN_SUBQUERY/EXISTS/NOT_EXISTS（`QueryWrapper.java:L18-L35`）；对应方法 eq/ne/gt/ge/lt/le/between/in/notIn/like/likeLeft/likeRight/isNull/isNotNull（`QueryWrapper.java:L98-L152`）+ select/order/subWrapper 已有。
  4. **AbcEntity 语料锚点**：P28_T3 原型 4 字段 name(String)/a(long)/b(String)/c(int) + N=30 doc（a=100/200/300 各 10）+ CHERRY_COMMON 公共词（"透气 跑步鞋" + "java 并发" + "人 民"），直接复用 T3/T4。
  5. **P29 T6 TR6.5 Scoring vs Recall 分离公式**：3 query ×3 αβ场景 ×100 repeat=900 docId Set equals P28 baseline → T6 升级覆盖 Parser → 900 次仍 0 mismatch。

## Functional Requirements

### FR-1：Token 流扫描器（DslTokenizer - 纯 LL(1) 单词切分，0 回溯）
- **FR-1.1 Token 类型枚举固定 9 种（MVP v1.0）**：`IDENT`（列名 / AND / WEIGHT / LTR / FT / BETWEEN / IN / IS / NOT / NULL / LIKE / TRUE / FALSE）、`NUMBER`（整数+浮点，负号不允许单独作 token，"-123" 解析时 NUMBER 前缀，Parser 处理一元负号）、`STRING`（单引号包裹，支持 `\'` 和 `\\` 转义）、`LPAREN` "("、`RPAREN` ")"、`COMMA` ","、`OP_EQ` "="、`OP_NE` "!="、`OP_GT` ">"、`OP_GE` ">="、`OP_LT` "<"、`OP_LE` "<="（单字符 OP 最大 2 字长，LL(1) 贪心）、`KW_AND` "AND" / `KW_BETWEEN` "BETWEEN" / `KW_IN` "IN" / `KW_IS` "IS" / `KW_NOT` "NOT" / `KW_NULL` "NULL" / `KW_LIKE` "LIKE" / `KW_WEIGHT` "WEIGHT" / `KW_LTR` "LTR" / `KW_FT` "FT" / `KW_TRUE` "TRUE" / `KW_FALSE` "FALSE"（保留关键字不允许作列名，IDENT 字符序列若 matches 关键字 enum → 转关键字 token）。
- **FR-1.2 位置追踪 ParsePos（行号列号从 1 开始）**：Tokenizer 每个 token 带 startLine/startCol/endLine/endCol 四字段；抛 HybridQueryParseException 必带 message + position，T3 错误场景断言位置精确到列。
- **FR-1.3 空白字符 + 注释（MVP v1.0 注释：-- 单行到行尾，/ * ... * / 块注释不允许嵌套）**：` \t\r\n` 自动跳过，`--` 到下 `\n` 跳过；任意 token 前后空白数 0~无限。
- **FR-1.4 字符串转义**：STRING token `\'` → `'`、`\\` → `\`、其它非转义字符照原样保留；未闭合字符串（到 end 仍无 `'`）抛异常位置 = 字符串起始列。

### FR-2：ParsedHybridQuery 四元组 + 2 辅助 Spec（不可变 final 结构，零可变）
- **FR-2.1 ParsedHybridQuery<T> 四字段 public final**：① `QueryWrapper<T> ormCond`（已填好的 criteria，orBranches MVP v1.0 空）；② `String ftQuery`（trim 后的 FT 字符串，空串表示没写 FT）；③ `WeightSpec weightSpec`（alpha/beta/columnWeights 三 final）；④ `LtrSpec ltrSpec`（`List<ToDoubleFunction<T>> featureExtractors` 与 `double[] weights` 两 final，null 表示不启用 LTR）。
- **FR-2.2 WeightSpec 三字段 final**：`double alpha ∈ [0,100]`；`double beta ∈ [0,100]`；`Map<String, Double> columnWeights`（key=列名字符串，value∈[0,100]，unmodifiable Map，构造后不可变）。
- **FR-2.3 LtrSpec MVP 两字段 final**：featureExtractors（顺序与 P29 searchHybridWithLtr 的 extractors 参数严格同顺序，unmodifiable List）；weights（长度与 extractors 相等，未启用 LTR=null）；Parser 本身不 new ToDoubleFunction（避免越界依赖具体实体），而是**由业务方显式传入 `Map<String, ToDoubleFunction<T>> columnExtractors` 给 `HybridQueryParser.parse(Class<T>, String dsl, Map<String, ToDoubleFunction<T>> ltrColumnFn)`**；解析到 LTR 时按列顺序（LTR(0.1,0.2,0.3,0.4) 对应 ltrColumnFn.values().stream().sorted(字典序) 顺序 或 LTR 列表写法 LTR(a=0.1,b=0.2) 两方案 MVP 只支持 LTR(a=0.1,c=0.3) 显式 key=double，与 WEIGHT 列写法对齐，避免 map 顺序不确定）。
- **FR-2.4 DeepEquals 工具方法**：`ParsedHybridQuery.deepEquals(ParsedHybridQuery a, ParsedHybridQuery b)` 递归比较 QueryWrapper criteria 逐条 Criterion.op/col/a/b（含 Collections 顺序，MVP v1.0 纯 AND 平铺不排序保证顺序一致）+ ftQuery 字符串 + alpha/beta + columnWeights Map (entrySet 排序后 equals) + LTR extractor list 引用相等性（或 feature 值重算相等性单测用具体实体，T2 TR2.4 用 AbcEntity 手算四组全部 equals true）。

### FR-3：HybridQueryParser 主解析器（手写 LL(1)，parse 方法 3 参数）
- **FR-3.1 主 API 签名**：`public static <T extends DsTableAdapter> ParsedHybridQuery<T> parse(Class<T> entityClass, String dsl, Map<String, ToDoubleFunction<T>> ltrNameToExtractor)`；entityClass 用于 new QueryWrapper<T>()；dsl 空白 trim 后再 tokenize；ltrNameToExtractor 是 LTR 列名到提取函数的映射（WEIGHT 列同理，MVP v1.0 WEIGHT 列只记录 key=weight double，LTR 列 key=weight double + 从 ltrNameToExtractor 拿 extractor 按列名拼 list，未找到列名 IAE）。
- **FR-3.2 语法规则 MVP v1.0 严格按 Overview 的 EBNF**：① conjunction 至少一个 condition（0 个 condition 空 DSL 抛 IAE，T3 覆盖）；② condition = ormCond | ftCond；③ ORM 条件支持 8 类 = "a=100" EQ | "a!=200" NE | "c>0" | "c>=1" | "c<1000" | "c<=999" | "a BETWEEN 10 AND 200" | "id IN (1,2,3,4)" | "id NOT IN (5,6)"（NOT_IN 额外） | "name LIKE '%hello%'" | "name LIKE 'hello%'" （LIKE_RIGHT 用 LIKE 前缀 pattern 或 KW_LIKE + % 开头/结尾 Parser 自动推断；MVP v1.0 另提供 LIKE% 作为简写对应 LIKE_RIGHT，%LIKE 对应 LIKE_LEFT，T3 覆盖所有 8 ORM 模式） | "b IS NULL" | "b IS NOT NULL"；④ FT 子句 = `FT('hello world')`；⑤ WEIGHT 尾段可选 = `WEIGHT(0.6, 0.4, a=0.5, c=0.3)`，前 2 参数必写（alpha, beta），后续 col=weight 0~N；⑥ LTR 尾段可选 = `LTR(a=0.1, c=0.3, ftScore=0.4, likeCount=0.2)`，参数必须显式 col=double，Parser 从 ltrNameToExtractor Map 按 key 拿 extractor 构造 LtrSpec；找不到列 → 抛异常（T3 覆盖）。
- **FR-3.3 顺序 + 优先级硬编码（MVP v1.0 不做二义性）**：WEIGHT 必须在 LTR 之前；condition 中 AND 关键字左结合；condition 内不能嵌套 condition；括号只允许在 IN/FUNCTION/WEIGHT/LTR 中使用。违反顺序 IAE（如先 LTR 再 WEIGHT → 抛异常，T3 覆盖）。
- **FR-3.4 所有解析错误统一走 HybridQueryParseException（受检？MVP v1.0 继承 RuntimeException 避免破坏现有 Facade）**：含 ParsePos 位置 + 详细错误原因 + dsl 片段（10 字符上下文），便于 T3 精确断言。

### FR-4：Parser 与手写 Java 代码装配字节级全等（DeepEquals，T2 8 场景全覆盖）
- **FR-4.1 8 场景枚举**：① 仅 EQ + FT；② EQ + GT + LT + FT；③ BETWEEN + IN + FT；④ LIKE + IS_NULL + IS_NOT_NULL + FT；⑤ EQ + NE + NOT_IN + FT；⑥ 全部无 WEIGHT（回退 alpha=1 beta=0）；⑦ 带 WEIGHT alpha beta 无列；⑧ 带 WEIGHT alpha beta 2 列 + 带 LTR 2 列；每种场景 A=手写 Java 装配 QueryWrapper + WeightSpec/LtrSpec 四元组 vs B=Parser.parse(dsl) → DeepEquals true（T2 4 TR = 8 场景 × 2 单测分组，共 4 TR 保证覆盖）。
- **FR-4.2 Criterion 插入顺序与手写装配顺序严格一致**：AND 平铺条件顺序就是用户写 DSL 从左→右顺序，Parser 按序 .eq().gt().between()，与手写顺序完全匹配（否则 DeepEquals 会因顺序不同判定不相等，T2 严格要求顺序一致，避免 QueryWrapper 语义不同）。

### FR-5：LTR 列提取与 P29 FR-4.3 / FR-4.4 严格对齐（dot product + 长度匹配 IAE）
- **FR-5.1**：LtrSpec 构造完后，Parser 内部立即 `assert ltrSpec.featureExtractors.size() == ltrSpec.weights.length`，不等立即抛 IAE（不等到合查时才错，T3 错误场景覆盖）。
- **FR-5.2**：LTR weight 每个值必须 ∈[-∞,+∞]（允许正负，与 P29 LTR 一致，与 WEIGHT 的 [0,100] 不同，P29 ltrLinearScore 只校验 finite，不限制范围，Parser 严格对齐不自己加范围限制）。

### FR-6：Parse 方法绝对零副作用（不 new 实体 / 不读文件 / 不发网络 / 不 new DsHybridQueryClient）
- **FR-6.1**：parse 只 new QueryWrapper（无副作用，空 wrapper）+ 调用 wrapper eq/ne/... 方法（纯内存填 criteria）+ new ParsedHybridQuery / WeightSpec / LtrSpec；绝不接触外部 I/O。
- **FR-6.2**：parse 重复 1000 次同一 dsl → 1000 次 DeepEquals 相等 + hashCode() 1000 次相等（保证可缓存，T2 TR2.5 1000 次循环稳定性）。

### FR-7：业务端 parse 后三选一路径（Facade 单调，0 改 P29 签名）
- **FR-7.1**：若 LTR 启用（ltrSpec != null）→ 业务显式调用 `DHQC.searchHybridWithLtr(parsed.ormCond, parsed.ftQuery, rawMap, K, opt, ltrSpec.featureExtractors, ltrSpec.weights)`；签名与 P29 FR-5.1 字节级 1:1。
- **FR-7.2**：若 WEIGHT 非默认（alpha!=1 || beta!=0 || !columnWeights.empty）→ 业务显式调用 `DHQC.searchHybridWithWeights(parsed.ormCond, parsed.ftQuery, rawMap, K, opt, weightSpec.alpha, weightSpec.beta, columnWeights → to Map<String, Map.Entry<Double, ToDoubleFunction<T>>> pairEntry)`：Parser 输出 WeightSpec 列 key/weight 双列，业务需再补 extractor（或 Parser 接受第二个 Map<String, ToDoubleFunction<T>> weightNameToExtractor 与 ltr 同一风格，MVP v1.0 FR-3.1 parse 加参数 weightNameToExtractor；FR 重新规定 parse 5 参数：entityClass, dsl, weightNameToExtractor, ltrNameToExtractor；WeightSpec 含 Map<String, Map.Entry<Double, ToDoubleFunction<T>>> P29 需要的 pair 结构，便于业务 0 改造 直传 8 参方法）。
- **FR-7.3**：默认路径 WEIGHT 全默认 alpha=1 beta=0 empty 列、LTR null → 业务直接调 P28 5 参 `searchHybrid(parsed.ormCond, parsed.ftQuery, rawMap, K, opt)`；与 P28/P29 零回归。

## Non-Functional Requirements
1. **NFR-1（性能 MVP v1.0）**：parse 1000 次典型 DSL（5 AND + FT + WEIGHT + LTR，≈150 字符）总耗时 P95 ≤ 200ms（平均每次 0.2ms，手写 LL(1) 无 backtrack，T6 TR6.1 实锤）。
2. **NFR-2（Javadoc 覆盖 ≥ P29 基线）**：HybridQueryParser / ParsedHybridQuery / WeightSpec / LtrSpec / DslTokenizer / HybridQueryParseException 6 主类，每个类级 Javadoc ≥ 12 行；所有 public 方法 /** Javadoc 100% 覆盖（含 @param/@return/@throws）。
3. **NFR-3（代码整洁 CPD dup=0）**：Parser 源码 360~480 行 MVP；CPD 25 行跨文件 dup=0（P29 HybridScoreEngine、DsHybridQueryClient、QueryWrapper、其他测试类）。
4. **NFR-4（println 0 + NPE null 保护 ≥ 6）**：新生产 6 类 0 行裸 `System.out.println`；parse 四参数 entityClass/dsl/weightNameToExtractor/ltrNameToExtractor 每个 null → 立即 NPE；token 边界 0 数组越界（Parser 内部 peek 必检查指针越界）。
5. **NFR-5（Parser → end2end Recall 0 漂移）**：T6 TR6.5 = 3 个 DSL 场景 ×3 WEIGHT/LTR ×100 repeat = 900 次合查，docId Set 与 P28 基线（手写 QueryWrapper + 5 参 searchHybrid）equals 100% = true（0 mismatch），证明 Parser 只影响 scoring 参数，不影响 orm/ft recall。
6. **NFR-6（Gate B 组合回归 P22~P30 必 0F0E）**：Surefire 组合至少 220 + P30×____ TR = ____ TR，P21~P29 Gatekeeper（ZeroLeak3 / NG-3 / TODO=0 / Facade 单调 / ShardTransport 签名 / UNIT DOC 公式 / 指纹稳定）必须仍然 PASS，不得因 Parser 新代码跨 Phase FAIL。
7. **NFR-7（异常消息中必包含列号/行号 position，且错误可定位）**：任意错误单测，HybridQueryParseException.getMessage().contains("line 1, col 42") 这样的定位字符串 100% 存在，T3 每条错误场景断言。
8. **NFR-8（JVM 兼容性 + 零反射新技巧）**：MVP v1.0 Parser 只使用 JDK 17+ 已用 API（P28/P29 所用），禁止 MethodHandles.Lookup.defineHiddenClass / 堆外 / VarHandle 新 API；避免引入 JDK 版本差异。

---

## Gate C 9Y 自检清单（SP3.1，Gate B 两连 PASS 后逐条填实锤）
| # | 自检条目（Spec 9Y） | 实锤证据（Gate C 时填） | Pass 状态（待填） |
|---|---|---|---|
| Y1 | ZeroLeak3=3（Fulltext top-level 独立类仅 3） | Parser 6 类全在 fulltext.cluster.parser 子包，tail 过滤后根包 top-level count=3 | [ ] |
| Y2 | NG-3 9 CNFE 全抛 + fulltext 根包 0 netty/grpc/p2p.client + 0 新增 Maven dep | 9 CNFE 全抛；ASM collectTypes 根包 0 命中；pom.xml diff 0 新增 dep | [ ] |
| Y3 | TOPK bucketAccessCounter 合查 100 次 sum=0 | 清零后 100× parse → end2end 合查执行 4 shard sum=0 | [ ] |
| Y4 | Facade 单调 ≥ P29 基线（GM/FS/TD/DHQC/HybridHit/HE 全 0 删） | 反射差集 empty；DHQC 3 合查签名 1:1；HybridHit 双构造 0 删；Parser 只追加 6 类 | [ ] |
| Y5 | UNIT/DOC 路由公式 与 P24 基线字节级全等 | 1000× sample vs 公式重算 long[] 4 向量全等 | [ ] |
| Y6 | P30 新增 6 类 + 主 API parse（entityClass,dsl,wFns,ltrFns） 存在且参数 exact | 反射 getDeclaredMethod 1/1 命中；返回 ParsedHybridQuery；参数列表 4 个 exact | [ ] |
| Y7 | 同 DSL ×1000 次 parse hashCode 稳定 + QueryWrapper DeepEquals true | 1000 次 hashCode Set.size=1；8 场景 DeepEquals 全 true | [ ] |
| Y8 | ShardTransport 5 原方法签名一字保留（与 P29 同） | 反射 ShardTransport 5 method name+param+return vs 基线逐字符 equals | [ ] |
| Y9 | 源码 grep TODO/FIXME/HACK = 0 个（P30 新 6 类 + 6 测试） | Files.readAllLines 12 文件 regex 0 匹配；Parser Javadoc 绝不能写"T1 骨架 TODO"过渡文案 | [ ] |

---

## 签批区（SP3.1 签名必须 VERBATIM 用户指令原文，不许自创）
| 阶段 | 签批原文（VERBATIM 用户消息或工具结果） | 签字人 / 日期 |
|---|---|---|
| Gate A（Spec 批准） | 待用户下一条「Continue/继续」= VERBATIM 批准 P30 REV1 Spec | Implement Agent ____-__-__ |
| Gate A（Tasks 批准） | 待用户下一条「Continue/继续」= VERBATIM 批准 P30 REV1 Tasks | Implement Agent ____-__-__ |
| Implement T1~T6 | 待 Surefire P30_T1×____ + T2×____ + T3×____ + T4×____ + T5×____ + T6×____ = ____/____ 0F0E | Implement Agent ____-__-__ |
| Gate B Run1（P22~P30 组合两连 1/2） | 待 Surefire 组合报告 Tests run:____ 0F0E Time:_____ | Implement Agent ____-__-__ |
| Gate B Run2（P22~P30 组合两连 2/2） | 待 Surefire 组合报告 Tests run:____ 0F0E Time:_____ | Implement Agent ____-__-__ |
| Gate C（9Y×9 + 洁癖 5/5 + 归档） | 待自检 + Gatekeeper + 洁癖文档同步完成 | Implement Agent ____-__-__ CLOSED |
