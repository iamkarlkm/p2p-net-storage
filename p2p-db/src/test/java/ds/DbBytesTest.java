package ds;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class DbBytesTest {

    private static final String TEST_DIR = "target/test-db-bytes";
    private DbBytes dbBytes;

    @Before
    public void setUp() throws IOException {
        File dir = new File(TEST_DIR);
        if (dir.exists()) {
            deleteRecursively(dir);
        }
        dir.mkdirs();
        
        dbBytes = new DbBytes(TEST_DIR);
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
        byte[] key = "test_key_1".getBytes(StandardCharsets.UTF_8);
        byte[] value = "Hello, DbBytes!".getBytes(StandardCharsets.UTF_8);

        long indexId = dbBytes.put(key, DsDataUtil.md5(key), value);
        assertTrue(indexId > 0);

        byte[] retrieved = dbBytes.get(key);
        assertArrayEquals(value, retrieved);
    }

    @Test
    public void testUpdate() throws Exception {
        byte[] key = "update_key".getBytes(StandardCharsets.UTF_8);
        byte[] value1 = "Initial Value".getBytes(StandardCharsets.UTF_8);
        byte[] value2 = "Updated Value - Longer Length".getBytes(StandardCharsets.UTF_8);
        byte[] value3 = "Short".getBytes(StandardCharsets.UTF_8);

        // 1. 初始存储
        dbBytes.put(key, DsDataUtil.md5(key), value1);
        assertArrayEquals(value1, dbBytes.get(key));

        // 2. 更新为更长的数组
        dbBytes.update(key, value2);
        assertArrayEquals(value2, dbBytes.get(key));

        // 3. 更新为更短的数组
        dbBytes.update(key, value3);
        assertArrayEquals(value3, dbBytes.get(key));
    }

    @Test
    public void testChunking() throws Exception {
        int originalMaxSize = DbBytes.MAX_FILE_SIZE;
        try {
            DbBytes.MAX_FILE_SIZE = 64;
            
            byte[] key = "chunked_key".getBytes(StandardCharsets.UTF_8);
            byte[] value = new byte[200];
            for (int i = 0; i < 200; i++) {
                value[i] = (byte) 'B';
            }
            
            dbBytes.put(key, DsDataUtil.md5(key), value);
            
            try {
                dbBytes.get(key);
                fail("Expected IllegalStateException for chunked data");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("Data exceeds max file size"));
            }
            
            List<Long> chunks = dbBytes.getChunkList(key, 1, 5000);
            assertNotNull(chunks);
            assertEquals(4, chunks.size());
            
            byte[] chunk0Data = dbBytes.getChunkById(chunks.get(0));
            assertNotNull(chunk0Data);
            assertEquals(64, chunk0Data.length);
            for (int i = 0; i < 64; i++) {
                assertEquals((byte) 'B', chunk0Data[i]);
            }
            
        } finally {
            DbBytes.MAX_FILE_SIZE = originalMaxSize;
        }
    }

    @Test
    public void testScanMethods() throws Exception {
        byte[] key = "scan_key".getBytes(StandardCharsets.UTF_8);
        byte[] value = "scan_value".getBytes(StandardCharsets.UTF_8);
        
        dbBytes.put(key, DsDataUtil.md5(key), value);
        
        byte[] scannedValue = dbBytes.scanValueByKey(key);
        assertArrayEquals(value, scannedValue);
        
        byte[] scannedKey = dbBytes.scanKeyByValue(value);
        assertArrayEquals(key, scannedKey);
    }
    
    @Test
    public void testGetKeyByValue() throws Exception {
        byte[] key = "reverse_key".getBytes(StandardCharsets.UTF_8);
        byte[] value = "reverse_value".getBytes(StandardCharsets.UTF_8);
        
        dbBytes.put(key, DsDataUtil.md5(key), value);
        
        byte[] retrievedKey = dbBytes.getKeyByValue(value);
        assertArrayEquals(key, retrievedKey);
    }
}
