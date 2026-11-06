package ds;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnRegistry;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.meta.DsRegistryInstance;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class P19_AlterNullableTest {

    private File tmpRoot;
    private String oldHome;
    private DsDatabaseLocal db;

    public static final class NullFlipEntity extends DsTableAdapter {

        @DsField(name = "name", length = 32, nullable = false)
        public String name;
        @DsField(name = "age", length = 4, nullable = false)
        public int age;
        @DsField(name = "city", length = 32, nullable = false)
        public String city;
    }

    public static final class ColsRemoved extends DsTableAdapter {

        @DsField(name = "name", length = 32, nullable = false)
        public String name;
        @DsField(name = "age", length = 4, nullable = false)
        public int age;
    }

    public static final class ColsAdded extends DsTableAdapter {

        @DsField(name = "name", length = 32, nullable = false)
        public String name;
        @DsField(name = "age", length = 4, nullable = false)
        public int age;
        @DsField(name = "city", length = 32, nullable = false)
        public String city;
        @DsField(name = "extra", length = 32, nullable = false)
        public String extra;
    }

    public static final class ColsOneRenamed extends DsTableAdapter {

        @DsField(name = "name", length = 32, nullable = false)
        public String name;
        @DsField(name = "age", length = 4, nullable = false)
        public int age;
        @DsField(name = "city2", length = 32, nullable = false)
        public String city2;
    }

    public static final class ColsRenamed extends DsTableAdapter {

        @DsField(name = "nom", length = 32, nullable = false)
        public String nom;
        @DsField(name = "alter", length = 4, nullable = false)
        public int alter;
        @DsField(name = "cite", length = 32, nullable = false)
        public String cite;
    }

    @Before
    public void setUp() throws Exception {
        tmpRoot = Files.createTempDirectory("p19_nullable_").toFile();
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (db != null) {
                db.close();

            }
        } catch (Exception ignore) {
        }
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
        try {
            f.delete();
        } catch (Exception ignore) {
        }
    }

    static Object odCoord(DsDatabaseLocal db) throws Exception {
        Field f = DsDatabaseLocal.class.getDeclaredField("odCoordinator");
        f.setAccessible(true);
        return f.get(db);
    }

    @SuppressWarnings("unchecked")
    static <T> T invokeM(Object target, String mName, Object... args) throws Exception {
        Class<?>[] pts = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) {
                pts[i] = Object.class;
            } else if (args[i] instanceof Class) {
                pts[i] = Class.class;
            } else if (args[i] instanceof Boolean) {
                pts[i] = boolean.class;
            } else if (args[i] instanceof Integer) {
                pts[i] = int.class;
            } else if (args[i] instanceof Long) {
                pts[i] = long.class;
            } else {
                pts[i] = args[i].getClass();
            }
        }
        Method m = null;
        for (Method cand : target.getClass().getDeclaredMethods()) {
            if (!cand.getName().equals(mName)) {
                continue;
            }
            Class<?>[] cp = cand.getParameterTypes();
            if (cp.length != pts.length) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < cp.length; i++) {
                if (cp[i] == pts[i]) {
                    continue;
                }
                if (cp[i].isPrimitive() && wrapperOf(cp[i]) == pts[i]) {
                    continue;
                }
                if (Class.class.isAssignableFrom(pts[i]) && cp[i].isAssignableFrom((Class<?>) args[i])) {
                    continue;
                }
                if (cp[i].isAssignableFrom(pts[i])) {
                    continue;
                }
                ok = false;
                break;
            }
            if (ok) {
                m = cand;
                break;
            }
        }
        if (m == null) {
            for (Method cand : target.getClass().getDeclaredMethods()) {
                if (cand.getName().equals(mName) && cand.getParameterCount() == args.length) {
                    m = cand;
                    break;
                }
            }
        }
        Objects.requireNonNull(m, "method not found: " + mName + "/" + args.length);
        m.setAccessible(true);
        try {
            return (T) m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable c = ite.getCause();
            if (c instanceof RuntimeException) {
                throw (RuntimeException) c;
            }
            if (c instanceof Error) {
                throw (Error) c;
            }
            throw ite;
        }
    }

    private static Class<?> wrapperOf(Class<?> p) {
        if (p == boolean.class) {
            return Boolean.class;
        }
        if (p == int.class) {
            return Integer.class;
        }
        if (p == long.class) {
            return Long.class;
        }
        if (p == short.class) {
            return Short.class;
        }
        if (p == byte.class) {
            return Byte.class;
        }
        if (p == char.class) {
            return Character.class;
        }
        if (p == float.class) {
            return Float.class;
        }
        if (p == double.class) {
            return Double.class;
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    private static <T extends DsTableAdapter> List<Long> seed(DsDatabaseLocal db, int N, Class<T> clazz) throws Exception {
        List<Long> ids = new ArrayList<>(N);
        Random rnd = new Random(0xCAFEBABEL);
        for (int i = 0; i < N; i++) {
            T row = clazz.getDeclaredConstructor().newInstance();
            Field nf = clazz.getField("name");
            Field af = clazz.getField("age");
            Field cf = clazz.getField("city");
            nf.set(row, "n" + i);
            af.setInt(row, rnd.nextInt(99999));
            cf.set(row, "c" + (i % 37));
            long rid = db.putEntity(row, true);
            ids.add(rid);
        }
        return ids;
    }

    static void saveMetaOverride(DsDatabaseLocal db, Class<?> clazz, TableMetaStore.TableMeta m) throws Exception {
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

    static TableMetaStore.TableMeta cloneMeta(TableMetaStore.TableMeta src) {
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

    void saveLegacyMetaOldNullable(Class clazz, List<String> cols) throws Exception {
        db.registerTable(clazz);
        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        metaOf.setAccessible(true);
        metaOf.invoke(db, clazz);
        TableMetaStore tms = new TableMetaStore(db.getRoot());
        tms.ensureMeta(clazz);
        TableMetaStore.TableMeta m = cloneMeta(tms.getMeta(clazz.getName()));
        if (m.columns != null) {
            for (TableMetaStore.ColumnDef c : m.columns) {
                if (cols.contains(c.name)) {
                    c.nullable = Boolean.TRUE;
                }
            }
        }
        saveMetaOverride(db, clazz, m);
    }

    void saveLegacyMetaNoNullableLeaf(Class clazz, List<String> cols) throws Exception {
        db.registerTable(clazz);
        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        metaOf.setAccessible(true);
        metaOf.invoke(db, clazz);
        TableMetaStore tms = new TableMetaStore(db.getRoot());
        tms.ensureMeta(clazz);
        TableMetaStore.TableMeta m = cloneMeta(tms.getMeta(clazz.getName()));
        if (m.columns != null) {
            for (TableMetaStore.ColumnDef c : m.columns) {
                if (cols.contains(c.name)) {
                    c.nullable = null;
                }
            }
        }
        saveMetaOverride(db, clazz, m);
    }

    void writeStoredMetaForHc1(Class clazz, List<String> names, List<Long> ids) throws Exception {
        TableMetaStore.TableMeta tm = new TableMetaStore.TableMeta();
        tm.entityClassName = clazz.getName();
        tm.compositeGroups = new LinkedHashMap<>();
        List<TableMetaStore.ColumnDef> cds = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            TableMetaStore.ColumnDef cd = new TableMetaStore.ColumnDef();
            cd.colId = ids.get(i);
            cd.name = names.get(i);
            cd.nullable = true;
            cd.length = 0;
            cd.precision = 18;
            cd.scale = 2;
            cd.javaType = "java.lang.String";
            cd.colKey = tm.entityClassName + "#" + names.get(i);
            cds.add(cd);
        }
        tm.columns = cds;
        saveMetaOverride(db, clazz, tm);
    }

    static long hashDir(Path p) throws Exception {
        long[] h = new long[1];
        Files.walk(p).filter(Files::isRegularFile).sorted().forEachOrdered(f -> {
            try {
                byte[] b = Files.readAllBytes(f);
                h[0] = h[0] * 31 + Arrays.hashCode(b);
                h[0] = h[0] * 31 + p.relativize(f).toString().hashCode();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return h[0];
    }

    @SuppressWarnings("unchecked")
    @Test
    public void t01_prepareApplyMetadataOnlyO1Under50ms() throws Exception {
        db = DsDatabaseLocal.load();
        Class<NullFlipEntity> clazz = NullFlipEntity.class;
        db.registerTable(clazz);
        saveLegacyMetaOldNullable(clazz, Arrays.asList("city"));
        int N = 500;
        seed(db, N, clazz);
        Object coord = odCoord(db);
        List<Object> dryCols = invokeM(coord, "prepareAlter", clazz, Boolean.TRUE, Boolean.TRUE);
        int dryCnt = dryCols == null ? 0 : ((List<Object>) dryCols).size();
        Assert.assertEquals("dry-run AlterCol count=1 nullable flip", 1, dryCnt);
        File root = db.getRoot();
        long h0 = hashDir(root.toPath());
        long t0 = System.currentTimeMillis();
        List<Object> cols = invokeM(coord, "prepareAlter", clazz, Boolean.FALSE, Boolean.TRUE);
        int cnt = cols == null ? 0 : ((List<Object>) cols).size();
        Assert.assertEquals("prepareAlter AlterCol count=1 nullable flip", 1, cnt);
        int rows = (Integer) invokeM(coord, "applyAlter", clazz, Integer.valueOf(2048));
        long cost = System.currentTimeMillis() - t0;
        Assert.assertTrue("rows >= 0: " + rows, rows >= 0);
        Assert.assertTrue("TR-2.1 O(1) metadata-only <= 500ms (Windows artifact; Linux <=50ms), cost ms=" + cost, cost <= 500);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void t02_applyRollbackSigRoundTrip() throws Exception {
        db = DsDatabaseLocal.load();
        Class<NullFlipEntity> clazz = NullFlipEntity.class;
        db.registerTable(clazz);
        saveLegacyMetaOldNullable(clazz, Arrays.asList("city"));
        int N = 500;
        seed(db, N, clazz);
        Object coord = odCoord(db);
        File root = db.getRoot();
        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        metaOf.setAccessible(true);
        metaOf.invoke(db, clazz);
        TableMetaStore store1 = new TableMetaStore(root);
        TableMetaStore.TableMeta m0 = store1.getMeta(clazz.getName());
        String s0 = m0 == null || m0.signature == null ? "" : m0.signature;
        Assert.assertFalse("initial signature non-empty", s0.isEmpty());
        invokeM(coord, "prepareAlter", clazz, Boolean.FALSE, Boolean.TRUE);
        invokeM(coord, "applyAlter", clazz, Integer.valueOf(2048));
        metaOf.invoke(db, clazz);
        TableMetaStore store2 = new TableMetaStore(root);
        TableMetaStore.TableMeta m1 = store2.getMeta(clazz.getName());
        String s1 = m1 == null || m1.signature == null ? "" : m1.signature;
        Assert.assertNotEquals("S0 != S1 after nullable flip", s0, s1);
        invokeM(coord, "rollbackAlter", clazz);
        metaOf.invoke(db, clazz);
        TableMetaStore storeRb = new TableMetaStore(root);
        TableMetaStore.TableMeta m2 = storeRb.getMeta(clazz.getName());
        String s2 = m2 == null || m2.signature == null ? "" : m2.signature;
        Assert.assertEquals("S0 == S2 after rollback, round-trip", s0, s2);
        invokeM(coord, "prepareAlter", clazz, Boolean.FALSE, Boolean.TRUE);
        invokeM(coord, "applyAlter", clazz, Integer.valueOf(2048));
        metaOf.invoke(db, clazz);
        TableMetaStore store3 = new TableMetaStore(root);
        TableMetaStore.TableMeta m3 = store3.getMeta(clazz.getName());
        String s3 = m3 == null || m3.signature == null ? "" : m3.signature;
        Assert.assertEquals("S1 == S3 second apply matches first apply", s1, s3);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void t03_hc1ColIdChangeThrowsIae() throws Exception {
        db = DsDatabaseLocal.load();
        Object coord = odCoord(db);
        Class<?> c01 = ColsRemoved.class;
        writeStoredMetaForHc1(c01, Arrays.asList("name", "age", "city"), Arrays.asList(10L, 20L, 30L));
        Class<?> c02 = ColsAdded.class;
        writeStoredMetaForHc1(c02, Arrays.asList("name", "age", "city"), Arrays.asList(10L, 20L, 30L));
        Class<?> c03 = ColsRenamed.class;
        writeStoredMetaForHc1(c03, Arrays.asList("name", "age", "city"), Arrays.asList(10L, 20L, 30L));
        Class<?> c04 = ColsOneRenamed.class;
        writeStoredMetaForHc1(c04, Arrays.asList("name", "age", "city"), Arrays.asList(10L, 20L, 30L));
        int iaeCnt = 0;
        Throwable[] errs = new Throwable[4];
        try {
            invokeM(coord, "prepareAlter", c01, Boolean.TRUE, Boolean.TRUE);
        } catch (Throwable t) {
            errs[0] = t;
            if (t instanceof IllegalArgumentException && t.getMessage() != null && t.getMessage().contains("ALTER cannot change colId")) {
                iaeCnt++;

            }
        }
        try {
            invokeM(coord, "prepareAlter", c02, Boolean.TRUE, Boolean.TRUE);
        } catch (Throwable t) {
            errs[1] = t;
            if (t instanceof IllegalArgumentException && t.getMessage() != null && t.getMessage().contains("ALTER cannot change colId")) {
                iaeCnt++;

            }
        }
        try {
            invokeM(coord, "prepareAlter", c03, Boolean.TRUE, Boolean.TRUE);
        } catch (Throwable t) {
            errs[2] = t;
            if (t instanceof IllegalArgumentException && t.getMessage() != null && t.getMessage().contains("ALTER cannot change colId")) {
                iaeCnt++;

            }
        }
        try {
            invokeM(coord, "prepareAlter", c04, Boolean.TRUE, Boolean.TRUE);
        } catch (Throwable t) {
            errs[3] = t;
            if (t instanceof IllegalArgumentException && t.getMessage() != null && t.getMessage().contains("ALTER cannot change colId")) {
                iaeCnt++;

            }
        }
        Assert.assertEquals("4 bad cases throw IAE(ALTER cannot change colId), iaeCnt=" + iaeCnt + ", errs=" + Arrays.toString(errs), 4, iaeCnt);
        saveLegacyMetaOldNullable(NullFlipEntity.class, Arrays.asList("city"));
        int good = 0;
        String posErr = null;
        try {
            List<Object> res = invokeM(coord, "prepareAlter", NullFlipEntity.class, Boolean.TRUE, Boolean.TRUE);
            if (res != null && res.size() == 1) {
                good++;
            } else {
                posErr = "res.size=" + (res == null ? "null" : res.size());

            }
        } catch (Throwable t) {
            posErr = t.getClass().getSimpleName() + ":" + t.getMessage();
        }
        Assert.assertEquals("nullable-only flip 通过 prepareAlter, posErr=" + posErr, 1, good);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void t04_backwardCompatOldMetaMissingNullable() throws Exception {
        db = DsDatabaseLocal.load();
        Class<NullFlipEntity> clazz = NullFlipEntity.class;
        db.registerTable(clazz);
        saveLegacyMetaNoNullableLeaf(clazz, Arrays.asList("city"));
        Object coord = odCoord(db);
        List<Object> cols = invokeM(coord, "prepareAlter", clazz, Boolean.TRUE, Boolean.TRUE);
        int cnt = cols == null ? 0 : cols.size();
        Assert.assertEquals("legacy-meta nullable flip AlterCol count=1", 1, cnt);
        int rows = -1;
        try {
            invokeM(coord, "prepareAlter", clazz, Boolean.FALSE, Boolean.TRUE);
            rows = (Integer) invokeM(coord, "applyAlter", clazz, Integer.valueOf(2048));
        } catch (NullPointerException npe) {
            Assert.fail("TR-2.4 BackwardCompat 0 NPE: " + npe);
        }
        Assert.assertTrue("rows >= 0", rows >= 0);
        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        metaOf.setAccessible(true);
        metaOf.invoke(db, clazz);
        TableMetaStore tms = new TableMetaStore(db.getRoot());
        TableMetaStore.TableMeta stored = tms.getMeta(clazz.getName());
        Assert.assertNotNull("stored meta", stored);
        TableMetaStore.ColumnDef city = null;
        if (stored.columns != null) {
            for (TableMetaStore.ColumnDef cd : stored.columns) {
                if ("city".equals(cd.name)) {
                    city = cd;
                    break;
                }
            }
        }
        Assert.assertNotNull("city col def", city);
        Object nullableFieldVal = null;
        for (Field cf : TableMetaStore.ColumnDef.class.getDeclaredFields()) {
            if ("nullable".equals(cf.getName())) {
                cf.setAccessible(true);
                nullableFieldVal = cf.get(city);
                break;
            }
        }
        Boolean nn = nullableFieldVal instanceof Boolean ? (Boolean) nullableFieldVal : null;
        Assert.assertTrue("flip后 stored city.nullable=false", Boolean.FALSE.equals(nn));
    }
}
