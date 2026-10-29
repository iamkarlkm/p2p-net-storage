package ds;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.cluster.DsPartitionRouter;
import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.collections.DsHashMapIdAllocator;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.DsDatabaseClusterClient;
import com.q3lives.ds.database.columnar.DsPartitionManager;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.rowsource.ColumnarAggRowSource;
import com.q3lives.ds.database.integration.GenericManager;

import javax.net.p2p.model.DbQuery;

/**
 * P46 T1：{@link ColumnarAggRowSource} 行源骨架 5 TR 0F0E。
 *
 * <p>
 * TR1.1 ctor gm null → IAE；TR1.2 ctor colClient != null 且 entityClassName 空白 →
 * IAE；TR1.3 ctor 本地双模（colClient == null）允许 entityClassName null；TR1.4 fetch 入参
 * IAE（null / 空 / 含 null 节点）；TR1.5 含 FT 节点 → UOE + 初始零调用（未 fetch 前 0 次
 * queryIds）。
 */
public class P46_T1_ColumnarRowSourceSkeletonTest {

    /**
     * 测试实体。
     */
    public static class Row extends DsTableAdapter {

        @DsField(name = "tag", length = 16)
        public String tag;
        @DsField(name = "price", length = 8)
        public Double price;

        public Row() {
        }
    }

    private static final DsServerDirectory EMPTY_DIR = new DsServerDirectory() {
        @Override
        public int[] listServerIds() {
            return new int[0];
        }

        @Override
        public Endpoint endpoint(int serverId) {
            return null;
        }
    };

    /**
     * 可编程列存客户端：覆写 {@code queryIds} 记录调用次数并返回预设 id 列表（0 真实网络）。
     */
    static class MockColClient extends DsDatabaseClusterClient {

        final List<Long> nextIds = new ArrayList<>();
        int calls;

        MockColClient(DsPartitionManager m, DsHashMapIdAllocator a) {
            super(1, m, a, EMPTY_DIR);
        }

        @Override
        public List<Long> queryIds(String entityClassName, DbQuery query, int offset, int limit) {
            calls++;
            return new ArrayList<>(nextIds);
        }
    }

    private File tmpRoot;
    private String oldHome;
    private GenericManager<Row> gm;
    private MockColClient mock;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p46_t1_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        gm = new GenericManager<>(Row.class);
        DsPartitionManager manager
                = new DsPartitionManager(new File(tmpRoot, "pm"), DsPartitionRouter.maxPartitionCount(),
                        new String[0]);
        DsHashMapIdAllocator allocator = new DsHashMapIdAllocator(new DsHashMap(new File(tmpRoot, "agg.map")));
        mock = new MockColClient(manager, allocator);
    }

    @After
    public void after() {
        if (mock != null) {
            mock.close();
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

    private static void expectIae(Runnable r) {
        try {
            r.run();
        } catch (IllegalArgumentException ok) {
            return;
        }
        Assert.fail("expected IllegalArgumentException");
    }

    private static void expectUoe(Runnable r) {
        try {
            r.run();
        } catch (UnsupportedOperationException ok) {
            return;
        }
        Assert.fail("expected UnsupportedOperationException");
    }

    // ============== TR1.1 ctor gm null → IAE ==============
    @Test
    public void tr11_ctorGmNullThrowsIae() {
        expectIae(() -> new ColumnarAggRowSource<Row>(null, null, null));
        expectIae(() -> new ColumnarAggRowSource<Row>(null, mock, Row.class.getName()));
    }

    // ============== TR1.2 ctor colClient != null 且 entityClassName 空白 → IAE ==============
    @Test
    public void tr12_ctorColClientBlankEntityThrowsIae() {
        expectIae(() -> new ColumnarAggRowSource<>(gm, mock, null));
        expectIae(() -> new ColumnarAggRowSource<>(gm, mock, ""));
        expectIae(() -> new ColumnarAggRowSource<>(gm, mock, "   "));
    }

    // ============== TR1.3 ctor 本地双模（colClient == null）允许 entityClassName null ==============
    @Test
    public void tr13_ctorLocalModeAllowsNullEntity() {
        ColumnarAggRowSource<Row> local = new ColumnarAggRowSource<>(gm, null, null);
        Assert.assertNotNull(local);
        Assert.assertEquals("本地模式不触碰列存客户端", 0, mock.calls);
    }

    // ============== TR1.4 fetch 入参 IAE（null / 空 / 含 null 节点） ==============
    @Test
    public void tr14_fetchMintermValidationThrowsIae() {
        ColumnarAggRowSource<Row> local = new ColumnarAggRowSource<>(gm, null, null);
        expectIae(() -> local.fetch(null));
        expectIae(() -> local.fetch(Collections.emptyList()));
        List<ConditionNode> withNull = new ArrayList<>();
        withNull.add(null);
        expectIae(() -> local.fetch(withNull));

        ColumnarAggRowSource<Row> srv = new ColumnarAggRowSource<>(gm, mock, Row.class.getName());
        expectIae(() -> srv.fetch(null));
        expectIae(() -> srv.fetch(Collections.emptyList()));
        expectIae(() -> srv.fetch(withNull));
    }

    // ============== TR1.5 含 FT 节点 → UOE + 初始零调用 ==============
    @Test
    public void tr15_fetchFtNodeThrowsUoeAndNoInitialCall() {
        ColumnarAggRowSource<Row> local = new ColumnarAggRowSource<>(gm, null, null);
        ColumnarAggRowSource<Row> srv = new ColumnarAggRowSource<>(gm, mock, Row.class.getName());
        Assert.assertEquals("初始（未 fetch）零调用", 0, mock.calls);

        List<ConditionNode> ftOnly = Collections.singletonList(ConditionNode.ft("java"));
        expectUoe(() -> local.fetch(ftOnly));
        expectUoe(() -> srv.fetch(ftOnly));
        Assert.assertEquals("FT 节点在 queryIds 前即被拒绝，仍 0 调用", 0, mock.calls);
    }
}
