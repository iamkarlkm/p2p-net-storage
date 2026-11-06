package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnRegistry;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.RowIdSequenceStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.TableMetaStore.ColumnDef;
import com.q3lives.ds.database.columnar.TableMetaStore.TableMeta;
import com.q3lives.ds.database.columnar.index.EqIndexMetaStore;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

public class P19_AlterKeySpaceTest {

    private static final String SPACE = "default_space";
    private static final String ENTITY = "com.example.ds.AlterUserEntity";
    private static final String MIG_ID = "2001";
    private static final long COL_ID = 11L;
    private static final int IDX = 0;
    private static final String SUB_LEAF = "payload";

    private File tmpRoot;
    private String oldHome;

    @Before
    public void before() throws IOException {
        tmpRoot = Files.createTempDirectory("dsdb_p19_alt_").toFile();
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void after() {
        try { deleteRecursive(tmpRoot); } catch (Exception ignore) {}
        if (oldHome == null) System.clearProperty("p2p.db.home");
        else System.setProperty("p2p.db.home", oldHome);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) { File[] ch = f.listFiles(); if (ch != null) for (File c : ch) deleteRecursive(c); }
        f.delete();
    }

    private static Method findMethod(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name)) return m;
        return null;
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur instanceof java.lang.reflect.InvocationTargetException) {
            Throwable c = cur.getCause();
            if (c == null || c == cur) break;
            cur = c;
        }
        return cur;
    }

    private static boolean containsAny(String m, String... keys) {
        if (m == null) return false;
        for (String k : keys) if (m.contains(k)) return true;
        return false;
    }

    private static void grepRecursive(File f, Pattern pat, List<File> out) throws IOException {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) { File[] ch = f.listFiles(); if (ch != null) for (File c : ch) grepRecursive(c, pat, out); return; }
        if (!f.getName().endsWith(".java")) return;
        String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        if (pat.matcher(content).find()) out.add(f);
    }

    private static String names(List<File> fs) {
        StringBuilder sb = new StringBuilder();
        for (File f : fs) { if (sb.length() > 0) sb.append(", "); sb.append(f.getName()); }
        return sb.toString();
    }

    // ==================== TR-1.1 t01: 路径合规 + IAE 5/5 ====================
    @Test
    public void t01_alterPathsValidPrefixAndIaeFive() throws Exception {
        Class<?> c = RegistryMetaPaths.class;
        Method mAlterDir = findMethod(c, "odManifestAlterDir");
        Method mAlterIdx = findMethod(c, "odManifestAlterIdx");
        Assert.assertNotNull("odManifestAlterDir exists", mAlterDir);
        Assert.assertNotNull("odManifestAlterIdx exists", mAlterIdx);

        mAlterDir.setAccessible(true);
        mAlterIdx.setAccessible(true);

        String pDir = (String) mAlterDir.invoke(null, SPACE, ENTITY, MIG_ID);
        String pIdx = (String) mAlterIdx.invoke(null, SPACE, ENTITY, MIG_ID, IDX, SUB_LEAF);

        Assert.assertNotNull("alterDir nonnull", pDir);
        Assert.assertNotNull("alterIdx nonnull", pIdx);
        Assert.assertTrue("alterDir 关键段 /orm/dbs/ + onlineDDL + manifest + /" + MIG_ID + "/: " + pDir,
            pDir.startsWith("/orm/dbs/")
            && pDir.contains("/onlineDDL/")
            && pDir.contains("/manifest/")
            && pDir.contains("/" + MIG_ID + "/"));
        Assert.assertTrue("alterIdx pDir prefix + /" + IDX + "/" + SUB_LEAF + " suffix: " + pIdx,
            pIdx.startsWith(pDir) && pIdx.endsWith("/" + IDX + "/" + SUB_LEAF));

        int iae = 0;
        try { mAlterDir.invoke(null, null, ENTITY, MIG_ID); }
        catch (Throwable t) { Throwable u = unwrap(t); if (u instanceof IllegalArgumentException && containsAny(u.getMessage(), "dbSpaceName","blank")) iae++; }
        try { mAlterDir.invoke(null, SPACE, null, MIG_ID); }
        catch (Throwable t) { Throwable u = unwrap(t); if (u instanceof IllegalArgumentException && containsAny(u.getMessage(), "entityClass","blank")) iae++; }
        try { mAlterDir.invoke(null, SPACE, ENTITY, null); }
        catch (Throwable t) { Throwable u = unwrap(t); if (u instanceof IllegalArgumentException && containsAny(u.getMessage(), "migId","blank")) iae++; }
        try { mAlterDir.invoke(null, SPACE, ENTITY, "   "); }
        catch (Throwable t) { Throwable u = unwrap(t); if (u instanceof IllegalArgumentException && containsAny(u.getMessage(), "migId","blank")) iae++; }
        try { mAlterIdx.invoke(null, SPACE, ENTITY, MIG_ID, -1, SUB_LEAF); }
        catch (Throwable t) { Throwable u = unwrap(t); if (u instanceof IllegalArgumentException && u.getMessage().contains("idx < 0")) iae++; }
        try { mAlterIdx.invoke(null, SPACE, ENTITY, MIG_ID, IDX, ""); }
        catch (Throwable t) { Throwable u = unwrap(t); if (u instanceof IllegalArgumentException && containsAny(u.getMessage(), "subLeaf","blank")) iae++; }

        Assert.assertTrue("IAE >=5 (实锤=" + iae + ")", iae >= 5);
    }

    // ==================== TR-1.2 t02: BackwardCompat + updateColumnDef ====================
    @Test
    public void t02_backwardCompatDefaultAndUpdateColumnDef() throws Exception {
        DsDatabaseLocal db = DsDatabaseLocal.load();
        File root = db.getRoot();
        TableMetaStore tms = new TableMetaStore(root);
        Field bf = TableMetaStore.class.getDeclaredField("backend");
        bf.setAccessible(true);
        Object backend = bf.get(tms);
        Class<?> bc = null;
        for (Class<?> ic : TableMetaStore.class.getDeclaredClasses())
            if ("Backend".equals(ic.getSimpleName())) { bc = ic; break; }
        Assert.assertNotNull("Backend class", bc);

        Method saveToR = bc.getDeclaredMethod("saveToRegistry", String.class, TableMeta.class);
        Method loadFromR = bc.getDeclaredMethod("loadFromRegistry", String.class);
        Method updateCd = bc.getDeclaredMethod("updateColumnDef", String.class, long.class, ColumnDef.class);
        Method computeSig = TableMetaStore.class.getDeclaredMethod("computeSignature", TableMeta.class);
        saveToR.setAccessible(true); loadFromR.setAccessible(true); updateCd.setAccessible(true); computeSig.setAccessible(true);

        String ent = ENTITY;
        TableMeta m = new TableMeta();
        m.entityClassName = ent;
        m.columns = new ArrayList<>();
        m.compositeGroups = new java.util.LinkedHashMap<>();
        ColumnDef c0 = new ColumnDef();
        c0.colId = COL_ID;
        c0.colKey = ent + "#uname";
        c0.name = "uname";
        c0.javaType = "java.lang.String";
        c0.length = 64;
        c0.nullable = Boolean.TRUE;
        m.columns.add(c0);
        m.signature = (String) computeSig.invoke(null, m);
        saveToR.invoke(backend, ent, m);

        TableMeta read1 = (TableMeta) loadFromR.invoke(backend, ent);
        Assert.assertNotNull("first load nonnull", read1);
        Assert.assertEquals("first load 1 col", 1, read1.columns.size());
        ColumnDef rc1 = read1.columns.get(0);
        Assert.assertEquals("orig javaType", "java.lang.String", rc1.javaType);
        Assert.assertEquals("orig length", 64, rc1.length);
        Assert.assertTrue("orig nullable=true", Boolean.TRUE.equals(rc1.nullable) || rc1.nullable == null);
        String sig1 = read1.signature;

        ColumnDef upd = new ColumnDef();
        upd.colId = COL_ID;
        upd.colKey = c0.colKey;
        upd.name = c0.name;
        upd.javaType = "java.lang.Integer";
        upd.length = 0;
        upd.precision = 0;
        upd.scale = 0;
        upd.nullable = Boolean.FALSE;
        updateCd.invoke(backend, ent, Long.valueOf(COL_ID), upd);

        TableMeta read2 = (TableMeta) loadFromR.invoke(backend, ent);
        Assert.assertNotNull("after update load nonnull", read2);
        String sig2 = read2.signature;
        Assert.assertNotEquals("update后签名必须变（javaType+length+nullable 三改）", sig1, sig2);
        Assert.assertEquals("read2 columns=1", 1, read2.columns.size());
        ColumnDef rc2 = read2.columns.get(0);
        Assert.assertEquals("updated javaType=Integer", "java.lang.Integer", rc2.javaType);
        Assert.assertEquals("updated length=0", 0, rc2.length);
        Assert.assertEquals("updated nullable=false", Boolean.FALSE, rc2.nullable);

        int iae = 0;
        try { updateCd.invoke(backend, null, Long.valueOf(COL_ID), upd); }
        catch (Throwable t) { Throwable u = unwrap(t); if (u instanceof IllegalArgumentException) iae++; }
        try { updateCd.invoke(backend, ent, Long.valueOf(0L), upd); }
        catch (Throwable t) { Throwable u = unwrap(t); if (u instanceof IllegalArgumentException) iae++; }
        try { updateCd.invoke(backend, ent, Long.valueOf(COL_ID), null); }
        catch (Throwable t) { Throwable u = unwrap(t); if (u instanceof IllegalArgumentException) iae++; }
        Assert.assertTrue("updateColumnDef IAE>=3（实锤=" + iae + "）", iae >= 3);
    }

    // ==================== TR-1.3 t03: Facade 6 门面计数 ±4 合规 ====================
    @Test
    public void t03_sixFacadePublicMethodCountWithinBand() {
        Class<?>[] facades = new Class<?>[] {
            DsDatabaseLocal.class, ColumnarStore.class, TableMetaStore.class,
            ColumnRegistry.class, RowIdSequenceStore.class, EqIndexMetaStore.class
        };
        int[] baselines = new int[] { 48, 24, 3, 11, 1, 7 };
        int tol = 4;
        List<String> fails = new ArrayList<>();
        for (int i = 0; i < facades.length; i++) {
            int cnt = 0;
            for (Method m : facades[i].getDeclaredMethods())
                if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) cnt++;
            int base = baselines[i];
            if (cnt < base - tol || cnt > base + tol)
                fails.add(facades[i].getSimpleName() + "=" + cnt + " (基线" + base + "±" + tol + ")");
        }
        Assert.assertTrue("Facade 计数合规（失败：" + fails + "）", fails.isEmpty());
    }

    // ==================== TR-1.4 t04: NG-4 zeroLeak 8 keywords files=1 ====================
    @Test
    public void t04_zeroLeakEightKeywordsOnlyRegistryMetaPaths() throws Exception {
        File userDir = new File(System.getProperty("user.dir"));
        File src = new File(userDir, "p2p-db/src/main/java");
        if (!src.exists()) src = new File("I:/2026/code/p2p-net-storage/p2p-db/src/main/java");
        Assert.assertTrue("src main java exists: " + src, src.isDirectory());
        Pattern p = Pattern.compile(
            "\\bmanifest\\b|\\bschemaVersion\\b|\\btombstones\\b|\\bmigrateId\\b|\\bonlineDdl\\b"
            + "|\\balterCols\\b|\\bcastMode\\b|\\boldJavaType\\b");
        List<File> matched = new ArrayList<>();
        grepRecursive(src, p, matched);
        Set<String> legacy = new HashSet<>(Arrays.asList(
            "DsFullBackup.java","DsIncrementalBackup.java","DsTimelineRestore.java","DsDatabaseMetaMigrator.java"));
        List<File> nonLegacy = new ArrayList<>();
        for (File f : matched) if (!legacy.contains(f.getName())) nonLegacy.add(f);
        long regCount = nonLegacy.stream().filter(f -> "RegistryMetaPaths.java".equals(f.getName())).count();
        Assert.assertTrue("至少 1 处 RegistryMetaPaths 命中（nonLegacy=" + nonLegacy.size() + " names=" + names(nonLegacy) + "）", regCount >= 1L);
        Assert.assertEquals("零外泄：排除 legacy 4 文件后仅 RegistryMetaPaths 命中（实锤 nonLegacy="
            + nonLegacy.size() + " files=" + names(nonLegacy) + "）", regCount, (long) nonLegacy.size());
    }
}
