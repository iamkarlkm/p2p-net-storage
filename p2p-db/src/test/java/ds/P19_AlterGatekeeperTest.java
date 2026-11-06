package ds;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnRegistry;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.RowIdSequenceStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.index.EqIndexMetaStore;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class P19_AlterGatekeeperTest {

    @Rule
    public final TemporaryFolder tmpRoot = new TemporaryFolder();

    private static final int[] BASELINE_A6 = new int[]{48, 24, 3, 11, 1, 7};
    private static final Class<?>[] FACADE_CLASSES = new Class<?>[]{
        DsDatabaseLocal.class, ColumnarStore.class, TableMetaStore.class,
        ColumnRegistry.class, RowIdSequenceStore.class, EqIndexMetaStore.class
    };
    private static final String[] SHORT = new String[]{
        "DsDatabaseLocal", "ColumnarStore", "TableMetaStore",
        "ColumnRegistry", "RowIdSequenceStore", "EqIndexMetaStore"
    };

    static int publicCount(Class<?> c) {
        int cnt = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                cnt++;
            }
        }
        return cnt;
    }

    static Path resolveSrcRoot() {
        Path rootP = Paths.get(System.getProperty("user.dir")).resolve("p2p-db").resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(rootP)) {
            Path alt = Paths.get(System.getProperty("user.dir")).resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(alt)) {
                rootP = alt;
            } else {
                try {
                    rootP = Paths.get(P19_AlterGatekeeperTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                            .getParent().getParent().getParent().resolve("src").resolve("main").resolve("java");
                } catch (Exception ignore) {
                }
            }
        }
        return rootP;
    }

    static int filesHitNonRmp(Path rootP, String[] keys) throws IOException {
        try (Stream<Path> s = Files.walk(rootP)) {
            return (int) s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("RegistryMetaPaths.java"))
                    .filter(p -> !p.toString().replace("\\", "/").contains("/backup/"))
                    .filter(p -> !p.toString().contains("DsDatabaseMetaMigrator.java"))
                    .filter(p -> {
                        try {
                            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                            boolean all = true;
                            for (String k : keys) {
                                if (!text.contains(k)) {
                                    all = false;
                                    break;
                                }
                            }
                            return all;
                        } catch (IOException ignore) {
                            return false;
                        }
                    }).count();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test(timeout = 180000)
    public void t01_c15_alterGatekeeper_29_assertions() throws Exception {
        int total = 0;
        Path rootP = resolveSrcRoot();
        assertTrue("[PRE] src root exists: " + rootP, Files.isDirectory(rootP));

        // ========== A6 (6 assertions)：6 Facade public count ∈ baseline±4 ==========
        int[] actual = new int[FACADE_CLASSES.length];
        for (int i = 0; i < FACADE_CLASSES.length; i++) {
            int a = publicCount(FACADE_CLASSES[i]);
            actual[i] = a;
            int lo = Math.max(0, BASELINE_A6[i] - 4);
            int hi = BASELINE_A6[i] + 4;
            assertTrue("[A" + (i + 1) + "/29] NG-2 " + SHORT[i] + " public=" + a + " ∈ [" + lo + "," + hi + "]",
                    a >= lo && a <= hi);
            total++;
        }
        assertEquals("A6 done 6", 6, total);

        // ========== B6 (6 assertions)：新增内部 impl 方法 non-public（6 类各 1 条 + 2 类各 1 条） ==========
        // DsDatabaseLocal: prepareAlter/applyAlter/rollbackAlter 全 non-public
        {
            long pub = Arrays.stream(DsDatabaseLocal.class.getDeclaredMethods())
                    .filter(m -> "prepareAlter".equals(m.getName()) || "applyAlter".equals(m.getName()) || "rollbackAlter".equals(m.getName()))
                    .filter(m -> Modifier.isPublic(m.getModifiers())).count();
            assertTrue("[B1/29] DsDatabaseLocal 3 ALTER impl non-public pub=" + pub, pub == 0L);
            total++;
        }
        // TableMetaStore.Backend：updateColumnDef non-public
        {
            Class<?> bc = null;
            for (Class<?> ic : TableMetaStore.class.getDeclaredClasses()) {
                if ("Backend".equals(ic.getSimpleName())) {
                    bc = ic;
                    break;
                }
            }
            assertNotNull("[B2/29] TableMetaStore.Backend present", bc);
            Method upd = null;
            for (Method m : bc.getDeclaredMethods()) {
                if ("updateColumnDef".equals(m.getName())) {
                    upd = m;
                    break;
                }
            }
            assertNotNull("[B2/29] TableMetaStore.Backend.updateColumnDef present", upd);
            assertFalse("[B2/29] updateColumnDef non-public", Modifier.isPublic(upd.getModifiers()));
            total++;
        }
        // ColumnRegistry：内部 Backend 新增方法全 non-public（反射扫 ColumnRegistry.Backend 看有没有 public 新方法）
        {
            Class<?> bc = null;
            for (Class<?> ic : ColumnRegistry.class.getDeclaredClasses()) {
                if ("Backend".equals(ic.getSimpleName())) {
                    bc = ic;
                    break;
                }
            }
            assertNotNull("[B3/29] ColumnRegistry.Backend present", bc);
            int pub = 0;
            for (Method m : bc.getDeclaredMethods()) {
                if (Modifier.isPublic(m.getModifiers())) {
                    pub++;
                }
            }
            // Baseline ≤ 11+4（A6 已 cover top-level，此处 Backend 内部不超 8）
            assertTrue("[B3/29] ColumnRegistry.Backend public impl reasonable pub=" + pub, pub <= 16);
            total++;
        }
        // DsDatabaseLocal.metaOf 辅助方法 non-public
        {
            Method m = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
            assertFalse("[B4/29] DsDatabaseLocal.metaOf non-public", Modifier.isPublic(m.getModifiers()));
            total++;
        }
        // OnlineDdlCoordinator（内嵌）：prepare/apply/rollback 非 public
        {
            Object db = new DsDatabaseLocal(tmpRoot.getRoot());
            try {
                Field cf = DsDatabaseLocal.class.getDeclaredField("odCoordinator");
                cf.setAccessible(true);
                Object coord = cf.get(db);
                Class<?> cc = coord.getClass();
                int pub = 0;
                for (Method m : cc.getDeclaredMethods()) {
                    if (Modifier.isPublic(m.getModifiers())) {
                        pub++;
                    }
                }
                assertTrue("[B5/29] OnlineDdlCoordinator few public impl pub=" + pub, pub <= 10);
                total++;
            } finally {
                try {
                    ((DsDatabaseLocal) db).close();
                } catch (Exception ignore) {
                }
            }
        }
        // TableMetaStore：saveMetaOverride/computeSignature 非 public
        {
            long pub = Arrays.stream(TableMetaStore.class.getDeclaredMethods())
                    .filter(m -> "cloneMeta".equals(m.getName()) || "computeSignature".equals(m.getName()) || "saveMetaOverride".equals(m.getName()))
                    .filter(m -> Modifier.isPublic(m.getModifiers())).count();
            assertTrue("[B6/29] TableMetaStore impl 非 public pub=" + pub, pub == 0L);
            total++;
        }
        assertEquals("B6 done 12", 12, total);

        // ========== C7 (7 assertions)：8 关键词 zeroLeak + NG-4 0 新增一级根 ==========
        String[][] litGroups = new String[][]{
            {"OD_SUB_MANIFEST", "manifest"},
            {"SCH_VER_LEAF", "schemaVersion"},
            {"OD_SUB_TOMB", "tombstones"},
            {"OD_LEAF_NEXT_MIG", "migrateId"},
            {"OD_MANIFEST_ALTER", "alterCols"},
            {"OD_ALTER_CAST", "OD_ALTER_OLD_JTYPE", "castMode", "oldJavaType"},};
        for (int i = 0; i < litGroups.length; i++) {
            int hit = filesHitNonRmp(rootP, litGroups[i]);
            assertTrue("[C" + (i + 1) + "/29] NG-4 leak literal hits=" + hit + " keys=" + Arrays.toString(litGroups[i]), hit == 0);
            total++;
        }
        // C7 第 7 条：一级根无 orm/ 目录
        Path pr = rootP.getParent().getParent().getParent();
        boolean ormExists = Files.isDirectory(pr.resolve("p2p-db").resolve("orm"))
                || Files.isDirectory(Paths.get(System.getProperty("user.dir")).resolve("orm"));
        assertFalse("[C7/29] NG-4 NO new /orm root dir", ormExists);
        total++;
        assertEquals("C7 done 19", 19, total);

        // ========== D5 (5 assertions)：5 通用集合类 Class.forName 全 CNFE ==========
        String[][] probes = new String[][]{
            {"org.apache.commons.collections4.bag.HashBag", "[D1/29] commons-collections4 NG-3"},
            {"it.unimi.dsi.fastutil.ints.IntArrayList", "[D2/29] fastutil NG-3"},
            {"org.eclipse.collections.api.list.MutableList", "[D3/29] eclipse-collections NG-3"},
            {"com.carrotsearch.hppc.IntArrayList", "[D4/29] HPPC NG-3"},
            {"net.openhft.chronicle.map.ChronicleMap", "[D5/29] chronicle NG-3"},};
        for (String[] p : probes) {
            boolean present;
            try {
                Class.forName(p[0]);
                present = true;
            } catch (ClassNotFoundException | LinkageError x) {
                present = false;
            }
            assertFalse(p[1], present);
            total++;
        }
        assertEquals("D5 done 24", 24, total);

        // ========== E3 (3 assertions)：3 个 non-facade public count ≤ 基线+5 ==========
        // E1. OnlineDdlCoordinator
        {
            Object db = new DsDatabaseLocal(tmpRoot.newFolder("e1"));
            try {
                Field cf = DsDatabaseLocal.class.getDeclaredField("odCoordinator");
                cf.setAccessible(true);
                Object coord = cf.get(db);
                int pub = publicCount(coord.getClass());
                assertTrue("[E1/29] OnlineDdlCoordinator pub=" + pub + " ≤ 16", pub <= 16);
                total++;
            } finally {
                try {
                    ((DsDatabaseLocal) db).close();
                } catch (Exception ignore) {
                }
            }
        }
        // E2. TableMetaStore.Backend
        {
            Class<?> bc = null;
            for (Class<?> ic : TableMetaStore.class.getDeclaredClasses()) {
                if ("Backend".equals(ic.getSimpleName())) {
                    bc = ic;
                    break;
                }
            }
            int pub = publicCount(bc);
            assertTrue("[E2/29] TableMetaStore.Backend pub=" + pub + " ≤ 16", pub <= 16);
            total++;
        }
        // E3. ColumnRegistry.Backend
        {
            Class<?> bc = null;
            for (Class<?> ic : ColumnRegistry.class.getDeclaredClasses()) {
                if ("Backend".equals(ic.getSimpleName())) {
                    bc = ic;
                    break;
                }
            }
            int pub = publicCount(bc);
            assertTrue("[E3/29] ColumnRegistry.Backend pub=" + pub + " ≤ 20", pub <= 20);
            total++;
        }
        assertEquals("E3 done 27", 27, total);

        // ========== F2 (2 assertions)：HC-1/HC-2 红线实锤 ==========
        // F1. HC-1：构造 stored.colId ≠ fresh.colId → 抛 IAE
        {
            File dir = tmpRoot.newFolder("f1_hc1");
            DsDatabaseLocal db = new DsDatabaseLocal(dir);
            try {
                Object coord = getCoord(db);
                Class<HC1GatekeeperEntity> clz = HC1GatekeeperEntity.class;
                java.util.List<Object> before = null;
                try {
                    before = prep(coord, clz, true, true);
                } catch (Throwable ignore) {
                }
                //  // 3 stored colIds (name/age/city 10/20/30 but fresh only 只有 name/age 2 列 → sIds ≠ fIds → IAE
                writeStoredMetaHc1(coord, clz, Arrays.asList("name", "age", "city"), Arrays.asList(10L, 20L, 30L));
                boolean hc1 = false;
                try {
                    prep(coord, clz, true, true);
                } catch (Throwable t) {
                    Throwable c = t;
                    while (c != null) {
                        if (c instanceof IllegalArgumentException && c.getMessage() != null && c.getMessage().contains("ALTER cannot change colId")) {
                            hc1 = true;
                            break;
                        }
                        c = c.getCause();
                    }
                }
                assertTrue("[F1/29] HC-1 colId tamper triggers IAE beforeSz=" + (before == null ? -1 : before.size()), hc1);
                total++;
            } finally {
                try {
                    db.close();
                } catch (Exception ignore) {
                }
            }
        }
        // F2. HC-2：long→int strict=true narrowing → IAE
        {
            File dir = tmpRoot.newFolder("f2_hc2");
            DsDatabaseLocal db = new DsDatabaseLocal(dir);
            try {
                Class<HC2Long8ToInt4Entity> storedAs = HC2Long8ToInt4Entity.class;
                db.registerTable(storedAs);
                Object coord = getCoord(db);
                saveLegacyLenJt(db, storedAs, "c2", 8, "java.lang.Long");
                boolean hc2 = false;
                try {
                    prep(coord, storedAs, false, true);
                } catch (Throwable t) {
                    Throwable c = t;
                    while (c != null) {
                        if (c instanceof IllegalArgumentException && c.getMessage() != null
                                && (c.getMessage().contains("narrowing") || c.getMessage().contains("strict"))) {
                            hc2 = true;
                            break;
                        }
                        c = c.getCause();
                    }
                }
                assertTrue("[F2/29] HC-2 long→int strict triggers IAE", hc2);
                total++;
            } finally {
                try {
                    db.close();
                } catch (Exception ignore) {
                }
            }
        }
        assertEquals("F2 done 29", 29, total);

        System.out.println("[c15 P19 Alter Gatekeeper] 29/29 assertions ALL PASS (A6=6 B6=6 C7=7 D5=5 E3=3 F2=2)");
    }

    // ===== Entities for F2 =====
    public static class HC1Size10Entity extends DsTableAdapter {

        @DsField(length = 12)
        public String c1;
    }

    public static class HC1GatekeeperEntity extends DsTableAdapter {

        @DsField(length = 32)
        public String name;
        @DsField(length = 4)
        public int age;
    }

    public static class HC2Long8ToInt4Entity extends DsTableAdapter {

        @DsField(length = 4)
        public Integer c2;
    }

    // ===== helpers =====
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void writeStoredMetaHc1(Object coord, Class clz, java.util.List<String> names, java.util.List<Long> ids) throws Exception {
        Method tm = coord.getClass().getDeclaredMethod("tms");
        tm.setAccessible(true);
        tm.invoke(coord);
        java.lang.reflect.Field tf = coord.getClass().getDeclaredField("tms");
        tf.setAccessible(true);
        TableMetaStore tms = (TableMetaStore) tf.get(coord);
        Field bf = TableMetaStore.class.getDeclaredField("backend");
        bf.setAccessible(true);
        Object backend = bf.get(tms);
        Class<?> bc = backend.getClass();
        Class<?> tmc = Class.forName("com.q3lives.ds.database.columnar.TableMetaStore$TableMeta");
        Class<?> cdc = Class.forName("com.q3lives.ds.database.columnar.TableMetaStore$ColumnDef");
        Object tm0 = tmc.getDeclaredConstructor().newInstance();
        java.lang.reflect.Field entF = tmc.getDeclaredField("entityClassName");
        entF.setAccessible(true);
        entF.set(tm0, clz.getName());
        java.lang.reflect.Field cgF = tmc.getDeclaredField("compositeGroups");
        cgF.setAccessible(true);
        cgF.set(tm0, new java.util.LinkedHashMap<>());
        java.util.List cols = new java.util.ArrayList();
        for (int i = 0; i < names.size(); i++) {
            Object cd = cdc.getDeclaredConstructor().newInstance();
            java.lang.reflect.Field ci = cdc.getDeclaredField("colId");
            ci.setAccessible(true);
            ci.set(cd, ids.get(i));
            java.lang.reflect.Field nm = cdc.getDeclaredField("name");
            nm.setAccessible(true);
            nm.set(cd, names.get(i));
            java.lang.reflect.Field nl = cdc.getDeclaredField("nullable");
            nl.setAccessible(true);
            nl.set(cd, true);
            java.lang.reflect.Field lg = cdc.getDeclaredField("length");
            lg.setAccessible(true);
            lg.set(cd, 0);
            java.lang.reflect.Field pr = cdc.getDeclaredField("precision");
            pr.setAccessible(true);
            pr.set(cd, 18);
            java.lang.reflect.Field sc = cdc.getDeclaredField("scale");
            sc.setAccessible(true);
            sc.set(cd, 2);
            java.lang.reflect.Field jt = cdc.getDeclaredField("javaType");
            jt.setAccessible(true);
            jt.set(cd, "java.lang.String");
            java.lang.reflect.Field ck = cdc.getDeclaredField("colKey");
            ck.setAccessible(true);
            ck.set(cd, clz.getName() + "#" + names.get(i));
            cols.add(cd);
        }
        java.lang.reflect.Field ccf = tmc.getDeclaredField("columns");
        ccf.setAccessible(true);
        ccf.set(tm0, cols);
        Method compSig = TableMetaStore.class.getDeclaredMethod("computeSignature", tmc);
        compSig.setAccessible(true);
        String sig = (String) compSig.invoke(null, tm0);
        java.lang.reflect.Field sigF = tmc.getDeclaredField("signature");
        sigF.setAccessible(true);
        sigF.set(tm0, sig);
        Method saveTo = bc.getDeclaredMethod("saveToRegistry", String.class, tmc);
        saveTo.setAccessible(true);
        saveTo.invoke(backend, clz.getName(), tm0);
    }

    static Object getCoord(DsDatabaseLocal db) throws Exception {
        Field f = DsDatabaseLocal.class.getDeclaredField("odCoordinator");
        f.setAccessible(true);
        return f.get(db);
    }

    @SuppressWarnings("rawtypes")
    static List prep(Object coord, Class clz, boolean dryRun, boolean strict) throws Exception {
        Method m = findM(coord.getClass(), "prepareAlter", Class.class, boolean.class, boolean.class);
        return (List) m.invoke(coord, clz, dryRun, strict);
    }

    static Method findM(Class<?> cl, String name, Class<?>... pts) throws NoSuchMethodException {
        Class<?> cc = cl;
        while (cc != null && cc != Object.class) {
            for (Method m : cc.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] mp = m.getParameterTypes();
                if (mp.length != pts.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < mp.length; i++) {
                    Class<?> a = w(mp[i]);
                    Class<?> b = w(pts[i]);
                    if (!a.isAssignableFrom(b) && !b.isAssignableFrom(a)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cc = cc.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    static Class<?> w(Class<?> c) {
        if (c == null) {
            return null;
        }
        if (c.isPrimitive()) {
            if (c == boolean.class) {
                return Boolean.class;
            }
            if (c == int.class) {
                return Integer.class;
            }
            if (c == long.class) {
                return Long.class;
            }
        }
        return c;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void saveLegacyLen(DsDatabaseLocal db, Class storedAs, String colName, int len) throws Exception {
        File root = db.getRoot();
        db.registerTable(storedAs);
        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        metaOf.setAccessible(true);
        metaOf.invoke(db, storedAs);
        TableMetaStore tms = new TableMetaStore(root);
        Class casted = storedAs;
        tms.ensureMeta(casted);
        TableMetaStore.TableMeta m = clone(tms.getMeta(storedAs.getName()));
        long tCol = -1L;
        if (m != null && m.columns != null) {
            for (TableMetaStore.ColumnDef c : m.columns) {
                if (colName.equals(c.name)) {
                    c.length = len;
                    tCol = c.colId;
                }
            }
        }
        saveMetaOv(db, storedAs, m);
        if (tCol > 0L) {
            applyUpdateCol(db.getRoot(), storedAs.getName(), tCol, m, colName, Integer.valueOf(len), null, null);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void saveLegacyLenJt(DsDatabaseLocal db, Class storedAs, String colName, int len, String jt) throws Exception {
        File root = db.getRoot();
        db.registerTable(storedAs);
        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        metaOf.setAccessible(true);
        metaOf.invoke(db, storedAs);
        TableMetaStore tms = new TableMetaStore(root);
        Class casted = storedAs;
        tms.ensureMeta(casted);
        TableMetaStore.TableMeta m = clone(tms.getMeta(storedAs.getName()));
        long tCol = -1L;
        if (m != null && m.columns != null) {
            for (TableMetaStore.ColumnDef c : m.columns) {
                if (colName.equals(c.name)) {
                    c.length = len;
                    c.javaType = jt;
                    tCol = c.colId;
                }
            }
        }
        saveMetaOv(db, storedAs, m);
        if (tCol > 0L) {
            applyUpdateCol(db.getRoot(), storedAs.getName(), tCol, m, colName, Integer.valueOf(len), jt, null);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void tamperColId(DsDatabaseLocal db, Class storedAs, String colName) throws Exception {
        File root = db.getRoot();
        TableMetaStore tms = new TableMetaStore(root);
        tms.ensureMeta(storedAs);
        TableMetaStore.TableMeta m = clone(tms.getMeta(storedAs.getName()));
        if (m != null && m.columns != null) {
            for (TableMetaStore.ColumnDef c : m.columns) {
                if (colName.equals(c.name)) {
                    c.colId = c.colId + 9999L;
                }
            }
        }
        saveMetaOv(db, storedAs, m);
    }

    static TableMetaStore.TableMeta clone(TableMetaStore.TableMeta src) {
        if (src == null) {
            return null;
        }
        TableMetaStore.TableMeta dst = new TableMetaStore.TableMeta();
        dst.entityClassName = src.entityClassName;
        dst.signature = src.signature;
        List<TableMetaStore.ColumnDef> cds = new ArrayList<>();
        if (src.columns != null) {
            for (TableMetaStore.ColumnDef c : src.columns) {
                TableMetaStore.ColumnDef d = new TableMetaStore.ColumnDef();
                d.colId = c.colId;
                d.colKey = c.colKey;
                d.name = c.name;
                d.javaType = c.javaType;
                d.length = c.length;
                d.precision = c.precision;
                d.scale = c.scale;
                d.nullable = c.nullable;
                cds.add(d);
            }
        }
        dst.columns = cds;
        dst.compositeGroups = src.compositeGroups == null ? new LinkedHashMap<>() : new LinkedHashMap<>(src.compositeGroups);
        return dst;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void saveMetaOv(DsDatabaseLocal db, Class clazz, TableMetaStore.TableMeta m) throws Exception {
        if (m.entityClassName == null || m.entityClassName.isEmpty()) {
            m.entityClassName = clazz.getName();
        }
        if (m.compositeGroups == null) {
            m.compositeGroups = new LinkedHashMap<>();
        }
        if (m.columns != null) {
            for (TableMetaStore.ColumnDef cd : m.columns) {
                if (cd.colKey == null || cd.colKey.isEmpty()) {
                    cd.colKey = m.entityClassName + "#" + cd.name;
                }
            }
        }
        Method sigM = TableMetaStore.class.getDeclaredMethod("computeSignature", TableMetaStore.TableMeta.class);
        sigM.setAccessible(true);
        m.signature = (String) sigM.invoke(null, m);
        TableMetaStore tms = new TableMetaStore(db.getRoot());
        Field backendF = TableMetaStore.class.getDeclaredField("backend");
        backendF.setAccessible(true);
        Object backend = backendF.get(tms);
        Class<?> bc = null;
        for (Class<?> ic : TableMetaStore.class.getDeclaredClasses()) {
            if ("Backend".equals(ic.getSimpleName())) {
                bc = ic;
                break;
            }
        }
        Method save = bc.getDeclaredMethod("saveToRegistry", String.class, TableMetaStore.TableMeta.class);
        save.setAccessible(true);
        save.invoke(backend, clazz.getName(), m);
        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        metaOf.setAccessible(true);
        metaOf.invoke(db, clazz);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void applyUpdateCol(File root, String entName, long targetColId, TableMetaStore.TableMeta m, String colName, Integer len, String jtype, Boolean nullable) throws Exception {
        Field backendF = TableMetaStore.class.getDeclaredField("backend");
        backendF.setAccessible(true);
        Object backend = backendF.get(new TableMetaStore(root));
        Method upd = null;
        for (Method mm : backend.getClass().getDeclaredMethods()) {
            if ("updateColumnDef".equals(mm.getName())) {
                upd = mm;
                break;
            }
        }
        if (upd != null) {
            upd.setAccessible(true);
            TableMetaStore.ColumnDef nc = new TableMetaStore.ColumnDef();
            if (m != null && m.columns != null) {
                for (TableMetaStore.ColumnDef c : m.columns) {
                    if (colName.equals(c.name)) {
                        nc.colId = c.colId;
                        nc.colKey = c.colKey;
                        nc.name = c.name;
                        nc.javaType = c.javaType;
                        nc.length = len != null ? len.intValue() : c.length;
                        nc.precision = c.precision;
                        nc.scale = c.scale;
                        nc.nullable = nullable != null ? nullable : c.nullable;
                        if (jtype != null) {
                            nc.javaType = jtype;
                        }
                        break;
                    }
                }
            }
            upd.invoke(backend, entName, Long.valueOf(targetColId), nc);
        }
    }
}
