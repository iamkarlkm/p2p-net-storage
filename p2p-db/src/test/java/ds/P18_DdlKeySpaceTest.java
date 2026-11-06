package ds;

import java.io.File;
import java.io.IOException;
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

import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.TableMetaStore.ColumnDef;
import com.q3lives.ds.database.columnar.TableMetaStore.TableMeta;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

public class P18_DdlKeySpaceTest {

    private static final String SPACE = "default_space";
    private static final String ENTITY = "com.example.ds.DdlUserEntity";
    private static final long MIG_ID = 1001L;
    private static final long COL_ID = 7L;
    private static final String SUB_LEAF = "markedAt";

    private File tmpRoot;
    private String oldHome;

    @Before
    public void before() throws IOException {
        tmpRoot = Files.createTempDirectory("dsdb_p18_ddl_").toFile();
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void after() {
        try {
            deleteRecursive(tmpRoot);
        } catch (Exception ignore) {
        }
        if (oldHome == null) {
            System.clearProperty("p2p.db.home");
        } else {
            System.setProperty("p2p.db.home", oldHome);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) {
                for (File c : ch) deleteRecursive(c);
            }
        }
        f.delete();
    }

    // ==================== helpers ====================

    private static Method findMethod(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    private static Object invokeHelper(Method m) throws Exception {
        int pc = m.getParameterCount();
        Object[] args = new Object[pc];
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pc; i++) {
            if (pts[i] == String.class) {
                args[i] = (i == 0) ? SPACE : (i == 1 ? ENTITY : (i == 3 ? SUB_LEAF : "subVal"));
            } else if (pts[i] == long.class || pts[i] == Long.class) {
                args[i] = (i == 2) ? MIG_ID : COL_ID;
            }
        }
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static Throwable unwrapInvoke(Throwable t) {
        Throwable cur = t;
        while (cur instanceof java.lang.reflect.InvocationTargetException) {
            Throwable cause = cur.getCause();
            if (cause == null || cause == cur) break;
            cur = cause;
        }
        return cur;
    }

    private static boolean containsAny(String msg, String... keys) {
        if (msg == null) return false;
        for (String k : keys) {
            if (msg.contains(k)) return true;
        }
        return false;
    }

    private static Object invoke(String methodName, String a0, String a1, long a2, String a3) throws Exception {
        Class<?> c = RegistryMetaPaths.class;
        Method m = findMethod(c, methodName);
        Assert.assertNotNull("method: " + methodName, m);
        int pc = m.getParameterCount();
        Object[] args = new Object[pc];
        Class<?>[] pts = m.getParameterTypes();
        int si = 0;
        if (si < pc && pts[si] == String.class) { args[si] = a0; si++; }
        if (si < pc && pts[si] == String.class) { args[si] = a1; si++; }
        if (si < pc && (pts[si] == long.class || pts[si] == Long.class)) { args[si] = Long.valueOf(a2); si++; }
        if (si < pc && pts[si] == String.class) { args[si] = a3; si++; }
        if (si < pc && pts[si] == String.class) { args[si] = SUB_LEAF; }
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    private static String matchedFileNames(List<File> files) {
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(f.getName());
        }
        return sb.toString();
    }

    private static void grepRecursive(File f, Pattern pat, List<File> out) throws IOException {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) {
                for (File c : ch) grepRecursive(c, pat, out);
            }
            return;
        }
        if (!f.getName().endsWith(".java")) return;
        byte[] bytes = Files.readAllBytes(f.toPath());
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (pat.matcher(content).find()) out.add(f);
    }

    // ==================== TR-1.1 t01: 12 helpers ====================

    @Test
    public void t01_twelveHelpersReflectExistAndValidPrefix() throws Exception {
        List<String> helpers = Arrays.asList(
                "odManifestDir",
                "odManifestPhase",
                "odManifestMigId",
                "odManifestFromSig",
                "odManifestToSig",
                "odManifestProgress",
                "odManifestHistoryDir",
                "odCurrentMigId",
                "odNextMigId",
                "colSchVersion",
                "odTombDir",
                "odTombCol"
        );
        Assert.assertEquals("helpers count=12", 12, helpers.size());

        Class<?> c = RegistryMetaPaths.class;
        for (String name : helpers) {
            Method m = findMethod(c, name);
            Assert.assertNotNull("helper 必须存在: " + name, m);
            Object result = invokeHelper(m);
            Assert.assertNotNull("helper 返回非空: " + name, result);
            String s = (String) result;
            boolean prefixOk = s.startsWith("/orm/dbs/") || s.startsWith("/orm/column/");
            Assert.assertTrue("helper 路径前缀 /orm/dbs/ 或 /orm/column/: " + name + "=" + s, prefixOk);
        }

        Method m = findMethod(c, "colSchVersion");
        Assert.assertNotNull("colSchVersion method", m);
        m.setAccessible(true);
        String path = (String) m.invoke(null, ENTITY);
        Assert.assertTrue("colSchVersion 落在 table/version: " + path,
                path.startsWith("/orm/column/") && path.endsWith("/version"));
    }

    // ==================== TR-1.1 t02: IAE >=10 ====================

    @Test
    public void t02_tenIllegalArgumentExceptions() throws Exception {
        int count = 0;
        try {
            invoke("odManifestDir", null, ENTITY, 0L, null);
        } catch (Throwable e) {
            Throwable t = unwrapInvoke(e);
            if (t instanceof IllegalArgumentException && containsAny(t.getMessage(), "dbSpaceName", "blank")) count++;
        }
        try {
            invoke("odManifestDir", "   ", ENTITY, 0L, null);
        } catch (Throwable e) {
            Throwable t = unwrapInvoke(e);
            if (t instanceof IllegalArgumentException && containsAny(t.getMessage(), "dbSpaceName", "blank")) count++;
        }
        try {
            invoke("odManifestDir", SPACE, null, 0L, null);
        } catch (Throwable e) {
            Throwable t = unwrapInvoke(e);
            if (t instanceof IllegalArgumentException && containsAny(t.getMessage(), "entityClass", "blank")) count++;
        }
        try {
            invoke("odManifestDir", SPACE, "   ", 0L, null);
        } catch (Throwable e) {
            Throwable t = unwrapInvoke(e);
            if (t instanceof IllegalArgumentException && containsAny(t.getMessage(), "entityClass", "blank")) count++;
        }
        StringBuilder sb = new StringBuilder(300);
        for (int i = 0; i < 300; i++) sb.append('x');
        try {
            invoke("odManifestDir", SPACE, sb.toString(), 0L, null);
        } catch (Throwable e) {
            Throwable t = unwrapInvoke(e);
            if (t instanceof IllegalArgumentException && (t.getMessage().contains("too long") || t.getMessage().contains("256"))) count++;
        }
        try {
            invoke("odManifestHistoryDir", SPACE, ENTITY, -5L, null);
        } catch (Throwable e) {
            Throwable t = unwrapInvoke(e);
            if (t instanceof IllegalArgumentException && t.getMessage().contains("migId")) count++;
        }
        long overflow = (1L << 62) - 1L + (1L << 62);
        try {
            invoke("odManifestHistoryDir", SPACE, ENTITY, overflow, null);
        } catch (Throwable e) {
            Throwable t = unwrapInvoke(e);
            if (t instanceof IllegalArgumentException && t.getMessage().contains("hex width exceed")) count++;
        }
        try {
            invoke("colSchVersion", null, null, 0L, null);
        } catch (Throwable e) {
            Throwable t = unwrapInvoke(e);
            if (t instanceof IllegalArgumentException && containsAny(t.getMessage(), "entityClass", "blank")) count++;
        }
        try {
            invoke("colSchVersion", SPACE, sb.toString(), 0L, null);
        } catch (Throwable e) {
            Throwable t = unwrapInvoke(e);
            if (t instanceof IllegalArgumentException && (t.getMessage().contains("too long") || t.getMessage().contains("256"))) count++;
        }
        try {
            invoke("odTombCol", SPACE, ENTITY, 0L, SUB_LEAF);
        } catch (Throwable e) {
            Throwable t = unwrapInvoke(e);
            if (t instanceof IllegalArgumentException && t.getMessage().contains("colId <= 0")) count++;
        }
        try {
            invoke("odTombCol", SPACE, ENTITY, COL_ID, "");
        } catch (Throwable e) {
            Throwable t = unwrapInvoke(e);
            if (t instanceof IllegalArgumentException && containsAny(t.getMessage(), "subLeaf", "blank")) count++;
        }
        try {
            invoke("odTombDir", null, ENTITY, COL_ID, SUB_LEAF);
        } catch (Throwable e) {
            Throwable t = unwrapInvoke(e);
            if (t instanceof IllegalArgumentException && containsAny(t.getMessage(), "dbSpaceName", "blank")) count++;
        }
        try {
            invoke("odTombCol", SPACE, ENTITY, overflow, SUB_LEAF);
        } catch (Throwable e) {
            Throwable t = unwrapInvoke(e);
            if (t instanceof IllegalArgumentException && t.getMessage().contains("hex width exceed")) count++;
        }
        Assert.assertTrue("IAE 非法参数 >=10 条（实锤=" + count + "）", count >= 10);
    }

    // ==================== TR-1.3 t03: NG-4 zeroLeak ====================

    @Test
    public void t03_zeroLeakAfterExcludingLegacyOnlyRegistryMetaPaths() throws Exception {
        File userDir = new File(System.getProperty("user.dir"));
        File src = new File(userDir, "p2p-db/src/main/java");
        if (!src.exists()) {
            src = new File("I:/2026/code/p2p-net-storage/p2p-db/src/main/java");
        }
        Assert.assertTrue("src main java 目录存在: " + src.getAbsolutePath(), src.isDirectory());
        Pattern pat = Pattern.compile("\\bmanifest\\b|\\bschemaVersion\\b|\\btombstones\\b|\\bmigrateId\\b|\\bonlineDdl\\b");
        List<File> matched = new ArrayList<>();
        grepRecursive(src, pat, matched);

        Set<String> legacy = new HashSet<>(Arrays.asList(
                "DsFullBackup.java",
                "DsIncrementalBackup.java",
                "DsTimelineRestore.java",
                "DsDatabaseMetaMigrator.java"
        ));
        List<File> nonLegacy = new ArrayList<>();
        for (File f : matched) {
            if (!legacy.contains(f.getName())) {
                nonLegacy.add(f);
            }
        }
        long regCount = nonLegacy.stream().filter(f -> "RegistryMetaPaths.java".equals(f.getName())).count();
        Assert.assertTrue("至少 1 处 RegistryMetaPaths 命中（实锤 nonLegacy=" + nonLegacy.size()
                + " names=" + matchedFileNames(nonLegacy) + "）", regCount >= 1L);
        Assert.assertEquals("零外泄：排除 P10/P12 legacy 4 文件后仅 RegistryMetaPaths 命中（非 legacy="
                + nonLegacy.size() + " files: " + matchedFileNames(nonLegacy) + "）",
                regCount, (long) nonLegacy.size());
    }

    // ==================== TR-1.2 t04: nullable signature ====================

    @Test
    public void t04_nullablePersistenceAndSignatureDiverge() throws Exception {
        Method computeSig = TableMetaStore.class.getDeclaredMethod("computeSignature", TableMeta.class);
        computeSig.setAccessible(true);

        TableMeta mT = new TableMeta();
        mT.entityClassName = ENTITY;
        mT.columns = new ArrayList<>();
        mT.compositeGroups = new java.util.LinkedHashMap<>();
        ColumnDef cTrue = new ColumnDef();
        cTrue.colId = 1L;
        cTrue.colKey = ENTITY + "#name";
        cTrue.name = "name";
        cTrue.javaType = "java.lang.String";
        cTrue.length = 64;
        cTrue.nullable = Boolean.TRUE;
        mT.columns.add(cTrue);
        String sigT = (String) computeSig.invoke(null, mT);

        TableMeta mF = new TableMeta();
        mF.entityClassName = ENTITY;
        mF.columns = new ArrayList<>();
        mF.compositeGroups = new java.util.LinkedHashMap<>();
        ColumnDef cFalse = new ColumnDef();
        cFalse.colId = 1L;
        cFalse.colKey = ENTITY + "#name";
        cFalse.name = "name";
        cFalse.javaType = "java.lang.String";
        cFalse.length = 64;
        cFalse.nullable = Boolean.FALSE;
        mF.columns.add(cFalse);
        String sigF = (String) computeSig.invoke(null, mF);

        Assert.assertNotEquals("nullable=T vs nullable=F 签名必须不同", sigT, sigF);

        TableMeta mNull = new TableMeta();
        mNull.entityClassName = ENTITY;
        mNull.columns = new ArrayList<>();
        mNull.compositeGroups = new java.util.LinkedHashMap<>();
        ColumnDef cNull = new ColumnDef();
        cNull.colId = 1L;
        cNull.colKey = ENTITY + "#name";
        cNull.name = "name";
        cNull.javaType = "java.lang.String";
        cNull.length = 64;
        cNull.nullable = null;
        mNull.columns.add(cNull);
        String sigNull = (String) computeSig.invoke(null, mNull);

        Assert.assertEquals("BackwardCompat nullable=null 必须等价 true", sigT, sigNull);

        Method colNullable = TableMetaStore.class.getDeclaredMethod("colNullable", ColumnDef.class);
        colNullable.setAccessible(true);
        ColumnDef onlyNull = new ColumnDef();
        onlyNull.nullable = null;
        Assert.assertTrue("null -> true", (Boolean) colNullable.invoke(null, onlyNull));
        ColumnDef onlyT = new ColumnDef();
        onlyT.nullable = Boolean.TRUE;
        Assert.assertTrue("TRUE -> true", (Boolean) colNullable.invoke(null, onlyT));
        ColumnDef onlyF = new ColumnDef();
        onlyF.nullable = Boolean.FALSE;
        Assert.assertFalse("FALSE -> false", (Boolean) colNullable.invoke(null, onlyF));
    }
}
