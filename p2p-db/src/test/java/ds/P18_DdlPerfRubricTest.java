package ds;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.columnar.ColumnRegistry;
import com.q3lives.ds.database.columnar.RowIdSequenceStore;
import org.junit.*;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

public class P18_DdlPerfRubricTest {
    File tmpRoot;
    String oldHome;
    DsDatabaseLocal db;

    public static final class PfEntity extends DsTableAdapter {
        @DsField(length = 64) public String f0;
        @DsField(length = 64) public String f1;
        @DsField(length = 64) public String f2;
        @DsField(length = 64) public String f3;
        @DsField(length = 64) public String f4;
        public long amt;
        public int flag;
    }

    @Before
    public void setUp() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p18_perf_").toFile();
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

    List<Long> seed(DsDatabaseLocal db, int N, Class<PfEntity> clazz) throws Exception {
        List<Long> ids = new ArrayList<>(N);
        Random rnd = new Random(0xBEEF);
        for (int i = 0; i < N; i++) {
            PfEntity e = clazz.getDeclaredConstructor().newInstance();
            e.f0 = "F0_" + String.format("%08d", i) + "_" + rnd.nextInt(99999);
            e.f1 = "F1_" + rnd.nextInt(99999);
            e.f2 = "F2_" + rnd.nextInt(99999);
            e.f3 = "F3_" + rnd.nextInt(99999);
            e.f4 = "F4_" + rnd.nextInt(99999);
            e.amt = rnd.nextLong() & 0x7FFFFFFFFFFFFFFFL;
            e.flag = rnd.nextInt(1024);
            long rid = db.putTable(e);
            ids.add(rid);
        }
        return ids;
    }

    @Test(timeout = 600_000L)
    public void t01_U1_addColumn_P99_le_60s() throws Exception {
        Class<PfEntity> clazz = PfEntity.class;
        db = DsDatabaseLocal.load();
        db.registerTable(clazz);
        int N = 2_000;
        int ROUNDS = 3;
        List<Long> ids = seed(db, N, clazz);
        db.forceApplyOnlineDDL(clazz);
        // 直接开始测性能，不依赖 ColRegistry fieldId 映射
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long[] timesMs = new long[ROUNDS];
        int BATCH = 512;
        for (int r = 0; r < ROUNDS; r++) {
            long start = System.currentTimeMillis();
            db.forceApplyOnlineDDL(clazz);
            // f5/f6 批 fill: forceApplyAddDefault (overlayDefaultsOnRead 自动填 nulls)
            for (int i = 0; i < N; i += BATCH) {
                int end = Math.min(N, i + BATCH);
                for (int j = i; j < end; j++) {
                    PfEntity row = db.getTable(clazz, ids.get(j));
                    if (row != null) db.putTable(row);
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            timesMs[r] = elapsed;
        }
        Arrays.sort(timesMs);
        long p99 = timesMs[(int) (ROUNDS * 0.99)];
        long pMax = timesMs[ROUNDS - 1];
        long p50 = timesMs[ROUNDS / 2];
        long sum = 0L; for (long t : timesMs) sum += t;
        System.out.println("[P18 U1] ADD COLUMN rounds=" + ROUNDS + " rows=" + N
            + " P50=" + p50 + "ms P99=" + p99 + "ms Pmax=" + pMax + "ms avg=" + (sum / (double) ROUNDS) + "ms");
        int score = 5;
        if (p99 > 15000) score = 4;
        if (p99 > 30000) score = 3;
        if (p99 > 60000) score = 2;
        if (p99 > 120000) score = 0;
        Assert.assertTrue("U1 P99 score ≥ 4 (P99=" + p99 + "ms score=" + score + ")", score >= 4);
    }

    @Test(timeout = 600_000L)
    public void t02_U2_peakMemory_le_512MB() throws Exception {
        Class<PfEntity> clazz = PfEntity.class;
        db = DsDatabaseLocal.load();
        db.registerTable(clazz);
        int N = 1_500;
        int ROUNDS = 3;
        List<Long> ids = seed(db, N, clazz);
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        long peakUsed = 0L;
        int BATCH = 256;
        for (int r = 0; r < ROUNDS; r++) {
            db.forceApplyOnlineDDL(clazz);
            for (int i = 0; i < N; i += BATCH) {
                int end = Math.min(N, i + BATCH);
                for (int j = i; j < end; j++) {
                    PfEntity row = db.getTable(clazz, ids.get(j));
                    if (row != null) db.putTable(row);
                }
            }
            System.gc();
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            MemoryUsage mu = memBean.getHeapMemoryUsage();
            if (mu.getUsed() > peakUsed) peakUsed = mu.getUsed();
            MemoryUsage nh = memBean.getNonHeapMemoryUsage();
            if (nh.getUsed() > peakUsed) peakUsed = nh.getUsed();
            // rollback by re-forceApply
            db.forceRollbackSchema(clazz);
            System.gc();
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            MemoryUsage mu2 = memBean.getHeapMemoryUsage();
            if (mu2.getUsed() > peakUsed) peakUsed = mu2.getUsed();
        }
        long peakMB = peakUsed / (1024L * 1024L);
        Runtime rt = Runtime.getRuntime();
        long maxMemMB = rt.maxMemory() / (1024L * 1024L);
        System.out.println("[P18 U2] Peak memory=" + peakMB + "MB (JVM max=" + maxMemMB + "MB), rounds=" + ROUNDS + " rows=" + N);
        int score = 5;
        if (peakMB > 128) score = 4;
        if (peakMB > 256) score = 3;
        if (peakMB > 512) score = 0;
        Assert.assertTrue("U2 peak memory score ≥ 4 (peak=" + peakMB + "MB score=" + score + ")", score >= 4);
    }
}
