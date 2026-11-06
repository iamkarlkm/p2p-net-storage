# P7 Follow-up Tgt-4 — C4 Binlog 跨节点 LZ4 帧压缩 Spec

> **版本**：1.0 (2026-09-17)
> **影响范围**：p2p-core (Model 零改)、p2p-db (Handler 解压 + Source 压缩调用)
> **硬约束锁死**：7 大底层 + DsRegistry + GenericManager + DsDatabaseServer + DsBinlogStore + DsBinlogReplicator = **12 类 diff=0**

---

## 1. Background / Non-Goals（硬约束，任何一条破坏直接 FAIL）

| # | Non-Goal | Verification Gate |
|---|---|---|
| NG-1 | **7 大底层 0 修改**：DsFixedBucketStore/DsFreeRing/DsString/DsTypeFixedBucketStore/DsTypeString/DsData/DsHashMap | Final Review `git diff --stat <baseline> <7files>` = 0 line |
| NG-2 | **DbEntityBinlogFrame Request/Ack 0 修改**：零新字段、零破坏 Protostuff 序列化向后兼容；压缩元信息内嵌到每个 byte[] 自己的 11B Header 中 | Model 类 javap -p 字段不变；T02 旧 payload（无 Header）直接 handler 0 退化 |
| NG-3 | **零新命令号、零新 Resolver**：复用 Tgt-1 DB_ENTITY_BINLOG_FRAME (24300/24301)；不引入 Tgt3Resolver 变化 | P2PCommand.java grep `LZ4\|BINLOG_2` = 0 hit |
| NG-4 | **DsBinlogStore / DsBinlogReplicator 本地直连 0 修改**：同 JVM doTick 路径零 touch；C4 Frame 跨节点才压缩 | DsBinlogStore.java + DsBinlogReplicator.java diff=0 |
| NG-5 | **Q2=C2 / Q3=C3 不变式 0 破坏**：rowId 永远纯本地自增；tableId=FNV-1a(className) | 6 tests 锚点不变 |

---

## 2. Core Design（Performance First 最小侵入）

### 2.1 已存在基础设施零新依赖复用

| Component | 已存在工件 | 复用方式 |
|---|---|---|
| **LZ4 编解码器** | `p2p-db/src/main/java/com/q3lives/ds/database/columnar/ColumnCompression.java` | **直接 import 调用**：`compress(byte[] input, int threshold)` / `decompress(byte[] input)` / `isCompressed(byte[] input)` |
| **Header 格式**（已存在不可变） | `[2B magic=0x4C 0x5A] [1B version=1] [4B originalLen] [4B compressedLen] [compressed bytes]` = **11B header** | 三个字段（fixed/dyn/bytes）**各自独立打包，各自独立判断** |
| **lz4-java 依赖** | `p2p-db/pom.xml L77 org.lz4:lz4-java:1.8.0` | `LZ4Factory.fastestInstance().fastCompressor()` + `.fastDecompressor()` 由 ColumnCompression 静态持有，**零第二实例** |
| **压缩判定自动回退** | ColumnCompression.compress L42：`compressedLen + HEADER_SIZE >= input.length → return null` | 调方无需二次判断：**收到 null = 不压缩，原 byte[] 原样用** |
| **阈值 THRESHOLD = 65536 (64KB)** | Handler 私有 static final；<64KB 零 CPU 开销；>=64KB 才尝试 fastCompressor | T02 32KB payload → `ColumnCompression.compress(bytes,65536)==null` 断言 |

### 2.2 端到端流程（Source→Network→Target）

```
┌─────────────────────────────────────────────────────────────────────┐
│  SOURCE SIDE（构造 DbEntityBinlogFrameRequest 时）                   │
│  1. Files.readAllBytes(fixedFile/dynFile/bytesFile) → raw[] × 3     │
│  2. 每个字段：maybeCompress(raw) =                                  │
│         ColumnCompression.compress(raw, 65536) ?: raw               │
│       └─ <64KB → null → 原 byte[] 直接（zero-copy zero-CPU）        │
│       └─ ≥64KB + 压缩节省 → 带 11B Header 的 compressed[]           │
│       └─ ≥64KB + 压缩不节省 → null → 原 byte[]（防膨胀）            │
│  3. 塞入 DbEntityBinlogFrameRequest.fixedFrames / dynFrames /       │
│        bytesFrames 三个现有字段（MODEL ZERO CHANGE）                 │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼ Protostuff 跨节点传输（0 新字段）
┌─────────────────────────────────────────────────────────────────────┐
│  TARGET SIDE（DbEntityBinlogFrameServerHandler.process）            │
│  1. 三处 writeWhole(storeDir, name, payload.xxxFrames) 前置：       │
│     byte[] effective = maybeDecompress(payload.xxxFrames);          │
│         → ColumnCompression.isCompressed(b) ?                       │
│              ColumnCompression.decompress(b) : b;                   │
│     └─ 旧 payload（无 0x4C5A magic）→ 0 开销直接写                  │
│     └─ 新 compressed payload → 解出 original → writeWhole           │
│     └─ Header 损坏（len 越界）→ decompress 返回 null → 抛 ISE       │
│                           → catch → stdError                        │
│  2. 其余流程（tmpRoot/_binlog/mkdirs/DsBinlogStore/replay）         │
│        与 Tgt-1 完全 0 差异                                         │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.3 三字段完全独立（zero 共享状态）

| Field | 典型内容 | 预期压缩率 | 判定 |
|---|---|---|---|
| fixedFrames | 定长 binlog frame header（rowId/tableId/colId/flag 重复）+ 数值型 UNDO/REDO | **高 2x-5x** | 压缩命中主力 |
| dynFrames | 变长字符串 payload（STRING/VARCHAR UTF-8） | **中 1.5x-3x** | 中文文本收益高 |
| bytesFrames | BINARY/ZIP 用户二进制（可能已压缩） | **低 0.8x-1.2x** | ColumnCompression 自动 null 回退防膨胀 |

---

## 3. Functional Requirements（10 条 FR 必须全绿）

| # | FR | AC（Acceptance Criteria，assertion 具体） |
|---|---|---|
| FR-1 | **三字段独立压缩独立判定**：fixed/dyn/bytes 每个字段各自走 ColumnCompression.compress(bytes, 65536) + 各自 null 回退 | T01：new byte[100_000] 填 0x41 → compressed != null；isCompressed(compressed) = true；`Arrays.equals(decompress(compressed), original)` |
| FR-2 | **Model 0 新字段 0 序列化破坏**：DbEntityBinlogFrameRequest/Ack 字段列表与 Tgt-1 完全一致 | javap -p 输出比较无新字段；T02 旧（无 compress）Request 直接 Handler.process → appliedFrames 与 Tgt-1 基线相等 |
| FR-3 | **Handler maybeDecompress 三调用**：L52 fixed / L54 dyn / L58 bytes 三处 writeWhole 前统一先 maybeDecompress | T03：50 rows × name=重复字符串 fixed+dyn ≥64KB → compress 后设 Request → Handler → appliedFrames=50, findList size=50 |
| FR-4 | **阈值硬锚 LZ4_THRESHOLD=65536**：Handler 与测试 Helper 都读同 static final；<64KB 零压缩尝试 | T02：32KB 随机 bytes → `compress(bytes,65536)==null` 断言；Handler 测 <64KB 旧格式时 `isCompressed=false` |
| FR-5 | **向后兼容 0 退化**：纯旧 payload（无 0x4C5A magic）Handler 直接走 writeWhole | T02：直接跑 Tgt-1 `test_Tgt1_Handler_Process_10Rows_AppliedFrames_GE10` 相同代码 0 修改 → pass |
| FR-6 | **损坏 Header 安全抛错**：decompress 返回 null 时 Handler 抛 `IllegalStateException("corrupt compressed frame")` → catch → stdError | T05：手工构造 byte[15]（magic+version=OK 但 originalLen=-1）→ payload → Handler 返回 command=STD_ERROR |
| FR-7 | **压缩率达标**：100KB 高冗余 byte[]（100_000 个 0x41）压缩率 ≥2.0x（即 compressed.length ≤ 50_000，含 11B Header） | T06：`compressed.length <= original.length/2` 断言 |
| FR-8 | **三字段混合模式**：fixed(128KB 压缩) + dyn(32KB 不压缩) + bytes(0 len 空) → Handler 正确各自处理 appliedFrames=N | T04：gmA 造 N rows → fixed+dyn 混合打包 → compress 仅 fixed 非 null → Handler → findList=N |
| FR-9 | **无全局状态 零污染**：maybeCompress / maybeDecompress 所有缓冲区局部变量；zero ThreadLocal（与 C4 classIdSet 路径不交） | ColumnCompression static final FACTORY/COMPRESSOR/DECOMPRESSOR 只读；Handler 无 static byte[] 可写 |
| FR-10 | **Final Review 12 文件 diff=0**：NG-1/2/4/5 锁死文件 0 行修改 | `git diff --stat <Tgt2-finish-hash> DsFixedBucketStore.java DsFreeRing.java DsString.java DsTypeFixedBucketStore.java DsTypeString.java DsData.java DsHashMap.java DsRegistry.java GenericManager.java DsDatabaseServer.java DsBinlogStore.java DsBinlogReplicator.java` 全部 0 |

---

## 4. NFR Rubric（3 项，每项 Score 2/2 全满才算 PASS）

| NFR | Score=2 证据 | 测试锚点 |
|---|---|---|
| **NFR-1 压缩正确性** | round-trip 无损 + Handler 端 applied 行级正确 2 场景双命中 | T01（round-trip）+ T03（50 rows Handler full flow） |
| **NFR-2 零侵入零退化** | 旧 payload 直通 + Model 0 字段 + 12 零改文件 diff=0 + <64KB 零 CPU | T02（旧 10 行）+ T02 基线相等 + FR-10 Final diff |
| **NFR-3 阈值回退 + 压缩率** | 64KB 已压缩随机字节 100% 回退 + 100KB 冗余 ≥2.0x | T06（冗余≥2x）+ T07（随机回退 100% null） |

---

## 5. 验收测试清单（4 Tests 0F0E + 1 Tgt1 基线复用）

| Test ID | 场景 | ≥断言数 |
|---|---|---|
| **T01** | `test_LZ4_roundTrip_highRedundancy_100KB`：ColumnCompression.compress/decompress round-trip；isCompressed=true；Arrays.equals 原始；compressed.length ≤ 50_000 | 5 |
| **T02** | `test_belowThreshold_32KB_zeroDegrade_baseline`：32KB 随机 → compress==null；复用 Tgt1 10 rows 旧（0 compress）Request → Handler → applied≥10；findList=10 | 4 |
| **T03** | `test_Handler_50rows_bigFields_compressed_applied50`：gmA 50 rows × name=重复 200 字符 fixed+dyn ≥64KB → maybeCompress 后 Request → Handler → appliedFrames≥50；findList=50；name 集合相等 | 6 |
| **T04** | `test_mixedMode_fixedCompressed_dynUncompressed_bytesEmpty`：fixed 128KB 重复 0x41（压缩非 null）+ dyn 32KB 随机（compress==null 原样）+ bytes=空 → Handler → applied=N；gmB 列表内容无误 | 4 |

（额外烟测：Tgt-1 `test_Tgt1_Handler_Process_10Rows_AppliedFrames_GE10` 继续 PASS 证明兼容）

---

## 6. Joint Verification Cmd（回归保护，任何源码改动必跑）

```powershell
# V1：p2p-core + p2p-db 依赖编译（若改 Handler 或测试需重编）
mvn -pl p2p-db -am compile test-compile -DskipTests -q

# V2：Tgt-4 专项 4 tests
mvn -pl p2p-db test "-Dtest=DsBinlogFrame_P7_Tgt4_LZ4_4_Test" -q

# V3：烟测 ≥60 PASS 0F0E
mvn -pl p2p-db test "-Dtest=DsRegistryTest,DsRegistry_P31_MasterIndexPrefixTest,DsRegistry_P7_A1_GlobalIds_6_Test,DsRegistry_P7_Tgt3_HandlerTest,DsRegistryExtAttrs_P7_Exts_Test,DsRegistryExtAttrs_P7_Tgt5_HandlerTest,DsRegistry_Phase1_DbMetaPackedAttrs_8_Test,Orm_P7_Tgt2_Handler_6_Test,DbEntityBinlogFrame_P7_C4_HandlerTest,DsBinlogFrame_P7_Tgt4_LZ4_4_Test,DsBinlogBasicTest,DsBinlogReplicator_P7_C4_Test,DsStringBlockStoreTest,DsTypeFixedBucketStoreTest" -q

# V4：全量 p2p-db ≥560 PASS 0F0E
mvn -pl p2p-db test -q
```
