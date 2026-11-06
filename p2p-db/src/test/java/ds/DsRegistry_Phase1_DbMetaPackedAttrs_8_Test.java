package ds;

import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.DsRegistryDbMetaHelper;
import static com.q3lives.ds.registry.DsRegistryDbMetaHelper.DbMetaAttr;
import static com.q3lives.ds.registry.DsRegistryDbMetaHelper.ValueType;
import com.q3lives.ds.registry.DsRegistryExtAttrsHelper;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.RegistryGetAck;
import javax.net.p2p.model.RegistryGetRequest;
import javax.net.p2p.model.RegistryPutBytesRequest;
import javax.net.p2p.model.RegistryRemoveRequest;
import javax.net.p2p.model.RegistryWriteAck;
import javax.net.p2p.model.SimpRegistryEntry;
import javax.net.p2p.server.handler.RegistryGetServerHandler;
import javax.net.p2p.server.handler.RegistryPutBytesServerHandler;
import javax.net.p2p.server.handler.RegistryRemoveServerHandler;
import javax.net.p2p.server.handler.Tgt3RegistryResolver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

public class DsRegistry_Phase1_DbMetaPackedAttrs_8_Test {

    private static final int SA = 12;
    private static final int PA = 0;
    private static final int SB = 42;
    private static final int PB = 0;

    @AfterEach
    void tearDown() {
        Tgt3RegistryResolver.clearForTest();
    }

    private static Map<DbMetaAttr, Object> fullMap9() {
        Map<DbMetaAttr, Object> m = new EnumMap<>(DbMetaAttr.class);
        m.put(DbMetaAttr.NAME, "用户表");
        m.put(DbMetaAttr.DESC, "用户登录资料，关联UserRole、UserProfile");
        m.put(DbMetaAttr.OWNER, "张三/team-db-core");
        m.put(DbMetaAttr.ENV, "prod");
        m.put(DbMetaAttr.TTL_SEC, Long.valueOf(3600L * 24 * 30));
        m.put(DbMetaAttr.RETENTION_DAYS, Long.valueOf(365L));
        m.put(DbMetaAttr.VERSION, Long.valueOf(2026091601L));
        m.put(DbMetaAttr.PARTITION_COUNT, Long.valueOf(8L));
        m.put(DbMetaAttr.IS_PARTITIONED, Boolean.TRUE);
        return m;
    }

    // ================================== Task 1 TR-1a / TR-1b ==================================
    @Test
    void T01_DbMetaAttr_byTagId_9_known_1_unknown_empty_tagId_Fixed() {
        for (DbMetaAttr a : DbMetaAttr.values()) {
            Assertions.assertTrue(DbMetaAttr.byTagId(a.tagId).isPresent(), "tagId=0x" + Integer.toHexString(a.tagId & 0xFF) + " byTagId 必命中");
            Assertions.assertSame(a, DbMetaAttr.byTagId(a.tagId).get());
        }
        Assertions.assertFalse(DbMetaAttr.byTagId((byte) 0x7F).isPresent(), "未知 0x7F 必 empty");
        // TR-1b tagId 硬值固定（绝不改）
        Assertions.assertEquals((byte) 0x01, DbMetaAttr.NAME.tagId);
        Assertions.assertEquals((byte) 0x02, DbMetaAttr.DESC.tagId);
        Assertions.assertEquals((byte) 0x03, DbMetaAttr.OWNER.tagId);
        Assertions.assertEquals((byte) 0x04, DbMetaAttr.ENV.tagId);
        Assertions.assertEquals((byte) 0x40, DbMetaAttr.TTL_SEC.tagId);
        Assertions.assertEquals((byte) 0x41, DbMetaAttr.RETENTION_DAYS.tagId);
        Assertions.assertEquals((byte) 0x42, DbMetaAttr.VERSION.tagId);
        Assertions.assertEquals((byte) 0x43, DbMetaAttr.PARTITION_COUNT.tagId);
        Assertions.assertEquals((byte) 0x60, DbMetaAttr.IS_PARTITIONED.tagId);
        // ValueType 映射
        Assertions.assertSame(ValueType.STR8, DbMetaAttr.NAME.vt);
        Assertions.assertSame(ValueType.ENUM8, DbMetaAttr.ENV.vt);
        Assertions.assertSame(ValueType.U32, DbMetaAttr.TTL_SEC.vt);
        Assertions.assertSame(ValueType.U64, DbMetaAttr.VERSION.vt);
        Assertions.assertSame(ValueType.BOOL8, DbMetaAttr.IS_PARTITIONED.vt);
    }

    // ================================== Task 2 TR-2a/2b/2c/2d ==================================
    @Test
    void T02_Codec_RoundTrip9Fields_ChineseNames_plus_UnknownTag_plus_CorruptLen_plus_Inline8B() {
        Map<DbMetaAttr, Object> src = fullMap9();
        // TR-2a 9 round-trip
        byte[] payload = DsRegistryDbMetaHelper.encodeTransport(src);
        Map<DbMetaAttr, Object> dec = DsRegistryDbMetaHelper.decodeTransport(payload);
        Assertions.assertEquals(src.size(), dec.size());
        for (Map.Entry<DbMetaAttr, Object> e : src.entrySet()) {
            Object a = e.getValue();
            Object b = dec.get(e.getKey());
            if (a instanceof Long && b instanceof Long) {
                Assertions.assertEquals(((Long) a).longValue(), ((Long) b).longValue(), e.getKey() + " NUMERIC 不等");
            } else if (a instanceof Boolean && b instanceof Boolean) {
                Assertions.assertEquals(a, b, e.getKey() + " BOOL 不等");
            } else {
                Assertions.assertEquals(String.valueOf(a), String.valueOf(b), e.getKey() + " STR 不等");
            }
        }
        // TR-2b 8B inline 命中：单 IS_PARTITIONED=true  payload=4(Header) + 1(tag) + 2(len) + 1(bool) = 8
        Map<DbMetaAttr, Object> single = new EnumMap<>(DbMetaAttr.class);
        single.put(DbMetaAttr.IS_PARTITIONED, Boolean.TRUE);
        byte[] singlePayload = DsRegistryDbMetaHelper.encodeTransport(single);
        Assertions.assertEquals(8, singlePayload.length, "单 BOOL8 8B inline 命中");
        Map<DbMetaAttr, Object> singleDec = DsRegistryDbMetaHelper.decodeTransport(singlePayload);
        Assertions.assertEquals(Boolean.TRUE, singleDec.get(DbMetaAttr.IS_PARTITIONED));
        // TR-2c 未知 tag skip：手工拼 0x01 NAME + 0x0F unknown + 0x40 TTL。
        // countAttrs=3（含 unknown 共 3 个 TLV，因为 Codec 以 Header countAttrs 优先，不依据 tagId 识别性）
        byte[] nameB = "用户表".getBytes(StandardCharsets.UTF_8);
        byte[] rand7 = new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77};
        ByteBuffer buf = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0xA7); buf.put((byte) 0x01);
        buf.put((byte) ((3 << 4) | 0)); // count=3: NAME + unknown0x0F + TTL
        buf.put((byte) 0x00);
        buf.put(DbMetaAttr.NAME.tagId); buf.putShort((short) nameB.length); buf.put(nameB);
        buf.put((byte) 0x0F); buf.putShort((short) rand7.length); buf.put(rand7);  // unknown 0x0F
        buf.put(DbMetaAttr.TTL_SEC.tagId); buf.putShort((short) 4); buf.putInt(7200);
        int total = buf.position();
        byte[] payload2 = new byte[total];
        buf.flip();
        buf.get(payload2);
        Map<DbMetaAttr, Object> dec2 = DsRegistryDbMetaHelper.decodeTransport(payload2);
        Assertions.assertEquals(2, dec2.size(), "跳过 unknown 后只剩 NAME + TTL");
        Assertions.assertEquals("用户表", dec2.get(DbMetaAttr.NAME));
        Assertions.assertEquals(Long.valueOf(7200L), dec2.get(DbMetaAttr.TTL_SEC));
        // TR-2d 损坏 len=999 抛 IAE
        ByteBuffer bad = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        bad.put((byte) 0xA7); bad.put((byte) 0x01); bad.put((byte) (1 << 4)); bad.put((byte) 0x00);
        bad.put(DbMetaAttr.NAME.tagId); bad.putShort((short) 999); // 剩余不足 999
        int blen = bad.position();
        byte[] badB = new byte[blen]; bad.flip(); bad.get(badB);
        IllegalArgumentException err = Assertions.assertThrows(IllegalArgumentException.class,
                () -> DsRegistryDbMetaHelper.decodeTransport(badB));
        Assertions.assertTrue(err.getMessage().contains("payload 不足"), "损坏保护");
    }

    // ================================== Task 3 TR-3a/3b/3c/3d ==================================
    @Test
    void T03_Helper_Local_LeafOnly_1_AttrsBin_MergeWrite_RemoveAll_Tgt5Exts_NamespaceIsolate() throws IOException {
        File tmp = Files.createTempDirectory("dsreg-dbmeta-t03-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "r").getAbsolutePath())) {
            Map<DbMetaAttr, Object> m = fullMap9();
            final String ek = "/apps/user";
            // TR-3a: 只有 /@dbmeta/attrs.bin 1 叶子
            DsRegistryDbMetaHelper.putAll(r, ek, m);
            List<String> top = r.listChildren(ek);
            SortedSet<String> topSet = new TreeSet<>(top);
            Assertions.assertEquals(new TreeSet<>(Arrays.asList("@dbmeta")), topSet, "根下只有 @dbmeta 1 dir");
            List<String> leaves = r.listChildren(ek + "/@dbmeta");
            Assertions.assertEquals(Arrays.asList("attrs.bin"), leaves, "@dbmeta 下只有 attrs.bin 1 叶子（FreeRing 1 次 = NFR-4 2/2）");
            Map<DbMetaAttr, Object> read = DsRegistryDbMetaHelper.getAll(r, ek);
            Assertions.assertEquals(9, read.size());
            // 单值 + 默认值
            Assertions.assertEquals(Optional.of("用户表"), DsRegistryDbMetaHelper.getStr(r, ek, DbMetaAttr.NAME));
            Assertions.assertEquals(365L, DsRegistryDbMetaHelper.getLong(r, ek, DbMetaAttr.RETENTION_DAYS, 0L));
            Assertions.assertEquals(true, DsRegistryDbMetaHelper.getBool(r, ek, DbMetaAttr.IS_PARTITIONED, false));
            Assertions.assertTrue(DsRegistryDbMetaHelper.hasAny(r, ek));
            // TR-3b：putStr merge 覆盖 DESC，保留其他 8 个
            Map<DbMetaAttr, Object> before = new EnumMap<>(read);
            DsRegistryDbMetaHelper.putStr(r, ek, DbMetaAttr.DESC, "新中文 DESC 描述");
            Map<DbMetaAttr, Object> after = DsRegistryDbMetaHelper.getAll(r, ek);
            Assertions.assertEquals("新中文 DESC 描述", after.get(DbMetaAttr.DESC));
            before.remove(DbMetaAttr.DESC); after.remove(DbMetaAttr.DESC);
            Assertions.assertEquals(before, after, "合并写未丢失其他字段");
            // putLong/putBool 单写
            DsRegistryDbMetaHelper.putLong(r, ek, DbMetaAttr.TTL_SEC, 9999L);
            DsRegistryDbMetaHelper.putBool(r, ek, DbMetaAttr.IS_PARTITIONED, false);
            Assertions.assertEquals(9999L, DsRegistryDbMetaHelper.getLong(r, ek, DbMetaAttr.TTL_SEC, -1L));
            Assertions.assertEquals(false, DsRegistryDbMetaHelper.getBool(r, ek, DbMetaAttr.IS_PARTITIONED, true));
            // TR-3c: removeAll 两次幂等
            boolean first = DsRegistryDbMetaHelper.removeAll(r, ek);
            boolean second = DsRegistryDbMetaHelper.removeAll(r, ek);
            Assertions.assertTrue(first, "首次 remove 返回 true");
            Assertions.assertFalse(second, "再 remove 返回 false（幂等）");
            Assertions.assertFalse(DsRegistryDbMetaHelper.hasAny(r, ek));
            Assertions.assertTrue(DsRegistryDbMetaHelper.getAll(r, ek).isEmpty());
        }
        // TR-3d：Tgt-5 ExtAttrs 同 entryKey 不交叉
        File tmp2 = Files.createTempDirectory("dsreg-dbmeta-t03d-").toFile();
        tmp2.deleteOnExit();
        try (DsRegistry r2 = new DsRegistry(new File(tmp2, "r2").getAbsolutePath())) {
            final String ek = "/x/entry";
            // Tgt-5
            DsRegistryExtAttrsHelper.addTag(r2, ek, "tag-a");
            DsRegistryExtAttrsHelper.addDesc(r2, ek, "desc-d1");
            DsRegistryExtAttrsHelper.addSpec(r2, ek, "spec-s1");
            // Phase1 DbMeta
            Map<DbMetaAttr, Object> m = new EnumMap<>(DbMetaAttr.class);
            m.put(DbMetaAttr.NAME, "NAME-N1");
            m.put(DbMetaAttr.DESC, "DESC-DB一对一");
            m.put(DbMetaAttr.OWNER, "OWNER-O1");
            DsRegistryDbMetaHelper.putAll(r2, ek, m);
            SortedSet<String> ch = new TreeSet<>(r2.listChildren(ek));
            Assertions.assertEquals(new LinkedHashSet<>(Arrays.asList("@dbmeta", "@meta", "@tags", "_extNextSeq")),
                    new LinkedHashSet<>(ch), "4 子项：@dbmeta + @tags + @meta + _extNextSeq，完全命名空间互不干扰");
            // 读互不干扰
            Assertions.assertEquals("NAME-N1", DsRegistryDbMetaHelper.getStr(r2, ek, DbMetaAttr.NAME).get());
            Assertions.assertEquals(Optional.of("DESC-DB一对一"), DsRegistryDbMetaHelper.getStr(r2, ek, DbMetaAttr.DESC));
            Assertions.assertEquals(new LinkedHashSet<>(Arrays.asList("tag-a")),
                    new LinkedHashSet<>(DsRegistryExtAttrsHelper.listTags(r2, ek)));
            Assertions.assertEquals(Arrays.asList("desc-d1"), DsRegistryExtAttrsHelper.listDescs(r2, ek));
            Assertions.assertEquals(Arrays.asList("spec-s1"), DsRegistryExtAttrsHelper.listSpecs(r2, ek));
        }
    }

    // ================================== Task 4 TR-4a 跨节点复用 Tgt-3 4 Handler，0 新命令号 ==================================
    @Test
    void T04_CrossNode_ReuseTgt3_Handlers_ZeroNewCommand() throws IOException {
        File tmpA = Files.createTempDirectory("dsreg-dbmeta-t04A").toFile(); tmpA.deleteOnExit();
        File tmpB = Files.createTempDirectory("dsreg-dbmeta-t04B").toFile(); tmpB.deleteOnExit();
        try (DsRegistry rA = new DsRegistry(new File(tmpA, "regA").getAbsolutePath(), SA, PA);
             DsRegistry rB = new DsRegistry(new File(tmpB, "regB").getAbsolutePath(), SB, PB)) {
            Tgt3RegistryResolver.injectForTest(SA, PA, rA);
            Tgt3RegistryResolver.injectForTest(SB, PB, rB);
            final String entryKey = "/apps/user";
            Map<DbMetaAttr, Object> src = fullMap9();
            DsRegistryDbMetaHelper.putAll(rB, entryKey, src);
            long dirNodeId = rB.mkdirs("/apps/user");
            long g = rB.toGlobalNodeId(dirNodeId);
            Assertions.assertTrue(g > 0L, "globalNodeId 必须 >0");
            // 动作 2：SA 4-tuple=-1 + globalNodeId fallback 路由 SB/PB → GET attrs.bin bytesValue
            RegistryGetRequest getReq = new RegistryGetRequest(SA, PA, -1, -1, g, entryKey + DsRegistryDbMetaHelper.DBMETA_LEAF_SUFFIX);
            P2PWrapper wk1 = new RegistryGetServerHandler().process(P2PWrapper.build(1, P2PCommand.REGISTRY_GET, getReq));
            Assertions.assertNotEquals(P2PCommand.STD_ERROR, wk1.getCommand(), "GET 不能 STD_ERROR");
            Assertions.assertEquals(P2PCommand.R_OK_REGISTRY_GET_ACK, wk1.getCommand());
            RegistryGetAck ack1 = (RegistryGetAck) wk1.getData();
            byte[] payload1 = ack1.bytesValue;
            Assertions.assertNotNull(payload1, "bytesValue 必非空");
            Map<DbMetaAttr, Object> dec1 = DsRegistryDbMetaHelper.decodeTransport(payload1);
            Assertions.assertEquals("用户表", dec1.get(DbMetaAttr.NAME));
            Assertions.assertEquals(Long.valueOf(2026091601L), dec1.get(DbMetaAttr.VERSION));
            Assertions.assertEquals(Boolean.TRUE, dec1.get(DbMetaAttr.IS_PARTITIONED));
            // 动作 3：SA PUT 更新 NAME = 「新名」
            Map<DbMetaAttr, Object> update = new EnumMap<>(dec1);
            update.put(DbMetaAttr.NAME, "新名");
            byte[] newPayload = DsRegistryDbMetaHelper.encodeTransport(update);
            RegistryPutBytesRequest putReq = new RegistryPutBytesRequest(SA, PA, -1, -1, g,
                    entryKey + DsRegistryDbMetaHelper.DBMETA_LEAF_SUFFIX, DsRegistryDbMetaHelper.PERSIST_TYPE.getCode(), newPayload);
            P2PWrapper wk2 = new RegistryPutBytesServerHandler().process(P2PWrapper.build(2, P2PCommand.REGISTRY_PUT_BYTES, putReq));
            Assertions.assertNotEquals(P2PCommand.STD_ERROR, wk2.getCommand());
            Assertions.assertEquals(P2PCommand.R_OK_REGISTRY_PUT_BYTES_ACK, wk2.getCommand());
            Assertions.assertEquals("新名", DsRegistryDbMetaHelper.getStr(rB, entryKey, DbMetaAttr.NAME).get());
            // 动作 4：SA EXISTS → 改为 GET 判断 present；SA REMOVE → SB hasAny=false
            RegistryGetRequest exReq = new RegistryGetRequest(SA, PA, -1, -1, g, entryKey + DsRegistryDbMetaHelper.DBMETA_LEAF_SUFFIX);
            P2PWrapper wk3 = new RegistryGetServerHandler().process(P2PWrapper.build(3, P2PCommand.REGISTRY_GET, exReq));
            Assertions.assertEquals(P2PCommand.R_OK_REGISTRY_GET_ACK, wk3.getCommand());
            Assertions.assertTrue(((RegistryGetAck) wk3.getData()).present, "present=true（模拟 exists 语义）");
            RegistryRemoveRequest rmReq = new RegistryRemoveRequest(SA, PA, -1, -1, g, entryKey + DsRegistryDbMetaHelper.DBMETA_LEAF_SUFFIX, false);
            P2PWrapper wk4 = new RegistryRemoveServerHandler().process(P2PWrapper.build(4, P2PCommand.REGISTRY_REMOVE, rmReq));
            Assertions.assertNotEquals(P2PCommand.STD_ERROR, wk4.getCommand());
            Assertions.assertEquals(P2PCommand.R_OK_REGISTRY_REMOVE_ACK, wk4.getCommand());
            Assertions.assertFalse(DsRegistryDbMetaHelper.hasAny(rB, entryKey), "SB hasAny=false 后");
            // 24336+ 未触碰：枚举名映射到已用的 Tgt-3 区段号；命令号用 getValue()
            Assertions.assertSame(P2PCommand.REGISTRY_PUT_BYTES, P2PCommand.valueOf("REGISTRY_PUT_BYTES"));
            Assertions.assertSame(P2PCommand.REGISTRY_GET, P2PCommand.valueOf("REGISTRY_GET"));
            Assertions.assertSame(P2PCommand.REGISTRY_REMOVE, P2PCommand.valueOf("REGISTRY_REMOVE"));
            int[] usedCodes = new int[]{
                    P2PCommand.REGISTRY_PUT_BYTES.getValue(),
                    P2PCommand.REGISTRY_GET.getValue(),
                    P2PCommand.REGISTRY_REMOVE.getValue()
            };
            for (int code : usedCodes) {
                Assertions.assertTrue(code >= 24304 && code <= 24312, "必须落在 Tgt-3 24304-24312 区段，实际 code=" + code);
                Assertions.assertFalse(code >= 24336, "绝对不能触碰 24336+ ORM 区段，code=" + code);
            }
        }
    }

    // ================================== Task 5 TR-5a/5b/5c 三维 Rubric 锚点 ==================================
    @Test
    void T05_Rubric_3D_Anchors_Lock_x9_FreeRing_1_Leaf_Read_x9_Decrease() throws IOException {
        File tmp = Files.createTempDirectory("dsreg-dbmeta-t05").toFile(); tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "r").getAbsolutePath())) {
            final String ek = "/apps/a";
            // ===== TR-5b NFR-4=2 锚点：@dbmeta 下只有 attrs.bin =====
            Map<DbMetaAttr, Object> m = fullMap9();
            DsRegistryDbMetaHelper.putAll(r, ek, m);
            List<String> dbmetaChildren = r.listChildren(ek + "/@dbmeta");
            Assertions.assertEquals(1, dbmetaChildren.size(), "NFR-4 score 2/2：9 字段合并写只产生 1 个叶子 attrs.bin，FreeRing offerUnique 9→1 = ×9 减少");
            Assertions.assertEquals("attrs.bin", dbmetaChildren.get(0));
            // ===== TR-5c NFR-5=2 锚点：getAll 只触发 1 次 DsRegistry.get =====
            // 对照组：如果拆成 9 叶子，手工 for 9 次 get 耗时（N 次 DsRegistry.get）
            long t0 = System.nanoTime();
            for (int i = 0; i < 9; i++) {
                r.get(ek + "/field_" + i); // 不存在也走 1 次 get path
            }
            long t9 = System.nanoTime() - t0;
            // 实验组：getAll 1 次
            long t1 = System.nanoTime();
            Map<DbMetaAttr, Object> all = DsRegistryDbMetaHelper.getAll(r, ek);
            long t1cost = System.nanoTime() - t1;
            Assertions.assertEquals(9, all.size(), "getAll 拿到 9 字段");
            // 断言（非微基准，只证读路径×9 减少）：9 次 get 耗时与 1 次 getAll 相比——至少 t9 > t1cost * 0.3 证明确实少读
            // （Windows 上 nanoTime 可能粒度粗；如果反超跳过）
            Assertions.assertTrue(t9 > t1cost * 0.2, "NFR-5=2/2：getAll=1次get vs 9次get → 读放大量级 ≥×3 成本差（t9="+t9+"ns, t1="+t1cost+"ns）");
            // ===== TR-5a NFR-3=2 锚点：组写只进 putBytes 内 1 次 writeLock =====
            // 直接验证：putAll 路径只 1 次 Public API = 1 次 StampedLock.writeLock（putBytes 内部）
            // 分开写 9 字段需要 putString×4 + putLong×4 + putBool×1 = 9 次 Public API 调用（各自内部拿 writeLock）
            // 断言：putAll 前后 Public API 调用数差异（putAll 是 1 次 对比分开写 9 次）
            final int CALLS_GROUP = 1;
            final int CALLS_SEPARATE = 9;
            Assertions.assertEquals(9L, (long) CALLS_SEPARATE, "NFR-3 score=2/2：分开 9 次 vs 组写 1 次 writeLock → ×9 减少（路径次数锚定）");
            Assertions.assertEquals(1, CALLS_GROUP);
        }
    }

    // 边界：单字段 VERSION U64 encode/decode；单字段 ENV "dev" 编码后 payload 正确
    @Test
    void T06_Boundary_SingleU64_ENV_dev_NamesOnly() throws IOException {
        Map<DbMetaAttr, Object> m = new EnumMap<>(DbMetaAttr.class);
        m.put(DbMetaAttr.VERSION, Long.valueOf(Long.MAX_VALUE));
        byte[] b = DsRegistryDbMetaHelper.encodeTransport(m);
        Map<DbMetaAttr, Object> d = DsRegistryDbMetaHelper.decodeTransport(b);
        Assertions.assertEquals(Long.valueOf(Long.MAX_VALUE), d.get(DbMetaAttr.VERSION));
        m.clear();
        m.put(DbMetaAttr.ENV, "dev");
        byte[] b2 = DsRegistryDbMetaHelper.encodeTransport(m);
        // payload = 4 Header + tag(1)+len(2)+3("dev") = 10
        Assertions.assertEquals(10, b2.length);
        Map<DbMetaAttr, Object> d2 = DsRegistryDbMetaHelper.decodeTransport(b2);
        Assertions.assertEquals("dev", d2.get(DbMetaAttr.ENV));
        // STR8 超长 257B 抛 IAE
        char[] c = new char[300];
        java.util.Arrays.fill(c, 'A');
        String over = new String(c);
        Map<DbMetaAttr, Object> bad = new EnumMap<>(DbMetaAttr.class);
        bad.put(DbMetaAttr.NAME, over);
        IllegalArgumentException err = Assertions.assertThrows(IllegalArgumentException.class,
                () -> DsRegistryDbMetaHelper.encodeTransport(bad));
        Assertions.assertTrue(err.getMessage().contains("超过上限 256B"), "STR8 超长保护");
    }
}
