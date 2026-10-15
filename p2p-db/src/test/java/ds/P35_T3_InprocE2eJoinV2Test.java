package ds;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsOneToOne;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient;
import com.q3lives.ds.database.fulltext.cluster.join.JoinSpec;
import com.q3lives.ds.database.fulltext.cluster.join.v2.HybridJoinClientV2Adapter;
import com.q3lives.ds.database.fulltext.cluster.join.v2.JoinMode;
import com.q3lives.ds.database.fulltext.cluster.join.v2.ManyToOneJoinResult;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P35 T3：Inproc E2E N:1 + J=2 树级联 + 共存 P31 1:1，3 TR。
 *
 * <p>
 * TR3.1 J=1 N:1 E2E 合查 + HybridHit.joinFetch 含 ManyToOneJoinResult
 * <br>TR3.2 J=2 树级联 E2E
 * <br>TR3.3 共存 P31 1:1 + N:1，互不干扰
 */
public class P35_T3_InprocE2eJoinV2Test {

    @Rule
    public TemporaryFolder tf = new TemporaryFolder();

    @com.q3lives.ds.annotation.DsTable(name = "p35_t3_main")
    public static class MainT3 extends DsTableAdapter {

        @DsField(name = "title", length = 96)
        public String title;
        @DsField(name = "ftText", length = 512)
        public String ftText;
        @DsOneToOne(joinProp = "mainId", props = "id")
        public UserT3 user;
    }

    @com.q3lives.ds.annotation.DsTable(name = "p35_t3_item")
    public static class OrderItemT3 extends DsTableAdapter {

        @DsField(name = "sku", length = 64)
        public String sku;
        @DsField(name = "qty", length = 8)
        public Long qty;
        @DsField(name = "mainId", length = 8)
        public Long mainId;
    }

    @com.q3lives.ds.annotation.DsTable(name = "p35_t3_ship")
    public static class ShipmentT3 extends DsTableAdapter {

        @DsField(name = "carrier", length = 64)
        public String carrier;
        @DsField(name = "itemId", length = 8)
        public Long itemId;
    }

    @com.q3lives.ds.annotation.DsTable(name = "p35_t3_user")
    public static class UserT3 extends DsTableAdapter {

        @DsField(name = "name", length = 64)
        public String name;
        @DsField(name = "mainId", length = 8)
        public Long mainId;
    }

    private static final String[] CORPUS = new String[]{
        "人工智能 大模型 深度学习 神经网络 机器学习 自然语言处理",
        "科技创新 推动 高质量发展 新质生产力 突破 攻关",
        "绿水青山 生态环境保护 可持续发展 低碳经济",
        "人类命运共同体 全球治理 国际合作 共赢",
        "数字经济 实体经济 深度融合 大数据 云计算 边缘计算"
    };

    private static final int SHARDS = 1;
    private static final int N = 10;

    private String oldHome;
    private File tmpRoot;
    private File ftRoot;
    private DsFulltextClusterClient ftClient;
    private GenericManager<MainT3> mainGm;
    private GenericManager<OrderItemT3> itemGm;
    private GenericManager<ShipmentT3> shipGm;
    private GenericManager<UserT3> userGm;
    private Map<Class<?>, GenericManager<?>> relatedGms;
    private List<Long> allMainIds;
    private Map<Long, String> rawDocTexts;

    @Before
    public void setUp() throws Exception {
        tmpRoot = tf.newFolder("p35t3-" + UUID.randomUUID());
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        ftRoot = new File(tmpRoot, "ft_inproc");
        ftRoot.mkdirs();
        ftClient = new DsFulltextClusterClient(SHARDS, null, ftRoot.getAbsolutePath(), "shard");

        mainGm = new GenericManager<>(MainT3.class);
        itemGm = new GenericManager<>(OrderItemT3.class);
        shipGm = new GenericManager<>(ShipmentT3.class);
        userGm = new GenericManager<>(UserT3.class);
        relatedGms = new HashMap<>();
        relatedGms.put(OrderItemT3.class, itemGm);
        relatedGms.put(ShipmentT3.class, shipGm);
        relatedGms.put(UserT3.class, userGm);

        rawDocTexts = new LinkedHashMap<>();
        allMainIds = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            MainT3 m = new MainT3();
            int seed = i % CORPUS.length;
            String text = CORPUS[seed] + " doc_" + String.format("%03d", i)
                    + " 扩展 关键词 人民 科技 绿色 国际 现代化 民主 共赢 规划 大模型";
            m.title = "订单_" + String.format("%03d", i);
            m.ftText = text;
            mainGm.saveOrUpdate(m);
            long mid = m.getId();
            IngestStat st = ftClient.putDocCluster(mid, text);
            Assert.assertNotNull("putDocCluster 非 null", st);
            rawDocTexts.put(mid, text);
            allMainIds.add(mid);
        }
        Thread.sleep(300);
    }

    @After
    public void tearDown() {
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
        if (oldHome == null) {
            System.clearProperty("p2p.db.home");
        } else {
            System.setProperty("p2p.db.home", oldHome);
        }
    }

    private HybridJoinClient<MainT3> newHjc() throws Exception {
        DsHybridQueryClient<MainT3> dhqc = DsHybridQueryClient.ofLocal(MainT3.class, mainGm, ftClient);
        return new HybridJoinClient<>(dhqc, relatedGms, mainGm);
    }

    /* -------- TR3.1 J=1 N:1 E2E -------- */
    @Test(timeout = 120000)
    public void tr31_n1E2e() throws Exception {
        // 给 5 个主表挂 orderItem（qty >= 5），其余不挂
        for (int i = 0; i < 5; i++) {
            OrderItemT3 it = new OrderItemT3();
            it.sku = "SKU-" + i;
            it.qty = 10L;
            it.mainId = allMainIds.get(i);
            itemGm.saveOrUpdate(it);
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        QueryWrapper qSub = new QueryWrapper();
        qSub.ge("qty", 5L);
        JoinSpec<OrderItemT3> j = new JoinSpec<>(OrderItemT3.class, "id", "mainId",
                (QueryWrapper) qSub,
                JoinMode.MANY_TO_ONE, 256);
        HybridJoinClient<MainT3> hjc = newHjc();
        try {
            List<HybridHit<MainT3>> hits = HybridJoinClientV2Adapter.searchHybridJoinWithManyToOne(
                    hjc, new QueryWrapper<>(), j, "人工智能", rawDocTexts, 20,
                    SearchOptions.defaults(), mainGm, relatedGms);
            // 至少 1 个 hit（FT "人工智能" 命中）
            Assert.assertTrue("N:1 E2E hits ≥ 1, got " + hits.size(), hits.size() >= 1);
            for (HybridHit<MainT3> h : hits) {
                Assert.assertTrue("hit docId ∈ seed main", allMainIds.contains(h.docId));
                Assert.assertNotNull("joinFetch 必须装载", h.joinFetch);
                Assert.assertTrue("joinFetch 包含 OrderItemT3",
                        h.joinFetch.containsKey(OrderItemT3.class));
                Object fetch = h.joinFetch.get(OrderItemT3.class);
                Assert.assertTrue("fetch 应是 ManyToOneJoinResult",
                        fetch instanceof ManyToOneJoinResult);
                ManyToOneJoinResult m = (ManyToOneJoinResult) fetch;
                Assert.assertNotNull("byOuterId 非 null", m.byOuterId);
            }
        } finally {
            hjc.close();
        }
    }

    /* -------- TR3.2 J=2 树级联 E2E -------- */
    @Test(timeout = 120000)
    public void tr32_j2TreeE2e() throws Exception {
        // 给 5 个 main 挂 2 个 item（仅 j=0 挂 ship）
        for (int i = 0; i < 5; i++) {
            OrderItemT3 item0 = new OrderItemT3();
            item0.sku = "SKU-A-" + i;
            item0.qty = 10L;
            item0.mainId = allMainIds.get(i);
            itemGm.saveOrUpdate(item0);
            OrderItemT3 item1 = new OrderItemT3();
            item1.sku = "SKU-B-" + i;
            item1.qty = 10L;
            item1.mainId = allMainIds.get(i);
            itemGm.saveOrUpdate(item1);
            // ship 仅挂 item0
            ShipmentT3 sh = new ShipmentT3();
            sh.carrier = "SF";
            sh.itemId = item0.getId();
            shipGm.saveOrUpdate(sh);
        }
        JoinSpec<OrderItemT3> j1 = new JoinSpec<>(OrderItemT3.class, "id", "mainId",
                null, JoinMode.MANY_TO_ONE, 256);
        JoinSpec<ShipmentT3> j2 = new JoinSpec<>(ShipmentT3.class, "id", "itemId",
                null, JoinMode.MANY_TO_ONE, 256);
        HybridJoinClient<MainT3> hjc = newHjc();
        try {
            List<HybridHit<MainT3>> hits = HybridJoinClientV2Adapter.searchHybridJoinWithManyToOneTree(
                    hjc, new QueryWrapper<>(), j1, j2, "人工智能", rawDocTexts, 20,
                    SearchOptions.defaults(), mainGm, relatedGms);
            Assert.assertTrue("J=2 树 E2E hits ≥ 1, got " + hits.size(), hits.size() >= 1);
            for (HybridHit<MainT3> h : hits) {
                Assert.assertTrue("hit docId ∈ seed main", allMainIds.contains(h.docId));
                Assert.assertNotNull("joinFetch 必须装载", h.joinFetch);
                Assert.assertTrue("joinFetch 包含 OrderItemT3 (R1)",
                        h.joinFetch.containsKey(OrderItemT3.class));
                Assert.assertTrue("joinFetch 包含 ShipmentT3 (R2)",
                        h.joinFetch.containsKey(ShipmentT3.class));
                Object r1 = h.joinFetch.get(OrderItemT3.class);
                Object r2 = h.joinFetch.get(ShipmentT3.class);
                Assert.assertTrue(r1 instanceof ManyToOneJoinResult);
                Assert.assertTrue(r2 instanceof ManyToOneJoinResult);
            }
        } finally {
            hjc.close();
        }
    }

    /* -------- TR3.3 共存 P31 1:1 + N:1 -------- */
    @Test(timeout = 120000)
    public void tr33_coexistOneToOneAndManyToOne() throws Exception {
        // 给 5 个 main 挂 user (1:1) + item (N:1)
        for (int i = 0; i < 5; i++) {
            UserT3 u = new UserT3();
            u.name = "User" + i;
            u.mainId = allMainIds.get(i);
            userGm.saveOrUpdate(u);
            for (int j = 0; j < 2; j++) {
                OrderItemT3 it = new OrderItemT3();
                it.sku = "SKU-" + i + "-" + j;
                it.qty = 10L;
                it.mainId = allMainIds.get(i);
                itemGm.saveOrUpdate(it);
            }
        }
        // 1:1 user join（OneToOne annotation 自动反射）
        List<JoinSpec<?>> reflectJoins = JoinSpec.fromDsOneToOneAnnotations(MainT3.class);
        JoinSpec<UserT3> j11 = new JoinSpec<>(UserT3.class, "id", "mainId", null);
        reflectJoins = new java.util.ArrayList<>();
        reflectJoins.add(j11);
        // N:1 item join
        JoinSpec<OrderItemT3> jN = new JoinSpec<>(OrderItemT3.class, "id", "mainId",
                null, JoinMode.MANY_TO_ONE, 256);
        HybridJoinClient<MainT3> hjc = newHjc();
        try {
            // 1:1 路径走 P31 HJC
            List<HybridHit<MainT3>> hits11 = hjc.searchHybridJoin(new QueryWrapper<>(),
                    Collections.singletonList(j11), "人工智能", rawDocTexts, 10,
                    SearchOptions.defaults());
            // N:1 路径走 V2Adapter
            List<HybridHit<MainT3>> hitsN = HybridJoinClientV2Adapter.searchHybridJoinWithManyToOne(
                    hjc, new QueryWrapper<>(), jN, "人工智能", rawDocTexts, 10,
                    SearchOptions.defaults(), mainGm, relatedGms);
            Assert.assertTrue("1:1 路径 hits≥1, got " + hits11.size(), hits11.size() >= 1);
            Assert.assertTrue("N:1 路径 hits≥1, got " + hitsN.size(), hitsN.size() >= 1);
            // N:1 路径的 joinFetch 含 OrderItemT3
            for (HybridHit<MainT3> h : hitsN) {
                Assert.assertTrue("N:1 joinFetch 含 OrderItemT3",
                        h.joinFetch.containsKey(OrderItemT3.class));
            }
            // 1:1 路径的 joinFetch 含 UserT3（P31 默认装载）
            for (HybridHit<MainT3> h : hits11) {
                Assert.assertTrue("1:1 joinFetch 含 UserT3",
                        h.joinFetch.containsKey(UserT3.class));
            }
        } finally {
            hjc.close();
        }
    }
}
