package ds;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.schema.EntityIndexUtil;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * P56 T3：行级迁移正确性（{@code OnlineDdlCoordinator.migrateLegacyRows}）。
 *
 * <p>覆盖 spec §5.2 T3：</p>
 * <ol>
 *   <li>单行 old(28B)→new(68B) rewrite 后 {@code getTable} 值一致；</li>
 *   <li>全表 N 行迁移后 0 剩余（全部可按新 schema 读回，收尾退出 compat）；</li>
 *   <li>新增列 {@code @DsField.defaultValue} 正确补齐；</li>
 *   <li>迁移后写路径正常（原地更新 + 新行分配 id 均可读）。</li>
 * </ol>
 */
public class P56_T3_MigrationTest {

    /** 旧 schema V1：id(8) + name(16) + age(4) = 28B（仅产出老行物理字节）。 */
    public static class MiV1 extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        public MiV1() {}
    }

    /** 当前 schema V2：+newCol(8, default=42) +note(32, default="hello-mig") = 68B。 */
    public static class MiV2 extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        @DsField(name = "newCol", length = 8, defaultValue = "42")
        public long newCol;
        @DsField(name = "note", length = 32, defaultValue = "hello-mig")
        public String note;
        public MiV2() {}
    }

    private static final int V1_LEN = 28;

    private File root;

    @Before
    public void before() throws Exception {
        root = Files.createTempDirectory("dsdb_p56_t3_").toFile();
        // 预置 leaf=28：等价于 V1 时期 createMeta 已 seed 的行长度
        EntityIndexUtil.writeLegacyRowLength(root, MiV2.class, V1_LEN);
    }

    @After
    public void after() {
        if (root != null && root.exists()) {
            try { DsDatabaseLocal.forceResetSchemaMetaForTest(root); } catch (Exception ignore) {}
            deleteRecursive(root);
        }
    }

    // ==================== ① 单行 rewrite 后值一致 ====================

    @Test
    public void t01_singleRowRewriteReadBackConsistent() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            long id = seedLegacyRow(db, 0);
            int migrated = db.migrateLegacyRows(MiV2.class, 8);
            Assert.assertEquals("单行应迁移 1", 1, migrated);

            MiV2 v = db.getTable(MiV2.class, id);
            Assert.assertNotNull(v);
            Assert.assertEquals("user_0", v.name);
            Assert.assertEquals(20, v.age);
            Assert.assertEquals("迁移收尾退出 compat", -1, legacyRowLength(db, MiV2.class));
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== ② 全表 N 行迁移后 0 剩余 ====================

    @Test
    public void t02_fullTableMigrationLeavesZeroRemaining() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            int n = 5;
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                ids.add(seedLegacyRow(db, i));
            }
            int migrated = db.migrateLegacyRows(MiV2.class, 2);
            Assert.assertEquals("N 行全部迁移", n, migrated);

            for (int i = 0; i < n; i++) {
                MiV2 v = db.getTable(MiV2.class, ids.get(i));
                Assert.assertNotNull("迁移后 id=" + ids.get(i) + " 可读回", v);
                Assert.assertEquals("user_" + i, v.name);
                Assert.assertEquals(20 + i, v.age);
            }
            Assert.assertEquals("0 剩余 → 退出 compat", -1, legacyRowLength(db, MiV2.class));
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== ③ 新增列 default 正确补齐 ====================

    @Test
    public void t03_newColumnsDefaultFilledAfterMigration() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            long id = seedLegacyRow(db, 2);
            db.migrateLegacyRows(MiV2.class, 4);

            MiV2 v = db.getTable(MiV2.class, id);
            Assert.assertEquals("newCol default", 42L, v.newCol);
            Assert.assertEquals("note default", "hello-mig", v.note);
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== ④ 迁移后写路径正常 ====================

    @Test
    public void t04_postMigrationWritePathWorks() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            long id = seedLegacyRow(db, 1);
            db.migrateLegacyRows(MiV2.class, 4);

            // 原地更新（新长度 == meta.rowLength）
            MiV2 upd = new MiV2();
            upd.setId(id);
            upd.name = "post";
            upd.age = 77;
            upd.newCol = 123L;
            upd.note = "post-mig";
            db.putEntity(upd, false);

            MiV2 back = db.getTable(MiV2.class, id);
            Assert.assertEquals("post", back.name);
            Assert.assertEquals(77, back.age);
            Assert.assertEquals(123L, back.newCol);
            Assert.assertEquals("post-mig", back.note);

            // 新行分配 id 后可按新 schema 读回
            MiV2 fresh = new MiV2();
            fresh.name = "fresh";
            fresh.age = 5;
            fresh.newCol = 9L;
            fresh.note = "fresh-note";
            long newId = db.putEntity(fresh, true);
            MiV2 freshBack = db.getTable(MiV2.class, newId);
            Assert.assertEquals("fresh", freshBack.name);
            Assert.assertEquals(9L, freshBack.newCol);
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== helpers ====================

    private long seedLegacyRow(DsDatabaseLocal db, int i) throws Exception {
        DsFixedBucketStore bs = bucketStore(db);
        String space = MiV2.class.getName();
        String type = rowType();
        long id = bs.getNewId(space, type, V1_LEN);
        MiV1 v1 = new MiV1();
        v1.setId(id);
        v1.name = "user_" + i;
        v1.age = 20 + i;
        ByteBuffer bb = v1.toBytes();
        byte[] b = new byte[bb.remaining()];
        bb.get(b);
        bs.overwrite(space, type, id, b);
        idSetForClass(db, MiV2.class).add(Long.valueOf(id));
        return id;
    }

    private String rowType() {
        return EntityIndexUtil.indexOf(root, MiV2.class).rowType;
    }

    private static int legacyRowLength(DsDatabaseLocal db, Class<? extends DsTableAdapter> clazz) throws Exception {
        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        metaOf.setAccessible(true);
        Object meta = metaOf.invoke(db, clazz);
        Field f = meta.getClass().getDeclaredField("legacyRowLength");
        f.setAccessible(true);
        return (Integer) f.get(meta);
    }

    private static DsFixedBucketStore bucketStore(DsDatabaseLocal db) throws Exception {
        Field f = DsDatabaseLocal.class.getDeclaredField("bucketStore");
        f.setAccessible(true);
        return (DsFixedBucketStore) f.get(db);
    }

    private static DsHashSet idSetForClass(DsDatabaseLocal db, Class<? extends DsTableAdapter> clazz) throws Exception {
        Method m = DsDatabaseLocal.class.getDeclaredMethod("idSetForClass", Class.class);
        m.setAccessible(true);
        return (DsHashSet) m.invoke(db, clazz);
    }

    private static void closeQuietly(DsDatabaseLocal db) {
        try { db.close(); } catch (Exception ignore) {}
    }

    private static void deleteRecursive(File f) {
        if (f == null) {
            return;
        }
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) {
                for (File c : ch) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }
}
