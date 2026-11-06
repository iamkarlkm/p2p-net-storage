package ds;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.bucket.DsData;

/**
 * P8 NG-1 Performance First 退化定量验证：
 *   striped indexLocks（CHM per-indexId + synchronized per lock）在单线程热路径 put dedup 100%
 *   命中场景下，锁相关开销（computeIfAbsent + monitor enter/exit）占**全链路总耗时**
 *   的比例 < 5%。
 *
 * 零侵入设计（不修改 DsData 源码，不篡改 CHM，不破坏 final 语义）：
 *   Phase A — 测全链路总耗时 T_total：同一 DsData 实例 单线程 put 同 8B value 200K 次
 *              （1 次真实分配 + 199,999 次 dedup 命中热路径，锁段必进）。
 *   Phase B — 测锁裸开销 T_lock：反射拿到 instance.indexLocks CHM 引用，单线程循环
 *              200K 次 `indexLocks.computeIfAbsent(fixedIndexId, k -> new Object())`
 *              紧跟 `synchronized(returnedLock){ // noop }`，模拟 put dedup 锁段
 *              的 100% 等价 monitor 开销。
 *   ratio = T_lock_best3_avg / T_total_best3_avg < 0.05 → PASS。
 *
 * Windows I/O bound 注意：put 首分配含 bucketStore.put + masterIndex.put，
 * 其余 199,999 次 dedup 只含：masterIndex.get(bytes) + readIndexRecord(16B RAF) +
 * Arrays.equals(8B) + indexLocks.computeIfAbsent + synchronized{} + re-readIndexRecord(16B)
 * + overwrite(16B RAF)。全部在 Windows 页缓存命中（同一 tmp，短时间内连续），
 * 真实 I/O < 1%，锁开销占比会被放大（更严苛），ratio <5% 可稳定通过。
 */
public class DsString_DsData_P8_NG1_DegradationBench {

    private static final int N = 200_000;
    private static final int RUNS = 5;
    private static final int BEST_K = 3;
    private static final double RATIO_LIMIT = 0.05;

    private File tmp;
    private DsData data;
    private byte[] value;
    private ConcurrentHashMap<Long, Object> indexLocksRef;
    private long warmIndexId;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        tmp = Files.createTempDirectory("dsdatang1-").toFile();
        tmp.deleteOnExit();
        data = new DsData(tmp.getAbsolutePath(), "string_store");
        value = new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte)0x88};
        warmIndexId = data.put(value);
        Field f = DsData.class.getDeclaredField("indexLocks");
        f.setAccessible(true);
        indexLocksRef = (ConcurrentHashMap<Long, Object>) f.get(data);
        for (int i = 0; i < 5_000; i++) {
            data.put(value);
            Object lock = indexLocksRef.computeIfAbsent(warmIndexId, k -> new Object());
            synchronized (lock) {
                // noop warmup JIT C2
            }
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        if (data != null) data.close();
    }

    @Test
    void test_NG1_StripedLocks_DedupOverhead_LT_5_Percent() throws Exception {
        long[] totalTimes = new long[RUNS];
        long[] lockTimes = new long[RUNS];

        for (int r = 0; r < RUNS; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) {
                long id = data.put(value);
                if (id != warmIndexId) {
                    throw new AssertionError("dedup fail round " + r + ": expected=" + warmIndexId + " got=" + id);
                }
            }
            totalTimes[r] = System.nanoTime() - t0;

            long[] t1 = new long[1];
            t1[0] = System.nanoTime();
            long id = warmIndexId;
            ConcurrentHashMap<Long, Object> chm = indexLocksRef;
            for (int i = 0; i < N; i++) {
                Object lock = chm.computeIfAbsent(id, k -> new Object());
                synchronized (lock) {
                    // noop
                }
            }
            lockTimes[r] = System.nanoTime() - t1[0];
        }

        long totalBest = bestK(totalTimes, BEST_K);
        long lockBest = bestK(lockTimes, BEST_K);
        double ratio = (double) lockBest / (double) totalBest;
        long totalOpsPerSec = (long) ((double) N * 1_000_000_000.0 / (double) totalBest);
        long lockOpsPerSec = (long) ((double) N * 1_000_000_000.0 / (double) lockBest);

        StringBuilder sb = new StringBuilder(400);
        sb.append("\n==== P8 NG-1 Striped Locks Degradation Benchmark ====\n");
        sb.append("N=").append(N).append(" RUNS=").append(RUNS).append(" BEST_K=").append(BEST_K).append('\n');
        sb.append("Total_times_ns[best ").append(BEST_K).append("]_avg=").append(totalBest).append(" ns/op=").append(String.format("%.2f", totalBest/(double)N)).append("ns  throughput=").append(totalOpsPerSec).append(" ops/s\n");
        sb.append("Lock_times_ns[best ").append(BEST_K).append("]_avg=").append(lockBest).append(" ns/op=").append(String.format("%.2f", lockBest/(double)N)).append("ns  throughput=").append(lockOpsPerSec).append(" ops/s\n");
        sb.append("Ratio=lock/total=").append(String.format("%.4f", ratio)).append(" = ").append(String.format("%.2f%%", ratio*100.0)).append("  LIMIT=").append(String.format("%.0f%%", RATIO_LIMIT*100.0)).append('\n');
        sb.append("Arrays.equals(all_times)=").append(Arrays.toString(totalTimes)).append('\n');
        sb.append("Arrays.equals(lock_times)=").append(Arrays.toString(lockTimes)).append('\n');
        System.out.println(sb.toString());

        assertTrue(ratio < RATIO_LIMIT,
            "NG-1 Performance First violated: striped lock overhead ratio=" +
            String.format("%.2f%%", ratio*100.0) +
            " >= LIMIT=" + String.format("%.0f%%", RATIO_LIMIT*100.0));
    }

    private static long bestK(long[] arr, int k) {
        long[] sorted = arr.clone();
        Arrays.sort(sorted);
        long sum = 0;
        for (int i = 0; i < k && i < sorted.length; i++) sum += sorted[i];
        return sum / Math.min(k, sorted.length);
    }
}
