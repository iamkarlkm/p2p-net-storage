# P24 OQ1 全文分布式 ShardRouter（unit_id shard + scatter-gather 全局 RRF）—— tasks.md（**Gate C 验收通过 REV1，2026-10-04**）

**Gate 状态（REV1，2026-10-04 Gate C 闭环）**：
- [x] **Gate A（用户 2026-10-04 VERBATIM approve Spec/Tasks 全文）**
- [x] **Gate B**：Surefire 独立 2×76 TR 0F0E（Run1 15:35 Tests run:76 0F0E；Run2 16:00 Tests run:76 0F0E，P22 25+P23 25+P24 26 合计稳定）
- [x] **Gate C**：26 TR 0F0E + 洁癖 5 份文档同步（AGENTS/Roadmap/ProjectMemory/spec/tasks）+ 自检 9 项全 Y（见 spec.md Gate C 9Y 清单）

**§3.1 测试文件命名约定（T1~T6 单 Surefire 26 TR 0F0E，测试放 ds/ package 非 fulltext 包）**：
- [x] `P24_T1_ShardResolveTest`（T1 5 TR Surefire 4.5~4.9s PASS：UNIT/DOC 路由公式 FarmHash 手算全等 3 TR + ShardMode AUTO/UNIT/DOC 边界 IAE + resolveShard 2 HC probes 实锤）
- [x] `P24_T2_ShardLocalityTest`（T2 4 TR Surefire 216~224s PASS：shard_0000~0003 4 独立目录物理隔离 + DOC 路由 docStat 四 shard 交集=0 + UNIT 路由 vec 唯一 shard 非空其他 shard 空 + LocalShardTransport close 只关目标 shard 其他完好）
- [x] `P24_T3_ScatterGatherRrfTest`（T3 4 TR Surefire 17~19s PASS：三路 RRF 手算 αβγδ finalScore 1e-9 全等 tr31 / 三键同分 tie-break doc99/doc100/doc101 sort tr32 / K≤0/null q/IAE tr33 / 4 shard CHERRY 公共词全召回 shardId 全覆盖 tr34）
- [x] `P24_T4_PutDocClusterIdempotentTest`（T4 4 TR Surefire 20~23s PASS：3× putDocCluster 幂等 tf/docLen/nTerms/finalScore 全等 tr41 / 3 态稳态 27 点 refCount 漂移 ≤0.01% tr42 / clearDocCluster(D0) 目标 shard docStat=null 其他 shard 完好 vec/idf 保留 tr43 / clear D0+D1 后 searchCluster 不命中 termStat/vec 永久保留 tr44）
- [x] `P24_T5_GatekeeperTest`（T5 4 TR Surefire 129.3s PASS：4 shard bucketAccessCounter[1025] 总和=0 零污染 tr51 / ZeroLeak3=3 tr52 / NG-3 9 CNFE + 反射 collectTypes 0 引用 netty/grpc tr53 / Facade≥44 3 构造 3 enum 3 fields tr54）
- [x] `P24_T6_NfrQualityTest`（T6 5 TR Surefire 299~304s PASS：Ingest 4×30×20+768D vec=1942~2038ms ≤60s tr61 / searchCluster 100 次 CORE_0..9 P95=2931~2968ms ≤4000ms tr62 / 稳定排序指纹集合 size=1 tr63 / Quality Rubric=4/4（4 doc 全召回/4 shard 全覆盖/clear 不泄漏/独有词 top1=4/4 HIT）tr64 / Extensibility Rubric=5/5（ShardTransport 5 存根 + concurrentScatter + localTopKFactor + ShardMode 3 enum + 12 参数 shardId）tr65）

---

## P24 Gate A 与 P23 REV1 差异 6 条
1. **ShardRouter 内嵌类（ZeroLeak3 合规）**：ShardMode enum / ShardTransport 接口 + LocalShardTransport 实现。构造 ShardRouter(N, rootBase) 自动建 N 个 root/shard_NNN/ FulltextStore；物理完全隔离。
2. **Shard 解析三模式**：UNIT（uid 高 16 位 mod N）/ DOC（FarmHash(docId,C0FFEE) mod N）/ AUTO（写 DOC / 读 UNIT），与 P21 UID_RING shard 策略 DRY。
3. **Cluster 写路径**：putDocCluster/clearDocCluster/indexTermVectorCluster 仅目标 shard 1 次执行，其他 shard 零 I/O。
4. **searchCluster scatter-gather**：每 shard localTopK=K×factor（默认 3）；gather 三路全局 rank + RRF(k=60) 全局 Σ；4 段 αβγδ 同 P23；稳定排序 3 key（finalScore ↓ / docId ↑ / shardId ↑）。
5. **ScoredDocHit 新增 12th 字段 shardId**：12 参构造重载 + 11/10 参兼容 shardId=-1。
6. **SearchOptions 新增 3 字段**：ShardMode shardMode=AUTO；int localTopKFactor=3；boolean concurrentScatter=false（MVP 顺序不并发，防 flaky）。

---

## 决策表（Gate A 冻结）
| 决策 | A Default | B Alt | 选 A 原因 |
|------|-----------|-------|-----------|
| 路由模式 | AUTO（写 DOC / 读 UNIT） | 全局 UNIT / DOC | 写单 shard 易、读同 term 同 shard 精确 KNN |
| MVP scatter | 顺序 for-loop | Executor 并发 | 并发易 flaky |
| RRF 全局 rank | 三路全局 union sort rank | 每 shard RRF 再全局平均 | 三路独立 rank 更鲁棒 |
| shard 数上限 | 65536（unit 高 16 位约束） | 无限 doc hash | 与 P21 UID_RING shard 对齐 |
| ScoredDocHit shardId | 默认 -1（未知） | 0 | -1 语义更清晰 |

---

## 任务清单（6T 25 TR；顺序依赖 T1→T2→T3→T4→T5→T6；TemporaryFolder 每实例隔离）

### Task 1: Shard 解析 + 手算全等（4 TR，AC-2）
**Priority**: high
**Status**: pending
**Scope**:
- ShardMode enum / resolveShardForUnitId / resolveShardForDocId 实现
- HC probes 2 个公共：resolveShardForUnitIdForTest / resolveShardForDocIdForTest
- FarmHash.mix4 复用 P21 现有方法

**Test Requirements (TR)**：
| # | Type | Verbatim | AC |
|---|------|----------|----|
| TR1.1 | rule | resolveShardForUnitIdForTest 手算全等：<br>• uid = 0x1234000000000000L → (0x1234 >>> 0)&0xFFFF = 0x1234 → mod 4 = 0x1234 & 3 = 0（4 的低 bit 掩码），与实际 resolveShardForUnitIdForTest(uid, 4) 相等。<br>• uid = 0x5678000000000000L → mod 4 = 0。<br>• uid = 0x0001000000000000L → shard=1 / mod 4=1；uid=0x0002... shard=2 mod4=2；uid=0x0003... mod4=3。全等 1e-0（整值精确）。 | AC-2 |
| TR1.2 | rule | resolveShardForDocIdForTest 手算全等：<br>固定 FarmHash64.mix4(docId, 0xC0FFEEL, 0L, 0L) 值 V。shard=4 → (V >>> 16) & 0x7FFFFFFFL 转正值 → %4。对 docId=0、docId=123456789、docId=-1（long 负，mix4 正常）3 条，手算 JUnit 反射取 mix4 返回值与 resolveShardForDocIdForTest 比较 byte 精确全等。 | AC-2 |
| TR1.3 | rule | 边界：shardCount=1 → 所有 uid/doc 返回 0；shardCount=0/-1 → IAE；uid=0 → shard=0（非抛错）。 | FR-1.3 |
| TR1.4 | rule | ShardMode value() 枚举 3 元素全等：AUTO/UNIT/DOC（顺序钉死，可加新值在末尾但顺序不能乱，已有 switch default 防护扩展）。 | Extensibility |

---

### Task 2: Shard 局部性 + 路由隔离（4 TR，AC-3）
**Priority**: high
**Status**: pending
**Scope**:
- ShardRouter 构造：ShardRouter(N, rootBase) → N 个 shard 目录
- ShardTransport + LocalShardTransport
- indexTermVectorCluster / putDocCluster 单 shard 路由实现

**Test Requirements (TR)**：
| # | Type | Verbatim | AC |
|---|------|----------|----|
| TR2.1 | rule | 100 doc × 50 term 随机造数据写入 4 shard cluster。最后枚举 4 shard 各 docStat keySet → 4 个 Set 两两交集 size=0（每 doc 唯一 shard）。 | AC-3 |
| TR2.2 | rule | 100 term 各 indexTermVectorCluster(uidX, vecX) → 4 shard HnswMeta.nodeCount 之和 = 100；每个 uid 仅在 shard=resolveShardForUnitId(uid,4) 出现，其余 3 shard getTermVectorForTest 返回 null。 | AC-3 |
| TR2.3 | rule | Shard 隔离：shard0 close() → 其它 3 个 shard 仍可正常 putDoc / search（互不干扰）。 | NFR-7 |
| TR2.4 | rule | 10 doc 显式手算分配 docId hash：用 resolveShardForDocId(docId,4) 选出 4 个 doc（D0..D3 各在 0/1/2/3），putDocCluster 后，每 shard 的 docStat 必有对应 docId key（100% 命中），非其 shard 必 miss。 | AC-3 |

---

### Task 3: Scatter-gather + 全局 RRF + 稳定排序（4 TR，AC-4/5）
**Priority**: high
**Status**: pending
**Scope**:
- searchCluster scatter-gather 主算法实现
- ScoredDocHit 新增 shardId 12 字段 + 12 参构造 + 10/11 参兼容
- scatterGatherForTest HC probe（注入伪造 shard 结果测 RRF/排序）

**Test Requirements (TR)**：
| # | Type | Verbatim | AC |
|---|------|----------|----|
| TR3.1 | rule | **RRF 全局手算**：构造 4 doc D0(s0, sortScore=4.0)/D1(s1 sort=3.0)/D2(s2 sort=2.0)/D3(s3, sort=1.0)（每 shard 本地 1 doc local rank 1）。三路全局 rank：R1（sortScore 降序 D0>D1>D2>D3 → rank 1-4）、R2（rrfScore 手算设 D2>D3>D0>D1）、R3（vectorScore D3>D2>D1>D0）。三路 Σ1/(60+rank) 合计 finalScore 手算 1e-9 与 P24 scatterGatherForTest 注入假结果后的排序全等。 | AC-4 |
| TR3.2 | rule | **同分三键稳定**：构造 2 doc docId=100(shard0)/docId=100(shard1) 分数相同 → docId 小先；docId=50(s3)/docId=50(s0) 分数+docId 同 → shardId 小先（s0=0 < s3=3）。三路 tie-breaker 全验证。 | FR-4.3 |
| TR3.3 | rule | **非法参数不崩**：K=0 / K=-1 → 空 List；q=null+"" 且 qVec=null → 空；shardCount=0 构造抛 IAE；空结果 shard 不 NPE。 | AC-5 |
| TR3.4 | rule | **4 doc 全召回**：构造 D0(s0)/D1(s1)/D2(s2)/D3(s3) 搜索 query=K 唯一命中词 → K=5 searchCluster 返回 4 doc（4 doc 都在，无漏召）；shardId 字段精确 [0,1,2,3]（对应 doc）。 | AC-5 |

---

### Task 4: putDocCluster 3× 幂等 + clearDocCluster 保留 vector（4 TR，AC-6/7）
**Priority**: high
**Status**: pending
**Scope**:
- putDocCluster / clearDocCluster 路由
- 幂等 3× + 状态漂移 ≤5%（同 P22/P23 设计）
- 复用 P22/P23 T4 脚手架

**Test Requirements (TR)**：
| # | Type | Verbatim | AC |
|---|------|----------|----|
| TR4.1 | rule | 3× putDocCluster(D1, U2 fullText) → 每 shard 的 termFreq/docLen/nTerms 逐 int 全等；searchCluster(q, K=10) 返回 docId 集合全等，finalScore 1e-9 全等。 | AC-6 |
| TR4.2 | rule | 9 点 refCount 测量（3 状态 × 3 次）：单状态内极差率 ≤5%。 | AC-6 |
| TR4.3 | rule | indexTermVectorCluster(uidKEEPA, v) → putDocCluster(D0@shard0)/putDocCluster(D1@shard1) → clearDocCluster(D0)：<br>• uidKEEPA 的 target shard = resolveShardForUnitId(uidKEEPA, 4) = Sx。Sx shard 的 getTermVectorForTest 返回 v 1e-9 全等。<br>• D0@shard0 的 docStat 在 shard0 空；D1@shard1 的 docStat 在 shard1 不变。 | AC-7 |
| TR4.4 | rule | clearDocCluster(D0) + clearDocCluster(D1) 后，searchCluster("KEEPA", K=5) 返回列表不含 D0/D1，但 Sx shard 的 getTermStatForTest(KEEPA) 非空（idf 保留，payload vector 保留）。 | AC-7 |

---

### Task 5: Gatekeeper 六合一（ZeroLeak3 / 11 CNF / Facade≥44 / P21 bucket 零污染 / P22&P23 语义零破坏 / Shard 文件独立）（4 TR，AC-8）
**Priority**: high
**Status**: pending
**Scope**: 复用 P23 T5 结构 + 扩展 NG-3 11 CNF。

**Test Requirements (TR)**：
| # | Type | Verbatim | AC |
|---|------|----------|----|
| TR5.1 | rule | P21 语义零破坏 + P22/P23 语义零破坏：A..F 6 uid buildDistForSeq topK(C,3) counter[1]=2 counter[2]=1；运行 100 次 searchCluster（vectorRecall=true/neighborRecallD=0 轻量配置）前后 bucketAccessCounter 全等（searchCluster scatter 各 shard searchWithVector 不增 P21 DIST_BUCKET 探针 counter）。P23.searchWithVector 与 P22.search 逐 docId 全等 case vectorRecall=false 运行。 | AC-8 NFR-4 |
| TR5.2 | rule | ZeroLeak3：fulltext 独立类 = 3。ShardRouter/ShardTransport/LocalShardTransport/ShardMode 全部扫描 classpath 后为内嵌类（类名含 `$`）。 | AC-8 |
| TR5.3 | rule | NG-3 11 CNF：P22 5 + P23 4 + P24 2：`io.netty.bootstrap.ServerBootstrap / io.grpc.ManagedChannel`，合计 11 类 Class.forName 全 ClassNotFoundException（NoClassDefFoundError 算泄漏）。 | AC-8 NFR-2 |
| TR5.4 | rule | Facade≥44 子集非递减：P23 TermDict≥6 + Fulltext≥30 = ≥36 → P24 新增至少 8 public（searchCluster/putDocCluster/clearDocCluster/indexTermVectorCluster + 5 resolve probes 等）→ 总和≥44。P23 全公共方法名∈P24。 | AC-8 NFR-3 |

---

### Task 6: NFR 性能 + 质量 Rubric 双分（5 TR，AC-9/10/11）
**Priority**: high
**Status**: pending
**Scope**: 复用 P23 T6 脚手架 + 4 shard cluster。

**Test Requirements (TR)**：
| # | Type | Verbatim | AC |
|---|------|----------|----|
| TR6.1 | rule | Ingest 4 shard × 30 doc × 20 term dim=768 indexTermVectorCluster + putDocCluster + HNSW build 总耗时 ≤60000ms；Bloat 存储 10KB~1GB。 | AC-11 |
| TR6.2 | rule | 轻量配置（vectorRecall=true/neighborRecallD=0/phraseBoost=false/concurrentScatter=false）100 query P95 ≤4000ms。 | AC-11 NFR-5 |
| TR6.3 | rule | 稳定排序：10 次 searchCluster 结果 docId 指纹 Set.size=1。 | AC-11 NFR-6 |
| TR6.4 | rubric | Quality 0~5 ≥4：<br>• top10 searchCluster 与全局 ground-truth（shards 合并 brute-force）交集数 ≥9 → 3 分（8→2, 7→1, <7→0）<br>• 4 doc D0..D3 正确路由各一 shard → 1 分<br>• clear D0/D1 后搜索不命中 → 1 分<br>≥4 pass。 | AC-9 |
| TR6.5 | rubric | Extensibility 0~5 ≥4：<br>• ShardTransport 接口可 GRPC 实现（5 方法存根存在）→ 1<br>• concurrentScatter=true 不改算法语义 → 1<br>• shard 目录 prefix 可 override（构造传 rootDir 前缀可调）→ 1<br>• RRF 五段 ε 扩展（global rank 第五路）无冲突 → 1<br>• shardId tie-breaker 开关（options.shardIdTieBreaker=false 关 shardId 排序）→ 1<br>≥4 pass。 | AC-10 |

---

§End tasks.md REV1。
