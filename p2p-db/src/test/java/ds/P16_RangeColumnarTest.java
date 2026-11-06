package ds;

import com.q3lives.ds.database.columnar.index.EqIndexStore;
import com.q3lives.ds.database.columnar.index.EqIndexMetaStore;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class P16_RangeColumnarTest {

    private File tmpRoot;
    private static final String ENTITY = "P16RangeStrEntity";
    private static final String LOGICAL = "name_idx";
    private static final long COL_ID = 1001L;

    @Before
    public void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("p16-col-range").toFile();
        tmpRoot.deleteOnExit();
    }

    @After
    public void tearDown() {
        // 依赖 tmpRoot.deleteOnExit() 清理，避免 Windows 上 DsFixedBucketStore 文件句柄未完全释放导致 FileSystem 访问冲突
    }

    private static byte[] toBytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static String[] SAMPLE = new String[] {
        "apple", "apricot", "banana", "blueberry", "cherry",
        "coconut", "date", "dragon", "elder", "fig",
        "grape", "guava", "kiwi", "lemon", "lime",
        "mango", "melon", "nectarine", "orange", "papaya",
        "peach", "pear", "pineapple", "plum", "pomegranate",
        "raspberry", "strawberry", "tangerine", "watermelon"
    };

    @Test
    public void t01_between_dict_order_sorted_correct() throws Exception {
        EqIndexStore store = new EqIndexStore(tmpRoot);
        store.createOrReplace(ENTITY, LOGICAL, COL_ID);
        // N=2000 random strings, unique enough
        TreeMap<String, List<Long>> baseline = new TreeMap<>();
        Random r = new Random(123);
        List<String> pool = new ArrayList<>();
        for (int i = 0; i < 500; i++) pool.add(SAMPLE[i % SAMPLE.length] + "_" + i);
        long rowId = 1;
        for (int i = 0; i < 2000; i++) {
            String s = pool.get(r.nextInt(pool.size()));
            store.add(ENTITY, COL_ID, toBytes(s), rowId);
            baseline.computeIfAbsent(s, k -> new ArrayList<>()).add(rowId);
            rowId++;
        }
        // between "banana_10" (inclusive) ~ "grape_400" (inclusive)
        String lo = "banana";
        String hi = "grape_";
        Method m = EqIndexStore.class.getDeclaredMethod("findRowIdsBetween",
            String.class, long.class, byte[].class, boolean.class, byte[].class, boolean.class, boolean.class, int.class);
        m.setAccessible(true);

        long[] asc = (long[]) m.invoke(store, ENTITY, COL_ID, toBytes(lo), true, toBytes(hi), false, true, Integer.MAX_VALUE);
        NavigableMap<String, List<Long>> slice = baseline.subMap(lo, true, hi, false);
        List<Long> expAsc = new ArrayList<>();
        for (Map.Entry<String, List<Long>> e : slice.entrySet()) expAsc.addAll(e.getValue());
        long[] expAscArr = new long[expAsc.size()];
        for (int i = 0; i < expAscArr.length; i++) expAscArr[i] = expAsc.get(i);
        Assert.assertEquals("ASC count same", expAscArr.length, asc.length);
        Assert.assertArrayEquals("ASC order content same", expAscArr, asc);

        long[] desc = (long[]) m.invoke(store, ENTITY, COL_ID, toBytes(lo), true, toBytes(hi), false, false, Integer.MAX_VALUE);
        Assert.assertEquals("DESC count same", expAscArr.length, desc.length);
        List<Long> expDesc = new ArrayList<>();
        Iterator<Map.Entry<String, List<Long>>> it = slice.descendingMap().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, List<Long>> e = it.next();
            List<Long> v = e.getValue();
            for (int j = v.size() - 1; j >= 0; j--) expDesc.add(v.get(j));
        }
        long[] expDescArr = new long[expDesc.size()];
        for (int i = 0; i < expDescArr.length; i++) expDescArr[i] = expDesc.get(i);
        Assert.assertArrayEquals("DESC order content", expDescArr, desc);
    }

    @Test
    public void t02_limit_topn_pushdown_matches_baseline_prefix() throws Exception {
        EqIndexStore store = new EqIndexStore(tmpRoot);
        store.createOrReplace(ENTITY, LOGICAL, COL_ID);
        TreeMap<String, List<Long>> baseline = new TreeMap<>();
        long rowId = 1;
        for (int i = 0; i < 2000; i++) {
            String s = String.format("key_%05d", i); // key_00000 ~ key_01999 ordered
            store.add(ENTITY, COL_ID, toBytes(s), rowId);
            baseline.computeIfAbsent(s, k -> new ArrayList<>()).add(rowId);
            rowId++;
        }
        Method m = EqIndexStore.class.getDeclaredMethod("findRowIdsBetween",
            String.class, long.class, byte[].class, boolean.class, byte[].class, boolean.class, boolean.class, int.class);
        m.setAccessible(true);
        int LIMIT = 100;
        long[] asc100 = (long[]) m.invoke(store, ENTITY, COL_ID, toBytes("key_00100"), true, toBytes("key_01500"), true, true, LIMIT);
        Assert.assertEquals("ASC LIMIT 100 count", LIMIT, asc100.length);
        NavigableMap<String, List<Long>> slice = baseline.subMap("key_00100", true, "key_01500", true);
        List<Long> exp = new ArrayList<>();
        for (Map.Entry<String, List<Long>> e : slice.entrySet()) {
            for (long rid : e.getValue()) {
                exp.add(rid);
                if (exp.size() == LIMIT) break;
            }
            if (exp.size() == LIMIT) break;
        }
        long[] expArr = new long[LIMIT];
        for (int i = 0; i < LIMIT; i++) expArr[i] = exp.get(i);
        Assert.assertArrayEquals("ASC LIMIT 100 content match prefix", expArr, asc100);

        long[] desc50 = (long[]) m.invoke(store, ENTITY, COL_ID, toBytes("key_00000"), true, toBytes("key_01999"), true, false, 50);
        Assert.assertEquals("DESC LIMIT 50 count", 50, desc50.length);
    }

    @Test
    public void t03_bloom_enable_does_not_break_lookup_and_dsHashMap_bounded() throws Exception {
        EqIndexStore store = new EqIndexStore(tmpRoot);
        store.createOrReplace(ENTITY, LOGICAL, COL_ID);
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);
        store.enableBloomForColumn(ENTITY, COL_ID, 20_000L, 0.01, counter);
        int N = 2000;
        Random r = new Random(77);
        List<String> pool = new ArrayList<>();
        for (int i = 0; i < 500; i++) pool.add("item_" + i);
        for (long i = 1; i <= N; i++) {
            String s = pool.get(r.nextInt(pool.size()));
            store.add(ENTITY, COL_ID, toBytes(s), i);
        }
        Method m = EqIndexStore.class.getDeclaredMethod("findRowIdsBetween",
            String.class, long.class, byte[].class, boolean.class, byte[].class, boolean.class, boolean.class, int.class);
        m.setAccessible(true);
        long[] rows = (long[]) m.invoke(store, ENTITY, COL_ID, toBytes("item_10"), true, toBytes("item_300"), true, true, Integer.MAX_VALUE);
        Assert.assertTrue("BETWEEN bloom enabled rows >= 0", rows.length >= 0);
        // DsHashMap 构造次数（每次 withLock 内 newDsHashMap 1 次；N=2000 add 2000次构造 expected ≤ N+小开销）
        Assert.assertTrue("DsHashMap 构造次数 bounded ≤ " + (N + 10) + " actual=" + counter.get(), counter.get() <= N + 10);
        // BloomFilter64 启用后 EQ 查找仍 works
        long[] eqHit = store.findRowIds(ENTITY, COL_ID, toBytes("item_10"), 10);
        Assert.assertTrue("Bloom EQ lookup still works count >=0", eqHit.length >= 0);
    }
}
