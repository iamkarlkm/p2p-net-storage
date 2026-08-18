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

public class DsEqIndexStoreTest {

    private File tmpRoot;
    private static final String SPACE = "com.test.UserEvent";
    private static final String IDX_STATUS = "status";

    @Before
    public void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("dsdb-eqidx-").toFile();
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

    @Test
    public void testPutAndFindFirst() throws IOException {
        try (DsEqIndexStore idx = new DsEqIndexStore(tmpRoot, SPACE, IDX_STATUS)) {
            long rowInflight = 1001L;
            long rowFailed = 2002L;
            long rowDone = 3003L;
            int V_INFLIGHT = 1;
            int V_FAILED = 3;
            int V_DONE = 5;
            idx.putIndex(V_INFLIGHT, rowInflight);
            idx.putIndex(V_FAILED, rowFailed);
            idx.putIndex(V_DONE, rowDone);

            Assert.assertEquals(rowInflight, idx.findFirstByIndex(V_INFLIGHT));
            Assert.assertEquals(rowFailed, idx.findFirstByIndex(V_FAILED));
            Assert.assertEquals(rowDone, idx.findFirstByIndex(V_DONE));

            long[] r = idx.findByIndex(V_FAILED);
            Assert.assertEquals(1, r.length);
            Assert.assertEquals(rowFailed, r[0]);

            Assert.assertTrue(idx.containsIndex(V_INFLIGHT));
            Assert.assertTrue(idx.containsIndex(V_FAILED));
            Assert.assertTrue(idx.containsIndex(V_DONE));
            Assert.assertTrue(idx.containsIndex(V_FAILED, rowFailed));
            Assert.assertFalse(idx.containsIndex(V_FAILED, rowDone));

            Assert.assertEquals(3L, idx.size());
        }
    }

    @Test
    public void testMultipleRowIdsForSameValue() throws IOException {
        try (DsEqIndexStore idx = new DsEqIndexStore(tmpRoot, SPACE, IDX_STATUS)) {
            int V_FAILED = 3;
            long rowA = 1111L;
            long rowB = 2222L;
            long rowC = 3333L;

            idx.putIndex(V_FAILED, rowA);
            idx.putIndex(V_FAILED, rowB);
            idx.putIndex(V_FAILED, rowC);

            Assert.assertEquals(3, idx.findByIndex(V_FAILED).length);
            Assert.assertEquals(1L, idx.size());

            // 幂等：重复 put 同 (value,rowId) 不增加
            idx.putIndex(V_FAILED, rowB);
            long[] arr = idx.findByIndex(V_FAILED);
            Assert.assertEquals(3, arr.length);
            Set<Long> ids = new HashSet<>();
            for (long r : arr) ids.add(r);
            Assert.assertEquals(new HashSet<>(Arrays.asList(rowA, rowB, rowC)), ids);

            Assert.assertTrue(idx.containsIndex(V_FAILED, rowA));
            Assert.assertTrue(idx.containsIndex(V_FAILED, rowB));
            Assert.assertTrue(idx.containsIndex(V_FAILED, rowC));
        }
    }

    @Test
    public void testRemoveSpecificRowIdKeepsOthers() throws IOException {
        try (DsEqIndexStore idx = new DsEqIndexStore(tmpRoot, SPACE, IDX_STATUS)) {
            int V_FAILED = 3;
            long rowA = 1111L;
            long rowB = 2222L;
            long rowC = 3333L;
            idx.putIndex(V_FAILED, rowA);
            idx.putIndex(V_FAILED, rowB);
            idx.putIndex(V_FAILED, rowC);

            Assert.assertTrue(idx.removeIndex(V_FAILED, rowB));
            Assert.assertFalse(idx.removeIndex(V_FAILED, rowB));

            long[] arr = idx.findByIndex(V_FAILED);
            Assert.assertEquals(2, arr.length);
            Set<Long> ids = new HashSet<>();
            for (long r : arr) ids.add(r);
            Assert.assertEquals(new HashSet<>(Arrays.asList(rowA, rowC)), ids);

            Assert.assertTrue(idx.containsIndex(V_FAILED, rowA));
            Assert.assertFalse(idx.containsIndex(V_FAILED, rowB));
            Assert.assertTrue(idx.containsIndex(V_FAILED, rowC));
            Assert.assertEquals(1L, idx.size());

            // 删除最后一个 rowId 后索引值条目也消失
            Assert.assertTrue(idx.removeIndex(V_FAILED, rowA));
            Assert.assertTrue(idx.removeIndex(V_FAILED, rowC));
            Assert.assertEquals(0, idx.findByIndex(V_FAILED).length);
            Assert.assertEquals(DsEqIndexStore.NOT_FOUND, idx.findFirstByIndex(V_FAILED));
            Assert.assertFalse(idx.containsIndex(V_FAILED));
            Assert.assertEquals(0L, idx.size());
        }
    }

    @Test
    public void testRemoveAllRowIdsForValue() throws IOException {
        try (DsEqIndexStore idx = new DsEqIndexStore(tmpRoot, SPACE, IDX_STATUS)) {
            int V_FAILED = 3;
            long rowA = 1111L;
            long rowB = 2222L;
            idx.putIndex(V_FAILED, rowA);
            idx.putIndex(V_FAILED, rowB);

            Assert.assertTrue(idx.removeIndex(V_FAILED));
            Assert.assertFalse(idx.removeIndex(V_FAILED));
            Assert.assertEquals(0, idx.findByIndex(V_FAILED).length);
            Assert.assertEquals(0L, idx.size());
        }
    }

    @Test
    public void testIndexPersistenceCloseReopen() throws IOException {
        int V_A = 10;
        int V_B = 20;
        long R_A1 = 1234L;
        long R_A2 = 1235L;
        long R_B = 5678L;
        try (DsEqIndexStore idx = new DsEqIndexStore(tmpRoot, SPACE, IDX_STATUS)) {
            idx.putIndex(V_A, R_A1);
            idx.putIndex(V_A, R_A2);
            idx.putIndex(V_B, R_B);
        }

        try (DsEqIndexStore reopened = new DsEqIndexStore(tmpRoot, SPACE, IDX_STATUS)) {
            long[] arrA = reopened.findByIndex(V_A);
            Assert.assertEquals(2, arrA.length);
            Set<Long> idsA = new HashSet<>();
            for (long r : arrA) idsA.add(r);
            Assert.assertEquals(new HashSet<>(Arrays.asList(R_A1, R_A2)), idsA);

            Assert.assertEquals(R_B, reopened.findFirstByIndex(V_B));

            Assert.assertEquals(2L, reopened.size());
            Assert.assertTrue(reopened.containsIndex(V_A));
            Assert.assertTrue(reopened.containsIndex(V_B));
        }

        File freshRoot = Files.createTempDirectory("dsdb-eqidx-fresh-").toFile();
        try (Stream<Path> walk = Files.walk(freshRoot.toPath())) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
        try (DsEqIndexStore clean = new DsEqIndexStore(freshRoot, SPACE, IDX_STATUS)) {
            Assert.assertEquals("fresh root should have empty index", 0L, clean.size());
            Assert.assertEquals(DsEqIndexStore.NOT_FOUND, clean.findFirstByIndex(V_A));
            Assert.assertEquals(DsEqIndexStore.NOT_FOUND, clean.findFirstByIndex(V_B));
            Assert.assertFalse(clean.containsIndex(V_A));
            Assert.assertFalse(clean.containsIndex(V_B));
        } finally {
            try (Stream<Path> walk = Files.walk(freshRoot.toPath())) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }
}
