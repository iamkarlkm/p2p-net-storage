package ds;

import com.q3lives.ds.collections.DsHashSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class DsHashSetConcurrentTest {

    private DsHashSet dsHashSet;
    private File dataFile;

    @Before
    public void setUp() throws Exception {
        dataFile = new File("test_dshashset_concurrent.dat");
        if (dataFile.exists()) {
            dataFile.delete();
        }
        dsHashSet = new DsHashSet(dataFile);
    }

    @After
    public void tearDown() throws Exception {
        dsHashSet.close();
    }

    @Test
    public void testConcurrentReadWriteAndIterate() throws Exception {
        int writers = 4;
        int readers = 2;
        int perWriter = 2000;

        ExecutorService pool = Executors.newFixedThreadPool(writers + readers + 1);
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
                        dsHashSet.add(base + i);
                    }
                    for (int i = 0; i < perWriter; i += 2) {
                        dsHashSet.remove(base + i);
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
                        Iterator<Long> it = dsHashSet.iterator();
                        int steps = 0;
                        while (it.hasNext() && steps < 200) {
                            Long v = it.next();
                            assertNotNull(v);
                            steps++;
                        }
                        dsHashSet.size();
                        dsHashSet.isEmpty();
                    }
                } catch (Throwable e) {
                    err.compareAndSet(null, e);
                }
            });
        }

        start.countDown();
        writersDone.await(20, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(20, TimeUnit.SECONDS);

        if (err.get() != null) {
            throw new AssertionError(err.get());
        }

        for (int t = 0; t < writers; t++) {
            long base = (long) t * 1_000_000L;
            for (int i = 0; i < perWriter; i++) {
                boolean shouldExist = (i % 2) == 1;
                assertEquals(shouldExist, dsHashSet.contains(base + i));
            }
        }
    }
}
