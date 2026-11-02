package ds;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.Neighbor;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;
import com.q3lives.ds.database.fulltext.wordnet.WordNetExporter;
import com.q3lives.ds.database.fulltext.wordnet.WordNetSegmentView;

/**
 * P54 T4：段淘汰 + 只读审计（5 TR）。
 *
 * <p>
 * TR4.1 {@code auditSuperseded} 全键覆盖判定（含段序）+ 部分覆盖不可淘汰；TR4.2 墓碑覆盖计入；TR4.3
 * {@code evict} 移除 manifest + 删段文件 + 剩余段仍可用；TR4.4 淘汰守卫 + R9' 强不变式（孤儿文件不在
 * manifest）； TR4.5 {@code evict} 边界（null/空/重复 IAE）+ 淘汰后同 segId 可重导出。
 */
public class P54_T4_EvictAuditTest {

    private static final long DOC_A = 801L;
    private static final long DOC_B = 802L;
    private static final String TEXT_A = "订阅推送服务稳定运行，提供高质量的文章订阅内容分发";
    private static final String TEXT_B = "服务端订阅数据同步，分布式推送协议实现稳定可靠";

    private static final long SEG_DATA = 1L;
    private static final long SEG_COVER = 2L;
    private static final long SEG_PARTIAL = 3L;
    private static final long SEG_TOMB_ALL = 4L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File rootDir;
    private FulltextStore store;
    private TermDictStore td;
    private long[] allAdjKeys;
    private long[] allPosKeys;

    @Before
    public void before() throws IOException {
        rootDir = tmp.newFolder("p54-t4-" + UUID.randomUUID().toString().replace("-", ""));
        System.setProperty("p2p.db.home", rootDir.getAbsolutePath());
        store = new FulltextStore(rootDir.getAbsolutePath());
        td = store.getTermDict();
        store.ingestDoc(DOC_A, TEXT_A);
        store.ingestDoc(DOC_B, TEXT_B);

        List<long[]> allCtx = store.wordNetAllContextKeys();
        allAdjKeys = collectAdjKeys(allCtx);
        allPosKeys = collectPosKeys(allCtx);

        WordNetExporter ex = new WordNetExporter();
        ex.export(store, rootDir.getAbsolutePath(), SEG_DATA);                          // 全量数据段
        ex.exportDelta(store, rootDir.getAbsolutePath(), SEG_COVER, allCtx);            // 全量覆盖段
        ex.tombstone(rootDir.getAbsolutePath(), SEG_PARTIAL, new long[]{allAdjKeys[0]}, null); // 部分墓碑
        ex.tombstone(rootDir.getAbsolutePath(), SEG_TOMB_ALL, allAdjKeys, allPosKeys);  // 全覆盖墓碑
    }

    @After
    public void after() {
        try {
            store.close();
        } catch (Exception ignore) {
            // 关闭失败不阻塞用例
        }
    }

    // ---------------- TR4.1 auditSuperseded 覆盖判定 + 段序 ----------------
    @Test(timeout = 60000)
    public void tr41_auditCoverageAndOrder() throws IOException {
        Assert.assertArrayEquals("TR4.1 全覆盖 → [1]", new long[]{SEG_DATA},
                WordNetSegmentView.auditSuperseded(rootDir.getAbsolutePath(),
                        new long[]{SEG_DATA, SEG_COVER}));
        Assert.assertArrayEquals("TR4.1 最新段不可淘汰 → []", new long[0],
                WordNetSegmentView.auditSuperseded(rootDir.getAbsolutePath(), new long[]{SEG_COVER}));
        Assert.assertArrayEquals("TR4.1 段序反转 → [2]", new long[]{SEG_COVER},
                WordNetSegmentView.auditSuperseded(rootDir.getAbsolutePath(),
                        new long[]{SEG_COVER, SEG_DATA}));
        Assert.assertArrayEquals("TR4.1 部分覆盖不可淘汰 → []", new long[0],
                WordNetSegmentView.auditSuperseded(rootDir.getAbsolutePath(),
                        new long[]{SEG_DATA, SEG_PARTIAL}));
    }

    // ---------------- TR4.2 墓碑覆盖计入 ----------------
    @Test(timeout = 60000)
    public void tr42_tombstoneCountsAsCoverage() throws IOException {
        Assert.assertArrayEquals("TR4.2 全覆盖墓碑 → [1]", new long[]{SEG_DATA},
                WordNetSegmentView.auditSuperseded(rootDir.getAbsolutePath(),
                        new long[]{SEG_DATA, SEG_TOMB_ALL}));
    }

    // ---------------- TR4.3 evict：manifest 移除 + 文件删除 + 剩余段可用 ----------------
    @Test(timeout = 60000)
    public void tr43_evictRemovesSegmentAndRemainingUsable() throws IOException {
        Assert.assertTrue("TR4.3 seg-1 文件存在（前置）", new File(rootDir, "wordnet/seg-1.dnet").isFile());
        WordNetSegmentView.evict(rootDir.getAbsolutePath(), new long[]{SEG_DATA});

        String manifest = readManifest();
        Assert.assertFalse("TR4.3 manifest 已移除 segId=1: " + manifest, manifest.contains("1"));
        Assert.assertFalse("TR4.3 seg-1 段文件已删", new File(rootDir, "wordnet/seg-1.dnet").exists());

        // 剩余 seg2（全量覆盖段）仍可 open / 查询。
        try (WordNetSegmentView v = WordNetSegmentView.open(rootDir.getAbsolutePath(),
                new long[]{SEG_COVER})) {
            Assert.assertEquals("TR4.3 剩余段数=1", 1, v.segmentCount());
            int checked = 0;
            for (long[] ctx : store.wordNetAllContextKeys()) {
                long docId = ctx[0];
                long parentId = ctx[1];
                int level = (int) ctx[2];
                Set<Long> srcs = new LinkedHashSet<>();
                for (long[] n : store.wordNetNeighborsForCtx(docId, parentId, level)) {
                    srcs.add(n[0]);
                }
                for (Long s : srcs) {
                    Set<String> mem = toKeySet(store.neighborsWithin(td.toText(s.longValue()), docId, parentId,
                            level, FulltextStore.DIST_MAX));
                    Set<String> seg = toKeySet(v.neighborsWithin(s.longValue(), docId, parentId, level,
                            FulltextStore.DIST_MAX));
                    Assert.assertEquals("TR4.3 剩余段邻接 ≡ 内存真值 src=" + s, mem, seg);
                    checked++;
                }
            }
            Assert.assertTrue("TR4.3 至少校验 1 个 srcUid", checked > 0);
        }
    }

    // ---------------- TR4.4 淘汰守卫 + R9' 强不变式（孤儿不可见） ----------------
    @Test(timeout = 60000)
    public void tr44_evictGuardsAndOrphanInvisible() throws IOException {
        String before = readManifest();
        int thrown = 0;
        thrown += expectIae(() -> WordNetSegmentView.evict(rootDir.getAbsolutePath(), new long[]{999L}));
        Assert.assertEquals("TR4.4 未注册 segId → IAE", 1, thrown);
        Assert.assertEquals("TR4.4 未注册淘汰 0 部分变更", before, readManifest());

        // R9' 强不变式：先 unregister 后 delete → 淘汰后 manifest 与文件同时消失。
        WordNetSegmentView.evict(rootDir.getAbsolutePath(), new long[]{SEG_PARTIAL});
        Assert.assertFalse("TR4.4 manifest 不含 3", readManifest().contains("3"));
        Assert.assertFalse("TR4.4 文件 seg-3 不存在", new File(rootDir, "wordnet/seg-3.dnet").exists());

        // 孤儿文件（不在 manifest）对 list()/manifest 不可见（可被后续清理）。
        File orphan = new File(rootDir, "wordnet/seg-999.dnet");
        Files.copy(new File(rootDir, "wordnet/seg-2.dnet").toPath(), orphan.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        Assert.assertTrue("TR4.4 孤儿文件确已存在", orphan.isFile());
        Assert.assertFalse("TR4.4 孤儿 segId 不在 manifest", readManifest().contains("999"));
    }

    // ---------------- TR4.5 evict 边界 + 淘汰后同 segId 可重导出 ----------------
    @Test(timeout = 60000)
    public void tr45_evictBoundaryAndReExport() throws IOException {
        int thrown = 0;
        thrown += expectIae(() -> WordNetSegmentView.evict(rootDir.getAbsolutePath(), null));
        thrown += expectIae(() -> WordNetSegmentView.evict(rootDir.getAbsolutePath(), new long[0]));
        Assert.assertEquals("TR4.5 evict null/空 → 2 IAE", 2, thrown);

        WordNetSegmentView.evict(rootDir.getAbsolutePath(), new long[]{SEG_DATA});
        Assert.assertEquals("TR4.5 淘汰成功可重导出同 segId", SEG_DATA,
                new WordNetExporter().export(store, rootDir.getAbsolutePath(), SEG_DATA).segId);
        thrown = 0;
        thrown += expectIae(() -> WordNetSegmentView.evict(rootDir.getAbsolutePath(), new long[]{9999L}));
        Assert.assertEquals("TR4.5 再次淘汰未注册 → IAE", 1, thrown);
    }

    // ---------------- helpers ----------------
    private long[] collectAdjKeys(List<long[]> allCtx) throws IOException {
        Set<Long> keys = new LinkedHashSet<>();
        for (long[] ctx : allCtx) {
            for (long[] n : store.wordNetNeighborsForCtx(ctx[0], ctx[1], (int) ctx[2])) {
                keys.add(ctxKey(ctx[0], ctx[1], (int) ctx[2], n[0]));
            }
        }
        return toArr(keys);
    }

    private long[] collectPosKeys(List<long[]> allCtx) throws IOException {
        Set<Long> keys = new LinkedHashSet<>();
        for (long[] ctx : allCtx) {
            long docId = ctx[0];
            long parentId = ctx[1];
            int level = (int) ctx[2];
            for (TuRow r : store.selectByParentLevel(docId, parentId, level)) {
                if (!store.wordNetPositionsForCtx(r.unit_id(), docId, parentId, level).isEmpty()) {
                    keys.add(posKey(r.unit_id(), docId, parentId, level));
                }
            }
        }
        return toArr(keys);
    }

    private static long[] toArr(Set<Long> keys) {
        long[] out = new long[keys.size()];
        int i = 0;
        for (Long k : keys) {
            out[i++] = k.longValue();
        }
        return out;
    }

    private static long ctxKey(long docId, long parentId, int level, long srcUid) {
        return TermDictStore.FarmHash64.mix4(docId, parentId, (long) level, srcUid);
    }

    private static long posKey(long uid, long docId, long parentId, int level) {
        return TermDictStore.FarmHash64.mix4(uid, docId, parentId,
                TermDictStore.FarmHash64.mix2((long) level, 0xDEADBEEFL));
    }

    private String readManifest() throws IOException {
        File m = new File(rootDir, "wordnet/manifest");
        Assert.assertTrue("manifest 必须存在", m.isFile());
        return new String(Files.readAllBytes(m.toPath()), StandardCharsets.UTF_8);
    }

    private static Set<String> toKeySet(Collection<Neighbor> ns) {
        Set<String> out = new HashSet<>();
        for (Neighbor n : ns) {
            out.add(n.uid + ":" + n.dir + ":" + n.distance);
        }
        return out;
    }

    private interface Thrower {

        void run() throws IOException;
    }

    private static int expectIae(Thrower t) {
        try {
            t.run();
        } catch (IllegalArgumentException e) {
            return 1;
        } catch (IOException e) {
            Assert.fail("期望 IAE 但抛 IOException: " + e.getMessage());
        }
        Assert.fail("期望 IllegalArgumentException 但未抛出");
        return 0;
    }
}
