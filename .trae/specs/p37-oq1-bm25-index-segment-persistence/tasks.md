# P37 OQ1 BM25 Index Segment 压缩 + 持久化 —— tasks.md（**REV2 GATE C CLOSED，2026-10-20**）

## Gate 三状态
- [x] **Gate A**（Spec+Tasks 签批）→ 用户 VERBATIM 批准（OQ12-⑮①）
- [x] **Gate B**（P37 own 31/31 TR 0F0E + P22-P36 拆 4 批 0F0E + 已知基线 fail 沿用）→ 已跑实锤
- [x] **Gate C**（9Y 自检 + 洁癖 5/5）→ 已完成

### 计划 TR 数（Gate B 实锤后回填）
- P37 Implement T1-T6：5+6+3+3+9+5 = **31 TR**（实测 **31/31 0F0E**）
- Gate B 拆 4 批（沿用 P36 模板 + 追加 P37）：批 4 = P31-P37
- 已知基线 fail 沿用：**P28_T6.tr64_noBarePrintAndGte5NullGuards**（P34 交付 `DnfParserHelperMain` 裸 System.out，跨进程 stdout 协议必需，与 P37 无关）

---

## Stage 1: Gate A（已完成）

| Step | 任务 | 状态 |
|------|------|------|
| 1.1 | Probe 5 锚点（P22 WeightStore 结构 / DsHashMap 磁盘格式 / DsFixedBucketStore mmap / lz4-java+ColumnCompression 复用 / FulltextStore ctor 装配点） | ✅ |
| 1.2 | 起草 spec.md（Scope/红线/架构/接口/测试/逆指标/风险 9 节 + 9Y 自检） | ✅ |
| 1.3 | 起草 tasks.md（Gate 三状态 + 6 阶段 TR 明细） | ✅ |
| 1.4 | NotifyUser 提交 Gate A 批准 | ✅ 用户 VERBATIM 批准 |

### Probe 结论（5 锚点实锤）
1. **P22 WeightStore**（`FulltextStore.WeightStore` 内嵌类，L251）：4 `DsHashMap`（`weight_store/maps/{doc_stat,term_freq_in_doc,term_stat,term_positions}.dshash`）+ 1 `DsFixedBucketStore`（`weight_store/weight_bucket_store`）+ 内嵌 `HnswStore`（`weight_store/hnsw/`）。payload = 4B totalLen 头 + 定长原始字节，**无压缩**；docStat = 48B（docLen[6]+nTerms[6]），termStat = idf 8B + payloadLen 4B + payload，tf key = `FarmHash64.mix4(uid,docId,parentId,level)`。
2. **DsHashMap**（`com.q3lives.ds.collections.DsHashMap`，extends DsObject）：4 文件（`X.dshash` + `.e16` + `.m64` + `.m32`），`MAGIC=".MAP"`，64B 头（`HDR_MAGIC/HDR_VALUE_SIZE/HDR_NEXT_NODE_ID/HDR_SIZE/HDR_NEXT_ENTRY_ID`），mmap-backed trie。
3. **DsFixedBucketStore**（`com.q3lives.ds.bucket`）：mmap 就绪 —— `openMappedWindow(...)` → `MappedWindow`（`MappedByteBuffer`，`mapWindow`）；`HEADER_SIZE=64`、`ID_OFFSET_BITS=56`、`MAX_POWER=27`。
4. **压缩可复用且 0 新 dep**：pom 既有 `org.lz4:lz4-java`；first-party `com.q3lives.ds.database.columnar.ColumnCompression`（LZ4，11B 头 `magic2+version1+origLen4+compLen4`，压缩不省则返回 null）。
5. **FulltextStore ctor**（L804-836）：装配 termDict/tuPkMap/tuStore/idx1-3/seg5builtin/distL-R/distPairMap/distNeighborStore/uidRingShards/**weightStore**（L835）。全库无显式 startup rebuild；idf 惰性算（`rebuildIdf` L1972）。

---

## Stage 2: Implement T1-T6（已完成，串行 T1→T6，31/31 TR 0F0E）

| T | 范围 | TR 数 | 关键实装 | 测试类 / 结果 |
|---|------|------|---------|--------|
| **T1** | DsIndexSegment + SegmentColumnCodec skeleton | 5 | 段不可变 POJO（12 长字段 + crc32[4] + 5 IAE + SHA-256 fingerprint）；Codec 三列定长 pack（56B/24B/12B）+ 动态区逐块 LZ4 + 5 IAE | `P37_T1_SegmentSkeletonTest` **5/5 绿** |
| **T2** | DsIndexSegmentWriter + DsIndexSegmentStore | 6 | 写段（排序 + 去重覆盖 + 列式 + LZ4 + 4KB 对齐 + CRC32 + 原子 rename）+ 6 IAE；store `rootDir/segment/` manifest（list/load/append/remove/openReader）+ 5 IAE | `P37_T2_SegmentWriterStoreTest` **6/6 绿** |
| **T3** | DsIndexSegmentReader mmap 零重建 | 3 | `open` mmap 头 + 段表（0 源扫描）+ 三列 mmap 二分查询 + payload 按需解压 + magic/version/CRC 损坏 IAE | `P37_T3_SegmentReaderTest` **3/3 绿** |
| **T4** | Inproc E2E 冷启动 parity | 3 | 同源数据 → WeightStore 直读 vs Segment 读 **逐条全等**（docStat/termStat idf bit 级/tf）+ 二次 open 源扫描探针=0 + 压缩率/冷启动 P95 达标 | `P37_T4_E2eParityTest` **3/3 绿**（6.078s） |
| **T5** | Gatekeeper 9Y | 9 | Y1 ZeroLeak3 反射收集 count=3 + Y2 pom diff=0/0 CNFE + Y3 bucketAccessCounter 0 污染 + Y4 P21/P22 源码 0 改 grep + Y5~Y8 公式/ctor 不变 + Y9 0 TODO | `P37_T5_Gatekeeper9YTest` **9/9 绿** |
| **T6** | NFR | 5 | 段体积压缩率 ≤ 50% + 冷启动 open P95 ≤ 200ms + 5 类 Javadoc ≥10 行 + CPD ≤1 + 0 println + IAE ≥8 | `P37_T6_NFR_Test` **5/5 绿** |

**TR 总数实锤**：5+6+3+3+9+5 = **31/31 TR 0F0E**（`mvn -o -pl p2p-db surefire:test '-Dtest=ds.P37_*'` → Tests run: 31, Failures: 0, Errors: 0）

---

## Stage 3: Gate B（已完成，P22-P37 拆 4 批）

### 3.1 Surefire 命令（继承 P36 拆批模板，实跑加 `-pl p2p-db` 规避聚合模块无测试）

```powershell
$tmp='i:\2026\code\p2p-net-storage\target\tmp'; $dbh="$tmp\home"
# 批1: P22-P24
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P22_*,ds.P23_*,ds.P24_*'
# 批2: P25-P27
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P25_*,ds.P26_*,ds.P27_*'
# 批3: P28-P30
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P28_*,ds.P29_*,ds.P30_*'
# 批4: P31-P37 (含 P37 own)
mvn -o -pl p2p-db surefire:test "-Djava.io.tmpdir=$tmp" "-Dp2p.db.home=$dbh" '-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*'
```

### 3.2 验收门槛（实锤）
- [x] P37 own **31/31** 0F0E（独立单跑 + 批 4 内含跑 双实锤）
- [x] 批 1（P22-P24）**76/76 0F0E** + 批 2（P25-P27）**80/80 0F0E** + 批 4（P31-P37）**224/224 0F0E**（`Tests run: 224, Failures: 0, Errors: 0` + BUILD SUCCESS，0 P37 引入 fail）
- [x] 批 3（P28-P30）**100 TR = 99 0F0E + 1 已知基线 fail**（P28_T6.tr64，与 P37 无关；P28_T4 本次意外 4/4 通过）
- [x] **Gate B 总计 = 76 + 80 + 100 + 224 = 480 TR（479 0F0E + 1 已知基线 fail）**
- [x] 统计 parity：Segment vs WeightStore 逐条全等（idf bit 级）——`P37_T4.tr41`
- [x] 零 startup 重建：段 open 0 源扫描——`P37_T4.tr43`
- [x] 段损坏 → IAE（magic/version/CRC 三类）——`P37_T3.tr32`

---

## Stage 4: Gate C 洁癖 5/5（已完成）

| 洁癖项 | 内容 | 状态 |
|--------|------|------|
| ① | spec.md Gate C [x] 9Y 自检逐项勾选 + REV2 闭环版头 + 实装偏差说明（§10） | ✅ |
| ② | tasks.md 实锤 TR 数 31/31 + Gate B 4 批实锤 | ✅ |
| ③ | Roadmap `docs/dsdb-design-status-and-roadmap.md` OQ12-⑮ ⏳→✅ + OQ12-⑯ 候选项挂出 | ✅ |
| ④ | Project Memory 追加 P37 交付摘要 | ✅ |
| ⑤ | 红线审计（`git diff --stat` 仅 `fulltext/segment/` 5 新类 + 6 测试文件；P21/P22/pom 0 改） | ✅ |
