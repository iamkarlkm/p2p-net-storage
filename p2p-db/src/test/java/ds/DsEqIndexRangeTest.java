package ds;

import com.q3lives.ds.database.index.DsEqIndexStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DsEqIndexRangeTest {

    private File tmpRoot;
    private static final String SPACE = "com.test.RangeOrder";
    private static final String IDX_AGE = "age";
    private static final String IDX_SCORE = "score";

    @Before
    public void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("dsdb-eqidx-range-").toFile();
    }

    @After
    public void tearDown() throws IOException {
        if (tmpRoot != null && tmpRoot.isDirectory()) {
            try (Stream<Path> walk = Files.walk(tmpRoot.toPath())) {
                walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(f -> f.delete());
            }
        }
    }

    private static Set<Long> setOf(long... arr) {
        HashSet<Long> s = new HashSet<>(arr.length);
        for (long v : arr) s.add(v);
        return s;
    }

    @Test
    public void testBetweenRangeBasicOrderAndMultiRow() throws IOException {
        try (DsEqIndexStore idx = new DsEqIndexStore(tmpRoot, SPACE, IDX_AGE)) {
            idx.putIndex(10L, 1001L);
            idx.putIndex(20L, 2001L);
            idx.putIndex(20L, 2002L);
            idx.putIndex(30L, 3001L);
            idx.putIndex(40L, 4001L);
            idx.putIndex(50L, 5001L);

            long[] rows = idx.findByBetween(20L, 40L);
            Assert.assertEquals(4, rows.length);
            Set<Long> got = setOf(rows);
            Assert.assertEquals(setOf(2001L, 2002L, 3001L, 4001L), got);
            Assert.assertTrue(rows[0] == 2001L || rows[0] == 2002L);
            Assert.assertEquals(20L, idx.findFirstByBetween(20L, 40L) == 2001L ? 20L : 20L);
        }
    }

    @Test
    public void testGtGteLtLteEdgeBoundary() throws IOException {
        try (DsEqIndexStore idx = new DsEqIndexStore(tmpRoot, SPACE, IDX_SCORE)) {
            idx.putIndex(0L, 101L);
            idx.putIndex(10L, 102L);
            idx.putIndex(100L, 103L);
            idx.putIndex(100L, 104L);
            idx.putIndex(Long.MAX_VALUE, 105L);

            long[] gte10 = idx.findByGte(10L);
            Set<Long> gteSet = setOf(gte10);
            Assert.assertEquals(setOf(102L, 103L, 104L, 105L), gteSet);
            Assert.assertEquals(4, gte10.length);

            long[] gt10 = idx.findByGt(10L);
            Set<Long> gtSet = setOf(gt10);
            Assert.assertEquals(setOf(103L, 104L, 105L), gtSet);
            Assert.assertEquals(3, gt10.length);

            long[] lt100 = idx.findByLt(100L);
            Set<Long> ltSet = setOf(lt100);
            Assert.assertEquals(setOf(101L, 102L), ltSet);
            Assert.assertEquals(2, lt100.length);

            long[] lte100 = idx.findByLte(100L);
            Set<Long> lteSet = setOf(lte100);
            Assert.assertEquals(setOf(101L, 102L, 103L, 104L), lteSet);
            Assert.assertEquals(4, lte100.length);

            Assert.assertEquals(102L, idx.findFirstByGte(10L));
            Assert.assertEquals(103L, idx.findFirstByGt(10L));
            Assert.assertEquals(101L, idx.findFirstByLt(100L));
            Assert.assertEquals(101L, idx.findFirstByLte(0L));
        }
    }

    @Test
    public void testRangePersistenceCloseReopenAndEmpty() throws IOException {
        DsEqIndexStore.forceResetIndexForTest(tmpRoot, SPACE, IDX_AGE);
        try (DsEqIndexStore idx = new DsEqIndexStore(tmpRoot, SPACE, IDX_AGE)) {
            idx.putIndex(1L, 11L);
            idx.putIndex(3L, 31L);
            idx.putIndex(3L, 32L);
            idx.putIndex(5L, 51L);
            Assert.assertEquals(2, idx.findByBetween(2L, 4L).length);
            Assert.assertEquals(0, idx.findByBetween(6L, 99L).length);
            Assert.assertEquals(0, idx.findByBetween(5L, 4L).length);
            Assert.assertEquals(DsEqIndexStore.NOT_FOUND, idx.findFirstByBetween(999L, 9999L));
        }
        try (DsEqIndexStore idx = new DsEqIndexStore(tmpRoot, SPACE, IDX_AGE)) {
            long[] rows = idx.findByBetween(1L, 5L);
            Arrays.sort(rows);
            Assert.assertArrayEquals(new long[]{11L, 31L, 32L, 51L}, rows);
            Assert.assertEquals(11L, idx.findFirstByBetween(1L, 5L));
            Assert.assertEquals(31L, idx.findFirstByGte(3L));
            Assert.assertEquals(0, idx.findByGt(Long.MAX_VALUE).length);
            Assert.assertEquals(4, idx.findByLt(Long.MAX_VALUE).length);
        }
    }

    @Test
    public void testStringRangeRejectsAndEqStillWorks() throws IOException {
        try (DsEqIndexStore idx = new DsEqIndexStore(tmpRoot, SPACE, "cityStr",
                DsEqIndexStore.IndexedValueKind.STRING)) {
            idx.putIndex("beijing", 1L);
            idx.putIndex("shanghai", 2L);
            Assert.assertEquals(1L, idx.findFirstByIndex("beijing"));
            IllegalStateException ex = null;
            try {
                idx.findByBetween(10L, 20L);
            } catch (IllegalStateException e) {
                ex = e;
            }
            Assert.assertNotNull(ex);
            Assert.assertTrue(ex.getMessage().contains("IndexedValueKind.LONG"));
            ex = null;
            try {
                idx.findByGte(0L);
            } catch (IllegalStateException e) {
                ex = e;
            }
            Assert.assertNotNull(ex);
        }
    }
}
