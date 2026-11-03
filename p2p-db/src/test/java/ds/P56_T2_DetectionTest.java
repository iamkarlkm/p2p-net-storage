package ds;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.schema.EntityIndexUtil;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * P56 T2：compat 检测修复 + {@code /.legacyRowLength} leaf 读写 helper。
 *
 * <p>覆盖 spec §5.2 T2：</p>
 * <ol>
 *   <li>{@code writeLegacyRowLength} 幂等覆盖；</li>
 *   <li>{@code readLegacyRowLength} 缺省（无 leaf）→ -1；</li>
 *   <li>{@code clearLegacyRowLength} 移除 leaf → 再读 -1；</li>
 *   <li>{@code createMeta} 读到 leaf → {@code TableMeta.legacyRowLength} 正确；</li>
 *   <li>无 leaf → {@code createMeta} seed 当前长度且 {@code legacyRowLength = -1}；</li>
 *   <li>IAE 守卫（null clazz / 负 rowLength）。</li>
 * </ol>
 */
public class P56_T2_DetectionTest {

    /** 当前 schema：id(8) + name(16) + age(4) + extra(8) = 36B。 */
    public static class DetV2 extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        @DsField(name = "extra", length = 8, defaultValue = "7")
        public long extra;
        public DetV2() {}
    }

    private static final int V2_LEN = 36;
    private static final int LEGACY_LEN = 28;

    private File root;

    @Before
    public void before() throws Exception {
        root = Files.createTempDirectory("dsdb_p56_t2_").toFile();
    }

    @After
    public void after() {
        if (root != null && root.exists()) {
            try { DsDatabaseLocal.forceResetSchemaMetaForTest(root); } catch (Exception ignore) {}
            deleteRecursive(root);
        }
    }

    // ==================== ① write 幂等覆盖 ====================

    @Test
    public void t01_writeLegacyRowLengthIsIdempotentAndOverwrites() {
        EntityIndexUtil.writeLegacyRowLength(root, DetV2.class, LEGACY_LEN);
        Assert.assertEquals("首次写入", LEGACY_LEN, EntityIndexUtil.readLegacyRowLength(root, DetV2.class));

        EntityIndexUtil.writeLegacyRowLength(root, DetV2.class, LEGACY_LEN);
        Assert.assertEquals("重复写同值幂等", LEGACY_LEN, EntityIndexUtil.readLegacyRowLength(root, DetV2.class));

        EntityIndexUtil.writeLegacyRowLength(root, DetV2.class, 20);
        Assert.assertEquals("再次写覆盖旧值", 20, EntityIndexUtil.readLegacyRowLength(root, DetV2.class));
    }

    // ==================== ② read 缺省 → -1 ====================

    @Test
    public void t02_readLegacyRowLengthDefaultMinusOne() {
        Assert.assertEquals("无 leaf → -1（非 compat）", -1, EntityIndexUtil.readLegacyRowLength(root, DetV2.class));
    }

    // ==================== ③ clear 移除 ====================

    @Test
    public void t03_clearLegacyRowLengthRemovesLeaf() {
        EntityIndexUtil.writeLegacyRowLength(root, DetV2.class, LEGACY_LEN);
        Assert.assertEquals(LEGACY_LEN, EntityIndexUtil.readLegacyRowLength(root, DetV2.class));

        EntityIndexUtil.clearLegacyRowLength(root, DetV2.class);
        Assert.assertEquals("清后 → -1", -1, EntityIndexUtil.readLegacyRowLength(root, DetV2.class));
    }

    // ==================== ④ createMeta 读到 leaf → legacyRowLength 正确 ====================

    @Test
    public void t04_createMetaReadsLeafIntoLegacyRowLength() throws Exception {
        EntityIndexUtil.writeLegacyRowLength(root, DetV2.class, LEGACY_LEN);
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            Assert.assertEquals("leaf=28 且当前 36 → compat 生效", LEGACY_LEN, legacyRowLength(db, DetV2.class));
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== ⑤ 无 leaf → createMeta seed 当前长度且 -1 ====================

    @Test
    public void t05_createMetaWithoutLeafSeedsCurrentLength() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(root);
        try {
            Assert.assertEquals("无 leaf → 非 compat (-1)", -1, legacyRowLength(db, DetV2.class));
            Assert.assertEquals("首次检测 seed 当前长度", V2_LEN, EntityIndexUtil.readLegacyRowLength(root, DetV2.class));
        } finally {
            closeQuietly(db);
        }
    }

    // ==================== ⑥ IAE 守卫 ====================

    @Test
    public void t06_illegalArgumentGuards() {
        assertIae(() -> EntityIndexUtil.readLegacyRowLength(root, null));
        assertIae(() -> EntityIndexUtil.writeLegacyRowLength(root, null, LEGACY_LEN));
        assertIae(() -> EntityIndexUtil.writeLegacyRowLength(root, DetV2.class, -1));
        assertIae(() -> EntityIndexUtil.clearLegacyRowLength(root, null));
    }

    // ==================== helpers ====================

    private static void assertIae(Runnable r) {
        try {
            r.run();
            Assert.fail("期望 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static int legacyRowLength(DsDatabaseLocal db, Class<? extends DsTableAdapter> clazz) throws Exception {
        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        metaOf.setAccessible(true);
        Object meta = metaOf.invoke(db, clazz);
        Field f = meta.getClass().getDeclaredField("legacyRowLength");
        f.setAccessible(true);
        return (Integer) f.get(meta);
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
