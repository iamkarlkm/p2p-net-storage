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
 * P56 T4：迁移幂等 + 断点续跑。
 *
 * <p>覆盖 spec §5.2 T4 / R8：</p>
 * <ol>
 *   <li>重复 {@code migrateLegacyRows} 短路（leaf 清后再次调用 → 0）；</li>
 *   <li>中途中断 → 续跑不丢行、不重复写（已完成行被跳过）；</li>
 *   <li>进度单调（剩余工作逐次下降至 0）。</li>
 * </ol>
 *
 * <p>「中断」以直接调用真实内部 {@code OnlineDdlCoordinator.rewriteLegacyRow} 迁移前 k 行来模拟：
 * 这 k 行经同一代码路径完成迁移（Registry payload 已达新长度），续跑时应被
 * {@code isAlreadyMigrated} 跳过。</p>
 */
public class P56_T4_IdempotentTest {

    /** 旧 schema V1：28B。 */
    public static class IdV1 extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        public IdV1() {}
    }

    /** 当前 schema V2：68B。 */
    public static class IdV2 extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        @DsField(name = "newCol", length = 8, defaultValue = "42")
        public long newCol;
        @DsField(name = "note", length = 32, defaultValue = "hello-idem")
        public String note;
        public IdV2() {}
    }

    private static final int V1_LEN = 28;

    private File root;

    @Before
    public void before() throws Exception {
        root = Files.createTempDirectory("dsdb_p56_t4_").toFile();
        EntityIndexUtil.writeLegacyRowLength(root, IdV2.class, V1_LEN);
    }

    @After
    public void after() {
        if (root != null && root.exists()) {
            try { DsDatabaseLocal.forceResetSchemaMetaForTest(root); } catch (Exception ignore) {}
            deleteRecursive(root);
        }
    }

    // ==================== ① 重复调用短路 ====================

    @Test
    public void t01_repeatCallShortCircuits() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            for (int i = 0; i < 3; i++) {
                seedLegacyRow(db, i);
            }
            Assert.assertEquals("首次迁移 3 行", 3, db.migrateLegacyRows(IdV2.class, 2));
            Assert.assertEquals("重复调用短路 → 0（leaf 已清，非 compat）", 0, db.migrateLegacyRows(IdV2.class, 2));
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== ② 断点续跑 0 丢 / 0 重 ====================

    @Test
    public void t02_resumeAfterInterruptionNoLossNoDup() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                ids.add(seedLegacyRow(db, i));
            }
            // 模拟中断：前 2 行已按同一路径迁移完成
            preMigrate(db, ids.subList(0, 2));

            int migrated = db.migrateLegacyRows(IdV2.class, 2);
            Assert.assertEquals("续跑只应迁移剩余 2 行（已完成行 0 重复写）", 2, migrated);

            for (int i = 0; i < 4; i++) {
                IdV2 v = db.getTable(IdV2.class, ids.get(i));
                Assert.assertNotNull("续跑后 id=" + ids.get(i) + " 不丢", v);
                Assert.assertEquals("user_" + i, v.name);
                Assert.assertEquals(20 + i, v.age);
            }
            Assert.assertEquals("续跑后再次调用 → 0", 0, db.migrateLegacyRows(IdV2.class, 2));
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== ③ 进度单调下降 ====================

    @Test
    public void t03_progressMonotonicDecreasingToZero() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ids.add(seedLegacyRow(db, i));
            }
            preMigrate(db, ids.subList(0, 1)); // 已完成 1 行

            int c1 = db.migrateLegacyRows(IdV2.class, 2);
            int c2 = db.migrateLegacyRows(IdV2.class, 2);
            Assert.assertEquals("首轮迁移剩余 4 行", 4, c1);
            Assert.assertEquals("次轮 0", 0, c2);
            Assert.assertTrue("进度单调非增", c1 >= c2);
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== helpers ====================

    private long seedLegacyRow(DsDatabaseLocal db, int i) throws Exception {
        DsFixedBucketStore bs = bucketStore(db);
        String space = IdV2.class.getName();
        String type = rowType();
        long id = bs.getNewId(space, type, V1_LEN);
        IdV1 v1 = new IdV1();
        v1.setId(id);
        v1.name = "user_" + i;
        v1.age = 20 + i;
        ByteBuffer bb = v1.toBytes();
        byte[] b = new byte[bb.remaining()];
        bb.get(b);
        bs.overwrite(space, type, id, b);
        idSetForClass(db, IdV2.class).add(Long.valueOf(id));
        return id;
    }

    /** 直接调用真实内部 rewriteLegacyRow 迁移指定行（模拟中断前已完成的部分）。 */
    private void preMigrate(DsDatabaseLocal db, List<Long> ids) throws Exception {
        Field cf = DsDatabaseLocal.class.getDeclaredField("odCoordinator");
        cf.setAccessible(true);
        Object coord = cf.get(db);
        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        metaOf.setAccessible(true);
        Object meta = metaOf.invoke(db, IdV2.class);
        Method rw = coord.getClass().getDeclaredMethod("rewriteLegacyRow", meta.getClass(), long.class, int.class);
        rw.setAccessible(true);
        for (Long id : ids) {
            rw.invoke(coord, meta, id.longValue(), V1_LEN);
        }
    }

    private String rowType() {
        return EntityIndexUtil.indexOf(root, IdV2.class).rowType;
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
