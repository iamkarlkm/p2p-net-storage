package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.wordnet.WordNetExporter;
import com.q3lives.ds.database.fulltext.wordnet.WordNetSegmentView;
import com.q3lives.ds.database.fulltext.wordnet.WordNetSnapshot;

/**
 * P54 T1：入口骨架 + IAE 守卫 + 哨兵常量（5 TR）。
 *
 * <p>
 * TR1.1 {@code exportDelta} 守卫（4 基础 IAE + {@code changedCtx} null + 重复 segId）；TR1.2
 * {@code tombstone} 守卫（4 IAE）；TR1.3 {@code auditSuperseded} / {@code evict} 守卫；TR1.4 墓碑段落盘 +
 * manifest 登记 + 计数 + 哨兵 payload 往返（反射读 {@code == -1}）；TR1.5 {@code TOMBSTONE_PAYLOAD} 常量 +
 * 空 {@code changedCtx} 合法段。
 */
public class P54_T1_SkeletonSentinelIaeTest {

    private static final long DOC = 501L;
    private static final String TEXT = "订阅推送服务稳定运行提供高质量文章订阅内容分发";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FulltextStore store;
    private String root;

    @Before
    public void before() throws IOException {
        File dir = tmp.newFolder("p54-t1-" + UUID.randomUUID().toString().replace("-", ""));
        System.setProperty("p2p.db.home", dir.getAbsolutePath());
        root = dir.getAbsolutePath();
        store = new FulltextStore(root);
    }

    @After
    public void after() {
        try {
            store.close();
        } catch (Exception ignore) {
            // 关闭失败不阻塞用例
        }
    }

    // ---------------- TR1.1 exportDelta 守卫 ----------------
    @Test(timeout = 30000)
    public void tr11_exportDeltaGuards() throws IOException {
        WordNetExporter ex = new WordNetExporter();
        int thrown = 0;
        thrown += expectIae(() -> ex.exportDelta(null, root, 1L, Collections.emptyList()));
        thrown += expectIae(() -> ex.exportDelta(store, null, 1L, Collections.emptyList()));
        thrown += expectIae(() -> ex.exportDelta(store, "   ", 1L, Collections.emptyList()));
        thrown += expectIae(() -> ex.exportDelta(store, root, -1L, Collections.emptyList()));
        thrown += expectIae(() -> ex.exportDelta(store, root, 1L, null));
        Assert.assertEquals("TR1.1 exportDelta 5 条 IAE 守卫", 5, thrown);

        WordNetSnapshot ok = ex.exportDelta(store, root, 1L, Collections.emptyList());
        Assert.assertEquals("TR1.1 空变更集合法段", 1L, ok.segId);
        thrown = 0;
        thrown += expectIae(() -> ex.exportDelta(store, root, 1L, Collections.emptyList()));
        Assert.assertEquals("TR1.1 重复 segId IAE", 1, thrown);
    }

    // ---------------- TR1.2 tombstone 守卫 ----------------
    @Test(timeout = 30000)
    public void tr12_tombstoneGuards() throws IOException {
        WordNetExporter ex = new WordNetExporter();
        int thrown = 0;
        thrown += expectIae(() -> ex.tombstone(null, 1L, new long[]{1L}, null));
        thrown += expectIae(() -> ex.tombstone("  ", 1L, new long[]{1L}, null));
        thrown += expectIae(() -> ex.tombstone(root, -1L, new long[]{1L}, null));
        thrown += expectIae(() -> ex.tombstone(root, 1L, null, null));
        thrown += expectIae(() -> ex.tombstone(root, 1L, new long[0], new long[0]));
        Assert.assertEquals("TR1.2 tombstone 5 条 IAE 守卫", 5, thrown);

        WordNetSnapshot ok = ex.tombstone(root, 3L, new long[]{1L}, null);
        Assert.assertEquals("TR1.2 合法墓碑段", 3L, ok.segId);
        thrown = 0;
        thrown += expectIae(() -> ex.tombstone(root, 3L, new long[]{2L}, null));
        Assert.assertEquals("TR1.2 重复 segId IAE", 1, thrown);
    }

    // ---------------- TR1.3 auditSuperseded / evict 守卫 ----------------
    @Test(timeout = 30000)
    public void tr13_auditAndEvictGuards() throws IOException {
        new WordNetExporter().tombstone(root, 7L, new long[]{1L}, null);

        int thrown = 0;
        thrown += expectIae(() -> WordNetSegmentView.auditSuperseded(null, new long[]{7L}));
        thrown += expectIae(() -> WordNetSegmentView.auditSuperseded("  ", new long[]{7L}));
        thrown += expectIae(() -> WordNetSegmentView.auditSuperseded(root, null));
        thrown += expectIae(() -> WordNetSegmentView.auditSuperseded(root, new long[0]));
        thrown += expectIae(() -> WordNetSegmentView.auditSuperseded(root, new long[]{999L}));
        Assert.assertEquals("TR1.3 auditSuperseded 5 条 IAE 守卫", 5, thrown);

        thrown = 0;
        thrown += expectIae(() -> WordNetSegmentView.evict(null, new long[]{7L}));
        thrown += expectIae(() -> WordNetSegmentView.evict("  ", new long[]{7L}));
        thrown += expectIae(() -> WordNetSegmentView.evict(root, null));
        thrown += expectIae(() -> WordNetSegmentView.evict(root, new long[0]));
        thrown += expectIae(() -> WordNetSegmentView.evict(root, new long[]{999L}));
        Assert.assertEquals("TR1.3 evict 5 条 IAE 守卫", 5, thrown);
    }

    // ---------------- TR1.4 墓碑段落盘 + manifest + 计数 + 哨兵 payload 往返 ----------------
    @Test(timeout = 30000)
    public void tr14_tombstoneSegmentAndSentinelPayload() throws Exception {
        long[] adjKeys = {111L, 222L};
        long[] posKeys = {333L};
        WordNetSnapshot snap = new WordNetExporter().tombstone(root, 2L, adjKeys, posKeys);
        Assert.assertEquals("TR1.4 segId", 2L, snap.segId);
        Assert.assertEquals("TR1.4 ctxCount == adj 键数", 2L, snap.ctxCount);
        Assert.assertEquals("TR1.4 adjCount == adj 键数", 2L, snap.adjCount);
        Assert.assertEquals("TR1.4 posCount == pos 键数", 1L, snap.posCount);
        Assert.assertTrue("TR1.4 段指纹 64-hex", snap.segmentFingerprint.matches("[0-9a-f]{64}"));

        Assert.assertTrue("TR1.4 段文件存在", new File(root, "wordnet/seg-2.dnet").isFile());
        String manifest = readManifest(root);
        Assert.assertTrue("TR1.4 manifest 已登记 segId=2, manifest=" + manifest, manifest.contains("2"));

        Object st = openStore(root, 2L);
        try {
            Assert.assertEquals("TR1.4 邻接哨兵 payload = -1", -1, payloadInt(st, 111L, true));
            Assert.assertEquals("TR1.4 邻接哨兵 payload = -1（键 222）", -1, payloadInt(st, 222L, true));
            Assert.assertEquals("TR1.4 positions 哨兵 payload = -1", -1, payloadInt(st, 333L, false));
        } finally {
            closeStore(st);
        }
    }

    // ---------------- TR1.5 TOMBSTONE_PAYLOAD 常量 + 空 changedCtx 合法段 ----------------
    @Test(timeout = 30000)
    public void tr15_tombstoneConstantAndEmptyDelta() throws Exception {
        Field f = WordNetExporter.class.getDeclaredField("TOMBSTONE_PAYLOAD");
        f.setAccessible(true);
        byte[] payload = (byte[]) f.get(null);
        Assert.assertArrayEquals("TR1.5 哨兵字节 = FF FF FF FF",
                new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, payload);
        Assert.assertEquals("TR1.5 哨兵 int 语义 = -1", -1, ByteBuffer.wrap(payload).getInt());

        WordNetSnapshot empty = new WordNetExporter().exportDelta(store, root, 77L,
                Collections.emptyList());
        Assert.assertEquals("TR1.5 空变更 ctxCount=0", 0L, empty.ctxCount);
        Assert.assertEquals("TR1.5 空变更 adjCount=0", 0L, empty.adjCount);
        Assert.assertEquals("TR1.5 空变更 posCount=0", 0L, empty.posCount);
        try (WordNetSegmentView v = WordNetSegmentView.open(root, new long[]{77L})) {
            Assert.assertEquals("TR1.5 空段视图段数=1", 1, v.segmentCount());
            Assert.assertTrue("TR1.5 空段邻接查询为空",
                    v.neighborsWithin(123L, DOC, DOC * 10L + 1L, 1, 5).isEmpty());
            Assert.assertEquals("TR1.5 空段距离=-1", -1, v.distanceBetween(1L, 2L, DOC, DOC * 10L + 1L, 1));
        }
    }

    // ---------------- helpers ----------------
    private static String readManifest(String root) throws IOException {
        File m = new File(root, "wordnet/manifest");
        Assert.assertTrue("manifest 必须存在", m.isFile());
        return new String(Files.readAllBytes(m.toPath()), StandardCharsets.UTF_8);
    }

    private static Object openStore(String root, long segId) throws Exception {
        Class<?> sc = Class.forName("com.q3lives.ds.database.fulltext.wordnet.WordNetSegmentView$Store");
        Method open = sc.getDeclaredMethod("open", String.class, long.class);
        open.setAccessible(true);
        return open.invoke(null, root, segId);
    }

    private static void closeStore(Object st) throws Exception {
        st.getClass().getMethod("close").invoke(st);
    }

    private static int payloadInt(Object st, long key, boolean adj) throws Exception {
        Class<?> sc = st.getClass();
        Method m = sc.getDeclaredMethod(adj ? "adjPayload" : "posPayload", long.class);
        m.setAccessible(true);
        byte[] p = (byte[]) m.invoke(st, key);
        Assert.assertNotNull("payload 必须命中 key=" + key, p);
        Assert.assertEquals("哨兵 payload 宽度 4B", 4, p.length);
        return ByteBuffer.wrap(p).getInt();
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
