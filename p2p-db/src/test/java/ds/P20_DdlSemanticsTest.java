package ds;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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

public class P20_DdlSemanticsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String savedHome;

    @Before
    public void setup() throws Exception {
        savedHome = System.getProperty("p2p.db.home");
        File root = tmp.newFolder("p20-t2-" + UUID.randomUUID().toString().replace("-","").substring(0,8));
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

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test
    public void tr2_1_parseCstCmdIAEAndEnumValues() throws Exception {
        Class<?> coordCls = findSubClassByName(DsDatabaseLocal.class, "OnlineDdlCoordinator");
        Assert.assertNotNull("OnlineDdlCoordinator 类存在", coordCls);
        Method parse = coordCls.getDeclaredMethod("parseCstCmd", String.class);
        parse.setAccessible(true);

        String[][] iaeCases = new String[][] {
                { null, "blank" },
                { "", "blank" },
                { "ADD_CST", "段数不足" },
                { "ADD_CST|cst=uk-e|type=UNIQUE|col=1|uk=true", "cstName nonAlnum" },
                { "ADD_CST|cst=ck1|type=UNKNOWN|col=1|uk=true", "type 非法" },
                { "ADD_CST|cst=ck1|type=UNIQUE|col=abc|uk=true", "col nonNum" },
                { "ADD_CST|cst=ck1|type=UNIQUE|col=1|uk=notbool", "bool 非法" },
                { "ADD_CST|cst=ck1|type=UNIQUE|col=1|uk=false", "UNIQUE 需 uk" },
                { "ADD_CST|cst=ck_len|type=CHECK|col=2|ck=", "CHECK 需 ck" },
                { "ADD_CST|cst=ck_len|type=CHECK|col=2|ck=ab", "CHECK 需 ck" },
                { "ADD_CST|cst=ck_len|type=NN|col=2|nn=false", "NN 需 nn" },
                { "ADD_CST|UNKNOWN_SEG=1|cst=a|type=UNIQUE|col=1|uk=true", "未知段名" }
        };
        for (String[] kase : iaeCases) {
            try {
                parse.invoke(null, kase[0]);
                Assert.fail("应抛 IAE: raw=\"" + kase[0] + "\" 子串=\"" + kase[1] + "\"");
            } catch (Exception ex) {
                Throwable t = unwrap(ex);
                Assert.assertTrue("IAE 子串 missing: expected=" + kase[1] + " actual=" + t.getMessage(),
                        t instanceof IllegalArgumentException
                                && t.getMessage() != null && t.getMessage().contains(kase[1]));
            }
        }

        Object rec = parse.invoke(null,
                "ADD_CST|cst=uk_email|type=UNIQUE|col=1|uk=true|nn=false|ck=");
        Assert.assertNotNull("parse 合法应非 null", rec);
        Method mCst = rec.getClass().getMethod("cst");
        Method mType = rec.getClass().getMethod("type");
        Method mCol = rec.getClass().getMethod("col");
        Method mUk = rec.getClass().getMethod("uk");
        mCst.setAccessible(true); mType.setAccessible(true); mCol.setAccessible(true); mUk.setAccessible(true);
        Assert.assertEquals("cst", "uk_email", (String) mCst.invoke(rec));
        Assert.assertEquals("type", "UNIQUE", (String) mType.invoke(rec));
        Assert.assertEquals("col", 1L, ((Number) mCol.invoke(rec)).longValue());
        Assert.assertTrue("uk=true", (Boolean) mUk.invoke(rec));
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test
    public void tr2_2_validateCstSpecBoundaries() throws Exception {
        Class<?> coordCls = findSubClassByName(DsDatabaseLocal.class, "OnlineDdlCoordinator");
        Class<?> cstSpecCls = findSubClassByName(coordCls, "CstSpec");
        Method validate = coordCls.getDeclaredMethod("validateCstSpec", cstSpecCls, TableMetaStore.TableMeta.class);
        validate.setAccessible(true);
        java.lang.reflect.Constructor<?> ctor = cstSpecCls.getDeclaredConstructor(
                String.class, String.class, long.class, String.class, boolean.class, boolean.class, boolean.class);
        ctor.setAccessible(true);

        TableMetaStore.TableMeta tmEmpty = new TableMetaStore.TableMeta();
        tmEmpty.entityClassName = "com.ex.Empty";
        tmEmpty.columns = new ArrayList<>();
        Object specBadCol = ctor.newInstance("a", "UNIQUE", 1L, null, true, false, false);
        try {
            validate.invoke(null, specBadCol, tmEmpty);
            Assert.fail("tm empty 应抛 IAE: tm 无 columns 或 tm 无 col");
        } catch (Exception ex) {
            Throwable t = unwrap(ex);
            Assert.assertTrue("tm empty missing: actual=" + t.getMessage(),
                    t instanceof IllegalArgumentException && t.getMessage() != null
                            && (t.getMessage().contains("tm 无 columns") || t.getMessage().contains("tm 无 col")));
        }

        Object[][] cases = new Object[][] {
                { null, tmEmpty, "tm null|spec null" },
                { ctor.newInstance("bad-n", "UNIQUE", 1L, null, true, false, false),
                        tmWithCols("com.ex.T", 2,3,4), "cst nonAlnum" },
                { ctor.newInstance("a", "UNIQUE", -1L, null, true, false, false),
                        tmWithCols("com.ex.T", 2,3,4), "col<0" },
                { ctor.newInstance("a", "", 1L, null, true, false, false),
                        tmWithCols("com.ex.T", 1), "type blank" },
                { ctor.newInstance("a", "UNIQUE", 1L, null, false, false, false),
                        tmWithCols("com.ex.T", 1), "UNIQUE→uk true" },
                { ctor.newInstance("a", "CHECK", 1L, "ab", false, false, false),
                        tmWithCols("com.ex.T", 1), "CHECK→ck 非空" },
                { ctor.newInstance("a", "NN", 1L, null, false, false, false),
                        tmWithCols("com.ex.T", 1), "NN→nn true" }
        };
        for (int i = 0; i < cases.length; i++) {
            Object spec = cases[i][0];
            TableMetaStore.TableMeta tm = (TableMetaStore.TableMeta) cases[i][1];
            String msg = (String) cases[i][2];
            try {
                validate.invoke(null, spec, tm);
                if (i > 0) Assert.fail("case i=" + i + " 应抛 IAE 子串=" + msg);
            } catch (Exception ex) {
                Throwable t = unwrap(ex);
                String act = t.getMessage() == null ? "" : t.getMessage();
                boolean ok = (t instanceof IllegalArgumentException);
                if (ok && msg != null) {
                    boolean any = false;
                    for (String alt : msg.split("\\|")) if (act.contains(alt)) { any = true; break; }
                    ok = any;
                }
                Assert.assertTrue("case i=" + i + " IAE子串 missing: expected=" + msg + " actual=" + act, ok);
            }
        }

        Object specValid = ctor.newInstance("uk_e", "UNIQUE", 1L, null, true, false, false);
        Object ok = validate.invoke(null, specValid, tmWithCols("com.ex.T", 1));
        Assert.assertTrue("valid spec→true", Boolean.TRUE.equals(ok));
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test
    public void tr2_3_execCstCmdIdempotentFiveTypes() throws Exception {
        Class<?> coordCls = findSubClassByName(DsDatabaseLocal.class, "OnlineDdlCoordinator");
        Class<?> backendCls = TableMetaStore.Backend.class;
        Method exec = coordCls.getDeclaredMethod("execCstCmd",
                findSubClassByName(coordCls, "CstCmdRec"), backendCls, TableMetaStore.TableMeta.class);
        exec.setAccessible(true);
        Method parse = coordCls.getDeclaredMethod("parseCstCmd", String.class);
        parse.setAccessible(true);
        Method readStr = backendCls.getDeclaredMethod("readStr", String.class);
        readStr.setAccessible(true);

        File root = new File(System.getProperty("p2p.db.home"));
        TableMetaStore.Backend backend = new TableMetaStore.Backend(root);
        String ENTITY = "com.ex.ds.T2User";
        String CST1 = "uk_email";

        TableMetaStore.TableMeta tm = tmWithCols(ENTITY, 1L, 2L, 3L);
        Object cmdAdd = parse.invoke(null,
                "ADD_CST|cst=" + CST1 + "|type=UNIQUE|col=1|uk=true|nn=false|ck=");
        exec.invoke(null, cmdAdd, backend, tm);
        String type1 = (String) readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, CST1, RegistryMetaPaths.OD_CST_TYPE));
        String createdAt1 = (String) readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, CST1, RegistryMetaPaths.OD_CST_CREATED_AT));
        Assert.assertEquals("UNIQUE type", "UNIQUE", type1);
        Assert.assertNotNull("createdAt 1 存在", createdAt1);

        Thread.sleep(3L);
        try {
            exec.invoke(null, cmdAdd, backend, tm);
        } catch (Exception ex) {
            Throwable t = unwrap(ex);
            Assert.fail("ADD 同 cst 同 type 应幂等不抛: " + t.getMessage() + " cause=" + t.getCause());
        }
        String createdAt2 = (String) readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, CST1, RegistryMetaPaths.OD_CST_CREATED_AT));
        Assert.assertEquals("幂等 createdAt 不变", createdAt1, createdAt2);

        Object cmdBadType = parse.invoke(null,
                "ADD_CST|cst=" + CST1 + "|type=CHECK|col=1|ck=a>0|uk=false|nn=false");
        try {
            exec.invoke(null, cmdBadType, backend, tm);
            Assert.fail("异 type ADD 应抛 IAE");
        } catch (Exception ex) {
            Throwable t = unwrap(ex);
            Assert.assertTrue("异 type IAE missing: " + t.getMessage(),
                    t instanceof IllegalArgumentException && t.getMessage().contains("type 已存在异 type"));
        }

        Object cmdAltEnb = parse.invoke(null,
                "ALT_CST|cst=" + CST1 + "|type=UNIQUE|col=1|uk=true|nn=false|ck=|enb=false");
        exec.invoke(null, cmdAltEnb, backend, tm);
        String enb = (String) readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, CST1, RegistryMetaPaths.OD_CST_ENABLED));
        Assert.assertEquals("ALT enb=false", "false", enb);
        String typeAfter = (String) readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, CST1, RegistryMetaPaths.OD_CST_TYPE));
        Assert.assertEquals("ALT 不影响 type", "UNIQUE", typeAfter);

        Object cmdEnb = parse.invoke(null, "ENB_CST|cst=" + CST1 + "|type=UNIQUE|col=1|uk=true");
        exec.invoke(null, cmdEnb, backend, tm);
        Assert.assertEquals("ENB→true", "true", (String) readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, CST1, RegistryMetaPaths.OD_CST_ENABLED)));
        Object cmdDsb = parse.invoke(null, "DSB_CST|cst=" + CST1 + "|type=UNIQUE|col=1|uk=true");
        exec.invoke(null, cmdDsb, backend, tm);
        Assert.assertEquals("DSB→false", "false", (String) readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, CST1, RegistryMetaPaths.OD_CST_ENABLED)));

        Object cmdDrp = parse.invoke(null, "DRP_CST|cst=" + CST1 + "|type=UNIQUE|col=1|uk=true");
        exec.invoke(null, cmdDrp, backend, tm);
        Assert.assertNull("DROP后 type 不存在", readStr.invoke(backend,
                RegistryMetaPaths.odCstLeaf(ENTITY, CST1, RegistryMetaPaths.OD_CST_TYPE)));
    }

    @Test
    public void tr2_4_ng4ZeroLeakAndT1Regression() throws Exception {
        File userDir = new File(System.getProperty("user.dir"));
        File src = new File(userDir, "p2p-db/src/main/java");
        if (!src.exists()) src = new File("I:/2026/code/p2p-net-storage/p2p-db/src/main/java");
        Assert.assertTrue("src main java", src.isDirectory());
        Pattern ng4 = Pattern.compile(
                "\\bconstraint\\b|\\buniqueKey\\b|\\bcheckExpr\\b|\\bnotNull\\b|\\bvalidateConstraint\\b");
        List<File> hit = new ArrayList<>();
        grepRecursive(src, ng4, hit);
        Set<String> white4 = new HashSet<>(Arrays.asList(
                "RegistryMetaPaths.java","TableMetaStore.java","DsEqIndexStore.java","DsDatabaseLocal.java"));
        List<File> nonLegacy = new ArrayList<>();
        for (File f : hit) if (!white4.contains(f.getName())) nonLegacy.add(f);
        long regCount = nonLegacy.stream().filter(f -> "RegistryMetaPaths.java".equals(f.getName())).count();
        if (nonLegacy.size() > 0) {
            Assert.assertTrue("NG-4 零外泄: nonLegacy=" + nonLegacy.size() + " names=" + names(nonLegacy),
                    regCount >= 1L);
            Assert.assertEquals("NG-4 零外泄 仅 RMP+4主源码: nonLegacy=" + names(nonLegacy),
                    regCount, (long) nonLegacy.size());
        }
        Pattern p19 = Pattern.compile(
                "\\bmanifest\\b|\\bschemaVersion\\b|\\btombstones\\b|\\bmigrateId\\b|\\bonlineDdl\\b"
                + "|\\balterCols\\b|\\bcastMode\\b|\\boldJavaType\\b");
        List<File> p19Hit = new ArrayList<>();
        grepRecursive(src, p19, p19Hit);
        Set<String> legacy4 = new HashSet<>(Arrays.asList(
                "DsFullBackup.java","DsIncrementalBackup.java","DsTimelineRestore.java","DsDatabaseMetaMigrator.java"));
        List<File> nonL = new ArrayList<>();
        for (File f : p19Hit) if (!legacy4.contains(f.getName())) nonL.add(f);
        long rmp = nonL.stream().filter(f -> "RegistryMetaPaths.java".equals(f.getName())).count();
        if (!nonL.isEmpty()) {
            Assert.assertTrue("P19 RMP 至少1命中: nonL=" + names(nonL), rmp >= 1L);
            Assert.assertEquals("P19 零外泄 非 legacy4/RMP 0: names=" + names(nonL), rmp, (long) nonL.size());
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur instanceof java.lang.reflect.InvocationTargetException
                && cur.getCause() != null) cur = cur.getCause();
        return cur;
    }

    private static TableMetaStore.TableMeta tmWithCols(String name, long... cols) {
        TableMetaStore.TableMeta tm = new TableMetaStore.TableMeta();
        tm.entityClassName = name;
        tm.columns = new ArrayList<>();
        for (long c : cols) {
            TableMetaStore.ColumnDef cd = new TableMetaStore.ColumnDef();
            cd.colId = c;
            cd.colKey = "col_" + c;
            cd.name = "col_" + c;
            cd.javaType = "java.lang.String";
            cd.length = 255;
            cd.nullable = true;
            cd.unique = false;
            cd.checkExpr = null;
            tm.columns.add(cd);
        }
        tm.compositeGroups = new HashMap<>();
        return tm;
    }

    private static List<String> names(List<File> fs) {
        List<String> r = new ArrayList<>();
        for (File f : fs) r.add(f.getName());
        return r;
    }

    private static void grepRecursive(File root, Pattern p, List<File> out) throws Exception {
        if (root == null || !root.exists()) return;
        if (root.isDirectory()) {
            File[] ch = root.listFiles();
            if (ch == null) return;
            for (File c : ch) grepRecursive(c, p, out);
            return;
        }
        if (!root.getName().endsWith(".java")) return;
        Path pth = root.toPath();
        if (!Files.isReadable(pth)) return;
        try {
            String txt = new String(Files.readAllBytes(pth), "UTF-8");
            if (p.matcher(txt).find()) out.add(root);
        } catch (Exception ignored) {}
    }
}
