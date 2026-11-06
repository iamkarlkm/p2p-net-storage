package ds;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.columnar.TableMetaStore;
import org.junit.*;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.util.*;

public class P19_AlterPerfRubricTest {
    File tmpRoot;
    String oldHome;
    DsDatabaseLocal db;

    public static final class PerfEntity extends DsTableAdapter {
        @DsField(length = 20) public String f0;
        public long amt;
    }

    @Before
    public void setUp() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p19_perf_").toFile();
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void tearDown() throws Exception {
        try { if (db != null) db.close(); } catch (Exception ignored) {}
        deleteRecursive(tmpRoot);
        if (oldHome == null) System.clearProperty("p2p.db.home");
        else System.setProperty("p2p.db.home", oldHome);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File cc : c) deleteRecursive(cc);
        }
        try { f.delete(); } catch (Exception ignored) {}
    }

    static Object odCoord(DsDatabaseLocal db) throws Exception {
        Field f = DsDatabaseLocal.class.getDeclaredField("odCoordinator");
        f.setAccessible(true);
        return f.get(db);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static List prepAlter(Object coord, Class clz, boolean dryRun, boolean strict) throws Exception {
        Method m = findM(coord.getClass(), "prepareAlter", Class.class, boolean.class, boolean.class);
        return (List) m.invoke(coord, clz, dryRun, strict);
    }

    @SuppressWarnings({"rawtypes"})
    static int applyAlter(Object coord, Class clz, int batchSz) throws Exception {
        Method m = findM(coord.getClass(), "applyAlter", Class.class, int.class);
        return (int) m.invoke(coord, clz, batchSz);
    }

    @SuppressWarnings({"rawtypes"})
    static void rollbackAlter(Object coord, Class clz) throws Exception {
        Method m = findM(coord.getClass(), "rollbackAlter", Class.class);
        m.invoke(coord, clz);
    }

    static Method findM(Class<?> cl, String name, Class<?>... pts) throws NoSuchMethodException {
        Class<?> cc = cl;
        while (cc != null && cc != Object.class) {
            for (Method m : cc.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] mp = m.getParameterTypes();
                if (mp.length != pts.length) continue;
                boolean ok = true;
                for (int i = 0; i < mp.length; i++) {
                    Class<?> a = w(mp[i]);
                    Class<?> b = w(pts[i]);
                    if (!a.isAssignableFrom(b) && !b.isAssignableFrom(a)) { ok = false; break; }
                }
                if (ok) { m.setAccessible(true); return m; }
            }
            cc = cc.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    static Class<?> w(Class<?> c) {
        if (c == null) return null;
        if (c.isPrimitive()) {
            if (c == boolean.class) return Boolean.class;
            if (c == int.class) return Integer.class;
            if (c == long.class) return Long.class;
        }
        return c;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void saveLegacyLen(DsDatabaseLocal db, Class storedAs, String colName, int len) throws Exception {
        File root = db.getRoot();
        TableMetaStore tms = new TableMetaStore(root);
        Object backend;
        {
            Field bf = TableMetaStore.class.getDeclaredField("backend");
            bf.setAccessible(true);
            backend = bf.get(tms);
        }
        Class<?> bc = backend.getClass();
        Class<?> tmc = Class.forName("com.q3lives.ds.database.columnar.TableMetaStore$TableMeta");
        Class<?> cdc = Class.forName("com.q3lives.ds.database.columnar.TableMetaStore$ColumnDef");
        Object tm = tmc.getDeclaredConstructor().newInstance();
        {
            java.lang.reflect.Field f = tmc.getDeclaredField("entityClassName");
            f.setAccessible(true); f.set(tm, storedAs.getName());
        }
        {
            java.lang.reflect.Field f = tmc.getDeclaredField("compositeGroups");
            f.setAccessible(true); f.set(tm, new LinkedHashMap<>());
        }
        {
            java.lang.reflect.Field f = tmc.getDeclaredField("columns");
            f.setAccessible(true);
            java.util.List cols = new ArrayList();
            Object cd = cdc.getDeclaredConstructor().newInstance();
            {
                java.lang.reflect.Field cid = cdc.getDeclaredField("colId");
                cid.setAccessible(true); cid.set(cd, 0L);
            }
            {
                java.lang.reflect.Field cname = cdc.getDeclaredField("name");
                cname.setAccessible(true); cname.set(cd, colName);
            }
            {
                java.lang.reflect.Field ckey = cdc.getDeclaredField("colKey");
                ckey.setAccessible(true); ckey.set(cd, storedAs.getName() + "#" + colName);
            }
            {
                java.lang.reflect.Field cjt = cdc.getDeclaredField("javaType");
                cjt.setAccessible(true); cjt.set(cd, "java.lang.String");
            }
            {
                java.lang.reflect.Field cl = cdc.getDeclaredField("length");
                cl.setAccessible(true); cl.set(cd, len);
            }
            {
                java.lang.reflect.Field cp = cdc.getDeclaredField("precision");
                cp.setAccessible(true); cp.set(cd, 18);
            }
            {
                java.lang.reflect.Field cs = cdc.getDeclaredField("scale");
                cs.setAccessible(true); cs.set(cd, 2);
            }
            {
                java.lang.reflect.Field cn = cdc.getDeclaredField("nullable");
                cn.setAccessible(true); cn.set(cd, true);
            }
            cols.add(cd);
            f.set(tm, cols);
        }
        Method saveTo = bc.getDeclaredMethod("saveToRegistry", String.class, tmc);
        saveTo.setAccessible(true);
        saveTo.invoke(backend, storedAs.getName(), tm);
    }

    @SuppressWarnings("unchecked")
    List<Long> seed(DsDatabaseLocal db, int N, Class<PerfEntity> clazz) throws Exception {
        List<Long> ids = new ArrayList<>(N);
        Random rnd = new Random(0xC0FFEE);
        for (int i = 0; i < N; i++) {
            PerfEntity e = clazz.getDeclaredConstructor().newInstance();
            // f0 length=8 (fits old legacy=10, less than 10 to ensure no overflow at write)
            e.f0 = "X" + String.format("%07d", i);
            e.amt = rnd.nextLong() & 0x7FFFFFFFFFFFFFFFL;
            long rid = db.putEntity(e, true);
            ids.add(rid);
        }
        return ids;
    }

    @Test(timeout = 600_000L)
    public void t01_U1_paddingLength_P99_le_60s() throws Exception {
        Class<PerfEntity> clazz = PerfEntity.class;
        db = new DsDatabaseLocal(tmpRoot);
        db.registerTable(clazz);
        Object coord = odCoord(db);
        int N = 2_000;
        int ROUNDS = 3;
        int BATCH = 512;
        seed(db, N, clazz);
        saveLegacyLen(db, clazz, "f0", 10);
        prepAlter(coord, clazz, true, true);
        long[] timesMs = new long[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            long start = System.currentTimeMillis();
            prepAlter(coord, clazz, false, true);
            applyAlter(coord, clazz, BATCH);
            long elapsed = System.currentTimeMillis() - start;
            timesMs[r] = elapsed;
            rollbackAlter(coord, clazz);
            saveLegacyLen(db, clazz, "f0", 10);
        }
        Arrays.sort(timesMs);
        long p99 = timesMs[Math.max(0, (int) (ROUNDS * 0.99))];
        long pMax = timesMs[ROUNDS - 1];
        long sum = 0L; for (long t : timesMs) sum += t;
        long p50 = timesMs[ROUNDS / 2];
        double avg = sum / (double) ROUNDS;
        System.out.println("[P19 U1] LENGTH Padding 10→20 rounds=" + ROUNDS + " rows=" + N
            + " P50=" + p50 + "ms P99=" + p99 + "ms Pmax=" + pMax + "ms avg=" + String.format("%.2f", avg) + "ms");
        int score;
        if (p99 <= 30_000L) score = 5;
        else if (p99 <= 60_000L) score = 4;
        else if (p99 <= 120_000L) score = 3;
        else if (p99 <= 300_000L) score = 2;
        else score = 0;
        System.out.println("[P19 U1] score=" + score + " (P99=" + p99 + "ms)");
        Assert.assertTrue("[U1 score ≥ 4 (P99=" + p99 + "ms score=" + score + ")]", score >= 4);
    }

    @Test(timeout = 600_000L)
    public void t02_U2_peakMemory_le_64MB() throws Exception {
        Class<PerfEntity> clazz = PerfEntity.class;
        db = new DsDatabaseLocal(tmpRoot);
        db.registerTable(clazz);
        Object coord = odCoord(db);
        int N = 2_000;
        int ROUNDS = 3;
        int BATCH = 512;
        seed(db, N, clazz);
        saveLegacyLen(db, clazz, "f0", 10);
        prepAlter(coord, clazz, true, true);
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        Runtime rt = Runtime.getRuntime();
        long peakUsed = 0L;
        for (int r = 0; r < ROUNDS; r++) {
            prepAlter(coord, clazz, false, true);
            applyAlter(coord, clazz, BATCH);
            System.gc();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            MemoryUsage heap = memBean.getHeapMemoryUsage();
            MemoryUsage nonheap = memBean.getNonHeapMemoryUsage();
            long usedHeap = heap.getUsed();
            long usedNon = nonheap.getUsed();
            long usedRt = rt.totalMemory() - rt.freeMemory();
            long curMax = Math.max(Math.max(usedHeap, usedNon), usedRt);
            if (curMax > peakUsed) peakUsed = curMax;
            rollbackAlter(coord, clazz);
            saveLegacyLen(db, clazz, "f0", 10);
            System.gc();
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            MemoryUsage h2 = memBean.getHeapMemoryUsage();
            if (h2.getUsed() > peakUsed) peakUsed = h2.getUsed();
        }
        long peakMB = peakUsed / (1024L * 1024L);
        long maxMemMB = rt.maxMemory() / (1024L * 1024L);
        System.out.println("[P19 U2] Peak memory=" + peakMB + "MB (JVM max=" + maxMemMB + "MB), rounds=" + ROUNDS + " rows=" + N);
        int score;
        if (peakMB <= 40L) score = 5;
        else if (peakMB <= 64L) score = 4;
        else if (peakMB <= 128L) score = 3;
        else if (peakMB <= 256L) score = 2;
        else score = 0;
        System.out.println("[P19 U2] score=" + score + " (peak=" + peakMB + "MB)");
        Assert.assertTrue("[U2 score ≥ 4 (peak=" + peakMB + "MB score=" + score + ")]", score >= 4);
    }
}
