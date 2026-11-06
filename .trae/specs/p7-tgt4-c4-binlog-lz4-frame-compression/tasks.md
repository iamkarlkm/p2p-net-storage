# P7 Tgt-4 C4 LZ4 Binlog Frame Compression — Tasks（8 Tasks，每 Task 绑定 TR→AC）

> Baseline Commit（Tgt-2 Final 10 工件全绿）：53a382e39827 之后的 HEAD（Tgt-2 已 archive）
> 执行顺序：Task1→Task2→Task3→Task4→Task5→Task6→Task7→Task8（串行严格）

---

| Task ID | Task Description | Bound TR | Verification Cmd / Assertion | Exit Gate |
|---|---|---|---|---|
| **Task 1** | **Handler 新增 maybeDecompress 私有 helper**：DbEntityBinlogFrameServerHandler.java 尾部加 `private static byte[] maybeDecompress(byte[] b)`：`if (ColumnCompression.isCompressed(b)) { byte[] r = ColumnCompression.decompress(b); if (r==null) throw new ISE("corrupt"); return r; } return b;`；三处 writeWhole(L52/L54/L56 bytes) 前 byte[] eff = maybeDecompress(payload.xxxFrames)；再 writeWhole(..., eff) | FR-3 / FR-5 / FR-6 / NFR-2 | `mvn -pl p2p-db -am compile -DskipTests -q` exit 0；Handler.java grep `maybeDecompress` 3 处调用 hit | 编译 exit 0 + import ColumnCompression 无误 |
| **Task 2** | **新增测试侧 BinlogFrameCompressHelper**（p2p-db test 包，ds 下）：public static 方法 `byte[][] maybeCompressAll(byte[] fixed, byte[] dyn, byte[] bytes, int threshold)` → 三个字段分别调 `ColumnCompression.compress(x, threshold) ?? x`；返回 `[f,d,b] length=3`；零写入 bucket，纯 ALU | FR-1 / FR-4 / NFR-1 / NFR-3 | `mvn -pl p2p-db test-compile -DskipTests -q` exit 0 | 编译 exit 0 |
| **Task 3** | **T01 round-trip 单测**：`test_LZ4_roundTrip_highRedundancy_100KB` 100_000 byte 填 0x41 → ColumnCompression.compress(data, 65536)；assert != null；assert isCompressed；assert compressed.length ≤ 50_000；decompress → Arrays.equals 原 data；再 new Random().nextBytes(new byte[32768]) → compress(32KB, 65536) == null | FR-1 / FR-4 / FR-7 / NFR-1 / NFR-3 | mvn surefire T01 单独 PASS 0F0E | `mvn -pl p2p-db test "-Dtest=DsBinlogFrame_P7_Tgt4_LZ4_4_Test#test_LZ4_roundTrip_highRedundancy_100KB" -q` |
| **Task 4** | **T02 基线兼容**：复用 Tgt-1 10 rows 构造（10 UserC4 → 读 binlog → Request **不压缩** → Handler）→ applied≥10；findList=10；name 集合 match；同时断言 32KB 随机 bytes → compress(32KB,65536)==null | FR-2 / FR-5 / NFR-2 | mvn surefire T02 PASS | `mvn -pl p2p-db test "-Dtest=DsBinlogFrame_P7_Tgt4_LZ4_4_Test#test_belowThreshold_32KB_zeroDegrade_baseline" -q` |
| **Task 5** | **T03 全链路 50 rows 大字段压缩**：gmA 50 rows × name=`"a".repeat(200)+i` → fixed+dyn ≥64KB → BinlogFrameCompressHelper 压缩 → new Request 塞 compressed bytes → Handler.process → applied≥50；gmB.findList size=50；Set\<name\> 相等；assert 压缩后 fixed.length < 原 fixed.length（压缩生效非回退） | FR-3 / FR-7 / FR-9 / NFR-1 | mvn surefire T03 PASS 0F0E | `mvn -pl p2p-db test "-Dtest=DsBinlogFrame_P7_Tgt4_LZ4_4_Test#test_Handler_50rows_bigFields_compressed_applied50" -q` |
| **Task 6** | **T04 混合模式 fixed压缩+dyn不压缩+bytes空**：造 N rows → fixed 读原 → `byte[] bigPadding = new byte[131072]; Arrays.fill(bigPadding, (byte)'A');` 拼到 fixedDummy 后 compress → fixed compressed 非 null；dyn 32KB 随机原样；bytes=空 → Request → Handler → applied=N；gmB 列表内容 match（padding 不影响 replay，因真实 frame 是 [0..origLen)） | FR-8 / NFR-2 / NFR-3 | mvn surefire T04 PASS 0F0E | `mvn -pl p2p-db test "-Dtest=DsBinlogFrame_P7_Tgt4_LZ4_4_Test#test_mixedMode_fixedCompressed_dynUncompressed_bytesEmpty" -q` |
| **Task 7** | **烟测 ≥60 PASS 0F0E BUILD SUCCESS**：Tgt-4 4 + Tgt-2 6 + Tgt-1 1 + Phase1 6 + Tgt-3 4 + Tgt-5 7 + ExtAttrs 12 + DsRegistry 14 + P31 5 + C4 1 + Binlog 2 + DsString 1 + DsType 1 = ≥62 PASS | FR-2 / FR-5 / NFR-2 | `mvn -pl p2p-db test "-Dtest=DsRegistryTest,DsRegistry_P31_MasterIndexPrefixTest,DsRegistry_P7_A1_GlobalIds_6_Test,DsRegistry_P7_Tgt3_HandlerTest,DsRegistryExtAttrs_P7_Exts_Test,DsRegistryExtAttrs_P7_Tgt5_HandlerTest,DsRegistry_Phase1_DbMetaPackedAttrs_8_Test,Orm_P7_Tgt2_Handler_6_Test,DbEntityBinlogFrame_P7_C4_HandlerTest,DsBinlogFrame_P7_Tgt4_LZ4_4_Test,DsBinlogBasicTest,DsBinlogReplicator_P7_C4_Test,DsStringBlockStoreTest,DsTypeFixedBucketStoreTest" -q` exit 0 + 控制台 ≥62 Tests passed | BUILD SUCCESS + Tests ≥62 |
| **Task 8** | **归档 + Brain**：① AGENTS Invariant 追加 Tgt-4 8 条（阈值/flag位段内嵌Header/originalLen内嵌/解压器单例/最小payload/零压缩回退防膨胀/跨天混合帧/多实例复用ColumnCompression/Model0改12文件diff=0）；② current-state 追加 VERIFIED CLOSED Block 10 工件 + Joint Verification Cmd 4 条；③ `brain context audit --since <baseline>`；④ `brain session run` 记录 verify；⑤ `brain distill --session` 出 proposal；⑥ `brain session finish` exit 0；⑦ Final Review 12 零改文件 diff=0 手工核对 | FR-10 / NFR-2 / 项目流程 | AGENTS grep 8 Invariant 非空；current-state Block 存在；brain session finish exit 0；Final diff Review=pass | Invariant 8 hit + Session exit 0 + Review Result=pass |

---

## Gate 跨任务故障拦截（任何一步 FAIL 不进下一 Task）

- Task1：Handler import 找不到 ColumnCompression → 核对 pom lz4-java 1.8.0 是否存在
- Task3/4/5/6：单测 applied=0 → warm-up 失败（C4 classIdSet root cause）→ 必须 gmB.saveOrUpdate(dummy)+remove 先 warm
- Task7：烟测 <62 → 缺类名追加到 -Dtest
- Task8：brain session finish exit 非 0 → 检查缺失 verify 命令重新 session run
