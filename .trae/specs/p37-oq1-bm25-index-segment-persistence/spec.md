# P37 OQ1 BM25 Index Segment 压缩 + 持久化 —— spec.md（**REV2 GATE C CLOSED 2026-10-20**）

> Gate A REV1 已用户 VERBATIM 批准 → Implement T1-T6 = 31/31 TR 0F0E → Gate B 拆 4 批全量回归 → Gate C 9Y 逐项 [x] + 洁癖 5/5。实装偏差见文末「实装偏差说明（REV1 → REV2）」。

## Gate C 9Y 自检清单（Gate A 为**目标**，Gate C 逐项 [x] 通过；任一未过 → 退 Gate A 整改）
- [x] Y1 ZeroLeak3=3：fulltext 根包仍 = {FulltextSegmenter, FulltextStore, TermDictStore}，P37 0 新增根包 top-level 类（5 新类 100% 放 `segment/` 子包）——`P37_T5.y1_ZeroLeak3_Only3TopLevelClasses` 绿
- [x] Y2 NG-3 零依赖：P37 0 新增 netty/grpc/p2p.client 根包引用 + pom.xml 0 新增 dep/plugin（压缩复用 pom 既有 `org.lz4:lz4-java` + first-party `ColumnCompression`）——`P37_T5.y2` 绿
- [x] Y3 TOPK 铁律：segment 只读统计查询，0 改 K，`bucketAccessCounter[1025]` 严格 0 污染——`P37_T5.y3` 绿
- [x] Y4 Facade 单调不减：P21 FulltextStore / P22 WeightStore / P23~P36 全部 0 改；segment 模块纯 additive（新子包，0 触碰既有 public 签名）——`P37_T5.y4` 绿
- [x] Y5 路由公式：P37 0 改 ShardRouter / 0 改 P24 UNIT/DOC 路由——`P37_T5.y5` 绿
- [x] Y6 P21~P36 编译兼容：TermDictStore / WeightDocStatRow / WeightTermStatRow / UnigramPos 等既有类型 0 改——`P37_T5.y6` 绿
- [x] Y7 HybridHit ctor=2：0 改——`P37_T5.y7` 绿
- [x] Y8 ShardTransport 5 方法：0 改——`P37_T5.y8` 绿
- [x] Y9 0 TODO/FIXME/HACK：100% 实装 segment 写/读/压缩/零重建加载——`P37_T5.y9` 绿

---

## 1. Scope（用户单选 OQ12-⑮ 候选项 ①，VERBATIM 锚定）

> 原文：`P37 BM25 Index Segment 压缩 + 持久化（基于 P22 WeightStore 扩 DsIndexSegment，存 disk + mmap + 索引压缩，零 startup 重建）`

P37 = 对 P22 `WeightStore` 已落盘的 BM25 统计（docStat / termStat / termFreq）做 **列式 segment 化 + 压缩 + mmap 零重建读取**：

1. **DsIndexSegment**：不可变「索引段」抽象 —— 一段自描述的列式文件（4KB 头 + 定长列区 + 动态压缩区），承载某次快照的 BM25 统计全量。
2. **列式存储 + 定长/动态分离**：`docStat` 列（docCount × 48B 定长）、`termStat` 定长列（uid 8B + idf 8B + payloadOff 4B + payloadLen 4B）、`termFreq` 定长列（mix4 key 8B + tf 4B，按 key 升序）—— 三个定长列 **按 key 排序**，支持 mmap 上直接二分；`termStat.payload`（含 term vector）落在**独立动态区**，按 4KB 页 LZ4 压缩。
3. **索引压缩**：复用 pom 既有 `org.lz4:lz4-java` + first-party `com.q3lives.ds.database.columnar.ColumnCompression`（11B 头：magic 2B + version 1B + originalLen 4B + compressedLen 4B），**0 新增 Maven dep**。
4. **mmap 零 startup 重建**：`DsIndexSegmentReader` 打开段时仅 mmap 头 + 段偏移表，**不扫描源、不建哈希表、不重算 idf**；查询走「定长列二分 + 动态区按需解压」。
5. **DsIndexSegmentStore**：`rootDir/segment/` 下 manifest + 段文件生命周期（list / load / append / remove），段不可变（append-only 语义）。

**MVP 边界（0 越界）**：
- 仅做 BM25 统计（docStat / termStat / termFreq）的段化持久化 + 压缩 + 零重建读取
- **不做** segment 合并 / compaction / tiered-LSM / 增量 upsert（段是不可变快照）
- **不做** 倒排 postings 全量重建引擎（只持久化 P22 已有统计口径）
- **不做** 向量 HNSW 段化（P23 HnswStore 维持既有磁盘布局，0 改）
- **P21 `FulltextStore` / P22 `WeightStore` 源码 0 改**（收紧实装：段模块完全独立 additive，不反向依赖 WeightStore 私有字段）
- **0 新增 Maven dep**（压缩只用既有 lz4-java）
- 5 新类全放新子包 `com.q3lives.ds.database.fulltext.segment`（ZeroLeak3=3 永保）

---

## 2. 红线（VERIFIED 永久钉死，0 越界）

| 编号 | 红线 | 守卫实现约束 |
|------|------|------------|
| R1 | **ZeroLeak3=3** | 5 新类 100% 放 `segment/` 子包；fulltext 根包 top-level 类数严格 = 3（`{TermDictStore, FulltextStore, FulltextSegmenter}`） |
| R2 | **NG-3 零依赖** | pom.xml 0 新增 dep/plugin；源码 0 import `io.netty.*` / `io.grpc.*` / `javax.net.p2p.client.*`；压缩仅复用 lz4-java + `ColumnCompression` |
| R3 | **TOPK 铁律** | segment 查询纯只读统计；0 改 K / 0 改 `SearchOptions`；`bucketAccessCounter[1025]` 0 污染 |
| R4 | **Facade 单调不减** | P21 `FulltextStore` / P22 `WeightStore` / P23~P36 全部 0 改；P37 纯 additive（新子包，0 触碰既有 public 签名） |
| R5 | **HybridHit ctor=2** | 0 改 |
| R6 | **P21~P36 字节级兼容** | TermDictStore / WeightStore / DsHashMap / DsFixedBucketStore / ShardRouter / ShardTransport / HJC / Resilience / DnfParser 全部 0 改 |
| R7 | **段格式魔数 + 版本硬校验** | 段头含 `MAGIC="DIDX"` + `VERSION=1`；读时 magic/version 不匹配 → IAE（不静默降级） |
| R8 | **CRC32 完整性** | 每 section 头带 CRC32；读时校验失败 → IAE（防半写/损坏静默返回脏数据） |
| R9 | **4KB 页对齐 + 定长/动态分离** | 段头占 1 页（4096B）；三个定长列区起始 4KB 对齐；动态区按 4KB 页组织 + 逐页 LZ4 |

---

## 3. 架构（5 个新类，P21/P22 源码 0 改，0 改 P23~P36）

### 3.1 新增清单

| 名称 | 类型 | 职责 | 关键字段/方法 |
|------|------|------|---------------|
| `DsIndexSegment` | 不可变 POJO（`segment/`） | 段元数据 + section 偏移表 | `version` + `docCount` + `termCount` + `tfCount` + `docStatOff/Len` + `termStatOff/Len` + `tfOff/Len` + `varOff/Len` + `crc32[]` + 5 IAE 守卫 + `fingerprint()` |
| `SegmentColumnCodec` | 静态工具类（`segment/`） | 列式编解码（docStat / termStat / termFreq pack+unpack + LZ4 动态区 + delta+varint 压缩） | `packDocStat(rows)` / `unpackDocStat(buf,n)` / `packTermStat(rows)` / `packTf(entries)` / `compressVar(...)` / 5 IAE 守卫 |
| `DsIndexSegmentWriter` | 静态/实例写入口（`segment/`） | 从外部喂入的 BM25 统计写段（排序 + 列式 + 压缩 + 页对齐 + CRC） | `addDocStat(docId,docLen[],nTerms[])` / `addTermStat(uid,idf,payload)` / `addTf(key,tf)` / `finish():DsIndexSegment` / 6 IAE 守卫 |
| `DsIndexSegmentReader` | mmap 只读（`segment/`） | 打开段 → mmap → 二分查询（零重建） | `open(File)` / `getDocStat(docId)` / `getTermStat(uid)` / `getTf(key)` / `scanTf(prefix)` / `close()` / 4 IAE 守卫 |
| `DsIndexSegmentStore` | 目录/manifest 管理（`segment/`） | `rootDir/segment/` 下段生命周期 | `storeDir(rootDir)` / `list():long[]` / `load(segId)` / `append(segment)` / `remove(segId)` / 5 IAE 守卫 |

### 3.2 段文件布局（4KB 页对齐 + 定长/动态分离）

```
段文件（append-only，不可变）
┌──────────────────────── 4096B 头（1 页）────────────────────────┐
│ MAGIC "DIDX"(4B) │ VERSION(4B) │ docCount(8B) │ termCount(8B) │ tfCount(8B) │
│ docStatOff/Len(8+8) │ termStatOff/Len(8+8) │ tfOff/Len(8+8) │ varOff/Len(8+8) │
│ crcDocStat(4B) │ crcTermStat(4B) │ crcTf(4B) │ crcVar(4B) │ reserved → 4096B │
├──────── docStat 列（4KB 对齐，docCount × 48B，按 docId 升序）────────┤
│ [docLen[0..5] ×6 int][nTerms[0..5] ×6 int] × docCount │
├──────── termStat 定长列（4KB 对齐，termCount × 24B，按 uid 升序）────────┤
│ [uid 8B][idf 8B][payloadOff 4B][payloadLen 4B] × termCount │
├──────── termFreq 定长列（4KB 对齐，tfCount × 12B，按 mix4 key 升序）────────┤
│ [key 8B][tf 4B] × tfCount │
├──────── 动态区（4KB 页，逐页 LZ4）────────┤
│ [page 0: ColumnCompression(4B origPageLen + payload bytes)][page 1...] │
└─────────────────────────────────────────┘
```

- **定长列对齐** = P22 `WeightStore` 口径镜像：docStat 48B = `docLen[6]+nTerms[6]`；termStat idf 8B；tf key = `FarmHash64.mix4(uid,docId,parentId,level)`。
- **排序 → 零重建**：三列均按 key 升序落盘，读取时在 mmap 上 `ByteBuffer` 二分（`binarySearch(key)`），**无需任何启动期索引构建**。
- **压缩收益点**：动态区（termStat payload / term vector）LZ4 逐页压缩；定长列区可选 delta+varint 后 LZ4（首版定长列直存 + 段级 LZ4 由 T2 决策，见 §5.2 T2 说明）。

### 3.3 包结构（新增 `segment/` 子包）

```
com/q3lives/ds/database/fulltext/
├── FulltextSegmenter.java          (P21 既有，0 改)
├── FulltextStore.java              (P21 既有，0 改)
├── TermDictStore.java              (P21 既有，0 改)
├── cluster/                        (P26~P36 既有，0 改)
└── segment/                        (P37 新增子包)
    ├── DsIndexSegment.java              (NEW)
    ├── SegmentColumnCodec.java          (NEW)
    ├── DsIndexSegmentWriter.java        (NEW)
    ├── DsIndexSegmentReader.java        (NEW)
    └── DsIndexSegmentStore.java         (NEW)
```

---

## 4. 接口契约

### 4.1 DsIndexSegment 不可变 POJO

```java
public final class DsIndexSegment {
    public static final byte[] MAGIC = {'D', 'I', 'D', 'X'};
    public static final int VERSION = 1;
    public final int version;
    public final long docCount;
    public final long termCount;
    public final long tfCount;
    public final long docStatOff, docStatLen;
    public final long termStatOff, termStatLen;
    public final long tfOff, tfLen;
    public final long varOff, varLen;
    public final int[] crc32;   // [docStat, termStat, tf, var]
    // 5 IAE 守卫: 各 count ≥ 0；各 off/len ≥ 0；crc32.length == 4；version == VERSION
    public String fingerprint();  // SHA-256（定长列区摘要 + 头字段）
}
```

### 4.2 SegmentColumnCodec

```java
public final class SegmentColumnCodec {
    public static final int DOC_STAT_REC = 48;   // docLen[6]+nTerms[6]
    public static final int TERM_STAT_REC = 24;  // uid+idf+payloadOff+payloadLen
    public static final int TF_REC = 12;         // key+tf
    public static byte[] packDocStat(long[] docIds, int[][] docLen, int[][] nTerms); // 按 docId 升序
    public static byte[] packTermStat(long[] uids, double[] idf, int[] payloadOff, int[] payloadLen);
    public static byte[] packTf(long[] keys, int[] tfs);                             // 按 key 升序
    public static byte[] packDynamic(byte[][] payloads, int pageSize);               // 逐页 LZ4
    public static byte[] decompressPage(byte[] page);                                // 复用 ColumnCompression
    // 5 IAE 守卫: 数组长度一致 / 非 null / pageSize ≥ 4096 / 升序校验 / 记录数 ≥ 0
}
```

### 4.3 DsIndexSegmentWriter

```java
public final class DsIndexSegmentWriter {
    public DsIndexSegmentWriter(File target) throws IOException;
    public void addDocStat(long docId, int[] docLen, int[] nTerms) throws IOException;      // docLen/nTerms 长度=6
    public void addTermStat(long uid, double idf, byte[] payload) throws IOException;
    public void addTf(long key, int tf) throws IOException;
    public DsIndexSegment finish() throws IOException;  // 排序 + 列式 + LZ4 + 4KB 对齐 + CRC + 原子落盘
    // 6 IAE 守卫: docId ≥ 0 / docLen.length==6 / nTerms.length==6 / tf ≥ 0 / idf 有限 / finish 幂等
}
```

### 4.4 DsIndexSegmentReader

```java
public final class DsIndexSegmentReader implements AutoCloseable {
    public static DsIndexSegmentReader open(File segFile) throws IOException;  // mmap 头 + 段表（零重建）
    public FulltextStore.WeightDocStatRow getDocStat(long docId) throws IOException;   // 二分，miss=null
    public FulltextStore.WeightTermStatRow getTermStat(long uid) throws IOException;   // 二分 + 按需解压 payload
    public int getTf(long key) throws IOException;                                     // 二分，miss=0
    public long[] scanTfPrefix(long keyPrefixHighBits, long keyPrefixMask) throws IOException; // 范围扫描
    public long docCount(); public long termCount(); public long tfCount();
    public void close() throws IOException;   // unmap
    // 4 IAE 守卫: segFile 非 null / 文件 length ≥ 4096 / magic 匹配 / version 匹配
}
```

### 4.5 DsIndexSegmentStore

```java
public final class DsIndexSegmentStore implements AutoCloseable {
    public DsIndexSegmentStore(String rootDir) throws IOException;   // rootDir/segment/ + manifest
    public DsIndexSegment append(DsIndexSegment seg) throws IOException;  // 原子 rename 进 store + manifest 追加
    public DsIndexSegment load(long segId) throws IOException;
    public long[] list() throws IOException;
    public void remove(long segId) throws IOException;
    public DsIndexSegmentReader openReader(long segId) throws IOException;
    public void close() throws IOException;
    // 5 IAE 守卫: rootDir 非 null/空 / segId ≥ 0 / 段不存在 IAE / manifest 损坏 IAE / 重复 append IAE
}
```

---

## 5. 测试（Gate B 拆 4 批：P22-P30 + P31-P37）

### 5.1 Surefire 4 批拆分（继承 P36 模板）

```powershell
# 批1: P22-P24
mvn surefire:test '-Dtest=ds.P22_*,ds.P23_*,ds.P24_*'
# 批2: P25-P27
mvn surefire:test '-Dtest=ds.P25_*,ds.P26_*,ds.P27_*'
# 批3: P28-P30
mvn surefire:test '-Dtest=ds.P28_*,ds.P29_*,ds.P30_*'
# 批4: P31-P37 (含 P37 own)
mvn surefire:test '-Dtest=ds.P31_*,ds.P32_*,ds.P33_*,ds.P34_*,ds.P35_*,ds.P36_*,ds.P37_*'
```

### 5.2 TR 总数

| T | 范围 | TR 数 | 实装内容 |
|---|------|------|---------|
| **T1** | DsIndexSegment + SegmentColumnCodec skeleton | 5 | 段 POJO 5 IAE + fingerprint；Codec 三列 pack/unpack + LZ4 动态区 + 5 IAE |
| **T2** | DsIndexSegmentWriter + DsIndexSegmentStore | 6 | 写段（排序/列式/压缩/页对齐/CRC/原子落盘）+ 6 IAE；store manifest list/load/append/remove + 5 IAE |
| **T3** | DsIndexSegmentReader mmap 零重建 | 3 | open（mmap 头 + 段表，0 源扫描）+ 三列二分查询 + 段格式/CRC 损坏 IAE |
| **T4** | Inproc E2E 冷启动 parity | 3 | WeightStore vs Segment 全量统计逐条一致 + 二次 open 零重建（源扫描探针=0）+ 压缩率/冷启动 P95 达标 |
| **T5** | Gatekeeper 9Y | 9 | Y1-Y9 全 Y（含 ZeroLeak3 反射收集 count=3 + P21/P22 源码 0 改 grep + pom 0 diff） |
| **T6** | NFR | 5 | 段体积压缩率 ≤ 阈值 + 冷启动 P95 ≤ 阈值 + 5 类 Javadoc ≥ 10 行 + CPD ≤ 1 + 0 println + IAE ≥ 8 |

**TR 总数**：5+6+3+3+9+5 = **31 TR**

### 5.3 验收门槛
- P37 own 31/31 0F0E
- P22-P37 拆 4 批全量回归 0F0E（批4 P31-P37 = 224/224；总计 480 TR = 479 0F0E + 1 已知基线 fail），无 P37 引入 fail
- **统计 parity**：`DsIndexSegmentReader` 对同源数据返回的 `docStat/termStat/tf` 与 P22 `WeightStore` **逐条全等**（含 idf 双精度 bit 级）
- **零 startup 重建**：段 open 期间 P21/P22 源扫描计数器 = 0（不触 `idx1/idx2/idx3`、不重算 idf）
- **压缩达标**：段文件总体积 ≤ 未压缩列式体积 × 目标比（T6 阈值，实测填实锤）
- 段损坏（magic/version/CRC）→ IAE，绝不静默返回脏数据

---

## 6. 逆指标（Anti-Metrics，避免的伪优化）

| 逆指标 | 错误做法 | 正确做法 |
|--------|----------|----------|
| 假零重建 | open 时把段读进堆建 HashMap | mmap + 定长列二分，堆内 0 索引构建 |
| 压缩无收益硬压 | 小 payload 也走 LZ4 头开销 | `ColumnCompression.compress` 已自带「压缩后不省则返回 null」 |
| 段内多份真相 | 段同时存 hash 索引 + 列 | 只存 3 定长列 + 动态区，靠二分定位 |
| 触碰红线类 | 改 FulltextStore/WeightStore 加探针 | 段模块独立；parity 靠外部喂入同源数据 + 反射比对 |
| 破坏确定性 | 段列非稳定排序 | 三列严格按 key 升序，fingerprint 可复现 |
| 顺手 compaction | MVP 引入段合并/后台压缩线程 | 段不可变 append-only；compaction 明确排除 |

---

## 7. 风险与回退

| 风险 | 触发 | 回退 |
|------|------|------|
| mmap 段无法卸载（Windows 文件锁） | T3/T4 remove 段时 `close` 失败 | 用既有 unmapper 反射（`NewClass.java` 同款）强制 unmap + 重试删 |
| 定长列二分 key 重复 | 同 docId/uid/key 二次 add | writer 在 `finish` 前**去重合并**（同 key 覆盖），与 WeightStore 覆盖语义一致 |
| LZ4 逐页边界跨页 payload | 单 payload > pageSize | 动态区 page 按「不跨页」切分，超页 payload 独占多页 + 长度前缀 |
| CRC 计算开销大 | 大段 finish 慢 | CRC32 用 JDK 内置 `java.util.zip.CRC32`，仅 4 段块级校验 |
| 段格式升级不兼容 | 未来 VERSION=2 | R7 硬校验 version，不匹配 IAE + 旧段不回读（re-generate） |
| 冷启动收益不达阈值 | T6 P95 超标 | 定长列改 delta+varint + 段级 LZ4（T2 已预留），或调 pageSize |

---

## 8. 交付物清单

- `.trae/specs/p37-oq1-bm25-index-segment-persistence/spec.md`（本文件）
- `.trae/specs/p37-oq1-bm25-index-segment-persistence/tasks.md`（Gate 三状态 + 6 阶段 TR 明细）
- `p2p-db/src/main/java/com/q3lives/ds/database/fulltext/segment/*.java`（5 新类：`DsIndexSegment` / `SegmentColumnCodec` / `DsIndexSegmentWriter` / `DsIndexSegmentReader` / `DsIndexSegmentStore`）
- `p2p-db/src/test/java/ds/P37_T1_*.java` ~ `P37_T6_*.java`（6 测试文件 × 31 TR）
- **0 改**：`FulltextStore.java` / `WeightStore`（P22）/ `pom.xml` / P23~P36 任意源文件

---

## 9. 签批区（Gate A REV1 用户 VERBATIM 批准）

- [x] 用户批准 OQ12-⑮ 候选项 ① P37 BM25 Index Segment 压缩 + 持久化（Scope 锚定 above）
- [x] 确认「P21/P22 源码 0 改 + 段模块独立 additive」收紧实装口径
- [x] 确认「0 新增 Maven dep（复用 lz4-java + ColumnCompression）」
- [x] 确认 5 新类落 `fulltext/segment/` 子包（ZeroLeak3=3 永保）

---

## 10. 实装偏差说明（REV1 → REV2，Gate C 回填）

| 项 | REV1 计划 | REV2 实装 | 说明 |
|----|-----------|-----------|------|
| docStat 记录宽 | §3.2/§4.2 写 48B | **56B**（key 8B + payload 48B = `DOC_STAT_KEY=8` + `DOC_STAT_PAYLOAD=48` + `DOC_STAT_REC=56`） | §3.2 图漏算 8B 键宽；实装以「键+负载」整记录 56B 为准（R9 4KB 对齐不变） |
| `SegmentColumnCodec` API | `packDocStat(rows)` / `unpackDocStat` / `decompressPage` | `packDocStat(long[],int[][],int[][])` / `packTermStat` / `packTf` / `packDynamic(byte[][],int)→Dynamic` / `readVar` / `binarySearchKey` / `writeHeader` / `readHeader` / `crc32` / `checkStrictAscending` | 动态区改为「逐条 payload 定位 off/len + 逐块 5B 帧头（1B flag + 4B storedLen）」；mmap 读取走 `binarySearchKey` 二分而非 unpack 全量 |
| `DsIndexSegmentStore.append` | §4.5 `append(DsIndexSegment seg)` | `append(long segId, DsIndexSegment seg):DsIndexSegment` | 显式 segId + 从磁盘段头解析回读对象（比对 fingerprint） |
| 段文件命名 | 未指定 | `seg-<segId>.didx` + `manifest`（一行一 segId） | 文本 manifest 便于 list/remove |
| Reader 查询签名 | §4.4 带 `throws IOException` | 查询方法**无** `throws`（仅 `open`/`store` 相关抛 IOException） | 查询为纯 mmap 只读，IAE 覆盖损坏路径 |
| 压缩范围 | §3.2 提到「定长列可选 delta+varint」 | 定长列**直存**（不 delta+varint），压缩**仅**动态区逐块 LZ4 | 遵循「压缩无收益硬压」逆指标 + ColumnCompression 自带不省则 null |
| T6 压缩阈值 | 「实测填实锤」 | 高可压缩 payload（20KB'x' + 16KB'a'）段体积 ≤ 未压缩等价 × **50%** | `P37_T6.tr61` 实锤 |
| T6 冷启动阈值 | 「实测填实锤」 | open P95 ≤ **200ms**（200 docStat） | `P37_T6.tr62` 实锤 |

**核心不变量 100% 达成（REV1 → REV2 无偏离）**：ZeroLeak3=3 保 3；NG-3 0 新 dep；P21/P22/pom 0 改；段自描述（magic+version+4 CRC）；三列严格按 key 升序二分；零 startup 重建（open 不扫源、不建堆哈希、不重算 idf）；parity 逐条全等（idf bit 级）。
