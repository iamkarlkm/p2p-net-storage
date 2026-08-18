package ds;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.index.DsEqIndexStore;
import com.q3lives.ds.database.schema.EntitySchemaUtil;
import com.q3lives.ds.util.DsPathUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Date;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DsEqIndexOrmAutoMaintainTest {

    private File tmpRoot;

    @Before
    public void before() throws IOException {
        tmpRoot = Files.createTempDirectory("dsdb_orm_idx_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
    }

    @After
    public void after() throws IOException {
        try {
            DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        } catch (Exception ignore) {
        }
        try {
            DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        } catch (Exception ignore) {
        }
        deleteRecursive(tmpRoot);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    // ==================== 测试用实体 ====================

    /** 带 indexed=true 的 long 列（V1，4 字段 total=40B）。 */
    public static class IndexedUserV1 extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;

        @DsField(name = "age", length = 4, indexed = true)
        public int age;

        @DsField(name = "score", length = 8, indexed = true)
        public long score;

        @DsField(name = "createdAt", length = 8)
        public Date createdAt;

        public IndexedUserV1() {}
    }

    /** 继承 V1 字段 + 新 indexed String 列，共 5 字段 total=104B。 */
    public static class IndexedUserV2 extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;

        @DsField(name = "age", length = 4, indexed = true)
        public int age;

        @DsField(name = "score", length = 8, indexed = true)
        public long score;

        @DsField(name = "createdAt", length = 8)
        public Date createdAt;

        @DsField(name = "city", length = 48, defaultValue = "shanghai", indexed = true)
        public String city;

        public IndexedUserV2() {}
    }

    /** 与 IndexedUserV2 同列但 indexed=false（对比用）。 */
    public static class NoIndexUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;

        @DsField(name = "age", length = 4)
        public int age;

        @DsField(name = "score", length = 8)
        public long score;

        @DsField(name = "createdAt", length = 8)
        public Date createdAt;

        @DsField(name = "city", length = 48, defaultValue = "shanghai")
        public String city;

        public NoIndexUser() {}
    }

    // ==================== 工具方法 ====================

    @SuppressWarnings("unchecked")
    private DsEqIndexStore openIndexStore(DsDatabaseLocal db, Class<?> clazz, String fieldName) throws IOException {
        EntitySchemaUtil.SchemaDef schema = EntitySchemaUtil.schemaOf((Class<? extends DsTableAdapter>) clazz);
        String full = clazz.getName();
        String space = DsPathUtil.toSafeFileName(full, 80);
        String idxCol = "eq_" + DsPathUtil.toSafeFileName(fieldName, 64);
        String idxSpace = "indexes/" + space + "/" + schema.schemaId;
        DsEqIndexStore.IndexedValueKind kind = DsEqIndexStore.IndexedValueKind.LONG;
        for (EntitySchemaUtil.ColumnDef col : schema.getColumns()) {
            if (col.name.equals(fieldName) && col.indexed) {
                if (col.typeName.equals(String.class.getName())) {
                    kind = DsEqIndexStore.IndexedValueKind.STRING;
                } else {
                    kind = DsEqIndexStore.IndexedValueKind.LONG;
                }
            }
        }
        return new DsEqIndexStore(db.getRoot(), idxSpace, idxCol, kind);
    }

    // ==================== 4 条防线测试 ====================

    @Test
    public void testNewRowPutEntityAutoIntoIndexes() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot)) {
            IndexedUserV1 u = new IndexedUserV1();
            u.name = "alice";
            u.age = 25;
            u.score = 999L;
            u.createdAt = new Date(1700000000000L);
            long id = db.putEntity(u, true);
            Assert.assertTrue(id > 0L);
            Assert.assertEquals(Long.valueOf(id), u.getId());

            IndexedUserV1 got = db.getTable(IndexedUserV1.class, id);
            Assert.assertEquals("alice", got.name);
            Assert.assertEquals(25, got.age);
            Assert.assertEquals(999L, got.score);

            try (DsEqIndexStore ageIdx = openIndexStore(db, IndexedUserV1.class, "age");
                 DsEqIndexStore scoreIdx = openIndexStore(db, IndexedUserV1.class, "score")) {
                Assert.assertTrue(ageIdx.containsIndex(25L, id));
                Assert.assertTrue(scoreIdx.containsIndex(999L, id));
                Assert.assertEquals(1, ageIdx.size());
                Assert.assertEquals(1, scoreIdx.size());
            }
        }
    }

    @Test
    public void testUpdateIndexedColumnsOldRemovedNewPut() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot)) {
            IndexedUserV1 u = new IndexedUserV1();
            u.name = "bob";
            u.age = 30;
            u.score = 1111L;
            long id = db.putEntity(u, true);

            // 修改索引列，验证旧值从索引中删除
            u.age = 31;
            u.score = 2222L;
            db.putEntity(u, false);

            try (DsEqIndexStore ageIdx = openIndexStore(db, IndexedUserV1.class, "age");
                 DsEqIndexStore scoreIdx = openIndexStore(db, IndexedUserV1.class, "score")) {
                Assert.assertFalse("旧 age 值 30 应该被删除", ageIdx.containsIndex(30L, id));
                Assert.assertTrue("新 age 值 31 应该写入", ageIdx.containsIndex(31L, id));

                Assert.assertFalse("旧 score 值 1111 应该被删除", scoreIdx.containsIndex(1111L, id));
                Assert.assertTrue("新 score 值 2222 应该写入", scoreIdx.containsIndex(2222L, id));
                Assert.assertEquals(1, ageIdx.size());
                Assert.assertEquals(1, scoreIdx.size());
            }
        }
    }

    @Test
    public void testRemoveTableAutoDropAllIndexes() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot)) {
            IndexedUserV1 u = new IndexedUserV1();
            u.name = "carol";
            u.age = 40;
            u.score = 7777L;
            long id = db.putEntity(u, true);

            Assert.assertTrue(db.removeTable(IndexedUserV1.class, id, false));

            try (DsEqIndexStore ageIdx = openIndexStore(db, IndexedUserV1.class, "age");
                 DsEqIndexStore scoreIdx = openIndexStore(db, IndexedUserV1.class, "score")) {
                Assert.assertFalse(ageIdx.containsIndex(40L, id));
                Assert.assertFalse(scoreIdx.containsIndex(7777L, id));
                Assert.assertEquals(0, ageIdx.size());
                Assert.assertEquals(0, scoreIdx.size());
            }
        }
    }

    @Test
    public void testOnlineSchemaV2NewIndexedColumnCorrectlyMaintained() throws Exception {
        // 1) 先写 1 行 V2（带 city indexed String）
        try (DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot)) {
            IndexedUserV2 u = new IndexedUserV2();
            u.name = "dora";
            u.age = 22;
            u.score = 12345L;
            u.createdAt = new Date(1710000000000L);
            u.city = "beijing";
            long id = db.putEntity(u, true);

            IndexedUserV2 got = db.getTable(IndexedUserV2.class, id);
            Assert.assertEquals("beijing", got.city);

            try (DsEqIndexStore cityIdx = openIndexStore(db, IndexedUserV2.class, "city")) {
                Assert.assertTrue("新 indexed String 列应入索引", cityIdx.containsIndex("beijing", id));
                Assert.assertEquals(1, cityIdx.size());
            }

            // 更新 city
            u.city = "shenzhen";
            db.putEntity(u, false);
            try (DsEqIndexStore cityIdx = openIndexStore(db, IndexedUserV2.class, "city")) {
                Assert.assertFalse("旧 city 值 beijing 应被删除", cityIdx.containsIndex("beijing", id));
                Assert.assertTrue("新 city 值 shenzhen 应写入", cityIdx.containsIndex("shenzhen", id));
                Assert.assertEquals(1, cityIdx.size());
            }
        }

        // 2) 先写 V1，再用 V2 作为相同 clazz 写（同表、schema 未变，验证 indexed 列多次更新幂等）
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        try (DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot)) {
            IndexedUserV2 v2a = new IndexedUserV2();
            v2a.name = "eve";
            v2a.age = 18;
            v2a.score = 88L;
            v2a.createdAt = new Date(1720000000000L);
            v2a.city = "shanghai";
            long id = db.putEntity(v2a, true);

            IndexedUserV2 v2b = db.getTable(IndexedUserV2.class, id);
            Assert.assertEquals("shanghai", v2b.city);
            Assert.assertEquals(18, v2b.age);
            Assert.assertEquals(88L, v2b.score);

            v2b.city = "guangzhou";
            v2b.age = 19;
            long id2 = db.putEntity(v2b, false);
            Assert.assertEquals(id, id2);

            try (DsEqIndexStore cityIdx = openIndexStore(db, IndexedUserV2.class, "city");
                 DsEqIndexStore ageIdx = openIndexStore(db, IndexedUserV2.class, "age")) {
                Assert.assertFalse("旧 city=shanghai 应被删除", cityIdx.containsIndex("shanghai", id));
                Assert.assertTrue("新 city=guangzhou 应写入", cityIdx.containsIndex("guangzhou", id));
                Assert.assertFalse("旧 age=18 应被删除", ageIdx.containsIndex(18L, id));
                Assert.assertTrue("新 age=19 应写入", ageIdx.containsIndex(19L, id));
            }
        }
    }
}
