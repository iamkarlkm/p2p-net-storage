package ds;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * DbString 的单元测试类。
 */
public class DbStringTest {

    private static final String TEST_DIR = "target/test-db-string";
    private DbString dbString;

    @Before
    public void setUp() throws IOException {
        File dir = new File(TEST_DIR);
        if (dir.exists()) {
            deleteRecursively(dir);
        }
        dir.mkdirs();
        
        dbString = new DbString(TEST_DIR);
    }
    
    @Test
    public void testScanMethods() throws Exception {
        String key = "scan_key";
        String value = "scan_value";
        
        dbString.put(key, value);
        
        // 测试 scanValueByKey
        String scannedValue = dbString.scanValueByKey(key);
        assertEquals("scanValueByKey should return correct value", value, scannedValue);
        
        // 测试 scanKeyByValue
        String scannedKey = dbString.scanKeyByValue(value);
        assertEquals("scanKeyByValue should return correct key", key, scannedKey);
        
        // 测试不存在的情况
        assertNull(dbString.scanValueByKey("not_exist"));
        assertNull(dbString.scanKeyByValue("not_exist"));
    }
    
    @Test
    public void testGetKeyByValue() throws Exception {
        String key = "reverse_key";
        String value = "reverse_value";
        
        dbString.put(key, value);
        
        String retrievedKey = dbString.getKeyByValue(value);
        assertEquals(key, retrievedKey);
        
        String nonExistent = dbString.getKeyByValue("non_existent");
        assertNull(nonExistent);
    }

    @After
    public void tearDown() {
        // Close resources if any? DbString doesn't have close() yet, relies on GC/OS.
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                deleteRecursively(f);
            }
        }
        file.delete();
    }

    @Test
    public void testPutAndGet() throws Exception {
        String key = "test_key_1";
        String value = "Hello, DbString!";

        long indexId = dbString.put(key, value);
        assertTrue(indexId > 0);

        String retrieved = dbString.get(key);
        assertEquals(value, retrieved);
    }



    @Test
    public void testChunking() throws Exception {
        int originalMaxSize = DbString.MAX_FILE_SIZE;
        try {
            // Set max file size to a small value for testing (e.g., 64 bytes)
            DbString.MAX_FILE_SIZE = 64;
            
            String key = "chunked_key";
            // Create a value that is larger than 64 bytes (e.g., 200 bytes)
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                sb.append("A");
            }
            String value = sb.toString();
            
            // This should trigger chunking
            dbString.put(key, value);
            
            // Calling normal get should throw exception
            try {
                dbString.get(key);
                fail("Expected IllegalStateException for chunked data");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("Data exceeds max file size"));
            }
            
            // Get chunk list
            java.util.List<Long> chunks = dbString.getChunkList(key, 1, 5000);
            assertNotNull(chunks);
            
            // Expected chunks: ceil(200 / 64) = 4
            assertEquals(4, chunks.size());
            
            // Test pagination
            java.util.List<Long> page1 = dbString.getChunkList(key, 1, 2);
            assertEquals(2, page1.size());
            assertEquals(chunks.get(0), page1.get(0));
            assertEquals(chunks.get(1), page1.get(1));
            
            java.util.List<Long> page2 = dbString.getChunkList(key, 2, 2);
            assertEquals(2, page2.size());
            assertEquals(chunks.get(2), page2.get(0));
            assertEquals(chunks.get(3), page2.get(1));
            
            // Test getChunkById
            byte[] chunk0Data = dbString.getChunkById(chunks.get(0));
            assertNotNull(chunk0Data);
            // Since max size is 64 and bucket size will be exactly 64 for 64 bytes
            assertEquals(64, chunk0Data.length);
            for (int i = 0; i < 64; i++) {
                assertEquals((byte)'A', chunk0Data[i]);
            }
            
            // Last chunk might be padded since 200 = 64 * 3 + 8.
            // But we know the bucket for 8 bytes will be 8.
            byte[] chunk3Data = dbString.getChunkById(chunks.get(3));
            assertNotNull(chunk3Data);
            assertEquals(8, chunk3Data.length);
            for (int i = 0; i < 8; i++) {
                assertEquals((byte)'A', chunk3Data[i]);
            }
            
        } finally {
            // Restore original max size
            DbString.MAX_FILE_SIZE = originalMaxSize;
        }
    }

    @Test
    public void testLargeValue() throws Exception {
        String key = "large_key";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("Data-").append(i).append(",");
        }
        String value = sb.toString();

        dbString.put(key, value);
        String retrieved = dbString.get(key);
        assertEquals(value, retrieved);
    }

    @Test
    public void testCollisionHandling() throws Exception {
        // 很难构造 MD5 前 64 位碰撞，但可以测试不同 Key
        String key1 = "key1";
        String value1 = "value1";
        String key2 = "key2";
        String value2 = "value2";

        dbString.put(key1, value1);
        dbString.put(key2, value2);

        assertEquals(value1, dbString.get(key1));
        assertEquals(value2, dbString.get(key2));
    }
    
    @Test
    public void testUpdate() throws Exception {
        String key = "update_key";
        String val1 = "v1";
        String val2 = "v2_updated";
        
        dbString.put(key, val1);
        assertEquals(val1, dbString.get(key));
        
        dbString.put(key, val2);
        assertEquals(val2, dbString.get(key));
    }
    
    @Test
    public void testPersistence() throws Exception {
        String key = "persist_key";
        String value = "persist_val";
        
        dbString.put(key, value);
        
        // Re-open
        dbString = new DbString(TEST_DIR);
        assertEquals(value, dbString.get(key));
    }
}
