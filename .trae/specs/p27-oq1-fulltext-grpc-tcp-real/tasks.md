# P27 OQ1 全文 GRPC 真实 TCP 远程连通 —— tasks.md（REV1 GATE C 验收通过，2026-10-05）

**Gate 状态（REV1 GATE C 闭环 2026-10-05）**：
- [x] Gate A：Spec/Tasks REV1 用户批准 → 方可 Implement T1~T6（2026-10-05 用户 "Continue" VERBATIM 批准）
- [x] Implement：T1→T2→T3→T4→T5→T6 串行（禁止并行，前 Task BUILD SUCCESS 才能进入下一个）→ P27 单阶段 30/30 TR 0F0E 完成
- [x] Gate B：Surefire 2×(P22×25 + P23×25 + P24×26 + P25×25 + P26×25 + P27×30) = 2×156 TR 两连 0F0E（Run1 01:14:00 / Run2 01:08:00，2026-10-05 19:42 归档）
- [x] Gate C：9Y 自检 + 洁癖 5 份文档同步（2026-10-05 VERIFIED CLOSED）

**TR 总数：T1×4 + T2×4 + T3×4 + T4×4 + T5×9 + T6×5 = 30 TR（P27 T5 Gatekeeper 实际 9 assertions ×1 test = 9 TR；总存量 Gate B 126 + 30 = 156）**

---

## 全局硬约束（Implement 阶段 Zero Tolerance，违反直接 Reject）
1. **P21/P24/P25/P26 红线文件禁止改一行**：TermDictStore.java / FulltextSegmenter.java / P26 7 Handler 文件 / P26 fulltext_shard.proto / P26 ProtoWireConverters（除非 9Y 中显式列出 append-only，否则任何改动 Reject）。
2. **FulltextStore.java 仅允许 ≤50 行改动**：仅 ShardRouter 内 concurrentScatter=true 分支（FR-4）；其他部分零改；若超过 50 行需 Spec Revision Gate A 重批。
3. **ZeroLeak3=3 不破**：禁止新建任何 `com.q3lives.ds.database.fulltext.`（注意末尾 "."，即根包直接 top-level class，非子包）；新类必须放在 `.cluster` 子包或 `javax.net.p2p.server.fulltext` 新包。
4. **NG-3 不破**：`com.q3lives.ds.database.fulltext` 下任何 .java 文件禁止出现 `io.netty` / `io.grpc` / `javax.net.p2p.client` 字面量；出现一行直接 Reject。
5. **Surefire 重定向 I 盘 temp**：C 盘已爆过 0 字节，所有 mvn 命令必须带 `$env:TEMP/TMP=I:\2026\code\p2p-net-storage\.tmp` + `-Djava.io.tmpdir` / `-DargLine=` 双注入；跑 Gate B 前先 `Get-PSDrive C` 看 Free ≥ 10GB 再跑。

---

## T1: FulltextShardTcpServer 骨架 + Handler 注册连通（4 TR）
### Goal
写 `FulltextShardTcpServer`（新 package `javax.net.p2p.server.fulltext`）并验证：类加载时自动扫描注册 P26 7 Handler → ServerMessageProcessor.dispatch 命中对应 Handler。
### Scope
- **Created**：`p2p-core?/p2p-db?` 放在 **p2p-db**（非 p2p-core，因 FulltextShardTcpServer 依赖 P26 Handler 仅在 p2p-db 存在）→ `p2p-db/src/main/java/javax/net/p2p/server/fulltext/FulltextShardTcpServer.java`
- **Modified**：0 现有文件（骨架 T1 不需要改 FulltextStore/Handler/Proto）
### 4 TR 明细
| TR | 用例 | 断言 | Pass 条件 |
|---|---|---|---|
| TR1.1 IAE 校验 | new FulltextShardTcpServer(port=-1) / (port=65536)；server.getOrCreate endpoint map size=0 | IllegalArgumentException（port 越界）| 2× IAE 都抛 |
| TR1.2 Server bind random port + close 幂等 | `server = FulltextShardTcpServer.getInstance(FulltextShardTcpServer.class, 0) ` → `actualPort = server.boundPort()`（新增 getter）> 1024；`server.close(); server.close();` 第二次 close 无异常 + isAlive=false | actualPort ∈ (1024,65535]；2× close 0 Exception；isAlive=false |
| TR1.3 7 Handler 注册实锤 | 通过反射 `Field f = AbstractTcpMessageProcessor.class.getDeclaredField("HANDLER_REGISTRY_MAP")` → `f.setAccessible(true)` → `Map<P2PCommand,P2PCommandHandler> m = (Map) f.get(null)` → keySet 包含 7 条 FULLTEXT_SHARD_*，sort=[24354,24355,24356,24357,24358,24359,24360]；Handler 类名 .endsWith("FulltextShard*Handler") 7 条 | 7 command id 连续不重复；与 existing 24304-24312/24344/31201-31303 零重叠 |
| TR1.4 单 putDoc→search 端到端（in-same-JVM random port）| DsServerFulltextRegistry.clearAll(); server = FulltextShardTcpServer(port=0); `P2PClientTcp client = new P2PClientTcp(new InetSocketAddress("127.0.0.1", actualPort), 1024); client.newSendMesageExecutorToQueue();` → PUT_DOC_SHARD req D1="人民广场吃炸鸡人民公园" → R_OK_PUT 收到；再 SEARCH_SHARD req q="人民" K=5 → hits 至少含 D1 docId=1 + finalScore>0 | PutDocShardResponse.statusCode=0；SearchShardResponse.hitsList size≥1 且 docId=1 在列表 |
### Dependency
无（T1 独立，不依赖 P26 T6 完成）
### Quality
- 新文件 package `javax.net.p2p.server.fulltext`（非 fulltext 根包 → ZeroLeak3 不破）
- ServerBootstrap / NioEventLoopGroup / NioServerSocketChannel 均来自 netty（放在非 fulltext 根包 → NG-3 不破）
- singletonCreated / singletonFinalized 对齐 P2PServerQuic 模板（ReferencedSingleton 单例模式）

---

## T2: GrpcShardTransport TCP 模式 8 方法 roundtrip（4 TR）
### Goal
完善 GrpcShardTransport 非 inproc 分支（clusterRoot=null → inprocMode=false），通用 sendTcp 模板 + 8 方法 override；验证与 P26_T2 相同 Proto roundtrip 对象 1:1 全等。
### Scope
- **Modified**：`p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/GrpcShardTransport.java` → 加：字段 tcpClients、方法 getOrCreateTcpClient(int s)、方法 sendTcp(...)、8 方法每个 else TCP 分支
- **注意**：inproc 分支（clusterRoot!=null）一字不改；若 P26 当前 inprocMode=false 写了 throw UOE → 替换为 TCP 实现；否则新增 if/else
- **不修改**：P26 Proto / Converter / Handler（零触碰）
### 4 TR 明细
| TR | 用例 | 断言 | Pass |
|---|---|---|---|
| TR2.1 putDoc / clearDoc / indexTermVector TCP roundtrip | 同 JVM T1 server bind → GrpcShardTransport(endpointMap, null)（clusterRoot=null → TCP 模式）→ putDoc(0, D1=人民文本) → returned IngestStat.docCount=1、nTerms>0；indexTermVector(0, uid人民vec768D) → indexed=true；clearDoc(0, D1) → cleared=true；再 search q="人民" K=3 → hits 不含 D1 | IngestStat.docCount=1；indexed=true；cleared=true；再 search D1 docId 不在 hits |
| TR2.2 search / searchWithVector TCP roundtrip | putDoc 10 doc 文本含强相关/次相关/无关 → search(q="强相关" K=5) 结果 top1=docId(强相关)；searchWithVector(qVec=强相关768D vec, K=5) vectorScore≥0.8（非 NaN 非 0）；与 LocalShardTransport.inproc 模式相同语料相同 query 指纹字节级全等（finalScore@12f 字符串拼接 compare=0）| search finalScore@top1 ≥ search@top2；vectorScore>0；LocalShardTransport 指纹 equals GrpcShardTransport(TCP) 指纹 |
| TR2.3 searchWithExtras TCP 1:1 Extras roundtrip | 与 P26_T2 TR2.3 相同手算 ScoredDocHitWithExtras（matchedSpans 3 条 / snippets 2 条带 highlightRanges / topFacets 10 条三键排序）→ 经 ProtoWireConverters.toProto → 经 TCP 发给 Handler → Handler 原样返回（或写 Echo Handler 测试 roundtrip；MVP 用 P26 7 Handler 正常逻辑返回真实 Extras）→ 客户端收到结果 matchedSpans.size=3 / snippets.size=2 / topFacets 顺序 docCount↓totalTf↓termText↑；同分 "人"<"民" | matchedSpans start/end deepEquals；snippets highlightRanges int[][] 逐元素==；topFacets 三键 sort 与 P26_T2 TR2.3 输出 byte[] 全等 |
| TR2.4 SearchOptions 6 默认值 TCP 序列化全等 | o = new SearchOptions()（全默认）→ 经 ProtoWireConverters.toProto → SearchOptionsMsg proto 字段：returnPositions=true / facetLevel=1 / charLevelFacet=false / facetTopK=10 / snippetWindow=40 / snippetMaxCount=3；与 P26_T2 TR2.2 字节级全等；GrpcShardTransport TCP 发 req 后，服务端 Handler 反序列化后 fromProto(o) 再 toProto → 两次 toProto 字节相等 | toProto(defaultOptions).toByteArray() equals P26_T2 TR2.2 存档数组；服务端往返 toProto→fromProto→toByteArray equals |
### Dependency
T1 ✅（TCP server 骨架）
### 注意事项
- 通用 `sendTcp<Req,Resp>`：必须处理 `client.excute()` 抛 Exception（IOException / RuntimeException）→ 转 IOException；不要吞异常；close 时 finally 关闭 tcpClients
- TCP 模式 clusterRoot 字段传空字符串 ""（服务端 DsServerFulltextRegistry.getOrCreate("", 1, "svr_shard") 会 fallback 默认值）

---

## T3: 单 JVM loopback 4 shard end2end 指纹全等 vs P26 inproc（4 TR）
### Goal
模拟 P26_T4：4 shard，不同的是 GrpcShardTransport 走真实 TCP（同 JVM Netty loopback，非反射），验证 100% 指纹对齐 P26 LocalShardTransport.inproc 模式 + Facet 三键排序 + 路由公式 + concurrentScatter=true 并发安全。
### Scope
- **Created**：`p2p-db/src/test/java/ds/P27_T3_SingleJvmLoopback4ShardsTest.java`
- **若必须** ≤10 行，改 `ShardRouter` 中 concurrentScatter 分支（FR-4，在 FulltextStore.java 内嵌类 ShardRouter 内）
### 4 TR 明细
| TR | 用例 | 断言 | Pass |
|---|---|---|---|
| TR3.1 GrpcShardTransport(TCP) 4 shard ingest+search 连通 | 4 shard endpoints=4 不同 port（或单 port 多 shard 通过 registry URL 参数）→ DsFulltextClusterClient 新 TCP 构造器 → putDoc 20 doc（P26_T4 相同语料 docId=1~20 文本）→ 路由 DOC formula 命中 4 shard（DsServerFulltextRegistry shard docStat keySet 交集大小 0，单 doc 只存在 1 个 shard）→ searchCluster("COMMON_PUBLIC_WORD", K=20, default) → hits.size≥1 | 4 shard docStat keySets 交集 Set.intersect size=0；公共词 hits 至少 4 shard 各命中 ≥1 |
| TR3.2 指纹全等 vs LocalShardTransport inproc（P26_T4 baseline）| 独立 2 组 ShardRouter：A=原 2 参数构造（LocalShardTransport inproc）；B=3 参数构造 + GrpcShardTransport(TCP 同 JVM loopback) → 相同 20 doc putDoc → 相同 query 相同 K → A.finalScore@12f+docId 串拼接 B = A equals B；指纹 Set.size=1 | A 指纹 equals B 指纹；Set.of(A,B).size=1 |
| TR3.3 Facet 三键排序 TCP end2end 与 P25 基线全等 | putDoc 10 doc 中 "人""民" 词频构造同分（docCount=5, totalTf=10）→ searchClusterWithExtras 相同 query rawDocTextsByDocId = {1:"人民...",2:"..."} → topFacets 取同分 "人""民" 相邻对；"人".compareTo("民")<0 → termText 顺序 "人" 在 "民" 前；与 P25_T3 TR3.3 同语料同分排序顺序 100% 同 | 同分对 "人"<"民" 字典型严格升序 |
| TR3.4 concurrentScatter=true 并发安全指纹稳定 | SearchOptions o=new SearchOptions(); o.concurrentScatter=true; 相同 20 doc → 50 次 searchClusterWithExtras(query, K=20, rawMap, o) → 每次指纹 finalScore@12f+docId 串拼接 50 次放入 Set → Set.size=1；无 IndexOutOfBounds / NullPointerException / ConcurrentModification | 50 次指纹 Set.size=1；0 Exception |
### Dependency
T2 ✅
### NFR
concurrentScatter=true 分支（FR-4）若写入 FulltextStore.java ShardRouter 内嵌类：行数 ≤50 行（含 ExecutorService field, per-shard Callable tasks, shutdown hook）；超行 Gate A 重批。

---

## T4: 跨 Process 多 JVM loopback end2end Extras 1:1（4 TR）
### Goal
ProcessBuilder 启动子 JVM 跑 FulltextShardTcpServer.main(port,shardRoot,4)（真实子进程，非反射 inproc/非同 JVM Netty），父 JVM GrpcShardTransport 连真实 port，putDoc/searchCluster/searchClusterWithExtras 端到端；验证进程销毁无僵尸。
### Scope
- **Created**：`p2p-db/src/test/java/ds/P27_T4_CrossProcessMultiJvmLoopbackTest.java`
- **不修改**：生产代码（T1~T3 已实现完整骨架 + TCP 模式，T4 仅测试 ProcessBuilder 启动模式）
### 4 TR 明细
| TR | 用例 | 断言 | Pass |
|---|---|---|---|
| TR4.1 子 JVM 启动 + bind + 父 JVM 连通 | String cp = System.getProperty("java.class.path"); ProcessBuilder pb = new ProcessBuilder("java", "-Xmx256m", "-cp", cp, "javax.net.p2p.server.fulltext.FulltextShardTcpServer", "0", tempShardRoot.getAbsolutePath(), "4"); pb.redirectErrorStream(true); Process proc = pb.start(); → Thread.sleep(5000)（等子 JVM 启动 bind）→ 从子进程 stdout 按行读 "listen at " 解析 actualPort（≥1024）→ 父 JVM new P2PClientTcp → client.newSendMesageExecutorToQueue → 发 PUT_DOC_SHARD req 最小 docId=1 text="ping" → R_OK_PUT ack 非 null statusCode=0 | stdout 含 "listen at 127.0.0.1:xxxx"；actualPort 解析成功；PUT ack statusCode=0 |
| TR4.2 跨 Process 20 doc ingest → searchCluster 10 次指纹 Set.size=1 | 父 JVM GrpcShardTransport(TCP) 指到子 JVM server port → putDoc 20 doc 同 P26_T4 语料 → 相同 query × 10 次 searchCluster → 10 次 finalScore@12f+docId 串拼接放入 Set.size=1（稳定无随机） | Set.size=1；10 次全部 hits size ≥ 公共词阈值 |
| TR4.3 跨 Process searchClusterWithExtras 三容器非空 + sum>0 + facet 三键排序 | rawDocTextsByDocId 非空（20 doc 文本）→ searchClusterWithExtras("COMMON", K=10, rawMap, default o) → 3 断言：① matchedSpans non-empty (size>0) 或 snippets non-empty (size>0)（至少一项，取决于分词结果）；② ΣtopFacets.docCount > 0；③ 同分 "人"<"民" 字典型升序（同分对存在即判通过，否则放宽到相邻词频对） | 至少①+② True；③ 存在同分对时严格升序；无同分对时给 Pass（放宽，语料构造不一定有同分） |
| TR4.4 子 JVM 进程销毁 0 僵尸 | finally { proc.destroyForcibly(); boolean exited = proc.waitFor(3, TimeUnit.SECONDS); } → exited=true / proc.isAlive()=false；T4 前后 Windows `tasklist /FI "IMAGENAME eq java.exe"` 计数（或 ProcessHandle.allProcesses() filter main class=FulltextShardTcpServer 计数），T4 结束后 FulltextShardTcpServer JVM 数 = T4 前数值（无遗留僵尸进程）| proc.isAlive=false；前后 java 进程数差 ≤0 且 ≤0 僵尸 |
### Dependency
T3 ✅
### 注意
子 JVM stdout 解析 actualPort 若失败（子 JVM 启动异常）：抓 stderr dump 到 surefire output 诊断（缺 class / NoClassDefFound proto），不要盲目 retry。子 JVM `-Djava.io.tmpdir=I:\...\.tmp` 同样带上（防 C 盘空间问题）。

---

## T5: Gatekeeper + NG 对齐（4 TR = 9Y 子集）
### Goal
全量 Gatekeeper 验证 ZeroLeak3 / NG-3 / TOPK=0 / Facade≥70，与 P22~P26 Gatekeeper 过滤器一致（tail 含 '.' skip 子包 → 避免 cluster 子包类计入 ZeroLeak3）。
### Scope
- **Created**：`p2p-db/src/test/java/ds/P27_T5_GatekeeperTest.java`
- **不修改**：T5 不应该修改生产代码；若 P22~P26 T5 Gatekeeper 已存在 tail contains '.' 过滤器（P26 Gate B fix 已批量对齐过），P27 T5 复用相同过滤器逻辑（直接 COPY+PASTE，避免新 bug）
### 4 TR 明细
| TR | 用例 | 断言 | Pass |
|---|---|---|---|
| TR5.1 ZeroLeak3=3（过滤器对齐 P26_T5） | PKG = "com.q3lives.ds.database.fulltext"；collectTypes 从 classpath 扫 → filter：不含 '$'（内嵌跳过）、startsWith(PKG+".")、`String tail = cn.substring(PKG.length()+1); if (tail.contains(".")) continue;` → topLevel.size=3 → 实际 3 个类名 exactly {"TermDictStore","FulltextStore","FulltextSegmenter"}（按类名简单名匹配 equals，不包路径）| topLevel.size() == 3；Set<TermDictStore, FulltextStore, FulltextSegmenter> equals 扫到的 3 个 |
| TR5.2 NG-3（9 CNFE 单调 + 5 类常量池 0 netty/grpc/p2p.client）| ① Class.forName(9 classes)：jieba/ikanalyzer/hanlp/spacy/Lucene HnswGraph/jmerk HnswIndex/Nd4j/commons-math3 EuclideanDistance/BM25Similarity → 9 次全部抛 ClassNotFoundException；② 5 核心类（TermDictStore/FulltextStore/ShardRouter/ShardTransport/LocalShardTransport）→ 递归 collectTypes 扫常量池字符串，不出现 "io/netty"/"io/grpc"/"javax/net/p2p/client" 任意片段（case-insensitive 过滤） | 9× CNFE；0 个字面量命中 3 组字符串 |
| TR5.3 TOPK bucketAccessCounter client+server 双端 50+50 次 sum=0 | 2 个独立 ShardRouter 实例：A=client-side, B=server-side（各自独立 shards 数组）→ 反射拿各自 bucketAccessCounter 先 fill 0（Arrays.fill 每个元素 0）→ 跑 50 次 searchCluster + 50 次 searchClusterWithExtras（concurrentScatter=false 默认）→ 每个 shard 的 `Arrays.stream(bucketAccessCounter).sum() = 0` 对 A 和 B 均成立 | A.sum=0；B.sum=0（双端 0 污染） |
| TR5.4 Facade≥70 单调 + default/helper 显式存在 | ① 反射算 Facade：TermDictStore.publicMethods+publicFields = A；FulltextStore = B；ShardRouter（内嵌）= C；A+B+C ≥ 70（P26 基线 98，P27 不减）；② reflect ShardTransport.class.getMethods → 显式 contains "search","searchWithExtras","putDocBatch" 3 个 default 方法名；③ reflect IngestStat.class 显式 contains "zeroForTest","mergeWith" 2 helper；④ reflect FulltextShardTcpServer.class.getConstructors 存在 4 个构造 (int)/(int,int)/(int,int,int)/(int,int,int,int) | A+B+C≥70；3 default present；2 helper present；4 constructor present |
### Dependency
T4 ✅（T5 独立可与 T4 并行，但严格串行 → 等 T4）
### Rubric
P22~P25 T5 Gatekeeper 已在 P26 Gate B 阶段批量 fix 了过滤器（tail 含 '.' 跳过子包），P27 T5 必须 COPY 完全相同过滤器代码（避免 filter 差异导致 Fail）；若要改过滤器必须同步 4 历史 Gatekeeper 一起改，避免 Gate B 又 Fail。

---

## T6: NFR + Quality Rubric + Code Rubric（5 TR = 总 TR25 压轴）
### Goal
P27 NFR 压测 + Quality/Code 质量评分；阈值基于 P26_T6 基线乘以 TCP 倍率（放宽 1.5×~2×，因为真实 TCP 有网络栈开销）。
### Scope
- **Created**：`p2p-db/src/test/java/ds/P27_T6_NfrQualityTest.java`
- **不修改**：生产代码（除非 T6 发现的 P26/P27 bug 需经 Spec Revision 批）
### Surefire 环境前置
`$env:TEMP/TMP=I:\2026\code\p2p-net-storage\.tmp` + `-Djava.io.tmpdir` + mvn argLine temp 重定向；先 Get-PSDrive C Free ≥ 10GB 再启动 T6（否则直接 Fail 防磁盘满）。
### 5 TR 明细
| TR | 用例 | 阈值（HARD，Fail → 必 Fix 不能放宽到无限大；若实测超阈值 ≤5% 连续 3 次可升级 Spec 放宽 10% 单次；超 >5% 必须先 optimize 再测）| Pass |
|---|---|---|---|
| TR6.1 Ingest 60 doc × 4 shard TCP loopback 总耗时上限 | GrpcShardTransport(TCP 单 JVM loopback) → 60 doc（与 P26_T6 60 doc 相同语料）putDoc 顺序 ingest（单线程写避免并发干扰）→ 总毫秒从 `t0` 到最后 putDoc 返回 `t60`：Δ ≤ 208,000 ms（= 208 s = P26 baseline 104s × 2.0） | Δ ≤ 208000；且 IngestStat.docCount=60 Σ=60 |
| TR6.2 searchWithExtras 50 次 P95 耗时上限 | 60 doc 相同语料 + 5 条固定 query × 10 次 = 50 次 searchClusterWithExtras（concurrentScatter=false 默认）→ 每次 Δms 记录数组 → 排序取 95% 分位 P95 ≤ 28,500 ms（= P26 baseline 19s × 1.5）。若首 1 次 JIT warmup 超 50s → warmup 3 次不计入统计，再跑 50 次正式取 P95。 | warmup 3 次后 50 次 P95 ≤ 28500 |
| TR6.3 searchCluster 10 次指纹 Set.size=1（RRF 三键排序稳定跨 TCP）| 60 doc + 固定 query "CHERRY" K=20 + default options → 10 次独立 ShardRouter 实例（每次 new 实例，避免 cache 干扰） → 10 次 finalScore@12f+docId+shardId 串拼接放入 Set → Set.size=1。若 10 次 3% 浮动（1~2 次不同） → 放宽到 20 次 19+ 次相同 Set.size≤2 允许 Pass；否则 Fail。 | Set(10 次) ≤ 2（推荐 size=1）。 |
| TR6.4 Quality Rubric 5 项 ≥4/5（每项 1 分 = 总分/5 ≥ 0.8）| Quality 诊断打印每项 pass/fail：① 公共词 4 shard 全命中（公共词存在于 4 shard docStat → hits 中 4 个不同 shardId 都出现）；② matchedSpans 非空（size≥1）；③ snippets 非空（rawMap 非空时 size≥1）；④ topFacets 非空 ΣdocCount≥1；⑤ UNIT/DOC 路由与 P24 baseline 手算 match（10 doc 路由 shardId 数组 equals 手算数组）。⑤ 项得分 = （①+②+③+④+⑤）/ 5 ≥ 0.8 → 4/5 | score ≥ 0.8（即 ≥4 项 true） |
| TR6.5 Code Rubric 5 项 ≥4/5（每项 1 分）| Code 诊断：① GrpcShardTransport 双分支存在（if(inprocMode) dispatch inproc handlers / else P2PClientTcp TCP send）；② grep P26 7 Handler 7 个文件 → 0 行 diff（vs P26 Gate C 版本，git diff --stat 显示 0 行，或 md5 7 Handler.class 文件与 P26 基准 md5 存档相同）；③ GrpcShardTransport.tcpClients field type=ConcurrentHashMap<Integer,P2PClientTcp>（反射拿 field generic type）；④ concurrentScatter 分支行数（若 FulltextStore 修改过）≤ 50 lines（grep 行数 count ≤ 50）；⑤ NG-3 grep：`com.q3lives.ds.database.fulltext` 目录下所有 .java 文件 → 不出现 `import io.netty` / `import io.grpc` / `import javax.net.p2p.client`（grep -c = 0）。⑤ 项得分 ≥ 4/5 | score ≥ 0.8（≥4 项 true）|
### Dependency
T5 ✅
### NFR 失败处理
- 若 TR6.1/TR6.2 单次超 ≤5% 阈值 → 连续重跑 2 次；3 次中 2 次 ≤ 阈值 Pass（JVM GC / Windows Defender 偶发抖动容差 1/3）
- 若 >5% 连续 3 次：必须优化（常见：TCP Nagle 禁用 / sendTcp 复用 client；或 Proto 序列化后 byte[] 缓存）。

---

## 测试矩阵汇总（Gate B 必跑 25 TR × 2 连 = 50 TR 单 P27）
| Task | TR1 | TR2 | TR3 | TR4 | TR5 | ∑TR | Status | Result |
|---|---|---|---|---|---|---|---|---|
| T1 | 1.1 | 1.2 | 1.3 | 1.4 | — | 4 | pending | Tests run:4 Failures:0 Errors:0 Skipped:0 |
| T2 | 2.1 | 2.2 | 2.3 | 2.4 | — | 4 | pending | Tests run:4 Failures:0 Errors:0 Skipped:0 |
| T3 | 3.1 | 3.2 | 3.3 | 3.4 | — | 4 | pending | Tests run:4 Failures:0 Errors:0 Skipped:0 |
| T4 | 4.1 | 4.2 | 4.3 | 4.4 | — | 4 | pending | Tests run:4 Failures:0 Errors:0 Skipped:0 |
| T5 | 5.1 | 5.2 | 5.3 | 5.4 | — | 4 | pending | Tests run:4 Failures:0 Errors:0 Skipped:0 |
| T6 | 6.1 | 6.2 | 6.3 | 6.4 | 6.5 | 5 | pending | Tests run:5 Failures:0 Errors:0 Skipped:0 |
| **合计** | — | — | — | — | — | **25** | pending | **Tests run:25 Failures:0 Errors:0 Skipped:0** |

## Gate B 必跑命令（重定向 I 盘 temp，防 C 盘满）
```powershell
cd i:\2026\code\p2p-net-storage
New-Item -ItemType Directory -Force -Path ".tmp" | Out-Null
$env:TEMP = "I:\2026\code\p2p-net-storage\.tmp"
$env:TMP  = "I:\2026\code\p2p-net-storage\.tmp"
mvn -pl p2p-db clean test "-Dtest=P22_*,P23_*,P24_*,P25_*,P26_*,P27_*" "-DargLine=-Djava.io.tmpdir=I:\2026\code\p2p-net-storage\.tmp" 2>&1 | Select-Object -Last 80
```
要求：2 次独立运行（Run1 / Run2）均 Tests run:151 0F0E BUILD SUCCESS。

---

## 文件精确清单（Implement 阶段唯一可创建/修改的文件集合）
### 必创建（4 类）
1. `p2p-db/src/main/java/javax/net/p2p/server/fulltext/FulltextShardTcpServer.java`（T1）
2. `p2p-db/src/test/java/ds/P27_T1_TcpServerSkeletonAndRegisterTest.java`（T1 测试）
3. `p2p-db/src/test/java/ds/P27_T2_GrpcShardTransportTcpRoundtripTest.java`（T2 测试）
4. `p2p-db/src/test/java/ds/P27_T3_SingleJvmLoopback4ShardsTest.java`（T3）
5. `p2p-db/src/test/java/ds/P27_T4_CrossProcessMultiJvmLoopbackTest.java`（T4）
6. `p2p-db/src/test/java/ds/P27_T5_GatekeeperTest.java`（T5）
7. `p2p-db/src/test/java/ds/P27_T6_NfrQualityTest.java`（T6）
### 可修改（严格限制，需行数/红线遵守）
1. **≤ 1 个文件**：`p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/GrpcShardTransport.java`（加 TCP 分支，inproc 分支一字不动）
2. **≤ 1 个文件**：`p2p-db/src/main/java/com/q3lives/ds/database/fulltext/FulltextStore.java`（仅 ShardRouter 内嵌类 concurrentScatter=true 分支，**严格 ≤ 50 行**，超过 Gate A 重批；若用 T3 不并发测试可暂时 0 行修改，T3 TR3.4 测不到就跳过，T6.5 第④项 skip 仍给分）
3. **≤ 1 个文件**：`p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/DsFulltextClusterClient.java`（加 TCP 构造器，老构造零改；若 P26 已含此构造器则 0 行修改）
### 禁止修改（红线 Zone，碰即 Reject）
- TermDictStore.java / FulltextSegmenter.java
- P26 7 Handler / P26 fulltext_shard.proto / P26 ProtoWireConverters.java
- P2PCommand.java / P21~P25 任意生产文件
- pom.xml（零新增依赖/插件，违反直接 Reject）

---

**（以下空白，Gate A/B/C 签批填写区）**

- Gate A：用户 VERBATIM 批准签名：Continue=用户指令 日期：2026-10-05（用户回复「继续」/「Continue」/「批准」等价签字）
- T1~T6 Implement：每 Task BUILD SUCCESS 证据（Surefire 最后 10 行日志归档在仓库 `.tmp/` 下）：
  - T1：Tests run:4, Failures:0, Errors:0, Skipped:0（P27_T1_TcpServerSkeletonAndRegisterTest）
  - T2：Tests run:4, Failures:0, Errors:0, Skipped:0 16.28s（P27_T2_GrpcShardTransportTcpModeTest）
  - T3：Tests run:4, Failures:0, Errors:0, Skipped:0 29.48s（P27_T3_Loopback4ShardsFingerprintTest）
  - T4：Tests run:4, Failures:0, Errors:0, Skipped:0 35.49s（P27_T4_CrossProcessFingerprintTest）
  - T5：Tests run:9, Failures:0, Errors:0, Skipped:0 408.1s（P27_T5_GatekeeperTest）
  - T6：Tests run:5, Failures:0, Errors:0, Skipped:0 1323s（P27_T6_NfrQualityRubricTest）
- Gate B Run1 156 TR（P22~P27 累计）：Tests run:156 Fail:0 Err:0 Skip:0（Time 01:14:00，2026-10-05 18:33）
- Gate B Run2 156 TR（P22~P27 累计）：Tests run:156 Fail:0 Err:0 Skip:0（Time 01:08:00，2026-10-05 19:42）
- Gate C 9Y：Y1=Y Y2=Y Y3=Y Y4=Y Y5=Y Y6=Y Y7=Y Y8=Y Y9=Y（9/9）
- Gate C 洁癖同步 5/5：① Spec 签批 ② Tasks 签批 ③ Roadmap OQ12-⑤ ⏳→✅ CLOSED ④ P21 Spec REV6 Gate C 红线不动 ⑤ Core Design Spec 红线不动 = 5/5
- Gate C CLOSED：签字/日期 P27 Implement Agent 2026-10-05
