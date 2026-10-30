package ds;

import java.io.File;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.autocompact.AutoCompactionHook;
import com.q3lives.ds.database.fulltext.autocompact.AutoCompactionPolicy;
import com.q3lives.ds.database.fulltext.autocompact.AutoCompactionStats;
import com.q3lives.ds.database.fulltext.sched.CompactionSchedConfig;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P48 T3：{@code AutoCompactionHook} 主路径（委托 + 阈值内联触发），5 TR 0F0E。
 *
 * <p>
 * TR3.1 3 IAE 守卫；TR3.2 未达阈值时逐字节等价裸 {@code store.append}（R11）；TR3.3 达阈值自动触发
 * {@code tick()}；<br>TR3.4 未达阈值不触发；TR3.5 {@code tickNow()} 显式手动触发。
 */
public class P48_T3_HookMainTest {

    private File root;

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p48t3-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        deleteRecursive(root);
    }

    @Test
    public void tr31_threeGuards() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "g").getAbsolutePath());
        CompactionSchedConfig cfg = CompactionSchedConfig.defaults();
        AutoCompactionPolicy pol = AutoCompactionPolicy.defaults();
        expectIae(() -> new AutoCompactionHook(null, cfg, pol));
        expectIae(() -> new AutoCompactionHook(store, null, pol));
        expectIae(() -> new AutoCompactionHook(store, cfg, null));
    }

    @Test(timeout = 60000)
    public void tr32_equivalentToBareAppendWhenNotTriggered() throws Exception {
        File ra = new File(root, "a");
        File rb = new File(root, "b");
        ra.mkdirs();
        rb.mkdirs();
        DsIndexSegmentStore storeA = new DsIndexSegmentStore(ra.getAbsolutePath());
        DsIndexSegmentStore storeB = new DsIndexSegmentStore(rb.getAbsolutePath());
        // 高阈值 → 永不触发；验证未触发态的写路径透明性
        AutoCompactionHook hook = new AutoCompactionHook(storeA, CompactionSchedConfig.defaults(),
                new AutoCompactionPolicy(1_000, 1_000));

        for (int i = 0; i < 4; i++) {
            long segId = 10L + i;
            DsIndexSegment onDiskA = hook.append(segId, writeSmall(storeA, segId, i));
            DsIndexSegment onDiskB = storeB.append(segId, writeSmall(storeB, segId, i));
            Assert.assertEquals("返回段指纹逐字节等价 segId=" + segId,
                    onDiskB.fingerprint(), onDiskA.fingerprint());
        }
        Assert.assertArrayEquals("manifest 逐字节等价", readAll(manifest(storeB)), readAll(manifest(storeA)));
        Assert.assertArrayEquals("已注册段列表等价", storeB.list(), storeA.list());
        Assert.assertEquals("未触发态 ticks 应为 0", 0L, hook.stats().ticks());
        Assert.assertEquals(4L, hook.autoStats().writesObserved());
        Assert.assertEquals(0L, hook.autoStats().triggersFired());
        hook.close();
    }

    @Test(timeout = 60000)
    public void tr33_autoTriggerOnThreshold() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "t33").getAbsolutePath());
        AutoCompactionHook hook = new AutoCompactionHook(store, CompactionSchedConfig.defaults(),
                AutoCompactionPolicy.defaults());
        for (int i = 0; i < 4; i++) {
            long segId = 100L * (i + 1);
            hook.append(segId, writeSmall(store, segId, i));
        }
        Assert.assertEquals("4 次写入达阈值 → 自动触发 1 次", 1L, hook.autoStats().triggersFired());
        Assert.assertEquals("4 段同层 → 合并 1 次", 1L, hook.stats().runsSucceeded());
        Assert.assertEquals("四合一后仅剩 1 段", 1, store.list().length);
        Assert.assertEquals("触发后写计数复位", 0, hook.writesSinceLastTick());
        Assert.assertEquals(4L, hook.autoStats().writesObserved());
        Assert.assertEquals(0L, hook.autoStats().triggersSkipped());
        hook.close();
    }

    @Test(timeout = 60000)
    public void tr34_noTriggerBelowThreshold() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "t34").getAbsolutePath());
        AutoCompactionHook hook = new AutoCompactionHook(store, CompactionSchedConfig.defaults(),
                AutoCompactionPolicy.defaults());
        for (int i = 0; i < 3; i++) {
            long segId = 100L * (i + 1);
            hook.append(segId, writeSmall(store, segId, i));
        }
        Assert.assertEquals("写入 3 < 4 未达阈值", 0L, hook.autoStats().triggersFired());
        Assert.assertEquals("未触发 → 0 轮 tick", 0L, hook.stats().ticks());
        Assert.assertEquals(3, store.list().length);
        Assert.assertEquals(3, hook.writesSinceLastTick());
        hook.close();
    }

    @Test(timeout = 60000)
    public void tr35_tickNowExplicit() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "t35").getAbsolutePath());
        // 预置 4 段（裸 append，不经过装饰器 → 写计数为 0）
        for (int i = 0; i < 4; i++) {
            long segId = 100L * (i + 1);
            store.append(segId, writeSmall(store, segId, i));
        }
        AutoCompactionHook hook = new AutoCompactionHook(store, CompactionSchedConfig.defaults(),
                new AutoCompactionPolicy(1_000, 1_000));
        Assert.assertEquals("显式 tick 合并 1 次", 1, hook.tickNow());
        Assert.assertEquals(1, store.list().length);
        Assert.assertEquals("显式触发不计入自动触发计数", 0L, hook.autoStats().triggersFired());
        hook.close();
    }

    /* -------- helpers -------- */

    private static File manifest(DsIndexSegmentStore store) {
        return new File(store.dir(), "manifest");
    }

    private static byte[] readAll(File f) throws Exception {
        return Files.readAllBytes(f.toPath());
    }

    /** 写一个小段文件（不登记），返回其描述对象。 */
    private static DsIndexSegment writeSmall(DsIndexSegmentStore store, long segId, int seed) throws Exception {
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(store.segmentFile(segId));
        w.addDocStat(1L + seed, six(1 + seed), six(1));
        w.addTermStat(100L + seed, 1.5 + seed, new byte[]{(byte) (seed + 1)});
        w.addTf(500L + seed, 1 + seed);
        return w.finish();
    }

    private static int[] six(int v) {
        return new int[]{v, 0, 0, 0, 0, 0};
    }

    private interface ThrowingRunnable {

        void run() throws Exception;
    }

    private static void expectIae(ThrowingRunnable r) {
        try {
            r.run();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        } catch (Exception e) {
            Assert.fail("expected IllegalArgumentException but got: " + e);
        }
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
