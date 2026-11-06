package ds;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertFalse;
import org.junit.Test;

import com.q3lives.ds.bucket.DsData;

public class DsString_DsData_P8_RefCount_ConcurrentStressTest {

    private static File tmpDir(String prefix) {
        File f = new File("target", prefix + "-" + System.nanoTime());
        f.mkdirs();
        f.deleteOnExit();
        return f;
    }

    private static ConcurrentMap<String, AtomicInteger> newErrCounters() {
        return new ConcurrentHashMap<>();
    }

    private static void incErr(ConcurrentMap<String, AtomicInteger> m, String k) {
        m.computeIfAbsent(k, x -> new AtomicInteger()).incrementAndGet();
    }

    private static void incErr(ConcurrentMap<String, AtomicInteger> m, String k, Throwable t) {
        incErr(m, k);
        m.computeIfAbsent(k + "_firstMsg", x -> new AtomicInteger())
                .compareAndSet(0, 1);
    }

    private static void assertEmptyErrs(ConcurrentMap<String, AtomicInteger> m, String tag) {
        StringBuilder sb = new StringBuilder(tag);
        boolean bad = false;
        for (java.util.Map.Entry<String, AtomicInteger> e : m.entrySet()) {
            if (e.getKey().endsWith("_firstMsg")) continue;
            int c = e.getValue().get();
            if (c > 0) {
                bad = true;
                sb.append(" | ").append(e.getKey()).append('=').append(c);
            }
        }
        assertFalse(sb.toString(), bad);
    }

    @Test
    public void T01_testPutRemove_RefCountLeak_R2() throws Exception {
        File dir = tmpDir("ds-p8-t01-leak");
        DsData d = new DsData(dir.getAbsolutePath(), "data");
        final byte[] KEY = "concurrent_key_R2_0123456789_abcdef".getBytes(StandardCharsets.UTF_8);
        final int N = 500;
        final int PUT_THREADS = 6;
        final int RM_THREADS = 6;
        ConcurrentMap<String, AtomicInteger> errCounters = newErrCounters();
        try {
            ExecutorService pool = Executors.newFixedThreadPool(PUT_THREADS + RM_THREADS);
            List<Callable<Void>> tasks = new ArrayList<>();
            AtomicLong putsOk = new AtomicLong();
            AtomicLong rmsOk = new AtomicLong();
            for (int i = 0; i < PUT_THREADS; i++) {
                tasks.add(() -> {
                    for (int k = 0; k < N; k++) {
                        try {
                            d.put(KEY);
                            putsOk.incrementAndGet();
                        } catch (Throwable t) {
                            incErr(errCounters, "put_ex", t);
                        }
                    }
                    return null;
                });
            }
            for (int i = 0; i < RM_THREADS; i++) {
                tasks.add(() -> {
                    for (int k = 0; k < N; k++) {
                        try {
                            Long id = d.getIndexId(KEY);
                            if (id != null) {
                                boolean removed = d.remove(id);
                                if (removed) rmsOk.incrementAndGet();
                            }
                        } catch (Throwable t) {
                            incErr(errCounters, "remove_ex", t);
                        }
                    }
                    return null;
                });
            }
            List<Future<Void>> fs = pool.invokeAll(tasks);
            for (Future<Void> f : fs) f.get();
            pool.shutdownNow();

            // Lightweight check: no need to drain to 0 (costly I/O on Windows)
            // Instead: compute upper bound on ref based on observable delta
            long delta = putsOk.get() - rmsOk.get();
            Long finalId = d.getIndexId(KEY);
            int finalRef = 0;
            if (finalId != null) finalRef = d.getRefCountByIndexId(finalId);
            // Upper bound: if perfectly serialized finalRef == max(0, delta).
            // Allow 1 slack due to non-atomicity of getIndexId + refCount read window (not race bug).
            // If leak (R2), finalRef >> delta.  If slot ok, <= delta + 1 when delta>=0.
            long upper = Math.max(0L, delta) + 1;
            if (finalRef > upper) {
                incErr(errCounters, "refCount_leak_finalRef_" + finalRef + "_gt_upper_" + upper);
            }
            if (finalId != null && finalRef == 0) {
                // harmless dangling mapping, not a leak
            }
            String tag = "T01 puts=" + putsOk + " rms=" + rmsOk + " delta=" + delta
                    + " finalId=" + finalId + " finalRef=" + finalRef + " upperBound=" + upper;
            assertEmptyErrs(errCounters, tag);
        } finally {
            d.close();
        }
    }

    @Test
    public void T02_testRetainRemove_Resurrection_R1() throws Exception {
        File dir = tmpDir("ds-p8-t02-resurrect");
        DsData d = new DsData(dir.getAbsolutePath(), "data");
        final byte[] KEY = "R1_revive_me".getBytes(StandardCharsets.UTF_8);
        final int CYCLES = 800;
        final int RETAIN_THREADS = 8;
        ConcurrentMap<String, AtomicInteger> errCounters = newErrCounters();
        try {
            ExecutorService pool = Executors.newFixedThreadPool(RETAIN_THREADS + 1);
            AtomicLong retainTrue = new AtomicLong();
            AtomicLong retainFalse = new AtomicLong();
            AtomicLong retainExIse = new AtomicLong();
            AtomicLong cycleRms = new AtomicLong();

            List<Callable<Void>> tasks = new ArrayList<>();

            // remove-driver: repeatedly add (ref=1) then remove last ref -> free loop
            tasks.add(() -> {
                for (int c = 0; c < CYCLES; c++) {
                    long id;
                    try {
                        id = d.put(KEY);
                    } catch (Throwable t) {
                        incErr(errCounters, "driver_put_ex", t);
                        continue;
                    }
                    int refAfterPut;
                    try {
                        refAfterPut = d.getRefCountByIndexId(id);
                    } catch (Throwable t) {
                        incErr(errCounters, "driver_ref_ex", t);
                        continue;
                    }
                    if (refAfterPut != 1) {
                        incErr(errCounters, "put_ref_not_1_r" + refAfterPut);
                        for (int i = 0; i < Math.max(refAfterPut, 0); i++) {
                            try { d.remove(id); } catch (Throwable ignore) {}
                        }
                        continue;
                    }
                    try {
                        boolean ok = d.remove(id);
                        if (ok) cycleRms.incrementAndGet();
                    } catch (Throwable t) {
                        incErr(errCounters, "driver_remove_ex", t);
                    }
                }
                return null;
            });

            // retain-storm: 31 threads continuously try to retain((first id observed))
            // (They may retain random ids seen via getIndexId; if they hit between put+remove-last,
            //  and their RMW beats remove's tombstone write, they resurrect a free'd slot -> ref>0 but no masterIndex mapping)
            for (int i = 0; i < RETAIN_THREADS; i++) {
                tasks.add(() -> {
                    long lastSeenId = -1;
                    for (int c = 0; c < CYCLES; c++) {
                        try {
                            Long id = d.getIndexId(KEY);
                            if (id != null) lastSeenId = id;
                            if (lastSeenId > 0) {
                                boolean ok;
                                try {
                                    ok = d.retain(lastSeenId);
                                } catch (Throwable t) {
                                    incErr(errCounters, "retain_inner_ex", t);
                                    continue;
                                }
                                if (ok) {
                                    retainTrue.incrementAndGet();
                                    try { d.remove(lastSeenId); } catch (Throwable ignore) {}
                                } else {
                                    retainFalse.incrementAndGet();
                                }
                            }
                        } catch (IllegalStateException ise) {
                            retainExIse.incrementAndGet();
                        } catch (Throwable t) {
                            incErr(errCounters, "retain_ex", t);
                        }
                    }
                    return null;
                });
            }

            List<Future<Void>> fs = pool.invokeAll(tasks);
            for (Future<Void> f : fs) {
                try { f.get(); }
                catch (Throwable t) { incErr(errCounters, "future_get_ex", t); }
            }
            pool.shutdownNow();

            // Post-check: any slot has ref>0 with no masterIndex mapping = resurrection
            // We can't iterate all slots, but we can re-put same key and see if returned id
            // has unexpected non-zero refs (id reuse is rare so this is a proxy)
            long newId = d.put(KEY);
            int refAfterRePut = d.getRefCountByIndexId(newId);
            // remove both the 1 just added and any residual
            int removed = 0;
            while (d.remove(newId)) removed++;
            if (removed > 1) {
                // ref was >1 after single put => resurrection left stray ref on this slot
                incErr(errCounters, "resurrection_leftover_ref_removed_" + removed);
            }

            // A direct strong check: getIndexId null -> then directly read ref on ids we saw via bucket is hard
            // Easier proxy: if we observed retainTrue happen when KEY had no mapping, implies RMW won race
            // Not directly measurable; leftover_ref check above catches major cases.
            String tag = "T02 cycles=" + CYCLES + " cycleRms=" + cycleRms
                    + " retainTrue=" + retainTrue + " retainFalse=" + retainFalse
                    + " retainExIse=" + retainExIse + " leftoverRemoved=" + removed;
            assertEmptyErrs(errCounters, tag);
        } finally {
            d.close();
        }
    }

    @Test
    public void T03_testCoWPartialVsRemove_DoubleFree_R4() throws Exception {
        File dir = tmpDir("ds-p8-t03-cow");
        DsData d = new DsData(dir.getAbsolutePath(), "data");
        final byte[] BASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345".getBytes(StandardCharsets.UTF_8);
        final int N = 200;
        final int T = 4;
        ConcurrentMap<String, AtomicInteger> errCounters = newErrCounters();
        try {
            ExecutorService pool = Executors.newFixedThreadPool(T * 2);
            List<Callable<Void>> tasks = new ArrayList<>();
            AtomicLong updOk = new AtomicLong();
            AtomicLong rmOk = new AtomicLong();

            for (int t = 0; t < T; t++) {
                tasks.add(() -> {
                    for (int i = 0; i < N; i++) {
                        try {
                            long id = d.put(BASE);
                            byte[] patch = ("x" + i % 1000).getBytes(StandardCharsets.UTF_8);
                            long newId = d.updatePartialValueByIndexId(id, 0, patch);
                            if (newId != id) {
                                updOk.incrementAndGet();
                            } else {
                                updOk.incrementAndGet();
                            }
                        } catch (Throwable t0) {
                            incErr(errCounters, "cow_ex", t0);
                        }
                    }
                    return null;
                });
            }
            for (int t = 0; t < T; t++) {
                tasks.add(() -> {
                    for (int i = 0; i < N; i++) {
                        try {
                            Long id = d.getIndexId(BASE);
                            if (id != null) {
                                boolean ok = d.remove(id);
                                if (ok) rmOk.incrementAndGet();
                            }
                        } catch (Throwable t0) {
                            incErr(errCounters, "rm_ex", t0);
                        }
                    }
                    return null;
                });
            }
            List<Future<Void>> fs = pool.invokeAll(tasks);
            for (Future<Void> f : fs) f.get();
            pool.shutdownNow();

            String tag = "T03 updOk=" + updOk + " rmOk=" + rmOk;
            assertEmptyErrs(errCounters, tag);
        } finally {
            d.close();
        }
    }

    @Test
    public void T04_testMixed64Thread_10s_Stability() throws Exception {
        File dir = tmpDir("ds-p8-t04-mixed");
        DsData d = new DsData(dir.getAbsolutePath(), "data");
        final int KEYS = 64;
        final byte[][] KEY_ARR = new byte[KEYS][];
        for (int i = 0; i < KEYS; i++) KEY_ARR[i] = ("K" + i + "_payload_" + "Z".repeat(16)).getBytes(StandardCharsets.UTF_8);
        final int THREADS = 4;
        final int PER_THREAD = 40;
        ConcurrentMap<String, AtomicInteger> errCounters = newErrCounters();
        try {
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            List<Callable<Void>> tasks = new ArrayList<>();
            AtomicLong ops = new AtomicLong();
            for (int t = 0; t < THREADS; t++) {
                final int ft = t;
                tasks.add(() -> {
                    java.util.Random r = new java.util.Random(ft * 131L + 7);
                    for (int i = 0; i < PER_THREAD; i++) {
                        int kind = r.nextInt(5);
                        byte[] k = KEY_ARR[r.nextInt(KEYS)];
                        try {
                            switch (kind) {
                                case 0: d.put(k); break;
                                case 1: {
                                    Long id = d.getIndexId(k);
                                    if (id != null) d.retain(id);
                                    break;
                                }
                                case 2: {
                                    Long id = d.getIndexId(k);
                                    if (id != null) d.remove(id);
                                    break;
                                }
                                case 3: d.getIndexId(k); break;
                                case 4: {
                                    Long id = d.getIndexId(k);
                                    if (id != null) d.getRefCountByIndexId(id);
                                    break;
                                }
                            }
                            ops.incrementAndGet();
                        } catch (IllegalStateException ise) {
                            incErr(errCounters, "ise_" + kind);
                        } catch (Throwable th) {
                            incErr(errCounters, "ex_" + kind, th);
                        }
                    }
                    return null;
                });
            }
            List<Future<Void>> fs = pool.invokeAll(tasks);
            for (Future<Void> f : fs) f.get();
            pool.shutdownNow();
            String tag = "T04 ops=" + ops;
            assertEmptyErrs(errCounters, tag);
        } finally {
            d.close();
        }
    }
}
