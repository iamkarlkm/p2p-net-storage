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
 * P43 T1：挂载骨架（attach / detach / hasLayeredView），5 TR 0F0E。
 *
 * <p>
 * TR1.1 {@code attachLayeredView(null)} → IAE
 * <br>TR1.2 初始 {@code hasLayeredView() == false}
 * <br>TR1.3 attach 后 {@code true} + detach 后 {@code false}
 * <br>TR1.4 {@code detachLayeredView()} 幂等（未挂载时 0 副作用不抛）
 * <br>TR1.5 重复 attach → IAE（须先 detach）
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P43_T1_AttachSkeletonTest {

    private static final long SEG = 1L;

    private File root;
    private FulltextStore ft;
    private DsIndexSegmentStore segStore;

    @Before
    public void setUp() throws Exception {
        root = new File(System.getProperty("java.io.tmpdir"), "p43t1-" + System.nanoTime());
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

    /* -------- TR1.1 attach(null) IAE -------- */

    @Test(timeout = 30000)
    public void tr11_attachNullViewIae() throws Exception {
        try {
            ft.attachLayeredView(null);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        Assert.assertFalse("IAE 后不应挂载", ft.hasLayeredView());
    }

    /* -------- TR1.2 初始未挂载 -------- */

    @Test(timeout = 30000)
    public void tr12_initiallyNotAttached() {
        // 默认态（与 P21~P42 字节级同行为）
        Assert.assertFalse(ft.hasLayeredView());
        ft.detachLayeredView();
        Assert.assertFalse("未挂载时 detach 后仍 false", ft.hasLayeredView());
    }

    /* -------- TR1.3 attach/detach 状态位翻转 -------- */

    @Test(timeout = 30000)
    public void tr13_attachThenDetachTogglesState() throws Exception {
        try (LayeredInvertedView view = openView()) {
            ft.attachLayeredView(view);
            Assert.assertTrue("attach 后 true", ft.hasLayeredView());
            ft.detachLayeredView();
            Assert.assertFalse("detach 后 false", ft.hasLayeredView());
        }
    }

    /* -------- TR1.4 detach 幂等 -------- */

    @Test(timeout = 30000)
    public void tr14_detachIdempotentWhenNotAttached() {
        for (int i = 0; i < 3; i++) {
            ft.detachLayeredView();
        }
        Assert.assertFalse(ft.hasLayeredView());
        try (LayeredInvertedView view = openView()) {
            ft.attachLayeredView(view);
            ft.detachLayeredView();
            // 二次 detach（已卸载）→ 0 副作用
            ft.detachLayeredView();
            Assert.assertFalse(ft.hasLayeredView());
        } catch (Exception e) {
            Assert.fail("detach 幂等不应抛异常: " + e);
        }
    }

    /* -------- TR1.5 重复 attach IAE -------- */

    @Test(timeout = 30000)
    public void tr15_duplicateAttachIae() throws Exception {
        try (LayeredInvertedView v1 = openView(); LayeredInvertedView v2 = openView()) {
            ft.attachLayeredView(v1);
            try {
                ft.attachLayeredView(v2);
                Assert.fail("expected IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                // ok
            }
            Assert.assertTrue("重复 attach 失败后原挂载应保留", ft.hasLayeredView());
            ft.detachLayeredView();
            ft.attachLayeredView(v2);
            Assert.assertTrue("detach 后可重新 attach", ft.hasLayeredView());
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
