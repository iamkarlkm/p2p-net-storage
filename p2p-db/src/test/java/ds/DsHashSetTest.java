package ds;

import com.q3lives.ds.collections.DsHashSetI64;
import com.q3lives.ds.collections.DsMemorySet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

import static org.junit.Assert.*;
import zio.Duration;

@Slf4j
public class DsHashSetTest {

    private DsHashSetI64 dsHashSet;
    private File dataFile;

    @Before
    public void setUp() throws Exception {
        dataFile = new File("test_dshashset.dat");
        if (dataFile.exists()) {
            //dataFile.delete();
           
        }
//        dsHashSet = new DsHashSetI64_Fixed(dataFile);
        dsHashSet = new DsHashSetI64(dataFile);
        dsHashSet.clear();
    }

    @After
    public void tearDown() throws Exception {
        dsHashSet.close();
        if (dataFile.exists()) {
            dataFile.delete();
        }
    }

    @Test
    public void testAddContainsRemove() throws Exception {
        assertTrue(dsHashSet.isEmpty());
        assertEquals(0, dsHashSet.size());

        assertTrue(dsHashSet.add(1L));
        assertTrue(dsHashSet.contains(1L));
        assertEquals(1, dsHashSet.size());

        assertFalse(dsHashSet.add(1L));
        assertEquals(1, dsHashSet.size());

        assertTrue(dsHashSet.add(2L));
        assertTrue(dsHashSet.contains(2L));
        assertEquals(2, dsHashSet.size());

        assertTrue(dsHashSet.remove(1L));
        assertFalse(dsHashSet.contains(1L));
        assertEquals(1, dsHashSet.size());

        assertFalse(dsHashSet.remove(1L));
        //assertFalse(dsHashSet.remove("x"));
    }
    
    @Test
    public void testBigstore() throws Exception {
         DsMemorySet mset = new DsMemorySet(null);
        int count = 50000;
         System.gc();
        long memBefore = getUsedMemory();
        long gcBefore = getGCCount();

        long start = System.nanoTime();
//        long start = System.currentTimeMillis();
        for(long i=-count;i<count;i++){
            dsHashSet.add(i);
        }
        
          long duration = System.nanoTime() - start;
        long memAfter = getUsedMemory();
        System.gc();
        
        long gcAfter = getGCCount();

        BenchmarkResult test = new BenchmarkResult(
            "ds",
            duration,
            count*2,
            memAfter - memBefore,
            gcAfter - gcBefore
        );
      test.print();
        printFastPutStats(dsHashSet);
        System.out.println(dsHashSet.contains(-50000L)+" ************** "+dsHashSet.total()+" ************** "+dsHashSet.getStoreUsed());
        System.out.println(dsHashSet.first()+" -> "+dsHashSet.last()+" range(0, 10): "+dsHashSet.range(0, 10));
         System.out.println("value -50000: "+dsHashSet.contains(-50000L));
         System.out.println("state: "+dsHashSet.readState(7, 176));
            System.out.println("getByNodeId(7, 176): "+dsHashSet.getByNodeId(7, 176));
             System.out.println("value -50000 hash pash: "+dsHashSet.debugDumpJson(-50000L));
             System.out.println("value -259 hash pash: "+dsHashSet.debugDumpJson(-259L));
        Set<Long> set = new HashSet();
         System.gc();
        memBefore = getUsedMemory();
        gcBefore = getGCCount();

        start = System.nanoTime();
        for(long i=-count;i<count;i++){
            set.add(i);
//            count++;
        }
      
        duration = System.nanoTime() - start;
        memAfter = getUsedMemory();
      
        System.gc();
        
        gcAfter = getGCCount();

        test = new BenchmarkResult(
            "set",
            duration,
            count*2,
            memAfter - memBefore,
            gcAfter - gcBefore
        );
       test.print();
       
      
      
       System.gc();
        memBefore = getUsedMemory();
        gcBefore = getGCCount();
System.out.println(set.size()+" ************** ");
        start = System.nanoTime();
        for(int i=-count;i<count;i++){
            mset.add(i);
//            count++;
        }
        duration = System.nanoTime() - start;
        memAfter = getUsedMemory();
        System.gc();
        
        gcAfter = getGCCount(); ByteBuffer buf;

        test = new BenchmarkResult(
            "mset",
            duration,
            count*2,
            memAfter - memBefore,
            gcAfter - gcBefore
        );
       test.print();
        printFastPutStats(mset);
        System.out.println(mset.total()+" ************** "+mset.getStoreUsed());
        System.out.println(mset.first()+" -> "+mset.last());
         dsHashSet.clear();
    }

    @Test
    public void testFastPutQuickCacheBenchmark() throws Exception {
        long[] prefixes = {
            0x0102030405000000L,
            0x1112131415000000L,
            0x2122232425000000L,
            0x3132333435000000L
        };
        int rounds = 4096;

        DsHashSetI64.FastPutStats stats = runFastPutBenchmark(
            "ds-fast-put-multiprefix",
            null,
            prefixes.length * rounds,
            () -> {
                for (int round = 0; round < rounds; round++) {
                    long suffix = ((long) (round & 0xFF) << 8) | ((round >>> 2) & 0xFF);
                    for (long prefix : prefixes) {
                        dsHashSet.add(prefix | suffix);
                    }
                }
            }
        );

        assertNotNull(stats);
        assertTrue(stats.lastHitCount() > 0);
        assertTrue(stats.quickCacheSize() > 0);
        for (int round = 0; round < Math.min(rounds, 256); round++) {
            long suffix = ((long) (round & 0xFF) << 8) | ((round >>> 2) & 0xFF);
            for (long prefix : prefixes) {
                assertTrue(dsHashSet.contains(prefix | suffix));
            }
        }
    }

    @Test
    public void testFastPutScenarioComparison() throws Exception {
        int count = 20_000;
        DsHashSetI64.FastPutStats sequential = runFastPutBenchmark(
            "ds-fast-put-sequential",
            null,
            count,
            () -> {
                for (long i = 0; i < count; i++) {
                    dsHashSet.add(i);
                }
            }
        );

        long[] prefixes = {
            0x0102030405000000L,
            0x1112131415000000L,
            0x2122232425000000L,
            0x3132333435000000L
        };
        int rounds = 4096;
        DsHashSetI64.FastPutStats multiPrefix = runFastPutBenchmark(
            "ds-fast-put-compare-multiprefix",
            null,
            prefixes.length * rounds,
            () -> {
                for (int round = 0; round < rounds; round++) {
                    long suffix = ((long) (round & 0xFF) << 8) | ((round >>> 2) & 0xFF);
                    for (long prefix : prefixes) {
                        dsHashSet.add(prefix | suffix);
                    }
                }
            }
        );

        log.info(
            "FastPut场景对比: sequential(last={}, quick={}, miss={}) vs multiprefix(last={}, quick={}, miss={})",
            sequential.lastHitCount(),
            sequential.quickHitCount(),
            sequential.missCount(),
            multiPrefix.lastHitCount(),
            multiPrefix.quickHitCount(),
            multiPrefix.missCount()
        );
        assertTrue(sequential.lastHitCount() > 0);
        assertTrue(multiPrefix.quickCacheSize() > 0);
    }

    @Test
    public void testFastPutQuickCacheSizeTuning() throws Exception {
        int[] capacities = {32, 64, 128, 256};
        long[] prefixes = {
            0x0102030405000000L,
            0x1112131415000000L,
            0x2122232425000000L,
            0x3132333435000000L
        };
        int rounds = 4096;
        List<String> csv = new ArrayList<>();
        List<FastPutBenchmarkResult> results = new ArrayList<>();
        csv.add("capacity,throughput,lastHit,quickHit,miss,rejected,invalidated,quickSize,quickCapacity");
        for (int capacity : capacities) {
            FastPutBenchmarkResult result = runFastPutBenchmarkResult(
                "ds-fast-put-size-" + capacity,
                capacity,
                prefixes.length * rounds,
                () -> {
                    for (int round = 0; round < rounds; round++) {
                        long suffix = ((long) (round & 0xFF) << 8) | ((round >>> 2) & 0xFF);
                        for (long prefix : prefixes) {
                            dsHashSet.add(prefix | suffix);
                        }
                    }
                }
            );
            DsHashSetI64.FastPutStats stats = result.stats();
            assertEquals(capacity, stats.quickCacheCapacity());
            results.add(result);
            csv.add(toFastPutCsv(result));
        }
        log.info("FastPutCapacityCSV:\n{}", String.join("\n", csv));
        FastPutBenchmarkResult best = findBestFastPutResult(results);
        log.info(
            "FastPutCapacityBest=capacity={},throughput={},quickHit={},lastHit={},miss={},summary={}",
            best.stats().quickCacheCapacity(),
            best.throughputOps(),
            best.stats().quickHitCount(),
            best.stats().lastHitCount(),
            best.stats().missCount(),
            summarizeFastPut(best.stats(), best.totalLookups(), best.lastPercent(), best.quickPercent())
        );
    }
    
     /**
     *
     * 获取已使用内存（字节）
     */
    private static long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     *
     * 获取GC次数
     */
    private static long getGCCount() {
        long count = 0;
        for (java.lang.management.GarbageCollectorMXBean gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            count += gc.getCollectionCount();
        }
        return count;
    }

    /**
     *
     * 基准测试结果
     */
    static class BenchmarkResult {

        final String name;
        final long durationNs;
        final int iterations;
        final long memoryUsed;
        final long gcCount;

        BenchmarkResult(String name, long durationNs, int iterations, long memoryUsed, long gcCount) {
            this.name = name;
            this.durationNs = durationNs;
            this.iterations = iterations;
            this.memoryUsed = memoryUsed;
            this.gcCount = gcCount;
        }

        void print() {
            log.info("=== {} ===", name);
            log.info("总耗时: {} ms", durationNs / 1_000_000);
            log.info("平均耗时: {} ns/op", durationNs / iterations);
            log.info("吞吐量: {} ops/s", (iterations * 1_000_000_000L) / durationNs);
            log.info("内存占用: {} MB", memoryUsed / (1024 * 1024));
            log.info("GC次数: {}", gcCount);
        }
    }

    private void printFastPutStats(DsHashSetI64 dsHashSet) {
        DsHashSetI64.FastPutStats stats = dsHashSet.getFastPutStats();
        Map<String, Object> statsMap = dsHashSet.getFastPutStatsMap();
        long totalLookups = stats.lastHitCount() + stats.quickHitCount() + stats.missCount();
        long hitPercent = totalLookups == 0 ? 0 : ((stats.lastHitCount() + stats.quickHitCount()) * 100 / totalLookups);
        long lastPercent = totalLookups == 0 ? 0 : (stats.lastHitCount() * 100 / totalLookups);
        long quickPercent = totalLookups == 0 ? 0 : (stats.quickHitCount() * 100 / totalLookups);
        log.info("FastPutStats: {}", statsMap);
        log.info("FastPut命中率: {}% (last={}, quick={}, miss={})", hitPercent, stats.lastHitCount(), stats.quickHitCount(), stats.missCount());
        log.info("FastPut校验失败/淘汰: rejected={}, invalidated={}", stats.rejectedCount(), stats.invalidatedCount());
        log.info("FastPut摘要: {}", summarizeFastPut(stats, totalLookups, lastPercent, quickPercent));
    }

    private void printFastPutStats(DsMemorySet memorySet) {
        DsMemorySet.FastPutStats stats = memorySet.getFastPutStats();
        Map<String, Object> statsMap = memorySet.getFastPutStatsMap();
        long totalLookups = stats.lastHitCount() + stats.quickHitCount() + stats.missCount();
        long hitPercent = totalLookups == 0 ? 0 : ((stats.lastHitCount() + stats.quickHitCount()) * 100 / totalLookups);
        long lastPercent = totalLookups == 0 ? 0 : (stats.lastHitCount() * 100 / totalLookups);
        long quickPercent = totalLookups == 0 ? 0 : (stats.quickHitCount() * 100 / totalLookups);
        log.info("MemoryFastPutStats: {}", statsMap);
        log.info("MemoryFastPut命中率: {}% (last={}, quick={}, miss={})", hitPercent, stats.lastHitCount(), stats.quickHitCount(), stats.missCount());
        log.info("MemoryFastPut校验失败/淘汰: rejected={}, invalidated={}", stats.rejectedCount(), stats.invalidatedCount());
        log.info("MemoryFastPut摘要: {}", summarizeFastPut(stats.lastHitCount(), stats.quickHitCount(), stats.missCount(), stats.rejectedCount(), stats.invalidatedCount(), stats.quickCacheCapacity(), stats.quickCacheSize(), lastPercent, quickPercent));
    }

    private String summarizeFastPut(DsHashSetI64.FastPutStats stats, long totalLookups, long lastPercent, long quickPercent) {
        return summarizeFastPut(
            stats.lastHitCount(),
            stats.quickHitCount(),
            stats.missCount(),
            stats.rejectedCount(),
            stats.invalidatedCount(),
            stats.quickCacheCapacity(),
            stats.quickCacheSize(),
            lastPercent,
            quickPercent
        );
    }

    private String summarizeFastPut(long lastHitCount, long quickHitCount, long missCount, long rejectedCount, long invalidatedCount, int quickCacheCapacity, int quickCacheSize, long lastPercent, long quickPercent) {
        long totalLookups = lastHitCount + quickHitCount + missCount;
        if (totalLookups == 0) {
            return "无统计数据";
        }
        StringBuilder sb = new StringBuilder();
        if (lastPercent >= 80) {
            sb.append("单热点前缀明显，保留 lastPutCache；");
        } else if (lastHitCount > 0) {
            sb.append("lastPutCache 有收益；");
        } else {
            sb.append("lastPutCache 收益弱；");
        }

        if (quickPercent >= 5) {
            sb.append(" quickCache 对多热点前缀有效；");
        } else if (quickHitCount > 0) {
            sb.append(" quickCache 有少量收益；");
        } else {
            sb.append(" quickCache 基本未命中；");
        }

        if (rejectedCount > 0 || invalidatedCount > 0) {
            sb.append(" 存在校验失败/淘汰，当前实现已兜底。");
        } else {
            sb.append(" 无校验失败/淘汰。");
        }
        sb.append(' ').append(recommendQuickCacheSize(quickHitCount, missCount, quickCacheCapacity, quickCacheSize, quickPercent));
        return sb.toString();
    }

    private String recommendQuickCacheSize(DsHashSetI64.FastPutStats stats, long quickPercent) {
        return recommendQuickCacheSize(stats.quickHitCount(), stats.missCount(), stats.quickCacheCapacity(), stats.quickCacheSize(), quickPercent);
    }

    private String recommendQuickCacheSize(long quickHitCount, long missCount, int capacity, int quickCacheSize, long quickPercent) {
        if (quickHitCount == 0) {
            if (capacity > 64) {
                return "建议将 -Dds.hashset.quickCacheSize 先降到 64 观察。";
            }
            return "建议保持当前 quickCacheSize，必要时可继续减小。";
        }
        if (quickPercent >= 5) {
            if (quickCacheSize >= capacity && missCount > quickHitCount) {
                return "建议尝试增大 -Dds.hashset.quickCacheSize，观察 quickHit 是否继续上升。";
            }
            return "建议保留当前 quickCacheSize。";
        }
        if (quickHitCount > 0) {
            return "quickCache 有收益但较弱，建议优先保持当前容量并结合真实 workload 再调参。";
        }
        return "建议保持当前 quickCacheSize。";
    }

    private DsHashSetI64.FastPutStats runFastPutBenchmark(String name, Integer quickCacheSize, int iterations, FastPutWorkload workload) throws Exception {
        return runFastPutBenchmarkResult(name, quickCacheSize, iterations, workload).stats();
    }

    private FastPutBenchmarkResult runFastPutBenchmarkResult(String name, Integer quickCacheSize, int iterations, FastPutWorkload workload) throws Exception {
        if (quickCacheSize != null) {
            dsHashSet.setQuickCacheSize(quickCacheSize);
        }
        dsHashSet.clear();
        System.gc();
        long memBefore = getUsedMemory();
        long gcBefore = getGCCount();
        long start = System.nanoTime();
        workload.run();
        long duration = System.nanoTime() - start;
        long memAfter = getUsedMemory();
        System.gc();
        long gcAfter = getGCCount();

        BenchmarkResult test = new BenchmarkResult(
            name,
            duration,
            iterations,
            memAfter - memBefore,
            gcAfter - gcBefore
        );
        test.print();
        printFastPutStats(dsHashSet);
        return new FastPutBenchmarkResult(name, duration, iterations, dsHashSet.getFastPutStats());
    }

    private String toFastPutCsv(FastPutBenchmarkResult result) {
        DsHashSetI64.FastPutStats stats = result.stats();
        return stats.quickCacheCapacity()
            + "," + result.throughputOps()
            + "," + stats.lastHitCount()
            + "," + stats.quickHitCount()
            + "," + stats.missCount()
            + "," + stats.rejectedCount()
            + "," + stats.invalidatedCount()
            + "," + stats.quickCacheSize()
            + "," + stats.quickCacheCapacity();
    }

    private FastPutBenchmarkResult findBestFastPutResult(List<FastPutBenchmarkResult> results) {
        FastPutBenchmarkResult best = results.get(0);
        for (int i = 1; i < results.size(); i++) {
            FastPutBenchmarkResult candidate = results.get(i);
            if (candidate.throughputOps() > best.throughputOps()) {
                best = candidate;
                continue;
            }
            if (candidate.throughputOps() == best.throughputOps()
                && candidate.stats().quickHitCount() > best.stats().quickHitCount()) {
                best = candidate;
            }
        }
        return best;
    }

    @FunctionalInterface
    private interface FastPutWorkload {
        void run() throws Exception;
    }

    private record FastPutBenchmarkResult(String name, long durationNs, int iterations, DsHashSetI64.FastPutStats stats) {
        long throughputOps() {
            return durationNs == 0 ? 0 : (iterations * 1_000_000_000L) / durationNs;
        }

        long totalLookups() {
            return stats.lastHitCount() + stats.quickHitCount() + stats.missCount();
        }

        long lastPercent() {
            long total = totalLookups();
            return total == 0 ? 0 : (stats.lastHitCount() * 100 / total);
        }

        long quickPercent() {
            long total = totalLookups();
            return total == 0 ? 0 : (stats.quickHitCount() * 100 / total);
        }
    }


    @Test
    public void testToArrayAndIterator() throws Exception {
        int count = 0;
        dsHashSet.clear();
        for(int i=-10000;i<10000;i= i+100){
            dsHashSet.add(i);
            count++;
        }
//        dsHashSet.add(10L);
//        dsHashSet.add(20L);
//        dsHashSet.add(30L);

        
        dsHashSet.remove(dsHashSet.first());
        count--;
        Object[] arr = dsHashSet.toArray();
         System.out.println(dsHashSet.first()+" -> "+dsHashSet.last());
        assertEquals(count, arr.length);
        for (Object o : arr) {
            System.out.println(o);
            assertTrue(o instanceof Long);
            assertTrue(dsHashSet.contains(o));
        }
        System.out.println("**************"+dsHashSet.getStoreUsed());
        long[] longs = dsHashSet.toArrayLong();
        assertEquals(count, longs.length);
        for (long v : longs) {
             System.out.println(v);
            assertTrue(dsHashSet.contains(v));
        }
        int c = 0;
        for (Long v : dsHashSet) {
            assertNotNull(v);
            assertTrue(dsHashSet.contains(v));
            c++;
        }
        assertEquals(count, c);
    }

    @Test
    public void testBulkOps() throws Exception {
        assertTrue(dsHashSet.addAll(java.util.Arrays.asList(1L, 2L, 3L)));
        assertFalse(dsHashSet.addAll(java.util.Arrays.asList(1L, 2L)));
        assertTrue(dsHashSet.containsAll(java.util.Arrays.asList(1L, 2L)));

        assertTrue(dsHashSet.removeAll(java.util.Arrays.asList(2L, 999L)));
        assertFalse(dsHashSet.contains(2L));
        assertEquals(2, dsHashSet.size());

        assertTrue(dsHashSet.retainAll(java.util.Arrays.asList(1L)));
        assertTrue(dsHashSet.contains(1L));
        assertFalse(dsHashSet.contains(3L));
        assertEquals(1, dsHashSet.size());
    }

    @Test
    public void testClear() throws Exception {
        dsHashSet.add(1L);
        dsHashSet.add(2L);
        assertFalse(dsHashSet.isEmpty());
        dsHashSet.clear();
        assertTrue(dsHashSet.isEmpty());
        assertEquals(0, dsHashSet.size());
        assertFalse(dsHashSet.contains(1L));
        assertFalse(dsHashSet.contains(2L));
    }

    @Test
    public void testLiveIteratorReflectsChanges() throws Exception {
        dsHashSet.add(1L);
        dsHashSet.add(2L);

        Iterator<Long> it = dsHashSet.iterator();
        assertTrue(it.hasNext());
        Long first = it.next();
        assertNotNull(first);

        dsHashSet.add(3L);
        dsHashSet.remove(2L);

        HashSet<Long> rest = new HashSet<>();
        for (Long v : dsHashSet) {
            rest.add(v);
        }

        assertTrue(rest.contains(3L));
        assertFalse(rest.contains(2L));
    }
}
