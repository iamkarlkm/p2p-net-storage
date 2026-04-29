package ds;

import com.q3lives.ds.collections.DsHashMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;

import static org.junit.Assert.*;

@Slf4j
public class DsHashMapTest {

    private DsHashMap dsHashMap;
    private File dataFile;

    @Before
    public void setUp() throws Exception {
        dataFile = File.createTempFile("test_dshashmap_", ".dat");
        dataFile.deleteOnExit();
        deleteWithSidecars(dataFile);
        dsHashMap = new DsHashMap(dataFile);
    }

    private static void deleteWithSidecars(File f) {
        File[] files = new File[] {
                f,
                new File(f.getAbsolutePath() + ".e16"),
                new File(f.getAbsolutePath() + ".e16.next"),
                new File(f.getAbsolutePath() + ".e16.free"),
                new File(f.getAbsolutePath() + ".e16.free.tmp"),
                new File(f.getAbsolutePath() + ".e32"),
                new File(f.getAbsolutePath() + ".e32.next"),
                new File(f.getAbsolutePath() + ".e32.free"),
                new File(f.getAbsolutePath() + ".e32.free.tmp"),
                new File(f.getAbsolutePath() + ".e64"),
                new File(f.getAbsolutePath() + ".e64.next"),
                new File(f.getAbsolutePath() + ".e64.free"),
                new File(f.getAbsolutePath() + ".e64.free.tmp"),
                new File(f.getAbsolutePath() + ".k16"),
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
    public void testBigStore() throws IOException{
         int count = 500000;
                
        File mapFile = File.createTempFile("test_DsHashMapI64_", ".map");
        mapFile.deleteOnExit();
        deleteWithSidecars(mapFile);
        DsHashMap map = new DsHashMap(mapFile);
             map.clear();
             
         System.gc();
        long memBefore = getUsedMemory();
        long gcBefore = getGCCount();

        long start = System.nanoTime();
        for(long i=-count;i<count;i++){
           map.put(i, i);
        }
      
        long duration = System.nanoTime() - start;
        long memAfter = getUsedMemory();
      
        System.gc();
        
        long gcAfter = getGCCount();

        BenchmarkResult test = new BenchmarkResult(
            "map",
            duration,
            count*2,
            memAfter - memBefore,
            gcAfter - gcBefore
        );
       test.print();
       //map info
       System.out.println(map.get(-50000L)+"==-50000 ************** "+map.getStoreUsed());
        System.out.println(map.sizeLong()+"==1000000 ************** "+map.first()+"==-500000 -> "+map.last()+"===499999 range(0, 10): "+map.range(0, 10));
        assertEquals("map size must equals:", count*2, map.sizeLong());
        assertEquals("map min:",-500000L, map.first().longValue());
        assertEquals("map max:",499999L, map.last().longValue());
        map.close();
    }

    @Test
    public void testPutAndGet() throws Exception {
        Long oldVal1 = dsHashMap.put(100L, 200L);
        assertNull("初次插入，旧值应为null", oldVal1);
        
        Long val1 = dsHashMap.get(100L);
        assertNotNull("应该能获取到值", val1);
        assertEquals("获取的值应该等于插入的值", 200L, val1.longValue());

        Long oldVal2 = dsHashMap.put(100L, 300L);
        assertNotNull("更新时，旧值不应为null", oldVal2);
        assertEquals("旧值应该是之前插入的值", 200L, oldVal2.longValue());

        Long val2 = dsHashMap.get(100L);
        assertEquals("获取的值应该等于更新后的值", 300L, val2.longValue());
    }

    @Test
    public void testRemove() throws Exception {
        dsHashMap.put(50L, 500L);
        assertNotNull("应该包含key=50", dsHashMap.get(50L));

        Long removed = dsHashMap.remove(50L);
        assertNotNull("移除应该成功", removed);

        assertNull("移除后不应该包含key=50", dsHashMap.get(50L));
        assertNull("移除后获取应该返回null", dsHashMap.get(50L));

        Long removedAgain = dsHashMap.remove(50L);
        assertNull("再次移除应该返回false", removedAgain);
    }

    @Test
    public void testContainsKey() throws Exception {
        assertNull("空Map不应该包含任意key", dsHashMap.get(99L));
        dsHashMap.put(99L, 999L);
        assertNotNull("插入后应该包含key", dsHashMap.get(99L));
    }

    @Test
    public void testHashCollision() throws Exception {
        // DsHashMap uses an 8-level 256-ary trie based on little-endian bytes of the long key.
        // 1L      -> bytes[0]=1, bytes[1]=0
        // 257L    -> bytes[0]=1, bytes[1]=1
        // 513L    -> bytes[0]=1, bytes[1]=2
        dsHashMap.clear();
        dsHashMap.put(1L, 10L);
        dsHashMap.put(257L, 2570L);
        dsHashMap.put(513L, 5130L);

        assertEquals("获取冲突的key=1应该成功", 10L, dsHashMap.get(1L).longValue());
        assertEquals("获取冲突的key=257应该成功", 2570L, dsHashMap.get(257L).longValue());
        assertEquals("获取冲突的key=513应该成功", 5130L, dsHashMap.get(513L).longValue());

        assertNotNull("应该包含所有冲突的key", dsHashMap.get(1L));
        assertNotNull("应该包含所有冲突的key", dsHashMap.get(257L));
        assertNotNull("应该包含所有冲突的key", dsHashMap.get(513L));

        dsHashMap.remove(257L);
        assertNull("移除后不应包含key=257", dsHashMap.get(257L));
        assertNull("移除后获取应为null", dsHashMap.get(257L));
        
        assertEquals("移除中间节点不影响其他冲突节点", 10L, dsHashMap.get(1L).longValue());
        assertEquals("移除中间节点不影响其他冲突节点", 5130L, dsHashMap.get(513L).longValue());
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


}
