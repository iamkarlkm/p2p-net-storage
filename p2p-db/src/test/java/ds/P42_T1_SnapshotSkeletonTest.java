package ds;

import java.io.File;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.layered.LayeredIndexExporter;
import com.q3lives.ds.database.fulltext.layered.LayeredInvertedView;
import com.q3lives.ds.database.fulltext.layered.LayeredSnapshot;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;

/**
 * P42 T1：{@link LayeredSnapshot} + 入口骨架，5 TR 0F0E。
 *
 * <p>
 * TR1.1 Snapshot 6 IAE 守卫
 * <br>TR1.2 Snapshot fingerprint 64-hex 恒定且随字段变化
 * <br>TR1.3 Snapshot deepEquals 真值表
 * <br>TR1.4 LayeredIndexExporter 构造零状态
 * <br>TR1.5 LayeredInvertedView.open null/空 segIds IAE
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P42_T1_SnapshotSkeletonTest {

    private File root;

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p42t1-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        deleteRecursive(root);
    }

    /* -------- TR1.1 6 IAE -------- */

    @Test(timeout = 30000)
    public void tr11_snapshotSixIaeGuards() {
        final String fp = hex64();
        assertIae(() -> new LayeredSnapshot(-1L, 0L, 0L, 0L, fp));
        assertIae(() -> new LayeredSnapshot(1L, -1L, 0L, 0L, fp));
        assertIae(() -> new LayeredSnapshot(1L, 0L, -1L, 0L, fp));
        assertIae(() -> new LayeredSnapshot(1L, 0L, 0L, -1L, fp));
        assertIae(() -> new LayeredSnapshot(1L, 0L, 0L, 0L, null));
        assertIae(() -> new LayeredSnapshot(1L, 0L, 0L, 0L, "deadbeef"));
        LayeredSnapshot ok = new LayeredSnapshot(7L, 2L, 3L, 4L, fp);
        Assert.assertEquals(7L, ok.segId);
        Assert.assertEquals(2L, ok.docCount);
        Assert.assertEquals(3L, ok.termCount);
        Assert.assertEquals(4L, ok.tfCount);
        Assert.assertEquals(fp, ok.segmentFingerprint);
    }

    /* -------- TR1.2 fingerprint 64-hex -------- */

    @Test(timeout = 30000)
    public void tr12_fingerprintIs64HexAndFieldSensitive() {
        String fp = hex64();
        LayeredSnapshot a = new LayeredSnapshot(1L, 2L, 3L, 4L, fp);
        LayeredSnapshot b = new LayeredSnapshot(1L, 2L, 3L, 4L, fp);
        Assert.assertEquals("同字段 fingerprint 恒定", a.fingerprint(), b.fingerprint());
        Assert.assertEquals(64, a.fingerprint().length());
        Assert.assertTrue("应为小写 hex", a.fingerprint().matches("[0-9a-f]{64}"));

        Assert.assertNotEquals("segId 变 → 指纹变", a.fingerprint(), new LayeredSnapshot(2L, 2L, 3L, 4L, fp).fingerprint());
        Assert.assertNotEquals("docCount 变 → 指纹变", a.fingerprint(), new LayeredSnapshot(1L, 9L, 3L, 4L, fp).fingerprint());
        Assert.assertNotEquals("termCount 变 → 指纹变", a.fingerprint(), new LayeredSnapshot(1L, 2L, 9L, 4L, fp).fingerprint());
        Assert.assertNotEquals("tfCount 变 → 指纹变", a.fingerprint(), new LayeredSnapshot(1L, 2L, 3L, 9L, fp).fingerprint());
        Assert.assertNotEquals("segmentFingerprint 变 → 指纹变", a.fingerprint(),
                new LayeredSnapshot(1L, 2L, 3L, 4L, hex64Alt()).fingerprint());
    }

    /* -------- TR1.3 deepEquals -------- */

    @Test(timeout = 30000)
    public void tr13_deepEqualsTruthTable() {
        String fp = hex64();
        LayeredSnapshot a = new LayeredSnapshot(1L, 2L, 3L, 4L, fp);
        Assert.assertTrue("自身", a.deepEquals(a));
        Assert.assertTrue("全等", a.deepEquals(new LayeredSnapshot(1L, 2L, 3L, 4L, fp)));
        Assert.assertFalse("null", a.deepEquals(null));
        Assert.assertFalse("异类型", a.deepEquals("not-a-snapshot"));
        Assert.assertFalse("segId 异", a.deepEquals(new LayeredSnapshot(2L, 2L, 3L, 4L, fp)));
        Assert.assertFalse("docCount 异", a.deepEquals(new LayeredSnapshot(1L, 9L, 3L, 4L, fp)));
        Assert.assertFalse("termCount 异", a.deepEquals(new LayeredSnapshot(1L, 2L, 9L, 4L, fp)));
        Assert.assertFalse("tfCount 异", a.deepEquals(new LayeredSnapshot(1L, 2L, 3L, 9L, fp)));
        Assert.assertFalse("segmentFingerprint 异", a.deepEquals(new LayeredSnapshot(1L, 2L, 3L, 4L, hex64Alt())));
    }

    /* -------- TR1.4 exporter ctor 零状态 -------- */

    @Test(timeout = 30000)
    public void tr14_exporterConstructorStateless() {
        Assert.assertEquals("LayeredIndexExporter 不应持有实例字段", 0,
                LayeredIndexExporter.class.getDeclaredFields().length);
        LayeredIndexExporter e1 = new LayeredIndexExporter();
        LayeredIndexExporter e2 = new LayeredIndexExporter();
        Assert.assertNotNull(e1);
        Assert.assertNotNull(e2);
    }

    /* -------- TR1.5 view open null/空 segIds IAE -------- */

    @Test(timeout = 30000)
    public void tr15_viewOpenNullAndEmptySegIdsIae() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        assertIaeChecked(() -> LayeredInvertedView.open(null, new long[]{10L}));
        assertIaeChecked(() -> LayeredInvertedView.open(store, null));
        assertIaeChecked(() -> LayeredInvertedView.open(store, new long[0]));
    }

    /* -------- helpers -------- */

    private static void assertIae(Runnable r) {
        try {
            r.run();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static void assertIaeChecked(CheckedRunnable r) throws Exception {
        try {
            r.run();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static String hex64() {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            sb.append(Character.forDigit(i % 16, 16));
        }
        return sb.toString();
    }

    private static String hex64Alt() {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            sb.append(Character.forDigit((i + 7) % 16, 16));
        }
        return sb.toString();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
