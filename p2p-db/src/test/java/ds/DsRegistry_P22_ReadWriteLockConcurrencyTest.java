package ds;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;

public class DsRegistry_P22_ReadWriteLockConcurrencyTest {

    @Test
    public void testMultiReaderSingleWriter_NoTornRead() throws Exception {
        File tmp = Files.createTempDirectory("dsregp22-").toFile();
        tmp.deleteOnExit();
        Path dir = new File(tmp, "cr1").toPath().toAbsolutePath();
        try (DsRegistry r = new DsRegistry(dir.toString())) {
            for (int i = 0; i < 32; i++) {
                r.putLong("/k" + i, DataType.REG_QWORD, -1L);
            }
            AtomicInteger errors = new AtomicInteger();
            AtomicLong reads = new AtomicLong();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(9);
            Thread writer = new Thread(() -> {
                try {
                    start.await();
                    long deadline = System.currentTimeMillis() + 2000L;
                    long v = 0L;
                    while (System.currentTimeMillis() < deadline) {
                        for (int i = 0; i < 32; i++) {
                            r.putLong("/k" + i, DataType.REG_QWORD, v);
                        }
                        v++;
                    }
                } catch (Throwable t) {
                    errors.incrementAndGet();
                    t.printStackTrace();
                } finally {
                    done.countDown();
                }
            }, "writer-1");
            Runnable reader = () -> {
                try {
                    start.await();
                    long deadline = System.currentTimeMillis() + 2000L;
                    while (System.currentTimeMillis() < deadline) {
                        for (int i = 0; i < 32; i++) {
                            RegistryEntry e = r.get("/k" + i);
                            if (!e.isPresent()) { errors.incrementAndGet(); continue; }
                            if (e.dataType() != DataType.REG_QWORD) errors.incrementAndGet();
                            long v = e.asLong();
                            if (v != -1L && v < 0L) errors.incrementAndGet();
                            reads.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    errors.incrementAndGet();
                    t.printStackTrace();
                } finally {
                    done.countDown();
                }
            };
            List<Thread> all = new ArrayList<>();
            all.add(writer);
            for (int ri = 0; ri < 8; ri++) all.add(new Thread(reader, "reader-" + ri));
            for (Thread t : all) t.start();
            start.countDown();
            done.await();
            for (Thread t : all) t.join();
            assertEquals(0, errors.get(), "并发期间出现 " + errors.get() + " 处异常/损坏");
            assertTrue(reads.get() >= 1000L, "读次数太少:" + reads.get());
        }
    }

    @Test
    public void testMultiWriter_SerializedVisibility() throws Exception {
        File tmp = Files.createTempDirectory("dsregp22-").toFile();
        tmp.deleteOnExit();
        Path dir = new File(tmp, "cr2").toPath().toAbsolutePath();
        try (DsRegistry r = new DsRegistry(dir.toString())) {
            int n = 201;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(3);
            AtomicInteger errs = new AtomicInteger();
            Thread t0 = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 67; i++) r.putString("/arr/" + i, "v-" + i + "-t0", DataType.REG_SZ);
                } catch (Throwable t) { errs.incrementAndGet(); t.printStackTrace(); }
                finally { done.countDown(); }
            }, "w0");
            Thread t1 = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 67; i < 134; i++) r.putString("/arr/" + i, "v-" + i + "-t1", DataType.REG_SZ);
                } catch (Throwable t) { errs.incrementAndGet(); t.printStackTrace(); }
                finally { done.countDown(); }
            }, "w1");
            Thread t2 = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 134; i < n; i++) r.putString("/arr/" + i, "v-" + i + "-t2", DataType.REG_SZ);
                } catch (Throwable t) { errs.incrementAndGet(); t.printStackTrace(); }
                finally { done.countDown(); }
            }, "w2");
            t0.start(); t1.start(); t2.start();
            start.countDown();
            done.await();
            t0.join(); t1.join(); t2.join();
            assertEquals(0, errs.get(), "写入异常");
            for (int i = 0; i < n; i++) {
                RegistryEntry e = r.get("/arr/" + i);
                assertTrue(e.isPresent(), "key 丢失:/arr/" + i);
                assertEquals(DataType.REG_SZ, e.dataType(), "dataType 不对 i=" + i);
            }
        }
    }

    @Test
    public void testClose_RejectsSubsequentCalls() throws Exception {
        File tmp = Files.createTempDirectory("dsregp22-").toFile();
        tmp.deleteOnExit();
        Path dir = new File(tmp, "cr3").toPath().toAbsolutePath();
        DsRegistry r = new DsRegistry(dir.toString());
        r.putLong("/x", DataType.REG_DWORD, 100L);
        r.close();
        assertThrows(IllegalStateException.class, () -> r.get("/x"));
        assertThrows(IllegalStateException.class, () -> r.putLong("/y", DataType.REG_DWORD, 200L));
        assertThrows(IllegalStateException.class, () -> r.exists("/x"));
        assertThrows(IllegalStateException.class, r::sync);
    }
}
