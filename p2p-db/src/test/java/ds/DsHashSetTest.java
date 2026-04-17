package ds;

import com.q3lives.ds.collections.DsHashSetI64;
import com.q3lives.ds.collections.DsHashSetI64_Fixed;
import com.q3lives.ds.collections.DsMemorySet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Iterator;
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
        System.out.println(dsHashSet.contains(-50000)+" ************** "+dsHashSet.total()+" ************** "+dsHashSet.getStoreUsed());
        System.out.println(dsHashSet.first()+" -> "+dsHashSet.last()+" range(0, 10): "+dsHashSet.range(0, 10));
         System.out.println("value -50000: "+dsHashSet.contains(-50000));
         System.out.println("state: "+dsHashSet.readState(7, 176));
            System.out.println("getByNodeId(7, 176): "+dsHashSet.getByNodeId(7, 176));
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
        System.out.println(mset.total()+" ************** "+mset.getStoreUsed());
        System.out.println(mset.first()+" -> "+mset.last());
         dsHashSet.clear();
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


    @Test
    public void testToArrayAndIterator() throws Exception {
        int count = 0;
        dsHashSet.clear();
        for(int i=-1000000;i<1000000;i= i+100000){
            dsHashSet.add(i);
            count++;
        }
//        dsHashSet.add(10L);
//        dsHashSet.add(20L);
//        dsHashSet.add(30L);

        
        dsHashSet.remove(dsHashSet.first());
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
        while (it.hasNext()) {
            rest.add(it.next());
        }

        assertTrue(rest.contains(3L));
        assertFalse(rest.contains(2L));
    }
}
