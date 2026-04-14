package ds;

import com.q3lives.ds.collections.DsHashMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class DsHashMapConcurrentTest {

    private DsHashMap dsHashMap;
    private File dataFile;

    @Before
    public void setUp() throws Exception {
        dataFile = new File("test_dshashmap_concurrent.dat");
        deleteWithSidecars(dataFile);
        dsHashMap = new DsHashMap(dataFile);
    }

    private static void deleteWithSidecars(File f) {
        File[] files = new File[] {
                f,
                new File(f.getAbsolutePath() + ".k16"),
                new File(f.getAbsolutePath() + ".k32"),
                new File(f.getAbsolutePath() + ".k64"),
                new File(f.getAbsolutePath() + ".m32"),
                new File(f.getAbsolutePath() + ".m64")
        };
        for (File x : files) {
            if (x.exists()) {
                x.delete();
            }
        }
    }

    @After
    public void tearDown() throws Exception {
        dsHashMap.close();
    }

    @Test
    public void testConcurrentPutGetRemove() throws Exception {
        int writers = 4;
        int readers = 2;
        int perWriter = 2000;

        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(writers);

        for (int t = 0; t < writers; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    long base = (long) tid * 1_000_000L;
                    for (int i = 0; i < perWriter; i++) {
                        dsHashMap.put(base + i, (base + i) * 10L);
                    }
                    for (int i = 0; i < perWriter; i += 2) {
                        dsHashMap.remove(base + i);
                    }
                } catch (Throwable e) {
                    err.compareAndSet(null, e);
                } finally {
                    writersDone.countDown();
                }
            });
        }

        for (int t = 0; t < readers; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (writersDone.getCount() > 0) {
                        dsHashMap.size();
                        long v = dsHashMap.get(1L) == null ? 0L : 1L;
                        if (v == 1L) {
                            assertNotNull(dsHashMap.get(1L));
                        }
                    }
                } catch (Throwable e) {
                    err.compareAndSet(null, e);
                }
            });
        }

        start.countDown();
        writersDone.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        if (err.get() != null) {
            throw new AssertionError(err.get());
        }

        for (int t = 0; t < writers; t++) {
            long base = (long) t * 1_000_000L;
            for (int i = 0; i < perWriter; i++) {
                Long got = dsHashMap.get(base + i);
                if ((i % 2) == 0) {
                    assertNull(got);
                } else {
                    assertNotNull(got);
                    assertEquals((base + i) * 10L, got.longValue());
                }
            }
        }
    }
}
