package ds;

import java.io.File;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.layered.LayeredInvertedView;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P44 T1：驱逐骨架（evict / isLayeredStatsEvicted / rehydrate），5 TR 0F0E。
 *
 * <p>
 * TR1.1 未挂载视图调 {@code evictLayeredStats()} → IAE（且不进入驱逐态）
 * <br>TR1.2 初始 {@code isLayeredStatsEvicted() == false}
 * <br>TR1.3 attach + evict → {@code true}（且视图仍挂载）
 * <br>TR1.4 重复 evict 幂等（不抛、仍为 {@code true}）
 * <br>TR1.5 {@code rehydrateLayeredStats()} → {@code false}，且可再次 evict → {@code true}
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P44_T1_EvictSkeletonTest {

    private static final long SEG = 1L;

    private File root;
    private FulltextStore ft;
    private DsIndexSegmentStore segStore;

    @Before
    public void setUp() throws Exception {
        root = new File(System.getProperty("java.io.tmpdir"), "p44t1-" + System.nanoTime());
        root.mkdirs();
        ft = new FulltextStore(root.getAbsolutePath());
        segStore = new DsIndexSegmentStore(root.getAbsolutePath());
    }

    @After
    public void tearDown() {
        try {
            ft.close();
        } catch (Exception ignored) {
            // 清理
        }
        try {
            segStore.close();
        } catch (Exception ignored) {
            // 清理
        }
        deleteRecursive(root);
    }

    /* -------- TR1.1 未挂载视图 → IAE -------- */

    @Test(timeout = 30000)
    public void tr11_evictWithoutViewIae() throws Exception {
        try {
            ft.evictLayeredStats();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        Assert.assertFalse("IAE 后不应进入驱逐态", ft.isLayeredStatsEvicted());
    }

    /* -------- TR1.2 初始未驱逐 -------- */

    @Test(timeout = 30000)
    public void tr12_initiallyNotEvicted() {
        Assert.assertFalse(ft.isLayeredStatsEvicted());
        Assert.assertFalse(ft.hasLayeredView());
    }

    /* -------- TR1.3 attach + evict → true -------- */

    @Test(timeout = 30000)
    public void tr13_attachThenEvictSetsTrue() throws Exception {
        try (LayeredInvertedView view = openView()) {
            ft.attachLayeredView(view);
            Assert.assertFalse("attach 后仍未驱逐", ft.isLayeredStatsEvicted());
            ft.evictLayeredStats();
            Assert.assertTrue("evict 后应处于驱逐态", ft.isLayeredStatsEvicted());
            Assert.assertTrue("evict 不应卸载视图", ft.hasLayeredView());
        }
    }

    /* -------- TR1.4 重复 evict 幂等 -------- */

    @Test(timeout = 30000)
    public void tr14_duplicateEvictIdempotent() throws Exception {
        try (LayeredInvertedView view = openView()) {
            ft.attachLayeredView(view);
            ft.evictLayeredStats();
            for (int i = 0; i < 3; i++) {
                ft.evictLayeredStats();
            }
            Assert.assertTrue(ft.isLayeredStatsEvicted());
        } catch (Exception e) {
            Assert.fail("重复 evict 不应抛异常: " + e);
        }
    }

    /* -------- TR1.5 rehydrate 复位 + 可再驱逐 -------- */

    @Test(timeout = 30000)
    public void tr15_rehydrateResetsAndCanEvictAgain() throws Exception {
        try (LayeredInvertedView view = openView()) {
            ft.attachLayeredView(view);
            ft.evictLayeredStats();
            Assert.assertTrue(ft.isLayeredStatsEvicted());
            ft.rehydrateLayeredStats();
            Assert.assertFalse("rehydrate 后应退出驱逐态", ft.isLayeredStatsEvicted());
            // 未驱逐态下重复 rehydrate → 0 副作用
            ft.rehydrateLayeredStats();
            Assert.assertFalse(ft.isLayeredStatsEvicted());
            ft.evictLayeredStats();
            Assert.assertTrue("可再次驱逐", ft.isLayeredStatsEvicted());
        }
    }

    /* -------- helpers -------- */

    private LayeredInvertedView openView() throws Exception {
        if (segStore.list().length == 0) {
            DsIndexSegmentWriter w = new DsIndexSegmentWriter(segStore.segmentFile(SEG));
            w.addDocStat(100L, new int[]{3, 0, 0, 0, 0, 0}, new int[]{2, 0, 0, 0, 0, 0});
            w.addTermStat(200L, 1.25, new byte[]{1, 2});
            w.addTf(300L, 3);
            DsIndexSegment seg = w.finish();
            segStore.append(SEG, seg);
        }
        return LayeredInvertedView.open(segStore, new long[]{SEG});
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
