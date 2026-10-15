package ds;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.join.JoinSpec;
import com.q3lives.ds.database.fulltext.cluster.join.v2.JoinIdResolverV2;
import com.q3lives.ds.database.fulltext.cluster.join.v2.JoinMode;
import com.q3lives.ds.database.fulltext.cluster.join.v2.ManyToOneJoinResult;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P35 T2：JoinIdResolverV2 IN 子查询 + FetchSize 切片 + 树级联 6 TR。
 *
 * <p>
 * TR2.1 单批次 IN：N=5 outerIds + 3 matched
 * <br>TR2.2 多批次切片：N=10 + batchSize=3 → 4 切片
 * <br>TR2.3 aggregateByOuterId 聚合：byOuterId Map 正确
 * <br>TR2.4 J=2 树级联：T1 ∩ R1 ∩ R2
 * <br>TR2.5 batchSize > 4096 IAE
 * <br>TR2.6 mode 非 MANY_TO_ONE IAE
 */
public class P35_T2_JoinIdResolverV2Test {

    @Rule
    public TemporaryFolder tf = new TemporaryFolder();

    @com.q3lives.ds.annotation.DsTable(name = "p35_t2_main")
    public static class MainT2 extends DsTableAdapter {

        @DsField(name = "name", length = 64)
        public String name;
    }

    @com.q3lives.ds.annotation.DsTable(name = "p35_t2_sub1")
    public static class Sub1T2 extends DsTableAdapter {

        @DsField(name = "info", length = 128)
        public String info;
        @DsField(name = "mainId", length = 8)
        public Long mainId;
    }

    @com.q3lives.ds.annotation.DsTable(name = "p35_t2_sub2")
    public static class Sub2T2 extends DsTableAdapter {

        @DsField(name = "tag", length = 64)
        public String tag;
        @DsField(name = "sub1Id", length = 8)
        public Long sub1Id;
    }

    private String oldHome;
    private GenericManager<MainT2> mainGm;
    private GenericManager<Sub1T2> sub1Gm;
    private GenericManager<Sub2T2> sub2Gm;

    @Before
    public void setUp() throws Exception {
        File tmp = tf.newFolder("p35t2-" + UUID.randomUUID());
        DsDatabaseLocal.forceResetAllIndexesForTest(tmp);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmp);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmp.getAbsolutePath());
        mainGm = new GenericManager<>(MainT2.class);
        sub1Gm = new GenericManager<>(Sub1T2.class);
        sub2Gm = new GenericManager<>(Sub2T2.class);
    }

    @After
    public void tearDown() {
        if (oldHome == null) {
            System.clearProperty("p2p.db.home");
        } else {
            System.setProperty("p2p.db.home", oldHome);
        }
    }

    private Map<Class<?>, GenericManager<?>> relGms() {
        Map<Class<?>, GenericManager<?>> m = new HashMap<>();
        m.put(Sub1T2.class, sub1Gm);
        m.put(Sub2T2.class, sub2Gm);
        return m;
    }

    /* -------- TR2.1 单批次 IN -------- */
    @Test
    public void tr21_singleBatch() {
        // 5 主表 + 5 从表（mainId 取实际 main 表 id），outer = {1..5} 是 outerIds 参数的语义占位
        // 关键：resolveManyToOne 用 outerIds ∩ matched 算法；sub 行 mainId 都 ∈ {1..5}，matched ⊇ outerIds
        // 这里直接构造 outer={1,2,3,4,5} 让 Sub1T2.mainId ∈ {1..5} 命中
        Set<Long> outer = new HashSet<>(Arrays.asList(1L, 2L, 3L, 4L, 5L));
        // Sub1: 5 个，mainId=1..5
        for (int i = 1; i <= 5; i++) {
            Sub1T2 s = new Sub1T2();
            s.info = "S" + i;
            s.mainId = (long) i;
            sub1Gm.saveOrUpdate(s);
        }
        // 5 主表（与 N:1 语义对齐：1 个外层一行可关联多 Sub，这里 main 只插入作完整性占位）
        for (int i = 1; i <= 5; i++) {
            MainT2 m = new MainT2();
            m.name = "M" + i;
            mainGm.saveOrUpdate(m);
        }
        JoinSpec<Sub1T2> j = new JoinSpec<>(Sub1T2.class, "id", "mainId", null,
                JoinMode.MANY_TO_ONE, 256);
        Set<Long> matched = JoinIdResolverV2.resolveManyToOne(outer, j, relGms());
        // outerIds={1..5}, matched={1..5}, 交集=5
        Assert.assertEquals(5, matched.size());
        Assert.assertTrue(matched.contains(1L));
        Assert.assertTrue(matched.contains(5L));
    }

    /* -------- TR2.2 多批次切片 -------- */
    @Test
    public void tr22_multiBatch() {
        // 10 主表 + 10 从表
        for (long i = 1; i <= 10; i++) {
            MainT2 m = new MainT2();
            m.name = "M" + i;
            mainGm.saveOrUpdate(m);
            Sub1T2 s = new Sub1T2();
            s.info = "S" + i;
            s.mainId = (long) i;
            sub1Gm.saveOrUpdate(s);
        }
        // 5 sub 多 1 个（mainId=1..5 各 2 个）
        for (int i = 1; i <= 5; i++) {
            Sub1T2 s = new Sub1T2();
            s.info = "S-dup-" + i;
            s.mainId = (long) i;
            sub1Gm.saveOrUpdate(s);
        }
        Set<Long> outer = new HashSet<>(Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L));
        // batchSize=3 → 4 切片
        JoinSpec<Sub1T2> j = new JoinSpec<>(Sub1T2.class, "id", "mainId", null,
                JoinMode.MANY_TO_ONE, 3);
        Set<Long> matched = JoinIdResolverV2.resolveManyToOne(outer, j, relGms());
        Assert.assertEquals(10, matched.size());
    }

    /* -------- TR2.3 aggregateByOuterId 聚合 -------- */
    @Test
    public void tr23_aggregateByOuterId() {
        for (long i = 1; i <= 3; i++) {
            MainT2 m = new MainT2();
            m.name = "M" + i;
            mainGm.saveOrUpdate(m);
        }
        // mainId=1 3 个，mainId=2 2 个，mainId=3 1 个
        for (int i = 0; i < 3; i++) {
            Sub1T2 s = new Sub1T2();
            s.info = "S1-" + i;
            s.mainId = 1L;
            sub1Gm.saveOrUpdate(s);
        }
        for (int i = 0; i < 2; i++) {
            Sub1T2 s = new Sub1T2();
            s.info = "S2-" + i;
            s.mainId = 2L;
            sub1Gm.saveOrUpdate(s);
        }
        Sub1T2 s = new Sub1T2();
        s.info = "S3";
        s.mainId = 3L;
        sub1Gm.saveOrUpdate(s);
        Set<Long> outer = new HashSet<>(Arrays.asList(1L, 2L, 3L));
        JoinSpec<Sub1T2> j = new JoinSpec<>(Sub1T2.class, "id", "mainId", null,
                JoinMode.MANY_TO_ONE, 256);
        ManyToOneJoinResult<Sub1T2> m = JoinIdResolverV2.aggregateByOuterId(outer, j, sub1Gm);
        Assert.assertEquals(3, m.matchedOuterIds.size());
        Assert.assertEquals(3, m.byOuterId.get(1L).size());
        Assert.assertEquals(2, m.byOuterId.get(2L).size());
        Assert.assertEquals(1, m.byOuterId.get(3L).size());
    }

    /* -------- TR2.4 J=2 树级联 -------- */
    @Test
    public void tr24_treeJ2() {
        // 用 snowflake 风格的高位 id（与 bucketStore.getNewId 一致）
        // 改用 saveOrUpdate 后回读 id，让 mainId/sub1Id 引用实际 id
        java.util.List<Long> mainIds = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            MainT2 m = new MainT2();
            m.name = "M" + i;
            mainGm.saveOrUpdate(m);
            mainIds.add(m.getId());
        }
        java.util.List<Long> sub1Ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Sub1T2 s = new Sub1T2();
            s.info = "S1-" + i;
            s.mainId = mainIds.get(i - 1);
            sub1Gm.saveOrUpdate(s);
            sub1Ids.add(s.getId());
        }
        java.util.List<Long> sub2Ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Sub2T2 t = new Sub2T2();
            t.tag = "T" + i;
            t.sub1Id = sub1Ids.get(i - 1);
            sub2Gm.saveOrUpdate(t);
            sub2Ids.add(t.getId());
        }
        JoinSpec<Sub1T2> j1 = new JoinSpec<>(Sub1T2.class, "id", "mainId", null,
                JoinMode.MANY_TO_ONE, 256);
        JoinSpec<Sub2T2> j2 = new JoinSpec<>(Sub2T2.class, "id", "sub1Id", null,
                JoinMode.MANY_TO_ONE, 256);
        Set<Long> matched = JoinIdResolverV2.resolveManyToOneTree(mainGm,
                new QueryWrapper<>(), j1, j2, relGms());
        // T1 ∩ R1 ∩ R2 = {mainIds[0..2]} (因为 Sub2T2.sub1Id 只在 sub1Ids[0..2])
        Assert.assertEquals(3, matched.size());
        Assert.assertTrue(matched.contains(mainIds.get(0)));
        Assert.assertTrue(matched.contains(mainIds.get(1)));
        Assert.assertTrue(matched.contains(mainIds.get(2)));
        Assert.assertFalse(matched.contains(mainIds.get(3)));
        Assert.assertFalse(matched.contains(mainIds.get(4)));
    }

    /* -------- TR2.5 batchSize > 4096 IAE -------- */
    @Test
    public void tr25_batchSizeOverLimit() {
        // JoinSpec 构造器自身就校验 batchSize > 4096
        try {
            new JoinSpec<Sub1T2>(Sub1T2.class, "id", "mainId", null,
                    JoinMode.MANY_TO_ONE, 4097);
            Assert.fail("batchSize=4097 应抛 IAE");
        } catch (IllegalArgumentException ignore) {
        }
        // 边界 4096 合法
        JoinSpec<Sub1T2> j = new JoinSpec<>(Sub1T2.class, "id", "mainId", null,
                JoinMode.MANY_TO_ONE, 4096);
        Assert.assertEquals(4096, j.batchSize);
    }

    /* -------- TR2.6 mode 非 MANY_TO_ONE IAE -------- */
    @Test
    public void tr26_wrongMode() {
        JoinSpec<Sub1T2> j = new JoinSpec<>(Sub1T2.class, "id", "mainId", null);  // 默认 ONE_TO_ONE
        try {
            JoinIdResolverV2.resolveManyToOne(new HashSet<>(Arrays.asList(1L)), j, relGms());
            Assert.fail("ONE_TOONE 应抛 IAE");
        } catch (IllegalArgumentException ignore) {
        }
    }
}
