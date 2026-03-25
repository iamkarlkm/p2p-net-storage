package ds;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class DsHashMapTest {

    private DsHashMap dsHashMap;
    private File dataFile;

    @Before
    public void setUp() throws Exception {
        // 创建临时测试文件
        dataFile = new File("test_dshashmap.dat");
        if (dataFile.exists()) {
            dataFile.delete();
        }
        dsHashMap = new DsHashMap(dataFile);
    }

    @After
    public void tearDown() throws Exception {
        // We do not delete the file to avoid FileSystemException on Windows
        // The MappedByteBuffer will be garbage collected eventually.
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
        assertTrue("应该包含key=50", dsHashMap.containsKey(50L));

        boolean removed = dsHashMap.remove(50L);
        assertTrue("移除应该成功", removed);

        assertFalse("移除后不应该包含key=50", dsHashMap.containsKey(50L));
        assertNull("移除后获取应该返回null", dsHashMap.get(50L));

        boolean removedAgain = dsHashMap.remove(50L);
        assertFalse("再次移除应该返回false", removedAgain);
    }

    @Test
    public void testContainsKey() throws Exception {
        assertFalse("空Map不应该包含任意key", dsHashMap.containsKey(99L));
        dsHashMap.put(99L, 999L);
        assertTrue("插入后应该包含key", dsHashMap.containsKey(99L));
    }

    @Test
    public void testHashCollision() throws Exception {
        // level 0: shift 0, mask 0xf. (bits 0-3)
        // level 1: shift 4, mask 0xffc. (bits 6-15 effectively, because bottom 2 bits of shifted are 0)
        // 1L = 1. level 0 hash = 1. level 1 hash = 0.
        // 65L = 64 + 1. level 0 hash = 1. level 1 hash = (65 >>> 4) & 0xffc = 4 & 0xffc = 4.
        // 129L = 128 + 1. level 0 hash = 1. level 1 hash = (129 >>> 4) & 0xffc = 8 & 0xffc = 8.
        
        dsHashMap.put(1L, 10L);
        dsHashMap.put(65L, 650L);
        dsHashMap.put(129L, 1290L);

        assertEquals("获取冲突的key=1应该成功", 10L, dsHashMap.get(1L).longValue());
        assertEquals("获取冲突的key=65应该成功", 650L, dsHashMap.get(65L).longValue());
        assertEquals("获取冲突的key=129应该成功", 1290L, dsHashMap.get(129L).longValue());

        assertTrue("应该包含所有冲突的key", dsHashMap.containsKey(1L));
        assertTrue("应该包含所有冲突的key", dsHashMap.containsKey(65L));
        assertTrue("应该包含所有冲突的key", dsHashMap.containsKey(129L));

        dsHashMap.remove(65L);
        assertFalse("移除后不应包含key=65", dsHashMap.containsKey(65L));
        assertNull("移除后获取应为null", dsHashMap.get(65L));
        
        assertEquals("移除中间节点不影响其他冲突节点", 10L, dsHashMap.get(1L).longValue());
        assertEquals("移除中间节点不影响其他冲突节点", 1290L, dsHashMap.get(129L).longValue());
    }
}
