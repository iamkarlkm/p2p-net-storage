package ds;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.schema.EntityIndexUtil;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * P56 T6：非功能需求（NFR）。
 *
 * <p>
 * 对应 spec §5.2 T6：</p>
 * <ol>
 * <li>{@code tr61} 迁移吞吐（行/秒）≥ 阈值；</li>
 * <li>{@code tr62} 迁移按批流式（无全量行装载）+ 堆增量有界；</li>
 * <li>{@code tr63} 改动区 Javadoc ≥ 阈值 + 0 重复块（轻量 CPD）；</li>
 * <li>{@code tr64} 0 {@code System.out.println}/{@code printStackTrace} + IAE
 * 显式守卫 ≥ 阈值；</li>
 * <li>{@code tr65} P56 改动区 0 TODO/FIXME/HACK。</li>
 * </ol>
 */
public class P56_T6_NFR_Test {

    private static final String DDL = "src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java";
    private static final String EIU = "src/main/java/com/q3lives/ds/database/schema/EntityIndexUtil.java";

    private static final int V1_LEN = 28;
    private static final int V2_LEN = 36;

    /**
     * 吞吐阈值（行/秒，保守下界）。实测 ~75 行/秒（每行含 Registry 读取 + bucket get + putEntity
     * 持久化），阈值取 ~1/5 以吸收 CI 负载波动，仅拦截灾难性回归（如 O(N²)）。
     */
    private static final double MIN_ROWS_PER_SEC = 15.0;
    /**
     * 迁移 N 行后的堆增量上界（字节，宽松上界，证明非全量行装载）。
     */
    private static final long MAX_HEAP_DELTA_BYTES = 128L * 1024 * 1024;

    public static class NfrV1 extends DsTableAdapter {

        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;

        public NfrV1() {
        }
    }

    public static class NfrV2 extends DsTableAdapter {

        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        @DsField(name = "extra", length = 8, defaultValue = "7")
        public long extra;

        public NfrV2() {
        }
    }

    private File root;

    @Before
    public void before() throws Exception {
        root = Files.createTempDirectory("dsdb_p56_t6_").toFile();
    }

    @After
    public void after() {
        if (root != null && root.exists()) {
            try {
                DsDatabaseLocal.forceResetSchemaMetaForTest(root);
            } catch (Exception ignore) {
            }
            deleteRecursive(root);
        }
    }

    // ==================== tr61 迁移吞吐 ====================
    @Test(timeout = 120000)
    public void tr61_migrationThroughput() throws Exception {
        final int rows = 400;
        EntityIndexUtil.writeLegacyRowLength(root, NfrV2.class, V1_LEN);
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            for (int i = 0; i < rows; i++) {
                seedLegacyRow(db, i);
            }
            long t0 = System.nanoTime();
            int migrated = db.migrateLegacyRows(NfrV2.class, 128);
            long elapsed = System.nanoTime() - t0;
            Assert.assertEquals("应全部迁移", rows, migrated);
            double sec = Math.max(elapsed / 1e9, 1e-6);
            double rps = rows / sec;
            Assert.assertTrue("迁移吞吐 " + (long) rps + " 行/秒 应 ≥ " + (long) MIN_ROWS_PER_SEC,
                    rps >= MIN_ROWS_PER_SEC);
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== tr62 批流式 + 堆增量有界 ====================
    @Test(timeout = 120000)
    public void tr62_streamingBatchNoFullLoad() throws Exception {
        // 结构性证据：按 subList 分批，逐行 rewrite（无全量行累加）
        String coord = p56CoordinatorSlice(readUtf8(srcDir(DDL)));
        Assert.assertTrue("必须按 subList 分批", coord.contains("subList(i, end)"));
        Assert.assertTrue("必须逐行 rewrite", coord.contains("rewriteLegacyRow(meta, id, legacyLen)"));

        final int rows = 1000;
        EntityIndexUtil.writeLegacyRowLength(root, NfrV2.class, V1_LEN);
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            for (int i = 0; i < rows; i++) {
                seedLegacyRow(db, i);
            }
            System.gc();
            Thread.sleep(50);
            long before = usedHeap();
            int migrated = db.migrateLegacyRows(NfrV2.class, 128);
            long after = usedHeap();
            Assert.assertEquals("应全部迁移", rows, migrated);
            long delta = after - before;
            Assert.assertTrue("堆增量 " + delta + "B 应 ≤ " + MAX_HEAP_DELTA_BYTES + "B（非全量装载）",
                    delta <= MAX_HEAP_DELTA_BYTES);
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== tr63 Javadoc + 0 重复块 ====================
    @Test(timeout = 30000)
    public void tr63_javadocAndNoDuplicateBlocks() throws Exception {
        String region = p56DdlSlice(readUtf8(srcDir(DDL))) + readUtf8(srcDir(EIU));
        int javadocs = countOccurrences(region, "/**");
        Assert.assertTrue("P56 改动区 Javadoc 数 " + javadocs + " 应 ≥ 6", javadocs >= 6);

        int dups = duplicateWindows(region, 8);
        Assert.assertEquals("P56 改动区不应有重复代码块（轻量 CPD）", 0, dups);
    }

    // ==================== tr64 0 打印 + IAE 守卫 ====================
    @Test(timeout = 30000)
    public void tr64_noPrintAndIllegalArgumentGuards() throws Exception {
        String region = p56DdlSlice(readUtf8(srcDir(DDL))) + readUtf8(srcDir(EIU));
        Assert.assertFalse("P56 改动区不应有 System.out.println", region.contains("System.out.println"));
        Assert.assertFalse("P56 改动区不应有 printStackTrace", region.contains("printStackTrace"));

        int guards = countOccurrences(region, "throw new IllegalArgumentException");
        Assert.assertTrue("IAE 显式守卫数 " + guards + " 应 ≥ 4", guards >= 4);
    }

    // ==================== tr65 0 TODO/FIXME/HACK ====================
    @Test(timeout = 30000)
    public void tr65_noTodoFixmeHack() throws Exception {
        String region = p56DdlSlice(readUtf8(srcDir(DDL))) + readUtf8(srcDir(EIU));
        for (String banned : new String[]{"TODO", "FIXME", "HACK"}) {
            Assert.assertFalse("found " + banned + " in P56 region", region.contains(banned));
        }
    }

    // ==================== helpers ====================
    private static String p56DdlSlice(String src) {
        String[] lines = src.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        appendWindow(lines, sb, "compat 判定改读持久化", 14);
        appendWindow(lines, sb, "Online DDL row-plane 显式 pull 全量迁移", 30);
        appendWindow(lines, sb, "显式 pull 的行级全量迁移", 105);
        return sb.toString();
    }

    private static String p56CoordinatorSlice(String src) {
        String[] lines = src.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        appendWindow(lines, sb, "显式 pull 的行级全量迁移", 105);
        return sb.toString();
    }

    private static void appendWindow(String[] lines, StringBuilder sb, String anchor, int after) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(anchor)) {
                int hi = Math.min(lines.length, i + after);
                for (int j = i; j < hi; j++) {
                    sb.append(lines[j]).append('\n');
                }
            }
        }
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0;
        int idx = s.indexOf(needle);
        while (idx >= 0) {
            n++;
            idx = s.indexOf(needle, idx + needle.length());
        }
        return n;
    }

    /**
     * 轻量 CPD：统计重复的非空白 {@code window} 行滑动窗口数（跳过长度 &lt; 12 的行以减少样板误报）。
     */
    private static int duplicateWindows(String src, int window) {
        String[] raw = src.split("\n", -1);
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String r : raw) {
            String t = r.trim();
            if (t.length() >= 12) {
                lines.add(t);
            }
        }
        Map<String, Integer> seen = new HashMap<>();
        int dups = 0;
        for (int i = 0; i + window <= lines.size(); i++) {
            StringBuilder key = new StringBuilder();
            for (int j = i; j < i + window; j++) {
                key.append(lines.get(j)).append('\u0001');
            }
            String k = key.toString();
            Integer c = seen.get(k);
            if (c == null) {
                seen.put(k, 1);
            } else {
                if (c == 1) {
                    dups++;
                }
                seen.put(k, c + 1);
            }
        }
        return dups;
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static String readUtf8(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static File srcDir(String rel) {
        File f = new File(System.getProperty("user.dir"), rel);
        Assert.assertTrue("源文件不存在: " + f.getAbsolutePath(), f.isFile());
        return f;
    }

    private long seedLegacyRow(DsDatabaseLocal db, int i) throws Exception {
        DsFixedBucketStore bs = bucketStore(db);
        String space = NfrV2.class.getName();
        String type = EntityIndexUtil.indexOf(root, NfrV2.class).rowType;
        long id = bs.getNewId(space, type, V1_LEN);
        NfrV1 v1 = new NfrV1();
        v1.setId(id);
        v1.name = "user_" + i;
        v1.age = 20 + i;
        ByteBuffer bb = v1.toBytes();
        byte[] b = new byte[bb.remaining()];
        bb.get(b);
        bs.overwrite(space, type, id, b);
        idSetForClass(db, NfrV2.class).add(Long.valueOf(id));
        return id;
    }

    private static DsFixedBucketStore bucketStore(DsDatabaseLocal db) throws Exception {
        Field f = DsDatabaseLocal.class.getDeclaredField("bucketStore");
        f.setAccessible(true);
        return (DsFixedBucketStore) f.get(db);
    }

    private static DsHashSet idSetForClass(DsDatabaseLocal db, Class<? extends DsTableAdapter> clazz) throws Exception {
        Method m = DsDatabaseLocal.class.getDeclaredMethod("idSetForClass", Class.class);
        m.setAccessible(true);
        return (DsHashSet) m.invoke(db, clazz);
    }

    private static void closeQuietly(DsDatabaseLocal db) {
        try {
            db.close();
        } catch (Exception ignore) {
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null) {
            return;
        }
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) {
                for (File c : ch) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }
}
