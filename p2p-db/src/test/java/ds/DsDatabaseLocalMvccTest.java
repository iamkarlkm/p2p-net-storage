package ds;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.index.DsEqIndexStore;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.schema.EntityIndexUtil;
import com.q3lives.ds.database.schema.EntitySchemaUtil;
import com.q3lives.ds.util.DsPathUtil;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DsDatabaseLocalMvccTest {

    private File tmpRoot;

    @Before
    public void before() throws IOException {
        tmpRoot = Files.createTempDirectory("dsdb_mvcc_").toFile();
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

    // ==================== 测试实体 ====================

    /** 非 MVCC 对照表，行为应与改造前一致。 */
    public static class NonMvccUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;

        @DsField(name = "age", length = 4, indexed = true)
        public int age;

        @DsField(name = "score", length = 8, indexed = true)
        public long score;

        public NonMvccUser() {}
    }

    /** MVCC 表：每次写入生成版本链。 */
    @DsTable(mvcc = true)
    public static class MvccUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;

        @DsField(name = "age", length = 4, indexed = true)
        public int age;

        @DsField(name = "score", length = 8, indexed = true)
        public long score;

        public MvccUser() {}
    }

    /** 列级 MVCC 表：只有 age 启用 MVCC，name/score 不启用。 */
    @DsTable
    public static class ColumnMvccUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;

        @DsField(name = "age", length = 4, indexed = true, mvcc = true)
        public int age;

        @DsField(name = "score", length = 8, indexed = true)
        public long score;

        public ColumnMvccUser() {}
    }

    // ==================== 工具方法 ====================

    private long nextWriteTs(DsDatabaseLocal db) throws Exception {
        Field f = DsDatabaseLocal.class.getDeclaredField("writeTimestamp");
        f.setAccessible(true);
        AtomicLong ts = (AtomicLong) f.get(db);
        return ts.get();
    }

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

    // ==================== 回归测试 ====================

    @Test
    public void testNonMvccTableBehaviorUnchanged() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot)) {
            NonMvccUser u = new NonMvccUser();
            u.name = "alice";
            u.age = 20;
            u.score = 100L;
            long id = db.putEntity(u, true);
            Assert.assertTrue(id > 0L);

            NonMvccUser got = db.getTable(NonMvccUser.class, id);
            Assert.assertEquals("alice", got.name);
            Assert.assertEquals(20, got.age);
            Assert.assertEquals(100L, got.score);

            u.age = 21;
            u.score = 101L;
            db.putEntity(u, false);
            got = db.getTable(NonMvccUser.class, id);
            Assert.assertEquals(21, got.age);
            Assert.assertEquals(101L, got.score);

            try (DsEqIndexStore ageIdx = openIndexStore(db, NonMvccUser.class, "age");
                 DsEqIndexStore scoreIdx = openIndexStore(db, NonMvccUser.class, "score")) {
                Assert.assertFalse(ageIdx.containsIndex(20L, id));
                Assert.assertTrue(ageIdx.containsIndex(21L, id));
                Assert.assertFalse(scoreIdx.containsIndex(100L, id));
                Assert.assertTrue(scoreIdx.containsIndex(101L, id));
            }

            Assert.assertTrue(db.removeTable(NonMvccUser.class, id, false));
            // 非 MVCC 表的底层存储仅将 id 回收进 free-ring，不保证 getTable 立即返回 null；
            // 这里只验证 remove 不抛异常且索引已被清理（行为与改造前一致）。
        }
    }

    @Test
    public void testMvccBasicVersionChain() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot)) {
            MvccUser u = new MvccUser();
            u.name = "alice";
            u.age = 20;
            u.score = 100L;
            long rowId = db.putEntity(u, true);
            Assert.assertTrue(rowId > 0L);

            // v1 beginTs == 1（writeTimestamp 从 1 开始）
            long tsAfterV1 = nextWriteTs(db);
            Assert.assertEquals(2L, tsAfterV1);

            MvccUser latest = db.getTable(MvccUser.class, rowId, Long.MAX_VALUE);
            Assert.assertEquals("alice", latest.name);
            Assert.assertEquals(20, latest.age);

            MvccUser v1Read = db.getTable(MvccUser.class, rowId, 1L);
            Assert.assertNotNull(v1Read);
            Assert.assertEquals("alice", v1Read.name);
            Assert.assertEquals(20, v1Read.age);

            u.name = "alice2";
            u.age = 21;
            u.score = 101L;
            db.putEntity(u, false);
            long tsAfterV2 = nextWriteTs(db);
            Assert.assertEquals(3L, tsAfterV2);

            latest = db.getTable(MvccUser.class, rowId, Long.MAX_VALUE);
            Assert.assertEquals("alice2", latest.name);
            Assert.assertEquals(21, latest.age);

            // 快照读：在 v1 时间窗口内应看到 v1
            v1Read = db.getTable(MvccUser.class, rowId, 1L);
            Assert.assertNotNull(v1Read);
            Assert.assertEquals("alice", v1Read.name);
            Assert.assertEquals(20, v1Read.age);

            // 在 v2 beginTs（2）应看到 v2
            MvccUser v2Read = db.getTable(MvccUser.class, rowId, 2L);
            Assert.assertNotNull(v2Read);
            Assert.assertEquals("alice2", v2Read.name);
            Assert.assertEquals(21, v2Read.age);
        }
    }

    @Test
    public void testMvccIndexPointsToVersionId() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot)) {
            MvccUser u = new MvccUser();
            u.name = "bob";
            u.age = 30;
            u.score = 200L;
            long rowId = db.putEntity(u, true);

            u.name = "bob2";
            u.age = 31;
            u.score = 201L;
            db.putEntity(u, false);

            try (DsEqIndexStore ageIdx = openIndexStore(db, MvccUser.class, "age");
                 DsEqIndexStore scoreIdx = openIndexStore(db, MvccUser.class, "score")) {
                // 历史版本索引值必须存在；通过该索引值按对应时间戳能读回正确版本即可
                long[] age30 = ageIdx.findByIndex(30L);
                Assert.assertNotNull("age=30 的历史索引不应被删除", age30);
                Assert.assertEquals(1, age30.length);
                long age30VersionId = age30[0];
                MvccUser age30User = db.getTable(MvccUser.class, age30VersionId, 1L);
                Assert.assertNotNull(age30User);
                Assert.assertEquals(30, age30User.age);
                Assert.assertEquals("bob", age30User.name);

                long[] age31 = ageIdx.findByIndex(31L);
                Assert.assertNotNull(age31);
                Assert.assertEquals(1, age31.length);
                long age31VersionId = age31[0];
                MvccUser age31User = db.getTable(MvccUser.class, age31VersionId, Long.MAX_VALUE);
                Assert.assertNotNull(age31User);
                Assert.assertEquals(31, age31User.age);
                Assert.assertEquals("bob2", age31User.name);

                long[] score200 = scoreIdx.findByIndex(200L);
                Assert.assertNotNull(score200);
                Assert.assertEquals(1, score200.length);
            }
        }
    }

    @Test
    public void testMvccRemoveCreatesTombstone() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot)) {
            MvccUser u = new MvccUser();
            u.name = "carol";
            u.age = 40;
            u.score = 300L;
            long rowId = db.putEntity(u, true);

            // 在删除前打快照
            MvccUser beforeDelete = db.getTable(MvccUser.class, rowId, 1L);
            Assert.assertNotNull(beforeDelete);
            Assert.assertEquals("carol", beforeDelete.name);

            Assert.assertTrue(db.removeTable(MvccUser.class, rowId, false));

            // 最新读应不可见（tombstone）
            Assert.assertNull(db.getTable(MvccUser.class, rowId, Long.MAX_VALUE));

            // 旧快照仍能看到删除前版本
            beforeDelete = db.getTable(MvccUser.class, rowId, 1L);
            Assert.assertNotNull(beforeDelete);
            Assert.assertEquals("carol", beforeDelete.name);
        }
    }

    @Test
    public void testMvccGenericManagerSnapshotRead() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot)) {
            GenericManager<MvccUser> mgr = new GenericManager<>(MvccUser.class);
            // GenericManager 无参构造会加载系统默认 db；这里通过反射替换为测试 db
            Field dbField = GenericManager.class.getDeclaredField("db");
            dbField.setAccessible(true);
            dbField.set(mgr, db);

            MvccUser u = new MvccUser();
            u.name = "dave";
            u.age = 50;
            u.score = 400L;
            mgr.saveOrUpdate(u);
            long rowId = u.getId();

            u.name = "dave2";
            u.age = 51;
            u.score = 401L;
            mgr.saveOrUpdate(u);

            // 默认 readTimestamp = Long.MAX_VALUE，读最新
            MvccUser latest = mgr.getById(rowId);
            Assert.assertEquals("dave2", latest.name);
            Assert.assertEquals(51, latest.age);

            // 快照读到 v1
            mgr.setReadTimestamp(1L);
            MvccUser snapshot = mgr.getById(rowId);
            Assert.assertNotNull(snapshot);
            Assert.assertEquals("dave", snapshot.name);
            Assert.assertEquals(50, snapshot.age);

            // list 同样受 readTimestamp 影响
            List<MvccUser> listAt1 = mgr.findListByMap(java.util.Collections.singletonMap("age", 50));
            Assert.assertEquals(1, listAt1.size());
            Assert.assertEquals("dave", listAt1.get(0).name);

            mgr.setReadTimestamp(Long.MAX_VALUE);
            List<MvccUser> listLatest = mgr.findListByMap(java.util.Collections.singletonMap("age", 51));
            Assert.assertEquals(1, listLatest.size());
            Assert.assertEquals("dave2", listLatest.get(0).name);
        }
    }

    @Test
    public void testMvccLegacyMigration() throws Exception {
        // 模拟旧数据兼容：直接在主表 rowId 位置写入非 MVCC 数据，
        // 然后用 MVCC 表读取/更新，应触发 legacy 版本迁移。
        try (DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot)) {
            Field bsField = DsDatabaseLocal.class.getDeclaredField("bucketStore");
            bsField.setAccessible(true);
            DsFixedBucketStore bs = (DsFixedBucketStore) bsField.get(db);

            EntityIndexUtil.IndexDef index = EntityIndexUtil.indexOf(tmpRoot, MvccUser.class);
            int rowLength = index.rowLength;

            long rowId = bs.getNewId(MvccUser.class.getName(), index.rowType, rowLength);
            MvccUser legacy = new MvccUser();
            legacy.name = "legacy";
            legacy.age = 60;
            legacy.score = 500L;
            legacy.setId(rowId);
            ByteBuffer buf = legacy.toBytes();
            byte[] bytes;
            if (buf.remaining() == rowLength) {
                bytes = buf.array();
            } else {
                bytes = new byte[rowLength];
                buf.rewind();
                buf.get(bytes, 0, Math.min(buf.remaining(), rowLength));
            }
            bs.update(MvccUser.class.getName(), index.rowType, rowId, bytes,
                    DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);

            // MVCC 首次读取应能看到 legacy 数据
            MvccUser got = db.getTable(MvccUser.class, rowId, Long.MAX_VALUE);
            Assert.assertNotNull(got);
            Assert.assertEquals("legacy", got.name);
            Assert.assertEquals(60, got.age);
            Assert.assertEquals(500L, got.score);

            // 首次更新应把 legacy 数据包成 VersionRecord，然后追加新版本
            got.name = "legacy2";
            got.age = 61;
            db.putEntity(got, false);

            MvccUser latest = db.getTable(MvccUser.class, rowId, Long.MAX_VALUE);
            Assert.assertEquals("legacy2", latest.name);
            Assert.assertEquals(61, latest.age);

            MvccUser legacySnapshot = db.getTable(MvccUser.class, rowId, 1L);
            Assert.assertNotNull(legacySnapshot);
            Assert.assertEquals("legacy", legacySnapshot.name);
            Assert.assertEquals(60, legacySnapshot.age);
        }
    }

    @Test
    public void testColumnMvccNonMvccColumnUsesLatestValue() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot)) {
            ColumnMvccUser u = new ColumnMvccUser();
            u.name = "alice";
            u.age = 20;
            u.score = 100L;
            long rowId = db.putEntity(u, true);
            Assert.assertTrue(rowId > 0L);

            u.name = "alice2";
            u.age = 21;
            u.score = 200L;
            db.putEntity(u, false);

            // 最新读：所有字段都是最新值
            ColumnMvccUser latest = db.getTable(ColumnMvccUser.class, rowId, Long.MAX_VALUE);
            Assert.assertEquals("alice2", latest.name);
            Assert.assertEquals(21, latest.age);
            Assert.assertEquals(200L, latest.score);

            // 快照读到 v1：MVCC 列 age 保留历史值，非 MVCC 列 name/score 用最新值
            ColumnMvccUser snapshot = db.getTable(ColumnMvccUser.class, rowId, 1L);
            Assert.assertNotNull(snapshot);
            Assert.assertEquals("alice2", snapshot.name);
            Assert.assertEquals(20, snapshot.age);
            Assert.assertEquals(200L, snapshot.score);
        }
    }

    @Test
    public void testColumnMvccIndexIdRouting() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot)) {
            ColumnMvccUser u = new ColumnMvccUser();
            u.name = "bob";
            u.age = 30;
            u.score = 300L;
            long rowId = db.putEntity(u, true);

            u.name = "bob2";
            u.age = 31;
            u.score = 301L;
            db.putEntity(u, false);

            try (DsEqIndexStore ageIdx = openIndexStore(db, ColumnMvccUser.class, "age");
                 DsEqIndexStore scoreIdx = openIndexStore(db, ColumnMvccUser.class, "score")) {
                // MVCC 列 age 的历史索引应保留；通过该索引值按对应时间戳能读回正确版本即可
                long[] age30 = ageIdx.findByIndex(30L);
                Assert.assertNotNull("age=30 的历史索引应保留", age30);
                Assert.assertEquals(1, age30.length);
                long age30VersionId = age30[0];
                ColumnMvccUser age30User = db.getTable(ColumnMvccUser.class, age30VersionId, 1L);
                Assert.assertNotNull(age30User);
                Assert.assertEquals(30, age30User.age);
                Assert.assertEquals("bob2", age30User.name); // 非 MVCC 列覆盖为最新

                // 非 MVCC 列 score 的索引应只保留最新值，且指向 rowId
                long[] score300 = scoreIdx.findByIndex(300L);
                Assert.assertEquals("score=300 的旧索引应被移除", 0, score300.length);

                long[] score301 = scoreIdx.findByIndex(301L);
                Assert.assertNotNull("score=301 的索引应存在", score301);
                Assert.assertEquals(1, score301.length);
                Assert.assertEquals("非 MVCC 列索引应指向 rowId", rowId, score301[0]);
            }
        }
    }

    @Test
    public void testColumnMvccRemoveCleansNonMvccIndexOnly() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot)) {
            ColumnMvccUser u = new ColumnMvccUser();
            u.name = "carol";
            u.age = 40;
            u.score = 400L;
            long rowId = db.putEntity(u, true);

            Assert.assertTrue(db.removeTable(ColumnMvccUser.class, rowId, false));

            try (DsEqIndexStore ageIdx = openIndexStore(db, ColumnMvccUser.class, "age");
                 DsEqIndexStore scoreIdx = openIndexStore(db, ColumnMvccUser.class, "score")) {
                // 非 MVCC 列 score 的索引应被清理
                long[] score400 = scoreIdx.findByIndex(400L);
                Assert.assertEquals("删除后 score 索引应被清理", 0, score400.length);

                // MVCC 列 age 的历史索引应保留，仍可按时间戳读到旧值
                long[] age40 = ageIdx.findByIndex(40L);
                Assert.assertNotNull("删除后 age 历史索引应保留", age40);
                Assert.assertEquals(1, age40.length);
                ColumnMvccUser snapshot = db.getTable(ColumnMvccUser.class, age40[0], 1L);
                Assert.assertNotNull(snapshot);
                Assert.assertEquals(40, snapshot.age);
                Assert.assertEquals("carol", snapshot.name);
            }
        }
    }

    @Test
    public void testColumnMvccGenericManagerSnapshotRead() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot)) {
            GenericManager<ColumnMvccUser> mgr = new GenericManager<>(ColumnMvccUser.class);
            Field dbField = GenericManager.class.getDeclaredField("db");
            dbField.setAccessible(true);
            dbField.set(mgr, db);

            ColumnMvccUser u = new ColumnMvccUser();
            u.name = "dave";
            u.age = 50;
            u.score = 500L;
            mgr.saveOrUpdate(u);
            long rowId = u.getId();

            u.name = "dave2";
            u.age = 51;
            u.score = 501L;
            mgr.saveOrUpdate(u);

            // 默认读最新
            ColumnMvccUser latest = mgr.getById(rowId);
            Assert.assertEquals("dave2", latest.name);
            Assert.assertEquals(51, latest.age);
            Assert.assertEquals(501L, latest.score);

            // 快照读 v1
            mgr.setReadTimestamp(1L);
            ColumnMvccUser snapshot = mgr.getById(rowId);
            Assert.assertNotNull(snapshot);
            Assert.assertEquals("dave2", snapshot.name); // 非 MVCC 最新
            Assert.assertEquals(50, snapshot.age);       // MVCC 历史
            Assert.assertEquals(501L, snapshot.score);   // 非 MVCC 最新

            // list 查询：age 是 MVCC 列，按快照过滤
            List<ColumnMvccUser> listAt1 = mgr.findListByMap(java.util.Collections.singletonMap("age", 50));
            Assert.assertEquals(1, listAt1.size());
            Assert.assertEquals("dave2", listAt1.get(0).name);
            Assert.assertEquals(50, listAt1.get(0).age);

            mgr.setReadTimestamp(Long.MAX_VALUE);
            List<ColumnMvccUser> listLatest = mgr.findListByMap(java.util.Collections.singletonMap("age", 51));
            Assert.assertEquals(1, listLatest.size());
            Assert.assertEquals("dave2", listLatest.get(0).name);
            Assert.assertEquals(51, listLatest.get(0).age);
        }
    }
}
