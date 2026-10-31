package ds;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.autoseg.AutoSegmentHook;
import com.q3lives.ds.database.fulltext.autoseg.AutoSegmentPolicy;
import com.q3lives.ds.database.fulltext.layered.LayeredSnapshot;
import com.q3lives.ds.database.fulltext.sched.CompactionSchedConfig;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;

/**
 * P49 T3：{@code AutoSegmentHook} 主路径（委托 + 阈值导出 + 内联合并），5 TR 0F0E。
 *
 * <p>
 * TR3.1 4 IAE 守卫（ft/store/schedCfg/policy null）；TR3.2 未达导出阈值时写路径透明等价裸
 * {@code ft.putDoc}（R13）；<br>TR3.3 达导出阈值自动产段；TR3.4 段数达阈值自动合并（{@code list()}
 * 收敛）；TR3.5 {@code flushNow()} 显式导出。
 */
public class P49_T3_HookMainTest {

    private static final String TEXT = "hello world autoseg engine token";

    private File root;
    private final List<FulltextStore> stores = new ArrayList<>();

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p49t3-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        for (FulltextStore ft : stores) {
            try {
                ft.close();
            } catch (Exception ignore) {
                // best-effort
            }
        }
        deleteRecursive(root);
    }

    @Test(timeout = 60000)
    public void tr31_fourGuards() throws Exception {
        DsIndexSegmentStore seg = new DsIndexSegmentStore(new File(root, "g").getAbsolutePath());
        FulltextStore ft = newFt("gft");
        CompactionSchedConfig cfg = CompactionSchedConfig.defaults();
        AutoSegmentPolicy pol = AutoSegmentPolicy.defaults();
        expectIae(() -> new AutoSegmentHook(null, seg, cfg, pol));
        expectIae(() -> new AutoSegmentHook(ft, null, cfg, pol));
        expectIae(() -> new AutoSegmentHook(ft, seg, null, pol));
        expectIae(() -> new AutoSegmentHook(ft, seg, cfg, null));
    }

    @Test(timeout = 120000)
    public void tr32_transparentWhenBelowFlushThreshold() throws Exception {
        FulltextStore ftHook = newFt("tm_hook");
        FulltextStore ftBare = newFt("tm_bare");
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "tm_seg").getAbsolutePath());

        // 高阈值 → 永不导出；验证未触发态写路径透明性（R13）
        AutoSegmentHook hook = new AutoSegmentHook(ftHook, store, CompactionSchedConfig.defaults(),
                new AutoSegmentPolicy(1_000, 1_000));

        for (int i = 1; i <= 5; i++) {
            FulltextStore.IngestStat sh = hook.putDoc(i, TEXT + " " + i);
            FulltextStore.IngestStat sb = ftBare.putDoc(i, TEXT + " " + i);
            Assert.assertEquals("docId 等价", sb.docId, sh.docId);
            Assert.assertEquals("pairsTotal 等价", sb.pairsTotal, sh.pairsTotal);
            Assert.assertArrayEquals("rowsPerLevel 等价", sb.rowsPerLevel, sh.rowsPerLevel);
        }

        Assert.assertEquals("未达阈值不产段（R13）", 0, store.list().length);
        Assert.assertEquals("docStat 键集逐条等价",
                ftBare.weightStoreAllDocKeysForTest(), ftHook.weightStoreAllDocKeysForTest());
        Assert.assertEquals("termStat 键集逐条等价",
                ftBare.weightStoreAllTermKeys(), ftHook.weightStoreAllTermKeys());
        Assert.assertEquals("tf 键值逐条等价",
                ftBare.weightStoreAllTfEntries(), ftHook.weightStoreAllTfEntries());
        for (Long d : ftBare.weightStoreAllDocKeysForTest()) {
            FulltextStore.WeightDocStatRow hb = ftBare.getDocStatForTest(d);
            FulltextStore.WeightDocStatRow hh = ftHook.getDocStatForTest(d);
            Assert.assertArrayEquals("docLen doc" + d, hb.docLen, hh.docLen);
            Assert.assertArrayEquals("nTerms doc" + d, hb.nTerms, hh.nTerms);
        }
        Assert.assertEquals("写入观察计数", 5L, hook.stats().writesObserved());
        Assert.assertEquals("未触发 → 0 导出", 0L, hook.stats().flushesFired());
        Assert.assertEquals("未触发 → 0 tick", 0L, hook.compactionStats().ticks());
        hook.close();
    }

    @Test(timeout = 120000)
    public void tr33_autoFlushOnThreshold() throws Exception {
        FulltextStore ft = newFt("t33_mem");
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "t33_seg").getAbsolutePath());
        // 每 2 次写导 1 段；段数下限 4 远大于本用例段数 → 只导不合
        AutoSegmentHook hook = new AutoSegmentHook(ft, store, CompactionSchedConfig.defaults(),
                new AutoSegmentPolicy(2, 4));

        for (int i = 1; i <= 4; i++) {
            hook.putDoc(i, TEXT + " " + i);
        }
        Assert.assertEquals("4 次写 / 阈值 2 → 2 次导出", 2L, hook.stats().flushesFired());
        Assert.assertEquals("2 段已登记", 2, store.list().length);
        Assert.assertEquals("段数不足 → 未合并", 0L, hook.stats().compactionsFired());
        Assert.assertEquals("段数不足 → 记跳过", 2L, hook.stats().compactionsSkipped());
        Assert.assertEquals(4L, hook.stats().writesObserved());
        Assert.assertEquals("导出后写计数复位", 0, hook.writesSinceFlush());
        hook.close();
    }

    @Test(timeout = 120000)
    public void tr34_autoCompactOnSegmentThreshold() throws Exception {
        FulltextStore ft = newFt("t34_mem");
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "t34_seg").getAbsolutePath());
        // 每次写都导段；段数达 4 即合并（同 doc 重复写入 → 各快照逐字节相同 → 同层可合）
        AutoSegmentHook hook = new AutoSegmentHook(ft, store, CompactionSchedConfig.defaults(),
                new AutoSegmentPolicy(1, 4));

        for (int i = 0; i < 4; i++) {
            hook.putDoc(1L, TEXT);
        }
        Assert.assertEquals("4 次写各导 1 段", 4L, hook.stats().flushesFired());
        Assert.assertEquals("第 4 段达阈值 → 合并 1 轮", 1L, hook.stats().compactionsFired());
        Assert.assertEquals("前 3 次记跳过", 3L, hook.stats().compactionsSkipped());
        Assert.assertEquals("四合一 → list() 收敛为 1", 1, store.list().length);
        Assert.assertEquals("账目守恒", hook.stats().flushesFired(),
                hook.stats().compactionsFired() + hook.stats().compactionsSkipped());
        hook.close();
    }

    @Test(timeout = 120000)
    public void tr35_flushNowExplicit() throws Exception {
        FulltextStore ft = newFt("t35_mem");
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "t35_seg").getAbsolutePath());
        AutoSegmentHook hook = new AutoSegmentHook(ft, store, CompactionSchedConfig.defaults(),
                new AutoSegmentPolicy(1_000, 1_000));

        for (int i = 1; i <= 3; i++) {
            hook.putDoc(i, TEXT + " " + i);
        }
        Assert.assertEquals("阈值未达 → 无导出", 0, store.list().length);
        Assert.assertEquals("未复位写计数", 3, hook.writesSinceFlush());

        LayeredSnapshot snap = hook.flushNow();
        Assert.assertEquals("显式导出 segId=0", 0L, snap.segId);
        Assert.assertEquals(1, store.list().length);
        Assert.assertEquals("显式导出计入 flushesFired", 1L, hook.stats().flushesFired());
        Assert.assertEquals("显式导出后写计数复位", 0, hook.writesSinceFlush());
        hook.close();
    }

    /* -------- helpers -------- */

    private FulltextStore newFt(String name) throws Exception {
        FulltextStore ft = new FulltextStore(new File(root, name).getAbsolutePath());
        stores.add(ft);
        return ft;
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
