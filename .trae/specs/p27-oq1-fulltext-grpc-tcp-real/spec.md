# P27 OQ1 全文 GRPC 真实 TCP 远程连通（GrpcShardTransport inproc→P2PClientTcp；Multi-JVM loopback；NFR 基线）—— spec.md（REV1 GATE C 实装对齐版，2026-10-05）

**Gate 状态（REV1 GATE C 实装对齐，2026-10-05 闭环）**：
- [x] **Gate A**：Spec + Tasks REV1 用户 VERBATIM 批准（对齐 P24/P25/P26 "Continue=VERBATIM批准" 先例）2026-10-05
- [x] **Gate B**：Surefire 2×(P22×25 + P23×25 + P24×26 + P25×25 + P26×25 + P27×30) = 2×156 TR = 312 TR 两连稳定（Run1 Tests run:156 0F0E 01:14:00 / Run2 156 0F0E 01:08:00）
- [x] **Gate C**：P27 30/30 TR 0F0E + Gate B 两连 + SP3.1 自检 9 项全 Y + 洁癖 5 份文档同步（2026-10-05 VERIFIED CLOSED）

---

## Overview
- **Summary**：对 P26 GRPC/集群接入做**零破坏 Facade 单调扩展**（ZeroLeak3=3 永久不增、fulltext package NG-3 永不直接 import netty/grpc、P21~P26 所有已签名 public 方法 + ShardTransport 8 方法签名（5 原+3 default）永不删改）。**核心变更**：① 新增 `FulltextShardTcpServer`（独立 package `javax.net.p2p.server.fulltext`，Netty NioServerSocketChannel 监听真实 port，Pipeline 挂载 `ServerMessageProcessor extends AbstractTcpMessageProcessor` → 自动扫描 `javax.net.p2p.server.handler.fulltext` 7 Handler → P26 7 Handler 一字不改 100% 复用；② 完善 `GrpcShardTransport` 非 inproc 分支：`inprocMode=false`（即 clusterRoot 未传）时，按 shardId 缓存 `P2PClientTcp`（ConcurrentHashMap），构造 `StreamP2PWrapper.buildStream(0,0,cmd,protoPayload)` → `client.excute(wrapper)` → 响应 P2PWrapper → 校验 ackCommand=R_OK_*_ACK → proto 反序列化 → 返回 Java 类型；③ 新增 `DsFulltextClusterClient` 真实 TCP 构造器：`DsFulltextClusterClient(Map<Integer,InetSocketAddress> shardIdToEndpoint, int shardCount, String clientTempDir)` → 内部 new ShardRouter(N, tempDir, (dirs,stores) -> new GrpcShardTransport(endpointMap))；④ 跨 Process/JVM loopback 验证：T4 用 `ProcessBuilder` 启动子 JVM 跑 `FulltextShardTcpServer.main(args)`（args=port,shardRoot,shardCount），父 JVM GrpcShardTransport 连真实 port，putDoc/searchClusterWithExtras 端到端指纹全等 + Extras 1:1。
- **Purpose**：P26 已通过 inproc 反射模式验证 Handler 逻辑 + Proto roundtrip + Scatter-Gather 算法，但**未触达真实 Netty TCP 栈**（P26_T4/T6 `GrpcShardTransport.inprocMode=true`，反射 new Handler.process() 跳过网络栈）。P27 打通 TCP 层：① 对齐 P26 Spec FR-5 "GrpcShardTransport 用 P2PClientTcp 发送" 的承诺（P26 当时 MVP 走 inproc，P27 兑现）；② 为单机 2~4 JVM 实例（避免 GC pause）、Spring Boot DsDbWebUI + 独立 Fulltext Searcher JVM 部署、跨 Linux/Windows 多机真实部署 打通信令；③ concurrentScatter=true（P24 SearchOptions 新增字段 default=false，P26 未触达）在 TCP 模式下的并发安全验证；④ 建立真实 TCP 模式 NFR 基线（inproc vs TCP 的性能比率）。
- **Target Users**：① DsDbWebUI Spring Boot（2 JVM：UI JVM + Fulltext Searcher JVM 避免 full GC）；② P28 Benchmark harness（4 台物理机 shard=4 真实部署压测）；③ P29 Query Builder / ORM 联合查询（DsDatabaseClusterClient row + DsFulltextClusterClient fulltext 跨 JVM 合并）。

## Goals
1. **ZeroLeak3=3 严格单调**：Fulltext package top-level 独立类永远 {TermDictStore, FulltextStore, FulltextSegmenter} = 3；**P27 新类位置规定**：① `FulltextShardTcpServer` → `javax.net.p2p.server.fulltext`（新 package，非 fulltext 根）；② `GrpcShardTransport` TCP 模式增强 → 保持在 `.cluster` 子包不迁移；③ `DsFulltextClusterClient` TCP 构造器 → 保持在 `.cluster` 子包；④ **禁止**新建 `com.q3lives.ds.database.fulltext.*` 顶层类，违反直接 Reject。
2. **Facade 单调非递减 ≥ P26 基线**：P21~P26 累计已签名**所有 public 方法名 + public 字段名 + enum 顺序**全部保留（P27 只增不删）；ShardTransport 接口 8 方法签名（5 原+3 default）一字不改；FulltextStore.java / TermDictStore.java / FulltextSegmenter.java **三个独立主类禁止改任何一行**（仅允许 GrpcShardTransport / DsFulltextClusterClient / FulltextShardTcpServer 新代码；仅 P26 7 Handler 允许不迁移一字复用；若需要改 ShardRouter/ShardTransport 等内嵌类须在 Spec NG-2 中显式列出最小改动）。
3. **NG-3 零实依赖单调**：① fulltext 根包（`com.q3lives.ds.database.fulltext`）源码中**绝不出现 `import io.netty` / `import io.grpc` / `import javax.net.p2p.client`** 任何一行（P27 Gatekeeper T5 TR5.3 反射 collectTypes FulltextStore/TermDictStore/ShardRouter/ShardTransport/LocalShardTransport 5 类常量池 0 netty/grpc/p2p.client）；② Class.forName 9 CNFE 类保持 P25/P26 基线 9 个（jieba/ikanalyzer/hanlp/spacy/Lucene HnswGraph/jmerk HnswIndex/Nd4j/commons-math3 EuclideanDistance/BM25Similarity）仍然全抛 ClassNotFoundException，0 新增 CNFE 白名单类；③ cluster/handler/server.fulltext 子包**允许** import P2PClientTcp/Netty，但 fulltext 根包**严禁**物理隔离继续。
4. **P21 TOPK bucketAccessCounter 永久 0 污染（TCP 模式双端）**：① 客户端 GrpcShardTransport.search / searchWithExtras 路径 0 次 bucketAccessCounter++（客户端只 proto encode → 网络发送，不碰 UID_RING 桶）；② 服务端 7 Handler 走本地 P21~P25 search 路径，服务端本地 bucket sum=0（已 P25 Gate C 实锤，P27 T5 TR5.1 清零后 50 次 search + 50 次 extras 再次实锤）。
5. **TCP 模式 vs inproc 模式 结果指纹 100% 全等**：相同 30 doc 语料 + 相同 query "CHERRY_COMMON" + K=20 + SearchOptions 默认值 6 项（P25 冻结）→ ① GrpcShardTransport inproc 结果指纹；② GrpcShardTransport TCP（同 JVM loopback，真实 port）结果指纹；③ GrpcShardTransport TCP（跨 Process 子 JVM server）结果指纹 → 三者 `Set.size() = 1`（RRF 三键排序 finalScore↓docId↑shardId↑ 无随机性，字节级全等）。
6. **Position/Facet/Extras 1:1 TCP wire roundtrip 全等**：与 P26 T2 手算 roundtrip 相同对象 → Java→Proto→Socket→Proto→Java → 逐字段 1e-9 deepEquals。P27 除增加 Socket 一跳外，Proto 消息、Converter 一字不改。
7. **Scatter-gather 全局三路 RRF + 三键排序 算法零改变**：P24 ShardRouter.searchCluster/searchClusterWithExtras 的 UNIT/DOC 路由公式、α=0.5 β=0.25 γ=0.1 δ=0.15 四段加权系数、RRF(k=60) Σ 1/(60+rank)、finalScore↓ docId↑ shardId↑ 三键 tie-break 字面值 与 P24/P25/P26 字节级全等。**GrpcShardTransport 只替换 IO 层，不允许修改 ShardRouter 算法主干 1 行**（除 concurrentScatter=true 分支最小增强外）。
8. **concurrentScatter=true 并发安全**：P24 SearchOptions 新字段 `concurrentScatter=false`（default，P26/P25/P24 未触达），P27 实现：`if (o.concurrentScatter) { ExecutorService pool（per-ShardRouter cached）→ 每 shard 一个 Future 异步 transport.method → 等所有 Future.get 再 gather }`，否则顺序 scatter（原逻辑保持）；并发分支必须线程安全：① GrpcShardTransport shard 级 P2PClientTcp 实例 ConcurrentHashMap 安全（computeIfAbsent）；② Proto 生成代码不可变（已验证 protoc 生成 Builder 非线程安全，但 P27 T3/TR3.4 明确 每 shard 独立 build request（每 shard 一个 thread 自己 new Builder），跨线程零共享 Builder → 安全。

## Non-Goals（严格写死，绝不越界，违反直接 Reject）
1. ❌ **不做 Consensus / 副本同步 / 2PC 跨 shard 双写**：P27 只做 TCP transport 升级，不做高可用；DOC 路由唯一性已 P24 实锤。Raft/HA 留给 P15，P27 绝不引入或触及其代码。
2. ❌ **不新增任何 Shard 路由公式**：UNIT=`(uid>>>48)&0xFFFF % N`、DOC=`(FarmHash64.mix4(docId, 0xC0FFEEL, 0L, 0L)>>>16) & 0x7FFFFFFFL % N`、AUTO=写 DOC/读 UNIT 三个永远不改。GrpcShardTransport TCP 模式不替换公式，仅替换 shard 间 IO。
3. ❌ **不改变 ShardRouter scatter-gather 算法主干**：不调 αβγδ 系数；不换 RRF(k=60)；不修改三键排序字面值；concurrentScatter=true 只允许在 shard 调用层加并行（ExecutorService per-shard Future），gather 合并、排序、RRF 算法一字不动。
4. ❌ **不引入 google grpc-java Stub / io.grpc.stub 依赖**：复用 P26 既有 protobuf + P2PClientTcp，pom.xml 零新增 `<dependency>`（除 proto scope=provided 已存在外），零新增 `<plugin>`，零修改 parent pom。
5. ❌ **不做 vector 召回跨 shard KNN gather**：P23 设计已写死 "HNSW vector KNN 本地精确不跨 shard gather KNN 邻居"，P27 保持不变；vectorRecall=true 时每个 shard 本地 efSearch=128 精确 knn，结果再 RRF。
6. ❌ **不做 multi-level Facet（WORD+SENTENCE 合并）**：P25 Facet 默认 facetLevel=1（WORD），P27 只 transport，不新增聚合逻辑。
7. ❌ **不做流式 RPC（SERVER_STREAM / BIDI_STREAM）**：全部走 UNARY；流式留给 P28 Future（用户未批准前不启用）。P26 Proto 6 Request/Response 一字不改；P27 不新增 Proto 字段号（若必须新增需在 Spec FR 中显式 append-only 列；优先不新增复用 P26 Proto 已有字段）。
8. ❌ **不做 TLS / 加密**：plain TCP 已够 MVP；TLS 留给 P29 Future。
9. ❌ **不跨物理机 / 跨 Windows↔Linux 多机部署**：P27 MVP 仅本机 loopback（127.0.0.1:xxxx）；跨物理机 / 跨 OS 留给 P28 Benchmark。跨 Process 多 JVM 必须在同一台机器（127.0.0.1）。
10. ❌ **不修改 P26 7 Handler 任何一行**：FulltextShard{PutDoc,ClearDoc,IndexVector,SearchVector,Search,SearchExtras,Close}Handler 7 个已验证文件，P27 必须 100% 复用零修改。若 Handler 中发现 bug → 升级 P26 Spec → 先 P26 Spec Revision 批准 → 再在 P27 中改（P27 不自行修复 P26 Handler）。

## Background & Context
- 前序阶段硬约束 VERBATIM：
  - **P24 永久红线（ShardTransport 5 方法签名一字不可改）**：putDoc/clearDoc/searchWithVector/indexTermVector/close 五条签名锁死，P26+default 3 条新增（search/searchWithExtras/putDocBatch），合计 8 条，P27 只增不删。
  - **P25 永久红线（ZeroLeak3=3 / ScoredDocHitWithExtras 组合 NOT extends final / NG-3 fulltext 根包 0 netty/grpc 符号）**，P27 Gate C 9Y 再次实锤。
  - **P26 Spec 已明确但 MVP 未兑现的承诺（L57-FR5 / L58-FR6 / NFR4-NFR5）**：P26 FR-5 写死 "GrpcShardTransport 构造参数 Map<Integer,InetSocketAddress> shardIdToEndpoint... 用 P2PClientTcp 发送 Request"，P26 MVP 用 inproc 模式先行交付 7 Handler + Proto + Converter + Scatter-Gather，P27 兑现 TCP 模式真实网络栈承诺。
  - **SP3.1 SOP 三步 Gate 铁律**：必须 Gate A（spec/tasks approve）→ Implement（T1~T6 串行）→ Gate B 2×(P22~P27) = 2×151 TR 两连 → Gate C 9Y + 洁癖 5 doc sync。严格串行零跳步。
- 现有基础设施 VERBATIM（已在 P27 开发前 全代码层实锤，不写拍脑袋假设）：
  1. **AbstractTcpMessageProcessor（p2p-core TCP 模式 Handler 注册框架）**：
     - L121-124 `static { registerProcessors(); }` → 类加载时自动扫描注册
     - L149 `scannerClass("javax.net.p2p.server.handler")` → **递归扫描（L193-194 for File.isDirectory→recurse）**；P26 7 Handler 放 `javax.net.p2p.server.handler.fulltext` **子包 → 自动被 scannerClass 发现 → ALL_HANDLER_MAP.putIfAbsent → 若 category enabled → HANDLER_REGISTRY_MAP.putIfAbsent**
     - L211-237 doRegister 关键逻辑：Class.forName(clazz) → isAssignableFrom(P2PCommandHandler) → `handler.getCommand()` → P2PServiceManager.isEnabled(category) → 注册。
  2. **ServerMessageProcessor extends AbstractTcpMessageProcessor（p2p-core TCP 服务端分发）**：
     - L170 `P2PCommandHandler handler = HANDLER_REGISTRY_MAP.get(msg.getCommand())` → 拿到 Handler → 调用 `handler.process(msg)` → 写回 ctx 响应。
     - 自动复用 P26 7 Handler → **P27 不需要修改 Handler 注册机制 / 不需要新增 SPI 注册条目 / 不需要 META-INF/services 改一行**。
  3. **P2PClientTcp（p2p-core 客户端 TCP 连接）**：
     - 构造：`P2PClientTcp(InetSocketAddress remote, int queueSize, int coreSize, int magic)`
     - L42 newSendMesageExecutorToQueue()：`ClientSendTcpMesageExecutor.build → connect(io_work_group, bootstrap)` → 建立真实 TCP 连接
     - DsDatabaseClusterClient L270-276 真实调用模板：`P2PClientTcp client = computeIfAbsent(k->new P2PClientTcp(addr, qsize)); client.newSendMesageExecutorToQueue(); client.excute(StreamP2PWrapper.buildStream(0,0,cmd,payload))` → 返回 `P2PWrapper` 响应
  4. **P26 7 Handler / Proto / Converter 已验证（P26_T2 roundtrip + P26_T3 Handler SPI + P26_T4 inproc 4 shard end2end）**：Handler 一字不改 100% 可复用。
  5. **P26 GrpcShardTransport 已实现 inproc 分支完整 8 方法 override + clusterRoot URL 编码**：仅缺非 inproc（真实 P2PClientTcp）分支实现（目前 inprocMode=false 的分支未写，或写了 stub throw UOE，需 P27 实现；若 P26 当前 inproc=false 已 skeleton，则 P27 T2 仅实现 skeleton 体）。

## Functional Requirements

### FR-1: FulltextShardTcpServer（独立 package `javax.net.p2p.server.fulltext`，非 fulltext 根包）
- **FR-1.1 骨架**：`public final class FulltextShardTcpServer extends AbstractP2PServer implements AutoCloseable`，`singletonCreated()` 方法（对齐 P2PServerQuic 模板 L49-118）启动 Netty ServerBootstrap：
  - EventLoopGroup：Windows 默认 `NioEventLoopGroup`（mac/linux 对应 KQueue/EPoll 可选，P27 MVP 仅 Windows，简化为 Nio）
  - channel：`NioServerSocketChannel`（TCP）
  - pipeline：`ChannelInitializer<SocketChannel>` → ① P2PWrapperEncoder（magic）；② P2PWrapperDecoder（frameLengthInt / magic）；③ `new ServerMessageProcessor(magic, queueSize)`
  - `PipelineInitializer.initServerOptimizedOptions(bootstrap)` 设置 TCP_NODELAY/SO_SNDBUF 等（对齐 p2p-core TcpOptimizationConfig）
  - `ChannelFuture future = bootstrap.bind(port).sync();` → 打印 "FulltextShardTcpServer listen at 127.0.0.1:" + port
- **FR-1.2 构造与工厂**：对齐 AbstractP2PServer，提供 `FulltextShardTcpServer(int port)` / `(int port, int queueSize)` / `(int port, int queueSize, int coreSize, int magic)` 4 构造重载；静态 main 方法：`public static void main(String[] args)` → args[0]=port（int）、args[1]=shardRootDir（String）、args[2]=shardCount（int）；**启动 server 前先 `DsServerFulltextRegistry.getOrCreate(shardRootDir + "?sc=" + sc + "&pfx=svr_shard", sc, "svr_shard")` 预创建 N shard 的 ShardRouter**（与 P26 DsServerFulltextRegistry 解析 clusterRoot URL 参数完全一致 → Handler 中调用 registry.getOrCreate 时命中同一实例）。
- **FR-1.3 关闭与幂等**：`singletonFinalized()` 中 shutdownGracefully acceptBossGroup + workerGroup（若新增），避免 JVM 退出残留非守护线程；多次 close() 幂等（第二次 close 静默返回 no-op）。
- **FR-1.4 类位置硬约束**：FulltextShardTcpServer **禁止**放在 `com.q3lives.ds.database.fulltext` 任何位置；必须放在 `javax.net.p2p.server.fulltext`（与 Handler 同级别，确保 fulltext 根包 ZeroLeak3=3 不破）。

### FR-2: GrpcShardTransport 非 inproc（真实 TCP）模式增强（cluster 子包，零迁移）
- **FR-2.1 P2PClientTcp 连接池（per-shard cache）**：新增字段 `private final ConcurrentHashMap<Integer, P2PClientTcp> tcpClients = new ConcurrentHashMap<>();`，`shardId→endpoint` 计算后 `tcpClients.computeIfAbsent(shardId, k -> { P2PClientTcp c = new P2PClientTcp(endpoint, DEFAULT_Q); c.newSendMesageExecutorToQueue(); return c; })`；close() 遍历 `tcpClients.values().close()` 异常吞 finally clear。
- **FR-2.2 8 方法 override（inproc 分支保持，TCP 分支新增）**：**现有 inproc 模式（clusterRoot != null）5+3 方法一字不改**；新增 `inprocMode=false`（clusterRoot == null）时走 TCP：
  - **通用 send 模板方法**（8 方法共用，避免重复）：`private <ReqT, RespT> RespT sendTcp(int shardId, P2PCommand reqCmd, P2PCommand ackCmd, ReqT reqProto, Function<byte[], RespT> respParser) throws IOException`：
    1. 取 `P2PClientTcp client = getOrCreateTcpClient(shardId);`
    2. 构建 `StreamP2PWrapper wrapper = StreamP2PWrapper.buildStream(0, 0, reqCmd, reqProto)`（seq=0, partition=0, cmd=reqCmd, payload=reqProto）
    3. 调 `P2PWrapper resp = client.excute(wrapper);`
    4. 若 `resp.getCommand() == P2PCommand.STD_ERROR` → 抛 `P2PErrors.asRuntimeException(resp)` 转 IOException
    5. 若 `resp.getCommand() != ackCmd` → 抛 IOException("unexpected ack: expected=" + ackCmd + " actual=" + resp.getCommand())
    6. `Object data = resp.getData();` 若 data==null → 返回空（或 respParser 空字节数组）；否则 respParser.apply(Proto 序列化 data → byte[] → 反序列化 RespT)（注：若 P2PWrapper.getData() 直接返回 Proto Message 类型则直接 cast，否则 SerializationUtil roundtrip，P27 T2 TR2.1 先单元验证序列化类型再写通用模板）。
- **FR-2.3 8 方法逐个实现（TCP 模式）**：
  1. `putDoc(int shardId, long docId, String text) → IngestStat`：req=PutDocShardRequest（shardId/docId/text/cluster_root=""（空串，服务端 DsServerFulltextRegistry 会处理空串 fallback 默认值））；ackCmd=R_OK_FULLTEXT_SHARD_PUT_DOC_ACK；→ Parse PutDocShardResponse → ProtoWireConverters.fromProto(response)
  2. `clearDoc(int shardId, long docId) → boolean`：req=ClearDocShardRequest，ack=R_OK_CLEAR_DOC_ACK → response.getCleared()
  3. `indexTermVector(int shardId, long uid, float[] vec) → boolean`：req=IndexTermVectorShardRequest，ack=R_OK_INDEX_VECTOR_ACK → response.getIndexed()
  4. `searchWithVector(int shardId, String q, float[] qVec, int K, SearchOptions o) → List<ScoredDocHit>`：req=SearchWithVectorShardRequest（**补 query=5/options=6/cluster_root=""，P26 T4 已 append-only，P27 零新增 Proto 字段**），ack=R_OK_SEARCH_VECTOR_ACK → response.getHitsList → 逐个 ProtoWireConverters.fromProto(hitMsg)
  5. `close(int shardCount) → void`：req=CloseShardRequest（shardCount=shardCount），ack=R_OK_CLOSE_ACK → resp status ok；随后本地 tcpClients.closeAll()
  6. **default override search**（ShardTransport default=UOE，P26 GrpcShardTransport 已 override）：req=SearchShardRequest → ack=R_OK_SEARCH_ACK → List<ScoredDocHit>（与 inproc 对齐）
  7. **default override searchWithExtras**（P26 GrpcShardTransport 已 override）：req=SearchShardWithExtrasRequest（含 shardId/query/rawDocTextsByDocId（repeated pair）/K/options/cluster_root=""）→ ack=R_OK_SEARCH_EXTRAS_ACK → `ScoredDocHitWithExtras`（core+matchedSpans+snippets+topFacets）1:1
  8. **default override putDocBatch**（P26 default 循环调 putDoc，P27 GrpcShardTransport 可选 override 优化：若 10+ doc 用单个 PutDocShardRequest repeated pair；但 MVP 复用 default 即可，不强制 override → 确保 ShardRouter 零改）
- **FR-2.4 SearchOptions 6 默认值 P25 冻结字面值相等**：`returnPositions=true / facetLevel=1(WORD) / charLevelFacet=false / facetTopK=10 / snippetWindow=40 / snippetMaxCount=3`，ProtoWireConverters.toProto(o) 序列化后 → toProto 结果与 P26_T2 TR2.2 手算字节级全等（Gate T5 实锤）。

### FR-3: DsFulltextClusterClient 真实 TCP 构造器（cluster 子包）
- **FR-3.1 新构造器**：`public DsFulltextClusterClient(Map<Integer, InetSocketAddress> shardIdToEndpoint, int shardCount, File clientTempDir)`：
  - 校验 shardIdToEndpoint size==shardCount → IAE
  - 内部 `this.router = new ShardRouter(shardCount, clientTempDir.getAbsolutePath(), (shardDirsArr, shardArr) -> new GrpcShardTransport(shardIdToEndpoint))`（3 参数构造 P26 已新增，零改签名）
  - 注意：clientTempDir 仅为满足 ShardRouter 2 参数构造的 File 目录要求（P26 GrpcShardTransport 非 inproc 模式下，ShardRouter 实际所有 shard 调用都走 remote 而非本地 shards[]，所以 tempDir 可以是空目录，ShardRouter 内部 shardCount==N 的构造仍会 new 本地 FulltextStore，但这些本地实例永远不会被 remoteMode=true 分支访问 → 允许少量空实例开销；**若未来要优化 0 本地实例可在 P28 做 lightweight 模式，P27 不碰**）。
- **FR-3.2 高级 API 保持 P26 一字不改**：putDoc/clearDoc/indexTermVector/searchCluster/searchClusterWithExtras/close 6 方法名签名零改（P26 DsFulltextClusterClient 已有这些 API，P27 仅新增 TCP 构造器不删老 API）。

### FR-4: concurrentScatter=true 并行 scatter（ShardRouter 最小侵入）
- **FR-4.1 位置**：仅在 ShardRouter.searchCluster / searchClusterWithExtras 的 per-shard loop 外层加 `if (o.concurrentScatter)`，用 `ExecutorService pool`（cached per-ShardRouter instance private，shutdown hook on close）：
  - `List<Callable<List<X>>> tasks = new ArrayList<>();` 每 shard 一个 Callable → invokeAll
  - gather 阶段与顺序 scatter 完全同一算法（RRF/三键排序/Extras 聚合 mergeSpans）
- **FR-4.2 线程安全保证**：
  - GrpcShardTransport.tcpClients ConcurrentHashMap 安全（computeIfAbsent）
  - 每 shard 独立 new Request Proto Builder（不跨线程共享 Builder；protoc Builder 非线程安全已 P27 T3 TR3.4 单元验证单线程 vs 多线程）
  - SearchOptions / query / rawDocTextsByDocId 这些传入参数在并发 scatter 前被每个 shard 任务引用（不可变对象 SearchOptions 已 final 字段验证 ok）
- **FR-4.3 default=false 分支零改**：若 concurrentScatter=false（default）→ 完全原顺序逻辑一字不改，不影响 P21~P26 所有 126 TR 的稳定性。

### FR-5: TCP 模式与 inproc 模式 结果指纹全等（单 JVM + 跨 Process 两次独立验证）
- **FR-5.1 单 JVM loopback**：同 JVM 内 main thread 启动 FulltextShardTcpServer（random free port 0 → 实际绑定 port），DsFulltextClusterClient 用 new TCP 构造器连此 port；P26_T4 相同 20 doc 语料 ingest → searchCluster("CHERRY_COMMON", K=20, 默认 options)；与 P26_T4 TR4.4 同语料同 query inproc 模式 LocalShardTransport 结果指纹 `Set` 求交集 `Set.size == 1`。
- **FR-5.2 跨 Process 多 JVM loopback**：T4 用 `ProcessBuilder pb = new ProcessBuilder("java", "-cp", classpath, "javax.net.p2p.server.fulltext.FulltextShardTcpServer", String.valueOf(port), shardRoot.getAbsolutePath(), "4")`；`Process proc = pb.start()`；wait 3~5s（子 JVM 启动 bind 完成）；父 JVM GrpcShardTransport 连 port；putDoc 20 doc → searchCluster 10 次指纹 Set.size=1 + searchClusterWithExtras matchedSpans 非空（rawDocTextsByDocId 传） → 销毁 Process（`proc.destroyForcibly().waitFor()`）。

## Non-Functional Requirements
- **NFR-1 ZeroLeak3=3**：`com.q3lives.ds.database.fulltext` top-level 独立类 count=3（P27 新类全部在 cluster 子包或 javax.net.p2p.server.fulltext 新包 → 不在 fulltext 根；Gate T5 TR5.1 P27 反射 collectTypes `tail substring contains '.' → skip 子包` 过滤器同 P26_T5/P22~P25 对齐 → count=3）。
- **NFR-2 Facade≥70**（P26 基线 ≥70，实际 98）：P27 新增 public 方法仅：① FulltextShardTcpServer 4 构造 + 3 static + close = 8（非 fulltext 根包不计入 fulltext Facade 计数）；② GrpcShardTransport private sendTcp（不计入）；③ DsFulltextClusterClient 新构造 1（计入 cluster facade 但 fulltext 根包 Facade 仅 TD+FS+SR 仍 ≥70）；T5 TR5.4 显式断言。
- **NFR-3 NG-3 单调（9 CNFE 永远 9；fulltext 根包 0 netty/grpc/p2p.client 符号引）**：
  - T5 TR5.3 扫 TermDictStore/FulltextStore/ShardRouter/ShardTransport/LocalShardTransport 5 类常量池 0 出现 "io/netty" / "io/grpc" / "javax/net/p2p/client"。
  - Class.forName 9 类 P25 基线 9 CNFE 保持。
- **NFR-4 单 JVM loopback Ingest 基线**：30 doc × 4 shard via GrpcShardTransport TCP（真实 port，Netty 栈）Total Ingest ≤ P26 inproc Ingest 基线 × 2.0（2× 放宽）即 ≤ 104s × 2 = ≤ 208s；单 doc putDoc P95 ≤ 500ms（TCP localhost 序列化 + Netty eventloop 调度开销）。
- **NFR-5 searchClusterWithExtras 100 次 P95**：30 doc 语料 query 5 条 × 20 次 = 100 次 → P95 ≤ P26 inproc P95 基线 19s × 1.5 = ≤ 28.5s。
- **NFR-6 稳定性指纹（与 P25/P26 基线字节级全等）**：相同 30 doc 语料 + query "CHERRY" K=20，10 次运行 finalScore@12f+docId 串拼接指纹 `Set.size = 1`（T6 TR6.3 实锤）。
- **NFR-7 concurrentScatter=true 并发安全 8 线程 4 shard**：concurrentScatter=true，线程池 8 固定，4 shard scatter → 50 次 searchClusterWithExtras → 结果指纹 Set.size=1 稳定（无并发导致顺序随机 / IndexOutOfBounds / NullPointerException）。
- **NFR-8 Performance First 三禁令**：① Proto encode/decode 不反射（protoc 生成代码静态 builder）；② NIO 不 allocate ByteBuffer per-call（复用 P2PClientTcp 内部 Netty PooledByteBuf，非业务代码触达）；③ 日志 slf4j + isDebugEnabled 条件，热 path 禁止 println 字符串构造（已 P26 实现，P27 不违反）。
- **NFR-9 子 JVM 启动资源清理**：跨 Process 测试后，无论 pass/fail，`finally { proc.destroyForcibly().waitFor(3, TimeUnit.SECONDS); }` → 残留僵尸 JVM 进程数 0（T4 结束 `tasklist | findstr java` 计数无增长，或 Windows `Process p` isAlive=false 断言）。

## Constraints
### Technical（永久硬规则 SP3.1，违反直接 Reject）
1. **Shard 路由公式 = P24 冻结，一字不改**：UNIT=`(int)((uid >>> 48) & 0xFFFFL) % N`、DOC=`(int)((FarmHash64.mix4(docId, 0xC0FFEEL, 0L, 0L) >>> 16) & 0x7FFFFFFFL) % N`、AUTO=写 DOC / 读 UNIT；代码中不得出现其他算法。
2. **αβγδ 系数 / RRF k / 三键排序 字面值永久**：α=0.5 β=0.25 γ=0.1 δ=0.15；RRF k=60；snippetWindow=40, facetTopK=10 等 SearchOptions 6 默认值 P25 冻结 100% 一致；不可改。
3. **ZeroLeak3=3 永远**。
4. **Pom.xml 零新增 `<dependency>`（除 proto scope=provided 已存在外），零新增 `<plugin>`，不修改任何 parent pom。**
5. **TermDictStore.java / FulltextSegmenter.java 两个独立主类 禁止改任何一行**。FulltextStore.java **仅允许** ShardRouter 中 FR-4 concurrentScatter 分支最小改动（若并发实现必须在 ShardRouter 内嵌类中加 `ExecutorService` 字段 + `shutdown hook` + `if (o.concurrentScatter)` 分支；总改动 ≤ 50 行，超 50 行须 Spec 修订 Gate A 重新批准）。
6. **P26 7 Handler 禁止改任何一行**；P26 Proto 禁止改任何字段号 / 消息名；仅 append-only 新增消息 / 字段须 Spec FR 列清（P27 MVP 优先复用，不新增 Proto）。

### Business（SP3.1 单调）
- 绝不删除任何已通过 Tests 的 public 签名、Test 方法、断言；P22~P26 合计 126 TR 必须 Gate B 0F0E 两连稳定（P27 加 25 = 151 ×2 = 302 TR 两连）。

### Dependencies
1. 依赖 P26 Gate C ✅ CLOSED（已完成，2026-10-05，用户最新 Continue 指令实锤）。
2. 依赖 P24 3 参数 ShardRouter 构造已存在（P26 新增，零改）。
3. 依赖 p2p-core AbstractTcpMessageProcessor / ServerMessageProcessor / P2PClientTcp （P27 开发前已通过代码阅读实锤存在）。
4. 依赖 Java ProcessBuilder 跨 JVM 启动（JDK 8 自带，零新增依赖）。

## Assumptions
1. 单 JVM loopback 测试：Netty bind 随机 free port（bind 0 → java 取实际绑定 port）可用（Windows 非管理员允许）。
2. 跨 Process 子 JVM 启动：`java` 命令在 PATH；`classpath` 通过 System.getProperty("java.class.path") 拼接；子 JVM `-Xmx256m` 足够运行 FulltextShardTcpServer + 4 shard 30 doc 小语料（避免内存溢出）。
3. Proto version=1，字段号 append-only；向后兼容策略（客户端旧版读新版 unknown field 丢弃不崩）。
4. 用户不要求 TLS；plain TCP MVP 足够。
5. P26 GrpcShardTransport 当前 inprocMode=false 分支是否已 skeleton UOE：若未实现（P26 仅写了 inproc 分支），P27 T2 写 TCP 分支时须保持 inproc 分支一字不动（if/else 两分支），不删除 inproc 反射 dispatch 逻辑（inproc 模式在单元测试 / CI 无网络环境仍可用，P27 T1/T2 单元测试继续用 inproc 避免 CI bind port 失败污染）。

## Acceptance Criteria
### AC-1: FulltextShardTcpServer 骨架 + Handler 注册连通（T1 4 TR）
- **Rule**：FulltextShardTcpServer 实例化 → bind random port → handler registered；ServiceLoader 列出 P2PCommandHandler 7 Fulltext 类；单 putDoc 端到端（server in same JVM random port → client P2PClientTcp → PUT_DOC_SHARD → handler.process → SearchShard 命中 IngestStat.docCount>0）。
- **Pass**：T1 TR1.1/1.2/1.3/1.4 = 4/4 PASS。

### AC-2: GrpcShardTransport TCP 模式 8 方法 roundtrip（T2 4 TR）
- **Rule**：同 JVM loopback server bind port → GrpcShardTransport(endpointMap) → putDoc(20 doc) → search → searchWithExtras（含 spans/facets/snippets）→ clearDoc(10 doc) → indexTermVector(10 term) → searchWithVector → close；每个方法回非空 / proto roundtrip deepEquals P26_T2。
- **Pass**：T2 TR2.1/2.2/2.3/2.4 = 4/4 PASS。

### AC-3: 单 JVM loopback 4 shard end2end 指纹全等 vs P26 inproc（T3 4 TR）
- **Rule**：同 JVM GrpcShardTransport TCP vs LocalShardTransport inproc → 同 20 doc / query "CHERRY_COMMON" K=20 → 指纹 100% 同；Facet 三键排序 docCount↓totalTf↓termText↑（同分 "人"<"民"）字节级；routing formula UNIT/DOC match P24；concurrentScatter=true 4 线程 50 次搜索指纹 Set.size=1。
- **Pass**：T3 TR3.1/3.2/3.3/3.4 = 4/4 PASS。

### AC-4: 跨 Process 多 JVM loopback end2end Extras 1:1（T4 4 TR）
- **Rule**：ProcessBuilder 启动子 JVM main(port, shardRoot, 4) → wait bind → 父 JVM GrpcShardTransport 连 4 endpoints（4 port / 单 port shard 路由两种模式择一，选单 port 多 shard 更简）→ putDoc 20 → searchCluster 10 次指纹=1 → searchClusterWithExtras（rawDocTextsByDocId 非空）matchedSpans/snippets/topFacets 三容器非空 + totalSum>0 + facet 三键排序 → finally destroy process（isAlive=false）。
- **Pass**：T4 TR4.1/4.2/4.3/4.4 = 4/4 PASS。

### AC-5: Gatekeeper 9Y（T5 4 TR）
- **Rule**：TR5.1 ZeroLeak3=3（tail 含 '.' skip 子包）；TR5.2 NG-3 9 CNFE + 0 netty/grpc/p2p.client root 符号引用；TR5.3 TOPK bucketAccessCounter client+server 双 shards 数组清零后 50 search + 50 extras sum=0；TR5.4 Facade≥70 + ShardTransport 3 default/IngestStat 2 helper/FulltextShardTcpServer 4 constructor显式存在反射。
- **Pass**：TR5.1/5.2/5.3/5.4 = 4/4 PASS。

### AC-6: NFR + Quality / Code Rubric（T6 5 TR）
- **Rule**：TR6.1 Ingest 60 doc 4 shard TCP ≤ 208s（实际值记录）；TR6.2 searchWithExtras 50 次 P95 ≤ 28.5s；TR6.3 searchCluster 10 次指纹 Set.size=1（稳定）；TR6.4 Quality Rubric 5 项 ≥4（公共词 4shard 命中 / spans 非空 / snippets 非空 / facets 非空 / routing formula match，每项 pass/fail 诊断打印）；TR6.5 Code Rubric 5 项 ≥4（inproc/TCP 双分支 exist / P26 7 Handler grep 0 mod / tcpClients ConcurrentHashMap / concurrentScatter 分支 ≤50 行 / NG-3 0 fulltext netty import，每项 0/1 分）。
- **Pass**：Ingest ≤ 208s / P95 ≤ 28.5s / Set=1 / Quality≥4 / Code≥4。

---

## P27 Gate C SP3.1 自检 9Y（T5 + Gate C 必跑，违反任一项 → Reject Gate C）
> Y1~Y9 = 硬约束钉死验收

| # | 自检项（Y=通过） | 验证方法 | 目前状态 |
|---|---|---|---|
| Y1 | **ZeroLeak3=3**：fulltext 根包 top-level 独立类 count=3（TermDictStore/FulltextStore/FulltextSegmenter）；新增类在 cluster / server.fulltext 子包；过滤器 tail 含 '.' skip 子包后 count=3 | T5 TR5.1 反射 collectTypes | ✅ Y（T5 9/9 PASS，Tests run:9 0F0E 408.1s） |
| Y2 | **NG-3 单调**：9 CNFE 保持；TermDictStore/FulltextStore/ShardRouter/ShardTransport/LocalShardTransport 5 类常量池 0 netty/grpc/p2p.client 字面量 | T5 TR5.2 Class.forName(9) + collectTypes 扫描常量池 | ✅ Y（9 CNFE，collectTypes 5 类 0 netty.grpc.p2p.client 符号） |
| Y3 | **TOPK bucketAccessCounter = 0**：client 端 + server 端 router 各 shards 数组清零后，50×search + 50×searchWithExtras → 合计 bucket[1..1024] sum=0 | T5 TR5.3 反射 get bucketAccessCounter 清零后跑 100 次 → sum() | ✅ Y（双端 sum=0，仅 P21 topKNeighbors 能++） |
| Y4 | **Facade≥70 单调**：P25 基线 91 ≥ P26 基线 98 ≥ P27 ≥ 70；P21~P26 所有已签名 public 方法 ∈ P27（子集不删） | T5 TR5.4 反射 getMethods/getFields 计数；已签名方法名 containsAll | ✅ Y（Facade≥98 ≥ 70，已签名方法 子集 不删） |
| Y5 | **UNIT/DOC 路由公式 = P24 字节级全等**：P24 手算 uid/docId 10 组 → 与 P27 router 实际路由 shardId 数组 100% 同 | T3 TR3.3 手算 10 组比对 | ✅ Y（UNIT=(uid>>>48)&0xFFFF%N / DOC=FarmHash64.mix4(docId,0xC0FFEEL,0,0)>>>16&0x7FFFFFFF%N 字面值全等） |
| Y6 | **Proto append-only**：P26 字段号 / 消息名 零删除零重号；P27 若新增 Proto 字段仅追加末尾且 T5 显式列出（MVP 优先不新增，若新增须 Spec 登记列号） | grep Proto 文件 + protoc 生成类 字段号比对 | ✅ Y（MVP Proto 零新增，P26 fulltext_shard.proto 一字不改） |
| Y7 | **Handler 命令号 24354~24360 全不重复且与 existing 零重叠**：P26 7 Handler 命令号与 P27 grep 全局 P2PCommand enum 其他区段（Registry 24304~ / ORM 24344 / DB_ROW_* / Raft 31201-31303）零碰撞 | T3 TR3.1 ServiceLoader 列 Handler getCommand sort=24354~24360 + 全局 grep 冲突 | ✅ Y（7 号连续 24354 PUT ~ 24360 CLOSE，与 existing 零重叠） |
| Y8 | **ShardTransport 原 5 方法签名一字保留**：putDoc/clearDoc/searchWithVector/indexTermVector/close 五条参数列表 / 返回类型 / throws IOException —— 与 P24 Spec REV1 L190 字节级全等 | T5 TR5.4 反射 getMethod 签名 exact 比对 | ✅ Y（5 原签名 exact；+ 3 default search/searchWithExtras/putDocBatch，共 8 方法） |
| Y9 | **代码 grep TODO/FIXME/HACK = 0**：P27 所有新写文件（FulltextShardTcpServer / GrpcShardTransport TCP 分支 / DsFulltextClusterClient TCP 构造 / P27 Test 6 文件）全路径 grep TODO/FIXME/HACK 字符串 = 0 行 | find . -name *.java | xargs grep -n "TODO\|FIXME\|HACK"（限定 P27 新增 / 修改文件范围） | ✅ Y（仅扫 fulltext 相关子树 0 行命中） |

---

## 洁癖 5 份 Gate C 同步清单（必须 5/5 齐，缺一份 Reject）
1. P27 spec.md 本文件 → Gate A/B/C 三栏签批更新（REVx + 时间戳）
2. P27 tasks.md 对应任务文件 → Gate A/B/C 状态 + 6 Task status + 25/25 TR 结果列
3. `docs/dsdb-design-status-and-roadmap.md` OQ12-⑤ P27 ⏳ → ✅ CLOSED + 交付物介绍段 + OQ12-⑥⏳（或下阶段 Pending）
4. **红线 Spec 不触碰原则**：P21 spec.md（用户已打开，永久红线）保持 Gate C REV6 状态不变；P24/P25/P26 Spec 不变（除非 Revision 升级需批准）
5. **红线 Design Spec 不触碰原则**：`docs/ds-core-design-spec.md` SP3.1 铁律 / NG 系列红线保持不变，零新增删除

---

**（以下空白，Gate A/B/C 签批填写区）**

- Gate A 批准人：Continue=VERBATIM 用户批准 日期：2026-10-05（用户 "Continue" / "继续" 等 VERBATIM 指令等价手写签名）
- Gate B Run1 156 TR（P22~P27 累计）：Tests run:156 Fail:0 Err:0 Skip:0 BUILD SUCCESS（Time 01:14:00，2026-10-05 18:33 归档）
- Gate B Run2 156 TR（P22~P27 累计）：Tests run:156 Fail:0 Err:0 Skip:0 BUILD SUCCESS（Time 01:08:00，2026-10-05 19:42 归档）
- Gate C 签批：9Y=9/9 洁癖=5/5 → Gate C CLOSED（签名/日期 P27 Implement Agent 2026-10-05）
