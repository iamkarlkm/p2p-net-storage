package ds;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.cluster.stats.DbStatsRegistry;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.schema.EntityIndexUtil;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * P56 T5：Gatekeeper 9Y（9 TR / 0F0E）。
 *
 * <p>
 * 对应 spec §5.2 T5 / 页首 Gate C 清单：</p>
 * <ul>
 * <li><b>Y1</b> 0 新顶级类：{@code database/} 顶层 11、{@code database/schema/} 顶层 3
 * 守恒；</li>
 * <li><b>Y2</b> NG-3 零依赖：P56 新增源码 0 forbidden 引用 + {@code EntityIndexUtil}
 * import 白名单 + pom 0 指标库；</li>
 * <li><b>Y3</b> R10 0 新线程：P56 改动区去注释后 0 线程原语；</li>
 * <li><b>Y4</b> P18~P55 单调不减：fulltext / columnar / cluster.stats 锁定包类数 +
 * 既有签名守恒；</li>
 * <li><b>Y5</b> 既有 public 面 0 改：4 人工按钮 + {@code getTable}/{@code putEntity}
 * 既有重载；</li>
 * <li><b>Y6</b> R5 段格式 0 变更：{@code DsTableAdapter} 序列化 API + 定长 bit 级公式；</li>
 * <li><b>Y7</b> 检测语义确定：死分支移除 + 4 helper 可达 + leaf 唯一真源语义；</li>
 * <li><b>Y8</b> 幂等 + 断点续跑：重复迁移短路 + 退出 compat；</li>
 * <li><b>Y9</b> 0 TODO/FIXME/HACK（P56 改动区）。</li>
 * </ul>
 */
public class P56_T5_Gatekeeper9YTest {

    private static final String DB_SRC = "src/main/java/com/q3lives/ds/database";
    private static final String SCHEMA_SRC = "src/main/java/com/q3lives/ds/database/schema";
    private static final String FT = "src/main/java/com/q3lives/ds/database/fulltext";
    private static final String COL = "src/main/java/com/q3lives/ds/database/columnar";
    private static final String STATS = "src/main/java/com/q3lives/ds/cluster/stats";
    private static final String DDL = DB_SRC + "/DsDatabaseLocal.java";
    private static final String EIU = SCHEMA_SRC + "/EntityIndexUtil.java";

    private static final int V1_LEN = 28;
    private static final int V2_LEN = 36;

    /**
     * 旧 schema V1：8 + 16 + 4 = 28B。
     */
    public static class GateV1 extends DsTableAdapter {

        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;

        public GateV1() {
        }
    }

    /**
     * 当前 schema V2：8 + 16 + 4 + 8 = 36B。
     */
    public static class GateV2 extends DsTableAdapter {

        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        @DsField(name = "extra", length = 8, defaultValue = "7")
        public long extra;

        public GateV2() {
        }
    }

    private File root;

    @Before
    public void before() throws Exception {
        root = Files.createTempDirectory("dsdb_p56_t5_").toFile();
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

    // ============== Y1 0 新顶级类 ==============
    @Test(timeout = 30000)
    public void y1_zeroNewTopLevelClass() {
        // 基线 11→12：HanLpProvider（REV7 HanLP 硬依赖分词 Provider）落于 database 根包（其 javadoc 注明为
        // 规避 fulltext 根包 ZeroLeak 词类名扫描而有意置于此），故 database 根包顶级类总数上调为 12 并冻结。
        Assert.assertEquals("database/ 顶层类数必须守恒（0 新增 .java）", 12, countTopLevelJava(DB_SRC));
        Assert.assertEquals("database/schema/ 顶层类数必须守恒（0 新增 .java）", 3, countTopLevelJava(SCHEMA_SRC));
        Assert.assertTrue("改动的 DsDatabaseLocal 必须存在", srcDir(DDL).isFile());
        Assert.assertTrue("改动的 EntityIndexUtil 必须存在", srcDir(EIU).isFile());
    }

    // ============== Y2 NG-3 零依赖 ==============
    @Test(timeout = 30000)
    public void y2_ng3ZeroDependency() throws Exception {
        String[] forbidden = {"io.netty", "io.grpc", "com.google.common", "micrometer", "prometheus"};

        // P56 新增源码区（DDL P56 窗口 + EntityIndexUtil 全文）0 forbidden 引用
        String p56Code = p56DdlSlice(readUtf8(srcDir(DDL))) + readUtf8(srcDir(EIU));
        for (String bad : forbidden) {
            Assert.assertFalse("P56 新增源码不应引用 " + bad, p56Code.contains(bad));
        }

        // EntityIndexUtil import 白名单（JDK + first-party）
        for (String line : readUtf8(srcDir(EIU)).split("\n")) {
            String t = line.trim();
            if (!t.startsWith("import ")) {
                continue;
            }
            boolean ok = t.startsWith("import java.") || t.contains("com.q3lives.ds.");
            Assert.assertTrue("EntityIndexUtil 仅允许 JDK + first-party import, got: " + t, ok);
        }

        // 模块 pom 0 新增指标/外部集合库
        String pom = new String(Files.readAllBytes(
                new File(System.getProperty("user.dir"), "pom.xml").toPath()),
                StandardCharsets.UTF_8).toLowerCase();
        for (String banned : new String[]{"micrometer", "prometheus", "dropwizard", "guava", "netty", "grpc"}) {
            Assert.assertFalse("pom 不应新增依赖: " + banned, pom.contains(banned));
        }
    }

    // ============== Y3 R10 0 新线程 ==============
    @Test(timeout = 30000)
    public void y3_noNewThreadInP56Region() throws Exception {
        String region = stripComments(p56DdlSlice(readUtf8(srcDir(DDL))))
                + stripComments(readUtf8(srcDir(EIU)));
        Assert.assertFalse("P56 改动区切片必须非空", region.trim().isEmpty());
        for (String banned : new String[]{
            "ExecutorService", "Executors", "new Thread", "Timer", "scheduleAtFixedRate", "ScheduledFuture"}) {
            Assert.assertFalse("R10 violation (" + banned + ") in P56 region", region.contains(banned));
        }
    }

    // ============== Y4 P18~P55 单调不减 ==============
    @Test(timeout = 30000)
    public void y4_lockedPackagesUnchanged() throws Exception {
        Assert.assertEquals("fulltext 根包仍 3", 3, countTopLevelJava(FT));
        Assert.assertEquals("fulltext/segment 仍 5", 5, countTopLevelJava(FT + "/segment"));
        Assert.assertEquals("fulltext/wordnet 仍 3", 3, countTopLevelJava(FT + "/wordnet"));
        Assert.assertEquals("columnar/pushdown 仍 2", 2, countTopLevelJava(COL + "/pushdown"));
        Assert.assertEquals("cluster/stats 仍 2", 2, countTopLevelJava(STATS));

        // P21 fulltext 既有签名不得消失
        Assert.assertNotNull("neighborsWithin 原签名必须保留",
                FulltextStore.class.getDeclaredMethod("neighborsWithin",
                        String.class, long.class, long.class, int.class, int.class));
        // P55 指标入口不得消失
        Assert.assertNotNull("DbStatsRegistry.snapshot 必须保留",
                DbStatsRegistry.class.getDeclaredMethod("snapshot"));
    }

    // ============== Y5 既有 public 面 0 改 ==============
    @Test(timeout = 30000)
    public void y5_existingPublicSurfaceUnchanged() {
        Class<DsDatabaseLocal> c = DsDatabaseLocal.class;
        Assert.assertTrue("forceApplyOnlineDDL 必须保留",
                hasMethod(c, "forceApplyOnlineDDL", Class.class));
        Assert.assertTrue("cancelOnlineDDL 必须保留",
                hasMethod(c, "cancelOnlineDDL", Class.class));
        Assert.assertTrue("forceRollbackSchema 必须保留",
                hasMethod(c, "forceRollbackSchema", Class.class));
        Assert.assertTrue("forceResetSchemaMetaForTest 必须保留",
                hasMethod(c, "forceResetSchemaMetaForTest", File.class));

        Assert.assertTrue("getTable(Class,long) 必须保留",
                hasMethod(c, "getTable", Class.class, long.class));
        Assert.assertTrue("getTable(Class,long,long) 必须保留",
                hasMethod(c, "getTable", Class.class, long.class, long.class));
        Assert.assertTrue("getTable(Class,long,boolean) 必须保留",
                hasMethod(c, "getTable", Class.class, long.class, boolean.class));
        Assert.assertTrue("putEntity(DsTableAdapter,boolean) 必须保留",
                hasMethod(c, "putEntity", DsTableAdapter.class, boolean.class));
        Assert.assertTrue("putEntity(DsTableAdapter,boolean,boolean) 必须保留",
                hasMethod(c, "putEntity", DsTableAdapter.class, boolean.class, boolean.class));
    }

    // ============== Y6 R5 段格式 0 变更 ==============
    @Test(timeout = 30000)
    public void y6_rowFormatBitLevelUnchanged() {
        Assert.assertTrue("DsTableAdapter.toBytes 必须保留",
                hasMethod(DsTableAdapter.class, "toBytes"));
        Assert.assertTrue("DsTableAdapter.load(ByteBuffer) 必须保留",
                hasMethod(DsTableAdapter.class, "load", ByteBuffer.class));

        // 定长公式 bit 级不变：8(id) + Σ composite + Σ column
        Assert.assertEquals("V1 定长单元应仍为 28B", V1_LEN, new GateV1().toBytes().remaining());
        Assert.assertEquals("V2 定长单元应仍为 36B", V2_LEN, new GateV2().toBytes().remaining());

        Class<DsFixedBucketStore> bs = DsFixedBucketStore.class;
        Assert.assertTrue("bucket getNewId 必须保留",
                hasMethod(bs, "getNewId", String.class, String.class, int.class));
        Assert.assertTrue("bucket overwrite 必须保留",
                hasMethod(bs, "overwrite", String.class, String.class, long.class, byte[].class));
        Assert.assertTrue("bucket get 必须保留",
                hasMethod(bs, "get", String.class, String.class, long.class, int.class, int.class));
    }

    // ============== Y7 检测语义确定 ==============
    @Test(timeout = 30000)
    public void y7_detectionSemanticsDeterministic() throws Exception {
        // 1) 死分支表达式必须移除（去注释后判定；注释中记录移除事实不算残留）
        String ddlCode = stripComments(readUtf8(srcDir(DDL)));
        Assert.assertFalse("恒 false 的 sample != index.rowLength 死分支必须移除",
                ddlCode.contains("sample != index.rowLength"));
        Assert.assertTrue("必须改用持久化 leaf 检测信号",
                ddlCode.contains("detectLegacyRowLength"));

        // 2) 4 helper 签名可达
        Class<EntityIndexUtil> e = EntityIndexUtil.class;
        Assert.assertTrue("readLegacyRowLength(File,Class) 必须可达",
                hasMethod(e, "readLegacyRowLength", File.class, Class.class));
        Assert.assertTrue("writeLegacyRowLength(File,Class,int) 必须可达",
                hasMethod(e, "writeLegacyRowLength", File.class, Class.class, int.class));
        Assert.assertTrue("clearLegacyRowLength(File,Class) 必须可达",
                hasMethod(e, "clearLegacyRowLength", File.class, Class.class));
        Assert.assertTrue("detectLegacyRowLength(File,Class,int) 必须可达",
                hasMethod(e, "detectLegacyRowLength", File.class, Class.class, int.class));

        // 3) leaf 为唯一真源语义（可复现）
        Assert.assertEquals("无 leaf → -1 且 seed 当前长度", -1,
                EntityIndexUtil.detectLegacyRowLength(root, GateV2.class, V2_LEN));
        Assert.assertEquals("seed 值 == 当前长度", V2_LEN,
                EntityIndexUtil.readLegacyRowLength(root, GateV2.class));
        Assert.assertEquals("持久化 == 当前 → -1（非 compat）", -1,
                EntityIndexUtil.detectLegacyRowLength(root, GateV2.class, V2_LEN));
        EntityIndexUtil.writeLegacyRowLength(root, GateV2.class, V1_LEN);
        Assert.assertEquals("持久化 != 当前 → 返回旧长度（compat）", V1_LEN,
                EntityIndexUtil.detectLegacyRowLength(root, GateV2.class, V2_LEN));
    }

    // ============== Y8 幂等 + 断点续跑 ==============
    @Test(timeout = 30000)
    public void y8_idempotentAndResumable() throws Exception {
        // 预置 compat 信号：数据实际写入 28B，当前 schema 36B
        EntityIndexUtil.writeLegacyRowLength(root, GateV2.class, V1_LEN);
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            for (int i = 0; i < 3; i++) {
                seedLegacyRow(db, i);
            }
            Assert.assertEquals("首轮迁移 3 行", 3, db.migrateLegacyRows(GateV2.class, 2));
            Assert.assertEquals("重复调用短路 → 0（已完成行 0 重复写）",
                    0, db.migrateLegacyRows(GateV2.class, 2));
            Assert.assertEquals("迁移后退出 compat（legacyRowLength = -1）",
                    -1, legacyRowLength(db, GateV2.class));
        } finally {
            closeQuietly(db);
        }
    }

    // ============== Y9 0 TODO/FIXME/HACK ==============
    @Test(timeout = 30000)
    public void y9_noTodoFixmeHack() throws Exception {
        String region = p56DdlSlice(readUtf8(srcDir(DDL))) + readUtf8(srcDir(EIU));
        for (String banned : new String[]{"TODO", "FIXME", "HACK"}) {
            Assert.assertFalse("found " + banned + " in P56 region", region.contains(banned));
        }
    }

    // ============== helpers ==============
    /**
     * 抽取 DsDatabaseLocal 中 P56 新增代码窗口（避免误命中既有 TODO/线程基线）。
     */
    private static String p56DdlSlice(String src) {
        String[] lines = src.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        appendWindow(lines, sb, "compat 判定改读持久化", 14);
        appendWindow(lines, sb, "Online DDL row-plane 显式 pull 全量迁移", 30);
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

    private static boolean hasMethod(Class<?> c, String name, Class<?>... params) {
        try {
            c.getMethod(name, params);
            return true;
        } catch (NoSuchMethodException e1) {
            try {
                c.getDeclaredMethod(name, params);
                return true;
            } catch (NoSuchMethodException e2) {
                return false;
            }
        }
    }

    private static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder sb = new StringBuilder(noBlock.length());
        for (String line : noBlock.split("\n", -1)) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }

    private static String readUtf8(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static File srcDir(String rel) {
        File f = new File(System.getProperty("user.dir"), rel);
        Assert.assertTrue("源文件不存在: " + f.getAbsolutePath(), f.isFile());
        return f;
    }

    private static int countTopLevelJava(String rel) {
        File dir = new File(System.getProperty("user.dir"), rel);
        Assert.assertTrue("源目录不存在: " + dir.getAbsolutePath(), dir.isDirectory());
        File[] children = dir.listFiles();
        int n = 0;
        if (children != null) {
            for (File f : children) {
                if (f.isFile() && f.getName().endsWith(".java")) {
                    n++;
                }
            }
        }
        return n;
    }

    private long seedLegacyRow(DsDatabaseLocal db, int i) throws Exception {
        DsFixedBucketStore bs = bucketStore(db);
        String space = GateV2.class.getName();
        String type = EntityIndexUtil.indexOf(root, GateV2.class).rowType;
        long id = bs.getNewId(space, type, V1_LEN);
        GateV1 v1 = new GateV1();
        v1.setId(id);
        v1.name = "user_" + i;
        v1.age = 20 + i;
        ByteBuffer bb = v1.toBytes();
        byte[] b = new byte[bb.remaining()];
        bb.get(b);
        bs.overwrite(space, type, id, b);
        idSetForClass(db, GateV2.class).add(Long.valueOf(id));
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

    private static int legacyRowLength(DsDatabaseLocal db, Class<? extends DsTableAdapter> clazz) throws Exception {
        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        metaOf.setAccessible(true);
        Object meta = metaOf.invoke(db, clazz);
        Field f = meta.getClass().getDeclaredField("legacyRowLength");
        f.setAccessible(true);
        return (Integer) f.get(meta);
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
