package ds;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.ColumnRegistry;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@NotThreadSafe
public class P19_AlterSizeTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    public static final class SizeLen10Entity extends DsTableAdapter {
        @DsField(name = "payload", length = 10, nullable = false)
        public String payload;
    }

    public static final class SizeLen12Entity extends DsTableAdapter {
        @DsField(name = "payload", length = 12, nullable = false)
        public String payload;
    }

    public static final class SizeNarrow10Entity extends DsTableAdapter {
        @DsField(name = "payload", length = 10, nullable = false)
        public String payload;
    }

    public static final class SizeNarrow8Entity extends DsTableAdapter {
        @DsField(name = "payload", length = 8, nullable = false)
        public String payload;
    }

    private DsDatabaseLocal db;

    @Before
    public void setUp() throws IOException {
        File root = tmp.newFolder("dsdb-size");
        System.setProperty("dsdb.root", root.getAbsolutePath());
    }

    @After
    public void tearDown() {
        System.clearProperty("dsdb.root");
        if (db != null) try { db.close(); } catch (Exception ignore) {}
    }

    static Object odCoord(DsDatabaseLocal db) throws Exception { return odCoord0(db); }
    static Object odCoord0(DsDatabaseLocal db) throws Exception {
        Field f = DsDatabaseLocal.class.getDeclaredField("odCoordinator");
        f.setAccessible(true);
        return f.get(db);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    static List<Object> prep(Object coord, Class clz, boolean dryRun, boolean strict) throws Exception {
        return (List<Object>) invokeM(coord, "prepareAlter", clz, dryRun, strict);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    static int apply(Object coord, Class clz, int batchSize) throws Exception {
        return (Integer) invokeM(coord, "applyAlter", clz, batchSize);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    static void roll(Object coord, Class clz) throws Exception {
        invokeM(coord, "rollbackAlter", clz);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    static Object invokeM(Object target, String name, Object... args) throws Exception {
        Class<?>[] types = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Boolean) types[i] = boolean.class;
            else if (a instanceof Integer) types[i] = int.class;
            else if (a instanceof Long) types[i] = int.class;
            else if (a instanceof Class) types[i] = Class.class;
            else types[i] = a == null ? Object.class : a.getClass();
        }
        Method m = findM(target.getClass(), name, types);
        if (m == null) throw new NoSuchMethodException(name + " " + Arrays.toString(types));
        m.setAccessible(true);
        try { return m.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable t = ite.getTargetException();
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            if (t instanceof Error) throw (Error) t;
            throw new RuntimeException(t);
        }
    }

    static Method findM(Class<?> cl, String name, Class<?>[] types) {
        for (Method ms : cl.getDeclaredMethods()) {
            if (!ms.getName().equals(name)) continue;
            Class<?>[] pts = ms.getParameterTypes();
            if (pts.length != types.length) continue;
            boolean ok = true;
            for (int j = 0; j < pts.length; j++) {
                Class<?> pt = pts[j], at = types[j];
                if (pt.isAssignableFrom(at)) continue;
                if (pt.isPrimitive()) {
                    if (pt == boolean.class && at == Boolean.class) continue;
                    if (pt == int.class && (at == Integer.class || at == Long.class)) continue;
                    if (pt == long.class && at == Long.class) continue;
                }
                ok = false; break;
            }
            if (ok) return ms;
        }
        return null;
    }

    static Field findFieldByName(Class<?> c, String... names) { for (Field ff : c.getDeclaredFields()) for (String n : names) if (n.equals(ff.getName())) return ff; return null; }
    static Field findFieldByType(Class<?> c, Class<?> t) { for (Field ff : c.getDeclaredFields()) if (t.isAssignableFrom(ff.getType())) return ff; return null; }
    static Method findMethodByName(Class<?> c, String... names) { for (Method mm : c.getDeclaredMethods()) for (String n : names) if (n.equals(mm.getName())) return mm; return null; }
    static Method findGv2(Class<?> c, String name, Class<?>... typesArr) {
        for (Method mm : c.getMethods()) {
            if (!name.equals(mm.getName())) continue;
            Class<?>[] pts = mm.getParameterTypes();
            if (pts.length != typesArr.length) continue;
            boolean ok = true;
            for (int k = 0; k < pts.length; k++) {
                Class<?> pt = pts[k], at = typesArr[k];
                if (pt.isAssignableFrom(at)) continue;
                if (pt.isPrimitive()) {
                    if (pt == boolean.class && at == Boolean.class) continue;
                    if (pt == int.class && (at == Integer.class || at == Long.class)) continue;
                    if (pt == long.class && at == Long.class) continue;
                }
                ok = false; break;
            }
            if (ok) return mm;
        }
        for (Method mm : c.getDeclaredMethods()) {
            if (!name.equals(mm.getName())) continue;
            Class<?>[] pts = mm.getParameterTypes();
            if (pts.length != typesArr.length) continue;
            boolean ok = true;
            for (int k = 0; k < pts.length; k++) {
                Class<?> pt = pts[k], at = typesArr[k];
                if (pt.isAssignableFrom(at)) continue;
                if (pt.isPrimitive()) {
                    if (pt == boolean.class && at == Boolean.class) continue;
                    if (pt == int.class && (at == Integer.class || at == Long.class)) continue;
                    if (pt == long.class && at == Long.class) continue;
                }
                ok = false; break;
            }
            if (ok) return mm;
        }
        return null;
    }
    static Method findGv(Class<?> c) { return findGv2(c,"getValue",String.class,String.class,long.class); }

    @SuppressWarnings({"rawtypes","unchecked"})
    static void seed(DsDatabaseLocal db, int N, Class storedAsClazz, int len) throws Exception {
        Object coord = odCoord0(db);
        Class<?> coordCls = coord.getClass();
        Field csF = findFieldByType(coordCls, ColumnarStore.class);
        if (csF == null) csF = findFieldByName(coordCls,"cs","columnarStore");
        csF.setAccessible(true);
        Object csObj = csF.get(coord);
        if (csObj == null) {
            Method csM = findMethodByName(coordCls,"cs","columnarStore");
            csM.setAccessible(true);
            csObj = csM.invoke(coord);
        }
        Method putV = findGv2(csObj.getClass(),"putValue",String.class,String.class,long.class,byte[].class);
        putV.setAccessible(true);
        java.nio.charset.Charset UTF8 = java.nio.charset.StandardCharsets.UTF_8;
        String ent = storedAsClazz.getName();
        for (int i = 0; i < N; i++) {
            long rid = i + 1L;
            String base = "r" + String.format("%08d", i);
            StringBuilder sb = new StringBuilder();
            while (sb.length() < len) sb.append(base);
            String s = sb.substring(0, len);
            byte[] rawB = s.getBytes(UTF8);
            byte[] b = Arrays.copyOf(rawB, len);
            try { putV.invoke(csObj, ent, "payload", rid, b); }
            catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable t = ite.getTargetException();
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                throw new RuntimeException(t);
            }
        }
    }

    static long hashDir(Path p) throws Exception {
        final long[] h = {0};
        Files.walk(p).filter(Files::isRegularFile).sorted().forEachOrdered(f -> {
            try {
                byte[] b = Files.readAllBytes(f);
                h[0] = h[0] * 31 + Arrays.hashCode(b);
                h[0] = h[0] * 31 + p.relativize(f).toString().hashCode();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        return h[0];
    }

    static TableMetaStore.TableMeta cloneMeta(TableMetaStore.TableMeta src) {
        TableMetaStore.TableMeta dst = new TableMetaStore.TableMeta();
        dst.entityClassName = src.entityClassName;
        dst.signature = src.signature;
        List<TableMetaStore.ColumnDef> cds = new ArrayList<>();
        if (src.columns != null) {
            for (TableMetaStore.ColumnDef c : src.columns) {
                TableMetaStore.ColumnDef d = new TableMetaStore.ColumnDef();
                d.colId = c.colId; d.colKey = c.colKey; d.name = c.name; d.javaType = c.javaType;
                d.length = c.length; d.precision = c.precision; d.scale = c.scale; d.nullable = c.nullable;
                cds.add(d);
            }
        }
        dst.columns = cds;
        dst.compositeGroups = src.compositeGroups == null ? new LinkedHashMap<>() : new LinkedHashMap<>(src.compositeGroups);
        return dst;
    }

    static void saveMetaOverride(DsDatabaseLocal db, Class<?> clazz, TableMetaStore.TableMeta m) throws Exception {
        if (m.entityClassName == null || m.entityClassName.isEmpty()) m.entityClassName = clazz.getName();
        if (m.compositeGroups == null) m.compositeGroups = new LinkedHashMap<>();
        if (m.columns != null) {
            for (TableMetaStore.ColumnDef cd : m.columns) {
                if (cd.colKey == null || cd.colKey.isEmpty()) cd.colKey = m.entityClassName + "#" + cd.name;
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
        for (Class<?> ic : TableMetaStore.class.getDeclaredClasses()) if ("Backend".equals(ic.getSimpleName())) { bc = ic; break; }
        Method save = bc.getDeclaredMethod("saveToRegistry", String.class, TableMetaStore.TableMeta.class);
        save.setAccessible(true);
        save.invoke(backend, clazz.getName(), m);
        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        metaOf.setAccessible(true);
        metaOf.invoke(db, clazz);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    void saveLegacyMetaOverLength(Class storedAsClazz, String colName, int length) throws Exception {
        File root = db.getRoot();
        Class casted = storedAsClazz;
        db.registerTable(storedAsClazz);
        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        metaOf.setAccessible(true);
        metaOf.invoke(db, storedAsClazz);
        TableMetaStore tms = new TableMetaStore(root);
        tms.ensureMeta(casted);
        TableMetaStore.TableMeta m = cloneMeta(tms.getMeta(storedAsClazz.getName()));
        if (m != null && m.columns != null) {
            for (TableMetaStore.ColumnDef c : m.columns) {
                if (colName.equals(c.name)) c.length = length;
            }
        }
        saveMetaOverride(db, storedAsClazz, m);
        TableMetaStore tms2 = new TableMetaStore(root);
        TableMetaStore.TableMeta cm = tms2.getMeta(storedAsClazz.getName());
        String cl = "null";
        if (cm != null && cm.columns != null) for (TableMetaStore.ColumnDef cc : cm.columns) if (colName.equals(cc.name)) { cl = String.valueOf(cc.length); break; }
        System.out.println("[Size.saveLegacyOverride] post-check " + storedAsClazz.getSimpleName() + "." + colName + ".length=" + cl + " (target=" + length + ")");
    }

    static Field getF(Class c, String n) throws Exception { Field f = c.getDeclaredField(n); f.setAccessible(true); return f; }
    static long fiLong(Object o, String n) { try { return getF(o.getClass(),n).getLong(o); } catch (Exception e) { return -1L; } }
    static String fiStr(Object o, String n) { try { Object v = getF(o.getClass(),n).get(o); return v==null?"":v.toString(); } catch (Exception e) { return "ex"; } }
    static int fiInt(Object o, String n) { try { return getF(o.getClass(),n).getInt(o); } catch (Exception e) { return -1; } }

    static String dumpCols(List<Object> acs) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (acs == null) return "null";
        for (Object co : acs) {
            long ci = fiLong(co,"colId");
            String cm = fiStr(co,"cMode");
            int ol = fiInt(co,"oLen");
            int nl = fiInt(co,"nLen");
            sb.append("colId=").append(ci).append(" cMode=").append(cm)
              .append(" oLen=").append(ol).append(" nLen=").append(nl).append("; ");
        }
        return sb.toString();
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    static String metaLen(Class cl, File root, String col) {
        TableMetaStore store = new TableMetaStore(root);
        TableMetaStore.TableMeta m = store.getMeta(cl.getName());
        if (m == null || m.columns == null) return "null";
        for (TableMetaStore.ColumnDef c : m.columns) if (col.equals(c.name)) return String.valueOf(c.length);
        return "no-col";
    }

    static String regLen(Class<?> cl, File root, String col) {
        try {
            Object reg = Class.forName("com.q3lives.ds.registry.meta.DsRegistryInstance").getMethod("get", File.class).invoke(null, root);
            String leaf = RegistryMetaPaths.columnTableColDir(cl.getName(), col) + "/length";
            Object e = reg.getClass().getMethod("get", String.class).invoke(reg, leaf);
            if (e == null) return "null";
            Object asStr = e.getClass().getMethod("asString").invoke(e);
            return asStr == null ? "null" : asStr.toString();
        } catch (Exception ex) { return "ex:" + ex.getMessage(); }
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    static byte[] csVal(Class cl, File root, String col, long rid, int[] lenOut) {
        ColumnarStore cs = new ColumnarStore(root);
        try {
            byte[] b = cs.getValue(cl.getName(), col, rid);
            if (lenOut != null) lenOut[0] = b == null ? -1 : b.length;
            return b;
        } catch (Exception ignore) {
            if (lenOut != null) lenOut[0] = -2;
            return null;
        } finally {
            try { cs.close(); } catch (Exception ignore) {}
        }
    }

    Object csOfDb() throws Exception {
        Object coord = odCoord(db);
        Class<?> coordCls = coord.getClass();
        Field f = findFieldByType(coordCls, ColumnarStore.class);
        if (f == null) f = findFieldByName(coordCls,"cs","columnarStore");
        if (f == null) throw new RuntimeException("coord.cs not found");
        f.setAccessible(true);
        Object cur = f.get(coord);
        if (cur == null) {
            Method m = findMethodByName(coordCls,"cs","columnarStore");
            if (m == null) throw new RuntimeException("coord.cs() not found");
            m.setAccessible(true);
            cur = m.invoke(coord);
        }
        return cur;
    }

    static byte[] csGetValue(Object cs, String ent, String col, long rid) throws Exception {
        Method gv = findGv(cs.getClass());
        gv.setAccessible(true);
        try { return (byte[]) gv.invoke(cs, ent, col, Long.valueOf(rid)); }
        catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable t = ite.getTargetException();
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            if (t instanceof IOException) throw (IOException) t;
            throw new RuntimeException(t);
        }
    }

    @Test
    @SuppressWarnings({"rawtypes","unchecked"})
    public void t01_dryRunZeroWriteDirHashEqual() throws Exception {
        db = DsDatabaseLocal.load();
        Class clazzNew = SizeLen12Entity.class;
        saveLegacyMetaOverLength(clazzNew, "payload", 10);
        int N = 200;
        seed(db, N, clazzNew, 8);
        Object coord = odCoord(db);
        File root = db.getRoot();
        long h0 = hashDir(root.toPath());
        List<Object> cols = prep(coord, clazzNew, true, true);
        int cnt = cols == null ? 0 : cols.size();
        System.out.println("[Size.t01] cnt=" + cnt + " acs=" + dumpCols(cols));
        Assert.assertTrue("dry-run AlterCol count >= 1 got cnt=" + cnt, cnt >= 1);
        long h1 = hashDir(root.toPath());
        System.out.println("[Size.t01] H0=" + h0 + " H1=" + h1 + " diff=" + (h1-h0));
        Assert.assertEquals("TR-3.1 dry-run zero-write dirHash equal", h0, h1);
    }

    @Test
    @SuppressWarnings({"rawtypes","unchecked"})
    public void t02_narrowingStrictTrueIaeStrictFalseOk() throws Exception {
        db = DsDatabaseLocal.load();
        Class clazzNew = SizeNarrow8Entity.class;
        saveLegacyMetaOverLength(clazzNew, "payload", 10);
        int N = 100;
        seed(db, N, clazzNew, 8);
        Object coord = odCoord(db);
        int iaeCnt = 0;
        try {
            List<Object> cols = prep(coord, clazzNew, false, true);
            Assert.fail("expected IAE strict=true narrowing, got cols=" + (cols == null ? 0 : cols.size()) + " acs=" + dumpCols(cols));
        } catch (IllegalArgumentException iae) {
            iaeCnt++;
            Assert.assertTrue("msg contains narrowing: " + iae.getMessage(),
                iae.getMessage() != null && iae.getMessage().contains("narrowing"));
        }
        Assert.assertEquals("TR-3.2 narrowing strict=true throws IAE (count=1)", 1, iaeCnt);
        List<Object> cols = prep(coord, clazzNew, false, false);
        int cnt = cols == null ? 0 : cols.size();
        Assert.assertTrue("strict=false prepare ok, count=1: " + cnt, cnt >= 1);
        int rows = apply(coord, clazzNew, 128);
        Assert.assertTrue("strict=false apply ok rows>=0: " + rows, rows >= 0);
    }

    @Test
    @SuppressWarnings({"rawtypes","unchecked"})
    public void t03_checkpointResumeBatch32() throws Exception {
        db = DsDatabaseLocal.load();
        Class clazzNew = SizeLen12Entity.class;
        saveLegacyMetaOverLength(clazzNew, "payload", 10);
        int N = 2000;
        seed(db, N, clazzNew, 10);
        Object coord = odCoord(db);
        File root = db.getRoot();
        int[] lo0 = {0};
        csVal(clazzNew, root, "payload", 1, lo0);
        System.out.println("[Size.t03] before-prep cs.len@1=" + lo0[0] + " meta.len=" + metaLen(clazzNew,root,"payload") + " reg.len=" + regLen(clazzNew,root,"payload"));
        List<Object> acs = prep(coord, clazzNew, false, true);
        System.out.println("[Size.t03] prepare acs=" + dumpCols(acs));
        int rows1 = apply(coord, clazzNew, 32);
        System.out.println("[Size.t03] apply1 rows=" + rows1);
        Object coord2 = odCoord(db);
        int rows2 = apply(coord2, clazzNew, 32);
        System.out.println("[Size.t03] apply2 rows=" + rows2);
        System.out.println("[Size.t03] after-apply meta.len=" + metaLen(clazzNew,root,"payload") + " reg.len=" + regLen(clazzNew,root,"payload"));
        Object cs = csOfDb();
        String ent = clazzNew.getName();
        int nulls = 0, notPad = 0, bad = 0;
        byte[] sample = csGetValue(cs, ent, "payload", 1L);
        System.out.println("[Size.t03] rid=1 sample len=" + (sample==null?-1:sample.length) + " content=" + (sample==null?"null":Arrays.toString(sample)));
        for (long rid = 1; rid <= N; rid++) {
            byte[] b = csGetValue(cs, ent, "payload", rid);
            if (b == null) { nulls++; continue; }
            String base = "r" + String.format("%08d", (int)(rid-1));
            StringBuilder sb = new StringBuilder();
            while (sb.length() < 10) sb.append(base);
            byte[] exp = sb.substring(0, 10).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            boolean ok = true;
            int lim = Math.min(10, exp.length);
            for (int i = 0; i < lim; i++) if (b[i] != exp[i]) { ok = false; break; }
            if (ok && b.length >= 12) for (int i = 10; i < 12; i++) if (b[i] != 0) { ok = false; break; }
            if (!ok) notPad++;
            if (!ok) bad++;
        }
        System.out.println("[Size.t03] nulls=" + nulls + " notPad=" + notPad + " bad=" + bad);
        Assert.assertEquals("TR-3.3 resume 2000 rows all valid padding, bad=" + bad, 0, bad);
    }

    @Test
    @SuppressWarnings({"rawtypes","unchecked"})
    public void t04_applyRollbackApplyRoundTrip() throws Exception {
        db = DsDatabaseLocal.load();
        Class clazzNew = SizeLen12Entity.class;
        saveLegacyMetaOverLength(clazzNew, "payload", 10);
        int N = 500;
        seed(db, N, clazzNew, 10);
        Object coord = odCoord(db);
        File root = db.getRoot();
        System.out.println("[Size.t04] H0 meta.len=" + metaLen(clazzNew,root,"payload") + " reg.len=" + regLen(clazzNew,root,"payload"));
        int[] h0l = {0};
        byte[] s0 = csVal(clazzNew, root, "payload", 1, h0l);
        System.out.println("[Size.t04] H0 cs.len@1=" + h0l[0] + " s=" + Arrays.toString(s0));
        byte[] H0 = fullScanHash(clazzNew, "payload", N);
        System.out.println("[Size.t04] H0=" + hex(H0));
        List<Object> ac1 = prep(coord, clazzNew, false, true);
        System.out.println("[Size.t04] prepare ac1=" + dumpCols(ac1));
        int r1 = apply(coord, clazzNew, 256);
        System.out.println("[Size.t04] apply1 rows=" + r1);
        int[] h1l = {0};
        byte[] s1 = csVal(clazzNew, root, "payload", 1, h1l);
        System.out.println("[Size.t04] H1 cs.len@1=" + h1l[0] + " meta.len=" + metaLen(clazzNew,root,"payload") + " reg.len=" + regLen(clazzNew,root,"payload") + " s=" + Arrays.toString(s1));
        byte[] H1 = fullScanHash(clazzNew, "payload", N);
        System.out.println("[Size.t04] H1=" + hex(H1));
        roll(coord, clazzNew);
        int[] h2l = {0};
        byte[] s2 = csVal(clazzNew, root, "payload", 1, h2l);
        System.out.println("[Size.t04] H2 cs.len@1=" + h2l[0] + " meta.len=" + metaLen(clazzNew,root,"payload") + " reg.len=" + regLen(clazzNew,root,"payload") + " s=" + Arrays.toString(s2));
        byte[] H2 = fullScanHash(clazzNew, "payload", N);
        System.out.println("[Size.t04] H2=" + hex(H2));
        Assert.assertArrayEquals("TR-3.4 rollback restores original H0=H2", H0, H2);
        Object coord2 = odCoord(db);
        prep(coord2, clazzNew, false, true);
        apply(coord2, clazzNew, 256);
        byte[] H3 = fullScanHash(clazzNew, "payload", N);
        System.out.println("[Size.t04] H3=" + hex(H3));
        Assert.assertArrayEquals("TR-3.4 second apply H1=H3 round-trip", H1, H3);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(16, b.length); i++) sb.append(String.format("%02x", b[i]));
        return sb.toString();
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    byte[] fullScanHash(Class clazz, String colName, int N) throws Exception {
        Object cs = csOfDb();
        String ent = clazz.getName();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        int nulls = 0;
        for (long rid = 1; rid <= N; rid++) {
            byte[] b = csGetValue(cs, ent, colName, rid);
            if (b == null) { nulls++; continue; }
            md.update(b);
        }
        if (nulls > 0) throw new RuntimeException("fullScan null rows=" + nulls);
        return md.digest();
    }
}