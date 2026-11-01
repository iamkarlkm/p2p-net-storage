package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.wordnet.WordNetExporter;
import com.q3lives.ds.database.fulltext.wordnet.WordNetSegmentView;
import com.q3lives.ds.database.fulltext.wordnet.WordNetSnapshot;

/**
 * P51 T1：{@link WordNetSnapshot} 骨架 + 导出/视图入口守卫（5 TR）。
 *
 * <p>
 * TR1.1 快照 6 条 IAE；TR1.2 {@code fingerprint()} 64-hex + 同字段恒定 + 任一变即变； TR1.3
 * {@code deepEquals} 真值表；TR1.4 导出器无状态构造 + 4 IAE；TR1.5 视图 {@code open} 3 IAE。
 */
public class P51_T1_SnapshotSkeletonTest {

    private static final String HEX64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FulltextStore store;
    private TermDictStore td;
    private String root;

    @Before
    public void before() throws IOException {
        File dir = tmp.newFolder("p51-t1-" + UUID.randomUUID().toString().replace("-", ""));
        System.setProperty("p2p.db.home", dir.getAbsolutePath());
        root = dir.getAbsolutePath();
        store = new FulltextStore(root);
        td = store.getTermDict();
    }

    @After
    public void after() {
        try {
            store.close();
        } catch (Exception ignore) {
            // 关闭失败不阻塞用例
        }
    }

    // ---------------- TR1.1 快照 6 条 IAE ----------------
    @Test(timeout = 30000)
    public void tr11_snapshotSixIaeGuards() {
        WordNetSnapshot ok = new WordNetSnapshot(1L, 2L, 3L, 4L, HEX64);
        Assert.assertEquals("合法基线 segId", 1L, ok.segId);

        int thrown = 0;
        thrown += expectIae(() -> new WordNetSnapshot(-1L, 0L, 0L, 0L, HEX64));
        thrown += expectIae(() -> new WordNetSnapshot(1L, -1L, 0L, 0L, HEX64));
        thrown += expectIae(() -> new WordNetSnapshot(1L, 0L, -1L, 0L, HEX64));
        thrown += expectIae(() -> new WordNetSnapshot(1L, 0L, 0L, -1L, HEX64));
        thrown += expectIae(() -> new WordNetSnapshot(1L, 0L, 0L, 0L, null));
        thrown += expectIae(() -> new WordNetSnapshot(1L, 0L, 0L, 0L, "abc"));
        Assert.assertEquals("TR1.1 6 条 IAE 守卫全部生效", 6, thrown);
    }

    // ---------------- TR1.2 fingerprint 64-hex 恒定 / 敏感 ----------------
    @Test(timeout = 30000)
    public void tr12_fingerprintHex64StableAndSensitive() {
        WordNetSnapshot a = new WordNetSnapshot(7L, 11L, 13L, 17L, HEX64);
        String fa = a.fingerprint();
        Assert.assertTrue("TR1.2 fingerprint 必须 64 位小写 hex: " + fa, fa.matches("[0-9a-f]{64}"));
        Assert.assertEquals("TR1.2 同字段重复调用恒定", fa, a.fingerprint());
        Assert.assertEquals("TR1.2 同字段新实例恒定",
                fa, new WordNetSnapshot(7L, 11L, 13L, 17L, HEX64).fingerprint());
        Assert.assertNotEquals("segId 变 → 指纹变",
                fa, new WordNetSnapshot(8L, 11L, 13L, 17L, HEX64).fingerprint());
        Assert.assertNotEquals("ctxCount 变 → 指纹变",
                fa, new WordNetSnapshot(7L, 12L, 13L, 17L, HEX64).fingerprint());
        Assert.assertNotEquals("adjCount 变 → 指纹变",
                fa, new WordNetSnapshot(7L, 11L, 14L, 17L, HEX64).fingerprint());
        Assert.assertNotEquals("posCount 变 → 指纹变",
                fa, new WordNetSnapshot(7L, 11L, 13L, 18L, HEX64).fingerprint());
        String other = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
        Assert.assertNotEquals("segmentFingerprint 变 → 指纹变",
                fa, new WordNetSnapshot(7L, 11L, 13L, 17L, other).fingerprint());
    }

    // ---------------- TR1.3 deepEquals 真值表 ----------------
    @Test(timeout = 30000)
    public void tr13_deepEqualsTruthTable() {
        WordNetSnapshot a = new WordNetSnapshot(7L, 11L, 13L, 17L, HEX64);
        WordNetSnapshot same = new WordNetSnapshot(7L, 11L, 13L, 17L, HEX64);
        Assert.assertTrue("同字段 → true", a.deepEquals(same));
        Assert.assertTrue("自反 → true", a.deepEquals(a));
        Assert.assertFalse("null → false", a.deepEquals(null));
        Assert.assertFalse("异类型 → false", a.deepEquals("x"));
        Assert.assertFalse("segId 异 → false", a.deepEquals(new WordNetSnapshot(8L, 11L, 13L, 17L, HEX64)));
        Assert.assertFalse("ctxCount 异 → false", a.deepEquals(new WordNetSnapshot(7L, 12L, 13L, 17L, HEX64)));
        Assert.assertFalse("adjCount 异 → false", a.deepEquals(new WordNetSnapshot(7L, 11L, 14L, 17L, HEX64)));
        Assert.assertFalse("posCount 异 → false", a.deepEquals(new WordNetSnapshot(7L, 11L, 13L, 18L, HEX64)));
        Assert.assertFalse("segmentFingerprint 异 → false",
                a.deepEquals(new WordNetSnapshot(7L, 11L, 13L, 17L,
                        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")));
    }

    // ---------------- TR1.4 导出器零状态 + 4 IAE ----------------
    @Test(timeout = 30000)
    public void tr14_exporterStatelessConstructionAnd4Iae() throws IOException {
        WordNetExporter ex = new WordNetExporter();
        int instanceFields = 0;
        for (Field f : WordNetExporter.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                instanceFields++;
            }
        }
        Assert.assertEquals("TR1.4 导出器 0 实例字段（零状态；静态常量不计）", 0, instanceFields);
        Assert.assertEquals("TR1.4 导出器恰 1 个公开无参构造",
                1, WordNetExporter.class.getDeclaredConstructors().length);
        Assert.assertEquals("TR1.4 无参构造 0 参",
                0, WordNetExporter.class.getDeclaredConstructors()[0].getParameterCount());

        int thrown = 0;
        thrown += expectIae(() -> ex.export(null, root, 1L));
        thrown += expectIae(() -> ex.export(store, null, 1L));
        thrown += expectIae(() -> ex.export(store, "   ", 1L));
        thrown += expectIae(() -> ex.export(store, root, -1L));
        Assert.assertEquals("TR1.4 4 条 IAE 守卫全部生效", 4, thrown);

        WordNetSnapshot s1 = ex.export(store, root, 5L);
        Assert.assertEquals("TR1.4 空词网导出 segId", 5L, s1.segId);
        thrown = 0;
        thrown += expectIae(() -> ex.export(store, root, 5L));
        Assert.assertEquals("TR1.4 重复 segId 登记 IAE", 1, thrown);
    }

    // ---------------- TR1.5 视图 open 3 IAE ----------------
    @Test(timeout = 30000)
    public void tr15_viewOpenThreeIae() throws IOException {
        int thrown = 0;
        thrown += expectIae(() -> WordNetSegmentView.open(null, new long[]{1L}));
        thrown += expectIae(() -> WordNetSegmentView.open("  ", new long[]{1L}));
        thrown += expectIae(() -> WordNetSegmentView.open(root, null));
        thrown += expectIae(() -> WordNetSegmentView.open(root, new long[0]));
        Assert.assertEquals("TR1.5 视图 open 守卫生效", 4, thrown);
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
