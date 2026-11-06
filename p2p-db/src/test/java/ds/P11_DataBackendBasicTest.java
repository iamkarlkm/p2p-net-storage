package ds;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.registry.meta.AbstractRegistryBackend.YamlConsumer;
import com.q3lives.ds.registry.meta.AbstractRegistryBackend.YamlSupplier;
import com.q3lives.ds.registry.meta.AbstractRegistryDataBackend;
import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

/**
 * P11 T2 Backend 抽象 3 TRs（独立 tmp 根，不接 DsDatabaseLocal/ColumnarStore，test-only 子类验证 5 helper 自洽）
 * - TR-T2-R1：readLeafBytes/writeLeafBytes roundtrip 5 长度 × 10 次
 * - TR-T2-R2：HYBRID 回填模拟（bucket 有值 Registry 空 → 首次读返回 bucket → 断言下次 Registry 有）
 * - TR-T2-R3：atomicModifyBytes 8 线程 × 1000 次，字节级原子计数总和=8000
 */
public class P11_DataBackendBasicTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        // T2 只在默认 HYBRID 下跑（GLOBAL=HYBRID 是缺省，覆盖 Backend 路由的最多分支：双写双读+回填）
        // YAML/REGISTRY 单一 mode 验证留 T3/T6 真实 DsDatabaseLocal/ColumnarStore 介入时测更准确
        System.clearProperty(MetaMode.PROPERTY);
        // MetaMode GLOBAL 是 private static final（class load 时已 resolve），此处保证 property 清除即可
    }

    /**
     * Test-only Backend：bucket 是内存 byte[] holder（不落地，保证测试只验证 Registry 路由与字节正确性，
     * 不依赖 bucket 层，速度快）。
     * 由于 Backend 4 helper 是 protected（跨包 ds 不可直接访问），内部包装 public t_* 转发方法便于调用。
     */
    static class MemBackend extends AbstractRegistryDataBackend {
        final java.util.concurrent.ConcurrentHashMap<String, byte[]> mem =
            new java.util.concurrent.ConcurrentHashMap<>();
        MemBackend(File root) throws IOException { super(root); }

        public void t_write(String path, byte[] value, YamlConsumer<byte[]> cons) throws IOException {
            writeLeafBytes(path, value, cons);
        }
        public byte[] t_read(String path, YamlSupplier<byte[]> sup, byte[] def) throws IOException {
            return readLeafBytes(path, sup, def);
        }
        public boolean t_exists(String path, YamlSupplier<Boolean> sup) throws IOException {
            return leafBytesExists(path, sup);
        }
        public boolean t_remove(String path, YamlSupplier<Boolean> sup) throws IOException {
            return removeLeafBytes(path, sup);
        }
        public byte[] t_cas(String path, UnaryOperator<byte[]> op, byte[] seed,
                            YamlSupplier<byte[]> g, YamlConsumer<byte[]> s) throws IOException {
            return atomicModifyBytes(path, op, seed, g, s);
        }
        public com.q3lives.ds.registry.DsRegistry t_registry() { return registryReadOnlyForVerify(); }
    }

    @Test
    public void t01_backend_roundtrip() throws Exception {
        File root = tmp.newFolder("t2-r1");
        MemBackend be = new MemBackend(root);
        int[] lens = {0, 8, 128, 2048, 65536};
        for (int len : lens) {
            for (int i = 0; i < 10; i++) {
                byte[] payload = new byte[len];
                Arrays.fill(payload, (byte) ((len + i) & 0xFF));
                String path = RegistryMetaPaths.dataRowPayload("com.foo.T" + len, 1000L + i);
                // Registry 写 → bucket no-op（mem 存，这里 consumer 写 mem 以备读回）
                be.t_write(path, payload, b -> be.mem.put(path, b));
                byte[] back = be.t_read(path, () -> be.mem.get(path), null);
                assertNotNull("len=" + len + " i=" + i + " 不应为 null", back);
                assertArrayEquals("len=" + len + " i=" + i + " roundtrip 字节不一致", payload, back);

                // leafExists = true；remove 后 false
                assertTrue("exists=true", be.t_exists(path, () -> be.mem.containsKey(path)));
                assertTrue("remove ok", be.t_remove(path, () -> be.mem.remove(path) != null));
                assertFalse("after remove exists=false", be.t_exists(path, () -> be.mem.containsKey(path)));
            }
        }
    }

    @Test
    public void t02_hybrid_backfill() throws Exception {
        File root = tmp.newFolder("t2-r2");
        MemBackend be = new MemBackend(root);
        String entity = "com.foo.Backfill";
        long rowId = 999L;
        String path = RegistryMetaPaths.dataRowPayload(entity, rowId);
        byte[] bucketBytes = new byte[96];
        Arrays.fill(bucketBytes, (byte) 0xAB);

        // 预填 bucket（mem），Registry 此时空
        be.mem.put(path, bucketBytes);
        // 断言 Registry 空（通过只读引用 exists 判）
        assertFalse("首次 backfill 前 Registry 应空",
            be.t_registry().exists(path));

        // 首次读（Registry miss→bucket 返回→应触发回填）
        byte[] first = be.t_read(path, () -> be.mem.get(path), null);
        assertArrayEquals("首次读应返回 bucket 字节", bucketBytes, first);

        // 断言回填已成功写入 Registry（下次读 Registry 命中）
        assertTrue("回填后 Registry 应存在",
            be.t_registry().exists(path));
        byte[] rr = be.t_registry().get(path).asBytes();
        assertArrayEquals("回填 Registry 字节应等价 bucket", bucketBytes, rr);
    }

    @Test
    public void t03_atomic_modify_bytes_concurrent() throws Exception {
        File root = tmp.newFolder("t2-r3");
        MemBackend be = new MemBackend(root);
        String path = RegistryMetaPaths.dataRowPayload("com.foo.CAS", 1L);

        // seed = 1 字节 {0}，op = bytes[0] += 1 后回写
        byte[] seed = new byte[]{0};
        UnaryOperator<byte[]> increment = b -> {
            byte[] r = Arrays.copyOf(b, Math.max(1, b.length));
            r[0] = (byte) (r[0] + 1);
            return r;
        };

        int threads = 8;
        int perThread = 1000;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<Throwable> firstErr = new AtomicReference<>();
        for (int t = 0; t < threads; t++) {
            es.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < perThread; k++) {
                        be.t_cas(path, increment, seed,
                            () -> be.mem.get(path),
                            b -> be.mem.put(path, b));
                    }
                } catch (Throwable th) {
                    errors.incrementAndGet();
                    firstErr.compareAndSet(null, th);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue("CAS 并发 30s 内应完成", done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        es.shutdownNow();
        if (firstErr.get() != null) {
            throw new AssertionError("并发 CAS 异常", firstErr.get());
        }
        assertEquals("并发错误数应 0", 0, errors.get());

        // 最终结果：bytes[0] = threads * perThread（unsigned byte，如果 ≥256 则溢出则用 sum bytes）
        // 这里改：用 8-byte little-endian long 存 counter 避免 overflow（修改 op）
        // 简化：改用 sum bucket get byte[0] 做 unsigned，假设 threads*perThread = 8000 ≥ 256 会溢出。
        // → 改 path 存 8-byte long：
        String longPath = RegistryMetaPaths.dataRowPayload("com.foo.CASLong", 2L);
        byte[] longSeed = new byte[8]; // long little-endian 0
        UnaryOperator<byte[]> incLong = b -> {
            long v = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
            v += 1L;
            return java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(v).array();
        };
        CountDownLatch start2 = new CountDownLatch(1);
        CountDownLatch done2 = new CountDownLatch(threads);
        AtomicInteger err2 = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            es = Executors.newFixedThreadPool(threads);
            // 实际 es 已经 shutdown，重新 new：
        }
        ExecutorService es2 = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            final int myT = t;
            es2.submit(() -> {
                try {
                    start2.await();
                    for (int k = 0; k < perThread; k++) {
                        be.t_cas(longPath, incLong, longSeed,
                            () -> be.mem.get(longPath),
                            b -> be.mem.put(longPath, b));
                    }
                } catch (Throwable th) {
                    err2.incrementAndGet();
                    firstErr.compareAndSet(null, th);
                } finally {
                    done2.countDown();
                }
            });
        }
        start2.countDown();
        assertTrue("CAS long 并发 30s 内完成", done2.await(30, java.util.concurrent.TimeUnit.SECONDS));
        es2.shutdownNow();
        if (firstErr.get() != null) {
            throw new AssertionError("并发 CAS long 异常", firstErr.get());
        }
        assertEquals("long CAS 错误数 0", 0, err2.get());
        byte[] finalBytes = be.t_read(longPath, () -> be.mem.get(longPath), null);
        assertNotNull("final bytes 非空", finalBytes);
        long finalVal = java.nio.ByteBuffer.wrap(finalBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
        assertEquals("最终 long CAS 值应为 " + (threads * perThread),
            (long) threads * perThread, finalVal);
    }
}
