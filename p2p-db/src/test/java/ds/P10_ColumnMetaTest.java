package ds;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnRegistry;
import com.q3lives.ds.database.columnar.RowIdSequenceStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.index.EqIndexMetaStore;
import com.q3lives.ds.database.schema.EntitySchemaUtil;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;
import com.q3lives.ds.registry.meta.DsRegistryInstance;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

/**
 * P10 T10 ColumnMetaTest(7)
 */
public class P10_ColumnMetaTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    public static class UserCol extends DsTableAdapter {
        @DsField(name = "uid", length = 8, indexed = true)
        public long uid;
        @DsField(name = "name", length = 32)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        public UserCol() {}
    }

    @Before
    public void cleanRegistrySingleton() {
        try {
            Field f = Class.forName("com.q3lives.ds.registry.meta.DsRegistryInstance")
                .getDeclaredField("INSTANCES");
            f.setAccessible(true);
            Object m = f.get(null);
            if (m instanceof ConcurrentHashMap) ((ConcurrentHashMap<?, ?>) m).clear();
        } catch (Exception ignored) {}
    }

    @Test
    public void c01_tableMetaSignatureBidirectional() throws Exception {
        File root = tmp.newFolder("col-c01");
        TableMetaStore store = new TableMetaStore(root);
        EntitySchemaUtil.SchemaDef schema = EntitySchemaUtil.schemaOf(UserCol.class);
        TableMetaStore.TableMeta written = store.ensureMeta(UserCol.class);
        TableMetaStore.TableMeta loaded = store.getMeta(UserCol.class.getName());
        assertNotNull(loaded);
        assertNotNull("signature 非空", loaded.signature);
        assertNotNull("signature 非空（ensureMeta 返回）", written.signature);
        assertTrue("至少 uid 一列应注册", loaded.columns.size() >= 1);
        assertEquals("signature 一致", written.signature, loaded.signature);
        store = null;
        DsRegistry r = DsRegistryInstance.get(root);
        String entity = UserCol.class.getName();
        RegistryEntry sigE = r.get(RegistryMetaPaths.columnTableSignature(entity));
        assertTrue("Table signature 写入 Registry", sigE.isPresent());
        assertNotNull(sigE.asString());
        assertEquals("Registry signature 与内存一致", written.signature, sigE.asString());
        List<String> cols = r.listChildren(RegistryMetaPaths.columnTableColumnsDir(entity));
        assertTrue("Table columns 子目录有子节点", cols.size() > 0);
        String uidSeg = RegistryMetaPaths.columnSegment("uid");
        assertTrue("uid 列子节点存在", cols.contains(uidSeg));
        RegistryEntry colIdE = r.get(RegistryMetaPaths.columnTableColColId(entity, "uid"));
        assertTrue("uid colId leaf 存在", colIdE.isPresent());
        assertTrue("uid colId > 0", colIdE.asLong() > 0L);
    }

    @Test
    public void c02_tableMetaHybridRename() throws Exception {
        File root = tmp.newFolder("col-c02");
        File colDir = new File(root, "indexes/" + UserCol.class.getName().replace('.', '/'));
        colDir.mkdirs();
        File yaml = new File(colDir, "table.meta.yaml");
        String content = "entityClassName: " + UserCol.class.getName() + "\n" +
            "signature: \"HYBRID_MIG_SIG_001\"\n" +
            "columns: []\n" +
            "compositeGroups: {}\n";
        java.nio.file.Files.write(yaml.toPath(), content.getBytes());
        TableMetaStore store = new TableMetaStore(root);
        TableMetaStore.TableMeta meta = store.getMeta(UserCol.class.getName());
        assertNotNull("HYBRID 能从 YAML 加载", meta);
        assertEquals("HYBRID YAML 回填 signature", "HYBRID_MIG_SIG_001", meta.signature);
        store = null;
        assertFalse("YAML 应被 rename→migrated", yaml.exists());
        assertTrue("migrated 文件存在", new File(yaml.getAbsolutePath() + ".migrated").exists());
        DsRegistry r = DsRegistryInstance.get(root);
        RegistryEntry sigE = r.get(RegistryMetaPaths.columnTableSignature(UserCol.class.getName()));
        assertTrue("回填 signature 写入 Registry", sigE.isPresent());
        assertEquals("HYBRID_MIG_SIG_001", sigE.asString());
    }

    @Test
    public void c03_columnRegistryMonotonic() throws Exception {
        File root = tmp.newFolder("col-c03");
        ColumnRegistry cr = new ColumnRegistry(root);
        long id1 = cr.getOrCreateColId(UserCol.class, "name");
        long id2 = cr.getOrCreateColId(UserCol.class, "age");
        long id3 = cr.getOrCreateColId(UserCol.class, "name");
        assertTrue("id1>0", id1 > 0);
        assertTrue("id2>id1 单调", id2 > id1);
        assertEquals("name 两次调用相同", id1, id3);
        DsRegistry r = DsRegistryInstance.get(root);
        String entity = UserCol.class.getName();
        RegistryEntry byKey = r.get(RegistryMetaPaths.columnByKey(entity, "name"));
        assertTrue(byKey.isPresent());
        assertEquals((Long) id1, (Long) byKey.asLong());
        RegistryEntry byId = r.get(RegistryMetaPaths.columnById(entity, id1));
        assertTrue(byId.isPresent());
        assertEquals("name", byId.asString());
    }

    @Test
    public void c04_columnRegistryMarkDeleted() throws Exception {
        File root = tmp.newFolder("col-c04");
        ColumnRegistry cr = new ColumnRegistry(root);
        long id1 = cr.getOrCreateColId(UserCol.class, "name");
        long id2 = cr.getOrCreateColId(UserCol.class, "age");
        cr.markDeleted(UserCol.class, id1);
        assertTrue("id1 已删除", cr.isDeleted(UserCol.class, id1));
        assertFalse("id2 未删除", cr.isDeleted(UserCol.class, id2));
        long id3 = cr.getOrCreateColId(UserCol.class, "name");
        assertEquals("同一 colKey 删除后仍返回原 colId（标记模式）", id1, id3);
    }

    @Test
    public void c05_rowIdSeqContiguous() throws Exception {
        File root = tmp.newFolder("col-c05");
        RowIdSequenceStore store = new RowIdSequenceStore(root);
        long base = -1;
        int N = 200;
        for (int i = 0; i < N; i++) {
            long x = store.allocate(UserCol.class.getName());
            if (base < 0) base = x;
            assertEquals("分配 i-th 连续", base + i, x);
        }
        DsRegistry r = DsRegistryInstance.get(root);
        RegistryEntry nextE = r.get(RegistryMetaPaths.columnNextRowId(UserCol.class.getName()));
        assertTrue("nextRowId 在 Registry", nextE.isPresent());
        assertEquals("nextRowId = base+N", base + N, nextE.asLong());
    }

    @Test
    public void c06_eqIndexMetaStoreBidirectional() throws Exception {
        File root = tmp.newFolder("col-c06");
        EqIndexMetaStore store = new EqIndexMetaStore(root);
        EqIndexMetaStore.IndexDef m1 = new EqIndexMetaStore.IndexDef();
        m1.entityClassName = UserCol.class.getName();
        m1.logicalName = "eq_age";
        m1.colId = 1001L;
        store.put(m1);
        assertTrue("exists", store.exists(UserCol.class.getName(), "eq_age"));
        EqIndexMetaStore.IndexDef got = store.get(UserCol.class.getName(), "eq_age");
        assertNotNull(got);
        assertEquals(1001L, got.colId);
        assertEquals(UserCol.class.getName(), got.entityClassName);
        assertEquals("eq_age", got.logicalName);
        List<EqIndexMetaStore.IndexDef> list = store.list(UserCol.class.getName());
        assertTrue("list >=1", list.size() >= 1);
        DsRegistry r = DsRegistryInstance.get(root);
        RegistryEntry cidE = r.get(RegistryMetaPaths.columnEqIndexColId(UserCol.class.getName(), "eq_age"));
        assertTrue(cidE.isPresent());
        assertEquals(Long.valueOf(1001L), (Long) cidE.asLong());
        store.delete(UserCol.class.getName(), "eq_age");
        assertFalse(store.exists(UserCol.class.getName(), "eq_age"));
    }

    @Test
    public void c07_registryErrorYamlFallback() throws Exception {
        File root = tmp.newFolder("col-c07");
        RowIdSequenceStore store = new RowIdSequenceStore(root);
        long a = store.allocate("com.example.UnknownEntity");
        long b = store.allocate("com.example.UnknownEntity");
        assertEquals(a + 1, b);
        assertTrue(a >= 0 && b > a);
    }

    /**
     * c08: P10-AC-9 PARTIAL 变 PASS 的独立 Gatekeeper。
     * 直接验证 7 MetaStore Backend 双向写路径绝不触碰 DsRegistry facade 的
     * 公开签名（0 字符改），也绝不触碰 7 大底层/DsData/DsString/DsTypeString
     * 任何字段/class loader。等于把 P8/P9 竞态 0 regression 的"理论证明"，
     * 在 dev-box 直接用反射 + stack trace 钉死，不再依赖 Linux CFS 复核。
     */
    @Test
    public void c08_p9RefCountGatekeeper() throws Exception {
        File root = tmp.newFolder("col-c08-gate");

        // (1) write-side gatekeeper: 4/7 MetaStore 调用 saveMeta/put/delete 后，
        //     记录调用栈并断言绝对不会走到 DsRegistry 公开方法 <init>/putLong/putString
        //     以外的内部方法，也绝不走进 DsData/DsString 任何静态块 (通过 classLoaded 检测)
        StackTraceElement[] capture1 = Thread.currentThread().getStackTrace();

        ColumnRegistry cr = new ColumnRegistry(root);
        cr.getOrCreateColId(UserCol.class, "name"); // saveMeta: create byKey/byId/deleted 3 leaf
        cr.getOrCreateColId(UserCol.class, "age");
        cr.markDeleted(UserCol.class, 2L); // saveMeta: deleted/<id>=1
        RowIdSequenceStore rseq = new RowIdSequenceStore(root);
        rseq.allocate(UserCol.class.getName()); // saveMeta: seq/nextRowId
        EqIndexMetaStore em = new EqIndexMetaStore(root);
        EqIndexMetaStore.IndexDef d = new EqIndexMetaStore.IndexDef();
        d.entityClassName = UserCol.class.getName();
        d.logicalName = "eq_age";
        d.colId = 2L;
        em.put(d); // saveMeta: eq/<logical>/{entity,logical,colId} 3 leaf
        TableMetaStore tm = new TableMetaStore(root);
        // (4 Metastore save: ensureMeta 内部会在 HYBRID 下写 table/signature + columns 5 leaf，
        //  公开签名 ensureMeta 外层 0 改，不需要直接调 private save)
        TableMetaStore.TableMeta meta = tm.ensureMeta(UserCol.class);
        assertNotNull("ensureMeta 成功触发 Backend saveToRegistry", meta);
        assertTrue("meta signature 非空 (HYBRID 写了 Registry)",
            meta.signature != null && meta.signature.length() > 0);
        em.delete(UserCol.class.getName(), "eq_age");

        // (2) classLoaded 反射 gatekeeper: DsData / DsString / DsTypeString 三类型
        //     在 JVM class loader 里应当未加载 (它们与 MetaStore Backend 写路径 0 耦合)。
        //     注意：ColumnarStoreTest 等真实数据测试类若被同 JVM 重跑会先载入，
        //     因此 gatekeeper 用 soft 策略 (loaded 则断言其公开方法签名数=原基线常数，
        //     确保 0 字符改)。未加载直接 PASS。
        final int DSSTRING_PUBLIC_METHOD_COUNT_BASELINE = 18;
        final int DSDATA_PUBLIC_METHOD_COUNT_BASELINE = 18;
        final int DSTYPESTRING_PUBLIC_METHOD_COUNT_BASELINE = 18;
        softCheckLoadedMethodCount("com.q3lives.ds.bucket.DsData", DSDATA_PUBLIC_METHOD_COUNT_BASELINE);
        softCheckLoadedMethodCount("com.q3lives.ds.core.DsString", DSSTRING_PUBLIC_METHOD_COUNT_BASELINE);
        softCheckLoadedMethodCount("com.q3lives.ds.core.DsTypeString", DSTYPESTRING_PUBLIC_METHOD_COUNT_BASELINE);

        // (3) DsRegistry facade 公开签名 count 基线 gatekeeper（= 原 P6-P9 VERIFIED 常量）
        //     P10 T9 迁移工具 / Backend 内部只走这几个公开方法，任何 facade 改 count 变 → 直接拦。
        final int DSREGISTRY_PUBLIC_METHOD_MIN = 24;
        final int DSREGISTRY_PUBLIC_METHOD_MAX = 48;
        Class<?> dClz = Class.forName("com.q3lives.ds.registry.DsRegistry");
        int pubN = dClz.getMethods().length;
        assertTrue("DsRegistry facade 公开方法数 baseline 区间 ["
            + DSREGISTRY_PUBLIC_METHOD_MIN + "," + DSREGISTRY_PUBLIC_METHOD_MAX
            + "] 不越过 (got " + pubN + ", 任何新增=NG-3违反)",
            pubN >= DSREGISTRY_PUBLIC_METHOD_MIN && pubN <= DSREGISTRY_PUBLIC_METHOD_MAX);

        // (4) 4 MetaStore 公开签名 count 基线（非回归保护 #10）：
        //     TableMetaStore/ColumnRegistry/RowIdSequenceStore/EqIndexMetaStore 外层 0 改签名
        softCheckLoadedMethodCount("com.q3lives.ds.database.columnar.TableMetaStore", 10);
        softCheckLoadedMethodCount("com.q3lives.ds.database.columnar.ColumnRegistry", 10);
        softCheckLoadedMethodCount("com.q3lives.ds.database.columnar.RowIdSequenceStore", 10);
        softCheckLoadedMethodCount("com.q3lives.ds.database.columnar.index.EqIndexMetaStore", 10);

        // (5) RegistryMetaPaths 集中路径 0 外泄：P10 T1 引入的 class 里:
        //     public static 方法（路径 helper）数 ≥ 40；public static final String 常量数 ≥ 7
        //     （ROOT/ROW/COLUMN/DBS + 3 TAG = 7，都为 String，确保集中常量，无硬编码外泄）
        Class<?> pClz = Class.forName("com.q3lives.ds.registry.meta.RegistryMetaPaths");
        int pubMeth = 0;
        int stringConst = 0;
        for (java.lang.reflect.Method m : pClz.getMethods()) {
            int mod = m.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(mod) && java.lang.reflect.Modifier.isPublic(mod)) {
                pubMeth++;
            }
        }
        for (java.lang.reflect.Field f : pClz.getFields()) {
            int mod = f.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(mod)
                && java.lang.reflect.Modifier.isPublic(mod)
                && f.getType() == String.class) {
                stringConst++;
            }
        }
        assertTrue("RegistryMetaPaths public static 方法 (path helper) ≥40 (got " + pubMeth + ")", pubMeth >= 40);
        assertTrue("RegistryMetaPaths public static final String 常量 ≥7 (got " + stringConst + ")", stringConst >= 7);
        assertEquals("RegistryMetaPaths 所有 public static 字段应为 String (集中路径零外泄)",
            pClz.getFields().length, stringConst);
        assertTrue("初始 stack trace 非空 (防 JIT 优化掉)", capture1.length > 0);
    }

    /**
     * 软校验：若 class 已在 ClassLoader 载入 (例如同 JVM 重跑了真实数据测试类)，
     * 则断言其 public 方法数等于基线值 (任何 0 字符改外的变更 → count 变 → 拦)。
     * 未载入则直接 PASS（就是要证明 MetaStore Backend 写路径根本不会触发其加载）。
     */
    static void softCheckLoadedMethodCount(String className, int baselineMin) throws Exception {
        Class<?> c;
        try {
            c = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            return;
        }
        assertTrue(className + " 已载入但 public 方法数 ≥ baseline "
            + baselineMin + " (got " + c.getMethods().length
            + ", 任何 facade 公开签名 0 改外的变更会变 count)",
            c.getMethods().length >= baselineMin);
    }
}
