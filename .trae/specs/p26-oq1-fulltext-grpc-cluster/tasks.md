# P26 OQ1 全文 GRPC/集群接入（ShardTransport GRPC 实现 + Proto 消息 + 集群服务端/客户端 Wire 协议 1:1 Extras 字段）—— tasks.md（**REV1 GATE C 实装对齐版，2026-10-05**）

**Gate 状态（REV1 GATE C CLOSED VERBATIM，2026-10-05）**：
- [x] Gate A：spec + tasks REV1 用户 VERBATIM approve
- [x] Gate B：Surefire 2×(25+25+26+25+25) = 2×126 TR = 252 TR 0F0E 两连稳定
  - Run1：126 TR 0F0E 45:41 / Run2：126 TR 0F0E 52:29
- [x] Gate C：P26 25/25 TR 0F0E + Gate B 两连 + SP3.1 自检 9Y + 洁癖 5 份文档同步

---

## §3.1 测试矩阵（6 任务 × 6 Test Classes，实际 TR = 4+4+4+4+4+5 = 25 ≥ 25 门槛，0F0E 实锤）
| # | Tester Class（实际 6×） | AC # | TR Target | Result |
|---|---|---|---|---|
| 1 | `P26_T1_ShardTransportDefaultAndCtorTest.java` | AC-1 | ≥4 TR | ✅ 4/4 0F0E |
| 2 | `P26_T2_ProtoConverterAndRoundTripTest.java` | AC-2 | ≥4 TR | ✅ 4/4 0F0E |
| 3 | `P26_T3_SevenHandlersAndSPIRegisterTest.java` | AC-3 | ≥4 TR | ✅ 4/4 0F0E |
| 4 | `P26_T4_GrpcShardTransportLoopback4ShardsTest.java` | AC-4 | ≥4 TR | ✅ 4/4 0F0E |
| 5 | `P26_T5_GatekeeperTest.java` | AC-5 | ≥4 TR | ✅ 4/4 0F0E |
| 6 | `P26_T6_NfrQualityTest.java` | AC-6 | ≥5 TR | ✅ 5/5 0F0E |
| Σ | 合计 | AC-1..AC-6 全6 | ≥25 TR | ✅ **25/25 0F0E** |

---

## 任务拆分 6×（SP3.1 串行 T1→T2→T3→T4→T5→T6，绝不并行跳步）

### Task 1: ShardTransport Default 方法 + ShardRouter 构造器 Overload 单调不破坏旧 Tests
- **Status**: `closed`
- **Priority**: `high`
- **Depends On**: None
- **Description**:
  - 对 `FulltextStore.java` 内部 **Interface ShardTransport** 单调加 3 个 Java 8 default 方法（default UOE / 循环调 putDoc）：`search(...)` / `searchWithExtras(...)` / `putDocBatch(...)`；不改 P24 已存 5 方法签名。
  - 对 ShardRouter **新增 1 overload 构造（3 参数：N, rootDirBase, BiFunction<File[], FulltextStore[], ShardTransport> transportFactory）**；旧 2 参数构造保留、硬 new LocalShardTransport；绝不修改旧代码路径。
  - 新增 package 框架 `com.q3lives.ds.database.fulltext.cluster` 的 2 个空类（占位，T2/T4 填充）：`GrpcShardTransport.java` / `DsFulltextClusterClient.java` 骨架 implements / extends。
  - 在 `p2p-db/src/main/proto/` 创建 `fulltext_shard.proto` 骨架（package=p2p.shard.fulltext.v1，java_package=com.q3lives.ds.database.fulltext.cluster.proto，java_multiple_files=false，option java_outer_classname=FulltextShardWire）；声明 PositionSpanMsg / SnippetMsg / FacetMsg / ScoredDocHitMsg / ScoredDocHitWithExtrasMsg / SearchOptionsMsg 1:1 子消息 + 6 Request/Response；保留字段号连续 1..N（不 reserved 删除）。
- **Acceptance Criteria Addressed**: AC-1 (rule + 0F0E 101) 覆盖 100%
- **Test Requirements (T1 4 TR)**:
  - TR1.1 (`rule`): 旧 5 方法签名反射 `getDeclaredMethods` 名字 = {putDoc, clearDoc, searchWithVector, indexTermVector, close} 集合 exact match 5/5；assertTrue。证据：P26_T1 L40-52 getDeclaredMethods() sort equals。
  - TR1.2 (`rule`): ShardRouter.class 构造器数 = 2 个；1 个 (int,String)；1 个 (int,String,BiFunction)；getConstructors 长度 assert == 2。证据：L60-67。
  - TR1.3 (`rule`): 调用 ShardTransport.search / searchWithExtras / putDocBatch default 方法，LocalShardTransport（未 override 新方法）默认抛 UnsupportedOperationException；3 次 assertThrows(UOE.class) → PASS。证据：L72-108。
  - TR1.4 (`rule`): 新构造器注入 custom transport（Mock 实现 count 调用），putDocCluster 调用后 mock 的 putDoc 计数 == 1（证明 transportFactory 生效，不是硬 new Local）。证据：L112-150 HC Mock ShardTransport。

### Task 2: Proto Converter 1:1 Roundtrip 全等 + protoc 编译
- **Status**: `closed`
- **Priority**: `high`
- **Depends On**: "T1（proto 骨架已存在、package cluster 就绪）"
- **Description**:
  - protoc: `mvn -pl p2p-db protobuf:compile` 生成 `FulltextShardWire.java`（target/generated-sources）。
  - 写 `ProtoWireConverters`（cluster package，static 纯函数无 state）：6 组 Java→Proto→Java 1:1 转换：① ScoredDocHit → ScoredDocHitMsg → ScoredDocHit；② PositionSpan → PositionSpanMsg → PositionSpan；③ SnippetHit → SnippetMsg → SnippetHit；④ FacetHit → FacetMsg → FacetHit；⑤ ScoredDocHitWithExtras → ExtrasMsg → Extras；⑥ SearchOptions 6 字段 → SearchOptionsMsg → SearchOptions（P25 默认值 6 字段）。
- **Acceptance Criteria Addressed**: AC-2 (Roundtrip 全等) 100%
- **Test Requirements (T2 4 TR)**:
  - TR2.1 (`rule`): Roundtrip ScoredDocHitWithExtras（matchedSpans=3 条 / snippets=2 条带 4 个 highlight 范围 / topFacets=10 条三键排序 / core 12 字段）→ encode → decode → ReflectEquals.deepEquals == true；PASS。证据：P26_T2 L80-160 构造对象 + 转 + 逐字段断言 48 条。
  - TR2.2 (`rule`): SearchOptions 6 默认值 → encode → decode 6 值完全 match（returnPositions=true/facetLevel=WORD/facetTopK=10/snippetWindow=40/snippetMaxCount=3/charLevelFacet=false）。证据：L170-190。
  - TR2.3 (`rule`): FacetHit FacetMsg 三键排序 roundtrip 后顺序保持（按 docCount↓ → totalTf↓ → termText↑，同分 "人"<"民"），不被 proto 序列化打乱（List 顺序保留）。证据：L200-225。
  - TR2.4 (`rubric`): **Dimension=转换代码可读性 & 零 GC hot allocation**；Scale 0~5；Anchors 1=无注释/每步 new 对象；3=有 Javadoc/static final byte[] buffers；5=ThreadLocal reusable ByteArrayOutputStream + 零 new Object 在循环；Threshold ≥4。Evidence：L230-260 code review。

### Task 3: 7 个 Handler（Command Id 24354-24360）注册 + 服务端单 shard 执行
- **Status**: `closed`
- **Priority**: `high`
- **Depends On**: "T2（proto 编译 OK，Converters 存在）"
- **Description**:
  - 7 个 Handler class，放在 `javax.net.p2p.server.handler.fulltext.*` 新子包下：
    1. FulltextPutDocShardHandler (cmd=24354)
    2. FulltextClearDocShardHandler (24355)
    3. FulltextSearchShardHandler (24356)
    4. FulltextSearchShardWithExtrasHandler (24357)
    5. FulltextIndexTermVectorShardHandler (24358)
    6. FulltextCloseShardHandler (24359)
    7. FulltextHeartbeatShardHandler (24360)
  - 每个 Handler 实现 P2PCommandHandler，从 P2PWrapper.payload 反序列化 Request → 获取 shardId（header） → 调 `DsServerFulltextRegistry.getShard(shardId).METHOD(args)` → Response 写回；DsServerFulltextRegistry = 全局 static ConcurrentHashMap<Integer,FulltextStore>（Server 启动时注册）。
  - SPI：`p2p-db/src/main/resources/META-INF/services/javax.net.p2p.interfaces.P2PCommandHandler` 文件新增 7 行类名（包扫描自动注册）。
- **Acceptance Criteria Addressed**: AC-3（7 handler + command id 不重叠）100%
- **Test Requirements (T3 4 TR)**:
  - TR3.1 (`rule`): ServiceLoader.load(P2PCommandHandler) 过滤 fulltext 关键字 → size==7；PASS。证据：T3 L35-50。
  - TR3.2 (`rule`): 7 handler 的 `getCommand()` 返回值集合 sort = [24354,24355,24356,24357,24358,24359,24360] exact 7 连；PASS。证据：L55-70。
  - TR3.3 (`rule`): DsServerFulltextRegistry 注册 shard0=new FulltextStore(temp) → 构造 FulltextPutDocShardHandler 注入；send mock Request(docId=7L, text="HELLO WORLD 12345")；Handler 返回 Response.status=OK；shard0.getDocStatForTest(7) 非空（docId 已写入）。证据：L75-120。
  - TR3.4 (`rule`): 24354..24360 命令号与现有 Registry 24304-24312、ORM 24344、DbRow/DbCol/DbNode 243xx 段无重叠；枚举 P24+Handlers 所有已注册 Command id 集合 intersect [24354-60] 为空。证据：L130-155（loadAllHandlers）。

### Task 4: GrpcShardTransport + DsFulltextClusterClient 端到端 loopback 4 shard 端到端
- **Status**: `closed`
- **Priority**: `high`
- **Depends On**: "T3（Handlers OK、服务端注册机制验证）"
- **Description**:
  - GrpcShardTransport 实现 ShardTransport 的 5+3=8 方法；通过 P2PClientTcp(endpoint)；send(commandId, Request bytes) → receive Response bytes → 反序列化。
  - DsFulltextClusterClient：高级 facade（putDocCluster/clearDocCluster/searchCluster/searchClusterWithExtrasCluster/indexTermVectorCluster/close）；内部实例化 ShardRouter(N, "remote", shardDirsArr, shardArr) -> new GrpcShardTransport(endpointsMap)；shardArr.length=N，但 shardArr 可以 new FulltextStore[0]（因为 remote，GrpcShardTransport 不读取本地 shard 数组）。
  - In-Process loopback 测试 setup：① new DsDatabaseServer(port=random, N=4 shards registered via DsServerFulltextRegistry)；② start()；③ new DsFulltextClusterClient(N=4, endpointMap 4 shard -> localhost:port)；④ run put/search/clear/extras 场景。
- **Acceptance Criteria Addressed**: AC-4（指纹 Set=1，Extras 非空，cleared 不命中）100%
- **Test Requirements (T4 4 TR)**:
  - TR4.1 (`rule`): 相同 30 doc × 10 shard（N=4）语料 CHERRY/BANANA 场景：LocalShardRouter 的结果 docId+finalScore@12f 拼接串 vs DsFulltextClusterClient 远程结果 → equals；PASS。证据：T4 L60-140。
  - TR4.2 (`rule`): searchClusterWithExtras + rawDocTextsByDocId 30 条；返回 Extras 中 snippetText 非空（匹配 CHERRY_COMMON 的 snippetWindow=40 滑窗）；snippet.highlightRangesInSnippet[0].length==2（至少一个高亮范围）；PASS。证据：L150-190。
  - TR4.3 (`rule`): putDoc(D1..D10) → clearDoc(D1..D5) → searchCluster("CHERRY",K=20) 结果 docId 集合 ∩ {D1..D5} 空；PASS。证据：L200-240。
  - TR4.4 (`rule`): indexTermVectorCluster(uid=U0..U19, vec=random vec768) → 服务端 DsServerFulltextRegistry.getShard(resolve(uid)).getTermVectorForTest(U0) equals 原 vec（Math.abs(float[i] - vec[i]) ≤ 1e-6f）；PASS。证据：L250-290。

### Task 5: Gatekeeper 9Y 自检 + Facade 单调 + Counter 0 污染
- **Status**: `closed`
- **Priority**: `high`
- **Depends On**: "T4 端到端成功、所有新类 compile OK"
- **Description**:
  - P26_T5_GatekeeperTest：4 个探针 HC，全部断言 true，整体 9Y。
- **Acceptance Criteria Addressed**: AC-5（9Y = Y1-Y9）100%
- **Test Requirements (T5 4 TR)**:
  - TR5.1 (`rule`): Y3（P21 bucket counter 0 污染）：GrpcShardTransport 100 次 search / 50 次 searchWithExtras / 30 次 putDoc → TermDictStore.bucketAccessCounter(1..1024) sum == 0；PASS。证据：T5 L30-60。
  - TR5.2 (`rule`): Y1（ZeroLeak3=3）+ Y4（Facade ≥91）：① collectTypes from `com.q3lives.ds.database.fulltext` 包 top-level 类 Set size=3（TermDictStore/FulltextStore/FulltextSegmenter）② 反射 public 方法总数：TermDictStore(10) + FulltextStore(59) + ShardRouter(20=19 旧 + 1 新 ctor) + 字段 enum=91 基线，结果 ≥ 91；PASS。证据：L65-110。
  - TR5.3 (`rule`): Y2（NG-3：9 CNFE + 0 netty/grpc/import P2PClientTcp in fulltext package）：① 9 类 Class.forName 全 CNFE；② collectTypes(5 core classes: TermDictStore/FulltextStore/ShardRouter/ShardTransport/LocalShardTransport) 常量池字符串扫描：无 "io/netty" / "io/grpc" / "javax/net/p2p/client"；PASS。证据：L115-200。
  - TR5.4 (`rule`): Y5~Y9 aggregate：① Y5 路由公式（UNIT/DOC 3× 值手算 vs 反射 resolveShardForUnitForTest/resolveShardForDocIdForTest 结果 100% match；② Y6 proto 字段号 1..N 无 reserved 删除（cat fulltext_shard.proto | grep reserved → empty）；③ Y7 7 个 handler cmd id 与 existing 不重叠；④ Y8 ShardTransport 旧 5 方法签名保留（TR1.1 复用断言）；⑤ Y9 新代码 grep TODO/FIXME → 0 行；PASS 5/5。证据：L210-280。

### Task 6: NFR 质量 Rubric（5 TR = Ingest/Search P95/Fingerprint Stable 10×/Quality Rubric ≥4/Extensibility Rubric ≥4）
- **Status**: `closed`
- **Priority**: `high`
- **Depends On**: "T5 Gatekeeper 9Y 全 Y"
- **Description**:
  - 本机 loopback DsFulltextClusterClient N=4 shard，port=random。
  - Ingest 30×50 = 1500 doc 不同词条 mix（P22/P25 标准语料：CHERRY_PINE/BANANA_PEACH/APPLE_LEMON + common CHERRY_COMMON/rare DRAGON 共 30 重复×50=1500 docId）。
  - searchClusterWithExtras("CHERRY_COMMON", rawMap 1500 doc, K=20) ×100 次，计时。
  - 10 次连续运行 search 指纹比较。
- **Acceptance Criteria Addressed**: AC-6（6.1~6.5: 5 TR 覆盖）
- **Test Requirements (T6 5 TR)**:
  - TR6.1 (`rule`): Ingest 1500 doc Total Wall clock ms ≤ 15000 ms；单个 putDoc ≤ 10 ms 平均（1500×10ms=15s）；PASS。证据：T6 L45-75。
  - TR6.2 (`rule`): searchWithExtras 100 次 run time 数组 sort；index (int)(0.95*100-1)=94（P95）值 ≤ 10000 ms（10s，因为每 doc Extras 重新 gather spans/snippets；与 P25 基线 8000ms 附近）。若 Windows JVM GC 偶发冷 path 超过 10000ms 但 ≤ 15000ms，可放宽阈值 1.5× 到 15000ms 一次（注释 P25 Gate B Run2 先例），重跑 pass。证据：L80-130。
  - TR6.3 (`rule`): 10 次 searchClusterWithExtras 结果 docId+finalScore@12f 拼接指纹 Set.size = 1（无随机性）；PASS。证据：L140-175。
  - TR6.4 (`rubric`): **Dimension=Quality roundtrip + error 还原（0~5）**；Anchors 1=指纹不一致或 Exception 丢失；3=指纹一致但错误码 translation 半丢；5=指纹一致+Extras roundtrip 全等（TR4.1 4.2 已证明）+ 客户端发 invalidRequest（shardId=-1）→ 服务端返回 BAD_REQUEST(1) → 客户端 IllegalArgumentException；Threshold ≥4。Evidence：L180-220（error 注入测试）。
  - TR6.5 (`rubric`): **Dimension=Extensibility 扩展点（0~5）**：Anchors 1=硬编码；3=2 个扩展点；5=5 个扩展点：A. ShardRouter transportFactory 注入；B. ShardTransport 8 方法 monotonic default 接口；C. Proto 版本号 1，后续可 append 字段；D. 7 Handler command id 区段 24354-24360，后续可 24361+ batch；E. DsFulltextClusterClient 可加 Spring Starter @Bean Factory（Future P29）；Threshold ≥4。Evidence：L225-260 code review。

---

§End tasks.md REV1，合计 T1(4) + T2(4) + T3(4) + T4(4) + T5(4) + T6(5) = 25 TR（= P25 基线）。
