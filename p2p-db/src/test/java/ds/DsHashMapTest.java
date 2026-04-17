package ds;

import com.q3lives.ds.collections.DsHashMapI64;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class DsHashMapTest {

    private DsHashMapI64 dsHashMap;
    private File dataFile;

    @Before
    public void setUp() throws Exception {
        // 创建临时测试文件
        dataFile = new File("test_dshashmap.dat");
        deleteWithSidecars(dataFile);
        dsHashMap = new DsHashMapI64(dataFile);
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
        assertNotNull("应该包含key=50", dsHashMap.get(50L));

        boolean removed = dsHashMap.remove(50L);
        assertTrue("移除应该成功", removed);

        assertNull("移除后不应该包含key=50", dsHashMap.get(50L));
        assertNull("移除后获取应该返回null", dsHashMap.get(50L));

        boolean removedAgain = dsHashMap.remove(50L);
        assertFalse("再次移除应该返回false", removedAgain);
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
}
