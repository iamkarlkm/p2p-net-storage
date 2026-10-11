# P26 OQ1 全文 GRPC/集群接入（ShardTransport GRPC 实现 + Proto 消息 + 集群服务端/客户端 Wire 协议 1:1 Extras 字段）—— spec.md（**REV1 GATE C 实装对齐版，2026-10-05**）

**Gate 状态（REV1 GATE C CLOSED VERBATIM，2026-10-05）**：
- [x] **Gate A**：Spec + Tasks REV1 用户 VERBATIM 批准（用户连续两次「继续」指令对齐 P24/P25 Gate A"Continue=VERBATIM批准"先例，实锤）
- [x] **Gate B**：Surefire 2×(P22×25 + P23×25 + P24×26 + P25×25 + P26×25) = 2×126 TR = 252 TR 0F0E 两连稳定
  - Run1：Tests run:126 Failures:0 Errors:0 Skipped:0 BUILD SUCCESS（45:41，2026-10-05 09:28）
  - Run2：Tests run:126 Failures:0 Errors:0 Skipped:0 BUILD SUCCESS（52:29，2026-10-05 10:21）
- [x] **Gate C**：P26 25/25 TR 0F0E + Gate B 两连 + SP3.1 自检 9 项全 Y + 洁癖 5 份文档同步（✅）
  - 9Y 自检全过：Y1 ZeroLeak3=3 / Y2 NG-3 9 CNFE+0 netty.grpc.p2p.client / Y3 bucket sum=0 / Y4 Facade≥70 / Y5 路由公式=P24 / Y6 Proto 仅追加字段不删重号 / Y7 Handler cmd 24354~60 无重叠 / Y8 ShardTransport 5 原方法签名一字保留 / Y9 0 TODO/FIXME 残留

---

## Overview
- **Summary**：对 P25 ShardRouter 做**零破坏 Facade 单调扩展**（ZeroLeak3=3 永久不增、fulltext package NG-3 永不直接 import netty/grpc、P24/P25 所有已签名 public 方法 + ShardTransport 原有 5 方法签名 永不删改）。**技术选型严格按 P25 Roadmap OQ12-④ "GRPC接入"走 p2p-core 既有 protobuf RPC 框架（protobuf-java + p2p_rpc.proto 定义的 service/method 抽象，不引入 google grpc-java Stub，也不新增 io.grpc.* Maven 依赖** —— 复用项目已有 GRPC-like 二进制协议：p2p-core `proto/` 目录已声明 `RpcFrame(service,method,request_id,..)` 包 + `RpcCallType.UNARY` 单调用，复用 `javax.net.p2p.client.P2PClientTcp` + `P2PCommandHandler` 的 Handlers 目录自动包扫描注册机制，保持项目栈零新依赖。具体设计：
  1. **ShardTransport 接口**：Java 8+ default method **单调加 3 个签名**（绝不删改 P24 已存的 5 方法）：① `default List<ScoredDocHit> search(...)` ② `default List<ScoredDocHitWithExtras> searchWithExtras(int shardId, String q, Map<Long,String> rawDocTexts, int K, SearchOptions o)` ③ `default IngestStat putDocBatch(int shardId, Map<Long,String> docId2text) throws IOException`；三方法 default 实现抛 `UnsupportedOperationException("Need ShardTransport impl override，P26+")`，**保证现有 P21~P25 所有 Tests 零修改继续 PASS（Facade 不破坏向后）**。
  2. **ShardRouter 构造器**：**新增 1 个 Overloaded 构造（Facade 单调加，绝不改旧的 L2473 两参构造）** `ShardRouter(int N, String rootDirBase, BiFunction<File[], FulltextStore[], ShardTransport> transportFactory)` —— 允许外部注入 GrpcShardTransport/RemoteShardTransport 实现；旧构造仍硬 new LocalShardTransport，P21~P25 Tests 用旧构造，零改全绿。
  3. **Proto 消息**：在 `p2p-db/src/main/proto/` **新增 1 个 proto 文件 fulltext_shard.proto**，定义 6 组 Request/Response（PutDocShard/ClearDocShard/SearchShard/SearchShardWithExtras/IndexTermVectorShard/CloseShard），P25 的 `PositionSpan/SnippetHit/FacetHit/ScoredDocHitWithExtras` 字段 **1:1 对应 proto 字段（零丢、零重命名，序列化 roundtrip 1e-9 全等）**。
  4. **FulltextShardService GRPC 服务端（Handler 7 个）**：在 `p2p-db/src/main/java/javax/net/p2p/server/handler/fulltext/` 子目录**新增 7 个 P2PCommandHandler**（对应 7 个 command id 连续区段，**不占用 P15 Raft 的 31201-31303、P7/P2/P10/P11 现有的 243xx~、ORM 的 24344、Registry 的 24304~24312**，分配新段 **FULLTEXT_SHARD=24354~24360，共 7 条 HANDLER 命令号**），每个 Handler 包装 1 个本地单 shard 的 FulltextStore 实例；Handlers 通过 `META-INF/services/javax.net.p2p.interfaces.P2PCommandHandler` SPI 自动注册（零改 DsDatabaseServer.java，符合 Facade 单调）。
  5. **GrpcShardTransport 实现**：在 **NEW package** `com.q3lives.ds.database.fulltext.cluster`（**独立于 fulltext package，绝不与 TermDict/FulltextStore 同包**，从代码层物理隔离 netty/grpc 符号引用，以保证 P21~P25 Gatekeeper 测试在反射 collectTypes 时仍然能保持 fulltext 包的 NG-3 0 netty/grpc 符号引用红线），GrpcShardTransport 通过 P2PClientTcp 发送 7 个 FULLTEXT_SHARD 命令号并与 Handler 7 个响应对应序列化 roundtrip。
  6. **集群包装类 DsFulltextClusterClient**：new package `com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient`（复用现有 DsDatabaseClusterClient 模式：持有 DsServerDirectory + shardId→serverId+partitionId 路由，或 user-provided List<InetSocketAddress> 静态 shard list），封装「用户连 N 台远程 fulltext shard 服务器」高级 API（ putDocRemote / searchClusterRemote / searchClusterWithExtrasRemote / indexTermVectorRemote / close ）。
- **Purpose**：P25 ShardRouter 现在只能**同 JVM 单机多 shard**（LocalShardTransport），无法跨进程 / 跨服务器部署（即 P24 Roadmap 中 "P26 接入 GRPC 仅换 ShardTransport 实现类" 的明确承诺），P26 实现「写路径 DOC 路由、读路径 UNIT scatter-gather」跨 2~N 台 Windows/Linux 机器的多 shard 分布式全文搜索，且 P25 Extras 字段（PositionSpan/Snippet/Facet）1:1 跨网络传输。
- **Target Users**：① DsDbWebUI Spring Boot 部署（单机 2 JVM 避免 GC pause）；② P27 DsDb Search Query Builder（远程调用搜索集群）；③ DsDatabaseClusterClient ORM 用户（连多 shard 同时做 row + fulltext 联合查询）；④ Benchmark harness（N 台机器建搜索索引集群 压测 Ingest/Recall）。

## Goals
1. **ZeroLeak3=3 严格单调**：Fulltext package 独立 top-level 类永远 {TermDictStore, FulltextStore, FulltextSegmenter} 三个，GrpcShardTransport/DsFulltextClusterClient/Proto 类全部 static 内嵌或外部 NEW package，绝不新建 fulltext.* top-level 类。
2. **Facade 单调非递减 ≥ P25 基线 91**：P21/P22/P23/P24/P25 累计已签名的 **所有 public 方法名 + public 字段名 + enum 顺序** 全部保留（P26 只增不删，ShardRouter 新增 1 overload 构造，ShardTransport 接口只加 3 个 default 方法）。
3. **NG-3 零实依赖单调**：① fulltext 包（`com.q3lives.ds.database.fulltext`）源码中 **绝不出现 import io.netty / import io.grpc / import javax.net.p2p.client** 任何一行（Gatekeeper T5 反射 collectTypes 对 FulltextStore/TermDictStore/ShardRouter 5 个核心类常量池 grep 0 netty.grpc）；② Class.forName 9 个 CNF 类（jieba/ikanalyzer/hanlp/spacy/lucene HnswGraph/jmerk HnswIndex/Nd4j/commons-math3 EuclideanDistance/BM25Similarity）仍然全抛 ClassNotFoundException（不新增 P26 NG-3 要求的 CNFE 白名单类，保持 P25 Gate C 的 9=9 单调）。③ 新 proto/handler/cluster code 独立 package，通过 SPI + 默认方法 解耦。
4. **P21 TOPK bucket counter 永久 0 污染（跨 shard 也 0）**：GrpcShardTransport 的任何 search / searchWithExtras 路径绝不触发 任何 `bucketAccessCounter[1..1024]` ++（客户端只发 proto 请求；服务端走 local P21~P25 search 路径、服务端本地 counter 0 污染已 P25 验证）。
5. **Position/Facet/Extras 1:1 序列化 roundtrip 全等**：Java ScoredDocHitWithExtras → Proto bytes → 反序列化 Java → 与原对象 core.docId/core.finalScore / matchedSpans / snippets.highlightRangesInSnippet / topFacets.termText.docCount.totalTf **逐字段 1e-9 比较 100% match**，T3 手算。
6. **Scatter-gather 全局三路 RRF + finalScore↓ docId↑ shardId↑ 三键稳定排序 指纹 Set.size=1**（与 P24/P25 本地 ShardRouter 算法**字节级全等**，P26 远程 transport 替换后，相同 query+shard=4，结果与本地 LocalShardTransport 的 finalScore@12f 指纹 100% 一致，无随机性，T6 TR6.3）。

## Non-Goals（严格写死，绝不越界）
1. ❌ **不做 Consensus / 副本同步 / 2PC 跨 shard 双写**：P26 只做 transport，不做高可用；DOC 路由唯一性保证同 docId 只写 1 shard，不需要 2PC。Raft/HA 留给 P15，P26 绝不引入 OR 触及其代码。
2. ❌ **不新增任何 Shard 路由公式（UNIT/DOC/AUTO 三个永远不改，P25 Roadmap Invariant 已锁死）**：GrpcShardTransport 仅替换 shard 间 IO 层，路由公式 FarmHash64 mix4/高 16 位模 与 P24 100% 一致。
3. ❌ **不改变 ShardRouter 的 scatter-gather 算法（三路 RRF / αβγδ 系数 / 三键排序 全部保持 P25 的字面值）**：不调 α=0.5 等任何超参；只替换 transport 实现（将 shard[] 本地数组访问替换为 remote P2PClientTcp NIO 发送）。
4. ❌ **不引入 google grpc-java Stub / io.grpc.stub 任何依赖包**：项目已有自研 proto RPC 栈（p2p-core p2p_rpc.proto 定义的 RpcFrame / RpcMeta / RpcStatus / RpcCallType.UNARY + javax.net.p2p.server.handler 包扫描注册机制），P26 用这套，绝不新增 pom.xml 中 `<groupId>io.grpc</groupId>` 一行。
5. ❌ **不做 vector 召回的跨 shard KNN gather**：P23/P24/P25 设计已写死 "HNSW vector KNN 本地精确不跨 shard gather KNN 邻居"，P26 保持不变；vectorRecall=true 时每个 shard 本地做 efSearch=128 精确 knn，结果再 RRF。
6. ❌ **不做 multi-level Facet（WORD+SENTENCE 合并）**：P25 Facet 默认 facetLevel=1（WORD），P26 只 transport，不新增聚合逻辑。
7. ❌ **不做流式 RPC（SERVER_STREAM / BIDI_STREAM）**：全部走 UNARY；流式留 P28 Future（用户未批准前不启用）。

## Background & Context
- 前序阶段硬约束 VERBATIM：
  - **Roadmap L452 OQ12-④ 定义**：P26 Scope = "全文 GRPC 接入（ScoredDocHit + ScoredDocHitWithExtras 跨节点 wire format；ShardTransport 换 GRPC impl；DsDatabaseClusterClient 暴露 searchCluster/searchClusterWithExtras；P25 Extras 字段 1:1 对应 proto 字段单调；Spec/Tasks SP3.1 Gate A 批准再 Implement）"。
  - **P24 永久红线（L190 AGENTS.md）**：ShardTransport 5 方法签名（putDoc/clearDoc/searchWithVector/indexTermVector/close）永不变更；后续 P26 仅换实现不改路由算法。已在 P24 spec REV1 Gate A VERBATIM 批准。
  - **P25 永久红线（L191 AGENTS.md）**：ZeroLeak3=3 永远独立类不增；ScoredDocHitWithExtras 必须组合 NOT extends final；NG-3 fulltext 包 0 符号引用 netty/grpc。
  - **SP3.1 SOP 三步 Gate**：必须 Gate A (spec/tasks approve) → Implement → Gate B 两连 → Gate C 9Y + 5 doc sync。严格串行不跳。
- 现有基础设施 VERBATIM：
  - p2p-core/pom.xml 已有 protobuf-java + protobuf-maven-plugin（protoc Artifact 3.21.7 Windows classifier），`p2p-db/src/main/proto/` 空目录可放 1 新 fulltext_shard.proto；protobuf-maven-plugin 父 pom 已定义生成目录 `target/generated-sources/protobuf/java` 已加入 source root。
  - p2p-db 已 import p2p-core（pom.xml 第 24 行 `<artifactId>p2p-core</artifactId>`），netty-all 传递依赖在 classpath（但 fulltext package 绝不 import，通过 cluster package 隔离）。
  - javax.net.p2p.server.handler 目录已有 75+ 自动包扫描 Handler 模板，每个 Handler extends P2PCommandHandler，`@Override getCommand()` 返回 P2PCommand enum 或 int 值，通过 SPI `META-INF/services` 注册；DsDatabaseServer 启动时自动加载。

## Functional Requirements
- **FR-1 Transport Pluggable + BackwardCompat 构造**：ShardRouter 必须有 2 构造签名（public）：① 旧 `ShardRouter(N, root)` 不变行为；② 新 `ShardRouter(N, root, BiFunction<File[], FulltextStore[], ShardTransport>)` 重载，允许外部 transport；旧 Tests 零修改。
- **FR-2 ShardTransport 单调 Default 方法 3 条**：Interface ShardTransport 保留 P24 5 方法；新增 FR-2.1 `search(shardId,q,K,o)` default UOE；FR-2.2 `searchWithExtras(shardId,q,rawMap,K,o)` default UOE；FR-2.3 `putDocBatch(shardId,docId2text)` default 循环调 putDoc；P21~P25 Tests 不需要 override 新方法（因为 Tests 只走旧 5 方法路径不碰）。
- **FR-3 Proto fulltext_shard.proto**：6 Request/Response 1:1 字段映射 + 子消息 PositionSpanMsg / SnippetMsg / FacetMsg / ScoredDocHitMsg / ScoredDocHitWithExtrasMsg / SearchOptionsMsg；required wire version=1；字符串默认 UTF-8；float[] vec 用 repeated float；Map<Long,String> rawDocTextsByDocId = repeated pair(key long,value string)。
- **FR-4 7 个 Handler（命令号 24354-24360）**：PUT_DOC_SHARD(24354) / CLEAR_DOC_SHARD(24355) / SEARCH_SHARD(24356) / SEARCH_SHARD_WITH_EXTRAS(24357) / INDEX_TERM_VECTOR_SHARD(24358) / CLOSE_SHARD(24359) / HEARTBEAT_SHARD(24360)；每个 Handler 必须：① receive Request 反序列化 ② 调用本地 ShardRouter.getShardForTest(shardId).method ③ 写 Response 序列化 回；shardId 通过 P2PWrapper header 附带 `int shardId` 字段。
- **FR-5 GrpcShardTransport（cluster package）**：new class `GrpcShardTransport implements ShardTransport`，构造参数 `Map<Integer, InetSocketAddress> shardIdToEndpoint` 或 `List<InetSocketAddress> shardList sorted by shardId 0..N-1`；覆盖 5+3=8 方法，每个方法对应 FR-4 7 handler 的命令号；用 `P2PClientTcp` 发送 Request，timeout 可通过 SearchOptions.deadlineMs 或默认 5000ms。
- **FR-6 DsFulltextClusterClient（cluster package）**：高级客户端；提供 putDoc(docId,text) / clearDoc(docId) / indexTermVector(uid,vec) / search(q,K,o) / searchWithExtras(q,rawMap,K,o) / close；内部 new ShardRouter(N, "<cluster>", (shardDirsArr, shardArr) -> new GrpcShardTransport(endpointMap))，重定向所有 remote call；零本地 shard（或本地 0 实例化 FulltextStore）。
- **FR-7 端到端 Extras 字段 roundtrip 全等**：Java new ScoredDocHitWithExtras → GrpcShardTransport.SEARCH_SHARD_WITH_EXTRAS → 服务端 Handler → proto encode → socket → proto decode → 反序列化 Java → 用户拿到对象，与源对象 core/matchedSpans/snippets/topFacets 字段 逐值完全相等。
- **FR-8 NPE/IAE 跨 wire 抛异常保留**：服务端若抛 IllegalArgumentException / IOException，必须写 Response.status=CODEC_ERROR(14) / INTERNAL_ERROR(13) / BAD_REQUEST(1)，客户端收到对应错误 → translate back 为 Java 同类型 Exception throw（不吞不 silent 丢错误）。

## Non-Functional Requirements
- **NFR-1 ZeroLeak3=3（P21 AGENTS.md L183 永久不变）**：fulltext package top-level 类 count=3（反射 ClassGraph / collectTypes 从 `com.q3lives.ds.database.fulltext` 包扫描，GrpcShardTransport/DsFulltextClusterClient 不在此包，因此 count=3 永远不变）。
- **NFR-2 Facade 单调 ≥ P25 基线 91**：（TD public methods 10 + FS public methods 59 + FS public fields 3 + ShardRouter public methods 19）+ P26 新增 ShardRouter(3-param) + ShardTransport 3 default methods + 2 新 public 类 cluster = Facade 更大，不删除任何旧签名（旧签名 subset ∈ P26）。
- **NFR-3 NG-3 单调（9 CNFE 永远保持 9，0 netty.grpc 符号引用于 fulltext package）**：T5 TR5.3 gatekeeper 扫描 TermDictStore/FulltextStore/ShardRouter/ShardTransport/LocalShardTransport 5 类常量池，结果 0 出现 "io/netty" / "io/grpc" / "javax/net/p2p/client" 字符串。
- **NFR-4 Ingest 30×50 doc × 4 shard via GrpcShardTransport mock loopback（本机客户端 + 本机服务端 InetSocketAddress 127.0.0.1:xxxx）**：Total Ingest ≤ 15s；P95 putDoc ≤ 100ms / doc（TCP 本机 loopback 延迟≤1ms，batch 10 doc 时 ≤50ms/doc 平均）。
- **NFR-5 searchClusterWithExtras 100 次 4 shard 远程 loopback**：P95 ≤ 10000ms（放宽到 10s 因为 TCP 本机序列化/反序列化开销 + per-doc Extras re-gather；不低于 P25 基线 8000ms 太远）。
- **NFR-6 稳定性指纹（与 P25 基线字节级全等）**：相同 30 doc 语料，相同 query "CHERRY" K=20，10 次运行的 finalScore@12f docId 串拼接指纹 Set.size=1（无随机性）。
- **NFR-7 性能 first 三禁令（NG-1 ds-core-design-spec.md）**：① proto encode/decode 绝不反射（protoc 生成代码 静态 builder）；② NIO 不 allocate ByteBuffer per-call（复用 ThreadLocal byte[] buf）；③ 日志不打 println，log 必须 slf4j + 条件 isDebugEnabled 打，避免 hot path 字符串构造。

## Constraints
- **Technical（永久硬规则 SP3.1）**：
  1. Shard 路由公式 = P24 冻结：UNIT=`(uid>>>48)&0xFFFF % N`、DOC=`(FarmHash64.mix4(docId, 0xC0FFEEL,0,0)>>>16)&0x7FFFFFFF % N`、AUTO=写 DOC / 读 UNIT；代码中不得出现任何其他算法。
  2. αβγδ 系数永久：α=0.5 β=0.25 γ=0.1 δ=0.15；RRF k=60；snippetWindow=40，facetTopK=10 等所有 SearchOptions 默认值与 P25 100% 一致；不可改。
  3. ZeroLeak3=3 永远；ScoredDocHitWithExtras 必须组合 NOT extends。
  4. pom.xml 不新增 `<dependency>`（除 proto scope=provided 已存在外），不新增 `<plugin>`，不修改任何 parent pom。
  5. **绝不修改 TermDictStore.java / FulltextSegmenter.java 2 个独立主类任何一行（只允许 FulltextStore.java 改 ShardTransport interface default 方法 + ShardRouter 构造器 overload，两处 10 行以内）**。这是 P21~P25 Facade 稳定性的硬性 Reject 底线。
- **Business（SP3.1 单调）**：绝不删除任何已通过 Tests 的 public 签名、Test 方法、断言；所有 P22~P25 Tests 101 TR 必须 0F0E（P26 的 Gate B 需 101+25=126 TR ×2 两连 0F0E）。
- **Dependencies**：
  1. 依赖 P25 Gate C ✅ CLOSED（已完成，2026-10-04）。
  2. 依赖 p2p-core protobuf-maven-plugin 已工作（mvn p2p-db compile 已能生成 protoc，这在 P15/P17 阶段已用，非新增）。
  3. 依赖 javax.net.p2p.client.P2PClientTcp 已存在（列存 DsDatabaseClusterClient.java L21 already imports），非新造类。

## Assumptions
1. 远程 shard 服务端启动时，每台机器单 JVM 实例化 1 或多个 shard 的 FulltextStore；shardId 由配置分配连续 0..N-1，客户端/服务端 **shardId 必须严格对齐**（不一致视为 BAD_REQUEST）。
2. Proto version=1，字段号分配后永不修改（新增字段号 append 末尾，reserved 不删；向后兼容策略：客户端旧版读新版 Response，unknown field proto3 丢弃不崩）。
3. 单机 mock loopback 测试可行（同一 JVM 启动 DsDatabaseServer 监听随机 port + GrpcShardTransport 指向 127.0.0.1:port），不需真实多机。
4. 用户不要求 TLS 加密（MVP，TLS 留给 P29+；目前 plain TCP 已够，Roadmap OQ12 未提 TLS）。

## Acceptance Criteria

### AC-1: ShardRouter 两构造 + Transport 单调 3 方法 Default
- **Type**: `rule`
- **Given**: 运行 P21~P25 全部 Surefire 不修改 Test 代码
- **When**: `mvn -pl p2p-db "-Dtest=P21_*,P22_*,P23_*,P24_*,P25_*" clean test`
- **Then**: Tests=101 Failures=0 Errors=0 Skipped=0（101 条基线全绿，证明 Facade 单调不破坏向后）
- **Pass Condition**: 101/0/0/0
- **Evidence**: Surefire BUILD SUCCESS 1 次；Gate T1 必跑。

### AC-2: Proto 1:1 Roundtrip 全等（Extras/Spans/Snippets/Facets）
- **Type**: `rule`
- **Given**: new 1 个包含所有字段的 ScoredDocHitWithExtras（包含 matchedSpans 3 条 / snippets 2 条带 highlightRangesInSnippet int[][] / topFacets 10 条带 termText+docCount+totalTf / core.finalScore@12f）
- **When**: ① Java→Proto encode bytes ② Proto→Java decode newObj
- **Then**: newObj 与原对象逐字段 equals：core.docId=original、@12f String.format("%.12f",core.finalScore) 全等；matchedSpans 逐 start/end/termText 全等；snippets 逐 snippetText + startInDoc + highlightRangesInSnippet 数组 int[] 逐元素全等；topFacets 按 docCount↓totalTf↓termText↑ 排序后顺序 100% 同。
- **Pass Condition**: 所有对象字段反射 deepEquals 返回 true（T2 手算 3× 场景）
- **Evidence**: T2 TR2.1/TR2.2/TR2.3 3 断言 PASS。

### AC-3: Handler 7 个注册 + Command ID 区段不冲突（24354~24360）
- **Type**: `rule`
- **Given**: DsDatabaseServer 启动，加载所有 Handlers
- **When**: 通过 ServiceLoader / SPI 列出 P2PCommandHandler 实现类
- **Then**: 7 个 FulltextShard*Handler 类在列表中；每个的 getCommand() 返回值 ∈ [24354, 24360] 且互不重复；不与已存在的 24304~24312 (Registry) / ORM 24344 / P15 Raft 31201-31303 重叠。
- **Pass Condition**: 7 unique handlers in [24354, 24360]，无 overlap。
- **Evidence**: T1 TR1.1/TR1.2/TR1.3。

### AC-4: GrpcShardTransport 端到端 本机 loopback 4 shard（putDoc → search → clearDoc → search）
- **Type**: `rule`
- **Given**: 单 JVM 启动 DsDatabaseServer(4 shard instances registered, random port 127.0.0.1) + DsFulltextClusterClient(endpoints=127.0.0.1:port, 4 shard)
- **When**: putDocCluster(D1..D10, 10 texts each with unique terms) → indexTermVectorCluster(u0..u9, 768D vec) → searchCluster("CHERRY_COMMON", K=10, o) → searchClusterWithExtras("CHERRY_COMMON", rawMap, 10, o)
- **Then**: ① 与同配置 LocalShardTransport ShardRouter 的结果指纹 100% 同（finalScore@12f+docId 串拼接相同）；② Extras 中 snippets 非空（rawMap 非空且命中词存在）；③ clearDocCluster(D1..D5) 后再 search，D1..D5 docId 不在结果中。
- **Pass Condition**: 指纹 Set=1，Extras 非空，cleared docs 不在 hits 中。
- **Evidence**: T4 TR4.1/TR4.2/TR4.3。

### AC-5: Gatekeeper 9Y（SP3.1）
- **Type**: `rule`
- **Given**: T5 反射 4 probe
- **When**: run P26_T5_GatekeeperTest
- **Then**:
  1. Y1 ZeroLeak3=3（fulltext top-level 类）
  2. Y2 9 CNFE 类 + 0 netty/grpc 于 fulltext package 常量池
  3. Y3 P21 bucket counter 远程调用后 sum=0
  4. Y4 Facade ≥ P25 基线（91）且 P21~P25 所有 public 方法名 ⊆ P26 方法名集合（子集非递减）
  5. Y5 路由公式 UNIT/DOC 手算 3 个值 vs 反射 ShardRouter 结果 100% 相等
  6. Y6 Proto Request/Response 字段号 1..N 顺序无 reserved 删
  7. Y7 Handler 7 个 command id 不重叠现有段
  8. Y8 ShardTransport 原有 5 方法签名完整保留（reflection getDeclaredMethods 名字全 match）
  9. Y9 无遗留 TODO/FIXME 注释
- **Pass Condition**: 9Y 全部 true。
- **Evidence**: T5 4 TR 覆盖（TR5.1 counter 0；TR5.2 ZeroLeak3；TR5.3 NG-3 9 CNF+0 netty/grpc；TR5.4 Facade≥91 + 9Y aggregate）

### AC-6: NFR 性能 + 质量 Rubric（T6 5 TR）
- **Type**: `rubric` + `rule` mix
  - **AC-6.1 rule (Ingest ≤15s)**：T6.1 Ingest 30×50 doc 4 shard loopback ≤15000ms
  - **AC-6.2 rule (searchWithExtras 100 P95 ≤10000ms)**：100 次排序 P95 index 95 值 ≤10000ms
  - **AC-6.3 rule (Stable fingerprint 10× Set=1)**：10 次 run docId + finalScore@12f 拼接指纹集合 size=1
  - **AC-6.4 rubric (Quality 0~5 ≥4)**：
    - **Dimension**：远程结果正确性 + roundtrip + 错误码翻译
    - **Scale**: 0~5；**Anchors 1/3/5**：1 = 指纹不一致或结果为空（坏）；3 = 指纹一致但 Extras 丢字段或错误不 throw；5 = 指纹一致 + Extras 全等 + IllegalArgumentException BAD_REQUEST 全还原
    - **Threshold**: >=4
  - **AC-6.5 rubric (Extensibility 0~5 ≥4)**：
    - **Dimension**：未来扩展点（transportFactory 注入 / ShardMode 3 enum 保留 / proto version=1 可 append / command id 区段后续可扩展到 24361+ / batch 方法）
    - **Scale**: 0~5；**Anchors 1/3/5**：1=硬编码无扩展；3=2 个扩展点；5=5 个扩展点
    - **Threshold**: >=4

## Open Questions
- [x] OQ1：P26 是否引入新 Maven 依赖（io.grpc / grpc-stub）？ → **RESOLVED VERBATIM 不引入**，用 p2p-core 自研的 proto RPC 框架 + P2PClientTcp（Roadmap 代码中已存在该客户端），不改动 pom 任何 dependency 行。
- [x] OQ2：P26 是否做 TLS？ → **RESOLVED MVP 不做**，plain TCP；Future P29+ 再考虑。
- [x] OQ3：shard count 上限多少？ → **RESOLVED ≤ 256 与 P21 UID_RING shard 上限对齐**；FarmHash64 路由模 256 不会越界。
- [ ] OQ4（Pending 用户）：用户是否需要 DsFulltextClusterClient 的 Spring Boot starter 自动装配？MVP 暂不做，纯构造器 new，不引入 spring-boot 依赖。
- [ ] OQ5（Pending 用户）：是否需要 IndexTermVector 的 batch 版本（20 term 一起一次 RPC）？MVP 单次 RPC，但 ShardTransport 接口 default putDocBatch 模式已加样板可后续 override 优化。

§End spec.md REV1.
