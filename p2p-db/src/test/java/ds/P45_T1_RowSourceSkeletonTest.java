package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.rowsource.FulltextAggRowSource;
import com.q3lives.ds.database.integration.GenericManager;

/**
 * P45 T1：{@link FulltextAggRowSource} 骨架 5 TR 0F0E。
 *
 * <p>
 * TR1.1 ctor gm==null → IAE；TR1.2 ctor ftClient==null → IAE；TR1.3 ctor ftK&lt;1 → IAE（边界 ftK=1
 * 通过）；TR1.4 {@code fetch} IAE 矩阵（minterm null / 空 / 含 null）；TR1.5 ctor 惰性（0 次 FT/ORM
 * 调用）+ opt=null → {@link SearchOptions#defaults()}，仅 {@code fetch} 触发 FT 检索。
 */
public class P45_T1_RowSourceSkeletonTest {

    /** 测试实体：tag 过滤列 + price 数值列（承载聚合）。 */
    public static class Row extends DsTableAdapter {
        @DsField(name = "tag", length = 16)
        public String tag;
        @DsField(name = "price", length = 8)
        public Double price;

        public Row() {
        }
    }

    /** 计数 FT 客户端：覆写 {@code searchCluster} 计数 + 可编程命中 docId（不触真实索引）。 */
    static class CountingFtClient extends DsFulltextClusterClient {
        final AtomicInteger searchCalls = new AtomicInteger();
        volatile List<Long> nextHits = Collections.emptyList();

        CountingFtClient(String root) throws IOException {
            super(2, null, root, "shard");
        }

        @Override
        public List<ScoredDocHit> searchCluster(String q, int K, SearchOptions opt) {
            searchCalls.incrementAndGet();
            List<ScoredDocHit> out = new ArrayList<>();
            for (long id : nextHits) {
                out.add(new ScoredDocHit(id, 0L, 1, 0, 0L, 1.0, 1.0, 0.0, 0.0, 1));
            }
            return out;
        }
    }

    private File tmpRoot;
    private String oldHome;
    private CountingFtClient ftClient;
    private GenericManager<Row> gm;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p45_t1_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        File ftRoot = new File(tmpRoot, "ft");
        ftRoot.mkdirs();
        ftClient = new CountingFtClient(ftRoot.getAbsolutePath());
        gm = new GenericManager<>(Row.class);
    }

    @After
    public void after() {
        try {
            if (ftClient != null) {
                ftClient.close();
            }
        } catch (Exception ignore) {
        }
        try {
            DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        } catch (Exception ignore) {
        }
        try {
            DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        } catch (Exception ignore) {
        }
        try {
            Class<?> c = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            Method m = c.getDeclaredMethod("clearAll");
            m.setAccessible(true);
            m.invoke(null);
        } catch (Throwable ignore) {
        }
        deleteRecursive(tmpRoot);
        if (oldHome == null) {
            System.clearProperty("p2p.db.home");
        } else {
            System.setProperty("p2p.db.home", oldHome);
        }
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

    // ============== TR1.1 ctor gm == null ==============

    @Test(timeout = 30000)
    public void tr11_ctorGmNullThrowsIae() {
        try {
            FulltextAggRowSource<Row> s = new FulltextAggRowSource<>(null, ftClient, 10, null);
            Assert.fail("gm=null 必须抛 IAE，却得到 " + s);
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue("gm null 抛 IAE", true);
        }
    }

    // ============== TR1.2 ctor ftClient == null ==============

    @Test(timeout = 30000)
    public void tr12_ctorFtClientNullThrowsIae() {
        try {
            FulltextAggRowSource<Row> s = new FulltextAggRowSource<>(gm, null, 10, null);
            Assert.fail("ftClient=null 必须抛 IAE，却得到 " + s);
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue("ftClient null 抛 IAE", true);
        }
    }

    // ============== TR1.3 ctor ftK < 1 ==============

    @Test(timeout = 30000)
    public void tr13_ctorFtKLt1ThrowsIae() {
        for (int k : new int[] {0, -1, -100}) {
            try {
                FulltextAggRowSource<Row> s = new FulltextAggRowSource<>(gm, ftClient, k, null);
                Assert.fail("ftK=" + k + " 必须抛 IAE，却得到 " + s);
            } catch (IllegalArgumentException ex) {
                Assert.assertTrue("ftK=" + k + " 抛 IAE", true);
            }
        }
        FulltextAggRowSource<Row> ok = new FulltextAggRowSource<>(gm, ftClient, 1, null);
        Assert.assertNotNull("ftK=1 为合法下界，不得抛异常", ok);
    }

    // ============== TR1.4 fetch IAE 矩阵 ==============

    @Test(timeout = 30000)
    public void tr14_fetchIaeMatrix() {
        FulltextAggRowSource<Row> src = new FulltextAggRowSource<>(gm, ftClient, 10, null);
        try {
            src.fetch(null);
            Assert.fail("minterm=null 必须抛 IAE");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue("minterm null 抛 IAE", true);
        }
        try {
            src.fetch(new ArrayList<>());
            Assert.fail("minterm 空 必须抛 IAE");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue("minterm 空 抛 IAE", true);
        }
        List<ConditionNode> withNull = new ArrayList<>();
        withNull.add(ConditionNode.orm("tag", DslToken.OP_EQ, "x"));
        withNull.add(null);
        try {
            src.fetch(withNull);
            Assert.fail("minterm 含 null 节点 必须抛 IAE");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue("minterm 含 null 抛 IAE", true);
        }
        Assert.assertEquals("IAE 前置校验 0 次 FT 调用", 0, ftClient.searchCalls.get());
    }

    // ============== TR1.5 ctor 惰性 + opt null → defaults ==============

    @Test(timeout = 30000)
    public void tr15_ctorLazyOptDefaultsOnlyFetchTouchesFt() throws Exception {
        FulltextAggRowSource<Row> src = new FulltextAggRowSource<>(gm, ftClient, 10, null);
        Assert.assertEquals("ctor 后 0 次 FT 调用（惰性）", 0, ftClient.searchCalls.get());

        Field optField = FulltextAggRowSource.class.getDeclaredField("opt");
        optField.setAccessible(true);
        Assert.assertNotNull("opt=null 必须回落到 SearchOptions.defaults()", optField.get(src));
        Field kField = FulltextAggRowSource.class.getDeclaredField("ftK");
        kField.setAccessible(true);
        Assert.assertEquals("ftK 原值保留", 10, ((Integer) kField.get(src)).intValue());

        SearchOptions custom = SearchOptions.defaults();
        FulltextAggRowSource<Row> src2 = new FulltextAggRowSource<>(gm, ftClient, 7, custom);
        Assert.assertSame("显式 opt 必须原样保留（不复制）", custom, optField.get(src2));

        ftClient.nextHits = Collections.emptyList();
        List<Row> rows = src.fetch(Collections.singletonList(ConditionNode.ft("java")));
        Assert.assertTrue("FT 无命中 → 空列表", rows.isEmpty());
        Assert.assertEquals("仅 fetch 触发 1 次 FT 检索", 1, ftClient.searchCalls.get());
    }
}
