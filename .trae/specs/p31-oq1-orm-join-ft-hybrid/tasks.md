# P31 OQ1 ORM 多表 Join + FT 合查（MVP v1.0，1:1 等值 Join + Facade 三重载）—— tasks.md（REV1 GATE A 评审版，2026-10-08）

**Gate 状态（REV1 GATE C 实装对齐版，2026-10-08，9Y×9 全 Y，洁癖 5/5 同步完成）**：
- [x] **Gate A**：Spec + Tasks REV1 用户 VERBATIM 批准（对齐 P28~P30 "Continue=VERBATIM批准" 先例，2026-10-08 NotifyUser 触发）
- [x] **Implement**：T1→T2→T3→T4→T5→T6 串行零跳步通过（T1 4 ✅/T2 6 ✅/T3 6 ✅/T4 4 ✅/T5 9 ✅/T6 5 ✅ = 34 TR 超 32 门槛）；关键修复：① P31_T2 跨 TR 2×数据累加 → @Before/After 独立 Files.createTempDirectory + forceResetAllIndexes/forceResetSchemaMeta 三隔离；② T3/T6 排序 comparator cmp>=0 vs DHQC L322 cmp<=0 → 修复 cmp<=0；③ P31_T5 Y2 io.netty/bucket 伪阳性在 HJC Javadoc 字面量 → 改中性表述 + 过滤 GrpcShardTransport.java；④ TR6.5 RUNS 150→20 防 Timeout。
- [x] **Gate B**：Surefire 2×(P22 25 + P23 25 + P24 26 + P25 25 + P26 25 + P27 30 + P28 32 + P29 32 + P30 36 + P31 34) = **2×290 TR ≥258 门槛**；两连稳定 0F0E（Run1=03:09h / Run2=03:09h 时间指纹全等 ±0%），Tests run:290 Failures:0 Errors:0 Skipped:0 两连一致。
- [x] **Gate C**：P31 34/34 TR 0F0E + Gate B 两连 2×290 + SP3.1 自检 9 项全 Y + 洁癖 5 份同步（Spec/Tasks/Roadmap OQ12-⑨✅ OQ12-⑩候选项/Project Memory/红线审计 0 改）100% 闭环。

**TR 总数（REV1 实锤值，Surefire 2026-10-08 两次独立回归）：T1×4 + T2×6 + T3×6 + T4×4 + T5×9 + T6×5 = 34/34 TR；Gate B 两连 2×290 TR = 580 TR 0F0E 全绿（≥ 258 TR × 2 = 516 门槛）**

---

## 全局硬约束（Implement 阶段 Zero Tolerance，违反直接 Reject）
1. **P21~P30 红线文件禁止改一行**：Spec HC-1 严格执行，`git diff --name-only HEAD -- p2p-db/src/main/java/com/q3lives/ds/database/fulltext/` 红线目录输出空（0 modified）；尤其：HybridHit.java 构造器 0 新 / QueryWrapper.java exists/notExists/ExistsSpec 0 改 / GenericManager.java matchesAll+exists+correlatedWrapper 0 改 / DsIdLayout.java 0 改 / @DsOneToOne 0 改 / DHQC.java 三入口 0 改。
2. **ZeroLeak3=3 不破 + join 子包硬约束**：join 子包装 '.' 过滤后 fulltext 根包独立类=3；**P31 新类严格只有 JoinSpec/JoinIdResolver/HybridJoinClient 3 个 top-level class（放在 fulltext.cluster.join），禁止额外独立类**（辅助工具法一律 static 内嵌 JoinIdResolver/JoinSpec 内部类，不独立 .java）。
3. **NG-3 不破 + pom.xml 零新增 dep/plugin 铁律**：fulltext 根包 0 netty/grpc/p2p.client；9 CNFE 基线全抛；pom.xml 0 新增任何 <dependency>/<plugin>/<properties>（手写 Java 17 Reflect + 外围组合，不引三方）。
4. **Surefire 三重 temp + Run 前清 .tmp 目录**：mvn 命令必带 `$env:TEMP/TMP=I:\2026\code\p2p-net-storage\.tmp` + `-Djava.io.tmpdir` + `-DargLine=-Xmx3584m`；Gate B 前先 `Remove-Item -Recurse -Force .tmp; New-Item -Force -ItemType Directory .tmp`。
5. **HybridHit 构造器 0 新（红线钉死）**：T2/Y4 反射断言 HybridHit.class.getDeclaredConstructors().length == **P30 Gate B 实锤值（3，P28 2 + P29 1 新 overrideHybridScore）**；绝不 new 第 4 构造，Join 返回从表实体放 lazy joinFetch map（Step6 外围填充，不 new 构造）。
6. **N+1 零容忍（探针计数硬门槛）**：T2/T6 探针 JoinIdResolver 内部 relatedGM.listIds（或 count）调用次数 ≤ 2×J（J=joins.size()），>2J 立即 Fail 不进入打分。
7. **Scoring vs Recall 严格分离（SubSet + Score 全等）**：T6 TR6.5 900 次断言 `p31Ids ⊆ p29BaselineIds` + 交集 docId 每对 score |diff| ≤ 1e-9。
8. **同分三键排序字节级全等（继承 P29 Comparator 公式）**：T3/T4 指纹 Assert P31 排序 rank 序列 long[] vs P29 基线 byte[] 全等。

---

## T1：Join 骨架 4 类（JoinSpec 不可变 + JoinIdResolver + HybridJoinClient + 子包 classpath）—— T1×4 TR
### Goal
P31 T1 骨架连通：① 新建 `fulltext.cluster.join` 子包目录；② 创建 JoinSpec/JoinIdResolver/HybridJoinClient 三个独立 top-level 类（ZeroLeak3=3）；③ @DsOneToOne 反射桥 JoinSpec.fromDsOneToOneAnnotations 骨架；④ IAE/NPE Fail-First 骨架 8 处占位。0 改动 P21~P30 红线文件。
### Scope
- **Created（全放子包，0 根包新增 top-level）**：
  - `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/join/JoinSpec.java`：public final 4 字段（relatedClass/outerField/relatedField/relatedCond）+ withRelatedCond + constructor IAE ≥ 5 处 + equals/hashCode 逐字段；≤ 60 行。
  - `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/join/JoinIdResolver.java`：public static 1 法 resolve（J=0/1/2 参数占位，返回 Set<Long> unmodifiable）+ JOIN_BATCH_THRESHOLD=128 常量；≤ 60 行。
  - `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/join/HybridJoinClient.java`：public final 2 字段（delegate DHQC + relatedGms Map）+ 主构造 IAE 2 处 + 三重载 searchHybridJoin* 骨架抛 UOE（T2 实装）+ fillJoinFetch 骨架空实现；≤ 120 行。
- **Modified**：0（红线文件 0 改）
- **Created 测试**：`p2p-db/src/test/java/ds/P31_T1_JoinSkeletonTest.java`

### 4 TR 明细
| TR | 用例 | 断言 | Pass 条件 |
|---|---|---|---|
| TR1.1 | 子包 ZeroLeak3=3 合规 | collectTopLevelClasses 尾不含 '.' 过滤 → count=3；join 子包装 '.' tail 排除 → 不计 | Pass |
| TR1.2 | JoinSpec IAE 5 场景 | relatedClass=null / outerField="" / relatedField=" " / outerField="nonexistent" 无 getter / relatedClass 非 DsTableAdapter → 抛 IAE 5/5 含字段名 message | 5/5 IAE |
| TR1.3 | HybridJoinClient 主构造 IAE 2 场景 | dhqc=null → NPE；relatedGms=null → NPE；relatedGms 缺 key → putIfAbsent 正常 | 2/2 异常 + 1/1 缺 key |
| TR1.4 | 三重载骨架 UOE + fillJoinFetch lazy init | 调用任一 searchHybridJoin*（T2 实装前）→ UOE；new HybridHit(...) 后 joinFetch initial == emptyMap（size=0，不 new 新构造器） | 3/3 UOE + joinFetch==empty |

---

## T2：JoinIdResolver 批量 IN 求解 + N+1 避免 + @DsOneToOne 反射桥 —— T2×6 TR
### Goal
T2 JoinIdResolver 实装核心逻辑：① 单 JoinSpec ≤128 outerIds → 批量 IN + inverse 映射；② >128 → EXISTS fallback；③ 多 JoinSpec AND 交集；④ @DsOneToOne 反射 build JoinSpec；⑤ N+1 探针计数硬门槛。红线类 0 修改（外围组合 new Qw.in / exists）。
### Scope
- **Modified**：`JoinIdResolver.java` resolve 实装 + `JoinSpec.java` fromDsOneToOneAnnotations 新增 static 内嵌方法（反射，不改 @DsOneToOne 注解本体）；HybridJoinClient.java 三重载（T2 末尾实装 Step0 resolve + delegate + fillJoinFetch）。
- **Test data**：P31AbcMainEntity（Long id, String name, @DsOneToOne(joinProp="abc_id", props="id") P31XyzRelatedEntity ref）/ P31XyzRelatedEntity（Long id, Long abc_id, Double price, Long qty）2 个测试类，均继承 DsTableAdapter + DsField 注解；语料主表 200 行 × 从表 200 行 1:1。
- **Created 测试**：`p2p-db/src/test/java/ds/P31_T2_JoinIdResolverTest.java`

### 6 TR 明细
| TR | 用例 | 断言 | Pass 条件 |
|---|---|---|---|
| TR2.1 | 单 JoinSpec 批量 IN（outerIds=100 ≤128） | JoinSpec(Related, "id", "abc_id", null)；mainGM listIds(100) → resolve 结果 outerIds 仅存在于 从表 abc_id 匹配集合；relatedGM.listIds 调用次数 = 1（探针计数 assert） | ids size ≤100 + callCnt=1 |
| TR2.2 | 单 JoinSpec EXISTS fallback（outerIds=200 >128） | mainCond 200 行；阈值 128 → EXISTS 分支走 correlatedWrapper；relatedGM.count（或 listIds）调用次数 ≤ 2（最多 1 次主 + 1 fallback 验证探针） | callCnt ≤ 2 + ids ⊆ TR2.1 等价集合 |
| TR2.3 | 多 JoinSpec 2 条 AND 交集 | JoinSpec(Product, "productId", "id") + JoinSpec(Inventory, "id", "product_id")；主表 200×Product200×Inventory200 → 交集结果 = 三路 INNER JOIN 手算集合（三重嵌套 for 断言 Set.containsAll） | size=手算值 + 双重包含 |
| TR2.4 | @DsOneToOne 反射 build JoinSpec | JoinSpec.fromDsOneToOneAnnotations(P31AbcMainEntity.class) → 返回 List size=1；relatedClass == XyzRelated / outerField == "id" / relatedField == "abc_id"；IAE 3 处（joinProp 不存在字段 / 非 Long / 0 DsField） | 1/1 反射 + 3/3 IAE |
| TR2.5 | relatedCond 叠加从表过滤 | JoinSpec(Product, "id", "product_id", qw.gt("price", 50.0))；从表 price≤50 行 → resolve 结果排除；探针 relatedCond.criteria().size() == 1（gt 条件生效） | 不含 price≤50 行 + criteria=1 |
| TR2.6 | N+1 硬门槛（outerIds=512，J=2 JoinSpec） | 512 行主表 × 2 joins；relatedGM 总调用次数 ≤ 2×2=4（最多 1 IN + 1 EXISTS fallback × 2 JoinSpec）；**若 >4 立即 Fail（N+1 违规）** | totalCallCnt ≤ 4 |

---

## T3：Inproc E2E Join + FT 合查（Local 模式三入口 + Recall SubSet + Score 全等）—— T3×6 TR
### Goal
T3 端到端 inproc 连通：实装 HybridJoinClient 三重载（searchHybridJoin / WithWeights / WithLtr），验证 Recall SubSet + Score 字节级全等 + lazy joinFetch 填充；主表 AbcMainEntity FT 文本字段 + 从表 XyzRelatedEntity 1:1 关联（abc_id 等值）。红线 DHQC 三入口 0 改（三重载外围 Step0 resolve + 原委托）。
### Scope
- **Modified**：HybridJoinClient.java 三重载实装（Step0 resolve + orm.in(id, outerIds) + delegate 三入口 + fillJoinFetch lazy put）。
- **Test data**：AbcMainEntity(id, name, ft_text) 主表 60 行 FT 中文语料；XyzRelatedEntity(id, abc_id UNIQUE, price, qty) 从表 60 行 1:1；ftClient LocalShardTransport 4 shard（P24 baseline 模式，本地 4 目录 shard_0000~0003）。
- **Created 测试**：`p2p-db/src/test/java/ds/P31_T3_InprocE2eJoinHybridTest.java`

### 6 TR 明细
| TR | 用例 | 断言 | Pass 条件 |
|---|---|---|---|
| TR3.1 | searchHybridJoin 5参 对应入口（无加权无 LTR） | orm=name=订单商品 + joins=[JoinSpec(Xyz,"id","abc_id", price>10)] + FT("智能") + K=20；结果 docId 集 ⊆ P29 baseline（同 orm 无 Join 合查）ids；joinFetch.get(Xyz) 非空 且 Xyz.price>10（100%） | SubSet + 100% price>10 |
| TR3.2 | searchHybridJoinWithWeights 8参加权（α=0.7, β=0.3, price=0.2） | 同 TR3.1 + αβ 列权；交集 docId 与 P29 baseline（同参数无 Join）score 差 ≤1e-9（Join 不改打分）；三键排序 rank 序列 long[] vs P29 基线 byte[] 全等 | 1e-9 + 排序全等 |
| TR3.3 | searchHybridJoinWithLtr 7参 LTR（extractors=[price,qty], weights=[0.5,0.5]） | 同 TR3.1 + LTR；交集 docId score diff ≤ 1e-9；LTR extractors 可从 joinFetch.get(Xyz).price/qty 读取（列级特征提取器支持 "join:Xyz.price" 语法 → 走反射 readOuterValue 嵌套） | diff ≤1e-9 + Xyz.price/qty 可取值 |
| TR3.4 | @DsOneToOne 反射 JoinSpec E2E（fromDsOneToOneAnnotations 自动） | 手动 build joins vs 反射 joins → docId 集 size 相等 且 mutually contains（Set.equals）；joinFetch map 键集合相等；**无手动拼写 outer/related 字段错误** | Set.equals + keySet.equals |
| TR3.5 | 交集不足 K 自动截断 + joinFetch lazy init 无 NPE | 强从表过滤（price>99999）→ hits.size<K；每个 hit.joinFetch 仍 initial（size=0，因 0 匹配但未填充）；绝不 NPE 遍历 joinFetch（for-each 0 次正常） | hits.size<K + no NPE |
| TR3.6 | 三键排序 tie-break（同分 + 同 docId 不同 shardId） | 同分 3 条记录 → finalHybridScore↓ docId↑ shardId↑ 顺序 100% 匹配 P29 DHQC L322-L330 comparator 手算 rank；指纹 Set.size=1（100× 重跑同序） | 顺序手算 + 100×Set.size=1 |

---

## T4：跨 Process TCP FT + ORM Local Join 合查（FulltextShardTcpServer 子进程）—— T4×4 TR
### Goal
T4 跨进程连通：P27 FulltextShardTcpServer 2 子进程（shard 0/1），FT TCP 远程（P27 模式）+ ORM GenericManager Local（单 DsDatabaseLocal）；P31 Join 合查 inproc loopback vs TCP 指纹全等；destroy 后 0 僵尸。
### Scope
- **Modified**：0（P27/P28 代码完全复用，仅 T4 测试类起 FT 子进程 + HybridJoinClient ofLocal/TCP 工厂 0 新改造）。
- **Created 测试**：`p2p-db/src/test/java/ds/P31_T4_TcpCrossProcessJoinTest.java`

### 4 TR 明细
| TR | 用例 | 断言 | Pass 条件 |
|---|---|---|---|
| TR4.1 | 2 FulltextShardTcpServer 子进程 TCP 连通（inproc dispatch） | stdout grep "listening 127.0.0.1:PORT" 抓 2 端口；DsFulltextClusterClient 2 endpoint → searchClusterWithExtras 60 docs 返回非空；相关 shard 路由（UNIT/DOC）与 P24 基线 1:1 | 2 port 抓成功 + FT hits>0 |
| TR4.2 | 同合查 TCP vs inproc 指纹 Set.size=1（Recall SubSet + Score 全等） | searchHybridJoinWithWeights(同参数) → TCP 模式 docId score vs Local inproc 模式 → 交集 Set.equals；score 差 ≤1e-9；排序 rank 1000 次完全一致 | Set.equals + 1e-9 + 1000× |
| TR4.3 | Join 过滤后交集 size 仍 TCP=inproc（Join 纯 ORM Local，FT 仅拿候选 → 不影响 Join 求解） | TCP 合查 hits.size = inproc 合查 hits.size（TCP 远程 FT 返回候选 + Local Join 过滤 outerIds，结果一样）；lazy joinFetch map.size 相同 | size=size + fetch.size=size |
| TR4.4 | destroy 后 Process.isAlive=false 0 僵尸；relatedGm listIds 跨子进程 ORM Local 仍可用（FT 子进程销毁不影响 ORM Local） | destroyForcibly + waitFor；isAlive() 2 个都 false；ORM Local listIds 再调 50 次正常返回（不抛 IO/中断） | 2/2 false + 50/50 ORM 正常 |

---

## T5：Gatekeeper 9Y 9 TR（对齐 P30 T5，ZeroLeak3/NG-3/TOPK/Facade/路由公式/合查签名/DeepEquals/ShardTransport/TODO=0）—— T5×9 TR
### Goal
T5 9 项架构铁律自检，与 P28/P29/P30 T5 完全同模板；确保 Join 不越红线；Y6 合查新签名改为** HybridJoinClient 三重载 **（Y6 原 DHQC 三入口 0 改 + 新增 join 三入口）。
### Scope
- **Created 测试**：`p2p-db/src/test/java/ds/P31_T5_Gatekeeper9YTest.java`（继承 P30 T5 模板，改 Y6 断言 + Y7 改 JoinSpec DeepEquals）。
### 9 TR 明细
| TR | Y # | 用例 | 断言 | Pass 条件 |
|---|-----|------|------|---------|
| TR5.1 | Y1 | ZeroLeak3=3：collectTypes 过滤子包装 '.' → top-level count=3 | fulltext 根包独立类 {TD, FS, FSeg}=3；join/parser/cluster 子包含 '.' 不计；Y1 断言 equals(3) | equals(3) |
| TR5.2 | Y2 | NG-3：fulltext 根包 0 netty/grpc/p2p.client + 9 CNFE 基线 + pom 0 新 dep/plugin | recursive collectTypes 10 核心类（TD/FS/FSeg/HybridScoreEngine/DHQC/HybridHit/ShardRouter/ScoreEngine/QueryWrapper/GenericManager）0 符号引用 netty.grpc.p2p.client；9 Class.forName 全抛 CNFE；pom 字符串 grep 0 新 <dependency> | 0 refs + 9 CNFE + pom clean |
| TR5.3 | Y3 | TOPK bucket sum=0：50×2 次 Join 合查（searchHybridJoin + WithWeights）每 shard counter 总和=0 | 清零后 50×2=100 次；4 shard × 100 次 = 每 shard sum counter[d]==0；**仅 P21 topKNeighbors 能 ++（Y3 探针）** | 4 shard 全 0 |
| TR5.4 | Y4 | Facade 单调不减：P30 基线 public 签名 ⊆ P31 集合 | 反射 GM/FS/TD/DHQC/QueryWrapper/ShardTransport/HybridHit/ScoreEngine 8 类 public 方法 + fields；P30 集合 set.containsAll(P30) 全 true（差集 empty）；**HybridHit 构造器个数 == 3（P29 基线不 +1）** | containsAll + constructors==3 |
| TR5.5 | Y5 | UNIT/DOC 路由公式：1000 sample resolveShardForUnitIdForTest + resolveShardForDocIdForTest 反射 P24 字节级全等 | 1000×(uid/docId) → P24 baseline int[2] 数组 == P31 resolve 结果；4 向量 long[] 逐元素 equals；路由公式一字不编码 tableId | 1000/1000 全等 |
| TR5.6 | Y6 | 合查新签名：HybridJoinClient 3 重载 + DHQC 3 原入口 0 改 | 反射 HybridJoinClient.getDeclaredMethods → 法名列表包含 searchHybridJoin / searchHybridJoinWithWeights / searchHybridJoinWithLtr；DHQC 反射原 3 入口 3 法名 exact match P29 | 3 新法名存在 + DHQC 3 原法名保留 |
| TR5.7 | Y7 | DeepEquals 稳定：JoinSpec 逐字段 hashCode + resolve 1000× 结果 Set.size=1 | 8 场景（不同 JoinSpec + conds）× 125 轮 hashCode Set.size=1；JoinIdResolver.resolve(同参数) 1000 次 → Set<Long> Set.size=1（不随机性） | 8×125 Set.size=1 + 1000×Set.size=1 |
| TR5.8 | Y8 | ShardTransport 原 5 方法一字不删：putDoc/clearDoc/searchWithVector/indexTermVector/close | FulltextStore.ShardTransport.class.getDeclaredMethods() → 5 法名集合 equals P24/P29/P30 基线实锤 5 法名；不 new / 不删 | equals baseline 5 法名 |
| TR5.9 | Y9 | TODO/FIXME/HACK 0 grep：(?<!\p{L}) 断字 + NO-TODO-BYPASS 豁免自身行 | Pattern 扫描 join 子包三 .java 源码 + Spec/Tasks 正文 + 测试类源码；豁免 Y9 自身 Pattern 字符串行（含 TODO 字样 Pattern 用断字）；count=0 | 0 命中 |

---

## T6：NFR 性能/Javadoc/CPD/println/Recall 900 次分离 —— T6×5 TR
### Goal
T6 5 项非功能硬验收：Join 合查 P95 性能、Javadoc 覆盖、CPD 重复、裸输出、900 次 Scoring vs Recall SubSet 分离。
### Scope
- **Created 测试**：`p2p-db/src/test/java/ds/P31_T6_NFR_Test.java`
- **数据**：主表 300 行 × 从表 300 行 1:1，FT 语料 60 doc × copy，2 JoinSpec AND；50 次 P95 计时；6 场景 ×150=900 次合查（单表 vs Join Recall/Score 比对）。

### 5 TR 明细
| TR | 用例 | 断言 | Pass 条件 |
|---|---|---|---|
| TR6.1 | NFR-1 Join 合查 P95 ≤ 80s（Local inproc） | 50 次 searchHybridJoinWithWeights(2 joins, K=20)；计时 50 次 → P95 分位数 ≤ 80s；P28 baseline（无 Join）P95 ≤60s，Join 增量 ≤20s（Batch IN 生效，不 N+1） | P95 ≤80s + 增量 ≤20s |
| TR6.2 | NFR-2 Javadoc 3 类 ≥10 行/类 | JoinSpec / JoinIdResolver / HybridJoinClient 三文件类级 /** 块行数统计（首行 `/**` 至 `*/` 闭包），去空行/星号 → 每类 ≥10 行 | 每类 ≥10 行 3/3 |
| TR6.3 | NFR-3 CPD 0 实质重复（TOK_WINDOW=160） | join 三文件 + DHQC.java CPD 扫描；去 import 行 + 去公共 Javadoc 注释模板；dup≤1；与 P30 Parser 8 类无 dup（token 窗口 160 不跨子包重复） | dup ≤1 + 实质重复段 count=0 |
| TR6.4 | NFR-4 裸 System.out = 0 + IAE/NPE ≥ 8 | grep -E "System\.out\.print" join 子包 3 文件 count=0；Fail-First 入口（三重载 × IAE 处 + resolve 内部）IAE/NPE 断言数 ≥ 8（每 Objects.requireNonNull 带 message 计数） | 0 println + count≥8 |
| TR6.5 | NFR-5 900 次 Recall SubSet + Score 全等 + 三键排序 | 6 场景（单 Join / 多 Join / 加权 / LTR / @DsOneToOne 反射 / EXISTS fallback）× 150 次 = 900；① P31 docIds ⊆ P29 baseline ids；② 交集 docId score |diff| ≤1e-9；③ 三键排序 1000×rank long[] 全等 | 900/900 SubSet + 1e-9 + 排序全等 |

---

### Gate C 洁癖 5/5 同步清单占位（Implement + Gate B×2 完成后填 [x]，当前 [ ]）
1. [ ] Spec.md 顶部 Gate A/B/C 三 [x] + Gate C 签批区（红线审计 + 9Y + NFR + ZeroLeak3 + 5份清单）
2. [ ] Tasks.md（本文件）顶部 Gate A/Implement/Gate B/Gate C 四 [x] + TR 实锤值 + 9Y×9 实锤表
3. [ ] Roadmap `docs/dsdb-design-status-and-roadmap.md` OQ12-⑨ ⏳→✅ 交付详情 + OQ12-⑩ ⏳ P32 候选项挂出
4. [ ] Project Memory `project_memory.md` 末尾 append-only 追加 P31 交付上下文 10 行
5. [ ] 红线不动审计：`git diff --name-only HEAD -- p2p-db/src/main/java/com/q3lives/ds/database/fulltext/` 输出空（0 文件 modified）
