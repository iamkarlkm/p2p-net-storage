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
 * P56 T1：运行期定性（Q3=A 前置，Fail→Fix→Green）。
 *
 * <p>
 * 用真实 {@link DsDatabaseLocal}（row-plane add-column：28B → 68B）验证三件事：</p>
 * <ol>
 * <li>旧 compat 判据 {@code sample != index.rowLength} 两侧恒等 → 死分支；</li>
 * <li>加列后按新长度读老行（over-read）是否真实抛错；</li>
 * <li>加列后对老行 {@code putEntity}（新长度）是否发生 id 漂移（写进新 power 桶）。</li>
 * </ol>
 *
 * <p>
 * 说明：为在同一 JVM 内复现「表名不变、行变长」的历史状态，本测试以
 * {@link EntityIndexUtil#writeLegacyRowLength} 预置 leaf=28（等价于 V1 时期 createMeta
 * 的 seed）， 并以 V1 实体 {@code toBytes()} 产出的 28B 载荷经 {@link DsFixedBucketStore}
 * 逐字节落盘 （与老代码写出的物理字节完全一致），从而在 V2 表路径下构造出「真实老行」。</p>
 *
 * <p>
 * <b>T1 实测结论（固化读/写口径，Q3=A）</b>：</p>
 * <ol>
 * <li>旧 compat 判据 {@code sample != index.rowLength} 两侧同源恒等（均 68）→
 * 死分支（t01）。</li>
 * <li>加列后按新长度(68) 读老行(28B/unit32) → 抛 {@code "data overflow bucket unit"}（t02
 * over-read 实锤）。</li>
 * <li>按旧长度(28) 读老行安全，{@code load()} 以 {@code @DsField.defaultValue}
 * 补齐新增列（t03）。</li>
 * <li>写路径 {@code putEntity} 新长度命中 {@code newPower>oldPower} → 分配新 id 至 128B
 * 桶（id 漂移实锤）， 老 id 处的 bucket 单元不可再按新长度读（t04）。漂移由
 * {@code DsFixedBucketStore.update} 内部完成， 返回值被既有调用方丢弃。</li>
 * <li><b>迁移口径</b>：HYBRID（默认）下 {@code getTable} 优先读 Registry payload（以逻辑 rowId
 * 为键）， {@code putEntity} 会把「已 pad 到新长度」的 payload 写回该 rowId → 迁移后即可按新 schema 读回；
 * bucket 层的 id 漂移因 power 分离（旧 power5 / 新 power7）不会被本表复用，不构成读一致性风险（t05）。</li>
 * </ol>
 */
public class P56_T1_LegacyProbeTest {

    /**
     * 旧 schema V1：id(8) + name(16) + age(4) = 28B（仅用于产出老行物理字节）。
     */
    public static class ProbeV1 extends DsTableAdapter {

        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;

        public ProbeV1() {
        }
    }

    /**
     * 当前 schema V2：V1 + newCol(8, default=42) + note(32,
     * default="hello-online") = 68B。
     */
    public static class ProbeV2 extends DsTableAdapter {

        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        @DsField(name = "newCol", length = 8, defaultValue = "42")
        public long newCol;
        @DsField(name = "note", length = 32, defaultValue = "hello-online")
        public String note;

        public ProbeV2() {
        }
    }

    private static final int V1_LEN = 28;
    private static final int V2_LEN = 68;

    private File root;

    @Before
    public void before() throws Exception {
        root = Files.createTempDirectory("dsdb_p56_t1_").toFile();
        // 预置 leaf=28：等价于 V1 时期 createMeta 已 seed 的行长度，必须早于任何 metaOf
        EntityIndexUtil.writeLegacyRowLength(root, ProbeV2.class, V1_LEN);
    }

    @After
    public void after() {
        if (root != null && root.exists()) {
            try {
                DsDatabaseLocal.forceResetSchemaMetaForTest(root);
            } catch (Exception ignore) {
            }
            deleteRecursive(root);
        }
    }

    // ==================== ① 死分支实证 ====================
    @Test
    public void t01_legacyCompatSignatureIsDeadCode() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            int sample = sampleRowLength(ProbeV2.class);
            int indexRowLength = EntityIndexUtil.indexOf(root, ProbeV2.class).rowLength;
            Assert.assertEquals("V2 行总长 = 68", V2_LEN, sample);
            Assert.assertEquals("旧判据 `sample != index.rowLength` 两侧同源恒等 → 分支恒不可达",
                    sample, indexRowLength);
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== ② over-read 实证（读路径） ====================
    @Test
    public void t02_readLegacyRowByNewLengthThrowsOverread() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            long id = seedLegacyRow(db, 0);
            Assert.assertEquals("leaf=28 → TableMeta.legacyRowLength=28（compat 生效）",
                    V1_LEN, legacyRowLength(db, ProbeV2.class));

            RuntimeException caught = null;
            try {
                db.getTable(ProbeV2.class, id);
            } catch (RuntimeException re) {
                caught = re;
            }
            Assert.assertNotNull("加列后按新长度(68)读老行(28B/unit32) 应抛 over-read 异常", caught);
            Throwable cause = caught.getCause() == null ? caught : caught.getCause();
            Assert.assertTrue("异常应源于 bucket 单元溢出: " + cause,
                    String.valueOf(cause).contains("data overflow bucket unit"));
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== ③ 老行按旧长度读取正确（迁移读口径安全） ====================
    @Test
    public void t03_readLegacyRowByLegacyLengthIsCorrect() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            long id = seedLegacyRow(db, 0);
            byte[] b28 = bucketStore(db).get(ProbeV2.class.getName(), rowType(), id, 0, V1_LEN);
            Assert.assertNotNull("按旧长度(28)可安全读出老行", b28);
            ProbeV2 v = new ProbeV2();
            v.load(ByteBuffer.wrap(b28));
            Assert.assertEquals("user_0", v.name);
            Assert.assertEquals(20, v.age);
            Assert.assertEquals("新增列走 @DsField.defaultValue", 42L, v.newCol);
            Assert.assertEquals("新增列走 @DsField.defaultValue", "hello-online", v.note);
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== ④ 写路径 id 漂移实证 ====================
    @Test
    public void t04_writeLegacyRowAtNewLengthDriftsToNewBucket() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            long id = seedLegacyRow(db, 0);
            String space = ProbeV2.class.getName();
            String type = rowType();
            Assert.assertFalse("迁移前 row space 不应存在 128B 桶", rowBucketExists(space, type, "data_128B.dat"));
            Assert.assertTrue("老行 28B → 32B 桶", rowBucketExists(space, type, "data_32B.dat"));

            ProbeV2 upd = new ProbeV2();
            upd.setId(id);
            upd.name = "user_x";
            upd.age = 99;
            db.putEntity(upd, false);

            Assert.assertTrue("新长度(68→power7)必须分配 row space 128B 桶：id 漂移实锤",
                    rowBucketExists(space, type, "data_128B.dat"));

            RuntimeException caught = null;
            try {
                bucketStore(db).get(space, type, id, 0, V2_LEN);
            } catch (RuntimeException re) {
                caught = re;
            }
            Assert.assertNotNull("登记 id 处仍不可按新长度读取（返回值被丢弃 → 老 id 未迁移）", caught);
            Assert.assertTrue(String.valueOf(caught), String.valueOf(caught).contains("data overflow bucket unit"));
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== ⑤ 迁移后可读回（固化迁移口径：HYBRID 下以 Registry payload 为准） ====================
    @Test
    public void t05_afterFullMigrationRowsReadableByNewSchema() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                ids.add(seedLegacyRow(db, i));
            }

            int migrated = db.migrateLegacyRows(ProbeV2.class, 2);
            Assert.assertEquals("3 行老行应全部 rewrite", 3, migrated);

            for (int i = 0; i < 3; i++) {
                long id = ids.get(i);
                ProbeV2 v = db.getTable(ProbeV2.class, id);
                Assert.assertNotNull("迁移后 id=" + id + " 必须可按新 schema 读回", v);
                Assert.assertEquals("user_" + i, v.name);
                Assert.assertEquals(20 + i, v.age);
                Assert.assertEquals(42L, v.newCol);
                Assert.assertEquals("hello-online", v.note);
            }
            Assert.assertEquals("迁移收尾后退出 compat", -1, legacyRowLength(db, ProbeV2.class));
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== helpers ====================
    private long seedLegacyRow(DsDatabaseLocal db, int i) throws Exception {
        DsFixedBucketStore bs = bucketStore(db);
        String space = ProbeV2.class.getName();
        String type = rowType();
        long id = bs.getNewId(space, type, V1_LEN);
        ProbeV1 v1 = new ProbeV1();
        v1.setId(id);
        v1.name = "user_" + i;
        v1.age = 20 + i;
        ByteBuffer bb = v1.toBytes();
        byte[] b = new byte[bb.remaining()];
        bb.get(b);
        Assert.assertEquals("V1 物理载荷 = 28B", V1_LEN, b.length);
        bs.overwrite(space, type, id, b);
        idSetForClass(db, ProbeV2.class).add(Long.valueOf(id));
        return id;
    }

    private String rowType() {
        return EntityIndexUtil.indexOf(root, ProbeV2.class).rowType;
    }

    /**
     * 仅在 row 数据目录（{space}/{type}/）内检查桶文件，避免命中 string_store / registry 同名桶。
     */
    private boolean rowBucketExists(String space, String type, String name) {
        File dir = new File(root, space.replace('.', '/') + "/" + type);
        return new File(dir, name).exists();
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

    private static void closeQuietly(DsDatabaseLocal db) {
        try {
            db.close();
        } catch (Exception ignore) {
        }
    }

    private static int sampleRowLength(Class<? extends DsTableAdapter> clazz) throws Exception {
        Method m = DsDatabaseLocal.class.getDeclaredMethod("sampleRowLength", Class.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, clazz);
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
}
