package ds;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.enums.Category;
import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;
import com.q3lives.ds.registry.RegistryWalker;

public class DsRegistryTest {

    private File tmp;
    private DsRegistry reg;

    @Before
    public void setUp() throws IOException {
        tmp = Files.createTempDirectory("dsreg-").toFile();
        tmp.deleteOnExit();
        reg = new DsRegistry(tmp.getAbsolutePath());
    }

    @After
    public void tearDown() throws Exception {
        if (reg != null) try { reg.close(); } catch (Exception ignore) {}
        if (tmp != null) deleteDir(tmp);
    }

    private static void deleteDir(File f) {
        if (f == null || !f.exists()) return;
        File[] list = f.listFiles();
        if (list != null) for (File c : list) deleteDir(c);
        f.delete();
    }

    // ========================================================================
    // Test #1: ≤64-bit inline NUMERIC put/get + restart
    // ========================================================================
    @Test
    public void testSmokePutGetInlineAndRestart() throws Exception {
        long n1 = reg.putLong("/a/dword", DataType.REG_DWORD, 42L);
        long n2 = reg.putLong("/a/qword", DataType.REG_QWORD, 1L << 42);
        long n3 = reg.putLong("/pi/double", DataType.REG_DOUBLE, Double.doubleToLongBits(3.14159));
        Assert.assertTrue(n1 > 0);
        Assert.assertTrue(n2 > 0);
        Assert.assertTrue(n3 > 0);

        RegistryEntry e1 = reg.get("/a/dword");
        RegistryEntry e2 = reg.get("/a/qword");
        RegistryEntry e3 = reg.get("/pi/double");
        Assert.assertTrue(e1.isPresent());
        Assert.assertEquals(42L, e1.asLong());
        Assert.assertEquals(DataType.REG_DWORD, e1.dataType());
        Assert.assertEquals(1L << 42, e2.asLong());
        Assert.assertEquals(DataType.REG_QWORD, e2.dataType());
        double piBack = e3.asDouble();
        Assert.assertEquals(3.14159, piBack, 1e-9);
        Assert.assertEquals(DataType.REG_DOUBLE, e3.dataType());

        reg.close();
        reg = new DsRegistry(tmp.getAbsolutePath());
        Assert.assertEquals(42L, reg.get("/a/dword").asLong());
        Assert.assertEquals(1L << 42, reg.get("/a/qword").asLong());
        Assert.assertEquals(3.14159, reg.get("/pi/double").asDouble(), 1e-9);
    }

    // ========================================================================
    // Test #2: STRING/JSON/TEXT put/get + 1MB JSON sha256 一致
    // ========================================================================
    @Test
    public void testStringPutGetAnd1MBJson() throws Exception {
        reg.putString("/etc/profile", "{\"name\":\"p2p\",\"port\":8080}", DataType.REG_JSON);
        reg.putString("/etc/xml", "<root/>", DataType.REG_XML);

        RegistryEntry json = reg.get("/etc/profile");
        Assert.assertEquals(DataType.REG_JSON, json.dataType());
        Assert.assertEquals("{\"name\":\"p2p\",\"port\":8080}", json.asString());
        Assert.assertEquals(Category.STRING, json.category());

        StringBuilder sb = new StringBuilder(1024 * 1024 + 10);
        sb.append('[');
        for (int i = 0; i < 65536; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(i).append(",\"v\":\"").append("abcdefghij".repeat(4)).append("\"}");
        }
        sb.append(']');
        String bigJson = sb.toString();
        String shaBefore = sha256Hex(bigJson.getBytes("UTF-8"));
        reg.putString("/huge/doc", bigJson, DataType.REG_TEXT);
        String after = reg.get("/huge/doc").asString();
        Assert.assertEquals(shaBefore, sha256Hex(after.getBytes("UTF-8")));
    }

    // ========================================================================
    // Test #3: BINARY inline (≤8B packSmallBytes) vs external (>8B length header)
    // ========================================================================
    @Test
    public void testBinaryExternalVsInline() throws Exception {
        byte[] tiny = new byte[] {(byte)0xFF, 0x01, 0x02, 0x00, 0x00, 0x00, 0x00, (byte)0xAB}; // 8B exactly
        byte[] mid  = new byte[200];
        byte[] big  = new byte[200 * 1024];
        Arrays.fill(mid, (byte)0x5A);
        Arrays.fill(big, (byte)0x7E);

        // 最后 4 位带 0x00，验证 length header 不 strip trailing zero
        byte[] tinyWithZero = new byte[] {0x01, 0x02, 0x00, 0x04, 0x00, 0x00, 0x00, 0x08};
        byte[] midWithZeroTail = new byte[200];
        Arrays.fill(midWithZeroTail, (byte)0x42);
        midWithZeroTail[199] = 0x00;

        reg.putBytes("/config/signature8", tiny, DataType.REG_BINARY);
        reg.putBytes("/config/signature_zero", tinyWithZero, DataType.REG_BINARY);
        reg.putBytes("/blobs/mid200", mid, DataType.REG_BLOB);
        reg.putBytes("/blobs/big200k", big, DataType.REG_BLOB);
        reg.putBytes("/blobs/mid_tail0", midWithZeroTail, DataType.REG_BINARY);

        Assert.assertArrayEquals(tiny, reg.get("/config/signature8").asBytes());
        Assert.assertArrayEquals(tinyWithZero, reg.get("/config/signature_zero").asBytes());
        Assert.assertArrayEquals(mid, reg.get("/blobs/mid200").asBytes());
        Assert.assertArrayEquals(big, reg.get("/blobs/big200k").asBytes());
        Assert.assertArrayEquals(midWithZeroTail, reg.get("/blobs/mid_tail0").asBytes());

        // inline 证明：tiny signature 的长度是 8 → 用 packSmallBytes inline，不应创建 type_CD(=205? 实际 TYPE_REGISTRY_VALUE_BINARY=203=HEX=CB) / type_CB/reg_vals/bin/data_*.dat
        // 但 big200k 必须创建物理 data 文件。简单证明：tiny 和 big 的 valueBinaries typeDir 是否存在 data_* 文件
        String binTypeDir = com.q3lives.ds.bucket.DsTypeFixedBucketStore.typeDirFor(tmp.getAbsolutePath(), DsRegistry.TYPE_REGISTRY_VALUE_BINARY);
        File valsDir = new File(binTypeDir, "reg_vals");
        File binDir = new File(valsDir, "bin");
        // binDir 必须有 data_size.dat（因为 big200k > INLINE_BYTES_MAX_LEN）
        boolean hasBigBlobData = false;
        if (binDir.exists()) {
            for (File f : binDir.listFiles()) {
                if (f.isFile() && f.getName().startsWith("data_")) { hasBigBlobData = true; break; }
            }
        }
        Assert.assertTrue("big200k 必须落物理 data_*.dat 文件", hasBigBlobData);
    }

    // ========================================================================
    // Test #4: 目录树 mkdirs + listChildren（5层 a/b/c/d/e/x）
    // ========================================================================
    @Test
    public void testDirectoryTreeAndListChildren() throws Exception {
        reg.putLong("/a/b/c/d/e/x", DataType.REG_DWORD, 7L);

        List<String> root = reg.listChildren("/");
        List<String> lvlA = reg.listChildren("/a");
        List<String> lvlB = reg.listChildren("/a/b");
        List<String> lvlC = reg.listChildren("/a/b/c");
        List<String> lvlD = reg.listChildren("/a/b/c/d");
        List<String> lvlE = reg.listChildren("/a/b/c/d/e");

        Assert.assertEquals(new HashSet<>(Arrays.asList("a")), new HashSet<>(root));
        Assert.assertEquals(new HashSet<>(Arrays.asList("b")), new HashSet<>(lvlA));
        Assert.assertEquals(new HashSet<>(Arrays.asList("c")), new HashSet<>(lvlB));
        Assert.assertEquals(new HashSet<>(Arrays.asList("d")), new HashSet<>(lvlC));
        Assert.assertEquals(new HashSet<>(Arrays.asList("e")), new HashSet<>(lvlD));
        Assert.assertEquals(Arrays.asList("x"), lvlE);

        RegistryEntry ent = reg.get("/a/b/c/d/e");
        Assert.assertTrue(ent.isDirectory());
        Assert.assertTrue(reg.get("/a/b/c/d/e/x").isPresent());
    }

    // ========================================================================
    // Test #5: Dynamic DataType 自定义注册（bit15=1）
    // ========================================================================
    @Test
    public void testDynamicUserDataType() throws Exception {
        DataType my = DataType.registerUserDefined(0x8001, Category.BINARY, "CUSTOM_REG_MYBLOB_V2", "My Blob v2");
        Assert.assertTrue(my.isUserDefined());
        Assert.assertEquals("CUSTOM_REG_MYBLOB_V2", my.name());

        long id = my.generateId(5L);
        DataType parsed = DataType.parseFromId(id);
        Assert.assertEquals(my, parsed);
        Assert.assertTrue(parsed.isUserDefined());

        Assert.assertFalse(DataType.REG_DWORD.isUserDefined());
        Assert.assertFalse(DataType.REG_JSON.isUserDefined());

        try {
            DataType.registerUserDefined(0x8001, Category.BINARY, "DUPLICATE", "dup");
            Assert.fail("必须抛重复注册异常");
        } catch (IllegalArgumentException ok) { /* expected */ }

        try {
            DataType.registerUserDefined(0x7FFF, Category.STRING, "WRONG_FLAG", "bad");
            Assert.fail("bit15=0 必须抛");
        } catch (IllegalArgumentException ok) { /* expected */ }
    }

    // ========================================================================
    // Test #6: matchesThis 守卫（keyStrings.add 返回 typedId 喂 valueStrings.get → null/refCount=0）
    // ========================================================================
    @Test
    public void testMatchesThisGuardPreventsCrossTypedIdLeak() throws Exception {
        long aId = reg.keyStrings().add("/demo/guard-sentinel");
        int aType = com.q3lives.ds.util.MultiTypeDataId.decodeTypeId(aId);
        Assert.assertEquals(DsRegistry.TYPE_REGISTRY_KEY, aType);
        String got = reg.valueStrings().get(aId);
        int rc = reg.valueStrings().refCount(aId);
        Assert.assertNull("跨 TypeID 不应读到字符串", got);
        Assert.assertEquals("跨 TypeID refCount 必须 0", 0, rc);
    }

    // ========================================================================
    // Test #7: walk visitor 深度 1 vs 深度 3
    // ========================================================================
    @Test
    public void testWalkVisitorDepthCount() throws Exception {
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 5; j++) {
                reg.putLong("/lvl1_" + i + "/lvl2_" + j + "/leaf", DataType.REG_DWORD, (long)(i * 5 + j));
            }
        }
        RegistryWalker.CountVisitor depth3 = new RegistryWalker.CountVisitor();
        reg.walk(depth3, 3, null);
        // 10*5 leaves + 1(root) + 10(lvl1) + 50(lvl2) = 61（中间目录含）
        Assert.assertTrue("walk depth=3 应该至少访问 50 leaves", depth3.count() >= 50);

        RegistryWalker.CountVisitor depth1 = new RegistryWalker.CountVisitor();
        reg.walk(depth1, 1, null);
        // depth=1: only root (depth=0) + lvl1_* 共 11
        Assert.assertTrue("walk depth=1 只能顶层（root+10 lvl1=11 左右）", depth1.count() <= 20);
    }

    // ========================================================================
    // Test #8: remove recursive
    // ========================================================================
    @Test
    public void testRemoveRecursiveCleansAll() throws Exception {
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 4; j++) {
                reg.putLong("/a/sub" + i + "/j" + j, DataType.REG_DWORD, 100L * i + j);
            }
        }
        Assert.assertTrue(reg.exists("/a"));
        List<String> subList = reg.listChildren("/a");
        Assert.assertEquals(5, subList.size());

        try {
            reg.remove("/a", false);
            Assert.fail("非空目录 recursive=false 必须抛 ISE");
        } catch (IllegalStateException ok) { /* expected */ }

        reg.remove("/a", true);
        Assert.assertFalse(reg.exists("/a"));
        Assert.assertEquals(0, reg.listChildren("/a").size());
    }

    // ========================================================================
    // Test #9: DataType generateId/parseFromId 向量验证
    // ========================================================================
    @Test
    public void testDataTypeBitOpsVectorCheck() {
        long v1 = DataType.REG_DWORD.generateId(1L);
        Assert.assertEquals((0x0020L << DataType.SEQ_BITS) | 1L, v1);
        Assert.assertEquals(DataType.REG_DWORD, DataType.parseFromId(v1));

        long v2 = DataType.REG_JSON.generateId(0xFFFFFFL);
        long expected2 = (0x12L << DataType.SEQ_BITS) | 0xFFFFFFL;
        Assert.assertEquals(expected2, v2);
        Assert.assertEquals(DataType.REG_JSON, DataType.parseFromId(v2));

        DataType custom = DataType.registerUserDefined(0x8102, Category.COMPLEX, "CUSTOM_REG_X_02", "x2");
        long v3 = custom.generateId(0L);
        long v3Expected = 0x8102L << DataType.SEQ_BITS;
        Assert.assertEquals(v3Expected, v3);
        Assert.assertTrue(custom.isUserDefined());
    }

    // ========================================================================
    // Test #10: packSmallBytes / unpackSmallBytes
    // ========================================================================
    @Test
    public void testTinyBytesInlinePackUnpack() {
        byte[] b3 = new byte[]{0x12, 0x34, 0x56};
        long packed = DsRegistry.packSmallBytes(b3);
        byte[] back = DsRegistry.unpackSmallBytes(packed);
        Assert.assertArrayEquals(b3, back);

        byte[] b8 = new byte[]{1,2,3,4,5,6,7,8};
        long p8 = DsRegistry.packSmallBytes(b8);
        byte[] u8 = DsRegistry.unpackSmallBytes(p8);
        Assert.assertArrayEquals(b8, u8);

        byte[] empty = new byte[0];
        Assert.assertArrayEquals(empty, DsRegistry.unpackSmallBytes(DsRegistry.packSmallBytes(empty)));
    }

    // ========================================================================
    // Test #11: listChildrenEntries 带 type/value
    // ========================================================================
    @Test
    public void testListChildrenEntriesTypes() throws Exception {
        reg.putLong("/mixed/a_dword", DataType.REG_DWORD, 1234L);
        reg.putString("/mixed/b_json", "{}", DataType.REG_JSON);
        reg.putBytes("/mixed/c_bin", new byte[]{1,2,3,4,5,6,7,8,9}, DataType.REG_BINARY);
        List<RegistryEntry> entries = reg.listChildrenEntries("/mixed");
        Set<String> names = entries.stream().map(RegistryEntry::key).map(s -> { int i = s.lastIndexOf('/'); return i < 0 ? s : s.substring(i+1); }).collect(Collectors.toSet());
        Assert.assertTrue(names.contains("a_dword"));
        Assert.assertTrue(names.contains("b_json"));
        Assert.assertTrue(names.contains("c_bin"));
        Set<DataType> types = entries.stream().map(RegistryEntry::dataType).collect(Collectors.toSet());
        Assert.assertTrue(types.contains(DataType.REG_DWORD));
        Assert.assertTrue(types.contains(DataType.REG_JSON));
        Assert.assertTrue(types.contains(DataType.REG_BINARY));
    }

    // ========================================================================
    // Test #12: 非法 key 抛 IAE（无 / 前缀、中间段 ..、中间段空 //）
    // ========================================================================
    @Test
    public void testInvalidKeyPathsThrow() {
        for (String bad : new String[]{"no/leading/slash", "/a/../b", "/a//b/c"}) {
            try {
                DsRegistry.parseKey(bad);
                Assert.fail("路径非法应抛 IAE: " + bad);
            } catch (IllegalArgumentException ok) { /* expected */ }
        }
    }

    // ========================================================================
    // Test #13: 128 paths × 100 updates 吞吐（只 INFO，不 assert 数值）
    // ========================================================================
    @Test
    public void testPerf128x100Update() throws Exception {
        String[] paths = new String[128];
        for (int i = 0; i < 128; i++) paths[i] = "/perf/key_" + i;
        for (int i = 0; i < 40; i++) {
            for (int k = 0; k < 128; k++) reg.putLong(paths[k], DataType.REG_DWORD, (long)(i * k));
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            for (int k = 0; k < 128; k++) reg.putLong(paths[k], DataType.REG_DWORD, (long)(i * 131 + k));
        }
        long dtNs = System.nanoTime() - t0;
        double ops = 12800.0 / (dtNs * 1e-9);
        System.err.println("[INFO DsRegistryTest] 128x100=12800 update ops in " + (dtNs/1_000_000.0) + " ms → " + (long)ops + " ops/sec");
        Assert.assertTrue(paths.length == 128);
    }

    // ========================================================================
    // Test #14: 根目录 / 条目
    // ========================================================================
    @Test
    public void testRootDirEntry() throws Exception {
        reg.putString("/root/only", "x", DataType.REG_SZ);
        RegistryEntry root = reg.get("/");
        Assert.assertTrue(root.isPresent());
        Assert.assertTrue(root.isDirectory());
        Assert.assertEquals(DataType.REG_REGISTRY_DIR, root.dataType());
        Assert.assertTrue(reg.listChildren("/").contains("root"));
    }

    private static String sha256Hex(byte[] in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(in);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
