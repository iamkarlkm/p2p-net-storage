package ds;

import com.q3lives.ds.legacy.db.DbString;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class DbRecoveryTest {

    private static final String TEST_DIR = "target/db_recovery_test";
    private DbString dbString;

    @Before
    public void setUp() throws IOException {
        deleteDirectory(new File(TEST_DIR));
        dbString = new DbString(TEST_DIR);
    }

    @After
    public void tearDown() {
        // deleteDirectory(new File(TEST_DIR));
    }

    private void deleteDirectory(File file) {
        if (file.exists()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteDirectory(f);
                }
            }
            file.delete();
        }
    }

    @Test
    public void testRecycling() throws IOException, InterruptedException {
        // 1. Add data 1
        String key1 = "key1";
        String value1 = "value1_1234567890"; // length 17 -> Bucket 32
        long indexId1 = dbString.put(key1, value1);
        
        // 2. Add data 2
        String key2 = "key2";
        String value2 = "value2_1234567890";
        long indexId2 = dbString.put(key2, value2);
        
        // 3. Verify data
        assertEquals(value1, dbString.get(key1));
        assertEquals(value2, dbString.get(key2));
        
        // 4. Delete data 1
        dbString.deleteByIndexId(indexId1);
        
        // 5. Add data 3 (should reuse data 1's space)
        String key3 = "key3";
        String value3 = "value3_1234567890"; // Same length
        long indexId3 = dbString.put(key3, value3);
        
        // Verify data 3
        assertEquals(value3, dbString.get(key3));
        
        // Check offsets
        // IndexID format: High 8 bits = p (Power), Low 56 bits = Offset
        // ValueID is stored inside Index Bucket.
        
        // Let's get the ValueID for indexId1 (which was deleted)
        // We can't easily get it now as it's deleted (marked free), but we can inspect the indexId3.
        
        // indexId1 and indexId3 should ideally have the same offset if recycling works for Index Bucket.
        // But put() allocates Value Bucket first, then Index Bucket.
        
        // If we deleted indexId1, we freed both Index Block and Value Block.
        // So putting key3 should reuse both Index Block and Value Block (if they are head of free list).
        
        long offset1 = indexId1 & 0x00FFFFFFFFFFFFFFL;
        long offset3 = indexId3 & 0x00FFFFFFFFFFFFFFL;
        
        System.out.println("IndexID1 Offset: " + offset1);
        System.out.println("IndexID3 Offset: " + offset3);
        
        // Since recycling uses a stack (LIFO) or similar (head pointer), the most recently freed block is reused first?
        // My implementation:
        // markFree: write offset to end of .free file.
        // allocateOffset: read from freeHeadPointer.
        // So it is FIFO (First In, First Out) if we append to end and read from head?
        // Wait.
        // markFree: freeRaf.seek(length); write(offset); -> Appends to end.
        // allocateOffset: seek(freeHeadPointer); read(); freeHeadPointer+=8; -> Reads from head.
        // So it is a Queue (FIFO).
        
        // If I delete 1 item, it goes to the queue.
        // allocateOffset reads from queue head.
        // So yes, it should reuse it.
        
        assertEquals("Index Offset should be reused", offset1, offset3);
        
        // Also check Value ID reuse
        // We need to read the Index Data to get Value ID.
        // dbString.getValueByIndexId is available? No, but we can use reflection or add helper?
        // DbString has `getValueByIndexId`? Yes, I saw it in the file.
        
        /*
        public byte[] getValueByIndexId(long indexId) throws IOException { ... }
        */
        // But it returns value content, not ID.
        
        // However, we can infer recycling if the file size didn't grow?
        // Or just trust the index reuse test.
    }
}
