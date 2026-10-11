package ds;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsOneToOne;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.join.JoinSpec;
import com.q3lives.ds.database.fulltext.cluster.join.JoinIdResolver;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

@SuppressWarnings({"rawtypes", "unchecked"})
public class P31_T2_JoinIdResolverTest {

    private File tmpRoot;
    private String oldHome;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p31_t2_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void after() {
        try { DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot); } catch (Exception ignore) {}
        try { DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot); } catch (Exception ignore) {}
        deleteRecursive(tmpRoot);
        if (oldHome == null) System.clearProperty("p2p.db.home");
        else System.setProperty("p2p.db.home", oldHome);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    // ===== TR2.1 独立表 =====
    @DsTable(name = "p31_main_t2_21")
    public static class MainT2_21 extends DsTableAdapter {
        @DsField(name = "name", length = 256) public String name;
        @DsField(name = "ft_text", length = 1024) public String ftText;
        @DsOneToOne(joinProp = "abcId", props = "id") public RelT2_21 rel;
    }
    @DsTable(name = "p31_rel_t2_21")
    public static class RelT2_21 extends DsTableAdapter {
        @DsField(name = "abc_id", length = 8) public Long abcId;
        @DsField(name = "price", length = 8) public Double price;
        @DsField(name = "qty", length = 8) public Long qty;
    }

    // ===== TR2.2 独立表 =====
    @DsTable(name = "p31_main_t2_22")
    public static class MainT2_22 extends DsTableAdapter {
        @DsField(name = "name", length = 256) public String name;
        @DsOneToOne(joinProp = "abcId", props = "id") public RelT2_22 rel;
    }
    @DsTable(name = "p31_rel_t2_22")
    public static class RelT2_22 extends DsTableAdapter {
        @DsField(name = "abc_id", length = 8) public Long abcId;
        @DsField(name = "price", length = 8) public Double price;
        @DsField(name = "qty", length = 8) public Long qty;
    }

    // ===== TR2.3 独立表 =====
    @DsTable(name = "p31_main_t2_23")
    public static class MainT2_23 extends DsTableAdapter {
        @DsField(name = "name", length = 256) public String name;
    }
    @DsTable(name = "p31_rel_t2_23")
    public static class RelT2_23 extends DsTableAdapter {
        @DsField(name = "abc_id", length = 8) public Long abcId;
        @DsField(name = "price", length = 8) public Double price;
        @DsField(name = "qty", length = 8) public Long qty;
    }
    @DsTable(name = "p31_tag_t2_23")
    public static class TagT2_23 extends DsTableAdapter {
        @DsField(name = "abc_id", length = 8) public Long abcId;
        @DsField(name = "tag", length = 64) public String tag;
    }

    // ===== TR2.4 反射校验表（不持久化，可共享） =====
    @DsTable(name = "p31_main_t2_24")
    public static class MainT2_24 extends DsTableAdapter {
        @DsField(name = "name", length = 256) public String name;
        @DsOneToOne(joinProp = "abcId", props = "id") public RelT2_24 rel;
    }
    @DsTable(name = "p31_rel_t2_24")
    public static class RelT2_24 extends DsTableAdapter {
        @DsField(name = "abc_id", length = 8) public Long abcId;
    }
    @DsTable(name = "p31_abc_bad1")
    static class AbcBad1 extends DsTableAdapter {
        @DsOneToOne(joinProp = "NO_SUCH_COL", props = "id")
        public RelT2_24 rel;
    }
    @DsTable(name = "p31_xyz_bad2")
    static class XyzBad2 extends DsTableAdapter {
        @DsField(name = "abc_id_str", length = 64) public String abcIdStr;
    }
    @DsTable(name = "p31_abc_bad2")
    static class AbcBad2 extends DsTableAdapter {
        @DsOneToOne(joinProp = "abcIdStr", props = "id")
        public XyzBad2 rel;
    }
    @DsTable(name = "p31_xyz_bad3")
    static class XyzBad3 extends DsTableAdapter {
        public Long abcId;
    }
    @DsTable(name = "p31_abc_bad3")
    static class AbcBad3 extends DsTableAdapter {
        @DsOneToOne(joinProp = "abcId", props = "id")
        public XyzBad3 rel;
    }

    // ===== TR2.5 独立表 =====
    @DsTable(name = "p31_main_t2_25")
    public static class MainT2_25 extends DsTableAdapter {
        @DsField(name = "name", length = 256) public String name;
    }
    @DsTable(name = "p31_rel_t2_25")
    public static class RelT2_25 extends DsTableAdapter {
        @DsField(name = "abc_id", length = 8) public Long abcId;
        @DsField(name = "price", length = 8) public Double price;
        @DsField(name = "qty", length = 8) public Long qty;
    }

    // ===== TR2.6 独立表 =====
    @DsTable(name = "p31_main_t2_26")
    public static class MainT2_26 extends DsTableAdapter {
        @DsField(name = "name", length = 256) public String name;
    }
    @DsTable(name = "p31_rel_t2_26")
    public static class RelT2_26 extends DsTableAdapter {
        @DsField(name = "abc_id", length = 8) public Long abcId;
        @DsField(name = "price", length = 8) public Double price;
        @DsField(name = "qty", length = 8) public Long qty;
    }
    @DsTable(name = "p31_tag_t2_26")
    public static class TagT2_26 extends DsTableAdapter {
        @DsField(name = "abc_id", length = 8) public Long abcId;
        @DsField(name = "tag", length = 64) public String tag;
    }

    @Test
    public void tr2_1_singleJoin_inBatchUnderThreshold() throws Exception {
        GenericManager<MainT2_21> mainGm = new GenericManager<>(MainT2_21.class);
        GenericManager<RelT2_21> relGm = new GenericManager<>(RelT2_21.class);
        Map<Class<?>, GenericManager<?>> relatedGms = new HashMap<>();
        relatedGms.put(RelT2_21.class, relGm);

        List<String> matchNames = new ArrayList<>();
        Set<Long> expectedIds = new LinkedHashSet<>();
        for (int i = 1; i <= 100; i++) {
            MainT2_21 e = new MainT2_21();
            e.name = "a" + i;
            mainGm.saveOrUpdate(e);
            Long mid = e.getId();
            matchNames.add(e.name);
            RelT2_21 r = new RelT2_21();
            r.abcId = mid; r.price = 10.0 * i; r.qty = (long) i;
            relGm.saveOrUpdate(r);
            expectedIds.add(mid);
        }
        for (int i = 101; i <= 200; i++) {
            MainT2_21 e = new MainT2_21();
            e.name = "b" + i;
            mainGm.saveOrUpdate(e);
            Long mid = e.getId();
            RelT2_21 r = new RelT2_21();
            r.abcId = mid + 9999L;
            r.price = 0.0; r.qty = 0L;
            relGm.saveOrUpdate(r);
        }

        QueryWrapper<MainT2_21> mainCond = new QueryWrapper<MainT2_21>();
        mainCond.in("name", matchNames);
        JoinSpec<RelT2_21> j = new JoinSpec<>(RelT2_21.class, "id", "abcId", null);
        List<JoinSpec<?>> joins = Collections.singletonList(j);
        Map<String, int[]> probes = new HashMap<>();

        Set<Long> result = JoinIdResolver.resolve(mainGm, mainCond, joins, relatedGms, probes);

        Assert.assertEquals(100, result.size());
        Assert.assertTrue(result.containsAll(expectedIds));
        Assert.assertEquals(1, probes.get("totalRelatedCalls")[0]);
    }

    @Test
    public void tr2_2_singleJoin_existsFallbackOverThreshold() throws Exception {
        GenericManager<MainT2_22> mainGm = new GenericManager<>(MainT2_22.class);
        GenericManager<RelT2_22> relGm = new GenericManager<>(RelT2_22.class);
        Map<Class<?>, GenericManager<?>> relatedGms = new HashMap<>();
        relatedGms.put(RelT2_22.class, relGm);
        Set<Long> expected = new LinkedHashSet<>();
        for (int i = 1; i <= 200; i++) {
            MainT2_22 e = new MainT2_22();
            e.name = "n" + i;
            mainGm.saveOrUpdate(e);
            Long mid = e.getId();
            RelT2_22 r = new RelT2_22();
            if (i % 2 == 0) {
                r.abcId = mid;
                expected.add(mid);
            } else {
                r.abcId = mid + 99999L;
            }
            r.price = (double) i; r.qty = (long) i;
            relGm.saveOrUpdate(r);
        }
        Map<String, int[]> probes = new HashMap<>();
        Set<Long> result = JoinIdResolver.resolve(mainGm, null, Collections.singletonList(
                new JoinSpec<>(RelT2_22.class, "id", "abcId", null)), relatedGms, probes);
        Assert.assertEquals(expected.size(), result.size());
        Assert.assertTrue(result.containsAll(expected));
        Assert.assertTrue(probes.get("totalRelatedCalls")[0] <= 2);
    }

    @Test
    public void tr2_3_twoJoins_andIntersection() throws Exception {
        GenericManager<MainT2_23> mainGm = new GenericManager<>(MainT2_23.class);
        GenericManager<RelT2_23> relGm = new GenericManager<>(RelT2_23.class);
        GenericManager<TagT2_23> tagGm = new GenericManager<>(TagT2_23.class);
        Map<Class<?>, GenericManager<?>> relatedGms = new HashMap<>();
        relatedGms.put(RelT2_23.class, relGm);
        relatedGms.put(TagT2_23.class, tagGm);
        List<Long> allMainIds = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            MainT2_23 e = new MainT2_23();
            e.name = "n" + i;
            mainGm.saveOrUpdate(e);
            allMainIds.add(e.getId());
        }
        Set<Long> xyzMatch = new LinkedHashSet<>();
        for (int i = 0; i < 50; i++) {
            RelT2_23 r = new RelT2_23();
            Long mid = allMainIds.get(i);
            r.abcId = mid; r.price = (double) i; r.qty = (long) i;
            relGm.saveOrUpdate(r);
            xyzMatch.add(mid);
        }
        Set<Long> tagMatch = new LinkedHashSet<>();
        for (int i = 24; i < 74; i++) {
            TagT2_23 t = new TagT2_23();
            Long mid = allMainIds.get(i);
            t.abcId = mid; t.tag = "t" + i;
            tagGm.saveOrUpdate(t);
            tagMatch.add(mid);
        }
        Set<Long> expected = new LinkedHashSet<>(xyzMatch);
        expected.retainAll(tagMatch);
        List<JoinSpec<?>> joins = Arrays.asList(
                new JoinSpec<>(RelT2_23.class, "id", "abcId", null),
                new JoinSpec<>(TagT2_23.class, "id", "abcId", null)
        );
        Set<Long> result = JoinIdResolver.resolve(mainGm, null, joins, relatedGms, new HashMap<>());
        Assert.assertEquals(expected.size(), result.size());
        Assert.assertEquals(expected, new LinkedHashSet<>(result));
    }

    @Test
    public void tr2_4_fromDsOneToOneAnnotations_reflection() throws Exception {
        int ok = 0;
        List<JoinSpec<?>> list = JoinSpec.fromDsOneToOneAnnotations(MainT2_24.class);
        Assert.assertEquals(1, list.size());
        JoinSpec<?> j = list.get(0);
        Assert.assertSame(RelT2_24.class, j.relatedClass);
        Assert.assertEquals("id", j.outerField);
        Assert.assertEquals("abcId", j.relatedField);

        try { JoinSpec.fromDsOneToOneAnnotations(AbcBad1.class); Assert.fail(); }
        catch (IllegalArgumentException e) { if (e.getMessage().contains("NO_SUCH_COL")) ok++; }
        try { JoinSpec.fromDsOneToOneAnnotations(AbcBad2.class); Assert.fail(); }
        catch (IllegalArgumentException e) { if (e.getMessage().contains("not Long")) ok++; }
        try { JoinSpec.fromDsOneToOneAnnotations(AbcBad3.class); Assert.fail(); }
        catch (IllegalArgumentException e) { if (e.getMessage().contains("@DsField")) ok++; }
        Assert.assertEquals(3, ok);
    }

    @Test
    public void tr2_5_relatedCond_filter() throws Exception {
        GenericManager<MainT2_25> mainGm = new GenericManager<>(MainT2_25.class);
        GenericManager<RelT2_25> relGm = new GenericManager<>(RelT2_25.class);
        Map<Class<?>, GenericManager<?>> relatedGms = new HashMap<>();
        relatedGms.put(RelT2_25.class, relGm);
        for (int i = 1; i <= 50; i++) {
            MainT2_25 e = new MainT2_25();
            e.name = "n" + i;
            mainGm.saveOrUpdate(e);
            Long mid = e.getId();
            RelT2_25 r = new RelT2_25();
            r.abcId = mid; r.price = (double) i; r.qty = (long) i;
            relGm.saveOrUpdate(r);
        }
        QueryWrapper<RelT2_25> subQ = new QueryWrapper<RelT2_25>();
        subQ.gt("price", 25.0);
        JoinSpec<RelT2_25> js = new JoinSpec<>(RelT2_25.class, "id", "abcId", subQ);
        Set<Long> result = JoinIdResolver.resolve(mainGm, null, Collections.singletonList(js), relatedGms, new HashMap<>());
        Assert.assertEquals(25, result.size());
    }

    @Test
    public void tr2_6_n1_hardGate_512xJ2() throws Exception {
        GenericManager<MainT2_26> mainGm = new GenericManager<>(MainT2_26.class);
        GenericManager<RelT2_26> relGm = new GenericManager<>(RelT2_26.class);
        GenericManager<TagT2_26> tagGm = new GenericManager<>(TagT2_26.class);
        Map<Class<?>, GenericManager<?>> relatedGms = new HashMap<>();
        relatedGms.put(RelT2_26.class, relGm);
        relatedGms.put(TagT2_26.class, tagGm);
        for (int i = 1; i <= 512; i++) {
            MainT2_26 e = new MainT2_26();
            e.name = "n" + i;
            mainGm.saveOrUpdate(e);
            Long mid = e.getId();
            RelT2_26 r = new RelT2_26();
            r.abcId = mid; r.price = 1.0; r.qty = 1L;
            relGm.saveOrUpdate(r);
            TagT2_26 t = new TagT2_26();
            t.abcId = mid; t.tag = "tag";
            tagGm.saveOrUpdate(t);
        }
        Map<String, int[]> probes = new HashMap<>();
        List<JoinSpec<?>> joins = Arrays.asList(
                new JoinSpec<>(RelT2_26.class, "id", "abcId", null),
                new JoinSpec<>(TagT2_26.class, "id", "abcId", null)
        );
        Set<Long> res = JoinIdResolver.resolve(mainGm, null, joins, relatedGms, probes);
        Assert.assertEquals(512, res.size());
        int calls = probes.get("totalRelatedCalls")[0];
        Assert.assertTrue("calls=" + calls + " > 4", calls <= 4);
    }
}
