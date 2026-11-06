package ds;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.meta.DsDatabaseMetaMigrator;
import com.q3lives.ds.registry.meta.DsRegistryInstance;
import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

/**
 * P11 T3 行存接入 DataBackend 4 TRs：
 *   TR-T3-R1：HYBRID roundtrip（putEntity → getTable → 字段全一致）
 *   TR-T3-R2：HYBRID 回填（bucket 预写 bytes → Registry 强制清 → 首次 getTable 触发回填）
 *   TR-T3-R3：removeTable 双删（bucket + Registry 都删干净）
 *   TR-T3-R4：HYBRID / YAML 初始化契约（默认 HYBRID rowDataBackend != null；构造分支保证 YAML = null）
 */
public class P11_RowDataTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() {
        // 清 DsRegistryInstance INSTANCES 缓存，保证每个 test 独立 Registry 单例池
        try {
            Field f = DsRegistryInstance.class.getDeclaredField("INSTANCES");
            f.setAccessible(true);
            Object m = f.get(null);
            if (m instanceof ConcurrentHashMap) ((ConcurrentHashMap<?, ?>) m).clear();
        } catch (Exception ignored) {}
        System.clearProperty(MetaMode.PROPERTY);
    }

    @DsTable(name = "t_user_t3")
    public static class User extends DsTableAdapter {
        @DsField(name = "name", length = 64)
        public String name;
        @DsField(name = "age", length = 4, indexed = true)
        public int age;
        @DsField(name = "email", length = 128)
        public String email;

        public User() {}
        public User(String name, int age, String email) {
            this.name = name; this.age = age; this.email = email;
        }
    }

    @Test
    public void r1_hybrid_row_roundtrip() throws Exception {
        File root = tmp.newFolder("t3-r1");
        new DsDatabaseMetaMigrator(root, false).migrateAll();
        try (DsDatabaseLocal db = new DsDatabaseLocal(root)) {
            User u1 = new User("Alice", 30, "alice@foo.com");
            long id = db.putEntity(u1, true);
            assertTrue(id > 0L);
            assertEquals(id, (long) u1.getId());

            // HYBRID 3 次读：1 次 bucket+回填 2/3 次 Registry 命中；字段每次全一致
            for (int i = 0; i < 3; i++) {
                User back = db.getTable(User.class, id);
                assertNotNull("i=" + i + " back 不应 null", back);
                assertEquals("i=" + i + " name", "Alice", back.name);
                assertEquals("i=" + i + " age", 30, back.age);
                assertEquals("i=" + i + " email", "alice@foo.com", back.email);
                assertEquals("i=" + i + " id", id, (long) back.getId());
            }
        }
    }

    @Test
    public void r2_hybrid_row_backfill() throws Exception {
        File root = tmp.newFolder("t3-r2");
        new DsDatabaseMetaMigrator(root, false).migrateAll();
        try (DsDatabaseLocal db = new DsDatabaseLocal(root)) {
            User u = new User("Bob", 25, "bob@x.com");
            long id = db.putEntity(u, true);

            // 通过 RegistryInstance 拿到 DsRegistry，强制清对应 path
            DsRegistry registry = DsRegistryInstance.get(root);
            String path = RegistryMetaPaths.dataRowPayload(User.class.getName(), id);
            assertTrue("putEntity 写后 Registry 应存在", registry.exists(path));
            // 手动删 Registry（模拟只有 bucket 有 → 下次读触发回填）
            registry.remove(path, false);
            assertFalse("remove 后 Registry 应空", registry.exists(path));

            User back = db.getTable(User.class, id);
            assertNotNull("回填后 getTable 非 null", back);
            assertEquals("Bob", back.name);
            assertEquals(25, back.age);
            assertEquals("bob@x.com", back.email);
            assertTrue("读回填后 Registry 重新存在", registry.exists(path));
        }
    }

    @Test
    public void r3_removeTable_both_backend_deleted() throws Exception {
        File root = tmp.newFolder("t3-r3");
        new DsDatabaseMetaMigrator(root, false).migrateAll();
        try (DsDatabaseLocal db = new DsDatabaseLocal(root)) {
            User u = new User("Carol", 35, "c@y.com");
            long id = db.putEntity(u, true);
            DsRegistry registry = DsRegistryInstance.get(root);
            String path = RegistryMetaPaths.dataRowPayload(User.class.getName(), id);
            assertTrue("remove 前 Registry 存在", registry.exists(path));

            boolean removed = db.removeTable(User.class, id, false);
            assertTrue("removeTable 返回 true", removed);
            assertNull("remove 后 getTable 返回 null", db.getTable(User.class, id));
            // 用 get() 验证：Entry 要么 null 要么 !isPresent（DsRegistry.exists 在某些情况下可能返回 true 但值已清空）
            com.q3lives.ds.registry.RegistryEntry entry = registry.get(path);
            boolean stillHasValue = (entry != null && entry.isPresent());
            assertFalse("remove 后 Registry 不应有 payload 值(entry=" + entry + ")", stillHasValue);
            // exists 验证作为软断言（DsRegistry 内部缓存策略可能不立即清 path 节点）
            assertFalse("remove 后 Registry path 也应不存在", registry.exists(path));
        }
    }

    @Test
    public void r4_hybrid_row_data_backend_init_contract() throws Exception {
        File root = tmp.newFolder("t3-r4");
        new DsDatabaseMetaMigrator(root, false).migrateAll();
        try (DsDatabaseLocal db = new DsDatabaseLocal(root)) {
            // 默认 HYBRID（property 空 = HYBRID）→ rowDataBackend != null
            Field f = DsDatabaseLocal.class.getDeclaredField("rowDataBackend");
            f.setAccessible(true);
            Object rdb = f.get(db);
            assertNotNull("默认 HYBRID 时 rowDataBackend 非 null，保证双写/回填/删除钩子生效", rdb);
        }
        // YAML 分支契约：构造函数中 `if (MetaMode.global() != YAML) new RowDataBackend(root)` →
        // 若启动属性 p2p.orm.meta.mode = yaml，则 GLOBAL=YAML → rowDataBackend=null，零 new Registry 开销。
        // 该分支由静态代码保证，运行时切换 final 字段成本高，这里不动态测试。
    }
}
