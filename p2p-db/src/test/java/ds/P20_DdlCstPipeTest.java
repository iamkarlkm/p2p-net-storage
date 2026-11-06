package ds;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

public class P20_DdlCstPipeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String savedHome;

    @Before
    public void setup() throws Exception {
        savedHome = System.getProperty("p2p.db.home");
        File root = tmp.newFolder("p20-t3-" + UUID.randomUUID().toString().replace("-","").substring(0,8));
        System.setProperty("p2p.db.home", root.getAbsolutePath());
    }

    @After
    public void tearDown() throws Exception {
        if (savedHome != null) System.setProperty("p2p.db.home", savedHome);
        else System.clearProperty("p2p.db.home");
        deleteRecursive(new File(System.getProperty("p2p.db.home", tmp.getRoot().getAbsolutePath())));
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) { File[] ch = f.listFiles(); if (ch != null) for (File c : ch) deleteRecursive(c); }
        try { f.delete(); } catch (Exception ignored) {}
    }

    private static Class<?> findSubClassByName(Class<?> outer, String simpleName) {
        for (Class<?> ic : outer.getDeclaredClasses()) {
            if (simpleName.equals(ic.getSimpleName())) return ic;
            Class<?> rec = findSubClassByName(ic, simpleName);
            if (rec != null) return rec;
        }
        return null;
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur instanceof java.lang.reflect.InvocationTargetException
                && cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static Throwable unwrapEx(Exception ex) { return unwrap(ex); }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test
    public void tr3_1_listCstsAndManifestDirPaths() throws Exception {
        Class<?> coordCls = findSubClassByName(DsDatabaseLocal.class, "OnlineDdlCoordinator");
        Class<?> cstSpecCls = findSubClassByName(coordCls, "CstSpec");
        Class<?> backendCls = TableMetaStore.Backend.class;
        Method listCsts = backendCls.getDeclaredMethod("listCsts", String.class);
        listCsts.setAccessible(true);
        Method parse = coordCls.getDeclaredMethod("parseCstCmd", String.class);
        parse.setAccessible(true);
        Method exec = coordCls.getDeclaredMethod("execCstCmd",
                findSubClassByName(coordCls, "CstCmdRec"), backendCls, TableMetaStore.TableMeta.class);
        exec.setAccessible(true);
        Method cstNameG = cstSpecCls.getDeclaredMethod("cstName");
        cstNameG.setAccessible(true);
        Method typeG = cstSpecCls.getDeclaredMethod("type");
        typeG.setAccessible(true);

        File root = new File(System.getProperty("p2p.db.home"));
        Object backend = backendCls.getDeclaredConstructor(File.class).newInstance(root);
        String ENTITY = "com.ex.T3A";

        List<Object> empty = (List<Object>) listCsts.invoke(backend, ENTITY);
        Assert.assertEquals("listCsts 空 0", 0, empty.size());

        TableMetaStore.TableMeta tm = new TableMetaStore.TableMeta();
        tm.entityClassName = ENTITY;
        tm.columns = new ArrayList<>();
        TableMetaStore.ColumnDef c1 = new TableMetaStore.ColumnDef();
        c1.colId = 1L; c1.colKey = "email"; c1.name = "email"; c1.javaType = "java.lang.String"; c1.length = 255;
        tm.columns.add(c1);

        Object cmdAdd = parse.invoke(null,
                "ADD_CST|cst=uk_email|type=UNIQUE|col=1|uk=true|nn=false|ck=");
        exec.invoke(null, cmdAdd, backend, tm);

        List<Object> one = (List<Object>) listCsts.invoke(backend, ENTITY);
        Assert.assertEquals("listCsts 1 条", 1, one.size());
        Assert.assertEquals("cstName==uk_email", "uk_email", cstNameG.invoke(one.get(0)));
        Assert.assertEquals("type==UNIQUE", "UNIQUE", typeG.invoke(one.get(0)));

        String manDir = RegistryMetaPaths.cstManifestNameListDir("default", ENTITY);
        Assert.assertTrue("manifests 段名应包含 constraints: " + manDir, manDir.contains("/constraints"));
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test
    public void tr3_2_executeUkConflictThenRollbackCleanAll() throws Exception {
        Class<?> coordCls = findSubClassByName(DsDatabaseLocal.class, "OnlineDdlCoordinator");
        Class<?> pipeCls = findSubClassByName(coordCls, "DdlCstPipe");
        Class<?> cstSpecCls = findSubClassByName(coordCls, "CstSpec");
        Class<?> backendCls = TableMetaStore.Backend.class;
        Class<?> eqCls = Class.forName("com.q3lives.ds.database.index.DsEqIndexStore");
        Constructor<?> ctor8 = cstSpecCls.getDeclaredConstructor(String.class, String.class, long.class,
                String.class, boolean.class, boolean.class, boolean.class, boolean.class);
        ctor8.setAccessible(true);
        Method prepare = pipeCls.getDeclaredMethod("prepare", String.class, Object[].class, Object.class);
        prepare.setAccessible(true);
        Method execute = pipeCls.getDeclaredMethod("execute", String.class, Object[].class, List.class, Object.class);
        execute.setAccessible(true);
        Method rollback = pipeCls.getDeclaredMethod("rollback", String.class, List.class, Object.class);
        rollback.setAccessible(true);
        Method readStr = backendCls.getDeclaredMethod("readStr", String.class);
        readStr.setAccessible(true);
        Method listChildren = null;
        for (Method m : backendCls.getDeclaredMethods()) {
            if (m.getName().equals("listChildren") && m.getParameterCount() == 2) { listChildren = m; break; }
        }
        if (listChildren != null) listChildren.setAccessible(true);
        Method putU = null;
        Constructor<?> eqCtor3 = eqCls.getDeclaredConstructor(File.class, String.class, String.class);
        eqCtor3.setAccessible(true);
        for (Method m : eqCls.getDeclaredMethods()) {
            if (m.getName().equals("putIndexUnique") && m.getParameterCount() == 2) { putU = m; break; }
        }
        if (putU != null) putU.setAccessible(true);

        File root = new File(System.getProperty("p2p.db.home"));
        Object backend = backendCls.getDeclaredConstructor(File.class).newInstance(root);
        String ENTITY = "com.ex.T3B";
        String TABLE = "T3B";

        Object cst1 = ctor8.newInstance("uk_email", "UNIQUE", 14L, null, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.TRUE);
        Object cst2 = ctor8.newInstance("ck_len", "CHECK", 2L, "len>0", Boolean.FALSE, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE);
        Object cst3 = ctor8.newInstance("uk_phone", "UNIQUE", -12L, null, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.TRUE);
        Object[] cstArr = new Object[] { cst1, cst2, cst3 };

        List<String> migList = (List<String>) prepare.invoke(null, ENTITY, cstArr, backend);
        Assert.assertEquals("prepare 3 条 manifest", 3, migList.size());
        String m0 = (String) readStr.invoke(backend, migList.get(0));
        Assert.assertTrue("manifest 0 含 |cst=", m0 != null && m0.contains("|cst="));

        // execute: cst1 写 uk_email(col=14,row=1,kv=1*13+14=27) OK; cst2 ck OK; cst3 uk_phone(col=-12,row=3,kv=3*13+(-12)=27) 同 kv 异 row → 冲突
        Exception caught = null;
        try {
            execute.invoke(null, ENTITY, cstArr, migList, backend);
        } catch (Exception ex) {
            caught = ex;
        }
        Assert.assertNotNull("UK 冲突应抛 RuntimeException", caught);
        Throwable cause = unwrapEx(caught);
        Assert.assertTrue("UK 冲突 msg: actual=" + cause.getMessage(),
                (cause instanceof RuntimeException) && cause.getMessage() != null
                        && cause.getMessage().contains("UK 冲突"));

        // execute 失败前已写入 cst1/cst2 的主 leaf（cst3 可能部分写也可能没写，rollback 统一清理）
        String tAfter = (String) readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, "uk_email", RegistryMetaPaths.OD_CST_TYPE));
        Assert.assertEquals("execute cst1 type=UNIQUE", "UNIQUE", tAfter);
        String ckAfter = (String) readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, "ck_len", RegistryMetaPaths.OD_CST_CK));
        Assert.assertEquals("execute cst2 ck=len>0", "len>0", ckAfter);

        rollback.invoke(null, ENTITY, migList, backend);
        String tRb = (String) readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, "uk_email", RegistryMetaPaths.OD_CST_TYPE));
        Assert.assertNull("rollback 后 uk_email type null", tRb);
        String ckRb = (String) readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, "ck_len", RegistryMetaPaths.OD_CST_CK));
        Assert.assertNull("rollback 后 ck_len ck null", ckRb);
        String t3Rb = (String) readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, "uk_phone", RegistryMetaPaths.OD_CST_TYPE));
        Assert.assertNull("rollback 后 uk_phone type null", t3Rb);
        boolean allClean = true;
        for (String m : migList) {
            String v = (String) readStr.invoke(backend, m);
            if (v != null && !v.isEmpty()) { allClean = false; break; }
        }
        Class<?> yamlSupCls = Class.forName("com.q3lives.ds.registry.meta.AbstractRegistryBackend$YamlSupplier");
        String manDir = RegistryMetaPaths.cstManifestNameListDir("default", ENTITY);
        List<?> manSubs = null;
        if (listChildren != null) {
            Object ys = java.lang.reflect.Proxy.newProxyInstance(yamlSupCls.getClassLoader(),
                    new Class<?>[] { yamlSupCls },
                    (p, mth, a) -> new ArrayList<>());
            manSubs = (List<?>) listChildren.invoke(backend, manDir, ys);
        }
        if (manSubs != null && !manSubs.isEmpty()) allClean = false;
        Assert.assertTrue("rollback 后 manifests 全清: subs=" + (manSubs == null ? "null" : manSubs.size()), allClean);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test
    public void tr3_3_commitKeepCreatedAtAndClearManifests() throws Exception {
        Class<?> coordCls = findSubClassByName(DsDatabaseLocal.class, "OnlineDdlCoordinator");
        Class<?> pipeCls = findSubClassByName(coordCls, "DdlCstPipe");
        Class<?> cstSpecCls = findSubClassByName(coordCls, "CstSpec");
        Class<?> backendCls = TableMetaStore.Backend.class;
        Constructor<?> ctor8 = cstSpecCls.getDeclaredConstructor(String.class, String.class, long.class,
                String.class, boolean.class, boolean.class, boolean.class, boolean.class);
        ctor8.setAccessible(true);
        Method prepare = pipeCls.getDeclaredMethod("prepare", String.class, Object[].class, Object.class);
        prepare.setAccessible(true);
        Method execute = pipeCls.getDeclaredMethod("execute", String.class, Object[].class, List.class, Object.class);
        execute.setAccessible(true);
        Method commit = pipeCls.getDeclaredMethod("commit", String.class, List.class, boolean.class, Object.class);
        commit.setAccessible(true);
        Method readStr = backendCls.getDeclaredMethod("readStr", String.class);
        readStr.setAccessible(true);
        Method listChildren = null;
        for (Method m : backendCls.getDeclaredMethods()) {
            if (m.getName().equals("listChildren") && m.getParameterCount() == 2) { listChildren = m; break; }
        }
        if (listChildren != null) listChildren.setAccessible(true);

        File root = new File(System.getProperty("p2p.db.home"));
        Object backend = backendCls.getDeclaredConstructor(File.class).newInstance(root);
        String ENTITY = "com.ex.T3C";

        Object cst1 = ctor8.newInstance("uk_email", "UNIQUE", 1L, null, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.TRUE);
        Object[] arr = new Object[] { cst1 };
        List<String> mig = (List<String>) prepare.invoke(null, ENTITY, arr, backend);
        Assert.assertEquals("1 条 manifest", 1, mig.size());

        execute.invoke(null, ENTITY, arr, mig, backend);
        String caBefore = (String) readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, "uk_email", RegistryMetaPaths.OD_CST_CREATED_AT));
        Assert.assertNotNull("execute 后 createdAt 已写非空", caBefore);
        Assert.assertTrue("execute 后 createdAt 毫秒 >0", Long.parseLong(caBefore) > 0L);

        commit.invoke(null, ENTITY, mig, Boolean.TRUE, backend);
        String ca1 = (String) readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, "uk_email", RegistryMetaPaths.OD_CST_CREATED_AT));
        Assert.assertEquals("keepCa 幂等 commit 不改变 createdAt", caBefore, ca1);

        Thread.sleep(3L);
        List<String> mig2 = (List<String>) prepare.invoke(null, ENTITY, arr, backend);
        commit.invoke(null, ENTITY, mig2, Boolean.TRUE, backend);
        String ca2 = (String) readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, "uk_email", RegistryMetaPaths.OD_CST_CREATED_AT));
        Assert.assertEquals("2 次幂等 commit createdAt 不变", caBefore, ca2);

        String manDir = RegistryMetaPaths.cstManifestNameListDir("default", ENTITY);
        List<?> manSubs = listChildren == null ? new ArrayList<>() : (List<?>) listChildren.invoke(backend, manDir,
                java.util.function.Supplier.class.cast((java.util.function.Supplier<List<?>>) () -> new ArrayList<>()));
        Assert.assertTrue("manifests 清完: size=" + manSubs.size(), manSubs == null || manSubs.isEmpty());
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test
    public void tr3_4_zeroLeakAndPrevious19TR() throws Exception {
        File projectRoot = new File(System.getProperty("user.dir"));
        if (!new File(projectRoot, "p2p-db").exists()) projectRoot = new File("I:/2026/code/p2p-net-storage");
        File mainJava = new File(projectRoot, "p2p-db/src/main/java");
        File testJava = new File(projectRoot, "p2p-db/src/test/java");
        Pattern ng4 = Pattern.compile(
                "\\bconstraint\\b|\\buniqueKey\\b|\\bcheckExpr\\b|\\bnotNull\\b|\\bvalidateConstraint\\b",
                Pattern.CASE_INSENSITIVE);
        Pattern p19 = Pattern.compile(
                "\\bmanifest\\b|\\bschemaVersion\\b|\\btombstones\\b|\\bmigrateId\\b|\\bonlineDdl\\b|\\balterCols\\b|\\bcastMode\\b|\\boldJavaType\\b");

        Set<String> allowNg4 = new HashSet<>(Arrays.asList(
                "RegistryMetaPaths.java", "TableMetaStore.java", "DsEqIndexStore.java", "DsDatabaseLocal.java",
                "DsRegistryExtAttrs_P7_Tgt5_PerfDeep_16x16_MatrixStressTest.java",
                "P20_ConstraintKeySpaceTest.java", "P20_DdlSemanticsTest.java", "P20_DdlCstPipeTest.java"));
        Set<String> allowP19 = new HashSet<>(Arrays.asList(
                "RegistryMetaPaths.java", "DsFullBackup.java", "DsIncrementalBackup.java",
                "DsTimelineRestore.java", "DsDatabaseMetaMigrator.java"));

        List<File> hitsNg = new ArrayList<>();
        grepRecursive(mainJava, ng4, hitsNg);
        grepRecursive(testJava, ng4, hitsNg);
        List<String> bad = new ArrayList<>();
        for (File f : hitsNg) if (!allowNg4.contains(f.getName())) bad.add(f.getAbsolutePath());
        Assert.assertTrue("NG-4 非白名单 hit: " + bad, bad.isEmpty());

        List<File> hitsP19 = new ArrayList<>();
        grepRecursive(mainJava, p19, hitsP19);
        List<String> badP19 = new ArrayList<>();
        for (File f : hitsP19) if (!allowP19.contains(f.getName())) badP19.add(f.getAbsolutePath());
        Assert.assertTrue("P19 非白名单 hit: " + badP19, badP19.isEmpty());
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static void grepRecursive(File root, Pattern p, List<File> out) throws Exception {
        if (root == null || !root.exists()) return;
        if (root.isDirectory()) {
            File[] ch = root.listFiles();
            if (ch != null) for (File c : ch) grepRecursive(c, p, out);
            return;
        }
        if (!root.getName().endsWith(".java")) return;
        String content = new String(java.nio.file.Files.readAllBytes(root.toPath()), "UTF-8");
        if (p.matcher(content).find()) out.add(root);
    }
}
