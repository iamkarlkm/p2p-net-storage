package ds.cache;

import java.io.File;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class RedisBytesCacheTest {

    private static final String TEST_DIR = "target/test-redis-bytes-cache";

    @Before
    public void setUp() {
        File dir = new File(TEST_DIR);
        if (dir.exists()) {
            deleteRecursively(dir);
        }
        dir.mkdirs();
    }

    @Test
    public void testSetGetAndPersistence() throws Exception {
        RedisBytesCache cache = new RedisBytesCache(TEST_DIR, 128);
        cache.set("k1", new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, cache.get("k1"));

        RedisBytesCache cache2 = new RedisBytesCache(TEST_DIR, 128);
        assertArrayEquals(new byte[]{1, 2, 3}, cache2.get("k1"));
    }

    @Test
    public void testExpire() throws Exception {
        RedisBytesCache cache = new RedisBytesCache(TEST_DIR, 128);
        cache.set("k1", new byte[]{9}, 50);
        assertNotNull(cache.get("k1"));

        Thread.sleep(120);
        assertNull(cache.get("k1"));
        assertEquals(-2, cache.ttlMillis("k1"));
    }

    @Test
    public void testDel() throws Exception {
        RedisBytesCache cache = new RedisBytesCache(TEST_DIR, 128);
        cache.set("k1", new byte[]{7, 8});
        assertTrue(cache.exists("k1"));
        cache.del("k1");
        assertFalse(cache.exists("k1"));
        assertNull(cache.get("k1"));
    }

    @Test
    public void testIncrDecr() throws Exception {
        RedisBytesCache cache = new RedisBytesCache(TEST_DIR, 128);
        String key = "test_incr";
        cache.set(key, "10".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        
        long val = cache.incr(key);
        assertEquals(11, val);
        assertArrayEquals("11".getBytes(java.nio.charset.StandardCharsets.UTF_8), cache.get(key));
        
        val = cache.incrBy(key, 5);
        assertEquals(16, val);
        assertArrayEquals("16".getBytes(java.nio.charset.StandardCharsets.UTF_8), cache.get(key));
        
        val = cache.decr(key);
        assertEquals(15, val);
        
        val = cache.decrBy(key, 20);
        assertEquals(-5, val);
        assertArrayEquals("-5".getBytes(java.nio.charset.StandardCharsets.UTF_8), cache.get(key));
        
        String newKey = "test_incr_new";
        assertEquals(1, cache.incr(newKey));
    }

    @Test
    public void testMgetMset() throws Exception {
        RedisBytesCache cache = new RedisBytesCache(TEST_DIR, 128);
        java.util.Map<String, byte[]> data = new java.util.LinkedHashMap<>();
        data.put("mk1", new byte[]{1});
        data.put("mk2", new byte[]{2});
        data.put("mk3", new byte[]{3});
        
        cache.mset(data);
        
        java.util.Map<String, byte[]> result = cache.mget("mk1", "mk2", "mk4");
        assertArrayEquals(new byte[]{1}, result.get("mk1"));
        assertArrayEquals(new byte[]{2}, result.get("mk2"));
        assertNull(result.get("mk4"));
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteRecursively(f);
                }
            }
        }
        file.delete();
    }
}

