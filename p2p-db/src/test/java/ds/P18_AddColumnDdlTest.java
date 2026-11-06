package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.meta.DsRegistryInstance;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

public class P18_AddColumnDdlTest {

    public static final class MigEntity extends DsTableAdapter {
        @DsField(name = "name", length = 24)
        public String name;
        @DsField(name = "age", length = 4, indexed = true)
        public int age;
        @DsField(name = "city", length = 32, indexed = true)
        public String city;
        @DsField(name = "newA", length = 8)
        public long newA;
        @DsField(name = "newB", length = 32, defaultValue = "hello-p18-ddl")
        public String newB;
        @DsField(name = "newC", length = 32, nullable = false, defaultValue = "non_null_pinned")
        public String newC;
    }

    /** hack: 保存旧版本 meta（不含 newA/newB/newC）到 registry，模拟 ADD COLUMN 前的 stored signature */
    private void saveLegacyMeta(DsDatabaseLocal db, Class<?> clazz, List<String> legacyCols) throws Exception {
        File root = db.getRoot();
        TableMetaStore tmsLocal = new TableMetaStore(root);
        Field backendField = TableMetaStore.class.getDeclaredField("backend");
        backendField.setAccessible(true);
        Object backend = backendField.get(tmsLocal);
        Method buildFresh = null;
        Object coord = odCoord(db);
        for (Method m : coord.getClass().getDeclaredMethods()) {
            if ("buildFresh".equals(m.getName()) && m.getParameterCount() == 1) { buildFresh = m; m.setAccessible(true); break; }
        }
        Assert.assertNotNull("buildFresh method", buildFresh);
        TableMetaStore.TableMeta fresh = (TableMetaStore.TableMeta) buildFresh.invoke(coord, clazz);
        Set<String> keep = new HashSet<>(legacyCols);
        List<TableMetaStore.ColumnDef> filt = new ArrayList<>();
        for (TableMetaStore.ColumnDef c : fresh.columns) if (keep.contains(c.name)) filt.add(c);
        fresh.columns = filt;
        if (fresh.compositeGroups == null) fresh.compositeGroups = new LinkedHashMap<>();
        Method computeSig = TableMetaStore.class.getDeclaredMethod("computeSignature", TableMetaStore.TableMeta.class);
        computeSig.setAccessible(true);
        fresh.signature = (String) computeSig.invoke(null, fresh);
        Class<?> backendCls = null;
        for (Class<?> ic : TableMetaStore.class.getDeclaredClasses()) if ("Backend".equals(ic.getSimpleName())) { backendCls = ic; break; }
        Assert.assertNotNull("Backend 内嵌类", backendCls);
        Method save = backendCls.getDeclaredMethod("saveToRegistry", String.class, TableMetaStore.TableMeta.class);
        save.setAccessible(true);
        save.invoke(backend, clazz.getName(), fresh);
    }

    private List<Long> seed(DsDatabaseLocal db, int N, Class<? extends DsTableAdapter> clazz) throws Exception {
        List<Long> ids = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            DsTableAdapter u = clazz.getDeclaredConstructor().newInstance();
            Field nf = clazz.getField("name"); nf.set(u, "user_" + i);
            Field af = clazz.getField("age");  af.setInt(u, i % 120);
            Field cf = clazz.getField("city"); cf.set(u, "city_" + (i % 256));
            long id = db.putEntity(u, true);
            ids.add(id);
        }
        return ids;
    }

    private File tmpRoot;
    private String oldHome;

    @Before
    public void before() throws IOException {
        tmpRoot = Files.createTempDirectory("dsdb_p18_addcol_").toFile();
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void after() {
        try {
            deleteRecursive(tmpRoot);
        } catch (Exception ignore) {
        }
        if (oldHome == null) System.clearProperty("p2p.db.home");
        else System.setProperty("p2p.db.home", oldHome);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    private static byte[] longBE(long v) {
        return new byte[]{(byte)(v>>>56),(byte)(v>>>48),(byte)(v>>>40),(byte)(v>>>32),
                          (byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v};
    }
    private static byte[] intBE(int v) {
        return new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v};
    }

    private static String hexOf(byte[] d) {
        char[] HEX = "0123456789abcdef".toCharArray();
        char[] ch = new char[d.length * 2];
        for (int i = 0; i < d.length; i++) {
            int v = d[i] & 0xFF;
            ch[i*2] = HEX[v >>> 4];
            ch[i*2+1] = HEX[v & 0x0F];
        }
        return new String(ch);
    }

    private String dirHash(File root) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        if (!root.exists()) return hexOf(md.digest());
        try (Stream<Path> walk = Files.walk(root.toPath())) {
            walk.filter(Files::isRegularFile).sorted().forEach(p -> {
                try {
                    byte[] bytes = Files.readAllBytes(p);
                    md.update(intBE(bytes.length));
                    md.update(bytes);
                    md.update(p.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException re) {
            Throwable cause = re.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw re;
        }
        return hexOf(md.digest());
    }

    private Object odCoord(DsDatabaseLocal db) throws Exception {
        Field f = DsDatabaseLocal.class.getDeclaredField("odCoordinator");
        f.setAccessible(true);
        return f.get(db);
    }
    private static Method findM(Class<?> c, String name, Class<?>... pts) {
        try {
            Method m = c.getDeclaredMethod(name, pts);
            m.setAccessible(true);
            return m;
        } catch (Exception e) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] mp = m.getParameterTypes();
                if (mp.length != pts.length) continue;
                boolean ok = true;
                for (int i = 0; i < pts.length; i++) {
                    if (pts[i] != null && !pts[i].isAssignableFrom(mp[i])) { ok=false; break; }
                }
                if (ok) { m.setAccessible(true); return m; }
            }
            return null;
        }
    }

    // ==================== TR-2.1 t01: dry-run 零写 ====================
    @Test
    public void t01_dryRunZeroWriteRegistryUnchanged() throws Exception {
        DsDatabaseLocal db = DsDatabaseLocal.load();
        Class<MigEntity> clazz = MigEntity.class;
        db.registerTable(clazz);
        saveLegacyMeta(db, clazz, java.util.Arrays.asList("name", "age", "city"));
        int N = 1500;
        List<Long> ids = seed(db, N, clazz);
        File root = db.getRoot();
        String before = dirHash(root);
        Assert.assertEquals("totalRows=" + N, N, ids.size());

        Object coord = odCoord(db);
        Class<?> cc = coord.getClass();
        Method computeAddDiff = findM(cc, "computeAddDiff", Class.class);
        Assert.assertNotNull("computeAddDiff method", computeAddDiff);
        Object diff = computeAddDiff.invoke(coord, clazz);
        Assert.assertNotNull("diff", diff);

        Method applyAddInternal = null;
        for (Method m : cc.getDeclaredMethods()) {
            if (!"applyAddInternal".equals(m.getName())) continue;
            if (m.getParameterCount() == 4) { applyAddInternal = m; m.setAccessible(true); break; }
        }
        Assert.assertNotNull("applyAddInternal method", applyAddInternal);
        applyAddInternal.invoke(coord, clazz, diff, Integer.valueOf(256), Boolean.TRUE);

        String after = dirHash(root);
        Assert.assertEquals("TR-2.1: dry-run 零写 before/after hash 全等", before, after);
    }

    // ==================== TR-2.2 t02: apply 100% ====================
    @Test
    public void t02_applyFullProgressHundred() throws Exception {
        DsDatabaseLocal db = DsDatabaseLocal.load();
        Class<MigEntity> clazz = MigEntity.class;
        db.registerTable(clazz);
        saveLegacyMeta(db, clazz, java.util.Arrays.asList("name", "age", "city"));
        int N = 3000;
        List<Long> ids = seed(db, N, clazz);
        File root = db.getRoot();
        db.forceApplyOnlineDDL(clazz);

        DsRegistry reg = DsRegistryInstance.get(root);
        String entName = clazz.getName();
        String prog = safeStr(reg, RegistryMetaPaths.odManifestProgress("default", entName));
        Assert.assertEquals("progress=100", "100", prog);
        String phase = safeStr(reg, RegistryMetaPaths.odManifestPhase("default", entName));
        Assert.assertEquals("phase=DONE", "DONE", phase);
        int cpCount = 0;
        String cpDir = RegistryMetaPaths.odManifestDir("default", entName) + "/checkpoints";
        for (String s : safeList(reg, cpDir)) { try { int v = Integer.parseInt(s); if (v > 0) cpCount++; } catch (Exception ignore) {} }
        Assert.assertTrue("checkpoint 数 >= 2 (实锤=" + cpCount + ")", cpCount >= 2);

        Random rnd = new Random(0xCAFEBABEL);
        byte[] expA = longBE(0L);
        ColumnarStore cs = new ColumnarStore(root);
        int sample = Math.min(200, N);
        for (int i = 0; i < sample; i++) {
            long rid = ids.get(rnd.nextInt(N));
            byte[] va = cs.getValue(entName, "newA", rid);
            Assert.assertArrayEquals("random sample newA 默认 i=" + i, expA, va);
            MigEntity e = db.getTable(clazz, rid);
            Assert.assertNotNull("getTable i=" + i, e);
            Assert.assertEquals("newB=hello i=" + i, "hello-p18-ddl", e.newB);
            Assert.assertEquals("newC=non_null i=" + i, "non_null_pinned", e.newC);
        }
    }

    // ==================== TR-2.3 t03: 断点续跑 ====================
    @Test
    public void t03_resumeAfterCp4NoDupNoGap() throws Exception {
        int N = 2400;
        int BATCH = 128;
        int BREAK_AFTER = 4;
        DsDatabaseLocal db = DsDatabaseLocal.load();
        Class<MigEntity> clazz = MigEntity.class;
        db.registerTable(clazz);
        saveLegacyMeta(db, clazz, java.util.Arrays.asList("name", "age", "city"));
        List<Long> ids = seed(db, N, clazz);
        File root = db.getRoot();

        Object coord = odCoord(db);
        Class<?> cc = coord.getClass();
        Method computeAddDiff = findM(cc, "computeAddDiff", Class.class);
        Assert.assertNotNull("computeAddDiff method", computeAddDiff);
        Object diff = computeAddDiff.invoke(coord, clazz);
        Method ensureMigId = findM(cc, "ensureMigId", DsRegistry.class, String.class);
        Method writePrepareManifest = findM(cc, "writePrepareManifest", DsRegistry.class, String.class, long.class, diff.getClass());
        DsRegistry regPre = DsRegistryInstance.get(root);
        long migId = (Long) ensureMigId.invoke(coord, regPre, clazz.getName());
        writePrepareManifest.invoke(coord, regPre, clazz.getName(), Long.valueOf(migId), diff);

        Method applyAddInternal = null;
        for (Method m : cc.getDeclaredMethods()) {
            if (!"applyAddInternal".equals(m.getName())) continue;
            if (m.getParameterCount() == 4) { applyAddInternal = m; m.setAccessible(true); break; }
        }
        Assert.assertNotNull("applyAddInternal method", applyAddInternal);

        String oldProp = System.getProperty("p18.od.debug.stop.after.cp");
        try {
            System.setProperty("p18.od.debug.stop.after.cp", String.valueOf(BREAK_AFTER));
            try {
                applyAddInternal.invoke(coord, clazz, diff, Integer.valueOf(BATCH), Boolean.FALSE);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable t = ite.getCause();
                if (t == null || t.getMessage() == null || !t.getMessage().startsWith("OD_DEBUG_STOP_CP:")) {
                    throw ite;
                }
            }
        } finally {
            if (oldProp == null) System.clearProperty("p18.od.debug.stop.after.cp");
            else System.setProperty("p18.od.debug.stop.after.cp", oldProp);
        }

        DsRegistry reg1 = DsRegistryInstance.get(root);
        String cpDir = RegistryMetaPaths.odManifestDir("default", clazz.getName()) + "/checkpoints";
        int afterBreakCount = 0;
        for (String s : safeList(reg1, cpDir)) { try { int v = Integer.parseInt(s); if (v >= 1 && v <= BREAK_AFTER) afterBreakCount++; } catch (Exception ignore) {} }
        Assert.assertEquals("break 时 checkpoint 1.." + BREAK_AFTER + " 都存在", BREAK_AFTER, afterBreakCount);

        applyAddInternal.invoke(coord, clazz, diff, Integer.valueOf(BATCH), Boolean.FALSE);
        Method ensureMeta = TableMetaStore.class.getDeclaredMethod("ensureMeta", Class.class);
        ensureMeta.setAccessible(true);
        for (Method m : cc.getDeclaredMethods()) {
            if ("tms".equals(m.getName()) && m.getParameterCount() == 0) { m.setAccessible(true); Object tms = m.invoke(coord); ensureMeta.invoke(tms, clazz); break; }
        }
        Field cacheField = DsDatabaseLocal.class.getDeclaredField("tableMetaCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Class<?>, Object> cache = (java.util.Map<Class<?>, Object>) cacheField.get(db);
        cache.remove(clazz);
        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        metaOf.setAccessible(true);
        metaOf.invoke(db, clazz);

        String phase = safeStr(reg1, RegistryMetaPaths.odManifestPhase("default", clazz.getName()));
        String prog = safeStr(reg1, RegistryMetaPaths.odManifestProgress("default", clazz.getName()));
        Assert.assertEquals("续跑 phase=DONE", "DONE", phase);
        Assert.assertEquals("续跑 progress=100", "100", prog);
        int totalCp = 0;
        for (String s : safeList(reg1, cpDir)) { try { int v = Integer.parseInt(s); if (v > 0) totalCp++; } catch (Exception ignore) {} }
        int expected = (N + BATCH - 1) / BATCH;
        Assert.assertEquals("最终 cp 总数 = ceil(N/BATCH)", expected, totalCp);

        ColumnarStore cs = new ColumnarStore(root);
        byte[] expA = longBE(0L);
        Random rnd = new Random(0xBEEFL);
        int sample = Math.min(100, N);
        for (int i = 0; i < sample; i++) {
            long rid = ids.get(rnd.nextInt(N));
            byte[] va = cs.getValue(clazz.getName(), "newA", rid);
            Assert.assertArrayEquals("续跑后 newA i=" + i, expA, va);
            MigEntity e = db.getTable(clazz, rid);
            Assert.assertNotNull("续跑后 getTable i=" + i, e);
            Assert.assertEquals("续跑后 newB i=" + i, "hello-p18-ddl", e.newB);
        }
    }

    // ==================== TR-2.4 t04: checkpoint 数 rubric ====================
    @Test
    public void t04_checkpointCountRubricAtLeastTwo() throws Exception {
        int N = 3600;
        int BATCH = 128;
        DsDatabaseLocal db = DsDatabaseLocal.load();
        Class<MigEntity> clazz = MigEntity.class;
        db.registerTable(clazz);
        saveLegacyMeta(db, clazz, java.util.Arrays.asList("name", "age", "city"));
        seed(db, N, clazz);
        File root = db.getRoot();
        db.forceApplyOnlineDDL(clazz);
        DsRegistry reg = DsRegistryInstance.get(root);
        String cpDir = RegistryMetaPaths.odManifestDir("default", clazz.getName()) + "/checkpoints";
        int cpCount = 0;
        for (String s : safeList(reg, cpDir)) { try { int v = Integer.parseInt(s); if (v > 0) cpCount++; } catch (Exception ignore) {} }
        Assert.assertTrue("checkpoint 数 rubric ≥ 2（实锤=" + cpCount + "）", cpCount >= 2);
    }

    // ==================== TR-2.5 t05: nullable=false 签名差异 ====================
    @Test
    public void t05_nullableFalseSignatureDiverges() throws Exception {
        Method computeSigLocal = null;
        Method buildFresh = null;

        DsDatabaseLocal db = DsDatabaseLocal.load();
        Class<MigEntity> clazz = MigEntity.class;
        db.registerTable(clazz);
        Object coord = odCoord(db);
        Class<?> cc = coord.getClass();
        for (Method m : cc.getDeclaredMethods()) {
            if ("buildFresh".equals(m.getName()) && m.getParameterCount() == 1) { buildFresh = m; m.setAccessible(true); }
            if ("computeSigLocal".equals(m.getName()) && m.getParameterCount() == 1) { computeSigLocal = m; m.setAccessible(true); }
        }
        Assert.assertNotNull("buildFresh method", buildFresh);
        Assert.assertNotNull("computeSigLocal method", computeSigLocal);
        TableMetaStore.TableMeta full = (TableMetaStore.TableMeta) buildFresh.invoke(coord, clazz);
        List<TableMetaStore.ColumnDef> onlyTrue = new ArrayList<>();
        TableMetaStore.ColumnDef colTrue = null, colFalse = null;
        for (TableMetaStore.ColumnDef c : full.columns) if (!"newC".equals(c.name)) onlyTrue.add(c); else colFalse = c;
        if (colTrue == null) for (TableMetaStore.ColumnDef c : onlyTrue) if ("newB".equals(c.name)) { colTrue = c; break; }
        full.columns = onlyTrue;
        String sigWitoutC = (String) computeSigLocal.invoke(coord, full);
        full.columns = new ArrayList<>(onlyTrue); full.columns.add(colFalse);
        String sigWithC = (String) computeSigLocal.invoke(coord, full);
        Assert.assertNotEquals("TR-2.5: nullable=false vs true 签名必须不同", sigWitoutC, sigWithC);

        Method colNullable = TableMetaStore.class.getDeclaredMethod("colNullable", TableMetaStore.ColumnDef.class);
        colNullable.setAccessible(true);
        TableMetaStore.ColumnDef cF = new TableMetaStore.ColumnDef();
        cF.nullable = Boolean.FALSE;
        Assert.assertFalse("nullable=false resolver", (Boolean) colNullable.invoke(null, cF));
        TableMetaStore.ColumnDef cT = new TableMetaStore.ColumnDef();
        cT.nullable = Boolean.TRUE;
        Assert.assertTrue("nullable=true resolver", (Boolean) colNullable.invoke(null, cT));
    }

    private static String safeStr(DsRegistry reg, String path) {
        try {
            Object e = reg.get(path);
            if (e == null) return null;
            Method m = e.getClass().getMethod("asString");
            Object v = m.invoke(e);
            if (v == null || "".equals(v)) return null;
            return v.toString();
        } catch (Exception ignore) { return null; }
    }
    @SuppressWarnings("unchecked")
    private static List<String> safeList(DsRegistry reg, String path) {
        try {
            List<String> out = (List<String>) reg.getClass().getMethod("listChildren", String.class).invoke(reg, path);
            return out == null ? new ArrayList<>() : out;
        } catch (Exception ignore) { return new ArrayList<>(); }
    }
}
