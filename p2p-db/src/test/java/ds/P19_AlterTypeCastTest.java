package ds;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
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
import java.security.MessageDigest;
import java.util.*;

public class P19_AlterTypeCastTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    DsDatabaseLocal db;
    File tmpRoot;
    String oldHome;

    public static final class TypeInt4Entity extends DsTableAdapter {

        @DsField(length = 4)
        public int payload;
    }

    public static final class TypeLong8Entity extends DsTableAdapter {

        @DsField(length = 8)
        public long payload;
    }

    @Before
    public void setUp() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p19_tc_").toFile();
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (db != null) {
                db.close();

            }
        } catch (Exception ignored) {
        }
        deleteRecursive(tmpRoot);
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
            File[] arr = f.listFiles();
            if (arr != null) {
                for (File c : arr) {
                    deleteRecursive(c);
                }
            }
        }
        try {
            f.delete();
        } catch (Exception ignored) {
        }
    }

    static Object odCoord(DsDatabaseLocal db) throws Exception {
        Field f = DsDatabaseLocal.class.getDeclaredField("odCoordinator");
        f.setAccessible(true);
        return f.get(db);
    }

    static Object invokeM(Object target, String mName, Object... args) throws Exception {
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
            } else {
                pts[i] = args[i].getClass();
            }
        }
        Method m = findM(target.getClass(), mName, pts);
        if (m == null) {
            throw new NoSuchMethodException(mName);
        }
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    static Method findM(Class<?> c, String name, Class<?>[] pts) {
        best:
        for (Method m : c.getDeclaredMethods()) {
            if (!name.equals(m.getName())) {
                continue;
            }
            Class<?>[] mp = m.getParameterTypes();
            if (mp.length != pts.length) {
                continue;
            }
            for (int i = 0; i < mp.length; i++) {
                if (pts[i] == Object.class) {
                    continue;
                }
                if (mp[i] == pts[i]) {
                    continue;
                }
                if (mp[i] == boolean.class && pts[i] == Boolean.class) {
                    continue;
                }
                if (mp[i] == int.class && pts[i] == Integer.class) {
                    continue;
                }
                if (mp[i] == long.class && pts[i] == Long.class) {
                    continue;
                }
                continue best;
            }
            return m;
        }
        return null;
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

    static Object csOfDb(DsDatabaseLocal db) throws Exception {
        Field f = DsDatabaseLocal.class.getDeclaredField("odCoordinator");
        f.setAccessible(true);
        Object coord = f.get(db);
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

    static byte[] csGetValue(Object cs, String ent, String col, long rid) throws Exception {
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

    static void csPutValue(Object cs, String ent, String col, long rid, byte[] b) throws Exception {
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
        pm.invoke(cs, ent, col, rid, b);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static List<Object> prep(Object coord, Class cl, boolean dry, boolean strict) throws Exception {
        return (List) invokeM(coord, "prepareAlter", cl, dry, strict);
    }

    static int apply(Object coord, Class cl, int batch) throws Exception {
        Object r = invokeM(coord, "applyAlter", cl, batch);
        return ((Number) r).intValue();
    }

    static void rollback(Object coord, Class cl) throws Exception {
        invokeM(coord, "rollbackAlter", cl);
    }

    static Field getF(Class c, String n) throws Exception {
        Field f = c.getDeclaredField(n);
        f.setAccessible(true);
        return f;
    }

    static long fiLong(Object o, String n) {
        try {
            return getF(o.getClass(), n).getLong(o);
        } catch (Exception e) {
            return -1L;
        }
    }

    static String fiStr(Object o, String n) {
        try {
            Object v = getF(o.getClass(), n).get(o);
            return v == null ? "" : v.toString();
        } catch (Exception e) {
            return "ex";
        }
    }

    static int fiInt(Object o, String n) {
        try {
            return getF(o.getClass(), n).getInt(o);
        } catch (Exception e) {
            return -1;
        }
    }

    static String dumpCols(List<Object> acs) throws Exception {
        if (acs == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (Object co : acs) {
            sb.append("colId=").append(fiLong(co, "colId")).append(" cMode=").append(fiStr(co, "cMode"))
                    .append(" oLen=").append(fiInt(co, "oLen")).append(" nLen=").append(fiInt(co, "nLen")).append(" oJT=").append(fiStr(co, "oJType")).append(" nJT=").append(fiStr(co, "nJType")).append("; ");
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
    void saveLegacyMetaOverType(Class storedAsClazz, String colName, int legacyLen, String legacyJType) throws Exception {
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
                    c.length = legacyLen;
                    c.javaType = legacyJType;
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
                            nc.javaType = legacyJType;
                            nc.length = legacyLen;
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
        TableMetaStore tmsPost = new TableMetaStore(root);
        TableMetaStore.TableMeta post = tmsPost.getMeta(storedAsClazz.getName());
        String postLen = "null", postType = "null";
        if (post != null && post.columns != null) {
            for (TableMetaStore.ColumnDef c : post.columns) {
                if (colName.equals(c.name)) {
                    postLen = String.valueOf(c.length);
                    postType = c.javaType == null ? "null" : c.javaType;
                }
            }
        }
        System.out.println("[Type.saveLegacyOverride] post-check " + storedAsClazz.getSimpleName() + "." + colName + " length=" + postLen + " (target=" + legacyLen + ") type=" + postType + " (target=" + legacyJType + ")");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    void seedInt(Class storedAsClazz, int N) throws Exception {
        Object cs = csOfDb(this.db);
        String ent = storedAsClazz.getName();
        Random rnd = new Random(42L);
        for (int i = 0; i < N; i++) {
            long rid = i + 1L;
            int v;
            if (i == 0) {
                v = Integer.MIN_VALUE;
            } else if (i == 1) {
                v = Integer.MAX_VALUE;
            } else if (i == 2) {
                v = -1;
            } else if (i == 3) {
                v = 0;
            } else {
                v = rnd.nextInt();
            }
            ByteBuffer bb = ByteBuffer.allocate(4);
            bb.putInt(v);
            byte[] b = bb.array();
            csPutValue(cs, ent, "payload", rid, b);
        }
        System.out.println("[Type.seedInt] N=" + N);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    int verifyIntEqualsLong(Class storedAsClazz, int N) throws Exception {
        Object cs = csOfDb(this.db);
        String ent = storedAsClazz.getName();
        Random rnd = new Random(42L);
        int wrong = 0;
        for (int i = 0; i < N; i++) {
            long rid = i + 1L;
            int expV;
            if (i == 0) {
                expV = Integer.MIN_VALUE;
            } else if (i == 1) {
                expV = Integer.MAX_VALUE;
            } else if (i == 2) {
                expV = -1;
            } else if (i == 3) {
                expV = 0;
            } else {
                expV = rnd.nextInt();
            }
            byte[] b = csGetValue(cs, ent, "payload", rid);
            if (b == null || b.length < 8) {
                if (wrong<5) System.out.println("[t01.bad] rid=" + rid + " " + (b==null?"null":"len="+b.length));
                wrong++;
                continue;
            }
            ByteBuffer bb = ByteBuffer.wrap(b);
            long actual = bb.getLong(0);
            if (actual != (long) expV) {
                if (wrong < 5) {
                    StringBuilder sb = new StringBuilder("[t01.bad] rid=" + rid + " exp=" + expV + " (0x" + Integer.toHexString(expV) + ") actual=" + actual + " (0x" + Long.toHexString(actual) + ") bytes=");
                    for (int k = 0; k < b.length && k < 16; k++) sb.append(String.format("%02x", b[k]));
                    System.out.println(sb);
                }
                wrong++;
            }
        }
        return wrong;
    }

    static byte[] fullScanBytes(DsDatabaseLocal db, Class cl, String col, int N, int len) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        Object cs = csOfDb(db);
        String ent = cl.getName();
        for (long rid = 1; rid <= N; rid++) {
            byte[] b = csGetValue(cs, ent, col, rid);
            if (b == null) {
                throw new RuntimeException("null rid=" + rid);
            }
            if (b.length != len) {
                byte[] tmp = new byte[len];
                for (int i = 0; i < Math.min(len, b.length); i++) {
                    tmp[i] = b[i];

                }
                b = tmp;
            }
            md.update(b);
        }
        return md.digest();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.length() > 32 ? sb.substring(0, 32) : sb.toString();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void t01_intToLongMathEqual() throws Exception {
        db = DsDatabaseLocal.load();
        Class storedAs = TypeLong8Entity.class;
        saveLegacyMetaOverType(storedAs, "payload", 4, "java.lang.Integer");
        int N = 2000;
        seedInt(storedAs, N);
        Object coord = odCoord(db);
        List<Object> acs = prep(coord, storedAs, false, true);
        int cnt = (acs == null) ? 0 : acs.size();
        System.out.println("[Type.t01] cnt=" + cnt + " acs=" + dumpCols(acs));
        Assert.assertTrue("TR-4.1 prepare count>=1: " + cnt, cnt >= 1);
        int rows = apply(coord, storedAs, 128);
        System.out.println("[Type.t01] apply rows=" + rows);
        int wrong = verifyIntEqualsLong(storedAs, N);
        System.out.println("[Type.t01] wrong=" + wrong);
        Assert.assertEquals("TR-4.1 math equal 0 wrong", 0, wrong);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void t02_narrowingStrictIaeStrictFalseOk() throws Exception {
        db = DsDatabaseLocal.load();
        Class storedAs = TypeInt4Entity.class;
        saveLegacyMetaOverType(storedAs, "payload", 8, "java.lang.Long");
        Object coord = odCoord(db);
        int iaeCnt = 0;
        try {
            prep(coord, storedAs, false, true);
            Assert.fail("expected IAE strict=true narrowing");
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof IllegalArgumentException && cause.getMessage() != null && cause.getMessage().contains("narrowing")) {
                iaeCnt++;
            }
        } catch (IllegalArgumentException iae) {
            if (iae.getMessage() != null && iae.getMessage().contains("narrowing")) {
                iaeCnt++;
            }
        }
        Assert.assertEquals("TR-4.2 long->int strict IAE count=1: " + iaeCnt, 1, iaeCnt);
        List<Object> cols = prep(coord, storedAs, false, false);
        int cnt = cols == null ? 0 : cols.size();
        System.out.println("[Type.t02] strict=false prep cnt=" + cnt);
        Assert.assertTrue("TR-4.2 strict=false ok cnt>=1: " + cnt, cnt >= 1);
        int rows = apply(coord, storedAs, 256);
        System.out.println("[Type.t02] apply rows=" + rows);
        Assert.assertTrue("TR-4.2 strict=false apply rows>=0: " + rows, rows >= 0);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void t03_applyRollbackApplyRoundTrip() throws Exception {
        db = DsDatabaseLocal.load();
        Class storedAs = TypeLong8Entity.class;
        saveLegacyMetaOverType(storedAs, "payload", 4, "java.lang.Integer");
        int N = 500;
        seedInt(storedAs, N);
        byte[] H0 = fullScanBytes(db, storedAs, "payload", N, 4);
        System.out.println("[Type.t03] H0=" + hex(H0));
        Object c1 = odCoord(db);
        prep(c1, storedAs, false, true);
        apply(c1, storedAs, 64);
        byte[] H1 = fullScanBytes(db, storedAs, "payload", N, 8);
        System.out.println("[Type.t03] H1=" + hex(H1));
        Object c2 = odCoord(db);
        rollback(c2, storedAs);
        byte[] H2 = fullScanBytes(db, storedAs, "payload", N, 4);
        System.out.println("[Type.t03] H2=" + hex(H2));
        Object c3 = odCoord(db);
        prep(c3, storedAs, false, true);
        apply(c3, storedAs, 64);
        byte[] H3 = fullScanBytes(db, storedAs, "payload", N, 8);
        System.out.println("[Type.t03] H3=" + hex(H3));
        Assert.assertArrayEquals("TR-4.3 rollback restore H0=H2", H0, H2);
        Assert.assertArrayEquals("TR-4.3 double-apply H1=H3", H1, H3);
    }
}
