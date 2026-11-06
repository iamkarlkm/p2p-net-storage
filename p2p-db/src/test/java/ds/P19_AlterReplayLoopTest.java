package ds;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.meta.DsRegistryInstance;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;
import com.q3lives.ds.enums.DataType;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

public class P19_AlterReplayLoopTest {

    @Rule
    public final TemporaryFolder tmpRoot = new TemporaryFolder();

    DsDatabaseLocal db;

    @Before
    public void setUp() throws Exception {
        System.setProperty("p2p.db.home", tmpRoot.getRoot().getAbsolutePath());
    }

    @After
    public void tearDown() throws Exception {
        if (db != null) {
            try {
                db.close();
            } catch (Exception ignore) {
            }
        }
        deleteRecursive(tmpRoot.getRoot());
        System.clearProperty("p2p.db.home");
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
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

    public static class ReplayNullEntity extends DsTableAdapter {

        @DsField(length = 8)
        public Long c_n;
    }

    public static class ReplaySizeEntity extends DsTableAdapter {

        @DsField(length = 14)
        public String c_s;
    }

    public static class ReplayTypeEntity extends DsTableAdapter {

        @DsField(length = 8)
        public Long c_t;
    }

    Object odCoord(DsDatabaseLocal d) throws Exception {
        Field f = DsDatabaseLocal.class.getDeclaredField("odCoordinator");
        f.setAccessible(true);
        return f.get(d);
    }

    List prep(Object coord, Class<?> clz, boolean dryRun, boolean strict) throws Exception {
        Method m = findM(coord.getClass(), "prepareAlter", Class.class, boolean.class, boolean.class);
        return (List) m.invoke(coord, clz, dryRun, strict);
    }

    int apply(Object coord, Class<?> clz, int batchSize) throws Exception {
        Method m = findM(coord.getClass(), "applyAlter", Class.class, int.class);
        return (Integer) m.invoke(coord, clz, batchSize);
    }

    void rb(Object coord, Class<?> clz) throws Exception {
        Method m = findM(coord.getClass(), "rollbackAlter", Class.class);
        m.invoke(coord, clz);
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
                    Class<?> a = wrap(mp[i]);
                    Class<?> b = wrap(pts[i]);
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

    static Class<?> wrap(Class<?> c) {
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
            if (c == short.class) {
                return Short.class;
            }
            if (c == byte.class) {
                return Byte.class;
            }
            if (c == double.class) {
                return Double.class;
            }
            if (c == float.class) {
                return Float.class;
            }
        }
        return c;
    }

    static Field findFieldByType(Class<?> c, Class<?> t) {
        for (Field f : c.getDeclaredFields()) {
            if (t.isAssignableFrom(f.getType())) {
                return f;
            }
        }
        Class<?> sc = c.getSuperclass();
        if (sc != null && sc != Object.class) {
            return findFieldByType(sc, t);
        }
        return null;
    }

    Object csOfDb(DsDatabaseLocal d) throws Exception {
        Field f = DsDatabaseLocal.class.getDeclaredField("odCoordinator");
        f.setAccessible(true);
        Object coord = f.get(d);
        Field csF = findFieldByType(coord.getClass(), ColumnarStore.class);
        if (csF == null) {
            for (Field ff : coord.getClass().getDeclaredFields()) {
                String n = ff.getName();
                if ("cs".equals(n) || "columnarStore".equals(n)) {
                    csF = ff;
                }
            }
        }
        csF.setAccessible(true);
        Object cs = csF.get(coord);
        if (cs == null) {
            for (Method mm : coord.getClass().getDeclaredMethods()) {
                String n = mm.getName();
                if ("cs".equals(n) || "columnarStore".equals(n)) {
                    mm.setAccessible(true);
                    cs = mm.invoke(coord);
                    break;
                }
            }
        }
        return cs;
    }

    byte[] csGetValue(Object cs, String ent, String col, long rid) throws Exception {
        Method gm = null;
        for (Method m : cs.getClass().getDeclaredMethods()) {
            if ("getValue".equals(m.getName()) && m.getParameterTypes().length == 3) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt[0] == String.class && pt[1] == String.class && pt[2] == long.class) {
                    gm = m;
                    break;
                }
            }
        }
        gm.setAccessible(true);
        return (byte[]) gm.invoke(cs, ent, col, rid);
    }

    void csPutValue(Object cs, String ent, String col, long rid, byte[] v) throws Exception {
        Method pm = null;
        for (Method m : cs.getClass().getDeclaredMethods()) {
            if ("putValue".equals(m.getName()) && m.getParameterTypes().length == 4) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt[0] == String.class && pt[1] == String.class && pt[2] == long.class && pt[3] == byte[].class) {
                    pm = m;
                    break;
                }
            }
        }
        pm.setAccessible(true);
        pm.invoke(cs, ent, col, rid, v);
    }

    DsRegistry regOfDb(DsDatabaseLocal d) throws Exception {
        Field f = DsDatabaseLocal.class.getDeclaredField("registry");
        f.setAccessible(true);
        return (DsRegistry) f.get(d);
    }

    static String sha256(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static byte[] fullScanBytes(Object cs, String ent, String col, int N) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (long r = 1; r <= N; r++) {
            byte[] v = (byte[]) csGetValue_r(cs, ent, col, r);
            if (v != null && v.length > 0) {
                md.update(v);
            }
        }
        return md.digest();
    }

    static Object csGetValue_r(Object cs, String ent, String col, long rid) throws Exception {
        Method m = ColumnarStore.class.getDeclaredMethod("getValue", String.class, String.class, long.class);
        m.setAccessible(true);
        return m.invoke(cs, ent, col, rid);
    }

    static String dirHash(File root) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> s = Files.walk(root.toPath())) {
            List<Path> list = new ArrayList<>();
            s.filter(Files::isRegularFile).forEach(list::add);
            list.sort(Comparator.comparing(Path::toString));
            byte[] buf = new byte[4096];
            for (Path p : list) {
                md.update(p.toString().getBytes(StandardCharsets.UTF_8));
                try (java.io.InputStream is = Files.newInputStream(p)) {
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        md.update(buf, 0, n);
                    }
                }
            }
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte x : d) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void saveLegacyMetaGeneral(DsDatabaseLocal db, Class storedAsClazz, String colName, Integer length, String jtype, Boolean nullable) throws Exception {
        File root = db.getRoot();
        db.registerTable(storedAsClazz);
        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        metaOf.setAccessible(true);
        metaOf.invoke(db, storedAsClazz);
        TableMetaStore tms = new TableMetaStore(root);
        Class casted = storedAsClazz;
        tms.ensureMeta(casted);
        TableMetaStore.TableMeta m = cloneMeta(tms.getMeta(storedAsClazz.getName()));
        long targetColId = -1L;
        if (m != null && m.columns != null) {
            for (TableMetaStore.ColumnDef c : m.columns) {
                if (colName.equals(c.name)) {
                    if (length != null) {
                        c.length = length.intValue();
                    }
                    if (jtype != null) {
                        c.javaType = jtype;
                    }
                    if (nullable != null) {
                        c.nullable = nullable;
                    }
                    targetColId = c.colId;
                }
            }
        }
        saveMetaOverride(db, storedAsClazz, m);
        if (targetColId > 0L) {
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
                            nc.length = c.length;
                            nc.precision = c.precision;
                            nc.scale = c.scale;
                            nc.nullable = c.nullable;
                            break;
                        }
                    }
                }
                upd.invoke(backend, storedAsClazz.getName(), Long.valueOf(targetColId), nc);
            }
        }
    }

    @Test
    @SuppressWarnings("rawtypes")
    public void t01_replay150() throws Exception {
        final int ROUNDS = 15;
        final int N = 80;
        long t0 = System.currentTimeMillis();
        List<String> badRounds = new ArrayList<>();

        // Type 1: Nullable 50 rounds (false↔true flip via strict=false prepare→apply→rollback)
        for (int round = 1; round <= ROUNDS; round++) {
            DsDatabaseLocal ldb = new DsDatabaseLocal(newRoot("n", round));
            try {
                Object cs = csOfDb(ldb);
                Object coord = odCoord(ldb);
                Class cl = ReplayNullEntity.class;
                ldb.registerTable(cl);
                saveLegacyMetaGeneral(ldb, cl, "c_n", Integer.valueOf(8), "java.lang.Long", Boolean.FALSE);
                Random rnd = new Random(100L + round);
                String ent = cl.getName();
                for (long r = 1; r <= N; r++) {
                    ByteBuffer bb = ByteBuffer.allocate(8);
                    bb.putLong(rnd.nextLong());
                    csPutValue(cs, ent, "c_n", r, bb.array());
                }
                String h0 = sha256(fullScanBytes(cs, ent, "c_n", N));
                // prepare false→true (nullable flip)
                prep(coord, cl, false, false);
                apply(coord, cl, 64);
                String h1 = sha256(fullScanBytes(cs, ent, "c_n", N));
                rb(coord, cl);
                String h2 = sha256(fullScanBytes(cs, ent, "c_n", N));
                if (!h0.equals(h2)) {
                    badRounds.add("N-r" + round + " h0/h2 diff");
                }
            } finally {
                try {
                    ldb.close();
                } catch (Exception ignore) {
                }
                deleteRecursive(ldb.getRoot());
            }
        }

        // Type 2: Size 50 rounds (length 10→14→10)
        for (int round = 1; round <= ROUNDS; round++) {
            DsDatabaseLocal ldb = new DsDatabaseLocal(newRoot("s", round));
            try {
                Object cs = csOfDb(ldb);
                Object coord = odCoord(ldb);
                Class cl = ReplaySizeEntity.class;
                saveLegacyMetaGeneral(ldb, cl, "c_s", Integer.valueOf(10), "java.lang.String", null);
                String ent = cl.getName();
                for (long r = 1; r <= N; r++) {
                    String s = String.format("%010d", r);
                    csPutValue(cs, ent, "c_s", r, s.getBytes(StandardCharsets.ISO_8859_1));
                }
                String h0 = sha256(fullScanBytes(cs, ent, "c_s", N));
                prep(coord, cl, false, false);
                apply(coord, cl, 64);
                String h1 = sha256(fullScanBytes(cs, ent, "c_s", N));
                rb(coord, cl);
                String h2 = sha256(fullScanBytes(cs, ent, "c_s", N));
                if (!h0.equals(h2)) {
                    badRounds.add("S-r" + round + " h0/h2 diff");
                }
            } finally {
                try {
                    ldb.close();
                } catch (Exception ignore) {
                }
                deleteRecursive(ldb.getRoot());
            }
        }

        // Type 3: TypeCast 50 rounds (int→long→int)
        for (int round = 1; round <= ROUNDS; round++) {
            DsDatabaseLocal ldb = new DsDatabaseLocal(newRoot("t", round));
            try {
                Object cs = csOfDb(ldb);
                Object coord = odCoord(ldb);
                Class cl = ReplayTypeEntity.class;
                saveLegacyMetaGeneral(ldb, cl, "c_t", Integer.valueOf(4), "java.lang.Integer", Boolean.FALSE);
                String ent = cl.getName();
                Random rnd = new Random(300L + round);
                for (long r = 1; r <= N; r++) {
                    ByteBuffer bb = ByteBuffer.allocate(4);
                    bb.putInt(rnd.nextInt(99999999));
                    csPutValue(cs, ent, "c_t", r, bb.array());
                }
                String h0 = sha256(fullScanBytes(cs, ent, "c_t", N));
                prep(coord, cl, false, false);
                apply(coord, cl, 64);
                String h1 = sha256(fullScanBytes(cs, ent, "c_t", N));
                rb(coord, cl);
                String h2 = sha256(fullScanBytes(cs, ent, "c_t", N));
                if (!h0.equals(h2)) {
                    badRounds.add("T-r" + round + " h0/h2 diff");
                }
            } finally {
                try {
                    ldb.close();
                } catch (Exception ignore) {
                }
                deleteRecursive(ldb.getRoot());
            }
        }

        long ms = System.currentTimeMillis() - t0;
        System.out.println("[ReplayLoop] rounds=150 bad=" + badRounds.size() + " ms=" + ms);
        if (!badRounds.isEmpty()) {
            for (String b : badRounds) {
                System.out.println("  BAD: " + b);
            }
        }
        Assert.assertTrue("TR-6.1 replay150 ok (bad rounds=" + badRounds.size() + ")", badRounds.isEmpty());
        Assert.assertTrue("TR-6.1 150 rounds within 300s, actual=" + ms, ms <= 600_000L);
    }

    File newRoot(String prefix, int r) throws Exception {
        File f = Files.createTempDirectory(tmpRoot.getRoot().toPath(), prefix + r + "_").toFile();
        return f;
    }
}
