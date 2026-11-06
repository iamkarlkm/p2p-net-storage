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
import com.q3lives.ds.database.index.DsEqIndexStore;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

public class P20_ConstraintKeySpaceTest {

    private static final String SPACE = "default_space";
    private static final String ENTITY = "com.example.ds.ConstraintUserEntity";
    private static final long COL_ID = 11L;
    private static final String CST1 = "uk_email";
    private static final String CST2 = "ck_age";
    private static final String BAD_CST = "uk bad!";

    private File tmpRoot;
    private String oldHome;

    @Before
    public void before() throws IOException {
        tmpRoot = Files.createTempDirectory("dsdb_p20_cst_").toFile();
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
        if (f.isDirectory()) {
            String name = f.getName();
            if ("target".equals(name) || "node_modules".equals(name) || ".git".equals(name)) return;
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) grepRecursive(c, pat, out);
            return;
        }
        if (!f.getName().endsWith(".java")) return;
        String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        if (pat.matcher(content).find()) out.add(f);
    }

    @Test
    public void tr1_1_registryMetaPathsIAEAndAbbreviations() throws Exception {
        String[] constNames = new String[] {
                "OD_MANIFEST_CST", "OD_CST_NAME", "OD_CST_TYPE", "OD_CST_COL",
                "OD_CST_CK", "OD_CST_UK", "OD_CST_NN", "OD_CST_ST", "OD_CST_ENABLED",
                "OD_CST_CREATED_AT", "OD_CST_VC"
        };
        Pattern NG4_FULL = Pattern.compile(
                "\\b(constraint|uniqueKey|checkExpr|notNull|validateConstraint)\\b",
                Pattern.CASE_INSENSITIVE);
        Set<String> leafLiterals = new HashSet<>(Arrays.asList(
                "checkExpr", "uniqueKey", "notNull", "validateConstraint"
        ));
        List<String> constValues = new ArrayList<>();
        for (String cn : constNames) {
            Field f = RegistryMetaPaths.class.getDeclaredField(cn);
            f.setAccessible(true);
            Object v = f.get(null);
            Assert.assertNotNull("常量值不能为空: " + cn, v);
            Assert.assertTrue("常量应为 String: " + cn, v instanceof String);
            String s = (String) v;
            if (!leafLiterals.contains(s)) {
                Assert.assertFalse("常量值不应匹配完整词 NG-4 5 词: " + cn + "=" + s,
                        NG4_FULL.matcher(s).find());
            }
            Assert.assertFalse("常量名应缩写避零泄漏: " + cn, NG4_FULL.matcher(cn).find());
            constValues.add(s);
        }

        Class<?> cls = RegistryMetaPaths.class;
        Method mColumnDir = cls.getMethod("columnTableConstraintsDir", String.class);
        Method mListDir = cls.getMethod("cstListDir", String.class, String.class);
        Method mCstDir = cls.getMethod("cstDir", String.class, String.class, String.class);
        Method mLeaf = cls.getMethod("odCstLeaf", String.class, String.class, String.class);
        Method mManiLeaf = cls.getMethod("odCstManifestLeaf", String.class, String.class, String.class);
        Method mManiList = cls.getMethod("cstManifestNameListDir", String.class, String.class);

        Method[] iaeMethods = new Method[] { mColumnDir, mListDir, mCstDir, mLeaf, mManiLeaf, mManiList };
        for (Method m : iaeMethods) {
            Class<?>[] pts = m.getParameterTypes();
            Object[] argsNull = new Object[pts.length];
            try {
                m.invoke(null, argsNull);
                Assert.fail("IAE 未抛出 on null: " + m.getName());
            } catch (Exception e) {
                Throwable c = unwrap(e);
                Assert.assertTrue("应为 IllegalArgumentException: " + m.getName() + " got " + c,
                        c instanceof IllegalArgumentException);
            }
            if (pts.length >= 1 && pts[0] == String.class) {
                Object[] argsBlank = argsNull.clone();
                argsBlank[0] = "  ";
                try {
                    m.invoke(null, argsBlank);
                    Assert.fail("IAE 未抛出 on blank[0]: " + m.getName());
                } catch (Exception e) {
                    Throwable c = unwrap(e);
                    Assert.assertTrue("应为 IllegalArgumentException: " + m.getName() + " got " + c,
                            c instanceof IllegalArgumentException);
                }
            }
            int cstIdx = -1;
            for (int i = 0; i < pts.length; i++) if (pts[i] == String.class && cstIdx == -1) {
                if ("cstDir".equals(m.getName()) && i == 2) cstIdx = i;
                if ("odCstLeaf".equals(m.getName()) && i == 1) cstIdx = i;
                if ("odCstManifestLeaf".equals(m.getName()) && i == 1) cstIdx = i;
            }
            if (cstIdx != -1) {
                Object[] argsBad = new Object[pts.length];
                for (int i = 0; i < pts.length; i++) if (pts[i] == String.class) argsBad[i] = "ok";
                argsBad[cstIdx] = BAD_CST;
                try {
                    m.invoke(null, argsBad);
                    Assert.fail("IAE 未抛出 on 非字母数字 cstName: " + m.getName());
                } catch (Exception e) {
                    Throwable c = unwrap(e);
                    Assert.assertTrue("应为 IllegalArgumentException: " + m.getName() + " got " + c,
                            c instanceof IllegalArgumentException);
                }
            }
        }

        String p1 = (String) mLeaf.invoke(null, ENTITY, CST1, "xx");
        Assert.assertTrue("leaf 应含 " + CST1 + " 段并以 /xx 结尾: " + p1,
                p1.contains("/" + CST1 + "/") && p1.endsWith("/xx"));
        String p2 = (String) mCstDir.invoke(null, SPACE, ENTITY, CST1);
        Assert.assertTrue("cstDir 应包含 " + CST1 + " 段: " + p2, p2.endsWith("/" + CST1));
    }

    @Test
    public void tr1_2_tableMetaStoreBackendConstraintApi() throws Exception {
        File dbRoot = new File(tmpRoot, "db");
        Class<?> backendCls = Class.forName("com.q3lives.ds.database.columnar.TableMetaStore$Backend");
        java.lang.reflect.Constructor<?> backendCtor = backendCls.getDeclaredConstructor(File.class);
        backendCtor.setAccessible(true);
        Object backend = backendCtor.newInstance(dbRoot);

        try {
            Method up = null;
            for (Method m : backendCls.getDeclaredMethods()) {
                if (m.getName().equals("updateConstraint") && m.getParameterCount() == 2) up = m;
            }
            Assert.assertNotNull("updateConstraint(ent,spec) 未找到", up);
            up.setAccessible(true);
            Method en = null;
            for (Method m : backendCls.getDeclaredMethods()) if (m.getName().equals("enableConstraint")) en = m;
            Assert.assertNotNull("enableConstraint 未找到", en);
            en.setAccessible(true);

            Object spec = null;
            Class<?> coordinatorCls = DsDatabaseLocal.class;
            for (Class<?> ic : coordinatorCls.getDeclaredClasses()) if (ic.getSimpleName().equals("OnlineDdlCoordinator")) {
                for (Class<?> c2 : ic.getDeclaredClasses()) if (c2.getSimpleName().equals("CstSpec")) {
                    java.lang.reflect.Constructor<?> ctor = c2.getDeclaredConstructor(
                            String.class, String.class, long.class, String.class, boolean.class, boolean.class, boolean.class);
                    ctor.setAccessible(true);
                    spec = ctor.newInstance(CST1, "UNIQUE", COL_ID, null, true, false, true);
                }
            }
            Assert.assertNotNull("ConstraintSpec 未找到", spec);

            Method cstLeaf = RegistryMetaPaths.class.getMethod("odCstLeaf", String.class, String.class, String.class);
            String expectedType = (String) cstLeaf.invoke(null, ENTITY, CST1, RegistryMetaPaths.OD_CST_TYPE);
            String expectedEnabled = (String) cstLeaf.invoke(null, ENTITY, CST2, RegistryMetaPaths.OD_CST_ENABLED);
            Assert.assertTrue("UNIQUE 叶子路径应含 UNIQUE: " + expectedType, expectedType.endsWith("/" + RegistryMetaPaths.OD_CST_TYPE));
            Assert.assertTrue("enable 叶子路径应含 enabled: " + expectedEnabled, expectedEnabled.endsWith("/" + RegistryMetaPaths.OD_CST_ENABLED));

            up.invoke(backend, ENTITY, spec);
            en.invoke(backend, ENTITY, CST2, true);

            Method closeM = null;
            for (Method m : backendCls.getMethods()) if (m.getName().equals("close")) closeM = m;
            if (closeM != null) { closeM.setAccessible(true); closeM.invoke(backend); }
        } finally {
            try {
                Method cm = backendCls.getMethod("close");
                cm.setAccessible(true);
                cm.invoke(backend);
            } catch (Throwable ignore) {}
        }
    }

    @Test
    public void tr1_3_eqIndexUniqueAndClearPartition() throws Exception {
        File root = new File(tmpRoot, "db2");
        root.mkdirs();
        DsEqIndexStore idx = new DsEqIndexStore(root, SPACE, "idx_email");
        try (DsEqIndexStore i = idx) {
            Method putU = findMethod(DsEqIndexStore.class, "putIndexUnique");
            Assert.assertNotNull("putIndexUnique 未找到", putU);
            putU.setAccessible(true);
            Method clearP = findMethod(DsEqIndexStore.class, "clearPartition");
            Assert.assertNotNull("clearPartition 未找到", clearP);
            clearP.setAccessible(true);

            long key1 = 1001L;
            long row1 = 1L;
            long row2 = 2L;
            putU.invoke(idx, Long.valueOf(key1), row1);

            try {
                putU.invoke(idx, Long.valueOf(key1), row2);
                Assert.fail("重复 UNIQUE 键应抛 RuntimeException");
            } catch (Exception e) {
                Throwable c = unwrap(e);
                Assert.assertTrue("应为 RuntimeException 违反唯一约束: " + c,
                        c instanceof RuntimeException);
            }

            clearP.invoke(idx, 0);

            try {
                putU.invoke(idx, Long.valueOf(key1), row2);
            } catch (Exception e) {
                Assert.fail("clearPartition 后原冲突 key 应可重新写入: " + unwrap(e));
            }

            long key2 = 1002L;
            long row3 = 3L;
            putU.invoke(idx, Long.valueOf(key2), row3);
            clearP.invoke(idx, 0);
            try {
                putU.invoke(idx, Long.valueOf(key2), row1);
            } catch (Exception e) {
                Assert.fail("clearPartition 后第二个 key 也应可重写: " + unwrap(e));
            }
        }
    }

    @Test
    public void tr1_4_ng4ZeroLeakAbbreviationsOnly() throws Exception {
        Pattern NG4 = Pattern.compile(
                "\\b(constraint|uniqueKey|checkExpr|notNull|validateConstraint)\\b",
                Pattern.CASE_INSENSITIVE);
        List<File> hits = new ArrayList<>();
        File projectRoot = new File("i:/2026/code/p2p-net-storage");
        grepRecursive(new File(projectRoot, "p2p-db/src/main/java"), NG4, hits);
        grepRecursive(new File(projectRoot, "p2p-db/src/test/java"), NG4, hits);
        Set<String> allowed = new HashSet<>(Arrays.asList(
                "RegistryMetaPaths.java",
                "TableMetaStore.java",
                "DsEqIndexStore.java",
                "DsDatabaseLocal.java",
                "DsRegistryExtAttrs_P7_Tgt5_PerfDeep_16x16_MatrixStressTest.java",
                "P20_ConstraintKeySpaceTest.java",
                "P20_DdlSemanticsTest.java"
        ));
        List<String> bad = new ArrayList<>();
        for (File f : hits) {
            if (!allowed.contains(f.getName())) {
                bad.add(f.getAbsolutePath() + " (" + f.getName() + ")");
            }
        }
        Assert.assertTrue("零泄漏违规文件: " + String.join(" ; ", bad), bad.isEmpty());
    }
}
