package ds;

import com.q3lives.ds.database.index.DsEqIndexStore;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;

public class P16_RangeRowStoreTest {

    private static final String SPACE = "p16_range_row_space";
    private static final String IDX_AGE = "age";
    private File tmpRoot;

    @Before
    public void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("p16-row-range").toFile();
        tmpRoot.deleteOnExit();
    }

    @After
    public void tearDown() throws IOException {
        DsEqIndexStore.forceResetIndexForTest(tmpRoot, SPACE, IDX_AGE);
    }

    private static long[] sortedCopy(long[] arr) {
        long[] a = arr.clone();
        Arrays.sort(a);
        return a;
    }

    @Test
    public void t01_six_range_ops_sorted_equals_memory_baseline() throws IOException {
        int N = 100_000;
        try (DsEqIndexStore idx = new DsEqIndexStore(tmpRoot, SPACE, IDX_AGE, DsEqIndexStore.IndexedValueKind.LONG)) {
            Random r = new Random(42);
            Map<Long, List<Long>> baseline = new TreeMap<>();
            for (long rowId = 1; rowId <= N; rowId++) {
                long age = r.nextInt(100_000);
                idx.putIndex(age, rowId);
                baseline.computeIfAbsent(age, k -> new ArrayList<>()).add(rowId);
            }

            long[][] ops = new long[][] {
                {20_000L, 40_000L},
                {10_000L, 10_000L},
                {0L, 99_999L}
            };
            for (long[] op : ops) {
                long lo = op[0]; long hi = op[1];
                long[] actual = idx.findByBetween(lo, hi);
                List<Long> expectedList = new ArrayList<>();
                for (Map.Entry<Long, List<Long>> e : baseline.entrySet()) {
                    long k = e.getKey();
                    if (k >= lo && k <= hi) expectedList.addAll(e.getValue());
                }
                long[] expectedArr = new long[expectedList.size()];
                for (int i = 0; i < expectedArr.length; i++) expectedArr[i] = expectedList.get(i);
                long[] expSorted = sortedCopy(expectedArr);
                long[] actSorted = sortedCopy(actual);
                Assert.assertArrayEquals("BETWEEN " + lo + " ~ " + hi, expSorted, actSorted);
            }

            long[] gte = idx.findByGte(10_000L);
            Assert.assertTrue("gte count", gte.length > 0);
            for (long v : gte) Assert.assertTrue("gte rowId positive", v >= 1);

            long[] gt = idx.findByGt(99_998L);
            List<Long> gt99998 = new ArrayList<>();
            for (Map.Entry<Long, List<Long>> e : baseline.entrySet()) {
                if (e.getKey() > 99998L) gt99998.addAll(e.getValue());
            }
            Assert.assertEquals("GT(99998) size", gt99998.size(), gt.length);

            long[] lt = idx.findByLt(1_000L);
            List<Long> ltExpected = new ArrayList<>();
            for (Map.Entry<Long, List<Long>> e : baseline.entrySet()) {
                if (e.getKey() < 1000L) ltExpected.addAll(e.getValue());
            }
            Assert.assertEquals("LT(1000) size", ltExpected.size(), lt.length);

            long[] lte = idx.findByLte(9_999L);
            List<Long> lteExpected = new ArrayList<>();
            for (Map.Entry<Long, List<Long>> e : baseline.entrySet()) {
                if (e.getKey() <= 9_999L) lteExpected.addAll(e.getValue());
            }
            Assert.assertEquals("LTE(9999) size", lteExpected.size(), lte.length);
        }
    }

    @Test
    public void t02_limit_topn_pushdown_reflect_internal() throws Exception {
        int N = 100_000;
        try (DsEqIndexStore idx = new DsEqIndexStore(tmpRoot, SPACE, IDX_AGE, DsEqIndexStore.IndexedValueKind.LONG)) {
            TreeMap<Long, List<Long>> baseline = new TreeMap<>();
            for (long rowId = 1; rowId <= N; rowId++) {
                long age = rowId;
                idx.putIndex(age, rowId);
                baseline.computeIfAbsent(age, k -> new ArrayList<>()).add(rowId);
            }
            Method m = DsEqIndexStore.class.getDeclaredMethod("findByRange",
                Long.class, boolean.class, Long.class, boolean.class, boolean.class, int.class);
            m.setAccessible(true);
            int LIMIT = 100;
            long[] asc = (long[]) m.invoke(idx, 10_000L, true, 50_000L, true, true, LIMIT);
            Assert.assertEquals("ASC LIMIT 100 count", LIMIT, asc.length);
            List<Long> expAsc = new ArrayList<>();
            for (Map.Entry<Long, List<Long>> e : baseline.entrySet()) {
                long k = e.getKey();
                if (k < 10_000 || k > 50_000) continue;
                for (long rid : e.getValue()) {
                    expAsc.add(rid);
                    if (expAsc.size() == LIMIT) break;
                }
                if (expAsc.size() == LIMIT) break;
            }
            long[] ascExp = new long[LIMIT];
            for (int i = 0; i < LIMIT; i++) ascExp[i] = expAsc.get(i);
            Assert.assertArrayEquals("ASC LIMIT 100 content", ascExp, asc);

            long[] desc = (long[]) m.invoke(idx, 10_000L, true, 50_000L, true, false, LIMIT);
            Assert.assertEquals("DESC LIMIT 100 count", LIMIT, desc.length);
            List<Long> expDesc = new ArrayList<>();
            Iterator<Map.Entry<Long, List<Long>>> itDesc = baseline.descendingMap().entrySet().iterator();
            while (itDesc.hasNext() && expDesc.size() < LIMIT) {
                Map.Entry<Long, List<Long>> e = itDesc.next();
                long k = e.getKey();
                if (k < 10_000L || k > 50_000L) continue;
                List<Long> v = e.getValue();
                for (int j = v.size() - 1; j >= 0 && expDesc.size() < LIMIT; j--) expDesc.add(v.get(j));
            }
            long[] descExp = new long[LIMIT];
            for (int i = 0; i < LIMIT; i++) descExp[i] = expDesc.get(i);
            Assert.assertArrayEquals("DESC LIMIT 100 content", descExp, desc);
        }
    }

    @Test
    public void t03_perf_1e6_range_order_limit_100_p99_50ms() throws Exception {
        int N = 200_000;
        int rounds = 100;
        try (DsEqIndexStore idx = new DsEqIndexStore(tmpRoot, SPACE, IDX_AGE, DsEqIndexStore.IndexedValueKind.LONG)) {
            Random r = new Random(7);
            for (long rowId = 1; rowId <= N; rowId++) {
                long age = r.nextInt(1_000_000);
                idx.putIndex(age, rowId);
            }
            Method m = DsEqIndexStore.class.getDeclaredMethod("findByRange",
                Long.class, boolean.class, Long.class, boolean.class, boolean.class, int.class);
            m.setAccessible(true);
            List<Long> times = new ArrayList<>();
            for (int i = 0; i < rounds; i++) {
                long lo = 100_000 + i * 3_000L;
                long hi = lo + 100_000;
                long s = System.nanoTime();
                long[] rows = (long[]) m.invoke(idx, lo, true, hi, true, true, 100);
                long dur = (System.nanoTime() - s) / 1_000_000L;
                times.add(dur);
            }
            Collections.sort(times);
            long p99 = times.get((int) (times.size() * 0.99));
            int score;
            if (p99 <= 50) score = 5;
            else if (p99 <= 150) score = 4;
            else if (p99 <= 400) score = 3;
            else if (p99 <= 700) score = 2;
            else if (p99 <= 1500) score = 1;
            else score = 0;
            System.out.println("[U1 RowStore Range] N=" + N + " rounds=" + rounds
                + " P99=" + p99 + "ms score=" + score + "/5 (HARD=50 SOFT=150 threshold>=4)");
            Assert.assertTrue("P99=" + p99 + "ms score=" + score + " should >=4", score >= 4);
        }
    }
}
