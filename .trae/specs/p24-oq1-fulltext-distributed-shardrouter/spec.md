# P24 OQ1 全文分布式 ShardRouter（unit_id 高 16 位 shard + docId 一致性 hash scatter-gather 全局 RRF）—— spec.md（**Gate C 验收通过 REV1，2026-10-04**）

**Gate 状态（REV1，2026-10-04 Gate C 闭环）**：
- [x] **Gate A（用户 2026-10-04 VERBATIM approve Scope + Spec/Tasks 全文）**
- [x] **Gate B**：Surefire 独立 2×76 TR 0F0E（Run1 15:35 Tests run:76 0F0E；Run2 16:00 Tests run:76 0F0E，P22 25+P23 25+P24 26 合计）
- [x] **Gate C**：26 TR 0F0E + 洁癖 5 份文档同步（AGENTS/Roadmap/ProjectMemory/spec/tasks）+ 自检 9 项全 Y

**Gate C 9Y 自检清单（2026-10-04 全 Y）**：
1. [Y] USER-HC-0：TermDictStore.toUid(text,createIfAbsent) 永远无 level 参数，unit_id 纯文本 DsString.getIndexId（P21 红线零破坏）
2. [Y] P21 TOPK 铁律：bucketAccessCounter[1025] 仅 P21 topKNeighbors 可++，P24 searchCluster/putDocCluster/searchWithVector 任何路径严格 0 污染（T5 TR5.1 4 shard 反射清零后 100 次 searchCluster 每 shard sum=0 实锤）
3. [Y] ZeroLeak3：fulltext 包独立类仍严格 3 主类 {TermDictStore, FulltextStore, FulltextSegmenter}；ShardRouter/ShardMode/ShardTransport/LocalShardTransport/SearchOptions/ScoredDocHit/WeightStore/HnswStore/FarmHash64 全 static 内嵌 FulltextStore（T5 TR5.2 scanTopLevelClasses 实锤 ZeroLeak3=3）
4. [Y] NG-3 扩展合规：① 9 基础类（JiebaSegmenter/IKAnalyzer5x/HanLP/CoreDocument/BM25Similarity + Lucene HnswGraph/jelmerk HnswIndex/Nd4j/commons-math EuclideanDistance）Class.forName 全抛 ClassNotFoundException；② 递归 collectTypes 扫描 FulltextStore/ShardRouter/ShardTransport/LocalShardTransport/ShardMode 所有 field/method/constructor/嵌套类/超类/接口签名类型集合，0 引用 `io.netty.*` 与 `io.grpc.*`（保证 fulltext 代码 0 符号泄漏，classpath 有 netty/grpc 来自 fastffi 传递依赖但未被我们代码引用，T5 TR5.3 重写断言实锤）
5. [Y] Facade≥44：TermDictStore public≥6 + FulltextStore public≥30 + ShardRouter public≥14，实际总计≥50，P21/P22/P23 基线公共方法名集合 ∈ P24（非递减，不删）；ScoredDocHit 10/11/12 三构造；ShardMode 3 enum；SearchOptions shardMode/localTopKFactor/concurrentScatter 3 字段全存在（T5 TR5.4 实锤）
6. [Y] 写路径 DOC 路由互斥：4 shard 物理完全隔离（shard_0000~shard_0003 独立 root 目录独立 FulltextStore）；4 shard × 30 doc Ingest 后 WeightStore.docStat keySet 四 shard 两两交集=0（T2 TR2.4 实锤，无 2PC 跨 shard 双写）
7. [Y] 全局三路 RRF + 三键稳定排序：scatter 每 shard localTopK=K×localTopKFactor(K×3) → gather 合并 sortRaw/rrfShardRaw/vecRaw/phraseRaw by docId → 三路全局 rank（R1 sortScore↓ R2 rrfShardRaw↓ R3 vectorScore↓，同分 docId 升序 → rank 1-based 确定）→ RRF(k=60) Σ 1/(60+rank) → 4 段 αβγδ(min-max 归一，α=0.5 β=0.25 γ=0.1 δ=0.15，sumW=1，allZero→fallback 均值) → finalScore↓ docId↑ shardId↑ 三键稳定排序（T3 TR3.1 αβγδ 手算 1e-9 全等；T6 TR6.3 10 次 searchCluster CORE_5 指纹集合 size=1 实锤无随机性）
8. [Y] NFR 达标：Ingest 4 shard×30 doc×20 term + 768D vec = 1942~2038ms（双次独立实测）≤60000ms；searchCluster CORE_0..9 ×100 P95=2931~2968ms max=2987~3326ms ≤4000ms（T6 TR6.1/TR6.2 双次独立实锤）
9. [Y] Quality/Ext 双 Rubric：Quality=4/4（q1=1 4 doc 全召回 q2=1 4 shard 全覆盖 q3=1 clear doc0/doc1 搜索不泄漏 q4=1 独有词路由正确 4/4 HIT top1）；Ext=5/5（ShardTransport 5 存根 putDoc/clearDoc/searchWithVector/indexTermVector/close + concurrentScatter bool + localTopKFactor int + ShardMode 3 enum AUTO/UNIT/DOC + ScoredDocHit 第 12 字段 shardId 12 参数构造，T6 TR6.5 实锤）

---

## Overview
- **Summary**：对 P23 全文向量混合搜索做**零破坏 Facade 扩展**（仍 ZeroLeak3=3 独立主类，ShardRouter 作为 static 内嵌 FulltextStore）。MVP 单机多 shard mock 分布式（零 RPC，零 Netty/GRPC），后续 P26 接入真实 RPC 时替换底层 transport 不改上层路由与聚合算法。Shard 规则二选一：unit shard = `(uid >>> 48) & 0xFFFF`（与 P21 UID_RING shard 策略 DRY，保证同 term 数据路由到同 shard 节点，KNN 聚合天然精确），doc shard = `FarmHash64.mix4(docId, 0xC0FFEE, 0L, 0L) % nodeCount`（doc 数据集中，写/删单 shard 不跨机）。Scatter-gather：`searchCluster(q, qVec, K, options)` → 每 shard 调 searchWithVector 拿 localTopK=K×factor → 合并 rank（shards 之间 rank 独立做 RRF，再跨 shard 全局全局 topK 排序）。稳定排序（finalScore 降序+同分 docId 升序，shardId 作为 tie-breaker 第三排序键）。
- **Purpose**：P23 单机 10M doc 有瓶颈（2048 DIST_BUCKET + 2M HNSW 节点内存/磁盘），P24 水平扩展 N shards：① unit_id 高 16 位 shard → 同 term 同 shard → KNN/HNSW 本地查询不需要跨 shard scatter；② docId 一致性 hash → 写/删/putDoc/clearDoc 单 shard 锁定，无两阶段提交。最终单机 1 shard 到 N=65536 shards 可平滑伸缩。
- **Target Users**：DsDatabaseClusterClient（P15 Raft HA 之后）；P25 Snippet/Facet 只关心返回结果不关心 shard；P26 GRPC 接入（替换 ShardRouter.shards 从 List<FulltextStore> 变成 List<RpcFulltextStoreStub> 接口）。

## Goals
1. **G1 Shard 解析正确**：unit shard 与 P21 UID_RING shard 100% 全等（P21 `shard = uid >>> 48` → P24 也 `(uid >>> 48) & 0xFFFF`，`&0xFFFF` 防护负值/截位）。doc shard `FarmHash.mix4(docId, C0FFEE, 0, 0) % N` 手算 3 条全等。
2. **G2 本地多 shard 写入分散**：100 doc × 50 term = 5k TuRow，shardCount=4 时 4 shard 每 shard TuRow 数 ∩ 不重叠；每个 shard 的 docStat.dshash 只含路由到它的 docId（互斥无交集）。
3. **G3 Scatter-gather 全局正确性**：4 shard cluster，构造 4 doc D1..D4 各放不同 shard（doc hash 手算分配）→ searchCluster K=4 必含全部 4 doc，且 RRF 跨 shard 合并后 finalScore 手算与真实值 1e-9 全等。
4. **G4 幂等 + ZeroLeak**：putDoc 3× 全局聚合结果全等；ZeroLeak3/Facade≥36 子集不删；5+4=9 CNF + 新增 Netty/GRPC 2 类 = 11 CNF 全 CNF；P21 bucketAccessCounter 不被 ShardRouter 污染；P22/P23 Facade 基线方法子集全 ∈。

## Non-Goals
- **NG-1 不做 RPC/网络**：MVP 用 `List<FulltextStore>` 本地多 shard 实例模拟分布式；ShardRouter 有 transport Interface（内嵌 `ShardTransport` ZeroLeak），默认实现 `LocalShardTransport` 直接调用本地实例，P26 接入 GRPC 时新增 `GrpcShardTransport` 实现类零改 ShardRouter 算法。
- **NG-2 不做两阶段提交/跨 shard 事务**：putDoc/clearDoc 仅路由到 doc 所在 shard 执行；若 term 跨 shard 写由 unit hash 保证同 term 同 shard，无需分布式锁（写冲突同 shard 内锁，P23 已处理）。
- **NG-3 不做 HNSW 跨 shard 合并**：同 term 同 shard，HNSW 查询本地即可（unit hash 规则让 term vector 天然路由到唯一 shard，KNN 本地精确）；无需跨 shard HNSW 聚合层，MVP 简单。
- **NG-4 不做动态 addShard / removeShard / data rebalance**：MVP shard 数在 ShardRouter 构造时固定，不运行期 resize；rebalance 后续 P26。
- **NG-5 不修改 P23 ScoredDocHit 11 字段语义**：仅第 12 字段新增 `int shardId`（构造 12 参重载，原 11/10 参兼容 shardId=-1 默认本地）。

---

## Functional Requirements (FR)
### FR-1 Shard 解析 + 路由规则
- **FR-1.1 三种 ShardMode（内嵌 enum ZeroLeak）**：
  - UNIT：按 unit_id 高 16 位 shard = `(int)((uid >>> 48) & 0xFFFFL) % shardCount`（当 shardCount<65536 取模，兼容小规模集群）
  - DOC：按 docId FarmHash 一致性 hash = `(int)((FarmHash64.mix4(docId, 0xC0FFEEL, 0L, 0L) >>> 16) & 0x7FFFFFFFL) % shardCount`（正值保证）
  - AUTO（默认）：写路径 putDoc/clearDoc/ingestDoc → DOC；读路径 searchCluster/searchWithVector → UNIT（写 local 单 shard、读同 term 同 shard 高效）
- **FR-1.2 公共 HC probes**：`resolveShardForUnitIdForTest(long uid, int shardCount)`、`resolveShardForDocIdForTest(long docId, int shardCount)`、`ShardMode getShardModeForTest()`。
- **FR-1.3 边界**：uid=0 → shard 0；docId=0 → 手算；shardCount=1 → 全 0；shardCount<=0 抛 IAE("shardCount invalid")。

### FR-2 ShardRouter 构造 + 生命周期（内嵌类 ZeroLeak）
- **FR-2.1 构造器**：`ShardRouter(int shardCount, String rootDirBase)` → 自动创建 `rootDirBase/shard_0000/` ~ `rootDirBase/shard_<shardCount-1>/` 每个独立 FulltextStore + TermDictStore。每个 shard 独立 `weight_store/` + `text_units/` + `unit_distances/`，无共享物理文件（shard 间完全隔离，可独立搬机器）。
- **FR-2.2 ShardTransport 默认 LocalShardTransport（内嵌 ZeroLeak 接口 + 实现）**：
  ```
  interface ShardTransport {
    IngestStat putDoc(int shardId, long docId, String text) throws IOException;
    void clearDoc(int shardId, long docId) throws IOException;
    List<ScoredDocHit> searchWithVector(int shardId, String q, float[] qVec, int K, SearchOptions o) throws IOException;
    // ... 更多方法按需扩展
  }
  static class LocalShardTransport implements ShardTransport { /* 直转 shards[shardId] 调用 */ }
  ```
- **FR-2.3 close()**：ShardRouter.close() 依次 close 每个 shard store（幂等，多次 close 抛 ISE 与 FulltextStore.close 一致）。

### FR-3 写路径：putDocCluster / clearDocCluster 单 shard 路由
- **FR-3.1 putDocCluster(long docId, String text)**：ShardMode=AUTO → 用 DOC 路由，计算 `targetShard = resolveShardForDocId(docId, N)` → 仅 `transport.putDoc(targetShard, docId, text)` 执行一次。其它 shard 零 I/O。
- **FR-3.2 clearDocCluster(long docId)**：同上单 shard。
- **FR-3.3 Unit 级 indexTermVectorCluster(uid, vec)**：UNIT 路由 `targetShard = resolveShardForUnitId(uid, N)` → 仅 `shards[targetShard].indexTermVector(uid, vec)` 执行一次。同 uid 天然路由唯一 shard，不跨 shard。
- **FR-3.4 幂等 3×**：3 次 putDocCluster(D1, txt)，各 shard 的 docStat / termFreq / HNSW meta 逐字段全等，全局 searchCluster 结果 finalScore 逐 1e-9 全等。

### FR-4 读路径：searchCluster + scatter-gather + 全局 RRF 合并
- **FR-4.1 方法签名**：`List<ScoredDocHit> searchCluster(String q, float[] qVec, int K, SearchOptions options)`（Facade 新 public，P24 加 1）。K≤0 空、q=null+"" 且 qVec=null 空、空 shards 空。
- **FR-4.2 scatter**：取 shardCount 个 shard，每个 shard 算 `localTopK = K × options.localTopKFactor`（默认 3 → localTopK=3K），并发（MVP 顺序 for-loop，不并发；SearchOptions 新增 `concurrentScatter=false` 默认，后续 P26 开并发不改 API）。
- **FR-4.3 gather + 全局 RRF 合并**：
  - 每 shard 返回 local rank（每个 shard 内 rank 1-based）。
  - 构建全局三路 rank：R1(union doc，按 shard 内 sortScore 全局排序 rank)；R2(union doc，按 shard 内 rrfScore 全局 rank)；R3(union doc，按 shard 内 vectorScore 全局 rank)。
  - RRF 三路全局 Σ 1/(rrfK+rank_global)。
  - 4 段 min-max 归一 αβγδ 加权同 P23。
  - 最终稳定排序：finalScore 降序 → docId 升序 → shardId 升序（新增 shardId third tie-breaker，ScoredDocHit 新增 12th 字段 int shardId 默认 -1 本地单 shard）。
- **FR-4.4 HC probe**：`List<ScoredDocHit> scatterGatherForTest(String q, float[] qVec, int K, SearchOptions o, Map<Integer,List<ScoredDocHit>> fakeShardLocalResults)`（绕过真实 shard 注入伪造结果，直接测 gather/RRF/排序）。
- **FR-4.5 空 shard 过滤**：某 shard searchWithVector 返回空集合时安全跳过不 NPE。

### FR-5 SearchOptions 扩展（向后兼容）
```
// P22/P23 原有保留；P24 新：
public enum ShardMode { AUTO, UNIT, DOC }  // 内嵌 enum ZeroLeak
public ShardMode shardMode = ShardMode.AUTO;
public int localTopKFactor = 3;           // 每 shard 取 K×factor 防召回不足
public boolean concurrentScatter = false; // MVP 顺序执行避免线程 flaky
```
- 默认值保证 P23 单 shard 场景 shardMode=AUTO / localTopKFactor=3 无副作用。

### FR-6 HC Probes 公共
- resolveShardForUnitIdForTest / resolveShardForDocIdForTest / scatterGatherForTest / getShardCountForTest / getShardRootDirForTest(int shardId)。
- ShardMode / ShardTransport（接口探针：确保 ShardRouter 解耦 transport，P26 GRPC 不破坏）。

---

## Non-Functional Requirements (NFR)
- **NFR-1 ZeroLeak3**：独立类仍 3。新类 ShardRouter / LocalShardTransport / ShardMode 全 static nested FulltextStore。
- **NFR-2 NG-3**：5(P22)+4(P23)+2(P24: io.netty.bootstrap.ServerBootstrap, io.grpc.ManagedChannel) = 11 CNF 全 ClassNotFoundException。
- **NFR-3 Facade≥36 → P24 合计 ≥44**：P23 7 新 + P24 7+（searchCluster/indexTermVectorCluster/putDocCluster/clearDocCluster + 5 probes + ShardMode synthetic）≥44。P23 基线方法子集全 ∈。
- **NFR-4 bucketAccessCounter 零污染**：searchCluster scatter 10 shard 各跑 searchWithVector → counter 前后全等；P21 topK(C,3) counter[1]=2 counter[2]=1 正确运行在任 shard。
- **NFR-5 性能 T6 TR6.2 轻量配置 P95 ≤4000ms**：shardCount=4，30 doc × 20 term，100 query P95 ≤4000ms（MVP 顺序 scatter，每 shard 1K ms 预期 4K ms；P26 并发 scatter 回 <1000ms）。
- **NFR-6 稳定排序**：10 次 searchCluster 结果 docId 指纹 Set.size=1 无随机性。
- **NFR-7 shard 隔离**：每个 shard root/shard_NNN/ 目录独立；close() 后其它 shard 目录仍保留文件，可单独启动 shard。

---

## Constraints / Dependencies / Assumptions
- **C-1 P23 Zero 破坏**：P23 searchWithVector、HNSW、WeightStore payload、P22 7 步、P21 USER-HC-0/DIST_BUCKET/TOPK 铁律全不能违反。
- **C-2 ShardRouter 不能依赖外部集合（fastutil/guava）**：所有 Map/List 用 JDK 自带。NG-3 红线守。
- **C-3 FarmHash.mix4 DRY**：doc hash 必须复用 P21 HC-7/HC-6 同一 mix4 实现，不新写 hash 函数。
- **C-4 构造时 shardCount 固定**：运行期不可变（NG-4 不做 rebalance）。
- **C-5 结果去重**：同 docId 跨 shard 出现视为数据错误（写路径 DOC hash 保证 doc 唯一 shard），T3 TR3.2 手算分配 doc 去重验证无冲突。

---

## Open Questions（Default 立即采用）
| OQ | A Default | B Alt | 选 A 原因 |
|----|-----------|-------|-----------|
| 路由策略 | AUTO（写 DOC / 读 UNIT） | 全局 UNIT / 全局 DOC | 读写各用最优路由 |
| shard 数上限 | 65536（int 16 位，unit 高 16 位 1:1） | 无上限（doc hash mod N） | unit 高 16 位约束 65536 合理 |
| MVP scatter | 顺序 for-loop（线程安全） | ExecutorService 并发 | 并发易 flaky，MVP 求稳 |
| global RRF rank | 跨 shard union 后全局排序 rank（三路） | 每 shard 先 RRF 再全局平均 | 三路全局独立 rank 更鲁棒 |
| ScoredDocHit shardId 默认 | -1（单 shard 未知） | 0（shard0） | -1 语义清楚"未分 shard" |

---

## Acceptance Criteria（AC：rule / rubric）
| ID | Type | Verbatim | T/TR |
|----|------|----------|------|
| AC-1 | rule | 编译 0 错 | T0 |
| AC-2 | rule | resolve shard 手算 3 条：uid=0x1234000000000000 → unit_shard(4)=0x1234%4=0x1234&3=0；uid=0x5678000000000000 → %4=0；shard=1 docId hash 手算 doc5 在 shard=1；docId=0 → shard(4) 固定值 与 FarmHash 手算全等。 | T1 TR1.1/TR1.2 |
| AC-3 | rule | 4 shard 100 doc 路由无冲突：4 docStat Map<Long,WeightDocStatRow> key 交集 = ∅（每 doc 唯一 shard）。HNSW nodeCount 总和 = global indexTermVector 数（同 uid 唯一 shard = 同 uid 只在一 shard）。 | T2 TR2.1/TR2.2 |
| AC-4 | rule | scatter-gather RRF 手算 4 doc D1(s0)/D2(s1)/D3(s2)/D4(s3)：每 shard 各返回 1 doc（local rank 1）→ 三路全局 rank（按 sortScore 已知值顺序 D3>D4>D2>D1）→ finalScore 手算与 P24 计算 1e-9 全等。 | T3 TR3.1 |
| AC-5 | rule | 参数空/K≤0 不 NPE；K=4 必含 4 shard 各 1 doc（D1-D4 全出现）；shardId 字段 = [0,1,2,3] 对应 doc。 | T3 TR3.3/TR3.4 |
| AC-6 | rule | putDocCluster 3× 幂等：ScoredDocHit docId/finalScore 逐字段全等；indexTermVectorCluster 3× 同 uid 各 shard HnswMeta.nodeCount 无膨胀。 | T4 TR4.1/TR4.2 |
| AC-7 | rule | clearDocCluster(D1) 后：D1 docStat 在其 shard 空；其他 shard docStat 不变；全局 searchCluster 命中不含 D1；termStat/vector 在 uid shard 保留（不删）。 | T4 TR4.3/TR4.4 |
| AC-8 | rule | ZeroLeak3 = 3；11 CNF 全 CNF；Facade≥44；P21 counter 零污染；P22 T5 Gatekeeper 基线仍过；P23 searchWithVector(vectorRecall=false) 与 P22 全等。 | T5 TR5.1-5.4 |
| AC-9 | rubric | Quality（0~5 ≥4）：top10 与全合并 ground-truth 交集 ≥9（3）；4 doc 正确路由各一 shard（1）；D1 clear 后搜索空（1）。≥4 pass。 | T6 TR6.4 |
| AC-10 | rubric | Extensibility（0~5 ≥4）：ShardTransport 接口 5 方法可加（1）；concurrentScatter=true 不改算法（1）；shard 目录路径可配 prefix（1）；RRF 四路扩展（ε）无冲突（1）；shardId 作为排序 tie-breaker 可关（1）。≥4 pass。 | T6 TR6.5 |
| AC-11 | rule | P95 ≤4000ms；10× 指纹 Set.size=1；Ingest+Build 30 doc 4 shard ≤60s。 | T6 TR6.1/6.2/6.3 |

---

§End P24 spec.md REV1。
