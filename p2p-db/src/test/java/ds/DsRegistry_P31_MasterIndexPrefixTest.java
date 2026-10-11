package ds;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;

public class DsRegistry_P31_MasterIndexPrefixTest {

    @Test
    void testFindByPrefixShortCircuitLimitOne() throws Exception {
        File tmp = Files.createTempDirectory("dsregp31a-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(tmp.getAbsolutePath())) {
            for (int i = 0; i < 1000; i++) {
                r.putLong("/keys/k" + i, DataType.REG_DWORD, i);
            }
            List<String> one = r.findByPrefixKeys("/keys/k", Integer.MAX_VALUE, 1, null);
            assertEquals(1, one.size());
            assertTrue(one.get(0).startsWith("/keys/k"));
            List<String> ten = r.findByPrefixKeys("/keys/k", Integer.MAX_VALUE, 10, null);
            assertEquals(10, ten.size());
            for (String k : ten) assertTrue(k.startsWith("/keys/k"), k);
        }
    }

    @Test
    void testFindByPrefixNonDirPrefixMatch() throws Exception {
        File tmp = Files.createTempDirectory("dsregp31b-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(tmp.getAbsolutePath())) {
            r.putString("/cfg/app.conf.host", "h1", DataType.REG_SZ);
            r.putString("/cfg/app.conf.port", "8080", DataType.REG_SZ);
            r.putString("/cfg/app.profile", "dev", DataType.REG_SZ);
            r.putString("/cfg/app.properties", "x=1", DataType.REG_SZ);
            List<String> keys = r.findByPrefixKeys("/cfg/app.conf", Integer.MAX_VALUE, 100, null);
            Collections.sort(keys);
            assertEquals(2, keys.size(), keys.toString());
            assertEquals("/cfg/app.conf.host", keys.get(0));
            assertEquals("/cfg/app.conf.port", keys.get(1));
        }
    }

    @Test
    void testFindByPrefixDefaultOverload() throws Exception {
        File tmp = Files.createTempDirectory("dsregp31c-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(tmp.getAbsolutePath())) {
            for (int i = 0; i < 50; i++) r.putLong("/a/n" + i, DataType.REG_DWORD, i);
            List<com.q3lives.ds.registry.RegistryEntry> all = r.findByPrefix("/a");
            assertTrue(all.size() >= 50);
            for (com.q3lives.ds.registry.RegistryEntry e : all) {
                assertTrue(e.key().startsWith("/a"), e.key());
            }
        }
    }

    @Test
    void testLargeSetPrefixPerformanceConsistent() throws Exception {
        File tmp = Files.createTempDirectory("dsregp31d-").toFile();
        tmp.deleteOnExit();
        final int N = 3000;
        try (DsRegistry r = new DsRegistry(tmp.getAbsolutePath())) {
            for (int i = 0; i < N; i++) {
                long bucket = i % 3;
                String k = "/b" + bucket + "/item-" + String.format("%06d", i);
                r.putLong(k, DataType.REG_DWORD, i);
            }
            List<String> all = new ArrayList<>();
            List<String> b0 = r.findByPrefixKeys("/b0", Integer.MAX_VALUE, Integer.MAX_VALUE, null);
            all.addAll(b0);
            for (String k : b0) assertTrue(k.startsWith("/b0/item-"), k);
            List<String> b1 = r.findByPrefixKeys("/b1", Integer.MAX_VALUE, Integer.MAX_VALUE, null);
            all.addAll(b1);
            for (String k : b1) assertTrue(k.startsWith("/b1/item-"), k);
            List<String> b2 = r.findByPrefixKeys("/b2", Integer.MAX_VALUE, Integer.MAX_VALUE, null);
            all.addAll(b2);
            for (String k : b2) assertTrue(k.startsWith("/b2/item-"), k);
            assertTrue(b0.size() >= N / 4, "b0.size=" + b0.size());
            assertTrue(b1.size() >= N / 4, "b1.size=" + b1.size());
            assertTrue(b2.size() >= N / 4, "b2.size=" + b2.size());
            assertEquals(N, all.size());
        }
    }

    @Test
    void testConcurrentReadWriteNoTornPrefix() throws Exception {
        File tmp = Files.createTempDirectory("dsregp31e-").toFile();
        tmp.deleteOnExit();
        final int N = 2000;
        try (DsRegistry r = new DsRegistry(tmp.getAbsolutePath())) {
            for (int i = 0; i < N; i++) r.putLong("/r/item-" + i, DataType.REG_DWORD, i);
            final int READERS = 2;
            final int ITERS = 30;
            final int WRITES = 60;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(READERS + 1);
            AtomicReference<Throwable> err = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < WRITES; i++) {
                        r.putLong("/r/w-" + i, DataType.REG_DWORD, 100000 + i);
                    }
                } catch (Throwable t) { err.compareAndSet(null, t); }
                finally { done.countDown(); }
            });
            writer.start();
            List<Thread> readers = new ArrayList<>();
            for (int tid = 0; tid < READERS; tid++) {
                final int myId = tid;
                Thread th = new Thread(() -> {
                    try {
                        start.await();
                        for (int it = 0; it < ITERS; it++) {
                            List<String> keys = r.findByPrefixKeys("/r/item-", Integer.MAX_VALUE, Integer.MAX_VALUE, null);
                            if (keys.size() < N) {
                                throw new AssertionError("size=" + keys.size() + " tid=" + myId);
                            }
                            for (String k : keys) {
                                assertNotNull(k);
                                assertTrue(k.startsWith("/r/item-") || k.startsWith("/r/w-"), k);
                            }
                        }
                    } catch (Throwable t0) { err.compareAndSet(null, t0); }
                    finally { done.countDown(); }
                });
                readers.add(th);
                th.start();
            }
            start.countDown();
            done.await();
            if (err.get() != null) {
                throw new AssertionError(err.get());
            }
        }
    }
}
