package ds.cache;

import com.q3lives.ds.cache.RedisStringCache;
import java.io.File;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class RedisStringCacheTest {

    private static final String TEST_DIR = "target/test-redis-string-cache";

    @Before
    public void setUp() {
        File dir = new File(TEST_DIR);
        if (dir.exists()) {
            deleteRecursively(dir);
        }
        dir.mkdirs();
    }

    @Test
    public void testIncrDecr() throws Exception {
        RedisStringCache cache = new RedisStringCache(TEST_DIR, 128);
        String key = "test_incr";
        cache.set(key, "10");
        
        long val = cache.incr(key);
        assertEquals(11, val);
        assertEquals("11", cache.get(key));
        
        val = cache.incrBy(key, 5);
        assertEquals(16, val);
        assertEquals("16", cache.get(key));
        
        val = cache.decr(key);
        assertEquals(15, val);
        
        val = cache.decrBy(key, 20);
        assertEquals(-5, val);
        assertEquals("-5", cache.get(key));
        
        // Test incr on non-existing key
        String newKey = "test_incr_new";
        assertEquals(1, cache.incr(newKey));
    }

    @Test
    public void testMgetMset() throws Exception {
        RedisStringCache cache = new RedisStringCache(TEST_DIR, 128);
        java.util.Map<String, String> data = new java.util.LinkedHashMap<>();
        data.put("mk1", "v1");
        data.put("mk2", "v2");
        data.put("mk3", "v3");
        
        cache.mset(data);
        
        java.util.Map<String, String> result = cache.mget("mk1", "mk2", "mk4");
        assertEquals("v1", result.get("mk1"));
        assertEquals("v2", result.get("mk2"));
        assertNull(result.get("mk4"));
    }

    @Test
    public void testSetGetAndPersistence() throws Exception {
        RedisStringCache cache = new RedisStringCache(TEST_DIR, 128);
        cache.set("k1", "v1");
        assertEquals("v1", cache.get("k1"));

        RedisStringCache cache2 = new RedisStringCache(TEST_DIR, 128);
        assertEquals("v1", cache2.get("k1"));
    }

    @Test
    public void testExpire() throws Exception {
        RedisStringCache cache = new RedisStringCache(TEST_DIR, 128);
        cache.set("k1", "v1", 50);
        assertEquals("v1", cache.get("k1"));

        Thread.sleep(120);
        assertNull(cache.get("k1"));
        assertEquals(-2, cache.ttlMillis("k1"));
    }

    @Test
    public void testDel() throws Exception {
        RedisStringCache cache = new RedisStringCache(TEST_DIR, 128);
        cache.set("k1", "v1");
        assertTrue(cache.exists("k1"));
        cache.del("k1");
        assertFalse(cache.exists("k1"));
        assertNull(cache.get("k1"));
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

