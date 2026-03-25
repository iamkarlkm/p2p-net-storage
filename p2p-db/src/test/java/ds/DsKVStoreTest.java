package ds;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * DsKVStore 的单元测试类。
 */
public class DsKVStoreTest {

    private static final String TEST_DIR = "target/test-db";
    private DsKVStore kvStore;
    private String storeName;

    @Before
    public void setUp() throws IOException {
        // 确保测试目录存在
        File dir = new File(TEST_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        
        // 使用随机的存储名称，避免 Windows 下 MappedByteBuffer 文件锁导致无法删除旧文件
        storeName = "test_kv_" + UUID.randomUUID().toString();
        
        // 初始化 KV 存储
        kvStore = new DsKVStore(TEST_DIR, storeName);
    }

    @After
    public void tearDown() {
        if (kvStore != null) {
            kvStore.close();
        }
    }

    @Test
    public void testPutAndGet() throws Exception {
        String key = "test_key_1";
        String value = "Hello, DsKVStore!";

        // 写入数据
        kvStore.put(key, value);
        assertTrue(kvStore.containsKey(key));
        assertEquals(1, kvStore.size());

        // 读取数据
        String retrievedValue = kvStore.get(key);
        assertEquals(value, retrievedValue);
    }

    @Test
    public void testUpdate() throws Exception {
        String key = "update_key";
        String value1 = "Initial Value";
        String value2 = "Updated Value - Longer String to trigger append";

        kvStore.put(key, value1);
        assertEquals(value1, kvStore.get(key));

        // 更新值
        kvStore.put(key, value2);
        assertEquals(value2, kvStore.get(key));
        assertEquals(1, kvStore.size());
    }

    @Test
    public void testRemove() throws Exception {
        String key = "remove_key";
        String value = "Value to be removed";

        kvStore.put(key, value);
        assertTrue(kvStore.containsKey(key));

        // 删除键
        boolean removed = kvStore.remove(key);
        assertTrue(removed);
        assertFalse(kvStore.containsKey(key));
        assertNull(kvStore.get(key));
        assertEquals(0, kvStore.size());
    }

    @Test
    public void testPersistenceAndRecovery() throws Exception {
        String key1 = "persist_key_1";
        String value1 = "persist_value_1";
        String key2 = "persist_key_2";
        String value2 = "persist_value_2";

        kvStore.put(key1, value1);
        kvStore.put(key2, value2);
        kvStore.remove(key1);

        // 关闭当前存储实例（模拟重启）
        kvStore.close();

        // 重新加载存储实例，它应从磁盘中重建索引
        DsKVStore recoveredStore = new DsKVStore(TEST_DIR, storeName);

        // 验证已删除的键
        assertFalse(recoveredStore.containsKey(key1));
        assertNull(recoveredStore.get(key1));

        // 验证未删除的键
        assertTrue(recoveredStore.containsKey(key2));
        assertEquals(value2, recoveredStore.get(key2));
        assertEquals(1, recoveredStore.size());
        
        recoveredStore.close();
    }

    @Test
    public void testLargeDataset() throws Exception {
        int count = 1000;
        for (int i = 0; i < count; i++) {
            kvStore.put("key_" + i, "value_" + UUID.randomUUID().toString());
        }

        assertEquals(count, kvStore.size());

        for (int i = 0; i < count; i++) {
            String val = kvStore.get("key_" + i);
            assertNotNull("Value should not be null for key_" + i, val);
            if (!val.startsWith("value_")) {
                System.out.println("Failed at key_" + i + " : " + val);
            }
            assertTrue("Value should start with 'value_'", val.startsWith("value_"));
        }
    }
}
